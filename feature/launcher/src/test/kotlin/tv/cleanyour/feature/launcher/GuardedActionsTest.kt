package tv.cleanyour.feature.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import tv.cleanyour.core.flows.LookDetails
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.spec.BandChoice
import tv.cleanyour.core.spec.ButtonsChoice
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.ui.design.component.LookDoneFooter
import tv.cleanyour.ui.design.component.LookEditor
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookEditorState
import tv.cleanyour.ui.design.component.LookPage
import tv.cleanyour.ui.design.component.toEditor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.AppChoice
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.ui.design.component.DockApp
import tv.cleanyour.ui.design.component.DockEditorState

// R5-W8 O1 as ruled: applying the dock and switching to the TV's own home screen drop a tap
// through another app's overlay; Cancel and the dock's up, down and remove buttons never do.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class GuardedActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun useTheTvsOwnIsGuardedAndCancelIsNot() {
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { StockPreviewContent(Samples.plan(emptyList())) { hits += it } }
        compose.tap("Use the TV's own", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CancelPreview), hits)
    }

    @Test fun updateIsGuardedAndCancelIsNot() {
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { UpdatePreviewContent(Samples.plan(emptyList()), "2.4") { hits += it } }
        compose.tap("Update", OBSCURED)
        compose.tap("Cancel", OBSCURED)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CancelPreview), hits)
        compose.tap("Update", 0)
        assertEquals(listOf(HomeScreenIntent.CancelPreview, HomeScreenIntent.UpdateHomeScreen), hits)
    }

    @Test fun dockDoneIsGuardedAndEditingIsNot() {
        val netflix = AppChoice(PackageName("com.netflix.ninja"), "Netflix")
        val state = DockEditorState(listOf(netflix.toDockApp()), 4)
        var done = 0
        compose.showColumn { DockContent(state, listOf(netflix), { done++ }) }
        compose.tap("Done", OBSCURED)
        assertEquals(0, done)
        compose.tap(compose.onNodeWithContentDescription("Take Netflix out"), OBSCURED)
        assertEquals(emptyList<DockApp>(), state.apps)
        compose.tap("Done", 0)
        assertEquals(1, done)
    }

    // look2 Look sheet: on each page "Put it on the TV" is the one guarded write (a tap through an
    // overlay is dropped), and every other control only chooses: it sends its own LookEdit, also
    // through an overlay, and never a send.
    @Test fun everyLookPageHasOneGuardedPrimaryAndEveryControlSendsOnlyItsEdit() {
        val hits = mutableListOf<HomeScreenIntent>()
        val nav = LookEditorNav()
        val state = HomeScreenState(tvName = "Mi TV 4A", dock = LookFixtures.dock4, pictures = LookFixtures.pictureOptions,
            look = LookFixtures.draft(resettable = 1, wayBack = WayBack.UNDO, changed = true, previews = false))
        compose.showColumn { LookContent(state, { hits += it }, nav = nav) }
        fun tapText(text: String, flags: Int = OBSCURED) = compose.tap(compose.onNodeWithText(text).inView(), flags)
        fun tapLabel(label: String, flags: Int = OBSCURED) = compose.tap(compose.onNodeWithContentDescription(label).inView(), flags)
        fun expect(vararg edits: LookEdit) = assertEquals(edits.map { HomeScreenIntent.Look(it) }, hits.toList()).also { hits.clear() }

        // Look
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(1)
        tapText("Aurora")
        tapLabel("Glass. A frosted dock and light in each app's colour.")
        tapLabel("6 apps in a row")
        tapText("Names under every app")
        tapText("Undo changes")
        expect(LookEdit.SelectPicture("aurora"), LookEdit.SelectStyle(StyleId.GLASS), LookEdit.SelectColumns(6), LookEdit.SetNames(true), LookEdit.UndoChanges)
        tapText("Put it on the TV")
        expect()

        // More options
        tapText("More options")
        assertEquals(LookPage.MORE, nav.page)
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(1)
        tapText("Smooth")
        tapText("Big name above the apps")
        tapText("From the top")
        tapText("Ring in the app's colour")
        tapText("Glass")
        // 0.5.0: the buttons' look and green Wi-Fi moved to Widgets in Make it yours.
        compose.onAllNodesWithText("Round icons").assertCountEquals(0)
        compose.onAllNodesWithText("Green Wi-Fi when online").assertCountEquals(0)
        tapText("Sky that follows the clock")
        tapText("Use Classic's own settings")
        expect(
            LookEdit.SetMovement(Movement.SMOOTH), LookEdit.SetBigName(false), LookEdit.SetGlow(GlowChoice.FROM_TOP), LookEdit.SetRing(true),
            LookEdit.SetBand(BandChoice.GLASS), LookEdit.SetSky(true),
            LookEdit.ResetDetails,
        )
        tapText("Put it on the TV")
        expect()

        // Advanced
        tapText("Advanced")
        assertEquals(LookPage.ADVANCED, nav.page)
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(1)
        tapText("Lift and zoom")
        tapText("Show launcher names")
        tapText("Copy")
        expect(LookEdit.SetLift(true), LookEdit.SetKeyNames(true))
        tapText("Put it on the TV")
        expect()
        tapText("Put it on the TV", 0)
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.UseLook), hits)
    }

    // The controls the test above does not reach: Put back the earlier look, Turn it off (a photo
    // under a glow) and the See-through segments also send only their own edits, through an overlay too.
    @Test fun theEarlierLookTheTintLineAndSeeThroughOnlyEdit() {
        val hits = mutableListOf<HomeScreenIntent>()
        val nav = LookEditorNav()
        val mine = LookFixtures.gallery.getValue("aurora").wallpaper.let { w -> w.copy(path = LookFixtures.resourcePath(w)!!) }
        val state = HomeScreenState(tvName = "Mi TV 4A", dock = LookFixtures.dock4, pictures = LookFixtures.pictureOptions, ownPicture = mine,
            look = LookFixtures.draft(picture = LookEditorState.MINE, wayBack = WayBack.EARLIER, details = LookDetails(band = BandChoice.SEE_THROUGH)))
        compose.showColumn { LookContent(state, { hits += it }, nav = nav) }
        fun tapText(text: String) = compose.tap(compose.onNodeWithText(text).inView(), OBSCURED)
        fun tapLabel(label: String) = compose.tap(compose.onNodeWithContentDescription(label).inView(), OBSCURED)

        tapText("Put back the earlier look")
        tapText("Turn it off")
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        tapLabel("30%")
        assertEquals(listOf(LookEdit.PutBackEarlier, LookEdit.TurnGlowOff, LookEdit.SetSeeThrough(30)).map { HomeScreenIntent.Look(it) }, hits.toList())
    }

    // Setup's look step: the same editor with Done, which only closes the step.
    @Test fun setupFooterIsDoneOnly() {
        var done = 0
        val editor = LookFixtures.draft(previews = false).toEditor(LookFixtures.pictureOptions, null, 30, null, 30)
        compose.showColumn { LookEditor(editor, {}, { _, _ -> }, {}, footer = { LookDoneFooter { done++ } }) }
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(0)
        compose.tap("Done", 0)
        assertEquals(1, done)
    }
}

// Scrolls a node into view when it sits in a scrolling list (the pinned footer does not).
internal fun SemanticsNodeInteraction.inView(): SemanticsNodeInteraction = also { runCatching { it.performScrollTo() } }
