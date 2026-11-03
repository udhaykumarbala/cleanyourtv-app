@file:OptIn(ExperimentalLayoutApi::class)

package tv.cleanyour.feature.launcher

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.ButtonsLook
import tv.cleanyour.core.flows.MakeItYoursEdits
import tv.cleanyour.core.flows.MakeItYoursIntent
import tv.cleanyour.core.flows.MakeItYoursState
import tv.cleanyour.core.flows.SecondDate
import tv.cleanyour.core.flows.TopButton
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.ClockFormats
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CytIcon
import tv.cleanyour.ui.design.component.CytTextField
import tv.cleanyour.ui.design.component.LinkRow
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.SwitchRow
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Outfit

// 0.5.0 Make it yours, Widgets (was Clock and top bar, and Cards; design SCREENS.topbar, D3, D5, D13 to D17).
// The TV's five widget places as a board laid out as on the TV (topBoard): Clock and Under the clock on the
// left, Buttons and Under the buttons on the right, Before your apps across the bottom. Tapping a place opens
// its editor under the board. Every change is a MakeItYoursIntent on the one Make it yours draft; the page
// reads `state.shown` (what the TV shows) and offers a control only where this TV's home screen reads its key.

// The places are fixed by the home screen; what is in them is the person's.
enum class WidgetPlace { CLOCK, UNDER_CLOCK, BUTTONS, UNDER_BUTTONS, BEFORE_APPS }

// One widget: a row of data, so a new widget (or one that leaves Later) is one more row in WIDGETS. `line` is
// what the TV draws for it, `on` whether it shows now, `toggle` the intent its pick sends (null: not switched
// from the strip; the place's editor says where), `configKeys` the home screen keys it writes, and `offered`
// whether this TV can show it. A Later widget has `why` (what it needs) and no keys.
class WidgetEntry(
    val key: String,
    val place: WidgetPlace,
    @StringRes val name: Int,
    @StringRes val sub: Int,
    val line: @Composable (MakeItYoursState) -> String,
    val on: (MakeItYoursState) -> Boolean = { false },
    val toggle: (MakeItYoursState, Boolean) -> MakeItYoursIntent? = { _, _ -> null },
    val configKeys: List<String> = emptyList(),
    val offered: (MakeItYoursState) -> Boolean = { s -> configKeys.any { s.reads(it) } },
    @StringRes val why: Int? = null,
) {
    val later: Boolean get() = why != null
}

// The widgets 0.5.0 offers (design WIDGETS), in the home screen's order within each place.
internal val WIDGETS: List<WidgetEntry> = listOf(
    WidgetEntry(
        "second", WidgetPlace.UNDER_CLOCK, R.string.widgets_second_name, R.string.widgets_second_sub,
        line = { s -> stringResource(if (s.secondDate == SecondDate.LUNAR) R.string.widgets_line_lunar else R.string.widgets_line_shamsi) },
        on = { it.secondDate != SecondDate.NONE },
        // On is Solar Hijri unless lunar is the one shown (the editor also remembers a lunar choice made this visit).
        toggle = { s, on -> MakeItYoursIntent.Second(if (!on) SecondDate.NONE else if (s.secondDate == SecondDate.LUNAR) SecondDate.LUNAR else SecondDate.SHAMSI) },
        configKeys = listOf("shamsi_date", "lunar_date"),
    ),
    WidgetEntry(
        "stats", WidgetPlace.UNDER_CLOCK, R.string.widgets_stats_name, R.string.widgets_stats_sub,
        line = { stringResource(R.string.widgets_line_stats) },
        on = { it.shown.widgets.showStats == true },
        toggle = { _, on -> MakeItYoursIntent.Stats(on) },
        configKeys = listOf("show_stats"),
    ),
    // Shown, but switched by the Picture names switch under the strip (the one place 0.5.0 switches it).
    WidgetEntry(
        "caption", WidgetPlace.UNDER_BUTTONS, R.string.widgets_caption_name, R.string.widgets_caption_sub,
        line = { stringResource(R.string.widgets_line_caption) },
        on = { it.shown.wallpaper.captions == true && it.captionsWork },
        configKeys = listOf("wallpaper_captions"),
    ),
    // The Retro card: added and removed in Cards and modules (the pick opens it). The Play build has no add-ons.
    WidgetEntry(
        "retro", WidgetPlace.BEFORE_APPS, R.string.widgets_retro_name, R.string.widgets_retro_sub,
        line = { stringResource(R.string.widgets_line_retro) },
        on = { it.addOns && it.retroOn },
        configKeys = listOf("module_cards"),
        offered = { it.addOns && it.reads("module_cards") },
    ),
)

private fun later(key: String, place: WidgetPlace, @StringRes name: Int, @StringRes line: Int, @StringRes why: Int) =
    WidgetEntry(key, place, name, R.string.widgets_later_sub, line = { stringResource(line) }, offered = { true }, why = why)

// Not on any TV yet (design LATER, D15): dimmed at the end of their place's strip, marked Later, never added.
internal val LATER: List<WidgetEntry> = listOf(
    later("weather", WidgetPlace.UNDER_CLOCK, R.string.widgets_weather_name, R.string.widgets_weather_line, R.string.widgets_weather_why),
    later("world", WidgetPlace.UNDER_CLOCK, R.string.widgets_world_name, R.string.widgets_world_line, R.string.widgets_small_change_why),
    later("countdown", WidgetPlace.UNDER_CLOCK, R.string.widgets_countdown_name, R.string.widgets_countdown_line, R.string.widgets_small_change_why),
    later("sun", WidgetPlace.UNDER_CLOCK, R.string.widgets_sun_name, R.string.widgets_sun_line, R.string.widgets_small_change_why),
    later("prayer", WidgetPlace.UNDER_CLOCK, R.string.widgets_prayer_name, R.string.widgets_prayer_line, R.string.widgets_prayer_why),
    later("f1", WidgetPlace.UNDER_CLOCK, R.string.widgets_f1_name, R.string.widgets_f1_line, R.string.widgets_f1_why),
    later("match", WidgetPlace.UNDER_CLOCK, R.string.widgets_match_name, R.string.widgets_match_line, R.string.widgets_match_why),
    later("note", WidgetPlace.UNDER_BUTTONS, R.string.widgets_note_name, R.string.widgets_note_line, R.string.widgets_small_change_why),
    later("sources", WidgetPlace.BEFORE_APPS, R.string.widgets_sources_name, R.string.widgets_sources_line, R.string.widgets_sources_why),
    later("wcard", WidgetPlace.BEFORE_APPS, R.string.widgets_wcard_name, R.string.widgets_wcard_line, R.string.widgets_card_why),
    later("wclock", WidgetPlace.BEFORE_APPS, R.string.widgets_wclock_name, R.string.widgets_wclock_line, R.string.widgets_card_why),
)

// The header's widths (the date and buttons check)

// One button of the top bar as the TV draws it: round (an icon or an app's logo) or with its name.
internal data class HeaderButton(val button: TopButton, val round: Boolean, val app: PackageName? = null, val menu: Boolean = false)

// The top bar, left to right (activity_main.xml): Inputs, your apps (top_bar_apps), Wi-Fi, VPN (vpn_pill),
// Reorder, Library (needs the App library, always round), Settings. One menu is the gear alone.
internal fun headerButtons(s: MakeItYoursState, look: ButtonsLook = s.buttonsLook): List<HeaderButton> {
    if (look == ButtonsLook.MENU) return listOf(HeaderButton(TopButton.SETTINGS, round = true, menu = true))
    val w = s.shown.widgets
    val icons = look == ButtonsLook.ICONS
    return buildList {
        if (s.shows(TopButton.INPUTS)) add(HeaderButton(TopButton.INPUTS, icons))
        if (s.reads("top_bar_apps")) w.topBarApps.orEmpty().forEach { add(HeaderButton(TopButton.APPS, true, it)) }
        if (s.shows(TopButton.WIFI)) add(HeaderButton(TopButton.WIFI, icons && (w.wifiLabelWidth ?: 0) == 0))
        if (s.reads("vpn_pill") && s.shows(TopButton.VPN)) add(HeaderButton(TopButton.VPN, icons))
        if (s.shows(TopButton.REORDER)) add(HeaderButton(TopButton.REORDER, icons))
        if (s.shows(TopButton.LIBRARY)) add(HeaderButton(TopButton.LIBRARY, true))
        add(HeaderButton(TopButton.SETTINGS, icons))
    }
}

// A width estimate in the TV's own dp (its home screen is laid out for 960 dp across). The clock block starts
// 56 dp from the left edge and the buttons end 56 dp from the right, so the two share 848 dp and need about 16 dp
// between them. The numbers lean wide on purpose only where the TV's text varies (a long name, a second date),
// so the warning shows when the date would really touch the buttons, not on every TV with a few buttons.
internal object HeaderWidths {
    const val TV_WIDTH = 960
    const val MARGIN = 56
    const val BETWEEN = 16
    const val CLOCK_BLOCK = 300        // the clock, the short upright line and the date column
    const val CLOCK_EXTRA = 80         // a second date line, or a long greeting over the block
    const val LONG_NAME = 10           // a greeting name longer than this widens the block
    const val NAMED_BUTTON = 100       // a button with its name (Inputs, VPN, Reorder, Settings)
    const val ROUND_BUTTON = 42        // a round button or an app's logo
    const val WIFI_NAMED = 140         // the Wi-Fi button with the network's name
    const val BUTTON_GAP = 10
}

internal fun clockBlockWidth(s: MakeItYoursState): Int {
    val w = s.shown.widgets
    val greeting = !s.reads("show_greeting") || w.showGreeting != false
    val longName = greeting && w.greetingName.orEmpty().trim().length > HeaderWidths.LONG_NAME
    val extra = s.secondDate != SecondDate.NONE || longName
    return HeaderWidths.CLOCK_BLOCK + if (extra) HeaderWidths.CLOCK_EXTRA else 0
}

internal fun buttonsRowWidth(s: MakeItYoursState): Int {
    val b = headerButtons(s)
    val widths = b.sumOf {
        when {
            it.round -> HeaderWidths.ROUND_BUTTON
            it.button == TopButton.WIFI -> HeaderWidths.WIFI_NAMED
            else -> HeaderWidths.NAMED_BUTTON
        }
    }
    return widths + HeaderWidths.BUTTON_GAP * (b.size - 1).coerceAtLeast(0)
}

// The home screen does not move the date out of the buttons' way (design renderTV), so the page checks: the
// date beside the clock, the buttons not one menu, and the two together wider than the TV has room for.
internal fun datePushesIntoButtons(s: MakeItYoursState): Boolean {
    val below = s.reads("date_below_clock") && s.shown.widgets.dateBelowClock == true
    if (below || s.buttonsLook == ButtonsLook.MENU) return false
    val room = HeaderWidths.TV_WIDTH - 2 * HeaderWidths.MARGIN
    return clockBlockWidth(s) + HeaderWidths.BETWEEN + buttonsRowWidth(s) > room
}

// The page

// The page body: the board, the hint, the date and buttons warning, then the selected place's editor. No
// scaffold, title or draft bar (the hub wraps it). `backdrop` is the TV's picture, else `backdropArgb`.
// `initialPlace`: the place selected when the page first opens (screenshot tests); it is kept across
// recreation.
@Composable
internal fun ColumnScope.WidgetsContent(
    state: MakeItYoursState,
    onIntent: (MakeItYoursIntent) -> Unit,
    onOpenCards: () -> Unit,
    backdrop: ImageBitmap? = null,
    backdropArgb: Int = 0xFF101014.toInt(),
    initialPlace: WidgetPlace = WidgetPlace.CLOCK,
) {
    var place by rememberSaveable { mutableStateOf(initialPlace) }
    Board(state, place, { place = it }, backdrop, backdropArgb, Modifier.padding(top = 8.dp))
    Hint(stringResource(R.string.widgets_board_hint))
    if (datePushesIntoButtons(state) && state.reads("date_below_clock")) {
        Banner(
            stringResource(R.string.widgets_collide), BannerTone.WARNING, Modifier.padding(top = 12.dp),
            actionLabel = stringResource(R.string.widgets_collide_action), onAction = { onIntent(MakeItYoursIntent.DateBelow(true)) },
        )
    }
    when (place) {
        WidgetPlace.CLOCK -> ClockEditor(state, onIntent)
        WidgetPlace.UNDER_CLOCK -> UnderClockEditor(state, onIntent)
        WidgetPlace.BUTTONS -> ButtonsEditor(state, onIntent)
        WidgetPlace.UNDER_BUTTONS -> UnderButtonsEditor(state, onIntent)
        WidgetPlace.BEFORE_APPS -> BeforeAppsEditor(state, onIntent, onOpenCards)
    }
}

@Composable
internal fun placeName(p: WidgetPlace): String = stringResource(
    when (p) {
        WidgetPlace.CLOCK -> R.string.widgets_place_clock
        WidgetPlace.UNDER_CLOCK -> R.string.widgets_place_under_clock
        WidgetPlace.BUTTONS -> R.string.widgets_place_buttons
        WidgetPlace.UNDER_BUTTONS -> R.string.widgets_place_under_buttons
        WidgetPlace.BEFORE_APPS -> R.string.widgets_place_before_apps
    },
)

// The board (design topBoard, .tb and .wd)

private val BoardWhite = Color.White
private const val LARGE_TEXT = 1.3f
private val BoardDim = Color.White.copy(alpha = 0.7f)
private val BoardText = TextStyle(fontFamily = Outfit, color = BoardWhite)

@Composable
private fun Board(
    state: MakeItYoursState, selected: WidgetPlace, onSelect: (WidgetPlace) -> Unit, backdrop: ImageBitmap?, backdropArgb: Int, modifier: Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.fillMaxWidth().clip(shape)) {
        if (backdrop != null) {
            Image(backdrop, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.matchParentSize().background(Color(backdropArgb)))
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1.08f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClockCard(state, selected == WidgetPlace.CLOCK) { onSelect(WidgetPlace.CLOCK) }
                    UnderClockCard(state, selected == WidgetPlace.UNDER_CLOCK) { onSelect(WidgetPlace.UNDER_CLOCK) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ButtonsCard(state, selected == WidgetPlace.BUTTONS) { onSelect(WidgetPlace.BUTTONS) }
                    UnderButtonsCard(state, selected == WidgetPlace.UNDER_BUTTONS) { onSelect(WidgetPlace.UNDER_BUTTONS) }
                }
            }
            BeforeAppsCard(state, selected == WidgetPlace.BEFORE_APPS) { onSelect(WidgetPlace.BEFORE_APPS) }
        }
    }
}

// One place on the board: a button that carries its selected state, read as "{place}. {what is in it}". The
// drawing inside is hidden from TalkBack (the description says it), and an empty place has a dashed rim.
@Composable
private fun PlaceCard(
    place: WidgetPlace, selected: Boolean, now: String, empty: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val name = placeName(place)
    val cd = msg(R.string.widgets_board_place_cd, "place" to name, "now" to now)
    val accent = CytTheme.colors.accent
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (selected) Modifier.border(3.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(15.dp)) else Modifier)
            .padding(3.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = if (selected) 0.5f else 0.32f))
            .then(
                when {
                    selected -> Modifier.border(1.5.dp, BoardWhite, shape)
                    empty -> Modifier.drawBehind {
                        val w = 1.5.dp.toPx()
                        drawRoundRect(
                            BoardWhite.copy(alpha = 0.45f), cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    else -> Modifier.border(1.5.dp, BoardWhite.copy(alpha = 0.2f), shape)
                },
            )
            .selectable(selected, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 9.dp),
    ) {
        Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name.toUpperCase(Locale.current), style = BoardText.copy(fontSize = 10.sp, letterSpacing = 0.12.em, color = BoardDim), maxLines = 2)
            content()
        }
    }
}

@Composable
private fun greetingText(s: MakeItYoursState): String? {
    val w = s.shown.widgets
    val on = !s.reads("show_greeting") || w.showGreeting != false
    if (!on) return null
    val name = w.greetingName.orEmpty().trim()
    return if (name.isEmpty()) stringResource(R.string.widgets_sample_greeting) else msg(R.string.widgets_sample_greeting_name, "name" to name)
}

// 12 or 24 hours as the TV will show them: clock_format where this home screen reads it, else the TV's own
// setting, which the phone cannot read (the phone's own stands in for it).
@Composable
private fun clock24(s: MakeItYoursState): Boolean {
    val f = s.shown.widgets.clockFormat
    return when {
        s.reads("clock_format") && f == ClockFormats.H24 -> true
        s.reads("clock_format") && f == ClockFormats.H12 -> false
        else -> android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    }
}

@Composable
private fun ClockCard(s: MakeItYoursState, selected: Boolean, onClick: () -> Unit) {
    val greeting = greetingText(s)
    val h24 = clock24(s)
    val time = stringResource(if (h24) R.string.widgets_sample_time_24 else R.string.widgets_sample_time_12)
    val ampm = stringResource(R.string.widgets_sample_am_pm)
    val date = stringResource(R.string.widgets_sample_date)
    val below = s.reads("date_below_clock") && s.shown.widgets.dateBelowClock == true
    val bright = s.reads("header_white") && s.shown.widgets.headerWhite == true
    val now = listOfNotNull(greeting, if (h24) time else "$time $ampm", date).joinToString(", ")
    PlaceCard(WidgetPlace.CLOCK, selected, now, empty = false, onClick) {
        if (greeting != null) {
            Text(greeting.toUpperCase(Locale.current), style = BoardText.copy(fontSize = 9.sp, letterSpacing = 0.14.em, color = BoardDim),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val clock = buildAnnotatedString {
            append(time)
            if (!h24) withStyle(SpanStyle(fontSize = 11.sp)) { append(" $ampm") }
        }
        val dateStyle = BoardText.copy(fontSize = 9.5.sp, letterSpacing = 0.12.em, color = if (bright) BoardWhite else BoardDim)
        if (below) {
            Text(clock, style = BoardText.copy(fontSize = 30.sp, fontWeight = FontWeight.Light, lineHeight = 32.sp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BoardWhite.copy(alpha = 0.3f)))
            Text(date.toUpperCase(Locale.current), Modifier.padding(top = 2.dp), style = dateStyle)
        } else if (LocalDensity.current.fontScale > LARGE_TEXT) {
            // Beside the clock, but with large text there is no room beside it in the card: the date under it, no line.
            Text(clock, style = BoardText.copy(fontSize = 26.sp, fontWeight = FontWeight.Light, lineHeight = 28.sp))
            Text(date.toUpperCase(Locale.current), style = dateStyle)
        } else {
            // Beside the clock, after a short upright line.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(clock, style = BoardText.copy(fontSize = 26.sp, fontWeight = FontWeight.Light, lineHeight = 28.sp))
                Box(Modifier.width(1.dp).height(22.dp).background(BoardWhite.copy(alpha = 0.5f)))
                Text(date.toUpperCase(Locale.current), Modifier.weight(1f), style = dateStyle)
            }
        }
    }
}

@Composable
private fun UnderClockCard(s: MakeItYoursState, selected: Boolean, onClick: () -> Unit) {
    val lines = WIDGETS.filter { it.place == WidgetPlace.UNDER_CLOCK && it.offered(s) && it.on(s) }.map { it.line(s) }
    val empty = lines.isEmpty()
    PlaceCard(WidgetPlace.UNDER_CLOCK, selected, if (empty) stringResource(R.string.widgets_board_nothing) else lines.joinToString(". "), empty, onClick) {
        if (empty) AddLine(stringResource(R.string.widgets_board_add))
        lines.forEach { Text(it.toUpperCase(Locale.current), style = BoardText.copy(fontSize = 10.sp, letterSpacing = 0.08.em, color = BoardWhite.copy(alpha = 0.85f))) }
    }
}

@Composable
private fun AddLine(text: String) {
    Text(text, style = BoardText.copy(fontSize = 13.sp, color = BoardWhite.copy(alpha = 0.85f)))
}

@Composable
private fun ButtonsCard(s: MakeItYoursState, selected: Boolean, onClick: () -> Unit) {
    val buttons = headerButtons(s)
    val labels = buttons.map { buttonLabel(s, it) }
    PlaceCard(WidgetPlace.BUTTONS, selected, labels.joinToString(", "), empty = false, onClick) {
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            buttons.forEachIndexed { i, b -> MiniPill(s, b, labels[i], 24.dp, 11) }
        }
    }
}

@Composable
private fun UnderButtonsCard(s: MakeItYoursState, selected: Boolean, onClick: () -> Unit) {
    val lines = WIDGETS.filter { it.place == WidgetPlace.UNDER_BUTTONS && it.offered(s) && it.on(s) }.map { it.line(s) }
    val empty = lines.isEmpty()
    PlaceCard(WidgetPlace.UNDER_BUTTONS, selected, if (empty) stringResource(R.string.widgets_board_nothing) else lines.joinToString(". "), empty, onClick) {
        if (empty) AddLine(stringResource(R.string.widgets_board_add))
        lines.forEach { Text(it, style = BoardText.copy(fontSize = 12.5.sp, color = BoardWhite.copy(alpha = 0.9f))) }
    }
}

@Composable
private fun BeforeAppsCard(s: MakeItYoursState, selected: Boolean, onClick: () -> Unit) {
    val cards = WIDGETS.filter { it.place == WidgetPlace.BEFORE_APPS && it.offered(s) && it.on(s) }
    val empty = cards.isEmpty()
    val names = cards.map { stringResource(it.name) to it.line(s) }
    val emptyText = stringResource(if (s.addOns) R.string.widgets_board_add_card else R.string.widgets_board_no_cards)
    val now = if (empty) (if (s.addOns) stringResource(R.string.widgets_board_nothing) else emptyText) else names.joinToString(". ") { "${it.first}, ${it.second}" }
    PlaceCard(WidgetPlace.BEFORE_APPS, selected, now, empty, onClick) {
        if (empty) AddLine(emptyText)
        if (!empty) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { (name, line) ->
                    val cardShape = RoundedCornerShape(8.dp)
                    Column(Modifier.clip(cardShape).background(Color(0xD914141A)).border(1.dp, BoardWhite.copy(alpha = 0.22f), cardShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(name, style = BoardText.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium))
                        Text(line, style = BoardText.copy(fontSize = 9.5.sp, color = BoardDim))
                    }
                }
            }
        }
    }
}

// The top bar's buttons, drawn small (design pillsHTML, .mp)

@Composable
private fun buttonLabel(s: MakeItYoursState, b: HeaderButton): String = when {
    b.menu -> stringResource(R.string.widgets_btn_menu)
    b.app != null -> s.name(b.app)
    else -> stringResource(buttonName(b.button))
}

@StringRes
private fun buttonName(b: TopButton): Int = when (b) {
    TopButton.INPUTS -> R.string.widgets_btn_inputs
    TopButton.APPS -> R.string.widgets_btn_apps
    TopButton.WIFI -> R.string.widgets_btn_wifi
    TopButton.VPN -> R.string.widgets_btn_vpn
    TopButton.REORDER -> R.string.widgets_btn_reorder
    TopButton.LIBRARY -> R.string.widgets_btn_library
    TopButton.SETTINGS -> R.string.widgets_btn_settings
}

@StringRes
private fun buttonSub(b: TopButton): Int = when (b) {
    TopButton.INPUTS -> R.string.widgets_btn_inputs_sub
    TopButton.APPS -> R.string.widgets_btn_apps_sub
    TopButton.WIFI -> R.string.widgets_btn_wifi_sub
    TopButton.VPN -> R.string.widgets_btn_vpn_sub
    TopButton.REORDER -> R.string.widgets_btn_reorder_sub
    TopButton.LIBRARY -> R.string.widgets_btn_library_sub
    TopButton.SETTINGS -> R.string.widgets_btn_settings_sub
}

private fun buttonIcon(b: TopButton): ImageVector = when (b) {
    TopButton.INPUTS -> CytIcons.Tv
    TopButton.APPS -> WidgetIcons.Apps
    TopButton.WIFI -> CytIcons.Wifi
    TopButton.VPN -> CytIcons.Lock
    TopButton.REORDER -> WidgetIcons.Reorder
    TopButton.LIBRARY -> WidgetIcons.Library
    TopButton.SETTINGS -> WidgetIcons.Gear
}

private val VpnGreen = Color(0xFF1F8F4A)
private val WifiGreen = Color(0xFF5CE07E)
private val AppFill = Color(0xFF3A4060)

// One button: a pill with an icon and its name, or round (an icon, or an app's first letter). Pictures only:
// the place's description says the names.
@Composable
private fun MiniPill(s: MakeItYoursState, b: HeaderButton, label: String, height: Dp, textSp: Int) {
    val shape = RoundedCornerShape(50)
    val vpn = b.button == TopButton.VPN
    val fill = when {
        b.app != null -> AppFill
        vpn -> VpnGreen
        else -> Color.Black.copy(alpha = 0.28f)
    }
    val rim = if (b.app != null || vpn) Color.Transparent else BoardWhite.copy(alpha = 0.45f)
    val green = b.button == TopButton.WIFI && s.shown.widgets.wifiStatusColor == true
    val iconSize = height * 0.55f
    if (b.round) {
        Box(Modifier.size(height).clip(shape).background(fill).border(1.dp, rim, shape), contentAlignment = Alignment.Center) {
            if (b.app != null) {
                val letterSp = with(LocalDensity.current) { (height * 0.5f).toSp() }
                Text(label.trim().take(1).toUpperCase(Locale.current), style = BoardText.copy(fontSize = letterSp, fontWeight = FontWeight.SemiBold, lineHeight = letterSp))
            } else {
                CytIcon(buttonIcon(b.button), null, tint = if (green) WifiGreen else BoardWhite, size = iconSize)
            }
        }
    } else {
        Row(
            Modifier.heightIn(min = height).clip(shape).background(fill).border(1.dp, rim, shape).padding(horizontal = height / 3, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CytIcon(buttonIcon(b.button), null, tint = if (green) WifiGreen else BoardWhite, size = iconSize)
            Text(label, style = BoardText.copy(fontSize = textSp.sp), maxLines = 1)
        }
    }
}

// The buttons' icons the app's set does not have (design ICONS): apps, library, reorder arrows, gear.
private object WidgetIcons {
    val Apps: ImageVector by lazy {
        icon("Apps", "M7 3a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M17 3a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M7 13a4 4 0 1 1 0 8a4 4 0 1 1 0-8z", "M17 13a4 4 0 1 1 0 8a4 4 0 1 1 0-8z")
    }
    val Library: ImageVector by lazy { icon("Library", "M4 4h7v7H4z", "M13 4h7v7h-7z", "M4 13h7v7H4z", "M13 13h7v7h-7z") }
    val Reorder: ImageVector by lazy { icon("ReorderArrows", "M7 4v16M4 7l3-3 3 3M17 20V4M14 17l3 3 3-3") }
    val Gear: ImageVector by lazy {
        icon("Gear", "M12 8.8a3.2 3.2 0 1 1 0 6.4a3.2 3.2 0 1 1 0-6.4z",
            "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1")
    }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}

// Shared parts of the editors

// A section label that is a heading, so TalkBack can jump between groups.
@Composable
private fun Label(text: String, top: Dp = 24.dp) {
    Overline(text, Modifier.padding(start = 4.dp, top = top, bottom = 8.dp).semantics { heading() })
}

@Composable
private fun Hint(text: String, top: Dp = 8.dp) {
    Body(text, Modifier.padding(start = 4.dp, end = 4.dp, top = top))
}

// What a tap on a Later widget, a locked button or a refused app says: one line under the strip, read out
// as it changes.
@Composable
private fun NoteLine(text: String) {
    val c = CytTheme.colors
    val shape = CytTheme.shapes.row
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(c.surface2).border(1.dp, c.outline, shape)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        CytIcon(CytIcons.Info, null, tint = c.textSecondary)
        Text(text, Modifier.weight(1f), color = c.textPrimary, style = CytTheme.type.bodyMedium)
    }
}

private class ChipOption(val key: String, val label: String, val enabled: Boolean = true)

// A few choices as pills that wrap (design chips): each a radio button at least 48 dp high.
@Composable
private fun Chips(options: List<ChipOption>, selected: String?, groupLabel: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = CytTheme.colors
    val shape = CytTheme.shapes.pill
    FlowRow(
        modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (o in options) {
            val on = o.key == selected
            Row(
                Modifier.heightIn(min = 48.dp).clip(shape)
                    .background(if (on) c.textPrimary else c.surface1)
                    .border(1.dp, if (on) c.textPrimary else c.outlineStrong, shape)
                    .selectable(on, enabled = o.enabled, role = Role.RadioButton) { onSelect(o.key) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (on) CytIcon(CytIcons.Check, null, tint = c.bg, size = 16.dp)
                Text(o.label, color = when { on -> c.bg; o.enabled -> c.textPrimary; else -> c.textTertiary }, style = CytTheme.type.bodyMedium)
            }
        }
    }
}

// A sideways strip of picks (design .strip), scrolled by hand.
@Composable
private fun Strip(label: String, radio: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .then(if (radio) Modifier.selectableGroup() else Modifier)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private enum class Mark { ON, OFF, LATER, NONE }

// One pick (design .pick): a small dark picture with a mark at its top end (a check or a number when on, a ring
// when off, a Later tag), the name and one line under it. `interaction` is the pick's role, state and click with
// its description; everything inside is drawn only.
@Composable
private fun Pick(
    name: String, sub: String?, mark: Mark, interaction: Modifier, width: Dp = 112.dp, number: Int? = null, dim: Boolean = false,
    fill: Color = Color(0xFF101014), thumb: @Composable BoxScope.() -> Unit,
) {
    val c = CytTheme.colors
    val shape = CytTheme.shapes.small
    Column(Modifier.width(width).clip(shape).then(interaction).padding(4.dp)) {
        Box(Modifier.clearAndSetSemantics {}) {
            Box(
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                    .border(if (mark == Mark.ON) 2.dp else 1.dp, if (mark == Mark.ON) c.textPrimary else BoardWhite.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .alpha(if (dim) 0.55f else 1f),
                contentAlignment = Alignment.Center,
            ) { thumb() }
            when (mark) {
                Mark.ON -> Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).widthIn(min = 22.dp).height(22.dp).background(c.accent, CytTheme.shapes.pill).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (number != null) Text("$number", color = Color.Black, style = TextStyle(fontFamily = Outfit, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    else CytIcon(CytIcons.Check, null, tint = Color.Black, size = 14.dp)
                }
                Mark.OFF -> Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.Black.copy(alpha = 0.28f), CytTheme.shapes.pill)
                        .border(2.dp, BoardWhite.copy(alpha = 0.9f), CytTheme.shapes.pill),
                )
                Mark.LATER -> Text(
                    stringResource(R.string.widgets_later_tag).toUpperCase(Locale.current),
                    Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xFF3A3A40), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = BoardText.copy(fontSize = 9.5.sp, letterSpacing = 0.08.em),
                )
                Mark.NONE -> {}
            }
        }
        Column(Modifier.clearAndSetSemantics {}) {
            Text(name, Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp), color = if (mark == Mark.ON) c.textPrimary else c.textSecondary,
                style = CytTheme.type.bodyMedium, textAlign = TextAlign.Center, maxLines = 2)
            if (sub != null) Text(sub, Modifier.fillMaxWidth().padding(horizontal = 2.dp), color = c.textTertiary, style = CytTheme.type.bodySmall, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

// The line a widget's pick draws in its picture.
@Composable
private fun BoxScope.LineThumb(text: String) {
    // A picture of the TV's line: its size follows the picture, not the phone's text size (the name under it does).
    val d = LocalDensity.current
    val size = with(d) { 9.dp.toSp() }
    val line = with(d) { 12.dp.toSp() }
    Text(
        // Clear of the mark at the top end.
        text.toUpperCase(Locale.current), Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 2.dp),
        style = BoardText.copy(fontSize = size, letterSpacing = 0.08.em, lineHeight = line), textAlign = TextAlign.Center, maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

// One place's strip (design placeStrip): the widgets this TV can show as checkable picks, then the Later ones,
// dimmed and tagged; tapping a Later one says what it needs in the line under the strip.
@Composable
private fun PlaceStrip(
    s: MakeItYoursState, place: WidgetPlace, label: String, onIntent: (MakeItYoursIntent) -> Unit,
    toggle: (WidgetEntry, Boolean) -> MakeItYoursIntent? = { e, on -> e.toggle(s, on) },
    onFixed: ((WidgetEntry) -> Unit)? = null,
    fixedLabel: String? = null,
    note: String?, onNote: (String?) -> Unit,
) {
    val now = WIDGETS.filter { it.place == place && it.offered(s) }
    val later = LATER.filter { it.place == place }
    val onWord = stringResource(R.string.widgets_pick_on)
    val offWord = stringResource(R.string.widgets_pick_off)
    Label(label)
    val whys = later.associate { it.key to stringResource(it.why!!) }
    Strip(label) {
        for (e in now) {
            val on = e.on(s)
            val name = stringResource(e.name)
            val sub = stringResource(e.sub)
            val switchable = e.toggle(s, !on) != null
            val interaction = if (switchable) {
                Modifier.toggleable(on, role = Role.Checkbox) { v -> toggle(e, v)?.let(onIntent); onNote(null) }
                    .semantics { contentDescription = "$name. $sub" }
            } else if (onFixed != null) {
                Modifier.clickable(role = Role.Button, onClickLabel = fixedLabel) { onFixed(e) }
                    .semantics { contentDescription = "$name. $sub"; stateDescription = if (on) onWord else offWord }
            } else {
                Modifier.semantics { contentDescription = "$name. $sub"; stateDescription = if (on) onWord else offWord }
            }
            Pick(name, sub, if (on) Mark.ON else Mark.OFF, interaction) { LineThumb(e.line(s)) }
        }
        for (e in later) {
            val name = stringResource(e.name)
            val cd = msg(R.string.widgets_later_cd, "name" to name)
            val action = stringResource(R.string.widgets_later_action)
            val why = whys.getValue(e.key)
            Pick(
                name, stringResource(e.sub), Mark.LATER, dim = true,
                interaction = Modifier.clickable(role = Role.Button, onClickLabel = action) { onNote(why) }.semantics { contentDescription = cd },
            ) { LineThumb(e.line(s)) }
        }
    }
    note?.let { NoteLine(it) }
}

// Clock (design editClock, D13)

@Composable
private fun ColumnScope.ClockEditor(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit) {
    val w = s.shown.widgets
    val readsGreeting = s.reads("show_greeting")
    val greetingOn = !readsGreeting || w.showGreeting != false
    // The typed name stays on the phone while Plain is chosen, so With your name brings it back.
    var typed by rememberSaveable { mutableStateOf(s.greetingName) }
    var withName by rememberSaveable { mutableStateOf(w.greetingName.orEmpty().isNotBlank()) }
    Label(placeName(WidgetPlace.CLOCK), top = 20.dp)
    if (readsGreeting) {
        ListGroup {
            SwitchRow(stringResource(R.string.widgets_greeting), greetingOn, { onIntent(MakeItYoursIntent.Greeting(it)) },
                subtitle = stringResource(R.string.widgets_greeting_sub))
        }
    }
    if (greetingOn && s.reads("greeting_name")) {
        val greetingLabel = stringResource(R.string.widgets_greeting)
        Chips(
            listOf(ChipOption("plain", stringResource(R.string.widgets_greeting_plain)), ChipOption("name", stringResource(R.string.widgets_greeting_with_name))),
            if (withName) "name" else "plain", greetingLabel,
            { k ->
                withName = k == "name"
                onIntent(MakeItYoursIntent.GreetingName(if (withName) typed else ""))
            },
            Modifier.padding(top = 12.dp),
        )
        if (withName) {
            CytTextField(
                typed, { v -> typed = v.take(MakeItYoursEdits.MAX_NAME); onIntent(MakeItYoursIntent.GreetingName(typed)) },
                stringResource(R.string.widgets_name_label), Modifier.padding(top = 10.dp), supporting = stringResource(R.string.widgets_name_hint),
            )
        }
    }
    if (greetingOn) Hint(stringResource(R.string.widgets_greeting_times))

    val below = s.reads("date_below_clock")
    val bright = s.reads("header_white")
    if (below || bright) {
        Label(stringResource(R.string.widgets_date_label))
        ListGroup {
            if (below) {
                SwitchRow(stringResource(R.string.widgets_date_below), w.dateBelowClock == true, { onIntent(MakeItYoursIntent.DateBelow(it)) },
                    subtitle = stringResource(R.string.widgets_date_below_sub))
            }
            if (below && bright) ListDivider()
            if (bright) {
                SwitchRow(stringResource(R.string.widgets_bright_date), w.headerWhite == true, { onIntent(MakeItYoursIntent.BrightDate(it)) },
                    subtitle = stringResource(R.string.widgets_bright_date_sub))
            }
        }
    }

    val timeLabel = stringResource(R.string.widgets_time_label)
    Label(timeLabel)
    if (s.reads("clock_format")) {
        Chips(
            listOf(
                ChipOption(ClockFormats.SYSTEM, stringResource(R.string.widgets_time_system)),
                ChipOption(ClockFormats.H12, stringResource(R.string.widgets_time_12)),
                ChipOption(ClockFormats.H24, stringResource(R.string.widgets_time_24)),
            ),
            w.clockFormat ?: ClockFormats.SYSTEM, timeLabel, { onIntent(MakeItYoursIntent.ClockFormat(it)) },
        )
        Hint(stringResource(R.string.widgets_time_hint))
    } else {
        Hint(stringResource(R.string.widgets_time_older), top = 0.dp)
    }
    Hint(stringResource(R.string.widgets_clock_always), top = 12.dp)
}

// Under the clock (design editUnder)

@Composable
private fun ColumnScope.UnderClockEditor(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit) {
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val lunarOk = s.reads("lunar_date")
    // The second date chosen this visit, so switching it off and on keeps lunar.
    var lastSecond by rememberSaveable { mutableStateOf(if (s.secondDate == SecondDate.LUNAR) SecondDate.LUNAR else SecondDate.SHAMSI) }
    PlaceStrip(
        s, WidgetPlace.UNDER_CLOCK, placeName(WidgetPlace.UNDER_CLOCK), onIntent,
        toggle = { e, on ->
            if (e.key == "second" && on && lastSecond == SecondDate.LUNAR && lunarOk) MakeItYoursIntent.Second(SecondDate.LUNAR) else e.toggle(s, on)
        },
        note = note, onNote = { note = it },
    )
    Hint(stringResource(R.string.widgets_under_hint))
    if (s.secondDate != SecondDate.NONE) {
        val label = stringResource(R.string.widgets_second_label)
        Label(label)
        val options = listOfNotNull(
            ChipOption(SecondDate.SHAMSI.name, stringResource(R.string.widgets_second_shamsi)),
            ChipOption(SecondDate.LUNAR.name, stringResource(R.string.widgets_second_lunar)).takeIf { lunarOk },
        )
        Chips(options, s.secondDate.name, label, { k ->
            val d = SecondDate.valueOf(k)
            lastSecond = d
            onIntent(MakeItYoursIntent.Second(d))
        })
        Hint(stringResource(if (s.secondDate == SecondDate.LUNAR) R.string.widgets_second_lunar_hint else R.string.widgets_second_shamsi_hint))
    }
    if (s.shown.widgets.showStats == true && s.reads("show_stats")) Hint(stringResource(R.string.widgets_stats_hint), top = 16.dp)
}

// Buttons (design editButtons, D14)

@Composable
private fun ColumnScope.ButtonsEditor(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit) {
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var appsNote by rememberSaveable { mutableStateOf<String?>(null) }
    val w = s.shown.widgets
    val look = s.buttonsLook

    // How they look: the three looks, each a small picture of this TV's buttons in it.
    val lookLabel = stringResource(R.string.widgets_look_label)
    Label(lookLabel, top = 20.dp)
    val looks = listOfNotNull(
        Triple(ButtonsLook.NAMES, R.string.widgets_look_names, R.string.widgets_look_names_sub),
        Triple(ButtonsLook.ICONS, R.string.widgets_look_icons, R.string.widgets_look_icons_sub),
        Triple(ButtonsLook.MENU, R.string.widgets_look_menu, R.string.widgets_look_menu_sub).takeIf { s.reads("top_menu") || look == ButtonsLook.MENU },
    )
    Strip(lookLabel, radio = true) {
        for ((k, name, sub) in looks) {
            val t = stringResource(name)
            val d = stringResource(sub)
            val picked = look == k
            val preview = headerButtons(s, k)
            val labels = preview.map { buttonLabel(s, it) }
            Pick(
                t, d, if (picked) Mark.ON else Mark.NONE, width = 132.dp,
                interaction = Modifier.selectable(picked, role = Role.RadioButton) { onIntent(MakeItYoursIntent.Look(k)) }.semantics { contentDescription = "$t. $d" },
            ) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(start = 6.dp, end = 30.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    preview.forEachIndexed { i, b -> MiniPill(s, b, labels[i], 14.dp, 7) }
                }
            }
        }
    }
    if (look == ButtonsLook.MENU) {
        Hint(stringResource(R.string.widgets_look_menu_hint))
    } else {
        // Which buttons, in the home screen's order. Settings always shows; Library brings the App library.
        val whichLabel = stringResource(R.string.widgets_which_label)
        Label(whichLabel)
        val readsKey = mapOf(
            TopButton.INPUTS to "show_inputs_pill", TopButton.APPS to "top_bar_apps", TopButton.WIFI to "show_network_pill",
            TopButton.VPN to "vpn_pill", TopButton.REORDER to "reorder_button", TopButton.LIBRARY to "library_button",
        )
        val always = stringResource(R.string.widgets_btn_settings_always)
        Strip(whichLabel) {
            for (b in TopButton.entries) {
                val key = readsKey[b]
                if (key != null && !s.reads(key)) continue
                val on = s.shows(b)
                val name = stringResource(buttonName(b))
                val needsLibrary = b == TopButton.LIBRARY && s.shown.apps.appLibrary != true
                val sub = stringResource(if (needsLibrary) R.string.widgets_btn_library_needs else buttonSub(b))
                val interaction = if (b == TopButton.SETTINGS) {
                    Modifier.toggleable(true, role = Role.Checkbox) { note = always }.semantics { contentDescription = "$name. $sub" }
                } else {
                    Modifier.toggleable(on, role = Role.Checkbox) { v -> onIntent(MakeItYoursIntent.Button(b, v)); note = null }
                        .semantics { contentDescription = "$name. $sub" }
                }
                Pick(name, sub, if (on) Mark.ON else Mark.OFF, interaction) {
                    CytIcon(buttonIcon(b), null, tint = BoardWhite, size = 24.dp)
                }
            }
        }
        note?.let { NoteLine(it) }
        Hint(stringResource(R.string.widgets_which_hint))

        if (s.reads("top_bar_apps") && s.shows(TopButton.APPS)) {
            val appsLabel = stringResource(R.string.widgets_apps_label)
            Label(appsLabel)
            val picked = w.topBarApps.orEmpty()
            val pool: List<AppChoice> = (s.dock + s.apps.filter { a -> s.dock.none { it.pkg == a.pkg } }).distinctBy { it.pkg }.take(12)
            val full = stringResource(R.string.widgets_apps_full)
            Strip(appsLabel) {
                for (a in pool) {
                    val i = picked.indexOf(a.pkg)
                    val on = i >= 0
                    val number = if (on) msg(R.string.widgets_apps_number, "n" to (i + 1)) else null
                    Pick(
                        a.name, null, if (on) Mark.ON else Mark.OFF, number = if (on) i + 1 else null, width = 96.dp, fill = AppFill,
                        interaction = Modifier.toggleable(on, role = Role.Checkbox) { v ->
                            if (v && picked.size >= MakeItYoursEdits.MAX_TOP_APPS) appsNote = full
                            else { appsNote = null; onIntent(MakeItYoursIntent.TopApp(a.pkg, v)) }
                        }.semantics { contentDescription = a.name; if (number != null) stateDescription = number },
                    ) {
                        Text(a.name.trim().take(1).toUpperCase(Locale.current), style = BoardText.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
            appsNote?.let { NoteLine(it) }
            Hint(stringResource(R.string.widgets_apps_hint))
        }
    }

    // The Wi-Fi button: its colours, the network name with round icons, and what OK on it opens.
    if (s.shows(TopButton.WIFI) || look == ButtonsLook.MENU) {
        val green = s.reads("wifi_status_color")
        val warn = green && w.wifiStatusColor == true && s.reads("wifi_status_warnings")
        val name = look == ButtonsLook.ICONS && s.reads("wifi_label_width")
        if (green || name) {
            Label(stringResource(R.string.widgets_wifi_label))
            ListGroup {
                if (green) {
                    SwitchRow(stringResource(R.string.widgets_wifi_green), w.wifiStatusColor == true, { onIntent(MakeItYoursIntent.WifiGreen(it)) },
                        subtitle = stringResource(R.string.widgets_wifi_green_sub))
                }
                if (warn) {
                    ListDivider()
                    SwitchRow(stringResource(R.string.widgets_wifi_warn), w.wifiStatusWarnings == true, { onIntent(MakeItYoursIntent.WifiWarnings(it)) },
                        subtitle = stringResource(R.string.widgets_wifi_warn_sub))
                }
                if (name) {
                    if (green) ListDivider()
                    SwitchRow(stringResource(R.string.widgets_wifi_name), (w.wifiLabelWidth ?: 0) > 0, { onIntent(MakeItYoursIntent.WifiName(it)) },
                        subtitle = stringResource(R.string.widgets_wifi_name_sub))
                }
            }
        }
        val vpnButton = s.reads("vpn_pill") && s.shows(TopButton.VPN)
        if (!vpnButton) {
            val opensLabel = stringResource(R.string.widgets_opens_label)
            Label(opensLabel, top = 16.dp)
            val none = s.vpnApps.isEmpty()
            Chips(
                listOf(
                    ChipOption("wifi", stringResource(R.string.widgets_wifi_opens_wifi)),
                    ChipOption("vpn", stringResource(R.string.widgets_wifi_opens_vpn), enabled = !none || s.wifiOpensVpn),
                ),
                if (s.wifiOpensVpn) "vpn" else "wifi", opensLabel, { k -> onIntent(MakeItYoursIntent.WifiOpensVpn(k == "vpn")) },
            )
            if (none) Hint(stringResource(R.string.widgets_vpn_none))
            if (s.wifiOpensVpn) {
                VpnPicker(s, onIntent, Modifier.padding(top = 10.dp))
                Hint(stringResource(R.string.widgets_wifi_hold))
            }
        }
    }

    // The VPN button opens the VPN app picked here.
    if (look != ButtonsLook.MENU && s.reads("vpn_pill") && s.shows(TopButton.VPN)) {
        Label(stringResource(R.string.widgets_vpn_label))
        if (s.vpnApps.isEmpty()) Hint(stringResource(R.string.widgets_vpn_none), top = 0.dp) else VpnPicker(s, onIntent)
        Hint(stringResource(R.string.widgets_vpn_hint))
    }

    // The Settings button: what OK on it opens.
    if (s.reads("launcher_settings_button")) {
        Label(stringResource(R.string.widgets_settings_label))
        val opensLabel = stringResource(R.string.widgets_opens_label)
        Body(opensLabel, Modifier.padding(start = 4.dp, bottom = 8.dp), color = CytTheme.colors.textPrimary)
        Chips(
            listOf(ChipOption("tv", stringResource(R.string.widgets_settings_tv)), ChipOption("home", stringResource(R.string.widgets_settings_home))),
            if (w.launcherSettingsButton == true) "home" else "tv", opensLabel, { k -> onIntent(MakeItYoursIntent.SettingsOpensHome(k == "home")) },
        )
        Hint(stringResource(R.string.widgets_settings_hint))
    }
}

@Composable
private fun VpnPicker(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit, modifier: Modifier = Modifier) {
    if (s.vpnApps.isEmpty()) return
    val label = stringResource(R.string.widgets_vpn_app_label)
    Chips(s.vpnApps.map { ChipOption(it.pkg.value, it.name) }, s.shown.widgets.vpnApp, label, { onIntent(MakeItYoursIntent.VpnApp(PackageName(it))) }, modifier)
}

// Under the buttons (design editUbtn)

@Composable
private fun ColumnScope.UnderButtonsEditor(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit) {
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val captionsOn = s.shown.wallpaper.captions == true
    val switchBelow = stringResource(R.string.widgets_caption_switch)
    PlaceStrip(s, WidgetPlace.UNDER_BUTTONS, placeName(WidgetPlace.UNDER_BUTTONS), onIntent, note = note, onNote = { note = it })
    val callout = when {
        !s.captionsWork -> R.string.widgets_caption_none
        captionsOn -> R.string.widgets_caption_showing
        else -> R.string.widgets_caption_off
    }
    Banner(stringResource(callout), BannerTone.INFO, Modifier.padding(top = 12.dp))
    if (s.captionsWork && s.reads("wallpaper_captions")) {
        ListGroup(Modifier.padding(top = 12.dp)) {
            SwitchRow(switchBelow, captionsOn, { onIntent(MakeItYoursIntent.Captions(it)) }, subtitle = stringResource(R.string.widgets_caption_switch_sub))
        }
    }
}

// Before your apps (design editBefore, D5)

@Composable
private fun ColumnScope.BeforeAppsEditor(s: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit, onOpenCards: () -> Unit) {
    if (!s.addOns) {
        Hint(stringResource(R.string.widgets_cards_play), top = 20.dp)
        return
    }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    PlaceStrip(
        s, WidgetPlace.BEFORE_APPS, stringResource(R.string.widgets_cards_label), onIntent,
        onFixed = { onOpenCards() }, fixedLabel = stringResource(R.string.widgets_retro_action), note = note, onNote = { note = it },
    )
    Hint(stringResource(R.string.widgets_cards_hint))
    if (s.retroOn) Hint(stringResource(R.string.widgets_retro_hint))
    ListGroup(Modifier.padding(top = 12.dp)) { LinkRow(stringResource(R.string.widgets_cards_link), onOpenCards) }
}
