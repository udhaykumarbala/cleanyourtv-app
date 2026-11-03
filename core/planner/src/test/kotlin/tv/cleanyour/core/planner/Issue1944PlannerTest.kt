package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.releaseRule
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ProfileResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #1944 (Xiaomi MiTV-MOOQ0, croods, Android 10, 0.4.6): Home resolved to
// com.duokan.factorytest/.FactoryLauncher, with that factory test app (priority 0) and Settings'
// FallbackHome its only claimants: the TV's own home screen was off and the new one gone. The MOOQ0
// list switches the factory test app off, and its header says to do that before the TV's own home
// screen, but every report of the model (#697, #800, #1098, #1492, #1944) shows it still on: as a
// Home claimant, SwitchOffRule kept it as "the TV's own home screen", and the switch only took the
// stock home off. Since then the list's line takes it off while Home answers to a real home screen,
// the switch takes it off first on a TV whose list does not, and it is never a home screen to go
// back to (HomePlaceholder.isFactoryHome).
class Issue1944PlannerTest {
    private val factory = Component(PackageName("com.duokan.factorytest"), ".FactoryLauncher")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val tclFactory = Component(PackageName("com.tcl.factory.view"), ".tv.FactoryTvActivity")
    private fun sys(label: String) = PackageState(EnabledState.ENABLED, true, 1, null, false, false, label, false)
    private val extra = mapOf(factory.pkg to sys("Factory test"), tclFactory.pkg to sys("Factory view"))

    // A MOOQ0 before the switch: tvlauncher (2), the factory test app (0) and FallbackHome claim Home.
    private fun before(home: Component? = stock, claimants: List<Component> = listOf(stock, factory, fallback)) =
        snapshot(home = home, claimants = claimants, extra = extra)

    private val mooq0 = (BundledProfileSource().resolveNow(tv.cleanyour.testing.fixtures.Samples.miTvFacts().copy(
        brand = "Xiaomi", manufacturer = "Xiaomi", model = "MiTV-MOOQ0", device = "croods", sdk = 29)) as ProfileResolution.Exact).profile

    @Test fun theMooq0ListTakesTheFactoryTestAppOffBeforeTheSwitch() {
        assertEquals("xiaomi-mitv-mooq0", mooq0.id)
        assertTrue(mooq0.groups.any { g -> g.packages.any { it.name == factory.pkg } }, "the list names it")
        val p = plan(setup(profile = mooq0), snapshot = before())
        val offs = p.steps.filter { it.op == Op.Disable(factory.pkg) }
        assertEquals(1, offs.size, "switched off once")
        val section = p.sections.single()
        assertTrue(offs.single().index < section.from, "by the list, before the safety timer is armed")
        assertTrue(offs.single().group != DefaultPlanner.GROUP_HOME)
        val stockOff = p.steps.indexOfFirst { it.op == Op.Disable(stock.pkg) }
        assertTrue(stockOff > offs.single().index, "before the TV's own home screen goes off")
        assertTrue(Op.Enable(factory.pkg) !in section.rollback, "step R leaves the list's switch-offs as they are")
        val row = p.summary.groups.flatMap { it.rows }.single { it.pkg == factory.pkg }
        assertTrue(row.off && !row.protected, "an ordinary row of the list, not the TV's home screen")
    }

    @Test fun theSwitchTakesTheFactoryTestAppOffFirstWhereTheListDoesNot() {
        val p = plan(setup(), snapshot = before())
        val section = p.sections.single()
        val i = p.steps.indexOfFirst { it.op == Op.Disable(factory.pkg) }
        assertTrue(i > section.from && i < p.steps.indexOfFirst { it.op == Op.Disable(stock.pkg) }, "inside the switch, before the TV's own home screen")
        assertEquals(DefaultPlanner.GROUP_HOME, p.steps[i].group)
        assertEquals(tv.cleanyour.core.ops.FailurePolicy.STOP_AND_ROLLBACK, p.steps[i].onFailure)
        val arm = p.steps.map { it.op }.filterIsInstance<Op.ArmHomeWatchdog>().single()
        assertEquals(listOf(Op.ConfirmHome(arm.token), Op.KillWatchdog(arm.token), Op.Enable(stock.pkg), Op.Enable(factory.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome),
            section.rollback, "step R puts the TV's own home screen back first")
        val row = p.summary.groups.single { it.id == DefaultPlanner.GROUP_HOME }.rows.single { it.pkg == factory.pkg }
        assertEquals(DefaultPlanner.DESCRIPTION_FACTORY_HOME, row.description)
        assertTrue(row.off && row.protected)
        // TCL's factory view is never switched off (the TCL lists say so), not even by the switch.
        val tcl = plan(setup(), snapshot = before(claimants = listOf(stock, tclFactory, fallback)))
        assertTrue(tcl.steps.none { it.op == Op.Disable(tclFactory.pkg) })
    }

    @Test fun aFactoryTestAppIsAHomeScreenOnlyWhileHomeAnswersToItOrIsUndecided() {
        val p = profile()
        val ok = before()
        assertNull(SwitchOffRule.reason(factory.pkg, ok.packages[factory.pkg], p, ok, p.stockLaunchers), "a claimant beside the stock home")
        assertNotNull(SwitchOffRule.reason(tclFactory.pkg, ok.packages[tclFactory.pkg], p, before(claimants = listOf(stock, tclFactory)), p.stockLaunchers))
        val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
        for (home in listOf(factory, chooser, null)) {
            val s = before(home = home)
            assertEquals(DefaultPlanner.REASON_HOME_SCREEN, SwitchOffRule.reason(factory.pkg, s.packages[factory.pkg], p, s, p.stockLaunchers), "HOME ${home?.flat}")
        }
    }

    // #1944's TV: Home answers to the factory test app. It is never recorded as the home screen to
    // go back to, and putting it back under the new home screen makes ours the default again.
    @Test fun neverAHomeScreenToGoBackTo() {
        assertTrue(HomePlaceholder.isPlaceholder(factory) && HomePlaceholder.isPlaceholder(tclFactory))
        assertEquals(stock, StockHome.recorded(before(home = factory), stock))
        val ours = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, fallback),
            extra = mapOf(factory.pkg to sys("Factory test").copy(enabled = EnabledState.DISABLED_USER)) + installedLauncher())
        val journal = listOf(change(0, Op.Disable(factory.pkg), Prior(packageState = EnabledState.ENABLED)))
        val back = plan(PlanRequest.PutBack(factory.pkg), snapshot = ours, journals = journal).steps.map { it.op }
        assertEquals(listOf(Op.Enable(factory.pkg), Op.SetHome(launcher), Op.VerifyHome(launcher)), back)
    }

    // Issue #4976 (Xiaomi MiTV-MOSR2, list xiaomi-mitv-mosr4, 0.4.6.1): the switch was done before the #1944 fix,
    // so the factory test app still claimed Home at 0 next to the new home screen, with the TV's own off. Run Setup
    // again plans no switch (Home is ours), so nothing took it off, and once the new home screen is gone Home lands
    // on it. Setup now switches it off then, outside any switch, and Undo everything puts it back.
    @Test fun issue4976ALeftOverFactoryTestAppGoesOffWhenHomeIsAlreadyOurs() {
        val ours = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, factory, fallback), extra = extra + installedLauncher())
        val p = plan(setup(), snapshot = ours)
        assertTrue(p.sections.isEmpty(), "no switch: Home is ours")
        val off = p.steps.single { it.op == Op.Disable(factory.pkg) }
        assertEquals(DefaultPlanner.GROUP_LEFTOVERS, off.group)
        assertEquals(EnabledState.ENABLED, off.prior?.packageState)
        // Already off, or Home not ours: nothing.
        val offAlready = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, fallback),
            extra = mapOf(factory.pkg to sys("Factory test").copy(enabled = EnabledState.DISABLED_USER)) + installedLauncher())
        assertTrue(plan(setup(), snapshot = offAlready).steps.none { it.op == Op.Disable(factory.pkg) })
        // TCL's factory view is never switched off (the TCL lists say so).
        val tcl = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, tclFactory, fallback), extra = extra + installedLauncher())
        assertTrue(plan(setup(), snapshot = tcl).steps.none { it.op == Op.Disable(tclFactory.pkg) })
    }

    // R0 is unchanged: a factory test app among the claimants is still another home screen there.
    @Test fun r0StillCountsItAsAnotherHome() = releaseRule {
        assertFalse(HomeSwitchRisk.r0Placeholder(factory))
        assertFalse(HomeSwitchRisk.r0Placeholder(tclFactory))
        assertTrue(HomeSwitchRisk.r0Placeholder(fallback))
        val s = before()
        assertEquals(HomeSwitchRisk.R0Blocker.OTHER_HOME, HomeSwitchRisk.r0Blocker(s.facts, s, anyBrand = false))
    }
}
