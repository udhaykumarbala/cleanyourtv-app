package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Issue114
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #114 (0.3.4, also #45 and #390): the TCL Smart TV Pro (BeyondTV4) on its Google TV build AR11.
// The log said "profile exact tcl-65c725", "setup wizard and saved Wi-Fi: wizard_owns" and "home switch
// blocked UNKNOWN_HOME", and the run switched off the Android TV list's clean-up. Setup now resolves
// with the snapshot's Home: the TCL brand set, and the switch blocked for the wizard that saved the Wi-Fi.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue114SetupTest {
    private val wizardOwns = SavedWifi(true, 1, setOf("${Issue114.wizard.value}:10031"))   // "setup wizard and saved Wi-Fi: wizard_owns"
    private val source = BundledProfileSource()

    private fun harness(): Harness {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = Issue114.facts()
        h.prober.snapshotFor = { r -> Issue114.snapshot(r) }
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.3.7")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        return h
    }

    private fun machine(h: Harness, scope: kotlinx.coroutines.CoroutineScope) = SetupMachine(h.setupDeps(savedWifi = { wizardOwns }), scope, SetupStart.Fresh)

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun issue114SetupUsesTheTclBrandSetAndLeavesTheRemoteKeysOn() = runTest(timeout = 60.seconds) {
        val h = harness()
        // The record 0.3.4 to 0.3.7 kept for the TV: the Android TV list.
        h.tvs.upsert(Samples.tv(facts = Issue114.facts()).copy(profileId = "tcl-65c725"))
        val m = machine(h, backgroundScope)
        toFound(h, m)
        val log = h.logLines.joinToString("\n")
        val found = assertIs<SetupState.Found>(m.state.value, log)
        assertTrue(h.logLines.any { it.startsWith("setup: probe done") && it.contains("profile brand set TCL, tv TCL Smart TV Pro (BeyondTV4) sdk 30") }, log)
        assertTrue(h.logLines.any { it == "setup: home switch blocked WIZARD_WIFI" }, log)
        assertTrue(h.logLines.none { it.contains("tcl-65c725") }, log)
        val off = found.plan.steps.mapNotNull { (it.op as? Op.Disable)?.pkg }.toSet()
        assertTrue(off.isNotEmpty(), log)
        for (p in Issue114.keptByTheGoogleTvList + Issue114.wizard + Issue114.launcherx.pkg) assertTrue(p !in off, p.value)
        assertTrue(found.plan.sections.isEmpty(), "no home screen switch")
        assertTrue(m.extras.value.unknownModel)
        assertNull(h.tvs.tvs.value.single().profileId)
    }

    // Manage, before the TV is connected again: the kept record names the Android TV list, the latest
    // snapshot shows Google TV's home screen, so Manage leaves the list out.
    @Test fun issue114ManageLeavesAnOldRecordsAndroidTvListOut() = runTest(timeout = 60.seconds) {
        val h = harness()
        val tv = Samples.tv(facts = Issue114.facts()).copy(profileId = "tcl-65c725")
        h.tvs.upsert(tv)
        val d = h.manageDeps(profiles = source)
        assertEquals("tcl-65c725", d.profileOf(tv)?.id)   // no snapshot stored yet: the record stands
        h.snapshots.save(Issue114.snapshot())
        assertNull(d.profileOf(tv))
        // The same record on the Android TV set it was written for keeps the list.
        h.snapshots.save(Samples.snapshot(facts = Issue114.facts(), takenAtMs = Samples.NOW_MS + 1))
        assertEquals("tcl-65c725", d.profileOf(tv)?.id)
    }
}
