package tv.cleanyour.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.ProbeKind
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ReadArg
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.PackageDump
import tv.cleanyour.core.ops.Privilege
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.core.ops.VerifyResult
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.Step
import tv.cleanyour.core.planner.StockHome
import tv.cleanyour.core.planner.SwitchOffRule
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.session.BootOutcome
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.GatePass
import tv.cleanyour.core.store.SnapshotStore
import java.util.concurrent.ConcurrentHashMap

// The executor (PRODUCT-SPEC 5.9, SECURITY 3.5, ARCHITECTURE 2.4). One op per exec, the journal
// written and forced before every line, verify read after every write, the denylist checked again
// with live facts, the plan hash compared with what the UI confirmed, a GatePass required for any
// write (D17), and the HOME takeover run as a guarded section with the phone-side timer, the live
// focus check and step R (section 3.4). A run lives in the engine's own scope; the UI watches
// runState. Steps are cancellable only between steps.
class DefaultEngine(
    private val renderer: OpRenderer,
    private val prober: Prober,
    private val journal: Journal,
    private val snapshots: SnapshotStore,
    private val plans: PlanStore,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val profiles: ProfileSource? = null,
    private val liveCheckEveryMs: Long = 2_000L,
    private val thumbnailEveryMs: Long = 5_000L,
    private val recentThumbnailMs: Long = 15_000L,
    private val noThumbnailWindowMs: Long = 120_000L,
    private val bootTimeoutMs: Long = 300_000L,
    // Redacted lines for the diagnostics log (issue #375): only fixed words and the TV's own
    // package names, never an app the person installed. Defaulted and last, so every caller compiles.
    private val log: (String) -> Unit = {},
    // Issue #629: what the home check saw in front when it rolled back (WRONG_FOCUS), so Setup can
    // tell a Settings page the person opened from a home screen that did not hold. Defaulted, last.
    private val onWrongFocus: (TvId, Component) -> Unit = { _, _ -> },
) : Engine {

    // `fromJournalOnly`: the plan file could not be trusted on resume, so this run holds only the
    // open section's step R, rebuilt from the journal (R1 R-2); it ends ABANDONED once step R holds.
    private class Run(val plan: Plan, val session: TvSession, val fromJournalOnly: Boolean = false) {
        @Volatile var at = 0
        val done = sortedSetOf<Int>()
        val skipped = mutableListOf<Step>()
        val states = Array(plan.steps.size) { NarrationState.UPCOMING }
        var restorePoint = NarrationState.DOING
        var snapshot: Snapshot? = null
        var profile: Profile? = null
        var stockLaunchers: List<Component> = emptyList()   // the resolution's, as the planner had them (SwitchOffRule)
        @Volatile var stopRequested = false
        val decisions = Channel<Boolean>(Channel.CONFLATED)
        var job: Job? = null
        var hold: SessionHold? = null
        var thumbnail: ByteArray? = null
        var lastThumbnailOkAt = Long.MIN_VALUE / 2
        var lastThumbnailTryAt = Long.MIN_VALUE / 2
        val openSections = linkedSetOf<String>()
        val closedSections = linkedSetOf<String>()
        var home: HomeOutcome? = null
        var lastPause: PauseReason? = null
        var sectionOpenedAt = Long.MIN_VALUE / 2
        var watchdogDeadlineMono = Long.MAX_VALUE   // when the TV-side watchdog of the open section fires, at the latest
        @Volatile var undoing = false               // an undo is under way: continue carries on undoing (R1 R-6)
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        // The TV said no (non-zero exit, install refusal). `kept`: the live check kept a package that
        // runs the TV's inputs (upstream #421); the step is skipped, never a pause (RF-LAUNCHER-250 F1).
        data class Refused(val exit: Int, val stderr: String, val kept: Boolean = false) : Outcome
        data class Failed(val error: AdbError, val detail: String) : Outcome   // verify mismatch, policy, device error
        data class Lost(val error: AdbError) : Outcome                          // transport or auth: the TV went away
    }

    private val runs = ConcurrentHashMap<TvId, Run>()
    private val states = ConcurrentHashMap<TvId, MutableStateFlow<RunState>>()
    private val startLock = Mutex()

    private fun flow(tvId: TvId) = states.computeIfAbsent(tvId) { MutableStateFlow(RunState.Idle) }
    override fun runState(tvId: TvId): StateFlow<RunState> = flow(tvId).asStateFlow()

    // ---- start, gate, hash -------------------------------------------------------------------------

    override suspend fun start(plan: Plan, session: TvSession, confirmed: Sha256, pass: GatePass?): StartResult = startLock.withLock {
        dropIfForgotten(plan.tvId)
        val current = flow(plan.tvId).value
        if (current !is RunState.Idle && current !is RunState.Finished) return StartResult.Refused(StartRefusal.RUN_OPEN)
        if (journal.openRuns(plan.tvId).isNotEmpty()) return StartResult.Refused(StartRefusal.RUN_OPEN)
        // A plan runs once (R1): a second run would reuse its run token (and so its watchdog name and
        // token file) and append to a closed journal. "Try again" is a new plan from the planner.
        if (journal.read(plan.id).isNotEmpty()) return StartResult.Refused(StartRefusal.RUN_OPEN)
        if (session.state.value !is ConnectionState.Connected) return StartResult.Refused(StartRefusal.NOT_CONNECTED)
        if (plan.tvId != session.tvId) return StartResult.Refused(StartRefusal.WRONG_TV)
        if (confirmed != plan.hash || !rendersTheSame(plan)) return StartResult.Refused(StartRefusal.HASH_MISMATCH)
        gate(plan, plan.steps, pass)?.let { return StartResult.Refused(it) }

        plans.save(plan)
        val run = Run(plan, session)
        runs[plan.tvId] = run
        try {
            journal.append(JournalRecord.PlanStart(plan.id, plan.tvId, plan.baseSnapshot, plan.steps.size, plan.hash, plan.appVersion, clock.nowMs()))
        } catch (e: Throwable) {
            runs.remove(plan.tvId, run)
            throw e
        }
        run.hold = session.hold()
        publishRunning(run, 0)
        run.job = scope.launch { execute(run, prePlan = true) }
        StartResult.Started
    }

    // R0: 5. Every op re-rendered through the renderer; the lines must equal the plan's and the hash
    // over them must equal the plan's hash. A RenderRefused (a protected package, a bad argument)
    // is a mismatch too.
    private fun rendersTheSame(plan: Plan): Boolean {
        val again = try {
            plan.steps.mapIndexed { i, s ->
                if (s.index != i) return false
                renderer.render(s.op)
            }
        } catch (e: RenderRefused) {
            return false
        }
        // Everything the executor acts on is compared, not only the lines: the privilege decides the
        // gate (D17), the verify read decides "done", the timeout and cap bound the exec (R1).
        val same = again.zip(plan.steps).all { (a, s) ->
            a.lines.map { it.text } == s.rendered.lines.map { it.text } && a.transfer == s.rendered.transfer &&
                a.privilege == s.rendered.privilege && a.verify == s.rendered.verify &&
                a.timeoutMs == s.rendered.timeoutMs && a.stdoutCap == s.rendered.stdoutCap
        }
        return same && renderer.planHash(again) == plan.hash
    }

    // D17 by type (R0: 6): a plan with a write step needs a live pass for this TV.
    // RFC-W8-5: the restart test (a REBOOT plan of one Reboot step) is journaled but not gated; it
    // changes nothing that needs undoing.
    private fun gate(plan: Plan, steps: List<Step>, pass: GatePass?): StartRefusal? {
        if (steps.none { it.rendered.privilege in GATED }) return null
        if (plan.kind == PlanKind.REBOOT && steps.all { it.op == Op.Reboot }) return null
        if (pass == null || pass.tvId != plan.tvId) return StartRefusal.GATE_REQUIRED
        if (pass.expiresAtMs <= clock.nowMs()) return StartRefusal.GATE_EXPIRED
        return null
    }

    // ---- the run -----------------------------------------------------------------------------------

    private suspend fun execute(run: Run, prePlan: Boolean) {
        try {
            if (prePlan) {
                if (!takeRestorePoint(run)) return
            } else {
                if (run.home != null) { finishWithSkips(run, run.home, run.at); return }   // the section was rolled back: a new plan is needed
                if (!recheck(run, save = false, from = run.at + 1)) return   // the step at `at` may have half happened; it is re-run anyway
            }
            loop(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error)
        } catch (e: Throwable) {
            pause(run, PauseReason.STEP_FAILED, AdbError.Transport.Protocol(e.toString().take(200)))
        }
    }

    // The run's first action: the PRE_PLAN snapshot, saved, then the staleness check against the
    // priors of every remaining step.
    private suspend fun takeRestorePoint(run: Run): Boolean {
        run.restorePoint = NarrationState.DOING
        publishRunning(run, run.at)
        val ok = recheck(run, save = true, from = run.at)
        if (ok) { run.restorePoint = NarrationState.DONE; publishRunning(run, run.at) }
        return ok
    }

    private suspend fun recheck(run: Run, save: Boolean, from: Int): Boolean {
        val snap = try {
            prober.snapshot(run.session, SnapshotReason.PRE_PLAN)
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error); return false
        }
        if (save) snapshots.save(snap)
        run.snapshot = snap
        resolve(run, snap)
        val diff = Staleness.diff(run.plan.steps.drop(from), snap)
        if (diff.isNotEmpty()) { pause(run, PauseReason.STALE, AdbError.Policy.Stale(diff)); return false }
        return true
    }

    // The profile and the stock launchers of this TV's resolution, so the live check asks the same
    // rule the planner asked (SwitchOffRule) with the same lists, and with the snapshot's Home as
    // Setup and the planner weigh it (issue #114).
    private suspend fun resolve(run: Run, snap: Snapshot) {
        val r = profiles?.resolveFor(snap)
        run.profile = (r as? ProfileResolution.Exact)?.profile
        run.stockLaunchers = when (r) {
            is ProfileResolution.Exact -> r.profile.stockLaunchers
            is ProfileResolution.BrandSureSet -> r.stockLaunchers
            is ProfileResolution.None -> r.stockLaunchers
            null -> emptyList()
        }
    }

    private suspend fun loop(run: Run) {
        val plan = run.plan
        while (run.at < plan.steps.size) {
            if (run.stopRequested) { pause(run, PauseReason.USER_STOP, null); return }
            val i = run.at
            val step = plan.steps[i]
            plan.sections.firstOrNull { it.from == i && it.name !in run.closedSections && it.name !in run.openSections }?.let { section ->
                if (!openSection(run, section)) { finishWithSkips(run, HomeOutcome.VERIFY_FAILED, i); return }
            }
            val section = plan.sections.firstOrNull { it.name in run.openSections && i in it.from..it.to }
            // A new group forces a fresh thumbnail. Silent steps (RFC-CURTAIN-1: the curtain's start
            // and cues, woven between the switch-offs) never force one and do not end a group: the
            // group of a voiced step is compared with the last voiced step's (RF-CURTAIN-1 F1).
            val voiced = step.narration.isNotBlank()
            val lastVoiced = plan.steps.subList(0, i).lastOrNull { it.narration.isNotBlank() }
            if (i == 0 || (voiced && lastVoiced?.group != step.group)) thumbnail(run, force = true) else thumbnail(run, force = false)
            run.states[i] = NarrationState.DOING
            publishRunning(run, i)

            when (val outcome = runStep(run, step)) {
                Outcome.Ok -> { run.done += i; run.states[i] = NarrationState.DONE; run.at = i + 1 }
                is Outcome.Lost -> { run.states[i] = NarrationState.UPCOMING; pause(run, PauseReason.CONNECTION_LOST, outcome.error); return }
                is Outcome.Refused, is Outcome.Failed -> {
                    val error: AdbError = when (outcome) {
                        is Outcome.Failed -> outcome.error
                        is Outcome.Refused -> AdbError.NonZeroExit(outcome.exit, outcome.stderr.take(4096))
                        else -> AdbError.Transport.Closed
                    }
                    when {
                        // RF-LAUNCHER-250 F1, F2: a package kept because it runs the TV's inputs is a
                        // skip whatever the step's policy, inside the takeover too: the section goes on
                        // with the TV's own home screen left enabled (SetHome still makes ours the default).
                        outcome is Outcome.Refused && outcome.kept -> {
                            run.skipped += step; run.states[i] = NarrationState.SKIPPED; run.at = i + 1
                        }
                        section != null -> {
                            run.states[i] = NarrationState.FAILED
                            if (!rollbackSection(run, section, HomeOutcome.VERIFY_FAILED)) return
                            finishWithSkips(run, HomeOutcome.VERIFY_FAILED, i + 1); return
                        }
                        // Issue #10: a switch-off outside the home screen switch that the live check
                        // refused, or that the TV answered but kept on (its check read does not list it
                        // and its package dump still shows it on, see disableChecked), changed nothing: it is a skip, whatever its policy, so the run
                        // goes on and Carry on never meets the same refusal again. Done names it
                        // ("wouldn't switch off; it's harmless").
                        step.op is Op.Disable && outcome is Outcome.Failed && keptOn(outcome.error) -> {
                            run.skipped += step; run.states[i] = NarrationState.SKIPPED; run.at = i + 1
                        }
                        step.onFailure == FailurePolicy.CONTINUE || (step.onFailure == FailurePolicy.SKIP_IF_REFUSED && outcome is Outcome.Refused) -> {
                            run.skipped += step; run.states[i] = NarrationState.SKIPPED; run.at = i + 1
                        }
                        else -> { run.states[i] = NarrationState.FAILED; pause(run, PauseReason.STEP_FAILED, error); return }
                    }
                }
            }

            if (section != null && i == section.to && run.at == i + 1) {
                when (val outcome = awaitConfirm(run, section)) {
                    HomeOutcome.CONFIRMED -> {
                        journal.append(JournalRecord.Section(plan.id, section.name, section.token, SectionState.CONFIRMED, clock.nowMs()))
                        run.openSections -= section.name; run.closedSections += section.name
                    }
                    HomeOutcome.CONNECTION_LOST -> { pause(run, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed); return }
                    else -> {
                        if (!rollbackSection(run, section, outcome)) return
                        finishWithSkips(run, outcome, i + 1); return
                    }
                }
            }
        }
        end(run, if (run.skipped.isEmpty()) RunStatus.COMPLETED else RunStatus.COMPLETED_WITH_SKIPS, run.home)
    }

    // Section 3.4 preconditions at run time: the new home screen started, the launcher installed
    // and, when this plan installed it, hashing to what was pushed; a screenshot probe succeeding;
    // the token in the journal.
    private suspend fun openSection(run: Run, section: GuardedSection): Boolean {
        // Launcher v2.4.0 (RF-LAUNCHER-240 F3): the start of the new home screen just before the
        // section must have succeeded ("Status: ok" from am start -W); a plan without that step
        // (written before it) is not held to it.
        val start = run.plan.steps.subList(0, section.from).lastOrNull { it.op == Op.StartActivity(section.expectedHome, wait = true) }
        if (start != null && start.index !in run.done) return false
        val launcherPkg = section.expectedHome.pkg
        val installed = prober.installedSha256(run.session, launcherPkg) ?: return false
        val expected = run.plan.steps.firstNotNullOfOrNull { s ->
            (s.op as? Op.Install)?.takeIf { it.pkg == launcherPkg }?.sha256 ?: (s.op as? Op.InstallStream)?.takeIf { it.pkg == launcherPkg }?.sha256
        }
        if (expected != null && installed != expected) return false
        if (!thumbnail(run, force = true)) return false
        val arm = run.plan.steps.getOrNull(section.from)?.op as? Op.ArmHomeWatchdog ?: return false
        if (arm.token != section.token) return false
        journal.append(JournalRecord.Section(run.plan.id, section.name, section.token, SectionState.ARMED, clock.nowMs() + arm.seconds * 1000L))
        run.openSections += section.name
        run.sectionOpenedAt = clock.monotonicMs()
        // Taken before the arm step is sent, so it is no later than the watchdog's real deadline.
        run.watchdogDeadlineMono = run.sectionOpenedAt + arm.seconds * 1000L
        return true
    }

    // The check screen (3.4 step 5): the phone timer (90 s, or 120 s without a recent thumbnail,
    // D23), the 2 s focus check, a thumbnail every 5 s, and the user's answer.
    //
    // The two layers stay ordered (R1 R-1, DECISIONS-W3 D-W3-1): the planner arms the watchdog for
    // 150 s so the phone's window ends first, and the phone's deadline is never later than 10 s
    // before the watchdog's, whatever the section's steps cost. A "Yes" therefore always lands while
    // the watchdog is still asleep, and its token file defuses it.
    private suspend fun awaitConfirm(run: Run, section: GuardedSection): HomeOutcome {
        // D23: 90 s with a thumbnail fetched now or within the last 15 s since the section opened, else 120 s.
        val fresh = thumbnail(run, force = true)
        val now = clock.monotonicMs()
        val recent = run.lastThumbnailOkAt > run.sectionOpenedAt && now - run.lastThumbnailOkAt <= recentThumbnailMs
        val wanted = if (fresh || recent) section.confirmWindowMs else noThumbnailWindowMs
        val window = minOf(wanted, run.watchdogDeadlineMono - WATCHDOG_MARGIN_MS - now).coerceAtLeast(0)
        val deadlineMono = now + window
        val deadlineWall = clock.nowMs() + window
        var homeCheck: Boolean? = null
        while (true) {
            flow(run.plan.tvId).value = RunState.AwaitingConfirm(run.plan, section, deadlineWall, homeCheck, run.thumbnail)
            val decision = withTimeoutOrNull(liveCheckEveryMs) { run.decisions.receive() }
            if (decision == true) return HomeOutcome.CONFIRMED
            if (decision == false) return HomeOutcome.USER_SAID_NO
            if (clock.monotonicMs() >= deadlineMono) return HomeOutcome.TIMED_OUT
            val focus = try {
                prober.homeFocus(run.session)
            } catch (e: AdbException) {
                if (e.error.isLost()) return HomeOutcome.CONNECTION_LOST else null
            }
            if (focus != null) {
                if (focus.pkg in section.rollbackIfFocus) {
                    // Issues #153, #339, #375, #385: the log said WRONG_FOCUS and nothing about what
                    // was in front, so no report could tell the setup wizard from Settings or the chooser.
                    // The TV's own: a system app, or one of the home screens, wizards and settings apps
                    // the check knows by name (an update can move Google's home screen under /data).
                    val ownApp = run.snapshot?.packages?.get(focus.pkg)?.system == true || focus.pkg in tv.cleanyour.core.spec.KnownLaunchers.byPackage ||
                        focus.pkg.value in Denylist.setupWizards || focus.pkg.value in Denylist.vendorSetupWizards || focus.pkg.value == Denylist.FRAMEWORK || focus.pkg.value in SETTINGS_APPS
                    log("engine: the home check saw ${if (ownApp) focus.flat else "an app you installed"} in front, so the old home screen goes back")
                    onWrongFocus(run.plan.tvId, focus)
                    return HomeOutcome.WRONG_FOCUS
                }
                homeCheck = focus.pkg == section.expectedHome.pkg
            }
            thumbnail(run, force = false)
        }
    }

    // Step R: journaled like any op, idempotent; a lost connection leaves the section open so resume
    // runs it again. Only step R's own vocabulary runs (the plan file is not hashed for its
    // sections), and a failure to put the TV's own home screen back is never recorded as ROLLED_BACK:
    // the run pauses with the error, the section stays open, and continue, undo or resume try again
    // (SECURITY 3.5: undo failures are shown, never hidden) (R1).
    //
    // Undo is state-based, never command-based (issue #2): a refused or failed op of step R whose
    // target already holds on the TV (the package enabled, HOME on the stock launcher) is journaled
    // done and verified. Once VerifyHome passes, the TV's own home screen is back, so a refusal before
    // it (Fire OS refuses pm enable of its protected launcher) is a skip, its Failed record kept; the
    // run pauses only when the TV's own home screen is really not back.
    private suspend fun rollbackSection(run: Run, section: GuardedSection, outcome: HomeOutcome): Boolean {
        flow(run.plan.tvId).value = RunState.RollingBack(run.plan, section.from)
        journal.append(JournalRecord.Rollback(run.plan.id, section.from))
        var failed: AdbError? = null   // the first failure since the last VerifyHome that passed
        for (op in section.rollback) {
            if (!StepR.allows(op, section)) continue
            val undone = run.plan.steps.firstOrNull { it.index in section.from..section.to && undoes(op, it.op) }?.index ?: -1
            val rendered = try { renderer.render(op) } catch (e: RenderRefused) { continue }
            when (val r = performStepR(run, undone, op, rendered)) {
                is Outcome.Lost -> { pause(run, PauseReason.CONNECTION_LOST, r.error); return false }
                is Outcome.Refused -> if (StepR.mustSucceed(op) && failed == null) failed = AdbError.NonZeroExit(r.exit, r.stderr.take(4096))
                is Outcome.Failed -> if (StepR.mustSucceed(op) && failed == null) failed = r.error
                Outcome.Ok -> if (op is Op.VerifyHome) failed = null
            }
        }
        if (failed != null) { pause(run, PauseReason.STEP_FAILED, failed); return false }
        journal.append(JournalRecord.Section(run.plan.id, section.name, section.token, SectionState.ROLLED_BACK, clock.nowMs()))
        run.openSections -= section.name
        run.closedSections += section.name
        run.home = outcome
        return true
    }

    // One op of step R, like perform, with the state check: when an op is refused or fails, its
    // target is read (targetHolds: a read that proves the state, never an empty or failed one); if it
    // holds (the package enabled, HOME on the stock launcher, no watchdog left), Done
    // (verified) is journaled instead of Failed, so the change records see the item as put back.
    private suspend fun performStepR(run: Run, index: Int, op: Op, rendered: Rendered): Outcome {
        journal.append(JournalRecord.Begin(run.plan.id, index, op, rendered.lines.map { it.text }, null, clock.nowMs()))
        val started = clock.monotonicMs()
        val outcome = try {
            dispatch(run, op, rendered, null)
        } catch (e: AdbException) {
            if (e.error.isLost()) Outcome.Lost(e.error) else Outcome.Failed(e.error, e.error.toString())
        }
        val held = when {
            outcome == Outcome.Ok || outcome is Outcome.Lost -> false
            else -> try {
                targetHolds(run.session, op, rendered)
            } catch (e: AdbException) {
                if (e.error.isLost()) return Outcome.Lost(e.error)   // in doubt: no Done, no Failed; resume runs step R again
                false
            }
        }
        val ms = clock.monotonicMs() - started
        when {
            outcome == Outcome.Ok -> journal.append(JournalRecord.Done(run.plan.id, index, 0, verified = true, ms = ms))
            held -> journal.append(JournalRecord.Done(run.plan.id, index, (outcome as? Outcome.Refused)?.exit ?: 0, verified = true, ms = ms))
            outcome is Outcome.Refused -> journal.append(JournalRecord.Failed(run.plan.id, index, outcome.exit, "exit ${outcome.exit}", outcome.stderr.take(4096)))
            outcome is Outcome.Failed -> journal.append(JournalRecord.Failed(run.plan.id, index, null, outcome.detail.take(4096), ""))
            else -> {}
        }
        return if (held) Outcome.Ok else outcome
    }

    // Whether an op's target state already holds on the TV, from a read that proves it: the read must
    // answer exit 0 with output that shows the state, so a read that errors or prints nothing never
    // holds (a refused pm enable of a package that stays off must stay a Failed record).
    // Enable: the package's own dumpsys, which must show the package and its enabled state as
    // ENABLED or DEFAULT. SetHome and VerifyHome: the HOME resolver, which must name the component.
    // KillWatchdog: pgrep, whose documented answer for "no such process" is exit 1 with nothing on
    // stdout or stderr; any other exit or any stderr is an error. Anything else: its verify read,
    // exit 0, output, and the renderer's check.
    private suspend fun targetHolds(session: TvSession, op: Op, rendered: Rendered): Boolean {
        when (op) {
            is Op.Enable -> {
                val r = readResult(session, renderer.render(Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(op.pkg)))) ?: return false
                if (r.exit != 0 || !PackageDump.exists(op.pkg, r.stdout)) return false
                val state = PackageDump.enabledState(r.stdout)
                return state == EnabledState.ENABLED || state == EnabledState.DEFAULT
            }
            is Op.KillWatchdog -> {
                val probe = rendered.verify?.let { renderer.render(it) } ?: return false
                val r = readResult(session, probe) ?: return false
                val none = r.exit == 1 && r.stdout.isBlank() && r.stderr.isBlank()
                return none || (r.exit == 0 && renderer.check(op, r.stdout, null) == VerifyResult.Ok)
            }
            else -> {
                val probe = rendered.verify?.let { renderer.render(it) } ?: rendered.takeIf { it.privilege == Privilege.READ && it.transfer == null } ?: return false
                val r = readResult(session, probe) ?: return false
                return r.exit == 0 && r.stdout.isNotBlank() && renderer.check(op, r.stdout, null) == VerifyResult.Ok
            }
        }
    }

    // A read's last answer with its exit code and stderr (read() keeps only stdout); null when the
    // read has no line. Every earlier line must answer exit 0 as well.
    private suspend fun readResult(session: TvSession, rendered: Rendered): ExecResult? {
        var last: ExecResult? = null
        for (line in rendered.lines) {
            if (last != null && last.exit != 0) return last
            last = send(session, line, ExecSpec(timeoutMs = rendered.timeoutMs, stdoutCap = rendered.stdoutCap, retry = RetryClass.IDEMPOTENT_READ))
        }
        return last
    }

    private fun undoes(rollbackOp: Op, stepOp: Op): Boolean = when (rollbackOp) {
        is Op.Enable -> stepOp is Op.Disable && stepOp.pkg == rollbackOp.pkg
        is Op.SetHome -> stepOp is Op.SetHome
        else -> false
    }

    // One plan step: the live denylist check, then Begin, the op, its verify read, Done or Failed.
    private suspend fun runStep(run: Run, step: Step): Outcome {
        val target = Staleness.writeTarget(step.op)
        if (target != null) {
            val dumpRead = renderer.render(Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(target)))
            val dump = try {
                read(run.session, dumpRead)
            } catch (e: AdbException) {
                return if (e.error.isLost()) Outcome.Lost(e.error) else Outcome.Failed(e.error, "dumpsys package failed")
            }
            val live = liveState(target, dump, run.snapshot)
            val reason = if (step.op is Op.Disable) switchOffReason(run, step, target, live, dump) else Denylist.reason(target, live, run.profile, run.snapshot)
            if (reason != null) {
                journal.append(JournalRecord.Begin(run.plan.id, step.index, step.op, step.rendered.lines.map { it.text }, step.prior, clock.nowMs()))
                journal.append(JournalRecord.Failed(run.plan.id, step.index, null, "$PROTECTED$reason", ""))
                // A switch-off of something that runs the TV's inputs is kept and skipped (F1); any
                // other protected package stops the step as before.
                if (step.op is Op.Disable && reason == Denylist.REASON_TV_INPUT) return Outcome.Refused(1, "$PROTECTED$reason", kept = true)
                return Outcome.Failed(AdbError.Policy.ProtectedPackage(target), reason)
            }
            if (!PackageDump.exists(target, dump) && step.op !is Op.Uninstall) {
                journal.append(JournalRecord.Begin(run.plan.id, step.index, step.op, step.rendered.lines.map { it.text }, step.prior, clock.nowMs()))
                journal.append(JournalRecord.Failed(run.plan.id, step.index, 1, "package not on the TV", ""))
                return Outcome.Refused(1, "package not on the TV")
            }
        }
        return perform(run, step.index, step.op, step.rendered, step.prior)
    }

    // The switch-off rule at run time, the one the planner applied (SwitchOffRule, issue #10), with the
    // live facts: the package's dump read just above and the run's PRE_PLAN snapshot. The TV's
    // current HOME and every HOME claimant, the stock launchers and the setup wizard are switched off
    // only inside the open guarded section (R1 R-13: a plan made before HOME changed cannot reach
    // them; the staleness check covers SetHome priors, not a HOME change behind a debloat plan).
    // Fire OS's own home screens (issue #2) and a home screen app the person installed (issue #8)
    // never go off, inside a section too, whatever an older plan file says. Upstream #421 (launcher
    // v2.5.0's tools/disable.sh): a package that asks for TV_INPUT_HARDWARE is the layer under the
    // HDMI, AV and tuner inputs, so it stays on, inside the takeover too (RF-LAUNCHER-250 F2).
    private fun switchOffReason(run: Run, step: Step, target: PackageName, live: PackageState?, dump: String): String? {
        val inSection = run.plan.sections.any { it.name in run.openSections && step.index in it.from..it.to }
        return SwitchOffRule.reason(target, live, run.profile, run.snapshot, run.stockLaunchers, inHomeSwitch = inSection,
            requestsTvInputHardware = PackageDump.requestsTvInputHardware(dump))
    }

    // A refusal that leaves the package as it was: the live check's (nothing was sent), or a package
    // dump that still shows it on after the TV answered (some firmware, like the Xiaomi box of issue
    // #10, says yes and keeps it on). A check read that errors is never one (disableChecked).
    private fun keptOn(error: AdbError): Boolean = error is AdbError.Policy.ProtectedPackage || error is AdbError.Policy.GuardFailed

    // Issues #80 and #367: on some TVs (a Sony BRAVIA VH2 on Android 12, a TCL C06 on Android 11) the
    // streamed install ("exec:cmd package install -S") ends in a protocol error every time, within
    // seconds, while the connection itself stays up. Taken for a lost connection, it paused the run
    // as "the TV stopped answering", and Carry on met the same install again, for good. With the
    // session still connected it is the install's own failure: the installed APK is checked (the
    // stream may have finished before the error), Done when it hashes to the streamed one, else
    // Failed, and the step's policy decides (the launcher's and the curtain's installs CONTINUE, so
    // the clean-up goes on and the home screen switch is skipped, never opened without its launcher).
    private suspend fun streamFailed(run: Run, op: Op.InstallStream, error: AdbError): Outcome = try {
        when (installedApkCheck(run.session, op)) {
            Outcome.Ok -> Outcome.Ok
            else -> Outcome.Failed(error, "$STREAM_FAILED$error")
        }
    } catch (e: AdbException) {
        if (e.error.isLost()) Outcome.Lost(e.error) else Outcome.Failed(error, "$STREAM_FAILED$error")
    }

    // Begin (forced) before the line is sent, Done or Failed before the next Begin.
    private suspend fun perform(run: Run, index: Int, op: Op, rendered: Rendered, prior: Prior?): Outcome {
        journal.append(JournalRecord.Begin(run.plan.id, index, op, rendered.lines.map { it.text }, prior, clock.nowMs()))
        val started = clock.monotonicMs()
        var verified = true
        val outcome = try {
            dispatch(run, op, rendered, prior)
        } catch (e: AdbException) {
            // debug_off: the TV closes the socket (5.2), so nothing was read back; the flows confirm
            // with a failed probe of 5555 instead (F11), and the record says it was not verified (R1 R-7).
            if (rendered.privilege == Privilege.SESSION_END && e.error.isLost()) { verified = false; Outcome.Ok }
            else if (op is Op.InstallStream && e.error is AdbError.Transport.Protocol && run.session.state.value is ConnectionState.Connected) streamFailed(run, op, e.error)
            else if (e.error.isLost()) Outcome.Lost(e.error)
            else Outcome.Failed(e.error, e.error.toString())
        }
        val ms = clock.monotonicMs() - started
        when (outcome) {
            Outcome.Ok -> journal.append(JournalRecord.Done(run.plan.id, index, 0, verified = verified, ms = ms))
            is Outcome.Refused -> journal.append(JournalRecord.Failed(run.plan.id, index, outcome.exit, "exit ${outcome.exit}", outcome.stderr.take(4096)))
            is Outcome.Failed -> journal.append(JournalRecord.Failed(run.plan.id, index, null, outcome.detail.take(4096), ""))
            is Outcome.Lost -> {}   // in doubt: no Done, no Failed; resume re-verifies it
        }
        return outcome
    }

    // The dispatch table of 5.2 (R0: 7).
    private suspend fun dispatch(run: Run, op: Op, rendered: Rendered, prior: Prior?): Outcome {
        val session = run.session
        var ownOutput = ""
        when (op) {
            is Op.Settle -> { delay(op.seconds * 1000L); return Outcome.Ok }
            Op.Reboot -> {
                session.reboot()
                return when (session.awaitBoot(bootTimeoutMs)) {
                    is BootOutcome.Back -> Outcome.Ok
                    BootOutcome.Timeout -> Outcome.Lost(AdbError.Transport.Timeout("boot"))
                }
            }
            else -> {}
        }
        for (line in rendered.lines) {
            val r = send(session, line, ExecSpec(timeoutMs = rendered.timeoutMs, stdoutCap = rendered.stdoutCap, retry = RetryClass.NEVER))
            if (r.exit != 0) return Outcome.Refused(r.exit, r.stderr)
            ownOutput = r.stdout
        }
        when (val t = rendered.transfer) {
            is Transfer.Push -> session.push(t.src, t.dest)
            is Transfer.Pull -> { run.thumbnail = session.pull(t.src, t.maxBytes) }
            is Transfer.StreamInstall -> when (val o = session.installStream(t.src, t.size)) {
                InstallOutcome.Installed -> {}
                is InstallOutcome.Failed -> return Outcome.Refused(1, "${o.code}: ${o.detail}")
            }
            null -> {}
        }
        val check = rendered.verify
        if (op is Op.Disable && check != null) return disableChecked(session, op, renderer.render(check), prior)
        val verifyOutput = rendered.verify?.let { read(session, renderer.render(it)) } ?: ownOutput
        return when (val v = renderer.check(op, verifyOutput, prior)) {
            VerifyResult.Ok -> installedApkCheck(session, op)
            is VerifyResult.Mismatch -> Outcome.Failed(AdbError.Policy.GuardFailed(-1, "expected ${v.expected}, got ${v.actual}"), "verify: expected ${v.expected}, got ${v.actual}")
        }
    }

    // A switch-off's check read (pm list packages -d). A check read that errors is a failure, never
    // a kept-on package: the step pauses as before. When the check read answers but does not list the
    // package (an empty answer, or one that raced the write), the package's own dump is read once,
    // the read the live check uses: switched off (DISABLED_USER or DISABLED) is done and recorded as
    // a change, and only a dump that positively shows it enabled or default is the TV keeping it on
    // (issue #10's GuardFailed, a skip outside the home screen switch). A dump that cannot be read,
    // or shows no enabled state, is a failure too.
    private suspend fun disableChecked(session: TvSession, op: Op.Disable, check: Rendered, prior: Prior?): Outcome {
        val r = readResult(session, check) ?: return Outcome.Failed(AdbError.Transport.Protocol("no check read"), "verify: no check read")
        if (r.exit != 0) return Outcome.Failed(AdbError.NonZeroExit(r.exit, r.stderr.take(4096)), "verify read: exit ${r.exit}")
        val v = renderer.check(op, r.stdout, prior) as? VerifyResult.Mismatch ?: return Outcome.Ok
        val d = readResult(session, renderer.render(Op.Read(ProbeKind.PACKAGE_DUMP, ReadArg.Pkg(op.pkg))))
            ?: return Outcome.Failed(AdbError.Transport.Protocol("no package dump read"), "verify: no package dump read")
        if (d.exit != 0) return Outcome.Failed(AdbError.NonZeroExit(d.exit, d.stderr.take(4096)), "verify: dumpsys package exit ${d.exit}")
        val state = if (PackageDump.exists(op.pkg, d.stdout)) PackageDump.enabledState(d.stdout) else null
        return when (state) {
            EnabledState.DISABLED_USER, EnabledState.DISABLED -> Outcome.Ok
            EnabledState.ENABLED, EnabledState.DEFAULT ->
                Outcome.Failed(AdbError.Policy.GuardFailed(-1, "expected ${v.expected}, got ${v.actual}"), "verify: expected ${v.expected}, got ${v.actual}")
            null -> Outcome.Failed(AdbError.Transport.Protocol("dumpsys package shows no enabled state"), "verify: dumpsys package shows no enabled state for ${op.pkg.value}")
        }
    }

    // SECURITY 3.2 (R1 R-5): an install is done only when the APK the TV installed hashes to what
    // was pushed or streamed, read through the prober's D40 script; versionCode alone is not enough.
    private suspend fun installedApkCheck(session: TvSession, op: Op): Outcome {
        val (pkg, expected) = when (op) {
            is Op.Install -> op.pkg to op.sha256
            is Op.InstallStream -> op.pkg to op.sha256
            else -> return Outcome.Ok
        }
        val installed = prober.installedSha256(session, pkg)
        if (installed == expected) return Outcome.Ok
        val got = installed?.hex ?: "no readable apk"
        return Outcome.Failed(AdbError.Policy.GuardFailed(-1, "installed apk sha256 ${expected.hex}, got $got"), "verify: installed apk sha256 ${expected.hex}, got $got")
    }

    // Verify and probe reads: idempotent, so the session may retry them.
    private suspend fun read(session: TvSession, rendered: Rendered): String {
        var out = ""
        for (line in rendered.lines) {
            out = send(session, line, ExecSpec(timeoutMs = rendered.timeoutMs, stdoutCap = rendered.stdoutCap, retry = RetryClass.IDEMPOTENT_READ)).stdout
        }
        return out
    }

    // The single call site of TvSession.exec in the engine (the lint in :core:engine tests).
    private suspend fun send(session: TvSession, line: ShellLine, spec: ExecSpec): ExecResult = session.exec(line, spec)

    private fun liveState(pkg: PackageName, dump: String, snapshot: Snapshot?): PackageState? {
        val known = snapshot?.packages?.get(pkg)
        if (!PackageDump.exists(pkg, dump)) return known
        return PackageState(
            enabled = PackageDump.enabledState(dump) ?: known?.enabled ?: EnabledState.ENABLED,
            system = known?.system ?: PackageDump.isSystem(dump),
            versionCode = PackageDump.versionCode(dump) ?: known?.versionCode ?: 0,
            signer = known?.signer,
            persistent = PackageDump.isPersistent(dump) || known?.persistent == true,
            tvInput = known?.tvInput ?: false,
            label = known?.label,
            installedByUs = known?.installedByUs ?: false,
        )
    }

    // A thumbnail at most every 5 s (every group change forces one); returns whether one was taken.
    private suspend fun thumbnail(run: Run, force: Boolean): Boolean {
        val now = clock.monotonicMs()
        if (!force && now - run.lastThumbnailTryAt < thumbnailEveryMs) return false
        run.lastThumbnailTryAt = now
        val png = try { prober.screenshot(run.session) } catch (e: AdbException) { null }
        if (png != null) { run.thumbnail = png; run.lastThumbnailOkAt = now }
        return png != null
    }

    // ---- ending a run ------------------------------------------------------------------------------

    private suspend fun finishWithSkips(run: Run, home: HomeOutcome?, from: Int) {
        removeCurtain(run, from)
        for (i in from until run.plan.steps.size) if (i !in run.done && run.plan.steps[i] !in run.skipped) {
            run.skipped += run.plan.steps[i]; run.states[i] = NarrationState.SKIPPED
        }
        end(run, RunStatus.COMPLETED_WITH_SKIPS, home)
    }

    // QA of 0.4.5 (the home check timed out and the old home screen was put back): the run ended
    // there and skipped every step after it, the TV curtain's removal (the plan's last step) too, so
    // the curtain stayed installed and running on the TV. Its removal still runs when the rest is
    // skipped. Like every curtain step it can never stop the run: a refusal or a lost connection
    // only leaves it skipped.
    private suspend fun removeCurtain(run: Run, from: Int) {
        for (i in from until run.plan.steps.size) {
            val step = run.plan.steps[i]
            if (i in run.done || step.group != DefaultPlanner.GROUP_CURTAIN || step.op !is Op.Uninstall) continue
            val outcome = try {
                perform(run, i, step.op, step.rendered, step.prior)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (outcome == Outcome.Ok) { run.done += i; run.states[i] = NarrationState.DONE }
        }
    }

    // A curtain step of this run was done (its install or its start) and its removal was not.
    private fun curtainLeft(run: Run): Boolean {
        val steps = run.plan.steps.filter { it.group == DefaultPlanner.GROUP_CURTAIN }
        val removal = steps.lastOrNull { it.op is Op.Uninstall } ?: return false
        return removal.index !in run.done && steps.any { it.index in run.done && (it.op is Op.Install || it.op is Op.InstallStream || it.op is Op.StartActivity) }
    }

    private suspend fun end(run: Run, status: RunStatus, home: HomeOutcome?) {
        journal.append(JournalRecord.End(run.plan.id, status))
        run.hold?.close(); run.hold = null
        runs.remove(run.plan.tvId, run)
        flow(run.plan.tvId).value = RunState.Finished(run.plan, status, run.skipped.toList(), home)
    }

    private suspend fun pause(run: Run, reason: PauseReason, error: AdbError?) {
        run.lastPause = reason
        runCatching { journal.append(JournalRecord.Paused(run.plan.id, run.at, reason)) }
        run.hold?.close(); run.hold = null
        flow(run.plan.tvId).value = RunState.Paused(run.plan, run.at, reason, error)
    }

    // RFC-CURTAIN-1: a step with a blank narration (the TV curtain's start and cues) is silent: not
    // listed on the Working screen and not counted in the percent. The journal, resume and Show
    // commands still see every step.
    private fun publishRunning(run: Run, step: Int) {
        val voiced = run.plan.steps.filter { it.narration.isNotBlank() }
        val lines = listOf(NarrationLine("Restore point saved", run.restorePoint)) +
            voiced.map { s -> NarrationLine(s.narration, run.states[s.index]) }
        val total = voiced.size
        val percent = if (total == 0) 100 else (voiced.count { it.index in run.done } * 100) / total
        flow(run.plan.tvId).value = RunState.Running(run.plan, step, lines, percent, run.thumbnail)
    }

    // ---- controls ----------------------------------------------------------------------------------

    override suspend fun confirmHome(tvId: TvId) { runs[tvId]?.decisions?.trySend(true) }
    override suspend fun rejectHome(tvId: TvId) { runs[tvId]?.decisions?.trySend(false) }
    override suspend fun stop(tvId: TvId) { runs[tvId]?.stopRequested = true }

    override suspend fun continueRun(tvId: TvId, pass: GatePass?) {
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        // An undo that was cut off carries on undoing; it never turns into a forward run (R1 R-6).
        if (run.undoing) { undoRun(tvId, pass); return }
        if (run.fromJournalOnly) { abandon(tvId); return }   // nothing to continue: only step R, then the run is given up
        if (gate(run.plan, run.plan.steps.drop(run.at), pass) != null) return
        if (run.session.state.value !is ConnectionState.Connected) return
        run.stopRequested = false
        run.hold = run.session.hold()
        publishRunning(run, run.at)
        run.job = scope.launch {
            // A section left open by a lost connection: step R first, then this run is over.
            val open = run.plan.sections.firstOrNull { it.name in run.openSections }
            if (open != null) {
                guarded(run) { if (rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) finishWithSkips(run, HomeOutcome.CONNECTION_LOST, run.at) }
                return@launch
            }
            execute(run, prePlan = !begun(run))
        }
    }

    // Issue #616: the TCL C735 lost the Wi-Fi while its run was saving the restore point ("run paused
    // CONNECTION_LOST at step 0 (timeout)"), and later the app was closed before step 0 ("run paused
    // IN_DOUBT at step 0" after the restart). Carry on then went on as after a step: no restore point
    // was saved for the run, and the staleness check skipped step 0. A run with no step begun (no
    // Begin in its journal) carries on from its very start instead: the restore point is taken and
    // saved, and every step is checked against the TV, as a fresh start does.
    private suspend fun begun(run: Run): Boolean = run.done.isNotEmpty() || journal.read(run.plan.id).any { it is JournalRecord.Begin }

    // A background job's failure lands the run in Paused, never in a crashed coroutine.
    private suspend fun guarded(run: Run, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbException) {
            pause(run, if (e.error.isLost()) PauseReason.CONNECTION_LOST else PauseReason.STEP_FAILED, e.error)
        } catch (e: Throwable) {
            pause(run, PauseReason.STEP_FAILED, AdbError.Transport.Protocol(e.toString().take(200)))
        }
    }

    // The inverse of every done step, most recent first, journaled as a rollback (ARCHITECTURE 2.4).
    // Each step undone leaves `done`, so an undo cut off by a lost connection or a crash carries on
    // with what is left (R1 R-6).
    override suspend fun undoRun(tvId: TvId, pass: GatePass?) {
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        if (run.fromJournalOnly) { abandon(tvId); return }   // no plan to undo from: step R, then the run is given up
        if (gate(run.plan, run.plan.steps, pass) != null) return
        if (run.session.state.value !is ConnectionState.Connected) return
        run.hold = run.session.hold()
        run.job = scope.launch {
            guarded(run) {
                val open = run.plan.sections.firstOrNull { it.name in run.openSections }
                if (open != null && !rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) return@guarded
                run.undoing = true
                flow(tvId).value = RunState.RollingBack(run.plan, run.at)
                journal.append(JournalRecord.Rollback(run.plan.id, run.at))
                for (i in run.done.toList().asReversed()) {   // a copy: undone steps leave `done` as the loop runs
                    val step = run.plan.steps[i]
                    val inverse = renderer.inverse(step.op, step.prior ?: Prior()) ?: continue
                    val rendered = try { renderer.render(inverse) } catch (e: RenderRefused) { continue }
                    flow(tvId).value = RunState.RollingBack(run.plan, i)
                    when (val r = perform(run, i, inverse, rendered, null)) {
                        is Outcome.Lost -> { pause(run, PauseReason.CONNECTION_LOST, r.error); return@guarded }
                        Outcome.Ok -> { run.done -= i; run.states[i] = NarrationState.UPCOMING }
                        else -> {}
                    }
                }
                end(run, RunStatus.ROLLED_BACK, run.home)
            }
        }
    }

    // Waits for its own work to settle before returning (the work itself runs in the engine's scope,
    // so a cancelled caller never cancels it): the caller then reads Finished, or Paused when step R
    // could not put the TV's own home screen back (A5, issue #2). A run whose journal is gone ("Forget
    // this TV" deleted it) is dropped from memory at once, without the TV.
    override suspend fun abandon(tvId: TvId) {
        // Under the start lock, so a start between putting its run in memory and journaling its
        // PlanStart is never mistaken for a forgotten run.
        if (startLock.withLock { dropIfForgotten(tvId) }) return
        val run = runs[tvId] ?: return
        if (flow(tvId).value !is RunState.Paused) return
        val open = run.plan.sections.firstOrNull { it.name in run.openSections }
        val restore = unfinishedAccessibilityRestore(run)
        // QA of 0.4.5: "Stop here" left the TV curtain this run installed running on the TV. Its
        // removal (the plan's own curtain step, nothing else) runs before the run is given up, when
        // the TV is there; without the TV the run ends as before, and Leftovers removes it later.
        val curtain = curtainLeft(run) && run.session.state.value is ConnectionState.Connected
        if (open == null && restore.isEmpty() && !curtain) { end(run, RunStatus.ABANDONED, run.home); return }
        // An open section means the TV's own home screen may be off: step R runs before the run is
        // given up. A restore of the accessibility services cut off half way (upstream #424) leaves
        // the TV with none, and accessibility off: its remaining steps run first too (RF-LAUNCHER-250
        // F3). Without the TV there is nothing to run, so the run stays paused until it is back and
        // resume or abandon runs them then (R1).
        if (run.session.state.value !is ConnectionState.Connected) return
        run.hold = run.session.hold()
        val job = scope.launch {
            guarded(run) {
                if (open != null && !rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) return@guarded
                if (!finishAccessibilityRestore(run, restore)) return@guarded
                if (curtainLeft(run)) removeCurtain(run, 0)
                end(run, RunStatus.ABANDONED, run.home)
            }
        }
        run.job = job
        job.join()
    }

    // "Forget this TV" deletes the TV's journals (SettingsMachines.forget): a run of this engine whose
    // journal is gone is dropped from memory, its work cancelled and its hold closed, and the TV's
    // state goes back to Idle, so a new Setup is never refused RUN_OPEN and no force stop is needed.
    // Only a journal with no records at all counts as forgotten: one that merely is no longer open
    // (end() has just written its End) is left to end() itself. Callers hold the start lock, so a
    // start that has not journaled its PlanStart yet is never dropped. Internal to the engine; the
    // frozen Engine interface is unchanged. True when one was dropped.
    private suspend fun dropIfForgotten(tvId: TvId): Boolean {
        val run = runs[tvId] ?: return false
        val records = try { journal.read(run.plan.id) } catch (e: CancellationException) { throw e } catch (e: Exception) { return false }
        if (records.isNotEmpty()) return false
        if (!runs.remove(tvId, run)) return false
        run.job?.cancel()
        run.hold?.close(); run.hold = null
        flow(tvId).value = RunState.Idle
        return true
    }

    // The steps left of an accessibility restore the run has begun but not finished: from the clear
    // of the service list to the write of accessibility_enabled 1 after it (DefaultPlanner.
    // restoreAccessibility). Begun: the run has done a step of it or stopped inside it. Empty when
    // the plan has none or it finished.
    private fun unfinishedAccessibilityRestore(run: Run): List<Int> {
        val steps = run.plan.steps
        for (clear in steps.filter { it.op == Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null) }) {
            val last = steps.drop(clear.index + 1).firstOrNull { it.op == Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1") } ?: continue
            val range = clear.index..last.index
            if (last.index in run.done) continue
            if (run.at in range || range.any { it in run.done }) return (clear.index + 1..last.index).filter { it !in run.done }
        }
        return emptyList()
    }

    // Runs those steps (typed, idempotent, CONTINUE); false when the TV went away, the run paused.
    private suspend fun finishAccessibilityRestore(run: Run, restore: List<Int>): Boolean {
        for (i in restore) {
            val step = run.plan.steps[i]
            run.at = i
            when (val r = perform(run, i, step.op, step.rendered, step.prior)) {
                Outcome.Ok -> { run.done += i; run.states[i] = NarrationState.DONE }
                is Outcome.Lost -> { pause(run, PauseReason.CONNECTION_LOST, r.error); return false }
                else -> { run.states[i] = NarrationState.SKIPPED }
            }
        }
        return true
    }

    // Safety only (R0: 6): find the open run, run step R for an open section, re-verify the
    // in-doubt step, land in Paused. Continue or undo is the user's decision afterwards.
    override suspend fun resume(tvId: TvId, session: TvSession) {
        val job = startLock.withLock {
            dropIfForgotten(tvId)
            if (runs.containsKey(tvId)) return
            val planId = journal.openRuns(tvId).firstOrNull() ?: return
            val records = journal.read(planId)
            val plan = plans.load(tvId, planId)
            if (plan == null || plan.id != planId || plan.tvId != tvId || !rendersTheSame(plan)) {
                // No trustworthy plan to resume from. An open section still has its step R, rebuilt
                // from the journal alone, before the run is given up (R1 R-2).
                val stepR = JournalStepR.build(records, tvId)
                if (stepR == null) {
                    journal.append(JournalRecord.End(planId, RunStatus.ABANDONED))
                    flow(tvId).value = RunState.Idle
                    return
                }
                // Without this TV there is nothing to run: the journal stays open, so the next resume
                // (after the reconnect) runs step R then.
                if (session.tvId != tvId || session.state.value !is ConnectionState.Connected) return
                val run = Run(stepR, session, fromJournalOnly = true)
                run.openSections += stepR.sections.single().name
                run.restorePoint = NarrationState.DONE
                runs[tvId] = run
                return@withLock scope.launch { abandonFromJournal(run) }
            }
            val run = Run(plan, session)
            Replay.apply(records, run.done, run.openSections, run.closedSections)
            run.undoing = Replay.undoStarted(records)
            run.lastPause = records.filterIsInstance<JournalRecord.Paused>().lastOrNull()?.reason
            run.home = if (run.closedSections.isNotEmpty() && records.filterIsInstance<JournalRecord.Section>().any { it.state == SectionState.ROLLED_BACK }) HomeOutcome.CONNECTION_LOST else null
            for (i in run.done) run.states[i] = NarrationState.DONE
            // Issue #616: no step begun, so the restore point may not have been saved; Carry on takes it.
            run.restorePoint = if (records.any { it is JournalRecord.Begin }) NarrationState.DONE else NarrationState.UPCOMING
            run.snapshot = snapshots.load(tvId, plan.baseSnapshot) ?: snapshots.latest(tvId)
            run.snapshot?.let { s -> resolve(run, s) }
            runs[tvId] = run
            scope.launch { safety(run, Replay.inDoubt(records)) }
        }
        job.join()
    }

    // Step R from the journal, then ABANDONED. A failure pauses the run with the error and the
    // section open; continue, undo and abandon all run step R again.
    private suspend fun abandonFromJournal(run: Run) = guarded(run) {
        run.hold = run.session.hold()
        val section = run.plan.sections.single()
        if (rollbackSection(run, section, HomeOutcome.CONNECTION_LOST)) end(run, RunStatus.ABANDONED, run.home)
    }

    private suspend fun safety(run: Run, inDoubt: Int?) {
        val plan = run.plan
        guarded(run) {
            if (run.session.state.value !is ConnectionState.Connected) { pause(run, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed); return@guarded }
            run.hold = run.session.hold()
            var reason = if (inDoubt != null) PauseReason.IN_DOUBT else run.lastPause ?: PauseReason.IN_DOUBT
            // A4 (issue #2): where the run stands, from the journal, before step R, so a pause in step
            // R names the step the run stopped at, never step 0.
            run.at = inDoubt ?: ((run.done.maxOrNull() ?: -1) + 1)
            val open = plan.sections.firstOrNull { it.name in run.openSections }
            if (open != null) {
                flow(plan.tvId).value = RunState.RollingBack(plan, open.from)
                if (!rollbackSection(run, open, HomeOutcome.CONNECTION_LOST)) return@guarded
                reason = PauseReason.CONNECTION_LOST   // the card: "We've put the old home screen back to be safe"
            }
            var at = (run.done.maxOrNull() ?: -1) + 1
            if (inDoubt != null) {
                val step = plan.steps[inDoubt]
                flow(plan.tvId).value = RunState.InDoubt(plan, inDoubt)
                val verified = step.rendered.verify?.let { v ->
                    val out = read(run.session, renderer.render(v))
                    renderer.check(step.op, out, step.prior) == VerifyResult.Ok && installedApkCheck(run.session, step.op) == Outcome.Ok
                } ?: false
                if (verified) {
                    journal.append(JournalRecord.Done(plan.id, inDoubt, 0, verified = true, ms = 0, resumed = true))
                    run.done += inDoubt; run.states[inDoubt] = NarrationState.DONE
                    at = inDoubt + 1
                } else {
                    at = inDoubt
                }
            }
            run.at = at
            pause(run, reason, null)
        }
    }

    private fun AdbError.isLost(): Boolean = this is AdbError.Transport || this is AdbError.Auth

    companion object {
        val GATED = setOf(Privilege.PROFILE_STEP, Privilege.COMMUNITY, Privilege.TAKEOVER)
        const val WATCHDOG_MARGIN_MS = 10_000L   // the phone's window ends at least this long before the watchdog fires (R1 R-1)
        // The journal's Failed text for a live protected refusal. KEPT_TV_INPUT marks a package kept
        // because it runs the TV's inputs (RF-LAUNCHER-250 F1); Done and Manage name it.
        const val PROTECTED = "protected: "
        const val KEPT_TV_INPUT = PROTECTED + Denylist.REASON_TV_INPUT
        // The journal's Failed text for a streamed install the TV ended with a protocol error while
        // the connection stayed up (issues #80, #367).
        const val STREAM_FAILED = "streamed install failed, the TV is still connected: "
        val SETTINGS_APPS = setOf("com.android.settings", "com.android.tv.settings")
    }
}

// Step R from the journal alone (R1 R-2), for a resume whose plan file is missing, tampered or
// from another TV while the journal shows an open section. The `Section ARMED` record gives the
// token; the `Begin` records after it (up to step R's own `Rollback`) give the arm step (and with
// it the stock launcher the watchdog would restore), the `Disable` ops to invert and the `SetHome`
// prior. Nothing else from the journal is run: the ops are step R's vocabulary only and are
// rendered again by the one renderer.
internal object JournalStepR {
    fun build(records: List<JournalRecord>, tvId: TvId): Plan? {
        val start = records.firstOrNull() as? JournalRecord.PlanStart ?: return null
        if (start.tvId != tvId) return null
        var armedAt = -1
        var armed: JournalRecord.Section? = null
        for ((i, r) in records.withIndex()) {
            if (r !is JournalRecord.Section) continue
            when (r.state) {
                SectionState.ARMED -> { armed = r; armedAt = i }
                SectionState.CONFIRMED, SectionState.ROLLED_BACK -> if (r.name == armed?.name) { armed = null; armedAt = -1 }
            }
        }
        val section = armed ?: return null
        val inside = records.drop(armedAt + 1).takeWhile { it !is JournalRecord.Rollback }.filterIsInstance<JournalRecord.Begin>()
        val arm = inside.firstOrNull { (it.op as? Op.ArmHomeWatchdog)?.token == section.token } ?: return null   // never armed: nothing on the TV changed
        val stock = (arm.op as Op.ArmHomeWatchdog).stock
        val disabled = inside.mapNotNull { (it.op as? Op.Disable)?.pkg }.distinct()
        val setHome = inside.firstOrNull { it.op is Op.SetHome }
        // WP4 (G4): a journal from before 0.4.5 may hold the chooser or another placeholder as the
        // SetHome prior; step R then hands HOME to the stock home the watchdog was armed with.
        val home: Component = setHome?.prior?.home?.takeUnless { StockHome.isPlaceholder(it) } ?: stock
        val rollback: List<Op> = listOf(Op.ConfirmHome(section.token), Op.KillWatchdog(section.token)) +
            disabled.map { Op.Enable(it) } + listOf(Op.SetHome(home), Op.VerifyHome(home), Op.PressHome)
        val expected = (setHome?.op as? Op.SetHome)?.component ?: home
        val guarded = GuardedSection(section.name, arm.i, inside.maxOf { it.i }, section.token, 0L, expected, emptySet(), rollback)
        return Plan(
            id = start.planId, tvId = tvId, kind = PlanKind.SETUP, baseSnapshot = start.base, steps = emptyList(),
            sections = listOf(guarded), summary = PlanSummary(emptyList(), emptyList(), emptyList(), false, false),
            hash = start.hash, createdAtMs = start.atMs, appVersion = start.app,
        )
    }
}

// Step R's vocabulary (PRODUCT-SPEC 3.4): defuse both layers for this section's token, then the
// TV's own home screen back. Anything else in a plan file's rollback list is ignored.
internal object StepR {
    fun allows(op: Op, section: GuardedSection): Boolean = when (op) {
        is Op.ConfirmHome -> op.token == section.token
        is Op.KillWatchdog -> op.token == section.token
        is Op.Enable, is Op.SetHome, is Op.VerifyHome, Op.PressHome -> true
        else -> false
    }

    // The ops whose failure means the TV's own home screen is not back.
    fun mustSucceed(op: Op): Boolean = op is Op.Enable || op is Op.SetHome || op is Op.VerifyHome
}

// What a journal says about a run, for resume.
internal object Replay {
    fun apply(records: List<JournalRecord>, done: MutableSet<Int>, open: MutableSet<String>, closed: MutableSet<String>) {
        var undoing = false
        for (r in records) {
            when (r) {
                is JournalRecord.Done -> if (undoing) done -= r.i else if (r.i >= 0) done += r.i
                is JournalRecord.Rollback -> undoing = true
                is JournalRecord.Section -> when (r.state) {
                    SectionState.ARMED -> open += r.name
                    SectionState.CONFIRMED, SectionState.ROLLED_BACK -> { open -= r.name; closed += r.name }
                }
                else -> {}
            }
        }
    }

    // An undo of the run was started (R1 R-6): a `Rollback` record written while no section was open.
    // Step R's own `Rollback` is written inside its open section, so it does not count.
    fun undoStarted(records: List<JournalRecord>): Boolean {
        val open = mutableSetOf<String>()
        for (r in records) {
            when (r) {
                is JournalRecord.Section -> if (r.state == SectionState.ARMED) open += r.name else open -= r.name
                is JournalRecord.Rollback -> if (open.isEmpty()) return true
                else -> {}
            }
        }
        return false
    }

    // The step index of a Begin with no Done or Failed after it, if the journal ends that way.
    fun inDoubt(records: List<JournalRecord>): Int? {
        var pending: Int? = null
        var undoing = false
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> pending = if (undoing) null else r.i
                is JournalRecord.Done, is JournalRecord.Failed -> pending = null
                is JournalRecord.Rollback -> { undoing = true; pending = null }
                else -> {}
            }
        }
        return pending
    }
}

// The staleness check of 5.9: the priors of the remaining steps against a fresh snapshot.
internal object Staleness {
    fun writeTarget(op: Op): PackageName? = when (op) {
        is Op.Disable -> op.pkg
        is Op.DefaultState -> op.pkg
        is Op.Uninstall -> op.pkg
        else -> null
    }

    fun diff(steps: List<Step>, snapshot: Snapshot): List<String> {
        val out = mutableListOf<String>()
        for (s in steps) {
            val prior = s.prior ?: continue
            when (val op = s.op) {
                is Op.Disable -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.Enable -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.DefaultState -> packageDiff(op.pkg, prior, snapshot, out)
                is Op.Uninstall -> if (prior.present == true && op.pkg !in snapshot.packages) out += "${op.pkg.value}: was installed, now missing"
                is Op.Install -> presentDiff(op.pkg, prior, snapshot, out)
                is Op.InstallStream -> presentDiff(op.pkg, prior, snapshot, out)
                is Op.SettingsPut -> if (op.key in snapshot.settings) {
                    val now = snapshot.settings[op.key]
                    if (now != prior.settingValue) out += "${op.key.key}: was ${prior.settingValue ?: "unset"}, now ${now ?: "unset"}"
                }
                // WP4 (B5): the planner records the stock home as the prior while HOME answers with
                // the chooser, a placeholder or nothing (StockHome.recorded), so that answer is no change,
                // as long as the recorded home still claims HOME.
                is Op.SetHome -> prior.home?.let {
                    val undecided = snapshot.home.resolved.let { r -> r == null || StockHome.isPlaceholder(r) }
                    val stillClaims = snapshot.home.claimants.any { c -> c.component == it }
                    if (snapshot.home.resolved != it && !(undecided && stillClaims)) out += "home: was ${it.flat}, now ${snapshot.home.resolved?.flat ?: "none"}"
                }
                else -> {}
            }
        }
        return out.distinct()
    }

    private fun packageDiff(pkg: PackageName, prior: Prior, snapshot: Snapshot, out: MutableList<String>) {
        val was = prior.packageState ?: return
        val now = snapshot.packages[pkg]?.enabled
        if (now == null) out += "${pkg.value}: was ${was.name}, now missing"
        else if (now != was) out += "${pkg.value}: was ${was.name}, now ${now.name}"
    }

    private fun presentDiff(pkg: PackageName, prior: Prior, snapshot: Snapshot, out: MutableList<String>) {
        val was = prior.present ?: return
        val now = pkg in snapshot.packages
        if (was != now) out += "${pkg.value}: was ${if (was) "installed" else "absent"}, now ${if (now) "installed" else "absent"}"
    }
}
