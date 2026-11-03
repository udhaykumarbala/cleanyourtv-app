package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.MakeItYoursIntent
import tv.cleanyour.core.flows.MakeItYoursState
import tv.cleanyour.core.flows.SaverBehind
import tv.cleanyour.core.flows.MakeItYoursEdits
import tv.cleanyour.core.flows.StartOn
import tv.cleanyour.core.flows.TopButton
import tv.cleanyour.core.flows.withWidgets
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.ChoiceList
import tv.cleanyour.ui.design.component.ChoiceOption
import tv.cleanyour.ui.design.component.CytTextField
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SegmentOption
import tv.cleanyour.ui.design.component.SegmentedChoice
import tv.cleanyour.ui.design.component.SwitchRow
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TvPreview
import tv.cleanyour.ui.design.component.TvThumbnail
import tv.cleanyour.ui.design.component.galleryPictureName
import tv.cleanyour.ui.design.component.lookPicture
import tv.cleanyour.ui.design.component.rememberPng
import tv.cleanyour.ui.design.component.toTvLook
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow

// 0.5.0 Make it yours (D9, D17): the hub of the parts of the home screen, in its order: Look, Wallpaper, Apps,
// Widgets, Inputs and Live TV, Remote buttons, Screensaver. Look and Wallpaper open the Look sheet (the look and
// its picture are one push, as before); Apps' dock and the Live TV tile open the Dock sheet. The other pages edit
// the TV's settings document (MakeItYoursMachine): their changes collect in one draft, counted in the bar at the
// bottom, and go to the TV with one "Put it on the TV". The page itself (which page shows) is the app's, so Back
// returns to the hub (AppRoot).
enum class HubPage(val key: String) {
    HUB("hub"), APPS("apps"), WIDGETS("widgets"), INPUTS("inputs"), BUTTONS("buttons"), SAVER("saver");

    companion object { fun of(key: String?): HubPage = entries.firstOrNull { it.key == key } ?: HUB }
}

@Composable
fun MakeItYoursScreen(
    home: HomeScreenState,
    settings: MakeItYoursState,
    page: HubPage,
    onPage: (HubPage) -> Unit,
    onHome: (HomeScreenIntent) -> Unit,
    onSettings: (MakeItYoursIntent) -> Unit,
    onBack: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenHomePage: () -> Unit,
    modifier: Modifier = Modifier,
    photos: PhotoPicker? = null,
    onReport: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    // Nothing to make yours while the new home screen is not on this TV: the Home screen page's reasons.
    home.notOnTv?.let { NotOnTvScreen(home.tvName, it, onHome, onBack, modifier, onReport, onOpenUrl); return }
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    val busy = home.busy ?: settings.busy
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { CytTopBar(onBack = { if (page == HubPage.HUB) onBack() else onPage(HubPage.HUB) }) },
        bottomBar = if (settings.changes > 0 || settings.busy != null) ({ DraftBar(settings, onSettings) }) else null,
    ) {
        when (page) {
            HubPage.HUB -> HubContent(home, settings, onPage, onHome, busy, onOpenHomePage)
            HubPage.APPS -> AppsContent(settings, onSettings, home, onDock = { dockOpen = true })
            HubPage.WIDGETS -> {
                ScreenTitle(stringResource(R.string.miy_row_widgets), Modifier.padding(top = 20.dp))
                NeedsUpdate(settings)
                WidgetsContent(settings, onSettings, onOpenCards, backdrop = lookPicture(home.sent?.picturePath, sample = 16),
                    backdropArgb = home.sent?.backgroundArgb ?: 0xFF101014.toInt())
            }
            HubPage.INPUTS -> InputsContent(settings, onSettings, home, onDock = { dockOpen = true })
            HubPage.BUTTONS -> ButtonsContent(home, onHome)
            HubPage.SAVER -> SaverContent(settings, onSettings)
        }
    }
    if (dockOpen) {
        DockSheet(home.dock, home.apps, home.columns, onDone = { onHome(HomeScreenIntent.SetDock(it)); dockOpen = false }, onDismiss = { dockOpen = false },
            waiting = home.waiting)
    }
    if (home.look != null && home.busy == null && !home.lookFailed) LookSheet(home, onHome, photos)
}

// One bar for every page: what is waiting, and the one guarded write. While it runs, the progress.
@Composable
private fun ColumnScope.DraftBar(settings: MakeItYoursState, onSettings: (MakeItYoursIntent) -> Unit) {
    val busy = settings.busy
    ActionColumn(Modifier.padding(top = 8.dp)) {
        if (busy != null) {
            LiveLine(stringResource(R.string.home_applying), Modifier.padding(bottom = 8.dp))
            if (busy is Busy.Working) ProgressBar(busy.percent / 100f, Modifier.padding(bottom = 8.dp))
        } else {
            Text(msg(R.string.miy_changes, "count" to settings.changes), Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium)
            PrimaryButton(stringResource(R.string.miy_put), { onSettings(MakeItYoursIntent.Send) }, enabled = settings.canSend)
            TertiaryButton(stringResource(R.string.miy_discard), { onSettings(MakeItYoursIntent.Discard) }, quiet = true)
        }
    }
}

@Composable
private fun ColumnScope.HubContent(home: HomeScreenState, settings: MakeItYoursState, onPage: (HubPage) -> Unit,
    onHome: (HomeScreenIntent) -> Unit, busy: Busy?, onOpenHomePage: () -> Unit) {
    ScreenTitle(stringResource(R.string.miy_title), Modifier.padding(top = 20.dp))
    Body(home.tvName, Modifier.padding(top = 4.dp, start = 4.dp))
    if (home.drift) {
        Banner(stringResource(R.string.home_drift_banner), BannerTone.WARNING, Modifier.padding(top = 16.dp),
            actionLabel = stringResource(R.string.home_drift_restore), onAction = { onHome(HomeScreenIntent.RestoreMine) }, guardAction = true)
    }
    if (home.busy != null) {
        LiveLine(stringResource(R.string.home_applying))
        if (home.busy is Busy.Working) ProgressBar((home.busy as Busy.Working).percent / 100f, Modifier.padding(top = 16.dp))
    }
    if (settings.failed) Banner(stringResource(R.string.miy_failed), BannerTone.WARNING, Modifier.padding(top = 16.dp))
    // After a send: what the TV shows now (the look's or the settings' screenshot), else the preview with the draft.
    val shot = settings.shot ?: home.lookShot
    if (shot != null && busy == null) {
        ListGroup(Modifier.padding(top = 16.dp)) {
            ListRow(stringResource(R.string.home_tv_now_title))
            TvThumbnail(rememberPng(shot), stringResource(R.string.home_tv_now_cd), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), label = null)
        }
    } else home.sent?.let { sent ->
        val p = sent.withWidgets(settings.draft.widgets)
        Box(Modifier.padding(top = 16.dp)) {
            TvPreview(p.toTvLook(lookPicture(p.picturePath, sample = 8)), stringResource(R.string.miy_preview_cd), Modifier.fillMaxWidth())
        }
        Body(stringResource(R.string.miy_preview_caption), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
    val idle = busy == null
    ListGroup(Modifier.padding(top = 20.dp)) {
        LookRow(home, onClick = if (idle) ({ onHome(HomeScreenIntent.OpenLook) }) else null)
        ListDivider()
        ListRow(stringResource(R.string.miy_row_wallpaper), subtitle = wallpaperLine(home), onClick = if (idle) ({ onHome(HomeScreenIntent.OpenLook) }) else null)
        ListDivider()
        ListRow(stringResource(R.string.miy_row_apps), subtitle = appsLine(home), onClick = { onPage(HubPage.APPS) })
        ListDivider()
        ListRow(stringResource(R.string.miy_row_widgets), subtitle = widgetsLine(settings), onClick = { onPage(HubPage.WIDGETS) })
        ListDivider()
        ListRow(stringResource(R.string.miy_row_inputs), subtitle = startLine(settings), onClick = { onPage(HubPage.INPUTS) })
        // The Home button helper is the remote's one setting in 0.5.0; without it the page would be empty.
        home.helper?.let {
            ListDivider()
            ListRow(stringResource(R.string.miy_row_buttons), subtitle = stringResource(R.string.miy_row_buttons_helper), onClick = { onPage(HubPage.BUTTONS) })
        }
        ListDivider()
        ListRow(stringResource(R.string.miy_row_saver), subtitle = saverLine(settings), onClick = { onPage(HubPage.SAVER) })
    }
    if (home.lookFailed && home.busy == null) {
        Banner(stringResource(R.string.home_look_failed), BannerTone.WARNING, Modifier.padding(top = 12.dp),
            actionLabel = stringResource(R.string.home_look_failed_open), onAction = { onHome(HomeScreenIntent.OpenLook) })
    }
    if (home.tvSideSettings) Body(stringResource(R.string.home_tv_changes_note), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    // Which home screen is in charge, and its update: the Home screen page.
    ListGroup(Modifier.padding(top = 20.dp)) {
        ListRow(stringResource(R.string.miy_row_home),
            subtitle = home.updateTo?.let { msg(R.string.home_row_update_note, "version" to it) } ?: stringResource(R.string.miy_row_home_note),
            onClick = onOpenHomePage)
    }
}

@Composable
private fun wallpaperLine(home: HomeScreenState): String {
    val pic = home.sentPicture
    return when {
        home.sentStyle == null -> stringResource(R.string.miy_row_wallpaper_none).takeIf { pic == LookEditorState.NONE } ?: ""
        pic == null || pic == LookEditorState.NONE -> stringResource(R.string.miy_row_wallpaper_none)
        pic == LookEditorState.MINE || pic == LookEditorState.NEW || pic == LookEditorState.EARLIER -> stringResource(R.string.miy_row_wallpaper_photo)
        else -> home.pictures.firstOrNull { it.id == pic }?.let { galleryPictureName(it.id, it.name) } ?: stringResource(R.string.miy_row_wallpaper_none)
    }
}

@Composable
private fun appsLine(home: HomeScreenState): String? {
    val names = home.dock.take(home.columns).map { it.name }
    if (names.isEmpty()) return null
    return if (names.size > 3) msg(R.string.miy_row_apps_dock_more, "names" to names.take(3).joinToString(", "), "count" to names.size - 3)
        else msg(R.string.miy_row_apps_dock, "names" to names.joinToString(", "))
}

@Composable
private fun widgetsLine(s: MakeItYoursState): String {
    val w = s.shown.widgets
    val parts = mutableListOf<String>()
    val name = w.greetingName.orEmpty()
    parts += if (w.showGreeting == true && name.isNotEmpty()) msg(R.string.miy_row_widgets_greets, "name" to name) else stringResource(R.string.miy_row_widgets_clock)
    if (s.secondDate != tv.cleanyour.core.flows.SecondDate.NONE) parts += stringResource(R.string.miy_row_widgets_second)
    if (w.showStats == true) parts += stringResource(R.string.miy_row_widgets_stats)
    parts += if (s.buttonsLook == tv.cleanyour.core.flows.ButtonsLook.MENU) stringResource(R.string.miy_row_widgets_menu)
        else msg(R.string.miy_row_widgets_buttons, "count" to TopButton.entries.count { s.shows(it) && it != TopButton.APPS } + w.topBarApps.orEmpty().size)
    return parts.joinToString(", ")
}

@Composable
private fun startLine(s: MakeItYoursState): String = stringResource(when (s.startOn) {
    StartOn.HOME -> R.string.miy_start_home
    StartOn.LIVE_TV -> R.string.miy_start_live
    StartOn.APP -> R.string.miy_start_app
})

@Composable
private fun saverLine(s: MakeItYoursState): String =
    if (s.saverOn) msg(R.string.miy_row_saver_clock, "count" to (s.shown.screensaver.idleMinutes ?: 0)) else stringResource(R.string.miy_row_saver_tv)

// A choice this TV's home screen does not read is left out of its page; one line says why.
@Composable
private fun NeedsUpdate(s: MakeItYoursState) {
    if (!s.reads("clock_format") || !s.reads("top_bar_apps")) Banner(stringResource(R.string.miy_needs_update), BannerTone.INFO, Modifier.padding(top = 12.dp))
}

// Apps: the dock, moving apps with the remote, the App library, hidden apps and tile names.
@Composable
private fun ColumnScope.AppsContent(s: MakeItYoursState, onSettings: (MakeItYoursIntent) -> Unit, home: HomeScreenState, onDock: () -> Unit) {
    ScreenTitle(stringResource(R.string.miy_row_apps), Modifier.padding(top = 20.dp))
    Body(stringResource(R.string.miy_apps_sub), Modifier.padding(top = 4.dp, start = 4.dp))
    ListGroup(Modifier.padding(top = 16.dp)) {
        ListRow(stringResource(R.string.home_row_dock), subtitle = home.dock.take(home.columns).joinToString(", ") { it.name }.ifEmpty { null },
            onClick = if (home.busy == null && s.busy == null) onDock else null)
    }
    Body(stringResource(R.string.miy_apps_dock_hint), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    val a = s.shown.apps
    if (s.reads("tile_menu") || s.reads("app_library")) {
        SectionLabel(stringResource(R.string.miy_apps_on_tv))
        ListGroup {
            if (s.reads("tile_menu")) SwitchRow(stringResource(R.string.miy_apps_move), a.tileMenu == true, { onSettings(MakeItYoursIntent.MoveWithRemote(it)) },
                subtitle = stringResource(R.string.miy_apps_move_desc))
            if (s.reads("tile_menu") && s.reads("app_library")) ListDivider()
            if (s.reads("app_library")) SwitchRow(stringResource(R.string.miy_apps_library), a.appLibrary == true, { onSettings(MakeItYoursIntent.AppLibrary(it)) },
                subtitle = stringResource(R.string.miy_apps_library_desc))
        }
    }
    val docked = home.dock.map { it.pkg }.toSet()
    val others = s.apps.filter { it.pkg !in docked }
    if (others.isNotEmpty()) {
        SectionLabel(stringResource(R.string.miy_apps_hidden))
        Body(stringResource(R.string.miy_apps_hidden_hint), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
        val hidden = a.hiddenApps.orEmpty().toSet()
        ListGroup {
            others.forEachIndexed { i, app ->
                if (i > 0) ListDivider()
                SwitchRow(app.name, app.pkg in hidden, { onSettings(MakeItYoursIntent.Hidden(app.pkg, it)) })
            }
        }
    }
    if (s.reads("app_labels") && home.dock.isNotEmpty()) {
        SectionLabel(stringResource(R.string.miy_apps_names))
        Body(stringResource(R.string.miy_apps_names_hint), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
        val labels = a.appLabels.orEmpty()
        Column {
            home.dock.take(home.columns).forEach { app ->
                CytTextField(labels[app.pkg].orEmpty(), { onSettings(MakeItYoursIntent.Label(app.pkg, it.take(MakeItYoursEdits.MAX_LABEL))) },
                    msg(R.string.miy_apps_name_label, "name" to app.name), Modifier.padding(top = 8.dp))
            }
        }
    }
}

// Inputs and Live TV: when the TV starts, and where the Live TV tile lives (the dock). Input names wait for 0.5.1.
@Composable
private fun ColumnScope.InputsContent(s: MakeItYoursState, onSettings: (MakeItYoursIntent) -> Unit, home: HomeScreenState, onDock: () -> Unit) {
    ScreenTitle(stringResource(R.string.miy_row_inputs), Modifier.padding(top = 20.dp))
    if (s.reads("boot_to_live_tv")) {
        SectionLabel(stringResource(R.string.miy_inputs_start))
        val live = s.liveTv
        val options = listOfNotNull(
            ChoiceOption(StartOn.HOME.name, stringResource(R.string.miy_inputs_home), stringResource(R.string.miy_inputs_home_desc), icon = null),
            live?.let { ChoiceOption(StartOn.LIVE_TV.name, stringResource(R.string.miy_inputs_live), msg(R.string.miy_inputs_live_desc, "app" to it), icon = null) },
            if (s.reads("open_on_start_choice")) ChoiceOption(StartOn.APP.name, stringResource(R.string.miy_inputs_app), stringResource(R.string.miy_inputs_app_desc), icon = null) else null,
        )
        ChoiceList(options, s.startOn.name, { o -> onSettings(MakeItYoursIntent.Start(StartOn.valueOf(o.key))) })
        Body(stringResource(R.string.miy_inputs_start_hint), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        if (live == null) Body(stringResource(R.string.miy_inputs_no_live), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
    SectionLabel(stringResource(R.string.miy_inputs_elsewhere))
    val inDock = s.liveTv != null && home.dock.any { it.name == s.liveTv }
    ListGroup {
        ListRow(stringResource(R.string.miy_inputs_live_tile),
            subtitle = stringResource(if (inDock) R.string.miy_inputs_live_tile_in else R.string.miy_inputs_live_tile_out),
            onClick = if (home.busy == null && s.busy == null) onDock else null)
    }
    Body(stringResource(R.string.miy_inputs_input_key), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
}

// Remote buttons: in 0.5.0 the Home button helper (RFC-HELPER-1) and the notes about this TV's remote.
@Composable
private fun ColumnScope.ButtonsContent(home: HomeScreenState, onHome: (HomeScreenIntent) -> Unit) {
    ScreenTitle(stringResource(R.string.miy_row_buttons), Modifier.padding(top = 20.dp))
    SectionLabel(stringResource(R.string.miy_buttons_home))
    val helper = home.helper
    if (helper != null) HomeHelperCard(helper, enabled = home.busy == null, onHome)
    else Body(stringResource(R.string.miy_buttons_none), Modifier.padding(start = 4.dp, end = 4.dp))
    if (home.tclRemoteKeys && helper?.canSwitchOff != true) Banner(stringResource(R.string.home_tcl_remote_keys), BannerTone.INFO, Modifier.padding(top = 12.dp))
    if (home.googleTvSettingsKey && helper?.canSwitchOff != true) Banner(stringResource(R.string.home_google_tv_settings_key), BannerTone.INFO, Modifier.padding(top = 12.dp))
}

// Screensaver: the TV's own, or the home screen's clock (home_idle_clock_minutes), only on Home (D17).
@Composable
private fun ColumnScope.SaverContent(s: MakeItYoursState, onSettings: (MakeItYoursIntent) -> Unit) {
    ScreenTitle(stringResource(R.string.miy_row_saver), Modifier.padding(top = 20.dp))
    Body(stringResource(R.string.miy_saver_sub), Modifier.padding(top = 4.dp, start = 4.dp))
    if (!s.reads("home_idle_clock_minutes")) { NeedsUpdate(s); return }
    ChoiceList(
        listOf(
            ChoiceOption("tv", stringResource(R.string.miy_saver_tv), stringResource(R.string.miy_saver_tv_desc), icon = null),
            ChoiceOption("clock", stringResource(R.string.miy_saver_clock), stringResource(R.string.miy_saver_clock_desc), icon = null),
        ),
        if (s.saverOn) "clock" else "tv", { o -> onSettings(MakeItYoursIntent.Saver(o.key == "clock")) }, Modifier.padding(top = 16.dp),
    )
    if (!s.saverOn) return
    SectionLabel(stringResource(R.string.miy_saver_after))
    val minutes = s.shown.screensaver.idleMinutes ?: MakeItYoursEdits.SAVER_DEFAULT_MINUTES
    val options = MakeItYoursEdits.SAVER_MINUTES.map { m ->
        SegmentOption(m.toString(), msg(R.string.miy_saver_minutes, "count" to m), msg(R.string.miy_saver_minutes_cd, "count" to m))
    }
    SegmentedChoice(options, minutes.toString().takeIf { minutes in MakeItYoursEdits.SAVER_MINUTES }, { o -> onSettings(MakeItYoursIntent.SaverAfter(o.key.toInt())) },
        groupLabel = stringResource(R.string.miy_saver_after))
    if (s.reads("clock_wallpaper")) {
        SectionLabel(stringResource(R.string.miy_saver_behind))
        val behind = s.behind
        ChoiceList(
            listOf(
                ChoiceOption(SaverBehind.PICTURE.name, stringResource(R.string.miy_saver_picture), icon = null),
                ChoiceOption(SaverBehind.BLACK.name, stringResource(R.string.miy_saver_black), icon = null),
            ),
            behind.name.takeIf { behind != SaverBehind.GLOW }, { o -> onSettings(MakeItYoursIntent.Behind(SaverBehind.valueOf(o.key))) },
        )
        if (behind == SaverBehind.GLOW) Body(stringResource(R.string.miy_saver_glow_now), Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
    Body(stringResource(R.string.miy_saver_where), Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
}
