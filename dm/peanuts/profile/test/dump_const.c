// PEANUTS: equivalence test, PC side A (peanuts/scripts/check-runtime-profile.sh). Built once per title against that title's compile-time
// game_profile.h (dm/games/game.py header <id>), the exact header every per-title Android host compiles today. Prints every DM_GAME_* field in a
// fixed text form; dump_runtime.c (built once, not per title) prints the same fields read from the runtime profile.bin the generic host would load.
// A byte-for-byte diff of the two outputs, run over every dm/games/* profile, is the "for all 131 profiles, compare field by field" equivalence gate.
#include "game_profile.h"
#include <stdio.h>
#include <string.h>

int main(void) {
    printf("id=%s\n", DM_GAME_ID);
    printf("name=%s\n", DM_GAME_NAME);
    printf("program_id=%llu\n", (unsigned long long)DM_GAME_PROGRAM_ID);
    printf("nmod=%d\n", (int)DM_GAME_NMOD);
    { static const char *mods[] = DM_GAME_MODULES; for (int i = 0; i < DM_GAME_NMOD; i++) printf("modules[%d]=%s\n", i, mods[i]); }
    printf("romfs_marker=%s\n", DM_GAME_ROMFS_MARKER);
    printf("mem_reserve=%llu\n", (unsigned long long)DM_GAME_MEM_RESERVE);
    printf("mem_alias=%llu\n", (unsigned long long)DM_GAME_MEM_ALIAS);
    printf("mem_stack=%llu\n", (unsigned long long)DM_GAME_MEM_STACK);
    printf("app_pool=%llu\n", (unsigned long long)DM_GAME_APP_POOL);
    printf("mem_used=%llu\n", (unsigned long long)DM_GAME_MEM_USED);
    printf("sys_resource=%llu\n", (unsigned long long)DM_GAME_SYS_RESOURCE);
    printf("args=%s\n", DM_GAME_ARGS);
    printf("env=%s\n", DM_GAME_ENV);
    printf("fastboot_env=%s\n", DM_GAME_FASTBOOT_ENV);
    printf("devtools=%s\n", DM_GAME_DEVTOOLS);
    printf("mem_map_limit=%d\n", (int)DM_GAME_MEM_MAP_LIMIT);
    printf("stdalloc_cap=%llu\n", (unsigned long long)DM_GAME_STDALLOC_CAP);
    { static const struct { const char *build, *ver; } dv[] = {DM_GAME_DISPLAY_VERSIONS};   // real call sites wrap it in braces (hle_sdk16.c:16, hle_cheats.c:1719); no NDISPLAY_VERSIONS counterpart, sizeof is the count
      int n = (int)(sizeof(dv) / sizeof(dv[0]));
      for (int i = 0; i < n; i++) printf("display_versions[%d]=%s,%s\n", i, dv[i].build, dv[i].ver); }
    printf("present_display=%d\n", (int)DM_GAME_PRESENT_DISPLAY);
    printf("core_preempt=%d\n", (int)DM_GAME_CORE_PREEMPT);
    printf("alloc_big_align=%d\n", (int)DM_GAME_ALLOC_BIG_ALIGN);
    printf("fps_target=%d\n", (int)DM_GAME_FPS_TARGET);
    printf("fps_hold=%d\n", (int)DM_GAME_FPS_HOLD);
    printf("fps_limit=%d\n", (int)DM_GAME_FPS_LIMIT);
    printf("ue_texture_pool=%d\n", (int)DM_GAME_UE_TEXTURE_POOL);
    printf("ue_fps_cvar=%s\n", DM_GAME_UE_FPS_CVAR);
    printf("present_snap=%d\n", (int)DM_GAME_PRESENT_SNAP);
    printf("pso_mtl4=%d\n", (int)DM_GAME_PSO_MTL4);
    printf("enc_thread=%d\n", (int)DM_GAME_ENC_THREAD);
#ifdef DM_GAME_SHARED_FONT_TTF
    printf("has_shared_font_ttf=1\n"); printf("shared_font_ttf=%d\n", (int)DM_GAME_SHARED_FONT_TTF);
#else
    printf("has_shared_font_ttf=0\n"); printf("shared_font_ttf=0\n");
#endif
    printf("pad_layout=%d\n", (int)DM_GAME_PAD_LAYOUT);
    printf("opmode=%d\n", (int)DM_GAME_OPMODE);
    printf("perf_boost=%d\n", (int)DM_GAME_PERF_BOOST);
    printf("tex_flush_upload=%d\n", (int)DM_GAME_TEX_FLUSH_UPLOAD);
    printf("shader_key_skip_off=%llu\n", (unsigned long long)DM_GAME_SHADER_KEY_SKIP_OFF);
    printf("shader_key_skip_len=%llu\n", (unsigned long long)DM_GAME_SHADER_KEY_SKIP_LEN);
    printf("render_cond=%d\n", (int)DM_GAME_RENDER_COND);
    printf("tex_dirty_upload=%d\n", (int)DM_GAME_TEX_DIRTY_UPLOAD);
    printf("tex_dyn_slots=%d\n", (int)DM_GAME_TEX_DYN_SLOTS);
    printf("tex_soft=%d\n", (int)DM_GAME_TEX_SOFT);
    printf("gpu_ts_segment=%d\n", (int)DM_GAME_GPU_TS_SEGMENT);
    printf("draw_barriers_game=%d\n", (int)DM_GAME_DRAW_BARRIERS_GAME);
    printf("vconv_range=%d\n", (int)DM_GAME_VCONV_RANGE);
    printf("av1_dav1d=%d\n", (int)DM_GAME_AV1_DAV1D);
    printf("romfs_subst=%s\n", DM_GAME_ROMFS_SUBST);
    printf("nwindow_fn=%d\n", (int)DM_GAME_NWINDOW_FN);
    { static const struct { const char *build; unsigned long long wfn, hfn; unsigned w, h; } wf[] = DM_GAME_WINDOW_FN;
      for (int i = 0; i < DM_GAME_NWINDOW_FN; i++) printf("window_fn[%d]=%s,%llu,%llu,%u,%u\n", i, wf[i].build, wf[i].wfn, wf[i].hfn, wf[i].w, wf[i].h); }
    printf("nsubgroup_one_lane=%d\n", (int)DM_GAME_NSUBGROUP_ONE_LANE);
    { static const unsigned long long sol[] = DM_GAME_SUBGROUP_ONE_LANE; for (int i = 0; i < DM_GAME_NSUBGROUP_ONE_LANE; i++) printf("subgroup_one_lane[%d]=%llu\n", i, sol[i]); }
    printf("nmesh_vegetation_cut=%d\n", (int)DM_GAME_NMESH_VEGETATION_CUT);
    { static const unsigned long long veg[] = DM_GAME_MESH_VEGETATION_CUT; for (int i = 0; i < DM_GAME_NMESH_VEGETATION_CUT; i++) printf("mesh_vegetation_cut[%d]=%llu\n", i, veg[i]); }
    printf("nmesh_prepass_cut=%d\n", (int)DM_GAME_NMESH_PREPASS_CUT);
    { static const unsigned long long cut[][4] = DM_GAME_MESH_PREPASS_CUT;
      for (int i = 0; i < DM_GAME_NMESH_PREPASS_CUT; i++) printf("mesh_prepass_cut[%d]=%llu,%llu,%llu,%llu\n", i, cut[i][0], cut[i][1], cut[i][2], cut[i][3]); }
    printf("nfps_steps=%d\n", (int)DM_GAME_NFPS_STEPS);
    { static const struct { const char *build; unsigned long long global, mode, count_field; int noffs; unsigned long long offs[3]; } fs[] = DM_GAME_FPS_STEPS;
      for (int i = 0; i < DM_GAME_NFPS_STEPS; i++) {
          printf("fps_steps[%d]=%s,%llu,%llu,%llu,%d", i, fs[i].build, fs[i].global, fs[i].mode, fs[i].count_field, fs[i].noffs);
          for (int k = 0; k < fs[i].noffs && k < 3; k++) printf(",%llu", fs[i].offs[k]);
          printf("\n");
      } }
    printf("ndrs_ctl=%d\n", (int)DM_GAME_NDRS_CTL);
    { static const struct { const char *build; unsigned long long params, pick, budget, preset; } d[] = DM_GAME_DRS_CTL;
      for (int i = 0; i < DM_GAME_NDRS_CTL; i++) printf("drs_ctl[%d]=%s,%llu,%llu,%llu,%llu\n", i, d[i].build, d[i].params, d[i].pick, d[i].budget, d[i].preset); }
    printf("nres_levels=%d\n", (int)DM_GAME_NRES_LEVELS);
    { static const struct { const char *build; unsigned long long table; unsigned n, nl; struct { unsigned long long off; unsigned len; } l[12]; } r[] = DM_GAME_RES_LEVELS;
      for (int i = 0; i < DM_GAME_NRES_LEVELS; i++) {
          printf("res_levels[%d]=%s,%llu,%u,%u", i, r[i].build, r[i].table, r[i].n, r[i].nl);
          for (unsigned k = 0; k < r[i].nl && k < 12; k++) printf(",%llu:%u", r[i].l[k].off, r[i].l[k].len);
          printf("\n");
      } }
    printf("event_wait=%d\n", (int)DM_GAME_EVENT_WAIT); printf("occlusion_split_pass=%d\n", (int)DM_GAME_OCCLUSION_SPLIT_PASS);
    printf("low_ram_place_part_mb=%d\n", (int)DM_GAME_LOW_RAM_PLACE_PART_MB); printf("low_ram_img_cache_max_mb=%d\n", (int)DM_GAME_LOW_RAM_IMG_CACHE_MAX_MB);
    printf("low_ram_app_pool_mb=%d\n", (int)DM_GAME_LOW_RAM_APP_POOL_MB); printf("render_default_out=%s\n", DM_GAME_RENDER_DEFAULT_OUT);
    { static const int q[4] = DM_GAME_RENDER_QUALITY; printf("render_quality=%d,%d,%d,%d\n", q[0], q[1], q[2], q[3]); }
    printf("nrender_cfg=%d\n", (int)DM_GAME_NRENDER_CFG);
    { static const struct { const char *build; unsigned long long table; unsigned n, stride, out_off, ren_off, sz[8][4], ow, oh, mw, mh, min; } r[] = DM_GAME_RENDER_CFG;
      for (int i = 0; i < DM_GAME_NRENDER_CFG; i++) {
          printf("render_cfg[%d]=%s,%llu,%u,%u,%u,%u,%u,%u,%u,%u,%u", i, r[i].build, r[i].table, r[i].n, r[i].stride, r[i].out_off, r[i].ren_off, r[i].ow, r[i].oh, r[i].mw, r[i].mh, r[i].min);
          for (unsigned k = 0; k < r[i].n && k < 8; k++) printf(",%ux%u/%ux%u", r[i].sz[k][0], r[i].sz[k][1], r[i].sz[k][2], r[i].sz[k][3]);
          printf("\n");
      } }
    printf("nrt_pool=%d\n", (int)DM_GAME_NRT_POOL);
    { static const struct { const char *build, *module; unsigned long long off; unsigned word, base, nt, mb[4], w[4], h[4], out_h; } t[] = DM_GAME_RT_POOL;
      for (int i = 0; i < DM_GAME_NRT_POOL; i++) {
          printf("rt_pool[%d]=%s,%s,%llu,%u,%u,%u,%u", i, t[i].build, t[i].module, t[i].off, t[i].word, t[i].base, t[i].nt, t[i].out_h);
          for (unsigned k = 0; k < t[i].nt && k < 4; k++) printf(",%u=%ux%u", t[i].mb[k], t[i].w[k], t[i].h[k]);
          printf("\n");
      } }
    // PEANUTS(upstream-candidate): window_words / view_scale_words / upscale_pass
    printf("nwindow_words=%d\n", (int)DM_GAME_NWINDOW_WORDS);
    { static const struct { const char *build; unsigned n; struct { unsigned long long off; unsigned word, kind; } s[8]; } t[] = DM_GAME_WINDOW_WORDS;
      for (int i = 0; i < DM_GAME_NWINDOW_WORDS; i++) { printf("window_words[%d]=%s,%u", i, t[i].build, t[i].n); for (unsigned k = 0; k < t[i].n && k < 8; k++) printf(",%llu:%u:%u", t[i].s[k].off, t[i].s[k].word, t[i].s[k].kind); printf("\n"); } }
    printf("nview_scale_words=%d\n", (int)DM_GAME_NVIEW_SCALE_WORDS);
    { static const struct { const char *build; unsigned n; struct { unsigned long long off; unsigned word, kind; } s[8]; } t[] = DM_GAME_VIEW_SCALE_WORDS;
      for (int i = 0; i < DM_GAME_NVIEW_SCALE_WORDS; i++) { printf("view_scale_words[%d]=%s,%u", i, t[i].build, t[i].n); for (unsigned k = 0; k < t[i].n && k < 8; k++) printf(",%llu:%u:%u", t[i].s[k].off, t[i].s[k].word, t[i].s[k].kind); printf("\n"); } }
    printf("upscale_pass=%d,%llu,%llu,%u\n", (int)DM_GAME_HAS_UPSCALE_PASS, (unsigned long long)DM_GAME_UPSCALE_PASS_VS, (unsigned long long)DM_GAME_UPSCALE_PASS_PS, (unsigned)DM_GAME_UPSCALE_PASS_BINDING);
    // PEANUTS(upstream-candidate): view_scale_steps
    printf("nview_scale_steps=%d\n", (int)DM_GAME_NVIEW_SCALE_STEPS);
    { static const struct { const char *build; struct { unsigned n; unsigned pct[13]; unsigned word[13]; } st; } t[] = DM_GAME_VIEW_SCALE_STEPS;
      for (int i = 0; i < DM_GAME_NVIEW_SCALE_STEPS; i++) { printf("view_scale_steps[%d]=%s,%u", i, t[i].build, t[i].st.n); for (unsigned k = 0; k < t[i].st.n && k < 13; k++) printf(",%u:%u", t[i].st.pct[k], t[i].st.word[k]); printf("\n"); } }
    // PEANUTS(upstream-candidate): view_scale_fixed
    printf("nview_scale_fixed=%d\n", (int)DM_GAME_NVIEW_SCALE_FIXED);
    { static const struct { const char *build; unsigned w, h; } t[] = DM_GAME_VIEW_SCALE_FIXED;
      for (int i = 0; i < DM_GAME_NVIEW_SCALE_FIXED; i++) printf("view_scale_fixed[%d]=%s,%u,%u\n", i, t[i].build, t[i].w, t[i].h); }
#ifdef DM_GAME_NVN_API_MAJOR
    printf("has_nvn_api=1\n"); printf("nvn_api_major=%d\n", (int)DM_GAME_NVN_API_MAJOR); printf("nvn_api_minor=%d\n", (int)DM_GAME_NVN_API_MINOR);
#else
    printf("has_nvn_api=0\n"); printf("nvn_api_major=0\n"); printf("nvn_api_minor=0\n");
#endif
    return 0;
}
