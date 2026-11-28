// PEANUTS: draws that write no colour on Qualcomm's own driver (docs/reference/rendering.md "Effects in 32x32 cells on Qualcomm's driver: a draw that
// writes no colour gets wrong pixel shader inputs"). The driver (512.676, Adreno 740) gives such a draw's pixel shader wrong inputs: every pixel of a 16x16
// block reads one stale value. With any colour channel written they are right. DM_VK_DEPTH_ONLY_BLEND (vka_pso.c pstate_fill) writes every channel of the
// targets chosen here through blend ZERO, ONE, which leaves them as they were.
// Header only, no Vulkan headers: dm/host/tests/t_android_zonly.c tests it.
#ifndef VKA_ZONLY_H
#define VKA_ZONLY_H
#include <stdint.h>

// Unsigned normalized and sRGB colour formats (VkFormat numbers of the core 1.0 formats). Blending clamps their source to the format's range first,
// so ZERO times a NaN or infinite pixel shader output cannot reach the target; a float target would take the NaN. Not signed normalized: there blending
// ONE times the target turns -128 (-1.0) into -127, so a target meant to stay as it was could change by one code
static inline int vka_zonly_fixed(uint32_t f) {
    static const uint8_t fx[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 22, 23, 29, 30, 36, 37, 43, 44, 50, 51, 57, 58, 64, 70, 77, 84, 91};
    for (unsigned i = 0; i < sizeof fx; i++) if (f == fx[i]) return 1;
    return 0;
}

// The colour targets (bit per attachment) whose pixel shader output is of the float class (DMSH_VK_TYPE_FLOAT, 0, in fout_types' 2 bits per target), as the
// draw takes them (vka_draw.c out_cls): none with the DMSH_VK_MOD_UINT module (all_uint), not uint_mask's with DMSH_VK_MOD_UINT_MIXED. A target of another
// class has its mask set to 0 by the draw (Vulkan leaves the mismatch undefined), and blending it would be undefined too
static inline uint32_t vka_zonly_float_outs(uint32_t fout_types, uint32_t uint_mask, int all_uint) {
    uint32_t r = 0;
    if (all_uint) return 0;
    for (int i = 0; i < 8; i++) if (!(uint_mask >> i & 1) && (fout_types >> (2 * i) & 3) == 0) r |= 1u << i;
    return r;
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
