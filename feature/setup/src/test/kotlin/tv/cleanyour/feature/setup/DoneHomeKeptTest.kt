package tv.cleanyour.feature.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.engine.HomeOutcome
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// QA of 0.4.5 (bug-040-retry): the home check ran out of time, the old home screen went back, and
// Done said only "Your TV is ready" and a count. It now says the home screen is unchanged and why.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class DoneHomeKeptTest {
    @get:Rule val compose = createComposeRule()

    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) {}
        override fun back(): Boolean = false
        override fun dismissCard() {}
    }
    private val done = SetupState.Done(Samples.miTvId(), DoneSummary(1, false, Samples.NOW_MS, emptyList(), false), null)
    private val timedOut = "The home screen is unchanged: the check on the TV ran out of time, so the old home screen is back. You can try again from Manage."

    private fun show(extras: SetupExtras) =
        compose.setContent { CytTheme(reducedMotion = true) { DoneScreen(done, extras, actions) } }

    @Test fun aTimedOutCheckSaysTheHomeScreenIsUnchangedAndWhy() {
        show(SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED, doneHomeKept = HomeOutcome.TIMED_OUT))
        compose.onNodeWithText(timedOut).performScrollTo().assertExists()
    }

    @Test fun aNoSaysSo() {
        show(SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED, doneHomeKept = HomeOutcome.USER_SAID_NO))
        compose.onNodeWithText("The home screen is unchanged: you said the new one didn't look right, so the old home screen is back.").performScrollTo().assertExists()
    }

    @Test fun withoutATriedSwitchThereIsNoLine() {
        show(SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED))
        check(compose.onAllNodesWithText(timedOut).fetchSemanticsNodes().isEmpty()) { "the line is shown" }
    }
}
