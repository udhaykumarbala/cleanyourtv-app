package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #2402 (Xiaomi MiTV-MTEQ0, baymax, Android 10, PatchWall without Google's services, 0.4.6.1): the
// exact list xiaomi-mitv-mteq0 matched, Home resolved to PatchWall (com.mitv.tvhome/.MainActivityUserMode,
// the list's stock launcher), two apps the person installed claimed Home at 0, and Setup said
// UNKNOWN_HOME. That is still the answer, on purpose: the switch can hand Home back only to a home screen
// it knows (Google's or Amazon's, KnownLaunchers), so a list whose stock home is PatchWall has none, and
// this list's own notes say the firmware takes only system or allowlisted home screens (switching
// PatchWall off leaves FallbackHome, a black screen). Setup keeps PatchWall and plans the clean-up.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue2402SetupTest {
    private val source = BundledProfileSource()
    private val patchWall = Component(PackageName("com.mitv.tvhome"), ".MainActivityUserMode")
    private val fallbackHome = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val homeA = Component(PackageName("com.example.home.a"), ".Main")
    private val homeB = Component(PackageName("com.example.home.b"), ".Main")

    private fun facts() = Samples.miTvFacts().copy(
        brand = "xiaomi", manufacturer = "xiaomi", model = "MiTV-MTEQ0", device = "baymax", sdk = 29, release = "10",
        fingerprint = "xiaomi/baymax/baymax:10/QQ2A.200305.004.A1/3967:user/release-keys", hasGms = false,
    )

    private fun app(system: Boolean) =
        PackageState(EnabledState.ENABLED, system = system, versionCode = 1, signer = null, persistent = false, tvInput = false, label = null, installedByUs = false)

    private fun snapshot(reason: SnapshotReason) = Samples.snapshot(
        facts = facts(), reason = reason,
        packages = mapOf(
            PackageName("android") to app(true),
            fallbackHome.pkg to app(true),
            patchWall.pkg to app(true),
            PackageName("com.miui.tv.analytics") to app(true),
            PackageName("com.xiaomi.statistic") to app(true),
            homeA.pkg to app(false),
            homeB.pkg to app(false),
        ),
        home = HomeResolution(patchWall, listOf(HomeClaimant(patchWall, 0), HomeClaimant(homeA, 0), HomeClaimant(homeB, 0), HomeClaimant(fallbackHome, -1000))),
    )

    @Test fun issue2402PatchWallStaysAndTheCleanUpIsPlanned() = runTest(timeout = 60.seconds) {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = facts()
        h.prober.snapshotFor = { r -> snapshot(r) }
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.4.9")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        val m = SetupMachine(h.setupDeps(), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        val log = h.logLines.joinToString("\n")
        val found = assertIs<SetupState.Found>(m.state.value, log)
        assertTrue(h.logLines.any { it == "setup: home switch blocked UNKNOWN_HOME" }, log)
        assertTrue(found.plan.sections.isEmpty(), "no home screen switch")
        assertTrue(found.plan.steps.none { it.op is Op.SetHome || it.op is Op.InstallStream || it.op is Op.Install || it.op == Op.Disable(patchWall.pkg) }, log)
        // The list's switch-offs that are on the TV are planned: "0 to switch off" on the report was a TV without them.
        assertTrue(found.plan.steps.any { it.op == Op.Disable(PackageName("com.miui.tv.analytics")) }, log)
    }
}
