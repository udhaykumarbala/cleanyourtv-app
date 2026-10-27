package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PutBackAdvice
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
    private data class WizardHomeRead(val tvId: TvId, val applies: Boolean)
    private data class SwitchingHome(val on: Boolean)
    // 0.4.6: what the last connect read of each TV's Home said (WizardHome), for the cards.
    private val wizardHomes = java.util.concurrent.ConcurrentHashMap<TvId, Boolean>()

    init {
        scope.launch {
            combine(d.tvs.tvs, d.sessions.states) { tvs, states -> tvs to states }.collect { (tvs, states) ->
                val cards = tvs.sortedByDescending { it.lastSeenAt }.map { tv ->
                    val run = d.engine.runState(tv.id).value
                    val offPkgs = d.ledger.switchedOffByUs(tv.id)
                    val off = offPkgs.size
                    val home = d.homePrefs.load(tv.id)
                    val latest = d.snapshots.latest(tv.id)
                    TvCard(
                        id = tv.id, name = tv.nickname, room = tv.room, clean = off > 0, checkedAtMs = tv.lastSeenAt, level = tv.level,
                        chip = chipFor(states[tv.id]?.takeIf { d.access.live(tv.id) != null }, run, tv.debugging),
                        thumbnail = state.value.cards.firstOrNull { it.id == tv.id }?.thumbnail,
                        switchedOff = off, dockNames = home?.let { shownDock(it) }.orEmpty().map { d.nameOf(it) },
                        restorePointAtMs = latest?.takenAtMs, debugging = tv.debugging,
                        helpAddTv = tv.profileId == null && latest != null,
                        ownHome = tv.launcher == null && latest != null && Launcher.pkg !in latest.packages,
                        betterBackOn = if (PutBackAdvice.forProfile(tv.profileId).isEmpty()) 0
                            else PutBackAdvice.offers(tv.profileId, latest, d.ledger.changes(tv.id), d.profileOf(tv)).map { it.pkg }.distinct().count { it in offPkgs },
                        wizardHome = wizardHomes[tv.id] == true,
                    )
                }
                send(Loaded(cards))
            }
        }
        scope.launch { connected() }
    }

    // On a connect: the thumbnail, and the TV's Home read again (0.4.6, WizardHome).
    private suspend fun connected() {
        thumbnail()
        checkWizardHome()
    }

    private suspend fun checkWizardHome() {
        val id = state.value.current?.id ?: state.value.selected ?: return
        val s = d.access.live(id) ?: return
        val tv = d.tv(id) ?: return
        val home = attempt { d.prober.homeNow(s) }.getOrNull() ?: return
        val profile = d.profileOf(tv)
        val stock = WizardHome.stockOf(d.ledger.changes(id), profile)
        val applies = WizardHome.applies(home, attempt { d.snapshots.latest(id) }.getOrNull(), profile, stock)
        wizardHomes[id] = applies
        send(WizardHomeRead(id, applies))
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
        is WizardHomeRead -> state.copy(cards = state.cards.map { if (it.id == event.tvId) it.copy(wizardHome = event.applies) else it })
        is SwitchingHome -> state.copy(switchingHome = event.on)
        is Reconnected -> state.copy(reconnecting = false, offline = !event.ok && state.offline)
        else -> state
    }

    override fun handle(event: Any, before: ManageState, after: ManageState) {
        val id = after.current?.id
        when (event) {
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

    // For the TV card: true while a run is open on this TV (the chip says "Working").
    fun running(tvId: TvId): Boolean = d.engine.runState(tvId).value.let { it !is RunState.Idle && it !is RunState.Finished }
}
