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
#endif
