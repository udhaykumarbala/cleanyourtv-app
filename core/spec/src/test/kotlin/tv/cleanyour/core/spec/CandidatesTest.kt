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

// Test builds (0.3.5-test1, 0.3.6-beta1, 0.3.8-beta1, 0.3.8-beta2, 0.4.5-beta1): the candidate lists in core/spec/candidates.
// They are converted only with -Pcyt.candidates=true; these tests convert them directly, so they run in every build.
class CandidatesTest {
    private val examples = File(Fixtures.upstreamDir, "examples")
    private val meta = File(Fixtures.repoRoot, "core/spec/src/main/resources/tv/cleanyour/core/spec/profile-meta.json")
    private val unconverted = ProfileConverter.readUnconverted(File(Fixtures.repoRoot, "core/spec/upstream-feedback/unconverted.txt"))
    private val candidateDir = File(Fixtures.repoRoot, "core/spec/candidates")
    private val candidateMeta = File(candidateDir, "candidate-meta.json")
    // In folder order: five of 0.3.5-test1, eleven of 0.3.6-beta1 and twenty-seven drafted from the TV
    // reports since (each for one reported TV, by device, brand or maker and one Android version, with
    // the fingerprint prefix where the model name is a family name). The others became tested lists in
    // 0.3.7 (upstream v2.7.0) or match the same TV as one. 0.4.5-beta1 carries thirty-nine of the
    // forty-three of 0.3.8-beta2: the TV of each of the other four resolves to a list promoted in 0.4.5
    // (upstream b06ab21), the same device and Android version: philips-google-tv-ph1m-9972
    // (philips-google-tv-ta1), sharp-2k-smart-tv-a-beomil (sharp-2t-c32gh3000i),
    // skyworth-swtv-22ae-fhd-android11 (skyworth-swtv-22ae-fhd, now Android 11 to 14) and
    // sony-bravia-vu3-4k (sony-bravia-vu3).
    private val ids = listOf(
        "amazon-fire-tv-cube-aftr", "aurzen-boomair", "bpl-ai-pont-samseong", "changhong-ai-pont-redwood-android14",
        "changhong-ai-pont-samseong", "formovie-c025rgn-tianshan", "haier-android-tv-nippori", "kivi-4k-android-tv-bangbae",
        "kogan-ai-pont-samseong", "nokia-android-tv-shandao", "oneplus-tv-c00-cebu", "oneplus-tv-dubai-android11",
        "panasonic-tv-r2-scbc", "realme-ai-pont-ikebukuro", "sharp-aquos-tve19a", "skyworth-4k-google-tv-box-yda",
        "skyworth-globe-sa-android8", "skyworth-sw-22ae-nf", "skyworth-swtv-20sa-sw4h", "skyworth-swtv-21ae-android11",
        "skyworth-swtv-24ae-fhd-beomil", "skyworth-swtv-shibuya", "smarttech-apollo-premium4k", "sony-bravia-4k-ur1",
        "tcl-beyondtv-c02-4k", "tcl-g06-google-tv", "tdsystems-android-tv-redwood-android14", "toshiba-tv-hayward",
        "wansa-ai-pont-redwood", "xiaomi-mi-tv-4a-android11", "xiaomi-mitv-aesp0-android10", "xiaomi-mitv-afzp0",
        "xiaomi-mitv-axfu0", "xiaomi-mitv-axso2-android11", "xiaomi-mitv-mftr0-android14", "xiaomi-mitv-mosr1",
        "xiaomi-mitv-mtsu0", "xiaomi-mitv-nsop0", "xiaomi-mitv4i-magnolia",
    )
    // The candidates whose TV's own home is not a known Google or Amazon launcher (PatchWall on these
    // Xiaomi sets): they name no stock home, so the home screen switch is refused and the list is
    // clean-up only.
    private val cleanUpOnly = setOf("xiaomi-mitv-afzp0", "xiaomi-mitv-mftr0-android14", "xiaomi-mitv-mtsu0", "xiaomi-mitv-nsop0")

    private fun without() = ProfileConverter.convert(examples, meta, "b06ab21", unconverted)
    private fun with() = ProfileConverter.convert(examples, meta, "b06ab21", unconverted, candidateDir, candidateMeta)

    private fun facts(brand: String, model: String, device: String, sdk: Int, manufacturer: String = brand) = DeviceFacts(
        brand = brand, manufacturer = manufacturer, model = model, device = device, serialLast4 = "0000", sdk = sdk, release = "x",
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
            assertTrue(p.match.devices.isNotEmpty(), "${p.id}: matched by the reported device")
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
            val (_, problems) = ProfileConverter.convert(examples, meta, "b06ab21", unconverted, dir, File(dir, "m.json"))
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
        // Where the model name is a family name ("Smart TV") a list matches the device and build alone:
        // the C02 firmware of the TCL BeyondTV gets its candidate, the tested AP06 firmware keeps its
        // own list, another firmware or device gets neither.
        val c02 = facts("TCL", "Smart TV", "BeyondTV", 28).copy(fingerprint = "TCL/C02_4K_US/BeyondTV:9/PPR1.180610.011/AP12:user/release-keys")
        assertEquals("tcl-beyondtv-c02-4k", source.candidateFor(c02)?.id)
        assertNull(source.candidateFor(c02.copy(fingerprint = "TCL/C02_4K_US/BeyondTV:9/PPR1.180610.011/AP08:user/release-keys")))
        val ap06 = c02.copy(model = "BeyondTV", fingerprint = "TCL/BeyondTV/BeyondTV:9/PPR1.180610.011/AP06:user/release-keys")
        assertNull(source.candidateFor(ap06))
        assertEquals("tcl-beyondtv-rt2851", (source.resolve(ap06) as ProfileResolution.Exact).profile.id)
        assertEquals("tcl-g06-google-tv", source.candidateFor(facts("TCL", "Smart TV", "G06", 30))?.id)
        assertNull(source.candidateFor(facts("TCL", "Smart TV", "unknown", 30)))
        // A Redmi-branded set of a Xiaomi list, by its maker; the Android 10 Mi TV 4A keeps its tested list.
        assertEquals("xiaomi-mi-tv-4a-android11", source.candidateFor(facts("Redmi", "MiTV-MOOQ3", "tarzan", 30).copy(manufacturer = "Xiaomi"))?.id)
        assertNull(source.candidateFor(facts("Xiaomi", "MiTV-MOOQ1", "tarzan", 29)))
        // The AI PONT family name: one Changhong board under several brands, each its own list by the
        // brand and the fingerprint prefix; another brand of the same board gets none.
        fun aiPont(brand: String, product: String, device: String, sdk: Int, release: String) =
            facts(brand, "AI PONT", device, sdk, manufacturer = "Changhong").copy(fingerprint = "$brand/$product/$device:$release/X/1:user/release-keys")
        assertEquals("changhong-ai-pont-samseong", source.candidateFor(aiPont("Changhong", "DVB93", "samseong", 30, "11"))?.id)
        assertEquals("bpl-ai-pont-samseong", source.candidateFor(aiPont("BPL", "DVB93BL", "samseong", 30, "11"))?.id)
        assertEquals("kogan-ai-pont-samseong", source.candidateFor(aiPont("Kogan", "DVB93KG", "samseong", 30, "11"))?.id)
        assertNull(source.candidateFor(aiPont("Kogan", "DVB93", "samseong", 30, "11")))
        assertNull(source.candidateFor(aiPont("Wansa", "DVB92WS", "redwood", 34, "14")))
        assertEquals("wansa-ai-pont-redwood", source.candidateFor(aiPont("Wansa", "DVB92WS", "redwood", 30, "11"))?.id)
        assertEquals("changhong-ai-pont-redwood-android14", source.candidateFor(aiPont("Changhong", "DVB92", "redwood", 34, "14"))?.id)
        // The same Hisense-built hayward board: the Toshiba set gets its candidate by the prefix.
        assertEquals("toshiba-tv-hayward", source.candidateFor(facts("Toshiba", "Toshiba TV", "hayward", 30, manufacturer = "Hisense")
            .copy(fingerprint = "Toshiba/huashan_emT/hayward:11/X/1:user/release-keys"))?.id)
        // A model name that is the TV's own: the Android 11 MFTR0 keeps its tested list, the Android 14
        // one gets the clean-up only candidate.
        assertEquals("xiaomi-mitv-mftr0", (source.resolve(facts("Xiaomi", "MiTV-MFTR0", "freeguy", 30)) as ProfileResolution.Exact).profile.id)
        assertEquals("xiaomi-mitv-mftr0-android14", source.candidateFor(facts("Xiaomi", "MiTV-MFTR0", "freeguy", 34))?.id)
        // Fire OS: the Cube's candidate, matched by its model.
        assertEquals("amazon-fire-tv-cube-aftr", source.candidateFor(facts("Amazon", "AFTR", "raven", 28))?.id)
        assertNull(source.candidateFor(facts("Amazon", "AFTR", "raven", 30)))
        // 0.4.5: the four TVs of 0.3.8-beta2 candidates that a promoted list now matches, as their
        // reports gave them, get that tested list and no candidate.
        val promoted = mapOf(
            facts("Philips", "Philips Google TV TA1", "PH1M_WW_9972", 34, manufacturer = "TPV")
                .copy(fingerprint = "Philips/PH1M_WW_9972/PH1M_WW_9972:14/UKN2.241117.001/3000518:user/release-keys") to "philips-google-tv-ta1",
            facts("Sharp", "SHARP 2K Smart TV-A", "beomil", 34, manufacturer = "skyworth")
                .copy(fingerprint = "Sharp/SW11_SHARP_APEU/beomil:14/UKN6.260116.061/15165694:user/release-keys") to "sharp-2t-c32gh3000i",
            facts("skyworth", "SWTV-22AE-FHD", "umeda", 30)
                .copy(fingerprint = "skyworth/SW7_TG/umeda:11/RTM5.220609.223/9367774:user/release-keys") to "skyworth-swtv-22ae-fhd",
            facts("Sony", "BRAVIA VU3", "BRAVIA_VU3_4K", 34)
                .copy(fingerprint = "Sony/BRAVIA_VU3/BRAVIA_VU3_4K:14/UKR1.240726.001/386-60842:user/release-keys") to "sony-bravia-vu3",
        )
        for ((f, id) in promoted) {
            assertNull(source.candidateFor(f), id)
            assertEquals(id, (source.resolve(f) as ProfileResolution.Exact).profile.id)
        }
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
            assertEquals(p.id in cleanUpOnly, p.stockLaunchers.isEmpty(), p.id)
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
