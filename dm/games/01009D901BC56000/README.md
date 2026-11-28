# Donkey Kong Country Returns HD (`01009D901BC56000`)

Update 1.1.0 (v65536) over the base game, SDK 18.3.5. Unity 6000.0.28f1 IL2CPP with FMOD on NVN. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`; the SDK and movie module are the builds used by [Monster Train 2](../010051701FB46000/README.md) and [Super Mario Galaxy](../010099C022B96000/README.md). The base game is not registered or tested.

Plays on the Mac at 60 fps: the title, a new save, the opening movie, the island and Jungle maps, and the opening of 1-1 Jungle Hijinxs (running, jumping, damage, losing a life and restarting). An unsigned IPA is built; iOS is untested.

<!-- PEANUTS: the Android sections below (Status, Tested on, Builds) and the Android items under Open -->
## Status

Plays on Android at the game's own 60 fps. The profile offers no frame-rate cap: the game paces itself (Game owned), and that pacing was measured.

| Cap | Frame time (median of 300-frame windows' p50) | p95 (median / worst window) | Slowest frame | Game speed |
|---|---|---|---|---|
| Game owned (60 fps) | 16.70 ms (16.58–16.80) | 18.91 / 26.55 ms | 535 ms, at scene changes | not measured against a clock in the game (no slowdown seen in play) |

- The slow frames come at scene and level changes (frames 2308, 14266, 14282, 14688 of the run): the game's own main thread and its `BatchDel` thread are busy, with no new pipeline in those frames. 21 of the 141 gameplay windows had a frame over 33.4 ms.
- First launch from an empty pipeline cache: 294 graphics and 117 compute pipelines made, none failed, 170 of them on the frame path.
- Memory: footprint 2,302 MiB at the start, 2,763 MiB at its peak (frame 35400), 2,724 MiB at the end.
- The opening movie `GI.mp4` needs the H.264 low-latency fix for streams in decode order (its 1920×1080 Main profile stream has `pic_order_cnt_type` 2): without it the decoder keeps the last two pictures and the game waits on the last frame. The fix is its own pull request.

## Tested on

AYN Thor (Qualcomm QCS8550 / Snapdragon 8 Gen 2, Adreno 740, 16 GB, Android 13), PurpleVK public driver (Turnip, `26.3.0-devel`, `git-62ac221a33`, driver version `0x6802063`), the Peanuts test app at `00ebfe300df5` with the movie fix, on 2026-11-28. From an empty pipeline cache: the startup, the opening movie (skipped after a few seconds), the menus and two levels, about 12.7 minutes (47,100 frames). The log reports the main build `991eccf3339153e469f15936fac59c52` (1.1.0, `v65536`). Not run on Qualcomm's own driver.

## Builds

| Version | Package | Main build | Evidence |
|---|---|---|---|
| 1.1.0 | `v65536` | `991eccf3339153e469f15936fac59c52` | Full and targeted shader splits match; the run above |

The shader split of the 1.1.0 `.nup` on a Windows PC: the full split (`DM_SPLIT_FILES`, which wrote `shader_files.tsv`) took 35 s over the 362 RomFS files, the targeted split (`DM_SPLIT_TARGETED=1`) 11 s over 39 of them. Both found 19,314 shaders and 9,602 pairs, and the targeted split's `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv` and `pso_pairs.tsv` match the full split's byte for byte. Against the earlier tables only their header comments changed.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | the NPDM declares 16 MB of system resource: virtual address memory |
| 5 GB guest block: `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | Unity 6 reserves and commits its regions itself, without setting a heap, as [BALL x PIT](../010086A022444000/README.md) |
| `depth_mode zero` | `game.tsv` | the offline pipeline prebuild uses the near=0 convention the game sets at boot |
| SDK node area 1 GB → 62 MB, and regions of 4 MB and more aligned to 2 MB as SDK 20 | `dm/tools/sdk_patches.py`, the SDK-wide rules (`MATCH_CUTS`) on SDK build `60751516…` | leaves room for the game's address regions; the build's row (the node area cut alone) was removed on 2026-10-30 after a run on Monster Train 2, the same build |
| Unity 6 Shader layout | `dm/gfx/tools/unityfs.py` | no bundle supplies the stripped files' type tree; see [Shaders](#shaders) |

No game-code patch.

## Shaders

- **Resolved:** the split stopped with `no bundle has a Shader type tree of the same hash to borrow`. The 214 Shader objects have hash `2ec7223dce3af4232b14ae1441bd7999`, the same as BALL x PIT's bundles. The shared reader now supplies that layout: [Unity Shader layouts written out for players without bundles](../../../docs/reference/shaders.md#unity-shader-layouts-written-out-for-players-without-bundles).
- The layout matches the bundle tree in types, field order and alignment flags. Every Shader object and all 16 ComputeShader objects read to their exact ends; compute uses the existing 2022.3 layout (`bfdf63ab…`).
- All 19,314 shaders build for Mac and iOS, with no unresolved binding or driver-bank read the host cannot fill. The prewarm seed has 280 entries (160 shaders, 110 render pipelines, 10 compute pipelines), covering the tested opening route. A clean Mac startup prepared every entry in 0.04 seconds with no missing key or red line.
- 19,314 stage shaders: 9,596 vertex, 9,600 pixel, 117 compute and one geometry; 9,602 vertex/pixel pairs. Of these, 19,303 come from the loose serialized files under `Data/`, and 11 are the player's GLSLC outputs in `main`. RomFS has 362 files (12.50 GB), including 14 MP4 movies and 74 FMOD resource files.

## Mac routes

The work folder is `<NSP folder>/01009D901BC56000-work`. `run/boot.py <log name> <seconds> KEY=VALUE…` uses the rewritten v65536 modules and `build/returnshost`, with save and temporary storage in `run/`. `run/ctl.py --shot <name> <buttons…>` drives `DM_CTL` and takes a screenshot.

- Fresh save: L+R at the title, A at the update notice, A for slot 1, A for Modern Mode, then A for one player. Give each menu transition about two seconds. The opening movie is `GI.mp4`, 119 seconds of 1920×1080 H.264 with stereo 48 kHz AAC.
- Existing slot: L+R, A, then A for one player; the new-game mode choice is skipped. The save made after the intro starts at the island map. A enters Jungle; A selects 1-1 Jungle Hijinxs, and A starts the level. At the treehouse, press X rapidly (30 presses about 0.23 seconds apart) to finish the opening prompt; widely spaced presses do not finish it. The left stick moves, B jumps and X/Y pounds or rolls.

## Testing

- 2026-10-26, Mac: initialization, title, new-game menus, the opening MP4, save creation and reloading, island and Jungle maps, and the opening of 1-1 Jungle Hijinxs. Running, jumping over the first block, enemy damage, losing a life (four balloons to three), restarting and the pause menu work. No red line in the complete-library run, about ten minutes including the maps and first-level testing. No game-code cut or rendering switch is needed.
- Frame time p50 about 16.67 ms (60 fps); median p95 over the last twenty 300-frame windows 19.53 ms while the IPA build ran on the same Mac.
- The address-region high-water mark reaches +3.51 GB of 4.375 GB in the first level. Resident memory settles at 2.67 GB and Metal allocations at 0.96 GB: 2,674 → 2,676 MB resident and 962 → 963 MB GPU over frames 24,000–33,000, about 150 seconds.
- FMOD opens one stereo 48 kHz AudioOut. The first ten seconds of PCM contain 592,464 nonzero samples of 960,000, peak 12,940 and RMS 1,239; real Core Audio output opens without an underrun or watchdog warning. Sound was not assessed by listening.
- The unsigned IPA is about 11.6 GB (12,486,937,808 bytes), with 19,314 iOS libraries, the prewarm list and the fps counter. Archive inspection confirmed version 1.1.0, the correct bundle ID, an unsigned arm64 executable and no diagnostic environment file.

## Open

- iOS has not been tested on a device.
- Only the opening portion of the first level was played; finishing it, later levels, bosses and multiplayer remain untested.
- The other thirteen MP4 movies and non-English languages are untested.
<!-- PEANUTS: Android -->
- Android: the game speed against a clock in the game (10 game seconds in 10 s) was not measured.
- Android: Qualcomm's own driver was not run; the opening movie was skipped after a few seconds on this run, not watched to its end; later worlds, bosses and multiplayer were not played.

## Found on this game

- The stripped Unity 6 Shader layout, hash `2ec7223d…`: [shaders.md](../../../docs/reference/shaders.md#unity-shader-layouts-written-out-for-players-without-bundles).
