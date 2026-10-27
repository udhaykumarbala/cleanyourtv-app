package tv.cleanyour.core.flows

import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneOffset
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 0.3.3 D1 with real sizes: the four TV reports posted on the first public day (issues #9 AURZEN
// BOOMAIR, #11 Haier nippori, #13 Philips PH0M_EA_T32 and #15 Smarttech marina, 113 to 126 system
// packages), as 0.3.2 made them. Each fixture is rebuilt into the input the app had, and text() gives
// it back line for line, so the sizes measured here are the real ones. The compact report of each
// rode in the tv_support link under 0.3.3's cap of 7,000; under 0.4.6's 4,000 (GitHub's HTTP 500 with
// a signed-in phone's cookies, IssueLinks.MAX_URL) each one is copied for the paste instead.
class RealReportsTest {
    private val issues = listOf(9, 11, 13, 15)

    private fun fixture(n: Int): String =
        checkNotNull(javaClass.getResourceAsStream("/reports/issue-$n.txt")) { "issue-$n.txt" }.readBytes().decodeToString()

    @Test fun eachFixtureIsRebuiltExactly() {
        for (n in issues) assertEquals(fixture(n), TvReport.text(parse(fixture(n))), "issue #$n")
    }

    @Test fun theCompactReportOfEachRealTvIsCopiedForThePaste() {
        for (n in issues) {
            val input = parse(fixture(n))
            val compact = TvReport.compact(input)
            val link = IssueLinks.tvSupport(input)
            // 0.4.6: about 5,600 to 6,000 characters with the report, over the 4,000 cap; the link
            // keeps the short fields and the preview copies the report for the paste.
            val withReport = IssueLinks.tvSupport(input, null).url + "&report=" + java.net.URLEncoder.encode(compact.trimEnd(), "UTF-8").replace("+", "%20")
            assertTrue(withReport.length in 5_000..6_500, "issue #$n: ${withReport.length}")
            assertFalse(link.reportInLink, "issue #$n: ${link.url.length}")
            assertTrue(link.url.length <= IssueLinks.MAX_URL, "issue #$n: ${link.url.length}")
            assertEquals(4000, IssueLinks.MAX_URL)
            // The other fields are there.
            for (k in listOf("template", "title", "model", "android", "platform")) assertTrue(query(link.url)[k]?.isNotEmpty() == true, "issue #$n: $k")
            // The same facts as the full report: every system package, in its state, once.
            val full = fixture(n).lines().dropWhile { !it.startsWith("System apps (") }.drop(1).takeWhile { it.isNotBlank() }
            for (line in full) {
                val (pkg, state) = line.split(' ', limit = 2)
                val group = compactGroups(compact).entries.single { pkg in it.value }.key
                assertTrue(group.endsWith(state), "issue #$n: $pkg $state in $group")
            }
            assertEquals(full.size, compactGroups(compact).values.sumOf { it.size }, "issue #$n")
            // Shorter than the full report, and every line short enough to read.
            assertTrue(compact.length < fixture(n).length, "issue #$n")
            assertTrue(compact.lines().all { it.length <= TvReport.WRAP || ' ' !in it }, "issue #$n")
        }
    }

    // A TV with far more system packages than any so far: the report does not fit, so the link has
    // no report and the person pastes it (the preview says so first).
    @Test fun aReportTooLongForTheLinkFallsBackToThePaste() {
        val base = parse(fixture(15))
        val many = base.snapshot.packages + (1..200).associate { PackageName("com.example.vendor.firmware.part$it") to state(EnabledState.DEFAULT, true) }
        val input = base.copy(snapshot = base.snapshot.copy(packages = many))
        val link = IssueLinks.tvSupport(input)
        assertFalse(link.reportInLink)
        assertFalse("report=" in link.url)
        assertTrue(link.url.length <= IssueLinks.MAX_URL)
        assertTrue(link.url.contains("&model="), link.url)
    }

    // Whatever the size, the link never goes over the cap, and the report is in it exactly when it fits.
    @Test fun theLinkNeverGoesOverTheCap() {
        val base = parse(fixture(9))
        for (extra in 0..240 step 8) {
            val pkgs = base.snapshot.packages + (1..extra).associate { PackageName("com.example.part$it") to state(EnabledState.DEFAULT, true) }
            val input = base.copy(snapshot = base.snapshot.copy(packages = pkgs))
            val link = IssueLinks.tvSupport(input)
            assertTrue(link.url.length <= IssueLinks.MAX_URL, "$extra: ${link.url.length}")
            val withReport = IssueLinks.tvSupport(input, null).url + "&report=" + java.net.URLEncoder.encode(TvReport.compact(input).trimEnd(), "UTF-8").replace("+", "%20")
            assertEquals(withReport.length <= IssueLinks.MAX_URL, link.reportInLink, "$extra")
        }
    }

    private fun compactGroups(compact: String): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        for (line in compact.lines().dropWhile { !it.startsWith("System apps (") }.drop(1)) {
            when {
                line.startsWith("Other apps:") -> break
                line.isBlank() -> current = null
                line.endsWith("):") -> current = mutableSetOf<String>().also { out[line.substringBefore(" (")] = it }
                else -> current!!.addAll(line.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        // "On, default" ends with the pm state word "default", "Off, disabled-user" with "disabled-user".
        return out.mapKeys { (k, _) -> k.substringAfter(", ") }
    }

    private fun state(e: EnabledState, system: Boolean) = PackageState(e, system, 0, null, false, false, null, false)

    // The 0.3.2 TV report back into its input. The values the report leaves out (serial, id,
    // settings, inputs) get neutral ones; the person's own apps get made-up names.
    private fun parse(text: String): TvReportInput {
        val lines = text.lines()
        fun v(key: String) = lines.first { it.startsWith("$key: ") }.removePrefix("$key: ")
        val phone = v("Phone")
        val (version, flavour) = v("App").let { it.substringBefore(" (") to it.substringAfter("(").removeSuffix(")") }
        val android = v("Android")
        val facts = DeviceFacts(
            brand = v("Brand"), manufacturer = v("Manufacturer"), model = v("Model"), device = v("Device"), serialLast4 = "0000",
            sdk = android.substringAfter("SDK ").removeSuffix(")").toInt(), release = android.substringBefore(" ("),
            fingerprint = v("Build"), abis = listOf("armeabi-v7a"), ramMb = 2048, hasGms = v("Google Play services") == "yes",
            adbSecure = true, adbFeatures = emptySet(), installRefusal = false, locale = "en-GB",
        )
        val read = v("Read from the TV")
        val day = LocalDate.parse(read.substringBefore(",")).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reason = if (read.endsWith("before any change")) SnapshotReason.BASELINE else SnapshotReason.MANUAL
        fun component(flat: String) = Component(PackageName(flat.substringBefore('/')), flat.substringAfter('/'))
        val claimants = lines.filter { it.startsWith("Claimant: ") }.map {
            val c = it.removePrefix("Claimant: ")
            HomeClaimant(component(c.substringBefore(" priority ")), c.substringAfter(" priority ").toInt())
        }
        val resolved = v("Resolves to").takeIf { '/' in it }?.let(::component)
        val system = lines.dropWhile { !it.startsWith("System apps (") }.drop(1).takeWhile { it.isNotBlank() }.associate { line ->
            val (pkg, st) = line.split(' ', limit = 2)
            val e = when (st) {
                "enabled" -> EnabledState.ENABLED; "default" -> EnabledState.DEFAULT
                "disabled-user" -> EnabledState.DISABLED_USER; "disabled" -> EnabledState.DISABLED
                else -> error("state $st")
            }
            PackageName(pkg) to state(e, true)
        }
        val others = v("Other apps").substringBefore(" ").toInt()
        val packages = system + (1..others).associate { PackageName("person.app$it") to state(EnabledState.ENABLED, false) }
        val snapshot = Snapshot(
            TvId("0000000000000000"), day, reason, facts, packages, HomeResolution(resolved, claimants),
            emptyMap(), null, emptyList(), null, emptyList(), Samples.zeroHash,
        )
        val profile = v("Profile").let { p ->
            when {
                p == "none" -> ProfileResolution.None(emptyList())
                p.startsWith("brand set ") -> ProfileResolution.BrandSureSet(p.removePrefix("brand set "), emptyList(), emptyList())
                else -> error("profile $p")
            }
        }
        val home = v("Home switch").removePrefix("blocked ").let { HomeDecision(false, it) }
        return TvReportInput(
            AppAbout(version, flavour, phone.substringBeforeLast(", "), phone.substringAfterLast(", ")),
            snapshot, profile, home,
        )
    }

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
