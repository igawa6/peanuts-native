// PEANUTS: deferred inline uniform buffer updates (coalescing, overlap test, and CPU shadow apply), pure C with no Vulkan dependency.
// Used by dm/gfx/vk_android/vka_draw.c (vka_rp_ub_defer, ub_overlaps, ub_apply) and checked by dm/host/tests/t_android_ubdefer.c.
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

enum { VKA_UB_MAX = 256 };

typedef struct {
    uint64_t dst, n, cap;
    uint8_t *data;
} vka_ub_ent;

typedef struct {
    int n;
    uint64_t lo, hi;
    vka_ub_ent e[VKA_UB_MAX];
} vka_ub_q;

static inline void vka_ub_reset(vka_ub_q *q) {
    q->n = 0;
    q->lo = 0;
    q->hi = 0;
}

static inline void vka_ub_free(vka_ub_q *q) {
    for (int i = 0; i < VKA_UB_MAX; i++) free(q->e[i].data);
    memset(q, 0, sizeof *q);
}

static inline int vka_ub_overlaps(const vka_ub_q *q, uint64_t addr, uint64_t size) {
    uint64_t b = size <= UINT64_MAX - addr ? addr + size : UINT64_MAX;   // PEANUTS: bounded draw ranges
    if (!q->n || !size || b <= q->lo || addr >= q->hi) return 0;
    for (int i = 0; i < q->n; i++) {
        if (q->e[i].dst < b && addr < q->e[i].dst + q->e[i].n) return 1;
    }
    return 0;
}

static inline void vka_ub_apply(const vka_ub_q *q, uint64_t addr, void *out, size_t size) {
    uint64_t b = size <= UINT64_MAX - addr ? addr + (uint64_t)size : UINT64_MAX;
    if (!q->n || !size || b <= q->lo || addr >= q->hi) return;
    uint8_t *c = (uint8_t *)out;
    for (int i = 0; i < q->n; i++) {
        uint64_t lo = q->e[i].dst > addr ? q->e[i].dst : addr;
        uint64_t hi = q->e[i].dst + q->e[i].n < b ? q->e[i].dst + q->e[i].n : b;
        if (lo < hi) memcpy(c + (lo - addr), q->e[i].data + (lo - q->e[i].dst), (size_t)(hi - lo));
    }
}

// Coalesce an inline update [dst, dst + n) into q, preserving write order.
// Returns 1 if kept (setting *changed = 1 if bytes or bounds changed, 0 if an identical repeat was skipped), 0 if q is full or allocation failed.
static inline int vka_ub_defer(vka_ub_q *q, uint64_t dst, uint64_t n, const void *data, int *changed) {
    if (changed) *changed = 0;
    if (!n || dst > UINT64_MAX - n) return 0;
    uint64_t end = dst + n;
    int slot = -1;
    for (int i = q->n - 1; i >= 0; i--) {
        uint64_t lo = q->e[i].dst, hi = lo + q->e[i].n;
        if (lo <= dst && end <= hi) {
            if (!memcmp(q->e[i].data + (dst - lo), data, (size_t)n)) return 1;
            slot = i;
            break;
        }
        if (lo <= end && dst <= hi) {
            slot = i;
            break;
        }
    }
    if (slot >= 0) {
        uint64_t lo = q->e[slot].dst, hi = lo + q->e[slot].n;
        uint64_t new_lo = dst < lo ? dst : lo, new_hi = end > hi ? end : hi;
        uint64_t need = new_hi - new_lo;
        if (need > q->e[slot].cap) {
            uint64_t nc = need < 256 ? 256 : need * 2;
            uint8_t *p = (uint8_t *)realloc(q->e[slot].data, (size_t)nc);
            if (!p) return 0;
            q->e[slot].data = p;
            q->e[slot].cap = nc;
        }
        if (new_lo < lo) memmove(q->e[slot].data + (lo - new_lo), q->e[slot].data, (size_t)(hi - lo));
        q->e[slot].dst = new_lo;
        memcpy(q->e[slot].data + (dst - new_lo), data, (size_t)n);
        q->e[slot].n = need;
        if (new_lo < q->lo) q->lo = new_lo;
        if (new_hi > q->hi) q->hi = new_hi;
    } else {
        if (q->n == VKA_UB_MAX) return 0;
        int i = q->n;
        if (n > q->e[i].cap) {
            uint64_t nc = n < 256 ? 256 : n * 2;
            uint8_t *p = (uint8_t *)realloc(q->e[i].data, (size_t)nc);
            if (!p) return 0;
            q->e[i].data = p;
            q->e[i].cap = nc;
        }
        memcpy(q->e[i].data, data, (size_t)n);
        q->e[i].dst = dst;
        q->e[i].n = n;
        if (!q->n) {
            q->lo = dst;
            q->hi = end;
        } else {
            if (dst < q->lo) q->lo = dst;
            if (end > q->hi) q->hi = end;
        }
        q->n++;
    }
    if (changed) *changed = 1;
    return 1;
}
