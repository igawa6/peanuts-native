"""PEANUTS: tex_pool_range (hle_nvn.c texpool_range) on the production descriptor pool code, without NVN or a GPU.

Compiles hle_nvn.c from its descriptor pool table up to nvnSamplerPoolInitialize with a stub prelude and runs scenarios:
without the key registrations take the descriptor pools' host ID (base + ID; nvn_dpool.h's small pools skip their reserved IDs) and no
line is added (PEANUTS: the small pools' table); with it each pool's IDs are limited to its own range on top of that host ID,
also in a reused finalized range; the frame replayer's pools (nvn_dpool_base_set) are not limited; DM_NVN_TEXPOOL_RANGE=0
turns the profile's value off.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'dm/gfx/nvn/hle_nvn.c'
PRELUDE = r'''
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define LOG(...) (printf("LOG "), printf(__VA_ARGS__), printf("\n"))
#define OLOG(...) ((void)0)
#define NVN(name, ret, ...) ret h_##name(__VA_ARGS__)
typedef int NVNboolean; typedef struct { char b[64]; } NVNtexturePool, NVNsamplerPool, NVNmemoryPool, NVNtexture, NVNtextureView;
typedef struct { char *mem; } pool_t;   // PEANUTS: hle_nvn.c dpool_store reads the memory pool's memory
typedef struct { int x; } tex_t, view_t;
typedef struct { const pool_t *pool; int64_t off; int32_t n; } dpool_t;
static void mtl_set_tex_pool_n(int n) { (void)n; }
static void mtl_reg_tex(int id, const tex_t *t, const view_t *v) { (void)t; (void)v; printf("REG %d\n", id); }
static void mtl_reg_image(int id, const tex_t *t, const view_t *v) { (void)t; (void)v; printf("IMG %d\n", id); }
#include "nvn_dpool.h"
'''
MAIN = r'''
int main(int argc, char **argv) {
    static NVNtexturePool tp[4], rp; NVNtexture t;
    const char *what = argc > 1 ? argv[1] : "";
    if (!strcmp(what, "pools")) {   // three pools of 2^20 entries, registrations into each, then a reused range for a small pool
        for (int i = 0; i < 3; i++) h_nvnTexturePoolInitialize(&tp[i], NULL, 0, 1 << 20);
        for (int k = 0; k < 70; k++) for (int i = 0; i < 3; i++) h_nvnTexturePoolRegisterTexture(&tp[i], 256 + k, &t, NULL);
        h_nvnTexturePoolRegisterTexture(&tp[1], 20000, &t, NULL);
        h_nvnTexturePoolRegisterImage(&tp[2], 300, &t, NULL);
        h_nvnTexturePoolFinalize(&tp[1]);
        h_nvnTexturePoolInitialize(&tp[3], NULL, 0, 300);   // takes pool #2's finalized range
        h_nvnTexturePoolRegisterTexture(&tp[3], 299, &t, NULL);
        h_nvnTexturePoolRegisterTexture(&tp[3], 600, &t, NULL);
    } else if (!strcmp(what, "replay")) {   // the frame replayer's pool: a base from the capture, no Initialize
        nvn_dpool_base_set(&rp, 70000, 0);
        h_nvnTexturePoolRegisterTexture(&rp, 123, &t, NULL);
    }
    return 0;
}
'''


class TexPoolRange(unittest.TestCase):
    exe = {}

    @classmethod
    def setUpClass(cls):
        cc = shutil.which('cc')
        if not cc:
            raise unittest.SkipTest('needs a C compiler')
        src = SOURCE.read_text()
        body = src[src.index('static nvn_dpool_tab g_tpt'):src.index('NVN(nvnSamplerPoolInitialize')]
        cls.tmp = tempfile.TemporaryDirectory()
        path = Path(cls.tmp.name)
        (path / 'texpool.c').write_text(PRELUDE + body + MAIN)
        for name, profile in (('none', 0), ('profile', 16384)):
            out = path / name
            build = subprocess.run([cc, '-std=c11', '-O1', '-Wall', '-Wno-unused-function', '-fsanitize=undefined', '-fno-sanitize-recover=all', '-pthread',
                                    f'-DDM_GAME_TEX_POOL_RANGE={profile}', '-I', str(SOURCE.parent), str(path / 'texpool.c'), '-o', str(out)],
                                   capture_output=True, text=True)
            assert build.returncode == 0, build.stderr
            cls.exe[name] = out

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_case(self, exe, what, env=None):
        e = {k: v for k, v in os.environ.items() if k != 'DM_NVN_TEXPOOL_RANGE'}
        e.update(env or {})
        r = subprocess.run([str(self.exe[exe]), what], capture_output=True, text=True, env=e)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.splitlines()

    def test_without_key_base_plus_id_and_no_new_lines(self):
        out = self.run_case('none', 'pools')
        logs = [l for l in out if l.startswith('LOG ')]
        # only the lines the pool table already gave before tex_pool_range (dpool_add: a later pool's numbering)
        self.assertEqual(logs, ['LOG [nvn] pool #2 (texture, 1048576 entries): numbered from 1048576 in the host table, separate from the earlier pools',
                                'LOG [nvn] pool #3 (texture, 1048576 entries): numbered from 2097152 in the host table, separate from the earlier pools'])
        self.assertIn('REG 1068576', out)          # pool #2's 20000: base + ID, kept
        self.assertIn('IMG 2097452', out)          # pool #3's image 300
        # PEANUTS: the reused range: base + ID for 299 and 600, not limited. A 300-entry pool is small (nvn_dpool.h): its base is the range's start - 256
        self.assertEqual(out[-2:], ['REG %d' % (1048576 - 256 + 299), 'REG %d' % (1048576 - 256 + 600)])

    def test_env_zero_turns_profile_off(self):
        out = self.run_case('profile', 'pools', {'DM_NVN_TEXPOOL_RANGE': '0'})
        self.assertIn('LOG [nvn] texture pool range: off, 16384 IDs of the host table at most for each texture pool (DM_NVN_TEXPOOL_RANGE)', out)
        self.assertIn('REG 1068576', out)
        self.assertFalse([l for l in out if 'RED' in l])

    def test_profile_range_limits_each_pool(self):
        out = self.run_case('profile', 'pools')
        self.assertEqual(out[0], 'LOG [nvn] texture pool range: 16384 IDs of the host table at most for each texture pool (game profile tex_pool_range)')
        self.assertIn('LOG [nvn] texture pool #2: 1048576 entries, IDs 0-16383 from host ID 16384 (tex_pool_range)', out)
        self.assertIn('REG %d' % (16384 + 256), out)
        self.assertIn('IMG %d' % (32768 + 300), out)
        self.assertNotIn('REG %d' % (16384 + 20000), out)
        self.assertIn('LOG [RED] texture pool #2: ID 20000 is past its host range of 16384 (tex_pool_range / DM_NVN_TEXPOOL_RANGE): the registration is dropped', out)
        self.assertIn('LOG [nvn] texture pool #1 (host base 0, range 16384): 64 registrations, highest ID 319', out)

    def test_reused_range_takes_the_new_pools_limit(self):
        out = self.run_case('profile', 'pools')
        self.assertIn('LOG [nvn] texture pool #2: 300 entries, IDs 0-511 from host ID 16128 (tex_pool_range)', out)   # PEANUTS: a small pool, base = range start - 256
        self.assertEqual(out[-2:], ['REG %d' % (16384 - 256 + 299),
                                    'LOG [RED] texture pool #2: ID 600 is past its host range of 512 (tex_pool_range / DM_NVN_TEXPOOL_RANGE): the registration is dropped'])
        self.assertNotIn('REG %d' % (16384 - 256 + 600), out)   # PEANUTS

    def test_replayer_pool_not_limited(self):
        for exe in ('none', 'profile'):
            out = self.run_case(exe, 'replay')
            self.assertIn('REG 70123', out)
            self.assertFalse([l for l in out if 'RED' in l])


if __name__ == '__main__':
    unittest.main()
