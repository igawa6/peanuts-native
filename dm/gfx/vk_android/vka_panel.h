// PEANUTS: the panel's refresh grid as the display reports it (VK_GOOGLE_display_timing, vka_present.c timing_poll), for two uses:
//   - the host's pacing clocks tick in whole refreshes of the panel instead of a free-running 60.000 Hz (DM_VK_PACE_PANEL: the acquire pacer in hle_nvn.c, the frame
//     limiter in hle_fpslock.c, the vi vsync event in hle_sdk16.c). An AYN Thor's panel refreshes every 16.71-16.72 ms (59.83 Hz): a game on the 60.000 Hz clock ran
//     0.28 % faster than the panel, filled the present queue and waited about 24 ms in an acquire every few seconds
//   - each game frame gets a desired present time on that grid, a whole number of refreshes after the one before (DM_VK_PRESENT_GRID). Before, a frame had a time only
//     when its period was over 1.5 refreshes, and that time was the last one plus 33.333 ms, which drifts across a 33.43 ms pair of refreshes every few hundred frames
// The parts here are free of Android and Vulkan so a unit test on Linux can check them (dm/host/tests/t_android_panel.c)
#ifndef VKA_PANEL_H
#define VKA_PANEL_H
#include <stdint.h>

enum {
    VKPN_MIN_NS = 5000000, VKPN_MAX_NS = 25000000,   // a refresh period outside 5-25 ms (200-40 Hz) is not believed
    VKPN_FIRST = 32,    // refreshes of unbroken readings before the first estimate is given out
    VKPN_BLOCK = 256,   // refreshes a block spans: the estimate is the block's whole span over its refreshes, so one reading's jitter is divided by the block's length
    VKPN_BAD = 8,       // readings in a row off the grid: the panel changed, the estimate is dropped
    VKPN_GAP = 8,       // more refreshes than this between two readings is a pause, not an interval to measure
};
typedef struct {
    uint64_t seed;      // the driver's refresh period (vkGetRefreshCycleDurationGOOGLE): tells how many refreshes an interval is until there is an estimate
    uint64_t period;    // the estimate (ns; 0 = none: the callers fall back)
    uint64_t last;      // the newest present time on the grid (the grid's phase)
    uint64_t t0;        // the block's first present time
    uint32_t n, blocks; // refreshes since t0; whole blocks measured since the last reset
    uint32_t bad;       // readings in a row off the grid
    uint32_t resets;    // times the estimate was dropped (read by the test; no log line has it)
} vkpanel;

static inline uint64_t vkpn_absdiff(uint64_t a, uint64_t b) { return a > b ? a - b : b - a; }
static inline void vkpanel_reset(vkpanel *p, uint64_t t) { p->period = 0; p->last = p->t0 = t; p->n = p->blocks = p->bad = 0; }
// The driver's refresh period. One within 1 % of the last is the same panel mode (the driver's own figure wanders by 0.01-0.02 Hz); another starts over
static inline void vkpanel_seed(vkpanel *p, uint64_t refresh_ns) {
    if (refresh_ns < VKPN_MIN_NS || refresh_ns > VKPN_MAX_NS) { if (p->seed) { p->seed = 0; vkpanel_reset(p, 0); } return; }
    if (p->seed && vkpn_absdiff(p->seed, refresh_ns) * 100 < p->seed) return;
    p->seed = refresh_ns; vkpanel_reset(p, 0);
}
// One actual present time, in the order the display reports them. Intervals between presents are whole numbers of refreshes (a game frame held for two, a refresh
// with no new image), so each interval is divided by the count the current period gives it, and the estimate is a block's span over its refreshes.
// Returns 1 when the estimate was dropped at this reading (the caller reads the driver's refresh period again)
static inline int vkpanel_sample(vkpanel *p, uint64_t t) {
    if (!t || !p->seed) return 0;
    if (!p->last) { p->last = p->t0 = t; p->n = 0; return 0; }
    if (t <= p->last) return 0;   // the same refresh again, or a report out of order
    uint64_t ref = p->period ? p->period : p->seed, d = t - p->last, k = (d + ref / 2) / ref;
    if (k > VKPN_GAP) { p->last = p->t0 = t; p->n = 0; p->bad = 0; return 0; }   // a pause (a load, the app in the background): the period stays, the block starts again
    if (k < 1 || vkpn_absdiff(d, k * ref) > ref / 16) {   // off the grid: one such reading is left out; several in a row are a new grid
        if (++p->bad < VKPN_BAD) return 0;
        int had = p->period != 0; vkpanel_reset(p, t); p->resets += (uint32_t)had; return 1;
    }
    p->bad = 0; p->last = t; p->n += (uint32_t)k;
    if (p->n < VKPN_FIRST) return 0;
    uint64_t est = (t - p->t0) / p->n;
    if (est < VKPN_MIN_NS || est > VKPN_MAX_NS) { vkpanel_reset(p, t); return 0; }
    if (!p->blocks) p->period = est;   // the first block: the running figure, better with every reading
    if (p->n < VKPN_BLOCK) return 0;
    if (p->blocks && vkpn_absdiff(est, p->period) * 200 > p->period) { vkpanel_reset(p, t); p->resets++; return 1; }   // a block 0.5 % off the estimate: the period jumped
    if (p->blocks) p->period = (p->period + est + 1) / 2;
    p->blocks++; p->t0 = t; p->n = 0;
    return 0;
}
// A pacing period as whole refreshes of the panel: the nearest whole number of them when that is within 2 % of the period asked for (16.667 ms on a 59.83 Hz panel is
// one refresh, 33.333 ms two, 16.667 ms on a 120 Hz panel two), else the period as it is (45 fps on a 60 Hz panel, 60 fps on a 90 Hz one). panel_ns 0 = not known
// PEANUTS: Thor's nominal 120 Hz mode measures 118.52 Hz, a 1.25 % period difference. The old 1 % fit rejected it while the present grid accepted four refreshes
// for 30 fps, so the limiter/acquire/VI clocks drifted against presentation. Keep these clocks on the same measured grid; incompatible rates still fall back.
static inline uint64_t vkpanel_fit(uint64_t period_ns, uint64_t panel_ns) {
    if (!panel_ns || !period_ns) return period_ns;
    uint64_t n = (period_ns + panel_ns / 2) / panel_ns;
    return n >= 1 && vkpn_absdiff(n * panel_ns, period_ns) * 100 <= period_ns * 2 ? n * panel_ns : period_ns;
}
// The grid point nearest to t, and the first one at or after t (the grid: anchor + i * r, on both sides of the anchor)
static inline uint64_t vkpanel_near(uint64_t anchor, uint64_t r, uint64_t t) {
    if (t >= anchor) return anchor + (t - anchor + r / 2) / r * r;
    uint64_t back = (anchor - t + r / 2) / r * r; return anchor > back ? anchor - back : anchor;
}
static inline uint64_t vkpanel_up(uint64_t anchor, uint64_t r, uint64_t t) {
    if (t >= anchor) return anchor + (t - anchor + r - 1) / r * r;
    return anchor - (anchor - t) / r * r;
}
// The refreshes a frame period holds an image for: the whole number within 0.15 refresh of it (the rule frame generation's hold has, vka_framegen.c), 0 when it is
// none (45 fps on a 60 Hz panel, a period under one refresh): such a frame is presented as before
static inline uint32_t vkpanel_hold(uint64_t period_ns, uint64_t r) {
    if (!r || !period_ns) return 0;
    uint64_t n = (period_ns + r / 2) / r;
    return n >= 1 && n <= 64 && vkpn_absdiff(n * r, period_ns) * 100 <= r * 15 ? (uint32_t)n : 0;
}
// The chain of desired present times: each game frame's target is the grid point one hold after the last target. A frame that comes too late for it (its target less
// than half a refresh away, so the time handed to the system would be in the past) takes the first grid point it can have, and the chain goes on from there: the
// lateness is paid once, not carried. The time to hand to vkQueuePresentKHR is the target minus half a refresh (the system shows a frame at the first refresh not
// before its desired time). Returns the target, 0 when the period is not a whole number of refreshes (the chain ends)
typedef struct { uint64_t last; } vkgrid;
// PEANUTS(upstream-candidate): Android reports the queue lead required by its compositor; a future API timestamp alone is not a reachable display target.
static inline uint64_t vkgrid_next_deadline(vkgrid *g, uint64_t anchor, uint64_t r, uint64_t period_ns, uint64_t now, uint64_t deadline_ns, int *late) {
    uint32_t hold = vkpanel_hold(period_ns, r);
    if (late) *late = 0;
    if (!hold || !anchor) { g->last = 0; return 0; }
    uint64_t lead = deadline_ns > r / 2 ? deadline_ns : r / 2;   // PEANUTS: preserve the old fallback when display timing is unavailable
    uint64_t first = now + lead, t = g->last ? vkpanel_near(anchor, r, g->last + (uint64_t)hold * r) : 0;
    // (a target more than eight holds ahead cannot come from frames in the swapchain's queue: the clock or the grid moved, start again)
    if (t < first || t > first + 8ull * hold * r) { if (late) *late = g->last != 0; t = vkpanel_up(anchor, r, first); }
    g->last = t;
    return t;
}
// PEANUTS: callers without Android display metadata retain the previous fallback.
static inline uint64_t vkgrid_next(vkgrid *g, uint64_t anchor, uint64_t r, uint64_t period_ns, uint64_t now, int *late) {
    return vkgrid_next_deadline(g, anchor, r, period_ns, now, 0, late);
}
#endif
