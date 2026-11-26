// PEANUTS: which shader image bindings are viewed as their texel's raw bits (dm/gfx/vk_android/vka_raw32.h; vka_tex.c id_view): an integer-class shader image over a
// 4-byte colour texture, with the profile's image_raw32 on, is R32_UINT / R32_SINT (FIFA 18's GPU texture compressors read RGBA8 kits with SULD.D .B32); nothing else is
#include <stdio.h>
#include "../../gfx/vk_android/vka_raw32.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    enum { FLOAT = 0, UINT = 1, SINT = 2 };
    CHECK(raw32_class(1, 1, UINT, 4, 1, 1, 1) == 1, "a uint shader image over RGBA8 was not viewed as R32_UINT (only the red byte read: red kits and faces)");
    CHECK(raw32_class(1, 1, SINT, 4, 1, 1, 1) == 2, "a sint shader image over RGBA8 was not viewed as R32_SINT");
    CHECK(raw32_class(0, 1, UINT, 4, 1, 1, 1) == 0, "the raw view was taken with image_raw32 off (every other game keeps the per-channel view)");
    CHECK(raw32_class(1, 0, UINT, 4, 1, 1, 1) == 0, "a sampled view was made raw (only shader images are raw surface loads)");
    CHECK(raw32_class(1, 1, FLOAT, 4, 1, 1, 1) == 0, "a float shader image was made raw");
    CHECK(raw32_class(1, 1, UINT, 8, 1, 1, 1) == 0, "an 8-byte texel was made a 4-byte raw view");
    CHECK(raw32_class(1, 1, UINT, 2, 1, 1, 1) == 0, "a 2-byte texel was made a 4-byte raw view");
    CHECK(raw32_class(1, 1, UINT, 4, 4, 4, 1) == 0, "a block-compressed format was made a raw view");
    CHECK(raw32_class(1, 1, UINT, 4, 1, 1, 0) == 0, "a depth or stencil image was made a raw colour view");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
