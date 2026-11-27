// PEANUTS: new file. Resolution scale (DM_RES_SCALE, vka_resscale.inc "Resolution scale"): the arithmetic, free of Vulkan so the unit test
// (dm/host/tests/t_android_resscale.c) can check it. The factor is kept in quarters (2 = 0.5x, 3 = 0.75x, 4 = 1x: off, 5 = 1.25x, 6 = 1.5x, 8 = 2x), so every size and
// rectangle is integer arithmetic and the same on every device. A scaled image is the texture's size times the factor, rounded down, at least 1 a side; a rectangle the
// game names in the texture's pixels (a scissor, a clear, a copy region, the present's crop) becomes the image's pixels by rounding both of its edges down, so two
// rectangles that touch still touch and neither a gap nor a doubled row appears between them, and a rectangle that covers the texture covers the image exactly
#ifndef VKA_RESSCALE_H
#define VKA_RESSCALE_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

enum { VKRS_ONE = 4 };   // the factor's unit: quarters
// DM_RES_SCALE's value as quarters: one of the factors offered, else 1x (an unknown value changes nothing rather than guessing)
static inline uint32_t vkrs_parse(const char *s) {
    if (!s || !*s) return VKRS_ONE;
    char *e = NULL; double v = strtod(s, &e);
    if (e == s || !(v > 0.0 && v < 16.0)) return VKRS_ONE;
    uint32_t q = (uint32_t)(v * 4.0 + 0.5); double d = v * 4.0 - q;
    return d > -0.01 && d < 0.01 && (q == 2 || q == 3 || q == 5 || q == 6 || q == 8) ? q : VKRS_ONE;   // (1.4 is not 1.5)
}
// An image side of a texture side v: rounded down, at least 1
static inline uint32_t vkrs_dim(uint32_t v, uint32_t q) { uint64_t r = (uint64_t)v * q >> 2; return r ? (uint32_t)r : 1; }
// An edge at texture pixel v as the image's pixel: rounded down (toward minus infinity, for the negative origins a scissor may have)
static inline int64_t vkrs_px(int64_t v, uint32_t q) { int64_t p = v * (int64_t)q; return p >= 0 ? p >> 2 : -((-p + 3) >> 2); }
// The span [*x0, *x1) of texture pixels as image pixels: both edges rounded down; a span that was not empty keeps at least one pixel (0.5x of a 1-pixel scissor)
static inline void vkrs_span(int64_t *x0, int64_t *x1, uint32_t q) {
    int64_t a = vkrs_px(*x0, q), b = vkrs_px(*x1, q);
    if (*x1 > *x0 && b <= a) b = a + 1;
    *x0 = a; *x1 = b;
}
// The same for an offset and a size inside a texture side of `side` pixels (a copy or clear region): the result lies inside the image's side (vkrs_dim), so a region
// valid for the texture is valid for the image. 0 = nothing of it is inside. The region is cut to the texture before it is scaled: one that starts past the last
// pixel has nothing inside (it was answered with the image's last pixel), and one that starts before pixel 0 keeps what it covers (below 1x its span rounded to nothing)
static inline int vkrs_region(int32_t *off, int32_t *len, uint32_t side, uint32_t q) {
    int64_t a = *off, b = (int64_t)*off + *len, lim = vkrs_dim(side, q);
    if (*len <= 0) return 0;
    if (a < 0) a = 0;
    if (b > (int64_t)side) b = side;
    if (b <= a) return 0;
    vkrs_span(&a, &b, q);
    if (b > lim) b = lim;
    if (a >= lim) { a = lim - 1; b = lim; }   // (the forced single pixel of a span at the texture's last pixel)
    *off = (int32_t)a; *len = (int32_t)(b - a);
    return 1;
}
// The bytes a scaled image takes for one that takes `bytes` at 1x (an estimate before it is made: the memory limit's question)
static inline uint64_t vkrs_bytes(uint64_t bytes, uint32_t q) { return bytes * q * q / 16; }

// Shapes that are not scaled again: a texture of this size, format and flags was scaled and then used in a way a scaled image does not take (vka_resscale.inc
// rs_set_locked's reasons), so the next image of that shape is made at 1x and nothing changes scale back and forth. For the run; a full table denies nothing new
// (the images then keep changing when the use comes again, each change logged)
enum { VKRS_DENY_MAX = 256 };
typedef struct { uint32_t w, h, fmt, flags; uint8_t why; } vkrs_shape;
typedef struct { uint32_t n; vkrs_shape e[VKRS_DENY_MAX]; } vkrs_deny;
static inline int vkrs_deny_find(const vkrs_deny *t, uint32_t w, uint32_t h, uint32_t fmt, uint32_t flags) {
    for (uint32_t i = 0; i < t->n; i++) if (t->e[i].w == w && t->e[i].h == h && t->e[i].fmt == fmt && t->e[i].flags == flags) return (int)i;
    return -1;
}
static inline int vkrs_deny_add(vkrs_deny *t, uint32_t w, uint32_t h, uint32_t fmt, uint32_t flags, uint8_t why) {   // 1 = added, 0 = there already, -1 = full
    if (vkrs_deny_find(t, w, h, fmt, flags) >= 0) return 0;
    if (t->n == VKRS_DENY_MAX) return -1;
    t->e[t->n++] = (vkrs_shape){w, h, fmt, flags, why};
    return 1;
}

// What a translated module does with the resolution scale (the Ryujinx translator's own scale code, read from its support buffer: SupportBuffer.cs, member 5
// render_scale; InstEmitAttribute.cs, TexturePass.cs, HelperFunctionManager.cs): VKRS_SPV_RT: it divides gl_FragCoord by render_scale[0]; VKRS_SPV_TEX: it reads
// render_scale[1 + i] (the helpers of a fetch by integer coordinates from, or a size query of, a plain 2D texture); VKRS_SPV_PIXEL: it has a fetch, an image read or
// a size query of an image that is no buffer or 1D texture (with VKRS_SPV_TEX unset: none of them goes through a helper, which is how the translator leaves texture
// arrays, pool reads and other shapes). binding: the support buffer's binding in set 0 (UINT32_MAX: the module has none). 0 for words that are no module.   // PEANUTS: #734
// fb (may be NULL): the set 0 bindings of the plain 2D textures the module fetches from or sizes by pixel, the ones whose scale the helpers read; a texture it only
// samples by normalized coordinates reads right at any scale (#703: Digimon Story Time Stranger's lighting pass samples a 512x512 asset beside its scaled G-buffer).
// all: such a fetch or query whose image comes from no variable with a binding, or more bindings than b holds: every 2D texture of the stage counts
#define VKRS_FB_MAX 24
typedef struct { uint16_t b[VKRS_FB_MAX]; uint8_t n, all; } vkrs_fb;
static inline int vkrs_fb_has(const vkrs_fb *f, uint32_t binding) {
    if (f->all) return 1;
    for (uint32_t i = 0; i < f->n; i++) if (f->b[i] == binding) return 1;
    return 0;
}
enum { VKRS_SPV_RT = 1, VKRS_SPV_TEX = 2, VKRS_SPV_PIXEL = 4, VKRS_RENDER_SCALE_MEMBER = 5 };
static inline unsigned vkrs_spv_scan(const uint32_t *w, size_t nw, uint32_t binding, vkrs_fb *fb) {   // PEANUTS: #734
    if (nw < 5 || w[0] != 0x07230203u) return 0;
    uint32_t bound = w[3]; if (!bound || bound > (1u << 22)) return 0;
    typedef struct { uint32_t cv, bind, src; uint8_t isc, hasb, set1, dim1; } vkrs_id;   // a 32-bit constant's value; a variable's binding and set + 1; an image's Dim + 1 (types, pointers, values); src: the variable a value comes from + 1   // PEANUTS: #734
    vkrs_id *id = (vkrs_id *)calloc(bound, sizeof *id); if (!id) return 0;
    unsigned out = 0;
    if (fb) { fb->n = 0; fb->all = 0; }   // PEANUTS: #734
    #define VKRS_OK(x) ((x) < bound)
    for (size_t p = 5; p < nw;) {
        uint32_t op = w[p] & 0xffff, n = w[p] >> 16; const uint32_t *a = w + p + 1;
        if (!n || p + n > nw) break;
        switch (op) {
        case 71: if (n >= 4 && VKRS_OK(a[0])) { if (a[1] == 33) { id[a[0]].bind = a[2]; id[a[0]].hasb = 1; } else if (a[1] == 34) id[a[0]].set1 = (uint8_t)(a[2] < 254 ? a[2] + 1 : 255); } break;   // OpDecorate Binding / DescriptorSet
        case 43: if (n == 4 && VKRS_OK(a[1])) { id[a[1]].cv = a[2]; id[a[1]].isc = 1; } break;                                                                                                // OpConstant, 32 bits
        case 25: if (n >= 4 && VKRS_OK(a[0])) id[a[0]].dim1 = (uint8_t)(a[2] < 254 ? a[2] + 1 : 0); break;                                                                                   // OpTypeImage
        case 27: case 28: case 29: if (n >= 3 && VKRS_OK(a[0]) && VKRS_OK(a[1])) id[a[0]].dim1 = id[a[1]].dim1; break;                                                                        // OpTypeSampledImage, arrays of them
        case 32: if (n >= 4 && VKRS_OK(a[0]) && VKRS_OK(a[2])) id[a[0]].dim1 = id[a[2]].dim1; break;                                                                                          // OpTypePointer
        case 59: case 61: case 83: case 86: case 100: if (n >= 3 && VKRS_OK(a[0]) && VKRS_OK(a[1])) id[a[1]].dim1 = id[a[0]].dim1;                                                            // OpVariable, OpLoad, OpCopyObject, OpSampledImage, OpImage: their type's   // PEANUTS: #734
            if (n >= 3 && VKRS_OK(a[1])) id[a[1]].src = op == 59 ? a[1] + 1 : n >= 4 && VKRS_OK(a[2]) ? id[a[2]].src : 0;                                                                    // the variable itself; the operand's
            break;
        case 65: case 66:                                                                                                                                                                    // OpAccessChain, OpInBoundsAccessChain
            if (n >= 4 && VKRS_OK(a[0]) && VKRS_OK(a[1])) id[a[1]].dim1 = id[a[0]].dim1;
            if (n >= 4 && VKRS_OK(a[1]) && VKRS_OK(a[2])) id[a[1]].src = id[a[2]].src;   // PEANUTS: #734
            if (n >= 5 && VKRS_OK(a[2]) && VKRS_OK(a[3]) && id[a[2]].hasb && id[a[2]].bind == binding && id[a[2]].set1 <= 1 && id[a[3]].isc && id[a[3]].cv == VKRS_RENDER_SCALE_MEMBER)
                out |= n >= 6 && VKRS_OK(a[4]) && id[a[4]].isc && id[a[4]].cv == 0 ? VKRS_SPV_RT : VKRS_SPV_TEX;
            break;
        case 95: case 98: case 103: case 104:                                                                                                                                                // OpImageFetch, OpImageRead, OpImageQuerySizeLod, OpImageQuerySize
            if (n >= 4 && VKRS_OK(a[2]) && id[a[2]].dim1 >= 2 && id[a[2]].dim1 <= 5) out |= VKRS_SPV_PIXEL;   // Dim 2D, 3D, Cube, Rect
            if (fb && op != 98 && n >= 4 && VKRS_OK(a[2]) && id[a[2]].dim1 == 2) {                          // a fetch or size query of a 2D image: its binding (the helpers' texture)   // PEANUTS: #734
                uint32_t v = id[a[2]].src;
                if (!v || !VKRS_OK(v - 1) || !id[v - 1].hasb || id[v - 1].set1 > 1) fb->all = 1;
                else if (!vkrs_fb_has(fb, id[v - 1].bind)) { if (fb->n < VKRS_FB_MAX) fb->b[fb->n++] = (uint16_t)id[v - 1].bind; else fb->all = 1; }
            }
            break;
        default: break;
        }
        p += n;
    }
    #undef VKRS_OK
    free(id);
    return out;
}
#endif
