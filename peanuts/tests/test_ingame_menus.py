"""The in-game menus: the game menu at the left (dev.peanuts.ingame.GameMenu) and the quick
menu at the right (upstream's SidePanel with its tabs on a rail, dev.peanuts.ingame.Rail), joined by dev.nxnative.android.InGamePanel. Checks the marked
hooks in upstream's files, the rail's order and icons, the game menu's rows, the solid icons against design's icons.py output, and the text the player
reads against UI-RULES.md. On the JVM (InGameBackupJvmTest with SaveStore): Back up the save beside the host's Commit. Skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
LAUNCHER = APP / 'main/java/dev/nxnative/android'
INGAME = APP / 'main/java/dev/peanuts/ingame'
RES = APP / 'peanuts/res'
UI_FILES = [LAUNCHER / 'InGamePanel.java', INGAME / 'GameMenu.java', INGAME / 'Rail.java', INGAME / 'Look.java', INGAME / 'GameRows.java', INGAME / 'BottomMenu.java', INGAME / 'BottomScreen.java', APP / 'main/java/dev/peanuts/ui/MenuFeel.java',
            APP / 'main/java/dev/peanuts/ui/LayoutChoice.java', APP / 'main/java/dev/peanuts/ui/QuickButton.java']


def marked(src, call):
    """The line with call carries a PEANUTS marker"""
    return any(call in line and 'PEANUTS' in line for line in src.splitlines())


class Hooks(unittest.TestCase):
    def test_side_panel(self):
        p = (LAUNCHER / 'SidePanel.java').read_text(encoding='utf-8')
        for call in ('InGamePanel.frame(this, sh, tabs, scroll)', 'InGamePanel.rail(this, tab, this::showTab)', 'InGamePanel.railFocused()',
                     'InGamePanel.slideIn(this, sheet)', 'InGamePanel.slideOut(this, sheet,', 'InGamePanel.panelBack(this)', 'InGamePanel.laid(this, box, tab)'):
            self.assertTrue(marked(p, call), f'SidePanel: the marked hook {call}')
        self.assertRegex(p, r'static int TAB_CONTROLS = 2, TAB_GENERAL = 3;\s+// PEANUTS', 'Controls moves behind Cheats and Mods')
        self.assertNotIn('setDuration(180)', p, "upstream's open animation is the quick menu's (InGamePanel.slideIn)")

    def test_game_activity(self):
        g = (LAUNCHER / 'GameActivity.java').read_text(encoding='utf-8')
        for call in ('InGamePanel.attach(this, root, panel)', 'InGamePanel.key(this, combo, k, down,', 'InGamePanel.toggle(this, true)',
                     'InGamePanel.backKey(this, e, prewarmCover != null)',
                     'InGamePanel.menuKey(this, e, gamepad(e.getSource()))', 'InGamePanel.menuMotion(this, e)', 'InGamePanel.back(this)',
                     'static File runSave()'):
            self.assertTrue(marked(g, call), f'GameActivity: the marked hook {call}')
        self.assertNotIn('panel.padToggle()', g, 'the controller opens the game menu, not the quick menu')
        self.assertLess(g.index('InGamePanel.menuKey('), g.index('if (panel.isOpen() && gamepad(e.getSource()))'), 'the game menu takes the controller first')
        self.assertLess(g.index('InGamePanel.backKey('), g.index('InGamePanel.key(this, combo'), "the controller's Back before the combination")
        p = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        back = p[p.index('static boolean backKey('):]
        back = back[:back.index('\n    }\n') + 6]
        self.assertIn('controllerBack(e)', back, "only the controller's own Back")
        self.assertIn('supportsSource(InputDevice.SOURCE_GAMEPAD)', p, "the device tells a controller's Back: Android gives it the keyboard's source alone")
        self.assertIn('FLAG_FALLBACK', p, "a Back Android made from an unused B is not the controller's Back")
        self.assertIn('PadPanel.HOLD_MS', back, 'held as long as Home for the quick menu')
        self.assertIn('toggle(a, true)', back, 'pressed: the game menu with the selection shown')
        plain = p[p.index('static void back(Activity a)'):]
        plain = plain[:plain.index('\n    }\n')]
        self.assertIn('padAttached()', plain, "Android's own Back (a key tool handed it on) with the selection while a controller is attached")
        self.assertIn('menu.at(quickRow);', p, 'B from the quick menu: back on the Quick menu row')

    def test_save_store(self):
        s = (LAUNCHER / 'SaveStore.java').read_text(encoding='utf-8')
        self.assertIn('    private static Backup backupLocked(', s, "backupLocked stays upstream's, private")
        span = s[s.index('PEANUTS begin: the recovery apart from the copy'):]
        span = span[:span.index('PEANUTS end')]
        self.assertIn('static Backup backupRunning(', span, 'backupRunning, in its marked span')
        running = span[span.index('static Backup backupRunning('):]
        running = running[:running.index('\n    }\n')]
        self.assertNotIn('recover', running, "no recovery on the running game's save: a marker there is the host's own Commit, still being written")
        self.assertIn('if (running && pending(save)) throw', span, 'a marker before the copy fails it')
        self.assertTrue(marked(s, 'if (running && pending(save)) throw new IOException("the save changed while it was copied (the game started saving it)")'),
                        'and one after its second scan, before the backup gets its name')
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('SaveStore.backupRunning(save, to, SaveUi.stamp())', b, "the game menu's backup: SaveStore's own copy, without the recovery")
        self.assertNotIn('backupLocked', b)
        self.assertIn('GameActivity.runSave()', b, 'the save the game runs on (the player picked at the start)')
        self.assertIn('if (err.contains("no save folder")) break;', b, 'a Commit during the copy is tried again')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class BackupBesideACommit(unittest.TestCase):
    def test_jvm(self):
        """InGameBackupJvmTest: each state of the host's Commit as the copy starts, then a host committing beside backups (not on Windows)"""
        with tempfile.TemporaryDirectory() as d:
            srcs = [LAUNCHER / 'SaveStore.java', APP / 'jvmtest/java/dev/nxnative/android/InGameBackupJvmTest.java']
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, srcs)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.InGameBackupJvmTest'], capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
            self.assertIn('PASS', r.stdout)


class Menus(unittest.TestCase):
    def test_the_rail(self):
        mp = (LAUNCHER / 'ModsPanel.java').read_text(encoding='utf-8')
        order = re.findall(r'names\.add\((\w+|"\w+")\)', mp)
        self.assertEqual(order, ['CHEATS', 'MODS', '"Controls"', '"General"'], 'Performance, Display, then Cheats, Mods, Controls, the settings at the bottom')
        rail = (INGAME / 'Rail.java').read_text(encoding='utf-8')
        for tab, ic in (('Performance', 'bolt'), ('Display', 'display'), ('Cheats', 'cheats'), ('Mods', 'mods'), ('Controls', 'controller')):
            self.assertIn(f'case "{tab}": return "{ic}";', rail, f'{tab} has the solid {ic}')
        self.assertIn('"General".equals(tab) ? "Menu settings"', rail, "General is the menu's own settings")

    def test_the_game_menu(self):
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        rows = re.findall(r'new GameMenu\.Item\("([\w-]+)", "([^"]+)"', b)
        self.assertEqual([t for _, t in rows], ['Back to the game', 'Pause the game', 'Quick menu', 'Back up the save', 'Button mapping', 'Share log', 'Stop the game'])
        for icon, _ in rows:
            self.assertTrue((RES / 'drawable' / f'pis_{icon.replace("-", "_")}.xml').is_file(), f'the solid icon {icon}')
        gm = (INGAME / 'GameMenu.java').read_text(encoding='utf-8')
        self.assertIn('"Keep playing"', gm, 'Stop asks once, the safe choice first')
        self.assertIn('c.getDrawable(dev.nxnative.android.R.drawable.ic_chevron)', gm, "a row that opens a page shows the launcher's chevron")
        self.assertNotIn('"chevron-right"', gm, "there is no pi_chevron_right: Icons.get gave null, an empty space at the row's end")
        tab = (APP / 'main/java/dev/peanuts/mods/ModsTab.java').read_text(encoding='utf-8')
        self.assertIn('public static void fill(Activity a, LinearLayout box, Host host, int part)', tab, 'Cheats and Mods are two tabs')

    def test_solid_icons(self):
        styles = (RES / 'values/peanuts_ingame.xml').read_text(encoding='utf-8')
        for s in ('PeanutsSolidRest', 'PeanutsSolidSelected', 'PeanutsSolidMenu'):
            self.assertIn(f'<style name="{s}">', styles)
        for f in (RES / 'drawable').glob('pis_*.xml'):
            x = f.read_text(encoding='utf-8')
            self.assertIn('generated from icons.py', x, f'{f.name}: made by design/peanuts-native/icons/icons.py')
            self.assertNotRegex(x, r'fillColor="#(?!00000000)', f'{f.name}: colours come from the theme (?attr/pnSolid*)')

    def test_ui_text_rules(self):
        for f in UI_FILES:
            src = f.read_text(encoding='utf-8')
            for s in re.findall(r'"((?:[^"\\]|\\.)*)"', src):
                for bad, what in (('—', 'an em dash'), ('–', 'an en dash'), ('·', 'a middle dot'), (' • ', 'a bullet'), ('...', 'three dots'), ('…', 'an ellipsis')):
                    self.assertNotIn(bad, s, f'{f.name}: {what} in "{s}"')
                self.assertFalse(re.search('[\U0001F300-\U0001FAFF☀-➿]', s), f'{f.name}: an emoji in "{s}"')
                if ' ' in s and not s.startswith('(?') and not s.startswith('[RED]'):
                    self.assertNotIn('!', s, f'{f.name}: an exclamation mark in "{s}"')


class Feel(unittest.TestCase):
    def test_the_menus_feel(self):
        f = (APP / 'main/java/dev/peanuts/ui/MenuFeel.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean("menu_haptics", true)', f, 'the tick is on by default')
        self.assertIn('public static boolean sounds(Context c) { return Sounds.on(c); }', f, "Menu sounds is the app's Sounds switch (test_ui_sounds: on by default)")
        self.assertIn('Haptics.buzz(v, Haptics.Word.TICK)', f, "the launcher's haptics: its gaps and the system's setting")
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        for line in ('"Vibrate on each step"', '"Menu sounds"', 'MenuFeel.move(now)', 'MenuFeel.open(sheet)', 'MenuFeel.tab(box)'):
            self.assertIn(line, b)
        gm = (INGAME / 'GameMenu.java').read_text(encoding='utf-8')
        for line in ('MenuFeel.move(this)', 'MenuFeel.select(this)', 'MenuFeel.open(this)', 'MenuFeel.back(this)'):
            self.assertIn(line, gm)

    def test_console_details(self):
        stop = (RES / 'drawable/pis_stop.xml').read_text(encoding='utf-8')
        self.assertIn('M7.1 6.9a7.6 7.6 0 1 0 9.8 0', stop, 'Stop the game has the power sign')
        tab = (APP / 'main/java/dev/peanuts/mods/ModsTab.java').read_text(encoding='utf-8')
        self.assertIn('host.action("X", "All off"', tab, 'All off in the header with its button')
        pp = (APP / 'main/java/dev/peanuts/ui/PanelPolish.java').read_text(encoding='utf-8')
        self.assertIn('private static void inset(View v, float dp)', pp, 'the focus frame keeps clear of the first letter')
        self.assertIn('b.setMaxLines(1);', pp, 'a short choice: one line without the scroll that hides the frame')
        self.assertIn('public static View chosen(View old, View now)', pp, 'into a track of choices: onto the chosen one')
        self.assertIn('PanelPolish.landing(v)', (INGAME / 'Rail.java').read_text(encoding='utf-8'), 'a first row that is a track: its chosen choice')
        self.assertIn('b.setButtonDrawable(radioMark(dp, room ? in : 0));', pp, "a list of choices: a still mark of the panel's, clear of the frame")
        self.assertIn('ring(dp, ACCENT, true)', pp, 'the chosen one: a yellow ring with its dot')
        self.assertIn('if (b.getBackground() instanceof android.graphics.drawable.RippleDrawable) b.setBackground(null);', pp, "a list choice: no grey circle of the theme's")
        rail = (INGAME / 'Rail.java').read_text(encoding='utf-8')
        self.assertIn('if (direction == View.FOCUS_RIGHT) { View r = firstRow(); if (r != null) return r; }', rail, 'right from the rail: into the rows')
        self.assertIn('LinearLayout.HORIZONTAL) return v;', pp, 'a list one under the other keeps its step to the next row')
        panel = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('PanelPolish.chosen(old, now)', panel, 'the quick menu asks it at each step of the focus')
        self.assertIn('chosen.post(() -> { if (p.isOpen() && now.isFocused()) chosen.requestFocus(); });', panel, "after the change: the panel's frame follows the focus")

class Bottom(unittest.TestCase):
    def test_the_bottom_screens_menu(self):
        p = (LAUNCHER / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertTrue(marked(p, 'Rows rows() { return rows; }'), 'SidePanel gives its rows to the bottom panel, marked')
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('dev.peanuts.PeanutsPrefs.menuLayout(a) != dev.peanuts.PeanutsPrefs.LAYOUT_BOTTOM) return false', b, 'only with that layout')
        self.assertIn('if (oneMode(a)) { openOne(a, true, pad); return; }', b, 'Home pressed opens one menu on Game')
        self.assertIn('openOne(a, false, true)', b, 'Home held opens it on the last tab')
        self.assertIn('if (bottomMode(a)) openBottom(a, onGame, pad); else openRight(a, onGame, pad);', b, 'on the bottom screen, else at the right')
        self.assertIn('onActivityPaused(Activity x) { if (x == a && panelOf == panel) closeBottom(true); }', b, 'the screen is given back when the game leaves it')
        prefs = (APP / 'main/java/dev/peanuts/PeanutsPrefs.java').read_text(encoding='utf-8')
        self.assertIn('launcher.getInt("menu_layout_pick", LAYOUT_TWO)', prefs, 'two menus until one is picked')
        self.assertIn('game.getLong("menu_layout_at", 0) >= launcher.getLong("menu_layout_pick_at", 0)', prefs, 'the newer choice counts')
        screen = (INGAME / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('FLAG_NOT_FOCUSABLE', screen, 'the controller stays with the game')
        look = (INGAME / 'Look.java').read_text(encoding='utf-8')
        self.assertIn('UP_MS = 280, DOWN_MS = 220', look)
        for f, line in ((APP / 'main/java/dev/peanuts/ui/StatsView.java', 'BottomMenu.grip('), (APP / 'main/java/dev/peanuts/ui/GameScreen.java', 'BottomMenu.edgeRoot(')):
            self.assertIn(line, f.read_text(encoding='utf-8'), f'{f.name}: the grip and the swipe up')

    def test_one_menu_keys(self):
        panel = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('if (down && o.isInTouchMode()) { View f = o.findFocus(); if (f != null) f.requestFocusFromTouch(); }', panel, "the bottom screen's window leaves touch mode with a key: the frame shows")
        rail = (INGAME / 'Rail.java').read_text(encoding='utf-8')
        self.assertIn('head.setVisibility(View.GONE);', rail[rail.index('public void game()'):], "Game: its own header is the top, no Back and Game over it")
        self.assertIn('head.setVisibility(View.VISIBLE);', rail[rail.index('private void leaveGame()'):], 'a tab: its header again')
        self.assertIn('if (inRows) tabs.post(() -> { View r = firstRow(); if (r != null) r.requestFocus(); });', rail, 'L and R keep the controller in the rows')
        self.assertIn('return onGame && gameRows != null ? firstGameRow() : firstIn(content);', rail, "right from the rail on Game: Game's first row")

    def test_the_layout_is_picked_once(self):
        q = (APP / 'main/java/dev/peanuts/ui/LayoutChoice.java').read_text(encoding='utf-8')
        offer = q[q.index('public static void offer(Activity a) {'):]
        offer = offer[:offer.index('asked = true;')]
        self.assertIn('PeanutsPrefs.menuLayoutPicked(a)', offer, 'asked until one is picked')
        self.assertIn('later.setOnClickListener(v -> d.dismiss());', q, 'Later picks nothing: asked once more at the next start')
        self.assertIn('static final int TIMES = 2;', q, 'twice at most')
        self.assertIn('PeanutsPrefs.menuLayoutAsked(a) >= TIMES', offer, 'then no more')
        self.assertIn('PeanutsPrefs.menuLayoutAsked(a, PeanutsPrefs.menuLayoutAsked(a) + 1);', q, 'each time it shows')
        self.assertIn('if (!PeanutsPrefs.setupDone(a)) { setupRun = true; return; }', offer, "not in the run of the first start's setup: one question a start")
        self.assertIn('setupRun ||', offer)
        self.assertIn('View first = made[second ? count - 1 : 0];', q, 'two screens: the focus starts on the bottom screen\'s')
        self.assertIn('cards.setBaselineAligned(false);', q)
        self.assertIn('new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)', q, 'side by side, the cards are one size')
        self.assertIn('Math.round(PICTURE_DP * dp)', q, 'every picture in a box of one height, so the names line up')
        self.assertNotIn('setAdjustViewBounds', q, "no picture sets its card's height")
        self.assertIn('private static final String[] NAMES = {"Two menus", "One menu at the right", "Bottom screen"};', q, "the question's names fit a line of their card each")
        self.assertIn('box.post(() -> LayoutChoice.offer(a));', (APP / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8'), "Home's list asks")
        for name in ('two', 'right', 'bottom'):
            x = (RES / f'drawable/pil_layout_{name}.xml').read_text(encoding='utf-8')
            self.assertNotIn('layouts.py', x, f'pil_layout_{name}: names no generator the repository does not have')
            self.assertIn(f'"pil_layout_{name}"', q, f'the choice shows pil_layout_{name}')

    def test_the_top_screen_dims_a_little(self):
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('dimTop((Activity) p.getContext(), true);', b, 'in with the bottom menu')
        self.assertIn('dimTop((Activity) p.getContext(), false);', b, 'out with it')
        self.assertIn('topDim.setBackgroundColor(0x66000000);', b, "SidePanel's scrim")
        screen = (APP / 'main/java/dev/peanuts/ui/GameScreen.java').read_text(encoding='utf-8')
        self.assertIn('grip.setVisibility(!loading && !live ?', screen, 'the grip on the Now playing page too')

    def test_one_menu_at_the_right(self):
        p = (LAUNCHER / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertTrue(marked(p, 'if (InGamePanel.instead(this)) return;'), 'what opens the quick menu opens the one menu instead, marked')
        self.assertLess(p.index('if (open) return;'), p.index('InGamePanel.instead(this)'), 'before the panel builds anything')
        b = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('if (p != panelOf ||', b, 'only the quick menu is sent on, never the one menu itself')
        self.assertIn('root.addView(rightPanel, root.indexOfChild(panelOf) + 1,', b, "in the game's window, over the game as the quick menu")
        self.assertIn('if (cachedRightMode(a)) vis = View.GONE;', b, "the left edge is the game's")
        self.assertIn('rightRows = gameRows(a, () -> rightPanel);', b, 'Game first on its rail, its rows closing it')
        self.assertIn('{ openRight(a, onGame, pad); return; }', b, 'the bottom screen gone: the one at the right')
        self.assertIn('String[] names = {"Two menus", "One menu at the right", "Bottom screen"};', b, "Layout's choices have the question's names")
        rows = (INGAME / 'GameRows.java').read_text(encoding='utf-8')
        self.assertIn('c.getDrawable(dev.nxnative.android.R.drawable.ic_chevron)', rows, "Game's rows that open a page show the launcher's chevron")
        self.assertIn('if (i == dev.peanuts.PeanutsPrefs.LAYOUT_BOTTOM && !second) continue;', b, 'the bottom screen only with a second display')


class ReviewFixes(unittest.TestCase):
    """The review of #678-#680, fixed for 0.1.9"""

    def setUp(self):
        self.panel = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')

    def body(self, sig):
        i = self.panel.index(sig)
        return self.panel[i:self.panel.index('\n    }\n', i)]

    def test_back_is_left_to_the_cover_the_preparation_and_the_controller_applet(self):
        g = (LAUNCHER / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertTrue(marked(g, 'InGamePanel.backKey(this, e, prewarmCover != null)'), 'the cover is passed in')
        self.assertIn('return covered || dev.peanuts.build.ShaderCapture.active(a) || ctrlAppletOpen();', self.panel)
        self.assertIn('&& !backBelongsElsewhere(a, covered);', self.body('static boolean backKey('), 'a Back down not taken: onBackPressed has it')
        self.assertIn('startsWith("open")', self.body('private static boolean ctrlAppletOpen()'), "CtrlScreen's own test of its join screen")
        # onBackPressed still handles them before the menus
        ob = g[g.index('public void onBackPressed()'):]
        ob = ob[:ob.index('\n')]
        self.assertLess(ob.index('prewarmCover != null'), ob.index('InGamePanel.back(this)'))
        self.assertLess(ob.index('ShaderCapture.cancel(this)'), ob.index('InGamePanel.back(this)'))
        self.assertLess(ob.index('CtrlScreen.back()'), ob.index('InGamePanel.back(this)'))

    def test_one_backup_at_a_time(self):
        b = self.body('private static void backup(Activity a)')
        self.assertTrue(b.split('\n')[1].strip().startswith('if (backingUp) return;'), 'a second press while it runs is ignored')
        self.assertLess(b.index('backingUp = true;'), b.index('new Thread('))
        self.assertIn('backingUp = false;', b[b.index('UI.post('):], 'ended on the UI thread, whatever the result')
        self.assertNotIn('backingUp = false', self.body('private static void reset()'), 'a screen made again does not let a second one start beside it')

    def test_a_recreated_screen_starts_clean(self):
        a = self.body('static void attach(Activity a, FrameLayout root, SidePanel panel)')
        self.assertTrue(a.split('\n')[1].strip() == 'reset();', 'reset first')
        r = self.body('private static void reset()')
        self.assertIn('closeBottom(true);', r)
        for f in ('rightPanel', 'bottomPanel', 'topDim', 'rightRows', 'gameRows', 'bottomScreen', 'cover', 'version', 'menu', 'quickRow', 'menuRows'):
            self.assertRegex(r, rf'\b{f} = null;', f'{f} reset')
        self.assertIn('x == a && panelOf == panel', self.panel, "the old screen's end does not close the new one's bottom menu")

    def test_the_left_edge_reads_the_layout_only_when_it_may_have_changed(self):
        self.assertIn('registerDisplayListener(displays, UI)', self.panel)
        self.assertNotIn('rightMode(a)) vis', self.panel.replace('cachedRightMode(a)) vis', ''), 'the global layout listener uses the cached layout')
        self.assertIn('layoutChanged();', self.body('private static void layoutSetting('), 'a layout picked in the menu')

    def test_the_menu_width_follows_the_screen(self):
        self.assertIn('made.width(menuWidth(a, r - l))', self.panel)
        self.assertIn('public void width(int px)', (INGAME / 'GameMenu.java').read_text(encoding='utf-8'))

    def test_the_bottom_edge_passes_taps_on(self):
        m = (INGAME / 'BottomMenu.java').read_text(encoding='utf-8')
        self.assertIn('public boolean onInterceptTouchEvent(MotionEvent e) { return swipe(e); }', m, 'taken only once it is the swipe')
        self.assertNotIn('setOnTouchListener', m, 'no strip over the page that takes every touch')
        self.assertNotIn('BottomMenu.edge(', (APP / 'main/java/dev/peanuts/ui/GameScreen.java').read_text(encoding='utf-8'))

    def test_the_layout_question(self):
        q = (APP / 'main/java/dev/peanuts/ui/LayoutChoice.java').read_text(encoding='utf-8')
        offer = q[q.index('public static void offer'):q.index('private static boolean padAttached')]
        self.assertLess(offer.index('setOnShowListener'), offer.index('PeanutsPrefs.menuLayoutAsked(a, PeanutsPrefs.menuLayoutAsked(a) + 1)'), 'counted once it shows')
        self.assertIn('uptimeMillis() - shownAt[0] < INPUT_DELAY_MS) return;', offer, 'a button already on its way picks nothing')

    def test_bottom_screen_focus_frame_with_keys(self):
        # The Thor run: on the bottom screen Android refuses to leave touch mode ("Touch mode switch rejected": its window never has the focus), so the
        # panel drew no frame after the first opening and its first D-pad press only found a focus. Keys now drive it out of touch mode in SidePanel's eyes
        side = (LAUNCHER / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertTrue(marked(side, '@Override public boolean isInTouchMode() { return super.isInTouchMode() && !InGamePanel.keyDriven(this); }'))
        self.assertIn('static boolean keyDriven(SidePanel p) { return bottomKeys && p != null && p == bottomPanel; }', self.panel)
        self.assertIn('if (down && o == bottomPanel) bottomKeys = true;', self.body('static boolean menuKey('), 'a key to the bottom menu: keys drive it')
        ob = self.body('static void openBottom(')
        self.assertLess(ob.index('bottomKeys = pad;'), ob.index('show(bottomPanel, gameRows, onGame, pad);'), 'opened with the controller: the frame at once')
        self.assertIn('bottomKeys = false;', self.body('private static void bottomTouched()'))
        self.assertIn('new dev.peanuts.ingame.BottomScreen(a, d, bottomPanel, InGamePanel::bottomTouched)', self.panel)
        scr = (INGAME / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && touched != null) touched.run();', scr, 'a touch gives it back to touch')
        self.assertIn('bottomKeys = false;', self.body('private static void reset()'))
        # SidePanel's own touch-mode checks are the ones the override changes: the frame, the first move, the focus at opening
        for s in ('if (now == null || !inside(now) || isInTouchMode()) return;', 'if (f == null || isInTouchMode()) { ensureFocus(); return; }',
                  'post(() -> { if (open && !isInTouchMode()) focusFirst(); });'):
            self.assertIn(s, side)

    def test_bottom_menu_focus_listeners_follow_its_window(self):
        # The Thor run, again: the bottom screen's menu joins a new window at each opening, and a focus listener added while it had no window stayed with
        # the first window only (no frame, no rail switching, no ticks from the second opening). Every focus listener of the quick menus moves with it
        look = (INGAME / 'Look.java').read_text(encoding='utf-8')
        f = look[look.index('public static void focusListener('):]
        for s in ('addOnAttachStateChangeListener', 'o.removeOnGlobalFocusChangeListener(l);', 'o.addOnGlobalFocusChangeListener(l);',
                  'onViewDetachedFromWindow(android.view.View x) { x.getViewTreeObserver().removeOnGlobalFocusChangeListener(l); }'):
            self.assertIn(s, f)
        side = (LAUNCHER / 'SidePanel.java').read_text(encoding='utf-8')
        self.assertTrue(marked(side, 'dev.peanuts.ingame.Look.focusListener(this, this::focusMoved);'))
        self.assertNotIn('getViewTreeObserver().addOnGlobalFocusChangeListener(this::focusMoved)', side)
        self.assertIn('Look.focusListener(sheet, (old, now) -> {', (INGAME / 'Rail.java').read_text(encoding='utf-8'))
        self.assertIn('Look.focusListener(p, (old, now) -> {', self.panel)
        for f in (LAUNCHER / 'InGamePanel.java', INGAME / 'Rail.java', INGAME / 'GameRows.java', INGAME / 'GameMenu.java'):
            self.assertNotIn('addOnGlobalFocusChangeListener', f.read_text(encoding='utf-8'), f.name)

    def test_panel_polish_needs_no_r(self):
        self.assertNotIn('R.id', (APP / 'main/java/dev/peanuts/ui/PanelPolish.java').read_text(encoding='utf-8'), 'test_android_hud compiles it alone')
        self.assertNotIn('pn_inset', (RES / 'values/peanuts_ingame.xml').read_text(encoding='utf-8'))

    def test_help_and_wiki_describe_two_menus(self):
        h = (APP / 'main/java/dev/peanuts/ui/SettingsHelp.java').read_text(encoding='utf-8')
        self.assertNotIn("in-game panel's Mods tab", h)
        self.assertIn('pressed, the game menu; held, the quick menu', h)
        self.assertIn("The quick menu's Cheats and Mods tabs", h)
        side = (REPO / 'wiki/Side-Panel-In-Game.md').read_text(encoding='utf-8')
        for s in ('# The in-game menus', '## The game menu', '### Cheats', '### Mods', '## Layout'):
            self.assertIn(s, side)
        self.assertIn('tab **Cheats**', (REPO / 'wiki/Cheats.md').read_text(encoding='utf-8'))
        self.assertIn('## Quick menu button', (REPO / 'wiki/Settings-Controls.md').read_text(encoding='utf-8'))
        self.assertIn('an extra button on the back of the controller', (APP / 'main/java/dev/peanuts/ui/QuickButton.java').read_text(encoding='utf-8'))


class QuickMenuButton(unittest.TestCase):
    def test_set_up_by_pressing_it(self):
        q = (APP / 'main/java/dev/peanuts/ui/QuickButton.java').read_text(encoding='utf-8')
        self.assertIn('d.setOnKeyListener(', q, "the controller's next press is the button")
        self.assertIn('if (games(k)) warn(a, k, done); else take(a, k, done);', q, 'a button games use is taken only after the warning')
        for k in ('KEYCODE_BUTTON_A', 'KEYCODE_BUTTON_THUMBL', 'KEYCODE_BUTTON_THUMBR', 'KEYCODE_DPAD_UP', 'KEYCODE_BUTTON_L2'):
            self.assertIn(f'case KeyEvent.{k}:', q[q.index('static boolean games('):], f'{k} warns: games use it')
        self.assertIn('if (k == KeyEvent.KEYCODE_BUTTON_MODE) return', q, "Home stays the game menu's")
        self.assertNotIn('static void offer(', q, "the app's start asks nothing about it: its row in Settings › Controls sets it up")
        self.assertNotIn('QuickButton', (APP / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8'), "Home's list asks nothing about it")
        prefs = (APP / 'main/java/dev/peanuts/PeanutsPrefs.java').read_text(encoding='utf-8')
        self.assertIn('getInt("quick_key", 0)', prefs, 'none by default')
        self.assertIn('r("Quick menu button", "controller")', (APP / 'main/java/dev/peanuts/ui/SettingsSections.java').read_text(encoding='utf-8'))
        self.assertIn('"Quick menu button"', (APP / 'main/java/dev/peanuts/ui/SettingsHelp.java').read_text(encoding='utf-8'))

    def test_it_opens_the_quick_menu(self):
        g = (LAUNCHER / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertTrue(marked(g, 'if (InGamePanel.quickKey(this, e)) return true;'), 'GameActivity: the marked hook')
        self.assertLess(g.index('InGamePanel.quickKey('), g.index('InGamePanel.key(this, combo'), 'before the combination and the game')
        p = (LAUNCHER / 'InGamePanel.java').read_text(encoding='utf-8')
        self.assertIn('if (open && dev.peanuts.ui.QuickButton.moves(quickKey)) return false;', p, 'a button the menus move with keeps that job in them')
        self.assertIn('else if (oneMode(a)) openOne(a, false, true);', p, 'one menu: on the tab shown last')
        self.assertIn('quickRow.value(dev.peanuts.ui.QuickButton.name(qk), false);', p, "the game menu's Quick menu row names the button")


if __name__ == '__main__':
    unittest.main()
