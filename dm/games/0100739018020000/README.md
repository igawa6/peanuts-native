# LEGO 2K Drive (`0100739018020000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.17 (v1114112) over the base game 1.0 (v0), SDK 14.3.0. Visual Concepts / 2K Games, Unreal Engine 4.27.2 (`EpicGames+UnrealEngine-4_27_2` in `main`; `SF_GLSL_SWITCH` shaders), NVN, FMOD sound (`.bank`), Bink 2 movies (`.bk2`), PhysX 4.1.2. Modules `main`, `subsdk0` (NintendoSDK_movie, which `main` imports from) and `sdk`. Thirteen add-on content packages of about 120 KB each (unlock entitlements, no files of their own that hold shaders).

**Status (current): plays on an AYN Thor (Turnip 27.0.0-R2), 60 fps.** Checked 2026-11-23 with a pack of 1.17 and the thirteen add-ons built on a PC: boot, the intro and the first drive in Turbo Acres (the drift lesson), 216 s of play: no crash, no `[RED]` line but the usual `mtl_*` statistics stubs, median 59.5 fps (33.8 at the lowest, while loading), GPU busy p50 / p90 12.6 / 21.8 ms, no audio underrun, about 2.8 GB of memory. The profile is the survey's guess (`dm/games/profile_guess.py`) and needed nothing more; its memory keys are those of the other Unreal 4.27 games ([Hogwarts Legacy](../0100F7E00C70E000/README.md)). Not run on the system driver. <!-- PEANUTS -->

## Builds

| Version | Build | `main` build ID | Shaders |
|---|---|---|---|
| 1.17 (`v1114112`) | update | `b75e7d3db78d69c139d53ad109af34e8` | the tables are this version's |

The app builds 1.17 only (`android_builds v1114112`).

## Files and shaders

- One pak, `Artemis/Content/Paks/pakchunk0-Switch.pak` (pak version 11, index not encrypted, 95,833 files: Oodle, Zlib and stored entries). The update's pak replaces the base game's.
- The shaders are in two shader code archives inside it, stored without pak compression: `Artemis/Content/ShaderArchive-Global-SF_GLSL_SWITCH.ushaderbytecode` and `ShaderArchive-Artemis_Chunk0-SF_GLSL_SWITCH.ushaderbytecode`. Each shader in them is Oodle-compressed (`shader_groups ShaderArchive-Global-=global ShaderArchive-=game`).
- **How the tables were made:** on a PC, as for Hogwarts Legacy: `shader_split.py` with `DM_UE_DECODE` and `DM_OODLE_OOZ` (the open-source ooz decoder, `unoodle`, [dm/third_party/ooz](../../third_party/ooz/README.md)) on the update's RomFS and `main`, `J=8`. 15,885 shaders (compute 1,171, geometry 52, pixel 10,168, vertex 4,494; game 14,317, global 1,568). ooz is not the game's own Oodle, and its output has no checksum; the phone's Prepare decodes the same archives with the game's own decoder and keeps its targeted split only when the tables are the same.

## Frame rate <!-- PEANUTS -->

The game runs at 30 fps on the Switch (`t.MaxFPS` 30, `rhi.SyncInterval` 2). The host's general Unreal step raises both to the launcher's target (`DM_FPS_TARGET=60`: `t.MaxFPS` 30 → 60, `rhi.SyncInterval` 2 → 1, game and render thread), and the game then presents at 60 on the Thor. Unreal steps its world by the time between frames, so the speed should stay right at 60; that was not checked against a 30 fps run.

## Memory

The survey's guess for Unreal 4: `mem_reserve 0x1028000000`, `mem_alias 0x1000000000`, `app_pool 0xCD500000`; the NPDM declares 80 MB of system resource (`sys_resource 0x5000000`). Not checked by a heap run.

## Open

- A run on the system driver (Adreno's own Vulkan driver).
- Longer play: races, the open world's other biomes, the garage (brick builder), split screen.

## Found on this game

Nothing yet.
