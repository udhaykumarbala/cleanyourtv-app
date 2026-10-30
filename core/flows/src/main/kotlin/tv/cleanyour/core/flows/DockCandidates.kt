package tv.cleanyour.core.flows

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.HelperLines
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.trust.PinnedCurtain

// The dock sheet's "Other apps on the TV" (finding 3 of the emulator run). Only apps a launcher
// would show (a LEANBACK_LAUNCHER or LAUNCHER activity, from LaunchableApps); never a home screen
// (the stock ones, ours, any HOME claimant, the setup wizard), never a switched-off package and
// never a content provider package. `launchable` is null when the TV could not say: then only
// user-installed apps and known streaming apps stand in, never the system ones.
// 0.4.8 (issue #2742): a HOME claimant below priority 0 is the framework's placeholder (FallbackHome, at
// -1000, in the TV's Settings app), not a home screen, so it bars nothing. This app's own TV curtain and
// the Home button helper (in our launcher) are never offered.
object DockCandidates {
    fun of(snap: Snapshot, launchable: Set<PackageName>?, profile: Profile?, facts: FactsTable): Set<PackageName> {
        val homes = HashSet<PackageName>()
        val placeholders = snap.home.claimants.filter { it.priority < 0 }.map { it.component }.toSet()
        snap.home.claimants.filter { it.priority >= 0 }.mapTo(homes) { it.component.pkg }
        snap.home.resolved?.takeIf { it !in placeholders }?.let { homes += it.pkg }
        profile?.stockLaunchers?.mapTo(homes) { it.pkg }
        profile?.setupWraith?.let { homes += it }
        homes += Launcher.pkg
        homes += PackageName(PinnedCurtain.PACKAGE)
        homes += HelperLines.SERVICE.pkg
        return snap.packages.filter { (pkg, st) ->
            val category = facts.lookup(pkg)?.category
            val on = st.enabled != EnabledState.DISABLED && st.enabled != EnabledState.DISABLED_USER
            val shown = if (launchable != null) pkg in launchable else !st.system || category == "streaming"
            on && shown && pkg !in homes && category != "launcher" && category != "home" && !isProvider(pkg)
        }.keys
    }

    // com.android.providers.calendar, com.example.contentprovider and the like.
    fun isProvider(pkg: PackageName): Boolean = pkg.value.split('.').any { it.startsWith("provider") || it.endsWith("provider") || it.endsWith("providers") }
}
