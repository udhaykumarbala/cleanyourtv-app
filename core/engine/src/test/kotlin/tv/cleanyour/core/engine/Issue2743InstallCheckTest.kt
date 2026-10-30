package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.probe.InstalledApk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// RFC-INSTALL-1, issues #2743 (Xiaomi jaws), #1918 (Hisense songshan), #1510 (Sony BRAVIA VH2) and 17
// more, all Google TVs on Android 11 and 12: "Install com.gathin.tvhome: failed: verify: installed apk
// sha256 <x>, got no readable apk", then home VERIFY_FAILED. The package dump showed versionCode 9,
// but `pm path` named nothing: a launcher on the TV but not installed for user 0. The check stays
// a hash check (SECURITY 3.2); a second read-only call says why, in the failure and in one line.
class Issue2743InstallCheckTest {
    private fun user(id: Int, installed: Boolean) = InstalledApk.UserState(id, installed, hidden = false, suspended = false, stopped = true, enabled = 0)
    private val notForUser0 = InstalledApk(basePaths = 0, hashExit = 1, currentUser = 0, users = listOf(user(0, false), user(10, true)))

    @Test fun issue2743AnUnreadableApkSaysWhyAndTheSwitchStaysClosed() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.installedShaUnreadable = true
        h.prober.installedApkState = notForUser0
        val lines = mutableListOf<String>()
        val engine = h.engine(log = { lines += it })
        val plan = h.setupPlan(takeover = true)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertTrue(h.records(plan).none { it is JournalRecord.Section }, "the section was never armed")
        assertEquals(h.stock, h.tv.home)
        val stock = h.tv.packages[h.stock.pkg]?.enabled
        assertTrue(stock != EnabledState.DISABLED_USER && stock != EnabledState.DISABLED, "the TV's own home screen stays on: $stock")

        val install = plan.steps.first { it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == h.launcher.pkg }.index
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == install }
        assertEquals("verify: installed apk sha256 ${h.apkSha.hex}, got no readable apk (on the TV but not installed for user 0, installed for 1 other user)", failed.error)
        assertTrue("installedApk ${h.launcher.pkg.value}" in h.prober.calls)
        val line = lines.single { it.startsWith("engine: install check of ${h.launcher.pkg.value}: ") }
        assertEquals("engine: install check of com.gathin.tvhome: pm path base.apk lines 0, hash exit 1, current user 0, user 0 installed no, " +
            "hidden no, suspended no, stopped yes, enabled 0, 2 users listed, installed for 1 other", line)
        assertTrue(lines.none { "/data/app" in it || h.apkSha.hex in it }, "no path and no hash in the log")
    }

    // A state read that fails leaves the words as they were, with one line that says so.
    @Test fun aStateThatCannotBeReadKeepsTheOldWords() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.installedShaUnreadable = true
        val lines = mutableListOf<String>()
        val engine = h.engine(log = { lines += it })
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val install = plan.steps.first { it.op is Op.InstallStream }.index
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == install }
        assertTrue(failed.error.endsWith("got no readable apk"), failed.error)
        assertTrue(lines.any { it == "engine: install check of ${(plan.steps[install].op as Op.InstallStream).pkg.value}: its state could not be read (launcher)" }, lines.toString())
    }

    // A hash that reads but differs is not asked about: only the unreadable case costs a second call.
    @Test fun aDifferentHashIsNotAskedAbout() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.installedShaOverride = tv.cleanyour.core.model.Sha256("e".repeat(64))
        h.prober.installedApkState = notForUser0
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        assertTrue(h.prober.calls.none { it.startsWith("installedApk ") })
    }
}
