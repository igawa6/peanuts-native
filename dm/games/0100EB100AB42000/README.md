<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has no profile for this title). -->
# FINAL FANTASY XII THE ZODIAC AGE (`0100EB100AB42000`)

Base game 1.0.0 (`v0`), SDK 6.4.0. Square Enix / Virtuos PhyreEngine, NVN. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The closest profiles are [FINAL FANTASY X/X-2 HD Remaster](../0100BC300CB48000/README.md) (the same Virtuos `SRYK` archives, SDK 5.4.0), [ASTRAL CHAIN](../01007300020FA000/README.md) (SDK 6.4.0) and [Fire Emblem: Three Houses](../010055D009F78000/README.md) (SDK 6.5.0).

**Status: provisional. Packed from the NSP's `.nup` on a PC and played on one handheld, an AYN Thor (Adreno 740), on two drivers: Turnip v27.0.0 and the system driver (Qualcomm 512.676).** On Turnip the game boots to the frame loop, plays sound and its movies, draws its menus and the opening 3D scene, and saves and loads. On the system driver the scene is tinted orange-brown with black patches ([Open](#open), B12). Only the base game is packed, and nothing here was run on a Mac. The profile is the survey's (`scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py`): its keys are not measured. The game is not in any list of supported games.

**This note describes the profile with two host fixes still pending in separate changes, neither of them part of it:** one for NVN1 textures the CPU wrote before their first sample (B3) and one for the pipelines the Qualcomm driver refuses (B10). Everything below that says "pending" was measured with those changes applied on the phone and is not in the repository yet. Without them the target circles and lines do not draw on either driver, and the system driver refuses pipelines ([Open](#open), B3 and B10).

## Builds

| Module | Build ID |
|---|---|
| `main` | `598723c7a3d439356b5efb2f8bbcb807` |
| `subsdk0` | `b2bfdeddd143de2d821031fe668d097b` |
| `sdk` | `e29be8ad6778527aa248310d0c71a8a5` |
| `rtld` | `09aa0f157b8cf44df373510612a0452a` |

The display version is the base game's control data (`control.nacp` +0x3060): 1.0.0, `v0`. Only the base game is packed: the profile's `build`, `package` and `shader_versions` rows all say `v0`. A code update NSP, v1.1.0, exists and is not packed (see [Open](#open), B9).

## What plays

Measured on the Thor, base game, Turnip unless it says otherwise:

- Boots to the frame loop at about 30 frames a second (the game sets present interval 2); the GPU takes 2 to 8 ms a frame. The menus run at 60.
- Sound; the opening AVC movie, drawn as the game draws it (the game itself covers 11 px at the top and 115 px at the bottom with black bars, and an emulator shows the same layout).
- The Config screen on a black background, which is how the game looks on its first run; the pause menu with its background, the mini map, the save crystal, the save list and the UI, once `dual_want.tsv` is in the library (see below).
- Gameplay: the 3D scene of the opening area with the player characters and the tutorial text, and controller input.
- Saving and loading: a save wrote `FFXII_000` (4,683 bytes, two transactional commits, no file-system error), the save list showed it, and loading it back worked. The other slots read "Unused file", the game's normal look.
- With the pending changes applied: no refused pipeline on either driver, the target circles and lines draw on both (the user confirmed it on Turnip, and on the system driver the intro shows and the rings draw), 263 pipelines on Turnip and a good picture.

What does not, or is not known to:

- **B12 (open):** on the system driver the 3D scene is tinted orange-brown with black patches on the walls ([Open](#open)).
- The profile covers the base game v0 only; the update NSP v1.1.0 is not packed.
- `[RED] BeginRecording re-entered` is printed once a frame from boot and is not understood (B8).

## What is guessed

| Setting | Where | Why it is a guess |
|---|---|---|
| `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | [`game.tsv`](game.tsv) | `profile_guess.py`'s rule for a game whose NPDM declares no system resource and whose `main` sets a heap of at most the pool − 64 MB: a 3328 MB heap region. FINAL FANTASY X/X-2 HD Remaster, the same engine, runs with these same values, measured there. The game boots and plays on the Thor with them; no heap or memory figure was recorded here, and the heap check that would confirm it on a Mac was not run |
| `sys_resource 0x0` | `game.tsv` | read from the NPDM: it declares no system resource (`IsVirtualAddressMemoryEnabled` is false), not a guess |
| `modules main subsdk0 sdk` | `game.tsv` | every module `main` imports from; `subsdk0` is the movie module |
| `romfs_marker FFXII_TZA.vbf`, `zip_store .mkv:.vbf:.metallib` | `game.tsv` | the rule's: the largest RomFS file, the big file types |
| No `mem_used` | | the provisional profile has none; the NSP script computes it from the rewritten modules after the rewrite (`refine_after_rewrite`, `profile_guess.mem_used`). FINAL FANTASY X/X-2 HD Remaster needs `mem_used 0x8000000` for the same reason: its rewritten modules exceed the default 64 MB |
| No `depth_mode`, no `present_interval` | | the game ran without them and sets its own present interval (2, so about 30 fps); whether either key would change anything was not tried |

## Files and shaders

- RomFS: 127 files, 12.58 GB: `FFXII_TZA.vbf` at the root (10.58 GB, `SRYK`), 114 files under `gamedata/nvn` (1.99 GB: 72 loose `.fpo` Phyre programs, 40 `.mkv` movies and 2 `.txt`), and 12 `.fst` files under `filesizetables/nx`.
- The archive's 64 KB blocks are raw LZ4, not the zlib of FINAL FANTASY X/X-2 HD Remaster: the split reads it because `dm/gfx/tools/vbf.py` has the LZ4 fallback ([Phyre NVN effects in Virtuos VBF archives](../../../docs/reference/shaders.md#phyre-nvn-effects-in-virtuos-vbf-archives)). Without it the split dies on the archive and finds 3,100 shaders (`main` and the 72 loose programs) instead of 6,454.
- `shader_split.py` found 6,454 programs: 84 geometry, 5,999 pixel, 371 vertex, no compute. By source: 3,403 in the `gamedata` files, 3,051 in `main`. `shader_pairs.tsv` has 742 pairs: each of the 371 single-stage vertex shaders is paired with a cover of two pixel shaders (`003ce6f0fbd07b59`, `0004addcf11aa82b`).
- The split prints no `[RED]` line. `main` embeds 23 offline programs that no relocation points at and whose code builds no address for: 7 aligned control sections with their code area directly before them (`main` +`0xfc4dec`, `+0xfc732c`, `+0xfc9ab4`, `+0xfce4ac`, `+0xfd0d40`, `+0xfd34a4`, `+0xfd5e08`: 5 vertex, 2 pixel) and 16 at offsets that are not 4-byte aligned (`+0xfc4293`, `+0xfc5b45`, `+0xfc679e`, `+0xfc7d85` vertex; `+0xfc87de`, `+0xfc914f`, `+0xfca50d`, `+0xfcee05`, `+0xfcf85e`, `+0xfd02cf`, `+0xfd1699`, `+0xfd1ff2`, `+0xfd2a4b`, `+0xfd3efd`, `+0xfd4956`, `+0xfd53af` pixel). `shader_split.py` `raw_shaders` takes all 23 ([Embedded programs in main that no code points at](../../../docs/reference/shaders.md#embedded-programs-in-main-that-no-code-points-at-adjacent-code-area-unaligned-control-section)). Without that, the first run on the phone drew a black picture with sound and movies working, and the log named 7 keys missing from the library.
- The Android library built from these tables holds 9,305 modules (the 74 dual-source modules of `dual_want.tsv` among them), and all of them pass `spirv-val --target-env vulkan1.3`. The pipelines the host rewrites at run time were not checked that way.
- `shader_facts.tsv`, `bf_want.tsv` and `pso_manifest.tsv` have no rows (no PC shader pairing supplied, no compute shader, no prewarm recorded), and neither does `pso_pairs.tsv`. No `patches.py`.
- [`dual_want.tsv`](dual_want.tsv) lists 74 pixel shaders, one 16-hex key per line: those of the library that write exactly colours 0 and 1, all 72 loose `gamedata/nvn/branch_shader` `.fpo` programs, the SMAA effect pixel shader and one embedded `main` program, found by a census of the library's `index.json`. `dmsh_build.py` and `vk_android_build.py` build a dual-source module (`MOD_DUAL`) for each, which the Android host needs for a pipeline that blends with a `SRC1` factor; without one it prints `[RED] vk: a SRC1 blend factor with no dual-source module` and reads the second blend source as zero. The effect was measured on the Thor (Turnip): with the 74 modules the line is gone and the pause and menu backgrounds, the mini map, the save crystal, the save list and the UI draw, and without them they did not. The census is not play-driven: the other 2,265 shaders that write colours 0 and 1 (Phyre default lit and character effects) are not listed, and whether the background still lacks something was not measured. The file's own header comment still calls it an experiment whose effect is not measured; it predates that run, and it is left as it is because its bytes are part of the shader library's stamp. Adding or changing the file changes the profile's shader inputs digest, so the shader library and the APK have to be rebuilt for it to take effect.

## Rewrite census

From the survey, before any rewrite:

- `main`: 2,484,828 instructions, 360 undecodable words, 11,208 `x18` uses.
- `sdk`: 1,338,984 instructions, 268 undecodable words, 10,812 `x18` uses, 60 `svc`, 54 `mrs tpidrro_el0`, 32 `mrs cntpct_el0`.
- `subsdk0`: 908,040 instructions, 316 undecodable words, 10,362 `x18` uses.

## Found on this game

- [Phyre NVN effects in Virtuos VBF archives](../../../docs/reference/shaders.md#phyre-nvn-effects-in-virtuos-vbf-archives): this archive compresses its blocks with LZ4, which `vbf.py` did not read before.
- [Embedded programs in main that no code points at](../../../docs/reference/shaders.md#embedded-programs-in-main-that-no-code-points-at-adjacent-code-area-unaligned-control-section): `main` embeds 23 offline programs that no relocation slot points at and whose code builds no address for, 7 aligned with their code area directly before the control section and 16 at unaligned offsets. Until `raw_shaders` took them the shader library lacked all 23, and the first phone run drew a black picture (sound and movies worked) with these 7 keys reported missing from the library: `59241958cbf8ccf2` `f8052db57514646c` `16246f6e2c51b7b2` `05bead59ceb8faaf` `4a721837b4313fd8` `6db47b250e545e02` `3933ce26c4dbd8eb`.
- Two more findings of this game are in changes still pending, so they have no reference entry here yet: the NVN1 host rule for storage the CPU wrote before a texture's first sample (B3), and the dual-source output the Qualcomm driver refuses (B10). Both are described under Open.

## Open

Numbers in brackets are the bring-up's bug numbers.

- **B12, the system driver draws the 3D scene in the wrong colours.** Measured at the Attack target list: the walls and the floor, blue-grey and teal on Turnip, read orange-brown, and black rectangular patches sit on the walls. The UI, the mini map and the target rings are right, and that run had no refused pipeline and no skipped draw, so no draw is missing. A replay of a frame captured on Turnip, drawn on the system driver with the fixed library, comes out correct (5,021 pixels differ from the live Turnip frame, no orange and no black patches), so the bad content is built in earlier frames: a GPU-written image (a lookup texture, an environment map or a history target) that comes out wrong on Qualcomm. Cause: open. A red/blue swap in a scene texture or target is suspected and not measured, and the black patches may be a separate texture problem. The next step is a capture on the system driver at the same scene, to compare its GPU-written images with the Turnip capture.
- **B3, the target circles and lines (fixed on the phone, pending in a separate change).** They are the Attack target list's draws (vertex `16246f6e2c51b7b2`, fragment `a36ad6b33f6f5049`) and were invisible because their 128x128 RGBA8 texture (serial 1118) read all zero in every sampled frame: the game writes the texels from the CPU into its pool before the texture's first sample, flushes nothing, and the host treated the storage as GPU content and cleared the image. The profile keys `tex_flush_upload` and `tex_dirty_upload` made no difference (the texture never enters their tracking). A host rule for NVN1 storage the CPU wrote before a texture's first use, applied on the phone, makes the texture sample `w 53.4%` (was 0.0%) on both drivers and the circles and lines draw on Turnip. It is not in the repository.
- **B10, the system driver refuses pipelines (fixed on the phone, pending in a separate change).** Without the change, on the system driver 25 of 94 pipelines had failed with `VK_ERROR_INITIALIZATION_FAILED` (`-13`) by frame 2700, the log reported 45 % of draws skipped over the last 300 frames, the intro drew black, the rings did not draw and the picture flickered widely; the same build on Turnip had 0 failures. Cause: Qualcomm 512.676 refuses any pipeline whose pixel shader writes the dual-source output (`Location 0 Index 1`, the `MOD_DUAL` modules of `dual_want.tsv`) through per-component access chains, which is how the translator writes every output. The same modules pass `spirv-val`, and a module that writes Index 1 with one whole-vector store is accepted. With the dual-source output written whole, applied on the phone: 0 `-13`, no skipped-draws warning, the intro shows and the rings draw on the system driver, and 0 `-13` and a good picture on Turnip (the user's check). Until that change lands, a pack built from this profile on the system driver shows what is described first.
- B8: `[RED] BeginRecording re-entered` is printed once per host frame from boot (about 24,600 lines by frame 24,600). Harmless if the previous state was a begin with no command yet, a lost End if it was a recording in progress; which, and who calls it, was not found. Low priority.
- B9: the update NSP v1.1.0 is ignored. Its package (`update/65536`) has `main` build ID `c2932c4d1c84ed7def7ceb1042b78951` and adds `FFXII_TZA_PATCH.vbf` (2.0 MB); the profile registers the base only, so the launcher picks the base and the run used the base's build ID. To support it: a `build v65536` row, `package v65536`, `shader_versions v0 v65536`, then the split on the update, the translation, the rewrite and a new pack.
- B7: after the scene loads the character draws first and the terrain a little later. Parked as probably expected: the likely cause is `DM_VK_PSO_ASYNC=2` with an empty `pso_manifest.tsv`, pipelines made in the background at 97 to 244 ms each and their draws skipped until they are ready. That reading was not tested.
- The memory keys, the depth mode and frame pacing are the survey's or the game's own, not measured here (see What is guessed). No play session beyond the opening area was recorded, and no frame-rate figure for the system driver.
- Not checked at all: a Mac run; any device but the Thor and any driver but Turnip v27.0.0 and the system driver; the update NSP; a companion; the 84 geometry shaders (no `geo_mesh.tsv` was written; no geometry shader was in use in the opening scene, and a geometry draw takes the compute path until a run names its vertex and geometry pairs); SDK 6.4.0 service layouts and NVN object sizes against the host ([Host records must fit the game's SDK object sizes](../../../docs/reference/nvn-objects.md#host-records-must-fit-the-games-sdk-object-sizes)).
- `pso_pairs.tsv` and `pso_manifest.tsv` are empty: the pipeline prebuild has nothing to work from until a run records it.
- 371 vertex shaders against 5,999 pixel shaders looks lopsided for a renderer that draws a whole scene. Unchecked.
