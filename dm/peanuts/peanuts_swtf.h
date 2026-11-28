// Smoother next start, learning on: the transform feedback capture pipelines a game's runs made, as dm/gfx/vk_android/vka_pso.c (sw_tf_*) keeps them per game and GPU
// driver beside the prewarm list (<list>.<driver tag or "system">.tf). Free of Vulkan, so the Mac's and Linux's unit test (dm/host/tests/t_peanuts_swtf.c) checks it.
//
// The prewarm list never holds them: a capture pipeline draws with its vertex shader's capture variant (dm/gfx/vk_android/vka_tf.c), whose modules carry the layout
// the game's program declares at nvnProgramSetShaders, and a start that makes pipelines ahead of the game has no layout noted yet. So an entry is the list record of the
// pipeline (vka_pwlist.h: key[0] the variant's base vertex shader, its key bytes, never instanced) with the capture layout it was made with, and its hash: the record's
// vka_pw_hash XOR PN_SWTF_SALT, which is also its entry in the .hot file (peanuts_swhot.h). A save keeps the entries whose hash the .hot file keeps, so they age out with
// it; at most PN_SWTF_MAX (about 84 KB).
//   file: pn_swtf_hdr (magic PNSWTF01, n, the list's layout fingerprint vka_pw_layout, the entry's size), then n pn_swtf_ent
#ifndef PEANUTS_SWTF_H
#define PEANUTS_SWTF_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "../gfx/vk_android/vka_pwlist.h"
#include "../gfx/vk_android/vka_tf.h"
#include "peanuts_swhot.h"

#define PN_SWTF_MAX 64
#define PN_SWTF_SALT 0x46545753504e4950ull   // a capture entry's hash: its record's vka_pw_hash XOR this (no record of the list has it)
static const char PN_SWTF_MAGIC[8] = {'P', 'N', 'S', 'W', 'T', 'F', '0', '1'};
typedef struct { char magic[8]; uint32_t n, layout, ent_size, pad; } pn_swtf_hdr;
typedef struct { uint64_t h; pwrec r; vka_gkey k; vka_tf_layout L; uint8_t pad[4]; } pn_swtf_ent;   // k: r.klen bytes, the rest 0
_Static_assert(sizeof(pn_swtf_hdr) == 24 && sizeof(pn_swtf_ent) % 8 == 0, "file records");

static inline uint64_t pn_swtf_hash(const pwrec *r, const void *kb) { return (vka_pw_hash(r, kb) ^ PN_SWTF_SALT) | 1; }

// An entry that may reach the prewarm: a vertex pipeline's record that passes vka_pw_rec_ok, not instanced, whose hash is its own, and a layout of the form
// vka_tf_layout_read gives (vka_tf_spv.c): a buffer with no stride has no outputs, one with a stride 1 to VKA_TF_OUTS outputs of 1-4 components at a Location
// below 32 whose bytes make the stride, the slots past its outputs 0, and at least one buffer (vka_tf.c checks it against the device too)
static inline int pn_swtf_ok(const pn_swtf_ent *e) {
    if (e->r.kind != VKA_PW_GFX || e->r.bad || e->r.inst[0] || e->r.inst[1] || e->r.klen > sizeof e->k) return 0;
    if (!vka_pw_rec_ok(&e->r, (const uint8_t *)&e->k) || e->h != pn_swtf_hash(&e->r, &e->k)) return 0;
    const uint8_t *k = (const uint8_t *)&e->k;
    for (size_t i = e->r.klen; i < sizeof e->k; i++) if (k[i]) return 0;
    int any = 0;
    for (int b = 0; b < 4; b++) {
        if (!e->L.stride[b]) { if (e->L.n[b]) return 0; for (int i = 0; i < VKA_TF_OUTS; i++) if (e->L.loc[b][i] || e->L.comp[b][i]) return 0; continue; }
        if (e->L.n[b] < 1 || e->L.n[b] > VKA_TF_OUTS) return 0;
        unsigned sum = 0;
        for (int i = 0; i < e->L.n[b]; i++) { if (e->L.loc[b][i] >= 32 || e->L.comp[b][i] < 1 || e->L.comp[b][i] > 4) return 0; sum += e->L.comp[b][i]; }
        for (int i = e->L.n[b]; i < VKA_TF_OUTS; i++) if (e->L.loc[b][i] || e->L.comp[b][i]) return 0;   // (vka_tf_layout_read leaves them 0: pn_swtf_holds compares bytes)
        if (e->L.stride[b] != 4 * sum) return 0;
        any = 1;
    }
    return any;
}

// A capture variant made ahead with layout made (an earlier run's, from the file), at its first use by a draw (vka_tf.c learned_holds): 1 when this run's program noted
// the same layout for its vertex shader (st as vka_tf_layout_read returned it: 1 read, 0 none noted, -1 a form not known; run that layout), else 0: the variant is not
// used (its pipelines made ahead are never reached: the pipeline map keys a pipeline by its shader objects) and the draw makes its own with this run's layout, as before.
// Any byte counts: an output's Location, component count or order, a buffer's stride or output count, another buffer
static inline int pn_swtf_holds(int st, const vka_tf_layout *run, const vka_tf_layout *made) { return st == 1 && run && made && !memcmp(run, made, sizeof *made); }

// A file's bytes: NULL and *out (malloc'd, NULL when empty) with the entries that may reach the prewarm (*skipped the others); else why it is not used (*out NULL, *n 0)
static inline const char *pn_swtf_parse(const uint8_t *d, size_t len, uint32_t layout, pn_swtf_ent **out, size_t *n, size_t *skipped) {
    *out = NULL; *n = 0; *skipped = 0;
    pn_swtf_hdr h;
    if (!d || len < sizeof h) return "short";
    memcpy(&h, d, sizeof h);
    if (memcmp(h.magic, PN_SWTF_MAGIC, 8)) return "not a file of this form";
    if (h.layout != layout || h.ent_size != sizeof(pn_swtf_ent)) return "of another list layout (its records name no pipeline of this host)";
    if (h.n > PN_SWTF_MAX) return "longer than it may be";
    if (len != sizeof h + (size_t)h.n * sizeof(pn_swtf_ent)) return "short";
    if (!h.n) return NULL;
    pn_swtf_ent *e = (pn_swtf_ent *)malloc((size_t)h.n * sizeof *e); if (!e) return "no memory";
    size_t k = 0;
    for (uint32_t i = 0; i < h.n; i++) { memcpy(&e[k], d + sizeof h + (size_t)i * sizeof *e, sizeof *e); if (pn_swtf_ok(&e[k])) k++; else (*skipped)++; }
    if (!k) { free(e); e = NULL; }
    *out = e; *n = k;
    return NULL;
}

// One more entry for this run's table (*e of *n, *cap slots): 1 = added, 0 = one of its hash is there, the table is full (PN_SWTF_MAX) or there is no memory
static inline int pn_swtf_add(pn_swtf_ent **e, size_t *n, size_t *cap, const pn_swtf_ent *x) {
    for (size_t i = 0; i < *n; i++) if ((*e)[i].h == x->h) return 0;
    if (*n >= PN_SWTF_MAX) return 0;
    if (*n == *cap) { size_t c = *cap ? 2 * *cap : 8; pn_swtf_ent *t = (pn_swtf_ent *)realloc(*e, c * sizeof *t); if (!t) return 0; *e = t; *cap = c; }
    (*e)[(*n)++] = *x;
    return 1;
}

// The entries a save writes, into out (max entries): run's (this run's), then old's (the runs before's) that run does not hold, each only while keep (the entries the
// .hot file's save writes, kn of them) holds its hash, so an entry ages out with it. Returns their count
static inline size_t pn_swtf_keep(const pn_swtf_ent *run, size_t runn, const pn_swtf_ent *old, size_t oldn, const pn_sw_ent *keep, size_t kn, pn_swtf_ent *out, size_t max) {
    uint64_t *set = NULL; size_t cap = 0, cnt = 0, n = 0;
    for (size_t i = 0; i < kn; i++) pn_sw_set_add(&set, &cap, &cnt, keep[i].h);
    for (int part = 0; part < 2; part++) {
        const pn_swtf_ent *src = part ? old : run; size_t sn = part ? oldn : runn;
        for (size_t i = 0; i < sn && n < max; i++) {
            if (!pn_sw_set_has(set, cap, src[i].h)) continue;
            size_t j = 0; while (j < n && out[j].h != src[i].h) j++;
            if (j == n) out[n++] = src[i];
        }
    }
    free(set);
    return n;
}
#endif
