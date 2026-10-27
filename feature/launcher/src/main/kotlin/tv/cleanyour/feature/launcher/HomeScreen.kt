@file:OptIn(ExperimentalMaterial3Api::class)

package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import tv.cleanyour.ui.design.component.UndoSnackbarHost
import tv.cleanyour.ui.design.component.UndoSnackbarState
import tv.cleanyour.ui.design.component.rememberUndoSnackbarState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.NotOnTv
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.CheckState
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DockApp
import tv.cleanyour.ui.design.component.DockEditor
import tv.cleanyour.ui.design.component.DockEditorState
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.Glow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.clearAndSetSemantics
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.component.galleryPictureName
import tv.cleanyour.ui.design.component.lookPicture
import tv.cleanyour.ui.design.component.styleName
import tv.cleanyour.ui.design.component.toTvLook

// M2 Home screen: Dock (S3b), Look (theme picker), "Use the TV's own home screen" with its preview,
// and the drift banner with "Send mine again" (D15). Each sheet keeps its body in an internal *Content
// composable, so the screenshot tests can draw it on SheetSurface.
@Composable
// 0.4.6: `onReport` opens the prefilled GitHub form from the not-on-this-TV screen, with its lead.
fun HomeScreen(
    state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, photos: PhotoPicker? = null,
    onReport: (String) -> Unit = {},
) {
    val notOnTv = state.notOnTv
    if (notOnTv != null) { NotOnTvScreen(state.tvName, notOnTv, onIntent, onBack, modifier, onReport); return }
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(stringResource(R.string.home_title), Modifier.padding(top = 20.dp))
        if (state.drift) {
            Banner(stringResource(R.string.home_drift_banner), BannerTone.WARNING, Modifier.padding(top = 16.dp),
                actionLabel = stringResource(R.string.home_drift_restore), onAction = { onIntent(HomeScreenIntent.RestoreMine) }, guardAction = true)
        }
        // 0.4.0: a dated look (Diwali) ended; the phone already counts the look before it as this
        // TV's, and one tap puts it back on the TV (the launcher only drops the festival skin).
        state.endedLook?.let { ended ->
            if (!state.drift) {
                Banner(msg(R.string.home_look_ended_banner, "picture" to ended), BannerTone.INFO, Modifier.padding(top = 16.dp),
                    actionLabel = stringResource(R.string.home_look_ended_restore), onAction = { onIntent(HomeScreenIntent.RestoreMine) }, guardAction = true)
            }
        }
        if (busy != null) {
            LiveLine(stringResource(R.string.home_applying))
            if (busy is Busy.Working) ProgressBar(busy.percent / 100f, Modifier.padding(top = 16.dp))
        }
        ListGroup(Modifier.padding(top = 20.dp)) {
            // The dock's apps by name, and how many more sit in the rows under it.
            val inDock = state.dock.take(state.columns).joinToString(", ") { it.name }
            val below = (state.dock.size - state.columns).coerceAtLeast(0)
            val dockLine = if (below > 0) msg(R.string.home_row_dock_rows, "names" to inDock, "count" to below) else inDock
            ListRow(stringResource(R.string.home_row_dock), subtitle = dockLine.ifEmpty { null },
                onClick = if (busy == null) ({ dockOpen = true }) else null)
            ListDivider()
            LookRow(state, onClick = if (busy == null) ({ onIntent(HomeScreenIntent.OpenLook) }) else null)
        }
        // look2: a failed look send keeps the draft (and a new photo); Open Look reopens it.
        if (state.lookFailed && busy == null) {
            Banner(stringResource(R.string.home_look_failed), BannerTone.WARNING, Modifier.padding(top = 12.dp),
                actionLabel = stringResource(R.string.home_look_failed_open), onAction = { onIntent(HomeScreenIntent.OpenLook) })
        }
        // Launcher 2.4: Launcher settings on the TV can override what the phone sent; the phone
        // cannot read them, so the rows above show what config.json says. Launcher 2.5 also edits the
        // dock on the TV (files/order.txt, kept while dock_apps stays the same), so the note says what
        // a new look and a new dock do to it.
        // Only for a launcher that has those settings (2.4 and later; RF-LAUNCHER-240 F16).
        if (state.tvSideSettings) Body(stringResource(R.string.home_tv_changes_note), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        state.updateTo?.let { version ->
            ListGroup(Modifier.padding(top = 12.dp)) {
                ListRow(stringResource(R.string.home_row_update), subtitle = msg(R.string.home_row_update_note, "version" to version),
                    onClick = if (busy == null) ({ onIntent(HomeScreenIntent.PreviewUpdate) }) else null)
            }
        }
        // Issue #275: on TCL Google TV these remote buttons only reach Google TV's own home screen.
        if (state.tclRemoteKeys) Banner(stringResource(R.string.home_tcl_remote_keys), BannerTone.INFO, Modifier.padding(top = 12.dp))
        if (state.stock != null && state.homeScreenOurs) {
            ListGroup(Modifier.padding(top = 12.dp)) {
                ListRow(stringResource(R.string.home_row_stock), subtitle = stringResource(R.string.home_row_stock_note),
                    onClick = if (busy == null) ({ onIntent(HomeScreenIntent.PreviewStock) }) else null)
            }
        }
    }
    if (dockOpen) {
        DockSheet(state.dock, state.apps, state.columns, onDone = { onIntent(HomeScreenIntent.SetDock(it)); dockOpen = false }, onDismiss = { dockOpen = false },
            waiting = state.waiting)
    }
    // The Look sheet, while a draft is open and not sending (the sheet closes on "Put it on the TV").
    if (state.look != null && busy == null && !state.lookFailed) LookSheet(state, onIntent, photos)
    state.stockPreview?.let { plan ->
        CytBottomSheet({ onIntent(HomeScreenIntent.CancelPreview) }) { StockPreviewContent(plan, onIntent) }
    }
    state.updatePreview?.let { plan ->
        CytBottomSheet({ onIntent(HomeScreenIntent.CancelPreview) }) { UpdatePreviewContent(plan, state.updateTo.orEmpty(), onIntent) }
    }
}

// 0.3.3 D2 (issue #3): the new home screen isn't on this TV, so no Dock or Look rows (a tap there
// was refused and said "We stopped"). Why, from what Setup recorded, and the next step when there
// is one: Setup again (the reason can change) or Help add this TV (a list is what is missing).
// Both buttons only navigate; nothing here writes to the TV.
// 0.4.6: without "Help add this TV" (a list is not what is missing), "Report a problem" opens the
// GitHub form with why, so a block that should not be there reaches us with the TV's report.
@Composable
internal fun NotOnTvScreen(tvName: String, n: NotOnTv, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, onReport: (String) -> Unit = {}) {
    val action = when {
        n.setupAgain -> R.string.home_run_setup_again to HomeScreenIntent.RunSetupAgain
        n.helpAddTv -> R.string.home_help_add_tv to HomeScreenIntent.HelpAddTv
        else -> null
    }
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { CytTopBar(onBack = onBack) },
        bottomBar = if (action == null) null else ({
            ActionColumn { PrimaryButton(stringResource(action.first), { onIntent(action.second) }, guardObscured = false) }
        }),
    ) {
        ScreenTitle(stringResource(R.string.home_title), Modifier.padding(top = 20.dp))
        val lead = msg(R.string.home_not_on_tv_lead, "tv" to tvName)
        Lead(lead, Modifier.padding(top = 12.dp))
        // beta3: an earlier Setup's block may not hold with this version (issues #701, #1273).
        val why = if (n.earlier) stringResource(R.string.home_not_on_tv_earlier) else when (n.reason) {
            HomeBlock.TV_REFUSES -> msg(R.string.home_not_on_tv_refuses, "brand" to (if (n.fireTv) stringResource(R.string.home_fire_tv_name) else n.brand))
            HomeBlock.OLD_ANDROID -> stringResource(R.string.home_not_on_tv_old_android)
            HomeBlock.UNKNOWN_HOME -> stringResource(R.string.home_not_on_tv_unknown_home)
            HomeBlock.WIZARD_WIFI -> stringResource(if (n.unlisted) R.string.home_not_on_tv_wizard_wifi_unlisted else R.string.home_not_on_tv_wizard_wifi)
            HomeBlock.WIZARD_WIFI_UNKNOWN -> stringResource(R.string.home_not_on_tv_wizard_wifi_unknown)
            HomeBlock.CASTING -> stringResource(R.string.home_not_on_tv_casting)
            HomeBlock.BOOT_LOOP -> stringResource(R.string.home_not_on_tv_boot_loop)
            HomeBlock.REMOTE_BUTTONS -> stringResource(R.string.home_not_on_tv_remote_buttons)
            HomeBlock.WIZARD_STAYS_ON -> stringResource(R.string.home_not_on_tv_wizard_stays_on)
            HomeBlock.HOME_CHANGED -> stringResource(R.string.home_not_on_tv_home_changed)
            HomeBlock.OTHER_HOME -> stringResource(R.string.home_not_on_tv_other_home)
            HomeBlock.NONE, null -> stringResource(R.string.home_not_on_tv_none)
        }
        Banner(why, BannerTone.INFO, Modifier.padding(top = 16.dp), icon = CytIcons.Info)
        val next = when {
            n.helpAddTv -> R.string.home_not_on_tv_next_list
            !n.setupAgain -> null
            n.earlier -> R.string.home_not_on_tv_next_check
            n.reason == HomeBlock.WIZARD_WIFI -> R.string.home_not_on_tv_next_wifi
            n.reason == HomeBlock.WIZARD_WIFI_UNKNOWN -> R.string.home_not_on_tv_next_check
            n.reason == HomeBlock.HOME_CHANGED -> R.string.home_not_on_tv_next_home_changed
            n.reason == HomeBlock.OTHER_HOME -> R.string.home_not_on_tv_next_other_home
            else -> R.string.home_not_on_tv_next_setup
        }
        next?.let { Body(stringResource(it), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) }
        if (!n.helpAddTv) TertiaryButton(stringResource(R.string.home_report), { onReport(lead) }, Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun ColumnScope.StockPreviewContent(plan: Plan, onIntent: (HomeScreenIntent) -> Unit) {
    SheetHeader(stringResource(R.string.home_stock_preview_title))
    Body(stringResource(R.string.home_stock_preview_body), Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.steps.map { it.narration }.distinct().forEach { CheckLine(it, state = CheckState.NONE) }
    }
    ActionColumn(Modifier.padding(top = 20.dp)) {
        PrimaryButton(stringResource(R.string.home_stock_confirm), { onIntent(HomeScreenIntent.UseStock) })
        TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelPreview) }, quiet = true)
    }
}

// D4: "Update the home screen?", the plan's steps as the list, like the stock preview. Update is a
// guarded primary (R4-W8): a tap through another app's overlay is dropped.
@Composable
internal fun ColumnScope.UpdatePreviewContent(plan: Plan, version: String, onIntent: (HomeScreenIntent) -> Unit) {
    SheetHeader(stringResource(R.string.home_update_preview_title))
    Body(msg(R.string.home_update_preview_body, "version" to version), Modifier.padding(top = 8.dp))
    Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.steps.map { it.narration }.filter { it.isNotBlank() }.distinct().forEach { CheckLine(it, state = CheckState.NONE) }
    }
    ActionColumn(Modifier.padding(top = 20.dp)) {
        PrimaryButton(stringResource(R.string.home_update_confirm), { onIntent(HomeScreenIntent.UpdateHomeScreen) })
        TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelPreview) }, quiet = true)
    }
}

// S3b on Manage: the dock and the rows under it (DockEditor), held and dragged to order, with up
// and down buttons for the UX 7.1 motor rule. Done applies the one ordered list to the TV.
// 0.4.0 `waiting`: saved apps beyond the three rows at this many apps in a row. The sheet does not
// show them and Done keeps them after its own list (HomeScreenMachine), so one line says where they are.
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, columns: Int, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit, waiting: Int = 0) {
    val state = remember(current, columns) { DockEditorState(current.map { it.toDockApp() }, columns) }
    val scroll = rememberScrollState()
    val undo = rememberUndoSnackbarState()
    CytBottomSheet(onDismiss) {
        // Three rows and the other apps can be taller than the screen; the sheet scrolls, also by
        // itself while an app is dragged near its edge.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(scroll)) { DockContent(state, apps, { onDone(state.apps.map { PackageName(it.key) }) }, scroll, undo, waiting) }
            UndoSnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun ColumnScope.DockContent(state: DockEditorState, apps: List<AppChoice>, onDone: () -> Unit, scroll: ScrollState? = null, undo: UndoSnackbarState? = null,
    waiting: Int = 0) {
    SheetHeader(stringResource(R.string.dock_title), subtitle = msg(R.string.dock_body, "count" to state.columns))
    DockEditor(state, remember(apps) { apps.map { it.toDockApp() } }, scroll = scroll, undo = undo,
        footnote = if (waiting > 0) msg(R.string.dock_waiting, "count" to waiting) else null)
    PrimaryButton(stringResource(R.string.action_done), onDone, Modifier.padding(top = 20.dp))
}

internal fun AppChoice.toDockApp() = DockApp(pkg.value, name)

// The Look row (W1): a 64 x 36 dp picture of what was last sent, then "Glass with Mist" and
// "5 apps in a row". A look saved before look2 that was never sent again keeps its 0.4.0 name.
@Composable
internal fun LookRow(state: HomeScreenState, onClick: (() -> Unit)?) {
    val sent = state.sent
    val style = state.sentStyle
    // W1: while a look send runs (the sheet closes on Put it on the TV), the row says so.
    val subtitle = if (state.lookSending && state.busy != null) {
        stringResource(R.string.home_row_look_sending)
    } else if (style == null) {
        state.themes.firstOrNull { it.id == state.themeId }?.name ?: stringResource(R.string.theme_default)
    } else {
        val name = styleName(style)
        val pic = state.sentPicture
        val first = when {
            pic == null || pic == LookEditorState.NONE -> msg(R.string.home_row_look_summary_none, "style" to name)
            pic == LookEditorState.MINE || pic == LookEditorState.NEW || pic == LookEditorState.EARLIER -> msg(R.string.home_row_look_summary_photo, "style" to name)
            else -> state.pictures.firstOrNull { it.id == pic }?.let { msg(R.string.home_row_look_summary_picture, "style" to name, "picture" to galleryPictureName(it.id, it.name)) }
                ?: msg(R.string.home_row_look_summary_none, "style" to name)
        }
        first + "\n" + msg(R.string.home_row_look_columns, "count" to (sent?.columns ?: state.columns))
    }
    ListRow(
        stringResource(R.string.home_row_look), subtitle = subtitle, onClick = onClick,
        leading = sent?.let { p ->
            { Box(Modifier.size(64.dp, 36.dp).clearAndSetSemantics {}) { TvPreview(p.toTvLook(lookPicture(p.picturePath, sample = 16)), "", Modifier.width(64.dp)) } }
        },
    )
}
