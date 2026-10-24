package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.planner
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.spec.LauncherConfigDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Remote buttons: a look push that maps a remote button also switches on the launcher's HomeKeyService, the
// key service that reads app_keys and leaves every other key alone. Shown as typed settings steps after the
// restart of Home, undone to what the TV had, and refused when it would bind another app's service.
class RemoteButtonsPlanTest {
    private fun ops(p: Plan) = p.steps.map { it.op }
    private val config = BlobRef(Sha256("c".repeat(64)), 100, "blob:c")
    private val service = "com.gathin.tvhome/.HomeKeyService"
    private val base = snapshot(home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
    private fun with(list: String?, on: String?): Snapshot {
        val s = base.settings - SettingKey.ENABLED_ACCESSIBILITY - SettingKey.ACCESSIBILITY_ENABLED
        return base.withSettings(s + listOfNotNull(list?.let { SettingKey.ENABLED_ACCESSIBILITY to it }, on?.let { SettingKey.ACCESSIBILITY_ENABLED to it }))
    }
    private fun request(keyService: Boolean = true) = PlanRequest.ApplyTheme(LauncherConfigDoc(appKeys = listOf("scan:425:a.b")), config, null, keyService)
    private fun refusal(s: Snapshot): List<String> =
        when (val r = planner(OverlayTransport()).plan(request(), s, PlannerFixtures.facts, emptyList())) {
            is PlanResult.Ok -> throw AssertionError("expected a refusal, got ${ops(r.plan)}")
            is PlanResult.Refused -> r.reasons
        }

    @Test fun aTvWithoutAccessibilityGetsTheKeyServiceAfterTheRestart() {
        for ((list, on) in listOf(null to null, null to "0", "" to "0")) {
            val p = plan(request(), snapshot = with(list, on), planner = planner(OverlayTransport()))
            val restart = p.steps.indexOfFirst { it.op is Op.RestartHome }
            assertTrue(restart >= 0)
            assertEquals(
                listOf(Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, service), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1")),
                ops(p).drop(restart + 1), "list $list, accessibility $on",
            )
            val steps = p.steps.drop(restart + 1)
            assertTrue(steps.all { it.group == DefaultPlanner.GROUP_THEME && it.onFailure == FailurePolicy.CONTINUE })
            // Undo puts back what the TV had: no list (deleted) and accessibility as it was.
            assertEquals(null, steps[1].prior?.settingValue)
            assertEquals(on, steps[2].prior?.settingValue)
            assertTrue(steps[1].prior != null && steps[2].prior != null)
        }
    }

    @Test fun ourOtherServiceIsKeptAndHomeKeyServiceAdded() {
        val input = "com.gathin.tvhome/com.gathin.tvhome.InputKeyService"
        val p = plan(request(), snapshot = with(input, "0"), planner = planner(OverlayTransport()))
        assertEquals(Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, "$input:$service"), ops(p).first { it is Op.SettingsPut })
    }

    @Test fun alreadyOnNeedsOnlyTheRestartsOwnPutBack() {
        // HomeKeyService listed and accessibility on: restoreAccessibility after the restart binds it again,
        // and nothing more is added.
        val p = plan(request(), snapshot = with(service, "1"), planner = planner(OverlayTransport()))
        val puts = ops(p).filterIsInstance<Op.SettingsPut>()
        assertEquals(listOf(null, "0", service, "1"), puts.map { it.value })
    }

    @Test fun anotherAppsServiceIsNeverSwitchedOnWithIt() {
        val reasons = refusal(with("com.example.reader/.Reader", "0"))
        assertTrue(reasons.any { "another app's accessibility service" in it }, reasons.toString())
        // With accessibility already on, the other service already runs, so adding ours changes nothing for it.
        val p = plan(request(), snapshot = with("com.example.reader/.Reader", "1"), planner = planner(OverlayTransport()))
        assertEquals(Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, "com.example.reader/.Reader:$service"),
            ops(p).filterIsInstance<Op.SettingsPut>().first { it.key == SettingKey.ENABLED_ACCESSIBILITY && it.value?.contains("HomeKey") == true })
    }

    @Test fun withoutTheAskNothingIsSwitchedOn() {
        val p = plan(request(keyService = false), snapshot = with(null, "0"), planner = planner(OverlayTransport()))
        assertTrue(ops(p).last() is Op.RestartHome)
        assertTrue(ops(p).none { it is Op.SettingsPut })
    }
}
