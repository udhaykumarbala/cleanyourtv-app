package tv.cleanyour.core.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.cleanyour.core.model.PackageName

// 0.5.0 F0: one settings document per TV for the launcher's config.json. Every push (Look, Dock,
// Cards and modules, Setup, and the Customise sections) starts from the file the TV has now, changes
// only the keys of its own section, and sends the whole document, so no push wipes a key another
// section set. ConfigKeys says which section owns each key and which launcher versionCode first
// reads it.
//
// The interface the Customise sections build against:
//
//   TvSettings.seed(overlay, versionCode)  the document of the TV's config.json as it is now (the probed
//                                          overlay, or what this phone last pushed there), never from
//                                          scratch; no file, or one the launcher ignores whole, is empty.
//                                          versionCode: the TV's launcher, null when not known (bundled)
//   TvSettings.of(doc, versionCode)        the same from a doc
//   widgets, withWidgets(WidgetsSettings)         Widgets (0.5.0: was Clock and top bar)
//   apps, withApps(AppsSettings)                  Apps
//   screensaver, withScreensaver(...)             Screensaver
//   startUp, withStartUp(StartUpSettings)         When the TV starts
//   wallpaper, withWallpaper(WallpaperSettings)   Wallpapers
//   withSections(built, sections)          a doc built elsewhere for whole sections (LookKeys.build, a
//                                          theme, DockLayers): each key of `sections` takes built's
//                                          value, or goes when built has none. LOOK_PUSH are the sections
//                                          the look push (Look, Dock, Cards, Setup) writes
//   section(section)                       one section's keys as the document has them
//   doc                                    the whole document, every key (the preview reads it)
//   pushDoc(), encode()                    what a push sends: the whole document less the keys this TV's
//                                          launcher does not read (ConfigKeys.reads), so a TV on 2.7
//                                          never gets a 3.0 key
//
// A section's typed value says what the file says: null is "not in the file", which the launcher
// reads as its own default (DEFAULTS, config.xml at b06ab21), and a value the launcher would ignore
// (a wrong type, out of range) reads as null too. A with...() call writes only the keys whose value
// changed, so a key it did not change keeps its bytes, even one the launcher ignores. Values are
// brought into the launcher's rules rather than refused: strings trimmed, blank list entries dropped,
// numbers into range. A key no launcher we know reads is carried as the TV has it (ConfigCodec).
//
// Undo is not this document's: the planner records the probed overlay as the push's prior, as before.
class TvSettings private constructor(private val keys: Map<String, JsonElement>, val versionCode: Long?) {

    companion object {
        // The sections the look push writes whole: Look, Dock, Cards and modules, and Setup all send
        // LookKeys.build's doc (or a 0.4.0 theme's) with the dock and module_cards, and the Home
        // button helper's keys are put back by its own rule (HomeHelperKeys) on every push.
        val LOOK_PUSH: Set<SettingsSection> = setOf(SettingsSection.LOOK, SettingsSection.DOCK, SettingsSection.CARDS, SettingsSection.HOME_HELPER)

        fun seed(overlay: ByteArray?, versionCode: Long?): TvSettings =
            of(overlay?.let { (LauncherConfigCodec.decode(it) as? DecodeResult.Ok)?.doc } ?: LauncherConfigDoc(), versionCode)

        fun of(doc: LauncherConfigDoc, versionCode: Long?): TvSettings = TvSettings(keysOf(doc), versionCode?.takeIf { it > 0L })

        private fun keysOf(doc: LauncherConfigDoc): Map<String, JsonElement> =
            Json.parseToJsonElement(LauncherConfigCodec.encode(doc).decodeToString()).jsonObject

        private fun docOf(keys: Map<String, JsonElement>): LauncherConfigDoc =
            when (val r = LauncherConfigCodec.decode(JsonObject(keys).toString().encodeToByteArray())) {
                is DecodeResult.Ok -> r.doc
                is DecodeResult.Rejected -> error("the settings document does not decode: ${r.reason}")
            }
    }

    val doc: LauncherConfigDoc by lazy { docOf(keys) }

    fun pushDoc(): LauncherConfigDoc = docOf(keys.filterKeys { ConfigKeys.reads(it, versionCode) })

    fun encode(): ByteArray = LauncherConfigCodec.encode(pushDoc())

    fun section(section: SettingsSection): Map<String, JsonElement> = keys.filterKeys { ConfigKeys.of(it)?.owner == section }

    fun withSections(built: LauncherConfigDoc, sections: Set<SettingsSection>): TvSettings {
        val b = keysOf(built)
        val stray = b.keys.filter { ConfigKeys.of(it)?.owner !in sections }
        require(stray.isEmpty()) { "the push sets ${stray.joinToString()}, which no section in $sections owns" }
        val out = LinkedHashMap(keys)
        for (k in sections.flatMap(ConfigKeys::owned)) {
            val v = b[k]
            if (v == null) out.remove(k) else out[k] = v
        }
        return TvSettings(out, versionCode)
    }

    // 0.5.0: the look push on this document. `built`'s keys of `sections` go in as withSections writes them, and
    // its keys of `standIn` (WIDGETS: the buttons' look and the header rows a look still builds) only where the
    // document has none, so the TV's own value wins: a look push never changes what the Widgets page set, and a
    // TV with no file still gets the look's buttons, as before 0.5.0.
    fun withLook(built: LauncherConfigDoc, sections: Set<SettingsSection>, standIn: Set<SettingsSection>): TvSettings {
        val b = keysOf(built)
        val s = withSections(docOf(b.filterKeys { ConfigKeys.of(it)?.owner !in standIn }), sections)
        val out = LinkedHashMap(s.keys)
        for ((k, v) in b) if (ConfigKeys.of(k)?.owner in standIn && k !in out) out[k] = v
        return TvSettings(out, versionCode)
    }

    val widgets: WidgetsSettings get() = WidgetsSettings(
        showInputsPill = bool("show_inputs_pill"), showNetworkPill = bool("show_network_pill"),
        launcherSettingsButton = bool("launcher_settings_button"), greetingName = string("greeting_name"),
        shamsiDate = bool("shamsi_date"), lunarDate = bool("lunar_date"), lunarUtcOffset = int("lunar_utc_offset"),
        showStats = bool("show_stats"), vpnPill = bool("vpn_pill"), vpnApp = string("vpn_app"),
        topBarApps = packages("top_bar_apps"), wifiLabelWidth = int("wifi_label_width"),
        showGreeting = bool("show_greeting"), dateBelowClock = bool("date_below_clock"), headerWhite = bool("header_white"),
        clockFormat = clockFormat("clock_format"), topMenu = bool("top_menu"), iconOnlyButtons = bool("icon_only_buttons"),
        iconOnlyInputs = bool("icon_only_inputs"), iconOnlyVpn = bool("icon_only_vpn"), wifiStatusColor = bool("wifi_status_color"),
        wifiStatusWarnings = bool("wifi_status_warnings"), reorderButton = bool("reorder_button"), libraryButton = bool("library_button"),
    )

    fun withWidgets(t: WidgetsSettings): TvSettings = edit(SettingsSection.WIDGETS, widgets.json(), t.json())

    val apps: AppsSettings get() = AppsSettings(
        tileMenu = bool("tile_menu"), appLibrary = bool("app_library"),
        showLibraryPill = bool("show_library_pill"), hiddenApps = packages("hidden_apps"), appLabels = labels("app_labels"),
    )

    fun withApps(a: AppsSettings): TvSettings = edit(SettingsSection.APPS, apps.json(), a.json())

    val screensaver: ScreensaverSettings get() = ScreensaverSettings(
        idleMinutes = int("home_idle_clock_minutes"), clockWallpaper = bool("clock_wallpaper"),
        clockWallpaperDim = int("clock_wallpaper_dim"), clockGlow = bool("clock_glow"),
    )

    fun withScreensaver(s: ScreensaverSettings): TvSettings = edit(SettingsSection.SCREENSAVER, screensaver.json(), s.json())

    val startUp: StartUpSettings get() = StartUpSettings(bootToLiveTv = bool("boot_to_live_tv"), openOnStartChoice = bool("open_on_start_choice"))

    fun withStartUp(s: StartUpSettings): TvSettings = edit(SettingsSection.START_UP, startUp.json(), s.json())

    val wallpaper: WallpaperSettings get() = WallpaperSettings(captions = bool("wallpaper_captions"))

    fun withWallpaper(w: WallpaperSettings): TvSettings = edit(SettingsSection.WALLPAPER, wallpaper.json(), w.json())

    // 0.5.0 Make it yours: the keys whose value differs between this document and `other` (one has it and the
    // other not, or both with other values), every section's.
    fun changedKeys(other: TvSettings): Set<String> = (keys.keys + other.keys.keys).filterTo(LinkedHashSet()) { keys[it] != other.keys[it] }

    // 0.5.0 Make it yours: this document with `names` as `from` has them (a name `from` has not goes). A send
    // starts from the TV's file as it is then and takes only the keys the person changed (changedKeys), so a
    // key another push set since the page was read stays.
    fun withKeysOf(from: TvSettings, names: Set<String>): TvSettings {
        val out = LinkedHashMap(keys)
        for (k in names) { val v = from.keys[k]; if (v == null) out.remove(k) else out[k] = v }
        return TvSettings(out, versionCode)
    }

    // The keys of `section` whose value differs between `before` and `after`; null removes the key.
    private fun edit(section: SettingsSection, before: Map<String, JsonElement?>, after: Map<String, JsonElement?>): TvSettings {
        val owned = ConfigKeys.owned(section).toSet()
        check(after.keys == owned) { "the $section model does not cover its keys: ${after.keys} against $owned" }
        val out = LinkedHashMap(keys)
        for ((k, v) in after) if (before[k] != v) { if (v == null) out.remove(k) else out[k] = v }
        return TvSettings(out, versionCode)
    }

    // The readers, as Config.java's helpers read a value, but null where the launcher keeps its default.
    private fun bool(key: String): Boolean? = (keys[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
        when (it) { "true" -> true; "false" -> false; else -> null }
    }

    private fun int(key: String): Int? {
        val k = ConfigKeys.of(key)!!
        return LauncherKeys.intValue(keys[key])?.takeIf { it in k.min..k.max }
    }

    // clock_format (3.3): "12", "24" or "system"; the launcher treats any other string as the TV's own (ClockFormat).
    private fun clockFormat(key: String): String? = string(key)?.takeIf { it in ClockFormats.ALL }

    private fun string(key: String): String? = (keys[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.javaTrim()

    private fun strings(key: String): List<String>? = (keys[key] as? JsonArray)?.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.javaTrim()?.takeIf { it.isNotEmpty() }
    }

    private fun packages(key: String): List<PackageName>? = strings(key)?.mapNotNull { runCatching { PackageName(it) }.getOrNull() }

    private fun labels(key: String): Map<PackageName, String>? = strings(key)?.mapNotNull { e ->
        ConfigCodecImpl.packageMapEntry(e)?.let { (p, label) -> runCatching { PackageName(p) to label }.getOrNull() }
    }?.toMap()
}

// The sections of the settings document, each the only writer of its keys (ConfigKeys.owner). The
// look push writes LOOK, DOCK, CARDS and HOME_HELPER (TvSettings.LOOK_PUSH); the rest have their
// own typed value in TvSettings, but INPUTS, whose one key the look push's Live TV rule sets
// (LiveTvKeys), until the Inputs and Live TV section has its own.
enum class SettingsSection {
    LOOK,          // Look: style, details, picture, movement, apps in a row, More options (LookKeys.build)
    DOCK,          // the dock and the rows under it (DockLayers)
    CARDS,         // Cards and modules: module_cards (Cards)
    HOME_HELPER,   // the Home button helper's redirect and key filter (HomeHelperKeys)
    INPUTS,        // Inputs and Live TV: live_tv_package (LiveTvKeys)
    APPS,          // Apps: the App library, hidden apps, tile names, moving apps with the remote
    WIDGETS,       // Widgets (0.5.0, was Clock and top bar): the clock, under it, the buttons and their look
    SCREENSAVER,   // the clock screensaver
    START_UP,      // When the TV starts
    WALLPAPER,     // Wallpapers
}

// Widgets (0.5.0, was Clock and top bar): the TV's places at the top of Home and what is in them. The clock
// (greeting, name, date under it, brighter date, 12 or 24 hours), under the clock (second date, TV health),
// the buttons (which show, their look, the Wi-Fi and VPN buttons, what Settings opens). 0.5.0 moved the
// top bar's look and the header rows here from LOOK, and reorder_button and library_button from APPS, so
// a look push no longer changes them on a TV whose file has them (LookCarry.merge).
data class WidgetsSettings(
    val showInputsPill: Boolean? = null,          // show_inputs_pill (2.6): the Inputs pill
    val showNetworkPill: Boolean? = null,         // show_network_pill (2.6): the Wi-Fi or Ethernet pill
    val launcherSettingsButton: Boolean? = null,  // launcher_settings_button (2.7): Launcher settings instead of TV Settings
    val greetingName: String? = null,             // greeting_name (2.4): "Good evening, {name}"
    val shamsiDate: Boolean? = null,              // shamsi_date (2.0)
    val lunarDate: Boolean? = null,               // lunar_date (3.0)
    val lunarUtcOffset: Int? = null,              // lunar_utc_offset (3.0, config.json only): -1 (the locale's) to 14
    val showStats: Boolean? = null,               // show_stats (2.4): CPU, memory and storage under the clock
    val vpnPill: Boolean? = null,                 // vpn_pill (3.0 at b06ab21): the VPN as its own pill
    val vpnApp: String? = null,                   // vpn_app (2.0): the VPN app's package, "" for none
    val topBarApps: List<PackageName>? = null,    // top_bar_apps (3.0): round app buttons; they also leave the grid
    val wifiLabelWidth: Int? = null,              // wifi_label_width (2.6): 0 to 300 dp of network name on a round Wi-Fi button
    val showGreeting: Boolean? = null,            // show_greeting (3.0): the greeting above the clock
    val dateBelowClock: Boolean? = null,          // date_below_clock (3.0): the date under the clock, not beside it
    val headerWhite: Boolean? = null,             // header_white (3.0): the date full white
    val clockFormat: String? = null,              // clock_format (3.3): "12", "24" or "system"
    val topMenu: Boolean? = null,                 // top_menu (2.7): one gear button with a menu
    val iconOnlyButtons: Boolean? = null,         // icon_only_buttons (2.6): round buttons
    val iconOnlyInputs: Boolean? = null,          // icon_only_inputs (2.7): a round Inputs button too
    val iconOnlyVpn: Boolean? = null,             // icon_only_vpn (3.2): with vpn_pill, a round lock button
    val wifiStatusColor: Boolean? = null,         // wifi_status_color (2.7): the Wi-Fi icon green while online
    val wifiStatusWarnings: Boolean? = null,      // wifi_status_warnings (3.0): orange without internet, red offline
    val reorderButton: Boolean? = null,           // reorder_button (2.4): the move button in the top bar
    val libraryButton: Boolean? = null,           // library_button (2.6): the library as a top bar button
) {
    companion object {
        // config.xml at d7b0d4a5 (ConfigKeysTest reads it).
        val DEFAULTS = WidgetsSettings(showInputsPill = true, showNetworkPill = true, launcherSettingsButton = false,
            greetingName = "", shamsiDate = false, lunarDate = false, lunarUtcOffset = -1, showStats = false, vpnPill = false,
            vpnApp = "", topBarApps = emptyList(), wifiLabelWidth = 0, showGreeting = true, dateBelowClock = false, headerWhite = false,
            clockFormat = ClockFormats.SYSTEM, topMenu = false, iconOnlyButtons = false, iconOnlyInputs = false, iconOnlyVpn = false,
            wifiStatusColor = false, wifiStatusWarnings = false, reorderButton = false, libraryButton = false)
    }

    // Every value of this, else the launcher's own (DEFAULTS): what the TV shows.
    fun orDefaults(): WidgetsSettings = WidgetsSettings(
        showInputsPill ?: DEFAULTS.showInputsPill, showNetworkPill ?: DEFAULTS.showNetworkPill, launcherSettingsButton ?: DEFAULTS.launcherSettingsButton,
        greetingName ?: DEFAULTS.greetingName, shamsiDate ?: DEFAULTS.shamsiDate, lunarDate ?: DEFAULTS.lunarDate, lunarUtcOffset ?: DEFAULTS.lunarUtcOffset,
        showStats ?: DEFAULTS.showStats, vpnPill ?: DEFAULTS.vpnPill, vpnApp ?: DEFAULTS.vpnApp, topBarApps ?: DEFAULTS.topBarApps,
        wifiLabelWidth ?: DEFAULTS.wifiLabelWidth, showGreeting ?: DEFAULTS.showGreeting, dateBelowClock ?: DEFAULTS.dateBelowClock,
        headerWhite ?: DEFAULTS.headerWhite, clockFormat ?: DEFAULTS.clockFormat, topMenu ?: DEFAULTS.topMenu, iconOnlyButtons ?: DEFAULTS.iconOnlyButtons,
        iconOnlyInputs ?: DEFAULTS.iconOnlyInputs, iconOnlyVpn ?: DEFAULTS.iconOnlyVpn, wifiStatusColor ?: DEFAULTS.wifiStatusColor,
        wifiStatusWarnings ?: DEFAULTS.wifiStatusWarnings, reorderButton ?: DEFAULTS.reorderButton, libraryButton ?: DEFAULTS.libraryButton,
    )

    internal fun json(): Map<String, JsonElement?> = linkedMapOf(
        "show_inputs_pill" to showInputsPill.element(), "show_network_pill" to showNetworkPill.element(),
        "launcher_settings_button" to launcherSettingsButton.element(), "greeting_name" to greetingName.element(),
        "shamsi_date" to shamsiDate.element(), "lunar_date" to lunarDate.element(), "lunar_utc_offset" to lunarUtcOffset.element("lunar_utc_offset"),
        "show_stats" to showStats.element(), "vpn_pill" to vpnPill.element(), "vpn_app" to vpnApp.element(),
        "top_bar_apps" to topBarApps.element(), "wifi_label_width" to wifiLabelWidth.element("wifi_label_width"),
        "show_greeting" to showGreeting.element(), "date_below_clock" to dateBelowClock.element(), "header_white" to headerWhite.element(),
        "clock_format" to clockFormat?.takeIf { it in ClockFormats.ALL }.element(), "top_menu" to topMenu.element(),
        "icon_only_buttons" to iconOnlyButtons.element(), "icon_only_inputs" to iconOnlyInputs.element(), "icon_only_vpn" to iconOnlyVpn.element(),
        "wifi_status_color" to wifiStatusColor.element(), "wifi_status_warnings" to wifiStatusWarnings.element(),
        "reorder_button" to reorderButton.element(), "library_button" to libraryButton.element(),
    )
}

// clock_format's values (launcher 3.3, ClockFormat): "12" and "24" set the launcher's clocks whatever the TV
// says; "system" (the default) follows the TV.
object ClockFormats {
    const val SYSTEM = "system"
    const val H12 = "12"
    const val H24 = "24"
    val ALL: Set<String> = setOf(SYSTEM, H12, H24)
}

// Apps.
data class AppsSettings(
    val tileMenu: Boolean? = null,                     // tile_menu (2.7): hold OK opens Move, Remove from Home, App info (on by default since 3.3)
    val appLibrary: Boolean? = null,                   // app_library (2.4)
    val showLibraryPill: Boolean? = null,              // show_library_pill (3.0): the pill after the last row
    val hiddenApps: List<PackageName>? = null,         // hidden_apps (2.0): off the grid, and out of the dock
    val appLabels: Map<PackageName, String>? = null,   // app_labels (2.5): a tile's own name
) {
    companion object {
        // config.xml at d7b0d4a5 (ConfigKeysTest reads it).
        val DEFAULTS = AppsSettings(tileMenu = true, appLibrary = false, showLibraryPill = true,
            hiddenApps = ConfigCodecImpl.BUILT_IN.hiddenApps.map(::PackageName), appLabels = emptyMap())
    }

    fun orDefaults(): AppsSettings = AppsSettings(tileMenu ?: DEFAULTS.tileMenu, appLibrary ?: DEFAULTS.appLibrary,
        showLibraryPill ?: DEFAULTS.showLibraryPill, hiddenApps ?: DEFAULTS.hiddenApps, appLabels ?: DEFAULTS.appLabels)

    internal fun json(): Map<String, JsonElement?> = linkedMapOf(
        "tile_menu" to tileMenu.element(), "app_library" to appLibrary.element(),
        "show_library_pill" to showLibraryPill.element(), "hidden_apps" to hiddenApps.element(),
        "app_labels" to appLabels?.mapNotNull { (p, label) -> label.javaTrim().takeIf { it.isNotEmpty() }?.let { "${p.value}=$it" } }
            ?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) },
    )
}

// The clock screensaver.
data class ScreensaverSettings(
    val idleMinutes: Int? = null,          // home_idle_clock_minutes (2.6): 0 (off) to 1440 minutes on Home
    val clockWallpaper: Boolean? = null,   // clock_wallpaper (3.0): over Home's wallpaper instead of black
    val clockWallpaperDim: Int? = null,    // clock_wallpaper_dim (3.0): 0 to 90 %
    val clockGlow: Boolean? = null,        // clock_glow (3.0): the coloured glow behind the clock
) {
    companion object {
        // config.xml at b06ab21 (ConfigKeysTest reads it).
        val DEFAULTS = ScreensaverSettings(idleMinutes = 0, clockWallpaper = false, clockWallpaperDim = 50, clockGlow = true)
    }

    fun orDefaults(): ScreensaverSettings = ScreensaverSettings(idleMinutes ?: DEFAULTS.idleMinutes, clockWallpaper ?: DEFAULTS.clockWallpaper,
        clockWallpaperDim ?: DEFAULTS.clockWallpaperDim, clockGlow ?: DEFAULTS.clockGlow)

    internal fun json(): Map<String, JsonElement?> = linkedMapOf(
        "home_idle_clock_minutes" to idleMinutes.element("home_idle_clock_minutes"), "clock_wallpaper" to clockWallpaper.element(),
        "clock_wallpaper_dim" to clockWallpaperDim.element("clock_wallpaper_dim"), "clock_glow" to clockGlow.element(),
    )
}

// When the TV starts.
data class StartUpSettings(
    val bootToLiveTv: Boolean? = null,        // boot_to_live_tv (2.6): Live TV the first time Home comes up after power-on
    val openOnStartChoice: Boolean? = null,   // open_on_start_choice (3.0): an app picked in Launcher settings on the TV opens instead
) {
    companion object { val DEFAULTS = StartUpSettings(bootToLiveTv = false, openOnStartChoice = false) }

    fun orDefaults(): StartUpSettings = StartUpSettings(bootToLiveTv ?: DEFAULTS.bootToLiveTv, openOnStartChoice ?: DEFAULTS.openOnStartChoice)

    internal fun json(): Map<String, JsonElement?> = linkedMapOf("boot_to_live_tv" to bootToLiveTv.element(),
        "open_on_start_choice" to openOnStartChoice.element())
}

// Wallpapers.
data class WallpaperSettings(
    val captions: Boolean? = null,   // wallpaper_captions (2.6): each rotating picture's name from captions.txt
) {
    companion object { val DEFAULTS = WallpaperSettings(captions = false) }

    fun orDefaults(): WallpaperSettings = WallpaperSettings(captions ?: DEFAULTS.captions)

    internal fun json(): Map<String, JsonElement?> = linkedMapOf("wallpaper_captions" to captions.element())
}

private fun Boolean?.element(): JsonElement? = this?.let { JsonPrimitive(it) }

private fun String?.element(): JsonElement? = this?.let { JsonPrimitive(it.javaTrim()) }

private fun Int?.element(key: String): JsonElement? = this?.let { v -> ConfigKeys.of(key)!!.let { JsonPrimitive(v.coerceIn(it.min, it.max)) } }

private fun List<PackageName>?.element(): JsonElement? = this?.let { l -> JsonArray(l.map { JsonPrimitive(it.value) }) }
