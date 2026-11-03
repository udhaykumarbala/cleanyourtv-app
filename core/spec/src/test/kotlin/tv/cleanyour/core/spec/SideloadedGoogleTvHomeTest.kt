package tv.cleanyour.core.spec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 0.5.0 (issues #5539, #5744, #5911, #4177, #4551): Google TV's home screen the person installed on an
// Android TV set made the TV count as a Google TV, so the exact Android TV list did not match. The
// TVs of #4177 (NVIDIA SHIELD, darcy) and #5911 (TCL UnionTV) as their reports show them: tvlauncher
// answers Home at priority 2, launcherx is an app the person installed claiming Home at 0.
class SideloadedGoogleTvHomeTest {
    private val source = BundledProfileSource()
    private val tvlauncher = KnownLaunchers.TVLAUNCHER
    private val launcherx = KnownLaunchers.LAUNCHERX
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")

    private fun state(system: Boolean, enabled: EnabledState = EnabledState.DEFAULT, version: Long = 1_000) =
        PackageState(enabled, system, version, null, persistent = false, tvInput = false, label = null, installedByUs = false)

    private fun leanbackHome(launcherxPriority: Int = 0) = HomeResolution(tvlauncher,
        listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(launcherx, launcherxPriority), HomeClaimant(fallback, -1000)))

    private fun packages(launcherx: PackageState? = state(system = false), tvlauncherState: PackageState? = state(system = true)) =
        buildMap {
            tvlauncherState?.let { put(tvlauncher.pkg, it) }
            launcherx?.let { put(this@SideloadedGoogleTvHomeTest.launcherx.pkg, it) }
            put(fallback.pkg, state(system = true))
        }

    private val shield = Fixtures.miTv().facts().copy(brand = "NVIDIA", manufacturer = "NVIDIA", model = "SHIELD Android TV", device = "darcy", sdk = 30,
        fingerprint = "NVIDIA/darcy/darcy:11/RQ1A.210105.003/13961458_4387.0822:user/release-keys")
    private val unionTv = Fixtures.miTv().facts().copy(brand = "TCL", manufacturer = "TCL", model = "UnionTV", device = "UnionTV", sdk = 28,
        fingerprint = "TCL/UnionTV/UnionTV:9/PPR2.180905.006.A1/builder.20201203.103024:user/release-keys")

    @Test fun theReportedTvsGetTheirAndroidTvList() = runBlocking<Unit> {
        for ((facts, id) in listOf(shield to "nvidia-shield-tv-2017", unionTv to "tcl-uniontv-latin-america")) {
            val r = assertIs<ProfileResolution.Exact>(source.resolve(facts, leanbackHome(), packages()), id)
            assertEquals(id, r.profile.id)
            assertTrue(HomeFit.sideloadedGoogleTvHome(leanbackHome(), packages()))
            assertFalse(HomeFit.showsGoogleTvHome(leanbackHome(), packages()))
        }
    }

    // Every sign must agree; one that does not keeps the TV counted as a Google TV, as before.
    // tvlauncher updated under /data with its flags not read: its Home claim at 2 says it is the TV's own.
    // After the home screen switch it is off and claims nothing, so its system flag decides.
    @Test fun tvlaunchersClaimOrItsFlagSaysItIsTheTvsOwn() {
        assertTrue(HomeFit.sideloadedGoogleTvHome(leanbackHome(), packages(tvlauncherState = state(system = false))))
        val ours = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
        val after = HomeResolution(ours, listOf(HomeClaimant(launcherx, 0), HomeClaimant(ours, 0), HomeClaimant(fallback, -1000)))
        assertTrue(HomeFit.sideloadedGoogleTvHome(after, packages(tvlauncherState = state(system = true, enabled = EnabledState.DISABLED_USER))))
        assertFalse(HomeFit.sideloadedGoogleTvHome(after, packages(tvlauncherState = state(system = false, enabled = EnabledState.DISABLED_USER))))
    }

    @Test fun aRealGoogleTvIsNeverTakenForOne() = runBlocking<Unit> {
        val home = leanbackHome()
        fun gtv(h: HomeResolution, p: Map<PackageName, PackageState>?) {
            assertFalse(HomeFit.sideloadedGoogleTvHome(h, p))
            assertTrue(HomeFit.showsGoogleTvHome(h, p))
            assertFalse(source.resolveNow(shield, home = h, packages = p) is ProfileResolution.Exact)
        }
        // A system app, as the probe reads it when the flags answer.
        gtv(home, packages(launcherx = state(system = true)))
        // Updated under /data on a real Google TV: the system flag reads wrong, the Home claim keeps its priority.
        gtv(leanbackHome(launcherxPriority = 2), packages())
        // Its details not read (version code 0): the system flag is only a guess.
        gtv(home, packages(launcherx = state(system = false, version = 0)))
        // Google's Android TV home is not a system app here and claims no Home above 0, or is not on the TV at all.
        val noTvlauncherClaim = HomeResolution(tvlauncher, home.claimants.filter { it.component != tvlauncher })
        gtv(noTvlauncherClaim, packages(tvlauncherState = state(system = false)))
        gtv(noTvlauncherClaim, packages(tvlauncherState = null))
        // Home answers to it.
        gtv(HomeResolution(launcherx, home.claimants), packages())
        // Switched off (what the home screen switch leaves on a real Google TV).
        gtv(HomeResolution(tvlauncher, listOf(HomeClaimant(tvlauncher, 2))), packages(launcherx = state(system = false, enabled = EnabledState.DISABLED_USER)))
        // No packages read: the Home alone decides.
        gtv(home, null)
    }
}
