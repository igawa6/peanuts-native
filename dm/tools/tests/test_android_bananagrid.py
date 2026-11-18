"""The launcher's banana background (BananaGrid.java, android/app/src/main): a tap pops the banana under it, found by undoing the grid's tilt and drift;
a banana keeps its row and column as the drift wraps, and a tap between bananas hits none. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/BananaGrid.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/BananaGridJvmTest.java'
VIEW = REPO / 'android/app/src/main/java/dev/nxnative/android/BananaBackgroundView.java'

W, H, D = 1080, 2400, 2.75


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Grid(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='bananagrid_'))
        subprocess.run(['javac', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *args):
        r = subprocess.run(['java', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.BananaGridJvmTest', *map(str, args)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.split()

    def centre(self, r, c, wx, wy):
        return [float(v) for v in self.java('centre', r, c, W, H, wx, wy, D)[1:]]

    def hit(self, x, y, wx, wy):
        return self.java('hit', x, y, W, H, wx, wy, D)[1:]

    def test_a_tap_on_a_centre_hits_that_banana(self):
        for wx, wy in [(0, 0), (123.4, 77.7), (5000.25, 3333.5), (-40, 12)]:
            for r, c in [(0, 0), (1, 0), (-3, 2), (2, -4), (7, 5)]:
                x, y = self.centre(r, c, wx, wy)
                self.assertEqual(self.hit(x, y, wx, wy), [str(r), str(c)], (wx, wy, r, c))

    def test_a_tap_a_little_off_still_hits(self):
        x, y = self.centre(1, 1, 50, 20)
        self.assertEqual(self.hit(x + 20 * D * 0.7, y - 20 * D * 0.7, 50, 20), ['1', '1'])

    def test_a_tap_between_bananas_hits_none(self):
        # half a step to the side of a centre in an even row: a gap (neighbours are a whole step away, and 34 dp is under half of that)
        x, y = self.centre(0, 0, 0, 0)
        self.assertEqual(self.hit(x + 62 * D * 0.97, y - 62 * D * 0.24, 0, 0), ['none'])

    def test_a_banana_keeps_its_name_while_it_drifts(self):
        # the same banana, a second later and a minute later: its centre moves with the drift, the tap on it names the same cell
        for t in (0, 1, 60, 3600):
            wx, wy = t * 9 * D, t * 6 * D
            x, y = self.centre(3, -2, wx, wy)
            self.assertEqual(self.hit(x, y, wx, wy), ['3', '-2'])

    def test_view_pops_through_the_grid(self):
        text = VIEW.read_text()
        self.assertIn('BananaGrid.hit(', text)
        self.assertIn('RESPAWN_MS = 5000', text)


if __name__ == '__main__':
    unittest.main()
