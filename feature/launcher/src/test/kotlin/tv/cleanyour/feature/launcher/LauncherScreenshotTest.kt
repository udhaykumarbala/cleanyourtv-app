package tv.cleanyour.feature.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tv.cleanyour.ui.design.theme.CytTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.Busy
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.NotOnTv
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.DockEditorState
import tv.cleanyour.ui.design.component.PhotoCropContent
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.LookPage
import tv.cleanyour.ui.design.component.SheetSurface
import tv.cleanyour.core.flows.DetailWord
import tv.cleanyour.core.flows.LookDetails
import tv.cleanyour.core.flows.LookNote
import tv.cleanyour.core.flows.LookNoteKind
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.TilesChoice
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.toTvLook

// Every section 4 state of :feature:launcher (PRODUCT-SPEC 4: M2 with the drift banner, the
// Look and dock sheets and the stock home screen preview), each in dark, light, 200% font and
// right to left, with ATF on.

private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)

private val defaultDock = listOf(
    app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
    app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("com.droidlogic.tvinput", "Live TV"),
)
private val apps = defaultDock + listOf(app("com.spotify.tv.android", "Spotify"), app("com.plexapp.android", "Plex"), app("org.videolan.vlc", "VLC"))
private val rowsDock = defaultDock + apps.drop(4).take(2)
// 0.4.0: a saved dock of 15 (the Console look's three rows of 7 hold it all), for the waiting lines.
private val longDock = apps + listOf(
    app("com.disney.disneyplus", "Disney+"), app("tv.twitch.android.app", "Twitch"), app("com.sonyliv", "Sony LIV"),
    app("com.graymatrix.did", "ZEE5"), app("com.jio.media.ondemand", "JioCinema"), app("in.startv.hotstar", "JioHotstar"),
    app("com.zattoo.player", "Zattoo"), app("com.apple.atve.androidtv.appletv", "Apple TV"),
)

// The dock sheet's state as DockSheet makes it (remembered, so it survives recomposition).
@Composable
private fun dockState(dock: List<AppChoice>, addRow: Boolean = false) =
    remember { DockEditorState(dock.map { it.toDockApp() }, 4).apply { if (addRow) addRow() } }
private val themes = listOf(
    ThemeOption("default", "Default", "A black background with four apps along the bottom."),
    ThemeOption("aurora", "Aurora", "A deep blue background with a soft glow behind the apps."),
    ThemeOption("six-columns", "Six columns", "Six apps across, so more fit on the screen."),
)

private fun home(
    themeId: String? = null, drift: Boolean = false, busy: Busy? = null, stock: Boolean = true, ours: Boolean = true, dock: List<AppChoice> = defaultDock,
    updateTo: String? = null, endedLook: String? = null,
) = HomeScreenState(
    tvName = "Mi TV 4A", dock = dock, apps = apps, themeId = themeId, themes = themes, drift = drift,
    stock = if (stock) Samples.stockLauncher else null, busy = busy, homeScreenOurs = ours, updateTo = updateTo,
    tvSideSettings = updateTo == null,   // a TV still on launcher 2.0 (an update offered) has no Launcher settings
    endedLook = endedLook,
)

// look2 Look sheet: the editor on the sheet surface, over the Home screen and its scrim, with a
// bounded height as the modal sheet gives it (the page scrolls between the pinned parts).
private fun lookSheet(draft: LookDraft, extra: (HomeScreenState) -> HomeScreenState = { it }) =
    extra(home(themeId = "aurora", dock = LookFixtures.dock4).copy(pictures = LookFixtures.pictureOptions, look = draft))

@Composable
private fun LookShot(state: HomeScreenState, page: LookPage = LookPage.MAIN, asking: Boolean = false) {
    val nav = remember { LookEditorNav().apply { open(page); this.asking = asking } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { HomeScreen(home(), {}, {}) }
        Box(Modifier.fillMaxSize().background(CytTheme.colors.scrim).clearAndSetSemantics {})
        SheetSurface(Modifier.align(Alignment.BottomCenter).padding(top = 48.dp)) {
            LookContent(state, {}, pictures = LookFixtures.pictureFiles, photos = PhotoPicker {}, nav = nav)
        }
    }
}

// The sheet laid out in full (no bound, so the page is not cut) inside a scroll at its end, to reach the
// bottom of a long page.
@Composable
private fun LookShotScrolled(state: HomeScreenState, page: LookPage) {
    val nav = remember { LookEditorNav().apply { open(page) } }
    Box(Modifier.fillMaxSize().background(CytTheme.colors.scrim)) {
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState(Int.MAX_VALUE))) {
            SheetSurface(Modifier.padding(top = 48.dp)) {
                LookContent(state, {}, pictures = LookFixtures.pictureFiles, photos = PhotoPicker {}, nav = nav)
            }
        }
    }
}

// 0.3.3 D2: a TV without the new home screen, as HomeScreenMachine loads it (no dock, no stock row).
private fun notOnTv(n: NotOnTv, tvName: String = "Mi TV 4A") =
    HomeScreenState(tvName = tvName, themes = themes, homeScreenOurs = false, notOnTv = n)

// D4 "Update the home screen": the plan's narration lines are the preview's list.
private val updatePlan = Samples.plan(
    listOf(Op.InstallStream(Samples.launcherArtifact().blob, Samples.zeroHash, Samples.launcherArtifact().pkg, 5, Samples.zeroHash),
        Op.StartActivity(Samples.launcherComponent, wait = true)),
).let { p ->
    val lines = listOf("Updating the home screen to version 2.4", "Starting the updated home screen")
    p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(narration = lines[i]) })
}

// "Use the TV's own home screen": the plan's narration lines are the preview's list.
private val stockPlan = Samples.plan(
    listOf(Op.Enable(Samples.stockLauncher.pkg), Op.SetHome(Samples.stockLauncher), Op.VerifyHome(Samples.stockLauncher)),
).let { p ->
    val lines = listOf("Switch the TV's own home screen back on", "Make it the home screen", "Check that Home opens it")
    p.copy(steps = p.steps.mapIndexed { i, s -> s.copy(narration = lines[i]) })
}

object LauncherShots {
    val all: List<Shot> = listOf(
        // M2 Home screen
        Shot("home_normal") { HomeScreen(home(), {}, {}) },
        Shot("home_aurora_theme") { HomeScreen(home(themeId = "aurora"), {}, {}) },
        Shot("home_drift_banner") { HomeScreen(home(drift = true), {}, {}) },
        Shot("home_busy_connecting") { HomeScreen(home(busy = Busy.Connecting), {}, {}) },
        Shot("home_busy_working") { HomeScreen(home(busy = Busy.Working(40, listOf("Updating the home screen on the TV..."))), {}, {}) },
        Shot("home_no_stock_launcher") { HomeScreen(home(stock = false), {}, {}) },
        Shot("home_stock_already_in_use") { HomeScreen(home(ours = false), {}, {}) },
        Shot("home_empty_dock") { HomeScreen(home(dock = emptyList()), {}, {}) },
        Shot("home_dock_rows") { HomeScreen(home(dock = rowsDock), {}, {}) },
        Shot("home_update_available") { HomeScreen(home(updateTo = "2.4"), {}, {}) },
        // Issue #275: TCL Google TV, the remote buttons the new home screen loses and the way back.
        Shot("home_tcl_remote_keys") { HomeScreen(home().copy(tvName = "TCL 55C755", tclRemoteKeys = true), {}, {}) },
        // 0.4.0: a dated look ended; the earlier one is the TV's again, one tap puts it back.
        Shot("home_look_ended") { HomeScreen(home(themeId = "aurora", endedLook = "Diwali"), {}, {}) },
        // 0.3.3 D2 (issue #3): the new home screen isn't on the TV. Why, and the next step when there is one.
        Shot("home_not_on_tv_wizard_wifi") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_WIFI, "Xiaomi", setupAgain = true)), {}, {}) },
        Shot("home_not_on_tv_unlisted") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_STAYS_ON, "Hisense", unlisted = true, helpAddTv = true), "Hisense 32A4200G"), {}, {}) },
        Shot("home_not_on_tv_fire_tv") { HomeScreen(notOnTv(NotOnTv(HomeBlock.TV_REFUSES, "Insignia", fireTv = true), "Insignia Fire TV"), {}, {}) },
        Shot("home_not_on_tv_home_changed") { HomeScreen(notOnTv(NotOnTv(HomeBlock.HOME_CHANGED, "TCL", setupAgain = true), "TCL G07"), {}, {}) },
        // beta3: another home screen app claims Home (issue #1119); a block an earlier Setup decided (issues #701, #1273).
        Shot("home_not_on_tv_other_home") { HomeScreen(notOnTv(NotOnTv(HomeBlock.OTHER_HOME, "Xiaomi", setupAgain = true), "Xiaomi MiTV4I"), {}, {}) },
        Shot("home_not_on_tv_earlier") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_STAYS_ON, "TCL", setupAgain = true, earlier = true), "TCL Percee TV"), {}, {}) },

        // look2 Home: the Look row with its mini of what was last sent; a failed send kept for Open Look.
        Shot("home_look_row_mini") { HomeScreen(home().copy(sent = LookFixtures.preview(StyleId.GLASS), sentStyle = StyleId.GLASS, sentPicture = "mist",
            pictures = LookFixtures.pictureOptions), {}, {}) },
        Shot("look_failed_kept") { HomeScreen(lookSheet(LookFixtures.draft(changed = true)) { it.copy(lookFailed = true, look = it.look) }, {}, {}) },
        // look2 Look sheet: the three pages, the states of spec W2 to W12.
        Shot("look_main", 1100.dp) { LookShot(lookSheet(LookFixtures.draft())) },
        Shot("look_main_changed", 1100.dp) {
            LookShot(lookSheet(LookFixtures.draft(StyleId.GLASS, columns = 7, wayBack = WayBack.UNDO, changed = true, tvChanged = true)))
        },
        Shot("look_main_earlier", 1100.dp) { LookShot(lookSheet(LookFixtures.draft(StyleId.CALM, "none", wayBack = WayBack.EARLIER))) },
        Shot("look_main_eight", 1100.dp) { LookShot(lookSheet(LookFixtures.draft(columns = 8, movement = Movement.QUICK, movementPicked = true))) },
        Shot("look_older_home_screen", 1100.dp) { LookShot(lookSheet(LookFixtures.draft(reads27 = false)) { it.copy(updateTo = "3.0") }) },
        Shot("look_photo_tint_line", 1100.dp) {
            LookShot(lookSheet(LookFixtures.draft(picture = LookEditorState.MINE)) { it.copy(ownPicture = LookFixtures.gallery.getValue("aurora").wallpaper.let { w ->
                w.copy(path = LookFixtures.resourcePath(w)!!) }) })
        },
        Shot("look_style_snackbar", 1100.dp) {
            LookShot(lookSheet(LookFixtures.draft(StyleId.SPOTLIGHT, changed = true, wayBack = WayBack.UNDO,
                note = LookNote(1, LookNoteKind.STYLE_CHANGED, StyleId.SPOTLIGHT, own = listOf(DetailWord.BUTTONS, DetailWord.GLOW), dropped = listOf(DetailWord.DOCK_BAND), action = true))))
        },
        Shot("look_leave", 1100.dp) { LookShot(lookSheet(LookFixtures.draft(changed = true, wayBack = WayBack.UNDO)), asking = true) },
        Shot("look_more", 1700.dp) { LookShot(lookSheet(LookFixtures.draft(details = LookDetails(glow = GlowChoice.FROM_TOP), resettable = 1, keyNames = true)), LookPage.MORE) },
        Shot("look_more_spotlight", 1700.dp) { LookShot(lookSheet(LookFixtures.draft(StyleId.SPOTLIGHT, "diwali")), LookPage.MORE) },
        Shot("look_more_all_apps", 1700.dp) { LookShot(lookSheet(LookFixtures.draft(StyleId.ALL_APPS, ramMb = 1024, movement = Movement.QUICK)), LookPage.MORE) },
        Shot("look_advanced", 1700.dp) {
            LookShot(lookSheet(LookFixtures.draft(details = LookDetails(band = BandChoice.SEE_THROUGH, seeThrough = 30), keyNames = true)), LookPage.ADVANCED)
        },
        Shot("look_advanced_low_memory", 1700.dp) { LookShot(lookSheet(LookFixtures.draft(lift = true, ramMb = 1536)), LookPage.ADVANCED) },
        // Home screen 3.0 (0.4.5): on a TV that reads 3.0, Every app in one row (on, with its note)
        // on the Look page; More options with the band's None, the greeting and date rows, Warn
        // when the internet is down (greyed until Green Wi-Fi) and the Tiles; Advanced's Highlight.
        // On a TV below 3.0 with the update offered, More options ends with the footer line.
        Shot("look_main_30", 1300.dp) { LookShot(lookSheet(LookFixtures.draft(reads30 = true, extras = LookExtras(oneRow = true)))) },
        Shot("look_more_30", 3400.dp) {
            LookShot(lookSheet(LookFixtures.draft(reads30 = true, extras = LookExtras(noBand = true, dateBelow = true, tiles = TilesChoice.ICONS))), LookPage.MORE)
        },
        Shot("look_more_30_names", 3600.dp) {
            LookShot(lookSheet(LookFixtures.draft(reads30 = true, keyNames = true, details = LookDetails(greenWifi = true),
                extras = LookExtras(hideGreeting = true, brightDate = true, wifiWarnings = true))), LookPage.MORE)
        },
        // The end of More options on a TV that reads 3.0, laid out in full and scrolled to it: the
        // Tiles section with Icons on cards chosen, Picture, and the Advanced link.
        Shot("look_more_30_tiles", 2400.dp) {
            LookShotScrolled(lookSheet(LookFixtures.draft(reads30 = true, extras = LookExtras(tiles = TilesChoice.ICONS))), LookPage.MORE)
        },
        Shot("look_advanced_30", 1900.dp) { LookShot(lookSheet(LookFixtures.draft(reads30 = true, extras = LookExtras(highlight = HighlightChoice.RIM))), LookPage.ADVANCED) },
        Shot("look_more_older_footer", 3000.dp) { LookShot(lookSheet(LookFixtures.draft()) { it.copy(updateTo = "3.0") }, LookPage.MORE) },
        // "Your photo": Cancel at the top, the style's glow over the photo, the Darken line.
        Shot("home_photo_crop", 900.dp) {
            Column(Modifier.fillMaxSize().background(CytTheme.colors.bg).padding(20.dp)) {
                PhotoCropContent(LookFixtures.pictureFiles.getValue(LookFixtures.resourcePath(LookFixtures.gallery.getValue("mist").wallpaper)!!),
                    LookFixtures.preview(StyleId.GLASS).toTvLook(null, crop = true), 30, null, { _, _, _, _ -> }, {})
            }
        },
        Shot("home_dock_sheet", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock.take(3)), apps, {}) } },
        Shot("home_dock_sheet_full", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock), apps, {}) } },
        Shot("home_dock_sheet_empty") { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(emptyList()), apps, {}) } },
        Shot("home_dock_sheet_rows", 1200.dp) { SheetOver({ HomeScreen(home(dock = rowsDock), {}, {}) }) { DockContent(dockState(rowsDock), apps, {}) } },
        Shot("home_dock_sheet_new_row", 1200.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock, addRow = true), apps, {}) } },
        // 0.4.0: 15 saved at 4 in a row: the sheet holds 12, and one line says 3 apps wait.
        Shot("home_dock_sheet_waiting", 1500.dp) {
            SheetOver({ HomeScreen(home(dock = longDock.take(12)), {}, {}) }) { DockContent(dockState(longDock.take(12)), longDock, {}, waiting = 3) }
        },
        Shot("home_stock_preview") { SheetOver({ HomeScreen(home(), {}, {}) }) { StockPreviewContent(stockPlan) {} } },
        Shot("home_update_preview") { SheetOver({ HomeScreen(home(updateTo = "2.4"), {}, {}) }) { UpdatePreviewContent(updatePlan, "2.4") {} } },
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi")
class LauncherScreenshotTest(private val shot: Shot, private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun shot() = compose.shoot(shot, variant)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = LauncherShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
