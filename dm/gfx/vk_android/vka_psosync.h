// PEANUTS: which draws are not skipped while their pipeline is made in the background (DM_VK_PSO_ASYNC; vka_draw.c draw_body, vka_pso.c vka_gfx_pipeline_now), as pure
// logic, free of Android and Vulkan so a unit test on Linux can check it (dm/host/tests/t_android_psosync.c).
// With the asynchronous threads on, a draw whose pipeline the cache lacks is skipped until a background thread has made it. That costs the draw in a few frames, which
// is the point, except where the game keeps what the draw made: then the picture stays wrong after every pipeline is there. Super Mario Odyssey on an AYN Thor (Adreno
// 740, Turnip), first run (no pipeline cache, no prewarm list): black with only the HUD for the whole session, `7 % of draws skipped ... pipelines not made 11521`,
// ~650 pipelines made in the background in the first minute and still black with none waiting; right with DM_VK_PSO_ASYNC=0, and right once the cache existed.
// So two kinds of draw get their pipeline on the frame path, as with DM_VK_PSO_ASYNC=0. The rule is fixed: no switch, on whenever the asynchronous threads are:
//   a draw into a small target, at most 64x64 texels in the game's own pixels, whatever its geometry: luminance, exposure and lookup tables (a tiny float target
//   blended with its own last value each frame never recovers from an undefined start). This kind alone makes Odyssey's first launch right. PEANUTS: a
//   layered target (more than one layer: a cube map or an array) counts as small up to 128x128 a layer. That is a capture, a sky light or reflection cube an
//   engine draws once, at a level load, and only reads from then on (128 is Unreal Engine 4's default size for both), so a skipped draw is never made again.
//   2D targets keep the 64 limit: a 128x128 2D target is a downsample or bloom step, drawn every frame. Crisis Core Reunion on an AYN Thor (Turnip), first
//   run: the station white for the whole session; its three draws into two 128x128x6 RGBA16F cubes at the level load were skipped, and the 1x1 brightness
//   the game derives from them (3332 instead of 0.00036) was never made again. A 128 limit for every target made it right, 107 did not, and those cubes
//   were the only skipped targets between the two;
//   a draw whose result is memory, not a picture: its shaders write storage buffers, images or raw memory, or the rasterizer is off (transform feedback, a
//   geometry shader's writes). Nothing redraws those next frame unless the game does.
// Everything else is skipped: that is where the long compiles are.
// Tried on 2026-11-15 and dropped: a direct draw of at most 6 vertices (full-screen passes), since a 2D game draws its whole scene that way (OCTOPATH TRAVELER 0 on
// the AYN Thor: 99 pipelines held on the frame by it, frames up to 564 ms); and a draw into a target the game does not draw into every frame (a cube map drawn
// once), which no game was found to need then and which held 36 more there (3.2 s of frame time for the two together); PEANUTS: the layered kind above is far narrower.
#ifndef VKA_PSOSYNC_H
#define VKA_PSOSYNC_H
#include <stdint.h>

enum { VKPS_SMALL = 1, VKPS_WRITES = 2,   // the reason vkps_why returns
       VKPS_SMALL_PX = 64,                // a small target's side at most
       VKPS_LAYERED_PX = 128 };           // a small layered target's side at most (more than one layer; PEANUTS)

// The draw as draw_body knows it where the pipeline is looked up. w, h: the pass's target in the game's own pixels (0: not known); layers: the pass's layers
// (0 or 1: one); writes: the shaders write memory, or the rasterizer is off
typedef struct { int writes; uint32_t w, h, layers; } vkps_draw;

// The reason this draw's pipeline is made on the frame path (VKPS_SMALL before VKPS_WRITES), 0 = none: the draw is skipped until the pipeline is made
static inline int vkps_why(const vkps_draw *d) {
    uint32_t px = d->layers > 1 ? VKPS_LAYERED_PX : VKPS_SMALL_PX;   // PEANUTS: a cube map or array keeps its capture
    if (d->w && d->h && d->w <= px && d->h <= px) return VKPS_SMALL;
    if (d->writes) return VKPS_WRITES;
    return 0;
}
static inline const char *vkps_name(int why) { return why == VKPS_SMALL ? "a small target" : why == VKPS_WRITES ? "it writes memory" : "none"; }
#endif
