package tv.cleanyour.core.planner

import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.planner.HomeSwitchRisk.Risk
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.spec.ProfileConverter
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Test builds (0.3.5-test1, 0.3.6-beta1, 0.3.8-beta1, 0.3.8-beta2, 0.4.5-beta1): a candidate list goes
// through every rule a tested list does, and for the home screen switch's setup wizard rule it counts
// as no profile at all, so trying a list that nobody tested never brings a switch the careful path
// would skip.
class CandidateSafetyTest {
    private val tv = snapshot()   // the reference TV, its setup wizard installed and on
    private val noWizard = tv.copy(packages = tv.packages - wizard).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    private val tested = PlannerFixtures.profile()
    private val candidate = tested.copy(tested = TestedInfo("candidate, issue #57", null, null, rebootTested = false, candidate = true))

    @Test fun aCandidateGetsTheWizardRuleOfATvWithNoProfile() {
        // A tested list that never names the wizard is not held back (HomeSwitchRiskTest); a candidate is.
        assertNull(HomeSwitchRisk.of(tv.facts, tested, tv, WizardWifi.OWNER))
        assertEquals(Risk.WIZARD_WIFI, HomeSwitchRisk.of(tv.facts, candidate, tv, WizardWifi.OWNER))
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(tv.facts, candidate, tv, WizardWifi.UNKNOWN))
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(tv.facts, candidate, tv, WizardWifi.NOT_OWNER))
        for (w in WizardWifi.entries) assertEquals(HomeSwitchRisk.of(tv.facts, null, tv, w), HomeSwitchRisk.of(tv.facts, candidate, tv, w), "$w")
        // The planner refuses a switch asked for anyway, and plans the clean-up alone.
        assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = candidate, choices = choices(wizardWifi = WizardWifi.NOT_OWNER))))
        plan(setup(profile = candidate, choices = choices(installLauncher = false, takeover = false, wizardWifi = WizardWifi.NOT_OWNER)))
        // Without a setup wizard on the TV the switch is the same as on the careful path.
        for (w in WizardWifi.entries) assertNull(HomeSwitchRisk.of(noWizard.facts, candidate, noWizard, w), "$w")
        assertTrue(Op.SetHome(PlannerFixtures.launcher) in plan(setup(profile = candidate, choices = choices(wizardWifi = WizardWifi.UNKNOWN)), snapshot = noWizard).steps.map { it.op })
    }

    // Every line the candidate lists switch off by default passes SwitchOffRule (the denylist, the
    // never-disable lists, the home screens and the setup wizards) on its own, so no draft line had
    // to be dropped; on the TV the live check asks the same rule again with the TV's own state.
    @Test fun noCandidateLineIsRefusedByTheSwitchOffRule() {
        val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
        val (bundle, problems) = ProfileConverter.convert(
            File(Fixtures.upstreamDir, "examples"), File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"),
            "b06ab21", unconverted, File(Fixtures.repoRoot, "core/spec/candidates"), File(Fixtures.repoRoot, "core/spec/candidates/candidate-meta.json"),
        )
        assertEquals(emptyList(), problems)
        val candidates = bundle.profiles.filter { it.isCandidate }
        assertEquals(39, candidates.size)
        val refusedLines = mutableListOf<String>()
        for (p in candidates) for (g in p.groups.filter { it.default == GroupDefault.DISABLE }) for (e in g.packages)
            SwitchOffRule.reason(e.name, null, p, null, p.stockLaunchers)?.let { refusedLines += "${p.id} ${e.name.value}: $it" }
        assertEquals(emptyList(), refusedLines)
    }

    // 0.4.5-beta1: no candidate is on a TV family whose switch HomeSwitchRisk holds back by name (the
    // MOKA boot loop, Google's own casting hardware, the remote buttons of sony-bravia-vu3), so a
    // candidate never needs a rule a tested list has; without a setup wizard on the TV, the switch of
    // each one is planned as on the careful path, or refused for want of a known home (clean-up only).
    @Test fun noCandidateIsOnATvFamilyTheSwitchHoldsBack() {
        val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
        val (bundle, _) = ProfileConverter.convert(
            File(Fixtures.upstreamDir, "examples"), File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"),
            "b06ab21", unconverted, File(Fixtures.repoRoot, "core/spec/candidates"), File(Fixtures.repoRoot, "core/spec/candidates/candidate-meta.json"),
        )
        val held = mutableListOf<String>()
        for (p in bundle.profiles.filter { it.isCandidate }) {
            val m = p.match
            val f = noWizard.facts.copy(brand = m.brand!!, manufacturer = m.brand!!, model = m.models.firstOrNull() ?: "unknown", device = m.devices.first(), sdk = m.sdkMin!!)
            for (w in WizardWifi.entries) HomeSwitchRisk.of(f, p, noWizard, w)?.let { held += "${p.id} $w: $it" }
        }
        assertEquals(emptyList(), held)
    }
}
