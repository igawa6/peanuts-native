// PEANUTS: the Unreal 4.27 allocator's page alignment outside the small pools (hle_uemalloc.c align_of, #236)
// Run with section garbage collection: only the allocator alignment policy is needed, not the guest loader.
#include <stdbool.h>
#include "../hle_uemalloc.c"
#include <assert.h>

int main(void) {
    ue4_page_allocations = true;
    assert(align_of(0xf000, 64) == 4096);
    assert(align_of(128 * 1024, 16) == 16);
    assert(align_of(128 * 1024 + 1, 0) == 4096);
    assert(align_of(64, 8192) == 8192);
    ue4_page_allocations = false;
    assert(align_of(0xf000, 64) == 64);
    assert(align_of(128 * 1024 + 1, 0) == 16);
    // A 16-aligned block below 16 bytes asks malloc for 16: jemalloc's 8-byte class is 8-aligned (PhysX refuses it, Tetris Effect: Connected, #455)
    assert(malloc16_size(0) == 16 && malloc16_size(1) == 16 && malloc16_size(8) == 16);
    assert(malloc16_size(16) == 16 && malloc16_size(17) == 17 && malloc16_size(4096) == 4096);
    return 0;
}
