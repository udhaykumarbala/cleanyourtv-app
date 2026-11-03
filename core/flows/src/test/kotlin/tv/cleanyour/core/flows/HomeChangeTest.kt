package tv.cleanyour.core.flows

import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.KnownLaunchers
import tv.cleanyour.testing.fixtures.Issue033
import tv.cleanyour.testing.fixtures.Issue034
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 0.5.0 (36 held reports, #2996 and others): the advice under HOME_CHANGED follows what the report shows
// of the TV's own home screen, instead of "set it back as Home in the TV's settings", which no TV in those
// reports could do.
class HomeChangeTest {
    private val generic = KnownLaunchers.generic.map { it.pkg }

    // #2996 (PartnerTV-Box), #4798 (JVC Google TV) and 20 more: Google's home screen is on the TV,
    // switched off (disabled-user), and Home goes to a launcher the person installed.
    @Test fun theTvsOwnHomeScreenSwitchedOffIsNamedForItsSettings() {
        val snap = Issue033.g07Snapshot()
        assertEquals(HomeChange.OWN_SWITCHED_OFF, HomeChange.of(snap, listOf(Issue033.launcherx.pkg)))
        assertEquals(Issue033.launcherx.pkg, HomeChange.switchedOff(snap, emptyList()))
        assertEquals("Google TV Home", HomeChange.settingsName(HomeChange.switchedOff(snap, generic)))
        val tvlauncher = snap.copy(packages = snap.packages - Issue033.launcherx.pkg +
            (KnownLaunchers.TVLAUNCHER.pkg to snap.packages.getValue(Issue033.launcherx.pkg)))
        assertEquals(HomeChange.OWN_SWITCHED_OFF, HomeChange.of(tvlauncher, generic))
        assertEquals("Android TV Home", HomeChange.settingsName(HomeChange.switchedOff(tvlauncher, generic)))
        // Switched off by the system (DISABLED) is off too.
        val disabled = snap.copy(packages = snap.packages + (Issue033.launcherx.pkg to snap.packages.getValue(Issue033.launcherx.pkg).copy(enabled = EnabledState.DISABLED)))
        assertEquals(HomeChange.OWN_SWITCHED_OFF, HomeChange.of(disabled, generic))
    }

    // #3539, #4709 (Hikeen: a vendor home this app does not know), #4954 and 9 more: no Google home screen
    // on the TV at all, or one this app does not know; nothing to turn back on.
    @Test fun noTvHomeScreenToTurnBackOnIsNotFound() {
        val snap = Issue033.g07Snapshot().let { it.copy(packages = it.packages - Issue033.launcherx.pkg) }
        assertEquals(HomeChange.OWN_NOT_FOUND, HomeChange.of(snap, generic))
        assertNull(HomeChange.switchedOff(snap, generic))
        assertNull(HomeChange.settingsName(PackageName("com.zeasn.hikeen")))
        // On, but not a claimant (its Home activity is off on its own): not something Settings' Enable changes.
        val on = Issue033.g07Snapshot().let { it.copy(packages = it.packages + (Issue033.launcherx.pkg to it.packages.getValue(Issue033.launcherx.pkg).copy(enabled = EnabledState.DEFAULT))) }
        assertEquals(HomeChange.OWN_NOT_FOUND, HomeChange.of(on, generic))
    }

    // #3136, #3197, #4328 and 9 more (issues #171, #187, #220 before): Home answers to com.gathin.tvhome
    // with another key, put on another way. That comes first, whatever the TV's own home screen is.
    @Test fun ourHomeScreenUnderAnotherKeyIsNamedAsSuch() {
        for (snap in listOf(Issue034.magnoliaSnapshot(), Issue034.onedaySnapshot(), Issue034.songshanSnapshot())) {
            assertEquals(HomeChange.OURS_ANOTHER_KEY, HomeChange.of(snap, generic), snap.facts.model)
        }
        val ours = Issue033.g07Snapshot().copy(home = HomeResolution(Launcher.home, listOf(HomeClaimant(Launcher.home, 0))))
        assertEquals(HomeChange.OURS_ANOTHER_KEY, HomeChange.of(ours, generic))
    }
}
