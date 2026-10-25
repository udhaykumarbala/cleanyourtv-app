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
import tv.cleanyour.core.flows.ButtonCapture
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
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.ThemeOption
import tv.cleanyour.ui.design.component.ThemePicker
import tv.cleanyour.ui.design.component.themeSwatch
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.Glow

// M2 Home screen: Dock (S3b), Look (theme picker), "Use the TV's own home screen" with its preview,
// and the drift banner with "Restore mine" (D15). Each sheet keeps its body in an internal *Content
// composable, so the screenshot tests can draw it on SheetSurface.
@Composable
fun HomeScreen(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val notOnTv = state.notOnTv
    if (notOnTv != null) { NotOnTvScreen(state.tvName, notOnTv, onIntent, onBack, modifier); return }
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    var buttonsOpen by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(stringResource(R.string.home_title), Modifier.padding(top = 20.dp))
        if (state.drift) {
            Banner(stringResource(R.string.home_drift_banner), BannerTone.WARNING, Modifier.padding(top = 16.dp),
                actionLabel = stringResource(R.string.home_drift_restore), onAction = { onIntent(HomeScreenIntent.RestoreMine) }, guardAction = true)
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
            val theme = state.themes.firstOrNull { it.id == state.themeId }?.name ?: stringResource(R.string.theme_default)
            ListRow(stringResource(R.string.home_row_look), subtitle = theme, onClick = if (busy == null) ({ lookOpen = true }) else null)
        }
        // Launcher 2.4: Launcher settings on the TV can override what the phone sent; the phone
        // cannot read them, so the rows above show what config.json says. Launcher 2.5 also edits the
        // dock on the TV (files/order.txt, kept while dock_apps stays the same), so the note says what
        // a new look and a new dock do to it.
        // Only for a launcher that has those settings (2.4 and later; RF-LAUNCHER-240 F16).
        if (state.tvSideSettings) Body(stringResource(R.string.home_tv_changes_note), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        // Remote buttons: only where the TV's launcher reads "scan:" app_keys (2.6 and later).
        if (state.buttonsSupported) {
            ListGroup(Modifier.padding(top = 12.dp)) {
                val line = if (state.buttons.isEmpty()) stringResource(R.string.home_row_buttons_none)
                    else msg(R.string.home_row_buttons_count, "count" to state.buttons.size)
                ListRow(stringResource(R.string.home_row_buttons), subtitle = line, onClick = if (busy == null) ({ buttonsOpen = true }) else null)
            }
        }
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
    if (dockOpen) DockSheet(state.dock, state.apps, state.columns, onDone = { onIntent(HomeScreenIntent.SetDock(it)); dockOpen = false }, onDismiss = { dockOpen = false })
    if (buttonsOpen) CytBottomSheet({ onIntent(HomeScreenIntent.CancelCapture); buttonsOpen = false }) {
        Column(Modifier.verticalScroll(rememberScrollState())) { RemoteButtonsContent(state, onIntent) }
    }
    if (lookOpen) CytBottomSheet({ lookOpen = false }) {
        LookContent(state) { onIntent(HomeScreenIntent.SetTheme(it)); lookOpen = false }
    }
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
@Composable
private fun NotOnTvScreen(tvName: String, n: NotOnTv, onIntent: (HomeScreenIntent) -> Unit, onBack: () -> Unit, modifier: Modifier) {
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
        Lead(msg(R.string.home_not_on_tv_lead, "tv" to tvName), Modifier.padding(top = 12.dp))
        val why = when (n.reason) {
            HomeBlock.TV_REFUSES -> msg(R.string.home_not_on_tv_refuses, "brand" to (if (n.fireTv) stringResource(R.string.home_fire_tv_name) else n.brand))
            HomeBlock.OLD_ANDROID -> stringResource(R.string.home_not_on_tv_old_android)
            HomeBlock.UNKNOWN_HOME -> stringResource(R.string.home_not_on_tv_unknown_home)
            HomeBlock.WIZARD_WIFI -> stringResource(if (n.unlisted) R.string.home_not_on_tv_wizard_wifi_unlisted else R.string.home_not_on_tv_wizard_wifi)
            HomeBlock.WIZARD_WIFI_UNKNOWN -> stringResource(R.string.home_not_on_tv_wizard_wifi_unknown)
            HomeBlock.CASTING -> stringResource(R.string.home_not_on_tv_casting)
            HomeBlock.BOOT_LOOP -> stringResource(R.string.home_not_on_tv_boot_loop)
            HomeBlock.WIZARD_STAYS_ON -> stringResource(R.string.home_not_on_tv_wizard_stays_on)
            HomeBlock.HOME_CHANGED -> stringResource(R.string.home_not_on_tv_home_changed)
            HomeBlock.NONE, null -> stringResource(R.string.home_not_on_tv_none)
        }
        Banner(why, BannerTone.INFO, Modifier.padding(top = 16.dp), icon = CytIcons.Info)
        val next = when {
            n.helpAddTv -> R.string.home_not_on_tv_next_list
            !n.setupAgain -> null
            n.reason == HomeBlock.WIZARD_WIFI -> R.string.home_not_on_tv_next_wifi
            n.reason == HomeBlock.WIZARD_WIFI_UNKNOWN -> R.string.home_not_on_tv_next_check
            n.reason == HomeBlock.HOME_CHANGED -> R.string.home_not_on_tv_next_home_changed
            else -> R.string.home_not_on_tv_next_setup
        }
        next?.let { Body(stringResource(it), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) }
    }
}

// The three launcher themes with their one-line descriptions; the preview tile colours come from
// :ui:design (themeSwatch), shared with the wizard's picker.
private val themeDescriptions = mapOf(
    "default" to R.string.theme_default_desc,
    "aurora" to R.string.theme_aurora_desc,
    "six-columns" to R.string.theme_six_columns_desc,
)

// A tap here applies the look to the TV, so the rows are guarded like a primary (R4-W8).
@Composable
internal fun ColumnScope.LookContent(state: HomeScreenState, onSelect: (String) -> Unit) {
    SheetHeader(stringResource(R.string.theme_title))
    val options = state.themes.map { t ->
        ThemeOption(t.id, t.name, themeDescriptions[t.id]?.let { stringResource(it) } ?: t.description, themeSwatch(t.id))
    }
    ThemePicker(options, state.themeId ?: "default", { onSelect(it.id) }, Modifier.padding(top = 16.dp), guardObscured = true)
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

// Remote buttons: the buttons mapped for this TV, each with Remove, then "Add a button": the TV listens for
// one press, and a button that can open an app is offered the TV's apps. Choosing one sends the look with the
// button (an ApplyTheme plan, with its own preview and gate), so nothing reaches the TV before that.
@Composable
internal fun ColumnScope.RemoteButtonsContent(state: HomeScreenState, onIntent: (HomeScreenIntent) -> Unit) {
    SheetHeader(stringResource(R.string.buttons_title))
    Body(stringResource(R.string.buttons_body), Modifier.padding(top = 8.dp))
    val idle = state.busy == null
    if (state.buttons.isNotEmpty()) {
        ListGroup(Modifier.padding(top = 16.dp)) {
            state.buttons.forEachIndexed { i, b ->
                if (i > 0) ListDivider()
                ListRow(msg(R.string.buttons_name, "code" to b.scancode.toString()), subtitle = b.app.name, trailing = {
                    TertiaryButton(stringResource(R.string.buttons_remove), { onIntent(HomeScreenIntent.UnmapButton(b.scancode)) }, enabled = idle)
                })
            }
        }
    }
    when (val capture = state.capture) {
        null -> {
            Body(stringResource(R.string.buttons_helper_note), Modifier.padding(top = 16.dp))
            PrimaryButton(stringResource(R.string.buttons_add), { onIntent(HomeScreenIntent.CaptureButton) }, Modifier.padding(top = 20.dp), enabled = idle)
        }
        ButtonCapture.Waiting -> {
            LiveLine(stringResource(R.string.buttons_waiting))
            TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelCapture) }, Modifier.padding(top = 12.dp), quiet = true)
        }
        is ButtonCapture.Pressed -> {
            Body(msg(R.string.buttons_choose, "code" to capture.scancode.toString()), Modifier.padding(top = 16.dp), color = tv.cleanyour.ui.design.theme.CytTheme.colors.textPrimary)
            ListGroup(Modifier.padding(top = 12.dp)) {
                state.apps.forEachIndexed { i, app ->
                    if (i > 0) ListDivider()
                    ListRow(app.name, onClick = if (idle) ({ onIntent(HomeScreenIntent.MapButton(capture.scancode, app.pkg)) }) else null)
                }
            }
            TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelCapture) }, Modifier.padding(top = 12.dp), quiet = true)
        }
        else -> {
            val why = when (capture) {
                ButtonCapture.Shared -> R.string.buttons_shared
                ButtonCapture.Needed -> R.string.buttons_needed
                ButtonCapture.NothingPressed -> R.string.buttons_nothing
                else -> R.string.buttons_failed
            }
            Banner(stringResource(why), BannerTone.WARNING, Modifier.padding(top = 16.dp))
            ActionColumn(Modifier.padding(top = 20.dp)) {
                PrimaryButton(stringResource(R.string.buttons_try_again), { onIntent(HomeScreenIntent.CaptureButton) }, enabled = idle)
                TertiaryButton(stringResource(R.string.action_cancel), { onIntent(HomeScreenIntent.CancelCapture) }, quiet = true)
            }
        }
    }
}

// S3b on Manage: the dock and the rows under it (DockEditor), held and dragged to order, with up
// and down buttons for the UX 7.1 motor rule. Done applies the one ordered list to the TV.
@Composable
fun DockSheet(current: List<AppChoice>, apps: List<AppChoice>, columns: Int, onDone: (List<PackageName>) -> Unit, onDismiss: () -> Unit) {
    val state = remember(current, columns) { DockEditorState(current.map { it.toDockApp() }, columns) }
    val scroll = rememberScrollState()
    val undo = rememberUndoSnackbarState()
    CytBottomSheet(onDismiss) {
        // Three rows and the other apps can be taller than the screen; the sheet scrolls, also by
        // itself while an app is dragged near its edge.
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(scroll)) { DockContent(state, apps, { onDone(state.apps.map { PackageName(it.key) }) }, scroll, undo) }
            UndoSnackbarHost(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun ColumnScope.DockContent(state: DockEditorState, apps: List<AppChoice>, onDone: () -> Unit, scroll: ScrollState? = null, undo: UndoSnackbarState? = null) {
    SheetHeader(stringResource(R.string.dock_title), subtitle = msg(R.string.dock_body, "count" to state.columns))
    DockEditor(state, remember(apps) { apps.map { it.toDockApp() } }, scroll = scroll, undo = undo)
    PrimaryButton(stringResource(R.string.action_done), onDone, Modifier.padding(top = 20.dp))
}

internal fun AppChoice.toDockApp() = DockApp(pkg.value, name)
