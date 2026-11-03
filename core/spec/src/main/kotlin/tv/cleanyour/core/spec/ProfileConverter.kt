package tv.cleanyour.core.spec

import java.io.File

// Build-time converter (D28), run by :core:spec's convertProfiles task:
//   ProfileConverter <examples dir> <profile-meta.json> <upstream commit> <output resources dir> [unconverted.txt] [<candidates dir> <candidate-meta.json>]
// Parses every examples/<folder>/debloat.txt, refuses a folder without curated metadata or a
// group title the metadata does not name, computes the brand sure sets (with the categories of
// facts.tsv, read from beside profile-meta.json) and writes profiles.json.
// Owner decision D2: the folders listed in upstream-feedback/unconverted.txt are skipped (no
// curated entry yet); any other folder without an entry still fails the build, and so does a
// listed folder that has an entry or is not upstream.
// Test build only (-Pcyt.candidates=true): the candidate lists in core/spec/candidates, each with an
// entry in candidate-meta.json marked `"candidate": true`, are added after the upstream profiles.
// They never feed a brand sure set, and a candidate folder may not share a name with an upstream
// folder the app ships. A parked one (unconverted.txt) is not a profile, so a candidate of the same
// name stays the test list until that folder is curated (xiaomi-mitv4i-magnolia at d7b0d4a): the id
// is what saved candidate choices and TV records carry.
object ProfileConverter {
    // unconverted.txt: one folder per line; # comments and blank lines are ignored.
    fun readUnconverted(file: File?): Set<String> =
        file?.readLines().orEmpty().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    fun convert(
        examples: File, metaFile: File, commit: String, unconverted: Set<String> = emptySet(),
        candidates: File? = null, candidateMeta: File? = null,
        // The careful sets (D26 amendment) read the categories in facts.tsv, which sits beside profile-meta.json.
        factsFile: File = File(metaFile.parentFile, FACTS_FILE),
    ): Pair<ProfileBundle, List<String>> {
        val meta = ProfileMeta.decode(metaFile.readText())
        val problems = mutableListOf<String>()
        val all = examples.listFiles().orEmpty().filter { File(it, "debloat.txt").isFile }.sortedBy { it.name }
        (unconverted - all.map { it.name }.toSet()).forEach { problems += "$it: in unconverted.txt but not upstream" }
        (unconverted intersect meta.profiles.keys).forEach { problems += "$it: in both unconverted.txt and profile-meta.json" }
        val folders = all.filter { it.name !in unconverted }
        val profiles = folders.map { dir ->
            val text = File(dir, "debloat.txt").readText()
            val m = meta.profiles[dir.name]
            if (m == null) problems += "${dir.name}: no entry in profile-meta.json"
            else {
                val scan = DebloatTxt.scan(text, dir.name)
                val titles = scan.groups.map { it.title }.toSet()
                (titles - m.groups.keys).forEach { problems += "${dir.name}: group title not in profile-meta.json: $it" }
                (m.groups.keys - titles).forEach { problems += "${dir.name}: profile-meta.json names a group that is not there: $it" }
                scan.keptOnlyTitles.forEach { problems += keptOnly(dir.name, it) }
                staleLeftOut(scan, m).forEach { problems += "${dir.name}: profile-meta.json leaves out a package that is not an active line: $it" }
            }
            DebloatTxt.parse(text, dir.name, m)
        }
        (meta.profiles.keys - all.map { it.name }.toSet()).forEach { problems += "$it: in profile-meta.json but not upstream" }
        val tried = if (candidates == null || candidateMeta == null) emptyList() else candidateProfiles(candidates, candidateMeta, folders.map { it.name }.toSet(), problems)
        return ProfileBundle(commit, profiles + tried, SureSetGenerator.generate(profiles, BundledFactsTable.parse(factsFile.readText()))) to problems
    }

    const val FACTS_FILE = "facts.tsv"

    // FolderMeta.leftOut entries that name no active package line of the list.
    internal fun staleLeftOut(scan: DebloatTxt.Scan, meta: FolderMeta): List<String> {
        val active = scan.groups.flatMap { g -> g.entries.map { it.pkg.value } }.toSet()
        return meta.leftOut.keys.filter { it !in active }
    }

    // C2: packages under a comment block of kept lines only join the group before them; the list may
    // have meant a new group, so a curated folder with one fails until the list (or its curation) says.
    fun keptOnly(folder: String, line: Int) =
        "$folder/debloat.txt:$line: the comment block above holds only commented-out packages, so these packages join the group before it"

    private fun candidateProfiles(dir: File, metaFile: File, shipped: Set<String>, problems: MutableList<String>): List<tv.cleanyour.core.model.Profile> {
        val meta = ProfileMeta.decode(metaFile.readText())
        val folders = dir.listFiles().orEmpty().filter { File(it, "debloat.txt").isFile }.sortedBy { it.name }
        (meta.profiles.keys - folders.map { it.name }.toSet()).forEach { problems += "candidate $it: in candidate-meta.json but no debloat.txt" }
        return folders.mapNotNull { d ->
            val m = meta.profiles[d.name]
            when {
                m == null -> { problems += "candidate ${d.name}: no entry in candidate-meta.json"; null }
                m.tested?.candidate != true -> { problems += "candidate ${d.name}: tested.candidate is not true"; null }
                d.name in shipped -> { problems += "candidate ${d.name}: an upstream folder has the same name"; null }
                else -> {
                    val text = File(d, "debloat.txt").readText()
                    val scan = DebloatTxt.scan(text, d.name)
                    val titles = scan.groups.map { it.title }.toSet()
                    (titles - m.groups.keys).forEach { problems += "candidate ${d.name}: group title not in candidate-meta.json: $it" }
                    (m.groups.keys - titles).forEach { problems += "candidate ${d.name}: candidate-meta.json names a group that is not there: $it" }
                    scan.keptOnlyTitles.forEach { problems += "candidate " + keptOnly(d.name, it) }
                    staleLeftOut(scan, m).forEach { problems += "candidate ${d.name}: candidate-meta.json leaves out a package that is not an active line: $it" }
                    DebloatTxt.parse(text, d.name, m)
                }
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 4..5 || args.size == 7) { "usage: ProfileConverter <examples dir> <profile-meta.json> <commit> <out dir> [unconverted.txt] [<candidates dir> <candidate-meta.json>]" }
        val (bundle, problems) = convert(File(args[0]), File(args[1]), args[2], readUnconverted(args.getOrNull(4)?.let(::File)),
            args.getOrNull(5)?.let(::File), args.getOrNull(6)?.let(::File))
        if (problems.isNotEmpty()) {
            System.err.println(problems.joinToString("\n"))
            throw IllegalStateException("${problems.size} profile conversion problem(s)")
        }
        val out = File(args[3], ProfileBundleJson.RESOURCE.removePrefix("/"))
        out.parentFile.mkdirs()
        out.writeText(ProfileBundleJson.encode(bundle))
        println("profiles.json: ${bundle.profiles.size} profiles (${bundle.profiles.count { it.isCandidate }} candidate lists, ${readUnconverted(args.getOrNull(4)?.let(::File)).size} upstream folders not converted), sure sets for ${bundle.sureSets.joinToString { "${it.brand} (${it.groups.sumOf { g -> g.packages.size }})" }}")
    }
}
