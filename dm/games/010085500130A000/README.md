# LEGO CITY UNDERCOVER (`010085500130A000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.3 (v196608) over base 1.0.0. SDK 1.3.1, the oldest SDK of any profile here. TT Games' own engine (Nu2, with its "NuUbershader" shader system) on NVN. Modules `main`, `subsdk0` (NintendoSDK_NVN), `subsdk1` (GLSLC, NVN's run-time GLSL compiler, which `main` imports `glslcCompile` from), `subsdk2` (nn::sf), `subsdk4` (NintendoSDK_movie) and `sdk`. `subsdk3` (libcurl) is not loaded: `main` takes nothing from it that the host does not supply.

**Status (current): plays on Android, with sound.** On an AYN Thor (Turnip 27.0.0 R2) it boots through the loading posters and the title screen, the menus, the opening movie (H.264, MP4) and into the first mission in Auburn, and draws it right (the black and noisy textures are fixed, below). The frame rate: 30 fps as the game asks (present interval 2) by default; the profile offers the launcher's frame limit as experimental (`fps_limit_present`, up to 120 with `fps_limit_max`), since the engine steps by measured frame time (`GameFramework::Process` → `FrameworkEnvironment::SetFrameTime`). Measured: 60 fps on the menus and 45 to 50 in Auburn, where the GPU takes about 19 ms a frame; at 120 (the phone's display set to 120 Hz) the title screen runs 66 to 68 fps, GPU-bound, and the loading posters 118; 15 holds. The game's speed away from 30 has not been measured. The sound plays: the host's audio renderer service mixes the title and menu music, the movies' sound and the game's sound effects (the first minute of loading posters is silent, as the game makes no voice until it has loaded). Only Turnip was run. The profile comes from `dm/games/profile_guess.py` and the survey; its memory layout is still that guess.

## Builds

| Module | Build ID |
|---|---|
| `main` | `32c590b06495654696eb5f2bdfadddd0` |
| `subsdk0` | `3612d66d30dbd18d6cce211138c88eec` |
| `subsdk1` | `45e9265eeb138d2b4428df0c5f8ff88d` |
| `subsdk2` | `8a052b6dfd9b1515a85882ad8699869d` |
| `subsdk4` | `ad84f543dad22836a94c58daae35ea3a` |
| `sdk` | `9f841a6521ca02a184ce694c3e242519` |

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | the survey's guess: `main` sets a heap of at most the pool − 64 MB; not measured |
| `sys_resource 0x0` | `game.tsv` | no system resource in the NPDM |
| `modules main subsdk0 subsdk1 subsdk2 subsdk4 sdk` | `game.tsv` | every module `main` imports from |
| `shader_groups /MOVIES/=movies` | `game.tsv` | the survey's guess; every DAT shader lands in the `other` group |

## Files and shaders

- RomFS: `GAME.DAT`, `GAME0.DAT`, `GAME1.DAT`, `GAME2.DAT` (base, 8.5 GB) and the update's `PATCH.DAT`, plus 12 `.mp4` movies under `MOVIES/MP4`. The DAT archives hold 75,792 files (14.7 GB decoded), most of them in RFPK chunks ([BNSH files in TT Games' DAT archives](../../../docs/reference/shaders.md#bnsh-files-in-tt-games-dat-archives)).
- The shaders are BNSH files of one stage each (a vertex shader, then its pixel shader) inside the `.nnx_shaders` resources, one per level, cutscene and object set. `shader_split.py` found 24,393 shaders: 22,283 pixel, 2,110 vertex. 23,731 came from the DATs and 662 from `main`'s GLSLC outputs (331 in each of the base and update `main`). It found no variation pairs, since each BNSH holds one stage; no `[RED]` line.
- The engine also compiles shaders its caches lack at run time (`NuUbershaderBytecodeCache::CompileShader` through `glslcCompile`). If the game reports keys missing from the shader map, they would need a GLSLC capture.

## Rewrite census

From the survey, before any rewrite:

- `main`: 3,532,089 instructions, 18 undecodable, 3,810 `x18` uses.
- `sdk`: 735,676 instructions, 11 undecodable, 4,999 `x18` uses, 104 `svc`, 47 `mrs tpidrro_el0`, 33 `mrs cntpct_el0`.
- `subsdk1` (GLSLC): 1,899,833 instructions, 4,659 `x18` uses; `subsdk4` (movie): 769,605 instructions, 6,916 `x18` uses.

## Open

- **Open: the first loading takes about a minute** (the loading posters, silent) before the title screen; not looked into.
- **Open: game speed away from 30 fps** not measured (the engine steps by measured frame time, so it should hold).
- **Open: the memory layout** is the survey's guess (`profile_guess.py`), not measured.
- **Open: only Turnip was run** (27.0.0 R2 on an AYN Thor), not the system driver.

## For the next TT Games title

The other LEGO games on the Switch run on the same engine (Nu2: `NuSoundNNX`, `NuUbershader`, DAT archives), so these are where to look first:

- **Shaders:** the split reads TT Games' DAT archives (`.CC40TAD` index, RFPK and ZIPX chunks) and finds the single-stage BNSH files in `.nnx_shaders` resources ([BNSH files in TT Games' DAT archives](../../../docs/reference/shaders.md#bnsh-files-in-tt-games-dat-archives)). `main` also carries GLSLC output (and may compile at run time through `subsdk1`'s `glslcCompile`).
- **Sound:** an early SDK (1.3.1 here) has no audio renderer in `sdk`; the host serves `audren:u` and mixes ([An SDK with no renderer of its own](../../../docs/reference/audio.md#an-sdk-with-no-renderer-of-its-own)). A boot that stops under `GetAudioRendererWorkBufferSize` with `[RED] sf: … audren:u` is this. The first minute of loading is silent by design.
- **Black screen, no `[RED]` for shaders:** texture uploads from the single 2 GiB pool ([A buffer ↔ texture copy names only the bytes it copies](../../../docs/reference/memory.md#a-buffer--texture-copy-names-only-the-bytes-it-copies-android)).
- **Black, noisy or shifted textures, cut menu boxes:** the texture heap defragmenter moves textures with GPU buffer copies, at load and in play ([A buffer → texture copy's texels in the texture's storage](../../../docs/reference/rendering.md#a-buffer--texture-copys-texels-in-the-textures-storage-android), [A copy made twice in a texture's first upload](../../../docs/reference/rendering.md#a-copy-made-twice-in-a-textures-first-upload-android)). To tell a texture's content from its storage: `DM_VK_IMG_DUMP=<w>x<h>,<VkFormat>` with the ctl `screenshot` dumps the image, the ctl `memdump <address> <bytes> <file>` the storage (its address from `DM_VK_RT_LOG=<w>x<h>`'s texture lines); compare them deswizzled before suspecting scissors or blending.
- **Movies:** SDK 1.3.1's `movie::Buffer` getters have no `const` ([SDK 1.3.1's movie::Buffer getters without const](../../../docs/reference/movies.md#sdk-131s-moviebuffer-getters-without-const)).
- **Frame rate:** the engine steps by measured frame time, so the launcher's frame limit can be offered (`fps_limit_present`).


## Resolved

- **Resolved: abort at boot in the audio renderer setup.** `[RED] sf: the game connects to service audren:u itself, which the host does not serve`, then `Break#1 reason=0` under `nn::audio::GetAudioRendererWorkBufferSize` (`sdk+0x1612bc`) → `CreateAudioRendererManagerForAppletByHipc` (`sdk+0x1642c0`), from `NuSoundNNX::InitAudioDevice` (`main+0x4bc070`). SDK 1.3.1's `sdk` has no renderer of its own to run on the CPU; the host now serves the renderer service ([An SDK with no renderer of its own](../../../docs/reference/audio.md#an-sdk-with-no-renderer-of-its-own)). A first stopgap that answered the renderer calls with nothing got the game past the abort to a black screen.
- **Resolved: black screen after the sound setup.** Every frame was black (11 draws, then 7, then 1 as menus were confirmed blind) because no texture reached the GPU: the game uploads its textures with `CopyBufferToTexture` from its single 2 GiB memory pool, and each copy named its source with no length, which the host read as the rest of the pool (272 MiB over 13 placed parts, more than one join takes), so every copy across two parts failed (`[RED] vk: buffer → texture copy source … is not in placed guest RAM`). Fixed in general ([A buffer ↔ texture copy names only the bytes it copies](../../../docs/reference/memory.md#a-buffer--texture-copy-names-only-the-bytes-it-copies-android)).
- **Resolved: black and noisy textures** (Chase McCain's torso print, the police car, hands and props, first seen in Auburn). The game defragments its texture heap with GPU buffer copies, and a texture filled by `CopyBufferToTexture` had its texels only in the host's image, not in its storage, so the moved bytes were stale ([A buffer → texture copy's texels in the texture's storage](../../../docs/reference/rendering.md#a-buffer--texture-copys-texels-in-the-textures-storage-android)). Placing the pool whole (`DM_VK_PLACE_PART_MB=0`) and hashing pool content at every use (`DM_NVN_DIRTYCHECK=1`) changed nothing, which ruled out the pool's placement and the CPU-write tracking.
- **Resolved: menu boxes cut short** (Options, Select Slot: boxes ending in the middle of their text with straight vertical edges, at the same places on every run). The texture heap defragmenter moves runs of menu textures up in 16 KiB copies at load, in an earlier part of the submission than the upload of the texture made at the new place, which made every copy a second time ([A copy made twice in a texture's first upload](../../../docs/reference/rendering.md#a-copy-made-twice-in-a-textures-first-upload-android)). Not clipping: no scissor, viewport or alpha test cut them.
- **Resolved: no sound.** The host's first answer to SDK 1.3.1's renderer service reported the voices' progress and mixed nothing; it now mixes them ([An SDK with no renderer of its own](../../../docs/reference/audio.md#an-sdk-with-no-renderer-of-its-own)): the movie's PCM16 voices and the game's DSP-ADPCM sound effects play, with the voices' low-pass biquads.
- **Resolved: abort when the opening movie starts.** `FATAL movie: missing SDK symbol _ZNK5movie6Buffer4DataEv`: SDK 1.3.1's movie module declares `movie::Buffer`'s getters without `const` ([SDK 1.3.1's movie::Buffer getters without const](../../../docs/reference/movies.md#sdk-131s-moviebuffer-getters-without-const)).

## Found on this game

- [BNSH files in TT Games' DAT archives](../../../docs/reference/shaders.md#bnsh-files-in-tt-games-dat-archives): the split's DAT reader (`dm/gfx/tools/ttdat.py`).
- [An SDK with no renderer of its own](../../../docs/reference/audio.md#an-sdk-with-no-renderer-of-its-own): the host serves the audio renderer service and mixes its voices.
- [A buffer ↔ texture copy names only the bytes it copies](../../../docs/reference/memory.md#a-buffer--texture-copy-names-only-the-bytes-it-copies-android): the black screen.
- [A copy made twice in a texture's first upload](../../../docs/reference/rendering.md#a-copy-made-twice-in-a-textures-first-upload-android): the cut-short menu boxes.
- [SDK 1.3.1's movie::Buffer getters without const](../../../docs/reference/movies.md#sdk-131s-moviebuffer-getters-without-const): the opening movie.
- [A buffer → texture copy's texels in the texture's storage](../../../docs/reference/rendering.md#a-buffer--texture-copys-texels-in-the-textures-storage-android): the black and noisy textures.
