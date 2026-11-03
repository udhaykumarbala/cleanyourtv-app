package tv.cleanyour.core.planner

import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot

// Firmware that refuses another home screen, told from the TV's own facts so that an unlisted set
// is caught as surely as one with an exact profile (issue #2: an Insignia Fire TV no profile knew
// resolved to the Amazon sure set, a takeover was planned and Fire OS refused it half way).
// Not a frozen interface.
object FireOs {
    // Fire OS's own home screens. Both are protected packages on Fire OS 7 and 8 (pm disable-user and
    // pm enable answer "Cannot disable a protected package"), their HOME filter has priority 950 and
    // set-home-activity is ignored (upstream examples/insignia-ns55f301na25 and
    // amazon-fire-tv-stick-4k-2023). They are never switched off, in or out of the home screen switch.
    val HOMES: Set<PackageName> = setOf(PackageName("com.amazon.tv.launcher"), PackageName("com.amazon.firehomestarter"))
    const val MAKER = "amazon"
    const val MODEL_PREFIX = "AFT"

    fun isHome(pkg: PackageName): Boolean = pkg in HOMES

    // Amazon as the maker or brand (Fire TV Edition sets from Insignia, Toshiba or Pioneer report
    // Amazon as one of them), an Amazon model code (AFTKM, AFTDCT31), or one of Fire OS's home
    // screens installed or answering Home.
    fun isFireOs(facts: DeviceFacts, snapshot: Snapshot?): Boolean {
        if (MAKER.equals(facts.manufacturer.trim(), ignoreCase = true) || MAKER.equals(facts.brand.trim(), ignoreCase = true)) return true
        if (facts.model.trim().startsWith(MODEL_PREFIX, ignoreCase = true)) return true
        val s = snapshot ?: return false
        if (HOMES.any { it in s.packages }) return true
        return s.home.resolved?.pkg?.let(::isHome) == true || s.home.claimants.any { isHome(it.component.pkg) }
    }
}

// China-market TCL and FFalcon sets (the TCL_CN profiles: ak30a5, tcl_mt5879_cn): no Google
// services at all and a firmware that takes no other launcher. The international TCL Android and
// Google TV sets always carry Google services, so the pair of facts names the China firmware only.
object TclChina {
    val BRANDS: Set<String> = setOf("tcl", "ffalcon")

    fun isTclChina(facts: DeviceFacts): Boolean =
        !facts.hasGms && (facts.brand.trim().lowercase() in BRANDS || facts.manufacturer.trim().lowercase() in BRANDS)

    // Whether the rule decides for this TV: only when no exact profile knows it, or the exact profile
    // is a TCL_CN one. An exact TCL Android TV or Google TV profile keeps its own behaviour (a set
    // whose Google services the probe could not see is not turned into a China-market one).
    fun applies(facts: DeviceFacts, exact: Profile?): Boolean =
        (exact == null || exact.platform == Platform.TCL_CN) && isTclChina(facts)
}

// TCL Google TV sets (issue #275): the firmware sends the remote's Settings (gear), Netflix and
// YouTube buttons only to Google TV's own home screen, so with the new home screen they do nothing.
// TCL as the brand or the maker, and Google TV's home screen (launcherx) installed or answering Home.
// Only a warning before the switch; it never changes what is planned. Not a frozen interface.
object TclGoogleTv {
    const val BRAND = "tcl"
    val GOOGLE_TV_HOME = PackageName("com.google.android.apps.tv.launcherx")

    fun isTcl(facts: DeviceFacts): Boolean =
        BRAND.equals(facts.brand.trim(), ignoreCase = true) || BRAND.equals(facts.manufacturer.trim(), ignoreCase = true)

    // Google TV's home screen (launcherx) installed or answering Home.
    fun hasGoogleTvHome(snapshot: Snapshot?): Boolean {
        val s = snapshot ?: return false
        return GOOGLE_TV_HOME in s.packages || s.home.resolved?.pkg == GOOGLE_TV_HOME || s.home.claimants.any { it.component.pkg == GOOGLE_TV_HOME }
    }

    fun applies(facts: DeviceFacts, snapshot: Snapshot?): Boolean = isTcl(facts) && hasGoogleTvHome(snapshot)
}

// Every other Google TV set (issue #3059, Xiaomi MiTV-MOEU0 on Android 14): Google TV's own home
// screen answers the remote's Settings (gear) button with its settings panel, which on many TVs
// holds the picture and sound settings of the input being watched, so with the new home screen it
// may stop working. The same test as TclGoogleTv without the brand, and never on a TCL set, which
// keeps its own warning. Only a warning before the switch; it never changes what is planned.
object GoogleTvSettingsKey {
    fun applies(facts: DeviceFacts, snapshot: Snapshot?): Boolean =
        !TclGoogleTv.isTcl(facts) && TclGoogleTv.hasGoogleTvHome(snapshot)
}
