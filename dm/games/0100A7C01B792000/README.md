# Minecraft Dungeons II (`0100A7C01B792000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.1.3.0 (v131072) over the base game 1.1.1.0 (v0), SDK 20.5.15. Mojang Studios, Unreal Engine 5.6.1 (`SDK MW+EpicGames+UnrealEngine-5_6_1` in `main`; `SF_GLSL_SWITCH` shaders), NVN, Wwise sound, Havok. Modules `main`, `subsdk0` (NintendoSDK_movie, which `main` imports from) and `sdk`. One add-on content package, the Hero Pack (`0100A7C01B793001`, about 120 KB: an entitlement and a text file, no shaders).

**Status (current): plays on an AYN Thor (Turnip 27.0.0-R2), 60 fps.** First run 2026-11-23 with a pack of 1.1.3.0 and the Hero Pack built on a PC: boot, the opening and the first level (Squid Coast), 130 s of play: no crash, median 59 fps (35.7 at the lowest, while loading), GPU busy p50 / p90 11.1 / 16.3 ms, no audio underrun, about 2.1 GB of memory. The profile is the survey's guess (`dm/games/profile_guess.py`) plus `tex_dyn_slots on` (below). Not run on the system driver. <!-- PEANUTS -->

## Builds

| Version | Build | `main` build ID | Shaders |
|---|---|---|---|
| 1.1.3.0 (`v131072`) | update | `c6b32f4d3d4243ff190b082b4f6aea18` | the tables are this version's |

## Files and shaders

- An IoStore container, `Dungeons/Content/Paks/Dungeons-Switch.utoc` / `.ucas` (the profile's `romfs_marker`), beside `Dungeons-Switch.pak` and a `global.utoc` / `.ucas`, as [FINAL FANTASY RESONANCE](../0100606026F12000/README.md) (the same engine version).
- The shaders are in the container's two IoStore shader libraries, `ShaderArchive-Global-SF_GLSL_SWITCH-SP_GLSL_SWITCH` (3,433 shaders) and `ShaderArchive-Dungeons-SF_GLSL_SWITCH-SP_GLSL_SWITCH` (13,318), Oodle-compressed (`shader_groups ShaderArchive-Global-=global ShaderArchive-=game`).
- **How the tables were made:** on a PC, `shader_split.py` with `DM_UE_DECODE` and `DM_OODLE_OOZ` (the open-source ooz decoder, `unoodle`, [dm/third_party/ooz](../../third_party/ooz/README.md)) on the update's RomFS and `main`, `J=8`: both libraries decoded in 3 s. 15,826 shaders (compute 1,987, geometry 4, pixel 10,354, vertex 3,481; game 12,405, global 3,421). ooz is not the game's own Oodle, and its output has no checksum; the phone's Prepare decodes the same libraries with the game's own decoder and keeps its targeted split only when the tables are the same.

## Dynamic texture handles

64 vertex shaders (Unreal's particle vertex factories, as in [FINAL FANTASY RESONANCE](../0100606026F12000/README.md)) pick their texture and sampler slots from c2's tables at run time. Without `tex_dyn_slots on` the first run logged `[RED] vk: shader … dynamic texture handles (profile key tex_dyn_slots is off); its draws are skipped` for each of them. The library already has their fallback arrays (249 `tex_dyn` / `samp_dyn` bindings), so the key needs no new shaders. **Resolved:** the second run with the key (2026-11-23, Squid Coast, about 2 minutes) had no `[RED]` line, median 57.6 fps.

## Memory

The survey's guess for Unreal: `mem_reserve 0x1028000000`, `mem_alias 0x1000000000`, `app_pool 0xCD500000`; the NPDM declares 20 MB of system resource (`sys_resource 0x1400000`). Not checked by a heap run.

## Open

- A run on the system driver (Adreno's own Vulkan driver).
- The frame rate: the game asked for present interval 2 and presented at 60 on the Thor through the general Unreal step (`DM_FPS_TARGET=60`); game speed at 60 not compared with a 30 fps run.
- Longer play: camp, other levels, the Hero Pack's content.

## Found on this game

- **A pack made on Windows keeps most of the PC's threads idle** (the rewrite of `main` serial for most of its 17 minutes, the shaders on about one core): [Packing](../../../docs/reference/packing.md#a-pack-made-on-windows-keeps-most-of-the-pcs-threads-idle).
