package tv.cleanyour.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.ui.design.component.Spinner
import tv.cleanyour.ui.design.component.TvStatus
import tv.cleanyour.ui.design.theme.CytTheme

// Small helpers the screens of this module share; everything visual comes from :ui:design.

// copy: ICU MessageFormat with named arguments (strings.xml rule)
@Composable
fun msg(id: Int, vararg args: Pair<String, Any>): String {
    val pattern = stringResource(id)
    if (args.isEmpty()) return pattern
    val locale = LocalConfiguration.current.locales[0]
    return remember(pattern, locale, args.toList()) {
        android.icu.text.MessageFormat(pattern, locale).format(args.toMap())
    }
}

// The flows' chip state as the design system's status (the same five words, D42).
fun ChipState.toTvStatus(): TvStatus = when (this) {
    ChipState.CONNECTED -> TvStatus.CONNECTED
    ChipState.WAITING -> TvStatus.WAITING
    ChipState.WORKING -> TvStatus.WORKING
    ChipState.DEBUGGING_OFF -> TvStatus.DEBUGGING_OFF
    ChipState.OFFLINE -> TvStatus.OFFLINE
}

// A stop card's primary drops touches through an overlay (SECURITY 4.3) when it acts: carries on,
// tries again, retakes Home, reads the TV. Not when it is Back, a plain OK or a way to another
// screen (R5-W8 O1).
// 0.4.7 (UX finding 16): the wizard's Wi-Fi card keeps the TV's own home screen first; its second
// button tries the switch again.
fun StopCase.primaryActs(): Boolean = when (this) {
    StopCase.WRONG_NETWORK, StopCase.IDENTITY_CHANGED, StopCase.TV_NOT_SUPPORTED,
    StopCase.TV_REFUSES_HOME, StopCase.PAIRING_NEEDED, StopCase.HOME_TIMED_OUT,
    StopCase.INSTALL_FAILED, StopCase.UNDONE, StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_KEY,
    -> false
    else -> true
}

// The same rule for the second and third buttons (R4-W8): "Undo what we did" rolls the run back,
// "Stop here" puts the TV's own home screen back when a home section is open, "Try again" retakes
// Home. "Leave it as is", "Keep {launcher}", "Show details", Back and OK do not write.
fun StopCase.secondaryActs(): Boolean = when (this) {
    StopCase.CONNECTION_LOST, StopCase.STEP_FAILED, StopCase.RESUME_AFTER_DEATH, StopCase.HOME_TIMED_OUT, StopCase.HOME_SETUPWRAITH, StopCase.HOME_OTHER_KEY -> true
    else -> false
}

fun StopCase.tertiaryActs(): Boolean = when (this) {
    StopCase.CONNECTION_LOST, StopCase.STEP_FAILED -> true
    else -> false
}

// One paragraph in text.secondary.
@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, color: Color = CytTheme.colors.textSecondary) =
    Text(text, modifier, color = color, style = CytTheme.type.bodyMedium)

// A spinner and a status sentence ("Waiting for the TV..."), a polite live region.
@Composable
internal fun StatusLine(text: String, modifier: Modifier = Modifier, busy: Boolean = true) {
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) Spinner()
        Text(text, Modifier.weight(1f), color = CytTheme.colors.textPrimary, style = CytTheme.type.bodyMedium)
    }
}
