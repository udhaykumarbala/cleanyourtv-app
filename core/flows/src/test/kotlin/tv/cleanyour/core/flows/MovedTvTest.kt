package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TvId
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// RFC-RECONNECT-1 (issue #1578, a mesh Wi-Fi moved the TV from .111 to .104): a saved TV whose address no longer
// answers is looked for on the network, matched by its TvId with the key it already took, and saved at its new
// address. A different TV, or one that never allowed this phone, is never connected and sees no Allow dialog.
@OptIn(ExperimentalCoroutinesApi::class)
class MovedTvTest {
    private val newHost = "192.0.2.44"
    private fun found(host: String, open: Boolean? = true) = FoundTv(host, "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), port5555Open = open,
        pairingService = null, connectService = null, lastSeenMs = Samples.NOW_MS)

    @Test fun theSameTvAtANewAddressIsFoundAndSaved() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.sessions.register(newHost, h.session)          // the same TV (same TvId), now at .44
        h.discovery.open5555[newHost] = true
        h.discovery.emit(DiscoveryEvent.Found(found(newHost)))
        var result: AccessResult? = null
        backgroundScope.launch { result = h.access.session(tv.id) }
        settle()
        assertIs<Reach.NeedsDebugging>(h.access.reach.value, "the saved address is tried first, as before")
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        assertEquals(listOf(Endpoint.PlainTcp(newHost)), h.tvs.tvs.value.single().endpoints)
        assertEquals(listOf(Endpoint.PlainTcp(newHost)), h.sessions.knownOpens.toList())
        assertTrue(h.sessions.opens.none { it.host == newHost }, "never the open that can raise a dialog")
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    // 0.4.8: the shared search hands the reconnect search each TV as it was last seen, so a TV seen while its debugging
    // was off still says port 5555 closed after it was switched on. Such a host is probed, once per round, not skipped.
    @Test fun aTvLastSeenWithItsPortClosedIsProbedAgain() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.sessions.register(newHost, h.session)
        h.discovery.open5555[newHost] = true             // debugging is on again now
        h.discovery.emit(DiscoveryEvent.Found(found(newHost, open = false)))
        var result: AccessResult? = null
        backgroundScope.launch { result = h.access.session(tv.id) }
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        assertEquals(listOf(Endpoint.PlainTcp(newHost)), h.tvs.tvs.value.single().endpoints)
        assertTrue(h.sessions.opens.none { it.host == newHost }, "only the known-TV handshake")
    }

    @Test fun anotherTvOnTheNetworkIsNeverConnected() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val other = FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = TvId("0123456789abcdef")))
        h.sessions.register(newHost, other)              // a second TV that took this phone's key (the person's other TV)
        h.sessions.register("192.0.2.45", h.session)
        h.sessions.allowPromptFor["192.0.2.45"] = "AB:CD"  // a TV that has not allowed this phone
        h.discovery.open5555[newHost] = true
        h.discovery.open5555["192.0.2.45"] = true
        h.discovery.emit(DiscoveryEvent.Found(found(newHost)))
        h.discovery.emit(DiscoveryEvent.Found(found("192.0.2.45")))
        var result: AccessResult? = null
        val job = backgroundScope.launch { result = h.access.session(tv.id) }
        settle(12_000)
        assertNull(result, "still waiting at the debugging step")
        assertIs<Reach.NeedsDebugging>(h.access.reach.value)
        assertEquals(setOf(newHost, "192.0.2.45"), h.sessions.knownOpens.map { it.host }.toSet())
        assertNull(h.sessions.existing(TvId("0123456789abcdef")), "the other TV got no session")
        assertEquals(tv.endpoints, h.tvs.tvs.value.single().endpoints, "nothing saved")
        assertTrue(h.logLines.isEmpty() || h.logLines.none { it.contains("new address") })
        job.cancel()
    }

    // "Add a TV" finds the saved TV at its new address (the report's "Is this your TV?" step): it is not a new TV, so
    // no identity check, and its record moves to the new address.
    @Test fun addATvKnowsASavedTvAtANewAddress() = runTest(timeout = 30.seconds) {
        val h = Harness()
        h.tvs.upsert(Samples.tv())                       // saved at 192.0.2.10
        h.sessions.register(newHost, h.session)
        val m = h.setup(backgroundScope)
        h.discovery.emit(DiscoveryEvent.Found(found(newHost, open = false))); settle()
        m.dispatch(SetupIntent.Start); settle()
        h.discovery.open5555[newHost] = true
        settle(2_100)
        assertNull(m.extras.value.identity)
        assertTrue(h.logLines.any { it.contains("identity check not needed, a saved TV at a new address") }, h.logLines.toString())
    }

    @Test fun theSavedAddressStillWinsWhenItAnswers() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        val result = h.access.session(tv.id)
        assertIs<AccessResult.Ok>(result)
        assertTrue(h.sessions.knownOpens.isEmpty(), "no search when the saved address answers")
        assertEquals(0, h.discovery.activeCollectors)
    }
}
