package tv.cleanyour.core.spec

import tv.cleanyour.core.spec.ConfigKeyType.BOOL
import tv.cleanyour.core.spec.ConfigKeyType.COLOR
import tv.cleanyour.core.spec.ConfigKeyType.INT
import tv.cleanyour.core.spec.ConfigKeyType.INT_LIST
import tv.cleanyour.core.spec.ConfigKeyType.OBJECT
import tv.cleanyour.core.spec.ConfigKeyType.RACES
import tv.cleanyour.core.spec.ConfigKeyType.STRING
import tv.cleanyour.core.spec.ConfigKeyType.STRING_LIST

// 0.5.0 F0: every key the bundled launcher 3.0 (b06ab21) reads from config.json, in Config.load's
// order, with how it reads it, the lowest launcher versionCode that reads it, and the one section of
// the settings document (TvSettings) that writes it. Read off the vendored readers, and held to them
// by ConfigKeysTest: a key with `since` 2 is read by 2.0 (1bbf0c9), 6 or lower by 2.5 (v2.5.0), 8 or
// lower by 2.7 (v2.7.0), and every key by 3.0. The versionCodes of the upstream tags: v2.2.0 3,
// v2.3.0 4, v2.4.0 5, v2.5.0 6, v2.6.0 7, v2.7.0 8, v3.0.0 and b06ab21 9 (1bbf0c9, between v2.1.0
// and v2.2.0, is 2; v2.1.0 reads no config.json at all). vpn_pill, input_sources, locale,
// home_long_press_home and power_on_home_always came with b06ab21, after the v3.0.0 tag, under the
// same versionCode 9: a TV on the v3.0.0 release ignores them, which no versionCode can tell.
//
// 0.5.0: launcher 3.3 (d7b0d4a5, versionCode 12), bundled since 0.4.9-beta1, reads 11 more keys: v3.1.0
// (versionCode 10) added inputs_connected_only, v3.2.0 (11) open_on_start_network_wait, icon_only_vpn,
// library_excluded_apps, game_day and seasonal_skin, v3.3.0 (12) open_on_start_on_wake, back_opens_clock,
// glow_snap and clock_format, and d7b0d4a5 (still 12) inputs_cec_state_only. The vendored v30 reader is that
// launcher now. The top bar's look and the header rows (icon_only_buttons, icon_only_inputs, top_menu,
// wifi_status_color, show_greeting, date_below_clock, header_white, wifi_status_warnings) moved from LOOK to
// WIDGETS, with reorder_button and library_button from APPS: the Widgets page is their one home.
//
// `owner` null: no section writes the key; the document keeps it as the TV has it.
enum class ConfigKeyType {
    BOOL,          // Config.bool: a JSON true or false
    INT,           // Config.integer: a number, min to max
    STRING,        // Config.string: a string, trimmed
    COLOR,         // Config.color: a colour Color.parseColor reads
    STRING_LIST,   // Config.stringList: a list of strings, trimmed, blanks dropped
    INT_LIST,      // Config.intArray: a list of numbers of 1 or more, all or nothing
    OBJECT,        // wallpaper_moods: an object of lists
    RACES,         // race_calendar: a list of races (Config.raceCalendar)
}

data class ConfigKey(val name: String, val type: ConfigKeyType, val since: Long, val owner: SettingsSection?,
    val min: Int = Int.MIN_VALUE, val max: Int = Int.MAX_VALUE)

object ConfigKeys {
    const val V20 = 2L
    const val V23 = 4L
    const val V24 = 5L
    const val V25 = 6L
    const val V26 = 7L
    const val V27 = LookGates.FIRST_27_VERSION_CODE
    const val V30 = LookGates.FIRST_30_VERSION_CODE
    const val V31 = 10L
    const val V32 = 11L
    const val V33 = 12L

    private fun key(name: String, type: ConfigKeyType, since: Long, owner: SettingsSection?, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) =
        ConfigKey(name, type, since, owner, min, max)

    val ALL: List<ConfigKey> = listOf(
        key("dock_apps", STRING_LIST, V20, SettingsSection.DOCK),
        key("fill_dock", BOOL, V24, SettingsSection.DOCK),
        key("hidden_apps", STRING_LIST, V20, SettingsSection.APPS),
        key("library_apps", STRING_LIST, V27, null),
        key("library_excluded_apps", STRING_LIST, V32, null),
        key("live_tv_package", STRING, V20, SettingsSection.INPUTS),
        key("live_tv_action", STRING, V20, null),
        key("live_tv_input_id", STRING, V26, null),
        key("launch_actions", STRING_LIST, V25, null),
        key("app_labels", STRING_LIST, V25, SettingsSection.APPS),
        key("app_banners", STRING_LIST, V25, null),
        key("source_panel_service", STRING, V20, null),
        key("source_panel_action", STRING, V20, null),
        key("source_panel_extra", STRING, V26, null),
        key("coocaa_sources", BOOL, V26, null),
        key("input_action", STRING, V20, null),
        key("input_sources", STRING_LIST, V30, null),
        key("input_uri_scheme", STRING, V26, null),
        key("columns", INT, V20, SettingsSection.LOOK, 1, 8),
        key("rows_below_dock", INT, V20, SettingsSection.DOCK, 0, 3),
        key("animations", BOOL, V24, SettingsSection.LOOK),
        key("advanced_effects", BOOL, V24, SettingsSection.LOOK),
        key("shamsi_date", BOOL, V20, SettingsSection.WIDGETS),
        key("lunar_date", BOOL, V30, SettingsSection.WIDGETS),
        key("lunar_utc_offset", INT, V30, SettingsSection.WIDGETS, -1, 14),
        key("greeting_name", STRING, V24, SettingsSection.WIDGETS),
        key("module_cards", BOOL, V20, SettingsSection.CARDS),
        key("reduced_motion", BOOL, V23, SettingsSection.LOOK),
        key("boot_to_live_tv", BOOL, V26, SettingsSection.START_UP),
        key("open_on_start_choice", BOOL, V30, SettingsSection.START_UP),
        key("open_on_start_network_wait", INT, V32, null, 0, 600),
        key("open_on_start_on_wake", BOOL, V33, null),
        key("dock_title", BOOL, V24, SettingsSection.LOOK),
        key("dock_row", BOOL, V24, SettingsSection.LOOK),
        key("top_menu", BOOL, V27, SettingsSection.WIDGETS),
        key("launcher_settings_button", BOOL, V27, SettingsSection.WIDGETS),
        key("reorder_button", BOOL, V24, SettingsSection.WIDGETS),
        key("show_network_pill", BOOL, V26, SettingsSection.WIDGETS),
        key("show_inputs_pill", BOOL, V26, SettingsSection.WIDGETS),
        key("icon_only_buttons", BOOL, V26, SettingsSection.WIDGETS),
        key("icon_only_inputs", BOOL, V27, SettingsSection.WIDGETS),
        key("icon_only_vpn", BOOL, V32, SettingsSection.WIDGETS),
        key("back_opens_clock", BOOL, V33, null),
        key("glow_snap", BOOL, V33, null),
        key("library_button", BOOL, V26, SettingsSection.WIDGETS),
        key("show_library_pill", BOOL, V30, SettingsSection.APPS),
        key("wifi_label_width", INT, V26, SettingsSection.WIDGETS, 0, 300),
        key("always_show_names", BOOL, V26, SettingsSection.LOOK),
        key("wallpaper_true_color", BOOL, V26, null),
        key("focus_overshoot", BOOL, V26, SettingsSection.LOOK),
        key("wallpaper_captions", BOOL, V26, SettingsSection.WALLPAPER),
        key("tile_menu", BOOL, V27, SettingsSection.APPS),
        key("vpn_app", STRING, V20, SettingsSection.WIDGETS),
        key("show_home_settings_shortcut", BOOL, V20, null),
        key("show_stats", BOOL, V24, SettingsSection.WIDGETS),
        key("game_day", STRING, V32, null),
        key("app_library", BOOL, V24, SettingsSection.APPS),
        key("app_library_selection", BOOL, V27, null),
        key("library_ok_opens", BOOL, V30, null),
        key("uniform_tiles", BOOL, V30, SettingsSection.LOOK),
        key("background_color", COLOR, V20, SettingsSection.LOOK),
        key("sky_intensity", INT, V24, SettingsSection.LOOK, 0, 100),
        key("settings_key_keycodes", INT_LIST, V24, null),
        key("settings_key_panel", BOOL, V24, null),
        key("restore_key_services", BOOL, V27, null),
        key("input_key_keycodes", INT_LIST, V25, null),
        key("handle_view_inputs", BOOL, V26, null),
        key("app_keys", STRING_LIST, V25, null),
        key("stock_home_redirect", STRING, V26, SettingsSection.HOME_HELPER),
        key("cinematic_home", BOOL, V25, SettingsSection.LOOK),
        key("ambient_glow", BOOL, V26, SettingsSection.LOOK),
        key("app_glow", BOOL, V26, SettingsSection.LOOK),
        key("fade_rows", BOOL, V27, null),
        key("home_idle_clock_minutes", INT, V26, SettingsSection.SCREENSAVER, 0, 1440),
        key("key_filter", BOOL, V26, SettingsSection.HOME_HELPER),
        key("gamepad_passthrough", BOOL, V27, null),
        key("consume_volume_keys", BOOL, V30, null),
        key("power_on_home", BOOL, V30, null),
        key("home_long_press_home", BOOL, V30, null),
        key("power_on_home_always", BOOL, V30, null),
        key("inputs_connected_only", BOOL, V31, null),
        key("inputs_cec_state_only", BOOL, V33, null),
        key("clock_format", STRING, V33, SettingsSection.WIDGETS),
        key("dock_glass", BOOL, V26, SettingsSection.LOOK),
        key("dock_even_padding", BOOL, V27, SettingsSection.LOOK),
        key("dock_band", BOOL, V30, SettingsSection.LOOK),
        key("cinematic_dots", BOOL, V27, null),
        key("cinematic_white_focus", BOOL, V27, SettingsSection.LOOK),
        key("wifi_status_color", BOOL, V27, SettingsSection.WIDGETS),
        key("cinematic_art", INT, V27, SettingsSection.LOOK, 0, 100),
        key("cinematic_scrim", INT, V27, SettingsSection.LOOK, 0, 100),
        key("wallpaper_moods", OBJECT, V27, null),
        key("wifi_status_warnings", BOOL, V30, SettingsSection.WIDGETS),
        key("date_below_clock", BOOL, V30, SettingsSection.WIDGETS),
        key("vpn_pill", BOOL, V30, SettingsSection.WIDGETS),
        key("locale", STRING, V30, null),
        key("top_bar_apps", STRING_LIST, V30, SettingsSection.WIDGETS),
        key("show_greeting", BOOL, V30, SettingsSection.WIDGETS),
        key("header_white", BOOL, V30, SettingsSection.WIDGETS),
        key("clock_wallpaper", BOOL, V30, SettingsSection.SCREENSAVER),
        key("clock_wallpaper_dim", INT, V30, SettingsSection.SCREENSAVER, 0, 90),
        key("clock_glow", BOOL, V30, SettingsSection.SCREENSAVER),
        key("single_row", BOOL, V30, SettingsSection.LOOK),
        key("dock_band_all", BOOL, V30, SettingsSection.LOOK),
        key("glow_style", STRING, V26, SettingsSection.LOOK),
        key("glass_style", STRING, V30, SettingsSection.LOOK),
        key("wallpaper", BOOL, V20, SettingsSection.LOOK),
        key("wallpaper_rotation_minutes", INT, V25, SettingsSection.LOOK, 0, 1440),
        key("wallpaper_dim", INT, V25, SettingsSection.LOOK, 0, 90),
        key("dock_opacity", INT, V26, SettingsSection.LOOK, 30, 100),
        key("wallpaper_order", STRING, V26, null),
        key("festival", STRING, V24, SettingsSection.LOOK),
        key("festival_until", STRING, V24, SettingsSection.LOOK),
        key("seasonal_skin", STRING, V32, null),
        key("greeting", STRING, V24, SettingsSection.LOOK),
        key("accent_color", STRING, V24, SettingsSection.LOOK),
        key("race_calendar", RACES, V25, null),
    )

    private val byName: Map<String, ConfigKey> = ALL.associateBy { it.name }

    fun of(name: String): ConfigKey? = byName[name]

    // The keys a section writes, in Config.load's order.
    fun owned(section: SettingsSection): List<String> = ALL.filter { it.owner == section }.map { it.name }

    // Whether a launcher with this versionCode reads the key. Not known (null, or 0 and below when the
    // probe could not read it) is the launcher this app bundles, which reads them all (the LookGates
    // rule). A key no launcher we know reads (a newer launcher's, or a typo) is not ours to judge:
    // it is kept.
    fun reads(name: String, versionCode: Long?): Boolean {
        val k = byName[name] ?: return true
        return versionCode == null || versionCode <= 0L || versionCode >= k.since
    }
}
