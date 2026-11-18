# PEANUTS: exercise the production content-rate hint without Android.
"""A capped game's content-rate hint can differ from its presentation tick."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class FrameRateVote(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_cap_vote_preserves_generated_output(self):
        source = (ROOT / 'dm/gfx/vk_android/vka_present.c').read_text()
        start = source.index('    // PEANUTS begin (upstream-candidate): the content-rate hint follows')
        block = source[start:source.index('    // PEANUTS end', start)]
        preamble = r'''
#include <assert.h>
#include <math.h>
static int g_fg_on, cap, queries, polls, votes;
static double voted;
int nxhost_fps_limit_get(void);
static void frame_rate(double period) { voted = period; votes++; }
static void refresh_poll(void) { polls++; }
static void vote(double period) {
    struct { double period; } value = {period};
    const typeof(value) *it = &value;
'''
        cases = r'''
}
int nxhost_fps_limit_get(void) { queries++; return cap; }
static void check(int generated, int limit, double period, double expected) {
    g_fg_on = generated; cap = limit;
    queries = polls = votes = 0; voted = 0;
    vote(period);
    assert(fabs(voted - expected) < 1e-12 && polls == 1 && votes == 1);
    assert(queries == (!generated));
}
int main(void) {
    check(0, 60, 1. / 120, 1. / 60);
    check(0, 30, 1. / 120, 1. / 30);
    check(0, 120, 1. / 120, 1. / 120);
    check(0, 30, 1. / 20, 1. / 20);  // never vote faster than content
    check(0, 0, 1. / 120, 1. / 120); // no cap / Game owned
    check(0, -1, 1. / 60, 1. / 60);
    check(1, 30, 1. / 120, 1. / 120); // FG output is 120 despite 30 base
    check(1, 30, 1. / 60, 1. / 60);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as td:
            src, exe = Path(td) / 'vote.c', Path(td) / 'vote'
            src.write_text(preamble + block + cases)
            built = subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', str(src), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            ran = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(ran.returncode, 0, ran.stderr)


if __name__ == '__main__':
    unittest.main()
