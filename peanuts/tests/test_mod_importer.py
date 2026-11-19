"""ModImporter's layout normalizer and nested bundle discovery, plain-Java javac/java."""
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRCS = [
    REPO / 'android/app/src/main/java/dev/peanuts/mods/ModStore.java',
    REPO / 'android/app/src/main/java/dev/peanuts/mods/ModImporter.java',
]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class ModImporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_importer_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ModImporterTest.java'
        runner.write_text('''package dev.peanuts.mods;
import java.io.File;
public final class ModImporterTest {
    public static void main(String[] args) throws Exception {
        File staging = new File(args[0]);
        File modsRoot = new File(args[1]);
        String titleId = args[2];
        String displayName = args[3];
        ModImporter.Result r = ModImporter.importFrom(staging, modsRoot, titleId, displayName, null);
        if (!r.ok) { System.out.println("error: " + r.error); return; }
        System.out.println(r.modNames.size() == 1 ? "ok: " + r.modName + " " + r.kind
                : "ok: " + r.modNames.size() + " mods " + r.kind);
    }
}
''');
        subprocess.run(['javac', '-d', str(cls.classes)] + [str(s) for s in SRCS] + [str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.staging = Path(tempfile.mkdtemp(dir=self.root, prefix='staging_'))
        self.mods_root = Path(tempfile.mkdtemp(dir=self.root, prefix='mods_'))
        self.title = '0100000000000000'

    def run_import(self, name='My Mod'):
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModImporterTest',
                             str(self.staging), str(self.mods_root), self.title, name],
                            check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def write(self, rel, content=b'x'):
        p = self.staging / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)

    def npdm(self):
        data = bytearray(0xa0)
        data[:4] = b'META'
        struct.pack_into('<I', data, 0x70, 0x80)
        data[0x80:0x84] = b'ACI0'
        struct.pack_into('<Q', data, 0x90, int(self.title, 16))
        return bytes(data)

    def mod_dir(self, name):
        return self.mods_root / name   # the mods root is per game now (<game home>/mods, no title-ID level)

    # ---- bare layout ----
    def test_bare_romfs_only_is_a_files_mod(self):
        self.write('romfs/Model/retexture.bntx')
        out = self.run_import()
        self.assertEqual(out, 'ok: My Mod files')
        self.assertTrue((self.mod_dir('My Mod') / 'romfs' / 'Model' / 'retexture.bntx').is_file())

    def test_bare_exefs_makes_it_a_code_mod(self):
        self.write('romfs/text.txt')
        self.write('exefs/subsdk1')
        out = self.run_import()
        self.assertEqual(out, 'ok: My Mod code')
        self.assertTrue((self.mod_dir('My Mod') / 'exefs' / 'subsdk1').is_file())
        self.assertTrue((self.mod_dir('My Mod') / 'romfs' / 'text.txt').is_file())

    def test_bare_cheats_only(self):
        self.write('cheats/01020304AABBCCDD.txt', b'[Cheat]\n04000000 00000000 00000000\n')
        out = self.run_import()
        self.assertEqual(out, 'ok: My Mod files')
        self.assertTrue((self.mod_dir('My Mod') / 'cheats' / '01020304AABBCCDD.txt').is_file())

    # ---- Yuzu/Eden "load" layout: a single wrapper folder holding romfs/exefs ----
    def test_load_layout_single_wrapper_uses_its_name(self):
        self.write('CTGP-DX/romfs/Course/new_course.szs')
        out = self.run_import('ignored')
        self.assertEqual(out, 'ok: CTGP-DX files')
        self.assertTrue((self.mod_dir('CTGP-DX') / 'romfs' / 'Course' / 'new_course.szs').is_file())

    def test_load_layout_with_exefs_is_code(self):
        self.write('RaceMod/romfs/x.bin')
        self.write('RaceMod/exefs/subsdk0')
        out = self.run_import()
        self.assertEqual(out, 'ok: RaceMod code')

    def test_nested_bundle_imports_each_mod_as_a_separate_option(self):
        self.write('Release/Modules/Core Module/romfs/config/core.ini')
        self.write('Release/Modules/Core Module/exefs/main.npdm', self.npdm())
        self.write('Release/Modules/Core Module/exefs/subsdk6')
        self.write('Release/Features/Feature One/romfs/config/one.ini')
        self.write('Release/Features/Feature One/romfs/payload/Another/romfs/not-a-mod.bin')
        self.write('Release/Features/Feature Two/romfs/config/two.ini')
        for i in range(3, 8):
            self.write(f'Release/Features/Feature {i}/romfs/config/settings.ini')
        self.write('Release/__MACOSX/Hidden Mod/romfs/ignored.bin')

        out = self.run_import('Release')

        self.assertEqual(out, 'ok: 8 mods code')
        for name in ('Core Module', 'Feature One', 'Feature Two', *(f'Feature {i}' for i in range(3, 8))):
            self.assertTrue((self.mod_dir(name) / 'mod.json').is_file(), name)
        self.assertIn('"kind": "code"', (self.mod_dir('Core Module') / 'mod.json').read_text())
        self.assertIn('"kind": "files"', (self.mod_dir('Feature One') / 'mod.json').read_text())
        self.assertTrue((self.mod_dir('Core Module') / 'exefs' / 'subsdk6').is_file())
        self.assertTrue((self.mod_dir('Feature One') / 'romfs' / 'payload' / 'Another' / 'romfs' / 'not-a-mod.bin').is_file())
        self.assertFalse(self.mod_dir('Another').exists())
        self.assertFalse(self.mod_dir('Hidden Mod').exists())

    def test_atmosphere_layout_inside_arbitrary_wrapper_is_discovered(self):
        self.write(f'Release Folder/atmosphere/contents/{self.title}/romfs/Pack01/data.arc')
        self.write('Release Folder/Other Feature/romfs/feature.ini')
        out = self.run_import('ignored')
        self.assertEqual(out, 'ok: 2 mods files')
        self.assertTrue((self.mod_dir('Release Folder') / 'romfs' / 'Pack01' / 'data.arc').is_file())
        self.assertTrue((self.mod_dir('Other Feature') / 'romfs' / 'feature.ini').is_file())

    def test_invalid_nested_candidate_prevents_partial_bundle_install(self):
        self.write('Release/Good Mod/romfs/good.bin')
        self.write('Release/Wrong Game/atmosphere/contents/0100000000000099/romfs/bad.bin')
        out = self.run_import('Release')
        self.assertIn('0100000000000099', out)
        self.assertFalse(any(self.mods_root.iterdir()))

    # ---- Atmosphere layout ----
    def test_atmosphere_matching_title_id(self):
        tid = self.title
        self.write(f'atmosphere/contents/{tid}/romfs/Pack01/data.arc')
        self.write(f'atmosphere/contents/{tid}/exefs/main.npdm', self.npdm())
        self.write(f'atmosphere/contents/{tid}/exefs/subsdk6')
        self.write(f'atmosphere/exefs_patches/anti_desync/1.ips')
        out = self.run_import('CTGP-DX')
        self.assertEqual(out, 'ok: CTGP-DX code')
        self.assertTrue((self.mod_dir('CTGP-DX') / 'romfs' / 'Pack01' / 'data.arc').is_file())
        self.assertTrue((self.mod_dir('CTGP-DX') / 'exefs' / 'subsdk6').is_file())
        self.assertTrue((self.mod_dir('CTGP-DX') / 'exefs_patches' / 'anti_desync' / '1.ips').is_file())

    def test_atmosphere_mismatched_title_id_is_rejected(self):
        self.write('atmosphere/contents/0100000000000099/romfs/x.bin')
        out = self.run_import()
        self.assertTrue(out.startswith('error: '))
        self.assertIn('0100000000000099', out)
        self.assertFalse(self.mod_dir('My Mod').exists())

    def test_atmosphere_case_insensitive_title_id_match(self):
        self.write(f'atmosphere/contents/{self.title.lower()}/romfs/x.bin')
        out = self.run_import()
        self.assertEqual(out, 'ok: My Mod files')

    # ---- nothing recognizable ----
    def test_nothing_recognizable_is_an_error_not_an_empty_mod(self):
        self.write('readme.txt', b'just docs')
        out = self.run_import()
        self.assertTrue(out.startswith('error: '))
        self.assertFalse(self.mod_dir('My Mod').exists())

    # ---- name collision ----
    def test_name_collision_gets_a_suffix(self):
        self.write('romfs/a.bin')
        self.run_import('Dupe')
        # a second, unrelated staging folder importing under the same requested name
        staging2 = Path(tempfile.mkdtemp(dir=self.root, prefix='staging2_'))
        (staging2 / 'romfs').mkdir()
        (staging2 / 'romfs' / 'b.bin').write_bytes(b'y')
        r = subprocess.run(['java', '-cp', str(self.classes), 'dev.peanuts.mods.ModImporterTest',
                             str(staging2), str(self.mods_root), self.title, 'Dupe'],
                            check=True, capture_output=True, text=True)
        self.assertEqual(r.stdout.strip(), 'ok: Dupe (2) files')


def displayNameNotUsed():
    return 'ignored'


if __name__ == '__main__':
    unittest.main()
