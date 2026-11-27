"""The profile key place_join_mb (dm/games/README.md): the largest run of a memory pool's placed parts the Android replay joins into one, as the default of
DM_VK_PLACE_JOIN_MB (dm/gfx/vk_android/vka_mem.c join_cap). NieR:Automata's two profiles set 512 (GitHub issues #394, #651: parts of 288 to 368 MiB were not
joined past the 256 MiB default and their draws skipped); every other profile leaves the host's 256. The player's own DM_VK_PLACE_JOIN_MB line (env.txt,
Extra environment: the process environment the switch reads) still wins.

Checked here: game.py's range check and macro, the macro for every real profile, the generic host's runtime profile field (gen_runtime_profile.py), and
join_cap itself, cut from vka_mem.c and compiled with a getenv-backed VKA_ENV."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GAME = ROOT / 'dm/games/game.py'
GEN = ROOT / 'dm/peanuts/profile/gen_runtime_profile.py'
MEM = ROOT / 'dm/gfx/vk_android/vka_mem.c'
NIER = ('010056B015FE8000', '0100B8E016F76000')
ID = '0100000000F0F000'
TSV = ('title_id\t' + ID + '\nname\tTest\nprogram_id\t' + ID + '\nmodules\tmain\nromfs_marker\tromfs.img\n'
       'mem_reserve\t0x100000\nmem_alias\t0x100000\nmem_stack\t0x100000\napp_pool\t0x100000\n')


def header(rows=None, title=ID):
    """(exit code, output, the DM_GAME_PLACE_JOIN_MB line) of game.py header: a synthetic profile with these rows, or a real one (rows None)"""
    with tempfile.TemporaryDirectory() as d:
        env = dict(os.environ)
        if rows is not None:
            Path(d, title).mkdir()
            (Path(d, title) / 'game.tsv').write_text(TSV + rows)
            env['DM_GAMES_DIR'] = d
        out = Path(d, 'game.h')
        r = subprocess.run([sys.executable, str(GAME), 'header', title, str(out)], env=env, capture_output=True, text=True)
        lines = [ln for ln in out.read_text().splitlines() if 'PLACE_JOIN' in ln] if out.exists() else []
    return r.returncode, (r.stderr + r.stdout).strip(), lines


class GameKey(unittest.TestCase):
    def test_unset_is_zero(self):
        self.assertEqual(header('')[::2], (0, ['#define DM_GAME_PLACE_JOIN_MB 0']))

    def test_valid(self):
        for v in ('0', '64', '512', '2048'):
            self.assertEqual(header(f'place_join_mb\t{v}\n')[::2], (0, [f'#define DM_GAME_PLACE_JOIN_MB {v}']), v)

    def test_rejected(self):
        for v in ('32', '63', '2049', '-1', '0x200', '512 MB'):
            rc, out, _ = header(f'place_join_mb\t{v}\n')
            self.assertNotEqual(rc, 0, v)
            self.assertIn('place_join_mb must be 0 (not set) or 64 to 2048 MiB', out, v)

    def test_real_profiles(self):   # 512 for both NieR:Automata releases, nothing (0, the host's 256) for every other game
        titles = sorted(p.name for p in (ROOT / 'dm/games').iterdir() if re.fullmatch(r'[0-9A-F]{16}', p.name) and (p / 'game.tsv').exists())
        self.assertGreater(len(titles), 100)
        for t in titles:
            rc, out, lines = header(None, t)
            self.assertEqual(rc, 0, f'{t}: {out}')
            self.assertEqual(lines, [f'#define DM_GAME_PLACE_JOIN_MB {512 if t in NIER else 0}'], t)


READ = r'''
#include "profile_rt.h"
#include <stdio.h>
int main(int argc, char **argv) {   // the field at its offset in the host's own struct, from a profile.bin the generator wrote
    static unsigned char b[sizeof(dm_game_profile_t)]; FILE *f = fopen(argv[1], "rb");
    if (!f || fread(b, 1, sizeof b, f) != sizeof b) return 2;
    printf("%u\n", ((const dm_game_profile_t *)b)->place_join_mb); return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class RuntimeProfile(unittest.TestCase):   # the generic host's profile.bin carries the value (gen_runtime_profile.py; all fields: check-runtime-profile.sh)
    def test_value(self):
        with tempfile.TemporaryDirectory() as d:
            c = Path(d, 'read.c'); c.write_text(READ)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-I', str(GEN.parent), '-o', str(Path(d, 'read')), str(c)], check=True)
            for title, want in ((NIER[0], 512), (NIER[1], 512), ('01007EF00011E000', 0)):   # (Breath of the Wild: no row)
                out = Path(d, title + '.bin')
                subprocess.run([sys.executable, str(GEN), 'profile', title, str(out)], check=True, capture_output=True)
                got = subprocess.run([str(Path(d, 'read')), str(out)], check=True, capture_output=True, text=True).stdout
                self.assertEqual(int(got), want, title)


PRELUDE = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#define VKA_ENV(k, def) (getenv(k) && *getenv(k) ? atoi(getenv(k)) : (def))
#define VLOG(...) ((void)0)
'''
MAIN = r'''
int main(void) { printf("%llu\n", (unsigned long long)(join_cap() >> 20)); return 0; }
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class JoinCap(unittest.TestCase):   # vka_mem.c join_cap as it is in the production file: the profile's value is the default, the player's line wins
    @classmethod
    def setUpClass(cls):
        src = MEM.read_text()
        m = re.search(r'^static uint64_t join_cap\(void\) \{.*$', src, re.M)
        assert m, 'join_cap not found in vka_mem.c'
        cls.fn = m[0]
        cls.tmp = tempfile.TemporaryDirectory()
        cls.bins = {}
        for prof in (0, 512):
            c = Path(cls.tmp.name, f'j{prof}.c')
            c.write_text(PRELUDE + f'#define DM_GAME_PLACE_JOIN_MB {prof}\n' + cls.fn + '\n' + MAIN)
            b = Path(cls.tmp.name, f'j{prof}')
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-o', str(b), str(c)], check=True)
            cls.bins[prof] = b

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def cap(self, prof, player=None):
        env = {k: v for k, v in os.environ.items() if k != 'DM_VK_PLACE_JOIN_MB'}
        if player is not None: env['DM_VK_PLACE_JOIN_MB'] = player
        return int(subprocess.run([str(self.bins[prof])], env=env, capture_output=True, text=True, check=True).stdout)

    def test_default(self):
        self.assertEqual(self.cap(0), 256)   # no row: the host's 256, as before

    def test_profile(self):
        self.assertEqual(self.cap(512), 512)   # NieR:Automata

    def test_player_wins(self):
        self.assertEqual(self.cap(512, '256'), 256)
        self.assertEqual(self.cap(512, '1024'), 1024)
        self.assertEqual(self.cap(512, '0'), 0)   # joins off
        self.assertEqual(self.cap(0, '512'), 512)   # the reporter's Extra environment line on a profile without the row
        self.assertEqual(self.cap(512, ''), 512)   # an empty value is unset


if __name__ == '__main__':
    unittest.main()
