"""GitHub issue #811: the phone's gyro ran inverted after a half turn between the two landscape sides (ROTATION_90 <-> ROTATION_270), which changes no
Configuration, so onConfigurationChanged never refreshed the host's motion rotation. dev.peanuts.display.RotationWatch follows every turn with a display
listener while the game screen is resumed; MotionRotation tells the host only a change. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DISPLAY = ROOT / 'android/app/src/main/java/dev/peanuts/display'
SRC = DISPLAY / 'MotionRotation.java'
WATCH = DISPLAY / 'RotationWatch.java'
TEST = ROOT / 'android/app/src/jvmtest/java/dev/peanuts/display/MotionRotationJvmTest.java'
ACTIVITY = ROOT / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'


def method(t, head):
    i = t.index(head)
    j = t.index('\n    }', i)
    return t[i:j]


class Wiring(unittest.TestCase):
    def test_activity_tells_the_host_only_through_the_watch(self):
        t = ACTIVITY.read_text(encoding='utf-8')
        # a direct call would leave MotionRotation's last value stale, and the next turn back could be skipped
        self.assertEqual(re.findall(r'Native\.motionRotation\(', t), [])
        self.assertIn('new dev.peanuts.display.RotationWatch(this, ui, Native::motionRotation)', t)

    def test_lifecycle(self):
        t = ACTIVITY.read_text(encoding='utf-8')
        self.assertIn('rotationWatch.resume()', method(t, 'protected void onResume()'))
        self.assertIn('rotationWatch.pause()', method(t, 'protected void onPause()'))
        self.assertIn('rotationWatch.pause()', method(t, 'protected void onDestroy()'))
        self.assertIn('rotationWatch.now()', method(t, 'public void onConfigurationChanged('))
        self.assertIn('if (resumedNow) rotationWatch.resume(); else rotationWatch.now();', method(t, 'private void hostSetup()'))

    def test_watch_listens_to_its_own_display(self):
        w = WATCH.read_text(encoding='utf-8')
        self.assertIn('registerDisplayListener(this, handler)', w)
        self.assertIn('unregisterDisplayListener(this)', w)
        changed = method(w, 'public void onDisplayChanged(')
        self.assertIn('d.getDisplayId() == displayId', changed)
        self.assertIn('rotation.set(d.getRotation())', changed)
        self.assertIn('rotation.forget()', method(w, 'public void resume()'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MotionRotationJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.display.MotionRotationJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
