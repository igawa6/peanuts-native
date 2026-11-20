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
    return 0;
}
