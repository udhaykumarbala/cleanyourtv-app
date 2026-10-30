package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.8: the Home screen page after a look went to the TV ("This is your TV now", PLAN item 3), Done's "Send it again",
// and the two beta1 QA findings (PLAN item 5): no "Use the TV's own home screen" when it is Home already, and no "Put
// it on the TV" when nothing changed.
@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreen048Test {
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))
    private val themes = FakeThemeSource(listOf(Theme("cinema", "Cinema", "Curtains", LauncherConfigDoc(columns = 5, cinematicHome = true), null)))
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private suspend fun Harness.ourTv(home: HomeResolution = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0)))): tv.cleanyour.core.model.TvId {
        val t = knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        tvs.upsert(t)
        snapshots.save(Samples.snapshot(takenAtMs = Samples.NOW_MS + 1, home = home))
        return t.id
    }

    @Test fun afterALookReachedTheTvThePageShowsTheTvAsItLooksNow() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = h.ourTv()
        HomePrefs(h.documents).save(id, HomeChoice(dock, "cinema", cards = false, picture = LookPicture.NONE_ID))
        h.prober.screenshotPng = png
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = themes), backgroundScope, id)
        settle()
        assertNull(m.state.value.lookShot)
        m.dispatch(HomeScreenIntent.RestoreMine); settle()
        assertEquals(1, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size)
        assertNull(m.state.value.lookShot, "a moment first, so the home screen has drawn the look")
        settle(HomeScreenMachine.LOOK_SHOT_DELAY_MS)
        assertContentEquals(png, m.state.value.lookShot)
        // "Not right? Send it again" sends the saved look once more; the old picture goes with the new send.
        m.dispatch(HomeScreenIntent.RestoreMine); settle()
        assertNull(m.state.value.lookShot)
        assertEquals(2, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size)
    }

    @Test fun doneSendItAgainOpensThePageThatSendsTheSavedLook() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = h.ourTv()
        HomePrefs(h.documents).save(id, HomeChoice(dock, "cinema", cards = false, picture = LookPicture.NONE_ID))
        HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = themes), backgroundScope, id, again = true)
        settle()
        assertEquals(1, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size, "once, after the page loaded")
        // Without the ask, opening the page sends nothing.
        HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, themes = themes), backgroundScope, id)
        settle()
        assertEquals(1, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size)
    }

    @Test fun noUseTheTvsOwnHomeScreenWhenItIsHomeAlready() = runTest(timeout = 30.seconds) {
        val stock = Samples.stockLauncher
        val h = Harness()
        val id = h.ourTv(HomeResolution(stock, listOf(HomeClaimant(stock, 2), HomeClaimant(Launcher.home, 0))))
        val m = HomeScreenMachine(h.manageDeps(), backgroundScope, id)
        settle()
        assertEquals(stock, m.state.value.stock)
        assertTrue(m.state.value.stockIsHome, "the helper switched off: the TV's own home screen answers Home")
        val ours = Harness()
        val id2 = ours.ourTv()
        val m2 = HomeScreenMachine(ours.manageDeps(), backgroundScope, id2)
        settle()
        assertFalse(m2.state.value.stockIsHome)
    }

    @Test fun putItOnTheTvOnlyWhenSomethingChanged() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = h.ourTv()
        h.snapshots.save(Samples.snapshot(takenAtMs = Samples.NOW_MS + 2, home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0))),
            packages = Samples.miTvPackages() + dock.associateWith { tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, false, 0, null, false, false, null, false) }))
        HomePrefs(h.documents).save(id, HomeChoice(dock, null, cards = false, picture = LookPicture.NONE_ID, columns = 5, style = StyleId.CLASSIC.id))
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, id)
        settle()
        m.dispatch(HomeScreenIntent.OpenLook); settle()
        assertFalse(assertNotNull(m.state.value.look).canSend, "the TV has this look")
        m.dispatch(HomeScreenIntent.UseLook); settle()
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplyTheme }, "nothing sent")
        assertNotNull(m.state.value.look, "the sheet stays open")
        m.dispatch(HomeScreenIntent.Look(LookEdit.SelectStyle(StyleId.GLASS))); settle()
        assertTrue(assertNotNull(m.state.value.look).canSend)
        m.dispatch(HomeScreenIntent.UseLook); settle()
        assertEquals(1, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().size)
        // A look never sent from this phone can always be sent.
        val fresh = Harness()
        val id2 = fresh.ourTv()
        val m2 = HomeScreenMachine(fresh.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, id2)
        settle()
        m2.dispatch(HomeScreenIntent.OpenLook); settle()
        assertTrue(assertNotNull(m2.state.value.look).canSend)
    }

    // 0.4.8 (issue #2742): Manage's dock list used the rule for a TV that cannot say, which hid launchable system apps
    // (the Play Store, Settings) and offered user apps with nothing to open (our TV curtain, a token service). With the
    // TV connected it offers what a launcher would show; FallbackHome (a HOME claimant at -1000, in Settings) bars
    // nothing, and our curtain is never offered.
    @Test fun theDockListOffersTheAppsALauncherWouldShow() = runTest(timeout = 30.seconds) {
        val h = Harness()
        fun st(system: Boolean) = tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, system, 1, null, false, false, null, false)
        val store = PackageName("com.android.vending")
        val settings = PackageName("com.android.tv.settings")
        val curtain = PackageName("tv.cleanyour.curtain")
        val token = PackageName("com.example.tokenservice")
        val vlc = PackageName("org.videolan.vlc")
        val fallback = tv.cleanyour.core.model.Component(settings, ".FallbackHome")
        val home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0), HomeClaimant(fallback, -1000)))
        val t = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(t)
        h.snapshots.save(Samples.snapshot(takenAtMs = Samples.NOW_MS + 1, home = home,
            packages = Samples.miTvPackages() + mapOf(store to st(true), settings to st(true), curtain to st(false), token to st(false), vlc to st(false))))
        h.prober.launchableApps = setOf(store, settings, curtain, vlc)
        val m = HomeScreenMachine(h.manageDeps(), backgroundScope, t.id).also { settle() }
        val offered = m.state.value.apps.map { it.pkg }.toSet()
        assertTrue(store in offered && settings in offered && vlc in offered, offered.toString())
        assertFalse(curtain in offered || token in offered, offered.toString())
        // Not connected (or the TV cannot say): the rule as before, but still never the curtain.
        h.prober.launchableApps = null
        val fallbackRule = HomeScreenMachine(h.manageDeps(), backgroundScope, t.id).also { settle() }.state.value.apps.map { it.pkg }.toSet()
        assertTrue(vlc in fallbackRule && token in fallbackRule && store !in fallbackRule, fallbackRule.toString())
        assertFalse(curtain in fallbackRule)
    }
}
