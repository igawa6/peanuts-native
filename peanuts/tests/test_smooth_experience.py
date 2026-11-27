"""Smooth Experience (Settings › App › Extras; dev.peanuts.smooth.Smooth, SmoothItems, dev.peanuts.ui.StartCover) and Developer view
(Settings › Developer; dev.peanuts.smooth.DevView, PlayerWords): the items and the in-game menu's words on the JVM (android/app/src/jvmtest's SmoothJvmTest;
skipped without a JDK), and against the sources: both settings' names in their one place and wherever Settings spells them, the defaults (Smooth
Experience off, each of its items on; Developer view on for an app installed before, off for a new install), every hook in an upstream file with its
PEANUTS marker, the hidden notices and toasts still in the log, the start screen gone at the first frame, and the new text keeps UI-RULES.md."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
SMOOTH = APP / 'main/java/dev/peanuts/smooth'
UI = APP / 'main/java/dev/peanuts/ui'
UP = APP / 'main/java/dev/nxnative/android'


def read(p):
    return p.read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='smooth_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(SMOOTH / 'SmoothItems.java'), str(SMOOTH / 'PlayerWords.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/smooth/SmoothJvmTest.java')], check=True)
            r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', t, 'dev.peanuts.smooth.SmoothJvmTest'], capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Names(unittest.TestCase):
    def name(self, cls):
        return re.search(r'public static final String NAME = "([^"]+)";', read(SMOOTH / f'{cls}.java')).group(1)

    def test_one_name_wherever_settings_spells_it(self):
        """Renaming a setting is its NAME and these three places (Settings finds its rows by their title)"""
        for cls, icon in (('SmoothItems', 'peanut'), ('DevView', 'terminal')):
            n = self.name(cls)
            self.assertIn(f'r("{n}", "{icon}")', read(UI / 'SettingsSections.java'))
            self.assertIn(f'put(m, "{n}", ', read(UI / 'SettingsHelp.java'))
            self.assertIn(f'new PUi.Setting(a, "", "{n}", ', read(UI / 'LibrarySettings.java'))
        self.assertEqual(self.name('SmoothItems'), 'Smooth Experience')
        self.assertNotIn('Just the game', ''.join(read(f) for f in SMOOTH.glob('*.java')))

    def test_the_parts_under_the_switch(self):
        """Each part a row of its own right under Smooth Experience (App › Extras), its title as SmoothItems has it, shown while the switch is on"""
        table, help_, lib = read(UI / 'SettingsSections.java'), read(UI / 'SettingsHelp.java'), read(UI / 'LibrarySettings.java')
        titles = re.findall(r'new Item\((?:START_LOG|WARM|SKIP), "([^"]+)"', read(SMOOTH / 'SmoothItems.java'))
        self.assertEqual(titles, ['Start screen', 'Preparing graphics notice', 'Graphics driver notice'])
        extras = re.search(r'new Group\("Extras", (.*?)\)\)\),', table, re.S).group(1)
        self.assertEqual(re.findall(r'r\("([^"]+)"', extras), ['Smooth Experience'] + titles)
        for t in titles:
            self.assertIn(f'put(m, "{t}", "Part of Smooth Experience: ', help_)
            self.assertIn(f'smoothPart(a, smoothBox, new PUi.Setting(a, "", "{t}", null)', lib)
        # the parts sit in one block right after the switch's row in its card (SettingsPage carries a non-row view with the row before it), set in
        # under the switch's title, and the block shows while the switch is on
        self.assertRegex(lib, r'card\.addView\(smoothRow\.view\);\s*smoothBox = new LinearLayout\(a\);')
        self.assertIn('card.addView(smoothBox, bp);', lib)
        self.assertIn('if (smoothBox != null) smoothBox.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);', lib)
        page = read(UI / 'SettingsPage.java')
        self.assertIn('else if (last != null) last.after.add(k);', page)
        self.assertIn('for (View v : it.after) card.addView(v);', page)
        self.assertFalse((SMOOTH / 'SmoothPage.java').exists())   # no sheet: the page grows under the switch

    def test_where_the_rows_are(self):
        table = read(UI / 'SettingsSections.java')
        self.assertIn('new Group("Extras", r("Smooth Experience", "peanut")', table)
        self.assertRegex(table, r'new Group\("Testing", [^\n]*r\("Developer view", "terminal"\)\)')


class Defaults(unittest.TestCase):
    def test_smooth_off_its_items_on(self):
        prefs = read(APP / 'main/java/dev/peanuts/PeanutsPrefs.java')
        self.assertIn('getBoolean("smooth", false)', prefs)
        self.assertIn('getBoolean("smooth_" + key, true)', prefs)

    def test_developer_view_as_it_was_for_an_installed_app(self):
        """On for an app Android updated at least once, off for a new install, decided once and kept (a later update must not turn it on)"""
        dv = read(SMOOTH / 'DevView.java')
        self.assertIn('return i.lastUpdateTime > i.firstInstallTime;', dv)
        self.assertRegex(dv, r'if \(p\.contains\(KEY\)\) return p\.getBoolean\(KEY, true\);\s*boolean was = installedBefore\(c\);\s*p\.edit\(\)\.putBoolean\(KEY, was\)\.commit\(\);')
        self.assertIn('FILE = "peanuts_devview"', dv)   # its own file: no other process rewrites "peanuts"


class Hooks(unittest.TestCase):
    """Each hook in an upstream file is one marked line (upstream-update.sh --check holds the hunks to it; this holds the calls)"""
    SITES = {
        UP / 'GameActivity.java': ['dev.peanuts.ui.StartCover.add(this, rootView, rootView.indexOfChild(pausedView), overlay, this::end);',
                                   'dev.peanuts.ui.StartCover.tail(this, overlay, drawing, failed, TouchControls.showLog(this));',
                                   'ui.postDelayed(this::tail, dev.peanuts.ui.StartCover.next(1000));',
                                   'dev.peanuts.ui.StartCover.takesPad() && gamepad(e.getSource())',
                                   'dev.peanuts.smooth.Smooth.hides(this, dev.peanuts.smooth.SmoothItems.SKIP)',
                                   '{ String t = dev.peanuts.smooth.DevView.startToast(this, unknownNotes); if (t != null) android.widget.Toast.makeText(',
                                   'if (!dev.peanuts.smooth.DevView.plain(this)) android.widget.Toast.makeText(this, "Environment lines "'],
        UP / 'MainActivity.java': ['if (!dev.peanuts.smooth.DevView.plain(this)) Toast.makeText(this, "A command from another app'],
        UP / 'SidePanel.java': ['fpsNow.setText(dev.peanuts.smooth.DevView.panel(act, s));',
                                'lsfgNow.setText(dev.peanuts.smooth.DevView.frameGen(act, st, '],
        UP / 'Lsfg.java': ['t.setText(dev.peanuts.smooth.DevView.panel(c, "Not in this build (built with -PnxLsfg=false)."));',
                           'mode.setText(dev.peanuts.smooth.DevView.panel(c, "Mode: performance (the only shader set Eden\'s LSFG uses)"));'],
    }

    def test_every_hook_is_marked(self):
        for f, calls in self.SITES.items():
            lines = read(f).splitlines()
            for call in calls:
                hits = [l for l in lines if call in l]
                self.assertEqual(len(hits), 1, f'{f.name}: {call}')
                self.assertIn('PEANUTS', hits[0], f'{f.name}: {call}')
            # and nothing else of these classes in the file than the hooks above (each call site listed here)
            n = sum(l.count('dev.peanuts.smooth.') + l.count('dev.peanuts.ui.StartCover.') for l in lines)
            self.assertLessEqual(n, 2 * len(calls) + 2, f'{f.name}: a hook this test does not list')

    def test_the_overlay_is_its_own(self):
        """The overlay has its own switches (Settings › Graphics › Overlay): Smooth Experience leaves it alone"""
        self.assertIn('private boolean overlayOn() { return overlayEnv != null ? overlayEnv : Prefs.overlay(this); }', read(UP / 'GameActivity.java'))
        for f in ('MainActivity.java', 'SidePanel.java'):
            self.assertNotIn('overlayNote', read(UP / f))
        self.assertNotIn('OVERLAY', read(SMOOTH / 'SmoothItems.java'))

    def test_the_two_panel_notes(self):
        side = read(UP / 'SidePanel.java')
        self.assertEqual(side.count('dev.peanuts.smooth.DevView.panel(act, live ? "Fit keeps'), 1)
        self.assertEqual(side.count('note(dev.peanuts.smooth.DevView.panel(act, act instanceof GameActivity'), 1)

    def test_hidden_notices_still_go_to_the_log(self):
        ga = read(UP / 'GameActivity.java')
        self.assertIn('Native.note("preparing graphics: " + n + " pipelines waiting; the notice is off (Smooth Experience)");', ga)
        self.assertIn('Native.note("skipped work: " + pct + " % of draws; the notice is off (Smooth Experience)"); return; }', ga)
        # the toasts Developer view off leaves out are written to the log first
        self.assertRegex(ga, r'for \(String n : unknownNotes\) Native\.note\(n\);')
        self.assertRegex(ga, r'Native\.note\("\[RED\] env: " \+ msg\);\s*\n\s*if \(!dev\.peanuts\.smooth\.DevView\.plain\(this\)\)')

    def test_only_testers_lines_leave_the_toast(self):
        """Developer view off keeps every toast a player needs: only a switch nothing reads (and DM_TEST_CRASH) goes to the log alone, matched by
        the texts upstream writes; the shader library's update hint always shows"""
        self.assertIn('" is not a switch of this build: nothing reads it"', read(SMOOTH / 'PlayerWords.java'))
        self.assertIn(') is not a switch of this build: nothing reads it"', read(UP / 'Readiness.java'))
        self.assertIn('"[nxlaunch] DM_TEST_CRASH not taken: the test app only"', read(UP / 'GameActivity.java'))
        self.assertIn('            Toast.makeText(this, ShaderLibs.LEGACY_WARNING, Toast.LENGTH_LONG).show();\n', read(UP / 'MainActivity.java'))

    def test_listed_as_hook_sites(self):
        peanuts = read(REPO / 'PEANUTS.md')
        self.assertIn('Smooth Experience', peanuts)
        self.assertIn('Developer view', peanuts)


class StartScreen(unittest.TestCase):
    def test_gone_at_the_first_frame(self):
        """A view over the SurfaceView costs a GPU-bound game frames (Overlay, issue #34): GONE and out of the window"""
        s = read(UI / 'StartCover.java')
        body = s[s.index('} else if (drawing && shown != null && !leaving) {'):]
        body = body[:body.index('        }\n    }')]
        self.assertIn('s.setVisibility(View.GONE);', body)
        self.assertIn('root.removeView(s)', body)
        self.assertIn('GameScreen.refresh();', body)   # the bottom screen leaves its start page with it

    def test_breath_on_the_bottom_screens_clock(self):
        s, start = read(UI / 'StartCover.java'), read(UI / 'StartView.java')
        self.assertIn('BREATH_MS = 1300', s)
        self.assertIn('pingPong(now, 1300)', start)
        self.assertIn('new PathInterpolator(0.45f, 0f, 0.55f, 1f)', s)
        self.assertIn('new PathInterpolator(0.45f, 0f, 0.55f, 1f)', start)
        self.assertIn('SystemClock.uptimeMillis()', s)
        self.assertIn('boolean run = on && Motion.on() && isAttachedToWindow();', s)   # still under reduced motion
        self.assertNotRegex(s, r'setRotation|\.rotate\(')                            # a breath, never a wobble

    def test_failure_card(self):
        s = read(UI / 'StartCover.java')
        for t in ('"The game stopped while starting"', '"Share log"', '"Close"'):
            self.assertIn(t, s)
        ga = read(UP / 'GameActivity.java')
        self.assertIn('if (text.contains("CRASH sig=") || text.contains("FATAL")) failed = true;', ga)   # what tail() calls a failure

    def test_bottom_screen_in_step(self):
        g = read(UI / 'GameScreen.java')
        self.assertIn('boolean starting = mode != LOADING && StartCover.waiting();', g)
        self.assertIn('start.status(starting ? "Starting the game" : ', g)


class Copy(unittest.TestCase):
    """UI-RULES.md: no em or en dash, no middle dot or bullet between values, no emoji, no exclamation mark in the new text"""
    def test_new_text(self):
        files = list(SMOOTH.glob('*.java')) + [UI / 'StartCover.java']
        bad = re.compile('[—–·•!]|[\U0001F300-\U0001FAFF☀-➿]')
        for f in files:
            for n, line in enumerate(read(f).splitlines(), 1):
                code = line.split('//', 1)[0]
                if code.lstrip().startswith(('*', '/*')):
                    continue
                for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', code):
                    if '(?' in lit or '\\\\' in lit:   # PlayerWords' patterns, not text
                        continue
                    self.assertIsNone(bad.search(lit), f'{f.name}:{n}: {lit}')


if __name__ == '__main__':
    unittest.main()
