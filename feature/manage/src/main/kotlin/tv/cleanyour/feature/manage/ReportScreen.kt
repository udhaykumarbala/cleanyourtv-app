package tv.cleanyour.feature.manage

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.flows.ReportIntent
import tv.cleanyour.core.flows.ReportKind
import tv.cleanyour.core.flows.ReportState
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.Gap
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.MonospaceBlock
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.Glow

// 0.3.2 B1 and B2: the report preview, "Help add this TV" and "Report a problem". It shows exactly
// the text that would be copied or shared, says plainly that a GitHub post is public and that the
// app sends nothing itself, then offers the two ways out: copy it and open the GitHub form (the
// browser opens the link; the app makes no request), or the share sheet. The same in both builds.
// 0.3.3 D1: when the report fits in the link the form opens with it filled in and the button says
// "Open GitHub"; when it does not, a line above the report says so before GitHub opens, and the
// person pastes it as in 0.3.2. 0.4.6: a problem report always fits (ProblemReports.fit).
@Composable
fun ReportScreen(state: ReportState, onIntent: (ReportIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tvReport = state.kind == ReportKind.TV
    val ready = !state.loading && state.available
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { CytTopBar(onBack = onBack) },
        bottomBar = if (!ready) null else ({
            ActionColumn {
                // Neither button writes to the TV: copying, the browser and the share sheet are the phone's.
                PrimaryButton(stringResource(if (state.inLink) R.string.report_open else R.string.report_copy_open), { onIntent(ReportIntent.CopyAndOpen) }, guardObscured = false)
                SecondaryButton(stringResource(R.string.report_share), { onIntent(ReportIntent.Share) })
            }
        }),
    ) {
        ScreenTitle(stringResource(if (tvReport) R.string.report_tv_title else R.string.report_problem_title), Modifier.padding(top = 20.dp))
        when {
            state.loading -> LiveLine(stringResource(R.string.report_loading))
            !state.available -> Lead(stringResource(R.string.report_unavailable), Modifier.padding(top = 12.dp))
            else -> {
                Lead(stringResource(if (tvReport) R.string.report_tv_body else R.string.report_problem_body), Modifier.padding(top = 12.dp))
                Banner(stringResource(R.string.report_public), BannerTone.INFO, Modifier.padding(top = 16.dp), icon = CytIcons.Info)
                // Before the report, so it is read before GitHub opens however long the report is.
                if (!state.inLink) Body(stringResource(R.string.report_too_long), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
                SectionLabel(stringResource(R.string.report_preview_label))
                // Wrapped and one text node: no sideways scrolling on a phone, and TalkBack reads it as one block.
                MonospaceBlock(state.text.trimEnd().lines(), wrap = true)
                // In the scrolling content, not the bottom bar, so at large font sizes the report keeps the room.
                // 0.4.6: a long problem report goes into the form shorter (its newest log lines), so it says so.
                val hint = when {
                    !state.inLink -> R.string.report_paste_hint
                    state.shortened -> R.string.report_in_link_short_hint
                    else -> R.string.report_in_link_hint
                }
                Body(stringResource(hint), Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp))
                Gap(16.dp)
            }
        }
    }
}
