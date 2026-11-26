// PEANUTS: whether a shader image binding is viewed as its texel's raw bits (vka_tex.c id_view), free of Vulkan so the Mac's unit test (dm/host/tests/t_android_raw32.c)
// can check it.
#ifndef VKA_RAW32_H
#define VKA_RAW32_H

// A shader image of an integer class over a 4-byte texture of another class is the texel's raw bits, r32ui / r32i: the translator declares the raw surface loads and
// stores (SULD.D / SUST.D .B32, InstEmitSurface.cs GetTextureFormat) and the atomics that way, and that is what the Switch hands the shader whatever the texture's format.
// The per-channel twin (vka_tex.c class_twin: RGBA8_UNORM read as RGBA8_UINT) reads only the red byte: FIFA 18's GPU texture compressors (compress_bc1 / compress_bc3,
// SportsRNA::Utility::DXTBatchJob) load the composited RGBA8 kits and faces with SULD.D .B32, so every kit and face came out red with alpha 0.
// on: the profile's image_raw32 (DM_VK_RAW32 overrides); storage: a shader image, not a sampled view; want: the shader's class (1 uint, 2 sint, 0 float); bpb, bw, bh: the
// view format's bytes per block and block size; color: a colour aspect. → 1 R32_UINT, 2 R32_SINT, 0 none (the twin, as before)
static inline int raw32_class(int on, int storage, int want, unsigned bpb, unsigned bw, unsigned bh, int color) {
    return on && storage && (want == 1 || want == 2) && bpb == 4 && bw == 1 && bh == 1 && color ? want : 0;
}
#endif
