package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.HomeSwitchRisk.Risk
import tv.cleanyour.core.planner.PlannerFixtures.releaseRule
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.5.0 (issues #5539, #5744, #4177, #4551): Google TV's home screen the person installed on an Android
// TV set claims Home at priority 0 next to tvlauncher. R0 took it for a TV home screen (it is one of the
// known ones), so the switch was refused as OTHER_HOME where any other home screen app the person
// installed is accepted (issue #1119). A launcherx that is the TV's own (a system app, or claiming above
// 0) still blocks R0, as before.
class SideloadedGoogleTvR0Test {
    private val tvlauncher = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    private val launcherx = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val mitv4i: DeviceFacts = snapshot().facts.copy(brand = "Xiaomi", manufacturer = "Xiaomi", model = "MiTV4I", device = "magnolia", sdk = 28,
        release = "28", fingerprint = "Xiaomi/magnolia/magnolia:9/PI/3698:user/release-keys")

    private fun tv(system: Boolean = false, priority: Int = 0, home: Component = tvlauncher, f: DeviceFacts = mitv4i): Snapshot =
        snapshot().copy(facts = f, home = HomeResolution(home, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(launcherx, priority), HomeClaimant(fallback, -1000))))
            .let { s -> s.copy(packages = s.packages + (launcherx.pkg to PackageState(EnabledState.DEFAULT, system, 1_000, null, false, false, null, false))) }
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    @Test fun r0AcceptsGoogleTvsHomeScreenThePersonInstalled() = releaseRule {
        // The reference capture lists tvlauncher under /data (an update), so its system flag reads false;
        // its Home claim at 2 says it is the TV's own.
        assertFalse(tv().packages.getValue(tvlauncher.pkg).system)
        for (w in WizardWifi.entries) {
            assertTrue(HomeSwitchRisk.installedHome(launcherx.pkg, tv()))
            assertNull(HomeSwitchRisk.r0Blocker(mitv4i, tv(), anyBrand = false), "$w")
            assertNull(HomeSwitchRisk.of(mitv4i, null, tv(), w, anyBrand = false), "release, brand set $w")
            // The test build's rule for every brand: the itel and MULTYNET sets of #5744 and #5539.
            val itel = mitv4i.copy(brand = "itel", manufacturer = "Changhong", model = "AI PONT", device = "samseong", sdk = 30)
            assertNull(HomeSwitchRisk.of(itel, null, tv(f = itel), w, anyBrand = true), "test build $w")
        }
    }

    @Test fun theTvsOwnGoogleTvHomeStillBlocksR0() = releaseRule {
        for (w in WizardWifi.entries) {
            for ((why, s) in listOf("a system app" to tv(system = true), "priority 1" to tv(priority = 1), "Home answers to it" to tv(home = launcherx))) {
                assertFalse(HomeSwitchRisk.installedHome(launcherx.pkg, s), why)
                assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(mitv4i, null, s, w, anyBrand = false), "$w: $why")
            }
        }
    }

    // #5911 (TCL UnionTV, Android 9): its remote's buttons go to the TV's own Android TV home, so the TCL
    // Google TV warning is not for it; a TCL set whose launcherx is its own still gets it.
    @Test fun theTclGoogleTvWarningIsNotForAnAndroidTvSet() {
        val union = mitv4i.copy(brand = "TCL", manufacturer = "TCL", model = "UnionTV", device = "UnionTV")
        assertFalse(TclGoogleTv.applies(union, tv(f = union)))
        assertFalse(GoogleTvSettingsKey.applies(mitv4i, tv()))
        assertTrue(TclGoogleTv.applies(union, tv(f = union, system = true)))
        assertTrue(GoogleTvSettingsKey.applies(mitv4i, tv(system = true)))
    }
}
