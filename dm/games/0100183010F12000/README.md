<!-- PEANUTS: Android bring-up of JUMP FORCE - Deluxe Edition -->
# JUMP FORCE - Deluxe Edition (`0100183010F12000`)

Base game v0 (10.5 GB) and update v458752 (1.08, 1.8 GB): 459 RomFS files, 10.5 GB, most of it in two Unreal paks (`J5/Content/Paks/pakchunk0-Switch.pak`, 151,223 files, and `pakchunk1-Switch.pak`, 70,666), 186 MP4 movies and 43 CRIWARE `.awb` sound banks. SDK 9.3.1. Unreal Engine 4 (4.22, from its pak version) on NVN. Modules `main` (build `1667568420912c733119f8ed92a52794`), `subsdk0` (NintendoSDK_movie, `5a7ada8dd7447c10aa244944f64d0aff`) and `sdk` (`ad3fdfbd62a5f4b082d385ffe420d440`): the same `sdk` and `subsdk0` builds as [DRAGON BALL Z: KAKAROT](../010051C0134F8000/README.md).

## Status

Bring-up on Android: boots to the menus, Create Avatar and fights on the New York stage at 60 fps (fights stepped for 60: Frame rate) (Adreno 840, Turnip and the system driver). Two `patches.py` cuts let the online client setup go on without the network (no online play).

## Files and shaders

- **Resolved: the PC split found no shader.** The paks are version 8 of Unreal Engine 4.22: the footer holds four compression method names (its magic 0xac bytes from the end, not 0xcc) and an entry's compression method index is one byte, so an entry is 50 bytes, not 53 (`dm/gfx/tools/uepak.py`). Unencrypted, Zlib. With that the split reads them: 16,963 shaders (12,561 pixel, 3,948 vertex, 451 compute, 3 geometry), the global cache's (`Engine/GlobalShaderCache-GLSL_SWITCH.bin`, each program a zlib stream of a GLSLC output) and the cooked packages'.

## Memory

- Provisional, the survey's: Unreal's 62 GB reservation as Kakarot's, the NPDM's 40 MB of system resource. `ue_malloc.py` does not cut this main's FMallocBinned3 (its name getter is vtable slot 17, a layout the host does not know), so the game's own allocator runs in the 64 GB alias region.

## Frame rate

- **Resolved: 60 fps fights ran twice as fast.** The J5 engine's Tick (`main+0xb4e00`) steps the battle (Bandai's CS framework, `CS::CSTickInfo` with a float step at +4) in fixed 1/60 s steps, as many a frame as the byte at engine+0x3d1 says: 2 in the shipped mode (the game's own 30/60 switch at `main+0x78150` writes 2 with a 30 limit, 1 with 60). Menus run on Unreal's delta time and were right. The profile's `fpslocker_plan` replaces the mode test (`main+0xb4f24`..`0xb4f38`) by a load of 60 / fps (`vsync_u32` row on the plan's data page) into the step count, and a `target_f32` row writes `t.MaxFPS` (the host's own write is off with rows). The rows follow the limiter, so `fps_limit 30` is the default and 60 a choice. Tablet: 59.7 fps, fights at normal speed and smooth; 30 keeps two steps a frame. 20 and 15 would take three and four steps; 45 and 40 are not offered by these rows (whole vsyncs only). 120 does not work: one 1/120 s step a frame (the data page held 120 / fps and 1 / fps, checked with `peek`) still ran fights twice as fast, since their move frames advance once a step whatever its length.

## Rendering

- **Resolved: black floors, streets and props, black blotches on characters.** Its streamed ASTC textures carry the SPARSE flag in ordinary CPU-uncached pools and get mip levels from the CPU after their first use; they were filled once and the later levels stayed zero blocks, which Adreno decodes as NaN ([SPARSE-flagged textures in ordinary pools](../../../docs/reference/nvn-objects.md#sparse-flagged-textures-in-ordinary-pools-vulkan-replay)). Profile keys `tex_flush_upload` and `tex_dirty_upload` cover the textures still empty at first use.

## Open

- Movies (MP4 through NintendoSDK_movie), CRIWARE sound and longer play not checked yet; frame rate beyond the first stage not measured.

## Found on this game

- The Unreal Engine 4.22 version 8 pak layout (above, Files and shaders).
- [SPARSE-flagged textures in ordinary pools](../../../docs/reference/nvn-objects.md#sparse-flagged-textures-in-ordinary-pools-vulkan-replay) (above, Rendering).
