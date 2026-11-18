// PEANUTS: how a texel buffer is read (dm/gfx/vk_android/vka_tbuf.h; vka_draw.c tbuf_view): the choice between a view in place, the CPU's copy and a copy the GPU makes,
// for every combination of its inputs against the rules written out here; without a pending GPU write (or with DM_VK_TBUF_GW=0) it is the choice the replay made
// before; then which writes count as pending, over a small model of the tracker (vka_tex.c g_gw): the sequence of Final Fantasy Resonance's first frame of a scene
// (a clear and a count through storage texel buffers, then the read of the counts in the same submission), the frames after it, and the writes that must not count;
// then which image a buffer texture read through an image binding takes (vka_tbuf_img_choice) and how one draw keeps its answer (vktb_memo)
#include <stdio.h>
#include <stdint.h>
#include "../../gfx/vk_android/vka_tbuf.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// The replay's choice before DM_VK_TBUF_GW (vka_draw.c as it was): the CPU's copy for a read-only buffer of up to 1 MiB that cannot be viewed in place or when
// DM_VK_TBUF_COPY is on; else in place when it can be viewed there; else empty
static int before(int storage, uint64_t bytes, int placed, int aligned, int copy_sw) {
    if ((!placed || !aligned || copy_sw) && !storage && bytes && bytes <= (1u << 20)) return VKTB_CPU;
    return placed && aligned ? VKTB_PLACE : VKTB_NONE;
}

// The tracker's writes: range, fence (0 until the submission commits), tb
typedef struct { uint64_t lo, hi, fence; int tb; } wr;
static wr W[16]; static int nw; static uint64_t done_;
static void mark(uint64_t a, uint64_t n, int tb) { W[nw++] = (wr){a, a + n, 0, tb}; }
static void commit(uint64_t fence) { for (int i = 0; i < nw; i++) if (!W[i].fence) W[i].fence = fence; }
static int pending(uint64_t a, uint64_t n) { int r = 0; for (int i = 0; i < nw && !r; i++) r = vka_tbuf_gw_pending(W[i].lo, W[i].hi, W[i].fence, W[i].tb, a, a + n, done_); return r; }

int main(void) {
    static const uint64_t sizes[] = {0, 4, 8192, 1u << 20, (1u << 20) + 4, 64u << 20};
    int n = 0;
    for (int storage = 0; storage < 2; storage++) for (unsigned s = 0; s < sizeof sizes / sizeof *sizes; s++) for (int placed = 0; placed < 2; placed++)
    for (int aligned = 0; aligned < 2; aligned++) for (int copy_sw = 0; copy_sw < 2; copy_sw++) for (int gw = 0; gw < 2; gw++) for (int in_pass = 0; in_pass < 2; in_pass++) {
        uint64_t bytes = sizes[s];
        int p = vka_tbuf_path(storage, bytes, placed, aligned, copy_sw, gw, in_pass), was = before(storage, bytes, placed, aligned, copy_sw); n++;
        #define CASE "storage %d bytes %llu placed %d aligned %d copy %d gw %d pass %d: %d", storage, (unsigned long long)bytes, placed, aligned, copy_sw, gw, in_pass, p
        // no pending GPU write: exactly as before, whatever else
        if (!gw) CHECK(p == was, CASE);
        // a storage binding is never a copy (its stores must land in guest memory); a buffer over 1 MiB and an empty one neither
        if (storage || !bytes || bytes > (1u << 20)) CHECK(p == VKTB_PLACE || p == VKTB_NONE, CASE);
        if (storage || !bytes || bytes > (1u << 20)) CHECK(p == was, CASE);
        // a view in place only where the device can make one; a copy on the GPU only of placed memory (it copies from the guest memory buffer) and never inside a pass
        if (p == VKTB_PLACE) CHECK(placed && aligned, CASE);
        if (p == VKTB_GPU) CHECK(gw && placed && !aligned && !in_pass && !storage, CASE);
        // a pending GPU write over a read-only buffer of up to 1 MiB: never the CPU's copy where anything else is possible, and the copy switch does not matter
        if (gw && !storage && bytes && bytes <= (1u << 20)) {
            if (placed && aligned) CHECK(p == VKTB_PLACE, CASE);
            else if (placed && !in_pass) CHECK(p == VKTB_GPU, CASE);
            else CHECK(p == VKTB_CPU, CASE);
            CHECK(p == vka_tbuf_path(storage, bytes, placed, aligned, !copy_sw, gw, in_pass), CASE);
        }
        // read-only, up to 1 MiB: never empty
        if (!storage && bytes && bytes <= (1u << 20)) CHECK(p != VKTB_NONE, CASE);
        #undef CASE
    }
    // The cases by name
    CHECK(vka_tbuf_path(0, 8192, 1, 1, 1, 0, 0) == VKTB_CPU, "a CPU-written buffer keeps its copy");
    CHECK(vka_tbuf_path(0, 8192, 1, 1, 1, 1, 0) == VKTB_PLACE, "GPU-written, aligned, a dispatch: in place");
    CHECK(vka_tbuf_path(0, 8192, 1, 1, 1, 1, 1) == VKTB_PLACE, "GPU-written, aligned, a draw: in place");
    CHECK(vka_tbuf_path(0, 8192, 1, 0, 1, 1, 0) == VKTB_GPU, "GPU-written, misaligned, a dispatch: the GPU's copy");
    CHECK(vka_tbuf_path(0, 8192, 1, 0, 1, 1, 1) == VKTB_CPU, "GPU-written, misaligned, a draw: the CPU's copy");
    CHECK(vka_tbuf_path(0, 8192, 0, 1, 0, 1, 0) == VKTB_CPU, "GPU-written, not in placed memory: the CPU's copy");
    CHECK(vka_tbuf_path(1, 8192, 1, 0, 1, 1, 0) == VKTB_NONE, "a misaligned storage texel buffer stays empty");

    // The tracker. The buffer: 2,048 words at 0x10000; the newest completed fence is 6, the submission being recorded will be 8
    uint64_t X = 0x10000, XN = 8192; done_ = 6;
    CHECK(!pending(X, XN), "nothing recorded: not pending");
    mark(0x40000, 4096, 1);                      // another buffer's stores
    CHECK(!pending(X, XN), "another range");
    mark(X, XN, 0);                              // a storage buffer's (or a copy's) opaque write over it: not counted (vka_tbuf.h)
    CHECK(!pending(X, XN), "an opaque write that is not a storage texel buffer's");
    mark(X, XN, 1);                              // the clear dispatch, then the counting dispatches, in the submission being recorded
    CHECK(pending(X, XN), "stores recorded in this submission");
    CHECK(pending(X + 4096, 4) && pending(X - 4, 8) && pending(X + XN - 4, 64), "an overlap at either end, or inside");
    CHECK(!pending(X + XN, 64) && !pending(X - 64, 64), "the ranges beside it");
    commit(8);
    CHECK(pending(X, XN), "the submission is queued, not done (the next frame is being recorded)");
    done_ = 7; CHECK(pending(X, XN), "an earlier submission is done");
    done_ = 8; CHECK(!pending(X, XN), "its submission is done: the CPU has the bytes, the copy is right again");
    mark(X, XN, 1); done_ = 9;                   // the next frame's stores: fence 0 is the submission being recorded, whatever is done
    CHECK(pending(X, XN), "the next frame's stores");
    commit(10); done_ = 10;
    CHECK(!pending(X, XN), "and done");

    // ---- A buffer texture read through an image binding (vka_draw.c tbuf_img_gpu): the row image of the CPU's bytes, or the copy the GPU makes
    int nc = 0;
    for (int m = 0; m < 128; m++) {
        int sw = m & 1, shape = m >> 1 & 1, cls = m >> 2 & 1, fits = m >> 3 & 1, gw = m >> 4 & 1, placed = m >> 5 & 1, grid = m >> 6 & 1, c = vka_tbuf_img_choice(sw, shape, cls, fits, gw, placed, grid); nc++;
        if (!gw) CHECK(!c, "no pending store over the range: the row image, whatever else (%#x)", m);
        if (!sw || !shape || !cls || !fits || !placed || !grid) CHECK(!c, "a copy that cannot be made or read is never chosen (%#x)", m);
        if (m == 127) CHECK(c, "a pending store, and the copy can be made and read: the copy");
    }
    // The answers of one draw. The tracker's answer is latched at the submission's prepare (done_ here: vka_tex.c g_gw_done), so between two asks of one draw it
    // can only go from 0 to 1, by a store an earlier binding of the same draw records; the first answer holds
    vktb_memo M; vktb_memo_reset(&M); nw = 0; done_ = 20;
    #define ASK(tid, dim, a, n) ({ int k_ = vktb_memo_find(&M, tid, dim); if (k_ < 0) k_ = vktb_memo_put(&M, tid, dim, vka_tbuf_img_choice(1, 1, 1, 1, pending(a, n), 1, 1)); k_ >= 0 && M.e[k_].gpu; })
    CHECK(!ASK(7, 1, X, XN), "the draw's first question, nothing pending: the row image");
    mark(X, XN, 1);                              // the same draw's storage texel buffer binding records its stores
    CHECK(pending(X, XN) && !ASK(7, 1, X, XN), "asked again after the draw's own store: still the row image (no copy was made for this draw)");
    CHECK(!vktb_memo_bind_gpu(&M, 7, 1), "and nothing binds the copy");
    CHECK(ASK(7, 0, X, XN), "another shape of the same texture is another question");
    CHECK(!vktb_memo_bind_gpu(&M, 7, 0), "a copy chosen but not made for this draw is not bound (an older copy holds another frame's bytes)");
    vktb_memo_fresh(&M, 7, 0); CHECK(vktb_memo_bind_gpu(&M, 7, 0), "made, or found current, when the draw's images were made: bound");
    vktb_memo_fresh(&M, 7, 1); CHECK(!vktb_memo_bind_gpu(&M, 7, 1), "the row image's entry never binds the copy");
    vktb_memo_reset(&M);                         // the next draw of the same submission
    CHECK(ASK(7, 1, X, XN) && !vktb_memo_bind_gpu(&M, 7, 1), "the next draw asks anew: the store is pending, the copy is chosen and must be made first");
    commit(21); done_ = 20;                      // the watcher completes fence 21 while this submission is recorded: done_ is the prepare's reading, unchanged
    vktb_memo_reset(&M); CHECK(ASK(7, 1, X, XN), "a fence completed meanwhile changes nothing until the next prepare");
    done_ = 21; nw = 0;                          // the next prepare: the write is dropped, the textures over it are uploaded again
    vktb_memo_reset(&M); CHECK(!ASK(7, 1, X, XN), "after that prepare: the row image, uploaded again by it");
    vktb_memo_reset(&M);
    for (int i = 0; i < VKTB_MEMO_N; i++) CHECK(vktb_memo_put(&M, 100 + i, 1, 1) == i, "the memo holds %d answers", VKTB_MEMO_N);
    mark(X, XN, 1); CHECK(!ASK(7, 1, X, XN) && !vktb_memo_bind_gpu(&M, 7, 1), "a full memo answers the row image for one more");
    CHECK(vktb_memo_find(&M, 100 + VKTB_MEMO_N - 1, 1) == VKTB_MEMO_N - 1 && vktb_memo_find(&M, 100, 0) < 0, "found by texture ID and shape");
    #undef ASK
    (void)nc;

    if (fails) { printf("t_android_tbuf: %d FAILED\n", fails); return 1; }
    printf("t_android_tbuf: ok (%d choices; the pending writes; the image of a buffer texture and a draw's answers)\n", n);
    return 0;
}
