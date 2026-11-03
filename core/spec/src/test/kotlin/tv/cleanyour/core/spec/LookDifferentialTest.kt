package tv.cleanyour.core.spec

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.UpstreamLauncher.Version
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// look2 acceptance: every doc LookKeys.build can make reads on launcher 3.0's own Config.java (the
// bundled launcher, the submodule's at b06ab21, vendored in com.gathin.tvhome.v30, RFC-UP300-1) and
// on launcher 2.7's (frozen, com.gathin.tvhome.v27) exactly as the codec and LauncherKeys read it,
// field by field (Agreement.agree), with no codec warning, and reads back to the doc that was built.
//
// Inputs: the full LookSpec cross product (5 styles, glow 3, App colour across the top 2, ring 2,
// band with its amounts 5, buttons 3, green Wi-Fi 2, big name 2, names 2, movement 4, Lift and zoom
// 2: 57,600 specs) times 5 pictures, through build at 5 apps in a row with a 4-app dock (288,000
// builds), kept once per distinct bytes, since the rules of spec 7.3 fold most of the cross onto the
// same keys; then each style's own spec at 4 to 8 apps in a row with docks of 0, 4, 15 and 24 apps,
// for each picture. The full cross runs well inside the 4 minute budget on forge, so it is not cut.
class LookDifferentialTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dock4 = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar")
        .map(::PackageName)
    private fun dock(n: Int) = (dock4 + List(20) { PackageName("com.example.app$it") }).take(n)

    @Test fun everyLookDocAgreesWithLauncher30() = everyLookDocAgreesWith(Version.V30)

    @Test fun everyLookDocAgreesWithLauncher27() = everyLookDocAgreesWith(Version.V27)

    private fun everyLookDocAgreesWith(version: Version) {
        val started = System.nanoTime()
        val docs = LinkedHashMap<String, Pair<String, LauncherConfigDoc>>()   // by the bytes' text
        val pictures = LookInputs.pictures
        var built = 0
        for ((pictureName, picture) in pictures) {
            for (spec in LookInputs.cross()) {
                val doc = LookKeys.build(spec, picture, columns = 5, dock = dock4, cards = false)
                built++
                val text = LauncherConfigCodec.encode(doc).decodeToString()
                docs.getOrPut(text) { "$spec with $pictureName" to doc }
            }
            for (style in StyleId.entries) for (columns in 4..8) for (n in listOf(0, 4, 15, 24)) {
                val doc = LookKeys.build(LookKeys.spec(style), picture, columns, dock(n), cards = true)
                built++
                val text = LauncherConfigCodec.encode(doc).decodeToString()
                docs.getOrPut(text) { "$style own at $columns with $n apps and $pictureName" to doc }
            }
        }
        val up = UpstreamLauncher(version, tmp.newFolder("looks"))
        val t = Agreement.Tally()
        for ((text, entry) in docs) {
            val (label, doc) = entry
            val bytes = text.encodeToByteArray()
            assertTrue(bytes.size <= ConfigCodecImpl.MAX_BYTES, "$label: ${bytes.size} bytes")
            val ok = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes), label)
            assertEquals(emptyList(), ok.warnings, label)
            assertEquals(doc, ok.doc, "$label reads back to the doc that was built")
            val read = Agreement.agree(up, bytes, wallpaper = doc.wallpaper == true, label = label, t = t)
            assertEquals(doc, read, label)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("look2 differential on launcher ${version.label}: $built builds, ${docs.size} distinct look docs, ${t.agreed} agree with ${version.label} Config.java, " +
            "${t.warnings} codec warnings (%.1f s)".format(seconds))
        assertEquals(docs.size, t.agreed)
        assertEquals(docs.size, t.applied)
        assertEquals(0, t.warnings)
        assertEquals(0, t.withWarnings)
    }

    // The vendored v30 classes are the submodule's own files with only the package line changed
    // (launcher 3.0 at b06ab21 until 0.4.8, 3.3 at d7b0d4a5 since 0.4.9-beta1), and the Cards stub's
    // cleanLabel is the submodule's method, unchanged.
    @Test fun vendoredLauncher30ClassesAreTheSubmodules() {
        val upstream = File(Fixtures.upstreamDir, "launcher/app/src/main/java/com/gathin/tvhome")
        val vendored = File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/v30")
        val copies = listOf("Config", "Festival", "WallpaperRotation", "RaceWeekend", "WallpaperMood", "Sun", "ThemeFiles", "WallpaperCaptions")
        for (name in copies) {
            val theirs = File(upstream, "$name.java").readLines()
            val ours = File(vendored, "$name.java").readLines()
            assertEquals("package com.gathin.tvhome;", theirs.first(), name)
            assertEquals("package com.gathin.tvhome.v30;", ours.first(), name)
            assertEquals(theirs.drop(1), ours.drop(1), "$name.java differs from the submodule's beyond the package line")
        }
        // Every file in the package is a copy, the R, Cards or bridge stub: nothing else slips in.
        assertEquals((copies + listOf("R", "Cards", "V30ConfigBridge")).map { "$it.java" }.toSet(), vendored.list()!!.toSet())
        fun cleanLabel(f: File): String {
            val lines = f.readLines()
            val start = lines.indexOfFirst { it.trim().startsWith("static String cleanLabel(") }
            val end = (start until lines.size).first { lines[it] == "    }" }
            return lines.subList(start, end + 1).joinToString("\n")
        }
        assertEquals(cleanLabel(File(upstream, "Cards.java")), cleanLabel(File(vendored, "Cards.java")))
        // R holds every id the copies read, and the bridge answers each one config.xml has.
        val ids = copies.flatMap { Regex("\\bR\\.(bool|integer|string|array)\\.([a-z_0-9]+)").findAll(File(vendored, "$it.java").readText()).map { m -> m.value }.toList() }.toSet()
        val r = File(vendored, "R.java").readText()
        for (id in ids) {
            val (type, name) = id.removePrefix("R.").split('.')
            assertTrue(Regex("class $type \\{[^}]*\\b$name =").containsMatchIn(r), "R.java has $id")
        }
        // Config.load reads every CARRIED_30 key from config.json.
        val reader = File(vendored, "Config.java").readText()
        for (k in ConfigCodecImpl.CARRIED_30) assertTrue("(json, \"$k\"" in reader, "Config.load reads $k from config.json")
        // And every CARRIED_33 key: clock_format through its constant, seasonal_skin in festivalName.
        for (k in ConfigCodecImpl.CARRIED_33 - "clock_format" - "seasonal_skin") assertTrue("(json, \"$k\"" in reader, "Config.load reads $k from config.json")
        assertTrue("string(json, CLOCK_FORMAT_KEY, clockFormat)" in reader && "static final String CLOCK_FORMAT_KEY = \"clock_format\";" in reader)
        assertTrue("string(o, \"seasonal_skin\", \"\")" in reader && "festivalName(json)" in reader)
    }

    // The vendored 2.7 classes are launcher 2.7's own files (v2.7.0, 3e34f40) with only the package
    // line changed, and the Cards stub's cleanLabel is that launcher's method, unchanged. Frozen by
    // digest since the submodule moved past 2.7 (the RFC-UP037-1 pattern of ConfigDifferentialTest):
    // each digest was computed from `git -C third_party/cleanyourtv show 3e34f40:<path>` with the
    // package line renamed, and matched the vendored file.
    @Test fun vendoredLauncher27ClassesAreV270() {
        val vendored = File(Fixtures.repoRoot, "core/spec/src/test/java/com/gathin/tvhome/v27")
        val v270 = mapOf(
            "Config" to "12cf6e5dd41c55728b556950e99cc100d557657e9ceefda3244853f1fc664cda",
            "Festival" to "dc6f1d314e0a334c71730b48fb7a86a7f18b840db63443dcc5de371af6f02c05",
            "WallpaperRotation" to "4d1532dcdcd18b38dd5a082994de14403b0931a781be06a5b29f8178e741683d",
            "RaceWeekend" to "08159bda6e1c4c58303a938b45950f23aa05da1e23149deb20903edfa6dd6aaa",
            "WallpaperMood" to "70e4fcbb6d97a7a7ae3c4ebd18d987d3da584d7eae43b800dab27b4efb30ebfb",
            "Sun" to "653a206f5776609a8edfeba33430faa382231b7e026db9df7ffa1c7e92ef88a1",
            "ThemeFiles" to "63f211fd95d67ed353f1a9e6dc6e8c79bd67b1aa38f527ab6fd59e004676cb44",
            "WallpaperCaptions" to "047d4375a163f8fba03f85267680b021ad06236e3a65502e0bbffee516bd9d7d",
        )
        fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val copies = v270.keys.toList()
        for ((name, sha) in v270) {
            val f = File(vendored, "$name.java")
            assertEquals("package com.gathin.tvhome.v27;", f.readLines().first(), name)
            assertEquals(sha, sha256(f.readBytes()), "$name.java is not launcher 2.7's")
        }
        // Every file in the package is a copy, the R, Cards or bridge stub: nothing else slips in.
        assertEquals((copies + listOf("R", "Cards", "V27ConfigBridge")).map { "$it.java" }.toSet(), vendored.list()!!.toSet())
        fun cleanLabel(f: File): String {
            val lines = f.readLines()
            val start = lines.indexOfFirst { it.trim().startsWith("static String cleanLabel(") }
            val end = (start until lines.size).first { lines[it] == "    }" }
            return lines.subList(start, end + 1).joinToString("\n")
        }
        assertEquals("1451afa4173b120935157aeda39c51bfbf59431255c3019e6c8bb981f3a932e7", sha256(cleanLabel(File(vendored, "Cards.java")).encodeToByteArray()))
        // R holds every id the copies read, and the bridge answers each one config.xml has.
        val ids = copies.flatMap { Regex("\\bR\\.(bool|integer|string|array)\\.([a-z_0-9]+)").findAll(File(vendored, "$it.java").readText()).map { m -> m.value }.toList() }.toSet()
        val r = File(vendored, "R.java").readText()
        for (id in ids) {
            val (type, name) = id.removePrefix("R.").split('.')
            assertTrue(Regex("class $type \\{[^}]*\\b$name =").containsMatchIn(r), "R.java has $id")
        }
    }

    // The 3.0 reader's own defaults (no config.json) are config.xml's, LauncherKeys.DEFAULTS_27 for
    // the 2.7 look keys and DEFAULTS_30 for the 25 keys 3.0 added.
    @Test fun withNoFileLauncher30ShowsTheDefaults() {
        val up = UpstreamLauncher(Version.V30, tmp.newFolder("none30"))
        val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(null, wallpaper = false)).config
        assertEquals(LauncherKeys.DEFAULTS_27, UpstreamLauncher.look(u.v27))
        assertEquals(LauncherKeys.DEFAULTS_30, UpstreamLauncher.keys30(u.v30))
        assertEquals(LauncherKeys.DEFAULTS_30, up.keys30Defaults())
        assertEquals(LauncherKeys.DEFAULTS_30, LauncherKeys.keys30(null))
    }

    // The 2.7 reader's own defaults (no config.json) are config.xml's and LauncherKeys.DEFAULTS_27.
    @Test fun withNoFileLauncher27ShowsTheDefaults() {
        val up = UpstreamLauncher(Version.V27, tmp.newFolder("none"))
        val u = assertIs<UpstreamLauncher.Outcome.Loaded>(up.load(null, wallpaper = false)).config
        assertEquals(LauncherKeys.DEFAULTS_27, UpstreamLauncher.look(u.v27))
        assertEquals(LauncherKeys.DEFAULTS_27, up.lookDefaults())
        assertEquals(LauncherKeys.DEFAULTS_27, LauncherKeys.look(null))
    }
}

// The inputs of the look2 differential and of LookKeysTest.
object LookInputs {
    // The five pictures of spec 6.2 the differential uses: none, Mist, a photo darkened 30, Mountains
    // with its sky at 55, and Diwali with its festival keys.
    val pictures: List<Pair<String, PictureKeys>> = listOf(
        "no picture" to PictureKeys(wallpaper = false, backgroundColor = "#000000", dim = 0, skyIntensity = 0),
        "Mist" to PictureKeys(wallpaper = true, backgroundColor = "#0E1022", dim = 0, skyIntensity = 0),
        "a photo at dim 30" to PictureKeys(wallpaper = true, backgroundColor = "#000000", dim = 30, skyIntensity = 0),
        "Mountains" to PictureKeys(wallpaper = true, backgroundColor = "#000000", dim = 0, skyIntensity = 55),
        "Diwali" to PictureKeys(wallpaper = true, backgroundColor = "#120809", dim = 0, skyIntensity = 0,
            festival = "diwali", festivalUntil = "2026-11-10", accentColor = "#FFB23C"),
    )

    // Band with its amounts: solid, see-through at 70, 50 and 30, glass.
    val bands: List<Pair<BandChoice, Int>> = listOf(BandChoice.SOLID to LookKeys.SEE_THROUGH_DEFAULT) +
        LookKeys.SEE_THROUGH.map { BandChoice.SEE_THROUGH to it } + listOf(BandChoice.GLASS to LookKeys.SEE_THROUGH_DEFAULT)

    fun cross(): Sequence<LookSpec> = sequence {
        val tf = listOf(false, true)
        for (style in StyleId.entries) for (glow in GlowChoice.entries) for (spot in tf) for (ring in tf)
            for ((band, see) in bands) for (buttons in ButtonsChoice.entries) for (wifi in tf) for (big in tf)
                for (names in tf) for (movement in Movement.entries) for (lift in tf)
                    yield(LookSpec(style, big, glow, spot, ring, band, see, buttons, wifi, names, movement, lift))
    }
}
