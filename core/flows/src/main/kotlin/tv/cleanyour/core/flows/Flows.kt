// FROZEN INTERFACE (PRODUCT-SPEC section 5.12, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W8.

package tv.cleanyour.core.flows

import tv.cleanyour.core.model.*
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.probe.ProbeStage
import tv.cleanyour.core.store.GatePass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

// R0: 2 compilable skeleton (the earlier sketch declared properties without bodies); reducers stay pure, handlers call send
abstract class Machine<S : Any, I : Any, E : Any>(initial: S, protected val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(initial)
    private val effectChannel = Channel<E>(Channel.BUFFERED)
    val state: StateFlow<S> = mutableState.asStateFlow()
    val effects: Flow<E> = effectChannel.receiveAsFlow()
    fun dispatch(intent: I) = send(intent)
    protected fun send(event: Any) { val before = mutableState.value; val after = reduce(before, event); mutableState.value = after; handle(event, before, after) }
    protected fun effect(effect: E) { effectChannel.trySend(effect) }
    protected abstract fun reduce(state: S, event: Any): S
    protected open fun handle(event: Any, before: S, after: S) {} }   // side effects: launch in scope, feed results back through send

sealed interface SetupState {
    data class Welcome(val found: List<FoundTv>, val onWifi: Boolean, val looking: Boolean) : SetupState
    data class Picker(val found: List<FoundTv>) : SetupState
    data class OpenDoor(val tv: FoundTv, val family: String, val step: Int, val phase: DoorPhase, val fingerprint: String?, val banner: String?, val waitedMs: Long) : SetupState
    data class PairingNeeded(val tv: FoundTv) : SetupState
    data class Probing(val tvId: TvId, val stage: ProbeStage, val partial: PlanSummary?) : SetupState
    data class Found(val tvId: TvId, val plan: Plan, val level: Level, val sheet: Sheet?) : SetupState
    data class Working(val tvId: TvId, val run: RunState.Running, val thumbnail: ByteArray?) : SetupState
    data class Check(val tvId: TvId, val run: RunState.AwaitingConfirm, val thumbnail: ByteArray?, val dockNames: List<String>) : SetupState
    data class Stopped(val tvId: TvId, val card: StopCard) : SetupState
    data class Done(val tvId: TvId, val summary: DoneSummary, val restartState: RestartState?) : SetupState }
enum class DoorPhase { STEPS, CONNECTING, ALLOW, IDENTITY, CONNECTED }
enum class Sheet { LIST, CUSTOMISE, DOCK, THEME, ADDRESS }
// R0: 2 were referenced and never declared; W8 may add fields, never remove
enum class StopCase { HOME_SETUPWRAITH, HOME_OTHER_LAUNCHER, HOME_UNKNOWN, HOME_USER_SAID_NO, CONNECTION_LOST, RESUME_AFTER_DEATH, INSTALL_FAILED, TV_REFUSES_HOME, PAIRING_NEEDED, IDENTITY_CHANGED,
    // W8 additions (growable, R0: 2; DECISIONS-W8.md D-W8-3): the stops of section 4 that had no case
    TV_NOT_FOUND, WRONG_NETWORK, TV_NOT_SUPPORTED, HOME_TIMED_OUT, STEP_FAILED, UNDONE, PROBE_FAILED, NOT_BACK_AFTER_RESTART,
    HOME_PUT_BACK,   // M5: step R put the old home screen back after a lost connection
    PLAN_REFUSED,    // 0.3.4 (issues #171, #187, #220): the TV answered, the planner found no safe plan
    HOME_SETTINGS_OPEN }   // issue #629: the home check rolled back because a Settings page was open on the TV
data class StopCard(val case: StopCase, val title: String, val body: String, val primary: String, val secondary: String?, val planId: PlanId?,
    val tertiary: String? = null)   // W8 addition: S4d and S4e have three buttons
data class DoneSummary(val switchedOff: Int, val homeScreenOn: Boolean, val restorePointAtMs: Long, val skipped: List<String>, val restartRecommended: Boolean)
sealed interface RestartState { data class Restarting(val sinceMs: Long) : RestartState; data class Back(val afterMs: Long) : RestartState
    data class NotBack(val afterMs: Long) : RestartState }
sealed interface SetupIntent { data object Start : SetupIntent; data class PickTv(val host: String) : SetupIntent; data class EnterAddress(val ip: String) : SetupIntent
    data class ChangeFamily(val family: String) : SetupIntent; data object AskTvAgain : SetupIntent; data object ConfirmIdentity : SetupIntent
    data class Keep(val pkg: PackageName, val keep: Boolean) : SetupIntent; data class SetDock(val dock: List<PackageName>) : SetupIntent
    data class SetTheme(val id: String) : SetupIntent; data class KeepDebuggingOn(val on: Boolean) : SetupIntent; data class Open(val sheet: Sheet?) : SetupIntent
    data object MakeItClean : SetupIntent; data class CredentialPassed(val pass: GatePass) : SetupIntent; data object CredentialFailed : SetupIntent   // R0: 6
    data object StopAndUndo : SetupIntent; data object YesISeeIt : SetupIntent; data object NoPutItBack : SetupIntent
    data object TryWithWizardOff : SetupIntent; data object LeaveOldHome : SetupIntent; data object CarryOn : SetupIntent; data object UndoWhatWeDid : SetupIntent
    data object Restart : SetupIntent; data object SaveRestoreNote : SetupIntent; data object AddCard : SetupIntent; data object Finish : SetupIntent
    data class StopAction(val action: tv.cleanyour.core.flows.StopAction) : SetupIntent; data object RejectIdentity : SetupIntent   // RFC-W8-1 (accepted)
    data class ShowOnTv(val on: Boolean) : SetupIntent   // RFC-CURTAIN-1: the Customise switch "Show the progress on the TV"
    // RFC-LOOK-SETUP-1 (0.4.0): the look step has the Look sheet's choices. Apps in a row (4 to 8,
    // anything else is ignored), the picture (HomeChoice.picture, null for the look's own), and a
    // photo the phone just made (16:9, a JPEG of at most 280 KB) with its dim. They only change the
    // draft; the push happens when Setup runs.
    data class SelectColumns(val count: Int) : SetupIntent; data class SelectPicture(val id: String?) : SetupIntent
    class SetOwnPicture(val jpeg: ByteArray, val dim: Int) : SetupIntent
    // look2: every tap of the Look editor in the look step, the same edits as the Look sheet's
    // (HomeScreenIntent.Look). SetTheme, SelectColumns and SelectPicture stay until ui moves.
    data class Look(val edit: LookEdit) : SetupIntent }
// RFC-W8-1: a stop card button by position; the machine maps it per StopCase
enum class StopAction { PRIMARY, SECONDARY, TERTIARY }
sealed interface SetupEffect { data object HapticLight : SetupEffect; data object HapticDouble : SetupEffect; data object HapticFirm : SetupEffect
    data object RequestCredential : SetupEffect; data class Share(val text: String) : SetupEffect; data class Navigate(val key: String) : SetupEffect
    data class Notify(val text: String) : SetupEffect; data object RequestNotificationPermission : SetupEffect }
