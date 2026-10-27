package tv.cleanyour.core.flows

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.discovery.Discovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.HomeFit
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.spec.TilesChoice
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
    // 0.4.0: the pictures any look can show instead of its own (the app's ThemeSource is one).
    val pictures: PictureSource = themes as? PictureSource ?: PictureSource.NONE,
) {
    fun tv(id: TvId): Tv? = tvs.tvs.value.firstOrNull { it.id == id }

    // The TV's exact profile as Setup last stored it, unless it does not fit the Home of the TV's
    // latest snapshot (issue #114: a record kept from before the rule names an Android TV list for a
    // Google TV set; Setup clears it on the next connect, Manage already leaves it out).
    suspend fun profileOf(tv: Tv): Profile? {
        val profile = tv.profileId?.let { id -> attempt { profiles.all() }.getOrNull()?.firstOrNull { it.id == id } } ?: return null
        val latest = attempt { snapshots.latest(tv.id) }.getOrNull()
        return profile.takeIf { HomeFit.fits(it, latest?.home, latest?.packages) }
    }

    fun nameOf(pkg: PackageName, label: String? = null): String = AppNames.of(pkg, facts, label)
}

// The dock and theme this phone last pushed to a TV. Tv has no field for them once the wizard
// draft is gone, so they live beside the TV record: tvs/<id>/home.txt, "dock=a,b" and "theme=id".
// `cards` is the module_cards value the look carries (RFC-CARDS-1, "cards=true"): null when this
// phone has not kept one for the TV yet (Cards.of decides then).
// `previousThemeId` (0.4.0, "previous=id"): the look before a dated one (a festival), so the person
// has it again once the dated look ends (lookOn). `picture` (0.4.0, "picture=id"): the picture the
// look shows instead of its own (LookPicture), null for the look's own. `dim` (0.4.0, "dim=n"): the
// dim the person chose for their own photo (LookPicture.MINE_ID), 0 to 90.
// `columns` (0.4.0, "columns=n"): the person's own apps in a row (4 to 8), kept only when it differs
// from the look's own; null means the look's own. `dock` is the whole saved order (DockLayers.keep,
// up to 24 apps), which can hold more apps than the TV shows at this many in a row: every reader
// cuts it with DockLayers (decode, applyTo, trim) before use.
// look2: `style` (StyleId.id, "style=") is set for a choice in the new form; null is a legacy 0.4.0
// choice, which keeps its look id and its 0.4.0 bytes (LegacyLook) until the next send. `movement`
// ("movement="), `lift` ("lift=") and `names` ("names=") belong to nobody; `details` (the "d." lines)
// are the person's changes over the style's and the picture's own; `previousPicture` ("prevpic=") is
// the picture before a dated one (Diwali), which is theirs again once it ends. In the new form
// `picture` is never null and `columns` is always written.
// 0.4.5: `extras` (the "x." lines) are the home screen 3.0 switches, each line written only when it
// is not the launcher's own, so a 0.4.0 file loads as DEFAULT and saves again byte for byte.
data class HomeChoice(val dock: List<PackageName>, val themeId: String?, val cards: Boolean? = null, val previousThemeId: String? = null,
    val picture: String? = null, val dim: Int? = null, val columns: Int? = null,
    val style: String? = null, val movement: String? = null, val lift: Boolean? = null, val names: Boolean? = null,
    val details: LookDetails = LookDetails(), val previousPicture: String? = null, val extras: LookExtras = LookExtras.DEFAULT) {
    // The look that is this TV's on `today`: the saved one, or once a dated look has ended, the one
    // before it (null, the Default look, when that one has ended too or there was none).
    fun lookOn(today: LocalDate): String? = when {
        Looks.offered(themeId, today) -> themeId
        Looks.offered(previousThemeId, today) -> previousThemeId
        else -> null
    }

    // A dated look that ended by `today` and is still the saved one: the TV still shows it (the
    // launcher only drops the festival skin by itself).
    fun endedLook(today: LocalDate): String? = themeId?.takeIf { !Looks.offered(it, today) }

    // The choice after the person picks `next`: a dated look keeps the look before it, any other
    // look forgets it.
    fun withTheme(next: String?, dock: List<PackageName> = this.dock, cards: Boolean? = this.cards, picture: String? = this.picture,
        dim: Int? = this.dim, columns: Int? = this.columns): HomeChoice =
        HomeChoice(dock, next, cards, previousThemeId = previousFor(themeId, previousThemeId, next), picture = picture, dim = dim, columns = columns)

    // look2: the choice is in the new form (a style), not a saved 0.4.0 look.
    val isNew: Boolean get() = style != null

    companion object {
        fun previousFor(current: String?, earlier: String?, next: String?): String? = when {
            !Looks.dated(next) -> null
            current == next -> earlier
            Looks.dated(current) -> earlier
            else -> current
        }
    }
}

// The day on the phone, for dated looks.
fun Clock.today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(ZoneId.systemDefault()).toLocalDate()

// tvs/<id>/home.txt, one "key=value" line each. Every line is optional, and a value that does not
// read loads as null, so a file of any version loads: 0.3.x (dock, theme, cards, previous), 0.4.0
// (picture, dim, columns), look2 (style, movement, lift, names, prevpic and the "d." details) and
// 0.4.5 (the "x." extras, only those not at the launcher's own).
class HomePrefs(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/home.txt"

    suspend fun load(id: TvId): HomeChoice? = store.read(path(id)) { bytes -> decode(bytes.decodeToString()) }

    suspend fun save(id: TvId, choice: HomeChoice) {
        store.write(path(id), encode(choice).encodeToByteArray())
    }

    companion object {
        private val OFFERED = DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS

        // The text form of a choice, also kept as WizardDraft.look and in tvs/<id>/earlier.txt.
        fun encode(choice: HomeChoice): String {
            val d = choice.details
            val lines = listOf(
                "dock" to choice.dock.joinToString(",") { it.value }, "theme" to choice.themeId.orEmpty(),
                "cards" to choice.cards, "previous" to choice.previousThemeId, "picture" to choice.picture, "dim" to choice.dim,
                "columns" to choice.columns, "style" to choice.style, "movement" to choice.movement, "lift" to choice.lift,
                "names" to choice.names, "prevpic" to choice.previousPicture,
                "d.bigname" to d.bigName, "d.glow" to d.glow?.id, "d.spotcolour" to d.spotColour, "d.ring" to d.ring,
                "d.band" to d.band?.id, "d.see" to d.seeThrough, "d.buttons" to d.buttons?.id, "d.wifi" to d.greenWifi,
                "d.dim" to d.dim, "d.sky" to d.sky, "d.skystr" to d.skyStrength,
            ) + extrasLines(choice.extras)
            return lines.filter { (k, v) -> v != null || k == "dock" || k == "theme" }.joinToString("") { (k, v) -> "$k=$v\n" }
        }

        // The "x." lines, each only when it is not LookExtras.DEFAULT's value (null leaves it out).
        private fun extrasLines(e: LookExtras): List<Pair<String, Any?>> = listOf(
            "x.onerow" to "1".takeIf { e.oneRow }, "x.bandall" to "1".takeIf { e.bandAll }, "x.noband" to "1".takeIf { e.noBand },
            "x.nogreeting" to "1".takeIf { e.hideGreeting }, "x.datebelow" to "1".takeIf { e.dateBelow },
            "x.brightdate" to "1".takeIf { e.brightDate }, "x.wifiwarn" to "1".takeIf { e.wifiWarnings },
            "x.tiles" to e.tiles.takeIf { it != TilesChoice.ART }?.id, "x.highlight" to e.highlight.takeIf { it != HighlightChoice.GLASS }?.id,
        )

        fun decode(text: String): HomeChoice {
            val map = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            fun str(k: String) = map[k]?.takeIf { it.isNotBlank() }
            fun bool(k: String) = map[k]?.toBooleanStrictOrNull()
            fun int(k: String, range: IntRange) = map[k]?.toIntOrNull()?.takeIf { it in range }
            val details = LookDetails(
                bigName = bool("d.bigname"), glow = GlowChoice.of(map["d.glow"]), spotColour = bool("d.spotcolour"), ring = bool("d.ring"),
                band = BandChoice.of(map["d.band"]), seeThrough = int("d.see", 0..100)?.takeIf { it in LookKeys.SEE_THROUGH },
                buttons = ButtonsChoice.of(map["d.buttons"]), greenWifi = bool("d.wifi"), dim = int("d.dim", 0..90),
                sky = bool("d.sky"), skyStrength = int("d.skystr", LookRules.SKY_STRENGTH),
            )
            return HomeChoice(
                str("dock").orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { PackageName(it) }.getOrNull() },
                str("theme"), bool("cards"), str("previous"), str("picture"), int("dim", 0..90), int("columns", OFFERED),
                style = StyleId.of(map["style"])?.id, movement = Movement.of(map["movement"])?.id, lift = bool("lift"), names = bool("names"),
                details = details, previousPicture = str("prevpic"),
                extras = LookExtras(
                    oneRow = map["x.onerow"] == "1", bandAll = map["x.bandall"] == "1", noBand = map["x.noband"] == "1",
                    hideGreeting = map["x.nogreeting"] == "1", dateBelow = map["x.datebelow"] == "1", brightDate = map["x.brightdate"] == "1",
                    wifiWarnings = map["x.wifiwarn"] == "1",
                    tiles = TilesChoice.entries.firstOrNull { it.id == map["x.tiles"] } ?: TilesChoice.ART,
                    highlight = HighlightChoice.entries.firstOrNull { it.id == map["x.highlight"] } ?: HighlightChoice.GLASS,
                ),
            )
        }
    }
}

// App settings (M8) that are preferences, not records: haptics and large text. look2: the Look
// editor's Show launcher names ("lookKeyNames=", Advanced), off unless turned on.
data class AppPrefsValue(val haptics: Boolean = true, val largeTextFollowsSystem: Boolean = true, val lookKeyNames: Boolean = false)

class AppPrefs(private val store: DocumentStore) {
    private val path = "settings.txt"

    suspend fun load(): AppPrefsValue = store.read(path) { bytes ->
        val map = bytes.decodeToString().lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        AppPrefsValue(haptics = map["haptics"] != "false", largeTextFollowsSystem = map["largeText"] != "false", lookKeyNames = map["lookKeyNames"] == "true")
    } ?: AppPrefsValue()

    suspend fun save(v: AppPrefsValue) {
        store.write(path, ("haptics=${v.haptics}\nlargeText=${v.largeTextFollowsSystem}\n" + if (v.lookKeyNames) "lookKeyNames=true\n" else "").encodeToByteArray())
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
        (d.engine.runState(tvId).value as? RunState.Paused)?.let { p -> publish(tv, p); return WriteResult.Paused(p.plan, p) }
        return attempt {
            val snapshot = if (fresh) d.runner.freshSnapshot(session) else d.runner.snapshotFor(tv, session)
            runFrom(tv, session, request, snapshot, onBusy)
        }.getOrElse { WriteResult.Failed(it.message ?: it.toString()) }
    }

    // The plan for the request from this read of the TV, then the run, followed to its end or pause.
    private suspend fun runFrom(tv: Tv, session: TvSession, request: PlanRequest, snapshot: Snapshot, onBusy: (Busy) -> Unit): WriteResult {
        val tvId = tv.id
        return attempt {
            val plan = when (val r = d.runner.plan(tv, request, snapshot)) {
                is PlanResult.Ok -> r.plan
                is PlanResult.Refused -> return@attempt WriteResult.Refused(r.reasons)
            }
            d.openWrites.remember(plan.id, request)
            val outcome = d.runner.run(tv, plan, session, onStarted = { d.sessionEnd.touched(tvId) }) { s ->
                if (s is RunState.Running) onBusy(Busy.Working(s.percent, s.narration.map { it.text }))
            }
            when (outcome) {
                is RunOutcome.Finished -> {
                    afterRun(tv, session, plan, outcome.state)
                    WriteResult.Done(plan, outcome.state)
                }
                is RunOutcome.Paused -> { publish(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
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
        if (publish) publish(tv, p)
        return p
    }

    // The open run's card (O2). QA of 0.4.5: it knows whether any step of the run was done, so a run
    // paused STALE before its first step says nothing was changed, never "we stopped partway".
    suspend fun publish(tv: Tv, p: RunState.Paused) {
        val nothingDone = attempt { d.ledger.progress(p.plan.id).doneSteps == 0 }.getOrDefault(false)
        d.openWrites.show(tv, p, nothingDone)
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
            if (action == OpenWriteAction.CARRY_ON && paused.reason == PauseReason.STALE) return@attempt checkAgain(tv, session, paused)
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
                is RunOutcome.Paused -> { publish(tv, outcome.state); WriteResult.Paused(plan, outcome.state) }
                else -> { idle(); WriteResult.Failed("unexpected") }
            }
        }.getOrElse { idle(); WriteResult.Failed(it.message ?: it.toString()) }
    }

    // QA of 0.4.5: the TV changed after the read the plan was made from, so the engine paused the run
    // STALE before the step whose prior no longer held. Carrying on with that plan meets the same
    // check again, for good (the engine re-reads the TV but the plan's priors stay old). "Check
    // again" ends that run (what was done stays done), reads the TV afresh and plans the same request
    // from what it has now, and runs it; the engine checks the new plan against the TV once more
    // before its first step, so a plan made from an old read never runs. When the request is not
    // known (the app was restarted since), the run is ended and the TV read, and the next change
    // starts from that read.
    private suspend fun checkAgain(tv: Tv, session: TvSession, paused: RunState.Paused): WriteResult {
        d.engine.abandon(tv.id)
        val after = d.engine.runState(tv.id).value
        if (after is RunState.Paused) {   // the run could not be ended (the TV went away again)
            d.openWrites.update(tv.id) { it.copy(busy = false, notTaken = true) }
            return WriteResult.Paused(paused.plan, after)
        }
        val snapshot = d.runner.freshSnapshot(session)
        val request = d.openWrites.requestOf(paused.plan.id)
        if (request == null) {
            d.openWrites.clear(tv.id)
            return WriteResult.Done(paused.plan, after as? RunState.Finished ?: RunState.Finished(paused.plan, RunStatus.ABANDONED, emptyList(), null))
        }
        // The card stays, busy, while the new plan runs; a pause of that run shows its own card.
        val result = runFrom(tv, session, request, snapshot) {}
        if (result !is WriteResult.Paused) d.openWrites.clear(tv.id)
        return result
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
