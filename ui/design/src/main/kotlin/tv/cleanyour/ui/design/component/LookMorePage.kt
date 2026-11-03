package tv.cleanyour.ui.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.LookReason
import tv.cleanyour.core.flows.LookRow
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.ui.design.R
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme

// More options (prototype pageMore): Movement, Big name, Glow and dock (Spotlight: App colour
// across the top), Top of the screen, Picture, "Use {style}'s own settings" with its count, then
// the Advanced link. Every row comes from the flows' LookRows: a disabled row shows the value the
// TV will really use, checked, with the reason, and never a "You changed this" line (owner).
// Home screen 3.0 (ExtraRows): None for the dock band, the greeting and
// date rows, Warn when the internet is down under Green Wi-Fi and the Tiles section; on a TV below
// 3.0 with the update offered, the footer line and its link instead.
@Composable
internal fun ColumnScope.LookMorePage(state: LookEditorState, onEdit: (LookEdit) -> Unit, onAdvanced: () -> Unit,
    onUpdateHomeScreen: (() -> Unit)? = null) {
    val keys = state.keyNames
    val x = ExtraRows(state)
    LookSection(stringResource(R.string.cyt_look_more_movement), Modifier.padding(top = 0.dp))
    val lowRam = state.ramMb?.takeIf { it <= LookGates.LOW_RAM_MB }
    val suggested = lowRam?.let { lookMsg(R.string.cyt_look_motion_suggested, "size" to memoryWords(it)) }
    LookRadioList(
        listOf(
            RadioChoice(Movement.LIVELY.id, stringResource(R.string.cyt_look_motion_lively), stringResource(R.string.cyt_look_motion_lively_desc)),
            RadioChoice(Movement.SMOOTH.id, stringResource(R.string.cyt_look_motion_smooth), stringResource(R.string.cyt_look_motion_smooth_desc)),
            RadioChoice(Movement.QUICK.id, stringResource(R.string.cyt_look_motion_quick), stringResource(R.string.cyt_look_motion_quick_desc), suggested),
            RadioChoice(Movement.OFF.id, stringResource(R.string.cyt_look_motion_off), stringResource(R.string.cyt_look_motion_off_desc)),
        ),
        state.movement.id, { id -> Movement.of(id)?.let { onEdit(LookEdit.SetMovement(it)) } },
        keyName = KEY_NAMES["motion"].takeIf { keys },
    )

    val r = state.rows ?: return
    LookSection(stringResource(R.string.cyt_look_more_big_name))
    ListGroup {
        LookSwitchRow(
            stringResource(R.string.cyt_look_big_name), r.bigName.value ?: false, { onEdit(LookEdit.SetBigName(it)) },
            description = stringResource(R.string.cyt_look_big_name_desc).takeIf { r.bigName.enabled }, enabled = r.bigName.enabled,
            lines = reasons(r.bigName), keyName = KEY_NAMES["bigName"].takeIf { keys },
        )
    }

    LookSection(stringResource(R.string.cyt_look_more_glow_dock))
    if (r.spotColour.visible) {
        ListGroup {
            LookSwitchRow(
                stringResource(R.string.cyt_look_spot_colour), r.spotColour.value ?: false, { onEdit(LookEdit.SetSpotColour(it)) },
                description = stringResource(R.string.cyt_look_spot_colour_desc), enabled = r.spotColour.enabled,
                lines = reasons(r.spotColour), keyName = KEY_NAMES["spotGlow"].takeIf { keys },
            )
        }
    }
    if (r.glow.visible) {
        LookSublabel(stringResource(R.string.cyt_look_glow))
        LookRadioList(
            listOf(
                RadioChoice(GlowChoice.SOFT.id, stringResource(R.string.cyt_look_glow_soft), stringResource(R.string.cyt_look_glow_soft_desc)),
                RadioChoice(GlowChoice.FROM_TOP.id, stringResource(R.string.cyt_look_glow_top), stringResource(R.string.cyt_look_glow_top_desc)),
                RadioChoice(GlowChoice.NONE.id, stringResource(R.string.cyt_look_glow_none), stringResource(R.string.cyt_look_glow_none_desc)),
            ),
            r.glow.value?.id, { id -> GlowChoice.of(id)?.let { onEdit(LookEdit.SetGlow(it)) } },
            enabled = r.glow.enabled, reason = r.glow.reason?.let { reasonText(it) }, keyName = KEY_NAMES["glow"].takeIf { keys },
        )
    }
    if (r.ring.visible) {
        val festive = if (r.festiveRing) lookMsg(R.string.cyt_look_ring_festival, "picture" to festivalName(state)) else null
        ListGroup(Modifier.padding(top = 12.dp)) {
            LookSwitchRow(
                stringResource(R.string.cyt_look_ring), r.ring.value ?: false, { onEdit(LookEdit.SetRing(it)) },
                description = stringResource(R.string.cyt_look_ring_desc), enabled = r.ring.enabled,
                lines = reasons(r.ring) + listOfNotNull(festive), keyName = KEY_NAMES["ring"].takeIf { keys },
            )
        }
    }
    if (r.band.visible) {
        LookSublabel(stringResource(R.string.cyt_look_band))
        // Home screen 3.0 adds None (dock_band false); choosing a band again turns it back on.
        val none = RadioChoice(ExtraRows.BAND_NONE, stringResource(R.string.cyt_look_band_none), stringResource(R.string.cyt_look_band_none_desc))
            .takeIf { x.bandOffered }
        LookRadioList(
            listOfNotNull(
                RadioChoice(BandChoice.SOLID.id, stringResource(R.string.cyt_look_band_solid), stringResource(R.string.cyt_look_band_solid_desc)),
                RadioChoice(BandChoice.SEE_THROUGH.id, stringResource(R.string.cyt_look_band_see), stringResource(R.string.cyt_look_band_see_desc)),
                RadioChoice(BandChoice.GLASS.id, stringResource(R.string.cyt_look_band_glass), stringResource(R.string.cyt_look_band_glass_desc)),
                none,
            ),
            if (x.noBandOn) ExtraRows.BAND_NONE else r.band.value?.id,
            { id ->
                if (id == ExtraRows.BAND_NONE) {
                    x.edit(onEdit) { copy(noBand = true) }
                } else {
                    BandChoice.of(id)?.let { onEdit(if (x.noBandOn) LookEdit.SetBandBack(it) else LookEdit.SetBand(it)) }
                }
            },
            enabled = r.band.enabled, reason = r.band.reason?.let { reasonText(it) },
            keyName = KEY_NAMES[if (x.bandOffered) "band30" else "band"].takeIf { keys },
        )
    }

    // 0.5.0 Make it yours: the greeting, the date and the top buttons (their look, green Wi-Fi and its warnings)
    // moved to Widgets, next to the switches that show them. A look no longer sets them on a TV whose file has them.
    LookSection(stringResource(R.string.cyt_look_more_top))
    Text(stringResource(R.string.cyt_look_moved_widgets), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        color = CytTheme.colors.textSecondary, style = CytTheme.type.bodyMedium)

    if (x.tiles) {
        LookSection(stringResource(R.string.cyt_look_more_tiles))
        LookSublabel(stringResource(R.string.cyt_look_tiles))
        LookRadioList(
            listOf(
                RadioChoice(TilesChoice.ART.id, stringResource(R.string.cyt_look_tiles_art), stringResource(R.string.cyt_look_tiles_art_desc)),
                RadioChoice(TilesChoice.ICONS.id, stringResource(R.string.cyt_look_tiles_icons), stringResource(R.string.cyt_look_tiles_icons_desc)),
            ),
            x.acting.tiles.id, { id -> TilesChoice.entries.firstOrNull { it.id == id }?.let { t -> x.edit(onEdit) { copy(tiles = t) } } },
            keyName = KEY_NAMES["tiles"].takeIf { keys },
        )
    }

    LookSection(stringResource(R.string.cyt_look_more_picture))
    ListGroup {
        if (r.dim.visible) {
            LookSliderRow(
                stringResource(R.string.cyt_look_darken), r.dim.value ?: 0, 0..60, 10, { onEdit(LookEdit.SetDim(it)) },
                enabled = r.dim.enabled, reason = r.dim.reason?.let { reasonText(it) }, keyName = KEY_NAMES["dim"].takeIf { keys },
            )
            ListDivider()
        }
        if (r.sky.visible) {
            LookSwitchRow(
                stringResource(R.string.cyt_look_sky), r.sky.value ?: false, { onEdit(LookEdit.SetSky(it)) },
                description = stringResource(R.string.cyt_look_sky_desc), enabled = r.sky.enabled,
                lines = reasons(r.sky), keyName = KEY_NAMES["sky"].takeIf { keys },
            )
        }
    }

    // "Use {style}'s own settings": an outlined button that names what it does, with the count.
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedPill(
            lookMsg(R.string.cyt_look_reset, "style" to styleName(state.style)), { onEdit(LookEdit.ResetDetails) }, Modifier.weight(1f, fill = false),
            enabled = r.resettable > 0,
        )
        if (r.resettable > 0) {
            Text(lookMsg(R.string.cyt_look_reset_count, "count" to r.resettable), color = CytTheme.colors.textSecondary, style = CytTheme.type.bodySmall)
        }
    }
    ListGroup(Modifier.padding(top = 14.dp)) {
        ListRow(stringResource(R.string.cyt_look_advanced), subtitle = stringResource(R.string.cyt_look_advanced_desc), onClick = onAdvanced)
    }
    // A TV below home screen 3.0, with the update offered: one line and the update route.
    if (!state.reads30 && onUpdateHomeScreen != null) {
        LookBody(stringResource(R.string.cyt_look_needs_30), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
        TertiaryButton(stringResource(R.string.cyt_look_needs_30_action), onUpdateHomeScreen, Modifier.padding(start = 4.dp))
    }
}

// The festival picture's name for the ring line ("While Diwali runs, ...").
@Composable
private fun festivalName(state: LookEditorState): String =
    (state.pictures.firstOrNull { it.id == state.picture } ?: state.pictures.firstOrNull { it.until != null })?.let { galleryPictureName(it.id, it.name) }.orEmpty()

@Composable
private fun reasons(row: LookRow<*>): List<String> = listOfNotNull(row.reason?.takeUnless { row.enabled }?.let { reasonText(it) })

// Spec 7.3: why a row cannot be changed, in the app's words.
@Composable
internal fun reasonText(r: LookReason): String = stringResource(
    when (r) {
        LookReason.NEEDS_UPDATE -> R.string.cyt_look_needs_update
        LookReason.ALL_APPS_NO_BIG_NAME -> R.string.cyt_look_big_name_all_apps
        LookReason.SPOTLIGHT_BIG_NAME -> R.string.cyt_look_big_name_spotlight
        LookReason.THREE_ROWS -> R.string.cyt_look_big_name_three_rows
        LookReason.ALL_APPS_NO_BAND -> R.string.cyt_look_band_all_apps
        LookReason.SPOTLIGHT_BAND -> R.string.cyt_look_band_spotlight
        LookReason.MENU_NO_WIFI -> R.string.cyt_look_wifi_green_menu
        LookReason.NO_PICTURE -> R.string.cyt_look_darken_none
        LookReason.LIFT_MOVEMENT -> R.string.cyt_look_lift_needs_motion
        LookReason.SEE_THROUGH_OFF -> R.string.cyt_look_see_amount_off
        LookReason.SKY_OFF -> R.string.cyt_look_sky_strength_off
    },
)

// "Show launcher names": each row's keys as the home screen's settings file spells them. Code,
// not copy: they stay the same in every language. Show launcher names itself has no key; its line,
// "(phone only)", is copy (cyt_look_key_names_phone_only).
internal val KEY_NAMES = mapOf(
    "motion" to "animations, reduced_motion, focus_overshoot", "names" to "always_show_names", "spotGlow" to "ambient_glow",
    "bigName" to "dock_title", "glow" to "ambient_glow, glow_style", "ring" to "app_glow, cinematic_white_focus",
    "band" to "dock_glass, dock_opacity", "buttons" to "icon_only_buttons, icon_only_inputs, top_menu", "wifi" to "wifi_status_color",
    "dim" to "wallpaper_dim", "sky" to "sky_intensity", "lift" to "advanced_effects", "see" to "dock_opacity", "skyStr" to "sky_intensity",
    // Home screen 3.0.
    "oneRow" to "single_row", "band30" to "dock_glass, dock_opacity, dock_band", "greeting" to "show_greeting",
    "dateBelow" to "date_below_clock", "brightDate" to "header_white", "wifiWarn" to "wifi_status_warnings", "tiles" to "uniform_tiles",
    "highlight" to "glass_style",
)

// A launcher key name under a row, in mono and always left to right.
@Composable
internal fun KeyNameLine(text: String, modifier: Modifier = Modifier) {
    LtrText(text, modifier.padding(top = 4.dp), style = CytTheme.type.mono, color = CytTheme.colors.textSecondary)
}

// A smaller label inside a section ("Glow", "Dock band", "Buttons").
@Composable
internal fun LookSublabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        color = CytTheme.colors.textPrimary, style = CytTheme.type.titleMedium)
}

// A switch row of the Look pages: the title, its line, any reason or warning lines and the key
// name. The whole row toggles and reports on or off; a disabled row shows the value the TV will
// use and reads its reason.
@Composable
internal fun LookSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    lines: List<String> = emptyList(),
    warning: String? = null,
    keyName: String? = null,
) {
    val c = CytTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // Disabled (the prototype's .45): the title and its line dim, the reason stays readable.
            Column(Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
                Text(title, color = c.textPrimary, style = CytTheme.type.bodyLarge)
                if (description != null) Text(description, color = c.textSecondary, style = CytTheme.type.bodySmall)
            }
            for (l in lines) Text(l, Modifier.padding(top = 2.dp), color = c.textSecondary, style = CytTheme.type.bodySmall)
            if (warning != null) Text(warning, Modifier.padding(top = 2.dp), color = c.warning, style = CytTheme.type.bodySmall)
            if (keyName != null) KeyNameLine(keyName)
        }
        CytSwitch(checked, null, Modifier.clearAndSetSemantics {}, enabled = enabled)
    }
}

// How far a disabled row dims (the prototype: .45 for a switch row's title and track, .42 for the
// choices of a radio list).
internal const val DISABLED_ALPHA = 0.45f
internal const val DISABLED_CHOICE_ALPHA = 0.42f

// One choice of a radio list: an id, the title, its line and an optional extra line ("Suggested
// for this TV: ...").
@Immutable
internal data class RadioChoice(val id: String, val title: String, val description: String, val extra: String? = null)

// A radio list under its label (Movement, Glow, Dock band, Buttons): each row reads its title, its
// line and its extra line; the chosen one has a check. Disabled: the value the TV will use stays
// checked, the reason shows above the list, and every row reads it as its state.
@Composable
internal fun LookRadioList(
    choices: List<RadioChoice>,
    selected: String?,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    reason: String? = null,
    keyName: String? = null,
) {
    val c = CytTheme.colors
    if (!enabled && reason != null) LookBody(reason, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp))
    ListGroup(Modifier.selectableGroup()) {
        choices.forEachIndexed { i, ch ->
            if (i > 0) ListDivider()
            val on = ch.id == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(ch.id) }
                    .semantics(mergeDescendants = true) { if (!enabled && reason != null) stateDescription = reason }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Disabled (the prototype's .42): every choice dims; the reason above stays readable.
                Column(Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_CHOICE_ALPHA)) {
                    Text(ch.title, color = c.textPrimary, style = CytTheme.type.bodyLarge)
                    Text(ch.description, color = c.textSecondary, style = CytTheme.type.bodySmall)
                    ch.extra?.let { Text(it, Modifier.padding(top = 2.dp), color = c.textSecondary, style = CytTheme.type.bodySmall) }
                }
                Box(Modifier.size(24.dp).alpha(if (!on) 0f else if (enabled) 1f else DISABLED_CHOICE_ALPHA), contentAlignment = Alignment.Center) {
                    CytIcon(CytIcons.Check, null, tint = c.accent)
                }
            }
        }
    }
    if (keyName != null) KeyNameLine(keyName, Modifier.padding(start = 4.dp))
}

// A slider row: the title and the value ("30%"), the reason when it is off, the key name, then the
// slider in steps. It reads "Darken the picture, 30 percent".
@Composable
internal fun LookSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    onValue: (Int) -> Unit,
    enabled: Boolean = true,
    reason: String? = null,
    keyName: String? = null,
) {
    val c = CytTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA), color = c.textPrimary, style = CytTheme.type.bodyLarge)
            Text(lookMsg(R.string.cyt_look_percent, "value" to value), Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA), color = c.textSecondary,
                style = CytTheme.type.bodyMedium)
        }
        if (!enabled && reason != null) Text(reason, color = c.textSecondary, style = CytTheme.type.bodySmall)
        if (keyName != null) KeyNameLine(keyName)
        CytSlider(
            value, { v -> if (v != value) onValue(v) }, range, step, title, lookMsg(R.string.cyt_look_percent_cd, "value" to value),
            enabled = enabled,
        )
    }
}
