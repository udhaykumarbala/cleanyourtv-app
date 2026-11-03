package tv.cleanyour.core.ops

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.Snapshot

// The protected denylist (PLAN 6.5 plus SECURITY 8.5), as data. It sits above every profile and no
// mode of 0.1 can override it. `Protected.isProtected` (the frozen entry point) applies the static
// names and the per-package facts; `Denylist.reason` adds the snapshot facts (the active keyboard
// and screensaver) for callers that have a snapshot: the planner at plan time and the executor
// with live facts (SECURITY 3.5, C4). Reasons are plain words for the "Why?" link (D2).
object Denylist {
    // The framework package. PACKAGE_RE needs a dot, so it is spelled out here and the planner and
    // the renderer refuse it whether or not the value class ever admits it (RFC-W1-2 ruling).
    const val FRAMEWORK = "android"

    val exact: Map<String, String> = linkedMapOf(
        FRAMEWORK to "it is the Android framework itself",
        "com.android.systemui" to "it draws the TV's status bar, dialogs and system UI",
        "com.android.settings" to "it is the TV's Settings app",
        "com.android.tv.settings" to "it is the TV's Settings app",
        "com.android.packageinstaller" to "it installs and removes apps",
        "com.google.android.packageinstaller" to "it installs and removes apps",
        "com.android.permissioncontroller" to "it manages app permissions",
        "com.google.android.permissioncontroller" to "it manages app permissions",
        "com.android.providers.settings" to "it stores the TV's settings",
        "com.android.providers.tv" to "it stores the TV's channels and inputs",
        "com.android.providers.media" to "it manages the TV's storage",
        "com.android.providers.media.module" to "it manages the TV's storage",
        "com.google.android.providers.media.module" to "it manages the TV's storage",
        "com.android.shell" to "it is the shell this app talks to",
        "com.android.adbd" to "it is the debugging service this app talks to",
        "com.android.keychain" to "it keeps the TV's certificates",
        "com.android.se" to "it is a secure element service",
        "com.android.location.fused" to "it is a core location service",
        "com.android.bluetooth" to "it pairs and talks to the remote",
        "com.google.android.tv.btservices" to "it pairs and talks to the remote",
        "com.droidlogic.ble" to "it pairs and talks to the remote",
        "com.droidlogic" to "it is a core service of this TV's chipset",
        "com.droidlogic.tvinput" to "it provides the TV's inputs",
        "com.droidlogic.tv.settings" to "it is the TV's Settings app",
        "com.droidlogic.overlay" to "it is a core service of this TV's chipset",
        "com.google.android.gms" to "it is Google Play services",
        "com.google.android.gsf" to "it is Google Play services",
        "com.android.vending" to "it is the Play Store",
        // Upstream v2.7.0 lists switch these off by default (owner decision for 0.3.7, refuse more):
        // what accessibility, the remote and the TV stack need, never switched off by any profile.
        "com.google.android.marvin.talkback" to "it reads the screen aloud for people who need it",
        "com.google.android.tts" to "it is the TV's text-to-speech voice, which TalkBack reads with",
        "com.sony.dtv.accessibility.tutorial" to "it is part of the TV's accessibility settings",
        "com.tcl.hearaid" to "it is the TV's hearing aid sound setting",
        "com.google.android.tv.remote.service" to "it runs the phone remote and the remote's microphone",
        "com.google.android.tv.remotecontrol.logging" to "it is part of the remote control service",
        "com.sony.dtv.seeds.iot.remotestart" to "it turns the TV on from a phone or smart speaker",
        "com.heytap.tv.remotediagnosing" to "it is part of the remote control service",
        "mitv.service" to "it runs the Xiaomi TV's remote keys and TV services; Settings can stop working without it",
        "com.tcl.channelplus" to "it provides TCL Live's channels, a TV input",
        // Issue #1378 (Skyworth SWTV-22AE-FHD): the remote's keys on the Skyworth platform (Skyworth,
        // Sharp, Philips and Thomson sets). Every list that names the global key service keeps it on,
        // and every list but the 22AE one keeps the quick menu on, the receiver of the remote's global
        // buttons; on the 22AE the custom (Daily) button needs it.
        "com.smartdevice.global.service" to "it runs the remote's hot keys on this TV",
        "com.smartdevice.dailyshortcut" to "it runs the remote's quick menu and extra buttons on this TV",
        // 0.4.9: upstream's core guard (tools/disable.sh CORE_RE at d7b0d4a5), which refuses a whole list
        // that names one of these: without them a TV may not start again (#884, #1578, #1711, #2557).
        "com.android.sdksandbox" to "it is a core part of Android; a TV may not start again without it",
        "android.ext.services" to "it is a core part of Android; a TV may not start again without it",
        "android.ext.shared" to "it is a core part of Android; a TV may not start again without it",
        "com.android.ext.services" to "it is a core part of Android; a TV may not start again without it",
        "com.google.android.ext.services" to "it is a core part of Android; a TV may not start again without it",
        "com.android.ext.shared" to "it is a core part of Android; a TV may not start again without it",
        "com.google.android.ext.shared" to "it is a core part of Android; a TV may not start again without it",
        "com.android.modulemetadata" to "it describes Android's system modules; a TV may not start again without it",
        "com.google.android.modulemetadata" to "it describes Android's system modules; a TV may not start again without it",
        "com.android.phone" to "it is a core part of Android; a TV may not start again without it",
        // MediaTek TV middleware: a Haier H50H5F restarted in a loop with com.cltv.mal off (#1578).
        "com.cltv.mal" to "it is the TV's own system layer (MediaTek); a TV restarted in a loop without it",
        "com.mediatek.tis" to "it provides the TV's inputs and tuner (MediaTek)",
        "com.mediatek.tv.service" to "it runs the TV's picture, sound and inputs (MediaTek)",
        "com.mediatek.tv.agent" to "it runs the TV's picture, sound and inputs (MediaTek)",
    ) + vendorSetupWizards.associateWith { REASON_VENDOR_WIZARD } + vendorHomes.associateWith { REASON_VENDOR_HOME }

    val prefixes: Map<String, String> = linkedMapOf(
        "com.android.networkstack" to "it keeps the TV's network running",
        "com.android.wifi." to "it keeps the TV's Wi-Fi running",
        "com.android.providers.media." to "it manages the TV's storage",
        // 0.4.9, upstream's core guard: Google's system module overlays and every Android data
        // provider but the four in providerExceptions.
        "com.google.android.overlay.modules" to "it is part of Android's system modules; a TV may not start again without it",
        "com.android.providers." to "it keeps data the TV's system needs (downloads, settings, media and more)",
    )

    // Upstream's CORE_OK: data providers that tested lists switch off and no TV feature uses. They
    // are not protected by the providers prefix (a list can still keep them on).
    val providerExceptions: Set<String> = setOf(
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.providers.userdictionary",
        "com.android.providers.blockednumber",
    )

    // The first-run wizard: protected unless the profile names it with the takeover quirk, and
    // even then only the home screen switch may switch it off (the planner enforces the where).
    val setupWizards: Set<String> = setOf("com.google.android.tungsten.setupwraith")

    // Vendor first-run wizards and vendor home screens (upstream v2.7.0 lists switch them off): never
    // switched off, not even by the home screen switch, which knows only the Google wizard.
    val vendorSetupWizards: Set<String>
        get() = setOf("com.smartdevice.setupwraith", "com.oneplus.oobe.tv", "com.xiaomi.setupwizard", "com.android.managedprovisioning")
    val vendorHomes: Set<String>
        get() = setOf("com.haier.launcher.haiermatrix", "com.oneplus.tv.launcher")

    const val REASON_SETUP_WIZARD = "the first-run setup wizard holds on to the Home button on this TV"
    const val REASON_PERSISTENT = "the TV keeps it running at all times"
    const val REASON_TV_INPUT = "it provides a TV input (HDMI, tuner or CEC)"
    const val REASON_PROFILE = "this TV's profile says never switch it off"
    const val REASON_KEYBOARD = "it is the TV's keyboard"
    const val REASON_SCREENSAVER = "it is the TV's screensaver"
    const val REASON_VENDOR_WIZARD = "it is part of the TV's first-run setup"
    const val REASON_VENDOR_HOME = "it is this TV's own home screen"

    fun staticReason(pkg: String): String? {
        exact[pkg]?.let { return it }
        if (pkg in providerExceptions) return null
        for ((prefix, reason) in prefixes) if (pkg.startsWith(prefix)) return reason
        return null
    }

    // The full check for a caller with a snapshot: the frozen check plus the keyboard and the
    // screensaver read from the TV.
    fun reason(pkg: PackageName, state: PackageState?, profile: Profile?, snapshot: Snapshot?): String? {
        Protected.isProtected(pkg, state, profile)?.let { return it }
        if (snapshot == null) return null
        if (snapshot.ime?.pkg == pkg) return REASON_KEYBOARD
        if (snapshot.screensaver.any { it.pkg == pkg }) return REASON_SCREENSAVER
        return null
    }

    fun reason(pkg: PackageName, snapshot: Snapshot, profile: Profile?): String? =
        reason(pkg, snapshot.packages[pkg], profile, snapshot)

    fun isWizardAllowed(pkg: PackageName, profile: Profile?): Boolean =
        profile != null && profile.setupWraith == pkg && Quirk.HOME_TAKEOVER_SETUPWRAITH in profile.quirks

    fun isStockLauncher(pkg: PackageName, stockLaunchers: List<Component>): Boolean =
        stockLaunchers.any { it.pkg == pkg }
}
