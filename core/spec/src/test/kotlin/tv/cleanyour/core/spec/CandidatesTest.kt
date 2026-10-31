package tv.cleanyour.core.spec

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Test build (0.3.5-test1): the candidate lists in core/spec/candidates. They are converted only
// with -Pcyt.candidates=true; these tests convert them directly, so they run in every build.
class CandidatesTest {
    private val examples = File(Fixtures.upstreamDir, "examples")
    private val meta = File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json")
    private val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
    private val candidateDir = File(Fixtures.repoRoot, "core/spec/candidates")
    private val candidateMeta = File(candidateDir, "candidate-meta.json")
    private val ids = listOf(
        "aurzen-boomair", "haier-android-tv-nippori", "sharp-aquos-tve19a", "smarttech-apollo-premium4k", "xiaomi-mitv4i-magnolia",
    )

    private fun without() = ProfileConverter.convert(examples, meta, "0cb195a", unconverted)
    private fun with() = ProfileConverter.convert(examples, meta, "0cb195a", unconverted, candidateDir, candidateMeta)

    private fun facts(brand: String, model: String, device: String, sdk: Int) = DeviceFacts(
        brand = brand, manufacturer = brand, model = model, device = device, serialLast4 = "0000", sdk = sdk, release = "x",
        fingerprint = "$brand/$device/$device:x/X/1:user/release-keys", abis = listOf("armeabi-v7a"), ramMb = 2048, hasGms = true,
        adbSecure = true, adbFeatures = setOf("shell_v2"), installRefusal = false, locale = "en",
    )

    @Test fun withoutTheFlagNothingChanges() {
        val (bundle, problems) = without()
        assertEquals(emptyList(), problems)
        assertTrue(bundle.profiles.none { it.isCandidate })
        // The shipped profiles.json of a build without the flag is the encoding of this bundle, with
        // no "candidate" key anywhere (the release file, byte for byte).
        val shipped = BundledProfileSource::class.java.getResourceAsStream(ProfileBundleJson.RESOURCE)!!.use { it.readBytes().decodeToString() }
        if (BundledProfileSource.bundled.profiles.none { it.isCandidate }) {
            assertEquals(ProfileBundleJson.encode(bundle), shipped)
            assertFalse("\"candidate\"" in shipped)
        }
    }

    @Test fun withTheFlagTheCandidatesComeAfterTheUpstreamProfiles() {
        val (plain, _) = without()
        val (bundle, problems) = with()
        assertEquals(emptyList(), problems)
        assertEquals(plain.profiles.map { it.id } + ids, bundle.profiles.map { it.id })
        assertEquals(plain.profiles, bundle.profiles.take(plain.profiles.size))
        // Candidates never feed a brand sure set.
        assertEquals(plain.sureSets, bundle.sureSets)
        for (p in bundle.profiles.drop(plain.profiles.size)) {
            assertTrue(p.isCandidate, p.id)
            assertTrue(p.tested!!.by!!.startsWith("candidate, issue #"), p.id)
            assertFalse(p.tested!!.rebootTested, p.id)
            assertEquals(p.match.sdkMin, p.match.sdkMax, "${p.id}: D-W4-14, the reported Android version only")
        }
        // The codec keeps the flag.
        assertEquals(bundle, ProfileBundleJson.decode(ProfileBundleJson.encode(bundle)))
        assertTrue("\"candidate\": true" in ProfileBundleJson.encode(bundle))
        // The shipped file of a build with the flag is this bundle.
        if (BundledProfileSource.bundled.profiles.any { it.isCandidate }) assertEquals(bundle, BundledProfileSource.bundled)
    }

    @Test fun theConverterRefusesABadCandidate() {
        val dir = java.nio.file.Files.createTempDirectory("candidates").toFile()
        try {
            File(dir, "sony-bravia-4k-ur2").mkdirs()
            File(dir, "sony-bravia-4k-ur2/debloat.txt").writeText("# T\ncom.a.b\n")
            File(dir, "untested").mkdirs()
            File(dir, "untested/debloat.txt").writeText("# T\ncom.a.b\n")
            File(dir, "nometa").mkdirs()
            File(dir, "nometa/debloat.txt").writeText("# T\ncom.a.b\n")
            fun entry(candidate: Boolean) = """{"match":{"models":["X"],"brand":"B","sdkMin":30,"sdkMax":30},"platform":"ANDROID_TV",
                "tested":{"by":"candidate, issue #1","candidate":$candidate},"groups":{"T":{"category":"tracking"}}}"""
            File(dir, "m.json").writeText("""{"schema":1,"profiles":{"sony-bravia-4k-ur2":${entry(true)},"untested":${entry(false)},"gone":${entry(true)}}}""")
            val (_, problems) = ProfileConverter.convert(examples, meta, "0cb195a", unconverted, dir, File(dir, "m.json"))
            assertEquals(
                setOf(
                    "candidate gone: in candidate-meta.json but no debloat.txt",
                    "candidate nometa: no entry in candidate-meta.json",
                    "candidate sony-bravia-4k-ur2: an upstream folder has the same name",
                    "candidate untested: tested.candidate is not true",
                ),
                problems.toSet(),
            )
        } finally { dir.deleteRecursively() }
    }

    // A candidate is the exact profile only after the person agreed, and never over a tested list.
    @Test fun resolvingToACandidate() = runBlocking<Unit> {
        val (bundle, _) = with()
        var yes = false
        val source = BundledProfileSource(bundle, CandidateConsent { yes })
        val magnolia = facts("Xiaomi", "MiTV4I", "magnolia", 28)
        assertEquals("xiaomi-mitv4i-magnolia", source.candidateFor(magnolia)?.id)
        val careful = source.resolve(magnolia)
        assertIs<ProfileResolution.BrandSureSet>(careful)
        assertEquals("Xiaomi", careful.brand)
        yes = true
        val tried = source.resolve(magnolia)
        assertIs<ProfileResolution.Exact>(tried)
        assertEquals("xiaomi-mitv4i-magnolia", tried.profile.id)
        // Another Android version of the same TV: no candidate (D-W4-14).
        assertNull(source.candidateFor(magnolia.copy(sdk = 30)))
        assertIs<ProfileResolution.BrandSureSet>(source.resolve(magnolia.copy(sdk = 30)))
        // A TV with a tested list is never offered a candidate, whatever the answer.
        val tested = Fixtures.miTv().facts()
        assertNull(source.candidateFor(tested))
        assertEquals("xiaomi-mitv-axso0", (source.resolve(tested) as ProfileResolution.Exact).profile.id)
        // A brand with no sure set: careful is None, as in 0.3.4.
        yes = false
        val boomair = facts("AURZEN", "BOOMAIR", "himalaya", 30)
        assertEquals("aurzen-boomair", source.candidateFor(boomair)?.id)
        assertIs<ProfileResolution.None>(source.resolve(boomair))
        // Without consent wiring (the release graph before the test build), never a candidate.
        assertIs<ProfileResolution.BrandSureSet>(BundledProfileSource(bundle).resolve(magnolia))
    }

    // Safety: what a candidate switches off by default passes the same rules a shipped list does
    // (ProfilesTest.noAccessibilityRemoteOrPairingPackageIsDefaultOff), and a candidate never names
    // the setup wizard or the stock home, or the quirk that lets the switch turn the wizard off.
    @Test fun candidatesKeepEverySafetyRule() {
        val (bundle, _) = with()
        val facts = BundledFactsTable()
        val words = Regex("talkback|accessib|marvin|remote|pairing|autopair|btservice|bluetooth|(^|_)rcu(_|$)|keyhandler|globalkey|virtualkey|fivekey|smartkey|keyintercept|inputkey|tvinput|tuner|hdmi|setupwizard|setupwraith")
        val offenders = mutableListOf<String>()
        for (p in bundle.profiles.filter { it.isCandidate }) {
            assertNull(p.setupWraith, p.id)
            assertFalse(Quirk.HOME_TAKEOVER_SETUPWRAITH in p.quirks, p.id)
            assertTrue(p.stockLaunchers.isNotEmpty(), p.id)
            for (g in p.groups.filter { it.default == GroupDefault.DISABLE }) for (e in g.packages) {
                val pkg = e.name
                if (facts.lookup(pkg)?.category == "accessibility" || pkg.value.split('.').drop(1).any { words.containsMatchIn(it.lowercase()) }) offenders += "${p.id} ${pkg.value}"
                if (pkg in p.neverDisable || pkg == p.liveTv || p.stockLaunchers.any { it.pkg == pkg } || pkg in KnownLaunchers.byPackage) offenders += "${p.id} ${pkg.value} (kept)"
                if (pkg == PackageName("com.google.android.tungsten.setupwraith")) offenders += "${p.id} setupwraith"
            }
        }
        assertEquals(emptyList(), offenders)
    }
}
