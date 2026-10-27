package tv.cleanyour.core.ops

import tv.cleanyour.core.model.CLASS_RE
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PACKAGE_RE
import tv.cleanyour.core.model.RUN_TOKEN_RE
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RunToken

// The TV-side dead-man timer (PRODUCT-SPEC 3.4 step 1, D14, SECURITY 3.6). The one line that runs
// more than one command under a single exec, so it is a string constant with placeholders, filled
// by the renderer from four validated values and covered by a golden test. Spike S12 picks the
// variant on the Mi TV and a Google TV emulator; the renderer takes it as a constructor argument.
enum class WatchdogVariant { NOHUP_SETSID, SUBSHELL }

object Watchdog {
    const val PROCESS_NAME_PREFIX = "cleanyourtv-rollback-"
    const val TOKEN_FILE_PREFIX = "home-ok-"
    const val LOG_FILE_PREFIX = "watchdog-"
    const val LOG_FILE_SUFFIX = ".log"
    const val MIN_SECONDS = 30
    const val MAX_SECONDS = 300

    private const val DIR = "/data/local/tmp/cleanyourtv"

    // The body every variant runs: sleep, exit if the phone wrote the token file, else put the
    // stock launcher back and start HOME. Placeholders are upper case words that no grammar admits.
    // `sleep & wait`, not a foreground sleep: the TV's sh puts off SIGTERM until a foreground child
    // ends, so KillWatchdog's pkill left the watchdog running for the whole sleep (Mi TV, Android 9,
    // device test 2026-10-20); a sh in `wait` takes the signal at once. Only SIGHUP is ignored (nohup).
    const val BODY = "sleep SECONDS & wait; [ -f $DIR/${TOKEN_FILE_PREFIX}TOKEN ] && exit 0; " +
        "pm enable --user 0 STOCKPKG; cmd package set-home-activity --user 0 STOCKCOMP; " +
        "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"

    // Variant A (PRODUCT-SPEC 3.4): nohup and setsid detach the shell from the adb socket.
    const val TEMPLATE_NOHUP_SETSID =
        "nohup setsid sh -c '$BODY' ${PROCESS_NAME_PREFIX}TOKEN >$DIR/${LOG_FILE_PREFIX}TOKEN$LOG_FILE_SUFFIX 2>&1 &"

    // Variant B: the same body in a backgrounded subshell (double fork) instead of nohup setsid.
    const val TEMPLATE_SUBSHELL =
        "( sh -c '$BODY' ${PROCESS_NAME_PREFIX}TOKEN >$DIR/${LOG_FILE_PREFIX}TOKEN$LOG_FILE_SUFFIX 2>&1 & )"

    fun template(variant: WatchdogVariant): String = when (variant) {
        WatchdogVariant.NOHUP_SETSID -> TEMPLATE_NOHUP_SETSID
        WatchdogVariant.SUBSHELL -> TEMPLATE_SUBSHELL
    }

    init {
        check(RemoteDir.TMP.path == DIR) { "watchdog template dir differs from RemoteDir.TMP" }
    }

    // Fills the template. Every value is re-validated against its grammar here even though the
    // types did it already (belt and braces, SECURITY 3.1): none of them can contain a quote, a
    // space or a metacharacter, so the body needs no inner quoting.
    fun render(variant: WatchdogVariant, stock: Component, seconds: Int, token: RunToken): String {
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "watchdog seconds $seconds outside $MIN_SECONDS..$MAX_SECONDS" }
        require(PACKAGE_RE.matches(stock.pkg.value)) { "stock launcher package fails PACKAGE_RE" }
        require(CLASS_RE.matches(stock.cls)) { "stock launcher class fails CLASS_RE" }
        require(RUN_TOKEN_RE.matches(token.value)) { "run token fails RUN_TOKEN_RE" }
        val comp = stock.flat
        require(!comp.contains('\'') && !comp.contains(' ')) { "component contains a quote or a space" }
        val line = fill(template(variant), stock, seconds, token)
        check(ShellTokens.LINE_RE.matches(line)) { "watchdog line is not printable ASCII" }
        check(matches(variant, line, stock, seconds, token)) { "watchdog line does not match its template" }
        return line
    }

    // The inverse check used by the renderer's self-assertion and by tests: the line is exactly
    // the template with the four values and nothing else.
    fun matches(variant: WatchdogVariant, line: String, stock: Component, seconds: Int, token: RunToken): Boolean {
        val expected = fill(template(variant), stock, seconds, token)
        return line == expected && line.count { it == '\'' } == 2
    }

    private val PLACEHOLDER = Regex("SECONDS|STOCKPKG|STOCKCOMP|TOKEN")

    // One pass over the template: a value is never rescanned for placeholders, so a package name
    // that happens to contain the word TOKEN cannot be rewritten.
    private fun fill(template: String, stock: Component, seconds: Int, token: RunToken): String =
        PLACEHOLDER.replace(template) { m ->
            when (m.value) {
                "SECONDS" -> seconds.toString()
                "STOCKPKG" -> stock.pkg.value
                "STOCKCOMP" -> stock.flat
                else -> token.value
            }
        }

    fun processName(token: RunToken): String = PROCESS_NAME_PREFIX + token.value
    fun tokenFileName(token: RunToken): String = TOKEN_FILE_PREFIX + token.value
    // The file the template sends the watchdog's output to (empty unless something went wrong).
    fun logFileName(token: RunToken): String = LOG_FILE_PREFIX + token.value + LOG_FILE_SUFFIX

    // The pattern pgrep and pkill match against full command lines (-f). The first letter sits in a
    // bracket expression, so the pattern matches the watchdog's command line (which carries the
    // plain name) but never the pgrep or pkill process itself, nor the `sh -c` wrapper adbd runs
    // the line under: both carry the bracketed text, which the regex does not match. Without the
    // bracket, pkill kills its own parent shell and pgrep always finds a pid, so "armed" could
    // never fail and "killed" could never pass (R1).
    fun processPattern(token: RunToken): String =
        "[" + PROCESS_NAME_PREFIX.first() + "]" + PROCESS_NAME_PREFIX.drop(1) + token.value
}
