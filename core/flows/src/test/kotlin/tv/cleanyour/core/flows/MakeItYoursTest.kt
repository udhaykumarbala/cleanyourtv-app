package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.AppsSettings
import tv.cleanyour.core.spec.ClockFormats
import tv.cleanyour.core.spec.ScreensaverSettings
import tv.cleanyour.core.spec.StartUpSettings
import tv.cleanyour.core.spec.TvSettings
import tv.cleanyour.core.spec.WidgetsSettings
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.5.0 Make it yours: the pages' edits, one draft for every page counted against the TV's file, and one send of
// only the changed keys on top of the TV's file as it is then (PlanRequest.ApplySettings, no wallpaper step).
@OptIn(ExperimentalCoroutinesApi::class)
class MakeItYoursTest {
    private val e = MakeItYoursEdits
    private val on = PackageState(EnabledState.ENABLED, false, 0, null, false, false, null, false)
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))
    private val tvFile = """{"greeting_name": "Asha", "show_stats": false, "columns": 5, "dock_apps": ["com.netflix.ninja"],
        |"zz_future_key": 1, "icon_only_buttons": true, "wallpaper_dim": 30}""".trimMargin().encodeToByteArray()

    private fun flat(bytes: ByteArray): Map<String, JsonElement> = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private suspend fun Harness.ourTv(overlay: ByteArray?, launcherVersion: Long? = null): TvId {
        val t = knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        tvs.upsert(t)
        val packages = Samples.miTvPackages() + dock.associateWith { on } + listOfNotNull(launcherVersion?.let { Launcher.pkg to on.copy(versionCode = it) })
        val snap = Samples.snapshot(takenAtMs = Samples.NOW_MS + 1, packages = packages, home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0))))
            .copy(launcherOverlay = overlay)
        snapshots.save(snap.copy(contentHash = SnapshotCodec.hash(snap)))
        return t.id
    }

    private fun TestScope.machine(h: Harness, id: TvId): MakeItYoursMachine {
        val m = MakeItYoursMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, id)
        settle()
        return m
    }

    @Test fun thePagesReadTheTvsFileAndCountWhatChanged() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = machine(h, h.ourTv(tvFile))
        val s = m.state.value
        assertFalse(s.loading)
        assertEquals(0, s.changes)
        assertEquals("Asha", s.shown.widgets.greetingName)
        assertEquals(ButtonsLook.ICONS, s.buttonsLook)
        assertEquals(true, s.shown.widgets.showGreeting, "not in the file: the launcher's own")
        m.dispatch(MakeItYoursIntent.Stats(true)); settle()
        m.dispatch(MakeItYoursIntent.Greeting(false)); settle()
        assertEquals(2, m.state.value.changes)
        // Back to what the TV has is no change.
        m.dispatch(MakeItYoursIntent.Stats(false)); settle()
        assertEquals(1, m.state.value.changes)
        m.dispatch(MakeItYoursIntent.Discard); settle()
        assertEquals(0, m.state.value.changes)
        assertEquals(true, m.state.value.shown.widgets.showGreeting)
    }

    @Test fun oneSendTakesTheChangedKeysAndKeepsEverythingElse() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = h.ourTv(tvFile)
        val m = machine(h, id)
        m.dispatch(MakeItYoursIntent.Stats(true)); settle()
        m.dispatch(MakeItYoursIntent.ClockFormat(ClockFormats.H24)); settle()
        m.dispatch(MakeItYoursIntent.Start(StartOn.APP)); settle()
        m.dispatch(MakeItYoursIntent.Saver(true)); settle()
        m.dispatch(MakeItYoursIntent.Send); settle()
        val r = h.planner.requests.filterIsInstance<PlanRequest.ApplySettings>().single()
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplyTheme }, "no look push: the wallpaper stays")
        val sent = flat(h.blobs.bytes(r.config))
        assertEquals(JsonPrimitive(true), sent["show_stats"])
        assertEquals(JsonPrimitive("24"), sent["clock_format"])
        assertEquals(JsonPrimitive(true), sent["open_on_start_choice"])
        assertEquals(JsonPrimitive(false), sent["boot_to_live_tv"])
        assertEquals(JsonPrimitive(MakeItYoursEdits.SAVER_DEFAULT_MINUTES), sent["home_idle_clock_minutes"])
        assertEquals(JsonPrimitive(true), sent["clock_wallpaper"])
        for (k in listOf("greeting_name", "columns", "zz_future_key", "icon_only_buttons", "wallpaper_dim", "dock_apps")) assertEquals(flat(tvFile)[k], sent[k], k)
        assertNull(sent["show_greeting"], "a key nobody changed is not written")
        // What went is remembered, so the next push starts from it, and the page counts against it.
        assertEquals(flat(h.blobs.bytes(r.config)), flat(assertNotNull(PushedSettings.load(h.documents, id))))
        assertTrue(m.state.value.sent)
        assertEquals(0, m.state.value.changes)
    }

    @Test fun nothingIsSentWithoutAChange() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = machine(h, h.ourTv(tvFile))
        m.dispatch(MakeItYoursIntent.Send); settle()
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplySettings })
    }

    // A TV on launcher 3.0 never gets a 3.3 key, though the draft holds it.
    @Test fun aTvGetsOnlyTheKeysItsLauncherReads() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = machine(h, h.ourTv(tvFile, launcherVersion = 9L))
        assertFalse(m.state.value.reads("clock_format"))
        assertTrue(m.state.value.reads("show_greeting"))
        m.dispatch(MakeItYoursIntent.ClockFormat(ClockFormats.H12)); settle()
        m.dispatch(MakeItYoursIntent.DateBelow(true)); settle()
        m.dispatch(MakeItYoursIntent.Send); settle()
        val sent = flat(h.blobs.bytes(h.planner.requests.filterIsInstance<PlanRequest.ApplySettings>().single().config))
        assertNull(sent["clock_format"])
        assertEquals(JsonPrimitive(true), sent["date_below_clock"])
    }

    @Test fun theButtonsLookWritesItsKeys() {
        val w = WidgetsSettings()
        assertEquals(WidgetsSettings(topMenu = true), e.withButtonsLook(w, ButtonsLook.MENU))
        assertEquals(WidgetsSettings(topMenu = false, iconOnlyButtons = true, iconOnlyInputs = true, iconOnlyVpn = true), e.withButtonsLook(w, ButtonsLook.ICONS))
        val named = e.withButtonsLook(WidgetsSettings(iconOnlyButtons = true, wifiLabelWidth = 160), ButtonsLook.NAMES)
        assertEquals(ButtonsLook.NAMES, e.buttonsLook(named))
        assertEquals(0, named.wifiLabelWidth)
        assertEquals(ButtonsLook.MENU, e.buttonsLook(e.withButtonsLook(WidgetsSettings(iconOnlyButtons = true), ButtonsLook.MENU)))
    }

    @Test fun yourAppsAreUpToThree() {
        val apps = (1..5).map { PackageName("com.example.app$it") }
        var w = WidgetsSettings()
        for (a in apps) w = e.withTopApp(w, a, true)
        assertEquals(apps.take(3), w.topBarApps)
        w = e.withTopApp(w, apps[1], false)
        assertEquals(listOf(apps[0], apps[2]), w.topBarApps)
        assertEquals(listOf(apps[4]), e.withButton(WidgetsSettings(), TopButton.APPS, true, apps[4]).topBarApps, "switched on with the dock's first")
        assertEquals(emptyList(), e.withButton(w, TopButton.APPS, false).topBarApps)
    }

    @Test fun theSecondDateIsOneOrTheOther() {
        for (d in SecondDate.entries) assertEquals(d, e.secondDate(e.withSecondDate(WidgetsSettings(shamsiDate = true, lunarDate = true), d)))
    }

    @Test fun greenWifiOffTakesTheWarningsWithIt() {
        val w = e.withWifiGreen(WidgetsSettings(wifiStatusColor = true, wifiStatusWarnings = true), false)
        assertEquals(false, w.wifiStatusColor)
        assertEquals(false, w.wifiStatusWarnings)
        assertNull(e.withWifiGreen(WidgetsSettings(wifiStatusColor = true), false).wifiStatusWarnings, "a key not in the file stays out")
        assertTrue(e.wifiOpensVpn(WidgetsSettings(vpnApp = "com.wireguard.android")))
        assertFalse(e.wifiOpensVpn(WidgetsSettings(vpnApp = "com.wireguard.android", vpnPill = true)), "with a VPN button the Wi-Fi button opens Wi-Fi settings")
    }

    @Test fun startAndScreensaverMapToTheirKeys() {
        for (o in StartOn.entries) assertEquals(o, e.startOn(e.withStart(StartUpSettings(bootToLiveTv = true, openOnStartChoice = true), o)))
        val on = e.withSaver(ScreensaverSettings(), true)
        assertTrue(e.saverOn(on))
        assertEquals(SaverBehind.PICTURE, e.behind(on))
        assertEquals(0, e.withSaver(on, false).idleMinutes)
        assertEquals(on, e.withSaver(on, true), "already on: as it is")
        assertEquals(SaverBehind.BLACK, e.behind(e.withBehind(on, SaverBehind.BLACK)))
        assertEquals(SaverBehind.GLOW, e.behind(ScreensaverSettings.DEFAULTS))
    }

    @Test fun hiddenAppsAndNames() {
        val p = PackageName("com.example.ads")
        val hidden = e.withHidden(AppsSettings(), p, true)
        assertEquals(AppsSettings.DEFAULTS.hiddenApps!! + p, hidden.hiddenApps, "the launcher's own list, with the app")
        assertEquals(AppsSettings.DEFAULTS.hiddenApps, e.withHidden(hidden, p, false).hiddenApps)
        val named = e.withLabel(AppsSettings(), p, "  Films  ")
        assertEquals(mapOf(p to "Films"), named.appLabels)
        assertEquals(emptyMap(), e.withLabel(named, p, " ").appLabels)
        assertEquals(MakeItYoursEdits.MAX_LABEL, e.withLabel(AppsSettings(), p, "x".repeat(99)).appLabels!![p]!!.length)
    }

    // A config.json as a TV may have it: some keys of the pages and of the look, one no launcher reads.
    private fun randomFile(rnd: Random): ByteArray {
        val bools = listOf("show_stats", "show_greeting", "top_menu", "icon_only_buttons", "shamsi_date", "lunar_date", "app_library", "show_library_pill",
            "boot_to_live_tv", "open_on_start_choice", "clock_wallpaper", "clock_glow", "dock_row", "animations")
        val m = LinkedHashMap<String, JsonElement>()
        for (b in bools) if (rnd.nextBoolean()) m[b] = JsonPrimitive(rnd.nextBoolean())
        if (rnd.nextBoolean()) m["home_idle_clock_minutes"] = JsonPrimitive(rnd.nextInt(0, 60))
        if (rnd.nextBoolean()) m["columns"] = JsonPrimitive(rnd.nextInt(1, 9))
        if (rnd.nextBoolean()) m["zz_future_key"] = JsonPrimitive("kept")
        return kotlinx.serialization.json.JsonObject(m).toString().encodeToByteArray()
    }

    // Property: over random files and random edits, a send changes exactly the keys the draft changed, and a key
    // another push set after the page was read stays as that push left it.
    @Test fun aSendWritesOnlyTheDraftsKeys() {
        val rnd = Random(505)
        val edits = listOf<(SettingsDraft) -> SettingsDraft>(
            { it.copy(widgets = it.widgets.copy(showStats = rnd.nextBoolean())) },
            { it.copy(widgets = e.withButtonsLook(it.widgets, ButtonsLook.entries.random(rnd))) },
            { it.copy(widgets = e.withSecondDate(it.widgets, SecondDate.entries.random(rnd))) },
            { it.copy(apps = e.withLibrary(it.apps, rnd.nextBoolean())) },
            { it.copy(startUp = e.withStart(it.startUp, StartOn.entries.random(rnd))) },
            { it.copy(screensaver = e.withSaver(it.screensaver, rnd.nextBoolean())) },
        )
        repeat(500) { n ->
            val file = randomFile(rnd)
            val base = TvSettings.seed(file, null)
            var draft = SettingsDraft.of(base)
            repeat(rnd.nextInt(1, 5)) { draft = edits.random(rnd)(draft) }
            val wanted = draft.applyTo(base)
            val changed = wanted.changedKeys(base)
            // Another push since: a key nobody here touched.
            val now = base.withWidgets(base.widgets.copy(vpnApp = "com.example.vpn$n"))
            val sent = now.withKeysOf(wanted, changed)
            val k = flat(sent.encode())
            val w = flat(wanted.encode())
            for (c in changed) assertEquals(w[c], k[c], "case $n: $c")
            if ("vpn_app" !in changed) assertEquals(JsonPrimitive("com.example.vpn$n"), k["vpn_app"], "case $n")
            assertTrue(changed.containsAll(sent.changedKeys(now)), "case $n: only the draft's keys differ from the TV's file")
        }
    }
}
