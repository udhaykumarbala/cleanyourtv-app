package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.cleanyour.core.discovery.Discovery
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.DebugState
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.store.TvRepository

// D7 and F11: debugging on the TV is switched off when the person taps Finish, when the app has
// been in the background for 10 minutes, or when the session is closed for any other reason,
// whichever comes first; "Keep debugging on" (per session, Customise > Afterwards) skips it. The
// DebugOff plan is SESSION_END, so it needs no pass (R0: 6). The TV closes the socket, so the
// result counts only once a follow-up probe of port 5555 fails.
//
// 0.4.8 (beta4 follow-up): an open port is not "on". The Android emulator's own process listens on
// 5555 whatever the TV's adbd does, and so can a router or proxy in front of a TV, so the record said
// "on" after Finish had switched debugging off, and the debugging step never said "we switched it
// off". A port that stays open is asked: the known-TV handshake (openKnown: the app's signature only,
// no Allow dialog) and a fresh read of adb_enabled, the way a snapshot reads it. "On" when this TV
// answers with adb_enabled not 0, or an adbd there answers and only refuses the key; "off" when nothing
// answers as an adbd or the setting reads 0; "unknown" when the TV answered but the read failed, or a
// different TV answers at the address.
class SessionEnd(
    private val sessions: SessionManager,
    private val discovery: Discovery,
    private val tvs: TvRepository,
    private val runner: PlanRunner,
    private val foreground: AppForeground,
    private val backgroundMs: Long = 10 * 60_000,
    private val probeTries: Int = 3,
    private val probeGapMs: Long = 1_000,
    private val handshakeMs: Long = 6_000,
    private val readMs: Long = 20_000,
) {
    private val keepOn = HashSet<TvId>()
    private val touched = LinkedHashSet<TvId>()
    private var watcher: Job? = null

    fun keepDebuggingOn(tvId: TvId, on: Boolean) { synchronized(keepOn) { if (on) keepOn.add(tvId) else keepOn.remove(tvId) } }
    fun keepsDebuggingOn(tvId: TvId): Boolean = synchronized(keepOn) { tvId in keepOn }

    // Every TV a write touched this process; the 10 minute rule switches these off.
    fun touched(tvId: TvId) { synchronized(touched) { touched.add(tvId) } }

    // Start the background watch in the app's long-lived scope.
    fun watch(scope: CoroutineScope) {
        watcher?.cancel()
        watcher = scope.launch {
            foreground.foreground.collectLatest { fg ->
                if (!fg) {
                    delay(backgroundMs)
                    // Coming back to the foreground must not cut a switch-off in half (the TV would be
                    // off while the record still says on), so the work itself is not cancellable.
                    withContext(NonCancellable) { endAll() }
                }
            }
        }
    }

    // D7 "or when the session is closed for any other reason": callers that close sessions (Forget
    // this TV, Reset my key, Delete everything) end them here first. Only TVs a write touched.
    suspend fun end(tvId: TvId): DebugState? {
        val wrote = synchronized(touched) { tvId in touched }
        return if (wrote && !keepsDebuggingOn(tvId)) switchOff(tvId) else null
    }

    suspend fun endAll() {
        val ids = synchronized(touched) { touched.toList() }
        for (id in ids) end(id)
    }

    // Runs debug_off on the live session of this TV. Returns the debugging state it recorded.
    suspend fun switchOff(tvId: TvId): DebugState {
        val session = sessions.existing(tvId) ?: return DebugState.UNKNOWN
        val tv = tvs.tvs.value.firstOrNull { it.id == tvId } ?: return DebugState.UNKNOWN
        val snapshot = runCatching { runner.snapshotFor(tv, session) }.getOrNull() ?: return DebugState.UNKNOWN
        val plan = (runner.plan(tv, PlanRequest.DebugOff, snapshot) as? PlanResult.Ok)?.plan ?: return DebugState.UNKNOWN
        runCatching { runner.run(tv, plan, session) }
        val host = tv.endpoints.filterIsInstance<Endpoint.PlainTcp>().firstOrNull()?.host
        // adbd can take a moment to let go of the port; a few probes a second apart before "on".
        var stillOpen = host != null && discovery.probe5555(host)
        var tries = 1
        while (stillOpen && tries < probeTries) {
            delay(probeGapMs)
            stillOpen = discovery.probe5555(host!!)
            tries++
        }
        val state = if (stillOpen && host != null) readBack(tv, host) else DebugState.OFF
        val now = tvs.tvs.value.firstOrNull { it.id == tvId } ?: tv
        tvs.upsert(now.copy(debugging = state))
        if (state == DebugState.OFF) synchronized(touched) { touched.remove(tvId) }
        return state
    }

    // The port still answers after debug_off: what the TV says, not what the port says (see the class comment).
    private suspend fun readBack(tv: Tv, host: String): DebugState {
        val opened = withTimeoutOrNull(handshakeMs) { attempt { sessions.openKnown(Endpoint.PlainTcp(host), tv.id) }.getOrNull() }
        return when (opened) {
            is OpenResult.Ok -> {
                val read = withTimeoutOrNull(readMs) { attempt { runner.readSnapshot(opened.session).settings[SettingKey.ADB_ENABLED] } }
                when {
                    read == null || read.isFailure -> DebugState.UNKNOWN
                    read.getOrNull()?.trim() == "0" -> DebugState.OFF
                    else -> DebugState.ON
                }
            }
            is OpenResult.Failed -> when (opened.error) {
                AdbError.Auth.Denied, AdbError.Transport.NoShellV2 -> DebugState.ON   // an adbd answered: debugging is on
                is AdbError.Policy.WrongTv -> DebugState.UNKNOWN                        // another TV answers at the address
                else -> DebugState.OFF                                                   // nothing answered as an adbd
            }
            null -> DebugState.OFF   // no handshake in time: the port is not this TV's adbd (the emulator's own listener)
        }
    }
}
