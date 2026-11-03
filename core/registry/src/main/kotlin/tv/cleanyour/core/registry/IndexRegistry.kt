package tv.cleanyour.core.registry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.DocumentStore
import tv.cleanyour.core.trust.IndexFailure
import tv.cleanyour.core.trust.IndexVerdict
import tv.cleanyour.core.trust.IndexVerifier
import tv.cleanyour.core.trust.KeysFile
import tv.cleanyour.core.trust.MinisignIndexVerifier
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.core.trust.TrustRoots
import java.io.IOException
import java.util.Base64

// The four files of one index generation, exactly as signed.
class IndexBundle(val index: ByteArray, val indexSig: ByteArray, val keys: ByteArray, val keysSig: ByteArray)

// Where refresh() fetches from. null in IndexRegistry: the bundled seed is the only source
// (PRODUCT-SPEC D29, the interim case).
data class IndexSources(val index: String, val indexSig: String, val keys: String, val keysSig: String) {
    companion object {
        fun under(base: String): IndexSources = base.trimEnd('/').let { IndexSources("$it/index.json", "$it/index.json.minisig", "$it/keys.json", "$it/keys.json.minisig") }
    }
}

// The index state machine (SECURITY 2.4 S2 and S3, PRODUCT-SPEC F9):
//
// restore()  no network. Takes the newer (by sequence) of the stored copy and the bundled seed,
//            re-verifies its signatures (expiry aside) and publishes Ready, stale when it has
//            expired. Nothing verifiable: Empty, or Failed with the verdict of the seed.
// refresh()  fetches keys.json, index.json and both signatures, verifies them against the last
//            accepted sequence and now, refuses a keys.json older than the last accepted one, and on
//            success stores the generation atomically and publishes Ready(stale = false). On any
//            failure it publishes Failed(fetch failure or verdict, last good index); the last index
//            itself is never replaced by a worse one. Calls are single flight.
// stale      Ready.stale is true once now is past the index's expiry; the list is still shown with
//            "The module list may be out of date", and installs wait for a fresh refresh. The same
//            list behind a Failed state is shown but never installed from (IndexState.installableIndex).
//            The bundled seed is published with fetchedAtMs 0.
//
// The high-water marks (index sequence, keys sequence) only grow and are stored with the copy, so a
// replayed older index or keys file fails ROLLBACK or BAD_KEYS_FILE even across restarts.
class IndexRegistry(
    private val http: HttpFetcher,
    private val store: DocumentStore,
    private val clock: Clock,
    private val seed: IndexBundle?,
    private val sources: IndexSources?,
    private val verifier: IndexVerifier = TrustRoots.indexVerifier(),
    private val keysSequenceOf: (ByteArray) -> Long? = KeysFile::peekSequence,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Registry {

    private val state = MutableStateFlow<IndexState>(IndexState.Empty)
    override val index: StateFlow<IndexState> = state.asStateFlow()
    private val mutex = Mutex()

    private var current: Generation? = null

    private class Generation(val bundle: IndexBundle, val index: VerifiedIndex, val keysSequence: Long, val fetchedAtMs: Long)

    override fun module(id: String): Module? = when (val s = state.value) {
        is IndexState.Ready -> s.index.modules.firstOrNull { it.id == id }
        is IndexState.Failed -> s.last?.modules?.firstOrNull { it.id == id }
        IndexState.Empty -> null
    }

    suspend fun restore(): IndexState = mutex.withLock { restoreUnlocked() }

    override suspend fun refresh(): IndexState = mutex.withLock {
        if (current == null) restoreUnlocked()
        val src = sources ?: return@withLock publish(current?.let(::ready) ?: state.value)
        val now = clock.nowMs()
        val fetched = try {
            IndexBundle(
                index = download(src.index, IndexLimits.INDEX_BYTES),
                indexSig = download(src.indexSig, IndexLimits.SIG_BYTES),
                keys = download(src.keys, IndexLimits.KEYS_BYTES),
                keysSig = download(src.keysSig, IndexLimits.SIG_BYTES),
            )
        } catch (e: FetchException) {
            return@withLock publish(IndexState.Failed(e.failure, null, current?.index))
        } catch (e: IllegalArgumentException) {
            return@withLock publish(IndexState.Failed(FetchFailure.HOST_NOT_ALLOWED, null, current?.index))
        } catch (e: IOException) {
            return@withLock publish(IndexState.Failed(FetchFailure.OFFLINE, null, current?.index))
        }
        val last = current
        // The keys high-water mark is part of step 1 of the fixed order (the keys file), so it is
        // checked before the verifier can report a later step such as ROLLBACK or EXPIRED. Reading
        // the sequence before the root signature is checked is safe: an unsigned keys file fails
        // BAD_KEYS_FILE in the verifier's step 1 anyway.
        val peekedKeys = keysSequenceOf(fetched.keys)
        if (last != null && peekedKeys != null && peekedKeys < last.keysSequence) {
            return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, last.index))
        }
        when (val verdict = verifier.verify(fetched.index, fetched.indexSig, fetched.keys, fetched.keysSig, last?.index?.sequence, now)) {
            is IndexVerdict.Bad -> publish(IndexState.Failed(null, verdict.failure, last?.index))
            is IndexVerdict.Ok -> {
                val keysSeq = keysSequenceOf(fetched.keys)
                    ?: return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, last?.index))
                if (last != null && keysSeq < last.keysSequence) {
                    return@withLock publish(IndexState.Failed(null, IndexFailure.BAD_KEYS_FILE, last.index))
                }
                val gen = Generation(fetched, verdict.index, keysSeq, now)
                current = gen
                try { save(gen) } catch (e: IOException) { /* the next refresh stores it; state stays correct */ }
                publish(IndexState.Ready(gen.index, stale = false, fetchedAtMs = now))
            }
        }
    }

    private suspend fun restoreUnlocked(): IndexState {
        val stored = loadStored()
        val seeded = seed?.let { check(it, fetchedAtMs = 0L) }
        val best = listOfNotNull(stored, (seeded as? Checked.Ok)?.gen)
            .maxWithOrNull(compareBy<Generation>({ it.index.sequence }, { it.fetchedAtMs }))
        current = best
        if (best != null) return publish(ready(best))
        val bad = (seeded as? Checked.Bad)?.verdict
        return publish(if (bad != null) IndexState.Failed(null, bad.failure, null) else IndexState.Empty)
    }

    private fun ready(gen: Generation): IndexState.Ready =
        IndexState.Ready(gen.index, stale = clock.nowMs() >= gen.index.expiresAtMs, fetchedAtMs = gen.fetchedAtMs)

    private fun publish(s: IndexState): IndexState { state.value = s; return s }

    // The index to install from now (IndexState.installableIndex against this registry's clock).
    fun installableIndex(): VerifiedIndex? = state.value.installableIndex(clock.nowMs())

    private sealed interface Checked { data class Ok(val gen: Generation) : Checked; data class Bad(val verdict: IndexVerdict.Bad) : Checked }

    private fun check(bundle: IndexBundle, fetchedAtMs: Long): Checked =
        when (val v = verifier.verify(bundle.index, bundle.indexSig, bundle.keys, bundle.keysSig, null, MinisignIndexVerifier.VERIFY_IGNORING_EXPIRY)) {
            is IndexVerdict.Bad -> Checked.Bad(v)
            is IndexVerdict.Ok -> keysSequenceOf(bundle.keys)?.let { Checked.Ok(Generation(bundle, v.index, it, fetchedAtMs)) }
                ?: Checked.Bad(IndexVerdict.Bad(IndexFailure.BAD_KEYS_FILE, "keys file unreadable"))
        }

    // 0.4.8 (issues #818, #2486): read on `io` with the request (getSmall), whatever scope refresh() runs in.
    private suspend fun download(url: String, max: Int): ByteArray {
        val r = http.getSmall(url, max, io)
        if (r.cut) throw FetchException(FetchFailure.TOO_LARGE, null, "$url is larger than $max bytes")
        if (r.code != 200 || r.bytes == null) throw FetchException(FetchFailure.HTTP_ERROR, r.code, "HTTP ${r.code}")
        return r.bytes
    }

    // One document, written atomically, so the four files and the high-water marks never disagree.
    private suspend fun save(gen: Generation) {
        val b64 = Base64.getEncoder()
        val doc = buildJsonObject {
            put("schema", 1)
            put("sequence", gen.index.sequence)
            put("keys_sequence", gen.keysSequence)
            put("fetched_at_ms", gen.fetchedAtMs)
            put("index", b64.encodeToString(gen.bundle.index))
            put("index_sig", b64.encodeToString(gen.bundle.indexSig))
            put("keys", b64.encodeToString(gen.bundle.keys))
            put("keys_sig", b64.encodeToString(gen.bundle.keysSig))
        }
        store.write(CACHE_PATH, doc.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun loadStored(): Generation? {
        val doc = try { store.read(CACHE_PATH) { Json.parseToJsonElement(it.toString(Charsets.UTF_8)) as? JsonObject } } catch (e: Exception) { null } ?: return null
        return try {
            val b64 = Base64.getDecoder()
            fun field(k: String) = b64.decode((doc[k] as JsonPrimitive).content)
            val bundle = IndexBundle(field("index"), field("index_sig"), field("keys"), field("keys_sig"))
            val fetchedAt = doc["fetched_at_ms"]!!.jsonPrimitive.long
            (check(bundle, fetchedAt) as? Checked.Ok)?.gen
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CACHE_PATH = "registry/index-cache.json"
    }
}

object IndexLimits {
    const val INDEX_BYTES = 1024 * 1024
    const val KEYS_BYTES = 64 * 1024
    const val SIG_BYTES = 4096
}

// The index the phone may install from at nowMs: verified and not past its expiry. A stale list,
// or the last list behind a failed refresh, is shown (shownIndex) but this returns null for it.
// Ready.stale is computed when the state is published and does not change while the process lives,
// so the expiry is checked again here against the caller's clock: a Ready published before the
// expiry must not stay installable after it.
fun IndexState.installableIndex(nowMs: Long): VerifiedIndex? = when (this) {
    is IndexState.Ready -> index.takeIf { !stale && nowMs < it.expiresAtMs }
    else -> null
}

// The index to show, fresh or not (Ready, or the last good one behind a failure).
fun IndexState.shownIndex(): VerifiedIndex? = when (this) {
    is IndexState.Ready -> index
    is IndexState.Failed -> last
    IndexState.Empty -> null
}
