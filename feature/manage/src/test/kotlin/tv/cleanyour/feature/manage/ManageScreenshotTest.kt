package tv.cleanyour.feature.manage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.flows.AppConfirm
import tv.cleanyour.core.flows.AppPrefsValue
import tv.cleanyour.core.flows.AppSettingsState
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.LeftoversState
import tv.cleanyour.core.flows.ManageState
import tv.cleanyour.core.flows.ReportKind
import tv.cleanyour.core.flows.ReportState
import tv.cleanyour.core.flows.OffGroup
import tv.cleanyour.core.flows.OffRow
import tv.cleanyour.core.flows.OpenWrite
import tv.cleanyour.core.flows.OpenWriteAction
import tv.cleanyour.core.flows.SwitchedOffState
import tv.cleanyour.core.flows.TvCard
import tv.cleanyour.core.flows.TvSettingsState
import tv.cleanyour.core.flows.UpdateCheck
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.Samples

// Every section 4 state of :feature:manage (PRODUCT-SPEC 4: M1, M1a, the open-run card, M4, M5,
// M7, M8 and their sheets), each in dark, light, 200% font and right to left, with ATF on.

private fun fakeShot(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF05060A.toInt())
    val paint = Paint().apply { color = 0xFF2A2F45.toInt() }
    repeat(4) { i -> canvas.drawRoundRect(RectF(70f + i * 130f, 220f, 180f + i * 130f, 320f), 16f, 16f, paint) }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

private const val NOW = Samples.NOW_MS
private val tvId = Samples.miTvId()
private val dockNames = listOf("Netflix", "YouTube", "Prime Video", "Live TV")
private val shotPng by lazy { fakeShot() }

private fun card(
    clean: Boolean = true, level: Level = Level.SIMPLE, chip: ChipState = ChipState.CONNECTED, thumbnail: Boolean = true,
    switchedOff: Int = 23, room: String? = "Living room", restorePointAtMs: Long? = NOW, debugging: DebugState = DebugState.ON,
) = TvCard(tvId, "Mi TV 4A", room, clean, NOW, level, chip, if (thumbnail) shotPng else null, switchedOff, dockNames, restorePointAtMs, debugging)

private fun manage(card: TvCard = card(), offline: Boolean = false, reconnecting: Boolean = false, others: List<TvCard> = emptyList()) =
    ManageState(cards = listOf(card) + others, selected = tvId, offline = offline, reconnecting = reconnecting, loading = false)

private fun off(pkg: String, name: String, description: String?, off: Boolean = true) = OffRow(PackageName(pkg), name, description, off)

private val offGroups = listOf(
    OffGroup("ads", "Adverts and sponsored rows", listOf(
        off("com.mitv.patchwall", "PatchWall", "Xiaomi's home screen with adverts"),
        off("com.mitv.promos", "Mi TV+ promos", "Promoted content rows"),
        off("com.glance.tv", "Glance", "Adverts on the lock screen"),
    )),
    OffGroup("tracking", "Tracking and analytics", listOf(
        off("com.xiaomi.analytics", "Xiaomi Analytics", "Reports what you watch to Xiaomi"),
        off("com.alphonso.tv", "Alphonso", "Listens to what's on to sell ads"),
    )),
    OffGroup("other", "", listOf(off("com.example.legacy", "Legacy app", null))),
)
private val askGroups = listOf(
    OffGroup("voice", "Voice assistant", listOf(
        off("com.google.android.katniss", "Google Assistant", "Voice search and commands", off = false),
    )),
    OffGroup("updates", "Software updates", listOf(
        off("com.mitv.ota", "System updater", "Kept on so the TV keeps getting security fixes.", off = false),
        off("com.mitv.otaextra", "Update helper", "Checks for new TV software", off = true),
    )),
)

private fun switchedOff(level: Level = Level.SIMPLE, groups: List<OffGroup> = offGroups, ask: List<OffGroup> = emptyList(), busy: String? = null, count: Int = 6) =
    SwitchedOffState("Mi TV 4A", level, groups, ask, busy?.let { PackageName(it) }, loading = false, count = count)

private fun appSettings(update: UpdateCheck = UpdateCheck.Idle, backing: String = "STRONGBOX") = AppSettingsState(
    version = "0.1.0", prefs = AppPrefsValue(haptics = true, largeTextFollowsSystem = true),
    launcherPkg = "com.gathin.tvhome", launcherVersion = "0.1.0", launcherDigest = "9f2c41d07a6be8135c4d0e72ab19f5c3",
    keyBacking = backing, keyFingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:7F:2A", update = update,
)

private fun tvSettings(level: Level = Level.SIMPLE, room: String? = "Living room") = TvSettingsState("Mi TV 4A", room, level, "192.168.1.50", NOW)

private fun openWrite(kind: PlanKind = PlanKind.SWITCH_OFF, reason: PauseReason = PauseReason.CONNECTION_LOST, busy: Boolean = false, notTaken: Boolean = false,
                      nothingDone: Boolean = false, tookHome: String? = null) =
    OpenWrite(tvId, "Mi TV 4A", PlanId("01JAZ0000000000000000000"), kind, reason, busy, notTaken, nothingDone, tookHome)

// 0.3.2 B1 and B2: the report preview with the text the report builder makes (TvReportTest has the
// exact text; this is its shape, shortened). 0.3.3 D1: the TV report in its compact form, as the
// preview shows it and the form gets it.
private val tvReport = """
    Clean Your TV: TV report
    App: 0.3.2 (full)
    Phone: Samsung SM-S921B, Android 16

    TV
    Brand: Hisense
    Manufacturer: Hisense
    Model: 55A6H
    Device: HiTV-MT9602
    Android: 11 (SDK 30)
    Build: Hisense/HE55A6H/HiTV-MT9602:11/RTM6.230109.155/155:user/release-keys
    Google Play services: yes
    Home screen it came with: Google TV
    Read from the TV: 2026-10-23, before any change

    Matching
    Profile: brand set Hisense
    Home switch: blocked WIZARD_STAYS_ON

    Home screen
    Resolves to: com.google.android.apps.tv.launcherx/.home.HomeActivity
    Claimant: com.google.android.apps.tv.launcherx/.home.HomeActivity priority 2

    System apps (5)
    Switched off by this app (1):
    com.hisense.storedemo

    On, enabled (2):
    com.google.android.apps.tv.launcherx, com.google.android.tungsten.setupwraith

    On, default (2):
    android, com.android.tv.settings

    Other apps: 14 (not named)
""".trimIndent()

private val problemReport = """
    Clean Your TV: problem report
    App: 0.3.2 (play)
    Phone: Samsung SM-S921B, Android 16

    TV
    Brand: Insignia
    Model: NS-55F501NA26
    Device: mantis
    Android: 9 (SDK 28)
    Home screen it came with: Fire TV
    Profile: brand set Amazon
    Home switch: blocked TV_REFUSES
    Apps: 255 system, 14 other

    Diagnostics log (addresses, serial numbers, keys and codes removed)
    Clean Your TV 0.3.2, Android 36
    1792745068397 I setup: probe done in 5464 ms, 269 packages, profile brand set Amazon, tv Insignia NS-55F501NA26 (mantis) sdk 28
    1792745323148 I setup: run paused STEP_FAILED at step 72: undo step failed: Enable com.amazon.tv.launcher: nonzero_exit(1)
""".trimIndent()

private fun report(kind: ReportKind, text: String = if (kind == ReportKind.TV) tvReport else problemReport, inLink: Boolean = true) =
    ReportState(kind, loading = false, text = text, url = "https://github.com/udhaykumarbala/cleanyourtv-app/issues/new", available = true, inLink = inLink)

private val tall = 1100.dp

private val playSettings = appSettings().copy(updateCheck = false, addOns = false)

// RF-STORES-030 M2: the Play build's Switched off at the Custom level. An updater an earlier full
// build switched off is listed with Put back; the ask groups have no updater pills.
private val playOffGroups = offGroups.dropLast(1) + OffGroup("updates", "Software updates", listOf(
    off("com.mitv.otaextra", "Update helper", "Checks for new TV software"),
)) + offGroups.last()
private val betterGroups = listOf(
    OffGroup(OffGroup.BETTER_BACK_ON, OffGroup.BETTER_BACK_ON_TITLE, listOf(
        off("com.tcl.xian.StartandroidService", "TCL start service", null).copy(reason = "TCL's software update needs this to find your TV."),
        off("com.google.android.tv.remote.service", "Remote service", "Lets a phone work as the remote").copy(reason = "Your remote's mic button needs this."),
    )),
)
private val playAskGroups = askGroups.filter { it.id != "updates" }

object ManageShots {
    val all: List<Shot> = listOf(
        // M1 Manage
        Shot("manage_clean") { ManageScreen(manage(), {}) },
        Shot("manage_custom_level") { ManageScreen(manage(card(level = Level.CUSTOM)), {}) },
        Shot("manage_original") { ManageScreen(manage(card(clean = false, switchedOff = 0)), {}) },
        Shot("manage_offline") { ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true), {}) },
        Shot("manage_reconnecting") { ManageScreen(manage(card(chip = ChipState.OFFLINE), offline = true, reconnecting = true), {}) },
        Shot("manage_debugging_off") { ManageScreen(manage(card(chip = ChipState.DEBUGGING_OFF, debugging = DebugState.OFF)), {}) },
        Shot("manage_working") { ManageScreen(manage(card(chip = ChipState.WORKING)), {}) },
        Shot("manage_no_thumbnail") { ManageScreen(manage(card(thumbnail = false, room = null)), {}) },
        // 0.3.2 B1: a TV no profile knows invites the person to help add it.
        Shot("manage_help_add_tv", tall) { ManageScreen(manage(card().copy(helpAddTv = true)), {}) },
        // 0.3.3 D2 (issue #3): the new home screen isn't on this TV; the Home screen row says so.
        Shot("manage_own_home", tall) { ManageScreen(manage(card().copy(helpAddTv = true, ownHome = true, dockNames = emptyList())), {}) },
        // WP4 (C6): one line for the apps the list for this TV now keeps on.
        Shot("manage_better_back_on", tall) { ManageScreen(manage(card().copy(betterBackOn = 2)), {}) },
        // 0.4.6: a setup wizard claims Home under the new home screen; the way back, and while it runs.
        Shot("manage_wizard_home", tall) { ManageScreen(manage(card().copy(wizardHome = true)), {}) },
        Shot("manage_wizard_home_working", tall) { ManageScreen(manage(card().copy(wizardHome = true)).copy(switchingHome = true), {}) },
        Shot("manage_two_tvs", tall) {
            val other = card().copy(id = TvId("1111222233334444"), name = "Bedroom Chromecast", room = "Bedroom")
            ManageScreen(manage(others = listOf(other)), {})
        },

        // 0.3.2 B1 and B2: the report preview
        Shot("report_tv", tall) { ReportScreen(report(ReportKind.TV), {}, {}) },
        Shot("report_problem", tall) { ReportScreen(report(ReportKind.PROBLEM), {}, {}) },
        // 0.3.3 D1: too long for the link, so the screen says it copies the report for the paste.
        Shot("report_tv_too_long", tall) { ReportScreen(report(ReportKind.TV, inLink = false), {}, {}) },
        Shot("report_loading") { ReportScreen(ReportState(ReportKind.TV), {}, {}) },
        Shot("report_unavailable") { ReportScreen(ReportState(ReportKind.TV, loading = false, available = false), {}, {}) },

        // M1a the debugging step for a returning write
        Shot("debugstep_waiting") { SheetOver({ ManageScreen(manage(), {}) }) { DebugStepContent("Mi TV 4A", connecting = false) {} } },
        Shot("debugstep_connecting") { SheetOver({ ManageScreen(manage(), {}) }) { DebugStepContent("Mi TV 4A", connecting = true) {} } },

        // The open-run card (R5-W8 O2)
        Shot("openwrite_connection_lost") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(), {}, {}, {}) } },
        // Undo and Stop here ask first, in the same sheet.
        Shot("openwrite_confirm_undo") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(), {}, {}, {}, initiallyAsking = OpenWriteAction.UNDO) } },
        Shot("openwrite_confirm_stop_here") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(), {}, {}, {}, initiallyAsking = OpenWriteAction.STOP_HERE) } },
        Shot("openwrite_step_failed") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(reason = PauseReason.STEP_FAILED), {}, {}, {}) } },
        Shot("openwrite_busy") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(busy = true), {}, {}, {}) } },
        Shot("openwrite_not_taken") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(notTaken = true), {}, {}, {}) } },
        // QA of 0.4.5: the TV changed after we read it (STALE): before any step, and partway through.
        Shot("openwrite_stale_nothing_done") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(kind = PlanKind.THEME, reason = PauseReason.STALE, nothingDone = true), {}, {}, {}) } },
        Shot("openwrite_stale_partway") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(kind = PlanKind.THEME, reason = PauseReason.STALE), {}, {}, {}) } },
        // 0.4.6: the app a put back switched on took the Home button.
        Shot("openwrite_took_home") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(kind = PlanKind.PUT_BACK, reason = PauseReason.STEP_FAILED, tookHome = "Android TV Home"), {}, {}, {}) } },
        Shot("openwrite_setup") { SheetOver({ ManageScreen(manage(), {}) }) { OpenWriteContent(openWrite(kind = PlanKind.SETUP), {}, {}, {}) } },

        // M4 Switched off
        Shot("switchedoff_groups", tall) { SwitchedOffScreen(switchedOff(), {}, {}) },
        Shot("switchedoff_busy", tall) { SwitchedOffScreen(switchedOff(busy = "com.glance.tv"), {}, {}) },
        Shot("switchedoff_custom", tall) { SwitchedOffScreen(switchedOff(Level.CUSTOM, ask = askGroups), {}, {}) },
        // WP4 (C6): "Better back on" at the top, each row with its reason and "Switch back on".
        Shot("switchedoff_better_back_on", tall) { SwitchedOffScreen(switchedOff(groups = betterGroups + offGroups, count = 8), {}, {}) },
        Shot("switchedoff_empty") { SwitchedOffScreen(switchedOff(groups = emptyList(), count = 0), {}, {}) },
        Shot("switchedoff_loading") { SwitchedOffScreen(SwitchedOffState(), {}, {}) },

        // M5 Check for leftovers
        Shot("leftovers_checking") { LeftoversScreen(LeftoversState.Checking, {}, {}) },
        Shot("leftovers_found") { LeftoversScreen(LeftoversState.Found(listOf("PatchWall", "Glance", "Xiaomi Analytics"), Samples.plan(emptyList())), {}, {}) },
        Shot("leftovers_found_one") { LeftoversScreen(LeftoversState.Found(listOf("PatchWall"), Samples.plan(emptyList())), {}, {}) },
        Shot("leftovers_nothing") { LeftoversScreen(LeftoversState.Nothing, {}, {}) },
        Shot("leftovers_working") { LeftoversScreen(LeftoversState.Working(40), {}, {}) },
        Shot("leftovers_done") { LeftoversScreen(LeftoversState.Done(3), {}, {}) },
        Shot("leftovers_failed") { LeftoversScreen(LeftoversState.Failed, {}, {}) },

        // M7 TV settings
        Shot("tvsettings_simple", tall) { TvSettingsScreen(tvSettings(), {}, {}) },
        Shot("tvsettings_custom", tall) { TvSettingsScreen(tvSettings(Level.CUSTOM, room = null), {}, {}) },
        Shot("tvsettings_forget") { SheetOver({ TvSettingsScreen(tvSettings(), {}, {}) }) { ForgetContent("Mi TV 4A") {} } },

        // M8 App settings
        Shot("appsettings_idle", tall) { AppSettingsScreen(appSettings(), {}, {}, {}) },
        Shot("appsettings_checking", tall) { AppSettingsScreen(appSettings(UpdateCheck.Checking), {}, {}, {}) },
        Shot("appsettings_update_available", tall) { AppSettingsScreen(appSettings(UpdateCheck.Available("0.2.0")), {}, {}, {}) },
        Shot("appsettings_up_to_date", tall) { AppSettingsScreen(appSettings(UpdateCheck.Latest), {}, {}, {}) },
        Shot("appsettings_update_failed", tall) { AppSettingsScreen(appSettings(UpdateCheck.Failed), {}, {}, {}) },
        Shot("appsettings_key_tee", tall) { AppSettingsScreen(appSettings(backing = "TEE"), {}, {}, {}) },
        Shot("appsettings_key_wrapped", tall) { AppSettingsScreen(appSettings(backing = "WRAPPED_SOFTWARE"), {}, {}, {}) },
        Shot("appsettings_licences") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { LicencesContent() } },
        Shot("appsettings_privacy") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { PrivacyContent() } },
        Shot("appsettings_reset_key") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { ConfirmContent(AppConfirm.RESET_KEY, working = false) {} } },
        Shot("appsettings_delete_all") { SheetOver({ AppSettingsScreen(appSettings(), {}, {}, {}) }) { ConfirmContent(AppConfirm.DELETE_EVERYTHING, working = false) {} } },

        // The Google Play build (STORES-PLAN 1): no Cards and modules row (the add-ons entry), no
        // Check for updates, the privacy line without GitHub, and no updater pills in Switched off
        // (RF-STORES-030 M2). Every other screen is the same.
        Shot("play_manage_clean") { ManageScreen(manage().copy(addOns = false), {}) },
        Shot("play_appsettings_idle", tall) { AppSettingsScreen(playSettings, {}, {}, {}) },
        Shot("play_switchedoff_custom", tall) { SwitchedOffScreen(switchedOff(Level.CUSTOM, groups = playOffGroups, ask = playAskGroups, count = 7), {}, {}) },
        Shot("play_appsettings_privacy") { SheetOver({ AppSettingsScreen(playSettings, {}, {}, {}) }) { PrivacyContent(online = false) } },
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi")
class ManageScreenshotTest(private val shot: Shot, private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun shot() = compose.shoot(shot, variant)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = ManageShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
