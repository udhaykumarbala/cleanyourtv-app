// CytBottomSheet takes the Material 3 sheet state, which is still marked experimental.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.cleanyour.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.Confirmations
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.HomeKept
import tv.cleanyour.core.flows.HomeRole
import tv.cleanyour.core.flows.IssueLinks
import tv.cleanyour.core.flows.LookName
import tv.cleanyour.core.flows.NothingWhy
import tv.cleanyour.core.flows.Reconnect
import tv.cleanyour.core.flows.RestartRead
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.Sheet
import tv.cleanyour.core.flows.StopAction
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.core.planner.switchOffCount
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.CheckState
import tv.cleanyour.ui.design.component.CollapsedGroupsCard
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.component.CountdownConfirm
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DiffGroup
import tv.cleanyour.ui.design.component.DiffGroupSection
import tv.cleanyour.ui.design.component.DiffItem
import tv.cleanyour.ui.design.component.DockApp
import tv.cleanyour.ui.design.component.DockEditor
import tv.cleanyour.ui.design.component.DockEditorState
import tv.cleanyour.ui.design.component.Gap
import tv.cleanyour.ui.design.component.GlassCheckMark
import tv.cleanyour.ui.design.component.GroupTone
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.LinkRow
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.MonospaceBlock
import tv.cleanyour.ui.design.component.NarrationLine
import tv.cleanyour.ui.design.component.NarrationState
import tv.cleanyour.ui.design.component.NumeralHeadline
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.PlanSummaryCard
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ProgressNarration
import tv.cleanyour.ui.design.component.ProtectedGroupSection
import tv.cleanyour.ui.design.component.SafetyLine
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.SmallGlassButton
import tv.cleanyour.ui.design.component.Spinner
import tv.cleanyour.ui.design.component.StopCard
import tv.cleanyour.ui.design.component.SummaryGroup
import tv.cleanyour.ui.design.component.SwitchRow
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TextActionRow
import tv.cleanyour.ui.design.component.TitleSize
import tv.cleanyour.ui.design.component.TvStatusChip
import tv.cleanyour.ui.design.component.TvThumbnail
import tv.cleanyour.ui.design.component.UndoSnackbarHost
import tv.cleanyour.ui.design.component.UndoSnackbarState
import tv.cleanyour.ui.design.component.galleryPictureName
import tv.cleanyour.ui.design.component.rememberEmphasis
import tv.cleanyour.ui.design.component.styleName
import tv.cleanyour.ui.design.component.rememberUndoSnackbarState
import tv.cleanyour.ui.design.component.LookDoneFooter
import tv.cleanyour.ui.design.component.LookEditor
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookEditorSheet
import tv.cleanyour.ui.design.component.rememberLookEditorNav
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.rememberPng
import tv.cleanyour.ui.design.component.toEditor
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow
import tv.cleanyour.core.engine.NarrationState as EngineNarration

// Screens S2d probing, S3, S3a, S3b, S4a, S4b, S4c to S4e and S5 (PRODUCT-SPEC 4, UX 2 screens 3 to 5,
// UX 4.7, 4.8, 4.14), built from the :ui:design components. Stateless over SetupState and
// SetupExtras.
@Composable
fun SetupFlow(
    state: SetupState,
    extras: SetupExtras,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    onCopyCommands: (String) -> Unit = {},
    nowMs: () -> Long = System::currentTimeMillis,
    // 0.3.2 B1: the TV report preview is the app's own screen; this opens it ("report/tv/<id>").
    // 0.4.6: a stop card's "Report a problem" goes through SetupActions.report instead.
    onRoute: (String) -> Unit = {},
    // 0.4.0: the system photo picker, for "Choose a photo" in the look step.
    photos: PhotoPicker? = null,
    // 0.4.7: the Why sheet's link to the TV list page (the browser opens it).
    onOpenUrl: (String) -> Unit = {},
) {
    when (state) {
        is SetupState.Probing -> ProbingScreen(state, extras, modifier, actions)
        is SetupState.Found -> FoundScreen(state, extras, actions, modifier, onCopyCommands, onHelpAddTv = { onRoute("report/tv/${state.tvId.value}") }, photos = photos,
            onOpenUrl = onOpenUrl)
        is SetupState.Working -> WorkingScreen(state, extras, actions, modifier)
        is SetupState.Check -> CheckScreen(state, extras, actions, modifier, nowMs)
        is SetupState.Stopped -> StoppedScreen(state, extras, actions, modifier)
        is SetupState.Done -> DoneScreen(state, extras, actions, modifier, onHelpAddTv = { onRoute("report/tv/${state.tvId.value}") }, onOpenUrl = onOpenUrl)
        else -> {}
    }
}


@Composable
private fun TopBar(extras: SetupExtras, onBack: (() -> Unit)? = null) =
    CytTopBar(onBack = onBack, center = { extras.tvName?.let { TvStatusChip(it, (extras.chip ?: ChipState.CONNECTED).toTvStatus()) } })

fun dateTime(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

// S2d to S3: the probe's progress while the summary fills in.
@Composable
fun ProbingScreen(state: SetupState.Probing, extras: SetupExtras, modifier: Modifier = Modifier, actions: SetupActions = tv.cleanyour.core.flows.NoSetupActions) {
    val candidate = extras.candidate
    ScreenScaffold(Glow.Tv, modifier, topBar = { TopBar(extras) }) {
        if (candidate != null) CandidateCard(candidate.issue, actions)
        else {
            StatusLine(stringResource(R.string.probing_line), Modifier.padding(start = 4.dp, top = 20.dp))
            val stages = tv.cleanyour.core.probe.ProbeStage.entries
            val probingCd = stringResource(R.string.probing_progress_cd)
            ProgressBar((stages.indexOf(state.stage) + 1f) / stages.size, Modifier.padding(top = 16.dp).semantics { contentDescription = probingCd })
        }
    }
}

// Test build: the TV's list is a candidate (made from reports of the same model, not tested yet).
// Nothing is planned until the person answers: try the test list, or stay with the careful set.
@Composable
internal fun CandidateCard(issue: String?, actions: SetupActions) {
    val c = CytTheme.colors
    val shape = CytTheme.shapes.medium
    Overline(stringResource(R.string.candidate_overline), Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(c.surface2, shape).border(1.dp, c.rim, shape).padding(20.dp)) {
        Text(stringResource(R.string.candidate_title), Modifier.semantics { heading() }, color = c.textPrimary, style = CytTheme.type.titleLarge)
        Body(stringResource(R.string.candidate_body), Modifier.padding(top = 8.dp))
        Body(if (issue != null) msg(R.string.candidate_ask_issue, "issue" to issue) else stringResource(R.string.candidate_ask), Modifier.padding(top = 12.dp))
        for (id in listOf(R.string.candidate_check_wifi, R.string.candidate_check_remote, R.string.candidate_check_hdmi, R.string.candidate_check_live_tv, R.string.candidate_check_casting))
            Body("\u2022  " + stringResource(id), Modifier.padding(start = 8.dp, top = 6.dp), color = c.textPrimary)
        ActionColumn(Modifier.padding(top = 20.dp)) {
            PrimaryButton(stringResource(R.string.candidate_try), { actions.chooseCandidate(true) })
            SecondaryButton(stringResource(R.string.candidate_careful), { actions.chooseCandidate(false) })
        }
        Body(stringResource(R.string.candidate_careful_note), Modifier.padding(top = 12.dp))
    }
    Gap(8.dp)
}

// S3 What we found (D4, D17 grey line, UX 2 rules behind the numbers, UX 4.6).
// 0.4.7 (UX findings 4, 5, 12, 13; PM 1, 7): when the TV keeps its own home screen (HomeKept) the
// outcome is the first row under the headline, with a short reason and "Why" (the long reason and
// what would change it, in a sheet), never a banner at the bottom. With nothing to switch off as
// well, there is no "Make it clean": "Done" ends Setup, and an unlisted TV offers "Help add this TV".
@Composable
fun FoundScreen(
    state: SetupState.Found, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyCommands: (String) -> Unit = {},
    onHelpAddTv: () -> Unit = {},
    photos: PhotoPicker? = null,
    onOpenUrl: (String) -> Unit = {},
) {
    val summary = state.plan.summary
    // Every switch-off of the plan, the home screen switch's own included (round 3).
    val off = summary.switchOffCount
    val kept = extras.homeKept as? HomeKept.Blocked ?: if (!summary.homeScreenPossible && extras.homePossible) HomeKept.Blocked(HomeBlock.NONE, extras.fireTv, extras.brand) else null
    // 0.4.7-beta2 (issue #251): with the Home button helper switched on there is something to do.
    val nothingToDo = kept != null && off == 0 && !extras.helperOn
    var why by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { TopBar(extras, onBack = { actions.back() }) },
        bottomBar = {
            // UX 7.3 rule 8: the safety sentence sits above the button that needs it, so it is
            // pinned with the button rather than scrolled under it (R4-W8).
            // D36 (RF-LAUNCHER-240 F1): replacing a home screen app signed with another key erases what
            // it kept, so S3 says so right above the button; the credential prompt after it is the
            // confirmation. Pinned with the button, so it cannot be scrolled past unseen.
            if (summary.replacesLauncher) Banner(stringResource(R.string.found_replaces_launcher), BannerTone.INFO, Modifier.padding(bottom = 12.dp))
            if (!nothingToDo) SafetyLine(stringResource(R.string.found_safety), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp))
            ActionColumn {
                when {
                    // An empty plan on a kept TV runs nothing: no credential prompt, no "Nothing was changed".
                    // RFC-HELPER-1 (test build): an empty plan still opens Customise where the Home button
                    // helper is offered, or a TV cleaned before (Run Setup again) could never reach it (#251).
                    nothingToDo && extras.unknownModel -> {
                        PrimaryButton(stringResource(R.string.found_help_add_tv), onHelpAddTv, guardObscured = false)
                        SecondaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) })
                        if (extras.helperOffer) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })
                    }
                    nothingToDo -> {
                        PrimaryButton(stringResource(R.string.action_done), { actions.intent(SetupIntent.LeaveAsIs) }, guardObscured = false)
                        if (extras.helperOffer) TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })
                    }
                    else -> {
                        // Nothing to switch off and the switch is possible: the button says what it does.
                        PrimaryButton(stringResource(if (off == 0) R.string.found_switch_home else R.string.found_make_clean), { actions.intent(SetupIntent.MakeItClean) }, loading = extras.starting)
                        TertiaryButton(stringResource(R.string.found_customise), { actions.intent(SetupIntent.Open(Sheet.CUSTOMISE)) })
                    }
                }
            }
        },
    ) {
        Overline(stringResource(R.string.found_overline), Modifier.padding(top = 8.dp))
        when {
            off > 0 -> NumeralHeadline(off, msg(if (extras.unknownModel) R.string.found_unknown_headline else R.string.found_headline, "count" to off), Modifier.padding(top = 6.dp))
            nothingToDo -> {
                ScreenTitle(stringResource(R.string.found_title_zero_kept), Modifier.padding(top = 8.dp))
                Body(stringResource(if (extras.unknownModel) R.string.found_zero_kept_unknown_body else R.string.found_zero_kept_body), Modifier.padding(start = 4.dp, top = 8.dp))
            }
            else -> {
                ScreenTitle(msg(if (extras.unknownModel) R.string.found_unknown_title else R.string.found_title, "count" to 0), Modifier.padding(top = 8.dp))
                if (!extras.unknownModel) Body(stringResource(R.string.found_zero_body), Modifier.padding(start = 4.dp, top = 8.dp))
            }
        }
        // The outcome first, above the fold: the TV keeps its own home screen, and in one line why.
        if (kept != null) KeptRow(kept, Modifier.padding(top = 16.dp), onWhy = { why = true })
        // RFC-HELPER-1 (test build): the Home button helper's outcome instead.
        if (extras.helperOn) HelperOutcomeRow(Modifier.padding(top = 16.dp))
        if (extras.unknownModel) {
            if (!nothingToDo) Body(stringResource(R.string.found_unknown_body), Modifier.padding(start = 4.dp, top = 12.dp))
            // 0.4.7: the report sent before, so the same report is not sent again by mistake.
            extras.reportSentAtMs?.let { Body(msg(R.string.found_report_sent, "date" to shortDate(it, locale)), Modifier.padding(start = 4.dp, top = 8.dp)) }
            // 0.3.2 B1: careful mode invites the person to help, next to the sentence that explains it.
            if (!nothingToDo) TertiaryButton(stringResource(R.string.found_help_add_tv), onHelpAddTv)
        }
        if (off > 0) PlanSummaryCard(
            summary.groups.filter { it.off > 0 }.map { SummaryGroup(it.id, it.title, it.off) },
            stringResource(R.string.found_see_list), { actions.intent(SetupIntent.Open(Sheet.LIST)) }, Modifier.padding(top = 20.dp),
        )
        if (!nothingToDo) {
            // UX finding 25: a Fire TV has no Chromecast.
            val fixed = stringResource(if (extras.fireTv) R.string.found_keep_fixed_fire else R.string.found_keep_fixed)
            val apps = summary.keepNames.joinToString(", ")
            val keep = if (summary.keepNames.isEmpty()) msg(R.string.found_keep_paragraph_no_apps, "fixed" to fixed)
            else msg(R.string.found_keep_paragraph, "apps" to apps, "fixed" to fixed)
            Text(
                rememberEmphasis(keep, apps), Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp),
                color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium,
            )
        }
        if (kept == null) {
            // The dock's apps by name, and how many more sit in the rows under it.
            val all = summary.dock.ifEmpty { extras.dock.map { it.name } }
            val dock = all.take(extras.dockColumns).joinToString(", ")
            val below = (all.size - extras.dockColumns).coerceAtLeast(0)
            // An empty dock never shows "Home screen:" with nothing after it.
            val row = when {
                dock.isEmpty() -> stringResource(R.string.found_home_row_empty)
                below > 0 -> msg(R.string.found_home_row_rows, "dock" to dock, "count" to below)
                else -> msg(R.string.found_home_row, "dock" to dock)
            }
            TextActionRow(row, stringResource(R.string.found_home_change), { actions.intent(SetupIntent.Open(Sheet.DOCK)) }, Modifier.padding(top = 8.dp))
            // Issue #275: TCL's firmware sends these remote buttons only to Google TV's own home
            // screen, so the person hears it before the switch, with the way back.
            // With the Home button helper the TV's own home screen stays Home, so they keep working.
            if (extras.tclRemoteKeys && !extras.helperOn) Banner(stringResource(R.string.found_tcl_remote_keys), BannerTone.INFO, Modifier.padding(top = 12.dp))
            // Issue #3059: on other Google TVs the remote's Settings (gear) button opens Google TV's
            // own settings panel, often with the input's picture and sound settings.
            if (extras.googleTvSettingsKey && !extras.helperOn) Banner(stringResource(R.string.found_google_tv_settings_key), BannerTone.INFO, Modifier.padding(top = 12.dp))
        }
        if (extras.noScreenLock && !nothingToDo) Body(stringResource(R.string.found_no_lock), Modifier.padding(start = 4.dp, top = 12.dp))
        Gap(8.dp)
    }
    when (state.sheet) {
        Sheet.LIST, Sheet.CUSTOMISE -> CustomiseSheet(state, extras, actions, onCopyCommands, kept = kept, onOpenUrl = onOpenUrl, onHelpAddTv = onHelpAddTv)
        Sheet.DOCK -> DockSheet(extras.dock, extras.apps, extras.dockColumns, onDone = { actions.intent(SetupIntent.SetDock(it)); actions.intent(SetupIntent.Open(null)) },
            onDismiss = { actions.intent(SetupIntent.Open(null)) })
        Sheet.THEME -> ThemeSheet(extras, actions, photos)
        else -> {}
    }
    if (why && kept != null) CytBottomSheet({ why = false }) { WhyKeptContent(kept, onOpenUrl, onHelpAddTv = if (kept.unlisted && HomeKept.needsList(kept.block, true)) onHelpAddTv else null) }
}

fun shortDate(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM", locale).format(Date(ms))

// 0.4.7: the outcome, the same words on every screen: "The TV keeps its own home screen.", the
// short reason for this TV, and "Why" for the long one.
@Composable
internal fun KeptRow(kept: HomeKept.Blocked, modifier: Modifier = Modifier, onWhy: () -> Unit) {
    val outcome = stringResource(R.string.kept_outcome)
    val reason = keptReason(kept)
    Banner(
        buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(outcome) }; append("\n"); append(reason) },
        BannerTone.INFO, modifier, icon = CytIcons.Info, actionLabel = stringResource(R.string.kept_why), onAction = onWhy,
    )
}

// RFC-HELPER-1 (test build): the outcome with the Home button helper, the same words on What we
// found, Customise and Done: the Home button opens the new home screen, the TV's own stays on.
@Composable
internal fun HelperOutcomeRow(modifier: Modifier = Modifier) {
    val outcome = stringResource(R.string.helper_outcome)
    val reason = stringResource(R.string.helper_outcome_body)
    Banner(
        buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(outcome) }; append("\n"); append(reason) },
        BannerTone.INFO, modifier, icon = CytIcons.Info,
    )
}

// The short reason, one per HomeBlock (UX findings 12 and 13: the TV's brand from its facts, never
// its nickname; a Fire TV is named for Fire OS).
@Composable
internal fun keptReason(kept: HomeKept.Blocked): String = when (kept.block) {
    HomeBlock.TV_REFUSES -> if (kept.fireTv) stringResource(R.string.kept_reason_fire_tv) else msg(R.string.kept_reason_tv_refuses, "brand" to kept.brand)
    HomeBlock.OLD_ANDROID -> stringResource(R.string.kept_reason_old_android)
    HomeBlock.UNKNOWN_HOME -> stringResource(R.string.kept_reason_unknown_home)
    HomeBlock.WIZARD_WIFI -> stringResource(R.string.kept_reason_wizard_wifi)
    HomeBlock.WIZARD_WIFI_UNKNOWN -> stringResource(R.string.kept_reason_wizard_wifi_unknown)
    HomeBlock.CASTING -> stringResource(R.string.kept_reason_casting)
    HomeBlock.BOOT_LOOP -> stringResource(R.string.kept_reason_boot_loop)
    HomeBlock.REMOTE_BUTTONS -> stringResource(R.string.kept_reason_remote_buttons)
    HomeBlock.WIZARD_STAYS_ON -> stringResource(R.string.kept_reason_wizard_stays_on)
    HomeBlock.HOME_CHANGED -> stringResource(R.string.kept_reason_home_changed)
    HomeBlock.OTHER_HOME -> stringResource(R.string.kept_reason_other_home)
    HomeBlock.VENDOR_HOME -> stringResource(R.string.kept_reason_vendor_home)
    HomeBlock.PATCHWALL_FEEDS -> stringResource(R.string.kept_reason_patchwall_feeds)
    HomeBlock.NONE -> stringResource(R.string.kept_reason_none)
}

// The Why sheet: the long reason, what would change it (when something can), the clean-up note and
// the TV list page. `onHelpAddTv`: a list is what would change it, so the report is one tap away.
@Composable
internal fun ColumnScope.WhyKeptContent(kept: HomeKept.Blocked, onOpenUrl: (String) -> Unit = {}, onHelpAddTv: (() -> Unit)? = null) {
    SheetHeader(stringResource(R.string.kept_why_title))
    val long = when (kept.block) {
        HomeBlock.TV_REFUSES -> if (kept.fireTv) stringResource(R.string.found_home_fire_tv) else msg(R.string.found_home_tv_refuses, "brand" to kept.brand)
        HomeBlock.OLD_ANDROID -> stringResource(R.string.found_home_old_android)
        HomeBlock.UNKNOWN_HOME -> stringResource(R.string.found_home_unknown_stock)
        HomeBlock.HOME_CHANGED -> stringResource(R.string.found_home_changed)
        // A TV no exact profile knows keeps its wizard on whoever saved the Wi-Fi, so the
        // "forget the network" advice would not help there.
        HomeBlock.WIZARD_WIFI -> stringResource(if (kept.unlisted) R.string.found_home_wizard_wifi_unlisted else R.string.found_home_wizard_wifi)
        HomeBlock.WIZARD_WIFI_UNKNOWN -> stringResource(R.string.found_home_wizard_wifi_unknown)
        HomeBlock.CASTING -> stringResource(R.string.found_home_casting)
        HomeBlock.BOOT_LOOP -> stringResource(R.string.found_home_boot_loop)
        HomeBlock.REMOTE_BUTTONS -> stringResource(R.string.found_home_remote_buttons)
        HomeBlock.WIZARD_STAYS_ON -> stringResource(R.string.found_home_wizard_stays_on)
        HomeBlock.OTHER_HOME -> stringResource(R.string.found_home_other_home)
        HomeBlock.VENDOR_HOME -> stringResource(R.string.found_home_vendor_home)
        HomeBlock.PATCHWALL_FEEDS -> stringResource(R.string.found_home_patchwall_feeds)
        HomeBlock.NONE -> stringResource(R.string.found_home_none)
    }
    Body(long, Modifier.padding(top = 8.dp), color = CytTheme.colors.textPrimary)
    val next = when {
        HomeKept.needsList(kept.block, kept.unlisted) && kept.unlisted -> R.string.kept_next_list
        kept.block == HomeBlock.WIZARD_WIFI -> R.string.kept_next_wifi
        kept.block == HomeBlock.WIZARD_WIFI_UNKNOWN -> R.string.kept_next_wifi_unknown
        kept.block == HomeBlock.HOME_CHANGED -> R.string.kept_next_home_changed
        kept.block == HomeBlock.OTHER_HOME -> R.string.kept_next_other_home
        kept.block == HomeBlock.PATCHWALL_FEEDS -> R.string.kept_next_patchwall_feeds
        kept.block == HomeBlock.NONE || kept.block == HomeBlock.UNKNOWN_HOME || kept.block == HomeBlock.WIZARD_STAYS_ON -> null
        else -> R.string.kept_next_nothing
    }
    Overline(stringResource(R.string.kept_what_changes_title), Modifier.padding(top = 20.dp, bottom = 6.dp))
    Body(stringResource(next ?: R.string.kept_next_list), Modifier.padding(top = 2.dp))
    if (onHelpAddTv != null) TertiaryButton(stringResource(R.string.found_help_add_tv), onHelpAddTv, Modifier.padding(top = 4.dp))
    Body(stringResource(R.string.kept_clean_up_note), Modifier.padding(top = 16.dp))
    LinkRow(stringResource(R.string.kept_tv_list), { onOpenUrl(IssueLinks.TV_LIST) }, Modifier.padding(top = 8.dp))
    Gap(8.dp)
}

// S3a See the list / Customise (D2, D3, D21, D22): a modal sheet that scrolls, with its own undo
// snackbar inside the sheet window.
@Composable
fun CustomiseSheet(
    state: SetupState.Found, extras: SetupExtras, actions: SetupActions, onCopyCommands: (String) -> Unit = {},
    kept: HomeKept.Blocked? = extras.homeKept as? HomeKept.Blocked, onOpenUrl: (String) -> Unit = {}, onHelpAddTv: () -> Unit = {},
) {
    val undo = rememberUndoSnackbarState()
    var why by remember { mutableStateOf(false) }
    var whyKept by remember { mutableStateOf(false) }
    CytBottomSheet({ actions.intent(SetupIntent.Open(null)) }) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CustomiseContent(state, extras, actions, undo, onWhy = { why = true }, onCopyCommands = onCopyCommands, kept = kept, onWhyKept = { whyKept = true })
            }
            UndoSnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (why) CytBottomSheet({ why = false }) { WhyContent(state, extras, kept) }
    if (whyKept && kept != null) CytBottomSheet({ whyKept = false }) { WhyKeptContent(kept, onOpenUrl, onHelpAddTv = if (kept.unlisted && HomeKept.needsList(kept.block, true)) onHelpAddTv else null) }
}

// 0.4.7 (UX findings 1, 2, 3, 11, 15): when the TV keeps its own home screen (`kept`) the sheet
// opens on the outcome card (the decision the rest depends on) instead of the Home screen group:
// no Dock, no Look (neither would reach the TV), no "then the new home screen". The home screen's
// own rows under "We keep these" say they stay in the app's words (HomeRole), and a PatchWall tap
// that changes the decision says so in the snackbar.
@Composable
internal fun ColumnScope.CustomiseContent(
    state: SetupState.Found,
    extras: SetupExtras,
    actions: SetupActions,
    undo: UndoSnackbarState,
    onWhy: () -> Unit,
    onCopyCommands: (String) -> Unit,
    initiallyOpen: List<String>? = null,
    kept: HomeKept.Blocked? = extras.homeKept as? HomeKept.Blocked,
    onWhyKept: () -> Unit = {},
) {
    val summary = state.plan.summary
    val total = summary.groups.sumOf { it.total }
    val off = summary.switchOffCount
    val custom = state.level == Level.CUSTOM
    val keptText = stringResource(R.string.customise_snackbar_kept)
    val offText = stringResource(R.string.customise_snackbar_off)
    val keptHomeOwnText = stringResource(R.string.customise_snackbar_kept_home_own)
    val offHomeBackText = stringResource(R.string.customise_snackbar_off_home_back)
    val locale = LocalConfiguration.current.locales[0]
    SheetHeader(
        stringResource(R.string.customise_title),
        trailing = rememberEmphasis(msg(R.string.customise_live, "total" to total, "off" to off), "$off"),
        subtitle = stringResource(R.string.customise_nothing_deleted),
    )
    if (kept != null) KeptRow(kept, Modifier.padding(top = 12.dp), onWhy = onWhyKept)
    if (extras.helperOn) HelperOutcomeRow(Modifier.padding(top = 12.dp))   // RFC-HELPER-1 (test build)
    // "We keep these" holds the protected rows that stay on; a protected row that is off is part of
    // the home screen switch and stays in its group, shown Off with nothing to flip.
    val protectedRows = summary.groups.flatMap { g -> g.rows.filter { it.kept() } }
    val groups = summary.groups.map { g -> g.copy(rows = g.rows.filterNot { it.kept() }) }.filter { it.rows.isNotEmpty() }
    // The home screen's own rows in the app's words (UX finding 3), by what they are to the switch.
    val roles = extras.homeRoles
    val wizardName = stringResource(R.string.customise_wizard_name)
    val wizardOff = stringResource(R.string.customise_wizard_off)
    val ownHomeOff = stringResource(R.string.customise_own_home_off)
    val wizardKept = stringResource(R.string.customise_wizard_kept)
    val ownHomeKept = stringResource(R.string.customise_own_home_kept)
    // RFC-HELPER-1 (test build): with the Home button helper both stay on, and the rows say why.
    val wizardStays = if (extras.helperOn) stringResource(R.string.customise_wizard_helper) else wizardKept
    val ownHomeStays = if (extras.helperOn) stringResource(R.string.customise_own_home_helper) else ownHomeKept
    val stays = kept != null || extras.helperOn
    fun RowSummary.item(): DiffItem = when (roles[pkg]) {
        HomeRole.WIZARD -> if (this.off) DiffItem(pkg.value, wizardName, wizardOff, pkg.value, true, fixed = protected)
            else if (stays) DiffItem(pkg.value, friendlyName, wizardStays, pkg.value, false, fixed = false) else toItem()
        HomeRole.OWN_HOME -> if (this.off && protected) DiffItem(pkg.value, friendlyName, ownHomeOff, pkg.value, true, fixed = true)
            else if (stays && protected) DiffItem(pkg.value, friendlyName, ownHomeStays, pkg.value, false, fixed = false) else toItem()
        null -> toItem()
    }
    // The first group opens; the others wait collapsed until tapped (DESIGN-SYSTEM 8.4, mockup 08).
    // Groups keep their order: an opened group expands where it was tapped, and the closed groups
    // around it stay in cards above and below it (device test 2026-10-20: it jumped to the top).
    var open by rememberSaveable { mutableStateOf(initiallyOpen ?: listOfNotNull(groups.firstOrNull()?.id)) }
    // Runs of closed groups between the open ones, in list order.
    val segments = mutableListOf<List<GroupSummary>>()
    for (g in groups) {
        val last = segments.lastOrNull()
        if (g.id !in open && last != null && last.first().id !in open) segments[segments.lastIndex] = last + g else segments += listOf(g)
    }
    // UX finding 15: a PatchWall tap that changes the home screen decision says so. PatchWall off
    // while the TV keeps its own home screen for its feeds brings the switch back; keeping it on
    // while its feeds are off takes it away.
    val switchPlanned = kept == null && !extras.helperOn && summary.homeScreenPossible && state.plan.sections.isNotEmpty()
    fun snackText(row: RowSummary): String {
        val patchWall = row.pkg == extras.patchWall
        val pattern = when {
            row.off -> if (patchWall && switchPlanned && extras.patchWallHoldsHome) keptHomeOwnText else keptText
            else -> if (patchWall && kept?.block == HomeBlock.PATCHWALL_FEEDS) offHomeBackText else offText
        }
        return android.icu.text.MessageFormat(pattern, locale).format(mapOf("name" to row.friendlyName))
    }
    for (seg in segments) key(seg.first().id) {
        val g = seg.first()
        if (g.id in open) {
            GroupSection(g, custom, onCollapse = { open = open - g.id }, item = { it.item() }) { row ->
                actions.intent(SetupIntent.Keep(row.pkg, keep = row.off))
                undo.show(snackText(row)) { actions.intent(SetupIntent.Keep(row.pkg, keep = !row.off)) }
            }
        } else {
            CollapsedGroupsCard(seg.map { c -> DiffGroup(c.id, c.title, c.rows.map { it.item() }) }, { open = open + it.key }, Modifier.padding(top = 10.dp))
        }
    }
    if (protectedRows.isNotEmpty()) {
        ProtectedGroupSection(stringResource(R.string.customise_protected_title), protectedRows.map { it.item() }, onWhy, Modifier.padding(top = 16.dp), showPackageNames = custom)
    }
    if (kept == null) {
        Overline(stringResource(R.string.customise_group_home), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
        ListGroup {
            val inDock = extras.dock.take(extras.dockColumns).joinToString(", ") { it.name }
            val below = (extras.dock.size - extras.dockColumns).coerceAtLeast(0)
            val dockRow = when {
                extras.dock.isEmpty() -> stringResource(R.string.customise_dock_row_empty)
                below > 0 -> msg(R.string.customise_dock_row_rows, "names" to inDock, "count" to below,
                    "rows" to (below + extras.dockColumns - 1) / extras.dockColumns.coerceAtLeast(1))
                else -> msg(R.string.customise_dock_row, "names" to inDock)
            }
            ListRow(dockRow, onClick = { actions.intent(SetupIntent.Open(Sheet.DOCK)) })
            ListDivider()
            // The look, and its apps in a row when it is not the look's own ("Look: Console, 6 apps in a row").
            val theme = lookName(extras)
            val count = extras.look?.takeIf { it.columns != it.lookColumns }?.columns
            ListRow(if (count != null) msg(R.string.customise_look_row_count, "theme" to theme, "count" to count) else msg(R.string.customise_look_row, "theme" to theme),
                onClick = { actions.intent(SetupIntent.Open(Sheet.THEME)) })
        }
    }
    // UX finding 11: the curtain cannot install on an older Android, and a TV that refuses other
    // home screens refuses it too, so the row is not offered there.
    val curtainPossible = kept?.block != HomeBlock.OLD_ANDROID && kept?.block != HomeBlock.TV_REFUSES
    if (custom && curtainPossible) {
        // RFC-CURTAIN-1: Custom only, on by default; Simple always shows the progress on the TV.
        Overline(stringResource(R.string.customise_group_tv), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
        ListGroup {
            SwitchRow(
                stringResource(R.string.customise_show_on_tv), extras.draft?.curtain ?: true,
                { actions.intent(SetupIntent.ShowOnTv(it)) }, subtitle = stringResource(R.string.customise_show_on_tv_note),
            )
        }
    }
    HomeHelperGroup(extras, actions)   // RFC-HELPER-1 (test build)
    Overline(stringResource(R.string.customise_group_afterwards), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    ListGroup {
        SwitchRow(
            stringResource(R.string.customise_keep_debugging), extras.draft?.keepDebuggingOn == true,
            { actions.intent(SetupIntent.KeepDebuggingOn(it)) }, subtitle = stringResource(R.string.customise_keep_debugging_warning), warning = true,
        )
    }
    Overline(stringResource(R.string.customise_group_commands), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    Body(stringResource(R.string.customise_commands_intro), Modifier.padding(start = 4.dp))
    MonospaceBlock(
        extras.commands, Modifier.padding(top = 8.dp),
        copyLabel = stringResource(R.string.customise_copy_commands), onCopy = { onCopyCommands(extras.commands.joinToString("\n")) },
    )
}

private fun RowSummary.toItem() = DiffItem(pkg.value, friendlyName, description, pkg.value, off, fixed = protected && off)

// A protected row that stays on: the "We keep these" group.
private fun RowSummary.kept() = protected && !off

// A group of the full list: all rows when there are six or fewer, otherwise four and "Show n more".
@Composable
private fun GroupSection(g: GroupSummary, custom: Boolean, onCollapse: () -> Unit, item: (RowSummary) -> DiffItem = { it.toItem() }, onToggle: (RowSummary) -> Unit) {
    var expanded by rememberSaveable(g.id) { mutableStateOf(false) }
    val visible = if (expanded || g.rows.size <= 6) g.rows.size else 4
    DiffGroupSection(
        DiffGroup(g.id, g.title, g.rows.map(item)),
        onToggle = { item -> g.rows.firstOrNull { it.pkg.value == item.key && !it.protected }?.let(onToggle) },
        modifier = Modifier.padding(top = 16.dp), showPackageNames = custom, visibleCount = visible, onShowMore = { expanded = true },
        onCollapse = onCollapse,
    )
}

// "Why?" on the protected group: one reason per row. 0.4.7 (UX finding 3): the home screen's own
// rows get the app's words, by HomeRole, instead of the planner's reason strings.
@Composable
internal fun WhyContent(state: SetupState.Found, extras: SetupExtras = SetupExtras(), kept: HomeKept.Blocked? = extras.homeKept as? HomeKept.Blocked) {
    SheetHeader(stringResource(R.string.customise_protected_why_title))
    state.plan.summary.groups.flatMap { g -> g.rows.filter { it.kept() } }.forEach { r ->
        Text(r.friendlyName, Modifier.padding(top = 16.dp), color = CytTheme.colors.textPrimary, style = CytTheme.type.titleMedium)
        val why = when (extras.homeRoles[r.pkg]) {
            HomeRole.WIZARD -> stringResource(when { extras.helperOn -> R.string.customise_wizard_helper; kept != null -> R.string.customise_wizard_kept; else -> R.string.customise_switch_part })
            HomeRole.OWN_HOME -> stringResource(when { extras.helperOn -> R.string.customise_own_home_helper; kept != null -> R.string.customise_own_home_kept; else -> R.string.customise_switch_part })
            null -> r.whyProtected ?: r.description
        }
        Body(why, Modifier.padding(top = 4.dp))
    }
}

// S3b Your dock: the dock and the rows under it (DockEditor). Hold and drag to reorder; the up and
// down buttons stay for motor access (UX 7.1). Done hands back the one ordered list.
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, columns: Int, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    val state = remember(current, columns) { DockEditorState(current.map { it.toDockApp() }, columns) }
    val scroll = rememberScrollState()
    val undo = rememberUndoSnackbarState()
    CytBottomSheet(onDismiss) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(scroll)) {
                DockContent(state, apps, onDone = { onDone(state.apps.map { PackageName(it.key) }) }, scroll, undo)
            }
            UndoSnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun DockContent(state: DockEditorState, apps: List<AppChoice>, onDone: () -> Unit, scroll: ScrollState? = null, undo: UndoSnackbarState? = null) {
    SheetHeader(stringResource(R.string.dock_title), subtitle = msg(R.string.dock_body, "count" to state.columns))
    DockEditor(state, remember(apps) { apps.map { it.toDockApp() } }, scroll = scroll, undo = undo)
    PrimaryButton(stringResource(R.string.action_done), onDone, Modifier.padding(top = 20.dp))
}

internal fun AppChoice.toDockApp() = DockApp(pkg.value, name)

// Customise's look name. 0.4.7: a look2 choice names its style and picture ("Classic with your photo"); the row read
// only the 0.4.0 theme id before, so a look with the person's own photo said "Look: Default". A 0.4.0 look keeps its
// name, and the Default look (Classic, no picture) stays "Default".
@Composable
private fun lookName(extras: SetupExtras): String = when (val n = LookName.of(extras.look, extras.draft?.themeId)) {
    is LookName.Legacy -> extras.themes.firstOrNull { it.id == n.themeId }?.name ?: stringResource(R.string.theme_default)
    is LookName.Styled -> when {
        n.photo -> msg(R.string.customise_look_photo, "style" to styleName(n.style))
        n.picture != null -> extras.pictures.firstOrNull { it.id == n.picture }
            ?.let { msg(R.string.customise_look_picture, "style" to styleName(n.style), "picture" to galleryPictureName(it.id, it.name)) } ?: styleName(n.style)
        else -> styleName(n.style)
    }
}

// The look step (S3a "Look: Default >"): the Look editor of :ui:design, the same pages in the same
// order as the Look sheet after Setup (Picture, Style, Apps in a row, Names under every app, More
// options and Advanced), with Done as its footer. A tap only changes the draft (SetupIntent.Look,
// the same edits as the Look sheet), so nothing here is guarded; the push happens when Setup runs.
// Closing keeps the choice, so there is no leave question. `pictures`: the pictures by file, given
// by the screenshot tests (the app reads the files).
@Composable
internal fun ThemeSheet(extras: SetupExtras, actions: SetupActions, photos: PhotoPicker? = null) {
    val editor = extras.toEditor() ?: return
    LookEditorSheet(
        editor, onEdit = { actions.intent(SetupIntent.Look(it)) },
        onPhoto = { jpeg, dim -> actions.intent(SetupIntent.SetOwnPicture(jpeg, dim)) },
        onClose = { actions.intent(SetupIntent.Open(null)) },
        footer = { LookDoneFooter { actions.intent(SetupIntent.Open(null)) } },
        photos = photos, asksBeforeLeaving = false, photoAfter = stringResource(R.string.look_photo_after),
    )
}

// The same editor without the modal window, for the screenshot tests (a bounded height, as the
// sheet gives it).
@Composable
internal fun ColumnScope.ThemeContent(extras: SetupExtras, actions: SetupActions, photos: PhotoPicker? = null, pictures: Map<String, ImageBitmap>? = null,
    modifier: Modifier = Modifier, nav: LookEditorNav = rememberLookEditorNav()) {
    val editor = extras.toEditor() ?: return
    LookEditor(
        editor, onEdit = { actions.intent(SetupIntent.Look(it)) },
        onPhoto = { jpeg, dim -> actions.intent(SetupIntent.SetOwnPicture(jpeg, dim)) },
        onClose = { actions.intent(SetupIntent.Open(null)) },
        footer = { LookDoneFooter { actions.intent(SetupIntent.Open(null)) } },
        photos = photos, pictures = pictures, modifier = modifier, nav = nav, asksBeforeLeaving = false,
        photoAfter = stringResource(R.string.look_photo_after),
    )
}

// The look step's draft as the editor shows it: the one mapping, LookEditorFrom. Setup has no
// earlier look yet.
internal fun SetupExtras.toEditor(): LookEditorState? = look?.toEditor(pictures, ownPicture, ownDim, newPicture, newDim, earlierPicture = null)

// S4a Making it clean: narration, live thumbnail, the quiet "Stop and undo" (UX 4a, D18, D32).
@Composable
fun WorkingScreen(state: SetupState.Working, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    val run = state.run
    // "Stop and undo" asks first; the run carries on until the answer is yes.
    var asking by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { TopBar(extras) },
        bottomBar = {
            ActionColumn {
                TertiaryButton(
                    stringResource(if (extras.rollingBack) R.string.working_stopping else R.string.working_stop), { asking = true },
                    quiet = !extras.highlightStop, enabled = !extras.rollingBack,
                )
            }
        },
    ) {
        ProgressNarration(stringResource(R.string.working_title), run.percent, run.narration.toLines(), Modifier.padding(top = 8.dp))
        if (extras.rollingBack) StatusLine(stringResource(R.string.working_putting_back), Modifier.padding(start = 4.dp, top = 16.dp))
        TvThumbnail(
            rememberPng(state.thumbnail ?: run.thumbnail), stringResource(R.string.working_thumbnail_cd), Modifier.padding(top = 20.dp),
            label = stringResource(R.string.working_thumbnail_caption),
        )
        // UX finding 10: without a home screen check there is no "time to look at the TV".
        Body(stringResource(if (run.plan.sections.isEmpty()) R.string.working_keep_on_no_check else R.string.working_keep_on), Modifier.padding(start = 4.dp, top = 16.dp))
    }
    ConfirmSheetFor(if (asking && !extras.rollingBack) Confirmations.stopAndUndo else null,
        onConfirm = { asking = false; actions.intent(SetupIntent.StopAndUndo) }, onCancel = { asking = false })
}

// The engine's five line states on the design system's four: a step that did not work is shown as
// skipped (it is named on Done, UX 4.14).
private fun List<tv.cleanyour.core.engine.NarrationLine>.toLines(): List<NarrationLine> = mapIndexed { i, l ->
    NarrationLine(
        "$i:${l.text}", l.text,
        when (l.state) {
            EngineNarration.DOING -> NarrationState.DOING
            EngineNarration.DONE -> NarrationState.DONE
            EngineNarration.UPCOMING -> NarrationState.UPCOMING
            EngineNarration.SKIPPED, EngineNarration.FAILED -> NarrationState.SKIPPED
        },
    )
}

// S4b Can you see it? (DESIGN-SYSTEM 8.6, D23): the ring counts down from the engine's deadline;
// the ring itself tells TalkBack at 60, 30 and 10 seconds.
@Composable
fun CheckScreen(state: SetupState.Check, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, nowMs: () -> Long = System::currentTimeMillis) {
    val run = state.run
    var now by remember { mutableLongStateOf(nowMs()) }
    // Ticks once a second until the deadline; a clock that stands still (tests) stops the loop.
    LaunchedEffect(run.deadlineMs) {
        while (now < run.deadlineMs) {
            delay(1_000)
            val t = nowMs()
            if (t == now) break
            now = t
        }
    }
    val remaining = (run.deadlineMs - now).coerceAtLeast(0)
    val total = run.section.confirmWindowMs.coerceAtLeast(1)
    val shot = rememberPng(state.thumbnail ?: run.thumbnail)
    val locale = LocalConfiguration.current.locales[0]
    // "Netflix, YouTube, Prime Video and Live TV" in the person's language: the dock's own apps,
    // the ones along the bottom (the rows under it are not in the sentence).
    val inDock = state.dockNames.take(extras.dockColumns)
    val names = remember(inDock, locale) { android.icu.text.ListFormatter.getInstance(locale).format(inDock) }
    val dock = if (inDock.isEmpty()) stringResource(R.string.check_dock_sentence_empty)
    else msg(R.string.check_dock_sentence, "dock" to names)
    ScreenScaffold(Glow.Tv, modifier, scrollable = false, topBar = { TopBar(extras) }) {
        CountdownConfirm(
            overline = stringResource(R.string.check_overline),
            title = stringResource(R.string.check_title),
            thumbnail = shot,
            thumbnailDescription = msg(R.string.check_thumbnail_cd, "count" to inDock.size),
            sentence = dock + " " + stringResource(R.string.check_thumbnail_note),
            instruction = stringResource(R.string.check_press_home),
            liveCheck = stringResource(R.string.check_live_ok),
            liveCheckPassed = run.homeCheck == true,
            remainingMs = remaining, totalMs = total,
            timeoutSentence = stringResource(R.string.check_countdown_text),
            yesLabel = stringResource(R.string.check_yes), noLabel = stringResource(R.string.check_no),
            onYes = { actions.intent(SetupIntent.YesISeeIt) }, onNo = { actions.intent(SetupIntent.NoPutItBack) },
            noThumbnailSentence = dock + " " + stringResource(R.string.check_no_thumbnail_note),
        )
    }
}

// The stops worth a report: a step the TV refused, an install that failed, a probe that failed, a plan
// the app refused to make (0.3.4: the report carries the reason), a run cut off by the app closing,
// and the old home screen put back after a lost connection. The cards
// whose own button already opens the report (home screen unknown, "Something looked wrong", "Help
// me") are not in it.
// 0.4.6 (REPORTING-PROPOSAL 2.2): every stop, timeout and paused change, and every home screen check
// that did not hold, has it too; only the card the person chose (Undo) has none. It opens the
// GitHub form with the card filled in (SetupActions.report).
private val REPORT_CASES = setOf(StopCase.STEP_FAILED, StopCase.INSTALL_FAILED, StopCase.PROBE_FAILED, StopCase.PLAN_REFUSED, StopCase.RESUME_AFTER_DEATH,
    StopCase.HOME_PUT_BACK, StopCase.CONNECTION_LOST, StopCase.HOME_TIMED_OUT, StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_LAUNCHER,
    StopCase.HOME_SETTINGS_OPEN)

// The home cases: their card offers "Show details" with the run's lines (D21).
private val HOME_CASES = setOf(
    StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_LAUNCHER, StopCase.HOME_UNKNOWN, StopCase.HOME_USER_SAID_NO,
    StopCase.HOME_TIMED_OUT, StopCase.UNDONE, StopCase.HOME_PUT_BACK, StopCase.HOME_SETTINGS_OPEN,
)

// S4c, S4d, S4e: stop cards with a grey icon and the way out on the card (UX 4).
@Composable
fun StoppedScreen(state: SetupState.Stopped, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onReport: () -> Unit = { actions.report(state.card.title) }) {
    val c = state.card
    var details by rememberSaveable { mutableStateOf(false) }
    // "Stop here" and "Undo what we did" ask first (Confirmations); the others act at once.
    var asking by rememberSaveable(c.case) { mutableStateOf<StopAction?>(null) }
    fun act(action: StopAction) {
        if (Confirmations.forStopAction(c.case, action) != null) asking = action else actions.intent(SetupIntent.StopAction(action))
    }
    ScreenScaffold(Glow.Tv, modifier, topBar = { TopBar(extras) }) {
        val body = if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.GAVE_UP) stringResource(R.string.stop_connection_lost_gone_body) else c.body
        StopCard(
            c.title, body, Modifier.padding(top = 24.dp),
            primaryLabel = c.primary, onPrimary = { act(StopAction.PRIMARY) },
            secondaryLabel = c.secondary, onSecondary = { act(StopAction.SECONDARY) },
            tertiaryLabel = c.tertiary, onTertiary = { act(StopAction.TERTIARY) },
            detailsLabel = if (c.case in HOME_CASES && extras.commands.isNotEmpty()) stringResource(R.string.stop_show_details) else null, onDetails = { details = true },
            busyText = when {
                c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.TRYING -> stringResource(R.string.stop_connection_lost_reconnecting)
                extras.rollingBack -> stringResource(R.string.stop_home_wait_hint)
                else -> null
            },
            primaryGuarded = c.case.primaryActs(),
            secondaryGuarded = c.case.secondaryActs(),
            tertiaryGuarded = c.case.tertiaryActs(),
        )
        if (c.case == StopCase.CONNECTION_LOST && extras.reconnect == Reconnect.BACK) {
            Banner(stringResource(R.string.stop_connection_lost_back), BannerTone.SUCCESS, Modifier.padding(top = 16.dp))
        }
        // 0.3.2 B2: a stop the person did not cause offers the report under the card.
        if (c.case in REPORT_CASES) {
            TertiaryButton(stringResource(R.string.stop_report), onReport, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }
    }
    if (details) CytBottomSheet({ details = false }) { DetailsContent(extras.commands) }
    val pending = asking
    ConfirmSheetFor(pending?.let { Confirmations.forStopAction(c.case, it) },
        onConfirm = { asking = null; pending?.let { actions.intent(SetupIntent.StopAction(it)) } }, onCancel = { asking = null })
}

@Composable
internal fun DetailsContent(lines: List<String>) {
    SheetHeader(stringResource(R.string.stop_show_details))
    MonospaceBlock(lines, Modifier.padding(top = 16.dp))
}

// S5 Done (D7, D24, D31, UX 4.8 restart flow).
// 0.4.7 (UX findings 6, 7, 9, 18; PM 4): on a TV that keeps its own home screen the title is
// "Clean-up done", the lead says so with "Why", there is no card to add, and the restart line says
// the home screen is the TV's own. After a failed switch the next step is Home screen for this TV.
@Composable
fun DoneScreen(state: SetupState.Done, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onHelpAddTv: () -> Unit = {}, onOpenUrl: (String) -> Unit = {}) {
    val s = state.summary
    val locale = LocalConfiguration.current.locales[0]
    val finishing = extras.finishing
    val kept = extras.homeKept
    val blocked = kept as? HomeKept.Blocked
    var why by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        Glow.Done, modifier,
        topBar = { TopBar(extras) },
        bottomBar = {
            PrimaryButton(
                when (finishing) { Finishing.SWITCHING -> stringResource(R.string.done_finishing); Finishing.DONE -> stringResource(R.string.done_finished); null -> stringResource(R.string.done_finish) },
                { actions.intent(SetupIntent.Finish) }, loading = finishing == Finishing.SWITCHING, enabled = finishing == null,
            )
        },
    ) {
        val doneCd = stringResource(R.string.done_hero_cd)
        Box(Modifier.padding(top = 16.dp, start = 6.dp).semantics { contentDescription = doneCd }) { GlassCheckMark() }
        // Issue #15: a run that changed nothing says so, never "Your TV is ready" over "0 things".
        val nothing = extras.doneNothing
        ScreenTitle(stringResource(when { nothing -> R.string.done_title_nothing; kept != null -> R.string.done_title_kept; else -> R.string.done_title }),
            Modifier.padding(top = 22.dp), size = TitleSize.DISPLAY)
        val lead = when {
            // "We weren't sure about anything" only when nothing was planned on a TV without an exact list.
            nothing -> stringResource(when {
                extras.doneNothingWhy == NothingWhy.KEPT_ON -> R.string.done_body_nothing_kept
                extras.doneNothingWhy == NothingWhy.STOPPED -> R.string.done_body_nothing_stopped
                extras.unknownModel -> R.string.done_body_nothing_unknown
                else -> R.string.done_body_nothing
            })
            // RFC-HELPER-1 (test build): the helper's redirect, never "New home screen on" as for the switch.
            extras.helperOn && extras.doneHomeKept == null -> msg(R.string.done_body_helper, "count" to s.switchedOff)
            s.homeScreenOn -> msg(R.string.done_body, "count" to s.switchedOff)
            blocked != null -> msg(R.string.done_body_home_kept, "count" to s.switchedOff)
            else -> msg(R.string.done_body_no_home, "count" to s.switchedOff)
        }
        Lead(lead, Modifier.padding(top = 8.dp))
        if (blocked != null) TertiaryButton(stringResource(R.string.kept_why), { why = true })
        // QA of 0.4.5: the home screen switch was tried and the TV's own home screen is back; Done says so and why.
        extras.doneHomeKept?.let { outcome ->
            Body(stringResource(when (outcome) {
                HomeOutcome.TIMED_OUT -> R.string.done_home_kept_timed_out
                HomeOutcome.USER_SAID_NO -> R.string.done_home_kept_said_no
                else -> R.string.done_home_kept
            }), Modifier.padding(top = 8.dp))
        }
        // UX finding 18 (issues #1279, #1649): the look did not reach the TV; the Look sheet opens on the choice.
        if (extras.doneLookFailed) Body(stringResource(R.string.done_look_failed), Modifier.padding(top = 8.dp))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // beta3 QA: a later run names the restore point Undo everything goes back to, the first one.
            CheckLine(msg(if (s.restorePointEarlier) R.string.done_restore_earlier else R.string.done_restore_saved, "date" to dateTime(s.restorePointAtMs, locale)))
            // D7: with "Keep debugging on" chosen, Finish leaves debugging on, and the line says so.
            CheckLine(
                stringResource(when {
                    finishing == Finishing.DONE -> R.string.done_finished
                    extras.keepsDebugging -> R.string.done_debug_kept
                    else -> R.string.done_debug_note
                }),
                state = if (finishing == Finishing.SWITCHING) CheckState.PENDING else CheckState.DONE,
            )
        }
        for (name in extras.doneKept) Body(msg(R.string.kept_tv_input, "name" to name), Modifier.padding(start = 4.dp, top = 12.dp))
        when {
            s.skipped.size == 1 -> Body(msg(R.string.done_skipped, "name" to s.skipped.single()), Modifier.padding(start = 4.dp, top = 12.dp))
            s.skipped.size > 1 -> Body(msg(R.string.done_skipped_many, "count" to s.skipped.size), Modifier.padding(start = 4.dp, top = 12.dp))
        }
        // 0.4.8 (PLAN item 3): the look went to the TV: what the TV shows now, and one tap to send it again.
        extras.doneShot?.let { png ->
            ListGroup(Modifier.padding(top = 18.dp)) {
                ListRow(stringResource(R.string.done_tv_now_title))
                TvThumbnail(rememberPng(png), stringResource(R.string.done_tv_now_cd), Modifier.padding(start = 16.dp, end = 16.dp), label = null)
                LinkRow(stringResource(R.string.done_tv_now_again), { actions.intent(SetupIntent.SendLookAgain) }, Modifier.padding(top = 4.dp))
            }
        }
        // Nothing changed, so there is nothing a restart could check.
        if (!nothing) RestartCard(state, extras, actions)
        extras.card?.takeIf { it.case == StopCase.NOT_BACK_AFTER_RESTART }?.let { c ->
            // The card is the news: scroll it into view when it appears instead of leaving it
            // under the pinned Finish (R4-W8).
            val bring = remember { BringIntoViewRequester() }
            // The request waits for the card's first placement: a request made before layout is a no-op.
            var placed by remember(c.case) { mutableStateOf(false) }
            LaunchedEffect(placed) { if (placed) bring.bringIntoView() }
            StopCard(
                c.title, c.body, Modifier.padding(top = 10.dp).bringIntoViewRequester(bring).onGloballyPositioned { placed = true },
                primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
                secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) },
                primaryGuarded = c.case.primaryActs(),
            )
        }
        ListGroup(Modifier.padding(top = 10.dp)) {
            // STORES-PLAN 1: the Play build has no add-ons, so Done offers no card to add.
            // UX finding 9: nor does a TV that keeps its own home screen (a card has nowhere to show).
            if (extras.addOns && kept == null) {
                ListRow(stringResource(R.string.done_add_card_title), subtitle = stringResource(R.string.done_add_card_body), onClick = { actions.intent(SetupIntent.AddCard) })
                ListDivider()
            }
            ListRow(stringResource(R.string.done_note_title), onClick = { actions.intent(SetupIntent.SaveRestoreNote) })
        }
        Gap(16.dp)
    }
    if (why && blocked != null) CytBottomSheet({ why = false }) { WhyKeptContent(blocked, onOpenUrl, onHelpAddTv = if (blocked.unlisted && HomeKept.needsList(blocked.block, true)) onHelpAddTv else null) }
}

// "Restart the TV to check it all sticks" with "Restart" (D24), then the UX 4.8 flow inline.
@Composable
private fun RestartCard(state: SetupState.Done, extras: SetupExtras, actions: SetupActions) {
    val r = state.restartState
    val title = stringResource(R.string.restart_card_title)
    val note = stringResource(if (state.summary.restartRecommended) R.string.restart_card_recommended else R.string.restart_card_optional)
    ListGroup(Modifier.padding(top = 18.dp), tone = GroupTone.RAISED) {
        when (r) {
            null, is RestartState.NotBack -> ListRow(
                title, subtitle = note,
                trailing = if (r == null) ({ SmallGlassButton(stringResource(R.string.restart_button), { actions.intent(SetupIntent.Restart) }) }) else null,
            )
            is RestartState.Restarting -> {
                ListRow(title, subtitle = stringResource(R.string.restart_intro), trailing = { Spinner() })
                if (extras.restartSlow) Body(stringResource(R.string.restart_slow), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
            is RestartState.Back -> {
                ListRow(title)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // UX finding 7: "the new one" only when it is; after a block or a failed switch
                    // the home screen is the TV's own. 0.4.8: what the app read after the restart (Restarts).
                    val read = extras.restartRead
                    val failedLine = stringResource(R.string.restart_progress_failed)
                    when {
                        read == RestartRead.FAILED -> CheckLine(failedLine, state = CheckState.NONE, emphasised = true)
                        read == RestartRead.HELD && !extras.helperOn -> CheckLine(stringResource(R.string.restart_progress_held))
                        else -> CheckLine(stringResource(when {
                            extras.helperOn && extras.doneHomeKept == null -> R.string.restart_progress_home_helper
                            state.summary.homeScreenOn || extras.homeKept == null -> R.string.restart_progress_home
                            else -> R.string.restart_progress_home_own
                        }))
                    }
                    if (extras.restartReenabled > 0) CheckLine(msg(R.string.restart_came_back, "count" to extras.restartReenabled))
                    if (read != RestartRead.FAILED) CheckLine(stringResource(R.string.restart_progress_good))
                    // 0.4.8 (PLAN item 2): one tap tells us; the preview shows what goes, and only its Open GitHub sends it.
                    if (read == RestartRead.HELD) {
                        SecondaryButton(stringResource(R.string.done_tell_us), { actions.intent(SetupIntent.TellUsItWorked) }, Modifier.padding(top = 6.dp))
                        Body(stringResource(R.string.done_tell_us_note))
                    }
                    if (read == RestartRead.FAILED) {
                        Body(stringResource(R.string.done_restart_report_note))
                        TertiaryButton(stringResource(R.string.stop_report), { actions.intent(SetupIntent.ReportRestartFailed(failedLine)) })
                    }
                }
            }
        }
    }
}
