package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.discovery.ServiceRef
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.NarrationLine
import tv.cleanyour.core.engine.NarrationState
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.WizardWifi
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.PictureKeys
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.core.store.GateMethod
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SetupMachineTest {

    private fun found(tv: FoundTv = miTvFound) = DiscoveryEvent.Found(tv)

    // Welcome to Found: discovery, Start, the port opening, the Allow dialog, the probe and the plan.
    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(found())
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun welcomeListsTvsAndStopsLookingAfterEightSeconds() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        settle()
        assertEquals(1, h.discovery.activeCollectors)
        h.discovery.emit(found())
        settle()
        val w = assertIs<SetupState.Welcome>(m.state.value)
        assertEquals(listOf(HOST), w.found.map { it.host })
        assertTrue(w.looking)
        settle(8_000)
        assertFalse(assertIs<SetupState.Welcome>(m.state.value).looking)
    }

    @Test fun twoTvsOpenThePickerAndPickingOneOpensTheDoor() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found())
        h.discovery.emit(found(miTvFound.copy(host = "192.0.2.11", friendlyName = "Bedroom")))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        assertIs<SetupState.Picker>(m.state.value)
        m.dispatch(SetupIntent.PickTv("192.0.2.11"))
        settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals("192.0.2.11", d.tv.host)
        assertEquals(DoorPhase.STEPS, d.phase)
        assertEquals(Families.ATV, d.family)
        // F1: discovery stops once Welcome and the picker are left.
        assertEquals(0, h.discovery.activeCollectors)
    }

    @Test fun portOpeningConnectsAndTheAllowDialogShowsTheFingerprint() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.effects.test {
            h.discovery.emit(found())
            settle()
            m.dispatch(SetupIntent.Start)
            settle()
            h.discovery.open5555[HOST] = true
            settle(2_100)
            assertEquals(SetupEffect.HapticDouble, drain().first())
            val d = assertIs<SetupState.OpenDoor>(m.state.value)
            assertEquals(DoorPhase.CONNECTED, d.phase)
            assertEquals("AB:CD:7F:2A", d.fingerprint)
            settle(700)
            assertIs<SetupState.Found>(m.state.value)
        }
        assertEquals("7F:2A", fingerprintTail("ab:cd:7f:2a"))
    }

    @Test fun noAllowDialogOnANewTvAsksIsThisYourTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        assertEquals(Samples.miTvFacts().serialLast4, assertNotNull(m.extras.value.identity).serialLast4)
        m.dispatch(SetupIntent.ConfirmIdentity)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
    }

    @Test fun probeSavesTheBaselineAndRecordsTheTvWithItsDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(h.setupPlan.id, f.plan.id)
        assertEquals(Level.SIMPLE, f.level)
        assertTrue("snapshot BASELINE" in h.prober.calls)
        assertNotNull(h.snapshots.baseline(f.tvId))
        val tv = h.tvs.tvs.value.single()
        assertEquals("Mi TV 4A", tv.nickname)
        assertEquals(3, tv.wizard?.step)
        assertEquals(DebugState.ON, tv.debugging)
        assertEquals("xiaomi-mitv-axso0", tv.profileId)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertTrue(setup.choices.installLauncher)
        assertEquals("com.netflix.ninja", setup.choices.dock?.single()?.value)
    }

    // The dock sheet's rows (S3b): the draft keeps one ordered list, the dock then Row 2 and Row 3,
    // cut at three rows of the look's columns; the plan and the sheet get the same list.
    @Test fun aDockWithRowsIsOneListCutAtThreeRows() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val apps = List(14) { PackageName("com.example.app$it") }
        m.dispatch(SetupIntent.SetDock(apps + apps.first()))
        settle()
        val last = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(apps.take(12), last.choices.dock)
        // 0.4.0: the draft keeps the whole list (no repeats, up to 24 apps), so more apps in a row
        // in the look step brings the last two into view; the plan and the sheet show three rows.
        assertEquals(apps, h.tvs.tvs.value.single().wizard?.dock)
        assertEquals(apps.take(12), m.extras.value.dock.map { it.pkg })
        assertEquals(4, m.extras.value.dockColumns)
        assertEquals(2, m.extras.value.waiting)
    }

    @Test fun keepingAPackageReplansAndPersistsTheDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = true))
        settle()
        val last = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(setOf(promoPkg), last.choices.kept)
        assertEquals(setOf(promoPkg), h.tvs.tvs.value.single().wizard?.kept)
        m.dispatch(SetupIntent.Keep(promoPkg, keep = false))
        settle()
        assertEquals(emptySet(), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    @Test fun customiseEntersTheCustomLevel() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.Open(Sheet.CUSTOMISE))
        settle()
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Sheet.CUSTOMISE, f.sheet)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(Level.CUSTOM, h.tvs.tvs.value.single().level)
    }

    @Test fun makeItCleanPromptsOnceAndStartsWithAPassForThisTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { emit(it.tvId, RunState.Running(it, 0, listOf(NarrationLine("Restore point saved", NarrationState.DOING)), 10, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.effects.test {
            m.dispatch(SetupIntent.MakeItClean)
            settle()
            assertEquals(listOf(SetupEffect.RequestCredential, SetupEffect.RequestNotificationPermission), drain().takeLast(2))
        }
        assertEquals(1, h.credential.prompts.size)
        assertEquals("GATE_SUBTITLE{tv=Mi TV 4A}", h.credential.prompts.single().subtitle)
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().single()
        assertEquals(StartResult.Started, start.result)
        assertEquals(h.setupPlan.hash, start.confirmed)
        val w = assertIs<SetupState.Working>(m.state.value)
        assertEquals(10, w.run.percent)
        assertEquals(4, h.tvs.tvs.value.single().wizard?.step)
        assertEquals(listOf(h.setupPlan.id), h.ledger.runs(w.tvId).map { it.planId })
    }

    @Test fun cancelledPromptStartsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        assertTrue(h.engine.calls.isEmpty())
        assertIs<SetupState.Found>(m.state.value)
        assertFalse(m.extras.value.starting)
    }

    @Test fun noScreenLockRunsWithANonePassAndShowsTheGreyLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.credential.mode = FakeCredentialGate.Mode.NO_SCREEN_LOCK
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean)
        settle()
        val start = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().first()
        assertEquals(GateMethod.NONE, start.pass?.method)
        assertTrue(m.extras.value.noScreenLock)
    }

    @Test fun theCheckScreenBuzzesFirmAndYesConfirmsHome() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> emit(p.tvId, RunState.AwaitingConfirm(p, p.sections.single(), Samples.NOW_MS + 90_000, null, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.effects.test {
            m.dispatch(SetupIntent.MakeItClean)
            settle()
            val effects = drain()
            assertTrue(SetupEffect.HapticFirm in effects)
            assertTrue(SetupEffect.Notify("NOTIFY_LOOK") in effects)
        }
        val c = assertIs<SetupState.Check>(m.state.value)
        assertEquals(Samples.NOW_MS + 90_000, c.run.deadlineMs)
        m.dispatch(SetupIntent.YesISeeIt)
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.ConfirmHome })
        // The engine reports the confirmed section and the run ends: Done, home on.
        h.engine.finish(c.run.plan, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        assertTrue(d.summary.homeScreenOn)
        assertEquals(1, d.summary.switchedOff)
        assertTrue(d.summary.restartRecommended)   // the profile's reboot_tested is false (D24)
        assertNull(h.tvs.tvs.value.single().wizard)
        // D44: the restore point plan ran after the setup plan.
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })
    }

    @Test fun noPutItBackEndsOnTheUserSaidNoCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> emit(p.tvId, RunState.AwaitingConfirm(p, p.sections.single(), Samples.NOW_MS + 90_000, null, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        m.dispatch(SetupIntent.NoPutItBack); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.RejectHome })
        h.engine.finish(h.setupPlan, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.USER_SAID_NO)
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_USER_SAID_NO, s.card.case)
        assertTrue(s.card.primary.startsWith("STOP_HOME_USER_SAID_NO_PRIMARY"))
        // "Try again" plans from a fresh snapshot and starts once more.
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
        assertEquals(2, h.engine.calls.count { it is FakeEngine.Call.Start })
        assertTrue(h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" } >= 1)
    }

    // QA of 0.4.5 (bug-040-retry): a Setup whose home check ran out of time. The run is journaled as
    // DefaultEngine journals it: the clean-up switch-off done, the section armed, the TV's own home
    // screen switched off and Home set, then the timer ran out and step R (what the TV's watchdog
    // does too) switched the old home screen back on and gave it Home again. The TV then reads with
    // its own home screen in charge.
    private fun timedOutTakeover(h: Harness, id: String, debloat: Boolean = true): tv.cleanyour.core.planner.Plan {
        val token = RunToken("00000000000000aa")
        val head = if (debloat) listOf<Op>(Op.Disable(adPkg)) else emptyList()
        val ops = head + listOf(Op.ArmHomeWatchdog(Samples.stockLauncher, 150, token), Op.Disable(Samples.stockLauncher.pkg),
            Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home))
        val section = tv.cleanyour.core.planner.GuardedSection("home_takeover", head.size, head.size + 3, token, 90_000, Launcher.home, setOf(Samples.stockLauncher.pkg),
            listOf(Op.Enable(Samples.stockLauncher.pkg), Op.SetHome(Samples.stockLauncher)))
        return Samples.plan(ops, id = tv.cleanyour.core.model.PlanId(id), sections = listOf(section))
    }

    // The second run as DefaultEngine journals it when the person says yes on the check screen.
    private suspend fun FakeEngine.runAndConfirm(h: Harness, p: tv.cleanyour.core.planner.Plan) {
        val j = h.journal
        val from = p.sections.single().from
        j.append(JournalRecord.PlanStart(p.id, p.tvId, p.baseSnapshot, p.steps.size, p.hash, "test", Samples.NOW_MS))
        for (step in p.steps) {
            if (step.index == from) j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, Samples.NOW_MS + 150_000))
            j.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
            j.append(JournalRecord.Done(p.id, step.index, 0, true, 1))
        }
        j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.CONFIRMED, Samples.NOW_MS))
        j.append(JournalRecord.End(p.id, RunStatus.COMPLETED))
        finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
    }

    private suspend fun FakeEngine.runAndTimeOut(h: Harness, p: tv.cleanyour.core.planner.Plan) {
        val j = h.journal
        val from = p.sections.single().from
        j.append(JournalRecord.PlanStart(p.id, p.tvId, p.baseSnapshot, p.steps.size, p.hash, "test", Samples.NOW_MS))
        for (step in p.steps) {
            if (step.index == from) j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, Samples.NOW_MS + 150_000))
            j.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
            j.append(JournalRecord.Done(p.id, step.index, 0, true, 1))
        }
        j.append(JournalRecord.Rollback(p.id, from))
        j.append(JournalRecord.Begin(p.id, from + 1, Op.Enable(Samples.stockLauncher.pkg), emptyList(), null, Samples.NOW_MS))
        j.append(JournalRecord.Done(p.id, from + 1, 0, true, 1))
        j.append(JournalRecord.Begin(p.id, from + 2, Op.SetHome(Samples.stockLauncher), emptyList(), null, Samples.NOW_MS))
        j.append(JournalRecord.Done(p.id, from + 2, 0, true, 1))
        j.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ROLLED_BACK, Samples.NOW_MS))
        j.append(JournalRecord.End(p.id, RunStatus.COMPLETED_WITH_SKIPS))
        h.prober.snapshotFor = { r -> Samples.miTvSnapshot(reason = r) }   // the TV's own home screen is Home again
        finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.TIMED_OUT)
    }

    // "Try again" (the card's second button) reads the TV again, plans the switch afresh and runs it;
    // it never goes to Done with the old home screen on the TV. Before, it was "OK"'s action.
    @Test fun timedOutTryAgainReadsTheTvAndRunsTheSwitchAgain() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        // The second plan is made from the fresh read: the clean-up switch-off is already done.
        val plans = mutableListOf(timedOutTakeover(h, "01FAKEPLAN0000000000000001"), timedOutTakeover(h, "01FAKEPLAN0000000000000002", debloat = false))
        var current = plans.first()
        h.planner.respond = { request, _ ->
            when (request) {
                is PlanRequest.Setup -> PlanResult.Ok(current)
                else -> PlanResult.Ok(h.plan(emptyList(), kind = PlanKind.WRITE_RESTORE_POINT, id = "01FAKEKIND0000000000000099"))
            }
        }
        h.engine.onStart = { p -> when (p.id) { plans[0].id -> runAndTimeOut(h, p); plans[1].id -> runAndConfirm(h, p); else -> finish(p, RunStatus.COMPLETED) } }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val card = assertIs<SetupState.Stopped>(m.state.value).card
        assertEquals(StopCase.HOME_TIMED_OUT, card.case)
        assertTrue(assertNotNull(card.secondary).startsWith("STOP_HOME_TIMED_OUT_SECONDARY"), "the second button is Try again")
        current = plans[1]
        val reads = h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" }
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" } > reads, "the TV is read again")
        val starts = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().filter { it.plan.kind == PlanKind.SETUP }
        assertEquals(listOf(plans[0].id, plans[1].id), starts.map { it.plan.id }, "a new plan, never the one that ran")
        val done = assertIs<SetupState.Done>(m.state.value).summary
        assertTrue(done.homeScreenOn)
        // Both runs count: the clean-up switch-off of the first and the TV's own home screen of the second.
        assertEquals(2, done.switchedOff)
    }

    // beta3 QA: Done named the newest read as the restore point ("saved at 22:47") while Undo everything
    // goes back to the first one (12:31). Done now names the first one, and says when it is from an
    // earlier run.
    @Test fun doneNamesTheRestorePointUndoGoesBackTo() = runTest(timeout = 30.seconds) {
        for (earlier in listOf(false, true)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val first = Samples.NOW_MS - 36_000_000
            if (earlier) h.snapshots.save(Samples.snapshot(reason = SnapshotReason.BASELINE, takenAtMs = first))
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED) }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val done = assertIs<SetupState.Done>(m.state.value, "$earlier: ${m.state.value}").summary
            assertEquals(earlier, done.restorePointEarlier)
            assertEquals(if (earlier) first else Samples.NOW_MS, done.restorePointAtMs)
        }
    }

    // "OK" keeps the old home screen: Done counts only what stayed switched off (not the TV's own
    // home screen, which step R switched back on), says the home screen is unchanged and why.
    @Test fun timedOutOkEndsOnAnHonestDone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = timedOutTakeover(h, "01FAKEPLAN0000000000000001")
        h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) runAndTimeOut(h, p) else finish(p, RunStatus.COMPLETED) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val card = assertIs<SetupState.Stopped>(m.state.value).card
        assertEquals(StopCase.HOME_TIMED_OUT, card.case)
        // One thing stayed switched off, so the card never says the TV is as it was.
        assertEquals("STOP_HOME_TIMED_OUT_BODY_OFF{count=1,launcher=}", card.body)
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
        assertEquals(1, h.engine.calls.count { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.SETUP }, "OK never starts the switch again")
        val done = assertIs<SetupState.Done>(m.state.value).summary
        assertFalse(done.homeScreenOn)
        assertEquals(1, done.switchedOff, "the TV's own home screen is on again, so it is not counted")
        assertEquals(HomeOutcome.TIMED_OUT, m.extras.value.doneHomeKept)
        assertEquals(1, h.ledger.progress(h.setupPlan.id).doneDisables)
    }

    // With nothing left switched off (the plan had only the switch, put back), the card says the TV
    // is as it was, which is then true.
    @Test fun timedOutWithNothingSwitchedOffSaysTheTvIsAsItWas() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = timedOutTakeover(h, "01FAKEPLAN0000000000000001", debloat = false)
        h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) runAndTimeOut(h, p) else finish(p, RunStatus.COMPLETED) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals("STOP_HOME_TIMED_OUT_BODY{launcher=}", assertIs<SetupState.Stopped>(m.state.value).card.body)
    }

    @Test fun connectionLostShowsCountsThenCarryOnContinuesWithThePass() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.CONNECTION_LOST, s.card.case)
        assertEquals("STOP_CONNECTION_LOST_BODY{done=0,total=2}", s.card.body)
        assertTrue(s.card.tertiary.orEmpty().startsWith("STOP_CONNECTION_LOST_TERTIARY"))
        assertEquals(ChipState.OFFLINE, m.extras.value.chip)
        settle()
        assertEquals(Reconnect.BACK, m.extras.value.reconnect)   // the fake session is still connected
        m.dispatch(SetupIntent.CarryOn); settle()
        val cont = h.engine.calls.filterIsInstance<FakeEngine.Call.ContinueRun>().single()
        assertNotNull(cont.pass)
        assertNull(cont.refused)
        assertEquals(1, h.credential.prompts.size)   // one prompt per TV session (D17)
    }

    @Test fun stopAndUndoStopsThenUndoesOnThePause() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Running(p, 0, emptyList(), 5, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value, "found before Make it clean")
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Working>(m.state.value, "after Make it clean: ${h.engine.calls} ${m.extras.value.starting} ${h.credential.prompts}")
        assertTrue(m.back(), "back is consumed on 4a")
        assertTrue(m.extras.value.highlightStop, "back only highlights Stop and undo")
        m.dispatch(SetupIntent.StopAndUndo); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Stop }, "engine.stop called")
        h.engine.emit(h.setupPlan.tvId, RunState.Paused(h.setupPlan, 1, PauseReason.USER_STOP, null))
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun }, "undoRun after the user stop: ${h.engine.calls}")
        h.engine.finish(h.setupPlan, RunStatus.ROLLED_BACK)
        settle()
        assertEquals(StopCase.UNDONE, assertIs<SetupState.Stopped>(m.state.value).card.case)
    }

    @Test fun finishSwitchesDebuggingOffAndOpensManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(700)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
        val debugOff = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertNull(debugOff.pass)   // SESSION_END needs no pass (R0: 6)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
        assertEquals(Finishing.DONE, m.extras.value.finishing)
    }

    // STORES-PLAN 1: "Add a card" on Done opens Cards and modules in the full build; the Play build
    // has no add-ons, so Done does not offer it and the intent goes nowhere.
    @Test fun doneOffersAddACardOnlyWhenTheBuildHasAddOns() = runTest(timeout = 30.seconds) {
        for (store in listOf(StoreRules.FULL, StoreRules.PLAY)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val m = h.setup(backgroundScope, store = store)
            assertEquals(store.addOns, m.extras.value.addOns)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            assertIs<SetupState.Done>(m.state.value)
            assertEquals(store.addOns, m.extras.value.addOns)
            m.effects.test {
                m.dispatch(SetupIntent.AddCard); settle()
                val navigations = drain().filterIsInstance<SetupEffect.Navigate>()
                if (store.addOns) assertEquals(SetupEffect.Navigate("modules/${Samples.miTvId().value}"), navigations.single())
                else assertTrue(navigations.isEmpty(), navigations.toString())
            }
        }
    }

    // R5: a STALE pause leaves the run open; the machine ends it so the fresh plan can start.
    @Test fun aStaleRunIsEndedSoTheFreshPlanCanStart() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val first = h.setupPlan
        val second = h.plan(listOf(Op.Disable(adPkg)), id = "01FAKEPLAN0000000000000002")
        h.engine.onStart = { p ->
            if (p.id == first.id) { h.setupPlan = second; emit(p.tvId, RunState.Paused(p, 0, PauseReason.STALE, null)) }
            else finish(p, RunStatus.COMPLETED)
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon }, "the stale run is ended")
        assertEquals(second.id, assertIs<SetupState.Found>(m.state.value).plan.id)
        assertTrue(m.extras.value.staleNotice)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StartResult.Started, h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().result)
        assertIs<SetupState.Done>(m.state.value)
    }

    // R5: a start refused RUN_OPEN shows the open run instead of leaving the screen on Found.
    @Test fun anOpenRunIsShownWhenTheStartIsRefused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val open = h.plan(listOf(Op.Disable(promoPkg)), id = "01FAKEPLAN0000000000000009")
        h.engine.emit(open.tvId, RunState.Paused(open, 1, PauseReason.USER_STOP, null))
        m.dispatch(SetupIntent.MakeItClean); settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals(open.id, s.card.planId)
        assertFalse(m.extras.value.starting)
    }

    // R5, D7: the 10 minute background rule only switches debugging off where a write ran.
    @Test fun tenMinutesInTheBackgroundSwitchesDebuggingOffOnlyAfterAWrite() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.sessionEnd.watch(backgroundScope)
        toFound(h, m)
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff }, "a probe-only session never sends debug_off")
        h.foreground.set(true); settle()
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.discovery.open5555[HOST] = false
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertEquals(1, h.planner.requests.count { it == PlanRequest.DebugOff })
        assertNull(h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last().pass)
        assertEquals(DebugState.OFF, h.tvs.tvs.value.single().debugging)
    }

    // R5, D7: Finish only says "done" when the probe confirmed debugging is off.
    @Test fun finishDoesNotClaimDebuggingOffWhenThePortStaysOpen() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val before = h.discovery.probes.size
        m.effects.test {
            m.dispatch(SetupIntent.Finish)
            settle(3_100)
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().last())
        }
        assertEquals(3, h.discovery.probes.size - before, "three probes a second apart before recording on")
        assertEquals(DebugState.ON, h.tvs.tvs.value.single().debugging)
        assertTrue(m.extras.value.finishing != Finishing.DONE)
        assertTrue(m.extras.value.chip != ChipState.DEBUGGING_OFF)
    }

    // RFC-CURTAIN-1: replan hands the bundled curtain to the planner; Custom's switch turns it off and
    // on again, the draft keeps the choice; a pin that refuses its asset plans without it.
    @Test fun theTvCurtainIsHandedInAndTheCustomSwitchTurnsItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val sha = tv.cleanyour.core.model.Sha256("e".repeat(64))
        val curtain = tv.cleanyour.core.model.LauncherArtifact(PackageName("tv.cleanyour.curtain"), 1, "0.1", sha, Samples.zeroHash,
            tv.cleanyour.core.model.BlobRef(sha, 49_571, "bundled:cleanyourtv-curtain.apk"))
        val m = SetupMachine(h.setupDeps(curtainPin = object : tv.cleanyour.core.trust.CurtainPin { override fun bundled() = curtain }), backgroundScope)
        toFound(h, m)
        fun last() = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertEquals(curtain, last().curtain)
        assertTrue(last().choices.curtain)
        m.dispatch(SetupIntent.Open(Sheet.CUSTOMISE)); settle()
        m.dispatch(SetupIntent.ShowOnTv(false)); settle()
        assertNull(last().curtain)
        assertFalse(last().choices.curtain)
        assertFalse(h.tvs.tvs.value.single().wizard!!.curtain, "the draft keeps the choice")
        m.dispatch(SetupIntent.ShowOnTv(true)); settle()
        assertEquals(curtain, last().curtain)

        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val refusing = SetupMachine(h2.setupDeps(curtainPin = object : tv.cleanyour.core.trust.CurtainPin { override fun bundled() = error("does not match its pin") }), backgroundScope)
        toFound(h2, refusing)
        assertNull(h2.planner.requests.filterIsInstance<PlanRequest.Setup>().last().curtain)
        assertIs<SetupState.Found>(refusing.state.value)
    }

    // RF-PROFILES-63 P1: below Android 8 (the launcher's and the curtain's minSdk) Setup is the
    // clean-up alone: no launcher install, no look, no curtain, no home screen switch, and S3 says why.
    @Test fun belowAndroid8TheSetupIsTheCleanUpAlone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = Samples.miTvFacts().copy(sdk = 24, release = "7.0")
        val sha = Sha256("e".repeat(64))
        val curtain = tv.cleanyour.core.model.LauncherArtifact(PackageName("tv.cleanyour.curtain"), 1, "0.1", sha, Samples.zeroHash, BlobRef(sha, 49_571, "bundled:cleanyourtv-curtain.apk"))
        val m = SetupMachine(h.setupDeps(curtainPin = object : tv.cleanyour.core.trust.CurtainPin { override fun bundled() = curtain }), backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertFalse(setup.choices.installLauncher)
        assertFalse(setup.choices.takeover)
        assertNull(setup.curtain, "the curtain needs Android 8 too")
        assertNull(setup.config, "no look without the new home screen")
        assertFalse(m.extras.value.homePossible)
        assertEquals(HomeBlock.OLD_ANDROID, m.extras.value.homeBlock)
    }

    // RF-PROFILES-63 P2: an exact profile that knows no stock launcher (a China PatchWall home, a
    // projector's own) gets the clean-up instead of a plan the planner refuses as a whole.
    @Test fun aProfileWithNoKnownStockHomeGetsTheCleanUp() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.Exact(miProfile.copy(stockLaunchers = emptyList())) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertFalse(setup.choices.installLauncher)
        assertFalse(setup.choices.takeover)
        assertEquals(HomeBlock.UNKNOWN_HOME, m.extras.value.homeBlock)
        // The reference TV still gets the switch.
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m2 = h2.setup(backgroundScope)
        toFound(h2, m2)
        assertTrue(h2.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.takeover)
        assertEquals(HomeBlock.NONE, m2.extras.value.homeBlock)
    }

    @Test fun keepDebuggingOnSkipsDebugOffAtFinish() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.KeepDebuggingOn(true)); settle()
        assertTrue(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.keepDebuggingOn)
        m.dispatch(SetupIntent.MakeItClean); settle()
        // Baseline QA (0.4.0): Done still said debugging switches off at Finish; it now knows it stays on.
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(m.extras.value.keepsDebugging)
        m.dispatch(SetupIntent.Finish); settle(700)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun withoutKeepDebuggingOnDoneSaysFinishSwitchesItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertFalse(m.extras.value.keepsDebugging)
        m.dispatch(SetupIntent.Finish); settle(700)
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    @Test fun pairingOnlyTvGoesToThePairingDeadEnd() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found(miTvFound.copy(port5555Open = false, connectService = ServiceRef("adb-tls", HOST, 37000))))
        settle()
        m.dispatch(SetupIntent.Start); settle()
        assertIs<SetupState.PairingNeeded>(m.state.value)
        assertTrue(m.back())
        assertIs<SetupState.Welcome>(m.state.value)
    }

    @Test fun typedAddressMustBeAHomeNetworkAddress() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress("8.8.8.8")); settle()
        assertTrue(m.extras.value.addressError)
        assertIs<SetupState.Welcome>(m.state.value)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        assertEquals("192.168.1.50", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.sheet)
        assertTrue(isHomeAddress("10.0.0.2") && isHomeAddress("172.16.4.1") && isHomeAddress("169.254.1.1"))
        assertFalse(isHomeAddress("172.32.0.1") || isHomeAddress("192.168.1") || isHomeAddress("192.168.1.256"))
    }

    @Test fun startWithNoTvWaitsOnTheDoorAndAdoptsTheFirstTvFound() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertEquals(1, h.discovery.activeCollectors)
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        h.discovery.emit(found()); settle()
        assertEquals(HOST, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        assertNull(m.extras.value.card)
    }

    @Test fun waitingForTheAllowTapCountsTimeAndAskAgainRetries() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        settle(3_000)
        assertTrue(assertIs<SetupState.OpenDoor>(m.state.value).waitedMs >= 3_000)
        m.dispatch(SetupIntent.ChangeFamily("Amazon")); settle()
        val d = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.FIRE, d.family)
        assertEquals("BANNER_FIRE", d.banner)
    }

    // A TCL UnionTV in India got the China-market 本机信息 steps from the TCL chip: the chip now asks
    // about the menus first, and only Chinese menus get those steps and the home screen banner.
    @Test fun theTclChipAsksAboutTheMenusBeforeAnySteps() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.EnterAddress("192.168.1.50")); settle()
        m.dispatch(SetupIntent.ChangeFamily("TCL")); settle()
        val asking = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL_ASK, asking.family)
        assertNull(asking.banner)
        assertEquals(DoorPhase.STEPS, asking.phase)

        m.dispatch(SetupIntent.ChangeFamily(Families.ofTclMenus(chinese = false))); settle()
        val global = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL_GLOBAL, global.family)
        assertNull(global.banner)

        // Changing the answer (back through the chip) works the other way too.
        m.dispatch(SetupIntent.ChangeFamily("TCL")); settle()
        assertEquals(Families.TCL_ASK, assertIs<SetupState.OpenDoor>(m.state.value).family)
        m.dispatch(SetupIntent.ChangeFamily(Families.ofTclMenus(chinese = true))); settle()
        val china = assertIs<SetupState.OpenDoor>(m.state.value)
        assertEquals(Families.TCL, china.family)
        assertEquals("BANNER_TCL", china.banner)
        assertEquals("192.168.1.50", china.tv.host)
    }

    @Test fun brandChipsMapToTheirFamilies() {
        assertEquals(Families.TCL_ASK, Families.ofBrand("TCL"))
        assertEquals(Families.TCL_ASK, Families.ofBrand("tcl"))
        assertEquals(Families.ATV, Families.ofBrand("Xiaomi"))
        assertEquals(Families.GTV, Families.ofBrand("Google"))
        assertEquals(Families.FIRE, Families.ofBrand("Amazon"))
        assertEquals(Families.UNKNOWN, Families.ofBrand("Other"))
        assertEquals(Families.TCL_GLOBAL, Families.ofTclMenus(chinese = false))
        assertEquals(Families.TCL, Families.ofTclMenus(chinese = true))
        // Every family the screen can be sent is one the machine takes as it is.
        assertTrue(Families.TCL_ASK in Families.all && Families.TCL_GLOBAL in Families.all && Families.TCL in Families.all)
        // A TCL found on the network still gets the generic steps, never the China-market ones.
        assertEquals(Families.UNKNOWN, Families.of(FoundTv("192.168.1.71", "TCL C635", "C635", emptySet(), true, null, null, 0L)))
    }

    @Test fun wrongTvAtARememberedAddressShowsTheIdentityCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val other = Samples.tv().copy(id = TvId("0123456789abcdef"))
        h.tvs.upsert(other)
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(StopCase.IDENTITY_CHANGED, m.extras.value.card?.case)
        assertTrue(h.session.calls.isEmpty())   // D37: no line sent to the wrong TV
    }

    @Test fun resumeStartShowsTheCountsAndResumesTheEngine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.RESUME_AFTER_DEATH, s.card.case)
        assertEquals("STOP_RESUME_BODY{done=0,total=2,tv=Mi TV 4A}", s.card.body)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Resume })
        h.engine.emit(tv.id, RunState.Paused(h.setupPlan, 1, PauseReason.IN_DOUBT, null))
        m.dispatch(SetupIntent.StopAction(StopAction.TERTIARY)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // R5, D7: after process death the journal shows writes ran, so the TV is ended like any other.
    @Test fun resumeAfterProcessDeathKeepsTheDebugOffPromise() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        h.journal.append(JournalRecord.Begin(h.setupPlan.id, 0, Op.Disable(adPkg), listOf("fake"), null, 1))
        h.journal.append(JournalRecord.Done(h.setupPlan.id, 0, 0, true, 5))
        h.discovery.open5555[HOST] = true
        h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.discovery.open5555[HOST] = false
        h.sessionEnd.endAll()
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff })
    }

    // R5, D15: Done remembers the digest of the config.json the run pushed, so Manage can warn on drift.
    @Test fun doneRemembersThePushedConfigDigest() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val digest = Sha256("ab".repeat(32))
        val push = Op.PushAsset(BlobRef(digest, 10, "blobs/config"), RemotePath(RemoteDir.OVERLAY, listOf("config.json")), digest)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.Begin(p.id, 0, push, listOf("fake"), null, 1))
            h.journal.append(JournalRecord.Done(p.id, 0, 0, true, 5))
            finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertEquals(digest, h.tvs.tvs.value.single().launcher?.ourOverlayDigest)
    }

    // RFC-DOCK-1: replan encodes the dock (with its rows) and the look into the BlobStore, exactly as
    // Manage's ApplyTheme does, and hands both blobs to the Setup request; Done then finds the run's
    // config.json push and remembers its digest (D15).
    @Test fun replanSendsTheDockAndTheLookAsOneConfigJson() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        h.planner.respond = { request, _ ->
            val r = request as PlanRequest.Setup
            val push = r.config?.let { Op.PushAsset(it, config, it.sha256) }
            PlanResult.Ok(h.plan(listOfNotNull(Op.Disable(adPkg), push), takeover = true))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        fun last() = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        // RFC-CARDS-1: module_cards is always written, off on a first Setup. 0.4.0: fill_dock false.
        // look2: Setup sends the new form; a TV never customised has Setup's Default look (Classic,
        // no picture, 4 apps in a row), and this 1 GB TV gets Quick movement.
        fun expected(dock: List<PackageName>) = LauncherConfigCodec.encode(LookKeys.build(LookKeys.spec(StyleId.CLASSIC, movement = Movement.QUICK),
            PictureKeys(false, "#000000", 0, 0), 4, dock, cards = false))
        // The first plan: the picked dock on Setup's Default look.
        val first = expected(listOf(PackageName("com.netflix.ninja")))
        assertEquals(first.decodeToString(), h.blobs.bytes(assertNotNull(last().config)).decodeToString())
        assertNull(last().wallpaper)
        // A look and a dock with a row under it: one config.json with both. Aurora migrates to
        // Classic with the Aurora picture, which this phone's gallery does not have: no picture.
        val apps = List(6) { PackageName("com.example.app$it") }
        m.dispatch(SetupIntent.SetTheme("aurora")); settle()
        m.dispatch(SetupIntent.SetDock(apps)); settle()
        val bytes = expected(apps)
        val sent = assertNotNull(last().config)
        assertEquals(bytes.decodeToString(), h.blobs.bytes(sent).decodeToString(), "the blob is the look's bytes for the dock")
        assertEquals(sha256Of(bytes), sent.sha256)
        assertNull(last().wallpaper)
        assertEquals(1, assertIs<tv.cleanyour.core.spec.DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).doc.rowsBelowDock)
        // The run pushes it; Done remembers what it pushed.
        h.engine.onStart = { p ->
            val i = p.steps.indexOfFirst { it.op is Op.PushAsset }
            h.journal.append(JournalRecord.Begin(p.id, i, p.steps[i].op, listOf("fake"), null, 1))
            h.journal.append(JournalRecord.Done(p.id, i, 0, true, 5))
            finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED)
        }
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertEquals(sha256Of(bytes), h.tvs.tvs.value.single().launcher?.ourOverlayDigest)
        assertEquals(sha256Of(bytes), h.tvs.tvs.value.single().launcher?.overlayDigest)
        // RFC-CARDS-1: Manage starts from what the run pushed: the dock, the look and cards off.
        val saved = assertNotNull(HomePrefs(h.documents).load(h.tvs.tvs.value.single().id))
        assertEquals(listOf<Any?>(apps, false, "classic", LookPicture.NONE_ID, 4), listOf(saved.dock, saved.cards, saved.style, saved.picture, saved.columns))
    }

    // RFC-CARDS-1: the first Setup never turns module cards on, so the new home screen never asks
    // "New card from ..." about an app already on the TV; a TV whose cards the person turned on in
    // an earlier session keeps them on.
    @Test fun setupKeepsModuleCardsOffUnlessTheyWereTurnedOnBefore() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        fun cards(): Boolean? {
            val sent = assertNotNull(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().config)
            return assertIs<tv.cleanyour.core.spec.DecodeResult.Ok>(LauncherConfigCodec.decode(h.blobs.bytes(sent))).doc.moduleCards
        }
        assertEquals(false, cards())
        HomePrefs(h.documents).save(h.tvs.tvs.value.single().id, HomeChoice(emptyList(), null, cards = true))
        m.dispatch(SetupIntent.SetDock(listOf(PackageName("com.netflix.ninja")))); settle()
        assertEquals(true, cards())
    }

    @Test fun startDestinationFollowsTheDisk() = runTest(timeout = 30.seconds) {
        val h = Harness()
        assertEquals(StartDestination.Welcome, resolveStart(emptyList(), h.journal))
        val tv = Samples.tv()
        assertEquals(StartDestination.Manage(tv.id), resolveStart(listOf(tv), h.journal))
        val draft = tv.copy(wizard = WizardDraft(3, Level.SIMPLE, emptySet(), emptySet(), null, null, false, null, 0))
        assertEquals(StartDestination.ContinueSetup(tv.id), resolveStart(listOf(draft), h.journal))
        h.journal.append(JournalRecord.PlanStart(h.setupPlan.id, tv.id, Samples.zeroHash, 2, h.setupPlan.hash, "0.1.0", 0))
        assertEquals(StartDestination.ResumeRun(tv.id, h.setupPlan.id), resolveStart(listOf(draft), h.journal))
    }

    @Test fun continueStartReconnectsToTheDraftTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(promoPkg), emptySet(), null, null, false, null, 0))
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Continue(tv.id))
        settle(); settle(700)
        val f = assertIs<SetupState.Found>(m.state.value)
        assertEquals(Level.CUSTOM, f.level)
        assertEquals(setOf(promoPkg), h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.kept)
    }

    // RFC-W8-1: "No" on the identity line is an intent; it closes the session and records nothing.
    @Test fun noOnTheIdentityLineClosesTheSessionAndRecordsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertEquals(DoorPhase.IDENTITY, assertIs<SetupState.OpenDoor>(m.state.value).phase)
        m.dispatch(SetupIntent.RejectIdentity); settle()
        assertIs<SetupState.Welcome>(m.state.value)
        assertTrue(FakeTvSession.Call.Close in h.session.calls)
        assertTrue(h.tvs.tvs.value.isEmpty())
        // Finding 1: "No" offers the address entry, and the same device is not offered again.
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        assertNull(m.extras.value.identity)
        h.discovery.emit(found()); settle()
        assertTrue(assertIs<SetupState.Welcome>(m.state.value).found.isEmpty())
        m.back(); settle()                                  // the sheet closes; Start waits for a TV
        m.dispatch(SetupIntent.Start); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        h.discovery.emit(found()); settle()
        assertEquals("", assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
    }

    // RFC-W8-5: the restart test is a REBOOT plan through the runner (journaled, no prompt), never a
    // direct session call; things that came back on are switched off after it.
    @Test fun theRestartTestRunsARebootPlanWithoutAPrompt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        val prompts = h.credential.prompts.size
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.planner.requests.any { it == PlanRequest.Reboot })
        val reboot = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().last()
        assertEquals(PlanKind.REBOOT, reboot.plan.kind)
        assertNull(reboot.pass)
        assertEquals(StartResult.Started, reboot.result)
        assertEquals(prompts, h.credential.prompts.size)
        assertTrue(h.session.calls.none { it == FakeTvSession.Call.Reboot })
        assertIs<RestartState.Back>(assertIs<SetupState.Done>(m.state.value).restartState)
    }

    // RFC-W8-5: a boot wait that runs out leaves the reboot step in doubt and the run open; the
    // machine ends it (nothing to undo) so later writes are not refused, and shows the 5 minute card.
    @Test fun aRestartThatDoesNotComeBackEndsItsRunAndShowsTheCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        h.engine.onStart = { p ->
            if (p.kind == PlanKind.REBOOT) emit(p.tvId, RunState.Paused(p, 0, PauseReason.CONNECTION_LOST, AdbError.Transport.Timeout("boot")))
            else finish(p, RunStatus.COMPLETED)
        }
        m.dispatch(SetupIntent.Restart); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertIs<RestartState.NotBack>(assertIs<SetupState.Done>(m.state.value).restartState)
        assertEquals(StopCase.NOT_BACK_AFTER_RESTART, m.extras.value.card?.case)
    }

    // R1 R-11: with the HOME section open and the TV away, the engine's abandon does nothing; the
    // card says to reconnect, and the abandon runs once the TV answers again.
    @Test fun stopHereWithTheHomeSectionOpenWaitsForTheTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.PlanStart(p.id, p.tvId, Samples.zeroHash, p.steps.size, p.hash, "0.1.0", 0))
            h.journal.append(JournalRecord.Section(p.id, "home_takeover", RunToken("00000000000000aa"), SectionState.ARMED, 0))
            emit(p.tvId, RunState.Paused(p, 2, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.CONNECTION_LOST, assertIs<SetupState.Stopped>(m.state.value).card.case)
        val connected = h.session.state.value
        h.session.setState(ConnectionState.Idle)
        h.discovery.open5555[HOST] = false
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Abandon })
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertTrue(s.card.body.startsWith("STOP_RECONNECT_HOME_BODY"), s.card.body)
        h.session.setState(connected)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
    }

    // W3 D-W3-2: step R rebuilt from the journal ends the run ABANDONED with no steps of its own
    // and home CONNECTION_LOST; the card says the old home screen is back.
    @Test fun stepRFromTheJournalEndsOnTheOldHomeScreenIsBackCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.ledger.record(h.setupPlan)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, h.setupPlan.id))
        settle()
        h.engine.emit(tv.id, RunState.Finished(h.setupPlan.copy(steps = emptyList()), RunStatus.ABANDONED, emptyList(), HomeOutcome.CONNECTION_LOST))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_PUT_BACK, s.card.case)
        assertEquals("STOP_HOME_PUT_BACK_TITLE{tv=Mi TV 4A}", s.card.title)
        assertEquals("STOP_RESUME_HOME_BODY{tv=Mi TV 4A}", s.card.body)
    }

    // R5-W8 O2: the resume card for an open Manage write does not say "setting up", and the run
    // ends in Manage, not on the wizard's Done.
    @Test fun theResumeCardForAManageWriteSaysChangeAndEndsInManage() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val put = h.plan(listOf(Op.Enable(adPkg)), kind = PlanKind.PUT_BACK, id = "01FAKEPUTBACK0000000000001")
        h.ledger.record(put)
        h.discovery.open5555[HOST] = true
        val m = h.setup(backgroundScope, SetupStart.Resume(tv.id, put.id))
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals("STOP_RESUME_CHANGE_TITLE{done=0,total=0,tv=Mi TV 4A}", s.card.title)
        assertTrue(s.card.body.startsWith("STOP_RESUME_CHANGE_BODY"), s.card.body)
        h.engine.emit(tv.id, RunState.Paused(put, 0, PauseReason.IN_DOUBT, null)); settle()
        m.effects.test {
            h.engine.finish(put, RunStatus.COMPLETED); settle()
            assertEquals(SetupEffect.Navigate("manage/${tv.id.value}"), drain().last())
        }
        assertFalse(m.state.value is SetupState.Done)
    }

    // Bug 2 of the emulator run: an address typed on the "No TV found yet" card never probed port
    // 5555, because the placeholder door and the typed door are the same state class and phase.
    @Test fun anAddressTypedFromTheNotFoundCardProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        m.dispatch(SetupIntent.Start); settle()
        settle(20_000)
        assertEquals(StopCase.TV_NOT_FOUND, m.extras.value.card?.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertTrue(TYPED in h.discovery.probes)
        assertEquals(0, h.discovery.activeCollectors)       // a typed door stops looking
        assertNull(m.extras.value.card)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    @Test fun anAddressTypedOnWelcomeProbesItsPortAndConnects() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.discovery.emit(found()); settle()                  // one TV found, and still the address is offered
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        assertEquals(Sheet.ADDRESS, m.extras.value.sheet)
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        assertEquals(TYPED, assertIs<SetupState.OpenDoor>(m.state.value).tv.host)
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == TYPED } >= 2)   // waiting: the poll runs
        h.discovery.open5555[TYPED] = true
        settle(2_100)
        settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertTrue(HOST !in h.discovery.probes)
    }

    @Test fun typingAnotherAddressWhileWaitingMovesThePollToIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        val wrong = "192.168.1.40"
        m.dispatch(SetupIntent.EnterAddress(wrong)); settle()
        settle(4_100)
        assertTrue(h.discovery.probes.count { it == wrong } >= 2)
        m.dispatch(SetupIntent.Open(Sheet.ADDRESS)); settle()
        h.discovery.open5555[TYPED] = true
        m.dispatch(SetupIntent.EnterAddress(TYPED)); settle()
        val wrongProbes = h.discovery.probes.count { it == wrong }
        assertTrue(TYPED in h.discovery.probes)
        settle(4_100)
        assertEquals(wrongProbes, h.discovery.probes.count { it == wrong })   // the old poll stopped
        assertIs<SetupState.Found>(m.state.value)
        assertEquals(listOf<tv.cleanyour.core.model.Endpoint>(tv.cleanyour.core.model.Endpoint.PlainTcp(TYPED)), h.sessions.opens)
    }

    // Finding 3: the dock sheet offers launchable apps only, never a home screen or a provider.
    @Test fun theDockSheetOffersOnlyLaunchableApps() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val netflix = tv.cleanyour.core.model.PackageName("com.netflix.ninja")
        val calendar = tv.cleanyour.core.model.PackageName("com.android.providers.calendar")
        val stock = stockComponent.pkg
        fun st(system: Boolean, enabled: tv.cleanyour.core.model.EnabledState = tv.cleanyour.core.model.EnabledState.ENABLED) =
            tv.cleanyour.core.model.PackageState(enabled, system, 1, null, false, false, null, false)
        val vlc = tv.cleanyour.core.model.PackageName("org.videolan.vlc")
        val gboard = tv.cleanyour.core.model.PackageName("com.google.android.inputmethod.latin")
        val packages = mapOf(netflix to st(true), calendar to st(true), stock to st(true), vlc to st(false), gboard to st(true), adPkg to st(true))
        h.prober.snapshotFor = { Samples.snapshot(packages = packages, reason = it) }
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.launchable = setOf(netflix, stock, calendar, vlc)
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertEquals(listOf(netflix, vlc).sortedBy { it.value }, m.extras.value.apps.map { it.pkg }.sortedBy { it.value })
        // The TV could not say: user-installed apps and known streaming apps only.
        assertEquals(setOf(vlc), DockCandidates.of(Samples.snapshot(packages = packages), null, miProfile, h.facts))
        assertTrue(DockCandidates.isProvider(calendar) && !DockCandidates.isProvider(netflix))
    }

    // CytLog: the wizard writes redacted milestones for "Share diagnostics", never an address,
    // a serial or a package name.
    @Test fun theWizardLogsItsMilestonesWithoutAddressesSerialsOrPackages() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { finish(it, RunStatus.COMPLETED, null) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val log = h.logLines.joinToString("\n")
        for (want in listOf("setup: connect start", "setup: connected", "setup: probe start", "setup: probe done", "setup: plan ready", "setup: run finished COMPLETED")) {
            assertTrue(h.logLines.any { it.startsWith(want) }, "missing $want in\n$log")
        }
        val serial = Samples.miTvFacts().serialLast4
        for (secret in listOf(HOST, serial, adPkg.value, promoPkg.value, "com.netflix.ninja", Samples.miTvId().value)) assertTrue(secret !in log, "$secret logged")
    }

    // D3 (launcher v2.4.0's guard) with RF-LAUNCHER-240 F6 and F14: the wizard stays on when it
    // saved the Wi-Fi or when the TV could not tell. RF-SAFETY-250: the switch needs it off, and with
    // it on and the TV's own home screen off it takes Home (upstream #506), so then the switch is
    // skipped up front and S3 says why. With the Wi-Fi saved by others, or nothing saved at all (a
    // cable), the takeover may switch it off as before.
    @Test fun theSetupWizardStaysOnWhileItMayOwnTheWifi() = runTest(timeout = 30.seconds) {
        val wizard = PackageName("com.google.android.tungsten.setupwraith")
        data class Case(val wifi: SavedWifi?, val expected: WizardWifi, val verdict: String, val block: HomeBlock)
        val cases = listOf(
            Case(SavedWifi(true, 1, setOf("${wizard.value}:10031")), WizardWifi.OWNER, "wizard_owns", HomeBlock.WIZARD_WIFI),
            Case(SavedWifi(false, 0, emptySet()), WizardWifi.UNKNOWN, "cannot_tell", HomeBlock.WIZARD_WIFI_UNKNOWN),
            Case(null, WizardWifi.UNKNOWN, "cannot_tell", HomeBlock.WIZARD_WIFI_UNKNOWN),   // the read failed
            Case(SavedWifi(true, 1, setOf("android.uid.system:1000")), WizardWifi.NOT_OWNER, "others_own", HomeBlock.NONE),
            Case(SavedWifi(true, 0, emptySet()), WizardWifi.NOT_OWNER, "nothing_saved", HomeBlock.NONE),
        )
        for (c in cases) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(packages = s.packages + (wizard to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, true, 1, null, false, false, null, false)),
                        home = s.home.copy(claimants = s.home.claimants + tv.cleanyour.core.model.HomeClaimant(tv.cleanyour.core.model.Component(wizard, ".RecoveryActivity"), 1)))
                }.let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
            }
            h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.Exact(miProfile.copy(setupWraith = wizard, quirks = setOf(tv.cleanyour.core.model.Quirk.HOME_TAKEOVER_SETUPWRAITH))) }
            val skipped = c.block != HomeBlock.NONE
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = !skipped)
            h.engine.onStart = { p -> if (skipped) finish(p, RunStatus.COMPLETED, null) else finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED) }
            var asked = 0
            val m = SetupMachine(h.setupDeps(savedWifi = { asked++; c.wifi ?: throw tv.cleanyour.core.probe.ProbeException(tv.cleanyour.core.probe.ProbeStage.LAUNCHER, "no answer") }),
                backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            val setups = h.planner.requests.filterIsInstance<PlanRequest.Setup>()
            assertEquals(c.expected, setups.last().choices.wizardWifi, "$c")
            assertEquals(1, asked, "asked once per snapshot, however often the draft replans")
            assertEquals(c.block, m.extras.value.homeBlock, "$c")
            assertEquals(!skipped, m.extras.value.homePossible, "$c")
            assertEquals(!skipped, setups.last().choices.takeover, "$c")
            assertEquals(!skipped, setups.last().choices.installLauncher, "$c")
            m.dispatch(SetupIntent.MakeItClean); settle()
            if (skipped) {
                // The clean-up alone runs and ends on Done; no stop card, no switch, the wizard untouched.
                assertTrue(m.state.value !is SetupState.Stopped, m.state.value.toString())
                assertTrue(h.planner.requests.filterIsInstance<PlanRequest.Setup>().none { it.choices.takeover || wizard in it.choices.extraOff })
            } else {
                // Issue #375: with the Wi-Fi saved by others the switch already had the wizard off (the
                // quirk), so "Try with the wizard off" is never offered; the plain card instead.
                val s = assertIs<SetupState.Stopped>(m.state.value)
                assertEquals(StopCase.HOME_UNKNOWN, s.card.case)
                assertTrue(s.card.primary.startsWith("STOP_HOME_UNKNOWN_PRIMARY"), s.card.primary)
            }
            // Only the verdict is logged.
            assertTrue(h.logLines.any { it == "setup: setup wizard and saved Wi-Fi: ${c.verdict}" }, h.logLines.toString())
            assertTrue(h.logLines.none { "10031" in it || "android.uid" in it })
        }
    }

    // RF-SAFETY-250 with the shipped profiles: each TV below gets the clean-up alone and its own S3
    // line. The Google TV sets whose wizard the switch would switch off, when the TV says the wizard
    // saved the Wi-Fi (and only then); Google's own hardware (casting); the MOKA family (the boot
    // loop), also when no profile matches; and an unknown TV whose wizard saved the Wi-Fi.
    @Test fun theSwitchIsSkippedUpFrontWhereItIsUnsafe() = runTest(timeout = 60.seconds) { releaseRule {
        val wizard = PackageName("com.google.android.tungsten.setupwraith")
        val shipped = tv.cleanyour.core.spec.BundledProfileSource.bundled.profiles.associateBy { it.id }
        val owns = SavedWifi(true, 1, setOf("${wizard.value}:10031"))
        val others = SavedWifi(true, 1, setOf("android.uid.system:1000"))
        data class Case(val name: String, val profile: String?, val facts: tv.cleanyour.core.model.DeviceFacts, val wifi: SavedWifi, val block: HomeBlock, val wizardOn: Boolean = true)
        val base = Samples.miTvFacts().copy(sdk = 34, release = "14")
        fun facts(device: String, brand: String, manufacturer: String = brand, sdk: Int = 34) = base.copy(device = device, brand = brand, manufacturer = manufacturer, model = "unknown", sdk = sdk)
        val cases = buildList {
            for (id in listOf("tcl-43s5k", "tcl-g03-google-tv", "xgimi-mogo-3-pro", "eko-2k-google-tv-tennoji", "byintek-t5-max", "aiwa-ai-pont-sa", "polytron-2k-hayward")) {
                add(Case(id, id, base, owns, HomeBlock.WIZARD_WIFI))
                add(Case("$id, Wi-Fi saved by others", id, base, others, HomeBlock.NONE))
            }
            add(Case("chromecast", "google-chromecast-gtv-4k", facts("sabrina", "google"), others, HomeBlock.CASTING))
            add(Case("streamer", "google-tv-streamer-4k-kirkwood", facts("kirkwood", "google"), others, HomeBlock.CASTING))
            add(Case("chromecast hd, no profile", null, facts("boreal", "google"), others, HomeBlock.CASTING))
            add(Case("kalley-43g200", "kalley-43g200", facts("R3_GTV", "Kalley", "MOKA"), others, HomeBlock.BOOT_LOOP))
            add(Case("kalley-r10g", "kalley-r10g", facts("R10G", "Kalley"), others, HomeBlock.BOOT_LOOP))
            add(Case("prismplus-q65-ultra-r10g", "prismplus-q65-ultra-r10g", facts("R10G", "PRISMPLUS", "MOKA"), others, HomeBlock.BOOT_LOOP))
            add(Case("R10G on Android 15, no profile", null, facts("R10G", "PRISMPLUS", "MOKA", sdk = 35), others, HomeBlock.BOOT_LOOP))
            // 0.4.5: the remote's app buttons need launcherx on the BRAVIA VU3.
            add(Case("sony-bravia-vu3", "sony-bravia-vu3", facts("BRAVIA_VU3_4K", "Sony"), others, HomeBlock.REMOTE_BUTTONS))
            // 0.4.6: the unknown TV under another maker; the reference Xiaomi itself meets R0 (below).
            val acme = base.copy(brand = "Acme", manufacturer = "Acme")
            add(Case("unknown TV, wizard owns the Wi-Fi", null, acme, owns, HomeBlock.WIZARD_WIFI))
            // RF-SAFETY-250: with no profile the switch never switches the wizard off, so a wizard
            // that is on means no switch, whatever the Wi-Fi answer; without one the switch goes ahead.
            add(Case("unknown TV, Wi-Fi saved by others", null, acme, others, HomeBlock.WIZARD_STAYS_ON))
            add(Case("unknown TV, the Wi-Fi read failed", null, acme, SavedWifi(false, 0, emptySet()), HomeBlock.WIZARD_STAYS_ON))
            add(Case("unknown TV without a setup wizard", null, acme, others, HomeBlock.NONE, wizardOn = false))
            // 0.4.6 (R0): an unknown Xiaomi whose only Home claimant is Google's Android TV home: the
            // wizard is on but claims no Home, so the switch goes ahead whatever the Wi-Fi answer.
            add(Case("unknown Xiaomi, wizard owns the Wi-Fi", null, base, owns, HomeBlock.NONE))
            add(Case("unknown Xiaomi, Wi-Fi saved by others", null, base, others, HomeBlock.NONE))
            add(Case("unknown Xiaomi, the Wi-Fi read failed", null, base, SavedWifi(false, 0, emptySet()), HomeBlock.NONE))
            add(Case("Philips R8G, made by MOKA but not this family", null, facts("whitefield", "Philips", "MOKA"), others, HomeBlock.NONE, wizardOn = false))
        }
        for (c in cases) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.facts = c.facts
            val profile = c.profile?.let { assertNotNull(shipped[it], it) }   // as shipped: the streamer and kalley-43g200 list no stock home
            // The profile's own stock home answers Home (issue #8: a TV whose stock home is not a
            // claimant gets the clean-up alone, UNKNOWN_HOME, before any of these risks).
            val stock = profile?.stockLaunchers?.firstOrNull() ?: Samples.stockLauncher
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    val withStock = s.copy(packages = s.packages + (stock.pkg to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, true, 1, null, false, false, null, false)),
                        home = tv.cleanyour.core.model.HomeResolution(stock, listOf(tv.cleanyour.core.model.HomeClaimant(stock, 2))))
                    if (c.wizardOn) withStock.copy(packages = withStock.packages + (wizard to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, true, 1, null, false, false, null, false)))
                    else withStock.copy(packages = withStock.packages - wizard)
                }.let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
            }
            h.profiles.resolver = { _, _ -> profile?.let { tv.cleanyour.core.spec.ProfileResolution.Exact(it) } ?: tv.cleanyour.core.spec.ProfileResolution.None(listOf(Samples.stockLauncher)) }
            val m = SetupMachine(h.setupDeps(savedWifi = { c.wifi }), backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
            assertEquals(c.block, m.extras.value.homeBlock, c.name)
            assertEquals(c.block == HomeBlock.NONE, setup.choices.takeover, c.name)
            assertEquals(c.block == HomeBlock.NONE, setup.choices.installLauncher, c.name)
            if (c.block != HomeBlock.NONE) assertNull(setup.config, "${c.name}: no look without the new home screen")
        }
    }
    }

    // 0.4.6 (R0): a reported global Xiaomi (MiTV-AXSO1) resolves to the Xiaomi brand set, whose stock
    // launchers hold Google's Android TV home, so its Home is known (never UNKNOWN_HOME), and with the
    // wizard on and no Home claimant Setup offers the switch: HomeBlock.NONE, for each Wi-Fi answer.
    @Test fun aReportedXiaomiOnItsBrandSetGetsTheSwitchWithTheWizardOn() = runTest(timeout = 60.seconds) {
        val wizard = PackageName("com.google.android.tungsten.setupwraith")
        val tvlauncher = Samples.stockLauncher
        val fallback = tv.cleanyour.core.model.Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
        val facts = Samples.miTvFacts().copy(model = "MiTV-AXSO1", device = "dangalFHD", fingerprint = "Xiaomi/dangalFHD/dangalFHD:9/PI/6157:user/release-keys")
        val bundled = tv.cleanyour.core.spec.BundledProfileSource()
        val res = bundled.resolveNow(facts)
        assertTrue(res is tv.cleanyour.core.spec.ProfileResolution.BrandSureSet && tvlauncher in res.stockLaunchers, res.toString())
        for (wifi in listOf(SavedWifi(true, 1, setOf("${wizard.value}:10031")), SavedWifi(true, 1, setOf("android.uid.system:1000")), SavedWifi(false, 0, emptySet()))) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.facts = facts
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(facts = facts, home = tv.cleanyour.core.model.HomeResolution(tvlauncher,
                        listOf(tv.cleanyour.core.model.HomeClaimant(tvlauncher, 2), tv.cleanyour.core.model.HomeClaimant(fallback, -1000))))
                }.let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
            }
            h.profiles.resolver = { _, _ -> res }
            val m = SetupMachine(h.setupDeps(savedWifi = { wifi }), backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
            assertEquals(HomeBlock.NONE, m.extras.value.homeBlock, "$wifi")
            assertTrue(setup.choices.takeover && setup.choices.installLauncher, "$wifi")
        }
    }

    // RF-LAUNCHER-250 F1: a profile's switch-off that the live check kept, because the package runs
    // the TV's inputs, is a skip: Setup ends on Done (never the "We stopped" card), the line names it
    // as kept for the inputs, and the generic "harmless" line is only for the other skips.
    @Test fun aSwitchOffKeptForTheTvInputsEndsOnDoneWithItsOwnLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)))
        h.engine.onStart = { p -> if (p.kind != PlanKind.SETUP) finish(p, RunStatus.COMPLETED) else {
            h.journal.append(JournalRecord.Begin(p.id, 0, p.steps[0].op, listOf("fake"), null, 20))
            h.journal.append(JournalRecord.Failed(p.id, 0, null, tv.cleanyour.core.engine.DefaultEngine.KEPT_TV_INPUT, ""))
            h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 21))
            h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "package not on the TV"))
            emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, p.steps, null))
        } }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        val kept = m.extras.value.doneKept
        assertEquals(1, kept.size, kept.toString())
        assertEquals(1, d.summary.skipped.size, "only the other skip gets the generic line")
        assertTrue(kept.single() !in d.summary.skipped)
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.ContinueRun })
    }

    // Upstream #435: a vendor's FallbackHome answers Home but is no launcher, so when the switch fails
    // the card never names it as "another home screen app"; a real one is still named.
    @Test fun aFallbackHomeIsNotAnotherLauncher() = runTest(timeout = 30.seconds) {
        val cases = listOf(
            tv.cleanyour.core.model.Component(PackageName("com.tcl.keycustomfunctionservice"), ".FallbackHome") to StopCase.HOME_UNKNOWN,
            tv.cleanyour.core.model.Component(PackageName("com.android.provision"), ".DefaultActivity") to StopCase.HOME_UNKNOWN,
            tv.cleanyour.core.model.Component(PackageName("com.example.otherhome"), ".Home") to StopCase.HOME_OTHER_LAUNCHER,
        )
        for ((claimant, expected) in cases) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(home = s.home.copy(claimants = s.home.claimants + tv.cleanyour.core.model.HomeClaimant(claimant, 1)))
                }
            }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED) }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val s = assertIs<SetupState.Stopped>(m.state.value)
            assertEquals(expected, s.card.case, claimant.flat)
        }
    }

    // D36 (RF-LAUNCHER-240 F1): a home screen app under another key is replaced, and only then; the
    // plan's summary then carries replacesLauncher, which S3 shows above the button.
    @Test fun onlyALauncherUnderAnotherKeyIsReplaced() = runTest(timeout = 30.seconds) {
        val launcher = PackageName("com.gathin.tvhome")
        for ((signer, replace) in listOf(Sha256("e".repeat(64)) to true, Samples.zeroHash to false, null to false)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(packages = s.packages + (launcher to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, false, 2, signer, false, false, null, false)))
                }
            }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            assertEquals(replace, h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.replaceLauncherSigner, "signer $signer")
        }
    }
    // Issue #2 (A1): an unlisted Fire TV Edition set (an Insignia panel, Amazon the maker, an AFT
    // model code) resolves to the Amazon sure set; its facts say Fire OS, so S3 offers no new home
    // screen, names Fire TV, and the real planner makes the clean-up alone: no section, no install.
    @Test fun anUnlistedFireTvOnTheAmazonSureSetGetsTheCleanUpAlone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val amazon = tv.cleanyour.core.model.Component(PackageName("com.amazon.tv.launcher"), ".ui.HomeActivity_vNext")
        val fire = Samples.miTvFacts().copy(brand = "Insignia", manufacturer = "Amazon", model = "AFTDCT99", device = "unknown")
        h.prober.facts = fire
        h.prober.snapshotFor = { r ->
            Samples.snapshot(facts = fire, reason = r,
                packages = Samples.miTvPackages(emptySet()) + (amazon.pkg to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, true, 1, null, false, false, "Home", false)),
                home = tv.cleanyour.core.model.HomeResolution(amazon, listOf(tv.cleanyour.core.model.HomeClaimant(amazon, 950))))
        }
        val groups = listOf(tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE,
            listOf(adPkg, amazon.pkg).map { tv.cleanyour.core.model.PackageEntry(it, null) }))
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.BrandSureSet("Amazon", groups, listOf(amazon)) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(),
            tv.cleanyour.testing.fixtures.FakeConfigTransport(), "0.3.2")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value)
        assertEquals(HomeBlock.TV_REFUSES, m.extras.value.homeBlock)
        assertTrue(m.extras.value.fireTv, "S3 names Fire TV, not Insignia")
        assertFalse(m.extras.value.homePossible)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertFalse(setup.choices.takeover)
        assertFalse(setup.choices.installLauncher)
        assertTrue(found.plan.sections.isEmpty())
        assertTrue(found.plan.steps.none { it.op is Op.Install || it.op is Op.InstallStream || it.op is Op.SetHome || it.op is Op.ArmHomeWatchdog })
        assertEquals(listOf<Op>(Op.Disable(adPkg)), found.plan.steps.map { it.op })
    }

    // A5: "Stop here" shows Done only once the engine's abandon really ended the run. While it is
    // still paused (step R could not put the TV's own home screen back) the card comes back instead.
    @Test fun stopHereDoesNotShowDoneWhileTheEngineIsPaused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused"))) }
        h.engine.onAbandon = { id -> emit(id, RunState.Paused(h.setupPlan, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused again"))) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value, "not Done while paused").card.case)
        assertEquals(2, h.logLines.count { it.startsWith("setup: run paused") }, "each pause logged once")
        // The TV lets step R through this time: the abandon ends the run, and Done follows.
        h.engine.onAbandon = { _ -> finish(h.setupPlan, RunStatus.ABANDONED) }
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertIs<SetupState.Done>(m.state.value)
    }

    // The abandon's new pause equals the first one, so the run state's collector never sees it
    // (a StateFlow drops a value equal to the last one it delivered). The direct path logs it, once.
    @Test fun anAbandonThatPausesAgainLogsThatPauseOnce() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val error = AdbError.NonZeroExit(1, "refused")
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, error)) }
        h.engine.onAbandon = { id ->
            emit(id, RunState.RollingBack(h.setupPlan, 1))
            emit(id, RunState.Paused(h.setupPlan, 1, PauseReason.STEP_FAILED, error))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(1, h.logLines.count { it.startsWith("setup: run paused") })
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        assertEquals(2, h.logLines.count { it.startsWith("setup: run paused") }, h.logLines.joinToString("\n"))
    }

    // Issue #8 (TCL G07, exact profile tcl-g07-google-tv): launcherx, the profile's stock home, is
    // switched off and Home answers to a launcher the person installed. 0.3.2 asked the planner for
    // the switch, the planner found no stock home among the claimants and refused the whole plan
    // ("plan refused" after every probe). Now the wizard asks the planner's StockHome first: the
    // home screen was already changed, so S3 says so and the real planner makes the clean-up alone.
    @Test fun issue8AHomeScreenAlreadyChangedGetsTheCleanUpAlone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        h.prober.snapshotFor = { r -> tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r) }
        val source = tv.cleanyour.core.spec.BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(),
            tv.cleanyour.testing.fixtures.FakeConfigTransport(), "0.3.3")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
        assertEquals(HomeBlock.HOME_CHANGED, m.extras.value.homeBlock)
        assertFalse(m.extras.value.homePossible)
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertIs<tv.cleanyour.core.spec.ProfileResolution.Exact>(setup.resolution)
        assertFalse(setup.choices.takeover)
        assertFalse(setup.choices.installLauncher)
        assertTrue(found.plan.sections.isEmpty())
        assertTrue(found.plan.steps.none { it.op == Op.Disable(tv.cleanyour.testing.fixtures.Issue033.personsLauncher.pkg) })
        assertTrue(h.logLines.none { it == "setup: plan refused" }, h.logLines.joinToString("\n"))
        assertTrue(h.logLines.any { it == "setup: home switch blocked HOME_CHANGED" }, h.logLines.joinToString("\n"))
        // The same TV with launcherx still a claimant gets the switch as before.
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h2.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        val lx = tv.cleanyour.testing.fixtures.Issue033.launcherx
        h2.prober.snapshotFor = { r ->
            tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r).let { snap ->
                snap.copy(packages = snap.packages + (lx.pkg to snap.packages.getValue(lx.pkg).copy(enabled = tv.cleanyour.core.model.EnabledState.ENABLED)),
                    home = tv.cleanyour.core.model.HomeResolution(lx, listOf(tv.cleanyour.core.model.HomeClaimant(lx, 2)) + snap.home.claimants))
            }
        }
        h2.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val m2 = SetupMachine(h2.setupDeps(savedWifi = { tv.cleanyour.core.probe.SavedWifi(listed = true, networks = 1, owners = setOf("android.uid.system:1000")) }), backgroundScope)
        toFound(h2, m2)
        assertEquals(HomeBlock.NONE, m2.extras.value.homeBlock)
    }

    // Issues #171 (Xiaomi MiTV4I, brand sure set), #187 (Xiaomi Mi Box S, exact profile) and #220
    // (Polytron Google TV, no profile): Home already answers to com.gathin.tvhome, the new home
    // screen's package name, signed with another key, and the TV's own home screen is switched off.
    // 0.3.3 took that for ours and asked for the install and the switch, which the planner refuses
    // (D36), so every probe ended in "plan refused" and the card said the TV had stopped answering.
    // Now the home screen was already changed: S3 says so and the real planner makes the clean-up alone.
    private suspend fun kotlinx.coroutines.test.TestScope.issue034Found(snap: (SnapshotReason) -> tv.cleanyour.core.model.Snapshot): Pair<Harness, SetupMachine> {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = snap(SnapshotReason.BASELINE).facts
        h.prober.snapshotFor = { r -> snap(r) }
        val source = tv.cleanyour.core.spec.BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(),
            tv.cleanyour.testing.fixtures.FakeConfigTransport(), "0.3.4")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        return h to m
    }

    @Test fun issue171And187And220AHomeScreenUnderOurNameWithAnotherKeyGetsTheCleanUpAlone() = runTest(timeout = 60.seconds) {
        val reports = listOf<Pair<String, (SnapshotReason) -> tv.cleanyour.core.model.Snapshot>>(
            "#171" to { r -> tv.cleanyour.testing.fixtures.Issue034.magnoliaSnapshot(reason = r) },
            "#187" to { r -> tv.cleanyour.testing.fixtures.Issue034.onedaySnapshot(reason = r) },
            "#220" to { r -> tv.cleanyour.testing.fixtures.Issue034.songshanSnapshot(reason = r) },
        )
        for ((issue, snap) in reports) {
            val (h, m) = issue034Found(snap)
            val log = "$issue\n" + h.logLines.joinToString("\n")
            val found = assertIs<SetupState.Found>(m.state.value, log)
            assertEquals(HomeBlock.HOME_CHANGED, m.extras.value.homeBlock, log)
            assertFalse(m.extras.value.homePossible, log)
            val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
            assertFalse(setup.choices.takeover, log)
            assertFalse(setup.choices.installLauncher, log)
            assertTrue(found.plan.sections.isEmpty(), log)
            val pkg = tv.cleanyour.testing.fixtures.Issue034.gathinHome.pkg
            assertTrue(found.plan.steps.none { it.op == Op.Disable(pkg) || it.op == Op.Uninstall(pkg) || it.op is Op.Install || it.op is Op.InstallStream && (it.op as Op.InstallStream).pkg == pkg }, log)
            assertTrue(h.logLines.none { it.startsWith("setup: plan refused") }, log)
            assertTrue(h.logLines.any { it == "setup: home switch blocked HOME_CHANGED" }, log)
        }
    }

    // The same TVs with our own key on the launcher in charge: the switch is already done, nothing
    // is blocked, and the plan is made (no "plan refused").
    @Test fun issue171And187And220OurOwnLauncherAlreadyInChargeIsAlreadyDone() = runTest(timeout = 60.seconds) {
        val ours = Samples.launcherArtifact().signer
        val reports = listOf<Pair<String, (SnapshotReason) -> tv.cleanyour.core.model.Snapshot>>(
            "#171" to { r -> tv.cleanyour.testing.fixtures.Issue034.magnoliaSnapshot(ours, r) },
            "#187" to { r -> tv.cleanyour.testing.fixtures.Issue034.onedaySnapshot(ours, r) },
            "#220" to { r -> tv.cleanyour.testing.fixtures.Issue034.songshanSnapshot(ours, r) },
        )
        for ((issue, snap) in reports) {
            val (h, m) = issue034Found(snap)
            val log = "$issue\n" + h.logLines.joinToString("\n")
            val found = assertIs<SetupState.Found>(m.state.value, log)
            assertEquals(HomeBlock.NONE, m.extras.value.homeBlock, log)
            assertTrue(found.plan.sections.isEmpty(), log)
            assertTrue(found.plan.steps.none { it.op is Op.Uninstall && (it.op as Op.Uninstall).pkg == tv.cleanyour.testing.fixtures.Issue034.gathinHome.pkg }, log)
            assertTrue(h.logLines.none { it.startsWith("setup: plan refused") }, log)
            assertTrue(h.logLines.any { it == "setup: home switch not needed (the new home screen is on)" }, log)
        }
    }

    // A plan the planner refuses has its own card (the TV answered; nothing was changed), not the
    // probe failure's "the TV stopped answering", and the log says why. An app the person installed
    // is not named in the reason; the TV's own apps and our package are.
    @Test fun issue171ARefusedPlanSaysWhyAndHasItsOwnCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        h.prober.snapshotFor = { r -> tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r) }
        h.planner.respond = { _, _ -> PlanResult.Refused(listOf(
            "Projectivy Launcher cannot be switched off: it is a home screen app you installed",
            "com.spocky.projengmenu is not on this TV",
            "the home screen app can only be replaced while the TV's own home screen is in charge (D36)",
            "the launcher artifact is com.gathin.tvhome, not com.google.android.apps.tv.launcherx",
        )) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val stopped = assertIs<SetupState.Stopped>(m.state.value, h.logLines.joinToString("\n"))
        assertEquals(StopCase.PLAN_REFUSED, stopped.card.case)
        val because = h.logLines.filter { it.startsWith("setup: refused because ") }
        assertEquals(listOf(
            "setup: refused because an app you installed cannot be switched off: it is a home screen app you installed",
            "setup: refused because an app you installed is not on this TV",
            "setup: refused because the home screen app can only be replaced while the TV's own home screen is in charge (D36)",
            "setup: refused because the launcher artifact is com.gathin.tvhome, not com.google.android.apps.tv.launcherx",
        ), because, h.logLines.joinToString("\n"))
        assertTrue(h.logLines.none { "Projectivy" in it || "projengmenu" in it })
        // Try again reads the TV again; the same facts give the same card.
        m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle(); settle(700)
        assertEquals(StopCase.PLAN_REFUSED, assertIs<SetupState.Stopped>(m.state.value).card.case)
    }

    // Issue #15 (a Smarttech set no profile knows, the switch blocked): nothing to switch off and no
    // home switch. Done says plainly that nothing was changed, never "Your TV is ready".
    @Test fun issue15ARunThatChangedNothingSaysSoOnDone() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(emptyList())
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff)
        assertTrue(m.extras.value.doneNothing)
        // A run that switched things off is not "nothing".
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m2 = h2.setup(backgroundScope)
        toFound(h2, m2)
        m2.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(2, assertIs<SetupState.Done>(m2.state.value).summary.switchedOff)
        assertFalse(m2.extras.value.doneNothing)
    }

    // Issue #10: the finish line names the switch-offs the run skipped, so a report shows what stayed on.
    @Test fun theFinishLineNamesTheSkippedSwitchOffs() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[0]), null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(h.logLines.any { it == "setup: run finished COMPLETED_WITH_SKIPS, 1 skipped: Disable ${adPkg.value}" }, h.logLines.joinToString("\n"))
        assertFalse(m.extras.value.doneNothing)
    }

    // 0.3.3: which "nothing was changed" body Done shows. Every planned switch-off stayed on (the TV
    // kept them, issue #10) on a TV without an exact list: never "We weren't sure about anything",
    // which is only for a plan with nothing to switch off there.
    @Test fun doneSaysEverythingStayedOnWhenTheTvKeptEverySwitchOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.None(listOf(Samples.stockLauncher)) }
        h.engine.onStart = { p -> emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, p.steps, null)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertTrue(m.extras.value.unknownModel)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff)
        assertTrue(m.extras.value.doneNothing)
        assertEquals(NothingWhy.KEPT_ON, m.extras.value.doneNothingWhy)
        // Nothing planned on the same TV: the "not sure" body.
        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h2.profiles.resolver = { _, _ -> tv.cleanyour.core.spec.ProfileResolution.None(listOf(Samples.stockLauncher)) }
        h2.setupPlan = h2.plan(emptyList())
        val m2 = h2.setup(backgroundScope)
        toFound(h2, m2)
        m2.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(m2.extras.value.doneNothing && m2.extras.value.unknownModel)
        assertEquals(NothingWhy.NOTHING_PLANNED, m2.extras.value.doneNothingWhy)
    }

    // N5: "Stop here" with no step done counts what the ledger shows, never the plan's switch-offs.
    @Test fun stopHereWithNothingDoneSwitchedNothingOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 0, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused"))) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.STEP_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertEquals(0, done.summary.switchedOff, "the plan had 2 switch-offs, none done")
        assertTrue(m.extras.value.doneNothing)
        assertEquals(NothingWhy.STOPPED, m.extras.value.doneNothingWhy)
    }

    // N4: the TV curtain comes and goes, but when this run installed it and its removal failed it is
    // still on the TV, so the run did change something.
    @Test fun aCurtainLeftOnTheTvIsAChange() = runTest(timeout = 30.seconds) {
        val curtainPkg = PackageName(tv.cleanyour.core.planner.DefaultPlanner.CURTAIN_PACKAGE)
        val blob = BlobRef(Samples.zeroHash, 49_571, "bundled:cleanyourtv-curtain.apk")
        suspend fun kotlinx.coroutines.test.TestScope.curtainRun(removed: Boolean): SetupExtras {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val base = h.plan(listOf(Op.InstallStream(blob, blob.sha256, curtainPkg, 1, Samples.zeroHash), Op.Uninstall(curtainPkg)))
            h.setupPlan = base.copy(steps = base.steps.map { it.copy(group = tv.cleanyour.core.planner.DefaultPlanner.GROUP_CURTAIN) })
            h.engine.onStart = { p ->
                h.journal.append(JournalRecord.PlanStart(p.id, p.tvId, Samples.zeroHash, p.steps.size, p.hash, "0.1.0", 0))
                h.journal.append(JournalRecord.Begin(p.id, 0, p.steps[0].op, listOf("fake"), null, 1))
                h.journal.append(JournalRecord.Done(p.id, 0, 0, true, 1))
                h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 2))
                if (removed) h.journal.append(JournalRecord.Done(p.id, 1, 0, true, 1))
                else h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "Failure [DELETE_FAILED_INTERNAL_ERROR]"))
                emit(p.tvId, RunState.Finished(p, if (removed) RunStatus.COMPLETED else RunStatus.COMPLETED_WITH_SKIPS, if (removed) emptyList() else listOf(p.steps[1]), null))
            }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            assertIs<SetupState.Done>(m.state.value)
            return m.extras.value
        }
        assertTrue(curtainRun(removed = true).doneNothing, "the curtain came and went")
        assertFalse(curtainRun(removed = false).doneNothing, "the curtain is still on the TV")
    }

    // S3: our own new home screen is never the person's launcher (the probe reads installedByUs
    // false for every package). A re-run on the G07 with launcherx switched off and Home on ours gets
    // the switch and the look, as in 0.3.2, never HOME_CHANGED.
    @Test fun ourOwnHomeScreenAsHomeIsNeverAHomeScreenChangedByThePerson() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = tv.cleanyour.testing.fixtures.Issue033.g07Facts()
        val ours = Samples.launcherComponent
        h.prober.snapshotFor = { r ->
            tv.cleanyour.testing.fixtures.Issue033.g07Snapshot(r).let { snap ->
                snap.copy(
                    packages = snap.packages - tv.cleanyour.testing.fixtures.Issue033.personsLauncher.pkg +
                        (ours.pkg to tv.cleanyour.core.model.PackageState(tv.cleanyour.core.model.EnabledState.ENABLED, false, 1, Samples.zeroHash, false, false, null, false)),
                    home = tv.cleanyour.core.model.HomeResolution(ours, listOf(tv.cleanyour.core.model.HomeClaimant(ours, 0)) + snap.home.claimants.filter { it.component.pkg != tv.cleanyour.testing.fixtures.Issue033.personsLauncher.pkg }),
                ).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
            }
        }
        val source = tv.cleanyour.core.spec.BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, tv.cleanyour.testing.fixtures.FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(),
            tv.cleanyour.testing.fixtures.FakeConfigTransport(), "0.3.3")
        h.planner.respond = { request, snap -> planner.plan(request, snap, h.facts, emptyList()) }
        val m = SetupMachine(h.setupDeps(savedWifi = { SavedWifi(listed = true, networks = 1, owners = setOf("android.uid.system:1000")) }), backgroundScope)
        toFound(h, m)
        assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
        val setup = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
        assertIs<tv.cleanyour.core.spec.ProfileResolution.Exact>(setup.resolution)
        assertEquals(HomeBlock.NONE, m.extras.value.homeBlock)
        assertTrue(setup.choices.installLauncher)
        assertNotNull(setup.config, "the look is planned")
    }
}
