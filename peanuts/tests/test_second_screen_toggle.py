"""The second screen during play (measured on the AYN Thor, 2026-11-24): a tap on another app's window there (the Thor's launcher on Screen-2) made that app
Android's top one, the game's process went from top-app to foreground and its threads to the small cores. So Peanuts' page is on by default for games without a
companion (its window is the game's process), the game's threads go to the fast cores while Android has it out of top-app, and a four-finger tap on the game
hides or shows the companion or the page (dev.peanuts.Companion.toggleScreen)."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PEANUTS = REPO / 'android/app/src/main/java/dev/peanuts'
GAME = REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'


def body(src, start, end):
    return src[src.index(start):src.index(end, src.index(start))]


class PageByDefault(unittest.TestCase):
    def test_games_without_a_companion_keep_peanuts_page_by_default(self):
        prefs = (PEANUTS / 'PeanutsPrefs.java').read_text(encoding='utf-8')
        self.assertIn('return of(c).getBoolean("bottom_screen_games", true);', prefs)
        # an old player's cover: decided from the stored value, not the default
        self.assertIn('!p.contains("bottom_screen_games_page") && p.getBoolean("bottom_screen_games", false)', prefs)


class Toggle(unittest.TestCase):
    def setUp(self):
        self.c = (PEANUTS / 'Companion.java').read_text(encoding='utf-8')

    def test_every_way_back_onto_the_screen_respects_hidden(self):
        self.assertIn('if (hidden || wanted < 0 || listenerDm == null) return;', body(self.c, 'private static void openPage', '\n    }\n'))
        self.assertIn('away || hidden ||', body(self.c, 'private static void show(Activity game', '\n    }\n'))
        self.assertIn('if (hidden) return;', body(self.c, 'case ScreenPlace.SHOW:', 'default:'))
        self.assertIn('wanted >= 0 && !hidden', body(self.c, 'public static void back(Activity game)', 'public static void gameOnTop'))

    def test_each_run_starts_shown(self):
        self.assertIn('hidden = false;', body(self.c, 'public static void start(Activity game', 'public static void stop()'))

    def test_toggle_hides_like_away_and_brings_the_page_when_nothing_was_there(self):
        t = body(self.c, 'public static Boolean toggleScreen(Activity game)', '/** Starts the game hold')
        self.assertIn('shown.hide()', t)            # hidden, not dismissed: the companion comes back with its page
        self.assertIn('Bridge.present(false)', t)   # held fingers released, as away()
        self.assertIn('GameScreen.away()', t)
        self.assertIn('wanted = GameScreen.PLAYING', t)
        self.assertIn('if (allowed) place(game);', t)


class Gesture(unittest.TestCase):
    def setUp(self):
        self.g = GAME.read_text(encoding='utf-8')

    def test_four_fingers_cancel_the_games_touches_and_toggle(self):
        d = body(self.g, 'public boolean dispatchTouchEvent(MotionEvent e)', 'private void fourFingerTap()')
        self.assertIn('e.getPointerCount() == 4', d)
        self.assertIn('ACTION_CANCEL', d)
        self.assertIn('fourFingerTap()', d)
        self.assertIn('Companion.toggleScreen(this)', body(self.g, 'private void fourFingerTap()', '\n    }\n'))


class FastCoresAway(unittest.TestCase):
    def test_threads_placed_while_out_of_top_app(self):
        g = GAME.read_text(encoding='utf-8')
        tick = body(g, 'private void pinTick()', 'private void pinStart()')
        self.assertIn('cpuGroup()', tick)
        self.assertIn('!group.equals("top-app")', tick)
        self.assertIn('Native.pinThreads()', tick)
        self.assertIn('Native.unpinThreads()', tick)   # Android's move back to top-app left the threads placed (Thor, 2026-11-24)
        self.assertRegex(g, re.compile(r'onTopResumedActivityChanged\(boolean top\).*topResumed = top;.*else if \(started\) pinStart\(\);'))
        self.assertIn('indexOf(":cpu:/")', body(g, 'static String cpuGroup()', '\n    }\n'))


if __name__ == '__main__':
    unittest.main()
