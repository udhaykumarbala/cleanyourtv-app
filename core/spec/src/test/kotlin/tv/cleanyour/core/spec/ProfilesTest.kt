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
        assertEquals(324, upstream.size)
        assertEquals(174, examples.size)
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
            // A wizard the curated takeover keeps on (setupWraithStaysOn) is never-disable instead, and a
            // line the curated entry leaves out (leftOut) is in no place, as if commented out.
            val leftOut = ProfileMeta.bundled.profiles.getValue(e.folder).leftOut.keys.map(::p).toSet()
            val placed = inGroups.toSet() + profile.stockLaunchers.map { it.pkg } + listOfNotNull(profile.setupWraith) +
                profile.neverDisable.filter { it == KnownLaunchers.SETUPWRAITH } + leftOut
            assertTrue(inGroups.none { it in leftOut }, "${e.folder}: a left-out line is in a group")
            assertEquals(emptySet(), scanned - placed, "${e.folder}: packages lost")
            assertTrue(inGroups.all { it in scanned }, e.folder)
        }
    }

    @Test fun converterSeesEveryGroupTitleInTheMetadata() {
        val (bundle, problems) = ProfileConverter.convert(
            File(Fixtures.upstreamDir, "examples"),
            File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json"),
            "0cb195a",
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
        assertEquals(150, unconverted.size)
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

    // b06ab21: the R10G lists of the MOKA family. kalley-r10g keeps launcherx and the setup wizard on
    // (upstream #1807: with both off, system_server crashed in a boot loop), and prismplus-q65-ultra-r10g,
    // whose list switches the wizard off, leaves that line out (leftOut), as in 0.4.0: the home screen
    // switch is skipped for the family (HomeSwitchRisk.BOOT_LOOP), so neither profile switches either one off.
    // kalley-r10g still names its wizard with the takeover quirk, so a TV that has it off gets it back
    // only with the TV's own home screen in charge (DefaultPlanner.putBack, PutBackAdvice); both are
    // never-disable as well.
    @Test fun theR10gProfilesKeepTheStockHomeAndTheWizard() {
        for (id in listOf("kalley-r10g", "prismplus-q65-ultra-r10g")) {
            val pr = profile(id)
            val inGroups = pr.groups.flatMap { g -> g.packages.map { it.name } }
            for (pkg in listOf(KnownLaunchers.LAUNCHERX.pkg, KnownLaunchers.SETUPWRAITH)) assertTrue(pkg !in inGroups, "$id $pkg")
        }
        val prism = profile("prismplus-q65-ultra-r10g")
        assertEquals(null, prism.setupWraith)
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH !in prism.quirks)
        assertTrue(KnownLaunchers.SETUPWRAITH.value in ProfileMeta.bundled.profiles.getValue("prismplus-q65-ultra-r10g").leftOut)
        assertTrue(KnownLaunchers.SETUPWRAITH in profile("kalley-r10g").neverDisable)
        val kalley = profile("kalley-r10g")
        assertTrue(KnownLaunchers.LAUNCHERX.pkg in kalley.neverDisable && Quirk.STOCK_LAUNCHER_KEEP_ENABLED in kalley.quirks)
        assertEquals(KnownLaunchers.SETUPWRAITH, kalley.setupWraith)
        assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in kalley.quirks)
    }

    // Issue #1378 (Skyworth SWTV-22AE-FHD, Android 14): after the clean-up the remote's custom (Daily)
    // button did nothing, and the owner got it back by switching com.smartdevice.dailymanage,
    // dailyshortcut and myapp on again. The upstream list switches all three off; the profile leaves
    // those lines out and keeps them never-disable. The rest of the list is unchanged.
    @Test fun theSkyworth22aeKeepsTheRemoteButtonPackages() {
        val pr = profile("skyworth-swtv-22ae-fhd")
        val inGroups = pr.groups.flatMap { g -> g.packages.map { it.name } }.toSet()
        val keys = listOf("com.smartdevice.dailymanage", "com.smartdevice.dailyshortcut", "com.smartdevice.myapp").map(::p)
        val meta = ProfileMeta.bundled.profiles.getValue("skyworth-swtv-22ae-fhd")
        for (pkg in keys) {
            assertTrue(pkg !in inGroups, "${pkg.value} is in a group")
            assertTrue(pkg in pr.neverDisable, "${pkg.value} is not never-disable")
            assertTrue(pkg.value in meta.leftOut && "#1378" in meta.leftOut.getValue(pkg.value), pkg.value)
        }
        val listed = DebloatTxt.scan(examples.first { it.folder == "skyworth-swtv-22ae-fhd" }.debloatTxt, "skyworth-swtv-22ae-fhd")
            .groups.flatMap { g -> g.entries.map { it.pkg } }.toSet()
        assertTrue(listed.containsAll(keys), "the upstream list still switches them off")
        assertEquals(listed - keys.toSet() - KnownLaunchers.SETUPWRAITH, inGroups)
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
        // Issue #111: PatchWall is the owner's choice (an ask row), not an advert switched off.
        assertTrue(p("com.mitv.tvhome.atv") !in byId.getValue("ads").packages.map { it.name })
        assertEquals(GroupDefault.ASK, byId.getValue("ads-optional").default)
        assertTrue(p("com.mitv.tvhome.atv") in byId.getValue("ads-optional").packages.map { it.name })
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
        // Simple switches off the disable groups only; 19 packages on this TV (20 before PatchWall
        // became an ask row, issue #111).
        val simple = mi.groups.filter { it.default == GroupDefault.DISABLE }.flatMap { it.packages }
        assertEquals(19, simple.size)
        // Disable groups come first, in the fixed category order.
        assertEquals(listOf("ads", "tracking", "preloads", "brand", "other", "ads-optional", "streaming", "voice", "casting", "updates"), mi.groups.map { it.id })
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
    // The b06ab21 lists name no new updater.
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
    // 0.4.5 (second curator): Xiaomi China-market sets whose Home takes only allowlisted apps, so
    // com.mitv.tvhome stays on and Setup does the clean-up only (no new home screen offered).
    private val cleanUpOnlyPart2: Set<String> = setOf(
        "xiaomi-mitv-astp0",
        "xiaomi-mitv-mteq0",
        "redmi-mitv-mftp0",
    )

    @Test fun theProfilesWithNoKnownStockLauncher() {
        val none = BundledProfileSource.bundled.profiles.filter { it.stockLaunchers.isEmpty() && it.platform != Platform.FIRE_OS && it.platform != Platform.TCL_CN }
        // xiaomi-mitv-mftr0 (0.3.7): China firmware whose home is com.mitv.tvhome, not a known launcher.
        // sber-salute-tv-at30a6 (0.4.5): StarOS, whose home is Sber's ru.sberdevices.starlauncher.
        assertEquals(setOf("challenger-32t22bt", "freebox-player-pop", "google-tv-streamer-4k-kirkwood", "hisense-e5q-pro", "kalley-43g200",
            "sber-salute-tv-at30a6", "stellamore-t950s", "verizon-stream-tv", "vidda-mt9653", "xiaomi-mitv-ansp0", "xiaomi-mitv-astp1", "xiaomi-mitv-mffu0", "xiaomi-mitv-mftr0") + cleanUpOnlyPart2, none.map { it.id }.toSet())
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
            // An AU G08 on Android 12 reports the EU list's fingerprint, so it gets that list (0.4.5).
            facts("Smart TV Pro", "G08", "TCL", 31, "TCL/G08_4K_GB/G08:12/STT2.221228.001/AS34:user/release-keys") to "tcl-g08-eu-android12",
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
            // The Strong LEAP-S3 box (YDA board) is sold as Strong and as Thomson; the maker, SkyworthDigital, decides.
            facts("4K Google TV Box", "YDA", "Strong", 34, manufacturer = "SkyworthDigital") to "strong-leap-s3",   // #399, #693, #813
            facts("4K Google TV Box", "YDA", "Thomson", 34, manufacturer = "SkyworthDigital") to "strong-leap-s3",   // #601
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
            val own = facts(m.models.first(), m.devices.first(), m.brand!!, m.sdkMin!!)
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

    // 0.4.5 (second curator): each promoted b06ab21 list resolves from a TV report in our issues, and
    // the near misses (another Android version, another badge on the same board) keep their old path.
    @Test fun promotedAtB06ab21Part2ResolveFromTheirReports() {
        val source = BundledProfileSource()
        fun f(brand: String, maker: String, model: String, device: String, sdk: Int, fingerprint: String) =
            Fixtures.miTv().facts().copy(brand = brand, manufacturer = maker, model = model, device = device, sdk = sdk, fingerprint = fingerprint)
        val reports = listOf<Pair<tv.cleanyour.core.model.DeviceFacts, String?>>(
            f("Xiaomi", "Xiaomi", "MIBOX4", "oneday", 31, "Xiaomi/oneday/oneday:12/STTC.230106.001/4560:user/release-keys") to "xiaomi-mi-box-s-android12",   // #1153
            f("Xiaomi", "Xiaomi", "MiTV-MSSP2", "volver", 28, "Xiaomi/volver/volver:9/PI/6305:user/release-keys") to "xiaomi-mitv-mssp2",   // #1209
            f("Xiaomi", "Xiaomi", "MiTV-MSSP2", "volver", 28, "Xiaomi/volver/volver:9/PI/4621:user/release-keys") to "xiaomi-mitv-mssp2",   // #814
            f("Xiaomi", "Xiaomi", "MiTV-MSSP2", "volver", 30, "Xiaomi/volver/volver:11/RTT2.211214.001/933:user/release-keys") to null,   // #540, Android 11
            f("Xiaomi", "Xiaomi", "MiTV-MSSP0", "amelie", 28, "Xiaomi/amelie/amelie:9/PI/4072:user/release-keys") to "xiaomi-mitv-mssp0",   // #970
            f("SaluteTV", "SaluteTV", "SaluteTV", "m5621", 28, "MediaTek/m5621/m5621:9/PPR2.180905.006.A1/tuz_starosfw_jenkins06181401:user/release-keys") to null,   // #1052, the board name
            f("Xiaomi", "Xiaomi", "MiTV-AYFR0", "soul", 34, "Xiaomi/soul/soul:14/UKG3.250107.001/V816.0.26.6.10.UZFAATK:user/release-keys") to "xiaomi-mitv-ayfr0",   // #1173
            f("Xiaomi", "Xiaomi", "MiTV-AYFR0", "soul", 30, "Xiaomi/soul/soul:11/RTT0.211222.001/1546:user/release-keys") to null,   // #1180, Android 11
            f("Xiaomi", "xiaomi", "MiTV-ASTP0", "mulan", 28, "Xiaomi/mulan/mulan:9/PPR1.180610.011/3982:user/release-keys") to "xiaomi-mitv-astp0",   // #882
            f("Xiaomi", "xiaomi", "MiTV-ASTP0", "mulan", 28, "Xiaomi/mulan/mulan:9/PPR1.180610.011/OS3.0.101.0.PSTAATV:user/release-keys") to "xiaomi-mitv-astp0",   // #340
            f("xiaomi", "xiaomi", "MiTV-MTEQ0", "baymax", 29, "xiaomi/baymax/baymax:10/QQ2A.200305.004.A1/2446:user/release-keys") to "xiaomi-mitv-mteq0",   // #1079
            f("xiaomi", "xiaomi", "MiTV-MFTP0", "alita", 28, "xiaomi/alita/alita:9/PPR2.180905.006.A1/6318:user/release-keys") to "redmi-mitv-mftp0",   // #670
            f("Xiaomi", "Xiaomi", "MIBOX4", "oneday", 28, "Xiaomi/oneday/oneday:9/PI/3595:user/release-keys") to "xiaomi-mi-box-s",   // #772, older PI firmware
            f("Xiaomi", "Xiaomi", "MIBOX4", "oneday", 28, "Xiaomi/oneday/oneday:9/PI/3933:user/release-keys") to "xiaomi-mi-box-s",   // #187, the tested firmware
            f("AQUA", "Haier", "AQUA MatrixTV EE", "omonia", 34, "AQUA/AQ9676EU/omonia:14/UKN6.260116.056/15136903:user/release-keys") to "aqua-matrixtv-ee-50",   // #646
            f("Haier", "Haier", "Haier MatrixTV EE", "omonia", 34, "Haier/HR9676EU/omonia:14/UKN5.251016.114/14643368:user/release-keys") to "aqua-matrixtv-ee-50",   // #822
            f("Haier", "Haier", "Haier MatrixTV DE", "omonia", 34, "Haier/HR9676EUF/omonia:14/UKN2.241219.053/13043114:user/release-keys") to "aqua-matrixtv-ee-50",   // #926
            f("JVC", "MTC", "SA 4K Android TV", "seocho", 30, "JVC/MTCMSD6886SA/seocho:11/RTO8.250904.001/14050644:user/release-keys") to "jvc-lt55mb508",   // #127
            f("Sharp", "skyworth", "SHARP 2K Smart TV-A", "beomil", 34, "Sharp/SW11_SHARP_APEU/beomil:14/UKN6.260116.061/15165694:user/release-keys") to "sharp-2t-c32gh3000i",   // #793
            f("skyworth", "skyworth", "SWTV-24AE-FHD", "beomil", 34, "skyworth/SW11_TG/beomil:14/UKN7.260716.011/15978261:user/release-keys") to null,   // #108, neither SHARP nor Motorola
            f("THOMSON", "SPPL", "SPPL 2K ANDROID TV", "sauve", 34, "THOMSON/SPPL_2K_RT41/sauve:14/UKRE.260524.055/16101080:user/release-keys") to "thomson-43rt1055",   // #558
            f("THOMSON", "SPPL", "SPPL 2K ANDROID TV", "sauve", 30, "THOMSON/SPPL_2K_RT41/sauve:11/RTK4.241026.026/12861515:user/release-keys") to null,   // #825, Android 11
            f("Amazon", "Amazon", "AFTCIBFBD1", "coyote", 30, "Amazon/coyote/coyote:11/RS8180.3749N/0032280323460:user/amz-p,release-keys") to "hisense-fire-tv-aftcibfbd1",   // #949
            f("Amazon", "Amazon", "AFTCIBFBD1", "coyote", 30, "Amazon/coyote/coyote:11/RS8180.3734N/0032280319620:user/amz-p,release-keys") to "hisense-fire-tv-aftcibfbd1",   // #347
            f("eSTREAM4K", "SEI Robotics", "TiVo Stream 4K", "SEI400TV", 31, "eSTREAM4K/SEI400TV/SEI400TV:12/SC/1272:user/release-keys") to null,   // #1214, parked
        )
        for ((facts, id) in reports) assertEquals(id, (source.resolveNow(facts) as? ProfileResolution.Exact)?.profile?.id, "${facts.model} ${facts.fingerprint}")
        for (id in cleanUpOnlyPart2) {
            val pr = BundledProfileSource.bundled.profiles.single { it.id == id }
            assertTrue(p("com.mitv.tvhome") in pr.neverDisable && pr.stockLaunchers.isEmpty(), id)
        }
    }

    @Test fun malformedFilesFailWithTheLine() {
        val e = assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# T\ncom.ok.pkg\nnot a package line!\n", "bad") }
        assertEquals(3, e.line)
        assertFailsWith<DebloatTxtException> { DebloatTxtParser.parse("# only comments\n", "empty") }
        val bom = DebloatTxt.parse("# Group\ncom.a.b   # note\n          # more note\r\ncom.c.d\n", "x", null)
        assertEquals("note more note", bom.groups.single().packages.first().note)
    }

    // C2 (0.4.5): a commented-out package line is a package the owner keeps on. Inside a run it
    // neither ends the run nor titles a group, and the comments indented under it are its own note.
    @Test fun keptPackageLinesNeitherEndARunNorTitleAGroup() {
        val mid = DebloatTxt.scan("# ==== 1. Telemetry ====\ncom.a.b   # one\n# com.k.kept   # stays on\ncom.c.d   # two\n", "x")
        assertEquals(listOf("1. Telemetry"), mid.groups.map { it.title })
        assertEquals(listOf("com.a.b", "com.c.d"), mid.groups.single().entries.map { it.pkg.value })
        assertEquals(mapOf(p("com.k.kept") to "stays on"), mid.kept)
        // The indented continuation of a kept line is the kept line's note, not the previous entry's.
        val cont = DebloatTxt.scan("# Group\ncom.a.b   # note\n# com.k.kept   # first part\n    # second part\n#        # third part\ncom.c.d\n", "x")
        assertEquals("note", cont.groups.single().entries.first().note)
        assertEquals(null, cont.groups.single().entries.last().note)
        assertEquals("first part second part third part", cont.kept[p("com.k.kept")])
        // A kept line right under the title: the title stays, the run starts after it.
        val first = DebloatTxt.scan("# Header\n\n# Preloads\n# com.k.kept\n    # why\ncom.a.b\n", "x")
        assertEquals(listOf("Preloads"), first.groups.map { it.title })
        assertEquals(mapOf(p("com.k.kept") to "why"), first.kept)
        // Any other comment inside a run still ends it; a package also listed as active is not kept,
        // and header lines are never kept.
        val two = DebloatTxt.scan("#   com.h.header\n\n# One\ncom.a.b\n# Two\n# com.a.b\ncom.c.d\n", "x")
        assertEquals(listOf("One", "Two"), two.groups.map { it.title })
        assertEquals(emptyMap(), two.kept)
        assertTrue(p("com.h.header") in DebloatTxt.keepListIn(listOf("# Keep:") + two.header))
    }

    // Packages under a block of kept lines only join the group before them, which the converter
    // reports; a "## heading" under a kept line is a heading, not the kept line's note.
    @Test fun keptOnlyBlocksAreReportedAndHeadingsStayHeadings() {
        val joined = DebloatTxt.scan("# One\ncom.a.b\n\n# com.k.kept   # stays on\n    # why\ncom.c.d\n", "x")
        assertEquals(listOf("One"), joined.groups.map { it.title })
        assertEquals(listOf("com.a.b", "com.c.d"), joined.groups.single().entries.map { it.pkg.value })
        assertEquals(listOf(6), joined.keptOnlyTitles)
        assertEquals(mapOf(p("com.k.kept") to "stays on why"), joined.kept)
        assertEquals(emptyList(), DebloatTxt.scan("# com.k.kept\ncom.a.b\n", "x").keptOnlyTitles, "no group before: an untitled group, as before")
        val above = DebloatTxt.scan("# One\ncom.a.b\n\n# com.k.kept   # stays on\n## Two\ncom.c.d\n", "x")
        assertEquals(listOf("One", "# Two"), above.groups.map { it.title })
        assertEquals(mapOf(p("com.k.kept") to "stays on"), above.kept)
        assertEquals(emptyList(), above.keptOnlyTitles)
        val inRun = DebloatTxt.scan("# One\ncom.a.b\n# com.k.kept   # stays on\n## Two\ncom.c.d\n", "x")
        assertEquals(listOf("One", "# Two"), inRun.groups.map { it.title })
        assertEquals(mapOf(p("com.k.kept") to "stays on"), inRun.kept)
        assertEquals(ProfileConverter.keptOnly("x", 6), "x/debloat.txt:6: the comment block above holds only commented-out packages, so these packages join the group before it")
    }

    // A header with no blank line before the first group ends at the last empty "#" line above it,
    // when a title line follows; otherwise the block stays one title block, as before.
    @Test fun aGluedHeaderEndsAtItsLastEmptyCommentLine() {
        val cut = DebloatTxt.scan("# Model line\n# Keep: com.x.y\n#\n# Telemetry\ncom.a.b\n", "x")
        assertEquals(listOf("Telemetry"), cut.groups.map { it.title })
        assertEquals(listOf("# Model line", "# Keep: com.x.y", "#"), cut.header)
        assertEquals(setOf(p("com.x.y")), DebloatTxt.keepListIn(cut.header))
        val nothingAfter = DebloatTxt.scan("# Model line\n#\n# com.k.kept\ncom.a.b\n", "x")
        assertEquals(listOf("Model line"), nothingAfter.groups.map { it.title })
        assertEquals(emptyList(), nothingAfter.header)
        assertEquals(mapOf(p("com.k.kept") to null), nothingAfter.kept)
        val blank = DebloatTxt.scan("# Model line\n\n# Title\n#\n# More about it\ncom.a.b\n", "x")
        assertEquals(listOf("Title"), blank.groups.map { it.title })
        assertEquals(listOf("# Model line"), blank.header)
        val noEmpty = DebloatTxt.scan("# Model line\n# Telemetry\ncom.a.b\n", "x")
        assertEquals(listOf("Model line"), noEmpty.groups.map { it.title })
    }

    // FolderMeta.leftOut: an active line the curated entry leaves out is in no group, as if it were
    // commented out, and an entry that names no active line fails the conversion.
    @Test fun leftOutLinesAreNotInTheProfile() {
        val text = "# One\ncom.a.b\ncom.c.d   # left out\n\n# Two\ncom.e.f\n"
        fun meta(leftOut: Map<String, String>) = FolderMeta(
            match = MatchMeta(models = listOf("X"), brand = "B"), platform = "ANDROID_TV",
            groups = mapOf("One" to CategoryMeta("tracking"), "Two" to CategoryMeta("ads")), leftOut = leftOut,
        )
        val all = DebloatTxt.parse(text, "x", meta(emptyMap())).groups.flatMap { g -> g.packages.map { it.name.value } }
        assertEquals(listOf("com.a.b", "com.c.d", "com.e.f").sorted(), all.sorted())
        val out = DebloatTxt.parse(text, "x", meta(mapOf("com.c.d" to "not tested off")))
        assertEquals(listOf("com.a.b", "com.e.f").sorted(), out.groups.flatMap { g -> g.packages.map { it.name.value } }.sorted())
        assertTrue(out.neverDisable.none { it.value == "com.c.d" })
        val scan = DebloatTxt.scan(text, "x")
        assertEquals(emptyList(), ProfileConverter.staleLeftOut(scan, meta(mapOf("com.c.d" to "r"))))
        assertEquals(listOf("com.k.gone"), ProfileConverter.staleLeftOut(scan, meta(mapOf("com.c.d" to "r", "com.k.gone" to "r"))))
        // A kept (commented-out) line is not an active line either.
        val kept = DebloatTxt.scan("# One\ncom.a.b\n# com.c.d\n", "x")
        assertEquals(listOf("com.c.d"), ProfileConverter.staleLeftOut(kept, meta(mapOf("com.c.d" to "r"))))
    }

    // The real lists that split a group at a kept line before the fix, and every upstream folder:
    // no group title is a package line. tcl-g09-google-tv opens two groups with a prose note that
    // starts with a package name; both are curated ask groups and stay as they are.
    @Test fun noGroupTitleIsAPackageLine() {
        val titleStartsWithPackage = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+\\b")
        val proseNotes = setOf(
            "tcl-g09-google-tv: com.mirakulo.astrotv.service is Ginga, the ISDB-Tb broadcast interactive-TV engine (AstroTVService,",
            "tcl-g09-google-tv: com.google.android.tv.remote.service pairs phone remote apps (_androidtvremote2._tcp, 6466 pairing,",
        )
        val found = upstream.flatMap { e -> DebloatTxt.scan(e.debloatTxt, e.folder).groups.map { "${e.folder}: ${it.title}" } }
            .filter { titleStartsWithPackage.containsMatchIn(it.substringAfter(": ")) }
        assertEquals(proseNotes, found.toSet())
        fun scan(folder: String) = DebloatTxt.scan(upstream.first { it.folder == folder }.debloatTxt, folder)
        val g03 = scan("tcl-g03-google-tv")
        assertEquals(listOf("com.tcl.bi", "com.tcl.logkit", "com.google.android.feedback"),
            g03.groups.single { it.title == "1. TCL and Google telemetry" }.entries.map { it.pkg.value })
        assertTrue(p("com.tcl.xian.StartandroidService") in g03.kept)
        assertTrue(p("com.tcl.versionUpdateApp") in scan("tcl-ak30a5").kept)
        assertTrue(p("com.google.android.tv.remote.service") in scan("tcl-g10-google-tv").kept)
        val r10g = scan("kalley-r10g")
        assertTrue(p("com.google.android.apps.tv.launcherx") in r10g.kept && KnownLaunchers.SETUPWRAITH in r10g.kept)
        for (s in listOf(g03, r10g, scan("tcl-ak30a5"), scan("tcl-g10-google-tv"))) {
            val active = s.groups.flatMap { g -> g.entries.map { it.pkg } }.toSet()
            assertTrue(s.kept.keys.none { it in active })
        }
    }

    @Test fun bundledProfilesEqualAFreshParse() {
        // Test build: the candidate lists (CandidatesTest) come after the upstream profiles.
        val bundle = BundledProfileSource.bundled.let { b -> b.copy(profiles = b.profiles.filter { !it.isCandidate }) }
        assertEquals("0cb195a", bundle.upstreamCommit)
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
            // Issue #111: an ask group holds owner's-choice packages only (ProfileCategory.OWNERS_CHOICE).
            for (g in s.groups.filter { it.default == GroupDefault.ASK }) assertTrue(g.packages.all { ProfileCategory.isOwnersChoice(it.name) }, "${s.brand} ${g.id}")
            for (g in s.groups.filter { it.default != GroupDefault.ASK }) {
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
    // b06ab21: the same rows. The Amazon set's notes for com.amazon.media.recommendations,
    // com.amazon.client.metrics.api and com.amazon.tv.developer.dataservice now come from
    // amazon-fire-tv-afttiff43, the first Amazon list, whose Omni rows note them.
    // Issue #111: the Xiaomi set's PatchWall row moves from off to an ask row ("More promoted
    // content"), still 29 rows.
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
        assertEquals("701fb0da11cb2e11c122cb4d8944362c3ea95a20530152dfdd9e46004457e1a7", digest)
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
            "MOKA" to pkgs("com.android.printspooler"),
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
        // At b06ab21 the tcl-g10 list keeps the phone remote service on (the remote mic's bridge, #1441).
        assertTrue(all.getValue("tcl-g10-google-tv").groups.none { g -> g.packages.any { it.name == p("com.google.android.tv.remote.service") } })
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
