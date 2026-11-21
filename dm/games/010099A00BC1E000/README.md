# Resident Evil 4 (`010099A00BC1E000`)

Update v131072 over the base game, SDK 5.4.0. Capcom's own Resident Evil 4 engine (the HD version's code: its source paths name `Prj_ESUFOUR` and a `gx_NX.cpp`), rendering with **NVN** through nn::gfx, which is linked into `main`. CRI middleware plays the sound (Atom, ADX2) and the movies (Mana, H.264 through the SDK's movie library). Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`.

This profile was written from a survey of the game files, with its memory keys guessed by rule, not measured. With it the game was packed on Linux (207 shaders, all valid) and played on an AYN Thor (Adreno 740, Turnip): the title, new save data, a new game, the opening movie and the first forest scene, 60 fps, about 2.4 GB, no red line. Nothing past that scene was checked, and the Mac was never run.

## What was found

- **Modules.** `main` `82c2a04a…` (the base game's is `8ceaf0d2…`), `subsdk0` `2e8d3a96…`, `sdk` `f7c7b7a9…`. The update changes only `main`. The rewriter takes all three; the rewritten modules are 49 MB.
- **NPDM.** 64-bit, no system resource, so no virtual address memory. `main` imports `nn::os::SetMemoryHeapSize`.
- **Graphics.** `main` imports `nvnBootstrapLoader` and nothing of EGL, OpenGL or Vulkan. Its SDK components are `NintendoSDK_gfx`, `libcurl` and `libz`.
- **Shaders.** 207 BNSH files embedded in `main` (offsets `0x6f637c` to `0x9fe338`), one single-stage program each: 69 vertex and 138 pixel shaders. The RomFS holds none (every file was scanned). `shader_pairs.tsv` is empty: a BNSH file here never holds a vertex and a pixel shader together, so the pairs exist only at run time.
- **GLSLC.** `main` imports eight `glslc*` functions (`glslcCompile`, `glslcInitialize`, …) that no module of the game defines, and holds no GLSL source and no GLSLC output. With nn::gfx these imports usually come with the library and are never called; this was not checked.
- **Movies.** 57 CRI USM files in `bio4/movie` (2.0 GB). `main` takes 29 functions from `subsdk0` (`movie::Decoder`, `movie::Buffer`, `movie::MediaData`, the H.264 helpers), none of which the host supplies itself, so the module is loaded.
- **Network.** NEX 4.5.0 is linked in (rankings), with `nn::socket` (28 imports), `nn::nifm`, `nn::ssl`, `nn::account`'s network service account calls and `nn::friends`.
- **RomFS.** 5,275 files, 12.05 GB, all under `bio4/`. By size: `.fen` 6.8 GB (2,117 files, mostly `bio4/imagepackhd`, the HD textures), `.udas` 2.2 GB (331, room and enemy data), `.usm` 2.0 GB, `.awb` / `.acb` 0.67 GB (433 each, CRI sound), `.fix` 0.4 GB, `.dat` 0.3 GB. No DLC.

## What is guessed

| Setting | Why it is a guess |
|---|---|
| 4 GB layout: `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x0` | `profile_guess.py`'s rule for a game without a system resource that sets a heap. The heap the game asks for was not measured |
| No `mem_used` | the rewritten modules are under the default 64 MB; the heap request was not seen |
| `pad_layout full` | a third-person game with two sticks; no file decides it |
| `modules main subsdk0 sdk` | the rule (a module `main` imports from). Sound as far as the imports go, but the movie path has not run |
| `zip_store`, `romfs_marker` | the rule (big or compressed file types; the largest file, `bio4/snd/bio4bgm.awb`) |
| `build` row's display version `?` | the import keeps no control data, so the version's name was not read |

`shader_facts.tsv` has no rows (no PC shader pairing, no compute shader). `bf_want.tsv` and `pso_manifest.tsv` are empty. No `patches.py`.

## Open

- Not booted anywhere. The run-time keys (`render_cond`, `tex_flush_upload`, `tex_dirty_upload`, `core_preempt`, `present_interval`, …) keep their defaults.
- The `sdk` build is not one `dm/tools/sdk_patches.py` knows: its two function-matched patches (the node area cut and the address region alignment) found no function in it. Both concern virtual address memory, which this game does not have.
- nn::gfx on SDK 5.4.0: NVN object sizes and the API version differ by SDK and were not checked for this build.
- The `glslc*` imports resolve to nothing. If the game calls them, it needs a GLSLC module it does not ship.
- With no vertex / pixel pairs, no vertex shader is padded for its pixel shaders; a pipeline may fail to link until the pairs are known from a run.
- The NEX and network calls: whether the game starts and plays offline with what the host answers was not checked.
- The movie path (CRI Mana over `movie::Decoder`) was not run.
- No readiness rows (`readiness.tsv`): the game is not marked as supported on Android.
- The profile has no row in the games table of [dm/games/README.md](../README.md) yet.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on ? through 6 `aspect_plan` rows that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the width,height pairs in data, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
