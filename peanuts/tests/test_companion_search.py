"""Run the host's real companion search against normal and code-mod pack layouts."""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TITLE = '0100000011D90000'


@unittest.skipUnless(shutil.which('cc'), 'no C compiler')
class CompanionSearch(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='companion-search-')
        cls.root = Path(cls.temp.name)
        src = (ROOT / 'dm/peanuts/peanuts.c').read_text()
        # Compile the production functions unchanged; no Android or game process is needed for a directory search.
        begin = src.index('static int rt_pkgdir_suffix(')
        end = src.index('// ---- the game\'s threads stopped', begin)
        harness = cls.root / 'search.c'
        harness.write_text('''#define _POSIX_C_SOURCE 200809L
#include <dirent.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define __android_log_print(...) ((void)0)
static const char *pack;
static const char *dm_module_dir(void) { return pack; }
static uint64_t rt_running_title(void) { return 0x0100000011D90000ull; }
''' + src[begin:end] + '''
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    pack = argv[1]; setenv("DM_ROMFS", argv[2], 1);
    char dir[768]; uint64_t title = 0;
    if (!rt_find_package(dir, sizeof dir, &title)) return 1;
    puts(dir); return 0;
}
''')
        cls.exe = cls.root / 'search'
        subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', str(harness), '-o', str(cls.exe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(dir=self.root))
        self.home = self.case / 'builds' / TITLE
        self.game = str(self.home / 'game.nup') + ':update/393216/romfs'

    def package(self, root, name='Lumi', title=TITLE):
        folder = root / name
        (folder / 'dualscreen').mkdir(parents=True)
        (folder / 'dualscreen/manifest.json').write_text(json.dumps({'title_id': title}))
        return folder

    def search(self, pack, expected, romfs=None):
        pack.mkdir(parents=True, exist_ok=True)
        r = subprocess.run([str(self.exe), str(pack), romfs or self.game], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0 if expected else 1, r.stderr)
        if expected:
            self.assertEqual(Path(r.stdout.strip()).resolve(), expected.resolve())

    def test_build_folder_normal_pack(self):
        self.search(self.home / 'pack', self.package(self.home / 'peanuts'))

    def test_build_folder_code_mod_pack(self):
        self.search(self.home / 'mod-86c06ecc5c84/pack', self.package(self.home / 'peanuts'))

    def test_app_owned_code_mod_pack(self):
        packs = self.case / 'app/packs'
        self.search(packs / (TITLE + '-mod-86c06ecc5c84'), self.package(packs / 'peanuts'))

    def test_dump_adjacent_fallback(self):
        self.search(self.home / 'mod-86c06ecc5c84/pack', self.package(self.home / (TITLE + '-work') / 'peanuts'))

    def test_other_title_and_unfinished_install_do_not_load(self):
        root = self.home / 'peanuts'
        self.package(root, 'Other', '01006BB00C6F0000')
        self.package(root, 'Lumi.new')
        self.search(self.home / 'mod-86c06ecc5c84/pack', None)

    def test_interrupted_swap_falls_back_to_old(self):
        old = self.package(self.home / 'peanuts', 'Lumi.old')
        self.search(self.home / 'mod-86c06ecc5c84/pack', old)

    def test_active_package_wins_over_parked_old(self):
        self.package(self.home / 'peanuts', 'Lumi.old')
        active = self.package(self.home / 'peanuts')
        self.search(self.home / 'mod-86c06ecc5c84/pack', active)

    def test_unrecognized_directory_does_not_search_ancestor(self):
        self.package(self.home / 'peanuts')
        self.search(self.home / 'unrelated/pack', None)


if __name__ == '__main__':
    unittest.main()
