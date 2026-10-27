package tv.cleanyour.feature.manage

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.flows.OpenWrite
import tv.cleanyour.core.flows.OpenWriteAction
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.testing.fixtures.Samples

// QA of 0.4.5 (obs-unfinished): a send paused STALE before its first step said "We stopped partway
// through. Carry on with the rest, or undo what we did" though nothing was done. The card now says
// the TV changed since it was read and nothing was changed, and offers to check again.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class OpenWriteStaleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun open(nothingDone: Boolean) =
        OpenWrite(Samples.miTvId(), "Mi TV 4A", PlanId("01JAZ0000000000000000000"), PlanKind.THEME, PauseReason.STALE, nothingDone = nothingDone)

    private fun absent(text: String) = check(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) { "shown: $text" }

    @Test fun nothingDoneSaysNothingWasChangedAndOffersToCheckAgain() {
        val hits = mutableListOf<OpenWriteAction>()
        compose.showColumn { OpenWriteContent(open(nothingDone = true), { hits += it }, {}, {}) }
        compose.onNodeWithText("Mi TV 4A changed since we last read it").assertExists()
        compose.onNodeWithText("Nothing was changed.", substring = true).assertExists()
        absent("stopped partway")
        absent("Undo what we did")
        absent("Carry on")
        compose.tap("Check again", 0)
        assertEquals(listOf(OpenWriteAction.CARRY_ON), hits)
    }

    @Test fun afterSomeStepsItSaysSoAndKeepsUndo() {
        compose.showColumn { OpenWriteContent(open(nothingDone = false), {}, {}, {}) }
        compose.onNodeWithText("so we stopped partway through", substring = true).assertExists()
        compose.onNodeWithText("Check again").assertExists()
        compose.onNodeWithText("Undo what we did").assertExists()
        absent("Nothing was changed.")
    }
}
