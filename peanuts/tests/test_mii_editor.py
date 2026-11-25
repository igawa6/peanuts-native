"""The Mii editor (dev.peanuts.mii.MiiEditor) when Android builds it again: a turn of the device, a change to dark mode or the app's process ending in the
background keeps the Mii being edited, its Undo steps and the tab (MiiEditState on the JVM with MiiEditStateJvmTest; the editor's hooks read from the source).
Skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MII = REPO / 'android/app/src/main/java/dev/peanuts/mii'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiEditStateJvmTest.java'
SOURCES = [MII / n for n in ('MiiStore.java', 'MiiImport.java', 'MiiEditState.java')]
EDITOR = MII / 'MiiEditor.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiEditStateJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiEditStateJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class Editor(unittest.TestCase):
    def setUp(self):
        self.src = EDITOR.read_text(encoding='utf-8')

    def test_state_is_saved(self):
        """onSaveInstanceState keeps the Mii, the Mii as it was, the Undo steps and the tab"""
        m = re.search(r'protected void onSaveInstanceState\(Bundle out\) \{(.*?)\n    \}', self.src, re.S)
        self.assertIsNotNone(m)
        for key in ('"mii"', '"original"', '"undo"', '"tab"', '"group"', '"more"'):
            self.assertIn(key, m.group(1))

    def test_state_is_restored_before_the_page_is_built(self):
        """onCreate takes the saved state back after it read the Mii from the store, and before it builds the page"""
        body = self.src[self.src.index('protected void onCreate(Bundle saved)'):]
        self.assertLess(body.index('original = MiiStore.charInfo(m);'), body.index('restore(saved);'))
        self.assertLess(body.index('restore(saved);'), body.index('build();'))

    def test_no_android_classes(self):
        self.assertNotIn('import android.', (MII / 'MiiEditState.java').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
