package tv.cleanyour.core.flows

import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest

// M6 Restore point (UX 4.13, F10): Undo everything with its preview, the one outlined danger button
// inside its confirmation sheet, the credential prompt, narration, and the end line; "Save a copy"
// as a SAF zip; "Before a factory reset" on Google TV.
// `launcherInstalled` (0.4.7, UX finding 17): the new home screen is on the TV, so the preview asks
// whether to keep or remove it; on a TV where Setup never installed it there is nothing to ask.
// `helperOff` (RFC-HELPER-1, 0.4.7-beta1 QA): the undo takes our HomeKeyService out of the TV's list, so the sheet says so.
data class UndoPreview(val plan: Plan, val thingsBack: Int, val stockName: String?, val wizardBack: Boolean, val removeLauncher: Boolean,
                       val launcherInstalled: Boolean = true, val helperOff: Boolean = false)

sealed interface RestorePhase {
    data object Idle : RestorePhase
    data object Previewing : RestorePhase
    data class Preview(val preview: UndoPreview, val confirming: Boolean) : RestorePhase
    data class Working(val kind: String, val percent: Int, val lines: List<String>) : RestorePhase   // kind: "undo" or "factory"
    data class Finished(val kind: String, val atMs: Long) : RestorePhase
    data object Failed : RestorePhase
}

data class RestorePointState(
    val tvName: String = "",
    val baselineAtMs: Long? = null,
    val latestAtMs: Long? = null,
    // 0.4.7 (UX finding 17): "Before a factory reset" only when this app switched the setup wizard
    // off on this TV (the ledger says so); it was `googleTv`, every Google TV profile, before.
    val wizardOff: Boolean = false,
    val phase: RestorePhase = RestorePhase.Idle,
    val exporting: Boolean = false,
)

sealed interface RestorePointIntent {
    data object PreviewUndo : RestorePointIntent
    data class RemoveLauncher(val remove: Boolean) : RestorePointIntent
    data object Confirm : RestorePointIntent           // opens the confirmation sheet
    data object PutEverythingBack : RestorePointIntent
    data object Cancel : RestorePointIntent
    data object SaveCopy : RestorePointIntent
    data object BeforeFactoryReset : RestorePointIntent
    data object CleanAgain : RestorePointIntent
}

class RestorePointMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<RestorePointState, RestorePointIntent, ManageEffect>(RestorePointState(), scope) {

    private val writer = TvWriter(d)
    private data class Loaded(val name: String, val baseline: Long?, val latest: Long?, val wizardOff: Boolean)
    private data class Phase(val p: RestorePhase)
    private data class Exporting(val on: Boolean)

    init { scope.launch { load() } }

    override fun reduce(state: RestorePointState, event: Any): RestorePointState = when (event) {
        is Loaded -> state.copy(tvName = event.name, baselineAtMs = event.baseline, latestAtMs = event.latest, wizardOff = event.wizardOff)
        is Phase -> state.copy(phase = event.p)
        is Exporting -> state.copy(exporting = event.on)
        RestorePointIntent.PreviewUndo -> state.copy(phase = RestorePhase.Previewing)
        RestorePointIntent.Confirm -> (state.phase as? RestorePhase.Preview)?.let { state.copy(phase = it.copy(confirming = true)) } ?: state
        RestorePointIntent.Cancel -> state.copy(phase = RestorePhase.Idle)
        else -> state
    }

    override fun handle(event: Any, before: RestorePointState, after: RestorePointState) {
        when (event) {
            RestorePointIntent.PreviewUndo -> scope.launch { preview(removeLauncher = false) }
            is RestorePointIntent.RemoveLauncher -> if (before.phase is RestorePhase.Preview) scope.launch { preview(event.remove) }
            RestorePointIntent.PutEverythingBack -> (before.phase as? RestorePhase.Preview)?.let { p -> if (p.confirming) scope.launch { undoEverything(p.preview) } }
            RestorePointIntent.SaveCopy -> if (!before.exporting) d.tv(tvId)?.let { tv ->
                val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date(d.clock.nowMs()))
                val model = tv.facts.model.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
                effect(ManageEffect.CreateDocument("cleanyourtv-restore-$model-$date.zip"))
            }
            RestorePointIntent.BeforeFactoryReset -> scope.launch { factoryReset() }
            RestorePointIntent.CleanAgain -> effect(ManageEffect.Navigate("setup/${tvId.value}"))
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        val wraith = profile?.setupWraith
        val wizardOff = profile?.platform == Platform.GOOGLE_TV && wraith != null && wraith in attempt { d.ledger.switchedOffByUs(tvId) }.getOrDefault(emptySet())
        send(Loaded(tv.nickname, d.snapshots.baseline(tvId)?.takenAtMs, d.snapshots.latest(tvId)?.takenAtMs, wizardOff))
    }

    // The inverse is computed from the journals, never from "enable everything disabled", so things
    // the owner switched off before the app stay off (ARCHITECTURE 2.4).
    // 0.4.8 (issue #2825): the preview and the run plan from a fresh read of the TV, as "Switch off" of the Home button
    // helper does. Taking our helper out has the whole accessibility list as its prior, which a restart can change
    // (and an app's state can change too); from a stored snapshot the run paused as stale at that step.
    private suspend fun preview(removeLauncher: Boolean) {
        val changes = d.ledger.changes(tvId)
        val plan = writer.preview(tvId, PlanRequest.UndoEverything(changes, removeLauncher), fresh = true) ?: return send(Phase(RestorePhase.Failed))
        val tv = d.tv(tvId)
        val profile = tv?.let { d.profileOf(it) }
        val stock = profile?.stockLaunchers?.firstOrNull()?.pkg
        val wraith = profile?.setupWraith
        val back = plan.steps.count { it.op is Op.Enable || it.op is Op.DefaultState }
        val wizard = wraith != null && plan.steps.any { (it.op as? Op.Enable)?.pkg == wraith }
        val installed = d.snapshots.latest(tvId)?.packages?.containsKey(Launcher.pkg) == true || tv?.launcher != null
        val helper = plan.steps.any { it.op is Op.AccessibilityRemove }
        send(Phase(RestorePhase.Preview(UndoPreview(plan, back, stock?.let { d.nameOf(it) }, wizard, removeLauncher, installed, helper), confirming = false)))
    }

    private suspend fun undoEverything(p: UndoPreview) {
        send(Phase(RestorePhase.Working("undo", 0, emptyList())))
        val r = writer.write(tvId, PlanRequest.UndoEverything(d.ledger.changes(tvId), p.removeLauncher), fresh = true) { b ->
            if (b is Busy.Working) send(Phase(RestorePhase.Working("undo", b.percent, b.lines)))
        }
        when (r) {
            is WriteResult.Done -> {
                d.tv(tvId)?.let { d.tvs.upsert(it.copy(launcher = if (p.removeLauncher) null else it.launcher)) }
                send(Phase(RestorePhase.Finished("undo", d.snapshots.baseline(tvId)?.takenAtMs ?: d.clock.nowMs())))
                effect(ManageEffect.HapticDouble)
            }
            WriteResult.GateCancelled, WriteResult.NoTv, is WriteResult.Paused -> send(Phase(RestorePhase.Preview(p, confirming = false)))   // Paused: the open-run card (O2)
            else -> send(Phase(RestorePhase.Failed))
        }
        load()
    }

    private suspend fun factoryReset() {
        send(Phase(RestorePhase.Working("factory", 0, emptyList())))
        val r = writer.write(tvId, PlanRequest.BeforeFactoryReset) { b -> if (b is Busy.Working) send(Phase(RestorePhase.Working("factory", b.percent, b.lines))) }
        send(Phase(if (r is WriteResult.Done) RestorePhase.Finished("factory", d.clock.nowMs()) else if (r is WriteResult.GateCancelled || r is WriteResult.NoTv || r is WriteResult.Paused) RestorePhase.Idle else RestorePhase.Failed))
    }

    // Called by the app with the stream SAF opened for the CreateDocument effect.
    suspend fun writeBundle(sink: OutputStream): Boolean {
        val tv = d.tv(tvId) ?: return false
        send(Exporting(true))
        val ok = attempt {
            val baseline = d.snapshots.baseline(tvId) ?: d.snapshots.latest(tvId) ?: error("no restore point yet")
            d.bundleWriter.write(tv, baseline, d.ledger.changes(tvId), overlay = null, wallpaper = null, sink = sink)
        }.isSuccess
        send(Exporting(false))
        effect(ManageEffect.Snackbar(if (ok) SnackKind.EXPORT_SAVED else SnackKind.EXPORT_FAILED, "", false))
        return ok
    }
}
