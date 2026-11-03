package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.probe.InstalledApk
import tv.cleanyour.core.session.ExecResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issues #5242 (TiVo Stream 4K, Android 12) and #5295 (Changhong, Android 11), 0.4.8-beta2: the streamed
// install answered Success, then the check's package dump showed no versionCode: "verify: expected
// Package [com.gathin.tvhome] with versionCode=9, got not installed", run after run. The check stays as it
// is; the state read RFC-INSTALL-1 added for an unreadable APK now says why here too.
class Issue5242InstallCheckTest {
    private val stateOk = InstalledApk(basePaths = 1, hashExit = 0, currentUser = 0,
        users = listOf(InstalledApk.UserState(0, installed = true, hidden = false, suspended = false, stopped = true, enabled = 0)))

    @Test fun issue5242ANotInstalledCheckSaysWhatTheTvShows() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.answer = { line -> if (line == "dumpsys package ${h.launcher.pkg.value}") ExecResult(0, "", "", false, 1) else null }
        h.prober.installedApkState = stateOk
        val lines = mutableListOf<String>()
        val engine = h.engine(log = { lines += it })
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val install = plan.steps.first { it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == h.launcher.pkg }
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == install.index }
        val vc = (install.op as Op.InstallStream).versionCode
        assertEquals("verify: expected Package [com.gathin.tvhome] with versionCode=$vc, got not installed (cause unknown)", failed.error)
        assertTrue(lines.any { it == "engine: install check of com.gathin.tvhome: pm path base.apk lines 1, hash exit 0, current user 0, user 0 installed yes, " +
            "hidden no, suspended no, stopped yes, enabled 0, 1 user listed, installed for 0 other" }, lines.toString())
    }

    // Another versionCode is not asked about: only "not installed" costs the second read.
    @Test fun anotherVersionIsNotAskedAbout() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.answer = { line -> if (line == "dumpsys package ${h.launcher.pkg.value}") ExecResult(0, "Package [${h.launcher.pkg.value}] (1a2b3c):\n  versionCode=7 minSdk=26\n", "", false, 1) else null }
        h.prober.installedApkState = stateOk
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val install = plan.steps.first { it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == h.launcher.pkg }
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == install.index }
        assertTrue(failed.error.endsWith("got versionCode=7"), failed.error)
        assertTrue(h.prober.calls.none { it.startsWith("installedApk ") })
    }
}
