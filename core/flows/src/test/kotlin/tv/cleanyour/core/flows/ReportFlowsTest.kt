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
import tv.cleanyour.core.engine.SectionState
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.3.2 B1 to B3 in the machines: the readable log lines of the wizard, the report preview's two
// actions, and the ways into it (Manage for an unlisted TV, Settings, the stop cards).
@OptIn(ExperimentalCoroutinesApi::class)
class ReportFlowsTest {

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
    }

    @Test fun theProbeLineSaysHowTheProfileResolvedAndWhichTvItIs() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        val m = h.setup(backgroundScope)
        toFound(h, m)
        val f = Samples.miTvFacts()
        val probe = h.logLines.single { it.startsWith("setup: probe done") }
        assertTrue(probe.endsWith("profile exact ${miProfile.id}, tv ${f.brand} ${f.model} (${f.device}) sdk ${f.sdk}"), probe)

        val h2 = Harness()
        h2.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h2.profiles.resolver = { _, _ -> ProfileResolution.BrandSureSet("Xiaomi", emptyList(), emptyList()) }
        toFound(h2, h2.setup(backgroundScope))
        assertTrue(h2.logLines.single { it.startsWith("setup: probe done") }.contains("profile brand set Xiaomi, tv "))
    }

    // The home switch decision is logged once per change and kept for Manage's TV report.
    @Test fun theHomeSwitchDecisionIsLoggedAndKept() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertEquals(listOf("setup: home switch planned"), h.logLines.filter { it.startsWith("setup: home switch") })
        assertEquals(HomeDecision(true), HomeDecisions(h.documents).load(Samples.miTvId()))
        // A Keep replans: the same decision is not logged again.
        m.dispatch(SetupIntent.Keep(adPkg, keep = true)); settle()
        assertEquals(1, h.logLines.count { it.startsWith("setup: home switch") })
    }

    // Issue #2: step R's Enable was refused and the log said only "(f3)". Now it names the op, the
    // code and the TV's own words, and says it was the undo step.
    @Test fun aPauseNamesTheFailedOpAndWhetherItWasTheUndoStep() = runTest(timeout = 30.seconds) {
        for (undo in listOf(true, false)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            h.setupPlan = h.plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)), takeover = true)
            h.engine.onStart = { p ->
                // Step R runs inside the open section (issue #2's shape); the plain failure has none.
                if (undo) h.journal.append(JournalRecord.Section(p.id, "home", RunToken("0123456789abcdef"), SectionState.ARMED, 0))
                h.journal.append(JournalRecord.Begin(p.id, 1, Op.Disable(promoPkg), listOf("pm disable-user ${promoPkg.value}"), null, 0))
                h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "Error: refused"))
                if (undo) {
                    h.journal.append(JournalRecord.Rollback(p.id, 1))
                    h.journal.append(JournalRecord.Begin(p.id, 1, Op.Enable(promoPkg), listOf("pm enable ${promoPkg.value}"), null, 0))
                    h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "Exception occurred\nwhile executing 'enable' from 192.168.1.20"))
                }
                emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "secret 192.168.1.20")))
            }
            val m = h.setup(backgroundScope)
            toFound(h, m)
            m.dispatch(SetupIntent.MakeItClean); settle()
            val line = h.logLines.single { it.startsWith("setup: run paused") }
            val want = if (undo) "setup: run paused STEP_FAILED at step 1: undo step failed: Enable ${promoPkg.value}: nonzero_exit(1) Exception occurred while executing 'enable' from [address]"
                else "setup: run paused STEP_FAILED at step 1: step failed: Disable ${promoPkg.value}: nonzero_exit(1) Error: refused"
            assertEquals(want, line)
            assertFalse("192.168.1.20" in h.logLines.joinToString("\n"))
        }
    }

    // S1: a pause names only the op that made it. An older refusal that was skipped (step 0's package
    // was not on the TV) is not it: a pause at step 2 with no failure of step 2 names no op.
    @Test fun aPauseNeverNamesAnOlderSkippedRefusal() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)), takeover = false)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.Begin(p.id, 0, Op.Disable(adPkg), listOf("pm disable-user ${adPkg.value}"), null, 0))
            h.journal.append(JournalRecord.Failed(p.id, 0, 1, "package not on the TV", ""))
            h.journal.append(JournalRecord.Begin(p.id, 1, Op.Disable(promoPkg), listOf("pm disable-user ${promoPkg.value}"), null, 0))
            h.journal.append(JournalRecord.Done(p.id, 1, 0, true, 1))
            h.journal.append(JournalRecord.Paused(p.id, 2, PauseReason.STEP_FAILED))
            emit(p.tvId, RunState.Paused(p, 2, PauseReason.STEP_FAILED, AdbError.Transport.Protocol("boom")))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val line = h.logLines.single { it.startsWith("setup: run paused") }
        assertTrue(line.startsWith("setup: run paused STEP_FAILED at step 2") && adPkg.value !in line, line)
    }

    // A pause with nothing in the journal keeps the short form, with a stable code.
    @Test fun aLostConnectionPauseHasAStableCode() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.engine.onStart = { p -> emit(p.tvId, RunState.Paused(p, 1, PauseReason.CONNECTION_LOST, AdbError.Transport.Closed)) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertTrue(h.logLines.any { it == "setup: run paused CONNECTION_LOST at step 1 (lost)" }, h.logLines.joinToString("\n"))
    }

    // B2: "Share diagnostics" on the home screen card opens the report preview instead of sharing at once.
    // 0.4.6: it opens the prefilled GitHub form at once (IssueRoute), with the card's title and case.
    @Test fun theHomeUnknownCardOpensTheReportPreview() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.HOME_UNKNOWN, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.effects.test {
            m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
            val effects = drain()
            val route = assertNotNull(effects.filterIsInstance<SetupEffect.Navigate>().firstNotNullOfOrNull { IssueRoute.parse(it.key) }, effects.toString())
            assertEquals(Samples.miTvId(), route.first)
            val card = assertIs<SetupState.Stopped>(m.state.value).card
            assertEquals(ReportContext("Setup", card.title, StopCase.HOME_UNKNOWN.name), route.second)
            assertTrue(effects.none { it is SetupEffect.Share })
        }
    }

    // 0.4.6 (REPORTING-PROPOSAL 2.2): "Report a problem" on the card of a step that failed opens the
    // GitHub form for this TV with the card and the step the run paused at; the link holds the report.
    @Test fun aFailedStepCardReportsTheCardAndTheStep() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)), takeover = true)
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.Begin(p.id, 1, Op.Disable(promoPkg), listOf("pm disable-user ${promoPkg.value}"), null, 0))
            h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "Error: refused at 192.168.1.20"))
            emit(p.tvId, RunState.Paused(p, 1, PauseReason.STEP_FAILED, AdbError.NonZeroExit(1, "secret")))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val card = assertIs<SetupState.Stopped>(m.state.value).card
        assertEquals(StopCase.STEP_FAILED, card.case)
        m.effects.test {
            m.report(card.title); settle()
            val (id, ctx) = assertNotNull(IssueRoute.parse(drain().filterIsInstance<SetupEffect.Navigate>().single().key))
            assertEquals(Samples.miTvId(), id)
            assertEquals(ReportContext("Setup", card.title, "STEP_FAILED"), ctx)
            val r = ProblemReports.build(h.manageDeps(), id, ctx)
            assertTrue(r.link.reportInLink && r.link.url.length <= IssueLinks.MAX_URL, r.link.url)
            val step = "Step: paused STEP_FAILED at step 1: step failed: Disable ${promoPkg.value}: nonzero_exit(1) Error: refused at [address]\n"
            assertTrue("What happened\nScreen: Setup\nCard: ${card.title} (STEP_FAILED)\n$step" in r.sent, r.sent)
            assertTrue("Model: ${Samples.miTvFacts().model}\n" in r.sent, r.sent)
            assertFalse("192.168.1.20" in r.link.url || "192.168.1.20" in r.full)
            assertTrue(r.link.url.contains("&what="), r.link.url)
        }
        // The TV's nickname in a title comes out, as a whole word.
        val nick = assertNotNull(m.extras.value.tvName)
        m.effects.test {
            m.report("$nick stopped, ${nick}Box"); settle()
            val named = assertNotNull(IssueRoute.parse(drain().filterIsInstance<SetupEffect.Navigate>().single().key)).second
            assertEquals("the TV stopped, ${nick}Box", named.card)
        }
    }

    // 0.4.6: a connect that failed has no TV to read: the report has the phone's side, how the TV was
    // found, the port and the named error, and the title says it could not connect.
    @Test fun aConnectFailureReportsThePhonesSide() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val other = "192.0.2.20"
        val about = AppAbout("0.4.6", "full", "Xiaomi 2201117TG", "Android 13")
        val m = SetupMachine(h.setupDeps(diagnostics = { "Clean Your TV 0.4.6, Android 33\n1 I session: x.x.x.20 refused" }, about = about,
            connectDetail = { "ConnectException, ECONNREFUSED (Connection refused) to 192.0.2.20" }), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(DiscoveryEvent.Found(miTvFound.copy(host = other))); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[other] = true
        settle(2_100); settle(700)
        assertEquals("unreachable", assertNotNull(m.extras.value.connectError).kind)
        m.effects.test {
            m.report("The TV answered, but the phone could not connect"); settle()
            val (id, ctx) = assertNotNull(IssueRoute.parse(drain().filterIsInstance<SetupEffect.Navigate>().single().key))
            assertNull(id)
            assertEquals("Connect", ctx.screen)
            assertEquals("CONNECT_FAILED", ctx.case)
            assertTrue(ctx.connect)
            assertEquals("unreachable", ctx.error)
            assertEquals(5555, ctx.port)
            assertEquals("google_cast", ctx.found)
            val r = ProblemReports.build(h.manageDeps(), id, ctx)
            val q = r.link.url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals("Problem: could not connect (unreachable)", q["title"])
            assertTrue("TV: none read yet" in r.sent, r.sent)
            assertTrue("Connection\nFound by: google_cast\nPort: 5555\nError: unreachable (ConnectException, ECONNREFUSED (Connection refused) to [address])\n" in r.sent, r.sent)
            for (leak in listOf("192.0.2", "x.x.x.20")) assertFalse(leak in r.link.url || leak in r.full, leak)
        }
    }

    // B1: an unlisted TV with a stored snapshot gets "Help add this TV" on Manage; a listed one does not.
    @Test fun manageInvitesHelpForAnUnlistedTvOnly() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val listed = h.knownTv()
        val m = ManageMachine(h.manageDeps(), backgroundScope, null)
        settle()
        assertFalse(assertNotNull(m.state.value.current).helpAddTv)
        h.tvs.upsert(listed.copy(profileId = null)); settle()
        assertTrue(assertNotNull(m.state.value.current).helpAddTv)
        m.effects.test {
            m.dispatch(ManageIntent.HelpAddTv); settle()
            assertEquals(ManageEffect.Navigate("report/tv/${listed.id.value}"), drain().single())
        }
    }

    // B1: the preview's text is the TV report; the button copies first, then opens the form. 0.3.3 D1:
    // the preview and the form get the compact report, in the link; Share keeps the full one.
    @Test fun theTvReportCopiesThenOpensTheFormOrShares() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(profileId = null).also { h.tvs.upsert(it) }
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_STAYS_ON"))
        val deps = h.manageDeps().let { d -> ManageDepsWith(d, AppAbout("0.3.2", "play", "Pixel 7", "Android 15")) }
        val m = ReportMachine(deps, backgroundScope, ReportKind.TV, tv.id)
        settle()
        val s = m.state.value
        assertFalse(s.loading)
        assertTrue(s.available)
        assertTrue(s.text.startsWith("Clean Your TV: TV report\nApp: 0.3.2 (play)\nPhone: Pixel 7, Android 15\n"), s.text)
        assertTrue("Home switch: blocked WIZARD_STAYS_ON" in s.text)
        assertTrue(s.url.contains("template=tv_support.yml"))
        assertFalse(tv.id.value in s.text || tv.facts.serialLast4 in s.text || "192.0.2" in s.text || tv.nickname in s.text)
        assertTrue(s.inLink)
        assertTrue(s.url.contains("&report="), s.url)
        assertTrue(s.url.length <= IssueLinks.MAX_URL)
        assertTrue("On, enabled (" in s.text, s.text)
        assertTrue(s.shareText.startsWith("Clean Your TV: TV report\n") && "On, enabled (" !in s.shareText && " enabled\n" in s.shareText, s.shareText)
        m.effects.test {
            m.dispatch(ReportIntent.CopyAndOpen); settle()
            assertEquals(listOf(ManageEffect.Copy(s.text), ManageEffect.OpenUrl(s.url)), drain())
        }
        m.effects.test {
            m.dispatch(ReportIntent.Share); settle()
            assertEquals(listOf<ManageEffect>(ManageEffect.Share(s.shareText)), drain())
        }
    }

    // 0.3.3 D1: a TV report too long for the link: no report in it, the preview says so (inLink false),
    // and the button still copies before it opens the form.
    @Test fun aTvReportTooLongForTheLinkIsCopiedForThePaste() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv().copy(profileId = null).also { h.tvs.upsert(it) }
        val many = (1..400).associate {
            PackageName("com.example.vendor.firmware.part$it") to PackageState(EnabledState.DEFAULT, true, 0, null, false, false, null, false)
        }
        h.snapshots.save(Samples.snapshot(packages = Samples.miTvPackages() + many, takenAtMs = Samples.NOW_MS + 1))
        val m = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.TV, tv.id)
        settle()
        val s = m.state.value
        assertFalse(s.inLink)
        assertFalse("report=" in s.url, s.url)
        assertTrue(s.url.length <= IssueLinks.MAX_URL)
        m.effects.test {
            m.dispatch(ReportIntent.CopyAndOpen); settle()
            assertEquals(listOf(ManageEffect.Copy(s.text), ManageEffect.OpenUrl(s.url)), drain())
        }
    }

    // No snapshot: nothing to report from, and the buttons do nothing.
    @Test fun aTvNeverReadHasNoReport() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv().copy(profileId = null).also { h.tvs.upsert(it) }
        val m = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.TV, tv.id)
        settle()
        assertFalse(m.state.value.available)
        m.effects.test {
            m.dispatch(ReportIntent.CopyAndOpen); settle()
            assertTrue(drain().isEmpty())
        }
    }

    // B2 from Settings: the TV used last, and the redacted log; Settings' row opens it.
    @Test fun theProblemReportFromSettings() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.knownTv()
        val m = ReportMachine(h.manageDeps(), backgroundScope, ReportKind.PROBLEM, null)
        settle()
        val s = m.state.value
        assertTrue(s.text.startsWith("Clean Your TV: problem report\n"), s.text)
        assertTrue("Apps: " in s.text && s.text.endsWith("redacted\n"), s.text)
        assertTrue(s.url.contains("template=bug_report.yml") && s.url.contains("&tv="), s.url)
        // 0.3.3 D1: a short problem report rides in the link too.
        assertTrue(s.inLink && s.url.contains("&diagnostics="), s.url)
        val settings = AppSettingsMachine(h.manageDeps(), backgroundScope)
        settings.effects.test {
            settings.dispatch(AppSettingsIntent.ReportProblem); settle()
            assertEquals(ManageEffect.Navigate("report/problem"), drain().single())
        }
    }

    // ManageDeps with another AppAbout (the app passes its own).
    private fun ManageDepsWith(d: ManageDeps, about: AppAbout) = ManageDeps(
        tvs = d.tvs, sessions = d.sessions, discovery = d.discovery, access = d.access, gate = d.gate, runner = d.runner, engine = d.engine,
        ledger = d.ledger, prober = d.prober, profiles = d.profiles, facts = d.facts, snapshots = d.snapshots, documents = d.documents,
        blobs = d.blobs, themes = d.themes, launcherPin = d.launcherPin, configTransport = d.configTransport, restorePoint = d.restorePoint,
        bundleWriter = d.bundleWriter, registry = d.registry, fetcher = d.fetcher, inspector = d.inspector, cardDetector = d.cardDetector,
        sessionEnd = d.sessionEnd, homePrefs = d.homePrefs, appPrefs = d.appPrefs, keyInfo = d.keyInfo, updates = d.updates,
        diagnostics = d.diagnostics, clock = d.clock, copy = d.copy, encoder = d.encoder, store = d.store, about = about,
    )
}
