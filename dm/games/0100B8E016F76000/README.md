# NieR:Automata The End of YoRHa Edition, Asian release (`0100B8E016F76000`)

Update 1.0.3 (v131072, update title `0100B8E016F76800`) over the Asian base game, SDK 12.3.9: the Japanese / Korean / Chinese release under its own title ID. PlatinumGames' engine on NVN, as in the global release ([010056B015FE8000](../010056B015FE8000/README.md)), whose note covers the game, its keys, its shaders and its 60 fps. Modules `main`, `subsdk0` and `sdk`; `subsdk0` (`be1ee79a…`) and `sdk` (`e8cdceb1…`) are the global release's builds, `main` (`98397a10…`) is not.

## What it needs

The global profile's keys:

| Setting | Where | Why |
|---|---|---|
| Memory, `sys_resource`, `pad_layout`, `tex_flush_upload`, `tex_dirty_upload`, `present_interval display`, `gpu_timestamps segment`, `fps_target 60` | [`game.tsv`](game.tsv) | as the global profile ([its note](../010056B015FE8000/README.md#what-it-needs)) |
| Six dynamic-resolution cuts | [`patches.py`](patches.py) | the global build's 60 fps thresholds and preset-following lower bounds. The function is the global build's, `0x13c0` further on: each original sequence matches once in this `main`, at the global address + `0x13c0` |
| Two frame-interval defaults (`0x591198`, `0x9bc128`) | `patches.py` | 60 fps as the game's own default, as the global build's. Single instructions, matched by what the code does: the first loads the mode right before the mode setter (`0x9c0590`), the second is the interval the present-interval function stores at the device's `+0x9c160` |
| Frame rate (`DM_FPS_TARGET`, 30 or 60) | `hle_fpslock.c` `k_nier_dr` | the host sets the frame-interval mode at the display object's `+0x504` and the device's present interval, as for the global build. The display object is `main+0x192d600`, the target of the relocation of the slot the mode setter reads (`main+0x12c2d18`) |
| `DYNAMIC_RESOLUTION` capability | `patches.py` | the launcher's **Dynamic resolution** (`DM_DYNAMIC_RESOLUTION`, off by default): the game's own controller may scale between 70 and 100 %. The host sets the preset minimum at `main+0x128f130`, the target of the relocation of the slot the function reads it through (`main+0x12c8500`), after checking the clamp (`0x9c0928`) and publish (`0x9c0938`) instructions and both floor cuts (`hle_fpslock.c` `k_nier_dr`) |

## Files and shaders

- The same files as the global release; `shader_map.tsv`, `shader_pairs.tsv`, `shader_facts.tsv`, `bf_want.tsv` and `pso_manifest.tsv` are identical to its tables (1,225 shaders).
- `geo_mesh.tsv` (also in the global profile): the 96 pairs of the four `IndexBuildIndependent_*` geometry shaders with the 24 shadow-map vertex shaders (`zmap_vs_*`), named by the shaders' `.gso` / `.vso` files. Each gets a mesh library; the Resistance Camp draws seven of them.

## Status

- Reported in [PR #416](https://github.com/seedds/nxnative/pull/416), Mac (M1, 720p handheld mode): title, a new Easy game with auto-chips (name entry through `DM_KBD`), the opening flight, the ocean and the factory: 60 fps held for four minutes after the [lean geometry path](../../../docs/reference/shaders.md#passthrough-geometry-shaders-drawn-by-the-vertex-shader) (50 ms frames before it).
- iPhone 15 Pro (player's report, handheld mode): 60 fps through most of the flight; the heaviest later scenes (1,100–2,070 draws a frame) fell to about 15 fps once the phone was thermally serious, with up to 28 ms of translation and 56 ms of GPU work a frame. The dynamic-resolution control was not yet in that build.

- Current reconciliation: all 1,225 shaders and 96 mesh pairs build for Mac and iOS on chain `a78f966a7b38`, with a separate Asian-profile stamp. The shader inputs match the verified global package. The Asian executable and iPhone runtime were not locally retested for this reconciliation.

## Android (Peanuts Native)

PEANUTS: packed on Linux from 1.0.3 with its DLC (`0100B8E016F77001`): all 1,225 shaders and 1,476 modules valid, a 12.3 MB library; the targeted split reads 1 of 661 RomFS files (1.1 s, against 197 s for the full scan, with the same tables and package hashes). Played on an AYN Thor (Adreno 740) with Turnip v26.2.0 R5, docked mode, 2026-11-20:

- Title, menus, the Downloadable Content page (its items listed from the mounted DLC; their use in play is not checked), save creation, name entry through the Android text dialog, Easy with auto-chips, the opening formation cinematic and the ocean flight with combat: 60 fps, frame time 16.7 ms, GPU 7 to 14 ms, about 1.9 GB. Left stick movement and auto-fire respond. Wwise through AAudio, 48 kHz: no underruns in 56,000 callbacks. A relaunch loads the save. No crash in about 15 minutes over several runs.
- The system driver (Qualcomm) draws the same scenes right but runs the formation cinematic at 36 to 39 fps (25.6 ms of GPU work a frame, against 13.7 on Turnip).
- One log line besides the Metal-only statistics: `SetRenderEnableConditional: the host draws anyway for now`. No wrong picture was seen.
- Frame limits ([`game.tsv`](game.tsv)): the native clock steps by elapsed time in 60 Hz ticks, clamped to one or two a frame. The interval between two subtitles of the opening cinematic took 6.41 s at 60, 6.51 s at 45 and 6.19 s at 30 (correct, within the 0.6 s screenshot timing) and 7.53 s at 20 (slow, past the two-tick clamp). The launcher offers 30, 40, 45 and 60; above 60 the one-tick floor would run the game fast, so 90 and 120 are not offered.
- Shader seed (`vk_prewarm.bin`, 225 pipeline keys from the title, menus and the opening flight): a cold launch prepares them before the game starts (2.3 s, 142 made, 83 dropped as on every run); the relaunch reads the pipeline cache and prepares nothing.

## Found on this game

- [Passthrough geometry shaders drawn by the vertex shader](../../../docs/reference/shaders.md#passthrough-geometry-shaders-drawn-by-the-vertex-shader): the lean kernels, their hoisting, and the mesh draws (Resistance Camp on an M1: 21.2 → about 17 ms a frame).
- [Controller haptic engines without advanced players](../../../docs/reference/services.md#controller-haptic-engines-without-advanced-players): a GameSir Super Nova on the iPhone.

## Open

- The dynamic-resolution control and the 30 fps choice on the iPhone are untested.
