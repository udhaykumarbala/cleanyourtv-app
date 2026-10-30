package tv.cleanyour.adb.session

import org.junit.Rule
import org.junit.rules.Timeout
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.testing.fakeadbd.FakePackage
import tv.cleanyour.testing.fakeadbd.Faults
import tv.cleanyour.testing.fakeadbd.sha256Hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionTransferTest {
    // Every test is bounded (a hang once blocked the shared forge build lock).
    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    private val dest = RemotePath(RemoteDir.TMP, listOf("a.bin"))

    @Test fun pushVerifiesBothHashesAndReportsProgress() = runTest {
        val rig = Rig(this)
        val s = rig.open()
        assertEquals(0, s.exec(line("mkdir -p /data/local/tmp/cleanyourtv")).exit)
        val data = ByteArray(150_000) { (it % 17).toByte() }
        val b = blob(data)
        val progress = ArrayList<Long>()
        val r = s.push(b, dest) { progress += it }
        assertEquals(data.size.toLong(), r.bytes)
        assertEquals(b.sha256, r.remoteSha256)
        assertContentEquals(data, rig.tv.files[dest.absolute])
        assertEquals(data.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, c) -> c > a })
        assertContentEquals(data, s.pull(dest, 1_000_000))
    }

    @Test fun pushRefusesATamperedBlob() = runTest {
        val s = Rig(this).open()
        s.exec(line("mkdir -p /data/local/tmp/cleanyourtv"))
        val b = blob(ByteArray(1000) { 1 }, sha = sha256Hex(ByteArray(1000) { 2 }))
        val e = assertFailsWith<AdbException> { s.push(b, dest) }
        assertTrue(assertIs<AdbError.Transport.Protocol>(e.error).detail.contains("hash"))
        val wrongSize = blob(ByteArray(10), size = 11)
        assertIs<AdbError.Transport.Protocol>(assertFailsWith<AdbException> { s.push(wrongSize, dest) }.error)
        val missing = blob(ByteArray(10)).also { it.file().delete() }
        assertIs<AdbError.Transport.Protocol>(assertFailsWith<AdbException> { s.push(missing, dest) }.error)
    }

    @Test fun pushIntoAMissingDirectoryIsADeviceFailure() = runTest {
        val s = Rig(this).open()
        val e = assertFailsWith<AdbException> { s.push(blob(ByteArray(10)), dest) }
        val err = assertIs<AdbError.NonZeroExit>(e.error)
        assertTrue(err.stderrHead.contains("couldn't create file"), err.stderrHead)
        assertIs<ConnectionState.Connected>(s.state.value)
    }

    @Test fun pushSizeIsCapped() = runTest {
        val s = Rig(this).open()
        val b = blob(ByteArray(10), size = 201L * 1024 * 1024)
        assertIs<AdbError.Transport.Protocol>(assertFailsWith<AdbException> { s.push(b, dest) }.error)
    }

    @Test fun pullCapAndMissingFile() = runTest {
        val rig = Rig(this)
        val s = rig.open()
        rig.tv.files["/sdcard/cleanyourtv/shot.png"] = ByteArray(5000)
        val path = RemotePath(RemoteDir.RESTORE, listOf("shot.png"))
        assertEquals(5000, s.pull(path, 5000).size)
        val big = assertFailsWith<AdbException> { s.pull(path, 4999) }
        assertEquals("pull larger than 4999 bytes", assertIs<AdbError.Transport.Protocol>(big.error).detail)
        val missing = assertFailsWith<AdbException> { s.pull(RemotePath(RemoteDir.RESTORE, listOf("nope")), 100) }
        assertIs<AdbError.NonZeroExit>(missing.error)
        assertIs<ConnectionState.Connected>(s.state.value)
    }

    @Test fun streamedInstallOutcomes() = runTest {
        val rig = Rig(this)
        val s = rig.open()
        val apk = ByteArray(300_000) { (it * 3).toByte() }
        val b = blob(apk)
        rig.tv.apkCatalog[sha256Hex(apk)] = FakePackage("com.gathin.tvhome", "/data/app/com.gathin.tvhome-1/base.apk", versionCode = 3)
        val progress = ArrayList<Long>()
        assertEquals(InstallOutcome.Installed, s.installStream(b, b.size) { progress += it })
        assertEquals(apk.size.toLong(), progress.last())
        assertEquals(3, rig.tv.packages.getValue("com.gathin.tvhome").versionCode)

        rig.tv.installFailure = "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        assertEquals(InstallOutcome.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE", ""), s.installStream(b, b.size))
        rig.tv.installFailure = null

        val unknown = blob(ByteArray(2000) { 9 })
        val f = assertIs<InstallOutcome.Failed>(s.installStream(unknown, unknown.size))
        assertEquals("INSTALL_PARSE_FAILED_NOT_APK", f.code)
        assertTrue(f.detail.contains("not an APK"))

        val tampered = blob(apk, sha = sha256Hex(ByteArray(1)))
        assertIs<AdbError.Transport.Protocol>(assertFailsWith<AdbException> { s.installStream(tampered, tampered.size) }.error)
        assertIs<AdbError.Transport.Protocol>(assertFailsWith<AdbException> { s.installStream(b, b.size + 1) }.error)
    }

    // RFC-INSTALL-1 (issues #2743, #1918, #1510 and 17 more): a launcher on the TV but not installed
    // for user 0, because a second user keeps it. The streamed install names user 0, so it is
    // installed for user 0 again and `pm path` finds it; "for all users" (the fake keeps Android 11
    // and 12's rule) would have left it out.
    @Test fun aStreamedInstallIsForUser0() = runTest {
        val rig = Rig(this)
        val s = rig.open()
        val apk = ByteArray(5_000) { (it * 7).toByte() }
        val b = blob(apk)
        val pkg = FakePackage("com.gathin.tvhome", "/data/app/com.gathin.tvhome-1/base.apk", versionCode = 9)
        rig.tv.apkCatalog[sha256Hex(apk)] = pkg
        rig.tv.packages[pkg.name] = pkg.copy()
        rig.tv.notForUser0 += pkg.name
        assertTrue(rig.tv.sdk <= 32, "a persona with Android 11 and 12's install rule")
        assertEquals("Success", rig.tv.installApk(apk), "for all users")
        assertTrue(pkg.name in rig.tv.notForUser0, "for all users leaves user 0 out")
        assertEquals(1, s.exec(line("pm path com.gathin.tvhome")).exit)

        assertEquals(InstallOutcome.Installed, s.installStream(b, b.size))
        assertTrue(rig.adbd.events.any { it == "install: Success" })
        assertTrue(pkg.name !in rig.tv.notForUser0)
        val path = s.exec(line("pm path com.gathin.tvhome"))
        assertEquals(0, path.exit)
        assertEquals("package:/data/app/com.gathin.tvhome-1/base.apk\n", path.stdout)
    }

    @Test fun stalledPushTimesOutOnTheIdleClock() = runTest {
        val rig = Rig(this, faults = Faults().apply { stallPushAfterChunks = 1 })
        val s = rig.open()
        s.exec(line("mkdir -p /data/local/tmp/cleanyourtv"))
        val t = currentTime
        val e = assertFailsWith<AdbException> { s.push(blob(ByteArray(300_000)), dest) }
        assertEquals(AdbError.Transport.Timeout("push"), e.error)
        assertEquals(SessionConfig.PUSH_IDLE_MS, currentTime - t)
        assertIs<ConnectionState.Connected>(s.state.value)
    }

    @Test fun hangingInstallTimesOut() = runTest {
        val rig = Rig(this, faults = Faults().apply { installHang = true })
        val s = rig.open()
        val b = blob(ByteArray(1000))
        val t = currentTime
        val e = assertFailsWith<AdbException> { s.installStream(b, b.size) }
        assertEquals(AdbError.Transport.Timeout("install"), e.error)
        assertEquals(SessionConfig.INSTALL_IDLE_MS, currentTime - t)
    }

    @Test fun slowLinkStillCompletes() = runTest {
        val rig = Rig(this, faults = Faults().apply { latencyMs = 40; bytesPerSecond = 200_000 })
        val s = rig.open()
        s.exec(line("mkdir -p /data/local/tmp/cleanyourtv"))
        val data = ByteArray(400_000) { 5 }
        val r = s.push(blob(data), dest)
        assertEquals(data.size.toLong(), r.bytes)
        assertContentEquals(data, s.pull(dest, 1_000_000))
    }
}
