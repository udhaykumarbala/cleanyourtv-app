package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Issues #3221 (Xiaomi MiTV-AXSO0, Android 9), #3101 (Xiaomi MiTV-MSSP1, Android 9) and #2851 (Sony
// BRAVIA UR3, Android 10, low on storage): "home switch stopped at Install com.gathin.tvhome: failed:
// streamed install failed, the TV is still connected: Protocol(detail=the TV closed the stream)". The
// failure now says how much space /data has, from one read-only df after the hash check failed.
class Issue3221StreamStorageTest {
    private val closed = AdbError.Transport.Protocol("the TV closed the stream")

    private suspend fun installFailure(h: Harness, plan: tv.cleanyour.core.planner.Plan): JournalRecord.Failed {
        val install = plan.steps.first { it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == h.launcher.pkg }.index
        return h.records(plan).filterIsInstance<JournalRecord.Failed>().single { it.i == install }
    }

    @Test fun issue3221TheFailureNamesTheFreeStorage() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.installStreamError = closed
        h.prober.freeMb = 312
        val engine = h.engine()
        val plan = h.setupPlan(takeover = true)
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertTrue(h.records(plan).none { it is JournalRecord.Section }, "the section was never armed")
        assertEquals(h.stock, h.tv.home)
        assertEquals("${DefaultEngine.STREAM_FAILED}$closed [TV storage: 312 MB free]", installFailure(h, plan).error)
        assertEquals(312, InstallStorage.freeMbIn(installFailure(h, plan).error))
        // One read, after the hash check.
        assertEquals(1, h.prober.calls.count { it == "freeDataMb" })
        assertTrue(h.prober.calls.indexOf("freeDataMb") > h.prober.calls.indexOf("installedSha256 ${h.launcher.pkg.value}"))
    }

    // A read that fails leaves the words as they were, with one line that says so.
    @Test fun aStorageReadThatFailsKeepsTheOldWords() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.installStreamError = closed
        h.prober.freeMb = null
        val lines = mutableListOf<String>()
        val engine = h.engine(log = { lines += it })
        val plan = h.setupPlan(takeover = true)
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertIs<RunState.Finished>(h.settle(engine))
        val failed = installFailure(h, plan)
        assertEquals("${DefaultEngine.STREAM_FAILED}$closed", failed.error)
        assertNull(InstallStorage.freeMbIn(failed.error))
        assertTrue("engine: streamed install of ${h.launcher.pkg.value}: the TV's free storage could not be read (facts)" in lines, lines.toString())
    }

    // A stream that landed before the error is Done, and costs no storage read.
    @Test fun aStreamThatLandedIsNotAskedAbout() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.installStreamError = closed
        h.tv.installStreamInstallsFirst = true
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        assertEquals(RunStatus.COMPLETED, assertIs<RunState.Finished>(h.settle(engine)).status)
        assertTrue(h.prober.calls.none { it == "freeDataMb" })
    }

    // The threshold: under 200 MB, or under twice the APK, is clearly low; anything above is not.
    @Test fun clearlyLow() {
        val apk = 6L * 1024 * 1024
        assertTrue(InstallStorage.low(199, apk))
        assertFalse(InstallStorage.low(200, apk))
        assertFalse(InstallStorage.low(312, apk))
        val big = 150L * 1024 * 1024
        assertTrue(InstallStorage.low(299, big))
        assertFalse(InstallStorage.low(300, big))
        assertNull(InstallStorage.freeMbIn("streamed install failed, the TV is still connected: Protocol(detail=the TV closed the stream)"))
        assertNull(InstallStorage.freeMbIn("[TV storage: lots MB free]"))
    }
}
