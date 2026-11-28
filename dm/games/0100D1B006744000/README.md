# Crash Bandicoot N. Sane Trilogy (`0100D1B006744000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Base game 1.0.0 (`v0`). SDK 3.5.3 (`NintendoSdk_nnSdk-3_5_3-Release`, SdkAddon 3.5.2). Vicarious Visions' Alchemy engine (build codename `MACK`, `build.xml`) with NVN graphics, Havok, FMOD and Bink 2. Modules `main`, `subsdk0` (the game's own GLSLC) and `sdk`. Modelled on [Crash Team Racing Nitro-Fueled](../0100F9F00C696000/README.md) (`0100F9F00C696000`), which uses the same Alchemy engine.

## Status

**Plays on Android (Turnip).** Continuous `14.9 min` (`892.65 s`, `34,365` frames) fresh-cache session on `SM-X800` (Handheld mode, `DM_RES_SCALE=0.75`, LSFG off) with `0` crashes, `0` failed pipelines, and `0` audio underruns (`236,000` callbacks); memory footprint `1,414 / 1,591 / 1,799 MiB` peak (`1,096 MiB` peak GPU memory):

- **`30 fps` cap (default):** median **`29.9 fps`** (`33.5 ms` frame time; min `24.7`, max `30.4`), slowest **`284.0 ms`** (cold-cache boot/scene load, frame 1 at `5.6 s`; `35.6 ms` median longframe), **`1.0x` game speed**.
- **`60 fps` cap:** median **`51.6 fps`** (`19.4 ms` frame time; min `27.2`, max `61.2`, holding `59.6–60 fps` / `16.7 ms` on the overworld map and lighter areas, `9.3 ms` GPU busy on map), slowest **`275.7 ms`** (`22.2 ms` median longframe), **`1.0x` game speed**.
- Higher caps (`90`/`120`) and intermediate caps (`40`/`45`) were not measured, so `fps_modes` is narrowed to `30,60`.

## Tested on

- **Samsung Galaxy Tab S8+ (`SM-X800`)**, Snapdragon 8 Gen 1 / Adreno 730, `7,467,116 kB` (`8 GB` / `7.1 GiB` usable) RAM, Android `16`:
  - **Mesa Turnip (`driverID=18`):** `Turnip Adreno Driver T30 (@Mr_Purple_666) (26.3.0-T30-1.4.359)` (`PurpleVK 26.3.0-devel (git-62ac221a33)`, Vulkan `1.4.359`). Fresh-cache run (`0100D1B006744000-20261127-234304.log`, `vk_pipelines.keys` `72,096 bytes`, `273` prewarm keys, `519` graphics pipelines built, `0` failed): boots past Activision bumper, legal screens and Vicarious Visions intro, plays Crash 1 *N. Sanity Beach* through to the overworld map (*Jungle Rollers*) cleanly with FMOD audio and `0` missing shaders.
  - **Qualcomm system driver:** not tested in play (only exposes Vulkan 1.1 on `SM-X800`, fails readiness).

## Builds

| Version | Display | `main` | `subsdk0` | `sdk` |
|---|---|---|---|---|
| `v0` | `1.0.0` | `29e1a37d84227147a50a18d055fbb032` | `a82aa14c5d5a359ad04dc4eab9f5d590` | `c871c614793a419581104bec9379efa3` |

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x0` | `game.tsv` | Verified from `main.npdm` (`SystemResourceSize = 0x0`) and disassembly of `nninitStartup` (`main+0x1169b98`): calls `QueryMemoryInfo` and `SetMemoryHeapSize((total - used) & ~0x1FFFFF)` |
| `mem_used 0x6400000` | `game.tsv` | Raw modules take 85.0 MiB (`main` 61.44, `subsdk0` 13.50, `sdk` 10.07) and ~90 MiB after `patch_nso.py`, exceeding the 64 MiB default; without `mem_used >= image_bytes`, `nninitStartup` fails `SetMemoryHeapSize` and falls back to a fatal 32 MiB heap (`0x2000000`) |
| `modules main subsdk0 sdk` | `game.tsv` | `subsdk0` is GLSLC, imported and called by `main` at run time |
| `depth_mode zero` | `game.tsv` | Verified from disassembly of `Graphics::igNxGraphicsDevice::activate` (`main+0x9edd08`): calls `nvnDeviceSetDepthMode(device, 1)` (`NVN_DEPTH_MODE_NEAR_IS_ZERO`) |
| `present_interval display`, `fps_limit 30`, `fps_modes 30,60` | `game.tsv` | Overrides game's present interval 2 to offer the measured 30 and 60 fps caps |
| `recommend driver.id=8 driver=18` | `game.tsv` | Recommends Mesa Turnip (`VkDriverId` 18) on Qualcomm Adreno devices (see `Tested on`) |
| `capture_keys 8:A*45/3` | `game.tsv` | Presses A during on-device GLSLC capture boot to advance past Activision bumper / legal / language selection (`languagestartup.pak`) and compile the runtime shaders <!-- PEANUTS --> |

## Files and shaders

- **RomFS:** 171 files, 5.59 GB: 166 `.pak` archives under `archives/` (`igArchive` version 12, 99,801 inner files), 2 Bink 2 movies (`bumper_atvi.bk2`, `credits.bk2`), and 3 root files (`build.xml`, `cachenonce.txt`, `memoryconfiguration.igz`).
- **`shader_split.py`:** (with `igArchive` v12 enabled and runtime GLSLC capture) finds **1,635 unique shaders** with zero hash collisions: **1,267 pixel, 367 vertex, 1 geometry, 0 compute** (1,623 from the 84 shader `.igz` files inside `archives/shaders_nx.pak`, 10 from `main`, and 2 runtime GLSLC captures).
- **Fast Prepare (`shader_files.tsv`):** Full split `53.60 s` vs targeted split (`DM_SPLIT_TARGETED=1`) `2.28 s` (lists `archives/shaders_nx.pak`; `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv`, and `pso_pairs.tsv` match).
- **Runtime GLSLC shaders:** `main` embeds 5 GLSL source strings (`#version 440 core` + `GL_NV_gpu_shader5`, Bink 2 video + Alchemy blit) and imports `glslcCompile` from `subsdk0`. Captured on device via `DM_GLSLC_CAPTURE` (or Peanuts' on-device Build) and grouped under `/glslc_capture/=runtime`.

## Vulkan / Mesa Turnip notes (Snapdragon 8 Gen 1 / Adreno 730)

- `main` has **0** callers to `nvnCommandBufferSetRenderEnableConditional` (`render_cond off`), **0** callers to `nvnQueueWaitSync` (`event_wait on`), and uploads textures via `nvnTextureWriteTexels` (`tex_flush_upload off`, `tex_dirty_upload off`).
- `Graphics::igNxGraphicsDevice::activate` checks NVN API version `major == 53` and `minor >= 12` (`main+0x9edce8..0x9edcf8`); Peanuts Native's SDK <= 10 default (`53.313`) satisfies this automatically without setting `device_api`.
- With Mesa Turnip (`driver_id=18`), `vka_auto.h` automatically applies `DM_VK_PSO_SLOTS`, `DM_VK_PRESENT_GATE_WAIT=30`, `DM_VK_ZSHARE_MAX_MB=0`, `DM_VK_SUBGROUP_GS=0` (protects the 1 geometry shader), `DM_VK_SHUFFLE_LDS=1`, `DM_VK_QUAD_SHFL=1`, and `DM_VK_CS_WARM=1`.

## Rewrite census

- `main`: 5,576,212 instructions, 185 `svc` (all `#0x26`), 380 dynamic imports (100% resolved by `sdk` + `subsdk0`), 60.02 MiB image + 1.42 MiB bss.
- `subsdk0` (`GLSLC`): 1,900,973 instructions, 0 `svc`, 13.49 MiB image + 0.01 MiB bss.
- `sdk` (`nnSdk 3.5.3`): 1,014,398 instructions, 106 `svc`, 9.15 MiB image + 0.92 MiB bss.

## Open

- Depends on #863 (`patch_nso.py` `adrp`/`add` jump-table fix for rewriting `main`) and the separate `igArchive` v12 PR (`dm/gfx/tools/shader_split.py`).
- Qualcomm system driver not tested (requires a Vulkan 1.2+ device).
- macOS/iOS not tested.
- 10-minute run on a 12 GB+ Adreno phone with memory peak needed for release list.
