package tv.cleanyour.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.core.flows.ButtonsLook
import tv.cleanyour.core.flows.MakeItYoursIntent
import tv.cleanyour.core.flows.SecondDate
import tv.cleanyour.core.flows.TopButton
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.AppsSettings
import tv.cleanyour.core.spec.ConfigKeys
import tv.cleanyour.core.spec.WidgetsSettings

// The Widgets page's rules without a screen: the widget tables and the date and buttons check.
class WidgetsPageTest {
    private val plain = WidgetsSettings()

    @Test fun widgetsAreThe050Rows() {
        assertEquals(listOf("second", "stats", "caption", "retro"), WIDGETS.map { it.key })
        assertEquals(
            listOf(WidgetPlace.UNDER_CLOCK, WidgetPlace.UNDER_CLOCK, WidgetPlace.UNDER_BUTTONS, WidgetPlace.BEFORE_APPS),
            WIDGETS.map { it.place },
        )
        assertTrue(WIDGETS.none { it.later })
    }

    @Test fun everyWidgetWritesKnownKeys() {
        for (w in WIDGETS) {
            assertTrue("${w.key} writes no key", w.configKeys.isNotEmpty())
            for (k in w.configKeys) assertNotNull("${w.key}: $k is not a key the home screen reads", ConfigKeys.of(k))
        }
    }

    @Test fun laterWidgetsWriteNothingAndSayWhy() {
        assertEquals(
            listOf("weather", "world", "countdown", "sun", "prayer", "f1", "match", "note", "sources", "wcard", "wclock"),
            LATER.map { it.key },
        )
        for (w in LATER) {
            assertTrue(w.later)
            assertTrue(w.configKeys.isEmpty())
            assertNotNull(w.why)
            val s = widgetsState()
            assertFalse(w.on(s))
            assertNull(w.toggle(s, true))
        }
        assertEquals(setOf(WidgetPlace.UNDER_CLOCK, WidgetPlace.UNDER_BUTTONS, WidgetPlace.BEFORE_APPS), LATER.map { it.place }.toSet())
    }

    @Test fun keysAreUnique() {
        val keys = (WIDGETS + LATER).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun secondDateTogglesSolarHijriUnlessLunarShows() {
        val second = WIDGETS.first { it.key == "second" }
        val none = widgetsState(plain)
        assertFalse(second.on(none))
        assertEquals(MakeItYoursIntent.Second(SecondDate.SHAMSI), second.toggle(none, true))
        val lunar = widgetsState(plain.copy(lunarDate = true))
        assertTrue(second.on(lunar))
        assertEquals(MakeItYoursIntent.Second(SecondDate.NONE), second.toggle(lunar, false))
        assertEquals(MakeItYoursIntent.Second(SecondDate.LUNAR), second.toggle(lunar, true))
    }

    @Test fun pictureNameAndRetroAreNotSwitchedFromTheStrip() {
        val s = widgetsState()
        assertNull(WIDGETS.first { it.key == "caption" }.toggle(s, false))
        assertNull(WIDGETS.first { it.key == "retro" }.toggle(s, false))
        assertTrue(WIDGETS.first { it.key == "retro" }.on(s))
        assertFalse(WIDGETS.first { it.key == "retro" }.offered(widgetsState(addOns = false)))
        assertFalse(WIDGETS.first { it.key == "caption" }.on(widgetsState(captionsWork = false)))
    }

    @Test fun theUsualButtonsLeaveRoomForTheDate() {
        val s = widgetsState(plain)
        assertEquals(HeaderWidths.CLOCK_BLOCK, clockBlockWidth(s))
        // Inputs, Wi-Fi with its name, Settings.
        assertEquals(100 + 140 + 100 + 2 * 10, buttonsRowWidth(s))
        assertFalse(datePushesIntoButtons(s))
        assertFalse(datePushesIntoButtons(widgetsState(plain.copy(shamsiDate = true))))
    }

    @Test fun everyButtonWithItsNameRunsIntoTheDate() {
        val all = plain.copy(vpnPill = true, reorderButton = true, libraryButton = true, shamsiDate = true)
        val s = widgetsState(all, apps = AppsSettings(appLibrary = true))
        // Inputs, Wi-Fi, VPN, Reorder, Library (round), Settings.
        assertEquals(100 + 140 + 100 + 100 + 42 + 100 + 5 * 10, buttonsRowWidth(s))
        assertTrue(datePushesIntoButtons(s))
        // Under the clock, round icons or one menu: no warning.
        assertFalse(datePushesIntoButtons(widgetsState(all.copy(dateBelowClock = true), apps = AppsSettings(appLibrary = true))))
        assertFalse(datePushesIntoButtons(widgetsState(all.copy(iconOnlyButtons = true, iconOnlyInputs = true, iconOnlyVpn = true), apps = AppsSettings(appLibrary = true))))
        assertFalse(datePushesIntoButtons(widgetsState(all.copy(topMenu = true), apps = AppsSettings(appLibrary = true))))
    }

    @Test fun aLongNameOrASecondDateWidensTheClock() {
        assertEquals(HeaderWidths.CLOCK_BLOCK + HeaderWidths.CLOCK_EXTRA, clockBlockWidth(widgetsState(plain.copy(greetingName = "Alexandrina Rose"))))
        assertEquals(HeaderWidths.CLOCK_BLOCK, clockBlockWidth(widgetsState(plain.copy(greetingName = "Alexandrina Rose", showGreeting = false))))
        assertEquals(HeaderWidths.CLOCK_BLOCK + HeaderWidths.CLOCK_EXTRA, clockBlockWidth(widgetsState(plain.copy(lunarDate = true))))
        // Inputs, Wi-Fi, Reorder, Settings fit beside a plain clock, not beside a widened one.
        val four = plain.copy(reorderButton = true)
        assertFalse(datePushesIntoButtons(widgetsState(four)))
        assertTrue(datePushesIntoButtons(widgetsState(four.copy(lunarDate = true))))
    }

    @Test fun theDateBelowNeedsAHomeScreenThatReadsIt() {
        // Home screen 2.7 (versionCode 8) has no date_below_clock: the date stays beside the clock.
        val all = plain.copy(reorderButton = true, libraryButton = true, dateBelowClock = true, shamsiDate = true)
        assertTrue(datePushesIntoButtons(widgetsState(all, versionCode = 8L, apps = AppsSettings(appLibrary = true))))
        assertFalse(datePushesIntoButtons(widgetsState(all, versionCode = 12L, apps = AppsSettings(appLibrary = true))))
    }

    @Test fun theTopBarInTheHomeScreensOrder() {
        val apps = listOf(PackageName("com.netflix.ninja"), PackageName("com.spotify.tv.android"))
        val s = widgetsState(plain.copy(topBarApps = apps, vpnPill = true, iconOnlyButtons = true, iconOnlyInputs = true, iconOnlyVpn = true))
        val b = headerButtons(s)
        assertEquals(listOf(TopButton.INPUTS, TopButton.APPS, TopButton.APPS, TopButton.WIFI, TopButton.VPN, TopButton.SETTINGS), b.map { it.button })
        assertTrue(b.all { it.round })
        assertEquals(apps, b.mapNotNull { it.app })
        assertEquals(ButtonsLook.ICONS, s.buttonsLook)
        // One menu is the gear alone; a TV before 3.0 has no app buttons or VPN button.
        assertEquals(1, headerButtons(widgetsState(plain.copy(topMenu = true))).size)
        assertEquals(listOf(TopButton.INPUTS, TopButton.WIFI, TopButton.SETTINGS), headerButtons(widgetsState(plain.copy(topBarApps = apps, vpnPill = true), versionCode = 8L)).map { it.button })
    }
}
