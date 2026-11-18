"""Run the production texture registration path without a Vulkan device."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'dm/gfx/vk_android/vka_tex.c'
PRELUDE = r'''
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define NVN_TEXTURE_TARGET_BUFFER 11
#define VLOG(...) ((void)0)
typedef struct { uint64_t serial; void *pool; struct { int target; } d; } tex_t;
typedef struct { int set_target, target; } view_t;
typedef struct { tex_t t; view_t v; uint8_t has_v, img; uint64_t pgen; } treg_ent;
static pthread_mutex_t g_tmu = PTHREAD_MUTEX_INITIALIZER;
static treg_ent *g_treg;
static int g_ntreg, *g_tdirty, g_ntd, g_tdcap;
static uint8_t *g_tdflag;
static _Atomic uint64_t *g_tver;
static _Atomic uint8_t *g_tisbuf;
static uint64_t nvn_pool_live(void *pool, void *unused, void *also_unused) {
    (void)pool; (void)unused; (void)also_unused; return 7;
}
'''
MAIN = r'''
int main(void) {
    const int id = 1031;
    tex_t texture = {.serial = 42, .d.target = NVN_TEXTURE_TARGET_BUFFER};
    view_t image_view = {.set_target = 1, .target = 2};
    view_t buffer_view = {.set_target = 1, .target = NVN_TEXTURE_TARGET_BUFFER};
    treg(id, &texture, NULL, 0);
    assert(vka_tex_id_is_buffer(id));
    assert(g_treg[id].t.serial == 42 && g_treg[id].pgen == 7);
    assert(atomic_load(&g_tver[id]) == 1 && g_ntd == 1);
    treg(id, NULL, NULL, 0);
    assert(!vka_tex_id_is_buffer(id));
    treg_ent cleared = {0};
    assert(memcmp(&g_treg[id], &cleared, sizeof cleared) == 0);
    assert(atomic_load(&g_tver[id]) == 2 && g_ntd == 1 && g_tdflag[id]);
    // A view overrides a live texture's target, but cannot make a NULL registration live.
    treg(id, &texture, &image_view, 1);
    assert(!vka_tex_id_is_buffer(id) && g_treg[id].img && g_treg[id].has_v);
    texture.d.target = 2;
    treg(id, &texture, &buffer_view, 0);
    assert(vka_tex_id_is_buffer(id));
    treg(id, NULL, &buffer_view, 1);
    assert(!vka_tex_id_is_buffer(id));
    assert(memcmp(&g_treg[id], &cleared, sizeof cleared) == 0);
    assert(atomic_load(&g_tver[id]) == 5);
    // Clearing a never-registered ID, and rejecting IDs outside the table.
    treg(id + 1, NULL, NULL, 1);
    assert(!vka_tex_id_is_buffer(id + 1) && g_ntd == 2);
    treg(-1, NULL, NULL, 0);
    treg(1 << 20, NULL, NULL, 0);
    assert(g_ntd == 2);
    free(g_treg); free(g_tdirty); free(g_tdflag); free(g_tver); free(g_tisbuf);
    return 0;
}
'''


class TextureRegistration(unittest.TestCase):
    def test_register_clear_and_view_targets(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('needs a C compiler')
        source = SOURCE.read_text()
        # Compile the actual registration and dirty-list functions, not a test copy.
        functions = source[source.rindex('static void dirty(int **list,'):source.index('void mtl_reg_tex(int id,')]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / 'registration.c').write_text(PRELUDE + functions + MAIN)
            build = subprocess.run([cc, '-std=c11', '-O1', '-fsanitize=undefined', '-fno-sanitize-recover=all',
                                    '-pthread', str(path / 'registration.c'), '-o', str(path / 'registration')],
                                   capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(path / 'registration')], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == '__main__':
    unittest.main()
