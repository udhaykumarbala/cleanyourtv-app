package tv.cleanyour.core.planner

import kotlinx.coroutines.runBlocking
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlannerFixtures.artifact
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Issue114
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #114 (0.3.4, also #45 and #390): a TCL Smart TV Pro (BeyondTV4) on the Google TV build AR11
// matched the Android TV list tcl-65c725, whose stock launcher (tvlauncher) it does not have, so the
// home screen switch was blocked (UNKNOWN_HOME) but the clean-up ran from that list, com.tcl.keyhelp
// (the remote's virtual keys, which the Google TV list for the set leaves on) among it. With the
// snapshot's Home the set now resolves to the TCL brand set, like any TCL TV no list knows.
class Issue114PlannerTest {
    private val source = BundledProfileSource()
    private val facts = BundledFactsTable()
    private fun planner() = DefaultPlanner(PlannerFixtures.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.3.7")

    // What Setup asks for on this TV: the clean-up, no home screen switch (the switch is blocked).
    private fun setup(r: ProfileResolution) =
        PlanRequest.Setup(r, choices(installLauncher = false, takeover = false, wizardWifi = WizardWifi.OWNER), artifact(), Level.SIMPLE)

    private fun switchedOff(r: ProfileResolution): Set<PackageName> {
        val plan = assertIs<PlanResult.Ok>(planner().plan(setup(r), Issue114.snapshot(), facts, emptyList())).plan
        return plan.steps.mapNotNull { (it.op as? Op.Disable)?.pkg }.toSet()
    }

    // The report's clean-up, from the facts alone (0.3.4 to 0.3.7): the Android TV list's.
    @Test fun issue114FromTheFactsAloneTheAndroidTvListSwitchedOffTheRemoteKeys() {
        val r = assertIs<ProfileResolution.Exact>(source.resolveNow(Issue114.snapshot().facts))
        assertEquals("tcl-65c725", r.profile.id)
        val off = switchedOff(r)
        for (p in Issue114.keptByTheGoogleTvList) assertTrue(p in off, p.value)
    }

    @Test fun issue114WithItsHomeTheSetGetsTheTclBrandSet() = runBlocking<Unit> {
        val snap = Issue114.snapshot()
        val r = assertIs<ProfileResolution.BrandSureSet>(source.resolveFor(snap))
        assertEquals("TCL", r.brand)
        val off = switchedOff(r)
        assertTrue(off.isNotEmpty())
        // Only the brand set's own switch-offs, and none of what the Google TV list leaves on.
        val brandOff = r.sureSet.flatMap { g -> g.packages.map { it.name } }.toSet()
        assertTrue(brandOff.containsAll(off), (off - brandOff).toString())
        for (p in Issue114.keptByTheGoogleTvList + Issue114.wizard + Issue114.launcherx.pkg) assertFalse(p in off, p.value)
        // The home screen switch stays off, now for the wizard that saved the Wi-Fi and claims Home.
        assertEquals(HomeSwitchRisk.Risk.WIZARD_WIFI, HomeSwitchRisk.of(snap.facts, null, snap, WizardWifi.OWNER))
        assertEquals(HomeSwitchRisk.Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(snap.facts, null, snap, WizardWifi.NOT_OWNER))
    }
}
