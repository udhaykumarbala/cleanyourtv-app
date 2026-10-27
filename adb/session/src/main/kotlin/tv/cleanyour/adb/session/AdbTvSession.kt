package tv.cleanyour.adb.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbConnection
import tv.cleanyour.adb.wire.AdbService
import tv.cleanyour.adb.wire.AdbSigner
import tv.cleanyour.adb.wire.AdbStreamReader
import tv.cleanyour.adb.wire.ShellPacket
import tv.cleanyour.adb.wire.ShellV2
import tv.cleanyour.adb.wire.SocketFactory
import tv.cleanyour.adb.wire.SyncClient
import tv.cleanyour.adb.wire.SyncFailedException
import tv.cleanyour.adb.wire.SyncProtocol
import tv.cleanyour.adb.wire.TlsUpgrader
import tv.cleanyour.adb.wire.protocolError
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.BootOutcome
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.PushResult
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.session.TvIdentity
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.Clock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

// How a session gets back to its TV.
internal class Link(
    val socketFactory: SocketFactory,
    val signer: AdbSigner,
    val tls: TlsUpgrader?,
    val onAwaitingAllow: suspend (String) -> Unit,
    // 0.3.6: a failed socket connect, for the diagnostics log (ConnectFailure).
    val onConnectFailure: (Throwable) -> Unit = {},
    // Issues #325, #620: a handshake or identity read that failed inside the app (HandshakeFailure).
    val onHandshakeFailure: (Throwable) -> Unit = {},
)

// TvSession over :adb:wire (PRODUCT-SPEC 5.3, ARCHITECTURE 3): one call at a time, two clocks
// and two caps per exec, holds with a 60 s idle close, a keepalive ping while held, reconnect with
// a backoff budget, identity re-checked on every connect (D37), boot wait after reboot.
class AdbTvSession internal constructor(
    override val identity: TvIdentity,
    @Volatile private var endpoint: Endpoint,
    initial: AdbConnection,
    private val link: Link,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val onStateChange: (TvId, ConnectionState) -> Unit,
    private val onClosed: (AdbTvSession) -> Unit,
    // Where blob files on the phone are read; tests pass the test dispatcher's context.
    private val blobIo: CoroutineContext = Dispatchers.IO,
) : TvSession {
    override val tvId: TvId get() = identity.tvId

    private val mutex = Mutex()
    @Volatile private var connection: AdbConnection? = initial
    @Volatile private var adoptable: AdbConnection? = null
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Connected(endpoint, initial.features, initial.maxPayload))
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private val holds = AtomicInteger(0)
    private val closedForGood = AtomicBoolean(false)
    private var idleJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var lastBootId: String? = null
    val isClosedForGood: Boolean get() = closedForGood.get()

    init {
        watch(initial)
        startIdleTimer()
    }

    private fun setState(s: ConnectionState) {
        mutableState.value = s
        onStateChange(tvId, s)
    }

    private fun watch(c: AdbConnection) {
        c.invokeOnClose { error ->
            if (connection !== c || closedForGood.get()) return@invokeOnClose
            connection = null
            val s = mutableState.value
            if (s is ConnectionState.Connected) {
                if (holds.get() > 0) {
                    setState(ConnectionState.Reconnecting(error, 0, clock.nowMs()))
                    scope.launch { reconnectInBackground() }
                } else {
                    setState(ConnectionState.Idle)
                }
            }
        }
    }

    // Waits its turn behind the call in flight (which saw the same drop), then reconnects only if
    // a hold still wants the socket and nobody reconnected meanwhile.
    private suspend fun reconnectInBackground() {
        mutex.withLock {
            if (closedForGood.get()) return
            if (connection?.isClosed == false) return
            if (holds.get() == 0) {
                if (mutableState.value is ConnectionState.Reconnecting) setState(ConnectionState.Idle)
                return
            }
            try {
                ensureConnectedLocked()
            } catch (e: AdbException) {
                // ensureConnectedLocked has published Failed; the next call reports it.
            }
        }
    }

    // A fresh connection from the manager for this TV (the flows opened the same TV again).
    internal fun adopt(conn: AdbConnection, at: Endpoint) {
        endpoint = at
        if (mutex.tryLock()) {
            try { installLocked(conn) } finally { mutex.unlock() }
        } else {
            adoptable?.let { scope.launch { it.close() } }
            adoptable = conn
        }
    }

    private fun installLocked(conn: AdbConnection) {
        val old = connection
        connection = conn
        watch(conn)
        setState(ConnectionState.Connected(endpoint, conn.features, conn.maxPayload))
        if (old != null && old !== conn) scope.launch { old.close() }
    }

    // Mutex held. Returns the live connection, reconnecting with the budget of ARCHITECTURE 3.4.
    private suspend fun ensureConnectedLocked(): AdbConnection {
        if (closedForGood.get()) throw AdbException(AdbError.Transport.Closed)
        adoptable?.let { adoptable = null; if (!it.isClosed) installLocked(it) }
        connection?.takeIf { !it.isClosed }?.let { return it }
        connection = null
        val fromIdle = mutableState.value is ConnectionState.Idle
        var last: AdbError = (mutableState.value as? ConnectionState.Reconnecting)?.reason
            ?: (mutableState.value as? ConnectionState.Failed)?.error
            ?: AdbError.Transport.Closed
        for (attempt in 1..SessionConfig.RECONNECT_ATTEMPTS) {
            val wait = if (attempt == 1 && fromIdle) 0L else Backoff.delayMs(attempt)
            if (!fromIdle || attempt > 1) setState(ConnectionState.Reconnecting(last, attempt, clock.nowMs() + wait))
            if (wait > 0) delay(wait)
            setState(ConnectionState.Connecting(endpoint, attempt))
            try {
                val conn = connectOnce()
                installLocked(conn)
                return conn
            } catch (e: AdbException) {
                last = e.error
                if (e.error is AdbError.Policy || e.error is AdbError.Auth || e.error is AdbError.Transport.NoShellV2) {
                    setState(ConnectionState.Failed(e.error))
                    throw e
                }
            }
        }
        setState(ConnectionState.Failed(last))
        throw AdbException(AdbError.Transport.Closed)
    }

    // Handshakes and pumps run on the manager's dispatcher, never on the caller's.
    private val dispatcher: CoroutineContext = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default

    // One TCP connect, handshake and identity check; the connection is closed on any failure.
    private suspend fun connectOnce(): AdbConnection = withContext(dispatcher) {
        val socket = try {
            link.socketFactory.connect(endpoint, SessionConfig.CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            link.onConnectFailure(e)
            throw AdbException(classifyConnectFailure(e))
        }
        val conn = try {
            AdbConnection.open(
                socket, link.signer, link.tls,
                onAwaitingAllow = { fp -> setState(ConnectionState.AwaitingAllow(clock.nowMs(), fp)); link.onAwaitingAllow(fp) },
                firstAuthTimeoutMs = SessionConfig.FIRST_AUTH_TIMEOUT_MS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            // Issues #325, #620: typed, so the reconnect loop and the engine see an AdbException (R0: 7).
            // Throwable, so an Error such as NoSuchFieldError is typed and named as well.
            link.onHandshakeFailure(e)
            throw HandshakeFailure.typed(e)
        }
        try {
            if ("shell_v2" !in conn.features) throw AdbException(AdbError.Transport.NoShellV2)
            val found = Identity.read(conn, clock)
            if (found.tvId != identity.tvId) throw AdbException(AdbError.Policy.WrongTv(identity.tvId, found.tvId))
            conn
        } catch (t: Throwable) {
            conn.close()
            if (t is CancellationException || t is VirtualMachineError) throw t
            link.onHandshakeFailure(t)
            throw HandshakeFailure.typed(t)
        }
    }

    // Single flight: every call waits for the one before it. Transport.Closed is retried once for
    // the idempotent classes; timeouts and everything else are results or final failures.
    private suspend fun <T> call(retry: RetryClass, block: suspend (AdbConnection) -> T): T = mutex.withLock {
        var retried = false
        while (true) {
            val conn = ensureConnectedLocked()
            try {
                return@withLock block(conn)
            } catch (e: AdbException) {
                if (e.error is AdbError.Transport.Timeout) dropIfSilentLocked(conn, e.error)
                val retriable = e.error == AdbError.Transport.Closed && retry != RetryClass.NEVER && !retried && !closedForGood.get()
                if (!retriable) throw e
                retried = true
                if (connection?.isClosed != false) connection = null
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    // Issue #616 (TCL C735 on a Wi-Fi that kept dropping it): a call that timed out left its
    // connection in place, as 5.3 wants for a TV that is only slow. For a TV that had left the
    // network the session went on saying Connected: the keepalive that would notice runs only while
    // the session is idle and held, and a paused run holds nothing. So the reconnect watcher said the
    // TV was back at once, and Carry on started again on the dead socket and waited out every read.
    // Now a timeout is followed by one keepalive (5 s, mutex held). A TV that answers keeps its
    // connection (5.3); one that does not has it dropped, the state says so (Reconnecting while held,
    // else Idle), and the next call or the background reconnect opens a fresh socket.
    private suspend fun dropIfSilentLocked(c: AdbConnection, error: AdbError) {
        if (closedForGood.get() || c.isClosed || connection !== c) return
        val alive = try {
            bounded(SessionConfig.PING_TIMEOUT_MS, "ping") {
                val s = c.openStream(AdbService.Ping)
                try { while (s.read() != null) { } } finally { s.close() }
            }
            true
        } catch (e: AdbException) {
            false
        }
        if (alive || connection !== c) return
        connection = null
        val held = holds.get() > 0
        setState(if (held) ConnectionState.Reconnecting(error, 0, clock.nowMs()) else ConnectionState.Idle)
        c.close()
        if (held) scope.launch { reconnectInBackground() }
    }

    override suspend fun exec(line: ShellLine, spec: ExecSpec): ExecResult {
        PmGuard.refusal(line.text)?.let { throw AdbException(AdbError.Policy.GuardFailed(0, it)) }
        return call(spec.retry) { conn -> Exec.run(conn, line, spec, clock) }
    }

    // Lines of stdout, each cut at 8 KiB; output over stdoutCap fails the flow (a parser must never
    // see a silently shortened listing); a non-zero exit fails it with NonZeroExit at the end.
    override fun lines(line: ShellLine, spec: ExecSpec): Flow<String> = flow {
        PmGuard.refusal(line.text)?.let { throw AdbException(AdbError.Policy.GuardFailed(0, it)) }
        call(RetryClass.NEVER) { conn -> streamLines(conn, line, spec, this) }
    }

    private suspend fun streamLines(conn: AdbConnection, line: ShellLine, spec: ExecSpec, out: FlowCollector<String>) {
        val stream = conn.openBounded(AdbService.ShellV2(line), minOf(spec.idleTimeoutMs, spec.timeoutMs))   // issue #616
        try {
            bounded(spec.timeoutMs, "exec-total") {
                val reader = AdbStreamReader(stream)
                val err = CappedBuffer(spec.stderrCap)
                var pending = java.io.ByteArrayOutputStream()
                var pendingTruncated = false
                var total = 0L
                suspend fun flushLine() {
                    val bytes = pending.toByteArray()
                    pending = java.io.ByteArrayOutputStream()
                    pendingTruncated = false
                    out.emit(String(bytes, Charsets.UTF_8))
                }
                while (true) {
                    when (val p = bounded(spec.idleTimeoutMs, "exec-idle") { ShellV2.next(reader) }) {
                        null -> throw if (conn.isClosed) AdbException(AdbError.Transport.Closed) else protocolError("the shell ended without an exit code")
                        is ShellPacket.Stderr -> err.append(p.bytes)
                        is ShellPacket.Stdout -> {
                            total += p.bytes.size
                            if (total > spec.stdoutCap) throw protocolError("output exceeds the cap of ${spec.stdoutCap} bytes")
                            var start = 0
                            for (i in p.bytes.indices) {
                                if (p.bytes[i] == '\n'.code.toByte()) {
                                    appendCapped(pending, p.bytes, start, i - start, pendingTruncated).let { pendingTruncated = it }
                                    flushLine()
                                    start = i + 1
                                }
                            }
                            if (start < p.bytes.size) pendingTruncated = appendCapped(pending, p.bytes, start, p.bytes.size - start, pendingTruncated)
                        }
                        is ShellPacket.Exit -> {
                            if (pending.size() > 0) flushLine()
                            if (p.code != 0) throw AdbException(AdbError.NonZeroExit(p.code, err.text().take(512)))
                            return@bounded
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException()
            }
        } finally {
            stream.close()
        }
    }

    private fun appendCapped(buf: java.io.ByteArrayOutputStream, bytes: ByteArray, off: Int, len: Int, truncated: Boolean): Boolean {
        val room = SessionConfig.LINE_CAP_BYTES - buf.size()
        if (len <= room) { buf.write(bytes, off, len); return truncated }
        if (room > 0) buf.write(bytes, off, room)
        return true
    }

    private fun blobFile(blob: BlobRef, expectedSize: Long = blob.size): File {
        val f = File(blob.path)
        if (!f.isFile) throw protocolError("blob is missing on the phone")
        if (f.length() != expectedSize || blob.size != expectedSize) throw protocolError("blob size does not match")
        return f
    }

    private class Hasher {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun readChunk(input: InputStream, buf: ByteArray): ByteArray? = withContext(blobIo) {
        val n = input.read(buf)
        if (n <= 0) null else buf.copyOf(n)
    }

    // sync SEND with the local hash checked while streaming and the remote hash read back (D39).
    override suspend fun push(src: BlobRef, dest: RemotePath, onProgress: (Long) -> Unit): PushResult {
        if (src.size > SessionConfig.MAX_PUSH_BYTES) throw protocolError("push larger than 200 MiB")
        val file = blobFile(src)
        return call(RetryClass.NEVER) { conn ->
            val start = clock.monotonicMs()
            val stream = conn.openBounded(AdbService.Sync, SessionConfig.PUSH_IDLE_MS)   // issue #616
            val sync = SyncClient(stream, SessionConfig.PUSH_IDLE_MS, "push")
            val hasher = Hasher()
            try {
                bounded(SessionConfig.PUSH_TOTAL_MS, "push") {
                    file.inputStream().use { input ->
                        val buf = ByteArray(SessionConfig.FILE_CHUNK_BYTES)
                        try {
                            sync.send(dest.absolute, SessionConfig.PUSH_MODE, (clock.nowMs() / 1000).toInt(),
                                chunks = { readChunk(input, buf)?.also { hasher.digest.update(it) } },
                                onProgress = { onProgress(it) })
                        } catch (e: SyncFailedException) {
                            throw AdbException(AdbError.NonZeroExit(1, e.reason.take(512)))
                        }
                    }
                }
                sync.quit()
            } finally {
                stream.close()
            }
            if (hasher.hex() != src.sha256.hex) throw protocolError("the blob on the phone does not match its hash")
            val remote = remoteSha256(conn, dest)
            if (remote != null && remote != src.sha256) throw protocolError("the pushed file does not match its hash")
            PushResult(src.size, clock.monotonicMs() - start, remote)
        }
    }

    private suspend fun remoteSha256(conn: AdbConnection, path: RemotePath): Sha256? {
        val r = Exec.run(conn, SessionLines.sha256sum(path), ExecSpec(timeoutMs = SessionConfig.HASH_EXEC_TIMEOUT_MS), clock)
        if (r.exit != 0) return null
        val hex = r.stdout.trim().take(64)
        return if (Regex("^[0-9a-f]{64}$").matches(hex)) Sha256(hex) else null
    }

    override suspend fun pull(src: RemotePath, maxBytes: Long): ByteArray = call(RetryClass.IDEMPOTENT_READ) { conn ->
        val stream = conn.openBounded(AdbService.Sync, SessionConfig.PULL_IDLE_MS)   // issue #616
        val sync = SyncClient(stream, SessionConfig.PULL_IDLE_MS, "pull")
        try {
            bounded(SessionConfig.PULL_TOTAL_MS, "pull") {
                try {
                    sync.recv(src.absolute, maxBytes)
                } catch (e: SyncFailedException) {
                    throw AdbException(AdbError.NonZeroExit(1, e.reason.take(512)))
                }
            }.also { sync.quit() }
        } finally {
            stream.close()
        }
    }

    // exec:cmd package install -S size (D39 ladder step 1); the APK streams from the cache file.
    override suspend fun installStream(apk: BlobRef, size: Long, onProgress: (Long) -> Unit): InstallOutcome {
        val file = blobFile(apk, size)
        return call(RetryClass.NEVER) { conn ->
            val stream = conn.openBounded(AdbService.ExecInstall(size), SessionConfig.INSTALL_IDLE_MS)   // issue #616
            val hasher = Hasher()
            val total = SessionConfig.INSTALL_BASE_MS + size * 1000 / SessionConfig.INSTALL_BYTES_PER_SECOND
            try {
                bounded(total, "install") {
                    file.inputStream().use { input ->
                        val buf = ByteArray(SessionConfig.FILE_CHUNK_BYTES)
                        var sent = 0L
                        while (true) {
                            val chunk = readChunk(input, buf) ?: break
                            hasher.digest.update(chunk)
                            bounded(SessionConfig.INSTALL_IDLE_MS, "install") { stream.write(chunk) }
                            sent += chunk.size
                            onProgress(sent)
                        }
                        if (sent != size) throw protocolError("blob size does not match")
                    }
                    if (hasher.hex() != apk.sha256.hex) throw protocolError("the APK on the phone does not match its hash")
                    val reader = AdbStreamReader(stream)
                    val out = CappedBuffer(SessionConfig.INSTALL_OUTPUT_CAP)
                    while (true) {
                        val chunk = bounded(SessionConfig.INSTALL_IDLE_MS, "install") { reader.readChunk(4096) } ?: break
                        out.append(chunk)
                    }
                    parseInstallOutput(out.text())
                }
            } finally {
                stream.close()
            }
        }
    }

    private fun parseInstallOutput(text: String): InstallOutcome {
        val t = text.trim()
        if (t.startsWith("Success")) return InstallOutcome.Installed
        val m = INSTALL_FAILURE.find(t) ?: return InstallOutcome.Failed("UNKNOWN", t.take(200))
        return InstallOutcome.Failed(m.groupValues[1], m.groupValues[2].trim().take(200))
    }

    override suspend fun reboot() {
        mutex.withLock {
            val conn = ensureConnectedLocked()
            lastBootId = try {
                Exec.run(conn, SessionLines.BOOT_ID, ExecSpec(timeoutMs = SessionConfig.IDENTITY_TIMEOUT_MS, idleTimeoutMs = SessionConfig.IDENTITY_IDLE_MS), clock)
                    .stdout.trim().takeIf { it.isNotEmpty() }
            } catch (e: AdbException) {
                null
            }
            val now = clock.nowMs()
            setState(ConnectionState.WaitingForBoot(now, now + SessionConfig.BOOT_DEFAULT_TIMEOUT_MS))
            try {
                Exec.run(conn, SessionLines.REBOOT, ExecSpec(timeoutMs = SessionConfig.REBOOT_EXEC_TIMEOUT_MS, idleTimeoutMs = SessionConfig.REBOOT_EXEC_TIMEOUT_MS), clock)
            } catch (e: AdbException) {
                // The socket dying is the expected outcome of a reboot.
                if (e.error !is AdbError.Transport.Closed && e.error !is AdbError.Transport.Timeout) throw e
            }
            connection = null
            conn.close()
        }
    }

    // Reconnect, then sys.boot_completed, pm path android, and a boot id different from the one
    // read before the reboot (ARCHITECTURE 3.10). Relative to the call (R0: 16).
    override suspend fun awaitBoot(timeoutMs: Long): BootOutcome = mutex.withLock {
        val deadline = clock.monotonicMs() + timeoutMs
        setState(ConnectionState.WaitingForBoot(clock.nowMs(), clock.nowMs() + timeoutMs))
        delay(minOf(SessionConfig.BOOT_INITIAL_WAIT_MS, timeoutMs))
        var wait = SessionConfig.BOOT_POLL_MIN_MS
        while (clock.monotonicMs() < deadline) {
            try {
                val conn = connection?.takeIf { !it.isClosed } ?: connectOnce().also { connection = it; watch(it) }
                val spec = ExecSpec(timeoutMs = SessionConfig.IDENTITY_TIMEOUT_MS, idleTimeoutMs = SessionConfig.IDENTITY_IDLE_MS)
                val props = Identity.parseProps(Exec.run(conn, SessionLines.GETPROP, spec, clock).stdout)
                val pmReady = Exec.run(conn, SessionLines.PM_PATH_ANDROID, spec, clock).exit == 0
                val bootId = Exec.run(conn, SessionLines.BOOT_ID, spec, clock).stdout.trim()
                if (props["sys.boot_completed"] == "1" && pmReady && bootId.isNotEmpty() && bootId != lastBootId) {
                    lastBootId = bootId
                    setState(ConnectionState.Connected(endpoint, conn.features, conn.maxPayload))
                    return@withLock BootOutcome.Back(bootId)
                }
            } catch (e: AdbException) {
                if (e.error is AdbError.Policy.WrongTv) { setState(ConnectionState.Failed(e.error)); throw e }
                connection?.let { scope.launch { it.close() } }
                connection = null
            }
            val remaining = deadline - clock.monotonicMs()
            if (remaining <= 0) break
            delay(minOf(wait, remaining))
            wait = minOf(wait * 2, SessionConfig.BOOT_POLL_MAX_MS)
        }
        setState(ConnectionState.Failed(AdbError.Transport.Timeout("boot")))
        BootOutcome.Timeout
    }

    override fun hold(): SessionHold {
        if (holds.incrementAndGet() == 1) {
            idleJob?.cancel(); idleJob = null
            startPing()
        }
        return object : SessionHold {
            private val released = AtomicBoolean(false)
            override fun close() {
                if (!released.compareAndSet(false, true)) return
                if (holds.decrementAndGet() == 0) {
                    pingJob?.cancel(); pingJob = null
                    startIdleTimer()
                }
            }
        }
    }

    private fun startIdleTimer() {
        if (closedForGood.get()) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(SessionConfig.IDLE_CLOSE_MS)
            if (holds.get() > 0 || closedForGood.get()) return@launch
            mutex.withLock {
                if (holds.get() > 0) return@withLock
                val c = connection ?: return@withLock
                connection = null
                setState(ConnectionState.Idle)
                c.close()
            }
        }
    }

    // An exec:echo every 20 s while held and idle; a miss drops the connection and reconnects.
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(SessionConfig.PING_INTERVAL_MS)
                if (!mutex.tryLock()) continue
                try {
                    if (mutableState.value is ConnectionState.WaitingForBoot) continue
                    val c = connection
                    if (c == null || c.isClosed) {
                        runCatching { ensureConnectedLocked() }
                        continue
                    }
                    try {
                        bounded(SessionConfig.PING_TIMEOUT_MS, "ping") {
                            val s = c.openStream(AdbService.Ping)
                            try { while (s.read() != null) { } } finally { s.close() }
                        }
                    } catch (e: AdbException) {
                        connection = null
                        setState(ConnectionState.Reconnecting(e.error, 0, clock.nowMs()))
                        c.close()
                        runCatching { ensureConnectedLocked() }
                    }
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    override suspend fun close() {
        if (!closedForGood.compareAndSet(false, true)) return
        idleJob?.cancel()
        pingJob?.cancel()
        adoptable?.close()
        connection?.close()
        connection = null
        setState(ConnectionState.Idle)
        onClosed(this)
    }

    companion object {
        private val INSTALL_FAILURE = Regex("Failure \\[([A-Z_0-9]+)(?::\\s*([^\\]]*))?\\]")
    }
}
