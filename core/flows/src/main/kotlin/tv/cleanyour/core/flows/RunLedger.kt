package tv.cleanyour.core.flows

import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.Journal
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.store.DocumentStore

// Which plans ran on which TV, so the flows can derive ChangeRecords from the journals (the
// Journal interface lists only open runs) and tell the resume prompt its counts without the Plan
// document. One line per run in tvs/<tvId>/runs.txt: planId|kind|createdAtMs|disables|takeover.
data class RunEntry(val planId: PlanId, val kind: PlanKind, val createdAtMs: Long, val disables: Int, val takeover: Boolean)

// What a run's journal says so far: the counts for "14 of 23" and whether a HOME section is open.
data class RunProgress(val doneSteps: Int, val doneDisables: Int, val openSection: Boolean, val ended: Boolean)

// The op that made a run pause, its exit code and the head of the TV's stderr; `undo` when it was a
// step of step R (putting the TV's own home screen back).
data class LastFailure(val op: Op, val exit: Int?, val stderrHead: String, val undo: Boolean)

class RunLedger(private val store: DocumentStore, private val journal: Journal) {

    private fun path(tvId: TvId) = "tvs/${tvId.value}/runs.txt"

    suspend fun record(plan: Plan) {
        val existing = runs(plan.tvId)
        if (existing.any { it.planId == plan.id }) return
        val entry = RunEntry(plan.id, plan.kind, plan.createdAtMs, plan.steps.count { it.op is Op.Disable }, plan.sections.isNotEmpty())
        store.write(path(plan.tvId), encode(existing + entry))
    }

    suspend fun runs(tvId: TvId): List<RunEntry> = store.read(path(tvId)) { decode(it) }.orEmpty()

    suspend fun entry(tvId: TvId, planId: PlanId): RunEntry? = runs(tvId).firstOrNull { it.planId == planId }

    suspend fun forget(tvId: TvId) { store.delete(path(tvId)) }

    // Every committed change this app made to the TV, oldest first: a Begin followed by its Done.
    // Reads and phone-side steps are not changes.
    suspend fun changes(tvId: TvId): List<ChangeRecord> = runs(tvId).flatMap { run ->
        val records = journal.read(run.planId)
        val begins = HashMap<Int, JournalRecord.Begin>()
        val out = ArrayList<ChangeRecord>()
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> begins[r.i] = r
                is JournalRecord.Done -> begins.remove(r.i)?.takeIf { isChange(it.op) }?.let { out += ChangeRecord(run.planId, r.i, it.op, it.prior, it.atMs) }
                else -> {}
            }
        }
        out
    }

    suspend fun progress(planId: PlanId): RunProgress {
        val records = journal.read(planId)
        val begins = HashMap<Int, Op>()
        val doneOps = HashMap<Int, Op?>()   // the last op done at each step: step R and undo journal their op under the step they undo
        var doneSteps = 0
        var open = false
        var ended = false
        for (r in records) {
            when (r) {
                is JournalRecord.Begin -> begins[r.i] = r.op
                is JournalRecord.Done -> { doneSteps++; doneOps[r.i] = begins[r.i] }
                is JournalRecord.Section -> open = r.state == SectionState.ARMED
                is JournalRecord.End -> ended = true
                else -> {}
            }
        }
        // QA of 0.4.5: a switch-off that step R or an undo switched back on (the TV's own home screen
        // after the home check ran out of time) is not counted as switched off.
        val doneDisables = doneOps.values.count { it is Op.Disable }
        return RunProgress(doneSteps, doneDisables, open, ended)
    }

    // The steps of a run whose switch-off the live check kept because the package runs the TV's
    // inputs (RF-LAUNCHER-250 F1): Done and Manage name them with their own line.
    suspend fun keptTvInputs(planId: PlanId): Set<Int> =
        journal.read(planId).filterIsInstance<JournalRecord.Failed>().filter { it.error == DefaultEngine.KEPT_TV_INPUT }.map { it.i }.toSet()

    // The op that made a run pause, from its journal, for the pause line of the diagnostics log
    // (0.3.2 B3); null when the journal does not show one, and the line then names no op (never an
    // older refusal that was only skipped).
    // - A pause in step R (a Rollback record written inside an open section, then the pause with the
    //   section still open): the engine pauses on the first failure of a must-succeed op (Enable,
    //   SetHome, VerifyHome) since the last VerifyHome that passed, so that is the one named, with
    //   `undo` set. A refused op before a passing VerifyHome was a skip.
    // - Any other pause: the Failed record of step `at` written since the previous pause, outside an
    //   undo of the whole run (whose failures are skips).
    // The pause described is the newest Paused record; when the journal has records after it (the
    // Paused append itself failed), the journal's tail is the pause. A switch-off the live check kept
    // (KEPT_TV_INPUT) is never a failure.
    suspend fun lastFailure(planId: PlanId, at: Int): LastFailure? {
        val open = HashSet<String>()
        var begun: JournalRecord.Begin? = null
        var stepR = false        // inside step R's Rollback of an open section
        var undoing = false      // inside an undo of the whole run
        var stepRFailure: LastFailure? = null
        var atFailure: LastFailure? = null
        var paused: LastFailure? = null
        var endsWithPause = false
        for (r in journal.read(planId)) {
            endsWithPause = false
            when (r) {
                is JournalRecord.Begin -> begun = r
                is JournalRecord.Rollback -> { stepR = open.isNotEmpty(); undoing = !stepR; stepRFailure = null }
                is JournalRecord.Section -> {
                    if (r.state == SectionState.ARMED) open += r.name else open -= r.name
                    stepR = false; stepRFailure = null
                }
                is JournalRecord.Done -> if (stepR && begun?.i == r.i && begun?.op is Op.VerifyHome) stepRFailure = null
                is JournalRecord.Failed -> if (r.error != DefaultEngine.KEPT_TV_INPUT) {
                    val f = begun?.takeIf { it.i == r.i }?.let { LastFailure(it.op, r.exit, r.stderrHead, stepR) }
                    when {
                        f == null -> {}
                        stepR -> if (stepRFailure == null && mustSucceed(f.op)) stepRFailure = f
                        !undoing && r.i == at -> atFailure = f
                    }
                }
                is JournalRecord.Paused -> {
                    paused = if (stepR) stepRFailure else atFailure.takeIf { r.at == at }
                    stepR = false; undoing = false; stepRFailure = null; atFailure = null
                    endsWithPause = true
                }
                else -> {}
            }
        }
        return if (endsWithPause) paused else if (stepR) stepRFailure else atFailure
    }

    // Why a home screen switch ended VERIFY_FAILED (issue #338: three tries, each "rolling back" with
    // nothing in the log to say why): the first failed step that the switch stands on, from the
    // journal. That is a step inside the guarded section before its rollback, or, when the section
    // never opened, the install or the first start of the new home screen. Null when the journal
    // shows none (the section's own checks before it opened, such as the installed APK's hash).
    suspend fun homeSwitchFailure(plan: Plan): LastFailure? {
        val section = plan.sections.firstOrNull() ?: return null
        val launcher = section.expectedHome
        fun standsOn(i: Int, op: Op): Boolean = i in section.from..section.to || when (op) {
            is Op.Install -> op.pkg == launcher.pkg
            is Op.InstallStream -> op.pkg == launcher.pkg
            is Op.StartActivity -> op.component == launcher
            else -> false
        }
        var begun: JournalRecord.Begin? = null
        for (r in journal.read(plan.id)) when (r) {
            is JournalRecord.Begin -> begun = r
            is JournalRecord.Rollback -> return null
            is JournalRecord.Failed -> {
                val b = begun?.takeIf { it.i == r.i }
                if (b != null && r.error != DefaultEngine.KEPT_TV_INPUT && standsOn(b.i, b.op)) return LastFailure(b.op, r.exit, r.stderrHead.ifEmpty { r.error }, undo = false)
            }
            else -> {}
        }
        return null
    }

    // The packages this app switched off and has not put back since, in the order they went off.
    // QA of 0.4.5: the packages a run switched off and left off: the last op done at each step is a
    // switch-off (step R and undo journal their op under the step they undo).
    suspend fun disabledBy(planId: PlanId): Set<PackageName> {
        val begins = HashMap<Int, Op>()
        val doneOps = HashMap<Int, Op?>()
        for (r in journal.read(planId)) when (r) {
            is JournalRecord.Begin -> begins[r.i] = r.op
            is JournalRecord.Done -> doneOps[r.i] = begins[r.i]
            else -> {}
        }
        return doneOps.values.mapNotNull { (it as? Op.Disable)?.pkg }.toSet()
    }

    suspend fun switchedOffByUs(tvId: TvId): List<PackageName> {
        val off = LinkedHashSet<PackageName>()
        for (c in changes(tvId)) when (val op = c.op) {
            is Op.Disable -> off.add(op.pkg)
            is Op.Enable -> off.remove(op.pkg)
            is Op.DefaultState -> off.remove(op.pkg)
            is Op.Uninstall -> off.remove(op.pkg)
            else -> {}
        }
        return off.toList()
    }

    companion object {
        // Step R's ops whose failure means the TV's own home screen is not back (the engine's rule).
        private fun mustSucceed(op: Op): Boolean = op is Op.Enable || op is Op.SetHome || op is Op.VerifyHome

        fun isChange(op: Op): Boolean = when (op) {
            is Op.Read, is Op.VerifyHome, is Op.Settle, Op.PressHome, is Op.CurtainCue -> false
            else -> true
        }

        internal fun encode(entries: List<RunEntry>): ByteArray = entries.joinToString("") {
            "${it.planId.value}|${it.kind.name}|${it.createdAtMs}|${it.disables}|${it.takeover}\n"
        }.encodeToByteArray()

        internal fun decode(bytes: ByteArray): List<RunEntry> = bytes.decodeToString().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 5) return@mapNotNull null
            val kind = PlanKind.entries.firstOrNull { it.name == p[1] } ?: return@mapNotNull null
            RunEntry(PlanId(p[0]), kind, p[2].toLongOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4] == "true")
        }
    }
}
