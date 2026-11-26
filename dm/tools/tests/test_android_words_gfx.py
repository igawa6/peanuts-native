"""The launcher's graphics settings for a game whose profile has window_words rows (WordsGfx.java, WordsRows.java and Profile's words: kind) on the JVM
(android/app/src/jvmtest's WordsGfxJvmTest), build.gradle's resolutionOf (checked against a Python reading of the same game.tsv rows), and that the settings, the panel
and the start are wired to it. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/games'))

SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/WordsGfxJvmTest.java'
GRADLE = REPO / 'android/app/build.gradle'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";
}
'''


def text(name):
    return (SRC / name).read_text(encoding='utf-8')


def rows(lines, key):
    return [line.split('\t') for line in lines if line.startswith(key + '\t')]


def resolution_of(lines, words=True):   # build.gradle resolutionOf, in Python (only the kinds that need no render_cfg row's fields)
    if rows(lines, 'render_cfg'): return 'render'
    if words and rows(lines, 'window_words'): return 'words:1920x1080'
    if words and rows(lines, 'view_scale_fixed'): return 'words:' + rows(lines, 'view_scale_fixed')[0][2].strip()   # fixedWordsOf, after the window_words kind
    if rows(lines, 'window_fn') or rows(lines, 'res_levels'): return 'window'
    gs = rows(lines, 'graphics_scale')
    return '' if not gs else 'scale:' + ','.join(f'{c[1].strip()}={c[2].strip()}/{c[3].strip()}' for c in gs)


def fps_default_ok(lines):   # the rule build.gradle's fpsDefaults applies to one profile's rows (True when the build accepts them), in Python; game.py header refuses the same rows
    rs = rows(lines, 'fps_default')
    if len(rs) > 1: return False
    if not rs: return True
    v = rs[0][1].strip() if len(rs[0]) == 2 else ''
    if not re.fullmatch(r'[1-9][0-9]', v): return False
    limit = (rows(lines, 'fps_limit') or [['', '0']])[0][1].strip()
    if limit == '0': return False
    md = rows(lines, 'fps_modes')
    offered = md[0][1].strip().split(',') if md else [x for x in ('20', '30', '40', '45', '60') if int(x) >= int((rows(lines, 'fps_limit_min') or [['', '20']])[0][1])]
    return int(v) <= int(limit) and v in offered


def fps_default_field(titles):   # NX_PEANUTS_FPS_DEFAULT as build.gradle's fpsDefaults builds it for the games a build names: '<title ID>:<n>' per title with an fps_default row, space-separated, in the order of the titles
    return ' '.join(f'{tid}:{rows(lines, "fps_default")[0][1].strip()}' for tid, lines in titles if rows(lines, 'fps_default'))


def tree_titles():   # every profile of this tree, whatever rows it has (the tree-independent reading; a title added later, such as Three Hopes', is read like any other)
    return [(t.parent.name, t.read_text(encoding='utf-8').splitlines()) for t in sorted((REPO / 'dm/games').glob('*/game.tsv'))]


class Wiring(unittest.TestCase):
    def test_gradle_kind(self):
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("def wordsOf = { String game -> tsvRows(game, 'window_words').isEmpty() ? '' : 'words:1920x1080' }", g)
        self.assertIn("!wordsOf(game).isEmpty() ? wordsOf(game)", g, 'resolutionOf takes the kind, after the render_cfg kind')
        self.assertLess(g.index('!renderOf(game).isEmpty() ? renderOf(game) : !wordsOf'), g.index("tsvRows(game, 'window_fn')"), 'before the older kinds')

    def test_gradle_fixture(self):
        """A profile with a window_words row is words:; one without is what it was (the same rows through the old and the new rule)"""
        words = ['name\tT', 'window_words\t5b76a43b\t0x28639c:15a080d2:w\t0x2863a8:155ac0f2:h', 'op_mode\thandheld', 'fps_limit\t30']
        self.assertEqual(resolution_of(words), 'words:1920x1080')
        for lines in (['name\tA'], ['window_fn\tx'], ['res_levels\tx'], ['graphics_scale\t1920x1080\t2\tconsole', 'graphics_scale\t1280x720\t1\thandheld'], ['render_cfg\tx\ty']):
            self.assertEqual(resolution_of(lines), resolution_of(lines, words=False), lines)
        self.assertEqual(resolution_of(['window_fn\tx']), 'window')

    def test_every_profile_unchanged(self):
        """No profile of the tree changes kind by the new rule unless it has window_words rows or (build.gradle resolutionOf / fixedWordsOf) a view_scale_fixed row: the two ways into words:"""
        for t in sorted((REPO / 'dm/games').glob('*/game.tsv')):
            lines = t.read_text(encoding='utf-8').splitlines()
            if rows(lines, 'render_cfg'):
                self.assertEqual(resolution_of(lines), 'render', t.parent.name)   # a render_cfg row wins over both
            elif rows(lines, 'window_words'):
                self.assertEqual(resolution_of(lines), 'words:1920x1080', t.parent.name)
            elif rows(lines, 'view_scale_fixed'):   # the second route, 'words:<W>x<H>' from tab field 3 of the first row, as fixedWindowOf
                self.assertEqual(resolution_of(lines), 'words:' + rows(lines, 'view_scale_fixed')[0][2].strip(), t.parent.name)
            else:
                self.assertEqual(resolution_of(lines), resolution_of(lines, words=False), t.parent.name)

    def test_view_scale_fixed_is_a_words_route(self):
        """on fixture rows (no in-tree profile has a view_scale_fixed row but Three Hopes', outside this tree) a view_scale_fixed row gives 'words:<W>x<H>'; window_words wins over it; no row, no words:; the
        same rule as build.gradle's resolutionOf, which real gradle ran on scratch fixtures (cases fixed_*)"""
        fx = lambda size: f'view_scale_fixed\t5b76a43b231e0640521ac66203f7c178\t{size}'
        self.assertEqual(resolution_of(['name\tT', fx('1920x1080')]), 'words:1920x1080')
        self.assertEqual(resolution_of(['name\tT', fx('1280x720')]), 'words:1280x720')
        self.assertEqual(resolution_of(['name\tT', fx('1280x720'), 'view_scale_fixed\t0123456789abcdef0123456789abcdef\t1280x720']), 'words:1280x720', 'rows of two builds agree')
        self.assertEqual(resolution_of(['name\tT', 'window_words\t5b76a43b\t0x28639c:15a080d2:w', fx('1280x720')]), 'words:1920x1080', 'window_words comes first')
        self.assertEqual(resolution_of(['name\tT', 'render_cfg\tx\ty', fx('1280x720')]), 'render', 'render_cfg comes before both')
        self.assertEqual(resolution_of(['name\tT', 'window_fn\tx', fx('1280x720')]), 'words:1280x720', 'the older kinds come after')
        self.assertEqual(resolution_of(['name\tT', 'window_fn\tx']), 'window')
        self.assertEqual(resolution_of(['name\tT']), '')
        # the rule in the gradle text: the fixed kind sits between the window_words kind and the older kinds
        g = GRADLE.read_text(encoding='utf-8')
        res = g.split('def resolutionOf = {')[1].split('\n')[0]
        self.assertLess(res.index('wordsOf(game)'), res.index('fixedWordsOf(game)'))
        self.assertLess(res.index('fixedWordsOf(game)'), res.index("'window_fn'"))

    def test_real_profile_when_it_has_the_rows(self):
        """Once the title has window_words rows (P adds them): the old 3D option rows are gone (Internal resolution replaces them) and op_mode stays handheld (the default Graphics quality)"""
        t = REPO / 'dm/games/0100317013770000/game.tsv'
        lines = t.read_text(encoding='utf-8').splitlines()
        if not rows(lines, 'window_words'): self.skipTest('the profile has no window_words row yet')
        self.assertEqual(resolution_of(lines), 'words:1920x1080')
        self.assertEqual([c for c in rows(lines, 'option') + rows(lines, 'option_patch') if len(c) > 1 and c[1] == 'DM_MRSOH_3D_FULL'], [])
        self.assertIn(['op_mode', 'handheld'], [[c[0], c[1].strip()] for c in rows(lines, 'op_mode')], 'Handheld stays the default: ScreenMode.profile reads op_mode')
        self.assertTrue(rows(lines, 'fps_limit'), 'the Frame rate slider is for a limited game')

    def test_start_and_rows(self):
        act = text('GameActivity.java')
        self.assertIn('WordsRows.env(this, id, env);', act)
        self.assertLess(act.index('ResScale.env(this, id, env);'), act.index('WordsRows.env(this, id, env);'))
        gs = text('GameSettings.java')
        self.assertIn('if (prof != null && prof.words()) WordsRows.settings(a, g, picture::add, again);', gs)
        self.assertLess(gs.index('WordsRows.settings('), gs.index('ScreenMode.row(a, g, again)'), 'Graphics quality follows the three rows')
        self.assertIn('if (Prefs.limited(g.titleId) && WordsRows.on(g.titleId)) { WordsRows.capDialog(a, g, done); return; }', gs)
        side = text('SidePanel.java')
        self.assertIn('if (p != null && p.words()) { wordsPicture(); return; }', side)
        self.assertIn('if (ScreenMode.fixed(game()) == null && !WordsRows.on(game())) {', side, 'no live Console mode row for a words: game')
        self.assertIn('wordsFps();', side)
        self.assertIn('caps.setOnCheckedChangeListener((g, id) -> capPicked(choices[id - 3000]));', side, 'the radio list and the slider share one body')
        wp = side.split('private void wordsPicture()')[1].split('// PEANUTS end')[0]
        self.assertNotIn('graphicsModeSet', wp, 'Graphics quality is restart only: no live call')
        self.assertIn('WordsRows.pushUpscaler', wp, 'the Upscaler is live')
        rs = text('ResScale.java')
        self.assertIn('WordsGfx.hidesResScale(p)', rs)
        sm = text('ScreenMode.java')
        self.assertIn('!WordsGfx.unlocksMode(p)', sm)
        self.assertIn('if (WordsGfx.unlocksMode(Profile.of(id))) return wordsRow(a, g, done);', sm)
        self.assertNotIn('graphicsModeSet', sm)

    def test_no_helper_text(self):
        """the words: rows show their title and value only, in Game settings and in the in-game panel (the user wants no added helper line)"""
        self.assertNotIn('.note(', text('WordsRows.java'), 'Game settings rows have no summary line')
        wp = text('SidePanel.java').split('private void wordsPicture()')[1].split('// PEANUTS end')[0]
        # the user asked for a helper line in the in-game Display rows: "Applies on next start" under Internal resolution (it cannot change while the game runs). After a device test: the same
        # line, in-game only, under Graphics quality (read at the start: DM_OPMODE). Nothing else is loosened: those two notes, both WordsGfx.NEXT_START, and no other
        self.assertEqual(wp.count('note('), 2, 'the in-game Display rows add two notes only')
        self.assertEqual(wp.count('note(WordsGfx.NEXT_START);'), 2, 'both are the one NEXT_START line, no other text')
        first, second = wp.index('note(WordsGfx.NEXT_START);'), wp.rindex('note(WordsGfx.NEXT_START);')
        self.assertLess(wp.index('"Internal resolution"'), first, 'the first is after the Internal resolution heading')
        self.assertLess(first, wp.index('head("Internal upscaler")'), 'and before the next row: it belongs to Internal resolution')
        self.assertLess(wp.index('head("Graphics quality");'), second, 'the second is after the Graphics quality heading')
        self.assertLess(wp.index('box.addView(rg);'), second, 'directly under the Graphics quality control')
        self.assertEqual(wp[wp.index('box.addView(rg);'):second].count(chr(10)), 1, 'with nothing in between')
        self.assertIn('static final String NEXT_START = "Applies on next start";', text('WordsGfx.java'), 'exactly this text')
        self.assertNotIn('NEXT_START', text('WordsRows.java'), 'Game settings gets no helper: every row there applies at the next start')
        self.assertNotIn('restart', wp, 'no restart line in the in-game Display rows')
        sm = text('ScreenMode.java').split('private static Ui.Setting wordsRow')[1].split('// PEANUTS end')[0]
        self.assertNotIn('.note(', sm, 'Graphics quality row has no summary line')
        self.assertIn('GFX_NAMES = {"Docked", "Handheld"}', text('WordsGfx.java'), 'plain choice names')

    def test_in_game_graphics_quality_is_kept(self):
        """(A device test: in-game Graphics quality did not persist console_mode@<id>, and nothing said it applies at the next start). The in-game control of a words: game writes the per-game pref that
        Game settings' row, ScreenMode.mode and ScreenMode.env read (so the next start uses it), reads it back, and claims no live change; non-words games keep their Console mode block as it was"""
        sm, sp = text('ScreenMode.java'), text('SidePanel.java')
        wp = sp.split('private void wordsPicture()')[1].split('// PEANUTS end')[0]
        panel = wp.split('head("Graphics quality");')[1]
        # the control: the slot's mode through the pure mapping, then keep
        self.assertIn('ScreenMode.keep(act, id, WordsGfx.gfxModeOfSlot(rid - ID_WORDS - 40));', panel)
        self.assertIn('rg.check(ID_WORDS + 40 + WordsGfx.gfxSlotOfMode(ScreenMode.mode(act, id)));', panel, 'the checked button is the stored mode')
        self.assertIn('b.setText(WordsGfx.GFX_NAMES[WordsGfx.gfxModeOfSlot(k)])', panel)
        self.assertNotIn('graphicsModeSet', wp, 'no live change of the console mode')
        self.assertNotIn('Native.', wp)
        # keep -> set -> the same key the launcher row, mode() and env() use
        keep = sm.split('static void keep(')[1].split('    }' + chr(10))[0]
        self.assertIn('set(c, id, mode)', keep)
        self.assertIn('int back = mode(c, id);', keep, 'read back (kept in a variable for the log line)')
        self.assertIn('back != mode', keep)
        st = sm.split('static boolean set(')[1].split('    }' + chr(10))[0]
        self.assertIn('putInt(Prefs.gameKey(KEY, id), v).commit()', st, 'console_mode@<id>, committed before the call returns (the game process may end next)')
        self.assertIn('static final String KEY = "console_mode";', sm)
        self.assertIn('Prefs.of(c).getInt(Prefs.gameKey(KEY, id), g < 0 ? own(id) : g)', sm.split('static int mode(')[1].split('    }' + chr(10))[0], 'mode() reads that key')
        self.assertIn('int m = mode(c, id);', sm.split('static void env(')[1].split('    }' + chr(10))[0], 'the start (DM_OPMODE) reads mode()')
        wr = sm.split('private static Ui.Setting wordsRow(')[1].split('// PEANUTS end')[0]
        self.assertIn('set(a, id, w == 0 ? null : w == 1 ? 1 : 0);', wr, 'the launcher row writes the same key with the same meaning (1 handheld, 0 docked)')
        self.assertIn('static String gameKey(String key, String titleId) { return key + "@" + titleId; }', text('Prefs.java'))
        self.assertIn('ScreenMode.env(', text('GameActivity.java'), 'GameActivity sends DM_OPMODE from mode() at each start')
        # the launcher row has no helper (the user's rule): the next-start note is in-game only
        self.assertNotIn('NEXT_START', sm)
        # non-words games: the Console mode block and its live change are as they were
        live = sp.split('if (ScreenMode.fixed(game()) == null && !WordsRows.on(game())) {')[1].split('// PEANUTS end')[0]
        for line in ('ScreenMode.set(act, game(), m);', 'Native.graphicsModeSet(-1, 1 - m)', 'cm.check(ID_CONSOLE + ScreenMode.mode(act, game()));', 'Changes now, as when a Switch leaves its dock, and is kept for this game.'):
            self.assertIn(line, live)
        self.assertNotIn('keep(', live)

    def test_in_game_graphics_quality_logs_every_choice(self):
        """(Cause of a device report not pinned: no evidence the handler ran): ScreenMode.keep logs the choice before the write, then the read-back on success,
        and keeps the failure line; the in-game words control still calls keep; the non-words Console mode block does not"""
        sm, sp, wg = text('ScreenMode.java'), text('SidePanel.java'), text('WordsGfx.java')
        keep = sm.split('static void keep(')[1].split('    }' + chr(10))[0]
        picked, written, back = keep.index('Native.note(WordsGfx.gfxPickedLine(id, mode));'), keep.index('set(c, id, mode)'), keep.index('int back = mode(c, id);')
        self.assertLess(picked, written, 'the choice is logged before the write')
        self.assertLess(written, back, 'read back after the write')
        self.assertIn('if (!written || back != mode) Native.note("Graphics quality: the choice was not kept for the next start");', keep, "the failure line kept")
        self.assertIn('else Native.note(WordsGfx.gfxKeptLine(id, mode, back));', keep, 'the success line carries the value read back')
        self.assertIn('"Graphics quality: kept " + GFX_NAMES[mode == 1 ? 1 : 0] + " (console_mode@" + id + "=" + readBack + ") for the next start"', wg)
        self.assertIn('"Graphics quality: " + GFX_NAMES[mode == 1 ? 1 : 0] + " picked in game, writing console_mode@" + id + "=" + mode + " for the next start"', wg)
        panel = sp.split('private void wordsPicture()')[1].split('// PEANUTS end')[0].split('head("Graphics quality");')[1]
        self.assertIn('ScreenMode.keep(act, id, WordsGfx.gfxModeOfSlot(rid - ID_WORDS - 40));', panel)
        live = sp.split('if (ScreenMode.fixed(game()) == null && !WordsRows.on(game())) {')[1].split('// PEANUTS end')[0]
        self.assertNotIn('keep(', live, 'non-words titles: unchanged')
        self.assertNotIn('gfxPickedLine', live)

    def test_no_end_captions_no_game_owned(self):
        """The Frame rate, Internal resolution and Upscaler sharpness sliders have no end captions, a words: game's Frame rate has no Game owned and no helper line"""
        wr, sp, st = text('WordsRows.java'), text('SidePanel.java'), text('StepSlider.java')
        for word in ('Lighter', 'Smoother', 'Faster', 'Sharper', 'Softer'):
            self.assertNotIn(f'"{word}"', wr, f'WordsRows: no {word} caption')
        wp = sp.split('private void wordsPicture()')[1].split('// PEANUTS end')[0]
        wf = sp.split('private void wordsFps()')[1].split('private void wordsPicture')[0]
        for word in ('Lighter', 'Smoother', 'Faster', 'Sharper', 'Softer'):
            self.assertNotIn(f'"{word}"', wp, f'in-game Display: no {word} caption')
            self.assertNotIn(f'"{word}"', wf, f'in-game Frame rate: no {word} caption')
        self.assertEqual(wp.count('null, null'), 2, 'Internal resolution and Upscaler sharpness: both ends null')
        self.assertIn('null, null, h::setText', wf, 'Frame rate: both ends null')
        self.assertNotIn('Game owned', wf, 'the in-game slider has no Game owned box')
        self.assertNotIn('CheckBox', wf)
        cd = wr.split('static void capDialog')[1]
        self.assertIn('null, null, null, false,', cd, 'no end words, no checkbox')
        self.assertNotIn('GAME_OWNED', cd)
        self.assertNotIn('Game owned', cd, 'the dialog has no Game owned box')
        self.assertNotIn('fpsLast', wr + sp, 'the last-rate memory (it served Game owned) is gone')
        # the helper line "Switching to or from Game owned applies on the next start." is not drawn for a words: game
        note = [l for l in sp.splitlines() if 'if (!WordsRows.on(game())) note(' in l]
        self.assertEqual(len(note), 1, 'one Game owned note, behind !WordsRows.on')
        self.assertIn('Switching to or from Game owned', note[0])
        self.assertIn('if (Prefs.limited(game())) {', sp.split('if (!WordsRows.on(game())) note(')[0].rsplit('\n', 3)[-3], 'the note is in the limited block, after the why line')
        # StepSlider still draws ends for the callers that pass words (DLSS stand-in, Motion sensitivity), so those are unchanged
        self.assertIn('if (left != null || right != null)', st)

    def test_words_slider_has_no_game_owned_step(self):
        """Prefs.paceCap gives a words: game its stored rate, else the recommended one, else 30 fps (WordsGfx.fpsStart); the Frame rate slider of a words: game has no Game owned step, so a stored Game owned is dropped; fps_owned_default is not read there"""
        pr = text('Prefs.java')
        pc = pr.split('static int paceCap(Context c, String titleId) {')[1].split('/** The recommended frame rate cap')[0]
        head, words = pc.split('if (p != null && p.words()) {   // PEANUTS: a words: game')
        words, rest = words.split('\n        }\n', 1)
        self.assertIn('Profile p = Profile.of(titleId);', head)
        self.assertIn('WordsGfx.fpsStart(WordsGfx.fpsSteps(paceCaps(titleId)), of(c).getInt(key, -1), WordsGfx.fpsDefault(BuildConfig.NX_PEANUTS_FPS_DEFAULT, titleId), r != null ? r : -1)', words, 'stored, else fps_default, else recommended, else 30')
        self.assertIn('if (wordsOwnedStored(c, titleId)) of(c).edit().remove(key).commit();', words, 'a stored Game owned is migrated away')
        self.assertIn('return', words, 'a words: game returns from its own block')
        self.assertNotIn('ownedBefore', words, 'fps_owned_default does not reach a words: game')
        self.assertIn('FrameRateCaps.ownedBefore(p)', rest, 'other games keep it')
        self.assertIn('limited(titleId) && wordsOn(titleId)', pr.split('static boolean chosenFpsCap')[1].split(chr(10))[0], 'a words: game always passes its rate')
        self.assertIn('&& !wordsOwnedStored(c, titleId)', pr.split('static boolean ownFpsCap')[1].split(chr(10))[0], 'a stale Game owned is no own choice')
        gs = text('GameSettings.java')
        self.assertIn('WordsRows.capDialog(a, g, done); return;', gs, 'the Game settings dialog for a words: game is WordsRows.capDialog')

    def test_reset_covers_the_new_choices(self):
        prefs = text('Prefs.java')
        keys = prefs.split('GAME_SETTINGS = {')[1].split('};')[0]
        for k in ('out_res', 'scene_pct', 'cup_mode', 'cup_sharp', 'fps_last', 'fps_cap', 'console_mode'):
            self.assertIn(f'"{k}"', keys, k)
        wr = text('WordsRows.java')
        for const, k in (('OUT', 'out_res'), ('CUP_MODE', 'cup_mode'), ('CUP_SHARP', 'cup_sharp'), ('FPS_LAST', 'fps_last')):
            self.assertRegex(wr, rf'{const} = "{k}"')
        self.assertIn('SCENE_KEY = "scene_pct"', text('WordsGfx.java'))
        self.assertIn('e.remove(WordsGfx.legacyKey(titleId));', prefs.split('static void resetGame')[1].split('e.commit();')[0], 'Reset also drops the old switch')

    def test_output_default_gradle(self):
        """build.gradle builds NX_PEANUTS_OUTPUT_DEFAULT from the output_default rows, in NX_PEANUTS_HANDHELD's style, and nothing else changes"""
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("buildConfigField 'String', 'NX_PEANUTS_OUTPUT_DEFAULT', \"\\\"$outputDefaults\\\"\"", g)
        self.assertIn("tsvRows(g, 'output_default')", g)
        self.assertIn("v in ['native', '720p', '1080p']", g, 'only the choices the launcher can offer')
        self.assertIn('more than one output_default row', g)
        self.assertIn('"$g:$v".toString()', g)
        self.assertIn("}.join(' ')", g.split('def outputDefaults')[1].split('\n\n')[0].split('\n// ')[0], 'space-separated, like handheldGames')
        self.assertNotIn('output_default', g.split('def nxProfiles')[1].split('\n')[0], 'NX_PROFILES (Profile.parse) is unchanged')

    def test_output_default_fixture(self):
        """A Python reading of outputDefaults on fixture rows: no row gives nothing; native/720p/1080p give '<id>:<value>'; a bad value or a second row fails"""
        def default_of(titles):
            out = []
            for tid, lines in titles:
                rs = rows(lines, 'output_default')
                self.assertLessEqual(len(rs), 1)
                for c in rs:
                    v = c[1].strip() if len(c) > 1 else ''
                    self.assertIn(v, ('native', '720p', '1080p'))
                    out.append(f'{tid}:{v}')
            return ' '.join(out)
        self.assertEqual(default_of([('0100317013770000', ['name\tT', 'op_mode\thandheld'])]), '')
        self.assertEqual(default_of([('A', ['output_default\t720p']), ('B', ['name\tB']), ('C', ['output_default\t1080p'])]), 'A:720p C:1080p')
        # no game.tsv of the tree has the row but a title that adds one (Sparks of Hope adds none)
        mrsoh = (REPO / 'dm/games/0100317013770000/game.tsv').read_text(encoding='utf-8').splitlines()
        self.assertEqual(rows(mrsoh, 'output_default'), [], 'Sparks of Hope has no output_default row: its BuildConfig field is empty')

    def test_fps_default_gradle(self):
        """build.gradle builds NX_PEANUTS_FPS_DEFAULT from the fps_default rows ('<titleid>:<n>', space-separated, like outputDefaults); a bad row fails the build; NX_PROFILES has no new column"""
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("buildConfigField 'String', 'NX_PEANUTS_FPS_DEFAULT', \"\\\"$fpsDefaults\\\"\"", g)
        body = g.split('def fpsDefaults')[1].split('\n// ')[0]
        self.assertIn("tsvRows(g, 'fps_default')", body)
        for what in ('more than one fps_default row', 'fps_default must be one whole number', 'fps_default needs an fps_limit row', 'is above fps_limit', 'is not one of the offered frame rates'):
            self.assertIn(what, body)
        self.assertIn('v ==~ /[1-9][0-9]/', body)
        self.assertIn("fpsModesOf(g)", body)
        self.assertIn('"$g:$v".toString()', body)
        self.assertIn("}.join(' ')", body)
        self.assertLess(g.index('def fpsLimitOf'), g.index('def fpsDefaults'), 'it reads fpsLimitOf, fpsModesOf and fpsLimitMinOf, defined before')
        self.assertNotIn('fps_default', g.split('def nxProfiles')[1].split('\n')[0], 'NX_PROFILES (Profile.parse) is unchanged')

    def test_fps_default_buildconfig_lists_the_rows(self):
        """(Replaces test_fps_default_absent_everywhere, which held only while no profile had the row): whatever profiles this tree has, NX_PEANUTS_FPS_DEFAULT lists exactly the titles that have
        an fps_default row, each with its value (empty when none has one: this tree; a tree with Three Hopes' row 'fps_default 60' and gives '<its title ID>:60'). Every row of the tree is one the build accepts"""
        titles = tree_titles()
        self.assertTrue(titles)
        want = []   # read from the files without the helper: the row's own text, one per title at most
        for tid, lines in titles:
            found = [l for l in lines if l.startswith('fps_default')]
            self.assertLessEqual(len(found), 1, tid + ': more than one fps_default row')
            for l in found:
                m = re.fullmatch(r'fps_default\t([1-9][0-9])', l.rstrip('\r'))
                self.assertIsNotNone(m, f'{tid}: {l!r}: fps_default takes one whole number after a TAB')
                want.append(f'{tid}:{m.group(1)}')
            self.assertTrue(fps_default_ok(lines), tid + ': the rows are not ones build.gradle accepts (fps_limit, offered step)')
        got = fps_default_field(titles)
        self.assertEqual(got.split(), want, 'exactly the titles with a row, each with its value')
        for tid, lines in titles:   # a title without a row is never in the list
            if not rows(lines, 'fps_default'): self.assertNotIn(tid + ':', got)

    def test_fps_default_buildconfig_fixture(self):
        """the same reading on scratch profiles (copies of Sparks of Hope's game.tsv in a temp folder, never a real file) with fps_default rows: the list has exactly the titles with a row; game.py header accepts
        those rows, and real gradle agreed on the same shapes"""
        base = [l for l in (REPO / 'dm/games/0100317013770000/game.tsv').read_text(encoding='utf-8').split('\n') if not l.startswith(('fps_limit\t', 'fps_modes\t', 'fps_default\t'))]
        modes = ['fps_limit\t60', 'fps_modes\t30,40,60']
        fixtures = {'AAAAAAAAAAAAAAA1': base + modes + ['fps_default\t60'], 'AAAAAAAAAAAAAAA2': base + modes, 'AAAAAAAAAAAAAAA3': base + ['fps_limit\t30', 'fps_default\t30'], 'AAAAAAAAAAAAAAA4': base + ['fps_limit	30']}
        with tempfile.TemporaryDirectory() as d:
            for tid, lines in fixtures.items():
                (Path(d) / tid).mkdir()
                (Path(d) / tid / 'game.tsv').write_text('\n'.join(lines + ['']), encoding='utf-8', newline='\n')
            titles = [(t.parent.name, t.read_text(encoding='utf-8').splitlines()) for t in sorted(Path(d).glob('*/game.tsv'))]
        self.assertEqual(len(titles), 4)
        self.assertTrue(all(fps_default_ok(lines) for _, lines in titles))
        self.assertEqual(fps_default_field(titles), 'AAAAAAAAAAAAAAA1:60 AAAAAAAAAAAAAAA3:30', 'two titles with a row; two with an fps_limit but no fps_default row')
        self.assertEqual(fps_default_field(titles[1:2] + titles[3:]), '', 'no title has a row: the field is empty')
        self.assertEqual(fps_default_field(titles[:1]), 'AAAAAAAAAAAAAAA1:60')
        # the tree's own titles together with the fixtures: the tree's rows, then the fixtures' (what a tree with fps_default rows gives)
        self.assertEqual(fps_default_field(tree_titles() + titles), (fps_default_field(tree_titles()) + ' ' + fps_default_field(titles)).strip())
        for extra in (modes + ['fps_default\t60'], ['fps_limit\t30', 'fps_default\t30']):
            self.assertEqual(self._gamepy_header(extra)[0], 0, extra)

    def test_fps_default_wiring(self):
        """Prefs.paceCap reads it after the stored choice; the plain part takes the table as an argument; Reset removes fps_cap so the default returns; the UI goes through paceCap"""
        wg = text('WordsGfx.java')
        for name in ('static int fpsDefault(String table, String titleId)', 'static int fpsStart(int[] steps, int stored, int def, int rec)'):
            self.assertIn(name, wg)
        self.assertNotIn('BuildConfig', wg, 'the plain part takes the table as an argument')
        self.assertIn('"fps_cap"', text('Prefs.java').split('GAME_SETTINGS = {')[1].split('};')[0])
        for name in ('WordsRows.java', 'SidePanel.java'):
            self.assertIn('Prefs.paceCap(', text(name), name + ' shows what paceCap gives (stored, else fps_default, else recommended, else 30)')
            self.assertNotIn('NX_PEANUTS_FPS_DEFAULT', text(name), 'one reader only')

    def _gamepy_header(self, extra, base_drop=('fps_limit\t',)):
        """game.py header on a copy of Sparks of Hope's game.tsv in a temp folder (never the real file) with the given extra rows; (exit code, message)"""
        base = [l for l in (REPO / 'dm/games/0100317013770000/game.tsv').read_text(encoding='utf-8').split('\n') if not l.startswith(base_drop + ('fps_modes\t', 'fps_default\t'))]
        with tempfile.TemporaryDirectory() as d:
            prof = Path(d) / '0100317013770000'
            prof.mkdir()
            (prof / 'game.tsv').write_text('\n'.join(base + extra + ['']), encoding='utf-8', newline='\n')
            r = subprocess.run([sys.executable, '-I', str(REPO / 'dm/games/game.py'), 'header', str(prof), str(Path(d) / 'out.h')], capture_output=True, text=True)
            return r.returncode, (r.stderr + r.stdout).strip()

    def test_fps_default_gamepy(self):
        """game.py takes one whole fps the slider offers and at most fps_limit; it refuses a bad value, a value not offered, two rows, a value without an fps_limit row, a row without the TAB"""
        modes = ['fps_limit\t60', 'fps_modes\t30,40,60']
        ok = [('modes 30,40,60 and default 60', modes + ['fps_default\t60']), ('default 30', modes + ['fps_default\t30']), ('default 40', modes + ['fps_default\t40']),
              ('no modes, limit 30, default 20', ['fps_limit\t30', 'fps_default\t20']), ('no modes, limit 60, default 45', ['fps_limit\t60', 'fps_default\t45']),
              ('no row', modes), ('limit_min 30, default 30', ['fps_limit\t60', 'fps_limit_min\t30', 'fps_default\t30']),
              ('whole-step title, default 30', ['fps_limit\t60', 'fps_steps\t5b76a43b231e0640521ac66203f7c178\t0x1000\t-\t0x10\t0x14', 'fps_default\t30']),
              ('modes with 120, limit 60, default 60', ['fps_limit\t60', 'fps_modes\t30,60,120', 'fps_default\t60'])]
        for name, extra in ok:
            rc, msg = self._gamepy_header(extra)
            self.assertEqual(rc, 0, f'{name}: {msg}')
        bad = [('not a number', modes + ['fps_default\tgame'], 'fps_default takes'), ('empty value', modes + ['fps_default\t'], 'fps_default takes'),
               ('a sign', modes + ['fps_default\t+60'], 'fps_default takes'), ('a fraction', modes + ['fps_default\t60.0'], 'fps_default takes'),
               ('one digit', modes + ['fps_default\t5'], 'fps_default takes'), ('three digits', modes + ['fps_default\t120'], 'fps_default takes'),
               ('a leading zero', modes + ['fps_default\t060'], 'fps_default takes'), ('two values', modes + ['fps_default\t30\t60'], 'fps_default takes'),
               ('two rows', modes + ['fps_default\t30', 'fps_default\t60'], 'fps_default takes'),
               ('not offered (modes 30,40,60)', modes + ['fps_default\t45'], 'not one of the offered'), ('not offered (20 with modes 30,40,60)', modes + ['fps_default\t20'], 'not one of the offered'),
               ('not offered (no modes: 50)', ['fps_limit\t60', 'fps_default\t50'], 'not one of the offered'),
               ('below fps_limit_min', ['fps_limit\t60', 'fps_limit_min\t30', 'fps_default\t20'], 'not one of the offered'),
               ('whole-step title: 45 is no step', ['fps_limit\t60', 'fps_steps\t5b76a43b231e0640521ac66203f7c178\t0x1000\t-\t0x10\t0x14', 'fps_default\t45'], 'not one of the offered'),
               ('above fps_limit', ['fps_limit\t30', 'fps_default\t45'], 'above fps_limit'), ('90 is above the limit of 60', modes + ['fps_default\t90'], 'above fps_limit'),
               ('no fps_limit row', ['fps_default\t30'], 'needs an fps_limit row'), ('fps_limit 0 is none', ['fps_limit\t0', 'fps_default\t30'], 'needs an fps_limit row'),
               ('a space after the key', modes + ['fps_default 60'], 'must start with the key and a TAB'), ('a leading space', modes + [' fps_default\t60'], 'must start with the key and a TAB'),
               ('a space inside the value row', modes + ['fps_default\t 60 x'], 'fps_default takes'),
               ('a words: title without an fps_limit row (the legacy list would show next to the words rows)', [], 'needs an fps_limit row'),
               ('a words: title with fps_limit 0', ['fps_limit\t0'], 'needs an fps_limit row')]
        for name, extra, want in bad:
            rc, msg = self._gamepy_header(extra)
            self.assertNotEqual(rc, 0, f'{name}: accepted')
            self.assertIn(want, msg, f'{name}: {msg}')

    def test_fps_default_gradle_fixture_rule(self):
        """the rule build.gradle applies on a row (checked in real gradle on scratch fixtures), in Python on the same rows, agrees with game.py's: the bad rows are the ones game.py refuses"""
        cases = [(['fps_limit\t60', 'fps_modes\t30,40,60', 'fps_default\t60'], True), (['fps_limit\t60', 'fps_modes\t30,40,60', 'fps_default\t45'], False), (['fps_limit\t30', 'fps_default\t45'], False),
                 (['fps_default\t30'], False), (['fps_limit\t60', 'fps_default\t20', 'fps_default\t30'], False), (['fps_limit\t60', 'fps_default\tgame'], False), (['fps_limit\t60'], True)]
        for extra, want in cases:
            self.assertEqual(fps_default_ok(extra), want, extra)
            self.assertEqual(self._gamepy_header(extra)[0] == 0, want, extra)

    def test_gate_is_read_at_every_build(self):
        """(1) the docked_only gate is read from Graphics quality each time the page or panel is built, and a choice of Graphics quality builds the page (Game settings) or the panel (in-game) again"""
        wr, gs, sm, sp = text('WordsRows.java'), text('GameSettings.java'), text('ScreenMode.java'), text('SidePanel.java')
        st = wr.split('static void settings(MainActivity a, Game g, Consumer<Ui.Setting> add, Runnable again) {')[1].split('    }\n')[0]
        self.assertIn('int gm = ScreenMode.mode(a, id);', st, 'read now, not kept from an earlier build')
        for row in ('ROW_OUTPUT', 'ROW_INTERNAL', 'ROW_UPSCALER'):
            self.assertIn(f'gt.gated(WordsGfx.{row}, gm)', st)
        self.assertLess(gs.index('WordsRows.settings(a, g, picture::add, again)'), gs.index('ScreenMode.row(a, g, again)'))
        wrow = sm.split('private static Ui.Setting wordsRow(')[1].split('// PEANUTS end')[0]
        self.assertIn('set(a, id, w == 0 ? null : w == 1 ? 1 : 0);', wrow)
        self.assertLess(wrow.index('set(a, id,'), wrow.index('done.run();'), 'the page is built again after the choice')
        panel = sp.split('head("Graphics quality");')[1].split('box.addView(rg);')[0]
        self.assertIn('ScreenMode.keep(act, id,', panel, 'the in-game choice goes through ScreenMode.keep, which calls set (test_in_game_graphics_quality_is_kept)')
        self.assertIn('restyle(rid)', panel, 'the panel is built again after the choice')
        self.assertIn('box.removeAllViews();\n            build();', sp.split('private void restyle(int focusId) {')[1].split('private')[0].replace('\r', ''))

    def test_game_default_dialog_title(self):
        """(2) Internal resolution's and the sharpness's dialogs title themselves by the box while it is checked, and disable the slider; the in-game Internal heading already did"""
        wr = text('WordsRows.java')
        self.assertIn('checkDialog(a, "Internal resolution"', wr)
        self.assertIn('checkDialog(a, "Upscaler sharpness"', wr)
        self.assertNotIn('StepSlider.dialogCheck(a, "Internal resolution"', wr)
        cd = wr.split('private static void checkDialog(')[1].split('private static void enable(')[0]
        self.assertIn('WordsGfx.checkTitle(label, check, def.isChecked(), step[0])', cd)
        self.assertIn('enable(slider, !on)', cd)
        self.assertIn('enable(slider, !checked)', cd)
        self.assertIn('WordsGfx.pctLabel(pct)', wr, 'the row value of Internal resolution')
        self.assertIn('ih.setText(on ? "Internal resolution: Game default"', text('SidePanel.java'), 'the in-game panel')

    def test_words_title_always_has_the_words_frame_rate(self):
        """(3) the Frame rate dialog with a second "Default", Game owned and unavailable rates is the legacy list (Prefs.limited false: no fps_limit row); a words: title must have the row (game.py, build.gradle), so it always gets the slider"""
        gs, pr = text('GameSettings.java'), text('Prefs.java')
        cap = gs.split('private static void cap(MainActivity a, Game g, Runnable done) {')[1]
        self.assertLess(cap.index('WordsRows.capDialog(a, g, done); return;'), cap.index('"Default (Settings: "'), 'the words slider comes before the legacy list, which is the one with the Default entries')
        self.assertIn('FrameRateCaps.limited(Profile.of(titleId))', pr.split('static boolean limited(')[1].split('\n')[0], 'Prefs.limited is FrameRateCaps.limited')
        self.assertIn('p.fpsLimit > 0', text('FrameRateCaps.java').split('static boolean limited(')[1].split('\n')[0], 'limited() is the fps_limit row (or paced, or an opt-in): without it the list is the legacy one')
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("resolutionOf(g).startsWith('words:') && fpsLimitOf(g).isEmpty()", g)
        self.assertLess(g.index("resolutionOf(g).startsWith('words:') && fpsLimitOf(g).isEmpty()"), g.index('def fpsDefaults'))
        self.assertIn('needs an fps_limit row', (REPO / 'dm/games/game.py').read_text(encoding='utf-8'))

    def test_two_upscaler_rows_are_two_things(self):
        """(4) "Internal upscaler" (WordsRows, the words composite) honours hidden=upscaler; "Upscaler" (GameSettings, the upstream present FSR / NDSR row) is a different row and is not renamed"""
        wr, gs = text('WordsRows.java'), text('GameSettings.java')
        self.assertIn('else if (gt.shows(WordsGfx.ROW_UPSCALER))', wr)
        self.assertIn('"Internal upscaler", v -> radio(', wr)
        self.assertIn('new Ui.Setting(a, "🔍", "Upscaler", v -> upscaler(a, g, again))', gs)
        self.assertNotIn('ROW_UPSCALER', gs, 'the upstream row does not read the gate')

    def test_output_default_wiring(self):
        wr = text('WordsRows.java')
        self.assertIn('BuildConfig.NX_PEANUTS_OUTPUT_DEFAULT', wr)
        self.assertIn('WordsGfx.outPick(sp(c).getString(Prefs.gameKey(OUT, id), null)', wr, 'the default applies only when nothing is stored')
        self.assertIn('WordsGfx.outStore(v,', wr)
        wg = text('WordsGfx.java')
        for name in ('static String outDefault(', 'static String outPick(', 'static String outStore('):
            self.assertIn(name, wg)
        self.assertNotIn('BuildConfig', wg, 'the plain part takes the table as an argument (the JVM test has a stub BuildConfig)')
        self.assertIn('"out_res"', text('Prefs.java').split('GAME_SETTINGS = {')[1].split('};')[0], 'Reset to defaults removes the stored choice, so the default returns')
        sm = text('ScreenMode.java')   # Graphics quality: Docked unless the profile says op_mode handheld (build.gradle handheldGames); the Output default leaves it alone
        self.assertIn('static int profile(String id) { return HANDHELD.contains(id) ? 1 : 0; }', sm)
        self.assertIn('static int own(String id) { return HANDHELD.contains(id) || PREFER_HANDHELD.contains(id) ? 1 : 0; }', sm)

    def test_fixed_window_gradle(self):
        """build.gradle builds NX_PEANUTS_FIXED_WINDOW from the view_scale_fixed rows (<titleid>:<W>x<H>, space-separated) and gives the title the words: kind; NX_PROFILES has no new column"""
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("buildConfigField 'String', 'NX_PEANUTS_FIXED_WINDOW', \"\\\"$fixedWindows\\\"\"", g)
        self.assertIn("tsvRows(game, 'view_scale_fixed')", g)
        self.assertIn(r"v ==~ /\d+x\d+/", g, 'a <W>x<H> size or the build fails')
        self.assertIn('view_scale_fixed rows disagree on the size', g)
        self.assertIn("def fixedWordsOf = { String game -> def f = fixedWindowOf(game); f.isEmpty() ? '' : 'words:' + f }", g)
        self.assertIn("!wordsOf(game).isEmpty() ? wordsOf(game) /* PEANUTS */ : !fixedWordsOf(game).isEmpty() ? fixedWordsOf(game) /* PEANUTS */ : !tsvRows(game, 'window_fn')", g,
                      'after the window_words kind, before the older kinds')
        self.assertIn('"$g:$f".toString()', g)
        self.assertIn("}.findAll { it }.join(' ')", g.split('def fixedWindows')[1].split('\n')[0], 'space-separated, like outputDefaults')
        self.assertNotIn('view_scale_fixed', g.split('def nxProfiles')[1].split('\n')[0], 'NX_PROFILES carries no new column: a fixed title is words:<W>x<H> in the resolution column, every other title is as it was')

    def test_fixed_window_fixture(self):
        """A Python reading of fixedWordsOf / resolutionOf on fixture rows: a fixed row gives words:<WxH>; no row gives what it gave; a window_words row still wins"""
        fixed = ['name\tF', 'view_scale_words\tf62a7d41\t0x1d9f618:0000403f:f32', 'view_scale_fixed\tf62a7d41\t1920x1080', 'op_mode\thandheld']
        self.assertEqual(resolution_of(fixed), 'words:1920x1080')
        self.assertEqual(resolution_of(['view_scale_fixed\tx\t1280x720']), 'words:1280x720')
        self.assertEqual(resolution_of(fixed, words=False), '', 'without the row the title has no kind')
        for lines in (['name\tA'], ['window_fn\tx'], ['res_levels\tx'], ['graphics_scale\t1920x1080\t2\tconsole'], ['render_cfg\tx\ty']):
            self.assertEqual(resolution_of(lines), resolution_of(lines, words=False), lines)
        self.assertEqual(resolution_of(['window_words\t5b76a43b\t0x1:2:w', 'view_scale_fixed\tx\t1280x720']), 'words:1920x1080', 'window_words is read first')
        for t in sorted((REPO / 'dm/games').glob('*/game.tsv')):   # a real profile that has the row (none in this tree) is f32 and not windowed
            lines = t.read_text(encoding='utf-8').splitlines()
            if not rows(lines, 'view_scale_fixed'): continue
            self.assertTrue(rows(lines, 'view_scale_words'), t.parent.name)
            self.assertFalse(rows(lines, 'window_words'), t.parent.name)
            self.assertRegex(rows(lines, 'view_scale_fixed')[0][2].strip(), r'^\d+x\d+$')

    def test_words_gate_gradle(self):
        """build.gradle builds NX_PEANUTS_WORDS_GATE from the words_gate row (<titleid>:<spec>), failing loudly on a bad row; no profile.bin field, no NX_PROFILES column"""
        g = GRADLE.read_text(encoding='utf-8')
        self.assertIn("buildConfigField 'String', 'NX_PEANUTS_WORDS_GATE', \"\\\"$wordsGates\\\"\"", g)
        body = g.split('def wordsGates')[1].split('\n// ')[0]
        self.assertIn("tsvRows(g, 'words_gate')", body)
        self.assertIn('more than one words_gate row', body)
        self.assertIn("['docked_only', 'hidden']", body)
        self.assertIn("['output', 'internal', 'upscaler']", body)
        self.assertIn('words_gate needs docked_only=<list> and/or hidden=<list>', body, 'at least one part')
        self.assertIn("}.join(' ')", body, 'space-separated, like outputDefaults')
        self.assertNotIn('words_gate', g.split('def nxProfiles')[1].split('\n')[0], 'NX_PROFILES (Profile.parse) is unchanged')

    def test_words_gate_fixture(self):
        """A Python reading of wordsGates on fixture rows: the spec string"""
        def gate_of(titles):
            out = []
            for tid, lines in titles:
                rs = rows(lines, 'words_gate')
                self.assertLessEqual(len(rs), 1)
                for c in rs:
                    parts = [p for f in c[1:] for p in f.split()]
                    self.assertTrue(parts)
                    seen = {}
                    for p in parts:
                        k, _, v = p.partition('=')
                        self.assertIn(k, ('docked_only', 'hidden'))
                        self.assertNotIn(k, seen)
                        names = [x.strip() for x in v.split(',')]
                        self.assertTrue(all(n in ('output', 'internal', 'upscaler') for n in names) and len(set(names)) == len(names), p)
                        seen[k] = ','.join(names)
                    out.append(f'{tid}:' + ';'.join(f'{k}={v}' for k, v in seen.items()))
            return ' '.join(out)
        self.assertEqual(gate_of([('A', ['name\tA'])]), '')
        self.assertEqual(gate_of([('F', ['words_gate\tdocked_only=output,internal\thidden=upscaler'])]), 'F:docked_only=output,internal;hidden=upscaler')
        self.assertEqual(gate_of([('F', ['words_gate\thidden=upscaler']), ('G', ['words_gate\tdocked_only=output'])]), 'F:hidden=upscaler G:docked_only=output')
        mrsoh = (REPO / 'dm/games/0100317013770000/game.tsv').read_text(encoding='utf-8').splitlines()
        self.assertEqual(rows(mrsoh, 'words_gate') + rows(mrsoh, 'view_scale_fixed'), [], 'Sparks of Hope has neither row: both BuildConfig fields are empty for it')

    def test_fixed_window_wiring(self):
        """the rows, the panel and the start follow the fixed window and the gate"""
        wr = text('WordsRows.java')
        self.assertIn('BuildConfig.NX_PEANUTS_FIXED_WINDOW', wr)
        self.assertIn('BuildConfig.NX_PEANUTS_WORDS_GATE', wr)
        self.assertIn('ScreenMode.mode(a, id)));', wr.split('static void env(Activity a')[1].split('startedSig = sig(a, id);')[0], 'the start hands over the effective Graphics quality')
        self.assertIn('WordsGfx.DOCKED_ONLY', wr, 'the disabled row reads Docked only')
        self.assertIn('s.view.setEnabled(false);', wr)
        side = text('SidePanel.java').split('private void wordsPicture()')[1].split('// PEANUTS end')[0]
        self.assertIn('WordsGfx.DOCKED_ONLY', side)
        self.assertIn('WordsRows.gate(id)', side)
        wg = text('WordsGfx.java')
        for name in ('static int[] fixedSize(', 'static int[] nativeFixed(', 'static Gate gateOf(', 'static List<Out> outs('):
            self.assertIn(name, wg)
        self.assertIn('"DM_OUT_SIZE="', wg)
        self.assertNotIn('BuildConfig', wg, 'the plain part takes the tables as arguments')
        self.assertIn('static int[] nativeOut(', wg, 'the windowed titles keep nativeOut')
        self.assertIn('static final int[][] SIZES = {{1920, 1080}, {1280, 720}};', wg, 'SIZES is untouched')

    def test_no_android_classes_in_the_plain_part(self):
        self.assertNotRegex(text('WordsGfx.java'), r'import android\.|android\.')

    def test_contract_env_names(self):
        wg = text('WordsGfx.java')
        for name in ('DM_WINDOW_SIZE=', 'DM_OUT_SIZE=', 'DM_SCENE_PCT=', 'DM_VK_CUPSCALE=', 'DM_VK_CUPSCALE_SHARP='):
            self.assertIn(name, wg)
        self.assertIn('{1, 2, 4}', wg, 'DM_VK_CUPSCALE: 1 bilinear (the game\'s own), 2 FSR 1, 4 SGSR 1')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class WordsGfxJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'stub').mkdir()
            (d / 'stub/BuildConfig.java').write_text(STUB)
            srcs = [SRC / f'{n}.java' for n in ('WordsGfx', 'Profile', 'FrameRateCaps')]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(TEST), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.WordsGfxJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
