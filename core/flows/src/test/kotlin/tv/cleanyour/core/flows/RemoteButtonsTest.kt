package tv.cleanyour.core.flows

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.probe.PressedKey
import tv.cleanyour.core.probe.RemoteKeyCapture

import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Remote buttons on the Home screen page: press a button on the TV's remote, choose an app, and the look the
// app pushes carries "scan:<code>:<package>" in app_keys with the launcher's key service asked for; the choice
// is kept for the TV, so a later dock, look, card or Setup keeps the button.
class RemoteButtonsTest {
    private val youtube = PackageName("com.google.android.youtube.tv")
    private val canal = PackageName("com.canal.android.canal")

    private class Presses(vararg keys: PressedKey?) : RemoteKeyCapture {
        val queue = ArrayDeque(keys.toList())
        override suspend fun nextKey(session: tv.cleanyour.core.session.TvSession): PressedKey? = queue.removeFirst()
    }

    private suspend fun Harness.tvWithLauncher(versionCode: Long): tv.cleanyour.core.model.Tv {
        val tv = knownTv().copy(launcher = LauncherState(versionCode, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        tvs.upsert(tv)
        val launcher = PackageState(EnabledState.ENABLED, false, versionCode, null, false, false, null, true)
        snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (Samples.launcherArtifact().pkg to launcher), takenAtMs = Samples.NOW_MS + 1))
        return tv
    }

    @Test fun pressChooseAndTheLookCarriesTheButton() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.tvWithLauncher(RemoteButtons.FIRST_WITH_SCANCODES)
        HomePrefs(h.documents).save(tv.id, HomeChoice(listOf(youtube), null, cards = false))
        val presses = Presses(PressedKey(425), PressedKey(240), PressedKey(108), null, PressedKey(150))
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, remoteKeys = presses), backgroundScope, tv.id)
        settle()
        assertTrue(m.state.value.buttonsSupported)
        assertTrue(m.state.value.buttons.isEmpty())

        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        assertEquals(ButtonCapture.Pressed(425), m.state.value.capture)
        // Nothing reaches the TV until an app is chosen.
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplyTheme })
        m.dispatch(HomeScreenIntent.MapButton(425, youtube)); settle()
        val apply = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last()
        assertEquals(listOf("scan:425:${youtube.value}"), apply.doc.appKeys)
        assertTrue(apply.keyService, "mapping a button asks for the launcher's key service")
        assertEquals(listOf(youtube), apply.doc.dockApps, "the dock is pushed as it was saved")
        assertEquals(listOf("scan:425:${youtube.value}"), HomePrefs(h.documents).load(tv.id)?.keys)
        assertEquals(listOf(425), m.state.value.buttons.map { it.scancode })
        assertNull(m.state.value.capture)

        // A button every unnamed key shares, a button the TV needs, and no press at all are never mapped.
        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        assertEquals(ButtonCapture.Shared, m.state.value.capture)
        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        assertEquals(ButtonCapture.Needed, m.state.value.capture)
        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        assertEquals(ButtonCapture.NothingPressed, m.state.value.capture)

        // A second button; a later look keeps both, and only mapping asks for the key service.
        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        m.dispatch(HomeScreenIntent.MapButton(150, canal)); settle()
        m.dispatch(HomeScreenIntent.SetTheme("aurora")); settle()
        val later = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last()
        assertEquals(listOf("scan:425:${youtube.value}", "scan:150:${canal.value}"), later.doc.appKeys)
        assertFalse(later.keyService)

        // Mapping a button again replaces its app; removing it takes it out of the next look.
        m.dispatch(HomeScreenIntent.MapButton(425, canal)); settle()
        assertEquals(listOf("scan:150:${canal.value}", "scan:425:${canal.value}"), HomePrefs(h.documents).load(tv.id)?.keys)
        m.dispatch(HomeScreenIntent.UnmapButton(150)); settle()
        assertEquals(listOf("scan:425:${canal.value}"), h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.appKeys)
        assertEquals(listOf(425), m.state.value.buttons.map { it.scancode })
    }

    @Test fun anOlderLauncherOrTheTvsOwnHomeScreenOffersNoButtons() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.tvWithLauncher(RemoteButtons.FIRST_WITH_SCANCODES - 1)
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec, remoteKeys = Presses(PressedKey(425))), backgroundScope, tv.id)
        settle()
        assertFalse(m.state.value.buttonsSupported, "launcher 2.5 skips scan: entries")
        m.dispatch(HomeScreenIntent.CaptureButton); settle()
        assertNull(m.state.value.capture)
        h.tvs.upsert(tv.copy(launcher = null))
        val own = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
        settle()
        assertFalse(own.state.value.buttonsSupported)
    }

    @Test fun keysSurviveTheirFileAndOnlyValidOnesComeBack() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = Samples.tv().id
        HomePrefs(h.documents).save(id, HomeChoice(listOf(youtube), "aurora", cards = true, keys = listOf("scan:425:a.b", "scan:150:c.d")))
        assertEquals(HomeChoice(listOf(youtube), "aurora", cards = true, keys = listOf("scan:425:a.b", "scan:150:c.d")), HomePrefs(h.documents).load(id))
        h.documents.write("tvs/${id.value}/home.txt", "dock=a.b\ntheme=\nkeys=scan:425:a.b,190:a.b,scan:0:a.b,scan:9:Not A Package\n".encodeToByteArray())
        assertEquals(listOf("scan:425:a.b"), HomePrefs(h.documents).load(id)?.keys)
        // A file from before Remote buttons has no keys line.
        h.documents.write("tvs/${id.value}/home.txt", "dock=a.b\ntheme=\n".encodeToByteArray())
        assertEquals(emptyList(), HomePrefs(h.documents).load(id)?.keys)
    }
}
