package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.session.ExecResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #5808 (Hisense SmartTV 4K FFM, songshan, Android 11, exact list): every run stopped the home screen
// switch at "Disable com.google.android.tungsten.setupwraith: exit 255: ... java.lang.SecurityException: Shell
// cannot change component state for com.google.android.tungsten.setupwraith/null to 3" and rolled it back.
// Android lets the shell move a whole package only between default (0), enabled (1) and disabled by the user
// (3); the wizard was already off by the TV itself (2, or 4: disabled until used, which `pm list packages -d`
// does not list). That switch-off is now a skip: nothing sent, nothing recorded, and the switch goes on.
class Issue5808WizardOffByTheTvTest {
    private fun harness(scope: kotlinx.coroutines.test.TestScope, code: Int): Harness {
        val h = Harness(scope)
        h.tv.packages[h.wizard] = PackageState(EnabledState.DEFAULT, true, 1, null, false, false, null, false)
        h.profiles = tv.cleanyour.testing.fixtures.FakeProfileSource(listOf(h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard)))
        val dump = h.tv.dump(h.wizard).replace("enabled=0", "enabled=$code")
        h.tv.answer = { line ->
            when (line) {
                "dumpsys package ${h.wizard.value}" -> ExecResult(0, dump, "", false, 1)
                "pm disable-user --user 0 ${h.wizard.value}" -> ExecResult(255, "", "Exception occurred while executing 'disable-user':\njava.lang.SecurityException: Shell cannot change component state for ${h.wizard.value}/null to 3", false, 1)
                else -> null
            }
        }
        return h
    }

    @Test fun issue5808AWizardTheTvTurnedOffIsSkippedAndTheSwitchGoesOn() = runTest(timeout = TEST_TIMEOUT) {
        for (code in listOf(2, 4)) {
            val h = harness(this, code)
            val engine = h.engine()
            val plan = h.setupPlan(takeover = true, profile = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard))
            val wizardOff = plan.steps.single { it.op == Op.Disable(h.wizard) }
            assertTrue(plan.sections.single().let { wizardOff.index in it.from..it.to }, "inside the switch")
            engine.start(plan, h.tv, plan.hash, h.pass())
            h.settle(engine)
            assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value, "code $code: the switch went on to the check, " + h.types(plan))
            assertEquals(0, h.tv.execs("pm disable-user --user 0 ${h.wizard.value}"), "nothing sent")
            val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == wizardOff.index }
            assertEquals(DefaultEngine.ALREADY_OFF_BY_THE_TV, failed.error)
            assertTrue(h.records(plan).filterIsInstance<JournalRecord.Done>().none { it.i == wizardOff.index }, "no change recorded for Undo")
        }
    }

    // Default (0), enabled (1) or disabled by the user (3) are the shell's to change: the switch-off is sent as before.
    @Test fun aWizardTheShellMayChangeIsSwitchedOffAsBefore() = runTest(timeout = TEST_TIMEOUT) {
        val h = harness(this, 1)
        h.tv.answer = null
        val engine = h.engine()
        val plan = h.setupPlan(takeover = true, profile = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard))
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        assertEquals(1, h.tv.execs("pm disable-user --user 0 ${h.wizard.value}"))
    }
}
