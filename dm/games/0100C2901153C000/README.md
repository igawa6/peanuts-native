# Yoru, Tomosu (`0100C2901153C000`)

Base game v0 (the Japanese release, 夜、灯す; no update), SDK 9.5.0. Nippon Ichi Software's own adventure engine, rendering with **NVN** through nn::gfx, which is linked into `main`. NintendoWare plays the sound (Atk: one `.bfsar` and 4,703 `.bfstm` streams) and draws the text (Font); the sprites are SpriteStudio projects (`.ssbp`). Modules `main` and `sdk`.

This profile was written from a survey of the game files, with its memory keys guessed by rule, not measured. With it the game was packed on Linux (35 shaders, all valid). It has not been booted anywhere.

## What was found

- **Modules.** `main` `3a573656…`, `sdk` `f93ce788…`; no subsdk. The rewriter takes both.
- **NPDM.** 64-bit, no system resource, so no virtual address memory. `main` does not import `nn::os::SetMemoryHeapSize`: its memory is its own bss, 1,569 MB, which it hands to `nn::mem::StandardAllocator` and `nn::lmem` exp heaps.
- **Graphics.** `main` imports `nvnBootstrapLoader` and nothing of EGL, OpenGL or Vulkan. Its SDK components are `NintendoSDK_gfx`, `NintendoWare_Atk` and `NintendoWare_Font`.
- **Shaders.** 24 BNSH files in the RomFS folder `shader/` (`BasicShader.bnsh`, `SpriteShader.bnsh`, `SS5Shader.bnsh`, `FontShader.bnsh`, the screen effects) and one embedded in `main` at `0xc0000`: 35 shaders after duplicates, 9 vertex and 26 pixel. `shader_pairs.tsv` has their 30 vertex / pixel pairs.
- **GLSLC.** `main` imports eight `glslc*` functions that no module of the game defines, and holds no GLSL source and no GLSLC output. With nn::gfx these imports come with the library (Resident Evil 4 has the same eight and plays).
- **Movies, network.** No movie library and no movie file. No socket, nifm or ssl import. `main` imports three `nn::web` functions (an offline web page, probably the manual) and `nn::account::OpenPreselectedUser`.
- **RomFS.** 5,255 files, 2.00 GB: `image/` 1.0 GB (`.bntx` textures), `sound/stream/` 0.9 GB (`.bfstm`), `font/` 57 MB, `ui/` 40 MB, `script/` (`.sc`, `.csv`), `shader/`. No DLC.

## What is guessed

| Setting | Why it is a guess |
|---|---|
| 1 GB block: `mem_reserve 0x40000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x0` | `profile_guess.py`'s rule for a game without a system resource that never sets a heap. Nothing was measured |
| No `mem_used` | the 1,569 MB bss counts as used memory by itself; whether the game asks for the used memory size is not known |
| No `pad_layout`, no `op_mode` | a text adventure: the default 2D layout, docked. No file decides either |
| `modules main sdk` | the rule (the game has only these) |
| `zip_store`, `romfs_marker` | the rule (big or compressed file types; the largest file, `font/font.bntx`) |
| `build` row's display version `?` | the import keeps no control data, so the version's name was not read |

`shader_facts.tsv` has no rows. `bf_want.tsv` and `pso_manifest.tsv` are empty. No `patches.py`.

## Open

- Not booted anywhere. The run-time keys (`render_cond`, `tex_flush_upload`, `tex_dirty_upload`, `core_preempt`, `present_interval`, …) keep their defaults.
- The 1,569 MB bss: whether a phone maps a module image of that size beside the 1 GB block was not checked.
- nn::gfx on SDK 9.5.0: NVN object sizes and the API version differ by SDK and were not checked for this build.
- `nn::web`: what the host answers when the game opens its offline page is not known.
- `nn::oe::SetCopyrightImage` and the copyright frame buffer are imported (a screenshot overlay); not checked.
- The text is Japanese: the game brings its own font (`font/font.bntx`, a `.bffnt`); whether it also asks for the shared fonts was not checked.
- No readiness rows (`readiness.tsv`): the game is not marked as supported on Android.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on ? through 3 `aspect_plan` rows that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
