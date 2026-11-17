"""The in-game overlay's SoC temperature line (SocZones.java and SocTemp.java in android/app/src/main/java/dev/peanuts/overlay, with
android/app/src/jvmtest's SocZonesJvmTest; the hook in Overlay.java): which thermal zones count, their temps, the hottest. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/overlay/SocZones.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/overlay/SocZonesJvmTest.java'
OVERLAY = REPO / 'android/app/src/main/java/dev/nxnative/android/Overlay.java'


class Wiring(unittest.TestCase):
    def test_overlay_hooks(self):
        t = OVERLAY.read_text()
        self.assertIn('{"soc", "SoC temperature (hottest CPU / GPU zone)"}', t)
        self.assertIn('boolean soc = Prefs.overlayItem(ctx, "soc");', t)
        self.assertIn('&& !soc && ', t)
        self.assertIn('if (soc) dev.peanuts.overlay.SocTemp.append(s);', t)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class SocZonesJvm(unittest.TestCase):
    def test_zones(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.overlay.SocZonesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
