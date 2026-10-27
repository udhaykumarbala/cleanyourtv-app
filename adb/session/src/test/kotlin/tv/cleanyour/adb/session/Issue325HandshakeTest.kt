package tv.cleanyour.adb.session

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.Timeout
import tv.cleanyour.adb.wire.AdbSigner
import tv.cleanyour.adb.wire.AuthToken
import tv.cleanyour.adb.wire.KeyBacking
import tv.cleanyour.adb.wire.SIGNER_FAILED
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.testing.fakeadbd.FakeAdbd
import tv.cleanyour.testing.fakeadbd.FakeAdbdSocketFactory
import tv.cleanyour.testing.fakeadbd.Personas
import java.security.ProviderException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issues #325 (STR-D2 G4K, Google TV 14) and #620 (Casper 55UGA610, Android TV 11): ADB from
// another device worked, mDNS saw the TV, yet every connect from the app ended within seconds in
//
//   I setup: connect start (new TV, android_tv_remote+google_cast+adb_mdns)
//   I setup: connect failed unreachable
//
// with no "session: socket connect failed" line, so the socket had connected. The manager caught
// only AdbException after the connect; a failure of the phone's key (a Keystore error from the
// signer) or any other exception left open() untyped, and Setup turned it into "unreachable" with
// no reason for Copy error. Now it is a typed failure, and the log and Copy error say why.
class Issue325HandshakeTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    // The key works until `failing` is set; then it fails the way a Keystore does, with a message
    // that must never reach the log.
    private class FlakyKey(var failing: Boolean, private val error: () -> Exception) : AdbSigner {
        override val publicKeyLine: String get() = Keys.signer.publicKeyLine
        override val fingerprint: String get() = Keys.signer.fingerprint
        override val backing: KeyBacking get() = KeyBacking.TEE
        override suspend fun sign(token: AuthToken): ByteArray = if (failing) throw error() else Keys.signer.sign(token)
        override suspend fun reset() {}
    }

    private class AppOwn : RuntimeException("app class, renamed by R8 in a release")

    private val keystore = { ProviderException("Keystore operation failed for 192.168.1.23:5555") }

    @Test fun issue325TheKeyThatCannotSignIsNamedNotUnreachable() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), FlakyKey(true, keystore), null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val r = assertIs<OpenResult.Failed>(manager.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), null) {})
        assertEquals(AdbError.Transport.Protocol(SIGNER_FAILED), r.error)
        assertTrue(r.error != AdbError.Transport.Unreachable)
        assertEquals(listOf("session: the phone's key could not sign: ProviderException"), lines)
        assertEquals("the phone's key could not sign: ProviderException", manager.lastConnectFailure)
        assertTrue(lines.none { "192." in it || "5555" in it || "Keystore" in it }, lines.toString())
        // The TV never got as far as the Allow dialog, as the report says.
        assertEquals(0, adbd.prompts.get())
        settle()
        assertTrue(adbd.connections.isEmpty(), "the socket is closed")
    }

    @Test fun anAppExceptionInTheKeyIsOtherAndAnyOtherExceptionIsTypedToo() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), FlakyKey(true) { AppOwn() }, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val endpoint = tv.cleanyour.core.model.Endpoint.PlainTcp(HOST)
        assertEquals(AdbError.Transport.Protocol(SIGNER_FAILED), assertIs<OpenResult.Failed>(manager.open(endpoint, null) {}).error)
        assertEquals("the phone's key could not sign: other", manager.lastConnectFailure)

        // Not the key: the Allow callback throws (a new TV, so the dialog step runs). Typed as well.
        val fresh = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val other = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to fresh)), Keys.signer, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val e = assertIs<OpenResult.Failed>(other.open(endpoint, null) { throw IllegalStateException("boom") }).error
        assertEquals(AdbError.Transport.Protocol(HandshakeFailure.UNEXPECTED), e)
        assertEquals("handshake failed: IllegalStateException", other.lastConnectFailure)
        assertEquals("session: handshake failed: IllegalStateException", lines.last())
    }

    // The Android 9 to 12 connect failure (#98, #325 and nine more): the TV asks for the public key
    // and building its line throws NoSuchFieldError, an Error (BigInteger.TWO, API 33). The token is
    // signed, the TV never shows Allow, and it must still be named, never "unreachable".
    private object KeyLineLinkageError : AdbSigner {
        override val publicKeyLine: String get() = throw NoSuchFieldError("TWO")
        override val fingerprint: String get() = Keys.signer.fingerprint
        override val backing: KeyBacking get() = KeyBacking.TEE
        override suspend fun sign(token: AuthToken): ByteArray = Keys.signer.sign(token)
        override suspend fun reset() {}
    }

    @Test fun aLinkageErrorWhileSendingThePublicKeyIsNamedToo() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), KeyLineLinkageError, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val r = assertIs<OpenResult.Failed>(manager.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), null) {})
        assertEquals(AdbError.Transport.Protocol(SIGNER_FAILED), r.error)
        assertEquals("the phone's key could not sign: NoSuchFieldError", manager.lastConnectFailure)
        assertEquals(listOf("session: the phone's key could not sign: NoSuchFieldError"), lines)
        assertEquals(0, adbd.prompts.get())
        settle()
        assertTrue(adbd.connections.isEmpty(), "the socket is closed")
    }

    // An Error that is not the key's, here from the Allow callback, is typed too.
    @Test fun anErrorOutsideTheKeyIsTypedAsUnexpected() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), Keys.signer, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val e = assertIs<OpenResult.Failed>(manager.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), null) { throw NoSuchMethodError("x") }).error
        assertEquals(AdbError.Transport.Protocol(HandshakeFailure.UNEXPECTED), e)
        assertEquals("session: handshake failed: NoSuchMethodError", lines.last())
    }

    // Out of memory is not a handshake failure to name: it goes up as it came, and the socket closes.
    @Test fun aVirtualMachineErrorIsNotTyped() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), FlakyKey(true) { RuntimeException() }.let { k ->
            object : AdbSigner by k { override suspend fun sign(token: AuthToken): ByteArray = throw OutOfMemoryError("test") }
        }, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        assertFailsWith<OutOfMemoryError> { manager.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), null) {} }
        assertEquals(emptyList(), lines)
        settle()
        assertTrue(adbd.connections.isEmpty(), "the socket is closed")
    }

    // The TV answers as it should (here: it refuses the shell): no handshake line, the kind says it.
    @Test fun aPlainAnswerOfTheTvAddsNoLine() = runTest {
        val rig = Rig(this, faults = tv.cleanyour.testing.fakeadbd.Faults().apply { refuseOpens = true })
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(rig.factory, Keys.signer, null, rig.clock, backgroundScope, EmptyCoroutineContext, log = { lines += it })
        assertIs<AdbError.Transport.Protocol>(assertIs<OpenResult.Failed>(manager.open(rig.endpoint, null) {}).error)
        assertEquals(emptyList(), lines)
        assertEquals(null, manager.lastConnectFailure)
    }

    // The same failure on a reconnect reaches the caller as an AdbException (TvSession's contract,
    // R0: 7), never as the Keystore's own exception.
    @Test fun aReconnectWhoseKeyFailsThrowsATypedError() = runTest {
        val adbd = FakeAdbd(Personas.miTv(), scope = backgroundScope).also { it.trust(Keys.signer) }
        val key = FlakyKey(false, keystore)
        val lines = mutableListOf<String>()
        val manager = AdbSessionManager(FakeAdbdSocketFactory(mapOf(HOST to adbd)), key, null, TestClock(this), backgroundScope, EmptyCoroutineContext, log = { lines += it })
        val s = assertIs<OpenResult.Ok>(manager.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), null) {}).session
        key.failing = true
        adbd.closeConnections()
        settle()
        val e = assertFailsWith<AdbException> { s.exec(line("echo x")) }
        assertIs<AdbError.Transport>(e.error)
        assertIs<ConnectionState.Failed>(s.state.value)
        assertTrue(lines.isNotEmpty() && lines.all { it == "session: the phone's key could not sign: ProviderException" }, lines.toString())
    }
}
