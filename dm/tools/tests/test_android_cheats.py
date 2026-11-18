"""The launcher's cheats (Cheats.java with android/app/src/jvmtest's CheatsJvmTest): the parse against the host's rules, the Game settings › Cheats switch (off: no
DM_CHEATS, no panel rows), the import's build gate, the merge's naming, a start's merged folder, the export / import zip; and the wiring from the panel to the host
(Native.cheatSet → nxlaunch.c → hle_cheats.c nxhost_cheat_set). The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/CheatsJvmTest.java'
OOT = REPO / 'dm/games/0400486024DEC000/cheats/E0F3CCFAF50065AE.txt'


class Wiring(unittest.TestCase):
    def test_panel_reaches_the_host(self):
        self.assertIn('nxhost_cheat_set', (REPO / 'dm/host/hle_cheats.c').read_text(encoding='utf-8'))
        self.assertIn('Native_cheatSet', (REPO / 'android/app/src/main/cpp/nxlaunch.c').read_text(encoding='utf-8'))
        self.assertIn('native int cheatSet', (SRC / 'Native.java').read_text(encoding='utf-8'))
        self.assertIn('Native.cheatSet', (SRC / 'SidePanel.java').read_text(encoding='utf-8'))

    def test_profile_column(self):
        self.assertIn("tsvRows(game, 'cheats')", (REPO / 'android/app/build.gradle').read_text(encoding='utf-8'))
        self.assertIn('p.cheats', (SRC / 'Profile.java').read_text(encoding='utf-8'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_cheats(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(SRC / 'Cheats.java'), str(SRC / 'Json.java'), str(TEST)],
                           check=True, capture_output=True, text=True)
            (d / 'work').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.CheatsJvmTest', str(d / 'work'), str(OOT)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
