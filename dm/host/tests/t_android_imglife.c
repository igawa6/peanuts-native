// PEANUTS: new file. A model of the texture cache's image lifetimes (dm/gfx/vk_android/vka_tex.c: references, holds, the old image an in-place swap leaves behind
// (zold), lingering, takeover, eviction, pool drops, queued region writes) run with random operations under the address sanitizer, which stops at the first image
// used or freed after it was freed. It is a model, not the cache's code: each function below is the cache's function of the same job reduced to its bookkeeping
// (unref_locked, im_unhold_locked, img_destroy, linger_keep, vka_tex_prune_locked, vka_tex_get, vka_tex_drop_pool, mtl_tex_write_region and vka_tex_prepare's drain,
// and the swap of img_own / soft_harden / img_storage_upgrade / vka_resscale.inc rs_set_locked). What it holds on to: the first device run of the resolution scale
// aborted in a second img_destroy, and this is the one way to it that reading the cache found. A region write (WriteTexels) holds its image by pointer with a reference and no hold; eviction and a pool drop
// force the count and freed the image at once; the swap's new vka_img took that memory, and the drain's release of the stale pointer destroyed it once more than its
// own callback. So: an image with queued region writes is not evicted, a pool drop takes its queued writes back, and a swap's old image cannot be brought to zero.
// `t_android_imglife old` runs the cache as it was (the sanitizer reports the use after free), for showing that the model has the fault.
// `t_android_imglife poolheld` runs a pool drop that also gives up images a holder has, as vka_tex_drop_pool did until it skipped them (the same report: the present's
// drop of a window texture whose pool the game finalized first).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "../../gfx/vk_android/vka_texown.h"   // PEANUTS: image retirement covers the recording submission

typedef struct node { struct node *next; } node;   // an image view (vka_view's list)
typedef struct img { int refs, holds, rw_pend, cpu_dirty; struct img *zold; node *views; uint64_t linger, last_frame, last_fence; int gpu, pool, key, scaled; } img;
enum { NSER = 48, NLIVE = 256, LINGER_MAX = 4, LINGER_FRAMES = 2, KEEP = 2, GPUF = 6, NRW = 256, NAFTER = 8192, HOLDER = 1 << 30, RW_LIMIT = 4 };
static img *g_map[NSER]; static struct { int alive, key, pool; } T[NSER];   // serial -> image; the game's texture objects
static img *g_live[NLIVE]; static int g_nlive;
static img *g_linger[LINGER_MAX]; static int g_nlinger;
static uint64_t g_frame = 1, g_last = 1, g_done;                             // the frame, the last fence reserved, the last fence done
static uint64_t g_recorded;   // PEANUTS: a command buffer can still refer to an image last bound several submissions ago
static struct { uint64_t f; img *im; } A[NAFTER]; static int nA;             // vka_after(img_destroy)
static int pool_alive[3] = {1, 1, 1};
static img *RW[NRW]; static int nRW;                                         // queued region writes
static img *REDO[NLIVE]; static int nREDO;                                   // non-owning whole-image redos (cleared in unref via redo_forget)
static int fixed = 1, pool_held = 0;   // pool_held: a pool drop also gives up the images a holder has, as vka_tex_drop_pool did (`t_android_imglife poolheld`)

static void destroy(img *im) {
    if (im->zold) { destroy(im->zold); im->zold = NULL; }
    for (node *v = im->views, *n; v; v = n) { n = v->next; free(v); }
    free(im);
}
static void after(uint64_t f, img *im) { if (g_done >= f) { destroy(im); return; } if (nA == NAFTER) abort(); A[nA].f = f; A[nA++].im = im; }
static void complete(uint64_t v) {
    if (v > g_last) v = g_last;
    if (v > g_done) g_done = v;
    for (int i = 0; i < nA;) if (A[i].f <= g_done) { img *im = A[i].im; A[i] = A[--nA]; destroy(im); } else i++;
}
static void live_del(img *im) { for (int i = 0; i < g_nlive; i++) if (g_live[i] == im) { g_live[i] = g_live[--g_nlive]; return; } }
static void map_del_im(img *im) { for (int s = 0; s < NSER; s++) if (g_map[s] == im) g_map[s] = NULL; }
static void redo_forget(img *im) { for (int i = 0; i < nREDO; i++) if (REDO[i] == im) REDO[i] = NULL; im->cpu_dirty = 0; }
static void redo_add(img *im) { if (!im || im->gpu || im->cpu_dirty || nREDO >= NLIVE) return; im->cpu_dirty = 1; REDO[nREDO++] = im; }
static void unref(img *im) {
    if (--im->refs) return;
    if (im->linger) { for (int i = 0; i < g_nlinger; i++) if (g_linger[i] == im) { g_linger[i] = g_linger[--g_nlinger]; break; } im->linger = 0; }
    map_del_im(im);
    if (im->cpu_dirty) redo_forget(im);
    live_del(im);
    after(fixed ? vto_retire_fence(im->last_fence, g_recorded) : (im->last_fence ? im->last_fence : g_last + 1), im);   // PEANUTS: image retirement covers the recording submission
}
static void unhold(img *im) { if (im->holds > 0) im->holds--; if (!im->holds && im->zold) { after(g_last + 1, im->zold); im->zold = NULL; } }
static void linger_drop_at(int i) { img *im = g_linger[i]; g_linger[i] = g_linger[--g_nlinger]; im->linger = 0; unref(im); }
static void linger_prune(void) { for (int i = 0; i < g_nlinger;) if (g_frame > g_linger[i]->linger + LINGER_FRAMES) linger_drop_at(i); else i++; }
static int linger_keep(img *im) {
    if (!im->gpu || im->refs != 1 || im->linger) return 0;
    linger_prune();
    if (g_nlinger == LINGER_MAX) { int o = 0; for (int i = 1; i < g_nlinger; i++) if (g_linger[i]->linger < g_linger[o]->linger) o = i; linger_drop_at(o); }
    im->linger = g_frame + 1; g_linger[g_nlinger++] = im;
    return 1;
}
static void rw_cancel_drop(img *im, int drop_refs) {
    int dropped = 0;
    for (int k = 0; k < nRW;) {
        if (RW[k] == im) { RW[k] = RW[--nRW]; dropped++; }
        else k++;
    }
    im->rw_pend = 0;
    if (drop_refs) for (int i = 0; i < dropped; i++) unref(im);
}
static void rw_cancel(img *im) { rw_cancel_drop(im, 0); }
static void force(img *im) { map_del_im(im); im->refs = 1; unref(im); }   // img_evict_locked, vka_tex_drop_pool: given up whatever its count
static void prune(void) {
    if (g_nlive < 6) return;   // (over the limit)
    for (int i = 0; i < g_nlive;) {
        img *im = g_live[i]; int pending = im->last_fence && g_done < im->last_fence, held = im->holds + (fixed ? im->rw_pend + im->cpu_dirty : 0);
        if (!held && !pending && im->last_frame && g_frame > im->last_frame && g_frame - im->last_frame >= (uint64_t)(im->gpu ? GPUF : KEEP)) force(im); else i++;
    }
}
static img *get(int s) {   // vka_tex_get
    if (g_map[s]) { g_map[s]->last_frame = g_frame; g_map[s]->last_fence = g_last; return g_map[s]; }
    img *im = NULL;
    for (int i = 0; i < g_nlive && !im; i++) {
        img *o = g_live[i]; if (o->key != T[s].key || !(pool_alive[o->pool] || o->gpu)) continue;
        im = o; o->pool = T[s].pool;
        if (o->linger) { for (int k = 0; k < g_nlinger; k++) if (g_linger[k] == o) { g_linger[k] = g_linger[--g_nlinger]; break; } o->linger = 0; } else o->refs++;
    }
    if (!im) { linger_prune(); prune(); if (g_nlive == NLIVE) return NULL; im = calloc(1, sizeof *im); im->refs = 1; im->key = T[s].key; im->pool = T[s].pool; g_live[g_nlive++] = im; }
    im->last_frame = g_frame; im->last_fence = g_last; g_map[s] = im;
    return im;
}
static void view(img *im) { node *v = malloc(sizeof *v); v->next = im->views; im->views = v; }
static void swap(img *im, int self, int with_prune) {   // the image made again in place: n holds what im had
    (void)self;
    if (with_prune) prune();   // (the first version of rs_set_locked pruned before it made the new image, which then took an evicted image's memory)
    img *n = calloc(1, sizeof *n); n->refs = fixed ? HOLDER : 1;
    n->views = im->views; im->views = NULL; im->scaled = !im->scaled;
    if (im->holds) { n->zold = im->zold; im->zold = n; } else after(g_last + 1, n);
}
static void release(int s) { T[s].alive = 0; img *im = g_map[s]; g_map[s] = NULL; if (im && !linger_keep(im)) unref(im); }   // mtl_tex_release

// PEANUTS: WriteTexels holds an image last bound at an already-complete fence. Finalize releases the serial, then
// prepare records the queued copy and drops its last reference. That copy must finish before destruction.
static void upload_then_release(int worker) {
    uint64_t previous = g_last, upload = previous + 1;
    complete(previous);
    T[0].alive = 1;
    img *im = get(0); im->refs++; im->rw_pend++;
    release(0);
    if (worker) g_last = upload;   // the worker's job already reserved its fence
    g_recorded = vto_retire_fence(g_recorded, upload);
    im->rw_pend--; unref(im);
    if (nA != 1 || A[0].im != im || A[0].f != upload) { fputs("FAIL: image retired before its region copy\n", stderr); exit(1); }
    if (!worker) g_last = upload;   // an inline submit reserves only at commit
    complete(upload);
    if (nA) { fputs("FAIL: image kept past the final commit\n", stderr); exit(1); }
}

static void run(unsigned seed, long steps) {
    srand(seed);
    img *held[3] = {0}; int heldn = 0;
    for (long it = 0; it < steps; it++) {
        int op = rand() % 100, s = rand() % NSER;
        if (op < 6) g_frame++;
        else if (op < 14) g_recorded = ++g_last;   // PEANUTS: image retirement covers the recording submission
        else if (op < 24) complete(g_done + 1 + (uint64_t)(rand() % 3));
        else if (op < 34) { if (!T[s].alive) { T[s].alive = 1; T[s].key = rand() % 12; T[s].pool = rand() % 3; pool_alive[T[s].pool] = 1; } }
        else if (op < 58) {   // the recording thread uses a texture: a view, a draw into it, a change of scale, a storage binding's hold with its own swap
            img *im = T[s].alive ? get(s) : NULL; if (!im) continue;
            if (rand() % 2) view(im);
            if (rand() % 2) im->gpu = 1;
            if (rand() % 3 == 0) swap(im, 0, !fixed);
            if (rand() % 8 == 0) { im->holds++; if (rand() % 2) swap(im, 1, 0); unhold(im); }
        }
        else if (op < 70) { if (T[s].alive) release(s); }
        else if (op < 80) {   // the present: a reference and a hold, kept for three presents
            img *im = T[s].alive ? g_map[s] : NULL; if (!im) continue;
            im->refs++; im->holds++; im->last_frame = g_frame; im->last_fence = g_last;
            if (heldn == 3) { img *o = held[0]; held[0] = held[1]; held[1] = held[2]; heldn = 2; unhold(o); unref(o); }
            held[heldn++] = im; if (rand() % 2) view(im);
        }
        else if (op < 84) {   // a memory pool is finalized: its images go whatever their count, but not ones a holder has (vka_tex_drop_pool's !im->holds: the present still
            int p = rand() % 3; pool_alive[p] = 0;   // drops the window textures it keeps for three presents, and a game may finalize their pool before that)
            for (int i = 0; i < g_nlive;) { img *im = g_live[i]; if (im->pool == p && (!im->holds || pool_held)) { if (fixed) rw_cancel(im); force(im); } else i++; }
            for (int k = 0; k < NSER; k++) if (T[k].alive && T[k].pool == p) release(k);
        }
        else if (op < 91) {   // WriteTexels: region writes coalesce to whole-image redo above RW_LIMIT
            img *im = T[s].alive ? g_map[s] : NULL;
            if (im && !im->gpu) {
                if (im->cpu_dirty) { if (im->rw_pend) rw_cancel_drop(im, 1); }
                else if (im->rw_pend + 1 > RW_LIMIT) { redo_add(im); rw_cancel_drop(im, 1); }
                else if (nRW < NRW) { im->refs++; im->rw_pend++; RW[nRW++] = im; }
            }
        }
        else if (op < 95) {   // the next prepare drains redos then region copies
            for (int i = 0; i < nREDO; i++) { img *im = REDO[i]; if (!im || !im->cpu_dirty) continue; im->cpu_dirty = 0; }
            nREDO = 0;
            for (int i = 0; i < nRW; i++) { RW[i]->rw_pend--; unref(RW[i]); }
            nRW = 0;
        }
        else prune();
    }
    for (int i = 0; i < nREDO; i++) { img *im = REDO[i]; if (im) im->cpu_dirty = 0; }
    nREDO = 0;
    for (int i = 0; i < nRW; i++) { RW[i]->rw_pend--; unref(RW[i]); }
    nRW = 0;
    while (heldn) { img *o = held[--heldn]; unhold(o); unref(o); }
    for (int k = 0; k < NSER; k++) if (T[k].alive) release(k);
    while (g_nlinger) linger_drop_at(0);
    while (g_nlive) force(g_live[0]);
    g_last += 2; complete(g_last);
    if (nA) { printf("FAIL: %d images never destroyed (seed %u)\n", nA, seed); exit(1); }
}

int main(int argc, char **argv) {
    upload_then_release(0); upload_then_release(1);   // PEANUTS: image retirement covers the recording submission
    if (argc > 1 && !strcmp(argv[1], "old")) fixed = 0;
    if (argc > 1 && !strcmp(argv[1], "poolheld")) pool_held = 1;   // the pool drop as it was: the sanitizer stops at the present's drop of a freed image
    for (unsigned seed = 1; seed <= 12; seed++) run(seed, 60000);
    printf("t_android_imglife: ok%s\n", fixed && !pool_held ? "" : " (the old rules: the sanitizer should have stopped this)");
    return 0;
}
