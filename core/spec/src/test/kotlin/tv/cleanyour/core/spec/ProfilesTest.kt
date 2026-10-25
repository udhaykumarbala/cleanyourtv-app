package tv.cleanyour.core.spec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilesTest {
    // D2: the upstream folders without a curated entry are skipped on purpose; the rest ship.
    private val unconvertedFile = File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt")
    private val unconverted = ProfileConverter.readUnconverted(unconvertedFile)
    private val upstream = Fixtures.upstreamExamples()
    private val examples = upstream.filter { it.folder !in unconverted }
    private fun p(s: String) = PackageName(s)
    private fun profile(folder: String) = DebloatTxtParser.parse(examples.first { it.folder == folder }.debloatTxt, folder)

    @Test fun everyConvertedUpstreamExampleParsesWithCuratedMetadata() {
        assertEquals(248, upstream.size)
        assertEquals(148, examples.size)
        for (e in examples) {
            val profile = DebloatTxtParser.parse(e.debloatTxt, e.folder)
            assertEquals(e.folder, profile.id)
            assertTrue(profile.groups.isNotEmpty(), e.folder)
            assertTrue(profile.groups.all { g -> g.packages.isNotEmpty() }, e.folder)
            assertNotNull(profile.match.brand, e.folder)
            assertNotNull(profile.tested, e.folder)
            assertTrue(profile.match.models.isNotEmpty() || profile.match.devices.isNotEmpty(), "${e.folder} has nothing to match on")
            // Every package line of the file is in exactly one place in the profile.
            val scanned = DebloatTxt.scan(e.debloatTxt, e.folder).groups.flatMap { g -> g.entries.map { it.pkg } }.toSet()
            val inGroups = profile.groups.flatMap { g -> g.packages.map { it.name } }
            assertEquals(inGroups.size, inGroups.toSet().size, "${e.folder}: a package is in two groups")
            // A wizard the curated takeover keeps on (setupWraithStaysOn) is never-disable instead.
            val placed = inGroups.toSet() + profile.stockLaunchers.map { it.pkg } + listOfNotNull(profile.setupWraith) +
                profile.neverDisable.filter { it == KnownLaunchers.SETUPWRAITH }
            assertEquals(emptySet(), scanned - placed, "${e.folder}: packages lost")
            assertTrue(inGroups.all { it in scanned }, e.folder)
        }
    }

    @Test fun converterSeesEveryGroupTitleInTheMetadata() {
        val (bundle, problems) = ProfileConverter.convert(
            File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"),
            "3e34f40",
            unconverted,
        )
        assertEquals(emptyList(), problems)
        assertEquals(examples.map { it.folder }, bundle.profiles.map { it.id })
    }

    // D2: unconverted.txt is exactly the upstream folders without a profile-meta.json entry, so a
    // new upstream folder fails here (and in the build) until it gets an entry or a line there.
    @Test fun theUnconvertedListIsPinnedToTheSubmodule() {
        val meta = ProfileMeta.decode(File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json").readText())
        assertEquals(upstream.map { it.folder }.filter { it !in meta.profiles }.toSet(), unconverted)
        assertEquals(100, unconverted.size)
        val listed = unconvertedFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listed.sorted(), listed, "one folder per line, sorted")
        assertTrue('\u2014' !in unconvertedFile.readText() && "-".repeat(2) !in unconvertedFile.readText())
        // A folder nobody lists or curates is a problem, and so is a stale or doubled line.
        val (_, missing) = ProfileConverter.convert(File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"), "x", unconverted - unconverted.first())
        assertEquals(listOf("${unconverted.first()}: no entry in profile-meta.json"), missing)
        val (_, stale) = ProfileConverter.convert(File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"), "x", unconverted + "gone-tv" + "xiaomi-mitv-axso0")
        assertEquals(setOf("gone-tv: in unconverted.txt but not upstream", "xiaomi-mitv-axso0: in both unconverted.txt and profile-meta.json"), stale.toSet())
    }

    // The unconverted folders still parse by header keywords alone, so curating one is a
    // metadata change only.
    @Test fun unconvertedUpstreamExamplesParseWithoutMetadata() {
        for (e in upstream.filter { it.folder in unconverted }) {
            val profile = DebloatTxt.parse(e.debloatTxt, e.folder, null)
            assertTrue(profile.groups.sumOf { it.packages.size } > 0, e.folder)
        }
    }

    // v2.4.0 changed two of the converted folders (tcl-g10-google-tv, xiaomi-mitv-mooq0) in their
    // header comments only; their package lines are those of 1bbf0c9. tcl-g10-google-tv's new note
    // says the setup wizard claims Home there too once launcherx is off, so its entry names the
    // wizard: the takeover switches it off, with the Wi-Fi guard (D3).
    @Test fun theG10TakeoverIncludesTheWizard() {
        val g10 = profile("tcl-g10-google-tv")
        assertEquals(KnownLaunchers.SETUPWRAITH, g10.setupWraith)
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in g10.quirks)
        assertTrue(g10.groups.none { g -> g.packages.any { it.name == KnownLaunchers.SETUPWRAITH } })
    }

    // v2.7.0 adds a second Xiaomi MiTV-AESP0 set that switches the setup wizard off to get Home back,
    // which costs casting there. The first set, which the home screen switch follows since 0.3.0, kept
    // it on, so the curated takeover still does (setupWraithStaysOn): the wizard is never-disable on
    // this stick and the second set's extra rows are ask rows.
    @Test fun theAesp0TakeoverKeepsTheWizard() {
        val aesp0 = profile("xiaomi-mitv-aesp0")
        assertEquals(null, aesp0.setupWraith)
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH !in aesp0.quirks)
        assertTrue(KnownLaunchers.SETUPWRAITH in aesp0.neverDisable)
        assertTrue(aesp0.groups.none { g -> g.packages.any { it.name == KnownLaunchers.SETUPWRAITH } })
        val secondSet = listOf("mitv.service", "com.google.android.tv", "com.android.providers.contacts", "com.android.providers.calendar")
        for (pkg in secondSet) assertEquals(GroupDefault.ASK, aesp0.groups.single { g -> g.packages.any { it.name == p(pkg) } }.default, pkg)
    }

    @Test fun theReferenceTvProfile() {
        val mi = profile("xiaomi-mitv-axso0")
        assertEquals(listOf("MiTV-AXSO0"), mi.match.models)
        assertEquals(listOf("dangal"), mi.match.devices)
        assertEquals(Platform.ANDROID_TV, mi.platform)
        assertEquals(listOf(Component(p("com.google.android.tvlauncher"), ".MainActivity")), mi.stockLaunchers)
        assertEquals(p("com.android.tv"), mi.liveTv)
        assertEquals(null, mi.setupWraith)
        assertTrue(Quirk.REBOOT_COMMAND in mi.quirks)
        val byId = mi.groups.associateBy { it.id }
        assertEquals(GroupDefault.DISABLE, byId.getValue("ads").default)
        assertTrue(p("com.mitv.tvhome.atv") in byId.getValue("ads").packages.map { it.name })
        assertEquals(p("com.glance.tv"), byId.getValue("ads").packages.first { it.name.value == "com.glance.tv" }.name)
        assertEquals("Glance lock-screen news and ads", byId.getValue("ads").packages.first { it.name.value == "com.glance.tv" }.note)
        assertEquals(listOf("com.miui.tv.analytics", "com.xiaomi.statistic", "com.google.android.feedback", "com.google.android.tv.bugreportsender"),
            byId.getValue("tracking").packages.map { it.name.value })
        // D22: the updater is a Software updates row, kept in Simple.
        assertEquals(GroupDefault.ASK, byId.getValue("updates").default)
        assertEquals(listOf("com.xiaomi.mitv.updateservice"), byId.getValue("updates").packages.map { it.name.value })
        assertEquals("Software updates", byId.getValue("updates").title)
        // Personal choices are ask groups: voice, casting, unused streaming apps.
        for (id in listOf("voice", "casting", "streaming")) assertEquals(GroupDefault.ASK, byId.getValue(id).default, id)
        assertEquals(listOf("com.google.android.katniss", "com.google.android.speech.pumpkin"), byId.getValue("voice").packages.map { it.name.value })
        // Simple switches off the disable groups only; 20 packages on this TV.
        val simple = mi.groups.filter { it.default == GroupDefault.DISABLE }.flatMap { it.packages }
        assertEquals(20, simple.size)
        // Disable groups come first, in the fixed category order.
        assertEquals(listOf("ads", "tracking", "preloads", "brand", "other", "streaming", "voice", "casting", "updates"), mi.groups.map { it.id })
        assertEquals("Adverts and sponsored rows", byId.getValue("ads").title)
        assertEquals("Xiaomi extras", byId.getValue("brand").title)
        assertEquals(DebloatTxt.sha256(examples.first { it.folder == "xiaomi-mitv-axso0" }.debloatTxt), mi.sourceSha256)
    }

    @Test fun setupwraithAndStockLaunchersLeaveTheGroups() {
        val g05 = profile("tcl-g05-google-tv")
        assertEquals(Platform.GOOGLE_TV, g05.platform)
        val gtv = profile("google-chromecast-gtv-4k")
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in gtv.quirks)
        assertEquals(KnownLaunchers.SETUPWRAITH, gtv.setupWraith)
        assertTrue(KnownLaunchers.LAUNCHERX in gtv.stockLaunchers)
        val all = examples.map { DebloatTxtParser.parse(it.debloatTxt, it.folder) }
        for (pr in all) {
            val inGroups = pr.groups.flatMap { g -> g.packages.map { it.name } }.toSet()
            assertTrue(KnownLaunchers.SETUPWRAITH !in inGroups, pr.id)
            assertTrue(KnownLaunchers.byPackage.keys.none { it in inGroups }, pr.id)
            // Every updater row is an ask row (D22).
            for (g in pr.groups) for (e in g.packages) if (ProfileCategory.isUpdater(e.name)) assertEquals(GroupDefault.ASK, g.default, "${pr.id} ${e.name}")
        }
        assertEquals(Platform.FIRE_OS, profile("insignia-ns55f301na25").platform)
        assertEquals(Platform.TCL_CN, profile("tcl-ak30a5").platform)
    }

    // D22, widened in 0.3.7: the updaters upstream v2.7.0 lists name are Software updates rows by name.
    @Test fun theUpdaterRuleKnowsTheV270Updaters() {
        for (name in listOf("com.cvte.tv.systemupgrade", "com.tcl.versionUpdateApp", "ru.sberdevices.rcfupdater", "com.amazon.tv.easyupgrade",
            "com.tcl.UpdatePeripheral", "com.sony.dtv.sensingcamera.fwupdate", "com.xiaomi.mitv.updateservice")) {
            assertTrue(ProfileCategory.isUpdater(p(name)), name)
        }
        for (name in listOf("com.tcl.versionapp", "com.sony.dtv.fwupdatehelper", "com.cvte.upgradeui", "com.amazon.tv.upgradeable")) assertTrue(!ProfileCategory.isUpdater(p(name)), name)
    }

    @Test fun headerKeepListsBecomeNeverDisable() {
        val g05 = profile("tcl-g05-google-tv")
        assertTrue(p("com.tcl.tvinput") in g05.neverDisable, g05.neverDisable.toString())
        val hisense = profile("hisense-smarttv-4k-ffm")
        assertTrue(p("com.hisense.tv.smartsense") in hisense.neverDisable, hisense.neverDisable.toString())
        assertTrue(hisense.neverDisable.none { it.value == "com.mediatek" })
        assertEquals(setOf(p("com.a.b"), p("com.c.d")), DebloatTxt.keepListIn(listOf("# Keep: com.a.b and com.c.d, plus com.mediatek.* and tools/restore.sh", "#", "# Stock launcher: com.e.f")))
        // A paragraph on what was already off ends the keep list: sony-xbr-65x900f names Samba TV
        // and Sony's promos there, and its list disables both.
        assertEquals(setOf(p("com.a.b")), DebloatTxt.keepListIn(listOf("# Keep: com.a.b,", "# Already off before the list (owner or factory): com.e.f, listed below anyway")))
        // A numbered note ends a keep paragraph (philips-55oled707 names its stock launcher in note 3).
        assertEquals(setOf(p("com.a.b")), DebloatTxt.keepListIn(listOf("# The DO NOT DISABLE block applies: com.a.b", "# 3. com.e.f wins Home while it is on")))
        // A wrapped line that happens to start with "keeps" is not a keep list (nvidia-shield-tv-2019).
        assertEquals(emptySet(), DebloatTxt.keepListIn(listOf("# - Updates are off. The Pro example", "#   keeps KeyComboOta; Play Games and com.a.b are not present here.")))
    }

    // RF-PROFILES-63 P3: a profile never protects its own stock launcher, or the home screen switch
    // could never hand Home over (keepStockEnabled), unless it says so with the quirk. Fire OS and
    // TCL China keep their own home screen anyway.
    @Test fun noProfileKeepsItsOwnStockLauncherByAccident() {
        for (pr in BundledProfileSource.bundled.profiles) {
            if (pr.platform == Platform.FIRE_OS || pr.platform == Platform.TCL_CN || Quirk.STOCK_LAUNCHER_KEEP_ENABLED in pr.quirks) continue
            assertEquals(emptySet(), pr.stockLaunchers.map { it.pkg }.toSet() intersect pr.neverDisable, pr.id)
        }
        assertTrue(p("com.google.android.tvlauncher") !in profile("philips-55oled707").neverDisable)
    }

    // RF-PROFILES-63 P2: the exact profiles that know no stock launcher get the clean-up only (Setup's
    // homePossible); pinned, so a new one is a reviewed decision.
    @Test fun theProfilesWithNoKnownStockLauncher() {
        // The candidate lists of a test build are pinned in CandidatesTest (cleanUpOnly).
        val none = BundledProfileSource.bundled.profiles.filter { it.stockLaunchers.isEmpty() && it.platform != Platform.FIRE_OS && it.platform != Platform.TCL_CN && !it.isCandidate }
        // xiaomi-mitv-mftr0 (0.3.7): China firmware whose home is com.mitv.tvhome, not a known launcher.
        assertEquals(setOf("challenger-32t22bt", "freebox-player-pop", "google-tv-streamer-4k-kirkwood", "hisense-e5q-pro", "kalley-43g200",
            "stellamore-t950s", "verizon-stream-tv", "vidda-mt9653", "xiaomi-mitv-ansp0", "xiaomi-mitv-astp1", "xiaomi-mitv-mffu0", "xiaomi-mitv-mftr0"), none.map { it.id }.toSet())
    }

    // RF-PROFILES-63 P8: where two profiles score the same for a TV, the longer fingerprint prefix wins
    // (ruling 1), and a pair still level goes to the first id. Every such TV is pinned here with the
    // winner its curation note intends, and any new level pair fails, so a renamed or new upstream
    // folder can never flip a reviewed resolution silently.
    @Test fun everyTieTheResolverSettlesIsPinned() {
        val source = BundledProfileSource()
        fun facts(model: String, device: String, brand: String, sdk: Int, fingerprint: String = "", manufacturer: String = brand) =
            Fixtures.miTv().facts().copy(model = model, device = device, brand = brand, manufacturer = manufacturer, sdk = sdk, fingerprint = fingerprint)
        fun winner(f: tv.cleanyour.core.model.DeviceFacts) = (source.resolveNow(f) as? ProfileResolution.Exact)?.profile?.id
        val pinned = listOf(
            facts("unknown", "G10", "TCL", 31, "TCL/G10_4K_US_NF/G10:12/STT2.230203.001/AS50") to "tcl-55s470g",   // supersedes tcl-g10-google-tv (ruling 1)
            facts("BRAVIA 4K GB", "BRAVIA_ATV2", "Sony", 28, "Sony/BRAVIA_ATV2_EU/BRAVIA_ATV2:9/x") to "sony-bravia-4k-gb",
            facts("BRAVIA 4K GB", "BRAVIA_ATV2", "Sony", 28, "Sony/BRAVIA_ATV2_UC/BRAVIA_ATV2:9/x") to "sony-bravia-4k-gb-us",
            facts("BRAVIA 4K VH2", "BRAVIA_VH2", "Sony", 31, "Sony/BRAVIA_VH2_EU/BRAVIA_VH2:12/x") to "sony-bravia-4k-vh2",
            facts("BRAVIA 4K VH2", "BRAVIA_VH2", "Sony", 31, "Sony/BRAVIA_VH2_SA/BRAVIA_VH2:12/x") to "sony-kd-55x85j",
            facts("BRAVIA 4K VH2", "BRAVIA_VH2", "Sony", 29) to "sony-kd-65x80j",
            facts("Smart TV Pro", "G05", "TCL", 30, "TCL/G05_4K_GB/G05:11/x") to "tcl-65c735",
            facts("Smart TV Pro", "G05", "TCL", 30, "TCL/G05_4K_US/G05:11/x") to "tcl-g05-google-tv",
            facts("Smart TV Pro", "G08", "TCL", 34, "TCL/G08_4K_GB/G08:14/x") to "tcl-c755-google-tv",
            facts("Smart TV Pro", "G08", "TCL", 34, "TCL/G08_4K_US/G08:14/x") to "tcl-qm6k-google-tv",
            facts("Smart TV Pro", "BeyondTV4", "TCL", 30) to "tcl-65c725",
            facts("UnionTV", "UnionTV", "TCL", 28) to "tcl-uniontv-latin-america",
            facts("UnionTV", "UnionTV", "Thomson", 28) to "thomson-32he5800",
            facts("UnionTV", "UnionTV", "Thomson", 28, manufacturer = "TCL") to "tcl-uniontv-latin-america",
            facts("unknown", "R10G", "Kalley", 34, manufacturer = "MOKA") to "kalley-r10g",
            facts("unknown", "R10G", "PRISMPLUS", 34, manufacturer = "MOKA") to "prismplus-q65-ultra-r10g",
            facts("2021_22_Philips_UHD_Android_TV", "PH1M_EA_9970A", "Philips", 30) to "philips-50pus8517",
            facts("2021/22 Philips UHD Android TV", "PH1M_EA_9970A", "Philips", 30) to "philips-55oled707",
            facts("unknown", "PH1M_EA_9970A", "Philips", 30) to "philips-50pus8517",
            facts("Philips Smart 4K TV", "eleonas", "Philips", 34) to "philips-smart-4k-google-tv",
            facts("SWTV-24NA-4K NF", "eleonas", "Skyworth", 34) to "skyworth-swtv-24na-4k-nf",
            facts("BeyondTV", "BeyondTV", "TCL", 28, "TCL/BeyondTV/BeyondTV:9/PPR1.180610.011/AP04:user/release-keys") to "tcl-43ep660",
            facts("BeyondTV", "BeyondTV", "TCL", 28, "TCL/BeyondTV/BeyondTV:9/PPR1.180610.011/AP06:user/release-keys") to "tcl-beyondtv-rt2851",
            facts("BeyondTV", "BeyondTV", "TCL", 28, "TCL/BeyondTV/BeyondTV:9/PPR1.180610.011/AP02:user/release-keys") to "tcl-beyondtv",
            facts("BeyondTV2", "BeyondTV", "TCL", 28, "TCL/BeyondTV2/BeyondTV:9/PPR1.180610.011/AP08:user/release-keys") to "tcl-beyondtv2-latin-america",
            facts("Smart TV", "C06", "TCL", 30, "TCL/C06_2K_GB/C06:11/AR2101/AR14:user/release-keys") to "hitachi-cdh-le32smart21",
            facts("Smart TV", "C06", "TCL", 30, "TCL/C06_2K_GB/C06:11/AR2101/AR20:user/release-keys") to "tcl-40s5400a",
            facts("Smart TV", "G07", "TCL", 30) to "tcl-g07-google-tv",
            // v2.5.0 TCL lists: each build to its own list by the longer fingerprint prefix.
            facts("BeyondTV2", "BeyondTV", "TCL", 28, "TCL/BeyondTV2/BeyondTV:9/PPR1.180610.011/AP06:user/release-keys") to "tcl-55c715",
            facts("BeyondTV2", "BeyondTV", "TCL", 30, "TCL/BeyondTV2/BeyondTV:11/PPR1.180610.011/AR05:user/release-keys") to "tcl-55c815",
            facts("Smart TV Pro", "BeyondTV4", "TCL", 30, "TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR09:user/release-keys") to "tcl-g03-google-tv",
            facts("Smart TV Pro", "BeyondTV4", "TCL", 30, "TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR11:user/release-keys") to "tcl-65c725",
            facts("unknown", "G10", "TCL", 31, "TCL/G10_4K_GB/G10:12/STT2.230203.001/x") to "tcl-55p7k",
            // FFalcon and TCL China sets share device and firmware; the brand tells them apart, and an
            // FFalcon set that reports TCL as its maker gets its own list by the id.
            facts("unknown", "tcl_mt5879_cn", "FFALCON", 30, manufacturer = "TCL") to "ffalcon-65s585c-pro",
            facts("unknown", "tcl_mt5879_cn", "TCL", 30) to "tcl-q10g-pro",
            facts("BRAVIA 4K GB", "BRAVIA_ATV2", "Sony", 28, "Sony/BRAVIA_ATV2_EU/BRAVIA_ATV2:9/PTT1.190515.001.S52/x") to "sony-kd-55xe8596",
            facts("BRAVIA 4K GB", "BRAVIA_ATV2", "Sony", 28, "Sony/BRAVIA_ATV2_EU/BRAVIA_ATV2:9/PTT1.190515.001.S62/x") to "sony-bravia-4k-gb",
            // The Vidda and Hisense VIDAA_TV lists are one build; a Vidda set that reports Hisense as its
            // maker gets the Hisense list, the subset that keeps the stock home, by the id.
            facts("VIDAA_TV", "MT9653", "Vidda", 30, manufacturer = "Hisense") to "hisense-e5q-pro",
            facts("VIDAA_TV", "MT9999", "Hisense", 30) to null,
            facts("VIDAA_TV", "MT9653", "Vidda", 30) to "vidda-mt9653",
            // The BYINTEK T5 Max is the Wanbo X5 Pro stick sold under another name: the brand decides.
            facts("4K Google TV Stick", "YYT", "BYINTEK", 34, manufacturer = "SkyworthDigital") to "byintek-t5-max",
            facts("4K Google TV Stick", "YYT", "SkyworthDigital", 34) to "wanbo-x5-pro",
            facts("Smart TV", "unknown", "TCL", 30) to null,
            // The shipped tcl-65c725 and tcl-g05-google-tv still match the family name alone and tie;
            // the earlier id order stands until the resolver policy (ruling 1). tcl-43c645 is parked.
            facts("Smart TV Pro", "unknown", "TCL", 30) to "tcl-65c725",
        )
        for ((f, id) in pinned) assertEquals(id, winner(f), "${f.brand} ${f.model} ${f.device} ${f.sdk} ${f.fingerprint}")
        // Each profile, asked with its own keys, resolves to itself, except the one superseded list;
        // and the ties settled by id are exactly these.
        // Test build: a candidate list is never the winner without the person's answer; with it, each
        // one, asked with its own keys, resolves to itself (no tested list matches those keys).
        for (c in BundledProfileSource.bundled.profiles.filter { it.isCandidate }) {
            val m = c.match
            val fingerprint = m.fingerprintPrefix?.let { if (Quirk.EXACT_PROFILE_MATCH in c.quirks) it else it + "x" } ?: "x"
            val own = facts(m.models.firstOrNull() ?: "unknown", m.devices.first(), m.brand!!, m.sdkMin!!, fingerprint)
            assertEquals(null, winner(own), c.id)
            assertEquals(c.id, source.candidateFor(own)?.id, c.id)
            assertEquals(c.id, (source.resolveNow(own, tryCandidate = true) as? ProfileResolution.Exact)?.profile?.id, c.id)
        }
        val profiles = BundledProfileSource.bundled.profiles.filter { !it.isCandidate }
        val ties = mutableSetOf<Set<String>>()
        for (pr in profiles) {
            val m = pr.match
            val own = facts(m.models.firstOrNull() ?: "unknown", m.devices.firstOrNull() ?: "unknown", m.brand!!, m.sdkMin ?: 30, (m.fingerprintPrefix ?: "") + "x")
            val scored = profiles.mapNotNull { o -> BundledProfileSource.score(o, own)?.let { (it to BundledProfileSource.specificity(o)) to o.id } }
            val top = scored.map { it.first }.maxWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
            scored.filter { it.first == top }.map { it.second }.toSet().takeIf { it.size > 1 }?.let { ties += it }
            assertEquals(if (pr.id == "tcl-g10-google-tv") "tcl-55s470g" else pr.id, winner(own), pr.id)
        }
        // The fingerprint prefix settles the BRAVIA_ATV2, BRAVIA_VH2, G05 and G08 pairs (pinned above);
        // tcl-g10-google-tv is the one true duplicate.
        assertEquals(setOf(setOf("tcl-55s470g", "tcl-g10-google-tv")), ties)
    }

    @Test fun malformedFilesFailWithTheLine() {
        val e = assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# T\ncom.ok.pkg\nnot a package line!\n", "bad") }
        assertEquals(3, e.line)
        assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# only comments\n", "empty") }
        val bom = DebloatTxt.parse("# Group\ncom.a.b   # note\n          # more note\r\ncom.c.d\n", "x", null)
        assertEquals("note more note", bom.groups.single().packages.first().note)
    }

    @Test fun bundledProfilesEqualAFreshParse() {
        // Test build: the candidate lists (CandidatesTest) come after the upstream profiles.
        val bundle = BundledProfileSource.bundled.let { b -> b.copy(profiles = b.profiles.filter { !it.isCandidate }) }
        assertEquals("3e34f40", bundle.upstreamCommit)
        assertEquals(examples.map { it.folder }, bundle.profiles.map { it.id })
        for (e in examples) assertEquals(DebloatTxtParser.parse(e.debloatTxt, e.folder), bundle.profiles.first { it.id == e.folder }, e.folder)
        assertEquals(SureSetGenerator.generate(bundle.profiles, BundledFactsTable.bundled), bundle.sureSets)
        // Every list still under review ships as an exact profile.
        assertTrue(bundle.profiles.map { it.id }.containsAll(SureSetGenerator.REVIEW_PENDING))
        // Codec round trip.
        assertEquals(bundle, ProfileBundleJson.decode(ProfileBundleJson.encode(bundle)))
        assertEquals(ProfileMeta.bundled.upstreamCommit, bundle.upstreamCommit)
    }

    @Test fun sureSetsFollowD26() {
        val profiles = SureSetGenerator.feeding(BundledProfileSource.bundled.profiles.filter { !it.isCandidate })
        val brands = profiles.groupBy { it.match.brand!!.lowercase() }
        val sets = SureSetGenerator.generate(profiles, BundledFactsTable.bundled).filter { brands.getValue(it.brand.lowercase()).size >= 3 }
        assertEquals(brands.filterValues { it.size >= 3 }.keys.sorted(), sets.map { it.brand.lowercase() })
        assertTrue(sets.any { it.brand == "Xiaomi" } && sets.any { it.brand == "TCL" })
        val keep = profiles.flatMap { it.neverDisable }.toSet()
        for (s in sets) {
            val mine = profiles.filter { it.match.brand.equals(s.brand, true) }
            for (g in s.groups) {
                assertEquals(GroupDefault.DISABLE, g.default)
                for (e in g.packages) {
                    assertTrue(e.name !in keep)
                    val n = mine.count { pr -> pr.groups.any { it.default == GroupDefault.DISABLE && e.name in it.packages.map { x -> x.name } } }
                    assertTrue(n >= 3, "${s.brand} ${e.name} in $n profiles")
                }
            }
            assertTrue(KnownLaunchers.TVLAUNCHER in s.stockLaunchers)
        }
        println("sure sets: " + sets.joinToString { s -> "${s.brand}=" + s.groups.flatMap { it.packages }.joinToString("/") { it.name.value } })
    }

    // The eight full sets (brands with three or more lists) are the ones 0.3.4 shipped, byte for
    // byte: the digest of their brand, lists, groups, rows and stock launchers in 0.3.4's profiles.json.
    // 0.3.7 (owner decision): the Hisense set gains com.google.android.partnersetup and
    // com.google.android.syncadapters.calendar from v2.7.0's hisense-smarttv-4k-ffm (9 to 11). The
    // lists promoted in 0.3.7 do not feed the sets yet (SureSetGenerator.REVIEW_PENDING).
    @Test fun theFullSetsAreThoseOf034() {
        val profiles = SureSetGenerator.feeding(BundledProfileSource.bundled.profiles.filter { !it.isCandidate })
        val brands = profiles.groupBy { it.match.brand!!.lowercase() }
        val sets = BundledProfileSource.bundled.sureSets
        val full = sets.takeWhile { brands.getValue(it.brand.lowercase()).size >= 3 }
        assertEquals(listOf("Amazon", "Hisense", "Panasonic", "Philips", "Sony", "TCL", "XGIMI", "Xiaomi"), full.map { it.brand })
        assertEquals(mapOf("Amazon" to 26, "Hisense" to 11, "Panasonic" to 2, "Philips" to 12, "Sony" to 28, "TCL" to 26, "XGIMI" to 2, "Xiaomi" to 29),
            full.associate { s -> s.brand to s.groups.sumOf { it.packages.size } })
        val canon = full.joinToString("") { s ->
            "${s.brand}|${s.profileIds.joinToString(",")}\n" +
                s.groups.joinToString("") { g -> "${g.id}|${g.title}|${g.default}\n" + g.packages.joinToString("") { "${it.name.value}|${it.note ?: ""}\n" } } +
                s.stockLaunchers.joinToString(",") { it.flat } + "\n"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canon.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("5bb3f4276f1faf5308cb627bb4f22d5655121c8d71e21be111fbeb8489679106", digest)
        // The careful sets come after every full set, so a full set wins when both match a TV.
        assertTrue(sets.drop(full.size).all { brands.getValue(it.brand.lowercase()).size < 3 })
    }

    // D26 amendment (0.3.5): brands with one or two lists get a careful set, and only of the six
    // packages reviewed for it; its stock launchers are the generic ones, so the home screen
    // decision is the one a TV with no set gets.
    @Test fun carefulSetsForBrandsWithOneOrTwoLists() {
        val profiles = SureSetGenerator.feeding(BundledProfileSource.bundled.profiles.filter { !it.isCandidate })
        val brands = profiles.groupBy { it.match.brand!!.lowercase() }
        val careful = BundledProfileSource.bundled.sureSets.filter { brands.getValue(it.brand.lowercase()).size < 3 }
        val six = setOf("com.google.android.feedback", "com.android.printspooler", "com.android.tv.feedbackconsent", "com.android.adservices.api",
            "com.android.ondevicepersonalization.services", "com.android.federatedcompute.services").map(::p).toSet()
        assertEquals(six, SureSetGenerator.CAREFUL_PACKAGES)
        val union = careful.flatMap { s -> s.groups.flatMap { g -> g.packages.map { it.name } } }.toSet()
        assertTrue(six.containsAll(union), "careful sets hold only the reviewed packages: ${union - six}")
        assertEquals(six, union, "every reviewed package is in some careful set")
        assertEquals(careful.map { it.brand.lowercase() }.sorted(), careful.map { it.brand.lowercase() })
        fun pkgs(vararg names: String) = names.map(::p).toSet()
        // The brands of the reported TVs the change is for (docs/lists-0.3.5, the per-brand table).
        val reported = mapOf(
            "Skyworth" to pkgs("com.google.android.feedback", "com.android.tv.feedbackconsent"),
            "SHARP" to pkgs("com.google.android.feedback", "com.android.tv.feedbackconsent"),
            "OnePlus" to pkgs("com.google.android.feedback"),
            "onn" to pkgs("com.google.android.feedback", "com.android.tv.feedbackconsent", "com.android.printspooler"),
            "Haier" to pkgs("com.google.android.feedback"),
            "google" to pkgs("com.google.android.feedback", "com.android.tv.feedbackconsent"),
            "Expressluck" to six - p("com.android.printspooler"),
            "SkyworthDigital" to six,
        )
        for ((brand, want) in reported) assertEquals(want, careful.single { it.brand == brand }.groups.flatMap { g -> g.packages.map { it.name } }.toSet(), brand)
        val askedSomewhere = profiles.flatMap { pr -> pr.groups.filter { it.default != GroupDefault.DISABLE }.flatMap { g -> g.packages.map { it.name } } }.toSet()
        val named = profiles.flatMap { pr -> pr.neverDisable + pr.stockLaunchers.map { it.pkg } + listOfNotNull(pr.setupWraith, pr.liveTv) }.toSet()
        for (s in careful) {
            assertEquals(KnownLaunchers.generic, s.stockLaunchers, s.brand)
            val mine = brands.getValue(s.brand.lowercase())
            assertEquals(mine.map { it.id }.sorted(), s.profileIds, s.brand)
            for (g in s.groups) {
                assertEquals(GroupDefault.DISABLE, g.default, s.brand)
                assertTrue(g.id in setOf("ads", "tracking", "preloads"), "${s.brand} ${g.id}")
                for (e in g.packages) {
                    assertTrue(e.name !in askedSomewhere && e.name !in named, "${s.brand} ${e.name}")
                    for (pr in mine) assertTrue(pr.groups.any { it.default == GroupDefault.DISABLE && e.name in it.packages.map { x -> x.name } }, "${s.brand} ${e.name} not off in ${pr.id}")
                    val off = profiles.count { pr -> pr.groups.any { it.default == GroupDefault.DISABLE && e.name in it.packages.map { x -> x.name } } }
                    assertTrue(off >= SureSetGenerator.CAREFUL_MIN_LISTS, "${e.name} off in $off lists")
                    assertTrue(BundledFactsTable.bundled.getValue(e.name).category in setOf("ads", "tracking", "preloads"), e.name.value)
                }
            }
        }
        println("careful sets: " + careful.joinToString { s -> "${s.brand}=" + s.groups.flatMap { it.packages }.joinToString("/") { it.name.value } })
    }

    // A TV of a careful-set brand without an exact list resolves to the careful set with the generic
    // stock launchers, the same launchers ProfileResolution.None gives.
    @Test fun aCarefulSetResolvesWithTheGenericLaunchers() = runBlocking<Unit> {
        val source = BundledProfileSource()
        val mi = Fixtures.miTv().facts()
        val sharp = source.resolve(mi.copy(brand = "SHARP", manufacturer = "SHARP", model = "AQUOS_TVE19A", device = "sindang", sdk = 30))
        assertTrue(sharp is ProfileResolution.BrandSureSet && sharp.brand == "SHARP", sharp.toString())
        assertEquals(KnownLaunchers.generic, sharp.stockLaunchers)
        assertEquals(setOf(p("com.google.android.feedback"), p("com.android.tv.feedbackconsent")), sharp.sureSet.flatMap { g -> g.packages.map { it.name } }.toSet())
        // A brand with a full set keeps it when its maker also has a careful set.
        val sonyBySkyworth = source.resolve(mi.copy(brand = "Sony", manufacturer = "Skyworth", model = "X1", device = "x1"))
        assertTrue(sonyBySkyworth is ProfileResolution.BrandSureSet && sonyBySkyworth.brand == "Sony", sonyBySkyworth.toString())
        assertEquals(ProfileResolution.None(KnownLaunchers.generic), source.resolve(mi.copy(brand = "Acme", manufacturer = "Acme", model = "X1", device = "x1")))
    }

    // RF-SAFETY-250 (d): TCL Live registers a TV input, and TV inputs are never default-off, so every
    // list that names it asks first and the TCL sure set leaves it out.
    @Test fun tclLiveIsAlwaysAnAskRow() {
        val live = p("com.tcl.channelplus")
        val profiles = BundledProfileSource.bundled.profiles
        val listing = profiles.filter { pr -> pr.groups.any { g -> g.packages.any { it.name == live } } }
        assertTrue(listing.map { it.id }.containsAll(listOf("tcl-g05-google-tv", "tcl-g10-google-tv", "tcl-qm6k-google-tv")), listing.map { it.id }.toString())
        for (pr in listing) {
            val groups = pr.groups.filter { g -> g.packages.any { it.name == live } }
            assertTrue(groups.none { it.default == GroupDefault.DISABLE }, "${pr.id}: ${groups.map { it.id }}")
        }
        val tcl = SureSetGenerator.generate(profiles, BundledFactsTable.bundled).single { it.brand.equals("TCL", ignoreCase = true) }
        assertTrue(tcl.groups.flatMap { it.packages }.none { it.name == live })
    }

    // RF-LAUNCHER-250 F1: the tuner input drivers the shipped lists name are never switched off; S3
    // shows them under "We keep these" instead of a row the run would refuse anyway.
    @Test fun knownTunerInputDriversAreNeverDisable() {
        val all = BundledProfileSource.bundled.profiles.associateBy { it.id }
        assertTrue(all.getValue("sony-bravia-ae2").neverDisable.containsAll(listOf(p("com.mediatek.dtv.tvinput.atsctuner"), p("com.sony.dtv.tvinput.atsc3tuner"))))
        assertTrue(all.getValue("prismplus-q65-ultra-r10g").neverDisable.containsAll(listOf(p("com.realtek.dtmb"), p("com.realtek.tv.atsc"), p("com.realtek.tv.isdb"))))
    }

    // Safety rule 8 (0.2.0): nothing that accessibility, the remote or remote pairing needs is ever
    // off by default, in a shipped list or a brand sure set: accessibility services and TalkBack, the
    // phone remote and the remote's services, pairing. Such rows are ask rows (or never-disable).
    @Test fun noAccessibilityRemoteOrPairingPackageIsDefaultOff() {
        val facts = BundledFactsTable()
        val words = Regex("talkback|accessib|marvin|remote|pairing|autopair|btservice|bluetooth|(^|_)rcu(_|$)|keyhandler|globalkey|virtualkey|fivekey|smartkey|keyintercept|inputkey")
        // Named like the remote but not it: settings downloaded from Amazon's servers.
        val notTheRemote = setOf(p("com.amazon.device_remote_config_sync_service"))
        fun guarded(pkg: PackageName): Boolean = pkg !in notTheRemote &&
            (facts.lookup(pkg)?.category == "accessibility" || pkg.value.split('.').drop(1).any { words.containsMatchIn(it.lowercase()) })
        val offenders = mutableListOf<String>()
        val profiles = BundledProfileSource.bundled.profiles
        for (pr in profiles) for (g in pr.groups.filter { it.default == GroupDefault.DISABLE }) for (e in g.packages)
            if (guarded(e.name) && e.name !in pr.neverDisable) offenders += "${pr.id} ${e.name.value} (${g.id})"
        for (sure in SureSetGenerator.generate(profiles, BundledFactsTable.bundled)) for (g in sure.groups) for (e in g.packages)
            if (guarded(e.name)) offenders += "sure set ${sure.brand} ${e.name.value}"
        assertEquals(emptyList(), offenders, "default-off accessibility, remote or pairing packages")
        val all = profiles.associateBy { it.id }
        fun askRow(id: String, pkg: String) = all.getValue(id).groups.single { g -> g.packages.any { it.name == p(pkg) } }.default
        for (id in listOf("google-chromecast-gtv-4k", "tcl-g05-google-tv", "tcl-g10-google-tv", "xiaomi-mi-box-s", "google-tv-streamer-4k-kirkwood"))
            assertEquals(GroupDefault.ASK, askRow(id, "com.google.android.marvin.talkback"), id)
        assertEquals(GroupDefault.ASK, askRow("tcl-g10-google-tv", "com.google.android.tv.remote.service"))
        for (id in listOf("google-chromecast-gtv-4k", "google-tv-streamer-4k-kirkwood"))
            assertEquals(GroupDefault.ASK, askRow(id, "com.google.android.tv.remotecontrol.logging"), id)
    }

    @Test fun resolveExactThenBrandThenNone() = runBlocking<Unit> {
        val source = BundledProfileSource()
        val mi = Fixtures.miTv().facts()
        val exact = source.resolve(mi)
        assertTrue(exact is ProfileResolution.Exact && exact.profile.id == "xiaomi-mitv-axso0", exact.toString())
        // Same TV after a firmware update to another SDK: no exact profile, the Xiaomi sure set.
        val updated = source.resolve(mi.copy(sdk = 30))
        assertTrue(updated is ProfileResolution.BrandSureSet && updated.brand == "Xiaomi" && updated.sureSet.isNotEmpty(), updated.toString())
        val unknownXiaomi = source.resolve(mi.copy(model = "MiTV-NEW1", device = "newdevice"))
        assertTrue(unknownXiaomi is ProfileResolution.BrandSureSet)
        val other = source.resolve(mi.copy(brand = "Acme", manufacturer = "Acme", model = "X1", device = "x1"))
        assertEquals(ProfileResolution.None(KnownLaunchers.generic), other)
        // A wrong brand never takes another brand's exact profile.
        assertTrue(source.resolve(mi.copy(brand = "Acme", manufacturer = "Acme")) is ProfileResolution.None)
    }

    @Test fun dockRanking() {
        val mi = profile("xiaomi-mitv-axso0")
        val installed = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.sonyliv", "com.android.tv")
            .map(::p).toSet()
        // The reference TV: inputs, Live TV installed, India.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.android.tv"),
            DockRanking.pick(mi, installed, hasInputs = true, liveTv = null, locale = "en-IN").map { it.value })
        // No inputs: four streaming apps, the regional table decides the fourth.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar"),
            DockRanking.pick(mi, installed, hasInputs = false, liveTv = null, locale = "en_IN").map { it.value })
        // A US locale skips Hotstar for nothing installed, so only three plus nothing else.
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom"),
            DockRanking.pick(null, installed, hasInputs = false, liveTv = null, locale = "en-US").map { it.value })
        // Only installed apps, never more than four, no duplicates even with a suggested dock.
        val withSuggested = mi.copy(suggestedDock = listOf(p("com.sonyliv"), p("com.netflix.ninja"), p("not.installed")))
        assertEquals(listOf("com.sonyliv", "com.netflix.ninja", "com.google.android.youtube.tv", "com.android.tv"),
            DockRanking.pick(withSuggested, installed, hasInputs = true, liveTv = p("com.android.tv"), locale = "en-IN").map { it.value })
        assertEquals(emptyList(), DockRanking.pick(null, emptySet(), hasInputs = true, liveTv = p("com.android.tv"), locale = "en-IN"))
        assertEquals("IN", DockRankingImpl.region("hi-Deva-IN"))
        assertEquals(null, DockRankingImpl.region("en"))
    }

    @Test fun factsCoverTheReferenceProfileTheSureSetsAndTheDockTable() {
        val facts = BundledFactsTable()
        val needed = profile("xiaomi-mitv-axso0").groups.flatMap { g -> g.packages.map { it.name } } +
            BundledProfileSource.bundled.sureSets.flatMap { s -> s.groups.flatMap { g -> g.packages.map { it.name } } } +
            DockRankingImpl.ranking("en-IN") + DockRankingImpl.ranking("en-US") + DockRankingImpl.ranking("en-GB") + p("com.android.tv")
        val missing = needed.distinct().filter { facts.lookup(it) == null }
        assertEquals(emptyList(), missing)
        val glance = facts.lookup(p("com.glance.tv"))!!
        assertTrue(glance.friendlyName.length <= 32 && glance.description.length <= 70)
        val raw = File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/facts.tsv").readText()
        assertTrue('\u2014' !in raw && "-".repeat(2) !in raw)
        for ((_, f) in BundledFactsTable.bundled) {
            assertTrue(f.friendlyName.isNotBlank() && f.friendlyName.length <= 32, f.toString())
            assertTrue(f.description.isNotBlank() && f.description.length <= 70, f.toString())
        }
        // Every upstream package has a name.
        val everyUpstream = examples.flatMap { e -> DebloatTxtParser.parse(e.debloatTxt, e.folder).groups.flatMap { g -> g.packages.map { it.name } } }.distinct()
        assertEquals(emptyList(), everyUpstream.filter { facts.lookup(it) == null })
    }
}
