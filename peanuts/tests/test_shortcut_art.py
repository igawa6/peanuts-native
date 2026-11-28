"""PEANUTS: a game's home-screen shortcut shows its whole cover (issue #258): dev.peanuts.launch's ShortcutArt on the JVM (android/app/src/jvmtest's
ShortcutArtJvmTest; skipped without a JDK), and GameShortcuts drawing the cover where ShortcutArt says, in the part of the adaptive icon a launcher shows."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
LAUNCH = APP / 'main/java/dev/peanuts/launch'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='shortcutart_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(LAUNCH / 'ShortcutArt.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/launch/ShortcutArtJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.launch.ShortcutArtJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_the_cover_goes_where_shortcut_art_says(self):
        src = (LAUNCH / 'GameShortcuts.java').read_text(encoding='utf-8')
        self.assertIn('ShortcutArt.fit(src.getWidth(), src.getHeight(), size)', src)
        self.assertIn('cv.drawColor(ShortcutArt.average(px))', src)
        self.assertNotIn('new Rect(0, 0, size, size)', src, 'no cover drawn over the whole 108 dp, where a launcher shows only the middle')


if __name__ == '__main__':
    unittest.main()
