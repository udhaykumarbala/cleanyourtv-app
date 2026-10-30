package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.testing.fixtures.FakeDiscovery
import tv.cleanyour.testing.fixtures.Samples
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// 0.4.8 (beta4 follow-up): the debugging step's search and "Copy connection details" (or Setup's search) ran two NSD
// searches side by side. SharedDiscovery runs one for every collector, and a late collector still sees every TV.
@OptIn(ExperimentalCoroutinesApi::class)
class SharedDiscoveryTest {
    private fun found(host: String, open: Boolean? = null) =
        FoundTv(host, "Mi TV 4A", null, setOf(DiscoverySource.GOOGLE_CAST), open, null, null, Samples.NOW_MS)

    @Test fun twoCollectorsShareOneSearchAndTheLastOneStopsIt() = runTest(timeout = 30.seconds) {
        val inner = FakeDiscovery()
        val shared = SharedDiscovery(inner, backgroundScope)
        val a = backgroundScope.launch { shared.discover().collect { } }
        settle()
        assertEquals(1, inner.activeCollectors)
        val b = backgroundScope.launch { shared.discover().collect { } }
        settle()
        assertEquals(1, inner.activeCollectors, "the second collector joins the search that runs")
        a.cancel(); settle()
        assertEquals(1, inner.activeCollectors, "still wanted by the other")
        b.cancel(); settle()
        assertEquals(0, inner.activeCollectors, "the search stops with its last collector")
        val c = backgroundScope.launch { shared.discover().collect { } }
        settle()
        assertEquals(1, inner.activeCollectors, "and starts again for the next")
        c.cancel()
    }

    @Test fun aLateCollectorSeesEveryTvFoundSoFarThenTheChanges() = runTest(timeout = 30.seconds) {
        val inner = FakeDiscovery()
        val shared = SharedDiscovery(inner, backgroundScope)
        val first = CopyOnWriteArrayList<DiscoveryEvent>()
        val late = CopyOnWriteArrayList<DiscoveryEvent>()
        backgroundScope.launch { shared.discover().collect { first += it } }
        settle()
        val net = DiscoveryEvent.NetworkState(onWifi = true, subnet = "192.0.2.0/24")
        inner.emit(net)
        inner.emit(DiscoveryEvent.Found(found("192.0.2.10")))
        inner.emit(DiscoveryEvent.Found(found("192.0.2.11")))
        settle()
        backgroundScope.launch { shared.discover().collect { late += it } }
        settle()
        assertEquals(listOf(net, DiscoveryEvent.Found(found("192.0.2.10")), DiscoveryEvent.Found(found("192.0.2.11"))), late.toList())
        inner.emit(DiscoveryEvent.Updated(found("192.0.2.10", open = true)))
        inner.emit(DiscoveryEvent.Lost("192.0.2.11"))
        settle()
        val changes = listOf(DiscoveryEvent.Updated(found("192.0.2.10", open = true)), DiscoveryEvent.Lost("192.0.2.11"))
        assertEquals(changes, late.drop(3))
        assertEquals(changes, first.takeLast(2))
        assertEquals(1, inner.activeCollectors)
    }

    // The case behind the follow-up: the step's reconnect search runs, and "Copy connection details" searches too.
    @Test fun theStepsSearchAndTheDetailsSearchAreOneSearch() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val shared = SharedDiscovery(h.discovery, backgroundScope)
        val access = TvAccess(h.sessions, shared, h.tvs, h.clock)
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        backgroundScope.launch { access.session(tv.id) }
        settle(3_000)                                      // the saved address is closed: the step searches
        assertEquals(1, h.discovery.activeCollectors)
        val details = backgroundScope.launch { withTimeoutOrNull(10_000) { shared.discover().collect { } } }
        settle(1_000)
        assertEquals(1, h.discovery.activeCollectors, "one NSD search, not two")
        settle(10_000)
        assertTrue(details.isCompleted)
        assertEquals(1, h.discovery.activeCollectors, "the step's search goes on")
    }
}
