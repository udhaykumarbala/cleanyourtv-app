package tv.cleanyour.core.flows

import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HdmiSource
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.3.2 B1 and B2: the TV report and the problem report, from a stored snapshot only. A fixture
// Hisense 55A6H (issue #1's TV) with personal values planted where the probe could have read them:
// the report holds the TV's matching keys and its system packages, and none of the planted values.
class TvReportTest {
    private val tvId = TvId("a1b2c3d4e5f60718")
    private val serialLast4 = "7Q2K"
    private val about = AppAbout("0.3.2", "full", "Samsung SM-S921B", "Android 16")

    private val launcherx = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")
    private val wraith = Component(PackageName("com.google.android.tungsten.setupwraith"), ".SetupWraithActivity")
    private val storeDemo = PackageName("com.hisense.storedemo")

    private fun facts(brand: String = "Hisense", model: String = "55A6H", device: String = "HiTV-MT9602") = DeviceFacts(
        brand = brand, manufacturer = "Hisense", model = model, device = device, serialLast4 = serialLast4, sdk = 30, release = "11",
        fingerprint = "Hisense/HE55A6H/HiTV-MT9602:11/RTM6.230109.155/155:user/release-keys", abis = listOf("armeabi-v7a"),
        ramMb = 2048, hasGms = true, adbSecure = true, adbFeatures = setOf("shell_v2"), installRefusal = false, locale = "en-GB",
    )

    private fun pkg(enabled: EnabledState, system: Boolean) = PackageState(enabled, system, 1, null, false, false, "Label", false)

    // Inserted out of order: the report sorts them.
    private val packages = linkedMapOf(
        storeDemo to pkg(EnabledState.DISABLED_USER, true),
        PackageName("org.videolan.vlc") to pkg(EnabledState.ENABLED, false),
        launcherx.pkg to pkg(EnabledState.ENABLED, true),
        PackageName("com.arun.familyphotos") to pkg(EnabledState.ENABLED, false),
        PackageName("com.hisense.smartvision") to pkg(EnabledState.DISABLED, true),
        wraith.pkg to pkg(EnabledState.ENABLED, true),
    )

    private fun snapshot(f: DeviceFacts = facts(), pkgs: Map<PackageName, PackageState> = packages) = Snapshot(
        tvId = tvId, takenAtMs = Samples.NOW_MS, reason = SnapshotReason.BASELINE, facts = f, packages = pkgs,
        home = HomeResolution(launcherx, listOf(HomeClaimant(wraith, 1), HomeClaimant(launcherx, 2))),
        settings = mapOf(SettingKey.DEVICE_NAME to "Arun Living Room", SettingKey.SCREENSAVER_ENABLED to "1"),
        ime = null, screensaver = emptyList(), launcherOverlay = null,
        hdmi = listOf(HdmiSource(1, "Arun Sky Box", "com.example/.Hdmi1", false)), contentHash = Samples.zeroHash,
    )

    private fun input(s: Snapshot = snapshot()) = TvReportInput(
        about, s, ProfileResolution.BrandSureSet("Hisense", emptyList(), emptyList()), HomeDecision(false, "WIZARD_STAYS_ON"), setOf(storeDemo),
    )

    private val expected = """
        Clean Your TV: TV report
        App: 0.3.2 (full)
        Phone: Samsung SM-S921B, Android 16

        TV
        Brand: Hisense
        Manufacturer: Hisense
        Model: 55A6H
        Device: HiTV-MT9602
        Android: 11 (SDK 30)
        Build: Hisense/HE55A6H/HiTV-MT9602:11/RTM6.230109.155/155:user/release-keys
        Google Play services: yes
        Home screen it came with: Google TV
        Read from the TV: 2025-10-09, before any change

        Matching
        Profile: brand set Hisense
        Home switch: blocked WIZARD_STAYS_ON

        Home screen
        Resolves to: com.google.android.apps.tv.launcherx/.home.HomeActivity
        Claimant: com.google.android.apps.tv.launcherx/.home.HomeActivity priority 2
        Claimant: com.google.android.tungsten.setupwraith/.SetupWraithActivity priority 1

        System apps (4)
        com.google.android.apps.tv.launcherx enabled
        com.google.android.tungsten.setupwraith enabled
        com.hisense.smartvision disabled
        com.hisense.storedemo disabled-user, switched off by this app

        Other apps: 2 (not named)
    """.trimIndent() + "\n"

    // What the person shares, line for line.
    @Test fun theTvReportOfTheFixtureTv() = assertEquals(expected, TvReport.text(input()))

    // 0.3.3 D1: the compact report for the form's box: the same header, the packages grouped by
    // state, sorted, comma separated. A package this app switched off and that is still off is in
    // the first group.
    @Test fun theCompactReportOfTheFixtureTv() {
        val compact = expected.substringBefore("System apps (4)") + """
            System apps (4)
            Switched off by this app (1):
            com.hisense.storedemo

            Off, disabled (1):
            com.hisense.smartvision

            On, enabled (2):
            com.google.android.apps.tv.launcherx, com.google.android.tungsten.setupwraith

            Other apps: 2 (not named)
        """.trimIndent() + "\n"
        assertEquals(compact, TvReport.compact(input()))
        // One that came back on after the app switched it off is shown in its state's group.
        val back = packages + (storeDemo to pkg(EnabledState.ENABLED, true))
        assertTrue("On, enabled (3):\ncom.google.android.apps.tv.launcherx, com.google.android.tungsten.setupwraith, com.hisense.storedemo\n" in
            TvReport.compact(input(snapshot(pkgs = back))))
        assertFalse("Switched off by this app" in TvReport.compact(input(snapshot(pkgs = back))))
    }

    // Long lists wrap at TvReport.WRAP characters, each line but the last ending in a comma.
    @Test fun theCompactListsWrap() {
        val many = (1..40).associate { PackageName("com.example.vendor.part%02d".format(it)) to pkg(EnabledState.DEFAULT, true) }
        val text = TvReport.compact(input(snapshot(pkgs = many)))
        val list = text.lines().dropWhile { it != "On, default (40):" }.drop(1).takeWhile { it.isNotBlank() }
        assertTrue(list.size > 1)
        assertTrue(list.all { it.length <= TvReport.WRAP }, list.joinToString("\n"))
        assertTrue(list.dropLast(1).all { it.endsWith(",") } && !list.last().endsWith(","))
        assertEquals(many.keys.map { it.value }, list.joinToString(" ").split(", ").map { it.trim() })
    }

    @Test fun theSameTvAlwaysGivesTheSameReport() {
        val shuffled = LinkedHashMap(packages.entries.reversed().associate { it.key to it.value })
        assertEquals(TvReport.text(input()), TvReport.text(input(snapshot(pkgs = shuffled))))
    }

    // Only the factory's system apps are named; the person's own apps are a count.
    @Test fun onlySystemPackagesAreNamed() {
        val text = TvReport.text(input())
        for ((p, st) in packages) assertEquals(st.system, p.value in text, p.value)
        assertTrue("Other apps: 2 (not named)" in text)
    }

    // A launcher the person installed (Projectivy here) that claims HOME and is what HOME resolves to is
    // never named, in the TV report or the problem report; the factory's own claimants and the
    // launcher this app installs still are.
    @Test fun aLauncherThePersonInstalledIsNeverNamed() {
        val projectivy = Component(PackageName("com.spocky.projengmenu"), ".ui.home.MainActivity")
        val pkgs = packages + mapOf(projectivy.pkg to pkg(EnabledState.ENABLED, false), Launcher.pkg to pkg(EnabledState.ENABLED, false))
        val home = HomeResolution(projectivy, listOf(HomeClaimant(wraith, 1), HomeClaimant(projectivy, 3), HomeClaimant(launcherx, 2), HomeClaimant(Launcher.home, 3)))
        val s = snapshot(pkgs = pkgs).copy(home = home)
        val text = TvReport.text(input(s))
        val problem = TvReport.problem(about, input(s), "log")
        for (out in listOf(text, problem)) {
            assertFalse("projengmenu" in out, out)
            assertTrue("Resolves to: an app you installed (not named)\n" in out, out)
            assertTrue(
                "Claimant: com.gathin.tvhome/.MainActivity priority 3\n" +
                    "Claimant: an app you installed (not named) priority 3\n" +
                    "Claimant: com.google.android.apps.tv.launcherx/.home.HomeActivity priority 2\n" +
                    "Claimant: com.google.android.tungsten.setupwraith/.SetupWraithActivity priority 1\n" in out,
                out,
            )
        }
        // A HOME package the probe never listed counts as the person's too.
        val unknown = Component(PackageName("ca.dstudio.atvlauncher.free"), ".MainActivity")
        val t2 = TvReport.text(input(snapshot().copy(home = HomeResolution(unknown, listOf(HomeClaimant(unknown, 1))))))
        assertFalse("atvlauncher" in t2, t2)
        assertTrue("Claimant: an app you installed (not named) priority 1" in t2, t2)
    }

    // Never the serial, the TV id (derived from it), the TV's name for itself, its inputs, labels or
    // an address, in the report or in the link.
    @Test fun noPersonalValueIsInTheReportOrTheLink() {
        val text = TvReport.text(input())
        val link = URLDecoder.decode(IssueLinks.tvSupport(input()).url, "UTF-8")
        for (out in listOf(text, TvReport.compact(input()), link)) {
            for (secret in listOf(serialLast4, tvId.value, "Arun", "Sky Box", "Label", "en-GB", "shell_v2")) assertFalse(secret in out, "$secret in\n$out")
        }
    }

    // A TV's own strings are free text: an address, a MAC, a serial or a code planted in them is masked.
    @Test fun valuesFromTheTvAreScrubbed() {
        val f = facts(brand = "Hisense serialno=G070VM1234", model = "55A6H at 192.168.1.20 code 482913", device = "aa:bb:cc:dd:ee:ff 0123456789abcdef")
        val text = TvReport.text(input(snapshot(f)))
        val link = URLDecoder.decode(IssueLinks.tvSupport(input(snapshot(f))).url, "UTF-8")
        for (out in listOf(text, link)) {
            for (secret in listOf("G070VM1234", "192.168.1.20", "482913", "aa:bb:cc", "0123456789abcdef")) assertFalse(secret in out, "$secret in\n$out")
        }
        assertTrue("Model: 55A6H at [address] code [code]" in text)
    }

    // The tv_support form's own field ids, prefilled; 0.3.3 D1: the compact report too, in the
    // form's "report" box (the id in tv_support.yml), encoded so newlines and commas survive.
    @Test fun theTvSupportLinkPrefillsTheFormFields() {
        val link = IssueLinks.tvSupport(input())
        val url = link.url
        assertTrue(url.startsWith("https://github.com/udhaykumarbala/cleanyourtv-app/issues/new?template=tv_support.yml&"), url)
        val q = query(url)
        assertEquals("Hisense 55A6H", q["model"])
        assertEquals("Android 11, RTM6.230109.155", q["android"])
        assertEquals("Google TV", q["platform"])
        assertEquals("TV support: Hisense 55A6H", q["title"])
        assertTrue(link.reportInLink)
        assertEquals(TvReport.compact(input()).trimEnd(), q[IssueLinks.TV_REPORT_FIELD])
        assertEquals("report", IssueLinks.TV_REPORT_FIELD)
        assertTrue(url.length <= IssueLinks.MAX_URL, "${url.length}")
        assertFalse('+' in url || ' ' in url || '\n' in url)
        // Without a report, no report field: the form's placeholder asks for the paste.
        assertFalse("report=" in IssueLinks.tvSupport(input(), null).url)
    }

    @Test fun theBugReportLinkPrefillsVersionPhoneAndTv() {
        val log = "Clean Your TV 0.3.2, Android 36\n1792745323148 I setup: run paused STEP_FAILED at step 72"
        val report = TvReport.problem(about, input(), log)
        val link = IssueLinks.bugReport(about, input(), report)
        assertTrue(link.reportInLink)
        assertEquals(report.trimEnd(), query(link.url)[IssueLinks.PROBLEM_REPORT_FIELD])
        assertEquals("diagnostics", IssueLinks.PROBLEM_REPORT_FIELD)
        // A log too long for the link: the other fields stay, the report is pasted.
        val long = IssueLinks.bugReport(about, input(), TvReport.problem(about, input(), (1..300).joinToString("\n") { "1792745323148 I setup: line $it" }))
        assertFalse(long.reportInLink)
        assertFalse("diagnostics=" in long.url)
        assertTrue(long.url.length <= IssueLinks.MAX_URL)
        val q = query(IssueLinks.bugReport(about, input()).url)
        assertEquals("bug_report.yml", q["template"])
        assertEquals("0.3.2 (full)", q["version"])
        assertEquals("Samsung SM-S921B, Android 16", q["phone"])
        assertEquals("Hisense 55A6H, Android 11, RTM6.230109.155 (Google TV)", q["tv"])
        // 0.4.6: the title carries the TV's key, the device codename and Android in brackets.
        assertEquals("Problem: Hisense 55A6H (HiTV-MT9602, Android 11)", q["title"])
        assertNull(query(IssueLinks.bugReport(about, null).url)["tv"])
        assertEquals("Problem report", query(IssueLinks.bugReport(about, null).url)["title"])
    }

    // A long model name cannot grow the link past its cap.
    @Test fun everyLinkStaysShort() {
        val long = facts(model = "X".repeat(5_000))
        assertTrue(IssueLinks.tvSupport(input(snapshot(long))).url.length <= IssueLinks.MAX_URL)
        assertTrue(IssueLinks.bugReport(about.copy(phone = "P".repeat(5_000)), input(snapshot(long)), "x".repeat(20_000)).url.length <= IssueLinks.MAX_URL)
        // The short fields stay short: the cut is MAX_VALUE each.
        assertTrue(IssueLinks.tvSupport(input(snapshot(long)), null).url.length < 2_000)
    }

    @Test fun theHomeScreenTheTvCameWith() {
        fun with(vararg extra: String, f: DeviceFacts = facts()) = TvReport.platformOf(snapshot(f, extra.associate { PackageName(it) to pkg(EnabledState.ENABLED, true) }))
        assertEquals("Fire TV", with("com.amazon.tv.launcher"))
        assertEquals("Fire TV", with(f = facts(model = "AFTKA")))
        assertEquals("Google TV", with("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher"))
        assertEquals("Android TV (Leanback)", with("com.google.android.tvlauncher"))
        val vendorHome = HomeResolution(null, listOf(HomeClaimant(Component(PackageName("com.mitv.tvhome"), ".Home"), 1)))
        assertEquals("The maker's own launcher", TvReport.platformOf(snapshot(pkgs = emptyMap()).copy(home = vendorHome)))
        assertEquals("Not sure", TvReport.platformOf(snapshot(pkgs = emptyMap()).copy(home = HomeResolution(null, emptyList()))))
    }

    // B2: the TV summary without the package list, then the log as the app's redactor left it.
    @Test fun theProblemReportHasTheSummaryAndTheLog() {
        val log = "Clean Your TV 0.3.2, Android 36\n1792745323148 I setup: run paused STEP_FAILED at step 72"
        val text = TvReport.problem(about, input(), log)
        assertTrue(text.startsWith("Clean Your TV: problem report\nApp: 0.3.2 (full)\nPhone: Samsung SM-S921B, Android 16\n"), text)
        assertTrue("Model: 55A6H" in text && "Profile: brand set Hisense" in text && "Apps: 4 system, 2 other" in text)
        // Issue #375: what this app switched off is named (the TV's own apps); no other package is.
        assertTrue("Apps: 4 system, 2 other\n\nSwitched off by this app (1):\n${storeDemo.value}\n\nDiagnostics log" in text, text)
        assertFalse("com.hisense.smartvision" in text || "org.videolan.vlc" in text)
        assertTrue(text.endsWith("Diagnostics log (addresses, serial numbers, keys and codes removed)\n$log\n"), text)
        assertTrue("TV: none read yet" in TvReport.problem(about, null, log))
    }

    // Issue #375: "the Wi-Fi goes in and out" with no list of what was switched off, so no reply
    // could say what to put back. The problem report now names the TV's own apps this app switched
    // off and has not put back (the change records), and only counts the person's own apps.
    @Test fun issue375TheProblemReportNamesWhatThisAppSwitchedOff() {
        val vlc = PackageName("org.videolan.vlc")
        val off = setOf(storeDemo, PackageName("com.hisense.smartvision"), vlc)
        val text = TvReport.problem(about, input().copy(switchedOffByApp = off), "log")
        assertTrue("Switched off by this app (3):\ncom.hisense.smartvision, com.hisense.storedemo\nand 1 app you installed (not named)\n" in text, text)
        assertFalse(vlc.value in text)
        // Nothing switched off: no such section, as before.
        assertFalse("Switched off by this app" in TvReport.problem(about, input().copy(switchedOffByApp = emptySet()), "log"))
        // The triage reads the count from the same line as in the TV report.
        assertTrue(Regex("^Switched off by this app \\((\\d+)\\)", RegexOption.MULTILINE).containsMatchIn(text))
    }

    // The log can be megabytes; the report keeps its first line and the newest lines.
    @Test fun aLongLogKeepsItsHeadAndItsNewestLines() {
        val log = (listOf("Clean Your TV 0.3.2, Android 36") + (1..1_000).map { "line $it" }).joinToString("\n")
        val lines = ReportMachine.tail(log).lines()
        assertEquals("Clean Your TV 0.3.2, Android 36", lines[0])
        assertEquals("(700 older lines left out)", lines[1])
        assertEquals("line 701", lines[2])
        assertEquals("line 1000", lines.last())
        val wide = (listOf("head") + (1..300).map { "x".repeat(500) }).joinToString("\n")
        assertTrue(ReportMachine.tail(wide).length <= ReportMachine.MAX_LOG_CHARS + 40)
        assertEquals("short\nlog", ReportMachine.tail("short\nlog"))
    }

    @Test fun theHomeDecisionIsKeptBesideTheTv() = runTest {
        val store = HomeDecisions(MemoryDocumentStore())
        assertNull(store.load(tvId))
        for (d in listOf(HomeDecision(true), HomeDecision(false, "WIZARD_STAYS_ON"), HomeDecision(false, alreadyOn = true), HomeDecision(false))) {
            store.save(tvId, d)
            assertEquals(d, store.load(tvId))
        }
        assertEquals("home switch planned", HomeDecision.of(HomeBlock.NONE, planHasSwitch = true, alreadyOn = false).line)
        assertEquals("home switch blocked TV_REFUSES", HomeDecision.of(HomeBlock.TV_REFUSES, planHasSwitch = false, alreadyOn = false).line)
        assertEquals("home switch not in the plan", HomeDecision.of(HomeBlock.NONE, planHasSwitch = false, alreadyOn = false).line)
    }

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
