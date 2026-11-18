#include <assert.h>
#include <stdio.h>
#include "../../gfx/vk_android/vka_draw_range.h"

int main(void) {
    // Direct: last index ends at the mapping boundary. One byte short must not turn into a shorter draw.
    assert(vkdi_index_span(6, 2, 3, 0) == 6);
    assert(!vkdi_index_span(5, 2, 3, 0));
    assert(!vkdi_index_span(4096, 4, 0x40000001u, 0));
    assert(vkdi_index_span(UINT64_C(0x100000004), 4, 0x40000001u, 0) == UINT64_C(0x100000004));
    // Indirect: firstIndex and count are read at execution. Bind through the last full index before a hole.
    assert(vkdi_index_span(65541, 4, 0, 1) == 65540);
    assert(vkdi_index_span(9, 2, UINT32_MAX, 1) == 8);
    assert(vkdi_index_span(7, 1, 0, 1) == 7);
    assert(!vkdi_index_span(3, 4, 1, 1));
    // The last indirect record is read as well, but not the stride padding following it.
    assert(vkdi_arg_span(1, 64, 20) == 20);
    assert(vkdi_arg_span(3, 64, 20) == 148);
    assert(vkdi_arg_span(65536, 65537, 16) == UINT64_C(0x10000000f));
    // Host-generated index counts: no read of index 0 on an empty loop; never allocate a wrapped output length.
    assert(!vkdi_expanded_count(1, 3));
    assert(vkdi_expanded_count(1, 9) == 12);
    uint32_t last_quad = (UINT32_MAX / 6) * 4;
    assert(vkdi_expanded_count(1, last_quad + 3) == (UINT32_MAX / 6) * 6);
    assert(!vkdi_expanded_count(1, last_quad + 4));
    assert(!vkdi_expanded_count(2, 0));
    assert(vkdi_expanded_count(2, 4) == 5);
    assert(vkdi_expanded_count(2, UINT32_MAX - 1) == UINT32_MAX);
    assert(!vkdi_expanded_count(2, UINT32_MAX));
    puts("t_android_draw_range: ok");
    return 0;
}
