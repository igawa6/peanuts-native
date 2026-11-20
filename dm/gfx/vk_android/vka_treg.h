// PEANUTS(upstream-candidate): CPU-side NVN texture registry. The includer supplies treg_ent and holds the registry lock.
#ifndef VKA_TREG_H
#define VKA_TREG_H
#include <stdlib.h>
#include "../nvn/nvn_dpool.h"

enum { TREG_PAGE = 256, TREG_GPU = 1 << 20, TREG_PAGES = NVN_NDPOOL * TREG_GPU / TREG_PAGE };
static treg_ent *g_tpage[TREG_PAGES];
static unsigned g_torigin[TREG_PAGES];   // insertion order: stable capture ordinals without scanning holes
static int g_ntreg;
static const treg_ent *treg_lookup(int id) {
    static const treg_ent empty;
    unsigned p = (unsigned)id / TREG_PAGE;
    return p < TREG_PAGES && g_tpage[p] ? &g_tpage[p][(unsigned)id % TREG_PAGE] : &empty;
}
static int treg_id(int i) { return (int)g_torigin[i / TREG_PAGE] + i % TREG_PAGE; }
static treg_ent *treg_at(int i) { return &g_tpage[g_torigin[i / TREG_PAGE] / TREG_PAGE][i % TREG_PAGE]; }
static treg_ent *treg_add(int id) {
    unsigned p = (unsigned)id / TREG_PAGE;
    if (id <= 0 || p >= TREG_PAGES) return NULL;
    if (!g_tpage[p]) {
        g_tpage[p] = calloc(TREG_PAGE, sizeof(treg_ent));
        if (!g_tpage[p]) return NULL;
        g_torigin[g_ntreg / TREG_PAGE] = p * TREG_PAGE;
        g_ntreg += TREG_PAGE;
    }
    return &g_tpage[p][(unsigned)id % TREG_PAGE];
}
#endif
