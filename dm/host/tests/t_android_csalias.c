// Cross-format compute storage aliasing (dm/gfx/vk_android/vka_csalias.h; vka_draw.c cs_img_alias_prep, vka_tex.c vka_tex_alias_copy):
// verifies which read-only storage bindings alias a written storage binding on the same subresource through a different format or numeric class.
#include <stdio.h>
#include "../../gfx/vk_android/vka_csalias.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
enum { R32_UINT = 98, R32_SFLOAT = 100, B10G11R11 = 122, RGBA8_UNORM = 37 };

int main(void) {
    int out[8];

    // 1. Cross-class alias (DOOM computedeferredcomposite_cs: R32_UINT read + float B10G11R11 write on same serial/level/layer).
    {
        vka_csa_bind b[3] = {
            {2, B10G11R11, 1, 0, 0, 0, 1},   // binding 7: read-only R32_UINT (cls 1)
            {2, B10G11R11, 0, 1, 0, 0, 1},   // binding 8: written B10G11R11 (cls 0)
            {46, B10G11R11, 0, 1, 0, 0, 1},  // binding 9: written B10G11R11 (different serial)
        };
        int n = vka_csa_find(b, 3, out, 8);
        CHECK(n == 1 && out[0] == 0, "cross-class alias: n=%d out[0]=%d", n, n ? out[0] : -1);
    }

    // 2. Cross-format same-class alias (different VkFormat values, same class and subresource).
    {
        vka_csa_bind b[2] = {
            {7, R32_SFLOAT, 0, 0, 0, 0, 1},
            {7, RGBA8_UNORM, 0, 1, 0, 0, 1},
        };
        int n = vka_csa_find(b, 2, out, 8);
        CHECK(n == 1 && out[0] == 0, "cross-format same-class alias: n=%d", n);
    }

    // 3. Same-format same-class alias is NOT flagged.
    {
        vka_csa_bind b[2] = {
            {7, B10G11R11, 0, 0, 0, 0, 1},
            {7, B10G11R11, 0, 1, 0, 0, 1},
        };
        CHECK(vka_csa_find(b, 2, out, 8) == 0, "same-format same-class should not flag");
    }

    // 4. Different serials are NOT flagged.
    {
        vka_csa_bind b[2] = {
            {2, B10G11R11, 1, 0, 0, 0, 1},
            {46, B10G11R11, 0, 1, 0, 0, 1},
        };
        CHECK(vka_csa_find(b, 2, out, 8) == 0, "different serials should not flag");
    }

    // 5. Different mip levels or disjoint layer ranges on the same serial are NOT flagged.
    {
        vka_csa_bind diff_lv[2] = {
            {2, B10G11R11, 1, 0, 0, 0, 1},
            {2, B10G11R11, 0, 1, 1, 0, 1},
        };
        vka_csa_bind diff_ly[2] = {
            {2, B10G11R11, 1, 0, 0, 0, 1},
            {2, B10G11R11, 0, 1, 0, 1, 1},
        };
        CHECK(vka_csa_find(diff_lv, 2, out, 8) == 0, "different mip levels should not flag");
        CHECK(vka_csa_find(diff_ly, 2, out, 8) == 0, "disjoint layer ranges should not flag");
    }

    // 6. Overlapping layer ranges ([0,2) vs [1,2)) ARE flagged.
    {
        vka_csa_bind b[2] = {
            {9, R32_UINT, 1, 0, 0, 0, 2},
            {9, B10G11R11, 0, 1, 0, 1, 1},
        };
        int n = vka_csa_find(b, 2, out, 8);
        CHECK(n == 1 && out[0] == 0, "overlapping layers should flag: n=%d", n);
    }

    // 7. Two read-only bindings or two writers are NOT flagged.
    {
        vka_csa_bind two_ro[2] = {{5, B10G11R11, 1, 0, 0, 0, 1}, {5, B10G11R11, 0, 0, 0, 0, 1}};
        vka_csa_bind two_rw[2] = {{5, B10G11R11, 1, 1, 0, 0, 1}, {5, B10G11R11, 0, 1, 0, 0, 1}};
        CHECK(vka_csa_find(two_ro, 2, out, 8) == 0, "two read-only should not flag");
        CHECK(vka_csa_find(two_rw, 2, out, 8) == 0, "two writers should not flag");
    }

    // 8. serial == 0 ignored, and max_out is respected.
    {
        vka_csa_bind zero_s[2] = {{0, R32_UINT, 1, 0, 0, 0, 1}, {0, B10G11R11, 0, 1, 0, 0, 1}};
        CHECK(vka_csa_find(zero_s, 2, out, 8) == 0, "serial 0 must be ignored");
        vka_csa_bind many[4] = {
            {3, R32_UINT, 1, 0, 0, 0, 1},
            {3, R32_UINT, 1, 0, 0, 0, 1},
            {3, R32_UINT, 1, 0, 0, 0, 1},
            {3, B10G11R11, 0, 1, 0, 0, 1},
        };
        int capped[2] = {-1, -1};
        int n = vka_csa_find(many, 4, capped, 2);
        CHECK(n == 2 && capped[0] == 0 && capped[1] == 1, "max_out cap: n=%d (%d,%d)", n, capped[0], capped[1]);
    }

    printf("t_android_csalias: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
