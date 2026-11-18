// Which tracked textures follow the CPU's writes to their storage (dm/gfx/vk_android/vka_wbfollow.h): one the GPU never wrote always does; a linear one the GPU wrote does once
// the copy of its GPU content back into the storage has run on the GPU (then the storage is the image, as on the Switch, where a linear texture is plain memory: a glyph cache
// the game clears on the GPU and fills on the CPU, Mario Tennis Fever's 8192x2048 R8); not while that copy is queued or its submission is unfinished, nor when no copy-back
// ever ran; a block-linear one the GPU wrote never does (its content stays the GPU's)
#include <stdio.h>
#include "../../gfx/vk_android/vka_wbfollow.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

int main(void) {
    CHECK(wbf_follows(0, 0, 0, 0, 0) == 1, "a texture the GPU never wrote stopped following its storage");
    CHECK(wbf_follows(0, 1, 0, 0, 5) == 1, "a linear texture the GPU never wrote stopped following its storage");
    CHECK(wbf_follows(1, 1, 0, 10, 10) == 1, "a GPU-written linear texture whose copy-back has run ignores the CPU's later writes (blank glyph cache)");
    CHECK(wbf_follows(1, 1, 0, 10, 12) == 1, "a GPU-written linear texture whose copy-back ran long ago ignores the CPU's later writes");
    CHECK(wbf_follows(1, 1, 0, 10, 9) == 0, "re-uploaded before its copy-back ran on the GPU: the storage still held older bytes than the image");
    CHECK(wbf_follows(1, 1, 1, 10, 12) == 0, "re-uploaded while a newer copy-back is queued: the GPU's latest write would be lost");
    CHECK(wbf_follows(1, 1, 0, 0, 12) == 0, "re-uploaded though no copy-back ever ran (the storage never held the GPU's content)");
    CHECK(wbf_follows(1, 0, 0, 10, 12) == 0, "a block-linear texture the GPU wrote was re-uploaded from its storage");
    // The scan that a copy-back wait asks for visits only the waiting images; a full scan visits all. Walking every live image each frame for the wait cost Mario Kart World
    // 20-34 ms of texture prepare a frame (0.5-4 ms with the switch off)
    CHECK(wbf_visit(1, 0) == 1, "a full scan skipped an image that was not waiting");
    CHECK(wbf_visit(1, 1) == 1, "a full scan skipped a waiting image");
    CHECK(wbf_visit(0, 1) == 1, "the wait-only scan skipped an image waiting for its copy-back (its CPU writes would never be uploaded)");
    CHECK(wbf_visit(0, 0) == 0, "the wait-only scan walked an image that was not waiting (the cost of the wait)");
    // A shader image marks its texture GPU-written only when the shader writes it (the library's DMSH_VK_BF_RW): Mario Tennis Fever's text compute reads the glyph cache
    // as image 0 and writes image 1; marked written at the read, the cache was copied back whole over glyphs the CPU had just drawn into its storage (letters lost)
    enum { RW = 1, ATOMIC = 2 };
    CHECK(wbf_view_of_binding(0, 0, RW) == 0, "a sampled binding asked for a storage view");
    CHECK(wbf_view_of_binding(0, RW, RW) == 0, "a sampled binding asked for a storage view");
    CHECK(wbf_view_of_binding(1, 0, RW) & VKA_VIEW_STORAGE, "a read-only shader image got no storage view");
    CHECK(!wbf_view_writes(wbf_view_of_binding(1, 0, RW)), "a shader image the shader only reads marked its texture GPU-written (glyph cache copied back over the CPU's glyphs)");
    CHECK(!wbf_view_writes(wbf_view_of_binding(1, ATOMIC, RW)), "a flag other than the write flag marked a shader image written");
    CHECK(wbf_view_writes(wbf_view_of_binding(1, RW, RW)), "a shader image the shader writes was not marked GPU-written (its content would be re-uploaded from stale storage)");
    CHECK(wbf_view_writes(wbf_view_of_binding(1, RW | ATOMIC, RW)), "a shader image with atomics was not marked GPU-written");
    CHECK(wbf_view_writes(VKA_VIEW_STORAGE), "a host pass's storage output (no binding flags) was not marked GPU-written");
    CHECK(!wbf_view_writes(0), "a sampled view marked its texture GPU-written");
    printf("%s: %s\n", __FILE__, fails ? "FAILED" : "ok");
    return fails != 0;
}
