package tv.cleanyour.core.planner

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.SettingKey
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
        val reported = setOf("R4_GTV" to "MOKA", "R3" to "SCBC", "R4" to "SCBC")
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

    // 0.4.7-beta2: the same board under Panasonic and other brands reports the maker SCBC (devices R3
    // and R4) and gets no switch either, in the test build's R0 for every brand too.
    @Test fun theScbcBoardsGetNoSwitch() {
        for (device in listOf("R3", "R4")) {
            val set = tv.facts.copy(brand = "Panasonic", manufacturer = "SCBC", model = "2KTV-2TH", device = device, sdk = 34)
            assertTrue(HomeSwitchRisk.isMokaFamily(set), device)
            assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(set, null, withFacts(set), WizardWifi.NOT_OWNER, anyBrand = true), device)
        }
        assertNull(HomeSwitchRisk.of(facts("R4", "Acme"), null, noWizard, WizardWifi.NOT_OWNER))
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
            // beta3: where only another home screen app stands in R0's way, that is the reason told.
            val other = Risk.OTHER_HOME
            check(r0Tv(f, two + HomeClaimant(launcherx, 1)), "launcherx next to tvlauncher", risk = other)
            check(r0Tv(f, listOf(HomeClaimant(patchWall, 100), HomeClaimant(fallback, -1000)), home = patchWall), "PatchWall")
            check(r0Tv(f, two + HomeClaimant(patchWall, 0)), "PatchWall next to tvlauncher", risk = other)
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)), "a vendor home", risk = other)
            // tvlauncher not a claimant, or switched off.
            check(r0Tv(f, listOf(HomeClaimant(fallback, -1000)), home = fallback), "no tvlauncher")
            check(r0Tv(f, disabled = setOf(tvlauncher.pkg.value)), "tvlauncher switched off")
            // Home answers to an app the claimants do not list (PatchWall resolved while only tvlauncher
            // and FallbackHome are listed), no claimants at all, or tvlauncher with no package entry.
            check(r0Tv(f, home = patchWall), "Home answers to PatchWall", risk = other)
            check(r0Tv(f, emptyList(), home = null), "no claimants")
            check(r0Tv(f).let { s -> s.copy(packages = s.packages - tvlauncher.pkg).let { it.copy(contentHash = SnapshotCodec.hash(it)) } }, "tvlauncher not in the package list")
            check(r0Tv(f, two + HomeClaimant(Component(PackageName("com.unlisted.home"), ".Home"), 0)), "a claimant with no package entry", risk = other)
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
        // 0.4.7: Hisense on Android 9 (this TV's version) graduated, so it is no longer in this list.
        for (brand in listOf("Sony", "OnePlus", "Philips", "Mecool", "Skyworth", "realme", "Haier", "TCL")) {
            val f = axso1.copy(brand = brand, manufacturer = brand, model = "unknown", device = "unknown", fingerprint = "$brand/unknown/unknown:11/x")
            for (w in WizardWifi.entries) {
                val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
                assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = false), "$brand $w, release")
                assertNull(HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = true), "$brand $w, test build")
                // Every other condition of R0 still holds in the test build.
                for ((why, s) in listOf(
                    "the wizard claims Home" to r0Tv(f, two + HomeClaimant(recovery, 0)),
                    "Google TV" to r0Tv(f, listOf(HomeClaimant(launcherx, 2), HomeClaimant(fallback, -1000)), home = launcherx),
                    "no tvlauncher" to r0Tv(f, listOf(HomeClaimant(fallback, -1000)), home = fallback),
                    "tvlauncher switched off" to r0Tv(f, disabled = setOf(tvlauncher.pkg.value)),
                )) assertEquals(blocked, HomeSwitchRisk.of(f, null, s, w, anyBrand = true), "$brand $w, test build: $why")
                assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(f, null, r0Tv(f, two + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)), w, anyBrand = true),
                    "$brand $w, test build: a vendor home")
                assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f, two + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)), w, anyBrand = false),
                    "$brand $w, release: R0 is not for this brand, so the wizard is the reason")
                val candidate = PlannerFixtures.profile().copy(tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
                // beta4: a candidate list never gets R0, in the test build either (aCandidateListNeverGetsR0).
                assertEquals(blocked, HomeSwitchRisk.of(f, candidate, r0Tv(f), w, anyBrand = true), "$brand $w, test build: a candidate list")
                assertEquals(blocked, HomeSwitchRisk.of(f, candidate, r0Tv(f), w, anyBrand = false), "$brand $w, release: a candidate list")
            }
        }
        // The other risks are untouched: the MOKA family and Google's hardware keep theirs.
        val moka = axso1.copy(brand = "Kalley", manufacturer = "MOKA", device = "R10G")
        assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(moka, null, r0Tv(moka), WizardWifi.NOT_OWNER, anyBrand = true))
        val boreal = axso1.copy(brand = "google", manufacturer = "Google", device = "boreal")
        assertEquals(Risk.CASTING, HomeSwitchRisk.of(boreal, null, r0Tv(boreal), WizardWifi.NOT_OWNER, anyBrand = true))
    }

    // beta3 gave a candidate list R0 in the test build with R0 widened to every brand (issue #258: a
    // OnePlus C00, Cebu, on Android 11 with the candidate list oneplus-tv-c00-cebu, claimants tvlauncher
    // (2) and FallbackHome, setupwraith on and no claimant). beta4, after the incident of 0.4.6-beta2
    // (a Xiaomi MiTV-AXSO2 on Android 11 on its candidate list stopped starting, issues #945, #1570):
    // no candidate list gets R0 in any build. It keeps the wizard's rule, as a release always did, and
    // a candidate whose wizard claims Home stays blocked either way.
    @Test fun aCandidateListNeverGetsR0() {
        val cebu = axso1.copy(brand = "OnePlus", manufacturer = "OnePlus", model = "OnePlus TV C00", device = "cebu", sdk = 30, release = "11",
            fingerprint = "OnePlus/cebu/cebu:11/x:user/release-keys")
        val candidate = PlannerFixtures.profile().copy(id = "oneplus-tv-c00-cebu", tested = TestedInfo("candidate, issue #258", null, null, rebootTested = false, candidate = true))
        val s = r0Tv(cebu)
        for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, s, w, anyBrand = true), "test build $w")
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, s, w, anyBrand = false), "release $w")
            // The same TV with no list at all still gets R0 in the test build.
            assertNull(HomeSwitchRisk.of(cebu, null, s, w, anyBrand = true), "test build, no list $w")
            val claims = r0Tv(cebu, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(recovery, 0), HomeClaimant(fallback, -1000)))
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, claims, w, anyBrand = true), "test build, the wizard claims Home, $w")
            assertEquals(blocked, HomeSwitchRisk.of(cebu, candidate, claims, w, anyBrand = false), "release, the wizard claims Home, $w")
            // An exact list keeps its own rule in the test build too (here one whose switch would
            // switch the wizard off while it may own the Wi-Fi).
            val exact = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
            assertEquals(HomeSwitchRisk.of(cebu, exact, s, w, anyBrand = false), HomeSwitchRisk.of(cebu, exact, s, w, anyBrand = true), "exact $w")
        }
        // Nor on a Xiaomi, in either build.
        for (w in WizardWifi.entries) for (anyBrand in listOf(false, true))
            assertTrue(HomeSwitchRisk.of(axso1, candidate, r0Tv(axso1), w, anyBrand = anyBrand) != null, "xiaomi anyBrand=$anyBrand $w")
    }

    // beta3, issue #1119 (kjntl007, Xiaomi MiTV4I magnolia, Android 9): claimants tvlauncher (2), a
    // home screen app the person installed (0) and FallbackHome (-1000). R0 accepts such an app, as
    // the snapshot tells it (listed, not a system app, priority 0 at most); the switch keeps it
    // installed and on, makes ours Home and checks it, and rolls back if that app (or the chooser) is
    // in front instead.
    private val theirs = Component(PackageName("person.home1"), ".Main")
    private fun withTheirs(s: Snapshot, system: Boolean = false, pkg: PackageName = theirs.pkg): Snapshot =
        s.copy(packages = s.packages + (pkg to PackageState(EnabledState.ENABLED, system, 1, null, false, false, null, false)))
            .let { it.copy(contentHash = SnapshotCodec.hash(it)) }
    private val three = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(theirs, 0), HomeClaimant(fallback, -1000))

    @Test fun issue1119R0AcceptsAHomeScreenAppThePersonInstalled() {
        val candidate = PlannerFixtures.profile().copy(id = "xiaomi-mitv4i-magnolia", tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
        for (w in WizardWifi.entries) {
            val s = withTheirs(r0Tv(mitv4i, three))
            assertTrue(HomeSwitchRisk.installedHome(theirs.pkg, s))
            assertNull(HomeSwitchRisk.of(mitv4i, null, s, w, anyBrand = false), "release, brand set $w")
            // beta4: not on its candidate list, in the test build either.
            assertEquals(if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON, HomeSwitchRisk.of(mitv4i, candidate, s, w, anyBrand = true), "test build, candidate list $w")
            // Home already answering to their app is their choice: it stays, with no switch (OTHER_HOME).
            assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(mitv4i, null, withTheirs(r0Tv(mitv4i, three, home = theirs)), w, anyBrand = false), "Home is theirs $w")
            // An app with an accessibility service on the TV's list can catch the Home key itself.
            for (list in listOf("person.home1/.KeyService", "com.other/.A:person.home1/person.home1.KeyService")) {
                val keys = withTheirs(r0Tv(mitv4i, three)).let { x -> x.copy(settings = x.settings + (SettingKey.ENABLED_ACCESSIBILITY to list)) }
                assertTrue(!HomeSwitchRisk.installedHome(theirs.pkg, keys), list)
                assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(mitv4i, null, keys, w, anyBrand = false), "$w: $list")
            }
            val otherService = withTheirs(r0Tv(mitv4i, three)).let { x -> x.copy(settings = x.settings + (SettingKey.ENABLED_ACCESSIBILITY to "com.other/.A")) }
            assertNull(HomeSwitchRisk.of(mitv4i, null, otherService, w, anyBrand = false), "$w: another app's service")
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            fun check(snap: Snapshot, why: String) = assertEquals(blocked, HomeSwitchRisk.of(mitv4i, null, snap, w, anyBrand = false), "$w: $why")
            // Still refused: the app is not in the package list, is a system app (a vendor home ships as
            // one), claims above 0 (Android never lets an installed app), is named like a setup wizard
            // or is a vendor home by name; or a wizard claims Home next to it.
            // beta3: the reason told is the other home screen app where only it stands in the way.
            fun other(snap: Snapshot, why: String) = assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(mitv4i, null, snap, w, anyBrand = false), "$w: $why")
            other(r0Tv(mitv4i, three), "the app is not listed")
            other(withTheirs(r0Tv(mitv4i, three), system = true), "a system app")
            other(withTheirs(r0Tv(mitv4i, three)).let { x -> x.copy(packages = x.packages + (theirs.pkg to x.packages.getValue(theirs.pkg).copy(versionCode = 0))) },
                "its details were not read")
            other(withTheirs(r0Tv(mitv4i, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(theirs, 1), HomeClaimant(fallback, -1000)))), "priority 1")
            val wraith = Component(PackageName("person.setupwraith"), ".Main")
            check(withTheirs(r0Tv(mitv4i, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(wraith, 0))), pkg = wraith.pkg), "named like a wizard")
            val vendor = Component(PackageName(Denylist.vendorHomes.first()), ".Home")
            other(withTheirs(r0Tv(mitv4i, listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(vendor, 0))), pkg = vendor.pkg), "a vendor home by name")
            check(withTheirs(r0Tv(mitv4i, three + HomeClaimant(recovery, 0))), "the wizard claims Home too")
            other(withTheirs(r0Tv(mitv4i, three, home = launcherx)), "Home answers to Google TV")
        }
        // The plan: tvlauncher off, their app left alone, ours made Home and checked; their app, the
        // chooser (package android) and FallbackHome in front after Home roll the switch back.
        val s = withTheirs(r0Tv(mitv4i, three))
        val p = plan(PlanRequest.Setup(source.resolveNow(mitv4i), choices(wizardWifi = WizardWifi.NOT_OWNER), PlannerFixtures.artifact(), Level.SIMPLE), snapshot = s)
        val ops = p.steps.map { it.op }
        assertTrue(Op.Disable(tvlauncher.pkg) in ops && Op.SetHome(PlannerFixtures.launcher) in ops && Op.VerifyHome(PlannerFixtures.launcher) in ops, "$ops")
        assertTrue(ops.none { (it is Op.Disable && it.pkg == theirs.pkg) || (it is Op.Uninstall && it.pkg == theirs.pkg) }, "their app stays installed and on")
        val section = p.sections.single()
        assertTrue(theirs.pkg in section.rollbackIfFocus && PackageName("android") in section.rollbackIfFocus && fallback.pkg in section.rollbackIfFocus, "${section.rollbackIfFocus}")
    }

    // beta4, incident of 0.4.6-beta2 (issue #825): a Thomson SPPL 2K (sauve, Android 11, Realtek) on
    // its brand set, claimants tvlauncher (2), Realtek's key service FallbackHome (-999) and Settings'
    // FallbackHome (-1000), took R0 in the test build and failed to start after a few restarts. For R0
    // a FallbackHome is harmless only when it is Android's own or in a package of the evidence list
    // (a Nokia R4, TCL, upstream's Thomson on Android 14); any other blocks R0 with its own reason.
    private val keyintercept = Component(PackageName("com.realtek.keyintercept"), ".fallbackhome.FallbackHome")
    private val thomson = reported("SPPL 2K ANDROID TV", "sauve", 30, "THOMSON/SPPL_2K_RT41/sauve:11/RTK4.241026.026/12861515:user/release-keys",
        brand = "THOMSON", manufacturer = "SPPL")
    private val issue825 = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(keyintercept, -999), HomeClaimant(fallback, -1000))

    @Test fun issue825AVendorServicesFallbackHomeBlocksR0InBothRules() {
        for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            val s = r0Tv(thomson, issue825)
            assertEquals(HomeSwitchRisk.R0Blocker.VENDOR_PLACEHOLDER, HomeSwitchRisk.r0Blocker(thomson, s, anyBrand = true))
            assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, null, s, w, anyBrand = true), "test build $w")
            // Review of beta4: on every path, whatever the brand and the wizard do.
            assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, null, s, w, anyBrand = false), "release $w: not a Xiaomi, still the vendor service")
            // The same claimants on a Xiaomi with the release rule, and Home answering to that placeholder.
            for (f in r0Tvs) {
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(f, null, r0Tv(f, issue825), w, anyBrand = false), "${f.model} release $w")
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(f, null, r0Tv(f, issue825), w, anyBrand = true), "${f.model} test build $w")
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(f, null, r0Tv(f, issue825, home = keyintercept), w, anyBrand = false), "${f.model} Home on it $w")
            }
            // Other vendor FallbackHomes, Settings' packages with a class of their own, and one next to
            // another home screen app (the vendor placeholder is the reason told).
            for (c in listOf(Component(PackageName("com.realtek.menu"), ".FallbackHome"), Component(PackageName("com.vstv.fallbackhome"), ".FallbackHome"),
                Component(PackageName("com.android.tv.settings"), ".vendor.FallbackHome"), Component(PackageName("com.android.settings"), ".tv.FallbackHome"))) {
                val claims = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(c, -999), HomeClaimant(fallback, -1000))
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, null, r0Tv(thomson, claims), w, anyBrand = true), "${c.flat} $w")
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(axso1, null, r0Tv(axso1, claims), w, anyBrand = false), "${c.flat} Xiaomi $w")
            }
            val withVendorHome = issue825 + HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0)
            assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(axso1, null, r0Tv(axso1, withVendorHome), w, anyBrand = false), "and a vendor home $w")
            // The wizard claiming Home too: the vendor service is still the reason.
            assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(axso1, null, r0Tv(axso1, issue825 + HomeClaimant(recovery, 0)), w, anyBrand = false), "the wizard claims Home $w")
            // The setup wizard off (or not installed), and an exact list whose switch would switch it off.
            val wizardOff = r0Tv(thomson, issue825, disabled = setOf(wizard.value))
            for (anyBrand in listOf(false, true)) {
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, null, wizardOff, w, anyBrand = anyBrand), "the wizard off, anyBrand=$anyBrand $w")
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, null, s.copy(packages = s.packages - wizard), w, anyBrand = anyBrand), "no wizard $w")
                val exact = PlannerFixtures.profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, exact, s, w, anyBrand = anyBrand), "an exact list $w")
                assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(thomson, PlannerFixtures.profile(), wizardOff, w, anyBrand = anyBrand), "an exact list, the wizard off $w")
            }
        }
        // The planner refuses a switch asked for anyway, with that reason.
        releaseRule {
            val s = r0Tv(axso1, issue825)
            assertEquals(listOf(Risk.VENDOR_HOME.reason), refused(setup(profile = null), snapshot = s))
        }
        // Every other caller keeps the wider placeholder test: Settings' card, Done and the check after
        // the switch only ask whether a HOME answer is a home screen.
        assertTrue(HomePlaceholder.isPlaceholder(keyintercept))
        assertTrue(!HomeSwitchRisk.r0Placeholder(keyintercept))
    }

    // What R0 still takes: Android's own FallbackHome (TV Settings' or Settings', its class written
    // short or in full), and the evidence list, at the priorities the reports give.
    @Test fun r0StillTakesAndroidsOwnPlaceholdersAndTheEvidenceList() {
        val nokia = reported("R4", "R4", 30, "NOKIA/R4/R4:11/RTT7.210923.001/x:user/release-keys", brand = "NOKIA", manufacturer = "NOKIA")
        for (w in WizardWifi.entries) {
            for ((c, priority) in listOf(
                Component(PackageName("com.apps.tvsettings"), ".ui.FallbackHome") to -999,   // the Nokia R4 (issue #512)
                Component(PackageName("com.apps.tvsettings"), ".FallbackHome") to -999,      // as its report writes it
                Component(PackageName("com.android.boot.fallbackhome"), ".FallbackHomeCustomizer") to 0,   // TCL (#481), Hisense (#1388)
                Component(PackageName("com.realtek.systemservice"), ".fallbackhome.FallbackHome") to -999,  // upstream's Thomson on Android 14
                Component(PackageName("com.android.settings"), ".FallbackHome") to -1000,
                Component(PackageName("com.android.tv.settings"), "com.android.tv.settings.system.FallbackHome") to -1000,
            )) {
                val claims = listOf(HomeClaimant(tvlauncher, 2), HomeClaimant(c, priority), HomeClaimant(fallback, -1000))
                assertNull(HomeSwitchRisk.of(nokia, null, r0Tv(nokia, claims), w, anyBrand = true), "${c.flat} test build $w")
                assertNull(HomeSwitchRisk.of(axso1, null, r0Tv(axso1, claims), w, anyBrand = false), "${c.flat} Xiaomi release $w")
                assertTrue(HomeSwitchRisk.r0Placeholder(c), c.flat)
                // Review of beta4: an exact list with such a claimant keeps its own rule.
                val exactTv = r0Tv(nokia, claims, disabled = setOf(wizard.value))
                assertNull(HomeSwitchRisk.of(nokia, PlannerFixtures.profile(), exactTv, w, anyBrand = false), "${c.flat} exact $w")
            }
            // The chooser answering Home is still a placeholder.
            val chooser = Component(PackageName("android"), "com.android.internal.app.ResolverActivity")
            assertNull(HomeSwitchRisk.of(axso1, null, r0Tv(axso1, home = chooser), w, anyBrand = false), "the chooser $w")
        }
    }

    // beta4, after the incident of 0.4.6-beta2: a release gives R0 to Xiaomi and Redmi with no list
    // on Android 9, 10 and 11 only, and holds out the MiTV-AXSO2 (dangalUHD) on Android 11 (issue
    // #945) until its owner answers. The test build keeps every brand and Android version, and holds
    // that set out too.
    @Test fun r0InAReleaseIsAndroid9To11AndTheAxso2OnAndroid11IsHeldOut() {
        val axso2 = reported("MiTV-AXSO2", "dangalUHD", 30, "Xiaomi/dangalUHD/dangalUHD:11/RTT0.211201.001/1150:user/release-keys")
        val axso2On9 = reported("MiTV-AXSO2", "dangalUHD", 28, "Xiaomi/dangalUHD/dangalUHD:9/PI/6432:user/release-keys")
        for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            for (sdk in 26..35) {
                val f = axso1.copy(sdk = sdk, release = "$sdk")
                if (sdk in 28..30) assertNull(HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = false), "release SDK $sdk $w")
                else {
                    assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = false), "release SDK $sdk $w")
                    assertEquals(HomeSwitchRisk.R0Blocker.SCOPE, HomeSwitchRisk.r0Blocker(f, r0Tv(f), anyBrand = false), "SDK $sdk")
                }
                assertNull(HomeSwitchRisk.of(f, null, r0Tv(f), w, anyBrand = true), "test build SDK $sdk $w")
                // Another brand in the test build, on any version.
                val sony = f.copy(brand = "Sony", manufacturer = "Sony", device = "unknown")
                assertNull(HomeSwitchRisk.of(sony, null, r0Tv(sony), w, anyBrand = true), "test build Sony SDK $sdk $w")
            }
            for (anyBrand in listOf(false, true)) {
                assertEquals(blocked, HomeSwitchRisk.of(axso2, null, r0Tv(axso2), w, anyBrand = anyBrand), "AXSO2 on Android 11, anyBrand=$anyBrand $w")
                assertEquals(blocked, HomeSwitchRisk.of(axso2.copy(device = "dangaluhd"), null, r0Tv(axso2), w, anyBrand = anyBrand), "case does not matter")
                assertNull(HomeSwitchRisk.of(axso2On9, null, r0Tv(axso2On9), w, anyBrand = anyBrand), "AXSO2 on Android 9, anyBrand=$anyBrand $w")
            }
        }
        assertTrue(source.resolveNow(axso2) is ProfileResolution.BrandSureSet, "the AXSO2 on Android 11 has no list in a release")
        releaseRule { assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = null, choices = choices(wizardWifi = WizardWifi.NOT_OWNER)), snapshot = r0Tv(axso2))) }
    }

    // 0.4.7: the release's R0 scope is the reviewed GRADUATED table. Its rows are pinned here, so a new row is a
    // reviewed change of this test too (with its issues in the row's comment and R0-GRADUATION.md).
    @Test fun theGraduatedTableIsTheReviewedOne() {
        data class Row(val makers: Set<String>, val sdks: IntRange, val models: Set<String>, val devices: Set<String>, val excluded: Set<String>)
        assertEquals(listOf(
            Row(setOf("Xiaomi", "Redmi"), 28..30, emptySet(), emptySet(), emptySet()),
            Row(setOf("Xiaomi", "Redmi"), 34..34, setOf("MiTV-MZTU1"), emptySet(), emptySet()),
            Row(setOf("OnePlus"), 30..30, emptySet(), emptySet(), emptySet()),
            Row(setOf("My"), 30..30, emptySet(), setOf("yeoksam", "shandao", "tokyo"), emptySet()),
            Row(setOf("Hisense"), 28..28, emptySet(), emptySet(), emptySet()),
            Row(setOf("Homatics"), 34..34, emptySet(), emptySet(), emptySet()),
            Row(setOf("MECOOL"), 34..34, emptySet(), emptySet(), emptySet()),
            Row(setOf("Sony"), 26..26, emptySet(), emptySet(), emptySet()),
            Row(setOf("Xiaomi", "Redmi"), 34..34, setOf("MiTV-MOEU1"), emptySet(), emptySet()),
            Row(setOf("MI"), 28..28, setOf("MiProjM05"), setOf("eva"), emptySet()),
        ), HomeSwitchRisk.GRADUATED.map { Row(it.makers, it.sdks, it.models, it.devices, it.excludedDevices) })
        for (g in HomeSwitchRisk.GRADUATED) {
            assertTrue(g.makers.isNotEmpty() && !g.sdks.isEmpty() && g.sdks.first >= 26 && g.why.isNotBlank(), g.toString())
            // A 0.4.7 or 0.4.8 row is one Android version: a new version needs its own confirmations.
            if ("(0.4.7)" in g.why || "(0.4.8)" in g.why) assertEquals(g.sdks.first, g.sdks.last, g.toString())
        }
        // A brand with no row is BRAND, whatever its version.
        val philips = axso1.copy(brand = "Philips", manufacturer = "TPV", device = "unknown")
        assertEquals(HomeSwitchRisk.R0Blocker.BRAND, HomeSwitchRisk.r0Blocker(philips, r0Tv(philips), anyBrand = false))
    }

    // 0.4.7 graduations: a TV of each confirming report (the facts its issue's report printed) meets R0 in the
    // release rule, and the plan switches with the setup wizard left on, whoever saved the Wi-Fi.
    private fun graduatedTv(model: String, device: String, sdk: Int, fingerprint: String, brand: String, manufacturer: String) =
        reported(model, device, sdk, fingerprint, brand = brand, manufacturer = manufacturer)
    private val confirmedTvs: List<Pair<String, DeviceFacts>> = listOf(
        "#1505 #868 #1877 #2809" to reported("MiTV-MZTU1", "stepup", 34, "Xiaomi/stepup/stepup:14/UKN1.240806.017/V816.0.26.6.8.3.UZTMBTV:user/release-keys"),
        "#235 #2420" to graduatedTv("D00", "Dubai", 30, "OnePlus/OnePlusTV/Dubai:11/RTM1.210315.027/2303030058:user/release-keys", "OnePlus", "OnePlus"),
        "#2360 #2492 #2524" to graduatedTv("C00", "Cebu", 30, "OnePlus/OnePlusTV/Cebu:11/RTM8.240401.135/2408080101:user/release-keys", "OnePlus", "OnePlus"),
        "#673" to graduatedTv("Android TV", "yeoksam", 30, "My/Alp/yeoksam:11/RTOC.251019.001/14296264:user/release-keys", "My", "My"),
        "#864 #2367" to graduatedTv("Android TV", "shandao", 30, "My/EMIR/shandao:11/RTM8.240401.031/11776249:user/release-keys", "My", "My"),
        "#1546" to graduatedTv("Android TV", "tokyo", 30, "My/Aslan/tokyo:11/RTOC.250820.001/13968254:user/release-keys", "My", "My"),
        "#1388 #2814" to graduatedTv("HiSmart 4K ATV4", "xinhaoshan", 28, "Hisense/xinhaoshan/xinhaoshan:9/PTT1.181214.002/1:user/release-keys", "Hisense", "Hisense"),
        "#1094 #654" to graduatedTv("Box R 4K Plus", "YYJ", 34, "Homatics/SEI804HM/YYJ:14/UKG3.250803.001/8963:user/release-keys", "Homatics", "SEI Robotics"),
        "#2396" to graduatedTv("LEAP-S1", "LAS", 34, "MECOOL/HP4035-Mecool/LAS:14/UKG3.240613.001/C1.4.1_20251218:user/release-keys", "MECOOL", "SkyworthDigital"),
        "#2522" to graduatedTv("4K Android TV Box", "YYC", 34, "MECOOL/HP4414-MECOOL/YYC:14/UKG3.250115.001/C1.2.7_20250822:user/release-keys", "MECOOL", "SkyworthDigital"),
        "#1219 #1585" to graduatedTv("BRAVIA 4K GB ATV3", "BRAVIA_ATV3_4K", 26, "Sony/BRAVIA_ATV3_4K_EU/BRAVIA_ATV3_4K:8.0.0/OPR2.170623.027.S34/665751:user/release-keys", "Sony", "Sony"),
        "#3099 #3312" to reported("MiTV-MOEU1", "lalaland", 34, "Xiaomi/lalaland/lalaland:14/UKN1.240806.017/V816.0.25.12.1.UOEMBTV:user/release-keys"),
        "#1647 #2925" to graduatedTv("MiProjM05", "eva", 28, "MI/eva/eva:9/PQ3B.190705.003/4185:user/release-keys", "MI", "fengmi"),
    )
    // The two Android 8 sets reported tvlauncher at priority 0, the others at 2.
    private fun confirmedTv(f: DeviceFacts, extra: List<HomeClaimant> = emptyList(), home: Component? = tvlauncher): Snapshot =
        r0Tv(f, listOf(HomeClaimant(tvlauncher, if (f.sdk == 26) 0 else 2), HomeClaimant(fallback, -1000)) + extra, home = home)

    @Test fun eachGraduatedRowCoversItsConfirmingTvs() = releaseRule {
        for ((issues, f) in confirmedTvs) {
            val s = confirmedTv(f)
            assertEquals(1, HomeSwitchRisk.GRADUATED.count { it.covers(f) }, "$issues ${f.model}: one row covers it")
            assertTrue(HomeSwitchRisk.graduated(f), "$issues ${f.model}")
            val res = source.resolveNow(f)
            assertTrue(res !is ProfileResolution.Exact, "$issues ${f.model}: no exact list, so R0 is its path ($res)")
            assertNull(HomeSwitchRisk.r0Blocker(f, s, anyBrand = false), "$issues ${f.model}")
            assertTrue(HomeSwitchRisk.wizardStaysOnSafely(f, s, anyBrand = false), "$issues ${f.model}")
            for (w in WizardWifi.entries) {
                assertNull(HomeSwitchRisk.of(f, null, s, w, anyBrand = false), "$issues ${f.model} $w")
                val p = plan(PlanRequest.Setup(res, choices(wizardWifi = w), PlannerFixtures.artifact(), Level.SIMPLE), snapshot = s)
                val ops = p.steps.map { it.op }
                assertTrue(Op.Disable(tvlauncher.pkg) in ops && Op.SetHome(PlannerFixtures.launcher) in ops, "$issues ${f.model} $w: the switch")
                assertTrue(ops.none { (it is Op.Disable && it.pkg == wizard) || (it is Op.Enable && it.pkg == wizard) }, "$issues ${f.model} $w: the wizard is left as it is")
                assertTrue(ops.none { it is Op.SettingsPut }, "$issues ${f.model} $w: no setting is written")
            }
        }
    }

    // Every incident block applies to a graduated TV as to any other: a vendor service's FallbackHome (VENDOR_HOME,
    // issue #825), another home screen app (OTHER_HOME), a wizard that claims Home, Google TV's home or no
    // tvlauncher, a candidate list, the Google hardware and the MOKA family.
    @Test fun theIncidentBlocksStillApplyToTheGraduatedTvs() = releaseRule {
        val candidate = PlannerFixtures.profile().copy(tested = TestedInfo("candidate", null, null, rebootTested = false, candidate = true))
        for ((issues, f) in confirmedTvs) for (w in WizardWifi.entries) {
            val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
            val at = "$issues ${f.model} $w"
            assertEquals(Risk.VENDOR_HOME, HomeSwitchRisk.of(f, null, confirmedTv(f, listOf(HomeClaimant(keyintercept, -999))), w, anyBrand = false), "$at: VENDOR_HOME")
            assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(f, null, confirmedTv(f, listOf(HomeClaimant(Component(PackageName("com.vendor.launcher"), ".Home"), 0))), w, anyBrand = false), "$at: OTHER_HOME")
            assertEquals(Risk.OTHER_HOME, HomeSwitchRisk.of(f, null, confirmedTv(f, listOf(HomeClaimant(launcherx, 1))), w, anyBrand = false), "$at: launcherx too")
            assertEquals(blocked, HomeSwitchRisk.of(f, null, confirmedTv(f, listOf(HomeClaimant(recovery, 0))), w, anyBrand = false), "$at: the wizard claims Home")
            assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f, listOf(HomeClaimant(launcherx, 2), HomeClaimant(fallback, -1000)), home = launcherx), w, anyBrand = false), "$at: Google TV")
            assertEquals(blocked, HomeSwitchRisk.of(f, null, r0Tv(f, disabled = setOf(tvlauncher.pkg.value)), w, anyBrand = false), "$at: tvlauncher off")
            assertEquals(blocked, HomeSwitchRisk.of(f, candidate, confirmedTv(f), w, anyBrand = false), "$at: a candidate list")
            assertEquals(Risk.BOOT_LOOP, HomeSwitchRisk.of(f.copy(device = "R10G", manufacturer = "MOKA"), null, confirmedTv(f), w, anyBrand = false), "$at: MOKA")
        }
        // HELD_OUT keeps the AXSO2 on Android 11 out, next to the new Xiaomi row.
        val axso2 = reported("MiTV-AXSO2", "dangalUHD", 30, "Xiaomi/dangalUHD/dangalUHD:11/RTT0.211201.001/1150:user/release-keys")
        assertEquals(HomeSwitchRisk.R0Blocker.SCOPE, HomeSwitchRisk.r0Blocker(axso2, r0Tv(axso2), anyBrand = false))
        // Google's own hardware keeps CASTING; "google" has no row (its confirmations were the Home button helper's).
        val chromecast = reported("Chromecast", "sabrina", 34, "google/sabrina_prod_stable/sabrina:14/UTTC.250917.004/14193937:user/release-keys", brand = "google", manufacturer = "Google")
        assertEquals(Risk.CASTING, HomeSwitchRisk.of(chromecast, null, r0Tv(chromecast), WizardWifi.NOT_OWNER, anyBrand = false))
        assertEquals(HomeSwitchRisk.R0Blocker.BRAND, HomeSwitchRisk.r0Blocker(chromecast, r0Tv(chromecast), anyBrand = false))
    }

    // The pairs 0.4.7 does not graduate keep the wizard's rule in a release: TCL on Android 11 and realme on Android
    // 14 (a failure among their confirmations), the pairs with under 2 R0 confirmations, other models, sets and
    // Android versions of the graduated brands. The test build keeps R0 for all of them.
    @Test fun thePairsNotGraduatedStayOffInTheRelease() = releaseRule {
        val brand = HomeSwitchRisk.R0Blocker.BRAND
        val scope = HomeSwitchRisk.R0Blocker.SCOPE
        val off: List<Triple<String, DeviceFacts, HomeSwitchRisk.R0Blocker>> = listOf(
            Triple("TCL 11 (#1799, #2061, #2082 failed)", graduatedTv("Smart TV", "BeyondTV4", 30, "TCL/G03_4K_US_NF_DB/BeyondTV4:11/RP1A.200622.001/AR26:user/release-keys", "TCL", "TCL"), brand),
            Triple("TCL 11 G06", graduatedTv("Smart TV", "G06", 30, "TCL/G06/G06:11/RP1A.200622.001/1:user/release-keys", "TCL", "TCL"), brand),
            Triple("realme 14 (#2126 failed)", graduatedTv("AI PONT", "redwood", 34, "realme/DVB92RM/redwood:14/UKNV.260514.001/15419756:user/release-keys", "realme", "Changhong"), brand),
            Triple("realme 14 stick", graduatedTv("TV Stick", "BOS", 34, "realme/HP4702-realme/BOS:14/UKG3.250826.001/1:user/release-keys", "realme", "SkyworthDigital"), brand),
            Triple("Kogan 11", graduatedTv("AI PONT", "samseong", 30, "Kogan/DVB93KG/samseong:11/RTO2.250508.001/13470691:user/release-keys", "Kogan", "Changhong"), brand),
            Triple("SHARP 11", graduatedTv("AQUOS_TVE19A", "sindang", 30, "SHARP/tcbrae_a/sindang:11/RTOB.250721.001/13808904:user/release-keys", "SHARP", "SHARP"), brand),
            Triple("SHARP 14", graduatedTv("SHARP 2K EU Android TV", "martin", 34, "SHARP/SHARP_2K_ATV2/martin:14/UKNV.260324.001/15080168:user/release-keys", "SHARP", "Expressluck"), brand),
            Triple("Strong 14, the MECOOL board", graduatedTv("LEAP-S1", "LAS", 34, "Strong/HP4005/LAS:14/UKG3.240613.001/1:user/release-keys", "Strong", "SkyworthDigital"), brand),
            Triple("THOMSON 14", graduatedTv("270", "SNA", 34, "THOMSON/Streaming_Box_Plus/SNA:14/URO4.260304.011.B1/15051976:user/release-keys", "THOMSON", "SDMC"), brand),
            Triple("TiVo Stream 4K, SEI Robotics", graduatedTv("TiVo Stream 4K", "SEI400TV", 31, "eSTREAM4K/SEI400TV/SEI400TV:12/1:user/release-keys", "eSTREAM4K", "SEI Robotics"), brand),
            Triple("Hisense 11", graduatedTv("HiSmart 2K ATV4", "hamamatsucho", 30, "Hisense/fushan_emeu/hamamatsucho:11/RTO6.230411.001/9924732:user/release-keys", "Hisense", "Hisense"), scope),
            Triple("Toshiba on Hisense's board, 11", graduatedTv("Smart TV", "hamamatsucho", 30, "Toshiba/hamamatsucho/hamamatsucho:11/1:user/release-keys", "Toshiba", "Hisense"), scope),
            Triple("OnePlus's JMGO on 14", graduatedTv("H80A", "songni", 34, "JMGO/songni/songni:14/1:user/release-keys", "JMGO", "OnePlus"), scope),
            Triple("OnePlus on 12", graduatedTv("C00", "Cebu", 31, "OnePlus/OnePlusTV/Cebu:12/1:user/release-keys", "OnePlus", "OnePlus"), scope),
            Triple("My on 14", graduatedTv("Android TV", "finch", 34, "My/finch/finch:14/1:user/release-keys", "My", "vestel"), scope),
            Triple("My on 11, another set", graduatedTv("Android TV", "finch", 30, "My/finch/finch:11/1:user/release-keys", "My", "My"), scope),
            Triple("My on 9", graduatedTv("Android TV", "yeoksam", 28, "My/Horizon/yeoksam:9/1:user/release-keys", "My", "My"), scope),
            Triple("Hisense on 10", graduatedTv("HiSmart 4K ATV4", "xinhaoshan", 29, "Hisense/xinhaoshan/xinhaoshan:10/1:user/release-keys", "Hisense", "Hisense"), scope),
            Triple("Homatics on 12", graduatedTv("Box R 4K Plus", "YYJ", 31, "Homatics/SEI804HM/YYJ:12/1:user/release-keys", "Homatics", "SEI Robotics"), scope),
            Triple("MECOOL on 11", graduatedTv("KM2", "deadpool", 30, "MECOOL/KM2/deadpool:11/1:user/release-keys", "MECOOL", "SkyworthDigital"), scope),
            Triple("Sony on 10 (#586, 1 of 2)", graduatedTv("BRAVIA 4K UR3", "BRAVIA_UR3", 29, "Sony/BRAVIA_UR3/BRAVIA_UR3:10/1:user/release-keys", "Sony", "Sony"), scope),
            Triple("Xiaomi 14, another model (#884, the helper)", reported("MiTV-MOOR2", "venom", 34, "Xiaomi/venom/venom:14/1:user/release-keys"), scope),
            Triple("Xiaomi 12 (#1273 failed)", reported("MIBOX4", "oneday", 31, "Xiaomi/oneday/oneday:12/1:user/release-keys"), scope),
            Triple("Xiaomi 13, the MZTU1's model", reported("MiTV-MZTU1", "stepup", 33, "Xiaomi/stepup/stepup:13/1:user/release-keys"), scope),
            Triple("Xiaomi 14, the MOEU0 beside the MOEU1", reported("MiTV-MOEU0", "ladybird", 34, "Xiaomi/ladybird/ladybird:14/1:user/release-keys"), scope),
            Triple("Xiaomi 13, the MOEU1's model", reported("MiTV-MOEU1", "lalaland", 33, "Xiaomi/lalaland/lalaland:13/1:user/release-keys"), scope),
            Triple("MI on 9, another fengmi projector", graduatedTv("MiProjM02", "almond", 28, "MI/almond/almond:9/1:user/release-keys", "MI", "fengmi"), scope),
            Triple("MI MiProjM05 on 11", graduatedTv("MiProjM05", "eva", 30, "MI/eva/eva:11/1:user/release-keys", "MI", "fengmi"), scope),
        )
        for ((why, f, blocker) in off) {
            assertTrue(!HomeSwitchRisk.graduated(f), why)
            val s = confirmedTv(f)
            assertEquals(blocker, HomeSwitchRisk.r0Blocker(f, s, anyBrand = false), why)
            assertNull(HomeSwitchRisk.r0Blocker(f, s, anyBrand = true), "$why: the test build keeps R0")
            for (w in WizardWifi.entries) {
                val blocked = if (w == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
                assertEquals(blocked, HomeSwitchRisk.of(f, null, s, w, anyBrand = false), "$why $w")
            }
            assertEquals(listOf(Risk.WIZARD_STAYS_ON.reason), refused(setup(profile = null, choices = choices(wizardWifi = WizardWifi.NOT_OWNER)), snapshot = s), why)
        }
    }

    // A row's optional parts: only some models, and sets held out by device; brand or manufacturer, case ignored.
    @Test fun aGraduatedRowCoversItsMakersVersionsAndModelsOnly() {
        val row = HomeSwitchRisk.Graduated(setOf("Xiaomi"), 34..34, models = setOf("MiTV-MZTU1"), excludedDevices = setOf("held"), why = "test")
        val mztu1 = reported("MiTV-MZTU1", "stepup", 34, "Xiaomi/stepup/stepup:14/UKN1/1:user/release-keys")
        assertTrue(row.covers(mztu1))
        assertTrue(row.covers(mztu1.copy(model = "mitv-mztu1", brand = "xiaomi")))
        assertTrue(row.covers(mztu1.copy(brand = "Redmi")), "the manufacturer is enough")
        assertTrue(!row.covers(mztu1.copy(sdk = 33)))
        assertTrue(!row.covers(mztu1.copy(model = "MiTV-MZTU2")))
        assertTrue(!row.covers(mztu1.copy(device = "Held")))
        assertTrue(!row.covers(mztu1.copy(brand = "Sony", manufacturer = "Sony")))
        val anyModel = row.copy(models = emptySet())
        assertTrue(anyModel.covers(mztu1.copy(model = "MiTV-MZTU2")))
        // 0.4.7: only some sets, by device, case ignored.
        val bySet = HomeSwitchRisk.Graduated(setOf("My"), 30..30, devices = setOf("shandao"), why = "test")
        val shandao = mztu1.copy(brand = "My", manufacturer = "My", model = "Android TV", device = "shandao", sdk = 30)
        assertTrue(bySet.covers(shandao))
        assertTrue(bySet.covers(shandao.copy(device = "SHANDAO")))
        assertTrue(!bySet.covers(shandao.copy(device = "finch")))
        assertTrue(!bySet.covers(shandao.copy(sdk = 34)))
        assertTrue(!bySet.copy(excludedDevices = setOf("shandao")).covers(shandao), "a set held out stays out")
    }
}
