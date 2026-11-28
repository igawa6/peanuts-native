// Smoother next start, learning on: the pipelines a game's runs used, as dm/gfx/vk_android/vka_pso.c (sw_*) keeps them per game and GPU driver beside the prewarm
// list (<list>.<driver tag or "system">.hot). Free of Vulkan, so the Mac's and Linux's unit test (dm/host/tests/t_peanuts_swhot.c) checks it.
//
// An entry is a prewarm list record's hash (vka_pwlist.h vka_pw_hash, bit 0 set: 0 marks an empty slot) and the number of runs since a run last used it. A save writes
// this run's records first (0 runs), then the runs before's that this run did not use, a run older each, the youngest first; one unused for PN_SW_AGE_MAX runs is
// dropped, and at most PN_SW_MAX are kept (64 KB), so the oldest go first. A record a game update or another shader library no longer has, or another driver's,
// matches no record of the list: it is never made, and it ages out.
//   file: pn_sw_hdr (magic PNSWHOT1, n, the list's layout fingerprint vka_pw_layout, the shader library it was saved with), then n pn_sw_ent
#ifndef PEANUTS_SWHOT_H
#define PEANUTS_SWHOT_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define PN_SW_MAX 4096
#define PN_SW_AGE_MAX 8
static const char PN_SW_MAGIC[8] = {'P', 'N', 'S', 'W', 'H', 'O', 'T', '1'};
typedef struct { char magic[8]; uint32_t n, layout; uint64_t lib; } pn_sw_hdr;
typedef struct { uint64_t h; uint8_t age, pad[7]; } pn_sw_ent;
_Static_assert(sizeof(pn_sw_hdr) == 24 && sizeof(pn_sw_ent) == 16, "file records");

// A set of hashes (open addressing, a power of two slots, at most half full): 1 = added, 0 = there already (or no memory)
static inline int pn_sw_set_add(uint64_t **set, size_t *cap, size_t *n, uint64_t h) {
    h |= 1;
    if (2 * (*n + 1) > *cap) {
        size_t nc = *cap ? 2 * *cap : 1024; uint64_t *t = (uint64_t *)calloc(nc, sizeof *t); if (!t) return 0;
        for (size_t i = 0; i < *cap; i++) if ((*set)[i]) { size_t j = (*set)[i] & (nc - 1); while (t[j]) j = (j + 1) & (nc - 1); t[j] = (*set)[i]; }
        free(*set); *set = t; *cap = nc;
    }
    size_t j = h & (*cap - 1);
    for (; (*set)[j]; j = (j + 1) & (*cap - 1)) if ((*set)[j] == h) return 0;
    (*set)[j] = h; (*n)++;
    return 1;
}
static inline int pn_sw_set_has(const uint64_t *set, size_t cap, uint64_t h) {
    if (!cap) return 0;
    h |= 1;
    for (size_t j = h & (cap - 1); set[j]; j = (j + 1) & (cap - 1)) if (set[j] == h) return 1;
    return 0;
}

// A file's bytes: NULL and *out (malloc'd, NULL when empty), *n, *lib; else why it is not used (*out NULL, *n 0)
static inline const char *pn_sw_parse(const uint8_t *d, size_t len, uint32_t layout, pn_sw_ent **out, size_t *n, uint64_t *lib) {
    *out = NULL; *n = 0; *lib = 0;
    pn_sw_hdr h;
    if (!d || len < sizeof h) return "short";
    memcpy(&h, d, sizeof h);
    if (memcmp(h.magic, PN_SW_MAGIC, 8)) return "not a file of this form";
    if (h.layout != layout) return "of another list layout (its hashes name no record of this one)";
    if (h.n > PN_SW_MAX) return "longer than it may be";
    if (len != sizeof h + (size_t)h.n * sizeof(pn_sw_ent)) return "short";
    if (h.n) {
        pn_sw_ent *e = (pn_sw_ent *)malloc((size_t)h.n * sizeof *e); if (!e) return "no memory";
        memcpy(e, d + sizeof h, (size_t)h.n * sizeof *e);
        for (uint32_t i = 0; i < h.n; i++) if (!(e[i].h & 1)) { free(e); return "damaged"; }
        *out = e;
    }
    *n = h.n; *lib = h.lib;
    return NULL;
}

// The entries a save writes: run, a set of runcap slots (this run's records), then old (the runs before's) by age, the youngest first, each a run older, none the run
// used again nor any past agemax; at most max. out holds max entries. Returns their count
static inline size_t pn_sw_merge(const uint64_t *run, size_t runcap, const pn_sw_ent *old, size_t oldn, pn_sw_ent *out, size_t max, unsigned agemax) {
    size_t n = 0;
    for (size_t i = 0; i < runcap && n < max; i++) if (run[i]) { memset(&out[n], 0, sizeof out[n]); out[n].h = run[i]; n++; }
    for (unsigned age = 0; age < agemax; age++)
        for (size_t i = 0; i < oldn && n < max; i++)
            if (old[i].age == age && !pn_sw_set_has(run, runcap, old[i].h)) { out[n] = old[i]; out[n].age = (uint8_t)(age + 1); n++; }
    return n;
}

// The prewarm's order (vka_pso.c pw_start): n records, the first nm of them mesh records (one thread takes those first); hot[i] = record i is one the runs before used.
// Writes order (the indices: in each of the two parts the hot ones first, the list's order kept; with only, the hot ones alone) and mark (1 where order holds a hot one),
// moves *nm to the mesh part's new size. Returns the new count; *hot_n the hot ones
static inline uint32_t pn_sw_order(const uint8_t *hot, uint32_t n, uint32_t *nm, int only, uint32_t *order, uint8_t *mark, uint32_t *hot_n) {
    uint32_t o = 0, h = 0, m = *nm < n ? *nm : n;
    for (int part = 0; part < 2; part++) {
        uint32_t a = part ? m : 0, b = part ? n : m;
        for (uint32_t i = a; i < b; i++) if (hot[i]) { mark[o] = 1; order[o++] = i; h++; }
        if (!only) for (uint32_t i = a; i < b; i++) if (!hot[i]) { mark[o] = 0; order[o++] = i; }
        if (!part) *nm = o;
    }
    *hot_n = h;
    return o;
}
#endif
