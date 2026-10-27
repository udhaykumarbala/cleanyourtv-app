"""Offline checks for every shipped phone-app translation, using only the stdlib."""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ['app', 'ui/design', 'feature/backup', 'feature/connect',
           'feature/launcher', 'feature/manage', 'feature/modules', 'feature/setup']
LOCALES = {'en': 'values', 'zh-Hant': 'values-b+zh+Hant', 'ja': 'values-ja',
           'ko': 'values-ko', 'zh-Hans': 'values-b+zh+Hans', 'th': 'values-th',
           'vi': 'values-vi', 'de': 'values-de', 'hi': 'values-hi', 'es': 'values-es',
           'fr': 'values-fr', 'ar': 'values-ar', 'bn': 'values-bn', 'ru': 'values-ru',
           'pt': 'values-pt', 'id': 'values-in'}


def resources(path):
    files = sorted(path.glob('*.xml')) if path.is_dir() else [path]
    elements = [el for file in files for el in ET.parse(file).getroot() if el.tag in ('string', 'plurals')]
    names = [el.attrib['name'] for el in elements]
    if len(set(names)) != len(names):
        raise AssertionError(f'Duplicate resource names in {path}')
    return {el.attrib['name']: el for el in elements}


def signature(text):
    """Keep ICU names/types, plural selectors, count symbols and printf types intact."""
    args, types, selectors = Counter(), Counter(), Counter()

    def closing(s, start):
        depth = 0
        for i in range(start, len(s)):
            depth += (s[i] == '{') - (s[i] == '}')
            if depth == 0:
                return i
        raise AssertionError(f'Unbalanced ICU message: {s}')

    def message(s):
        i = 0
        while i < len(s):
            if s[i] != '{':
                i += 1
                continue
            end = closing(s, i)
            expr = s[i:end+1]
            match = re.match(r'\{\s*(\w+)\s*(?:,\s*(\w+)\s*,?\s*)?', expr)
            if not match:
                raise AssertionError(f'Invalid ICU expression: {expr}')
            name, kind = match.groups()
            args[name] += 1
            if kind:
                types[kind] += 1
            if kind in ('plural', 'select', 'selectordinal'):
                p = match.end()
                while p < len(expr)-1:
                    start = expr.index('{', p)
                    selectors[expr[p:start].strip()] += 1
                    finish = closing(expr, start)
                    message(expr[start+1:finish])
                    p = finish+1
            i = end+1

    message(text)
    return (
        args, types, selectors,
        text.count('#'),
        Counter(re.findall(r'%(?:\d+\$)?[dsf]|%%', text)),
    )


class LocalizationTest(unittest.TestCase):
    def test_manifest_exposes_exactly_the_supported_locales(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        ns = '{http://schemas.android.com/apk/res/android}'
        self.assertEqual('@xml/locales_config', manifest.find('application').get(ns + 'localeConfig'))
        config = ET.parse(ROOT / 'app/src/main/res/xml/locales_config.xml').getroot()
        self.assertEqual(list(LOCALES), [el.get(ns + 'name') for el in config])

    def test_every_module_has_complete_safe_translations(self):
        for module in MODULES:
            res = ROOT / module / 'src/main/res'
            source = resources(res / 'values')
            expected = {name: el for name, el in source.items() if el.get('translatable') != 'false'}
            for tag, folder in LOCALES.items():
                if tag == 'en':
                    continue
                path = res / folder
                translated = resources(path)
                with self.subTest(module=module, locale=tag):
                    self.assertEqual(set(expected), set(translated), str(path))
                for name, src in expected.items():
                    with self.subTest(module=module, locale=tag, name=name):
                        dst = translated[name]
                        self.assertEqual(src.tag, dst.tag)
                        self.assertEqual(src.get('formatted'), dst.get('formatted'))
                        if src.tag == 'plurals':
                            src_by_quantity = {el.get('quantity'): el for el in src}
                            dst_by_quantity = {el.get('quantity'): el for el in dst}
                            required = {'ar': {'zero', 'one', 'two', 'few', 'many', 'other'},
                                        'ru': {'one', 'few', 'many', 'other'}}.get(tag, set(src_by_quantity))
                            self.assertEqual(required, set(dst_by_quantity))
                            pairs = [(src_by_quantity.get(q, src_by_quantity['other']), d)
                                     for q, d in dst_by_quantity.items()]
                        else:
                            pairs = [(src, dst)]
                        for s, d in pairs:
                            text = d.text or ''
                            self.assertTrue(text.strip(), str(path))
                            self.assertNotRegex(text, r'ZXQ\d+QXZ|TODO|TRANSLATE')
                            original, local = signature(s.text or ''), signature(text)
                            if src.tag == 'plurals' and tag == 'ar' and d.get('quantity') in ('one', 'two'):
                                # Arabic singular and dual forms can express the exact count in words.
                                self.assertEqual(original[:4], local[:4], str(path))
                                self.assertFalse(local[4])
                            elif tag in ('ar', 'ru') and ', plural,' in (s.text or ''):
                                # Locale-specific categories may repeat arguments and count symbols.
                                self.assertEqual(set(original[0]), set(local[0]), str(path))
                                self.assertEqual(set(original[1]), set(local[1]), str(path))
                                self.assertTrue(set(original[2]) <= set(local[2]), str(path))
                                self.assertEqual(bool(original[3]), bool(local[3]), str(path))
                                self.assertEqual(original[4], local[4], str(path))
                            else:
                                self.assertEqual(original, local, str(path))
                            self.assertNotRegex(text, r'(?<!\\)[\'"]', 'Android quotes must be escaped')
                            self.assertNotIn('—', text)
                            self.assertNotIn('--', text)
                            depth = 0
                            for ch in text:
                                depth += (ch == '{') - (ch == '}')
                                self.assertGreaterEqual(depth, 0)
                            self.assertEqual(0, depth)

    def test_shared_resource_names_have_consistent_translations(self):
        for tag, folder in LOCALES.items():
            seen = {}
            for module in MODULES:
                for name, el in resources(ROOT / module / 'src/main/res' / folder).items():
                    with self.subTest(locale=tag, name=name):
                        value = ET.tostring(el, encoding='unicode').strip()
                        if name in seen:
                            self.assertEqual(seen[name], value)
                        seen[name] = value

    def test_emphasized_phrases_are_present_in_the_connection_instructions(self):
        for tag, folder in LOCALES.items():
            strings = resources(ROOT / 'feature/connect/src/main/res' / folder / 'strings.xml')
            for name in ('allow_body_emphasis_always', 'allow_body_emphasis_ok'):
                with self.subTest(locale=tag, name=name):
                    self.assertIn(strings[name].text, strings['allow_body'].text)


if __name__ == '__main__':
    unittest.main()
