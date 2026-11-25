"""Run the production Vulkan region-write coalescing path without a Vulkan device."""  # PEANUTS: bounded WriteTexels dirty prep, #403 (0.1.8)
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
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { ST_ZERO = 0, ST_FILL = 1, ST_TRACK = 2 };
#define VLOG(...) ((void)0)

typedef struct { uint8_t kind, bw, bh, bpb; } vka_fmt;
typedef struct { uint64_t size; } nvn_texlayout;
typedef struct vka_img {
    uint64_t serial;
    uint8_t *mem;
    nvn_texlayout L;
    vka_fmt f;
    struct { int z; } m;
    uint32_t w, h, levels, layers;
    int refs, gpu_owned, cpu_dirty, track, mode, is_video, rsc, base, pdead;
    struct vka_img *par;
    uint32_t rw_pend;
    int wt;
    uint32_t *gpu_px;
} vka_img;
typedef struct { uint64_t serial; } tex_t;
typedef struct { int32_t xoffset, yoffset, zoffset, width, height, depth; } NVNcopyRegion;
typedef struct { uint64_t serial; vka_img *im; } smap_ent;

static pthread_mutex_t g_tmu = PTHREAD_MUTEX_INITIALIZER;
enum { MAX_SMAP = 64 };
static smap_ent g_smap[MAX_SMAP];
static int g_nsmap;
static uint64_t g_cpuw[MAX_SMAP];
static int g_ncpuw;

static void redo_forget_locked(vka_img *im);
static smap_ent *map_get(uint64_t s) {
    for (int i = 0; i < g_nsmap; i++) if (g_smap[i].serial == s) return &g_smap[i];
    return NULL;
}
static void map_put(uint64_t s, vka_img *im) {
    smap_ent *e = map_get(s);
    if (e) { e->im = im; return; }
    assert(g_nsmap < MAX_SMAP);
    g_smap[g_nsmap++] = (smap_ent){s, im};
}
static void map_del(uint64_t s) {
    for (int i = 0; i < g_nsmap; i++) if (g_smap[i].serial == s) { g_smap[i] = g_smap[--g_nsmap]; return; }
}
static void map_del_im(vka_img *im) {
    for (int i = 0; i < g_nsmap; ) {
        if (g_smap[i].im == im) g_smap[i] = g_smap[--g_nsmap];
        else i++;
    }
}
static int cpuw_take(uint64_t s, int add) {
    if (add) {
        for (int i = 0; i < g_ncpuw; i++) if (g_cpuw[i] == s) return 0;
        assert(g_ncpuw < MAX_SMAP);
        g_cpuw[g_ncpuw++] = s;
        return 0;
    }
    for (int i = 0; i < g_ncpuw; i++) if (g_cpuw[i] == s) { g_cpuw[i] = g_cpuw[--g_ncpuw]; return 1; }
    return 0;
}
static int img_pool_dead(const vka_img *im) { return im->pdead; }
static void unref_locked(vka_img *im) {
    if (!im || --im->refs) return;
    map_del_im(im);
    if (im->par) {
        vka_img *p = im->par;
        free(im);
        unref_locked(p);
        return;
    }
    if (im->cpu_dirty) redo_forget_locked(im);
    free(im->mem);
    free(im->gpu_px);
    free(im);
}
'''

MAIN = r'''
static vka_img *make_img(uint64_t serial, uint32_t w, uint32_t h, uint64_t lsize) {
    vka_img *im = calloc(1, sizeof *im);
    im->serial = serial;
    im->w = w;
    im->h = h;
    im->levels = 1;
    im->layers = 1;
    im->L.size = lsize;
    im->f = (vka_fmt){0, 1, 1, 4};
    im->refs = 1;
    im->track = 1;
    im->mode = ST_TRACK;
    im->mem = calloc((size_t)w * h, 4);
    im->gpu_px = calloc((size_t)w * h, 4);
    map_put(serial, im);
    return im;
}

static void drain_prepare(void) {
    for (int i = 0; i < g_nredo; i++) {
        vka_img *im = g_redo[i];
        if (!im || !im->cpu_dirty) continue;
        im->cpu_dirty = 0;
        if (im->gpu_owned || img_pool_dead(im)) continue;
        memcpy(im->gpu_px, im->mem, (size_t)im->w * im->h * 4);
    }
    g_nredo = 0;
    for (int i = 0; i < g_nreg_writes; i++) {
        tex_region_write_t *w = &g_reg_writes[i];
        vka_img *im = w->im;
        if (!im) continue;
        if (im->rw_pend) im->rw_pend--;
        if (!im->gpu_owned && !img_pool_dead(im) && !im->rsc) {
            for (uint32_t row = 0; row < w->h; row++) {
                memcpy(im->gpu_px + (size_t)(w->y + (int32_t)row) * im->w + w->x,
                       w->data + (size_t)row * w->w * 4,
                       (size_t)w->w * 4);
            }
        }
        free(w->data);
        unref_locked(im);
    }
    g_nreg_writes = 0;
}

static void write_px(const tex_t *t, vka_img *backing, int x, int y, uint32_t px) {
    if (backing) ((uint32_t *)backing->mem)[(size_t)y * backing->w + (size_t)x] = px;
    NVNcopyRegion r = {x, y, 0, 1, 1, 1};
    mtl_tex_write_region(t, 0, 0, &r, (const uint8_t *)&px, 0, 0);
}

int main(void) {
    // 1. Small region writes below threshold queue properly and release refs cleanly on drain.
    {
        vka_img *im = make_img(10, 64, 64, 64 * 64 * 4);
        tex_t t = {10};
        for (int i = 0; i < 10; i++) write_px(&t, im, i, 2, 0x1000u + (uint32_t)i);
        assert(im->rw_pend == 10 && im->refs == 11 && im->wt == 1 && im->track == 0 && !im->cpu_dirty);
        drain_prepare();
        assert(im->rw_pend == 0 && im->refs == 1);
        for (int i = 0; i < 10; i++) assert(im->gpu_px[2 * 64 + i] == 0x1000u + (uint32_t)i);
        map_del(10);
        unref_locked(im);
    }

    // 2. Metroid Dread pattern: 5,000 1x1 writes to a 1024x512 (2 MiB) image coalesce at #129.
    {
        vka_img *im = make_img(20, 1024, 512, 2u << 20);
        assert(rw_coalesce_limit(im) == 128);
        tex_t t = {20};
        for (int i = 0; i < 5000; i++) {
            int x = i % 1024, y = i / 1024;
            write_px(&t, im, x, y, 0xa0000000u + (uint32_t)i);
            if (i < 128) assert(im->rw_pend == (uint32_t)(i + 1) && !im->cpu_dirty && im->refs == i + 2);
            else assert(im->rw_pend == 0 && im->refs == 1 && im->cpu_dirty == 1 && g_nreg_writes == 0);
        }
        assert(g_nredo == 1 && g_redo[0] == im);
        drain_prepare();
        assert(!im->cpu_dirty && im->refs == 1);
        for (int i = 0; i < 5000; i++) assert(im->gpu_px[i] == 0xa0000000u + (uint32_t)i);
        map_del(20);
        unref_locked(im);
    }

    // 3. Interleaved images A & B + write-after-write ordering on fallback.
    {
        vka_img *a = make_img(30, 64, 64, 64 * 64 * 4);
        vka_img *b = make_img(31, 64, 64, 64 * 64 * 4);
        tex_t ta = {30}, tb = {31};
        write_px(&ta, a, 0, 0, 0x11111111u);
        for (int i = 0; i < 3; i++) write_px(&tb, b, i, 1, 0x5500u + (uint32_t)i);
        assert(a->rw_pend == 1 && b->rw_pend == 3 && g_nreg_writes == 4);

        // Update backing pixel in A then trigger fallback via mtl_tex_cpu_written.
        ((uint32_t *)a->mem)[0] = 0x22222222u;
        mtl_tex_cpu_written(&ta);
        assert(a->rw_pend == 0 && a->refs == 1 && a->cpu_dirty == 1);
        assert(b->rw_pend == 3 && b->refs == 4 && !b->cpu_dirty);

        // Subsequent WriteTexels while cpu_dirty=1 early-returns without queueing.
        write_px(&ta, a, 1, 0, 0x33333333u);
        assert(a->rw_pend == 0 && a->refs == 1);

        drain_prepare();
        assert(a->gpu_px[0] == 0x22222222u && a->gpu_px[1] == 0x33333333u);
        for (int i = 0; i < 3; i++) assert(b->gpu_px[64 + i] == 0x5500u + (uint32_t)i);
        assert(a->refs == 1 && b->refs == 1);

        // Also test unsupported region fallback cancelling queued writes on A.
        write_px(&ta, a, 2, 0, 0x44444444u);
        assert(a->rw_pend == 1 && a->refs == 2);
        ((uint32_t *)a->mem)[2] = 0x55555555u;
        NVNcopyRegion bad = {-1, 0, 0, 1, 1, 1};
        uint32_t px = 0;
        mtl_tex_write_region(&ta, 0, 0, &bad, (const uint8_t *)&px, 0, 0);
        assert(a->rw_pend == 0 && a->refs == 1 && a->cpu_dirty == 1);
        drain_prepare();
        assert(a->gpu_px[2] == 0x55555555u && a->refs == 1);

        map_del(30); map_del(31);
        unref_locked(a); unref_locked(b);
    }

    // 4. Pool drop path (drop_refs=0 + redo_forget_locked in unref_locked) frees cleanly with zero ASan leaks/UAF.
    {
        vka_img *im = make_img(40, 64, 64, 64 * 64 * 4);
        tex_t t = {40};
        for (int i = 0; i < 5; i++) write_px(&t, im, i, 0, (uint32_t)i);
        assert(im->rw_pend == 5 && im->refs == 6);
        rw_cancel_locked(im, 0);
        assert(im->rw_pend == 0 && g_nreg_writes == 0);
        map_del_im(im);
        im->refs = 1;
        unref_locked(im);

        vka_img *im2 = make_img(41, 64, 64, 64 * 64 * 4);
        tex_t t2 = {41};
        mtl_tex_cpu_written(&t2);
        assert(im2->cpu_dirty == 1 && im2->refs == 1 && g_nredo == 1 && g_redo[0] == im2);
        map_del_im(im2);
        im2->refs = 1;
        unref_locked(im2);   // triggers redo_forget_locked(im2) before freeing
        assert(g_redo[0] == NULL);
        drain_prepare();
    }

    // 5. Shared image whose original serial is finalized WHILE queued in g_redo still uploads cleanly.
    {
        vka_img *im = make_img(50, 512, 512, 1u << 20);
        assert(rw_coalesce_limit(im) == 64);
        im->refs++;
        map_put(51, im);
        tex_t t1 = {50}, t2 = {51};
        for (int i = 0; i < 70; i++) write_px(&t1, im, i, 0, 0x7700u + (uint32_t)i);
        assert(im->cpu_dirty == 1 && im->rw_pend == 0 && im->refs == 2);
        map_del(50);
        unref_locked(im);   // original serial 50 finalized while in g_redo; serial 51 still holds im
        assert(im->refs == 1 && im->cpu_dirty == 1);
        drain_prepare();
        assert(!im->cpu_dirty && im->refs == 1);
        for (int i = 0; i < 70; i++) assert(im->gpu_px[i] == 0x7700u + (uint32_t)i);
        write_px(&t2, im, 70, 0, 0x88888888u);
        assert(im->rw_pend == 1 && im->refs == 2 && !im->cpu_dirty);
        drain_prepare();
        assert(im->gpu_px[70] == 0x88888888u && im->refs == 1);
        map_del(51);
        unref_locked(im);
    }

    // 6. Large 85 MiB atlas gets cap 2048 so 500 tile writes stay region copies and #2049 coalesces.
    {
        vka_img *atlas = make_img(60, 1024, 1024, 85u << 20);
        assert(rw_coalesce_limit(atlas) == 2048);
        tex_t t = {60};
        for (int i = 0; i < 500; i++) write_px(&t, atlas, i % 1024, i / 1024, (uint32_t)i);
        assert(atlas->rw_pend == 500 && !atlas->cpu_dirty && atlas->refs == 501);
        for (int i = 500; i < 2049; i++) write_px(&t, atlas, i % 1024, i / 1024, (uint32_t)i);
        assert(atlas->rw_pend == 0 && atlas->cpu_dirty == 1 && atlas->refs == 1 && g_nreg_writes == 0);
        drain_prepare();
        assert(!atlas->cpu_dirty && atlas->refs == 1);
        map_del(60);
        unref_locked(atlas);
    }

    // 7. Level child (im->par != NULL) cancels parent's pending region writes too and uploads whole parent.
    {
        vka_img *par = make_img(70, 64, 64, 64 * 64 * 4);
        vka_img *child = calloc(1, sizeof *child);
        child->serial = 71;
        child->w = 32; child->h = 32; child->levels = 1; child->layers = 1;
        child->f = par->f;
        child->refs = 1;
        child->par = par;
        par->refs++;
        map_put(71, child);
        tex_t tp = {70}, tc = {71};
        write_px(&tp, par, 5, 0, 0x12345678u);   // stale region write queued on par
        assert(par->rw_pend == 1 && par->refs == 3);
        ((uint32_t *)par->mem)[5] = 0xabcdef01u;  // newer pixel written to storage before fallback
        write_px(&tc, par, 0, 0, 0x99999999u);
        assert(par->cpu_dirty == 1 && par->rw_pend == 0 && child->rw_pend == 0);
        assert(child->refs == 1 && par->refs == 2 && g_nreg_writes == 0);
        drain_prepare();
        assert(par->gpu_px[0] == 0x99999999u && par->gpu_px[5] == 0xabcdef01u);
        assert(!par->cpu_dirty && par->refs == 2);
        map_del(71); unref_locked(child);
        map_del(70); unref_locked(par);
    }

    free(g_redo);
    free(g_reg_writes);
    free(g_rw_set);
    free(g_rw_large);
    return 0;
}
'''


class WriteRegionCoalesce(unittest.TestCase):
    def test_region_write_coalescing_and_refcounts(self) -> None:
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('needs a C compiler')
        source = SOURCE.read_text()
        part1_start = source.index('static vka_img **g_redo;')
        part1_end = source.index('// Unreal grows a texture')
        part2_start = source.index('static int reg_write_overlap(')
        part2_end = source.index('// ---------- Descriptor tables ----------')
        functions = source[part1_start:part1_end] + '\n' + source[part2_start:part2_end]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            src_path = path / 'write_region.c'
            bin_path = path / 'write_region'
            src_path.write_text(PRELUDE + functions + MAIN)
            build = subprocess.run(
                [cc, '-std=c11', '-O1', '-Wall', '-Wextra', '-Werror',
                 '-Wno-unused-function', '-fsanitize=address,undefined',
                 '-fno-sanitize-recover=all', '-pthread',
                 str(src_path), '-o', str(bin_path)],
                capture_output=True, text=True,
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(bin_path)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


if __name__ == '__main__':
    unittest.main()
