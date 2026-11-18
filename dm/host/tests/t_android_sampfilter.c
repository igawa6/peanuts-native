// PEANUTS: Yoshi's registered 1x1 RGBA8_UINT volume and filterable material images share a sampler.
#include <assert.h>
#include <stdio.h>
#include "../../gfx/vk_android/vka_sampfilter.h"

int main(void) {
    samp_par guest = {.minf = NVN_MIN_FILTER_LINEAR_MIPMAP_NEAREST, .magf = NVN_MAG_FILTER_LINEAR,
        .ws = NVN_WRAP_MODE_REPEAT, .wt = NVN_WRAP_MODE_REPEAT, .wr = NVN_WRAP_MODE_REPEAT,
        .aniso = 1, .lod_max = 9, .lod_bias = -0.5f, .border = {0, 1, 0, 1}};
    // Weighted linear sampling requires FILTER_LINEAR, even on a one-level image. Only the incompatible pairing is changed.
    assert(vksf_required(&guest, 1) == VKSF_LINEAR);
    samp_par integer = guest;
    vksf_nearest(&integer);
    assert(integer.magf == NVN_MAG_FILTER_NEAREST && integer.minf == NVN_MIN_FILTER_NEAREST_MIPMAP_NEAREST);
    assert(integer.lod_max == guest.lod_max && integer.lod_bias == guest.lod_bias && integer.ws == guest.ws && integer.wt == guest.wt && integer.wr == guest.wr);
    assert(!memcmp(integer.border, guest.border, sizeof guest.border));
    assert(!vksf_required(&integer, 1) && guest.minf == NVN_MIN_FILTER_LINEAR_MIPMAP_NEAREST && guest.magf == NVN_MAG_FILTER_LINEAR);

    // MipmapMode alone is enough; hardware with MINMAX but no LINEAR can still use min/max filtering and vice versa.
    guest.magf = NVN_MAG_FILTER_NEAREST; guest.minf = NVN_MIN_FILTER_NEAREST_MIPMAP_LINEAR;
    assert(vksf_required(&guest, 1) == VKSF_LINEAR);
    guest.reduction = NVN_SAMPLER_REDUCTION_MIN; assert(vksf_required(&guest, 1) == VKSF_MINMAX);
    guest.reduction = NVN_SAMPLER_REDUCTION_MAX; assert(vksf_required(&guest, 1) == VKSF_MINMAX);
    assert(vksf_required(&guest, 0) == VKSF_LINEAR);   // sampler_of uses weighted average without the device feature
    guest.reduction = NVN_SAMPLER_REDUCTION_AVERAGE; guest.cmp_mode = NVN_COMPARE_MODE_COMPARE_R_TO_TEXTURE;
    assert(!vksf_required(&guest, 1));   // depth comparison filtering has its own capabilities

    guest.cmp_mode = NVN_COMPARE_MODE_NONE; guest.minf = NVN_MIN_FILTER_LINEAR; guest.aniso = 8;
    vksf_nearest(&guest);
    assert(guest.minf == NVN_MIN_FILTER_NEAREST && guest.aniso == 1 && !vksf_required(&guest, 1));   // no mip levels introduced
    puts("t_android_sampfilter: ok");
    return 0;
}
