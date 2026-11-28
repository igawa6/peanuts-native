"""Home's filter row shows only what has games: dev.peanuts.ui.HomeFilters (on the JVM with android/app/src/jvmtest's HomeFiltersJvmTest; skipped without a
JDK) says which chips show, and Library.render and its L/R shoulder keys follow it. A chip with no games ("Build needed 0", "Not Supported Yet 0") is not
shown, and when one state has every game the chip row goes ("All 12" beside "Ready 12" said the same twice), while Find, Sort, View and Build all stay
(in the header, or the dock on a phone held upright). The
Building chip is as before, the hidden-games toggle shows while games are hidden, and a chosen filter whose last game left goes back to All."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/HomeFiltersJvmTest.java'


def method(src, signature):
    """The body of the method whose declaration contains signature (up to the closing brace at four spaces)"""
    i = src.index(signature)
    j = src.index('\n    }\n', i)
    return src[i:j]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Rules(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='homefilters_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'HomeFilters.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.HomeFiltersJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Library(unittest.TestCase):
    def setUp(self):
        self.src = (UI / 'Library.java').read_text(encoding='utf-8')
        self.render = method(self.src, 'private void render() {')

    def test_same_filters(self):
        self.assertIn('ALL = HomeFilters.ALL, BUILDING = HomeFilters.BUILDING, READY = HomeFilters.READY, BUILD = HomeFilters.BUILD, '
                      'NO_PROFILE = HomeFilters.NO_PROFILE;', self.src, "one order of the filters, HomeFilters'")

    def test_render(self):
        r = self.render
        chips = r.index('boolean[] chipsOn = HomeFilters.chips(building, ready, toBuild, noProfile);')
        self.assertGreater(chips, r.index('default: noProfile++;'), 'after the counts')
        shown = r.index('filter = HomeFilters.shown(filter, chipsOn);')
        self.assertLess(shown, r.index('switch (filter) {'), 'the grid shows the filter that is left')
        for line in ('if (chipsOn[ALL]) chip(chipRow, "All " + all, ALL);',
                     'if (chipsOn[READY]) chip(chipRow, "Ready " + ready, READY);',
                     'if (chipsOn[BUILD]) chip(chipRow, "Build needed " + toBuild, BUILD);',
                     'if (chipsOn[NO_PROFILE]) chip(chipRow, a.getString(R.string.not_supported_yet) + " " + noProfile, NO_PROFILE);'):
            self.assertIn(line, r)
        self.assertIn('if (building > 0) {', r, 'the Building chip as before')
        self.assertIn('if (hidden > 0) {', r, 'the hidden games toggle as before')
        tools = method(self.src, 'Tools(Activity a, boolean dock) {')
        for kept in ('"Build all"', 'R.drawable.ic_sort', '"Find"', '"View"'):
            self.assertIn(kept, tools, 'the buttons stay without a tab row (in the header, or the dock on a phone upright)')
        self.assertIn('tools(any, any && offersBuildAll(rows));', r, 'render says what they show')

    def test_shoulders(self):
        step = method(self.src, 'private void stepFilter(View v, int d) {')
        self.assertIn('HomeFilters.step(filter, HomeFilters.chips(hasBuilding ? 1 : 0, stateCounts[0], stateCounts[1], stateCounts[2]), d)', step)
        self.assertLess(step.index('if (next == filter) return;'), step.index('Haptics.play(v, Haptics.Word.CLICK);'), 'no click when nothing changes')
        self.assertIn('stateCounts = new int[] {ready, toBuild, noProfile};', self.render)

    def test_focus_of_a_chip_that_went(self):
        after = method(self.src, 'private void afterRender(')
        self.assertIn('keep.startsWith("chip:")) v = keyed.containsKey("chip:" + filter) ? keyed.get("chip:" + filter) : firstCover;', after)


if __name__ == '__main__':
    unittest.main()
