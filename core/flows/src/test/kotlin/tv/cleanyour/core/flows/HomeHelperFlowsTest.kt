package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.testing.fixtures.Issue034
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.HomeHelperKeys
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// RFC-HELPER-1 (test build): the Home button helper in Setup (Customise's switch, the plan and
// its look, the decision line), Manage (its card and Switch off, a look sent while it is on) and
// the TV report (PLAN 4.6, 4.7). A release build offers nothing.
@OptIn(ExperimentalCoroutinesApi::class)
class HomeHelperFlowsTest {
    private val service = HelperLines.SERVICE.flat
    private val reader = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    private fun Snapshot.helper(list: String?, on: String?, overlay: ByteArray? = null, at: Long = Samples.NOW_MS + 10): Snapshot =
        copy(settings = settings + mapOf(SettingKey.ENABLED_ACCESSIBILITY to list, SettingKey.ACCESSIBILITY_ENABLED to on), launcherOverlay = overlay, takenAtMs = at)
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    private val redirectOverlay = LauncherConfigCodec.encode(HomeHelperKeys.with(LauncherConfigDoc(columns = 5), KnownLaunchers.TVLAUNCHER.pkg))

    @Test fun setupOffersTheHelperOnlyInTheTestBuildAndPlansItWithItsLook() = runTest(timeout = 30.seconds) {
        for (build in listOf(false, true)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val m = SetupMachine(h.setupDeps(homeHelper = build), backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            assertEquals(build, m.extras.value.helperOffer, "offered in the test build only")
            assertFalse(m.extras.value.helperOn)
            m.dispatch(SetupIntent.HomeHelper(true)); settle()
            val req = h.planner.requests.filterIsInstance<PlanRequest.Setup>().last()
            assertEquals(build, req.choices.homeHelper)
            assertEquals(build, m.extras.value.helperOn)
            if (!build) continue
            assertFalse(req.choices.takeover, "the helper instead of the switch")
            assertTrue(req.choices.installLauncher)
            val config = assertNotNull(req.config, "the look goes with the helper")
            val text = h.blobs.bytes(config).decodeToString()
            assertTrue("\"stock_home_redirect\": \"com.google.android.tvlauncher\"" in text, text)
            assertTrue("\"key_filter\": false" in text, text)
            assertEquals("home=redirect", h.documents.read("tvs/${Samples.miTvId().value}/decision.txt") { it }?.decodeToString()?.lines()?.first())
            // Switched off again: the plan goes back to the TV's rules.
            m.dispatch(SetupIntent.HomeHelper(false)); settle()
            assertFalse(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.homeHelper)
        }
    }

    // S3 in Setup: accessibility off with another service listed, so the row is not offered.
    @Test fun setupDoesNotOfferItWhenAnotherServiceWouldBind() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.snapshotFor = { r -> Samples.miTvSnapshot(r).helper(reader, "0", at = Samples.NOW_MS) }
        val m = SetupMachine(h.setupDeps(homeHelper = true), backgroundScope, SetupStart.Fresh)
        toFound(h, m)
        assertFalse(m.extras.value.helperOffer)
        m.dispatch(SetupIntent.HomeHelper(true)); settle()
        assertFalse(h.planner.requests.filterIsInstance<PlanRequest.Setup>().last().choices.homeHelper)
    }

    // 0.4.9 (issue #4288, a TCL BeyondTV4 on beta2): an earlier run's new home screen, still Home, hid the
    // helper with no word why; it showed only after Undo everything. Customise now says why (helperWhyNot),
    // and the log names it once. A release build says nothing.
    @Test fun setupSaysWhyTheHelperIsNotOfferedWhileTheNewHomeScreenIsHome() = runTest(timeout = 30.seconds) {
        for (build in listOf(false, true)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            val ours = tv.cleanyour.core.model.Component(Samples.launcherArtifact().pkg, ".MainActivity")
            h.prober.snapshotFor = { r -> Samples.miTvSnapshot(r).let { s -> s.copy(home = HomeResolution(ours, listOf(HomeClaimant(ours, 999)) + s.home.claimants)) }
                .let { it.copy(contentHash = SnapshotCodec.hash(it)) } }
            val m = SetupMachine(h.setupDeps(homeHelper = build), backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            assertFalse(m.extras.value.helperOffer)
            assertEquals(if (build) HelperWhyNot.NEW_HOME_ON else null, m.extras.value.helperWhyNot)
            assertEquals(if (build) listOf("setup: home button helper not offered: NEW_HOME_ON") else emptyList(),
                h.logLines.filter { it.startsWith("setup: home button helper") })
        }
    }

    @Test fun whyNotIsEmptyWhereTheHelperIsOfferedAndNamesTheAccessibilityRule() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = SetupMachine(h.setupDeps(homeHelper = true), backgroundScope, SetupStart.Fresh)
        toFound(h, m)
        assertTrue(m.extras.value.helperOffer)
        assertNull(m.extras.value.helperWhyNot)
        val other = Harness()
        other.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        other.prober.snapshotFor = { r -> Samples.miTvSnapshot(r).helper(reader, "0", at = Samples.NOW_MS) }
        val n = SetupMachine(other.setupDeps(homeHelper = true), backgroundScope, SetupStart.Fresh)
        toFound(other, n)
        assertEquals(HelperWhyNot.ACCESSIBILITY, n.extras.value.helperWhyNot)
    }

    @Test fun everyRefusalHasAReason() {
        for (r in tv.cleanyour.core.planner.HomeHelper.Refusal.entries) assertNotNull(HelperWhyNot.of(r))
        assertEquals(HelperWhyNot.HOME_NOT_GOOGLE, HelperWhyNot.of(tv.cleanyour.core.planner.HomeHelper.Refusal.NOT_GOOGLE_HOME))
        assertEquals(HelperWhyNot.TV_CANNOT, HelperWhyNot.of(tv.cleanyour.core.planner.HomeHelper.Refusal.NO_INSTALLS))
    }

    @Test fun manageShowsTheHelperAndSwitchesItOff() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot().helper("$reader:$service", "1", redirectOverlay))
        assertNull(HomeScreenMachine(h.manageDeps(), backgroundScope, tv.id).also { settle() }.state.value.helper, "a release build shows nothing")
        val m = HomeScreenMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id)
        settle()
        assertEquals(HelperCard(HelperShown.ON, googleTv = false), m.state.value.helper)
        m.dispatch(HomeScreenIntent.HelperOff); settle(500)
        assertTrue(PlanRequest.HomeHelperOff in h.planner.requests, h.planner.requests.toString())
    }

    // 0.4.8 (issue #2825, TCL G06 beta3: HOME_HELPER_OFF_PAUSED_STALE at step 0): Switch off plans from a fresh read
    // of the TV. Its prior is the whole accessibility list, which had changed since the stored snapshot (restarts).
    @Test fun switchOffPlansFromWhatTheTvHasNow() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot().helper("$reader:$service", "1", redirectOverlay))
        h.prober.snapshotFor = { Samples.miTvSnapshot(reason = it).helper(service, "1", redirectOverlay, at = Samples.NOW_MS + 20) }
        val planned = mutableListOf<String?>()
        val previous = h.planner.respond
        h.planner.respond = { r, snap -> if (r is PlanRequest.HomeHelperOff) planned += snap.settings[SettingKey.ENABLED_ACCESSIBILITY]; previous(r, snap) }
        val m = HomeScreenMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }
        m.dispatch(HomeScreenIntent.HelperOff); settle(500)
        assertEquals(listOf<String?>(service), planned)
    }

    // The same for Undo everything, preview and run: taking our helper out has the list as its prior.
    @Test fun undoEverythingPlansFromWhatTheTvHasNow() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot().helper("$reader:$service", "1", redirectOverlay))
        h.prober.snapshotFor = { Samples.miTvSnapshot(reason = it).helper(service, "1", redirectOverlay, at = Samples.NOW_MS + 20) }
        val planned = mutableListOf<String?>()
        h.planner.respond = { r, snap ->
            if (r is PlanRequest.UndoEverything) planned += snap.settings[SettingKey.ENABLED_ACCESSIBILITY]
            PlanResult.Ok(h.plan(listOf(Op.AccessibilityRemove(service, HelperLines.SERVICE)), kind = PlanKind.UNDO_EVERYTHING, id = "01FAKEUNDO0000000000000001"))
        }
        val m = RestorePointMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        m.dispatch(RestorePointIntent.Confirm); settle()
        m.dispatch(RestorePointIntent.PutEverythingBack); settle()
        assertEquals(listOf<String?>(service, service), planned)
    }

    // 0.4.7: with the helper chosen the plan is a redirect, so no screen says the TV keeps its own home screen;
    // a helper that did not hold is this run's news, as for the switch.
    @Test fun theHelperIsNotAKeptHomeScreen() {
        val casting = SetupExtras(homePossible = false, homeBlock = HomeBlock.CASTING)
        assertEquals(HomeKept.Blocked(HomeBlock.CASTING), HomeKept.of(casting))
        assertNull(HomeKept.of(casting.copy(helperOffer = true, helperOn = true)))
        assertNull(HomeKept.of(SetupExtras(homePossible = false, homeBlock = HomeBlock.NONE, helperOn = true)))
        assertEquals(HomeKept.SwitchFailed(tv.cleanyour.core.engine.HomeOutcome.TIMED_OUT),
            HomeKept.of(casting.copy(helperOn = true, doneHomeKept = tv.cleanyour.core.engine.HomeOutcome.TIMED_OUT)))
    }

    // 0.4.7-beta1 QA (S4): the helper on, the TV's own home screen Home on purpose and no launcher on the TV record:
    // the Home screen page is not "installed, not in use"; it has the helper's card, Dock and Look. Manage's card says
    // the helper's line, and the Cards row is not "this TV keeps its own home screen".
    @Test fun withTheHelperOnTheHomeScreenPageShowsItsCardNotInstalledButNotInUse() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = null).also { h.tvs.upsert(it) }
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, redirect = true))
        val stock = Samples.stockLauncher
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + (Launcher.pkg to Issue034.launcherState(Samples.zeroHash)),
            home = HomeResolution(stock, listOf(HomeClaimant(stock, 2))), takenAtMs = Samples.NOW_MS + 40)
            .helper(service, "1", redirectOverlay, at = Samples.NOW_MS + 40))
        val m = HomeScreenMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }
        assertNull(m.state.value.notOnTv)
        assertEquals(HelperShown.ON, m.state.value.helper?.shown)
        val card = assertNotNull(ManageMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }.state.value.current)
        assertFalse(card.homeInstalledNotInUse)
        assertTrue(card.homeHelperOn)
    }

    // 0.4.7-beta1 QA: the engine finishes a confirmed section with no HomeOutcome, so Done takes that for the switch or
    // the helper held: "New home screen on" and the launcher on the TV record (Manage reads it).
    @Test fun aConfirmedSectionWithNoOutcomeIsTheNewHomeScreenOn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED, null) }
        val m = SetupMachine(h.setupDeps(), backgroundScope, SetupStart.Fresh)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val d = kotlin.test.assertIs<SetupState.Done>(m.state.value)
        assertTrue(d.summary.homeScreenOn)
        assertNull(m.extras.value.doneHomeKept)
        assertNotNull(h.tvs.tvs.value.single().launcher)
    }

    // Undo everything with the helper on says it switches it off (the plan takes our entry out).
    @Test fun theUndoPreviewNamesTheHelper() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val previous = h.planner.respond
        h.planner.respond = { r, snap -> if (r is PlanRequest.UndoEverything) PlanResult.Ok(h.plan(listOf(Op.AccessibilityRemove(null, HelperLines.SERVICE)),
            kind = PlanKind.UNDO_EVERYTHING, id = "01FAKEUNDO0000000000000001")) else previous(r, snap) }
        val m = RestorePointMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }
        m.dispatch(RestorePointIntent.PreviewUndo); settle()
        assertTrue(kotlin.test.assertIs<RestorePhase.Preview>(m.state.value.phase).preview.helperOff)
    }

    @Test fun theCardSaysWhatTheTvShows() {
        val base = Samples.miTvSnapshot()
        assertEquals(HelperShown.OFF_BY_TV, HomeHelperFlows.card(base.helper(service, "0", redirectOverlay), true)?.shown)
        assertEquals(HelperShown.NO_REDIRECT, HomeHelperFlows.card(base.helper(service, "1", null), true)?.shown)
        assertEquals(HelperShown.REDIRECT_ONLY, HomeHelperFlows.card(base.helper(reader, "1", redirectOverlay), true)?.shown)
        assertEquals(false, HomeHelperFlows.card(base.helper(reader, "1", redirectOverlay), true)?.canSwitchOff)
        assertNull(HomeHelperFlows.card(base.helper(reader, "1", null), true))
        assertNull(HomeHelperFlows.card(base.helper(service, "1", redirectOverlay), false))
        val launcherx = LauncherConfigCodec.encode(HomeHelperKeys.with(LauncherConfigDoc(), KnownLaunchers.LAUNCHERX.pkg))
        assertEquals(true, HomeHelperFlows.card(base.helper(service, "1", launcherx), true)?.googleTv)
    }

    // A look sent from Manage while the helper is listed keeps its keys; after it is off they go.
    @Test fun aLookSentWhileTheHelperIsOnKeepsItsKeys() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.snapshots.save(Samples.miTvSnapshot().helper(service, "1", redirectOverlay))
        val doc = HomeHelperFlows.carry(h.manageDeps(), tv.id)(LauncherConfigDoc(columns = 6))
        assertEquals(KnownLaunchers.TVLAUNCHER.pkg, HomeHelperKeys.redirectOf(doc))
        assertEquals(false, HomeHelperKeys.keyFilterOf(doc))
        h.snapshots.save(Samples.miTvSnapshot().helper(reader, "1", redirectOverlay, at = Samples.NOW_MS + 20))
        assertNull(HomeHelperKeys.redirectOf(HomeHelperFlows.carry(h.manageDeps(), tv.id)(LauncherConfigDoc(columns = 6))))
    }

    // 0.4.7 (RFC-THEME-1): a TV whose launcher folder the shell cannot read (the look went through the provider)
    // gives no overlay; with the helper listed, the TV's own Google home screen in charge is the redirect kept.
    @Test fun aLookKeepsTheRedirectWhenTheOverlayCouldNotBeRead() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val own = Samples.miTvSnapshot().helper(service, "1", null)
        h.snapshots.save(own.copy(home = own.home.copy(resolved = KnownLaunchers.TVLAUNCHER)).let { it.copy(contentHash = SnapshotCodec.hash(it)) })
        val doc = HomeHelperFlows.carry(h.manageDeps(), tv.id)(LauncherConfigDoc(columns = 6))
        assertEquals(KnownLaunchers.TVLAUNCHER.pkg, HomeHelperKeys.redirectOf(doc))
        assertEquals(false, HomeHelperKeys.keyFilterOf(doc))
        // Not listed: nothing is added.
        h.snapshots.save(Samples.miTvSnapshot().helper(reader, "1", null, at = Samples.NOW_MS + 20))
        assertNull(HomeHelperKeys.redirectOf(HomeHelperFlows.carry(h.manageDeps(), tv.id)(LauncherConfigDoc(columns = 6))))
    }

    @Test fun theReportHasTheAccessibilitySectionInTheTestBuild() {
        val about = AppAbout("0.4.6", "full", "Pixel", "Android 15")
        val snap = Samples.miTvSnapshot().helper("$reader:$service", "1", redirectOverlay)
        val text = TvReport.text(TvReportInput(about, snap, null, HomeDecision(false, redirect = true), accessibility = true))
        val section = text.substringAfter("\nAccessibility\n").substringBefore("\n\n").lines()
        assertEquals(listOf(
            "Switched on: yes",
            "Services listed: 2 (the new home screen's Home button helper, 1 other, not named)",
            "Home button helper: listed, accessibility on",
            "Home redirect: com.google.android.tvlauncher",
            "Key filter: off",
            "Button map: none",
        ), section)
        assertTrue("Home switch: redirect" in text)
        assertFalse("talkback" in text, "another app's service is never named")
        val plain = TvReport.text(TvReportInput(about, Samples.miTvSnapshot(), null, null, accessibility = false))
        assertFalse("\nAccessibility\n" in plain, "a release report is as before")
        assertFalse("\nAccessibility\n" in TvReport.text(TvReportInput(about, Samples.miTvSnapshot(), null, null, accessibility = true)),
            "nothing read about accessibility, no section")
        val none = TvReport.text(TvReportInput(about, Samples.miTvSnapshot().helper(null, null), null, null, accessibility = true))
        assertTrue("Services listed: none" in none && "Home button helper: not listed" in none && "Switched on: unknown" in none, none)
        assertFalse("other services listed" in text || "other services listed" in none, "only when the new home screen has another entry")
    }

    // 0.5.0 (issues #3075, #3287, #5468): Customise refused the helper with one unnamed service listed and
    // accessibility off (S3). Whether that entry is the new home screen's own InputKeyService, the report could
    // not say. It names entries of the launcher's package now; other apps' stay a count.
    @Test fun theReportNamesTheNewHomeScreensOtherServicesOnly() {
        val about = AppAbout("0.5.0", "full", "Pixel", "Android 15")
        val input = "com.gathin.tvhome/.InputKeyService"
        val text = TvReport.text(TvReportInput(about, Samples.miTvSnapshot().helper("$reader:$input", "0"), null, null, accessibility = true))
        val section = text.substringAfter("\nAccessibility\n").substringBefore("\n\n").lines()
        assertEquals(listOf("Switched on: no", "Services listed: 2 (not named)", "The new home screen's other services listed: $input",
            "Home button helper: not listed"), section.take(4))
        assertFalse("talkback" in text, "another app's service is never named")
        val both = TvReport.text(TvReportInput(about, Samples.miTvSnapshot().helper("$service:com.gathin.tvhome/com.gathin.tvhome.InputKeyService", "1"), null, null, accessibility = true))
        assertTrue("Services listed: 2 (the new home screen's Home button helper, 1 other, not named)\n" +
            "The new home screen's other services listed: com.gathin.tvhome/com.gathin.tvhome.InputKeyService\n" in both, both)
    }
}
