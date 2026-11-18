"""The launcher's fix finder (FixFinder.java with android/app/src/jvmtest's FixFinderJvmTest; FixOffer.java puts it on the exit notice): dm/tools/advanced_fix.tsv parsed by the
app's own parser, the pick (the first row of the end's rule that is still Auto for the game, settable, and not offered before), the texts, and the wiring (the APK takes the table,
the exit notice and its What it means page show the offer). The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/FixFinderJvmTest.java'


class Wiring(unittest.TestCase):
    def test_apk_copies_the_table(self):
        g = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        i = g.index("def nxAdvanced")
        self.assertIn("include 'advanced_fix.tsv'", g[i:g.index('}\n', g.index('into', i))])

    def test_exit_notice_offers_it(self):
        s = (JAVA / 'ExitWatch.java').read_text(encoding='utf-8')
        self.assertIn('FixOffer.find(a, v, rec)', s)
        self.assertIn('FixOffer.add(a, b, fix, rec)', s)    # the notice
        self.assertIn('FixOffer.add(a, mb, fix, rec)', s)   # What it means

    def test_fix_offer_sets_the_game_row(self):
        s = (JAVA / 'FixOffer.java').read_text(encoding='utf-8')
        self.assertIn('AdvancedSettings.set(c, f.name, target, set[0] ? f.value : null)', s)
        self.assertIn('"Undo"', s)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Finder(unittest.TestCase):
    def test_finder_on_the_jvm(self):
        with tempfile.TemporaryDirectory(prefix='fixfinder_') as t:
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', t, str(JAVA / 'AdvancedTable.java'), str(JAVA / 'FixFinder.java'), str(TEST)],
                           check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', t, 'dev.nxnative.android.FixFinderJvmTest', str(REPO / 'docs/advanced_settings.tsv'), str(REPO / 'dm/tools/advanced_fix.tsv')],
                               capture_output=True, text=True)
            self.assertEqual(r.stdout.strip(), 'ok', r.stdout + r.stderr)


if __name__ == '__main__':
    unittest.main()
