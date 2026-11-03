package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.FileRoute
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.7 (issues #1279, #1649): Setup sends its look with a CONTINUE policy, so a look the TV refused used to end on
// "Your TV is ready" with nothing but "3 skipped" in the report. Now Done has a line for it, and the log and the
// report name the refused send and the TV's answer.
@OptIn(ExperimentalCoroutinesApi::class)
class LookSkippedTest {
    private val sha = Sha256("c".repeat(64))
    private val config = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))

    private suspend fun TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound)); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[HOST] = true
        settle(2_100); settle(700)
    }

    // A Setup plan with the switch whose second step is the look's send (through the launcher's provider, or the sync push).
    private fun Harness.lookPlan(route: FileRoute = FileRoute.THEME_PROVIDER): Plan {
        val p = plan(listOf(Op.Disable(adPkg), Op.PushAsset(BlobRef(sha, 10, "blobs/c"), config, sha, route)), takeover = true)
        return p.copy(steps = p.steps.mapIndexed { i, s -> if (i == 1) s.copy(group = DefaultPlanner.GROUP_THEME) else s })
    }

    @Test fun aRefusedLookGetsItsLineOnDoneAndInTheLog() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.lookPlan()
        h.engine.onStart = { p -> if (p.kind != tv.cleanyour.core.planner.PlanKind.SETUP) finish(p, RunStatus.COMPLETED) else {
            h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 21))
            h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "cp: /sdcard/Android/data/com.gathin.tvhome/files/config.json: Permission denied"))
            emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[1]), HomeOutcome.CONFIRMED))
        } }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        val d = assertIs<SetupState.Done>(m.state.value)
        assertTrue(m.extras.value.doneLookFailed)
        assertTrue(h.logLines.contains("setup: the look did not reach the TV; the choice is kept for the Look sheet"))
        assertTrue(d.summary.skipped.isEmpty(), "the look is not a switch-off that stayed on")
        val line = h.logLines.single { it.startsWith("setup: look not sent") }
        assertEquals("setup: look not sent: PushAsset config.json via provider: nonzero_exit(1) cp: /sdcard/Android/data/com.gathin.tvhome/files/config.json: Permission denied", line)
        // The report's step line (a finished run) carries it too.
        val f = RunState.Finished(h.setupPlan, RunStatus.COMPLETED_WITH_SKIPS, listOf(h.setupPlan.steps[1]), null)
        assertTrue(RunLines.finished(f, h.ledger).endsWith("; look not sent: PushAsset config.json via provider: nonzero_exit(1) cp: /sdcard/Android/data/com.gathin.tvhome/files/config.json: Permission denied"))
    }

    @Test fun aLookThatArrivedHasNoLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.lookPlan()
        h.engine.onStart = { p -> finish(p, RunStatus.COMPLETED, if (p.kind == tv.cleanyour.core.planner.PlanKind.SETUP) HomeOutcome.CONFIRMED else null) }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertFalse(m.extras.value.doneLookFailed)
        assertTrue(h.logLines.none { it.contains("look not sent") })
        assertNull(RunLines.lookSkipped(RunState.Finished(h.setupPlan, RunStatus.COMPLETED, emptyList(), null), h.ledger))
    }

    // 0.4.8 (issues #2486, #2743, #2851): with the launcher's install skipped (it failed), the look had no launcher to
    // reach, and "PushAsset config.json via provider: ..." read like the old look bug. The line says why instead; a
    // skipped install of the TV curtain changes nothing.
    @Test fun aLookAfterAFailedLauncherInstallSaysTheHomeScreenDidNotInstall() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val install = Op.InstallStream(BlobRef(sha, 10, "blobs/l"), sha, Launcher.pkg, 250, sha)
        val p = h.plan(listOf(install, Op.PushAsset(BlobRef(sha, 10, "blobs/c"), config, sha, FileRoute.THEME_PROVIDER)), takeover = true)
            .let { p -> p.copy(steps = p.steps.mapIndexed { i, s -> when (i) { 0 -> s.copy(group = DefaultPlanner.GROUP_LAUNCHER); 1 -> s.copy(group = DefaultPlanner.GROUP_THEME); else -> s } }) }
        h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 21))
        h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "Error: no provider for com.gathin.tvhome.theme"))
        val f = RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[0], p.steps[1]), null)
        assertEquals("look not sent: the new home screen did not install", RunLines.lookSkipped(f, h.ledger))
        assertTrue(RunLines.finished(f, h.ledger).endsWith("2 skipped; look not sent: the new home screen did not install"), RunLines.finished(f, h.ledger))
        val curtain = f.copy(skipped = listOf(p.steps[0].copy(group = DefaultPlanner.GROUP_CURTAIN), p.steps[1]))
        assertEquals("look not sent: PushAsset config.json via provider: nonzero_exit(1) Error: no provider for com.gathin.tvhome.theme",
            RunLines.lookSkipped(curtain, h.ledger))
    }

    // The sync push (Android 8 and 9, an older launcher) is the same look step: the same line on Done and in the report.
    @Test fun aRefusedSyncPushIsTheSameLine() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.setupPlan = h.lookPlan(FileRoute.SYNC)
        h.engine.onStart = { p -> if (p.kind != tv.cleanyour.core.planner.PlanKind.SETUP) finish(p, RunStatus.COMPLETED) else {
            h.journal.append(JournalRecord.Begin(p.id, 1, p.steps[1].op, listOf("fake"), null, 21))
            h.journal.append(JournalRecord.Failed(p.id, 1, 1, "exit 1", "adb: error: failed to copy"))
            emit(p.tvId, RunState.Finished(p, RunStatus.COMPLETED_WITH_SKIPS, listOf(p.steps[1]), HomeOutcome.CONFIRMED))
        } }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        assertIs<SetupState.Done>(m.state.value)
        assertTrue(m.extras.value.doneLookFailed)
        assertEquals("setup: look not sent: PushAsset config.json: nonzero_exit(1) adb: error: failed to copy", h.logLines.single { it.startsWith("setup: look not sent") })
    }
}
