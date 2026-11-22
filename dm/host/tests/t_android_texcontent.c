// PEANUTS(upstream-candidate): which texture first used as a sample takes its content from its pool storage (dm/gfx/vk_android/vka_texcontent.h; vka_tex.c storage_mode): the conditions of the
// rule on Switch 2 (NVN2: coherent memory, the kartnative rule: the storage may be empty at the first use) and on Switch 1 (NVN1: the storage must hold data at the
// first use), and the shapes that must stay out of it. The accepted NVN1 shape is FINAL FANTASY XII THE ZODIAC AGE's target-ring texture, measured 2026-11-20.
#include <stdio.h>
#include "../../gfx/vk_android/vka_texcontent.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

// A plain colour texture (fmt kind 1: uncompressed, not depth / stencil) in a CPU-writable cached pool, first use a sample, no packaged layout
#define OK_T 0u
#define OK_P (NVN_MEMORY_POOL_FLAGS_CPU_CACHED | NVN_MEMORY_POOL_FLAGS_GPU_CACHED)

int main(void) {
    // Switch 2 takes its storage at the first sample, empty storage included (tracked from zeros: the rule as kartnative wrote it)
    CHECK(vka_tc_sampled_content(1, OK_T, OK_P, 0, 0, 1, 1, 0) == 1, "the plain NVN2 shape was kept out, empty storage included");
    // Switch 1 takes it only where the CPU's work shows: data in the storage at the first use
    CHECK(vka_tc_sampled_content(0, OK_T, OK_P, 0, 0, 1, 1, 1) == 1, "the plain NVN1 shape with data was kept out");
    CHECK(vka_tc_sampled_content(0, OK_T, OK_P, 0, 0, 1, 1, 0) == 0, "a zeroed NVN1 storage was taken as the texture's content");

    // the shape measured on FINAL FANTASY XII THE ZODIAC AGE: texture flags 0x8 (COMPRESSIBLE), pool flags 0xa4 (CPU_CACHED | GPU_CACHED | COMPRESSIBLE), data at first use
    CHECK(vka_tc_sampled_content(0, 0x8u, 0xa4u, 0, 0, 1, 1, 1) == 1, "the FFXII ring texture was kept out of the NVN1 rule");

    // exclusions, both generations
    CHECK(vka_tc_sampled_content(1, OK_T, OK_P, 0, 0, 1, 0, 1) == 0, "a texture not first used as a sample took its storage");
    CHECK(vka_tc_sampled_content(0, OK_T, OK_P, 0, 0, 1, 0, 1) == 0, "a texture not first used as a sample took its storage (NVN1)");
    CHECK(vka_tc_sampled_content(1, OK_T, OK_P, 1, 0, 1, 1, 1) == 0, "a packaged layout took its storage (NVN2)");
    CHECK(vka_tc_sampled_content(0, OK_T, OK_P, 1, 0, 1, 1, 1) == 0, "a packaged layout took its storage (NVN1)");
    CHECK(vka_tc_sampled_content(1, OK_T, OK_P, 0, 1, 1, 1, 1) == 0, "a block-compressed format took its storage through this rule (it has its own)");
    CHECK(vka_tc_sampled_content(1, OK_T, OK_P, 0, 0, 3, 1, 1) == 0, "a depth / stencil format took its storage");
    CHECK(vka_tc_sampled_content(1, OK_T, NVN_MEMORY_POOL_FLAGS_CPU_NO_ACCESS, 0, 0, 1, 1, 1) == 0, "a CPU_NO_ACCESS pool's texture took its storage");
    CHECK(vka_tc_sampled_content(1, OK_T, NVN_MEMORY_POOL_FLAGS_VIRTUAL, 0, 0, 1, 1, 1) == 0, "a virtual pool's texture took its storage through this rule (it has its own)");
    CHECK(vka_tc_sampled_content(1, NVN_TEXTURE_FLAGS_IMAGE, OK_P, 0, 0, 1, 1, 1) == 0, "an IMAGE texture took its storage");
    CHECK(vka_tc_sampled_content(1, NVN_TEXTURE_FLAGS_LINEAR, OK_P, 0, 0, 1, 1, 1) == 0, "a LINEAR texture took its storage through this rule (its rows are tracked already)");
    CHECK(vka_tc_sampled_content(1, NVN_TEXTURE_FLAGS_LINEAR_RENDER_TARGET, OK_P, 0, 0, 1, 1, 1) == 0, "a LINEAR_RENDER_TARGET texture took its storage");
    CHECK(vka_tc_sampled_content(1, NVN_TEXTURE_FLAGS_SPARSE, OK_P, 0, 0, 1, 1, 1) == 0, "a SPARSE texture took its storage through this rule (it has its own)");

    // COMPRESSIBLE: out on NVN2 (kartnative's rule), in on NVN1 (FFXII flags its whole UI pool: a CPU-filled texture holds plain texels)
    CHECK(vka_tc_sampled_content(1, NVN_TEXTURE_FLAGS_COMPRESSIBLE, OK_P, 0, 0, 1, 1, 1) == 0, "a COMPRESSIBLE texture took its storage on NVN2");
    CHECK(vka_tc_sampled_content(0, NVN_TEXTURE_FLAGS_COMPRESSIBLE, OK_P, 0, 0, 1, 1, 1) == 1, "a COMPRESSIBLE texture with data was kept out on NVN1");

    // a CPU_UNCACHED pool is CPU-writable: the fill's conditions (a flush, data) stay in storage_mode, not here
    CHECK(vka_tc_sampled_content(0, OK_T, NVN_MEMORY_POOL_FLAGS_CPU_UNCACHED, 0, 0, 1, 1, 1) == 1, "a CPU_UNCACHED pool's texture with data was kept out");

    printf("t_android_texcontent: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
