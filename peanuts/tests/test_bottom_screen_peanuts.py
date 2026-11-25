"""The bottom screen's peanuts pop when tapped, and count, as the top screen's do (UPSTREAM-EDITS Round 43).

The launcher's page on the second display (dev.peanuts.ui.BottomPanel) draws upstream's BananaBackgroundView under its pages, but nothing routed a tap to
its popAt: tapping the peanuts there did nothing. The tap test (dev.peanuts.ui.BackgroundTap: a press on the background that moves no more than the slop and
lifts before a long press, as MainActivity.bananaTaps) runs on the JVM; javac compiles it with android/app/src/jvmtest's BackgroundTapJvmTest. Skipped
without a JDK. The rest reads the sources: the panel feeds every touch to it without taking any, pops the peanut under a tap, and the pop is counted through
MainActivity's own counter (Prefs.addBananaPopped and Settings' line), with upstream's pop animation and counter left as they are."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/BackgroundTapJvmTest.java'


def read(p):
    return p.read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Tap(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='bgtap_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'BackgroundTap.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.BackgroundTapJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Panel(unittest.TestCase):
    def setUp(self):
        self.src = read(UI / 'BottomPanel.java')

    def test_touches_reach_the_peanuts(self):
        m = re.search(r'public boolean dispatchTouchEvent\(MotionEvent e\) \{(.*?)\n    \}\n', self.src, re.S)
        self.assertIsNotNone(m, 'BottomPanel does not look at its touches')
        body = m.group(1)
        self.assertIn('bg.popAt(', body)
        self.assertIn('tap.down(', body)
        self.assertIn('emptyAt(root', body)
        self.assertIn('return super.dispatchTouchEvent(e);', body, 'the panel must never take a touch from its buttons')
        self.assertIn('page != LAUNCH', body, 'the start page is not a background')

    def test_pop_is_counted(self):
        self.assertRegex(self.src, r'bg\.setOnPop\(\(\) -> \{[^}]*BottomScreen\.popped\(\)')
        self.assertIn('CLOCK_TICK', self.src)
        bs = read(UI / 'BottomScreen.java')
        self.assertIn('public static void onPop(Activity a, Runnable counted)', bs)
        self.assertRegex(bs, r'if \(x == popsOf\) \{ popsOf = null; pops = null; \}')

    def test_main_activity_counts_as_on_top(self):
        src = read(MAIN / 'MainActivity.java')
        hook = [l for l in src.splitlines() if 'dev.peanuts.ui.BottomScreen.onPop(' in l]
        self.assertEqual(len(hook), 1, 'one hook line')
        self.assertIn('Prefs.addBananaPopped(this)', hook[0])
        self.assertIn('poppedText.setText(poppedLine(n))', hook[0])
        self.assertIn('// PEANUTS:', hook[0])

    def test_upstream_pop_unchanged(self):
        # package 2 replaces the pop and the counter: this fix leaves both as upstream has them
        view = read(MAIN / 'BananaBackgroundView.java')
        self.assertIn('private static final long POP_MS = 260, RESPAWN_MS = 5000, GROW_MS = 380;', view)
        self.assertIn('static long addBananaPopped(Context c)', read(MAIN / 'Prefs.java'))
        self.assertIn('"Peanuts cracked: "', read(MAIN / 'MainActivity.java'))   # upstream's Settings card says what Peanuts' About says (2026-11-24)


if __name__ == '__main__':
    unittest.main()
