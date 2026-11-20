"""PEANUTS(upstream-candidate): the level a mip-chain pass samples from its own target on the Android replay (dm/gfx/vk_android/vka_tex.c fb_samp_pin and
fb_view_levels, run as they are in the production file, without a Vulkan device). A sampler that pins one level (a mip filter, minimum and maximum LOD the same whole
number) samples that level alone; any other sampler keeps the view of the levels below the attachment. GitHub issue #170 (Breath of the Wild's Sheikah glow), reference:
"Upsample pass reading the level above the one it renders" in docs/reference/rendering.md."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'dm/gfx/vk_android/vka_tex.c'
DRAW = REPO / 'dm/gfx/vk_android/vka_draw.c'
PRELUDE = r'''
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include "nvn_obj.h"
'''
MAIN = r'''
// The level Vulkan reads through a view of levels l0+nl for a sampler whose LOD clamp is lod..lod: the LOD counts from the view's base and ends at its last level
static uint32_t fetched(uint32_t l0, uint32_t nl, int lod) { return l0 + ((uint32_t)lod < nl ? (uint32_t)lod : nl - 1); }
static samp_par samp(int minf, float lo, float hi) { samp_par p = {0}; p.minf = (uint8_t)minf; p.lod_min = lo; p.lod_max = hi; return p; }
static int pin_of(int minf, float lo, float hi) { samp_par p = samp(minf, lo, hi); return fb_samp_pin(&p); }
#define LML NVN_MIN_FILTER_LINEAR_MIPMAP_LINEAR
#define NMN NVN_MIN_FILTER_NEAREST_MIPMAP_NEAREST

int main(void) {
    uint32_t l0, nl;
    // Which samplers pin a level
    assert(pin_of(LML, 3, 3) == 3 && pin_of(NMN, 0, 0) == 0 && pin_of(NVN_MIN_FILTER_LINEAR_MIPMAP_NEAREST, 15, 15) == 15);
    assert(pin_of(NVN_MIN_FILTER_LINEAR, 3, 3) == -1 && pin_of(NVN_MIN_FILTER_NEAREST, 0, 0) == -1);   // no mip filter: no level is chosen by the clamp
    assert(pin_of(LML, 2, 3) == -1 && pin_of(LML, 0, 1000) == -1);   // a range (an explicit level in the shader picks within it)
    assert(pin_of(LML, 2.5f, 2.5f) == -1 && pin_of(LML, -1, -1) == -1 && pin_of(LML, 16, 16) == -1 && pin_of(LML, NAN, NAN) == -1);

    // An upsample pass (Breath of the Wild's glow, TOTK's 327ae8bbb37f7e51, DKB's ad1a5991aaad135e): renders level 2 of a 5-level chain, its sampler pins level 3.
    // The view of the levels below (0+2) read level 1, a sharper one; the view of level 3 alone reads level 3, as the uncut view would, without the attached level
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 2, pin_of(LML, 3, 3)) == 1 && l0 == 3 && nl == 1 && fetched(l0, nl, 3) == 3);
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 2, -1) == 2 && l0 == 0 && nl == 2 && fetched(l0, nl, 3) == 1);   // the rule off (DM_VK_FB_PIN=0): the old cut, the wrong level
    // Its last step renders level 0 from level 1: the base level was left as is, attached level in the view; now level 1 alone
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 0, 1) == 1 && l0 == 1 && nl == 1);

    // Donkey Kong Bananza's downsample (ef18c6eca9b2f991, lod n - 1..n - 1): renders level 3 from level 2. The same level as the view of the levels below, attachment out
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 3, 2) == 1 && l0 == 2 && nl == 1 && fetched(l0, nl, 2) == fetched(0, 3, 2));
    // DKB's environment cube (d1a506c1a94ffdfa, 8 levels, an explicit level with a linear mip filter): no pinned level, the levels below, as before
    l0 = 0; nl = 8;
    assert(fb_view_levels(&l0, &nl, 5, pin_of(LML, 0, 1000)) == 2 && l0 == 0 && nl == 5);
    // A sampler pinned to the attached level itself: not a level to read alone (it is the one being drawn): the levels below, or as is at the base
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 2, 2) == 2 && l0 == 0 && nl == 2);
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 0, 0) == 0 && l0 == 0 && nl == 5);
    // A pinned level past the view's levels: the sampler's clamp ends at the view's last level, which is read alone
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 1, 7) == 1 && l0 == 4 && nl == 1);
    l0 = 0; nl = 5;
    assert(fb_view_levels(&l0, &nl, 4, 9) == 2 && l0 == 0 && nl == 4);   // ... unless that is the attached one
    // The pinned level counts from the view's base (a texture view, a level child's lvk)
    l0 = 2; nl = 4;
    assert(fb_view_levels(&l0, &nl, 3, 2) == 1 && l0 == 4 && nl == 1);
    // Views that do not include the attached level, single-level views, and a view starting at it with no pin: left as they are
    l0 = 0; nl = 3; assert(fb_view_levels(&l0, &nl, 4, 1) == 0 && l0 == 0 && nl == 3);
    l0 = 2; nl = 3; assert(fb_view_levels(&l0, &nl, 1, 0) == 0 && l0 == 2 && nl == 3);
    l0 = 2; nl = 1; assert(fb_view_levels(&l0, &nl, 2, 0) == 0 && l0 == 2 && nl == 1);
    l0 = 1; nl = 4; assert(fb_view_levels(&l0, &nl, 1, -1) == 0 && l0 == 1 && nl == 4);

    // Every case of small views: a changed view leaves the attached level out and stays inside the old one; a pinned view reads the level the uncut view read;
    // a cut view is the levels below the attachment
    for (uint32_t b = 0; b < 4; b++) for (uint32_t n = 1; n < 7; n++) for (uint32_t att = 0; att < 10; att++) for (int pin = -1; pin < 9; pin++) {
        l0 = b; nl = n;
        int how = fb_view_levels(&l0, &nl, att, pin);
        int has = att >= b && att < b + n;
        if (!how) { assert(l0 == b && nl == n); assert(!has || n == 1 || (att == b && (pin < 0 || fetched(b, n, pin) == att))); continue; }
        assert(has && n > 1 && nl >= 1 && l0 >= b && l0 + nl <= b + n && !(att >= l0 && att < l0 + nl));
        if (how == 1) assert(pin >= 0 && nl == 1 && l0 == fetched(b, n, pin));
        else assert((pin < 0 || fetched(b, n, pin) == att) && l0 == b && nl == att - b);
    }
    printf("ok\n");
    return 0;
}
'''


def production_functions(source):
    """fb_samp_pin and fb_view_levels as the production file has them"""
    start = source.index('static int fb_samp_pin(const samp_par *p)')
    end = source.index('static _Thread_local int t_view_sid;')
    return source[start:end]


class PinnedLevel(unittest.TestCase):
    def test_pinned_level_alone_else_levels_below(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('needs a C compiler')
        functions = production_functions(SOURCE.read_text())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / 'pinned.c').write_text(PRELUDE + functions + MAIN)
            build = subprocess.run([cc, '-std=c11', '-O1', '-Wall', '-Werror', '-fsanitize=undefined', '-fno-sanitize-recover=all',
                                    '-I', str(REPO / 'dm/gfx/nvn'), '-I', str(REPO / 'dm/gfx/nvn/include'),
                                    str(path / 'pinned.c'), '-o', str(path / 'pinned'), '-lm'],
                                   capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(path / 'pinned')], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_the_draw_passes_its_sampler(self):
        # view_of cuts with fb_view_levels and the binding's sampler; a sampled texture binding hands its sampler ID over, a storage image none
        source = SOURCE.read_text()
        view_of = source[source.index('static VkImageView view_of('):source.index('int tgt = v && v->set_target')]
        self.assertIn('fb_view_levels(&l0, &nl, g_fb_lv[i], pin)', view_of)
        self.assertIn('fb_pin_of(t_view_sid)', view_of)
        self.assertRegex(DRAW.read_text(), re.compile(r'vka_tex_id_view_samp_rec\(tid, storage \? 0 : r\[i\]\.sid,'))


if __name__ == '__main__':
    unittest.main()
