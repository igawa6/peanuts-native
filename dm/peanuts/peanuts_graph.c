// PEANUTS: the host side of the overlay's frame-time graph (peanuts_graph.h). The launcher's block (slot 1) is kept packed; every game frame the bars are painted into
// the copy and the copy is given back to the host's overlay, so the graph moves at the game's frame rate while the rest of the block still changes twice a second.
#include "peanuts_graph.h"
#include "peanuts_frametime.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#define WINDOW PN_FT_CAP   // the frames the target is the median of (Hud.WINDOW)
#define VIS __attribute__((visibility("default")))

// A host without the overlay (Mac, tests) links without it
__attribute__((weak)) int nxhost_overlay_set(int slot, const void *px, int w, int h, int stride);
__attribute__((weak)) int nxhost_overlay_state(void);   // below 0: the present does not draw the overlay (DM_VK_OVERLAY=0, or its pipeline failed) and the launcher's views do

static pthread_mutex_t gr_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *gr_copy;       // the slot-1 block, w * h * 4 bytes
static int gr_w, gr_h;
static int g_rx, g_ry, g_rw, g_rh;   // the bars' box in the launcher's bitmap; g_rw or g_rh 0 = off
static int gr_shift;                 // the leading transparent rows moved to the bottom of the copy (a bottom corner): the box is that many rows higher
static _Atomic int gr_plain;   // pn_graph_plain: on-pace bars light grey (the pill) instead of green (the table, the default)
static _Atomic int gr_bars = PN_GRAPH_BARS;   // the columns the box is split into (pn_graph_bars: the table 48, the pill fewer)
static _Atomic int gr_corner = 1;    // the stats block's place, vka_ovl.h: 0 top left, 1 top right, 2 bottom left, 3 bottom right, 4 top centre, 6 bottom centre

static int cmpf(const void *a, const void *b) { float x = *(const float *)a, y = *(const float *)b; return (x > y) - (x < y); }

static void put(uint8_t *p, uint32_t rgb, int alpha) {
    p[0] = (uint8_t)((((rgb >> 16) & 255) * alpha + 127) / 255);
    p[1] = (uint8_t)((((rgb >> 8) & 255) * alpha + 127) / 255);
    p[2] = (uint8_t)(((rgb & 255) * alpha + 127) / 255);
    p[3] = (uint8_t)alpha;
}

void pn_graph_draw(uint8_t *px, int stride, int x, int y, int w, int h, const float *ms, int n) {
    if (!px || !ms || n <= 0 || w <= 0 || h <= 0 || x < 0 || y < 0) return;
    int sx = x >= 2 ? x - 2 : 0;
    for (int r = 0; r < h; r++) {   // each row from the background left of it, so a background that changes from top to bottom (the pill's sheen, PillBg) shows no box
        uint8_t *row = px + (size_t)(y + r) * stride, bg[4];
        memcpy(bg, row + (size_t)sx * 4, 4);
        row += (size_t)x * 4;
        for (int c = 0; c < w; c++) memcpy(row + c * 4, bg, 4);
    }
    float tmp[WINDOW];
    int m = n < WINDOW ? n : WINDOW;
    const float *all = ms + (n - m);
    memcpy(tmp, all, (size_t)m * sizeof(float));
    qsort(tmp, (size_t)m, sizeof(float), cmpf);
    float target = m % 2 ? tmp[m / 2] : (tmp[m / 2 - 1] + tmp[m / 2]) * 0.5f;
    int bars = atomic_load(&gr_bars);
    int show = n < bars ? n : bars;
    float step = (float)w / bars;
    int gap = step > 3.0f ? 1 : 0;
    for (int i = 0; i < show; i++) {
        float f = ms[n - show + i];
        int col = bars - show + i;
        int x0 = (int)(col * step), x1 = (int)((col + 1) * step) - gap;
        if (x1 > w) x1 = w;
        if (x1 <= x0) x1 = x0 + 1 <= w ? x0 + 1 : w;
        int bucket = 0;
        if (target > 0 && f > 0) { bucket = (int)(f / (target * 2.0f) * 8); if (bucket > 7) bucket = 7; }
        int bh = h * (bucket + 1) / 8;
        if (bh < 1) bh = 1;
        uint32_t rgb = f <= target * 1.15f ? (atomic_load(&gr_plain) ? 0xBDBDBD : 0x4CAF50) : f >= target * 2.0f ? 0xF44336 : 0xFFC107;   // on pace green (the pill: light grey, pn_graph_plain), slow yellow, very slow red
        for (int r = h - bh; r < h; r++) {
            uint8_t *row = px + (size_t)(y + r) * stride + (size_t)x * 4;
            for (int c = x0; c < x1; c++) put(row + c * 4, rgb, 217);
        }
    }
}

static int rect_ok(void) { return g_rw > 0 && g_rh > 0 && g_rx >= 0 && g_ry - gr_shift >= 0 && g_rx + g_rw <= gr_w && g_ry - gr_shift + g_rh <= gr_h; }

static void draw_current(uint8_t *px, int stride) {
    float ms[WINDOW];
    int n = pn_ft_read(ms, WINDOW);
    if (n >= 10) pn_graph_draw(px, stride, g_rx, g_ry - gr_shift, g_rw, g_rh, ms, n);
}

// The block's leading rows that are fully transparent (alpha 0 across the row), at most h - 1: the launcher's safety margin at the top, for a top corner
static int lead_rows(const uint8_t *px, int stride, int w, int h) {
    int n = 0;
    while (n < h - 1) {
        const uint8_t *row = px + (size_t)n * stride;
        for (int c = 0; c < w; c++) if (row[c * 4 + 3]) return n;
        n++;
    }
    return n;
}

// The first `lead` rows moved below the others (a rotation of the rows), in place
void pn_graph_rotate_rows(uint8_t *px, int stride, int w, int h, int lead) {
    if (!px || lead <= 0 || lead >= h || w <= 0) return;
    size_t rb = (size_t)w * 4;
    uint8_t *tmp = malloc(rb * (size_t)lead);
    if (!tmp) return;
    for (int r = 0; r < lead; r++) memcpy(tmp + r * rb, px + (size_t)r * stride, rb);
    for (int r = lead; r < h; r++) memmove(px + (size_t)(r - lead) * stride, px + (size_t)r * stride, rb);
    for (int r = 0; r < lead; r++) memcpy(px + (size_t)(h - lead + r) * stride, tmp + r * rb, rb);
    free(tmp);
}

VIS void peanuts_ovl_block(int slot, void *px, int w, int h, int stride) {
    if (slot != 1) return;
    pthread_mutex_lock(&gr_mu);
    if (!px || w <= 0 || h <= 0 || stride < w * 4) { free(gr_copy); gr_copy = NULL; gr_w = gr_h = 0; gr_shift = 0; pthread_mutex_unlock(&gr_mu); return; }
    if (w != gr_w || h != gr_h || !gr_copy) {
        free(gr_copy);
        gr_copy = malloc((size_t)w * h * 4);
        gr_w = gr_copy ? w : 0; gr_h = gr_copy ? h : 0;
    }
    if (gr_copy) {
        int corner = atomic_load(&gr_corner);
        // a bottom place (bit 2): the margin's rows go below the text, in the caller's pixels (what the host draws) and in the copy
        gr_shift = corner & 2 ? lead_rows((const uint8_t *)px, stride, w, h) : 0;
        pn_graph_rotate_rows((uint8_t *)px, stride, w, h, gr_shift);
        for (int r = 0; r < h; r++) memcpy(gr_copy + (size_t)r * w * 4, (const uint8_t *)px + (size_t)r * stride, (size_t)w * 4);
        if (rect_ok()) { draw_current(gr_copy, w * 4); draw_current((uint8_t *)px, stride); }   // the caller's pixels too: no frame of stale bars
    }
    pthread_mutex_unlock(&gr_mu);
}

void pn_graph_plain(int on) { atomic_store(&gr_plain, on ? 1 : 0); }
void pn_graph_bars(int n) { atomic_store(&gr_bars, n >= 1 && n <= PN_GRAPH_BARS ? n : PN_GRAPH_BARS); }

void pn_graph_corner(int corner) { atomic_store(&gr_corner, (corner >= 0 && corner <= 4) || corner == 6 ? corner : 1); }

// The corner of an overlay slot for the host's present (vka_present.c): the stats block (slot 1) where the launcher says; the build tag (slot 0, top left) moves to the
// top right when the stats block is top left
VIS int peanuts_ovl_corner(int slot, int dflt) {
    int c = atomic_load(&gr_corner);
    if (slot == 1) return c;
    if (slot == 0 && c == 0) return 1;
    return dflt;
}

void pn_graph_rect(int x, int y, int w, int h) {
    pthread_mutex_lock(&gr_mu);
    g_rx = x; g_ry = y; g_rw = w; g_rh = h;
    pthread_mutex_unlock(&gr_mu);
}

void pn_graph_frame(void) {
    if (!nxhost_overlay_set) return;
    if (nxhost_overlay_state && nxhost_overlay_state() < 0) return;   // nobody shows this copy: the launcher's views draw the block, with the bars peanuts_ovl_block painted
    pthread_mutex_lock(&gr_mu);
    if (gr_copy && rect_ok()) {
        draw_current(gr_copy, gr_w * 4);
        nxhost_overlay_set(1, gr_copy, gr_w, gr_h, gr_w * 4);   // copies the pixels itself; under the lock so the launcher cannot replace the copy meanwhile
    }
    pthread_mutex_unlock(&gr_mu);
}

#if defined(__ANDROID__)
#include <jni.h>
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_graphRect(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h) {
    (void)env; (void)cls;
    pn_graph_rect(x, y, w, h);
}
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_graphPlain(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    pn_graph_plain(on);
}
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_graphBars(JNIEnv *env, jclass cls, jint n) {
    (void)env; (void)cls;
    pn_graph_bars(n);
}
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_overlayCorner(JNIEnv *env, jclass cls, jint corner) {
    (void)env; (void)cls;
    pn_graph_corner(corner);
}
#endif
