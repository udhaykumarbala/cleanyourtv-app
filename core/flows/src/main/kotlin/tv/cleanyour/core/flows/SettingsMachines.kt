package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.ADGUARD_PRIVATE_DNS_HOST
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest

// M7 TV settings: name and room, level (D46: per TV), the address (read-only), "Forget this TV"
// (credential prompt; deletes the record, the journals and the restore points on this phone).
data class TvSettingsState(
    val name: String = "",
    val room: String? = null,
    val level: Level = Level.SIMPLE,
    val address: String? = null,
    val lastSeenAtMs: Long? = null,
    val confirmingForget: Boolean = false,
    val forgetting: Boolean = false,
    val isAdGuardDnsActive: Boolean = false,
    val privateDnsMode: String? = null,
    val privateDnsSpecifier: String? = null,
    val dnsBusy: Boolean = false,
)

sealed interface TvSettingsIntent {
    data class Rename(val name: String, val room: String?) : TvSettingsIntent
    data class SetLevel(val level: Level) : TvSettingsIntent
    data object AskForget : TvSettingsIntent
    data object CancelForget : TvSettingsIntent
    data object Forget : TvSettingsIntent
    data object SetAdGuardDns : TvSettingsIntent
    data object TurnOffPrivateDns : TvSettingsIntent
}

class TvSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<TvSettingsState, TvSettingsIntent, ManageEffect>(TvSettingsState(), scope) {

    private data class Loaded(val s: TvSettingsState)
    private data class Forgetting(val on: Boolean)
    private data class DnsBusy(val busy: Boolean)
    private data class DnsUpdated(val mode: String?, val specifier: String?, val busy: Boolean = false)

    init {
        scope.launch {
            d.tvs.tvs.collect { list ->
                list.firstOrNull { it.id == tvId }?.let { tv ->
                    val snap = d.snapshots.latest(tvId)
                    val mode = snap?.settings?.get(SettingKey.PRIVATE_DNS_MODE)
                    val spec = snap?.settings?.get(SettingKey.PRIVATE_DNS_SPECIFIER)
                    val adGuard = mode == "hostname" && spec == ADGUARD_PRIVATE_DNS_HOST
                    send(
                        Loaded(
                            TvSettingsState(
                                name = tv.nickname,
                                room = tv.room,
                                level = tv.level,
                                address = tv.endpoints.firstOrNull()?.host,
                                lastSeenAtMs = tv.lastSeenAt,
                                isAdGuardDnsActive = adGuard,
                                privateDnsMode = mode,
                                privateDnsSpecifier = spec,
                            ),
                        ),
                    )
                }
            }
        }
        scope.launch { refreshDnsFromTv() }
    }

    override fun reduce(state: TvSettingsState, event: Any): TvSettingsState = when (event) {
        is Loaded -> event.s.copy(
            confirmingForget = state.confirmingForget,
            forgetting = state.forgetting,
            dnsBusy = state.dnsBusy,
        )
        TvSettingsIntent.AskForget -> state.copy(confirmingForget = true)
        TvSettingsIntent.CancelForget -> state.copy(confirmingForget = false)
        is Forgetting -> state.copy(forgetting = event.on, confirmingForget = false)
        is DnsBusy -> state.copy(dnsBusy = event.busy)
        is DnsUpdated -> state.copy(
            dnsBusy = event.busy,
            privateDnsMode = event.mode,
            privateDnsSpecifier = event.specifier,
            isAdGuardDnsActive = event.mode == "hostname" && event.specifier == ADGUARD_PRIVATE_DNS_HOST,
        )
        else -> state
    }

    override fun handle(event: Any, before: TvSettingsState, after: TvSettingsState) {
        when (event) {
            is TvSettingsIntent.Rename -> scope.launch {
                val name = event.name.trim().take(40)
                if (name.isNotEmpty()) d.tv(tvId)?.let { d.tvs.upsert(it.copy(nickname = name, room = event.room?.trim()?.take(40)?.ifEmpty { null })) }
            }
            // "Back to Simple hides the extra options. Your TV stays as it is." (UX 3)
            is TvSettingsIntent.SetLevel -> scope.launch { d.tv(tvId)?.let { d.tvs.upsert(it.copy(level = event.level)) } }
            TvSettingsIntent.Forget -> if (!before.forgetting) scope.launch { forget() }
            TvSettingsIntent.SetAdGuardDns -> if (!before.dnsBusy && !before.forgetting) scope.launch {
                setPrivateDns("hostname", ADGUARD_PRIVATE_DNS_HOST)
            }
            TvSettingsIntent.TurnOffPrivateDns -> if (!before.dnsBusy && !before.forgetting) scope.launch {
                setPrivateDns("off", null)
            }
            else -> {}
        }
    }

    private suspend fun refreshDnsFromTv() {
        val live = d.access.live(tvId) ?: return
        val snap = attempt { d.runner.freshSnapshot(live) }.getOrNull() ?: return
        send(DnsUpdated(snap.settings[SettingKey.PRIVATE_DNS_MODE], snap.settings[SettingKey.PRIVATE_DNS_SPECIFIER], state.value.dnsBusy))
    }

    private suspend fun setPrivateDns(mode: String, specifier: String?) {
        val tv = d.tv(tvId) ?: return
        send(DnsBusy(true))
        when (TvWriter(d).write(tvId, PlanRequest.SetPrivateDns(mode, specifier), fresh = true)) {
            is WriteResult.Done -> {
                val live = d.access.live(tvId)
                val snap = if (live != null) attempt { d.runner.freshSnapshot(live) }.getOrNull() else null
                val updatedMode = snap?.settings?.get(SettingKey.PRIVATE_DNS_MODE) ?: mode
                val updatedSpec = snap?.settings?.get(SettingKey.PRIVATE_DNS_SPECIFIER) ?: specifier
                d.tv(tvId)?.let { d.tvs.upsert(it.copy(lastSeenAt = d.clock.nowMs())) }
                send(DnsUpdated(updatedMode, updatedSpec, busy = false))
                val kind = if (mode == "hostname") SnackKind.PRIVATE_DNS_SAVED else SnackKind.PRIVATE_DNS_OFF
                effect(ManageEffect.Snackbar(kind, specifier ?: "", undo = false))
            }
            WriteResult.GateCancelled, WriteResult.NoTv, is WriteResult.Paused -> send(DnsBusy(false))
            else -> {
                send(DnsBusy(false))
                effect(ManageEffect.Snackbar(SnackKind.FAILED, tv.nickname, undo = false))
            }
        }
    }

    private suspend fun forget() {
        val tv = d.tv(tvId) ?: return
        // A run still at work on this TV (step R included) is never cut off from here: its journal is
        // what puts the TV's own home screen back. Wait for it, or stop it first.
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        d.gate.pass(tvId, tv.nickname) ?: return send(Forgetting(false))
        if (d.engine.isWorking(tvId)) { send(Forgetting(false)); effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false)); return }
        send(Forgetting(true))
        // D7: closing the session ends it, so debugging goes off first if a write ran this session.
        attempt { d.sessionEnd.end(tvId) }
        attempt { d.sessions.existing(tvId)?.close() }
        d.documents.deleteTree("tvs/${tvId.value}")
        d.ledger.forget(tvId)
        d.gate.forget(tvId)
        d.tvs.forget(tvId)
        // Issue #2: the engine drops its in-memory run for this TV once the journal is gone (an
        // abandon of a forgotten run needs no TV), so a stuck setup ends here without a force stop
        // and a new Setup is not refused RUN_OPEN. After the record is gone, so nothing reconnects.
        attempt { d.engine.abandon(tvId) }
        send(Forgetting(false))
        effect(ManageEffect.Navigate("home"))
    }
}

// M8 App settings.
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Available(val version: String) : UpdateCheck
    data object Latest : UpdateCheck
    data object Failed : UpdateCheck
}

data class AppSettingsState(
    val version: String = "",
    val prefs: AppPrefsValue = AppPrefsValue(),
    val launcherPkg: String = "",
    val launcherVersion: String = "",
    val launcherDigest: String = "",
    val keyBacking: String = "",
    val keyFingerprint: String = "",
    val update: UpdateCheck = UpdateCheck.Idle,
    // STORES-PLAN 1: "Check for updates" exists only in the GitHub and F-Droid build, and the
    // privacy line names GitHub only when the app can reach it (an update check or an add-on).
    val updateCheck: Boolean = true,
    val addOns: Boolean = true,
    val confirm: AppConfirm? = null,
    val working: Boolean = false,
)

enum class AppConfirm { RESET_KEY, DELETE_EVERYTHING }

sealed interface AppSettingsIntent {
    data class SetHaptics(val on: Boolean) : AppSettingsIntent
    data class SetLargeText(val on: Boolean) : AppSettingsIntent
    data object CheckUpdates : AppSettingsIntent
    data object ReportProblem : AppSettingsIntent     // 0.3.2 B2: the report preview, its own screen
    data class Ask(val what: AppConfirm) : AppSettingsIntent
    data object CancelConfirm : AppSettingsIntent
    data object ResetKey : AppSettingsIntent
    data object DeleteEverything : AppSettingsIntent
}

// A run at work on the TV: running, waiting for the person's answer on the check screen, putting
// the TV's own home screen back (step R) or re-reading the step it was in doubt about. Forget this TV
// and Delete everything wait for it: they would delete the journal it is writing.
internal fun Engine.isWorking(tvId: TvId): Boolean = when (runState(tvId).value) {
    is RunState.Running, is RunState.AwaitingConfirm, is RunState.RollingBack, is RunState.InDoubt -> true
    else -> false
}

// The app-level actions are gated against a placeholder TV id: the gate is per TV (5.13) and
// "Reset my key" and "Delete everything" belong to no single TV.
val APP_GATE_ID = TvId("0000000000000000")

class AppSettingsMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
) : Machine<AppSettingsState, AppSettingsIntent, ManageEffect>(AppSettingsState(updateCheck = d.store.updateCheck, addOns = d.store.addOns), scope) {

    private data class Loaded(val s: AppSettingsState)
    private data class Update(val u: UpdateCheck)
    private data class Working(val on: Boolean)

    init {
        scope.launch {
            val pin = attempt { d.launcherPin.bundled() }.getOrNull()
            send(Loaded(AppSettingsState(
                version = d.appVersion, prefs = d.appPrefs.load(),
                launcherPkg = pin?.pkg?.value.orEmpty(), launcherVersion = pin?.versionName.orEmpty(),
                launcherDigest = pin?.let { keyDigest12(it.sha256) }.orEmpty(),
                keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""), keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""),
                updateCheck = d.store.updateCheck, addOns = d.store.addOns,
            )))
        }
    }

    override fun reduce(state: AppSettingsState, event: Any): AppSettingsState = when (event) {
        is Loaded -> event.s.copy(update = state.update, confirm = state.confirm)
        is AppSettingsIntent.SetHaptics -> state.copy(prefs = state.prefs.copy(haptics = event.on))
        is AppSettingsIntent.SetLargeText -> state.copy(prefs = state.prefs.copy(largeTextFollowsSystem = event.on))
        AppSettingsIntent.CheckUpdates -> if (state.updateCheck) state.copy(update = UpdateCheck.Checking) else state
        is Update -> state.copy(update = event.u)
        is AppSettingsIntent.Ask -> state.copy(confirm = event.what)
        AppSettingsIntent.CancelConfirm -> state.copy(confirm = null)
        is Working -> state.copy(working = event.on, confirm = null)
        else -> state
    }

    override fun handle(event: Any, before: AppSettingsState, after: AppSettingsState) {
        when (event) {
            is AppSettingsIntent.SetHaptics, is AppSettingsIntent.SetLargeText -> scope.launch { d.appPrefs.save(after.prefs) }
            AppSettingsIntent.CheckUpdates -> if (d.store.updateCheck && before.update != UpdateCheck.Checking) scope.launch {
                val v = attempt { d.updates.latestNewerThan(d.appVersion) }.getOrNull()
                send(Update(when { v == null -> UpdateCheck.Failed; v.isEmpty() -> UpdateCheck.Latest; else -> UpdateCheck.Available(v) }))
            }
            AppSettingsIntent.ReportProblem -> effect(ManageEffect.Navigate("report/problem"))
            AppSettingsIntent.ResetKey -> if (!before.working) scope.launch { resetKey() }
            AppSettingsIntent.DeleteEverything -> if (!before.working) scope.launch { deleteEverything() }
            else -> {}
        }
    }

    // Delete everything waits for a run at work on any TV, as Forget this TV does.
    private fun refusedWhileWorking(): Boolean {
        val busy = d.tvs.tvs.value.firstOrNull { d.engine.isWorking(it.id) } ?: return false
        send(Working(false))
        effect(ManageEffect.Snackbar(SnackKind.RUN_BUSY_ANY, busy.nickname, undo = false))
        return true
    }

    // "Reset my key" (F2): every TV asks to trust this phone again.
    private suspend fun resetKey() {
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_RESET_KEY), forcePrompt = true) ?: return send(Working(false))
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        attempt { d.keyInfo.reset() }
        d.gate.clear()
        send(Working(false))
        send(Loaded(state.value.copy(keyFingerprint = attempt { d.keyInfo.fingerprint }.getOrDefault(""), keyBacking = attempt { d.keyInfo.backing }.getOrDefault(""))))
    }

    // "Delete everything" (F13): every TV record, journal, restore point and cached download, the key,
    // and the app's diagnostics log.
    private suspend fun deleteEverything() {
        if (refusedWhileWorking()) return
        d.gate.passWithSubtitle(APP_GATE_ID, d.copy.text(CopyKey.GATE_SUBTITLE_DELETE_ALL), forcePrompt = true) ?: return send(Working(false))
        if (refusedWhileWorking()) return
        send(Working(true))
        attempt { d.sessionEnd.endAll() }   // D7: every session closes here
        attempt { d.sessions.closeAll() }
        for (tv in d.tvs.tvs.value) {
            d.documents.deleteTree("tvs/${tv.id.value}")
            d.tvs.forget(tv.id)
            attempt { d.engine.abandon(tv.id) }   // as in Forget this TV: the engine drops a run whose journal is gone
        }
        attempt { d.documents.deleteTree("registry") }
        attempt { d.documents.delete("settings.txt") }
        attempt { d.documents.delete(CandidateChoices.PATH) }   // test build: the answers on the candidate list card
        attempt { d.keyInfo.reset() }
        attempt { d.diagnostics.clear() }   // the app's own diagnostics log goes too
        d.gate.clear()
        send(Working(false))
        effect(ManageEffect.Navigate("welcome"))
    }
}
