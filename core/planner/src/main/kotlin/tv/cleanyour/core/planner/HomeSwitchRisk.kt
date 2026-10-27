package tv.cleanyour.core.planner

import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
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
    }

    // Google's own Google TV hardware, by maker and device: Chromecast with Google TV 4K (sabrina)
    // and HD (boreal), the Google TV Streamer (kirkwood). The profiles for sabrina and kirkwood match
    // the same keys; boreal is where upstream first saw the casting break.
    val GOOGLE_MAKER = "google"
    val GOOGLE_DEVICES: Set<String> = setOf("sabrina", "boreal", "kirkwood")
    val CASTING_PROFILES: Set<String> = setOf("google-chromecast-gtv-4k", "google-chromecast-gtv-hd", "google-tv-streamer-4k-kirkwood")

    // Sets of the MOKA family from TV reports, with no list of their own. The ACER R4G (issues #74
    // and #102): device R4_GTV, manufacturer MOKA, the same family as R3_GTV and R10G.
    val MOKA_REPORTED: Set<Pair<String, String>> = setOf("R4_GTV" to "MOKA")

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
    fun of(facts: DeviceFacts, profile: Profile?, snapshot: Snapshot, wizardWifi: WizardWifi, anyBrand: Boolean = anyBrandNow): Risk? = when {
        isMokaFamily(facts) || profile?.id in MOKA_PROFILES -> Risk.BOOT_LOOP
        isGoogleHardware(facts) || profile?.id in CASTING_PROFILES -> Risk.CASTING
        profile?.id in REMOTE_BUTTON_PROFILES -> Risk.REMOTE_BUTTONS
        // Test build: a candidate list was never tried with the switch, so for the setup wizard it
        // counts as no profile at all (a wizard that is on stays on, so no switch). 0.4.6: in a
        // release R0 is for a TV no list knows at all. In a test build with R0 widened to every brand
        // (anyBrand) it applies to a candidate list too, since the volunteers who try one are the
        // ones checking R0 on their brand (issue #258, a OnePlus C00 on its candidate list, was
        // blocked WIZARD_WIFI there); every other R0 condition holds, and an exact list keeps its own rule.
        else -> wizardWifiRisk(profile?.takeUnless { it.isCandidate }, snapshot, wizardWifi,
            r0 = (profile == null || (anyBrand && profile.isCandidate)) && wizardStaysOnSafely(facts, snapshot, anyBrand))
    }

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
    // other claimant a placeholder (the chooser, a FallbackHome) or our own home screen; and Home
    // answering to one of those, or to nothing. Google TV
    // builds (launcherx), China PatchWall (com.mitv.tvhome) and any vendor home fail the last test.
    // The wizard is never switched off on this path and the Wi-Fi is never touched, so the Wi-Fi
    // answer does not matter. Manage checks the claimants again on every connect (WizardHome).
    // `anyBrand` (a test build, ANY_BRAND_BUILD) drops the brand test and keeps every other one.
    fun wizardStaysOnSafely(facts: DeviceFacts, snapshot: Snapshot, anyBrand: Boolean = anyBrandNow): Boolean {
        if (!anyBrand && !isXiaomi(facts)) return false
        if (homeWizards(null, snapshot).isNotEmpty()) return false
        val claimants = snapshot.home.claimants.map { it.component }
        fun on(p: PackageName) = snapshot.packages[p]?.enabled.let { it != EnabledState.DISABLED_USER && it != EnabledState.DISABLED }
        val tvlauncher = KnownLaunchers.TVLAUNCHER.pkg
        val launcherState = snapshot.packages[tvlauncher]?.enabled
        if (claimants.none { it.pkg == tvlauncher } || launcherState == null || !on(tvlauncher)) return false
        // What Home answers now must be one of the same: nothing, tvlauncher, ours or a placeholder
        // (a TV whose Home answers to another app the claimants do not list is not this case).
        val resolved = snapshot.home.resolved
        if (resolved != null && resolved.pkg != tvlauncher && resolved.pkg != SwitchOffRule.OUR_LAUNCHER && !HomePlaceholder.isPlaceholder(resolved)) return false
        return claimants.all { c ->
            c.pkg == tvlauncher || c.pkg == SwitchOffRule.OUR_LAUNCHER || (HomePlaceholder.isPlaceholder(c) && !(isWizard(c.pkg) && on(c.pkg)))
        }
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
