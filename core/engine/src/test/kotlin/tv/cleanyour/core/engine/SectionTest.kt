package tv.cleanyour.core.engine

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.ops.Watchdog
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.session.ExecResult
import tv.cleanyour.core.session.InstallOutcome
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Section 3.4 end to end on the scripted TV: the watchdog armed, the stock launcher off, HOME set
// and verified, the check screen, then the user's answer, the timer, the live focus check, a
// verify failure, a lost connection and the resume that runs step R.
class SectionTest {

    private suspend fun TestScope.openCheckScreen(h: Harness, engine: Engine, plan: Plan): RunState.AwaitingConfirm {
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        advanceTimeBy(20_000); runCurrent()
        val s = engine.runState(h.tv.tvId).value
        assertIs<RunState.AwaitingConfirm>(s, "state after the section's last step")
        return s
    }

    private fun Harness.assertStockHomeIsBack(token: String) {
        assertEquals(stock, tv.home)
        assertEquals(EnabledState.ENABLED, tv.state(stock.pkg))
        assertTrue(tv.watchdogs.isEmpty(), "the watchdog was killed")
        assertTrue(tv.files.containsKey("/data/local/tmp/cleanyourtv/${Watchdog.TOKEN_FILE_PREFIX}$token"), "the token file defuses it too")
        for (p in debloat) assertEquals(EnabledState.DISABLED_USER, tv.state(p), "the debloat part is kept")
    }

    @Test fun yesISeeItConfirmsTheSection() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        val s = openCheckScreen(h, engine, plan)
        assertEquals(section, s.section)
        assertEquals(h.clock.nowMs() - 20_000 + 15_000 + 90_000, s.deadlineMs, "90 s from the section's last step, with a thumbnail available (D23)")
        assertNotNull(s.thumbnail)
        assertNull(s.homeCheck, "no HOME key pressed yet")
        // The TV in the meantime: our launcher is HOME, the stock one off, the watchdog alive.
        assertEquals(h.launcher, h.tv.home)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.stock.pkg))
        assertTrue(section.token.value in h.tv.watchdogs)
        // The user presses Home on the remote: the live check sees our launcher.
        h.tv.focus = h.launcher
        advanceTimeBy(2_500); runCurrent()
        assertEquals(true, (engine.runState(h.tv.tvId).value as RunState.AwaitingConfirm).homeCheck)
        assertTrue(h.prober.calls.count { it == "homeFocus" } >= 3, "the 2 s live check")
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value
        assertIs<RunState.Finished>(fin)
        assertEquals(RunStatus.COMPLETED, fin.status)
        assertNull(fin.home)
        assertEquals(h.launcher, h.tv.home)
        assertTrue(h.tv.watchdogs.isEmpty(), "pkill ran")
        assertTrue(h.tv.files.containsKey("/data/local/tmp/cleanyourtv/home-ok-${section.token.value}"), "the token file was written")
        val types = h.types(plan)
        assertEquals(listOf("section:ARMED", "section:CONFIRMED"), types.filter { it.startsWith("section") })
        assertTrue(types.indexOf("section:ARMED") < types.indexOf("begin${section.from}"), "the token is in the journal before the watchdog is armed")
        assertEquals("end:COMPLETED", types.last())
        val armed = h.records(plan).filterIsInstance<JournalRecord.Section>().first()
        assertEquals(section.token, armed.token)
        assertTrue(armed.deadlineWallMs >= Samples.NOW_MS + 15_000 + 120_000, "the TV watchdog fires 150 s after arming")
        assertTrue(armed.deadlineWallMs >= s.deadlineMs + DefaultEngine.WATCHDOG_MARGIN_MS, "the phone's window ends first (R1 R-1)")
    }

    // RF-LAUNCHER-250 F2: the TV input rule holds inside the takeover too, as upstream's disable.sh
    // applies it to the old launcher. A stock launcher that asks for TV_INPUT_HARDWARE is kept enabled:
    // its switch-off is skipped, the section goes on, SetHome makes ours the default, and the run
    // ends with that one skip.
    @Test fun aStockLauncherThatRunsTheInputsStaysOnAndTheSwitchGoesOn() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.requestedPermissions = mapOf(h.stock.pkg to listOf("android.permission.TV_INPUT_HARDWARE"))
        val engine = h.engine()
        val plan = h.setupPlan()
        val off = plan.steps.single { it.op == Op.Disable(h.stock.pkg) }
        val section = plan.sections.single()
        assertTrue(off.index in section.from..section.to, "the stock launcher's switch-off is inside the section")
        openCheckScreen(h, engine, plan)
        assertEquals(EnabledState.ENABLED, h.tv.state(h.stock.pkg), "never switched off")
        assertTrue(h.tv.execLog.none { it.contains("disable-user") && it.contains(h.stock.pkg.value) })
        assertEquals(h.launcher, h.tv.home)
        h.tv.focus = h.launcher
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        val fin = assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(listOf(off), fin.skipped)
        assertEquals(h.launcher, h.tv.home)
        assertEquals(EnabledState.ENABLED, h.tv.state(h.stock.pkg))
        assertEquals(DefaultEngine.KEPT_TV_INPUT, h.records(plan).filterIsInstance<JournalRecord.Failed>().single().error)
    }

    @Test fun noPutItBackRunsStepR() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        openCheckScreen(h, engine, plan)
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.USER_SAID_NO, fin.home)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(plan.steps.drop(section.to + 1), fin.skipped, "everything after the section is skipped")
        val types = h.types(plan)
        val r = types.indexOf("rollback${section.from}")
        assertTrue(r > 0)
        assertEquals(listOf("begin-1", "done-1", "begin-1", "done-1", "begin${section.from + 1}", "done${section.from + 1}", "begin${section.to - 1}", "done${section.to - 1}", "begin-1", "done-1", "begin-1", "done-1", "section:ROLLED_BACK", "end:COMPLETED_WITH_SKIPS"), types.drop(r + 1))
        // Change records: the debloat stays, the section's changes were undone.
        val changes = ChangeRecords.fromJournal(h.records(plan))
        assertEquals(h.debloat.size + 1, changes.size, "five disables and the install")
        assertTrue(changes.none { it.op is Op.SetHome || (it.op as? Op.Disable)?.pkg == h.stock.pkg })
        assertTrue(h.tv.execLog.map { tv.cleanyour.testing.fixtures.Recording.normalize(it) }.last() == "input keyevent 3")
    }

    @Test fun theTimerRollsBack() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        advanceTimeBy(60_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        advanceTimeBy(40_000); runCurrent()
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.TIMED_OUT, fin.home)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
    }

    // QA of 0.4.5 (bug-040-retry): the check timed out, the old home screen went back, and the run
    // ended with the TV curtain still installed and running, because its removal (the last step)
    // was skipped with the rest. It is removed now, and the old home screen is still back.
    @Test fun theTimerRollsBackAndTheCurtainIsRemoved() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val curtainPkg = PackageName("tv.cleanyour.curtain")
        val bytes = "fake curtain apk".encodeToByteArray()
        val sha = h.tv.acceptApk(bytes, curtainPkg, 1, h.signer)
        val curtain = tv.cleanyour.core.model.LauncherArtifact(curtainPkg, 1, "0.1", sha, h.signer, tv.cleanyour.core.model.BlobRef(sha, bytes.size.toLong(), "bundled:curtain.apk"))
        val plan = h.plan(tv.cleanyour.core.planner.PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.Exact(h.profile()),
            tv.cleanyour.core.planner.Choices(emptySet(), emptySet(), null, null, true, true, false, false, wizardWifi = tv.cleanyour.core.planner.WizardWifi.NOT_OWNER),
            h.artifact, tv.cleanyour.core.model.Level.SIMPLE, curtain = curtain))
        val removal = plan.steps.last()
        assertEquals(Op.Uninstall(curtainPkg), removal.op)
        assertTrue(removal.index > plan.sections.single().to, "the removal comes after the home screen switch")
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        var s = engine.runState(h.tv.tvId).value
        var guard = 0
        while (s !is RunState.AwaitingConfirm) { advanceTimeBy(1_000); runCurrent(); s = engine.runState(h.tv.tvId).value; check(guard++ < 600) { "no check screen: $s" } }
        assertTrue(curtainPkg in h.tv.packages, "the curtain is on the TV during the check")
        advanceTimeBy(130_000); runCurrent()
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.TIMED_OUT, fin.home)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        assertTrue(curtainPkg !in h.tv.packages, "the curtain is not left on the TV")
        assertTrue(removal !in fin.skipped)
        val types = h.types(plan)
        assertTrue("done${removal.index}" in types, types.toString())
        assertEquals("end:COMPLETED_WITH_SKIPS", types.last())
    }

    @Test fun withoutARecentThumbnailTheWindowIs120s() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.screenshotOkCount = 5   // through the section's screencap precondition; the check screen's fetch fails
        val engine = h.engine()
        val plan = h.setupPlan()
        val s = openCheckScreen(h, engine, plan)
        assertNotNull(s.thumbnail, "the last good one is still shown")
        assertTrue(h.prober.calls.count { it == "screenshot" } >= 6)
        assertEquals(h.clock.nowMs() - 20_000 + 15_000 + 120_000, s.deadlineMs)
        advanceTimeBy(95_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        advanceTimeBy(30_000); runCurrent(); h.settle(engine)
        assertEquals(HomeOutcome.TIMED_OUT, (engine.runState(h.tv.tvId).value as RunState.Finished).home)
    }

    // S10: the launcher crashes on HOME, so the HOME key lands on FallbackHome. The stock launcher
    // is HOME again without any user action.
    @Test fun aCrashingLauncherIsCaughtByTheLiveCheck() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.focus = Harness.fallbackHome
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.WRONG_FOCUS, fin.home)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        assertTrue(h.clock.monotonicMs() < 40_000, "rolled back at once, not at the deadline")
    }

    // The resolver (HOME key with no default, package android, R1 R-8) rolls back like the others.
    @Test fun theWizardTheStockLauncherAndTheResolverFocusRollBackToo() = runTest(timeout = TEST_TIMEOUT) {
        val resolver = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
        for (focus in listOf(Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity"), Samples.stockLauncher, resolver)) {
            val h = Harness(this)
            val engine = h.engine()
            val plan = h.setupPlan()
            openCheckScreen(h, engine, plan)
            h.tv.focus = focus
            advanceTimeBy(2_500); runCurrent(); h.settle(engine)
            assertEquals(HomeOutcome.WRONG_FOCUS, (engine.runState(h.tv.tvId).value as RunState.Finished).home, focus.flat)
        }
    }

    @Test fun verifyHomeFailureRollsBackAtOnce() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val wizard = Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity")
        h.tv.resolveHomeAs = { if (it == h.launcher) wizard else it }   // the wizard's RecoveryActivity wins HOME (issue #49)
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        val types = h.types(plan)
        val to = plan.sections.single().to
        assertTrue(("failed$to" in types || "failed${to - 1}" in types) && "section:ROLLED_BACK" in types)
        assertTrue(plan.steps[to + 1] in fin.skipped && plan.steps[to + 2] in fin.skipped)
        assertEquals(0, h.prober.calls.count { it == "homeFocus" }, "the check screen never opened")
    }

    @Test fun theWizardQuirkDisablesAndRestoresTheWizard() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val quirky = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard)
        h.profiles = tv.cleanyour.testing.fixtures.FakeProfileSource(listOf(quirky))   // the executor's live check must see the same profile
        val engine = h.engine()
        val plan = h.setupPlan(profile = quirky)
        assertTrue(plan.steps.any { it.op == Op.Disable(h.wizard) })
        openCheckScreen(h, engine, plan)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.wizard))
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        assertEquals(EnabledState.ENABLED, h.tv.state(h.wizard))
        assertEquals(h.stock, h.tv.home)
    }

    // Launcher v2.4.0's precondition (RF-LAUNCHER-240 F3): `am start -W` must say "Status: ok"
    // before anything of the switch happens; when it does not, the run ends with the switch skipped
    // and the TV's own home screen in charge, as for a launcher that is not installed.
    @Test fun aLauncherThatDoesNotStartSkipsTheSwitch() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.startFails = true
        val engine = h.engine()
        val plan = h.setupPlan()
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        val start = plan.steps.first { it.op == Op.StartActivity(h.launcher, wait = true) }
        assertTrue(start in fin.skipped && fin.skipped.any { it.op is Op.ArmHomeWatchdog })
        assertTrue(h.tv.watchdogs.isEmpty() && h.tv.home == h.stock && h.tv.state(h.stock.pkg) == EnabledState.ENABLED)
        assertTrue(h.types(plan).none { it.startsWith("section") })
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p))
    }

    @Test fun aFailedInstallSkipsTheSectionWithoutArmingAnything() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.installOutcome = InstallOutcome.Failed("INSTALL_FAILED_INSUFFICIENT_STORAGE", "no space")
        val engine = h.engine()
        val plan = h.setupPlan()
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        assertTrue(h.tv.watchdogs.isEmpty() && h.tv.home == h.stock && h.tv.state(h.stock.pkg) == EnabledState.ENABLED)
        assertTrue(h.types(plan).none { it.startsWith("section") })
        assertTrue(fin.skipped.any { it.op is Op.InstallStream } && fin.skipped.any { it.op is Op.ArmHomeWatchdog })
        assertTrue(fin.skipped.any { it.op == Op.StartActivity(h.launcher, wait = true) }, "a launcher that is not there does not start")
        for (p in h.debloat) assertEquals(EnabledState.DISABLED_USER, h.tv.state(p))
    }

    @Test fun aLostConnectionInsideTheSectionIsRolledBackByResume() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        h.tv.dropOnLine = { it.contains("resolve-activity") }   // the TV vanishes right after set-home-activity
        engine.start(plan, h.tv, plan.hash, h.pass())
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        assertEquals(section.to - 1, paused.at, "SetHome's verify read hit the dead socket")
        assertEquals(h.launcher, h.tv.home, "the TV is left with the new home screen and the watchdog running")
        assertTrue(section.token.value in h.tv.watchdogs)
        assertEquals(listOf("section:ARMED"), h.types(plan).filter { it.startsWith("section") })
        // The app comes back (a new engine, the journal and the plan file survive), reconnects, resumes.
        h.tv.dropOnLine = null; h.tv.reconnect()
        val engine2 = h.engine()
        assertEquals(listOf(plan.id), h.journal.openRuns(h.tv.tvId))
        engine2.resume(h.tv.tvId, h.tv)
        val after = engine2.runState(h.tv.tvId).value
        assertIs<RunState.Paused>(after)
        assertEquals(PauseReason.CONNECTION_LOST, after.reason)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
        // The in-doubt VerifyHome step was re-verified and found not to hold (the stock launcher is back).
        assertTrue(h.types(plan).none { it.endsWith("r") && it.startsWith("done") })
        // Carrying on does not re-enter the section: the run ends and a new plan is needed.
        engine2.continueRun(h.tv.tvId, h.pass())
        h.settle(engine2)
        val fin = engine2.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.CONNECTION_LOST, fin.home)
        assertEquals("end:COMPLETED_WITH_SKIPS", h.types(plan).last())
        assertTrue(h.journal.openRuns(h.tv.tvId).isEmpty())
        // RUN_OPEN held while the run was open, and a start after the end is allowed again.
        val again = h.setupPlan()
        assertEquals(StartResult.Started, engine2.start(again, h.tv, again.hash, h.pass()))
    }

    @Test fun continueAfterALostConnectionInTheCheckScreenRunsStepRFirst() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.dropOnLine = { it.contains("'dumpsys' 'activity'") }   // the live check hits a dead socket
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value as RunState.Paused
        assertEquals(PauseReason.CONNECTION_LOST, paused.reason)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(HomeOutcome.CONNECTION_LOST, fin.home)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
    }

    // R1: step R that cannot put the TV's own home screen back is surfaced, never recorded as done.
    @Test fun aFailingStepRPausesWithTheErrorAndIsRetried() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        openCheckScreen(h, engine, plan)
        h.tv.refuse = { it == h.stock.pkg }   // pm enable of the stock launcher now fails
        engine.rejectHome(h.tv.tvId)
        h.settle(engine)
        val paused = engine.runState(h.tv.tvId).value
        assertIs<RunState.Paused>(paused)
        assertEquals(PauseReason.STEP_FAILED, paused.reason)
        assertIs<tv.cleanyour.core.model.AdbError.NonZeroExit>(paused.error)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.stock.pkg))
        assertEquals(listOf("section:ARMED"), h.types(plan).filter { it.startsWith("section") }, "not ROLLED_BACK")
        // The TV lets the enable through this time: continue runs step R again and the run ends.
        h.tv.refuse = { false }
        engine.continueRun(h.tv.tvId, h.pass())
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
    }

    // Issue #2 (A3): undo is state-based. The TV refuses the switch-off of its own home screen and
    // then the pm enable of step R too, though the package was never off and Home is still its own.
    // The enable's target holds, so it is journaled done and verified, VerifyHome passes, and the
    // section closes ROLLED_BACK: the run ends with the switch skipped, never a pause.
    @Test fun aRefusedDisableAndARefusedEnableStillRollBackWhenHomeIsStock() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.tv.refuse = { it == h.stock.pkg }   // a protected package: pm disable-user and pm enable both refused
        val engine = h.engine()
        val plan = h.setupPlan()
        val section = plan.sections.single()
        val off = plan.steps.single { it.op == Op.Disable(h.stock.pkg) }
        assertEquals(StartResult.Started, engine.start(plan, h.tv, plan.hash, h.pass()))
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(RunStatus.COMPLETED_WITH_SKIPS, fin.status)
        assertEquals(HomeOutcome.VERIFY_FAILED, fin.home)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(1, h.tv.execs("pm enable --user 0 ${h.stock.pkg.value}"), "step R ran once")
        val types = h.types(plan)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), types.filter { it.startsWith("section") })
        assertTrue(types.none { it.startsWith("paused") }, "$types")
        // The refused enable is journaled done and verified: its target holds.
        val afterRollback = types.drop(types.indexOf("rollback${section.from}"))
        assertTrue(afterRollback.containsAll(listOf("begin${off.index}", "done${off.index}")) && "failed${off.index}" !in afterRollback, "$afterRollback")
        val done = h.records(plan).filterIsInstance<JournalRecord.Done>().last { it.i == off.index }
        assertTrue(done.verified)
        assertEquals(1, done.exit, "the refusal's exit code is kept")
        assertTrue(ChangeRecords.fromJournal(h.records(plan)).none { it.op is Op.SetHome || (it.op as? Op.Disable)?.pkg == h.stock.pkg })
    }

    // A3: once VerifyHome shows the TV's own home screen, a step R enable that was refused and whose
    // package really stays off (here the setup wizard) is a skip, its Failed record kept: the section
    // still closes ROLLED_BACK.
    @Test fun aRefusedEnableIsASkipOnceTheStockHomeIsVerified() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val quirky = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard)
        h.profiles = tv.cleanyour.testing.fixtures.FakeProfileSource(listOf(quirky))
        val engine = h.engine()
        val plan = h.setupPlan(profile = quirky)
        val section = plan.sections.single()
        val wizardOff = plan.steps.single { it.op == Op.Disable(h.wizard) }
        openCheckScreen(h, engine, plan)
        h.tv.refuse = { it == h.wizard }
        engine.rejectHome(h.tv.tvId)
        val fin = assertIs<RunState.Finished>(h.settle(engine))
        assertEquals(HomeOutcome.USER_SAID_NO, fin.home)
        h.assertStockHomeIsBack(section.token.value)
        assertEquals(EnabledState.DISABLED_USER, h.tv.state(h.wizard), "the TV kept it off")
        val types = h.types(plan)
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), types.filter { it.startsWith("section") })
        assertTrue("failed${wizardOff.index}" in types.drop(types.indexOf("rollback${section.from}")), "the refusal is journaled as a skip")
        assertTrue(types.none { it.startsWith("paused") })
    }

    // S2: a step R read that errors or prints nothing is no evidence. The TV refuses the wizard's pm
    // enable, and the read that would show it back on answers with an error, with nothing, or with
    // exit 0 and no such package: the refusal stays a Failed record, the wizard's switch-off stays a
    // change record (so Manage still offers to put it back), and the section still closes once the
    // stock home is verified.
    @Test fun aStepRReadThatErrorsOrPrintsNothingNeverHolds() = runTest(timeout = TEST_TIMEOUT) {
        val answers = listOf(
            ExecResult(1, "", "Error: dumpsys failed", false, 1),
            ExecResult(255, "Package [com.google.android.tungsten.setupwraith]\n    User 0: enabled=1\n", "Error", false, 1),
            ExecResult(0, "", "", false, 1),
        )
        for (bad in answers) {
            val h = Harness(this)
            val quirky = h.profile(setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), h.wizard)
            h.profiles = tv.cleanyour.testing.fixtures.FakeProfileSource(listOf(quirky))
            val engine = h.engine()
            val plan = h.setupPlan(profile = quirky)
            val section = plan.sections.single()
            val wizardOff = plan.steps.single { it.op == Op.Disable(h.wizard) }
            openCheckScreen(h, engine, plan)
            h.tv.refuse = { it == h.wizard }
            // Both reads a step R check could use answer badly once step R runs.
            h.tv.answer = { line -> if (line == "dumpsys package ${h.wizard.value}" || line == "pm list packages -d") bad else null }
            engine.rejectHome(h.tv.tvId)
            val fin = assertIs<RunState.Finished>(h.settle(engine), "$bad")
            assertEquals(HomeOutcome.USER_SAID_NO, fin.home)
            val records = h.records(plan)
            val afterRollback = records.drop(records.indexOfFirst { it is JournalRecord.Rollback })
            assertTrue(afterRollback.any { it is JournalRecord.Failed && it.i == wizardOff.index }, "$bad: the refusal is kept")
            assertTrue(afterRollback.none { it is JournalRecord.Done && it.i == wizardOff.index }, "$bad: never journaled as put back")
            assertTrue(ChangeRecords.fromJournal(records).any { it.op == Op.Disable(h.wizard) }, "$bad: the switch-off is still a change")
            assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
        }
    }

    // S2: SetHome refused, and the HOME resolver answers with an error (though it prints the stock
    // launcher) or with nothing: that never holds, so the TV's own home screen is not taken as back
    // and the run pauses with the section open.
    @Test fun aRefusedSetHomeWithAFailedOrEmptyReadPauses() = runTest(timeout = TEST_TIMEOUT) {
        for (bad in listOf(ExecResult(1, "${Samples.stockLauncher.flat}\n", "Error", false, 1), ExecResult(0, "", "", false, 1))) {
            val h = Harness(this)
            val engine = h.engine()
            val plan = h.setupPlan()
            openCheckScreen(h, engine, plan)
            h.tv.answer = { line ->
                when {
                    line.startsWith("cmd package set-home-activity --user 0 ${h.stock.flat}") -> ExecResult(1, "", "Error: refused", false, 1)
                    line.startsWith("cmd package resolve-activity") -> bad
                    else -> null
                }
            }
            engine.rejectHome(h.tv.tvId)
            val paused = assertIs<RunState.Paused>(h.settle(engine), "$bad")
            assertEquals(PauseReason.STEP_FAILED, paused.reason)
            assertEquals(listOf("section:ARMED"), h.types(plan).filter { it.startsWith("section") }, "$bad: not ROLLED_BACK")
        }
    }

    // S2: KillWatchdog's check is pgrep, whose "no such process" is exit 1 with no output; an error
    // (another exit, or stderr) is no evidence, so the refused pkill stays a Failed record. It is not a
    // must-succeed op, so the section still closes.
    @Test fun aKillWatchdogCheckThatErrorsIsAFailedRecord() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.answer = { line ->
            when {
                line.startsWith("pkill -f ") -> ExecResult(2, "", "pkill: bad pattern", false, 1)
                line.startsWith("pgrep -f ") -> ExecResult(2, "", "pgrep: bad pattern", false, 1)
                else -> null
            }
        }
        engine.rejectHome(h.tv.tvId)
        assertIs<RunState.Finished>(h.settle(engine))
        val records = h.records(plan)
        val afterRollback = records.drop(records.indexOfFirst { it is JournalRecord.Rollback })
        val kill = afterRollback.indexOfFirst { it is JournalRecord.Begin && it.op is Op.KillWatchdog }
        assertTrue(kill >= 0 && afterRollback[kill + 1] is JournalRecord.Failed, "$afterRollback")
        assertEquals(listOf("section:ARMED", "section:ROLLED_BACK"), h.types(plan).filter { it.startsWith("section") })
    }

    // R1: giving up a paused run with an open section puts the TV's own home screen back first, and
    // needs the TV for that.
    @Test fun abandonWithAnOpenSectionRunsStepRFirst() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        h.tv.dropOnLine = { it.contains("'dumpsys' 'activity'") }
        advanceTimeBy(2_500); runCurrent(); h.settle(engine)
        assertEquals(PauseReason.CONNECTION_LOST, (engine.runState(h.tv.tvId).value as RunState.Paused).reason)
        engine.abandon(h.tv.tvId)   // disconnected: nothing can be put back yet, so the run stays paused
        h.settle(engine)
        assertIs<RunState.Paused>(engine.runState(h.tv.tvId).value)
        assertEquals(h.launcher, h.tv.home)
        h.tv.dropOnLine = null; h.tv.reconnect()
        engine.abandon(h.tv.tvId)
        h.settle(engine)
        val fin = engine.runState(h.tv.tvId).value as RunState.Finished
        assertEquals(RunStatus.ABANDONED, fin.status)
        h.assertStockHomeIsBack(plan.sections.single().token.value)
        assertEquals("end:ABANDONED", h.types(plan).last())
    }

    // R1: a plan runs once; a second start of the same plan (same run token) is refused.
    @Test fun aPlanRunsOnce() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        val engine = h.engine()
        val plan = h.setupPlan()
        openCheckScreen(h, engine, plan)
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value)
        assertEquals(StartResult.Refused(StartRefusal.RUN_OPEN), engine.start(plan, h.tv, plan.hash, h.pass()))
        val again = h.setupPlan()
        assertTrue(again.id != plan.id)
    }

    // R1 R-1: without a thumbnail the phone waits the full 120 s of D23, and the watchdog (150 s from
    // arming) still sleeps at least 10 s longer, so a late "Yes" is never written after the TV has
    // already put its own home screen back.
    @Test fun withoutAThumbnailThePhoneDeadlineIsEarlierThanTheWatchdogs() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.screenshotOkCount = 5
        val engine = h.engine()
        val plan = h.setupPlan()
        val s = openCheckScreen(h, engine, plan)
        assertEquals(h.clock.nowMs() - 20_000 + 15_000 + 120_000, s.deadlineMs, "the user-facing 120 s is kept")
        val arm = plan.steps[plan.sections.single().from].op as Op.ArmHomeWatchdog
        assertEquals(150, arm.seconds)
        val armed = h.records(plan).filterIsInstance<JournalRecord.Section>().single()
        assertTrue(s.deadlineMs <= armed.deadlineWallMs - DefaultEngine.WATCHDOG_MARGIN_MS, "phone ${s.deadlineMs}, watchdog ${armed.deadlineWallMs}")
        // A "Yes" one second before the phone's deadline: the watchdog is still asleep, so it is defused.
        advanceTimeBy(s.deadlineMs - h.clock.nowMs() - 1_000); runCurrent()
        assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value)
        assertTrue(h.clock.nowMs() < armed.deadlineWallMs - DefaultEngine.WATCHDOG_MARGIN_MS)
        engine.confirmHome(h.tv.tvId)
        h.settle(engine)
        assertEquals(RunStatus.COMPLETED, (engine.runState(h.tv.tvId).value as RunState.Finished).status)
        assertEquals(h.launcher, h.tv.home)
    }

    // R1 R-1, the engine's guard: a watchdog armed for less than the window plus the section's steps
    // (here the old 120 s) caps the phone's deadline at the watchdog's minus 10 s.
    @Test fun aShortWatchdogCapsThePhoneDeadline() = runTest(timeout = TEST_TIMEOUT) {
        val h = Harness(this)
        h.prober.screenshotOkCount = 5
        val engine = h.engine()
        val short = tv.cleanyour.core.planner.DefaultPlanner(h.clock, h.idGen, h.renderer, h.transport, "0.1.0", watchdogSeconds = 120)
        val plan = h.setupPlan(planner = short)
        val s = openCheckScreen(h, engine, plan)
        val armed = h.records(plan).filterIsInstance<JournalRecord.Section>().single()
        assertEquals(armed.deadlineWallMs - DefaultEngine.WATCHDOG_MARGIN_MS, s.deadlineMs)
        assertTrue(s.deadlineMs < h.clock.nowMs() - 20_000 + 15_000 + 120_000, "shorter than the 120 s it would otherwise be")
        advanceTimeBy(s.deadlineMs - h.clock.nowMs() + 1_000); runCurrent(); h.settle(engine)
        assertEquals(HomeOutcome.TIMED_OUT, (engine.runState(h.tv.tvId).value as RunState.Finished).home)
        assertTrue(h.clock.nowMs() < armed.deadlineWallMs, "the phone's step R ran before the watchdog would fire")
        h.assertStockHomeIsBack(plan.sections.single().token.value)
    }
}
