# PEANUTS: the fork's stats page frame rate cap (dev.peanuts.ui.StatsView, GameActivity's GameControls.Host)
"""The frame rate cap picked on the second screen's stats page while a game runs (StatsView's pop-up, GameActivity's GameControls.Host, the same calls as adb's
game do=fps_cap): it is stored for the game before any live change is tried, so a pick the game takes only at its next start (to or from Game owned, a limited
game's 120) is kept for that start; the pop-up opens again after such a pick with its "Restart the game to apply" note; the game's log says what was picked
and whether the settings file holds it. Star Ocean The Second Story R and Hogwarts Legacy (opt-in present pacing) run on a JVM (skipped without a JDK)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/StatsCapJvmTest.java'


def body(text, head):
    """The braces block that starts at head"""
    i = text.index(head)
    j = text.index('{', i)
    depth = 0
    for k in range(j, len(text)):
        depth += {'{': 1, '}': -1}.get(text[k], 0)
        if depth == 0:
            return text[j:k + 1]
    raise AssertionError('unbalanced: ' + head)


class Wiring(unittest.TestCase):
    def setUp(self):
        self.act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.host = body(self.act, 'private dev.peanuts.ui.GameControls.Host peanutsControls()')

    def test_the_pick_is_stored_first(self):
        cap = body(self.host, '@Override public void cap(int v)')
        first = cap.strip('{} \n').split(';')[0].strip()
        self.assertEqual(first, 'Prefs.fpsCap(act, titleId(), v)', 'stored for the game before a live change, whether or not one is possible')
        self.assertIn('noteCapPick(v);', cap)
        self.assertNotIn('return', cap.split('noteCapPick(v);')[0], 'no way out before the store and the note')

    def test_console_mode_is_stored_first(self):
        mode = body(self.host, '@Override public boolean consoleMode(int m)')
        self.assertLess(mode.index('ScreenMode.set(act, titleId(), m);'), mode.index('Native.graphicsModeSet('), 'stored before the live change is tried')

    def test_the_note(self):
        note = body(self.act, 'private void noteCapPick(int v)')
        for k in ('it applies at the next start', 'applied now', 'FrameRateCaps.savedIn(', 'Prefs.gameKey("fps_cap", id)', 'new Thread('):
            self.assertIn(k, note)
        name = re.search(r'static SharedPreferences of\(Context c\) \{ return open\(c, "(\w+)"\); \}', (SRC / 'Prefs.java').read_text(encoding='utf-8')).group(1)
        self.assertIn('"shared_prefs/%s.xml"' % name, note, "the file Prefs.of writes")

    def test_stats_page_pick(self):
        s = (UI / 'StatsView.java').read_text(encoding='utf-8')
        pick = s[s.index('row.addView(choice(c, h.capLabel(v0)'):]
        pick = pick[:pick.index('}), weighted(c, i % 3));')]
        self.assertLess(pick.index('h.cap(v0);'), pick.index('closeControl();'))
        self.assertRegex(pick, r'if \(h\.capRunning\(\) != h\.cap\(\)\) openControl\(from, false\);', 'a pick that waits for a restart shows the note again')

    def test_adb_takes_the_same_call(self):
        link = (REPO / 'android/app/src/main/java/dev/peanuts/ctl/GameLink.java').read_text(encoding='utf-8')
        self.assertIn('@Override public void cap(int v) { h.cap(v); }', link)

    def test_profiles(self):
        for tid, rows in (('010065301A2E0000', ('fps_limit_present\t1', 'fps_modes\t20,30,60')), ('0100F7E00C70E000', ('fps_limit_present\t1', 'fps_limit_max\t60'))):
            t = (REPO / 'dm/games' / tid / 'game.tsv').read_text(encoding='utf-8')
            for r in rows:
                with self.subTest(game=tid, row=r):
                    self.assertIn('\n' + r + '\n', t)
            self.assertIsNone(re.search(r'(?m)^fps_limit\t', t), 'no fps_limit: opt-in present pacing')


def tsv_rows(path):
    out = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.startswith('#') or '\t' not in line: continue
        k, v = line.split('\t', 1)
        out.setdefault(k, v)
    return out


# The games that started Game owned until a pick before 0.1.8's release (FrameRateCaps.ownedBefore: opt-in present pacing, fps_limit_present with no fps_limit
# and not paced, or fps_owned_default); since then they start at FrameRateCaps.defaultLimit (60 fps), the owner's call of 2026-11-24
OWNED_BEFORE = {
    '01001B300B9BE000', '01002C9022770000', '01004D300C5AE000', '01006000040C2000', '010065301A2E0000', '010069401ADB8000', '01006AE02236C000',
    '01006F600FFC8000', '01006F8002326000', '01007A2027548000', '010085500130A000', '010097F018538000', '0100DAE01E4C8000', '0100F7E00C70E000',
}


class DefaultLimit(unittest.TestCase):
    def test_the_games(self):
        got = set()
        for f in sorted((REPO / 'dm/games').glob('*/game.tsv')):
            r = tsv_rows(f)
            if r.get('fps_limit_present') == '1' and 'fps_limit' not in r and 'fps_decouple' not in r or r.get('fps_owned_default') == '1':
                got.add(f.parent.name)
        self.assertEqual(got, OWNED_BEFORE, 'a game joins or leaves the 60 fps default: check its caps and its start at 60, then this list')

    def test_the_default_is_used(self):
        prefs = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        pace = body(prefs, 'static int paceCap(Context c, String titleId)')
        self.assertIn('boolean owned = FrameRateCaps.ownedBefore(p);', pace)
        self.assertIn('int defaultCap = owned ? FrameRateCaps.defaultLimit(p) :', pace)
        self.assertNotIn('GAME_OWNED : of(c)', pace, 'Game owned is no default')
        act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('if (Prefs.chosenFpsCap(this, id) || FrameRateCaps.ownedBefore(prof)) { int pace = Prefs.paceCap(this, id); env.add("DM_FPS_LIMIT=" + limitRate(pace)); }', act,
                      'the default limit is passed at the start: an opt-in profile has no limit of its own')
        self.assertIn('List<String> to = Prefs.chosenFpsCap(this, id) ? unknownNotes : quietNotes;', act, 'a default 60 without its variant: the log line, no toast')
        self.assertIn('for (String n : quietNotes) Native.note(n);', act)
        gs = (SRC / 'GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('labels[0] = "Default (" + Prefs.paceLabel(FrameRateCaps.defaultLimit(p)) + ")"', gs)
        self.assertNotIn('"Default (Game owned)"', gs)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class StatsCapJvm(unittest.TestCase):
    def test_jvm(self):
        stub = ('package dev.nxnative.android;\npublic final class BuildConfig {\n    public static final String NX_PROFILES = "";\n'
                '    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";\n}\n')
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'stub').mkdir()
            (d / 'stub/BuildConfig.java').write_text(stub)
            srcs = [SRC / f'{n}.java' for n in ('Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Nup', 'Nsp', 'Zstd', 'Profile', 'ShaderLibs', 'FrameRateCaps')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.StatsCapJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
