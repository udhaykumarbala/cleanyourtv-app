package tv.cleanyour.core.spec

import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot

// Issue #114: one model and device can ship as Android TV and as Google TV. TCL's Smart TV Pro
// (device BeyondTV4) is the Android TV 11 set of tcl-65c725 and, on the G03 AR11 build, a Google TV
// set; tcl-65c725 matches it by model, device, brand and SDK, and its clean-up then switches off what
// the Google TV firmware still uses (com.tcl.keyhelp, the remote's virtual keys, which the G03 list
// leaves on). So an exact Android TV list whose stock launchers do not name Google TV's home screen
// does not match a TV whose snapshot shows that home screen answering Home or claiming it: the TV
// resolves as one no list knows (the brand sure set, else None), and no other list takes its place.
// The rule only ever takes a match away, never adds one. Not a frozen interface.
object HomeFit {
    val GOOGLE_TV_HOME: PackageName = KnownLaunchers.LAUNCHERX.pkg

    // Google TV's home screen is the resolved Home or one of the Home claimants, or it is installed
    // and switched off by the user (`packages`, the snapshot's): after the home screen switch a
    // Google TV set's launcherx is DISABLED_USER and no longer claims Home, and the TV must not flip
    // back to fitting the Android TV list it was refused before.
    fun showsGoogleTvHome(home: HomeResolution, packages: Map<PackageName, PackageState>? = null): Boolean =
        home.resolved?.pkg == GOOGLE_TV_HOME || home.claimants.any { it.component.pkg == GOOGLE_TV_HOME } ||
            packages?.get(GOOGLE_TV_HOME)?.enabled == EnabledState.DISABLED_USER

    // Whether the profile may match a TV with this Home. No Home read yet (null) keeps the facts rule.
    fun fits(profile: Profile, home: HomeResolution?, packages: Map<PackageName, PackageState>? = null): Boolean =
        home == null || profile.platform != Platform.ANDROID_TV ||
            profile.stockLaunchers.any { it.pkg == GOOGLE_TV_HOME } || !showsGoogleTvHome(home, packages)
}

// A profile source that can also weigh the Home a snapshot read (HomeFit). The bundled source is one.
interface HomeAwareProfileSource : ProfileSource {
    // `packages`: the same snapshot's package states, so a switched-off Google TV home still counts.
    suspend fun resolve(facts: DeviceFacts, home: HomeResolution?, packages: Map<PackageName, PackageState>? = null): ProfileResolution
}

// The resolution of the TV a snapshot shows. Every caller with a snapshot asks this way (Setup, the
// planner, the engine's live check, the TV report), so they all agree on the list; a source that
// cannot weigh the Home answers from the facts alone.
suspend fun ProfileSource.resolveFor(snapshot: Snapshot): ProfileResolution = resolveFor(snapshot.facts, snapshot.home, snapshot.packages)

suspend fun ProfileSource.resolveFor(facts: DeviceFacts, home: HomeResolution?, packages: Map<PackageName, PackageState>? = null): ProfileResolution =
    if (this is HomeAwareProfileSource) resolve(facts, home, packages) else resolve(facts)
