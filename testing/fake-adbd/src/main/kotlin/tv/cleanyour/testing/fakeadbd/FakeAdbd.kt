package tv.cleanyour.testing.fakeadbd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbMessage
import tv.cleanyour.adb.wire.AdbProtocol
import tv.cleanyour.adb.wire.AdbProtocol.A_AUTH
import tv.cleanyour.adb.wire.AdbProtocol.A_CLSE
import tv.cleanyour.adb.wire.AdbProtocol.A_CNXN
import tv.cleanyour.adb.wire.AdbProtocol.A_OKAY
import tv.cleanyour.adb.wire.AdbProtocol.A_OPEN
import tv.cleanyour.adb.wire.AdbProtocol.A_WRTE
import tv.cleanyour.adb.wire.AdbPublicKeyCodec
import tv.cleanyour.adb.wire.AdbSignature
import tv.cleanyour.adb.wire.AdbSigner
import tv.cleanyour.adb.wire.AdbSocket
import tv.cleanyour.adb.wire.AdbStream
import tv.cleanyour.adb.wire.AdbStreamReader
import tv.cleanyour.adb.wire.FrameCodec
import tv.cleanyour.adb.wire.ShellV2
import tv.cleanyour.adb.wire.SocketFactory
import tv.cleanyour.adb.wire.SyncProtocol
import tv.cleanyour.adb.wire.protocolError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.Endpoint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// How the TV's Allow dialog is answered for an unknown key (D38).
sealed interface AllowBehaviour {
    data class Allow(val afterMs: Long = 0, val always: Boolean = true) : AllowBehaviour
    data class Deny(val afterMs: Long = 0) : AllowBehaviour
    data object Ignore : AllowBehaviour
}

// Fault knobs (ARCHITECTURE 6.4). All mutable so a test can flip one mid-session.
class Faults {
    var latencyMs = 0L
    var jitterMs = 0L
    var bytesPerSecond = 0L
    var dropAfterBytes = -1L
    var halfOpenAfterFrames = -1         // the fake stops reading after this many frames, socket stays open
    var stallPushAfterChunks = -1        // sync SEND: stop acknowledging after this many DATA chunks
    var ignoreFlowControl = false        // send WRTE frames without waiting for OKAY (hostile)
    var pmDelayMs = 0L                   // every pm call stalls this long (1 GB TVs compacting)
    var pmBusy = false                   // every pm call fails with a busy error
    var installHang = false              // the streamed install never answers
    var singleThreaded = false           // one stream served at a time
    var allow: AllowBehaviour = AllowBehaviour.Allow()
    var garbageBeforeConnect: ByteArray? = null   // hostile bytes where the handshake expects a frame
    var garbageAfterConnect: ByteArray? = null    // hostile bytes right after CNXN
    var shellChunkBytes = 4096
    var splitPackets = false             // shell packets cut across two WRTE frames
    var wrongTvOnReconnect: FakeTv? = null   // the next connection is served by another TV (D37)
    var rebootDownMs = 3_000L
    var bootSettleMs = 2_000L
    var cnxnDelayMs = 0L
    var requireTls = false               // answer CNXN with STLS (an Android 11+ set in TLS-only mode)
    var refuseOpens = false              // answer every OPEN with CLSE
}

// An in-process adbd: CNXN, AUTH with real signature checks, shell v2, sync, exec. One instance
// is one TV; connect() gives the client end of a fresh socket pair.
class FakeAdbd(tv: FakeTv, val faults: Faults = Faults(), private val scope: CoroutineScope) {
    @Volatile var tv: FakeTv = tv
        private set
    @Volatile var shell: FakeShell = FakeShell(tv, faults)
        private set
    val trustedKeys = CopyOnWriteArrayList<RSAPublicKey>()
    val connections = CopyOnWriteArrayList<ServerConnection>()
    val events = CopyOnWriteArrayList<String>()
    val prompts = AtomicInteger(0)
    @Volatile var adbEnabled = true
    @Volatile var rebooting = false
    @Volatile var acceptConnections = true
    private var tcpServer: ServerSocket? = null

    fun trust(signer: AdbSigner) { trustedKeys += AdbPublicKeyCodec.parseLine(signer.publicKeyLine) }

    fun swapTv(other: FakeTv) { tv = other; shell = FakeShell(other, faults) }

    suspend fun connect(): AdbSocket {
        if (!adbEnabled || rebooting || !acceptConnections) throw ConnectException("fake adbd: connection refused")
        faults.wrongTvOnReconnect?.let { swapTv(it); faults.wrongTvOnReconnect = null }
        val (client, server) = PipeSocket.pair(WireShaping(faults.latencyMs, faults.jitterMs, faults.bytesPerSecond, faults.dropAfterBytes))
        serve(server)
        return client
    }

    fun serve(socket: AdbSocket): Job {
        val c = ServerConnection(this, socket)
        connections += c
        return scope.launch { try { c.run() } finally { connections -= c } }
    }

    fun socketFactory(host: String = DEFAULT_HOST): SocketFactory = FakeAdbdSocketFactory(mapOf(host to this))

    fun onReboot() {
        rebooting = true
        tv.bootCompleted = false
        tv.pmReady = false
        tv.setProp("sys.boot_completed", "0")
        closeConnections()
        scope.launch {
            delay(faults.rebootDownMs)
            tv.bootId = UUID.randomUUID().toString()
            rebooting = false
            delay(faults.bootSettleMs)
            tv.bootCompleted = true
            tv.pmReady = true
            tv.setProp("sys.boot_completed", "1")
        }
    }

    fun onAdbOff() {
        adbEnabled = false
        events += "adbd stopped (adb_enabled 0)"
        closeConnections()
    }

    fun closeConnections() { for (c in connections.toList()) c.close() }

    // Loopback TCP front for the real PlainSocketFactory; returns the port.
    suspend fun listenTcp(): Int = withContext(Dispatchers.IO) {
        val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        tcpServer = ss
        scope.launch(Dispatchers.IO) {
            while (true) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                if (!adbEnabled || rebooting || !acceptConnections) { s.close(); continue }
                serve(BlockingAdbSocket(s))
            }
        }
        ss.localPort
    }

    fun stop() {
        closeConnections()
        runCatching { tcpServer?.close() }
    }

    companion object {
        const val DEFAULT_HOST = "192.0.2.10"
    }
}

// RFC-INSTALL-1: the streamed install the app sends, for user 0.
private const val INSTALL_USER0 = "cmd package install --user 0 -S "

class FakeAdbdSocketFactory(private val byHost: Map<String, FakeAdbd>) : SocketFactory {
    val attempts = CopyOnWriteArrayList<Endpoint>()
    override suspend fun connect(endpoint: Endpoint, timeoutMs: Long): AdbSocket {
        attempts += endpoint
        val d = byHost[endpoint.host] ?: throw NoRouteToHostException("fake: no host ${endpoint.host}")
        if (endpoint.port != 5555) throw ConnectException("fake: port ${endpoint.port} closed")
        return d.connect()
    }
}

// One accepted connection.
class ServerConnection(private val adbd: FakeAdbd, private val socket: AdbSocket) {
    private val writeMutex = Mutex()
    private val byClientId = ConcurrentHashMap<Int, ServerStream>()
    private val nextId = AtomicInteger(0)
    private val serviceGate = Mutex()
    private var framesRead = 0
    @Volatile var clientMaxPayload = 256 * 1024
        private set
    @Volatile var closed = false
        private set
    val openedServices = CopyOnWriteArrayList<String>()
    val ignoreFlowControl: Boolean get() = adbd.faults.ignoreFlowControl
    private val faults get() = adbd.faults
    private val tv get() = adbd.tv

    suspend fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = FrameCodec.EMPTY, off: Int = 0, len: Int = payload.size) {
        val frame = FrameCodec.encode(command, arg0, arg1, payload, off, len)
        writeMutex.withLock {
            socket.write(frame, 0, frame.size)
            socket.flush()
        }
    }

    private suspend fun readFrame(): AdbMessage? {
        val m = FrameCodec.readMessage(socket)
        if (m != null) framesRead++
        return m
    }

    // Services run in their own job, so the connection ends when the socket does, whatever a
    // service is waiting for; close() cancels them.
    private val serviceJob = SupervisorJob()

    suspend fun run() {
        val services = CoroutineScope(currentCoroutineContext().minusKey(Job) + serviceJob)
        try {
            val cnxn = readFrame() ?: return
            if (cnxn.command != A_CNXN) { adbd.events += "protocol: expected CNXN, got ${AdbProtocol.name(cnxn.command)}"; return }
            if (cnxn.arg1 < AdbProtocol.MIN_PEER_PAYLOAD) { adbd.events += "protocol: client window ${cnxn.arg1}"; return }
            clientMaxPayload = minOf(cnxn.arg1, AdbProtocol.MAX_PAYLOAD)
            faults.garbageBeforeConnect?.let { socket.write(it, 0, it.size); socket.flush() }
            if (faults.requireTls) {
                send(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0)
                adbd.events += "sent STLS"
                awaitCancellation()
            }
            if (tv.adbSecure && !authenticate()) return
            if (faults.cnxnDelayMs > 0) delay(faults.cnxnDelayMs)
            val banner = tv.banner().toByteArray(Charsets.US_ASCII)
            send(A_CNXN, AdbProtocol.VERSION, tv.maxPayload, banner.copyOf(banner.size + 1))
            faults.garbageAfterConnect?.let { socket.write(it, 0, it.size); socket.flush() }
            adbd.events += "connected"
            while (true) {
                if (faults.halfOpenAfterFrames >= 0 && framesRead >= faults.halfOpenAfterFrames) awaitCancellation()
                val m = readFrame() ?: break
                dispatch(m, services)
            }
        } catch (e: AdbException) {
            adbd.events += "protocol error from the client: ${e.error}"
        } catch (e: IOException) {
            adbd.events += "socket: ${e.message}"
        } finally {
            close()
        }
    }

    private fun randomToken(): ByteArray = ByteArray(AdbProtocol.TOKEN_BYTES).also { SecureRandom().nextBytes(it) }

    private suspend fun authenticate(): Boolean {
        var token = randomToken()
        send(A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
        while (true) {
            val m = readFrame() ?: return false
            if (m.command != A_AUTH) { adbd.events += "protocol: expected AUTH, got ${AdbProtocol.name(m.command)}"; return false }
            when (m.arg0) {
                AdbProtocol.AUTH_SIGNATURE -> {
                    if (adbd.trustedKeys.any { AdbSignature.verify(it, token, m.payload) }) {
                        adbd.events += "auth: signature accepted"
                        return true
                    }
                    adbd.events += "auth: signature rejected"
                    token = randomToken()
                    send(A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
                }
                AdbProtocol.AUTH_RSAPUBLICKEY -> {
                    val key = try {
                        AdbPublicKeyCodec.parseLine(String(m.payload, Charsets.US_ASCII))
                    } catch (e: Exception) {
                        adbd.events += "auth: malformed public key"
                        return false
                    }
                    adbd.prompts.incrementAndGet()
                    adbd.events += "allow prompt: ${AdbPublicKeyCodec.fingerprint(key)}"
                    when (val b = faults.allow) {
                        is AllowBehaviour.Allow -> {
                            delay(b.afterMs)
                            if (b.always) adbd.trustedKeys += key
                            adbd.events += "allow: tapped (always=${b.always})"
                            return true
                        }
                        is AllowBehaviour.Deny -> {
                            delay(b.afterMs)
                            adbd.events += "allow: denied"
                            token = randomToken()
                            send(A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token)
                        }
                        AllowBehaviour.Ignore -> awaitCancellation()
                    }
                }
                else -> { adbd.events += "protocol: AUTH type ${m.arg0}"; return false }
            }
        }
    }

    private suspend fun dispatch(m: AdbMessage, scope: CoroutineScope) {
        when (m.command) {
            A_OPEN -> {
                val service = String(m.payload, Charsets.UTF_8).trimEnd('\u0000')
                openedServices += service
                if (!accepts(service)) {
                    adbd.events += "refused: ${service.take(40)}"
                    send(A_CLSE, 0, m.arg0)
                    return
                }
                val s = ServerStream(this, clientId = m.arg0, ourId = nextId.incrementAndGet())
                // Fault knob: a sync stream stops acknowledging after the SEND request and N chunks.
                if (service == "sync:" && faults.stallPushAfterChunks >= 0) s.ackLimit = 1 + faults.stallPushAfterChunks
                byClientId[s.clientId] = s
                send(A_OKAY, s.ourId, s.clientId)
                scope.launch { serve(s, service) }
            }
            // Like adbd, a WRTE is acknowledged once it is queued for the service, not when the
            // service reads it, so a service that answers before reading never deadlocks the client.
            A_WRTE -> byClientId[m.arg0]?.takeIf { it.ourId == m.arg1 }?.let { s ->
                s.inbox.trySend(m.payload)
                if (!s.ackPaused && s.framesIn++ < s.ackLimit) send(A_OKAY, s.ourId, s.clientId)
            }
            A_OKAY -> byClientId[m.arg0]?.takeIf { it.ourId == m.arg1 }?.okays?.trySend(Unit)
            A_CLSE -> byClientId.remove(m.arg0)?.onClientClose()
            else -> adbd.events += "unexpected ${AdbProtocol.name(m.command)} after connect"
        }
    }

    private fun accepts(service: String): Boolean =
        !faults.refuseOpens && (service.startsWith("shell,v2,raw:") || service == "sync:" || service.startsWith("exec:"))

    private suspend fun serve(s: ServerStream, service: String) {
        val gate = if (faults.singleThreaded) serviceGate else null
        gate?.lock()
        try {
            when {
                service.startsWith("shell,v2,raw:") -> shellV2(s, service.removePrefix("shell,v2,raw:"))
                service == "sync:" -> sync(s)
                service.startsWith("exec:") -> exec(s, service.removePrefix("exec:"))
            }
        } catch (e: IOException) {
        } catch (e: AdbException) {
            adbd.events += "stream ${s.ourId}: ${e.error}"
        } finally {
            gate?.unlock()
            s.finish()
        }
    }

    private suspend fun writePackets(s: ServerStream, id: Int, bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val len = minOf(faults.shellChunkBytes, bytes.size - off)
            val packet = ShellV2.encode(id, bytes.copyOfRange(off, off + len))
            if (faults.splitPackets && packet.size > 2) {
                s.write(packet.copyOfRange(0, packet.size / 2))
                s.write(packet.copyOfRange(packet.size / 2, packet.size))
            } else {
                s.write(packet)
            }
            off += len
        }
    }

    private suspend fun shellV2(s: ServerStream, line: String) {
        val out = adbd.shell.run(line)
        if (out.effect == Effect.Hang) awaitCancellation()
        // The effect happens even when the client hangs up first (a real reboot does not wait).
        try {
            writePackets(s, ShellV2.ID_STDOUT, out.stdout)
            writePackets(s, ShellV2.ID_STDERR, out.stderr)
            s.write(ShellV2.exitPacket(out.exit))
        } finally {
            applyEffect(out.effect)
        }
    }

    private fun applyEffect(effect: Effect?) {
        when (effect) {
            Effect.Reboot -> adbd.onReboot()
            Effect.AdbOff -> adbd.onAdbOff()
            else -> {}
        }
    }

    private suspend fun exec(s: ServerStream, command: String) {
        when {
            command == "echo" -> s.write("\n".encodeToByteArray())
            // RFC-INSTALL-1: the app sends `--user 0`; the form without a user stays for older callers.
            command.startsWith("cmd package install -S ") || command.startsWith(INSTALL_USER0) -> {
                val forUser0 = command.startsWith(INSTALL_USER0)
                val size = command.removePrefix(if (forUser0) INSTALL_USER0 else "cmd package install -S ").trim().toLongOrNull()
                if (size == null || size < 0 || size > 200L * 1024 * 1024) { s.write("Failure [INSTALL_FAILED_INVALID_APK: bad size]\n".encodeToByteArray()); return }
                val reader = AdbStreamReader(s)
                val bytes = ByteArrayOutputStream()
                var left = size
                while (left > 0) {
                    val chunk = reader.readFully(minOf(left, 65536L).toInt()) ?: return
                    bytes.write(chunk)
                    left -= chunk.size
                }
                if (faults.installHang) awaitCancellation()
                val result = tv.installApk(bytes.toByteArray(), forUser0)
                adbd.events += "install: $result"
                s.write((result + "\n").encodeToByteArray())
            }
            else -> {
                val out = adbd.shell.run(command)
                if (out.effect == Effect.Hang) awaitCancellation()
                try { s.write(out.stdout + out.stderr) } finally { applyEffect(out.effect) }
            }
        }
    }

    private suspend fun sync(s: ServerStream) {
        val reader = AdbStreamReader(s)
        while (true) {
            val p = SyncProtocol.readPacket(reader) ?: return
            when (p.id) {
                SyncProtocol.SEND -> {
                    val spec = String(reader.readFully(boundedPath(p.arg)) ?: return, Charsets.UTF_8)
                    val path = spec.substringBeforeLast(',')
                    if (!tv.dirExists(tv.parentDir(path))) { fail(s, "couldn't create file: No such file or directory"); return }
                    val data = ByteArrayOutputStream()
                    var chunks = 0
                    while (true) {
                        val d = SyncProtocol.readPacket(reader) ?: return
                        when (d.id) {
                            SyncProtocol.DATA -> {
                                if (d.arg < 0 || d.arg > SyncProtocol.DATA_MAX) { fail(s, "data size exceeds ${SyncProtocol.DATA_MAX}"); return }
                                chunks++
                                if (faults.stallPushAfterChunks >= 0 && chunks > faults.stallPushAfterChunks) { s.ackPaused = true; awaitCancellation() }
                                data.write(reader.readFully(d.arg) ?: return)
                            }
                            SyncProtocol.DONE -> {
                                tv.files[path] = data.toByteArray()
                                adbd.events += "push: $path (${data.size()} bytes)"
                                s.write(SyncProtocol.header(SyncProtocol.OKAY, 0))
                                break
                            }
                            else -> { fail(s, "unexpected ${d.id} during SEND"); return }
                        }
                    }
                }
                SyncProtocol.RECV -> {
                    val path = String(reader.readFully(boundedPath(p.arg)) ?: return, Charsets.UTF_8)
                    val bytes = tv.files[path] ?: run { fail(s, "open failed: No such file or directory"); return }
                    var off = 0
                    while (off < bytes.size) {
                        val len = minOf(SyncProtocol.DATA_MAX, bytes.size - off)
                        s.write(SyncProtocol.header(SyncProtocol.DATA, len) + bytes.copyOfRange(off, off + len))
                        off += len
                    }
                    s.write(SyncProtocol.header(SyncProtocol.DONE, 0))
                    adbd.events += "pull: $path"
                }
                SyncProtocol.STAT -> {
                    val path = String(reader.readFully(boundedPath(p.arg)) ?: return, Charsets.UTF_8)
                    val bytes = tv.files[path]
                    val reply = ByteArray(16)
                    System.arraycopy("STAT".toByteArray(Charsets.US_ASCII), 0, reply, 0, 4)
                    FrameCodec.putIntLe(reply, 4, if (bytes != null) 0x81a4 else if (tv.dirExists(path)) 0x41ed else 0)
                    FrameCodec.putIntLe(reply, 8, bytes?.size ?: 0)
                    FrameCodec.putIntLe(reply, 12, 0)
                    s.write(reply)
                }
                SyncProtocol.QUIT -> return
                else -> { fail(s, "unknown command"); return }
            }
        }
    }

    private fun boundedPath(n: Int): Int {
        if (n < 0 || n > SyncProtocol.MAX_PATH_BYTES) throw protocolError("sync path of $n bytes")
        return n
    }

    private suspend fun fail(s: ServerStream, message: String) {
        val m = message.encodeToByteArray()
        s.write(SyncProtocol.header(SyncProtocol.FAIL, m.size) + m)
    }

    fun close() {
        if (closed) return
        closed = true
        serviceJob.cancel()
        for (s in byClientId.values) s.onClientClose()
        byClientId.clear()
        runCatching { socket.close() }
    }

    internal fun forget(s: ServerStream) { byClientId.remove(s.clientId) }
}

// The fake's side of one stream; implements AdbStream so the shared readers work on it.
class ServerStream(private val conn: ServerConnection, val clientId: Int, val ourId: Int) : AdbStream {
    val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    val okays = Channel<Unit>(1)
    @Volatile var clientClosed = false
        private set
    @Volatile var ackPaused = false
    @Volatile var ackLimit = Int.MAX_VALUE
    var framesIn = 0
    @Volatile private var finished = false

    fun onClientClose() {
        clientClosed = true
        inbox.close()
        okays.close()
    }

    override suspend fun read(): ByteArray? {
        val r = inbox.receiveCatching()
        if (r.isClosed) return null
        return r.getOrThrow()
    }

    override suspend fun write(bytes: ByteArray) {
        if (clientClosed) throw IOException("client closed the stream")
        var off = 0
        while (off < bytes.size) {
            val len = minOf(conn.clientMaxPayload, bytes.size - off)
            conn.send(A_WRTE, ourId, clientId, bytes, off, len)
            if (!conn.ignoreFlowControl) {
                val ok = okays.receiveCatching()
                if (ok.isClosed) throw IOException("client closed the stream")
            }
            off += len
        }
    }

    override fun close() { }

    suspend fun finish() {
        if (finished) return
        finished = true
        conn.forget(this)
        if (!clientClosed && !conn.closed) {
            try { conn.send(A_CLSE, ourId, clientId) } catch (e: IOException) { } catch (e: CancellationException) { throw e }
        }
        inbox.close()
        okays.close()
    }
}
