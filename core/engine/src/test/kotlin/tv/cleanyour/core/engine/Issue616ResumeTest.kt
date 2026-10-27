package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.testing.fixtures.MemoryJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issue #616 (TCL Smart TV Pro G05, the C735, on a Wi-Fi that kept losing it), from the report's log:
//
//   I setup: run running
//   I setup: run paused CONNECTION_LOST at step 0 (timeout)      the restore point read timed out
//   I setup: run running                                         Carry on
//   ...
//   I setup: run running
//   I setup: run paused IN_DOUBT at step 0                       after the app was closed and opened
//   I setup: run running                                         Carry on
//
// Both pauses came before step 0 began. Carry on went on as after a step: the restore point of the
// run was never saved, and the staleness check skipped step 0. A run with no step begun now carries
// on from its very start: the restore point is taken and saved, and every step is checked.
class Issue616ResumeTest {

    @Test fun issue616CarryOnAfterTheRestorePointFailedTakesItAgain() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        // The TV drops off the network during the restore point read.
        h.tv.dropOnLine = { it.contains("getprop") }
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        assertEquals(0, paused.at)
        assertTrue(h.records(plan).none { it is JournalRecord.Begin }, "no step began")
        assertNull(h.snapshots.latest(h.tv.tvId), "no restore point yet")

        // The TV is back; Carry on.
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.continueRun(h.tv.tvId, h.pass())
        assertIs<RunState.Finished>(h.settle(engine))
        val saved = assertNotNull(h.snapshots.latest(h.tv.tvId), "Carry on saved the run's restore point")
        assertEquals(SnapshotReason.PRE_PLAN, saved.reason)
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value).status)
    }

    @Test fun issue616InDoubtAtStepZeroAfterARestartTakesTheRestorePoint() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        // Only the PlanStart reaches the journal: the restore point read fails and the app is closed
        // before anything else is written (the pause record is lost with the process).
        val dying = MemoryJournal().also { it.crashAfter = 1 }
        val e1 = h.engine(journal = dying)
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.dropOnLine = { it.contains("getprop") }
        e1.start(plan, h.tv, plan.hash, h.pass())
        h.settle(e1)
        assertEquals(1, dying.read(plan.id).size)

        // The app opens again, the TV answers.
        dying.crashAfter = null
        h.tv.dropOnLine = null; h.tv.reconnect()
        val e2 = DefaultEngine(h.renderer, h.prober, dying, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        val paused = assertIs<RunState.Paused>(e2.runState(h.tv.tvId).value)
        assertEquals(PauseReason.IN_DOUBT, paused.reason)
        assertEquals(0, paused.at)
        assertNull(h.snapshots.latest(h.tv.tvId))

        e2.continueRun(h.tv.tvId, h.pass())
        assertIs<RunState.Finished>(h.settle(e2))
        assertEquals(SnapshotReason.PRE_PLAN, assertNotNull(h.snapshots.latest(h.tv.tvId)).reason)
        for (p in h.debloat) assertEquals(tv.cleanyour.core.model.EnabledState.DISABLED_USER, h.tv.state(p), p.value)
        assertEquals(1, dying.read(plan.id).count { it is JournalRecord.End })
    }

    // A run that had begun its steps carries on as before: no second restore point.
    @Test fun aRunThatHadBegunDoesNotTakeASecondRestorePoint() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.dropOnLine = { it.contains(h.debloat[2].value) && it.contains("disable-user") }
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertIs<RunState.Paused>(h.settle(engine))
        val before = h.prober.calls.count { it == "snapshot PRE_PLAN" }
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.continueRun(h.tv.tvId, h.pass())
        assertIs<RunState.Finished>(h.settle(engine))
        // One recheck read, as before (not saved: the restore point is the first one).
        assertEquals(before + 1, h.prober.calls.count { it == "snapshot PRE_PLAN" })
        assertEquals(1, h.records(plan).count { it is JournalRecord.Begin && it.i == 0 })
    }
}
