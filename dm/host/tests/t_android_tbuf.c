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

    // PEANUTS: a storage buffer's (SSBO) stores, value bit 2 (DM_VK_TBUF_GW_SSBO; vka_tbuf.h). A model of the tracker's entries with their value bits and the two counts
    // vka_tex.c keeps (g_gw_tb, g_gw_ss: bits counted on their 0 -> 1 transition at an add or a merge, taken off when the entry is dropped)
    { typedef struct { uint64_t lo, hi, fence; uint32_t value; } ent;
      ent E[8]; int ne = 0, ctb = 0, css = 0; uint64_t S = 0x40920bb740ull, SN = 0x200020, T = S + 0x20, TN = 2u << 20;
      #define CNT(bits, d) do { uint32_t b_ = (bits); if (b_ & VKTB_GW_TB) ctb += (d); if (b_ & VKTB_GW_SSBO) css += (d); } while (0)
      #define ADD(a, n, v) do { int j_ = -1; for (int i_ = 0; i_ < ne; i_++) if (!E[i_].fence && E[i_].lo == (a) && E[i_].hi == (a) + (n)) j_ = i_; \
                                if (j_ >= 0) { CNT(vka_tbuf_gw_new_bits(E[j_].value, (v)), 1); E[j_].value |= (v); } else { E[ne++] = (ent){(a), (a) + (n), 0, (v)}; CNT(vka_tbuf_gw_new_bits(0, (v)), 1); } } while (0)
      #define DROP(dn) do { int k_ = 0; for (int i_ = 0; i_ < ne; i_++) { if (E[i_].fence && E[i_].fence <= (dn)) { CNT(vka_tbuf_gw_new_bits(0, E[i_].value), -1); continue; } E[k_++] = E[i_]; } ne = k_; } while (0)
      #define PEND(bit, a, n, dn) ({ int r_ = 0; for (int i_ = 0; i_ < ne && !r_; i_++) r_ = vka_tbuf_gw_pending(E[i_].lo, E[i_].hi, E[i_].fence, (E[i_].value & (bit)) != 0, (a), (a) + (n), (dn)); r_; })
      #define RECOUNT(bit) ({ int c_ = 0; for (int i_ = 0; i_ < ne; i_++) c_ += (E[i_].value & (bit)) != 0; c_; })
      uint64_t dn = 30;
      // the bits
      CHECK(vka_tbuf_gw_new_bits(0, VKTB_GW_SSBO) == VKTB_GW_SSBO && vka_tbuf_gw_new_bits(VKTB_GW_SSBO, VKTB_GW_SSBO) == 0, "bit 2 is new once");
      CHECK(vka_tbuf_gw_new_bits(VKTB_GW_SSBO, VKTB_GW_TB) == VKTB_GW_TB && vka_tbuf_gw_new_bits(VKTB_GW_TB | VKTB_GW_SSBO, 3) == 0, "bit 1 on an SSBO entry is new; both held: nothing new");
      CHECK(vka_tbuf_gw_new_bits(0, 0) == 0 && vka_tbuf_gw_new_bits(0, 4) == 0, "a plain opaque write (and unknown bits) counts in neither");
      // the light list's writer: bit 2 only. Never pending for the texel buffer path (tbuf_view asks for bit 1: vka_tex_gw_tbuf_pending), pending for the image choice until done
      ADD(S, SN, VKTB_GW_SSBO);
      CHECK(ctb == 0 && css == 1, "an SSBO write: counted in g_gw_ss only (%d %d)", ctb, css);
      CHECK(!PEND(VKTB_GW_TB, T, TN, dn), "an SSBO write is never pending for a texel buffer");
      CHECK(PEND(VKTB_GW_SSBO, T, TN, dn), "pending for the image choice while the submission is recorded");
      for (int i = 0; i < ne; i++) if (!E[i].fence) E[i].fence = 31;
      CHECK(PEND(VKTB_GW_SSBO, T, TN, dn) && !PEND(VKTB_GW_TB, T, TN, dn), "queued, not done: still pending (image), still not (texel buffer)");
      { int cap; CHECK(vka_tbuf_img_choice(1, 1, 1, 1, vka_tbuf_img_gw(0, 1, TN, PEND(VKTB_GW_SSBO, T, TN, dn), &cap), 1, 1) && cap, "switch on, pending, 2 MiB: the GPU's copy");
        CHECK(!vka_tbuf_img_choice(1, 1, 1, 1, vka_tbuf_img_gw(0, 0, TN, PEND(VKTB_GW_SSBO, T, TN, dn), &cap), 1, 1), "switch off: the row image, as before"); }
      dn = 31; DROP(dn);
      CHECK(!PEND(VKTB_GW_SSBO, T, TN, dn) && ne == 0 && ctb == 0 && css == 0, "done and dropped: not pending, counts back to 0 (%d %d)", ctb, css);
      { int cap; CHECK(!vka_tbuf_img_choice(1, 1, 1, 1, vka_tbuf_img_gw(0, 1, TN, PEND(VKTB_GW_SSBO, T, TN, dn), &cap), 1, 1), "done: the row image (uploaded again by the prepare)"); }
      // one entry with both bits (a storage texel buffer and a storage buffer over the same range in one submission, in either order): counts balanced through add, merge, drop
      ADD(S, SN, VKTB_GW_SSBO); ADD(S, SN, VKTB_GW_TB); ADD(S, SN, VKTB_GW_SSBO); ADD(S, SN, 0);
      CHECK(ne == 1 && E[0].value == (VKTB_GW_TB | VKTB_GW_SSBO) && ctb == 1 && css == 1, "merged: one entry, both bits, each counted once (%d %d)", ctb, css);
      CHECK(PEND(VKTB_GW_TB, T, TN, dn) && PEND(VKTB_GW_SSBO, T, TN, dn), "both questions see it");
      ADD(0x50000000ull, 4096, VKTB_GW_TB); ADD(0x60000000ull, 4096, 0); ADD(0x60000000ull, 4096, VKTB_GW_SSBO);
      CHECK(ctb == RECOUNT(VKTB_GW_TB) && css == RECOUNT(VKTB_GW_SSBO) && ctb == 2 && css == 2, "counts equal a recount (%d %d)", ctb, css);
      for (int i = 0; i < ne; i++) if (!E[i].fence) E[i].fence = 32;
      dn = 32; DROP(dn);
      CHECK(ne == 0 && ctb == 0 && css == 0, "all done: counts back to 0 (%d %d)", ctb, css);
      #undef CNT
      #undef ADD
      #undef DROP
      #undef PEND
      #undef RECOUNT
    }
    // which storage buffer writes are kept as SSBO writes: the switch on, a bounded range of up to 64 MiB (one bound without a size covers the whole storage range)
    CHECK(!vka_tbuf_ssbo_mark(0, 0x200020), "switch off: a plain opaque write, as before");
    CHECK(vka_tbuf_ssbo_mark(1, 0x200020) && vka_tbuf_ssbo_mark(1, VKTB_SSBO_RANGE_MAX), "switch on, bounded: an SSBO write");
    CHECK(!vka_tbuf_ssbo_mark(1, VKTB_SSBO_RANGE_MAX + 1) && !vka_tbuf_ssbo_mark(1, ~0ull) && !vka_tbuf_ssbo_mark(1, 0), "over 64 MiB, VK_WHOLE_SIZE, empty: a plain opaque write");
    // the texture size cap and the inputs of the image choice's gw
    for (int m = 0; m < 16; m++) {
        int tb = m & 1, sw = m >> 1 & 1, ssp = m >> 2 & 1, big = m >> 3 & 1, cap = -1; uint64_t bytes = big ? VKTB_SSBO_IMG_MAX + 4 : VKTB_SSBO_IMG_MAX;
        int g = vka_tbuf_img_gw(tb, sw, bytes, ssp, &cap);
        CHECK(cap == !big, "cap %d for %llu bytes", cap, (unsigned long long)bytes);
        if (tb) CHECK(g, "a storage texel buffer's stores: the copy as before, whatever the SSBO rule (%#x)", m);
        if (!tb) CHECK(g == (sw && ssp && !big), "no texel buffer stores: only switch on, pending and within the cap (%#x)", m);
    }
    CHECK(!vka_tbuf_img_gw(0, 1, 0, 1, NULL), "an empty texture never takes the SSBO rule");

    // PEANUTS: the switch's value: DM_VK_TBUF_GW_SSBO when set, else the profile's tbuf_ssbo_copy (default off). The rule VKA_ENV applies (strtod, nonzero = on; unset or empty = default)
    { static const char *const envs[] = {NULL, "", "0", "1", "0.0", "2", "off"};   // "off" is not a number: strtod gives 0, as VKA_ENV does
      static const int want_set[] = {-1, -1, 0, 1, 0, 1, 0};
      for (unsigned e = 0; e < sizeof envs / sizeof *envs; e++) for (int prof = 0; prof < 2; prof++) {
          int got = vka_tbuf_ssbo_eff(envs[e], prof), want = want_set[e] < 0 ? prof : want_set[e];
          CHECK(got == want, "env %s profile %d: %d, want %d", envs[e] ? envs[e] : "(unset)", prof, got, want);
      }
      CHECK(!vka_tbuf_ssbo_eff(NULL, 0) && vka_tbuf_ssbo_eff(NULL, 1), "unset: the profile decides, off by default (another game's profile has no key)");
      CHECK(!vka_tbuf_ssbo_eff("0", 1) && vka_tbuf_ssbo_eff("1", 0), "a set variable overrides the profile, both ways");
      CHECK(vka_tbuf_ssbo_mark(vka_tbuf_ssbo_eff(NULL, 1), 4096) && !vka_tbuf_ssbo_mark(vka_tbuf_ssbo_eff(NULL, 0), 4096) && !vka_tbuf_ssbo_mark(vka_tbuf_ssbo_eff("0", 1), 4096),
            "the effective value is what the write marking and the image choice take: profile on = kept as an SSBO write, off or overridden off = plain opaque write");
      int cap; CHECK(vka_tbuf_img_gw(0, vka_tbuf_ssbo_eff(NULL, 1), (2u << 20), 1, &cap) && !vka_tbuf_img_gw(0, vka_tbuf_ssbo_eff(NULL, 0), (2u << 20), 1, &cap) && !vka_tbuf_img_gw(0, vka_tbuf_ssbo_eff("0", 1), (2u << 20), 1, &cap),
            "profile on: the GPU copy for a pending SSBO write; profile off (the default) or the variable 0: the row image"); }

    // PEANUTS: The copy kept for the submission (vka_tbuf_copy_live): Hogwarts Legacy's frame, three submissions whose fences the worker reserved before the first is recorded, so
    // vka_fence_last() + 1 is the same in all three (the key the copy was matched by before): a copy made in the first is not bound in the second or third
    { uint64_t fence_last = 7581, sub = 100, made_key_old = fence_last + 1, made_sub = sub;
      CHECK(vka_tbuf_copy_live(made_sub, sub), "a copy is bound again in the submission that made it");
      for (int k = 1; k < 3; k++) { sub++;
          CHECK(made_key_old == fence_last + 1, "the old key cannot tell submission %d of the frame from the first", k + 1);
          CHECK(!vka_tbuf_copy_live(made_sub, sub), "a copy of the frame's first submission is not bound in submission %d", k + 1); } }
    // PEANUTS: the scratch copy of a compute shader's storage texel buffer the device cannot view where it is (vka_tbuf.h vka_tbuf_store_*; vka_draw.c tbuf_store_view)
    // The switch's value: a set variable wins over the profile both ways, unset the profile decides, the default (profile off) is off
    CHECK(!vka_tbuf_store_eff(0, 0, 0) && !vka_tbuf_store_eff(0, 1, 0), "unset, profile off: off (the variable's value is not read)");
    CHECK(vka_tbuf_store_eff(0, 0, 1) && vka_tbuf_store_eff(0, 1, 1), "unset, profile on: on");
    CHECK(vka_tbuf_store_eff(1, 1, 0) && !vka_tbuf_store_eff(1, 0, 1) && !vka_tbuf_store_eff(1, 0, 0) && vka_tbuf_store_eff(1, 1, 1), "a set variable wins over the profile, both ways");
    // The choice, over every combination: 1 only for a storage texel buffer of a dispatch, placed and unaligned, of 1 .. VKTB_STORE_MAX bytes, a multiple of its texel, switch on
    { static const uint64_t bs[] = {0, 4, 32, 208, 4096, VKTB_STORE_MAX, VKTB_STORE_MAX + 4}; static const uint32_t ts[] = {0, 4, 16}; int on = 0, tot = 0;
      for (int sw = 0; sw < 2; sw++) for (int st = 0; st < 2; st++) for (int d = 0; d < 2; d++) for (int pl = 0; pl < 2; pl++) for (int al = 0; al < 2; al++)
      for (unsigned bi = 0; bi < sizeof bs / sizeof *bs; bi++) for (unsigned ti = 0; ti < sizeof ts / sizeof *ts; ti++) {
          uint64_t b = bs[bi]; uint32_t t = ts[ti];
          int want = sw && st && d && pl && !al && b && b <= VKTB_STORE_MAX && t && b % t == 0;
          int got = vka_tbuf_store_choice(sw, st, d, pl, al, b, t); tot++; on += got;
          CHECK(got == want, "choice sw %d storage %d dispatch %d placed %d aligned %d bytes %llu texel %u: %d, expected %d", sw, st, d, pl, al, (unsigned long long)b, t, got, want);
          if (!sw) CHECK(!got, "switch off: never");
      }
      CHECK(on > 0 && on < tot, "some combinations choose it (%d of %d)", on, tot);
      CHECK(vka_tbuf_store_choice(1, 1, 1, 1, 0, 208, 4) && vka_tbuf_store_choice(1, 1, 1, 1, 0, 32, 16), "the two Kingdom Battle bindings (208 bytes of R32, 32 of RGBA32F)");
      CHECK(!vka_tbuf_store_choice(1, 1, 1, 1, 0, 6, 4), "a size that is not a multiple of the texel is not copied"); }
    // The read path is untouched by the switch: vka_tbuf_path never looks at it (an unaligned storage texel buffer is still empty there)
    CHECK(vka_tbuf_path(1, 208, 1, 0, 1, 0, 0) == VKTB_NONE, "an unaligned storage texel buffer is empty in vka_tbuf_path as before");
    // The alias rule
    #define AL(a, n, f, rw, oa, on, of, orw, os) vka_tbuf_store_alias(a, n, f, rw, oa, on, of, orw, os)
    CHECK(AL(0x1000, 208, 100, 1, 0x2000, 64, 100, 1, 1) == VKTB_AL_NONE && AL(0x1000, 208, 100, 1, 0x10d0, 64, 100, 1, 1) == VKTB_AL_NONE, "disjoint (and adjacent) ranges do not alias");
    CHECK(AL(0x1000, 208, 100, 1, 0x1000, 208, 100, 0, 1) == VKTB_AL_SAME, "the same bytes in the same format of another storage texel buffer share the copy");
    CHECK(AL(0x1000, 208, 100, 1, 0x1000, 208, 100, 0, 0) == VKTB_AL_REFUSE, "the same bytes through a read-only uniform texel buffer, this one storing: refused (that one would read the old bytes)");
    CHECK(AL(0x1000, 208, 100, 0, 0x1000, 208, 100, 0, 0) == VKTB_AL_NOTE, "read-only both ways: a note only");
    CHECK(AL(0x1000, 208, 100, 0, 0x1000, 208, 98, 0, 1) == VKTB_AL_REFUSE && AL(0x1000, 208, 100, 0, 0x1000, 208, 98, 0, 0) == VKTB_AL_REFUSE, "another format of the same bytes is refused");
    CHECK(AL(0x1000, 208, 100, 1, 0x1040, 64, -1, 0, 0) == VKTB_AL_REFUSE, "a partial overlap with a binding that is no texel buffer view: refused when this one stores");
    CHECK(AL(0x1000, 208, 100, 0, 0x1040, 64, -1, 0, 0) == VKTB_AL_NOTE, "a read-only overlap on both sides: noted, not refused");
    CHECK(AL(0x1000, 208, 100, 0, 0x1040, 64, -1, 1, 0) == VKTB_AL_REFUSE, "an overlap with a binding that stores: refused");
    CHECK(AL(0x1000, 0, 100, 1, 0x1000, 8, 100, 1, 1) == VKTB_AL_NONE && AL(0x1000, 8, 100, 1, 0x1000, 0, 100, 1, 1) == VKTB_AL_NONE, "an empty range overlaps nothing");
    #undef AL
    // The registry: the Kingdom Battle case (38c757aa0b2d5dea binding 5: 0x4096bb7960, 208 bytes, VkFormat 100; 6df283f716e87420 binding 2: 0x4096258ea0, 32 bytes, VkFormat 109, in other dispatches)
    { vktb_store R; vktb_store_reset(&R); CHECK(R.n == 0, "empty");
      int a = vktb_store_add(&R, 0x4096bb7960ull, 208, 100, 1, 0);
      CHECK(a == 0 && vktb_store_find(&R, 0x4096bb7960ull, 208, 100) == 0 && vktb_store_find(&R, 0x4096bb7960ull, 208, 109) < 0 && vktb_store_find(&R, 0x4096bb7960ull, 204, 100) < 0, "found by bytes, size and format");
      CHECK(vktb_store_add(&R, 0x4096bb7960ull + 208, 16, 100, 1, 256) == 1, "a disjoint range is a separate entry");
      CHECK(vktb_store_add(&R, 0x4096bb7960ull + 100, 64, 100, 1, 512) == -2 && R.n == 2, "a partial overlap with a binding that stores: refused, nothing added");
      CHECK(vktb_store_add(&R, 0x4096bb7960ull, 208, 109, 0, 512) == -2, "the same bytes in another format: refused");
      CHECK(R.e[0].rw == 1 && R.e[0].soff == 0 && R.e[1].soff == 256, "entries keep their offsets and the store flag");
      vktb_store_reset(&R); CHECK(R.n == 0 && vktb_store_find(&R, 0x4096bb7960ull, 208, 100) < 0, "reset empties it");
      CHECK(vktb_store_add(&R, 0x4096258ea0ull, 32, 109, 1, 0) == 0, "the second Kingdom Battle binding, in its own dispatch");
      vktb_store_reset(&R);
      for (int i = 0; i < VKTB_STORE_N; i++) CHECK(vktb_store_add(&R, 0x10000ull + 0x1000ull * i, 32, 109, 1, 64u * i) == i, "entry %d", i);
      CHECK(vktb_store_add(&R, 0x90000ull, 32, 109, 1, 0) == -1 && R.n == VKTB_STORE_N, "a full registry refuses (-1)");
      vktb_store_reset(&R);
      CHECK(vktb_store_add(&R, 0x4096bb7960ull, 208, 100, 0, 0) == 0 && vktb_store_add(&R, 0x4096bb7960ull + 4, 8, 100, 0, 256) == 1, "read-only overlaps are both kept (a note in the replay)"); }
    // The offsets in the slot's scratch buffer: a multiple of the device's alignment (64) and of 16, never overlapping the previous copy, refused past the slot
    { uint32_t used = 0, off = 0, prev_end = 0; int okn = 0;
      static const uint64_t ns[] = {208, 32, 4, 64, 65, 1000, 16};
      for (unsigned i = 0; i < sizeof ns / sizeof *ns; i++) {
          int ok = vka_tbuf_store_off(used, 64, ns[i], VKTB_STORE_SLOT, &off);
          CHECK(ok && off % 64 == 0 && off % 16 == 0 && off >= prev_end && off + ns[i] <= VKTB_STORE_SLOT, "copy %u at %u (used %u)", i, off, used);
          if (ok) { used = off + (uint32_t)ns[i]; prev_end = used; okn++; } }
      CHECK(okn == 7, "all of them fit a slot");
      uint32_t o2 = 77; CHECK(vka_tbuf_store_off(0, 64, VKTB_STORE_SLOT, VKTB_STORE_SLOT, &o2) && o2 == 0, "a copy of the whole slot fits at 0");
      o2 = 77; CHECK(!vka_tbuf_store_off(1, 64, VKTB_STORE_SLOT, VKTB_STORE_SLOT, &o2) && o2 == 77, "past the slot: refused, the offset is left alone");
      CHECK(!vka_tbuf_store_off(VKTB_STORE_SLOT - 32, 64, 4, VKTB_STORE_SLOT, &o2), "the last bytes, rounded up past the slot: refused");
      CHECK(vka_tbuf_store_off(1, 4, 4, VKTB_STORE_SLOT, &o2) && o2 == 16, "an alignment under 16 is raised to 16");
      CHECK(!vka_tbuf_store_off(0, 64, 0, VKTB_STORE_SLOT, &o2), "no bytes: no copy"); }
    if (fails) { printf("t_android_tbuf: %d FAILED\n", fails); return 1; }
    printf("t_android_tbuf: ok (%d choices; the pending writes; the image of a buffer texture and a draw's answers; the copy kept for a submission; the scratch copy of an unaligned storage texel buffer: the switch, the choice, the alias rule, the registry, the offsets)\n", n);
    return 0;
}
