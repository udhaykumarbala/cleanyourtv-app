package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.probe.AccessibilityNow
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.8 (PLAN items 1, 2 and 4): the restart count, the confirmation it feeds and Manage's own reconnect, with fakes
// for a held restart, a failed one, a TV that was never switched and the Home button helper.
@OptIn(ExperimentalCoroutinesApi::class)
class RestartCountTest {
    private val stock = Samples.stockLauncher
    private val ours = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0), HomeClaimant(stock, 2)))
    private val theirs = HomeResolution(stock, listOf(HomeClaimant(stock, 2), HomeClaimant(Launcher.home, 0)))
    private val launcher = LauncherState(1, Samples.zeroHash, null, null)
    private val build = "0.4.8 (full, test build)"
    private val helperService = "com.gathin.tvhome/.HomeKeyService"
    private val talkback = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    @Test fun aHeldRestartIsCountedOnceAcrossTwoBoots() = runTest(timeout = 30.seconds) {
        val r = Restarts(MemoryDocumentStore())
        val id = Samples.miTvId()
        assertEquals(RestartRead.FIRST, r.observe(id, "boot-1", ours, HomePath.LIST, build, 1_000).read)
        assertEquals(RestartRead.SAME_BOOT, r.observe(id, "boot-1", ours, HomePath.LIST, build, 2_000).read, "the same boot is no restart")
        val held = r.observe(id, "boot-2", ours, HomePath.LIST, build, 3_000)
        assertEquals(RestartRead.HELD, held.read)
        assertEquals(listOf<Any?>(1, 0, 3_000L, 3_000L, build, false), held.tally.let { listOf(it.held, it.failed, it.firstAtMs, it.lastAtMs, it.build, it.lastFailed) })
        val again = r.observe(id, "boot-3", ours, HomePath.LIST, "0.4.9 (full, release)", 9_000)
        assertEquals(listOf<Any?>(2, 3_000L, 9_000L, "0.4.9 (full, release)"), again.tally.let { listOf(it.held, it.firstAtMs, it.lastAtMs, it.build) })
        assertEquals(again.tally, r.load(id), "kept on the phone")
    }

    @Test fun aFailedRestartCountsOnceAndNothingAfterItUntilTheNewHomeScreenIsBack() = runTest(timeout = 30.seconds) {
        val r = Restarts(MemoryDocumentStore())
        val id = Samples.miTvId()
        r.observe(id, "boot-1", ours, HomePath.R0, build, 1_000)
        val failed = r.observe(id, "boot-2", theirs, HomePath.R0, build, 2_000)
        assertEquals(RestartRead.FAILED, failed.read)
        assertEquals(listOf(0, 1), listOf(failed.tally.held, failed.tally.failed))
        assertTrue(failed.tally.lastFailed)
        // The TV's own home screen is still Home at the next boot: that restart had nothing to hold.
        assertEquals(RestartRead.NOT_COUNTED, r.observe(id, "boot-3", theirs, HomePath.R0, build, 3_000).read)
        // Setup run again (same boot: the new home screen answers again), then a restart that holds.
        assertEquals(RestartRead.SAME_BOOT, r.observe(id, "boot-3", ours, HomePath.R0, build, 4_000).read)
        val held = r.observe(id, "boot-4", ours, HomePath.R0, build, 5_000)
        assertEquals(listOf<Any?>(RestartRead.HELD, 1, 1, false), listOf(held.read, held.tally.held, held.tally.failed, held.tally.lastFailed))
    }

    @Test fun anotherPathStartsANewCount() = runTest(timeout = 30.seconds) {
        val r = Restarts(MemoryDocumentStore())
        val id = Samples.miTvId()
        r.observe(id, "boot-1", theirs, HomePath.HELPER, build, 1_000)
        assertEquals(1, r.observe(id, "boot-2", theirs, HomePath.HELPER, build, 2_000).tally.held)
        val switched = r.observe(id, "boot-2", ours, HomePath.LIST, build, 3_000)
        assertEquals(RestartRead.FIRST, switched.read)
        assertEquals(listOf<Any?>(0, 0, HomePath.LIST), listOf(switched.tally.held, switched.tally.failed, switched.tally.path))
    }

    // The path: a TV this app never switched has none, so Manage reads nothing more there.
    @Test fun thePathFollowsWhatThisAppPutOnTheTv() {
        val tv = Samples.tv()
        val snap = Samples.miTvSnapshot()
        assertNull(HomePath.of(tv, snap, null), "never switched")
        assertEquals(HomePath.LIST, HomePath.of(tv.copy(launcher = launcher), snap, HomeDecision(true, profile = "exact xiaomi-mitv-axso0")))
        assertEquals(HomePath.R0, HomePath.of(tv.copy(launcher = launcher, profileId = null), snap, HomeDecision(true, profile = "brand set Xiaomi")))
        assertEquals(HomePath.LIST, HomePath.of(tv.copy(launcher = launcher, profileId = null), snap, HomeDecision(true, profile = "candidate tcl-x")))
        val helper = snap.copy(settings = mapOf(SettingKey.ENABLED_ACCESSIBILITY to "com.gathin.tvhome/.HomeKeyService", SettingKey.ACCESSIBILITY_ENABLED to "1"))
        assertEquals(HomePath.HELPER, HomePath.of(tv, helper, null), "our service listed")
        assertEquals(HomePath.HELPER, HomePath.of(tv.copy(launcher = launcher), snap, HomeDecision(false, redirect = true)), "a read from before the helper")
        assertNull(HomePath.of(tv.copy(launcher = null), snap, HomeDecision(false, redirect = true)), "the TV's own home screen chosen")
        // The helper: Home answers to the Google home screen it sends on from, with ours still a claimant.
        assertTrue(Restarts.answers(HomePath.HELPER, theirs))
        assertFalse(Restarts.answers(HomePath.HELPER, HomeResolution(stock, listOf(HomeClaimant(stock, 2)))), "ours gone")
        assertFalse(Restarts.answers(HomePath.LIST, theirs))
    }

    private suspend fun Harness.switchedTv(): tv.cleanyour.core.model.Tv {
        val t = knownTv().copy(launcher = launcher)
        tvs.upsert(t)
        HomeDecisions(documents).save(t.id, HomeDecision(true, profile = "exact xiaomi-mitv-axso0"))
        return t
    }

    @Test fun manageCountsAHeldRestartOnConnectAndOffersTheConfirmation() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        h.prober.home = ours
        h.prober.bootIdValue = "boot-1"
        val d = h.manageDeps(about = AppAbout("0.4.8", "full", "Pixel 8", "Android 15", testBuild = true))
        val m = ManageMachine(d, backgroundScope, tv.id)
        settle()
        assertEquals(0, assertNotNull(m.state.value.current?.restarts).held, "the first read is the baseline")
        // The TV restarted: Manage's next connect reads a new boot with the new home screen answering.
        h.prober.bootIdValue = "boot-2"
        m.dispatch(ManageIntent.Select(tv.id)); settle()
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf<Any?>(1, 0, "0.4.8 (full, test build)"), listOf(r.held, r.failed, r.build))
        assertEquals(listOf("manage: restart held (list), 1 held, 0 failed"), h.logLines.filter { it.startsWith("manage: restart") })
        // Only boot_id beside what Manage read before 0.4.8 (the screenshot and Home).
        assertEquals(setOf("screenshot", "homeNow", "bootId"), h.prober.calls.toSet())
        m.effects.test {
            m.dispatch(ManageIntent.TellUsItWorked); settle()
            assertEquals(ManageEffect.Navigate("report/confirm/${tv.id.value}"), drain().single())
        }
        // The problem report carries the same Restarts lines.
        val input = assertNotNull(ProblemReports.input(d, tv.id))
        assertTrue("Held: 1" in TvReport.text(input).lines())
    }

    @Test fun manageCountsAFailedRestartAndOffersAProblemReport() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        h.prober.home = ours
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        h.prober.home = theirs
        h.prober.bootIdValue = "boot-2"
        m.dispatch(ManageIntent.Select(tv.id)); settle()
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf<Any?>(0, 1, true), listOf(r.held, r.failed, r.lastFailed))
        m.effects.test {
            m.dispatch(ManageIntent.TellUsItWorked); settle()
            m.dispatch(ManageIntent.ReportRestartFailed("The TV's own home screen came back after a restart.")); settle()
            val nav = assertIs<ManageEffect.Navigate>(drain().single(), "no confirmation without a held restart")
            val (id, ctx) = assertNotNull(IssueRoute.parse(nav.key))
            assertEquals(tv.id, id)
            assertEquals(listOf("Manage", ItWorked.RESTART_FAILED), listOf(ctx.screen, ctx.case))
        }
    }

    @Test fun aTvThisAppNeverSwitchedIsNotCountedAndItsBootIsNotRead() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        h.prober.bootIdValue = "boot-2"
        m.dispatch(ManageIntent.Select(tv.id)); settle()
        assertNull(m.state.value.current?.restarts)
        assertTrue("bootId" !in h.prober.calls)
        assertNull(Restarts(h.documents).load(tv.id))
    }

    @Test fun theHelperPathCountsWhileHomeAnswersToTheTvsOwn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(launcher = launcher)
        h.tvs.upsert(tv)
        h.snapshots.save(Samples.miTvSnapshot().copy(takenAtMs = Samples.NOW_MS + 1,
            settings = mapOf(SettingKey.ENABLED_ACCESSIBILITY to "com.gathin.tvhome/.HomeKeyService", SettingKey.ACCESSIBILITY_ENABLED to "1"))
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) })
        h.prober.home = theirs
        h.prober.accessibilityNow = AccessibilityNow("1", helperService)
        val m = ManageMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id)
        settle()
        h.prober.bootIdValue = "boot-2"
        m.dispatch(ManageIntent.Select(tv.id)); settle()
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf<Any?>(HomePath.HELPER, 1, 0), listOf(r.path, r.held, r.failed))
        assertTrue("accessibility" in h.prober.calls)
    }

    // 0.4.8 (issues #2061, #1799, #1650, #947): Home alone counted the helper held while it was off. Its two settings
    // are read with Home: listed with accessibility off is not counted yet (Android binds it about 30 s after a boot,
    // #1622) and a later read in the same boot counts it; not listed is a failed restart.
    private suspend fun kotlinx.coroutines.test.TestScope.helperTv(h: Harness): ManageMachine {
        val tv = h.knownTv().copy(launcher = launcher)
        h.tvs.upsert(tv)
        h.snapshots.save(Samples.miTvSnapshot().copy(takenAtMs = Samples.NOW_MS + 1,
            settings = mapOf(SettingKey.ENABLED_ACCESSIBILITY to helperService, SettingKey.ACCESSIBILITY_ENABLED to "1"))
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) })
        h.prober.home = theirs
        h.prober.accessibilityNow = AccessibilityNow("1", helperService)
        return ManageMachine(h.manageDeps(homeHelper = true), backgroundScope, tv.id).also { settle() }
    }

    @Test fun aHelperListedButNotBoundYetIsCountedOnceItIsOn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = helperTv(h)
        val id = assertNotNull(m.state.value.current).id
        h.prober.bootIdValue = "boot-2"
        h.prober.accessibilityNow = AccessibilityNow("0", helperService)
        m.dispatch(ManageIntent.Select(id)); settle()
        assertEquals(0, Restarts(h.documents).load(id)?.counted, "not counted while accessibility is off")
        h.prober.accessibilityNow = AccessibilityNow("1", "$talkback:$helperService")
        m.dispatch(ManageIntent.Select(id)); settle()
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf(1, 0), listOf(r.held, r.failed))
    }

    @Test fun aHelperNoLongerListedIsAFailedRestart() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val m = helperTv(h)
        val id = assertNotNull(m.state.value.current).id
        h.prober.bootIdValue = "boot-2"
        h.prober.accessibilityNow = AccessibilityNow("1", talkback)
        m.dispatch(ManageIntent.Select(id)); settle()
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf(0, 1), listOf(r.held, r.failed))
        assertTrue(h.logLines.any { it.startsWith("manage: restart failed (helper): the helper is no longer listed") }, h.logLines.toString())
        // A TV that cannot say counts nothing.
        assertEquals(Restarts.HelperNow.NOT_YET, Restarts.helperNow(AccessibilityNow(null, helperService)))
        assertEquals(Restarts.HelperNow.NOT_LISTED, Restarts.helperNow(AccessibilityNow("1", null)))
        assertEquals(Restarts.HelperNow.ON, Restarts.helperNow(AccessibilityNow("1", helperService)))
    }

    // PLAN item 2: the block the issue bot reads, with the title, in a link far under the cap, and nothing the
    // problem report leaves out.
    @Test fun theConfirmationIsTheFixedBlockInItsForm() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        val plan = h.plan(listOf(Op.PushAsset(BlobRef(Samples.zeroHash, 10, "blob:config"), config, Samples.zeroHash, FileRoute.THEME_PROVIDER)),
            kind = PlanKind.THEME, id = "01FAKERUN0000000000000020")
        h.ledger.record(plan)
        h.journal.append(JournalRecord.PlanStart(plan.id, tv.id, Samples.zeroHash, 1, plan.hash, "0.1.0", 0))
        h.journal.append(JournalRecord.Begin(plan.id, 0, plan.steps[0].op, listOf("fake"), null, 10))
        h.journal.append(JournalRecord.Done(plan.id, 0, 0, true, 5))
        h.journal.append(JournalRecord.End(plan.id, RunStatus.COMPLETED))
        val restarts = Restarts(h.documents)
        restarts.observe(tv.id, "boot-1", ours, HomePath.LIST, build, Samples.NOW_MS)
        restarts.observe(tv.id, "boot-2", ours, HomePath.LIST, build, Samples.NOW_MS + 86_400_000)
        val about = AppAbout("0.4.8", "full", "Pixel 8", "Android 15", testBuild = true)
        val d = h.manageDeps(about = about)
        val report = ReportMachine(d, backgroundScope, ReportKind.CONFIRM, tv.id)
        settle()
        val s = report.state.value
        assertTrue(s.available)
        val expected = """
            Clean Your TV: confirmation
            Format: 1
            App: 0.4.8 (full)
            Build kind: test build
            Phone: Pixel 8, Android 15

            TV
            Brand: Xiaomi
            Manufacturer: Xiaomi
            Model: MiTV-AXSO0
            Device: dangal
            Android: 9 (SDK 28)
            Build: ${Samples.miTvFacts().fingerprint}

            Home screen
            Profile: exact xiaomi-mitv-axso0
            Home switch: planned

            Restarts
            Home path: list
            Held: 1
            Failed: 0
            Last restart: held
            First counted: 2026-10-14
            Last counted: 2026-10-14
            Counted by: 0.4.8 (full, test build)
            Look route: provider
            Checked: boot_id and Home only
        """.trimIndent() + "\n"
        assertEquals(expected.lines().filterNot { it.startsWith("First counted") || it.startsWith("Last counted") },
            s.text.lines().filterNot { it.startsWith("First counted") || it.startsWith("Last counted") })
        assertTrue(s.inLink)
        assertTrue(s.url.length < IssueLinks.MAX_URL / 2, "${s.url.length}")
        assertTrue(s.url.startsWith("${IssueLinks.REPO}/issues/new?template=confirmation.yml&title=Confirmation%3A%20Xiaomi%20MiTV-AXSO0%20%28dangal%2C%20Android%209%29&confirmation="), s.url)
        assertEquals("Confirmation: Xiaomi MiTV-AXSO0 (dangal, Android 9)", IssueLinks.confirmationTitle(assertNotNull(ProblemReports.input(d, tv.id))))
        // Nothing of the TV's identity or the network: no serial, no id, no name, no room, no address.
        for (never in listOf(Samples.miTvFacts().serialLast4, tv.id.value, tv.nickname, "Living room", "192.0.2")) assertFalse(never in s.text, never)
        // Nothing leaves the phone but by the person's tap; the tap remembers when, and the count it carried.
        report.effects.test {
            report.dispatch(ReportIntent.CopyAndOpen); settle()
            assertEquals(listOf(ManageEffect.Copy(s.text), ManageEffect.OpenUrl(s.url)), drain())
        }
        assertEquals(listOf<Any?>(Samples.NOW_MS, 1), restarts.load(tv.id)?.let { listOf<Any?>(it.toldAtMs, it.toldHeld) })
    }

    @Test fun noConfirmationBeforeARestartHeld() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        Restarts(h.documents).observe(tv.id, "boot-1", ours, HomePath.LIST, build, Samples.NOW_MS)
        val report = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.CONFIRM, tv.id)
        settle()
        assertFalse(report.state.value.available)
    }

    // PLAN item 4: Manage on screen with the TV gone away (the 60 s idle close, a restart): it reconnects by itself,
    // with the known-TV handshake only (no Allow dialog, no debugging step), and keeps one hold while on screen.
    @Test fun manageReconnectsByItselfWithTheKnownTvHandshake() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.discovery.open5555[HOST] = true
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(ManageIntent.Resumed); settle()
        assertEquals(1, h.session.openHolds, "one hold while Manage is on screen")
        m.dispatch(ManageIntent.Paused); settle()
        assertEquals(0, h.session.openHolds, "released when it leaves")
        // The session closed while Manage was away (the idle close, a restart): "Offline" when it comes back, then
        // connected by itself once the TV answers.
        h.sessions.closeAll()
        settle()
        assertTrue(m.state.value.offline)
        h.session.setState(ConnectionState.Connected(Endpoint.PlainTcp(HOST), emptySet(), 4096))
        val opens = h.sessions.opens.size
        m.dispatch(ManageIntent.Resumed); settle()
        assertEquals(1, h.sessions.knownOpens.size, "the known-TV handshake")
        assertEquals(opens, h.sessions.opens.size, "never the open that can show the Allow dialog")
        assertTrue(h.logLines.any { it.startsWith("manage: reconnected by itself after 1 try") })
        assertFalse(m.state.value.offline)
        assertTrue(h.access.reach.value is Reach.Idle, "no debugging step on the phone")
    }

    // The TV emulator answers the known-TV handshake half a minute before it is up, with Home still FallbackHome: that
    // read counts nothing, and Manage reads Home again until the TV has its home screen, then counts the restart.
    @Test fun aRestartSeenWhileTheTvStillBootsIsCountedOnceItIsUp() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        h.prober.home = ours
        h.discovery.open5555[HOST] = true
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        m.dispatch(ManageIntent.Resumed); settle()
        h.sessions.closeAll()
        h.session.setState(ConnectionState.Connected(Endpoint.PlainTcp(HOST), emptySet(), 4096))
        h.prober.bootIdValue = "boot-2"
        val fallback = Component(PackageName("com.android.tv.settings"), ".FallbackHome")
        h.prober.home = HomeResolution(fallback, listOf(HomeClaimant(fallback, -1000)))
        settle()
        assertFalse(m.state.value.offline)
        assertEquals(0, m.state.value.current?.restarts?.counted, "nothing counted while the TV boots")
        h.prober.home = ours
        settle(ManageMachine.SETTLE_EVERY_MS)
        val r = assertNotNull(m.state.value.current?.restarts)
        assertEquals(listOf(1, 0), listOf(r.held, r.failed))
        assertFalse(Restarts.settled(HomeResolution(null, emptyList())))
    }

    @Test fun aTvThatNoLongerTakesTheKeyIsNotAskedAndManageKeepsTrying() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        h.discovery.open5555[HOST] = true
        h.sessions.allowPromptFor[HOST] = "AB:CD"
        val m = ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        h.sessions.closeAll()
        settle()
        m.dispatch(ManageIntent.Resumed); settle()
        settle(ManageMachine.AUTO_EVERY_MS * 2)
        assertEquals(3, h.sessions.knownOpens.size, "every ${ManageMachine.AUTO_EVERY_MS} ms")
        assertTrue(m.state.value.offline, "Reconnect stays the way in")
        m.dispatch(ManageIntent.Paused); settle(ManageMachine.AUTO_EVERY_MS * 3)
        assertEquals(3, h.sessions.knownOpens.size, "nothing while Manage is off screen")
    }

    // The Done restart check (PLAN items 1 to 3): a held restart offers "Tell us it worked", a failed one Report a
    // problem, and a look sent with the new home screen shows the TV's screenshot.
    private suspend fun kotlinx.coroutines.test.TestScope.toDone(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        h.prober.home = ours
        h.engine.onStart = { p -> if (p.kind == PlanKind.SETUP) finish(p, RunStatus.COMPLETED, HomeOutcome.CONFIRMED) else finish(p, RunStatus.COMPLETED) }
        m.dispatch(SetupIntent.MakeItClean); settle()
        val done = assertIs<SetupState.Done>(m.state.value)
        assertTrue(done.summary.homeScreenOn)
    }

    @Test fun doneCountsAHeldRestartAndOffersTheConfirmation() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val m = SetupMachine(h.setupDeps(about = AppAbout("0.4.8", "full", "", "", testBuild = true)), backgroundScope)
        toDone(h, m)
        assertEquals(0, m.extras.value.restarts?.held, "Done's read is the baseline")
        h.prober.bootIdValue = "boot-2"
        m.dispatch(SetupIntent.Restart); settle()
        assertEquals(RestartRead.HELD, m.extras.value.restartRead)
        assertEquals(1, m.extras.value.restarts?.held)
        val id = assertIs<SetupState.Done>(m.state.value).tvId
        m.effects.test {
            m.dispatch(SetupIntent.TellUsItWorked); settle()
            m.dispatch(SetupIntent.ReportRestartFailed("x")); settle()
            assertEquals(listOf(SetupEffect.Navigate("report/confirm/${id.value}")), drain().filterIsInstance<SetupEffect.Navigate>())
        }
        assertTrue(h.logLines.contains("setup: restart held (list), 1 held, 0 failed"))
    }

    @Test fun doneCountsAFailedRestartAndOffersAProblemReport() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val m = h.setup(backgroundScope)
        toDone(h, m)
        h.prober.bootIdValue = "boot-2"
        h.prober.home = theirs
        m.dispatch(SetupIntent.Restart); settle()
        assertEquals(RestartRead.FAILED, m.extras.value.restartRead)
        m.effects.test {
            m.dispatch(SetupIntent.TellUsItWorked); settle()
            m.dispatch(SetupIntent.ReportRestartFailed("The TV's own home screen came back after the restart.")); settle()
            val nav = drain().filterIsInstance<SetupEffect.Navigate>().single()
            assertEquals(ItWorked.RESTART_FAILED, assertNotNull(IssueRoute.parse(nav.key)).second.case)
        }
    }

    @Test fun aRestartWithTheSameBootSaysNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val m = h.setup(backgroundScope)
        toDone(h, m)
        m.dispatch(SetupIntent.Restart); settle()
        assertNull(m.extras.value.restartRead)
        assertEquals(0, m.extras.value.restarts?.counted)
    }

    @Test fun aRunWithoutTheNewHomeScreenCountsNothing() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertFalse(assertIs<SetupState.Done>(m.state.value).summary.homeScreenOn)
        h.prober.bootIdValue = "boot-2"
        m.dispatch(SetupIntent.Restart); settle()
        assertNull(m.extras.value.restarts)
        assertTrue("bootId" !in h.prober.calls)
    }

    @Test fun doneShowsTheTvAfterTheLookWentToIt() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        h.planner.respond = { request, _ ->
            when (request) {
                is PlanRequest.Setup -> PlanResult.Ok(h.plan(listOfNotNull(Op.Disable(adPkg), request.config?.let { Op.PushAsset(it, config, it.sha256) }), takeover = true))
                else -> PlanResult.Ok(h.plan(emptyList(), kind = PlanKind.WRITE_RESTORE_POINT, id = "01FAKEKIND0000000000000099"))
            }
        }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        h.prober.screenshotPng = png
        val m = h.setup(backgroundScope)
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
        h.engine.onStart = { p ->
            val i = p.steps.indexOfFirst { it.op is Op.PushAsset }
            if (i >= 0) {
                h.journal.append(JournalRecord.Begin(p.id, i, p.steps[i].op, listOf("fake"), null, 1))
                h.journal.append(JournalRecord.Done(p.id, i, 0, true, 5))
            }
            finish(p, RunStatus.COMPLETED, if (p.kind == PlanKind.SETUP) HomeOutcome.CONFIRMED else null)
        }
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        kotlin.test.assertContentEquals(png, m.extras.value.doneShot)
        val id = assertIs<SetupState.Done>(m.state.value).tvId
        m.effects.test {
            m.dispatch(SetupIntent.SendLookAgain); settle()
            assertEquals(listOf(SetupEffect.Navigate("home/${id.value}/again")), drain().filterIsInstance<SetupEffect.Navigate>())
        }
    }

    @Test fun theSessionCallsOfTheCountAreReadsOnly() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.switchedTv()
        h.prober.home = ours
        ManageMachine(h.manageDeps(), backgroundScope, tv.id)
        settle()
        assertTrue(h.session.calls.none { it is FakeTvSession.Call.Push || it == FakeTvSession.Call.Reboot }, "${h.session.calls}")
        assertTrue(h.planner.requests.isEmpty(), "no plan, so nothing written to the TV")
    }
}
