package tv.cleanyour.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityCheckOptions
import com.github.takahirom.roborazzi.RoborazziATFAccessibilityChecker
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import java.util.TimeZone
import org.hamcrest.Matchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.ui.design.component.SheetSurface
import tv.cleanyour.ui.design.theme.CytTheme

// The four renderings of every screen state (T8, the W7 rule for components): dark, light, 200%
// font and right to left. Screens are 360 x 780 dp like the mockups, under reduced motion so the
// count-up, the Welcome film and the list fades rest in their final frame.
enum class Variant(val dark: Boolean, val fontScale: Float, val rtl: Boolean) {
    DARK(true, 1f, false),
    LIGHT(false, 1f, false),
    FONT200(true, 2f, false),
    RTL(true, 1f, true),
}

class Shot(val name: String, val height: Dp = 780.dp, val content: @Composable () -> Unit) {
    override fun toString() = name
}

const val SHOT_TAG = "shot"

@Composable
fun Rendered(shot: Shot, variant: Variant) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(d.density, variant.fontScale),
        LocalLayoutDirection provides if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        CytTheme(darkTheme = variant.dark, reducedMotion = true) {
            Box(Modifier.testTag(SHOT_TAG).size(360.dp, shot.height).background(CytTheme.colors.bg)) { shot.content() }
        }
    }
}

// A modal sheet as the person sees it: the screen behind under the scrim (hidden from TalkBack and
// from the checks, as the modal window hides it), the sheet surface from the bottom. Robolectric
// does not capture the modal sheet window itself, so the sheet content is drawn inline.
@Composable
fun SheetFrame(behind: @Composable () -> Unit, maxHeight: Dp = 690.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { behind() }
        Box(Modifier.fillMaxSize().background(CytTheme.colors.scrim))
        SheetSurface(Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight).verticalScroll(rememberScrollState())) { content() }
    }
}

// Every shot in every variant, with the ATF checks (touch targets, contrast, labels) failing the
// test on any ERROR. Record with recordRoborazziDebug, compare with verifyRoborazziDebug.
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 0.4.6: the window is taller than a shot so a taller shot (Shot.height) is drawn whole; every
// other shot keeps its 360 x 780 dp box.
@Config(sdk = [35], qualifiers = "w360dp-h1000dp-xhdpi")
class ConnectScreenshotTest(private val shot: Shot, private val variant: Variant) {

    @get:Rule
    val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun shot() {
        compose.setContent { Rendered(shot, variant) }
        compose.waitForIdle()
        val node = compose.onNodeWithTag(SHOT_TAG)
        // Rendered at xhdpi for the checks, saved at half scale to keep the goldens small (R4-W8:
        // one scale for every module).
        node.captureRoboImage(
            "src/test/screenshots/${shot.name}_${variant.name.lowercase()}.png",
            roborazziOptions = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
        )
        node.checkRoboAccessibility(
            RoborazziATFAccessibilityCheckOptions(
                checker = RoborazziATFAccessibilityChecker(suppressions = Matchers.not(Matchers.anything())),
                failureLevel = RoborazziATFAccessibilityChecker.CheckLevel.Error,
            ),
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1}")
        fun params(): List<Array<Any>> = ConnectShots.all.flatMap { s -> Variant.entries.map { v -> arrayOf<Any>(s, v) } }
    }
}
