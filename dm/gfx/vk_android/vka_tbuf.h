// PEANUTS: how a buffer texture bound to a texel buffer slot is read (vka_draw.c tbuf_view), as pure logic, free of Android and Vulkan so a unit test on Linux can check
// it (dm/host/tests/t_android_tbuf.c).
// A read-only buffer texture of up to 1 MiB is read through a copy of the CPU's bytes taken when its draw or dispatch is recorded (DM_VK_TBUF_COPY: the game may refill
// the buffer for its next frame before the GPU has drawn this one). That copy is wrong for a buffer the GPU itself writes earlier in the same work: the CPU sees those
// bytes only once the submission is done, so the copy holds the frame before's, or what the memory held last. Final Fantasy Resonance (AYN Thor, Adreno 740, Turnip):
// the particle system's instance counts are cleared and counted by compute dispatches through storage texel buffers, and the dispatch that turns them into indirect
// draw arguments (544e80ec57b3a10b) read them through such a copy: in a scene's first frame it held leftover memory, the draws got a huge instance count and the GPU
// timed out in 40-50 % of the loads (0 in 12 with DM_VK_TBUF_COPY=0).
// So a store through a storage texel buffer is kept as a GPU write of that range (vka_tex.c vka_tex_gw_tbuf: an opaque write, flagged), and while such a write is not
// done (its submission has not completed: vka_tbuf_gw_pending) a read-only texel buffer over the range is not read through the CPU's copy (DM_VK_TBUF_GW, default 1):
//   - viewed where it is, when the device can view it there (placed memory, the offset a multiple of minTexelBufferOffsetAlignment: 64 on Adreno);
//   - else, outside a draw pass (a dispatch), through a copy the GPU makes before the dispatch (vkCmdCopyBuffer into scratch memory, a full barrier before the first
//     copy and one after the last), which sees what the work recorded before it wrote;
//   - else (a draw: no copy can be recorded inside its pass) through the CPU's copy as before, noted.
// Only stores through storage texel buffers count, not every opaque write: a storage buffer bound without a size is marked over the device's whole storage range, which
// would take the CPU's copy away from buffers the CPU does write (the case the copy was made for).
#ifndef VKA_TBUF_H
#define VKA_TBUF_H
#include <stdint.h>
#include <stdlib.h>   // PEANUTS: strtod (vka_tbuf_ssbo_eff)

enum { VKTB_NONE, VKTB_PLACE, VKTB_CPU, VKTB_GPU };   // an empty texel buffer; a view of guest memory; a view of the CPU's copy; a view of a copy the GPU makes
#define VKTB_COPY_MAX (1u << 20)                      // the largest buffer read through a copy

// One GPU write as the tracker keeps it (vka_tex.c gw_t): [lo, hi), its submission's fence (0: the submission being recorded), tb: a storage texel buffer's stores.
// 1 = it is such a write, overlaps [a, b) and is not done (done: the newest completed fence)
static inline int vka_tbuf_gw_pending(uint64_t lo, uint64_t hi, uint64_t fence, int tb, uint64_t a, uint64_t b, uint64_t done) {
    return tb && (!fence || fence > done) && lo < b && hi > a;
}
// storage: a storage texel buffer binding; placed: all of it is in placed guest memory; aligned: its offset there is one the device can view; copy_sw: DM_VK_TBUF_COPY;
// gw: DM_VK_TBUF_GW is on and a storage texel buffer's write over it is pending; in_pass: a draw pass is open (or the writes are only being built for one)
static inline int vka_tbuf_path(int storage, uint64_t bytes, int placed, int aligned, int copy_sw, int gw, int in_pass) {
    int viewable = placed && aligned, copyable = !storage && bytes && bytes <= VKTB_COPY_MAX;
    if (copyable && gw) return viewable ? VKTB_PLACE : placed && !in_pass ? VKTB_GPU : VKTB_CPU;
    if (copyable && (!viewable || copy_sw)) return VKTB_CPU;
    return viewable ? VKTB_PLACE : VKTB_NONE;
}

// ---- A buffer texture read through an image binding (vka_draw.c tbuf_img_gpu): this fork's row image of the CPU's bytes, or upstream's copy the GPU makes
// (vka_tbuf.c). 1 = upstream's copy: only while a storage texel buffer's stores over the range are pending (gw, as above), and only when the copy can be made and
// read: switches (DM_VK_TBUF, DM_VK_TBUF_GW), shape (1D or 2D rows), cls (the format's numeric class is the binding's: the copy's view has no class twin), fits (an
// image the device takes), placed (all of it in placed guest memory), grid (its offset there a multiple of the texel size)
static inline int vka_tbuf_img_choice(int switches, int shape, int cls, int fits, int gw, int placed, int grid) {
    return switches && gw && shape && cls && fits && placed && grid;
}
// PEANUTS begin: stores through an ordinary storage buffer (SSBO) over a buffer texture read through an image binding (DM_VK_TBUF_GW_SSBO, live; unset, the game profile's tbuf_ssbo_copy decides: off by default).
// Fire Emblem Warriors: Three Hopes builds its clustered light lists with a compute dispatch (afe3b29178c63f21) that stores them through a storage buffer, and its pixel
// shaders read them as a 16384x32 R32 buffer texture: the row image of the CPU's bytes held the list of an earlier frame, so lights blinked on and off while the
// camera moved. With the switch on, a bounded storage buffer write (VKTB_SSBO_RANGE_MAX at most: a buffer bound without a size covers the whole storage range, the
// case the note above keeps out) is kept with value bit VKTB_GW_SSBO, and while it is pending a buffer texture over it of up to VKTB_SSBO_IMG_MAX bytes takes
// upstream's copy on the GPU (vka_tbuf_img_choice's gw). Texel buffer bindings (vka_tbuf_path) never look at that bit: only VKTB_GW_TB counts for them.
// The value bits of a GW_OPAQUE entry (vka_tex.c gw_t); each bit has its own count of entries holding it, so either question's "none kept" is one load
enum { VKTB_GW_TB = 1, VKTB_GW_SSBO = 2 };
#define VKTB_SSBO_RANGE_MAX (64ull << 20)   // the largest storage buffer range kept as an SSBO write
#define VKTB_SSBO_IMG_MAX (4ull << 20)      // the largest buffer texture an SSBO write sends to the GPU's copy
// The bits an entry gains when value is merged into one that held had (to count: each bit on its 0 -> 1 transition; dropping the entry takes off all it holds)
static inline uint32_t vka_tbuf_gw_new_bits(uint32_t had, uint32_t value) { return value & ~had & (VKTB_GW_TB | VKTB_GW_SSBO); }
// The switch's value: env is DM_VK_TBUF_GW_SSBO's text (NULL or empty = unset), prof the game profile's tbuf_ssbo_copy (DM_GAME_TBUF_SSBO_COPY). A set variable wins over the
// profile (a developer's override, 0 or 1); unset, the profile decides (default off). The same rule as VKA_ENV("DM_VK_TBUF_GW_SSBO", prof): vka_draw.c reads it live that
// way, this is the rule written out for the start-up log line (vka_dev.c) and the unit test
static inline int vka_tbuf_ssbo_eff(const char *env, int prof) { return env && *env ? strtod(env, NULL) != 0 : prof != 0; }
// A storage buffer binding the shader may store into, range bytes: kept as an SSBO write (1) or as a plain opaque write (0)
static inline int vka_tbuf_ssbo_mark(int ssbo_sw, uint64_t range) { return ssbo_sw && range && range <= VKTB_SSBO_RANGE_MAX; }
// vka_tbuf_img_choice's gw for an image binding: a storage texel buffer's stores pending over the range, or (switch on) an SSBO write pending over a texture of up to
// VKTB_SSBO_IMG_MAX bytes. cap_ok is returned for the log (0: the texture is too large for the SSBO rule)
static inline int vka_tbuf_img_gw(int tb_pending, int ssbo_sw, uint64_t bytes, int ssbo_pending, int *cap_ok) {
    int cap = bytes && bytes <= VKTB_SSBO_IMG_MAX;
    if (cap_ok) *cap_ok = cap;
    return tb_pending || (ssbo_sw && cap && ssbo_pending);
}
// PEANUTS end
// The answers of one draw or dispatch, by texture ID and shape. A draw asks two or three times for a binding (whether its images are to be made, making them,
// binding them), and a store the draw's own earlier binding records changes what the tracker says in between: the first answer holds for the draw. fresh: the
// copy was made, or found made this frame and not written over since, when the draw's images were made; only then is it bound (an older copy is another frame's
// bytes). A full memo answers the row image for what it cannot hold
enum { VKTB_MEMO_N = 64 };
typedef struct { int n; struct { int tid; uint8_t dim, gpu, fresh; } e[VKTB_MEMO_N]; } vktb_memo;
static inline void vktb_memo_reset(vktb_memo *m) { m->n = 0; }
static inline int vktb_memo_find(const vktb_memo *m, int tid, uint8_t dim) {
    for (int i = 0; i < m->n; i++) if (m->e[i].tid == tid && m->e[i].dim == dim) return i;
    return -1;
}
static inline int vktb_memo_put(vktb_memo *m, int tid, uint8_t dim, int gpu) {   // the entry's index, -1 when the memo is full
    if (m->n == VKTB_MEMO_N) return -1;
    m->e[m->n].tid = tid; m->e[m->n].dim = dim; m->e[m->n].gpu = (uint8_t)(gpu != 0); m->e[m->n].fresh = 0;
    return m->n++;
}
static inline void vktb_memo_fresh(vktb_memo *m, int tid, uint8_t dim) { int i = vktb_memo_find(m, tid, dim); if (i >= 0 && m->e[i].gpu) m->e[i].fresh = 1; }
static inline int vktb_memo_bind_gpu(const vktb_memo *m, int tid, uint8_t dim) { int i = vktb_memo_find(m, tid, dim); return i >= 0 && m->e[i].gpu && m->e[i].fresh; }
// PEANUTS: A copy of the CPU's bytes kept for the submission (vka_draw.c g_tbc) may be bound again only in the submission that made it: its scratch memory retires with that
// submission's fence. sub: a count of the submissions recorded (vka_draw_sub_begin), not vka_fence_last() + 1, which stays the same over the submissions whose fences
// the worker reserved ahead (Hogwarts Legacy: three a frame; a copy of the first was bound in the next two from memory handed out again: plants and ground black for single frames)
static inline int vka_tbuf_copy_live(uint64_t made_sub, uint64_t cur_sub) { return made_sub == cur_sub; }
// PEANUTS begin: a compute shader's storage texel buffer at an offset the device cannot view (vka_draw.c tbuf_view: offset % minTexelBufferOffsetAlignment != 0, 64 on Adreno) was
// refused: the shader's stores were dropped. Mario + Rabbids Kingdom Battle: the sky's compute shader 38c757aa0b2d5dea binds a 208-byte R32_SFLOAT buffer 32 bytes off the
// alignment (a sun colour: 17.4 13.4 8.3), and 6df283f716e87420 a 32-byte RGBA32F one; neither was ever written, the sun and the shadows it casts were lost. With the profile key
// tbuf_store_copy (DM_VK_TBUF_STORE_COPY overrides, live; default off) the dispatch gets the bytes through an aligned scratch buffer instead: copied in before the dispatch,
// viewed there, and copied back after it (only for a binding the shader may store into: DMSH_VK_BF_RW / ATOMIC). Pure logic below, as the unit test (t_android_tbuf.c) sees it
enum { VKTB_STORE_N = 8, VKTB_STORE_VIEWS = 64 };   // bindings one dispatch may keep; views one submission slot may keep
#define VKTB_STORE_MAX (64u << 10)                   // the largest buffer texture one binding copies
#define VKTB_STORE_SLOT (256u << 10)                 // the scratch buffer of one submission slot
// The switch's value: env_set / env_val are DM_VK_TBUF_STORE_COPY (a set variable wins over the profile, both ways: 0 or 1), prof is the game profile's tbuf_store_copy. The same rule as
// VKA_ENV("DM_VK_TBUF_STORE_COPY", prof) != 0, which vka_draw.c reads live; written out for the start-up line and the test
static inline int vka_tbuf_store_eff(int env_set, double env_val, int prof) { return env_set ? env_val != 0 : prof != 0; }
// 1 = this binding takes the scratch copy. Only where it would otherwise be refused: a storage texel buffer (storage) of a dispatch (in_disp), all of it in placed guest memory
// (placed), at an offset the device cannot view (!aligned), a multiple of its texel (texel) in size, of 1 .. VKTB_STORE_MAX bytes; and the switch is on (sw)
static inline int vka_tbuf_store_choice(int sw, int storage, int in_disp, int placed, int aligned, uint64_t bytes, uint32_t texel) {
    return sw && storage && in_disp && placed && !aligned && bytes && bytes <= VKTB_STORE_MAX && texel && bytes % texel == 0;
}
// How another binding of the same dispatch (bytes [oa, oa + on), view format ofmt or -1 when it is no texel buffer view, orw: it may store, ostore: a storage texel buffer, the
// kind that takes this same copy) relates to this one ([a, a + n), format fmt, rw: it may store): NONE: they do not overlap; SAME: the same bytes in the same format, which
// share one copy (only between two storage texel buffers); REFUSE: another format of the same bytes, or an overlap where either may store (the copy-back would race or
// overwrite the other's stores); NOTE: a read-only overlap, which reads the bytes as they were before the dispatch
enum { VKTB_AL_NONE, VKTB_AL_SAME, VKTB_AL_NOTE, VKTB_AL_REFUSE };
static inline int vka_tbuf_store_alias(uint64_t a, uint64_t n, int fmt, int rw, uint64_t oa, uint64_t on, int ofmt, int orw, int ostore) {
    if (!n || !on || oa >= a + n || a >= oa + on) return VKTB_AL_NONE;
    if (oa == a && on == n && ofmt == fmt && ostore) return VKTB_AL_SAME;
    if (oa == a && on == n && ofmt >= 0 && ofmt != fmt) return VKTB_AL_REFUSE;
    return rw || orw ? VKTB_AL_REFUSE : VKTB_AL_NOTE;
}
// The bindings one dispatch took a copy for: guest bytes, view format, whether the shader may store (copied back), the copy's offset in the slot's scratch buffer
typedef struct { int n; struct { uint64_t addr, bytes; int fmt; uint8_t rw; uint32_t soff; } e[VKTB_STORE_N]; } vktb_store;
static inline void vktb_store_reset(vktb_store *s) { s->n = 0; }
static inline int vktb_store_find(const vktb_store *s, uint64_t addr, uint64_t bytes, int fmt) {   // the entry of exactly these bytes and format, else -1
    for (int i = 0; i < s->n; i++) if (s->e[i].addr == addr && s->e[i].bytes == bytes && s->e[i].fmt == fmt) return i;
    return -1;
}
// A new entry's index; -1: the registry is full; -2: it overlaps an entry in a way vka_tbuf_store_alias refuses (nothing is added in either case)
static inline int vktb_store_add(vktb_store *s, uint64_t addr, uint64_t bytes, int fmt, int rw, uint32_t soff) {
    for (int i = 0; i < s->n; i++)
        if (vka_tbuf_store_alias(addr, bytes, fmt, rw, s->e[i].addr, s->e[i].bytes, s->e[i].fmt, s->e[i].rw, 1) >= VKTB_AL_REFUSE) return -2;
    if (s->n == VKTB_STORE_N) return -1;
    s->e[s->n].addr = addr; s->e[s->n].bytes = bytes; s->e[s->n].fmt = fmt; s->e[s->n].rw = (uint8_t)(rw != 0); s->e[s->n].soff = soff;
    return s->n++;
}
// The offset of the next copy in a scratch buffer of cap bytes of which used are taken: a multiple of al (and 16), 1 = it fits (*off), 0 = it does not (nothing changes)
static inline int vka_tbuf_store_off(uint32_t used, uint32_t al, uint64_t bytes, uint32_t cap, uint32_t *off) {
    if (al < 16) al = 16;
    uint64_t o = ((uint64_t)used + al - 1) / al * al;
    if (!bytes || o + bytes > cap) return 0;
    *off = (uint32_t)o; return 1;
}
// PEANUTS end
#endif
