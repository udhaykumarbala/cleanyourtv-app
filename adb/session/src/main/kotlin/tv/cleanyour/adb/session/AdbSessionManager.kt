package tv.cleanyour.adb.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.wire.AdbConnection
import tv.cleanyour.adb.wire.AdbSigner
import tv.cleanyour.adb.wire.SocketFactory
import tv.cleanyour.adb.wire.TlsUpgrader
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

// SessionManager over :adb:wire: one AdbTvSession per TvId, the identity read at every open,
// D37's expected check before anything else is sent, the Allow callback passed through (D38).
class AdbSessionManager(
    private val socketFactory: SocketFactory,
    private val signer: AdbSigner,
    private val tls: TlsUpgrader? = null,
    private val clock: Clock = SystemClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val blobIo: CoroutineContext = Dispatchers.IO,
    // 0.3.6: the redacting diagnostics log; it gets the exception class and errno reason of a failed
    // socket connect (ConnectFailure), never an address.
    private val log: (String) -> Unit = {},
) : SessionManager {
    private val sessions = ConcurrentHashMap<TvId, AdbTvSession>()
    private val mutableStates = MutableStateFlow<Map<TvId, ConnectionState>>(emptyMap())
    override val states: StateFlow<Map<TvId, ConnectionState>> = mutableStates.asStateFlow()
    private val openMutex = Mutex()

    // 0.3.6: why the last socket connect failed (ConnectFailure.detail), for Setup's "Copy error";
    // null once a socket connects.
    @Volatile var lastConnectFailure: String? = null
        private set

    private fun noteConnectFailure(e: Throwable) {
        val detail = ConnectFailure.detail(e)
        lastConnectFailure = detail
        log("session: socket connect failed: $detail")
    }

    // Issues #325, #620: the socket connected and the handshake or the identity read failed inside
    // the app (the phone's key, or an exception that is not an AdbException). Logged and kept for
    // "Copy error" like a failed socket connect; a plain answer of the TV is left to the flows' line.
    private fun noteHandshakeFailure(e: Throwable) {
        val detail = HandshakeFailure.detail(e) ?: return
        lastConnectFailure = detail
        log("session: $detail")
    }

    private fun publish(tvId: TvId, state: ConnectionState) {
        mutableStates.update { it + (tvId to state) }
    }

    private fun remove(session: AdbTvSession) {
        sessions.remove(session.tvId, session)
        mutableStates.update { it - session.tvId }
    }

    // Handshakes and the message pumps run on the manager's dispatcher, never on the caller's.
    private val dispatcher: CoroutineContext = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default

    override suspend fun open(endpoint: Endpoint, expected: TvId?, onAwaitingAllow: suspend (fingerprint: String) -> Unit): OpenResult =
        withContext(dispatcher) { openOnDispatcher(endpoint, expected, onAwaitingAllow) }

    private suspend fun openOnDispatcher(endpoint: Endpoint, expected: TvId?, onAwaitingAllow: suspend (fingerprint: String) -> Unit): OpenResult = openMutex.withLock {
        expected?.let { sessions[it] }?.takeIf { !it.isClosedForGood && it.state.value is ConnectionState.Connected }?.let { return@withLock OpenResult.Ok(it) }
        if (expected != null) publish(expected, ConnectionState.Connecting(endpoint, 1))
        val socket = try {
            socketFactory.connect(endpoint, SessionConfig.CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteConnectFailure(e)
            return@withLock failed(expected, classifyConnectFailure(e))
        }
        lastConnectFailure = null
        val conn = try {
            AdbConnection.open(
                socket, signer, tls,
                onAwaitingAllow = { fp ->
                    if (expected != null) publish(expected, ConnectionState.AwaitingAllow(clock.nowMs(), fp))
                    onAwaitingAllow(fp)
                },
                firstAuthTimeoutMs = SessionConfig.FIRST_AUTH_TIMEOUT_MS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            // Before, only an AdbException was caught here: anything else left open() and the flows
            // turned it into "unreachable" (issues #325, #620). The socket is closed by open() itself.
            // Throwable, so an Error such as NoSuchFieldError (a Java API the phone lacks) is named too.
            noteHandshakeFailure(e)
            return@withLock failed(expected, HandshakeFailure.typed(e).error)
        }
        try {
            if ("shell_v2" !in conn.features) { conn.close(); return@withLock failed(expected, AdbError.Transport.NoShellV2) }
            val identity = Identity.read(conn, clock)
            if (expected != null && identity.tvId != expected) {
                conn.close()
                return@withLock failed(expected, AdbError.Policy.WrongTv(expected, identity.tvId))
            }
            sessions[identity.tvId]?.takeIf { !it.isClosedForGood }?.let { existing ->
                existing.adopt(conn, endpoint)
                return@withLock OpenResult.Ok(existing)
            }
            val session = AdbTvSession(identity, endpoint, conn, Link(socketFactory, signer, tls, onAwaitingAllow, ::noteConnectFailure, ::noteHandshakeFailure), clock, scope, ::publish, ::remove, blobIo)
            sessions[identity.tvId] = session
            publish(identity.tvId, session.state.value)
            OpenResult.Ok(session)
        } catch (e: CancellationException) {
            conn.close()
            throw e
        } catch (e: VirtualMachineError) {
            conn.close()
            throw e
        } catch (e: Throwable) {
            // An identity read that fails with anything else closes the connection too (it was left
            // open before) and is typed for the flows (R0: 7).
            conn.close()
            noteHandshakeFailure(e)
            failed(expected, HandshakeFailure.typed(e).error)
        }
    }

    private fun failed(expected: TvId?, error: AdbError): OpenResult {
        if (expected != null) publish(expected, ConnectionState.Failed(error))
        return OpenResult.Failed(error)
    }

    override fun existing(tvId: TvId): TvSession? = sessions[tvId]?.takeIf { !it.isClosedForGood }

    override suspend fun closeAll() {
        for (s in sessions.values.toList()) s.close()
        sessions.clear()
        mutableStates.value = emptyMap()
    }
}
