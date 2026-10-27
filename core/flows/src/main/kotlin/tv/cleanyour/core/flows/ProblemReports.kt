package tv.cleanyour.core.flows

import java.net.URLDecoder
import java.net.URLEncoder
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.resolveFor

// 0.4.6 (REPORTING-PROPOSAL 2.2): what the card said where it went wrong, so "Report a problem" on
// that card opens the GitHub form with it filled in and the person types nothing. Every value is a
// fixed word or the card's own text; the report cleans and redacts each one again (TvReport.problem),
// so a TV's name or an address in a card title never reaches GitHub.
//
// `screen`: where the card was, a fixed English word ("Setup", "Connect", "Manage", "Home screen").
// `card`: the card's title as the person saw it, the TV's own name taken out (named()).
// `case`: the card's fixed name for the maintainers (a StopCase, a HomeBlock, CONNECT_FAILED).
// `step`: the run's step as the log names it (RunLines), added by ProblemReports.build.
// `connect`: the card stopped a connect, so there is no TV to read; the report then has the phone's
// side: how the TV was found (`found`), the `port`, the named error (`error`, LogCodes) and why the
// socket failed (`detail`, the session's class and errno reason, never an address).
data class ReportContext(
    val screen: String,
    val card: String,
    val case: String,
    val step: String? = null,
    val connect: Boolean = false,
    val error: String? = null,
    val found: String? = null,
    val port: Int? = null,
    val detail: String? = null,
) {
    // Key=value lines for the route (IssueRoute); a value never holds a newline.
    fun encode(): String = listOfNotNull(
        "screen" to screen, "card" to card, "case" to case, step?.let { "step" to it }, "connect" to connect.toString(),
        error?.let { "error" to it }, found?.let { "found" to it }, port?.let { "port" to it.toString() }, detail?.let { "detail" to it },
    ).joinToString("\n") { (k, v) -> "$k=${v.replace('\n', ' ').replace('\r', ' ')}" }

    companion object {
        fun decode(text: String): ReportContext? {
            val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return ReportContext(
                screen = m["screen"] ?: return null, card = m["card"].orEmpty(), case = m["case"] ?: return null, step = m["step"],
                connect = m["connect"] == "true", error = m["error"], found = m["found"], port = m["port"]?.toIntOrNull(), detail = m["detail"],
            )
        }

        // A card's title with the TV's own names taken out: a nickname is the person's words. Whole
        // words only, so a short name ("Mi") leaves "Mirror" alone; a name that is just "TV" says nothing.
        fun named(title: String, vararg names: String?): String =
            names.filterNotNull().map { it.trim() }.filter { it.length >= 2 && !it.equals("TV", ignoreCase = true) && !it.equals("the TV", ignoreCase = true) }
                .distinct().sortedByDescending { it.length }
                .fold(title) { t, n -> t.replace(Regex("(?<!\\w)" + Regex.escape(n) + "(?!\\w)"), "the TV") }
    }
}

// The route a card's "Report a problem" sends ("issue/<tv id or ->/<context>"). The app opens the
// GitHub form from it at once (AppRoot); applyRoute never sees it. The context is URL encoded, so a
// slash in a card title cannot split the route.
object IssueRoute {
    const val PREFIX = "issue/"

    fun of(tvId: TvId?, context: ReportContext): String =
        "$PREFIX${tvId?.value ?: "-"}/${URLEncoder.encode(context.encode(), "UTF-8")}"

    fun parse(route: String): Pair<TvId?, ReportContext>? {
        if (!route.startsWith(PREFIX)) return null
        val parts = route.removePrefix(PREFIX).split('/', limit = 2)
        if (parts.size != 2) return null
        val ctx = runCatching { ReportContext.decode(URLDecoder.decode(parts[1], "UTF-8")) }.getOrNull() ?: return null
        return parts[0].takeIf { it != "-" && it.isNotBlank() }?.let(::TvId) to ctx
    }
}

// The run's state in the words of the log's run lines (0.3.2 B3, issue #367): the setup wizard logs
// them, and a report names the step it stopped at the same way.
object RunLines {
    fun label(r: RunState): String = when (r) {
        is RunState.Running -> "running"
        is RunState.AwaitingConfirm -> "awaiting confirm"
        is RunState.RollingBack -> "rolling back"
        // Issue #367's log said "run cd2": the class name, renamed by R8 in a release build.
        is RunState.InDoubt -> "in doubt"
        is RunState.Paused -> "paused ${r.reason} at step ${r.at}${r.error?.let { " (${LogCodes.of(it)})" } ?: ""}"
        // 0.3.3 (issue #10): the skipped switch-offs by name, so a report shows what stayed on.
        is RunState.Finished -> "finished ${r.status}${r.home?.let { ", home $it" } ?: ""}, ${r.skipped.size} skipped" +
            r.skipped.filter { it.op is Op.Disable }.take(SetupMachine.SKIPS_LOGGED).joinToString(", ") { LogCodes.op(it.op) }.let { if (it.isEmpty()) "" else ": $it" }
        else -> r::class.simpleName ?: "state"
    }

    // 0.3.2 B3: "paused STEP_FAILED at step 72: undo step failed: Enable com.amazon.tv.launcher:
    // nonzero_exit(1) <stderr head>". The failed op comes from the run's journal; its stderr is one
    // cleaned line (the app's redactor runs over the whole line too).
    suspend fun pause(p: RunState.Paused, ledger: RunLedger): String {
        val head = "paused ${p.reason} at step ${p.at}"
        val failed = (if (p.reason == tv.cleanyour.core.engine.PauseReason.STEP_FAILED) attempt { ledger.lastFailure(p.plan.id, p.at) }.getOrNull() else null)
            ?: return label(p)
        val code = p.error?.let { LogCodes.of(it) } ?: failed.exit?.let { "nonzero_exit($it)" } ?: "failed"
        val stderr = LogCodes.clean(failed.stderrHead, 160).takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        return "$head: ${if (failed.undo) "undo step failed" else "step failed"}: ${LogCodes.op(failed.op)}: $code$stderr"
    }
}

// 0.4.6 (REPORTING-PROPOSAL 2.1, 11 problem issues arrived with an empty report box): the problem
// report always rides in the GitHub link, so there is never a paste step. The whole report goes in
// when the link fits; when it does not, the report in the link is made shorter in this order, and
// the person's phone keeps the whole one (the preview's copy and Share, and the copy made when the
// form opens):
// 1. the log keeps its first line and its newest lines, down to MIN_LOG_LINES;
// 2. the names of what this app switched off become their count;
// 3. the log keeps fewer lines, down to none.
// The header, the TV, its matching, its Home switch and Home screen lines and what happened on the
// card are never cut. Real reports of 10,500 to 26,400 characters (issues #1006, #1130, #1136) keep
// 12 to 32 log lines in a link under IssueLinks.MAX_URL, 3,860 to 3,970 characters (ProblemReportsTest).
object ProblemReports {
    const val MIN_LOG_LINES = 10

    // `full`: the report as the preview shows it and Share and the copy send. `sent`: what the
    // link holds (the same when it fits). `logLines`: the log lines after its first line; `logKept`:
    // how many of the newest the link holds.
    data class Fitted(val full: String, val sent: String, val link: IssueLinks.Link, val logLines: Int, val logKept: Int, val namesKept: Boolean) {
        val shortened: Boolean get() = sent != full
    }

    fun fit(about: AppAbout, input: TvReportInput?, log: String, context: ReportContext? = null, redact: (String) -> String = { it }): Fitted {
        val full = TvReport.problem(about, input, ReportMachine.tail(log), context, redact = redact)
        val whole = IssueLinks.bugReport(about, input, full, context, redact)
        val lines = log.trimEnd().lines()
        val head = lines.first()
        val rest = lines.drop(1)
        // As many of the newest lines as tail() keeps: never more than the full report has.
        val most = ReportMachine.tail(log).lines().let { t -> if (t.size <= 1) 0 else t.size - 1 - (if (t.getOrNull(1)?.let(::isGap) == true) 1 else 0) }
        if (whole.reportInLink) return Fitted(full, full, whole, rest.size, most, true)

        fun text(kept: Int, names: Boolean): String {
            val dropped = rest.size - kept
            val shown = listOf(head) + (if (dropped > 0) listOf("($dropped older lines left out to fit the link; the app keeps them)") else emptyList()) + rest.takeLast(kept)
            return TvReport.problem(about, input, shown.joinToString("\n"), context, names, redact)
        }
        fun link(kept: Int, names: Boolean) = IssueLinks.bugReport(about, input, text(kept, names), context, redact)
        // The largest number of newest lines in lo..hi that fits, or null when even lo does not.
        fun largest(lo: Int, hi: Int, names: Boolean): Int? {
            if (lo > hi || !link(lo, names).reportInLink) return null
            var a = lo
            var b = hi
            while (a < b) {
                val m = (a + b + 1) / 2
                if (link(m, names).reportInLink) a = m else b = m - 1
            }
            return a
        }
        // Nothing switched off: no names to count, so the log alone gives way.
        val floor = if (input?.switchedOffByApp.isNullOrEmpty()) 0 else minOf(MIN_LOG_LINES, most)
        largest(floor, most, names = true)?.let { k -> return Fitted(full, text(k, true), link(k, true), rest.size, k, true) }
        largest(floor, most, names = false)?.let { k -> return Fitted(full, text(k, false), link(k, false), rest.size, k, false) }
        largest(0, floor, names = false)?.let { k -> return Fitted(full, text(k, false), link(k, false), rest.size, k, false) }
        // Never reached with the app's own fields (each short field is cut to MAX_VALUE): the form
        // then opens without the report, as before 0.4.6.
        return Fitted(full, full, whole, rest.size, 0, true)
    }

    private fun isGap(line: String) = line.startsWith("(") && line.contains("older lines left out")

    // What a report is built from for `tvId`: its latest snapshot, the profile, the home switch
    // decision and what this app switched off; null when this phone has not read the TV.
    suspend fun input(d: ManageDeps, tvId: TvId?): TvReportInput? {
        val tv = tvId?.let { d.tv(it) } ?: return null
        val snap = attempt { d.snapshots.latest(tv.id) }.getOrNull() ?: return null
        return TvReportInput(
            about = d.about, snapshot = snap,
            profile = attempt { d.profiles.resolveFor(snap) }.getOrNull(),
            home = attempt { d.homeDecisions.load(tv.id) }.getOrNull(),
            switchedOffByApp = attempt { d.ledger.switchedOffByUs(tv.id).toSet() }.getOrDefault(emptySet()),
        )
    }

    // The problem report for "Report a problem": from Settings (no context: the TV used last), or
    // from a card (its context, and the TV the card was about, if any). A run that is still paused
    // or has just finished on that TV adds its step, as the log line names it.
    suspend fun build(d: ManageDeps, tvId: TvId?, context: ReportContext?): Fitted {
        val id = tvId ?: if (context == null) d.tvs.tvs.value.maxByOrNull { it.lastSeenAt }?.id else null
        val input = input(d, id)
        val log = attempt { d.diagnostics.redactedText() }.getOrDefault("")
        val step = if (context == null || context.connect || id == null) null else when (val r = attempt { d.engine.runState(id).value }.getOrNull()) {
            is RunState.Paused -> attempt { RunLines.pause(r, d.ledger) }.getOrNull()
            is RunState.Finished -> RunLines.label(r)
            else -> null
        }
        return fit(d.about, input, log, context?.let { c -> if (step != null) c.copy(step = step) else c }, d.redact)
    }
}
