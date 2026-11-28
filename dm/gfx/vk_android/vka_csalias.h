#pragma once
#include <stdint.h>

// Cross-format compute storage aliasing (vka_draw.c cs_img_alias_prep, vka_tex.c vka_tex_alias_copy):
// Binding the same image subresource simultaneously as a read-only storage image in one view format or numeric
// class and a written storage image in another in the same dispatch causes an 8x4 wave/tile hazard on Adreno.
typedef struct {
    uint64_t serial;   // underlying image serial (0 = not a candidate)
    uint32_t fmt;      // Vulkan view format (VkFormat)
    uint8_t cls;       // numeric class the shader declares: 0 float, 1 uint, 2 sint
    uint8_t rw;        // 1 = shader writes or atomics on this binding, 0 = read-only
    uint32_t lv;       // storage view mip level
    uint32_t ly0, nly; // storage view layer range [ly0, ly0 + nly)
} vka_csa_bind;

// Records into out_idx (at most max_out) the indices of read-only bindings that alias any written binding on
// an overlapping subresource through a differing numeric class or view format. Returns total matches stored.
static inline int vka_csa_find(const vka_csa_bind *b, int n, int *out_idx, int max_out) {
    int out = 0;
    for (int i = 0; i < n && out < max_out; i++) {
        if (b[i].rw || !b[i].serial) continue;
        for (int j = 0; j < n; j++) {
            if (j == i || !b[j].rw || b[j].serial != b[i].serial) continue;
            if (b[i].lv != b[j].lv) continue;
            if (b[i].ly0 >= b[j].ly0 + b[j].nly || b[j].ly0 >= b[i].ly0 + b[i].nly) continue;
            if (b[i].cls == b[j].cls && (!b[i].fmt || !b[j].fmt || b[i].fmt == b[j].fmt)) continue;
            out_idx[out++] = i;
            break;
        }
    }
    return out;
}
