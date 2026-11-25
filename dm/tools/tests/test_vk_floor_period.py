"""vka_floor_period_ns() honors active FPS caps while preserving uncapped recovery."""  # PEANUTS: the frame cap in the GPU clock floor, #220 (0.1.8)
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class FloorPeriod(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_floor_period_respects_active_caps(self):
        dev_src = (ROOT / 'dm/gfx/vk_android/vka_dev.c').read_text()
        start = dev_src.index('_Atomic int64_t vka_period_min_ns;')
        end = dev_src.index('void vka_floor_span(', start)
        dev_block = dev_src[start:end]

        fps_src = (ROOT / 'dm/host/hle_fpslock.c').read_text()
        fstart = fps_src.index('int dm_fps_cap_get(void) {')
        fend = fps_src.index('\n// Whether the game\'s speed', fstart)
        fps_block = fps_src[fstart:fend]

        harness = r'''
#include <assert.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>

static double g_env_period;
static double VKA_ENVF(const char *k, double d) { (void)k; return g_env_period > 0 ? g_env_period : d; }

static int g_lim, g_elapsed, g_tgt, g_lock_eng, g_lock_floor;
static uint8_t g_state_buf[64];
uint8_t *dm_fps_state = NULL;
int nxhost_fps_limit_get(void) __attribute__((weak));
int dm_fps_cap_get(void) __attribute__((weak));
int nxhost_fps_limit_get(void) { return g_lim; }
static int dm_fps_elapsed_step(void) { return g_elapsed; }
int dm_fps_target(void) { return g_tgt; }
int dm_fps_lock_engine(void) { return g_lock_eng; }
int dm_fps_lock_floor(void) { return g_lock_floor; }

''' + dev_block + '\n' + fps_block + r'''

int main(void) {
    // 1. Uncapped (lim=0, tgt=0): stays 16.7 ms even if vka_period_min_ns is 33.3 ms.
    g_env_period = 0; g_lim = 0; g_elapsed = 0; g_tgt = 0; g_lock_eng = 0; g_lock_floor = 0; dm_fps_state = NULL;
    atomic_store(&vka_period_min_ns, 0);
    assert(vka_floor_period_ns() == 16666667);
    atomic_store(&vka_period_min_ns, 33333333);
    assert(vka_floor_period_ns() == 16666667);

    // 2. Uncapped 120 Hz game (vka_period_min_ns = 8.33 ms): keeps shorter period.
    atomic_store(&vka_period_min_ns, 8333333);
    assert(vka_floor_period_ns() == 8333333);

    // 3. Elapsed-step game (TotK): DM_FPS_LIMIT=30 overrides fps_target=60.
    atomic_store(&vka_period_min_ns, 16666667);
    g_elapsed = 1; dm_fps_state = g_state_buf; g_tgt = 60; g_lim = 30;
    assert(vka_floor_period_ns() == 1000000000ll / 30);

    // 4. Elapsed-step game at 40 / 45 / 60 / 120 fps.
    g_lim = 40; assert(vka_floor_period_ns() == 1000000000ll / 40);
    g_lim = 45; assert(vka_floor_period_ns() == 1000000000ll / 45);
    g_lim = 60; assert(vka_floor_period_ns() == 16666667);
    g_lim = 120; atomic_store(&vka_period_min_ns, 8333333);
    assert(vka_floor_period_ns() == 8333333);

    // 5. Elapsed-step game with no limit (lim=0) falls back to fps_target=30.
    atomic_store(&vka_period_min_ns, 16666667);
    g_lim = 0; g_tgt = 30;
    assert(vka_floor_period_ns() == 1000000000ll / 30);

    // 6. Non-elapsed frame-step or engine-locked game: lower of lim and tgt wins.
    g_elapsed = 0; g_lim = 60; g_tgt = 30;
    assert(vka_floor_period_ns() == 1000000000ll / 30);
    g_tgt = 45; // ignored by whole-vsync dm_fps_state when lock_eng=0 and lock_floor=0
    assert(vka_floor_period_ns() == 16666667);
    dm_fps_state = NULL; g_lock_eng = 1; g_lim = 0; g_tgt = 45;
    assert(vka_floor_period_ns() == 1000000000ll / 45);
    g_lock_eng = 0; g_lock_floor = 1; g_tgt = 30;
    assert(vka_floor_period_ns() == 1000000000ll / 30);

    // 7. Unapplied fps_target (no frame step, no engine limiter, no floor) is ignored.
    g_lock_floor = 0; g_tgt = 30; g_lim = 0;
    assert(vka_floor_period_ns() == 16666667);

    // 8. Explicit DM_VK_GPU_FLOOR_PERIOD overrides everything.
    g_env_period = 20.0; g_lim = 30; g_lock_eng = 1;
    assert(vka_floor_period_ns() == 20000000);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as td:
            src, exe = Path(td) / 'floor.c', Path(td) / 'floor'
            src.write_text(harness)
            # GCC -Werror=address warns on &weak_fn when defined in the same TU.
            built = subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-address', str(src), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stderr)


if __name__ == '__main__':
    unittest.main()
