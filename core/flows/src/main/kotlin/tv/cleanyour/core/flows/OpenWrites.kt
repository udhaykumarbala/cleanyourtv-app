package tv.cleanyour.core.flows

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import java.util.concurrent.ConcurrentHashMap

// R5-W8 O2: a Manage write that paused (the TV stopped answering, a step failed) leaves its run
// open, and the engine refuses every later write on that TV (RUN_OPEN) until the run is carried
// on, undone or ended. TvWriter publishes the open run here instead of a generic "failed"
// snackbar, and the app lays the card over whatever screen asked, the way it shows the debugging
// step (Reach). One card at a time: the person acts on one TV at a time.
data class OpenWrite(
    val tvId: TvId,
    val tvName: String,
    val planId: PlanId,
    val kind: PlanKind,              // SETUP: the card opens the wizard's resume card instead of acting here
    val reason: PauseReason,
    val busy: Boolean = false,       // carry on, undo or stop is running
    val notTaken: Boolean = false,   // the engine did not take the last action (the TV went away again)
    val nothingDone: Boolean = false,   // QA of 0.4.5: no step of the run was done (a STALE pause before step 0)
    // 0.4.6: a put back paused at its Home check because the app it switched back on took the Home
    // button from the new home screen (DefaultPlanner.putBack); the app's name. The card says so,
    // and its undo (the run's own) switches the app off again.
    val tookHome: String? = null,
)

enum class OpenWriteAction { CARRY_ON, UNDO, STOP_HERE }

class OpenWrites {
    private val state = MutableStateFlow<OpenWrite?>(null)
    val current: StateFlow<OpenWrite?> = state.asStateFlow()

    fun show(tv: Tv, paused: RunState.Paused, nothingDone: Boolean = false, tookHome: String? = null) {
        state.value = OpenWrite(tv.id, tv.nickname, paused.plan.id, paused.plan.kind, paused.reason, nothingDone = nothingDone, tookHome = tookHome)
    }

    companion object {
        // 0.4.6: the package a put back switched on, when the run paused at the Home check after it
        // (the SetHome or VerifyHome of the new home screen failed: that app answers Home now).
        fun tookHome(paused: RunState.Paused): PackageName? {
            val plan = paused.plan
            // Only Home's answer not matching (the verify's GuardFailed): a refused set-home-activity
            // or a read that failed is the ordinary card.
            if (plan.kind != PlanKind.PUT_BACK || paused.reason != PauseReason.STEP_FAILED || paused.error !is AdbError.Policy.GuardFailed) return null
            val at = plan.steps.getOrNull(paused.at)?.op
            val ours = (at as? Op.SetHome)?.component ?: (at as? Op.VerifyHome)?.expected ?: return null
            if (ours.pkg != Launcher.pkg) return null
            return plan.steps.firstNotNullOfOrNull { (it.op as? Op.Enable)?.pkg ?: (it.op as? Op.DefaultState)?.pkg }
        }
    }

    // QA of 0.4.5: what each Manage write asked for, by its plan, so "Check again" on a run paused
    // STALE can plan the same request again from a fresh read of the TV. Kept for this process
    // only: after a restart the request is unknown, and "Check again" reads the TV and ends the run.
    private val requests = ConcurrentHashMap<PlanId, PlanRequest>()
    fun remember(planId: PlanId, request: PlanRequest) { requests[planId] = request }
    fun requestOf(planId: PlanId): PlanRequest? = requests[planId]

    fun update(tvId: TvId, change: (OpenWrite) -> OpenWrite) {
        state.value?.takeIf { it.tvId == tvId }?.let { state.value = change(it) }
    }

    // The card's close button. The run stays open; the next write on that TV shows the card again.
    fun dismiss() { state.value = null }

    fun clear(tvId: TvId) { if (state.value?.tvId == tvId) state.value = null }
}
