"""The launcher's Advanced pages (AdvancedTable.java with android/app/src/jvmtest's AdvancedTableJvmTest): docs/advanced_settings.tsv parsed by the app's own parser has one row
for each switch of docs/env_switches.tsv, rows accept what their type says, the search and the developer toggle show the right rows, and the APK takes both tables from docs/ at
build time. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/AdvancedTableJvmTest.java'


class Wiring(unittest.TestCase):
    def test_apk_copies_the_tables(self):
        g = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        self.assertIn("include 'advanced_settings.tsv', 'env_switches.tsv'", g)
        self.assertIn('generated/nxadvanced/assets', g)

    def test_game_activity_puts_advanced_before_env_txt(self):
        s = (JAVA / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertLess(s.index('AdvancedSettings.lines(this, id, PROTECTED), "Advanced"'), s.index('guard(userEnv(new File(files, "env.txt")), "env.txt", false)'))
        self.assertIn('Native.note(n)', s[s.index('advNotes) Native') - 40:])


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Table(unittest.TestCase):
    def test_table_on_the_jvm(self):
        with tempfile.TemporaryDirectory(prefix='advanced_') as t:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', t, str(JAVA / 'AdvancedTable.java'), str(TEST)], check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', t, 'dev.nxnative.android.AdvancedTableJvmTest', str(REPO / 'docs/advanced_settings.tsv'), str(REPO / 'docs/env_switches.tsv')],
                               capture_output=True, text=True)
            self.assertEqual(r.stdout.strip(), 'ok', r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
