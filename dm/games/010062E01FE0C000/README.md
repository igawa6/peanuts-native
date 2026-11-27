# Digimon Story Time Stranger (`010062E01FE0C000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.2.2 (v262144) over the base game, with 14 add-on content titles (`010062E01FE0D001` to `010062E01FE0D011`). SDK 20.5.21. Media.Vision's own engine on NVN, with CRIWARE. The survey also flags an Unreal 4 marker in `main`. Modules: `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. This is not the engine of [Digimon Story Cyber Sleuth](../010014E00DB56000/README.md), which renders with OpenGL. The archives share only the `MDB1` magic, and this game's archives use another layout (below).

**Status (current): plays on Android.** On an AYN Thor (Turnip 27.0.0 R2), 2026-11-20: the title screen, Shinjuku's streets and a battle, with the right picture, at 39 to 60 fps with the frame limit at 60 (FPSLocker rows, below) and 30 fps Game owned. With the job pool cut (below) one run at limit 60 played about 6 minutes and one Game owned session about 40 minutes without a stop or a crash, where every run before it stopped or crashed within 1.5 to 4 minutes. On Qualcomm's system driver (676.53) it runs without a crash but at 16 to 24 fps, and the characters draw as black silhouettes (open, below); Turnip, the app's default on the Thor, draws them right. The profile started as a provisional one from `dm/games/profile_guess.py`. Its memory keys are the survey's guess.

## Builds

| Module | Build ID |
|---|---|
| `main` | `135c83cdea06aec2d858e1bc7d53d72f` |
| `subsdk0` | `a870a783a09f57256d3630b73485398e` |
| `sdk` | `c668ae02ea221e429677aa61d3dab7c6` |

## What it needs

| Setting | Where | Why |
|---|---|---|
| `modules main subsdk0 sdk` | [`game.tsv`](game.tsv) | `subsdk0` is the movie module |
| `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x1400000` | `game.tsv` | the survey's guess: the NPDM declares 20 MB of system resource, and `main` sets a heap. Not measured |
| `mem_used 0x4a00000` (74 MB) | `game.tsv` | the rewritten modules are larger than the default 64 MB of used memory: `main` 51.4 MB, `subsdk0` 13.7 MB, `sdk` 7.2 MB (`profile_guess.mem_used` on the pack) |
| `romfs_marker app_0.nx64.mvgl` | `game.tsv` | the largest archive |
| `shader_groups /=romfs` | `game.tsv` | every shader is in the RomFS root's archives |

## Files and shaders

- RomFS of 1.2.2: 54 files, 12.89 GB. There are 51 MVGL archives (`*.nx64.mvgl`) and two CRI CPK archives (`app_0.nx64.cpk`, 4.8 GB; `nx_patch_01.nx64.cpk`). The add-on content brings its own `addcont_*.mvgl` and `.cpk` files.
- **MVGL, 64-bit layout** ([`mvgl.py`](../../gfx/tools/mvgl.py) `wide`):
  - The header is `MDB1`, three u32 counts, then a u64 data start and a u64 archive size.
  - Tree entries are 16 bytes, name entries are 0x80 bytes (a 4-byte extension, then the name), and data entries are 16 bytes (u64 offset, u32 length, u32 stored length).
  - A compressed entry is one LZ4 block (Cyber Sleuth's archives use doboz).
  - `app_0.nx64.mvgl` is 6.2 GB with 176,835 entries: `geom` 21,159 (12.5 GB), `img` 19,266, `anim` 43,294, `shad` 56,708 (734 MB) and others.
- **Shaders:** each `shad` entry is one container:
  - It holds one stage: `NXVS` for vertex, `NXFS` for pixel. There are 28,354 of each.
  - Its header gives the container's length at +0x04, the control section's offset and length at +0x0c / +0x10, and the code area's at +0x14 / +0x18.
  - The control section is NVN's (`0x98761234`), and its +0x704 matches the code area's length in every container read.
- `shader_split.py` (`mvgl_jobs`, `nxsh_shader`) finds 19,078 shaders in the archives: 13,059 pixel and 6,019 vertex, no variation pair.
- **The engine's own shaders in `main`:** 202 more NX?S containers (172 pixel, 28 vertex, 2 compute) lie in `main`'s read-only data, the first scene's among them. In each, the control section's assembly text lies between it and the code area, so the control-section heuristic (`raw_shaders`) paired none of them. `shader_split.py` `nxsh_in` reads them by their headers. The split finds **19,280 shaders**: 19,078 in the archives and 202 in `main`, with no `[RED]` line.
- The archives also hold 150 `.glsl` entries: GLSL 4.40 sources of the effect (`spk`) shaders. `main` does not import GLSLC, so they are not compiled at run time. They are taken to be leftovers (open).
- The add-on content's archives were not split. Whether they hold shaders is open.

## Frame rate

The `fpslocker` rows apply FPSLocker Warehouse's config for this build (v4, `135C83CDEA06AEC2.yaml`), main 1.2.2 only, with the host's frame limit (60 by default, up to 120; Game owned applies none):

| Row | Address | What it does |
|---|---|---|
| `step_ticks_u32 1000000:1` | `main+0x1425cd0` | the frame time in µs (16,667 as shipped) |
| `target_i32` | `main+0x1425c98` | the frame rate (60 as shipped) |
| `fpslocker_plan` `p21:0x0`, `b5020091` | `main+0x9d6868`, `+0x9d687c` | `adrp x21` / `ldr x21, [x21, #0x1f0]` (the dynamic resolution's table: two modes of three floats, read at `table[mode * 12]`) become `adrp x21` / `add x21, x21, #0` to the plan's data page |
| `target_offset_f32 2:1`, `frame_offset_ms_f32 5:1`, `5:0.78` | data page `+0x0`, `+0x4`, `+0x8` | mode 0: fps + 2, 1000 / (fps + 5) and 0.78 of it; mode 1 keeps the config's 56, 16.949152, 13 (`d0xc` to `d0x14`) |

The two rate-offset kinds are new in `fpslocker_rows.h` for this config. On the Thor at limit 60: 59.7 to 62 fps, p95 16.8 ms.

## Found on this game

- **Resolved: text, menus and the scene garbled in the left half of the picture in handheld mode (Android, issue #552).** The game keeps its 4x multisample 1920x1080 scene target in handheld mode, draws a 1280x720 viewport into it and downsamples it into 1280x720 textures; the replay resolved the whole 1920x1080 into them. Fixed in the host: [A multisample resolve into a smaller texture](../../../docs/reference/rendering.md#a-multisample-resolve-into-a-smaller-texture-android).
- **Resolved: a Resolution scale above 1x left the 3D scene at 1x (Android, #703).** The scene is drawn into 1280x720 targets, one of them RGBA8 uint, and its lighting shader samples a 512x512 BC3 texture beside them; at 2x the integer target and then that texture took every scene pass back to 1x, so 2x looked no sharper than 1x in docked mode. Fixed in the host → [A resolution scale that left the scene at 1x](../../../docs/reference/rendering.md#a-resolution-scale-that-left-the-scene-at-1x-android). At 2x on the Thor (Turnip 27.0.0-R2): about 43 fps in a subway tunnel, where 1x gives 55 to 60. <!-- PEANUTS -->
- **Resolved: the picture stops, or a crash, within minutes (Android).** At limit 60 the picture stopped (sound went on) after 1.5 to 4 minutes, at the title screen or in play; at 30 fps (Game owned) the game crashed after about 4 minutes, `CRASH sig=11 addr=0x378 pc=main+0xb7f980` (also `addr=0x380 pc=main+0xb7f958`). Both are the animation job pool's (`main+0xb7f3b0`, its workers the three `PoseWorker` threads): the stops leave the pool's count of jobs not done (`pool+0x8`) above 0 with both job vectors empty, the workers spinning on their mutex (millions of lock and unlock SVCs) and the main thread in the pool's wait (`main+0xb7f16c`); the crashes take null jobs from the first vector. The cause is the game's: its wait tidies both job vectors holding neither of their mutexes, and empties them without looking at jobs not taken, while a submit on another thread wakes a worker. A per-build cut skips the tidy ([`patches.py`](patches.py); [Job vectors emptied by a tidy while workers take from them](../../../docs/reference/threads.md#job-vectors-emptied-by-a-tidy-while-workers-take-from-them)). Found with temporary observation gates on the vector's slot loads: a worker read two null slots right before a crash; no job was taken by two threads in the same pass, and no unlocked pop ran.
- **Resolved: crash right after the first frames (Android).** The game reads its sampler builders back (about 6,100 calls of `nvnSamplerBuilderGetLodBias`, `GetCompare`, `GetBorderColor`, `GetReductionFilter`), which the host did not implement: the `[RED]` stub left the outputs unset and the game crashed (`CRASH sig=11 addr=0 pc=main+0xbc13f0`). Fixed in the host (`hle_nvn.c`): the builder's getters return what its setters stored.
- **Resolved: black screen, 94 % of the draws skipped (Android).** The first scene draws with shaders built into `main` (above), which the split did not read: 24 were missing from the library at the first frame, and the screen stayed black. A phone run's `DM_NVN_SHDUMP` saves showed where they are; with `nxsh_in` the split reads all 202 from the game files, and every one of the 48 shaders the runs saved is among them.
- **Resolved: the split found no shader.** The 6.2 GB `app_0.nx64.mvgl` was read whole as a loose file. On macOS a `read()` of more than 2 GB fails (`OSError: [Errno 22] Invalid argument`), so the file was left out. Even read whole, its LZ4 entries would not have been decoded. Fixed in the tools: `mvgl.py` reads the 64-bit layout, and `shader_split.py` splits the archives' `shad` entries into jobs, as for a CPK.

## Open

- **Qualcomm's system driver:** characters draw as black silhouettes (the environment, minimap and UI are right; no `[RED]` line, no failed pipeline), and 16 to 24 fps where Turnip gives 39 to 60: GPU busy about 19.5 ms a frame, but about 39 ms of each frame waits for a command buffer slot's GPU work, with about 104 queue submits a frame. Not investigated yet.
- The movies (CRIWARE through `subsdk0`) and the add-on content are not checked yet.
- **Prewarm seed:** [`vk_prewarm.bin`](vk_prewarm.bin) holds 2,107 pipeline keys (2,105 graphics, 2 compute) from the runs on the Thor, 2026-11-20: the title screen, Shinjuku's streets, battles and a 40-minute session (Game owned, 30 fps, no stop or crash; at most 37 of 2,366 pipelines made on the frame path). No system-driver run is in it yet.
- The memory layout is a guess.

## Android on the AYN Thor

Joins the release in 0.1.7. The Thor results above (the title screen, Shinjuku's streets and battles on Turnip 27.0.0 R2, the frame rates, the 6-minute and 40-minute runs with the job pool cut, and the system driver's black silhouettes) are the contributor's own report from pull request #269. They have not been repeated on a maintainer's Thor.
