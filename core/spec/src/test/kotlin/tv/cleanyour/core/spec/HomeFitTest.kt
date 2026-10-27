package tv.cleanyour.core.spec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Issue114
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Issue #114 (also #45 and #390): an exact Android TV list does not match a TV that shows Google TV's
// home screen (HomeFit). These tests pin the report's TV, that no shipped list other than an Android
// TV one on such a TV changes its matching, and every reported TV's resolution before and after.
class HomeFitTest {
    private val source = BundledProfileSource()
    private val tested = BundledProfileSource.bundled.profiles.filter { !it.isCandidate }
    private val setupwraith = Component(KnownLaunchers.SETUPWRAITH, ".RecoveryActivity")
    private val googleTv = home(KnownLaunchers.LAUNCHERX, KnownLaunchers.LAUNCHERX, setupwraith)
    private val androidTv = home(KnownLaunchers.TVLAUNCHER, KnownLaunchers.TVLAUNCHER)

    private fun home(resolved: Component?, vararg claimants: Component) =
        HomeResolution(resolved, claimants.mapIndexed { i, c -> HomeClaimant(c, 2 - i) })

    private fun name(r: ProfileResolution): String = when (r) {
        is ProfileResolution.Exact -> "${if (r.profile.isCandidate) "candidate" else "exact"} ${r.profile.id}"
        is ProfileResolution.BrandSureSet -> "brand set ${r.brand}"
        is ProfileResolution.None -> "none"
    }

    private fun switchedOff(r: ProfileResolution.BrandSureSet): Set<PackageName> =
        r.sureSet.filter { it.default == GroupDefault.DISABLE }.flatMap { g -> g.packages.map { it.name } }.toSet()

    @Test fun issue114TheGoogleTvSetGetsTheBrandSetNotTheAndroidTvList() = runBlocking<Unit> {
        val facts = Issue114.facts()
        // From the facts alone (0.3.4 to 0.3.7, and still when no Home was read): the Android TV list.
        assertEquals("exact tcl-65c725", name(source.resolveNow(facts)))
        assertEquals("exact tcl-65c725", name(source.resolve(facts)))
        // With the Home the report shows: as a TCL set no list knows.
        val r = assertIs<ProfileResolution.BrandSureSet>(source.resolve(facts, Issue114.home))
        assertEquals("TCL", r.brand)
        assertEquals(r, source.resolveFor(Issue114.snapshot()))
        assertEquals(r, source.resolveNow(facts, home = Issue114.home))
        // The brand set leaves on what the Google TV list for this set leaves on, and the wizard.
        for (p in Issue114.keptByTheGoogleTvList + KnownLaunchers.SETUPWRAITH) assertFalse(p in switchedOff(r), p.value)
        // Not the next list down either: tcl-g05-google-tv matches the model name alone.
        assertEquals("tcl-g05-google-tv", tested.filter { it.id != "tcl-65c725" }.filter { BundledProfileSource.score(it, facts) != null }.single().id)
        // Either sign of Google TV's home screen is enough: answering Home, or claiming it.
        val ours = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
        assertEquals("brand set TCL", name(source.resolveNow(facts, home = home(ours, ours, KnownLaunchers.LAUNCHERX))))
        assertEquals("brand set TCL", name(source.resolveNow(facts, home = HomeResolution(KnownLaunchers.LAUNCHERX, emptyList()))))
        // The Android TV set the list was written for keeps it.
        assertEquals("exact tcl-65c725", name(source.resolveNow(facts, home = androidTv)))
        assertEquals("exact tcl-65c725", name(source.resolveNow(facts, home = home(ours, ours, KnownLaunchers.TVLAUNCHER))))
        // The AR09 build keeps its tested Google TV list; the list is not widened to AR11.
        assertEquals("exact tcl-g03-google-tv", name(source.resolveNow(Issue114.facts(Issue114.AR09))))
        assertEquals("exact tcl-g03-google-tv", name(source.resolveFor(Issue114.snapshot(fingerprint = Issue114.AR09))))
        val g03 = tested.single { it.id == "tcl-g03-google-tv" }
        assertEquals("TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR09", g03.match.fingerprintPrefix)
    }

    @Test fun theRuleIsNarrow() {
        val tcl65 = tested.single { it.id == "tcl-65c725" }
        assertTrue(HomeFit.fits(tcl65, null))
        assertTrue(HomeFit.fits(tcl65, androidTv))
        assertTrue(HomeFit.fits(tcl65, HomeResolution(null, emptyList())))
        assertFalse(HomeFit.fits(tcl65, googleTv))
        // A list of any other platform, or one that names Google TV's home screen, always fits.
        for (p in tested.filter { it.platform != Platform.ANDROID_TV }) assertTrue(HomeFit.fits(p, googleTv), p.id)
        assertTrue(HomeFit.fits(tcl65.copy(stockLaunchers = tcl65.stockLaunchers + KnownLaunchers.LAUNCHERX), googleTv))
        // A source that cannot weigh the Home answers from the facts.
        val plain = object : ProfileSource {
            override suspend fun all() = tested
            override suspend fun resolve(facts: DeviceFacts) = source.resolveNow(facts)
        }
        assertEquals("exact tcl-65c725", name(runBlocking { plain.resolveFor(Issue114.snapshot()) }))
    }

    // After the home screen switch the Google TV set's launcherx is switched off by the user and no
    // longer claims Home. It still counts, so the TV does not flip back to the Android TV list.
    @Test fun aSwitchedOffGoogleTvHomeStillCounts() = runBlocking<Unit> {
        val tcl65 = tested.single { it.id == "tcl-65c725" }
        val ours = Component(PackageName("com.gathin.tvhome"), ".MainActivity")
        val after = home(ours, ours, setupwraith)
        val before = Issue114.snapshot()
        val off = before.packages + (Issue114.launcherx.pkg to before.packages.getValue(Issue114.launcherx.pkg).copy(enabled = EnabledState.DISABLED_USER))
        assertFalse(HomeFit.fits(tcl65, after, off))
        assertEquals("brand set TCL", name(source.resolveNow(Issue114.facts(), home = after, packages = off)))
        assertEquals("brand set TCL", name(source.resolveFor(before.copy(home = after, packages = off))))
        // Installed and on but not claiming Home, or not installed at all: the Home alone decides.
        assertTrue(HomeFit.fits(tcl65, after, before.packages))
        assertTrue(HomeFit.fits(tcl65, after, before.packages - Issue114.launcherx.pkg))
        val disabled = before.packages + (Issue114.launcherx.pkg to before.packages.getValue(Issue114.launcherx.pkg).copy(enabled = EnabledState.DISABLED))
        assertTrue(HomeFit.fits(tcl65, after, disabled))
        // No Home read keeps the facts rule, whatever the packages say.
        assertTrue(HomeFit.fits(tcl65, null, off))
    }

    // Every shipped list, asked with its own keys: with no Home, the Android TV home screen or the
    // list's own stock launcher nothing changes; with Google TV's, exactly the TVs an Android TV list
    // wins change, each to what the same brand gets when no list knows the TV.
    @Test fun onlyAndroidTvListsOnAGoogleTvHomeChange() {
        val changed = sortedSetOf<String>()
        val unchangedOtherPlatforms = sortedSetOf<String>()
        for (p in tested) {
            val m = p.match
            val own = Fixtures.miTv().facts().copy(
                model = m.models.firstOrNull() ?: "unknown", device = m.devices.firstOrNull() ?: "unknown", brand = m.brand!!, manufacturer = m.brand!!,
                sdk = m.sdkMin ?: m.sdkMax ?: 30, fingerprint = (m.fingerprintPrefix ?: "") + "x",
            )
            val before = source.resolveNow(own)
            assertEquals(before, source.resolveNow(own, home = null), p.id)
            assertEquals(before, source.resolveNow(own, home = androidTv), p.id)
            val winner = (before as? ProfileResolution.Exact)?.profile
            winner?.stockLaunchers?.firstOrNull()?.let { s -> assertEquals(before, source.resolveNow(own, home = home(s, s)), p.id) }
            val after = source.resolveNow(own, home = googleTv)
            if (winner != null && winner.platform == Platform.ANDROID_TV) {
                changed += winner.id
                assertEquals(source.resolveNow(own.copy(model = "no such model", device = "no such device")), after, p.id)
                assertFalse(after is ProfileResolution.Exact, p.id)
            } else {
                assertEquals(before, after, p.id)
                winner?.let { unchangedOtherPlatforms += it.id }
            }
        }
        // The Android TV lists are exactly the ones that change, and only on such a TV.
        val androidTvLists = tested.filter { it.platform == Platform.ANDROID_TV }.map { it.id }.toSortedSet()
        assertEquals(androidTvLists.filter { it in changed }.toSortedSet(), changed)
        assertTrue(unchangedOtherPlatforms.none { it in androidTvLists })
        println("HomeFitTest: ${changed.size} of ${androidTvLists.size} Android TV lists step aside on a Google TV home; " +
            "${unchangedOtherPlatforms.size} other lists unchanged")
    }

    private data class Row(val issues: String, val facts: DeviceFacts, val home: HomeResolution)

    private fun rows(): List<Row> {
        val text = HomeFitTest::class.java.getResourceAsStream("/tv/cleanyour/core/spec/report-homes.tsv")!!.use { it.readBytes().decodeToString() }
        val base = Fixtures.miTv().facts()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split('\t')
            require(f.size == 10) { "report-homes.tsv: $line" }
            fun comp(s: String) = Component(PackageName(s.substringBefore('/')), s.substringAfter('/'))
            val claimants = f[9].split(' ').filter { it.isNotBlank() }.map { c -> HomeClaimant(comp(c.substringBeforeLast('@')), c.substringAfterLast('@').toInt()) }
            Row(
                f[0],
                base.copy(brand = f[1], manufacturer = f[2], model = f[3], device = f[4], sdk = f[5].toInt(), fingerprint = f[6], hasGms = f[7] == "yes"),
                HomeResolution(f[8].takeIf { it.isNotEmpty() }?.let(::comp), claimants),
            )
        }
    }

    // Every TV in the public reports, resolved from its facts alone and with its Home: only the TCL
    // BeyondTV4 Google TV build of #114 changes, from the Android TV list to the TCL brand set. With
    // the candidate lists of the test build (and the person's yes) nothing else changes either.
    @Test fun ofEveryReportedTvOnlyTheIssue114SetChanges() {
        val rows = rows()
        assertEquals(269, rows.size)
        val examples = File(Fixtures.upstreamDir, "examples")
        val meta = File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json")
        val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
        val candidateDir = File(Fixtures.repoRoot, "core/spec/candidates")
        val (withCandidates, problems) = ProfileConverter.convert(examples, meta, "3e34f40", unconverted, candidateDir, File(candidateDir, "candidate-meta.json"))
        assertEquals(emptyList(), problems)
        val trying = BundledProfileSource(withCandidates)
        val changed = mutableListOf<String>()
        val counts = sortedMapOf<String, Int>()
        for (r in rows) {
            val before = source.resolveNow(r.facts)
            val after = source.resolveNow(r.facts, home = r.home)
            counts.merge(name(after).substringBefore(' '), 1, Int::plus)
            if (before != after) changed += "${r.issues}: ${name(before)} -> ${name(after)}"
            val triedBefore = trying.resolveNow(r.facts, tryCandidate = true)
            val triedAfter = trying.resolveNow(r.facts, tryCandidate = true, home = r.home)
            if (triedBefore != triedAfter) assertEquals("${r.issues}: ${name(before)} -> ${name(after)}", "${r.issues}: ${name(triedBefore)} -> ${name(triedAfter)}")
            assertEquals(trying.candidateFor(r.facts) != null && HomeFit.fits(trying.candidateFor(r.facts)!!, r.home), trying.candidateFor(r.facts, r.home) != null, r.issues)
        }
        assertEquals(listOf("45 114 390: exact tcl-65c725 -> brand set TCL"), changed)
        println("HomeFitTest: ${rows.size} reported TVs, ${changed.size} changed, after: $counts")
    }
}
