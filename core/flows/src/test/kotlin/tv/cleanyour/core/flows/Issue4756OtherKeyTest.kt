package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
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

// Issues #2293 and #4756 (STREAM STR-D2 G4K, Android 14): a copy of com.gathin.tvhome signed with
// another key (put on with the computer tool) was on the TV, and the launcher's install ended with
// "exit 1: INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.gathin.tvhome signatures do not
// match newer version; ignoring!". The card said another home screen app was in charge
// (HOME_OTHER_LAUNCHER) and "Use the new home screen" failed the same way. It now has its own card:
// remove the old copy first, safely, with the TV's own home screen kept.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue4756OtherKeyTest {
    private suspend fun TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
    }

    private val apk = BlobRef(Samples.zeroHash, 6L * 1024 * 1024, "bundled:launcher.apk")
    private val stderr = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.gathin.tvhome signatures do not match newer version; ignoring!"

    private fun installFails(h: Harness, install: Op, err: String = stderr) {
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), install), takeover = true)
        val step = h.setupPlan.steps.single { it.op == install }
        h.engine.onStart = { p ->
            if (p.kind != PlanKind.SETUP) finish(p, RunStatus.COMPLETED) else {
                h.journal.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
                h.journal.append(JournalRecord.Failed(p.id, step.index, 1, "exit 1", err))
                finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED)
            }
        }
    }

    private suspend fun TestScope.stopped(h: Harness): Pair<SetupMachine, StopCard> {
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        return m to assertIs<SetupState.Stopped>(m.state.value, h.logLines.joinToString("\n")).card
    }

    @Test fun issue4756ACopySignedWithAnotherKeyGetsItsOwnCard() = runTest(timeout = 30.seconds) {
        val installs = listOf(
            Op.InstallStream(apk, Samples.zeroHash, Launcher.pkg, 9, Samples.zeroHash),
            Op.Install(tv.cleanyour.core.model.RemotePath(tv.cleanyour.core.model.RemoteDir.TMP, listOf("a.apk")), Samples.zeroHash, Launcher.pkg, 9, Samples.zeroHash, emptySet()),
        )
        for (install in installs) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            installFails(h, install)
            val (m, card) = stopped(h)
            assertEquals(StopCase.HOME_OTHER_KEY, card.case, "$install")
            assertEquals("STOP_HOME_OTHER_KEY_TITLE", card.title)
            assertEquals("STOP_HOME_OTHER_KEY_BODY", card.body)
            assertEquals(true, card.primary.startsWith("STOP_HOME_SETUPWRAITH_SECONDARY"), card.primary)   // "Keep the TV's own home screen"
            assertEquals(true, card.secondary?.startsWith("STOP_HOME_USER_SAID_NO_PRIMARY"), card.secondary)   // "Try again"
            assertNull(Confirmations.forStopAction(StopCase.HOME_OTHER_KEY, StopAction.PRIMARY))
            // Keeping the TV's own home screen goes on to Done, with no new switch.
            val starts = h.engine.calls.count { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.SETUP }
            m.dispatch(SetupIntent.StopAction(StopAction.PRIMARY)); settle()
            assertFalse(assertIs<SetupState.Done>(m.state.value).summary.homeScreenOn)
            assertEquals(starts, h.engine.calls.count { it is FakeEngine.Call.Start && it.plan.kind == PlanKind.SETUP })
        }
    }

    // Any other install failure keeps the card it had.
    @Test fun anotherInstallFailureKeepsItsCard() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        installFails(h, Op.InstallStream(apk, Samples.zeroHash, Launcher.pkg, 9, Samples.zeroHash), err = "INSTALL_FAILED_INSUFFICIENT_STORAGE")
        val (_, card) = stopped(h)
        assertEquals(StopCase.HOME_UNKNOWN, card.case)
    }
}
