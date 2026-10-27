package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #1119 (kjntl007, Xiaomi MiTV4I magnolia, Android 9): 0.4.6-beta2 blocked the switch
// WIZARD_STAYS_ON because a home screen app the person installed claims Home (priority 0) next to
// tvlauncher (2) and FallbackHome. The packages are the TV report of the issue (0.4.0), the Home
// and what this app switched off since are the newest problem report's (0.4.6-beta2). Setup now
// plans the switch with their app left installed and on.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1119SetupTest {
    private fun snapshot(): Pair<ReportText.Parsed, tv.cleanyour.core.model.Snapshot> {
        val tvReport = ReportText.parse(ReportText.fixture("tv-1119.txt"))
        val problem = ReportText.parse(ReportText.fixture("problem-1119.txt"))
        val off = problem.switchedOff.associateWith { p -> tvReport.snapshot.packages.getValue(p).copy(enabled = EnabledState.DISABLED_USER) }
        val theirs = problem.personHomes.associateWith { problem.snapshot.packages.getValue(it) }
        val packages = tvReport.snapshot.packages + off + theirs
        val snap = tvReport.snapshot.copy(packages = packages, home = problem.snapshot.home, facts = problem.facts)
        return problem to snap
    }

    @Test fun theReportIsReadAsTheAppMadeIt() {
        val (problem, snap) = snapshot()
        assertEquals("candidate xiaomi-mitv4i-magnolia", problem.profileLine)
        assertEquals("blocked WIZARD_STAYS_ON", problem.homeLine)
        assertEquals(listOf("com.google.android.tvlauncher" to 2, "person.home1" to 0, "com.android.tv.settings" to -1000),
            snap.home.claimants.map { it.component.pkg.value to it.priority })
        assertEquals(false, snap.packages.getValue(PackageName("person.home1")).system)
        assertEquals(EnabledState.DEFAULT, snap.packages.getValue(PackageName("com.google.android.tungsten.setupwraith")).enabled)
    }

    private fun kotlinx.coroutines.test.TestScope.found(problem: ReportText.Parsed, snap: tv.cleanyour.core.model.Snapshot, wifi: SavedWifi,
                                                      onPlan: (tv.cleanyour.core.planner.Plan) -> Unit = {}): Pair<Harness, SetupMachine> {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = problem.facts
        h.prober.snapshotFor = { reason -> snap.copy(reason = reason) }
        val source = BundledProfileSource()
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home, packages = snap.packages) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.4.6")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()).also { if (request is PlanRequest.Setup && it is PlanResult.Ok) onPlan(it.plan) } }
        val m = SetupMachine(h.setupDeps(savedWifi = { wifi }), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        return h to m
    }

    // beta3: the same TV with that app listed as a system app (as a vendor home ships): R0 cannot take
    // it, and the reason told is the other home screen app, not the setup wizard.
    @Test fun anotherHomeAppR0CannotTakeIsTheReasonTold() = runTest(timeout = 60.seconds) {
        val (problem, base) = snapshot()
        val theirs = PackageName("person.home1")
        val snap = base.copy(packages = base.packages + (theirs to base.packages.getValue(theirs).copy(system = true)))
        releaseRule {
            for (wifi in listOf(SavedWifi(true, 1, setOf("android.uid.system:1000")), SavedWifi(true, 1, setOf("com.google.android.tungsten.setupwraith:10010")))) {
                val (h, m) = found(problem, snap, wifi)
                assertEquals(HomeBlock.OTHER_HOME, m.extras.value.homeBlock, "${wifi.owners}")
                assertEquals(HomeDecision(false, "OTHER_HOME", profile = "brand set Xiaomi"), HomeDecisions(h.documents).load(snap.tvId))
                assertTrue("setup: home switch blocked OTHER_HOME" in h.logLines, h.logLines.joinToString("\n"))
            }
        }
    }

    // Review of beta3: Home already answering to their app is their choice. It stays: no switch, and
    // the reason told is the other home screen app.
    @Test fun homeAnsweringToTheirAppKeepsTheirChoice() = runTest(timeout = 60.seconds) {
        val (problem, base) = snapshot()
        val theirs = tv.cleanyour.core.model.Component(PackageName("person.home1"), ".Main")
        val snap = base.copy(home = base.home.copy(resolved = theirs))
        releaseRule {
            var plan: tv.cleanyour.core.planner.Plan? = null
            val (h, m) = found(problem, snap, SavedWifi(true, 1, setOf("android.uid.system:1000"))) { plan = it }
            assertEquals(HomeBlock.OTHER_HOME, m.extras.value.homeBlock)
            assertTrue("setup: home switch blocked OTHER_HOME" in h.logLines, h.logLines.joinToString("\n"))
            assertTrue(plan!!.sections.isEmpty() && plan!!.steps.none { it.op is Op.SetHome || (it.op is Op.Disable && (it.op as Op.Disable).pkg == theirs.pkg) })
        }
    }

    @Test fun setupPlansTheSwitchAndLeavesTheirHomeAppAlone() = runTest(timeout = 60.seconds) {
        val (problem, snap) = snapshot()
        releaseRule {
            for (wifi in listOf(SavedWifi(true, 1, setOf("android.uid.system:1000")), SavedWifi(true, 1, setOf("com.google.android.tungsten.setupwraith:10010")))) {
                val h = Harness(facts = BundledFactsTable())
                h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
                h.prober.facts = problem.facts
                h.prober.snapshotFor = { reason -> snap.copy(reason = reason) }
                val source = BundledProfileSource()
                h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home, packages = snap.packages) }
                val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.4.6")
                var plan: tv.cleanyour.core.planner.Plan? = null
                h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()).also { if (request is PlanRequest.Setup && it is PlanResult.Ok) plan = it.plan } }
                val m = SetupMachine(h.setupDeps(savedWifi = { wifi }), backgroundScope, SetupStart.Fresh)
                h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
                settle()
                m.dispatch(SetupIntent.Start)
                settle()
                h.discovery.open5555[HOST] = true
                settle(2_100)
                settle(700)
                assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
                assertEquals(HomeBlock.NONE, m.extras.value.homeBlock, "${wifi.owners}")
                val ops = plan!!.steps.map { it.op }
                val theirs = PackageName("person.home1")
                assertTrue(ops.none { (it is Op.Disable && it.pkg == theirs) || (it is Op.Uninstall && it.pkg == theirs) }, "their app stays")
                assertTrue(ops.any { it is Op.SetHome && it.component.pkg == Launcher.pkg } && ops.any { it is Op.VerifyHome && it.expected.pkg == Launcher.pkg })
                assertTrue(theirs in plan!!.sections.single().rollbackIfFocus)
            }
        }
    }
}
