// PEANUTS: draws that write no colour on Qualcomm's own driver (docs/reference/rendering.md "Effects in 32x32 cells on Qualcomm's driver: a draw that
// writes no colour gets wrong pixel shader inputs"). The driver (512.676, Adreno 740) gives such a draw's pixel shader wrong inputs: every pixel of a 16x16
// block reads one stale value. With any colour channel written they are right. DM_VK_DEPTH_ONLY_BLEND (vka_pso.c pstate_fill) writes every channel of the
// targets chosen here through blend ZERO, ONE, which leaves them as they were.
// Header only, no Vulkan headers: dm/host/tests/t_android_zonly.c tests it.
#ifndef VKA_ZONLY_H
#define VKA_ZONLY_H
#include <stdint.h>

// Unsigned and signed normalized and sRGB colour formats (VkFormat numbers of the core 1.0 formats). Blending clamps their source to the format's range first,
// so ZERO times a NaN or infinite pixel shader output cannot reach the target; a float target would take the NaN
static inline int vka_zonly_fixed(uint32_t f) {
    static const uint8_t fx[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 22, 23, 24, 29, 30, 31, 36, 37, 38, 43, 44, 45, 50, 51, 52, 57, 58, 59, 64, 65,
                                 70, 71, 77, 78, 84, 85, 91, 92};
    for (unsigned i = 0; i < sizeof fx; i++) if (f == fx[i]) return 1;
    return 0;
}

// The colour targets (bit per attachment) to write through blend ZERO, ONE: none when any channel of any target is written; else each target with a format
// that is fixed point and blendable (blendable: bit per attachment, from the device) and that the pixel shader writes (fout_mask)
static inline uint32_t vka_zonly_targets(int ncolor, const uint32_t *fmt, const uint8_t *mask, uint32_t fout_mask, uint32_t blendable) {
    uint32_t r = 0;
    for (int i = 0; i < ncolor && i < 8; i++) {
        if (mask[i]) return 0;
        if (fmt[i] && (fout_mask >> i & 1) && (blendable >> i & 1) && vka_zonly_fixed(fmt[i])) r |= 1u << i;
    }
    return r;
}
#endif
