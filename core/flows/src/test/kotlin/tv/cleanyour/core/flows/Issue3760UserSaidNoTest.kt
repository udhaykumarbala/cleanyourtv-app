package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// Issue #3760 (TCL Smart TV Pro G08, tcl-c755-google-tv): the person tried the new home screen, said no
// at the check because they want Google TV's own, and the card then offered only "Try again" (the
// switch planned again) and "Something looked wrong". There was no way to keep the TV's own home screen
// and finish. The card's third button now does that: Done, the clean-up kept, the home screen the TV's own.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue3760UserSaidNoTest {
    private suspend fun TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
    }

    @Test fun theUserSaidNoCardKeepsTheTvsOwnHomeScreenAndFinishes() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg)), takeover = true)
        h.engine.onStart = { p ->
            if (p.kind != PlanKind.SETUP) finish(p, RunStatus.COMPLETED)
            else emit(p.tvId, RunState.AwaitingConfirm(p, p.sections.single(), Samples.NOW_MS + 90_000, null, null))
        }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        m.dispatch(SetupIntent.NoPutItBack); settle()
        h.engine.finish(h.setupPlan, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.USER_SAID_NO)
        settle()
        val s = assertIs<SetupState.Stopped>(m.state.value)
        assertEquals(StopCase.HOME_USER_SAID_NO, s.card.case)
        assertEquals(true, s.card.tertiary?.startsWith("STOP_HOME_SETUPWRAITH_SECONDARY"), s.card.tertiary)   // "Keep the TV's own home screen"
        assertNull(Confirmations.forStopAction(StopCase.HOME_USER_SAID_NO, StopAction.TERTIARY), "it changes nothing, so it does not ask")
        fun setupStarts() = h.engine.calls.count { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.SETUP }
        val starts = setupStarts()
        m.dispatch(SetupIntent.StopAction(StopAction.TERTIARY)); settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        assertFalse(d.summary.homeScreenOn)
        assertEquals(starts, setupStarts(), "no new switch")
    }
}
