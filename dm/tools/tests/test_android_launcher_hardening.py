"""0.1.8 hardening of the launcher package's Peanuts code (GameActivity, UserSaves): the four-finger tap follows its fingers by pointer ID, the thread
placement loop does not scan /proc each second while the game is stopped, and the Saves tab's user cards hold no destroyed launcher screen."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
PKG = REPO / 'android/app/src/main/java/dev/nxnative/android'


def body(src, start, end):
    return src[src.index(start):src.index(end, src.index(start))]


class FourFingerTap(unittest.TestCase):
    def test_fingers_are_followed_by_pointer_id(self):
        # A finger lifted before the others shifts the pointer indices of the MOVE events that follow: compared by index, a still finger read as a moved one
        # and the tap was spoiled
        d = body((PKG / 'GameActivity.java').read_text(encoding='utf-8'), 'public boolean dispatchTouchEvent(MotionEvent e)', 'private void fourFingerTap()')
        self.assertIn('fourId[i] = e.getPointerId(i)', d)
        self.assertIn('e.findPointerIndex(fourId[i])', d)
        self.assertNotIn('e.getX(i) - fourX[i]', d)


class PinLoop(unittest.TestCase):
    def setUp(self):
        self.g = (PKG / 'GameActivity.java').read_text(encoding='utf-8')

    def test_no_placement_while_stopped(self):
        tick = body(self.g, 'private void pinTick()', 'private void pinStart()')
        self.assertLess(tick.index('if (!asked && stopped) return;'), tick.index('cpuGroup()'))

    def test_the_loop_starts_again_at_on_start_and_undoes_a_placement(self):
        self.assertIn('|| !topResumed || pinnedAway) ui.postDelayed(pinRun, 2000);', body(self.g, 'private void pinStart()', '\n    }\n'))
        start = body(self.g, 'protected void onStart()', '\n    }\n')
        self.assertLess(start.index('if (!started) return;'), start.index('pinStart();'))
        self.assertLess(start.index('stopped = false;'), start.index('pinStart();'))

    def test_a_recreated_screen_knows_the_placement(self):
        # attach() (a screen recreated while the game runs) calls pinStart: with pinnedAway process-wide it undoes the old screen's placement in top-app
        self.assertIn('private static boolean pinnedAway;', self.g)
        self.assertIn('pinStart();', body(self.g, 'prewarmTick();   // PEANUTS: restore the progress cover after recreation', '\n    }\n'))


class UserSavesTab(unittest.TestCase):
    def test_the_shown_tab_is_held_weakly(self):
        s = (PKG / 'UserSaves.java').read_text(encoding='utf-8')
        self.assertIn('private static WeakReference<Tab> tab;', s)
        self.assertNotRegex(s, re.compile(r'private static Tab tab;'))


if __name__ == '__main__':
    unittest.main()
