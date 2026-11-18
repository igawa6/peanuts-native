// PEANUTS: the time between the game's own presented frames, in a ring buffer in the host (peanuts_frametime.c), for the launcher's frame-time graph
// (Bridge.frameTimes). Generated frames (frame generation) are not counted: the present path calls peanuts_tick once per shown game frame.
#pragma once
#include <stddef.h>
#include <stdint.h>

// The ring holds this many intervals; the newest are kept
#define PN_FT_CAP 512

// One presented frame at now_ns (CLOCK_MONOTONIC). The first call only remembers the time; later calls store the interval since the previous call, in
// microseconds, unless it is over 1 s (a pause, a menu or a load: it would flatten the graph). The previous time is always updated
void pn_ft_push(uint64_t now_ns);
// pn_ft_push with the current CLOCK_MONOTONIC time
void pn_ft_note(void);
// Copies the newest min(n, stored, PN_FT_CAP) intervals into out_ms, oldest first, in milliseconds; returns how many
int pn_ft_read(float *out_ms, int n);
// Empties the ring and forgets the last time (for tests)
void pn_ft_reset(void);
