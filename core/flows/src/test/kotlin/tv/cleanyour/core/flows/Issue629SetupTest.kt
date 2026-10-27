package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Issue036
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issue #629 (OK. AI PONT, ikebukuro): the home check rolled back twice, with
// com.android.tv.settings/.connectivity.NetworkActivity and then .MainSettings in front, the pages
// the new home screen's network and Settings buttons open. The card said "We couldn't make the new
// home screen take over on this TV", which was not true. A Settings page in front now gets its own
// card: nothing is wrong with the TV, try again pressing only Home. FallbackHome, which the same
// package holds, is the TV with no home at all and keeps the generic card.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue629SetupTest {
    private val others = SavedWifi(true, 1, setOf("android.uid.system:1000"))
    private val tvSettings = PackageName("com.android.tv.settings")

    private fun harness(): Harness {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = Issue036.hengshanFacts()
        h.prober.snapshotFor = { r -> Issue036.hengshanSnapshot(r) }
        val source = BundledProfileSource()
        h.profiles.resolver = { f, _ -> source.resolveNow(f) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.3.8")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        return h
    }

    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun issue629ASettingsPageInFrontGetsItsOwnCard() = runTest(timeout = 60.seconds) {
        val cases = listOf(
            Component(tvSettings, ".connectivity.NetworkActivity") to StopCase.HOME_SETTINGS_OPEN,
            Component(tvSettings, ".MainSettings") to StopCase.HOME_SETTINGS_OPEN,
            Component(PackageName("com.android.settings"), ".Settings") to StopCase.HOME_SETTINGS_OPEN,
            Component(tvSettings, ".system.FallbackHome") to StopCase.HOME_UNKNOWN,
            null to StopCase.HOME_UNKNOWN,
        )
        for ((focus, expected) in cases) {
            val h = harness()
            var taken = 0
            h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.WRONG_FOCUS) }
            val m = SetupMachine(h.setupDeps(savedWifi = { others }, wrongFocus = { taken++; focus }), backgroundScope, SetupStart.Fresh)
            toFound(h, m)
            assertIs<SetupState.Found>(m.state.value, h.logLines.joinToString("\n"))
            m.dispatch(SetupIntent.MakeItClean); settle()
            val card = assertIs<SetupState.Stopped>(m.state.value).card
            assertEquals(expected, card.case, focus?.flat)
            assertEquals(1, taken, "read once")
            if (expected == StopCase.HOME_SETTINGS_OPEN) {
                assertTrue(card.body.startsWith("STOP_HOME_SETTINGS_OPEN_BODY"), card.body)
                assertTrue(card.primary.startsWith("STOP_HOME_USER_SAID_NO_PRIMARY"), card.primary)   // "Try again"
                // "Leave the old home screen" goes to Done with the TV's own home screen.
                m.dispatch(SetupIntent.StopAction(StopAction.SECONDARY)); settle()
                assertIs<SetupState.Done>(m.state.value)
            }
        }
    }

    // Any other outcome never asks what was in front.
    @Test fun otherOutcomesKeepTheirCards() = runTest(timeout = 60.seconds) {
        val h = harness()
        var taken = 0
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.USER_SAID_NO) }
        val m = SetupMachine(h.setupDeps(savedWifi = { others }, wrongFocus = { taken++; Component(tvSettings, ".MainSettings") }), backgroundScope, SetupStart.Fresh)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertEquals(StopCase.HOME_USER_SAID_NO, assertIs<SetupState.Stopped>(m.state.value).card.case)
        assertEquals(0, taken)
    }
}
