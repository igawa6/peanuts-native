// A texture over the mip levels of another (dm/gfx/nvn/nvn_tex.c nvn_tex_level_of, nvn_tex_parent_off): Mario Tennis Fever makes a 3584x3584 depth texture (format 0x33)
// whose storage is level 1 of its 7168x7168 four-level shadow map (0xC400000 bytes in, 0x3100000 bytes long: the log's serials 3145 and 3146). On the Switch both are one
// storage; the Vulkan replay made the second an image of its own, so what the game drew through it never reached the shadow map's level 1 (a tree's shadow half drawn)
#include <stdio.h>
#include "../../gfx/nvn/nvn_tex.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static nvn_texdesc tex(int fmt, uint32_t w, uint32_t h, uint32_t levels) {
    return (nvn_texdesc){.target = 1, .format = fmt, .flags = 0xc, .samples = 1, .width = w, .height = h, .depth = 1, .levels = levels};
}

int main(void) {
    nvn_texdesc map = tex(0x33, 7168, 7168, 4), lv1 = tex(0x33, 3584, 3584, 1);
    unsigned k = 0;
    CHECK(nvn_tex_level_of(&map, &lv1, 0xC400000, &k) && k == 1, "Mario Tennis Fever's 3584x3584 depth texture is not found as level 1 of its 7168x7168 shadow map (k %u)", k);
    CHECK(nvn_tex_parent_off(&lv1, 1) == 0xC400000, "level 1 of a texture twice its size is looked for at %#llx, not 0xC400000", (unsigned long long)nvn_tex_parent_off(&lv1, 1));
    nvn_texdesc lv12 = tex(0x33, 3584, 3584, 2);
    k = 0;
    CHECK(nvn_tex_level_of(&map, &lv12, 0xC400000, &k) && k == 1, "a two-level texture over levels 1 and 2 is not found (k %u)", k);
    nvn_texdesc lv2 = tex(0x33, 1792, 1792, 1);
    k = 0;
    CHECK(nvn_tex_level_of(&map, &lv2, nvn_tex_level_offset(&map, 2), &k) && k == 2, "a texture over level 2 is not found (k %u)", k);
    CHECK(nvn_tex_parent_off(&lv2, 2) == nvn_tex_level_offset(&map, 2), "level 2 of a texture four times its size is looked for at the wrong place");
    // Not a level: the wrong place, another format, size or shape, more levels than are left, level 0 (the same storage: same_shape's), linear or layered textures
    CHECK(!nvn_tex_level_of(&map, &lv1, 0xC400000 + 0x1000, &k), "a texture starting inside level 1 is taken for it");
    CHECK(!nvn_tex_level_of(&map, &lv1, 0, &k), "a texture at the map's own start is taken for a level");
    nvn_texdesc other = tex(0x25, 3584, 3584, 1);
    CHECK(!nvn_tex_level_of(&map, &other, 0xC400000, &k), "a texture of another format is taken for the level");
    nvn_texdesc wide = tex(0x33, 3584, 1792, 1);
    CHECK(!nvn_tex_level_of(&map, &wide, 0xC400000, &k), "a texture of another size is taken for the level");
    nvn_texdesc deep = tex(0x33, 3584, 3584, 4);
    CHECK(!nvn_tex_level_of(&map, &deep, 0xC400000, &k), "a texture with more levels than the map has left is taken for its levels");
    nvn_texdesc lin = lv1; lin.flags |= 0x10;
    CHECK(!nvn_tex_level_of(&map, &lin, 0xC400000, &k), "a linear texture is taken for a block-linear level");
    nvn_texdesc arr = map; arr.target = 4; arr.depth = 2;
    nvn_texdesc arr1 = lv1; arr1.target = 4; arr1.depth = 2;
    CHECK(!nvn_tex_level_of(&arr, &arr1, nvn_tex_level_offset(&arr, 1), &k), "a layered texture is taken for a level (its layers are not one run of storage)");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
