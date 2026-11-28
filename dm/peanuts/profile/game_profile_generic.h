// PEANUTS: the generic host's game_profile.h (nxhost_add_generic(), android/host/CMakeLists.txt; -PnxGenericHost=true). dm/host/dm.h includes
// "game_profile.h" unconditionally; the per-title build points its include path at CMake's generated gen/<game>/game_profile.h (dm/games/game.py
// header), the generic build points it at this file instead, so it is the only game_profile.h in the generic host's whole compile.
//
// Every scalar DM_GAME_* macro here reads a field of the single runtime profile g_profile (profile_rt.h), loaded from the title's profile.bin before
// anything else runs (nxhost_main, nxreplay's main, and the Oodle worker's entry - profile_rt_load()). No per-title text or binary data is compiled in:
// this header is the same bytes for every title.
//
// Three more are left undefined for the same reason and converted the same way: DM_GAME_RENDER_CFG, DM_GAME_RENDER_QUALITY, DM_GAME_RT_POOL (hle_render.c and
// loader.c read g_profile.render_cfg / render_quality / rt_pool, through dm_pf_rcfg_rows() / dm_pf_rtpool_rows() for the two row tables).
// Nine macros are deliberately left undefined here: DM_GAME_MODULES, DM_GAME_DISPLAY_VERSIONS, DM_GAME_WINDOW_FN, DM_GAME_SUBGROUP_ONE_LANE,
// DM_GAME_MESH_VEGETATION_CUT, DM_GAME_MESH_PREPASS_CUT, DM_GAME_FPS_STEPS, DM_GAME_DRS_CTL, DM_GAME_RES_LEVELS. Each of those is used upstream only as
// a `static const T x[] = DM_GAME_FOO;` brace initializer, which must be a compile-time constant - there is no way to make it read runtime data through
// the macro itself. Their ~13 call sites are edited directly (search for "PEANUTS: runtime profile" in dm/host and dm/gfx) to read g_profile's fixed-
// capacity array and count fields instead, behind #ifdef DM_RUNTIME_PROFILE, so the Mac/iOS Makefile build (which never defines DM_RUNTIME_PROFILE)
// takes the untouched `#else` branch with today's compiled-in macro. If this header is in use and one of those nine macros is still referenced
// unconverted somewhere, the build fails with "undeclared" at that line - that failure is intentional: it is the build guard for a missed site.
//
// This file is hand-written, not generated (found in review: an earlier draft of this comment claimed otherwise). What gen_runtime_profile.py
// generates is the per-title profile.bin, from dm/games/game.py's own header() output; it checks that header() never emits a macro name outside its
// own SCALAR/PRESENCE/ARRAY_NAMES tables (peanuts/scripts/check-runtime-profile.sh runs that, as check-all, over every profile under dm/games and fails
// the same way) - but a new SCALAR macro needing a `#define` here, and a new ARRAY_NAMES macro needing a converted call site, are both edits a person
// still has to make by hand in this file and at that site; see peanuts/UPSTREAM-EDITS.md for the full list of sites a rebase conflict here implies.
#pragma once
#include "profile_rt.h"

#define DM_RUNTIME_PROFILE 1

#define DM_GAME_ID (g_profile.id)
#define DM_GAME_NAME (g_profile.name)
#define DM_GAME_PROGRAM_ID (g_profile.program_id)
#define DM_GAME_NMOD (g_profile.nmod)
#define DM_GAME_ROMFS_MARKER (g_profile.romfs_marker)
#define DM_GAME_MEM_RESERVE (g_profile.mem_reserve)
#define DM_GAME_MEM_ALIAS (g_profile.mem_alias)
#define DM_GAME_MEM_STACK (g_profile.mem_stack)
#define DM_GAME_APP_POOL (g_profile.app_pool)
#define DM_GAME_MEM_USED (g_profile.mem_used)
#define DM_GAME_SYS_RESOURCE (g_profile.sys_resource)
#define DM_GAME_ARGS (g_profile.args)
#define DM_GAME_ENV (g_profile.env)
#define DM_GAME_FASTBOOT_ENV (g_profile.fastboot_env)
#define DM_GAME_DEVTOOLS (g_profile.devtools)
#define DM_GAME_MEM_MAP_LIMIT (g_profile.mem_map_limit)
#define DM_GAME_STDALLOC_CAP (g_profile.stdalloc_cap)
#define DM_GAME_PRESENT_DISPLAY (g_profile.present_display)
#define DM_GAME_CORE_PREEMPT (g_profile.core_preempt)
#define DM_GAME_ALLOC_BIG_ALIGN (g_profile.alloc_big_align)
#define DM_GAME_FPS_TARGET (g_profile.fps_target)
#define DM_GAME_FPS_HOLD (g_profile.fps_hold)
#define DM_GAME_FPS_LIMIT (g_profile.fps_limit)
#define DM_GAME_UE_TEXTURE_POOL (g_profile.ue_texture_pool)
#define DM_GAME_UE_FPS_CVAR (g_profile.ue_fps_cvar)
#define DM_GAME_PRESENT_SNAP (g_profile.present_snap)
#define DM_GAME_PSO_MTL4 (g_profile.pso_mtl4)
#define DM_GAME_ENC_THREAD (g_profile.enc_thread)
#define DM_GAME_PAD_LAYOUT (g_profile.pad_layout)
#define DM_GAME_OPMODE (g_profile.opmode)
#define DM_GAME_PERF_BOOST (g_profile.perf_boost)
#define DM_GAME_TEX_FLUSH_UPLOAD (g_profile.tex_flush_upload)
#define DM_GAME_SHADER_KEY_SKIP_OFF (g_profile.shader_key_skip_off)
#define DM_GAME_SHADER_KEY_SKIP_LEN (g_profile.shader_key_skip_len)
#define DM_GAME_RENDER_COND (g_profile.render_cond)
#define DM_GAME_TEX_DIRTY_UPLOAD (g_profile.tex_dirty_upload)
#define DM_GAME_TEX_DYN_SLOTS (g_profile.tex_dyn_slots)
#define DM_GAME_TEX_SOFT (g_profile.tex_soft)
#define DM_GAME_GPU_TS_SEGMENT (g_profile.gpu_ts_segment)
#define DM_GAME_DRAW_BARRIERS_GAME (g_profile.draw_barriers_game)
#define DM_GAME_VCONV_RANGE (g_profile.vconv_range)
#define DM_GAME_AV1_DAV1D (g_profile.av1_dav1d)
#define DM_GAME_ROMFS_SUBST (g_profile.romfs_subst)
#define DM_GAME_NWINDOW_FN (g_profile.nwindow_fn)
#define DM_GAME_NSUBGROUP_ONE_LANE (g_profile.nsubgroup_one_lane)
#define DM_GAME_NMESH_VEGETATION_CUT (g_profile.nmesh_vegetation_cut)
#define DM_GAME_NMESH_PREPASS_CUT (g_profile.nmesh_prepass_cut)
#define DM_GAME_NFPS_STEPS (g_profile.nfps_steps)
#define DM_GAME_NDRS_CTL (g_profile.ndrs_ctl)
#define DM_GAME_NRES_LEVELS (g_profile.nres_levels)
#define DM_GAME_EVENT_WAIT (g_profile.event_wait)
#define DM_GAME_OCCLUSION_SPLIT_PASS (g_profile.occlusion_split_pass)
#define DM_GAME_LOW_RAM_PLACE_PART_MB (g_profile.low_ram_place_part_mb)
#define DM_GAME_LOW_RAM_IMG_CACHE_MAX_MB (g_profile.low_ram_img_cache_max_mb)
#define DM_GAME_LOW_RAM_APP_POOL_MB (g_profile.low_ram_app_pool_mb)
#define DM_GAME_PLACE_JOIN_MB (g_profile.place_join_mb)
#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF (g_profile.push_desc_qcom_old_off)
#define DM_GAME_TEX_POOL_RANGE ((int)g_profile.tex_pool_range)   // PEANUTS
#define DM_GAME_RENDER_DEFAULT_OUT (g_profile.render_default_out)
#define DM_GAME_NRENDER_CFG (g_profile.nrender_cfg)
#define DM_GAME_NRT_POOL (g_profile.nrt_pool)

// Presence macros: upstream's #ifdef/#if-defined call sites are themselves converted (hle_sdk16.c, hle_nvn.c) to read g_profile.has_x at run time under
// #ifdef DM_RUNTIME_PROFILE; nothing here needs to fake "definedness". Kept as plain reads for anywhere else that just wants the value when present.
#define DM_GAME_SHARED_FONT_TTF (g_profile.shared_font_ttf)
#define DM_GAME_NVN_API_MAJOR (g_profile.nvn_api_major)
#define DM_GAME_NVN_API_MINOR (g_profile.nvn_api_minor)
