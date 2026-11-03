package tv.cleanyour.core.flows

// 0.3.6 (issues #98, #270, #325, #468): the TV's port answered, but the connection failed. The door
// shows a card with the likely causes on the phone's side and "Copy error", which puts `summary` on
// the clipboard so the person can paste it into an issue. `kind` is the log's word (LogCodes).
// 0.4.8 (issues #2084, #2211, #2430, #2502, #2529, #2823, #1915, #2274, #2673): `debuggingOff` when the TV turned
// the connection away (refused, ECONNREFUSED): nothing listens on its debugging port, so debugging is off on the TV,
// most often because Finish switched it off as planned. The card says that and how to switch it on, not that the
// phone is in the way.
data class ConnectError(val kind: String, val summary: String, val debuggingOff: Boolean = false) {
    companion object {
        private val ERRNO = Regex("\\bE[A-Z0-9]{2,15} \\(")

        // `detail` is the session's socket reason ("ConnectException, ECONNREFUSED (Connection refused)"). A refusal
        // with another errno (a firewall's EPERM, say, which Android also reports as a ConnectException) is the
        // phone's side; one with no errno at all is the TV's.
        fun debuggingOff(kind: String, detail: String?): Boolean =
            kind == "refused" && (detail == null || "ECONNREFUSED" in detail || !ERRNO.containsMatchIn(detail))
    }
}

// The short text "Copy error" copies: the app and Android version, the error kind with the class
// and errno reason the session logged, and the last lines of the diagnostics log. The log is
// redacted already (the app's Redactor); this masks once more what could name a network: IPv4 and
// IPv6 addresses (the last octet the Redactor keeps too) and local host names.
object ConnectErrorSummary {
    const val LOG_LINES = 10
    private const val HEADER = "Clean Your TV "
    private const val OCTET = "(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)"
    private val IPV4 = Regex("(?<![A-Za-z0-9.])$OCTET\\.$OCTET\\.$OCTET\\.$OCTET(?![A-Za-z0-9]|\\.[A-Za-z0-9])")
    private val MASKED_IPV4 = Regex("x\\.x\\.x\\.\\d{1,3}")
    private val IPV6 = Regex("(?i)(?<![A-Za-z0-9:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![A-Za-z0-9:])")
    private val LOCAL_HOST = Regex("(?i)(?<![A-Za-z0-9.-])[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:localdomain|local|lan|home\\.arpa)(?![A-Za-z0-9-])")

    fun redact(line: String): String = line
        .replace(LOCAL_HOST, "[host]")
        .replace(MASKED_IPV4, "[address]")
        .replace(IPV4, "[address]")
        .replace(IPV6, "[address]")

    // The same, with the running app's `version` kept on the App line and the log's first line
    // (OwnVersion.keep): a four-part version such as 0.4.6.1 looks like an address.
    fun redact(line: String, version: String?): String = OwnVersion.keep(line, version) { redact(it) }

    fun build(about: AppAbout?, kind: String, detail: String?, log: String): String {
        val lines = log.lines().filter { it.isNotBlank() }.filterNot { it.startsWith(HEADER) }.takeLast(LOG_LINES)
        return buildList {
            add("Clean Your TV connection error")
            add("App: ${about?.appLine ?: "unknown"}")
            add("Phone: ${about?.phoneAndroid?.ifBlank { null } ?: "Android unknown"}")
            add("Error: $kind" + (detail?.let { " ($it)" } ?: ""))
            if (lines.isNotEmpty()) {
                add("Last log lines:")
                addAll(lines)
            }
        }.joinToString("\n") { redact(it, about?.version) }
    }
}
