// FROZEN INTERFACE (PRODUCT-SPEC section 5.3, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W2.

package tv.cleanyour.adb.wire

import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine

@JvmInline value class AuthToken(val bytes: ByteArray) { init { require(bytes.size == 20) } }   // R0: 4 the only thing a signer will sign
enum class KeyBacking { STRONGBOX, TEE, WRAPPED_SOFTWARE }
interface AdbSigner {
    val publicKeyLine: String             // adbd RSAPublicKey struct, base64, space, "cleanyourtv"
    val fingerprint: String               // MD5 colon hex, as the TV's Allow dialog prints it
    val backing: KeyBacking
    suspend fun sign(token: AuthToken): ByteArray   // R0: 4; PKCS1 v1.5 over DigestInfo(SHA1) || token; implementations re-check the size
    suspend fun reset() }
interface SocketFactory { suspend fun connect(endpoint: Endpoint, timeoutMs: Long): AdbSocket }
interface AdbSocket : AutoCloseable { suspend fun read(buf: ByteArray, off: Int, len: Int): Int; suspend fun write(buf: ByteArray, off: Int, len: Int); suspend fun flush() }
interface TlsUpgrader { suspend fun upgrade(socket: AdbSocket, pin: Sha256?): AdbSocket }  // 0.2; 0.1 ships a NotSupported implementation
// R0: 3 no raw service string crosses the wire boundary; the connection spells each one
sealed interface AdbService {
    data class ShellV2(val line: ShellLine) : AdbService       // "shell,v2,raw:" + line.text
    data object Sync : AdbService                              // "sync:"
    data class ExecInstall(val size: Long) : AdbService        // "exec:cmd package install --user 0 -S <size>" (RFC-INSTALL-1)
    data object Ping : AdbService }                            // "exec:echo", the keepalive

class AdbConnection private constructor(private val core: ConnectionCore) {   // sketch; W2 owns the shape inside :adb:wire
    // RFC-RECONNECT-1: `sendPublicKey` false stops the handshake with Auth.Denied where the TV does not take the
    // signature, before the public key goes (the key is what raises the TV's Allow dialog).
    companion object { suspend fun open(socket: AdbSocket, signer: AdbSigner, tls: TlsUpgrader?, onAwaitingAllow: suspend (String) -> Unit, firstAuthTimeoutMs: Long = 120_000,
        sendPublicKey: Boolean = true): AdbConnection = AdbConnection(ConnectionCore.open(socket, signer, tls, onAwaitingAllow, firstAuthTimeoutMs, sendPublicKey)) }
    val features: Set<String> get() = core.features
    val maxPayload: Int get() = core.maxPayload
    suspend fun openStream(service: AdbService): AdbStream = core.openStream(service)     // R0: 3
    suspend fun close(): Unit = core.close()
    // W2, additive (DECISIONS-W2.md): lets the session see the pump die without polling.
    val isClosed: Boolean get() = core.isClosed
    fun invokeOnClose(handler: (AdbError) -> Unit): Unit = core.invokeOnClose(handler) }
interface AdbStream : AutoCloseable { suspend fun read(): ByteArray?; suspend fun write(bytes: ByteArray) }
