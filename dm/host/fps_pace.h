// PEANUTS: the host frame limiter's schedule (hle_fpslock.c fps_limit_wait), kept apart so a test can drive it with its own clock.
// *next is the deadline of the coming frame (0 = no schedule yet). The return value is the time to sleep until (0 = do not wait); *next is moved on by one period.
// A deadline further than one period ahead is the lead a longer period left (the cap raised from 30 to 45 or 60, a 30 fps movie over): it is cut to one period. Kept, it
// outran the frames for good: each frame moved it on a whole period, frames came sooner than that, so the wait was never taken again and the cap was lost until a long stall
#ifndef DM_FPS_PACE_H
#define DM_FPS_PACE_H
#include <stdint.h>
static inline uint64_t fps_pace_next(uint64_t *next, uint64_t now, uint64_t period) {
    uint64_t wake = 0;
    if (*next > now + period) *next = now + period;
    if (*next > now) { wake = *next; now = *next; }
    *next = (*next > now || (*next && now - *next < period / 2) ? *next : now) + period;   // keep the phase through small lateness, restart after a long frame
    return wake;
}
#endif
