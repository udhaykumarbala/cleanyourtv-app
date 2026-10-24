package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.cleanyour.core.model.PackageName

// The body of LauncherConfigCodec: the launcher's Config.java read path, key by key, as of
// upstream v2.5.0 (9071ec8), the launcher this app bundles. 1bbf0c9 added input_action and
// module_cards (RFC-W4-1); v2.4.0 added seventeen more keys (RFC-W4-2) and v2.5.0 nine more
// (RFC-W4-3), which a TV still on an older launcher ignores. Decoding answers "what will the TV show for these bytes"; a key the
// launcher would ignore is left null in the doc with a warning, so effective() of the decoded doc
// equals what Config.load computes. ConfigDifferentialTest holds this to the real Config.java of
// both launchers on the theme fixtures and on generated files.
//
// Not modelled, because the phone cannot read it: the choices made on the TV in Launcher settings
// (columns 4 to 7, rows below the dock 0 to 3, animations, advanced effects, a wallpaper pick),
// which override config.json until the next push changes the file; the dock and tile order set on
// the TV (Edit dock and Reorder, files/order.txt, kept while dock_apps stays the same); apps taken
// off Home there; and a theme's wallpapers/ collection, which this app never pushes.
internal object ConfigCodecImpl {
    const val MAX_BYTES = 64 * 1024

    // Config.java's keys in the order it reads them; encode writes this order.
    val KEY_ORDER: List<String> = listOf(
        "dock_apps", "fill_dock", "hidden_apps", "live_tv_package", "live_tv_action", "launch_actions",
        "app_labels", "app_banners", "source_panel_service", "source_panel_action", "input_action", "columns",
        "rows_below_dock", "animations", "advanced_effects", "shamsi_date", "greeting_name", "module_cards",
        "reduced_motion", "dock_title", "dock_row", "reorder_button", "vpn_app", "show_home_settings_shortcut",
        "show_stats", "app_library", "background_color", "sky_intensity", "settings_key_keycodes",
        "settings_key_panel", "input_key_keycodes", "app_keys", "cinematic_home", "wallpaper",
        "wallpaper_rotation_minutes", "wallpaper_dim", "festival", "festival_until", "greeting", "accent_color",
        "race_calendar",
    )
    // Remote buttons Home needs to move and choose: Back, the four arrows and centre, Enter, Menu.
    // MainActivity opens Settings on any key in settings_key_keycodes, HomeKeyService or not, so a
    // list with one of these takes that button away from the home screen (SECURITY.md V1).
    // Launcher 2.5's InputKeyService and HomeKeyService are accessibility services: with one on, a
    // key in input_key_keycodes or app_keys is taken in every app, not only on Home.
    val NAVIGATION_KEYCODES: Set<Int> = setOf(4, 19, 20, 21, 22, 23, 66, 82)
    // The same buttons by the names app_keys may use (KeyEvent.keyCodeFromString).
    val NAVIGATION_KEY_NAMES: Map<String, Int> = mapOf(
        "KEYCODE_BACK" to 4, "KEYCODE_DPAD_UP" to 19, "KEYCODE_DPAD_DOWN" to 20, "KEYCODE_DPAD_LEFT" to 21,
        "KEYCODE_DPAD_RIGHT" to 22, "KEYCODE_DPAD_CENTER" to 23, "KEYCODE_ENTER" to 66, "KEYCODE_MENU" to 82,
    )
    // The banner_* drawables launcher v2.5.0 bundles (res/drawable), the names app_banners can use.
    val BUNDLED_BANNERS: Set<String> = setOf("banner_tv")
    // Keys with a LauncherConfigDoc field: all of Config.java's keys.
    val MODELLED: Set<String> = KEY_ORDER.toSet()

    // res/values/config.xml of the launcher at v2.5.0 (the keys of older launchers keep their values).
    // config.xml has no festival, wallpaper rotation, dim or race calendar: those only ever come
    // from config.json.
    val BUILT_IN = EffectiveConfig(
        dockApps = listOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "in.startv.hotstar"),
        hiddenApps = listOf("com.mediatek.wwtv.tvcenter", "com.mitv.gallery"),
        liveTvPackage = "com.mediatek.wwtv.tvcenter", liveTvAction = "", sourcePanelService = "", sourcePanelAction = "",
        columns = 4, rowsBelowDock = 0, shamsiDate = false, vpnApp = "", showHomeSettingsShortcut = false,
        backgroundColorArgb = 0xFF000000.toInt(), wallpaper = true, inputAction = "", moduleCards = true,
        fillDock = true, animations = true, advancedEffects = false, greetingName = "", reducedMotion = false,
        dockTitle = true, dockRow = true, reorderButton = false, showStats = false, appLibrary = false,
        skyIntensity = 0, settingsKeyKeycodes = emptyList(), settingsKeyPanel = false,
        festival = "", festivalUntil = "", greeting = "", accentColorArgb = 0,
        launchActions = emptyMap(), appLabels = emptyMap(), appBanners = emptyMap(), inputKeyKeycodes = emptyList(),
        appKeys = emptyList(), cinematicHome = false, wallpaperRotationMinutes = 0, wallpaperDim = 0, raceCalendar = emptyList(),
    )

    fun isPackageName(s: String): Boolean = runCatching { PackageName(s) }.isSuccess

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > MAX_BYTES) return DecodeResult.Rejected("larger than 64 KB: the launcher ignores the whole file")
        var text = String(bytes, Charsets.UTF_8)
        if (text.startsWith('﻿')) text = text.substring(1)   // Config.read strips one BOM, JSONTokener another
        val o = try {
            AndroidJsonReader(text).readObjectDocument().map
        } catch (e: AndroidJsonTooDeep) {
            return DecodeResult.Rejected("${e.message}: refused here; the launcher would recurse that deep")
        } catch (e: AndroidJsonException) {
            return DecodeResult.Rejected("not readable by the launcher, so it ignores the whole file (${e.message})")
        }
        val w = mutableListOf<String>()

        fun stringList(key: String): List<PackageName>? {
            val v = o[key] ?: return null
            if (v !is AValue.Arr) { w += "$key is not a list; the launcher keeps its default"; return null }
            val out = ArrayList<PackageName>()
            v.items.forEachIndexed { i, item ->
                if (item is AValue.Str) {
                    val t = item.v.javaTrim()
                    when {
                        t.isEmpty() -> w += "$key[$i] is blank; the launcher skips it"
                        isPackageName(t) -> out += PackageName(t)
                        else -> w += "$key[$i] is not a package name; it can never match an app, so it is dropped here"
                    }
                } else {
                    w += "$key[$i] is not a string; the launcher skips it"
                }
            }
            return out
        }
        fun string(key: String): String? {
            val v = o[key] ?: return null
            if (v is AValue.Str) return v.v.javaTrim()
            w += "$key is not a string; the launcher keeps its default"
            return null
        }
        fun bool(key: String): Boolean? {
            val v = o[key] ?: return null
            if (v is AValue.Bool) return v.v
            w += "$key is not true or false; the launcher keeps its default"
            return null
        }
        fun integer(key: String, min: Int, max: Int): Int? {
            val v = o[key] ?: return null
            val i = when (v) {
                is AValue.IntV -> v.v
                is AValue.LongV -> v.v.toInt()          // Number.intValue() keeps the low 32 bits
                is AValue.Dbl -> v.v.toInt()            // and truncates a double towards zero
                else -> { w += "$key is not a number; the launcher keeps its default"; return null }
            }
            if (i < min || i > max) { w += "$key is $i, outside $min to $max; the launcher keeps its default"; return null }
            return i
        }

        // Config.intArray: all or nothing. Every item a number whose intValue() is 1 or more, or
        // the launcher ignores the whole list.
        fun intList(key: String): List<Int>? {
            val v = o[key] ?: return null
            if (v !is AValue.Arr) { w += "$key is not a list; the launcher keeps its default"; return null }
            val out = ArrayList<Int>(v.items.size)
            v.items.forEachIndexed { i, item ->
                val n = when (item) {
                    is AValue.IntV -> item.v
                    is AValue.LongV -> item.v.toInt()
                    is AValue.Dbl -> item.v.toInt()
                    else -> { w += "$key[$i] is not a number; the launcher ignores the whole list"; return null }
                }
                if (n < 1) { w += "$key[$i] is $n, below 1; the launcher ignores the whole list"; return null }
                out += n
            }
            return out
        }

        // Config.stringList as the launcher keeps it: the trimmed, non-blank strings; `check` warns
        // about an entry the launcher keeps but cannot use.
        fun textList(key: String, check: (Int, String) -> Unit = { _, _ -> }): List<String>? {
            val v = o[key] ?: return null
            if (v !is AValue.Arr) { w += "$key is not a list; the launcher keeps its default"; return null }
            val out = ArrayList<String>()
            v.items.forEachIndexed { i, item ->
                if (item is AValue.Str) {
                    val t = item.v.javaTrim()
                    if (t.isEmpty()) w += "$key[$i] is blank; the launcher skips it" else { out += t; check(i, t) }
                } else {
                    w += "$key[$i] is not a string; the launcher skips it"
                }
            }
            return out
        }
        // Config.packageMap's "package=value" entries (launch_actions, app_labels, app_banners).
        fun packageEntry(key: String, what: String): (Int, String) -> Unit = { i, t ->
            val entry = packageMapEntry(t)
            when {
                entry == null -> w += "$key[$i] is not package=$what; the launcher skips it"
                !isPackageName(entry.first) -> w += "$key[$i] names ${entry.first}, which is not a package name; it can never match an app"
                key == "app_banners" && entry.second !in BUNDLED_BANNERS ->
                    w += "$key[$i] names ${entry.second}, which is not a banner the launcher bundles (${BUNDLED_BANNERS.joinToString()}); the tile keeps its own art"
            }
        }
        val navigationApps = ArrayList<Int>()
        fun appKey(i: Int, t: String) {
            val scan = appScanEntry(t)
            when (val k = appKeyEntry(t)) {
                null -> w += if (scan != null) {
                    "app_keys[$i] maps button scancode ${scan.first}, which launcher 2.6 and later read; launcher 2.5 skips it"
                } else {
                    "app_keys[$i] is not keycode:package (190:com.google.android.youtube.tv); the launcher skips it"
                }
                else -> {
                    if (!isPackageName(k.second)) w += "app_keys[$i] names ${k.second}, which is not a package name; that button would do nothing"
                    if (k.first in NAVIGATION_KEYCODES) navigationApps += k.first
                }
            }
        }

        val dock = stringList("dock_apps")
        val fillDock = bool("fill_dock")
        val hidden = stringList("hidden_apps")
        val liveTv = string("live_tv_package")
        val liveTvAction = string("live_tv_action")
        val launchActions = textList("launch_actions", packageEntry("launch_actions", "action"))
        val appLabels = textList("app_labels", packageEntry("app_labels", "name"))
        val appBanners = textList("app_banners", packageEntry("app_banners", "banner_name"))
        val panelService = string("source_panel_service")
        val panelAction = string("source_panel_action")
        val inputAction = string("input_action")
        val columns = integer("columns", 1, 8)
        val rows = integer("rows_below_dock", 0, 3)
        val animations = bool("animations")
        val advancedEffects = bool("advanced_effects")
        val shamsi = bool("shamsi_date")
        val greetingName = string("greeting_name")
        val moduleCards = bool("module_cards")
        val reducedMotion = bool("reduced_motion")
        val dockTitle = bool("dock_title")
        val dockRow = bool("dock_row")
        val reorderButton = bool("reorder_button")
        val vpn = string("vpn_app")
        val homeSettings = bool("show_home_settings_shortcut")
        val showStats = bool("show_stats")
        val appLibrary = bool("app_library")
        var background: String? = null
        o["background_color"]?.let { v ->
            if (v !is AValue.Str) { w += "background_color is not a string; the launcher keeps black"; return@let }
            val t = v.v.javaTrim()
            when (AndroidColor.parseColor(t)) {
                is AndroidColor.Parse.Ok -> background = t
                AndroidColor.Parse.Invalid -> w += "background_color is not a colour the launcher knows; it keeps black"
                AndroidColor.Parse.Crash -> return DecodeResult.Rejected("background_color is empty: the launcher would crash when Home starts")
            }
        }
        val sky = integer("sky_intensity", 0, 100)
        val keycodes = intList("settings_key_keycodes")
        keycodes?.filter { it in NAVIGATION_KEYCODES }?.distinct()?.takeIf { it.isNotEmpty() }?.let { nav ->
            w += "settings_key_keycodes takes remote buttons the home screen needs (keycode ${nav.joinToString(", ")}): " +
                "on Home they would open Settings instead of moving or choosing"
        }
        val keyPanel = bool("settings_key_panel")
        val inputKeycodes = intList("input_key_keycodes")
        inputKeycodes?.filter { it in NAVIGATION_KEYCODES }?.distinct()?.takeIf { it.isNotEmpty() }?.let { nav ->
            w += "input_key_keycodes takes remote buttons every app needs (keycode ${nav.joinToString(", ")}): with the " +
                "launcher's input key service on, they would open the input switcher in any app"
        }
        val appKeys = textList("app_keys", ::appKey)
        navigationApps.distinct().takeIf { it.isNotEmpty() }?.let { nav ->
            w += "app_keys takes remote buttons every app needs (keycode ${nav.joinToString(", ")}): with the " +
                "launcher's key services on, they would open an app in any app"
        }
        val cinematic = bool("cinematic_home")
        var wallpaper: Boolean? = null
        o["wallpaper"]?.let { v ->
            if (v is AValue.Bool) wallpaper = v.v
            else w += "wallpaper is not true or false; the launcher shows wallpaper.jpg if it is there"
        }
        val rotation = integer("wallpaper_rotation_minutes", 0, 1440)
        val dim = integer("wallpaper_dim", 0, 90)
        val festival = string("festival")
        val festivalUntil = string("festival_until")
        val greeting = string("greeting")
        val accent = string("accent_color")
        when (LauncherFestival.parse(festival.orEmpty(), festivalUntil.orEmpty(), greeting.orEmpty(), accent.orEmpty())) {
            null -> when {
                festival != null && festival.isEmpty() -> w += "festival is empty; the launcher shows no festival"
                festival != null && LauncherFestival.name(festival).length > LauncherFestival.MAX_NAME ->
                    w += "festival is longer than ${LauncherFestival.MAX_NAME} characters; the launcher shows no festival"
                festival != null -> w += "festival_until is not a date like 2026-10-21; the launcher shows no festival"
                else -> listOf("festival_until" to festivalUntil, "greeting" to greeting, "accent_color" to accent)
                    .filter { it.second != null }
                    .forEach { w += "${it.first} has no effect without a festival" }
            }
            else -> if (!accent.isNullOrEmpty() && LauncherFestival.parseColor(accent) == 0) {
                w += "accent_color is not a colour like #FFB23C; the launcher uses its diya gold"
            }
        }

        val races = o["race_calendar"]?.let { readRaces(it, w) }

        val unknown = LinkedHashMap<String, JsonElement>()
        for ((k, v) in o) {
            if (k in MODELLED) continue
            unknown[k] = v.toJsonElement()
            w += "unknown key $k; the launcher ignores it (carried as is)"
        }
        val doc = LauncherConfigDoc(
            dockApps = dock, hiddenApps = hidden, liveTvPackage = liveTv, liveTvAction = liveTvAction,
            sourcePanelService = panelService, sourcePanelAction = panelAction, columns = columns, rowsBelowDock = rows,
            shamsiDate = shamsi, vpnApp = vpn, showHomeSettingsShortcut = homeSettings, backgroundColor = background,
            wallpaper = wallpaper, unknown = unknown, inputAction = inputAction, moduleCards = moduleCards,
            fillDock = fillDock, animations = animations, advancedEffects = advancedEffects, greetingName = greetingName,
            reducedMotion = reducedMotion, dockTitle = dockTitle, dockRow = dockRow, reorderButton = reorderButton,
            showStats = showStats, appLibrary = appLibrary, skyIntensity = sky, settingsKeyKeycodes = keycodes,
            settingsKeyPanel = keyPanel, festival = festival, festivalUntil = festivalUntil, greeting = greeting,
            accentColor = accent, launchActions = launchActions, appLabels = appLabels, appBanners = appBanners,
            inputKeyKeycodes = inputKeycodes, appKeys = appKeys, cinematicHome = cinematic, wallpaperRotationMinutes = rotation,
            wallpaperDim = dim, raceCalendar = races,
        )
        return DecodeResult.Ok(doc, w)
    }

    // Config.packageMap's reading of one entry: "package=value", both halves trimmed and non-empty,
    // the first '=' after at least one character; null when the launcher skips the entry.
    fun packageMapEntry(entry: String): Pair<String, String>? {
        val eq = entry.indexOf('=')
        if (eq <= 0) return null
        val pkg = entry.substring(0, eq).javaTrim()
        val value = entry.substring(eq + 1).javaTrim()
        return if (pkg.isEmpty() || value.isEmpty()) null else pkg to value
    }

    // RemoteKeys.parseAppKeys for one entry: "keycode:package" with a keycode of one to six digits
    // or a KEYCODE_ name; null when the launcher skips it. A name is resolved only for the
    // navigation buttons (Android knows the rest); another name gives keycode -1 here.
    fun appKeyEntry(entry: String): Pair<Int, String>? {
        val parts = entry.split(":", limit = 2)
        if (parts.size != 2 || parts[1].javaTrim().isEmpty()) return null
        val key = parts[0].javaTrim()
        val code = when {
            DIGITS.matches(key) -> key.toInt().takeIf { it > 0 } ?: return null
            KEY_NAME.matches(key) -> NAVIGATION_KEY_NAMES[key] ?: -1
            else -> return null
        }
        return code to parts[1].javaTrim()
    }
    // RemoteKeys.parseAppScancodes (launcher 2.6) for one entry: "scan:<code>:<package>", the button by its
    // Linux key code, for remotes whose buttons share a keycode or have none Android names. Launcher 2.5
    // has no such entries and skips them (appKeyEntry gives null). Null when 2.6 would skip it too.
    fun appScanEntry(entry: String): Pair<Int, String>? {
        val parts = entry.split(":", limit = 3)
        if (parts.size != 3 || parts[0].javaTrim() != "scan" || parts[2].javaTrim().isEmpty()) return null
        val code = parts[1].javaTrim()
        if (!DIGITS.matches(code)) return null
        return code.toInt().takeIf { it > 0 }?.let { it to parts[2].javaTrim() }
    }

    private val DIGITS = Regex("[0-9]{1,6}")
    private val KEY_NAME = Regex("KEYCODE_[A-Z0-9_]+")

    // Config.raceCalendar (v2.5.0): a race needs to be an object with a sessions list holding at
    // least one session with a name and a start Instant.parse reads; the rest is left out on its
    // own. Values go through optString and optInt as the launcher reads them, and each race keeps
    // what RaceWeekend keeps: the session names trimmed and upper-cased, sorted by start.
    private fun readRaces(v: AValue, w: MutableList<String>): List<LauncherRace>? {
        if (v !is AValue.Arr) { w += "race_calendar is not a list; the launcher shows no race weekends"; return null }
        val out = ArrayList<LauncherRace>()
        v.items.forEachIndexed { i, item ->
            val race = item as? AValue.Obj
            val sessions = race?.map?.get("sessions") as? AValue.Arr
            if (race == null || sessions == null) { w += "race_calendar[$i] is not a race with a sessions list; the launcher leaves it out"; return@forEachIndexed }
            val kept = ArrayList<Pair<java.time.Instant, LauncherRaceSession>>()
            sessions.items.forEachIndexed { j, s ->
                val session = s as? AValue.Obj
                val name = session?.map?.get("name").androidOptString("").javaTrim()
                val start = session?.map?.get("start").androidOptString("")
                val at = if (session == null || name.isEmpty()) null else parseInstant(start)
                if (at == null) w += "race_calendar[$i].sessions[$j] has no name or no start time the launcher reads; it is left out"
                else kept += at to LauncherRaceSession(name.uppercase(), at.toString())
            }
            if (kept.isEmpty()) { w += "race_calendar[$i] has no session the launcher can count down to; it is left out"; return@forEachIndexed }
            out += LauncherRace(race.map["round"].androidOptInt(0), race.map["name"].androidOptString("Grand Prix").javaTrim(),
                race.map["place"].androidOptString("").javaTrim(), kept.sortedBy { it.first }.map { it.second })
        }
        return out
    }

    // java.time.Instant.parse, which Config.raceCalendar calls; null for what it refuses.
    fun parseInstant(s: String): java.time.Instant? = try { java.time.Instant.parse(s) } catch (e: java.time.format.DateTimeParseException) { null }

    // What RaceWeekend keeps of races built in code: sessions with a name and a readable start,
    // upper-cased and sorted by start, races with at least one session.
    private fun normalRaces(races: List<LauncherRace>): List<LauncherRace> = races.mapNotNull { r ->
        val sessions = r.sessions.mapNotNull { s ->
            val name = s.name.javaTrim()
            parseInstant(s.start)?.takeIf { name.isNotEmpty() }?.let { it to LauncherRaceSession(name.uppercase(), it.toString()) }
        }.sortedBy { it.first }.map { it.second }
        if (sessions.isEmpty()) null else LauncherRace(r.round, r.name.javaTrim(), r.place.javaTrim(), sessions)
    }

    private fun packageMap(entries: List<String>): Map<String, String> =
        entries.map { it.javaTrim() }.filter { it.isNotEmpty() }.mapNotNull(::packageMapEntry).toMap()

    // What Config.load would compute from this doc. Values the launcher would ignore (out of
    // range, a colour it cannot parse) fall back to `defaults` here too, so a doc built in code
    // previews the way the TV will show it. The festival is read from the doc alone, as the
    // launcher reads it from config.json alone; `defaults` give it only when there is no doc.
    fun effective(doc: LauncherConfigDoc?, defaults: EffectiveConfig): EffectiveConfig {
        if (doc == null) return defaults.copy(columns = maxOf(1, defaults.columns), rowsBelowDock = maxOf(0, defaults.rowsBelowDock),
            skyIntensity = defaults.skyIntensity.coerceIn(0, 100))
        fun texts(l: List<String>) = l.map { it.javaTrim() }.filter { it.isNotEmpty() }
        val fest = LauncherFestival.parse(doc.festival.orEmpty(), doc.festivalUntil.orEmpty(), doc.greeting.orEmpty(), doc.accentColor.orEmpty())
        val bg = doc.backgroundColor?.let { (AndroidColor.parseColor(it.javaTrim()) as? AndroidColor.Parse.Ok)?.argb?.or(0xFF000000.toInt()) }
        return EffectiveConfig(
            dockApps = doc.dockApps?.map { it.value } ?: defaults.dockApps,
            hiddenApps = doc.hiddenApps?.map { it.value } ?: defaults.hiddenApps,
            liveTvPackage = doc.liveTvPackage?.javaTrim() ?: defaults.liveTvPackage,
            liveTvAction = doc.liveTvAction?.javaTrim() ?: defaults.liveTvAction,
            sourcePanelService = doc.sourcePanelService?.javaTrim() ?: defaults.sourcePanelService,
            sourcePanelAction = doc.sourcePanelAction?.javaTrim() ?: defaults.sourcePanelAction,
            columns = maxOf(1, doc.columns?.takeIf { it in 1..8 } ?: defaults.columns),
            rowsBelowDock = maxOf(0, doc.rowsBelowDock?.takeIf { it in 0..3 } ?: defaults.rowsBelowDock),
            shamsiDate = doc.shamsiDate ?: defaults.shamsiDate,
            vpnApp = doc.vpnApp?.javaTrim() ?: defaults.vpnApp,
            showHomeSettingsShortcut = doc.showHomeSettingsShortcut ?: defaults.showHomeSettingsShortcut,
            backgroundColorArgb = bg ?: defaults.backgroundColorArgb,
            wallpaper = doc.wallpaper ?: defaults.wallpaper,
            inputAction = doc.inputAction?.javaTrim() ?: defaults.inputAction,
            moduleCards = doc.moduleCards ?: defaults.moduleCards,
            fillDock = doc.fillDock ?: defaults.fillDock,
            animations = doc.animations ?: defaults.animations,
            advancedEffects = doc.advancedEffects ?: defaults.advancedEffects,
            greetingName = doc.greetingName?.javaTrim() ?: defaults.greetingName,
            reducedMotion = doc.reducedMotion ?: defaults.reducedMotion,
            dockTitle = doc.dockTitle ?: defaults.dockTitle,
            dockRow = doc.dockRow ?: defaults.dockRow,
            reorderButton = doc.reorderButton ?: defaults.reorderButton,
            showStats = doc.showStats ?: defaults.showStats,
            appLibrary = doc.appLibrary ?: defaults.appLibrary,
            skyIntensity = (doc.skyIntensity?.takeIf { it in 0..100 } ?: defaults.skyIntensity).coerceIn(0, 100),
            settingsKeyKeycodes = doc.settingsKeyKeycodes?.takeIf { l -> l.all { it >= 1 } } ?: defaults.settingsKeyKeycodes,
            settingsKeyPanel = doc.settingsKeyPanel ?: defaults.settingsKeyPanel,
            festival = fest?.name ?: "",
            festivalUntil = fest?.until?.toString() ?: "",
            greeting = fest?.greeting ?: "",
            accentColorArgb = fest?.accent ?: 0,
            launchActions = doc.launchActions?.let(::packageMap) ?: defaults.launchActions,
            appLabels = doc.appLabels?.let(::packageMap) ?: defaults.appLabels,
            appBanners = doc.appBanners?.let(::packageMap) ?: defaults.appBanners,
            inputKeyKeycodes = doc.inputKeyKeycodes?.takeIf { l -> l.all { it >= 1 } } ?: defaults.inputKeyKeycodes,
            appKeys = doc.appKeys?.let(::texts) ?: defaults.appKeys,
            cinematicHome = doc.cinematicHome ?: defaults.cinematicHome,
            // Read from config.json alone, like the festival: config.xml has none of these.
            wallpaperRotationMinutes = doc.wallpaperRotationMinutes?.takeIf { it in 0..1440 } ?: 0,
            wallpaperDim = doc.wallpaperDim?.takeIf { it in 0..90 } ?: 0,
            raceCalendar = doc.raceCalendar?.let(::normalRaces) ?: emptyList(),
        )
    }

    // Canonical form: Config.java's key order, two-space indent, lists on one line, UTF-8 without
    // BOM, a final newline. Refuses anything the launcher would read differently from the doc.
    fun encode(doc: LauncherConfigDoc): ByteArray {
        for (k in doc.unknown.keys) require(k !in MODELLED) { "unknown carries the modelled key $k" }
        fun canonicalString(key: String, v: String?) { if (v != null) require(v == v.javaTrim()) { "$key has leading or trailing whitespace the launcher would trim" } }
        canonicalString("live_tv_package", doc.liveTvPackage)
        canonicalString("live_tv_action", doc.liveTvAction)
        canonicalString("source_panel_service", doc.sourcePanelService)
        canonicalString("source_panel_action", doc.sourcePanelAction)
        canonicalString("input_action", doc.inputAction)
        canonicalString("vpn_app", doc.vpnApp)
        canonicalString("greeting_name", doc.greetingName)
        canonicalString("festival", doc.festival)
        canonicalString("festival_until", doc.festivalUntil)
        canonicalString("greeting", doc.greeting)
        canonicalString("accent_color", doc.accentColor)
        doc.columns?.let { require(it in 1..8) { "columns $it is outside 1 to 8" } }
        doc.rowsBelowDock?.let { require(it in 0..3) { "rows_below_dock $it is outside 0 to 3" } }
        doc.skyIntensity?.let { require(it in 0..100) { "sky_intensity $it is outside 0 to 100" } }
        doc.settingsKeyKeycodes?.let { l -> require(l.all { it >= 1 }) { "settings_key_keycodes holds a keycode below 1, so the launcher would ignore the list" } }
        doc.backgroundColor?.let {
            require(it == it.javaTrim() && AndroidColor.parseColor(it) is AndroidColor.Parse.Ok) { "background_color '$it' is not a colour the launcher reads" }
        }
        fun canonicalTexts(key: String, l: List<String>?) {
            l?.forEach { require(it.isNotEmpty() && it == it.javaTrim()) { "$key holds '$it', which the launcher would trim or skip" } }
        }
        canonicalTexts("launch_actions", doc.launchActions)
        canonicalTexts("app_labels", doc.appLabels)
        canonicalTexts("app_banners", doc.appBanners)
        canonicalTexts("app_keys", doc.appKeys)
        doc.inputKeyKeycodes?.let { l -> require(l.all { it >= 1 }) { "input_key_keycodes holds a keycode below 1, so the launcher would ignore the list" } }
        doc.wallpaperRotationMinutes?.let { require(it in 0..1440) { "wallpaper_rotation_minutes $it is outside 0 to 1440" } }
        doc.wallpaperDim?.let { require(it in 0..90) { "wallpaper_dim $it is outside 0 to 90" } }
        doc.raceCalendar?.let { races -> require(normalRaces(races) == races) { "race_calendar is not as the launcher keeps it (a race without sessions, a blank or lower-case session name, an unreadable or unsorted start)" } }

        val entries = ArrayList<Pair<String, String>>()
        fun list(v: List<PackageName>) = v.joinToString(", ", "[", "]") { quote(it.value) }
        fun texts(v: List<String>) = v.joinToString(", ", "[", "]") { quote(it) }
        fun raceList(v: List<LauncherRace>) = v.joinToString(", ", "[", "]") { r ->
            "{\"round\": ${r.round}, \"name\": ${quote(r.name)}, \"place\": ${quote(r.place)}, \"sessions\": " +
                r.sessions.joinToString(", ", "[", "]") { "{\"name\": ${quote(it.name)}, \"start\": ${quote(it.start)}}" } + "}"
        }
        for (key in KEY_ORDER) {
            val value: String? = when (key) {
                "dock_apps" -> doc.dockApps?.let(::list)
                "fill_dock" -> doc.fillDock?.toString()
                "hidden_apps" -> doc.hiddenApps?.let(::list)
                "live_tv_package" -> doc.liveTvPackage?.let(::quote)
                "live_tv_action" -> doc.liveTvAction?.let(::quote)
                "launch_actions" -> doc.launchActions?.let(::texts)
                "app_labels" -> doc.appLabels?.let(::texts)
                "app_banners" -> doc.appBanners?.let(::texts)
                "source_panel_service" -> doc.sourcePanelService?.let(::quote)
                "source_panel_action" -> doc.sourcePanelAction?.let(::quote)
                "input_action" -> doc.inputAction?.let(::quote)
                "columns" -> doc.columns?.toString()
                "rows_below_dock" -> doc.rowsBelowDock?.toString()
                "animations" -> doc.animations?.toString()
                "advanced_effects" -> doc.advancedEffects?.toString()
                "shamsi_date" -> doc.shamsiDate?.toString()
                "greeting_name" -> doc.greetingName?.let(::quote)
                "module_cards" -> doc.moduleCards?.toString()
                "reduced_motion" -> doc.reducedMotion?.toString()
                "dock_title" -> doc.dockTitle?.toString()
                "dock_row" -> doc.dockRow?.toString()
                "reorder_button" -> doc.reorderButton?.toString()
                "vpn_app" -> doc.vpnApp?.let(::quote)
                "show_home_settings_shortcut" -> doc.showHomeSettingsShortcut?.toString()
                "show_stats" -> doc.showStats?.toString()
                "app_library" -> doc.appLibrary?.toString()
                "background_color" -> doc.backgroundColor?.let(::quote)
                "sky_intensity" -> doc.skyIntensity?.toString()
                "settings_key_keycodes" -> doc.settingsKeyKeycodes?.joinToString(", ", "[", "]")
                "settings_key_panel" -> doc.settingsKeyPanel?.toString()
                "input_key_keycodes" -> doc.inputKeyKeycodes?.joinToString(", ", "[", "]")
                "app_keys" -> doc.appKeys?.let(::texts)
                "cinematic_home" -> doc.cinematicHome?.toString()
                "wallpaper" -> doc.wallpaper?.toString()
                "wallpaper_rotation_minutes" -> doc.wallpaperRotationMinutes?.toString()
                "wallpaper_dim" -> doc.wallpaperDim?.toString()
                "festival" -> doc.festival?.let(::quote)
                "festival_until" -> doc.festivalUntil?.let(::quote)
                "greeting" -> doc.greeting?.let(::quote)
                "accent_color" -> doc.accentColor?.let(::quote)
                "race_calendar" -> doc.raceCalendar?.let(::raceList)
                else -> error("no field for $key")
            }
            if (value != null) entries += key to value
        }
        for (k in doc.unknown.keys.filter { it !in KEY_ORDER }.sorted()) entries += k to element(doc.unknown.getValue(k))
        val text = if (entries.isEmpty()) "{}\n"
        else entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${quote(k)}: $v" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "encoded config is ${bytes.size} bytes, over the launcher's 64 KB cap" }
        return bytes
    }

    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    private fun element(e: JsonElement): String = when (e) {
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            e.isString -> quote(e.content)
            e.content == "true" || e.content == "false" -> e.content
            NUMBER.matches(e.content) -> e.content
            else -> throw IllegalArgumentException("carried value ${e.content} is not a finite JSON number, boolean or null")
        }
        is JsonArray -> e.joinToString(", ", "[", "]") { element(it) }
        is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "${quote(k)}: ${element(v)}" }
    }

    // JSON string syntax JSONTokener reads back to the same chars: quote, backslash and control
    // chars escaped, and lone surrogates escaped so the UTF-8 encoder cannot replace them.
    private fun hex4(c: Char): String = "\\u" + c.code.toString(16).padStart(4, '0')

    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append(hex4(c))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { b.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> b.append(hex4(c))
                else -> b.append(c)
            }
            i++
        }
        return b.append('"').toString()
    }
}
