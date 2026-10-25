package tv.cleanyour.core.probe

import tv.cleanyour.core.session.TvSession

// Not part of the frozen Prober (section 5.5), like LaunchableApps: the next button pressed on the TV's
// remote, for Remote buttons on the Home screen page. One read-only call (ProbeScripts.NEXT_KEY) that waits
// up to KEY_WAIT_S seconds; null when nothing was pressed in time. Fails with AdbException like the Prober.
fun interface RemoteKeyCapture {
    suspend fun nextKey(session: TvSession): PressedKey?
}

// A button by its Linux key code, the scancode the launcher's key services match on (app_keys
// "scan:<code>:<package>", launcher 2.6 and later). The Android keycode is set by the remote's key layout
// file, which the shell cannot read on most TVs, so the scancode is the only name a button has from here.
data class PressedKey(val scancode: Int) {
    val verdict: Verdict get() = when {
        scancode in SHARED -> Verdict.SHARED
        scancode in NEEDED -> Verdict.NEEDED
        else -> Verdict.MAPPABLE
    }

    enum class Verdict {
        MAPPABLE,
        // Every button the remote's driver does not name arrives with this code (KEY_UNKNOWN on the Chromecast
        // remote's YouTube and Netflix buttons), so a mapping would take all of them at once.
        SHARED,
        // A button the TV or the home screen needs: arrows, OK, Back, Home, Menu, power, volume.
        NEEDED,
    }

    companion object {
        val SHARED: Set<Int> = setOf(0, 240)   // KEY_RESERVED, KEY_UNKNOWN
        val NEEDED: Set<Int> = setOf(
            1, 28, 96, 102, 103, 105, 106, 108, 113, 114, 115, 116, 139, 158, 172, 352, 353,
            // KEY_ESC, KEY_ENTER, KEY_KPENTER, KEY_HOME, the four arrows, KEY_MUTE, KEY_VOLUMEDOWN, KEY_VOLUMEUP,
            // KEY_POWER, KEY_MENU, KEY_BACK, KEY_HOMEPAGE, KEY_OK, KEY_SELECT
        )
    }
}

object PressedKeyParser {
    // ProbeScripts.NEXT_KEY's one match: " 0001 <code in hex> 00000001". Anything else is no key.
    private val LINE = Regex("^ ?0001 ([0-9a-f]{4}) 00000001$")

    fun parse(stdout: String): PressedKey? {
        val t = stdout.lineSequence().map { it.removeSuffix("\r") }.firstOrNull { it.isNotBlank() } ?: return null
        val hex = LINE.find(t)?.groupValues?.get(1) ?: return null
        return PressedKey(hex.toInt(16))
    }
}
