package tv.cleanyour.feature.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.StopCard
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// 0.4.6 (REPORTING-PROPOSAL 2.2): "Report a problem" under a stop card sends the card's own title to
// the machine, which opens the prefilled GitHub form; it acts on nothing and asks nothing first.
// The stop the person chose (Undo) has no such button.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ReportThisTest {
    @get:Rule val compose = createComposeRule()

    private val reported = mutableListOf<String>()
    private val sent = mutableListOf<SetupIntent>()
    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) { sent += intent }
        override fun back(): Boolean = false
        override fun dismissCard() {}
        override fun report(title: String) { reported += title }
    }
    private val extras = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.OFFLINE)

    private fun card(case: StopCase, title: String) = StopCard(case, title, "Usually it's back in a few seconds.", "Carry on with the rest", "Stop here", planId = null)

    @Test fun aStoppedRunReportsItsCard() {
        var shown by mutableStateOf(card(StopCase.CONNECTION_LOST, ""))
        compose.setContent { CytTheme(reducedMotion = true) { StoppedScreen(SetupState.Stopped(Samples.miTvId(), shown), extras, actions) } }
        for ((case, title) in listOf(StopCase.CONNECTION_LOST to "The TV stopped answering", StopCase.HOME_TIMED_OUT to "No answer, so we put it back")) {
            reported.clear()
            shown = card(case, title)
            compose.waitForIdle()
            compose.onNodeWithText("Report a problem").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(title), reported)
            assertEquals(emptyList<SetupIntent>(), sent)
        }
    }

    @Test fun anUndoTheyChoseHasNoReport() {
        compose.setContent { CytTheme(reducedMotion = true) { StoppedScreen(SetupState.Stopped(Samples.miTvId(), card(StopCase.UNDONE, "Undone")), extras, actions) } }
        compose.onNodeWithText("Report a problem").assertDoesNotExist()
    }
}
