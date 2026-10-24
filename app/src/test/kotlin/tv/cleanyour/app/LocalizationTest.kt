package tv.cleanyour.app

import android.content.Context
import android.content.res.Configuration
import android.icu.text.MessageFormat
import android.icu.text.MessagePattern
import android.os.LocaleList
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = android.app.Application::class)
class LocalizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val locales = mapOf(
        "en" to "values", "zh-Hant" to "values-b+zh+Hant", "ja" to "values-ja",
        "ko" to "values-ko", "zh-Hans" to "values-b+zh+Hans", "th" to "values-th",
        "vi" to "values-vi", "de" to "values-de",
        "hi" to "values-hi", "es" to "values-es", "fr" to "values-fr", "ar" to "values-ar",
        "bn" to "values-bn", "ru" to "values-ru", "pt" to "values-pt", "id" to "values-in",
    )
    private val modules = listOf("app", "ui/design", "feature/connect", "feature/setup",
        "feature/manage", "feature/backup", "feature/launcher", "feature/modules")

    private fun localized(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        },
    )

    @Suppress("DiscouragedApi")
    private fun id(name: String, type: String = "string"): Int = context.resources.getIdentifier(name, type, context.packageName)
        .also { assertTrue(it != 0, "Missing compiled resource: $name") }

    // MessageFormat.argumentNames includes only top-level arguments. Read all pattern parts so
    // arguments inside plural branches receive sample values and are checked too.
    private fun argumentNames(text: String): Set<String> {
        val pattern = MessagePattern(text)
        return (0 until pattern.countParts()).mapNotNull { index ->
            val part = pattern.getPart(index)
            if (part.type == MessagePattern.Part.Type.ARG_NAME) pattern.getSubstring(part) else null
        }.toSet()
    }

    @Test fun allCompiledTranslationsResolveAndFormatWithoutLosingArguments() {
        val root = File(System.getProperty("cleanyourtv.repoRoot") ?: error("no repo root"))
        for ((tag, folder) in locales) {
            val language = localized(tag)
            for (module in modules) {
                val file = File(root, "$module/src/main/res/$folder/strings.xml")
                val entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(file).getElementsByTagName("string")
                for (i in 0 until entries.length) {
                    val el = entries.item(i) as Element
                    if (el.getAttribute("translatable") == "false") continue
                    val name = el.getAttribute("name")
                    val text = language.getString(id(name))
                    val expected = el.textContent.replace("\\'", "'").replace("\\\"", "\"")
                    assertEquals(expected, text, "$tag/$module/$name did not load its own resource")
                    if ('{' !in text) continue
                    val source = localized("en").getString(id(name))
                    val format = MessageFormat(text, Locale.forLanguageTag(tag))
                    val names = argumentNames(source)
                    assertEquals(names, argumentNames(text),
                        "$tag/$name changed an argument name")
                    for (count in listOf(0, 1, 2, 3, 5, 11, 21, 101, 102, 1_000_000)) {
                        val args = names.associateWith<String, Any> { "Sample" }.toMutableMap()
                        // The source determines numeric argument types, including nested plural groups.
                        Regex("\\{(\\w+),\\s*(?:plural|selectordinal|spellout|number)").findAll(source).forEach {
                            args[it.groupValues[1]] = count
                        }
                        val rendered = format.format(args)
                        assertTrue(rendered.isNotBlank(), "$tag/$name is blank")
                        assertFalse('{' in rendered, "$tag/$name left a placeholder: $rendered")
                    }
                }
            }
        }
    }

    @Test fun regionalLocalesSelectTheExpectedChineseScriptAndEnglishIsTheFallback() {
        val welcome = id("welcome_title")
        for ((region, script) in mapOf("zh-TW" to "zh-Hant", "zh-HK" to "zh-Hant",
            "zh-CN" to "zh-Hans", "zh-SG" to "zh-Hans", "en-GB" to "en", "fi-FI" to "en",
            "hi-IN" to "hi", "es-MX" to "es", "fr-CA" to "fr", "ar-EG" to "ar",
            "bn-BD" to "bn", "ru-RU" to "ru", "pt-BR" to "pt", "id-ID" to "id")) {
            assertEquals(localized(script).getString(welcome), localized(region).getString(welcome), region)
        }
        assertFalse(localized("zh-Hant").getString(welcome) == localized("zh-Hans").getString(welcome))
    }

    @Test fun modernStandardArabicUsesRightToLeftLayout() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, localized("ar").resources.configuration.layoutDirection)
        assertEquals(View.LAYOUT_DIRECTION_LTR, localized("hi").resources.configuration.layoutDirection)
        val welcome = localized("ar").getString(id("welcome_title"))
        assertTrue(welcome.any { it in '\u0600'..'\u06ff' })
    }

    @Test fun arabicAndRussianQuantityResourcesSelectTheirOwnPluralCategories() {
        val more = id("cyt_show_more", "plurals")
        val ar = localized("ar").resources
        val ru = localized("ru").resources
        val arabic = mapOf(0 to "عرض %d من العناصر الإضافية", 1 to "عرض عنصر إضافي واحد",
            2 to "عرض عنصرين إضافيين", 3 to "عرض %d عناصر إضافية", 11 to "عرض %d عنصرًا إضافيًا",
            100 to "عرض %d عنصر إضافي")
        for ((count, expected) in arabic) {
            assertEquals(expected, ar.getQuantityString(more, count), "Arabic count=$count")
            assertFalse("%d" in ar.getQuantityString(more, count, count))
        }
        for ((count, expected) in mapOf(1 to "Показать ещё %d элемент", 2 to "Показать ещё %d элемента",
            5 to "Показать ещё %d элементов", 21 to "Показать ещё %d элемент")) {
            assertEquals(expected, ru.getQuantityString(more, count), "Russian count=$count")
        }
        val found = id("welcome_found_many")
        assertEquals("تم العثور على جهازَي تلفاز",
            ResourceFlowCopy.format(localized("ar"), found, mapOf("count" to 2)))
        assertEquals("Найден 21 телевизор",
            ResourceFlowCopy.format(localized("ru"), found, mapOf("count" to 21)))
    }
}
