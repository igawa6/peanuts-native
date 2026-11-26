// PEANUTS: composite-pass upscaler (DM_VK_CUPSCALE). A game whose final (composite) pass samples its 3D picture at the internal size and stretches it to the window
// in the same pass that draws the UI (Snowdrop: Mario + Rabbids Sparks of Hope) gets that picture upscaled by FSR 1 (EASU + RCAS) or SGSR 1 into a host image of the
// window's size before the pass, and the composite draw samples that image in its place (about 1:1), so the game's grade and UI stay as they are and native.
// The pass: the profile's upscale_pass <vs key> <ps key> <binding> (game.tsv), or DM_VK_CUPSCALE_PASS=<vs>,<ps>,<binding> (vs 0 = any).
// DM_VK_CUPSCALE (live): 0 off (kill switch), 1 the game's own bilinear (default), 2 FSR 1, 4 SGSR 1. DM_VK_CUPSCALE_SHARP (live): sharpness in stops as
// DM_VK_FSR_SHARP (0 the most, 0.25 default, below 0 none). vka_cupscale.c; the hook is vka_draw.c cup_pre.
// The arming rules and the texel-size patch below are pure (no Vulkan): tests include this header alone with VKA_CUP_LOGIC_ONLY.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

enum {
    VKA_CUP_ARM = 0,     // upscale
    VKA_CUP_OFF,         // DM_VK_CUPSCALE=0
    VKA_CUP_BILINEAR,    // DM_VK_CUPSCALE=1: the game's own stretch
    VKA_CUP_BAD_MODE,    // another value
    VKA_CUP_EQUAL,       // the 3D picture is not smaller than the output (100 %)
    VKA_CUP_LARGER,      // larger on one axis and smaller on the other
    VKA_CUP_TINY,        // under 16 texels on a side
    VKA_CUP_ASPECT,      // not the output's aspect (within 1 texel)
    VKA_CUP_BINDING,     // the binding is not a 2D texture (or not bound)
    VKA_CUP_FORMAT,      // the 3D picture is not an 8-bit UNORM colour (sRGB, float, integer...)
    VKA_CUP_SHAPE,       // multisampled, layered, no sampled use
    VKA_CUP_TARGET,      // no colour target 0
    VKA_CUP_RESSCALE,    // DM_RES_SCALE is on (the sizes the game sees are not the images')
    VKA_CUP_UBO,         // a uniform buffer with a texel-size field is too large to copy
    VKA_CUP_GPU,         // the device refused something (push descriptors, a pipeline, an image)
    VKA_CUP_N
};
static inline const char *vka_cup_why(int r) {
    static const char *const s[VKA_CUP_N] = {"armed", "DM_VK_CUPSCALE=0: off", "DM_VK_CUPSCALE=1: the game's own bilinear stretch", "DM_VK_CUPSCALE is not 0, 1, 2 or 4",
        "the 3D picture is not smaller than the output (nothing to upscale)", "the 3D picture is larger than the output on one axis", "the 3D picture is under 16 texels on a side",
        "the 3D picture's aspect is not the output's", "the binding is not a bound 2D texture", "the 3D picture is not an 8-bit UNORM colour format",
        "the 3D picture is multisampled, layered or not sampled", "the pass has no colour target 0", "DM_RES_SCALE is on", "its texel-size uniform buffer is over 64 KB",
        "the device refused the upscaler's objects"};
    return r >= 0 && r < VKA_CUP_N ? s[r] : "?";
}
// mode: DM_VK_CUPSCALE; s: the 3D picture's size, o: the composite target's (output) size
static inline int vka_cup_decide(int mode, uint32_t sw, uint32_t sh, uint32_t ow, uint32_t oh) {
    if (mode == 0) return VKA_CUP_OFF;
    if (mode == 1) return VKA_CUP_BILINEAR;
    if (mode != 2 && mode != 4) return VKA_CUP_BAD_MODE;
    if (sw >= ow && sh >= oh) return VKA_CUP_EQUAL;
    if (sw > ow || sh > oh) return VKA_CUP_LARGER;
    if (sw < 16 || sh < 16) return VKA_CUP_TINY;
    int64_t d = (int64_t)sw * oh - (int64_t)sh * ow; if (d < 0) d = -d;   // |sw/sh - ow/oh| within one texel of the 3D picture on either axis
    if (d > (int64_t)ow || d > (int64_t)oh) return VKA_CUP_ASPECT;
    return VKA_CUP_ARM;
}
// The composite's texel-size field: a float pair (w, h) equal to the 3D picture's size, or (1/w, 1/h), at an 8-byte step in the first 256 bytes of a uniform buffer the
// pixel shader reads, rewritten to the output's size (or its reciprocal) so the shader's own filter (Snowdrop's smoothstep sample: x = u * size - 0.5, a texel's fraction
// eased, back to uv) runs on the output's texel grid, where it lands on texel centres. Returns the pairs rewritten
static inline int vka_cup_rcp_eq(float a, float b) { return fabsf(a - b) <= 1e-6f * fabsf(b); }
static inline int vka_cup_patch_texel(uint8_t *ub, size_t n, uint32_t sw, uint32_t sh, uint32_t ow, uint32_t oh) {
    int k = 0; if (n > 256) n = 256;
    for (size_t i = 0; i + 8 <= n; i += 8) {
        float f[2]; memcpy(f, ub + i, 8);
        if (f[0] == (float)sw && f[1] == (float)sh) { f[0] = (float)ow; f[1] = (float)oh; memcpy(ub + i, f, 8); k++; }
        else if (vka_cup_rcp_eq(f[0], 1.0f / (float)sw) && vka_cup_rcp_eq(f[1], 1.0f / (float)sh)) { f[0] = 1.0f / (float)ow; f[1] = 1.0f / (float)oh; memcpy(ub + i, f, 8); k++; }
    }
    return k;
}
// Whether a uniform buffer's first bytes hold such a field (the same test, nothing written)
static inline int vka_cup_has_texel(const uint8_t *ub, size_t n, uint32_t sw, uint32_t sh) {
    uint8_t t[256]; if (n > 256) n = 256; memcpy(t, ub, n);
    return vka_cup_patch_texel(t, n, sw, sh, sw + 1, sh + 1) > 0;
}
// DM_VK_CUPSCALE_SHARP in stops → RCAS's con0.x (FsrRcasCon: 2^-stops) and SGSR's EdgeSharpness (2.0, Qualcomm's default, at 0.25 stops and below; halved per stop above; 0 = none)
static inline float vka_cup_rcas(float stops) { return exp2f(-stops); }
static inline float vka_cup_sgsr_edge(float stops) { return stops < 0 ? 0.0f : 2.0f * exp2f(-(stops > 0.25f ? stops - 0.25f : 0.0f)); }
// DM_VK_CUPSCALE_PASS=<vs>,<ps>,<binding> (hex keys, decimal binding; vs 0 = any): 1 = parsed
static inline int vka_cup_parse_pass(const char *s, uint64_t *vs, uint64_t *ps, uint32_t *binding) {
    if (!s || !*s) return 0;
    char *e; unsigned long long a = strtoull(s, &e, 16); if (e == s || *e != ',') return 0; s = e + 1;
    unsigned long long b = strtoull(s, &e, 16); if (e == s || *e != ',' || !b) return 0; s = e + 1;
    unsigned long c = strtoul(s, &e, 10); if (e == s || (*e && *e != ' ' && *e != '\n') || c > 0xffff) return 0;
    *vs = a; *ps = b; *binding = (uint32_t)c; return 1;
}

#ifndef VKA_CUP_LOGIC_ONLY
#include "vka.h"
typedef struct { uint64_t vs, ps; uint32_t binding; int from_env; } vka_cup_pass;
int vka_cupscale_pass(vka_cup_pass *p);   // the pass to watch: the profile's upscale_pass, DM_VK_CUPSCALE_PASS over it; 0 = none (every other game: one compare a draw)
// Records the upscale of src (sv: its sampled view, GENERAL; sw x sh) into the host image of ow x oh into cb, outside any render pass, between full barriers.
// mode 2 FSR 1, 4 SGSR 1. Returns that image's sampled view (GENERAL) for the composite draw, or VK_NULL_HANDLE (then *why says why: VKA_CUP_GPU)
VkImageView vka_cupscale_run(VkCommandBuffer cb, int mode, VkImageView sv, uint32_t sw, uint32_t sh, uint32_t ow, uint32_t oh, float stops, int *why);
#endif
