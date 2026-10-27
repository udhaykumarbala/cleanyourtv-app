package tv.cleanyour.testing.fixtures

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason

// The TV of issue #114 (also #45 and #390), as plain data from what its problem reports say (nothing
// here came from a TV): a TCL Smart TV Pro, device BeyondTV4, Android 11 (SDK 30), build
// TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR11, Google Play services, "Home screen it came with:
// Google TV". Home resolves to Google TV's home screen (launcherx, priority 2); the setup wizard's
// RecoveryActivity (1) and Settings' FallbackHome (-1000) also claim it. 0.3.4 to 0.3.7 matched the
// Android TV list tcl-65c725 by model, device, brand and SDK, and switched off its clean-up there.
// The log of #114 says "setup wizard and saved Wi-Fi: wizard_owns".
object Issue114 {
    val launcherx = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")
    val recovery = Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity")
    val fallbackHome = Component(PackageName("com.android.tv.settings"), ".system.FallbackHome")
    val wizard: PackageName get() = recovery.pkg

    const val AR11 = "TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR11:user/release-keys"
    // The firmware tcl-g03-google-tv was tested on (its fingerprint prefix).
    const val AR09 = "TCL/G03_4K_GB/BeyondTV4:11/RP1A.200622.001/AR09:user/release-keys"

    // What the tcl-65c725 clean-up switched off here that the Google TV list for this set (tcl-g03-
    // google-tv) leaves on: the remote's virtual keys and the user agreement ("left alone as unclear").
    val keptByTheGoogleTvList: List<PackageName> = listOf(PackageName("com.tcl.keyhelp"), PackageName("com.tcl.useragreement"))

    private fun system(enabled: EnabledState = EnabledState.ENABLED, label: String? = null) =
        PackageState(enabled, system = true, versionCode = 1, signer = null, persistent = false, tvInput = false, label = label, installedByUs = false)

    // "Brand: TCL, Manufacturer: TCL, Model: Smart TV Pro, Device: BeyondTV4, Android: 11 (SDK 30)".
    fun facts(fingerprint: String = AR11): DeviceFacts = Samples.miTvFacts().copy(
        brand = "TCL", manufacturer = "TCL", model = "Smart TV Pro", device = "BeyondTV4", sdk = 30, release = "11",
        fingerprint = fingerprint, hasGms = true,
    )

    // The set's apps as far as the two lists for it name them (tcl-65c725 and tcl-g03-google-tv): what
    // they switch off, what they keep, and the system parts.
    val apps: List<String> = listOf(
        "android", "com.android.systemui", "com.android.tv.settings", "com.android.vending", "com.android.providers.tv",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.katniss", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.apps.tv.dreamx",
        "com.tcl.tv", "com.tcl.tvinput", "com.tcl.suspension", "com.tcl.settings", "com.tcl.system.server", "com.tcl.providers.config",
        "com.tcl.initsetup", "com.tcl.factory.view", "com.tcl.tcl_bt_rcu_service", "com.tcl.autopair", "com.tcl.inputmethod.international",
        "com.tcl.bi", "com.tcl.dashboard", "com.tcl.esticker", "com.tcl.partnercustomizer", "com.tcl.useragreement", "com.tcl.messagebox",
        "com.tcl.overseasappshow", "com.tcl.android.webview", "android.autoinstalls.config.tcl.device", "android.autoinstalls.config.google.gtvpai",
        "com.google.android.tvrecommendations", "com.google.android.partnersetup", "com.google.android.feedback",
        "com.google.android.onetimeinitializer", "com.tcl.guard", "com.tcl.keyhelp", "com.tcl.tv.tclhome_passive",
        "com.google.android.syncadapters.calendar", "com.tcl.assistant", "com.tcl.versionUpdateApp",
    )

    val home: HomeResolution = HomeResolution(launcherx, listOf(HomeClaimant(launcherx, 2), HomeClaimant(recovery, 1), HomeClaimant(fallbackHome, -1000)))

    fun snapshot(reason: SnapshotReason = SnapshotReason.BASELINE, fingerprint: String = AR11): Snapshot = Samples.snapshot(
        facts = facts(fingerprint), reason = reason,
        packages = apps.associate { PackageName(it) to system() } + mapOf(
            launcherx.pkg to system(label = "Google TV Home"),
            recovery.pkg to system(label = "Setup"),
        ),
        home = home,
    )
}
