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
# PEANUTS: Ocarina of Time (0400486024DEC000, four option rows with implies= / needs=) is a Switch 2 profile Peanuts Native does not carry: its rows are a
# fixture below, and the Switch 1 profiles' option rows (Persona 5 Royal, Luigi's Mansion 3) are checked as they are
OOT_ROWS = ('option\tDM_OOT_MAX_RESOLUTION\tMax resolution\tKeeps the 3D scene at full size: sharper, more GPU work. Best with Resolution › Quality\trestart\t'
            'implies=DM_OOT_TEXTURE_POOL_X2\tneeds=OOT_PATCHES\n'
            'option\tDM_OOT_TEXTURE_POOL_X2\tTexture pool ×2\tTwice the game\'s starting graphics memory. Max resolution needs it\trestart\tneeds=OOT_PATCHES\n'
            'option\tDM_OOT_FPS_UNLOCK\tUnlocked frame rate\tGame logic follows real time, so speed holds when fps changes. Does not raise fps\trestart\tneeds=OOT_PATCHES\n'
            'option\tDM_VK_OQ_SPLIT_PASS\tKeep cave walls (Qualcomm)\tFixes walls and rocks that vanish on Death Mountain Trail with Qualcomm\'s driver.\trestart\n')

STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
}
'''


class OptionTsv(unittest.TestCase):
    def test_switch1_options_in_game_tsv(self):   # PEANUTS begin: the Switch 1 profiles' option rows
        for game, env, label in (('0100DCA0064A6000', 'DM_LM3_FIXED_RES', 'Fixed docked resolution'),):   # P5R's HD resolution option removed (owner 2026-11-26)
            rows = [l.split('\t') for l in (REPO / 'dm/games' / game / 'game.tsv').read_text(encoding='utf-8').splitlines() if l.startswith('option\t')]
            self.assertEqual([(r[1].strip(), r[2].strip()) for r in rows], [(env, label)], game)
            self.assertIn('restart', [tok for col in rows[0][4:] for tok in col.split()], game)
        for p in (REPO / 'dm/games').glob('*/game.tsv'):
            for r in (l.split('\t') for l in p.read_text(encoding='utf-8').splitlines() if l.startswith('option\t')):
                self.assertRegex(r[1].strip(), r'^DM_[A-Z0-9_]+$', p)
                for tok in (t for col in r[4:] for t in col.split()): self.assertRegex(tok, r'^(restart|live|implies=DM_[A-Z0-9_,]+|needs=[A-Z0-9_]+|group=[a-z0-9_]+)$', f'{p}: {tok}')
        # PEANUTS end

    def test_oot_options_in_game_tsv(self):
        rows = [line.split('\t') for line in OOT_ROWS.splitlines() if line.startswith('option\t')]   # PEANUTS: the fixture
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

    def test_gradle_options_of_parser(self):
        gradle = GRADLE.read_text(encoding='utf-8')
        self.assertIn("def optionsOf = { String game ->", gradle)
        self.assertIn("optionsOf(g)", gradle)
        self.assertIn("implies=", gradle)
        self.assertIn("needs=", gradle)
        # PEANUTS: group= is written as a <key>=<value> token only when set, so an option without a group keeps the 7-field encoding
        self.assertIn("(group ? ['group=' + group] : [])", gradle)
        self.assertIn("tsvRows(g, 'thermal_cap').isEmpty() ? '' : '1', optionGroupsOf(g)].join", gradle)


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
