// The texture cache's size and eviction policy (vka_tex.c vka_tex_prune_locked), free of Vulkan so the Mac's unit test (dm/host/tests/t_android_texlimit.c) can check it.
// The limit is the most image memory the device can hold without pushing the game or the system out of RAM, measured, not guessed: the images' own bytes plus what the
// process could still allocate (the smaller of Android's MemAvailable less a reserve and the image heap's budget, VK_EXT_memory_budget, less what else uses that heap), never
// under a floor, and no more than what is not the working set while the system stalls on memory (vktl_pressure_limit). DM_VK_IMG_LIMIT_MB, when set, replaces it, working
// set and guard too. Eviction is least recently used first and never takes an image used in the current or the previous frame: when
// everything over the limit is in use the cache stays over it (a working set larger than the limit would otherwise be uploaded again every frame: the thrash
// docs/reference/memory.md "Texture cache thrash" describes)
#ifndef VKA_TEXLIMIT_H
#define VKA_TEXLIMIT_H
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define VKTL_MIB (1ull << 20)
enum { VKTL_KEEP_FRAMES = 2,      // an image used in the current or the previous frame stays
       VKTL_WS_FRAMES = 8,        // an image used in this frame or the VKTL_WS_FRAMES before it is the scene's working set: the pressure policy never takes it back
       VKTL_GPU_FRAMES = 120 };   // an image whose content is only the GPU's (rendered, not uploaded): kept whatever its age, unless the system is stalling hard on memory; then it goes after this many unused frames and after every image that can be uploaded again
#define VKTL_FLOOR (512 * VKTL_MIB)
#define VKTL_12GB (12 * 1024 * VKTL_MIB)   // the 12 GB class: the reserve rules below treat MemTotal at or under this differently

typedef struct {
    uint64_t mem_total, mem_avail;     // /proc/meminfo MemTotal and MemAvailable (0 = not known)
    uint64_t heap_size;                // the image memory type's heap (0 = not known)
    uint64_t heap_budget, heap_usage;  // VK_EXT_memory_budget for that heap (budget 0 = not available)
    uint64_t img_bytes;                // the cache's own image bytes now (part of mem_total - mem_avail and of heap_usage)
    uint64_t ws_bytes;                 // the bytes of the images of the working set (the scene's, which evicting only uploads again)
    double psi_some10;                 // /proc/pressure/memory "some avg10": the share of the last 10 s some task stalled on memory, in percent (< 0 = not known)
} vktl_in;

// The memory-pressure policy (Linux PSI): Android's lmkd kills the foreground game by how long tasks stall on memory, not by how much is free, so the cache follows it too.
//   DM_VK_IMG_PSI = 0  no pressure term (the cache answers to RAM and the heap budget only, with the reserve the build before this rule used)
//   DM_VK_IMG_PSI = 1  the policy below (the default)
// From hold percent of the last 10 s stalled the reserve grows on the 12 GB class (the RAM rule's own cap on growth) and from shrink percent the cache gives back an eighth of
// what is not the working set at each limit update, never under the working set or the floor. Both marks are hysteresis (ease is the way back out), so a spike does not flap the limit:
//   hold 10 % / ease 3 %   a phone that is merely busy (reclaiming page cache, writing swapped pages) sits below the enter mark; the exit mark, plus some RAM actually free
//                          again, is what lets a cache that gave RAM back take it again instead of entering pressure a second time at once
//   shrink 25 %            an eighth back is a change of a few hundred MiB at these sizes; a cache that is genuinely in the way of the system is worth that, and
//                          below 25 % the system is reclaiming what it can and taking the cache's working set away costs more stall than it saves
// The measured PSI of the phone sets these (the `image cache pressure` diagnostic line); they are env-tunable (DM_VK_IMG_PSI_HOLD / _EASE / _SHRINK, percent)
#define VKTL_PSI_HOLD 10.0
#define VKTL_PSI_EASE 3.0
#define VKTL_PSI_SHRINK 25.0
enum { VKTL_PSI_STEP = 8 };          // give back at most an eighth of what is not the working set in one update

// The switches: DM_VK_IMG_RESERVE_MB picks the RAM reserve's rule, DM_VK_IMG_PSI the pressure policy, and the three marks the policy's thresholds
typedef struct { int mb; int psi; double hold, ease, shrink; } vktl_cfg;
static inline vktl_cfg vktl_cfg_default(void) { vktl_cfg c = {0, 1, VKTL_PSI_HOLD, VKTL_PSI_EASE, VKTL_PSI_SHRINK}; return c; }
// The values an env switch gave, made safe: a mark that is not a finite positive percent, or an ease mark at or above the hold mark, would press and ease on the same
// reading and flap the cache at every update, so the default fills in for it (the thrashed cache is worse than the mark the player asked for). DM_VK_IMG_PSI has two
// policies, 0 and 1, and anything else is the default's
static inline int vktl_mark_ok(double v) { return v > 0.0 && v < 1e9; }   // (NaN and infinity fail both comparisons)
static inline vktl_cfg vktl_cfg_check(vktl_cfg c) {
    if (c.psi != 0 && c.psi != 1) c.psi = 1;
    if (!vktl_mark_ok(c.hold)) c.hold = VKTL_PSI_HOLD;
    if (!vktl_mark_ok(c.ease)) c.ease = VKTL_PSI_EASE;
    if (!vktl_mark_ok(c.shrink)) c.shrink = VKTL_PSI_SHRINK;
    if (c.ease >= c.hold) { c.hold = VKTL_PSI_HOLD; c.ease = VKTL_PSI_EASE; }
    return c;
}

static inline uint64_t vktl_sub(uint64_t a, uint64_t b) { return a > b ? a - b : 0; }
// What RAM keeps free for the game's own growth, the rest of the process and the system.
//   DM_VK_IMG_RESERVE_MB > 0   that many MiB
//   DM_VK_IMG_RESERVE_MB = 0   adaptive (the default): a 32nd, at least 512 MiB, and an eighth on a device of 12 GB or less while the system stalls on memory
//                              (pressed, vktl_psi_update). The eighth is the protection the 12 GB class needs from lmkd; while the system is not stalling it costs the
//                              cache nothing, and a cache made to give back an eighth of a 12 GB phone's RAM to a reserve nothing was asking for evicts and re-uploads the
//                              scene's textures instead (the regression this rule fixes)
// The small reserve is right on purpose: Android keeps MemAvailable low while it has cached apps and compressed swap to give (Mario Kart World runs at 0.7-1 GB of
// it on a 16 GB phone), so a larger reserve would put a game that fits over the limit. With DM_VK_IMG_PSI=0 nothing tells the reserve when the system stalls, so the
// eighth is permanent on that class, as it was before this rule: turning the pressure term off gives the build before it, not a third shape
static inline uint64_t vktl_reserve(uint64_t mem_total, const vktl_cfg *c, int pressed) {
    uint64_t small = mem_total / 32; if (small < 512 * VKTL_MIB) small = 512 * VKTL_MIB;
    if (c->mb > 0) return (uint64_t)c->mb << 20;
    // With the pressure term off, the rule that was there before this one, verbatim: the eighth on the class with no floor on it (a 3 GiB device kept 384 MiB)
    if (!c->psi) return mem_total && mem_total <= VKTL_12GB ? mem_total / 8 : small;
    // What keeps lmkd away where the game itself needs nearly all the RAM the system leaves (Ocarina of Time on an Odin 2 Portal, 11 GiB: the images grew to 3.2 GiB
    // without one prune while MemAvailable stayed above 512 MiB, and lmkd killed the game)
    if (pressed && mem_total && mem_total <= VKTL_12GB) { uint64_t big = mem_total / 8; if (big > small) return big; }
    return small;
}

typedef struct { int pressed; } vktl_psi;   // carried between limit updates: the hysteresis' state
// Enter pressure at hold; leave it under ease, and only with some RAM actually free again (the reserve in force plus 256 MiB), so a cache that gave RAM back is not eased
// into a system that has none. The band between the two marks is what damps the cycle: some avg10 is a 10 s average, so leaving it takes tens of seconds and the cache's own
// regrowth has that long to prove it is harmless. psi < 0 (not known) holds the state it had
static inline int vktl_psi_update(vktl_psi *s, const vktl_cfg *c, double psi, uint64_t mem_total, uint64_t mem_avail) {
    if (!c->psi) { s->pressed = 0; return 0; }
    if (psi < 0) return s->pressed;
    if (!s->pressed) { if (psi >= c->hold) s->pressed = 1; return s->pressed; }
    if (psi < c->ease && mem_avail >= vktl_reserve(mem_total, c, 0) + 256 * VKTL_MIB) s->pressed = 0;
    return s->pressed;
}
// The pressure term: past the shrink mark the cache gives back an eighth of what is not the working set at each update, so the images the scene is using (and would upload
// again at once) always stay. Growth needs no term of its own: entering pressure is what raises the reserve, which is the RAM rule's own cap on growth (vktl_reserve).
// Never under the floor
static inline uint64_t vktl_pressure_limit(uint64_t lim, const vktl_in *in, const vktl_cfg *c, int pressed) {
    if (!pressed || in->psi_some10 < c->shrink || in->img_bytes <= in->ws_bytes) return lim;
    uint64_t l = in->img_bytes - (in->img_bytes - in->ws_bytes) / VKTL_PSI_STEP;
    if (l < lim) lim = l;
    return lim < VKTL_FLOOR ? VKTL_FLOOR : lim;
}
// The limit in bytes; floor when nothing is known
static inline uint64_t vktl_limit(const vktl_in *in, const vktl_cfg *c, int pressed) {
    uint64_t lim = UINT64_MAX;
    if (in->mem_total && in->mem_avail) { uint64_t l = in->img_bytes + vktl_sub(in->mem_avail, vktl_reserve(in->mem_total, c, pressed)); if (l < lim) lim = l; }
    if (in->heap_budget) {   // the budget is the process's whole share of the heap: what else uses it (guest RAM, buffers, other images) is not the cache's
        uint64_t other = vktl_sub(in->heap_usage, in->img_bytes);
        uint64_t l = vktl_sub(in->heap_budget - in->heap_budget / 16, other); if (l < lim) lim = l;
    } else if (in->heap_size) { uint64_t l = in->heap_size / 2; if (l < lim) lim = l; }   // (no budget: half the heap, which on a unified-memory phone is the RAM)
    if (lim == UINT64_MAX || lim < VKTL_FLOOR) lim = VKTL_FLOOR;
    if (c->psi) lim = vktl_pressure_limit(lim, in, c, pressed);
    return lim;
}
// Prune down to this once over the limit. Under pressure no margin: the 16th exists so one new image does not start another prune, but while the cache holds at what
// it has, a 16th of it (a 2 GiB cache: 128 MiB) pruned at every new image is the thrash the hold was meant to avoid. Never under the working set.
// Both belong to the shipped policy (psi == 1) alone: with the pressure term off, the cache prunes with the margin the build before this rule had
static inline uint64_t vktl_target(uint64_t limit, uint64_t ws_bytes, const vktl_cfg *c, int pressed) {
    uint64_t t = c->psi == 1 && pressed ? limit : limit - limit / 16;
    return ws_bytes > t ? ws_bytes : t;
}
// The bytes the shipped policy will not prune under: the floor, or the working set when that is larger (a scene over the floor keeps its own)
static inline uint64_t vktl_keep_bytes(uint64_t ws_bytes) { return ws_bytes > VKTL_FLOOR ? ws_bytes : VKTL_FLOOR; }
// Whether the working set is protected at all: by the shipped policy, and not under DM_VK_IMG_LIMIT_MB, which replaces the rule and this guard with the pinned limit
static inline int vktl_ws_active(const vktl_cfg *c, int pinned) { return c->psi == 1 && !pinned; }
// Whether this image is the working set (`cur_frame - last_frame` within VKTL_WS_FRAMES: evicting one of those makes the next frames upload it again, which is the churn
// the policy exists to prevent)
static inline int vktl_keep_ws(uint64_t cur_frame, uint64_t last_frame) {
    return last_frame && cur_frame >= last_frame && cur_frame - last_frame <= (uint64_t)VKTL_WS_FRAMES;
}
// Whether the prune may not take this image out of the working set's exclusion and the guard (the two halves of the same rule, for the prune loop and for the test's)
static inline int vktl_skip_ws(const vktl_cfg *c, int pinned, uint64_t cur_frame, uint64_t last_frame) {
    return vktl_ws_active(c, pinned) && vktl_keep_ws(cur_frame, last_frame);
}
// Whether taking evict_bytes out would leave less than what the policy keeps (an eviction is whole images, so one can cross the working set or the floor)
static inline int vktl_guard_ws(const vktl_cfg *c, int pinned, uint64_t total_bytes, uint64_t evict_bytes, uint64_t ws_bytes) {
    return vktl_ws_active(c, pinned) && vktl_sub(total_bytes, evict_bytes) < vktl_keep_bytes(ws_bytes);
}
// A prune spread over frames (DM_VK_PRUNE_MS: a budget of replay-thread time a frame gives to evictions). A miss prunes when the cache is over its limit, or when the
// last prune ran out of its frame's budget (more) before the cache was down to its target (vktl_target's value, given by the caller)
static inline int vktl_prune_due(uint64_t total, uint64_t limit, uint64_t target, int more) { return limit && (total > limit || (more && total > target)); }
// Whether the eviction loop stops before candidate k: the frame's budget (0: none) spent, counting this prune's time; the clock is read every 8 evictions, and the first
// is always made so that a prune moves on even with a budget smaller than one eviction
static inline int vktl_budget_spent(uint64_t budget_ns, int k, uint64_t spent_ns) { return budget_ns && k && !(k & 7) && spent_ns >= budget_ns; }

// Whether an image may be evicted now: not held from outside the recording thread, not a playing video's, its last submission done, unused for VKTL_KEEP_FRAMES frames.
// An image whose content is the GPU's (gpu_owned: rendered, copied or computed into) is never a candidate: its storage holds what the CPU last wrote, not what the GPU made,
// so a texture made again for it starts empty and stays wrong until the game renders it again (a shadow or a menu background drawn once; docs/reference/rendering.md
// "A GPU-written image evicted and made again empty"). Only with allow_gpu (the policy is pressed past its shrink mark, where lmkd would end the game) and after
// VKTL_GPU_FRAMES unused frames. last_frame 0 = never used (kept: it was just made)
static inline int vktl_evictable(uint64_t cur_frame, uint64_t last_frame, int holds, int gpu_owned, int video_playing, int fence_pending, int allow_gpu) {
    if (holds || video_playing || fence_pending || !last_frame || cur_frame <= last_frame) return 0;
    if (gpu_owned) return allow_gpu && cur_frame - last_frame >= (uint64_t)VKTL_GPU_FRAMES;
    return cur_frame - last_frame >= (uint64_t)VKTL_KEEP_FRAMES;
}
// Eviction order: images that can be uploaded again before GPU-only ones, then least recently used first (qsort-style: < 0 = a goes first)
static inline int vktl_order(int gpu_a, uint64_t last_a, int gpu_b, uint64_t last_b) {
    if (gpu_a != gpu_b) return gpu_a - gpu_b;
    return last_a < last_b ? -1 : last_a > last_b;
}

// /proc/pressure/memory's text into a percentage: the avg10 of the line that starts with key, or -1 when the buffer, the line or a figure the kernel does not give is not
// there. Free of stdio so the host test reads the real file's shape (and a kernel that gives only one line) as well as vka_tex.c does
static inline double vktl_psi_field(const char *b, const char *key) {
    if (!b) return -1;
    size_t k = 0; while (key[k]) k++;
    for (const char *s = b; (s = strstr(s, key)); s += k) {
        if (s != b && s[-1] != '\n') continue;   // ("notfull avg10=" is not "full avg10=")
        if (s[k] < '0' || s[k] > '9') continue;  // (the field's number starts here: strtod would skip a newline and borrow the next line's)
        char *end; double v = strtod(s + k, &end);
        if (end == s + k) continue;              // (no number at all: not known, not 0)
        if (*end && *end != ' ' && *end != '\n' && *end != '\r') continue;
        if (!(v >= 0.0) || v > 100.0) continue;  // (a NaN, an infinity or a figure no percentage reaches)
        return v;
    }
    return -1;
}
#define VKTL_PSI_SOME "some avg10="
#define VKTL_PSI_FULL "full avg10="
#endif
