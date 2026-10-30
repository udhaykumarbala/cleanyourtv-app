package tv.cleanyour.core.flows

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.ConnectionState
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionHold
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.TvRepository

// What the app shows while a returning write waits for the TV (UX 4.3, screen M1a). The app
// observes `reach` and lays the debugging step over whatever screen asked.
sealed interface Reach {
    data object Idle : Reach
    data class Connecting(val tvId: TvId, val tvName: String) : Reach
    data class NeedsDebugging(val tvId: TvId, val tvName: String) : Reach      // M1a: port 5555 closed
    data class AwaitingAllow(val tvId: TvId, val tvName: String, val fingerprint: String) : Reach   // after "Reset my key"
    data class WrongNetwork(val tvId: TvId, val tvName: String) : Reach        // the phone is not on Wi-Fi (RFC-REACH-1)
}

// The TV the debugging step waits for, or null when it is closed.
fun Reach.tvIdOrNull(): TvId? = when (this) {
    is Reach.NeedsDebugging -> tvId
    is Reach.Connecting -> tvId
    is Reach.AwaitingAllow -> tvId
    is Reach.WrongNetwork -> tvId
    Reach.Idle -> null
}

// RFC-REACH-1: one answer of "What the app sees" under the debugging step. CHECKING until the first
// look; UNKNOWN when there is nothing to look at (no Wi-Fi, or no subnet saved for this TV).
enum class Seen { YES, NO, CHECKING, UNKNOWN }

// RFC-REACH-1: what the app sees while the debugging step waits, so the person (and a report) can
// tell which case it is. Updated each poll round; immutable, never holds an address or a subnet.
data class ReachCheck(
    val onWifi: Seen = Seen.CHECKING,
    val sameNetwork: Seen = Seen.CHECKING,      // as when the TV was set up; NO is a hint, never a gate
    val savedAnswers: Seen = Seen.CHECKING,     // port 5555 at a saved address
    val foundElsewhere: Seen = Seen.CHECKING,   // the network search of RFC-RECONNECT-1
    val asksToAllow: Seen = Seen.NO,            // the TV shows "Allow USB debugging?"
    val switchedOff: Boolean = false,           // the app switched debugging off when it was done, as planned
    val waitedMs: Long = 0,
    val typed: Seen? = null,                    // port 5555 at the address typed on the sheet; null: none typed
) {
    // About 30 s with the saved address closed and nothing found: the sheet adds the restart hint.
    val stuck: Boolean get() = waitedMs >= STUCK_MS && savedAnswers == Seen.NO && foundElsewhere == Seen.NO

    // Fixed words for a report's card line; never an address.
    fun summary(): String = "wifi ${w(onWifi)}, same network ${w(sameNetwork)}, 5555 ${w(savedAnswers)}, " +
        "found elsewhere ${w(foundElsewhere)}, allow asked ${w(asksToAllow)}, " +
        (typed?.let { "typed address ${w(it)}, " } ?: "") + "waited ${waitedMs / 1000} s"

    companion object {
        const val STUCK_MS = 30_000L
        private fun w(s: Seen) = s.name.lowercase()
    }
}

sealed interface AccessResult {
    data class Ok(val session: TvSession) : AccessResult
    data object Cancelled : AccessResult
    data class Failed(val error: AdbError) : AccessResult    // WrongTv (D37), NoShellV2, auth
    data object Unknown : AccessResult                        // no record for this TvId
}

// Connects to a TV the phone already knows, for any write outside the first-run wizard. A live
// session is reused; otherwise each remembered address is probed, and while port 5555 stays
// closed the debugging step shows and the probe repeats every 2 s until the TV answers or the
// person cancels. `expected` is always the TvId, so a different TV at the address never gets a
// line (D37).
//
// RFC-RECONNECT-1 (issue #1578, a mesh Wi-Fi gave the TV a new address): when no saved address answers, the TV is
// looked for on the network the way "Add a TV" finds TVs (Discovery), and every TV found at another address with
// port 5555 open is tried with openKnown: the app's key signs and nothing more, so a TV that never allowed this phone
// sees no Allow dialog, and one that takes the key must have this TvId (a different TV is never connected). The TV
// found is saved at its new address. A search lasts `searchMs` and runs again every `searchEveryMs` while the
// debugging step waits.
//
// RFC-REACH-1 (a user stuck at "Waiting for the TV..." after the network changed): D19 detects a different network by
// link subnet, and the subnet used to be a gate: on a new router, a reset router or a guest network the saved
// address was never probed and the network never searched, so the step waited for good. The subnet is now a hint
// shown to the person ("What the app sees"), not a gate: on any Wi-Fi the saved addresses are probed and the network
// is searched, and the TvId check (D37) keeps a different TV from ever getting a line. Only a phone that is not on
// Wi-Fi is WrongNetwork. Each change of state is one log line, for "Report a problem".
@OptIn(ExperimentalCoroutinesApi::class)   // CompletableDeferred.getCompleted, after isCompleted
class TvAccess(
    private val sessions: SessionManager,
    private val discovery: Discovery,
    private val tvs: TvRepository,
    private val clock: Clock,
    private val pollMs: Long = 2_000,
    private val giveUpMs: Long = 10 * 60_000,
    private val searchMs: Long = 15_000,
    private val searchEveryMs: Long = 30_000,
    private val log: FlowLog = FlowLog { },
) {
    private val mutableReach = MutableStateFlow<Reach>(Reach.Idle)
    val reach: StateFlow<Reach> = mutableReach.asStateFlow()
    private val mutableCheck = MutableStateFlow(ReachCheck())
    val check: StateFlow<ReachCheck> = mutableCheck.asStateFlow()   // RFC-REACH-1
    // RFC-REACH-1: the last search's counts (other addresses tried, those that answered but were not this TV) and the
    // last state logged, so a log line is written on a change only, never on every poll.
    private val searchCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    private val logged = MutableStateFlow<String?>(null)
    // The last handshake error of each TV, as LogCodes names it (R8 renames classes), for "Copy connection details".
    private val handshakes = java.util.concurrent.ConcurrentHashMap<TvId, String>()

    fun lastHandshake(tvId: TvId): String? = handshakes[tvId]
    private val cancelGen = MutableStateFlow(0L)
    // 0.4.8: the address typed on the debugging step ("Enter the TV's address"), for the TV it was typed for.
    private val typed = MutableStateFlow<Pair<TvId, String>?>(null)

    fun cancelWaiting() { cancelGen.value = cancelGen.value + 1 }

    // 0.4.8 (RFC-REACH-1, follow-up): "Enter the TV's address" on the debugging step, with Setup's rule for a typed
    // address (isHomeAddress, S1c); false when it is not one, so the sheet says so. The wait tries it first from the next
    // round, with the rules of a saved address: on a network other than the setup's only the known-TV handshake (no Allow
    // dialog on a stranger's TV), and the TvId check always (D37). It is saved only once this TV answers there; a
    // different TV at it ends nothing. Never logged: the line says only that an address was typed.
    fun tryAddress(tvId: TvId, text: String): Boolean {
        if (!isHomeAddress(text)) return false
        typed.value = tvId to text.trim()
        mutableCheck.update { it.copy(typed = Seen.CHECKING) }
        log.line("access: an address was typed for the TV")
        return true
    }

    // T7 item 1: AdbTvSession closes its socket 60 s after the last hold, and the engine refuses
    // NOT_CONNECTED on an idle session. The wizard keeps one hold per TV from the connect until Done
    // or a stop card, so a slow read of "What we found" keeps the session (and its keepalive ping).
    private val held = HashMap<TvId, Pair<TvSession, SessionHold>>()

    fun keep(session: TvSession) {
        val old = synchronized(held) {
            val now = held[session.tvId]
            if (now?.first === session) return
            held.put(session.tvId, session to session.hold())
        }
        old?.second?.close()
    }

    fun release(tvId: TvId) { synchronized(held) { held.remove(tvId) }?.second?.close() }

    fun holding(tvId: TvId): Boolean = synchronized(held) { tvId in held }

    fun live(tvId: TvId): TvSession? = sessions.existing(tvId)?.takeIf { it.state.value is ConnectionState.Connected }

    suspend fun session(tvId: TvId): AccessResult {
        live(tvId)?.let { return AccessResult.Ok(it) }
        val tv = tvs.tvs.value.firstOrNull { it.id == tvId } ?: return AccessResult.Unknown
        val gen = cancelGen.value
        val hosts = tv.endpoints.filterIsInstance<Endpoint.PlainTcp>()
        mutableCheck.value = ReachCheck(switchedOff = tv.debugging == DebugState.OFF)
        searchCounts.value = null
        logged.value = null
        typed.value = null
        try {
            return coroutineScope {
                // RFC-RECONNECT-1: after one round at the saved addresses, the network is searched beside the polling,
                // so the debugging step and a TV that answers at its saved address are as before.
                val moved = CompletableDeferred<Pair<Endpoint, TvSession>>()
                val searcher = launch {
                    delay(pollMs)
                    while (true) {
                        // RFC-REACH-1: on any Wi-Fi, also one that is not the network of the setup. Off Wi-Fi it looks
                        // again soon, so a phone back on Wi-Fi does not wait a whole search interval.
                        if (!discovery.network().value.onWifi) { delay(pollMs); continue }
                        val hit = moved(tv, hosts.map { it.host }.toSet())
                        if (hit != null) { mutableCheck.update { it.copy(foundElsewhere = Seen.YES) }; moved.complete(hit); return@launch }
                        mutableCheck.update { it.copy(foundElsewhere = Seen.NO) }
                        delay(searchEveryMs)
                    }
                }
                try { poll(tv, gen, hosts, moved) } finally { searcher.cancel() }
            }
        } finally {
            mutableReach.value = Reach.Idle
            mutableCheck.value = ReachCheck()
            typed.value = null
        }
    }

    private suspend fun poll(tv: Tv, gen: Long, hosts: List<Endpoint.PlainTcp>, moved: CompletableDeferred<Pair<Endpoint, TvSession>>): AccessResult {
        val tvId = tv.id
        var waited = 0L
        while (true) {
            if (cancelGen.value != gen) return AccessResult.Cancelled
            val net = discovery.network().value
            if (moved.isCompleted) {
                val (endpoint, session) = moved.getCompleted()
                val now = current(tvId, tv)
                // The new address replaces the saved ones: the old one may be another device's now.
                tvs.upsert(now.copy(endpoints = listOf(endpoint) + now.endpoints.filter { it !is Endpoint.PlainTcp },
                    lastSeenAt = clock.nowMs(), debugging = DebugState.ON, lastSubnet = net.subnet ?: tv.lastSubnet))
                log.line("access: the TV answered at a new address and was saved there")
                return AccessResult.Ok(session)
            }
            // RFC-REACH-1: the subnet is a hint for the person, never a gate; the TvId check keeps it safe (D37).
            val same = when {
                !net.onWifi || tv.lastSubnet == null || net.subnet == null -> Seen.UNKNOWN
                net.subnet == tv.lastSubnet -> Seen.YES
                else -> Seen.NO
            }
            if (!net.onWifi) {
                mutableReach.value = Reach.WrongNetwork(tvId, tv.nickname)
                mutableCheck.update { it.copy(onWifi = Seen.NO, sameNetwork = same, savedAnswers = Seen.UNKNOWN, foundElsewhere = Seen.UNKNOWN,
                    asksToAllow = Seen.NO, waitedMs = waited) }
                logState(net, same, hosts.size, null, null)
            } else {
                // 0.4.8: an address typed on the sheet is tried first; the saved ones when it does not answer.
                val typedHost = typed.value?.takeIf { it.first == tvId }?.second
                val typedOpen = typedHost?.let { discovery.probe5555(it) }
                val saved = if (typedOpen == true) null else hosts.firstOrNull { it.host != typedHost && discovery.probe5555(it.host) }
                val open = if (typedOpen == true && typedHost != null) Endpoint.PlainTcp(typedHost) else saved
                val isTyped = typedOpen == true && hosts.none { it.host == typedHost }
                mutableCheck.update { it.copy(onWifi = Seen.YES, sameNetwork = same,
                    savedAnswers = if (typedOpen == true) it.savedAnswers else if (saved != null) Seen.YES else Seen.NO,
                    foundElsewhere = if (it.foundElsewhere == Seen.UNKNOWN) Seen.CHECKING else it.foundElsewhere, waitedMs = waited,
                    typed = typedOpen?.let { o -> if (o) Seen.YES else Seen.NO } ?: it.typed) }
                var handshake: AdbError? = null
                if (open != null) {
                    mutableReach.value = Reach.Connecting(tvId, tv.nickname)
                    // RFC-REACH-1: on a network other than the setup's, the saved address may be someone else's TV now
                    // (an office or a friend's Wi-Fi), so only the known-TV handshake: no Allow dialog on a stranger's
                    // TV, and a refusal there is no reason to stop looking.
                    val elsewhere = same == Seen.NO
                    val r = if (elsewhere) attempt { sessions.openKnown(open, tvId) }.getOrElse { OpenResult.Failed(AdbError.Transport.Unreachable) }
                    else sessions.open(open, tvId) { fp ->
                        mutableReach.value = Reach.AwaitingAllow(tvId, tv.nickname, fp)
                        mutableCheck.update { it.copy(asksToAllow = Seen.YES) }
                        logState(net, same, hosts.size, true, null)
                    }
                    mutableCheck.update { it.copy(asksToAllow = Seen.NO) }
                    when (r) {
                        is OpenResult.Ok -> {
                            val now = current(tvId, tv)
                            // A typed address that answered as this TV replaces the saved ones, as a TV found elsewhere does.
                            val endpoints = if (isTyped) listOf(open) + now.endpoints.filter { it !is Endpoint.PlainTcp } else now.endpoints
                            tvs.upsert(now.copy(endpoints = endpoints, lastSeenAt = clock.nowMs(), debugging = DebugState.ON, lastSubnet = net.subnet ?: tv.lastSubnet))
                            log.line("access: connected at ${if (isTyped) "the address typed on the phone (saved there)" else "a saved address"}" +
                                if (same == Seen.NO) ", on a network other than the setup's (saved as the TV's network now)" else "")
                            return AccessResult.Ok(r.session)
                        }
                        is OpenResult.Failed -> {
                            handshake = r.error
                            handshakes[tvId] = LogCodes.of(r.error)
                            // A typed address where a different TV answers was a wrong address: it is dropped and the wait
                            // goes on at the saved ones.
                            val wrongTyped = typedOpen == true && r.error is AdbError.Policy.WrongTv
                            if (wrongTyped) {
                                typedHost?.let { typed.compareAndSet(tvId to it, null) }
                                mutableCheck.update { it.copy(typed = Seen.NO) }
                                log.line("access: a different TV answered at the address typed on the phone")
                            }
                            when (r.error) {
                                is AdbError.Policy.WrongTv, AdbError.Transport.NoShellV2, AdbError.Auth.Denied -> if (!elsewhere && !wrongTyped) {
                                    logState(net, same, hosts.size, true, r.error, final = true)
                                    return AccessResult.Failed(r.error)
                                }
                                else -> {}
                            }
                        }
                    }
                }
                mutableReach.value = Reach.NeedsDebugging(tvId, tv.nickname)
                logState(net, same, hosts.size, open != null, handshake)
            }
            if (waited >= giveUpMs) return AccessResult.Cancelled
            withTimeoutOrNull(pollMs) { moved.await() }   // a poll interval, cut short when the search finds the TV
            waited += pollMs
        }
    }

    // RFC-RECONNECT-1: the TV at an address it was not saved at, or null. Each host is tried once per search, when it
    // is found with port 5555 open or not yet probed; openKnown refuses a TV that has not allowed this phone (no
    // dialog) and any other TV (WrongTv), so only this TV can answer.
    private suspend fun moved(tv: Tv, saved: Set<String>): Pair<Endpoint, TvSession>? {
        val tried = HashSet<String>()
        var hit: Pair<Endpoint, TvSession>? = null
        var others = 0
        withTimeoutOrNull(searchMs) {
            discovery.discover().firstOrNull { e ->
                val found = when (e) { is DiscoveryEvent.Found -> e.tv; is DiscoveryEvent.Updated -> e.tv; else -> null }
                if (found == null || found.host in saved || found.port5555Open == false || found.host in tried) return@firstOrNull false
                tried += found.host
                if (found.port5555Open != true && !discovery.probe5555(found.host)) return@firstOrNull false
                val endpoint = Endpoint.PlainTcp(found.host)
                when (val r = attempt { sessions.openKnown(endpoint, tv.id) }.getOrNull()) {
                    is OpenResult.Ok -> { hit = endpoint to r.session; true }
                    else -> { others++; false }
                }
            }
        }
        if (hit == null) {
            searchCounts.value = tried.size to others
            log.line("access: looked for the TV on the network: ${tried.size} other addresses, $others answered but were not this TV or had not allowed this phone")
        }
        return hit
    }

    // 0.4.8 (PLAN item 4): Manage's own reconnect, after the TV restarted or the app came back, so "Offline" no longer
    // waits for a tap on Reconnect. Only the saved addresses whose port 5555 answers, and only the known-TV handshake
    // (openKnown, RFC-RECONNECT-1): the app's key signs and nothing more, so a TV that no longer takes it shows no Allow
    // dialog, a different TV is never connected, and neither the debugging step nor any other sheet shows on the phone.
    // Null when the TV did not answer that way; Reconnect stays the person's way in then.
    suspend fun quiet(tvId: TvId): TvSession? {
        live(tvId)?.let { return it }
        val tv = tvs.tvs.value.firstOrNull { it.id == tvId } ?: return null
        val net = discovery.network().value
        // RFC-REACH-1: any Wi-Fi; a network other than the setup's is no reason not to try (openKnown checks the TvId).
        if (!net.onWifi) return null
        for (endpoint in tv.endpoints.filterIsInstance<Endpoint.PlainTcp>()) {
            if (!attempt { discovery.probe5555(endpoint.host) }.getOrDefault(false)) continue
            val r = attempt { sessions.openKnown(endpoint, tvId) }.getOrNull()
            if (r is OpenResult.Ok) {
                tvs.upsert(current(tvId, tv).copy(lastSeenAt = clock.nowMs(), debugging = DebugState.ON, lastSubnet = net.subnet ?: tv.lastSubnet))
                return r.session
            }
        }
        return null
    }

    private fun current(tvId: TvId, fallback: Tv): Tv =tvs.tvs.value.firstOrNull { it.id == tvId } ?: fallback

    // RFC-REACH-1: one line when what the debugging step waits on changes, never per poll. Fixed words and counts only:
    // never an address or a subnet (the redactor masks them as well, but nothing here relies on it).
    private fun logState(net: DiscoveryEvent.NetworkState, same: Seen, saved: Int, answered: Boolean?, handshake: AdbError?, final: Boolean = false) {
        val state = when (mutableReach.value) {
            is Reach.WrongNetwork -> "not on Wi-Fi"
            is Reach.AwaitingAllow -> "waiting for Allow on the TV"
            is Reach.Connecting -> if (final) "refused" else "connecting"
            is Reach.NeedsDebugging -> "needs debugging"
            Reach.Idle -> "idle"
        }
        val network = when (same) { Seen.YES -> "same as setup"; Seen.NO -> "different from setup"; else -> "unknown" }
        val port = when (answered) { true -> "answered"; false -> "closed"; null -> "not tried" }
        val search = searchCounts.value?.let { (tried, others) -> "$tried other addresses, $others answered but were not this TV" } ?: "none yet"
        val line = "access: $state; wifi ${if (net.onWifi) "yes" else "no"}; network $network; " +
            "$saved saved address${if (saved == 1) "" else "es"}; 5555 $port; search $search; handshake ${handshake?.let { LogCodes.of(it) } ?: "none"}"
        if (logged.value == line) return
        logged.value = line
        log.line(line)
    }
}
