// PEANUTS: the "Preparing graphics" notice (dev.peanuts.ui.WarmNotice, GameActivity; android/README.md, In-game notices). With DM_VK_PSO_ASYNC a draw or dispatch whose
// pipeline the cache lacks is skipped until a background thread has made it (vka_pso.c). On a game's first run that can be most of the 3D scene for a minute: EA SPORTS
// FC 27 on an AYN Thor (Turnip) showed a black pitch with only the HUD while 517 pipelines were made in the background, and the only word on screen was the
// skipped-work notice blaming the GPU driver. Those skips are now counted apart from the driver's refusals (vka_draw.c g_skip_warm, vka_pso_last_pending), and this
// says while they happen. Pure counters, free of Android and Vulkan so the Mac's unit test (dm/host/tests/t_android_warmnote.c) can check them.
#ifndef VKA_WARMNOTE_H
#define VKA_WARMNOTE_H
#include <stdint.h>

enum { VKW_WINDOW = 30 };   // frames in a window (half a second at 60)

typedef struct {
    uint64_t f0, warm0;   // frame and skips at the window's start
    int on, waiting;      // shown; the pipelines waiting when the last window ended
} vkwarm;

// Called each frame with warm, the draws and dispatches skipped so far because their pipeline is being made in the background, and waiting, the pipelines queued or
// being made now. At the end of each window: shown when that window skipped draws this way and pipelines are still waiting; hidden otherwise (all made, or nothing
// on screen lacks one). Returns whether it shows.
static inline int vkwarm_step(vkwarm *s, uint64_t frame, uint64_t warm, int waiting) {
    if (!s->f0) { s->f0 = frame ? frame : 1; s->warm0 = warm; return s->on; }
    if (frame < s->f0 + VKW_WINDOW) return s->on;
    uint64_t d = warm - s->warm0;
    s->f0 = frame; s->warm0 = warm;
    s->waiting = waiting > 0 ? waiting : 0;
    s->on = d > 0 && s->waiting > 0;
    return s->on;
}
#endif
