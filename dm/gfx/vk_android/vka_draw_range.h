// Byte ranges consumed by Vulkan draw input, independent of device state.
#pragma once
#include <stdint.h>

// A direct draw starts at index 0 of this binding. For an indirect draw both
// firstIndex and indexCount are GPU inputs: bind every complete mapped index.
static inline uint64_t vkdi_index_span(uint64_t mapped, unsigned width, uint32_t count, int indirect) {
    uint64_t bytes = (uint64_t)count * width;
    return indirect ? mapped & ~(uint64_t)(width - 1) : bytes <= mapped ? bytes : 0;
}

static inline uint64_t vkdi_arg_span(uint32_t n, uint32_t stride, uint32_t record_bytes) {
    return n ? (uint64_t)(n - 1) * stride + record_bytes : 0;
}

// 1: quads as triangles; 2: a closed line strip. Reject a result that cannot
// be expressed by vkCmdDrawIndexed before allocating or writing its indices.
static inline uint32_t vkdi_expanded_count(int expand, uint32_t count) {
    uint64_t n = expand == 1 ? (uint64_t)(count / 4) * 6 : (uint64_t)count + 1;
    return count && n <= UINT32_MAX ? (uint32_t)n : 0;
}
