package tv.cleanyour.core.spec

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
import java.security.MessageDigest

// Upstream examples/<folder>/debloat.txt: '#' comment lines (a header with model, SDK, firmware,
// stock launcher, Live TV app and quirks), then groups. A group is a run of package lines; its
// title is the first line of the comment block directly above the run. A package line is a name
// with an optional trailing "# note"; an indented comment right under it continues the note.
// A commented-out package line ("# com.a.b   # why it stays on") is kept by the owner and is not
// part of the profile. Inside a run it neither ends the run nor starts a title, and above a run it
// is never the title; the comment lines right under it that are indented (or that carry a second
// '#', as "#      # more") continue its note, not the previous entry's. Scan.kept records these
// packages with their notes, except one the same file also lists as active.
// A curated entry may leave active lines out of the profile (FolderMeta.leftOut); they are then
// treated as if commented out.
// Any other unindented comment inside a run still ends the run, and the packages after it open a
// group titled with that comment.
// A file with no blank line between its header and the first group: the header ends at the last
// empty "#" line above the first package, so the first title is that group's own heading and not
// the model line (tcl-ak30a5 at b06ab21).
class DebloatTxtException(val folder: String, val line: Int, detail: String) : IllegalArgumentException("$folder/debloat.txt:$line: $detail")

internal object DebloatTxt {
    data class Entry(val pkg: PackageName, var note: String?)
    data class Group(val title: String, val entries: MutableList<Entry> = mutableListOf())
    // kept: the commented-out packages below the header, with their notes (C2). keptOnlyTitles: the
    // lines of packages whose comment block above held only kept lines, so they joined the group
    // before them; the converter reports each one, since the list may have meant a new group.
    data class Scan(val header: List<String>, val groups: List<Group>, val kept: Map<PackageName, String?> = emptyMap(), val keptOnlyTitles: List<Int> = emptyList())

    private val PACKAGE_LINE = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)\\s*(?:#\\s?(.*))?$")
    private val DECORATION = Regex("^[-=*\\s]+|[-=*\\s]+$")

    fun commentText(line: String): String = line.trimStart().removePrefix("#").trim()

    private fun title(block: List<String>, skip: Set<Int> = emptySet()): String =
        block.asSequence().filterIndexed { i, _ -> i !in skip }.map { commentText(it).replace(DECORATION, "") }.firstOrNull { it.isNotEmpty() } ?: ""

    private fun indented(line: String) = line[0] == ' ' || line[0] == '\t'

    // An unindented comment whose text is a package line: a package the owner keeps on.
    private fun keptLine(line: String): Entry? {
        if (line[0] != '#') return null
        val m = PACKAGE_LINE.matchEntire(commentText(line)) ?: return null
        val pkg = runCatching { PackageName(m.groupValues[1]) }.getOrNull() ?: return null
        return Entry(pkg, m.groupValues[2].trim().ifEmpty { null })
    }

    // A comment line right under a kept line that continues its note: indented, or "#    # more" (a
    // second '#' after spaces, so a "## heading" is not a continuation).
    private val SECOND_HASH = Regex("^#\\s+#")
    private fun keptContinuation(line: String) = indented(line) || SECOND_HASH.containsMatchIn(line)

    private fun addNote(e: Entry, line: String) {
        val more = commentText(line).removePrefix("#").trim()
        if (more.isNotEmpty()) e.note = listOfNotNull(e.note, more).joinToString(" ")
    }

    private fun splitGluedHeader(block: MutableList<String>, blockKept: MutableList<Pair<Int, Entry>>, notTitle: MutableSet<Int>, header: MutableList<String>) {
        val cut = block.indexOfLast { it.trim() == "#" }
        if (cut < 0 || (cut + 1 until block.size).none { it !in notTitle && commentText(block[it]).replace(DECORATION, "").isNotEmpty() }) return
        header += block.subList(0, cut + 1)
        val rest = block.subList(cut + 1, block.size).toList()
        val keptRest = blockKept.filter { it.first > cut }.map { (it.first - cut - 1) to it.second }
        val skipRest = notTitle.filter { it > cut }.map { it - cut - 1 }
        block.clear(); block += rest
        blockKept.clear(); blockKept += keptRest
        notTitle.clear(); notTitle += skipRest
    }

    fun scan(text: String, folder: String): Scan {
        val header = mutableListOf<String>()
        val groups = mutableListOf<Group>()
        val block = mutableListOf<String>()
        // Kept lines in the pending comment block, and the block lines that are not its title.
        val blockKept = mutableListOf<Pair<Int, Entry>>()
        val notTitle = mutableSetOf<Int>()
        val kept = mutableListOf<Entry>()
        val keptOnlyTitles = mutableListOf<Int>()
        var lastKept: Entry? = null
        var inPackages = false
        var seenPackage = false
        fun endBlock(toHeader: Boolean) {
            if (toHeader) header += block else blockKept.forEach { kept += it.second }
            block.clear(); blockKept.clear(); notTitle.clear()
        }
        text.split('\n').forEachIndexed { i, raw ->
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) {
                endBlock(toHeader = !seenPackage)
                inPackages = false; lastKept = null
                return@forEachIndexed
            }
            if (line.trimStart().startsWith("#")) {
                val k = lastKept
                if (inPackages) {
                    if (k != null && keptContinuation(line)) { addNote(k, line); return@forEachIndexed }
                    if (indented(line)) { addNote(groups.last().entries.last(), line); return@forEachIndexed }
                    val kl = keptLine(line)
                    if (kl != null) { kept += kl; lastKept = kl; return@forEachIndexed }
                    inPackages = false; endBlock(toHeader = false)
                }
                // Above a run (or in the header): the line stays in the block, so a header keep
                // list still reads it, but a kept line and its continuation are never the title.
                block += line
                val kl = keptLine(line)
                when {
                    kl != null -> { blockKept += (block.size - 1) to kl; notTitle += block.size - 1; lastKept = kl }
                    k != null && keptContinuation(line) -> { addNote(k, line); notTitle += block.size - 1 }
                    else -> lastKept = null
                }
                return@forEachIndexed
            }
            lastKept = null
            val m = PACKAGE_LINE.matchEntire(line) ?: throw DebloatTxtException(folder, i + 1, "neither a package nor a comment")
            val pkg = runCatching { PackageName(m.groupValues[1]) }.getOrElse { throw DebloatTxtException(folder, i + 1, "invalid package name ${m.groupValues[1]}") }
            if (!inPackages) {
                if (!seenPackage && header.isEmpty()) splitGluedHeader(block, blockKept, notTitle, header)
                when {
                    block.size > notTitle.size -> groups += Group(title(block, notTitle))
                    groups.isEmpty() -> groups += Group("")
                    block.isNotEmpty() -> keptOnlyTitles += i + 1
                }
                if (!seenPackage) seenPackage = true
                endBlock(toHeader = false); inPackages = true
            }
            groups.last().entries += Entry(pkg, m.groupValues[2].trim().ifEmpty { null })
        }
        endBlock(toHeader = !seenPackage)
        val active = groups.flatMap { g -> g.entries.map { it.pkg } }.toSet()
        val keptMap = linkedMapOf<PackageName, String?>()
        for (e in kept) if (e.pkg !in active && e.pkg !in keptMap) keptMap[e.pkg] = e.note
        return Scan(header, groups, keptMap, keptOnlyTitles)
    }

    fun sha256(text: String): Sha256 =
        Sha256(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) })

    private val SDK_OF_RELEASE = mapOf(9 to 28, 10 to 29, 11 to 30, 12 to 31, 13 to 33, 14 to 34, 15 to 35, 16 to 36)
    private val PKG_TOKEN = Regex("(?<![A-Za-z0-9_./-])([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)(?![A-Za-z0-9_*/-]|\\.[A-Za-z*])")
    private val LIKELY_PACKAGE = Regex("^(com|org|net|tv|android|in|co|io|de|jp|cn|air|mitv|ai)\\..+")

    private fun packagesIn(s: String): List<PackageName> = PKG_TOKEN.findAll(s).map { it.groupValues[1] }
        .filter { LIKELY_PACKAGE.matches(it) }
        .mapNotNull { runCatching { PackageName(it) }.getOrNull() }.toList()

    // Header facts by keyword, for a folder without curated metadata.
    private data class HeaderGuess(val platform: Platform, val sdk: Int?, val stock: List<PackageName>, val liveTv: PackageName?, val quirks: Set<Quirk>, val keep: Set<PackageName>)

    private fun guessHeader(header: List<String>, folder: String): HeaderGuess {
        val text = header.joinToString("\n") { commentText(it) }
        val lower = text.lowercase()
        val platform = when {
            "fire os" in lower -> Platform.FIRE_OS
            folder == "tcl-ak30a5" || ("tcl" in folder && "china" in lower) -> Platform.TCL_CN
            "google tv" in lower -> Platform.GOOGLE_TV
            else -> Platform.ANDROID_TV
        }
        val sdk = Regex("\\((?:SDK|API) (\\d{2})\\)").find(text)?.groupValues?.get(1)?.toInt()
            ?: Regex("(?:Android TV|Google TV|Android) (\\d{1,2})\\b").find(text)?.groupValues?.get(1)?.toInt()?.let { SDK_OF_RELEASE[it] }
        val stock = header.filter { "stock launcher" in it.lowercase() }.flatMap { packagesIn(it) }.distinct()
        val liveTv = header.firstNotNullOfOrNull { l -> if (Regex("live tv( app)?:", RegexOption.IGNORE_CASE).containsMatchIn(l)) packagesIn(l.substringAfter(':')).firstOrNull() else null }
        val quirks = buildSet {
            if ("svc power reboot" in lower) add(Quirk.REBOOT_COMMAND)
            if ("re-enabled by the firmware" in lower || "at every boot" in lower) add(Quirk.REENABLED_AT_BOOT)
            if ("wireless debugging" in lower && "pair" in lower) add(Quirk.WIRELESS_DEBUG_PAIRING_REQUIRED)
            if ("setupwraith" in lower) add(Quirk.HOME_TAKEOVER_SETUPWRAITH)
            if ("claims home" in lower || "declares the home" in lower) add(Quirk.EXTRA_HOME_CLAIMERS)
        }
        return HeaderGuess(platform, sdk, stock, liveTv, quirks, keepListIn(header))
    }

    // Packages a header line says to keep ("Keep:", "KEEP", "Do NOT disable", "must stay
    // enabled"); continuation lines of the same paragraph count too, up to a line that starts
    // "Already" (a note on what the owner or the factory had already switched off, which names
    // packages the list then disables anyway) or a numbered note. Used in addition to the curated list, since an
    // extra protected name only makes the plan smaller.
    fun keepListIn(lines: List<String>): Set<PackageName> {
        val out = linkedSetOf<PackageName>()
        var inKeep = false
        var bulletStart = false
        for (l in lines) {
            val t = commentText(l)
            val lower = t.lowercase().removePrefix("- ")
            val starts = KEEP_WORD.containsMatchIn(lower) || "do not disable" in lower || "must stay enabled" in lower || "keep enabled" in lower
            if (starts) { inKeep = true; bulletStart = t.startsWith("- ") }
            else if (inKeep && (t.isEmpty() || (bulletStart && t.startsWith("- ")) || HEADING.containsMatchIn(t) || lower.startsWith("already ") || NUMBERED_NOTE.containsMatchIn(t))) inKeep = false
            if (inKeep) out += packagesIn(t)
        }
        return out
    }
    private val HEADING = Regex("^[A-Z][A-Za-z ]{2,40}:")
    // "Keep" as a word: a wrapped sentence that goes on "keeps X; re-enable ..." starts no keep list.
    private val KEEP_WORD = Regex("^keep\\b")
    // A numbered note ("2. Play-Fi could not be kept disabled") starts a new paragraph: philips-55oled707
    // mentions another list's DO NOT DISABLE block inside note 1, and its stock launcher in note 3.
    private val NUMBERED_NOTE = Regex("^\\d+\\.\\s")

    fun parse(text: String, folder: String, meta: FolderMeta?): Profile {
        val scan = scan(text, folder)
        if (scan.groups.isEmpty()) throw DebloatTxtException(folder, 1, "no package lines")
        val guess = guessHeader(scan.header, folder)

        val brand = meta?.match?.brand ?: folder.substringBefore('-').let { if (it.length <= 3) it.uppercase() else it.replaceFirstChar(Char::uppercaseChar) }
        val stockPkgs = linkedSetOf<PackageName>()
        (meta?.stockLaunchers?.map(::PackageName) ?: guess.stock).forEach { stockPkgs += it }
        var setupWraith: PackageName? = meta?.setupWraith?.let(::PackageName)
        val quirks = (meta?.quirks?.map { Quirk.valueOf(it) }?.toMutableSet() ?: guess.quirks.toMutableSet())

        // Category per package: curated package override, else the D22 updater rule, else the
        // curated group category, else the title keywords.
        data class Placed(val category: ProfileCategory, val ask: Boolean, val entry: PackageEntry)
        val placed = mutableListOf<Placed>()
        val seen = mutableSetOf<PackageName>()
        var keptWizard = false
        for (g in scan.groups) {
            val (gc, gask) = meta?.groups?.get(g.title)?.let { ProfileCategory.of(it.category) to it.ask } ?: ProfileCategory.guess(g.title)
            for (e in g.entries) {
                if (meta != null && e.pkg.value in meta.leftOut) continue
                if (!seen.add(e.pkg)) continue
                if (e.pkg == KnownLaunchers.SETUPWRAITH && meta?.setupWraithStaysOn == true) { keptWizard = true; continue }
                if (e.pkg == KnownLaunchers.SETUPWRAITH) { setupWraith = e.pkg; quirks += Quirk.HOME_TAKEOVER_SETUPWRAITH; continue }
                if (e.pkg in KnownLaunchers.byPackage) { stockPkgs += e.pkg; continue }
                val override = meta?.packages?.get(e.pkg.value)
                val (c, ask) = when {
                    // Issue #111: an owner's-choice package stays an ask row, whatever the override says.
                    override != null -> ProfileCategory.of(override.category) to (override.ask || ProfileCategory.isOwnersChoice(e.pkg))
                    ProfileCategory.isUpdater(e.pkg) -> ProfileCategory.UPDATES to true
                    ProfileCategory.isOwnersChoice(e.pkg) -> gc to true
                    else -> gc to gask
                }
                placed += Placed(c, ask, PackageEntry(e.pkg, e.note))
            }
        }
        if (setupWraith != null) quirks += Quirk.HOME_TAKEOVER_SETUPWRAITH

        val groups = placed.groupBy { it.category.groupId(it.ask) }.entries
            .sortedWith(compareBy<Map.Entry<String, List<Placed>>>({ (_, v) -> if (v.first().category.default(v.first().ask) == GroupDefault.DISABLE) 0 else 1 }, { (_, v) -> v.first().category.ordinal }))
            .map { (id, v) ->
                val c = v.first().category
                val ask = v.first().ask
                PackageGroup(id, c.title(ask, brand.replaceFirstChar(Char::uppercaseChar)), c.default(ask), v.map { it.entry })
            }

        val match = meta?.match?.let { ProfileMatch(it.models, it.devices, it.brand, it.sdkMin, it.sdkMax, it.fingerprintPrefix) }
            ?: ProfileMatch(emptyList(), emptyList(), null, guess.sdk, guess.sdk, null)
        val neverDisable = linkedSetOf<PackageName>().apply {
            meta?.neverDisable?.forEach { add(PackageName(it)) }
            addAll(guess.keep)
            if (keptWizard) add(KnownLaunchers.SETUPWRAITH)
        }
        val tested = meta?.tested?.let { TestedInfo(it.by, it.firmware, it.daysLivedWith, it.rebootTested, it.candidate) }
        return Profile(
            id = folder,
            match = match,
            platform = meta?.platform?.let { Platform.valueOf(it) } ?: guess.platform,
            stockLaunchers = stockPkgs.mapNotNull { KnownLaunchers.byPackage[it] },
            liveTv = meta?.liveTv?.let(::PackageName) ?: if (meta == null) guess.liveTv else null,
            setupWraith = setupWraith,
            quirks = quirks,
            groups = groups,
            neverDisable = neverDisable,
            suggestedDock = meta?.suggestedDock?.map(::PackageName) ?: emptyList(),
            tested = tested,
            sourceSha256 = sha256(text),
        )
    }
}
