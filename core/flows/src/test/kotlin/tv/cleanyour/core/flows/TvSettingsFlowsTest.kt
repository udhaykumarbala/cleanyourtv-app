package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.ConfigKeys
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.TvSettings
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.5.0 F0 in the flows: a Manage push and a Setup plan send the TV's whole settings document,
// with the look's sections new and every other key as the TV has it, only the keys its launcher
// reads, and from what this phone pushed last when the stored read of the TV is older than that.
@OptIn(ExperimentalCoroutinesApi::class)
class TvSettingsFlowsTest {
    private val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))
    private val on = PackageState(EnabledState.ENABLED, false, 0, null, false, false, null, false)

    // Keys of other sections, one no launcher reads, an old look's switch, and the helper's keys.
    private val tvFile = """{"greeting_name": "Asha", "show_inputs_pill": false, "home_idle_clock_minutes": 10, "top_bar_apps": ["com.example.vpn"],
        |"clock_glow": false, "hidden_apps": ["com.example.ad", "com.mitv.gallery"], "zz_future_key": 1, "single_row": true,
        |"stock_home_redirect": "com.google.android.tvlauncher", "key_filter": false}""".trimMargin().encodeToByteArray()

    private fun flat(bytes: ByteArray): Map<String, JsonElement> = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private suspend fun Harness.ourTv(overlay: ByteArray?, launcherVersion: Long? = null, at: Long = Samples.NOW_MS + 1): TvId {
        val t = knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        tvs.upsert(t)
        val packages = Samples.miTvPackages() + dock.associateWith { on } + listOfNotNull(launcherVersion?.let { Launcher.pkg to on.copy(versionCode = it) })
        val snap = Samples.snapshot(takenAtMs = at, packages = packages, home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0))))
            .copy(launcherOverlay = overlay)
        snapshots.save(snap.copy(contentHash = SnapshotCodec.hash(snap)))
        return t.id
    }

    private suspend fun TestScope.pushDock(h: Harness, id: TvId): Map<String, JsonElement> {
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, id)
        settle()
        m.dispatch(HomeScreenIntent.SetDock(dock)); settle()
        return flat(h.blobs.bytes(h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().config))
    }

    @Test fun aManagePushKeepsTheTvsOtherKeys() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val sent = pushDock(h, h.ourTv(tvFile))
        for (k in listOf("greeting_name", "show_inputs_pill", "home_idle_clock_minutes", "top_bar_apps", "clock_glow", "zz_future_key")) {
            assertEquals(flat(tvFile)[k], sent[k], k)
        }
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv").map(::JsonPrimitive), (sent["dock_apps"] as List<*>))
        assertNull(sent["single_row"], "the look's own keys are the new look's")
        assertNull(sent["stock_home_redirect"], "the helper is not on, so its keys go (HomeHelperKeys.carry)")
        assertNull(sent["key_filter"])
        assertEquals(listOf(JsonPrimitive("com.example.ad"), JsonPrimitive("com.mitv.gallery")), sent["hidden_apps"] as List<*>, "the person's hidden list")
    }

    // A launcher 2.7 TV gets none of the 3.0 keys its file has; the 2.7 ones stay.
    @Test fun aLauncher27TvGetsNoLauncher30Key() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val sent = pushDock(h, h.ourTv(tvFile, launcherVersion = 8L))
        assertEquals(emptyList(), sent.keys.filter { ConfigKeys.of(it)?.since == ConfigKeys.V30 })
        assertEquals(JsonPrimitive(false), sent["show_inputs_pill"])
        assertEquals(JsonPrimitive("Asha"), sent["greeting_name"])
    }

    // The hidden list an earlier push made (the launcher's own list less a placed app) is worked out
    // again, so the Live TV app that is no longer placed goes off the grid, as in 0.4.8.
    @Test fun theRulesHiddenListIsWorkedOutAgain() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val sent = pushDock(h, h.ourTv("{\"hidden_apps\": [\"com.mitv.gallery\"], \"show_stats\": true}".encodeToByteArray()))
        assertNull(sent["hidden_apps"], "the launcher's own list again")
        assertEquals(JsonPrimitive(true), sent["show_stats"])
    }

    // After a push the stored read of the TV is older than the file the TV has: the next push starts
    // from what this phone pushed (PushedSettings), when the ledger's last change of config.json is it;
    // when the last change removed the file, from nothing.
    @Test fun theNextPushStartsFromWhatThisPhonePushed() = runTest(timeout = 30.seconds) {
        for (removed in listOf(false, true)) {
            val h = Harness()
            val id = h.ourTv(tvFile)
            val pushed = "{\"vpn_app\": \"com.example.vpn\"}\n".encodeToByteArray()
            PushedSettings.remember(h.documents, id, pushed)
            val ref = BlobRef(sha256Of(pushed), pushed.size.toLong(), "pushed")
            val op = if (removed) Op.RemoveFile(FileOverlayTransport.CONFIG) else Op.PushAsset(ref, FileOverlayTransport.CONFIG, ref.sha256)
            val plan = Samples.plan(listOf(op), tvId = id, kind = PlanKind.THEME, id = PlanId("01FAKEPUSH0000000000000001"))
            h.ledger.record(plan)
            h.journal.append(JournalRecord.Begin(plan.id, 0, op, emptyList(), Prior(), Samples.NOW_MS + 5))
            h.journal.append(JournalRecord.Done(plan.id, 0, 0, true, 1))
            val sent = pushDock(h, id)
            if (removed) {
                assertNull(sent["greeting_name"], "nothing of the read the removal made old")
                assertNull(sent["vpn_app"])
            } else {
                assertEquals(JsonPrimitive("com.example.vpn"), sent["vpn_app"])
                assertNull(sent["greeting_name"], "the read before the push is old")
            }
            // The push itself is kept for the next one.
            val next = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last()
            assertEquals(h.blobs.bytes(next.config).decodeToString(), PushedSettings.load(h.documents, id)?.decodeToString())
        }
    }

    // The merge Manage and Setup share (LookCarry.merge): the TV's file with the look's sections new,
    // and with no file on the TV the bytes the look made alone, as in 0.4.8.
    @Test fun theMergeKeepsTheFileAndWithNoFileIsTheLookAlone() {
        val seed = TvSettings.seed(tvFile, null)
        val built = LauncherConfigDoc(dockApps = dock, moduleCards = false, fillDock = false)
        val doc = LookCarry.merge(seed, built, null) { it }
        val sent = flat(LauncherConfigCodec.encode(doc))
        assertEquals(JsonPrimitive("Asha"), sent["greeting_name"])
        assertNull(sent["single_row"])
        assertTrue(sent.keys.containsAll(listOf("dock_apps", "module_cards", "fill_dock", "zz_future_key", "clock_glow")))
        assertEquals(LauncherConfigCodec.encode(built).decodeToString(),
            LauncherConfigCodec.encode(LookCarry.merge(TvSettings.seed(null, null), built, null) { it }).decodeToString(), "no file: the 0.4.8 bytes")
    }
}
