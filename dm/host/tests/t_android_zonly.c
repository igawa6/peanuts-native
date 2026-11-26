// PEANUTS: draws that write no colour on Qualcomm's driver (dm/gfx/vk_android/vka_zonly.h): which colour targets DM_VK_DEPTH_ONLY_BLEND writes through blend ZERO, ONE
#include <stdio.h>
#include "../../gfx/vk_android/vka_zonly.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

enum { RGBA8 = 37, SRGB8 = 43, BGRA8 = 44, A2B10G10R10 = 64, RGBA8UI = 41, RGBA16F = 97, R32F = 100, B10G11R11 = 122 };

int main(void) {
    uint32_t w;
    // Naruto x Boruto's reduction draw: two RGBA8 targets, masks 0, the pixel shader writes both
    w = vka_zonly_targets(2, (const uint32_t[]){RGBA8, RGBA8}, (const uint8_t[]){0, 0}, 3, 3);
    CHECK(w == 3, "both RGBA8 targets written through the blend (%#x)", w);
    // any channel written: the draw is drawn as asked
    w = vka_zonly_targets(2, (const uint32_t[]){RGBA8, RGBA8}, (const uint8_t[]){0, 8}, 3, 3);
    CHECK(w == 0, "a target with a channel written: none (%#x)", w);
    // float targets would take ZERO times a NaN output; integer targets cannot blend
    w = vka_zonly_targets(4, (const uint32_t[]){RGBA16F, R32F, B10G11R11, RGBA8UI}, (const uint8_t[]){0, 0, 0, 0}, 0xf, 0xf);
    CHECK(w == 0, "float and integer targets: none (%#x)", w);
    w = vka_zonly_targets(3, (const uint32_t[]){RGBA16F, SRGB8, A2B10G10R10}, (const uint8_t[]){0, 0, 0}, 7, 7);
    CHECK(w == 6, "only the fixed-point ones among float targets (%#x)", w);
    // a target the pixel shader does not write (its output undefined), one the device cannot blend, an unused slot
    w = vka_zonly_targets(3, (const uint32_t[]){RGBA8, BGRA8, RGBA8}, (const uint8_t[]){0, 0, 0}, 5, 3);
    CHECK(w == 1, "written by the shader and blendable only (%#x)", w);
    w = vka_zonly_targets(8, (const uint32_t[]){RGBA8, 0, 0, 0, 0, 0, 0, 0}, (const uint8_t[]){0, 0, 0, 0, 0, 0, 0, 0}, 0xff, 0xff);
    CHECK(w == 1, "undefined slots left out (%#x)", w);
    CHECK(vka_zonly_targets(0, NULL, NULL, 0xff, 0xff) == 0, "no colour target: none");

    printf("t_android_zonly: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
