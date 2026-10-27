package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.probe.ProbeException
import tv.cleanyour.core.probe.ProbeStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issues #788 and #1112 (FFalcon 65S585C Pro, the TCL China tcl_mt5879_cn set): every run stopped
// with "setup: probe failed probe_failed(facts)" and nothing more, so the report could not say which
// read the TV refused. The line now carries the prober's own fixed detail.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue788ProbeDetailTest {
    private suspend fun TestScope.toFound(h: Harness, m: SetupMachine) {
        h.discovery.emit(DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
    }

    @Test fun theProbeFailedLineSaysWhatTheProberFound() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.snapshotFor = { throw ProbeException(ProbeStage.FACTS, "getprop has no ro.product.model") }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertEquals(StopCase.PROBE_FAILED, assertIs<SetupState.Stopped>(m.state.value).card.case)
        assertTrue("setup: probe failed probe_failed(facts): getprop has no ro.product.model" in h.logLines, h.logLines.toString())
    }

    @Test fun whatTheTvPrintedAfterTheColonStaysOut() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.snapshotFor = { throw ProbeException(ProbeStage.PACKAGES, "exit 1: /system/bin/sh: cmd: inaccessible 192.168.1.20") }
        val m = h.setup(backgroundScope)
        toFound(h, m)
        assertTrue("setup: probe failed probe_failed(packages): exit 1" in h.logLines, h.logLines.toString())
        assertTrue(h.logLines.none { "192.168" in it || "inaccessible" in it }, h.logLines.toString())
    }
}
