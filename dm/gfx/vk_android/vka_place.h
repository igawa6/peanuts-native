// Placing large memory pools by parts (vka_mem.c mtl_pool_need; docs/reference/memory.md "Placed pools commit what the game only reserved"): the range arithmetic, free of
// Vulkan so the Mac's unit test (dm/host/tests/t_android_place.c) can check it. A pool of at least the part minimum is placed in parts: when the GPU is given [a, e) in it,
// the granules of the pool that [a, e) touches become placed memory (granules counted from the pool's start), not the whole pool. An ask that covers the whole pool
// (nvnMemoryPoolGetBufferAddress) places all of it. The caller aligns the part outward to the placed-map alignment, as it does a whole pool.
// An ask across a part placed earlier and one placed now lies in two segments; vkp_join finds the run of segments to join into one (vka_mem.c "Joining parts")
#ifndef VKA_PLACE_H
#define VKA_PLACE_H
#include <stddef.h>
#include <stdint.h>

// 1 = the pool [mem, mem + size) is placed by parts (part_min 0: never; a pool of less than two granules is placed whole, a part would be most of it)
static inline int vkp_by_parts(uint64_t size, uint64_t part_min, uint64_t granule) {
    return part_min && granule && size >= part_min && size >= 2 * granule;
}
// The part of the pool to place for the ask [a, e): the granules it touches, clipped to the pool, into [*lo, *hi). Returns 0 when the ask covers the whole pool or lies
// outside it (the caller places the whole pool, as before), 1 for a part
static inline int vkp_part(uint64_t mem, uint64_t size, uint64_t a, uint64_t e, uint64_t granule, uint64_t *lo, uint64_t *hi) {
    uint64_t end = mem + size;
    if (!granule || e <= a || a >= end || e <= mem || (a <= mem && e >= end)) return 0;
    uint64_t s = a > mem ? a - mem : 0, t = (e < end ? e : end) - mem;   // [s, t): the ask in the pool, as offsets
    s -= s % granule;
    t = t % granule ? t + (granule - t % granule) : t;
    if (t > size || t < s) t = size;   // (the last granule may be short; t < s: overflow of the round up)
    if (s == 0 && t == size) return 0;
    *lo = mem + s; *hi = mem + t;
    return 1;
}
// Joining parts (vka_mem.c "Joining parts"): the run of placed segments that holds [a, e). segs: n segments sorted by start and disjoint, each starting with its bounds
// [lo, hi) as two uint64_t, stride bytes apart (vka_mem.c's seg_t). Returns the number of segments from *k0 to *k1 when they hold all of [a, e) end to end (1: one
// segment holds it, nothing to join), 0 when some byte of it lies in no segment or between two that do not touch
static inline int vkp_join(const void *segs, size_t stride, int n, uint64_t a, uint64_t e, int *k0, int *k1) {
#define VKP_LO(i) (((const uint64_t *)((const char *)segs + (size_t)(i) * stride))[0])
#define VKP_HI(i) (((const uint64_t *)((const char *)segs + (size_t)(i) * stride))[1])
    if (e <= a || n <= 0) return 0;
    int lo = 0, hi = n - 1, k = -1;   // the last segment starting at or before a
    while (lo <= hi) { int m = lo + (hi - lo) / 2; if (VKP_LO(m) <= a) { k = m; lo = m + 1; } else hi = m - 1; }
    if (k < 0 || a >= VKP_HI(k)) return 0;
    int j = k;
    while (VKP_HI(j) < e) { if (j + 1 >= n || VKP_LO(j + 1) != VKP_HI(j)) return 0; j++; }
    *k0 = k; *k1 = j;
    return j - k + 1;
#undef VKP_LO
#undef VKP_HI
}
// PEANUTS(upstream-candidate) begin: the per-thread record of an ask with nothing left to do (vka_mem.c t_neg) holds for a later ask only if that record's ask did all
// the later one needs: an ask that only placed (mtl_pool_place: a texture's storage) leaves a range across two parts not joined, so a joining ask of the same range
// (the same texture as a linear or buffer texture, its address, a command naming it) must not be answered from it. rec_join / join: 1 = the ask joins
static inline int vkp_neg_hit(uint64_t rec_gen, uint64_t rec_a, uint64_t rec_e, int rec_join, uint64_t gen, uint64_t a, uint64_t e, int join) {
    return rec_gen == gen && a >= rec_a && e <= rec_e && (rec_join || !join);
}
// PEANUTS(upstream-candidate) end
// PEANUTS begin: cross-pool range join planner + refusal cache (keyed by segment gen) and linear merge for gmem_map chunks
typedef struct { uint64_t gen; uintptr_t a, e; } vkp_rjoin_neg_t;
// 1 = [a, e) (or a sub-range of it, which already cannot be joined) was refused at segment generation gen
static inline int vkp_rjoin_neg_hit(const vkp_rjoin_neg_t *tab, int n, uint64_t gen, uintptr_t a, uintptr_t e) {
    for (int i = 0; i < n; i++)
        if (tab[i].gen == gen && tab[i].e > tab[i].a && a <= tab[i].a && tab[i].e <= e) return 1;
    return 0;
}
static inline void vkp_rjoin_neg_put(vkp_rjoin_neg_t *tab, int n, int *w, uint64_t gen, uintptr_t a, uintptr_t e) {
    if (n <= 0 || e <= a) return;
    if (vkp_rjoin_neg_hit(tab, n, gen, a, e)) return;
    tab[*w % n] = (vkp_rjoin_neg_t){gen, a, e};
    *w = (*w + 1) % n;
}
// Decide whether segments covering [a, e) can be joined into one:
//   0  = already in a single segment (nothing to join)
//   1  = joinable placed segments (join)
//   2  = joinable exported-alias segments (join_alias)
//  -1  = refused: placed and exported-alias segments mixed
//  -2  = refused: full joined segment span exceeds join_cap
//  -3  = refused: full joined segment span exceeds max_alloc
//  -4  = refused: [a, e) is not covered end-to-end by contiguous segments
static inline int vkp_rjoin_plan(const void *segs, size_t stride, size_t alias_off, int n,
                                 uint64_t a, uint64_t e, uint64_t cap, uint64_t max_alloc, int *k0, int *k1) {
    int c = vkp_join(segs, stride, n, a, e, k0, k1);
    if (c == 1) return 0;
    if (c <= 0) return -4;
    uint64_t jlo = ((const uint64_t *)((const char *)segs + (size_t)(*k0) * stride))[0];
    uint64_t jhi = ((const uint64_t *)((const char *)segs + (size_t)(*k1) * stride))[1];
    int nalias = 0;
    for (int k = *k0; k <= *k1; k++)
        nalias += *(const int *)((const char *)segs + (size_t)k * stride + alias_off) != 0;
    if (nalias && nalias != c) return -1;
    if (!cap || jhi - jlo > cap) return -2;
    if (max_alloc && jhi - jlo > max_alloc) return -3;
    return nalias ? 2 : 1;
}
// Linear merge of two sorted, disjoint [lo, hi) -> bda tables into out (at most max entries, strictly sorted and disjoint)
static inline int vkp_merge_chunks(const uint64_t *alo, const uint64_t *ahi, const uint64_t *abda, int na,
                                   const uint64_t *blo, const uint64_t *bhi, const uint64_t *bbda, int nb,
                                   uint64_t *lo, uint64_t *hi, uint64_t *bda, int max) {
    int ia = 0, ib = 0, n = 0;
    while (n < max && (ia < na || ib < nb)) {
        int take_a = ib >= nb || (ia < na && alo[ia] <= blo[ib]);
        uint64_t cl = take_a ? alo[ia] : blo[ib];
        uint64_t ch = take_a ? ahi[ia] : bhi[ib];
        uint64_t cb = take_a ? abda[ia] : bbda[ib];
        if (take_a) ia++; else ib++;
        if (ch <= cl) continue;
        if (n > 0 && cl < hi[n - 1]) {
            if (ch <= hi[n - 1]) continue;
            cb += hi[n - 1] - cl;
            cl = hi[n - 1];
        }
        lo[n] = cl; hi[n] = ch; bda[n] = cb; n++;
    }
    return n;
}
// PEANUTS end
#endif
