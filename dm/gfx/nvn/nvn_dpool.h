// The descriptor pools' ranges in the host's texture and sampler tables (hle_nvn.c "Descriptor pools"): each pool gets a base, its registrations go to base + ID, so two pools
// that use the same IDs do not meet. Free of locks and of NVN, so dm/host/tests/t_nvn_dpool.c can check it; the caller holds its lock.
#ifndef NVN_DPOOL_H
#define NVN_DPOOL_H

// PEANUTS(upstream-candidate): ASTLIBRA Revision keeps hundreds of small texture and sampler pools live during play (257 and 272 entries: NVN's 256 reserved descriptors, then 1 or 16 of the game's),
// makes thousands more and never finalizes them. With 16 ranges of 512 every pool from the 17th read the first pool's numbering (button icons on the title screen,
// scene art in the dialogue box, the HUD drawn from the font page).
// - A small pool (NVN_DPOOL_SMALL entries or fewer) gets a range for its IDs past the reserved ones only (NVN_DPOOL_RESERVED: nvnDeviceGetInteger 0x11 / 0x12):
//   16 host entries for a 272-entry pool instead of 512. Applications never register into the reserved IDs (Fire Emblem: Three Houses' pools fill from 256 up)
// - limit: the IDs the host table can address (the sampler table holds 16,384); 0 for none
// - When every slot or the table is taken, the pool least recently initialized, registered into or bound gives its slot up (evicted counts them)
#define NVN_NDPOOL 1024
#define NVN_DPOOL_RESERVED 256
#define NVN_DPOOL_SMALL 512
#define NVN_DPOOL_GRAIN 16
// store: where the pool's descriptors live (its memory pool's memory + offset), NULL when not known. In NVN two pool objects initialized over the same memory
// see the same descriptors, so they share one range (an alias: cap 0, never handed out as a free range)
// lo, cap: the host entries the range holds (lo .. lo + cap); base: what the pool's IDs are added to (lo, or lo - 256 for a small pool); pool NULL: a finalized pool's range
typedef struct { const void *pool; int base, lo, cap, small; const void *store; unsigned use; } nvn_dpool_ent;
typedef struct { nvn_dpool_ent e[NVN_NDPOOL]; int n, next, limit, hint; unsigned clock, evicted; } nvn_dpool_tab;   // n slots made, next: where a new range starts

static inline int nvn_dpool_store_live(const nvn_dpool_tab *t, const void *store) {   // a live pool object uses this storage
    if (!store) return 0;
    for (int i = 0; i < t->n; i++) if (t->e[i].pool && t->e[i].store == store) return 1;
    return 0;
}
static inline int nvn_dpool_range_live(const nvn_dpool_tab *t, int skip, int lo) {   // another live object uses the range starting at lo
    for (int i = 0; i < t->n; i++) if (i != skip && t->e[i].pool && t->e[i].lo == lo) return 1;
    return 0;
}
// A new range of `size` host entries at the table's end, -1 past the limit
static inline int nvn_dpool_grow(nvn_dpool_tab *t, int size) {
    if (t->limit && t->next + size > t->limit) return -1;
    int lo = t->next; t->next += size; return lo;
}

// The pool's base: its own if it has one; the base of a pool over the same storage (store); else the smallest finalized range that holds it, else a new range at the
// table's end, else the least recently used pool's slot. -1: no slot at all (the caller numbers it from 0, sharing the first pool's IDs). A pool that takes a
// finalized range finds the old pool's registrations there until it registers its own, as an NVN pool's new descriptors are whatever its memory held
static inline int nvn_dpool_add2(nvn_dpool_tab *t, const void *pool, int n, const void *store) {
    int small = t->n > 0 && n > NVN_DPOOL_RESERVED && n <= NVN_DPOOL_SMALL;   // the first pool keeps base 0 and its whole range
    int need = small ? (n - NVN_DPOOL_RESERVED + NVN_DPOOL_GRAIN - 1) & ~(NVN_DPOOL_GRAIN - 1) : (n + 255) & ~255, fit = -1, lo;
    t->clock++;
    for (int i = 0; i < t->n; i++) if (t->e[i].pool == pool) { t->e[i].use = t->clock; t->hint = i; return t->e[i].base; }
    if (store) {
        int own = -1, slot = -1;
        for (int i = 0; i < t->n; i++) if (t->e[i].store == store && t->e[i].cap >= need && t->e[i].small == small && (own < 0 || t->e[i].cap > t->e[own].cap)) own = i;
        if (own >= 0) {
            if (!t->e[own].pool) { t->e[own].pool = pool; t->e[own].use = t->clock; return t->e[own].base; }   // the storage's own range, finalized: this object takes it back
            for (int i = 0; i < t->n && slot < 0; i++) if (!t->e[i].pool && !t->e[i].cap) slot = i;   // a finalized alias's slot
            if (slot < 0 && t->n < NVN_NDPOOL) slot = t->n++;
            // no slot: the newest alias of this storage gives its slot (the game made another object over the same memory without finalizing the old one)
            for (int i = t->n - 1; i >= 0 && slot < 0; i--) if (t->e[i].store == store && !t->e[i].cap) slot = i;
            if (slot >= 0) { nvn_dpool_ent o = t->e[own]; t->e[slot] = (nvn_dpool_ent){pool, o.base, o.lo, 0, small, store, t->clock}; return o.base; }
        }
    }
    for (int i = 0; i < t->n; i++)
        if (!t->e[i].pool && t->e[i].cap >= need && !nvn_dpool_range_live(t, i, t->e[i].lo) && (fit < 0 || t->e[i].cap < t->e[fit].cap)) fit = i;
    if (fit >= 0) lo = t->e[fit].lo, need = t->e[fit].cap;
    else if (t->n < NVN_NDPOOL && (lo = nvn_dpool_grow(t, need)) >= 0) fit = t->n++;
    else {   // every slot or the whole table is taken by live pools: the least recently used gives its slot up
        for (int i = 0; i < t->n; i++) if (fit < 0 || (int)(t->e[i].use - t->e[fit].use) < 0) fit = i;
        if (fit < 0) return -1;
        nvn_dpool_ent *v = &t->e[fit];
        t->evicted++;
        if (v->cap >= need && !nvn_dpool_range_live(t, fit, v->lo)) lo = v->lo, need = v->cap;
        else {   // its range is too small or shared: the smallest free-standing range of a live pool that is big enough, else a new one
            int alt = -1;
            for (int i = 0; i < t->n; i++) if (i != fit && t->e[i].cap >= need && !nvn_dpool_range_live(t, i, t->e[i].lo) && !t->e[i].pool && (alt < 0 || t->e[i].cap < t->e[alt].cap)) alt = i;
            if (alt >= 0) { lo = t->e[alt].lo; need = t->e[alt].cap; t->e[alt].cap = 0; }
            else if ((lo = nvn_dpool_grow(t, need)) < 0) return -1;
        }
    }
    if (small && lo < NVN_DPOOL_RESERVED) {   // the first pool's range (from 0): a small pool there would number its IDs below 0, so it takes it whole, or a new range
        if (need >= n) small = 0;
        else { int g = nvn_dpool_grow(t, need); if (g < 0) return -1; lo = g; }
    }
    t->e[fit] = (nvn_dpool_ent){pool, small ? lo - NVN_DPOOL_RESERVED : lo, lo, need, small, store, t->clock};
    t->hint = fit;
    return t->e[fit].base;
}
static inline int nvn_dpool_add(nvn_dpool_tab *t, const void *pool, int n) { return nvn_dpool_add2(t, pool, n, 0); }
static inline int nvn_dpool_slot(const nvn_dpool_tab *t, const void *pool) {   // the pool's slot, -1 when it has none
    if (t->hint >= 0 && t->hint < t->n && t->e[t->hint].pool == pool) return t->hint;
    for (int i = 0; i < t->n; i++) if (t->e[i].pool == pool) return i;
    return -1;
}
static inline int nvn_dpool_find(const nvn_dpool_tab *t, const void *pool) {   // its base; 0 when it has none
    int i = nvn_dpool_slot(t, pool);
    return i < 0 ? 0 : t->e[i].base;
}
// nvn_dpool_find for a use of the pool (a registration or a bind): marks it recently used; -1 when it has no slot. *small: whether its IDs below 256 have no range
static inline int nvn_dpool_touch(nvn_dpool_tab *t, const void *pool, int *small) {
    int i = nvn_dpool_slot(t, pool);
    if (i < 0) return -1;
    t->e[i].use = ++t->clock; t->hint = i;
    if (small) *small = t->e[i].small;
    return t->e[i].base;
}
static inline int nvn_dpool_live(const nvn_dpool_tab *t) { int k = 0; for (int i = 0; i < t->n; i++) k += t->e[i].pool != 0; return k; }
// nvnTexturePoolFinalize / nvnSamplerPoolFinalize: the pool's range is free for a later pool (kept at its place: the table does not shrink)
static inline void nvn_dpool_release(nvn_dpool_tab *t, const void *pool) { int i = nvn_dpool_slot(t, pool); if (i >= 0) t->e[i].pool = 0; }
#endif
