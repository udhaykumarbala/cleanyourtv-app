package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.resolveFor

// 0.3.2 B1 and B2: the report preview. "Help add this TV" (TV) is the TV report for a TV with no exact
// profile; "Report a problem" (PROBLEM) is the redacted diagnostics log with a short TV summary. Both
// are made from what this phone stored, so the TV need not be on. The screen shows the exact text,
// then copies it and opens the GitHub form, or hands it to the share sheet. Nothing is sent by the
// app: the clipboard, the browser and the share sheet are the person's.
enum class ReportKind(val key: String) {
    TV("tv"), PROBLEM("problem");

    companion object { fun of(key: String): ReportKind? = entries.firstOrNull { it.key == key } }
}

data class ReportState(
    val kind: ReportKind,
    val loading: Boolean = true,
    // What the preview shows and the GitHub form gets (in the link, or from the clipboard).
    val text: String = "",
    val url: String = "",
    // False when there is nothing to report from (TV: this phone has no snapshot of the TV).
    val available: Boolean = true,
    // 0.3.3 D1: the report rides in the link, so the form opens with it filled in. False: too long
    // for the link, the person pastes it, and the screen says so before GitHub opens.
    val inLink: Boolean = false,
    // What Share sends: the TV report with one package per line (TvReport.text); the problem report
    // as shown. Empty means the shown text.
    val shareText: String = "",
)

sealed interface ReportIntent {
    data object CopyAndOpen : ReportIntent
    data object Share : ReportIntent
}

class ReportMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    kind: ReportKind,
    private val tvId: TvId?,
) : Machine<ReportState, ReportIntent, ManageEffect>(ReportState(kind), scope) {

    private data class Loaded(val text: String, val url: String, val available: Boolean, val inLink: Boolean = false, val share: String = "")

    init { scope.launch { send(build(kind)) } }

    override fun reduce(state: ReportState, event: Any): ReportState = when (event) {
        is Loaded -> state.copy(loading = false, text = event.text, url = event.url, available = event.available, inLink = event.inLink, shareText = event.share)
        else -> state
    }

    override fun handle(event: Any, before: ReportState, after: ReportState) {
        if (after.loading || !after.available) return
        when (event) {
            // 0.3.3 D1: copied also when the report is in the link, in case the form opens without it
            // (a sign-in on the way can drop it). The screen says which way it goes.
            ReportIntent.CopyAndOpen -> { effect(ManageEffect.Copy(after.text)); effect(ManageEffect.OpenUrl(after.url)) }
            ReportIntent.Share -> effect(ManageEffect.Share(after.shareText.ifEmpty { after.text }))
            else -> {}
        }
    }

    private suspend fun build(kind: ReportKind): Loaded {
        // Settings asks without a TV: the TV used last, when there is one.
        val tv = tvId?.let { d.tv(it) } ?: if (kind == ReportKind.PROBLEM && tvId == null) d.tvs.tvs.value.maxByOrNull { it.lastSeenAt } else null
        val input = tv?.let { t ->
            attempt { d.snapshots.latest(t.id) }.getOrNull()?.let { snap ->
                TvReportInput(
                    about = d.about, snapshot = snap,
                    profile = attempt { d.profiles.resolveFor(snap) }.getOrNull(),
                    home = attempt { d.homeDecisions.load(t.id) }.getOrNull(),
                    switchedOffByApp = attempt { d.ledger.switchedOffByUs(t.id).toSet() }.getOrDefault(emptySet()),
                )
            }
        }
        return when (kind) {
            ReportKind.TV -> if (input == null) Loaded("", "", available = false) else {
                val compact = TvReport.compact(input)
                val link = IssueLinks.tvSupport(input, compact)
                Loaded(compact, link.url, true, link.reportInLink, share = TvReport.text(input))
            }
            ReportKind.PROBLEM -> {
                val log = attempt { d.diagnostics.redactedText() }.getOrDefault("")
                val text = TvReport.problem(d.about, input, tail(log))
                val link = IssueLinks.bugReport(d.about, input, text)
                Loaded(text, link.url, true, link.reportInLink, share = text)
            }
        }
    }

    companion object {
        // The log can hold a few megabytes (five 1 MB files); a GitHub issue takes 65,536 characters
        // and the clipboard and the share sheet about a megabyte. The report keeps the log's first line
        // (the app and the phone) and its newest lines, at most MAX_LOG_LINES and MAX_LOG_CHARS.
        const val MAX_LOG_LINES = 300
        const val MAX_LOG_CHARS = 40_000

        fun tail(log: String): String {
            val lines = log.trimEnd().lines()
            if (lines.size <= 1) return log.trimEnd()
            val head = lines.first()
            val kept = ArrayDeque<String>()
            var chars = head.length
            for (line in lines.drop(1).asReversed()) {
                if (kept.size >= MAX_LOG_LINES || chars + line.length + 1 > MAX_LOG_CHARS) break
                kept.addFirst(line)
                chars += line.length + 1
            }
            val dropped = lines.size - 1 - kept.size
            return (listOf(head) + (if (dropped > 0) listOf("($dropped older lines left out)") else emptyList()) + kept).joinToString("\n")
        }
    }
}
