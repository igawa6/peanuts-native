"""Back keeps your place: Settings, Advanced and a game's page come back where they were (UPSTREAM-EDITS Round 41).

MainActivity builds a page anew for every change, in a new ScrollView without a view id, so nothing kept where the page was: Back from Advanced, a rotation
or a window resize, and the activity made again (dark mode, a fold, Android ending the process) showed Settings at the top, and a controller's focus on its
first row. dev.peanuts.ui.PagePlace takes the scroll position and the focused row before such a rebuild and puts them back once the new page is laid out;
the row is found again by its words (dev.peanuts.ui.PlacePick, on the JVM with android/app/src/jvmtest's PagePlaceJvmTest; skipped without a JDK).
The rest reads the hooks in MainActivity."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android/MainActivity.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/PagePlaceJvmTest.java'


def method(src, signature):
    """The body of the method whose declaration contains signature (up to the closing brace at four spaces)"""
    i = src.index(signature)
    j = src.index('\n    }\n', i)
    return src[i:j]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Pick(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='pageplace_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'PlacePick.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.PagePlaceJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Hooks(unittest.TestCase):
    def setUp(self):
        self.src = MAIN.read_text(encoding='utf-8')

    def test_advanced_back(self):
        open_ = method(self.src, 'void openAdvanced(')
        self.assertLess(open_.index('PagePlace.cover(pageScroll())'), open_.index('build();'), 'the place is taken before Advanced replaces the page')
        close = method(self.src, 'private void closeAdvanced(')
        self.assertLess(close.index('build();'), close.index('PagePlace.uncover(pageScroll())'), 'and put back once the page is built again')

    def test_rotation_keeps_every_page(self):
        conf = method(self.src, 'public void onConfigurationChanged(')
        self.assertLess(conf.index('PagePlace.of(pageScroll())'), conf.index('build();'))
        self.assertIn('place.restore(pageScroll())', conf)
        self.assertIn('onHome() ? null', conf, "Home keeps its own place (dev.peanuts.ui.Library)")

    def test_recreation(self):
        save = method(self.src, 'protected void onSaveInstanceState(')
        self.assertIn('PagePlace.save(out, pageScroll(), advancedOpen)', save)
        create = method(self.src, 'protected void onCreate(')
        self.assertLess(create.index('build();'), create.index('PagePlace.restore(saved, pageScroll())'))

    def test_hooks_marked(self):
        lines = [l for l in self.src.splitlines() if 'PagePlace' in l or 'place.restore(' in l]
        self.assertEqual(len(lines), 6, lines)
        for l in lines:
            self.assertIn('// PEANUTS', l, l)

    def test_first_row_focus_waits_for_the_place(self):
        # MainActivity.build posts its first-row focus; PagePlace focuses in the first layout, which runs before that post, so the post finds the focus set
        build = method(self.src, 'private void build()')
        self.assertIn('getCurrentFocus() != null && frame.hasFocus()) return;', build)
        place = (UI / 'PagePlace.java').read_text(encoding='utf-8')
        self.assertIn('addOnGlobalLayoutListener', place)
        self.assertIn('if (first && index >= 0) {', place)
        self.assertIn('if (!s.isInTouchMode()) { focus(s);', place)
        # a window made again starts in touch mode: the row comes back with the controller's first press, before Android picks the first row shown
        self.assertIn('addOnTouchModeChangeListener', place)


if __name__ == '__main__':
    unittest.main()
