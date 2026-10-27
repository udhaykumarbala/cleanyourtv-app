package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.planner.HomeSwitchRisk.Risk
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.otherMaker
import tv.cleanyour.core.planner.PlannerFixtures.releaseRule
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.Issue036
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RF-SAFETY-250: the home screen switches skipped up front for 0.2.0, on the shipped profiles and on
// TVs no profile matches. The planner refuses such a switch asked for anyway; the clean-up alone
// is planned as usual.
class HomeSwitchRiskTest {
    private val source = BundledProfileSource()
    private val shipped: Map<String, Profile> = BundledProfileSource.bundled.profiles.associateBy { it.id }
    private val tv = snapshot()   // the reference TV, its setup wizard installed and on
    private val noWizard = tv.copy(packages = tv.packages - wizard).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    private fun facts(device: String, brand: String, manufacturer: String = brand, sdk: Int = 34): DeviceFacts =
        tv.facts.copy(device = device, brand = brand, manufacturer = manufacturer, model = "unknown", sdk = sdk)
    private fun withFacts(f: DeviceFacts): Snapshot = tv.copy(facts = f).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    private fun exact(f: DeviceFacts): Profile? = (source.resolveNow(f) as? ProfileResolution.Exact)?.profile

    // (a) The Google TV sets whose list switches the wizard off with the stock home: while the TV says
    // the wizard saved the Wi-Fi, or cannot tell, D3 keeps it on, so there is no switch.
    @Test fun theWizardWifiSetsGetNoSwitchWhileTheWizardMayOwnTheWifi() {
        val ids = listOf("tcl-43s5k", "tcl-g03-google-tv", "xgimi-mogo-3-pro", "eko-2k-google-tv-tennoji", "byintek-t5-max", "aiwa-ai-pont-sa", "polytron-2k-hayward")
        for (id in ids) {
            val p = shipped.getValue(id)
            assertEquals(wizard, p.setupWraith, id)
            assertEquals(Risk.WIZARD_WIFI, HomeSwitchRisk.of(tv.facts, p, tv, WizardWifi.OWNER), id)
            assertEquals(Risk.WIZARD_WIFI_UNKNOWN, HomeSwitchRisk.of(tv.facts, p, tv, WizardWifi.UNKNOWN), id)
            assertNull(HomeSwitchRisk.of(tv.facts, p, tv, WizardWifi.NOT_OWNER), id)
            assertTrue(Risk.WIZARD_WIFI.reason in refused(setup(profile = p, choices = choices(wizardWifi = WizardWifi.OWNER))), id)
            plan(setup(profile = p, choices = choices(installLauncher = false, takeover = false, wizardWifi = WizardWifi.OWNER)))
        }
        // A wizard that is already off on the TV keeps nothing: the switch may go ahead.
        val off = snapshot(disabled = setOf(wizard.value))
        assertNull(HomeSwitchRisk.of(off.facts, shipped.getValue("tcl-43s5k"), off, WizardWifi.OWNER))
        // A profile whose switch never touches the wizard is not held back by the Wi-Fi.
        assertNull(HomeSwitchRisk.of(tv.facts, PlannerFixtures.profile(), tv, WizardWifi.OWNER))
    }

    // (a) The generic path never switches the wizard off, so a setup wizard that is on would stay on
    // while the TV's own home screen goes off: no switch, whatever the Wi-Fi answer (the Wi-Fi line
    // when the TV says the wizard saved it). Without a wizard, or with it off, the switch goes ahead.
    // 0.4.6: on the reference TV under another maker, since the Xiaomi itself now meets R0 (below).
    @Test fun anUnknownTvWithItsSetupWizardOnGetsNoSwitch() = releaseRule {
        val acme = otherMaker(tv)
        assertEquals(Risk.WIZARD_WIFI, HomeSwitchRisk.of(acme.facts, null, acme, WizardWifi.OWNER))
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(acme.facts, null, acme, WizardWifi.UNKNOWN))
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(acme.facts, null, acme, WizardWifi.NOT_OWNER))
        assertEquals(listOf(Risk.WIZARD_WIFI.reason), refused(setup(profile = null, choices = choices(wizardWifi = WizardWifi.OWNER)), snapshot = acme))
        assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = null, choices = choices(wizardWifi = WizardWifi.NOT_OWNER)), snapshot = acme))
        for (w in WizardWifi.entries) assertNull(HomeSwitchRisk.of(noWizard.facts, null, noWizard, w), "$w")
        val off = otherMaker(snapshot(disabled = setOf(wizard.value)))
        assertNull(HomeSwitchRisk.of(off.facts, null, off, WizardWifi.UNKNOWN), "a wizard already off stays off")
        assertTrue(Op.SetHome(PlannerFixtures.launcher) in plan(setup(profile = null, choices = choices(wizardWifi = WizardWifi.UNKNOWN)), snapshot = otherMaker(noWizard)).steps.map { it.op })
    }

    // (b) Google's own hardware: the switch costs casting after every restart.
    @Test fun googleHardwareGetsNoSwitch() {
        for ((device, id) in listOf("sabrina" to "google-chromecast-gtv-4k", "kirkwood" to "google-tv-streamer-4k-kirkwood")) {
            val f = facts(device, "google", "Google")
            assertEquals(id, exact(f)?.id, device)
            assertEquals(Risk.CASTING, HomeSwitchRisk.of(f, exact(f), withFacts(f), WizardWifi.NOT_OWNER), device)
            assertEquals(listOf(Risk.CASTING.reason), refused(setup(profile = exact(f)), snapshot = withFacts(f)), device)
        }
        // The Chromecast HD has no profile and the same casting break.
        assertEquals(Risk.CASTING, HomeSwitchRisk.of(facts("boreal", "google", "Google"), null, tv, WizardWifi.NOT_OWNER))
        // Not by the maker alone (an emulator image says Google too), nor by the device name alone.
        assertNull(HomeSwitchRisk.of(facts("generic_x86", "google", "Google"), null, noWizard, WizardWifi.NOT_OWNER))
        assertNull(HomeSwitchRisk.of(facts("sabrina", "Acme"), null, noWizard, WizardWifi.NOT_OWNER))
    }

    // (b2) 0.4.5: the BRAVIA VU3, whose remote's app buttons go through launcherx (upstream PR #1757), gets
    // no switch whoever saved the Wi-Fi; the clean-up alone is planned and leaves launcherx and the setup
    // wizard (casting) as they are. The VU31, a sibling board that ships with the switch, is not held back here.
    @Test fun theRemoteButtonSetGetsNoSwitch() {
        val f = facts("BRAVIA_VU3_4K", "Sony").copy(model = "BRAVIA VU3", fingerprint = "Sony/BRAVIA_VU3/BRAVIA_VU3_4K:14/UKR1.240726.001/386-60842:user/release-keys")
        val vu3 = assertNotNull(exact(f))
        assertEquals("sony-bravia-vu3", vu3.id)
        for (w in WizardWifi.entries) assertEquals(Risk.REMOTE_BUTTONS, HomeSwitchRisk.of(f, vu3, withFacts(f), w), "$w")
        assertEquals(listOf(Risk.REMOTE_BUTTONS.reason), refused(setup(profile = vu3, choices = choices(wizardWifi = WizardWifi.NOT_OWNER)), snapshot = withFacts(f)))
        val cleanUp = plan(setup(profile = vu3, choices = choices(installLauncher = false, takeover = false, wizardWifi = WizardWifi.NOT_OWNER)), snapshot = withFacts(f))
        val off = cleanUp.steps.map { it.op }.filterIsInstance<Op.Disable>().map { it.pkg.value }
        assertTrue("com.google.android.apps.tv.launcherx" !in off && wizard.value !in off, off.toString())
        val vu31 = facts("BRAVIA_VU31_4K", "Sony")
        assertEquals("sony-bravia-vu31", exact(vu31)?.id)
        assertTrue(HomeSwitchRisk.of(vu31, exact(vu31), withFacts(vu31), WizardWifi.NOT_OWNER) != Risk.REMOTE_BUTTONS)
    }

    // (c) The MOKA family: the wizard comes back at boot and the set can boot-loop. The three shipped
    // profiles, and a set of the family that no profile matches (an Android update, another brand
    // build of the same device), get no switch; the Philips R8G, also made by MOKA, does.
    @Test fun theMokaFamilyGetsNoSwitch() {
        val pairs = HomeSwitchRisk.MOKA_PROFILES.flatMap { id -> shipped.getValue(id).match.let { m -> m.devices.map { it to m.brand!! } } }.toSet()
        // The family is what its profiles match on plus the reported sets no profile matches.
        val reported = setOf("R4_GTV" to "MOKA")
        assertEquals(reported, HomeSwitchRisk.MOKA_REPORTED)
        assertEquals(HomeSwitchRisk.MOKA_FAMILY, pairs + reported, "the family is its profiles' pairs plus the reported sets")
        assertTrue(reported.none { it in pairs }, "a reported set with a profile belongs with the profiles")
        for ((f, id) in listOf(facts("R3_GTV", "Kalley", "MOKA") to "kalley-43g200", facts("R10G", "Kalley", "MOKA") to "kalley-r10g",
            facts("R10G", "PRISMPLUS", "MOKA") to "prismplus-q65-ultra-r10g")) {
            assertEquals(id, exact(f)?.id)
            assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(f, exact(f), withFacts(f), WizardWifi.NOT_OWNER), id)
            assertTrue(Risk.BOOT_LOOP.reason in refused(setup(profile = exact(f)), snapshot = withFacts(f)), id)
        }
        val android15 = facts("R10G", "PRISMPLUS", "MOKA", sdk = 35)
        assertNull(exact(android15))
        assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(android15, null, withFacts(android15), WizardWifi.NOT_OWNER))
        assertEquals(listOf(Risk.BOOT_LOOP.reason), refused(setup(profile = null), snapshot = withFacts(android15)))
        assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(facts("r10g", "Kalley", "Kalley"), null, tv, WizardWifi.NOT_OWNER), "case does not matter")
        // Never by the maker alone or the device alone.
        val philips = facts("whitefield", "Philips", "MOKA")
        assertEquals("philips-r8g-google-tv", exact(philips.copy(model = "R8G"))?.id)
        assertNull(HomeSwitchRisk.of(philips, null, noWizard, WizardWifi.NOT_OWNER))
        assertNull(HomeSwitchRisk.of(facts("R10G", "Acme"), null, noWizard, WizardWifi.NOT_OWNER))
        assertNull(HomeSwitchRisk.of(facts("R3_GTV", "Acme"), null, noWizard, WizardWifi.NOT_OWNER))
        // The clean-up alone is planned for the family, with the stock home and the wizard left alone.
        val f = facts("R10G", "Kalley", "MOKA")
        val clean = plan(setup(profile = exact(f), choices = choices(installLauncher = false, takeover = false)), snapshot = withFacts(f))
        assertTrue(clean.sections.isEmpty() && clean.steps.none { it.op == Op.Disable(wizard) })
    }

    // Issues #74 and #102: the ACER R4G is made by MOKA (device R4_GTV), the family of R3_GTV and
    // R10G, and no list matches it. It gets no switch, on the brand set of its maker or without one.
    @Test fun theAcerR4gGetsNoSwitch() {
        val r4g = tv.facts.copy(brand = "ACER", manufacturer = "MOKA", model = "R4G", device = "R4_GTV", sdk = 34)
        assertNull(exact(r4g))
        assertTrue(HomeSwitchRisk.isMokaFamily(r4g))
        val r4gNoWizard = noWizard.copy(facts = r4g).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        for (w in WizardWifi.entries) {
            assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(r4g, null, withFacts(r4g), w), "$w")
            assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(r4g, null, r4gNoWizard, w), "$w, no wizard")
        }
        assertEquals(listOf(Risk.BOOT_LOOP.reason), refused(setup(profile = null), snapshot = withFacts(r4g)))
        // Not the device name under another maker.
        assertNull(HomeSwitchRisk.of(facts("R4_GTV", "Acme"), null, noWizard, WizardWifi.NOT_OWNER))
    }

    // The reference TV keeps its switch.
    @Test fun theReferenceTvKeepsItsSwitch() {
        assertNull(HomeSwitchRisk.of(tv.facts, PlannerFixtures.profile(), tv, WizardWifi.NOT_OWNER))
        assertTrue(plan(setup()).sections.isNotEmpty())
        assertEquals(EnabledState.ENABLED, tv.packages.getValue(wizard).enabled)
    }

    // 0.3.6: a setup wizard the TV shows as a Home claimant that the switch does not switch off takes
    // Home once the TV's own home screen is off, so there is no switch, exact profile or not. The
    // shipped sony-bravia-bf1 list names no wizard of its own, so before this its switch was planned
    // with setupwraith still on. tcl-g10-google-tv names the wizard in its list, which gives it the
    // takeover quirk: the switch switches the wizard off there, as on the other quirk lists below.
    @Test fun aWizardClaimingHomeThatStaysOnBlocksTheSwitchOnExactProfilesToo() {
        val claims = snapshot(claimants = listOf(PlannerFixtures.stock, Component(wizard, ".RecoveryActivity")))
        val bf1 = facts("BRAVIA_BF1_4K_A", "Sony", sdk = 31)
        val p = shipped.getValue("sony-bravia-bf1")
        assertNull(p.setupWraith)
        val s = claims.copy(facts = bf1).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        for (w in WizardWifi.entries) assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(bf1, p, s, w), "sony-bravia-bf1 $w")
        assertTrue(Risk.WIZARD_STAYS_ON.reason in refused(setup(profile = p), snapshot = s))
        // Without the wizard among the claimants, or with it off, the list keeps its switch.
        assertNull(HomeSwitchRisk.of(bf1, p, withFacts(bf1), WizardWifi.NOT_OWNER))
        val off = snapshot(disabled = setOf(wizard.value), claimants = claims.home.claimants.map { it.component })
        assertNull(HomeSwitchRisk.of(bf1, p, off, WizardWifi.NOT_OWNER), "the wizard off")
        // An exact list that names the wizard without the takeover quirk keeps it on: refused too.
        val named = PlannerFixtures.profile(setupWraith = wizard)
        for (w in WizardWifi.entries) assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(claims.facts, named, claims, w), "named $w")
        // The fixture list without the quirk: refused; the clean-up alone is still planned.
        val keeps = PlannerFixtures.profile()
        assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = keeps), snapshot = claims))
        plan(setup(profile = keeps, choices = choices(installLauncher = false, takeover = false)), snapshot = claims)
        // A list with the takeover quirk switches the wizard off inside the switch: still allowed, on
        // the TV of issue #375 (the wizard a system app claiming Home after a rollback).
        val hengshan = Issue036.hengshanSnapshot()
        for (id in listOf("tcl-g10-google-tv", "tcl-55s470g", "hisense-u75-hengshan", "sony-bravia-ae2")) {
            val q = shipped.getValue(id)
            assertTrue(Quirk.HOME_TAKEOVER_SETUPWRAITH in q.quirks && q.setupWraith == wizard, id)
            assertNull(HomeSwitchRisk.of(hengshan.facts, q, hengshan, WizardWifi.NOT_OWNER), id)
            assertEquals(Risk.WIZARD_WIFI, HomeSwitchRisk.of(hengshan.facts, q, hengshan, WizardWifi.OWNER), id)
        }
        val quirk = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
        val sys = claims.copy(packages = claims.packages + (wizard to claims.packages.getValue(wizard).copy(system = true)))
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        val ops = plan(setup(profile = quirk), snapshot = sys).steps.map { it.op }
        assertTrue(Op.Disable(wizard) in ops && Op.SetHome(PlannerFixtures.launcher) in ops)
        // A wizard the switch may not switch off (here it reads as an app the person installed, so
        // the switch-off rule keeps it) stays on: refused, quirk or not.
        if (claims.packages.getValue(wizard).system != true) assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(claims.facts, quirk, claims, WizardWifi.NOT_OWNER))
        // Another wizard claiming Home next to the one the quirk switches off still blocks it.
        val other = Component(PackageName("com.vendor.setupwraith"), ".RecoveryActivity")
        val two = sys.copy(home = HomeResolution(sys.home.resolved, sys.home.claimants + HomeClaimant(other, 0))).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(two.facts, quirk, two, WizardWifi.NOT_OWNER))
        // No profile: a wizard claiming Home that the snapshot does not list counts as on.
        val unlisted = noWizard.copy(home = claims.home).let { it.copy(contentHash = SnapshotCodec.hash(it)) }
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(unlisted.facts, null, unlisted, WizardWifi.NOT_OWNER))
        assertEquals(Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(unlisted.facts, keeps, unlisted, WizardWifi.NOT_OWNER))
    }

    // 0.4.6 (R0): 171 of 236 Xiaomi reports are global firmware whose only Home claimants are Google's
    // Android TV home (priority 2) and Settings' FallbackHome (-1000), with no exact profile. Their
    // setup wizard is on and no Home claimant, so it cannot take Home once tvlauncher is off: the
    // switch goes ahead with the wizard left on, whatever the Wi-Fi answer. Facts from the reports.
    private val tvlauncher = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    private val fallback = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    private val launcherx = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")
    private val patchWall = Component(PackageName("com.mitv.tvhome"), ".MainActivityUserMode")
    private val recovery = Component(wizard, ".RecoveryActivity")
    private fun reported(model: String, device: String, sdk: Int, fingerprint: String, brand: String = "Xiaomi", manufacturer: String = "Xiaomi") =
        tv.facts.copy(brand = brand, manufacturer = manufacturer, model = model, device = device, sdk = sdk, release = "$sdk", fingerprint = fingerprint)
    private val axso1 = reported("MiTV-AXSO1", "dangalFHD", 28, "Xiaomi/dangalFHD/dangalFHD:9/PI/6157:user/release-keys")
    private val mitv4i = reported("MiTV4I", "magnolia", 28, "Xiaomi/magnolia/magnolia:9/PI/3698:user/release-keys")
    private val aesp0 = reported("MiTV-AESP0", "aquaman", 29, "Xiaomi/aquaman/aquaman:10/QT/2514:user/release-keys")
    private val mooq3 = reported("MiTV-MOOQ3", "tarzan", 30, "Redmi/onward/tarzan:11/RTM4.220307.164/8237:user/release-keys", brand = "Redmi")
    private val r0Tvs = listOf(axso1, mitv4i, aesp0, mooq3)

    // A TV with those facts, the reference packages (the wizard installed and on), Home on `home`
    // and these claimants at the priorities the TVs report.
    private fun r0Tv(f: DeviceFacts, claimants: List<HomeClaimant> = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(fallback, -1000)),
        home: Component? = tvlauncher, disabled: Set<String> = emptySet()): Snapshot =
        snapshot(disabled = disabled).copy(facts = f, home = HomeResolution(home, claimants)).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    @Test fun r0LetsTheReportedXiaomiSetsSwitchWithTheWizardLeftOn() {
        for (f in r0Tvs) {
            val s = r0Tv(f)
            val res = source.resolveNow(f)
            assertTrue(res is ProfileResolution.BrandSureSet && res.brand == "Xiaomi", "${f.model}: $res")
            assertEquals(EnabledState.ENABLED, s.packages.getValue(wizard).enabled)
            assertTrue(HomeSwitchRisk.wizardStaysOnSafely(f, s), f.model)
            for (w in WizardWifi.entries) {
                assertNull(HomeSwitchRisk.of(f, null, s, w), "${f.model} $w")
                // The plan has the switch, never touches the wizard and writes no setting (so no Wi-Fi).
                val p = plan(PlanRequest.Setup(res, choices(wizardWifi = w), PlannerFixtures.artifact(), Level.SIMPLE), snapshot = s)
                val ops = p.steps.map { it.op }
                assertEquals(1, p.sections.size, "${f.model} $w")
                assertTrue(Op.Disable(tvlauncher.pkg) in ops && Op.SetHome(PlannerFixtures.launcher) in ops, "${f.model} $w")
                assertTrue(ops.none { (it is Op.Disable && it.pkg == wizard) || (it is Op.Enable && it.pkg == wizard) }, "${f.model} $w: the wizard is left as it is")
                assertTrue(ops.none { it is Op.SettingsPut }, "${f.model} $w: no setting is written")
                assertTrue(p.sections.single().rollback.none { it == Op.Enable(wizard) || it == Op.Disable(wizard) }, "${f.model} $w")
            }
        }
        // Our own home screen among the claimants (a set where it is installed) changes nothing, nor
        // does the case of the maker's name (xiaomi in some reports).
        val ours = r0Tv(axso1, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(PlannerFixtures.launcher, 0), HomeClaimant(fallback, -1000)))
        assertNull(HomeSwitchRisk.of(axso1, null, ours, WizardWifi.OWNER))
        // Home answering to a placeholder (FallbackHome) or to nothing is the same case.
        assertNull(HomeSwitchRisk.of(axso1, null, r0Tv(axso1, home = fallback), WizardWifi.OWNER))
        assertNull(HomeSwitchRisk.of(axso1, null, r0Tv(axso1, home = null), WizardWifi.OWNER))
        val lower = axso1.copy(brand = "xiaomi", manufacturer = "xiaomi")
        assertNull(HomeSwitchRisk.of(lower, null, r0Tv(lower), WizardWifi.OWNER))
        // A Redmi by its brand alone counts as Xiaomi's own.
        val redmi = mooq3.copy(manufacturer = "Redmi")
        assertNull(HomeSwitchRisk.of(redmi, null, r0Tv(redmi), WizardWifi.UNKNOWN))
    }

    @Test fun r0KeepsTheRuleWhereTheWizardCouldTakeHome() {
        for (f in r0Tvs) for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            fun check(s: Snapshot, why: String, facts: DeviceFacts = f, profile: Profile? = null, risk: Risk? = blocked) =
                assertEquals(risk, HomeSwitchRisk.of(facts, profile, s, w), "${f.model} $w: $why")
            val two = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(fallback, -1000))
            // The wizard claims Home: no switch, whoever saved the Wi-Fi.
            check(r0Tv(f, two + HomeClaimant(recovery, 0)), "the wizard claims Home")
            // Another wizard claims Home: a vendor one (Xiaomi's own) or AOSP's provision.
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.xiaomi.setupwizard"), ".SetupActivity"), 0)), "Xiaomi's setup wizard claims Home")
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.android.provision"), ".DefaultActivity"), 0)), "provision claims Home")
            // Google TV (launcherx), PatchWall, or any other home among the claimants.
            check(r0Tv(f, listOf(HomeClaimant(launcherx, 2), HomeClaimant(fallback, -1000)), home = launcherx), "Google TV")
            check(r0Tv(f, two + HomeClaimant(launcherx, 1)), "launcherx next to tvlauncher")
            check(r0Tv(f, listOf(HomeClaimant(patchWall, 100), HomeClaimant(fallback, -1000)), home = patchWall), "PatchWall")
            check(r0Tv(f, two + HomeClaimant(patchWall, 0)), "PatchWall next to tvlauncher")
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)), "a vendor home")
            // tvlauncher not a claimant, or switched off.
            check(r0Tv(f, listOf(HomeClaimant(fallback, -1000)), home = fallback), "no tvlauncher")
            check(r0Tv(f, disabled = setOf(tvlauncher.pkg.value)), "tvlauncher switched off")
            // Home answers to an app the claimants do not list (PatchWall resolved while only tvlauncher
            // and FallbackHome are listed), no claimants at all, or tvlauncher with no package entry.
            check(r0Tv(f, home = patchWall), "Home answers to PatchWall")
            check(r0Tv(f, emptyList(), home = null), "no claimants")
            check(r0Tv(f).let { s -> s.copy(packages = s.packages - tvlauncher.pkg).let { it.copy(contentHash = SnapshotCodec.hash(it)) } }, "tvlauncher not in the package list")
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.unlisted.home"), ".Home"), 0)), "a claimant with no package entry")
            // The same TV under another maker.
            val acme = f.copy(brand = "Acme", manufacturer = "Acme")
            releaseRule { check(r0Tv(acme), "not a Xiaomi", facts = acme) }
            // An exact profile keeps its own rule: one whose switch switches the wizard off while the
            // wizard saved the Wi-Fi, and a test build's candidate list (never tried with the switch).
            val quirk = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
            check(r0Tv(f), "an exact profile with the wizard quirk", profile = quirk, risk = when (w) {
                WizardWifi.OWNER -> Risk.WIZARD_WIFI; WizardWifi.UNKNOWN -> Risk.WIZARD_WIFI_UNKNOWN; WizardWifi.NOT_OWNER -> null })
            val candidate = PlannerFixtures.profile().copy(tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
            releaseRule { check(r0Tv(f), "a candidate list", profile = candidate) }
        }
        // The planner refuses a switch asked for anyway on a set where the wizard claims Home.
        val claims = r0Tv(axso1, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(recovery, 0), HomeClaimant(fallback, -1000)))
        assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = null), snapshot = claims))
    }

    // The test build's switch (-Pcyt.wizardStaysAnyBrand=true): R0 without its brand test. The
    // release bundles no marker, so its rule is Xiaomi only; the widened rule keeps every other test.
    @Test fun r0IsXiaomiOnlyUnlessATestBuildWidensItToEveryBrand() {
        assertEquals(System.getProperty("cyt.wizardStaysAnyBrand") == "true", HomeSwitchRisk.ANY_BRAND_BUILD, "the bundled switch is the build's")
        if (System.getProperty("cyt.wizardStaysAnyBrand") != "true") {
            assertNull(HomeSwitchRisk::class.java.getResource(HomeSwitchRisk.ANY_BRAND_RESOURCE), "a release build bundles no marker")
        }
        val two = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(fallback, -1000))
        for (brand in listOf("Sony", "OnePlus", "Philips", "Mecool", "Skyworth", "Hisense", "realme", "Haier", "TCL")) {
            val f = axso1.copy(brand = brand, manufacturer = brand, model = "unknown", device = "unknown", fingerprint = "$brand/unknown/unknown:11/x")
            for (w in WizardWifi.entries) {
                val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
                assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = false), "$brand $w, release")
                assertNull(HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = true), "$brand $w, test build")
                // Every other condition of R0 still holds in the test build.
                for ((why, s) in listOf(
                    "the wizard claims Home" to r0Tv(f, two + HomeClaimant(recovery, 0)),
                    "Google TV" to r0Tv(f, listOf(HomeClaimant(launcherx, 2), HomeClaimant(fallback, -1000)), home = launcherx),
                    "a vendor home" to r0Tv(f, two + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)),
                    "no tvlauncher" to r0Tv(f, listOf(HomeClaimant(fallback, -1000)), home = fallback),
                    "tvlauncher switched off" to r0Tv(f, disabled = setOf(tvlauncher.pkg.value)),
                )) assertEquals(blocked, HomeSwitchRisk.of(f, null, s, w, anyBrand = true), "$brand $w, test build: $why")
                val candidate = PlannerFixtures.profile().copy(tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
                // A candidate list gets R0 in the test build (aCandidateListGetsR0OnlyInTheWidenedTestBuild), never in a release.
                assertNull(HomeSwitchRisk.of(f, candidate, r0Tv(f), w, anyBrand = true), "$brand $w, test build: a candidate list")
                assertEquals(blocked, HomeSwitchRisk.of(f, candidate, r0Tv(f), w, anyBrand = false), "$brand $w, release: a candidate list")
            }
        }
        // The other risks are untouched: the MOKA family and Google's hardware keep theirs.
        val moka = axso1.copy(brand = "Kalley", manufacturer = "MOKA", device = "R10G")
        assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(moka, null, r0Tv(moka), WizardWifi.NOT_OWNER, anyBrand = true))
        val boreal = axso1.copy(brand = "google", manufacturer = "Google", device = "boreal")
        assertEquals(Risk.CASTING, HomeSwitchRisk.of(boreal, null, r0Tv(boreal), WizardWifi.NOT_OWNER, anyBrand = true))
    }

    // 0.4.6: in a test build with R0 widened to every brand, a candidate list gets R0 too, since the
    // volunteers who try one are the ones checking it. Issue #258: a OnePlus C00 (Cebu) on Android 11
    // with the candidate list oneplus-tv-c00-cebu, claimants tvlauncher (priority 2) and FallbackHome,
    // setupwraith on and no claimant, was blocked WIZARD_WIFI. A release keeps the rule as it was,
    // and a candidate whose wizard claims Home stays blocked either way.
    @Test fun aCandidateListGetsR0OnlyInTheWidenedTestBuild() {
        val cebu = axso1.copy(brand = "OnePlus", manufacturer = "OnePlus", model = "OnePlus TV C00", device = "cebu", sdk = 30, release = "11",
            fingerprint = "OnePlus/cebu/cebu:11/x:user/release-keys")
        val candidate = PlannerFixtures.profile().copy(id = "oneplus-tv-c00-cebu", tested = TestedInfo("candidate, issue #258", null, null, rebootTested = false, candidate = true))
        val s = r0Tv(cebu)
        for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            assertNull(HomeSwitchRisk.of(cebu, candidate, s, w, anyBrand = true), "test build $w")
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, s, w, anyBrand = false), "release $w")
            val claims = r0Tv(cebu, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(recovery, 0), HomeClaimant(fallback, -1000)))
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, claims, w, anyBrand = true), "test build, the wizard claims Home, $w")
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, claims, w, anyBrand = false), "release, the wizard claims Home, $w")
            // An exact list keeps its own rule in the test build too (here one whose switch would
            // switch the wizard off while it may own the Wi-Fi).
            val exact = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
            assertEquals(HomeSwitchRisk.of(cebu, exact, s, w, anyBrand = false), HomeSwitchRisk.of(cebu, exact, s, w, anyBrand = true), "exact $w")
        }
        // On a Xiaomi a release still never gives a candidate R0.
        for (w in WizardWifi.entries) assertTrue(HomeSwitchRisk.of(axso1, candidate, r0Tv(axso1), w, anyBrand = false) != null, "xiaomi release $w")
    }
}

