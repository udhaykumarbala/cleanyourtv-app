package tv.cleanyour.app

import android.content.res.Configuration
import android.icu.text.MessageFormat
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.cleanyour.core.flows.CopyKey
import java.io.File
import java.util.Locale
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CopyTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val sample = mapOf<String, Any>("tv" to "Mi TV 4A", "count" to 3, "done" to 2, "total" to 5, "name" to "PatchWall",
        "launcher" to "Projectivy", "percent" to 58, "date" to "19 Oct", "room" to "Living room", "step" to 2,
        "size" to 4, "free" to 12, "needed" to 20, "rows" to 2, "seconds" to 5, "off" to 3)

    // Every machine-produced sentence resolves and formats (ICU) with the arguments the flows pass.
    @Test fun everyCopyKeyFormats() {
        val copy = ResourceFlowCopy(context)
        val ar = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) })
        for (k in CopyKey.entries) {
            val text = copy.text(k, sample)
            assertTrue(text.isNotBlank(), "$k is blank")
            assertTrue('{' !in text, "$k left an argument unformatted: $text")
            val arText = ResourceFlowCopy.format(ar, ResourceFlowCopy.idOf(k), sample)
            assertTrue(arText.isNotBlank(), "ar $k is blank")
            assertTrue('{' !in arText, "ar $k left an argument unformatted: $arText")
        }
    }

    // Every string with ICU syntax in every module parses (a broken pattern would crash a screen),
    // and no copy contains an em dash or a double hyphen (house rule).
    @Test fun everyStringResourceIsValidIcuAndFollowsTheHouseRules() {
        val root = File(System.getProperty("cleanyourtv.repoRoot") ?: error("no repo root"))
        val modules = listOf("app", "feature/connect", "feature/setup", "feature/manage", "feature/launcher", "feature/modules", "feature/backup")
        val locales = listOf("values" to Locale.ENGLISH, "values-ar" to Locale.forLanguageTag("ar"))
        val re = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        for ((dir, locale) in locales) {
            var checked = 0
            for (m in modules) {
                val f = File(root, "$m/src/main/res/$dir/strings.xml")
                for (match in re.findAll(f.readText())) {
                    val (name, raw) = match.destructured
                    val text = raw.replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    if ('—' in text || "--" in text) fail("${f.path} $name breaks the dash rule")
                    if ('{' in text) {
                        try { MessageFormat(text, locale).format(sample) } catch (e: IllegalArgumentException) { fail("${f.path} $name: ${e.message}") }
                        checked++
                    }
                }
            }
            assertTrue(checked > 50, "$dir checked=$checked")
        }
    }
}
