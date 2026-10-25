package tv.cleanyour.core.probe

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Recording
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Remote buttons: the next key press, as ProbeScripts.NEXT_KEY's grep prints it. The lines are what a
// Chromecast with Google TV HD (Android 14) printed for its remote: YouTube is KEY_UNKNOWN (0xf0), the same
// code as Netflix, and Down is 0x6c. 0x1a9 is KEY_PRESENTATION, a Xiaomi MiTV remote's YouTube button.
class RemoteKeysTest {
    private val prober = AdbProber(ProbeFixtures.clock())

    @Test fun parsesTheFirstKeyDown() {
        assertEquals(PressedKey(0x1a9), PressedKeyParser.parse(" 0001 01a9 00000001\n"))
        assertEquals(PressedKey(108), PressedKeyParser.parse(" 0001 006c 00000001\r\n"))
        assertNull(PressedKeyParser.parse(""))
        assertNull(PressedKeyParser.parse("\n"))
        // Only the grep's own match counts: a release, another event type, or anything else is no key.
        assertNull(PressedKeyParser.parse(" 0001 01a9 00000000"))
        assertNull(PressedKeyParser.parse(" 0004 0004 000c0077"))
        assertNull(PressedKeyParser.parse("/system/bin/sh: getevent: not found"))
    }

    @Test fun sharedAndNeededButtonsAreToldApart() {
        assertEquals(PressedKey.Verdict.MAPPABLE, PressedKey(425).verdict)
        assertEquals(PressedKey.Verdict.MAPPABLE, PressedKey(150).verdict)   // KEY_WWW, the Xiaomi TV button
        assertEquals(PressedKey.Verdict.SHARED, PressedKey(240).verdict)
        for (code in listOf(103, 108, 105, 106, 352, 28, 158, 172, 102, 139, 116, 114, 115, 113)) {
            assertEquals(PressedKey.Verdict.NEEDED, PressedKey(code).verdict, "code $code")
        }
    }

    @Test fun waitsLongerThanTheButtonAndNeverRetries() = runBlocking<Unit> {
        val session = FakeTvSession(recording = Recording().on(ProbeScripts.NEXT_KEY.text, " 0001 01a9 00000001\n"))
        assertEquals(PressedKey(425), prober.nextKey(session))
        val spec = session.calls.filterIsInstance<FakeTvSession.Call.Exec>().single().spec
        assertTrue(spec.idleTimeoutMs > ProbeScripts.KEY_WAIT_S * 1000L && spec.timeoutMs > spec.idleTimeoutMs, spec.toString())
        assertEquals(RetryClass.NEVER, spec.retry)
        // Nothing pressed in time: timeout ends getevent, grep finds nothing and exits 1.
        assertNull(prober.nextKey(FakeTvSession(recording = Recording().on(ProbeScripts.NEXT_KEY.text, "", exit = 1))))
    }
}
