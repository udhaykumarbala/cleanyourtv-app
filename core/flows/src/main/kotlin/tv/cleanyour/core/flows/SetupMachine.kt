package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.backup.RestoreNote
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TrustRecord
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.FireOs
import tv.cleanyour.core.planner.HomeSwitchRisk
import tv.cleanyour.core.planner.TclGoogleTv
import tv.cleanyour.core.planner.TclChina
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.StockHome
import tv.cleanyour.core.planner.SwitchOffRule
import tv.cleanyour.core.probe.LaunchableApps
import tv.cleanyour.core.probe.SavedWifiOwners
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.planner.WizardWifi
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.session.BootOutcome
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.store.TvRepository
import tv.cleanyour.core.trust.CurtainPin
import tv.cleanyour.core.trust.LauncherPin

// How the wizard starts (ARCHITECTURE 2.2 start destination): fresh from Welcome, continuing a
// WizardDraft at the TV's remembered address, or at the resume prompt for a run with no End.
sealed interface SetupStart {
    data object Fresh : SetupStart
    data class Continue(val tvId: TvId) : SetupStart
    data class Resume(val tvId: TvId, val planId: PlanId) : SetupStart
}

data class IdentityCheck(val model: String, val serialLast4: String, val screenshot: ByteArray?)
// wallpaperPath (0.4.0): the look's own picture as a file on this phone, for the Look sheet; null without one.
data class ThemeOption(val id: String, val name: String, val description: String, val wallpaperPath: String? = null)
enum class Reconnect { TRYING, BACK, GAVE_UP }
enum class Finishing { SWITCHING, DONE }

// Why S3 offers no new home screen (RF-PROFILES-63 P1, P2); NONE when the switch is offered. The
// five after UNKNOWN_HOME are the switches skipped up front for safety (RF-SAFETY-250, HomeSwitchRisk).
// HOME_CHANGED (0.3.3, issue #8): HOME answers to a home screen app the person installed and the
// TV's own is not among the claimants, so the person's choice stays.
// REMOTE_BUTTONS (0.4.5): the remote's app buttons need the TV's own home screen (HomeSwitchRisk).
// OTHER_HOME (beta3): another home screen app claims Home where R0 would otherwise switch (HomeSwitchRisk).
enum class HomeBlock { NONE, TV_REFUSES, OLD_ANDROID, UNKNOWN_HOME, WIZARD_WIFI, WIZARD_WIFI_UNKNOWN, CASTING, BOOT_LOOP,
    WIZARD_STAYS_ON, HOME_CHANGED, REMOTE_BUTTONS, OTHER_HOME }

// Why a run changed nothing, for Done's body (0.3.3): nothing was planned to switch off (on a TV
// without an exact list, nothing we were sure about), every planned switch-off stayed on (the TV
// kept it, or the live check did), or the run was stopped before anything changed.
enum class NothingWhy { NOTHING_PLANNED, KEPT_ON, STOPPED }

// Presentation details the frozen SetupState has no field for (RFC-W8-2). Never a source of truth:
// everything here is derived from the state, the plan, the draft or a live call.
data class SetupExtras(
    val tvName: String? = null,
    val chip: ChipState? = null,
    val sheet: Sheet? = null,                    // S1c on Welcome, Picker and OpenDoor
    val addressError: Boolean = false,
    val card: StopCard? = null,                  // inline stops (TV not found, wrong network, identity, no shell v2)
    val allowRetries: Int = 0,
    val allowDenied: Boolean = false,
    val identity: IdentityCheck? = null,         // S2d first-time identity line (D38)
    val noScreenLock: Boolean = false,           // D17 grey line on S3
    val starting: Boolean = false,               // "Make it clean" pressed: prompt or start in flight
    val rollingBack: Boolean = false,
    val highlightStop: Boolean = false,          // system back on 4a and 4b only highlights the way out
    val reconnect: Reconnect? = null,            // S4d spinner, then "Back. Carry on?"
    val draft: WizardDraft? = null,
    val themes: List<ThemeOption> = emptyList(),
    val commands: List<String> = emptyList(),    // "Show commands" (D21): one line per op
    val unknownModel: Boolean = false,           // UX 2 "We found 9 things we're sure about"
    val homePossible: Boolean = true,            // UX 4.6 TV refuses other home screens
    val homeBlock: HomeBlock = HomeBlock.NONE,   // why the switch is not offered, for S3's line (RF-PROFILES-63 P1, P2)
    val fireTv: Boolean = false,                 // issue #2: a Fire OS set, so S3's TV_REFUSES line names Fire TV, not the panel's brand
    val tclRemoteKeys: Boolean = false,          // issue #275: a TCL Google TV, so S3 says which remote buttons the new home screen loses
    val connectError: ConnectError? = null,      // 0.3.6: the TV's port answered, the connection failed; the door's card and "Copy error"
    val staleNotice: Boolean = false,            // the TV changed under the plan; a new plan is shown
    val finishing: Finishing? = null,            // D7 "Switching debugging off... done."
    val keepsDebugging: Boolean = false,         // D7: "Keep debugging on" was chosen, so Finish leaves it on and Done says so
    val restartSlow: Boolean = false,            // UX 4.8 at 3 minutes
    val restartReenabled: Int = 0,               // "2 things came back on after the restart; switched off again."
    val doneLines: List<String> = emptyList(),   // refused ops named on Done (UX 4.14)
    val doneKept: List<String> = emptyList(),    // RF-LAUNCHER-250 F1: switch-offs kept because they run the TV's inputs
    val doneNothing: Boolean = false,            // 0.3.3 (issue #15): the run changed nothing on the TV, so Done says so
    val doneNothingWhy: NothingWhy = NothingWhy.NOTHING_PLANNED,   // which "nothing was changed" body Done shows
    val doneHomeKept: HomeOutcome? = null,       // QA of 0.4.5: the switch was tried and the old home screen is back, why (Done's line)
    val restorePointPushed: Boolean = false,     // D44
    val dock: List<AppChoice> = emptyList(),     // D4 row and the dock sheet (S3b): the dock, then Row 2 and Row 3
    val apps: List<AppChoice> = emptyList(),     // installed apps for the dock sheet
    val dockColumns: Int = DockLayers.DEFAULT_COLUMNS,   // apps per row for the chosen look (DockLayers)
    val addOns: Boolean = true,                  // STORES-PLAN 1: Done's "Add a card" (off in the Play build)
    // Test build: the card before the plan when this TV's list is a candidate (not tested yet), and
    // whether the plan on screen is that list (the person chose to try it).
    val candidate: CandidateOffer? = null,
    val candidateTried: Boolean = false,
    // 0.4.0 (RFC-LOOK-SETUP-1): the look step's Look sheet, the same choices as the Look sheet after
    // Setup: the draft with its preview, the gallery, the person's photo kept for this TV ("Restore
    // mine") and a photo just made in this step, with their dims, and how many apps of the draft's
    // dock wait beyond what this many in a row shows.
    val look: LookDraft? = null,
    val pictures: List<PictureOption> = emptyList(),
    val ownPicture: BlobRef? = null,
    val ownDim: Int = OwnPicture.DEFAULT_DIM,
    val newPicture: BlobRef? = null,
    val newDim: Int = OwnPicture.DEFAULT_DIM,
    val waiting: Int = 0,
)

class SetupDeps(
    val discovery: Discovery,
    val sessions: SessionManager,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val runner: PlanRunner,
    val engine: Engine,
    val gate: GateKeeper,
    val tvs: TvRepository,
    val snapshots: SnapshotStore,
    val ledger: RunLedger,
    val launcherPin: LauncherPin,
    val themes: ThemeSource,
    val restorePoint: RestorePointWriter,
    val restoreNote: RestoreNote,
    val sessionEnd: SessionEnd,
    val access: TvAccess,
    val clock: Clock,
    val copy: FlowCopy,
    // RFC-DOCK-1: the Setup plan's config.json (the dock, its rows and the look), stored like Manage's.
    val blobs: BlobStore,
    val encoder: ConfigEncoder = ConfigEncoder.Codec,
    val dock: DockPicker = DockPicker.Ranking,
    val screenLock: ScreenLock = ScreenLock { true },
    val diagnostics: Diagnostics = Diagnostics { "" },
    val locale: () -> String = { "en" },
    val pollMs: Long = 2_000,
    // Finding 3: the dock sheet's launchable apps; the app's AdbProber answers it.
    val launchable: LaunchableApps? = prober as? LaunchableApps,
    val log: FlowLog = FlowLog.None,
    // RFC-CARDS-1: where Manage keeps this TV's dock, theme and cards choice. Setup reads the cards
    // choice an earlier session kept (module_cards stays off otherwise) and keeps what it pushed.
    val homePrefs: HomePrefs? = null,
    // RFC-CURTAIN-1: the bundled TV curtain; null plans without it.
    val curtainPin: CurtainPin? = null,
    // RFC-LAUNCHER-1 (D3): who saved the TV's Wi-Fi networks; the app's AdbProber answers it. Null,
    // or no answer, keeps the setup wizard on.
    val savedWifi: SavedWifiOwners? = prober as? SavedWifiOwners,
    // STORES-PLAN 1: without add-ons (the Play build) Done offers no "Add a card".
    val store: StoreRules = StoreRules.FULL,
    // 0.3.2 B1: where the home screen switch decision is kept for the TV report; null keeps none.
    val homeDecisions: HomeDecisions? = null,
    // Test build: candidate lists (CandidateLists); null, or a build without them, never offers one.
    val candidates: CandidateLists? = null,
    // 0.3.6: "Copy error" on a failed connect names the app and the phone's Android version, and why
    // the last socket connect failed (the session's class and errno reason, never an address).
    val about: AppAbout? = null,
    val connectDetail: () -> String? = { null },
    // 0.4.0 (RFC-LOOK-SETUP-1): where the person's photo for a TV is kept (OwnPicture), so the look
    // step can show and send it and keep a photo made in Setup once the TV has it; null keeps none.
    val documents: DocumentStore? = null,
    // 0.4.0: the pictures any look can show instead of its own (the app's ThemeSource is one).
    val pictures: PictureSource = themes as? PictureSource ?: PictureSource.NONE,
    // Issue #629: what the home check saw in front when it last rolled back on this TV (the engine's
    // onWrongFocus), taken once; null when the app does not know.
    val wrongFocus: (TvId) -> Component? = { null },
)

// The first-run wizard, screens S1 to S5 (PRODUCT-SPEC 4, UX 2 and 4). One machine per wizard run,
// hosted by the app's ViewModel. Reducers are pure; side effects run in handle and feed back
// through send. The run itself lives in the engine's scope: this machine only observes it.
class SetupMachine(
    private val deps: SetupDeps,
    scope: CoroutineScope,
    private val start: SetupStart = SetupStart.Fresh,
) : Machine<SetupState, SetupIntent, SetupEffect>(initialState(start), scope), SetupActions {

    private val mutableExtras = MutableStateFlow(SetupExtras(addOns = deps.store.addOns))
    val extras: StateFlow<SetupExtras> = mutableExtras.asStateFlow()

    // Live context of this wizard run. Only handlers touch it.
    private var session: TvSession? = null
    private var tv: Tv? = null
    private var deviceFacts: DeviceFacts? = null
    private var resolution: ProfileResolution? = null
    private var snapshot: Snapshot? = null
    // beta3 QA: this session's probe read the TV before any change (its restore point is new).
    private var probedBaseline = false
    private var allowShown = false
    private var doorTv: FoundTv? = null
    private var knownTv: Tv? = null
    private var connectFailed = false   // a failed connect waits one poll before probing again
    private var blocked = false         // wrong TV, no shell v2 or a refusal: no retry until the person asks
    private var currentPlanId: PlanId? = null
    private var setupPlan: Plan? = null
    private var setupHome: SetupHome? = null   // the look the current plan pushes, by its digest
    private val drafts = LookDrafts(deps.themes, deps.pictures, deps.encoder, deps.clock)
    private var newPhoto: NewPhoto? = null    // RFC-LOOK-SETUP-1: a photo made in the look step, until Setup sends it
    private var undoRequested = false
    private var askedNotifications = false
    private var lastFinished: RunState.Finished? = null
    // QA of 0.4.5: the earlier Setup runs of this Setup ("Try again" after the home check), whose
    // switch-offs are still on the TV; Done and the timed out card count them with the last run's.
    private val earlierRuns = mutableListOf<PlanId>()
    private var abandonJob: Job? = null   // R1 R-11: an abandon waiting for the TV to come back
    private var heldTv: TvId? = null      // T7 item 1: the TV whose session this wizard holds (TvAccess.keep)
    private var launchable: Set<PackageName>? = null   // finding 3: null when the TV could not say
    // D3: whether the setup wizard saved the TV's Wi-Fi, read once per snapshot (wifiOf), and whether
    // the stop card on screen is the Wi-Fi one (its primary tries the switch again).
    private var wifiOf: Snapshot? = null
    private var wizardWifi: WizardWifi = WizardWifi.UNKNOWN
    private var wifiCard = false
    private val rejected = HashSet<String>()           // hosts answered "No" on the identity line, this run
    private var lastRunLog: String? = null
    // The pause the log line was written for, by identity: the engine makes a new Paused for every
    // pause, and a StateFlow drops one equal to the value it last delivered, so abandonToDone logs a
    // pause the collector never saw, and neither path logs the same pause twice.
    private var lastPauseLogged: RunState.Paused? = null
    private var lastDecision: HomeDecision? = null

    private var discoveryJob: Job? = null
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    private var connectJob: Job? = null
    private var runJob: Job? = null
    private var lookingJob: Job? = null
    private var notFoundJob: Job? = null
    private var reconnectJob: Job? = null
    private var restartJob: Job? = null
    private var draftJob: Job? = null

    private sealed interface Ev {
        data class Disc(val event: DiscoveryEvent) : Ev
        data object LookingOver : Ev
        data object NotFoundYet : Ev
        data class Tick(val ms: Long) : Ev
        data object PortOpen : Ev
        data class Allow(val fingerprint: String) : Ev
        data class Opened(val identityNeeded: Boolean) : Ev
        data class OpenFailed(val error: AdbError) : Ev
        data object GoProbe : Ev
        data class Stage(val tvId: TvId, val stage: ProbeStage) : Ev
        data class PlanReady(val tvId: TvId, val plan: Plan, val level: Level) : Ev
        data class Run(val tvId: TvId, val state: RunState) : Ev
        data class Stop(val tvId: TvId, val card: StopCard) : Ev
        data class ShowDone(val tvId: TvId, val summary: DoneSummary) : Ev
        data class RestartUpdate(val state: RestartState?) : Ev
        data class Door(val tv: FoundTv) : Ev
        data object ToWelcome : Ev
        data object Back : Ev
        data class CandidateChosen(val tryIt: Boolean) : Ev
    }

    init {
        // The hold ends with the wizard too (its scope is the screen's).
        scope.launch { try { awaitCancellation() } finally { heldTv?.let { deps.access.release(it) } } }
        // 0.4.0: a dated look (a festival) is offered up to its last day.
        val today = deps.clock.today()
        mutableExtras.update { it.copy(themes = drafts.offered(), pictures = drafts.pictureOptions()) }
        when (start) {
            SetupStart.Fresh -> afterTransition(state.value)
            is SetupStart.Continue -> scope.launch { continueDraft(start.tvId) }
            is SetupStart.Resume -> scope.launch { resumeRun(start.tvId, start.planId) }
        }
    }

    override fun intent(intent: SetupIntent) = dispatch(intent)

    // System back (RFC-W8-1 keeps it a method: it must answer at once whether it used the press).
    // Returns false when the wizard should be left (the app pops the entry).
    override fun back(): Boolean {
        val s = state.value
        return when {
            mutableExtras.value.sheet != null -> { mutableExtras.update { it.copy(sheet = null, addressError = false) }; true }
            s is SetupState.Found && s.sheet != null -> { send(SetupIntent.Open(null)); true }
            s is SetupState.Working || s is SetupState.Check -> { mutableExtras.update { it.copy(highlightStop = true) }; true }
            s is SetupState.Picker || s is SetupState.OpenDoor || s is SetupState.PairingNeeded -> { send(Ev.Back); true }
            else -> false
        }
    }

    override fun dismissCard() = mutableExtras.update { it.copy(card = null) }

    override fun chooseCandidate(tryIt: Boolean) = send(Ev.CandidateChosen(tryIt))

    override fun report(title: String) = shareDiagnostics(title)

    // reducer

    override fun reduce(state: SetupState, event: Any): SetupState = when (event) {
        is Ev.Disc -> reduceDiscovery(state, event.event)
        Ev.LookingOver -> if (state is SetupState.Welcome) state.copy(looking = false) else state
        SetupIntent.Start -> when (state) {
            is SetupState.Welcome -> when (state.found.size) {
                0 -> door(placeholderTv())
                1 -> door(state.found[0])
                else -> SetupState.Picker(state.found)
            }
            else -> state
        }
        is SetupIntent.PickTv -> when (state) {
            is SetupState.Picker -> state.found.firstOrNull { it.host == event.host }?.let { door(it) } ?: state
            is SetupState.Welcome -> state.found.firstOrNull { it.host == event.host }?.let { door(it) } ?: state
            else -> state
        }
        is SetupIntent.EnterAddress -> if (isHomeAddress(event.ip) && (state is SetupState.Welcome || state is SetupState.Picker || state is SetupState.OpenDoor || state is SetupState.PairingNeeded))
            door(manualTv(event.ip.trim())) else state
        // A family name as it is, a brand chip through Families.ofBrand. The TCL chip asks about the
        // menus first (TCL_ASK, no banner); the answer comes back as TCL_GLOBAL or TCL, and only
        // TCL (China-market firmware) gets the banner about the home screen.
        is SetupIntent.ChangeFamily -> if (state is SetupState.OpenDoor) {
            val f = if (event.family in Families.all) event.family else Families.ofBrand(event.family)
            state.copy(family = f, banner = bannerFor(f, state.tv))
        } else state
        is Ev.Door -> door(event.tv)
        Ev.PortOpen -> if (state is SetupState.OpenDoor && state.phase == DoorPhase.STEPS) state.copy(phase = DoorPhase.CONNECTING, step = 2, waitedMs = 0) else state
        is Ev.Allow -> if (state is SetupState.OpenDoor) state.copy(phase = DoorPhase.ALLOW, step = 2, fingerprint = event.fingerprint, waitedMs = 0) else state
        is Ev.Tick -> if (state is SetupState.OpenDoor) state.copy(waitedMs = state.waitedMs + event.ms) else state
        is Ev.Opened -> if (state is SetupState.OpenDoor) state.copy(phase = if (event.identityNeeded) DoorPhase.IDENTITY else DoorPhase.CONNECTED, waitedMs = 0) else state
        SetupIntent.ConfirmIdentity -> if (state is SetupState.OpenDoor && state.phase == DoorPhase.IDENTITY) state.copy(phase = DoorPhase.CONNECTED) else state
        is Ev.OpenFailed -> if (state is SetupState.OpenDoor) when (event.error) {
            AdbError.Auth.Denied, AdbError.Auth.PendingTooLong -> state.copy(phase = DoorPhase.ALLOW, waitedMs = 0)
            else -> state.copy(phase = DoorPhase.STEPS, waitedMs = 0)
        } else state
        is Ev.Stage -> if (state is SetupState.Probing && state.tvId == event.tvId) state.copy(stage = event.stage)
            else SetupState.Probing(event.tvId, event.stage, null)
        is Ev.PlanReady -> SetupState.Found(event.tvId, event.plan, event.level, (state as? SetupState.Found)?.sheet)
        is SetupIntent.Open -> if (state is SetupState.Found) state.copy(sheet = event.sheet) else state
        is Ev.Run -> reduceRun(state, event)
        is Ev.Stop -> SetupState.Stopped(event.tvId, event.card)
        is Ev.ShowDone -> SetupState.Done(event.tvId, event.summary, null)
        is Ev.RestartUpdate -> if (state is SetupState.Done) state.copy(restartState = event.state) else state
        Ev.ToWelcome -> SetupState.Welcome(emptyList(), onWifi = deps.discovery.network().value.onWifi, looking = true)
        Ev.Back -> SetupState.Welcome(emptyList(), onWifi = deps.discovery.network().value.onWifi, looking = true)
        SetupIntent.RejectIdentity -> SetupState.Welcome(emptyList(), onWifi = deps.discovery.network().value.onWifi, looking = true)
        else -> state
    }

    private fun reduceDiscovery(state: SetupState, e: DiscoveryEvent): SetupState {
        fun merge(list: List<FoundTv>): List<FoundTv> = when (e) {
            is DiscoveryEvent.Found -> list.filterNot { it.host == e.tv.host } + e.tv
            is DiscoveryEvent.Updated -> list.map { if (it.host == e.tv.host) e.tv else it }.let { if (it.none { t -> t.host == e.tv.host }) it + e.tv else it }
            is DiscoveryEvent.Lost -> list.filterNot { it.host == e.host }
            is DiscoveryEvent.NetworkState -> list
        }.filter(TvCandidates::isCandidate)
        return when (state) {
            is SetupState.Welcome -> state.copy(found = merge(state.found), onWifi = (e as? DiscoveryEvent.NetworkState)?.onWifi ?: state.onWifi)
            is SetupState.Picker -> state.copy(found = merge(state.found))
            else -> state
        }
    }

    private fun reduceRun(state: SetupState, ev: Ev.Run): SetupState = when (val r = ev.state) {
        is RunState.Running -> SetupState.Working(ev.tvId, r, r.thumbnail ?: (state as? SetupState.Working)?.thumbnail)
        is RunState.AwaitingConfirm -> SetupState.Check(ev.tvId, r, r.thumbnail ?: (state as? SetupState.Check)?.thumbnail, r.plan.summary.dock)
        else -> state
    }

    private fun door(tv: FoundTv): SetupState {
        val pairingOnly = (tv.pairingService != null || tv.connectService != null) && tv.port5555Open != true
        if (pairingOnly) return SetupState.PairingNeeded(tv)
        val family = Families.of(tv)
        val open = tv.port5555Open == true
        return SetupState.OpenDoor(tv, family, step = if (open) 2 else 0, phase = if (open) DoorPhase.CONNECTING else DoorPhase.STEPS,
            fingerprint = null, banner = bannerFor(family, tv), waitedMs = 0)
    }

    private fun bannerFor(family: String, tv: FoundTv): String? = when {
        family == Families.FIRE -> deps.copy.text(CopyKey.BANNER_FIRE)
        family == Families.TCL -> deps.copy.text(CopyKey.BANNER_TCL)   // China-market menus only, not TCL_GLOBAL
        listOfNotNull(tv.model, tv.friendlyName).any { "mibox" in it.lowercase() || "mi box" in it.lowercase() } -> deps.copy.text(CopyKey.BANNER_MIBOX)
        else -> null
    }

    // side effects

    override fun handle(event: Any, before: SetupState, after: SetupState) {
        // A new door (another TV, a typed address, a fresh start) clears the retry brakes.
        if (event is Ev.Door || event == SetupIntent.Start || event is SetupIntent.PickTv || event is SetupIntent.EnterAddress || event == Ev.Back || event == Ev.ToWelcome) {
            blocked = false; connectFailed = false
            mutableExtras.update { it.copy(connectError = null) }
        }
        when (event) {
            is SetupIntent.EnterAddress -> {
                // A typed address is the person's own choice, even one they said No to before.
                if (isHomeAddress(event.ip)) rejected -= event.ip.trim()
                mutableExtras.update {
                    if (isHomeAddress(event.ip)) it.copy(sheet = null, addressError = false, card = null) else it.copy(addressError = true)
                }
            }
            is SetupIntent.Open -> if (after !is SetupState.Found) mutableExtras.update { it.copy(sheet = event.sheet, addressError = false) }
                else if (event.sheet == Sheet.CUSTOMISE && after.level == Level.SIMPLE) setLevel(Level.CUSTOM)
            SetupIntent.AskTvAgain -> askAgain()
            is Ev.Opened -> { connectFailed = false; mutableExtras.update { it.copy(connectError = null) }; if (!event.identityNeeded) connected() }
            SetupIntent.ConfirmIdentity -> if (before is SetupState.OpenDoor && before.phase == DoorPhase.IDENTITY) connected()
            SetupIntent.RejectIdentity -> {
                // Finding 1: "No" must not lead back to the same device. It stays out of the list for
                // this run, and the address sheet opens so the right TV can be typed.
                (before as? SetupState.OpenDoor)?.tv?.host?.takeIf { it.isNotEmpty() }?.let { rejected += it }
                deps.log.line("setup: identity rejected")
                mutableExtras.update { it.copy(identity = null, sheet = Sheet.ADDRESS, addressError = false) }
                scope.launch { session?.let { s -> attempt { s.close() } }; session = null; tv = null }
            }
            Ev.GoProbe -> scope.launch { probe() }
            is Ev.CandidateChosen -> if (after is SetupState.Probing && mutableExtras.value.candidate != null) scope.launch { candidateChosen(event.tryIt) }
            is Ev.OpenFailed -> onOpenFailed(event.error)
            is SetupIntent.Keep -> editDraft { d ->
                if (event.keep) d.copy(kept = d.kept + event.pkg, extraOff = d.extraOff - event.pkg)
                else if (event.pkg in d.kept) d.copy(kept = d.kept - event.pkg) else d.copy(extraOff = d.extraOff + event.pkg)
            }
            // 0.4.0: the sheet's list, then the draft's apps it never showed (waiting for more apps in a
            // row), so a new dock never loses them, as on the Dock sheet after Setup.
            is SetupIntent.SetDock -> editDraft { d ->
                val tail = d.dock.orEmpty().drop(DockLayers.capacity(columnsOf(d))).filter { it !in event.dock }
                d.copy(dock = DockLayers.keep(event.dock + tail))
            }
            // look2: the look step's edits are the Look sheet's (LookDrafts.edit); the choice is kept
            // in WizardDraft.look, and the plan made again with its bytes. Nothing runs.
            is SetupIntent.Look -> lookEdit(event.edit)
            // 0.4.0, removed after look2 merges: a look of the 0.4.0 list is the draft it migrates to;
            // apps in a row and a picture are the look2 edits.
            is SetupIntent.SetTheme -> mutableExtras.value.look?.let { l -> setLook(drafts.selectLegacy(l, event.id, lookContext)) }
                ?: editDraft { it.copy(themeId = event.id, columns = null, look = null) }
            is SetupIntent.SelectColumns -> lookEdit(LookEdit.SelectColumns(event.count))
            is SetupIntent.SelectPicture -> mutableExtras.value.look?.let { l -> lookEdit(LookEdit.SelectPicture(drafts.legacyPicture(l, event.id))) }
            is SetupIntent.SetOwnPicture -> scope.launch {
                val dim = event.dim.coerceIn(0, 90)
                attempt { OwnPicture.stage(deps.blobs, event.jpeg) }.onSuccess { ref ->
                    newPhoto = NewPhoto(ref, event.jpeg, dim)
                    lookContext = lookContext.copy(new = LookPicture.Of(ref, dim))
                    mutableExtras.update { it.copy(newPicture = ref, newDim = dim) }
                    mutableExtras.value.look?.let { l ->
                        setLook(if (l.picture == LookPicture.NEW_ID) drafts.refresh(l, lookContext) else drafts.edit(l, LookEdit.SelectPicture(LookPicture.NEW_ID), lookContext))
                    }
                }.onFailure { deps.log.line("setup: the photo could not be staged") }
            }
            is SetupIntent.KeepDebuggingOn -> editDraft { it.copy(keepDebuggingOn = event.on) }
            is SetupIntent.ShowOnTv -> editDraft { it.copy(curtain = event.on) }
            SetupIntent.MakeItClean -> if (after is SetupState.Found && !mutableExtras.value.starting) {
                mutableExtras.update { it.copy(starting = true) }
                scope.launch {
                    // A Keep, dock or theme change just before the tap replans in the background; the
                    // run must be the plan with that change, never the one before it.
                    draftJob?.join()
                    makeItClean((state.value as? SetupState.Found)?.plan ?: after.plan, null)
                }
            }
            is SetupIntent.CredentialPassed -> { deps.gate.remember(event.pass); (state.value as? SetupState.Found)?.let { f -> if (mutableExtras.value.starting) scope.launch { makeItClean(f.plan, event.pass) } } }
            SetupIntent.CredentialFailed -> mutableExtras.update { it.copy(starting = false) }
            SetupIntent.StopAndUndo -> scope.launch { stopAndUndo() }
            SetupIntent.YesISeeIt -> scope.launch { tvIdOf(after)?.let { deps.engine.confirmHome(it) } }
            SetupIntent.NoPutItBack -> scope.launch { tvIdOf(after)?.let { deps.engine.rejectHome(it) } }
            SetupIntent.TryWithWizardOff -> scope.launch { tryWizardOff() }
            SetupIntent.LeaveOldHome -> scope.launch { leaveOldHome() }
            SetupIntent.CarryOn -> scope.launch { carryOn() }
            SetupIntent.UndoWhatWeDid -> scope.launch { undoWhatWeDid() }
            SetupIntent.Restart -> if (after is SetupState.Done && restartJob?.isActive != true) restartJob = scope.launch { restart(after) }
            SetupIntent.SaveRestoreNote -> scope.launch { restoreNote() }
            SetupIntent.AddCard -> if (deps.store.addOns) tvIdOf(after)?.let { effect(SetupEffect.Navigate("modules/${it.value}")) }
            SetupIntent.Finish -> if (after is SetupState.Done && mutableExtras.value.finishing == null) scope.launch { finish(after.tvId) }
            is Ev.Run -> onRun(before, event)
            is SetupIntent.StopAction -> onStopAction(after, event.action)
            else -> {}
        }
        // Bug 2 of the emulator run: a door whose address changed (the placeholder door to a typed
        // address, a found TV adopted, a second address typed while waiting) is a new door. Its old
        // port poll and connect belong to the old address, so they stop and the new ones start.
        val doorMoved = before is SetupState.OpenDoor && after is SetupState.OpenDoor && before.tv.host != after.tv.host
        if (doorMoved) leaveDoor()
        if (before::class != after::class || doorMoved || event is Ev.PortOpen || event is Ev.Door || event == SetupIntent.Start) afterTransition(after)
        else if (after is SetupState.OpenDoor && before is SetupState.OpenDoor && before.phase != after.phase) afterTransition(after)
        holdSession(after)
    }

    // The jobs and the session of a door that is being replaced by one at another address.
    private fun leaveDoor() {
        pollJob?.cancel(); pollJob = null
        connectJob?.cancel(); connectJob = null
        val old = session
        if (old != null) { session = null; tv = null; scope.launch { attempt { old.close() } } }
        mutableExtras.update { it.copy(identity = null, allowDenied = false) }
    }

    // T7 item 1: one hold on the session from the connect until Done or a stop card, so it never
    // drops to Idle (60 s after the last hold) while the person reads S3 or watches the run.
    private fun holdSession(s: SetupState) {
        val live = session
        val wants = live != null && when (s) {
            is SetupState.OpenDoor -> s.phase == DoorPhase.CONNECTED || s.phase == DoorPhase.IDENTITY
            is SetupState.Probing, is SetupState.Found, is SetupState.Working, is SetupState.Check -> true
            else -> false
        }
        val held = heldTv
        if (held != null && (!wants || held != live?.tvId)) { deps.access.release(held); heldTv = null }
        if (wants && live != null) { deps.access.keep(live); heldTv = live.tvId }
    }

    // Starts and stops the background jobs that belong to each state (F1: discovery only on
    // Welcome, the picker and a door with no TV yet; the port probe only while waiting for it).
    private fun afterTransition(s: SetupState) {
        val looking = s is SetupState.Welcome || s is SetupState.Picker || (s is SetupState.OpenDoor && s.tv.host.isEmpty())
        if (looking && discoveryJob?.isActive != true) {
            discoveryJob = scope.launch { deps.discovery.discover().collect { e -> onDiscovery(e) } }
        } else if (!looking) { discoveryJob?.cancel(); discoveryJob = null }

        if (s is SetupState.Welcome && lookingJob?.isActive != true) lookingJob = scope.launch { delay(8_000); send(Ev.LookingOver) }
        if (s is SetupState.OpenDoor && s.tv.host.isEmpty() && notFoundJob?.isActive != true) notFoundJob = scope.launch {
            delay(20_000)
            if ((state.value as? SetupState.OpenDoor)?.tv?.host?.isEmpty() == true) mutableExtras.update { it.copy(card = card(StopCase.TV_NOT_FOUND, null)) }
        }

        val waitingForPort = s is SetupState.OpenDoor && s.tv.host.isNotEmpty() && s.phase == DoorPhase.STEPS && !blocked
        if (waitingForPort && pollJob?.isActive != true) {
            val host = (s as SetupState.OpenDoor).tv.host
            pollJob = scope.launch {
                if (connectFailed) delay(deps.pollMs)
                while (true) {
                    if (attempt { deps.discovery.probe5555(host) }.getOrDefault(false)) { send(Ev.PortOpen); break }
                    delay(deps.pollMs)
                }
            }
        } else if (!waitingForPort) { pollJob?.cancel(); pollJob = null }

        if (s is SetupState.OpenDoor && tickJob?.isActive != true) {
            tickJob = scope.launch { while (true) { delay(1_000); send(Ev.Tick(1_000)) } }
        } else if (s !is SetupState.OpenDoor) { tickJob?.cancel(); tickJob = null }

        if (s is SetupState.OpenDoor && s.phase == DoorPhase.CONNECTING && connectJob?.isActive != true) {
            connectJob = scope.launch { connect(s.tv) }
        }
        mutableExtras.update { it.copy(tvName = nameOf(s) ?: it.tvName, chip = chipOf(s)) }
    }

    private fun onDiscovery(e: DiscoveryEvent) {
        val s = state.value
        // A device the person said No to stays out of this run's list (they can still type it).
        val seen = (e as? DiscoveryEvent.Found)?.tv?.host ?: (e as? DiscoveryEvent.Updated)?.tv?.host
        if (seen != null && rejected.contains(seen)) return
        if (s is SetupState.OpenDoor && s.tv.host.isEmpty() && (e is DiscoveryEvent.Found || e is DiscoveryEvent.Updated)) {
            val tv = (e as? DiscoveryEvent.Found)?.tv ?: (e as DiscoveryEvent.Updated).tv
            mutableExtras.update { it.copy(card = null) }
            send(Ev.Door(tv))
            return
        }
        if (e is DiscoveryEvent.NetworkState && !e.onWifi && (s is SetupState.Welcome || s is SetupState.Picker)) {
            mutableExtras.update { it.copy(card = card(StopCase.WRONG_NETWORK, null, body = CopyKey.STOP_WIFI_OFF_BODY)) }
        } else if (e is DiscoveryEvent.NetworkState && e.onWifi && mutableExtras.value.card?.case == StopCase.WRONG_NETWORK) {
            mutableExtras.update { it.copy(card = null) }
        }
        send(Ev.Disc(e))
    }

    private suspend fun connect(found: FoundTv) {
        val known = deps.tvs.tvs.value.firstOrNull { t -> t.endpoints.any { it.host == found.host } }
        allowShown = false
        doorTv = found
        knownTv = known
        deps.log.line("setup: connect start (${if (known != null) "known TV" else "new TV"}, ${found.sources.joinToString("+") { it.name.lowercase() }.ifEmpty { "found" }})")
        val result = attempt {
            deps.sessions.open(Endpoint.PlainTcp(found.host), known?.id) { fp -> allowShown = true; send(Ev.Allow(fp)) }
        }.getOrElse { e ->
            // Issues #325, #620: an exception the session did not type was logged as "unreachable",
            // the one word that sends the person to look at the phone's network. It is named for what
            // it is now; the session types its own (HandshakeFailure), so this is the last defence.
            if (e !is AdbException) deps.log.line("setup: connect threw ${LogCodes.of(e)}")
            OpenResult.Failed((e as? AdbException)?.error ?: AdbError.Transport.Protocol("unexpected"))
        }
        when (result) {
            is OpenResult.Ok -> {
                session = result.session
                val identity = result.session.identity
                // D38: a TV that raised no Allow dialog and is new to this phone gets the "Is this your
                // TV?" line with a screenshot before anything else happens.
                val needsIdentity = !allowShown && known == null
                deps.log.line("setup: connected (allow dialog ${if (allowShown) "shown" else "not shown"}, identity check ${if (needsIdentity) "needed" else "not needed"})")
                if (needsIdentity) {
                    val shot = attempt { deps.prober.screenshot(result.session) }.getOrNull()
                    mutableExtras.update { it.copy(identity = IdentityCheck(identity.model, identity.serialLast4, shot)) }
                }
                send(Ev.Opened(needsIdentity))
            }
            is OpenResult.Failed -> {
                deps.log.line("setup: connect failed ${errorName(result.error)}")
                if (result.error is AdbError.Transport && result.error != AdbError.Transport.NoShellV2) noteConnectError(result.error)
                send(Ev.OpenFailed(result.error))
            }
        }
    }

    // 0.3.6: the card's "Copy error" text, built when the connect fails, from the log as it is then.
    private suspend fun noteConnectError(error: AdbError) {
        val kind = errorName(error)
        val log = attempt { deps.diagnostics.redactedText() }.getOrDefault("")
        val detail = attempt { deps.connectDetail() }.getOrNull()
        mutableExtras.update { it.copy(connectError = ConnectError(kind, ConnectErrorSummary.build(deps.about, kind, detail, log))) }
    }

    private fun onOpenFailed(error: AdbError) {
        connectFailed = true
        if (error is AdbError.Policy.WrongTv || error == AdbError.Transport.NoShellV2) { blocked = true; pollJob?.cancel(); pollJob = null }
        when (error) {
            is AdbError.Policy.WrongTv -> mutableExtras.update { it.copy(card = card(StopCase.IDENTITY_CHANGED, null, args = mapOf("name" to (knownTv?.nickname ?: "")))) }
            AdbError.Transport.NoShellV2 -> mutableExtras.update { it.copy(card = card(StopCase.TV_NOT_SUPPORTED, null)) }
            AdbError.Auth.Denied -> mutableExtras.update { it.copy(allowDenied = true) }
            else -> {}
        }
    }

    private fun askAgain() {
        val s = state.value as? SetupState.OpenDoor ?: return
        blocked = false
        mutableExtras.update { it.copy(allowRetries = it.allowRetries + 1, allowDenied = false) }
        connectJob?.cancel()
        connectJob = scope.launch {
            session?.let { attempt { it.close() } }
            session = null
            connect(s.tv)
        }
    }

    private fun connected() {
        effect(SetupEffect.HapticDouble)
        scope.launch { delay(600); send(Ev.GoProbe) }
    }

    // probe and plan (S2d to S3)

    private suspend fun probe() {
        val s = session ?: return
        val tvId = s.tvId
        deps.log.line("setup: probe start")
        val startedMs = deps.clock.monotonicMs()
        send(Ev.Stage(tvId, ProbeStage.FACTS))
        val ok = attempt {
            val facts = deps.prober.facts(s)
            deviceFacts = facts
            val baseline = deps.snapshots.baseline(tvId)
            val snap = deps.prober.snapshot(s, if (baseline == null) SnapshotReason.BASELINE else SnapshotReason.MANUAL) { st -> send(Ev.Stage(tvId, st)) }
            probedBaseline = baseline == null
            deps.snapshots.save(snap)
            snapshot = snap
            // Issue #114: resolved with the Home the snapshot read, as the planner and the engine's
            // live check resolve it, so a list that does not fit this TV's home screen is not used.
            val res = deps.profiles.resolveFor(facts, snap.home, snap.packages)
            resolution = res
            // Finding 3: one read-only call for the apps a launcher would show; on any failure the
            // dock sheet falls back to its own rule.
            launchable = deps.launchable?.let { q -> attempt { q.launchable(s) }.getOrNull() }
            deps.log.line("setup: probe done in ${maxOf(deps.clock.monotonicMs() - startedMs, 0)} ms, ${snap.packages.size} packages, " +
                "${launchable?.size?.let { "$it launchable" } ?: "launchable unknown"}, profile ${LogCodes.profile(res)}, ${LogCodes.tv(facts)}")
            val record = upsertTv(s, facts, res)
            tv = record
            // Test build: a list not tested yet waits for the person's answer on the card.
            val candidate = deps.candidates?.offer(facts, snap.home, snap.packages)
            if (candidate != null) {
                deps.log.line("setup: candidate list ${candidate.id} offered")
                mutableExtras.update { it.copy(candidate = CandidateOffer.of(candidate), candidateTried = false) }
            } else replan(record)
        }
        ok.exceptionOrNull()?.let { e ->
            deps.log.line("setup: probe failed ${LogCodes.withDetail(e)}")
            val case = if (e is AdbException) StopCase.CONNECTION_LOST else StopCase.PROBE_FAILED
            send(Ev.Stop(tvId, card(case, null, args = mapOf("done" to 0, "total" to 0))))
        }
    }

    // Test build: the answer on the candidate card. It is kept (CandidateChoices) before the TV is
    // resolved again, so the plan, the engine's live check, Manage and the TV report all use the
    // same list; then the plan is made as usual, through every safety rule.
    private suspend fun candidateChosen(tryIt: Boolean) {
        val offer = mutableExtras.value.candidate ?: return
        val s = session ?: return
        val facts = deviceFacts ?: return
        mutableExtras.update { it.copy(candidate = null) }
        attempt { deps.candidates?.choose(offer.profileId, tryIt) }
            .onFailure { deps.log.line("setup: candidate choice not saved ${LogCodes.of(it)}") }
        deps.log.line("setup: candidate list ${offer.profileId}: " + if (tryIt) "trying the test list" else "careful brand set instead")
        val ok = attempt {
            val res = deps.profiles.resolveFor(facts, snapshot?.home, snapshot?.packages)
            resolution = res
            deps.log.line("setup: profile ${LogCodes.profile(res)}")
            mutableExtras.update { it.copy(candidateTried = (res as? ProfileResolution.Exact)?.profile?.isCandidate == true) }
            val record = upsertTv(s, facts, res)
            tv = record
            replan(record)
        }
        ok.exceptionOrNull()?.let { e ->
            deps.log.line("setup: probe failed ${LogCodes.withDetail(e)}")
            val case = if (e is AdbException) StopCase.CONNECTION_LOST else StopCase.PROBE_FAILED
            send(Ev.Stop(s.tvId, card(case, null, args = mapOf("done" to 0, "total" to 0))))
        }
    }

    private suspend fun upsertTv(s: TvSession, facts: DeviceFacts, res: ProfileResolution): Tv {
        val now = deps.clock.nowMs()
        val existing = deps.tvs.tvs.value.firstOrNull { it.id == s.tvId }
        val host = (s.state.value as? ConnectionState.Connected)?.endpoint ?: existing?.endpoints?.firstOrNull()
        val name = existing?.nickname ?: doorTv?.friendlyName ?: doorTv?.model ?: facts.model
        val profile = (res as? ProfileResolution.Exact)?.profile
        // RFC-LOOK-SETUP-1: a Setup run again starts from the look this phone saved for the TV (its
        // look, apps in a row and picture), so it never clears them.
        val saved = if (existing?.wizard != null) null else deps.homePrefs?.let { p -> attempt { p.load(s.tvId) }.getOrNull() }
        // look2: a saved choice in the new form is the look step's choice as it is (WizardDraft.look).
        val lookId = saved?.takeUnless { it.isNew }?.lookOn(deps.clock.today())
        val draft = existing?.wizard ?: WizardDraft(step = 3, level = existing?.level ?: Level.SIMPLE, kept = emptySet(), extraOff = emptySet(),
            dock = null, themeId = lookId, keepDebuggingOn = false, planId = null, updatedAtMs = now,
            columns = drafts.override(lookId, saved?.takeUnless { it.isNew }?.columns),
            picture = saved?.takeUnless { it.isNew }?.picture?.takeUnless { Looks.dated(lookId) },
            look = saved?.takeIf { it.isNew }?.let { HomePrefs.encode(it.copy(dock = emptyList(), cards = null)) })
        val tv = Tv(
            id = s.tvId, nickname = name, room = existing?.room, facts = facts,
            endpoints = listOfNotNull(host).ifEmpty { existing?.endpoints.orEmpty() },
            lastSubnet = deps.discovery.network().value.subnet ?: existing?.lastSubnet,
            trust = existing?.trust ?: TrustRecord(adbKeyId = "", tlsCertSha256 = null, firstConnectedAt = now, pairedAt = null),
            profileId = profile?.id, level = draft.level, launcher = existing?.launcher, wizard = draft.copy(step = 3, updatedAtMs = now),
            lastSeenAt = now, debugging = DebugState.ON,
        )
        deps.tvs.upsert(tv)
        mutableExtras.update { it.copy(draft = tv.wizard, tvName = tv.nickname) }
        return tv
    }

    private fun profile(): Profile? = (resolution as? ProfileResolution.Exact)?.profile

    // Why the new home screen is not offered: the TV refuses other home screens (UX 4.6); it runs an
    // Android older than the launcher's (RF-PROFILES-63 P1: the clean-up only, no install, look,
    // curtain or switch); the switch is known to be unsafe on it (RF-SAFETY-250: the MOKA family's
    // boot loop, casting on Google's own hardware); its profile knows no stock launcher the switch
    // could hand Home back to (P2: the clean-up only, instead of a plan the planner refuses as a
    // whole); or the setup wizard has to stay on for the Wi-Fi (D3) while the switch needs it off.
    //
    // Issue #2: the firmware's refusal is told from the TV's own facts too (FireOs, TclChina), so an
    // unlisted Fire TV on a brand sure set gets no install and no takeover; and without an exact
    // profile, a TV whose HOME claimants include none of the resolution's stock launchers gets the
    // clean-up instead of a plan the planner refuses as a whole (issue #85). The Fire TV flag is
    // set here too, for S3's line.
    private fun homeBlock(snap: Snapshot, wifi: WizardWifi): HomeBlock {
        val f = deviceFacts ?: return HomeBlock.NONE
        val p = profile()
        val fireOs = p?.platform == Platform.FIRE_OS || FireOs.isFireOs(f, snap)
        mutableExtras.update { it.copy(fireTv = fireOs, tclRemoteKeys = TclGoogleTv.applies(f, snap)) }
        // Already ours (a re-run): there is no switch to skip, as in the planner's takeover.
        // 0.3.4 (issues #171, #187, #220): Home may answer to our package name with another key on
        // it (CleanYourTV's tool for computers installs its launcher under the same name). That is not
        // ours: the planner may replace it only while the TV's own home screen is in charge (D36),
        // and refused the whole plan. The home screen was already changed, so Setup leaves it as it
        // is and does the clean-up alone, as for issue #8. A key the TV did not report stays ours,
        // as before.
        val pin = deps.launcherPin.bundled()
        val atHome = snap.home.resolved?.pkg == pin.pkg
        val homeSigner = snap.packages[pin.pkg]?.signer
        val anotherKey = atHome && homeSigner != null && homeSigner != pin.signer
        val ours = atHome && !anotherKey
        val risk = if (ours) null else HomeSwitchRisk.of(f, p, snap, wifi)
        // Issue #8: the exact profile's stock launchers too, through the planner's own StockHome, so
        // a TV whose own home screen is not among the claimants is never asked for a switch the
        // planner then refuses as a whole.
        val stocks = when (val r = resolution) {
            is ProfileResolution.Exact -> r.profile.stockLaunchers
            is ProfileResolution.BrandSureSet -> r.stockLaunchers
            is ProfileResolution.None -> r.stockLaunchers
            null -> emptyList()
        }
        val stockUnknown = !ours && StockHome.of(snap, stocks) == null
        return when {
            f.installRefusal || fireOs || p?.platform == Platform.TCL_CN || TclChina.applies(f, p) -> HomeBlock.TV_REFUSES
            f.sdk < DefaultPlanner.TV_MIN_SDK -> HomeBlock.OLD_ANDROID
            risk == HomeSwitchRisk.Risk.BOOT_LOOP -> HomeBlock.BOOT_LOOP
            risk == HomeSwitchRisk.Risk.CASTING -> HomeBlock.CASTING
            risk == HomeSwitchRisk.Risk.REMOTE_BUTTONS -> HomeBlock.REMOTE_BUTTONS
            anotherKey || (stockUnknown && StockHome.changedByPerson(snap, stocks)) -> HomeBlock.HOME_CHANGED
            (p != null && p.stockLaunchers.isEmpty()) || stockUnknown -> HomeBlock.UNKNOWN_HOME
            risk == HomeSwitchRisk.Risk.WIZARD_WIFI -> HomeBlock.WIZARD_WIFI
            risk == HomeSwitchRisk.Risk.WIZARD_WIFI_UNKNOWN -> HomeBlock.WIZARD_WIFI_UNKNOWN
            risk == HomeSwitchRisk.Risk.WIZARD_STAYS_ON -> HomeBlock.WIZARD_STAYS_ON
            risk == HomeSwitchRisk.Risk.OTHER_HOME -> HomeBlock.OTHER_HOME
            else -> HomeBlock.NONE
        }
    }

    // Plans the Setup from the current snapshot and draft; pure and quick (the planner is pure).
    private suspend fun replan(record: Tv, fresh: Boolean = false) {
        val s = session ?: return
        val res = resolution ?: return
        if (fresh) snapshot = deps.runner.freshSnapshot(s)
        val snap = snapshot ?: return
        val draft = withoutRefusedChoices(record, record.wizard ?: return, snap)
        val launcher = deps.launcherPin.bundled()
        val installed = snap.packages[launcher.pkg]
        // The dock and the rows under it, cut to what the chosen look holds at the chosen apps in a
        // row (DockLayers). The draft keeps its whole list (up to 24 apps), so fewer apps in a row
        // never drops the rest of the order.
        val choice = lookChoice(draft)
        val tvLook = LookTv(null, deviceFacts?.ramMb?.takeIf { it > 0 } ?: snap.facts.ramMb.takeIf { it > 0 },
            snap.hdmi.isNotEmpty() || profile()?.liveTv?.let { it in snap.packages } == true)
        val columns = mutableExtras.value.look?.columns ?: drafts.partsOf(choice, tvLook, autoPick = false).columns
        val theme = mutableExtras.value.look?.legacyThemeId?.let { drafts.theme(it) } ?: choice?.takeUnless { it.isNew }?.lookOn(deps.clock.today())?.let { drafts.theme(it) }
        val dock = DockLayers.trim(draft.dock ?: deps.dock.pick(profile(), snap.packages.keys, snap.hdmi.isNotEmpty(), profile()?.liveTv, deps.locale()), columns)
        val kept = draft.dock?.let(DockLayers::keep) ?: dock
        val wifi = wizardWifi(s, snap)
        val block = homeBlock(snap, wifi)
        val possible = block == HomeBlock.NONE
        val choices = Choices(
            kept = draft.kept, extraOff = draft.extraOff, dock = dock,
            theme = theme,
            installLauncher = possible, takeover = possible,
            replaceLauncherSigner = installed?.signer?.let { it != launcher.signer } ?: false,
            keepDebuggingOn = draft.keepDebuggingOn,
            wizardWifi = wifi,
        )
        // RFC-DOCK-1: the same config.json Manage's ApplyTheme would push for this dock and look, so
        // the plan, its hash and "Show commands" carry it. Only when the switch is possible: without
        // the new home screen there is nothing to read it. If it cannot be made, the plan goes without
        // it and the launcher keeps its own dock.
        // RFC-CARDS-1: module_cards off, so the new home screen never asks about cards the person did
        // not ask for; on only when they turned cards on for this TV in an earlier session (kept, or
        // said outright by the TV's overlay).
        val saved = deps.homePrefs?.let { attempt { it.load(record.id) }.getOrNull() }
        val cards = Cards.of(saved?.cards, snap.launcherOverlay, Cards.SETUP_DEFAULT)
        // RFC-LOOK-SETUP-1: the picture as the Look sheet sends it: the person's kept photo with its
        // saved dim, a photo made in this step (gone when the app was closed since: no picture), a
        // gallery picture or none. look2: the look step's draft is the Look sheet's (LookDrafts), and
        // the plan sends it in the new form.
        val own = deps.documents?.let { docs -> attempt { OwnPicture.ref(docs, deps.blobs, record.id) }.getOrNull() }
        val mine = own?.let { OwnPicture.mine(it, saved?.dim) }
        val photo = newPhoto
        val new = photo?.let { LookPicture.Of(it.ref, it.dim) }
        val label = { p: PackageName -> AppNames.of(p, deps.facts, snap.packages[p]?.label) }
        val lookApps = kept.map { p -> AppChoice(p, label(p)) }
        val keyNames = lookContext.keyNames || (deps.documents?.let { docs -> attempt { AppPrefs(docs).load() }.getOrNull()?.lookKeyNames } ?: false)
        val candidates = DockCandidates.of(snap, launchable, profile(), deps.facts).map { AppChoice(it, label(it)) }.sortedBy { it.name.lowercase() }
        lookContext = LookContext(apps = lookApps, others = candidates.filter { it.pkg !in kept }, mine = mine, new = new, tv = tvLook, cards = cards,
            keyNames = keyNames)
        val sheet = mutableExtras.value.look?.let { drafts.refresh(it, lookContext) } ?: drafts.open(choice, lookContext)
        val look = if (!possible) null else attempt { val (doc, w) = drafts.docOf(sheet, dock, cards, lookContext); Look.encode(deps.encoder, deps.blobs, doc, w) }
            .onFailure { deps.log.line("setup: the look could not be encoded, planned without it") }.getOrNull()
        // What Manage starts from once the run pushed it, saved as the Look sheet saves it, in the new
        // form: a photo made here is kept for the TV (and saved as "mine") only once the TV has it.
        val sendsNew = sheet.picture == LookPicture.NEW_ID && new != null
        val chosen = if (sendsNew) LookPicture.MINE_ID else sheet.picture ?: LookPicture.NONE_ID
        val dim = when {
            sendsNew -> photo.dim
            chosen == LookPicture.MINE_ID -> saved?.dim ?: OwnPicture.DEFAULT_DIM
            else -> null
        }
        setupHome = look?.let { SetupHome(it.digest, drafts.choiceOf(sheet, saved, kept, cards, chosen, dim), photo?.takeIf { sendsNew }, saved) }
        // RFC-CURTAIN-1: the TV shows the progress unless Custom switched it off (Simple never shows
        // the switch). An asset that does not match its pin is never offered; the plan goes without it.
        val showOnTv = draft.level == Level.SIMPLE || draft.curtain
        val oldAndroid = (deviceFacts?.sdk ?: DefaultPlanner.TV_MIN_SDK) < DefaultPlanner.TV_MIN_SDK   // P1: the curtain cannot install either
        val curtain = if (!showOnTv || oldAndroid) null else deps.curtainPin?.let { pin -> attempt { pin.bundled() }
            .onFailure { deps.log.line("setup: the TV curtain does not match its pin, planned without it") }.getOrNull() }
        when (val r = deps.runner.plan(record, PlanRequest.Setup(res, choices.copy(curtain = showOnTv), launcher, draft.level, look?.config, look?.wallpaper, curtain), snap)) {
            is PlanResult.Ok -> {
                setupPlan = r.plan
                val apps = candidates
                deps.log.line("setup: plan ready, ${r.plan.steps.size} steps, ${r.plan.steps.count { it.op is Op.Disable }} to switch off, " +
                    "level ${draft.level}, dock ${dock.size}, ${apps.size} dock candidates")
                // 0.3.2 B3: why the home screen switch is or is not in the plan, once per change, and
                // kept for the TV report (B1) that Manage makes without the TV.
                val decision = HomeDecision.of(block, r.plan.sections.isNotEmpty(), snap.home.resolved?.pkg == launcher.pkg,
                    app = deps.about?.version, profile = LogCodes.profile(res))
                if (decision != lastDecision) {
                    lastDecision = decision
                    deps.log.line("setup: ${decision.line}")
                    deps.homeDecisions?.let { store -> attempt { store.save(record.id, decision) } }
                }
                // The look step's draft and preview, from the whole draft dock (its waiting apps too).
                mutableExtras.update {
                    it.copy(commands = commandsOf(r.plan), unknownModel = res !is ProfileResolution.Exact, homePossible = possible, homeBlock = block,
                        noScreenLock = !deps.screenLock.available(), draft = draft, dock = dock.map { p -> AppChoice(p, label(p)) }, apps = apps,
                        dockColumns = columns, look = sheet, ownPicture = own, ownDim = saved?.dim ?: OwnPicture.DEFAULT_DIM,
                        newPicture = photo?.ref, newDim = photo?.dim ?: OwnPicture.DEFAULT_DIM, waiting = DockLayers.waiting(kept.size, columns))
                }
                send(Ev.PlanReady(record.id, r.plan, draft.level))
            }
            // 0.3.4 (issues #171, #187, #220): the reasons go in the log (an app the person installed
            // is not named), and the card says what happened: the TV answered and nothing was
            // changed, but there is no plan we can run safely. Before, it was the PROBE_FAILED card,
            // which said the TV had stopped answering.
            is PlanResult.Refused -> {
                deps.log.line("setup: plan refused")
                for (reason in r.reasons.distinct().take(REASONS_LOGGED)) deps.log.line("setup: refused because ${LogCodes.reason(reason, snap, deps.facts)}")
                send(Ev.Stop(record.id, card(StopCase.PLAN_REFUSED, null)))
            }
        }
    }

    // D3 (launcher v2.4.0's guard): the setup wizard deletes a Wi-Fi network it saved when it is
    // switched off, so the takeover may switch it off only when it owns none: others saved the TV's
    // networks, or nothing is saved (a cable). When the TV cannot tell, it stays on (RF-LAUNCHER-240
    // F6, F14). Asked only when a setup wizard is on the TV, once per snapshot; no answer is UNKNOWN.
    // Only the verdict is logged, never an owner or a network.
    private suspend fun wizardWifi(s: TvSession, snap: Snapshot): WizardWifi {
        if (wifiOf === snap) return wizardWifi
        val wizard = profile()?.setupWraith?.takeIf { it in snap.packages }
            ?: snap.packages.keys.firstOrNull { "setupwraith" in it.value.lowercase() }
        var verdict: SavedWifi.Verdict? = null
        val answer = if (wizard == null) WizardWifi.UNKNOWN else {
            verdict = deps.savedWifi?.let { q -> attempt { q.savedWifi(s) }.getOrNull() }?.verdict(wizard.value) ?: SavedWifi.Verdict.CANNOT_TELL
            when (verdict) {
                SavedWifi.Verdict.WIZARD_OWNS -> WizardWifi.OWNER
                SavedWifi.Verdict.OTHERS_OWN, SavedWifi.Verdict.NOTHING_SAVED -> WizardWifi.NOT_OWNER
                SavedWifi.Verdict.CANNOT_TELL -> WizardWifi.UNKNOWN
            }
        }
        if (verdict != null) deps.log.line("setup: setup wizard and saved Wi-Fi: ${verdict.name.lowercase()}")
        wifiOf = snap
        wizardWifi = answer
        return answer
    }

    private fun editDraft(change: (WizardDraft) -> WizardDraft) {
        val record = tv ?: return
        val draft = record.wizard ?: return
        val next = change(draft).copy(updatedAtMs = deps.clock.nowMs())
        val updated = record.copy(wizard = next, level = next.level)
        tv = updated
        if (next.keepDebuggingOn != draft.keepDebuggingOn) deps.sessionEnd.keepDebuggingOn(record.id, next.keepDebuggingOn)
        draftJob = scope.launch {
            deps.tvs.upsert(updated)
            replan(updated)
        }
    }

    private fun setLevel(level: Level) = editDraft { it.copy(level = level) }

    // look2: the look step's choice as the wizard keeps it: the new form (WizardDraft.look), else the
    // 0.4.0 fields (a draft of 0.4.0, or Setup run again on a TV with a saved 0.4.0 look), else none
    // (a TV this phone never customised: Setup's Default look, Classic with no picture).
    private fun lookChoice(draft: WizardDraft): HomeChoice? =
        draft.look?.let { HomePrefs.decode(it) }?.takeIf { it.isNew }
            ?: if (draft.themeId != null || draft.picture != null || draft.columns != null)
                HomeChoice(emptyList(), draft.themeId, picture = draft.picture, columns = draft.columns) else null

    // The draft's apps in a row: the open look step's, else its choice's.
    private fun columnsOf(draft: WizardDraft): Int = mutableExtras.value.look?.columns ?: drafts.partsOf(lookChoice(draft), lookContext.tv, autoPick = false).columns

    // What the look step's draft depends on (LookContext), made again on every plan.
    private var lookContext = LookContext(cards = Cards.SETUP_DEFAULT)

    private fun lookEdit(edit: LookEdit) {
        val l = mutableExtras.value.look ?: return
        if (edit is LookEdit.SetKeyNames) deps.documents?.let { docs -> scope.launch { attempt { AppPrefs(docs).let { p -> p.save(p.load().copy(lookKeyNames = edit.on)) } } } }
        if (edit is LookEdit.SetKeyNames) lookContext = lookContext.copy(keyNames = edit.on)
        setLook(drafts.edit(l, edit, lookContext))
    }

    // The new draft on screen, and in WizardDraft.look when the choice changed (the plan is made again).
    private fun setLook(next: LookDraft) {
        mutableExtras.update { it.copy(look = next) }
        val text = HomePrefs.encode(drafts.choiceOf(next, null, emptyList(), null, next.picture ?: LookPicture.NONE_ID, null))
        if (tv?.wizard?.look != text) editDraft { it.copy(look = text) }
    }

    // The look the current plan pushes: its digest, what Manage starts from once it is on the TV, the
    // photo made in the look step it sends (kept for the TV only then) and the choice saved before.
    private class SetupHome(val digest: Sha256, val choice: HomeChoice, val photo: NewPhoto?, val saved: HomeChoice?)

    private class NewPhoto(val ref: BlobRef, val jpeg: ByteArray, val dim: Int)

    // the run (S3 to S4)

    private suspend fun makeItClean(plan: Plan, given: GatePass?) {
        val record = tv
        val s = session
        if (record == null || s == null) { mutableExtras.update { it.copy(starting = false) }; return }
        mutableExtras.update { it.copy(starting = true) }
        var pass = given ?: deps.gate.cached(record.id)
        if (pass == null && GateKeeper.needsPass(plan)) {
            effect(SetupEffect.RequestCredential)
            pass = deps.gate.pass(record.id, record.nickname)
            if (pass == null) { mutableExtras.update { it.copy(starting = false) }; return }
        }
        mutableExtras.update { it.copy(noScreenLock = deps.gate.noScreenLock(record.id) || it.noScreenLock) }
        earlierRuns.clear()
        if (!askedNotifications) { askedNotifications = true; effect(SetupEffect.RequestNotificationPermission) }
        startPlan(record, s, plan, pass)
    }

    private suspend fun startPlan(record: Tv, s: TvSession, plan: Plan, pass: GatePass?) {
        currentPlanId = plan.id
        watchRun(record.id)
        val result = attempt { deps.engine.start(plan, s, plan.hash, pass) }.getOrElse { StartResult.Refused(StartRefusal.NOT_CONNECTED) }
        mutableExtras.update { it.copy(starting = false) }
        when (result) {
            StartResult.Started -> {
                deps.ledger.record(plan)
                deps.sessionEnd.touched(record.id)
                val t = record.copy(wizard = record.wizard?.copy(step = 4, planId = plan.id, updatedAtMs = deps.clock.nowMs()))
                tv = t
                deps.tvs.upsert(t)
            }
            is StartResult.Refused -> when (result.reason) {
                StartRefusal.HASH_MISMATCH -> replan(record, fresh = true)
                StartRefusal.GATE_REQUIRED, StartRefusal.GATE_EXPIRED -> deps.gate.forget(record.id)
                StartRefusal.RUN_OPEN -> {
                    // The flow will not emit again for a run that is already paused, so its current
                    // state is shown now (its stop card, or the Working screen it is in).
                    val open = deps.engine.runState(record.id).value
                    open.planOrNull()?.let { currentPlanId = it.id; send(Ev.Run(record.id, open)) }
                }
                StartRefusal.NOT_CONNECTED -> send(Ev.Stop(record.id, card(StopCase.CONNECTION_LOST, plan.id, args = counts(plan, 0))))
                StartRefusal.WRONG_TV -> send(Ev.Stop(record.id, card(StopCase.IDENTITY_CHANGED, plan.id, args = mapOf("name" to record.nickname))))
            }
        }
    }

    private fun watchRun(tvId: TvId) {
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            deps.engine.runState(tvId).collect { r ->
                val id = r.planOrNull()?.id
                if (id != null && id == currentPlanId) send(Ev.Run(tvId, r))
            }
        }
    }

    private fun onRun(before: SetupState, ev: Ev.Run) {
        val label = runLabel(ev.state)
        val fresh = label != lastRunLog
        // A pause is logged by its coroutine below, with the failed op from the journal (B3).
        if (fresh) { lastRunLog = label; if (ev.state !is RunState.Paused) deps.log.line("setup: run $label") }
        when (val r = ev.state) {
            is RunState.Running -> mutableExtras.update { it.copy(rollingBack = false, chip = ChipState.WORKING) }
            is RunState.AwaitingConfirm -> if (before !is SetupState.Check) {
                effect(SetupEffect.HapticFirm)
                effect(SetupEffect.Notify(deps.copy.text(CopyKey.NOTIFY_LOOK)))
                mutableExtras.update { it.copy(highlightStop = false) }
            }
            is RunState.RollingBack -> mutableExtras.update { it.copy(rollingBack = true) }
            is RunState.Paused -> scope.launch { logPause(r); onPaused(ev.tvId, r) }
            is RunState.Finished -> scope.launch { onFinished(ev.tvId, r) }
            else -> {}
        }
    }

    private suspend fun onPaused(tvId: TvId, p: RunState.Paused) {
        if (undoRequested && p.reason == PauseReason.USER_STOP) {
            undoRequested = false
            val record = tv ?: return
            val pass = deps.gate.pass(record.id, record.nickname)
            if (pass != null || !GateKeeper.needsPass(p.plan)) { deps.engine.undoRun(tvId, pass); return }
        }
        val c = counts(p.plan, deps.ledger.progress(p.plan.id).doneDisables)
        when (p.reason) {
            PauseReason.CONNECTION_LOST -> {
                send(Ev.Stop(tvId, card(StopCase.CONNECTION_LOST, p.plan.id, args = c)))
                watchReconnect(tvId)
            }
            PauseReason.STEP_FAILED -> {
                val failed = p.plan.steps.getOrNull(p.at)?.op
                val case = if (failed is Op.Install || failed is Op.InstallStream || p.error is AdbError.Device) StopCase.INSTALL_FAILED else StopCase.STEP_FAILED
                send(Ev.Stop(tvId, card(case, p.plan.id, args = c)))
            }
            PauseReason.STALE -> {
                // The engine paused before any write; the run is still open, so it is ended here or the
                // new plan's start would be refused RUN_OPEN and the screen would stay on Found.
                mutableExtras.update { it.copy(staleNotice = true) }
                currentPlanId = null
                attempt { deps.engine.abandon(tvId) }
                tv?.let { replan(it, fresh = true) }
            }
            PauseReason.IN_DOUBT, PauseReason.USER_STOP -> send(Ev.Stop(tvId, resumeCard(tvId, p.plan.id, c, home = false, change = p.plan.kind != PlanKind.SETUP)))
        }
    }

    private fun watchReconnect(tvId: TvId) {
        reconnectJob?.cancel()
        mutableExtras.update { it.copy(reconnect = Reconnect.TRYING) }
        reconnectJob = scope.launch {
            val back = withTimeoutOrNull(60_000) {
                var live = deps.sessions.existing(tvId)
                while (live == null) { delay(deps.pollMs); live = deps.sessions.existing(tvId) }
                live.state.first { it is ConnectionState.Connected }
                session = live
                true
            } ?: false
            mutableExtras.update { it.copy(reconnect = if (back) Reconnect.BACK else Reconnect.GAVE_UP) }
        }
    }

    private suspend fun onFinished(tvId: TvId, f: RunState.Finished) {
        lastFinished = f
        mutableExtras.update { it.copy(rollingBack = false, highlightStop = false, reconnect = null) }
        if (f.home == HomeOutcome.VERIFY_FAILED) logHomeSwitchFailure(f.plan)
        when {
            // A Manage write resumed from the S4e card (after process death) ends in Manage, never on Done.
            f.plan.kind != PlanKind.SETUP -> effect(SetupEffect.Navigate("manage/${tvId.value}"))
            f.status == RunStatus.ROLLED_BACK -> send(Ev.Stop(tvId, card(StopCase.UNDONE, f.plan.id)))
            // Step R rebuilt from the journal (the plan file could not be trusted on resume) ends the
            // run with no steps of its own: the old home screen is back, and the card says so.
            f.status == RunStatus.ABANDONED && f.home == HomeOutcome.CONNECTION_LOST && f.plan.steps.isEmpty() ->
                send(Ev.Stop(tvId, homePutBackCard(f.plan.id)))
            f.status == RunStatus.ABANDONED -> effect(SetupEffect.Navigate("manage/${tvId.value}"))
            f.home != null && f.home != HomeOutcome.CONFIRMED -> send(Ev.Stop(tvId, homeCard(f, switchedOffSoFar(f.plan))))
            else -> showDone(tvId, f, homeOn = f.plan.sections.isNotEmpty() && f.home == HomeOutcome.CONFIRMED)
        }
    }

    private suspend fun showDone(tvId: TvId, f: RunState.Finished?, homeOn: Boolean) {
        val record = tv ?: deps.tvs.tvs.value.firstOrNull { it.id == tvId } ?: return
        val plan = f?.plan ?: setupPlan
        val progress = plan?.let { deps.ledger.progress(it.id) }
        val skipped = f?.skipped.orEmpty()
        val planned = plan?.steps.orEmpty().filter { it.op is Op.Disable }
        // A run that finished by itself and left no Done in the ledger counts its plan less its
        // skips. "Stop here" (the run abandoned) counts only what the ledger shows: with no step
        // done, nothing was switched off, whatever the plan said.
        val stopped = f == null || f.status == RunStatus.ABANDONED
        // QA of 0.4.5: after "Try again", what this Setup's runs switched off and left off, all of them.
        val soFar = if (plan != null && earlierRuns.isNotEmpty()) switchedOffSoFar(plan) else null
        val off = when {
            soFar != null && (stopped || (progress?.doneSteps ?: 0) > 0) -> soFar
            progress != null && progress.doneSteps > 0 -> progress.doneDisables
            stopped -> 0
            plan != null -> planned.size - skipped.count { it.op is Op.Disable }
            else -> 0
        }
        // RF-LAUNCHER-250 F1: a package the live check kept because it runs the TV's inputs gets its
        // own line on Done, never the generic "it's harmless" one.
        val kept = plan?.let { deps.ledger.keptTvInputs(it.id) }.orEmpty()
        val keptNames = skipped.filter { it.index in kept }.mapNotNull { (it.op as? Op.Disable)?.pkg }.map { nameOfPkg(it) }
        val skippedNames = skipped.filter { it.index !in kept }.mapNotNull { (it.op as? Op.Disable)?.pkg }.map { nameOfPkg(it) }
        val p = profile()
        val recommended = p == null || Quirk.HOME_TAKEOVER_SETUPWRAITH in p.quirks || p.tested?.rebootTested != true
        // beta3 QA: the restore point Undo everything goes back to is the first read (the baseline),
        // so Done names that one, and says when it is from an earlier run.
        val baseline = deps.snapshots.baseline(tvId)
        val restorePointAt = (baseline ?: deps.snapshots.latest(tvId))?.takenAtMs ?: deps.clock.nowMs()
        val restorePointEarlier = baseline != null && !probedBaseline
        val launcher = deps.launcherPin.bundled()
        // D15: the digest of the config.json this run pushed, so Manage can warn when it drifts.
        val pushed = plan?.let { pl -> deps.ledger.changes(tvId).lastOrNull { c -> c.planId == pl.id && (c.op as? Op.PushAsset)?.dest?.let { d -> d.dir == RemoteDir.OVERLAY && d.segments.last() == "config.json" } == true } }
        val digest = (pushed?.op as? Op.PushAsset)?.sha256
        val done = record.copy(
            wizard = null,
            launcher = if (homeOn) LauncherState(launcher.versionCode, launcher.signer, digest ?: record.launcher?.overlayDigest, digest ?: record.launcher?.ourOverlayDigest)
                else record.launcher?.let { l -> if (digest != null) l.copy(overlayDigest = digest, ourOverlayDigest = digest) else l },
            lastSeenAt = deps.clock.nowMs(),
        )
        tv = done
        deps.tvs.upsert(done)
        // RFC-CARDS-1: what the run pushed is what Manage starts from (dock, theme, cards).
        // RFC-LOOK-SETUP-1: a photo made in the look step is kept for the TV now that the TV has it;
        // when it cannot be kept, the picture stays as it was saved.
        // look2: the look the TV had before becomes the earlier look (EarlierLook): the one this
        // phone saved, or for a first Setup the Default look.
        setupHome?.takeIf { it.digest == digest }?.let { home ->
            val photo = home.photo
            val oldPhoto = deps.documents?.let { docs -> attempt { docs.read(OwnPicture.path(tvId)) { it } }.getOrNull() }
            val keptPhoto = photo != null && deps.documents?.let { docs -> attempt { OwnPicture.keep(docs, tvId, photo.jpeg) }.isSuccess } == true
            val choice = if (photo == null || keptPhoto) home.choice
                else home.choice.copy(picture = home.saved?.picture ?: LookPicture.NONE_ID, dim = home.saved?.dim)
            if (keptPhoto) newPhoto = null
            deps.documents?.let { docs -> attempt { EarlierLook.remember(docs, tvId, EarlierLook.firstLook(home.saved, choice.dock), choice, keptPhoto, oldPhoto) } }
            deps.homePrefs?.let { prefs -> attempt { prefs.save(tvId, choice) } }
        }
        // Issue #15: nothing switched off, no new home screen and no other change of this run: Done
        // says plainly that nothing changed. The TV curtain comes and goes, so it does not count,
        // unless this run installed it and its removal did not go through: it is still on the TV.
        val mine = plan?.let { pl -> deps.ledger.changes(tvId).filter { it.planId == pl.id } }.orEmpty()
        fun curtain(step: Int) = plan?.steps?.getOrNull(step)?.group == DefaultPlanner.GROUP_CURTAIN
        val curtainRemoval = plan?.steps?.lastOrNull { it.group == DefaultPlanner.GROUP_CURTAIN && it.op is Op.Uninstall }
        val curtainInstalled = mine.any { curtain(it.step) && (it.op is Op.Install || it.op is Op.InstallStream) }
        val curtainLeft = curtainInstalled && (curtainRemoval == null || mine.none { it.step == curtainRemoval.index })
        val changed = mine.any { !curtain(it.step) } || curtainLeft
        val why = when {
            planned.isEmpty() -> NothingWhy.NOTHING_PLANNED
            planned.all { p -> skipped.any { it.index == p.index } } -> NothingWhy.KEPT_ON
            else -> NothingWhy.STOPPED
        }
        // QA of 0.4.5: the switch was in the plan and the old home screen is back (the check ran out
        // of time, the person said no, the new one did not take over): Done says the home screen is
        // unchanged and why, never only "Your TV is ready".
        val homeKept = f?.home?.takeIf { !homeOn && it != HomeOutcome.CONFIRMED && plan?.sections?.isNotEmpty() == true }
        mutableExtras.update { it.copy(doneLines = skippedNames, doneKept = keptNames, doneNothing = off == 0 && !homeOn && !changed, doneNothingWhy = why,
            keepsDebugging = keepsDebugging(tvId), doneHomeKept = homeKept) }
        send(Ev.ShowDone(tvId, DoneSummary(off, homeOn, restorePointAt, skippedNames, recommended, restorePointEarlier)))
        session?.let { s -> mutableExtras.update { it.copy(restorePointPushed = deps.restorePoint.write(done, s, p)) } }
    }

    private suspend fun stopAndUndo() {
        val id = tvIdOf(state.value) ?: return
        undoRequested = true
        mutableExtras.update { it.copy(highlightStop = false) }
        deps.engine.stop(id)
    }

    private suspend fun carryOn() {
        val record = tv ?: return
        val id = record.id
        val plan = deps.engine.runState(id).value.planOrNull()
        val pass = if (plan == null || GateKeeper.needsPass(plan)) deps.gate.pass(id, record.nickname) ?: return else null
        currentPlanId = plan?.id ?: currentPlanId
        watchRun(id)
        reconnectJob?.cancel()
        mutableExtras.update { it.copy(reconnect = null) }
        deps.sessionEnd.touched(id)
        deps.engine.continueRun(id, pass)
    }

    private suspend fun undoWhatWeDid() {
        val record = tv ?: return
        val plan = deps.engine.runState(record.id).value.planOrNull()
        val pass = if (plan == null || GateKeeper.needsPass(plan)) deps.gate.pass(record.id, record.nickname) ?: return else null
        currentPlanId = plan?.id ?: currentPlanId
        watchRun(record.id)
        reconnectJob?.cancel()
        mutableExtras.update { it.copy(reconnect = null) }
        deps.sessionEnd.touched(record.id)
        deps.engine.undoRun(record.id, pass)
    }

    // "Try with the wizard off" (UX 4.7). Issue #375: putting the wizard into the choices never
    // worked, the planner refuses it there, and the refusal stayed with the TV's saved draft. The card
    // no longer offers it (homeCard); an intent that still arrives keeps the old home screen, as the
    // card's other button does, and never touches the draft.
    private suspend fun tryWizardOff() {
        deps.log.line("setup: the setup wizard is only switched off as part of the home screen switch; the old home screen stays")
        leaveOldHome()
    }

    // Issue #375: a saved choice the planner would refuse (the wizard put there by "Try with the
    // wizard off" in 0.3.4 and 0.3.5, or an app that became a home screen or a protected package
    // since it was chosen) refused every plan for the TV, for good. It is dropped from the draft, so
    // the plan is made without it: one switch-off fewer, never one more. The draft is saved again.
    private suspend fun withoutRefusedChoices(record: Tv, draft: WizardDraft, snap: Snapshot): WizardDraft {
        val stocks = when (val r = resolution) {
            is ProfileResolution.Exact -> r.profile.stockLaunchers
            is ProfileResolution.BrandSureSet -> r.stockLaunchers
            is ProfileResolution.None -> r.stockLaunchers
            null -> emptyList()
        }
        val refused = draft.extraOff.filter { pkg ->
            pkg in snap.packages && SwitchOffRule.reason(pkg, snap.packages[pkg], profile(), snap, stocks) != null
        }.toSet()
        if (refused.isEmpty()) return draft
        val cleaned = draft.copy(extraOff = draft.extraOff - refused, updatedAtMs = deps.clock.nowMs())
        val updated = record.copy(wizard = cleaned)
        if (tv?.id == record.id) tv = updated
        attempt { deps.tvs.upsert(updated) }
        deps.log.line("setup: ${refused.size} earlier choice${if (refused.size == 1) "" else "s"} left out, now protected on this TV: " +
            refused.sortedBy { it.value }.joinToString(", ") { LogCodes.reason(it.value, snap, deps.facts) })
        mutableExtras.update { it.copy(draft = cleaned) }
        return cleaned
    }

    // Plans again from a fresh snapshot (the debloat part is already done) and starts at once.
    // QA of 0.4.5: only a plan made now from that read is started. When none could be made (the TV
    // went away, the plan was refused) the plan of the run that just ended is never started again;
    // the card the replan showed stays, or the run's own card when there is none.
    private suspend fun retakeover(record: Tv) {
        val s = session ?: return
        val before = setupPlan
        setupPlan = null
        replan(record.copy(wizard = record.wizard ?: draftFor(record)), fresh = true)
        val plan = setupPlan ?: run { setupPlan = before; return }
        lastFinished?.plan?.takeIf { it.kind == PlanKind.SETUP && it.id != plan.id && it.id !in earlierRuns }?.let { earlierRuns += it.id }
        val pass = if (GateKeeper.needsPass(plan)) deps.gate.pass(record.id, record.nickname) ?: return else null
        startPlan(record, s, plan, pass)
    }

    private fun draftFor(record: Tv) = WizardDraft(3, record.level, emptySet(), emptySet(), null, null, false, null, deps.clock.nowMs())

    private suspend fun leaveOldHome() {
        val id = tvIdOf(state.value) ?: return
        showDone(id, lastFinished, homeOn = false)
    }

    private fun onStopAction(s: SetupState, action: StopAction) {
        val case = (s as? SetupState.Stopped)?.card?.case ?: mutableExtras.value.card?.case ?: return
        when (case) {
            // D3: on the Wi-Fi card the primary tries the switch again (Wi-Fi asked afresh), never
            // with the wizard off.
            StopCase.HOME_SETUPWRAITH -> when {
                action != StopAction.PRIMARY -> send(SetupIntent.LeaveOldHome)
                wifiCard -> scope.launch { tv?.let { retakeover(it) } }
                else -> send(SetupIntent.TryWithWizardOff)
            }
            StopCase.HOME_OTHER_LAUNCHER -> if (action == StopAction.PRIMARY) scope.launch { tv?.let { retakeover(it) } } else send(SetupIntent.LeaveOldHome)
            StopCase.HOME_UNKNOWN -> if (action == StopAction.PRIMARY) shareDiagnostics() else send(SetupIntent.LeaveOldHome)
            StopCase.HOME_USER_SAID_NO -> if (action == StopAction.PRIMARY) scope.launch { tv?.let { retakeover(it) } } else shareDiagnostics()
            // QA of 0.4.5: the card's primary is "OK" and its secondary "Try again" (stopKeys); the
            // two were the wrong way round, so "Try again" went straight to Done with the old home
            // screen on the TV, and "OK" started the switch again.
            StopCase.HOME_TIMED_OUT -> if (action == StopAction.SECONDARY) scope.launch { tv?.let { retakeover(it) } } else send(SetupIntent.LeaveOldHome)
            StopCase.HOME_SETTINGS_OPEN -> if (action == StopAction.PRIMARY) scope.launch { tv?.let { retakeover(it) } } else send(SetupIntent.LeaveOldHome)
            StopCase.CONNECTION_LOST, StopCase.STEP_FAILED -> when (action) {
                StopAction.PRIMARY -> send(SetupIntent.CarryOn)
                StopAction.SECONDARY -> scope.launch { abandonToDone() }
                StopAction.TERTIARY -> send(SetupIntent.UndoWhatWeDid)
            }
            StopCase.HOME_PUT_BACK -> when {
                action == StopAction.PRIMARY -> scope.launch { tv?.let { retakeover(it) } }
                lastFinished?.status == RunStatus.ABANDONED -> tvIdOf(s)?.let { effect(SetupEffect.Navigate("manage/${it.value}")) }
                else -> send(SetupIntent.LeaveOldHome)
            }
            StopCase.RESUME_AFTER_DEATH -> when (action) {
                StopAction.PRIMARY -> send(SetupIntent.CarryOn)
                StopAction.SECONDARY -> send(SetupIntent.UndoWhatWeDid)
                StopAction.TERTIARY -> scope.launch { tvIdOf(s)?.let { id -> abandonRun(id, (s as? SetupState.Stopped)?.card?.planId) } }
            }
            StopCase.INSTALL_FAILED -> scope.launch { abandonToDone() }
            StopCase.UNDONE -> tvIdOf(s)?.let { effect(SetupEffect.Navigate("manage/${it.value}")) }
            StopCase.PROBE_FAILED, StopCase.PLAN_REFUSED -> if (action == StopAction.PRIMARY) scope.launch { probe() } else scope.launch { backFromRefusal(s) }
            StopCase.NOT_BACK_AFTER_RESTART -> if (action == StopAction.PRIMARY) (state.value as? SetupState.Done)?.let { d -> restartJob = scope.launch { awaitBack(d.tvId, deps.clock.nowMs()) } } else shareDiagnostics()
            StopCase.TV_NOT_FOUND -> if (action == StopAction.PRIMARY) { mutableExtras.update { it.copy(card = null) }; discoveryJob?.cancel(); discoveryJob = null; afterTransition(state.value) }
                else mutableExtras.update { it.copy(card = null, sheet = Sheet.ADDRESS) }
            StopCase.WRONG_NETWORK, StopCase.IDENTITY_CHANGED, StopCase.TV_NOT_SUPPORTED -> { mutableExtras.update { it.copy(card = null) }; send(Ev.ToWelcome) }
            StopCase.TV_REFUSES_HOME, StopCase.PAIRING_NEEDED -> send(Ev.ToWelcome)
        }
    }

    // Issue #375: "Back" on a card that stopped before any change. On a TV this app already changed,
    // it goes to Manage, where Restore point and Undo everything are, never only to Welcome, which
    // leads to the same stop again. A TV with no change of ours goes to Welcome as before.
    private suspend fun backFromRefusal(s: SetupState) {
        val id = tvIdOf(s) ?: tv?.id
        val changed = id != null && attempt { deps.ledger.changes(id).isNotEmpty() }.getOrDefault(false)
        if (id != null && changed) effect(SetupEffect.Navigate("manage/${id.value}")) else send(Ev.ToWelcome)
    }

    // "Stop here" and the install failure: keep what was done, abandon the rest, show Done truthfully.
    // The engine's abandon returns once its step R has settled (A5, issue #2): Done only when the run
    // really ended; a run still paused (the TV's own home screen is not back) shows its card again.
    private suspend fun abandonToDone() {
        val id = tvIdOf(state.value) ?: return
        val plan = deps.engine.runState(id).value.planOrNull()
        if (!abandonRun(id, plan?.id ?: (state.value as? SetupState.Stopped)?.card?.planId)) return
        val after = deps.engine.runState(id).value
        if (after is RunState.Paused) { logPause(after); onPaused(id, after); return }
        showDone(id, plan?.let { RunState.Finished(it, RunStatus.ABANDONED, emptyList(), null) }, homeOn = false)
    }

    // Ends the open run. With a HOME section open the engine must run step R first, and while the
    // TV is away its abandon does nothing (R1 F5, R-11): the run stays open. The card then says to
    // reconnect, and the abandon runs again once the TV answers (after process death the run is
    // loaded through resume first). False when the abandon is waiting for the TV.
    private suspend fun abandonRun(id: TvId, planId: PlanId?): Boolean {
        val open = planId != null && deps.ledger.progress(planId).openSection
        val live = (session?.takeIf { it.tvId == id } ?: deps.sessions.existing(id))?.state?.value is ConnectionState.Connected
        if (open && !live) {
            val c = (state.value as? SetupState.Stopped)?.card ?: card(StopCase.CONNECTION_LOST, planId, args = mapOf("done" to 0, "total" to 0))
            send(Ev.Stop(id, c.copy(body = deps.copy.text(CopyKey.STOP_RECONNECT_HOME_BODY, mapOf("tv" to (tv?.nickname ?: ""))))))
            if (abandonJob?.isActive != true) abandonJob = scope.launch { abandonWhenBack(id) }
            return false
        }
        attempt { deps.engine.abandon(id) }
        return true
    }

    private suspend fun abandonWhenBack(id: TvId) {
        mutableExtras.update { it.copy(reconnect = Reconnect.TRYING) }
        val s = when (val r = deps.access.session(id)) {
            is AccessResult.Ok -> r.session
            else -> { mutableExtras.update { it.copy(reconnect = Reconnect.GAVE_UP) }; return }
        }
        session = s
        mutableExtras.update { it.copy(reconnect = null, chip = ChipState.CONNECTED) }
        watchRun(id)
        if (deps.engine.runState(id).value !is RunState.Paused) attempt { deps.engine.resume(id, s) }
        if (deps.engine.runState(id).value is RunState.Paused) attempt { deps.engine.abandon(id) }
    }

    // 0.3.2 B2: the cards that offered "Share diagnostics" open the "Report a problem" preview, which
    // shows the redacted text first and copies or shares it only when the person says so.
    // 0.4.6 (REPORTING-PROPOSAL 2.2): every card where a run or a connect stopped badly opens the
    // GitHub form at once, with what the card said (ReportContext) and the TV as this phone last
    // read it; the app copies the whole report too, and nothing is public until the person posts.
    // A connect has no TV to read, so the card sends the phone's side instead: how the TV was found,
    // the port, the named error and the socket's reason.
    private fun shareDiagnostics(title: String? = null) {
        val s = state.value
        val card = (s as? SetupState.Stopped)?.card ?: mutableExtras.value.card
        val names = arrayOf(tv?.nickname, knownTv?.nickname, doorTv?.friendlyName)
        val connect = s is SetupState.Welcome || s is SetupState.Picker || s is SetupState.OpenDoor || s is SetupState.PairingNeeded
        val err = mutableExtras.value.connectError
        val case = when {
            card != null -> card.case.name
            s is SetupState.PairingNeeded -> StopCase.PAIRING_NEEDED.name
            err != null -> "CONNECT_FAILED"
            else -> s::class.simpleName?.uppercase() ?: "SETUP"
        }
        val shown = ReportContext.named(title ?: card?.title.orEmpty(), *names)
        val ctx = if (!connect) ReportContext(screen = "Setup", card = shown, case = case) else {
            val found = (s as? SetupState.OpenDoor)?.tv ?: (s as? SetupState.PairingNeeded)?.tv ?: doorTv
            val error = when {
                card?.case == StopCase.TV_NOT_SUPPORTED -> LogCodes.of(AdbError.Transport.NoShellV2)
                s is SetupState.PairingNeeded -> "pairing_needed"
                else -> err?.kind
            }
            ReportContext(
                screen = "Connect", card = shown, case = case, connect = true, error = error,
                // The port the app connects to (connect() opens Endpoint.PlainTcp at its default).
                found = found?.let { f -> foundBy(f) }, port = Endpoint.PlainTcp(found?.host.orEmpty()).port,
                detail = if (err != null) runCatching { deps.connectDetail() }.getOrNull() else null,
            )
        }
        // A connect to a TV this phone has read before still sends its summary.
        val id = if (connect) knownTv?.id else tvIdOf(s)
        effect(SetupEffect.Navigate(IssueRoute.of(id, ctx)))
    }

    // "adb_mdns+google_cast, pairing service seen": what discovery saw, never an address. Not the
    // port's state: the door polls the port itself, so the found record's flag can be an old one.
    private fun foundBy(f: FoundTv): String = listOfNotNull(
        f.sources.joinToString("+") { if (it == DiscoverySource.MANUAL) "address typed" else it.name.lowercase() }.ifEmpty { "found" },
        f.pairingService?.let { "pairing service seen" },
        f.connectService?.let { "wireless debugging seen" },
    ).joinToString(", ")

    // Done (S5)

    // D24 restart test, UX 4.8: reboot, wait, 3 minute note, 5 minute card; debugging stays on.
    // The reboot is a REBOOT plan (RFC-W8-5): journaled like every write and never gated; the
    // engine's step reboots and waits up to 5 minutes for the boot.
    private suspend fun restart(d: SetupState.Done) {
        val s = session ?: return
        val record = tv ?: deps.tvs.tvs.value.firstOrNull { it.id == d.tvId } ?: return
        val since = deps.clock.nowMs()
        send(Ev.RestartUpdate(RestartState.Restarting(since)))
        mutableExtras.update { it.copy(restartSlow = false, restartReenabled = 0, card = null) }
        val plan = attempt {
            val snap = snapshot ?: deps.runner.snapshotFor(record, s)
            (deps.runner.plan(record, PlanRequest.Reboot, snap) as? PlanResult.Ok)?.plan
        }.getOrNull() ?: run { send(Ev.RestartUpdate(null)); return }
        val start = deps.clock.monotonicMs()
        val slow = scope.launch { delay(3 * 60_000); mutableExtras.update { it.copy(restartSlow = true) } }
        val outcome = attempt { deps.runner.run(record, plan, s, onStarted = { deps.sessionEnd.touched(record.id) }) }.getOrNull()
        slow.cancel()
        val after = maxOf(deps.clock.monotonicMs() - start, 0)
        when {
            outcome is RunOutcome.Finished && outcome.state.status == RunStatus.COMPLETED && outcome.state.skipped.isEmpty() -> backAfterRestart(record, s, after)
            outcome is RunOutcome.Paused -> {
                // The boot wait ran out: the reboot step is in doubt and the run stays open. Nothing
                // on it needs undoing, so it is ended, or every later write would be refused RUN_OPEN.
                attempt { deps.engine.abandon(record.id) }
                notBack(after)
            }
            else -> send(Ev.RestartUpdate(null))   // the reboot was refused or never sent
        }
    }

    private fun notBack(afterMs: Long) {
        send(Ev.RestartUpdate(RestartState.NotBack(afterMs)))
        mutableExtras.update { it.copy(card = card(StopCase.NOT_BACK_AFTER_RESTART, null)) }
    }

    // After the restart: things that came back on are switched off again (UX 4.8).
    private suspend fun backAfterRestart(record: Tv, s: TvSession, afterMs: Long) {
        mutableExtras.update { it.copy(card = null) }
        val came = attempt { switchOffLeftovers(record, s) }.getOrDefault(0)
        mutableExtras.update { it.copy(restartReenabled = came) }
        send(Ev.RestartUpdate(RestartState.Back(afterMs)))
    }

    // "It's on again" on the not-back card: a wait for the boot, no write.
    private suspend fun awaitBack(tvId: TvId, since: Long) {
        val s = session ?: return
        send(Ev.RestartUpdate(RestartState.Restarting(since)))
        val start = deps.clock.monotonicMs()
        var outcome = attempt { s.awaitBoot(3 * 60_000) }.getOrElse { BootOutcome.Timeout }
        if (outcome is BootOutcome.Timeout) {
            mutableExtras.update { it.copy(restartSlow = true) }
            outcome = attempt { s.awaitBoot(2 * 60_000) }.getOrElse { BootOutcome.Timeout }
        }
        val after = maxOf(deps.clock.monotonicMs() - start, 0)
        if (outcome is BootOutcome.Timeout) { notBack(after); return }
        val record = tv
        if (record == null) { mutableExtras.update { it.copy(card = null) }; send(Ev.RestartUpdate(RestartState.Back(after))); return }
        backAfterRestart(record, s, after)
    }

    private suspend fun switchOffLeftovers(record: Tv, s: TvSession): Int {
        val expected = deps.ledger.switchedOffByUs(record.id).toSet()
        if (expected.isEmpty()) return 0
        val snap = deps.runner.freshSnapshot(s)
        val plan = (deps.runner.plan(record, PlanRequest.Leftovers(expected), snap) as? PlanResult.Ok)?.plan ?: return 0
        val n = plan.steps.count { it.op is Op.Disable }
        if (n == 0) return 0
        deps.runner.run(record, plan, s)
        return n
    }

    private suspend fun restoreNote() {
        val record = tv ?: return
        val changes = deps.ledger.changes(record.id)
        val stock = profile()?.stockLaunchers?.firstOrNull()?.pkg
        val stockName = stock?.let { nameOfPkg(it) } ?: stock?.value ?: ""
        val text = deps.restoreNote.text(record, changes, deps.facts, stockName, deps.copy.date(deps.clock.nowMs()))
        effect(SetupEffect.Share(text))
    }

    // D7: "Keep debugging on" was chosen for this TV (the saved draft, the draft on screen or this
    // session's choice). Done's line and Finish both read it, so the line says what Finish will do.
    private fun keepsDebugging(tvId: TvId): Boolean =
        tv?.wizard?.keepDebuggingOn == true || mutableExtras.value.draft?.keepDebuggingOn == true || deps.sessionEnd.keepsDebuggingOn(tvId)

    // D7: Finish switches debugging off unless "Keep debugging on" was chosen, then opens Manage.
    private suspend fun finish(tvId: TvId) {
        val keep = keepsDebugging(tvId)
        if (!keep) {
            mutableExtras.update { it.copy(finishing = Finishing.SWITCHING) }
            // "done" and the "Debugging off" chip only when a failed probe confirmed it (D7); otherwise
            // Manage opens at once and its card shows what the record says.
            if (deps.sessionEnd.switchOff(tvId) == DebugState.OFF) {
                mutableExtras.update { it.copy(finishing = Finishing.DONE, chip = ChipState.DEBUGGING_OFF) }
                delay(600)
            }
        }
        effect(SetupEffect.Navigate("manage/${tvId.value}"))
    }

    // start modes

    private suspend fun continueDraft(tvId: TvId) {
        val record = deps.tvs.tvs.value.firstOrNull { it.id == tvId } ?: run { send(Ev.ToWelcome); return }
        tv = record
        mutableExtras.update { it.copy(tvName = record.nickname, draft = record.wizard) }
        send(Ev.Door(foundOf(record, open = true)))
    }

    private suspend fun resumeRun(tvId: TvId, planId: PlanId) {
        val record = deps.tvs.tvs.value.firstOrNull { it.id == tvId } ?: run { send(Ev.ToWelcome); return }
        tv = record
        currentPlanId = planId
        mutableExtras.update { it.copy(tvName = record.nickname, chip = ChipState.WAITING) }
        val entry = deps.ledger.entry(tvId, planId)
        val progress = deps.ledger.progress(planId)
        val args = mapOf("tv" to record.nickname, "done" to progress.doneDisables, "total" to (entry?.disables ?: progress.doneDisables))
        val manageKind = entry != null && entry.kind != PlanKind.SETUP
        send(Ev.Stop(tvId, resumeCard(tvId, planId, args, home = progress.openSection, change = manageKind)))
        // The process that ran the steps is gone and with it the touched set: the journal says
        // writes reached this TV, so D7's background and session-close rules apply to it again.
        if (progress.doneSteps > 0 || progress.openSection) deps.sessionEnd.touched(tvId)
        when (val r = deps.access.session(tvId)) {
            is AccessResult.Ok -> {
                session = r.session
                watchRun(tvId)
                mutableExtras.update { it.copy(chip = ChipState.CONNECTED) }
                attempt { deps.engine.resume(tvId, r.session) }
            }
            is AccessResult.Failed -> {
                mutableExtras.update { it.copy(chip = ChipState.OFFLINE) }
                if (r.error is AdbError.Policy.WrongTv) send(Ev.Stop(tvId, card(StopCase.IDENTITY_CHANGED, planId, args = mapOf("name" to record.nickname))))
            }
            else -> mutableExtras.update { it.copy(chip = ChipState.OFFLINE) }
        }
    }

    // copy and small helpers

    // S4e. `change`: the open run is a Manage write (a put back, a theme, a module), not the setup,
    // so the card does not say "setting up" (R5-W8 O2).
    private fun resumeCard(tvId: TvId, planId: PlanId?, args: Map<String, Any>, home: Boolean, change: Boolean = false): StopCard {
        val a = args + ("tv" to (tv?.nickname ?: deps.tvs.tvs.value.firstOrNull { it.id == tvId }?.nickname ?: ""))
        val c = card(StopCase.RESUME_AFTER_DEATH, planId, args = a, body = if (home) CopyKey.STOP_RESUME_HOME_BODY else if (change) CopyKey.STOP_RESUME_CHANGE_BODY else null)
        return if (change) c.copy(title = deps.copy.text(CopyKey.STOP_RESUME_CHANGE_TITLE, a)) else c
    }

    // "We put the old home screen back": step R ran after the connection was lost (UX 4.7, 4.9).
    private fun homePutBackCard(planId: PlanId?): StopCard = card(StopCase.HOME_PUT_BACK, planId, args = mapOf("tv" to (tv?.nickname ?: "")))

    // The switch-offs this Setup's runs made and left on the TV (the journal is the record).
    private suspend fun switchedOffSoFar(plan: Plan): Int =
        (earlierRuns + plan.id).distinct().flatMap { id -> attempt { deps.ledger.disabledBy(id) }.getOrDefault(emptySet()) }.toSet().size

    private fun homeCard(f: RunState.Finished, off: Int = 0): StopCard {
        val other = otherLauncherName()
        // Issue #375: "Try with the wizard off" is never offered. The planner switches the wizard off
        // only inside the home screen switch, on a profile with the wizard quirk, and then this run
        // already did; anywhere else it refuses the wizard as a choice (REASON_HOME_SCREEN, or
        // REASON_WIZARD_SECTION_ONLY). Offering it put the wizard into the saved choices and every
        // later plan for the TV was refused. The wizard card stays for the Wi-Fi (D3) only; any other
        // switch the wizard took is the plain "it did not take over" card.
        val wraith = wraithClaims() && wizardWifi != WizardWifi.NOT_OWNER
        // Issue #629: a Settings page in front (the new home screen's network or Settings button, or
        // the remote's Settings key) is not a home screen that failed; the card says so. FallbackHome,
        // also in the settings package, is the TV having no home at all, so it keeps the other cards.
        val inFront = if (f.home == HomeOutcome.WRONG_FOCUS) deps.wrongFocus(f.plan.tvId) else null
        val settingsOpen = inFront != null && inFront.pkg.value in SETTINGS_PACKAGES && !inFront.cls.endsWith("FallbackHome")
        val case = when (f.home) {
            HomeOutcome.USER_SAID_NO -> StopCase.HOME_USER_SAID_NO
            HomeOutcome.TIMED_OUT -> StopCase.HOME_TIMED_OUT
            HomeOutcome.CONNECTION_LOST -> StopCase.HOME_PUT_BACK
            else -> when {
                settingsOpen -> StopCase.HOME_SETTINGS_OPEN
                wraith -> StopCase.HOME_SETUPWRAITH
                other != null -> StopCase.HOME_OTHER_LAUNCHER
                else -> StopCase.HOME_UNKNOWN
            }
        }
        if (case == StopCase.HOME_PUT_BACK) return homePutBackCard(f.plan.id)
        // D3: the wizard holds Home, but it may have saved the TV's Wi-Fi, so switching it off is not
        // offered; the card says why and what to do instead.
        wifiCard = case == StopCase.HOME_SETUPWRAITH
        if (wifiCard) {
            // The owner's sentence only when the TV said the wizard saved the Wi-Fi; never a claim the
            // app could not check (RF-LAUNCHER-240 F6).
            val body = if (wizardWifi == WizardWifi.OWNER) CopyKey.STOP_HOME_SETUPWRAITH_WIFI_BODY else CopyKey.STOP_HOME_SETUPWRAITH_WIFI_UNKNOWN_BODY
            return card(case, f.plan.id, body = body, primary = CopyKey.STOP_HOME_SETUPWRAITH_WIFI_PRIMARY)
        }
        // QA of 0.4.5: "Your TV is as it was" only when nothing stayed switched off; otherwise the
        // card says how many things are still off and that the home screen is as it was.
        if (case == StopCase.HOME_TIMED_OUT && off > 0) return card(case, f.plan.id, args = mapOf("launcher" to (other ?: ""), "count" to off), body = CopyKey.STOP_HOME_TIMED_OUT_BODY_OFF)
        return card(case, f.plan.id, args = mapOf("launcher" to (other ?: "")))
    }

    private fun wraithClaims(): Boolean {
        val claimants = snapshot?.home?.claimants?.map { it.component.pkg }.orEmpty()
        val wraith = profile()?.setupWraith
        return (wraith != null && wraith in claimants) || claimants.any { "setupwraith" in it.value.lowercase() }
    }

    // A launcher of someone else's that answers Home. Not one: the setup wizard, the settings apps,
    // com.android.provision, and a vendor's FallbackHome (com.tcl.keycustomfunctionservice/.FallbackHome),
    // a placeholder the firmware shows when no launcher is enabled (upstream #435).
    private fun otherLauncherName(): String? {
        val stock = profile()?.stockLaunchers?.map { it.pkg }.orEmpty().toSet()
        val wraith = profile()?.setupWraith
        val other = snapshot?.home?.claimants?.map { it.component }
            // #481's TCL Percee TV: com.android.boot.fallbackhome/.FallbackHomeCustomizer is one too.
            ?.filter { "FallbackHome" !in it.cls }?.map { it.pkg }
            ?.firstOrNull {
                it != Launcher.pkg && it !in stock && it != wraith && it.value != "com.android.provision" &&
                    "setupwraith" !in it.value.lowercase() && "settings" !in it.value.lowercase()
            }
            ?: return null
        return nameOfPkg(other)
    }

    private fun nameOfPkg(pkg: PackageName): String =
        AppNames.of(pkg, deps.facts, snapshot?.packages?.get(pkg)?.label)

    // Stable codes only (LogCodes): an AdbError's own text may carry ids, so it never reaches a log,
    // and a class name is R8's in a release build ("f3" in issue #2).
    private fun errorName(e: AdbError): String = LogCodes.of(e)

    // 0.3.2 B3: "paused STEP_FAILED at step 72: undo step failed: Enable com.amazon.tv.launcher:
    // nonzero_exit(1) <stderr head>". The failed op comes from the run's journal; its stderr is one
    // cleaned line (the app's redactor runs over the whole line too).
    private suspend fun logPause(p: RunState.Paused) {
        if (p === lastPauseLogged) return
        lastPauseLogged = p   // before the suspension, so a second caller sees it
        deps.log.line("setup: run ${pauseLine(p)}")
    }

    // 0.4.6: the same words as a report's step line (RunLines).
    private suspend fun pauseLine(p: RunState.Paused): String = RunLines.pause(p, deps.ledger)

    // Issue #338: the step the home screen switch stopped at, with its exit code and the head of the
    // TV's answer, the way a pause line names its step (0.3.2 B3).
    private suspend fun logHomeSwitchFailure(plan: Plan) {
        val failed = attempt { deps.ledger.homeSwitchFailure(plan) }.getOrNull() ?: return
        val code = failed.exit?.let { "exit $it" } ?: "failed"
        val head = LogCodes.clean(failed.stderrHead, 160).takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
        deps.log.line("setup: home switch stopped at ${LogCodes.op(failed.op)}: $code$head")
    }

    private fun runLabel(r: RunState): String = RunLines.label(r)

    private fun counts(plan: Plan, done: Int): Map<String, Any> = mapOf("done" to done, "total" to plan.steps.count { it.op is Op.Disable })

    private fun card(case: StopCase, planId: PlanId?, args: Map<String, Any> = emptyMap(), body: CopyKey? = null, primary: CopyKey? = null): StopCard {
        val k = stopKeys(case)
        val c = deps.copy
        return StopCard(case, c.text(k.title, args), c.text(body ?: k.body, args), c.text(primary ?: k.primary, args),
            k.secondary?.let { c.text(it, args) }, planId, k.tertiary?.let { c.text(it, args) })
    }

    private fun tvIdOf(s: SetupState): TvId? = when (s) {
        is SetupState.Probing -> s.tvId
        is SetupState.Found -> s.tvId
        is SetupState.Working -> s.tvId
        is SetupState.Check -> s.tvId
        is SetupState.Stopped -> s.tvId
        is SetupState.Done -> s.tvId
        else -> null
    } ?: tv?.id

    private fun nameOf(s: SetupState): String? = when (s) {
        is SetupState.OpenDoor -> s.tv.friendlyName ?: s.tv.model ?: tv?.nickname
        is SetupState.PairingNeeded -> s.tv.friendlyName ?: s.tv.model
        is SetupState.Welcome, is SetupState.Picker -> null
        else -> tv?.nickname
    }

    private fun chipOf(s: SetupState): ChipState? = when (s) {
        is SetupState.Welcome, is SetupState.Picker -> null
        is SetupState.OpenDoor -> if (s.phase == DoorPhase.CONNECTED || s.phase == DoorPhase.IDENTITY) ChipState.CONNECTED else ChipState.WAITING
        is SetupState.PairingNeeded -> ChipState.WAITING
        is SetupState.Working -> ChipState.WORKING
        is SetupState.Stopped -> if (s.card.case == StopCase.CONNECTION_LOST) ChipState.OFFLINE else mutableExtras.value.chip
        else -> if (mutableExtras.value.finishing == Finishing.DONE) ChipState.DEBUGGING_OFF else ChipState.CONNECTED
    }

    companion object {
        // At most this many skipped switch-offs are named on the run's finish line.
        const val SKIPS_LOGGED = 8
        // At most this many reasons of a refused plan are logged.
        const val REASONS_LOGGED = 8

        fun initialState(start: SetupStart): SetupState = when (start) {
            SetupStart.Fresh -> SetupState.Welcome(emptyList(), onWifi = true, looking = true)
            is SetupStart.Continue -> SetupState.Probing(start.tvId, ProbeStage.FACTS, null)
            is SetupStart.Resume -> SetupState.Probing(start.tvId, ProbeStage.FACTS, null)
        }

        fun placeholderTv() = FoundTv("", null, null, emptySet(), null, null, null, 0)

        fun manualTv(ip: String) = FoundTv(ip, null, null, setOf(DiscoverySource.MANUAL), null, null, null, 0)

        fun foundOf(tv: Tv, open: Boolean? = null) = FoundTv(
            host = tv.endpoints.firstOrNull { it is Endpoint.PlainTcp }?.host ?: tv.endpoints.firstOrNull()?.host ?: "",
            friendlyName = tv.nickname, model = tv.facts.model, sources = setOf(DiscoverySource.MANUAL),
            port5555Open = open, pairingService = null, connectService = null, lastSeenMs = tv.lastSeenAt,
        )

        // One line per op for "Show commands" (D21); a transfer shows as the sync line planHash uses.
        fun commandsOf(plan: Plan): List<String> = plan.steps.flatMap { step ->
            val lines = step.rendered.lines.map { it.text }
            val t = step.rendered.transfer
            lines + listOfNotNull(when (t) {
                is Transfer.Push -> "sync:${t.dest.absolute} ${t.src.sha256.hex}"
                is Transfer.Pull -> "sync:${t.src.absolute} ${t.maxBytes}"
                is Transfer.StreamInstall -> "sync:${t.src.path} ${t.size}"
                null -> null
            })
        }
    }
}

// The TV's settings apps (issue #629), as the engine's home check names them.
private val SETTINGS_PACKAGES = setOf("com.android.tv.settings", "com.android.settings")

internal data class StopKeys(val title: CopyKey, val body: CopyKey, val primary: CopyKey, val secondary: CopyKey?, val tertiary: CopyKey? = null)

internal fun stopKeys(case: StopCase): StopKeys = when (case) {
    StopCase.HOME_SETUPWRAITH -> StopKeys(CopyKey.STOP_HOME_SETUPWRAITH_TITLE, CopyKey.STOP_HOME_SETUPWRAITH_BODY, CopyKey.STOP_HOME_SETUPWRAITH_PRIMARY, CopyKey.STOP_HOME_SETUPWRAITH_SECONDARY)
    StopCase.HOME_OTHER_LAUNCHER -> StopKeys(CopyKey.STOP_HOME_OTHER_LAUNCHER_TITLE, CopyKey.STOP_HOME_OTHER_LAUNCHER_BODY, CopyKey.STOP_HOME_OTHER_LAUNCHER_PRIMARY, CopyKey.STOP_HOME_OTHER_LAUNCHER_SECONDARY)
    StopCase.HOME_UNKNOWN -> StopKeys(CopyKey.STOP_HOME_UNKNOWN_TITLE, CopyKey.STOP_HOME_UNKNOWN_BODY, CopyKey.STOP_HOME_UNKNOWN_PRIMARY, CopyKey.STOP_HOME_UNKNOWN_SECONDARY)
    StopCase.HOME_USER_SAID_NO -> StopKeys(CopyKey.STOP_HOME_USER_SAID_NO_TITLE, CopyKey.STOP_HOME_USER_SAID_NO_BODY, CopyKey.STOP_HOME_USER_SAID_NO_PRIMARY, CopyKey.STOP_HOME_USER_SAID_NO_SECONDARY)
    StopCase.HOME_TIMED_OUT -> StopKeys(CopyKey.STOP_HOME_TIMED_OUT_TITLE, CopyKey.STOP_HOME_TIMED_OUT_BODY, CopyKey.STOP_HOME_TIMED_OUT_PRIMARY, CopyKey.STOP_HOME_TIMED_OUT_SECONDARY)
    // Issue #629: "Try again" and "Leave the old home screen", with the Settings sentence.
    StopCase.HOME_SETTINGS_OPEN -> StopKeys(CopyKey.STOP_HOME_UNKNOWN_TITLE, CopyKey.STOP_HOME_SETTINGS_OPEN_BODY, CopyKey.STOP_HOME_USER_SAID_NO_PRIMARY, CopyKey.STOP_HOME_SETUPWRAITH_SECONDARY)
    StopCase.CONNECTION_LOST -> StopKeys(CopyKey.STOP_CONNECTION_LOST_TITLE, CopyKey.STOP_CONNECTION_LOST_BODY, CopyKey.STOP_CONNECTION_LOST_PRIMARY, CopyKey.STOP_CONNECTION_LOST_SECONDARY, CopyKey.STOP_CONNECTION_LOST_TERTIARY)
    StopCase.STEP_FAILED -> StopKeys(CopyKey.STOP_STEP_FAILED_TITLE, CopyKey.STOP_STEP_FAILED_BODY, CopyKey.STOP_CONNECTION_LOST_PRIMARY, CopyKey.STOP_CONNECTION_LOST_SECONDARY, CopyKey.STOP_CONNECTION_LOST_TERTIARY)
    StopCase.RESUME_AFTER_DEATH -> StopKeys(CopyKey.STOP_RESUME_TITLE, CopyKey.STOP_RESUME_BODY, CopyKey.STOP_RESUME_PRIMARY, CopyKey.STOP_RESUME_SECONDARY, CopyKey.STOP_RESUME_TERTIARY)
    StopCase.INSTALL_FAILED -> StopKeys(CopyKey.STOP_INSTALL_FAILED_TITLE, CopyKey.STOP_INSTALL_FAILED_BODY, CopyKey.STOP_INSTALL_FAILED_PRIMARY, null)
    StopCase.UNDONE -> StopKeys(CopyKey.STOP_UNDONE_TITLE, CopyKey.STOP_UNDONE_BODY, CopyKey.STOP_UNDONE_PRIMARY, null)
    StopCase.PROBE_FAILED -> StopKeys(CopyKey.STOP_PROBE_FAILED_TITLE, CopyKey.STOP_PROBE_FAILED_BODY, CopyKey.STOP_PROBE_FAILED_PRIMARY, CopyKey.STOP_PROBE_FAILED_SECONDARY)
    StopCase.PLAN_REFUSED -> StopKeys(CopyKey.STOP_PLAN_REFUSED_TITLE, CopyKey.STOP_PLAN_REFUSED_BODY, CopyKey.STOP_PROBE_FAILED_PRIMARY, CopyKey.STOP_PROBE_FAILED_SECONDARY)
    StopCase.HOME_PUT_BACK -> StopKeys(CopyKey.STOP_HOME_PUT_BACK_TITLE, CopyKey.STOP_RESUME_HOME_BODY, CopyKey.STOP_HOME_PUT_BACK_PRIMARY, CopyKey.STOP_HOME_PUT_BACK_SECONDARY)
    StopCase.NOT_BACK_AFTER_RESTART -> StopKeys(CopyKey.STOP_NOT_BACK_TITLE, CopyKey.STOP_NOT_BACK_BODY, CopyKey.STOP_NOT_BACK_PRIMARY, CopyKey.STOP_NOT_BACK_SECONDARY)
    StopCase.TV_NOT_FOUND -> StopKeys(CopyKey.STOP_TV_NOT_FOUND_TITLE, CopyKey.STOP_TV_NOT_FOUND_BODY, CopyKey.STOP_TV_NOT_FOUND_PRIMARY, CopyKey.STOP_TV_NOT_FOUND_SECONDARY)
    StopCase.WRONG_NETWORK -> StopKeys(CopyKey.STOP_WRONG_NETWORK_TITLE, CopyKey.STOP_WRONG_NETWORK_BODY, CopyKey.STOP_WRONG_NETWORK_PRIMARY, CopyKey.STOP_WRONG_NETWORK_SECONDARY)
    StopCase.IDENTITY_CHANGED -> StopKeys(CopyKey.STOP_IDENTITY_CHANGED_TITLE, CopyKey.STOP_IDENTITY_CHANGED_BODY, CopyKey.STOP_IDENTITY_CHANGED_PRIMARY, null)
    StopCase.TV_NOT_SUPPORTED -> StopKeys(CopyKey.STOP_TV_NOT_SUPPORTED_TITLE, CopyKey.STOP_TV_NOT_SUPPORTED_BODY, CopyKey.STOP_TV_NOT_SUPPORTED_PRIMARY, null)
    StopCase.TV_REFUSES_HOME, StopCase.PAIRING_NEEDED -> StopKeys(CopyKey.STOP_HOME_UNKNOWN_TITLE, CopyKey.STOP_HOME_UNKNOWN_BODY, CopyKey.STOP_HOME_UNKNOWN_SECONDARY, null)
}
