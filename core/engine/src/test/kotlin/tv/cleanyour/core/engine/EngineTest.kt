package tv.cleanyour.core.engine

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.PackageDump
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.store.GateMethod
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {

    @Test fun startRefusesInTheContractOrder() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        // NOT_CONNECTED before WRONG_TV before HASH_MISMATCH before the gate.
        h.tv.disconnect()
        assertEquals(StartResult.Refused(StartRefusal.NOT_CONNECTED), engine.start(plan, h.tv, plan.hash, null))
        h.tv.reconnect()
        val otherTv = plan.copy(tvId = TvId("fedcba9876543210"))
        assertEquals(StartResult.Refused(StartRefusal.WRONG_TV), engine.start(otherTv, h.tv, otherTv.hash, null))
        assertEquals(StartResult.Refused(StartRefusal.HASH_MISMATCH), engine.start(plan, h.tv, Sha256("0".repeat(64)), h.pass()))
        assertEquals(StartResult.Refused(StartRefusal.GATE_REQUIRED), engine.start(plan, h.tv, plan.hash, null))
        assertEquals(StartResult.Refused(StartRefusal.GATE_REQUIRED), engine.start(plan, h.tv, plan.hash, h.gate.mint(TvId("fedcba9876543210"))))
        assertEquals(StartResult.Refused(StartRefusal.GATE_EXPIRED), engine.start(plan, h.tv, plan.hash, h.gate.mint(h.tv.tvId, issuedAtMs = h.clock.nowMs() - 11 * 60_000)))
        assertTrue(h.journal.read(plan.id).isEmpty(), "nothing journaled by a refusal")
        assertEquals(RunState.Idle, engine.runState(h.tv.tvId).value)
        // A pass with NONE (no lock screen, D17) is still a pass; a run opens; a second start is RUN_OPEN.
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.gate.mint(h.tv.tvId, GateMethod.NONE)))
        assertEquals(StartResult.Refused(StartRefusal.RUN_OPEN), engine.start(plan, h.tv, plan.hash, h.pass()))
        h.settle(engine)
        assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        // A read-only plan needs no pass.
        val reads = h.opsPlan(Op.Read(tv.cleanyour.core.model.ProbeKind.DISABLED), Op.VerifyHome(h.stock), Op.Settle(1))
        assertEquals(StartResult.Started, engine.start(reads, h.tv, reads.hash, null))
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        // DebugOff (SESSION_END) needs no pass either.
        val off = h.plan(PlanRequest.DebugOff)
        assertEquals(StartResult.Started, engine.start(off, h.tv, off.hash, null))
        h.settle(engine)
        assertEquals("0", h.tv.settings[SettingKey.ADB_ENABLED])
    }

    @OptIn(RawShell::class)
    @Test fun hashMismatchCatchesTamperedLinesAndOps() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        val step = plan.steps.first { it.op is Op.Disable }
        val tamperedLine = step.copy(rendered = step.rendered.copy(lines = listOf(ShellLine("'pm' 'disable-user' '--user' '0' 'com.google.android.youtube.tv'"))))
        val p1 = plan.copy(steps = plan.steps.map { if (it.index == step.index) tamperedLine else it })
        assertEquals(StartResult.Refused(StartRefusal.HASH_MISMATCH), engine.start(p1, h.tv, p1.hash, h.pass()))
        // Same lines, hash recomputed over them, but the op was swapped: re-rendering the op differs.
        val swapped = step.copy(op = Op.Disable(PackageName("com.google.android.youtube.tv")))
        val p2 = plan.copy(steps = plan.steps.map { if (it.index == step.index) swapped else it })
        assertEquals(StartResult.Refused(StartRefusal.HASH_MISMATCH), engine.start(p2, h.tv, p2.hash, h.pass()))
        // A protected package smuggled into a plan file: the renderer refuses it, so the hash check refuses the plan.
        val smuggled = step.copy(op = Op.Disable(PackageName("com.android.systemui")))
        val p3 = plan.copy(steps = plan.steps.map { if (it.index == step.index) smuggled else it })
        assertEquals(StartResult.Refused(StartRefusal.HASH_MISMATCH), engine.start(p3, h.tv, p3.hash, h.pass()))
        assertTrue(h.tv.execLog.isEmpty(), "nothing was sent")
    }

    // RFC-CURTAIN-1: a step with a blank narration (the curtain's start and cues) is silent on the
    // Working screen: not listed, not counted in the percent. It still runs, and the journal holds
    // it like any other step.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun silentStepsAreNeitherListedNorCounted() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val base = h.opsPlan(Op.Disable(h.debloat[0]), Op.Settle(5), Op.Disable(h.debloat[1]), Op.Settle(5))
        val plan = base.copy(steps = base.steps.map { if (it.index == 1) it.copy(narration = " ") else it.copy(narration = "voiced ${it.index}") })
        assertEquals(base.hash, plan.hash, "narration is not part of the plan hash")
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        runCurrent()   // step 0 done, waiting in the silent settle of step 1
        val first = engine.runState(h.tv.tvId).value
        assertIs<RunState.Running>(first)
        assertEquals(1, first.step)
        assertEquals(listOf("Restore point saved", "voiced 0", "voiced 2", "voiced 3"), first.narration.map { it.text })
        assertEquals(33, first.percent)
        advanceTimeBy(5_500); runCurrent()   // through the silent step and step 2, waiting in step 3
        val second = engine.runState(h.tv.tvId).value
        assertIs<RunState.Running>(second)
        assertEquals(3, second.step)
        assertEquals(66, second.percent)
        assertEquals(listOf(NarrationState.DONE, NarrationState.DONE, NarrationState.DOING), second.narration.drop(1).map { it.state })
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        val expected = listOf("plan") + plan.steps.flatMap { listOf("begin${it.index}", "done${it.index}") } + "end:COMPLETED"
        assertEquals(expected, h.types(plan))
        for (p in h.debloat.take(2)) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p), p.value)
    }

    // RFC-CURTAIN-1: a Setup plan with the TV curtain runs to the end: the staleness check before
    // step 0 accepts it (the curtain is not on the TV yet), every cue line goes out, the curtain is
    // installed and removed again, and the switch-offs are as without it.
    @Test fun aSetupWithTheCurtainRunsAndRemovesIt() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val curtainPkg = PackageName("tv.cleanyour.curtain")
        val bytes = "fake curtain apk".encodeToByteArray()
        val sha = h.tv.acceptApk(bytes, curtainPkg, 1, h.signer)
        val curtain = tv.cleanyour.core.model.LauncherArtifact(curtainPkg, 1, "0.1", sha, h.signer, tv.cleanyour.core.model.BlobRef(sha, bytes.size.toLong(), "bundled:curtain.apk"))
        val base = h.setupPlan(takeover = false)
        val req = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.Exact(h.profile()),
            tv.cleanyour.core.planner.Choices(emptySet(), emptySet(), null, null, true, false, false, false), h.artifact, tv.cleanyour.core.model.Level.SIMPLE, curtain = curtain)
        val plan = h.plan(req)
        val cues = plan.steps.count { it.op is Op.CurtainCue }
        assertEquals(h.debloat.size + 6, cues, "SCAN, RESTORE_SAVED, one OFF per switch-off, GROUP_DONE, INSTALL, INSTALLED, FINALE")
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value
        assertIs<RunState.Finished>(fin)
        assertEquals(RunStatus.COMPLETED, fin.status, h.types(plan).toString())
        assertEquals(cues, h.tv.execs("am broadcast -n tv.cleanyour.curtain/.CueReceiver"))
        assertEquals(1, h.tv.execs("am start -n tv.cleanyour.curtain/.CurtainActivity"))
        assertTrue(curtainPkg !in h.tv.packages, "the curtain is gone at the end")
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p), p.value)
        assertEquals(base.steps.size + plan.steps.count { it.group == tv.cleanyour.core.planner.DefaultPlanner.GROUP_CURTAIN }, plan.steps.size)
        val expected = listOf("plan") + plan.steps.flatMap { listOf("begin${it.index}", "done${it.index}") } + "end:COMPLETED"
        assertEquals(expected, h.types(plan))
    }

    // QA of 0.4.5: "Stop here" after a paused Setup gave the run up with the TV curtain still
    // installed and running. The run's own curtain removal runs first; nothing else is touched, and
    // what the run switched off stays off.
    @Test fun stopHereRemovesTheCurtainTheRunInstalled() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val curtainPkg = PackageName("tv.cleanyour.curtain")
        val bytes = "fake curtain apk".encodeToByteArray()
        val sha = h.tv.acceptApk(bytes, curtainPkg, 1, h.signer)
        val curtain = tv.cleanyour.core.model.LauncherArtifact(curtainPkg, 1, "0.1", sha, h.signer, tv.cleanyour.core.model.BlobRef(sha, bytes.size.toLong(), "bundled:curtain.apk"))
        val req = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.Exact(h.profile()),
            tv.cleanyour.core.planner.Choices(emptySet(), emptySet(), null, null, true, false, false, false), h.artifact, tv.cleanyour.core.model.Level.SIMPLE, curtain = curtain)
        val plan = h.plan(req)
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        val firstOff = plan.steps.first { it.op is Op.Disable }.index
        var guard = 0
        while (h.records(plan).none { it is JournalRecord.Done && it.i == firstOff }) { advanceTimeBy(100); runCurrent(); check(guard++ < 6_000) { "no switch-off done" } }
        assertTrue(curtainPkg in h.tv.packages, "the curtain is on the TV")
        engine.stop(h.tv.tvId)
        val paused = h.settle(engine)
        assertIs<RunState.Paused>(paused)
        assertEquals(PauseReason.USER_STOP, paused.reason)
        val before = h.tv.execLog.size
        engine.abandon(h.tv.tvId); h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value
        assertIs<RunState.Finished>(fin)
        assertEquals(RunStatus.ABANDONED, fin.status)
        assertTrue(curtainPkg !in h.tv.packages, "the curtain is not left on the TV")
        val sent = h.tv.execLog.drop(before).map { tv.cleanyour.testing.fixtures.Recording.normalize(it) }.filter { !it.startsWith("dumpsys") && !it.startsWith("pm list") && !it.startsWith("pm path") }   // reads
        assertEquals(listOf("pm uninstall --user 0 tv.cleanyour.curtain"), sent, "only the curtain's removal is sent")
        val off = plan.steps.filter { it.op is Op.Disable && it.index in h.records(plan).filterIsInstance<JournalRecord.Done>().map { d -> d.i } }
        assertTrue(off.isNotEmpty())
        for (s in off) assertEquals(EnabledState.DISABLED_USER, h.tv.state((s.op as Op.Disable).pkg), "kept off")
    }

    // RF-CURTAIN-1 F1: a new group forces a thumbnail, but silent steps (the curtain's cues woven
    // after every switch-off) never force one and do not end a group, so the cues add no screenshots.
    @Test fun silentStepsForceNoThumbnail() {
        val receiver = tv.cleanyour.core.model.Component(PackageName("tv.cleanyour.curtain"), ".CueReceiver")
        val token = tv.cleanyour.core.model.RunToken("fedcba9876543210")
        fun cue(k: Int) = Op.CurtainCue(receiver, token, tv.cleanyour.core.model.CurtainStage.OFF, k, 3)
        fun forced(woven: Boolean): Int {
            var count = 0
            runTest(timeout = TEST_TIMEOUT) {
                val h = Harness(this)
                val engine = h.engine(thumbnailEveryMs = 3_600_000)   // only forced screenshots
                val groups = listOf("ads", "ads", "tracking")
                val ops = h.debloat.take(3).flatMapIndexed { k, p -> listOf<Op>(Op.Disable(p)) + if (woven) listOf(cue(k + 1)) else emptyList() }
                val base = h.opsPlan(*ops.toTypedArray())
                var g = 0
                val plan = base.copy(steps = base.steps.map { s ->
                    if (s.op is Op.CurtainCue) s.copy(group = "curtain", narration = "", onFailure = FailurePolicy.CONTINUE)
                    else s.copy(group = groups[g++], narration = "Switching off")
                })
                assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
                h.settle(engine)
                assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
                count = h.prober.calls.count { it == "screenshot" }
            }
            return count
        }
        val plain = forced(woven = false)
        assertEquals(2, plain, "the first step and the change from ads to tracking")
        assertEquals(plain, forced(woven = true))
    }

    @Test fun debloatRunJournalsVerifiesAndSavesTheRestorePoint() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        assertEquals(h.debloat.size, plan.steps.size)
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        val running = engine.runState(h.tv.tvId).value
        assertIs<RunState.Running>(running)
        assertEquals(NarrationState.DOING, running.narration.first().state)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value
        assertIs<RunState.Finished>(fin)
        assertEquals(RunStatus.COMPLETED, fin.status)
        assertTrue(fin.skipped.isEmpty())
        assertNull(fin.home)
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p), p.value)
        // The journal: plan, then begin/done per step in order, then end.
        val expected = listOf("plan") + plan.steps.flatMap { listOf("begin${it.index}", "done${it.index}") } + "end:COMPLETED"
        assertEquals(expected, h.types(plan))
        // Every Disable was preceded by the live dumpsys check and followed by the verify read.
        val log = h.tv.execLog.map { tv.cleanyour.testing.fixtures.Recording.normalize(it) }
        for (p in h.debloat) {
            val i = log.indexOf("pm disable-user --user 0 ${p.value}")
            assertTrue(i > 0, "${p.value} disabled")
            assertEquals("dumpsys package ${p.value}", log[i - 1])
            assertEquals("pm list packages -d", log[i + 1])
        }
        // Begin carries the exact line, the prior and a time; the PRE_PLAN snapshot was saved first.
        val begin = h.records(plan).filterIsInstance<JournalRecord.Begin>().first()
        assertEquals(plan.steps[0].rendered.lines.map { it.text }, begin.lines)
        assertEquals(Prior(packageState = EnabledState.ENABLED), begin.prior)
        assertEquals(Samples.NOW_MS, begin.atMs)
        assertEquals(SnapshotReason.PRE_PLAN, h.snapshots.latest(h.tv.tvId)?.reason)
        assertEquals(listOf("snapshot PRE_PLAN", "screenshot"), h.prober.calls.take(2))
        assertEquals(0, h.tv.holds, "the hold is released")
        assertNotNull(h.documents.bytes("tvs/${h.tv.tvId.value}/plans/${plan.id.value}.json"), "the plan was stored for resume")
        assertTrue(h.journal.openRuns(h.tv.tvId).isEmpty())
    }

    @Test fun aRefusedDisableIsSkippedNotAStop() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.refuse = { it == h.debloat[1] }
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(plan.steps[1]), fin.skipped)
        assertEquals(EnabledState.ENABLED, h.tv.state(h.debloat[1]))
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.debloat[4]))
        assertTrue("failed1" in h.types(plan) && "done2" in h.types(plan))
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single()
        assertEquals(1, failed.exit); assertTrue(failed.stderrHead.contains("Shell cannot change component state"))
    }

    @Test fun aVerifyMismatchOnAStopStepPausesThenContinueOrUndo() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        // UseStockHome from a taken-over TV: Enable(stock), SetHome, VerifyHome, PressHome; make the resolver lie once.
        h.tv.packages[h.launcher.pkg] = tv.cleanyour.core.model.PackageState(EnabledState.ENABLED, false, 1, h.signer, false, false, null, true)
        h.tv.home = h.launcher; h.tv.claimants += h.launcher
        h.tv.packages[h.stock.pkg] = h.tv.packages.getValue(h.stock.pkg).copy(enabled = EnabledState.DISABLED_USER)
        val plan = h.plan(PlanRequest.UseStockHome(h.stock))
        assertEquals(listOf("Enable", "SetHome", "VerifyHome", "PressHome"), plan.steps.map { it.op::class.simpleName })
        var lie = true
        h.tv.resolveHomeAs = { if (lie) h.launcher else it }
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value
        assertIs<RunState.Paused>(paused)
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertEquals(1, paused.at)
        assertIs<AdbError.Policy.GuardFailed>(paused.error)
        assertEquals(listOf("plan", "begin0", "done0", "begin1", "failed1", "paused:STEP_FAILED@1"), h.types(plan))
        // Continue needs the gate too (R0: 6); then the step is re-run and the run completes.
        engine.continueRun(h.tv.tvId, null)
        h.settle(engine)
        assertIs<RunState.Paused>(engine.runState(h.tv.tvId).value)
        lie = false
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals(h.stock, h.tv.home)
        assertEquals(2, h.prober.calls.count { it == "snapshot PRE_PLAN" }, "continuing re-probes for drift")
    }

    @Test fun undoRunRollsBackTheDoneStepsInReverse() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        h.tv.refuse = { it == h.debloat[2] }
        val plan = h.setupPlan(takeover = false, installLauncher = false).let { p -> p.copy(steps = p.steps.map { it.copy(onFailure = FailurePolicy.STOP_AND_ROLLBACK) }) }
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        assertEquals(PauseReason.STEP_FAILED, (engine.runState(h.tv.tvId).value as RunState.Paused).reason)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.debloat[0]))
        engine.undoRun(h.tv.tvId, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.ROLLED_BACK, fin.status)
        for (p in h.debloat) assertEquals(EnabledState.ENABLED, h.tv.state(p), p.value)
        assertEquals(listOf("plan", "begin0", "done0", "begin1", "done1", "begin2", "failed2", "paused:STEP_FAILED@2", "rollback2", "begin1", "done1", "begin0", "done0", "end:ROLLED_BACK"), h.types(plan))
        assertTrue(ChangeRecords.fromJournal(h.records(plan)).isEmpty(), "nothing left changed")
    }

    @Test fun driftSinceThePlanPausesBeforeAnyWrite() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.packages[h.debloat[3]] = h.tv.packages.getValue(h.debloat[3]).copy(enabled = EnabledState.DISABLED_USER)   // someone changed the TV by hand
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.STALE, paused.reason)
        assertEquals(listOf("${h.debloat[3].value}: was ENABLED, now DISABLED_USER"), (paused.error as AdbError.Policy.Stale).diff)
        assertEquals(listOf("plan", "paused:STALE@0"), h.types(plan))
        assertEquals(0, h.tv.execs("pm disable-user"))
        engine.abandon(h.tv.tvId)
        assertEquals(RunStatus.ABANDONED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals("end:ABANDONED", h.types(plan).last())
    }

    @Test fun theLiveDenylistCheckRefusesWhatTheTvNowSaysIsProtected() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.persistentOverride = setOf(h.debloat[0])   // the dump says PERSISTENT; the snapshot did not
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        // Issue #10: outside the home screen switch the refusal is a skip, never a pause.
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(plan.steps[0]), fin.skipped)
        assertEquals(h.debloat.size - 1, h.tv.execs("pm disable-user"), "the refused line was never sent")
        assertTrue(!h.tv.isOff(h.debloat[0]))
        assertEquals(listOf("plan", "begin0", "failed0", "begin1"), h.types(plan).take(4))
        assertEquals(DefaultEngine.PROTECTED + tv.cleanyour.core.ops.Denylist.REASON_PERSISTENT, h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error)
    }

    @Test fun stopPausesAfterTheCurrentStep() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        engine.stop(h.tv.tvId)
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.USER_STOP, paused.reason)
        assertTrue(paused.at <= 1)
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals(h.debloat.size, ChangeRecords.fromJournal(h.records(plan)).size)
    }

    @Test fun aLostConnectionPausesAndContinueResumesTheSameStep() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false, installLauncher = false)
        h.tv.dropOnLine = { it.contains(h.debloat[2].value) && it.contains("disable-user") }
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        assertEquals(2, paused.at)
        assertEquals(listOf("plan", "begin0", "done0", "begin1", "done1", "begin2", "paused:CONNECTION_LOST@2"), h.types(plan))
        engine.continueRun(h.tv.tvId, h.pass())   // still disconnected: ignored
        assertIs<RunState.Paused>(engine.runState(h.tv.tvId).value)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p))
    }

    @Test fun transfersInstallsSettleAndDebugOffDispatch() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val cfg = "{\"dock_apps\":[]}".encodeToByteArray()
        val cfgSha = tv.cleanyour.testing.fixtures.sha256Of(cfg)
        h.tv.blobs[cfgSha] = cfg
        h.tv.packages[h.launcher.pkg] = tv.cleanyour.core.model.PackageState(EnabledState.ENABLED, false, 1, h.signer, false, false, null, true)
        val theme = h.plan(PlanRequest.ApplyTheme(tv.cleanyour.core.spec.LauncherConfigDoc(), tv.cleanyour.core.model.BlobRef(cfgSha, cfg.size.toLong(), "cfg"), null))
        assertEquals(listOf("StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome"), theme.steps.map { it.op::class.simpleName })
        engine.start(theme, h.tv, theme.hash, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertTrue(h.tv.files[FileOverlayTransport.CONFIG.absolute]!!.contentEquals(cfg))
        assertEquals(cfgSha, h.transport.readDigest(h.tv))
        val log = h.tv.execLog.map { tv.cleanyour.testing.fixtures.Recording.normalize(it) }
        assertTrue(log.indexOf("push ${FileOverlayTransport.CONFIG.absolute}") < log.indexOf("sha256sum ${FileOverlayTransport.CONFIG.absolute}"))
        assertEquals(2, log.count { it.startsWith("am ") && it.contains("com.gathin.tvhome") && !it.contains("-W") })
        // Install ladder without cmd: push, sha256sum, pm install, rm; the model installs from the pushed bytes.
        val h2 = Harness(this, ScriptedTv(Samples.snapshot(facts = Samples.miTvFacts().copy(adbFeatures = setOf("shell_v2")), packages = Samples.miTvPackages(emptySet()))))
        val e2 = h2.engine()
        val plan = h2.setupPlan(takeover = false)
        assertTrue(plan.steps.any { it.op is Op.Install })
        e2.start(plan, h2.tv, plan.hash, h2.pass())
        h2.settle(e2)
        assertEquals(RunStatus.COMPLETED, (e2.runState(h2.tv.tvId).value as RunState.Finished).status)
        assertEquals(h2.apkSha, h2.tv.installed[h2.launcher.pkg])
        assertTrue(h2.tv.files.keys.none { it.endsWith(".apk") }, "the tmp apk was removed")
        assertEquals(0, h2.tv.execs("pm install -r"), "no -r on a fresh install")
        assertEquals(1, h2.tv.execs("pm install --user 0 /data/local/tmp/cleanyourtv/"))
    }

    // R1 R-5: an install is done only when the installed APK hashes to the pushed or streamed one.
    @Test fun anInstallIsCheckedAgainstTheInstalledApksHash() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan(takeover = false)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertTrue("installedSha256 ${h.launcher.pkg.value}" in h.prober.calls)
        // The TV installed something else under the same package and versionCode.
        val h2 = Harness(this)
        h2.prober.installedShaOverride = Sha256("e".repeat(64))
        val e2 = h2.engine()
        val p2 = h2.setupPlan(takeover = false)
        e2.start(p2, h2.tv, p2.hash, h2.pass())
        h2.settle(e2)
        val fin = e2.runState(h2.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertTrue(fin.skipped.any { it.op is Op.InstallStream })
        val install = p2.steps.first { it.op is Op.InstallStream }.index
        val failed = h2.records(p2).filterIsInstance<JournalRecord.Failed>().single { it.i == install }
        assertTrue(failed.error.contains("installed apk sha256"), failed.error)
    }

    // R1 R-7: debug_off closes the socket, so its step is journaled done but not verified.
    @Test fun aSessionEndStepThatLosesTheSocketIsNotVerified() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val off = h.plan(PlanRequest.DebugOff)
        h.tv.dropOnLine = { it.contains("adb_enabled") }
        engine.start(off, h.tv, off.hash, null)
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        val done = h.records(off).filterIsInstance<JournalRecord.Done>().single { it.i == off.steps.last().index }
        assertTrue(!done.verified, "nothing was read back")
        // When the socket stays up the step is verified like any other.
        h.tv.dropOnLine = null; h.tv.reconnect()
        val again = h.plan(PlanRequest.DebugOff)
        engine.start(again, h.tv, again.hash, null)
        h.settle(engine)
        assertTrue(h.records(again).filterIsInstance<JournalRecord.Done>().all { it.verified })
    }

    // R1 R-13: the executor refuses to switch off the TV's current HOME or any HOME claimant outside
    // the open guarded section, even when the plan was made before HOME changed (a skip since 0.3.3).
    @Test fun theLiveCheckRefusesToSwitchOffAHomeScreenOutsideTheSection() = runTest(timeout = TEST_TIMEOUT) {
        for (case in listOf("resolved", "claimant")) {
            val h = Harness(this)
            val engine = h.engine()
            val target = if (case == "resolved") h.stock.pkg else h.debloat[0]
            if (case == "claimant") h.tv.claimants += tv.cleanyour.core.model.Component(target, ".Home")
            val plan = h.opsPlan(Op.Disable(target))
            engine.start(plan, h.tv, plan.hash, h.pass())
            h.settle(engine)
            // Issue #10: refused and skipped, never a pause that Carry on would meet again.
            val fin = engine.runState(h.tv.tvId).value as RunState.Finished
            assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status, case)
            assertEquals(plan.steps, fin.skipped, case)
            assertEquals(0, h.tv.execs("pm disable-user"), "$case: the line was never sent")
            val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single()
            assertTrue(failed.error.contains("home screen"), failed.error)
            assertTrue(!h.tv.isOff(target), case)
        }
    }

    // Upstream #421 (launcher v2.5.0's disable step): a package that asks for TV_INPUT_HARDWARE drives
    // the HDMI, AV and tuner inputs even when no input is registered for it, so the live check refuses
    // to switch it off; a package that only holds it through others' lines, or puts one back, is fine.
    // RF-LAUNCHER-250 F1: a switch-off of a package that asks for TV_INPUT_HARDWARE is kept and the
    // step skipped, never a pause: the rest of the plan runs and the run ends with skips. The journal
    // says why, so Done and Manage can name it.
    @Test fun theLiveCheckRefusesToSwitchOffATvInputDriver() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val driver = h.debloat[0]
        val other = h.debloat[1]
        h.tv.requestedPermissions = mapOf(driver to listOf("android.permission.INTERNET", "android.permission.TV_INPUT_HARDWARE"))
        val plan = h.plan(PlanRequest.SwitchOff(setOf(driver, other)))   // Manage's switch-off, as planned
        val step = plan.steps.single { it.op == Op.Disable(driver) }
        assertEquals(FailurePolicy.SKIP_IF_REFUSED, step.onFailure)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(step), fin.skipped)
        assertEquals(1, h.tv.execs("pm disable-user"), "only the other package's line was sent")
        val failed = h.records(plan).filterIsInstance<JournalRecord.Failed>().single()
        assertEquals(DefaultEngine.KEPT_TV_INPUT, failed.error)
        assertTrue(!h.tv.isOff(driver))
        assertTrue(h.tv.isOff(other))
        // Putting a package back is never refused.
        h.tv.packages[driver] = h.tv.packages.getValue(driver).copy(enabled = EnabledState.DISABLED_USER)
        val back = h.opsPlan(Op.Enable(driver))
        engine.start(back, h.tv, back.hash, h.pass())
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertTrue(!h.tv.isOff(driver))
    }

    // RF-LAUNCHER-250 F3: "Stop here" on a run cut off inside the accessibility restore (upstream
    // #424: clear the list, accessibility off, write the list back, accessibility on) first runs the
    // rest of it, so the TV is never left with no accessibility services and accessibility off.
    @Test fun stopHereFinishesAnAccessibilityRestoreFirst() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val list = "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService:com.google.android.marvin.talkback/.TalkBackService"
        h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY] = list
        h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED] = "1"
        val engine = h.engine()
        val plan = h.opsPlan(Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"),
            Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, list), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"))
        // The connection drops as the list is written back: the TV has no services and accessibility off.
        h.tv.dropOnLine = { it.contains("'put'") && it.contains("'enabled_accessibility_services'") }
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        assertEquals(PauseReason.CONNECTION_LOST, (engine.runState(h.tv.tvId).value as RunState.Paused).reason)
        assertNull(h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("0", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
        // Still offline: Stop here waits for the TV.
        engine.abandon(h.tv.tvId); h.settle(engine)
        assertIs<RunState.Paused>(engine.runState(h.tv.tvId).value)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.abandon(h.tv.tvId); h.settle(engine)
        assertEquals(RunStatus.ABANDONED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals(list, h.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
        assertEquals("1", h.tv.settings[SettingKey.ACCESSIBILITY_ENABLED])
        assertEquals("end:ABANDONED", h.types(plan).last())
        // A run stopped before the restore began is given up at once, as before.
        val h2 = Harness(this)
        h2.tv.settings[SettingKey.ENABLED_ACCESSIBILITY] = list
        h2.tv.settings[SettingKey.ACCESSIBILITY_ENABLED] = "1"
        val e2 = h2.engine()
        val p2 = h2.opsPlan(Op.Disable(h2.debloat[0]), Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null),
            Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Op.Settle(1), Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, list), Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"))
        h2.tv.dropOnLine = { it.contains("'disable-user'") }
        e2.start(p2, h2.tv, p2.hash, h2.pass())
        h2.settle(e2)
        h2.tv.dropOnLine = null; h2.tv.reconnect()
        e2.abandon(h2.tv.tvId); h2.settle(e2)
        assertEquals(RunStatus.ABANDONED, (e2.runState(h2.tv.tvId).value as RunState.Finished).status)
        assertEquals(0, h2.tv.execs("settings put"), "nothing of the restore was run")
        assertEquals(list, h2.tv.settings[SettingKey.ENABLED_ACCESSIBILITY])
    }

    @Test fun onlyTheRequestedPermissionsBlockCounts() {
        fun dump(vararg lines: String) = "Packages:\n  Package [com.example.tv] (1a2b3c):\n" + lines.joinToString("") { "    $it\n" }
        assertTrue(PackageDump.requestsTvInputHardware(dump("requested permissions:", "  android.permission.TV_INPUT_HARDWARE", "install permissions:")))
        assertTrue(PackageDump.requestsTvInputHardware(dump("requested permissions:", "  android.permission.TV_INPUT_HARDWARE: restricted=true")))
        // Granted through a shared user, or printed in another block: not this package's own request.
        assertTrue(!PackageDump.requestsTvInputHardware(dump("requested permissions:", "  android.permission.INTERNET", "install permissions:", "  android.permission.TV_INPUT_HARDWARE: granted=true")))
        assertTrue(!PackageDump.requestsTvInputHardware(dump("requested permissions:", "User 0: installed=true", "  android.permission.TV_INPUT_HARDWARE")))
        assertTrue(!PackageDump.requestsTvInputHardware(dump("requested permissions:", "  android.permission.TV_INPUT_HARDWARE_EXTRA")))
        assertTrue(!PackageDump.requestsTvInputHardware(""))
    }

    // RFC-W8-5: the restart test is a plan of its own, journaled, and needs no credential pass.
    @Test fun aRebootPlanIsJournaledAndNotGated() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val reboot = h.plan(PlanRequest.Reboot)
        assertEquals(tv.cleanyour.core.planner.PlanKind.REBOOT, reboot.kind)
        assertEquals(listOf<Op>(Op.Reboot), reboot.steps.map { it.op })
        assertEquals(StartResult.Started, engine.start(reboot, h.tv, reboot.hash, null))
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals(listOf("plan", "begin0", "done0", "end:COMPLETED"), h.types(reboot))
        assertEquals(1, h.tv.execs("reboot"))
        // The exemption is for a plan of Reboot steps only: a write plan relabelled REBOOT is still gated.
        val setup = h.setupPlan(takeover = false, installLauncher = false)
        val relabelled = setup.copy(kind = tv.cleanyour.core.planner.PlanKind.REBOOT)
        assertEquals(StartResult.Refused(StartRefusal.GATE_REQUIRED), engine.start(relabelled, h.tv, relabelled.hash, null))
    }
}
