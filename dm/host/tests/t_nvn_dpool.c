// The descriptor pools' ranges in the host's tables (dm/gfx/nvn/nvn_dpool.h; hle_nvn.c "Descriptor pools"). Mario Tennis Fever makes a 4,096-entry texture pool and sampler
// pool for each sub-menu it opens and finalizes them when it is left: the 17th took the first pool's numbering (16 ranges, none given back), its textures overwrote the
// scene pool's IDs and the menus lost their text after a few sub-menus in and out
#include <stdio.h>
#include "../../gfx/nvn/nvn_dpool.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    nvn_dpool_tab t = {0}; static char scene, menu[40];
    CHECK(nvn_dpool_add(&t, &scene, 131072) == 0, "the first pool is not numbered from 0");
    int clash = 0, base0 = nvn_dpool_add(&t, &menu[0], 4096);
    for (int i = 0; i < 40; i++) {   // a sub-menu in and out, 40 times: one live menu pool at a time beside the scene's
        int b = nvn_dpool_add(&t, &menu[i], 4096);
        if (b < 131072) clash++;
        CHECK(nvn_dpool_find(&t, &menu[i]) == b, "menu pool %d is not found at its base", i);
        nvn_dpool_release(&t, &menu[i]);
    }
    CHECK(!clash, "%d of 40 menu pools were numbered over the scene pool's IDs (their textures overwrite the scene's: the menus lose their text)", clash);
    CHECK(base0 == 131072, "the first menu pool is not numbered after the scene's");
    CHECK(t.next <= 131072 + 4096 * 2, "40 menu pools in turn took %d entries of the table: finalized ranges are not used again", t.next - 131072);
    CHECK(nvn_dpool_find(&t, &scene) == 0, "the scene pool lost its base");
    // live pools never share a range; a freed range smaller than a new pool is not used for it
    nvn_dpool_tab u = {0}; static char a, b, c, d;
    int ba = nvn_dpool_add(&u, &a, 4096), bb = nvn_dpool_add(&u, &b, 4096);
    nvn_dpool_release(&u, &a);
    int bc = nvn_dpool_add(&u, &c, 8192);
    CHECK(bc != ba && bc != bb, "an 8,192-entry pool was put in a freed 4,096-entry range (or a live one)");
    int bd = nvn_dpool_add(&u, &d, 4096);
    CHECK(bd == ba, "a 4,096-entry pool did not take the freed 4,096-entry range (it took %d)", bd);
    CHECK(nvn_dpool_find(&u, &a) == 0 || nvn_dpool_find(&u, &a) == bd, "a finalized pool still has its old base");
    // PEANUTS(upstream-candidate): NVN_NDPOOL live pools fill the table; a release makes room for the next without giving up a live one
    nvn_dpool_tab v = {0}; static char p[NVN_NDPOOL + 1];
    for (int i = 0; i < NVN_NDPOOL; i++) nvn_dpool_add(&v, &p[i], 256);
    nvn_dpool_release(&v, &p[3]);
    CHECK(nvn_dpool_add(&v, &p[NVN_NDPOOL], 256) > 0 && !v.evicted, "a pool made after one was finalized gave up a live pool");
    // ASTLIBRA Revision during play: thousands of new 272-entry pools (256 reserved + 16) over fresh memory, none finalized. Each small pool takes 16 host entries;
    // the scene's pool (bound every frame) and the newest ones keep their own numbering; the least recently used give their slots up; the table stays in its limit
    for (int lim = 0; lim < 2; lim++) {
        nvn_dpool_tab z = {0}; z.limit = lim ? 4096 * 4 : 1 << 20;
        static char scene2, many[5000]; static char zmem[5000 * 8704];
        nvn_dpool_add2(&z, &scene2, 4096, zmem);
        int zbad = 0, zlate = 0;
        for (int i = 0; i < 5000; i++) {
            int b = nvn_dpool_add2(&z, &many[i], 272, zmem + 65536 + i * 8704);
            if (b + NVN_DPOOL_RESERVED < 4096 || b + 272 > z.limit) zbad++;
            for (int j = i > 3 ? i - 3 : 0; j <= i; j++) if (nvn_dpool_touch(&z, &many[j], 0) + NVN_DPOOL_RESERVED < 4096) zlate++;   // the newest few are registered into and bound
            if (nvn_dpool_touch(&z, &scene2, 0) != 0) zbad++;
        }
        CHECK(!zbad, "limit %d: %d of 5,000 new pools took the scene pool's IDs or went past the table, or the scene pool lost its base", z.limit, zbad);
        CHECK(!zlate, "limit %d: %d uses of the newest pools found no slot", z.limit, zlate);
        CHECK(z.next <= z.limit && z.next <= 4096 + NVN_NDPOOL * 16, "limit %d: the table grew to %d entries", z.limit, z.next);
        CHECK(nvn_dpool_live(&z) >= (lim ? 700 : NVN_NDPOOL), "limit %d: only %d pools kept their slots", z.limit, nvn_dpool_live(&z));
        for (int i = 4990; i < 5000; i++) for (int j = i + 1; j < 5000; j++) {
            int bi = nvn_dpool_find(&z, &many[i]), bj = nvn_dpool_find(&z, &many[j]);
            CHECK(bi + 272 <= bj + 256 || bj + 272 <= bi + 256, "pools %d and %d overlap (%d, %d)", i, j, bi, bj);
        }
    }
    // a small pool's reserved IDs have no range: its first own ID (256) sits right after the previous pool's
    nvn_dpool_tab r = {0}; static char r0, r1, r2;
    nvn_dpool_add(&r, &r0, 4096);
    int r1b = nvn_dpool_add(&r, &r1, 272), r2b = nvn_dpool_add(&r, &r2, 257), sm = 0;
    CHECK(r1b + 256 == 4096 && r2b + 256 == 4096 + 16, "small pools' IDs from 256 start at %d and %d, not 4096 and 4112", r1b + 256, r2b + 256);
    CHECK(nvn_dpool_touch(&r, &r1, &sm) == r1b && sm, "a small pool is not marked small");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
