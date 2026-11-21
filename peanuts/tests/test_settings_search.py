"""One search over every setting (dev.peanuts.ui.SettingsSearch, SettingsPage's search sheet, AdvancedPort): the matching and order on the JVM
(android/app/src/jvmtest's SettingsSearchJvmTest; skipped without a JDK), and the wiring: Advanced's own search is reached by the names upstream's
source has, a search sheet opens from each section's page, a row found jumps to its place."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UI = APP / 'main/java/dev/peanuts/ui'
UP = APP / 'main/java/dev/nxnative/android'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='settingssearch_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t] + [str(UI / f) for f in ('SettingsSections.java', 'SettingsHelp.java', 'SettingsSearch.java')]
                           + [str(APP / 'jvmtest/java/dev/peanuts/ui/SettingsSearchJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.SettingsSearchJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Advanced(unittest.TestCase):
    """AdvancedPort reaches these by reflection: they must be in upstream's source as it names them"""

    def test_names_exist(self):
        port = (UI / 'AdvancedPort.java').read_text(encoding='utf-8')
        settings = (UP / 'AdvancedSettings.java').read_text(encoding='utf-8')
        table = (UP / 'AdvancedTable.java').read_text(encoding='utf-8')
        main = (UP / 'MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('getDeclaredMethod("table", Context.class)', port)
        self.assertRegex(settings, r'static synchronized List<AdvancedTable\.Row> table\(Context c\)')
        self.assertIn('getDeclaredMethod("visible", List.class, String.class, boolean.class, String.class)', port)
        self.assertRegex(table, r'static List<Row> visible\(List<Row> rows, String titleId, boolean dev, String query\)')
        self.assertIn('"AdvancedTable$Row"', port)
        self.assertRegex(table, r'static final class Row \{')
        self.assertRegex(table, r'String name = "", [^;]*\btryIf = ""')
        self.assertRegex(table, r'String title\(\) \{')
        self.assertIn('getDeclaredMethod("openAdvanced", String.class, String.class)', port)
        self.assertRegex(main, r'void openAdvanced\(String titleId, String name\)')
        self.assertIn('EditText q = new EditText(a);', (UP / 'AdvancedPage.java').read_text(encoding='utf-8'), 'the Advanced page has its search field')


class Wiring(unittest.TestCase):
    def test_sheet_and_jump(self):
        page = (UI / 'SettingsPage.java').read_text(encoding='utf-8')
        self.assertIn('iconButton(a, "search", R.drawable.ic_search, "Search settings", v -> openSearch(a))', page)
        self.assertIn('SettingsSearch.find(titles, texts, query)', page)
        self.assertIn('AdvancedPort.find(a, query)', page)
        self.assertIn('{ d.dismiss(); jump(row); }', page)
        self.assertIn('{ d.dismiss(); AdvancedPort.open(a, query); }', page)
        self.assertIn('if (k != KeyEvent.KEYCODE_BUTTON_B) return false;', page[page.index('private static void openSearch('):])

    def test_ui_rules(self):
        for f in ('SettingsSearch.java', 'AdvancedPort.java', 'SettingsPage.java'):
            src = (UI / f).read_text(encoding='utf-8')
            strings = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', src))
            for bad in ('·', '•', ' - ', '–', '—', '!'):
                self.assertNotIn(bad, strings, f'{f}: {bad!r} in its text')


if __name__ == '__main__':
    unittest.main()
