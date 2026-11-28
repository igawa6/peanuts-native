// PEANUTS: equivalence test, PC side B. Built once (not per title; see dump_const.c, the per-title side). Loads a profile.bin written by
// gen_runtime_profile.py and prints the same fields in the same text form dump_const.c uses, straight from g_profile - no DM_GAME_* macros here, since
// this is exactly what the generic host itself does at start.
#include "profile_rt.h"
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    if (argc != 2) { fprintf(stderr, "usage: dump_runtime <profile.bin>\n"); return 2; }
    const char *err = profile_rt_load(argv[1]);
    if (err) { fprintf(stderr, "profile_rt_load: %s\n", err); return 2; }
    dm_game_profile_t *p = &g_profile;

    printf("id=%s\n", p->id);
    printf("name=%s\n", p->name);
    printf("program_id=%llu\n", (unsigned long long)p->program_id);
    printf("nmod=%d\n", (int)p->nmod);
    for (int i = 0; i < (int)p->nmod; i++) printf("modules[%d]=%s\n", i, p->modules[i]);
    printf("romfs_marker=%s\n", p->romfs_marker);
    printf("mem_reserve=%llu\n", (unsigned long long)p->mem_reserve);
    printf("mem_alias=%llu\n", (unsigned long long)p->mem_alias);
    printf("mem_stack=%llu\n", (unsigned long long)p->mem_stack);
    printf("app_pool=%llu\n", (unsigned long long)p->app_pool);
    printf("mem_used=%llu\n", (unsigned long long)p->mem_used);
    printf("sys_resource=%llu\n", (unsigned long long)p->sys_resource);
    printf("args=%s\n", p->args);
    printf("env=%s\n", p->env);
    printf("fastboot_env=%s\n", p->fastboot_env);
    printf("devtools=%s\n", p->devtools);
    printf("mem_map_limit=%d\n", (int)p->mem_map_limit);
    printf("stdalloc_cap=%llu\n", (unsigned long long)p->stdalloc_cap);
    for (int i = 0; i < (int)p->ndisplay_versions; i++) printf("display_versions[%d]=%s,%s\n", i, p->display_versions[i].build, p->display_versions[i].ver);
    printf("present_display=%d\n", (int)p->present_display);
    printf("core_preempt=%d\n", (int)p->core_preempt);
    printf("alloc_big_align=%d\n", (int)p->alloc_big_align);
    printf("fps_target=%d\n", (int)p->fps_target);
    printf("fps_hold=%d\n", (int)p->fps_hold);
    printf("fps_limit=%d\n", (int)p->fps_limit);
    printf("ue_texture_pool=%d\n", (int)p->ue_texture_pool);
    printf("ue_fps_cvar=%s\n", p->ue_fps_cvar);
    printf("present_snap=%d\n", (int)p->present_snap);
    printf("pso_mtl4=%d\n", (int)p->pso_mtl4);
    printf("enc_thread=%d\n", (int)p->enc_thread);
    printf("has_shared_font_ttf=%d\n", (int)p->has_shared_font_ttf);
    printf("shared_font_ttf=%d\n", (int)p->shared_font_ttf);
    printf("pad_layout=%d\n", (int)p->pad_layout);
    printf("opmode=%d\n", (int)p->opmode);
    printf("perf_boost=%d\n", (int)p->perf_boost);
    printf("tex_flush_upload=%d\n", (int)p->tex_flush_upload);
    printf("shader_key_skip_off=%llu\n", (unsigned long long)p->shader_key_skip_off);
    printf("shader_key_skip_len=%llu\n", (unsigned long long)p->shader_key_skip_len);
    printf("render_cond=%d\n", (int)p->render_cond);
    printf("tex_dirty_upload=%d\n", (int)p->tex_dirty_upload);
    printf("tex_dyn_slots=%d\n", (int)p->tex_dyn_slots);
    printf("tex_soft=%d\n", (int)p->tex_soft);
    printf("gpu_ts_segment=%d\n", (int)p->gpu_ts_segment);
    printf("draw_barriers_game=%d\n", (int)p->draw_barriers_game);
    printf("vconv_range=%d\n", (int)p->vconv_range);
    printf("av1_dav1d=%d\n", (int)p->av1_dav1d);
    printf("romfs_subst=%s\n", p->romfs_subst);
    printf("nwindow_fn=%d\n", (int)p->nwindow_fn);
    for (int i = 0; i < (int)p->nwindow_fn; i++) printf("window_fn[%d]=%s,%llu,%llu,%u,%u\n", i, p->window_fn[i].build, (unsigned long long)p->window_fn[i].wfn, (unsigned long long)p->window_fn[i].hfn, p->window_fn[i].w, p->window_fn[i].h);
    printf("nsubgroup_one_lane=%d\n", (int)p->nsubgroup_one_lane);
    for (int i = 0; i < (int)p->nsubgroup_one_lane; i++) printf("subgroup_one_lane[%d]=%llu\n", i, (unsigned long long)p->subgroup_one_lane[i]);
    printf("nmesh_vegetation_cut=%d\n", (int)p->nmesh_vegetation_cut);
    for (int i = 0; i < (int)p->nmesh_vegetation_cut; i++) printf("mesh_vegetation_cut[%d]=%llu\n", i, (unsigned long long)p->mesh_vegetation_cut[i]);
    printf("nmesh_prepass_cut=%d\n", (int)p->nmesh_prepass_cut);
    for (int i = 0; i < (int)p->nmesh_prepass_cut; i++) printf("mesh_prepass_cut[%d]=%llu,%llu,%llu,%llu\n", i, (unsigned long long)p->mesh_prepass_cut[i][0], (unsigned long long)p->mesh_prepass_cut[i][1], (unsigned long long)p->mesh_prepass_cut[i][2], (unsigned long long)p->mesh_prepass_cut[i][3]);
    printf("nfps_steps=%d\n", (int)p->nfps_steps);
    for (int i = 0; i < (int)p->nfps_steps; i++) {
        printf("fps_steps[%d]=%s,%llu,%llu,%llu,%d", i, p->fps_steps[i].build, (unsigned long long)p->fps_steps[i].ptr_off, (unsigned long long)p->fps_steps[i].mode, (unsigned long long)p->fps_steps[i].count, (int)p->fps_steps[i].nderef);
        for (int k = 0; k < (int)p->fps_steps[i].nderef && k < 3; k++) printf(",%llu", (unsigned long long)p->fps_steps[i].deref[k]);
        printf("\n");
    }
    printf("ndrs_ctl=%d\n", (int)p->ndrs_ctl);
    for (int i = 0; i < (int)p->ndrs_ctl; i++) printf("drs_ctl[%d]=%s,%llu,%llu,%llu,%llu\n", i, p->drs_ctl[i].build, (unsigned long long)p->drs_ctl[i].params, (unsigned long long)p->drs_ctl[i].pick, (unsigned long long)p->drs_ctl[i].budget, (unsigned long long)p->drs_ctl[i].preset);
    printf("nres_levels=%d\n", (int)p->nres_levels);
    for (int i = 0; i < (int)p->nres_levels; i++) {
        printf("res_levels[%d]=%s,%llu,%u,%u", i, p->res_levels[i].build, (unsigned long long)p->res_levels[i].table, p->res_levels[i].n, p->res_levels[i].nl);
        for (unsigned k = 0; k < p->res_levels[i].nl && k < 12; k++) printf(",%llu:%u", (unsigned long long)p->res_levels[i].l[k].off, p->res_levels[i].l[k].len);
        printf("\n");
    }
    printf("event_wait=%d\n", (int)p->event_wait); printf("occlusion_split_pass=%d\n", (int)p->occlusion_split_pass);
    printf("low_ram_place_part_mb=%d\n", (int)p->low_ram_place_part_mb); printf("low_ram_img_cache_max_mb=%d\n", (int)p->low_ram_img_cache_max_mb);
    printf("low_ram_app_pool_mb=%d\n", (int)p->low_ram_app_pool_mb); printf("render_default_out=%s\n", p->render_default_out);
    printf("place_join_mb=%d\n", (int)p->place_join_mb);
    printf("push_desc_qcom_old_off=%d\n", (int)p->push_desc_qcom_old_off);
    printf("tex_pool_range=%d\n", (int)p->tex_pool_range);   // PEANUTS
    printf("render_quality=%d,%d,%d,%d\n", (int)p->render_quality[0], (int)p->render_quality[1], (int)p->render_quality[2], (int)p->render_quality[3]);
    printf("nrender_cfg=%d\n", (int)p->nrender_cfg);
    for (int i = 0; i < (int)p->nrender_cfg; i++) {
        const dm_pf_render_cfg_t *r = &p->render_cfg[i];
        printf("render_cfg[%d]=%s,%llu,%u,%u,%u,%u,%u,%u,%u,%u,%u", i, r->build, (unsigned long long)r->table, r->n, r->stride, r->out_off, r->ren_off, r->orig_w, r->orig_h, r->max_w, r->max_h, r->min_h);
        for (unsigned k = 0; k < r->n && k < 8; k++) printf(",%ux%u/%ux%u", r->sz[k][0], r->sz[k][1], r->sz[k][2], r->sz[k][3]);
        printf("\n");
    }
    printf("nrt_pool=%d\n", (int)p->nrt_pool);
    for (int i = 0; i < (int)p->nrt_pool; i++) {
        const dm_pf_rt_pool_t *t = &p->rt_pool[i];
        printf("rt_pool[%d]=%s,%s,%llu,%u,%u,%u,%u", i, t->build, t->module, (unsigned long long)t->off, t->word, t->base_mb, t->nt, t->out_h);
        for (unsigned k = 0; k < t->nt && k < 4; k++) printf(",%u=%ux%u", t->mb[k], t->max_w[k], t->max_h[k]);
        printf("\n");
    }
    printf("has_nvn_api=%d\n", (int)p->has_nvn_api);
    printf("nvn_api_major=%d\n", (int)p->nvn_api_major);
    printf("nvn_api_minor=%d\n", (int)p->nvn_api_minor);
    return 0;
}
