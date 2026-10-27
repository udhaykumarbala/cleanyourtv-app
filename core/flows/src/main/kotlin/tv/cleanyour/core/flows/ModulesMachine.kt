package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PermissionStatement
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.registry.FetchResult
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.trust.IndexFailure

// M3 Cards and modules, M3a Add, M3b detail, M3c the card prompt mirror (UX 5, D1, D6, D45, F9).
sealed interface IndexView {
    data object Loading : IndexView
    data class Ready(val stale: Boolean) : IndexView
    data class Failed(val verdict: IndexFailure?, val fetch: FetchFailure?, val hasLast: Boolean) : IndexView
}

data class ModuleRow(
    val id: String, val name: String, val oneLine: String, val sizeBytes: Long?, val licence: String, val tier: Tier,
    val accent: String?, val installed: Boolean, val card: CardAnswer?, val updateTo: String?,
)

data class ModuleDetail(val module: Module, val version: ModuleVersion, val key12: String, val installed: Boolean)

sealed interface InstallStep {
    data class Downloading(val done: Long, val total: Long) : InstallStep
    data object Checking : InstallStep
    data class Installing(val percent: Int) : InstallStep
    data class Asking(val name: String, val pkg: String, val key12: String) : InstallStep   // M3c
    data class Shown(val name: String) : InstallStep
    data class Hidden(val name: String) : InstallStep
    data class Failed(val name: String, val fetch: FetchFailure?) : InstallStep
}

data class UpdateConsent(val moduleId: String, val name: String, val version: String, val added: List<PermissionStatement>)

data class ModulesState(
    val tvName: String = "",
    val index: IndexView = IndexView.Loading,
    val installed: List<ModuleRow> = emptyList(),
    val available: List<ModuleRow> = emptyList(),
    val query: String = "",
    val detail: ModuleDetail? = null,
    val install: InstallStep? = null,
    val consent: UpdateConsent? = null,
    val removing: String? = null,
    // RFC-CARDS-1: the new home screen is this TV's and its module cards are off, so an installed
    // module's card waits for "Show its card" (an add turns them on as part of its plan).
    val cardsOff: Boolean = false,
    val showing: String? = null,   // the module whose card is being turned on
) {
    val filtered: List<ModuleRow> get() = if (query.isBlank()) available
        else available.filter { it.name.contains(query, ignoreCase = true) || it.oneLine.contains(query, ignoreCase = true) }
}

sealed interface ModulesIntent {
    data object Refresh : ModulesIntent
    data class Search(val query: String) : ModulesIntent
    data class Open(val id: String) : ModulesIntent
    data object CloseDetail : ModulesIntent
    data class Add(val id: String) : ModulesIntent
    data class Remove(val id: String) : ModulesIntent
    data class ShowCard(val id: String) : ModulesIntent   // RFC-CARDS-1: turn the cards on for an installed module
    data class Update(val id: String) : ModulesIntent
    data object ConfirmUpdate : ModulesIntent
    data object SkipUpdate : ModulesIntent
    data class Answered(val answer: CardAnswer) : ModulesIntent   // manual outcome when the TV cannot be read
    data object DismissInstall : ModulesIntent
}

class ModulesMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
    private val pollMs: Long = 3_000,
) : Machine<ModulesState, ModulesIntent, ManageEffect>(ModulesState(), scope) {

    private data class Rows(val tvName: String, val index: IndexView, val installed: List<ModuleRow>, val available: List<ModuleRow>, val cardsOff: Boolean)
    private data class Step(val step: InstallStep?)
    private data class Consent(val c: UpdateConsent?)
    private data class Removing(val id: String?)
    private data class Showing(val id: String?)
    private var detectJob: Job? = null

    // STORES-PLAN 1: the Play build has no add-ons. Nothing leads here then (Manage and Done hide
    // their rows); should anything still open it, the list is not refreshed and nothing is added
    // or updated.
    private val addOns = d.store.addOns

    init {
        scope.launch { d.registry.index.collect { load() } }
        if (addOns) scope.launch { if (d.registry.index.value is IndexState.Empty) attempt { d.registry.refresh() } }
    }

    override fun reduce(state: ModulesState, event: Any): ModulesState = when (event) {
        is Rows -> state.copy(tvName = event.tvName, index = event.index, installed = event.installed, available = event.available, cardsOff = event.cardsOff,
            detail = state.detail?.let { det -> detailFor(det.module.id, event) ?: det })
        is ModulesIntent.Search -> state.copy(query = event.query)
        is ModulesIntent.Open -> state.copy(detail = detailFor(event.id, null))
        ModulesIntent.CloseDetail -> state.copy(detail = null)
        is Step -> state.copy(install = event.step)
        ModulesIntent.DismissInstall -> state.copy(install = null)
        is Consent -> state.copy(consent = event.c)
        ModulesIntent.SkipUpdate -> state.copy(consent = null)
        is Removing -> state.copy(removing = event.id)
        is Showing -> state.copy(showing = event.id)
        else -> state
    }

    override fun handle(event: Any, before: ModulesState, after: ModulesState) {
        val installing = before.install.let { it != null && it !is InstallStep.Shown && it !is InstallStep.Hidden && it !is InstallStep.Failed }
        when (event) {
            ModulesIntent.Refresh -> if (addOns) scope.launch { attempt { d.registry.refresh() } }
            is ModulesIntent.Add -> if (addOns && !installing) scope.launch { install(event.id) }
            is ModulesIntent.Update -> if (addOns && !installing) scope.launch { update(event.id) }
            ModulesIntent.ConfirmUpdate -> if (addOns) before.consent?.let { c -> send(Consent(null)); scope.launch { install(c.moduleId) } }
            is ModulesIntent.Remove -> if (before.removing == null) scope.launch { remove(event.id) }
            is ModulesIntent.ShowCard -> if (!installing && before.removing == null && before.showing == null) scope.launch { showCard(event.id) }
            is ModulesIntent.Answered -> answered(event.answer)
            ModulesIntent.DismissInstall -> detectJob?.cancel()
            else -> {}
        }
    }

    private fun module(id: String): Module? = d.registry.module(id)

    private fun detailFor(id: String, rows: Rows?): ModuleDetail? {
        val m = module(id) ?: return null
        val tv = d.tv(tvId) ?: return null
        val v = bestVersion(m, tv) ?: return null
        val installed = (rows?.installed ?: state.value.installed).any { it.id == id }
        return ModuleDetail(m, v, keyDigest12(v.artifact.signer), installed)
    }

    private fun bestVersion(m: Module, tv: Tv): ModuleVersion? = m.versions
        .filter { it.minSdk <= tv.facts.sdk && (it.abis.isEmpty() || it.abis.any { a -> a in tv.facts.abis }) }
        .maxByOrNull { it.versionCode }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val packages = d.snapshots.latest(tvId)?.packages.orEmpty()
        val view = when (val s = d.registry.index.value) {
            IndexState.Empty -> IndexView.Loading
            is IndexState.Ready -> IndexView.Ready(s.stale)
            is IndexState.Failed -> IndexView.Failed(s.verdict, s.failure, s.last != null)
        }
        val modules = when (val s = d.registry.index.value) {
            is IndexState.Ready -> s.index.modules
            is IndexState.Failed -> s.last?.modules.orEmpty()
            IndexState.Empty -> emptyList()
        }
        fun row(m: Module): ModuleRow {
            val v = bestVersion(m, tv)
            val have = packages[m.pkg]
            val newer = if (have != null && v != null && v.versionCode > have.versionCode) v.versionName else null
            return ModuleRow(m.id, m.name, m.oneLine, v?.artifact?.size, m.licence, m.tier, m.accent, have != null, null, newer)
        }
        val installed = modules.filter { packages.containsKey(it.pkg) }.map(::row)
        // Quarantined and revoked entries are hidden from the list (UX 5.1); an installed one still shows as Paused.
        val available = modules.filter { it.tier == Tier.VERIFIED || it.tier == Tier.COMMUNITY }.map(::row)
        val cardsOff = tv.launcher != null && !Cards.current(d, tvId, d.homePrefs.load(tvId))
        send(Rows(tv.nickname, view, installed, available, cardsOff))
    }

    // RFC-CARDS-1: the look that turns this TV's module cards on, when they are off and the new home
    // screen is the TV's: the dock and theme this phone keeps for it, module_cards true. Null when
    // there is nothing to change (cards already on, or the TV's own home screen is in use).
    private suspend fun cardsOnLook(overlay: ByteArray?): Pair<Look, HomeChoice>? {
        val tv = d.tv(tvId) ?: return null
        if (tv.launcher == null) return null
        val choice = d.homePrefs.load(tvId)
        if (Cards.current(d, tvId, choice, overlay)) return null
        // look2: a choice in the new form is pushed in its own form, on this TV.
        if (choice?.isNew == true) {
            val snap = d.snapshots.latest(tvId)
            val packages = snap?.packages.orEmpty()
            val liveTv = d.profileOf(tv)?.liveTv
            val lookTv = LookTv(packages[Launcher.pkg]?.versionCode, snap?.facts?.ramMb?.takeIf { it > 0 },
                snap == null || snap.hdmi.isNotEmpty() || liveTv?.let { it in packages } == true)
            val drafts = LookDrafts(d.themes, d.pictures, d.encoder, d.clock)
            val (doc, wallpaper) = drafts.docOf(choice, choice.dock, cards = true, OwnPicture.ref(d, tvId), lookTv)
            return Look.encode(d.encoder, d.blobs, doc, wallpaper) to choice.copy(dock = DockLayers.keep(choice.dock), cards = true)
        }
        // 0.4.0: once a dated look has ended, the look before it (HomeChoice.lookOn).
        val theme = choice?.lookOn(d.clock.today())?.let { id -> d.themes.bundled().firstOrNull { it.id == id } }
        // 0.4.0: with the person's apps in a row, and the whole saved order kept (DockLayers.keep),
        // not only the part the TV shows.
        val look = Look.encode(d.encoder, d.blobs, theme, choice?.dock.orEmpty(), cards = true, OwnPicture.lookPicture(d, tvId, choice?.picture, choice?.dim),
            choice?.columns)
        return look to (choice ?: HomeChoice(emptyList(), null)).withTheme(theme?.id, DockLayers.keep(choice?.dock.orEmpty()), cards = true)
    }

    private suspend fun keepCardsOn(pushed: Pair<Look, HomeChoice>) {
        d.homePrefs.save(tvId, pushed.second)
        rememberPushedLook(d, tvId, pushed.first.digest)
    }

    // UX 5.4: new permissions in an update need consent again.
    private suspend fun update(id: String) {
        val m = module(id) ?: return
        val tv = d.tv(tvId) ?: return
        val next = bestVersion(m, tv) ?: return
        val haveCode = d.snapshots.latest(tvId)?.packages?.get(m.pkg)?.versionCode
        val have = m.versions.firstOrNull { it.versionCode == haveCode }
        val added = next.permissions.filter { p -> have?.permissions?.none { it.permission == p.permission } ?: false }
        if (added.isNotEmpty()) send(Consent(UpdateConsent(id, m.name, next.versionName, added))) else install(id)
    }

    // "Add to Mi TV": the debugging step if needed, the gate, the download checked by sha256 and size
    // while streaming, the signer checked on the phone, then the AddModule plan (F9).
    private suspend fun install(id: String) {
        val m = module(id) ?: return
        val tv = d.tv(tvId) ?: return
        val v = bestVersion(m, tv) ?: return
        val session = (d.access.session(tvId) as? AccessResult.Ok)?.session ?: return
        // An earlier write left its run open: its card, not a download that could never install (O2).
        if (d.engine.runState(tvId).value is RunState.Paused) { TvWriter(d).openRun(tv, session); return }
        val pass = d.gate.pass(tvId, tv.nickname) ?: return
        send(Step(InstallStep.Downloading(0, v.artifact.size)))
        val blob = when (val f = attempt { d.fetcher.fetch(v.artifact) { done, total -> send(Step(InstallStep.Downloading(done, total))) } }
            .getOrElse { FetchResult.Failed(FetchFailure.OFFLINE, null) }) {
            is FetchResult.Ok -> f.blob
            is FetchResult.Failed -> return send(Step(InstallStep.Failed(m.name, f.failure)))
        }
        send(Step(InstallStep.Checking))
        val info = attempt { d.inspector.inspect(blob) }.getOrNull()
        if (info == null || info.pkg != m.pkg || info.signer != v.artifact.signer || info.sha256 != v.artifact.sha256 || info.versionCode != v.versionCode) {
            return send(Step(InstallStep.Failed(m.name, FetchFailure.SIGNER_MISMATCH)))
        }
        send(Step(InstallStep.Installing(0)))
        var cardsOn: Pair<Look, HomeChoice>? = null
        val outcome = attempt {
            val snapshot = d.runner.snapshotFor(tv, session)
            // RFC-CARDS-1: adding a card is the person asking for cards, so the same plan turns them on.
            cardsOn = cardsOnLook(snapshot.launcherOverlay)
            val request = PlanRequest.AddModule(m, v, blob, cardsOn?.first?.config, cardsOn?.first?.wallpaper)
            val plan = (d.runner.plan(tv, request, snapshot) as? PlanResult.Ok)?.plan ?: return@attempt null
            d.openWrites.remember(plan.id, request)
            d.runner.run(tv, plan, session, pass, onStarted = { d.sessionEnd.touched(tvId) }) { s -> if (s is RunState.Running) send(Step(InstallStep.Installing(s.percent))) }
        }.getOrNull()
        // A paused install (or a run another write left open) is the OpenWrites card's to resolve (O2).
        if (outcome is RunOutcome.Paused) { TvWriter(d).publish(tv, outcome.state); return send(Step(null)) }
        if (outcome is RunOutcome.StartRefused && outcome.refusal == StartRefusal.RUN_OPEN && TvWriter(d).openRun(tv, session) != null) return send(Step(null))
        if (outcome !is RunOutcome.Finished) return send(Step(InstallStep.Failed(m.name, null)))
        cardsOn?.let { keepCardsOn(it) }
        attempt { d.snapshots.save(d.runner.freshSnapshot(session)) }
        d.restorePoint.write(tv, session, d.profileOf(tv))
        load()
        ask(m.name, m.pkg, keyDigest12(v.artifact.signer), session)
    }

    // RFC-CARDS-1: an installed module whose card waits because the TV's cards are off. The look with
    // module_cards on goes to the TV as an ApplyTheme plan; the launcher's restart finds the card
    // and asks on the TV, and the phone mirrors that question as after an add.
    private suspend fun showCard(id: String) {
        val m = module(id) ?: return
        val tv = d.tv(tvId) ?: return
        val pushed = attempt { cardsOnLook(null) }.getOrNull() ?: return load()
        send(Showing(id))
        val r = TvWriter(d).write(tvId, PlanRequest.ApplyTheme(pushed.first.doc, pushed.first.config, pushed.first.wallpaper))
        send(Showing(null))
        if (r !is WriteResult.Done) {
            // Paused: the open-run card (O2); cancelled: nothing changed, nothing to say.
            if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
            return
        }
        keepCardsOn(pushed)
        load()
        val session = d.access.live(tvId) ?: return
        val signer = d.snapshots.latest(tvId)?.packages?.get(m.pkg)?.signer ?: bestVersion(m, tv)?.artifact?.signer ?: return
        ask(m.name, m.pkg, keyDigest12(signer), session)
    }

    // M3c: the launcher asks on the TV; the phone mirrors the prompt with the same 12 hex (D1).
    private fun ask(name: String, pkg: PackageName, key12: String, session: TvSession) {
        send(Step(InstallStep.Asking(name, pkg.value, key12)))
        effect(ManageEffect.HapticLight)
        detectJob?.cancel()
        detectJob = scope.launch {
            repeat(40) {
                val a = attempt { d.cardDetector.answer(session, pkg) }.getOrNull()
                if (a != null) { answered(a); return@launch }
                delay(pollMs)
            }
        }
    }

    private fun answered(a: CardAnswer) {
        val step = state.value.install as? InstallStep.Asking ?: return
        detectJob?.cancel()
        send(Step(if (a == CardAnswer.SHOWN) InstallStep.Shown(step.name) else InstallStep.Hidden(step.name)))
        effect(ManageEffect.HapticDouble)
    }

    private suspend fun remove(id: String) {
        val m = module(id) ?: return
        send(Removing(id))
        val r = TvWriter(d).write(tvId, PlanRequest.RemoveModule(m.pkg))
        if (r is WriteResult.Done) {
            d.access.live(tvId)?.let { s -> attempt { d.snapshots.save(d.runner.freshSnapshot(s)) } }
            effect(ManageEffect.Snackbar(SnackKind.MODULE_REMOVED, m.name, false))
        }
        send(Removing(null))
        load()
    }
}
