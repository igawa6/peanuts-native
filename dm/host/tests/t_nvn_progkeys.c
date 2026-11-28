// A program's stage keys by its serial (dm/gfx/nvn/nvn_progkeys.h; hle_nvn.c "Programs"). Ori and the Will of the Wisps copies its NVNprogram objects after
// nvnProgramSetShaders and draws with the copies: keyed by address a copy found no keys, made them from a control section the game had reused, and 32 vertex shaders
// got keys no split has (a black picture). This test runs the table's rules over a small table of its own, as hle_nvn.c runs them over its pointer map
#include <stdio.h>
#include <string.h>
#include "../../gfx/nvn/nvn_progkeys.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

typedef struct { uint32_t magic, nstage; uint64_t serial; } prog;   // the fields of prog_t that matter here
typedef struct { const void *id; const void *owner; uint64_t key; } rec;
static rec tab[16]; static int ntab; static uint64_t serials;

static rec *find(const prog *p) { for (int i = 0; i < ntab; i++) if (tab[i].id == nvn_pk_id(p->serial)) return &tab[i]; return NULL; }
static void drop(const prog *p) {   // hle_nvn.c pk_drop
    rec *r = find(p);
    if (r && nvn_pk_may_drop(r->owner, p)) *r = tab[--ntab];
}
static void set_shaders(prog *p, uint64_t key) {   // hle_nvn.c nvnProgramSetShaders: the old serial's record goes, a new serial, its keys made and owned here
    drop(p); p->serial = ++serials;
    tab[ntab++] = (rec){nvn_pk_id(p->serial), p, key};
}
static uint64_t draw_key(const prog *p) { rec *r = find(p); return r ? r->key : 0; }   // 0: no record (the key would be made from the control section again)

int main(void) {
    // the table's key: never the pointer map's empty slot (0) or tombstone (1), and one per serial
    for (uint64_t s = 1; s < 1000; s++) {
        uintptr_t a = (uintptr_t)nvn_pk_id(s);
        CHECK(a != 0 && a != 1, "serial %llu maps to the pointer map's reserved key %lu", (unsigned long long)s, (unsigned long)a);
        CHECK(nvn_pk_id(s) != nvn_pk_id(s + 1), "serials %llu and %llu share a key", (unsigned long long)s, (unsigned long long)(s + 1));
    }

    // a copy made after SetShaders finds the original's keys
    prog a = {0x474f5250, 1, 0};
    set_shaders(&a, 0xa1);
    prog b; memcpy(&b, &a, sizeof b);
    CHECK(draw_key(&b) == 0xa1, "a copy of the program object did not find its keys (the Ori black picture)");

    // finalizing the copy leaves the keys to the original, which still draws
    drop(&b);
    CHECK(draw_key(&a) == 0xa1, "finalizing a copy dropped the original's keys");

    // a new SetShaders on a copy gives the copy its own serial and leaves the original's keys
    prog c; memcpy(&c, &a, sizeof c);
    set_shaders(&c, 0xc1);
    CHECK(draw_key(&c) == 0xc1 && draw_key(&a) == 0xa1, "a new SetShaders on a copy changed the original's keys (copy %#llx, original %#llx)",
          (unsigned long long)draw_key(&c), (unsigned long long)draw_key(&a));

    // a new SetShaders on the original drops its old serial's record; its copies made before then find none (as the replay's entry for that serial is released too)
    prog d; memcpy(&d, &a, sizeof d);
    set_shaders(&a, 0xa2);
    CHECK(draw_key(&a) == 0xa2, "the original's new keys are not found");
    CHECK(draw_key(&d) == 0, "the old serial's record outlived the new SetShaders of its owner");

    // finalizing the owner drops its record
    drop(&a);
    CHECK(draw_key(&a) == 0, "finalizing the owner left its record");

    // keys a frame capture set have no owner: any object with the serial may drop them
    prog e = {0x474f5250, 1, ++serials};
    tab[ntab++] = (rec){nvn_pk_id(e.serial), NULL, 0xe1};
    prog f; memcpy(&f, &e, sizeof f);
    drop(&f);
    CHECK(draw_key(&e) == 0, "a record without an owner was kept");

    CHECK(ntab == 1, "%d records left, expected the copy c's own", ntab);
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
