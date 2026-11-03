package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.HomeSwitchRisk.Risk
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #4077 (RockChip X88MiNi14TV, rk3518_box_32, Android 14, no list): AOSP's first-run app
// com.android.provision claimed Home at 1 under tvlauncher (2), and Google's setup wizard is not on
// the box. The switch was planned; with tvlauncher off Home went to provision, the check rolled back,
// and "Use the new home screen" planned the same switch again. Without a list it now counts as the
// setup wizard does: no switch (WIZARD_STAYS_ON), on both rules.
class Issue4077ProvisionTest {
    private val tvlauncher = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    private val provision = Component(PackageName("com.android.provision"), ".DefaultActivity")
    private val ours = PlannerFixtures.launcher
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")

    private fun box(provisionState: EnabledState? = EnabledState.DEFAULT): Snapshot {
        val base = snapshot()
        val packages = (base.packages - wizard).let { p ->
            if (provisionState == null) p else p + (provision.pkg to PackageState(provisionState, true, 34, null, false, false, null, false))
        }
        val claimants = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(provision, 1), HomeClaimant(ours, 0), HomeClaimant(fallback, -1000))
        return base.copy(
            facts = base.facts.copy(brand = "RockChip", manufacturer = "RockChip", model = "X88MiNi14TV.E1010.786.FE", device = "rk3518_box_32", sdk = 34, release = "14"),
            packages = packages, home = HomeResolution(tvlauncher, claimants),
        ).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    }

    @Test fun provisionClaimingHomeBlocksTheSwitchWithoutAList() {
        for (anyBrand in listOf(false, true)) for (w in WizardWifi.entries) {
            for (s in listOf(box(), box(provisionState = null))) {
                assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(s.facts, null, s, w, anyBrand = anyBrand), "anyBrand $anyBrand, $w")
            }
            // Switched off, it cannot take Home: the switch is planned as before.
            val off = box(provisionState = EnabledState.DISABLED_USER)
            assertNull(HomeSwitchRisk.of(off.facts, null, off, w, anyBrand = anyBrand), "provision off, anyBrand $anyBrand, $w")
        }
        // The planner refuses a switch asked for anyway; without it the clean-up alone is planned.
        val s = box()
        fun setup(takeover: Boolean) = PlanRequest.Setup(ProfileResolution.None(listOf(tvlauncher)), choices(takeover = takeover), PlannerFixtures.artifact(), Level.SIMPLE)
        assertTrue(Risk.WIZARD_STAYS_ON.reason in PlannerFixtures.refused(setup(takeover = true), snapshot = s))
        val p = plan(setup(takeover = false), snapshot = s)
        assertTrue(p.steps.none { it.op is Op.SetHome || (it.op as? Op.Disable)?.pkg == tvlauncher.pkg }, "${p.steps.map { it.op }}")
    }

    // A tested list knows its TV: the rule is for TVs with no list only.
    @Test fun aTestedListKeepsItsOwnRule() {
        val s = box()
        val profile = PlannerFixtures.profile().copy(stockLaunchers = listOf(tvlauncher))
        assertNull(HomeSwitchRisk.of(s.facts, profile, s, WizardWifi.NOT_OWNER, anyBrand = false))
    }
}
