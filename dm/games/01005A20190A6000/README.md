# NARUTO X BORUTO Ultimate Ninja STORM CONNECTIONS (`01005A20190A6000`)

<!-- PEANUTS -->
The profile targets update **1.70 (`v655360`)**, SDK 14.3.0, with the three build IDs in [game.tsv](game.tsv): CyberConnect2's NUCC engine on NVN, Scaleform GFx for every screen of UI, CRI ADX2 sound, CRI Sofdec movies through the SDK's `nn::movie` VP9 decoder. Status: **bring-up**. On an AYN Odin 2 the app's own Prepare builds the game from the dump on the phone, and a Free Battle fight reaches player control with Turnip 27.0.0-R2 and with the system driver, characters and effects whole on both (below). The readiness check has no status row yet, so release builds leave the game out.

## What it needs

| What | Where | Why |
|---|---|---|
| `modules main subsdk0 sdk` | [`game.tsv`](game.tsv) | `subsdk0` is `NintendoSDK_movie`. `subsdk1` (GLSLC) and `subsdk2` (NVIDIA's OpenGL and EGL driver) are never loaded: `main` takes only its 10 EGL imports from `subsdk2`, which the host answers, and nothing from `subsdk1` |
| The OpenGL UI | [rendering.md, An NVN game whose UI draws with OpenGL through EGL](../../../docs/reference/rendering.md#an-nvn-game-whose-ui-draws-with-opengl-through-egl-android) | Scaleform draws the UI with GL ES 3.0 into one RGBA8 texture the size of the screen, which NVN samples over the 3D frame (`nvnTextureBuilderSetGLTextureName`) with fences both ways. The Android host forwards EGL and GL to the phone's OpenGL ES and shares the texture through an AHardwareBuffer |
| Memory rows | [`game.tsv`](game.tsv) | the heap region and `mem_used` from `profile_guess.py heap_signals` on the phone's log: `SetHeapSize OOM: req 0xc9200000 > cap 0x8000000` with the starting split (a fault in `sdk` at `nninitStartup`), then `SetHeapSize 0xc9200000 + modules 0x5c00000 > app pool`. The 1.25 GB alias region beside it is not measured yet |
| Shader tables | `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv` | `shader_split.py` over the 1.70 RomFS and `main`: 570 shaders (254 vertex, 291 pixel, 25 compute) |

## Graphics

- `main` imports `nvnBootstrapLoader` and 10 EGL functions (`eglGetDisplay`, `eglInitialize`, `eglBindAPI`, `eglChooseConfig`, `eglCreateContext`, `eglMakeCurrent`, `eglDestroyContext`, `eglReleaseThread`, `eglTerminate`, `eglGetProcAddress`); not `eglCreateWindowSurface`, so `profile_guess.py` calls it an NVN game. Its contexts are surfaceless, the API it binds is desktop GL (`EGL_OPENGL_API`) with ES shaders.
- `loader_census.py main`: 972 loader slots (534 NVN, 282 GL); the code outside the loaders calls 175 NVN functions, 90 GL functions (Scaleform's GL HAL in `ccScaleformPlatform_NX.cpp`) and the three interop functions. Scaleform's 1,116 GLSL sources are strings in `main` (695 GLSL ES 1.00, 421 `#version 300 es`), compiled at run time by the GL driver.
- The UI render target (`main+0x973454`): `glTexStorage2D(1, GL_RGBA8, w, h)` with the screen size the game keeps (it changes between docked and handheld), a depth renderbuffer and a framebuffer, then the NVN texture over it. Per frame (`main+0x972cc4`): `glFenceSync`, `glFlush`, `nvnSyncInitializeFromFencedGLSync`, `nvnQueueWaitSync`, the NVN draw of the UI over the 3D frame; then (`main+0x973824`) `nvnQueueFenceSync`, `nvnSyncCreateGLSync`, `glWaitSync`. Two syncs in a ring.

## Android on the AYN Odin 2

Odin 2 (Snapdragon 8 Gen 2, Adreno 740, 16 GB, Android 13), update 1.70 from a dump folder on the SD card. The fixes were found with a pack made on a PC (`scripts/pack_android.py`); evidence is screenshots of the same scenes before and after each fix, scored over several frames each. The last run used the pack the app's Prepare made on the phone.

| | Turnip 27.0.0-R2 | System driver (Adreno V@0676.32) |
|---|---|---|
| Opening movie, title, menus, Training's arena | right | right (characters black before `DM_VK_DYN_PASS`) |
| A training fight, player-controlled | 30 fps (SurfaceFlinger, frame interval p95 33.3 ms), PSS 4.3 GB | 30 fps (p95 33.3 ms) |
| Free Battle (VS Battle, Sage of the Six Paths Naruto, Sasuke, Sakura), player-controlled | right, 30 fps | right, 30 fps |
| Prepare on the phone (the app's own, from the dump), then Free Battle's practice with a Tailed Beast Bomb Rasen Shuriken | 30 fps (frame interval p50 and p95 33.3 ms), PSS 4.21 GiB (GL 623 MiB); effects whole | 30 fps (p50 and p95 33.3 ms), PSS 3.83 GiB (EGL 953 MiB); effects whole |
| Docked → handheld → docked mid-fight | the UI texture is made again at 1120x630 and 1600x900 and shared each time | not switched |
| `DM_NVN_SHDUMP` | no program missing | no program missing |

- **Resolved, found with the draft's CPU copy:** a black 3D frame wherever the UI covers it (the title's village, the menus' scene, the whole arena in a fight), ghost copies of earlier UI frames (the Training menu over the previous dialog), and a white opening movie. All three were the UI image reaching NVN unordered: [rendering.md, An NVN game whose UI draws with OpenGL through EGL](../../../docs/reference/rendering.md#an-nvn-game-whose-ui-draws-with-opengl-through-egl-android).
- **Resolved:** the reported missing terrain and sky: with the UI image shared the arena's ground, sky and water show on both drivers, and no shader is missing from the map. They were under the UI's black, not missing.
- **Resolved (system driver):** the characters' bodies drew black. Qualcomm's driver lost the depth write enable at a pass begin and the cull mode at a pipeline bind; `DM_VK_DYN_PASS` sets the dynamic state again there ([rendering.md](../../../docs/reference/rendering.md#characters-black-on-qualcomms-driver-dynamic-state-lost-at-a-pass-begin-or-a-pipeline-bind)). Checked toggled live over 8-16 frames a scene: title, Training's arena, a training fight.
- **Resolved (both drivers):** Sage of the Six Paths Naruto's costume was flat black at the character select and invisible in fights: its diffuse is the game's one BGR5A1 (0x97) texture, which had no Vulkan format ([rendering.md](../../../docs/reference/rendering.md#a-materials-body-invisible-or-black-nvn_format_bgr5a1-had-no-vulkan-format)). Checked at the character select on both drivers (6 frames each, before and after) and in a Free Battle on both.
- **Resolved (system driver):** jutsu and chakra effects (bursts, the Truth-Seeking Orb sphere, Susanoo) broke into square blocks. The effects pass's first draw writes a depth and no colour, and Qualcomm's driver gives such a draw wrong pixel shader inputs; `DM_VK_DEPTH_ONLY_BLEND` writes its colour targets unchanged through a blend ([rendering.md](../../../docs/reference/rendering.md#effects-in-32x32-cells-on-qualcomms-driver-a-draw-that-writes-no-colour-gets-wrong-pixel-shader-inputs)). Checked on a dump of the half-size depth the effects test against: 0 one-value 16x16 blocks of 1,400 with it, 743 without.
- **Prepare on the phone:** 56 s from the dump on the SD card (the code 42 s, the shader split 3 s, 570 shaders 3 s, the pack 4 s); 570 of 570 shaders made, every one of the 585 modules valid for `spirv-val`, a 93 MiB pack, the same Vulkan chain and inputs as the PC pack.
- **Seen once:** a crash in Android's own UI rendering (`libhwui.so` `ClipStack`, the game process's RenderThread) at the character select on the system driver; not seen again in the later runs (no new entry in the phone's crash log).

## Shaders

- `data/system/nuccMaterial_nx.nsh` (452 records) and `nuccPostEffect_nx.nsh` (136) are `NUP4` packages of plain GLSLC outputs; the 45 `data/out/*.nvn` are single ones; `main` holds 2.
- The split decodes every CRI CPK entry it can: 19,116 `xfbin` entries in the CPKs are stored in a form that is not CRILAYLA and are not scanned (`[RED] ...: not CRILAYLA; not scanned`). By their names they hold models, effects and UI, not shader code; a `DM_NVN_SHDUMP` run that finds no program missing from the map is the check still to make.
- `data/scaleform_cache/GFxShaders.cache` holds no GLSLC output and `main` calls no `glProgramBinary`.

## Versions and regions

- The US release is `0100FA10190A0000`. Its 1.70 has the same three build IDs as this profile (`main` 48ece454b61412b9fb46fab2be3f5ef7, reported in #637), so it needs a launcher mapping by build ID, not a profile of its own. Not done or run yet.

## Found on this game

- [An NVN game whose UI draws with OpenGL through EGL (Android)](../../../docs/reference/rendering.md#an-nvn-game-whose-ui-draws-with-opengl-through-egl-android)
- [Characters black on Qualcomm's driver: dynamic state lost at a pass begin or a pipeline bind](../../../docs/reference/rendering.md#characters-black-on-qualcomms-driver-dynamic-state-lost-at-a-pass-begin-or-a-pipeline-bind)
- [A material's body invisible or black: NVN_FORMAT_BGR5A1 had no Vulkan format](../../../docs/reference/rendering.md#a-materials-body-invisible-or-black-nvn_format_bgr5a1-had-no-vulkan-format)
- [Effects in 32x32 cells on Qualcomm's driver: a draw that writes no colour gets wrong pixel shader inputs](../../../docs/reference/rendering.md#effects-in-32x32-cells-on-qualcomms-driver-a-draw-that-writes-no-colour-gets-wrong-pixel-shader-inputs)
- [Big-stack wrappers of functions with stack-passed arguments](../../../dm/host/hle_bigstack.c): `glTexImage2D` and `glTexSubImage2D` have nine arguments (`t_bigstack`).

## Open

- Story mode, the US release (above), the alias region.
- The `libhwui.so` crash seen once (above).
