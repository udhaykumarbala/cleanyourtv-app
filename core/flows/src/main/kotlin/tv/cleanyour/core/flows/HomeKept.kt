package tv.cleanyour.core.flows

import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.store.DocumentStore

// 0.4.7 (UX-FINDINGS 1 to 11, PM 2): the TV keeps its own home screen. One state, read by every
// Setup screen after the decision (What we found, Customise, Working, Done), so no screen offers a
// dock, a look, a card or "the new home screen" on a path where none comes. Setup decides it once
// per plan (SetupMachine.homeBlock, the TV's facts, Fire OS, a kept stock home screen); a switch
// that was tried and did not hold is the other way in.
sealed interface HomeKept {
    // Setup planned no switch: why (HomeBlock), and what names the TV in the reason. `unlisted`: no
    // exact list knows this TV (or it tried a test list), so a list is what would change it.
    // 0.5.0: `change` and `ownName`, under HOME_CHANGED, what the report shows of the TV's own home
    // screen and its name in the TV's Settings (HomeChange), for the advice.
    data class Blocked(val block: HomeBlock, val fireTv: Boolean = false, val brand: String = "", val unlisted: Boolean = false,
                       val change: HomeChange? = null, val ownName: String? = null) : HomeKept

    // The switch was in the plan and the TV's own home screen is back: the check ran out of time,
    // the person said no, or the new one did not take over.
    data class SwitchFailed(val outcome: HomeOutcome) : HomeKept

    companion object {
        // From the wizard's extras: a switch that failed is this run's news and comes first; else
        // the block Setup decided; null while the new home screen is planned or already on.
        // RFC-HELPER-1 (test build): with the Home button helper chosen the plan is a redirect, not a
        // kept home screen: the TV's own stays Home and the Home button opens the new one, so null.
        fun of(e: SetupExtras): HomeKept? = when {
            e.doneHomeKept != null -> SwitchFailed(e.doneHomeKept)
            e.helperOn -> null
            !e.homePossible -> Blocked(e.homeBlock, e.fireTv, e.brand, e.unknownModel || e.candidateTried, e.homeChange, e.homeChangeOwn)
            else -> null
        }

        // The blocks a list made for the TV would change (UNKNOWN_HOME, WIZARD_STAYS_ON, and
        // WIZARD_WIFI on an unlisted TV): "Help add this TV" is the next step there.
        fun needsList(block: HomeBlock, unlisted: Boolean): Boolean =
            block == HomeBlock.UNKNOWN_HOME || block == HomeBlock.WIZARD_STAYS_ON || (block == HomeBlock.WIZARD_WIFI && unlisted)
    }
}

// What a protected row on the Customise sheet is to the home screen switch, so the sheet can say
// it in the app's own words instead of the planner's reason strings (UX finding 3): the TV's own
// home screen (a stock launcher, the current Home), or its first-run setup wizard.
enum class HomeRole { OWN_HOME, WIZARD }

// 0.4.7 (must 4, PM 4): "Help add this TV" remembers that this TV's report was sent, when, and the
// issue it went to once the person pasted the link, so the next visit says so instead of asking
// for the same report again. Sending again stays allowed. tvs/<id>/report-sent.txt, two lines:
// "at=<ms>" and "issue=<url>" (the second only once known). Forget this TV removes it with the
// TV's folder.
data class SentReport(val atMs: Long, val issueUrl: String? = null)

class SentReports(private val store: DocumentStore) {
    private fun path(id: tv.cleanyour.core.model.TvId) = "tvs/${id.value}/report-sent.txt"

    suspend fun save(id: tv.cleanyour.core.model.TvId, r: SentReport) {
        val lines = listOfNotNull("at=${r.atMs}", r.issueUrl?.takeIf { it.isNotBlank() }?.let { "issue=${it.replace('\n', ' ').replace('\r', ' ').trim()}" })
        store.write(path(id), lines.joinToString("") { "$it\n" }.encodeToByteArray())
    }

    suspend fun load(id: tv.cleanyour.core.model.TvId): SentReport? = store.read(path(id)) { bytes ->
        val lines = bytes.decodeToString().lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()?.ifEmpty { null }
        value("at")?.toLongOrNull()?.let { SentReport(it, value("issue")) }
    }

    companion object {
        // A GitHub issue link of this app's repository, as the person pastes it: the issue's own
        // page, with or without a fragment or a trailing slash; nothing else is kept.
        private val ISSUE = Regex("^https://github\\.com/udhaykumarbala/cleanyourtv-app/issues/(\\d+)/?(#.*)?$")

        fun issueUrl(text: String): String? = ISSUE.matchEntire(text.trim())?.let { "https://github.com/udhaykumarbala/cleanyourtv-app/issues/${it.groupValues[1]}" }

        // "#1234" for the banner, from the saved link.
        fun issueNumber(url: String): String? = ISSUE.matchEntire(url)?.let { "#${it.groupValues[1]}" }
    }
}

// The packages the Customise sheet names as the home screen's own rows (HomeRole), from what the
// wizard knows of the TV: the setup wizard first, then the TV's own home screens.
internal fun homeRoles(wizard: PackageName?, stocks: Collection<PackageName>, claimants: Collection<PackageName>): Map<PackageName, HomeRole> {
    val roles = LinkedHashMap<PackageName, HomeRole>()
    for (p in stocks + claimants) if (p != Launcher.pkg && "setupwraith" !in p.value.lowercase()) roles[p] = HomeRole.OWN_HOME
    wizard?.let { roles[it] = HomeRole.WIZARD }
    for (p in claimants) if ("setupwraith" in p.value.lowercase()) roles[p] = HomeRole.WIZARD
    return roles
}
