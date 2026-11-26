// Automatic defaults by driver: the DM_VK_* switches whose unset value depends on what the driver reports (driverID, its version, features), never on its name.
// Each call site keeps its own default expression (VKA_ENV(..., V.driver_id == ...)); this table restates those that differ from the general default, so that
// vka_dev.c can print one line per switch that Auto set at device creation:
//   [auto] KEY=value because driver_id=<n> (<driver name as reported>)[, <what else it keys on>]
// The switch is left out when it is set (env.txt, the launcher's Advanced page, Extra environment). A digest or an issue log then shows which defaults this driver took.
// dm/tools/tests/test_auto_defaults.py checks that every VKA_ENV / getenv default in dm/gfx/vk_android that reads driver_id or qcom_old has a rule here and that its
// docs/advanced_settings.tsv row says so in its `default` text. android/README.md "Custom GPU drivers" (Per-driver defaults) lists them.
// Header only, no Vulkan headers: dm/host/tests/t_android_auto.c tests it.
#ifndef VKA_AUTO_H
#define VKA_AUTO_H
#include <stdio.h>
#include <string.h>
#include "vka_psogate.h"

enum { VKA_DRV_QCOM = 8, VKA_DRV_TURNIP = 18 };   // VK_DRIVER_ID_QUALCOMM_PROPRIETARY, VK_DRIVER_ID_MESA_TURNIP (vka_dev.c checks them against the Vulkan header)

typedef struct {
    int driver_id;          // VkPhysicalDeviceDriverProperties.driverID
    int qcom_old;           // Qualcomm's own driver before 512.800 (V.qcom_old)
    int mesh_ext;           // the device lists VK_EXT_mesh_shader
    int memu;               // NVN2 mesh draws through compute (V.memu)
    int vin_dyn_ext;        // the device has vertex input dynamic state (before DM_VK_VIN_DYN)
    int prof_one_lane;      // the profile's subgroup_one_lane keys (DM_GAME_NSUBGROUP_ONE_LANE)
    int prof_prepass_cut;   // the profile's mesh_prepass_cut rows (DM_GAME_NMESH_PREPASS_CUT)
    int prof_oq_split;      // the profile's occlusion_split_pass (DM_GAME_OCCLUSION_SPLIT_PASS)
} vka_auto_in;

typedef struct { const char *key, *why; char val[8]; } vka_auto_rule;   // why: NULL or what else the rule keys on, besides the driver ID

// The rules that hold for in, in a fixed order; returns how many (at most max)
static inline int vka_auto_rules(const vka_auto_in *in, vka_auto_rule *o, int max) {
    int n = 0, q = in->driver_id == VKA_DRV_QCOM, t = in->driver_id == VKA_DRV_TURNIP;
#define VKA_AUTO_ADD(c, k, v, w) do { if ((c) && n < max) { o[n].key = (k); o[n].why = (w); snprintf(o[n].val, sizeof o[n].val, "%d", (int)(v)); n++; } } while (0)
    VKA_AUTO_ADD(q && in->mesh_ext, "DM_VK_MESH", 0, "mesh draws through compute");                          // vka_dev.c mesh_qcom
    VKA_AUTO_ADD(in->qcom_old && in->vin_dyn_ext, "DM_VK_VIN_DYN", 0, "driver version before 512.800");     // vka_dev.c
    VKA_AUTO_ADD(in->qcom_old, "DM_VK_PREWARM", 0, "driver version before 512.800");                         // vka_pso.c prewarm
    VKA_AUTO_ADD(t, "DM_VK_PSO_SLOTS", vkpg_cap_default(1), NULL);                                           // vka_pso.c gate_cap
    VKA_AUTO_ADD(t, "DM_VK_PRESENT_GATE_WAIT", 30, NULL);                                                    // vka_present.c
    VKA_AUTO_ADD(t, "DM_VK_ZSHARE_MAX_MB", 0, NULL);                                                         // vka_tex.c, vka_zshare.h vkz_max
    VKA_AUTO_ADD(t, "DM_VK_FSR3_WAVE", 1, NULL);                                                             // vka_fsr3.c
    VKA_AUTO_ADD(t, "DM_VK_SUBGROUP_GS", 0, NULL);                                                           // vka_shader.c sg_plan
    VKA_AUTO_ADD(t, "DM_VK_SHUFFLE_LDS", 1, NULL);                                                           // vka_shader.c shuffle_plan
    VKA_AUTO_ADD(t, "DM_VK_QUAD_SHFL", 1, NULL);                                                             // vka_shader.c quad shuffles (PEANUTS)
    VKA_AUTO_ADD(t, "DM_VK_CS_WARM", 1, NULL);                                                               // vka_pso.c cw_start (PEANUTS)
    VKA_AUTO_ADD(q && in->prof_one_lane, "DM_VK_ONE_LANE", 1, "the profile's subgroup_one_lane");            // vka_shader.c one_lane_env
    VKA_AUTO_ADD(q, "DM_VK_DYN_PASS", 1, "dynamic state lost at pass begins and binds");                     // vka_draw.c dyn_pass_begin (PEANUTS)
    VKA_AUTO_ADD(q, "DM_VK_DEPTH_ONLY_BLEND", 1, "pixel shader inputs lost in draws that write no colour");   // vka_pso.c pstate_fill, vka_zonly.h (PEANUTS)
    VKA_AUTO_ADD(q, "DM_VK_GS_FILL", 1, NULL);                                                               // vka_shader.c gs_fill
    VKA_AUTO_ADD(q && !in->memu && in->prof_prepass_cut, "DM_VK_MESH_PREPASS_SKIP", 1, "the profile's mesh_prepass_cut, mesh shaders on");   // vka_draw.c mesh_cut
    VKA_AUTO_ADD(q && in->prof_oq_split, "DM_VK_OQ_SPLIT_PASS", 1, "the profile's occlusion_split_pass");   // vka_exec.c oq_report
#undef VKA_AUTO_ADD
    return n;
}

// The log line for one rule (no prefix, no newline); name is the driver's name as reported, printed only
static inline void vka_auto_line(char *b, size_t n, const vka_auto_rule *r, int driver_id, const char *name) {
    snprintf(b, n, "[auto] %s=%s because driver_id=%d (%s)%s%s", r->key, r->val, driver_id, name && *name ? name : "?", r->why ? ", " : "", r->why ? r->why : "");
}
#endif
