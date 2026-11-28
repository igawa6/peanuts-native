// PEANUTS: coalesced inline uniform buffer updates (dm/gfx/vk_android/vka_ub_defer.h).
#include <assert.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include "../../gfx/vk_android/vka_ub_defer.h"

static void apply_ref(uint8_t *ref, uint64_t base, uint64_t dst, uint64_t n, const uint8_t *data) {
    memcpy(ref + (dst - base), data, (size_t)n);
}

static void check_matches(const vka_ub_q *q, const uint8_t *ref, uint64_t base, size_t len) {
    uint8_t applied[512];
    assert(len <= sizeof applied);
    // Start from 0xA5 pattern to verify vka_ub_apply overwrites every updated byte identically
    memset(applied, 0xA5, len);
    vka_ub_apply(q, base, applied, len);
    assert(memcmp(applied, ref, len) == 0);
}

int main(void) {
    enum { BASE = 1000, LEN = 256 };
    uint8_t ref[LEN];
    vka_ub_q q = {0};

    // 1. Downward extension via memmove (overlapping and head-adjacent) + exact repeat skip
    memset(ref, 0xA5, LEN);
    uint8_t a1[16];
    for (int i = 0; i < 16; i++) a1[i] = (uint8_t)(0x10 + i);
    int ch = -1;
    assert(vka_ub_defer(&q, BASE + 100, 16, a1, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 100, 16, a1);
    assert(q.n == 1 && q.e[0].dst == BASE + 100 && q.e[0].n == 16);
    check_matches(&q, ref, BASE, LEN);

    // Exact repeat of a sub-slice inside E0 is skipped (changed == 0)
    assert(vka_ub_defer(&q, BASE + 104, 8, a1 + 4, &ch) == 1 && ch == 0);
    assert(q.n == 1 && q.e[0].dst == BASE + 100 && q.e[0].n == 16);
    check_matches(&q, ref, BASE, LEN);

    // Extend E0 downward with overlap: [BASE+92, BASE+104) overlaps [BASE+100, BASE+116)
    uint8_t a2[12];
    for (int i = 0; i < 12; i++) a2[i] = (uint8_t)(0x40 + i);
    assert(vka_ub_defer(&q, BASE + 92, 12, a2, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 92, 12, a2);
    assert(q.n == 1 && q.e[0].dst == BASE + 92 && q.e[0].n == 24 && q.lo == BASE + 92 && q.hi == BASE + 116);
    check_matches(&q, ref, BASE, LEN);

    // Extend E0 downward head-adjacent: [BASE+80, BASE+92) touches BASE+92
    uint8_t a3[12];
    for (int i = 0; i < 12; i++) a3[i] = (uint8_t)(0x60 + i);
    assert(vka_ub_defer(&q, BASE + 80, 12, a3, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 80, 12, a3);
    assert(q.n == 1 && q.e[0].dst == BASE + 80 && q.e[0].n == 36 && q.lo == BASE + 80 && q.hi == BASE + 116);
    check_matches(&q, ref, BASE, LEN);

    // Grow past initial 256-byte capacity while extending downward via memmove
    uint8_t big1[128], big2[128];
    memset(big1, 0x33, sizeof big1);
    memset(big2, 0x77, sizeof big2);
    vka_ub_reset(&q);
    memset(ref, 0xA5, LEN);
    assert(vka_ub_defer(&q, BASE + 128, 128, big1, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 128, 128, big1);
    assert(vka_ub_defer(&q, BASE + 0, 128, big2, &ch) == 1 && ch == 1); // adjacent, total 256
    apply_ref(ref, BASE, BASE + 0, 128, big2);
    assert(q.n == 1 && q.e[0].dst == BASE && q.e[0].n == 256);
    check_matches(&q, ref, BASE, LEN);
    uint8_t big3[32];
    memset(big3, 0x99, sizeof big3);
    // Extend below BASE (at BASE - 16, size 32) so total becomes 272 > 256 (triggers realloc + memmove)
    uint8_t ref2[512];
    memset(ref2, 0xA5, sizeof ref2);
    memcpy(ref2 + 16, ref, LEN);
    memcpy(ref2, big3, 32);
    assert(vka_ub_defer(&q, BASE - 16, 32, big3, &ch) == 1 && ch == 1);
    assert(q.n == 1 && q.e[0].dst == BASE - 16 && q.e[0].n == 272);
    check_matches(&q, ref2, BASE - 16, 272);

    // 2. Overlap with a newer entry in between:
    // E0 = [BASE+10, BASE+30)
    // E1 = [BASE+50, BASE+70)
    // E2 = [BASE+20, BASE+60): overlaps E1 (and E0), so it stops at i=1 and merges into E1 -> [BASE+20, BASE+70), leaving E0 intact.
    // Then write [BASE+12, BASE+18) with the SAME bytes as E0: since E1=[BASE+20, BASE+70) does not intersect [BASE+12, BASE+18),
    // the scan reaches E0 and skips the repeat!
    // Then write [BASE+15, BASE+25) with E0's original bytes at [15,20) and new bytes at [20,25): hits E1 (newer!), merges into E1 -> [BASE+15, BASE+70).
    // Verify E1 still overrides E0 on [15,30) in vka_ub_apply.
    vka_ub_reset(&q);
    memset(ref, 0xA5, LEN);
    uint8_t e0[20], e1[20], e2[40], e3[10];
    for (int i = 0; i < 20; i++) { e0[i] = (uint8_t)(0x20 + i); e1[i] = (uint8_t)(0x80 + i); }
    for (int i = 0; i < 40; i++) e2[i] = (uint8_t)(0xC0 + i);
    for (int i = 0; i < 10; i++) e3[i] = (uint8_t)(0xE0 + i);

    assert(vka_ub_defer(&q, BASE + 10, 20, e0, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 10, 20, e0);
    assert(vka_ub_defer(&q, BASE + 50, 20, e1, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 50, 20, e1);
    assert(q.n == 2);

    // Disjoint-from-E1 write that matches E0's bytes is skipped
    assert(vka_ub_defer(&q, BASE + 12, 6, e0 + 2, &ch) == 1 && ch == 0);
    assert(q.n == 2);

    // Write overlapping both E0 and E1 merges into E1 (the newest overlapping entry)
    assert(vka_ub_defer(&q, BASE + 20, 40, e2, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 20, 40, e2);
    assert(q.n == 2 && q.e[0].dst == BASE + 10 && q.e[0].n == 20 && q.e[1].dst == BASE + 20 && q.e[1].n == 50);
    check_matches(&q, ref, BASE, LEN);

    // Write inside E0's range [15,25) that also touches E1=[20,70): must NOT skip or merge into E0; merges into E1
    assert(vka_ub_defer(&q, BASE + 15, 10, e3, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 15, 10, e3);
    assert(q.n == 2 && q.e[1].dst == BASE + 15 && q.e[1].n == 55);
    check_matches(&q, ref, BASE, LEN);

    // Now E1=[15,70) shadows E0's [15,30): an update on [15,25) matching E0's *old* bytes is compared against E1 (differs) and updates E1, not E0!
    assert(vka_ub_defer(&q, BASE + 15, 10, e0 + 5, &ch) == 1 && ch == 1);
    apply_ref(ref, BASE, BASE + 15, 10, e0 + 5);
    check_matches(&q, ref, BASE, LEN);

    // 3. Overlap test & bounded range check
    assert(vka_ub_overlaps(&q, BASE + 5, 5) == 0);
    assert(vka_ub_overlaps(&q, BASE + 5, 6) == 1);
    assert(vka_ub_overlaps(&q, BASE + 70, 10) == 0);
    assert(vka_ub_overlaps(&q, BASE + 69, UINT64_MAX - (BASE + 69) + 1) == 1);

    // 4. Queue capacity limit (VKA_UB_MAX disjoint entries -> 257th returns 0)
    vka_ub_reset(&q);
    uint8_t byte = 0x55;
    for (int i = 0; i < VKA_UB_MAX; i++) {
        assert(vka_ub_defer(&q, BASE + (uint64_t)i * 4, 1, &byte, &ch) == 1 && ch == 1);
    }
    assert(q.n == VKA_UB_MAX);
    assert(vka_ub_defer(&q, BASE + (uint64_t)VKA_UB_MAX * 4, 1, &byte, &ch) == 0);

    // 5. Differential fuzz across random overlapping/adjacent/repeat updates
    uint32_t rng = 0x12345678u;
    for (int trial = 0; trial < 200; trial++) {
        vka_ub_reset(&q);
        memset(ref, 0xA5, LEN);
        for (int step = 0; step < 64; step++) {
            rng = rng * 1664525u + 1013904223u;
            uint64_t off = (rng >> 16) % (LEN - 1);
            rng = rng * 1664525u + 1013904223u;
            uint64_t sz = 1 + ((rng >> 16) % (LEN - off));
            uint8_t buf[LEN];
            for (uint64_t k = 0; k < sz; k++) {
                rng = rng * 1664525u + 1013904223u;
                buf[k] = (uint8_t)(rng >> 24);
            }
            if (vka_ub_defer(&q, BASE + off, sz, buf, &ch)) {
                apply_ref(ref, BASE, BASE + off, sz, buf);
                check_matches(&q, ref, BASE, LEN);
            }
        }
    }

    vka_ub_free(&q);
    puts("t_android_ubdefer: ok");
    return 0;
}
