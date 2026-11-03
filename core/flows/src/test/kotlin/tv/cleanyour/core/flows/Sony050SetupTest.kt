package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.HomeSwitchRisk
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.5.0, from the TV reports of the issues (the Diagnostics logs left out):
// - Sony's Android 9 sets (#5197 on sony-xbr-65x900f, #5168 on sony-bravia-4k-gb, #4372 on the Sony brand set),
//   whose only claimant next to tvlauncher is TV Settings' own FallbackHome under Sony's class name. Their
//   exact lists switch again (VENDOR_HOME was a false block); the brand set keeps its wizard rule in a release.
// - The BRAVIA VH21 (#4815's claimants): its list keeps the setup wizard on (never-disable at d7b0d4a, it owns
//   the saved Wi-Fi and casting needs it), so a release skips the switch, and a test build offers the Home
//   button helper there instead (RFC-HELPER-1).
// - The Panasonic 4KTV-0SU (#5195): its wizard claims Home (priority 4), so no switch; the test build offers the helper.
@OptIn(ExperimentalCoroutinesApi::class)
class Sony050SetupTest {
    private val notOwner = SavedWifi(true, 1, setOf("android.uid.system:1000"))
    private val wizard = PackageName("com.google.android.tungsten.setupwraith")

    private fun TestScope.found(report: ReportText.Parsed, wifi: SavedWifi = notOwner, homeHelper: Boolean = false,
                                onPlan: (Plan) -> Unit = {}): Pair<Harness, SetupMachine> {
        val snap = report.snapshot
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = report.facts
        h.prober.snapshotFor = { reason -> snap.copy(reason = reason) }
        val source = BundledProfileSource()
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home, packages = snap.packages) }
        val planner = DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.5.0", homeHelper = homeHelper)
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()).also { if (request is PlanRequest.Setup && it is PlanResult.Ok) onPlan(it.plan) } }
        val m = SetupMachine(h.setupDeps(savedWifi = { wifi }, homeHelper = homeHelper), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        return h to m
    }

    private inline fun <T> rule(anyBrand: Boolean, block: () -> T): T {
        val before = HomeSwitchRisk.anyBrandForTests
        HomeSwitchRisk.anyBrandForTests = anyBrand
        try { return block() } finally { HomeSwitchRisk.anyBrandForTests = before }
    }

    @Test fun theSonyExactListsSwitchAgainNextToTvSettingsOwnFallbackHome() = runTest(timeout = 60.seconds) {
        for ((file, id) in listOf("problem-5197.txt" to "sony-xbr-65x900f", "problem-5168.txt" to "sony-bravia-4k-gb")) {
            val report = ReportText.parse(ReportText.fixture(file))
            assertEquals("exact $id", report.profileLine)
            assertEquals("blocked VENDOR_HOME", report.homeLine, "what 0.4.7 said")
            assertEquals(listOf("com.google.android.tvlauncher/.MainActivity" to 2, "com.android.tv.settings/com.sony.dtv.settings.system.FallbackHome" to -1000),
                report.snapshot.home.claimants.map { it.component.flat to it.priority })
            for (anyBrand in listOf(false, true)) rule(anyBrand) {
                var plan: Plan? = null
                val (h, m) = found(report) { plan = it }
                assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
                assertEquals(HomeBlock.NONE, m.extras.value.homeBlock, "$file anyBrand=$anyBrand")
                assertTrue(m.extras.value.homePossible, "$file anyBrand=$anyBrand")
                assertTrue("setup: home switch planned" in h.logLines, h.logLines.joinToString("\n"))
                val p = assertNotNull(plan)
                val ops = p.steps.map { it.op }
                assertEquals(listOf(DefaultPlanner.SECTION_HOME), p.sections.map { it.name }, "$file anyBrand=$anyBrand")
                assertTrue(ops.any { it is Op.SetHome } && Op.Disable(PackageName("com.google.android.tvlauncher")) in ops, "$file: $ops")
            }
        }
    }

    @Test fun theSonyBrandSetIsNoLongerAVendorHomeAndKeepsItsWizardRuleInARelease() = runTest(timeout = 60.seconds) {
        val report = ReportText.parse(ReportText.fixture("tv-4372.txt"))
        assertEquals("brand set Sony", report.profileLine)
        assertEquals("blocked VENDOR_HOME", report.homeLine, "what 0.4.7 said")
        rule(anyBrand = false) {
            val (h, m) = found(report)
            assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
            assertEquals(HomeBlock.WIZARD_STAYS_ON, m.extras.value.homeBlock, "no Sony row for Android 9 in a release: the wizard's rule")
            assertFalse(m.extras.value.homePossible)
        }
    }

    @Test fun theVh21SkipsTheSwitchInAReleaseAndOffersTheHelperInATestBuild() = runTest(timeout = 60.seconds) {
        val report = ReportText.parse(ReportText.fixture("problem-4815.txt"))
        assertEquals("exact sony-bravia-4k-vh21", report.profileLine)
        val profile = BundledProfileSource.bundled.profiles.single { it.id == "sony-bravia-4k-vh21" }
        assertTrue(wizard in profile.neverDisable, "upstream keeps the wizard on at d7b0d4a")
        for (homeHelper in listOf(false, true)) {
            var plan: Plan? = null
            val (h, m) = found(report, homeHelper = homeHelper) { plan = it }
            assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
            assertEquals(HomeBlock.WIZARD_STAYS_ON, m.extras.value.homeBlock, "homeHelper=$homeHelper")
            assertFalse(m.extras.value.homePossible)
            assertEquals(homeHelper, m.extras.value.helperOffer, "the helper in the test build only")
            assertNull(m.extras.value.helperWhyNot, "homeHelper=$homeHelper")
            m.dispatch(SetupIntent.HomeHelper(true)); settle()
            val req = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
            assertEquals(homeHelper, req.choices.homeHelper)
            assertFalse(req.choices.takeover)
            val p = assertNotNull(plan)
            val ops = p.steps.map { it.op }
            assertTrue(ops.none { it is Op.SetHome || (it is Op.Disable && it.pkg.value == "com.google.android.apps.tv.launcherx") }, "no switch: $ops")
            assertTrue(ops.none { (it is Op.Disable && it.pkg == wizard) || (it is Op.Enable && it.pkg == wizard) }, "the wizard is left as it is: $ops")
            if (!homeHelper) { assertTrue(p.sections.isEmpty()); continue }
            assertEquals(listOf(DefaultPlanner.SECTION_HELPER), p.sections.map { it.name })
            assertTrue(ops.any { it is Op.AccessibilityAdd }, "$ops")
            assertTrue("setup: home switch redirect" in h.logLines, h.logLines.joinToString("\n"))
        }
        // The same with the TV saying the wizard saved the Wi-Fi (the Wi-Fi units): the Wi-Fi block, the helper still offered.
        val owner = SavedWifi(true, 1, setOf(wizard.value))
        val (_, m) = found(report, wifi = owner, homeHelper = true)
        assertEquals(HomeBlock.WIZARD_WIFI, m.extras.value.homeBlock)
        assertTrue(m.extras.value.helperOffer)
    }

    @Test fun thePanasonicWhoseWizardClaimsHomeGetsTheHelperInATestBuild() = runTest(timeout = 60.seconds) {
        val report = ReportText.parse(ReportText.fixture("problem-5195.txt"))
        assertEquals("exact panasonic-4ktv-0su", report.profileLine)
        for (homeHelper in listOf(false, true)) {
            val (h, m) = found(report, homeHelper = homeHelper)
            assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
            assertEquals(HomeBlock.WIZARD_STAYS_ON, m.extras.value.homeBlock)
            assertEquals(homeHelper, m.extras.value.helperOffer)
        }
    }
}
