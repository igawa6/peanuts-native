// How a texture-to-texture copy is done on Vulkan (dm/gfx/vk_android/vka_t2tpath.h): a plain copy when it can be one; a scaled or mirrored copy is a blit only into a format the
// driver reports as a blit destination. Vulkan does not require BLIT_DST for depth formats, and Adreno 830's system driver has none for D16, D32, D24S8 or D32S8. Mario Tennis
// Fever fills its 7168x7168 static shadow map's level 1 with a scaled depth copy from level 0; blitted there, the level stayed unwritten and far shadows were lost
#include <stdio.h>
#include "../../gfx/vk_android/vka_t2tpath.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    enum { COLOR = 0, UINT = 1, DEPTH = 3, STENCIL = 4, DS = 5 };
    CHECK(t2t_path(1, DEPTH, DEPTH, 0, 0, 0) == T2T_COPY, "a same-size depth copy was not a plain copy");
    CHECK(t2t_path(1, COLOR, COLOR, 0, 0, 1) == T2T_COPY, "a same-size colour copy was not a plain copy");
    CHECK(t2t_path(0, COLOR, COLOR, 0, 0, 1) == T2T_BLIT, "a scaled colour copy into a blit destination was not a blit");
    CHECK(t2t_path(0, DEPTH, DEPTH, 0, 0, 1) == T2T_BLIT, "a scaled depth copy into a format that is a blit destination was not a blit");
    CHECK(t2t_path(0, DEPTH, DEPTH, 0, 0, 0) == T2T_DEPTH_DRAW, "a scaled depth copy into a format that is no blit destination was not drawn (the shadow map's level 1 stays unwritten)");
    CHECK(t2t_path(0, DS, DS, 0, 0, 0) == T2T_NONE, "a scaled depth-stencil copy into no blit destination was blitted (invalid; a draw cannot write stencil)");
    CHECK(t2t_path(0, STENCIL, STENCIL, 0, 0, 0) == T2T_NONE, "a scaled stencil copy into no blit destination was blitted (invalid)");
    CHECK(t2t_path(0, COLOR, COLOR, 0, 0, 0) == T2T_NONE, "a scaled colour copy into a format that is no blit destination was blitted (invalid)");
    CHECK(t2t_path(0, UINT, UINT, 0, 0, 1) == T2T_NONE, "a scaled integer copy was blitted");
    CHECK(t2t_path(0, COLOR, DEPTH, 0, 0, 1) == T2T_NONE, "a copy between a colour and a depth format was blitted");
    CHECK(t2t_path(0, COLOR, COLOR, 1, 0, 1) == T2T_NONE, "a scaled compressed copy was blitted");
    CHECK(t2t_path(0, DEPTH, DEPTH, 0, 1, 0) == T2T_NONE, "a scaled multisampled depth copy was drawn");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
