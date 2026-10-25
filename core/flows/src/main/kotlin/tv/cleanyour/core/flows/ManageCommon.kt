package tv.cleanyour.core.flows

import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.discovery.Discovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.probe.RemoteKeyCapture
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.store.TvRepository
import tv.cleanyour.core.trust.ApkInspector
import tv.cleanyour.core.trust.LauncherPin

// One-shot effects of the Manage family (M1 to M8). Snackbars carry a kind and a name; the screen
// turns them into the sentence from strings.xml, so the machines stay free of copy.
sealed interface ManageEffect {
    data class Navigate(val key: String) : ManageEffect
    data class Snackbar(val kind: SnackKind, val name: String, val undo: Boolean, val pkg: PackageName? = null) : ManageEffect
    data class Share(val text: String) : ManageEffect
    data class CreateDocument(val suggestedName: String) : ManageEffect   // SAF ACTION_CREATE_DOCUMENT
    data class OpenUrl(val url: String) : ManageEffect
    data class Copy(val text: String) : ManageEffect                     // to the clipboard (0.3.2 B1, B2)
    data object HapticLight : ManageEffect
    data object HapticDouble : ManageEffect
}

enum class SnackKind { PUT_BACK, SWITCHED_OFF, HOME_SAVED, MODULE_REMOVED, EXPORT_SAVED, EXPORT_FAILED, FAILED, HOME_UPDATED,
    KEPT_TV_INPUT,    // RF-LAUNCHER-250 F1: a switch-off the live check kept because it runs the TV's inputs
    KEPT_ON,          // issue #10: a switch-off the TV kept on (or the live check refused); nothing changed
    RUN_BUSY,         // Forget this TV refused: setup is still working on this TV
    RUN_BUSY_ANY }    // Delete everything refused: setup is still working on the TV named

// What a write in the Manage family is doing, for the narration row and the disabled buttons.
sealed interface Busy {
    data object Connecting : Busy
    data class Working(val percent: Int, val lines: List<String>) : Busy
}

// Everything the Manage family needs; the app builds one from AppGraph.
class ManageDeps(
    val tvs: TvRepository,
    val sessions: SessionManager,
    val discovery: Discovery,
    val access: TvAccess,
    val gate: GateKeeper,
    val runner: PlanRunner,
    val engine: Engine,
    val ledger: RunLedger,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val snapshots: SnapshotStore,
    val documents: DocumentStore,
    val blobs: BlobStore,
    val themes: ThemeSource,
    val launcherPin: LauncherPin,
    val configTransport: ConfigTransport,
    val restorePoint: RestorePointWriter,
    val bundleWriter: RestoreBundleWriter,
    val registry: Registry,
    val fetcher: ArtifactFetcher,
    val inspector: ApkInspector,
    val cardDetector: CardAnswerDetector,
    val sessionEnd: SessionEnd,
    val homePrefs: HomePrefs,
    val appPrefs: AppPrefs,
    val keyInfo: KeyInfo,
    val updates: UpdateChecker,
    val diagnostics: Diagnostics,
    val clock: Clock,
    val copy: FlowCopy,
    val encoder: ConfigEncoder = ConfigEncoder.Codec,
    val appVersion: String = "0.2.0",
    val openWrites: OpenWrites = OpenWrites(),   // one per graph, shared by every Manage machine (R5-W8 O2)
    val store: StoreRules = StoreRules.FULL,     // STORES-PLAN 1: the update check and the add-ons of this build
    // 0.3.2 B1, B2: the app, its build and the phone, for the reports; and the wizard's home screen
    // switch decision for each TV (SetupMachine keeps it).
    val about: AppAbout = AppAbout(appVersion, "full", "", ""),
    val homeDecisions: HomeDecisions = HomeDecisions(documents),
    // Remote buttons: the next press on the TV's remote. Not part of the frozen Prober, like LaunchableApps.
    val remoteKeys: RemoteKeyCapture? = prober as? RemoteKeyCapture,
) {
    fun tv(id: TvId): Tv? = tvs.tvs.value.firstOrNull { it.id == id }

    suspend fun profileOf(tv: Tv): Profile? = tv.profileId?.let { id -> attempt { profiles.all() }.getOrNull()?.firstOrNull { it.id == id } }

    fun nameOf(pkg: PackageName, label: String? = null): String = AppNames.of(pkg, facts, label)
}

// The dock and theme this phone last pushed to a TV. Tv has no field for them once the wizard
// draft is gone, so they live beside the TV record: tvs/<id>/home.txt, "dock=a,b" and "theme=id".
// `cards` is the module_cards value the look carries (RFC-CARDS-1, "cards=true"): null when this
// phone has not kept one for the TV yet (Cards.of decides then). `keys` are the remote buttons mapped on
// Home screen, Remote buttons ("keys=scan:425:a.b,scan:150:c.d"): the app_keys entries every look pushed to
// this TV carries, so a new dock, look or card never drops them.
data class HomeChoice(val dock: List<PackageName>, val themeId: String?, val cards: Boolean? = null, val keys: List<String> = emptyList())

class HomePrefs(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/home.txt"

    suspend fun load(id: TvId): HomeChoice? = store.read(path(id)) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        HomeChoice(map["dock"].orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { PackageName(it) }.getOrNull() },
            map["theme"]?.takeIf { it.isNotBlank() }, map["cards"]?.toBooleanStrictOrNull(),
            map["keys"].orEmpty().split(',').filter { RemoteButtons.entry(it) != null })
    }

    suspend fun save(id: TvId, choice: HomeChoice) {
        val text = "dock=${choice.dock.joinToString(",") { it.value }}\ntheme=${choice.themeId.orEmpty()}\n" +
            (choice.cards?.let { "cards=$it\n" } ?: "") +
            (if (choice.keys.isNotEmpty()) "keys=${choice.keys.joinToString(",")}\n" else "")
        store.write(path(id), text.encodeToByteArray())
    }
}

// App settings (M8) that are preferences, not records: haptics and large text.
data class AppPrefsValue(val haptics: Boolean = true, val largeTextFollowsSystem: Boolean = true)

class AppPrefs(private val store: DocumentStore) {
    private val path = "settings.txt"

    suspend fun load(): AppPrefsValue = store.read(path) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        AppPrefsValue(haptics = map["haptics"] != "false", largeTextFollowsSystem = map["largeText"] != "false")
    } ?: AppPrefsValue()

    suspend fun save(v: AppPrefsValue) {
        store.write(path, "haptics=${v.haptics}\nlargeText=${v.largeTextFollowsSystem}\n".encodeToByteArray())
    }
}

// Deletes every document under a folder, depth first (DocumentStore.list returns child names),
// then the folder itself, so nothing is left under it, not even an empty folder (T7 item 6). A
// stray temporary file of a write cut off by a crash keeps its folder; that is harmless, so it is
// not a failure.
suspend fun DocumentStore.deleteTree(dir: String) {
    for (child in list(dir)) {
        val path = "$dir/$child"
        val grand = list(path)
        if (grand.isEmpty()) delete(path) else deleteTree(path)
    }
    try { delete(dir) } catch (e: java.io.IOException) { }
}

sealed interface WriteResult {
    data class Done(val plan: Plan, val finished: RunState.Finished) : WriteResult
    // The run is open (this write paused, or an earlier one is): the OpenWrites card shows it, so the
    // machine shows no snackbar of its own.
    data class Paused(val plan: Plan, val paused: RunState.Paused) : WriteResult
    data class Refused(val reasons: List<String>) : WriteResult
    data object NoTv : WriteResult           // cancelled at the debugging step, or the TV is unknown
    data object GateCancelled : WriteResult
    data class Failed(val detail: String) : WriteResult
}

// The returning-user write of UX 4.11: the debugging step if needed (TvAccess), a plan from the
// newest snapshot, the credential gate (D17), the run, then the restore point on the TV (D44).
// `fresh` plans from a new snapshot instead of the newest stored one, for a request that needs what
// an older snapshot may not hold (UpdateLauncher: the installed launcher's signer, launcher v2.4.0).
class TvWriter(private val d: ManageDeps) {
    suspend fun write(tvId: TvId, request: PlanRequest, fresh: Boolean = false, onBusy: (Busy) -> Unit = {}): WriteResult {
        onBusy(Busy.Connecting)
        val session = when (val a = d.access.session(tvId)) {
            is AccessResult.Ok -> a.session
            is AccessResult.Failed -> return WriteResult.Failed(a.error.toString())
            else -> return WriteResult.NoTv
        }
        val tv = d.tv(tvId) ?: return WriteResult.NoTv
        // An earlier write left its run open: no new plan, no prompt, the card for that run (O2).
        (d.engine.runState(tvId).value as? RunState.Paused)?.let { p -> d.openWrites.show(tv, p); return WriteResult.Paused(p.plan, p) }
        return attempt {
            val snapshot = if (fresh) d.runner.freshSnapshot(session) else d.runner.snapshotFor(tv, session)
            val plan = when (val r = d.runner.plan(tv, request, snapshot)) {
                is PlanResult.Ok -> r.plan
                is PlanResult.Refused -> return@attempt WriteResult.Refused(r.reasons)
            }
            val outcome = d.runner.run(tv, plan, session, onStarted = { d.sessionEnd.touched(tvId) }) { s ->
                if (s is RunState.Running) onBusy(Busy.Working(s.percent, s.narration.map { it.text }))
            }
            when (outcome) {
                is RunOutcome.Finished -> {
                    afterRun(tv, session, plan, outcome.state)
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> { d.openWrites.show(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
                is RunOutcome.StartRefused -> if (outcome.refusal == StartRefusal.RUN_OPEN) {
                    openRun(tv, session)?.let { WriteResult.Paused(it.plan, it) } ?: WriteResult.Failed(outcome.refusal.name)
                } else WriteResult.Failed(outcome.refusal.name)
                RunOutcome.GateCancelled -> WriteResult.GateCancelled
            }
        }.getOrElse { WriteResult.Failed(it.message ?: it.toString()) }
    }

    // D44: the restore point on the TV after every plan that changed something.
    private suspend fun afterRun(tv: Tv, session: TvSession, plan: Plan, finished: RunState.Finished) {
        if (plan.kind != PlanKind.WRITE_RESTORE_POINT && plan.kind != PlanKind.DEBUG_OFF && finished.status != RunStatus.ABANDONED) {
            d.restorePoint.write(d.tv(tv.id) ?: tv, session, d.profileOf(tv))
        }
    }

    // The open run on this TV as a Paused state, published as the card. After process death the
    // engine has not loaded it yet (start says RUN_OPEN from the journal), so resume loads it: safety
    // only, step R for an open section, then Paused (R0: 6).
    suspend fun openRun(tv: Tv, session: TvSession, publish: Boolean = true): RunState.Paused? {
        if (d.engine.runState(tv.id).value !is RunState.Paused) attempt { d.engine.resume(tv.id, session) }
        val p = d.engine.runState(tv.id).value as? RunState.Paused ?: return null
        if (publish) d.openWrites.show(tv, p)
        return p
    }

    // The card's buttons (O2): carry on, undo what we did, or stop here, for the open run on this
    // TV. Reconnects first (the debugging step if needed), asks for the credential where the plan
    // has a gated step (D17), then follows the run to its end or its next pause.
    suspend fun resolveOpen(tvId: TvId, action: OpenWriteAction): WriteResult {
        val tv = d.tv(tvId) ?: return WriteResult.NoTv
        d.openWrites.update(tvId) { it.copy(busy = true, notTaken = false) }
        fun idle() = d.openWrites.update(tvId) { it.copy(busy = false) }
        val session = when (val a = d.access.session(tvId)) {
            is AccessResult.Ok -> a.session
            is AccessResult.Failed -> { idle(); return WriteResult.Failed(a.error.toString()) }
            else -> { idle(); return WriteResult.NoTv }
        }
        return attempt {
            val paused = openRun(tv, session, publish = false) ?: run { d.openWrites.clear(tvId); return@attempt WriteResult.NoTv }   // it ended meanwhile
            val plan = paused.plan
            when (action) {
                OpenWriteAction.CARRY_ON, OpenWriteAction.UNDO -> {
                    val pass = if (GateKeeper.needsPass(plan)) d.gate.pass(tvId, tv.nickname) ?: run { idle(); return@attempt WriteResult.GateCancelled } else null
                    d.sessionEnd.touched(tvId)
                    if (action == OpenWriteAction.CARRY_ON) d.engine.continueRun(tvId, pass) else d.engine.undoRun(tvId, pass)
                }
                OpenWriteAction.STOP_HERE -> d.engine.abandon(tvId)
            }
            // The engine leaves this Paused state when it takes the action; one that it refuses (the
            // session dropped again) leaves it as it was, and the card says so.
            val moved = withTimeoutOrNull(MOVE_WAIT_MS) { d.engine.runState(tvId).first { it !== paused } } != null
            if (!moved) { d.openWrites.update(tvId) { it.copy(busy = false, notTaken = true) }; return@attempt WriteResult.Paused(plan, paused) }
            when (val outcome = d.runner.follow(tv, plan)) {
                is RunOutcome.Finished -> {
                    d.openWrites.clear(tvId)
                    afterRun(tv, session, plan, outcome.state)
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> { d.openWrites.show(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
                else -> { idle(); WriteResult.Failed("unexpected") }
            }
        }.getOrElse { idle(); WriteResult.Failed(it.message ?: it.toString()) }
    }

    companion object { const val MOVE_WAIT_MS = 5_000L }

    // A plan for preview only (Undo everything, Use the TV's own home screen): no gate, no run.
    // `fresh`: from a new snapshot while the TV is connected (as write takes it), else the stored one.
    suspend fun preview(tvId: TvId, request: PlanRequest, fresh: Boolean = false): Plan? {
        val tv = d.tv(tvId) ?: return null
        val live = if (fresh) d.access.live(tvId)?.let { s -> attempt { d.runner.freshSnapshot(s) }.getOrNull() } else null
        val snapshot = live ?: d.snapshots.latest(tvId) ?: d.access.live(tvId)?.let { d.runner.snapshotFor(tv, it) } ?: return null
        return (d.runner.plan(tv, request, snapshot) as? PlanResult.Ok)?.plan
    }
}
