// The descriptor pools' ranges in the host's texture and sampler tables (hle_nvn.c "Descriptor pools"): each pool gets a base, its registrations go to base + ID, so two pools
// that use the same IDs do not meet. Free of locks and of NVN, so dm/host/tests/t_nvn_dpool.c can check it; the caller holds its lock.
#ifndef NVN_DPOOL_H
#define NVN_DPOOL_H

// PEANUTS(upstream-candidate): ASTLIBRA Revision keeps hundreds of small texture and sampler pools live during play (257 and 272 entries: NVN's 256 reserved descriptors, then 1 or 16 of the game's),
// makes thousands more and never finalizes them. With 16 ranges of 512 every pool from the 17th read the first pool's numbering (button icons on the title screen,
// scene art in the dialogue box, the HUD drawn from the font page).
// - A small pool (NVN_DPOOL_SMALL entries or fewer) gets a range for its IDs past the reserved ones only (NVN_DPOOL_RESERVED: nvnDeviceGetInteger 0x11 / 0x12):
//   16 host entries for a 272-entry pool instead of 512. Applications never register into the reserved IDs (Fire Emblem: Three Houses' pools fill from 256 up)
//   Registrations into them are skipped (hle_nvn.c dpool_id), and the Android replay reads a bound handle's ID below 256 of a small pool as no descriptor
//   (hle_nvn.c nvn_dpool_base_first: vka_bind.c ubo_tex_id / ubo_samp_id) instead of base + ID, which is in the previous pool's range.
//   TODO: macOS (nvn_mtl.m ubo_tex_id / pool_gpu, hle_nvn.c bind_handles / DrawTexture / BindTexture at recording) still adds base + ID there
// - limit: the IDs the host table can address (textures NVN_DPOOL_TEX_IDS, the registry vka_treg.h holds; the sampler table holds 16,384); 0 for none.
//   Only small pools are held to it: a large pool always gets a range at the table's end, as before the small pools, and its IDs past the table are dropped where
//   they are registered (vka_tex.c mtl_reg_samp, vka_treg.h treg). Trails through Daybreak II keeps three 1,048,576-entry texture pools live: bases 0, 1 M and 2 M
// - When every slot or the table is taken, the small pool least recently initialized, registered into or bound gives its slot up to a new small pool (evicted
//   counts them). Only small pools take part: a large pool is never given up and never takes a range given up, so the first pool and the scenes' pools keep
//   their numbering. A pool given up registers nothing more (nvn_dpool_id: its IDs would be the first pool's). A command buffer recorded against a small
//   pool that is given up later reads, when it is replayed: on Android the first pool's numbering (base 0, first ID 0: vka_draw.c resolves the bound pool at
//   replay, with the "[RED] ... given up for a newer one is used again" line); on macOS the newer pool's descriptors in that range (bind_handles adds
//   CB(c)->tex_base / samp_base at recording)
#define NVN_NDPOOL 1024
#define NVN_DPOOL_TEX_IDS (16 << 20)
#define NVN_DPOOL_RESERVED 256
#define NVN_DPOOL_SMALL 512
#define NVN_DPOOL_GRAIN 16
// store: where the pool's descriptors live (its memory pool's memory + offset), NULL when not known. In NVN two pool objects initialized over the same memory
// see the same descriptors, so they share one range (an alias: cap 0, never handed out as a free range)
// lo, cap: the host entries the range holds (lo .. lo + cap); base: what the pool's IDs are added to (lo, or lo - 256 for a small pool); pool NULL: a finalized pool's range
typedef struct { const void *pool; int base, lo, cap, small; const void *store; unsigned use; } nvn_dpool_ent;
typedef struct { nvn_dpool_ent e[NVN_NDPOOL]; int n, next, limit, hint; unsigned clock, evicted; } nvn_dpool_tab;   // n slots made, next: where a new range starts

static inline int nvn_dpool_range_live(const nvn_dpool_tab *t, int skip, int lo) {   // another live object uses the range starting at lo
    for (int i = 0; i < t->n; i++) if (i != skip && t->e[i].pool && t->e[i].lo == lo) return 1;
    return 0;
}
// A new range of `size` host entries at the table's end; -1 past the limit, which holds only for a small pool
static inline int nvn_dpool_grow(nvn_dpool_tab *t, int size, int small) {
    if (small && t->limit && t->next + size > t->limit) return -1;
    int lo = t->next; t->next += size; return lo;
}

// The least recently used live small pool that can give its slot up: with need > 0, one whose range holds `need` entries and no other live object shares; with need 0,
// any (an alias's slot, or a range nobody else uses). -1: none. A pool whose range another live object shares is passed over (a few tries: the scan stays linear)
static inline int nvn_dpool_lru_small(const nvn_dpool_tab *t, int need) {
    int skip[8], ns = 0;
    for (;;) {
        int v = -1;
        for (int i = 0; i < t->n; i++) {
            const nvn_dpool_ent *e = &t->e[i];
            if (!e->pool || !e->small || (need ? e->cap < need : 0) || (v >= 0 && (int)(e->use - t->e[v].use) >= 0)) continue;
            int sk = 0; for (int k = 0; k < ns; k++) sk |= skip[k] == i;
            if (!sk) v = i;
        }
        if (v < 0) return -1;
        if (!(need ? 0 : !t->e[v].cap) && nvn_dpool_range_live(t, v, t->e[v].lo)) { if (ns == 8) return -1; skip[ns++] = v; continue; }
        return v;
    }
}

// The pool's base: its own if it has one; the base of a pool over the same storage (store); else the smallest finalized range that holds it, else a new range at the
// table's end, else (a small pool only) the least recently used small pool's slot. -1: no slot at all (the caller numbers it from 0, sharing the first pool's IDs). A pool that takes a
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
    else if (t->n < NVN_NDPOOL && (lo = nvn_dpool_grow(t, need, small)) >= 0) fit = t->n++;
    else if (small) {   // every slot or the table is taken: the least recently used small pool gives its slot up. Large pools are never given up
        int v = t->n == NVN_NDPOOL ? nvn_dpool_lru_small(t, 0) : -1;   // the slots ran out: the least recently used small pool's slot
        if (v >= 0 && t->e[v].cap >= need && !nvn_dpool_range_live(t, v, t->e[v].lo)) lo = t->e[v].lo, need = t->e[v].cap;   // with its range
        else if (v < 0 || (lo = nvn_dpool_grow(t, need, small)) < 0) {   // or with a new range (its own is too small, or shared by its other object)
            v = nvn_dpool_lru_small(t, need);   // the table is full: the least recently used small pool whose range holds the new one
            if (v < 0) return -1;
            lo = t->e[v].lo, need = t->e[v].cap;
        }
        fit = v; t->evicted++;
    } else return -1;   // a large pool with every slot taken: it shares the first pool's numbering
    if (small && lo < NVN_DPOOL_RESERVED) {   // the first pool's range (from 0): a small pool there would number its IDs below 0, so it takes it whole, or a new range
        if (need >= n) small = 0;
        else { int g = nvn_dpool_grow(t, need, small); if (g < 0) return -1; lo = g; }
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
// The host ID of a pool's ID for a registration or a bind (marks the pool used), -1 to skip it. *why: 0 placed; 1 a reserved ID (below 256) of a small pool, skipped;
// 2 the pool was given up for a newer one (evicted), skipped: its IDs would overwrite the first pool's; 3 a pool never placed (nothing given up yet: the table
// was full of large pools), numbered from 0 as before the small pools. *small (may be NULL): whether the pool is small
static inline int nvn_dpool_id(nvn_dpool_tab *t, const void *pool, int id, int *why, int *small) {
    int sm = 0, b = nvn_dpool_touch(t, pool, &sm);
    if (small) *small = sm;
    if (b < 0) { *why = t->evicted ? 2 : 3; return t->evicted ? -1 : id; }
    if (sm && id < NVN_DPOOL_RESERVED) { *why = 1; return -1; }
    *why = 0; return b + id;
}
static inline int nvn_dpool_live(const nvn_dpool_tab *t) { int k = 0; for (int i = 0; i < t->n; i++) k += t->e[i].pool != 0; return k; }
// nvnTexturePoolFinalize / nvnSamplerPoolFinalize: the pool's range is free for a later pool (kept at its place: the table does not shrink)
static inline void nvn_dpool_release(nvn_dpool_tab *t, const void *pool) { int i = nvn_dpool_slot(t, pool); if (i >= 0) t->e[i].pool = 0; }
#endif
