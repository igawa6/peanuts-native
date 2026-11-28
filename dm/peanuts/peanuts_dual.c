// PEANUTS: direct 4-RTT dual-screen capture and GPU second-swapchain presentation (peanuts_dual.h).
#if defined(__ANDROID__)
#include "peanuts_dual.h"
#include "../gfx/vk_android/vka.h"
#include "../gfx/vk_android/vka_present_rot.h"
#include "../gfx/vk_android/vka_present_fit.h"
#include "../host/front.h"
#include <android/native_window.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { DUAL_PQ = 8, DUAL_NFRAME = 3, DUAL_MAXIMG = 8 };

// ---------- Live switch ----------
static _Atomic int g_wanted_cached = -1;
static _Atomic uint32_t g_wanted_gen = UINT32_MAX;

int pn_dual_wanted(void) {
    uint32_t gen = atomic_load_explicit(&vka_env_gen, memory_order_relaxed);
    if (gen != atomic_load_explicit(&g_wanted_gen, memory_order_relaxed)) {
        char b[16] = {0};
        int on = 0;
        if (vka_env_str("PN_DUAL_RTT", b, sizeof b) && b[0] && b[0] != '0') on = 1;
        if (on && vka_env_str("PN_COMPANION", b, sizeof b) && (!strcmp(b, "off") || !strcmp(b, "0"))) on = 0;
        atomic_store_explicit(&g_wanted_cached, on, memory_order_relaxed);
        atomic_store_explicit(&g_wanted_gen, gen, memory_order_relaxed);
    }
    return atomic_load_explicit(&g_wanted_cached, memory_order_relaxed) > 0;
}

// ---------- Replay-thread 4-RTT detector ----------
typedef struct {
    vka_img *main_ds, *main_ui;
    vka_img *sub_ds, *sub_ui;
    vka_img *comp_img;   // 1920x1080 final composite target when an encounter transition shader draws over Top
    float top_uv[4];     // normalized [u0, v0, du, dv] of Top screen inside comp_img
    uint64_t ticket;
    int active;
    int top_from_comp;   // 1 = sample Top from comp_img at top_uv (preserves encounter ripple/zoom/fade)
    int premul;   // 1 = ONE / ONE_MINUS_SRC_ALPHA, 0 = SRC_ALPHA / ONE_MINUS_SRC_ALPHA
    int swapped;  // 1 = Bottom DS screen on Top display, Top DS screen on Bottom display
} dual_frame;

static struct {
    vka_img *ds_from_256;
    uint64_t comp_serial;
    vka_img *pending_ds;
    struct { vka_img *ds, *ui; } pair[2];
    int npairs;
    vka_img *rt1440[6];
    int n1440;
    int extra_after;
    int ui_blend_en, ui_src_rgb, ui_dst_rgb;
    vka_img *comp_img;
    int top_fx;
} g_rec;

static uint64_t g_latch_main_ds, g_latch_main_ui, g_latch_sub_ui, g_latch_bat_bg;
static pthread_mutex_t g_dmu = PTHREAD_MUTEX_INITIALIZER;
static dual_frame g_ring[DUAL_PQ];
static dual_frame g_slot[DUAL_NFRAME];   // owned by the present thread's main slot k
static _Atomic int g_dual_active;
static _Atomic int g_aux_attached;
static _Atomic int g_swap_toggle;
static _Atomic int g_swap_btn_prev;

void pn_dual_pad_note(uint64_t raw_b) {
    if (!pn_dual_wanted()) return;
    int bit = VKA_ENV("PN_DUAL_SWAP_BTN", 8);   // bit 8 = ZL (L2); -1 disables button toggle
    if (bit < 0 || bit >= 16) return;
    int down = ((raw_b >> bit) & 1ull) != 0;
    if (!atomic_load_explicit(&g_dual_active, memory_order_relaxed)) {
        atomic_store_explicit(&g_swap_btn_prev, down, memory_order_relaxed);
        return;
    }
    int prev = atomic_exchange_explicit(&g_swap_btn_prev, down, memory_order_relaxed);
    if (down && !prev) {
        int now = atomic_fetch_xor_explicit(&g_swap_toggle, 1, memory_order_relaxed) ^ 1;
        VLOG("dual_rtt: screens -> %s (L2/ZL toggle)", now ? "SWAPPED (Bottom on Top)" : "NORMAL (Top on Top)");
    }
}

uint64_t pn_dual_pad_mask(uint64_t b) {
    if (!pn_dual_wanted() || !atomic_load_explicit(&g_dual_active, memory_order_relaxed)) return b;
    int bit = VKA_ENV("PN_DUAL_SWAP_BTN", 8);
    if (bit < 0 || bit >= 16) return b;
    return b & ~(1ull << bit);
}

static void df_drop(dual_frame *f) {
    if (f->main_ds) { vka_tex_drop(f->main_ds); f->main_ds = NULL; }
    if (f->main_ui) { vka_tex_drop(f->main_ui); f->main_ui = NULL; }
    if (f->sub_ds)  { vka_tex_drop(f->sub_ds);  f->sub_ds  = NULL; }
    if (f->sub_ui)  { vka_tex_drop(f->sub_ui);  f->sub_ui  = NULL; }
    if (f->comp_img){ vka_tex_drop(f->comp_img);f->comp_img= NULL; }
    f->active = 0; f->top_from_comp = 0; f->ticket = 0;
}

void pn_dual_observe_draw(vka_img *dst, VkFormat dst_fmt, uint32_t dst_w, uint32_t dst_h,
                          vka_img *tex, int blend_en, int src_rgb, int dst_rgb) {
    if (!dst || !tex) return;
    // Pass 124 (256x192) -> 120 (512x384) unambiguously identifies RTOMain regardless of Screen Layout or L2 focus.
    if (dst_w == 512 && dst_h == 384 && dst_fmt == VK_FORMAT_R8G8B8A8_UNORM &&
        tex->w == 256 && tex->h == 192 && tex->f.vk == VK_FORMAT_R8G8B8A8_UNORM) {
        g_rec.ds_from_256 = dst;
        return;
    }
    // Final 1920x1080 sRGB composite pass (e.g. serial 3141/3246)
    if (dst_w == 1920 && dst_h == 1080 && dst_fmt == VK_FORMAT_R8G8B8A8_SRGB) {
        if (g_rec.comp_serial && g_rec.comp_serial != dst->serial) {
            g_rec.npairs = 0; g_rec.n1440 = 0; g_rec.pending_ds = NULL; g_rec.extra_after = 0;
            g_rec.top_fx = 0;
        }
        g_rec.comp_serial = dst->serial;
        g_rec.comp_img = dst;
        if (tex->w == 512 && tex->h == 384 && tex->f.vk == VK_FORMAT_R8G8B8A8_UNORM) {
            int repeat_ds = (g_rec.npairs >= 1 && g_rec.pair[0].ds == tex) ||
                            (g_rec.npairs >= 2 && g_rec.pair[1].ds == tex);
            if (repeat_ds) {
                // Encounter transition post-process pass (e.g. wavy ripple / zoom / white fade drawn over Top on 1920x1080).
                g_rec.top_fx = 1;
            } else {
                g_rec.pending_ds = tex;
            }
        } else if (tex->w == 1440 && tex->h == 1080 && tex->f.vk == VK_FORMAT_R8G8B8A8_UNORM) {
            if (g_rec.n1440 < 6) g_rec.rt1440[g_rec.n1440++] = tex;
            if (g_rec.pending_ds) {
                if (g_rec.npairs < 2) {
                    g_rec.pair[g_rec.npairs].ds = g_rec.pending_ds;
                    g_rec.pair[g_rec.npairs].ui = tex;
                    g_rec.npairs++;
                    g_rec.ui_blend_en = blend_en;
                    g_rec.ui_src_rgb = src_rgb;
                    g_rec.ui_dst_rgb = dst_rgb;
                } else {
                    g_rec.extra_after++;
                }
                g_rec.pending_ds = NULL;
            } else {
                if (blend_en) { g_rec.ui_blend_en = blend_en; g_rec.ui_src_rgb = src_rgb; g_rec.ui_dst_rgb = dst_rgb; }
            }
        } else if ((g_rec.npairs >= 2 || g_rec.n1440 >= 2) && (tex->w > 16 || tex->h > 16)) {
            // Count non-trivial overlay draws after the DS screen targets; small 1-5 draw badges (Autosave/Manual Save) stay in DUAL.
            g_rec.extra_after++;
        } else if (tex->w > 16 || tex->h > 16) {
            g_rec.pending_ds = NULL;
        }
    }
}

void pn_dual_frame_fill(uint64_t ticket) {
    if (!pn_dual_wanted()) { memset(&g_rec, 0, sizeof g_rec); return; }
    dual_frame out = {0};
    out.ticket = ticket;
    if (g_rec.npairs == 2 && g_rec.extra_after < 6 &&
        g_rec.pair[0].ds && g_rec.pair[0].ui && g_rec.pair[1].ds && g_rec.pair[1].ui &&
        g_rec.pair[0].ds != g_rec.pair[1].ds && g_rec.pair[0].ui != g_rec.pair[1].ui) {
        int mi = -1;
        if (g_latch_main_ds) {
            if (g_rec.pair[0].ds->serial == g_latch_main_ds) mi = 0;
            else if (g_rec.pair[1].ds->serial == g_latch_main_ds) mi = 1;
        }
        if (mi < 0 && g_rec.ds_from_256) {
            if (g_rec.pair[0].ds == g_rec.ds_from_256) mi = 0;
            else if (g_rec.pair[1].ds == g_rec.ds_from_256) mi = 1;
        }
        if (mi < 0) mi = g_rec.pair[0].ds->serial < g_rec.pair[1].ds->serial ? 0 : 1;
        int si = 1 - mi;
        g_latch_main_ui = g_rec.pair[mi].ui->serial;
        g_latch_sub_ui  = g_rec.pair[si].ui->serial;
        if (g_latch_main_ds != g_rec.pair[mi].ds->serial) {
            g_latch_main_ds = g_rec.pair[mi].ds->serial;
            VLOG("dual_rtt: locked Top=(ds %llu %ux%u + ui %llu %ux%u) Bottom=(ds %llu %ux%u + ui %llu %ux%u) blend=%d(%d,%d)",
                 (unsigned long long)g_rec.pair[mi].ds->serial, g_rec.pair[mi].ds->w, g_rec.pair[mi].ds->h,
                 (unsigned long long)g_rec.pair[mi].ui->serial, g_rec.pair[mi].ui->w, g_rec.pair[mi].ui->h,
                 (unsigned long long)g_rec.pair[si].ds->serial, g_rec.pair[si].ds->w, g_rec.pair[si].ds->h,
                 (unsigned long long)g_rec.pair[si].ui->serial, g_rec.pair[si].ui->w, g_rec.pair[si].ui->h,
                 g_rec.ui_blend_en, g_rec.ui_src_rgb, g_rec.ui_dst_rgb);
        }
        out.main_ds = vka_tex_ref(g_rec.pair[mi].ds);
        out.main_ui = vka_tex_ref(g_rec.pair[mi].ui);
        out.sub_ds  = vka_tex_ref(g_rec.pair[si].ds);
        out.sub_ui  = vka_tex_ref(g_rec.pair[si].ui);
        out.active  = (out.main_ds && out.main_ui && out.sub_ds && out.sub_ui) ? 1 : 0;
        // NVN_BLEND_FUNC_ONE = 2 (premultiplied), NVN_BLEND_FUNC_SRC_ALPHA = 5 (straight alpha). Live override: PN_DUAL_BLEND=0|1.
        int ov = VKA_ENV("PN_DUAL_BLEND", -1);
        out.premul = ov >= 0 ? (ov != 0) : (g_rec.ui_blend_en && g_rec.ui_src_rgb == 2);
        if (!out.active) df_drop(&out);
    } else if (g_rec.n1440 >= 2 && g_rec.extra_after < 6) {
        // Battle / 3D-RTT mode: Top = (1440x1080 3D battle RT e.g. 65) + RTOMainUI (122), Bottom = RTOSubUI (124) (+ optional 512x384).
        vka_img *mui = NULL, *sui = NULL, *mbg = NULL, *sbg = NULL;
        for (int i = 0; i < g_rec.n1440; i++) {
            vka_img *im = g_rec.rt1440[i];
            if (g_latch_main_ui && im->serial == g_latch_main_ui) {
                mui = im;
                if (i > 0 && g_rec.rt1440[i - 1]->serial != g_latch_sub_ui && g_rec.rt1440[i - 1] != im) mbg = g_rec.rt1440[i - 1];
            } else if (g_latch_sub_ui && im->serial == g_latch_sub_ui) {
                sui = im;
                if (i > 0 && g_rec.rt1440[i - 1]->serial != g_latch_main_ui && g_rec.rt1440[i - 1] != im && g_rec.rt1440[i - 1] != mbg) sbg = g_rec.rt1440[i - 1];
            }
        }
        if (!mui && !sui && !g_latch_main_ui && !g_latch_sub_ui) {
            if (g_rec.npairs == 1 && g_rec.n1440 == 2) {
                mui = g_rec.pair[0].ui; mbg = g_rec.pair[0].ds;
                sui = (g_rec.rt1440[0] == mui) ? g_rec.rt1440[1] : g_rec.rt1440[0];
                g_latch_main_ds = mbg->serial;
                g_latch_main_ui = mui->serial;
                g_latch_sub_ui  = sui->serial;
            } else if (g_rec.n1440 == 3) {
                mbg = g_rec.rt1440[0]; mui = g_rec.rt1440[1]; sui = g_rec.rt1440[2];
            }
        }
        if (!mbg && mui && sui) {
            for (int i = 0; i < g_rec.n1440; i++) {
                vka_img *im = g_rec.rt1440[i];
                if (im != mui && im != sui && im != sbg) { mbg = im; break; }
            }
        }
        if (mui && sui) {
            if (!mbg && g_rec.npairs == 1 && g_rec.pair[0].ui == mui) mbg = g_rec.pair[0].ds;
            if (!sbg && g_rec.npairs == 1 && g_rec.pair[0].ui == sui) sbg = g_rec.pair[0].ds;
            uint64_t bgs = mbg ? mbg->serial : 0;
            if (g_latch_bat_bg != bgs || bgs == 0) {
                if (g_latch_bat_bg != (bgs ? bgs : 1)) {
                    g_latch_bat_bg = bgs ? bgs : 1;
                    VLOG("dual_rtt: battle 3D-RTT Top=(bg %llu + ui %llu) Bottom=(bg %llu + ui %llu) blend=%d(%d,%d)",
                         (unsigned long long)bgs, (unsigned long long)mui->serial,
                         (unsigned long long)(sbg ? sbg->serial : 0), (unsigned long long)sui->serial,
                         g_rec.ui_blend_en, g_rec.ui_src_rgb, g_rec.ui_dst_rgb);
                }
            }
            out.main_ds = mbg ? vka_tex_ref(mbg) : NULL;
            out.main_ui = vka_tex_ref(mui);
            out.sub_ds  = sbg ? vka_tex_ref(sbg) : NULL;
            out.sub_ui  = vka_tex_ref(sui);
            out.active  = (out.main_ui && out.sub_ui) ? 1 : 0;
            int ov = VKA_ENV("PN_DUAL_BLEND", -1);
            out.premul = ov >= 0 ? (ov != 0) : (g_rec.ui_blend_en && g_rec.ui_src_rgb == 2);
            if (!out.active) df_drop(&out);
        }
    }
    if (out.active && g_rec.top_fx && g_rec.comp_img && VKA_ENV("PN_DUAL_TRANSITION_FX", 1)) {
        out.comp_img = vka_tex_ref(g_rec.comp_img);
        if (out.comp_img) {
            out.top_from_comp = 1;
            // Default Switch Big-Top-Left layout (0..1440, 0..1080); optional live override PN_DUAL_TRANSITION_UV=u0,v0,du,dv.
            out.top_uv[0] = 0.0f; out.top_uv[1] = 0.0f; out.top_uv[2] = 1440.0f / 1920.0f; out.top_uv[3] = 1.0f;
            char ub[64] = {0};
            if (vka_env_str("PN_DUAL_TRANSITION_UV", ub, sizeof ub) && ub[0]) {
                float u0, v0, du, dv;
                if (sscanf(ub, "%f,%f,%f,%f", &u0, &v0, &du, &dv) == 4 && du > 0.f && dv > 0.f) {
                    out.top_uv[0] = u0; out.top_uv[1] = v0; out.top_uv[2] = du; out.top_uv[3] = dv;
                }
            }
            static int fx_logged;
            if (!fx_logged) {
                fx_logged = 1;
                VLOG("dual_rtt: encounter transition FX active (comp=%llu uv=%.3f,%.3f,%.3f,%.3f)",
                     (unsigned long long)out.comp_img->serial,
                     out.top_uv[0], out.top_uv[1], out.top_uv[2], out.top_uv[3]);
            }
        }
    }
    memset(&g_rec, 0, sizeof g_rec);
    pthread_mutex_lock(&g_dmu);
    dual_frame *slot = &g_ring[(ticket - 1) % DUAL_PQ];
    df_drop(slot);
    *slot = out;
    pthread_mutex_unlock(&g_dmu);
}

void pn_dual_slot_done(unsigned k) {
    if (k < DUAL_NFRAME) df_drop(&g_slot[k]);
}

void pn_dual_slot_drop_all(void) {
    for (unsigned k = 0; k < DUAL_NFRAME; k++) df_drop(&g_slot[k]);
}

int pn_dual_take(uint64_t ticket, unsigned k) {
    if (k >= DUAL_NFRAME) return 0;
    df_drop(&g_slot[k]);
    if (!ticket || !pn_dual_wanted()) return 0;
    pthread_mutex_lock(&g_dmu);
    dual_frame *r = &g_ring[(ticket - 1) % DUAL_PQ];
    if (r->ticket == ticket) {
        g_slot[k] = *r;
        memset(r, 0, sizeof *r);
    }
    pthread_mutex_unlock(&g_dmu);
    if (!atomic_load_explicit(&g_aux_attached, memory_order_acquire)) {
        df_drop(&g_slot[k]);
    }
    atomic_store_explicit(&g_dual_active, g_slot[k].active, memory_order_relaxed);
    int sov = VKA_ENV("PN_DUAL_SWAP", -1);
    g_slot[k].swapped = sov >= 0 ? (sov != 0) : atomic_load_explicit(&g_swap_toggle, memory_order_relaxed);
    static int last_active = -1;
    if (g_slot[k].active != last_active) {
        last_active = g_slot[k].active;
        VLOG("dual_rtt: mode -> %s", last_active ? "DUAL (4-RTT native)" : "PASSTHROUGH (menu/single-screen)");
    }
    return g_slot[k].active;
}

// ---------- Shared Vulkan dynamic-rendering 2-layer composite pipeline ----------
#define DK_FNS(X) X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreatePipelineLayout) X(vkCreateGraphicsPipelines) \
    X(vkDestroyPipeline) X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdPushConstants) \
    X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdDraw)
static struct { DK_FNS(VKA_DECL) } dk;

static int dk_load(void) {
    static int ok = -1;
    if (ok >= 0) return ok;
#define DK_LOAD(f) dk.f = (PFN_##f)vk.vkGetDeviceProcAddr(V.dev, #f); if (!dk.f) { VRED("dual_rtt: no %s", #f); return ok = 0; }
    DK_FNS(DK_LOAD)
#undef DK_LOAD
    if (vk.vkCreateShaderModule) dk.vkCreateShaderModule = vk.vkCreateShaderModule;
    if (vk.vkCreateGraphicsPipelines) dk.vkCreateGraphicsPipelines = vk.vkCreateGraphicsPipelines;
    return ok = 1;
}

// Exact IEC 61966-2-1 linear->sRGB fragment shader for linear 1440x1080 3D battle RTTs (e.g. RE Engine RT 65).
// Rebuilt byte-for-byte from the GLSL below with the NDK's glslc (same convention as vka_present_rot.h):
//   glslc -O --target-env=vulkan1.1 -mfmt=num dual_srgb.frag -o -
//
//   dual_srgb.frag: #version 450
//   dual_srgb.frag: layout(set = 0, binding = 0) uniform sampler2D tex;
//   dual_srgb.frag: layout(location = 0) in vec2 uv;
//   dual_srgb.frag: layout(location = 0) out vec4 o;
//   dual_srgb.frag: void main() {
//   dual_srgb.frag:     vec4 s = texture(tex, uv);
//   dual_srgb.frag:     vec3 c = clamp(s.rgb, 0.0, 1.0);
//   dual_srgb.frag:     vec3 lo = c * 12.92;
//   dual_srgb.frag:     vec3 hi = 1.055 * pow(max(c, vec3(1e-5)), vec3(1.0 / 2.4)) - 0.055;
//   dual_srgb.frag:     o = vec4(mix(lo, hi, greaterThan(c, vec3(0.0031308))), s.a);
//   dual_srgb.frag: }
static const uint32_t dual_srgb_frag[] = {
0x07230203,0x00010300,0x000d000a,0x00000047,
0x00000000,0x00020011,0x00000001,0x0006000b,
0x00000001,0x4c534c47,0x6474732e,0x3035342e,
0x00000000,0x0003000e,0x00000000,0x00000001,
0x0007000f,0x00000004,0x00000004,0x6e69616d,
0x00000000,0x00000011,0x00000030,0x00030010,
0x00000004,0x00000007,0x00040047,0x0000000d,
0x00000022,0x00000000,0x00040047,0x0000000d,
0x00000021,0x00000000,0x00040047,0x00000011,
0x0000001e,0x00000000,0x00040047,0x00000030,
0x0000001e,0x00000000,0x00020013,0x00000002,
0x00030021,0x00000003,0x00000002,0x00030016,
0x00000006,0x00000020,0x00040017,0x00000007,
0x00000006,0x00000004,0x00090019,0x0000000a,
0x00000006,0x00000001,0x00000000,0x00000000,
0x00000000,0x00000001,0x00000000,0x0003001b,
0x0000000b,0x0000000a,0x00040020,0x0000000c,
0x00000000,0x0000000b,0x0004003b,0x0000000c,
0x0000000d,0x00000000,0x00040017,0x0000000f,
0x00000006,0x00000002,0x00040020,0x00000010,
0x00000001,0x0000000f,0x0004003b,0x00000010,
0x00000011,0x00000001,0x00040017,0x00000014,
0x00000006,0x00000003,0x0004002b,0x00000006,
0x00000019,0x00000000,0x0004002b,0x00000006,
0x0000001a,0x3f800000,0x0004002b,0x00000006,
0x00000020,0x414eb852,0x0004002b,0x00000006,
0x00000023,0x3f870a3d,0x0004002b,0x00000006,
0x00000025,0x3727c5ac,0x0006002c,0x00000014,
0x00000026,0x00000025,0x00000025,0x00000025,
0x0004002b,0x00000006,0x00000028,0x3ed55555,
0x0006002c,0x00000014,0x00000029,0x00000028,
0x00000028,0x00000028,0x0004002b,0x00000006,
0x0000002c,0x3d6147ae,0x00040020,0x0000002f,
0x00000003,0x00000007,0x0004003b,0x0000002f,
0x00000030,0x00000003,0x0004002b,0x00000006,
0x00000034,0x3b4d2e1c,0x0006002c,0x00000014,
0x00000035,0x00000034,0x00000034,0x00000034,
0x00020014,0x00000036,0x00040017,0x00000037,
0x00000036,0x00000003,0x0006002c,0x00000014,
0x00000044,0x00000019,0x00000019,0x00000019,
0x0006002c,0x00000014,0x00000045,0x0000001a,
0x0000001a,0x0000001a,0x0006002c,0x00000014,
0x00000046,0x0000002c,0x0000002c,0x0000002c,
0x00050036,0x00000002,0x00000004,0x00000000,
0x00000003,0x000200f8,0x00000005,0x0004003d,
0x0000000b,0x0000000e,0x0000000d,0x0004003d,
0x0000000f,0x00000012,0x00000011,0x00050057,
0x00000007,0x00000013,0x0000000e,0x00000012,
0x0008004f,0x00000014,0x00000018,0x00000013,
0x00000013,0x00000000,0x00000001,0x00000002,
0x0008000c,0x00000014,0x0000001d,0x00000001,
0x0000002b,0x00000018,0x00000044,0x00000045,
0x0005008e,0x00000014,0x00000021,0x0000001d,
0x00000020,0x0007000c,0x00000014,0x00000027,
0x00000001,0x00000028,0x0000001d,0x00000026,
0x0007000c,0x00000014,0x0000002a,0x00000001,
0x0000001a,0x00000027,0x00000029,0x0005008e,
0x00000014,0x0000002b,0x0000002a,0x00000023,
0x00050083,0x00000014,0x0000002e,0x0000002b,
0x00000046,0x000500ba,0x00000037,0x00000038,
0x0000001d,0x00000035,0x000600a9,0x00000014,
0x00000039,0x00000038,0x0000002e,0x00000021,
0x00050051,0x00000006,0x0000003e,0x00000013,
0x00000003,0x00050051,0x00000006,0x0000003f,
0x00000039,0x00000000,0x00050051,0x00000006,
0x00000040,0x00000039,0x00000001,0x00050051,
0x00000006,0x00000041,0x00000039,0x00000002,
0x00070050,0x00000007,0x00000042,0x0000003f,
0x00000040,0x00000041,0x0000003e,0x0003003e,
0x00000030,0x00000042,0x000100fd,0x00010038
};
typedef struct {
    int state;
    VkFormat fmt;
    VkPipeline pipe_opaque;
    VkPipeline pipe_opaque_srgb;
    VkPipeline pipe_straight;
    VkPipeline pipe_premul;
    VkPipelineLayout lay;
    VkDescriptorSetLayout dsl;
    VkDescriptorPool pool;
    VkDescriptorSet set[DUAL_NFRAME][2];
    VkSampler samp[2];   // 0 nearest, 1 linear
} dual_pipe;

static dual_pipe g_dp_top;
static dual_pipe g_dp_aux;

static VkResult dp_make_pipe(VkPipelineLayout lay, VkFormat fmt, int blend_mode, VkPipeline *out) {
    VkShaderModule sm[2] = {0};
    VkShaderModuleCreateInfo mv = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = sizeof vka_rot_vert, .pCode = vka_rot_vert};
    VkShaderModuleCreateInfo mf = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = blend_mode == 3 ? sizeof dual_srgb_frag : sizeof vka_rot_frag,
        .pCode = blend_mode == 3 ? dual_srgb_frag : vka_rot_frag};
    VkResult r = dk.vkCreateShaderModule(V.dev, &mv, NULL, &sm[0]);
    if (!r) r = dk.vkCreateShaderModule(V.dev, &mf, NULL, &sm[1]);
    VkPipelineShaderStageCreateInfo st[2] = {
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = sm[0], .pName = "main"},
        {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = sm[1], .pName = "main"}
    };
    VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST};
    VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, .viewportCount = 1, .scissorCount = 1};
    VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO, .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .lineWidth = 1};
    VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO, .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT};
    VkPipelineColorBlendAttachmentState cba = {.colorWriteMask = 0xf};
    if (blend_mode == 1) {   // straight alpha
        cba = (VkPipelineColorBlendAttachmentState){VK_TRUE, VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, 0xf};
    } else if (blend_mode == 2) {   // premultiplied alpha
        cba = (VkPipelineColorBlendAttachmentState){VK_TRUE, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD,
            VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_OP_ADD, 0xf};
    }
    VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO, .attachmentCount = 1, .pAttachments = &cba};
    VkDynamicState dyn[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, .dynamicStateCount = 2, .pDynamicStates = dyn};
    VkPipelineRenderingCreateInfo rci = {VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO, .colorAttachmentCount = 1, .pColorAttachmentFormats = &fmt};
    VkGraphicsPipelineCreateInfo gi = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .pNext = &rci, .stageCount = 2, .pStages = st,
        .pVertexInputState = &vi, .pInputAssemblyState = &ia, .pViewportState = &vp, .pRasterizationState = &rs,
        .pMultisampleState = &ms, .pColorBlendState = &cb, .pDynamicState = &ds, .layout = lay};
    if (!r) r = dk.vkCreateGraphicsPipelines(V.dev, VK_NULL_HANDLE, 1, &gi, NULL, out);
    for (int i = 0; i < 2; i++) if (sm[i]) dk.vkDestroyShaderModule(V.dev, sm[i], NULL);
    return r;
}

static int dp_ready(dual_pipe *p, VkFormat fmt) {
    if (p->state > 0 && p->fmt == fmt) return 1;
    if (p->state < 0) return 0;
    if (!p->state) {
        if (!dk_load()) { p->state = -1; return 0; }
        VkDescriptorSetLayoutBinding b = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL};
        VkDescriptorSetLayoutCreateInfo dl = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &b};
        VkPushConstantRange pc = {VK_SHADER_STAGE_VERTEX_BIT, 0, 6 * sizeof(float)};
        VkDescriptorPoolSize ps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, DUAL_NFRAME * 2};
        VkDescriptorPoolCreateInfo pi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = DUAL_NFRAME * 2, .poolSizeCount = 1, .pPoolSizes = &ps};
        VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .maxLod = 0.25f};
        VkResult r = vk.vkCreateDescriptorSetLayout(V.dev, &dl, NULL, &p->dsl);
        VkPipelineLayoutCreateInfo li = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &p->dsl,
            .pushConstantRangeCount = 1, .pPushConstantRanges = &pc};
        if (!r) r = dk.vkCreatePipelineLayout(V.dev, &li, NULL, &p->lay);
        if (!r) r = vk.vkCreateDescriptorPool(V.dev, &pi, NULL, &p->pool);
        VkDescriptorSetLayout dsls[DUAL_NFRAME * 2];
        for (int i = 0; i < DUAL_NFRAME * 2; i++) dsls[i] = p->dsl;
        VkDescriptorSet raw[DUAL_NFRAME * 2] = {0};
        VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, .descriptorPool = p->pool,
            .descriptorSetCount = DUAL_NFRAME * 2, .pSetLayouts = dsls};
        if (!r) r = vk.vkAllocateDescriptorSets(V.dev, &ai, raw);
        for (int k = 0; k < DUAL_NFRAME; k++) { p->set[k][0] = raw[k * 2]; p->set[k][1] = raw[k * 2 + 1]; }
        if (!r) r = vk.vkCreateSampler(V.dev, &si, NULL, &p->samp[0]);
        si.magFilter = si.minFilter = VK_FILTER_LINEAR;
        if (!r) r = vk.vkCreateSampler(V.dev, &si, NULL, &p->samp[1]);
        if (r) { VRED("dual_rtt: descriptors/samplers failed: %d", r); p->state = -1; return 0; }
    }
    if (p->pipe_opaque) {
        vka_queue_idle();
        dk.vkDestroyPipeline(V.dev, p->pipe_opaque, NULL); p->pipe_opaque = VK_NULL_HANDLE;
        if (p->pipe_opaque_srgb) { dk.vkDestroyPipeline(V.dev, p->pipe_opaque_srgb, NULL); p->pipe_opaque_srgb = VK_NULL_HANDLE; }
        if (p->pipe_straight) { dk.vkDestroyPipeline(V.dev, p->pipe_straight, NULL); p->pipe_straight = VK_NULL_HANDLE; }
        if (p->pipe_premul)   { dk.vkDestroyPipeline(V.dev, p->pipe_premul, NULL);   p->pipe_premul   = VK_NULL_HANDLE; }
    }
    VkResult r = dp_make_pipe(p->lay, fmt, 0, &p->pipe_opaque);
    if (!r) r = dp_make_pipe(p->lay, fmt, 3, &p->pipe_opaque_srgb);
    if (!r) r = dp_make_pipe(p->lay, fmt, 1, &p->pipe_straight);
    if (!r) r = dp_make_pipe(p->lay, fmt, 2, &p->pipe_premul);
    if (r) { VRED("dual_rtt: pipeline for format %d failed: %d", fmt, r); p->state = -1; return 0; }
    p->state = 1; p->fmt = fmt;
    return 1;
}

static VkFormat dual_unorm_twin(VkFormat f) {
    switch (f) {
    case VK_FORMAT_R8G8B8A8_SRGB: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_B8G8R8A8_SRGB: return VK_FORMAT_B8G8R8A8_UNORM;
    case VK_FORMAT_A8B8G8R8_SRGB_PACK32: return VK_FORMAT_A8B8G8R8_UNORM_PACK32;
    default: return f;
    }
}

static int dual_quarter(VkSurfaceTransformFlagBitsKHR t) {
    return t == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR || t == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR;
}

static int dual_draw_pair(dual_pipe *p, VkCommandBuffer cb, VkImage dst_img, VkImageView dst_view, VkFormat fmt,
                          VkExtent2D ext, VkSurfaceTransformFlagBitsKHR pre, unsigned k,
                          vka_img *ds, vka_img *ui, int premul, const float *uv_rect) {
    if (!dp_ready(p, fmt) || !dst_view) return 0;
    static const VkComponentMapping swz_rb = {
        .r = VK_COMPONENT_SWIZZLE_B, .g = VK_COMPONENT_SWIZZLE_G, .b = VK_COMPONENT_SWIZZLE_R, .a = VK_COMPONENT_SWIZZLE_A
    };
    const VkComponentMapping *swz = vka_swap_rb() ? &swz_rb : NULL;
    VkImageView v_ds = (ds && ds->inited && (ds->usage & VK_IMAGE_USAGE_SAMPLED_BIT))
        ? vka_view(ds, dual_unorm_twin(ds->f.vk), VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1, swz) : VK_NULL_HANDLE;
    VkImageView v_ui = (ui && ui->inited && (ui->usage & VK_IMAGE_USAGE_SAMPLED_BIT))
        ? vka_view(ui, dual_unorm_twin(ui->f.vk), VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1, swz) : VK_NULL_HANDLE;
    if (!v_ds && !v_ui) return 0;

    int ds_lin = !VKA_ENV("DM_VK_PRESENT_NEAREST", 0);
    VkDescriptorImageInfo ii[2] = {
        {p->samp[ds_lin], v_ds, VK_IMAGE_LAYOUT_GENERAL},
        {p->samp[1],      v_ui, VK_IMAGE_LAYOUT_GENERAL}
    };
    VkWriteDescriptorSet wr[2]; int nw = 0;
    if (v_ds) wr[nw++] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = p->set[k][0],
        .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &ii[0]};
    if (v_ui) wr[nw++] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = p->set[k][1],
        .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &ii[1]};
    if (nw) vk.vkUpdateDescriptorSets(V.dev, (uint32_t)nw, wr, 0, NULL);

    // Logical display size (after preTransform): fit native 4:3 (1440x1080) centered
    int ew = dual_quarter(pre) ? (int)ext.height : (int)ext.width;
    int eh = dual_quarter(pre) ? (int)ext.width  : (int)ext.height;
    int fr[4]; vka_fit_rect(ew, eh, 1440, 1080, 0, 100, 5000, 5000, fr);
    int dx = fr[0], dy = fr[1], dw = fr[2], dh = fr[3];

    float W = (float)ext.width, H = (float)ext.height;
    VkViewport v = {0, 0, 0, 0, 0, 1};
    float a, b, c, d, e, f;
    switch (pre) {
    case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  v.x = W - (float)(dy + dh); v.y = (float)dx; v.width = (float)dh; v.height = (float)dw; a = 0; b = 1; e = 0; c = -1; d = 0; f = 1; break;
    case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: v.x = (float)dy; v.y = H - (float)(dx + dw); v.width = (float)dh; v.height = (float)dw; a = 0; b = -1; e = 1; c = 1; d = 0; f = 0; break;
    case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: v.x = W - (float)(dx + dw); v.y = H - (float)(dy + dh); v.width = (float)dw; v.height = (float)dh; a = -1; b = 0; e = 1; c = 0; d = -1; f = 1; break;
    default:                                      v.x = (float)dx; v.y = (float)dy; v.width = (float)dw; v.height = (float)dh; a = 1; b = 0; e = 0; c = 0; d = 1; f = 0; break;
    }
    float su = uv_rect ? uv_rect[2] : 1.0f, sv = uv_rect ? uv_rect[3] : 1.0f;
    float ou = uv_rect ? uv_rect[0] : 0.0f, ov = uv_rect ? uv_rect[1] : 0.0f;
    if (vka_origin_ll()) { ov = 1.0f - ov; sv = -sv; }
    float pc[6] = {su * a, su * b, sv * c, sv * d, ou + su * e, ov + sv * f};
    VkRect2D sc = {{0, 0}, ext};

    vka_barrier(cb);
    vka_img_barrier(cb, dst_img, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    VkRenderingAttachmentInfo ca = {VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO, .imageView = dst_view,
        .imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE, .clearValue = {.color = {{0, 0, 0, 1}}}};
    VkRenderingInfo ri = {VK_STRUCTURE_TYPE_RENDERING_INFO, .renderArea = {{0, 0}, ext}, .layerCount = 1,
        .colorAttachmentCount = 1, .pColorAttachments = &ca};
    vk.vkCmdBeginRendering(cb, &ri);
    dk.vkCmdPushConstants(cb, p->lay, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof pc, pc);
    dk.vkCmdSetViewport(cb, 0, 1, &v);
    dk.vkCmdSetScissor(cb, 0, 1, &sc);
    if (v_ds) {
        int srgb_bg = ds && ds->w == 1440 && ds->h == 1080 && VKA_ENV("PN_DUAL_3D_SRGB", 1);
        dk.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, srgb_bg ? p->pipe_opaque_srgb : p->pipe_opaque);
        dk.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, p->lay, 0, 1, &p->set[k][0], 0, NULL);
        dk.vkCmdDraw(cb, 3, 1, 0, 0);
    }
    if (v_ui) {
        dk.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, v_ds ? (premul ? p->pipe_premul : p->pipe_straight) : p->pipe_opaque);
        dk.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, p->lay, 0, 1, &p->set[k][1], 0, NULL);
        dk.vkCmdDraw(cb, 3, 1, 0, 0);
    }
    vk.vkCmdEndRendering(cb);
    vka_img_barrier(cb, dst_img, VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    return 1;
}

int pn_dual_draw_top(VkCommandBuffer cb, VkImage dst_img, VkImageView dst_view, VkFormat fmt,
                     VkExtent2D ext, VkSurfaceTransformFlagBitsKHR pre, unsigned k) {
    if (k >= DUAL_NFRAME || !g_slot[k].active) return 0;
    if (!g_slot[k].swapped && g_slot[k].top_from_comp && g_slot[k].comp_img) {
        return dual_draw_pair(&g_dp_top, cb, dst_img, dst_view, fmt, ext, pre, k,
                              g_slot[k].comp_img, NULL, 0, g_slot[k].top_uv);
    }
    vka_img *ds = g_slot[k].swapped ? g_slot[k].sub_ds : g_slot[k].main_ds;
    vka_img *ui = g_slot[k].swapped ? g_slot[k].sub_ui : g_slot[k].main_ui;
    return dual_draw_pair(&g_dp_top, cb, dst_img, dst_view, fmt, ext, pre, k,
                          ds, ui, g_slot[k].premul, NULL);
}

// ---------- Second Vulkan swapchain (companion display) ----------
static pthread_mutex_t g_aux_mu = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *g_awin;
static ANativeWindow *g_awin_pending;
static int g_awin_dirty;
static VkSurfaceKHR g_asurf;
static VkSwapchainKHR g_aswap;
static VkFormat g_aswap_fmt;
static VkExtent2D g_aext;
static VkSurfaceTransformFlagBitsKHR g_apre = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
static VkImage g_asimg[DUAL_MAXIMG];
static VkImageView g_asview[DUAL_MAXIMG];
static VkSemaphore g_asdone[DUAL_MAXIMG];
static uint32_t g_animg;
static VkCommandPool g_apool;
static unsigned g_afi;
static int g_arecreate;
static struct {
    VkCommandBuffer cb;
    VkFence fence;
    VkSemaphore acq;
    vka_img *sub_ds, *sub_ui;
    int used;
} g_afr[DUAL_NFRAME];

static void aux_wait_all(void) {
    for (unsigned i = 0; i < DUAL_NFRAME; i++) {
        if (g_afr[i].used && g_afr[i].fence && !vka_hung())
            vk.vkWaitForFences(V.dev, 1, &g_afr[i].fence, VK_TRUE, 2000000000ULL);
        g_afr[i].used = 0;
        if (g_afr[i].sub_ds) { vka_tex_drop(g_afr[i].sub_ds); g_afr[i].sub_ds = NULL; }
        if (g_afr[i].sub_ui) { vka_tex_drop(g_afr[i].sub_ui); g_afr[i].sub_ui = NULL; }
    }
}

static void aux_swap_destroy(int surface_too) {
    if (!V.dev) return;
    if (g_aswap || (surface_too && g_asurf)) aux_wait_all();
    for (uint32_t i = 0; i < g_animg; i++) {
        if (g_asdone[i]) { vk.vkDestroySemaphore(V.dev, g_asdone[i], NULL); g_asdone[i] = VK_NULL_HANDLE; }
        if (g_asview[i]) { vk.vkDestroyImageView(V.dev, g_asview[i], NULL); g_asview[i] = VK_NULL_HANDLE; }
    }
    g_animg = 0;
    if (g_aswap) { vk.vkDestroySwapchainKHR(V.dev, g_aswap, NULL); g_aswap = VK_NULL_HANDLE; }
    if (surface_too && g_asurf) { vk.vkDestroySurfaceKHR(V.inst, g_asurf, NULL); g_asurf = VK_NULL_HANDLE; }
}

static int aux_swap_create(void) {
    if (!g_asurf || !g_awin) return 0;
    VkSurfaceCapabilitiesKHR caps;
    VkResult r = vk.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(V.pd, g_asurf, &caps);
    if (r) { VRED("dual_rtt: aux surface caps %d", r); return 0; }
    uint32_t nf = 0; vk.vkGetPhysicalDeviceSurfaceFormatsKHR(V.pd, g_asurf, &nf, NULL);
    VkSurfaceFormatKHR fs[64]; if (nf > 64) nf = 64;
    vk.vkGetPhysicalDeviceSurfaceFormatsKHR(V.pd, g_asurf, &nf, fs);
    VkSurfaceFormatKHR pick = nf ? fs[0] : (VkSurfaceFormatKHR){VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    for (int pass = 0; pass < 2; pass++) for (uint32_t i = 0; i < nf; i++)
        if (fs[i].format == (pass ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8G8B8A8_UNORM) &&
            fs[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { pick = fs[i]; pass = 2; break; }
    VkSurfaceTransformFlagBitsKHR pre = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    if ((caps.currentTransform & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)) &&
        (caps.supportedTransforms & caps.currentTransform) && (caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) &&
        dp_ready(&g_dp_aux, pick.format))
        pre = caps.currentTransform;
    int ww = ANativeWindow_getWidth(g_awin), wh = ANativeWindow_getHeight(g_awin);
    VkExtent2D ext = caps.currentExtent;
    if (ww > 0 && wh > 0) ext = dual_quarter(pre) ? (VkExtent2D){(uint32_t)wh, (uint32_t)ww} : (VkExtent2D){(uint32_t)ww, (uint32_t)wh};
    if (!ext.width || !ext.height) return 0;
    uint32_t n = caps.minImageCount < 3 ? 3 : caps.minImageCount;
    if (caps.maxImageCount && n > caps.maxImageCount) n = caps.maxImageCount;
    if (n > DUAL_MAXIMG) n = DUAL_MAXIMG;
    VkCompositeAlphaFlagBitsKHR ca = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR :
        (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR :
        (VkCompositeAlphaFlagBitsKHR)(caps.supportedCompositeAlpha & -caps.supportedCompositeAlpha);
    VkSwapchainCreateInfoKHR ci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = g_asurf, .minImageCount = n,
        .imageFormat = pick.format, .imageColorSpace = pick.colorSpace, .imageExtent = ext, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE, .preTransform = pre, .compositeAlpha = ca,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE, .oldSwapchain = g_aswap};
    if (g_aswap) aux_wait_all();
    VkSwapchainKHR sw = VK_NULL_HANDLE;
    r = vk.vkCreateSwapchainKHR(V.dev, &ci, NULL, &sw);
    if (g_aswap) {
        for (uint32_t i = 0; i < g_animg; i++) {
            if (g_asdone[i]) { vk.vkDestroySemaphore(V.dev, g_asdone[i], NULL); g_asdone[i] = VK_NULL_HANDLE; }
            if (g_asview[i]) { vk.vkDestroyImageView(V.dev, g_asview[i], NULL); g_asview[i] = VK_NULL_HANDLE; }
        }
        g_animg = 0;
        vk.vkDestroySwapchainKHR(V.dev, g_aswap, NULL);
        g_aswap = VK_NULL_HANDLE;
    }
    if (r) { VRED("dual_rtt: aux swapchain %ux%u fmt %d failed: %d", ext.width, ext.height, pick.format, r); return 0; }
    g_aswap = sw; g_aswap_fmt = pick.format; g_aext = ext; g_apre = pre; g_arecreate = 0;
    g_animg = DUAL_MAXIMG;
    vk.vkGetSwapchainImagesKHR(V.dev, sw, &g_animg, g_asimg);
    VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (uint32_t i = 0; i < g_animg; i++) {
        vk.vkCreateSemaphore(V.dev, &sci, NULL, &g_asdone[i]);
        VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = g_asimg[i], .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = pick.format, .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vk.vkCreateImageView(V.dev, &vi, NULL, &g_asview[i]);
    }
    VLOG("dual_rtt: aux swapchain %ux%u, %u images, format %d, transform %#x (surface %#x)",
         ext.width, ext.height, g_animg, pick.format, pre, caps.currentTransform);
    return 1;
}

static void aux_apply_window_locked(void) {
    if (!g_awin_dirty) return;
    ANativeWindow *w = g_awin_pending; g_awin_pending = NULL; g_awin_dirty = 0;
    aux_swap_destroy(1);
    if (g_awin) { ANativeWindow_release(g_awin); g_awin = NULL; }
    g_awin = w;
    if (w && V.dev && V.has_android_surface) {
        VkAndroidSurfaceCreateInfoKHR si = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, .window = w};
        VkResult r = vk.vkCreateAndroidSurfaceKHR(V.inst, &si, NULL, &g_asurf);
        VkBool32 ok = VK_FALSE;
        if (!r) vk.vkGetPhysicalDeviceSurfaceSupportKHR(V.pd, V.qf, g_asurf, &ok);
        if (r || !ok) {
            VRED("dual_rtt: aux surface %p failed: %d (present %d)", (void *)w, r, ok);
            if (!r) vk.vkDestroySurfaceKHR(V.inst, g_asurf, NULL);
            g_asurf = VK_NULL_HANDLE;
        } else {
            aux_swap_create();
        }
    }
}

void nxhost_peanuts_aux_window(ANativeWindow *w) {
    if (w) ANativeWindow_acquire(w);
    atomic_store_explicit(&g_aux_attached, w != NULL ? 1 : 0, memory_order_release);
    pthread_mutex_lock(&g_aux_mu);
    if (!w) {
        // Synchronous teardown on surfaceDestroyed so the Android surface is never referenced after return.
        if (g_awin_pending) { ANativeWindow_release(g_awin_pending); g_awin_pending = NULL; }
        g_awin_dirty = 0;
        aux_swap_destroy(1);
        if (g_awin) { ANativeWindow_release(g_awin); g_awin = NULL; }
    } else {
        if (g_awin_pending) ANativeWindow_release(g_awin_pending);
        g_awin_pending = w;
        g_awin_dirty = 1;
    }
    pthread_mutex_unlock(&g_aux_mu);
    VLOG("dual_rtt: aux window %p", (void *)w);
}

void pn_dual_present_aux(uint64_t wait_fence, unsigned main_k) {
    if (!pn_dual_wanted() || vka_hung() || !V.dev) return;
    pthread_mutex_lock(&g_aux_mu);
    aux_apply_window_locked();
    if (!g_asurf) { pthread_mutex_unlock(&g_aux_mu); return; }
    if (!g_aswap || g_arecreate) {
        if (g_aswap) aux_swap_destroy(0);
        if (!aux_swap_create()) { pthread_mutex_unlock(&g_aux_mu); return; }
    }
    unsigned ak = g_afi++ % DUAL_NFRAME;
    if (!g_afr[ak].cb) {
        if (!g_apool) {
            VkCommandPoolCreateInfo pi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, .queueFamilyIndex = V.qf};
            vk.vkCreateCommandPool(V.dev, &pi, NULL, &g_apool);
        }
        VkCommandBufferAllocateInfo ai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .commandPool = g_apool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
        vk.vkAllocateCommandBuffers(V.dev, &ai, &g_afr[ak].cb);
        VkFenceCreateInfo fi = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        vk.vkCreateFence(V.dev, &fi, NULL, &g_afr[ak].fence);
        VkSemaphoreCreateInfo sci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        vk.vkCreateSemaphore(V.dev, &sci, NULL, &g_afr[ak].acq);
    }
    if (g_afr[ak].used) {
        if (vk.vkWaitForFences(V.dev, 1, &g_afr[ak].fence, VK_TRUE, 50000000ULL) != VK_SUCCESS) {
            pthread_mutex_unlock(&g_aux_mu);
            return;
        }
        g_afr[ak].used = 0;
        if (g_afr[ak].sub_ds) { vka_tex_drop(g_afr[ak].sub_ds); g_afr[ak].sub_ds = NULL; }
        if (g_afr[ak].sub_ui) { vka_tex_drop(g_afr[ak].sub_ui); g_afr[ak].sub_ui = NULL; }
    }
    uint32_t idx = 0;
    VkResult ar = vk.vkAcquireNextImageKHR(V.dev, g_aswap, 50000000ULL, g_afr[ak].acq, VK_NULL_HANDLE, &idx);
    if (ar == VK_ERROR_OUT_OF_DATE_KHR) { g_arecreate = 1; pthread_mutex_unlock(&g_aux_mu); return; }
    if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) { pthread_mutex_unlock(&g_aux_mu); return; }

    vk.vkResetFences(V.dev, 1, &g_afr[ak].fence);
    VkCommandBuffer cb = g_afr[ak].cb;
    vk.vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    vk.vkBeginCommandBuffer(cb, &bi);

    int active = (main_k < DUAL_NFRAME) && g_slot[main_k].active;
    int drew = 0;
    vka_img *s_ds = NULL, *s_ui = NULL;
    if (active) {
        if (g_slot[main_k].swapped && g_slot[main_k].top_from_comp && g_slot[main_k].comp_img) {
            s_ds = vka_tex_ref(g_slot[main_k].comp_img);
            s_ui = NULL;
            drew = dual_draw_pair(&g_dp_aux, cb, g_asimg[idx], g_asview[idx], g_aswap_fmt, g_aext, g_apre, ak,
                                  s_ds, NULL, 0, g_slot[main_k].top_uv);
        } else {
            vka_img *bds = g_slot[main_k].swapped ? g_slot[main_k].main_ds : g_slot[main_k].sub_ds;
            vka_img *bui = g_slot[main_k].swapped ? g_slot[main_k].main_ui : g_slot[main_k].sub_ui;
            s_ds = bds ? vka_tex_ref(bds) : NULL;
            s_ui = bui ? vka_tex_ref(bui) : NULL;
            drew = dual_draw_pair(&g_dp_aux, cb, g_asimg[idx], g_asview[idx], g_aswap_fmt, g_aext, g_apre, ak,
                                  s_ds, s_ui, g_slot[main_k].premul, NULL);
        }
    }
    if (!drew) {
        if (s_ds) { vka_tex_drop(s_ds); s_ds = NULL; }
        if (s_ui) { vka_tex_drop(s_ui); s_ui = NULL; }
        vka_img_barrier(cb, g_asimg[idx], VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkClearColorValue black = {{0, 0, 0, 1}};
        VkImageSubresourceRange rg = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vk.vkCmdClearColorImage(cb, g_asimg[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &rg);
        vka_img_barrier(cb, g_asimg[idx], VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    }
    vk.vkEndCommandBuffer(cb);

    int use_tl = drew && wait_fence != 0;
    VkSemaphore ws[2] = {g_afr[ak].acq, V.tl};
    uint64_t wv[2] = {0, wait_fence};
    VkPipelineStageFlags st[2] = {VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT};
    VkTimelineSemaphoreSubmitInfo ti = {VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
        .waitSemaphoreValueCount = use_tl ? 2u : 1u, .pWaitSemaphoreValues = wv,
        .signalSemaphoreValueCount = 1, .pSignalSemaphoreValues = wv};
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, .pNext = &ti,
        .waitSemaphoreCount = use_tl ? 2u : 1u, .pWaitSemaphores = ws, .pWaitDstStageMask = st,
        .commandBufferCount = 1, .pCommandBuffers = &cb,
        .signalSemaphoreCount = 1, .pSignalSemaphores = &g_asdone[idx]};

    pthread_mutex_lock(&V.qmu);
    VkResult sr = vk.vkQueueSubmit(V.q, 1, &si, g_afr[ak].fence);
    VkResult pr = VK_SUCCESS;
    if (!sr) {
        VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = 1,
            .pWaitSemaphores = &g_asdone[idx], .swapchainCount = 1, .pSwapchains = &g_aswap, .pImageIndices = &idx};
        pr = vk.vkQueuePresentKHR(V.q, &pi);
    }
    pthread_mutex_unlock(&V.qmu);

    if (!sr) {
        g_afr[ak].used = 1;
        g_afr[ak].sub_ds = s_ds;
        g_afr[ak].sub_ui = s_ui;
    } else {
        if (s_ds) vka_tex_drop(s_ds);
        if (s_ui) vka_tex_drop(s_ui);
        static _Atomic int nerr;
        if (atomic_fetch_add(&nerr, 1) < 6) VRED("dual_rtt: aux submit failed: %d", sr);
    }
    if (pr == VK_ERROR_OUT_OF_DATE_KHR) g_arecreate = 1;
    else if (pr == VK_SUBOPTIMAL_KHR && g_awin) {
        int ww = ANativeWindow_getWidth(g_awin), wh = ANativeWindow_getHeight(g_awin);
        int ew = dual_quarter(g_apre) ? (int)g_aext.height : (int)g_aext.width;
        int eh = dual_quarter(g_apre) ? (int)g_aext.width  : (int)g_aext.height;
        if (ww > 0 && wh > 0 && (ww != ew || wh != eh)) g_arecreate = 1;
    }
    pthread_mutex_unlock(&g_aux_mu);
}

// ---------- Bottom-display touch forwarding ----------
void nxhost_peanuts_dual_touch(int id, float u, float v, int down) {
    static struct { int down; float x, y; } tf[16];
    static pthread_mutex_t tmu = PTHREAD_MUTEX_INITIALIZER;
    if (id < 0 || id >= 16) return;
    int sov = VKA_ENV("PN_DUAL_SWAP", -1);
    int swapped = sov >= 0 ? (sov != 0) : atomic_load_explicit(&g_swap_toggle, memory_order_relaxed);
    if (swapped && down) return;   // Top screen is on Bottom display while swapped; don't inject DS bottom-screen taps
    u = u < 0 ? 0 : u > 0.9999f ? 0.9999f : u;
    v = v < 0 ? 0 : v > 0.9999f ? 0.9999f : v;
    // Normalized rect where the game expects bottom-screen touches inside its 1920x1080 canvas.
    // Default matches Left/Right layout (0,0,470,355 -> 0,0,0.2448,0.3287); override live with PN_DUAL_TOUCH_RECT=x,y,w,h.
    float rx = 0.0f, ry = 0.0f, rw = 470.0f / 1920.0f, rh = 355.0f / 1080.0f;
    char b[64] = {0};
    if (vka_env_str("PN_DUAL_TOUCH_RECT", b, sizeof b) && b[0]) {
        float px, py, pw, ph;
        if (sscanf(b, "%f,%f,%f,%f", &px, &py, &pw, &ph) == 4 && pw > 0 && ph > 0) {
            if (pw > 1.5f || ph > 1.5f) { rx = px / 1920.0f; ry = py / 1080.0f; rw = pw / 1920.0f; rh = ph / 1080.0f; }
            else { rx = px; ry = py; rw = pw; rh = ph; }
        }
    }
    float nx = rx + u * rw, ny = ry + v * rh;
    dm_touch t[16]; int n = 0;
    pthread_mutex_lock(&tmu);
    tf[id].down = down != 0; tf[id].x = nx; tf[id].y = ny;
    for (int i = 0; i < 16; i++) if (tf[i].down) { t[n].id = (uint32_t)i; t[n].x = tf[i].x; t[n].y = tf[i].y; n++; }
    pthread_mutex_unlock(&tmu);
    dm_touch_set(n ? t : NULL, n);
}

int nxhost_peanuts_dual_wanted(void) { return pn_dual_wanted(); }

#else
#include "peanuts_dual.h"
int pn_dual_wanted(void) { return 0; }
void pn_dual_pad_note(uint64_t raw_b) { (void)raw_b; }
uint64_t pn_dual_pad_mask(uint64_t b) { return b; }
int nxhost_peanuts_dual_wanted(void) { return 0; }
void nxhost_peanuts_aux_window(struct ANativeWindow *w) { (void)w; }
void nxhost_peanuts_dual_touch(int id, float u, float v, int down) { (void)id; (void)u; (void)v; (void)down; }
#endif
