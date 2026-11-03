package tv.cleanyour.core.spec

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 0.5.0 F0: the key table (ConfigKeys) against the vendored readers: every key launcher 3.0 reads,
// read the way the table says, first read by the launcher the table says; and one owner per key.
class ConfigKeysTest {
    private fun reader(version: String) = File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/$version/Config.java").readText()

    // The keys a reader takes, found as LauncherConfigCodecTest.everyKeyALauncherReadsIsKnown finds them, a key read
    // through a String constant of Config (3.3's CLOCK_FORMAT_KEY) as its literal.
    private fun keys(version: String): Set<String> {
        val text = reader(version)
        val constants = Regex("static final String ([A-Z_0-9]+) = \"([a-z_0-9]+)\";").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        return (Regex("\\((?:json|o), \"([a-z_0-9]+)\"").findAll(text).map { it.groupValues[1] } +
            Regex("\\b(?:json|o)\\.(?:opt\\w*|get\\w*|has|isNull)\\(\"([a-z_0-9]+)\"").findAll(text).map { it.groupValues[1] } +
            Regex("\\((?:json|o), ([A-Z_0-9]+)[,)]").findAll(text).mapNotNull { constants[it.groupValues[1]] }).toSet()
    }

    @Test fun theTableIsEveryKeyLauncher30Reads() {
        val names = ConfigKeys.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size, "a key twice")
        assertEquals(keys("v30"), names.toSet())
        val codec = ConfigCodecImpl.MODELLED + ConfigCodecImpl.CARRIED_27 + ConfigCodecImpl.CARRIED_30 + ConfigCodecImpl.CARRIED_33 + ConfigCodecImpl.CARRIED_READ
        assertEquals(codec, names.toSet(), "the codec knows the same keys")
    }

    // `since` against the four vendored launchers: 2.0 (1bbf0c9, versionCode 2), 2.5 (6), 2.7 (8), 3.0 (9).
    @Test fun sinceIsTheFirstLauncherThatReadsTheKey() {
        val v20 = keys("previous"); val v25 = keys("pinned"); val v27 = keys("v27")
        for (k in ConfigKeys.ALL) {
            assertEquals(k.name in v20, k.since <= ConfigKeys.V20, "${k.name} and 2.0")
            assertEquals(k.name in v25, k.since <= ConfigKeys.V25, "${k.name} and 2.5")
            assertEquals(k.name in v27, k.since <= ConfigKeys.V27, "${k.name} and 2.7")
            assertTrue(k.since in ConfigKeys.V20..ConfigKeys.V33, k.name)
        }
        assertEquals(LookGates.FIRST_27_VERSION_CODE, ConfigKeys.V27)
        assertEquals(LookGates.FIRST_30_VERSION_CODE, ConfigKeys.V30)
        // Between the vendored readers, the upstream tags (git show <tag>:launcher/app/build.gradle and
        // Config.java): v2.3.0 (versionCode 4) added reduced_motion, v2.4.0 (5) sixteen keys after it,
        // v2.5.0 (6) nine, v2.6.0 (7) twenty four.
        fun since(v: Long) = ConfigKeys.ALL.filter { it.since == v }.map { it.name }.toSet()
        assertEquals(setOf("reduced_motion"), since(ConfigKeys.V23))
        assertEquals(setOf("fill_dock", "animations", "advanced_effects", "greeting_name", "dock_title", "dock_row", "reorder_button",
            "show_stats", "app_library", "sky_intensity", "settings_key_keycodes", "settings_key_panel", "festival", "festival_until",
            "greeting", "accent_color"), since(ConfigKeys.V24))
        assertEquals(9, since(ConfigKeys.V25).size)
        assertEquals(setOf("live_tv_input_id", "source_panel_extra", "coocaa_sources", "input_uri_scheme", "boot_to_live_tv",
            "show_network_pill", "show_inputs_pill", "icon_only_buttons", "library_button", "wifi_label_width", "always_show_names",
            "wallpaper_true_color", "focus_overshoot", "wallpaper_captions", "handle_view_inputs", "stock_home_redirect", "ambient_glow",
            "app_glow", "home_idle_clock_minutes", "key_filter", "dock_glass", "glow_style", "dock_opacity", "wallpaper_order"),
            since(ConfigKeys.V26))
        // 0.5.0, launcher 3.3 (the tags' Config.java): v3.1.0 (10) one key, v3.2.0 (11) five, v3.3.0 (12) four, and
        // inputs_cec_state_only after the tag at d7b0d4a5, still 12. The codec carries all eleven (CARRIED_33).
        assertEquals(setOf("inputs_connected_only"), since(ConfigKeys.V31))
        assertEquals(setOf("open_on_start_network_wait", "icon_only_vpn", "library_excluded_apps", "game_day", "seasonal_skin"), since(ConfigKeys.V32))
        assertEquals(setOf("open_on_start_on_wake", "back_opens_clock", "glow_snap", "clock_format", "inputs_cec_state_only"), since(ConfigKeys.V33))
        assertEquals(ConfigCodecImpl.CARRIED_33, (since(ConfigKeys.V31) + since(ConfigKeys.V32) + since(ConfigKeys.V33)))
    }

    // The helper each key goes through in 3.0's Config.load, and the range of each integer.
    @Test fun theTypeIsHowLauncher30ReadsIt() {
        val text = reader("v30")
        for (k in ConfigKeys.ALL) {
            val n = Regex.escape(k.name)
            val found = when (k.type) {
                ConfigKeyType.BOOL -> Regex("bool\\(json, \"$n\"")
                ConfigKeyType.INT -> Regex("integer\\(json, \"$n\", [^,]+, ${k.min}, ${k.max}\\)")
                ConfigKeyType.STRING -> Regex(if (k.name == "clock_format") "string\\(json, CLOCK_FORMAT_KEY" else "string\\((?:json|o), \"$n\"")
                ConfigKeyType.COLOR -> Regex("color\\(json, \"$n\"")
                ConfigKeyType.STRING_LIST -> Regex("stringList\\(json, \"$n\"")
                ConfigKeyType.INT_LIST -> Regex("intArray\\(json, \"$n\"")
                ConfigKeyType.OBJECT -> Regex("json\\.optJSONObject\\(\"$n\"\\)")
                ConfigKeyType.RACES -> Regex("optJSONArray\\(\"$n\"\\)")
            }.containsMatchIn(text)
            assertTrue(found, "${k.name} is not read as ${k.type} ${k.min} to ${k.max}")
        }
    }

    // The 0.5.0 keys the Customise sections need, each with the name, type, range and owner
    // Config.java and the plan give it.
    @Test fun theBriefKeys() {
        for ((name, want) in TvSettingsSamples.BRIEF) {
            val k = ConfigKeys.of(name) ?: error("$name is not a key launcher 3.0 reads")
            assertEquals(want, Triple(k.type, k.owner, if (k.type == ConfigKeyType.INT) k.min to k.max else null), name)
        }
        assertEquals(36, TvSettingsSamples.BRIEF.size)
    }

    // Each section's typed value covers exactly the keys it owns (TvSettings.edit checks it on every
    // write), and its DEFAULTS are config.xml at the pin.
    @Test fun sectionDefaultsAreConfigXml30() {
        val xml = UpstreamLauncher.parseConfigXml(File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml"))
        fun b(k: String) = xml.getValue(k) as Boolean
        fun i(k: String) = xml.getValue(k) as Int
        fun s(k: String) = xml.getValue(k) as String
        @Suppress("UNCHECKED_CAST")
        fun l(k: String) = (xml.getValue(k) as Array<String>).toList()
        assertEquals(WidgetsSettings(b("show_inputs_pill"), b("show_network_pill"), b("launcher_settings_button"), s("greeting_name"),
            b("shamsi_date"), b("lunar_date"), -1, b("show_stats"), b("vpn_pill"), s("vpn_app"), l("top_bar_apps").map(::PackageName),
            i("wifi_label_width"), b("show_greeting"), b("date_below_clock"), b("header_white"), s("clock_format"), b("top_menu"),
            b("icon_only_buttons"), b("icon_only_inputs"), b("icon_only_vpn"), b("wifi_status_color"), b("wifi_status_warnings"),
            b("reorder_button"), b("library_button")), WidgetsSettings.DEFAULTS)
        assertEquals(AppsSettings(b("tile_menu"), b("app_library"), b("show_library_pill"),
            l("hidden_apps").map(::PackageName), l("app_labels").associate { PackageName(it.substringBefore('=')) to it.substringAfter('=') }),
            AppsSettings.DEFAULTS)
        assertEquals(ScreensaverSettings(i("home_idle_clock_minutes"), b("clock_wallpaper"), i("clock_wallpaper_dim"), b("clock_glow")),
            ScreensaverSettings.DEFAULTS)
        assertEquals(StartUpSettings(b("boot_to_live_tv"), b("open_on_start_choice")), StartUpSettings.DEFAULTS)
        assertEquals(WallpaperSettings(b("wallpaper_captions")), WallpaperSettings.DEFAULTS)
        val empty = TvSettings.seed(null, null)
        empty.withWidgets(WidgetsSettings.DEFAULTS).withApps(AppsSettings.DEFAULTS).withScreensaver(ScreensaverSettings.DEFAULTS)
            .withStartUp(StartUpSettings.DEFAULTS).withWallpaper(WallpaperSettings.DEFAULTS).let { s ->
                for (section in TvSettingsSamples.TYPED) assertEquals(ConfigKeys.owned(section).toSet(), s.section(section).keys, "$section")
            }
    }

    // One owner per key: every key a look push can write (LookKeys.build over the looks and their
    // extras, a bundled theme with its dock and cards) belongs to LOOK, DOCK or CARDS, and no key is
    // owned twice (the table holds one owner each).
    @Test fun theLookPushWritesOnlyItsOwnSections() {
        val look = setOf(SettingsSection.LOOK, SettingsSection.DOCK, SettingsSection.CARDS)
        val written = HashSet<String>()
        val rnd = kotlin.random.Random(50)
        val specs = LookInputs.cross().toList()
        repeat(3000) {
            val doc = TvSettingsSamples.look(rnd, specs)
            written += TvSettingsSamples.flat(doc).keys
        }
        // A 0.4.0 theme also may name hidden_apps, the Apps section's: Default writes the launcher's own
        // list, which the look push only uses for a TV without a list (LookCarry.merge).
        for (theme in BundledThemeSource().bundled()) for (columns in listOf(null, 4, 8)) {
            val doc = DockLayers.applyTo(theme.doc, TvSettingsSamples.apps.take(9), columns).copy(moduleCards = true, fillDock = true)
            theme.doc.hiddenApps?.let { assertEquals(ConfigCodecImpl.BUILT_IN.hiddenApps, it.map { p -> p.value }, theme.id) }
            written += TvSettingsSamples.flat(doc.copy(hiddenApps = null)).keys
        }
        // 0.5.0: the Widgets keys a look still builds only stand in where the TV's file has none (LookCarry.merge).
        val stray = written.filter { ConfigKeys.of(it)?.owner !in look + TvSettingsSamples.STAND_IN }
        assertEquals(emptyList(), stray, "keys a look push writes that another section owns")
        // Everything LOOK owns is a key a look writes, or the festival's greeting a theme may set.
        assertEquals(setOf("greeting"), ConfigKeys.owned(SettingsSection.LOOK).toSet() - written)
    }
}
