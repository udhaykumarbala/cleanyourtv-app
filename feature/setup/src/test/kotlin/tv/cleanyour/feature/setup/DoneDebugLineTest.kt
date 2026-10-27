package tv.cleanyour.feature.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// D7 on Done (baseline QA, 0.4.0): with "Keep debugging on" chosen the screen said "Debugging on
// the TV switches off when you tap Finish", though Finish leaves it on. The line says what Finish
// will do.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class DoneDebugLineTest {
    @get:Rule val compose = createComposeRule()

    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) {}
        override fun back(): Boolean = false
        override fun dismissCard() {}
    }
    private val done = SetupState.Done(Samples.miTvId(), DoneSummary(23, true, Samples.NOW_MS, emptyList(), false), null)

    private fun show(extras: SetupExtras) =
        compose.setContent { CytTheme(reducedMotion = true) { DoneScreen(done, extras, actions) } }

    @Test fun keepDebuggingOnSaysItStaysOn() {
        show(SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED, keepsDebugging = true))
        compose.onNodeWithText("Debugging on the TV stays on, as you chose").assertExists()
        compose.onAllNodesWithText("Debugging on the TV switches off when you tap Finish").fetchSemanticsNodes().let { check(it.isEmpty()) { "the switch off line is shown" } }
    }

    @Test fun otherwiseFinishSwitchesItOff() {
        show(SetupExtras(tvName = "Mi TV 4A", chip = ChipState.CONNECTED))
        compose.onNodeWithText("Debugging on the TV switches off when you tap Finish").assertExists()
        compose.onAllNodesWithText("Debugging on the TV stays on, as you chose").fetchSemanticsNodes().let { check(it.isEmpty()) { "the stays on line is shown" } }
    }
}
