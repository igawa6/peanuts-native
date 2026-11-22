# Hogwarts Legacy (`0100F7E00C70E000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.5 (v327680) over the base game 1.0.0 (v0), SDK 17.5.4. Avalanche Software / Warner Bros. Games, Unreal Engine 4.27.2 (`EpicGames+UnrealEngine-4_27_2` in `main`; the desktop renderer, `SF_GLSL_SWITCH` shaders), NVN, Wwise sound, Bink 2 movies (`.bk2`). Modules `main`, `subsdk0` (NintendoSDK_movie, which `main` imports from) and `sdk`. Five add-on content packages (the pre-order and edition items: `0100F7E00C70F001` to `F005`), each a single small data file.

**Status (current): boots to gameplay on Android (AYN Odin 2, Adreno 740, Turnip Gen8 V38), 30 fps.** Title, character select and a save's gameplay at 30 fps, GPU 11-17 ms. The world was black until the [material texture fix](#rendering) (a Vulkan chain change: the shaders are made again). The memory layout is still the survey's guess; no Mac run. The shader tables are the update's, made on a PC (below).

## Rendering

- **Resolved: the world black (NaN) behind the HUD.** Unreal's material textures are type-1 handles: the texture word in the material's uniform buffer (c4-c10, arrays of 32 indexed by the draw's primitive), the separate sampler in c2. The library left 139,302 such bindings (nearly every material shader) without a slot, so they read 0: normal maps of 0 gave NaN in the forward base pass (46-52 % of the scene colour; first in `36310803131ac0b6`, `f61fc44ee5ab4906`'s pixel shader), and the tonemapped picture was black. The character-select screen's black quads (flying pages) were the same. [Handles in a game uniform buffer with their sampler in c2](../../../docs/reference/shaders.md#handles-in-a-game-uniform-buffer-with-their-sampler-in-c2-android). Both drivers showed it.
- **Resolved: bursts of wrong surfaces with `[RED] vk: more than 512 texel buffer views at once`.** The forward lighting reads its light grid through texel buffers in every lit draw. [The texel buffer view table filled up](../../../docs/reference/rendering.md#the-texel-buffer-view-table-filled-up-android).
- **Resolved: 13-14 fps after the first spell and in cutscenes** (the replay's recording 72 ms a frame, the GPU at 13 ms, about 69,000 minor page faults a frame): every lit draw's light grid texel buffers were copied again for each binding. [A texel buffer copied for every binding](../../../docs/reference/rendering.md#a-texel-buffer-copied-for-every-binding-android). Measured on the Odin 2, Turnip Gen8 V38: the Basic Cast tutorial at the barrier 13.4 fps (recording 72.7 ms) before, 30.0 fps (recording 6.4-9.6 ms) after; the cliff cutscene with Fig 12.8 fps before, 30 after; frames over 50 / 100 ms after the first minute 361 / 96 in 11,700 frames before, 30 / 4 in 18,600 after (those in a load).
- **Resolved: dark 8×8-pixel blocks** in rows on mid-distance rock, cliffs and walls (and on the player's jacket): the forward lighting's per-light shadow map was taken from a descriptor array at a per-pixel index without `NonUniform`, so each 8×8 Adreno fragment wave read one pixel's shadow map. [A descriptor array read at a per-pixel index without NonUniform](../../../docs/reference/shaders.md#a-descriptor-array-read-at-a-per-pixel-index-without-nonuniform-android). Found by an image dump (the blocks are in the shadow mask, target serial 32126, B8G8R8A8, drawn by `f80a82b5ea3cbaa6`) and `DM_VK_SKIP_FS`; not the 32-element handle cap (the shadow map indices were 1-13), the texel buffer copies or LRZ.
- **Resolved: plants, grass and ground flickering black** for single frames (the cliff talk with Fig and the path after it), a fault of the texel buffer copy kept per submission (above): [A texel buffer's copy bound again after its submission](../../../docs/reference/rendering.md#a-texel-buffers-copy-bound-again-after-its-submission-android). AYN Thor, cliff talk, 1,500 frames: 27 flicker frames before, 0 after. Not yet counted on the Odin 2 with the fix (186 before; 0 with `DM_VK_TBUF_COPY=0`).
- **Resolved: a stone slab and ledge plants black for one frame** when they first come into view on the cliff path after the talk with Fig (AYN Thor: the slab in 5 of 5 walks, 0 of 5 after): their textures, read through the texture pool since the handle tables are, got their pool slots a frame late. A draw now makes the first 32 textures of a table it reads. [A table of handles longer than the translator's 32-element array](../../../docs/reference/shaders.md#a-table-of-handles-longer-than-the-translators-32-element-array-android), its "Also".
- **Resolved: a spell's effect left on screen as a bright disc, and the auto exposure flickering white and dark** after it: the Niagara particle simulation `c8974ea070ce1208` lost its 55 particle-data image accesses in translation (the handle spilled to local memory), so its particles never aged or died. [A bindless handle spilled to local memory](../../../docs/reference/shaders.md#a-bindless-handle-spilled-to-local-memory).
- **Open: Qualcomm's driver** (Odin 2, driver build 1a285a84ae): the world is black as on Turnip before the fix, and a few seconds into gameplay the process faulted in `vulkan.adreno.so+0x125ec8` (null + 0x10). Not looked into.
- **Open: the low sea mist is missing at the cliff outside the first cave**, and a small static green patch shows on the tower ruin there. The fog volume the scene samples has the right transmittance per slice (compared with a PC capture of an Eden-based emulator); its scattered light is 2-7 times lower. Not found.
- **Open:** the two shaders the library lacks (`more than 64 bindings in one stage`, e.g. `6d69c07dd8606f7e`); which passes use them is not known. The profile's delta manifest (`vk_delta.tsv`, version `31ab5778a1f0`, from a full build off the device) lists them as not built, so the phone's shaders step is not stopped by them; a phone with the library before the local-memory handle fix translates 105 shaders.

## Sound

- **Resolved: voices and music muffled.** Every hardware Opus stream decoded 15-20 dB low with three of four CELT bands nearly gone: the `sdk`'s libopus read `main`'s libopus tables of the same names. [The sdk's Opus decoder read the game's libopus tables](../../../docs/reference/audio.md#the-sdks-opus-decoder-read-the-games-libopus-tables). The same line of Fig's on the cliff, device output against an Eden-based emulator on the same Odin: 8-12 kHz -16.0 dB and 12-16 kHz -16.9 dB before, +2.2 / +2.2 dB after; 250-500 Hz -6.6 dB before, -0.6 dB after.
- **Resolved with it: the title theme sounded missing** (only a thin layer at -50 to -60 dBFS against an Eden-based emulator's -30 dBFS): the same Opus loss. After the fix the title is at -27 to -45 dBFS with the low end the Eden-based emulator has (20-250 Hz +2.9 dB re 0.5-1 kHz; that emulator -0.9 dB).

## Builds

| Version | Module | Build ID |
|---|---|---|
| 1.0.5 (v327680) | `main` | `0c00fc37b0610fbdb2cf73aed0cc73e7` |
| | `subsdk0` | `d0c48eef6dc2f1e7ac8b8dd5b5b03a60` |
| | `sdk` | `1ea0b9fe6cd6867b18e374a5d53d7950` |
| 1.0.0 (v0) | `main` | `70658a5c238d69c24698fded6a34efa6` |
| | `subsdk0` | `7c1a58cac01c4aa3935c6243f69cf0d3` |
| | `sdk` | `835c229763ce5c1816a70f875bd70ccd` |

The display versions are the control data's. The base game's RomFS (7 GB) lacks the update's `pakchunk9`, `pakchunk10` and `pakchunk11` paks, and its shader code archive is another one (65,954 game shaders, not decoded); the tables are of 1.0.5 only (`shader_versions v327680`), so a dump without the update stops at Prepare's shaders check.

## What it needs

| Setting | Where | Why |
|---|---|---|
| Engine region given what the alias region has room for: gate `OBS_UE_REGION` on the store of its size, `main+0x19ed298` (`nninitStartup` `main+0x19ecfc4`, the reservation at `+0x19ed29c`) | the rewriter's engine-wide rule (`dm/tools/ue_region.py`), no build ID | Unreal reserves 62 GB by default |
| Node area cut 1 GB → 62 MB (`sdk+0x139468` / `+0x1394fc`) and regions of 4 MB and more aligned to 2 MB (`sdk+0x139654`) | `dm/tools/sdk_patches.py` function-matched rules | matched by the rewriter on this `sdk` build |
| 64 GB alias region: `mem_reserve 0x1028000000`, `mem_alias 0x1000000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | the survey's layout for an Unreal game whose allocator is not cut (below): its pools take 37 GB of the engine region. Address space only on Android; not measured |
| `sys_resource 0x1000000` | `game.tsv` | the NPDM declares 16 MB of system resource |
| `pad_layout full` | `game.tsv` | a 3D game with a free camera on the right stick |

## Memory

- **Open: Unreal's FMallocBinned3 is not cut.** `dm/tools/ue_malloc.py` looks for the UTF-16 name `Binned3`; this build's `TCHAR` is 8-bit, and `main` has the name only in ASCII (`main+0x7aeac6c`). Taken with an 8-bit name, the rest of the chain holds (one getter, one vtable slot, one constructor, one call site), but the getter is slot 23 of the vtable (`main+0x8f313c0`), a slot layout `hle_uemalloc.c` does not know (19 for 4.26 / 4.27, 22 for UE 5, 26 for 5.6). So Unreal's own allocator runs and reserves its pools in the engine region, which is why the alias region is 64 GB. Mapping slot 23's layout (four slots more than stock 4.27) would let the host run Unreal's heap as on the other Unreal games.
- Not measured: the engine region's high-water mark, the app's footprint on a 16 GB phone.

## Files and shaders

- RomFS of 1.0.5: 200 files, 13.2 GB: twelve version 11 paks (`Phoenix/Content/Paks/pakchunk*-Switch.pak`, indexes not encrypted, files Zlib or Oodle in blocks) with an IoStore container beside each (`.utoc` / `.ucas`, the cooked packages), 140 Bink movies, splash images.
- Shaders: four shader code archives, stored in the paks: `ShaderArchive-Global-SF_GLSL_SWITCH` (3,068 shaders) and `ShaderArchive-Phoenix_Chunk0-SF_GLSL_SWITCH` (47,670) in `pakchunk0`, `_Chunk9` (51) in `pakchunk9`, `_Chunk10` (30,283) in `pakchunk10`. Every shader in them is Oodle Kraken (block header `8C 06`). `Engine/GlobalShaderCache-SF_GLSL_SWITCH.bin` (Oodle in the pak) is not read.
- `shader_split.py` found 65,047 unique shaders of the 81,072: 21,091 vertex, 38,350 pixel, 5,597 compute, 9 geometry (game 61,979, global 3,068), and no `[RED]` line. Every vertex shader is made to link with any pixel shader (`separable_pairs`).
- **How the tables were made:** on a PC, with the open-source ooz decoder (`unoodle`, [dm/third_party/ooz](../../third_party/ooz/README.md)) instead of the game's own Oodle: `shader_split.py` with `DM_UE_DECODE` and `DM_OODLE_OOZ` in an x86-64 Linux container, on the update's RomFS and `main`. All 81,072 streams decoded to their raw sizes. ooz's output has no checksum to check it against; the check is the phone's: Prepare decodes the same archives with the game's own `OodleLZ_Decompress` (`ue_ondevice.py`; found in `main` at `0x9c7f0` by its profiler string) and keeps its targeted split only when the tables come out the same byte for byte. A difference stops Prepare at "shaders differ" and means ooz decoded something wrongly: the tables must then be made again from a phone's split.

## Rewrite census

From the survey, before any rewrite: `main` 31,608,846 instructions (120.6 MB of text), 2,454 undecodable, 78,173 `x18` uses, 134 `mrs tpidr_el0`; `sdk` 1,547,469 instructions, 60 `svc`, 40 `mrs cntpct_el0`; `subsdk0` 868,466 instructions. The Android rewrite on a PC took about 10 minutes for `main` (its `.eh_frame` covers only part of the text: 39 ranges of up to 45 MB without unwind entries are analysed in pieces).

## Open

- The allocator (above), and everything at run time: boot, services (`nn::ro` is imported, with no NRO in the RomFS; `nn::ec`, `nn::web`, `nn::ldn`, `nn::friends`), Wwise audio, Bink movies, saves, frame rate.
- The nine geometry shaders: on DRAGON QUEST VII Reimagined (Unreal 4.27) most geometry shaders did not build and their draws are skipped ([its note](../0100A9D01C446000/README.md#rendering)).
- No companion for this game.

## Found on this game

- [Handles in a game uniform buffer with their sampler in c2](../../../docs/reference/shaders.md#handles-in-a-game-uniform-buffer-with-their-sampler-in-c2-android)
- [The texel buffer view table filled up](../../../docs/reference/rendering.md#the-texel-buffer-view-table-filled-up-android)
- [A descriptor array read at a per-pixel index without NonUniform](../../../docs/reference/shaders.md#a-descriptor-array-read-at-a-per-pixel-index-without-nonuniform-android)
- [A texel buffer copied for every binding](../../../docs/reference/rendering.md#a-texel-buffer-copied-for-every-binding-android)
- [A texel buffer's copy bound again after its submission](../../../docs/reference/rendering.md#a-texel-buffers-copy-bound-again-after-its-submission-android)
- [The sdk's Opus decoder read the game's libopus tables](../../../docs/reference/audio.md#the-sdks-opus-decoder-read-the-games-libopus-tables)
- [A table of handles longer than the translator's 32-element array](../../../docs/reference/shaders.md#a-table-of-handles-longer-than-the-translators-32-element-array-android)
- [A bindless handle spilled to local memory](../../../docs/reference/shaders.md#a-bindless-handle-spilled-to-local-memory)
