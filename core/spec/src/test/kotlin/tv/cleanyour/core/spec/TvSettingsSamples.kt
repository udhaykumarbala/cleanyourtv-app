package tv.cleanyour.core.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.ConfigKeyType.BOOL
import tv.cleanyour.core.spec.ConfigKeyType.INT
import tv.cleanyour.core.spec.ConfigKeyType.STRING
import tv.cleanyour.core.spec.ConfigKeyType.STRING_LIST
import tv.cleanyour.core.spec.SettingsSection.APPS
import tv.cleanyour.core.spec.SettingsSection.LOOK
import tv.cleanyour.core.spec.SettingsSection.SCREENSAVER
import tv.cleanyour.core.spec.SettingsSection.START_UP
import tv.cleanyour.core.spec.SettingsSection.TOP_BAR
import tv.cleanyour.core.spec.SettingsSection.WALLPAPER
import kotlin.random.Random

// Generators for the settings document tests: random config.json files over every key a launcher
// reads (valid values, values the launcher ignores, keys no launcher reads), random section values,
// and random look pushes.
object TvSettingsSamples {
    // The 0.5.0 keys of the brief: type, owner, and the range of an integer (Config.java at b06ab21).
    val BRIEF: Map<String, Triple<ConfigKeyType, SettingsSection?, Pair<Int, Int>?>> = linkedMapOf(
        "show_inputs_pill" to Triple(BOOL, TOP_BAR, null), "show_network_pill" to Triple(BOOL, TOP_BAR, null),
        "reorder_button" to Triple(BOOL, APPS, null), "library_button" to Triple(BOOL, APPS, null),
        "show_library_pill" to Triple(BOOL, APPS, null), "launcher_settings_button" to Triple(BOOL, TOP_BAR, null),
        "greeting_name" to Triple(STRING, TOP_BAR, null), "show_greeting" to Triple(BOOL, LOOK, null),
        "date_below_clock" to Triple(BOOL, LOOK, null), "header_white" to Triple(BOOL, LOOK, null),
        "shamsi_date" to Triple(BOOL, TOP_BAR, null), "lunar_date" to Triple(BOOL, TOP_BAR, null),
        "lunar_utc_offset" to Triple(INT, TOP_BAR, -1 to 14), "show_stats" to Triple(BOOL, TOP_BAR, null),
        "vpn_pill" to Triple(BOOL, TOP_BAR, null), "vpn_app" to Triple(STRING, TOP_BAR, null),
        "top_bar_apps" to Triple(STRING_LIST, TOP_BAR, null), "wifi_status_color" to Triple(BOOL, LOOK, null),
        "wifi_status_warnings" to Triple(BOOL, LOOK, null), "icon_only_buttons" to Triple(BOOL, LOOK, null),
        "icon_only_inputs" to Triple(BOOL, LOOK, null), "wifi_label_width" to Triple(INT, TOP_BAR, 0 to 300),
        "top_menu" to Triple(BOOL, LOOK, null), "tile_menu" to Triple(BOOL, APPS, null),
        "app_library" to Triple(BOOL, APPS, null), "hidden_apps" to Triple(STRING_LIST, APPS, null),
        "app_labels" to Triple(STRING_LIST, APPS, null), "boot_to_live_tv" to Triple(BOOL, START_UP, null),
        "home_idle_clock_minutes" to Triple(INT, SCREENSAVER, 0 to 1440), "clock_wallpaper" to Triple(BOOL, SCREENSAVER, null),
        "clock_wallpaper_dim" to Triple(INT, SCREENSAVER, 0 to 90), "clock_glow" to Triple(BOOL, SCREENSAVER, null),
        "wallpaper_captions" to Triple(BOOL, WALLPAPER, null),
    )

    // The sections with a typed value of their own in TvSettings.
    val TYPED: List<SettingsSection> = listOf(TOP_BAR, APPS, SCREENSAVER, START_UP, WALLPAPER)

    val apps: List<PackageName> = (listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
        "com.mediatek.wwtv.tvcenter", "com.mitv.gallery") + List(10) { "com.example.app$it" }).map(::PackageName)

    // A doc as its keys, as the codec writes them.
    fun flat(doc: LauncherConfigDoc): Map<String, JsonElement> = flat(LauncherConfigCodec.encode(doc))

    fun flat(bytes: ByteArray): Map<String, JsonElement> = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    // A value the launcher reads for this key.
    fun sample(k: ConfigKey, rnd: Random): JsonElement = when (k.type) {
        BOOL -> JsonPrimitive(rnd.nextBoolean())
        INT -> JsonPrimitive(rnd.nextInt(k.min, k.max + 1))
        STRING -> JsonPrimitive(when (k.name) {
            "glow_style" -> listOf("radial", "horizon").random(rnd)
            "glass_style" -> listOf("glass", "rim", "halo").random(rnd)
            "festival" -> "diwali"
            "festival_until" -> "2026-11-10"
            "greeting" -> "Happy Diwali"
            "accent_color" -> "#FFB23C"
            "stock_home_redirect" -> "com.google.android.tvlauncher"
            "greeting_name" -> listOf("", "Asha", "Ana María").random(rnd)
            else -> "com.example.s${rnd.nextInt(9)}"
        })
        ConfigKeyType.COLOR -> JsonPrimitive("#%06X".format(rnd.nextInt(0x1000000)))
        STRING_LIST -> JsonArray(List(rnd.nextInt(0, 4)) { i ->
            val p = apps[(i * 3 + rnd.nextInt(3)) % apps.size].value
            JsonPrimitive(when (k.name) {
                "app_labels" -> "$p=Name $i"
                "launch_actions" -> "$p=android.intent.action.VIEW"
                "app_banners" -> "$p=banner_tv"
                "app_keys" -> "${190 + i}:$p"
                else -> p
            })
        }.distinct())
        ConfigKeyType.INT_LIST -> JsonArray(List(rnd.nextInt(1, 3)) { JsonPrimitive(rnd.nextInt(100, 300)) })
        ConfigKeyType.OBJECT -> JsonObject(mapOf("morning" to JsonArray(listOf(JsonPrimitive("a.jpg")))))
        ConfigKeyType.RACES -> JsonArray(listOf(JsonObject(mapOf("round" to JsonPrimitive(1), "name" to JsonPrimitive("Brazil"),
            "place" to JsonPrimitive("Sao Paulo"), "sessions" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("RACE"),
                "start" to JsonPrimitive("2026-11-09T17:00:00Z")))))))))
    }

    // A value the launcher ignores for this key (a wrong type, or out of range), never one it would crash on.
    private fun ignored(k: ConfigKey, rnd: Random): JsonElement = when (k.type) {
        INT -> if (rnd.nextBoolean()) JsonPrimitive(k.max + 1) else JsonPrimitive("ten")
        STRING -> JsonPrimitive(5)
        else -> JsonPrimitive("yes")
    }

    // Every key a launcher reads with a value it reads, and one key none reads.
    fun everyKey(rnd: Random = Random(1)): ByteArray =
        JsonObject(ConfigKeys.ALL.associate { it.name to sample(it, rnd) } + ("zz_future_key" to JsonPrimitive(true))).toString().encodeToByteArray()

    // A config.json as a TV may have it: about half the keys, most with a value the launcher reads,
    // some with one it ignores, and keys no launcher reads.
    fun file(rnd: Random): ByteArray {
        val m = LinkedHashMap<String, JsonElement>()
        for (k in ConfigKeys.ALL.shuffled(rnd)) {
            if (rnd.nextBoolean()) continue
            m[k.name] = if (rnd.nextInt(8) == 0) ignored(k, rnd) else sample(k, rnd)
        }
        repeat(rnd.nextInt(3)) { i ->
            m["zz_unknown_$i"] = listOf(JsonPrimitive(i), JsonPrimitive("x"), JsonNull, JsonArray(listOf(JsonPrimitive(true)))).random(rnd)
        }
        return JsonObject(m).toString().encodeToByteArray()
    }

    private fun <T> maybe(rnd: Random, v: () -> T): T? = if (rnd.nextInt(3) == 0) null else v()
    private fun bool(rnd: Random): Boolean? = maybe(rnd) { rnd.nextBoolean() }
    private fun some(rnd: Random): List<PackageName> = apps.shuffled(rnd).take(rnd.nextInt(0, 4))

    fun topBar(rnd: Random) = TopBarSettings(bool(rnd), bool(rnd), bool(rnd), maybe(rnd) { listOf("", "Asha", "Ana María").random(rnd) },
        bool(rnd), bool(rnd), maybe(rnd) { rnd.nextInt(-1, 15) }, bool(rnd), bool(rnd), maybe(rnd) { listOf("", "com.example.vpn").random(rnd) },
        maybe(rnd) { some(rnd) }, maybe(rnd) { rnd.nextInt(0, 301) })

    fun apps(rnd: Random) = AppsSettings(bool(rnd), bool(rnd), bool(rnd), bool(rnd), maybe(rnd) { some(rnd) },
        maybe(rnd) { some(rnd).associateWith { "Name ${it.value.length}" } }, bool(rnd))

    fun screensaver(rnd: Random) = ScreensaverSettings(maybe(rnd) { rnd.nextInt(0, 1441) }, bool(rnd), maybe(rnd) { rnd.nextInt(0, 91) }, bool(rnd))

    fun startUp(rnd: Random) = StartUpSettings(bool(rnd))

    fun wallpaper(rnd: Random) = WallpaperSettings(bool(rnd))

    // A typed section push: the new value, and the document with it.
    fun edit(s: TvSettings, section: SettingsSection, rnd: Random): Pair<Any, TvSettings> = when (section) {
        TOP_BAR -> topBar(rnd).let { it to s.withTopBar(it) }
        APPS -> apps(rnd).let { it to s.withApps(it) }
        SCREENSAVER -> screensaver(rnd).let { it to s.withScreensaver(it) }
        START_UP -> startUp(rnd).let { it to s.withStartUp(it) }
        WALLPAPER -> wallpaper(rnd).let { it to s.withWallpaper(it) }
        else -> error("$section has no typed value")
    }

    fun typed(s: TvSettings, section: SettingsSection): Any = when (section) {
        TOP_BAR -> s.topBar
        APPS -> s.apps
        SCREENSAVER -> s.screensaver
        START_UP -> s.startUp
        WALLPAPER -> s.wallpaper
        else -> error("$section has no typed value")
    }

    // A look push as LookKeys.build makes it, over the look2 cross product, the pictures, apps in a row,
    // docks and the 3.0 switches.
    fun look(rnd: Random, specs: List<LookSpec>): LauncherConfigDoc {
        val extras = LookExtras(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(),
            rnd.nextBoolean(), rnd.nextBoolean(), TilesChoice.entries.random(rnd), HighlightChoice.entries.random(rnd))
        val doc = LookKeys.build(specs.random(rnd), LookInputs.pictures.random(rnd).second, rnd.nextInt(1, 9),
            apps.shuffled(rnd).take(rnd.nextInt(0, apps.size)), rnd.nextBoolean(), if (rnd.nextBoolean()) extras else LookExtras.DEFAULT)
        return if (rnd.nextBoolean()) doc.copy(fillDock = true) else doc
    }
}
