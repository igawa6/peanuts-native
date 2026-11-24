# Tetris Effect: Connected (`0100DE501433A000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 2.0.2.2 (v1310720; base 1.0.0). SDK 13.3.2. Monstars and Resonair's game (published by Enhance) on Unreal Engine 4.26 / 4.27 (the rewriter's `FMallocBinned3` match; IoStore containers, `.utoc` / `.ucas`), Wwise for audio, NVN graphics, and `nn::movie` (`subsdk0`) for the 63 `.mp4` background movies. Modules `main`, `subsdk0` (NintendoSDK_movie), `sdk`, plus one NRO in `nro/` that `main` loads through `nn::ro`: `EOSSDK-Switch-Shipping.nro`, the Epic Online Services SDK, which `nro_collect.py` lists as refused and the host does not load (as on Hades). NEX 4.6.6 (online play) is linked in `main`.

**Status (current): runs on Android on Turnip; the system driver is untested.** The profile was generated from the NSP script's survey and its shaders split on a Windows PC. `pack_android.py` made a pack of 2.0.2.2 from the `.nup` with no `[RED]` line: the three modules rewritten (`main`: 72,396 plain and 1,236 conditional sites, 109 spin sites, 111 gates, the Unreal allocator cut and the RedpointEOS cut below), and a Vulkan library of all 11,923 shaders (16,937 modules, every one valid for `spirv-val --target-env vulkan1.3`, 117 MB). On an AYN Thor (Adreno 740) with Turnip 27.0.0-R2 and the firmware's shared fonts, with the RedpointEOS cut, one run went on for about 10 minutes until it was stopped from the launcher: 35,084 frames on screen, 48 dropped, a median of 59.7 fps (frame time p50 16.76 ms, p95 18.78 ms), GPU busy about 9.2 ms a frame at 114 draws, a 3.0 to 3.2 GiB footprint, no crash and no `[RED]` line but the refused EOS module's. The first frames after the logos hold one 6.5 s frame (frame 4, the load). What was played (menus, Journey mode, a level) is not recorded in the log. Not run on the Mac. The memory layout is the Unreal guess of `dm/games/profile_guess.py`; it held for this run but was not measured.

## Builds

| Version | Module | Build ID |
|---|---|---|
| 2.0.2.2 (v1310720) | `main` | `8838095f4f659f63cb45a00e62dfb887` |
| | `subsdk0` | `a8cb97803f7f442a3b6cce1c7cd4ee4a` |
| | `sdk` | `357efc198e728cb5d55265aff304f03c` |

Only 2.0.2.2 has a `build` row. The base game 1.0.0 was not surveyed on its own.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `modules main subsdk0 sdk` | `game.tsv` | every module `main` imports from; `subsdk0` is the movie module |
| `mem_reserve 0x1028000000`, `mem_alias 0x1000000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | the Unreal 4 / 5 guess (a 64 GB alias region for `nninitStartup`'s address region; the rewriter cuts the allocator, [memory](../../../docs/reference/memory.md)); not measured for this game |
| `sys_resource 0x5000000` | `game.tsv` | the NPDM declares 80 MB of system resource |
| `shader_groups GlobalShaderCache=global #Engine/=engine #TetrisEffect/=game` | `game.tsv` | set by hand: the guess (`ShaderArchive-Global-=global ShaderArchive-=game`) is for shader code archives, which this game does not have; with it every shader fell into `other`. The groups follow [DRAGON QUEST XI S](../01006C300E9F0000/README.md), whose shaders are inline in the cooked packages too |

## Files and shaders

- RomFS (2.0.2.2): 73 files, 2.16 GB. One `.pak` (`TetrisEffect-Switch.pak`, the `romfs_marker`), two IoStore containers (`.utoc` / `.ucas`), 63 `.mp4` movies under `TetrisEffect/Content/Movies`, one `.nro` and its `.nrr`.
- The pak index is AES-encrypted with a key from `main`; `uepak.py` reads it through `_nxaes` off the Mac.
- `shader_split.py` found 11,923 shaders in the update's RomFS and `main`: 8,782 pixel, 2,402 vertex, 704 compute, 35 geometry. By group: `game` 9,591, `global` 1,573 (the engine's `GlobalShaderCache-SF_GLSL_SWITCH.bin` in the pak), `engine` 759. 2,402 vertex shaders are single-stage programs paired with one cover pixel shader (`1a449f90aff7f220`). No Oodle stream was needed and no `[RED]` line was printed.
- Prewarm seed: not part of the profile (contributors bring no binary). A Turnip 27.0.0-R2 seed of 137 pipeline keys (128 vertex, 9 compute) was made from the Thor's `vk_pipelines.keys` with `dm/tools/vk_prewarm.py seed`, the union of all five runs: 26 from the first two (which crashed at the RedpointEOS fault, after the logos), 16 more from a short run with the cut, 93 more from the 10-minute run and 2 from a last 1.5-minute run. No system-driver keys.

## Rewrite census

From the survey, before any rewrite:

- `main`: 17,533,216 instructions, 1,992 undecodable, 73,861 `x18` uses, 5 `mrs tpidr_el0`.
- `sdk`: 1,528,287 instructions, 277 undecodable, 13,864 `x18` uses, 109 `mrs tpidrro_el0`, 76 `svc`, 42 `mrs cntpct_el0`.
- `subsdk0`: 850,106 instructions, 314 undecodable, 10,541 `x18` uses.

## Found on this game

- **Resolved (by a cut): SIGSEGV at `main+0xb64bec` about 5 s after `nnMain` (Android, Turnip).** The host does not load the Epic Online Services SDK (`ro::LoadModule` fails for `EOSSDK-Switch-Shipping.nro`), so Redpoint's EOS online subsystem module (`RedpointEOS`) has no platform. The function at `main+0xb64b18` takes that module from the module manager, asks it for an object (vtable slot 0x58), builds a delegate and registers it through the object's slot 0x178 without checking the object, which is null: a load from address 0. Before the cut both runs crashed there after about 300 frames at 56 fps. The cut ([`patches.py`](patches.py), build `8838095f4f659f63cb45a00e62dfb887`) rewrites four instructions in place so that a null object branches to the function's own path after the registration (`cbz x20, main+0xb64bfc`); with it the game ran for 10 minutes. It is a workaround for the game's missing check, which the host cannot answer without loading the online SDK. `main` has several hundred other references to the `RedpointEOS` name; none has faulted in the runs so far, and online features are expected not to work.
- **Resolved: SIGSEGV writing address 0 at `main+0x38bbc40` right after `nnMain` on a OnePlus 13R (GitHub issue #455).** PhysX refused an 8-byte block from the host's Unreal heap that the phone's malloc had aligned to 8 only: [Small Unreal allocations only 8-byte aligned by the phone's malloc](../../../docs/reference/memory.md#small-unreal-allocations-only-8-byte-aligned-by-the-phones-malloc-android). A host fix; the reporter's run with it is open.
- **Resolved: Windows paths in the provisional profile and the shader map.** On Windows, `profile_guess.py` wrote `romfs_marker` with `\` (and counted the RomFS top levels wrong), and `uepak.py` / `shader_split.py` labelled pak and IoStore files `..\..\..\Engine\…` instead of `Engine/…`, so the map's sources differed from a Mac or Linux split of the same files. The fix (archive paths normalised with `posixpath`, RomFS paths with `as_posix()`) is its own change; this profile's tables were written with it, so their sources are in `/` form as on a Mac or Linux split.

## Open

- The system driver (Qualcomm, driver ID 8): not run yet. Which parts of the game were reached on Turnip (menus, Journey mode, the background movies, Wwise audio across a level) is not recorded.
- Other `RedpointEOS` code paths that may assume an EOS platform (online menus, leaderboards): not reached or not faulting so far.
- The rewriter found `t.MaxFPS`, `rhi.SyncInterval` and `r.Streaming.PoolSize` in `main` (`ue_cvar.py`); no frame-rate or streaming key is set yet.
- Memory: the layout is the Unreal guess; measure it at the first boot.
- The base game 1.0.0 has no `build` row.
- Online play (NEX) is not expected to work.

## Android on the AYN Thor

Joins the release in 0.1.7. The Thor results above are the contributor's own report from pull request #330: update 2.0.2.2 from a `.nup` on an AYN Thor (Adreno 740, 16 GB, Android 13) with Turnip 27.0.0-R2, about 10 minutes in the frame loop at a median of 59.7 fps with the RedpointEOS cut, and no crash. They have not been repeated on a maintainer's Thor. The system driver, frame caps other than the game's own and a first-launch prewarm seed are not checked; Game settings offers no frame rate rows for this game yet.
