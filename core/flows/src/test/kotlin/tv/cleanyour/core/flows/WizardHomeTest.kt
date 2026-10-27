package tv.cleanyour.core.flows

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.6: the safety net under R0. Manage offers the TV's own home screen back when, on a connect,
// ours is Home, the TV's own home screen is off and a setup wizard has become a Home claimant.
class WizardHomeTest {
    private val stock = Samples.stockLauncher
    private val wizard = PackageName("com.google.android.tungsten.setupwraith")
    private val recovery = Component(wizard, ".RecoveryActivity")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val latest = Samples.snapshot(packages = Samples.miTvPackages(setOf(stock.pkg.value)))
    private fun home(vararg c: Component, resolved: Component? = Launcher.home) = HomeResolution(resolved, c.map { HomeClaimant(it, 0) })

    @Test fun aWizardThatBecameAHomeClaimantUnderOursIsTold() {
        assertTrue(WizardHome.applies(home(Launcher.home, recovery, fallback), latest, null, stock))
        // A wizard named like Google TV's counts too (as HomeSwitchRisk.homeWizards tells one).
        assertTrue(WizardHome.applies(home(Launcher.home, Component(PackageName("com.vendor.setupwraith"), ".Recovery")), latest, null, stock))
    }

    @Test fun nothingIsToldOtherwise() {
        assertFalse(WizardHome.applies(home(Launcher.home, fallback), latest, null, stock), "no wizard among the claimants")
        assertFalse(WizardHome.applies(home(Launcher.home, recovery, stock), latest, null, stock), "the TV's own home screen is on")
        assertFalse(WizardHome.applies(home(stock, recovery, resolved = stock), latest, null, stock), "the TV's own home screen is Home")
        val wizardOff = Samples.snapshot(packages = Samples.miTvPackages(setOf(stock.pkg.value, wizard.value)))
        assertFalse(WizardHome.applies(home(Launcher.home, recovery), wizardOff, null, stock), "the wizard is switched off")
        assertFalse(WizardHome.applies(home(Launcher.home, recovery), null, null, stock), "no stored snapshot")
        assertFalse(WizardHome.applies(home(Launcher.home, recovery), latest, null, null), "no known stock home to switch back on")
        assertEquals(EnabledState.ENABLED, latest.packages.getValue(wizard).enabled)
    }

    @Test fun theStockHomeIsTheOneTheSwitchArmedItsWatchdogWith() {
        val armed = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
        val changes = listOf(ChangeRecord(PlanId("01FAKEPLAN0000000000000001"), 1, Op.ArmHomeWatchdog(armed, 150, RunToken("00000000000000aa")), null, 1),
            ChangeRecord(PlanId("01FAKEPLAN0000000000000001"), 2, Op.Disable(armed.pkg), Prior(packageState = EnabledState.ENABLED), 2))
        assertEquals(armed, WizardHome.stockOf(changes, null))
        assertNull(WizardHome.stockOf(emptyList(), null))
    }
}
