"""Home's five views (dev.peanuts.ui.HomeViews; on the JVM with android/app/src/jvmtest's HomeViewsJvmTest, skipped without a JDK): Grid, Row, Compact,
Carousel and Covers, the View button and Settings › App › Look › Home view, the dock on a phone held upright, the one-screen rule (Row and Carousel
take the chosen game's facts to its cover when no bottom screen shows its card), the strip's keys and swipes, the covers' marks and the NO SUPPORT tape.
The checks below read the sources: what must stay wired for the views to work."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/HomeViewsJvmTest.java'
LEDGER = REPO / 'android/app/src/main/java/dev/peanuts/launch/PlayLedger.java'


def method(src, signature):
    """The body of the method whose declaration contains signature (up to the closing brace at four spaces)"""
    i = src.index(signature)
    j = src.index('\n    }\n', i)
    return src[i:j]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Rules(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='homeviews_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'HomeViews.java'), str(LEDGER), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.HomeViewsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Library(unittest.TestCase):
    def setUp(self):
        self.src = (UI / 'Library.java').read_text(encoding='utf-8')
        self.render = method(self.src, 'private void render() {')

    def test_views_drawn(self):
        r = self.render
        self.assertIn('view = HomeViews.of(PeanutsPrefs.homeView(a));', r)
        self.assertIn('dual = BottomScreen.twoScreens(a);', r, 'the one-screen rule asks the bottom screen')
        self.assertIn('else if (HomeViews.shelf(view)) shelf(show);', r)
        self.assertIn('for (Row r : show) cells.add(compactCell(r));', r)
        self.assertIn('boolean named = view != HomeViews.COVERS;', r, 'Covers carries no names')
        self.assertIn('if (show.isEmpty() && !find.isEmpty()) noHit();', r, 'Find with no game: a sentence and Clear')
        self.assertLess(r.index('if (filter == BUILDING) {'), r.index('else if (HomeViews.shelf(view)) shelf(show);'), 'the Building tab keeps its cards in every view')

    def test_signature_follows_the_view(self):
        sig = method(self.src, 'private String signature(')
        self.assertIn('.append(view).append(dual).append(docked())', sig, 'a new view, a bottom screen that went, a turn of the phone: drawn again')

    def test_one_screen_rule(self):
        facts = method(self.src, 'private void fillFacts(Cover k) {')
        self.assertIn('if (dual) return;   // the bottom screen\'s card has the rest', facts)
        self.assertLess(facts.index('if (dual) return;'), facts.index('TextView pt = playText(e);'), 'the play time and the buttons only on one screen')
        self.assertIn('static void screensChanged() {', self.src)
        bs = (UI / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('static boolean twoScreens(Activity a) {', bs)
        self.assertGreaterEqual(bs.count('screensChanged();'), 4, 'the setting, a display that came, went or turned off, a game on the screen')

    def test_strip(self):
        shelf = (UI / 'HomeShelf.java').read_text(encoding='utf-8')
        self.assertIn('HomeViews.settle(pos, v, covers.size())', shelf, 'a swipe settles on a game, never past the ends')
        self.assertIn('getParent().requestDisallowInterceptTouchEvent(true);', shelf, 'a sideways drag is the strip\'s, not the page\'s')
        self.assertIn('case KeyEvent.KEYCODE_DPAD_LEFT:', shelf)
        self.assertIn('ACTION_SCROLL_FORWARD', shelf, 'TalkBack scrolls it')
        self.assertIn('setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);', shelf, 'one focus stop for a controller')
        lib = method(self.src, 'private void shelf(List<Row> show) {')
        self.assertIn('sh.setFocusedByDefault(true);', lib)
        self.assertIn('return shoulders.onKey(v, code, ev);', lib, 'L and R still switch the tabs')

    def test_buttons_and_dock(self):
        tools = method(self.src, 'private void tools(boolean any, boolean canBuildAll) {')
        self.assertIn('headerTools.row.setVisibility(any && !docked ? View.VISIBLE : View.GONE);', tools, 'no buttons without games, the dock on a phone upright')
        self.assertIn('d.shown(docked && any);', tools)
        main = (REPO / 'android/app/src/main/java/dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('trailing.addView(dev.peanuts.ui.Library.headerTools(this));', main, 'the hook in buildHome')
        dock = (UI / 'HomeDock.java').read_text(encoding='utf-8')
        self.assertIn('WindowInsets.Type.ime()', dock, 'the search field stays above the keyboard')

    def test_settings(self):
        sec = (UI / 'SettingsSections.java').read_text(encoding='utf-8')
        self.assertIn('new Group("Look", r("Home view", "library")),', sec)
        lib = (UI / 'LibrarySettings.java').read_text(encoding='utf-8')
        self.assertIn('"Home view"', lib)
        self.assertIn('Library.chooseView(a, LibrarySettings::refreshRows)', lib)

    def test_marks(self):
        cover = method(self.src, 'private Cover cover(Row r, int coverDp, int padDp, boolean marks) {')
        self.assertIn('CoverMarks.greyed(tile);', cover)
        self.assertIn('new CoverMarks.Tape(', cover, 'no profile: the NO SUPPORT tape')
        mark = method(self.src, 'private View mark(Cover k, int markDp) {')
        for icon in ('"stop"', '"update"', '"check"', '"folder"', '"layers"', '"build"'):
            self.assertIn(icon, mark)
        self.assertIn('pill.grow();', mark, 'a build that starts grows its mark into the pill')
        marks = (UI / 'CoverMarks.java').read_text(encoding='utf-8')
        self.assertIn('hammer.still(!Motion.on());', marks, 'Motion off: the hammer stands still')

    def test_marks_on_a_letter_cover(self):
        """A cover without its picture is PUi.tile's letter on banana yellow, the yellow marks' own colour: the pill, Build needed and a new update keep an edge there"""
        pui = (UI / 'PUi.java').read_text(encoding='utf-8')
        self.assertIn('t.setBackground(round(col(c, R.color.bn_banana), dp(c, 16), 0, 0));', method(pui, 'public static TextView tile('),
                      'the letter tile is banana yellow: if that changes, so does the reason for the edge')
        cover = method(self.src, 'private Cover cover(Row r, int coverDp, int padDp, boolean marks) {')
        self.assertLess(cover.index('k.letter = bm == null;'), cover.index('mark(k, '), 'known before the mark is made')
        mark = method(self.src, 'private View mark(Cover k, int markDp) {')
        self.assertIn('int edge = k.letter ? R.color.bn_on_banana : 0;', mark)
        self.assertIn('new CoverMarks.BuildPill(a, markDp, k.ownBuild ? Math.max(0, b.percent) : -1, k.letter);', mark)
        self.assertIn('round(markDp, R.color.bn_banana, edge, "update", R.color.bn_on_banana)', mark, 'a new update')
        self.assertIn('round(markDp, R.color.bn_banana, edge, "build", R.color.bn_on_banana)', mark, 'Build needed')
        self.assertNotIn('round(markDp, R.color.bn_banana, 0,', mark, 'no yellow mark without the edge on a letter tile')
        marks = (UI / 'CoverMarks.java').read_text(encoding='utf-8')
        pill = marks[marks.index('static final class BuildPill'):marks.index('// ---- The NO SUPPORT tape ----')]
        self.assertIn('BuildPill(Context c, int markDp, int percent, boolean edged) {', pill)
        self.assertIn('edge = edged ? onBanana : 0;', pill)
        draw = pill[pill.index('@Override protected void onDraw(Canvas c) {'):]
        self.assertLess(draw.index('c.restoreToCount(save);'), draw.index('c.drawRoundRect(r, h / 2f - sw, h / 2f - sw, stroke);'),
                        'the edge is drawn after the clip, inside the pill')

    def test_icons(self):
        icons = (REPO / 'peanuts/scripts/icons.py').read_text(encoding='utf-8')
        views = (UI / 'HomeViews.java').read_text(encoding='utf-8')
        for name in ('view_row', 'view_carousel', 'view_covers', 'stop'):
            self.assertIn(f'"{name}":', icons)
        self.assertIn('{"library", "view_row", "list", "view_carousel", "view_covers"}', views)


if __name__ == '__main__':
    unittest.main()
