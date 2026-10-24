package tv.cleanyour.core.flows

import tv.cleanyour.core.model.PackageName

// Remote buttons (Home screen): a button on the TV's remote that opens an app, mapped by pressing it. The app
// learns the button's Linux key code (RemoteKeyCapture) and pushes it to the launcher as an app_keys entry
// "scan:<code>:<package>", which launcher 2.6 (versionCode 7) and later read in HomeKeyService. The keycode
// Android gives the button is set by the remote's key layout file, which the shell cannot read, so the
// scancode is the only name the app can give a button.
data class RemoteButton(val scancode: Int, val app: AppChoice)

// What happened to the last press the Home screen page asked for.
sealed interface ButtonCapture {
    data object Waiting : ButtonCapture                       // listening on the TV for up to 20 seconds
    data class Pressed(val scancode: Int) : ButtonCapture     // a button that can open an app: choose which
    data object Shared : ButtonCapture                        // the remote sends this button with a code other buttons share
    data object Needed : ButtonCapture                        // arrows, OK, Back, Home, Menu, power or volume
    data object NothingPressed : ButtonCapture
    data object Failed : ButtonCapture                        // the TV did not answer
}

object RemoteButtons {
    // Launcher 2.6 added "scan:" entries (upstream "Remote keys: map app buttons by scancode").
    const val FIRST_WITH_SCANCODES = 7L
    private val DIGITS = Regex("[0-9]{1,6}")

    fun entry(text: String): Pair<Int, PackageName>? {
        val parts = text.split(':')
        if (parts.size != 3 || parts[0] != "scan" || !DIGITS.matches(parts[1])) return null
        val code = parts[1].toInt().takeIf { it > 0 } ?: return null
        val pkg = runCatching { PackageName(parts[2]) }.getOrNull() ?: return null
        return code to pkg
    }

    fun text(scancode: Int, pkg: PackageName): String = "scan:$scancode:${pkg.value}"

    // The saved list with one button set to an app (a button maps to one app; the last one chosen wins).
    fun mapped(keys: List<String>, scancode: Int, pkg: PackageName): List<String> = unmapped(keys, scancode) + text(scancode, pkg)

    fun unmapped(keys: List<String>, scancode: Int): List<String> = keys.filter { entry(it)?.first != scancode }
}
