// PEANUTS: new file. Resolution scale (dm/gfx/vk_android/vka_resscale.h; vka_resscale.inc "Resolution scale"): the factor's parsing, image sizes, the rounding of
// rectangles (touching rectangles still tile, a rectangle of the whole texture is the whole image, nothing leaves the image), the table of shapes kept at 1x, and
// the scan of a translated module for the translator's scale code, on hand-made SPIR-V
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../gfx/vk_android/vka_resscale.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const uint32_t QS[] = {2, 3, 4, 5, 6, 8};

static void t_parse(void) {
    static const struct { const char *s; uint32_t q; } c[] = {{NULL, 4}, {"", 4}, {"1", 4}, {"1.0", 4}, {"0.5", 2}, {".5", 2}, {"0.75", 3}, {"1.25", 5}, {"1.5", 6}, {"2", 8}, {"2.0", 8},
                                                             {"3", 4}, {"0", 4}, {"-1.5", 4}, {"abc", 4}, {"1.4", 4}, {"0.6", 4}, {"1e9", 4}, {"nan", 4}, {"1.5x", 6}};
    for (size_t i = 0; i < sizeof c / sizeof *c; i++) CHECK(vkrs_parse(c[i].s) == c[i].q, "parse(%s) = %u, want %u", c[i].s ? c[i].s : "NULL", vkrs_parse(c[i].s), c[i].q);
}
static void t_dim(void) {
    CHECK(vkrs_dim(1920, 6) == 2880 && vkrs_dim(1080, 6) == 1620, "1.5x of 1920x1080");
    CHECK(vkrs_dim(1280, 6) == 1920 && vkrs_dim(720, 6) == 1080, "1.5x of 1280x720 is the 1080p panel");
    CHECK(vkrs_dim(1920, 3) == 1440 && vkrs_dim(1080, 3) == 810, "0.75x of 1920x1080");
    CHECK(vkrs_dim(1600, 5) == 2000 && vkrs_dim(900, 5) == 1125, "1.25x of 1600x900");
    CHECK(vkrs_dim(1, 2) == 1 && vkrs_dim(1, 3) == 1 && vkrs_dim(0, 8) == 1, "a side is at least 1");
    CHECK(vkrs_dim(16384, 8) == 32768, "no overflow at the largest sides");
    for (size_t k = 0; k < 6; k++) for (uint32_t v = 1; v < 5000; v++) {
        uint32_t d = vkrs_dim(v, QS[k]);
        CHECK(d >= 1 && d == (v * QS[k] / 4 ? v * QS[k] / 4 : 1), "dim(%u, %u) = %u", v, QS[k], d);
        if (QS[k] == 4) CHECK(d == v, "1x changes nothing: %u -> %u", v, d);
    }
    CHECK(vkrs_bytes(1000, 6) == 2250 && vkrs_bytes(1000, 8) == 4000 && vkrs_bytes(1000, 2) == 250 && vkrs_bytes(1000, 4) == 1000, "bytes by the factor squared");
}
static void t_px(void) {
    CHECK(vkrs_px(0, 6) == 0 && vkrs_px(1, 6) == 1 && vkrs_px(2, 6) == 3 && vkrs_px(3, 6) == 4, "floor at 1.5x");
    CHECK(vkrs_px(-1, 6) == -2 && vkrs_px(-2, 6) == -3 && vkrs_px(-1, 2) == -1 && vkrs_px(-4, 3) == -3, "floor toward minus infinity");
    for (size_t k = 0; k < 6; k++) for (int64_t v = -3000; v < 3000; v++) {
        int64_t p = vkrs_px(v, QS[k]);
        CHECK(p * 4 <= v * (int64_t)QS[k] && (p + 1) * 4 > v * (int64_t)QS[k], "px(%lld, %u) = %lld is not the floor", (long long)v, QS[k], (long long)p);
        CHECK(vkrs_px(v + 1, QS[k]) >= p, "px is monotonic at %lld", (long long)v);
    }
}
// Spans that tile a side still tile it: no gap; an overlap only where a span was forced to one pixel
static void t_tile(void) {
    for (size_t k = 0; k < 6; k++) for (uint32_t side = 1; side <= 97; side++) for (uint32_t step = 1; step <= 9; step++) {
        uint32_t q = QS[k], lim = vkrs_dim(side, q); int64_t prev = 0;
        for (uint32_t x = 0; x < side; x += step) {
            int64_t a = x, b = x + step > side ? side : x + step; vkrs_span(&a, &b, q);
            CHECK(a <= prev, "gap before [%u, +%u) of %u at q %u: %lld > %lld", x, step, side, q, (long long)a, (long long)prev);
            CHECK(b > a, "empty span at %u of %u, q %u", x, side, q);
            if (q >= 4) CHECK(a == prev, "overlap at %u of %u, q %u", x, side, q);   // (at 1x and above nothing is forced: an exact tiling)
            prev = b;
        }
        CHECK(prev == (int64_t)lim || (vkrs_px(side, q) == 0 && prev == 1) || (q < 4 && prev <= (int64_t)lim + 1), "the spans of %u end at %lld, the image at %u (q %u)", side, (long long)prev, lim, q);
        int64_t a = 0, b = side; vkrs_span(&a, &b, q);
        CHECK(a == 0 && b == (int64_t)lim, "the whole side %u is [%lld, %lld), image %u (q %u)", side, (long long)a, (long long)b, lim, q);
    }
    int64_t a = 5, b = 5; vkrs_span(&a, &b, 6); CHECK(b <= a, "an empty span stays empty");
    a = 7; b = 3; vkrs_span(&a, &b, 2); CHECK(b <= a, "a negative span stays empty");
}
// A region inside the texture is inside the image, never empty
static void t_region(void) {
    for (size_t k = 0; k < 6; k++) for (uint32_t side = 1; side <= 40; side++) for (int32_t x = 0; x < (int32_t)side; x++) for (int32_t w = 1; x + w <= (int32_t)side; w++) {
        uint32_t q = QS[k], lim = vkrs_dim(side, q); int32_t o = x, l = w;
        int ok = vkrs_region(&o, &l, side, q);
        CHECK(ok && o >= 0 && l >= 1 && (uint32_t)(o + l) <= lim, "region %d+%d of %u at q %u -> %d+%d of %u (%d)", x, w, side, q, o, l, lim, ok);
        if (q == 4) CHECK(o == x && l == w, "1x changes a region: %d+%d -> %d+%d", x, w, o, l);
        if (x == 0 && w == (int32_t)side) CHECK(o == 0 && (uint32_t)l == lim, "the whole texture is not the whole image: %d+%d of %u", o, l, lim);
    }
    int32_t o = 0, l = 0; CHECK(!vkrs_region(&o, &l, 16, 6), "an empty region");
    o = 0; l = -4; CHECK(!vkrs_region(&o, &l, 16, 6), "a negative region");
    // A region that starts past the texture's last pixel has nothing inside at any factor (it was answered with the image's last pixel); one that starts on the last
    // pixel reaches the image's last pixel, and one that starts before pixel 0 keeps the part inside (below 1x it rounded to nothing)
    for (size_t k = 0; k < 6; k++) for (uint32_t side = 1; side <= 40; side++) {
        o = (int32_t)side; l = 8; CHECK(!vkrs_region(&o, &l, side, QS[k]), "a region at %u of a side of %u is not refused (q %u)", side, side, QS[k]);
        o = (int32_t)side + 24; l = 1; CHECK(!vkrs_region(&o, &l, side, QS[k]), "a region past a side of %u is not refused (q %u)", side, QS[k]);
        o = (int32_t)side - 1; l = 8; CHECK(vkrs_region(&o, &l, side, QS[k]) && l >= 1 && o >= 0 && (uint32_t)(o + l) == vkrs_dim(side, QS[k]), "the last pixel of a side of %u is %d+%d of %u (q %u)", side, o, l, vkrs_dim(side, QS[k]), QS[k]);
        o = -3; l = 4; CHECK(vkrs_region(&o, &l, side, QS[k]) && o == 0 && l >= 1 && (uint32_t)l <= vkrs_dim(side, QS[k]), "a region from before the texture is cut at 0 (side %u, q %u)", side, QS[k]);
        o = -8; l = 8; CHECK(!vkrs_region(&o, &l, side, QS[k]), "a region that ends at 0 has nothing inside (side %u, q %u)", side, QS[k]);
    }
    o = 1900; l = 40; { int ok = vkrs_region(&o, &l, 1920, 6); CHECK(!ok || (uint32_t)(o + l) <= 2880, "a region over the texture's edge is cut to the image: %d+%d", o, l); }
    // Two rectangles of one size at different offsets round to within a pixel of each other (vka_exec.c copy_t2t takes the smaller extent for a plain copy)
    for (size_t k = 0; k < 6; k++) for (int32_t w = 1; w < 64; w++) for (int32_t x = 0; x < 16; x++) for (int32_t y = 0; y < 16; y++) {
        int32_t o1 = x, l1 = w, o2 = y, l2 = w; vkrs_region(&o1, &l1, 256, QS[k]); vkrs_region(&o2, &l2, 256, QS[k]);
        CHECK(l1 - l2 <= 1 && l2 - l1 <= 1, "sizes %d and %d of width %d at %d and %d (q %u)", l1, l2, w, x, y, QS[k]);
    }
}
static void t_deny(void) {
    vkrs_deny *t = calloc(1, sizeof *t);
    CHECK(vkrs_deny_find(t, 1280, 720, 37, 8) < 0, "an empty table denies");
    CHECK(vkrs_deny_add(t, 1280, 720, 37, 8, 2) == 1 && t->n == 1, "first add");
    CHECK(vkrs_deny_add(t, 1280, 720, 37, 8, 3) == 0 && t->n == 1 && t->e[0].why == 2, "the same shape is not added twice, its first reason stays");
    CHECK(vkrs_deny_find(t, 1280, 720, 37, 8) == 0, "found");
    CHECK(vkrs_deny_find(t, 1280, 720, 37, 0) < 0 && vkrs_deny_find(t, 1280, 720, 44, 8) < 0 && vkrs_deny_find(t, 1280, 721, 37, 8) < 0 && vkrs_deny_find(t, 1281, 720, 37, 8) < 0, "another shape is not denied");
    for (uint32_t i = 1; i < VKRS_DENY_MAX; i++) CHECK(vkrs_deny_add(t, i, 1, 1, 0, 0) == 1, "add %u", i);
    CHECK(t->n == VKRS_DENY_MAX && vkrs_deny_add(t, 9999, 9, 9, 9, 0) == -1 && t->n == VKRS_DENY_MAX, "a full table takes nothing more");
    CHECK(vkrs_deny_add(t, 1280, 720, 37, 8, 0) == 0 && vkrs_deny_find(t, 255, 1, 1, 0) == 255, "a full table still finds what it holds");
    free(t);
}

// ---- The module scan: SPIR-V by hand ----
static unsigned SCAN(const uint32_t *w, size_t n, uint32_t b) { return vkrs_spv_scan(w, n, b, NULL); }   // PEANUTS: #734
typedef struct { uint32_t w[1024]; size_t n; } mod;
static void op(mod *m, uint32_t opc, int n, ...) {
    __builtin_va_list ap; __builtin_va_start(ap, n);
    m->w[m->n++] = (uint32_t)(n + 1) << 16 | opc;
    for (int i = 0; i < n; i++) m->w[m->n++] = __builtin_va_arg(ap, uint32_t);
    __builtin_va_end(ap);
}
enum { T_FLOAT = 1, T_INT, T_IMG2D, T_SIMG2D, T_PSIMG2D, V_TEX, C_5, C_0, C_1, T_PF, V_SUP, T_IMGBUF, T_SIMGBUF, T_PSIMGBUF, V_TBUF, T_ARR, T_PARR, V_ARR, C_4, T_IMG3D, T_PIMG3D, V_IMG3D, FIRST_FREE };
// A module with a support buffer at set 0 binding sup (set sup_set), a 2D texture, a buffer texture, an array of 2D textures and a 3D storage image; body adds the function's instructions
static void base(mod *m, uint32_t sup, uint32_t sup_set) {
    memset(m, 0, sizeof *m);
    m->w[0] = 0x07230203u; m->w[1] = 0x00010500; m->w[2] = 0; m->w[3] = 200; m->w[4] = 0; m->n = 5;
    op(m, 71, 3, (uint32_t)V_SUP, 33u, sup); op(m, 71, 3, (uint32_t)V_SUP, 34u, sup_set);
    op(m, 71, 3, (uint32_t)V_TEX, 33u, 7u); op(m, 71, 3, (uint32_t)V_TEX, 34u, 0u);
    op(m, 22, 2, (uint32_t)T_FLOAT, 32u); op(m, 21, 3, (uint32_t)T_INT, 32u, 1u);
    op(m, 25, 8, (uint32_t)T_IMG2D, (uint32_t)T_FLOAT, 1u, 0u, 0u, 0u, 1u, 0u); op(m, 27, 2, (uint32_t)T_SIMG2D, (uint32_t)T_IMG2D); op(m, 32, 3, (uint32_t)T_PSIMG2D, 0u, (uint32_t)T_SIMG2D);
    op(m, 25, 8, (uint32_t)T_IMGBUF, (uint32_t)T_FLOAT, 5u, 0u, 0u, 0u, 1u, 0u); op(m, 27, 2, (uint32_t)T_SIMGBUF, (uint32_t)T_IMGBUF); op(m, 32, 3, (uint32_t)T_PSIMGBUF, 0u, (uint32_t)T_SIMGBUF);
    op(m, 43, 3, (uint32_t)T_INT, (uint32_t)C_5, 5u); op(m, 43, 3, (uint32_t)T_INT, (uint32_t)C_0, 0u); op(m, 43, 3, (uint32_t)T_INT, (uint32_t)C_1, 1u); op(m, 43, 3, (uint32_t)T_INT, (uint32_t)C_4, 4u);
    op(m, 28, 3, (uint32_t)T_ARR, (uint32_t)T_SIMG2D, (uint32_t)C_4); op(m, 32, 3, (uint32_t)T_PARR, 0u, (uint32_t)T_ARR);
    op(m, 25, 8, (uint32_t)T_IMG3D, (uint32_t)T_FLOAT, 2u, 0u, 0u, 0u, 2u, 0u); op(m, 32, 3, (uint32_t)T_PIMG3D, 0u, (uint32_t)T_IMG3D);
    op(m, 32, 3, (uint32_t)T_PF, 2u, (uint32_t)T_FLOAT);
    op(m, 59, 3, (uint32_t)T_PSIMG2D, (uint32_t)V_TEX, 0u); op(m, 59, 3, (uint32_t)T_PSIMGBUF, (uint32_t)V_TBUF, 0u); op(m, 59, 3, (uint32_t)T_PARR, (uint32_t)V_ARR, 0u);
    op(m, 59, 3, (uint32_t)T_PIMG3D, (uint32_t)V_IMG3D, 0u); op(m, 59, 3, (uint32_t)T_PF, (uint32_t)V_SUP, 2u);
}
static void fetch(mod *m, uint32_t ptr_ty, uint32_t var, uint32_t simg_ty, uint32_t img_ty, uint32_t id0) {   // load, OpImage, OpImageFetch
    op(m, 61, 3, simg_ty, id0, var); op(m, 100, 3, img_ty, id0 + 1, id0); op(m, 95, 4, (uint32_t)T_FLOAT, id0 + 2, id0 + 1, (uint32_t)C_0); (void)ptr_ty;
}
static void t_scan(void) {
    mod m; uint32_t f = FIRST_FREE;
    CHECK(SCAN(NULL, 0, 0) == 0 && SCAN((const uint32_t[]){1, 2, 3, 4, 5, 6}, 6, 0) == 0, "no module");   // PEANUTS: #734
    base(&m, 130, 0); CHECK(SCAN(m.w, m.n, 130) == 0, "a module that reads nothing");
    base(&m, 130, 0); op(&m, 65, 5, (uint32_t)T_PF, f, (uint32_t)V_SUP, (uint32_t)C_5, (uint32_t)C_0);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_RT, "render_scale[0]: %u", SCAN(m.w, m.n, 130));   // PEANUTS: #734
    CHECK(SCAN(m.w, m.n, 131) == 0, "another binding is not the support buffer");
    base(&m, 130, 1); op(&m, 65, 5, (uint32_t)T_PF, f, (uint32_t)V_SUP, (uint32_t)C_5, (uint32_t)C_0);
    CHECK(SCAN(m.w, m.n, 130) == 0, "the same binding in the pools' set is not the support buffer");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 65, 5, (uint32_t)T_PF, f, (uint32_t)V_SUP, (uint32_t)C_5, (uint32_t)C_1);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_TEX, "render_scale[1]");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 66, 5, (uint32_t)T_PF, f, (uint32_t)V_SUP, (uint32_t)C_5, f + 50);   // a computed index (the helper's 1 + samplerIndex)
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_TEX, "render_scale[computed]");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 65, 5, (uint32_t)T_PF, f, (uint32_t)V_SUP, (uint32_t)C_0, (uint32_t)C_1);
    CHECK(SCAN(m.w, m.n, 130) == 0, "another member (the alpha test's reference)");   // PEANUTS: #734
    base(&m, 130, 0); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_PIXEL, "a 2D fetch without a helper: %u", SCAN(m.w, m.n, 130));   // PEANUTS: #734
    base(&m, 130, 0); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f); op(&m, 65, 5, (uint32_t)T_PF, f + 9, (uint32_t)V_SUP, (uint32_t)C_5, f + 50);
    CHECK(SCAN(m.w, m.n, 130) == (VKRS_SPV_PIXEL | VKRS_SPV_TEX), "a 2D fetch with the helper");   // PEANUTS: #734
    base(&m, 130, 0); fetch(&m, T_PSIMGBUF, V_TBUF, T_SIMGBUF, T_IMGBUF, f);
    CHECK(SCAN(m.w, m.n, 130) == 0, "a buffer texture's fetch is no pixel fetch");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 65, 4, (uint32_t)T_PSIMG2D, f, (uint32_t)V_ARR, (uint32_t)C_1); fetch(&m, T_PSIMG2D, f, T_SIMG2D, T_IMG2D, f + 1);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_PIXEL, "a fetch from an array of 2D textures");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 61, 3, (uint32_t)T_IMG3D, f, (uint32_t)V_IMG3D); op(&m, 98, 4, (uint32_t)T_FLOAT, f + 1, f, (uint32_t)C_0);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_PIXEL, "a 3D image read");   // PEANUTS: #734
    base(&m, 130, 0); op(&m, 61, 3, (uint32_t)T_SIMG2D, f, (uint32_t)V_TEX); op(&m, 100, 3, (uint32_t)T_IMG2D, f + 1, f); op(&m, 103, 4, (uint32_t)T_INT, f + 2, f + 1, (uint32_t)C_0);
    CHECK(SCAN(m.w, m.n, 130) == VKRS_SPV_PIXEL, "a size query");   // PEANUTS: #734
    base(&m, 130, 0); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f); m.w[m.n - 5] = 0xffffu << 16 | 95;   // a word count past the module's end: the scan stops
    CHECK(SCAN(m.w, m.n, 130) == 0, "a cut module");   // PEANUTS: #734
    base(&m, 130, 0); m.w[3] = 4; fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f);   // ids past the bound: ignored, not read out of range (the sanitizers check it)
    CHECK(SCAN(m.w, m.n, 130) == 0, "ids past the bound");   // PEANUTS: #734
}

// The bindings a module fetches from or sizes by pixel (#703): a texture it only samples is not among them, so its scale does not have to match
static void t_fetch_bindings(void) {
    mod m; uint32_t f = FIRST_FREE; vkrs_fb fb;
    base(&m, 130, 0); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 1 && fb.b[0] == 7 && vkrs_fb_has(&fb, 7) && !vkrs_fb_has(&fb, 8), "a 2D fetch names its binding: n %u all %u", fb.n, fb.all);
    base(&m, 130, 0); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f + 3);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 1, "two fetches of one binding name it once: n %u", fb.n);
    base(&m, 130, 0); op(&m, 61, 3, (uint32_t)T_SIMG2D, f, (uint32_t)V_TEX); op(&m, 87, 4, (uint32_t)T_FLOAT, f + 1, f, (uint32_t)C_0);   // OpImageSampleImplicitLod
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 0 && !vkrs_fb_has(&fb, 7), "a sample by normalized coordinates names nothing: n %u all %u", fb.n, fb.all);
    base(&m, 130, 0); op(&m, 61, 3, (uint32_t)T_SIMG2D, f, (uint32_t)V_TEX); op(&m, 100, 3, (uint32_t)T_IMG2D, f + 1, f); op(&m, 103, 4, (uint32_t)T_INT, f + 2, f + 1, (uint32_t)C_0);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 1 && fb.b[0] == 7, "a size query names its binding");
    base(&m, 130, 0); op(&m, 61, 3, (uint32_t)T_SIMG2D, f, (uint32_t)V_TEX); op(&m, 83, 3, (uint32_t)T_SIMG2D, f + 1, f); op(&m, 100, 3, (uint32_t)T_IMG2D, f + 2, f + 1); op(&m, 95, 4, (uint32_t)T_FLOAT, f + 3, f + 2, (uint32_t)C_0);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 1 && fb.b[0] == 7, "through OpCopyObject");
    base(&m, 130, 0); op(&m, 71, 3, f + 20, 33u, 9u); op(&m, 59, 3, (uint32_t)T_PSIMG2D, f + 20, 0u); fetch(&m, T_PSIMG2D, V_TEX, T_SIMG2D, T_IMG2D, f); fetch(&m, T_PSIMG2D, f + 20, T_SIMG2D, T_IMG2D, f + 3);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 2 && vkrs_fb_has(&fb, 7) && vkrs_fb_has(&fb, 9), "two textures: n %u", fb.n);
    base(&m, 130, 0); op(&m, 71, 3, f + 20, 33u, 9u); op(&m, 71, 3, f + 20, 34u, 1u); op(&m, 59, 3, (uint32_t)T_PSIMG2D, f + 20, 0u); fetch(&m, T_PSIMG2D, f + 20, T_SIMG2D, T_IMG2D, f);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(fb.all, "a texture of another set: every texture counts");
    base(&m, 130, 0); op(&m, 65, 4, (uint32_t)T_PSIMG2D, f, (uint32_t)V_ARR, (uint32_t)C_1); fetch(&m, T_PSIMG2D, f, T_SIMG2D, T_IMG2D, f + 1);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(fb.all, "a fetch from a variable with no binding: every texture counts");
    base(&m, 130, 0); op(&m, 100, 3, (uint32_t)T_IMG2D, f, f + 90); op(&m, 95, 4, (uint32_t)T_FLOAT, f + 1, f, (uint32_t)C_0);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(fb.all, "an image from no variable (a function parameter): every texture counts");
    base(&m, 130, 0); fetch(&m, T_PSIMGBUF, V_TBUF, T_SIMGBUF, T_IMGBUF, f); op(&m, 61, 3, (uint32_t)T_IMG3D, f + 3, (uint32_t)V_IMG3D); op(&m, 98, 4, (uint32_t)T_FLOAT, f + 4, f + 3, (uint32_t)C_0);
    vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(!fb.all && fb.n == 0, "a buffer texture's fetch and a 3D image read name nothing");
    base(&m, 130, 0);
    for (uint32_t k = 0; k <= VKRS_FB_MAX; k++) { uint32_t v = f + 100 + k * 4; op(&m, 71, 3, v, 33u, 20u + k); op(&m, 59, 3, (uint32_t)T_PSIMG2D, v, 0u); fetch(&m, T_PSIMG2D, v, T_SIMG2D, T_IMG2D, v + 1); }
    m.w[3] = 400; vkrs_spv_scan(m.w, m.n, 130, &fb); CHECK(fb.all && fb.n == VKRS_FB_MAX, "more bindings than the list holds: every texture counts (n %u)", fb.n);
}

int main(void) {
    t_parse(); t_dim(); t_px(); t_tile(); t_region(); t_deny(); t_scan(); t_fetch_bindings();   // PEANUTS: #734
    if (fails) { printf("t_android_resscale: %d FAILED\n", fails); return 1; }
    printf("t_android_resscale: ok\n");
    return 0;
}
