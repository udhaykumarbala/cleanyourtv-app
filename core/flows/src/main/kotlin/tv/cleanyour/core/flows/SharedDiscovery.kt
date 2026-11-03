package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.discovery.DiscoveryEvent
import tv.cleanyour.core.discovery.FoundTv

// 0.4.8 (beta4 follow-up): one network search for the whole app. Each discover() of NsdDiscovery starts its own NSD
// listeners and its own one-at-a-time resolve queue, so the debugging step's reconnect search (RFC-RECONNECT-1) and
// "Copy connection details" (RFC-REACH-1), or Setup's search and either of them, ran two searches side by side: every
// service was resolved twice and probed twice, and older Android refuses a second resolve while one runs. Here the
// first collector starts the one search, later collectors join it, and it stops when the last one leaves (as before:
// the flow stays cold for its users). A collector that joins late first gets every TV found so far as Found, then the
// changes as they come, so it sees what a collector of its own search would; while it is busy (a handshake in
// TvAccess) the changes in between are folded into the next one. The frozen Discovery interface is unchanged.
class SharedDiscovery(private val inner: Discovery, scope: CoroutineScope) : Discovery {
    private data class Seen(val network: DiscoveryEvent.NetworkState?, val tvs: Map<String, FoundTv>)

    private val shared: StateFlow<Seen?> = inner.discover()
        .runningFold(Seen(null, emptyMap())) { seen, e ->
            when (e) {
                is DiscoveryEvent.Found -> seen.copy(tvs = seen.tvs + (e.tv.host to e.tv))
                is DiscoveryEvent.Updated -> seen.copy(tvs = seen.tvs + (e.tv.host to e.tv))
                is DiscoveryEvent.Lost -> seen.copy(tvs = seen.tvs - e.host)
                is DiscoveryEvent.NetworkState -> seen.copy(network = e)
            }
        }
        .catch { }   // a search that fails ends quietly, as an NSD start that fails does
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    override fun discover(): Flow<DiscoveryEvent> = flow {
        var network: DiscoveryEvent.NetworkState? = null
        var known = emptyMap<String, FoundTv>()
        shared.collect { seen ->
            if (seen == null) return@collect
            if (seen.network != null && seen.network != network) { network = seen.network; emit(seen.network) }
            for ((host, tv) in seen.tvs) {
                val before = known[host]
                if (before == null) emit(DiscoveryEvent.Found(tv)) else if (before != tv) emit(DiscoveryEvent.Updated(tv))
            }
            for (host in known.keys) if (host !in seen.tvs) emit(DiscoveryEvent.Lost(host))
            known = seen.tvs
        }
    }

    override suspend fun probe5555(host: String, timeoutMs: Long): Boolean = inner.probe5555(host, timeoutMs)

    override fun network(): StateFlow<DiscoveryEvent.NetworkState> = inner.network()
}
