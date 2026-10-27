package tv.cleanyour.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import tv.cleanyour.core.flows.AppConfirm
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.IssueRoute
import tv.cleanyour.core.flows.ManageEffect
import tv.cleanyour.core.flows.ProblemReports
import tv.cleanyour.core.flows.ReportKind
import tv.cleanyour.core.flows.Reach
import tv.cleanyour.core.flows.ReportContext
import tv.cleanyour.core.flows.SetupEffect
import tv.cleanyour.core.flows.SetupStart
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.SnackKind
import tv.cleanyour.core.flows.StartDestination
import tv.cleanyour.core.flows.TvWriter
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import tv.cleanyour.feature.backup.RestoreScreen
import tv.cleanyour.feature.connect.ConnectFlow
import tv.cleanyour.feature.connect.isConnectState
import tv.cleanyour.feature.launcher.HomeScreen
import tv.cleanyour.feature.manage.AppSettingsScreen
import tv.cleanyour.feature.manage.DebugStepSheet
import tv.cleanyour.feature.manage.LeftoversScreen
import tv.cleanyour.feature.manage.ManageScreen
import tv.cleanyour.feature.manage.OpenWriteSheet
import tv.cleanyour.feature.manage.ReportScreen
import tv.cleanyour.feature.manage.SwitchedOffScreen
import tv.cleanyour.feature.manage.TvSettingsScreen
import tv.cleanyour.feature.modules.ModulesFlow
import tv.cleanyour.feature.setup.SetupFlow

// Navigation 3 keys (ARCHITECTURE 2.2): serializable, ids only, never payloads, so the back stack
// itself survives process death and every screen rebuilds its state from disk.
@Serializable sealed interface Dest : NavKey {
    @Serializable data class Setup(val tvId: String? = null, val planId: String? = null) : Dest
    @Serializable data class Manage(val tvId: String? = null) : Dest
    @Serializable data class SwitchedOff(val tvId: String) : Dest
    @Serializable data class Leftovers(val tvId: String) : Dest
    @Serializable data class Home(val tvId: String) : Dest
    @Serializable data class Modules(val tvId: String) : Dest
    @Serializable data class Restore(val tvId: String) : Dest
    @Serializable data class TvSettings(val tvId: String) : Dest
    @Serializable data object AppSettings : Dest
    // 0.3.2 B1, B2: the report preview; kind is ReportKind.key ("tv" or "problem").
    @Serializable data class Report(val kind: String, val tvId: String? = null) : Dest
}

fun startKey(d: StartDestination): Dest = when (d) {
    StartDestination.Welcome -> Dest.Setup()
    is StartDestination.ResumeRun -> Dest.Setup(d.tvId.value, d.planId.value)
    is StartDestination.ContinueSetup -> Dest.Setup(d.tvId.value)
    is StartDestination.Manage -> Dest.Manage(d.tvId.value)
}

// The back stack after a navigation route (ARCHITECTURE 2.2), pure so the rules are unit tested.
// Returns false when the app should be left instead. "returning" ("I've set up a TV before" on
// Welcome) opens Manage on top of Welcome, so Back returns to Welcome; every other Manage route
// (the wizard finished, a TV forgotten) replaces the stack.
// `addOns` false (the Play build, STORES-PLAN 1): "modules" leads nowhere, as no screen offers it.
internal fun applyRoute(stack: MutableList<NavKey>, route: String, noTvs: Boolean, addOns: Boolean = Store.rules.addOns): Boolean {
    val parts = route.split('/')
    val id = parts.getOrNull(1)
    val dest: Dest? = when (parts[0]) {
        "manage" -> Dest.Manage(id)
        "returning" -> { stack.add(Dest.Manage(null)); return true }
        "setup" -> Dest.Setup(id)
        "resume" -> id?.let { t -> parts.getOrNull(2)?.let { p -> Dest.Setup(t, p) } }
        "modules" -> if (addOns) id?.let { Dest.Modules(it) } else null
        "home" -> if (id != null) Dest.Home(id) else null
        "switchedoff" -> id?.let { Dest.SwitchedOff(it) }
        "leftovers" -> id?.let { Dest.Leftovers(it) }
        "restore" -> id?.let { Dest.Restore(it) }
        "tvsettings" -> id?.let { Dest.TvSettings(it) }
        "appsettings" -> Dest.AppSettings
        "report" -> id?.let { k -> ReportKind.of(k) }?.let { k -> if (k == ReportKind.TV && parts.getOrNull(2) == null) null else Dest.Report(k.key, parts.getOrNull(2)) }
        "back" -> return if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
        else -> null
    }
    when {
        route == "home" || route == "welcome" -> { stack.clear(); stack.add(if (noTvs) Dest.Setup() else Dest.Manage(null)) }
        dest is Dest.Manage -> { stack.clear(); stack.add(dest) }
        dest != null -> stack.add(dest)
    }
    return true
}

// The ViewModel only owns the machine's scope (ARCHITECTURE 2.1).
class MachineHost<M : Any>(make: (CoroutineScope) -> M) : ViewModel() {
    val machine: M = make(viewModelScope)
}

@Composable
private inline fun <reified M : Any> rememberMachine(key: String, noinline make: (CoroutineScope) -> M): M =
    viewModel<MachineHost<M>>(key = key) { MachineHost(make) }.machine

// Screens that show a key fingerprint or the reset of the key are FLAG_SECURE and data-sensitive
// for accessibility services (D35). Nothing else is: plan review, progress and the check screen
// may need a screenshot to ask for help.
@Composable
fun SecureWindow(active: Boolean) {
    val view = LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(active) {
        if (active) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        onDispose {
            if (active) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
            }
        }
    }
}

@Composable
fun AppRoot(graph: AppGraph, start: Dest, onExit: () -> Unit) {
    val backStack = rememberNavBackStack(start)
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // 0.4.6 (REPORTING-PROPOSAL 2.2): a card's "Report a problem" (IssueRoute) opens the GitHub form
    // at once, with the report in the link and the card filled in. The whole report is copied too, as
    // the preview's "Open GitHub" does, in case a sign-in on the way drops the link's fields. The app
    // sends nothing: the browser opens the link, and nothing is public until the person posts.
    // A second tap while the report is made does nothing, so one tap gives one tab. With no browser
    // to open the link, the report preview opens instead: its Share reaches any app that can post it.
    val openingIssue = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun openIssue(tvId: TvId?, report: ReportContext, preview: () -> Unit) {
        if (!openingIssue.compareAndSet(false, true)) return
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { ProblemReports.build(graph.manageDeps, tvId, report) }
                copyText(context, r.full, label = "report")
                val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.link.url))) }.isSuccess
                if (!opened) preview()
            } finally {
                openingIssue.set(false)
            }
        }
    }

    fun go(route: String) {
        IssueRoute.parse(route)?.let { (tvId, report) ->
            openIssue(tvId, report) { go(tvId?.let { "report/problem/${it.value}" } ?: "report/problem") }
            return
        }
        if (!applyRoute(backStack, route, noTvs = graph.tvs.tvs.value.isEmpty(), addOns = graph.store.rules.addOns)) onExit()
    }

    fun haptic(kind: Int) { view.performHapticFeedback(kind) }
    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    }

    val undoLabel = androidx.compose.ui.res.stringResource(tv.cleanyour.feature.manage.R.string.action_undo)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // 0.4.0 "Your photo" on the Look sheet: the system photo picker, images only, no permission.
    var onPhoto by remember { mutableStateOf<((android.net.Uri) -> Unit)?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPhoto?.invoke(uri); onPhoto = null }
    val photoPicker = remember(pickPhoto) {
        tv.cleanyour.ui.design.component.PhotoPicker { cb ->
            onPhoto = cb
            pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    suspend fun onSetupEffect(e: SetupEffect) = when (e) {
        SetupEffect.HapticLight -> haptic(HapticFeedbackConstants.CLOCK_TICK)
        SetupEffect.HapticDouble -> haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        SetupEffect.HapticFirm -> haptic(HapticFeedbackConstants.LONG_PRESS)
        SetupEffect.RequestCredential -> {}     // AndroidCredentialGate raises the system prompt itself
        is SetupEffect.Share -> share(e.text)
        is SetupEffect.Navigate -> go(e.key)
        is SetupEffect.Notify -> {}            // the foreground service's notification carries it (D18)
        SetupEffect.RequestNotificationPermission -> if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else Unit
    }

    suspend fun onManageEffect(e: ManageEffect, onDocument: (String) -> Unit = {}, onUndo: (ManageEffect.Snackbar) -> Unit = {}) {
        when (e) {
            is ManageEffect.Navigate -> go(e.key)
            is ManageEffect.Share -> share(e.text)
            is ManageEffect.OpenUrl -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.url))) }
            is ManageEffect.Copy -> copyText(context, e.text, label = "report")
            ManageEffect.HapticLight -> haptic(HapticFeedbackConstants.CLOCK_TICK)
            ManageEffect.HapticDouble -> haptic(HapticFeedbackConstants.LONG_PRESS)
            is ManageEffect.CreateDocument -> onDocument(e.suggestedName)
            is ManageEffect.Snackbar -> {
                val (id, args) = SnackText.of(e)
                val r = snack.showSnackbar(ResourceFlowCopy.format(context, id, args), actionLabel = if (e.undo) undoLabel else null,
                    duration = androidx.compose.material3.SnackbarDuration.Short)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndo(e)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onExit() },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<Dest.Setup> { key ->
                    val start = when {
                        key.planId != null && key.tvId != null -> SetupStart.Resume(TvId(key.tvId), PlanId(key.planId))
                        key.tvId != null -> SetupStart.Continue(TvId(key.tvId))
                        else -> SetupStart.Fresh
                    }
                    val m = rememberMachine("setup:${key.tvId}:${key.planId}") { graph.setup(it, start) }
                    val state by m.state.collectAsStateWithLifecycle()
                    val extras by m.extras.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onSetupEffect(it) } }
                    BackHandler { if (!m.back()) { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onExit() } }
                    SecureWindow((state as? SetupState.OpenDoor)?.phase == DoorPhase.ALLOW)
                    if (isConnectState(state)) ConnectFlow(state, extras, m, onReturning = { go("returning") }, onCopyError = { copyText(context, it, "error") })
                    else SetupFlow(state, extras, m, onCopyCommands = { copyText(context, it) }, onRoute = { go(it) }, photos = photoPicker)
                }
                entry<Dest.Manage> { key ->
                    val m = rememberMachine("manage:${key.tvId}") { graph.manage(it, key.tvId?.let(::TvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    ManageScreen(state, m::dispatch)
                }
                entry<Dest.SwitchedOff> { key ->
                    val m = rememberMachine("off:${key.tvId}") { graph.switchedOff(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    // UX 4.13: "Put back" ends with a 5 s snackbar whose Undo switches the app off again.
                    LaunchedEffect(m) {
                        m.effects.collect { e -> onManageEffect(e, onUndo = { s -> s.pkg?.let { m.dispatch(tv.cleanyour.core.flows.SwitchedOffIntent.Undo(it)) } }) }
                    }
                    SwitchedOffScreen(state, m::dispatch, onBack = { go("back") })
                }
                entry<Dest.Leftovers> { key ->
                    val m = rememberMachine("left:${key.tvId}") { graph.leftovers(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    LeftoversScreen(state, m::dispatch, onBack = { go("back") })
                }
                entry<Dest.Home> { key ->
                    val m = rememberMachine("home:${key.tvId}") { graph.homeScreen(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    HomeScreen(state, m::dispatch, onBack = { go("back") }, photos = photoPicker, onReport = { lead ->
                        go(IssueRoute.of(TvId(key.tvId), ReportContext("Home screen", ReportContext.named(lead, state.tvName), "HOME_BLOCKED_${state.notOnTv?.reason ?: "NONE"}")))
                    })
                }
                entry<Dest.Modules> { key ->
                    val m = rememberMachine("modules:${key.tvId}") { graph.modules(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    ModulesFlow(state, m::dispatch, onBack = { go("back") }, onOpenUrl = { url -> scope.launch { onManageEffect(ManageEffect.OpenUrl(url)) } })
                }
                entry<Dest.Restore> { key ->
                    val m = rememberMachine("restore:${key.tvId}") { graph.restorePoint(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        if (uri != null) scope.launch {
                            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }?.use { out -> m.writeBundle(out) }
                        }
                    }
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it, onDocument = { name -> create.launch(name) }) } }
                    // D35: the backup export is FLAG_SECURE while it is written.
                    SecureWindow(state.exporting)
                    RestoreScreen(state, m::dispatch, onBack = { go("back") })
                }
                entry<Dest.TvSettings> { key ->
                    val m = rememberMachine("tvsettings:${key.tvId}") { graph.tvSettings(it, TvId(key.tvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    TvSettingsScreen(state, m::dispatch, onBack = { go("back") })
                }
                entry<Dest.Report> { key ->
                    val kind = ReportKind.of(key.kind) ?: ReportKind.PROBLEM
                    val m = rememberMachine("report:${key.kind}:${key.tvId}") { graph.report(it, kind, key.tvId?.let(::TvId)) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    ReportScreen(state, m::dispatch, onBack = { go("back") })
                }
                entry<Dest.AppSettings> {
                    val m = rememberMachine("appsettings") { graph.appSettings(it) }
                    val state by m.state.collectAsStateWithLifecycle()
                    LaunchedEffect(m) { m.effects.collect { onManageEffect(it) } }
                    SecureWindow(state.confirm == AppConfirm.RESET_KEY)
                    AppSettingsScreen(state, m::dispatch, onBack = { go("back") },
                        onOpenRelease = { graph.store.releasePage?.let { url -> scope.launch { onManageEffect(ManageEffect.OpenUrl(url)) } } })
                }
            },
        )
        // R5-W8 O2: a Manage write that paused left its run open; carry on, undo or stop it here.
        val open by graph.manageDeps.openWrites.current.collectAsStateWithLifecycle()
        open?.let { w ->
            OpenWriteSheet(w,
                onAction = { a -> graph.scope.launch { TvWriter(graph.manageDeps).resolveOpen(w.tvId, a) } },
                onOpenSetup = { graph.manageDeps.openWrites.dismiss(); go("resume/${w.tvId.value}/${w.planId.value}") },
                onDismiss = { graph.manageDeps.openWrites.dismiss() },
                onReport = { title ->
                    graph.manageDeps.openWrites.dismiss()
                    go(IssueRoute.of(w.tvId, ReportContext("Manage", ReportContext.named(title, w.tvName), "${w.kind}_PAUSED_${w.reason}")))
                })
        }
        // M1a: any returning write that finds port 5555 closed shows the debugging step here.
        val reach by graph.access.reach.collectAsStateWithLifecycle()
        if (reach !is Reach.Idle) DebugStepSheet(reach) { graph.access.cancelWaiting() }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

fun copyText(context: android.content.Context, text: String, label: String = "commands") {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

// Snackbar sentences for the Manage family (the screens own the copy, the app only formats it).
object SnackText {
    fun of(e: ManageEffect.Snackbar): Pair<Int, Map<String, Any>> = when (e.kind) {
        SnackKind.PUT_BACK -> tv.cleanyour.feature.manage.R.string.switchedoff_snackbar to mapOf("name" to e.name)
        SnackKind.SWITCHED_OFF -> tv.cleanyour.feature.setup.R.string.customise_snackbar_off to mapOf("name" to e.name)
        SnackKind.HOME_SAVED -> tv.cleanyour.feature.launcher.R.string.home_snackbar_done to emptyMap()
        SnackKind.MODULE_REMOVED -> tv.cleanyour.feature.modules.R.string.modules_removed to mapOf("name" to e.name)
        SnackKind.EXPORT_SAVED -> tv.cleanyour.feature.backup.R.string.restore_export_saved to emptyMap()
        SnackKind.EXPORT_FAILED -> tv.cleanyour.feature.backup.R.string.restore_export_failed to emptyMap()
        SnackKind.FAILED -> tv.cleanyour.feature.setup.R.string.stop_step_failed_title to emptyMap()
        SnackKind.HOME_UPDATED -> tv.cleanyour.feature.launcher.R.string.home_update_done to mapOf("version" to e.name)
        SnackKind.KEPT_TV_INPUT -> tv.cleanyour.feature.setup.R.string.kept_tv_input to mapOf("name" to e.name)
        SnackKind.KEPT_ON -> tv.cleanyour.feature.setup.R.string.done_skipped to mapOf("name" to e.name)
        SnackKind.RUN_BUSY -> tv.cleanyour.feature.manage.R.string.tvsettings_forget_busy to emptyMap()
        SnackKind.RUN_BUSY_ANY -> tv.cleanyour.feature.manage.R.string.appsettings_delete_all_busy to mapOf("tv" to e.name)
    }
}

