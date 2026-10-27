package tv.cleanyour.adb.session

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.Timeout
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.testing.fakeadbd.Faults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #616 (TCL Smart TV Pro G05, the C735, on a Wi-Fi that kept losing it). The report's log:
//
//   I setup: run running
//   I setup: run paused CONNECTION_LOST at step 0 (timeout)
//   I setup: run running                                   2.2 s later: Carry on
//   I setup: run paused USER_STOP at step 0                100 s later
//   ...
//   I session: socket connect failed: ConnectException, EHOSTUNREACH (No route to host)
//   I setup: run paused IN_DOUBT at step 0
//
// Two things kept the run on the dead socket. The OPEN of a stream waited for the TV's OKAY with
// no bound, so a step sent to a TV that had left the network without closing the socket hung with
// the session's lock held (this test hung so before the fix): the Working screen for minutes, Stop
// doing nothing, until the app was closed. And a call that did time out left its connection in
// place, the session still saying Connected, so the reconnect watcher said the TV was back at once
// and Carry on started on the same dead socket. Every open is bounded now, and a timeout is followed
// by one keepalive: a TV that answers keeps its connection (5.3), one that does not has it dropped
// and the state says so.
class Issue616SilentTvTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun issue616ATvThatLeftTheNetworkIsNotConnectedAfterTheTimeout() = runTest {
        val faults = Faults()
        val rig = Rig(this, faults = faults)
        val s = rig.open()
        val hold = s.hold()   // the run holds the session while it works
        // The TV drops off the Wi-Fi: it reads nothing more, not even the step's OPEN, the socket
        // stays open (no RST), and it takes no new connection either.
        faults.halfOpenAfterFrames = 0
        rig.adbd.acceptConnections = false
        val t = currentTime
        val e = assertFailsWith<AdbException> { s.exec(line("pm disable-user --user 0 com.tcl.esticker"), ExecSpec(timeoutMs = 1_000)) }
        // Before, this call never returned: the OPEN waited for an OKAY that never came.
        assertEquals(AdbError.Transport.Timeout("open"), e.error)
        // The step's own bound, then the one keepalive.
        assertEquals(1_000 + SessionConfig.PING_TIMEOUT_MS, currentTime - t)
        assertTrue(s.state.value !is ConnectionState.Connected, s.state.value.toString())
        // The run pauses and lets go; the background reconnect finds no TV and says so.
        hold.close()
        settle(30_000)
        assertTrue(s.state.value !is ConnectionState.Connected, s.state.value.toString())
        // The TV comes back: the next call opens a fresh socket and works.
        faults.halfOpenAfterFrames = -1
        rig.adbd.acceptConnections = true
        assertEquals("back\n", s.exec(line("echo back")).stdout)
        assertIs<ConnectionState.Connected>(s.state.value)
    }

    // A TV that is only slow answers the keepalive and keeps its connection (5.3).
    @Test fun aSlowTvKeepsItsConnectionAfterATimeout() = runTest {
        val rig = Rig(this)
        val s = rig.open()
        val e = assertFailsWith<AdbException> { s.exec(line("sleep 100"), ExecSpec(timeoutMs = 1_000)) }
        assertEquals(AdbError.Transport.Timeout("exec-total"), e.error)
        assertIs<ConnectionState.Connected>(s.state.value)
        assertEquals(1, rig.adbd.connections.size)
        assertEquals("still here\n", s.exec(line("echo still here")).stdout)
        assertEquals(1, rig.factory.attempts.size, "no new socket")
    }
}
