package tv.cleanyour.feature.launcher

import androidx.compose.runtime.Composable
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
import tv.cleanyour.core.flows.ButtonCapture
import tv.cleanyour.core.flows.RemoteButton
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.NotOnTv
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.DockEditorState

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
    updateTo: String? = null, buttons: List<RemoteButton>? = null, capture: ButtonCapture? = null,
) = HomeScreenState(
    tvName = "Mi TV 4A", dock = dock, apps = apps, themeId = themeId, themes = themes, drift = drift,
    stock = if (stock) Samples.stockLauncher else null, busy = busy, homeScreenOurs = ours, updateTo = updateTo,
    tvSideSettings = updateTo == null,   // a TV still on launcher 2.0 (an update offered) has no Launcher settings
    buttons = buttons.orEmpty(), buttonsSupported = buttons != null, capture = capture,   // Remote buttons: launcher 2.6 and later
)

// Remote buttons mapped on a Xiaomi remote: its YouTube button (KEY_PRESENTATION) and its TV button (KEY_WWW).
private val mapped = listOf(RemoteButton(425, apps[1]), RemoteButton(150, apps[4]))

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
        Shot("home_buttons_row") { HomeScreen(home(buttons = mapped), {}, {}) },
        // 0.3.3 D2 (issue #3): the new home screen isn't on the TV. Why, and the next step when there is one.
        Shot("home_not_on_tv_wizard_wifi") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_WIFI, "Xiaomi", setupAgain = true)), {}, {}) },
        Shot("home_not_on_tv_unlisted") { HomeScreen(notOnTv(NotOnTv(HomeBlock.WIZARD_STAYS_ON, "Hisense", unlisted = true, helpAddTv = true), "Hisense 32A4200G"), {}, {}) },
        Shot("home_not_on_tv_fire_tv") { HomeScreen(notOnTv(NotOnTv(HomeBlock.TV_REFUSES, "Insignia", fireTv = true), "Insignia Fire TV"), {}, {}) },
        Shot("home_not_on_tv_home_changed") { HomeScreen(notOnTv(NotOnTv(HomeBlock.HOME_CHANGED, "TCL", setupAgain = true), "TCL G07"), {}, {}) },

        // Look (theme picker), the dock sheet (S3b on Manage) and the stock home screen preview
        Shot("home_look_sheet") { SheetOver({ HomeScreen(home(), {}, {}) }) { LookContent(home(themeId = "aurora")) {} } },
        Shot("home_dock_sheet", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock.take(3)), apps, {}) } },
        Shot("home_dock_sheet_full", 1000.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock), apps, {}) } },
        Shot("home_dock_sheet_empty") { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(emptyList()), apps, {}) } },
        Shot("home_dock_sheet_rows", 1200.dp) { SheetOver({ HomeScreen(home(dock = rowsDock), {}, {}) }) { DockContent(dockState(rowsDock), apps, {}) } },
        Shot("home_dock_sheet_new_row", 1200.dp) { SheetOver({ HomeScreen(home(), {}, {}) }) { DockContent(dockState(defaultDock, addRow = true), apps, {}) } },
        Shot("home_stock_preview") { SheetOver({ HomeScreen(home(), {}, {}) }) { StockPreviewContent(stockPlan) {} } },
        Shot("home_update_preview") { SheetOver({ HomeScreen(home(updateTo = "2.4"), {}, {}) }) { UpdatePreviewContent(updatePlan, "2.4") {} } },

        // Remote buttons: none yet, two mapped, waiting for a press, choosing an app, and a shared button
        Shot("buttons_sheet_empty") { SheetOver({ HomeScreen(home(buttons = emptyList()), {}, {}) }) { RemoteButtonsContent(home(buttons = emptyList())) {} } },
        Shot("buttons_sheet_mapped") { SheetOver({ HomeScreen(home(buttons = mapped), {}, {}) }) { RemoteButtonsContent(home(buttons = mapped)) {} } },
        Shot("buttons_sheet_waiting") { SheetOver({ HomeScreen(home(buttons = mapped), {}, {}) }) { RemoteButtonsContent(home(buttons = mapped, capture = ButtonCapture.Waiting)) {} } },
        Shot("buttons_sheet_choose", 1000.dp) { SheetOver({ HomeScreen(home(buttons = emptyList()), {}, {}) }) { RemoteButtonsContent(home(buttons = emptyList(), capture = ButtonCapture.Pressed(216))) {} } },
        Shot("buttons_sheet_shared") { SheetOver({ HomeScreen(home(buttons = emptyList()), {}, {}) }) { RemoteButtonsContent(home(buttons = emptyList(), capture = ButtonCapture.Shared)) {} } },
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
