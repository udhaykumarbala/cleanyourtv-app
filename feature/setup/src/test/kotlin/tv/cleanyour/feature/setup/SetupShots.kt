package tv.cleanyour.feature.setup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import tv.cleanyour.core.flows.HomeChoice
import tv.cleanyour.core.flows.LookReason
import tv.cleanyour.core.flows.LookRow
import tv.cleanyour.core.flows.LookRows
import tv.cleanyour.core.flows.LookSample
import tv.cleanyour.core.flows.LookSamples
import tv.cleanyour.core.flows.LookTv
import tv.cleanyour.core.flows.StyleCard
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookPage
import tv.cleanyour.ui.design.component.SheetSurface
import tv.cleanyour.ui.design.theme.CytTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.LookDocs
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookPicture
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.flows.PictureOption
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.NoSetupActions
import tv.cleanyour.core.flows.NothingWhy
import tv.cleanyour.core.flows.Reconnect
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.PhotoPicker
import tv.cleanyour.ui.design.component.UndoSnackbar
import tv.cleanyour.ui.design.component.rememberUndoSnackbarState

// Every state of :feature:setup's screens (PRODUCT-SPEC 4: probing, S3, S3a, S3b, S4a, S4b, S4c to
// S4e, S5, and the stops of section 4 that live here: install failure, TV refuses other home
// screens, TV not back after a restart), with the M5 cards: "We put the old home screen back"
// (HOME_PUT_BACK), the resume card for a Manage change and "Reconnect to the TV to put the old
// home screen back" (R-11). The copy comes from this module's strings, as the app formats it.

private fun fakeShot(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFF05060A.toInt())
    val paint = Paint()
    val tiles = intArrayOf(0xFF2A2F45.toInt(), 0xFF1F5FD0.toInt(), 0xFF4A4A50.toInt(), 0xFF138A7A.toInt())
    repeat(4) { i -> paint.color = tiles[i]; canvas.drawRoundRect(RectF(70f + i * 130f, 230f, 180f + i * 130f, 320f), 16f, 16f, paint) }
    paint.color = 0xFF3A3A40.toInt()
    canvas.drawRoundRect(RectF(60f, 40f, 160f, 70f), 8f, 8f, paint)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

// Sample data: the Mi TV 4A plan of PRODUCT-SPEC 4 S3.

private val tvId = Samples.miTvId()
private const val NOW = Samples.NOW_MS

private fun row(pkg: String, name: String, description: String, off: Boolean = true, protectedRow: Boolean = false, why: String? = null) =
    RowSummary(PackageName(pkg), name, description, off, protectedRow, why)

private val adsGroup = GroupSummary("ads", "Adverts and sponsored rows", off = 5, total = 6, rows = listOf(
    row("com.mitv.tvhome.atv", "PatchWall", "Xiaomi's home screen with adverts"),
    row("com.mitv.tvhome.mitvplus", "Mi TV+ promos", "Promoted content rows", off = false),
    row("com.glance.tv", "Glance", "Adverts on the lock screen"),
    row("com.xiaomi.mitv.ad", "Mi ad service", "Fetches the adverts for the rows"),
    row("com.mitv.shop", "Mi Shop banner", "Sponsored banner on the home screen"),
    row("com.mitv.screensaver.ads", "Screensaver adverts", "Adverts in the screensaver"),
    row("com.droidlogic.tvinput", "Live TV and HDMI", "Plays your HDMI set-top box", off = false, protectedRow = true,
        why = "Live TV plays your HDMI set-top box; switching it off would lose the Inputs button."),
))
private val trackingGroup = GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = listOf(
    row("com.xiaomi.analytics", "Xiaomi Analytics", "Reports what you watch to Xiaomi"),
    row("com.alphonso.tv", "Alphonso", "Listens to what's on to sell ads"),
))
private val nobodyGroup = GroupSummary("nobody", "Apps nobody asked for", off = 8, total = 8, rows = listOf(
    row("com.example.demo", "Demo mode", "Shop display mode"),
))
private val extrasGroup = GroupSummary("brand", "Xiaomi extras", off = 2, total = 2, rows = listOf(
    row("com.xiaomi.mitv.smartshare", "Mi Smart Share", "Casting from Xiaomi phones"),
))
private val keepNames = listOf("Netflix", "YouTube", "Prime Video")
private val dock = listOf("Netflix", "YouTube", "Prime Video", "Live TV")

private fun summary(groups: List<GroupSummary>, home: Boolean = true) =
    PlanSummary(groups, keepNames, dock, homeScreenPossible = home, replacesLauncher = false)

private fun plan(summary: PlanSummary): Plan = Samples.plan(emptyList()).copy(summary = summary)

private val fullPlan = plan(summary(listOf(adsGroup, trackingGroup, nobodyGroup, extrasGroup)))
private val zeroPlan = plan(summary(listOf(GroupSummary("ads", "Adverts and sponsored rows", 0, 0, emptyList()))))
private val unknownPlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 3, total = 3, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 4, total = 4, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 2, total = 2, rows = emptyList()),
)))
// The emulator TV (round 3): an unknown model where nothing is sure, so the one switch-off is the
// TV's own home screen inside the home screen switch, listed and counted like any other.
private val homeSwitchGroup = GroupSummary("home", "Needed for the new home screen", off = 1, total = 1, rows = listOf(
    row("com.google.android.tvlauncher", "Android TV home", "The old home screen. Switched off only after the new one works.",
        protectedRow = true, why = "part of the home screen switch; it comes back on if the switch is undone"),
))
// The Setup plan sends the dock and the look before the switch (RFC-DOCK-1), so its config.json
// push is on the list as the sync line planHash uses.
private const val CONFIG_SYNC = "sync:/sdcard/Android/data/com.gathin.tvhome/files/config.json 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private val homeSwitchCommands = listOf(
    CONFIG_SYNC,
    "pm disable-user --user 0 com.google.android.tvlauncher",
    "cmd package set-home-activity com.gathin.tvhome/.MainActivity",
)
private val homeSwitchOnlyPlan = plan(summary(listOf(homeSwitchGroup)).copy(dock = emptyList()))
// RFC-CURTAIN-1: the same plan with the TV curtain, as "Show commands" lists it: the curtain's install,
// its start, one am broadcast per cue, and its removal last.
private fun cue(stage: String, count: Int = 0, total: Int = 0) =
    "am broadcast -n tv.cleanyour.curtain/.CueReceiver -a tv.cleanyour.curtain.CUE --es token 3f9c0a17d2e4b856 --es stage $stage --es group NONE --ei count $count --ei total $total --ei gcount 0 --ei gtotal 0 --receiver-foreground"
private val curtainCommands = listOf(
    "sync:bundled:cleanyourtv-curtain.apk 49571",
    "am start -n tv.cleanyour.curtain/.CurtainActivity",
    cue("SCAN"), cue("RESTORE_SAVED"), cue("INSTALL"),
    "sync:bundled:cleanyourtv-launcher.apk 270885",
    cue("INSTALLED"), cue("FINALE"),
    CONFIG_SYNC,
    "pm disable-user --user 0 com.google.android.tvlauncher",
    "cmd package set-home-activity com.gathin.tvhome/.MainActivity",
    "pm uninstall --user 0 tv.cleanyour.curtain",
)
private val noHomePlan = plan(summary(listOf(
    GroupSummary("ads", "Adverts and sponsored rows", off = 6, total = 6, rows = emptyList()),
    GroupSummary("tracking", "Tracking and analytics", off = 7, total = 7, rows = emptyList()),
    GroupSummary("nobody", "Apps nobody asked for", off = 3, total = 3, rows = emptyList()),
), home = false))

private val section = GuardedSection("home", 0, 0, RunToken("0123456789abcdef"), confirmWindowMs = 90_000,
    expectedHome = Samples.launcherComponent, rollbackIfFocus = emptySet(), rollback = emptyList())
private val longSection = section.copy(confirmWindowMs = 120_000)

private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)
private val dockApps = listOf(app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
    app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("com.droidlogic.tvinput", "Live TV"))
private val otherApps = listOf(app("com.spotify.tv.android", "Spotify"), app("com.plexapp.android", "Plex"), app("org.videolan.vlc", "VLC"))
private val themes = listOf(
    ThemeOption("aurora", "Aurora", "A deep blue background with a soft glow behind the apps."),
    ThemeOption("six", "Six columns", "Six apps across, so more fit on the screen."),
)
private val commands = listOf(
    "pm disable-user --user 0 com.mitv.tvhome.atv",
    "pm disable-user --user 0 com.glance.tv",
    CONFIG_SYNC,
    "cmd package set-home-activity tv.cleanyour.launcher/.Home",
)

private val connected = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED)
private val working = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WORKING)
private val offline = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.OFFLINE)
private val customising = connected.copy(
    dock = dockApps, apps = dockApps + otherApps, themes = themes, commands = commands,
    draft = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), null, null, false, null, NOW),
)

// 0.4.0 look step (RFC-LOOK-SETUP-1): the Look editor as SetupMachine fills it. The previews are
// made as the machine makes them, from the bytes the push would send for the bundled looks
// (LookDocs, so fill_dock false and the apps in a row are in them), with their real pictures read
// from the :core:spec resources.
private val bundledLooks = BundledThemeSource().bundled().associateBy { it.id }
private val gallery = BundledThemeSource().pictures().associateBy { it.id }
private fun resourcePath(ref: BlobRef?) = ref?.path?.removePrefix(BundledThemeSource.PREFIX)?.let { "/$it" }
private val pictureFiles: Map<String, ImageBitmap> by lazy {
    (bundledLooks.values.map { it.wallpaper } + gallery.values.map { it.wallpaper }).mapNotNull { resourcePath(it) }.distinct().associateWith { path ->
        BitmapFactory.decodeStream(SetupShots::class.java.getResourceAsStream(path), null, BitmapFactory.Options().apply { inSampleSize = 4 })!!.asImageBitmap()
    }
}
// A photo made in the look step stands in as Mist's own picture file.
private val newPhoto = BlobRef(Samples.zeroHash, 1, resourcePath(bundledLooks.getValue("mist").wallpaper)!!)
private fun lookPreview(id: String, picture: LookPicture.Of?, columns: Int?, dock: List<AppChoice>): LookPreview {
    val (doc, wallpaper) = LookDocs.of(bundledLooks.getValue(id), dock.map { it.pkg }, cards = true, picture, columns)
    val p = LookPreview.of(LauncherConfigCodec.encode(doc), null, dock.map { it.name }, LocalDate.of(2026, 10, 25), 21 * 60 + 5)
    return p.copy(picturePath = if (doc.wallpaper != false) wallpaper?.path?.let { it.removePrefix(BundledThemeSource.PREFIX).let { r -> if (r.startsWith("/")) r else "/$r" } } else null)
}
// The step's draft as SetupMachine makes it: the look's own apps in a row unless `columns`.
private fun lookDraft(id: String, picture: String? = null, columns: Int? = null, dock: List<AppChoice> = dockApps): LookDraft {
    val own = DockLayers.columnsOf(bundledLooks.getValue(id).doc)
    val p = when (picture) {
        null, LookPicture.NONE_ID -> null
        LookPicture.NEW_ID -> LookPicture.Of(newPhoto, 40)
        else -> LookPicture.Of(gallery.getValue(picture).wallpaper)
    }
    val preview = if (picture == LookPicture.NONE_ID) lookPreview(id, null, columns, dock).copy(picturePath = null) else lookPreview(id, p, columns, dock)
    return LookDraft(id, picture, false, preview, columns ?: own, own)
}
private fun lookStep(draft: LookDraft, dock: List<AppChoice> = dockApps, waiting: Int = 0, themeId: String = draft.themeId) = customising.copy(
    themes = bundledLooks.values.map { ThemeOption(it.id, it.name, it.description, resourcePath(it.wallpaper)) },
    pictures = gallery.values.map { PictureOption(it.id, it.name, resourcePath(it.wallpaper)) },
    look = draft, dock = dock, waiting = waiting,
    draft = customising.draft?.copy(themeId = themeId, columns = draft.columns.takeIf { it != draft.lookColumns }, picture = draft.picture),
)

// look2 look step: the same Look editor as the Look sheet, with Done. The previews come from the
// bytes the push would send (LookSamples); the rows are written out here (a fake in test sources).
private val lookSource = BundledThemeSource()
private fun look2Preview(style: StyleId, picture: String, columns: Int = 5, extras: tv.cleanyour.core.spec.LookExtras = tv.cleanyour.core.spec.LookExtras.DEFAULT): LookPreview {
    val choice = HomeChoice(dockApps.map { it.pkg }, null, cards = false, picture = picture, columns = columns, style = style.id, movement = Movement.LIVELY.id,
        extras = extras)
    val p = LookSamples.preview(LookSample("setup", choice, choice.dock, LookTv(if (extras == tv.cleanyour.core.spec.LookExtras.DEFAULT) 8L else 9L, 2048, false)), lookSource, lookSource, LocalDate.of(2026, 10, 26), 20 * 60 + 30)
    return p.copy(picturePath = gallery[picture]?.let { resourcePath(it.wallpaper) }, dock = dockApps.map { it.name })
}
private fun look2Rows(style: StyleId, picture: String): LookRows {
    val own = LookKeys.own(style)
    return LookRows(
        names = LookRow(false, true), bigName = LookRow(own.bigName, true), glow = LookRow(own.glow, true),
        spotColour = LookRow(false, false, visible = false), ring = LookRow(own.ring, true), band = LookRow(own.band, true),
        buttons = LookRow(own.buttons, true), greenWifi = LookRow(own.greenWifi, true),
        dim = if (picture == LookPicture.NONE_ID) LookRow(0, false, LookReason.NO_PICTURE) else LookRow(0, true), sky = LookRow(false, true),
        lift = LookRow(false, true), seeThrough = LookRow(50, false, LookReason.SEE_THROUGH_OFF), skyStrength = LookRow(55, false, LookReason.SKY_OFF),
        resettable = 0, festiveRing = false, liftSlowRamMb = null,
    )
}
private fun look2Draft(style: StyleId = StyleId.CLASSIC, picture: String = "mist", columns: Int = 5, newPhoto: Boolean = false, waiting: Int = 0,
    oneRow: Boolean? = null) = LookDraft(
    "default", picture,
    // oneRow: a TV that reads 3.0, with Every app in one row as given (the preview from the bytes the push writes).
    preview = look2Preview(style, if (newPhoto) "mist" else picture, columns, tv.cleanyour.core.spec.LookExtras(oneRow = oneRow == true)),
    columns = columns, lookColumns = columns, style = style,
    reads30 = oneRow != null, extras = tv.cleanyour.core.spec.LookExtras(oneRow = oneRow == true),
    // What acts and what could act (LookRules.extras): one row on every style with a dock, the band's None
    // not on Spotlight, the internet warnings only with Green Wi-Fi (off in these rows).
    actingExtras = tv.cleanyour.core.spec.LookExtras(oneRow = oneRow == true && style != StyleId.ALL_APPS).takeIf { oneRow != null }
        ?: tv.cleanyour.core.spec.LookExtras.DEFAULT,
    offeredExtras = if (oneRow == null) tv.cleanyour.core.spec.LookExtras.DEFAULT else tv.cleanyour.core.spec.LookExtras(
        oneRow = style != StyleId.ALL_APPS, noBand = style != StyleId.ALL_APPS && style != StyleId.SPOTLIGHT, hideGreeting = true, dateBelow = true,
        brightDate = true, tiles = tv.cleanyour.core.spec.TilesChoice.ICONS, highlight = tv.cleanyour.core.spec.HighlightChoice.RIM),
    styleCards = StyleId.entries.map { StyleCard(it, look2Preview(it, if (newPhoto) "mist" else picture, columns), true) },
    rows = look2Rows(style, picture), newPhoto = newPhoto, settingsFile = "{\n  \"columns\": $columns\n}", apps = dockApps.size + waiting, waiting = waiting,
)
private fun look2Step(draft: LookDraft) = customising.copy(
    pictures = gallery.values.map { PictureOption(it.id, it.name, resourcePath(it.wallpaper), it.until, it.backgroundArgb) }, look = draft, dock = dockApps,
)

// The look step's sheet with a bounded height, as the modal sheet gives it.
@Composable
private fun LookFrame(behind: @Composable () -> Unit, page: LookPage = LookPage.MAIN, content: @Composable ColumnScope.(LookEditorNav) -> Unit) {
    val nav = remember { LookEditorNav().apply { open(page) } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { behind() }
        Box(Modifier.fillMaxSize().background(CytTheme.colors.scrim))
        SheetSurface(Modifier.align(Alignment.BottomCenter).padding(top = 48.dp)) { content(nav) }
    }
}

// The dock sheet's state as DockSheet makes it (remembered, so it survives recomposition).
@Composable
private fun dockState(apps: List<AppChoice>, addRow: Boolean = false) =
    remember { tv.cleanyour.ui.design.component.DockEditorState(apps.map { it.toDockApp() }, 4).apply { if (addRow) addRow() } }

private val rowsPlan = plan(summary(listOf(adsGroup, trackingGroup, nobodyGroup, extrasGroup)).copy(dock = dock + listOf("Spotify", "Plex")))

private fun found(plan: Plan = fullPlan, level: Level = Level.SIMPLE) = SetupState.Found(tvId, plan, level, sheet = null)

private fun running(lines: List<NarrationLine>, percent: Int, thumbnail: ByteArray?) =
    SetupState.Working(tvId, RunState.Running(fullPlan, step = 3, narration = lines, percent = percent, thumbnail = thumbnail), thumbnail)

private fun checkState(thumbnail: ByteArray?, deadlineAheadMs: Long = 84_000, homeCheck: Boolean? = null, s: GuardedSection = section) =
    SetupState.Check(tvId, RunState.AwaitingConfirm(fullPlan, s, NOW + deadlineAheadMs, homeCheck, thumbnail), thumbnail, dock)

private fun done(off: Int = 23, home: Boolean = true, skipped: List<String> = emptyList(), recommended: Boolean = false, restart: RestartState? = null) =
    SetupState.Done(tvId, DoneSummary(off, home, NOW, skipped, recommended), restart)

private val narration = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Tracking: 4 of 7...", NarrationState.DOING),
    NarrationLine("Apps nobody asked for", NarrationState.UPCOMING),
    NarrationLine("Installing the new home screen", NarrationState.UPCOMING),
    NarrationLine("Switching Home over", NarrationState.UPCOMING),
)
private val narrationAllStates = listOf(
    NarrationLine("Restore point saved", NarrationState.DONE),
    NarrationLine("Adverts switched off (6)", NarrationState.DONE),
    NarrationLine("Play Games wouldn't switch off", NarrationState.SKIPPED),
    NarrationLine("Mi Cast refused", NarrationState.FAILED),
    NarrationLine("Tracking switched off (7)", NarrationState.DONE),
    NarrationLine("Apps nobody asked for: 3 of 8...", NarrationState.DOING),
    NarrationLine("Replacing the home screen app", NarrationState.UPCOMING),
    NarrationLine("Switching Home over", NarrationState.UPCOMING),
)

// A stop card with the copy the app's ResourceFlowCopy would put on it.
@Composable
private fun card(case: StopCase, title: Int, body: Int, primary: Int, secondary: Int? = null, tertiary: Int? = null, args: Map<String, Any> = emptyMap()) =
    StopCard(
        case, msg(title, *args.toList().toTypedArray()), msg(body, *args.toList().toTypedArray()), msg(primary, *args.toList().toTypedArray()),
        secondary?.let { msg(it, *args.toList().toTypedArray()) }, planId = null, tertiary = tertiary?.let { msg(it, *args.toList().toTypedArray()) },
    )

private val lostArgs = mapOf<String, Any>("done" to 14, "total" to 23, "tv" to "Mi TV 4A")

@Composable
private fun connectionLost() = card(StopCase.CONNECTION_LOST, R.string.stop_connection_lost_title, R.string.stop_connection_lost_body,
    R.string.stop_connection_lost_primary, R.string.stop_connection_lost_secondary, R.string.stop_connection_lost_tertiary, lostArgs)

@Composable
private fun resume() = card(StopCase.RESUME_AFTER_DEATH, R.string.stop_resume_title, R.string.stop_resume_body,
    R.string.stop_resume_primary, R.string.stop_resume_secondary, R.string.stop_resume_tertiary, lostArgs)

@Composable
private fun homePutBack() = card(StopCase.HOME_PUT_BACK, R.string.stop_resume_home_title, R.string.stop_resume_home_body,
    R.string.stop_resume_home_primary, R.string.stop_resume_home_secondary, args = lostArgs)

@Composable
private fun home(case: StopCase, title: Int, body: Int, primary: Int, secondary: Int?, args: Map<String, Any> = emptyMap()) =
    card(case, title, body, primary, secondary, args = args)

@Composable
private fun Setup(state: SetupState, extras: SetupExtras = connected) = SetupFlow(state, extras, NoSetupActions, nowMs = { NOW })

@Composable
private fun Stopped(c: StopCard, extras: SetupExtras = connected) = Setup(SetupState.Stopped(tvId, c), extras)

object SetupShots {
    val all: List<Shot> = listOf(
        // S2d to S3 probing
        Shot("probing_start") { Setup(SetupState.Probing(tvId, ProbeStage.FACTS, null)) },
        Shot("probing_packages") { Setup(SetupState.Probing(tvId, ProbeStage.PACKAGES, null)) },
        // Test build: the card before the plan for a list not tested yet
        Shot("probing_candidate") { Setup(SetupState.Probing(tvId, ProbeStage.PACKAGES, null), connected.copy(candidate = tv.cleanyour.core.flows.CandidateOffer("xiaomi-mitv4i-magnolia", "#111"))) },

        // S3 What we found, with the unknown model, zero, no screen lock and TV-refuses-home variants
        Shot("found_simple") { Setup(found()) },
        Shot("found_zero") { Setup(found(zeroPlan)) },
        // Emulator run: a TV with no app to suggest for the dock; the row names the new home screen.
        Shot("found_zero_empty_dock") { Setup(found(zeroPlan.copy(summary = zeroPlan.summary.copy(dock = emptyList())))) },
        Shot("found_unknown_model") { Setup(found(unknownPlan), connected.copy(unknownModel = true)) },
        Shot("found_unknown_home_switch_only") { Setup(found(homeSwitchOnlyPlan), connected.copy(unknownModel = true)) },
        Shot("found_no_screen_lock") { Setup(found(), connected.copy(noScreenLock = true)) },
        Shot("found_tv_refuses_home") { Setup(found(noHomePlan), connected.copy(tvName = "TCL C635", homePossible = false)) },
        // RF-PROFILES-63 P1 and P2: Android 7 (the clean-up only), and a profile with no known stock home.
        Shot("found_home_old_android") { Setup(found(noHomePlan), connected.copy(tvName = "Sony KD-55X8509C", homePossible = false, homeBlock = HomeBlock.OLD_ANDROID)) },
        Shot("found_home_unknown_stock") { Setup(found(noHomePlan), connected.copy(tvName = "Mi TV ASTP1", homePossible = false, homeBlock = HomeBlock.UNKNOWN_HOME)) },
        // Issue #8: Home answers to a launcher the person installed; the TV's own home screen is off.
        Shot("found_home_changed") { Setup(found(noHomePlan), connected.copy(tvName = "TCL Smart TV", homePossible = false, homeBlock = HomeBlock.HOME_CHANGED)) },
        Shot("found_starting") { Setup(found(), connected.copy(starting = true)) },
        // D36 (RF-LAUNCHER-240 F1): the plan replaces a home screen app signed with another key.
        Shot("found_replaces_launcher") { Setup(found(fullPlan.copy(summary = fullPlan.summary.copy(replacesLauncher = true)))) },
        // Issue #275: a TCL Google TV, whose remote's gear, Netflix and YouTube buttons need Google TV's home.
        Shot("found_tcl_remote_keys") { Setup(found(), connected.copy(tvName = "TCL 55C755", tclRemoteKeys = true)) },

        // S3a Customise: Simple with the other groups collapsed; Custom with every group open, the
        // package names and the undo snackbar; the "Why?" sheet
        Shot("customise_sheet") {
            SheetFrame({ Setup(found()) }) { CustomiseContent(found(), customising, NoSetupActions, rememberUndoSnackbarState(), {}, {}) }
        },
        // A dock with a row under it: "Dock: Netflix, ... plus 2 apps in the row below", the groups closed.
        Shot("customise_sheet_dock_rows") {
            SheetFrame({ Setup(found(rowsPlan)) }) {
                CustomiseContent(found(rowsPlan), customising.copy(dock = dockApps + otherApps.take(2)), NoSetupActions, rememberUndoSnackbarState(), {}, {}, initiallyOpen = emptyList())
            }
        },
        Shot("customise_sheet_custom") {
            Box(Modifier.fillMaxSize()) {
                SheetFrame({ Setup(found(level = Level.CUSTOM)) }) {
                    CustomiseContent(found(level = Level.CUSTOM), customising.copy(draft = customising.draft?.copy(keepDebuggingOn = true, themeId = "aurora")),
                        NoSetupActions, rememberUndoSnackbarState(), {}, {}, initiallyOpen = listOf("ads", "tracking", "nobody", "brand"))
                }
                // The undo snackbar after "Mi TV+ promos" was tapped (S3a), as the sheet shows it.
                UndoSnackbar(msg(R.string.customise_snackbar_kept, "name" to "Mi TV+ promos"), {},
                    Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 28.dp))
            }
        },
        // The home screen switch's own switch-off: shown Off with no pill to flip, counted in "1 of 1 off".
        Shot("customise_sheet_home_switch") {
            SheetFrame({ Setup(found(homeSwitchOnlyPlan)) }) {
                CustomiseContent(found(homeSwitchOnlyPlan), customising.copy(dock = emptyList(), commands = homeSwitchCommands), NoSetupActions, rememberUndoSnackbarState(), {}, {})
            }
        },
        // RFC-CURTAIN-1: Custom shows "Show the progress on the TV" (on by default) and the cue lines.
        Shot("customise_sheet_curtain") {
            SheetFrame({ Setup(found(homeSwitchOnlyPlan, level = Level.CUSTOM)) }) {
                CustomiseContent(found(homeSwitchOnlyPlan, level = Level.CUSTOM), customising.copy(dock = emptyList(), commands = curtainCommands),
                    NoSetupActions, rememberUndoSnackbarState(), {}, {}, initiallyOpen = emptyList())
            }
        },
        Shot("customise_why_sheet") { SheetFrame({ Setup(found()) }, maxHeight = 360.dp) { WhyContent(found()) } },

        // S3b Your dock, with room and full; the look picker
        Shot("dock_sheet") { SheetFrame({ Setup(found()) }) { DockContent(dockState(dockApps.take(3)), dockApps + otherApps, {}) } },
        Shot("dock_sheet_full") { SheetFrame({ Setup(found()) }) { DockContent(dockState(dockApps), dockApps + otherApps, {}) } },
        // An empty dock: no "Dock" heading over nothing, the apps to add straight away.
        Shot("dock_sheet_empty") { SheetFrame({ Setup(found(homeSwitchOnlyPlan)) }) { DockContent(dockState(emptyList()), otherApps, {}) } },
        // The dock with a row under it, and a row just added and still empty.
        Shot("dock_sheet_rows") { SheetFrame({ Setup(found()) }) { DockContent(dockState(dockApps + otherApps.take(2)), dockApps + otherApps, {}) } },
        Shot("dock_sheet_new_row") { SheetFrame({ Setup(found()) }) { DockContent(dockState(dockApps, addRow = true), dockApps + otherApps, {}) } },
        // look2 look step: the same pages as the Look sheet after Setup, with Done as the footer.
        // Classic with Mist and "Choose a photo"; More options; a photo made here ("New photo")
        // next to the one kept for the TV ("Your photo").
        Shot("theme_sheet", 1100.dp) {
            LookFrame({ Setup(found()) }) { nav -> ThemeContent(look2Step(look2Draft()), NoSetupActions, PhotoPicker {}, pictureFiles, nav = nav) }
        },
        // Home screen 3.0 (0.4.5): the same step on a TV that reads 3.0, with Every app in one row on.
        Shot("theme_sheet_30", 1300.dp) {
            LookFrame({ Setup(found()) }) { nav -> ThemeContent(look2Step(look2Draft(oneRow = true)), NoSetupActions, PhotoPicker {}, pictureFiles, nav = nav) }
        },
        Shot("theme_sheet_more", 1700.dp) {
            LookFrame({ Setup(found()) }, LookPage.MORE) { nav -> ThemeContent(look2Step(look2Draft(StyleId.GLASS)), NoSetupActions, PhotoPicker {}, pictureFiles, nav = nav) }
        },
        Shot("theme_sheet_photo", 1100.dp) {
            LookFrame({ Setup(found()) }) { nav ->
                ThemeContent(look2Step(look2Draft(picture = LookPicture.NEW_ID, newPhoto = true, waiting = 3)).copy(
                    ownPicture = BlobRef(Samples.zeroHash, 1, resourcePath(gallery.getValue("glass").wallpaper)!!), newPicture = newPhoto, newDim = 40),
                    NoSetupActions, PhotoPicker {}, pictureFiles, nav = nav)
            }
        },
        // The Customise row names the look and its apps in a row when that is not the look's own.
        Shot("customise_sheet_look_count", 1300.dp) {
            SheetFrame({ Setup(found()) }, maxHeight = 1280.dp) {
                CustomiseContent(found(), lookStep(lookDraft("console", "glass", columns = 6)), NoSetupActions, rememberUndoSnackbarState(), {}, {}, initiallyOpen = emptyList())
            }
        },

        // S4a Making it clean
        Shot("working_58") { Setup(running(narration, 58, remember { fakeShot() }), working) },
        Shot("working_all_narration_states") { Setup(running(narrationAllStates, 72, remember { fakeShot() }), working) },
        Shot("working_no_thumbnail") { Setup(running(narration, 12, null), working) },
        Shot("working_back_highlights_stop") { Setup(running(narration, 58, remember { fakeShot() }), working.copy(highlightStop = true)) },
        Shot("working_rolling_back") { Setup(running(narration, 58, remember { fakeShot() }), working.copy(rollingBack = true, highlightStop = true)) },

        // S4b Can you see it? (90 s, the 120 s window of D23, the live check passed, no picture)
        Shot("check_84s") { Setup(checkState(remember { fakeShot() })) },
        Shot("check_home_checked") { Setup(checkState(remember { fakeShot() }, deadlineAheadMs = 60_000, homeCheck = true)) },
        Shot("check_no_thumbnail") { Setup(checkState(null)) },
        Shot("check_two_minute_window") { Setup(checkState(null, deadlineAheadMs = 118_000, s = longSection)) },

        // S4c Stopped: the old home screen is back (UX 4.7), each case
        Shot("stopped_home_setupwraith") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_body,
                R.string.stop_home_setupwraith_primary, R.string.stop_home_setupwraith_secondary), connected.copy(commands = commands))
        },
        // D3: the wizard may have saved the TV's Wi-Fi, so it stays on and the card says why.
        Shot("stopped_home_setupwraith_wifi") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_wifi_body,
                R.string.stop_home_setupwraith_wifi_primary, R.string.stop_home_setupwraith_secondary), connected.copy(commands = commands))
        },
        // RF-LAUNCHER-240 F6: the TV could not say who saved its Wi-Fi.
        Shot("stopped_home_setupwraith_wifi_unknown") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_wifi_unknown_body,
                R.string.stop_home_setupwraith_wifi_primary, R.string.stop_home_setupwraith_secondary), connected.copy(commands = commands))
        },
        Shot("stopped_home_rolling_back") {
            Stopped(home(StopCase.HOME_SETUPWRAITH, R.string.stop_home_setupwraith_title, R.string.stop_home_setupwraith_body,
                R.string.stop_home_setupwraith_primary, R.string.stop_home_setupwraith_secondary), connected.copy(rollingBack = true))
        },
        Shot("stopped_home_other_launcher") {
            Stopped(home(StopCase.HOME_OTHER_LAUNCHER, R.string.stop_home_other_launcher_title, R.string.stop_home_other_launcher_body,
                R.string.stop_home_other_launcher_primary, R.string.stop_home_other_launcher_secondary, mapOf("launcher" to "Projectivy")), connected.copy(commands = commands))
        },
        Shot("stopped_home_unknown") {
            Stopped(home(StopCase.HOME_UNKNOWN, R.string.stop_home_unknown_title, R.string.stop_home_unknown_body,
                R.string.stop_home_unknown_primary, R.string.stop_home_unknown_secondary), connected.copy(commands = commands))
        },
        // Issue #629: a Settings page was open on the TV during the check.
        Shot("stopped_home_settings_open") {
            Stopped(home(StopCase.HOME_SETTINGS_OPEN, R.string.stop_home_unknown_title, R.string.stop_home_settings_open_body,
                R.string.stop_home_user_said_no_primary, R.string.stop_home_setupwraith_secondary), connected.copy(commands = commands))
        },
        Shot("stopped_home_user_said_no") {
            Stopped(home(StopCase.HOME_USER_SAID_NO, R.string.stop_home_user_said_no_title, R.string.stop_home_user_said_no_body,
                R.string.stop_home_user_said_no_primary, R.string.stop_home_user_said_no_secondary), connected.copy(commands = commands))
        },
        Shot("stopped_home_timed_out") {
            Stopped(home(StopCase.HOME_TIMED_OUT, R.string.stop_home_timed_out_title, R.string.stop_home_timed_out_body,
                R.string.stop_home_timed_out_primary, R.string.stop_home_timed_out_secondary), connected.copy(commands = commands))
        },
        // QA of 0.4.5: things stayed switched off, so the card says how many, never "as it was".
        Shot("stopped_home_timed_out_switched_off") {
            Stopped(home(StopCase.HOME_TIMED_OUT, R.string.stop_home_timed_out_title, R.string.stop_home_timed_out_body_off,
                R.string.stop_home_timed_out_primary, R.string.stop_home_timed_out_secondary, args = mapOf("count" to 3)), connected.copy(commands = commands))
        },
        // M5: step R put the old home screen back after a lost connection (D-W8-16)
        Shot("stopped_home_put_back") { Stopped(homePutBack(), connected.copy(commands = commands)) },
        Shot("stopped_details_sheet") { SheetFrame({ Stopped(homePutBack(), connected.copy(commands = commands)) }, maxHeight = 420.dp) { DetailsContent(commands) } },

        // S4d Stopped: connection lost mid-plan (UX 4.14), and R-11's reconnect line
        // Stop and undo ask first: the question over the Working screen and over the stop card.
        Shot("confirm_stop_and_undo") {
            SheetFrame({ Setup(running(narration, 58, remember { fakeShot() }), working) }, maxHeight = 360.dp) {
                ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.STOP_AND_UNDO, {}, {})
            }
        },
        Shot("confirm_stop_here") { SheetFrame({ Stopped(connectionLost(), connected) }, maxHeight = 360.dp) { ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.STOP_HERE, {}, {}) } },
        Shot("confirm_undo") { SheetFrame({ Stopped(connectionLost(), connected) }, maxHeight = 360.dp) { ConfirmQuestionFor(tv.cleanyour.core.flows.Confirm.UNDO, {}, {}) } },
        Shot("stopped_connection_lost_reconnecting") { Stopped(connectionLost(), offline.copy(reconnect = Reconnect.TRYING)) },
        Shot("stopped_connection_lost_back") { Stopped(connectionLost(), connected.copy(reconnect = Reconnect.BACK)) },
        Shot("stopped_connection_lost_gave_up") { Stopped(connectionLost(), offline.copy(reconnect = Reconnect.GAVE_UP)) },
        Shot("stopped_reconnect_to_put_home_back") {
            Stopped(connectionLost().copy(body = msg(R.string.stop_reconnect_home_body, "tv" to "Mi TV 4A")), offline.copy(reconnect = Reconnect.TRYING))
        },
        Shot("stopped_step_failed") {
            Stopped(card(StopCase.STEP_FAILED, R.string.stop_step_failed_title, R.string.stop_step_failed_body,
                R.string.stop_connection_lost_primary, R.string.stop_connection_lost_secondary, R.string.stop_connection_lost_tertiary, lostArgs))
        },

        // S4e Resume after process death: the setup, an open HOME section, a Manage change, and R-11
        Shot("stopped_resume") { Stopped(resume(), connected.copy(chip = ChipState.WAITING)) },
        Shot("stopped_resume_with_home") {
            Stopped(resume().copy(body = stringResource(R.string.stop_resume_home_body)), connected.copy(chip = ChipState.WAITING))
        },
        Shot("stopped_resume_change") {
            Stopped(resume().copy(title = msg(R.string.stop_resume_change_title, "tv" to "Mi TV 4A"), body = stringResource(R.string.stop_resume_change_body)),
                connected.copy(chip = ChipState.WAITING))
        },
        Shot("stopped_resume_reconnect_home") {
            Stopped(resume().copy(body = msg(R.string.stop_reconnect_home_body, "tv" to "Mi TV 4A")), offline.copy(reconnect = Reconnect.TRYING))
        },

        // Install failure, undone, probe failure
        Shot("stopped_install_failed") {
            Stopped(card(StopCase.INSTALL_FAILED, R.string.stop_install_failed_title, R.string.stop_install_failed_body,
                R.string.stop_install_failed_primary, args = mapOf("free" to 180, "needed" to 600)))
        },
        Shot("stopped_install_failed_generic") {
            Stopped(card(StopCase.INSTALL_FAILED, R.string.stop_install_failed_title, R.string.stop_install_failed_generic_body, R.string.stop_install_failed_primary))
        },
        Shot("stopped_undone") {
            Stopped(card(StopCase.UNDONE, R.string.stop_undone_title, R.string.stop_undone_body, R.string.stop_undone_primary), connected.copy(commands = commands))
        },
        Shot("stopped_probe_failed") {
            Stopped(card(StopCase.PROBE_FAILED, R.string.stop_probe_failed_title, R.string.stop_probe_failed_body,
                R.string.stop_probe_failed_primary, R.string.stop_probe_failed_secondary))
        },
        Shot("stopped_plan_refused") {
            Stopped(card(StopCase.PLAN_REFUSED, R.string.stop_plan_refused_title, R.string.stop_plan_refused_body,
                R.string.stop_probe_failed_primary, R.string.stop_probe_failed_secondary))
        },

        // S5 Done and the restart flow (D24, UX 4.8)
        Shot("done_home_on") { Setup(done()) },
        Shot("done_restart_recommended") { Setup(done(recommended = true)) },
        Shot("done_without_home") { Setup(done(off = 16, home = false)) },
        Shot("done_skipped_one") { Setup(done(off = 22, skipped = listOf("Play Games"))) },
        Shot("done_skipped_many") { Setup(done(off = 20, skipped = listOf("Play Games", "Mi Cast", "Mi Home"))) },
        // Issue #15: nothing to switch off and no home switch, on a TV we know and on one we don't.
        Shot("done_nothing_changed") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true)) },
        Shot("done_nothing_changed_unknown") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true, unknownModel = true)) },
        // 0.3.3: every switch-off stayed on (issue #10 on a TV without an exact list), and Stop here before anything changed.
        Shot("done_nothing_kept_on") { Setup(done(off = 0, home = false, skipped = listOf("PatchWall"), recommended = true), connected.copy(doneNothing = true, doneNothingWhy = NothingWhy.KEPT_ON, unknownModel = true)) },
        Shot("done_nothing_stopped") { Setup(done(off = 0, home = false, recommended = true), connected.copy(doneNothing = true, doneNothingWhy = NothingWhy.STOPPED)) },
        Shot("done_restarting") { Setup(done(restart = RestartState.Restarting(NOW))) },
        Shot("done_restarting_slow") { Setup(done(restart = RestartState.Restarting(NOW)), connected.copy(restartSlow = true)) },
        Shot("done_back_after_restart") { Setup(done(restart = RestartState.Back(72_000)), connected.copy(restartReenabled = 2)) },
        Shot("done_not_back") {
            val c = card(StopCase.NOT_BACK_AFTER_RESTART, R.string.stop_not_back_title, R.string.stop_not_back_body, R.string.stop_not_back_primary, R.string.stop_not_back_secondary)
            Setup(done(restart = RestartState.NotBack(300_000)), offline.copy(card = c.copy(body = c.body + "\n\n" + stringResource(R.string.stop_not_back_auto))))
        },
        Shot("done_finishing") { Setup(done(), connected.copy(finishing = Finishing.SWITCHING)) },
        Shot("done_finished") { Setup(done(), connected.copy(chip = ChipState.DEBUGGING_OFF, finishing = Finishing.DONE)) },
        // D7: "Keep debugging on" chosen, so Done says debugging stays on (baseline QA, 0.4.0).
        Shot("done_keep_debugging") { Setup(done(), connected.copy(keepsDebugging = true)) },
        // QA of 0.4.5: OK on the timed out card; the home screen is unchanged and Done says why.
        Shot("done_home_kept_timed_out") { Setup(done(off = 3, home = false), connected.copy(doneHomeKept = tv.cleanyour.core.engine.HomeOutcome.TIMED_OUT)) },
        // The Google Play build (STORES-PLAN 1): Done offers no card to add.
        Shot("play_done_home_on") { Setup(done(), connected.copy(addOns = false)) },
    )
}
