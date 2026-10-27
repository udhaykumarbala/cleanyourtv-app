package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.engine.StartRefusal
import tv.cleanyour.core.engine.StartResult
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.ApkInfo
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.testing.fixtures.FakeLauncherPin
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PermissionStatement
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.testing.fixtures.FakeApkInspector
import tv.cleanyour.testing.fixtures.FakeArtifactFetcher
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Issue034
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ManageMachinesTest {

    // The journal of a finished run that switched `pkgs` off, recorded the way the engine writes it.
    private suspend fun Harness.ranDisable(tv: Tv, pkgs: List<PackageName>, id: String = "01FAKERUN0000000000000001") {
        val plan = plan(pkgs.map { Op.Disable(it) }, id = id)
        ledger.record(plan)
        journal.append(JournalRecord.PlanStart(plan.id, tv.id, Samples.zeroHash, pkgs.size, plan.hash, "0.1.0", 0))
        pkgs.forEachIndexed { i, p ->
            journal.append(JournalRecord.Begin(plan.id, i, Op.Disable(p), listOf("fake"), null, 10L + i))
            journal.append(JournalRecord.Done(plan.id, i, 0, true, 5))
        }
        journal.append(JournalRecord.End(plan.id, RunStatus.COMPLETED))
    }

    private fun Harness.respond(kind: PlanKind, ops: List<Op>) {
        val previous = planner.respond
        planner.respond = { r, snap ->
            val k = when (r) { is PlanRequest.PutBack -> PlanKind.PUT_BACK; is PlanRequest.SwitchOff -> PlanKind.SWITCH_OFF
                is PlanRequest.Leftovers -> PlanKind.LEFTOVERS; is PlanRequest.UndoEverything -> PlanKind.UNDO_EVERYTHING
                is PlanRequest.AddModule -> PlanKind.ADD_MODULE; is PlanRequest.RemoveModule -> PlanKind.REMOVE_MODULE; else -> null }
            if (k == kind) PlanResult.Ok(plan(ops, kind = kind, id = "01FAKE" + kind.name.take(4) + "00000000000000001")) else previous(r, snap)
        }
    }

    @Test fun ledgerDerivesChangesAndWhatIsStillOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        assertEquals(2, h.ledger.changes(tv.id).size)
        assertEquals(listOf(adPkg, promoPkg), h.ledger.switchedOffByUs(tv.id))
        val back = h.plan(listOf(Op.Enable(adPkg)), id = "01FAKERUN0000000000000002")
        h.ledger.record(back)
        h.journal.append(JournalRecord.Begin(back.id, 0, Op.Enable(adPkg), listOf("fake"), null, 99))
        h.journal.append(JournalRecord.Done(back.id, 0, 0, true, 5))
        assertEquals(listOf(promoPkg), h.ledger.switchedOffByUs(tv.id))
        val p = h.ledger.progress(PlanId("01FAKERUN0000000000000001"))
        assertEquals(2, p.doneDisables)
        assertTrue(p.ended)
    }

    @Test fun putBackRunsOnePlanWithTheGateAndOffersUndo() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertEquals(listOf(adPkg), m.state.value.groups.flatMap { g -> g.rows.map { it.pkg } })
        m.effects.test {
            m.dispatch(SwitchedOffIntent.PutBack(adPkg))
            settle()
            val snack = assertIs<ManageEffect.Snackbar>(drain().single())
            assertEquals(SnackKind.PUT_BACK, snack.kind)
            assertTrue(snack.undo)
            assertEquals(adPkg, snack.pkg)
        }
        assertEquals(1, h.credential.prompts.size)
        assertTrue(h.planner.requests.any { it == PlanRequest.PutBack(adPkg) })
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })   // D44 after every plan
        assertNull(m.state.value.busy)
    }

    // RF-LAUNCHER-250 F1: a switch-off the TV's live check kept, because the package runs the TV's
    // inputs, ends the run with a skip (no open-run card, no Carry on loop) and says so in its own line.
    @Test fun aSwitchOffKeptForTheTvInputsSaysSoAndLeavesNoOpenRun() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.SWITCH_OFF, listOf(Op.Disable(adPkg)))
        h.engine.onStart = { p ->
            if (p.kind != PlanKind.SWITCH_OFF) finish(p, RunStatus.COMPLETED) else {
                h.journal.append(JournalRecord.Begin(p.id, 0, p.steps[0].op, listOf("fake"), null, 20))
                h.journal.append(JournalRecord.Failed(p.id, 0, null, DefaultEngine.KEPT_TV_INPUT, ""))
                emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[0]), null))
            }
        }
        val deps = h.manageDeps()
        val m = SwitchedOffMachine(deps, backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(SwitchedOffIntent.Undo(adPkg))
            settle()
            val snack = assertIs<ManageEffect.Snackbar>(drain().single())
            assertEquals(SnackKind.KEPT_TV_INPUT, snack.kind)
            assertFalse(snack.undo)
        }
        assertIs<RunState.Finished>(h.engine.runState(tv.id).value)
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.ContinueRun })
        assertNull(deps.openWrites.current.value, "no open-run card")
        assertNull(m.state.value.busy)
        // A switch-off that went through is the usual line.
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED) }
        m.effects.test {
            m.dispatch(SwitchedOffIntent.Undo(adPkg))
            settle()
            assertEquals(SnackKind.SWITCHED_OFF, assertIs<ManageEffect.Snackbar>(drain().single()).kind)
        }
    }

    // Issue #10 in Manage: "Switch off" of a package the TV keeps on (the engine skips it, nothing
    // changed) never says it was switched off. It says Done's "wouldn't switch off" line, with no Undo.
    @Test fun aSwitchOffTheTvKeptOnSaysItWouldNotSwitchOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.SWITCH_OFF, listOf(Op.Disable(adPkg)))
        h.engine.onStart = { p ->
            if (p.kind != PlanKind.SWITCH_OFF) finish(p, RunStatus.COMPLETED) else {
                h.journal.append(JournalRecord.Begin(p.id, 0, p.steps[0].op, listOf("fake"), null, 20))
                h.journal.append(JournalRecord.Failed(p.id, 0, null, "verify: expected package:${adPkg.value} listed by pm list packages -d, got ", ""))
                emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[0]), null))
            }
        }
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(SwitchedOffIntent.Undo(adPkg))
            settle()
            val snack = assertIs<ManageEffect.Snackbar>(drain().single())
            assertEquals(SnackKind.KEPT_ON, snack.kind)
            assertFalse(snack.undo)
        }
        // A put back is never read as a kept-on switch-off.
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED) }
        m.effects.test {
            m.dispatch(SwitchedOffIntent.PutBack(adPkg))
            settle()
            assertEquals(SnackKind.PUT_BACK, assertIs<ManageEffect.Snackbar>(drain().single()).kind)
        }
    }

    @Test fun putBackCancelledAtTheGateChangesNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start })
    }

    // R5, D7: a write cancelled at the gate is not a write, so the background rule leaves the TV
    // alone; closing the session after a real write (Forget this TV) switches debugging off first.
    @Test fun debuggingGoesOffOnlyAfterAWriteAndBeforeTheSessionCloses() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.credential.mode = FakeCredentialGate.Mode.FAIL
        h.sessionEnd.watch(backgroundScope)
        val m = SwitchedOffMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        h.foreground.set(false); settle(10 * 60_000L + 100)
        assertFalse(h.planner.requests.any { it == PlanRequest.DebugOff }, "a cancelled gate is not a write")
        h.foreground.set(true); settle()
        h.credential.mode = FakeCredentialGate.Mode.PASS
        m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.PUT_BACK })
        val s = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        s.dispatch(TvSettingsIntent.Forget); settle()
        assertTrue(h.planner.requests.any { it == PlanRequest.DebugOff }, "Forget closes the session, debug_off first")
        assertTrue(h.tvs.tvs.value.isEmpty())
    }

    @Test fun leftoversFindsWhatCameBackAndSwitchesItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.LEFTOVERS, listOf(Op.Disable(adPkg)))
        val m = LeftoversMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        val found = assertIs<LeftoversState.Found>(m.state.value)
        // No facts and no label: the name is read from the package, never the raw package (round 3).
        assertEquals(listOf("Miui Analytics"), found.names)
        assertEquals(PlanRequest.Leftovers(setOf(adPkg)), h.planner.requests.filterIsInstance<PlanRequest.Leftovers>().first())
        m.dispatch(LeftoversIntent.SwitchOff); settle()
        assertEquals(LeftoversState.Done(1), m.state.value)
    }

    @Test fun leftoversWithNothingBack() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = LeftoversMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertEquals(LeftoversState.Nothing, m.state.value)
    }

    @Test fun undoEverythingPreviewsThenConfirmsThenRuns() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        h.respond(PlanKind.UNDO_EVERYTHING, listOf(Op.Enable(promoPkg), Op.Enable(adPkg)))
        val m = RestorePointMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        val p = assertIs<RestorePhase.Preview>(m.state.value.phase)
        assertEquals(2, p.preview.thingsBack)
        assertFalse(p.confirming)
        m.dispatch(RestorePointIntent.PutEverythingBack); settle()   // not before the confirmation sheet
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start })
        m.dispatch(RestorePointIntent.Confirm); settle()
        assertTrue(assertIs<RestorePhase.Preview>(m.state.value.phase).confirming)
        m.dispatch(RestorePointIntent.PutEverythingBack); settle()
        assertIs<RestorePhase.Finished>(m.state.value.phase)
        val undo = h.planner.requests.filterIsInstance<PlanRequest.UndoEverything>().last()
        assertEquals(2, undo.changes.size)
        assertFalse(undo.removeLauncher)
    }

    @Test fun saveACopyAsksForADocumentAndWritesTheBundle() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = RestorePointMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(RestorePointIntent.SaveCopy); settle()
            val doc = assertIs<ManageEffect.CreateDocument>(drain().single())
            assertTrue(doc.suggestedName.startsWith("cleanyourtv-restore-") && doc.suggestedName.endsWith(".zip"))
        }
        val out = java.io.ByteArrayOutputStream()
        assertTrue(m.writeBundle(out))
        assertEquals(1, h.bundleWriter.written.size)
    }

    @Test fun forgetThisTvAsksForTheCredentialAndDeletesItsRecords() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        val m = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(TvSettingsIntent.Rename("Lounge TV", "Lounge")); settle()
        assertEquals("Lounge TV", h.tvs.tvs.value.single().nickname)
        m.dispatch(TvSettingsIntent.SetLevel(Level.CUSTOM)); settle()
        assertEquals(Level.CUSTOM, h.tvs.tvs.value.single().level)
        m.dispatch(TvSettingsIntent.Forget); settle()
        assertEquals(1, h.credential.prompts.size)
        assertTrue(h.tvs.tvs.value.isEmpty())
        assertTrue(h.documents.paths().none { it.startsWith("tvs/${tv.id.value}") })
    }

    // Issue #2 (A5): "Forget this TV" also ends the engine's paused run for that TV, after its records
    // and journals are gone, so a stuck setup needs no force stop and a new Setup is not RUN_OPEN.
    @Test fun forgetThisTvClearsTheOpenRunSoANewSetupIsNotRunOpen() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val stuck = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.emit(tv.id, RunState.Paused(stuck, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused")))
        var goneFirst = false
        h.engine.onAbandon = { id ->
            goneFirst = h.tvs.tvs.value.none { it.id == id } && h.documents.paths().none { it.startsWith("tvs/${id.value}") }
            emit(id, RunState.Idle)
        }
        val m = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(TvSettingsIntent.Forget); settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon && it.tvId == tv.id })
        assertTrue(goneFirst, "the engine is told after the record and the journals are gone")
        assertEquals(RunState.Idle, h.engine.runState(tv.id).value)
        val fresh = h.plan(listOf(Op.Disable(adPkg)), id = "01FAKEPLAN0000000000000002")
        val result = h.engine.start(fresh, h.session, fresh.hash, h.credential.mint(tv.id))
        assertTrue(result != StartResult.Refused(StartRefusal.RUN_OPEN), "$result")
    }

    // S6: while a run is at work on the TV (running, on the check screen, in step R), Forget this TV
    // and Delete everything are refused with a message; nothing is asked, deleted or abandoned.
    @Test fun forgetAndDeleteEverythingWaitForARunAtWork() = runTest(timeout = 30.seconds) {
        val plan = Harness().plan(listOf(Op.Disable(adPkg)), takeover = true)
        val working = listOf(
            RunState.Running(plan, 0, emptyList(), 10, null),
            RunState.AwaitingConfirm(plan, plan.sections.single(), Samples.NOW_MS + 60_000, null, null),
            RunState.RollingBack(plan, 1),
        )
        for (state in working) {
            val h = Harness()
            val tv = h.knownTv()
            h.engine.emit(tv.id, state)
            val forget = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
            val app = AppSettingsMachine(h.manageDeps(), backgroundScope)
            settle()
            forget.effects.test {
                forget.dispatch(TvSettingsIntent.AskForget)
                forget.dispatch(TvSettingsIntent.Forget); settle()
                assertEquals(ManageEffect.Snackbar(SnackKind.RUN_BUSY, tv.nickname, undo = false), drain().single(), "$state")
            }
            assertFalse(forget.state.value.forgetting || forget.state.value.confirmingForget)
            app.effects.test {
                app.dispatch(AppSettingsIntent.Ask(AppConfirm.DELETE_EVERYTHING))
                app.dispatch(AppSettingsIntent.DeleteEverything); settle()
                assertEquals(ManageEffect.Snackbar(SnackKind.RUN_BUSY_ANY, tv.nickname, undo = false), drain().single(), "$state")
            }
            assertNull(app.state.value.confirm)
            assertTrue(h.credential.prompts.isEmpty(), "$state: nothing asked")
            assertEquals(listOf(tv.id), h.tvs.tvs.value.map { it.id }, "$state: nothing deleted")
            assertTrue(h.engine.calls.none { it is FakeEngine.Call.Abandon }, "$state: the run is not abandoned")
            assertEquals(0, h.diagnosticsClears)
            assertEquals(state, h.engine.runState(tv.id).value)
        }
    }

    // S3: Delete everything deletes the app's diagnostics log too.
    @Test fun deleteEverythingClearsTheDiagnosticsLog() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.knownTv()
        val m = AppSettingsMachine(h.manageDeps(), backgroundScope)
        settle()
        m.dispatch(AppSettingsIntent.Ask(AppConfirm.DELETE_EVERYTHING))
        m.dispatch(AppSettingsIntent.DeleteEverything); settle()
        assertTrue(h.tvs.tvs.value.isEmpty())
        assertEquals(1, h.diagnosticsClears)
    }

    @Test fun resetMyKeyAlwaysPromptsAndClearsPasses() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.gate.remember(h.credential.mint(tv.id))
        val m = AppSettingsMachine(h.manageDeps(), backgroundScope)
        settle()
        assertEquals("TEE", m.state.value.keyBacking)
        m.dispatch(AppSettingsIntent.Ask(AppConfirm.RESET_KEY)); settle()
        assertEquals(AppConfirm.RESET_KEY, m.state.value.confirm)
        m.dispatch(AppSettingsIntent.ResetKey); settle()
        assertEquals(1, h.keyInfo.resets)
        assertEquals("GATE_SUBTITLE_RESET_KEY", h.credential.prompts.single().subtitle)
        assertNull(h.gate.cached(tv.id))
        m.dispatch(AppSettingsIntent.CheckUpdates); settle()
        assertEquals(UpdateCheck.Available("0.2.0"), m.state.value.update)
    }

    @Test fun manageShowsTheTvAndOpensSections() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        val m = ManageMachine(h.manageDeps(), backgroundScope, null)
        settle()
        val card = assertNotNull(m.state.value.current)
        assertEquals(1, card.switchedOff)
        assertTrue(card.clean)
        assertEquals(ChipState.CONNECTED, card.chip)
        m.effects.test {
            m.dispatch(ManageIntent.Open(ManageSection.SWITCHED_OFF)); settle()
            assertEquals(ManageEffect.Navigate("switchedoff/${tv.id.value}"), drain().single())
        }
    }

    @Test fun addModuleFetchesChecksTheSignerInstallsAndMirrorsTheKey() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, listOf(PermissionStatement("INTERNET", "Uses the internet")), "Fetches game files", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES games on the TV", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        h.registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        h.inspector = FakeApkInspector(mapOf(sha256Of(apk) to ApkInfo(retro.pkg, 1, "0.1.0", signer, sha256Of(apk), apk.size.toLong(), "test")))
        h.respond(PlanKind.ADD_MODULE, listOf(Op.Disable(adPkg)))
        val tv = h.knownTv()
        val m = ModulesMachine(h.manageDeps(FakeArtifactFetcher(h.blobs, mapOf(ref.url to apk))), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        assertEquals(listOf("retro"), m.state.value.available.map { it.id })
        m.dispatch(ModulesIntent.Open("retro")); settle()
        assertEquals("${signer.hex.substring(0, 4)} ${signer.hex.substring(4, 8)} ${signer.hex.substring(8, 12)}", m.state.value.detail?.key12)
        m.dispatch(ModulesIntent.Add("retro")); settle()
        val asking = assertIs<InstallStep.Asking>(m.state.value.install)
        assertEquals(keyDigest12(signer), asking.key12)
        assertTrue(h.planner.requests.any { it is PlanRequest.AddModule })
        h.cardAnswer = CardAnswer.SHOWN
        settle(1_100)
        assertIs<InstallStep.Shown>(m.state.value.install)
    }

    @Test fun aReSignedApkNeverReachesThePlanner() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, emptyList(), "", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        h.registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        val other = sha256Of("someone else".encodeToByteArray())
        h.inspector = FakeApkInspector(mapOf(sha256Of(apk) to ApkInfo(retro.pkg, 1, "0.1.0", other, sha256Of(apk), apk.size.toLong(), "test")))
        val tv = h.knownTv()
        val m = ModulesMachine(h.manageDeps(FakeArtifactFetcher(h.blobs, mapOf(ref.url to apk))), backgroundScope, tv.id)
        settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        assertEquals(FetchFailure.SIGNER_MISMATCH, assertIs<InstallStep.Failed>(m.state.value.install).fetch)
        assertTrue(h.planner.requests.none { it is PlanRequest.AddModule })
    }

    @Test fun gateKeeperCachesForTenMinutesPerTv() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val id = Samples.miTvId()
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertEquals(1, h.credential.prompts.size)
        h.clock.advance(10 * 60_000 + 1)
        assertNull(h.gate.cached(id))
        assertNotNull(h.gate.pass(id, "Mi TV 4A"))
        assertEquals(2, h.credential.prompts.size)
    }

    // RFC-W8-4: the app-level pass ("Reset my key", "Delete everything") is never cached.
    @Test fun theAppLevelPassIsNeverCached() = runTest(timeout = 30.seconds) {
        val h = Harness()
        assertNotNull(h.gate.passWithSubtitle(APP_GATE_ID, "Reset my key", forcePrompt = true))
        assertNull(h.gate.cached(APP_GATE_ID))
        assertNotNull(h.gate.passWithSubtitle(APP_GATE_ID, "Delete everything"))
        assertEquals(2, h.credential.prompts.size)
        assertNull(h.gate.cached(APP_GATE_ID))
    }

    @Test fun tvAccessShowsTheDebuggingStepUntilThePortOpens() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        var result: AccessResult? = null
        backgroundScope.launchAccess(h, tv) { result = it }
        settle()
        assertIs<Reach.NeedsDebugging>(h.access.reach.value)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    @Test fun tvJsonRoundTrips() {
        val record = Samples.tv().copy(wizard = WizardDraft(3, Level.CUSTOM, setOf(adPkg), setOf(promoPkg), listOf(promoPkg), "aurora", true, PlanId("p1"), 5))
        assertEquals(record, TvJson.decode(TvJson.encode(record)))
        assertEquals("0000 0000 0000", keyDigest12(Samples.zeroHash))
    }

    @Test fun operationNoticesFollowTheRun() {
        val plan = Harness().plan(listOf(Op.Disable(adPkg)), takeover = true)
        val running = tv.cleanyour.core.engine.RunState.Running(plan, 1, emptyList(), 58, null)
        assertEquals("NOTIFY_PROGRESS{percent=58,tv=Mi TV 4A}", OperationNotices.of(plan.tvId, "Mi TV 4A", running, KeyCopy)?.text)
        assertTrue(OperationNotices.needsForeground(plan))
        assertFalse(OperationNotices.needsForeground(Harness().plan(listOf(Op.Enable(adPkg)))))
    }

    // R5-W8 O2: a Manage write paused by a lost connection shows the open-run card, not the generic
    // "failed" snackbar; the next write on that TV shows the same card without a new plan or prompt;
    // "Carry on" continues the run with the pass and the card goes once it finishes.
    @Test fun aPausedWriteShowsTheOpenRunCardAndCarryOnFinishesIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg, promoPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, 0, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        val m = SwitchedOffMachine(d, backgroundScope, tv.id)
        settle()
        m.effects.test {
            m.dispatch(SwitchedOffIntent.PutBack(adPkg)); settle()
            assertTrue(drain().none { it is ManageEffect.Snackbar }, "no generic failed snackbar")
            val card = assertNotNull(d.openWrites.current.value)
            assertEquals(PlanKind.PUT_BACK, card.kind)
            assertEquals(PauseReason.CONNECTION_LOST, card.reason)
            assertEquals(tv.nickname, card.tvName)
            val starts = h.engine.calls.count { it is FakeEngine.Call.Start }
            d.openWrites.dismiss()
            m.dispatch(SwitchedOffIntent.PutBack(promoPkg)); settle()
            assertTrue(drain().none { it is ManageEffect.Snackbar })
            assertEquals(starts, h.engine.calls.count { it is FakeEngine.Call.Start }, "no new run while one is open")
            assertNotNull(d.openWrites.current.value, "the card is back for the next write")
        }
        val paused = h.engine.runState(tv.id).value as RunState.Paused
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.CARRY_ON) }
        settle()
        val cont = h.engine.calls.filterIsInstance<FakeEngine.Call.ContinueRun>().single()
        assertNotNull(cont.pass)
        assertNull(cont.refused)
        assertEquals(1, h.credential.prompts.size)   // one prompt for the TV (D17)
        assertTrue(assertNotNull(d.openWrites.current.value).busy)
        h.engine.emit(tv.id, RunState.Running(paused.plan, 0, emptyList(), 50, null)); settle()
        h.engine.finish(paused.plan, RunStatus.COMPLETED); settle()
        assertIs<WriteResult.Done>(result)
        assertNull(d.openWrites.current.value)
        assertTrue(h.planner.requests.any { it is PlanRequest.WriteRestorePoint })   // D44 once the run is over
    }

    // O2: "Undo what we did" undoes the open run; "Stop here" ends it; an action the engine does
    // not take (the session went away again) leaves the card with the reconnect line.
    @Test fun theOpenRunCardUndoesStopsAndSaysWhenTheTvDidNotAnswer() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        h.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, 0, PauseReason.STEP_FAILED, null)) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d).write(tv.id, PlanRequest.PutBack(adPkg)))
        val paused = h.engine.runState(tv.id).value as RunState.Paused
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.UNDO) }
        settle(TvWriter.MOVE_WAIT_MS + 100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun })
        assertIs<WriteResult.Paused>(result)
        assertTrue(assertNotNull(d.openWrites.current.value).notTaken)
        assertEquals(paused, h.engine.runState(tv.id).value)
        result = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.STOP_HERE) }
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertEquals(RunStatus.ABANDONED, assertIs<WriteResult.Done>(result).finished.status)
        assertNull(d.openWrites.current.value)
    }

    // QA of 0.4.5 (obs-unfinished): the TV changed after the read a send was planned from, so the
    // engine paused it STALE before step 0. The card said "We stopped partway through" though
    // nothing was done, and Carry on met the same check again at once, for good. Now the card knows
    // nothing was done, and Carry on ("Check again") ends that run, reads the TV afresh, plans the
    // same request from that read and runs the new plan; the old plan never runs.
    @Test fun aStalePauseBeforeTheFirstStepIsCheckedAgainAndPlannedFromAFreshRead() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        var made = 0
        val previous = h.planner.respond
        h.planner.respond = { r, snap ->
            if (r is PlanRequest.PutBack) PlanResult.Ok(h.plan(listOf(Op.Enable(adPkg)), kind = PlanKind.PUT_BACK, id = "01FAKEPUTB0000000000000" + (++made).toString().padStart(3, '0')))
            else previous(r, snap)
        }
        val stale = RunState.Paused(h.plan(listOf(Op.Enable(adPkg)), kind = PlanKind.PUT_BACK, id = "01FAKEPUTB0000000000000001"), 0, PauseReason.STALE, AdbError.Policy.Stale(listOf("com.miui.tv.analytics: DISABLED_USER -> ENABLED")))
        h.engine.onStart = { p -> if (p.id == stale.plan.id) emit(p.tvId, stale.copy(plan = p)) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d).write(tv.id, PlanRequest.PutBack(adPkg)))
        val card = assertNotNull(d.openWrites.current.value)
        assertEquals(PauseReason.STALE, card.reason)
        assertTrue(card.nothingDone, "no step was done, so the card never says we stopped partway")
        val reads = h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" }
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.CARRY_ON) }
        settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.ContinueRun }, "the stale plan is never carried on")
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Abandon })
        assertTrue(h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" } > reads, "the TV is read again")
        val starts = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().filter { it.plan.kind == PlanKind.PUT_BACK }
        assertEquals(listOf("01FAKEPUTB0000000000000001", "01FAKEPUTB0000000000000002"), starts.map { it.plan.id.value }, "a new plan from the fresh read")
        assertIs<WriteResult.Done>(result)
        assertEquals(stale.plan.id.value.dropLast(1) + "2", assertIs<WriteResult.Done>(result).plan.id.value)
        assertNull(d.openWrites.current.value)
    }

    // The same pause after a restart: the request is not known any more, so "Check again" ends the
    // run and reads the TV, and starts nothing; the next change is planned from that read.
    @Test fun aStalePauseWhoseRequestIsUnknownIsEndedAndTheTvRead() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val open = h.plan(listOf(Op.Disable(promoPkg)), kind = PlanKind.SWITCH_OFF, id = "01FAKEOPENRUN000000000001")
        h.engine.emit(tv.id, RunState.Paused(open, 0, PauseReason.STALE, null))
        val d = h.manageDeps()
        assertNotNull(TvWriter(d).openRun(tv, h.session))
        assertTrue(assertNotNull(d.openWrites.current.value).nothingDone)
        val reads = h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" }
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.CARRY_ON) }
        settle()
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.ContinueRun })
        assertEquals(RunStatus.ABANDONED, assertIs<WriteResult.Done>(result).finished.status)
        assertTrue(h.prober.calls.count { it == "snapshot ${SnapshotReason.MANUAL}" } > reads, "the TV is read again")
        assertTrue(h.engine.calls.none { it is FakeEngine.Call.Start })
        assertNull(d.openWrites.current.value)
    }

    // O2 after process death: the engine has not loaded the open run, so start says RUN_OPEN; the
    // writer resumes it (safety only) and shows its card instead of the failed snackbar.
    @Test fun aRunOpenRefusalLoadsTheRunThroughResumeAndShowsItsCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.ranDisable(tv, listOf(adPkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(adPkg)))
        val open = h.plan(listOf(Op.Disable(promoPkg)), kind = PlanKind.LEFTOVERS, id = "01FAKEOPENRUN000000000001")
        h.engine.emit(tv.id, RunState.Running(open, 0, emptyList(), 0, null))   // the engine says RUN_OPEN
        val d = h.manageDeps()
        backgroundScope.launch { TvWriter(d).write(tv.id, PlanRequest.PutBack(adPkg)) }
        settle()
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.Resume })
        h.engine.emit(tv.id, RunState.Paused(open, 0, PauseReason.IN_DOUBT, null))
        assertNotNull(TvWriter(d).openRun(tv, h.session))
        assertEquals(PlanKind.LEFTOVERS, assertNotNull(d.openWrites.current.value).kind)
    }

    // W3 R-12: the typed name is trimmed before it is kept.
    // The dock sheet with a row under the dock: the config.json Manage pushes carries the six apps
    // in order (the dock, then Row 2) and rows_below_dock 1, nothing else of ours; Manage reads the
    // choice back the way the TV shows it, so an app switched off since then is skipped.
    @Test fun dockRowsGoToTheTvAsOneListAndComeBackAsTheTvShowsThem() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash)).also { h.tvs.upsert(it) }
        val six = List(6) { PackageName("com.example.app$it") }
        fun state(on: Boolean) = PackageState(if (on) EnabledState.ENABLED else EnabledState.DISABLED_USER, false, 0, null, false, false, null, false)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + six.associateWith { state(true) }, takenAtMs = Samples.NOW_MS + 1))
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
        settle()
        assertEquals(4, m.state.value.columns)
        m.dispatch(HomeScreenIntent.SetDock(six + six.first())); settle()
        val apply = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().single()
        assertEquals(six, apply.doc.dockApps)
        assertEquals(1, apply.doc.rowsBelowDock)
        // The blob the plan pushes is exactly these bytes, and the launcher reads them back the same.
        val bytes = LauncherConfigCodec.encode(apply.doc)
        assertEquals(sha256Of(bytes), apply.config.sha256)
        val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).doc
        // RFC-CARDS-1: no kept choice and no overlay on the TV: Manage keeps the launcher's default, on.
        // 0.4.0: fill_dock false in every push.
        assertEquals(LauncherConfigDoc(dockApps = six, rowsBelowDock = 1, moduleCards = true, fillDock = false), back)
        assertEquals(six, m.state.value.dock.map { it.pkg })
        // Later the second app is switched off on the TV: the rest move up a slot, as on the TV.
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + six.associateWith { state(it != six[1]) }, takenAtMs = Samples.NOW_MS + 2))
        val again = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
        settle()
        assertEquals(six - six[1], again.state.value.dock.map { it.pkg })
    }

    // RF-LAUNCHER-250 F4: a new look, and "Restore mine", push the dock as it was saved, even with an
    // app of it switched off on the TV since (the sheet shows it without), so dock_apps stays the same
    // and the TV's own dock edit keeps applying (upstream #340). Up to 18 apps under Six columns.
    @Test fun aNewLookPushesTheSavedDockUnchanged() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        h.tvs.upsert(tv)
        val apps = List(18) { PackageName("com.example.app$it") }
        fun state(on: Boolean) = PackageState(if (on) EnabledState.ENABLED else EnabledState.DISABLED_USER, false, 0, null, false, false, null, false)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + apps.associateWith { state(it != apps[1]) }, takenAtMs = Samples.NOW_MS + 1))
        val sixColumns = Theme("six-columns", "Six columns", "Six smaller tiles", LauncherConfigDoc(columns = 6), null)
        val themes = FakeThemeSource(listOf(Theme("aurora", "Aurora", "Navy", LauncherConfigDoc(), null), sixColumns))
        val deps = h.manageDeps(encoder = ConfigEncoder.Codec, themes = themes)
        HomePrefs(h.documents).save(tv.id, HomeChoice(apps, "six-columns", cards = false))
        val m = HomeScreenMachine(deps, backgroundScope, tv.id)
        settle()
        assertEquals(apps - apps[1], m.state.value.dock.map { it.pkg }, "the sheet shows the dock as the TV does")
        m.dispatch(HomeScreenIntent.SetTheme("six-columns")); settle()
        assertEquals(apps, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.dockApps)
        m.dispatch(HomeScreenIntent.RestoreMine); settle()
        assertEquals(apps, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.dockApps)
        // A four-tile look holds 12: the TV gets the first 12 there, the stated limit. 0.4.0: the
        // saved dock keeps all 18 in order (DockLayers.keep), so Six columns gets them back.
        m.dispatch(HomeScreenIntent.SetTheme("aurora")); settle()
        assertEquals(apps.take(12), h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.dockApps)
        assertEquals(apps, HomePrefs(h.documents).load(tv.id)?.dock)
        m.dispatch(HomeScreenIntent.SetTheme("six-columns")); settle()
        assertEquals(apps, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.dockApps)
    }

    // RFC-CARDS-1: a TV set up with the new home screen and module cards off, Retro in the index.
    private suspend fun Harness.cardsOffTv(dock: List<PackageName>): Pair<Tv, Module> {
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, emptyList(), "", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES games on the TV", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        inspector = FakeApkInspector(mapOf(sha256Of(apk) to ApkInfo(retro.pkg, 1, "0.1.0", signer, sha256Of(apk), apk.size.toLong(), "test")))
        retroFetcher = FakeArtifactFetcher(blobs, mapOf(ref.url to apk))
        val tv = knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash))
        tvs.upsert(tv)
        HomePrefs(documents).save(tv.id, HomeChoice(dock, "aurora", cards = false))
        return tv to retro
    }
    private var retroFetcher: tv.cleanyour.core.registry.ArtifactFetcher? = null
    private fun Harness.cardsDeps() = manageDeps(retroFetcher ?: fetcher, encoder = ConfigEncoder.Codec)
    private fun Harness.pushedDoc(config: tv.cleanyour.core.model.BlobRef?): LauncherConfigDoc =
        assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(blobs.bytes(assertNotNull(config)))).doc

    // Adding a card is the person asking for cards: the AddModule plan itself carries the look with
    // module_cards on (the dock and theme kept for the TV), the choice is kept, and a later theme
    // change keeps it on.
    @Test fun addingACardTurnsModuleCardsOnInTheSamePlanAndALaterLookKeepsThem() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val dock = listOf(PackageName("com.netflix.ninja"), PackageName("com.google.android.youtube.tv"))
        val (tv, _) = h.cardsOffTv(dock)
        h.respond(PlanKind.ADD_MODULE, listOf(Op.Disable(adPkg)))
        val m = ModulesMachine(h.cardsDeps(), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        assertTrue(m.state.value.cardsOff)
        m.dispatch(ModulesIntent.Add("retro")); settle()
        assertIs<InstallStep.Asking>(m.state.value.install)
        val add = h.planner.requests.filterIsInstance<PlanRequest.AddModule>().single()
        val doc = h.pushedDoc(add.config)
        assertEquals(true, doc.moduleCards)
        assertEquals(dock, doc.dockApps)
        assertEquals(h.themes.bundled().single { it.id == "aurora" }.wallpaper, add.wallpaper)
        assertEquals(HomeChoice(dock, "aurora", cards = true), HomePrefs(h.documents).load(tv.id))
        assertEquals(add.config?.sha256, h.tvs.tvs.value.single().launcher?.ourOverlayDigest)
        assertFalse(m.state.value.cardsOff)
        // A new look later keeps the cards on.
        val home = HomeScreenMachine(h.cardsDeps(), backgroundScope, tv.id)
        settle()
        home.dispatch(HomeScreenIntent.SetTheme("aurora")); settle()
        assertEquals(true, h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().last().doc.moduleCards)
        assertEquals(true, HomePrefs(h.documents).load(tv.id)?.cards)
        // With the cards already on, the next add sends no look.
        m.dispatch(ModulesIntent.DismissInstall); settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        assertNull(h.planner.requests.filterIsInstance<PlanRequest.AddModule>().last().config)
    }

    // 0.4.0 (LooksTest 7): the cards push keeps the person's apps in a row and the whole saved dock,
    // not only the part the TV shows.
    @Test fun theCardsPushKeepsAppsInARowAndTheWholeDock() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apps = List(18) { PackageName("com.example.app$it") }
        val (tv, _) = h.cardsOffTv(apps)
        HomePrefs(h.documents).save(tv.id, HomeChoice(apps, "aurora", cards = false, columns = 5))
        h.respond(PlanKind.ADD_MODULE, listOf(Op.Disable(adPkg)))
        val m = ModulesMachine(h.cardsDeps(), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        val doc = h.pushedDoc(h.planner.requests.filterIsInstance<PlanRequest.AddModule>().single().config)
        assertEquals(5, doc.columns)
        assertEquals(apps.take(15), doc.dockApps, "5 a row shows 15 of the 18")
        assertEquals(false, doc.fillDock)
        assertEquals(HomeChoice(apps, "aurora", cards = true, columns = 5), HomePrefs(h.documents).load(tv.id))
    }

    // look2: a saved choice in the new form is pushed in its own form, with module_cards on, and
    // stays in the new form.
    @Test fun theCardsPushSendsANewFormChoiceInItsOwnForm() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apps = List(18) { PackageName("com.example.app$it") }
        val (tv, _) = h.cardsOffTv(apps)
        val choice = HomeChoice(apps, null, cards = false, picture = LookPicture.NONE_ID, columns = 5, style = "glass", movement = "smooth", lift = false,
            names = true)
        HomePrefs(h.documents).save(tv.id, choice)
        h.respond(PlanKind.ADD_MODULE, listOf(Op.Disable(adPkg)))
        val m = ModulesMachine(h.cardsDeps(), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        val doc = h.pushedDoc(h.planner.requests.filterIsInstance<PlanRequest.AddModule>().single().config)
        assertEquals(5, doc.columns)
        assertEquals(apps.take(15), doc.dockApps)
        assertEquals(true, doc.moduleCards)
        assertEquals("true", doc.unknown["dock_glass"]?.toString(), "Glass's band")
        assertEquals("true", doc.unknown["always_show_names"]?.toString())
        assertEquals(choice.copy(cards = true), HomePrefs(h.documents).load(tv.id))
    }

    // A card module already on the TV while the cards are off: "Show its card" sends the look with
    // module_cards on as an ApplyTheme plan, then mirrors the TV's question.
    @Test fun showItsCardTurnsModuleCardsOnAndMirrorsTheQuestion() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val (tv, retro) = h.cardsOffTv(emptyList())
        val m = ModulesMachine(h.cardsDeps(), backgroundScope, tv.id, pollMs = 1_000)
        settle()
        m.dispatch(ModulesIntent.ShowCard(retro.id)); settle()
        val apply = h.planner.requests.filterIsInstance<PlanRequest.ApplyTheme>().single()
        assertEquals(true, apply.doc.moduleCards)
        assertEquals(true, h.pushedDoc(apply.config).moduleCards)
        assertEquals(retro.pkg.value, assertIs<InstallStep.Asking>(m.state.value.install).pkg)
        assertEquals(true, HomePrefs(h.documents).load(tv.id)?.cards)
        assertFalse(m.state.value.cardsOff)
        assertNull(m.state.value.showing)
    }

    // Existing TVs: a kept choice wins; without one, the TV's overlay decides when it says
    // module_cards outright, whoever wrote it; otherwise the caller's default (Setup off, Manage on).
    @Test fun aTvWithoutAKeptChoiceKeepsWhatItsOverlaySays() {
        val dock = listOf(PackageName("com.netflix.ninja"))
        val noKey = LauncherConfigCodec.encode(LauncherConfigDoc(dockApps = dock))      // an older build's push, the owner's TV
        val otherOff = LauncherConfigCodec.encode(LauncherConfigDoc(dockApps = dock, moduleCards = false))
        val otherOn = LauncherConfigCodec.encode(LauncherConfigDoc(dockApps = dock, moduleCards = true))
        val garbage = "not json".encodeToByteArray()
        for ((name, default) in listOf("setup" to Cards.SETUP_DEFAULT, "manage" to Cards.MANAGE_DEFAULT)) {
            // A TV set up before RFC-DOCK-1, or never given a look: no overlay, the caller's default.
            assertEquals(default, Cards.of(null, null, default), "$name, no overlay")
            // Another phone's overlay with an explicit value, whatever the digest this phone remembers.
            assertFalse(Cards.of(null, otherOff, default), "$name, another phone's overlay with false")
            assertTrue(Cards.of(null, otherOn, default), "$name, another phone's overlay with true")
            // The owner's TV: the overlay Setup pushed before this change has no key, so the default.
            assertEquals(default, Cards.of(null, noKey, default), "$name, an overlay without the key")
            assertEquals(default, Cards.of(null, garbage, default), "$name, an unreadable overlay")
            // A kept choice wins over the overlay.
            assertFalse(Cards.of(false, otherOn, default), "$name, kept off")
            assertTrue(Cards.of(true, otherOff, default), "$name, kept on")
        }
        assertFalse(Cards.SETUP_DEFAULT, "a re-run Setup on the owner's TV stays off")
        assertTrue(Cards.MANAGE_DEFAULT, "a Manage look change never takes away a card the launcher shows")
    }

    @Test fun renameTrimsTheTypedName() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = TvSettingsMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(TvSettingsIntent.Rename("  Lounge TV  ", "  Lounge ")); settle()
        assertEquals("Lounge TV", h.tvs.tvs.value.single().nickname)
        assertEquals("Lounge", h.tvs.tvs.value.single().room)
    }

    // D4 (RFC-LAUNCHER-1): "Update the home screen" is offered while the TV has an older build of
    // ours, previews the plan from a fresh look at the TV, runs it, and pushes no look again: the
    // dock and look stay on the TV through an in-place update.
    @Test fun updateTheHomeScreenOffersPreviewsAndRunsInPlace() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(2, Samples.zeroHash, null, null))
        h.tvs.upsert(tv)
        val pin = Samples.launcherArtifact().copy(versionCode = 5, versionName = "2.4")
        fun launcher(vc: Long, signer: Sha256?) = PackageState(EnabledState.ENABLED, false, vc, signer, false, false, null, true)
        fun snapWith(vc: Long, signer: Sha256?, at: Long = Samples.NOW_MS + 1) =
            Samples.snapshot(packages = Samples.miTvPackages() + (pin.pkg to launcher(vc, signer)), takenAtMs = at)
        h.snapshots.save(snapWith(2, Samples.zeroHash))
        h.prober.snapshotFor = { snapWith(2, Samples.zeroHash, Samples.NOW_MS + 5) }
        val updatePlan = h.plan(listOf(Op.InstallStream(pin.blob, pin.sha256, pin.pkg, 5, pin.signer)), kind = PlanKind.UPDATE_LAUNCHER, id = "01FAKEUPDA0000000000000001")
        val previous = h.planner.respond
        h.planner.respond = { r, s -> if (r is PlanRequest.UpdateLauncher) PlanResult.Ok(updatePlan) else previous(r, s) }
        val deps = h.manageDeps(launcherPin = FakeLauncherPin(pin))
        val m = HomeScreenMachine(deps, backgroundScope, tv.id)
        settle()
        assertEquals("2.4", m.state.value.updateTo)
        assertFalse(m.state.value.tvSideSettings, "launcher 2.0 has no Launcher settings on the TV (RF-LAUNCHER-240 F16)")
        m.dispatch(HomeScreenIntent.PreviewUpdate); settle()
        assertEquals(updatePlan, m.state.value.updatePreview)
        assertTrue(h.prober.calls.any { it == "snapshot ${SnapshotReason.MANUAL}" }, "the preview reads the TV afresh")
        m.effects.test {
            m.dispatch(HomeScreenIntent.UpdateHomeScreen)
            settle()
            val snack = assertIs<ManageEffect.Snackbar>(drain().single())
            assertEquals(SnackKind.HOME_UPDATED, snack.kind)
            assertEquals("2.4", snack.name)
        }
        assertEquals(pin, h.planner.requests.filterIsInstance<PlanRequest.UpdateLauncher>().last().launcher)
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplyTheme }, "the look is not pushed again")
        assertNull(m.state.value.updatePreview)
        assertNull(m.state.value.updateTo)
        assertEquals(5L, h.tvs.tvs.value.single { it.id == tv.id }.launcher?.versionCode)

        // Not offered: the pin itself, another key, or a key nobody knows.
        for (snap in listOf(snapWith(5, Samples.zeroHash, Samples.NOW_MS + 10), snapWith(2, Sha256("e".repeat(64)), Samples.NOW_MS + 11))) {
            h.snapshots.save(snap)
            val state = HomeScreenMachine(deps, backgroundScope, tv.id).also { settle() }.state.value
            assertNull(state.updateTo)
            assertEquals(snap.packages.getValue(pin.pkg).versionCode >= 5, state.tvSideSettings)
        }
        h.tvs.upsert(tv.copy(launcher = null))
        h.snapshots.save(snapWith(2, null, Samples.NOW_MS + 20))
        assertNull(HomeScreenMachine(deps, backgroundScope, tv.id).also { settle() }.state.value.updateTo)
        // An older snapshot without the signer, but Setup recorded our key for this TV: offered.
        h.tvs.upsert(tv)
        assertEquals("2.4", HomeScreenMachine(deps, backgroundScope, tv.id).also { settle() }.state.value.updateTo)
    }

    // 0.3.3 D2 (issue #3, Hisense 32A4200G): Setup blocked the home switch, so the new home screen was
    // never installed. 0.3.2 still offered Dock and Look; DefaultPlanner.applyTheme refuses ApplyTheme
    // without the launcher ("the new home screen is not installed"), TvWriter turned that into
    // WriteResult.Refused and HomeScreenMachine.apply into SnackKind.FAILED, which the app shows as
    // "We stopped". Now the screen says why, from the stored decision, and sends nothing.
    @Test fun withoutTheNewHomeScreenTheScreenSaysWhyAndSendsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(profileId = null).also { h.tvs.upsert(it) }   // the miTv snapshot has no com.gathin.tvhome
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_STAYS_ON"))
        val previous = h.planner.respond
        h.planner.respond = { r, snap -> if (r is PlanRequest.ApplyTheme) PlanResult.Refused(listOf("the new home screen is not installed")) else previous(r, snap) }
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id)
        settle()
        val n = assertNotNull(m.state.value.notOnTv)
        assertEquals(HomeBlock.WIZARD_STAYS_ON, n.reason)
        assertTrue(n.unlisted && n.helpAddTv)
        assertFalse(n.setupAgain)
        m.effects.test {
            m.dispatch(HomeScreenIntent.SetTheme("aurora")); settle()
            m.dispatch(HomeScreenIntent.SetDock(listOf(PackageName("com.netflix.ninja")))); settle()
            m.dispatch(HomeScreenIntent.RestoreMine); settle()
            assertTrue(drain().isEmpty(), "no We stopped, no write")
        }
        m.effects.test {
            m.dispatch(HomeScreenIntent.RunSetupAgain); settle()
            assertTrue(drain().isEmpty(), "Setup again is not the next step here")
        }
        m.effects.test {
            m.dispatch(HomeScreenIntent.HelpAddTv); settle()
            assertEquals(listOf<ManageEffect>(ManageEffect.Navigate("report/tv/${tv.id.value}")), drain())
        }
        assertTrue(h.planner.requests.none { it is PlanRequest.ApplyTheme })
        // Manage's Home screen row says the TV keeps its own.
        val manage = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertTrue(assertNotNull(manage.state.value.current).ownHome)
    }

    // Issues #171, #187, #220: Setup left a launcher under our package name with another key as it is
    // (HOME_CHANGED). Manage's Home screen says why, as for issue #8, and offers no dock or look for
    // it. The same package with any other decision (ours, a planned switch) keeps the rows.
    @Test fun issue171And187And220ALauncherUnderOurNameThatSetupLeftIsNotOffered() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = null).also { h.tvs.upsert(it) }
        val theirs = Issue034.launcherState(Issue034.otherSigner)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (Issue034.gathinHome.pkg to theirs), takenAtMs = Samples.NOW_MS + 30))
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "HOME_CHANGED"))
        val n = assertNotNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
        assertEquals(HomeBlock.HOME_CHANGED, n.reason)
        HomeDecisions(h.documents).save(tv.id, HomeDecision(true))
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, alreadyOn = true))
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
    }

    // 0.3.3 D2: the reason and the next step for each stored decision. WIZARD_WIFI can change by
    // forgetting and adding the network again only on a TV an exact profile knows; on an unlisted TV
    // the wizard has to stay on anyway (HomeSwitchRisk), so the next step is a list.
    @Test fun theReasonAndTheNextStepFollowTheStoredDecision() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val listed = h.knownTv()   // exact profile xiaomi-mitv-axso0
        suspend fun notOnTv(decision: HomeDecision?, profileId: String? = listed.profileId): NotOnTv? {
            h.tvs.upsert(listed.copy(profileId = profileId))
            if (decision != null) HomeDecisions(h.documents).save(listed.id, decision) else h.documents.delete("tvs/${listed.id.value}/decision.txt")
            return HomeScreenMachine(h.manageDeps(), backgroundScope, listed.id).also { settle() }.state.value.notOnTv
        }
        assertEquals(NotOnTv(HomeBlock.WIZARD_WIFI, "Xiaomi", setupAgain = true), notOnTv(HomeDecision(false, "WIZARD_WIFI")))
        assertEquals(NotOnTv(HomeBlock.WIZARD_WIFI, "Xiaomi", unlisted = true, helpAddTv = true), notOnTv(HomeDecision(false, "WIZARD_WIFI"), profileId = null))
        assertEquals(NotOnTv(HomeBlock.WIZARD_WIFI_UNKNOWN, "Xiaomi", setupAgain = true), notOnTv(HomeDecision(false, "WIZARD_WIFI_UNKNOWN")))
        assertEquals(NotOnTv(HomeBlock.UNKNOWN_HOME, "Xiaomi", unlisted = true, helpAddTv = true), notOnTv(HomeDecision(false, "UNKNOWN_HOME"), profileId = null))
        assertEquals(NotOnTv(HomeBlock.UNKNOWN_HOME, "Xiaomi"), notOnTv(HomeDecision(false, "UNKNOWN_HOME")))
        assertEquals(NotOnTv(HomeBlock.TV_REFUSES, "Xiaomi"), notOnTv(HomeDecision(false, "TV_REFUSES")))
        assertEquals(NotOnTv(HomeBlock.OLD_ANDROID, "Xiaomi"), notOnTv(HomeDecision(false, "OLD_ANDROID")))
        // Issue #8: the home screen was already changed; making the TV's own Home again is the person's
        // choice, and on a TV with an exact list Setup then offers the switch.
        assertEquals(NotOnTv(HomeBlock.HOME_CHANGED, "Xiaomi", setupAgain = true), notOnTv(HomeDecision(false, "HOME_CHANGED")))
        assertEquals(NotOnTv(HomeBlock.HOME_CHANGED, "Xiaomi", unlisted = true), notOnTv(HomeDecision(false, "HOME_CHANGED"), profileId = null))
        // Planned, but the launcher is gone since (removed): Setup again adds it. No decision (a TV set
        // up before 0.3.2): the same.
        assertEquals(NotOnTv(null, "Xiaomi", setupAgain = true), notOnTv(HomeDecision(true)))
        assertEquals(NotOnTv(null, "Xiaomi", setupAgain = true), notOnTv(null))
        val m = HomeScreenMachine(h.manageDeps(), backgroundScope, listed.id).also { settle() }
        m.effects.test {
            m.dispatch(HomeScreenIntent.RunSetupAgain); settle()
            assertEquals(listOf<ManageEffect>(ManageEffect.Navigate("setup/${listed.id.value}")), drain())
        }
        // Fire TV is named for Fire OS, not the panel's brand (issue #2).
        h.snapshots.save(Samples.snapshot(facts = Samples.miTvFacts().copy(brand = "Insignia", manufacturer = "Amazon"), takenAtMs = Samples.NOW_MS + 1))
        assertEquals(NotOnTv(HomeBlock.TV_REFUSES, "Insignia", fireTv = true), notOnTv(HomeDecision(false, "TV_REFUSES")))
        // The new home screen on the TV: Setup recorded it, or the newest snapshot lists it (installed,
        // perhaps not the home screen now): the dock and the look are there as before.
        h.tvs.upsert(listed.copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash)))
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, listed.id).also { settle() }.state.value.notOnTv)
        h.tvs.upsert(listed)
        val ours = PackageState(EnabledState.ENABLED, false, 1, null, false, false, null, true)
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (Launcher.pkg to ours), takenAtMs = Samples.NOW_MS + 2))
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, listed.id).also { settle() }.state.value.notOnTv)
        assertFalse(assertNotNull(ManageMachine(h.manageDeps(), backgroundScope, listed.id).also { settle() }.state.value.current).ownHome)
    }

    // 0.4.6: on a connect Manage reads the TV's Home again; with ours Home, the TV's own home screen
    // off and the setup wizard now a Home claimant, the card offers "Switch it back on", which is the
    // existing Use the TV's own home screen with the stock home the switch armed its watchdog with.
    @Test fun aSetupWizardThatClaimsHomeUnderOursIsToldOnConnectAndSwitchesTheTvsOwnBackOn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val stock = Samples.stockLauncher
        val token = RunToken("00000000000000aa")
        val plan = h.plan(listOf(Op.ArmHomeWatchdog(stock, 150, token), Op.Disable(stock.pkg), Op.SetHome(Launcher.home)), id = "01FAKERUN0000000000000009")
        h.ledger.record(plan)
        h.journal.append(JournalRecord.PlanStart(plan.id, tv.id, Samples.zeroHash, 3, plan.hash, "0.1.0", 0))
        plan.steps.forEach { st ->
            h.journal.append(JournalRecord.Begin(plan.id, st.index, st.op, listOf("fake"), if (st.op is Op.SetHome) Prior(home = stock) else null, 10L + st.index))
            h.journal.append(JournalRecord.Done(plan.id, st.index, 0, true, 5))
        }
        h.journal.append(JournalRecord.End(plan.id, RunStatus.COMPLETED))
        val recovery = Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity")
        h.prober.home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0), HomeClaimant(recovery, 0)))
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED) }
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertTrue(assertNotNull(m.state.value.current).wizardHome)
        assertTrue("homeNow" in h.prober.calls)
        // Once the TV's own home screen is back the row goes.
        h.prober.home = HomeResolution(stock, listOf(HomeClaimant(stock, 2), HomeClaimant(recovery, 0)))
        m.dispatch(ManageIntent.SwitchStockHomeOn); settle()
        assertEquals(PlanRequest.UseStockHome(stock), h.planner.requests.filterIsInstance<PlanRequest.UseStockHome>().single())
        assertFalse(assertNotNull(m.state.value.current).wizardHome)
        assertNull(h.tvs.tvs.value.single { it.id == tv.id }.launcher, "the TV record says the new home screen is no longer Home")
        // Without a wizard among the claimants there is nothing to say, and the button does nothing.
        val quiet = Harness()
        val tv2 = quiet.knownTv()
        quiet.prober.home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0)))
        val m2 = ManageMachine(quiet.manageDeps(), backgroundScope, tv2.id)
        settle()
        assertFalse(assertNotNull(m2.state.value.current).wizardHome)
        m2.dispatch(ManageIntent.SwitchStockHomeOn); settle()
        assertTrue(quiet.planner.requests.none { it is PlanRequest.UseStockHome })
    }

    // 0.4.6: a put back whose app took the Home button pauses at the Home check after it
    // (DefaultPlanner.putBack). The open run card names the app, and its "Switch it off again" is
    // the run's own undo; a put back that paused anywhere else is the ordinary card.
    @Test fun aPutBackThatTookTheHomeButtonSaysSoAndItsUndoSwitchesTheAppOffAgain() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = LauncherState(1, Samples.zeroHash, Samples.zeroHash, Samples.zeroHash)).also { h.tvs.upsert(it) }
        val stock = Samples.stockLauncher
        h.ranDisable(tv, listOf(stock.pkg))
        h.respond(PlanKind.PUT_BACK, listOf(Op.Enable(stock.pkg), Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home)))
        var at = 1
        h.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, at, PauseReason.STEP_FAILED, AdbError.Policy.GuardFailed(-1, "home"))) else finish(p, RunStatus.COMPLETED) }
        val d = h.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d).write(tv.id, PlanRequest.PutBack(stock.pkg)))
        val card = assertNotNull(d.openWrites.current.value)
        assertEquals(d.nameOf(stock.pkg), card.tookHome)
        var result: WriteResult? = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.UNDO) }
        settle(TvWriter.MOVE_WAIT_MS + 100)
        assertTrue(h.engine.calls.any { it is FakeEngine.Call.UndoRun }, "switch it off again is the run's undo")
        assertTrue(h.planner.requests.none { it is PlanRequest.SwitchOff }, "never a new switch-off plan, which would refuse a home screen")
        assertIs<WriteResult.Paused>(result)   // the fake engine never moves on: the card stays, with the reconnect line
        assertEquals(d.nameOf(stock.pkg), assertNotNull(d.openWrites.current.value).tookHome)
        assertNotNull(h.tvs.tvs.value.single { it.id == tv.id }.launcher, "undo keeps the new home screen in charge")
        // "Keep it" ends the run there, and the TV record says the new home screen is no longer Home.
        result = null
        backgroundScope.launch { result = TvWriter(d).resolveOpen(tv.id, OpenWriteAction.STOP_HERE) }
        settle()
        assertEquals(RunStatus.ABANDONED, assertIs<WriteResult.Done>(result).finished.status)
        assertNull(h.tvs.tvs.value.single { it.id == tv.id }.launcher)
        // Paused at the switch-on itself: the ordinary card.
        val other = Harness()
        val tv2 = other.knownTv()
        other.ranDisable(tv2, listOf(stock.pkg))
        other.respond(PlanKind.PUT_BACK, listOf(Op.Enable(stock.pkg), Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home)))
        at = 0
        other.engine.onStart = h.engine.onStart
        val d2 = other.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d2).write(tv2.id, PlanRequest.PutBack(stock.pkg)))
        assertNull(assertNotNull(d2.openWrites.current.value).tookHome)
        // Paused at the Home check for another reason (the TV refused set-home-activity): the ordinary
        // card too; only Home answering to another app (the verify's GuardFailed) is "took Home".
        val refused = Harness()
        val tv3 = refused.knownTv()
        refused.ranDisable(tv3, listOf(stock.pkg))
        refused.respond(PlanKind.PUT_BACK, listOf(Op.Enable(stock.pkg), Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home)))
        refused.engine.onStart = { p -> if (p.kind == PlanKind.PUT_BACK) emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "refused"))) else finish(p, RunStatus.COMPLETED) }
        val d3 = refused.manageDeps()
        assertIs<WriteResult.Paused>(TvWriter(d3).write(tv3.id, PlanRequest.PutBack(stock.pkg)))
        assertNull(assertNotNull(d3.openWrites.current.value).tookHome)
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchAccess(h: Harness, tv: Tv, done: (AccessResult) -> Unit) =
    launch { done(h.access.session(tv.id)) }
