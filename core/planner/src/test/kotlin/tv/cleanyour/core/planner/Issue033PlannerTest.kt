package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlannerFixtures.artifact
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.facts
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.Issue033
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.3.3: the planner and the engine ask one rule what may be switched off (SwitchOffRule), and the
// wizard asks the planner's own StockHome whether there is a home screen to hand Home back to. From
// the facts of issues #10 (Xiaomi TV Box S, jaws, Google TV 14) and #8 (TCL G07, Google TV 11).
class Issue033PlannerTest {
    private val source = BundledProfileSource()

    private fun stocksOf(r: ProfileResolution): List<Component> = when (r) {
        is ProfileResolution.Exact -> r.profile.stockLaunchers
        is ProfileResolution.BrandSureSet -> r.stockLaunchers
        is ProfileResolution.None -> r.stockLaunchers
    }

    private fun setup(r: ProfileResolution, takeover: Boolean, extraOff: Set<PackageName> = emptySet()) =
        PlanRequest.Setup(r, choices(installLauncher = takeover, takeover = takeover).copy(extraOff = extraOff), artifact(), Level.SIMPLE)

    // Every switch-off of a plan passes the engine's rule for the same facts: outside the home screen
    // switch as a plain switch-off, inside it as part of the switch. This is what the engine checks
    // again with live facts just before each step.
    private fun assertTheEngineWouldAllowEverySwitchOff(plan: Plan, snapshot: Snapshot, profile: Profile?, stocks: List<Component>) {
        for (s in plan.steps.filter { it.op is Op.Disable }) {
            val pkg = (s.op as Op.Disable).pkg
            val inSection = plan.sections.any { s.index in it.from..it.to }
            assertNull(SwitchOffRule.reason(pkg, snapshot.packages[pkg], profile, snapshot, stocks, inHomeSwitch = inSection), "${pkg.value} at step ${s.index}")
        }
    }

    // #10: Android 14 takes the box out of the 2nd gen profile (Android 11 only), so it gets the
    // Xiaomi brand sure set. PatchWall is in it and nothing in the facts forbids switching it off: it
    // is not HOME, not a HOME claimant, not persistent and not an input, so the planner plans it and
    // the engine's live rule agrees. (On this box the firmware answers "yes" and keeps it on; that is
    // the engine's skip, Issue10Test.) The home screens and the wizard are never in the plan.
    @Test fun issue10TheXiaomiSureSetPlanHoldsOnlyWhatTheEngineAllows() {
        val snap = Issue033.jawsSnapshot()
        val r = source.resolveNow(snap.facts)
        val sure = assertIs<ProfileResolution.BrandSureSet>(r, "no exact profile for jaws on Android 14")
        assertEquals("Xiaomi", sure.brand)
        assertTrue(Issue033.launcherx in sure.stockLaunchers)
        // Issue #111: PatchWall is an ask row of the Xiaomi set, kept on unless the owner chooses it.
        val kept = (planner().plan(setup(r, takeover = false), snap, facts, emptyList()) as PlanResult.Ok).plan
        assertTrue(kept.steps.none { it.op == Op.Disable(Issue033.patchwall) })
        val row = kept.summary.groups.flatMap { it.rows }.single { it.pkg == Issue033.patchwall }
        assertTrue(!row.off && "the remote's PatchWall button stops working while the TV's own home screen is in use" in row.description, row.toString())
        val plan = (planner().plan(setup(r, takeover = false, extraOff = setOf(Issue033.patchwall)), snap, facts, emptyList()) as PlanResult.Ok).plan
        val off = plan.steps.mapNotNull { (it.op as? Op.Disable)?.pkg }
        assertTrue(Issue033.patchwall in off, "chosen in Customise, PatchWall goes off: $off")
        assertTrue(plan.steps.filter { it.op is Op.Disable }.all { it.onFailure == tv.cleanyour.core.ops.FailurePolicy.SKIP_IF_REFUSED })
        for (p in listOf(Issue033.launcherx.pkg, Issue033.recovery.pkg, Issue033.fallbackHome.pkg)) assertTrue(p !in off, p.value)
        assertTheEngineWouldAllowEverySwitchOff(plan, snap, null, stocksOf(r))
        // PatchWall as HOME or a HOME claimant (a Mi TV whose own home it is) is never planned.
        val asHome = Issue033.jawsSnapshot().let { s -> s.copy(home = s.home.copy(claimants = s.home.claimants +
            tv.cleanyour.core.model.HomeClaimant(Component(Issue033.patchwall, ".MainActivity"), 0))) }
        val plan2 = (planner().plan(setup(r, takeover = false, extraOff = setOf(Issue033.patchwall)), asHome, facts, emptyList()) as PlanResult.Ok).plan
        assertTrue(plan2.steps.none { it.op == Op.Disable(Issue033.patchwall) })
        assertEquals(DefaultPlanner.REASON_HOME_SCREEN, plan2.summary.groups.flatMap { it.rows }.single { it.pkg == Issue033.patchwall }.whyProtected)
    }

    // #8: the exact profile knows launcherx as the stock home, but it is switched off and Home answers
    // to the person's own launcher. StockHome finds no home screen to hand Home back to and says the
    // home screen was changed by the person; asked for the switch anyway the planner still refuses
    // (the wizard no longer asks, SetupMachineTest), and the clean-up alone is a plan.
    @Test fun issue8TheHomeScreenWasChangedSoTheCleanUpAloneIsPlanned() {
        val snap = Issue033.g07Snapshot()
        val r = source.resolveNow(snap.facts)
        val exact = assertIs<ProfileResolution.Exact>(r)
        assertEquals("tcl-g07-google-tv", exact.profile.id)
        assertNull(StockHome.of(snap, exact.profile.stockLaunchers))
        assertTrue(StockHome.changedByPerson(snap, exact.profile.stockLaunchers))
        assertTrue(planner().plan(setup(r, takeover = true), snap, facts, emptyList()) is PlanResult.Refused)
        val plan = assertIs<PlanResult.Ok>(planner().plan(setup(r, takeover = false), snap, facts, emptyList())).plan
        assertTrue(plan.sections.isEmpty())
        assertTrue(plan.steps.none { it.op is Op.SetHome || it.op is Op.Install || it.op is Op.InstallStream })
        assertTrue(plan.steps.none { it.op == Op.Disable(Issue033.personsLauncher.pkg) })
        assertTheEngineWouldAllowEverySwitchOff(plan, snap, exact.profile, exact.profile.stockLaunchers)
        // The reference TV, stock home in charge: nothing changed by the person.
        val ref = PlannerFixtures.snapshot()
        assertNotNull(StockHome.of(ref, listOf(PlannerFixtures.stock)))
        assertTrue(!StockHome.changedByPerson(ref, listOf(PlannerFixtures.stock)))
    }

    // A home screen app the person installed is never switched off: not by Customise, not by
    // Manage's switch-off, and not inside the home screen switch either.
    @Test fun aLauncherThePersonInstalledIsNeverSwitchedOff() {
        val snap = Issue033.g07Snapshot()
        val pkg = Issue033.personsLauncher.pkg
        val profile = (source.resolveNow(snap.facts) as ProfileResolution.Exact).profile
        for (inSwitch in listOf(false, true)) {
            assertEquals(SwitchOffRule.REASON_YOUR_LAUNCHER, SwitchOffRule.reason(pkg, snap.packages[pkg], profile, snap, profile.stockLaunchers, inHomeSwitch = inSwitch))
        }
        val custom = PlanRequest.Setup(ProfileResolution.Exact(profile), choices(extraOff = setOf(pkg), installLauncher = false, takeover = false), artifact(), Level.CUSTOM)
        val refused = assertIs<PlanResult.Refused>(planner().plan(custom, snap, facts, emptyList())).reasons
        assertTrue(refused.single().contains(SwitchOffRule.REASON_YOUR_LAUNCHER), refused.toString())
        val manage = assertIs<PlanResult.Refused>(planner().plan(PlanRequest.SwitchOff(setOf(pkg)), snap, facts, emptyList())).reasons
        assertTrue(manage.single().contains(SwitchOffRule.REASON_YOUR_LAUNCHER), manage.toString())
        // A system launcher that is a claimant is the TV's own: only the switch may change it.
        assertEquals(DefaultPlanner.REASON_HOME_SCREEN, SwitchOffRule.reason(Issue033.launcherx.pkg, null, null, Issue033.jawsSnapshot(), emptyList()))
        assertNull(SwitchOffRule.reason(Issue033.launcherx.pkg, null, null, Issue033.jawsSnapshot(), emptyList(), inHomeSwitch = true))
    }

    // The rule holds on the reference TV's takeover too: the stock launcher and the wizard inside
    // the switch, the debloat list outside it.
    @Test fun theReferencePlanWithTheSwitchHoldsOnlyWhatTheEngineAllows() {
        val profile = PlannerFixtures.profile(quirks = setOf(tv.cleanyour.core.model.Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = PlannerFixtures.wizard)
        val snap = PlannerFixtures.snapshot()
        val plan = PlannerFixtures.plan(PlannerFixtures.setup(profile), snap)
        assertTrue(plan.sections.isNotEmpty())
        assertTrue(plan.steps.any { it.op == Op.Disable(PlannerFixtures.stock.pkg) })
        assertTheEngineWouldAllowEverySwitchOff(plan, snap, profile, profile.stockLaunchers)
        // Outside the switch the same two are refused, as the engine refuses them.
        assertEquals(DefaultPlanner.REASON_HOME_SCREEN, SwitchOffRule.reason(PlannerFixtures.stock.pkg, snap.packages[PlannerFixtures.stock.pkg], profile, snap, profile.stockLaunchers))
        assertEquals(DefaultPlanner.REASON_WIZARD_SECTION_ONLY, SwitchOffRule.reason(PlannerFixtures.wizard, snap.packages[PlannerFixtures.wizard], profile, snap, profile.stockLaunchers))
    }

    // Our own new home screen as Home is never "a home screen app you installed": the probe reads
    // installedByUs false for every package, so the rule names our launcher. On the G07 with
    // launcherx switched off, that is a re-run of Setup, never a home screen changed by the person.
    @Test fun ourOwnLauncherIsNeverThePersonsLauncher() {
        val g07 = Issue033.g07Snapshot()
        val ours = Component(SwitchOffRule.OUR_LAUNCHER, ".MainActivity")
        val snap = g07.copy(
            packages = g07.packages - Issue033.personsLauncher.pkg +
                (ours.pkg to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, false, 1, null, false, false, null, installedByUs = false)),
            home = tv.cleanyour.core.model.HomeResolution(ours, listOf(tv.cleanyour.core.model.HomeClaimant(ours, 0))),
        )
        val stocks = stocksOf(source.resolveNow(snap.facts))
        kotlin.test.assertFalse(SwitchOffRule.isPersonsLauncher(ours.pkg, snap))
        kotlin.test.assertFalse(StockHome.changedByPerson(snap, stocks))
        assertEquals(DefaultPlanner.REASON_HOME_SCREEN, SwitchOffRule.reason(ours.pkg, snap.packages[ours.pkg], null, snap, stocks))
        // The person's own launcher in the same place still is theirs.
        assertTrue(SwitchOffRule.isPersonsLauncher(Issue033.personsLauncher.pkg, g07))
    }
}
