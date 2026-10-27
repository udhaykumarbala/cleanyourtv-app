# App languages

The phone app supports sixteen languages in both the full and Play builds:

| Language | Locale | Resource folder |
| :-- | :-- | :-- |
| English | `en` | `values` (default and fallback) |
| 繁體中文 | `zh-Hant` | `values-b+zh+Hant` |
| 日本語 | `ja` | `values-ja` |
| 한국어 | `ko` | `values-ko` |
| 简体中文 | `zh-Hans` | `values-b+zh+Hans` |
| ไทย | `th` | `values-th` |
| Tiếng Việt | `vi` | `values-vi` |
| Deutsch | `de` | `values-de` |
| हिन्दी | `hi` | `values-hi` |
| Español | `es` | `values-es` |
| Français | `fr` | `values-fr` |
| العربية الفصحى (Modern Standard Arabic) | `ar` | `values-ar` |
| বাংলা | `bn` | `values-bn` |
| Русский | `ru` | `values-ru` |
| Português | `pt` | `values-pt` |
| Bahasa Indonesia | `id` | `values-in` (Android legacy qualifier) |

## Choose a language

On Android 13 or newer, open the phone's **Settings > Apps > Clean Your TV > Language**.
Depending on the phone, this may be under **System > Languages > App languages**.
Choose one of the sixteen languages or follow the system language. The supported languages are
declared in `app/src/main/res/xml/locales_config.xml` and the application manifest.

On Android 9 through 12, the app follows the phone's system language. Change the preferred
language in the phone's settings, then reopen the app. Unsupported languages fall back to English.
Traditional Chinese covers Taiwan and Hong Kong, while Simplified Chinese covers China and
Singapore through Android's script matching.

No translation service, account, extra permission or internet connection is needed at runtime.
All text is packaged in the app. Android's system fonts provide fallback glyphs for scripts
outside the bundled Outfit typeface.

## Coverage and review

All 1,066 translatable resource entries in `app`, the six `feature` modules and `ui/design` have
fifteen localized counterparts. This covers connection instructions, setup, management, backup and
restore, add-ons, notifications and accessibility descriptions. English remains the source.
The new translations were machine-assisted, with terminology and key interface text edited;
native-speaker review and device layout checks are welcome before a production release.

Product names, package names, commands and licence identifiers retain their original spelling.
The bundled TV launcher and curtain are separately signed upstream APKs. Their text, the TV's
own settings, upstream module descriptions, diagnostic reports and this repository's website
are not translated by these phone-app resources. Their pins and signatures are unchanged.

## Maintain translations

- Add each new translatable entry to the matching locale folder in the same module.
  Checks scan all resource XML files, including the TV-preview strings added in upstream 0.4.0.
- Preserve ICU argument names (`{tv}`, `{name}`), selector syntax (`plural`, `one`, `other`,
  `=0`), count information, `spellout`, printf placeholders and `formatted="false"` attributes.
  Arabic and Russian include additional locale-specific plural categories. Arabic singular and
  dual quantity resources express their exact count in words. Keep explicit `=0` messages where
  the English source has them. Other categories must retain the appropriate dynamic count.
- Escape ASCII apostrophes and quotation marks for Android XML. XML-escape `&` and `<`.
- Keep shared resource names consistent across modules because Android merges their resources.
- Keep each `allow_body_emphasis_*` value an exact substring of `allow_body`.
- Use short control labels, check long German sentences and test with large fonts.

Run the offline resource checks:

```sh
python3 -m unittest discover -s scripts/tests -p test_localization.py -v
```

With JDK 21, the Android SDK and the pinned submodule available, run:

```sh
./gradlew check :app:assembleDebug
```

`LocalizationTest` resolves every translated string from the compiled resources on Android 9
and 15, formats ICU messages with counts from zero through one million, checks argument names,
verifies regional matching and checks the English fallback. It also checks Arabic right-to-left
configuration and Arabic/Russian quantity selection. Existing screenshot checks include mirrored
right-to-left layouts. The localization workflow also uploads
debug APKs for both store flavours after a successful build.

Modern Standard Arabic uses the shared `ar` resources across Arabic regions, with the app's
existing `supportsRtl` setting and mirrored navigation icons. Spanish, French and Portuguese
use shared language resources across regions; Portuguese wording currently follows common
Brazilian usage. Indonesian uses BCP-47 `id` in the language configuration and Android's legacy
`in` resource qualifier. No new translation dependencies or runtime network calls were added.
