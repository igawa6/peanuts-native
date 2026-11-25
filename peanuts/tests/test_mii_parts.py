"""The Mii parts from the player's own firmware (dev.peanuts.mii.MiiParts, Tegra, MiiLook) on the JVM: the texture and shape files' layout, the Switch GPU's
block-linear order undone, halves and packed normals, the colour tables, the parts' colouring and where the face parts sit (MiiLookJvmTest). The test makes
its own files in the firmware's layout;
no firmware file is part of the repository. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MII = REPO / 'android/app/src/main/java/dev/peanuts/mii'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiPartsJvmTest.java'
SOURCES = [MII / n for n in ('MiiStore.java', 'MiiParts.java', 'Tegra.java', 'MiiLook.java', 'MiiPixels.java')]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiPartsJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiPartsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


LOOK_TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiLookJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiLookJvm(unittest.TestCase):
    """The colour tables, how each part is coloured and where the face parts sit (MiiLook, the numbers from vee)"""

    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(MII / 'MiiStore.java'), str(MII / 'MiiLook.java'), str(LOOK_TEST)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiLookJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)

    def test_named_where_it_is_checked(self):
        """MiiLook's comment names the test that checks it, and that test is there"""
        self.assertIn('MiiLookJvmTest checks them', (MII / 'MiiLook.java').read_text(encoding='utf-8'))
        self.assertTrue(LOOK_TEST.is_file())


class Sources(unittest.TestCase):
    def test_no_android_classes(self):
        """The readers run on the JVM: no android.* import"""
        for f in SOURCES[1:]:
            self.assertNotIn('import android.', f.read_text(encoding='utf-8'), f.name)

    def test_no_firmware_in_repo(self):
        """Firmware Mii files are the player's own and never committed"""
        tracked = subprocess.run(['git', 'ls-files'], cwd=REPO, capture_output=True, text=True).stdout.split('\n')
        for name in ('NXTextureMidSRGB.dat', 'NXTextureMidLinear.dat', 'NXTextureLowSRGB.dat', 'NXTextureLowLinear.dat', 'ShapeMid.dat', 'ShapeHigh.dat'):
            self.assertFalse(any(p.endswith('/' + name) or p == name for p in tracked), name)


if __name__ == '__main__':
    unittest.main()
