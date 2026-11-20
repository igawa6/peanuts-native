"""The companion on the built-in display (dev.peanuts.CompanionScreen, GitHub issue #183): its activity's window takes the display's focus but no touch, hides the
system bars and takes Back as the game's Back; the page is a panel above it that never takes the focus. The key rule (ScreenKeys) runs on the JVM with
android/app/src/jvmtest's ScreenKeysJvmTest; skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PEANUTS = REPO / 'android/app/src/main/java/dev/peanuts'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ScreenKeysJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Keys(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='screenkeys_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(PEANUTS / 'ScreenKeys.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ScreenKeysJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_constants_match_android(self):
        # KeyEvent.KEYCODE_BACK / ACTION_UP / FLAG_CANCELED, InputDevice.SOURCE_DPAD / SOURCE_GAMEPAD / SOURCE_JOYSTICK
        src = (PEANUTS / 'ScreenKeys.java').read_text(encoding='utf-8')
        self.assertIn('KEYCODE_BACK = 4, ACTION_UP = 1, FLAG_CANCELED = 0x20', src)
        self.assertIn('SOURCE_DPAD = 0x201, SOURCE_GAMEPAD = 0x401, SOURCE_JOYSTICK = 0x1000010', src)


class Windows(unittest.TestCase):
    def setUp(self):
        self.screen = (PEANUTS / 'CompanionScreen.java').read_text(encoding='utf-8')
        self.oncreate = self.screen[self.screen.index('protected void onCreate'):self.screen.index('private static void edgeToEdge')]

    def test_the_activity_window_can_take_the_focus(self):
        # Android lets only a focusable window hide the bars, and a display without one holds Back for 5 s, then reports the app as not responding
        self.assertNotIn('FLAG_NOT_FOCUSABLE', self.oncreate)
        self.assertIn('FLAG_NOT_TOUCHABLE', self.oncreate)
        self.assertIn('BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE', self.oncreate)
        self.assertIn('hide(WindowInsets.Type.systemBars())', self.oncreate)

    def test_the_page_is_a_panel_that_never_takes_the_focus(self):
        self.assertIn('TYPE_APPLICATION_PANEL', self.oncreate)
        self.assertIn('new CompanionPage(this, p,', self.oncreate)   # the panel's window, which the page makes NOT_FOCUSABLE
        page = (PEANUTS / 'CompanionPage.java').read_text(encoding='utf-8')
        self.assertIn('FLAG_NOT_FOCUSABLE', page[page.index('View create()'):page.index('void start()')])

    def test_back_never_finishes_it(self):
        self.assertIn('case ScreenKeys.BACK: Companion.gameBack(); return true;', self.screen)
        self.assertIn('public void onBackPressed() { Companion.gameBack(); }', self.screen)

    def test_gone_without_close_resets_the_state(self):
        self.assertIn('if (current == this) { current = null; Companion.screenGone(); }', self.screen)
        close = self.screen[self.screen.index('static void close()'):]
        self.assertLess(close.index('current = null;'), close.index('s.finish()'))   # Companion's own close is no "gone"
        companion = (PEANUTS / 'Companion.java').read_text(encoding='utf-8')
        gone = companion[companion.index('static void screenGone()'):]
        self.assertIn('screenUp = false;', gone[:gone.index('}')])


if __name__ == '__main__':
    unittest.main()
