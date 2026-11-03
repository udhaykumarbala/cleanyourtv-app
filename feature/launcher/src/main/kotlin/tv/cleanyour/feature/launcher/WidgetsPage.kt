package tv.cleanyour.feature.launcher

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import tv.cleanyour.core.flows.MakeItYoursIntent
import tv.cleanyour.core.flows.MakeItYoursState

// Placeholder until the Widgets page lands (branch rel050-widgets replaces this file).
@Composable
internal fun ColumnScope.WidgetsContent(state: MakeItYoursState, onIntent: (MakeItYoursIntent) -> Unit, onOpenCards: () -> Unit,
    backdrop: ImageBitmap? = null, backdropArgb: Int = 0xFF101014.toInt()) {
}
