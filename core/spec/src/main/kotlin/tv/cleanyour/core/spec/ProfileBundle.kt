package tv.cleanyour.core.spec

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TestedInfo

// profiles.json, schema 1: every upstream example converted at build time (D28) plus the brand
// sure sets computed from them (D26). Written by ProfileConverter, read by BundledProfileSource.
data class SureSet(val brand: String, val profileIds: List<String>, val groups: List<PackageGroup>, val stockLaunchers: List<Component>)
data class ProfileBundle(val upstreamCommit: String?, val profiles: List<Profile>, val sureSets: List<SureSet>)

internal object ProfileBundleJson {
    const val RESOURCE = "/tv/cleanyour/core/spec/profiles.json"
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable data class BundleDto(val schema: Int, val upstreamCommit: String?, val profiles: List<ProfileDto>, val sureSets: List<SureSetDto>)
    @Serializable data class ProfileDto(
        val id: String, val match: MatchDto, val platform: String, val stockLaunchers: List<String>, val liveTv: String?,
        val setupWraith: String?, val quirks: List<String>, val groups: List<GroupDto>, val neverDisable: List<String>,
        val suggestedDock: List<String>, val tested: TestedDto?, val sourceSha256: String,
    )
    @Serializable data class MatchDto(val models: List<String>, val devices: List<String>, val brand: String?, val sdkMin: Int?, val sdkMax: Int?, val fingerprintPrefix: String?)
    // `candidate` is left out of the file when false, so profiles.json without candidate lists is
    // byte for byte what it was before the test build.
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable data class TestedDto(val by: String?, val firmware: String?, val daysLivedWith: Int?, val rebootTested: Boolean,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val candidate: Boolean = false)
    @Serializable data class GroupDto(val id: String, val title: String, val default: String, val packages: List<EntryDto>)
    @Serializable data class EntryDto(val name: String, val note: String? = null)
    @Serializable data class SureSetDto(val brand: String, val profiles: List<String>, val groups: List<GroupDto>, val stockLaunchers: List<String>)

    private fun component(flat: String): Component {
        val i = flat.indexOf('/')
        require(i > 0) { "not a component: $flat" }
        return Component(PackageName(flat.substring(0, i)), flat.substring(i + 1))
    }

    private fun PackageGroup.dto() = GroupDto(id, title, default.name, packages.map { EntryDto(it.name.value, it.note) })
    private fun GroupDto.model() = PackageGroup(id, title, GroupDefault.valueOf(default), packages.map { PackageEntry(PackageName(it.name), it.note) })

    fun encode(b: ProfileBundle): String = json.encodeToString(BundleDto.serializer(), BundleDto(
        schema = 1, upstreamCommit = b.upstreamCommit,
        profiles = b.profiles.map { p ->
            ProfileDto(
                p.id, p.match.let { MatchDto(it.models, it.devices, it.brand, it.sdkMin, it.sdkMax, it.fingerprintPrefix) }, p.platform.name,
                p.stockLaunchers.map { it.flat }, p.liveTv?.value, p.setupWraith?.value, p.quirks.map { it.name }.sorted(), p.groups.map { it.dto() },
                p.neverDisable.map { it.value }, p.suggestedDock.map { it.value },
                p.tested?.let { TestedDto(it.by, it.firmware, it.daysLivedWith, it.rebootTested, it.candidate) }, p.sourceSha256.hex,
            )
        },
        sureSets = b.sureSets.map { s -> SureSetDto(s.brand, s.profileIds, s.groups.map { it.dto() }, s.stockLaunchers.map { it.flat }) },
    )) + "\n"

    fun decode(text: String): ProfileBundle {
        val d = json.decodeFromString(BundleDto.serializer(), text)
        require(d.schema == 1) { "profiles.json schema ${d.schema} is not 1" }
        return ProfileBundle(
            d.upstreamCommit,
            d.profiles.map { p ->
                Profile(
                    id = p.id, match = p.match.let { ProfileMatch(it.models, it.devices, it.brand, it.sdkMin, it.sdkMax, it.fingerprintPrefix) },
                    platform = Platform.valueOf(p.platform), stockLaunchers = p.stockLaunchers.map(::component),
                    liveTv = p.liveTv?.let(::PackageName), setupWraith = p.setupWraith?.let(::PackageName),
                    quirks = p.quirks.map { Quirk.valueOf(it) }.toSet(), groups = p.groups.map { it.model() },
                    neverDisable = p.neverDisable.map(::PackageName).toSet(), suggestedDock = p.suggestedDock.map(::PackageName),
                    tested = p.tested?.let { TestedInfo(it.by, it.firmware, it.daysLivedWith, it.rebootTested, it.candidate) }, sourceSha256 = Sha256(p.sourceSha256),
                )
            },
            d.sureSets.map { s -> SureSet(s.brand, s.profiles, s.groups.map { it.model() }, s.stockLaunchers.map(::component)) },
        )
    }
}

// D26: for a model we have no profile for, the brand's "sure set" is every package that sits in
// a default-disable group in at least `minProfiles` profiles of that brand and in no profile's
// never-disable list.
//
// D26 amendment (0.3.5): a brand with one or two profiles gets a careful set instead, made of the
// packages that pass every one of these rules:
// 1. every profile of the brand has it in a default-disable ads, tracking or preloads group, and
//    nowhere else;
// 2. at least CAREFUL_MIN_LISTS profiles of any brand switch it off by default, and none asks first;
// 3. no profile names it as never-disable, a stock launcher, the setup wizard or Live TV;
// 4. no accessibility, remote or pairing word (safety rule 8), not a TV-stack vendor package, and
//    no setup, launcher, input, tuner, voice, update or key word in its name;
// 5. its facts.tsv category is ads, tracking or preloads;
// and it is one of CAREFUL_PACKAGES, the six the owner reviewed for this rule. A careful set's stock
// launchers are KnownLaunchers.generic and nothing else (6), so a TV on it gets the home screen
// decision ProfileResolution.None gets; only the clean-up rows are new. The full sets come first in
// the list, so a TV whose brand and maker match a full set and a careful set keeps the full set.
object SureSetGenerator {
    const val MIN_PROFILES = 3
    const val CAREFUL_MIN_LISTS = 10

    val CAREFUL_PACKAGES: Set<PackageName> = listOf(
        "com.google.android.feedback", "com.android.printspooler", "com.android.tv.feedbackconsent",
        "com.android.adservices.api", "com.android.ondevicepersonalization.services", "com.android.federatedcompute.services",
    ).map(::PackageName).toSet()

    private val CAREFUL_GROUPS = setOf(ProfileCategory.ADS.id, ProfileCategory.TRACKING.id, ProfileCategory.PRELOADS.id)
    // The words of safety rule 8 (ProfilesTest.noAccessibilityRemoteOrPairingPackageIsDefaultOff).
    private val SAFETY_WORDS = Regex("talkback|accessib|marvin|remote|pairing|autopair|btservice|bluetooth|(^|_)rcu(_|$)|keyhandler|globalkey|virtualkey|fivekey|smartkey|keyintercept|inputkey")
    private val ROLE_WORDS = Regex("setup|launcher|input|tuner|voice|update|key")
    private val TV_STACK = listOf("com.mediatek", "com.droidlogic", "com.realtek", "org.droidtv", "com.hisense.smartsense", "com.vt", "com.mstar", "com.cvte", "com.amlogic", "mitv.service")

    // The upstream b06ab21 lists promoted in 0.4.5 (first part), held back from the brand sets the same
    // way: with them NVIDIA and OnePlus would get full sets and the Sony, Philips and TCL sets would move.
    val PROMOTED_045: Set<String> = setOf(
        "epson-ls800",
        "lumio-vision-7",
        "nvidia-shield-tv-2017",
        "oneplus-tv-cebu",
        "oneplus-tv-dosa",
        "philips-google-tv-ta1",
        "philips-google-tv-ta7",
        "sber-salute-tv-at30a6",
        "sony-bravia-vu1",
        "sony-bravia-vu3",
        "tcl-50dp660",
        "tcl-g08-eu-android12",
    )

    // The b06ab21 lists promoted in 0.4.5 (second curator): exact profiles that wait for the same
    // owner review before they feed a brand set, so the brand sets stay those of the base bump.
    private val PROMOTED_B06AB21_PART2: Set<String> = setOf(
        "xiaomi-mi-box-s-android12",
        "xiaomi-mitv-mssp2",
        "xiaomi-mitv-mssp0",
        "xiaomi-mitv-ayfr0",
        "xiaomi-mitv-astp0",
        "xiaomi-mitv-mteq0",
        "redmi-mitv-mftp0",
        "aqua-matrixtv-ee-50",
        "jvc-lt55mb508",
        "sharp-2t-c32gh3000i",
        "thomson-43rt1055",
        "hisense-fire-tv-aftcibfbd1",
    )

    // The upstream v2.7.0 lists promoted in 0.3.7. They ship as exact profiles but do not feed the
    // brand sets yet: with them the Amazon, Sony, Xiaomi and Hisense sets would grow and Skyworth and
    // google would get full sets, which is a separate owner review (the 0.3.7 job report has the diff).
    val REVIEW_PENDING: Set<String> = setOf(
        "amazon-fire-tv-aftdec012e", "google-chromecast-gtv-hd", "hisense-smarttv-4k-ffm-android11", "hisense-smarttv-4k-ffm-lushan", "karbonn-swtv-20ae",
        "ok-ai-pont-ikebukuro", "philips-50pud7406", "skyworth-sw-22sa", "skyworth-swtv-22ae-fhd", "sony-bravia-4k-ur3", "sony-bravia-4k-vh1", "sony-kd-43x80l",
        "xiaomi-mi-box-3", "xiaomi-mitv-mftr0", "xiaomi-mitv-moeu0", "xiaomi-mitv-mooq2", "xiaomi-mitv-mssp1",
        // The v2.7.0 list curated for a reported TV (#446), held back from the brand sets like the rest.
        "motorola-mt4310",
    ) + PROMOTED_045 + PROMOTED_B06AB21_PART2

    // The profiles the brand sets are made of: every shipped one but those still under review.
    fun feeding(profiles: List<Profile>): List<Profile> = profiles.filter { it.id !in REVIEW_PENDING }

    fun generate(all: List<Profile>, facts: Map<PackageName, PackageFacts>, minProfiles: Int = MIN_PROFILES): List<SureSet> {
        val profiles = feeding(all)
        val byBrand = profiles.filter { it.match.brand != null }.groupBy { it.match.brand!!.lowercase() }.toSortedMap()
        val full = byBrand.filterValues { it.size >= minProfiles }.map { (_, ps) -> fullSet(ps, profiles, minProfiles) }
        val careful = byBrand.filterValues { it.size < minProfiles }.mapNotNull { (_, ps) -> carefulSet(ps, profiles, facts) }
        return full + careful
    }

    private fun fullSet(ps: List<Profile>, profiles: List<Profile>, minProfiles: Int): SureSet {
        val keep = profiles.flatMap { it.neverDisable }.toSet()
        val brand = ps.first().match.brand!!
        val count = LinkedHashMap<PackageName, Int>()
        val firstSeen = LinkedHashMap<PackageName, Pair<PackageGroup, PackageEntry>>()
        val groupVotes = LinkedHashMap<PackageName, MutableMap<String, Int>>()
        for (p in ps) for (g in p.groups.filter { it.default == GroupDefault.DISABLE }) for (e in g.packages) {
            count[e.name] = (count[e.name] ?: 0) + 1
            firstSeen.putIfAbsent(e.name, g to e)
            groupVotes.getOrPut(e.name) { LinkedHashMap() }.let { v -> v[g.id] = (v[g.id] ?: 0) + 1 }
        }
        val sure = count.filter { (pkg, n) -> n >= minProfiles && pkg !in keep }.keys
        // Issue #111: an owner's-choice package (ProfileCategory.OWNERS_CHOICE) that at least
        // minProfiles of the brand's lists offer, as a row, is an ask row of the set, never an off.
        val askCount = LinkedHashMap<PackageName, Int>()
        val askVotes = LinkedHashMap<PackageName, MutableMap<String, Int>>()
        val askSeen = LinkedHashMap<PackageName, Pair<PackageGroup, PackageEntry>>()
        for (p in ps) for (g in p.groups.filter { it.default == GroupDefault.ASK }) for (e in g.packages) if (ProfileCategory.isOwnersChoice(e.name)) {
            askCount[e.name] = (askCount[e.name] ?: 0) + 1
            askSeen.putIfAbsent(e.name, g to e)
            askVotes.getOrPut(e.name) { LinkedHashMap() }.let { v -> v[g.id] = (v[g.id] ?: 0) + 1 }
        }
        val ask = askCount.filter { (pkg, n) -> n >= minProfiles && pkg !in keep && pkg !in sure }.keys
        val launchers = (ps.flatMap { it.stockLaunchers } + KnownLaunchers.generic).distinct()
        val groups = groups(ps, sure, groupVotes, firstSeen) + groups(ps, ask, askVotes, askSeen, GroupDefault.ASK)
        return SureSet(brand, ps.map { it.id }.sorted(), groups, launchers)
    }

    private fun carefulSet(ps: List<Profile>, profiles: List<Profile>, facts: Map<PackageName, PackageFacts>): SureSet? {
        val offCount = HashMap<PackageName, Int>()
        val askedSomewhere = HashSet<PackageName>()
        for (p in profiles) {
            for (pkg in p.groups.filter { it.default == GroupDefault.DISABLE }.flatMap { g -> g.packages.map { it.name } }.toSet()) offCount[pkg] = (offCount[pkg] ?: 0) + 1
            for (g in p.groups.filter { it.default != GroupDefault.DISABLE }) g.packages.forEach { askedSomewhere += it.name }
        }
        val named = profiles.flatMap { p -> p.neverDisable + p.stockLaunchers.map { it.pkg } + listOfNotNull(p.setupWraith, p.liveTv) }.toSet() + KnownLaunchers.SETUPWRAITH
        // Rule 1: in every profile of the brand, and there only in default-off ads, tracking or preloads groups.
        fun carefulIn(p: Profile): Set<PackageName> {
            val where = p.groups.flatMap { g -> g.packages.map { it.name to g } }.groupBy({ it.first }, { it.second })
            return where.filterValues { gs -> gs.all { it.default == GroupDefault.DISABLE && it.id in CAREFUL_GROUPS } }.keys
        }
        val sure = ps.map(::carefulIn).reduce { a, b -> a intersect b }.filter { pkg ->
            val segments = pkg.value.split('.').drop(1).map { it.lowercase() }
            (offCount[pkg] ?: 0) >= CAREFUL_MIN_LISTS && pkg !in askedSomewhere &&
                pkg !in named &&
                segments.none { SAFETY_WORDS.containsMatchIn(it) || ROLE_WORDS.containsMatchIn(it) } &&
                TV_STACK.none { pkg.value == it || pkg.value.startsWith("$it.") } &&
                facts[pkg]?.category in CAREFUL_GROUPS &&
                pkg in CAREFUL_PACKAGES
        }.sortedBy { it.value }
        if (sure.isEmpty()) return null
        val groupVotes = LinkedHashMap<PackageName, MutableMap<String, Int>>()
        val firstSeen = LinkedHashMap<PackageName, Pair<PackageGroup, PackageEntry>>()
        for (p in ps) for (g in p.groups) for (e in g.packages) if (e.name in sure) {
            firstSeen.putIfAbsent(e.name, g to e)
            groupVotes.getOrPut(e.name) { LinkedHashMap() }.let { v -> v[g.id] = (v[g.id] ?: 0) + 1 }
        }
        return SureSet(ps.first().match.brand!!, ps.map { it.id }.sorted(), groups(ps, sure, groupVotes, firstSeen), KnownLaunchers.generic)
    }

    // Each package goes to the group id most of the brand's profiles file it under.
    private fun groups(
        ps: List<Profile>, sure: Collection<PackageName>, groupVotes: Map<PackageName, Map<String, Int>>,
        firstSeen: Map<PackageName, Pair<PackageGroup, PackageEntry>>, default: GroupDefault = GroupDefault.DISABLE,
    ): List<PackageGroup> {
        val byGroup = LinkedHashMap<String, MutableList<PackageEntry>>()
        val titles = HashMap<String, String>()
        for (pkg in sure) {
            val votes = groupVotes.getValue(pkg)
            val id = votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order(it.key) }).first().key
            byGroup.getOrPut(id) { mutableListOf() } += PackageEntry(pkg, firstSeen.getValue(pkg).second.note)
            titles.putIfAbsent(id, ps.asSequence().flatMap { it.groups }.first { it.id == id }.title)
        }
        return byGroup.entries.sortedBy { order(it.key) }.map { (id, es) -> PackageGroup(id, titles.getValue(id), default, es.sortedBy { it.name.value }) }
    }

    private fun order(groupId: String): Int = ProfileCategory.entries.indexOfFirst { groupId.removeSuffix("-optional") == it.id }
}
