package tv.cleanyour.feature.manage

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.Reach
import tv.cleanyour.core.flows.ReachCheck
import tv.cleanyour.core.flows.Seen
import tv.cleanyour.testing.fixtures.Samples

// 0.4.8 (beta4 follow-up): the debugging step's "Enter the TV's address" opens Setup's address entry for this TV.
// An address that is not on a home network is refused there with Setup's words; a good one goes back to the step.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class ReachAddressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val stuck = ReachCheck(Seen.YES, Seen.YES, Seen.NO, Seen.NO, Seen.NO, waitedMs = 34_000)

    private fun absent(text: String) = check(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) { "shown: $text" }

    private fun show(reach: Reach, check: ReachCheck, onAddress: (String) -> Boolean) = compose.showColumn {
        var typing by remember { mutableStateOf(false) }
        ReachSheetBody(reach, check, {}, {}, DetailsUi(), {}, onAddress, typing) { typing = it }
    }

    @Test fun theButtonOpensTheAddressEntryAndAGoodAddressGoesBackToTheStep() {
        val typed = mutableListOf<String>()
        show(Reach.NeedsDebugging(Samples.miTvId(), "Mi TV 4A"), stuck) { typed += it; it.startsWith("192.168.") }
        compose.tap("Enter the TV's address", 0)
        compose.onNodeWithText("Type the TV's address").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("8.8.8.8")
        compose.tap("Connect", 0)
        compose.onNodeWithText("That isn't an address on your home network").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("192.168.1.50")
        compose.tap("Connect", 0)
        assertEquals(listOf("8.8.8.8", "192.168.1.50"), typed)
        absent("Type the TV's address")
        compose.onNodeWithText("Enter the TV's address").assertExists()
    }

    @Test fun backLeavesTheAddressEntryWithoutTryingAnything() {
        val typed = mutableListOf<String>()
        show(Reach.NeedsDebugging(Samples.miTvId(), "Mi TV 4A"), stuck) { typed += it; true }
        compose.tap("Enter the TV's address", 0)
        compose.tap("Back", 0)
        absent("Type the TV's address")
        assertEquals(emptyList<String>(), typed)
    }

    @Test fun notOnWifiOffersNoAddress() {
        show(Reach.WrongNetwork(Samples.miTvId(), "Mi TV 4A"), stuck.copy(onWifi = Seen.NO)) { true }
        compose.tap("Show details", 0)
        absent("Enter the TV's address")
    }

    @Test fun theTypedRowSaysWhetherTheTvAnswersThere() {
        show(Reach.NeedsDebugging(Samples.miTvId(), "Mi TV 4A"), stuck.copy(typed = Seen.NO)) { true }
        compose.tap("Show details", 0)
        compose.onNodeWithText("The TV answers at the address you typed").assertExists()
        compose.onNodeWithText("Check the address on the TV", substring = true).assertExists()
    }
}
