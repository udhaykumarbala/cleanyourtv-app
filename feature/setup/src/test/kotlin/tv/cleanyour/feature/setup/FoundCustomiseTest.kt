package tv.cleanyour.feature.setup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.HomeBlock
import tv.cleanyour.core.flows.Sheet
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.GroupSummary
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.planner.RowSummary
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.theme.CytTheme

// 0.4.9 (issue #1222, a Freebox Player POP on an exact list, Android 10, UNKNOWN_HOME): a plan with
// nothing to switch off by default on a TV that keeps its own home screen offered only Done, so the
// rows left on to ask (TF1+, Canal+) could never be chosen. Customise is offered whenever a row is
// neither off nor protected; a plan of protected rows only still offers Done alone.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class FoundCustomiseTest {
    @get:Rule val compose = createComposeRule()

    private val sent = mutableListOf<SetupIntent>()
    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) { sent += intent }
        override fun back(): Boolean = false
        override fun dismissCard() {}
    }
    private val extras = SetupExtras(tvName = "Freebox Player POP", chip = ChipState.CONNECTED, homePossible = false, homeBlock = HomeBlock.UNKNOWN_HOME, brand = "Freebox")

    private fun row(pkg: String, name: String, protectedRow: Boolean = false) =
        RowSummary(PackageName(pkg), name, "", off = false, protected = protectedRow, whyProtected = if (protectedRow) "the TV needs it" else null)

    private fun found(vararg rows: RowSummary): SetupState.Found {
        val summary = PlanSummary(listOf(GroupSummary("apps", "Apps", 0, rows.size, rows.toList())), emptyList(), emptyList(), homeScreenPossible = false, replacesLauncher = false)
        return SetupState.Found(Samples.miTvId(), Samples.plan(emptyList()).copy(summary = summary), Level.SIMPLE, sheet = null)
    }

    private fun show(state: SetupState.Found, e: SetupExtras = extras) =
        compose.setContent { CytTheme(reducedMotion = true) { FoundScreen(state, e, actions) } }

    @Test fun anEmptyPlanWithRowsToAskOffersCustomise() {
        show(found(row("fr.tf1.mytf1", "TF1+"), row("com.canal.android.canal", "Canal+"), row("fr.freebox.qmllauncher", "Freebox home", protectedRow = true)))
        compose.onNodeWithText("Nothing to switch off").assertExists()
        compose.onNodeWithText("Some apps are left on for you to choose. Customise lists them.").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("Customise").performClick()
        compose.waitForIdle()
        assertEquals(listOf<SetupIntent>(SetupIntent.Open(Sheet.CUSTOMISE)), sent)
    }

    @Test fun anEmptyPlanOfProtectedRowsOffersDoneAlone() {
        show(found(row("fr.freebox.qmllauncher", "Freebox home", protectedRow = true)))
        compose.onNodeWithText("Done").assertExists()
        assertTrue("Customise is offered", compose.onAllNodesWithText("Customise").fetchSemanticsNodes().isEmpty())
        assertTrue("the choices line is shown", compose.onAllNodesWithText("Some apps are left on for you to choose. Customise lists them.").fetchSemanticsNodes().isEmpty())
    }

    // RFC-HELPER-1 (test build, #251): the helper still opens Customise on a plan with no rows at all.
    @Test fun theHelperStillOffersCustomiseOnAnEmptyPlan() {
        show(found(), extras.copy(helperOffer = true))
        compose.onNodeWithText("Customise").assertExists()
        assertTrue("the choices line is shown", compose.onAllNodesWithText("Some apps are left on for you to choose. Customise lists them.").fetchSemanticsNodes().isEmpty())
    }
}
