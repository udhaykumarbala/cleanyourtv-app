package tv.cleanyour.core.spec

import android.content.MemoryPreferences
import com.gathin.tvhome.pinned.PinnedConfigBridge
import com.gathin.tvhome.v27.V27ConfigBridge
import com.gathin.tvhome.v30.V30ConfigBridge
import com.gathin.tvhome.previous.PreviousConfigBridge
import tv.cleanyour.core.spec.upstream.UpstreamConfig
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import tv.cleanyour.core.model.PackageName
import kotlin.test.assertTrue
import kotlin.test.fail

// Runs the real upstream Config.java against a folder that plays the launcher's external files
// dir, with resource values read from the matching config.xml. Four launchers:
// - 3.0 (the submodule at b06ab21, upstream main after v3.0.0, versionCode 9), the one the app
//   bundles and the reader the codec and the looks are checked against (RFC-UP300-1): Config.java
//   and the classes it calls vendored in com.gathin.tvhome.v30, checked against the submodule by
//   LookDifferentialTest;
// - 2.7 (v2.7.0, 3e34f40, versionCode 8, com.gathin.tvhome.v27), frozen by digest in
//   LookDifferentialTest: a TV updated to 2.7 and not since;
// - 2.5 (v2.5.0, 9071ec8, com.gathin.tvhome.pinned, RFC-UP037-1), kept for the keys it reads: a TV
//   updated once and not since;
// - 2.0 (main 1bbf0c9, com.gathin.tvhome.previous), the model of a TV that has not been updated yet.
// 2.5, 2.7 and 3.0 read the same config.xml here (the submodule's, 3.0's, whose values for the
// older keys are 2.7's); each R has only the ids its launcher reads.
class UpstreamLauncher(val version: Version, private val dir: File) {
    enum class Version(val label: String) { V20("2.0"), V25("2.5"), V27("2.7"), V30("3.0") }

    // The launcher reads the keys v2.4.0 and v2.5.0 added (UpstreamConfig.v24 and v25).
    val pinned: Boolean get() = version != Version.V20
    // The launcher reads the carried 2.7 look keys (UpstreamConfig.v27).
    val reads27: Boolean get() = version == Version.V27 || version == Version.V30
    // The launcher reads the 25 keys 3.0 added (UpstreamConfig.v30).
    val reads30: Boolean get() = version == Version.V30
    // The v30 copy is launcher 3.3 since 0.4.9-beta1: it reads the CARRIED_33 keys, among them
    // seasonal_skin as the festival's fallback name (Config.festivalName).
    val reads33: Boolean get() = version == Version.V30

    val values: Map<String, Any> = parseConfigXml(
        if (pinned) File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml")
        else File(javaClass.getResource("/upstream/config-1bbf0c9.xml")!!.toURI()),
    )

    // Launcher 2.7's and 3.0's private files dir, where it keeps a copy of the last config.json it read.
    private val privateDir: File by lazy { File(dir.parentFile, dir.name + ".private").also { it.mkdirs() } }

    // The launcher's private launcher_settings (v2.4.0 and later): the choices made on the TV. It lives as
    // long as this launcher, as it does on a TV, so a test can make a choice and push after it.
    val choices = MemoryPreferences()

    // Every push gets a newer modification time, as AdbTvSession's sync sets the phone's clock.
    private var pushes = 0L

    sealed interface Outcome {
        data class Loaded(val config: UpstreamConfig) : Outcome
        data class Crashed(val error: Throwable) : Outcome
    }

    // Pushes configJson (null removes config.json) and the wallpaper, then starts the launcher.
    fun load(configJson: ByteArray?, wallpaper: Boolean): Outcome {
        val cfg = File(dir, "config.json")
        val wp = File(dir, "wallpaper.jpg")
        if (configJson == null) cfg.delete() else {
            cfg.writeBytes(configJson)
            cfg.setLastModified(1_760_000_000_000L + 1_000L * ++pushes)
        }
        if (wallpaper) wp.writeBytes(byteArrayOf(1, 2, 3)) else wp.delete()
        return restart()
    }

    // Starts the launcher again on the files as they are (Home restarted on the TV, no push).
    fun restart(): Outcome = try {
        Outcome.Loaded(when (version) {
            Version.V30 -> V30ConfigBridge.load(dir, privateDir, values, choices)
            Version.V27 -> V27ConfigBridge.load(dir, privateDir, values, choices)
            Version.V25 -> PinnedConfigBridge.load(dir, values, choices)
            Version.V20 -> PreviousConfigBridge.load(dir, values)
        })
    } catch (e: RuntimeException) {
        Outcome.Crashed(e)
    }

    // The EffectiveConfig the launcher starts from with no overlay (Config.load's resource reads).
    // Launcher 2.0 does not read the keys v2.4.0 and v2.5.0 added; it gets the built-in values for them.
    fun defaults(): EffectiveConfig {
        @Suppress("UNCHECKED_CAST")
        fun list(k: String) = (values.getValue(k) as Array<String>).toList()
        fun s(k: String) = (values.getValue(k) as String).javaTrim()
        fun b(k: String) = values.getValue(k) as Boolean
        fun map(k: String) = list(k).mapNotNull { ConfigCodecImpl.packageMapEntry(it.javaTrim()) }.toMap()
        val base = EffectiveConfig(
            dockApps = list("dock_apps"), hiddenApps = list("hidden_apps"), liveTvPackage = s("live_tv_package"),
            liveTvAction = s("live_tv_action"), sourcePanelService = s("source_panel_service"), sourcePanelAction = s("source_panel_action"),
            columns = values.getValue("columns") as Int, rowsBelowDock = values.getValue("rows_below_dock") as Int,
            shamsiDate = b("shamsi_date"), vpnApp = s("vpn_app"),
            showHomeSettingsShortcut = b("show_home_settings_shortcut"),
            backgroundColorArgb = 0xFF000000.toInt(), wallpaper = true,
            inputAction = s("input_action"), moduleCards = b("module_cards"),
        )
        if (!pinned) return base
        return base.copy(
            fillDock = b("fill_dock"), animations = b("animations"), advancedEffects = b("advanced_effects"),
            greetingName = s("greeting_name"), reducedMotion = b("reduced_motion"), dockTitle = b("dock_title"),
            dockRow = b("dock_row"), reorderButton = b("reorder_button"), showStats = b("show_stats"),
            appLibrary = b("app_library"), skyIntensity = values.getValue("sky_intensity") as Int,
            settingsKeyKeycodes = (values.getValue("settings_key_keycodes") as IntArray).toList(),
            settingsKeyPanel = b("settings_key_panel"),
            launchActions = map("launch_actions"), appLabels = map("app_labels"), appBanners = map("app_banners"),
            inputKeyKeycodes = (values.getValue("input_key_keycodes") as IntArray).toList(),
            appKeys = list("app_keys").map { it.javaTrim() }.filter { it.isNotEmpty() }, cinematicHome = b("cinematic_home"),
        )
    }

    // The carried look keys with no overlay, from config.xml as launcher 2.7's Config.load reads
    // them (dock_opacity is not in config.xml: 100 without a config.json).
    fun lookDefaults(): LookConfig {
        check(reads27) { "launcher ${version.label} does not read the 2.7 look keys" }
        fun b(k: String) = values.getValue(k) as Boolean
        fun i(k: String) = values.getValue(k) as Int
        return LookConfig(
            ambientGlow = b("ambient_glow"), glowStyle = LauncherKeys.glowStyle(values.getValue("glow_style") as String),
            appGlow = b("app_glow"), dockGlass = b("dock_glass"), dockOpacity = 100, dockEvenPadding = b("dock_even_padding"),
            alwaysShowNames = b("always_show_names"), iconOnlyButtons = b("icon_only_buttons"), iconOnlyInputs = b("icon_only_inputs"),
            topMenu = b("top_menu"), wifiStatusColor = b("wifi_status_color"), focusOvershoot = b("focus_overshoot"),
            cinematicDots = b("cinematic_dots"), cinematicWhiteFocus = b("cinematic_white_focus"),
            cinematicArt = i("cinematic_art"), cinematicScrim = i("cinematic_scrim"),
        )
    }

    // The 25 keys of 3.0 with no overlay, from config.xml as launcher 3.0's Config.load reads them
    // (lunar_utc_offset is not in config.xml: -1 without a config.json).
    fun keys30Defaults(): Keys30 {
        check(reads30) { "launcher ${version.label} does not read the 3.0 keys" }
        fun b(k: String) = values.getValue(k) as Boolean
        @Suppress("UNCHECKED_CAST")
        fun list(k: String) = (values.getValue(k) as Array<String>).toList()
        return Keys30(
            inputSources = list("input_sources"), lunarDate = b("lunar_date"), lunarUtcOffset = -1,
            openOnStartChoice = b("open_on_start_choice"), showLibraryPill = b("show_library_pill"),
            libraryOkOpens = b("library_ok_opens"), uniformTiles = b("uniform_tiles"), consumeVolumeKeys = b("consume_volume_keys"),
            powerOnHome = b("power_on_home"), homeLongPressHome = b("home_long_press_home"), powerOnHomeAlways = b("power_on_home_always"),
            dockBand = b("dock_band"), wifiStatusWarnings = b("wifi_status_warnings"), dateBelowClock = b("date_below_clock"),
            vpnPill = b("vpn_pill"), locale = (values.getValue("locale") as String).javaTrim(), topBarApps = list("top_bar_apps"),
            showGreeting = b("show_greeting"), headerWhite = b("header_white"), clockWallpaper = b("clock_wallpaper"),
            clockWallpaperDim = values.getValue("clock_wallpaper_dim") as Int, clockGlow = b("clock_glow"),
            singleRow = b("single_row"), dockBandAll = b("dock_band_all"),
            glassStyle = LauncherKeys.glassStyle(values.getValue("glass_style") as String),
        )
    }

    companion object {
        // The real 3.0 Config's new fields as a Keys30, for the field by field comparison. Config
        // keeps single_row and-ed with dock_row; Agreement and-s ours the same way.
        fun keys30(v: UpstreamConfig.V30): Keys30 = Keys30(
            inputSources = v.inputSources, lunarDate = v.lunarDate, lunarUtcOffset = v.lunarUtcOffset,
            openOnStartChoice = v.openOnStartChoice, showLibraryPill = v.showLibraryPill, libraryOkOpens = v.libraryOkOpens,
            uniformTiles = v.uniformTiles, consumeVolumeKeys = v.consumeVolumeKeys, powerOnHome = v.powerOnHome,
            homeLongPressHome = v.homeLongPressHome, powerOnHomeAlways = v.powerOnHomeAlways, dockBand = v.dockBand,
            wifiStatusWarnings = v.wifiStatusWarnings, dateBelowClock = v.dateBelowClock, vpnPill = v.vpnPill,
            locale = v.locale, topBarApps = v.topBarApps, showGreeting = v.showGreeting, headerWhite = v.headerWhite,
            clockWallpaper = v.clockWallpaper, clockWallpaperDim = v.clockWallpaperDim, clockGlow = v.clockGlow,
            singleRow = v.singleRow, dockBandAll = v.dockBandAll,
            glassStyle = when (v.glassStyle) { "glass" -> GlassStyle.GLASS; "rim" -> GlassStyle.RIM; "halo" -> GlassStyle.HALO; else -> error("glass_style ${v.glassStyle}") },
        )

        // The real 2.7 Config's look fields as a LookConfig, for the field by field comparison.
        fun look(v: UpstreamConfig.V27): LookConfig = LookConfig(
            ambientGlow = v.ambientGlow,
            glowStyle = when (v.glowStyle) { "horizon" -> GlowStyle.HORIZON; "radial" -> GlowStyle.RADIAL; else -> error("glow_style ${v.glowStyle}") },
            appGlow = v.appGlow, dockGlass = v.dockGlass, dockOpacity = v.dockOpacity, dockEvenPadding = v.dockEvenPadding,
            alwaysShowNames = v.alwaysShowNames, iconOnlyButtons = v.iconOnlyButtons, iconOnlyInputs = v.iconOnlyInputs,
            topMenu = v.topMenu, wifiStatusColor = v.wifiStatusColor, focusOvershoot = v.focusOvershoot,
            cinematicDots = v.cinematicDots, cinematicWhiteFocus = v.cinematicWhiteFocus,
            cinematicArt = v.cinematicArt, cinematicScrim = v.cinematicScrim,
        )

        // aapt keeps string resources trimmed; these values are plain words, so text content is enough.
        fun parseConfigXml(f: File): Map<String, Any> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val out = LinkedHashMap<String, Any>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                val items = e.getElementsByTagName("item")
                out[name] = when (e.tagName) {
                    "string-array" -> Array(items.length) { items.item(it).textContent.trim() }
                    "integer-array" -> IntArray(items.length) { items.item(it).textContent.trim().toInt() }
                    "string" -> e.textContent.trim()
                    "integer" -> e.textContent.trim().toInt()
                    "bool" -> e.textContent.trim().toBooleanStrict()
                    else -> continue
                }
            }
            return out
        }
    }
}

// One file through the codec and a real launcher, field by field: every modelled field of
// LauncherConfigCodec.effective against the launcher's Config (the keys that launcher reads), on 2.7
// and 3.0 every LookConfig field of LauncherKeys.look against the real Config field, and on 3.0
// every Keys30 field of LauncherKeys.keys30. Used by
// ConfigDifferentialTest and LookDifferentialTest.
object Agreement {
    class Tally {
        var applied = 0; var ignored = 0; var crashed = 0; var withWarnings = 0; var warnings = 0
        // Files an older launcher reads that 2.7 and 3.0 crash on (not compared).
        var crashOn27Only = 0
        // Files where every compared field agreed (all of them, or the test failed).
        var agreed = 0
    }

    private fun validPkg(s: String) = runCatching { PackageName(s) }.isSuccess

    // A race as the bridge prints RaceWeekend's: "round|name|place|SESSION@start;...".
    fun raceText(r: LauncherRace) = "${r.round}|${r.name}|${r.place}|" + r.sessions.joinToString(";") { "${it.name}@${it.start}" }

    // Returns the doc the codec decoded (null for no file or a rejected one).
    fun agree(up: UpstreamLauncher, bytes: ByteArray?, wallpaper: Boolean, label: String, t: Tally): LauncherConfigDoc? {
        val upstream = up.load(bytes, wallpaper)
        val ours = bytes?.let { LauncherConfigCodec.decode(it) }
        fun ctx() = "launcher ${up.version.label}, $label: ${bytes?.let { String(it, Charsets.UTF_8).take(400) }}"
        when (upstream) {
            is UpstreamLauncher.Outcome.Crashed -> {
                t.crashed++
                // An empty background_color (Color.parseColor), or on 2.7 a NaN or Infinity in a list
                // (the copy Config.readOverlay keeps, ConfigCodecImpl.CRASH_27_NON_FINITE).
                val known = upstream.error is StringIndexOutOfBoundsException ||
                    (up.reads27 && upstream.error is NullPointerException && ours is DecodeResult.Rejected && ours.reason == ConfigCodecImpl.CRASH_27_NON_FINITE)
                assertTrue(known, "unexpected upstream crash ${upstream.error} for ${ctx()}")
                assertTrue(ours is DecodeResult.Rejected && ours.reason.contains("crash"), "upstream crashes but we said $ours for ${ctx()}")
                t.agreed++
                return null
            }
            is UpstreamLauncher.Outcome.Loaded -> {
                val u = upstream.config
                if (bytes != null && !u.overlayApplied) {
                    t.ignored++
                    assertTrue(ours is DecodeResult.Rejected && !ours.reason.contains("crash"), "upstream ignores the file but we said $ours for ${ctx()}")
                }
                val doc = when (ours) {
                    null -> null
                    is DecodeResult.Ok -> {
                        t.applied++
                        if (ours.warnings.isNotEmpty()) t.withWarnings++
                        t.warnings += ours.warnings.size
                        ours.doc
                    }
                    is DecodeResult.Rejected -> {
                        // The codec answers for the bundled 3.0 (and 2.7, which crashes the same way): a
                        // file only they crash on is read by older launchers, and is not compared there.
                        if (!up.reads27 && ours.reason == ConfigCodecImpl.CRASH_27_NON_FINITE && u.overlayApplied) {
                            t.crashOn27Only++
                            return null
                        }
                        assertTrue(!u.overlayApplied, "we rejected (${ours.reason}) a file upstream applied: ${ctx()}")
                        null
                    }
                }
                // effective() follows the bundled launcher's seasonal_skin fallback; older ones ignore the key.
                val forThis = if (doc == null || up.reads33) doc else doc.copy(unknown = doc.unknown - "seasonal_skin")
                val e = LauncherConfigCodec.effective(forThis, up.defaults())
                val keys30 = LauncherKeys.keys30(doc)
                // Launcher 3.0 moves the top-bar apps out of the grid: Config adds them to the hidden list.
                val hidden = if (!up.reads30) e.hiddenApps
                    else e.hiddenApps + keys30.topBarApps.filter(::validPkg).distinct().filter { it !in e.hiddenApps }
                val mine = listOf(
                    e.dockApps, hidden, e.liveTvPackage, e.liveTvAction, e.sourcePanelService, e.sourcePanelAction,
                    e.columns, e.rowsBelowDock, e.shamsiDate, e.vpnApp, e.showHomeSettingsShortcut, e.backgroundColorArgb,
                    e.wallpaper && wallpaper, e.inputAction, e.moduleCards,
                ) + if (up.pinned) listOf(
                    e.fillDock, e.animations, e.advancedEffects, e.greetingName, e.reducedMotion, e.dockTitle, e.dockRow,
                    e.reorderButton, e.showStats, e.appLibrary, e.skyIntensity, e.settingsKeyKeycodes, e.settingsKeyPanel,
                    e.festival, e.festivalUntil, e.greeting, e.accentColorArgb,
                    e.launchActions, e.appLabels, e.appBanners, e.inputKeyKeycodes, e.appKeys, e.cinematicHome,
                    e.wallpaperRotationMinutes, e.wallpaperDim, e.raceCalendar.map(::raceText),
                ) else emptyList()
                // Entries that are not package names can never match an app; the codec drops them.
                val theirs = listOf(
                    u.dockApps.filter(::validPkg), u.hiddenApps.filter(::validPkg), u.liveTvPackage, u.liveTvAction,
                    u.sourcePanelService, u.sourcePanelAction, u.columns, u.rowsBelowDock, u.shamsiDate, u.vpnApp,
                    u.showHomeSettingsShortcut, u.backgroundColor, u.wallpaperShown, u.inputAction, u.moduleCards,
                ) + if (up.pinned) u.v24.let { v -> listOf(
                    v.fillDock, v.animations, v.advancedEffects, v.greetingName, v.reducedMotion, v.dockTitle, v.dockRow,
                    v.reorderButton, v.showStats, v.appLibrary, v.skyIntensity, v.settingsKeyKeycodes, v.settingsKeyPanel,
                    v.festival, v.festivalUntil, v.greeting, v.accent,
                ) } + u.v25.let { v -> listOf(
                    v.launchActions, v.appLabels, v.appBanners, v.inputKeyKeycodes, v.appKeys, v.cinematicHome,
                    v.wallpaperRotationMinutes, v.wallpaperDim, v.raceCalendar,
                ) } else emptyList()
                if (mine != theirs) fail("effective config differs for ${ctx()}\n ours:     $mine\n upstream: $theirs")
                if (up.reads27) {
                    val look = LauncherKeys.look(doc)
                    val real = UpstreamLauncher.look(u.v27)
                    if (look != real) fail("2.7 look keys differ for ${ctx()}\n ours:     $look\n upstream: $real")
                }
                if (up.reads30) {
                    val ours30 = keys30.copy(singleRow = keys30.singleRow && e.dockRow)
                    val real = UpstreamLauncher.keys30(u.v30)
                    if (ours30 != real) fail("3.0 keys differ for ${ctx()}\n ours:     $ours30\n upstream: $real")
                }
                t.agreed++
                return doc
            }
        }
    }
}
