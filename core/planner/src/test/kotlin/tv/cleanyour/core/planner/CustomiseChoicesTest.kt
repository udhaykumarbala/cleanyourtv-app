package tv.cleanyour.core.planner

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.debloatList
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 0.4.9: Customise's switch off tap now leaves `kept` and joins `extraOff` in one go (SetupMachine),
// so these are the choice sets the planner gets after "Kept" is switched off again. A row a list
// leaves on to ask goes off; a row the list switches off anyway is switched off once, in its own
// group, never again under "Also switched off".
class CustomiseChoicesTest {
    private val tf1 = PackageName("fr.tf1.mytf1")
    private val on = PackageState(EnabledState.ENABLED, false, 1, null, false, false, null, false)
    private val ask = PackageGroup("ask", "Streaming apps", GroupDefault.ASK, listOf(PackageEntry(tf1, null)))
    private val prof = profile(extraGroups = listOf(ask))
    private val snap = snapshot(extra = mapOf(tf1 to on))

    private fun disables(p: Plan) = p.steps.mapNotNull { (it.op as? Op.Disable)?.pkg }

    @Test fun anAskRowSwitchedOffAfterKeptIsOff() {
        val p = plan(setup(profile = prof, choices = choices(kept = emptySet(), extraOff = setOf(tf1))), snap)
        val row = p.summary.groups.first { it.id == "ask" }.rows.single()
        assertTrue(row.off && !row.protected, row.toString())
        assertEquals(1, disables(p).count { it == tf1 })
        // Kept again: on, and nothing switches it off.
        val kept = plan(setup(profile = prof, choices = choices(kept = setOf(tf1), extraOff = emptySet())), snap)
        assertTrue(kept.summary.groups.first { it.id == "ask" }.rows.single().let { !it.off && !it.protected })
        assertTrue(tf1 !in disables(kept))
    }

    @Test fun aListRowInExtraOffIsSwitchedOffOnce() {
        val listed = debloatList.first { it in snap.packages }
        val p = plan(setup(profile = prof, choices = choices(extraOff = setOf(listed))), snap)
        assertEquals(1, disables(p).count { it == listed })
        assertTrue(p.summary.groups.none { it.id == DefaultPlanner.GROUP_CUSTOM }, p.summary.groups.map { it.id }.toString())
        assertEquals(disables(plan(setup(profile = prof), snap)), disables(p))
    }
}
