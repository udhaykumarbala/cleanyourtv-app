package tv.cleanyour.core.planner

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.spec.KnownLaunchers

// RF-SAFETY-250, owner decisions for 0.2.0: TVs where the home screen switch is skipped up front, so
// Setup is the clean-up alone (like RF-PROFILES-63 P1 and P2). SetupMachine asks before it plans and
// S3 says why; the planner refuses a switch asked for anyway. Not a frozen interface.
object HomeSwitchRisk {
    enum class Risk(val reason: String) {
        // The switch needs the setup wizard off, the Wi-Fi guard (D3) keeps it on, and with the wizard
        // on and the TV's own home screen off its RecoveryActivity takes Home and the system can
        // crash-loop (upstream #506, #422). OWNER: the wizard saved the Wi-Fi; UNKNOWN: the TV could not tell.
        WIZARD_WIFI("this TV's setup wizard saved its Wi-Fi, so the wizard has to stay on, and with it on the home screen switch can crash the TV"),
        WIZARD_WIFI_UNKNOWN("the TV could not say who saved its Wi-Fi, so its setup wizard has to stay on, and with it on the home screen switch can crash the TV"),
        // A TV no profile knows: the switch never switches the setup wizard off, so it would stay on
        // while the TV's own home screen goes off, the same RecoveryActivity risk (RF-SAFETY-250).
        // Since 0.3.6 also any TV whose wizard claims Home and is not switched off by the switch.
        // 0.4.6: not on a Xiaomi with Google's Android TV home whose wizard is no Home claimant (R0).
        WIZARD_STAYS_ON("this TV's setup wizard would stay on while its own home screen goes off, and the wizard can then take the Home button and crash the TV"),
        // Google's own hardware: the Cast receiver reads its credentials from the setup wizard, so the
        // switch (which needs the wizard off) costs casting after every restart (upstream #361, #422).
        CASTING("on this Google device the home screen switch needs the setup wizard off, and casting from phones then stops working after every restart"),
        // The MOKA family (kalley-43g200): the firmware turns the wizard back on at every boot, and with
        // the TV's own home screen off it boot-looped a set that needed a USB reflash.
        BOOT_LOOP("on this TV family the setup wizard comes back at every restart, and with the TV's own home screen off the TV can fail to start"),
        // 0.4.5, sony-bravia-vu3 (upstream PR #1757): Realtek's GlobalKeyReceiver hands the remote's Netflix,
        // YouTube, Prime Video and YouTube Music buttons to launcherx, so with it off they do nothing.
        // Upstream's own install keeps launcherx on with a key helper, and this app never switches an
        // accessibility service on, so there is no switch.
        REMOTE_BUTTONS("on this TV the remote's app buttons only work through its own home screen, so with it switched off they stop working"),
        // beta3: R0 would let the switch go ahead with the wizard left on, but another home screen app
        // claims Home (one the person installed that R0 cannot take, or a vendor home), so it does not.
        OTHER_HOME("another home screen app claims the Home button on this TV, so the switch is left alone while its setup wizard stays on"),
        // beta4 (issue #825): a vendor service holds a Home placeholder of its own (Realtek's key
        // service on a Thomson that failed to start after a few restarts), on any path and in any
        // build, whatever the setup wizard does. Nothing the person can remove.
        VENDOR_HOME("a system service on this TV also answers the Home button, so the switch is left alone"),
        // beta4 (issues #945, #1570, PatchWallFeeds): PatchWall is on and stays on while Mi TV+ or its
        // web content is off, and not by this app, so the plan cannot switch it back on.
        PATCHWALL_FEEDS("PatchWall is on while Mi TV+ or its web content is off, and with the TV's own home screen off that can stop the TV starting; switch PatchWall off or those apps back on first"),
    }

    // Google's own Google TV hardware, by maker and device: Chromecast with Google TV 4K (sabrina)
    // and HD (boreal), the Google TV Streamer (kirkwood). The profiles for sabrina and kirkwood match
    // the same keys; boreal is where upstream first saw the casting break.
    val GOOGLE_MAKER = "google"
    val GOOGLE_DEVICES: Set<String> = setOf("sabrina", "boreal", "kirkwood")
    val CASTING_PROFILES: Set<String> = setOf("google-chromecast-gtv-4k", "google-chromecast-gtv-hd", "google-tv-streamer-4k-kirkwood")

    // Sets of the MOKA family from TV reports, with no list of their own. The ACER R4G (issues #74
    // and #102): device R4_GTV, manufacturer MOKA, the same family as R3_GTV and R10G. 0.4.7-beta2: the
    // R3 and R4 boards sold under Panasonic and other brands report the maker SCBC and carry the same
    // firmware (com.moka.tv.overlay.framework, MOKA's com.mk services, Realtek); 18 reports, none with
    // the switch yet, so they get no switch until one holds on a tested list.
    val MOKA_REPORTED: Set<Pair<String, String>> = setOf("R4_GTV" to "MOKA", "R3" to "SCBC", "R4" to "SCBC")

    // The MOKA family by the exact device and maker pairs its three profiles match on (kalley-43g200:
    // R3_GTV by MOKA; prismplus-q65-ultra-r10g: R10G by MOKA; kalley-r10g: R10G under the Kalley
    // brand), whatever the Android version, plus the reported sets of the family no profile matches
    // (MOKA_REPORTED). A device name alone could be another maker's, and the maker alone is too wide:
    // MOKA also builds the Philips R8G (whitefield), which is not this family.
    val MOKA_FAMILY: Set<Pair<String, String>> = setOf("R3_GTV" to "MOKA", "R10G" to "MOKA", "R10G" to "Kalley") + MOKA_REPORTED
    val MOKA_PROFILES: Set<String> = setOf("kalley-43g200", "kalley-r10g", "prismplus-q65-ultra-r10g")

    // The profiles whose remote's app buttons need the TV's own home screen (REMOTE_BUTTONS).
    val REMOTE_BUTTON_PROFILES: Set<String> = setOf("sony-bravia-vu3")

    // 0.4.6 (R0): the makers whose Android TV sets keep their setup wizard on under the new home
    // screen, by brand or manufacturer. Redmi is Xiaomi's own brand: the Redmi MiTV-MOOQ3 (tarzan)
    // reports the same claimants as the Xiaomi Mi TV 4A on that device, and its sets report Xiaomi
    // as the manufacturer (the brand set that matched them is Xiaomi's). The China PatchWall sets of
    // either brand never meet the rest of the rule, their Home is com.mitv.tvhome.
    val XIAOMI_MAKERS: Set<String> = setOf("Xiaomi", "Redmi")

    // 0.4.7: where a release gives R0, as a reviewed table of graduated entries (research/release-0.4.7/
    // R0-GRADUATION.md keeps the candidates and their counts). An entry is a brand or manufacturer (any of `makers`,
    // case ignored), an Android SDK range, optionally only some models (`models`, by ro.product.model), only some
    // sets (`devices`, by ro.product.device, for a brand string too generic to stand alone) and sets held out
    // (`excludedDevices`, by ro.product.device). The rest of R0 (the wizard no Home claimant, tvlauncher on, the
    // placeholders) and every incident block (VENDOR_HOME, OTHER_HOME, PATCHWALL_FEEDS, HELD_OUT, candidates never
    // with R0) apply to every entry. The test build drops the table (ANY_BRAND_BUILD) and keeps the rest.
    //
    // Graduation rule (owner, 2026-10-28; 2026-10-29: 2 for a new Android version too, it was 3): a brand on an
    // Android version needs 2 confirmed testers. A confirmation is the tester's own words that the new home
    // screen was still there after a restart of the TV, on a test build. Each entry cites its confirming issues.
    // 0.4.7 (stable): only confirmations whose switch was R0 count for a row. A restart that held under the Home
    // button helper (a test build's opt-in, "Home path: helper") or on a tested list says nothing about R0.
    data class Graduated(val makers: Set<String>, val sdks: IntRange, val models: Set<String> = emptySet(),
                         val devices: Set<String> = emptySet(), val excludedDevices: Set<String> = emptySet(), val why: String) {
        fun maker(f: DeviceFacts): Boolean = makers.any { it.equals(f.brand, ignoreCase = true) || it.equals(f.manufacturer, ignoreCase = true) }
        fun covers(f: DeviceFacts): Boolean = maker(f) && f.sdk in sdks &&
            (models.isEmpty() || models.any { it.equals(f.model, ignoreCase = true) }) &&
            (devices.isEmpty() || devices.any { it.equals(f.device, ignoreCase = true) }) && excludedDevices.none { it.equals(f.device, ignoreCase = true) }
    }

    val GRADUATED: List<Graduated> = listOf(
        // 0.4.6 (beta4, after the incident of 0.4.6-beta2): Xiaomi and Redmi on Android 9, 10 and 11, the versions
        // the Xiaomi evidence covers: the maintainer's MiTV-AXSO0 on 9 across reboots since 2026-10-20, the MiTV4I and
        // MSSP0 on 9, AESP0 on 10, MOOQ1, MOOQ3 and AYFR0 on 11 (issues #1119, #1279 among them) and upstream's AXFR0
        // and AXFR1 on 11. The MiTV-AXSO2 (dangalUHD) on 11 is held out in every build (HELD_OUT, issue #945).
        Graduated(XIAOMI_MAKERS, 28..30, why = "Xiaomi and Redmi, Android 9 to 11 (0.4.6)"),
        // 0.4.7 rows, from research/release-0.4.7/confirmations.md (2026-10-30), counting the R0 confirmations only.
        // Xiaomi MiTV-MZTU1 (stepup) on Android 14: #1505 (0.4.6-beta3), #868 (0.4.6-beta2), #1877 and #2809
        // (0.4.7-beta3), four testers, all on this model. The other Android 14 Xiaomi confirmations (MiTV-AFKR0,
        // AXFU0, AXTU0, MOOR2, and #2517 on an MZTU1) held under the Home button helper, not R0, so only this model.
        // Android 12 and 13 have no confirmation; the MIBOX4 on Android 12 did not hold (#1273).
        Graduated(XIAOMI_MAKERS, 34..34, models = setOf("MiTV-MZTU1"), why = "Xiaomi MiTV-MZTU1, Android 14 (0.4.7)"),
        // OnePlus on Android 11: the D00 (Dubai) in #235 (0.4.6-beta2) and #2420, the C00 (Cebu) in #2360, #2492 and
        // #2524 (0.4.7-beta3, Home path R0). Five testers, no failure.
        Graduated(setOf("OnePlus"), 30..30, why = "OnePlus, Android 11 (0.4.7)"),
        // "My" on Android 11, model "Android TV": a brand string the board makers print for several shop brands
        // (#864's is a Cecotec), so only the three sets the testers have: yeoksam (#673, 0.4.6-beta2), shandao (#864,
        // 0.4.6-beta2, and #2367, 0.4.7-beta3) and tokyo (#1546, 0.4.7-beta1). Four testers, no failure.
        Graduated(setOf("My"), 30..30, devices = setOf("yeoksam", "shandao", "tokyo"), why = "My Android TV (yeoksam, shandao, tokyo), Android 11 (0.4.7)"),
        // Hisense on Android 9: the HiSmart 4K ATV4 (xinhaoshan) in #1388 (0.4.6-beta2) and #2814 (0.4.7-beta3, Home
        // path R0), two testers on two phones. Its FallbackHome (com.android.boot.fallbackhome) is one R0 takes.
        Graduated(setOf("Hisense"), 28..28, why = "Hisense, Android 9 (0.4.7)"),
        // Homatics on Android 14: the Box R 4K Plus (YYJ, SEI Robotics) in #1094 and #654 (0.4.6-beta2), two testers
        // on two phones. By the brand only: SEI Robotics also builds the TiVo Stream 4K, which did not hold (#832, #1518).
        Graduated(setOf("Homatics"), 34..34, why = "Homatics, Android 14 (0.4.7)"),
        // MECOOL on Android 14: the LEAP-S1 (LAS) in #2396 (0.4.7-beta3, the #1883 tester) and the 4K Android TV Box
        // (YYC) in #2522 (0.4.7-beta4), both Home path R0. By the brand only, not the board maker (SkyworthDigital).
        Graduated(setOf("MECOOL"), 34..34, why = "MECOOL, Android 14 (0.4.7)"),
        // Sony on Android 8: the BRAVIA 4K GB ATV3 (BRAVIA_ATV3_4K) in #1219 (0.4.6-beta2) and #1585 (0.4.7-beta1),
        // two testers on two phones and two firmware builds. Sony's other versions have no row (#1544 on Android 12
        // failed its check); the BRAVIA VU3 keeps REMOTE_BUTTONS through its exact list.
        Graduated(setOf("Sony"), 26..26, why = "Sony, Android 8 (0.4.7)"),
        // 0.4.8 rows, from research/release-0.4.8/sweep-1031/FINDINGS.md (2026-10-31), R0 confirmations only.
        // Xiaomi MiTV-MOEU1 (lalaland) on Android 14: #3099 (0.4.7-beta3) and #3312 (0.4.8-beta1), Home path R0, two
        // testers on two phones and two firmware builds, no failure. Only this model, like the MZTU1 row: the other
        // Android 14 Xiaomi models have no R0 confirmation of their own.
        Graduated(XIAOMI_MAKERS, 34..34, models = setOf("MiTV-MOEU1"), why = "Xiaomi MiTV-MOEU1, Android 14 (0.4.8)"),
        // MI (fengmi) MiProjM05 (eva) projector on Android 9: #1647 (a restart line after the 0.4.7 test build invite)
        // and #2925 (0.4.8-beta1, Home path R0), two testers on two phones, no failure. "MI" is a fengmi (Formovie)
        // brand string, not XIAOMI_MAKERS, so only this model and set.
        Graduated(setOf("MI"), 28..28, models = setOf("MiProjM05"), devices = setOf("eva"), why = "MI MiProjM05 projector, Android 9 (0.4.8)"),
        // Not graduated in 0.4.7 (research/release-0.4.7/confirmations.md, R0-GRADUATION.md): TCL on Android 11 and
        // realme on Android 14 (a tester's home screen did not hold: #1799, #2061, #2082; #2126); Kogan on Android 11
        // (#881, #138: #138 is listed against it in R0-GRADUATION.md, and Changhong boards failed under realme (#2126)
        // and TD Systems (#577) on Android 14); and SHARP on Android 11 and 14, Hisense on Android 11, Strong, THOMSON and
        // google on Android 14, whose count was met with Home button helper confirmations
        // (and, for SHARP on Android 11, #17's own launcher app) but has under 2 for R0.
    )

    // The release's R0 scope: a graduated entry covers the TV.
    fun graduated(f: DeviceFacts): Boolean = GRADUATED.any { it.covers(f) }

    // Held out of R0 in every build, by device and Android version, until the owner of the TV that
    // failed answers: the Xiaomi MiTV-AXSO2 (dangalUHD) on Android 11 (issue #945), whose brand set
    // path has not been seen on Android 11.
    val HELD_OUT: Set<Pair<String, Int>> = setOf("dangalUHD" to 30)
    fun heldOut(f: DeviceFacts): Boolean = HELD_OUT.any { (device, sdk) -> device.equals(f.device, ignoreCase = true) && f.sdk == sdk }

    // Test build only (-Pcyt.wizardStaysAnyBrand=true, core/planner/build.gradle.kts): R0 without its
    // brand test. 233 more reports from other brands (Sony, OnePlus, Philips, Mecool, Skyworth,
    // Hisense, Realme, Haier, TCL and others) show the same claimants with the wizard on; a volunteer
    // per brand checks reboot survival before a release takes them. The release bundles no marker.
    const val ANY_BRAND_RESOURCE = "/tv/cleanyour/core/planner/wizard-stays-any-brand"
    val ANY_BRAND_BUILD: Boolean by lazy { HomeSwitchRisk::class.java.getResource(ANY_BRAND_RESOURCE) != null }

    // Tests only: the rule a test is written for, whatever this build bundles (null: the build's).
    // A test of the release rule sets false (PlannerFixtures.releaseRule), so the suite passes in a
    // test build too.
    @Volatile var anyBrandForTests: Boolean? = null
    private val anyBrandNow: Boolean get() = anyBrandForTests ?: ANY_BRAND_BUILD

    // The first risk that applies, or null when the switch may be planned. `profile` is the exact
    // profile (null on a brand sure set or the generic path); `wizardWifi` the D3 answer.
    // `anyBrand`: R0 for every brand (the test build's switch, ANY_BRAND_BUILD); tests pass it.
    // beta4: `journals`, this TV's change records, and `patchWallGoesOff`, whether the plan switches
    // PatchWall off (PatchWallFeeds); the defaults are a TV with no records and PatchWall kept.
    fun of(facts: DeviceFacts, profile: Profile?, snapshot: Snapshot, wizardWifi: WizardWifi, anyBrand: Boolean = anyBrandNow,
           journals: List<ChangeRecord> = emptyList(), patchWallGoesOff: Boolean = false): Risk? = when {
        isMokaFamily(facts) || profile?.id in MOKA_PROFILES -> Risk.BOOT_LOOP
        isGoogleHardware(facts) || profile?.id in CASTING_PROFILES -> Risk.CASTING
        profile?.id in REMOTE_BUTTON_PROFILES -> Risk.REMOTE_BUTTONS
        // beta4: a FallbackHome R0 does not take (r0Placeholder) holds the switch back on every path.
        vendorPlaceholderClaims(snapshot) -> Risk.VENDOR_HOME
        PatchWallFeeds.stayingOff(snapshot, journals, patchWallGoesOff).isNotEmpty() -> Risk.PATCHWALL_FEEDS
        // Test build: a candidate list was never tried with the switch, so for the setup wizard it
        // counts as no profile at all (a wizard that is on stays on, so no switch). 0.4.6: R0 is for a
        // TV no list knows at all. beta4 (incident of 0.4.6-beta2, issues #945 and #1570): not for a
        // candidate list either, in any build. beta3's test build gave one R0 (issue #258), and a
        // Xiaomi MiTV-AXSO2 on Android 11 on its candidate list stopped starting after the switch.
        // A candidate keeps the wizard's rule until each has been checked over several restarts.
        // beta3: where R0 applies and only another home screen app stands in its way (one the person
        // installed that R0 cannot take, or a vendor home next to tvlauncher), that is the reason,
        // not the wizard's (OTHER_HOME): without that app the wizard could stay on.
        else -> {
            val r0Applies = profile == null
            val blocker = if (r0Applies) r0Blocker(facts, snapshot, anyBrand) else R0Blocker.NOT_THIS_PATH
            val risk = wizardWifiRisk(profile?.takeUnless { it.isCandidate }, snapshot, wizardWifi, r0 = blocker == null)
            if (risk != null && blocker == R0Blocker.OTHER_HOME) Risk.OTHER_HOME else risk
        }
    }

    // beta3: what keeps a TV from R0, the first that applies (null: R0 lets it switch).
    // beta4: VENDOR_PLACEHOLDER, a FallbackHome claimant R0 does not take (r0Placeholder); SCOPE, an
    // Android version a release does not give R0 (GRADUATED since 0.4.7) or a set held out (HELD_OUT).
    enum class R0Blocker { NOT_THIS_PATH, BRAND, SCOPE, WIZARD_CLAIMS_HOME, NO_TVLAUNCHER, VENDOR_PLACEHOLDER, OTHER_HOME }

    // beta4, incident of 0.4.6-beta2 (issue #825): a Thomson SPPL 2K (sauve, Android 11, Realtek) with
    // claimants tvlauncher (2), com.realtek.keyintercept/.fallbackhome.FallbackHome (-999) and Settings'
    // FallbackHome (-1000) took R0 in the test build, ran fine, then after a few restarts reset its
    // settings and offered a factory reset. That FallbackHome lives in Realtek's key service, a family
    // upstream has seen crash on every key press once the app it is wired to is off. So for R0 a
    // FallbackHome counts as harmless only when it is Android's own (TV Settings' or Settings') or in a
    // package a TV that held under R0 or upstream's checked set had: com.apps.tvsettings (a Nokia R4,
    // issue #512), com.android.boot.fallbackhome (TCL, issue #481, and a Hisense, #1388),
    // com.realtek.systemservice (upstream's Thomson sauve on Android 14). Any other (keyintercept,
    // com.realtek.menu, a vendor's own) blocks R0. HomePlaceholder.isPlaceholder stays as it is for
    // its other callers (Setup's card, Done, the check after the switch), which only ask whether a
    // HOME answer is a home screen.
    val AOSP_FALLBACK_HOMES: Set<Component> = setOf(
        Component(PackageName("com.android.tv.settings"), ".system.FallbackHome"),
        Component(PackageName("com.android.settings"), ".FallbackHome"),
    )
    val VENDOR_PLACEHOLDERS: Set<PackageName> =
        setOf("com.apps.tvsettings", "com.android.boot.fallbackhome", "com.realtek.systemservice").map { PackageName(it) }.toSet()

    // A Home claimant R0 takes as a placeholder: the chooser, a setup wizard (one that is on was
    // refused before this is asked), Android's own FallbackHome, or one in a package of the list
    // above. A class written out in full (the package's name in front) is the same class.
    // Issue #1944: a factory test app is a placeholder to every other caller, but not one R0 takes:
    // it answers Home at priority 0, as ours does, so for R0 it stays another home screen, as before.
    fun r0Placeholder(c: Component): Boolean {
        if (!HomePlaceholder.isPlaceholder(c) || HomePlaceholder.isFactoryHome(c.pkg)) return false
        if ("FallbackHome" !in c.cls) return true
        val short = if (c.cls.startsWith(c.pkg.value + ".")) c.cls.removePrefix(c.pkg.value) else c.cls
        return Component(c.pkg, short) in AOSP_FALLBACK_HOMES || c.pkg in VENDOR_PLACEHOLDERS
    }

    // A FallbackHome R0 does not take: the reason it gives (VENDOR_PLACEHOLDER, Risk.VENDOR_HOME).
    private fun vendorPlaceholder(c: Component): Boolean = HomePlaceholder.isPlaceholder(c) && "FallbackHome" in c.cls && !r0Placeholder(c)

    // Such a FallbackHome among the claimants or answering Home.
    fun vendorPlaceholderClaims(snapshot: Snapshot): Boolean =
        snapshot.home.claimants.any { vendorPlaceholder(it.component) } || snapshot.home.resolved?.let { vendorPlaceholder(it) } == true

    fun isXiaomi(f: DeviceFacts): Boolean = XIAOMI_MAKERS.any { it.equals(f.brand, ignoreCase = true) || it.equals(f.manufacturer, ignoreCase = true) }

    // 0.4.6 (R0): the switch may go ahead with the setup wizard left on. What WIZARD_STAYS_ON guards
    // against is the wizard's RecoveryActivity taking Home once the TV's own home screen is off, and
    // for that the wizard has to be a Home claimant. 171 of 236 Xiaomi reports are global firmware
    // whose only claimants are tvlauncher (priority 2) and Settings' FallbackHome (-1000); on the
    // maintainer's MiTV-AXSO0 (Android 9) with tvlauncher off, our home screen on and setupwraith on,
    // Home stayed ours across reboots since 2026-10-20 and the wizard never became a claimant
    // (upstream notes say the same of AYFR0, AXFR0/1, MOOQ2 and Mi Box S). So, all of: a Xiaomi by
    // brand or maker; no setup wizard among the claimants (homeWizards, and no vendor wizard or
    // com.android.provision either) that is on; Google's Android TV home among them and on; every
    // other claimant a placeholder (the chooser, a FallbackHome R0 takes, r0Placeholder) or our own home screen; and Home
    // answering to one of those, or to nothing. Google TV
    // builds (launcherx), China PatchWall (com.mitv.tvhome) and any vendor home fail the last test.
    // The wizard is never switched off on this path and the Wi-Fi is never touched, so the Wi-Fi
    // answer does not matter. Manage checks the claimants again on every connect (WizardHome).
    // `anyBrand` (a test build, ANY_BRAND_BUILD) drops the brand test and the Android versions of a
    // release (the GRADUATED table, 0.4.7) and keeps every other one, the sets held out (HELD_OUT) among them.
    // beta3 (issue #1119, a MiTV4I with a home screen app the person installed): a claimant may also
    // be an app the person installed (installedHome). Android caps the HOME priority of an app that
    // is not a system app at 0, so with tvlauncher off it ties with ours and the set-home preference
    // decides; on the maintainer's Mi TV, set-home-activity made ours win with such an app present,
    // across a reboot and after a boot into Live TV. The switch leaves that app installed and on, and
    // VerifyHome and the focus check roll back if it takes Home anyway (the chooser is not ours either).
    fun wizardStaysOnSafely(facts: DeviceFacts, snapshot: Snapshot, anyBrand: Boolean = anyBrandNow): Boolean =
        r0Blocker(facts, snapshot, anyBrand) == null

    fun r0Blocker(facts: DeviceFacts, snapshot: Snapshot, anyBrand: Boolean = anyBrandNow): R0Blocker? {
        // 0.4.7: BRAND, no graduated entry names the TV's maker; SCOPE, one does but not this Android version,
        // model or set, or the set is held out (in every build).
        if (!anyBrand && GRADUATED.none { it.maker(facts) }) return R0Blocker.BRAND
        if ((!anyBrand && !graduated(facts)) || heldOut(facts)) return R0Blocker.SCOPE
        fun on(p: PackageName) = snapshot.packages[p]?.enabled.let { it != EnabledState.DISABLED_USER && it != EnabledState.DISABLED }
        val claimants = snapshot.home.claimants.map { it.component }
        // A setup wizard among the claimants that is on, by any name (a placeholder that is a wizard too).
        if (homeWizards(null, snapshot).isNotEmpty() || claimants.any { HomePlaceholder.isPlaceholder(it) && isWizard(it.pkg) && on(it.pkg) })
            return R0Blocker.WIZARD_CLAIMS_HOME
        val tvlauncher = KnownLaunchers.TVLAUNCHER.pkg
        val launcherState = snapshot.packages[tvlauncher]?.enabled
        if (claimants.none { it.pkg == tvlauncher } || launcherState == null || !on(tvlauncher)) return R0Blocker.NO_TVLAUNCHER
        // What Home answers now must be one of the same: nothing, tvlauncher, ours or a placeholder (a
        // TV whose Home answers to another app is not this case). An app the person installed counts
        // only among the claimants: one Home already answers to is their choice, which stays.
        // beta4: a FallbackHome R0 does not take (r0Placeholder), among the claimants or answering Home,
        // is its own reason, before any other home screen app.
        val resolved = snapshot.home.resolved
        if (vendorPlaceholderClaims(snapshot)) return R0Blocker.VENDOR_PLACEHOLDER
        if (resolved != null && resolved.pkg != tvlauncher && resolved.pkg != SwitchOffRule.OUR_LAUNCHER && !r0Placeholder(resolved))
            return R0Blocker.OTHER_HOME
        val accepted = snapshot.home.claimants.all { claimant ->
            val c = claimant.component
            c.pkg == tvlauncher || c.pkg == SwitchOffRule.OUR_LAUNCHER || r0Placeholder(c) ||
                (installedHome(c.pkg, snapshot) && claimant.priority <= 0)
        }
        return if (accepted) null else R0Blocker.OTHER_HOME
    }

    // beta3: a home screen app the person installed, by the snapshot's own package info: listed, not
    // a system app, not a wizard or a vendor home by name. One the snapshot does not list, or lists
    // as a system app, is not (a vendor home ships as one). The probe falls back to the install path
    // when it could not read a package's flags, and to "not a system app" when it read neither; a
    // version code of 0 is that case (its details were not read), so it does not count either. Nor
    // does an app with an accessibility service in the TV's list: it can catch the Home key itself.
    fun installedHome(p: PackageName, snapshot: Snapshot): Boolean {
        val state = snapshot.packages[p] ?: return false
        val accessibility = snapshot.settings[SettingKey.ENABLED_ACCESSIBILITY].orEmpty().split(':').map { it.trim().substringBefore('/') }
        return !state.system && state.versionCode > 0 && !isWizard(p) && p.value !in Denylist.vendorHomes && p !in KnownLaunchers.byPackage &&
            p.value !in accessibility
    }

    // Any first-run wizard by name: Google's, a vendor's, AOSP's provision, or one named like setupwraith.
    private fun isWizard(p: PackageName): Boolean =
        p.value in Denylist.setupWizards || p.value in Denylist.vendorSetupWizards || p.value == "com.android.provision" || "setupwraith" in p.value.lowercase()

    fun isMokaFamily(f: DeviceFacts): Boolean =
        MOKA_FAMILY.any { (device, maker) -> device.equals(f.device, ignoreCase = true) && (maker.equals(f.brand, ignoreCase = true) || maker.equals(f.manufacturer, ignoreCase = true)) }

    fun isGoogleHardware(f: DeviceFacts): Boolean =
        GOOGLE_DEVICES.any { it.equals(f.device, ignoreCase = true) } && (GOOGLE_MAKER.equals(f.brand, ignoreCase = true) || GOOGLE_MAKER.equals(f.manufacturer, ignoreCase = true))

    // With an exact profile, the wizard the switch would switch off (its setupWraith with the takeover
    // quirk) while D3 keeps it on: the TV said the wizard saved the Wi-Fi, or could not tell. Without
    // one the switch never touches the wizard, so a setup wizard that is on stays on while the stock
    // home goes off, whatever the Wi-Fi answer: no switch (the Wi-Fi line when the wizard saved it).
    // 0.3.6: on every path, a setup wizard the snapshot shows as a Home claimant that the switch will
    // not switch off (an exact profile without the takeover quirk, sony-bravia-bf1 and
    // tcl-g10-google-tv among them) would take Home once the TV's own home screen is off: no switch.
    // 0.4.6: `r0`, the TV meets wizardStaysOnSafely, so a wizard that is on but claims no Home stays
    // on with no risk.
    private fun wizardWifiRisk(profile: Profile?, snapshot: Snapshot, wizardWifi: WizardWifi, r0: Boolean = false): Risk? {
        fun on(p: PackageName) = snapshot.packages[p]?.enabled.let { it != null && it != EnabledState.DISABLED_USER && it != EnabledState.DISABLED }
        val homeWizards = homeWizards(profile, snapshot)
        if (profile == null) {
            if (r0) return null
            val wizard = snapshot.packages.keys.firstOrNull { it.value in Denylist.setupWizards && on(it) }
                ?: return if (homeWizards.isEmpty()) null else Risk.WIZARD_STAYS_ON
            return if (wizardWifi == WizardWifi.OWNER && on(wizard)) Risk.WIZARD_WIFI else Risk.WIZARD_STAYS_ON
        }
        val allowed = profile.setupWraith?.takeIf { Denylist.isWizardAllowed(it, profile) }
        if (wizardWifi != WizardWifi.NOT_OWNER && allowed != null && on(allowed)) {
            return if (wizardWifi == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_WIFI_UNKNOWN
        }
        // The wizard the planner's takeover switches off, by the same test (DefaultPlanner.takeover).
        val switchedOff = allowed?.takeIf {
            wizardWifi == WizardWifi.NOT_OWNER && it in snapshot.packages &&
                SwitchOffRule.reason(it, snapshot.packages[it], profile, snapshot, profile.stockLaunchers, inHomeSwitch = true) == null
        }
        return if (homeWizards.any { it != switchedOff }) Risk.WIZARD_STAYS_ON else null
    }

    // The Home claimants that are a setup wizard: the known wizards, the profile's own, or a package
    // named like Google TV's (as SetupMachine tells a wizard among the claimants). One the snapshot
    // shows switched off cannot take Home; one it does not list counts, the safe side.
    fun homeWizards(profile: Profile?, snapshot: Snapshot): Set<PackageName> =
        snapshot.home.claimants.map { it.component.pkg }.filter {
            it.value in Denylist.setupWizards || it == profile?.setupWraith || "setupwraith" in it.value.lowercase()
        }.filter { snapshot.packages[it]?.enabled.let { e -> e != EnabledState.DISABLED_USER && e != EnabledState.DISABLED } }.toSet()
}
