package tv.cleanyour.adb.wire

import org.junit.Rule
import org.junit.rules.Timeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.testing.fakeadbd.FakeTv
import tv.cleanyour.testing.fakeadbd.Faults
import tv.cleanyour.testing.fakeadbd.Personas
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamTest {
    // Every test is bounded (a hang once blocked the shared forge build lock).
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun shellRoundTrip() = runTest {
        val tv = adbd()
        val conn = tv.open()
        val r = conn.shell("echo hello")
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exit)
        val missing = conn.shell("nosuchbinary")
        assertEquals(127, missing.exit)
        assertTrue(missing.stderr.contains("not found"))
        conn.close()
    }

    @Test fun streamEndsWithNullAfterTheTvCloses() = runTest {
        val conn = adbd().open()
        val stream = conn.openStream(AdbService.ShellV2(line("echo x")))
        val reader = AdbStreamReader(stream)
        assertIs<ShellPacket.Stdout>(ShellV2.next(reader))
        assertIs<ShellPacket.Exit>(ShellV2.next(reader))
        assertNull(ShellV2.next(reader))
        assertNull(stream.read())
        stream.close()
        conn.close()
    }

    @Test fun pingAnswersANewline() = runTest {
        val conn = adbd().open()
        val s = conn.openStream(AdbService.Ping)
        assertContentEquals("\n".toByteArray(), s.read())
        assertNull(s.read())
        s.close()
        conn.close()
    }

    @Test fun refusedOpenIsAProtocolError() = runTest {
        val conn = adbd(faults = Faults().apply { refuseOpens = true }).open()
        val e = assertFailsWith<AdbException> { conn.openStream(AdbService.Sync) }
        assertTrue(assertIs<AdbError.Transport.Protocol>(e.error).detail.contains("refused"))
        // The connection itself is still fine.
        assertTrue(!conn.isClosed)
        conn.close()
    }

    @Test fun serviceStringsAreSpelledByTheConnection() = runTest {
        val tv = adbd()
        val conn = tv.open()
        conn.openStream(AdbService.ShellV2(line("echo a"))).close()
        conn.openStream(AdbService.Sync).close()
        conn.openStream(AdbService.ExecInstall(1234)).close()
        conn.openStream(AdbService.Ping).close()
        advanceUntilIdle()
        assertEquals(listOf("shell,v2,raw:echo a", "sync:", "exec:cmd package install --user 0 -S 1234", "exec:echo"), tv.connections.first().openedServices)
        conn.close()
    }

    @Test fun installSizeIsBounded() = runTest {
        val conn = adbd().open()
        assertFailsWith<AdbException> { conn.openStream(AdbService.ExecInstall(0)) }
        assertFailsWith<AdbException> { conn.openStream(AdbService.ExecInstall(200L * 1024 * 1024 + 1)) }
        conn.close()
    }

    @Test fun closingTheConnectionFailsEveryStreamAndRunsTheHandlers() = runTest {
        val conn = adbd().open()
        val closedWith = ArrayList<AdbError>()
        conn.invokeOnClose { closedWith += it }
        val stream = conn.openStream(AdbService.ShellV2(line("sleep 100")))
        conn.close()
        assertEquals(listOf<AdbError>(AdbError.Transport.Closed), closedWith)
        assertTrue(conn.isClosed)
        assertEquals(AdbError.Transport.Closed, assertFailsWith<AdbException> { stream.read() }.error)
        assertEquals(AdbError.Transport.Closed, assertFailsWith<AdbException> { conn.openStream(AdbService.Ping) }.error)
        // A late handler runs at once.
        var late: AdbError? = null
        conn.invokeOnClose { late = it }
        assertEquals(AdbError.Transport.Closed, late)
    }

    @Test fun tvDroppingTheSocketIsClosed() = runTest {
        val tv = adbd()
        val conn = tv.open()
        val closedWith = ArrayList<AdbError>()
        conn.invokeOnClose { closedWith += it }
        tv.closeConnections()
        advanceUntilIdle()
        assertEquals(listOf<AdbError>(AdbError.Transport.Closed), closedWith)
    }

    @Test fun aPeerThatIgnoresFlowControlIsCutOff() = runTest {
        val faults = Faults().apply { ignoreFlowControl = true; shellChunkBytes = 64 }
        val tv = adbd(faults = faults)
        val conn = tv.open()
        val closedWith = ArrayList<AdbError>()
        conn.invokeOnClose { closedWith += it }
        // getprop is a few KiB: dozens of WRTE frames arrive before the consumer reads one.
        val stream = conn.openStream(AdbService.ShellV2(line("getprop")))
        advanceUntilIdle()
        val e = assertFailsWith<AdbException> { while (stream.read() != null) { } }
        assertTrue(assertIs<AdbError.Transport.Protocol>(e.error).detail.contains("flow control"), e.error.toString())
        assertEquals(1, closedWith.size)
        assertTrue(conn.isClosed)
    }

    @Test fun writesAreChunkedToThePeerWindow() = runTest {
        val small = Personas.miTv().let { FakeTv(it.name, it.getpropText().lines().filter { l -> l.isNotEmpty() }, it.packages.values.toList(), emptyMap(), it.home, it.features, maxPayload = 4096) }
        val tv = adbd(small)
        val conn = tv.open()
        assertEquals(4096, conn.maxPayload)
        val payload = ByteArray(50_000) { (it * 7).toByte() }
        val stream = conn.openStream(AdbService.Sync)
        val sync = SyncClient(stream)
        var sent = false
        sync.send("/data/local/tmp/big.bin", 0x1A4, 0, chunks = { if (sent) null else { sent = true; payload } })
        sync.quit()
        stream.close()
        assertContentEquals(payload, tv.tv.files["/data/local/tmp/big.bin"])
        conn.close()
    }

    @Test fun manyStreamsInSequenceReuseNothing() = runTest {
        val tv = adbd()
        val conn = tv.open()
        for (i in 1..50) assertEquals("$i\n", conn.shell("echo $i").stdout)
        conn.close()
    }

    @Test fun cancelledReadLeavesTheConnectionUp() = runTest {
        val tv = adbd()
        val conn = tv.open()
        val stream = conn.openStream(AdbService.ShellV2(line("sleep 30")))
        val r = kotlinx.coroutines.withTimeoutOrNull(1_000) { stream.read() }
        assertNull(r)
        stream.close()
        assertTrue(!conn.isClosed)
        assertEquals("ok\n", conn.shell("echo ok").stdout)
        conn.close()
    }
}
