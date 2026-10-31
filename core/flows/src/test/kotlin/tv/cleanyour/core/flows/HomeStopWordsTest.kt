package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Op
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The words of the stop card after a failed home screen switch, for two step lines of the 0.4.8
// sweep (research/release-0.4.8/sweep-1031/FINDINGS.md section 3). Only the words change: the run,
// its rollback and the card's case and buttons are as before.
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStopWordsTest {
    private suspend fun kotlinx.coroutines.test.TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    private val apk = BlobRef(Samples.zeroHash, 6L * 1024 * 1024, "bundled:launcher.apk")
    private val install = Op.InstallStream(apk, Samples.zeroHash, Launcher.pkg, 9, Samples.zeroHash)
    private fun streamClosed(free: Int?) = DefaultEngine.STREAM_FAILED + "Protocol(detail=the TV closed the stream)" + (free?.let { " [TV storage: $it MB free]" } ?: "")

    // The engine's journal for a launcher install the TV ended: its Begin and Failed, no section.
    private fun installFails(h: Harness, detail: String) {
        h.setupPlan = h.plan(listOf(Op.Disable(adPkg), install), takeover = true)
        val step = h.setupPlan.steps.single { it.op is Op.InstallStream }
        h.engine.onStart = { p ->
            h.journal.append(JournalRecord.Begin(p.id, step.index, step.op, emptyList(), null, Samples.NOW_MS))
            h.journal.append(JournalRecord.Failed(p.id, step.index, null, detail, ""))
            finish(p, RunStatus.COMPLETED_WITH_SKIPS, HomeOutcome.VERIFY_FAILED)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.stopCard(h: Harness): StopCard {
        val m = h.setup(backgroundScope)
        toFound(h, m)
        m.dispatch(SetupIntent.MakeItClean); settle()
        return assertIs<SetupState.Stopped>(m.state.value, h.logLines.joinToString("\n")).card
    }

    // Issues #3221, #3101, #2851: the TV closed the stream and has 120 MB free. The step line names
    // the storage; the card says to free some space and run Setup again.
    @Test fun issue3221ALowTvSaysToFreeSomeSpace() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        installFails(h, streamClosed(120))
        val card = stopCard(h)
        assertEquals(StopCase.HOME_UNKNOWN, card.case)
        assertEquals("STOP_HOME_LOW_STORAGE_BODY{free=120}", card.body)
        assertTrue(card.primary.startsWith("STOP_HOME_UNKNOWN_PRIMARY"), card.primary)
        assertTrue(h.logLines.any { it == "setup: home switch stopped at Install com.gathin.tvhome: failed: ${streamClosed(120)}" }, h.logLines.joinToString("\n"))
    }

    // Room to spare, or no storage read at all: the card is the one it was.
    @Test fun aTvWithRoomOrNoReadKeepsThePlainCard() = runTest(timeout = 30.seconds) {
        for (free in listOf(312, null)) {
            val h = Harness()
            h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
            installFails(h, streamClosed(free))
            val card = stopCard(h)
            assertEquals(StopCase.HOME_UNKNOWN, card.case, "free $free")
            assertEquals("STOP_HOME_UNKNOWN_BODY{launcher=}", card.body, "free $free")
        }
    }
}
