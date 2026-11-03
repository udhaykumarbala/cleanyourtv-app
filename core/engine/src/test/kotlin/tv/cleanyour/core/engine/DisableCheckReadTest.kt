package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.session.ExecResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// A switch-off whose pm write answered 0 is checked with pm list packages -d. When that check read
// comes back empty or races the write, the package's own dump decides: off is done (and recorded, so
// Switched off, Undo everything, the restore point and restore.txt all list it), and only a dump that
// shows the package on is the TV keeping it on (issue #10, Issue10Test). A check read or a dump read
// that errors is a failure: the run pauses, as in 0.3.2, and nothing is skipped.
class DisableCheckReadTest {
    private val empty = ExecResult(0, "", "", false, 1)
    private val error = ExecResult(1, "", "Error: could not access the package manager", false, 1)
    private val denied = ExecResult(255, "", "java.lang.SecurityException: Shell does not have permission to access user 10", false, 1)
    private val lists = listOf("pm list packages -d", "cmd package list packages -d --user 0", "pm list packages -d --user 0")

    @Test fun anEmptyCheckReadOfAPackageThatIsOffIsDoneAndRecorded() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line -> if (line == "pm list packages -d") empty else null }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED, fin.status)
        assertTrue(fin.skipped.isEmpty())
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(pkg))
        val records = h.records(plan)
        assertTrue(records.none { it is JournalRecord.Failed })
        assertEquals(0, records.filterIsInstance<JournalRecord.Done>().single().i)
        // The change records are what Switched off, Undo everything and the restore files read.
        assertEquals(listOf(Op.Disable(pkg)), ChangeRecords.fromJournal(records).map { it.op })
        assertEquals(listOf(Op.Disable(pkg)), ChangeRecords.fromJournals(listOf(records)).map { it.op })
    }

    // 0.4.9: every way to read the state errors (the list, its two fallbacks and the package's dump).
    @Test fun aCheckReadThatErrorsPausesAndSkipsNothing() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        var dumps = 0
        h.tv.answer = { line ->
            when {
                line in lists -> error
                // The live check's dump before the write answers; the one after it errors.
                line == "dumpsys package ${pkg.value}" -> if (++dumps >= 2) error else null
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertEquals(0, paused.at)
        assertIs<tv.cleanyour.core.model.AdbError.NonZeroExit>(paused.error)
        val records = h.records(plan)
        assertTrue(records.none { it is JournalRecord.Done })
        assertTrue(h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error.startsWith("verify read: exit 1"))
        assertTrue(h.types(plan).any { it.startsWith("paused:STEP_FAILED") })
        assertTrue(ChangeRecords.fromJournal(records).isEmpty())
    }

    @Test fun aDumpReadThatErrorsAfterAnEmptyCheckReadPauses() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        var dumps = 0
        h.tv.answer = { line ->
            when (line) {
                "pm list packages -d" -> empty
                // The live check's dump before the write answers; the re-read after it errors.
                "dumpsys package ${pkg.value}" -> if (++dumps >= 2) error else null
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertEquals(2, dumps)
        assertTrue(h.records(plan).none { it is JournalRecord.Done })
    }

    @Test fun anEmptyCheckReadOfAPackageTheDumpShowsOnIsTheKeptOnSkip() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line ->
            if (line == "pm disable-user --user 0 ${pkg.value}") ExecResult(0, "Package ${pkg.value} new state: enabled", "", false, 1) else null
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(Op.Disable(pkg)), fin.skipped.map { it.op })
        assertTrue(ChangeRecords.fromJournal(h.records(plan)).isEmpty())
    }

    // 0.4.9 (issues #3436, #1543): where pm answers a SecurityException, the check read falls back as the
    // probe does, to cmd package for user 0, then pm for user 0, and the log says once which one answered.
    @Test fun aFailingCheckReadFallsBackToCmdPackageForUserZero() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkgs = h.debloat.take(2)
        h.tv.answer = { line ->
            when (line) {
                "pm list packages -d" -> denied
                "cmd package list packages -d --user 0" -> ExecResult(0, pkgs.filter { h.tv.state(it) == EnabledState.DISABLED_USER }.joinToString("") { "package:${it.value}\n" }, "", false, 1)
                else -> null
            }
        }
        val log = mutableListOf<String>()
        val engine = h.engine(log = { log += it })
        val plan = h.opsPlan(*pkgs.map { Op.Disable(it) }.toTypedArray())
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED, fin.status)
        assertEquals(pkgs.map { Op.Disable(it) }, ChangeRecords.fromJournal(h.records(plan)).map { it.op })
        assertEquals(listOf("engine: disabled list from cmd package list packages -d for user 0 after pm list packages -d exit 255"),
            log.filter { it.contains("disabled list") })
        assertEquals(0, h.tv.execs("pm list packages -d --user 0"))
    }

    @Test fun thenToPmForUserZero() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line ->
            when (line) {
                "pm list packages -d", "cmd package list packages -d --user 0" -> denied
                "pm list packages -d --user 0" -> ExecResult(0, if (h.tv.state(pkg) == EnabledState.DISABLED_USER) "package:${pkg.value}\n" else "", "", false, 1)
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
    }

    // No list answers: only the package's dump showing it off passes. One that shows it on pauses (never
    // the kept-on skip, which needs a list that answered), and nothing is recorded.
    @Test fun noListAnswersAndTheDumpShowsItOffIsDone() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line -> if (line in lists) denied else null }
        val log = mutableListOf<String>()
        val engine = h.engine(log = { log += it })
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
        assertEquals(listOf(Op.Disable(pkg)), ChangeRecords.fromJournal(h.records(plan)).map { it.op })
        assertTrue(log.any { it == "engine: no disabled list answered (pm list packages -d exit 255); the package's dump decides" }, log.toString())
    }

    @Test fun noListAnswersAndTheDumpShowsItOnPauses() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line ->
            when {
                line in lists -> denied
                line == "pm disable-user --user 0 ${pkg.value}" -> ExecResult(0, "Package ${pkg.value} new state: enabled", "", false, 1)
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Disable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertIs<tv.cleanyour.core.model.AdbError.NonZeroExit>(paused.error)
        assertTrue(h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error.startsWith("verify read: exit 255"))
        assertTrue(ChangeRecords.fromJournal(h.records(plan)).isEmpty())
    }

    // A switch-on had no look at its check read's exit, so a list that failed passed it. It now falls back
    // the same way, and with no list a dump that does not show it on pauses.
    @Test fun aSwitchOnFallsBackAndPassesOnTheDump() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line -> if (line in lists) denied else null }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Enable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
        assertEquals(EnabledState.ENABLED, h.tv.state(pkg))
        lists.forEach { l -> assertEquals(1, h.tv.execLog.count { tv.cleanyour.testing.fixtures.Recording.normalize(it) == l }, l) }
    }

    @Test fun aSwitchOnWithNoReadableStatePauses() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line -> if (line in lists || line == "dumpsys package ${pkg.value}") denied else null }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Enable(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertTrue(h.records(plan).none { it is JournalRecord.Done })
    }
}
