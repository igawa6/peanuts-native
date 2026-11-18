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
    // 16 live pools fill the table; the 17th has no range of its own (-1), and a release makes one
    nvn_dpool_tab v = {0}; static char p[17];
    for (int i = 0; i < 16; i++) nvn_dpool_add(&v, &p[i], 256);
    CHECK(nvn_dpool_add(&v, &p[16], 256) == -1, "a 17th live pool got a range");
    nvn_dpool_release(&v, &p[3]);
    CHECK(nvn_dpool_add(&v, &p[16], 256) > 0, "a pool made after one of 16 was finalized got no range");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
