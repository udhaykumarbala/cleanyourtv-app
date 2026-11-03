package tv.cleanyour.core.probe

import org.junit.Test
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    private fun seq(s: String) = s.lineSequence()
    private fun p(s: String) = PackageName(s)
    private fun c(s: String) = Component(p(s.substringBefore('/')), s.substringAfter('/'))

    @Test fun goldenScripts() {
        val golden = ProbeFixtures.res("golden-scripts.txt").lines().filter { it.isNotEmpty() }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        val actual = ProbeScripts.all.mapValues { it.value.text } + mapOf(
            "fileSha256(OVERLAY/config.json)" to ProbeScripts.fileSha256(ProbeScripts.overlayConfig).text,
            "installedSha256(com.gathin.tvhome)" to ProbeScripts.installedSha256(p("com.gathin.tvhome")).text,
            "watchdogAlive(0123456789abcdef)" to ProbeScripts.watchdogAlive(tv.cleanyour.core.model.RunToken("0123456789abcdef")).text,
            "copyApk(com.gathin.tvhome, signer-0123456789abcdef.apk, 16777216)" to
                ProbeScripts.copyApk(p("com.gathin.tvhome"), ProbeScripts.signerPath("0123456789abcdef"), 16L * 1024 * 1024).text,
            "signerCleanup(signer-0123456789abcdef.apk)" to ProbeScripts.signerCleanup(ProbeScripts.signerPath("0123456789abcdef")).text,
            "installedApkState(com.gathin.tvhome)" to ProbeScripts.installedApkState(p("com.gathin.tvhome")).text,
        )
        assertEquals(golden, actual)
        // Printable ASCII only, and no write verb outside the lines that must write (all of them in
        // the app's TMP folder, or the no-op package).
        val writers = setOf("SCREENSHOT", "SCREENSHOT_CLEANUP", "MKDIR_TMP", "NOOP_INSTALL", "NOOP_UNINSTALL", "NOOP_CLEANUP",
            "copyApk(com.gathin.tvhome, signer-0123456789abcdef.apk, 16777216)", "signerCleanup(signer-0123456789abcdef.apk)")
        for ((k, v) in actual) {
            assertTrue(v.all { it in ' '..'~' }, k)
            if (k !in writers) assertFalse(Regex("\\b(disable|enable|install|uninstall|rm|put|delete|setprop|reboot|am|input|svc|mkdir|cp|mv|dd)\\b").containsMatchIn(v.replace("--brief", "")), "$k writes: $v")
        }
    }

    @Test fun getprop() {
        val props = GetpropParser.parse(seq(Fixtures.miTv().getpropText))
        assertEquals("MiTV-AXSO0", props["ro.product.model"])
        assertEquals("", GetpropParser.parse(seq("[a.b]: []\nnot a prop line\n"))["a.b"])
        assertFailsWith<ProbeException> { GetpropParser.parse(seq("[a]: [" + "x".repeat(LINE_CAP) + "]")) }
    }

    @Test fun packages() {
        val all = PackagesParser.parse(seq(Fixtures.miTv().packagesAllText))
        assertEquals(107, all.size)
        assertEquals(PackagesParser.Listed("com.droidlogic.tvinput", "/product/app/DroidLogicTvInput/DroidLogicTvInput.apk"), all.first())
        assertTrue(all.any { it.name == "android" })
        // A path with '=' in it (base64 app dirs) keeps the last '=' as the separator.
        assertEquals("com.x.y", PackagesParser.parse(seq("package:/data/app/com.x.y-AbC==/base.apk=com.x.y")).single().name)
        assertEquals(listOf("com.a.b"), PackagesParser.parse(seq("package:com.a.b\npackage:com.a.b\n\n")).map { it.name })
        assertFailsWith<ProbeException> { PackagesParser.parse(seq("Error: something")) }
        assertFailsWith<ProbeException> { PackagesParser.parse(seq("package:com.a.b; rm -rf /")) }
    }

    @Test fun dumpsysPackage() {
        val info = DumpsysPackageParser.parse(seq(ProbeFixtures.grepped(ProbeFixtures.res("mitv/dumpsys-package-packages.txt"))))
        assertEquals(107, info.size)
        val gms = info.getValue("com.google.android.gms")
        assertEquals("/data/app/com.google.android.gms-EGloYBvisAN99YFUFQ9-Hg==", gms.codePath)
        assertTrue("UPDATED_SYSTEM_APP" in gms.flags && gms.versionCode != 12345L, "hidden system copy must not win")
        assertEquals(3, info.getValue("com.mitv.tvhome.atv").enabled)
        // The whole, ungrepped dump parses the same.
        assertEquals(info, DumpsysPackageParser.parse(seq(ProbeFixtures.res("mitv/dumpsys-package-packages.txt"))))
        // Android 11+ user line and long version codes.
        val newer = DumpsysPackageParser.parse(seq("Packages:\n  Package [com.a.b] (1f2e3d):\n    versionCode=1234567890123 minSdk=30 targetSdk=34\n    pkgFlags=[ SYSTEM PERSISTENT ]\n    User 0: ceDataInode=1 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=2 instant=false virtual=false\n    User 10: ceDataInode=2 installed=false enabled=0\n"))
        assertEquals(DumpsysPackageParser.Info("com.a.b", null, 1234567890123, setOf("SYSTEM", "PERSISTENT"), true, 2), newer.getValue("com.a.b"))
        assertEquals(emptyMap(), DumpsysPackageParser.parse(seq("    pkgFlags=[ SYSTEM ]\n" + "x".repeat(LINE_CAP + 1))))
    }

    @Test fun home() {
        val claimants = HomeParser.parse(seq(ProbeFixtures.res("mitv/query-home.txt")))
        assertEquals(HomeClaimant(c("com.google.android.tvlauncher/.MainActivity"), 2), claimants.first())
        assertEquals(c("com.google.android.tvlauncher/.MainActivity"), HomeParser.parseResolved(seq(Fixtures.miTv().homeActivityText)))
        val gtv = HomeParser.parse(seq(ProbeFixtures.res("gtv34/query-home.txt")))
        assertEquals(listOf(4, 1), gtv.filter { it.component.pkg.value == "com.google.android.tungsten.setupwraith" }.map { it.priority })
        assertEquals(c("com.google.android.apps.tv.launcherx/.home.HomeActivity"), HomeParser.parseResolved(seq(ProbeFixtures.res("gtv34/resolve-home.txt"))))
        assertNull(HomeParser.parseResolved(seq("No activity found")))
        assertEquals(emptyList(), HomeParser.parse(seq("No activities found")))
        // A component with shell characters is not a component.
        assertEquals(emptyList(), HomeParser.parse(seq("priority=1 preferredOrder=0\ncom.evil/.A;reboot")))
    }

    @Test fun hdmiControl() {
        val mi = HdmiControlParser.parse(seq(ProbeFixtures.res("mitv/hdmi_control.txt")))
        assertFalse(mi.serviceMissing)
        assertEquals(mapOf(2 to listOf("Set-top Box"), 3 to listOf("Soundbar", "")), mi.namesByPort())
        assertEquals(3, mi.ports.size)
        assertEquals(true, mi.ports.first { it.id == 3 }.arc)
        assertTrue(mi.devices.first { it.physicalAddress == 0x3100 }.behindSwitch)
        val gtv = HdmiControlParser.parse(seq(ProbeFixtures.res("gtv34/hdmi_control.txt")))
        assertEquals(mapOf(1 to listOf("Apple TV")), gtv.namesByPort())
        assertEquals(listOf(1, 2), gtv.ports.map { it.id })
        assertEquals(0x2000, gtv.ports[1].physicalAddress)
        assertTrue(HdmiControlParser.parse(seq("Can't find service: hdmi_control")).serviceMissing)
        assertTrue(HdmiControlParser.parse(seq("")).serviceMissing)
        // Port from the physical address when port_id is 0; names that are not CEC OSD names are blanked.
        val odd = HdmiControlParser.parse(seq("CEC: logical_address: 0x04 device_type: 0x04 vendor_id: 0x0 display_name: \u001b[2Jhello world 123456 power_status: 0x00 physical_address: 0x4000 port_id: 0"))
        assertEquals(4, odd.devices.single().port)
        assertEquals("", odd.devices.single().name)
    }

    // R5: hostile shapes that used to cost seconds to minutes of backtracking or string copies.
    @Test fun hostileShapesStayFast() {
        val portBomb = ("HdmiPortInfo{id=1,address=0x1 cec=true ".repeat(LINE_CAP / 40) + "\n").repeat(20)
        val t0 = System.nanoTime()
        assertEquals(emptyList(), HdmiControlParser.parse(seq("HdmiControlService:\n" + portBomb)).ports)
        val settingsBomb = "a=1\n" + "x\n".repeat(1_000_000) + "b=2\n"
        val parsed = SettingsListParser.parse(seq(settingsBomb))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
        assertTrue(parsed.getValue("a")!!.length <= SettingsListParser.VALUE_CAP)
        assertEquals("2", parsed["b"])
        // A port record still needs all of address, cec and arc.
        assertEquals(listOf(HdmiControlParser.Port(4, 0x4000, false, true)), HdmiControlParser.parse(seq("x\n  HdmiPortInfo{id=4, type=INPUT, address=0x4000, cec=false, arc=true, eArc=false}")).ports)
        assertEquals(emptyList(), HdmiControlParser.parse(seq("x\n  HdmiPortInfo{id=4, type=INPUT, address=0x4000, cec=false}")).ports)
    }

    @Test fun tvInputs() {
        val inputs = TvInputParser.parse(seq(ProbeFixtures.res("mitv/tv_input.txt")))
        assertEquals(5, inputs.size)
        assertEquals(mapOf(1 to "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5", 2 to "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6", 3 to "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7"),
            inputs.filter { it.hdmiPort != null }.associate { it.hdmiPort!! to it.id })
        assertTrue(inputs.all { it.pkg == p("com.droidlogic.tvinput") })
        // No hardware section: the port comes from the service name.
        assertEquals(2, TvInputParser.parse(seq("  x: TvInputInfo{id=com.v.tv/.Hdmi2Service/HW9, pkg=com.v.tv, service=com.v.tv.Hdmi2Service}")).single().hdmiPort)
        assertEquals(emptyList(), TvInputParser.parse(seq("Can't find service: tv_input")))
    }

    @Test fun settingsList() {
        val secure = SettingsListParser.parse(seq(Fixtures.miTv().settingsSecureText))
        assertEquals("com.google.android.backdrop/.Backdrop", secure["screensaver_components"])
        assertNull(secure["accessibility_display_inversion_enabled"])
        assertEquals(mapOf("a" to "1\nmore", "b" to null), SettingsListParser.parse(seq("a=1\nmore\nb=null\n" + "c=" + "x".repeat(LINE_CAP))))
    }

    @Test fun resumedActivity() {
        assertEquals(c("com.google.android.tvlauncher/.MainActivity"), ResumedActivityParser.parse(seq(ProbeFixtures.res("mitv/activities.txt"))))
        assertEquals(c("com.gathin.tvhome/.MainActivity"), ResumedActivityParser.parse(seq(ProbeFixtures.res("gtv34/activities.txt"))))
        assertNull(ResumedActivityParser.parse(seq("")))
    }

    // Parser fuzz (ARCHITECTURE 6.3): random, truncated and mixed dumps never throw anything but
    // ProbeException.
    @Test fun parsersOnlyEverThrowProbeException() {
        val sources = listOf(
            Fixtures.miTv().getpropText, Fixtures.miTv().packagesAllText, Fixtures.miTv().settingsSecureText, ProbeFixtures.res("mitv/tv_input.txt"),
            ProbeFixtures.res("mitv/hdmi_control.txt"), ProbeFixtures.res("mitv/query-home.txt"), ProbeFixtures.res("mitv/dumpsys-package-packages.txt"),
        ).joinToString("\n")
        val r = Random(7)
        val parsers: List<(Sequence<String>) -> Any?> = listOf(
            GetpropParser::parse, PackagesParser::parse, DumpsysPackageParser::parse, HomeParser::parse, HomeParser::parseResolved,
            HdmiControlParser::parse, TvInputParser::parse, SettingsListParser::parse, ResumedActivityParser::parse,
        )
        repeat(2_000) {
            val start = r.nextInt(sources.length)
            var chunk = sources.substring(start, minOf(sources.length, start + r.nextInt(4000)))
            if (r.nextInt(4) == 0) chunk = chunk.map { ch -> if (r.nextInt(30) == 0) (r.nextInt(0, 0xFFFF)).toChar() else ch }.joinToString("")
            if (r.nextInt(10) == 0) chunk += "x".repeat(LINE_CAP + 1)
            for (parse in parsers) try { parse(chunk.lineSequence()) } catch (_: ProbeException) { }
        }
    }
}
