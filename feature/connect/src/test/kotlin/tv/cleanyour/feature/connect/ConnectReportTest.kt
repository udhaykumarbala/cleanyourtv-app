package tv.cleanyour.feature.connect

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
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.ConnectError
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.Families
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.ui.design.theme.CytTheme

// 0.4.6: a connect that failed (the named error since 0.4.5) and a TV that needs pairing have
// "Report a problem", which sends the card's title to the machine for the prefilled GitHub form.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ConnectReportTest {
    @get:Rule val compose = createComposeRule()

    private val reported = mutableListOf<String>()
    private val actions = object : SetupActions {
        override fun intent(intent: SetupIntent) {}
        override fun back(): Boolean = false
        override fun dismissCard() {}
        override fun report(title: String) { reported += title }
    }
    private val tv = FoundTv("192.168.1.50", "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), true, null, null, 0L)

    @Test fun aFailedConnectReportsItsCard() {
        val extras = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING, connectError = ConnectError("protocol", "summary"))
        compose.setContent { CytTheme(reducedMotion = true) { ConnectFlow(SetupState.OpenDoor(tv, Families.ATV, 2, DoorPhase.STEPS, null, null, 0), extras, actions, onReturning = {}) } }
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("The TV answered, but this phone couldn't connect"), reported)
    }

    // 0.4.8 (issues #2084, #2502, #2823): a refused connection says debugging is off on the TV and how to switch it on,
    // not that the phone is in the way; Copy error and Report a problem stay.
    @Test fun aRefusedConnectSaysDebuggingIsOffOnTheTv() {
        val extras = SetupExtras(tvName = "Mi TV 4A", chip = ChipState.WAITING, connectError = ConnectError("refused", "summary", debuggingOff = true))
        compose.setContent { CytTheme(reducedMotion = true) { ConnectFlow(SetupState.OpenDoor(tv, Families.ATV, 2, DoorPhase.STEPS, null, null, 0), extras, actions, onReturning = {}) } }
        compose.onNodeWithText("Debugging is off on the TV").performScrollTo()
        compose.onNodeWithText("then Device Preferences or System, then Developer options", substring = true).performScrollTo()
        compose.onNodeWithText("MIUI", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Copy error").performScrollTo()
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("Debugging is off on the TV"), reported)
    }

    @Test fun aTvThatNeedsPairingReportsItsCard() {
        compose.setContent { CytTheme(reducedMotion = true) { ConnectFlow(SetupState.PairingNeeded(tv), SetupExtras(), actions, onReturning = {}) } }
        compose.onNodeWithText("Report a problem").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, reported.size)
    }
}
