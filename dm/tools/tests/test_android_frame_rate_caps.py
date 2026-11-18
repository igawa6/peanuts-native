"""Saved frame-limit values and opt-in high-rate choices, using the production Java policy."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'android/app/src/main/java/dev/nxnative/android/FrameRateCaps.java'
MAIN = '''package dev.nxnative.android;
import java.util.Arrays;
public class FrameRateCapsTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(Arrays.equals(FrameRateCaps.rates(60), new int[] {-2, 0, 45, 40, 30, 20}));
        check(Arrays.equals(FrameRateCaps.rates(90), new int[] {-2, 90, 0, 45, 40, 30, 20}));
        check(Arrays.equals(FrameRateCaps.rates(120), new int[] {-2, 120, 90, 0, 45, 40, 30, 20}));
        check(FrameRateCaps.normalize(120, 60) == 0); // legacy profiles still stop at 60
        check(FrameRateCaps.normalize(120, 90) == 90);
        check(FrameRateCaps.normalize(120, 120) == 120);
        check(FrameRateCaps.normalize(90, 120) == 90);
        check(FrameRateCaps.normalize(60, 120) == 0); // saved zero retains its old meaning
        check(FrameRateCaps.normalize(0, 120) == 0 && FrameRateCaps.normalize(-1, 120) == 0);
        for (int rate : new int[] {45, 40, 30, 20}) check(FrameRateCaps.normalize(rate, 120) == rate);
        check(FrameRateCaps.normalize(15, 120) == 20);
        int[] changed = FrameRateCaps.rates(120); changed[0] = 999;
        check(FrameRateCaps.rates(120)[0] == -2);
        check(FrameRateCaps.normalize(-2, 60) == -2 && FrameRateCaps.normalize(-2, 120) == -2);
        check(FrameRateCaps.contains(FrameRateCaps.menu(), 90));
        check(!FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 20));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), -2));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 30));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 120));
    }
}'''


class FrameRateCaps(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
    def test_limits_and_saved_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            main = path / 'FrameRateCapsTest.java'
            main.write_text(MAIN)
            subprocess.run(['javac', '-d', str(path), str(SOURCE), str(main)], check=True, capture_output=True)
            run = subprocess.run(['java', '-cp', str(path), 'dev.nxnative.android.FrameRateCapsTest'], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == '__main__':
    unittest.main()
