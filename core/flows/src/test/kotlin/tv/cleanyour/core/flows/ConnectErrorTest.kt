package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.3.6 (issues #98, #270, #325, #468): the TV's port answered but the connection failed. The door
// shows a card, and "Copy error" copies a short summary with no address in it.
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectErrorTest {
    private val about = AppAbout("0.3.6", "full", "Xiaomi 2201117TG", "Android 13")
    private val other = "192.0.2.20"   // a TV whose port answers but whose connection fails

    private fun log(n: Int) = "Clean Your TV 0.3.6, Android 33\n" + (1..n).joinToString("\n") { "17000000000$it I setup: line $it" }

    @Test fun theSummaryNamesTheAppThePhoneTheErrorAndTheLastLines() {
        val s = ConnectErrorSummary.build(about, "unreachable", "NoRouteToHostException, EHOSTUNREACH (No route to host)", log(14))
        val lines = s.lines()
        assertEquals("Clean Your TV connection error", lines[0])
        assertEquals("App: 0.3.6 (full)", lines[1])
        assertEquals("Phone: Android 13", lines[2])
        assertEquals("Error: unreachable (NoRouteToHostException, EHOSTUNREACH (No route to host))", lines[3])
        assertEquals("Last log lines:", lines[4])
        assertEquals((5..14).map { "17000000000$it I setup: line $it" }, lines.drop(5))
        assertFalse("Android 33" in s, "the diagnostics header is not a log line")
        // Without an app record or a detail it still reads.
        assertEquals("Error: refused", ConnectErrorSummary.build(null, "refused", null, "").lines()[3])
    }

    @Test fun addressesAndLocalHostNamesAreMasked() {
        val text = "1 I session: x.x.x.195 and 192.168.0.7 and fe80::1c2:3ff:fe4d:5e6f and [::1] via mitv-livingroom.local and box.lan\n" +
            "2 I setup: profile xiaomi-mitv-axso0, home com.google.android.apps.tv.launcherx/.home.HomeActivity, build UKG3.250107.001, app 0.3.6"
        val s = ConnectErrorSummary.build(about, "unreachable", null, text)
        for (leak in listOf("195", "192.168", "fe80", "::1", "mitv-livingroom", "box.lan")) assertFalse(leak in s, "$leak in $s")
        for (kept in listOf("xiaomi-mitv-axso0", "com.google.android.apps.tv.launcherx/.home.HomeActivity", "UKG3.250107.001", "app 0.3.6")) assertTrue(kept in s, "$kept in $s")
    }

    // Issues #325 and #620: the session let an exception of the phone's own out of open(), and the
    // door said "unreachable" with no reason. The machine names it now; the session types its own
    // (Issue325HandshakeTest in :adb:session), so this is the last defence.
    @Test fun issue325AnExceptionFromTheSessionIsNotUnreachable() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val throwing = object : tv.cleanyour.core.session.SessionManager by h.sessions {
            override suspend fun open(endpoint: tv.cleanyour.core.model.Endpoint, expected: tv.cleanyour.core.model.TvId?, onAwaitingAllow: suspend (fingerprint: String) -> Unit) =
                if (endpoint.host == other) throw java.security.ProviderException("Keystore operation failed") else h.sessions.open(endpoint, expected, onAwaitingAllow)
        }
        val m = SetupMachine(h.setupDeps(diagnostics = { log(3) }, about = about, sessions = throwing), backgroundScope, SetupStart.Fresh)
        toDoor(h, m)
        val e = assertNotNull(m.extras.value.connectError, "the door shows the connect card")
        assertEquals("protocol", e.kind)
        assertTrue("setup: connect threw unexpected" in h.logLines, h.logLines.toString())
        assertTrue("setup: connect failed protocol" in h.logLines, h.logLines.toString())
        assertTrue(h.logLines.none { "unreachable" in it || "Keystore" in it }, h.logLines.toString())
    }

    private fun TestScope.toDoor(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound.copy(host = other)))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[other] = true
        settle(2_100)
        settle(700)
    }

    @Test fun aFailedConnectShowsTheCardAndASuccessfulOneClearsIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = SetupMachine(h.setupDeps(diagnostics = { log(3) }, about = about, connectDetail = { "NoRouteToHostException, EHOSTUNREACH (No route to host)" }), backgroundScope, SetupStart.Fresh)
        toDoor(h, m)
        val e = assertNotNull(m.extras.value.connectError, "the door shows the connect card")
        assertEquals("unreachable", e.kind)
        assertTrue("Error: unreachable (NoRouteToHostException, EHOSTUNREACH (No route to host))" in e.summary, e.summary)
        assertTrue("App: 0.3.6 (full)" in e.summary && "Phone: Android 13" in e.summary, e.summary)
        assertTrue("setup: line 3" in e.summary)
        assertFalse(other in e.summary)
        assertTrue(m.state.value is SetupState.OpenDoor)
        // The TV lets the phone in on the next try: the card goes.
        h.sessions.register(other, h.session)
        settle(2_100)
        settle(700)
        assertNull(m.extras.value.connectError)
    }
}
