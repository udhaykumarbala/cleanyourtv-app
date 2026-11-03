package tv.cleanyour.feature.launcher

import androidx.compose.ui.unit.dp
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.createComposeRule
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.MakeItYoursMachine
import tv.cleanyour.core.flows.MakeItYoursState
import tv.cleanyour.core.flows.SettingsDraft
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.AppsSettings
import tv.cleanyour.core.spec.ClockFormats
import tv.cleanyour.core.spec.WallpaperSettings
import tv.cleanyour.core.spec.WidgetsSettings
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.theme.Glow

// 0.5.0 Make it yours, Widgets: the page body (WidgetsContent) in the page as the hub shows it, each of the
// five places selected, the buttons' looks, the date and buttons warning, a TV on home screen 3.0 and the
// Play build, in dark, light, 200% font and right to left, with ATF on.

private fun app(pkg: String, name: String) = AppChoice(PackageName(pkg), name)

private val widgetsDock = listOf(
    app("com.netflix.ninja", "Netflix"), app("com.google.android.youtube.tv", "YouTube"),
    app("com.amazon.amazonvideo.livingroom", "Prime Video"), app("com.droidlogic.tvinput", "Live TV"),
)
private val widgetsApps = widgetsDock + listOf(
    app("com.spotify.tv.android", "Spotify"), app("com.plexapp.android", "Plex"), app("org.videolan.vlc", "VLC"),
    app("com.wireguard.android", "WireGuard"), app("com.tailscale.ipn", "Tailscale"),
)

// A TV on home screen 3.3 (versionCode 12) with the greeting and a name, Solar Hijri under the clock with TV
// health, the green Wi-Fi, picture names on, and the Retro card.
private val typical = WidgetsSettings(
    showGreeting = true, greetingName = "Priya", shamsiDate = true, showStats = true, wifiStatusColor = true, wifiStatusWarnings = true,
)

internal fun widgetsState(
    widgets: WidgetsSettings = typical, versionCode: Long? = 12L, addOns: Boolean = true, retroOn: Boolean = true, captionsWork: Boolean = true,
    captions: Boolean = true, apps: AppsSettings = AppsSettings(), vpnApps: Boolean = true,
): MakeItYoursState {
    val draft = SettingsDraft(widgets = widgets, apps = apps, wallpaper = WallpaperSettings(captions = captions))
    return MakeItYoursState(
        tvName = "Living room TV", loading = false, versionCode = versionCode, draft = draft, shown = draft.shown(),
        apps = widgetsApps, dock = widgetsDock, vpnApps = if (vpnApps) widgetsApps.filter { MakeItYoursMachine.looksLikeVpn(it) } else emptyList(),
        addOns = addOns, retroOn = retroOn, captionsWork = captionsWork, greetingName = widgets.greetingName.orEmpty(),
    )
}

private fun page(state: MakeItYoursState, place: WidgetPlace, name: String, height: androidx.compose.ui.unit.Dp) = Shot(name, height) {
    ScreenScaffold(Glow.Tv) { WidgetsContent(state, {}, {}, backdropArgb = 0xFF2A3550.toInt(), initialPlace = place) }
}

object WidgetsShots {
    val all: List<Shot> = listOf(
        page(widgetsState(), WidgetPlace.CLOCK, "widgets_clock", 1400.dp),
        page(widgetsState(), WidgetPlace.UNDER_CLOCK, "widgets_under_clock", 1200.dp),
        page(widgetsState(), WidgetPlace.BUTTONS, "widgets_buttons", 2000.dp),
        page(widgetsState(), WidgetPlace.UNDER_BUTTONS, "widgets_under_buttons", 1100.dp),
        page(widgetsState(), WidgetPlace.BEFORE_APPS, "widgets_before_apps", 1200.dp),
        // Round icons with two of your apps in the top bar and the network name by the Wi-Fi icon.
        page(
            widgetsState(typical.copy(iconOnlyButtons = true, iconOnlyInputs = true, iconOnlyVpn = true, wifiLabelWidth = 160, clockFormat = ClockFormats.H24,
                topBarApps = listOf(PackageName("com.netflix.ninja"), PackageName("com.spotify.tv.android")))),
            WidgetPlace.BUTTONS, "widgets_buttons_icons_apps", 2200.dp,
        ),
        page(widgetsState(typical.copy(topMenu = true)), WidgetPlace.BUTTONS, "widgets_buttons_menu", 1500.dp),
        // Every button with its name and the date beside the clock: the date runs into the buttons.
        page(
            widgetsState(typical.copy(vpnPill = true, reorderButton = true, libraryButton = true, vpnApp = "com.wireguard.android"),
                apps = AppsSettings(appLibrary = true)),
            WidgetPlace.BUTTONS, "widgets_collision", 2200.dp,
        ),
        // Home screen 3.0 (versionCode 9): no 12 or 24 hours from the phone.
        page(widgetsState(versionCode = 9L), WidgetPlace.CLOCK, "widgets_clock_home_30", 1400.dp),
        // The Play build: no add-ons, so no cards.
        page(widgetsState(addOns = false, retroOn = false, captionsWork = false), WidgetPlace.BEFORE_APPS, "widgets_play", 900.dp),
    )
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp-xhdpi")
class WidgetsScreenshotTest(private val shot: Shot, private val variant: Variant) {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @Test fun shot() = compose.shoot(shot, variant)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = WidgetsShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
