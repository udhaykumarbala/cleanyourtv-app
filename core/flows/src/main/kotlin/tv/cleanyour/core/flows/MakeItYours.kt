package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.HomeHelper
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.probe.LaunchableApps
import tv.cleanyour.core.spec.AppsSettings
import tv.cleanyour.core.spec.ClockFormats
import tv.cleanyour.core.spec.ConfigKeys
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LiveTvKeys
import tv.cleanyour.core.spec.ScreensaverSettings
import tv.cleanyour.core.spec.StartUpSettings
import tv.cleanyour.core.spec.TvSettings
import tv.cleanyour.core.spec.WallpaperSettings
import tv.cleanyour.core.spec.WidgetsSettings

// 0.5.0 Make it yours: the sections of the hub that are the TV's settings document (TvSettings, F0) rather than
// the look: Widgets, Apps, When the TV starts, Screensaver and the picture names. Every page edits one draft;
// "N changes not on the TV yet" counts the keys that differ from the TV's file, and one "Put it on the TV" sends
// them (PlanRequest.ApplySettings: config.json alone, the wallpaper stays). The look and the dock stay the Home
// screen machine's (Look sheet, Dock sheet), which the hub hosts beside this one.
//
// The draft holds each section as the file has it (null: not in the file, the launcher's own default), so a page
// that changes nothing writes nothing; the pages read `shown`, the same values with the launcher's defaults
// filled in, which is what the TV shows.

// Under the clock: one second date at a time (shamsi_date or lunar_date).
enum class SecondDate { NONE, SHAMSI, LUNAR }

// How the top buttons look: with names, round icons (Inputs and VPN too), or one menu behind a gear (top_menu).
enum class ButtonsLook { NAMES, ICONS, MENU }

// The top buttons, in the order the home screen draws them (activity_main.xml at d7b0d4a5). Settings always shows.
enum class TopButton { INPUTS, APPS, WIFI, VPN, REORDER, LIBRARY, SETTINGS }

// When the TV starts: Home, Live TV (boot_to_live_tv) or an app picked on the TV (open_on_start_choice).
enum class StartOn { HOME, LIVE_TV, APP }

// Behind the clock screensaver: Home's picture (clock_wallpaper), plain black (clock_glow off), or the
// launcher's own soft glow on black (neither: what a TV has until a choice is made).
enum class SaverBehind { PICTURE, BLACK, GLOW }

data class SettingsDraft(
    val widgets: WidgetsSettings = WidgetsSettings(),
    val apps: AppsSettings = AppsSettings(),
    val screensaver: ScreensaverSettings = ScreensaverSettings(),
    val startUp: StartUpSettings = StartUpSettings(),
    val wallpaper: WallpaperSettings = WallpaperSettings(),
) {
    // What the TV shows: every value, the launcher's own where the file has none.
    fun shown(): SettingsDraft = SettingsDraft(widgets.orDefaults(), apps.orDefaults(), screensaver.orDefaults(), startUp.orDefaults(), wallpaper.orDefaults())

    fun applyTo(s: TvSettings): TvSettings = s.withWidgets(widgets).withApps(apps).withScreensaver(screensaver).withStartUp(startUp).withWallpaper(wallpaper)

    companion object {
        fun of(s: TvSettings) = SettingsDraft(s.widgets, s.apps, s.screensaver, s.startUp, s.wallpaper)
    }
}

// The edits of the pages, pure, so they are tested on their own. Each sets only the keys its control is about.
object MakeItYoursEdits {
    const val MAX_TOP_APPS = 3
    // wifi_label_width when the round Wi-Fi button shows the network's name: room for about 14 characters.
    const val WIFI_NAME_WIDTH = 160
    val SAVER_MINUTES: List<Int> = listOf(5, 10, 15, 30)
    const val SAVER_DEFAULT_MINUTES = 10

    fun secondDate(w: WidgetsSettings): SecondDate = when {
        w.shamsiDate == true -> SecondDate.SHAMSI
        w.lunarDate == true -> SecondDate.LUNAR
        else -> SecondDate.NONE
    }

    fun withSecondDate(w: WidgetsSettings, d: SecondDate): WidgetsSettings =
        w.copy(shamsiDate = d == SecondDate.SHAMSI, lunarDate = d == SecondDate.LUNAR)

    fun buttonsLook(w: WidgetsSettings): ButtonsLook = when {
        w.topMenu == true -> ButtonsLook.MENU
        w.iconOnlyButtons == true -> ButtonsLook.ICONS
        else -> ButtonsLook.NAMES
    }

    // One menu leaves the icon keys as they are, as the launcher does (top_menu hides the buttons).
    fun withButtonsLook(w: WidgetsSettings, look: ButtonsLook): WidgetsSettings = when (look) {
        ButtonsLook.MENU -> w.copy(topMenu = true)
        ButtonsLook.NAMES -> w.copy(topMenu = false, iconOnlyButtons = false, iconOnlyInputs = false, iconOnlyVpn = false,
            wifiLabelWidth = w.wifiLabelWidth?.let { 0 })
        ButtonsLook.ICONS -> w.copy(topMenu = false, iconOnlyButtons = true, iconOnlyInputs = true, iconOnlyVpn = true)
    }

    // Whether a button shows, on the shown values (WidgetsSettings.orDefaults). The Library button needs the App
    // library (app_library), which is the Apps page's.
    fun shows(w: WidgetsSettings, apps: AppsSettings, b: TopButton): Boolean = when (b) {
        TopButton.INPUTS -> w.showInputsPill != false
        TopButton.APPS -> !w.topBarApps.isNullOrEmpty()
        TopButton.WIFI -> w.showNetworkPill != false
        TopButton.VPN -> w.vpnPill == true
        TopButton.REORDER -> w.reorderButton == true
        TopButton.LIBRARY -> w.libraryButton == true && apps.appLibrary == true
        TopButton.SETTINGS -> true
    }

    // `first`: the app Your apps starts with when switched on (the dock's first), so the button shows at once.
    fun withButton(w: WidgetsSettings, b: TopButton, on: Boolean, first: PackageName? = null): WidgetsSettings = when (b) {
        TopButton.INPUTS -> w.copy(showInputsPill = on)
        TopButton.APPS -> w.copy(topBarApps = if (on) (w.topBarApps.orEmpty().ifEmpty { listOfNotNull(first) }) else emptyList())
        TopButton.WIFI -> w.copy(showNetworkPill = on)
        TopButton.VPN -> w.copy(vpnPill = on)
        TopButton.REORDER -> w.copy(reorderButton = on)
        TopButton.LIBRARY -> w.copy(libraryButton = on)
        TopButton.SETTINGS -> w
    }

    // Up to MAX_TOP_APPS, in the order they were picked; a fourth is refused (the page says why).
    fun withTopApp(w: WidgetsSettings, pkg: PackageName, on: Boolean): WidgetsSettings {
        val l = w.topBarApps.orEmpty()
        return when {
            on && pkg in l -> w
            on && l.size >= MAX_TOP_APPS -> w
            on -> w.copy(topBarApps = l + pkg)
            else -> w.copy(topBarApps = l - pkg)
        }
    }

    // Green while online (wifi_status_color); the orange and red warnings need it, so off takes them off too.
    fun withWifiGreen(w: WidgetsSettings, on: Boolean): WidgetsSettings =
        if (on) w.copy(wifiStatusColor = true) else w.copy(wifiStatusColor = false, wifiStatusWarnings = w.wifiStatusWarnings?.let { false })

    fun withWifiName(w: WidgetsSettings, on: Boolean): WidgetsSettings = w.copy(wifiLabelWidth = if (on) WIFI_NAME_WIDTH else 0)

    // The Wi-Fi button opens the VPN app instead of Wi-Fi settings when vpn_app is set and there is no VPN button
    // (MainActivity.openNetworkPill); holding OK still opens Wi-Fi settings.
    fun wifiOpensVpn(w: WidgetsSettings): Boolean = w.vpnPill != true && !w.vpnApp.isNullOrEmpty()

    fun withWifiOpensVpn(w: WidgetsSettings, on: Boolean, app: PackageName?): WidgetsSettings =
        if (on) w.copy(vpnApp = app?.value ?: w.vpnApp) else w.copy(vpnApp = "")

    fun startOn(s: StartUpSettings): StartOn = when {
        s.bootToLiveTv == true -> StartOn.LIVE_TV
        s.openOnStartChoice == true -> StartOn.APP
        else -> StartOn.HOME
    }

    fun withStart(s: StartUpSettings, on: StartOn): StartUpSettings =
        s.copy(bootToLiveTv = on == StartOn.LIVE_TV, openOnStartChoice = on == StartOn.APP)

    fun saverOn(s: ScreensaverSettings): Boolean = (s.idleMinutes ?: 0) > 0

    // Switching the clock screensaver on starts it after 10 minutes over Home's picture, as the plan shows it.
    fun withSaver(s: ScreensaverSettings, clock: Boolean): ScreensaverSettings = when {
        !clock -> s.copy(idleMinutes = 0)
        saverOn(s) -> s
        else -> s.copy(idleMinutes = SAVER_DEFAULT_MINUTES, clockWallpaper = true)
    }

    fun behind(s: ScreensaverSettings): SaverBehind = when {
        s.clockWallpaper == true -> SaverBehind.PICTURE
        s.clockGlow == false -> SaverBehind.BLACK
        else -> SaverBehind.GLOW
    }

    fun withBehind(s: ScreensaverSettings, b: SaverBehind): ScreensaverSettings = when (b) {
        SaverBehind.PICTURE -> s.copy(clockWallpaper = true)
        SaverBehind.BLACK -> s.copy(clockWallpaper = false, clockGlow = false)
        SaverBehind.GLOW -> s.copy(clockWallpaper = false, clockGlow = true)
    }

    // The App library: app_library with its pill after the last row. Off also takes the Library button away.
    fun withLibrary(a: AppsSettings, on: Boolean): AppsSettings = a.copy(appLibrary = on, showLibraryPill = if (on) true else a.showLibraryPill)

    // Hidden apps: the TV's list (the launcher's own when the file has none) with the app in or out.
    fun withHidden(a: AppsSettings, pkg: PackageName, hidden: Boolean): AppsSettings {
        val l = a.hiddenApps ?: AppsSettings.DEFAULTS.hiddenApps!!
        return a.copy(hiddenApps = if (hidden) (if (pkg in l) l else l + pkg) else l - pkg)
    }

    // A tile's own name; blank goes back to the app's.
    fun withLabel(a: AppsSettings, pkg: PackageName, label: String): AppsSettings {
        val m = LinkedHashMap(a.appLabels.orEmpty())
        val t = label.trim().take(MAX_LABEL)
        if (t.isEmpty()) m.remove(pkg) else m[pkg] = t
        return a.copy(appLabels = m)
    }

    const val MAX_LABEL = 40
    const val MAX_NAME = 24
}

// What a page can do on this TV: its launcher reads the key (ConfigKeys.reads; not known is the bundled one).
data class MakeItYoursState(
    val tvName: String = "",
    val loading: Boolean = true,
    val versionCode: Long? = null,
    val draft: SettingsDraft = SettingsDraft(),
    val shown: SettingsDraft = SettingsDraft().shown(),
    val changes: Int = 0,                                  // keys that differ from the TV's file
    val apps: List<AppChoice> = emptyList(),              // the TV's apps by name, for the pickers
    val dock: List<AppChoice> = emptyList(),              // the dock and the rows under it, as the TV shows them
    val vpnApps: List<AppChoice> = emptyList(),           // the TV's apps that look like a VPN, for the VPN pickers
    val liveTv: String? = null,                           // the TV's Live TV app by name, when it has one
    val addOns: Boolean = true,                           // this build offers add-ons (cards); the Play build does not
    val retroOn: Boolean = false,                         // the Retro card shows: the add-on is on the TV and cards are on
    val captionsWork: Boolean = false,                    // the TV plays a picture collection, which names come from
    val greetingName: String = "",                        // the name typed for the greeting, kept while it is off
    val busy: Busy? = null,
    val sent: Boolean = false,                            // the last send reached the TV
    val failed: Boolean = false,                          // the last send did not
    val shot: ByteArray? = null,                          // the TV right after the send, when it gave one
    val canSend: Boolean = true,                          // the new home screen is on this TV
) {
    fun reads(key: String): Boolean = ConfigKeys.reads(key, versionCode)
    val secondDate: SecondDate get() = MakeItYoursEdits.secondDate(shown.widgets)
    val buttonsLook: ButtonsLook get() = MakeItYoursEdits.buttonsLook(shown.widgets)
    val startOn: StartOn get() = MakeItYoursEdits.startOn(shown.startUp)
    val saverOn: Boolean get() = MakeItYoursEdits.saverOn(shown.screensaver)
    val behind: SaverBehind get() = MakeItYoursEdits.behind(shown.screensaver)
    val wifiOpensVpn: Boolean get() = MakeItYoursEdits.wifiOpensVpn(shown.widgets)
    fun shows(b: TopButton): Boolean = MakeItYoursEdits.shows(shown.widgets, shown.apps, b)
    fun name(pkg: PackageName): String = (apps + dock).firstOrNull { it.pkg == pkg }?.name ?: pkg.value
}

sealed interface MakeItYoursIntent {
    // Widgets: the clock.
    data class Greeting(val on: Boolean) : MakeItYoursIntent
    data class GreetingName(val name: String) : MakeItYoursIntent   // "" for the greeting alone
    data class DateBelow(val on: Boolean) : MakeItYoursIntent
    data class BrightDate(val on: Boolean) : MakeItYoursIntent
    data class ClockFormat(val format: String) : MakeItYoursIntent  // ClockFormats
    // Under the clock.
    data class Second(val date: SecondDate) : MakeItYoursIntent
    data class Stats(val on: Boolean) : MakeItYoursIntent
    // The buttons.
    data class Look(val look: ButtonsLook) : MakeItYoursIntent
    data class Button(val button: TopButton, val on: Boolean) : MakeItYoursIntent
    data class TopApp(val pkg: PackageName, val on: Boolean) : MakeItYoursIntent
    data class WifiGreen(val on: Boolean) : MakeItYoursIntent
    data class WifiWarnings(val on: Boolean) : MakeItYoursIntent
    data class WifiName(val on: Boolean) : MakeItYoursIntent
    data class WifiOpensVpn(val on: Boolean) : MakeItYoursIntent
    data class VpnApp(val pkg: PackageName) : MakeItYoursIntent
    data class SettingsOpensHome(val on: Boolean) : MakeItYoursIntent
    // Apps.
    data class MoveWithRemote(val on: Boolean) : MakeItYoursIntent
    data class AppLibrary(val on: Boolean) : MakeItYoursIntent
    data class Hidden(val pkg: PackageName, val hidden: Boolean) : MakeItYoursIntent
    data class Label(val pkg: PackageName, val label: String) : MakeItYoursIntent
    // When the TV starts.
    data class Start(val on: StartOn) : MakeItYoursIntent
    // Screensaver.
    data class Saver(val clock: Boolean) : MakeItYoursIntent
    data class SaverAfter(val minutes: Int) : MakeItYoursIntent
    data class Behind(val behind: SaverBehind) : MakeItYoursIntent
    // Wallpaper: the picture names under the buttons.
    data class Captions(val on: Boolean) : MakeItYoursIntent
    // The draft.
    data object Send : MakeItYoursIntent
    data object Discard : MakeItYoursIntent
    data object Reload : MakeItYoursIntent        // after the look or the dock went to the TV from the hub
    data object DismissSent : MakeItYoursIntent
}

class MakeItYoursMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<MakeItYoursState, MakeItYoursIntent, ManageEffect>(MakeItYoursState(addOns = d.store.addOns), scope) {

    private val writer = TvWriter(d)
    private var base: TvSettings = TvSettings.seed(null, null)   // the TV's file as last read
    private var sending = false
    private class Loaded(val s: MakeItYoursState, val base: TvSettings)
    private data class BusyNow(val busy: Busy?)
    private data class SendResult(val ok: Boolean)
    private class Shot(val png: ByteArray?)

    init { scope.launch { load() } }

    override fun reduce(state: MakeItYoursState, event: Any): MakeItYoursState = when (event) {
        // The draft is counted against the file just read.
        is Loaded -> { base = event.base; edited(event.s, event.s.draft).copy(busy = state.busy, sent = state.sent, failed = state.failed, shot = state.shot) }
        is BusyNow -> state.copy(busy = event.busy)
        is SendResult -> state.copy(sent = event.ok, failed = !event.ok)
        is Shot -> state.copy(shot = event.png)
        MakeItYoursIntent.DismissSent -> state.copy(sent = false, failed = false, shot = null)
        MakeItYoursIntent.Discard -> edited(state, SettingsDraft.of(base))
        is MakeItYoursIntent -> if (state.busy != null || state.loading) state else edit(state, event)
        else -> state
    }

    private fun edit(state: MakeItYoursState, i: MakeItYoursIntent): MakeItYoursState {
        val e = MakeItYoursEdits
        val dr = state.draft
        val w = dr.widgets
        val next = when (i) {
            is MakeItYoursIntent.Greeting -> dr.copy(widgets = w.copy(showGreeting = i.on))
            is MakeItYoursIntent.GreetingName -> {
                val n = i.name.trim().take(MakeItYoursEdits.MAX_NAME)
                return edited(state.copy(greetingName = i.name.take(MakeItYoursEdits.MAX_NAME)), dr.copy(widgets = w.copy(greetingName = n)))
            }
            is MakeItYoursIntent.DateBelow -> dr.copy(widgets = w.copy(dateBelowClock = i.on))
            is MakeItYoursIntent.BrightDate -> dr.copy(widgets = w.copy(headerWhite = i.on))
            is MakeItYoursIntent.ClockFormat -> if (i.format in ClockFormats.ALL) dr.copy(widgets = w.copy(clockFormat = i.format)) else dr
            is MakeItYoursIntent.Second -> dr.copy(widgets = e.withSecondDate(w, i.date))
            is MakeItYoursIntent.Stats -> dr.copy(widgets = w.copy(showStats = i.on))
            is MakeItYoursIntent.Look -> dr.copy(widgets = e.withButtonsLook(w, i.look))
            is MakeItYoursIntent.Button -> {
                // The Library button needs the App library: switching it on here switches the library on too.
                val apps = if (i.button == TopButton.LIBRARY && i.on) e.withLibrary(dr.apps, true) else dr.apps
                dr.copy(widgets = e.withButton(w, i.button, i.on, state.dock.firstOrNull()?.pkg), apps = apps)
            }
            is MakeItYoursIntent.TopApp -> dr.copy(widgets = e.withTopApp(w, i.pkg, i.on))
            is MakeItYoursIntent.WifiGreen -> dr.copy(widgets = e.withWifiGreen(w, i.on))
            is MakeItYoursIntent.WifiWarnings -> dr.copy(widgets = w.copy(wifiStatusWarnings = i.on, wifiStatusColor = if (i.on) true else w.wifiStatusColor))
            is MakeItYoursIntent.WifiName -> dr.copy(widgets = e.withWifiName(w, i.on))
            is MakeItYoursIntent.WifiOpensVpn -> dr.copy(widgets = e.withWifiOpensVpn(w, i.on, state.vpnApps.firstOrNull()?.pkg))
            is MakeItYoursIntent.VpnApp -> dr.copy(widgets = w.copy(vpnApp = i.pkg.value))
            is MakeItYoursIntent.SettingsOpensHome -> dr.copy(widgets = w.copy(launcherSettingsButton = i.on))
            is MakeItYoursIntent.MoveWithRemote -> dr.copy(apps = dr.apps.copy(tileMenu = i.on))
            is MakeItYoursIntent.AppLibrary -> dr.copy(apps = e.withLibrary(dr.apps, i.on))
            is MakeItYoursIntent.Hidden -> dr.copy(apps = e.withHidden(dr.apps, i.pkg, i.hidden))
            is MakeItYoursIntent.Label -> dr.copy(apps = e.withLabel(dr.apps, i.pkg, i.label))
            is MakeItYoursIntent.Start -> dr.copy(startUp = e.withStart(dr.startUp, i.on))
            is MakeItYoursIntent.Saver -> dr.copy(screensaver = e.withSaver(dr.screensaver, i.clock))
            is MakeItYoursIntent.SaverAfter -> dr.copy(screensaver = dr.screensaver.copy(idleMinutes = i.minutes.coerceIn(1, 1440)))
            is MakeItYoursIntent.Behind -> dr.copy(screensaver = e.withBehind(dr.screensaver, i.behind))
            is MakeItYoursIntent.Captions -> dr.copy(wallpaper = dr.wallpaper.copy(captions = i.on))
            else -> return state
        }
        return edited(state, next)
    }

    // The draft and what it changes, counted against the TV's file.
    private fun edited(state: MakeItYoursState, draft: SettingsDraft): MakeItYoursState =
        state.copy(draft = draft, shown = draft.shown(), changes = changed(draft).size, sent = false, failed = false)

    private fun changed(draft: SettingsDraft): Set<String> = draft.applyTo(base).changedKeys(base)

    override fun handle(event: Any, before: MakeItYoursState, after: MakeItYoursState) {
        when (event) {
            MakeItYoursIntent.Send -> if (!sending && before.busy == null && after.changes > 0 && after.canSend) {
                sending = true
                scope.launch { try { push(after.draft) } finally { sending = false } }
            }
            MakeItYoursIntent.Reload -> if (before.busy == null) scope.launch { load(keep = after.draft) }
            else -> {}
        }
    }

    // `keep`: a draft to carry over (Reload after a look push): its changes go on top of the TV's new file.
    private suspend fun load(keep: SettingsDraft? = null) {
        val tv = d.tv(tvId) ?: return
        val snap = attempt { d.snapshots.latest(tvId) }.getOrNull()
        val packages = snap?.packages.orEmpty()
        val versionCode = packages[Launcher.pkg]?.versionCode
        val overlay = attempt { SettingsSeed.overlay(d, tvId, snap) }.getOrElse { snap?.launcherOverlay }
        val settings = TvSettings.seed(overlay, versionCode)
        val profile = attempt { d.profileOf(tv) }.getOrNull()
        val launchable = d.access.live(tvId)?.let { s -> (d.prober as? LaunchableApps)?.let { q -> attempt { q.launchable(s) }.getOrNull() } }
        val apps = (snap?.let { DockCandidates.of(it, launchable, profile, d.facts) } ?: emptySet())
            .map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }.sortedBy { it.name.lowercase() }
        val choice = attempt { d.homePrefs.load(tvId) }.getOrNull()
        val columns = choice?.columns ?: DockLayers.DEFAULT_COLUMNS
        val shownPkg = { p: PackageName -> snap == null || p in packages }
        val dock = DockLayers.decode(choice?.dock.orEmpty(), shownPkg, columns).map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }
        val live = snap?.let { LiveTvKeys.pick(profile, it.packages) }
        val retro = if (d.store.addOns) d.registry.module(RETRO_ID) else null
        val cards = attempt { Cards.current(d, tvId, choice, overlay) }.getOrNull() ?: Cards.MANAGE_DEFAULT
        val draft = keep?.let { k -> SettingsDraft.of(settings.withKeysOf(k.applyTo(base), k.applyTo(base).changedKeys(base))) } ?: SettingsDraft.of(settings)
        val ours = tv.launcher != null || (snap != null && (Launcher.pkg in packages || HomeHelper.listed(snap)))
        val s = MakeItYoursState(
            tvName = tv.nickname, loading = false, versionCode = versionCode,
            apps = apps, dock = dock, vpnApps = apps.filter { looksLikeVpn(it) },
            liveTv = live?.let { d.nameOf(it, packages[it]?.label) }, addOns = d.store.addOns,
            retroOn = retro != null && retro.pkg in packages && cards,
            captionsWork = (settings.doc.wallpaperRotationMinutes ?: 0) > 0,
            greetingName = settings.widgets.greetingName.orEmpty(), canSend = ours,
        )
        send(Loaded(s.copy(draft = draft), settings))
    }

    private suspend fun push(draft: SettingsDraft) {
        d.tv(tvId) ?: return
        val snap = attempt { d.snapshots.latest(tvId) }.getOrNull()
        // From the TV's file as it is now (a look may have gone since the page was read), with only the keys changed here.
        val overlay = attempt { SettingsSeed.overlay(d, tvId, snap) }.getOrElse { snap?.launcherOverlay }
        val now = TvSettings.seed(overlay, base.versionCode)
        val wanted = draft.applyTo(base)
        val next = now.withKeysOf(wanted, wanted.changedKeys(base))
        val look = attempt { Look.encode(d.encoder, d.blobs, next.pushDoc(), null) }.getOrNull()
            ?: run { send(SendResult(false)); effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false)); return }
        val r = writer.write(tvId, PlanRequest.ApplySettings(look.doc, look.config)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            rememberPushedLook(d, tvId, look)
            load()
            send(SendResult(true))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
            scope.launch { shot(r.finished.status) }
            return
        }
        send(SendResult(false))
        if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
    }

    private suspend fun shot(status: RunStatus) {
        if (status != RunStatus.COMPLETED && status != RunStatus.COMPLETED_WITH_SKIPS) return
        delay(HomeScreenMachine.LOOK_SHOT_DELAY_MS)
        val s = d.access.live(tvId) ?: return
        send(Shot(attempt { d.prober.screenshot(s) }.getOrNull()))
    }

    companion object {
        const val RETRO_ID = "retro"
        private val VPN_WORDS = listOf("vpn", "wireguard", "tailscale", "proton", "nord", "surfshark", "mullvad", "expressvpn", "windscribe", "zerotier", "openvpn")

        // A VPN app by its package or name (the pickers offer these first; any app can still be chosen elsewhere).
        fun looksLikeVpn(a: AppChoice): Boolean {
            val t = (a.pkg.value + " " + a.name).lowercase()
            return VPN_WORDS.any { it in t }
        }
    }
}
