"""Profile option rows and environment variable generation (Profile.java with android/app/src/jvmtest's
ProfileOptionsJvmTest, and build.gradle's optionsOf). The JVM part is skipped without a JDK."""
# PEANUTS(upstream-candidate): omit the unused re import.
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/Profile.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/ProfileOptionsJvmTest.java'
GRADLE = REPO / 'android/app/build.gradle'
OOT_TSV = REPO / 'dm/games/0400486024DEC000/game.tsv'

STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
}
'''


class OptionTsv(unittest.TestCase):
    def test_oot_options_in_game_tsv(self):
        rows = [line.split('\t') for line in OOT_TSV.read_text(encoding='utf-8').splitlines() if line.startswith('option\t')]
        self.assertEqual(len(rows), 4, f'OoT must declare 4 options: {rows}')
        envs = [r[1].strip() for r in rows]
        self.assertEqual(envs, ['DM_OOT_MAX_RESOLUTION', 'DM_OOT_TEXTURE_POOL_X2', 'DM_OOT_FPS_UNLOCK', 'DM_VK_OQ_SPLIT_PASS'])
        labels = [r[2].strip() for r in rows]
        self.assertEqual(labels, ['Max resolution', 'Texture pool ×2', 'Unlocked frame rate', 'Keep cave walls (Qualcomm)'])
        # Check attributes
        max_res_attrs = [tok for col in rows[0][4:] for tok in col.split()]
        self.assertIn('restart', max_res_attrs)
        self.assertIn('implies=DM_OOT_TEXTURE_POOL_X2', max_res_attrs)
        self.assertIn('needs=OOT_PATCHES', max_res_attrs)

        pool_attrs = [tok for col in rows[1][4:] for tok in col.split()]
        self.assertIn('restart', pool_attrs)
        self.assertIn('needs=OOT_PATCHES', pool_attrs)

        fps_attrs = [tok for col in rows[2][4:] for tok in col.split()]
        self.assertIn('restart', fps_attrs)
        self.assertIn('needs=OOT_PATCHES', fps_attrs)

    def test_mrkb_depth_of_field_option(self):   # PEANUTS
        tsv = (REPO / 'dm/games/010067300059A000/game.tsv').read_text(encoding='utf-8').splitlines()
        opt = [l.split('	') for l in tsv if l.startswith('option	')]
        if not opt:   # PEANUTS: the row lives in the Kingdom Battle profile; a tree without it (the Sparks of Hope clone) has nothing to pin here
            self.skipTest('Kingdom Battle DoF row not in this tree (dm/games/010067300059A000/game.tsv has no option row)')
        self.assertEqual(len(opt), 1)
        self.assertEqual(opt[0][1:4], ['DM_KB_DOF_OFF', 'Depth of field', ''], 'title only: no description')
        self.assertEqual([tok for col in opt[0][4:] for tok in col.split()], ['restart', 'perf', 'inverted'])
        patch = [l.split('	') for l in tsv if l.startswith('option_patch	')]
        self.assertEqual(patch, [['option_patch', 'DM_KB_DOF_OFF', '3b39e0c06b8841f1baf0ea4d54286bfe', '0xa55938', '08014039', '28008052']])
        self.assertEqual(int.from_bytes(bytes.fromhex(patch[0][5]), 'little'), 0x52800028, 'mov w8, #1')

    def test_perf_inverted_code_pins(self):   # PEANUTS: the Java / build.gradle half of the old test, tree-independent (runs without the Kingdom Battle row)
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn("tok == 'perf'", gradle)
        self.assertIn('[env, label, desc, mode, implies, needs, patches, perf, inverted]', gradle)
        self.assertIn("tok == 'inverted'", gradle)
        gs = (REPO / 'android/app/src/main/java/dev/nxnative/android/GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('(opt.perf ? picture : options).add(', gs, 'the Performance tab: a Picture row')
        self.assertIn('opt.shown(Prefs.gameOption(a, id, opt.env))', gs, 'inverted: the row shows the opposite')
        self.assertIn('Prefs.gameOption(a, id, opt.env, opt.stored(checked));', gs, 'inverted: and stores the option as ever')
        sp = (REPO / 'android/app/src/main/java/dev/nxnative/android/SidePanel.java').read_text(encoding='utf-8')
        self.assertIn('if (!opt.live && !opt.perf) continue;', sp, 'the in-game Performance tab')
        self.assertIn('if (opt.live || opt.perf) continue;', sp, 'not repeated under Next start')
        self.assertIn('note(WordsGfx.NEXT_START);', sp, 'the restart note is the Graphics rows\' line: Applies on next start')
        self.assertIn('Prefs.gameOption(act, game(), opt.env, opt.stored(on))', sp)
        a, b, c = sp.find('perfOptionRows(true);'), sp.find('lsfgNow = Lsfg.rows(act, box, "", '), sp.find('perfOptionRows(false);')   # PEANUTS: Depth of field is directly above Frame gen
        self.assertTrue(0 < a < b < c, 'Performance tab order: perf options, Frame gen, other live options')
        self.assertEqual(sp.count('perfOptionRows(true);'), 1)
        self.assertEqual(sp[a:b].strip().count('\n'), 0, 'nothing between the perf options and the Frame gen rows')
        self.assertEqual(sp.count('opt.shown(Prefs.gameOption(act, game(), opt.env))'), 2, 'both panel lists show the opposite for inverted')
        words = (REPO / 'android/app/src/main/java/dev/nxnative/android/WordsGfx.java').read_text(encoding='utf-8')
        self.assertIn('NEXT_START = "Applies on next start";', words)
        for p in sorted((REPO / 'dm/games').glob('*/game.tsv')):   # no other profile uses the attributes
            if p.parent.name == '010067300059A000': continue
            for l in p.read_text(encoding='utf-8').splitlines():
                if l.startswith('option\t'):
                    toks = [tok for col in l.split('\t')[4:] for tok in col.split()]
                    self.assertNotIn('perf', toks, p.parent.name); self.assertNotIn('inverted', toks, p.parent.name)

    def test_game_py_option_attributes(self):   # PEANUTS: perf / inverted validation, on synthetic profiles through game.py header
        import os, sys
        tid = '0100000000F0F000'
        base = ('title_id\t' + tid + '\nname\tTest\nprogram_id\t' + tid + '\nmodules\tmain\nromfs_marker\tromfs.img\n'
                'mem_reserve\t0x100000\nmem_alias\t0x100000\nmem_stack\t0x100000\napp_pool\t0x100000\n')

        def header(row):
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / tid).mkdir()
                (Path(d) / tid / 'game.tsv').write_text(base + row + '\n')
                r = subprocess.run([sys.executable, str(REPO / 'dm/games/game.py'), 'header', tid, str(Path(d) / 'g.h')],
                                   env={**os.environ, 'DM_GAMES_DIR': d}, capture_output=True, text=True)
            return r.returncode, r.stderr + r.stdout
        o = 'option\tDM_T\tT\t'
        self.assertEqual(header(o + 'A plain description.\trestart')[0], 0)
        self.assertEqual(header(o + '\trestart perf inverted')[0], 0, 'perf inverted with no description')
        self.assertEqual(header(o + 'Text here.\trestart inverted')[0], 0)
        rc, out = header(o + '\trestart')
        self.assertNotEqual(rc, 0, 'an empty description is for perf options only')
        self.assertIn('option takes', out)
        self.assertNotEqual(header(o + '\trestart inverted')[0], 0)
        rc, out = header(o + 'X.\trestart inverted implies=DM_U')
        self.assertNotEqual(rc, 0)
        self.assertIn('cannot also imply', out)
        rc, out = header(o + 'X.\trestart perfx')
        self.assertNotEqual(rc, 0)
        self.assertIn('unknown attribute', out)

    def test_gradle_options_of_parser(self):
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn("def optionsOf = { String game ->", gradle)
        self.assertIn("optionsOf(g)", gradle)
        self.assertIn("implies=", gradle)
        self.assertIn("needs=", gradle)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ProfileOptionsJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            stub = Path(d) / 'BuildConfig.java'
            stub.write_text(STUB)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST), str(stub)],
                           check=True, capture_output=True, text=True)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.ProfileOptionsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
