package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #275 (TCL G07 Google TV): the remote's Settings (gear), Netflix and YouTube buttons only
// reach Google TV's own home screen. Setup's plan screen and Manage > Home screen say so before
// the switch on a TCL Google TV, and only there. Issue #3059 (Xiaomi MiTV-MOEU0): every other
// Google TV hears that the Settings (gear) button may stop working, and a TCL one does not.
@OptIn(ExperimentalCoroutinesApi::class)
class TclRemoteKeysTest {
    private val launcherx = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")

    private fun tclGoogleTv(reason: SnapshotReason = SnapshotReason.BASELINE): Snapshot =
        googleTv("TCL", "TCL", "55C755", "G08", reason)

    private fun googleTv(brand: String, manufacturer: String, model: String, device: String,
                         reason: SnapshotReason = SnapshotReason.BASELINE): Snapshot {
        val base = Samples.miTvSnapshot(reason = reason)
        val f = base.facts.copy(brand = brand, manufacturer = manufacturer, model = model, device = device)
        return base.copy(facts = f, packages = base.packages + (launcherx.pkg to base.packages.values.first()),
            home = HomeResolution(launcherx, listOf(HomeClaimant(launcherx, 2))))
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun setupWarnsOnATclGoogleTvOnly() = runTest(timeout = 30.seconds) {
        for (tcl in listOf(true, false)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            if (tcl) {
                h.prober.facts = tclGoogleTv().facts
                h.prober.snapshotFor = { r -> tclGoogleTv(r) }
            }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
            val m = h.setup(backgroundScope)
            toFound(h, m)
            if (tcl) assertTrue(m.extras.value.tclRemoteKeys, "a TCL Google TV hears about the remote buttons")
            else assertFalse(m.extras.value.tclRemoteKeys, "the reference Xiaomi does not")
            assertFalse(m.extras.value.googleTvSettingsKey, "neither hears the other Google TV warning")
        }
    }

    @Test fun setupWarnsAboutTheSettingsKeyOnAnotherGoogleTvOnly() = runTest(timeout = 30.seconds) {
        val sets = listOf<((SnapshotReason) -> Snapshot)?>(
            { r -> googleTv("Xiaomi", "Xiaomi", "MiTV-MOEU0", "moeu0", r) },
            { r -> googleTv("Sony", "Sony", "BRAVIA 4K VH2", "BRAVIA_VH2", r) },
            { r -> googleTv("Philips", "TP Vision", "55PUS8808", "TA7", r) },
            null)   // the reference Xiaomi, an Android TV
        for (set in sets) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            if (set != null) {
                h.prober.facts = set(SnapshotReason.BASELINE).facts
                h.prober.snapshotFor = set
            }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
            val m = h.setup(backgroundScope)
            toFound(h, m)
            if (set != null) assertTrue(m.extras.value.googleTvSettingsKey, "a Google TV hears about the Settings button")
            else assertFalse(m.extras.value.googleTvSettingsKey, "the reference Xiaomi does not")
            assertFalse(m.extras.value.tclRemoteKeys, "only a TCL hears about the TCL buttons")
        }
    }

    @Test fun manageHomeScreenWarnsOnATclGoogleTvOnly() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot())
        assertFalse(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.tclRemoteKeys)
        h.snapshots.save(tclGoogleTv().copy(takenAtMs = Samples.NOW_MS + 10).let { it.copy(contentHash = SnapshotCodec.hash(it)) })
        assertTrue(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.tclRemoteKeys)
        assertFalse(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.googleTvSettingsKey, "a TCL keeps its own warning")
    }

    @Test fun manageHomeScreenWarnsAboutTheSettingsKeyOnAnotherGoogleTvOnly() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot())
        assertFalse(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.googleTvSettingsKey, "the reference Xiaomi")
        h.snapshots.save(googleTv("Sony", "Sony", "BRAVIA 4K VH2", "BRAVIA_VH2").copy(takenAtMs = Samples.NOW_MS + 10).let { it.copy(contentHash = SnapshotCodec.hash(it)) })
        val state = HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value
        assertTrue(state.googleTvSettingsKey, "a Sony Google TV")
        assertFalse(state.tclRemoteKeys)
    }
}
