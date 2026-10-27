package tv.cleanyour.core.engine

import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.SwitchOffRule
import tv.cleanyour.core.planner.WizardWifi
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.Issue033
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #10 (Xiaomi TV Box S, MiTV-AFKR0, jaws, Google TV 14, the Xiaomi brand sure set): the TV
// answers `pm disable-user` of PatchWall (com.mitv.tvhome.atv) with exit 0 and keeps it on, so the
// verify read (pm list packages -d) does not list it. 0.3.2 took that GuardFailed as a failure and
// paused ("step failed: Disable com.mitv.tvhome.atv: guard_failed"), and Carry on re-ran the same
// step into the same pause, over and over. From 0.3.3 a switch-off outside the home screen switch
// that the TV keeps on, or that the live check refuses, is a skip.
class Issue10Test {
    private val source = BundledProfileSource()

    // The jaws box as the scripted TV, with the bundled profiles, and PatchWall kept on by the firmware.
    private fun jaws(scope: kotlinx.coroutines.test.TestScope): Harness {
        val h = Harness(scope, ScriptedTv(Issue033.jawsSnapshot()))
        h.profiles = FakeProfileSource(resolver = { f, _ -> source.resolveNow(f) })
        h.tv.answer = { line ->
            if (line == "pm disable-user --user 0 ${Issue033.patchwall.value}") ExecResult(0, "Package ${Issue033.patchwall.value} new state: enabled", "", false, 1) else null
        }
        return h
    }

    // The 0.3.2 plan for that box: the Xiaomi sure set, the home screen switch blocked (WIZARD_WIFI).
    // Issue #111: PatchWall is an ask row since 0.3.8, so the owner chose it in Customise here.
    private fun sureSetPlan(h: Harness) = h.plan(PlanRequest.Setup(source.resolveNow(h.tv.facts),
        Choices(emptySet(), setOf(Issue033.patchwall), null, null, installLauncher = false, takeover = false, replaceLauncherSigner = false, keepDebuggingOn = false,
            wizardWifi = WizardWifi.OWNER), h.artifact, Level.SIMPLE))

    @Test fun aSwitchOffTheTvKeepsOnIsSkippedAndTheRunGoesOn() = runTest(timeout = TEST_TIMEOUT) {
        val h = jaws(this)
        val engine = h.engine()
        val plan = sureSetPlan(h)
        val step = plan.steps.single { it.op == Op.Disable(Issue033.patchwall) }
        val others = plan.steps.mapNotNull { (it.op as? Op.Disable)?.pkg }.filter { it != Issue033.patchwall }
        assertTrue(others.isNotEmpty())
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine), "never a pause")
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(step), fin.skipped)
        assertEquals(EnabledState.ENABLED, h.tv.state(Issue033.patchwall))
        for (p in others) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p), p.value)
        // The line went to the TV once; the journal keeps what the verify read said.
        assertEquals(1, h.tv.execs("pm disable-user --user 0 ${Issue033.patchwall.value}"))
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single()
        assertEquals(step.index, failed.i)
        assertTrue(failed.error.startsWith("verify:"), failed.error)
        assertTrue(h.types(plan).none { it.startsWith("paused") })
        // Nothing of PatchWall is in the change records: Manage never lists it as switched off.
        assertTrue(ChangeRecords.fromJournal(h.records(plan)).none { it.op == Op.Disable(Issue033.patchwall) })
    }

    // A run 0.3.2 left paused at that step (the journal of the report: STEP_FAILED at the refused
    // switch-off, then a Carry on that met it again): after the update, Carry on skips it and the
    // run finishes.
    @Test fun carryOnFromThe032PauseFinishesTheRun() = runTest(timeout = TEST_TIMEOUT) {
        val h = jaws(this)
        val plan = sureSetPlan(h)
        val at = plan.steps.single { it.op == Op.Disable(Issue033.patchwall) }.index
        val step = plan.steps[at]
        val j = h.journal
        h.plans.save(plan)
        j.append(JournalRecord.PlanStart(plan.id, plan.tvId, plan.baseSnapshot, plan.steps.size, plan.hash, plan.appVersion, 1))
        h.snapshots.save(h.tv.snapshot(SnapshotReason.PRE_PLAN))
        for (i in 0 until at) {
            val s = plan.steps[i]
            j.append(JournalRecord.Begin(plan.id, i, s.op, s.rendered.lines.map { it.text }, s.prior, 1))
            (s.op as? Op.Disable)?.let { h.tv.packages[it.pkg] = h.tv.packages.getValue(it.pkg).copy(enabled = EnabledState.DISABLED_USER) }
            j.append(JournalRecord.Done(plan.id, i, 0, true, 1))
        }
        repeat(2) {
            j.append(JournalRecord.Begin(plan.id, at, step.op, step.rendered.lines.map { it.text }, step.prior, 1))
            j.append(JournalRecord.Failed(plan.id, at, null, "verify: expected package:${Issue033.patchwall.value} listed by pm list packages -d, got ", ""))
            j.append(JournalRecord.Paused(plan.id, at, PauseReason.STEP_FAILED))
        }
        val engine = h.engine()
        engine.resume(h.tv.tvId, h.tv)
        val paused = assertIs<RunState.Paused>(h.settle(engine))
        assertEquals(at, paused.at)
        engine.continueRun(h.tv.tvId, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(at to step.op), fin.skipped.map { it.index to it.op })   // the plan as read back from its file
        assertEquals(EnabledState.ENABLED, h.tv.state(Issue033.patchwall))
    }

    // The live check's refusal outside the switch is a skip too (nothing is sent): here HOME moved
    // to PatchWall after the plan was made, so the engine keeps it on as a home screen.
    @Test fun aLiveRefusalOutsideTheSwitchIsASkip() = runTest(timeout = TEST_TIMEOUT) {
        val h = jaws(this)
        h.tv.answer = null
        val engine = h.engine()
        val plan = sureSetPlan(h)
        h.tv.claimants += tv.cleanyour.core.model.Component(Issue033.patchwall, ".MainActivity")
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(Op.Disable(Issue033.patchwall)), fin.skipped.map { it.op })
        assertEquals(0, h.tv.execs("pm disable-user --user 0 ${Issue033.patchwall.value}"), "the line was never sent")
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single()
        assertEquals(DefaultEngine.PROTECTED + tv.cleanyour.core.planner.DefaultPlanner.REASON_HOME_SCREEN, failed.error)
    }

    // Inside the home screen switch nothing changes: the TV keeping its own home screen on is a
    // failed switch, and step R puts the TV's own home screen back (ROLLED_BACK, VERIFY_FAILED).
    @Test fun insideTheSwitchAKeptOnSwitchOffStillRollsBack() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.answer = { line -> if (line == "pm disable-user --user 0 ${h.stock.pkg.value}") ExecResult(0, "Package ${h.stock.pkg.value} new state: enabled", "", false, 1) else null }
        val engine = h.engine()
        val plan = h.setupPlan(takeover = true, installLauncher = true)
        val section = plan.sections.single()
        val stockStep = plan.steps.single { it.op == Op.Disable(h.stock.pkg) }
        assertTrue(stockStep.index in section.from..section.to)
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertTrue(h.types(plan).any { it.startsWith("rollback") }, "step R ran")
        assertEquals(h.stock, h.tv.home)
    }

    // The engine's live rule is the planner's: a launcher the person installed is never switched
    // off, inside the switch too, whatever a plan file says.
    @Test fun aLauncherThePersonInstalledIsNeverSwitchedOffByAnyPlan() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this, ScriptedTv(Issue033.g07Snapshot()))
        val engine = h.engine()
        val theirs: PackageName = Issue033.personsLauncher.pkg
        val plan = h.opsPlan(Op.Disable(theirs))
        engine.start(plan, h.tv, plan.hash, h.pass())
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(0, h.tv.execs("pm disable-user"))
        assertEquals(DefaultEngine.PROTECTED + SwitchOffRule.REASON_YOUR_LAUNCHER, h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error)
        assertEquals(Op.Disable(theirs), fin.skipped.single().op)
        assertEquals(EnabledState.ENABLED, h.tv.state(theirs))
    }
}
