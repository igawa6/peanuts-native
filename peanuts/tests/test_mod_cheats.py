"""The enabled mods' cheat files in upstream's cheat list (dev.nxnative.android.ModCheats over upstream's Cheats.java, both without Android classes): one merged
file and one list, so the folder the host is given (DM_CHEATS) holds what the in-game panel lists and toggles. javac/java only, the pattern of test_ctl.py."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PKG = REPO / 'android/app/src/main/java/dev/nxnative/android'
MODS = REPO / 'android/app/src/main/java/dev/peanuts/mods'
BUILD = '0123456789ABCDEF'

RUNNER = r'''package dev.nxnative.android;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
public final class ModCheatsTest {
    public static void main(String[] a) throws Exception {   // shipped-or-"-" folder build on(comma) modCheatsDir...
        List<File> dirs = new ArrayList<>();
        for (int i = 4; i < a.length; i++) dirs.add(new File(a[i]));
        java.util.Set<String> on = new LinkedHashSet<>(a[3].isEmpty() ? new ArrayList<String>() : Arrays.asList(a[3].split(",")));
        List<String> notes = new ArrayList<>();
        List<Cheats.Entry> l = ModCheats.install(a[0].equals("-") ? null : new File(a[0]), new File(a[1]), a[2], on, dirs, notes);
        if (l == null) { System.out.println("none"); return; }
        StringBuilder sb = new StringBuilder();
        for (Cheats.Entry e : l) if (e.toggles()) sb.append(e.key).append('|');
        System.out.println(sb);
        System.out.println(String.join("|", on));
        System.out.println(String.join("|", notes));
    }
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
class ModCheats(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='modcheats-'))
        src = cls.tmp / 'src/dev/nxnative/android'; src.mkdir(parents=True)
        (src / 'ModCheatsTest.java').write_text(RUNNER, encoding='utf-8')
        cls.out = cls.tmp / 'out'
        subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-d", str(cls.out), str(PKG / "Cheats.java"), str(PKG / "Json.java"), str(PKG / "ModCheats.java"), *[str(MODS / f) for f in ("CheatFile.java", "CheatImport.java", "CheatSources.java", "CheatValues.java")], str(src / "ModCheatsTest.java")], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_install(self, shipped, folder, on, dirs, full=False):
        r = subprocess.run(['java', '-cp', str(self.out), 'dev.nxnative.android.ModCheatsTest', str(shipped) if shipped else '-', str(folder), BUILD, ','.join(on), *map(str, dirs)],
                           capture_output=True, text=True, check=True)
        lines = r.stdout.split('\n')
        if lines[0] == 'none' or not full: return lines[0]
        return lines[0].strip('|').split('|'), [k for k in lines[1].split('|') if k], [n for n in lines[2].split('|') if n]

    def case(self, name):
        d = self.tmp / name; d.mkdir()
        return d

    def mod(self, d, mod, file, text):
        c = d / 'mods' / mod / 'cheats'; c.mkdir(parents=True, exist_ok=True)
        (c / file).write_text(text, encoding='utf-8')
        return c

    def test_mods_join_the_profile_and_the_imported_cheats(self):
        d = self.case('all')
        shipped = d / f'{BUILD}.txt'; shipped.write_text('[Moon Jump]\n04000000 00000010 00000001\n', encoding='utf-8')
        folder = d / 'cheats'; (folder / 'imported').mkdir(parents=True)
        (folder / 'imported' / 'mine.txt').write_text(f'build {BUILD}\n[Coins]\n04000000 00000020 00000063\n', encoding='utf-8')
        m1 = self.mod(d, 'First Mod', f'{BUILD}.txt', '[Moon Jump]\n04000000 00000010 00000002\n[Speed]\n04000000 00000030 00000002\n')
        self.mod(d, 'First Mod', 'FEDCBA9876543210.txt', '[Other Build]\n04000000 00000040 00000001\n')
        m2 = self.mod(d, 'second', f'{BUILD.lower()}.txt', '[Speed]\n04000000 00000030 00000004\n')
        keys, on, notes = self.run_install(shipped, folder, ['Moon Jump', 'Speed (second)'], [m1, m2], full=True)
        self.assertEqual(keys, ['Moon Jump', 'Coins', 'Moon Jump (First Mod)', 'Speed (First Mod)', 'Speed (second)'], 'the profile\'s, the imported, then each mod\'s under the mod\'s name')
        self.assertEqual(notes, [])
        merged = (folder / f'{BUILD}.txt').read_text(encoding='utf-8')
        self.assertIn('[--SectionStart:First Mod--]', merged, "each mod's cheats under the mod's name (dev.peanuts.mods.CheatSources.merge)")
        self.assertIn('[--SectionStart:second--]', merged)
        self.assertIn('[--SectionStart:mine--]', merged, 'the earlier import is a source now')
        self.assertNotIn('Other Build', merged, 'a mod\'s file of another build is left out')
        self.assertEqual((folder / f'{BUILD}.enabled').read_text(encoding='utf-8').split('\n')[:-1], ['Moon Jump', 'Speed (second)'], 'the toggles the player set, by key')
        self.assertEqual(sorted(p.name for p in folder.iterdir()), [f'{BUILD}.enabled', f'{BUILD}.txt', 'sources'], 'one merged file and its toggles; imported/ moved into sources/')
        # the first mod turned off: the second mod's cheat keeps its key, and with it its toggle (the key was "Speed (imported)" while the first mod was on, "Speed" without it)
        keys, on, _ = self.run_install(shipped, folder, ['Moon Jump', 'Speed (second)'], [m2], full=True)
        self.assertEqual(keys, ['Moon Jump', 'Coins', 'Speed (second)'])
        self.assertEqual((folder / f'{BUILD}.enabled').read_text(encoding='utf-8').split('\n')[:-1], ['Moon Jump', 'Speed (second)'])

    def test_a_mods_own_toggles_count_once(self):
        d = self.case('own')
        m = self.mod(d, 'm', f'{BUILD}.txt', '{Master}\n04000000 00000000 00000001\n[A]\n04000000 00000010 00000001\n[B]\n04000000 00000020 00000001\n[A]\n04000000 00000030 00000001\n')
        (m / f'{BUILD}.enabled').write_text('B\r\nNo Such Cheat\n', encoding='utf-8')
        folder = d / 'cheats'
        keys, on, _ = self.run_install(None, folder, [], [m], full=True)
        self.assertEqual(keys, ['A (m)', 'B (m)', 'A (m) #2'], "a name twice in one file: the host's own #2 key")
        self.assertEqual(on, ['B (m)'], 'what the mod turns on is on the first time its cheats are listed')
        self.assertEqual((folder / f'{BUILD}.enabled').read_text(encoding='utf-8'), 'B (m)\n')
        self.assertEqual((folder / 'mods.seen').read_text(encoding='utf-8'), 'B (m)\n')
        keys, on, _ = self.run_install(None, folder, [], [m], full=True)   # the player turned it off since
        self.assertEqual(on, [], 'and not again: the player\'s toggles decide from then on')
        self.assertEqual((folder / f'{BUILD}.enabled').read_text(encoding='utf-8'), '')
        self.assertTrue((folder / 'mods.seen').is_file(), 'what was offered is kept across starts (install clears only .txt and .enabled)')

    def test_a_mod_file_that_cannot_be_read_is_left_out(self):
        d = self.case('bad')
        shipped = d / f'{BUILD}.txt'; shipped.write_text('[A]\n04000000 00000010 00000001\n', encoding='utf-8')
        m = self.mod(d, 'big', f'{BUILD}.txt', '[Huge]\n' + '04000000 00000010 00000001\n' * 12000)   # over Cheats.MAX_FILE
        g = self.mod(d, 'good', f'{BUILD}.txt', '[G]\n04000000 00000010 00000001\n')
        (m / 'unreadable.txt').mkdir()   # a folder named as a cheat file: reading it fails
        keys, on, notes = self.run_install(shipped, d / 'cheats', [], [m, g], full=True)
        self.assertEqual(keys, ['A', 'G (good)'], 'the profile\'s cheats and the other mod\'s are installed')
        self.assertEqual(len(notes), 2, notes)
        self.assertTrue(all('is left out' in n and 'big' in n for n in notes), notes)

    def test_a_long_name_keeps_the_mods_name(self):
        d = self.case('long')
        m = self.mod(d, 'm', f'{BUILD}.txt', '[' + 'x' * 127 + ']\n04000000 00000010 00000001\n')
        keys, _, _ = self.run_install(None, d / 'cheats', [], [m], full=True)
        self.assertEqual(keys, ['x' * 123 + ' (m)'])

    def test_a_game_with_only_a_mods_cheats(self):
        d = self.case('modonly')
        m = self.mod(d, 'm', f'{BUILD}.txt', '[Only]\n04000000 00000010 00000001\n')
        self.assertEqual(self.run_install(None, d / 'cheats', ['Only (m)'], [m]), 'Only (m)|')
        self.assertEqual((d / 'cheats' / f'{BUILD}.enabled').read_text(encoding='utf-8'), 'Only (m)\n')

    def test_without_mods_it_is_upstreams_install(self):
        d = self.case('plain')
        shipped = d / f'{BUILD}.txt'; shipped.write_text('[A]\n04000000 00000010 00000001\n', encoding='utf-8')
        self.assertEqual(self.run_install(shipped, d / 'cheats', [], []), 'A|')
        self.assertEqual(self.run_install(None, d / 'none', [], [d / 'no such folder']), 'none')

if __name__ == '__main__':
    unittest.main()
