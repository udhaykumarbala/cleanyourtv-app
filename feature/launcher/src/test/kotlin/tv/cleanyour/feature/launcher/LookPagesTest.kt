package tv.cleanyour.feature.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.HomeScreenIntent
import tv.cleanyour.core.flows.HomeScreenState
import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.LookNote
import tv.cleanyour.core.flows.LookNoteKind
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.ui.design.component.LookEditorNav
import tv.cleanyour.ui.design.component.LookPage

// look2 Look sheet behaviour (spec 4, 12.1, W12): Back walks Advanced, More options, Look, then
// asks before leaving anything unsent; while it asks, only the question can be reached; every
// control has a label; the preview's description names the style, the picture and apps in a row;
// the flows' notes show once, with their action, and are acknowledged.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1600dp-xhdpi")
class LookPagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sheet(draft: LookDraft) = HomeScreenState(tvName = "Mi TV 4A", dock = LookFixtures.dock4, pictures = LookFixtures.pictureOptions, look = draft)

    @Test fun backWalksUpThePagesThenAsksBeforeLeaving() {
        val nav = LookEditorNav()
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(changed = true, wayBack = WayBack.UNDO, previews = false)), { hits += it }, nav = nav) }
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        compose.onNodeWithContentDescription("Back to More options").assertIsDisplayed()
        assertFalse(compose.runOnIdle { nav.back() })
        compose.waitForIdle()
        assertEquals(LookPage.MORE, nav.page)
        compose.onNodeWithContentDescription("Back to Look").performClick()
        compose.waitForIdle()
        assertEquals(LookPage.MAIN, nav.page)
        assertFalse(compose.runOnIdle { nav.back() })
        compose.waitForIdle()
        assertTrue(nav.asking)
        compose.onNodeWithText("Leave without sending?").assertIsDisplayed()
        compose.onNodeWithText("Your changes aren't on the TV yet.").assertIsDisplayed()
        // While it asks, the sheet behind cannot be reached: one button to send, none shown.
        compose.onAllNodesWithText("Put it on the TV").assertCountEquals(0)
        compose.onNodeWithText("Keep choosing").performClick()
        compose.waitForIdle()
        assertFalse(nav.asking)
        assertTrue(hits.isEmpty())
        // Close asks too, and Leave closes.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CloseLook), hits)
    }

    @Test fun nothingUnsentClosesAtOnce() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(previews = false)), {}, nav = nav) }
        assertTrue(compose.runOnIdle { nav.back() })
    }

    @Test fun aNewPhotoHasItsOwnWords() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(newPhoto = true, changed = true, previews = false)), {}, nav = nav) }
        assertFalse(compose.runOnIdle { nav.close() })
        compose.onNodeWithText("Your new photo isn't on the TV yet.").assertIsDisplayed()
    }

    @Test fun everyControlHasALabelAndThePreviewNamesTheLook() {
        val nav = LookEditorNav()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(StyleId.GLASS, names = true)), {}, pictures = LookFixtures.pictureFiles, nav = nav) }
        compose.onNodeWithContentDescription("Preview of the TV's home screen: Glass, the Mist picture, 5 apps in a row, names under every app.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertIsDisplayed()
        for (page in LookPage.entries) {
            compose.runOnIdle { nav.open(page) }
            compose.waitForIdle()
            val unlabelled = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { n ->
                val c = n.config
                c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() && c.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            }
            assertEquals("unlabelled controls on $page", 0, unlabelled.size)
        }
    }

    // QA 0.4.5-beta1: with one row or Date under the clock on, TalkBack still heard "Classic, the
    // Mist picture, 5 apps in a row." The description names every 3.0 extra that acts in the look,
    // from the same acting extras the rows use, and nothing more with the DEFAULT extras.
    @Test fun thePreviewNamesThe30ExtrasThatAct() {
        val prefix = "Preview of the TV's home screen: "
        var draft by androidx.compose.runtime.mutableStateOf(LookFixtures.draft(reads30 = true, extras = LookExtras(oneRow = true, dateBelow = true)))
        compose.showColumn { LookContent(sheet(draft), {}, pictures = LookFixtures.pictureFiles) }
        compose.onNodeWithContentDescription(prefix + "Classic, the Mist picture, 5 apps in a row, every app in one row, the date under the clock.")
            .assertIsDisplayed()

        // Every extra at once, in page order; the internet warnings do not change the picture.
        compose.runOnIdle {
            draft = LookFixtures.draft(reads30 = true, extras = LookExtras(oneRow = true, noBand = true, hideGreeting = true, dateBelow = true,
                brightDate = true, wifiWarnings = true, tiles = TilesChoice.ICONS, highlight = HighlightChoice.HALO))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(prefix + "Classic, the Mist picture, 5 apps in a row, every app in one row, no dock band, no greeting, " +
            "the date under the clock, a brighter date, icons on the cards, a halo highlight.").assertIsDisplayed()

        // With names under every app, the extras come after them.
        compose.runOnIdle { draft = LookFixtures.draft(StyleId.GLASS, names = true, reads30 = true, extras = LookExtras(highlight = HighlightChoice.RIM)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(prefix + "Glass, the Mist picture, 5 apps in a row, names under every app, a rim highlight.").assertIsDisplayed()

        // Only what acts: All apps has no dock row, so one row and no band say nothing.
        compose.runOnIdle { draft = LookFixtures.draft(StyleId.ALL_APPS, reads30 = true, extras = LookExtras(oneRow = true, noBand = true)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(prefix + "All apps, the Mist picture, 5 apps in a row.").assertIsDisplayed()

        // The DEFAULT extras on a 3.0 TV, and any extras below 3.0: the description as before.
        compose.runOnIdle { draft = LookFixtures.draft(reads30 = true) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(prefix + "Classic, the Mist picture, 5 apps in a row.").assertIsDisplayed()
        compose.runOnIdle { draft = LookFixtures.draft(extras = LookExtras(oneRow = true, dateBelow = true)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(prefix + "Classic, the Mist picture, 5 apps in a row.").assertIsDisplayed()
    }

    @Test fun aNoteShowsOnceWithItsActionAndIsAcknowledged() {
        val hits = mutableListOf<HomeScreenIntent>()
        val note = LookNote(7, LookNoteKind.RESET, action = true)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(note = note, previews = false)), { hits += it }) }
        compose.onNodeWithText("Options reset.").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.Look(LookEdit.NoteAction(7)), HomeScreenIntent.Look(LookEdit.NoteSeen(7))), hits)
    }

    @Test fun aStyleChangeNamesWhatTheNewStyleHasOfItsOwn() {
        val note = LookNote(3, LookNoteKind.STYLE_CHANGED, StyleId.SPOTLIGHT,
            own = listOf(tv.cleanyour.core.flows.DetailWord.BUTTONS, tv.cleanyour.core.flows.DetailWord.GLOW),
            dropped = listOf(tv.cleanyour.core.flows.DetailWord.DOCK_BAND), action = true)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(StyleId.SPOTLIGHT, note = note, previews = false)), {}) }
        compose.onNodeWithText("Spotlight has its own buttons and glow. It has no dock band choice.").assertIsDisplayed()
        compose.onNodeWithText("Keep mine").assertIsDisplayed()
    }

    // Home screen 3.0 (0.4.5): its rows show only on a TV that reads 3.0, each tap sends the whole
    // set as one SetExtras, every row has a label on every page, and a TV below 3.0 with the update
    // offered gets the footer line and the update route on More options.
    @Test fun the30RowsShowOnlyOnA30Tv() {
        val nav = LookEditorNav()
        var draft by androidx.compose.runtime.mutableStateOf(LookFixtures.draft(previews = false))
        compose.showColumn { LookContent(sheet(draft), {}, nav = nav) }
        compose.onAllNodesWithText("Every app in one row").assertCountEquals(0)
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        for (t in listOf("Greeting above the clock", "Date under the clock", "Brighter date", "Warn when the internet is down", "Icons on cards")) {
            compose.onAllNodesWithText(t).assertCountEquals(0)
        }
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        compose.waitForIdle()
        compose.onAllNodesWithText("Halo").assertCountEquals(0)

        compose.runOnIdle { draft = LookFixtures.draft(previews = false, reads30 = true); nav.open(LookPage.MAIN) }
        compose.waitForIdle()
        compose.onNodeWithText("Every app in one row").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        for (t in listOf("Greeting above the clock", "Date under the clock", "Brighter date", "Warn when the internet is down", "Icons on cards",
            "No band. The apps sit right on your picture.")) {
            compose.onNodeWithText(t).performScrollTo().assertIsDisplayed()
        }
        // Green Wi-Fi is off: the warning row is greyed with its reason.
        compose.onNodeWithText("Turn on Green Wi-Fi when online first.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        compose.waitForIdle()
        compose.onNodeWithText("Halo").performScrollTo().assertIsDisplayed()
    }

    @Test fun allAppsHasNoOneRowAndSpotlightNoBandNone() {
        val nav = LookEditorNav()
        var draft by androidx.compose.runtime.mutableStateOf(LookFixtures.draft(StyleId.ALL_APPS, previews = false, reads30 = true))
        compose.showColumn { LookContent(sheet(draft), {}, nav = nav) }
        compose.onAllNodesWithText("Every app in one row").assertCountEquals(0)
        compose.runOnIdle { draft = LookFixtures.draft(StyleId.SPOTLIGHT, previews = false, reads30 = true) }
        compose.waitForIdle()
        compose.onNodeWithText("Every app in one row").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        compose.onAllNodesWithText("No band. The apps sit right on your picture.").assertCountEquals(0)
    }

    @Test fun eachTapSendsTheWholeSet() {
        val nav = LookEditorNav()
        val hits = mutableListOf<HomeScreenIntent>()
        val on = LookExtras(tiles = TilesChoice.ICONS)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(previews = false, reads30 = true, extras = on)), { hits += it }, nav = nav) }
        compose.onNodeWithText("Every app in one row").performScrollTo().performClick()
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        compose.onNodeWithText("No band. The apps sit right on your picture.").performScrollTo().performClick()
        compose.onNodeWithText("Greeting above the clock").performScrollTo().performClick()
        compose.onNodeWithText("Each app's banner, as the app makes it.").performScrollTo().performClick()
        compose.onNodeWithText("Warn when the internet is down").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { nav.open(LookPage.ADVANCED) }
        compose.waitForIdle()
        compose.onNodeWithText("Halo").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(on.copy(oneRow = true), on.copy(noBand = true), on.copy(hideGreeting = true), on.copy(tiles = TilesChoice.ART), on.copy(highlight = HighlightChoice.HALO))
                .map { HomeScreenIntent.Look(LookEdit.SetExtras(it)) },
            hits,
        )
    }

    @Test fun every30ControlHasALabel() {
        val nav = LookEditorNav()
        val e = LookExtras(oneRow = true, noBand = true, dateBelow = true, tiles = TilesChoice.ICONS, highlight = HighlightChoice.RIM)
        compose.showColumn { LookContent(sheet(LookFixtures.draft(reads30 = true, extras = e, keyNames = true)), {}, pictures = LookFixtures.pictureFiles, nav = nav) }
        for (page in LookPage.entries) {
            compose.runOnIdle { nav.open(page) }
            compose.waitForIdle()
            val unlabelled = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { n ->
                val c = n.config
                c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() && c.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
            }
            assertEquals("unlabelled controls on $page", 0, unlabelled.size)
        }
    }

    @Test fun aTvBelow30GetsTheFooterAndTheUpdateRoute() {
        val nav = LookEditorNav()
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(previews = false)).copy(updateTo = "3.0"), { hits += it }, nav = nav) }
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        compose.onNodeWithText("More choices come with home screen 3.0.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Update the home screen").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.CloseLook, HomeScreenIntent.PreviewUpdate), hits)
    }

    @Test fun noFooterWithoutTheUpdateOrOnA30Tv() {
        val nav = LookEditorNav()
        var state by androidx.compose.runtime.mutableStateOf(sheet(LookFixtures.draft(previews = false)))
        compose.showColumn { LookContent(state, {}, nav = nav) }
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        compose.onAllNodesWithText("More choices come with home screen 3.0.").assertCountEquals(0)
        compose.runOnIdle { state = sheet(LookFixtures.draft(previews = false, reads30 = true)).copy(updateTo = "3.1") }
        compose.waitForIdle()
        compose.onAllNodesWithText("More choices come with home screen 3.0.").assertCountEquals(0)
    }

    @Test fun aBandAfterNoneIsOneEdit() {
        val nav = LookEditorNav()
        val hits = mutableListOf<HomeScreenIntent>()
        compose.showColumn { LookContent(sheet(LookFixtures.draft(previews = false, reads30 = true, extras = LookExtras(noBand = true))), { hits += it }, nav = nav) }
        compose.runOnIdle { nav.open(LookPage.MORE) }
        compose.waitForIdle()
        compose.onNodeWithText("A light frosted band.").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf<HomeScreenIntent>(HomeScreenIntent.Look(LookEdit.SetBandBack(tv.cleanyour.core.spec.BandChoice.GLASS))), hits)
    }
}
