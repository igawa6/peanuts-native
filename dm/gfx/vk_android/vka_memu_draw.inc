// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors (the compute mesh emulation: pavelgajdo/Hokkaido #569)
// SPDX-FileCopyrightText: Copyright 2026 Banana Native (this port)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Included by vka_draw.c (its bindings and descriptor writes): the compute work of NVN2 task and mesh draws drawn through compute (docs/reference/rendering.md "Mesh draws
// through compute"; vka_memu.c, vka_memu_spv.c). vka_exec.c's look-ahead (memu_scan) finds, when a render pass is about to open, every mesh draw from there to the next
// record that could change what they read or the pass's targets (a dispatch, copy, fill, inline update, barrier, render target change, wait, render condition), and walks
// those records three times with the replay state as it will be at each draw (vka_state_op on a copy):
//   1 (MEMU_ADD) each draw's shaders, compute pipelines and record (its count and first workgroup: the direct draw's, or its indirect record copied on the GPU);
//     then the setup pass: payload and count space for task draws, the mesh output and the dispatch / draw arguments of draws without a task;
//   2 (MEMU_TASK) each task draw's task workgroups (vkCmdDispatchIndirect); then the distribution pass: each task draw's offsets and mesh output;
//   3 (MEMU_MESH) each draw's mesh workgroups;
// with one barrier between the steps (memu_bar), so the pass's whole mesh work costs five barriers (three without a task draw) and no render pass break; Hokkaido ended the pass for each draw. The draws
// then find their record (vka_memu_cur) and draw the arena with the mesh shader's vertex shader: vkCmdDrawIndexedIndirect (indexed triangles) or vkCmdDrawIndirect.
// A mesh draw met inside an open pass that the look-ahead did not reach ends the pass and has the look-ahead run from it (counted: "render passes split"). GPL-3.0-or-later.
// A draw's entry is the next one of its record in walk order, not the first one of its record (vka_memu_order.h: one look-ahead can meet a record more than once)
// The look-ahead also crosses game barriers (DM_VK_MESH_EMU_BAR, vka_exec.c memu_walk): each entry keeps how many it crossed before its draw (bar); the replay counts
// the barriers it meets (vka_memu_bar_met) and drops the entries past one it did not take inside the pass, which a new look-ahead from there then makes again
#include "vka_memu_order.h"
enum { MEMU_MAX = VKA_MEMU_ORDER_MAX };
typedef struct { int ok, rec, indexed, points; vka_shader *vs, *ts, *ms, *tcs, *mcs; vka_layout tl, ml; VkPipeline tp, mp; } memu_ent;
static struct { int live, ntask, bars; vka_memu_order o; memu_ent e[MEMU_MAX]; uint16_t bar[MEMU_MAX]; vka_span rec; VkBuffer arena, ctl; uint32_t cap; } MB;
static memu_ent *g_memu_cur;
uint64_t vka_memu_n_cross, vka_memu_n_dropped, vka_memu_n_nbar;   // entries made past a game barrier, entries dropped at a barrier not taken in the pass, the batches' own barriers
// A task or mesh shader whose compute may run before draws that came before a game barrier in the pass: it writes no memory (those draws may read what it overwrites)
// and samples no texture or image (one may be what they draw into). Reads of buffers are fine: those draws write none (a draw that writes memory makes the next game
// barrier end the pass, vka_rp_game_barrier, and the entries past it go). 378 of OoT's 495 mesh shaders bind neither
static int memu_hoistable(const vka_shader *s) {
    if (!s || !s->e || !s->b) return 1;
    for (unsigned i = 0; i < s->e->bind_count; i++) switch (s->b[i].kind) {
        case DMSH_VK_KIND_SBUF: case DMSH_VK_KIND_SBUF_UBO: if (s->b[i].flags & DMSH_VK_BF_RW) return 0; break;
        case DMSH_VK_KIND_TEX: case DMSH_VK_KIND_TBUF: case DMSH_VK_KIND_IMG: case DMSH_VK_KIND_TEX_UBO: case DMSH_VK_KIND_TEX_C2A: case DMSH_VK_KIND_TEX_POOL:
        case DMSH_VK_KIND_GMEM_LOG: case DMSH_VK_KIND_UNKNOWN: return 0;
        default: break;
    }
    return 1;
}
// A game barrier the replay met (vka_exec.c); kept: taken inside the open pass (vka_rp_game_barrier). One not taken there ended the pass with a full barrier, or came
// with no pass open: the entries the look-ahead made past it would have run before the work it orders, so they go (the next mesh draw looks ahead again from there)
void vka_memu_bar_met(int kept) {
    if (kept) { if (MB.bars < 65535) MB.bars++; return; }
    vka_memu_n_dropped += (uint64_t)vka_memu_order_cut(&MB.o, MB.bar, MB.bars);
}
// The record being replayed: its entry (the next of its record from the batch's cursor, taken), NULL when the batch has none left for it
void vka_memu_cur(const uint8_t *p) { int i = vka_memu_order_take(&MB.o, p); g_memu_cur = i < 0 ? NULL : &MB.e[i]; }
int vka_memu_on(void) { return V.memu; }
int vka_memu_has(const uint8_t *p) { return vka_memu_order_find(&MB.o, p) >= 0; }   // the batch still has an entry for a meeting of record p
static memu_ent *memu_find(void) { return g_memu_cur; }   // the entry of the draw being replayed (vka_memu_cur), NULL none
static void memu_write(uint32_t binding, VkBuffer b, VkDeviceSize off, VkDeviceSize range) {
    if (g_w.n == 512) return;
    uint32_t k = g_w.n++;
    g_w.bi[k] = (VkDescriptorBufferInfo){b, off, range};
    g_w.w[k] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = binding, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &g_w.bi[k]};
}
static void memu_bind_arena(void) { memu_write(VKA_MEMU_ARENA_BINDING, MB.arena, 0, VK_WHOLE_SIZE); }
// A compute form's dispatch: its stage's bindings walked with the replay state of its draw, the arena and the records, its record's index, its indirect arguments
static void memu_dispatch(const vka_dstate *d, int st, vka_shader *cs, const vka_layout *lay, VkPipeline p, int idx, uint32_t argw) {
    vka_res *r = g_wk.r[5]; int pool = 0; char why[200];
    int n = vka_walk(&d->b, st, cs->e, cs->b, cs->lib->h->ssbo_align, V.sb_align, rd_cb, NULL, r, 512, &pool, why, sizeof why);
    if (n < 0) { static _Atomic int k; if (atomic_fetch_add(&k, 1) < 8) VRED("%s shader through compute %016llx: %s; skipped", st == 6 ? "task" : "mesh", (unsigned long long)cs->key, why); return; }
    for (int i = 0; i < n; i++) if ((r[i].what == VKA_R_TEX || r[i].what == VKA_R_IMG) && r[i].tid && (vka_tex_id_pending_rec(r[i].tid, r[i].dim) || (r[i].what == VKA_R_IMG && vka_tex_id_storage_pending(r[i].tid))))
        vka_tex_id_view(r[i].tid, g_cb, r[i].dim, wbf_view_of_binding(r[i].what == VKA_R_IMG, r[i].flags, DMSH_VK_BF_RW), NULL);   // (images first used: made now, outside the pass)
    g_w.n = 0;
    if (!stage_writes(d, st, cs, r, n, pool, NULL)) return;
    memu_write(VKA_MEMU_ARENA_BINDING, MB.arena, 0, VK_WHOLE_SIZE);
    memu_write(VKA_MEMU_REC_BINDING, MB.rec.buf, MB.rec.off, (VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4);
    if (!sets_push(lay, VK_PIPELINE_BIND_POINT_COMPUTE, pool)) return;
    uint32_t pc[4] = {(uint32_t)idx, 0, 0, 0};
    vk.vkCmdPushConstants(g_cb, lay->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, VKA_MEMU_PUSH_BYTES, pc);
    vk.vkCmdBindPipeline(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, p);
    vk.vkCmdDispatchIndirect(g_cb, MB.rec.buf, MB.rec.off + ((VkDeviceSize)idx * VKA_MEMU_REC_WORDS + argw) * 4);
}
// A helper pass over the batch's records (setup: one invocation each; distribution: one workgroup each)
static void memu_helper(int dist) {
    VkPipeline ps, pd; VkPipelineLayout pl; if (!vka_memu_helpers(&ps, &pd, &pl)) return;
    VkDescriptorBufferInfo bi[3] = {{MB.rec.buf, MB.rec.off, (VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4}, {MB.ctl, 0, 64}, {MB.arena, 0, VK_WHOLE_SIZE}};
    VkWriteDescriptorSet w[3];
    for (uint32_t i = 0; i < 3; i++) w[i] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstBinding = i, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &bi[i]};
    vk.vkCmdBindPipeline(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, dist ? pd : ps);
    vk.vkCmdPushDescriptorSetKHR(g_cb, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 3, w);
    g_pd[1].n = 0;   // (another layout's push: the replay's last compute push is forgotten)
    uint32_t pc[4] = {0, (uint32_t)MB.o.n, MB.cap, 0};
    vk.vkCmdPushConstants(g_cb, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, pc);
    vk.vkCmdDispatch(g_cb, dist ? (uint32_t)MB.o.n : ((uint32_t)MB.o.n + 63) / 64, 1, 1);
}
// The barriers between a batch's steps order only its own compute work (the setup, task, distribution and mesh dispatches write the records and the arena; the next step
// reads them, as shader reads and as dispatch arguments), so they are compute to compute and indirect; the last one hands the mesh output to the draws (index and vertex
// reads, the draw arguments) and the mesh shaders' own storage writes to whatever comes after: from compute (and the transfers of images first used in the batch) to
// everything. The first one stays a full barrier: it orders the work before the batch (the arena's control words cleared, the indirect records copied, the
// dispatches before). A full barrier there made the four a batch had flush and invalidate the colour and depth caches too (Turnip: the CCU), work that has none.
// A batch without a task draw has no distribution step (its setup places every mesh output), so its mesh dispatches follow the setup's barrier: two barriers and a
// dispatch fewer
static void memu_bar(int last) {
    VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | (last ? VK_ACCESS_TRANSFER_WRITE_BIT : 0),
                          .dstAccessMask = last ? VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT : VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT};
    vk.vkCmdPipelineBarrier(g_cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | (last ? VK_PIPELINE_STAGE_TRANSFER_BIT : 0),
                            last ? VK_PIPELINE_STAGE_ALL_COMMANDS_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    vka_memu_n_nbar++;
    if (vka_gp_active) vka_gp_mark(g_cb, __FILE_NAME__, __LINE__ + last);   // (DM_VK_GPUPROF: a step ends here)
}
enum { MEMU_BEGIN, MEMU_ADD, MEMU_SETUP, MEMU_TASK, MEMU_DIST, MEMU_MESH, MEMU_END };
// Phase MEMU_ADD returns 0 when the look-ahead stops before this draw (bars: the game barriers the look-ahead crossed before it; its shaders are not hoistable), else 1
int vka_memu_batch(int phase, const vka_dstate *d, const p_draw *dw, const uint8_t *p, int idx, int bars) {
    if (phase == MEMU_BEGIN) { vka_memu_order_reset(&MB.o); MB.live = 0; MB.ntask = 0; MB.bars = 0; g_memu_cur = NULL; return 1; }
    if (phase == MEMU_ADD) {
        if (MB.o.n == MEMU_MAX || !g_cb) return 1;
        vka_shader *ts = NULL, *ms = NULL; unsigned bad = 0;
        const prog_t *mp = d->bst[7];   // (as draw_body: the fragment stage's program when it has a mesh shader)
        { vka_shader *m = NULL; unsigned b2 = 0; if (d->bst[1] && d->bst[1] != mp && resolve(d->bst[1], 7, &m, &b2) && (m || (b2 & 1u << 7))) mp = d->bst[1]; }
        int res = resolve(mp, 6, &ts, &bad) && resolve(mp, 7, &ms, &bad) && !bad && ms;
        if (bars > 0 && (!res || !memu_hoistable(ts) || !memu_hoistable(ms))) return 0;
        if (!MB.live) {
            MB.live = -1;
            int sa = V.sb_align > 64 ? (int)V.sb_align : 64;
            if (!vka_scratch((VkDeviceSize)MEMU_MAX * VKA_MEMU_REC_WORDS * 4, (VkDeviceSize)sa, &MB.rec) || !vka_memu_arena(g_cb, &MB.arena, &MB.ctl, &MB.cap)) return 1;
            vka_disp_settle(g_cb);   // (the dispatches before write what the batch reads: indirect records, buffers)
            MB.live = 1;
        }
        if (MB.live < 0) return 1;
        int ei = vka_memu_order_add(&MB.o, p); if (ei < 0) return 1;
        memu_ent *E = &MB.e[ei]; memset(E, 0, sizeof *E); E->rec = ei;
        MB.bar[ei] = (uint16_t)(bars < 65535 ? bars : 65535); if (bars > 0) vka_memu_n_cross++;
        uint32_t *r = (uint32_t *)(MB.rec.ptr + (size_t)E->rec * VKA_MEMU_REC_WORDS * 4); memset(r, 0, VKA_MEMU_REC_WORDS * 4);
        if (!res) return 1;
        vka_memu_sh *mi = vka_memu_of(ms), *ti = ts ? vka_memu_of(ts) : NULL;
        if (!mi || (ts && !ti)) return 1;
        E->ts = ts; E->ms = ms; E->vs = vka_memu_vs_of(ms); E->mcs = vka_memu_cs_of(ms, !vka_depth_zero()); E->tcs = ts ? vka_memu_cs_of(ts, 0) : NULL;
        if (!E->vs || !E->mcs || (ts && !E->tcs)) return 1;
        vka_shader *c5[VKA_NSTAGE] = {0};
        c5[5] = E->mcs; if (!vka_layout_of(c5, 1, &E->ml) || !(E->mp = vka_cs_pipeline(E->mcs, &E->ml))) return 1;
        if (ts) { c5[5] = E->tcs; if (!vka_layout_of(c5, 1, &E->tl) || !(E->tp = vka_cs_pipeline(E->tcs, &E->tl))) return 1; }
        vka_memu_rec_mesh(&mi->L, r);
        r[VKA_MEMU_R_FLAGS] |= ts ? VKA_MEMU_F_TASK : 0; r[VKA_MEMU_R_WT] = ts ? ti->L.payload_words : 0;
        r[VKA_MEMU_R_MAXG] = V.cs_groups < 65535 ? V.cs_groups : 65535;
        if (dw->ind) {   // {count, first}, as the GPU holds it now
            VkBuffer b; VkDeviceSize o;
            if (!vka_addr(dw->ind, 8, &b, &o, NULL) || (o & 3)) { static _Atomic int k; if (atomic_fetch_add(&k, 1) < 4) VRED("indirect mesh draw record at %#llx not in placed guest RAM: skipped", (unsigned long long)dw->ind); return 1; }
            VkBufferCopy c = {o, MB.rec.off + ((VkDeviceSize)E->rec * VKA_MEMU_REC_WORDS + VKA_MEMU_R_COUNT) * 4, 8}; vk.vkCmdCopyBuffer(g_cb, b, MB.rec.buf, 1, &c);
        } else { r[VKA_MEMU_R_COUNT] = dw->count > 0 ? (uint32_t)dw->count : 0; r[VKA_MEMU_R_FIRST] = dw->first > 0 ? (uint32_t)dw->first : 0; }
        E->indexed = mi->L.indexed; E->points = mi->L.points; E->ok = 1; MB.ntask += ts != NULL;
        return 1;
    }
    if (MB.live <= 0 || !MB.o.n) return 1;
    switch (phase) {
    case MEMU_SETUP: vka_barrier(g_cb); memu_helper(0); memu_bar(0); break;
    case MEMU_TASK: if (idx < MB.o.n && MB.e[idx].ok && MB.e[idx].tcs) memu_dispatch(d, 6, MB.e[idx].tcs, &MB.e[idx].tl, MB.e[idx].tp, idx, VKA_MEMU_R_TASK_X); break;
    case MEMU_DIST: if (MB.ntask) { memu_bar(0); memu_helper(1); memu_bar(0); } break;
    case MEMU_MESH: if (idx < MB.o.n && MB.e[idx].ok) memu_dispatch(d, 7, MB.e[idx].mcs, &MB.e[idx].ml, MB.e[idx].mp, idx, VKA_MEMU_R_MESH_X); break;
    case MEMU_END: memu_bar(1); vka_memu_n_batches++; vka_memu_n_draws += (uint64_t)MB.o.n; break;
    }
    return 1;
}
