package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.FakeProber
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.9 (issues #1222, #4896): a list that plans nothing to switch off says why in the report. The
// counts come from the planner's own summary and the snapshot, so they match what What we found shows.
class ListRowsTest {
    private fun p(s: String) = PackageName(s)
    private fun state(e: EnabledState) = PackageState(e, false, 1, null, false, false, null, false)
    private val planner = DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), FileOverlayTransport(FakeProber()), "0.1.0")

    private val gone = p("com.example.gone")          // on the list, not on the TV
    private val asleep = p("com.example.asleep")      // switched off before (a TV cleaned before)
    private val ads = p("com.example.ads")            // on, the list switches it off
    private val tf1 = p("fr.tf1.mytf1")               // on, the list asks
    private val canal = p("com.canal.android.canal")  // on, the list asks, the person kept it
    private val home = Samples.stockLauncher.pkg      // the TV's own home screen: protected

    private val groups = listOf(
        PackageGroup("ads", "Adverts", GroupDefault.DISABLE, listOf(gone, asleep, ads, home).map { PackageEntry(it, null) }),
        PackageGroup("ask", "Streaming apps", GroupDefault.ASK, listOf(tf1, canal, ads).map { PackageEntry(it, null) }),
    )
    private val snapshot = Samples.snapshot(packages = Samples.miTvPackages() + mapOf(
        asleep to state(EnabledState.DISABLED_USER), ads to state(EnabledState.ENABLED), tf1 to state(EnabledState.ENABLED),
        canal to state(EnabledState.ENABLED), home to state(EnabledState.ENABLED),
    ) - gone)

    private fun summary(res: ProfileResolution, kept: Set<PackageName>): PlanSummary {
        val choices = Choices(kept, emptySet(), null, null, installLauncher = false, takeover = false, replaceLauncherSigner = false, keepDebuggingOn = false)
        val r = planner.plan(PlanRequest.Setup(res, choices, Samples.launcherArtifact(), Level.SIMPLE), snapshot, tv.cleanyour.testing.fixtures.MapFactsTable(), emptyList())
        return assertIs<PlanResult.Ok>(r, r.toString()).plan.summary
    }

    @Test fun eachRowOfTheListIsCountedOnceByWhatBecameOfIt() {
        val res = ProfileResolution.Exact(miProfile.copy(groups = groups))
        val kept = setOf(canal)
        val counts = ListRows.count(res, snapshot, summary(res, kept), kept)
        assertEquals(ListRows.Counts(off = 1, absent = 1, alreadyOff = 1, protected = 1, ask = 1, kept = 1), counts)
        assertEquals("list rows: 1 to switch off, 1 not on the TV, 1 already off, 1 protected, 1 ask, 1 kept", counts?.line)
    }

    // #4896 and #1222: every row off before, or not on the TV: "0 to switch off" explains itself.
    @Test fun aTvCleanedBeforeCountsItsRowsAlreadyOff() {
        val off = snapshot.copy(packages = snapshot.packages + mapOf(ads to state(EnabledState.DISABLED_USER)))
        val res = ProfileResolution.Exact(miProfile.copy(groups = groups.take(1)))
        val choices = Choices(emptySet(), emptySet(), null, null, installLauncher = false, takeover = false, replaceLauncherSigner = false, keepDebuggingOn = false)
        val plan = assertIs<PlanResult.Ok>(planner.plan(PlanRequest.Setup(res, choices, Samples.launcherArtifact(), Level.SIMPLE), off, tv.cleanyour.testing.fixtures.MapFactsTable(), emptyList())).plan
        val counts = ListRows.count(res, off, plan.summary, emptySet())
        assertEquals(ListRows.Counts(off = 0, absent = 1, alreadyOff = 2, protected = 1, ask = 0, kept = 0), counts)
    }

    @Test fun anUnlistedTvHasNoLine() {
        val res = ProfileResolution.None(listOf(Samples.stockLauncher))
        assertNull(ListRows.count(res, snapshot, PlanSummary(emptyList(), emptyList(), emptyList(), false, false), emptySet()))
    }

    // The line reaches the log (and so the report), once per change, never with a package name.
    @Test fun setupLogsTheLineAfterThePlan() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.profiles.resolver = { _, _ -> ProfileResolution.Exact(miProfile.copy(groups = listOf(PackageGroup("ads", "Adverts", GroupDefault.DISABLE, listOf(PackageEntry(adPkg, null)))))) }
        val m = h.setup(backgroundScope)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        assertIs<SetupState.Found>(m.state.value)
        val lines = h.logLines.filter { it.startsWith("setup: list rows: ") }
        assertEquals(1, lines.size, h.logLines.joinToString("\n"))
        assertTrue(lines.single().matches(Regex("setup: list rows: \\d+ to switch off, \\d+ not on the TV, \\d+ already off, \\d+ protected, \\d+ ask, \\d+ kept")), lines.single())
    }
}
