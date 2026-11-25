"""The companion on the built-in display (dev.peanuts.CompanionScreen, GitHub issue #183): its activity's window takes the display's focus but no touch, hides the
system bars and takes Back as the game's Back; the page is a panel above it that never takes the focus. Where the game and the companion go when Android moves
the game (Recents) or a display goes away is ScreenPlace. Both rules (ScreenKeys, ScreenPlace) run on the JVM with android/app/src/jvmtest's tests;
skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PEANUTS = REPO / 'android/app/src/main/java/dev/peanuts'
JVMTEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts'
GAME = REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Keys(unittest.TestCase):
    def jvm(self, name):
        with tempfile.TemporaryDirectory(prefix='screen_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(PEANUTS / (name + '.java')), str(JVMTEST / (name + 'JvmTest.java'))], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.' + name + 'JvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_jvm(self):
        self.jvm('ScreenKeys')

    def test_place_jvm(self):
        self.jvm('ScreenPlace')

    def test_constants_match_android(self):
        # KeyEvent.KEYCODE_BACK / ACTION_UP / FLAG_CANCELED, InputDevice.SOURCE_DPAD / SOURCE_GAMEPAD / SOURCE_JOYSTICK, Display.INVALID_DISPLAY / DEFAULT_DISPLAY
        src = (PEANUTS / 'ScreenKeys.java').read_text(encoding='utf-8')
        self.assertIn('KEYCODE_BACK = 4, ACTION_UP = 1, FLAG_CANCELED = 0x20', src)
        self.assertIn('SOURCE_DPAD = 0x201, SOURCE_GAMEPAD = 0x401, SOURCE_JOYSTICK = 0x1000010', src)
        self.assertIn('INVALID = -1, DEFAULT = 0', (PEANUTS / 'ScreenPlace.java').read_text(encoding='utf-8'))


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
        self.assertIn('content(getIntent());', self.oncreate)
        content = self.screen[self.screen.index('private void content('):self.screen.index('protected void onNewIntent(')]
        self.assertIn('new CompanionPage(this, p,', content)   # the panel's window, which the page makes NOT_FOCUSABLE (black while a game is on the TV: test_tv_screens.py)
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


class Placement(unittest.TestCase):
    """Recents (a phone's starts the task on its own display), Home, a display that goes away: the game goes back to its display, the companion where it belongs"""
    def setUp(self):
        self.companion = (PEANUTS / 'Companion.java').read_text(encoding='utf-8')
        self.screen = (PEANUTS / 'CompanionScreen.java').read_text(encoding='utf-8')

    def test_the_game_reports_each_return_to_the_front(self):
        game = GAME.read_text(encoding='utf-8')
        hook = [l for l in game.splitlines() if 'onTopResumedActivityChanged' in l]
        self.assertEqual(len(hook), 1, hook)
        self.assertIn('if (top) dev.peanuts.Companion.gameOnTop(this);', hook[0])
        self.assertIn('// PEANUTS:', hook[0])

    def test_back_and_on_top_decide_with_screenplace(self):
        back = self.companion[self.companion.index('public static void back(Activity game)'):self.companion.index('public static void gameOnTop')]
        self.assertIn('place(game);', back)
        self.assertNotIn('show(game', back)   # the companion is shown only once the game is on its display
        place = self.companion[self.companion.index('private static void place(Activity game)'):self.companion.index('private static boolean moveHome')]
        self.assertIn('ScreenPlace.decide(', place)
        self.assertIn('else if (!CompanionScreen.inFront() && ', place)   # paused behind the launcher (AYN Recents, Home): brought forward
        self.assertIn('setLaunchDisplayId(id)', self.companion[self.companion.index('private static boolean moveHome'):])

    def test_home_is_the_start_display_and_forgotten_with_it(self):
        start = self.companion[self.companion.index('public static void start(Activity game, String gameName)'):self.companion.index('public static void stop()')]
        self.assertIn('home = displayOf(game);', start)
        self.assertIn('if (id == home) home = Display.INVALID_DISPLAY;', start)
        self.assertIn('place(game); }, DISPLAY_SETTLE_MS)', start)

    def test_without_per_display_focus_the_built_in_display_stays_on_top(self):
        refocus = self.companion[self.companion.index('static void refocusGame()'):self.companion.index('static void screenGone()')]
        self.assertIn('if (!perDisplay ||', refocus)
        touched = self.companion[self.companion.index('static void screenTouched()'):]
        self.assertIn('if (perDisplay || !screenWanted() || s == null || CompanionScreen.onTop()', touched[:touched.index('screenToFront();')])
        self.assertIn('Companion.screenTouched();', self.screen)
        self.assertIn('screenToFront();', touched[:touched.index('private static void screenToFront()')])
        self.assertIn('ACTION_DOWN', self.screen[self.screen.index('dispatchTouchEvent'):self.screen.index('Companion.screenTouched();')])

    def test_sticks_reach_the_game_too(self):
        self.assertIn('if (ScreenKeys.joystick(e.getSource()) && Companion.gameMotion(e)) return true;', self.screen)
        self.assertIn('requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK)', self.screen)
        self.assertIn('game.dispatchGenericMotionEvent(e)', self.companion)


if __name__ == '__main__':
    unittest.main()
