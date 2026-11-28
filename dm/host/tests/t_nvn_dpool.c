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
        nvn_dpool_tab z = {0}; z.limit = lim ? 4096 * 4 : NVN_DPOOL_TEX_IDS;   // hle_nvn.c g_spt / g_tpt
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
        CHECK(z.evicted == 5001u - (unsigned)nvn_dpool_live(&z), "limit %d: %u pools counted as given up, %d of 5,001 live", z.limit, z.evicted, nvn_dpool_live(&z));
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
    // PEANUTS(upstream-candidate): the tables as hle_nvn.c makes them (g_tpt, g_spt: their production limits). Only small pools are held to the limit and given up
    {   // Trails through Daybreak II: three 1,048,576-entry texture pools, never finalized: each its own numbering
        static nvn_dpool_tab tt = {.limit = NVN_DPOOL_TEX_IDS}; static char d[3], dmem[3];
        int b0 = nvn_dpool_add2(&tt, &d[0], 1 << 20, &dmem[0]), b1 = nvn_dpool_add2(&tt, &d[1], 1 << 20, &dmem[1]), b2 = nvn_dpool_add2(&tt, &d[2], 1 << 20, &dmem[2]);
        CHECK(b0 == 0 && b1 == 1 << 20 && b2 == 2 << 20, "three 1 M texture pools got bases %d, %d, %d, not 0, 1048576, 2097152", b0, b1, b2);
        CHECK(!tt.evicted, "three 1 M texture pools gave %u up", tt.evicted);
    }
    {   // a 1 M scene pool and four 4,096-entry pools, then thousands of small pools (slots run out): no large pool is given up or moved, no small one takes a large one's range
        static nvn_dpool_tab tt = {.limit = NVN_DPOOL_TEX_IDS}; static char big[5], bmem[5], sm[3000]; static char smem[3000];
        int bb[5], moved = 0, inbig = 0;
        bb[0] = nvn_dpool_add2(&tt, &big[0], 1 << 20, &bmem[0]);
        for (int i = 1; i < 5; i++) bb[i] = nvn_dpool_add2(&tt, &big[i], 4096, &bmem[i]);
        CHECK(bb[0] == 0 && bb[1] == 1 << 20 && bb[4] == (1 << 20) + 3 * 4096, "the large pools got bases %d, %d .. %d", bb[0], bb[1], bb[4]);
        for (int i = 0; i < 3000; i++) {
            int b = nvn_dpool_add2(&tt, &sm[i], 272, &smem[i]);
            if (b + NVN_DPOOL_RESERVED < (1 << 20) + 4 * 4096) inbig++;
        }
        for (int i = 0; i < 5; i++) if (nvn_dpool_touch(&tt, &big[i], 0) != bb[i]) moved++;
        CHECK(!moved, "%d of 5 large pools were given up or moved by 3,000 small ones", moved);
        CHECK(!inbig, "%d small pools were numbered inside the large pools' ranges", inbig);
        CHECK(tt.evicted == 3000u + 5u - (unsigned)nvn_dpool_live(&tt) && tt.evicted > 0, "%u given up, %d live", tt.evicted, nvn_dpool_live(&tt));
        // a new large pool once every slot is taken shares the first pool's numbering (-1): it does not take a small pool's range
        // the slots ran out, the table did not: a 512-entry small pool (256 host entries) takes the slot of a 272-entry one and a new range at the table's end
        static char wide[4]; int wb = 0, n0 = tt.next; unsigned e0 = tt.evicted;
        for (int i = 0; i < 4; i++) { int b = nvn_dpool_add2(&tt, &wide[i], 512, &wide[i]); if (b != n0 + i * 256 - NVN_DPOOL_RESERVED) wb++; }
        CHECK(!wb && tt.evicted == e0 + 4 && nvn_dpool_touch(&tt, &big[0], 0) == 0, "512-entry small pools with every slot taken: %d bad bases, %u given up", wb, tt.evicted - e0);
        static char late;
        CHECK(nvn_dpool_add2(&tt, &late, 4096, &late) == -1, "a large pool took a slot given up by a small pool");
    }
    {   // small pools after a 4,096-entry pool: 272 and 257 entries take 16 host entries each; when the sampler table is full only small pools are given up
        static nvn_dpool_tab ss = {.limit = 4096 * 4}; static char s0, s1, s2, sm[2000], smem[2000];
        int b0 = nvn_dpool_add2(&ss, &s0, 4096, &s0), b1 = nvn_dpool_add2(&ss, &s1, 272, &s1), b2 = nvn_dpool_add2(&ss, &s2, 257, &s2);
        CHECK(b0 == 0 && b1 == 4096 - 256 && b2 == 4096 + 16 - 256, "small sampler pools' bases %d, %d after a 4,096 pool at %d", b1, b2, b0);
        int bad = 0;
        for (int i = 0; i < 2000; i++) { int b = nvn_dpool_add2(&ss, &sm[i], 257, &smem[i]); if (b + NVN_DPOOL_RESERVED < 4096 || b + 257 > ss.limit) bad++; nvn_dpool_touch(&ss, &s1, 0); }
        CHECK(!bad && ss.next <= ss.limit, "%d small sampler pools out of their range (table %d)", bad, ss.next);
        CHECK(nvn_dpool_touch(&ss, &s0, 0) == 0 && nvn_dpool_touch(&ss, &s1, 0) == b1, "the large pool or the pool in use lost its range");
        CHECK(ss.evicted > 0 && ss.evicted == 2003u - (unsigned)nvn_dpool_live(&ss), "%u given up, %d live", ss.evicted, nvn_dpool_live(&ss));
        // Dark Souls Remastered: 12 sampler pools of 2,304 entries pass the 16,384-entry table: bases as before the small pools, none given up (IDs past the table are dropped)
        static nvn_dpool_tab ds = {.limit = 4096 * 4}; static char dp[12];
        int dbad = 0;
        for (int i = 0; i < 12; i++) if (nvn_dpool_add2(&ds, &dp[i], 2304, &dp[i]) != i * 2304) dbad++;
        for (int i = 0; i < 12; i++) if (nvn_dpool_touch(&ds, &dp[i], 0) != i * 2304) dbad++;
        CHECK(!dbad && !ds.evicted, "12 large sampler pools: %d bases differ from 2,304 apart, %u given up", dbad, ds.evicted);
    }
    {   // Fire Emblem: Three Houses: textures 12,544 + 4,096, samplers 3,856 + 4,096 (as before the small pools)
        static nvn_dpool_tab tt = {.limit = NVN_DPOOL_TEX_IDS}, ss = {.limit = 4096 * 4}; static char t0, t1, s0, s1;
        int a = nvn_dpool_add2(&tt, &t0, 12544, &t0), b = nvn_dpool_add2(&tt, &t1, 4096, &t1), c = nvn_dpool_add2(&ss, &s0, 3856, &s0), d = nvn_dpool_add2(&ss, &s1, 4096, &s1);
        CHECK(a == 0 && b == 12544 && c == 0 && d == 4096, "Fire Emblem: Three Houses' pools got %d, %d (textures) and %d, %d (samplers), not 0, 12544, 0, 4096", a, b, c, d);
    }
    {   // a registration into a pool given up for a newer one is skipped: read from 0 it would overwrite the scene pool's descriptor (hle_nvn.c dpool_id)
        static nvn_dpool_tab tt = {.limit = NVN_DPOOL_TEX_IDS}; static char scene, sm[1100], smem[1100];
        nvn_dpool_add2(&tt, &scene, 4096, &scene);
        for (int i = 0; i < 1100; i++) nvn_dpool_add2(&tt, &sm[i], 272, &smem[i]);
        int why = -1, h = nvn_dpool_id(&tt, &sm[0], 256, &why, 0);
        CHECK(tt.evicted && nvn_dpool_slot(&tt, &sm[0]) < 0 && h == -1 && why == 2, "a given-up pool's ID 256 maps to host %d (why %d), not skipped", h, why);
        h = nvn_dpool_id(&tt, &scene, 256, &why, 0);
        CHECK(h == 256 && why == 0, "the scene pool's ID 256 maps to %d", h);
        h = nvn_dpool_id(&tt, &sm[1099], 10, &why, 0);
        CHECK(h == -1 && why == 1, "a small pool's reserved ID 10 maps to %d (why %d), not skipped", h, why);
        // nothing given up yet (a pool never placed: every slot taken by large pools): numbered from 0, as before the small pools
        static nvn_dpool_tab lt = {.limit = NVN_DPOOL_TEX_IDS}; static char lp[NVN_NDPOOL + 1];
        for (int i = 0; i <= NVN_NDPOOL; i++) nvn_dpool_add2(&lt, &lp[i], 256, &lp[i]);
        h = nvn_dpool_id(&lt, &lp[NVN_NDPOOL], 300, &why, 0);
        CHECK(!lt.evicted && h == 300 && why == 3, "a never-placed pool's ID 300 maps to %d (why %d), not 300", h, why);
    }
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
