package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.HomeHelper
import tv.cleanyour.core.planner.PutBackAdvice
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.spec.DockLayers

// M1, the app's home once a TV exists (UX 4.11 case A).
data class TvCard(
    val id: TvId,
    val name: String,
    val room: String?,
    val clean: Boolean,               // "Clean" when this phone switched things off, "Original" after Undo everything
    val checkedAtMs: Long,
    val level: Level,
    val chip: ChipState,
    val thumbnail: ByteArray?,
    val switchedOff: Int,
    val dockNames: List<String>,
    val restorePointAtMs: Long?,
    val debugging: DebugState,
    // 0.3.2 B1: no exact profile and a stored snapshot, so "Help add this TV" can make its report.
    val helpAddTv: Boolean = false,
    // 0.3.3 D2: the new home screen is not on this TV (HomeScreenMachine says why), so the Home
    // screen row says the TV keeps its own instead of naming a dock.
    val ownHome: Boolean = false,
    // WP4 (C6): apps this phone switched off that the list for this TV now keeps on (PutBackAdvice);
    // Manage shows one line for them that opens the Switched off list.
    val betterBackOn: Int = 0,
    // 0.4.6: on this connect a setup wizard claims Home while ours is Home and the TV's own home
    // screen is off (WizardHome); Manage offers to switch the TV's own home screen back on.
    val wizardHome: Boolean = false,
    // 0.4.7 (UX finding 8): the new home screen is on the TV but not in use (NotOnTv.installed), so
    // the Home screen row says the TV's own is in use instead of naming a dock.
    val homeInstalledNotInUse: Boolean = false,
    // 0.4.7: this TV's report was sent then (SentReports); the help banner says so.
    val reportSentAtMs: Long? = null,
    // 0.4.7: on this connect the TV's own home screen, which this phone switched off, claims Home again
    // (StockHomeBack: XGIMI's remote buttons switch launcherx back on).
    val stockHomeBack: Boolean = false,
    // RFC-HELPER-1 (test build): the Home button helper is on (HomeHelper.State.ON in the latest
    // snapshot): the TV's own home screen stays Home and the Home button opens the new one.
    val homeHelperOn: Boolean = false,
    // 0.4.8 (Restarts): what this phone counted across the TV's restarts, while the new home screen is on it; null
    // before the first read on a path (a TV never switched has none).
    val restarts: RestartTally? = null,
)

data class ManageState(
    val cards: List<TvCard> = emptyList(),
    val selected: TvId? = null,
    val offline: Boolean = false,     // "Lost the TV. Is it still on? Reconnect"
    val reconnecting: Boolean = false,
    val loading: Boolean = true,
    val addOns: Boolean = true,       // STORES-PLAN 1: the "Cards and modules" row (off in the Play build)
    val switchingHome: Boolean = false,   // 0.4.6: the wizard Home row's "Switch it back on" is running
) {
    val current: TvCard? get() = cards.firstOrNull { it.id == selected } ?: cards.firstOrNull()
}

enum class ManageSection(val key: String) {
    HOME_SCREEN("home"), MODULES("modules"), SWITCHED_OFF("switchedoff"), LEFTOVERS("leftovers"),
    RESTORE_POINT("restore"), TV_SETTINGS("tvsettings"),
}

sealed interface ManageIntent {
    data class Select(val tvId: TvId) : ManageIntent
    data object Reconnect : ManageIntent
    data object RefreshThumbnail : ManageIntent
    data class Open(val section: ManageSection) : ManageIntent
    data object SetUpAnother : ManageIntent
    data object CleanAgain : ManageIntent
    data object AppSettings : ManageIntent
    data object HelpAddTv : ManageIntent
    data object SwitchStockHomeOn : ManageIntent   // 0.4.6: the wizard Home row (WizardHome)
    // 0.4.8: "Tell us it worked" (the confirmation's preview), and "Report a problem" after a restart that failed;
    // `title` is the banner's words as shown, for the problem form.
    data object TellUsItWorked : ManageIntent
    data class ReportRestartFailed(val title: String) : ManageIntent
    // 0.4.8 (PLAN item 4): Manage is on screen (the app came back, or a page above it closed) or left it.
    data object Resumed : ManageIntent
    data object Paused : ManageIntent
}

class ManageMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    initial: TvId? = null,
) : Machine<ManageState, ManageIntent, ManageEffect>(ManageState(selected = initial, addOns = d.store.addOns), scope) {

    private data class Loaded(val cards: List<TvCard>)
    private data class Thumb(val tvId: TvId, val png: ByteArray?)
    private data class Reconnected(val ok: Boolean)
    private data object Reconnecting
    private data class WizardHomeRead(val tvId: TvId, val applies: Boolean, val stockBack: Boolean = false)
    private data class SwitchingHome(val on: Boolean)
    private data class RestartsRead(val tvId: TvId, val tally: RestartTally)
    // 0.4.6: what the last connect read of each TV's Home said (WizardHome), for the cards.
    private val wizardHomes = java.util.concurrent.ConcurrentHashMap<TvId, Boolean>()
    // 0.4.8 (PLAN item 4): while Manage is on screen it keeps one hold on the shown TV's session, so the socket does not
    // close for idleness under the person (the "Offline" of every smoke since 0.4.0); and when the TV goes away (a
    // restart), it reconnects by itself (TvAccess.quiet). Released when Manage leaves the screen.
    private var visible = false
    private var hold: Pair<TvId, SessionHold>? = null
    private var autoJob: Job? = null
    @Volatile private var homeSettled = false   // the last Home read was one to count (Restarts.settled)

    init {
        scope.launch {
            // beta2 QA: the run states too, so the chip leaves "Working" when a run ends (Stop and
            // undo, then OK, kept it until the next connect changed something else).
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val runs = d.tvs.tvs.flatMapLatest { tvs ->
                if (tvs.isEmpty()) flowOf(emptyList()) else combine(tvs.map { d.engine.runState(it.id) }) { it.toList() }
            }
            combine(d.tvs.tvs, d.sessions.states, runs) { tvs, states, _ -> tvs to states }.collect { (tvs, states) ->
                val pin = runCatching { d.launcherPin.bundled() }.getOrNull()
                val cards = tvs.sortedByDescending { it.lastSeenAt }.map { tv ->
                    val run = d.engine.runState(tv.id).value
                    val offPkgs = d.ledger.switchedOffByUs(tv.id)
                    val off = offPkgs.size
                    val home = d.homePrefs.load(tv.id)
                    val latest = d.snapshots.latest(tv.id)
                    val ours = latest?.packages?.get(Launcher.pkg)
                    // Our launcher with our key (or one the probe could not read), not Home, and not a
                    // launcher under our name Setup left as it is (HOME_CHANGED): installed, not in use.
                    val mine = ours != null && tv.launcher == null && (ours.signer == null || pin == null || ours.signer == pin.signer)
                    val decision = if (mine) attempt { d.homeDecisions.load(tv.id) }.getOrNull() else null
                    // RFC-HELPER-1: with our HomeKeyService listed the TV's own is Home on purpose; the new one is in use through it.
                    val notInUse = mine && latest?.home?.resolved?.pkg != Launcher.pkg && decision?.alreadyOn != true && decision?.block == null && latest?.let { HomeHelper.listed(it) } != true
                    TvCard(
                        id = tv.id, name = tv.nickname, room = tv.room, clean = off > 0, checkedAtMs = tv.lastSeenAt, level = tv.level,
                        chip = chipFor(states[tv.id]?.takeIf { d.access.live(tv.id) != null }, run, tv.debugging),
                        thumbnail = state.value.cards.firstOrNull { it.id == tv.id }?.thumbnail,
                        switchedOff = off, dockNames = home?.let { shownDock(it) }.orEmpty().map { d.nameOf(it) },
                        restorePointAtMs = (d.snapshots.baseline(tv.id) ?: latest)?.takenAtMs, debugging = tv.debugging,
                        helpAddTv = tv.profileId == null && latest != null,
                        ownHome = tv.launcher == null && latest != null && Launcher.pkg !in latest.packages,
                        betterBackOn = if (PutBackAdvice.forProfile(tv.profileId).isEmpty()) 0
                            else PutBackAdvice.offers(tv.profileId, latest, d.ledger.changes(tv.id), d.profileOf(tv)).map { it.pkg }.distinct().count { it in offPkgs },
                        wizardHome = wizardHomes[tv.id] == true,
                        homeInstalledNotInUse = notInUse,
                        reportSentAtMs = if (tv.profileId == null) attempt { d.sentReports.load(tv.id) }.getOrNull()?.atMs else null,
                        stockHomeBack = d.stockHomeBacks[tv.id],
                        homeHelperOn = d.homeHelper && latest != null && HomeHelper.state(latest) == HomeHelper.State.ON,
                        restarts = attempt { d.restarts.load(tv.id) }.getOrNull()?.takeIf { it.path != null },
                    )
                }
                send(Loaded(cards))
            }
        }
        scope.launch { connected() }
        scope.launch { try { awaitCancellation() } finally { releaseHold() } }
    }

    // On a connect: the thumbnail, and the TV's Home read again (0.4.6, WizardHome), with its boot (0.4.8, Restarts).
    private suspend fun connected() {
        keepHold()
        thumbnail()
        checkWizardHome()
    }

    private fun keepHold() {
        if (!visible) return
        val id = state.value.current?.id ?: state.value.selected ?: return
        if (hold?.first == id) return
        val s = d.access.live(id) ?: return
        releaseHold()
        hold = id to s.hold()
    }

    private fun releaseHold() {
        hold?.second?.close()
        hold = null
    }

    // 0.4.8 (PLAN item 4): the TV the card shows is offline while Manage is on screen: try the quiet reconnect now and
    // then every AUTO_EVERY_MS, for as long as a restart takes (AUTO_TRIES), until it answers, Manage leaves the
    // screen, another TV is chosen or the person's own Reconnect runs.
    private fun autoReconnect() {
        val id = state.value.current?.id ?: return
        if (autoJob?.isActive == true || !visible) return
        autoJob = scope.launch {
            repeat(AUTO_TRIES) { i ->
                if (i > 0) delay(AUTO_EVERY_MS)
                val now = state.value
                if (!visible || !now.offline || now.reconnecting || now.current?.id != id) return@launch
                if (d.access.quiet(id) != null) {
                    d.log.line("manage: reconnected by itself after ${i + 1} ${if (i == 0) "try" else "tries"}")
                    send(Reconnected(true))
                    connected()
                    // A TV that restarted answers before it is up (Home still FallbackHome): read Home again a few
                    // times, so the restart is counted once the TV has its home screen.
                    repeat(SETTLE_TRIES) {
                        if (homeSettled || !visible) return@launch
                        delay(SETTLE_EVERY_MS)
                        checkWizardHome()
                    }
                    return@launch
                }
            }
        }
    }

    private suspend fun checkWizardHome() {
        homeSettled = false
        val id = state.value.current?.id ?: state.value.selected ?: return
        val s = d.access.live(id) ?: return
        val tv = d.tv(id) ?: return
        val home = attempt { d.prober.homeNow(s) }.getOrNull() ?: return
        homeSettled = Restarts.settled(home) && countRestart(tv, s, home)
        val profile = d.profileOf(tv)
        val changes = d.ledger.changes(id)
        val stock = WizardHome.stockOf(changes, profile)
        val applies = WizardHome.applies(home, attempt { d.snapshots.latest(id) }.getOrNull(), profile, stock)
        val back = StockHomeBack.applies(home, changes, stock)
        wizardHomes[id] = applies
        if (d.stockHomeBacks.record(id, back)) d.log.line("manage: the TV's own home screen is on again after this app switched it off, " +
            if (home.resolved?.pkg == stock?.pkg) "and Home answers to it" else "Home answers to ${if (home.resolved?.pkg == Launcher.pkg) "ours" else "another"}")
        send(WizardHomeRead(id, applies, back))
    }

    // 0.4.8 (Restarts): on a TV whose home screen this app switched (or whose helper it switched on), the boot_id
    // beside the Home read above; a new one counts a held or a failed restart. Nothing is read on any other TV.
    // The helper's path reads its two accessibility settings too (Restarts.HelperNow, issues #2061, #1799); false when
    // the helper is listed but not bound yet, so the reconnect reads again in this boot, as for a Home not settled.
    private suspend fun countRestart(tv: tv.cleanyour.core.model.Tv, s: tv.cleanyour.core.session.TvSession, home: tv.cleanyour.core.model.HomeResolution): Boolean {
        val latest = attempt { d.snapshots.latest(tv.id) }.getOrNull()
        val path = HomePath.of(tv, latest, attempt { d.homeDecisions.load(tv.id) }.getOrNull()) ?: return true
        val helper = if (path == HomePath.HELPER) Restarts.helperNow(d.prober, s) ?: return true else null
        if (helper == Restarts.HelperNow.NOT_YET) return false
        val boot = attempt { d.prober.bootId(s) }.getOrNull() ?: return true
        val o = attempt { d.restarts.observe(tv.id, boot, home, path, d.about.countLine, d.clock.nowMs(), helperOn = helper != Restarts.HelperNow.NOT_LISTED) }.getOrNull() ?: return true
        when (o.read) {
            RestartRead.HELD -> d.log.line("manage: restart held (${path.word}), ${o.tally.held} held, ${o.tally.failed} failed")
            RestartRead.FAILED -> d.log.line("manage: restart failed (${path.word}): ${Restarts.failedWhy(helper)}, ${o.tally.held} held, ${o.tally.failed} failed")
            else -> {}
        }
        send(RestartsRead(tv.id, o.tally))
        return true
    }

    // "Switch it back on": the existing undo of the switch (Use the TV's own home screen), then Home
    // is read again so the row goes once the TV's own home screen has it.
    private suspend fun switchStockHomeOn(id: TvId) {
        val tv = d.tv(id) ?: return
        val stock = WizardHome.stockOf(d.ledger.changes(id), d.profileOf(tv)) ?: return
        send(SwitchingHome(true))
        val r = TvWriter(d).useStockHome(id, stock)
        send(SwitchingHome(false))
        when (r) {
            is WriteResult.Done -> effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, d.nameOf(stock.pkg), false))
            WriteResult.GateCancelled, WriteResult.NoTv, is WriteResult.Paused -> {}   // Paused: the open-run card (O2)
            else -> effect(ManageEffect.Snackbar(SnackKind.FAILED, d.nameOf(stock.pkg), false))
        }
        checkWizardHome()
    }

    // 0.4.0: the saved dock can hold more apps than the TV shows (HomeChoice.dock), so the card names
    // the ones the TV shows: the dock and its rows at the saved apps in a row, else the look's own.
    private fun shownDock(home: HomeChoice): List<tv.cleanyour.core.model.PackageName> {
        val look = home.lookOn(d.clock.today())?.let { id -> d.themes.bundled().firstOrNull { it.id == id } }
        return DockLayers.trim(home.dock, home.columns ?: DockLayers.columnsOf(look?.doc))
    }

    override fun reduce(state: ManageState, event: Any): ManageState = when (event) {
        is Loaded -> {
            val selected = state.selected?.takeIf { id -> event.cards.any { it.id == id } } ?: event.cards.firstOrNull()?.id
            val cur = event.cards.firstOrNull { it.id == selected }
            state.copy(cards = event.cards, selected = selected, loading = false,
                offline = cur != null && cur.debugging == DebugState.ON && cur.chip == ChipState.OFFLINE)
        }
        is ManageIntent.Select -> state.copy(selected = event.tvId)
        is Thumb -> state.copy(cards = state.cards.map { if (it.id == event.tvId && event.png != null) it.copy(thumbnail = event.png) else it })
        Reconnecting -> state.copy(reconnecting = true)
        is WizardHomeRead -> state.copy(cards = state.cards.map { if (it.id == event.tvId) it.copy(wizardHome = event.applies, stockHomeBack = event.stockBack) else it })
        is SwitchingHome -> state.copy(switchingHome = event.on)
        is RestartsRead -> state.copy(cards = state.cards.map { if (it.id == event.tvId) it.copy(restarts = event.tally) else it })
        is Reconnected -> state.copy(reconnecting = false, offline = !event.ok && state.offline)
        else -> state
    }

    override fun handle(event: Any, before: ManageState, after: ManageState) {
        val id = after.current?.id
        when (event) {
            is Loaded -> if (after.offline && !before.offline) autoReconnect()
            ManageIntent.Resumed -> { visible = true; keepHold(); if (after.offline) autoReconnect() }
            ManageIntent.Paused -> { visible = false; autoJob?.cancel(); releaseHold() }
            is ManageIntent.Select -> scope.launch { connected() }
            ManageIntent.RefreshThumbnail -> scope.launch { thumbnail() }
            ManageIntent.Reconnect -> if (id != null && !after.reconnecting) scope.launch {
                send(Reconnecting)
                val ok = d.access.session(id) is AccessResult.Ok
                send(Reconnected(ok))
                if (ok) connected()
            }
            ManageIntent.SwitchStockHomeOn -> if (id != null && !before.switchingHome && after.current?.wizardHome == true) scope.launch { switchStockHomeOn(id) }
            is ManageIntent.Open -> if (event.section != ManageSection.MODULES || d.store.addOns) id?.let { effect(ManageEffect.Navigate("${event.section.key}/${it.value}")) }
            ManageIntent.SetUpAnother -> effect(ManageEffect.Navigate("setup"))
            ManageIntent.CleanAgain -> id?.let { effect(ManageEffect.Navigate("setup/${it.value}")) }
            ManageIntent.AppSettings -> effect(ManageEffect.Navigate("appsettings"))
            ManageIntent.HelpAddTv -> id?.let { effect(ManageEffect.Navigate("report/tv/${it.value}")) }
            // Only once a restart held (or failed): the preview shows the block, and only its Open GitHub sends anything.
            ManageIntent.TellUsItWorked -> if ((after.current?.restarts?.held ?: 0) > 0) id?.let { effect(ManageEffect.Navigate("report/confirm/${it.value}")) }
            is ManageIntent.ReportRestartFailed -> if ((after.current?.restarts?.failed ?: 0) > 0) id?.let {
                effect(ManageEffect.Navigate(IssueRoute.of(it, ReportContext("Manage", ReportContext.named(event.title, after.current?.name), ItWorked.RESTART_FAILED))))
            }
            else -> {}
        }
    }

    // The card's thumbnail is the last screenshot; tapping refreshes it when the TV is connected.
    private suspend fun thumbnail() {
        val id = state.value.current?.id ?: state.value.selected ?: return
        val s = d.access.live(id) ?: return
        val png = attempt { d.prober.screenshot(s) }.getOrNull() ?: return
        send(Thumb(id, png))
    }

    companion object {
        // A TV restart takes one to three minutes; the quiet reconnect tries for five.
        const val AUTO_EVERY_MS = 15_000L
        const val AUTO_TRIES = 21
        const val SETTLE_EVERY_MS = 10_000L
        const val SETTLE_TRIES = 12
    }

    // For the TV card: true while a run is open on this TV (the chip says "Working").
    fun running(tvId: TvId): Boolean = d.engine.runState(tvId).value.let { it !is RunState.Idle && it !is RunState.Finished }
}
