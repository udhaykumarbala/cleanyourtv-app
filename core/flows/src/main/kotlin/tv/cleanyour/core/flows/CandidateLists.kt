package tv.cleanyour.core.flows

import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.CandidateConsent
import tv.cleanyour.core.store.DocumentStore

// Test build (0.3.5-test1): a TV whose list is new and not tested yet (a candidate list, made from
// reports of the same model) gets it only when the person says so on Setup's card. Without
// candidate lists in the build (the release), offer() is always null and nothing changes.
interface CandidateLists {
    // The candidate list for this TV, or null when a tested list matches or none does. `home` is the
    // Home the snapshot read (issue #114: a candidate that does not fit it is not offered).
    fun offer(facts: DeviceFacts, home: HomeResolution? = null, packages: Map<PackageName, PackageState>? = null): Profile?
    suspend fun choose(profileId: String, tryIt: Boolean)
}

// What Setup's card shows: the list's id and the issue to tell us in ("#9").
data class CandidateOffer(val profileId: String, val issue: String?) {
    companion object {
        // tested.by reads "candidate, issue #13 #39 #266": the first issue is the one to reply in.
        fun of(p: Profile): CandidateOffer = CandidateOffer(p.id, p.tested?.by?.let { Regex("#\\d+").find(it)?.value })
    }
}

// candidates.txt, one line per list the person answered for on this phone: "<id>=try" or
// "<id>=brand". Read once, then kept in memory, so the engine's live check and the TV report see
// the answer at once even if the write failed. Delete everything removes it with the other files.
class CandidateChoices(private val store: DocumentStore) : CandidateConsent {
    private var loaded: MutableMap<String, Boolean>? = null

    private suspend fun all(): MutableMap<String, Boolean> = loaded ?: (store.read(PATH) { bytes ->
        bytes.decodeToString().lines().mapNotNull { l ->
            val (id, v) = l.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            when (v.trim()) { "try" -> id.trim() to true; "brand" -> id.trim() to false; else -> null }
        }.toMap()
    } ?: emptyMap()).toMutableMap().also { loaded = it }

    override suspend fun tries(profileId: String): Boolean = all()[profileId] == true

    suspend fun save(profileId: String, tryIt: Boolean) {
        val m = all()
        m[profileId] = tryIt
        store.write(PATH, m.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${if (it.value) "try" else "brand"}\n" }.encodeToByteArray())
    }

    companion object { const val PATH = "candidates.txt" }
}

class BundledCandidateLists(private val source: BundledProfileSource, private val choices: CandidateChoices) : CandidateLists {
    override fun offer(facts: DeviceFacts, home: HomeResolution?, packages: Map<PackageName, PackageState>?): Profile? = source.candidateFor(facts, home, packages)
    override suspend fun choose(profileId: String, tryIt: Boolean) = choices.save(profileId, tryIt)
}
