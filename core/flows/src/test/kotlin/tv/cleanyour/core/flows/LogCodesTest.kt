package tv.cleanyour.core.flows

import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.probe.ProbeException
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 0.3.2 B3: the diagnostics log names errors by stable codes, never by class name (R8 renamed
// AdbError.NonZeroExit to "f3" in issue #2's log), and never by an error's own text.
class LogCodesTest {
    private val pkg = PackageName("com.amazon.tv.launcher")
    private val sha = Sha256("a".repeat(64))

    @Test fun everyErrorHasAStableCode() {
        val codes = mapOf(
            AdbError.Transport.Unreachable to "unreachable",
            AdbError.Transport.Refused to "refused",
            AdbError.Transport.Timeout("boot") to "timeout(boot)",
            AdbError.Transport.Timeout("192.168.1.20") to "timeout",
            AdbError.Transport.Closed to "lost",
            AdbError.Transport.Protocol("bad A_CNXN from 192.168.1.20") to "protocol",
            AdbError.Transport.NoShellV2 to "no_shell_v2",
            AdbError.Auth.Denied to "auth_denied",
            AdbError.Auth.PendingTooLong to "auth_pending",
            AdbError.Auth.TrustChanged(sha, null) to "trust_changed",
            AdbError.Device.InstallFailed("INSTALL_FAILED_UPDATE_INCOMPATIBLE") to "install_failed(INSTALL_FAILED_UPDATE_INCOMPATIBLE)",
            AdbError.Device.InstallFailed("Failure [weird text]") to "install_failed",
            AdbError.Device.NotEnoughSpace to "no_space",
            AdbError.Device.InstallRefusedByFirmware to "install_refused",
            AdbError.Device.Busy to "busy",
            AdbError.NonZeroExit(1, "Error: java.lang.SecurityException") to "nonzero_exit(1)",
            AdbError.NonZeroExit(255, "") to "nonzero_exit(255)",
            AdbError.Policy.ProtectedPackage(pkg) to "protected",
            AdbError.Policy.GuardFailed(3, "reason") to "guard_failed",
            AdbError.Policy.Stale(listOf("a")) to "stale",
            AdbError.Policy.WrongTv(TvId("0123456789abcdef"), TvId("fedcba9876543210")) to "wrong_tv",
        )
        for ((e, code) in codes) assertEquals(code, LogCodes.of(e), e.toString())
        assertEquals("nonzero_exit(1)", LogCodes.of(AdbException(AdbError.NonZeroExit(1, "x"))))
        assertEquals("probe_failed(packages)", LogCodes.of(ProbeException(ProbeStage.PACKAGES, "exit 1")))
        assertEquals("unexpected", LogCodes.of(IllegalStateException("192.168.1.20")))
    }

    // Issues #788 and #1112: a probe failure's line carries the prober's fixed detail, never what the
    // TV printed after it, at most PROBE_DETAIL_MAX characters.
    @Test fun aProbeFailureNamesItsDetail() {
        assertEquals("probe_failed(facts): getprop has no ro.product.manufacturer",
            LogCodes.withDetail(ProbeException(ProbeStage.FACTS, "getprop has no ro.product.manufacturer")))
        // The scrubber masks any six-digit number as a code, as it does in every TV text.
        assertEquals("probe_failed(packages): output over [code] bytes", LogCodes.withDetail(ProbeException(ProbeStage.PACKAGES, "output over 524288 bytes")))
        assertEquals("probe_failed(packages): exit 1", LogCodes.withDetail(ProbeException(ProbeStage.PACKAGES, "exit 1: denied for 192.168.1.20")))
        assertEquals("probe_failed(packages): unexpected line", LogCodes.withDetail(ProbeException(ProbeStage.PACKAGES, "unexpected line: serial 0123456789ABCDEF")))
        val long = LogCodes.probeDetail(ProbeException(ProbeStage.FACTS, "the script stopped before " + "gms and ".repeat(40)))!!
        assertEquals(LogCodes.PROBE_DETAIL_MAX, long.length)
        assertEquals("nonzero_exit(1)", LogCodes.withDetail(AdbException(AdbError.NonZeroExit(1, "x"))))
        assertEquals("unexpected", LogCodes.withDetail(IllegalStateException("192.168.1.20")))
    }

    @Test fun opsAreNamedByKindAndTarget() {
        val home = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
        val token = RunToken("00000000000000aa")
        assertEquals("Disable com.amazon.tv.launcher", LogCodes.op(Op.Disable(pkg)))
        assertEquals("Enable com.amazon.tv.launcher", LogCodes.op(Op.Enable(pkg)))
        assertEquals("SetHome com.gathin.tvhome/.MainActivity", LogCodes.op(Op.SetHome(home)))
        assertEquals("VerifyHome com.gathin.tvhome/.MainActivity", LogCodes.op(Op.VerifyHome(home)))
        assertEquals("SettingsPut screensaver_enabled", LogCodes.op(Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0")))
        assertEquals("Install com.gathin.tvhome", LogCodes.op(Op.InstallStream(BlobRef(sha, 1, "bundled:x.apk"), sha, home.pkg, 1, sha)))
        assertEquals("PushAsset config.json", LogCodes.op(Op.PushAsset(BlobRef(sha, 1, "x"), RemotePath(RemoteDir.OVERLAY, listOf("config.json")), sha)))
        assertEquals("Read package_dump", LogCodes.op(Op.Read(ProbeKind.PACKAGE_DUMP)))
        // A run token is never logged.
        assertFalse(token.value in LogCodes.op(Op.ArmHomeWatchdog(home, 120, token)))
        assertFalse(token.value in LogCodes.op(Op.ConfirmHome(token)))
    }

    @Test fun theProbeLineNamesHowTheProfileResolvedAndTheTv() {
        assertEquals("none", LogCodes.profile(ProfileResolution.None(emptyList())))
        assertEquals("none", LogCodes.profile(null))
        assertEquals("brand set Hisense", LogCodes.profile(ProfileResolution.BrandSureSet("Hisense", emptyList(), emptyList())))
        assertEquals("exact xiaomi-mitv-axso0", LogCodes.profile(ProfileResolution.Exact(miProfile)))
        val f = Samples.miTvFacts()
        assertEquals("tv ${f.brand} ${f.model} (${f.device}) sdk ${f.sdk}", LogCodes.tv(f))
        assertFalse(f.serialLast4 in LogCodes.tv(f))
    }

    // A TV's stderr is one line, short, with addresses and ids masked.
    @Test fun stderrIsOneShortCleanLine() {
        val out = LogCodes.clean("Exception occurred while executing 'disable-user':\n\tjava.lang.SecurityException: from 192.168.1.20 " +
            "mac aa:bb:cc:dd:ee:ff id 0123456789abcdef serialno=G070VM12345 " + "z".repeat(400))
        assertFalse('\n' in out || '\t' in out)
        assertTrue(out.startsWith("Exception occurred while executing 'disable-user': java.lang.SecurityException: from [address] mac [mac] id [id] serialno=[serial]"), out)
        assertTrue(out.length <= 160)
    }

    // 0.3.3 D3 (issue #10): the report's own scrub keeps build strings whole ("V816.0.11.0.UZFAABX"
    // became "V816.[address].1.UZFAABX" in 0.3.2) and still masks real addresses.
    @Test fun buildStringsStayWholeAndAddressesGo() {
        val fingerprints = listOf(
            "Xiaomi/jaws/jaws:14/UKG3.250107.001/V816.0.11.0.UZFAABX:user/release-keys",          // issue #10
            "AURZEN/AURZEN_BOOMAIR/himalaya:11/RTMA.250416.043/13549864:user/release-keys",       // issue #9
            "Haier/nippori/nippori:11/RTO5.250812.001/13924387:user/release-keys",                // issue #11
            "Philips/PH0M_LT_T32/PH0M_EA_T32:11/RTM2.210929.098/200321:user/release-keys",       // issue #13
            "Smarttech/SMARTTECH_4K_ATV3/marina:11/RTMA.250416.028/13485761:user/release-keys",   // issue #15
        )
        // The TV report's Build line scrubs without the six digit code rule (a build number is not a code).
        for (f in fingerprints) assertEquals(f, Scrub.text(f, codes = false))
        for (s in listOf("V816.0.11.0.UZFAABX", "UKG3.250107.001", "1.2.3.4.5", "256.1.1.1")) assertEquals(s, LogCodes.clean(s))
        assertEquals("[address]", LogCodes.clean("192.168.0.195"))
        assertEquals("[address]:5555", LogCodes.clean("10.0.0.5:5555"))
        assertEquals("addr [address],", LogCodes.clean("addr 172.16.4.2,"))
        assertEquals("from [address].", LogCodes.clean("from 192.168.1.20."))
    }
}
