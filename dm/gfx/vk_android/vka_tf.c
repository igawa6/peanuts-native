// PEANUTS: NVN transform feedback on the Vulkan replay (Pokemon Legends: Arceus skins its people and Pokemon this way: a vertex shader writes the skinned vertices
// into buffers under rasterizer discard, and the character draws read those buffers as vertex streams; without the capture they drew nothing).
//
// How it reaches the replay: nvnCommandBufferBindTransformFeedbackBuffer(s) record OP_TF_BIND (the four buffers, kept in the queue's vka_dstate), Begin / End / Pause /
// Resume record OP_TF_STATE (with the game's 32-byte control buffer), and a draw while feedback is on captures. What is captured is not in the commands: the vertex
// shader's control section declares it, and that section is only valid inside nvnProgramSetShaders, so its layout is noted there by shader key (nvn_tf_ctl_note).
//
// The capture is VK_EXT_transform_feedback's, as in nx2android's Vulkan backend. The shader library's modules carry no capture, so a capturing draw draws with a
// variant of its vertex shader (a shader of another key, like the instanced variants: neither the pipeline table nor the prewarm list takes it for the library's) whose
// modules get the Xfb decorations when they are made (vka_tf_spv.c vka_spv_xfb through vka_shader.c prep_words). No library is built again and no pipeline key changes.
// The draw captures into staging memory of the replay's own, and when the pass ends (vka_rp_end; an End or Pause ends it) the bytes are copied into the game's
// buffers: the game's memory pools need no transform feedback usage, so their buffers are made as before. The bytes written are counted here (vertices x stride, as
// nvn_mtl.m's tf_prepare does on the GPU): only direct draws of point, line or triangle lists from the vertex stage are captured. At End / Pause the counts go into the
// control buffer in nvn_feedback.h's form, which nvnCommandBufferDrawTransformFeedback's indirect draw reads (hle_nvn.c).
// Whatever cannot be captured (no extension, DM_VK_XFB=0, a layout or module the rewrite does not take, another kind of draw) logs its reason once and the draw is made
// as before this file: skipped under rasterizer discard, else drawn without capture
#if defined(__ANDROID__)
#include "vka_tf.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VKA_TF_KEY_XOR 0x5846425f43415054ull   // a capture variant's key: its base shader's key XOR this
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
// Capture layouts by library key, noted at nvnProgramSetShaders (the game's threads); st: 1 read, -1 a form not known
typedef struct { uint64_t key; int st; vka_tf_layout L; } lay_ent;
static lay_ent *g_lay; static int g_nlay, g_clay;
// The variants made, by base shader (v NULL: refused, logged); the replay thread makes them, a module-making thread reads L by v's key (vka_tf_prep)
enum { NVAR = 256 };
static struct { vka_shader *s, *v; vka_tf_layout L; } g_var[NVAR]; static _Atomic int g_nvar;
static uint32_t g_max_loc, g_max_stride, g_max_bufs; static VkDeviceSize g_max_size;

void nvn_tf_ctl_note(const uint8_t key[8], const void *control) {   // hle_nvn.c, for each stage of a program whose key was made (the section is readable for 0x760 bytes there)
    lay_ent e; e.st = vka_tf_layout_read(control, 0x760, &e.L);
    if (!e.st) return;
    e.key = vka_shlib_key(key);
    pthread_mutex_lock(&g_mu);
    int i = 0; while (i < g_nlay && g_lay[i].key != e.key) i++;
    if (i == g_nlay && g_nlay < 16384) {
        if (g_nlay == g_clay) { int c = g_clay ? 2 * g_clay : 64; lay_ent *n = realloc(g_lay, (size_t)c * sizeof *n); if (n) { g_lay = n; g_clay = c; } }
        if (g_nlay < g_clay) g_lay[g_nlay++] = e;
    }
    pthread_mutex_unlock(&g_mu);
}
static void caps(void) {
    if (g_max_loc) return;
    VkPhysicalDeviceTransformFeedbackPropertiesEXT tp = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_PROPERTIES_EXT};
    VkPhysicalDeviceProperties2 p2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &tp};
    vk.vkGetPhysicalDeviceProperties2(V.pd, &p2);
    g_max_bufs = tp.maxTransformFeedbackBuffers; g_max_stride = tp.maxTransformFeedbackBufferDataStride; g_max_size = tp.maxTransformFeedbackBufferSize;
    uint32_t l = p2.properties.limits.maxVertexOutputComponents / 4; g_max_loc = l < 1 ? 1 : l > 32 ? 32 : l;
    VLOG("transform feedback: VK_EXT_transform_feedback, %u buffers of at most %llu bytes, stride at most %u, %u vertex output locations", g_max_bufs, (unsigned long long)g_max_size, g_max_stride, g_max_loc);
}
// The capture variant of vertex shader s and its layout; NULL = it cannot capture (logged when first asked)
static vka_shader *variant(vka_shader *s, const vka_tf_layout **L) {
    int nv = atomic_load(&g_nvar);
    for (int i = 0; i < nv; i++) if (g_var[i].s == s) { *L = &g_var[i].L; return g_var[i].v; }
    if (nv == NVAR) { VRED_ONCE("transform feedback: more than %d capturing vertex shaders: the rest are not captured", NVAR); return NULL; }
    caps();
    vka_shader *v = NULL; vka_tf_layout lay = {0}; int st = 0; char err[256] = "";   // (lay is kept with a refused shader too: never left unset)
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < g_nlay; i++) if (g_lay[i].key == s->key) { st = g_lay[i].st; lay = g_lay[i].L; break; }
    pthread_mutex_unlock(&g_mu);
    if (!st) snprintf(err, sizeof err, "no capture layout was noted for it (its control section declares none, or its program came from a capture file)");
    else if (st < 0) snprintf(err, sizeof err, "its control section's capture layout has a form not known");
    else if (vka_shader_is_variant(s) || (s->e->flags & DMSH_VK_F_SUBGROUP)) snprintf(err, sizeof err, "it is a variant already, or has subgroup operations");
    else {
        for (uint32_t b = 0; b < 4 && !err[0]; b++) if (lay.stride[b] && (b >= g_max_bufs || lay.stride[b] > g_max_stride))
            snprintf(err, sizeof err, "buffer %u of stride %u is past the device's %u buffers of stride %u", b, lay.stride[b], g_max_bufs, g_max_stride);
        size_t nb = 0, on = 0; uint32_t *w = err[0] ? NULL : vka_shader_memu_words(s, &nb, err, sizeof err);   // (the base module's words, as the library has them)
        uint32_t *o = w ? vka_spv_xfb(w, nb / 4, &lay, g_max_loc, &on, err, sizeof err) : NULL;   // tried here, so a draw only takes a variant whose modules will capture
        int ok = o != NULL; free(w); free(o);
        if (ok && (v = calloc(1, sizeof *v))) {
            *v = *s; memset(v->mods, 0, sizeof v->mods); memset(v->frz, 0, sizeof v->frz); v->vin = v->fout = NULL; v->img = NULL; v->tex = NULL; v->gslink = NULL;
            v->gs_claim = VK_NULL_HANDLE; v->memu_d = NULL; v->key = s->key ^ VKA_TF_KEY_XOR;
        } else if (ok) snprintf(err, sizeof err, "no memory");
    }
    if (v) VLOG("transform feedback: vertex shader %016llx captures %u + %u + %u + %u outputs into buffers of stride %u / %u / %u / %u", (unsigned long long)s->key,
                lay.n[0], lay.n[1], lay.n[2], lay.n[3], lay.stride[0], lay.stride[1], lay.stride[2], lay.stride[3]);
    else VRED("transform feedback: vertex shader %016llx is not captured: %s", (unsigned long long)s->key, err);
    pthread_mutex_lock(&g_mu);
    g_var[nv].s = s; g_var[nv].v = v; g_var[nv].L = lay; atomic_store(&g_nvar, nv + 1);
    pthread_mutex_unlock(&g_mu);
    *L = &g_var[nv].L;
    return v;
}
void vka_tf_prep(uint64_t key, uint32_t **w, size_t *nb) {
    int nv = atomic_load(&g_nvar), i = 0;
    if (!nv) return;   // (no capture variant was made: every game without transform feedback)
    while (i < nv && !(g_var[i].v && g_var[i].v->key == key)) i++;
    if (i == nv) return;
    size_t on = 0; char err[256]; uint32_t *o = vka_spv_xfb(*w, *nb / 4, &g_var[i].L, g_max_loc, &on, err, sizeof err);
    if (!o) { VRED("transform feedback: module of vertex shader %016llx not made to capture: %s", (unsigned long long)(key ^ VKA_TF_KEY_XOR), err); return; }   // (it was, when the variant was made)
    free(*w); *w = o; *nb = on * 4;
}

// ---------- Staging memory: what a pass's draws capture, until the pass ends ----------
enum { NCHUNK = 16, NPEND = 64 };
static struct { VkBuffer buf; VkDeviceMemory mem; VkDeviceSize size, used; } g_ch[NCHUNK]; static int g_nch;
static struct { int ch; VkDeviceSize off, len; uint64_t dst; } g_pend[NPEND]; static int g_npend;
static int stage_fits(VkDeviceSize n) { for (int i = 0; i < g_nch; i++) if (g_ch[i].size - g_ch[i].used >= n) return i; return -1; }
static int stage_get(VkDeviceSize n) {   // the chunk with n bytes free at its `used`, made when none has them; -1 = none
    int i = stage_fits(n);
    if (i >= 0 || g_nch == NCHUNK) return i;
    VkDeviceSize size = n > (4u << 20) ? (n + 0xffff) & ~(VkDeviceSize)0xffff : 4u << 20;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = size, .usage = VK_BUFFER_USAGE_TRANSFORM_FEEDBACK_BUFFER_BIT_EXT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    VkBuffer buf = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE;
    if (vk.vkCreateBuffer(V.dev, &bi, NULL, &buf)) return -1;
    VkMemoryRequirements mr; vk.vkGetBufferMemoryRequirements(V.dev, buf, &mr);
    uint32_t t = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0); if (t == UINT32_MAX) t = vka_mem_type(mr.memoryTypeBits, 0, 0);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = t};
    if (t == UINT32_MAX || vk.vkAllocateMemory(V.dev, &ai, NULL, &mem) || vk.vkBindBufferMemory(V.dev, buf, mem, 0)) {
        if (mem) vk.vkFreeMemory(V.dev, mem, NULL);
        vk.vkDestroyBuffer(V.dev, buf, NULL); return -1; }
    vka_mem_grew(mr.size);
    g_ch[g_nch].buf = buf; g_ch[g_nch].mem = mem; g_ch[g_nch].size = size; g_ch[g_nch].used = 0;
    VLOG("transform feedback: %llu KiB of staging memory (chunk %d)", (unsigned long long)(size >> 10), g_nch + 1);
    return g_nch++;
}

// ---------- The capture state (the replay thread) ----------
static uint64_t g_off[4]; static uint32_t g_verts; static int g_seq;   // bytes written into each buffer and vertices captured since Begin; g_seq: something was since the last End / Pause
static struct { uint64_t ctl, off[4]; uint32_t verts; } g_saved[16]; static unsigned g_nsaved;   // what an End / Pause left, by control buffer, for a Resume
static struct { int on, ch; uint32_t verts, mask; VkDeviceSize off[4], len[4], end; uint64_t dst[4], key; } A;   // the capture of the draw being made
static uint64_t g_ncap, g_ncopied;

vka_shader *vka_tf_capture(vka_shader *const sh[VKA_NSTAGE], const vka_dstate *d, const p_draw *dw, const p_draw_idx *di) {
    A.on = 0;
    if (!V.xfb) { VRED_ONCE("transform feedback: VK_EXT_transform_feedback is not on (the device lacks it, or DM_VK_XFB=0): not captured"); return NULL; }
    if (!sh[0] || sh[2] || sh[3] || sh[4]) { VRED_ONCE("transform feedback from a geometry or tessellation stage: not captured"); return NULL; }
    int prim = dw ? dw->prim : di->prim, pv = prim == NVN_DRAW_PRIMITIVE_POINTS ? 1 : prim == NVN_DRAW_PRIMITIVE_LINES ? 2 : prim == NVN_DRAW_PRIMITIVE_TRIANGLES ? 3 : 0;
    if (!pv || (dw ? dw->ind || dw->mdi_cnt : di->ind || di->mdi_cnt)) {
        VRED_ONCE("transform feedback with an indirect draw, or of a primitive type other than point, line or triangle lists (type %d): not captured", prim); return NULL; }
    int32_t cnt = dw ? dw->count : di->count, ins = dw ? dw->instances : di->instances;
    if (cnt < pv) return NULL;
    uint64_t n = (uint64_t)(cnt - cnt % pv) * (uint64_t)(ins > 1 ? ins : 1);   // vertices a capture of lists writes
    const vka_tf_layout *L; vka_shader *v = variant(sh[0], &L);
    if (!v) return NULL;
    for (int b = 0; b < 4; b++) if (L->stride[b]) {
        if (!d->tf[b].addr || !d->tf[b].size) { VRED_ONCE("transform feedback: buffer %d of vertex shader %016llx is not bound: not captured", b, (unsigned long long)sh[0]->key); return NULL; }
        uint64_t room = d->tf[b].size > g_off[b] ? (d->tf[b].size - g_off[b]) / L->stride[b] : 0;
        if (room < n) n = room;
    }
    n -= n % (unsigned)pv;   // (whole primitives: a primitive that does not fit is not written)
    if (!n || n > UINT32_MAX) { VRED_ONCE("transform feedback: a capture whose buffers are full (or of more than 2^32 vertices): nothing is written"); return NULL; }
    VkDeviceSize total = 0, longest = 0;
    for (int b = 0; b < 4; b++) { A.len[b] = n * L->stride[b]; A.off[b] = total; total += (A.len[b] + 15) & ~(VkDeviceSize)15; if (A.len[b] > longest) longest = A.len[b]; }
    if (longest > g_max_size) { VRED_ONCE("transform feedback: a capture of %llu bytes into one buffer, the device takes %llu: not captured", (unsigned long long)longest, (unsigned long long)g_max_size); return NULL; }
    // Staging memory is free again once the pass that captured into it has ended (vka_tf_flush): end it when this capture does not fit behind the earlier ones
    if (g_npend && (stage_fits(total) < 0 || g_npend + 4 > NPEND)) vka_rp_end();
    A.ch = stage_get(total);
    if (A.ch < 0) { VRED_ONCE("transform feedback: no staging memory for a capture of %llu bytes: not captured", (unsigned long long)total); return NULL; }
    A.mask = 0;
    for (int b = 0; b < 4; b++) if (L->stride[b]) { A.mask |= 1u << b; A.off[b] += g_ch[A.ch].used; A.dst[b] = d->tf[b].addr + g_off[b]; }
    A.end = g_ch[A.ch].used + total; A.verts = (uint32_t)n; A.key = sh[0]->key; A.on = 1;
    return v;
}
typedef struct { VkCommandBuffer cb; VkBuffer buf; uint32_t mask; VkDeviceSize off[4], len[4]; } begin_arg;
static void begin_run(const void *a) {   // (on the encoder thread when it is on: in order with the calls before)
    begin_arg g; memcpy(&g, a, sizeof g);
    for (uint32_t b = 0; b < 4; b++) if (g.mask >> b & 1) vk.vkCmdBindTransformFeedbackBuffersEXT(g.cb, b, 1, &g.buf, &g.off[b], &g.len[b]);
    vk.vkCmdBeginTransformFeedbackEXT(g.cb, 0, 0, NULL, NULL);
}
static void end_run(const void *a) { VkCommandBuffer cb; memcpy(&cb, a, sizeof cb); vk.vkCmdEndTransformFeedbackEXT(cb, 0, 0, NULL, NULL); }
void vka_tf_begin(VkCommandBuffer cb) {
    if (!A.on) return;
    begin_arg g = {cb, g_ch[A.ch].buf, A.mask}; memcpy(g.off, A.off, sizeof g.off); memcpy(g.len, A.len, sizeof g.len);
    if (!vka_enc_call(begin_run, &g, sizeof g)) begin_run(&g);
    A.on = 2;
}
void vka_tf_end(VkCommandBuffer cb) {
    if (A.on != 2) return;
    if (!vka_enc_call(end_run, &cb, sizeof cb)) end_run(&cb);
    if (g_ch[A.ch].used < A.end) g_ch[A.ch].used = A.end;
    for (int b = 0; b < 4; b++) if (A.mask >> b & 1) {
        if (g_npend < NPEND) { g_pend[g_npend].ch = A.ch; g_pend[g_npend].off = A.off[b]; g_pend[g_npend].len = A.len[b]; g_pend[g_npend++].dst = A.dst[b]; }
        g_off[b] += A.len[b];
    }
    g_verts += A.verts; g_seq = 1; A.on = 0;
    if (++g_ncap <= 4 || g_ncap % 16384 == 0)
        VLOG("transform feedback: capture %llu: %u vertices of vertex shader %016llx (%llu ranges copied into the game's buffers so far)", (unsigned long long)g_ncap, A.verts, (unsigned long long)A.key, (unsigned long long)g_ncopied);
}
void vka_tf_flush(VkCommandBuffer cb) {
    if (!g_npend) return;
    // PEANUTS: order transform feedback writes before transfer reads (pass-end barrier no longer drains ALL_COMMANDS)
    vka_barrier_stages(cb, VK_PIPELINE_STAGE_TRANSFORM_FEEDBACK_BIT_EXT, VK_ACCESS_TRANSFORM_FEEDBACK_WRITE_BIT_EXT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    for (int i = 0; i < g_npend; i++) {
        for (uint64_t done = 0; done < g_pend[i].len;) {
            VkBuffer b; VkDeviceSize o; uint64_t k = vka_gpu_addr_run(g_pend[i].dst + done, g_pend[i].len - done, &b, &o);   // PEANUTS: resolve virtual pool buffers
            if (!k) { VRED_ONCE("transform feedback: buffer at %#llx is not in placed guest RAM: its capture is dropped", (unsigned long long)(g_pend[i].dst + done)); break; }
            VkBufferCopy c = {g_pend[i].off + done, o, k}; vk.vkCmdCopyBuffer(cb, g_ch[g_pend[i].ch].buf, b, 1, &c); done += k;
        }
        g_ncopied++;
    }
    vka_barrier(cb);   // the copies before whatever reads the buffers next
    g_npend = 0;
    for (int i = 0; i < g_nch; i++) g_ch[i].used = 0;
}
void vka_tf_state(const p_tf_state *s, VkCommandBuffer cb) {
    if (s->what == 0) { memset(g_off, 0, sizeof g_off); g_verts = 0; g_seq = 0; return; }   // Begin: each buffer written from its start
    if (s->what == 3) {   // Resume: on from where the End / Pause into this control buffer left
        for (unsigned i = 0; i < 16; i++) if (g_saved[i].ctl == s->ctl && s->ctl) { memcpy(g_off, g_saved[i].off, sizeof g_off); g_verts = g_saved[i].verts; break; }
        return;
    }
    if (!g_seq) return;   // End / Pause with nothing captured since: as before this file
    g_seq = 0;
    unsigned k = 0; while (k < 16 && g_saved[k].ctl != s->ctl) k++;
    if (k == 16) k = g_nsaved++ % 16;
    g_saved[k].ctl = s->ctl; memcpy(g_saved[k].off, g_off, sizeof g_off); g_saved[k].verts = g_verts;
    if (!cb) return;
    vka_rp_end();   // what was captured is read next (as vertex streams): the pass ends and its captures are copied (vka_tf_flush)
    // The control buffer as nvn_feedback.h has it: four byte offsets, then the arguments of the draw DrawTransformFeedback makes (vertices, 1 instance, first 0, base 0)
    VkBuffer b; VkDeviceSize o; vka_span sp;
    if (!s->ctl || vka_gpu_addr_run(s->ctl, 32, &b, &o) < 32 || !vka_scratch(32, 16, &sp)) return;   // PEANUTS: resolve virtual pool buffers
    uint32_t st[8] = {(uint32_t)g_off[0], (uint32_t)g_off[1], (uint32_t)g_off[2], (uint32_t)g_off[3], g_verts, 1, 0, 0};
    memcpy(sp.ptr, st, 32);
    VkBufferCopy c = {sp.off, o, 32}; vk.vkCmdCopyBuffer(cb, sp.buf, b, 1, &c);
    vka_barrier(cb);
}
#endif
