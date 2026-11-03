package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issues #5016 (Sony BRAVIA VU31) and #5435 (SINGER R3): Setup had nothing to do ("left as is, nothing
// to switch off"), Finish ran the debugging-off run and the app was closed while it ran. The next start
// opened "We were in the middle of a change on the TV" (RESUME_AFTER_DEATH) for that run. Now the run
// is closed in its journal and Manage opens; no card, no resume.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue5016ResumeTest {

    @Test fun anOpenDebuggingOffRunNeverShowsTheResumeCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val off = h.plan(listOf(Op.DebugOff), kind = PlanKind.DEBUG_OFF, id = "01FAKEDEBUGOFF00000000001")
        h.ledger.record(off)
        h.journal.append(JournalRecord.PlanStart(off.id, tv.id, Samples.zeroHash, 1, off.hash, "0.4.7", 0))
        h.journal.append(JournalRecord.Begin(off.id, 0, Op.DebugOff, listOf("settings put global adb_enabled 0"), null, 1))
        assertEquals(StartDestination.ResumeRun(tv.id, off.id), resolveStart(listOf(tv), h.journal))
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, off.id))
        m.effects.test {
            settle()
            assertEquals(SetupEffect.Navigate("manage/${tv.id.value}"), drain().last())
        }
        assertFalse(m.state.value is SetupState.Stopped, "${m.state.value}")
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Resume })
        assertEquals(RunStatus.ABANDONED, assertIs<JournalRecord.End>(h.journal.read(off.id).last()).status)
        assertEquals(StartDestination.Manage(tv.id), resolveStart(listOf(tv), h.journal))
    }

    // Any other open run keeps its card, a Setup run's as before.
    @Test fun anOpenSetupRunStillShowsIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.4.7", 0))
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        assertEquals(StopCase.RESUME_AFTER_DEATH, assertIs<SetupState.Stopped>(m.state.value).card.case)
        assertFalse(h.ledger.closeOpenDebugOff(tv.id, h.setupPlan.id))
        assertEquals(listOf(h.setupPlan.id), h.journal.openRuns(tv.id))
    }
}
