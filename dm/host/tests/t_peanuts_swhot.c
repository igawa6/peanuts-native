// PEANUTS: Smoother next start's file of the pipelines a game's runs used (dm/peanuts/peanuts_swhot.h, vka_pso.c sw_*): the set, the file's check, the save's merge (this run first,
// the runs before by age, aged out after PN_SW_AGE_MAX runs, at most PN_SW_MAX) and the prewarm's order (the hot records first in their part, mesh records in front)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../peanuts/peanuts_swhot.h"

static int fails;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)

static size_t file(uint8_t *buf, uint32_t layout, const pn_sw_ent *e, uint32_t n) {
    pn_sw_hdr h; memset(&h, 0, sizeof h); memcpy(h.magic, PN_SW_MAGIC, 8); h.n = n; h.layout = layout; h.lib = 0x1234;
    memcpy(buf, &h, sizeof h); if (n) memcpy(buf + sizeof h, e, n * sizeof *e);
    return sizeof h + n * sizeof *e;
}

static int has_ent(const pn_sw_ent *e, size_t n, uint64_t h, int age) {
    for (size_t i = 0; i < n; i++) if (e[i].h == (h | 1) && e[i].age == age) return 1;
    return 0;
}

int main(void) {
    // the set: added once, found with or without bit 0, grown past its first size
    uint64_t *set = NULL; size_t cap = 0, n = 0;
    CHECK(pn_sw_set_add(&set, &cap, &n, 0x10) == 1);
    CHECK(pn_sw_set_add(&set, &cap, &n, 0x11) == 0);   // the same hash with bit 0 set: 0 is an empty slot, every hash is stored with bit 0
    CHECK(pn_sw_set_has(set, cap, 0x10) && pn_sw_set_has(set, cap, 0x11) && !pn_sw_set_has(set, cap, 0x20));
    for (uint64_t i = 1; i <= 5000; i++) pn_sw_set_add(&set, &cap, &n, i * 0x9E3779B97F4A7C15ull);
    CHECK(n == 5001 && cap >= 2 * n);
    for (uint64_t i = 1; i <= 5000; i++) CHECK(pn_sw_set_has(set, cap, i * 0x9E3779B97F4A7C15ull));
    CHECK(!pn_sw_set_has(NULL, 0, 1));
    free(set);

    // the file: read back; another form, layout, length, a damaged entry or one too many is not used
    static uint8_t buf[sizeof(pn_sw_hdr) + (PN_SW_MAX + 2) * sizeof(pn_sw_ent)];
    pn_sw_ent e3[3] = {{.h = 0x101}, {.h = 0x203, .age = 2}, {.h = 0x305, .age = 7}};
    size_t len = file(buf, 77, e3, 3);
    pn_sw_ent *got; size_t gn; uint64_t lib;
    CHECK(pn_sw_parse(buf, len, 77, &got, &gn, &lib) == NULL && gn == 3 && lib == 0x1234 && got && got[1].h == 0x203 && got[1].age == 2);
    free(got);
    CHECK(pn_sw_parse(buf, len, 78, &got, &gn, &lib) != NULL && !got && gn == 0);    // another list layout: its hashes name no record
    CHECK(pn_sw_parse(buf, len - 1, 77, &got, &gn, &lib) != NULL && gn == 0);        // short
    CHECK(pn_sw_parse(buf, len + 16, 77, &got, &gn, &lib) != NULL && gn == 0);       // longer than its count
    CHECK(pn_sw_parse(buf, 10, 77, &got, &gn, &lib) != NULL && pn_sw_parse(NULL, 0, 77, &got, &gn, &lib) != NULL);
    buf[0] = 'X'; CHECK(pn_sw_parse(buf, len, 77, &got, &gn, &lib) != NULL); buf[0] = 'P';
    e3[2].h = 0x304; len = file(buf, 77, e3, 3); CHECK(pn_sw_parse(buf, len, 77, &got, &gn, &lib) != NULL && !got);   // an entry without bit 0: damaged
    { pn_sw_hdr h; memcpy(&h, buf, sizeof h); h.n = PN_SW_MAX + 1; memcpy(buf, &h, sizeof h); CHECK(pn_sw_parse(buf, sizeof h + (PN_SW_MAX + 1) * sizeof(pn_sw_ent), 77, &got, &gn, &lib) != NULL); }
    len = file(buf, 77, NULL, 0); CHECK(pn_sw_parse(buf, len, 77, &got, &gn, &lib) == NULL && gn == 0 && !got);   // an empty file of this form

    // the merge: this run's (age 0) first, the runs before's it did not use a run older, the youngest first; past PN_SW_AGE_MAX gone
    uint64_t *run = NULL; size_t rcap = 0, rn = 0;
    pn_sw_set_add(&run, &rcap, &rn, 0xA1); pn_sw_set_add(&run, &rcap, &rn, 0x203);   // 0x203: used again this run
    pn_sw_ent old[4] = {{.h = 0x203, .age = 3}, {.h = 0x305, .age = PN_SW_AGE_MAX - 1}, {.h = 0x407, .age = PN_SW_AGE_MAX}, {.h = 0x509, .age = 0}};
    pn_sw_ent out[16];
    size_t m = pn_sw_merge(run, rcap, old, 4, out, 16, PN_SW_AGE_MAX);
    CHECK(m == 4);
    CHECK(out[0].age == 0 && out[1].age == 0);                       // this run's first
    CHECK(has_ent(out, m, 0xA1, 0) && has_ent(out, m, 0x203, 0));    // used again: young again
    CHECK(has_ent(out, m, 0x509, 1));                                // a run older
    CHECK(has_ent(out, m, 0x305, PN_SW_AGE_MAX));                    // the last run it is kept
    CHECK(!has_ent(out, m, 0x407, PN_SW_AGE_MAX) && !has_ent(out, m, 0x407, PN_SW_AGE_MAX + 1));   // unused for PN_SW_AGE_MAX runs: gone
    CHECK(out[2].h == 0x509 && out[3].h == 0x305);                   // the youngest of the runs before first
    // the cap: the oldest go first
    m = pn_sw_merge(run, rcap, old, 4, out, 3, PN_SW_AGE_MAX);
    CHECK(m == 3 && has_ent(out, m, 0x509, 1) && !has_ent(out, m, 0x305, PN_SW_AGE_MAX));
    // a run with nothing used: everything a run older
    m = pn_sw_merge(NULL, 0, old, 4, out, 16, PN_SW_AGE_MAX);
    CHECK(m == 3 && has_ent(out, m, 0x203, 4) && has_ent(out, m, 0x509, 1));
    // the bound over many runs: a run of PN_SW_MAX new records leaves none of the runs before
    uint64_t *big = NULL; size_t bcap = 0, bn = 0;
    for (uint64_t i = 1; i <= PN_SW_MAX; i++) pn_sw_set_add(&big, &bcap, &bn, i << 8);
    static pn_sw_ent bout[PN_SW_MAX];
    m = pn_sw_merge(big, bcap, old, 4, bout, PN_SW_MAX, PN_SW_AGE_MAX);
    CHECK(m == PN_SW_MAX);
    for (size_t i = 0; i < m; i++) CHECK(bout[i].age == 0);
    free(big); free(run);

    // the order: 3 mesh records then 5 others; hot ones first in each part, the list's order kept; with only, the hot ones alone
    uint8_t hot[8] = {0, 1, 0, 1, 0, 0, 1, 1}; uint32_t order[8], hn, nm = 3; uint8_t mark[8];
    uint32_t k = pn_sw_order(hot, 8, &nm, 0, order, mark, &hn);
    uint32_t want[8] = {1, 0, 2, 3, 6, 7, 4, 5}; uint8_t wmark[8] = {1, 0, 0, 1, 1, 1, 0, 0};
    CHECK(k == 8 && nm == 3 && hn == 4 && !memcmp(order, want, sizeof want) && !memcmp(mark, wmark, sizeof wmark));
    nm = 3; k = pn_sw_order(hot, 8, &nm, 1, order, mark, &hn);
    uint32_t want1[4] = {1, 3, 6, 7};
    CHECK(k == 4 && nm == 1 && hn == 4 && !memcmp(order, want1, sizeof want1) && mark[0] && mark[1] && mark[2] && mark[3]);
    uint8_t none[4] = {0}; nm = 0; k = pn_sw_order(none, 4, &nm, 1, order, mark, &hn);
    CHECK(k == 0 && hn == 0 && nm == 0);   // nothing the runs before used: a driver that does not prewarm makes nothing
    nm = 9; k = pn_sw_order(hot, 8, &nm, 0, order, mark, &hn);   // a mesh count past the list: every record is a mesh one
    CHECK(k == 8 && nm == 8 && hn == 4);

    if (fails) { fprintf(stderr, "%d failed\n", fails); return 1; }
    printf("t_peanuts_swhot: ok\n");
    return 0;
}
