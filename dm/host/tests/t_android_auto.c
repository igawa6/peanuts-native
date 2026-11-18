// Automatic defaults by driver (dm/gfx/vk_android/vka_auto.h): which switches Auto sets on Qualcomm's driver, an old Qualcomm driver and Turnip, that the profile- and
// feature-dependent ones need their condition, and the "[auto]" log line's form
#include <stdio.h>
#include <string.h>
#include "../../gfx/vk_android/vka_auto.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const char *val(const vka_auto_rule *r, int n, const char *key) {
    for (int i = 0; i < n; i++) if (!strcmp(r[i].key, key)) return r[i].val;
    return NULL;
}

int main(void) {
    vka_auto_rule r[16]; int n;
    const char *v;

    // Turnip: the Turnip rules only
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_TURNIP, .mesh_ext = 1, .vin_dyn_ext = 1, .prof_one_lane = 1, .prof_prepass_cut = 1, .prof_oq_split = 1}, r, 16);
    CHECK((v = val(r, n, "DM_VK_PSO_SLOTS")) && !strcmp(v, "1"), "Turnip: one compile slot");
    CHECK((v = val(r, n, "DM_VK_PRESENT_GATE_WAIT")) && !strcmp(v, "30"), "Turnip: 30 ms present gate wait");
    CHECK((v = val(r, n, "DM_VK_ZSHARE_MAX_MB")) && !strcmp(v, "0"), "Turnip: no color zero-share limit");
    CHECK((v = val(r, n, "DM_VK_FSR3_WAVE")) && !strcmp(v, "1"), "Turnip: FSR 3.1 quad shuffles");
    CHECK((v = val(r, n, "DM_VK_SUBGROUP_GS")) && !strcmp(v, "0"), "Turnip: one-lane geometry subgroups");
    CHECK((v = val(r, n, "DM_VK_SHUFFLE_LDS")) && !strcmp(v, "1"), "Turnip: shuffles through workgroup memory");
    CHECK(!val(r, n, "DM_VK_MESH") && !val(r, n, "DM_VK_GS_FILL") && !val(r, n, "DM_VK_ONE_LANE") && !val(r, n, "DM_VK_OQ_SPLIT_PASS") && !val(r, n, "DM_VK_PREWARM"),
          "Turnip: no Qualcomm rule");
    CHECK(n == 6, "Turnip: 6 rules, got %d", n);

    // Qualcomm's driver (512.800 or later), mesh draws through compute, profile with every key
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .mesh_ext = 1, .memu = 1, .vin_dyn_ext = 1, .prof_one_lane = 2, .prof_prepass_cut = 3, .prof_oq_split = 1}, r, 16);
    CHECK((v = val(r, n, "DM_VK_MESH")) && !strcmp(v, "0"), "Qualcomm: mesh extension off");
    CHECK((v = val(r, n, "DM_VK_GS_FILL")) && !strcmp(v, "1"), "Qualcomm: GS fill");
    CHECK((v = val(r, n, "DM_VK_ONE_LANE")) && !strcmp(v, "1"), "Qualcomm: profile's one-lane shaders");
    CHECK((v = val(r, n, "DM_VK_OQ_SPLIT_PASS")) && !strcmp(v, "1"), "Qualcomm: profile's occlusion split pass");
    CHECK(!val(r, n, "DM_VK_MESH_PREPASS_SKIP"), "Qualcomm through compute: the prepass cut does not apply");
    CHECK(!val(r, n, "DM_VK_PREWARM") && !val(r, n, "DM_VK_VIN_DYN") && !val(r, n, "DM_VK_PSO_SLOTS"), "Qualcomm 512.800+: no old-driver or Turnip rule");
    // ... with its mesh shaders (DM_VK_MESH=1): the prepass cut is on
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .mesh_ext = 1, .prof_prepass_cut = 3}, r, 16);
    CHECK((v = val(r, n, "DM_VK_MESH_PREPASS_SKIP")) && !strcmp(v, "1"), "Qualcomm with mesh shaders: prepass cut");
    // ... a profile without those keys and a device without the mesh extension: only GS fill
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .memu = 1}, r, 16);
    CHECK(n == 1 && !strcmp(r[0].key, "DM_VK_GS_FILL"), "Qualcomm, no mesh extension, empty profile: GS fill only (%d)", n);

    // Qualcomm before 512.800: prewarm off, vertex input in the pipeline (only where the device has the dynamic state)
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .qcom_old = 1, .vin_dyn_ext = 1, .memu = 1}, r, 16);
    CHECK((v = val(r, n, "DM_VK_PREWARM")) && !strcmp(v, "0"), "old Qualcomm: no prewarm");
    CHECK((v = val(r, n, "DM_VK_VIN_DYN")) && !strcmp(v, "0"), "old Qualcomm: vertex input dynamic state off");
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .qcom_old = 1, .memu = 1}, r, 16);
    CHECK(!val(r, n, "DM_VK_VIN_DYN"), "old Qualcomm without the extension: nothing to turn off");

    // Another driver (Mali, 9): nothing; the cap on the output is kept
    CHECK(vka_auto_rules(&(vka_auto_in){.driver_id = 9, .mesh_ext = 1, .vin_dyn_ext = 1, .prof_one_lane = 1, .prof_prepass_cut = 1, .prof_oq_split = 1}, r, 16) == 0, "other driver: no rule");
    CHECK(vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_TURNIP}, r, 2) == 2, "at most max rules");

    // The line
    char b[256];
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_TURNIP}, r, 16);
    vka_auto_line(b, sizeof b, &r[0], VKA_DRV_TURNIP, "turnip Mesa driver");
    CHECK(!strcmp(b, "[auto] DM_VK_PSO_SLOTS=1 because driver_id=18 (turnip Mesa driver)"), "line: %s", b);
    n = vka_auto_rules(&(vka_auto_in){.driver_id = VKA_DRV_QCOM, .qcom_old = 1, .memu = 1}, r, 16);
    vka_auto_line(b, sizeof b, &r[0], VKA_DRV_QCOM, "");
    CHECK(!strcmp(b, "[auto] DM_VK_PREWARM=0 because driver_id=8 (?), driver version before 512.800"), "line with a reason, no name: %s", b);

    printf("t_android_auto: %s\n", fails ? "FAILED" : "ok");
    return fails != 0;
}
