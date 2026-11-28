"""Unit tests for NVN virtual-pool buffer helpers:
- nvn_sparse_runs coalescing, holes, physical gaps, 2 MiB leaf boundaries, min(len, size) clipping, max cap, lock-free empty check
- vkp_merge_chunks linear merge sorted-and-disjoint invariant
- vkp_rjoin_plan & vkp_rjoin_neg refused cross-pool join caching keyed by segment generation
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HLE_NVN = ROOT / 'dm/gfx/nvn/hle_nvn.c'
VKA_PLACE = ROOT / 'dm/gfx/vk_android/vka_place.h'

SPARSE_HARNESS_HEAD = r'''
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { NVRUN = 1024, VPT_LEAF = 512 };
typedef struct { const uint8_t *phys; uint64_t gen; } vpte;
struct vpool { const void *pool; uint8_t *base; uint64_t len, size, top; int nrun; struct { uint64_t lo, hi; } run[NVRUN]; vpte **pt; };
static struct vpool **g_vp;
static int g_nvp;
static pthread_mutex_t g_vp_mu = PTHREAD_MUTEX_INITIALIZER;
static _Atomic int g_vp_count;

static struct vpool *vpool_at(const void *pool, uint64_t a) {
    (void)pool;
    for (int i = 0; i < g_nvp; i++) {
        struct vpool *v = g_vp[i];
        uint64_t base = (uint64_t)(uintptr_t)v->base;
        uint64_t cap = v->len < v->size ? v->len : v->size;
        if (a >= base && a < base + cap) return v;
    }
    return NULL;
}
static const vpte *vpt_get(const struct vpool *v, uint64_t off) {
    uint64_t l = off >> 21, p = (off >> 12) & (VPT_LEAF - 1);
    return v->pt && v->pt[l] ? &v->pt[l][p] : NULL;
}
'''

SPARSE_HARNESS_MAIN = r'''
static struct vpool make_pool(uint64_t base, uint64_t len, uint64_t size) {
    uint64_t nleaf = (size + (2u << 20) - 1) >> 21;
    struct vpool v = {0};
    v.base = (uint8_t *)(uintptr_t)base;
    v.len = len;
    v.size = size;
    v.pt = calloc((size_t)nleaf, sizeof(vpte *));
    assert(v.pt);
    return v;
}
static void map_pages(struct vpool *v, uint64_t voff, uint64_t phys, uint64_t npages) {
    for (uint64_t i = 0; i < npages; i++) {
        uint64_t off = voff + (i << 12);
        uint64_t l = off >> 21, p = (off >> 12) & (VPT_LEAF - 1);
        if (!v->pt[l]) { v->pt[l] = calloc(VPT_LEAF, sizeof(vpte)); assert(v->pt[l]); }
        v->pt[l][p].phys = (const uint8_t *)(uintptr_t)(phys + (i << 12));
    }
}
static void free_pool(struct vpool *v) {
    uint64_t nleaf = (v->size + (2u << 20) - 1) >> 21;
    for (uint64_t l = 0; l < nleaf; l++) free(v->pt[l]);
    free(v->pt);
}

int main(void) {
    uint64_t vlo[16], vhi[16], plo[16], phys = 0;

    // 1. Lock-free empty check: g_vp_count == 0 -> returns 0 without touching g_vp
    g_vp = NULL; g_nvp = 0; atomic_store(&g_vp_count, 0);
    assert(nvn_sparse_has() == 0);
    assert(nvn_sparse_runs(vlo, vhi, plo, 16) == 0);
    assert(nvn_sparse_run(0x10000000ull, 0x4000, &phys) == 0);

    // 2. Coalescing across 2 MiB leaf boundary, splitting on holes and physical gaps,
    //    and clipping to min(v->len, v->size) when len < size
    uint64_t base = 0x6200000000ull;
    struct vpool vp = make_pool(base, (4ull << 20) + 0x2000, 6ull << 20);
    // Run 0: [2 MiB - 8 KiB, 2 MiB + 12 KiB) -> contiguous across 2 MiB leaf boundary (5 pages)
    map_pages(&vp, (2ull << 20) - 0x2000, 0x4800000000ull, 5);
    // Hole at 2 MiB + 12 KiB (1 page), then Run 1: [2 MiB + 16 KiB, 2 MiB + 24 KiB) -> 2 pages
    map_pages(&vp, (2ull << 20) + 0x4000, 0x4800006000ull, 2);
    // Adjacent VA [2 MiB + 24 KiB, 2 MiB + 32 KiB) with physical gap -> Run 2
    map_pages(&vp, (2ull << 20) + 0x6000, 0x4900000000ull, 2);
    // Run 3 straddles v->len = 4 MiB + 8 KiB: map 4 pages starting at 4 MiB -> clipped to 2 pages
    map_pages(&vp, (4ull << 20), 0x4a00000000ull, 4);
    // Pages completely past v->len (at 5 MiB): must not appear
    map_pages(&vp, (5ull << 20), 0x4b00000000ull, 2);

    struct vpool *arr[1] = {&vp};
    g_vp = arr; g_nvp = 1; atomic_store(&g_vp_count, 1);
    assert(nvn_sparse_has() == 1);

    int n = nvn_sparse_runs(vlo, vhi, plo, 16);
    assert(n == 4);
    assert(vlo[0] == base + (2ull << 20) - 0x2000 && vhi[0] == base + (2ull << 20) + 0x3000 && plo[0] == 0x4800000000ull);
    assert(vlo[1] == base + (2ull << 20) + 0x4000 && vhi[1] == base + (2ull << 20) + 0x6000 && plo[1] == 0x4800006000ull);
    assert(vlo[2] == base + (2ull << 20) + 0x6000 && vhi[2] == base + (2ull << 20) + 0x8000 && plo[2] == 0x4900000000ull);
    assert(vlo[3] == base + (4ull << 20) && vhi[3] == base + (4ull << 20) + 0x2000 && plo[3] == 0x4a00000000ull);

    // 3. nvn_sparse_run also clips to min(v->len, v->size)
    uint64_t r = nvn_sparse_run(base + (4ull << 20), 0x10000, &phys);
    assert(r == 0x2000 && phys == 0x4a00000000ull);

    // 4. max cap honored strictly
    int ncap = nvn_sparse_runs(vlo, vhi, plo, 2);
    assert(ncap == 2);
    assert(vlo[0] == base + (2ull << 20) - 0x2000 && vlo[1] == base + (2ull << 20) + 0x4000);

    // 5. size < len also clips to size
    vp.len = 6ull << 20; vp.size = (2ull << 20) + 0x1000;
    int nsz = nvn_sparse_runs(vlo, vhi, plo, 16);
    assert(nsz == 1);
    assert(vlo[0] == base + (2ull << 20) - 0x2000 && vhi[0] == base + (2ull << 20) + 0x1000);

    free_pool(&vp);
    return 0;
}
'''

PLACE_HARNESS = r'''
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "vka_place.h"

typedef struct { uintptr_t lo, hi; int refs, fd_alias; } fake_seg_t;

int main(void) {
    // --- 1. vkp_merge_chunks: sorted & disjoint invariant ---
    uint64_t dlo[3] = {0x1000, 0x4000, 0x9000};
    uint64_t dhi[3] = {0x2000, 0x6000, 0xb000};
    uint64_t dbda[3] = {0x101000, 0x104000, 0x109000};
    uint64_t mlo[3] = {0x2000, 0x5000, 0xc000};
    uint64_t mhi[3] = {0x3000, 0x7000, 0xe000};
    uint64_t mbda[3] = {0x202000, 0x205000, 0x20c000};
    uint64_t olo[16], ohi[16], obda[16];
    int n = vkp_merge_chunks(dlo, dhi, dbda, 3, mlo, mhi, mbda, 3, olo, ohi, obda, 16);
    assert(n == 6);
    for (int i = 0; i < n; i++) {
        assert(olo[i] < ohi[i]);
        if (i > 0) assert(ohi[i - 1] <= olo[i]);
    }
    assert(olo[3] == 0x6000 && ohi[3] == 0x7000 && obda[3] == 0x206000);
    assert(vkp_merge_chunks(dlo, dhi, dbda, 3, mlo, mhi, mbda, 3, olo, ohi, obda, 2) == 2);

    // --- 2. vkp_rjoin_plan & vkp_rjoin_neg refusal cache ---
    fake_seg_t segs[5] = {
        {0x1000000, 0x1400000, 1, 0},
        {0x1400000, 0x1800000, 1, 0},
        {0x1800000, 0x1c00000, 1, 1},
        {0x1c00000, 0x2000000, 1, 1},
        {0x3000000, 0x3400000, 1, 0},
    };
    int k0 = 0, k1 = 0;
    uint64_t cap = 16ull << 20;

    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x1000000, 0x1200000, cap, 0, &k0, &k1) == 0);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x1200000, 0x1600000, cap, 0, &k0, &k1) == 1 && k0 == 0 && k1 == 1);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x1900000, 0x1e00000, cap, 0, &k0, &k1) == 2 && k0 == 2 && k1 == 3);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x1600000, 0x1a00000, cap, 0, &k0, &k1) == -1);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x13ff000, 0x1401000, 6ull << 20, 0, &k0, &k1) == -2);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x13ff000, 0x1401000, cap, 6ull << 20, &k0, &k1) == -3);
    assert(vkp_rjoin_plan(segs, sizeof *segs, offsetof(fake_seg_t, fd_alias), 5, 0x1e00000, 0x3100000, 64ull << 20, 0, &k0, &k1) == -4);

    vkp_rjoin_neg_t tab[8] = {0}; int w = 0;
    uint64_t gen = 42;
    assert(!vkp_rjoin_neg_hit(tab, 8, gen, 0x1600000, 0x1a00000));
    vkp_rjoin_neg_put(tab, 8, &w, gen, 0x1600000, 0x1a00000);
    assert(vkp_rjoin_neg_hit(tab, 8, gen, 0x1600000, 0x1a00000));
    assert(vkp_rjoin_neg_hit(tab, 8, gen, 0x1400000, 0x1c00000));
    assert(!vkp_rjoin_neg_hit(tab, 8, gen, 0x1600000, 0x1800000));
    assert(!vkp_rjoin_neg_hit(tab, 8, gen + 1, 0x1600000, 0x1a00000));
    return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class VirtualPoolBuffers(unittest.TestCase):
    def test_nvn_sparse_runs_coalesce_and_clip(self):
        src = HLE_NVN.read_text()
        m = re.search(
            r'// PEANUTS begin: lock-free sparse pool check.*?// PEANUTS end',
            src,
            re.S,
        )
        self.assertIsNotNone(m, 'sparse block not found in hle_nvn.c')
        with tempfile.TemporaryDirectory() as d:
            c = Path(d, 't_sparse.c')
            c.write_text(SPARSE_HARNESS_HEAD + m[0] + '\n' + SPARSE_HARNESS_MAIN)
            exe = Path(d, 't_sparse')
            r = subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-pthread', '-o', str(exe), str(c)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, f'compile failed:\n{r.stderr}')
            r2 = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(r2.returncode, 0, f'run failed:\n{r2.stderr}\n{r2.stdout}')

    def test_vkp_merge_chunks_and_rjoin_refusal_cache(self):
        with tempfile.TemporaryDirectory() as d:
            c = Path(d, 't_place.c')
            c.write_text(PLACE_HARNESS)
            exe = Path(d, 't_place')
            r = subprocess.run(
                ['cc', '-std=gnu11', '-Wall', '-Werror', '-I', str(VKA_PLACE.parent), '-o', str(exe), str(c)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(r.returncode, 0, f'compile failed:\n{r.stderr}')
            r2 = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(r2.returncode, 0, f'run failed:\n{r2.stderr}\n{r2.stdout}')


if __name__ == '__main__':
    unittest.main()
