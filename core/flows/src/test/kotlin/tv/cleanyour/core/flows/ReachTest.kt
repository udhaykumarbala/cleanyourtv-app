package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.TvId
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// RFC-REACH-1 (a user stuck at "Waiting for the TV..." after the network changed): the subnet is a hint, not a gate.
// On any Wi-Fi the saved addresses are probed and the network is searched; the TvId check keeps another TV out (D37).
@OptIn(ExperimentalCoroutinesApi::class)
class ReachTest {
    private val newSubnet = "198.51.100.0/24"
    private fun otherNetwork(h: Harness) = h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = true, subnet = newSubnet))

    @Test fun aDifferentSubnetStillProbesConnectsAndSavesTheNewSubnet() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        h.discovery.open5555[HOST] = true
        val result = h.access.session(tv.id)
        assertIs<AccessResult.Ok>(result)
        assertTrue(HOST in h.discovery.probes, "the saved address is probed on the new network")
        assertEquals(newSubnet, h.tvs.tvs.value.single().lastSubnet, "the new network is saved as the TV's")
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    @Test fun aDifferentSubnetShowsTheDebuggingStepAndSaysTheNetworkChanged() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv.copy(debugging = DebugState.OFF))
        otherNetwork(h)
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle()
        assertIs<Reach.NeedsDebugging>(h.access.reach.value, "never WrongNetwork on a Wi-Fi")
        val c = h.access.check.value
        assertEquals(Seen.YES, c.onWifi)
        assertEquals(Seen.NO, c.sameNetwork)
        assertEquals(Seen.NO, c.savedAnswers)
        assertTrue(c.switchedOff)
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(job.isCompleted)
        assertEquals(newSubnet, h.tvs.tvs.value.single().lastSubnet)
    }

    @Test fun theNetworkIsSearchedOnADifferentSubnet() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        val newHost = "198.51.100.44"
        h.sessions.register(newHost, h.session)
        h.discovery.open5555[newHost] = true
        h.discovery.emit(DiscoveryEvent.Found(FoundTv(newHost, "Mi TV 4A", null, setOf(DiscoverySource.GOOGLE_CAST), true, null, null, Samples.NOW_MS)))
        var result: AccessResult? = null
        backgroundScope.launch { result = h.access.session(tv.id) }
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        val saved = h.tvs.tvs.value.single()
        assertEquals(listOf(Endpoint.PlainTcp(newHost)), saved.endpoints)
        assertEquals(newSubnet, saved.lastSubnet)
        assertTrue(h.sessions.opens.none { it.host == newHost }, "only the known-TV handshake on a found address")
    }

    @Test fun notOnWifiIsWrongNetworkAndNothingIsProbed() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = false, subnet = null))
        h.discovery.open5555[HOST] = true
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle(10_000)
        assertIs<Reach.WrongNetwork>(h.access.reach.value)
        assertTrue(h.discovery.probes.isEmpty(), "nothing to try off Wi-Fi")
        assertTrue(h.sessions.opens.isEmpty() && h.sessions.knownOpens.isEmpty())
        assertEquals(0, h.discovery.activeCollectors, "no search off Wi-Fi")
        assertEquals(Seen.NO, h.access.check.value.onWifi)
        // Back on Wi-Fi, it carries on by itself.
        h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = true, subnet = "192.0.2.0/24"))
        settle(2_100)
        assertTrue(job.isCompleted)
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    @Test fun aDifferentTvAtTheSavedAddressIsStillRefused() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val otherId = TvId("0123456789abcdef")
        h.sessions.register(HOST, FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = otherId)))
        h.discovery.open5555[HOST] = true
        val result = h.access.session(tv.id)
        assertIs<AccessResult.Failed>(result)
        assertIs<AdbError.Policy.WrongTv>(result.error)
        assertNull(h.sessions.existing(otherId), "the other TV got no session")
        assertEquals(tv, h.tvs.tvs.value.single(), "nothing saved")
        assertEquals("wrong_tv", h.access.lastHandshake(tv.id))
    }

    // On a network other than the setup's the saved address may be a stranger's TV: only the known-TV handshake,
    // so no Allow dialog shows there, and a different TV at that address does not end the wait.
    @Test fun onAnotherNetworkTheSavedAddressGetsOnlyTheKnownHandshakeAndTheWaitGoesOn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        val otherId = TvId("0123456789abcdef")
        h.sessions.register(HOST, FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = otherId)))
        h.discovery.open5555[HOST] = true
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle(4_100)
        assertFalse(job.isCompleted, "a different TV at the old address is no reason to stop looking")
        assertIs<Reach.NeedsDebugging>(h.access.reach.value)
        assertTrue(h.sessions.opens.isEmpty(), "never the open that can show the Allow dialog on another network")
        assertTrue(Endpoint.PlainTcp(HOST) in h.sessions.knownOpens)
        assertNull(h.sessions.existing(otherId), "the other TV got no session")
        assertEquals(tv, h.tvs.tvs.value.single(), "nothing saved")
    }

    @Test fun onAnotherNetworkATvThatNeedsAllowShowsNoDialog() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        h.sessions.allowPromptFor[HOST] = "ab:cd"
        h.discovery.open5555[HOST] = true
        backgroundScope.launch { h.access.session(tv.id) }
        settle(2_100)
        assertTrue(h.sessions.opens.isEmpty())
        assertFalse(h.access.reach.value is Reach.AwaitingAllow, "no Allow prompt is raised on another network")
    }

    @Test fun quietReconnectsAcrossASubnetChange() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        h.discovery.open5555[HOST] = true
        assertNotNull(h.access.quiet(tv.id))
        assertEquals(listOf(Endpoint.PlainTcp(HOST)), h.sessions.knownOpens.toList())
        assertTrue(h.sessions.opens.isEmpty(), "never the open that can show the Allow dialog")
        assertEquals(newSubnet, h.tvs.tvs.value.single().lastSubnet)
        assertEquals(Reach.Idle, h.access.reach.value)
    }

    @Test fun quietDoesNothingOffWifi() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.discovery.emit(DiscoveryEvent.NetworkState(onWifi = false, subnet = null))
        h.discovery.open5555[HOST] = true
        assertNull(h.access.quiet(tv.id))
        assertTrue(h.discovery.probes.isEmpty())
    }

    @Test fun aStateChangeIsLoggedOnceNotOnEveryPoll() = runTest(timeout = 60.seconds) {
        val h = Harness()
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val access = TvAccess(h.sessions, h.discovery, h.tvs, h.clock, log = FlowLog { lines += it })
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        val job = backgroundScope.launch { access.session(tv.id) }
        settle(12_000)                                   // six polls, the first search still running
        val state = lines.filter { it.startsWith("access: needs debugging") }
        assertEquals(1, state.size, lines.toString())
        assertTrue(state.single().contains("wifi yes; network different from setup; 1 saved address; 5555 closed"), state.single())
        assertTrue(lines.none { it.contains("192.0.2") || it.contains("198.51.100") }, "no address or subnet in the log")
        settle(8_000)                                    // the search ended: one more line with its counts
        assertEquals(2, lines.count { it.startsWith("access: needs debugging") }, lines.toString())
        settle(14_000)                                   // more polls, nothing new
        assertEquals(2, lines.count { it.startsWith("access: needs debugging") }, lines.toString())
        assertTrue(access.check.value.stuck, "30 s with the saved address closed and nothing found")
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(job.isCompleted)
        assertTrue(lines.last().startsWith("access: connected at a saved address, on a network other than the setup's"), lines.last())
    }

    @Test fun theAllowPromptIsItsOwnState() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val access = TvAccess(h.sessions, h.discovery, h.tvs, h.clock, log = FlowLog { lines += it })
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        h.discovery.open5555[HOST] = true
        h.sessions.allowPromptFor[HOST] = "AB:CD"          // the fake reports the prompt, then connects
        assertIs<AccessResult.Ok>(access.session(tv.id))
        assertTrue(lines.any { it.startsWith("access: waiting for Allow on the TV; wifi yes; network same as setup") }, lines.toString())
        assertEquals(ReachCheck(), access.check.value, "reset once the step closes")
    }

    @Test fun theCheckSummaryHasFixedWordsOnly() {
        val c = ReachCheck(Seen.YES, Seen.NO, Seen.NO, Seen.CHECKING, Seen.NO, switchedOff = true, waitedMs = 34_500)
        assertEquals("wifi yes, same network no, 5555 no, found elsewhere checking, allow asked no, waited 34 s", c.summary())
        assertFalse(c.stuck, "still searching")
        assertTrue(c.copy(foundElsewhere = Seen.NO).stuck)
        assertFalse(c.copy(foundElsewhere = Seen.NO, waitedMs = 20_000).stuck)
    }
    // 0.4.8 (beta4 follow-up): "Enter the TV's address" on the step. Setup's rule for a typed address, the typed one is
    // tried first, and it is saved only once this TV answers there.
    @Test fun aTypedAddressIsTriedAndSavedOnceThisTvAnswers() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val access = TvAccess(h.sessions, h.discovery, h.tvs, h.clock, log = FlowLog { lines += it })
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        var result: AccessResult? = null
        backgroundScope.launch { result = access.session(tv.id) }
        settle()
        assertIs<Reach.NeedsDebugging>(access.reach.value)
        assertFalse(access.tryAddress(tv.id, "8.8.8.8"), "not an address on a home network, as in Setup")
        assertNull(access.check.value.typed)
        assertTrue(access.tryAddress(tv.id, " $TYPED "))
        assertEquals(Seen.CHECKING, access.check.value.typed)
        h.sessions.register(TYPED, h.session)
        h.discovery.open5555[TYPED] = true
        settle(2_100)
        assertIs<AccessResult.Ok>(result)
        assertEquals(listOf(Endpoint.PlainTcp(TYPED)), h.tvs.tvs.value.single().endpoints, "the typed address replaces the saved one")
        assertTrue(Endpoint.PlainTcp(TYPED) in h.sessions.opens, "on the setup's network the handshake is Setup's")
        assertTrue(lines.any { it == "access: connected at the address typed on the phone (saved there)" }, lines.toString())
        assertTrue(lines.none { it.contains(TYPED) }, "the typed address is never logged")
        assertEquals(ReachCheck(), access.check.value)
    }

    @Test fun onAnotherNetworkATypedAddressGetsOnlyTheKnownHandshake() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        otherNetwork(h)
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle()
        h.sessions.register(TYPED, h.session)
        h.sessions.allowPromptFor[TYPED] = "AB:CD"       // this TV has not allowed the phone at that address
        h.discovery.open5555[TYPED] = true
        assertTrue(h.access.tryAddress(tv.id, TYPED))
        settle(4_100)
        assertFalse(job.isCompleted, "no Allow dialog, and the wait goes on")
        assertTrue(h.sessions.opens.isEmpty(), "never the open that can show the Allow dialog on another network")
        assertTrue(Endpoint.PlainTcp(TYPED) in h.sessions.knownOpens)
        assertEquals(tv.endpoints, h.tvs.tvs.value.single().endpoints, "nothing saved")
        assertEquals(Seen.YES, h.access.check.value.typed)
        h.sessions.allowPromptFor.remove(TYPED)          // the TV takes the key it already knows
        settle(2_100)
        assertTrue(job.isCompleted)
        assertEquals(listOf(Endpoint.PlainTcp(TYPED)), h.tvs.tvs.value.single().endpoints)
    }

    @Test fun aDifferentTvAtATypedAddressEndsNothingAndIsNotSaved() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val otherId = TvId("0123456789abcdef")
        h.sessions.register(TYPED, FakeTvSession(identity = Fixtures.miTv().identity().copy(tvId = otherId)))
        h.discovery.open5555[TYPED] = true
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle()
        assertTrue(h.access.tryAddress(tv.id, TYPED))
        settle(2_100)
        assertFalse(job.isCompleted, "a wrong address typed is no reason to stop waiting")
        assertEquals(Seen.NO, h.access.check.value.typed)
        assertIs<Reach.NeedsDebugging>(h.access.reach.value)
        assertNull(h.sessions.existing(otherId), "the other TV got no session")
        assertEquals(tv, h.tvs.tvs.value.single(), "nothing saved")
        val tries = h.discovery.probes.count { it == TYPED }
        settle(4_100)
        assertEquals(tries, h.discovery.probes.count { it == TYPED }, "the wrong address is dropped")
        h.discovery.open5555[HOST] = true
        settle(2_100)
        assertTrue(job.isCompleted, "the saved address still works")
    }

    @Test fun anAddressTypedForAnotherTvIsNotTried() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = Samples.tv()
        h.tvs.upsert(tv)
        val job = backgroundScope.launch { h.access.session(tv.id) }
        settle()
        assertTrue(h.access.tryAddress(TvId("0123456789abcdef"), TYPED))
        settle(4_100)
        assertTrue(TYPED !in h.discovery.probes)
        assertFalse(job.isCompleted)
    }

    @Test fun theSummaryNamesATypedAddressOnlyWhenOneWasTyped() {
        val c = ReachCheck(Seen.YES, Seen.YES, Seen.NO, Seen.NO, Seen.NO, waitedMs = 12_000)
        assertFalse(c.summary().contains("typed"))
        assertEquals("wifi yes, same network yes, 5555 no, found elsewhere no, allow asked no, typed address no, waited 12 s",
            c.copy(typed = Seen.NO).summary())
    }
}
