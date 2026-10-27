package tv.cleanyour.core.engine

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Issue #629 (OK. AI PONT, Changhong ikebukuro, sold as the Realme AI PONT): the home screen switch
// rolled back twice, from the report's log:
//
//   I engine: the home check saw com.android.tv.settings/.connectivity.NetworkActivity in front, so the old home screen goes back
//   I engine: the home check saw com.android.tv.settings/.MainSettings in front, so the old home screen goes back
//
// Those are the pages the new home screen's own network pill and Settings button (or the remote's
// Settings key) open; nothing in the plan or the check opens Settings. The check stays as it is on
// purpose (refuse more): com.android.tv.settings owns the TV's FallbackHome, the screen a TV shows
// when no home screen answers, so while the switch is unconfirmed any of its pages in front counts
// as "the new home screen is not in charge" and the old one goes back. This pins that behaviour; the
// engine also hands Setup what was in front, so its card can say a Settings page was open.
class Issue629SettingsInFrontTest {
    private val tvSettings = PackageName("com.android.tv.settings")
    private val fallbackHome = Component(tvSettings, ".system.FallbackHome")

    @Test fun issue629ASettingsPageInFrontDuringTheCheckPutsTheOldHomeScreenBack() = runTest(timeout = TEST_TIMEOUT) {
        for (cls in listOf(".connectivity.NetworkActivity", ".MainSettings")) {
            val home = HomeResolution(Samples.stockLauncher, listOf(HomeClaimant(Samples.stockLauncher, 2), HomeClaimant(fallbackHome, -1000)))
            val h = Harness(this, ScriptedTv(Samples.snapshot(packages = Samples.miTvPackages(emptySet()), home = home)))
            val lines = mutableListOf<String>()
            val seen = mutableListOf<Component>()
            val engine = h.engine(log = { lines += it }, onWrongFocus = { tvId, c -> assertEquals(h.tv.tvId, tvId); seen += c })
            val plan = h.setupPlan()
            engine.start(plan, h.tv, plan.hash, h.pass())
            advanceTimeBy(20_000); runCurrent()
            assertIs<RunState.AwaitingConfirm>(engine.runState(h.tv.tvId).value, cls)
            val focus = Component(tvSettings, cls)
            h.tv.focus = focus
            advanceTimeBy(2_500); runCurrent(); h.settle(engine)
            assertEquals(HomeOutcome.WRONG_FOCUS, assertIs<RunState.Finished>(engine.runState(h.tv.tvId).value).home, cls)
            assertEquals(listOf("engine: the home check saw ${focus.flat} in front, so the old home screen goes back"), lines, cls)
            assertEquals(h.stock, h.tv.home, "the TV's own home screen is back")
            // Setup is told what was in front, for its card (Issue629SetupTest).
            assertEquals(listOf(focus), seen)
        }
    }
}
