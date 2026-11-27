# Metroid Prime Remastered (`010012101468C000`)

The base game 1.0.0 (v0), SDK 12.3.1, Retro Studios' own engine (RFRM forms, the engine of [Donkey Kong Country: Tropical Freeze](../0100C1F0051B6000/README.md) in a little-endian form), NVN. Modules `main`, `subsdk0` (the SDK's movie library) and `sdk`. `main` exports its own C++ names (`CGraphicsDeviceContext`, `NMaterialArchive`, `CSceneProxy` …), so its functions can be named from its dynamic symbols. RomFS is 341 files, 6.7 GB: `MaterialArchive.arc`, `MiscData.pak`, `StartupUI.pak`, `KernelMPRT.pak`, the preload packs under `Preload/` (`DuplicateData.pak` alone is 1.5 GB), `UI/MPR1/UIMP1.pak` and a `.pak` per room under `Worlds/`.

Brought up on the Mac: boot, the title, the save slot and difficulty menus, a new game's opening (the briefing, the gunship's approach) and the first room of the Frigate Orpheon. Played for about a minute on the Frigate's landing deck: walking, turning and firing, with the HUD, the hint texts and the lock-on markers. 60 fps (frame time p50 16.67 ms; the long frames are loads and first uses of pipelines), no red line but one (below); the audio renderer produces sound (71,573 of the first 72,000 rendered frames not silent; not listened to); memory levels off at about 4.3 GB resident and 2.6 GB of Metal allocations once the Frigate is loaded and stays there (frames 14,700 to 32,700). Not yet tested on the iPhone.

## What it needs

| Setting | Where | Why |
|---|---|---|
| 4 GB layout: `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | [`game.tsv`](game.tsv) | no virtual address memory: `nninitStartup` binary-searches its heap from 4.5 GB down and settles on 3.11 GB in the 3.25 GB heap region, as Tropical Freeze does ([memory.md](../../../docs/reference/memory.md#a-heap-request-beyond-the-heap-region-returns-resultoutofmemory)) |
| `mem_used 0x6600000` | `game.tsv` | the rewritten modules are larger than the default 64 MB of used memory |
| `sys_resource 0x0` | `game.tsv` | the NPDM declares 0 MB of system resource |
| `modules main subsdk0 sdk` | `game.tsv` | every module `main` imports from |
| `shader_groups` (`/Worlds/`, `/Preload/`, `/UI/`) | `game.tsv` | the shader split's groups ([Shaders](#shaders)) |
| [`dual_want.tsv`](dual_want.tsv) | profile | `ab809a7456f59614` writes colour output 1 that a pipeline blends as the second source |

No rendering key: what the game needed beyond the profile is general ([Found on this game](#found-on-this-game)).

## Shaders

- **Resolved: an empty split** (only the startup archive's 8 shaders). Retro's forms are little-endian here, a pack's resource list sits in a `TOCC` form with 52-byte entries, and four vertex stages carry GLSLC data past their control section's own length: [SNVN program records in Retro Studios' RFRM forms](../../../docs/reference/shaders.md#snvn-program-records-in-retro-studios-rfrm-forms). 40,483 shaders: 20,233 vertex, 20,233 pixel, 12 geometry and 5 compute, from `MaterialArchive.arc` (1,141 stages), the 778 `MTRL` resources of the packs and the startup archive in `main` (`main+0x1d0e7a4`, 8 stages: the title logo's quads); 20,233 vertex / pixel pairs. The split takes about 6 minutes (most of it reading `DuplicateData.pak`), the Mac libraries about 17.
- **Resolved: `[RED] shader … reflection conversion failed: … c0 dynamic index c0+0x630`** and `Shader instruction Pixld is not implemented` in the build, `nvnProgramSetSampleShading` unimplemented. The game shades per sample: 438 of `MaterialArchive.arc`'s 562 pixel shaders read the sample index (`PIXLD.MYINDEX`), and 30 of them look up GLSLC's sample position table in the driver segment and pack the position into an integer G-buffer target: [Pixel `c0 + 0x620`: the sample index and GLSLC's sample position table](../../../docs/reference/shaders.md#pixel-c0--0x620-the-sample-index-and-glslcs-sample-position-table). Every render target met so far is single-sample (1600×900 docked).

## Rendering

- **Resolved: the world black, the HUD drawn over it** (the gunship invisible in the opening, the Frigate's hull and rooms black in play; menus, text and effects fine). The scene target held the whole room after the opaque passes (`DM_NVN_TEXSNAP`), and a full-screen pass (`1b17e5a457135e3b`) turned it black: the volumetric fog, which samples a 100×57×64 RGBA16F froxel volume at each pixel's depth and blends it over the scene. The volume is filled by `1cbc19f806a08166`, 5,700 points drawn with rasterization off whose vertex shader writes the 3D image; the host skipped that draw because only storage buffer writes counted, so the volume stayed zero (colour × 0 + 0): [Rasterizer discard preserves vertex storage writes](../../../docs/reference/rendering.md#rasterizer-discard-preserves-vertex-storage-writes). The profile keys `tex_flush_upload` / `tex_dirty_upload` changed nothing (no CPU writes it: its guest memory stays zero), so the profile does not set them.
<!-- PEANUTS begin: Android -->
- **Resolved (Android): flat, opaque red / orange / white quads in the Frigate's interior** (the corridors of the escape, in place of the alarm holograms). Found live with `DM_VK_BISECT=1:<mask>:<value>` and screenshots scored by their share of saturated red pixels: pixel shader `5ae4aca83cf1f68e` (vertex shader `434f1dba99fe6cc1`); `DM_VK_SKIP_FS=5ae4aca83cf1f68e` removed every quad and nothing else. Dropping every geometry shader draw (`DM_VK_BISECT=2:0:0`) and listing 404 more keys in `dual_want.tsv` changed nothing. Its vertex shader reads the generic attributes at an index taken from a constant buffer (the material picks its UV set), so the translator declares them as one `vec4[16]` input at location 0, and the pixel shader its inputs likewise. The build's reflection counted such an array as location 0 alone: the library's `vin_mask` was `0x1`, the replay bound attribute 0 (the position) only, and attributes 1–15 read undefined values: constant UVs, one texel per quad, alpha = texel alpha². 3,858 of the game's 20,233 vertex shaders and 7,382 of its pixel shaders have such an array. `vk_android_build.py` now spreads an interface array over one location per element (`io_locations`: `vin`, the pixel shaders' inputs for the vertex shaders' padding, no pad over a location an output array covers); 7,391 vertex shaders are padded for their pixel shaders. The holograms draw with their text, diagrams and glowing edges (AYN Thor, Turnip).
<!-- PEANUTS end -->

## Current

- `vertex attribute format 0x27 (8 / 16-bit integer) feeds a float input` (RGBA8UI): converted on the CPU at replay, as for [Need for Speed: Hot Pursuit Remastered](../010029B0118E8000/README.md); no visible effect.
- A translucent cyan panel with a grid stands between two red lights where Samus lands, and fills the left of the view as she walks past it: drawn in the 3D scene, apparently one of the Frigate's holograms; not compared with the Switch.

<!-- PEANUTS begin: Android -->
## Android (AYN Thor)

Tested 2026-11-27 on an AYN Thor (Snapdragon 8 Gen 2, Adreno 740, Android 13) with the Android pack (40,483 of 40,483 shaders translated, every module valid for spirv-val) from the base game's `.nup`: the opening, the Frigate's landing deck and its interior up to the escape. 59–60 fps with either driver.

- **Turnip** (Mesa, a custom driver; driver ID 18): prepares the shader parts of every program before the game starts (graphics pipeline libraries, about 187 MB of cache), no pipeline refused, the picture right but for the points below.
- **Qualcomm's system driver** (driver ID 8, build of 12/27/23): no pipeline libraries, so pipelines are made while playing. Two pipelines with a geometry shader are refused with `-13` and their draws skipped (vertex `40ef539a4658f3f0` / geometry `7eff0a97951e2ec6`, vertex `3786bb45444ae151` / geometry `c478a95ad65aa880`). Seen there: stray lines in the opening, a garbled hologram panel, the top of the gunship as a red-green triangle mosaic, a purple glow at its engines. Seen before the interface array fix above, not tried again since.
- **Open on both drivers:**
  - **The volumetric fog has no light, and the interior is far too dark.** A playthrough of the Switch version shows the Frigate's interior under a dense, lit greenish haze that also lightens the arm cannon on its shadowed side; here the rooms, the enemies and the arm are near black and only emissive parts show. The chain, found live on the Thor: the fog pass `1b17e5a457135e3b` samples the 100×57×64 RGBA16F froxel volume, which holds (0, 0, 0, 1) everywhere (`DM_VK_TEX_STATS`); its writer `1cbc19f806a08166` runs (rasterizer discard, 5,700 points) and stores the texels of an 800×456 RGBA16F target (`DM_VK_TEX_PEEK`); that target has its density in alpha (87 % of the pixels, up to 0.09) and zero in RGB (`DM_VK_IMG_DUMP=800x456`); it is drawn by one full-screen quad with pixel shader `984bc2c990eefb6e`, whose 512×256×368 RGBA16F 3D light volume (texture flags 0x20) is almost empty: under 0.05 % of its texels above 1/1024, a few up to 23. The world's first pass (`f7800d296c68645b`) samples three more 3D textures of that size. These volumes live in a sparse virtual pool, which has no GPU side on Android (`[RED] virtual pool …: aliasing physical pool pages is not yet on Android`, `android/PORTING.md`): `vka_tex.c` gathers such a texture through the page table, and here it did so once, when the volume was made (`sparse texture serial … 512x256 layers 1 … format 0x50: … 46.0 MiB through staging, … 0.1 MiB of pages mapped behind them`: 1/8 of the level's 368 MiB, with 0.1 MiB mapped), and never again (no `its mappings or pages changed, re-uploaded` line in the run). `DM_VK_SPARSE_RES=0` changes nothing (2D images only). Open: why a 3D level goes through staging at 1/8 of its size, and how the game fills the volume afterwards (later mappings, CPU writes to the physical pages or GPU copies) without the replay noticing. Ruled out on the way: the light cluster grid (646×32 R32UI, one full-screen draw of `3cf7eb586defef44`; the game lists 1 to 4 lights a frame), the arm's textures, cube map, storage-buffer light records and vertex attributes.
  - Resolved: `[RED] … vertex shader … inputs 0x… (uint) … not retyped: no vec4 float input type` for 4 vertex shaders (`9b97258ddcf23736`, `88e6a2179535920a`, `52813a5f459313ae`, `099c3ff54bb28029`, the arm's among them), which the interface array fix above brought up: their bone indices (RGBA8UI) reached a `vec4[16]` input, which the replay's retyping (`vka_spv_vin_retype`) takes only as whole variables. `vk_android_spv.py` `split_input_arrays` now gives a vertex shader one input per element of such an array and fills a Private copy for the computed index; the line is gone. The arm stayed dark (above).
  - `[RED] … SRC1 blend factor with no dual-source module` in the interior: the second source reads as zero. `dual_want.tsv` names the two shaders a host has met; Android has no `DM_NVN_DUAL_WANT` to note more. No visible effect found.
  - `[RED] transform feedback from a geometry or tessellation stage: not captured` (`vka_tf.c`). No visible effect found.
<!-- PEANUTS end -->

## Mac routes

The work folder is `<NSP folder>/010012101468C000-work`: `host.sh` copies the worktree's `dm/host/dmhost` to `build/mprhost`; `run/boot.sh <log> [secs] VAR=value…` runs it on the NSP script's build (`010012101468C000_build`: the RomFS image, the rewritten modules, the shader libraries in `shader/cache`); `run/ctl.sh press|snap` drives a run started with `DM_CTL=run/ctl.txt`, and `run/at.sh <log> <frame> <ctl lines>…` sends control lines once the run passes a frame (for `memdump`, `texlist`, `passdump`). `tools/sym.py main <offsets>` names `main` offsets by its dynamic symbols; `tools/a64.py` disassembles.

- From a fresh save: `DM_KEYS=f1600:A,f2200:A,f2800:A,f3600:A,f4200:A`: the title (press A), the start of the menu, save slot 1 (New Game), difficulty (Normal), the save message; the briefing and the gunship's approach follow, and the game takes control on the Frigate Orpheon at about frame 12,000 (Samus's visor up, energy 99). Skipping the opening was not tried, and there is no save yet past the new file.

## Found on this game

General host and tool changes first needed here; each applies to every game:

- **Little-endian RFRM forms, `TOCC` resource lists and GLSLC's extra control data** (`rfrm_order`, `rfrm_shaders`, `snvn_records`): [shaders.md](../../../docs/reference/shaders.md#snvn-program-records-in-retro-studios-rfrm-forms).
- **The sample index (`PIXLD.MYINDEX`) and the pixel stage's sample position rows of `c0`** (translator, `SAMPLE_ROWS`, `c0_sample_rows`, `nvnProgramSetSampleShading`): [shaders.md](../../../docs/reference/shaders.md#pixel-c0--0x620-the-sample-index-and-glslcs-sample-position-table).
- **Image stores of vertex shaders drawn with rasterizer discard** (`vs_writes`): [rendering.md](../../../docs/reference/rendering.md#rasterizer-discard-preserves-vertex-storage-writes).
<!-- PEANUTS begin: Android -->
- **Interface arrays cover one location per element** (Android: `vk_android_build.py` `io_locations`, and `vk_android_spv.py` `split_input_arrays` for vertex inputs the replay retypes; moves the library version): [Rendering](#rendering), [Android](#android-ayn-thor).
<!-- PEANUTS end -->

## Open

- The iPhone: no IPA built or tested yet, and the pipeline prewarm list `pso_manifest.tsv` is still empty.
- Only the opening and the Frigate's landing deck have been played; the Frigate's interior, Tallon IV, the visors, the Morph Ball, bosses and the movies (`FMV0` resources, the SDK movie library in `subsdk0`) are untested.
- Sample shading and the sample position table were met only on single-sample targets; the table's nibble order for several samples is the hardware's, not seen in a game.
