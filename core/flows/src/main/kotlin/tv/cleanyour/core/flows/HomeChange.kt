package tv.cleanyour.core.flows

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.spec.KnownLaunchers

// 0.5.0 (36 held reports, #2996 and others): what HOME_CHANGED means on this TV, for the advice. Before,
// every screen told the person to "set the TV's own home screen back as Home in the TV's settings, then
// run Setup again". On these TVs that cannot work: HOME_CHANGED means the TV's own home screen is not a
// Home claimant at all (StockHome.changedByPerson), most TV settings have no home screen picker, and in
// 22 of the 36 reports the TV's own home screen was switched off (disabled) by another tool, so no setting
// could pick it. Removing the launcher the person installed, the other reading of that line, would leave
// such a TV with no home screen. So the advice follows what the report shows:
// OURS_ANOTHER_KEY: Home answers to the Clean Your TV home screen put on another way (the computer tool,
//   another copy), under another key; the tool that put it on has its own restore.
// OWN_SWITCHED_OFF: the TV's own home screen is on the TV and switched off, not by this app; turning it
//   back on in the TV's Settings (Apps, system apps, Enable) makes it a claimant, and Setup decides again.
// OWN_NOT_FOUND: the TV's own home screen is neither (not on the TV, or one this app does not know), so
//   there is no step to give; the person keeps their launcher and should not remove it.
enum class HomeChange {
    OURS_ANOTHER_KEY, OWN_SWITCHED_OFF, OWN_NOT_FOUND;

    companion object {
        // Only meaningful under HomeBlock.HOME_CHANGED. `stocks`: the resolution's stock launchers (an
        // exact list's, a brand set's, or the generic ones).
        fun of(snapshot: Snapshot, stocks: Collection<PackageName>): HomeChange = when {
            snapshot.home.resolved?.pkg == Launcher.pkg -> OURS_ANOTHER_KEY
            switchedOff(snapshot, stocks) != null -> OWN_SWITCHED_OFF
            else -> OWN_NOT_FOUND
        }

        // The TV's own home screen that is on the TV but switched off: one of `stocks` (Google's first,
        // in the resolution's order), else Google's TV home screens by package.
        fun switchedOff(snapshot: Snapshot, stocks: Collection<PackageName>): PackageName? =
            (stocks + KnownLaunchers.generic.map { it.pkg }).distinct().firstOrNull { p ->
                p != Launcher.pkg && snapshot.packages[p]?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }
            }

        // How the TV's Settings names that home screen, for the steps; null when the app does not know
        // the name (the screen then says "the TV's own home screen").
        fun settingsName(pkg: PackageName?): String? = when (pkg) {
            KnownLaunchers.TVLAUNCHER.pkg -> "Android TV Home"
            KnownLaunchers.LAUNCHERX.pkg -> "Google TV Home"
            KnownLaunchers.LEANBACK.pkg -> "Android TV Launcher"
            else -> null
        }
    }
}
