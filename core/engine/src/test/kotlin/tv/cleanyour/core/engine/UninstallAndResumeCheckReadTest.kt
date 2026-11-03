package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.testing.fixtures.MemoryJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 0.5.0: two check reads that took a failed read for an answer.
// An uninstall was checked with `pm list packages -f` without a look at its exit, so a list that failed
// (exit 255 with a SecurityException on some TVs, #3436, #1543) printed nothing and read as "not listed":
// uninstalled. It now reads the list as the probe does (cmd package for user 0, then pm for user 0), and
// with no list the package's own dump decides; only a read that shows the package gone passes.
// The resume check of a step in doubt read its list once, the same way: a switch-on or an uninstall in
// doubt passed on a list that failed. It now reads the state the way the step's own check does.
class UninstallAndResumeCheckReadTest {
    private val denied = ExecResult(255, "", "java.lang.SecurityException: Shell does not have permission to access user 10", false, 1)
    private val lists = listOf("pm list packages -f", "cmd package list packages -f --user 0", "pm list packages --user 0")
    private val disabledLists = listOf("pm list packages -d", "cmd package list packages -d --user 0", "pm list packages -d --user 0")
    private fun ok(out: String) = ExecResult(0, out, "", false, 1)
    private fun gone(pkg: PackageName) = ok("Dexopt state:\n  Unable to find package: ${pkg.value}\n")

    // The TV answers Success to the uninstall but keeps the package (the write did not take).
    private fun Harness.keepsOnUninstall(pkg: PackageName): (String) -> ExecResult? = { line ->
        when {
            line == "pm uninstall --user 0 ${pkg.value}" -> ok("Success")
            line in lists -> denied
            else -> null
        }
    }

    @Test fun anUninstallWhoseListFailsAndThePackageStaysPauses() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = h.keepsOnUninstall(pkg)
        val engine = h.engine()
        val plan = h.opsPlan(Op.Uninstall(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertTrue(h.records(plan).none { it is JournalRecord.Done })
        assertTrue(h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error.startsWith("verify read: exit 255"))
        // Every list was tried, once, before the dump decided.
        lists.forEach { l -> assertEquals(1, h.tv.execLog.count { tv.cleanyour.testing.fixtures.Recording.normalize(it) == l }, l) }
    }

    @Test fun anUninstallFallsBackToCmdPackageForUserZero() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line ->
            when (line) {
                "pm list packages -f" -> denied
                "cmd package list packages -f --user 0" -> ok(h.tv.snapshot().packages.keys.joinToString("") { "package:/data/app/${it.value}-1/base.apk=${it.value}\n" })
                else -> null
            }
        }
        val log = mutableListOf<String>()
        val engine = h.engine(log = { log += it })
        val plan = h.opsPlan(Op.Uninstall(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
        assertEquals(null, h.tv.state(pkg))
        assertEquals(listOf("engine: package list from cmd package list packages -f for user 0 after pm list packages -f exit 255"),
            log.filter { it.contains("package list") })
        assertEquals(0, h.tv.execs("pm list packages --user 0"))
    }

    // pm for user 0 prints no install paths ("package:<name>"): a package still there is still listed.
    @Test fun theListWithoutPathsStillShowsAPackageThatStayed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = { line ->
            when (line) {
                "pm uninstall --user 0 ${pkg.value}" -> ok("Success")
                "pm list packages -f", "cmd package list packages -f --user 0" -> denied
                "pm list packages --user 0" -> ok(h.tv.snapshot().packages.keys.joinToString("") { "package:${it.value}\n" })
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Uninstall(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertTrue(h.records(plan).none { it is JournalRecord.Done })
    }

    @Test fun noListAnswersAndTheDumpShowsThePackageGoneIsDone() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        var uninstalled = false
        h.tv.answer = { line ->
            when {
                line in lists -> denied
                line == "pm uninstall --user 0 ${pkg.value}" -> { uninstalled = true; null }
                line == "dumpsys package ${pkg.value}" && uninstalled -> gone(pkg)
                else -> null
            }
        }
        val log = mutableListOf<String>()
        val engine = h.engine(log = { log += it })
        val plan = h.opsPlan(Op.Uninstall(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
        assertTrue(log.any { it == "engine: no package list answered (pm list packages -f exit 255); the package's dump decides" }, log.toString())
    }

    // An empty answer with exit 0 is no answer either: neither the list nor the dump can show a package gone.
    @Test fun emptyAnswersNeverPassAnUninstall() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        var uninstalled = false
        h.tv.answer = { line ->
            when {
                line == "pm uninstall --user 0 ${pkg.value}" -> { uninstalled = true; ok("Success") }
                line in lists -> ok("")
                line == "dumpsys package ${pkg.value}" && uninstalled -> ok("")
                else -> null
            }
        }
        val engine = h.engine()
        val plan = h.opsPlan(Op.Uninstall(pkg))
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertIs<RunState.Paused>(h.settle(engine))
        assertTrue(h.records(plan).none { it is JournalRecord.Done })
    }

    // Resume: a switch-on in doubt (the app died after the write, before its record) on a TV whose
    // disabled lists all fail. The package is still off, so the step is not done: the run stops at it.
    @Test fun aSwitchOnInDoubtIsNotDoneOnAListThatFailed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        // Off before the run, as a switch-on in Undo finds it.
        h.tv.run("pm disable-user --user 0 ${pkg.value}")
        // The switch-on is answered but does not take, and the app dies before its record.
        h.tv.answer = { line ->
            when {
                line == "pm enable --user 0 ${pkg.value}" -> ok("Package ${pkg.value} new state: enabled")
                line in disabledLists -> denied
                else -> null
            }
        }
        val crashing = MemoryJournal().also { it.crashAfter = 2 }   // PlanStart and Begin, then the process dies
        val e1 = h.engine(journal = crashing)
        val plan = h.opsPlan(Op.Enable(pkg))
        e1.start(plan, h.tv, plan.hash, h.pass())
        h.settle(e1)
        crashing.crashAfter = null
        assertTrue(crashing.read(plan.id).none { it is JournalRecord.Done || it is JournalRecord.Failed }, crashing.read(plan.id).toString())
        val e2 = DefaultEngine(h.renderer, h.prober, crashing, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        val resumed = assertIs<RunState.Paused>(h.settle(e2))
        assertEquals(0, resumed.at)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(pkg))
        assertTrue(crashing.read(plan.id).none { it is JournalRecord.Done })
    }

    // The same switch-on in doubt that did take is done on resume from the package's own dump.
    @Test fun aSwitchOnInDoubtThatTookIsDoneFromTheDump() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.run("pm disable-user --user 0 ${pkg.value}")
        h.tv.answer = { line -> if (line in disabledLists) denied else null }
        val crashing = MemoryJournal().also { it.crashAfter = 2 }
        val e1 = h.engine(journal = crashing)
        val plan = h.opsPlan(Op.Enable(pkg))
        e1.start(plan, h.tv, plan.hash, h.pass())
        h.settle(e1)
        crashing.crashAfter = null
        val e2 = DefaultEngine(h.renderer, h.prober, crashing, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        val resumed = assertIs<RunState.Paused>(h.settle(e2))
        assertEquals(1, resumed.at)
        assertEquals(EnabledState.ENABLED, h.tv.state(pkg))
        assertTrue(crashing.read(plan.id).filterIsInstance<JournalRecord.Done>().single().resumed)
    }

    // An uninstall in doubt on a TV whose package lists fail, with the package still there: not done.
    @Test fun anUninstallInDoubtIsNotDoneOnAListThatFailed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val pkg = h.debloat.first()
        h.tv.answer = h.keepsOnUninstall(pkg)
        val crashing = MemoryJournal().also { it.crashAfter = 2 }
        val e1 = h.engine(journal = crashing)
        val plan = h.opsPlan(Op.Uninstall(pkg))
        e1.start(plan, h.tv, plan.hash, h.pass())
        h.settle(e1)
        crashing.crashAfter = null
        val e2 = DefaultEngine(h.renderer, h.prober, crashing, h.snapshots, h.plans, h.clock, backgroundScope, h.profiles)
        e2.resume(h.tv.tvId, h.tv)
        val resumed = assertIs<RunState.Paused>(h.settle(e2))
        assertEquals(0, resumed.at)
        assertTrue(crashing.read(plan.id).none { it is JournalRecord.Done })
    }
}
