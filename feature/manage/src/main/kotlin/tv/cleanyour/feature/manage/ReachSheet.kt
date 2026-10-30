@file:OptIn(ExperimentalMaterial3Api::class)

package tv.cleanyour.feature.manage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.Reach
import tv.cleanyour.core.flows.ReachCheck
import tv.cleanyour.core.flows.Seen
import tv.cleanyour.core.flows.fingerprintTail
import tv.cleanyour.ui.design.component.AddressEntry
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.StepCard
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TvStatus
import tv.cleanyour.ui.design.component.TvStatusChip
import tv.cleanyour.ui.design.component.addressText
import tv.cleanyour.ui.design.theme.CytTheme

// RFC-REACH-1: "Copy connection details" as the app shows it: collecting (about 10 s), then copied.
data class DetailsUi(val busy: Boolean = false, val copied: Boolean = false)

// The fixed name of the debugging step's case, for "Report a problem" (ReportContext.case).
fun reachCase(reach: Reach): String = when (reach) {
    is Reach.NeedsDebugging -> "REACH_NEEDS_DEBUGGING"
    is Reach.AwaitingAllow -> "REACH_AWAITING_ALLOW"
    is Reach.WrongNetwork -> "REACH_NOT_ON_WIFI"
    is Reach.Connecting -> "REACH_CONNECTING"
    Reach.Idle -> "REACH_IDLE"
}

// M1a: the debugging step for a returning write; it advances on its own when the port opens
// (UX 4.3). The app lays it over whatever screen asked (TvAccess.reach). RFC-REACH-1: it says
// which case it is (not on Wi-Fi, the Allow prompt, debugging off), and "What the app sees" under
// "Show details" has the live check, "Copy connection details" and "Report a problem".
// `onReport` gets the title the person saw, for the prefilled GitHub form (ReportContext).
// 0.4.8: "Enter the TV's address" turns the sheet into Setup's address entry (S1c, AddressEntry); `onAddress` hands the
// typed text to the wait (TvAccess.tryAddress) and says whether it is an address on a home network. Back on the step,
// "What the app sees" has a row for it. Closing the sheet there goes back to the step, not out of the wait.
@Composable
fun DebugStepSheet(
    reach: Reach, onCancel: () -> Unit, check: ReachCheck = ReachCheck(), onReport: (String) -> Unit = {},
    details: DetailsUi = DetailsUi(), onDetails: (share: Boolean) -> Unit = {}, onAddress: ((String) -> Boolean)? = null,
) {
    if (reach == Reach.Idle) return
    var typing by rememberSaveable { mutableStateOf(false) }
    CytBottomSheet(if (typing) ({ typing = false }) else onCancel) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ReachSheetBody(reach, check, onCancel, onReport, details, onDetails, onAddress, typing) { typing = it }
        }
    }
}

// The sheet's content: the step, or its address entry while `typing`.
@Composable
internal fun ColumnScope.ReachSheetBody(
    reach: Reach, check: ReachCheck, onCancel: () -> Unit, onReport: (String) -> Unit, details: DetailsUi,
    onDetails: (share: Boolean) -> Unit, onAddress: ((String) -> Boolean)?, typing: Boolean, onTyping: (Boolean) -> Unit,
) {
    if (typing && onAddress != null) AddressStep(onAddress, onDone = { onTyping(false) })
    else DebugStepContent(reach, check, onCancel, onReport, details, onDetails, onAddress = onAddress?.let { { onTyping(true) } })
}

// The debugging step's address entry: Setup's words and field; Connect goes back to the step when the address is one
// on a home network, and says it is not one otherwise.
@Composable
internal fun ColumnScope.AddressStep(onAddress: (String) -> Boolean, onDone: () -> Unit, initial: String = "", initialError: Boolean = false) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf(initialError) }
    AddressEntry(text, { text = addressText(it) }, error, onConnect = { if (onAddress(text)) onDone() else error = true })
    TertiaryButton(stringResource(R.string.action_back), onDone, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), quiet = true)
}

private fun Reach.tvName(): String = when (this) {
    is Reach.NeedsDebugging -> tvName
    is Reach.Connecting -> tvName
    is Reach.AwaitingAllow -> tvName
    is Reach.WrongNetwork -> tvName
    Reach.Idle -> ""
}

@Composable
internal fun ColumnScope.DebugStepContent(
    reach: Reach, check: ReachCheck, onCancel: () -> Unit, onReport: (String) -> Unit = {},
    details: DetailsUi = DetailsUi(), onDetails: (share: Boolean) -> Unit = {}, initiallyOpen: Boolean = false,
    onAddress: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { TvStatusChip(reach.tvName(), TvStatus.WAITING) }
    val title: String
    when (reach) {
        is Reach.WrongNetwork -> {
            title = stringResource(R.string.debugstep_nowifi_title)
            StepCard(title, stringResource(R.string.debugstep_nowifi_body), Modifier.padding(top = 16.dp))
            LiveLine(stringResource(R.string.debugstep_waiting))
        }
        is Reach.AwaitingAllow -> {
            title = stringResource(R.string.debugstep_allow_title)
            StepCard(title, stringResource(R.string.debugstep_allow_body), Modifier.padding(top = 16.dp))
            val tail = fingerprintTail(reach.fingerprint)
            if (tail.isNotEmpty()) Body(msg(R.string.debugstep_allow_code, "tail" to tail), Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            LiveLine(stringResource(R.string.debugstep_allow_waiting))
        }
        else -> {
            title = stringResource(R.string.debugstep_title)
            StepCard(title, stringResource(R.string.debugstep_body), Modifier.padding(top = 16.dp))
            if (check.switchedOff) Body(stringResource(R.string.debugstep_off_why), Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            Body(stringResource(R.string.debugstep_note), Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            LiveLine(stringResource(if (reach is Reach.Connecting) R.string.debugstep_connecting else R.string.debugstep_waiting))
        }
    }
    val stuck = check.stuck && reach is Reach.NeedsDebugging
    if (stuck) Body(stringResource(R.string.debugstep_stuck), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    TertiaryButton(stringResource(if (open) R.string.debugstep_details_hide else R.string.debugstep_details_show), { open = !open },
        Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp))
    if (open) {
        SectionLabel(stringResource(R.string.debugstep_sees))
        CheckRows(check)
        DetailsButtons(details, onDetails, Modifier.padding(top = 12.dp))
    }
    // 0.4.8: the TV may have a new address (the hint and the saved address row say so): type it, as in Setup.
    if ((open || stuck) && onAddress != null && reach is Reach.NeedsDebugging)
        TertiaryButton(stringResource(R.string.debugstep_address), onAddress, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
    if (open || stuck) TertiaryButton(stringResource(R.string.debugstep_report), { onReport(title) }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
    TertiaryButton(stringResource(R.string.action_cancel), onCancel, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), quiet = true)
}

// "What the app sees": each row Yes, No, Checking or Can't tell, with what to do when it matters.
@Composable
private fun CheckRows(check: ReachCheck) {
    val savedNo = check.savedAnswers != Seen.YES
    ListGroup {
        CheckRow(R.string.debugstep_row_wifi, check.onWifi, if (check.onWifi == Seen.NO) R.string.debugstep_row_wifi_no else null)
        ListDivider()
        CheckRow(R.string.debugstep_row_network, check.sameNetwork, if (check.sameNetwork == Seen.NO) R.string.debugstep_row_network_no else null)
        ListDivider()
        CheckRow(R.string.debugstep_row_saved, check.savedAnswers, if (check.savedAnswers == Seen.NO) R.string.debugstep_row_saved_no else null)
        ListDivider()
        CheckRow(R.string.debugstep_row_found, check.foundElsewhere, if (check.foundElsewhere == Seen.NO && savedNo) R.string.debugstep_row_found_no else null)
        ListDivider()
        CheckRow(R.string.debugstep_row_allow, check.asksToAllow, if (check.asksToAllow == Seen.YES) R.string.debugstep_row_allow_yes else null)
        check.typed?.let { typed ->
            ListDivider()
            CheckRow(R.string.debugstep_row_typed, typed, if (typed == Seen.NO) R.string.debugstep_row_typed_no else null)
        }
    }
}

@Composable
private fun CheckRow(title: Int, seen: Seen, todo: Int?) {
    ListRow(stringResource(title), subtitle = todo?.let { stringResource(it) }, subtitleColor = CytTheme.colors.textSecondary,
        value = stringResource(when (seen) {
            Seen.YES -> R.string.debugstep_yes
            Seen.NO -> R.string.debugstep_no
            Seen.CHECKING -> R.string.debugstep_checking
            Seen.UNKNOWN -> R.string.debugstep_unknown
        }))
}

// "Copy connection details" and "Share", or the live line while the details are collected.
@Composable
internal fun ColumnScope.DetailsButtons(details: DetailsUi, onDetails: (share: Boolean) -> Unit, modifier: Modifier = Modifier) {
    when {
        details.busy -> LiveLine(stringResource(R.string.details_collecting), modifier)
        else -> {
            SecondaryButton(stringResource(R.string.details_copy), { onDetails(false) }, modifier.fillMaxWidth())
            TertiaryButton(stringResource(R.string.details_share), { onDetails(true) }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            if (details.copied) Body(stringResource(R.string.details_copied), Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
        }
    }
}

// App settings' "Connection details" sheet: the same block when no debugging step is open; the app
// then checks each saved TV once.
@Composable
internal fun ColumnScope.DetailsContent(details: DetailsUi, onDetails: (share: Boolean) -> Unit) {
    SheetHeader(stringResource(R.string.details_title))
    Body(stringResource(R.string.details_body), Modifier.padding(top = 12.dp))
    if (details.busy) LiveLine(stringResource(R.string.details_collecting))
    else {
        PrimaryButton(stringResource(R.string.details_copy), { onDetails(false) }, Modifier.padding(top = 20.dp), guardObscured = false)
        TertiaryButton(stringResource(R.string.details_share), { onDetails(true) }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
        if (details.copied) Body(stringResource(R.string.details_copied), Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
    }
}
