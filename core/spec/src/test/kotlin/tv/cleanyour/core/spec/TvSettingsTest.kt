package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import tv.cleanyour.core.model.PackageName
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// 0.5.0 F0: the settings document. A push from one section keeps every key of the others; every key
// goes through the document as the TV had it; a TV's launcher gets only the keys it reads.
class TvSettingsTest {
    private fun flat(b: ByteArray) = TvSettingsSamples.flat(b)
    private fun flat(d: LauncherConfigDoc) = TvSettingsSamples.flat(d)
    private fun keys(s: TvSettings) = flat(s.encode())
    private fun outside(m: Map<String, *>, sections: Set<SettingsSection>) = m.filterKeys { ConfigKeys.of(it)?.owner !in sections }
    private fun inside(m: Map<String, *>, sections: Set<SettingsSection>) = m.filterKeys { ConfigKeys.of(it)?.owner in sections }

    // The property of F0, over random files: a push of one typed section changes its own keys to the
    // value pushed and leaves every other key, a key no launcher reads included, as the file had it,
    // in the document and after the bytes went to the TV and were read back.
    @Test fun aSectionPushKeepsEveryOtherSectionsKeys() {
        val rnd = Random(500)
        repeat(2000) { n ->
            val before = TvSettings.seed(TvSettingsSamples.file(rnd), null)
            val section = TvSettingsSamples.TYPED.random(rnd)
            val (value, after) = TvSettingsSamples.edit(before, section, rnd)
            val label = "case $n, $section"
            assertEquals(outside(keys(before), setOf(section)), outside(keys(after), setOf(section)), label)
            assertEquals(value, TvSettingsSamples.typed(after, section), label)
            val back = TvSettings.seed(after.encode(), null)
            assertEquals(value, TvSettingsSamples.typed(back, section), "$label, read back")
            assertEquals(keys(after), keys(back), "$label, read back")
            for (other in TvSettingsSamples.TYPED - section) {
                assertEquals(TvSettingsSamples.typed(before, other), TvSettingsSamples.typed(after, other), "$label: $other")
            }
        }
    }

    // The same for the look push (Look, Dock, Cards and modules, Setup): LookKeys.build's doc goes in
    // whole for LOOK_PUSH, and every key of the other sections stays.
    @Test fun aLookPushKeepsEveryOtherSectionsKeys() {
        val rnd = Random(501)
        val specs = LookInputs.cross().toList()
        repeat(2000) { n ->
            val before = TvSettings.seed(TvSettingsSamples.file(rnd), null)
            val built = TvSettingsSamples.look(rnd, specs)
            val after = before.withSections(built, TvSettings.LOOK_PUSH)
            assertEquals(outside(keys(before), TvSettings.LOOK_PUSH), outside(keys(after), TvSettings.LOOK_PUSH), "case $n")
            assertEquals(flat(built), inside(keys(after), TvSettings.LOOK_PUSH), "case $n: the look's own keys are the look's")
            for (section in TvSettingsSamples.TYPED) assertEquals(TvSettingsSamples.typed(before, section), TvSettingsSamples.typed(after, section))
        }
    }

    // Pushes one after another: each keeps what the others sent.
    @Test fun pushesInTurnKeepEachOthersKeys() {
        val rnd = Random(502)
        val specs = LookInputs.cross().toList()
        var s = TvSettings.seed(TvSettingsSamples.file(rnd), null)
        val sent = HashMap<SettingsSection, Any>()
        var look: LauncherConfigDoc? = null
        repeat(300) {
            if (rnd.nextInt(4) == 0) {
                look = TvSettingsSamples.look(rnd, specs)
                s = s.withSections(look!!, TvSettings.LOOK_PUSH)
            } else {
                val section = TvSettingsSamples.TYPED.random(rnd)
                val (value, next) = TvSettingsSamples.edit(s, section, rnd)
                sent[section] = value
                s = next
            }
            s = TvSettings.seed(s.encode(), null)   // the TV has it, and the next push starts from what it has
            for ((section, value) in sent) assertEquals(value, TvSettingsSamples.typed(s, section))
            look?.let { assertEquals(flat(it), inside(keys(s), TvSettings.LOOK_PUSH)) }
        }
    }

    // Every key a launcher reads, and one none reads, goes through the document as the file had it.
    @Test fun everyKeyRoundTrips() {
        val rnd = Random(503)
        for (k in ConfigKeys.ALL) repeat(5) {
            val value = TvSettingsSamples.sample(k, rnd)
            val file = kotlinx.serialization.json.JsonObject(mapOf(k.name to value, "zz_future_key" to JsonPrimitive("kept"))).toString().encodeToByteArray()
            val s = TvSettings.seed(file, null)
            assertEquals(mapOf(k.name to value, "zz_future_key" to JsonPrimitive("kept")), keys(s), k.name)
            assertEquals(keys(s), keys(TvSettings.seed(s.encode(), null)), k.name)
        }
        val all = TvSettings.seed(TvSettingsSamples.everyKey(), null)
        assertEquals(ConfigKeys.ALL.map { it.name }.toSet() + "zz_future_key", keys(all).keys)
    }

    // Each field of each typed section on its own, off its default: written, read back, and nothing else written.
    @Test fun everyTypedFieldRoundTrips() {
        val apps = TvSettingsSamples.apps
        val one = listOf<Pair<String, (TvSettings) -> TvSettings>>(
            "show_inputs_pill" to { it.withTopBar(it.topBar.copy(showInputsPill = false)) },
            "show_network_pill" to { it.withTopBar(it.topBar.copy(showNetworkPill = false)) },
            "launcher_settings_button" to { it.withTopBar(it.topBar.copy(launcherSettingsButton = true)) },
            "greeting_name" to { it.withTopBar(it.topBar.copy(greetingName = "Ana María")) },
            "shamsi_date" to { it.withTopBar(it.topBar.copy(shamsiDate = true)) },
            "lunar_date" to { it.withTopBar(it.topBar.copy(lunarDate = true)) },
            "lunar_utc_offset" to { it.withTopBar(it.topBar.copy(lunarUtcOffset = 7)) },
            "show_stats" to { it.withTopBar(it.topBar.copy(showStats = true)) },
            "vpn_pill" to { it.withTopBar(it.topBar.copy(vpnPill = true)) },
            "vpn_app" to { it.withTopBar(it.topBar.copy(vpnApp = "com.example.vpn")) },
            "top_bar_apps" to { it.withTopBar(it.topBar.copy(topBarApps = apps.take(2))) },
            "wifi_label_width" to { it.withTopBar(it.topBar.copy(wifiLabelWidth = 120)) },
            "tile_menu" to { it.withApps(it.apps.copy(tileMenu = true)) },
            "app_library" to { it.withApps(it.apps.copy(appLibrary = true)) },
            "library_button" to { it.withApps(it.apps.copy(libraryButton = true)) },
            "show_library_pill" to { it.withApps(it.apps.copy(showLibraryPill = false)) },
            "hidden_apps" to { it.withApps(it.apps.copy(hiddenApps = apps.drop(5).take(3))) },
            "app_labels" to { it.withApps(it.apps.copy(appLabels = mapOf(apps[0] to "Films", apps[1] to "Videos = fun"))) },
            "reorder_button" to { it.withApps(it.apps.copy(reorderButton = true)) },
            "home_idle_clock_minutes" to { it.withScreensaver(it.screensaver.copy(idleMinutes = 10)) },
            "clock_wallpaper" to { it.withScreensaver(it.screensaver.copy(clockWallpaper = true)) },
            "clock_wallpaper_dim" to { it.withScreensaver(it.screensaver.copy(clockWallpaperDim = 70)) },
            "clock_glow" to { it.withScreensaver(it.screensaver.copy(clockGlow = false)) },
            "boot_to_live_tv" to { it.withStartUp(StartUpSettings(bootToLiveTv = true)) },
            "wallpaper_captions" to { it.withWallpaper(WallpaperSettings(captions = true)) },
        )
        val typed = TvSettingsSamples.TYPED.flatMap(ConfigKeys::owned).toSet()
        assertEquals(typed, one.map { it.first }.toSet(), "every typed key has its case")
        val empty = TvSettings.seed(null, null)
        for ((key, set) in one) {
            val s = set(empty)
            assertEquals(setOf(key), keys(s).keys, key)
            val back = TvSettings.seed(s.encode(), null)
            for (section in TvSettingsSamples.TYPED) assertEquals(TvSettingsSamples.typed(s, section), TvSettingsSamples.typed(back, section), key)
            assertTrue(TvSettingsSamples.TYPED.any { TvSettingsSamples.typed(back, it) != TvSettingsSamples.typed(empty, it) }, key)
        }
        assertEquals("\"app_labels\": [\"com.netflix.ninja=Films\", \"com.google.android.youtube.tv=Videos = fun\"]",
            one.first { it.first == "app_labels" }.second(empty).encode().decodeToString().lines()[1].trim())
    }

    // A section value says what the file says; a key it does not change keeps its bytes, even a value
    // the launcher ignores; values are brought into the launcher's rules.
    @Test fun aSectionWritesOnlyWhatChanged() {
        val file = "{\"show_inputs_pill\": \"no\", \"greeting_name\": \"Asha\", \"wifi_label_width\": 900, \"hidden_apps\": [\"com.mitv.gallery\", \"  \"]}"
            .encodeToByteArray()
        val s = TvSettings.seed(file, null)
        assertEquals(TopBarSettings(greetingName = "Asha"), s.topBar, "values the launcher ignores read as not set")
        assertEquals(listOf(PackageName("com.mitv.gallery")), s.apps.hiddenApps)
        assertEquals(s.encode().decodeToString(), s.withTopBar(s.topBar).withApps(s.apps).encode().decodeToString())
        val t = s.withTopBar(s.topBar.copy(showStats = true))
        assertEquals(JsonPrimitive("no"), keys(t)["show_inputs_pill"], "not changed, so as the file had it")
        assertEquals(JsonPrimitive(900), keys(t)["wifi_label_width"])
        val u = s.withTopBar(TopBarSettings(greetingName = "  Ana ", lunarUtcOffset = 40, wifiLabelWidth = -5, showInputsPill = true))
        assertEquals(TopBarSettings(greetingName = "Ana", lunarUtcOffset = 14, wifiLabelWidth = 0, showInputsPill = true), u.topBar)
        val v = s.withApps(s.apps.copy(appLabels = mapOf(PackageName("com.netflix.ninja") to "  ")))
        assertEquals(emptyMap(), v.apps.appLabels, "a blank name is no name")
        assertEquals(TopBarSettings(), s.withTopBar(TopBarSettings()).topBar)
        assertEquals(null, keys(s.withTopBar(TopBarSettings()))["greeting_name"], "null takes a key it changed out")
        assertEquals(JsonPrimitive("no"), keys(s.withTopBar(TopBarSettings()))["show_inputs_pill"], "already null, so as the file had it")
    }

    // Launcher version awareness: a TV gets only the keys its launcher reads (ConfigKeys.since); not
    // known is the bundled launcher, which reads them all; a key no launcher we know reads is kept.
    @Test fun aTvGetsOnlyTheKeysItsLauncherReads() {
        val file = TvSettingsSamples.everyKey()
        for (vc in listOf(1L, 2L, 4L, 5L, 6L, 7L, 8L, 9L, 12L)) {
            val want = ConfigKeys.ALL.filter { it.since <= vc }.map { it.name }.toSet() + "zz_future_key"
            assertEquals(want, flat(TvSettings.seed(file, vc).encode()).keys, "versionCode $vc")
        }
        for (vc in listOf(null, 0L, -1L)) assertEquals(ConfigKeys.ALL.size + 1, flat(TvSettings.seed(file, vc).encode()).size, "versionCode $vc")
        // A 2.7 TV never gets a 3.0 key, from a section or a look with the 3.0 switches on.
        val on27 = TvSettings.seed(file, LookGates.FIRST_27_VERSION_CODE)
        val look = LookKeys.build(LookKeys.spec(StyleId.GLASS), LookInputs.pictures.first().second, 6, TvSettingsSamples.apps.take(6), cards = true,
            LookExtras(oneRow = true, hideGreeting = true, dateBelow = true, brightDate = true, wifiWarnings = true, tiles = TilesChoice.ICONS))
        assertTrue(flat(look).keys.any { ConfigKeys.of(it)!!.since == ConfigKeys.V30 }, "the look has 3.0 keys")
        val sent = flat(on27.withSections(look, TvSettings.LOOK_PUSH).withTopBar(TopBarSettings(vpnPill = true, lunarDate = true, showStats = true))
            .withScreensaver(ScreensaverSettings(clockGlow = false)).encode())
        assertEquals(emptyList(), sent.keys.filter { ConfigKeys.of(it)?.since == ConfigKeys.V30 })
        assertEquals(JsonPrimitive(true), sent["show_stats"])
        // The document still holds them, so the same TV after its update gets them.
        assertEquals(TopBarSettings(vpnPill = true, lunarDate = true, showStats = true), on27.withTopBar(TopBarSettings(vpnPill = true,
            lunarDate = true, showStats = true)).topBar)
    }

    // The document starts from the TV's file: none, or one the launcher ignores whole, is empty.
    @Test fun theSeedIsTheTvsFile() {
        assertEquals(LauncherConfigDoc(), TvSettings.seed(null, null).doc)
        assertEquals(LauncherConfigDoc(), TvSettings.seed("not json".encodeToByteArray(), null).doc)
        assertEquals("{}\n", TvSettings.seed(null, 9L).encode().decodeToString())
        val file = TvSettingsSamples.everyKey()
        val doc = (LauncherConfigCodec.decode(file) as DecodeResult.Ok).doc
        assertEquals(doc, TvSettings.seed(file, null).doc)
        assertEquals(LauncherConfigCodec.encode(doc).decodeToString(), TvSettings.seed(file, null).encode().decodeToString())
    }

    // One owner per key: a push may not carry another section's key.
    @Test fun aPushCannotWriteAnotherSectionsKey() {
        val s = TvSettings.seed(null, null)
        assertFailsWith<IllegalArgumentException> { s.withSections(LauncherConfigDoc(greetingName = "Asha"), TvSettings.LOOK_PUSH) }
        assertFailsWith<IllegalArgumentException> {
            s.withSections(LauncherConfigDoc(unknown = mapOf("zz_future_key" to JsonPrimitive(true))), TvSettings.LOOK_PUSH)
        }
        val owners = ConfigKeys.ALL.groupBy { it.owner }.mapValues { (_, v) -> v.map { it.name } }
        assertEquals(ConfigKeys.ALL.size, owners.values.sumOf { it.size })
        for (section in SettingsSection.entries) assertTrue(ConfigKeys.owned(section).isNotEmpty(), "$section owns nothing")
    }
}
