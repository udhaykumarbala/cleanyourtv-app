package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.testing.fixtures.MemoryJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issues #5016 (Sony BRAVIA VU31) and #5435 (SINGER R3): Setup left the TV as it was ("left as is,
// nothing to switch off"), Finish ran the debugging-off run, and the app was closed while it ran. On
// the next start the open journal gave "We were in the middle of a change on the TV" (paused IN_DOUBT
// at step 0) although nothing had changed. A debugging-off run left open is closed on resume instead:
// nothing to carry on or undo, and no card.
class Issue5016DebugOffResumeTest {

    @Test fun anOpenDebuggingOffRunIsClosedNotResumed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        // The PlanStart and the step's Begin reach the journal, then the process is gone.
        val dying = MemoryJournal().also { it.crashAfter = 2 }
        val e1 = h.engine(journal = dying)
        val plan = h.plan(PlanRequest.DebugOff)
        assertEquals(PlanKind.DEBUG_OFF, plan.kind)
        e1.start(plan, h.tv, plan.hash, null)
        h.settle(e1)
        assertTrue(dying.read(plan.id).any { it is JournalRecord.Begin }, "a step began")
        assertEquals(listOf(plan.id), dying.openRuns(h.tv.tvId))

        // The app opens again and the TV answers.
        dying.crashAfter = null
        val e2 = DefaultEngine(h.renderer, h.prober, dying, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        assertIs<RunState.Idle>(e2.runState(h.tv.tvId).value)
        assertEquals(RunStatus.ABANDONED, assertIs<JournalRecord.End>(dying.read(plan.id).last()).status)
        assertEquals(emptyList(), dying.openRuns(h.tv.tvId))
    }
}
