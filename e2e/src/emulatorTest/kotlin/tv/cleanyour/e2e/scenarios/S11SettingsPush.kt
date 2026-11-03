package tv.cleanyour.e2e.scenarios

import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.adb.session.AdbSessionManager
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.DocumentPlanStore
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ClockFormats
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.ScreensaverSettings
import tv.cleanyour.core.spec.TvSettings
import tv.cleanyour.core.spec.WidgetsSettings
import tv.cleanyour.e2e.E2eScenario
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import java.io.File
import java.util.concurrent.TimeUnit

// 0.5.0 Make it yours on a real adbd and the pinned launcher: a look with its wallpaper (Aurora), then the
// settings document with the Widgets, a clock screensaver and the TV's own keys (PlanRequest.ApplySettings).
// The second push leaves the wallpaper on the TV byte for byte, puts exactly the bytes the phone sent in
// config.json, and restarts the launcher to read them. Screenshots before and after (s11-*.png) are the visual
// check that the launcher reads the 3.3 keys (the 24 hour clock, the date under it, TV health, round buttons).
class S11SettingsPush : E2eScenario() {
    private val renderer = ShellRenderer()
    private val prober by lazy { AdbProber(deps.clock) }
    private val manager by lazy { AdbSessionManager(deps.sockets, deps.signer, tls = null, clock = deps.clock, scope = deps.scope) }
    private val planner by lazy { DefaultPlanner(deps.clock, deps.ids, renderer, FileOverlayTransport(prober), "e2e", profiles = BundledProfileSource()) }
    private val engine by lazy {
        DefaultEngine(renderer, prober, journal, snapshots, DocumentPlanStore(MemoryDocumentStore()), deps.clock, deps.scope, BundledProfileSource())
    }
    private val out: File get() = cfg.outDir.also { it.mkdirs() }

    @After fun tidy() {
        oracle.uninstall(LAUNCHER_PKG)
    }

    private fun fileBlob(name: String, bytes: ByteArray): BlobRef {
        val f = File(out, name).also { it.writeBytes(bytes) }
        val sha = Sha256(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return BlobRef(sha, bytes.size.toLong(), f.absolutePath)
    }

    private suspend fun plan(session: TvSession, request: PlanRequest): Plan {
        val base = prober.snapshot(session, SnapshotReason.BASELINE)
        snapshots.save(base)
        return when (val r = planner.plan(request, base, BundledFactsTable(), emptyList())) {
            is PlanResult.Ok -> r.plan
            is PlanResult.Refused -> throw AssertionError("planner refused: ${r.reasons}")
        }
    }

    // The screen as a PNG in the evidence folder, through the host adb.
    private fun screenshot(name: String) {
        oracle.shell("screencap -p /sdcard/$name.png")
        val p = ProcessBuilder(cfg.adb.path, "-s", cfg.serial, "pull", "/sdcard/$name.png", File(out, "$name.png").path).redirectErrorStream(true).start()
        p.waitFor(60, TimeUnit.SECONDS)
        oracle.shell("rm -f /sdcard/$name.png")
    }

    @Test fun theSettingsGoWithoutTouchingTheWallpaper() = e2e(timeoutMs = 600_000) {
        val apk = need(cfg.launcherApk, "launcher APK (CYT_E2E_LAUNCHER_APK)")
        // The package manager of a freshly booted image can refuse the first install; up to three tries, and say why.
        var r = oracle.install(apk)
        repeat(2) { if (!r.ok) { delay(10_000); r = oracle.install(apk) } }
        assertTrue("launcher install: ${r.stdout.takeLast(400)} ${r.stderr.takeLast(400)}", r.ok)
        oracle.shell("am start -W -n $LAUNCHER_PKG/.MainActivity")
        val session = open(manager)
        try {
            val aurora = Fixtures.upstreamThemes().first { it.name == "aurora" }
            val doc = (LauncherConfigCodec.decode(aurora.configJson) as DecodeResult.Ok).doc
            val wallpaper = fileBlob("s11-wallpaper.jpg", aurora.wallpaperJpg ?: throw AssertionError("aurora has no wallpaper"))
            val look = plan(session, PlanRequest.ApplyTheme(doc, fileBlob("s11-look.json", LauncherConfigCodec.encode(doc)), wallpaper))
            val first = runToEnd(engine, look, session, pass(session.tvId))
            assertTrue("look run ended $first", first is RunState.Finished && first.status == RunStatus.COMPLETED)
            val wallpaperPath = FileOverlayTransport.WALLPAPER.absolute
            val wallpaperSha = oracle.sha256(wallpaperPath)
            assertEquals(wallpaper.sha256.hex, wallpaperSha)
            delay(3_000)
            oracle.pressHome()
            delay(2_000)
            screenshot("s11-before")
            val pidBefore = oracle.shell("pidof $LAUNCHER_PKG").stdout.trim()

            // Make it yours: the Widgets and the screensaver on top of the TV's file, as MakeItYoursMachine sends them.
            val now = TvSettings.of(doc, null)
            val next = now.withWidgets(now.widgets.copy(clockFormat = ClockFormats.H24, showStats = true, dateBelowClock = true, greetingName = "Asha",
                iconOnlyButtons = true, iconOnlyInputs = true, topMenu = false, reorderButton = true))
                .withScreensaver(ScreensaverSettings(idleMinutes = 5, clockWallpaper = true))
            val bytes = next.encode()
            val settings = plan(session, PlanRequest.ApplySettings(next.pushDoc(), fileBlob("s11-settings.json", bytes)))
            assertTrue("no wallpaper step", settings.steps.none { (it.op as? Op.RemoveFile)?.path == FileOverlayTransport.WALLPAPER || (it.op as? Op.PushAsset)?.dest == FileOverlayTransport.WALLPAPER })
            val second = runToEnd(engine, settings, session, pass(session.tvId))
            assertTrue("settings run ended $second", second is RunState.Finished && second.status == RunStatus.COMPLETED)

            assertEquals("config.json is what the phone sent", Sha256(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }).hex, oracle.sha256(FileOverlayTransport.CONFIG.absolute))
            assertEquals("the wallpaper stays", wallpaperSha, oracle.sha256(wallpaperPath))
            delay(3_000)
            val pidAfter = oracle.shell("pidof $LAUNCHER_PKG").stdout.trim()
            assertTrue("launcher not running after the push", pidAfter.isNotEmpty())
            assertNotEquals("launcher was not restarted", pidBefore, pidAfter)
            oracle.pressHome()
            delay(3_000)
            screenshot("s11-after")
            File(out, "s11-settings.txt").writeText(bytes.decodeToString())
        } finally {
            session.close()
        }
    }
}
