package tv.cleanyour.core.probe

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HdmiSource
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Recording
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdbProberTest {
    private fun p(s: String) = PackageName(s)
    private fun c(s: String) = Component(p(s.substringBefore('/')), s.substringAfter('/'))
    private val prober = AdbProber(ProbeFixtures.clock())

    @Test fun factsFromTheReferenceTvEqualTheCaptureFacts() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        assertEquals(Fixtures.miTv().facts(), prober.facts(s))
        assertEquals(1, s.execLines.size)
    }

    @Test fun fullSnapshotOfTheReferenceTv() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        val stages = mutableListOf<ProbeStage>()
        val snap = prober.snapshot(s, SnapshotReason.BASELINE) { stages += it }
        assertEquals(ProbeStage.entries.toList(), stages.toList())

        // F3: at most 10 shell calls (7), one pull, and nothing written.
        assertEquals(7, s.execLines.size, s.execLines.joinToString("\n"))
        val reads = setOf(ProbeScripts.FACTS, ProbeScripts.PACKAGES, ProbeScripts.DISABLED, ProbeScripts.PACKAGE_FLAGS, ProbeScripts.HOME, ProbeScripts.INPUTS, ProbeScripts.SETTINGS).map { it.text }
        assertEquals(reads.toSet(), s.execLines.toSet())
        assertTrue(s.calls.none { it is FakeTvSession.Call.Push || it is FakeTvSession.Call.InstallStream || it is FakeTvSession.Call.Reboot })
        assertEquals(1, s.calls.count { it is FakeTvSession.Call.Pull })

        assertEquals(Fixtures.miTv().tvId(), snap.tvId)
        assertEquals(SnapshotReason.BASELINE, snap.reason)
        assertEquals(Fixtures.miTv().facts(), snap.facts)
        // Every listed package, the framework's "android" included (RFC-W1-2), with the capture's disabled set.
        assertEquals(107, snap.packages.size)
        assertTrue(snap.packages.getValue(p("android")).let { it.system && it.persistent })
        val disabled = Fixtures.miTv().disabledAfter.map(::p).toSet()
        assertEquals(disabled, snap.packages.filterValues { it.enabled == EnabledState.DISABLED_USER }.keys)
        assertTrue(snap.packages.filterKeys { it !in disabled }.values.all { it.enabled == EnabledState.DEFAULT })
        val tvInput = snap.packages.getValue(p("com.droidlogic.tvinput"))
        assertTrue(tvInput.tvInput && tvInput.persistent && tvInput.system)
        assertEquals(setOf(p("com.droidlogic.tvinput")), snap.packages.filterValues { it.tvInput }.keys)
        assertTrue(snap.packages.getValue(p("com.android.systemui")).persistent)
        assertTrue(snap.packages.getValue(p("com.google.android.gms")).system, "updated system app keeps SYSTEM")
        assertFalse(snap.packages.getValue(p("in.startv.hotstar")).system)
        assertTrue(snap.packages.values.all { it.versionCode > 0 && it.signer == null && !it.installedByUs })

        assertEquals(c("com.google.android.tvlauncher/.MainActivity"), snap.home.resolved)
        assertEquals(
            listOf(HomeClaimant(c("com.google.android.tvlauncher/.MainActivity"), 2), HomeClaimant(c("com.google.android.leanbacklauncher/.MainActivity"), 1),
                HomeClaimant(c("com.android.tv.settings/.system.FallbackHome"), -1000)),
            snap.home.claimants,
        )
        assertEquals("1", snap.settings[SettingKey.SCREENSAVER_ENABLED])
        assertEquals("com.google.android.backdrop/.Backdrop", snap.settings[SettingKey.SCREENSAVER_COMPONENTS])
        assertEquals("0", snap.settings[SettingKey.ACCESSIBILITY_ENABLED])
        assertEquals("1", snap.settings[SettingKey.ADB_ENABLED])
        assertEquals("TV", snap.settings[SettingKey.DEVICE_NAME])
        assertEquals(null, snap.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals(SettingKey.entries.toSet(), snap.settings.keys)
        assertEquals(c("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"), snap.ime)
        assertEquals(listOf(c("com.google.android.backdrop/.Backdrop")), snap.screensaver)
        assertContentEquals(ProbeFixtures.overlayConfig, snap.launcherOverlay)
        assertEquals(
            listOf(
                HdmiSource(1, null, "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5", false),
                HdmiSource(2, "Set-top Box", "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6", false),
                HdmiSource(3, "Soundbar", "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7", true),
            ),
            snap.hdmi,
        )
        assertEquals(SnapshotCodec.hash(snap), snap.contentHash)
        assertEquals(snap.contentHash, SnapshotCodec.decode(SnapshotCodec.canonical(snap)).contentHash)
    }

    @Test fun noOverlayFileIsNull() = runBlocking<Unit> {
        val snap = prober.snapshot(ProbeFixtures.miTvSession(overlay = false), SnapshotReason.PRE_PLAN)
        assertNull(snap.launcherOverlay)
    }

    @Test fun homeFocusAndTheSmallReads() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        assertEquals(c("com.google.android.tvlauncher/.MainActivity"), prober.homeFocus(s))
        assertEquals(1241, prober.freeDataMb(s))
        assertEquals("3b1f6c2e-0d4a-4f7e-9a51-6c0e2b7d9f10", prober.bootId(s))
        val hash = "ab".repeat(32)
        val path = RemotePath(RemoteDir.TMP, listOf("x.apk"))
        s.recording.on(ProbeScripts.fileSha256(path).text, "$hash  ${path.absolute}\n")
        assertEquals(Sha256(hash), prober.fileSha256(s, path))
        s.recording.on(ProbeScripts.installedSha256(p("com.gathin.tvhome")).text, "${hash.uppercase()}  /data/app/x/base.apk\n")
        assertEquals(Sha256(hash), prober.installedSha256(s, p("com.gathin.tvhome")))
        assertNull(prober.fileSha256(s, RemotePath(RemoteDir.TMP, listOf("missing.apk"))))   // unrecorded: exit 127
    }

    @Test fun watchdogAliveWithAndWithoutPgrep() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        val t = RunToken("0123456789abcdef")
        s.recording.on(ProbeScripts.watchdogAlive(t).text, "4242\n")
        assertTrue(prober.watchdogAlive(s, t))
        s.recording.on(ProbeScripts.watchdogAlive(t).text, "", exit = 1)
        assertFalse(prober.watchdogAlive(s, t))
        val t2 = RunToken("fedcba9876543210")
        s.recording.on(ProbeScripts.PS_ARGS.text, "ARGS\nsh -c sleep 120; [ -f x ] cleanyourtv-rollback-fedcba9876543210\n")
        assertTrue(prober.watchdogAlive(s, t2))   // pgrep unrecorded: 127, so ps
    }

    // Finding 3: the dock sheet offers only apps a launcher would show; one read-only call.
    @Test fun launchableAppsComeFromBothLauncherCategoriesInOneCall() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        s.recording.on(ProbeScripts.LAUNCHABLE.text, """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                com.netflix.ninja/.MainActivity
              Activity #1:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
            @@cyt@@ launcher
            1 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                org.videolan.vlc/.StartActivity
        """.trimIndent() + "\n")
        assertEquals(setOf(p("com.netflix.ninja"), p("com.google.android.youtube.tv"), p("org.videolan.vlc")), prober.launchable(s))
        assertEquals(listOf(ProbeScripts.LAUNCHABLE.text), s.execLines)
        s.recording.on(ProbeScripts.LAUNCHABLE.text, "No activities found\n")
        assertFailsWith<ProbeException> { prober.launchable(s) }   // the script stopped early: the caller falls back
    }

    @Test fun screenshotIsPulledCheckedAndCleanedUp() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        s.recording.on(ProbeScripts.SCREENSHOT.text, "")
        assertNull(prober.screenshot(s))                              // no file pulled
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        s.files[ProbeScripts.screenPath.absolute] = png
        assertContentEquals(png, prober.screenshot(s))
        assertTrue(ProbeScripts.SCREENSHOT_CLEANUP.text in s.execLines)
        // Device test 2026-10-20: the cleanup removes the file only. A thumbnail between Mkdir(TMP) and
        // ArmHomeWatchdog must never take away the directory the watchdog writes its log into.
        assertEquals("rm -f ${ProbeScripts.screenPath.absolute}", ProbeScripts.SCREENSHOT_CLEANUP.text)
        assertTrue(ProbeScripts.all.values.none { "rmdir" in it.text || "rm -r" in it.text }, "no probe script removes a directory")
        s.files[ProbeScripts.screenPath.absolute] = "not a png".encodeToByteArray()
        assertNull(prober.screenshot(s))
    }

    @Test fun installRefusal() = runBlocking<Unit> {
        val blob = BlobRef(Sha256("cd".repeat(32)), 3, "bundled:noop.apk")
        val s = FakeTvSession(recording = ProbeFixtures.miTvRecording(), blobs = { byteArrayOf(1, 2, 3) })
        s.recording.on(ProbeScripts.MKDIR_TMP.text, "")
        s.recording.on(ProbeScripts.NOOP_INSTALL.text, "Success\n")
        s.recording.on(ProbeScripts.NOOP_UNINSTALL.text, "Success\n")
        s.recording.on(ProbeScripts.NOOP_CLEANUP.text, "")
        assertFalse(prober.installRefusal(s, blob))
        assertEquals(listOf(ProbeScripts.MKDIR_TMP, ProbeScripts.NOOP_INSTALL, ProbeScripts.NOOP_UNINSTALL, ProbeScripts.NOOP_CLEANUP).map { it.text }, s.execLines)
        s.recording.on(ProbeScripts.NOOP_INSTALL.text, "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]\n", exit = 1)
        assertTrue(prober.installRefusal(s, blob))
        s.recording.on(ProbeScripts.NOOP_INSTALL.text, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n", exit = 1)
        val e = assertFailsWith<AdbException> { prober.installRefusal(s, blob) }
        assertEquals(AdbError.Device.NotEnoughSpace, e.error)
        assertEquals(ProbeScripts.NOOP_CLEANUP.text, s.execLines.last())
    }

    // R5: a timeout after `pm install` still uninstalls the no-op package and removes the APK.
    @Test fun installRefusalCleansUpAfterATimeout() = runBlocking<Unit> {
        val blob = BlobRef(Sha256("cd".repeat(32)), 3, "bundled:noop.apk")
        val r = Recording().on(ProbeScripts.MKDIR_TMP.text, "").on(ProbeScripts.NOOP_UNINSTALL.text, "Success\n").on(ProbeScripts.NOOP_CLEANUP.text, "")
            .on(Regex("pm install .*")) { throw AdbException(AdbError.Transport.Timeout("exec-total")) }
        val s = FakeTvSession(recording = r, blobs = { byteArrayOf(1, 2, 3) })
        assertFailsWith<AdbException> { prober.installRefusal(s, blob) }
        assertEquals(listOf(ProbeScripts.MKDIR_TMP, ProbeScripts.NOOP_INSTALL, ProbeScripts.NOOP_UNINSTALL, ProbeScripts.NOOP_CLEANUP).map { it.text }, s.execLines)
    }

    @Test fun transportFailuresSurfaceAsAdbException() = runBlocking<Unit> {
        val s = ProbeFixtures.miTvSession()
        s.failNext = AdbError.Transport.Timeout("exec-total")
        val e = assertFailsWith<AdbException> { prober.snapshot(s, SnapshotReason.MANUAL) }
        assertEquals(AdbError.Transport.Timeout("exec-total"), e.error)
    }

    @Test fun hostileOutputsAreTypedErrors() = runBlocking<Unit> {
        // A script whose output lost a section, a forged or repeated marker, an oversize answer.
        val cut = ProbeFixtures.miTvRecording().on(ProbeScripts.INPUTS.text, ProbeFixtures.res("mitv/tv_input.txt"))
        assertEquals(ProbeStage.INPUTS, assertFailsWith<ProbeException> { prober.snapshot(FakeTvSession(recording = cut), SnapshotReason.MANUAL) }.stage)
        val forged = ProbeFixtures.miTvRecording().on(ProbeScripts.SETTINGS.text, "a=1\n@@cyt@@ global\nb=2\n@@cyt@@ global\n")
        assertFailsWith<ProbeException> { prober.snapshot(FakeTvSession(recording = forged), SnapshotReason.MANUAL) }
        val unknownMark = ProbeFixtures.miTvRecording().on(ProbeScripts.FACTS.text, "@@cyt@@ evil\n")
        // The marker's name is the TV's text: the detail, which the log line carries, leaves it out.
        assertEquals("unexpected section marker", assertFailsWith<ProbeException> { prober.facts(FakeTvSession(recording = unknownMark)) }.detail)
        val giant = ProbeFixtures.miTvRecording().on(ProbeScripts.PACKAGES.text, "package:" + "a".repeat(9000) + ".b\n")
        assertFailsWith<ProbeException> { prober.snapshot(FakeTvSession(recording = giant), SnapshotReason.MANUAL) }
        val huge = ProbeFixtures.miTvRecording()
        huge.on(ProbeScripts.FACTS.text, "[a]: [" + "x".repeat(600 * 1024) + "]\n")
        assertFailsWith<ProbeException> { prober.facts(FakeTvSession(recording = huge)) }
        val noModel = Recording().on(ProbeScripts.FACTS.text, "[ro.product.brand]: [X]\n@@cyt@@ meminfo\nMemTotal: 1 kB\n@@cyt@@ gms\n")
        assertFailsWith<ProbeException> { prober.facts(FakeTvSession(recording = noModel)) }
        val badBoot = ProbeFixtures.miTvRecording().on(ProbeScripts.BOOT_ID.text, "\$(reboot)\n")
        assertFailsWith<ProbeException> { prober.bootId(FakeTvSession(recording = badBoot)) }
    }

    @Test fun homeFallsBackWhenTheChainedCommandStops() = runBlocking<Unit> {
        val r = ProbeFixtures.miTvRecording()
            .on(ProbeScripts.HOME.text, ProbeFixtures.res("mitv/query-home.txt"))
            .on(ProbeScripts.HOME_RESOLVED.text, Fixtures.miTv().homeActivityText)
        val s = FakeTvSession(recording = r)
        val home = prober.homeNow(s)
        assertEquals(c("com.google.android.tvlauncher/.MainActivity"), home.resolved)
        assertEquals(3, home.claimants.size)
        assertEquals(2, s.execLines.size)
    }
}
