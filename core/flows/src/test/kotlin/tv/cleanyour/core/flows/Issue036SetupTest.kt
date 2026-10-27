package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.WizardDraft
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Issue036
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #375 (0.3.4), Hisense U75 hengshan, exact list hisense-u75-hengshan, Wi-Fi saved by others.
// The log: "home switch planned", "run finished COMPLETED_WITH_SKIPS, home WRONG_FOCUS, 3 skipped",
// then on every later try "plan refused" with "Google TV setup wizard cannot be switched off: it is
// the TV's own home screen; only the home screen switch changes it", in that session and in the
// next ones. The stop card had offered "Try with the wizard off", which wrote the wizard into the
// TV's saved draft; the app opens on that draft at every start, so it went straight back to the
// refusal, and its Back led to Welcome, never to Manage and Restore point.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue036SetupTest {
    private val others = SavedWifi(true, 1, setOf("android.uid.system:1000"))   // "setup wizard and saved Wi-Fi: others_own"

    private fun harness(): Harness {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = Issue036.hengshanFacts()
        h.prober.snapshotFor = { r -> Issue036.hengshanSnapshot(r) }
        val source = BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.3.4")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        return h
    }

    private fun machine(h: Harness, scope: kotlinx.coroutines.CoroutineScope) = SetupMachine(h.setupDeps(savedWifi = { others }), scope, SetupStart.Fresh)

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    // The draft 0.3.4 and 0.3.5 left on such a TV: the wizard among the extra switch-offs.
    private fun stuckTv() = Samples.tv(facts = Issue036.hengshanFacts()).copy(
        profileId = "hisense-u75-hengshan",
        wizard = WizardDraft(3, Level.SIMPLE, emptySet(), setOf(Issue036.wizard), null, null, false, null, Samples.NOW_MS),
    )

    private fun setups(h: Harness) = h.planner.requests.filterIsInstance<PlanRequest.Setup>()

    // The rollback no longer offers "Try with the wizard off": the switch had the wizard off already.
    @Test fun issue375AWrongFocusRollbackOffersTheReportNotTheWizardOff() = runTest(timeout = 60.seconds) {
        val h = harness()
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.WRONG_FOCUS) }
        val m = machine(h, backgroundScope)
        toFound(h, m)
        val found = assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
        assertTrue(h.logLines.any { it == "setup: home switch planned" }, h.logLines.joinToString("\n"))
        val section = found.plan.sections.single()
        assertTrue(found.plan.steps.any { it.op == Op.Disable(Issue036.wizard) && it.index in section.from..section.to })
        m.dispatch(SetupIntent.MakeItClean); settle()
        val card = assertIs<SetupState.Stopped>(m.state.value).card
        assertEquals(StopCase.HOME_UNKNOWN, card.case)
        assertTrue(card.primary.startsWith("STOP_HOME_UNKNOWN_PRIMARY"), card.primary)
        // Both buttons, and the old intent if it still arrives, leave the saved choices alone.
        m.effects.test {
            m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
            // 0.4.6: the prefilled GitHub form for this TV, with the card it came from.
            val route = assertNotNull(IssueRoute.parse(drain().filterIsInstance<SetupEffect.Navigate>().last().key))
            assertEquals(Samples.miTvId(), route.first)
            assertEquals(StopCase.HOME_UNKNOWN.name, route.second.case)
        }
        m.dispatch(SetupIntent.TryWithWizardOff); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(setups(h).none { Issue036.wizard in it.choices.extraOff })
        assertTrue(h.logLines.none { it.startsWith("setup: plan refused") }, h.logLines.joinToString("\n"))
        val saved = h.tvs.tvs.value.single()
        assertTrue(saved.wizard?.extraOff.orEmpty().none { it == Issue036.wizard })
    }

    // A TV stuck since 0.3.4: the saved choice is dropped, the plan is made, and the draft is saved
    // without it, so the next start does not meet the refusal either.
    @Test fun issue375TheWizardLeftInTheSavedChoicesIsDroppedAndThePlanIsMade() = runTest(timeout = 60.seconds) {
        val h = harness()
        h.tvs.upsert(stuckTv())
        val m = machine(h, backgroundScope)
        toFound(h, m)
        val log = h.logLines.joinToString("\n")
        assertIs<SetupState.Found>(m.state.value, log)
        assertTrue(h.logLines.none { it.startsWith("setup: plan refused") || it == Issue036.REFUSED_LINE }, log)
        assertTrue(h.logLines.any { it == "setup: 1 earlier choice left out, now protected on this TV: ${Issue036.wizard.value}" }, log)
        assertTrue(setups(h).isNotEmpty() && setups(h).none { Issue036.wizard in it.choices.extraOff }, log)
        assertEquals(emptySet(), h.tvs.tvs.value.single().wizard?.extraOff)
        assertEquals(emptySet(), m.extras.value.draft?.extraOff)
    }

    // Any refusal on a TV this app already changed: Back opens Manage (Restore point, Undo everything).
    @Test fun issue375BackFromARefusedPlanOpensManageOnATvWeChanged() = runTest(timeout = 60.seconds) {
        val h = harness()
        h.planner.respond = { _, _ -> tv.cleanyour.core.planner.PlanResult.Refused(listOf("refused for the test")) }
        // The earlier run of the report: one switch-off, done.
        val earlier = h.plan(listOf(Op.Disable(tv.cleanyour.core.model.PackageName("com.vt.hishow"))), id = "01FAKEPLAN0000000000000042")
        h.ledger.record(earlier)
        h.journal.append(JournalRecord.Begin(earlier.id, 0, earlier.steps[0].op, emptyList(), Prior(), Samples.NOW_MS))
        h.journal.append(JournalRecord.Done(earlier.id, 0, 0, verified = true, ms = 10))
        val m = machine(h, backgroundScope)
        toFound(h, m)
        assertEquals(StopCase.PLAN_REFUSED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.effects.test {
            m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
            assertEquals(SetupEffect.Navigate("manage/${Samples.miTvId().value}"), drain().filterIsInstance<SetupEffect.Navigate>().last())
        }
    }

    // A TV with no change of ours: Back goes to Welcome, as before.
    @Test fun backFromARefusedPlanGoesToWelcomeOnATvWeNeverChanged() = runTest(timeout = 60.seconds) {
        val h = harness()
        h.planner.respond = { _, _ -> tv.cleanyour.core.planner.PlanResult.Refused(listOf("refused for the test")) }
        val m = machine(h, backgroundScope)
        toFound(h, m)
        assertEquals(StopCase.PLAN_REFUSED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
        assertIs<SetupState.Welcome>(m.state.value)
    }

    // The snapshot the report describes, for the fixture's own sake: the wizard claims Home.
    @Test fun issue375TheFixtureIsTheReport() {
        val s = Issue036.hengshanSnapshot(SnapshotReason.MANUAL)
        assertEquals(Issue036.launcherx, s.home.resolved)
        assertEquals(listOf(2, 1, 0, -1000), s.home.claimants.map { it.priority })
    }
}
