// PEANUTS(upstream-candidate): which texture first used as a sample takes its content from its pool storage (vka_tex.c storage_mode), as pure logic, free of Android and Vulkan so a unit
// test on Linux can check it (dm/host/tests/t_android_texcontent.c).
// On a Switch a texture is its storage: what the CPU wrote there is what the GPU reads. The replay keeps images in memory of its own, so the CPU's content must be
// copied in, and what says the storage holds it differs by NVN generation:
//   Switch 2 (NVN2): memory is coherent (the driver's FlushMappedRange and FlushTexels are bare returns), so a sampled texture's content is its storage at its first
//   use and after: the storage may still be empty then and the CPU fills it later (the rule tracks it and re-uploads on the CPU's writes). has_data is not needed.
//   Switch 1 (NVN1): the game's writes can sit in the CPU cache, and the host sees the storage only when the game flushes (storage_mode keeps the flush path). The
//   rule takes the storage only where the CPU has written it, shown by data in the storage at the first use. An all-zero storage keeps the old behaviour (GPU
//   content): Switch 1 games ran with cleared images, and a reused pool slot's leftover bytes are not the new texture's content. Measured on FINAL FANTASY XII THE
//   ZODIAC AGE's target rings (2026-11-20): a 128x128 RGBA8 texture in a CPU_CACHED | GPU_CACHED | COMPRESSIBLE pool, stored by the CPU before its first use,
//   never flushed: the host made a zero image and every target circle and line drew transparent.
// The exclusions: IMAGE, the two LINEAR flags and SPARSE have content rules of their own (their formats and uses: storage_mode and the entries above). COMPRESSIBLE
// (UBWC: the GPU may store the texture compressed) keeps out on NVN2, as kartnative's rule has it; on NVN1 the data check is the evidence, so the flag stays out
// only of NVN2: a texture the CPU fills holds plain texels, and one the GPU wrote is GPU content (gpu_image_over, and gpu_owned ends the tracking). FINAL FANTASY
// XII flags its whole UI pool and these textures COMPRESSIBLE (pool flags 0xa4, texture flags 0x8).
#ifndef VKA_TEXCONTENT_H
#define VKA_TEXCONTENT_H
#include "../nvn/include/nvn.h"

// nvn2: a Switch 2 game's coherent NVN (vka_tex.c vka_nvn2). texflags: the texture's NVN_TEXTURE_FLAGS, poolflags: its pool's NVN_MEMORY_POOL_FLAGS (nvn.h).
// has_layout: the texture carries a packaged layout; compressed_fmt: a block-compressed format (its own rule); fmt_kind: the format's class, 3 and up is depth /
// stencil, never CPU content; sampled: the texture's first use is a sample; has_data: the storage holds non-zero bytes at that first use (NVN2 does not consult it)
static inline int vka_tc_sampled_content(int nvn2, unsigned texflags, unsigned poolflags, int has_layout, int compressed_fmt, int fmt_kind, int sampled, int has_data) {
    if (!sampled || has_layout || compressed_fmt || fmt_kind >= 3) return 0;
    if (poolflags & (NVN_MEMORY_POOL_FLAGS_CPU_NO_ACCESS | NVN_MEMORY_POOL_FLAGS_VIRTUAL)) return 0;
    if (texflags & (NVN_TEXTURE_FLAGS_IMAGE | NVN_TEXTURE_FLAGS_LINEAR | NVN_TEXTURE_FLAGS_LINEAR_RENDER_TARGET | NVN_TEXTURE_FLAGS_SPARSE)) return 0;
    if (nvn2) return !(texflags & NVN_TEXTURE_FLAGS_COMPRESSIBLE);
    return has_data;
}
#endif
