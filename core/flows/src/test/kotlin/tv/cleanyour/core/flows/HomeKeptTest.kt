package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.planner.PatchWallFeeds
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.Issue034
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.7 (UX-FINDINGS, PM-FINDINGS): the "home screen kept" state the screens read (HomeKept), the
// home screen's own rows by role, the installed-but-not-in-use state on the Home screen page and
// Manage, the restore point's gating, the Wi-Fi card's buttons, Done without a run, and the memory
// of a sent TV report (SentReports).
@OptIn(ExperimentalCoroutinesApi::class)
class HomeKeptTest {

    private val wizard = PackageName("com.google.android.tungsten.setupwraith")

    @Test fun theStateComesFromTheBlockOrTheFailedSwitch() {
        assertNull(SetupExtras().homeKept)
        assertEquals(HomeKept.Blocked(HomeBlock.CASTING, brand = "Google"), SetupExtras(homePossible = false, homeBlock = HomeBlock.CASTING, brand = "Google").homeKept)
        assertEquals(HomeKept.Blocked(HomeBlock.TV_REFUSES, fireTv = true, brand = "Insignia"),
            SetupExtras(homePossible = false, homeBlock = HomeBlock.TV_REFUSES, fireTv = true, brand = "Insignia").homeKept)
        // No exact list, or a test list tried: a list is what would change it.
        assertEquals(HomeKept.Blocked(HomeBlock.WIZARD_STAYS_ON, unlisted = true), SetupExtras(homePossible = false, homeBlock = HomeBlock.WIZARD_STAYS_ON, unknownModel = true).homeKept)
        assertEquals(HomeKept.Blocked(HomeBlock.WIZARD_WIFI, unlisted = true), SetupExtras(homePossible = false, homeBlock = HomeBlock.WIZARD_WIFI, candidateTried = true).homeKept)
        // The switch was tried and the TV's own home screen is back: that comes first.
        assertEquals(HomeKept.SwitchFailed(HomeOutcome.TIMED_OUT), SetupExtras(doneHomeKept = HomeOutcome.TIMED_OUT).homeKept)
        assertEquals(HomeKept.SwitchFailed(HomeOutcome.VERIFY_FAILED), SetupExtras(homePossible = false, homeBlock = HomeBlock.CASTING, doneHomeKept = HomeOutcome.VERIFY_FAILED).homeKept)
    }

    @Test fun aListChangesOnlyTheBlocksAboutTheTvsOwnHomeScreenOrWizard() {
        assertTrue(HomeKept.needsList(HomeBlock.UNKNOWN_HOME, unlisted = true))
        assertTrue(HomeKept.needsList(HomeBlock.WIZARD_STAYS_ON, unlisted = false))
        assertTrue(HomeKept.needsList(HomeBlock.WIZARD_WIFI, unlisted = true))
        assertFalse(HomeKept.needsList(HomeBlock.WIZARD_WIFI, unlisted = false))
        for (b in listOf(HomeBlock.TV_REFUSES, HomeBlock.OLD_ANDROID, HomeBlock.CASTING, HomeBlock.BOOT_LOOP, HomeBlock.REMOTE_BUTTONS, HomeBlock.HOME_CHANGED,
            HomeBlock.OTHER_HOME, HomeBlock.VENDOR_HOME, HomeBlock.PATCHWALL_FEEDS, HomeBlock.NONE)) assertFalse(HomeKept.needsList(b, unlisted = true), b.name)
    }

    @Test fun theHomeScreensOwnRowsAreNamedByRole() {
        val stock = Samples.stockLauncher.pkg
        val roles = homeRoles(wizard, listOf(stock), listOf(stock, Launcher.pkg, PackageName("com.android.tv.setupwraith")))
        assertEquals(HomeRole.OWN_HOME, roles[stock])
        assertEquals(HomeRole.WIZARD, roles[wizard])
        assertEquals(HomeRole.WIZARD, roles[PackageName("com.android.tv.setupwraith")])
        assertNull(roles[Launcher.pkg])
        assertTrue(homeRoles(null, emptyList(), emptyList()).isEmpty())
    }

    // Setup fills the extras the Customise sheet needs: the TV's brand from its facts, the roles of
    // its wizard and home screen, and PatchWall's part in the decision.
    @Test fun setupFillsTheBrandTheRolesAndPatchWall() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val patchWall = PatchWallFeeds.PATCHWALL
        val on = PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false)
        h.prober.snapshotFor = { r ->
            Samples.miTvSnapshot(reason = r).let { s ->
                s.copy(packages = s.packages + (wizard to on) + (patchWall to on),
                    home = HomeResolution(Samples.stockLauncher, listOf(HomeClaimant(Samples.stockLauncher, 2), HomeClaimant(Component(wizard, ".Setup"), 1))))
            }.let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        }
        h.profiles.resolver = { _, _ -> ProfileResolution.Exact(miProfile.copy(setupWraith = wizard)) }
        val m = SetupMachine(h.setupDeps(savedWifi = { SavedWifi(true, 1, setOf("android.uid.system:1000")) }), backgroundScope)
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        assertIs<SetupState.Found>(m.state.value)
        val e = m.extras.value
        assertEquals(Samples.miTvFacts().brand, e.brand)
        assertEquals(HomeRole.WIZARD, e.homeRoles[wizard])
        assertEquals(HomeRole.OWN_HOME, e.homeRoles[Samples.stockLauncher.pkg])
        assertEquals(patchWall, e.patchWall)
        // The Mi TV fixture has Mi TV+ off (not by this app), so keeping PatchWall on keeps the TV's
        // own home screen too, and Setup decided PATCHWALL_FEEDS for that reason.
        assertTrue(e.patchWallHoldsHome)
        assertEquals(HomeBlock.PATCHWALL_FEEDS, e.homeBlock)
    }

    // UX finding 16: on the Wi-Fi card the first button keeps the TV's own home screen and goes to
    // Done; the second tries the switch again once the person has done what the card asks.
    @Test fun theWifiCardKeepsTheTvsOwnHomeScreenFirstAndTriesAgainSecond() = runTest(timeout = 30.seconds) {
        for ((wifi, secondKey) in listOf(
            SavedWifi(true, 1, setOf("${wizard.value}:10031")) to "STOP_HOME_SETUPWRAITH_WIFI_SECONDARY",
            SavedWifi(false, 0, emptySet()) to "STOP_HOME_USER_SAID_NO_PRIMARY",
        )) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.prober.snapshotFor = { r ->
                Samples.miTvSnapshot(reason = r).let { s ->
                    s.copy(packages = s.packages + (wizard to PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false)),
                        home = s.home.copy(claimants = s.home.claimants + HomeClaimant(Component(wizard, ".Setup"), 1)))
                }.let { it.copy(contentHash = SnapshotCodec.hash(it)) }
            }
            // Each plan request gets a plan of its own (as the real planner does), so the second run's
            // end is a new value and not one the state flow already delivered.
            var made = 0
            h.planner.respond = { request, _ ->
                if (request is tv.cleanyour.core.planner.PlanRequest.Setup) tv.cleanyour.core.planner.PlanResult.Ok(h.plan(listOf(Op.Disable(adPkg)), takeover = true, id = "01FAKEPLAN000000000000000${++made}"))
                else tv.cleanyour.core.planner.PlanResult.Ok(h.plan(emptyList(), kind = PlanKind.WRITE_RESTORE_POINT, id = "01FAKEKIND0000000000000099"))
            }
            h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED) else finish(p, RunStatus.COMPLETED) }
            val m = SetupMachine(h.setupDeps(savedWifi = { wifi }), backgroundScope)
            h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
            m.dispatch(SetupIntent.Start); settle()
            h.discovery.open5555[HOST] = true
            settle(2_100); settle(700)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val s = assertIs<SetupState.Stopped>(m.state.value)
            assertEquals(StopCase.HOME_SETUPWRAITH, s.card.case)
            assertEquals("STOP_HOME_SETUPWRAITH_SECONDARY", s.card.primary, "$wifi")   // Keep the TV's own home screen
            assertEquals(secondKey, s.card.secondary, "$wifi")
            // The second button plans afresh and starts the switch once more, and the same failure
            // shows the same card again.
            m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
            val starts = h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().filter { it.plan.kind == PlanKind.SETUP }
            assertEquals(2, starts.size, "$wifi")
            assertIs<SetupState.Stopped>(m.state.value, "$wifi")
            // The first keeps the TV's own home screen: Done, with the failed switch as its news.
            m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
            val d = assertIs<SetupState.Done>(m.state.value, "$wifi")
            assertFalse(d.summary.homeScreenOn)
            assertEquals(HomeKept.SwitchFailed(HomeOutcome.VERIFY_FAILED), m.extras.value.homeKept)
        }
    }

    // UX finding 5, PM 7: Done on a plan with nothing to do ends the wizard without a run, a
    // credential prompt or a Done screen, and the draft is cleared so the app does not reopen it.
    @Test fun leaveAsIsEndsSetupWithoutARun() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(emptyList())
        val m = h.setup(backgroundScope)
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        assertIs<SetupState.Found>(m.state.value)
        assertNotNull(h.tvs.tvs.value.single().wizard)
        m.effects.test {
            // Finish's own steps follow (debugging off, with its probes a second apart).
            m.dispatch(SetupIntent.LeaveAsIs); settle(3_500)
            assertEquals(listOf<SetupEffect>(SetupEffect.Navigate("manage/${Samples.miTvId().value}")), drain().filterIsInstance<SetupEffect.Navigate>())
        }
        // No Setup run (Finish's own debugging-off plan is the only start).
        assertTrue(h.engine.calls.filterIsInstance<FakeEngine.Call.Start>().none { it.plan.kind == PlanKind.SETUP })
        assertNull(h.tvs.tvs.value.single().wizard)
        assertTrue(h.logLines.any { it == "setup: left as is, nothing to switch off" })
    }

    // UX finding 24: the restart is recommended for the wizard quirk only when the switch ran; a
    // kept home screen on a reboot tested profile has nothing a restart could check.
    @Test fun theRestartIsRecommendedForTheWizardQuirkOnlyWhenTheSwitchRan() = runTest(timeout = 30.seconds) {
        for ((takeover, expected) in listOf(true to true, false to false)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.profiles.resolver = { _, _ -> ProfileResolution.Exact(miProfile.copy(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), tested = TestedInfo("user", null, 7, rebootTested = true))) }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = takeover)
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED, if (takeover) HomeOutcome.CONFIRMED else null) }
            val m = h.setup(backgroundScope)
            h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
            m.dispatch(SetupIntent.Start); settle()
            h.discovery.open5555[HOST] = true
            settle(2_100); settle(700)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val d = assertIs<SetupState.Done>(m.state.value)
            assertEquals(expected, d.summary.restartRecommended, "takeover=$takeover")
        }
    }

    // 0.4.9 (issue #3870): without a home switch the restart is Optional, also on a profile whose restart
    // was never tested and on a TV without a list; with one it stays recommended there.
    @Test fun withoutAHomeSwitchTheRestartIsOptional() = runTest(timeout = 30.seconds) {
        for ((profiled, takeover) in listOf(true to false, false to false, true to true)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            if (!profiled) h.profiles.resolver = { _, _ -> ProfileResolution.None(listOf(Samples.stockLauncher)) }
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = takeover)
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED, if (takeover) HomeOutcome.CONFIRMED else null) }
            val m = h.setup(backgroundScope)
            h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
            m.dispatch(SetupIntent.Start); settle()
            h.discovery.open5555[HOST] = true
            settle(2_100); settle(700)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val d = assertIs<SetupState.Done>(m.state.value)
            assertEquals(takeover, d.summary.homeScreenOn)
            assertEquals(takeover, d.summary.restartRecommended, "profiled=$profiled takeover=$takeover")
        }
    }

    private fun ours(signer: Sha256?) = Issue034.launcherState(signer)

    private var snapshotsSaved = 0
    private suspend fun Harness.snapshotWithOurs(signer: Sha256?, home: Component = Samples.stockLauncher) {
        // Each save is the newest read (the store's latest is the one with the newest takenAtMs).
        snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (Launcher.pkg to ours(signer)),
            home = HomeResolution(home, listOf(HomeClaimant(home, 2))), takenAtMs = Samples.NOW_MS + 30 + ++snapshotsSaved))
    }

    // UX finding 8: our launcher on the TV with our key, Home answering to the TV's own home screen
    // (the switch was tried and did not hold): installed, not in use, with "Try the switch again".
    // A launcher under our name with another key, or one that is Home, keeps the rows.
    @Test fun installedButNotInUseLeadsToTheSwitchAgain() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = null).also { h.tvs.upsert(it) }
        HomeDecisions(h.documents).save(tv.id, HomeDecision(true))
        h.snapshotWithOurs(Samples.zeroHash)
        val m = HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }
        assertEquals(NotOnTv(null, "Xiaomi", setupAgain = true, installed = true), m.state.value.notOnTv)
        m.effects.test {
            m.dispatch(HomeScreenIntent.RunSetupAgain); settle()
            assertEquals(listOf<ManageEffect>(ManageEffect.Navigate("setup/${tv.id.value}")), drain())
        }
        // Manage's Home screen row says the TV's own is in use.
        val manage = ManageMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }
        val card = assertNotNull(manage.state.value.current)
        assertTrue(card.homeInstalledNotInUse)
        assertFalse(card.ownHome)
        // The signer the probe could not read counts as ours; another key does not.
        h.snapshotWithOurs(null)
        assertEquals(true, HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv?.installed)
        h.snapshotWithOurs(Issue034.otherSigner)
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
        assertFalse(assertNotNull(ManageMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.current).homeInstalledNotInUse)
        // Home answering to ours: in use, the rows stay.
        h.snapshotWithOurs(Samples.zeroHash, home = Launcher.home)
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
        assertFalse(assertNotNull(ManageMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.current).homeInstalledNotInUse)
    }

    // UX finding 17: "Before a factory reset" only when this app switched the wizard off, and the
    // undo preview asks about the new home screen only when it is on the TV.
    @Test fun theRestorePointOffersOnlyWhatHappened() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        // The TV's list: a Google TV with a setup wizard (Manage reads it by the record's profile id).
        val profiles = FakeProfileSource(listOf(miProfile.copy(platform = Platform.GOOGLE_TV, setupWraith = wizard)))
        assertFalse(RestorePointMachine(h.manageDeps(profiles = profiles), backgroundScope, tv.id).also { settle() }.state.value.wizardOff)
        // The wizard switched off by this app: the row shows.
        val plan = h.plan(listOf(Op.Disable(wizard)), id = "01FAKERUN0000000000000009")
        h.ledger.record(plan)
        h.journal.append(JournalRecord.PlanStart(plan.id, tv.id, Samples.zeroHash, 1, plan.hash, "0.1.0", 0))
        h.journal.append(JournalRecord.Begin(plan.id, 0, Op.Disable(wizard), listOf("fake"), null, 10))
        h.journal.append(JournalRecord.Done(plan.id, 0, 0, true, 5))
        h.journal.append(JournalRecord.End(plan.id, RunStatus.COMPLETED))
        val m = RestorePointMachine(h.manageDeps(profiles = profiles), backgroundScope, tv.id).also { settle() }
        assertTrue(m.state.value.wizardOff)
        // The miTv snapshot has no new home screen: no choice about it in the preview.
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        assertFalse(assertIs<RestorePhase.Preview>(m.state.value.phase).preview.launcherInstalled)
        h.snapshotWithOurs(Samples.zeroHash)
        m.dispatch(RestorePointIntent.Cancel); settle()
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        assertTrue(assertIs<RestorePhase.Preview>(m.state.value.phase).preview.launcherInstalled)
    }

    @Test fun sentReportsRoundTripAndTakeOnlyAnIssueOfThisApp() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val store = SentReports(h.documents)
        val id = Samples.miTvId()
        assertNull(store.load(id))
        store.save(id, SentReport(1_700_000_000_000))
        assertEquals(SentReport(1_700_000_000_000), store.load(id))
        store.save(id, SentReport(1_700_000_000_000, "https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368"))
        assertEquals(SentReport(1_700_000_000_000, "https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368"), store.load(id))
        assertEquals("https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368", SentReports.issueUrl(" https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368#issuecomment-1 "))
        assertEquals("#1368", SentReports.issueNumber("https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368"))
        assertNull(SentReports.issueUrl("https://github.com/someone/else/issues/1"))
        assertNull(SentReports.issueUrl("https://github.com/udhaykumarbala/cleanyourtv-app/pull/12"))
        assertNull(SentReports.issueUrl("1368"))
    }

    // must 4: the TV report remembers it was sent (copied and opened, or shared), keeps the issue
    // link once pasted, and the Home screen page, Manage and Setup read the date.
    @Test fun theTvReportRemembersItWasSentAndItsIssue() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(profileId = null).also { h.tvs.upsert(it) }
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_STAYS_ON", app = "0.2.0", profile = "none"))
        h.profiles.resolver = { _, _ -> ProfileResolution.None(emptyList()) }
        val m = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.TV, tv.id).also { settle() }
        assertNull(m.state.value.sent)
        m.effects.test { m.dispatch(ReportIntent.CopyAndOpen); settle(); assertEquals(2, drain().size) }
        assertEquals(SentReport(Samples.NOW_MS), m.state.value.sent)
        assertEquals(SentReport(Samples.NOW_MS), SentReports(h.documents).load(tv.id))
        // A pasted link that is not an issue of this app is refused and nothing is kept.
        m.dispatch(ReportIntent.SaveIssue("https://example.com")); settle()
        assertTrue(m.state.value.issueError)
        assertNull(m.state.value.sent?.issueUrl)
        m.dispatch(ReportIntent.SaveIssue("https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368")); settle()
        assertFalse(m.state.value.issueError)
        assertEquals("https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368", m.state.value.sent?.issueUrl)
        m.effects.test {
            m.dispatch(ReportIntent.OpenIssue); settle()
            assertEquals(listOf<ManageEffect>(ManageEffect.OpenUrl("https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368")), drain())
        }
        // Sending again keeps the link and moves the date.
        h.clock.setWall(Samples.NOW_MS + 5_000)
        m.effects.test { m.dispatch(ReportIntent.Share); settle(); assertEquals(1, drain().size) }
        assertEquals(SentReport(Samples.NOW_MS + 5_000, "https://github.com/udhaykumarbala/cleanyourtv-app/issues/1368"), SentReports(h.documents).load(tv.id))
        // The Home screen page and Manage carry the date.
        assertEquals(Samples.NOW_MS + 5_000, HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.notOnTv?.reportSentAtMs)
        assertEquals(Samples.NOW_MS + 5_000, assertNotNull(ManageMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.current).reportSentAtMs)
        // A problem report is not a TV report: nothing is remembered for it.
        val problem = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.PROBLEM, tv.id).also { settle() }
        h.clock.setWall(Samples.NOW_MS + 9_000)
        problem.dispatch(ReportIntent.CopyAndOpen); settle()
        assertEquals(Samples.NOW_MS + 5_000, SentReports(h.documents).load(tv.id)?.atMs)
    }

    // UX finding 19: a look send that paused (the open-run card) keeps the draft, as a failed one does.
    @Test fun aPausedLookSendKeepsTheDraft() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.tvs.upsert(tv.copy(launcher = LauncherState(1, Samples.zeroHash, null, null)))
        h.snapshotWithOurs(Samples.zeroHash, home = Launcher.home)
        h.engine.onStart = { p -> if (p.kind == PlanKind.THEME) emit(p.tvId, RunState.Paused(p, 0, PauseReason.STEP_FAILED, null)) else finish(p, RunStatus.COMPLETED) }
        val m = HomeScreenMachine(h.manageDeps(encoder = ConfigEncoder.Codec), backgroundScope, tv.id).also { settle() }
        m.dispatch(HomeScreenIntent.OpenLook); settle()
        assertNotNull(m.state.value.look)
        m.dispatch(HomeScreenIntent.UseLook); settle(2_000)
        assertTrue(m.state.value.lookFailed, "the draft is kept for Open Look")
        assertFalse(m.state.value.lookSending)
    }
}

