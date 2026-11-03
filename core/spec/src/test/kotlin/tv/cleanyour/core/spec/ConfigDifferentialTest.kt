package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.Agreement.Tally
import tv.cleanyour.core.spec.UpstreamLauncher.Version
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

// F8 acceptance: upstream Config.java and LauncherConfigCodec agree on every theme fixture and on
// 10,000 generated files, so the phone's preview can never disagree with the TV. Upstream runs
// for real: its Config.java (and the classes it calls) compiled unchanged on the JVM against
// AOSP's own org.json and Color.parseColor (test sources) and stubs for Context, Resources,
// SharedPreferences, Environment, Log and Cards.cleanLabel. Four launchers: the bundled one (3.3 at
// d7b0d4a5 since 0.4.9-beta1, 3.0 at b06ab21 before; vendored as v30) and the reader the codec is checked against
// (RFC-UP300-1), compared on every modelled field, every carried look key (LauncherKeys.look) and
// the 25 keys 3.0 added (LauncherKeys.keys30); 2.7 (v2.7.0, 3e34f40), frozen, a TV updated to 2.7
// and not since; 2.5 (v2.5.0), kept for the keys it reads, a TV updated once and not since; and 2.0
// (1bbf0c9), which a TV set up before the first update still runs. 2.0 ignores the keys v2.4.0 and
// v2.5.0 added, so only the keys both read are compared there. The look2 docs have their own
// differential, LookDifferentialTest.
class ConfigDifferentialTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun agree(up: UpstreamLauncher, bytes: ByteArray?, wallpaper: Boolean, label: String, t: Tally) {
        Agreement.agree(up, bytes, wallpaper, label, t)
    }

    private fun allLaunchers() = listOf(
        UpstreamLauncher(Version.V30, tmp.newFolder("bundled")),
        UpstreamLauncher(Version.V27, tmp.newFolder("v27")),
        UpstreamLauncher(Version.V25, tmp.newFolder("v25")),
        UpstreamLauncher(Version.V20, tmp.newFolder("previous")),
    )

    @Test fun builtInDefaultsEqualTheLaunchersConfigXml() {
        for (up in allLaunchers()) assertEquals(up.defaults(), LauncherConfigCodec.builtInDefaults)
        // The 25 keys of 3.0: config.xml at the pin is LauncherKeys.DEFAULTS_30, and so is the real
        // 3.0 Config with no file; the 2.7 look keys keep their 2.7 defaults on 3.0.
        val bundled = UpstreamLauncher(Version.V30, tmp.newFolder("defaults30"))
        assertEquals(LauncherKeys.DEFAULTS_30, bundled.keys30Defaults())
        val none = assertIs<UpstreamLauncher.Outcome.Loaded>(bundled.load(null, wallpaper = false)).config
        assertEquals(LauncherKeys.DEFAULTS_30, UpstreamLauncher.keys30(none.v30))
        assertEquals(LauncherKeys.DEFAULTS_30, LauncherKeys.keys30(null))
        assertEquals(LauncherKeys.DEFAULTS_27, UpstreamLauncher.look(none.v27))
        assertEquals(LauncherKeys.DEFAULTS_27, bundled.lookDefaults())
        val previous = UpstreamLauncher.parseConfigXml(java.io.File(javaClass.getResource("/upstream/config-1bbf0c9.xml")!!.toURI()))
        val b = LauncherConfigCodec.builtInDefaults
        assertEquals(b.inputAction to b.moduleCards, previous.getValue("input_action") to previous.getValue("module_cards"))
    }

    @Test fun themeFixturesAndHandWrittenCasesAgree() {
        val cases = Fixtures.upstreamThemes().map { "theme ${it.name}" to it.configJson } + listOf(
            "no file" to null,
            "empty" to ByteArray(0),
            "bom" to "﻿{\"columns\": 6}".encodeToByteArray(),
            "two boms" to "﻿﻿{\"columns\": 6}".encodeToByteArray(),
            "three boms" to "﻿﻿﻿{\"columns\": 6}".encodeToByteArray(),
            "empty colour crashes Home" to "{\"background_color\": \"\"}".encodeToByteArray(),
            "blank colour crashes Home" to "{\"background_color\": \"  \"}".encodeToByteArray(),
            "long truncates" to "{\"columns\": 4294967302}".encodeToByteArray(),
            "octal" to "{\"columns\": 07, rows_below_dock = 03}".encodeToByteArray(),
            "nan forbidden" to "{\"columns\": NaN}".encodeToByteArray(),
            "nan in a list is fine" to "{\"dock_apps\": [NaN, \"a.b\"]}".encodeToByteArray(),
            "trailing garbage" to "{\"columns\": 3} and more".encodeToByteArray(),
            "64 KB exactly" to ("{\"columns\": 2}" + " ".repeat(65536 - 14)).encodeToByteArray(),
            "64 KB plus one" to ("{\"columns\": 2}" + " ".repeat(65537 - 14)).encodeToByteArray(),
            "extras" to "{\"input_action\": \" com.xgimi.action.hdmiPlayer \", \"module_cards\": false}".encodeToByteArray(),
            "extras wrong types" to "{\"input_action\": 3, \"module_cards\": \"false\"}".encodeToByteArray(),
            "wallpaper off" to "{\"wallpaper\": false}".encodeToByteArray(),
            "wallpaper not a bool" to "{\"wallpaper\": \"no\"}".encodeToByteArray(),
            "v2.4 keys" to ("{\"fill_dock\": false, \"animations\": false, \"advanced_effects\": true, \"greeting_name\": \" Mr. Trịnh \", " +
                "\"reduced_motion\": true, \"dock_title\": false, \"dock_row\": false, \"reorder_button\": true, \"show_stats\": true, " +
                "\"app_library\": true, \"sky_intensity\": 60, \"settings_key_keycodes\": [83, 384], \"settings_key_panel\": true}").encodeToByteArray(),
            "v2.4 keys wrong types" to "{\"fill_dock\": 0, \"greeting_name\": [], \"sky_intensity\": \"60\", \"settings_key_keycodes\": 83}".encodeToByteArray(),
            "sky out of range" to "{\"sky_intensity\": 101}".encodeToByteArray(),
            "sky truncates" to "{\"sky_intensity\": 99.9}".encodeToByteArray(),
            "keycodes all or nothing" to "{\"settings_key_keycodes\": [83, 0]}".encodeToByteArray(),
            "keycodes with a string" to "{\"settings_key_keycodes\": [83, \"84\"]}".encodeToByteArray(),
            "keycodes truncate and wrap" to "{\"settings_key_keycodes\": [4.9, 4294967379, 1e400]}".encodeToByteArray(),
            "keycodes empty" to "{\"settings_key_keycodes\": []}".encodeToByteArray(),
            "keycodes with an empty slot" to "{\"settings_key_keycodes\": [83,,84]}".encodeToByteArray(),
            "keycodes take the arrows" to "{\"settings_key_keycodes\": [19, 23, 66]}".encodeToByteArray(),
            "festival" to "{\"festival\": \" Diwali \", \"festival_until\": \"2026-11-09\", \"greeting\": \"  Happy\u2028 Diwali  \", \"accent_color\": \"#ffb23c\"}".encodeToByteArray(),
            "festival no end" to "{\"festival\": \"dussehra\"}".encodeToByteArray(),
            "festival bad date" to "{\"festival\": \"dussehra\", \"festival_until\": \"2026-02-30\"}".encodeToByteArray(),
            "festival bad colour" to "{\"festival\": \"dussehra\", \"accent_color\": \"#FFF\"}".encodeToByteArray(),
            "festival too long" to ("{\"festival\": \"" + "x".repeat(41) + "\"}").encodeToByteArray(),
            "festival grows when lower-cased" to ("{\"festival\": \"" + "\u0130".repeat(21) + "\"}").encodeToByteArray(),
            "festival long greeting" to ("{\"festival\": \"x\", \"greeting\": \"" + "g".repeat(39) + "\uD83D\uDE00 tail\"}").encodeToByteArray(),
            "greeting without festival" to "{\"greeting\": \"hi\", \"accent_color\": \"#123456\"}".encodeToByteArray(),
            // Launcher 3.3: seasonal_skin names the festival when festival is empty or missing; older ones ignore it.
            "seasonal skin" to "{\"seasonal_skin\": \" Halloween \", \"festival_until\": \"2026-11-01\"}".encodeToByteArray(),
            "festival over seasonal skin" to "{\"festival\": \"diwali\", \"seasonal_skin\": \"halloween\"}".encodeToByteArray(),
            "empty festival and seasonal skin" to "{\"festival\": \"  \", \"seasonal_skin\": \"halloween\", \"greeting\": \"Boo\"}".encodeToByteArray(),
            "seasonal skin not a string" to "{\"festival\": 5, \"seasonal_skin\": [\"halloween\"]}".encodeToByteArray(),
            "v2.5 keys" to ("{\"launch_actions\": [\" com.telekom.onetv.tv.pl = com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE \", \"=x\", \"a.b=\", \"no equals\"], " +
                "\"app_labels\": [\"com.telekom.onetv.tv.pl=TV\", \"com.telekom.onetv.tv.pl=Later wins\", 5], \"app_banners\": [\"com.android.tv=banner_tv\", \"a.b=banner_none\"], " +
                "\"input_key_keycodes\": [192], \"app_keys\": [\"190:com.google.android.youtube.tv\", \"KEYCODE_BUTTON_3 : com.google.android.youtube.tv\", \"0:a.b\", \"x:a.b\", \"191:\", \"1234567:a.b\"], " +
                "\"cinematic_home\": true, \"wallpaper_rotation_minutes\": 30, \"wallpaper_dim\": 40}").encodeToByteArray(),
            "v2.5 keys wrong types" to "{\"launch_actions\": \"a=b\", \"input_key_keycodes\": [192, 0], \"cinematic_home\": 1, \"wallpaper_rotation_minutes\": 1441, \"wallpaper_dim\": 90.9}".encodeToByteArray(),
            "v2.5 keys take the arrows" to "{\"input_key_keycodes\": [23], \"app_keys\": [\"KEYCODE_DPAD_CENTER:a.b\", \"4:a.b\"]}".encodeToByteArray(),
            "race calendar" to ("{\"race_calendar\": [{\"round\": 18, \"name\": \" United States Grand Prix \", \"place\": \"Austin\", \"sessions\": [" +
                "{\"name\": \"race\", \"start\": \"2026-10-25T19:00:00Z\"}, {\"name\": \" fp1 \", \"start\": \"2026-10-23T17:30:00Z\"}, " +
                "{\"name\": \"\", \"start\": \"2026-10-23T17:30:00Z\"}, {\"name\": \"FP2\", \"start\": \"tomorrow\"}, 7]}, " +
                "{\"round\": \"19.7\", \"sessions\": [{\"name\": 5, \"start\": \"2026-11-01T18:00:00Z\"}]}, {\"round\": 20, \"sessions\": []}, " +
                "{\"name\": [1, \"a/b\", null, 2.0, -0.0], \"place\": {\"k\": \"v\"}, \"sessions\": [{\"name\": null, \"start\": \"2026-11-08T17:00:00Z\"}]}, \"not a race\"]}").encodeToByteArray(),
            "race calendar not a list" to "{\"race_calendar\": {\"round\": 1}}".encodeToByteArray(),
            "invalid utf8" to byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte()),
        )
        for (up in allLaunchers()) {
            val t = Tally()
            for ((label, bytes) in cases) for (wp in listOf(true, false)) agree(up, bytes, wp, label = label, t = t)
            assertTrue(t.crashed > 0 && t.ignored > 0 && t.applied > 0)
        }
    }

    // 0.4.0 Apps in a row: every bundled look at 4 to 8 apps in a row, with fill_dock false and a
    // 6-app dock, as Look.docOf makes the doc (the override before the dock is cut, then fill_dock),
    // agrees with Config.java on all three launchers (2.0 for the keys it reads) and decodes with no
    // warning. 55 docs; columns 8 is in Config.load's range (1 to 8), as on launcher 2.7.
    @Test fun everyLookAtEveryOfferedAppsInARowAgrees() {
        val six = List(6) { PackageName("com.example.app$it") }
        val docs = BundledThemeSource().bundled().flatMap { look ->
            (DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS).map { c ->
                "${look.id} at $c" to DockLayers.applyTo(look.doc, six, c).copy(fillDock = false, moduleCards = false)
            }
        }
        assertEquals(55, docs.size)
        for ((label, doc) in docs) {
            val bytes = LauncherConfigCodec.encode(doc)
            val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes), label)
            assertEquals(emptyList(), ok.warnings, "$label decodes with no warning")
            assertEquals(doc, ok.doc, label)
        }
        for (up in allLaunchers()) {
            val t = Tally()
            for ((label, doc) in docs) {
                val bytes = LauncherConfigCodec.encode(doc)
                agree(up, bytes, wallpaper = true, label = label, t = t)
                val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(bytes, wallpaper = true)).config
                assertEquals(DockLayers.columnsOf(doc), u.columns, "$label: the launcher's columns")
                if (up.pinned) assertEquals(false, u.v24.fillDock, "$label: fill_dock")
            }
            assertEquals(55, t.applied)
        }
    }

    // WallpaperRotation and RaceWeekend, which v2.5.0's Config.java calls, are vendored in the test
    // sources; they must be the submodule's own files, with only the package line changed.
    // RFC-UP037-1: the vendored reader is launcher 2.5's (v2.5.0, 9071ec8), byte for byte, with only the
    // package renamed; the bundled launcher is 2.7, whose reader of the codec's keys is the same.
    @Test fun vendoredLauncherClassesAreThoseOfV250() {
        val v250 = mapOf(
            "Config.java" to "e6c61c5597de84c57504a3d74b0e2ff3f836d8703db7530af24eb8cc9f872948",
            "Festival.java" to "3426587a74086fd3a9fc2f0d04358d251817675eea3569b02f05d0c1b86a49f3",
            "WallpaperRotation.java" to "cd3fe26c63b001e914e31a3d4c78370640a3b4e5d9dac73b16a0f8ef91e7691d",
            "RaceWeekend.java" to "36d1cabc92b1e8f38962ccb6bbb5fafa8814cdbfc3fef238b977c435460c5845",
        )
        for ((name, sha) in v250) {
            val bytes = java.io.File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/pinned/$name").readBytes()
            val got = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(sha, got, name)
        }
    }

    // The fuzz writes the carried 2.7 look keys and the 25 keys of 3.0 too (ConfigFuzz), so
    // LauncherKeys.look and keys30 meet their wrong types and out of range values against the real
    // 3.0 reader.
    @Test fun tenThousandGeneratedFilesAgreeWithTheBundledLauncher() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("gen"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0030)
        val t = Tally()
        repeat(10_000) { i -> agree(up, fuzz.next(), wallpaper = i % 3 != 0, label = "generated #$i", t = t) }
        println("launcher 3.0: ${t.applied} applied (${t.withWarnings} with warnings), ${t.ignored} ignored, ${t.crashed} would crash, ${t.agreed} agreed")
        assertTrue(t.applied > 4_000 && t.ignored > 1_000 && t.crashed > 10 && t.withWarnings > 1_000, "generator lost coverage: applied ${t.applied}, ignored ${t.ignored}, crashed ${t.crashed}")
        assertEquals(10_000, t.agreed)
    }

    // The frozen 2.7 reader, a TV updated to 2.7 and not since: the same fuzz, the 2.7 fields.
    @Test fun tenThousandGeneratedFilesAgreeWithLauncher27() {
        val up = UpstreamLauncher(Version.V27, tmp.newFolder("gen27"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0001)
        val t = Tally()
        repeat(10_000) { i -> agree(up, fuzz.next(), wallpaper = i % 3 != 0, label = "generated #$i", t = t) }
        println("launcher 2.7: ${t.applied} applied (${t.withWarnings} with warnings), ${t.ignored} ignored, ${t.crashed} would crash, ${t.agreed} agreed")
        assertTrue(t.applied > 4_000 && t.ignored > 1_000 && t.crashed > 10 && t.withWarnings > 1_000, "generator lost coverage: applied ${t.applied}, ignored ${t.ignored}, crashed ${t.crashed}")
        assertEquals(10_000, t.agreed)
    }

    @Test fun generatedFilesAgreeWithLauncher25() {
        val up = UpstreamLauncher(Version.V25, tmp.newFolder("gen-v25"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0006)
        val t = Tally()
        repeat(3_000) { i -> agree(up, fuzz.next(), wallpaper = i % 2 == 0, label = "launcher 2.5 #$i", t = t) }
    }

    @Test fun generatedFilesAgreeWithLauncher20() {
        val up = UpstreamLauncher(Version.V20, tmp.newFolder("gen-previous"))
        val fuzz = ConfigFuzz(seed = 0x5EED_0002)
        val t = Tally()
        repeat(3_000) { i -> agree(up, fuzz.next(), wallpaper = i % 2 == 0, label = "launcher 2.0 #$i", t = t) }
    }

    // Launcher settings on the TV (v2.4.0) can choose 4 to 7 tiles per row and the motion; the
    // choices win over config.json until a push changes the file, which every push does (a new
    // modification time). A restart without a push keeps them.
    @Test fun aChoiceMadeOnTheTvLastsUntilTheNextPush() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("choices"))
        val pushed = LauncherConfigCodec.encode(LauncherConfigDoc(columns = 4, animations = true))
        fun loaded(o: UpstreamLauncher.Outcome) = assertIs<UpstreamLauncher.Outcome.Loaded>(o).config
        assertEquals(4, loaded(up.load(pushed, wallpaper = false)).columns)
        up.choices.values["columns"] = 6
        up.choices.values["animations"] = false
        val chosen = loaded(up.restart())
        assertEquals(6, chosen.columns)
        assertEquals(false, chosen.v24.animations)
        up.choices.values["columns"] = 9   // outside 4 to 7: the launcher ignores the choice
        assertEquals(4, loaded(up.restart()).columns)
        up.choices.values["columns"] = 6
        val again = loaded(up.load(pushed, wallpaper = false))
        assertEquals(4, again.columns, "the same bytes pushed again still clear the choice")
        assertEquals(true, again.v24.animations)
        assertEquals(null, up.choices.values["columns"])
        val e = LauncherConfigCodec.effective(assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(pushed)).doc, up.defaults())
        assertEquals(e.columns to e.animations, again.columns to again.v24.animations)
    }

    // Launcher 2.5 adds two choices on the TV: rows below the dock (0 to 3) and a wallpaper pick
    // ("none" or a picture's name). Like the columns they win until a push changes config.json.
    @Test fun launcher25ChoicesOnTheTvLastUntilTheNextPush() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("choices25"))
        val pushed = LauncherConfigCodec.encode(LauncherConfigDoc(rowsBelowDock = 1, wallpaper = true))
        fun loaded(o: UpstreamLauncher.Outcome) = assertIs<UpstreamLauncher.Outcome.Loaded>(o).config
        loaded(up.load(pushed, wallpaper = true)).let { assertEquals(1 to true, it.rowsBelowDock to it.wallpaperShown) }
        up.choices.values["rows_below_dock"] = 3
        up.choices.values["wallpaper_choice"] = "none"
        loaded(up.restart()).let { assertEquals(3 to false, it.rowsBelowDock to it.wallpaperShown) }
        up.choices.values["rows_below_dock"] = 4   // outside 0 to 3: ignored
        assertEquals(1, loaded(up.restart()).rowsBelowDock)
        up.choices.values["rows_below_dock"] = 2
        loaded(up.load(pushed, wallpaper = true)).let { assertEquals(1 to true, it.rowsBelowDock to it.wallpaperShown, "a push clears both") }
        assertEquals(null, up.choices.values["rows_below_dock"])
        assertEquals(null, up.choices.values["wallpaper_choice"])
    }

    // Launcher 2.7 adds the dock band's see-through amount to the choices on the TV (Launcher
    // settings offers 70, 50 and 30); like the others it wins until a push changes config.json.
    // The same on 3.0 and on 2.7.
    @Test fun launcher27DockOpacityChoiceLastsUntilTheNextPush() = listOf(Version.V30, Version.V27).forEach { version ->
        val up = UpstreamLauncher(version, tmp.newFolder("choices27-${version.label}"))
        val pushed = LauncherConfigCodec.encode(LauncherConfigDoc(unknown = mapOf("dock_opacity" to JsonPrimitive(50))))
        fun opacity(o: UpstreamLauncher.Outcome) = assertIs<UpstreamLauncher.Outcome.Loaded>(o).config.v27.dockOpacity
        assertEquals(50, opacity(up.load(pushed, wallpaper = false)))
        up.choices.values["dock_opacity"] = 70
        assertEquals(70, opacity(up.restart()))
        up.choices.values["dock_opacity"] = 20   // outside 30 to 100: ignored
        assertEquals(50, opacity(up.restart()))
        up.choices.values["dock_opacity"] = 30
        assertEquals(50, opacity(up.load(pushed, wallpaper = false)), "a push clears it")
        assertEquals(null, up.choices.values["dock_opacity"])
    }

    private fun carried30Keys(r: Random): Map<String, kotlinx.serialization.json.JsonElement> {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        fun list(vararg xs: String) = kotlinx.serialization.json.JsonArray(xs.toList().shuffled(r).take(r.nextInt(xs.size + 1)).map { JsonPrimitive(it) })
        for (k in ConfigCodecImpl.CARRIED_30.shuffled(r).take(r.nextInt(ConfigCodecImpl.CARRIED_30.size + 1))) {
            out[k] = when (k) {
                "input_sources" -> list("HW5=23", "HW6=24", "HW8=-")
                "top_bar_apps" -> list("com.google.android.youtube.tv", "com.netflix.ninja", "a.b")
                "lunar_utc_offset" -> JsonPrimitive(listOf(-1, 7, 8, 14).random(r))
                "clock_wallpaper_dim" -> JsonPrimitive(r.nextInt(0, 91))
                "locale" -> JsonPrimitive(listOf("", "vi", "pt-BR").random(r))
                "glass_style" -> JsonPrimitive(listOf("glass", "rim", "halo").random(r))
                else -> JsonPrimitive(r.nextBoolean())
            }
        }
        return out
    }

    private fun carriedLookKeys(r: Random): Map<String, kotlinx.serialization.json.JsonElement> {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (k in ConfigCodecImpl.CARRIED_27.shuffled(r).take(r.nextInt(ConfigCodecImpl.CARRIED_27.size + 1))) {
            out[k] = when (k) {
                "glow_style" -> JsonPrimitive(listOf("radial", "horizon").random(r))
                "dock_opacity" -> JsonPrimitive(listOf(30, 50, 70, 100).random(r))
                "cinematic_scrim", "cinematic_art" -> JsonPrimitive(r.nextInt(0, 101))
                else -> JsonPrimitive(r.nextBoolean())
            }
        }
        return out
    }

    // Our own encoder's output reads back to the same doc and the TV sees what effective() says.
    @Test fun encodedDocsReadBackIdenticallyOnBothSides() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("enc"))
        val up27 = UpstreamLauncher(Version.V27, tmp.newFolder("enc27"))
        val t27 = Tally()
        val r = Random(0x5EED_0003)
        val pkgs = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "in.startv.hotstar", "com.android.tv", "a.b")
        val strings = listOf("", "com.android.tv", "with \"quotes\" and \\ slash", "tab\tinside", "ünï", "\u0001", "emoji 😀", "lone \uD800 surrogate")
        val t = Tally()
        repeat(2_000) { i ->
            fun <T> maybe(v: () -> T): T? = if (r.nextBoolean()) v() else null
            var doc = LauncherConfigDoc(
                dockApps = maybe { List(r.nextInt(6)) { PackageName(pkgs.random(r)) } },
                hiddenApps = maybe { List(r.nextInt(3)) { PackageName(pkgs.random(r)) } },
                liveTvPackage = maybe { strings.random(r) }, liveTvAction = maybe { strings.random(r) },
                sourcePanelService = maybe { strings.random(r) }, sourcePanelAction = maybe { strings.random(r) },
                columns = maybe { r.nextInt(1, 9) }, rowsBelowDock = maybe { r.nextInt(0, 4) }, shamsiDate = maybe { r.nextBoolean() },
                vpnApp = maybe { strings.random(r) }, showHomeSettingsShortcut = maybe { r.nextBoolean() },
                backgroundColor = maybe { listOf("#070A14", "#80FF0000", "navy", "Teal", "#000000").random(r) }, wallpaper = maybe { r.nextBoolean() },
                unknown = if (r.nextInt(4) == 0) mapOf("x_carried" to JsonPrimitive(r.nextInt()), "a_list" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("v")))) else emptyMap(),
            ).let { d -> d.copy(liveTvPackage = d.liveTvPackage?.javaTrim()) }
            if (r.nextBoolean()) doc = doc.copy(inputAction = strings.random(r).javaTrim())
            if (r.nextBoolean()) doc = doc.copy(moduleCards = r.nextBoolean())
            // RFC-W4-2: the keys v2.4.0 added.
            doc = doc.copy(
                fillDock = maybe { r.nextBoolean() }, animations = maybe { r.nextBoolean() }, advancedEffects = maybe { r.nextBoolean() },
                greetingName = maybe { strings.random(r).javaTrim() }, reducedMotion = maybe { r.nextBoolean() },
                dockTitle = maybe { r.nextBoolean() }, dockRow = maybe { r.nextBoolean() }, reorderButton = maybe { r.nextBoolean() },
                showStats = maybe { r.nextBoolean() }, appLibrary = maybe { r.nextBoolean() }, skyIntensity = maybe { r.nextInt(0, 101) },
                settingsKeyKeycodes = maybe { List(r.nextInt(4)) { listOf(83, 384, 176, 1, Int.MAX_VALUE).random(r) } },
                settingsKeyPanel = maybe { r.nextBoolean() },
            )
            // RFC-W4-3: the keys v2.5.0 added.
            val entries = listOf("com.android.tv=banner_tv", "a.b=Name with spaces", "a.b=x=y", "no equals", "=x", "😀=x", "with \"quotes\"=\\")
            doc = doc.copy(
                launchActions = maybe { List(r.nextInt(3)) { entries.random(r) } }, appLabels = maybe { List(r.nextInt(3)) { entries.random(r) } },
                appBanners = maybe { List(r.nextInt(3)) { entries.random(r) } },
                inputKeyKeycodes = maybe { List(r.nextInt(3)) { listOf(192, 170, 1, Int.MAX_VALUE).random(r) } },
                appKeys = maybe { List(r.nextInt(3)) { listOf("190:com.google.android.youtube.tv", "KEYCODE_BUTTON_3:a.b", "x", "0:a.b").random(r) } },
                cinematicHome = maybe { r.nextBoolean() }, wallpaperRotationMinutes = maybe { r.nextInt(0, 1441) }, wallpaperDim = maybe { r.nextInt(0, 91) },
                raceCalendar = maybe { List(r.nextInt(3)) { k ->
                    LauncherRace(r.nextInt(-1, 25), strings.random(r).javaTrim(), strings.random(r).javaTrim(),
                        List(r.nextInt(1, 4)) { j -> LauncherRaceSession(listOf("FP1", "SPRINT QUALI", "RACE", "ÖSTERREICH").random(r), "2026-10-2${j}T1${k}:30:00Z") })
                } },
            )
            // The carried 2.7 look keys, with the values LookKeys writes.
            if (r.nextBoolean()) doc = doc.copy(unknown = doc.unknown + carriedLookKeys(r))
            // The 25 keys of 3.0, with valid values.
            if (r.nextBoolean()) doc = doc.copy(unknown = doc.unknown + carried30Keys(r))
            if (r.nextInt(3) == 0) doc = doc.copy(
                festival = listOf("dussehra", "Diwali", "", "x".repeat(41)).random(r),
                festivalUntil = maybe { listOf("2026-10-21", "2026-02-30", "").random(r) },
                greeting = maybe { strings.random(r).javaTrim() },
                accentColor = maybe { listOf("#FFB23C", "#fff", "").random(r) },
            )
            fun trimmed(s: String?) = s?.javaTrim()
            doc = doc.copy(liveTvAction = trimmed(doc.liveTvAction), sourcePanelService = trimmed(doc.sourcePanelService),
                sourcePanelAction = trimmed(doc.sourcePanelAction), vpnApp = trimmed(doc.vpnApp))
            val bytes = LauncherConfigCodec.encode(doc)
            val back = LauncherConfigCodec.decode(bytes)
            assertTrue(back is DecodeResult.Ok && back.doc == doc, "round trip changed the doc #$i\n$doc\n$back\n${bytes.decodeToString()}")
            agree(up, bytes, wallpaper = true, label = "encoded #$i", t = t)
            agree(up27, bytes, wallpaper = true, label = "encoded #$i", t = t27)
            // And the AOSP parser reads our output as strict JSON with the same keys.
            assertEquals(JSONObject(bytes.decodeToString()).length(), (back as DecodeResult.Ok).doc.let { countKeys(it) })
        }
        assertEquals(2_000, t.applied)
        assertEquals(2_000, t27.applied)
    }

    // The dock sheet's rows reach the TV as the sheet shows them. Our doc goes through the real
    // Config.java; the grid is then built the way AppAdapter.reload (v2.4.0) builds and sorts it
    // and GridLayoutManager cuts it (`columns` per row, the first row the dock, rows_below_dock rows
    // in view under it). reload skips a non-exported activity, a second activity of one package
    // with the same label, and an app taken off Home on the TV, then sorts by the owner's TV-side
    // order (empty until the owner reorders on the TV), the place in dock_apps, cards (none here)
    // and the label. The phone's `shown` is the same notion: an app the TV shows as a tile. The
    // rows in view must start with exactly the sheet's rows, also when some dock apps are not shown
    // (they are skipped, the later ones move up). Not modelled: one package with two activities
    // under two labels, which shows two tiles on the TV and one on the phone.
    @Test fun dockRowsShowOnTheTvAsTheSheetShowsThem() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("dock"))
        val r = Random(0x5EED_0005)
        val pool = List(20) { PackageName("com.example.app$it") }
        data class Activity(val pkg: String, val name: String, val label: String, val exported: Boolean)
        val skips = mutableMapOf("missing" to 0, "not exported" to 0, "same label twice" to 0, "taken off Home" to 0)
        repeat(500) { i ->
            val columns = listOf(4, 4, 6, 3).random(r)
            val theme = LauncherConfigDoc(columns = columns.takeIf { it != 4 }, rowsBelowDock = listOf(null, 0, 1).random(r))
            val chosen = pool.shuffled(r).take(r.nextInt(1, 16))
            // What each app looks like on this TV.
            val activities = ArrayList<Activity>()
            val removedFromHome = HashSet<String>()
            for (p in pool.map { it.value } + "com.example.other") {
                when (r.nextInt(10)) {
                    0 -> skips.merge("missing", 1, Int::plus)
                    1 -> { activities += Activity(p, ".Main", p, exported = false); skips.merge("not exported", 1, Int::plus) }
                    2 -> { activities += Activity(p, ".Main", p, true); activities += Activity(p, ".Other", p, true); skips.merge("same label twice", 1, Int::plus) }
                    3 -> { activities += Activity(p, ".Main", p, true); removedFromHome += p; skips.merge("taken off Home", 1, Int::plus) }
                    else -> activities += Activity(p, ".Main", p, true)
                }
            }
            val shownOnTv = activities.filter { it.exported && it.pkg !in removedFromHome }.map { PackageName(it.pkg) }.toSet()
            val doc = DockLayers.applyTo(theme, chosen)
            val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(LauncherConfigCodec.encode(doc), wallpaper = false)).config
            val kept = DockLayers.trim(chosen, columns)
            assertEquals(kept.map { it.value }, u.dockApps, "#$i dock_apps")
            assertEquals(DockLayers.rowsBelowDock(kept.size, columns), u.rowsBelowDock, "#$i rows_below_dock")
            assertEquals(columns, u.columns)
            assertEquals(kept.size <= 2 * columns, u.v24.dockTitle, "#$i dock_title: off only for three rows (D1)")
            // AppAdapter.reload: the skips, then the comparator (labels: the package names).
            val seen = HashSet<String>()
            val tiles = activities.filter { a ->
                a.exported && LauncherVisible.visible(tvApp = true, hidden = a.pkg in u.hiddenApps, removed = a.pkg in removedFromHome) &&
                    seen.add(a.pkg + "\n" + a.label)
            }
            val grid = tiles.map { it.pkg }.sortedWith { a, b ->
                val fa = u.dockApps.indexOf(a)
                val fb = u.dockApps.indexOf(b)
                if (fa >= 0 || fb >= 0) { if (fa < 0) 1 else if (fb < 0) -1 else fa.compareTo(fb) } else a.compareTo(b, ignoreCase = true)
            }
            val inView = grid.chunked(u.columns).take(1 + u.rowsBelowDock)
            val sheet = DockLayers.rows(DockLayers.decode(kept, { it in shownOnTv }, columns), columns).map { row -> row.map { it.value } }
            assertTrue(sheet.size <= inView.size, "#$i the TV keeps every sheet row in view")
            // With every app shown the rows match exactly; with some not shown, the TV fills the
            // freed slots after the sheet's apps with the next apps by name.
            sheet.forEachIndexed { k, row -> assertEquals(row, inView[k].take(row.size), "#$i row ${k + 1}") }
            if (kept.all { it in shownOnTv }) assertEquals(sheet.dropLast(1), inView.take(sheet.size - 1), "#$i full rows")
        }
        assertTrue(skips.values.all { it > 100 }, "every skip is exercised: $skips")
    }

    // LibraryApps.visible (v2.4.0) for a tile with no library choice of its own: a TV app shows
    // unless the theme hides it or it was taken off Home on the TV.
    private object LauncherVisible {
        fun visible(tvApp: Boolean, hidden: Boolean, removed: Boolean) = !removed && (tvApp && !hidden)
    }

    private fun countKeys(d: LauncherConfigDoc) = listOfNotNull(d.dockApps, d.hiddenApps, d.liveTvPackage, d.liveTvAction, d.sourcePanelService,
        d.sourcePanelAction, d.columns, d.rowsBelowDock, d.shamsiDate, d.vpnApp, d.showHomeSettingsShortcut, d.backgroundColor, d.wallpaper,
        d.inputAction, d.moduleCards, d.fillDock, d.animations, d.advancedEffects, d.greetingName, d.reducedMotion, d.dockTitle,
        d.dockRow, d.reorderButton, d.showStats, d.appLibrary, d.skyIntensity, d.settingsKeyKeycodes, d.settingsKeyPanel,
        d.festival, d.festivalUntil, d.greeting, d.accentColor, d.launchActions, d.appLabels, d.appBanners, d.inputKeyKeycodes,
        d.appKeys, d.cinematicHome, d.wallpaperRotationMinutes, d.wallpaperDim, d.raceCalendar).size + d.unknown.size

    // The lenient reader is a port of JSONTokener; check it value for value, including the
    // Integer, Long and Double split, null slots, duplicate keys and what is refused.
    @Test fun androidJsonPortMatchesAospOrgJson() {
        val fuzz = ConfigFuzz(seed = 0x5EED_0004)
        var accepted = 0
        repeat(10_000) { i ->
            val text = String(fuzz.next(), Charsets.UTF_8)
            val theirs = runCatching { toAValue(JSONObject(text)) }
            val ours = runCatching { AndroidJsonReader(text).readObjectDocument() }
            assertEquals(theirs.isSuccess, ours.isSuccess, "accept differs for #$i ${text.take(300)}: ${theirs.exceptionOrNull()} vs ${ours.exceptionOrNull()}")
            if (ours.isSuccess) {
                accepted++
                assertEquals(theirs.getOrThrow().dump(), ours.getOrThrow().dump(), "value differs for #$i ${text.take(300)}")
            }
        }
        assertTrue(accepted > 5_000)
    }

    private fun toAValue(v: Any?): AValue = when (v) {
        null -> AValue.Absent
        JSONObject.NULL -> AValue.Null
        is String -> AValue.Str(v)
        is Int -> AValue.IntV(v)
        is Long -> AValue.LongV(v)
        is Double -> AValue.Dbl(v)
        is Boolean -> AValue.Bool(v)
        is JSONObject -> AValue.Obj(LinkedHashMap<String, AValue>().also { m -> v.keys().forEach { k -> m[k] = toAValue(v.opt(k)) } })
        is JSONArray -> AValue.Arr((0 until v.length()).map { toAValue(v.opt(it)) })
        else -> error("unexpected ${v::class}")
    }

    private fun AValue.dump(): String = when (this) {
        is AValue.Obj -> map.entries.joinToString(",", "{", "}") { "${it.key}=${it.value.dump()}" }
        is AValue.Arr -> items.joinToString(",", "[", "]") { it.dump() }
        is AValue.Dbl -> "D(${java.lang.Double.doubleToRawLongBits(v)})"
        else -> toString()
    }
}
