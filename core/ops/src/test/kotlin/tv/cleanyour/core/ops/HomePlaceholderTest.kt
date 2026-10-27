package tv.cleanyour.core.ops

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.PackageName
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A start-up fallback screen is a placeholder whatever its class is called, the same test the Setup
// card uses (#481): the TCL Percee TV's com.android.boot.fallbackhome/.FallbackHomeCustomizer is one.
class HomePlaceholderTest {
    private fun c(pkg: String, cls: String) = Component(PackageName(pkg), cls)

    @Test fun everyFallbackHomeIsAPlaceholder() {
        assertTrue(HomePlaceholder.isPlaceholder(c("com.android.tv.settings", ".system.FallbackHome")))
        assertTrue(HomePlaceholder.isPlaceholder(c("com.android.boot.fallbackhome", ".FallbackHomeCustomizer")))
    }

    @Test fun aRealHomeScreenIsNot() {
        assertFalse(HomePlaceholder.isPlaceholder(c("com.google.android.apps.tv.launcherx", ".home.HomeActivity")))
        assertFalse(HomePlaceholder.isPlaceholder(c("com.gathin.tvhome", ".MainActivity")))
    }
}
