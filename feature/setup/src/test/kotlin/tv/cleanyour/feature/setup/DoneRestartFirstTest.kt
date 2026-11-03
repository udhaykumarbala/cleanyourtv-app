package tv.cleanyour.feature.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.DoneSummary
import tv.cleanyour.core.flows.Finishing
import tv.cleanyour.core.flows.RestartState
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// 0.4.9 (issue #3870, a Fire TV): the restart check runs only from Restart on Done, before Finish, and
// the person finished without it. After a home switch with the restart recommended, the line above
// Finish says to restart first until the check has run; without a switch the restart is Optional and
// there is no line.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class DoneRestartFirstTest {
    @get:Rule val compose = createComposeRule()

    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) {}
        override fun back(): Boolean = false
        override fun dismissCard() {}
    }
    private val line = "Before you finish, restart the TV to check that the new home screen stays."
    private val extras = SetupExtras(tvName = "Fire TV Stick 4K", chip = ChipState.CONNECTED)

    private fun done(home: Boolean, restart: RestartState? = null, recommended: Boolean = home) =
        SetupState.Done(Samples.miTvId(), DoneSummary(3, home, Samples.NOW_MS, emptyList(), restartRecommended = recommended), restart)

    private fun show(state: SetupState.Done, e: SetupExtras = extras) =
        compose.setContent { CytTheme(reducedMotion = true) { DoneScreen(state, e, actions) } }

    private fun absent(text: String) = assertTrue("shown: $text", compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())

    @Test fun afterAHomeSwitchFinishSaysToRestartFirst() {
        show(done(home = true))
        compose.onNodeWithText(line).assertExists()
        compose.onNodeWithText("2 minutes, recommended").performScrollTo().assertExists()
    }

    @Test fun theLineGoesOnceTheRestartRan() {
        show(done(home = true, RestartState.Back(72_000)))
        absent(line)
    }

    @Test fun theLineGoesWhileFinishing() {
        show(done(home = true), extras.copy(finishing = Finishing.SWITCHING))
        absent(line)
    }

    // A profile whose restart was tested: the switch is known to stay, so the restart is Optional and there is no line.
    @Test fun aTestedRestartNeedsNoLine() {
        show(done(home = true, recommended = false))
        absent(line)
        compose.onNodeWithText("Optional").performScrollTo().assertExists()
    }

    @Test fun withoutAHomeSwitchTheRestartIsOptionalAndThereIsNoLine() {
        show(done(home = false))
        absent(line)
        compose.onNodeWithText("Optional").performScrollTo().assertExists()
    }
}
