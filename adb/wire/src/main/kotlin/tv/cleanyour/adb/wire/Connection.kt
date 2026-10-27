/*
 * Copyright (c) 2021 mobile.dev inc.
 * Copyright (c) 2026 the Clean Your TV authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Derived from dadb 2.0.0 (AdbConnection.kt, AdbStream.kt, MessageQueue.kt); see NOTICE.
 * Changes: coroutines instead of threads and locks; the handshake signs only a 20-byte token
 * through the AdbSigner seam and reports the Allow dialog; one bounded inbox per stream (a peer
 * that ignores flow control is cut off); the STLS seam; typed AdbError failures.
 */

package tv.cleanyour.adb.wire

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.adb.wire.AdbProtocol.A_AUTH
import tv.cleanyour.adb.wire.AdbProtocol.A_CLSE
import tv.cleanyour.adb.wire.AdbProtocol.A_CNXN
import tv.cleanyour.adb.wire.AdbProtocol.A_OKAY
import tv.cleanyour.adb.wire.AdbProtocol.A_OPEN
import tv.cleanyour.adb.wire.AdbProtocol.A_STLS
import tv.cleanyour.adb.wire.AdbProtocol.A_SYNC
import tv.cleanyour.adb.wire.AdbProtocol.A_WRTE
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// The connection behind the frozen AdbConnection: handshake, message pump, stream table.
internal class ConnectionCore private constructor(
    private val socket: AdbSocket,
    val features: Set<String>,
    val maxPayload: Int,
    private val scope: CoroutineScope,
) {
    internal val streams = ConcurrentHashMap<Int, StreamImpl>()
    internal val pendingOpens = ConcurrentHashMap<Int, StreamImpl>()
    private val writeMutex = Mutex()
    private val nextLocalId = AtomicInteger(0)
    private val closeHandlers = CopyOnWriteArrayList<(AdbError) -> Unit>()
    private val closedError = AtomicReference<AdbError?>(null)
    private lateinit var pump: Job

    val isClosed: Boolean get() = closedError.get() != null
    val closeReason: AdbError? get() = closedError.get()

    fun invokeOnClose(handler: (AdbError) -> Unit) {
        closeHandlers += handler
        closedError.get()?.let { runCatching { handler(it) } }
    }

    private fun start() {
        pump = scope.launch { pump() }
    }

    private suspend fun pump() {
        var error: AdbError = AdbError.Transport.Closed
        try {
            while (true) {
                val m = FrameCodec.readMessage(socket) ?: break
                dispatch(m)
            }
        } catch (e: AdbException) {
            error = e.error
        } catch (e: CancellationException) {
            error = closedError.get() ?: AdbError.Transport.Closed
        } catch (e: Exception) {
            error = if (closedError.get() != null) closedError.get()!! else AdbError.Transport.Closed
        } finally {
            fail(error)
        }
    }

    private fun dispatch(m: AdbMessage) {
        when (m.command) {
            A_OKAY -> {
                val opening = pendingOpens.remove(m.arg1)
                if (opening != null) {
                    opening.remoteId = m.arg0
                    streams[m.arg1] = opening
                    opening.opened.complete(Unit)
                } else {
                    streams[m.arg1]?.onOkay()
                }
            }
            A_WRTE -> {
                val s = streams[m.arg1] ?: return
                if (s.remoteId != m.arg0) return
                val r = s.inbox.trySend(m.payload)
                if (!r.isSuccess && !r.isClosed) throw protocolError("the TV ignores flow control on stream ${m.arg1}")
            }
            A_CLSE -> {
                pendingOpens.remove(m.arg1)?.opened?.completeExceptionally(OpenRefused())
                streams.remove(m.arg1)?.onRemoteClose()
            }
            // We accept no streams opened by the TV (reverse forwards); AOSP answers CLSE.
            A_OPEN -> scope.launch { sendQuietly(A_CLSE, 0, m.arg0) }
            A_CNXN, A_AUTH, A_STLS, A_SYNC -> throw protocolError("unexpected ${AdbProtocol.name(m.command)} after connect")
            else -> throw protocolError("unknown command")
        }
    }

    suspend fun openStream(service: AdbService): AdbStream {
        closedError.get()?.let { throw AdbException(it) }
        val destination = serviceString(service)
        val stream = StreamImpl(this, nextLocalId.incrementAndGet())
        pendingOpens[stream.localId] = stream
        try {
            val payload = destination.toByteArray(Charsets.UTF_8)
            send(A_OPEN, stream.localId, 0, payload.copyOf(payload.size + 1))
            stream.opened.await()
            return stream
        } catch (e: OpenRefused) {
            pendingOpens.remove(stream.localId)
            throw protocolError("the TV refused to open ${describe(service)}")
        } catch (t: Throwable) {
            pendingOpens.remove(stream.localId)
            stream.close()
            throw t
        }
    }

    private fun serviceString(service: AdbService): String = when (service) {
        is AdbService.ShellV2 -> {
            if ("shell_v2" !in features) throw AdbException(AdbError.Transport.NoShellV2)
            "shell,v2,raw:" + service.line.text
        }
        AdbService.Sync -> "sync:"
        is AdbService.ExecInstall -> {
            if ("cmd" !in features) throw protocolError("the TV has no cmd service for a streamed install")
            if (service.size !in 1..MAX_INSTALL_BYTES) throw protocolError("install size out of range")
            "exec:cmd package install -S ${service.size}"
        }
        AdbService.Ping -> "exec:echo"
    }

    private fun describe(service: AdbService): String = when (service) {
        is AdbService.ShellV2 -> "a shell"
        AdbService.Sync -> "sync"
        is AdbService.ExecInstall -> "a streamed install"
        AdbService.Ping -> "the keepalive"
    }

    // Every frame goes out whole: the write itself is not cancellable, the wait for the mutex is.
    suspend fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = FrameCodec.EMPTY, off: Int = 0, len: Int = payload.size) {
        closedError.get()?.let { throw AdbException(it) }
        val frame = FrameCodec.encode(command, arg0, arg1, payload, off, len)
        writeMutex.withLock {
            try {
                withContext(NonCancellable) {
                    socket.write(frame, 0, frame.size)
                    socket.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(AdbError.Transport.Closed)
                throw AdbException(AdbError.Transport.Closed)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun sendQuietly(command: Int, arg0: Int, arg1: Int) {
        try { send(command, arg0, arg1) } catch (e: AdbException) { }
    }

    // Fire and forget CLSE for a stream the consumer closed; nothing to do once the connection is gone.
    fun closeRemote(localId: Int, remoteId: Int) {
        if (isClosed) return
        scope.launch { sendQuietly(A_CLSE, localId, remoteId) }
    }

    fun streamGone(): AdbException =
        closedError.get()?.let { AdbException(it) } ?: protocolError("the TV closed the stream")

    // Idempotent: closes the socket, fails every stream and pending open, runs the close handlers.
    fun fail(error: AdbError) {
        if (!closedError.compareAndSet(null, error)) return
        runCatching { socket.close() }
        for (s in streams.values) s.onFail(error)
        streams.clear()
        for (s in pendingOpens.values) s.onFail(error)
        pendingOpens.clear()
        for (h in closeHandlers) runCatching { h(error) }
        scope.cancel()
    }

    suspend fun close() {
        fail(AdbError.Transport.Closed)
    }

    private class OpenRefused : Exception()

    companion object {
        const val HANDSHAKE_STEP_TIMEOUT_MS = 5_000L   // ARCHITECTURE 3.3: CNXN and AUTH with a known key
        const val INBOX_CAPACITY = 4                    // WRTE frames queued per stream before the peer is cut off
        const val MAX_INSTALL_BYTES = 200L * 1024 * 1024   // SECURITY 3.5 size cap for APKs
        private val EOF = AdbMessage(0, 0, 0, FrameCodec.EMPTY)

        suspend fun open(
            socket: AdbSocket,
            signer: AdbSigner,
            tls: TlsUpgrader?,
            onAwaitingAllow: suspend (String) -> Unit,
            firstAuthTimeoutMs: Long,
        ): ConnectionCore {
            var sock = socket
            try {
                val (upgraded, banner) = handshake(sock, signer, tls, onAwaitingAllow, firstAuthTimeoutMs)
                sock = upgraded
                val parentContext = currentCoroutineContext()
                val scope = CoroutineScope(parentContext.minusKey(Job) + SupervisorJob())
                val core = ConnectionCore(sock, banner.features, banner.maxPayload, scope)
                core.start()
                return core
            } catch (t: Throwable) {
                runCatching { sock.close() }
                throw t
            }
        }

        private class Banner(val features: Set<String>, val maxPayload: Int)

        private suspend fun writeFrame(socket: AdbSocket, frame: ByteArray) {
            try {
                socket.write(frame, 0, frame.size)
                socket.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AdbException(AdbError.Transport.Closed)
            }
        }

        private suspend fun readStep(socket: AdbSocket, timeoutMs: Long, awaitingAllow: Boolean): AdbMessage {
            val m = try {
                withTimeoutOrNull(timeoutMs) { FrameCodec.readMessage(socket) ?: EOF }
            } catch (e: AdbException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AdbException(AdbError.Transport.Closed)
            }
            if (m == null) throw AdbException(if (awaitingAllow) AdbError.Auth.PendingTooLong else AdbError.Transport.Timeout("auth"))
            if (m === EOF) throw AdbException(AdbError.Transport.Closed)
            return m
        }

        // Issues #325 and #620: the TV's port answered, then every connect failed "unreachable" with no
        // reason. Only the phone's key can fail that way in the handshake: a Keystore error from the
        // signer is not an AdbException, so it left the session untyped and the flows read it as an
        // unreachable TV. It is a typed failure now (SIGNER_FAILED) that keeps the signer's exception
        // as its cause, so the session can log the platform class (ConnectFailure).
        private suspend fun <T> keyStep(block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            // Out of memory or stack: not the key's fault, and nothing to name.
            throw e
        } catch (e: AdbException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a Java API the phone lacks throws a LinkageError here
            // (BigInteger.TWO on Android 9 to 12 was NoSuchFieldError), and it is named the same way.
            throw signerFailed(e)
        }

        // CNXN, then AUTH: sign the first token; a second token means the key is unknown, so send
        // the public key once and wait for the Allow tap; a third token is a denial.
        private suspend fun handshake(
            socket0: AdbSocket,
            signer: AdbSigner,
            tls: TlsUpgrader?,
            onAwaitingAllow: suspend (String) -> Unit,
            firstAuthTimeoutMs: Long,
        ): Pair<AdbSocket, Banner> {
            var socket = socket0
            writeFrame(socket, FrameCodec.encode(A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, AdbProtocol.CONNECT_PAYLOAD))
            var signatureSent = false
            var publicKeySent = false
            while (true) {
                val m = readStep(socket, if (publicKeySent) firstAuthTimeoutMs else HANDSHAKE_STEP_TIMEOUT_MS, publicKeySent)
                when (m.command) {
                    A_STLS -> {
                        if (tls == null) throw protocolError("the TV requires TLS (STLS), which 0.1 does not support")
                        writeFrame(socket, FrameCodec.encode(A_STLS, AdbProtocol.STLS_VERSION, 0))
                        socket = tls.upgrade(socket, null)
                    }
                    A_AUTH -> {
                        if (m.arg0 != AdbProtocol.AUTH_TOKEN) throw protocolError("unexpected AUTH type ${m.arg0}")
                        if (m.payload.size != AdbProtocol.TOKEN_BYTES) throw protocolError("AUTH token of ${m.payload.size} bytes")
                        when {
                            !signatureSent -> {
                                val signature = keyStep { signer.sign(AuthToken(m.payload.copyOf())) }
                                if (signature.isEmpty() || signature.size > AdbProtocol.MAX_CONTROL_PAYLOAD) throw protocolError("signer returned ${signature.size} bytes")
                                writeFrame(socket, FrameCodec.encode(A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signature))
                                signatureSent = true
                            }
                            !publicKeySent -> {
                                val line = keyStep { signer.publicKeyLine }.toByteArray(Charsets.US_ASCII)
                                writeFrame(socket, FrameCodec.encode(A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, line.copyOf(line.size + 1)))
                                publicKeySent = true
                                onAwaitingAllow(keyStep { signer.fingerprint })
                            }
                            else -> throw AdbException(AdbError.Auth.Denied)
                        }
                    }
                    A_CNXN -> return socket to parseBanner(m)
                    else -> throw protocolError("unexpected ${AdbProtocol.name(m.command)} during the handshake")
                }
            }
        }

        // "device::ro.product.name=x;ro.product.model=y;ro.product.device=z;features=a,b,c"
        private fun parseBanner(m: AdbMessage): Banner {
            val text = String(m.payload, Charsets.US_ASCII).trimEnd('\u0000')
            val state = text.substringBefore("::", missingDelimiterValue = "")
            if (state != "device") throw protocolError("the TV answered as '${state.take(16)}', not as a device")
            val features = text.substringAfter("::").split(';')
                .mapNotNull { kv -> kv.indexOf('=').let { i -> if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1) } }
                .toMap()["features"]
                ?.split(',')?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
            val peerMax = m.arg1
            if (peerMax < AdbProtocol.MIN_PEER_PAYLOAD) throw protocolError("the TV advertises a payload window of $peerMax")
            return Banner(features, minOf(peerMax, AdbProtocol.MAX_PAYLOAD))
        }
    }
}

// One ADB stream: a bounded inbox of WRTE payloads, OKAY sent when the consumer takes a frame
// (so adbd never has more than INBOX_CAPACITY frames in flight), one WRTE outstanding at a time.
internal class StreamImpl(private val core: ConnectionCore, val localId: Int) : AdbStream {
    @Volatile var remoteId: Int = 0
    val opened = CompletableDeferred<Unit>()
    val inbox = Channel<ByteArray>(ConnectionCore.INBOX_CAPACITY)
    private val okays = Channel<Unit>(1)
    private val closed = AtomicBoolean(false)
    @Volatile private var remoteClosed = false

    fun onOkay() { okays.trySend(Unit) }

    fun onRemoteClose() {
        remoteClosed = true
        inbox.close()
        okays.close()
    }

    fun onFail(error: AdbError) {
        remoteClosed = true
        val cause = AdbException(error)
        inbox.close(cause)
        okays.close(cause)
        opened.completeExceptionally(cause)
    }

    override suspend fun read(): ByteArray? {
        val r = inbox.receiveCatching()
        if (r.isClosed) {
            r.exceptionOrNull()?.let { throw it }
            return null
        }
        val payload = r.getOrThrow()
        if (!remoteClosed && !closed.get()) core.sendQuietly(A_OKAY, localId, remoteId)
        return payload
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed.get()) throw protocolError("write on a closed stream")
        if (bytes.isEmpty()) return
        var off = 0
        while (off < bytes.size) {
            if (remoteClosed) throw core.streamGone()
            val len = minOf(core.maxPayload, bytes.size - off)
            core.send(A_WRTE, localId, remoteId, bytes, off, len)
            val ok = okays.receiveCatching()
            if (ok.isClosed) throw ok.exceptionOrNull() ?: core.streamGone()
            off += len
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        core.streams.remove(localId)
        core.pendingOpens.remove(localId)
        inbox.close()
        okays.close()
        val remote = remoteId
        if (remote != 0 && !remoteClosed) core.closeRemote(localId, remote)
    }
}
