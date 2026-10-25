package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import org.junit.Test
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LauncherConfigCodecTest {
    private fun ok(s: String) = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(s.encodeToByteArray()), s)
    private fun rejected(b: ByteArray) = assertIs<DecodeResult.Rejected>(LauncherConfigCodec.decode(b))

    @Test fun decodesTheUpstreamThemes() {
        val t = Fixtures.upstreamThemes().associate { it.name to LauncherConfigCodec.decode(it.configJson) }
        val aurora = assertIs<DecodeResult.Ok>(t.getValue("aurora")).doc
        assertEquals(LauncherConfigDoc(backgroundColor = "#070A14", wallpaper = true), aurora)
        assertEquals(0xFF070A14.toInt(), LauncherConfigCodec.effective(aurora, LauncherConfigCodec.builtInDefaults).backgroundColorArgb)
        val six = assertIs<DecodeResult.Ok>(t.getValue("six-columns")).doc
        assertEquals(6, six.columns); assertEquals(1, six.rowsBelowDock); assertEquals(false, six.wallpaper)
        val def = assertIs<DecodeResult.Ok>(t.getValue("default"))
        assertEquals(emptyList(), def.warnings)
        // The default theme is the built-in look written out.
        val e = LauncherConfigCodec.effective(def.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(LauncherConfigCodec.builtInDefaults.copy(wallpaper = false), e)
        assertEquals(false, def.doc.reducedMotion)
        // v2.4.0's festive theme: a festival with an end date and an accent.
        val dussehra = assertIs<DecodeResult.Ok>(t.getValue("dussehra"))
        assertEquals(emptyList(), dussehra.warnings)
        val d = LauncherConfigCodec.effective(dussehra.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(listOf("dussehra", "2026-10-21", "", 0xFFFFB23C.toInt()), listOf(d.festival, d.festivalUntil, d.greeting, d.accentColorArgb))
    }

    // RFC-W4-2: the seventeen keys launcher v2.4.0 added are fields, decoded in Config.java's
    // order, written back in that order, and previewed by effective(); none lands in `unknown`.
    @Test fun theV24KeysAreModelled() {
        val all = "{\n  \"dock_apps\": [\"a.b\"],\n  \"fill_dock\": false,\n  \"columns\": 5,\n  \"animations\": false,\n" +
            "  \"advanced_effects\": true,\n  \"greeting_name\": \"Mr. Trịnh\",\n  \"module_cards\": false,\n  \"reduced_motion\": true,\n" +
            "  \"dock_title\": false,\n  \"dock_row\": false,\n  \"reorder_button\": true,\n  \"show_stats\": true,\n" +
            "  \"app_library\": true,\n  \"sky_intensity\": 60,\n  \"settings_key_keycodes\": [83, 384],\n  \"settings_key_panel\": true,\n" +
            "  \"wallpaper\": true,\n  \"festival\": \"Diwali\",\n  \"festival_until\": \"2026-11-09\",\n  \"greeting\": \"Shubh  Deepavali\",\n" +
            "  \"accent_color\": \"#ffb23c\"\n}\n"
        val r = ok(all)
        assertEquals(emptyList(), r.warnings)
        assertEquals(emptyMap(), r.doc.unknown)
        assertEquals(all, LauncherConfigCodec.encode(r.doc).decodeToString())
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(
            listOf(false, false, true, "Mr. Trịnh", true, false, false, true, true, true, 60, listOf(83, 384), true),
            listOf(e.fillDock, e.animations, e.advancedEffects, e.greetingName, e.reducedMotion, e.dockTitle, e.dockRow,
                e.reorderButton, e.showStats, e.appLibrary, e.skyIntensity, e.settingsKeyKeycodes, e.settingsKeyPanel),
        )
        assertEquals(listOf("diwali", "2026-11-09", "Shubh Deepavali", 0xFFFFB23C.toInt()), listOf(e.festival, e.festivalUntil, e.greeting, e.accentColorArgb))
        // Unordered and padded input reads to the same doc.
        val shuffled = ok("{\"accent_color\": \" #ffb23c \", \"sky_intensity\": 60.7, \"festival\": \" Diwali\", \"greeting_name\": \" Mr. Trịnh \"}")
        assertEquals(LauncherConfigDoc(accentColor = "#ffb23c", skyIntensity = 60, festival = "Diwali", greetingName = "Mr. Trịnh"), shuffled.doc)
        // The built-in values are config.xml's.
        val b = LauncherConfigCodec.builtInDefaults
        assertEquals(listOf(true, true, false, "", false, true, true, false, false, false, 0, emptyList<Int>(), false, "", "", "", 0),
            listOf(b.fillDock, b.animations, b.advancedEffects, b.greetingName, b.reducedMotion, b.dockTitle, b.dockRow, b.reorderButton,
                b.showStats, b.appLibrary, b.skyIntensity, b.settingsKeyKeycodes, b.settingsKeyPanel, b.festival, b.festivalUntil, b.greeting, b.accentColorArgb))
    }

    // settings_key_keycodes is all or nothing, as Config.intArray reads it.
    @Test fun keycodesAreAllOrNothing() {
        assertEquals(listOf(83, 83, Int.MAX_VALUE), ok("{\"settings_key_keycodes\": [83.9, 4294967379, 1e400]}").doc.settingsKeyKeycodes)
        assertEquals(emptyList(), ok("{\"settings_key_keycodes\": []}").doc.settingsKeyKeycodes)
        for (bad in listOf("[83, 0]", "[83, \"84\"]", "[83, null]", "[83,,84]", "[0.5]", "83", "\"83\"")) {
            val r = ok("{\"settings_key_keycodes\": $bad}")
            assertEquals(null, r.doc.settingsKeyKeycodes, bad)
            assertEquals(1, r.warnings.size, "$bad: ${r.warnings}")
        }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(settingsKeyKeycodes = listOf(83, 0))) }
        assertEquals("{\n  \"settings_key_keycodes\": [83]\n}\n", LauncherConfigCodec.encode(LauncherConfigDoc(settingsKeyKeycodes = listOf(83))).decodeToString())
    }

    // A list that takes Back, the arrows, OK, Enter or Menu would leave Home without those buttons:
    // decode keeps it (the launcher reads it) and warns (SECURITY.md V1).
    @Test fun navigationKeycodesWarn() {
        for (k in listOf(4, 19, 20, 21, 22, 23, 66, 82)) {
            val r = ok("{\"settings_key_keycodes\": [83, $k]}")
            assertEquals(listOf(83, k), r.doc.settingsKeyKeycodes)
            assertEquals(1, r.warnings.size, r.warnings.toString())
            assertTrue("keycode $k" in r.warnings.single() && "Settings" in r.warnings.single(), r.warnings.single())
        }
        assertEquals(emptyList(), ok("{\"settings_key_keycodes\": [83, 384, 176]}").warnings)
        assertTrue("keycode 19, 66" in ok("{\"settings_key_keycodes\": [19, 66, 19]}").warnings.single())
    }

    // RFC-W4-3: the nine keys launcher v2.5.0 added, read as Config.java reads them.
    @Test fun theV25KeysAreModelled() {
        val r = ok("{\"launch_actions\": [\" com.telekom.onetv.tv.pl=com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE \"], " +
            "\"app_labels\": [\"com.telekom.onetv.tv.pl=TV\", \"com.telekom.onetv.tv.pl=Live\"], \"app_banners\": [\"com.telekom.onetv.tv.pl=banner_tv\"], " +
            "\"input_key_keycodes\": [192], \"app_keys\": [\"190:com.google.android.youtube.tv\"], \"cinematic_home\": true, " +
            "\"wallpaper_rotation_minutes\": 30, \"wallpaper_dim\": 40, \"race_calendar\": [{\"round\": 18, \"name\": \"United States Grand Prix\", " +
            "\"place\": \"Austin\", \"sessions\": [{\"name\": \"Race\", \"start\": \"2026-10-25T19:00:00Z\"}, {\"name\": \"fp1\", \"start\": \"2026-10-23T17:30:00Z\"}]}]}")
        assertEquals(emptyList(), r.warnings)
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE"), e.launchActions)
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "Live"), e.appLabels, "a later entry for a package wins")
        assertEquals(mapOf("com.telekom.onetv.tv.pl" to "banner_tv"), e.appBanners)
        assertEquals(listOf(192) to listOf("190:com.google.android.youtube.tv"), e.inputKeyKeycodes to e.appKeys)
        assertEquals(Triple(true, 30, 40), Triple(e.cinematicHome, e.wallpaperRotationMinutes, e.wallpaperDim))
        assertEquals(listOf(LauncherRace(18, "United States Grand Prix", "Austin", listOf(
            LauncherRaceSession("FP1", "2026-10-23T17:30:00Z"), LauncherRaceSession("RACE", "2026-10-25T19:00:00Z")))), e.raceCalendar)
        // Our own encoding reads back the same, and an older push (no new keys) is unchanged.
        assertEquals(r.doc, ok(LauncherConfigCodec.encode(r.doc).decodeToString()).doc)
        assertEquals("{\n  \"columns\": 6\n}\n", LauncherConfigCodec.encode(LauncherConfigDoc(columns = 6)).decodeToString())
        // What the launcher skips, it says so.
        val bad = ok("{\"launch_actions\": [\"no equals\", \"=x\", \"Not A Package=x\"], \"app_banners\": [\"a.b=banner_none\"], " +
            "\"app_keys\": [\"x:a.b\", \"190:\", \"0:a.b\"], \"wallpaper_dim\": 91, \"race_calendar\": [{\"sessions\": [{\"name\": \"FP1\", \"start\": \"tomorrow\"}]}]}")
        assertEquals(10, bad.warnings.size, bad.warnings.joinToString("\n"))
        assertEquals(emptyList(), LauncherConfigCodec.effective(bad.doc, LauncherConfigCodec.builtInDefaults).raceCalendar)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(appKeys = listOf(" 190:a.b"))) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(wallpaperDim = 91)) }
        assertFailsWith<IllegalArgumentException> {
            LauncherConfigCodec.encode(LauncherConfigDoc(raceCalendar = listOf(LauncherRace(1, "x", "y", listOf(LauncherRaceSession("fp1", "2026-10-23T17:30:00Z"))))))
        }
    }

    // SECURITY.md V1: with launcher 2.5's key services on (InputKeyService, HomeKeyService), a
    // navigation button in input_key_keycodes or app_keys is taken in every app.
    @Test fun navigationKeysInTheKeyServicesWarn() {
        val r = ok("{\"input_key_keycodes\": [192, 23], \"app_keys\": [\"KEYCODE_DPAD_CENTER:a.b\", \"4:a.b\", \"190:a.b\"]}")
        assertEquals(2, r.warnings.size, r.warnings.toString())
        assertTrue("keycode 23" in r.warnings[0] && "input switcher" in r.warnings[0], r.warnings[0])
        assertTrue("keycode 23, 4" in r.warnings[1] && "any app" in r.warnings[1], r.warnings[1])
        assertEquals(emptyList(), ok("{\"input_key_keycodes\": [192], \"app_keys\": [\"KEYCODE_BUTTON_3:a.b\"]}").warnings)
    }

    // Remote buttons: "scan:<code>:<package>" (launcher 2.6, RemoteKeys.parseAppScancodes) is kept as written
    // and named for what it is, a button launcher 2.5 skips; a malformed one keeps the old warning.
    @Test fun scancodeAppKeysAreKeptAndNamed() {
        val r = ok("{\"app_keys\": [\"scan:425:com.google.android.youtube.tv\", \"190:a.b\", \"scan:0:a.b\", \"scan:x:a.b\", \"scan:425:\"]}")
        assertEquals(listOf("scan:425:com.google.android.youtube.tv", "190:a.b", "scan:0:a.b", "scan:x:a.b", "scan:425:"), r.doc.appKeys)
        assertEquals(4, r.warnings.size, r.warnings.toString())
        assertTrue("scancode 425" in r.warnings[0] && "2.6" in r.warnings[0], r.warnings[0])
        assertTrue(r.warnings.drop(1).all { "is not keycode:package" in it }, r.warnings.toString())
        assertEquals(425 to "a.b", ConfigCodecImpl.appScanEntry(" scan : 425 : a.b "))
        assertEquals(null, ConfigCodecImpl.appScanEntry("hold:scan:425:a.b"))
        val doc = LauncherConfigDoc(appKeys = listOf("scan:425:a.b"))
        assertEquals(doc.appKeys, ok(LauncherConfigCodec.encode(doc).decodeToString()).doc.appKeys)
    }

    @Test fun festivalLikeFestivalJava() {
        fun e(json: String) = LauncherConfigCodec.effective(ok(json).doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("", e("{\"festival\": \"x\", \"festival_until\": \"2026-02-30\"}").festival)
        assertEquals("", e("{\"festival\": \"" + "x".repeat(41) + "\"}").festival)
        assertEquals("x".repeat(40), e("{\"festival\": \"" + "X".repeat(40) + "\"}").festival)
        assertEquals("", e("{\"festival\": \"" + "\u0130".repeat(21) + "\"}").festival, "lower-casing grows it past 40")
        assertEquals("" to "", e("{\"festival\": \"x\"}").let { it.festivalUntil to it.greeting })
        assertEquals(0, e("{\"festival\": \"x\", \"accent_color\": \"#FFF\"}").accentColorArgb)
        assertEquals("g".repeat(39), e("{\"festival\": \"x\", \"greeting\": \"" + "g".repeat(39) + "\uD83D\uDE00\"}").greeting, "a pair is never split")
        assertEquals("" to 0, e("{\"greeting\": \"hi\", \"accent_color\": \"#123456\"}").let { it.greeting to it.accentColorArgb })
        // Warnings name what the launcher drops.
        assertEquals(1, ok("{\"festival\": \"x\", \"festival_until\": \"soon\"}").warnings.size)
        assertEquals(1, ok("{\"festival\": \"x\", \"accent_color\": \"gold\"}").warnings.size)
        assertEquals(2, ok("{\"greeting\": \"hi\", \"accent_color\": \"#123456\"}").warnings.size)
        assertEquals(0, ok("{\"festival\": \"x\", \"accent_color\": \"\"}").warnings.size)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(festival = " x")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(skyIntensity = 101)) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(greetingName = "x ")) }
    }

    @Test fun perKeyTypeAndRangeLikeConfigJava() {
        val r = ok("{\"columns\": 9, \"rows_below_dock\": -1, \"shamsi_date\": \"true\", \"dock_apps\": [\" a.b \", \"\", 3, \"not a pkg\"], \"vpn_app\": 7, \"zzz\": 1}")
        assertEquals(null, r.doc.columns); assertEquals(null, r.doc.rowsBelowDock); assertEquals(null, r.doc.shamsiDate); assertEquals(null, r.doc.vpnApp)
        assertEquals(listOf(PackageName("a.b")), r.doc.dockApps)
        assertEquals(setOf("zzz"), r.doc.unknown.keys)
        assertEquals(8, r.warnings.size, r.warnings.toString())
        assertEquals(4, ok("{\"columns\": 4294967300}").doc.columns)            // Number.intValue() of a long
        assertEquals(7, ok("{\"columns\": 07.9}").doc.columns)                  // 07.9 is a double: 7
        assertEquals(8, ok("{\"columns\": 010}").doc.columns)                   // octal
        assertEquals(6, ok("{columns: 0x6; rows_below_dock: 1}").doc.columns)
        assertEquals(6, ok("{columns = 0x6} trailing text").doc.columns)
        assertEquals("x", ok("{'live_tv_action' => ' x ' /* c */ # hash\n}").doc.liveTvAction)
    }

    // RFC-W4-1: the two keys upstream 1bbf0c9 added are fields, decoded in Config.java's order and
    // never carried in `unknown`.
    @Test fun inputActionAndModuleCardsAreModelled() {
        val text = "{\n  \"live_tv_action\": \"x\",\n  \"input_action\": \"com.xgimi.action.hdmiPlayer\",\n  \"columns\": 6,\n" +
            "  \"module_cards\": false,\n  \"zz\": 1\n}\n"
        val r = ok("{\"zz\": 1, \"module_cards\": false, \"input_action\": \" com.xgimi.action.hdmiPlayer \", \"columns\": 6, \"live_tv_action\": \"x\"}")
        assertEquals("com.xgimi.action.hdmiPlayer", r.doc.inputAction)
        assertEquals(false, r.doc.moduleCards)
        assertEquals(setOf("zz"), r.doc.unknown.keys)
        assertEquals(text, LauncherConfigCodec.encode(r.doc).decodeToString())
        assertEquals(r.doc, assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(text.encodeToByteArray())).doc)
        val e = LauncherConfigCodec.effective(r.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("com.xgimi.action.hdmiPlayer" to false, e.inputAction to e.moduleCards)
        // Wrong types: the launcher keeps its defaults, so the doc leaves them null with a warning.
        val wrong = ok("{\"input_action\": 3, \"module_cards\": \"false\"}")
        assertEquals(null, wrong.doc.inputAction); assertEquals(null, wrong.doc.moduleCards)
        assertEquals(emptyMap(), wrong.doc.unknown)
        assertEquals(2, wrong.warnings.size, wrong.warnings.toString())
        val d = LauncherConfigCodec.effective(wrong.doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("" to true, d.inputAction to d.moduleCards)
        assertEquals("" to true, LauncherConfigCodec.builtInDefaults.let { it.inputAction to it.moduleCards })
    }

    @Test fun refusesWhatTheLauncherIgnoresOrCrashesOn() {
        assertTrue(rejected(ByteArray(64 * 1024 + 1) { ' '.code.toByte() }).reason.contains("64 KB"))
        rejected("[1]".encodeToByteArray()); rejected("".encodeToByteArray()); rejected("{\"columns\": NaN}".encodeToByteArray())
        assertTrue(rejected("{\"background_color\": \"  \"}".encodeToByteArray()).reason.contains("crash"))
        assertTrue(rejected(("{\"a\": " + "[".repeat(600) + "]".repeat(600) + "}").encodeToByteArray()).reason.contains("nested"))
        ok("{\"background_color\": \"#12\"}").let { assertEquals(null, it.doc.backgroundColor); assertEquals(1, it.warnings.size) }
        assertEquals(0xFFFFFFFF.toInt(), LauncherConfigCodec.effective(ok("{\"background_color\": \"#-0000001\"}").doc, LauncherConfigCodec.builtInDefaults).backgroundColorArgb)
    }

    @Test fun encodeIsCanonicalAndStrict() {
        val doc = LauncherConfigDoc(dockApps = listOf(PackageName("com.netflix.ninja")), columns = 5, backgroundColor = "#000000", wallpaper = false,
            unknown = mapOf("zz_custom" to JsonArray(listOf(JsonPrimitive(1)))), inputAction = "com.xgimi.action.hdmiPlayer", moduleCards = false)
        val text = LauncherConfigCodec.encode(doc).decodeToString()
        assertEquals(
            "{\n  \"dock_apps\": [\"com.netflix.ninja\"],\n  \"input_action\": \"com.xgimi.action.hdmiPlayer\",\n  \"columns\": 5,\n" +
                "  \"module_cards\": false,\n  \"background_color\": \"#000000\",\n  \"wallpaper\": false,\n  \"zz_custom\": [1]\n}\n",
            text,
        )
        val e = LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults)
        assertEquals("com.xgimi.action.hdmiPlayer" to false, e.inputAction to e.moduleCards)
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(inputAction = " padded")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("input_action" to JsonPrimitive("x")))) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("module_cards" to JsonPrimitive(false)))) }
        assertContentEquals("{}\n".encodeToByteArray(), LauncherConfigCodec.encode(LauncherConfigDoc()))
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(columns = 9)) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(backgroundColor = "")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(vpnApp = " padded")) }
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("columns" to JsonPrimitive(3)))) }
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val raw = JsonUnquotedLiteral("1}, \"columns\": 8, {")
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("x" to raw))) }
        // Over the cap.
        assertFailsWith<IllegalArgumentException> { LauncherConfigCodec.encode(LauncherConfigDoc(vpnApp = "v".repeat(70_000))) }
    }

    @Test fun bundledThemes() {
        val themes = BundledThemeSource().bundled()
        // dussehra (v2.4.0) is not bundled: its festival ends on 2026-10-21.
        assertEquals(listOf("default", "aurora", "six-columns"), themes.map { it.id })
        assertEquals(false, themes[0].doc.reducedMotion, "the bundled default is v2.4.0's")
        assertEquals(listOf("Default", "Aurora", "Six columns"), themes.map { it.name })
        val aurora = themes[1]
        val wp = aurora.wallpaper!!
        val bytes = BundledThemeSource.bytes(wp)!!
        assertContentEquals(java.io.File(Fixtures.upstreamDir, "themes/aurora/wallpaper.jpg").readBytes(), bytes)
        assertEquals(bytes.size.toLong(), wp.size)
        assertEquals(null, themes[0].wallpaper)
        for (t in themes) {
            assertTrue('\u2014' !in t.description && "-".repeat(2) !in t.description)
            // A bundled theme survives our own encoder unchanged.
            assertEquals(t.doc, assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(LauncherConfigCodec.encode(t.doc))).doc)
        }
    }
}
