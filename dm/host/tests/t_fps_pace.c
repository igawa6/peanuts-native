// PEANUTS: the frame limiter keeps a deadline within one period (fps_pace.h, GitHub issue #887)
// The frame limiter's schedule (fps_pace.h) on a simulated clock: a frame takes `work` of CPU time, then the limiter says when to wake.
// A cap raised while the game runs (30 to 45, 60 to 45 after a faster stretch) or a 30 fps movie ending must hold the new rate (issue: the cap was lost for good)
#include "../fps_pace.h"
#include <assert.h>
#include <stdio.h>
// The schedule as it was before: a lead of more than one period meant no wait, and the deadline still moved on by a period
static uint64_t legacy_next(uint64_t *next, uint64_t now, uint64_t period) {
    uint64_t wake = 0;
    if (*next > now && *next - now <= period) { wake = *next; now = *next; }
    *next = (*next > now || (*next && now - *next < period / 2) ? *next : now) + period;
    return wake;
}
typedef uint64_t (*pace_fn)(uint64_t *, uint64_t, uint64_t);
// Runs n frames at the period, each doing `work` ns, from time *t; returns the average frame time in ns
static double run(pace_fn f, uint64_t *next, uint64_t *t, uint64_t period, uint64_t work, int n) {
    uint64_t t0 = *t;
    for (int i = 0; i < n; i++) { *t += work; uint64_t w = f(next, *t, period); if (w > *t) *t = w; }
    return (double)(*t - t0) / n;
}
#define MS 1000000ull
int main(void) {
    const uint64_t p30 = 1000 * MS / 30, p45 = 1000 * MS / 45, p60 = 1000 * MS / 60;
    uint64_t next = 0, t = 1000 * MS;
    // a steady cap
    assert(run(fps_pace_next, &next, &t, p45, 8 * MS, 200) > 0.99 * p45);
    // the cap raised from 30 to 45 (the in-game panel, or a 30 fps movie over): the new rate holds
    run(fps_pace_next, &next, &t, p30, 8 * MS, 100);
    double avg = run(fps_pace_next, &next, &t, p45, 8 * MS, 300);
    printf("30 -> 45: %.2f ms a frame (want %.2f)\n", avg / 1e6, p45 / 1e6);
    assert(avg > 0.99 * p45 && avg < 1.01 * p45);
    // 60 -> 45 and 30 -> 60 as well, with frames that are cheap and dear
    run(fps_pace_next, &next, &t, p60, 5 * MS, 100);
    avg = run(fps_pace_next, &next, &t, p45, 5 * MS, 300);
    assert(avg > 0.99 * p45 && avg < 1.01 * p45);
    run(fps_pace_next, &next, &t, p30, 20 * MS, 100);
    avg = run(fps_pace_next, &next, &t, p60, 5 * MS, 300);
    assert(avg > 0.99 * p60 && avg < 1.01 * p60);
    // a frame longer than the period starts a new schedule, as before; a lowered cap waits the longer period
    run(fps_pace_next, &next, &t, p45, 100 * MS, 1);
    avg = run(fps_pace_next, &next, &t, p30, 8 * MS, 200);
    assert(avg > 0.99 * p30 && avg < 1.01 * p30);
    // what it was: the same 30 -> 45 run lost the cap
    next = 0; t = 1000 * MS;
    run(legacy_next, &next, &t, p30, 8 * MS, 100);
    avg = run(legacy_next, &next, &t, p45, 8 * MS, 300);
    printf("30 -> 45 before the change: %.2f ms a frame\n", avg / 1e6);
    assert(avg < 0.5 * p45);
    puts("t_fps_pace: ok");
    return 0;
}
