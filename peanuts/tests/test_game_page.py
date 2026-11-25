"""A game's page in areas (dev.peanuts.ui.GamePage, GameAreas; dev.nxnative.android.GamePagePort): the areas' table on the JVM
(android/app/src/jvmtest's GameAreasJvmTest; skipped without a JDK), and the wiring: the marked hooks in GameSettings, the port's harvest of upstream's
tabs, Mods & Cheats as an area (CheatsPage.area), Back and Home, and a drawable for every icon the table names."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UI = APP / 'main/java/dev/peanuts/ui'
UP = APP / 'main/java/dev/nxnative/android'
DRAWABLE = APP / 'peanuts/res/drawable'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='gameareas_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'GameAreas.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/ui/GameAreasJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.GameAreasJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')

    def test_help_jvm(self):
        with tempfile.TemporaryDirectory(prefix='gamehelp_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'GameAreas.java'), str(UI / 'GameHelp.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/ui/GameHelpJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.GameHelpJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_hooks_in_game_settings(self):
        gs = (UP / 'GameSettings.java').read_text(encoding='utf-8')
        build = gs[gs.index('static void build(MainActivity a, LinearLayout content, Game g'):]
        first = build.split('\n')[1]
        self.assertIn('if (GamePagePort.build(a, content, g, again, close)) return;', first, 'the page in areas is build\'s first line')
        self.assertIn('// PEANUTS', first)
        self.assertRegex(gs, r'boolean wide = .*>= 600 && !GamePagePort\.harvesting\(\);\s+// PEANUTS')
        self.assertIn('{ Boolean s = GamePagePort.step(g, delta); if (s != null) return s; }   // PEANUTS', gs)
        self.assertIn('GamePagePort.at(t);', gs, 'Settings › Users opens the page at Saves')

    def test_port_harvests_upstreams_tabs(self):
        port = (UP / 'GamePagePort.java').read_text(encoding='utf-8')
        self.assertIn('if (harvesting || !dev.peanuts.ui.GamePage.used(a)) return false;', port)
        self.assertIn('harvesting = true;', port)
        self.assertRegex(port, r'finally \{\s+harvesting = false;\s+tab\(was\);', 'upstream\'s tab is put back, whatever happens')
        self.assertIn('off(a, g, GameSettings.PERFORMANCE, again, close)', port)
        self.assertNotIn('GameSettings.GAME, again', port, 'one build of the page has every section: no second one')
        gs = (UP / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('Section[] all = GamePagePort.harvesting() ? new Section[] {picture, speed, options, controls, advanced} : perf ?', gs)
        self.assertIn('a.gameTab(f.saves, g, true)', port)
        self.assertIn('a.gameTab(f.storage, g, false)', port)

    def test_mods_and_cheats_is_an_area(self):
        cp = (APP / 'main/java/dev/peanuts/mods/CheatsPage.java').read_text(encoding='utf-8')
        self.assertIn('public static void area(Activity a, LinearLayout pane', cp)
        for at in ('public static boolean build(', 'public static boolean onBack(', 'public static boolean stepTab('):
            i = cp.index(at)
            self.assertIn('if (inArea) return false;', cp[i:i + 400], f'{at} stands aside in the area')
        self.assertIn('if (own) stick(content, top);', cp, 'no top that stays in the area')
        page = (UI / 'GamePage.java').read_text(encoding='utf-8')
        self.assertIn('dev.peanuts.mods.CheatsPage.area(a, body, f.titleId, f.name, f.gameDir, f.packDir, f.again);', page)

    def test_back_and_home(self):
        sp = (UI / 'SettingsPage.java').read_text(encoding='utf-8')
        self.assertIn('if (GamePage.onBack(a)) return true;', sp)
        self.assertIn('GamePage.left();', sp)
        bs = (UI / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('GamePage.left();', bs)

    def test_y_plays_but_not_in_mods_and_cheats(self):
        page = (UI / 'GamePage.java').read_text(encoding='utf-8')
        self.assertIn('e.getKeyCode() != KeyEvent.KEYCODE_BUTTON_Y || GameAreas.MODS.equals(area)', page)
        ys = [l.strip() for l in page.splitlines() if '"Y"' in l]
        self.assertTrue(all('PLAY = {"Y", "Play"}' in l for l in ys), f'Y only in the keys of the second screen, no Y glyph on the Play button: {ys}')

    def test_every_icon_has_a_drawable(self):
        src = (UI / 'GameAreas.java').read_text(encoding='utf-8')
        table = src[src.index('static {'):src.index('/** The headings upstream builds')]
        icons = {i for _, i in re.findall(r'"([A-Z0-9][^"]*)", "([a-z][a-z0-9-]*)"', table)}   # group(...): title, icon
        icons |= set(re.findall(r'new Area\([A-Z]+, "[^"]+", "([a-z-]+)"', src))
        icons |= {v for v in re.findall(r'OPTION_EMOJI\.put\("[^"]+", "([a-z-]+)"\)', src)}
        icons |= {i for row in re.findall(r'\{"[A-Z][^}]*\}', src[src.index('BY_VALUE = {'):]) for i in re.findall(r'"([a-z][a-z-]+)"', row)}
        icons |= {'game', 'update', 'dlc', 'warning'}   # Game files' rows (GamePage.filesIcons)
        self.assertGreater(len(icons), 50)
        missing = sorted(i for i in icons if not (DRAWABLE / f'pi_{i.replace("-", "_")}.xml').is_file())
        self.assertEqual([], missing, 'every icon the page names is a drawable of the app')

    def test_a_phone_gets_the_areas_too(self):
        page = (UI / 'GamePage.java').read_text(encoding='utf-8')
        self.assertIn('public static boolean used(Context c) { return true; }', page)
        self.assertIn('if (!wide) phoneTop(a, p, f);', page, 'a phone: the game at the top of the tiles')
        self.assertIn('if (!wide) h.addView(PUi.back(a, v -> backKey(a)));', page, 'a phone: an area is a page of its own, with Back')
        self.assertIn('boolean upright = !wide;', page, 'a phone: upright tiles in two columns')

    def test_the_second_screen_explains_the_page(self):
        page = (UI / 'GamePage.java').read_text(encoding='utf-8')
        self.assertIn('if (now != null) explain(now);', page, 'what the focus is on is explained')
        for f in ('private static void explainGame()', 'private static void explainArea(String key)', 'private static void explainPlay()',
                  'private static void explainRow(View row, String title, String areaKey)'):
            self.assertIn(f, page)
        self.assertIn('e.flip = () -> { if (sw.isEnabled()) { sw.toggle(); explainRow(row, title, areaKey); } };', page, 'a switch flips from the second screen')
        self.assertIn('if (v.getOnFocusChangeListener() != null) return true;', page, "Mods & Cheats' rows explain themselves")
        screen = (UI / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('public java.util.List<String[]> keys;', screen)
        panel = (UI / 'BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('boolean show = pad && e != null && e.keys != null && !e.keys.isEmpty();', panel, 'the keys only while a controller is connected')
        self.assertIn('if (page == EXPLAIN) bindKeys(shownExplain);', panel, 'a controller that comes or goes shows or hides them')
        self.assertIn('exCard.animate().cancel();', panel, 'a fade still running does not take the card back to nothing')

    def test_no_separator_glyphs_in_the_pages_words(self):
        for f in ('GamePage.java', 'GameAreas.java', 'GameHelp.java'):
            src = (UI / f).read_text(encoding='utf-8')
            for s in re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', src):
                self.assertNotIn('·', s, f'{f}: {s}')
                self.assertNotIn('—', s, f'{f}: {s}')


if __name__ == '__main__':
    unittest.main()
