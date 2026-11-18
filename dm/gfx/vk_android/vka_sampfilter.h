// PEANUTS: filter compatibility for direct combined samplers, using the final view's optimal-tiling format features.
#pragma once
#include "../nvn/nvn_obj.h"

// VkFormatFeatureFlagBits, asserted by vka_tex.c so the host test doesn't need Vulkan headers.
enum { VKSF_LINEAR = 0x1000, VKSF_MINMAX = 0x10000 };

static inline unsigned vksf_required(const samp_par *p, int device_minmax) {
    if ((p->minf == NVN_MIN_FILTER_NEAREST || p->minf == NVN_MIN_FILTER_NEAREST_MIPMAP_NEAREST) &&
        p->magf != NVN_MAG_FILTER_LINEAR && !(p->aniso > 1)) return 0;
    if (device_minmax && (p->reduction == NVN_SAMPLER_REDUCTION_MIN || p->reduction == NVN_SAMPLER_REDUCTION_MAX)) return VKSF_MINMAX;
    return p->cmp_mode == NVN_COMPARE_MODE_COMPARE_R_TO_TEXTURE ? 0 : VKSF_LINEAR;
}

// Keep mip access (and the caller's LOD range and bias), but do not interpolate within or across mip levels.
static inline void vksf_nearest(samp_par *p) {
    p->minf = p->minf >= NVN_MIN_FILTER_NEAREST_MIPMAP_NEAREST ? NVN_MIN_FILTER_NEAREST_MIPMAP_NEAREST : NVN_MIN_FILTER_NEAREST;
    p->magf = NVN_MAG_FILTER_NEAREST;
    p->aniso = 1;
}
