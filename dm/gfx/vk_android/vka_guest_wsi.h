// PEANUTS: under the window mutex. Invalidating the generation rejects new work while
// surfaceDestroyed drains in-flight WSI calls. Vulkan handles remain owned by the guest.
#pragma once
#include <stdint.h>

typedef struct { uint64_t epoch; unsigned users; int available; } guest_window_state;

static inline void guest_window_invalidate(guest_window_state *s) {
    s->available = 0;
    ++s->epoch;
}

static inline int guest_window_enter(guest_window_state *s, uint64_t epoch) {
    if (!s->available || !epoch || epoch != s->epoch) return 0;
    ++s->users;
    return 1;
}
