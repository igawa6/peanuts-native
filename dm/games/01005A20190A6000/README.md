# NARUTO X BORUTO Ultimate Ninja STORM CONNECTIONS (`01005A20190A6000`)

<!-- PEANUTS -->
Update **1.70 (`v655360`)**, SDK 14.3.0. CyberConnect2's NUCC engine on NVN, Scaleform GFx for every screen of UI (drawn with OpenGL ES through EGL), CRI ADX2 sound, CRI Sofdec movies through the SDK's `nn::movie` VP9 decoder. It needs the EGL and GL forwarding of #847 on Android: without it the game stops at `UNIMPL eglGetDisplay`.

## Status

**Bring-up: plays on an AYN Odin 2 with Turnip 27.0.0-R2 and the Qualcomm driver.** The app's own Prepare builds the game from the dump on the phone, and Free Battle reaches player control on both drivers with characters and effects whole. Not on the release list.

- **Import and Prepare on the phone, from the NSPs:** the app imported the base and 1.70 update NSPs from the SD card in 1,390 s (18 GB at 13.4 MB/s), then Prepare took about 64 s: 570 of 570 shaders. An earlier Prepare from an unpacked dump folder took 56 s (the code 42 s, the shader split 3 s, 570 shaders 3 s, the pack 4 s), every one of the 585 modules valid for `spirv-val`, a 93 MiB pack.
- **Fast Prepare:** a full split of 1.70 with `DM_SPLIT_FILES` takes 505.6 s on a PC (Windows, Python 3.12, `J=8`) and finds shaders in 48 of the 1,185 RomFS files ([shader_files.tsv](shader_files.tsv)). A targeted split of the same build (`DM_SPLIT_TARGETED=1`) reads 47 of them and takes 2.9 s. Its `shader_map.tsv`, `shader_facts.tsv` and `shader_pairs.tsv` match the full split's and the profile's byte for byte; both write an empty `pso_pairs.tsv`, so the profile has none.
- **Frame rate:** Game owned. The game paces itself at 30, and the profile has no frame-rate row. Free Battle > Practice, Sage of the Six Paths Naruto against himself, Training Field, under player control, from SurfaceFlinger's present times:

| driver | cap | play | presents | median | p95 | slowest | game speed |
|---|---|---|---|---|---|---|---|
| Turnip 27.0.0-R2 | 30 (Game owned) | about 11 min of fights, 240 presses | 19,778 over 659 s | 33.33 ms | 33.33 ms | 50.0 ms (at most 0.4 % of a fight's presents over 34 ms) | 1.00 |
| Qualcomm V@0676.32 | 30 (Game owned) | about 5.5 min of fights, 120 presses | 9,465 over 315 s | 33.33 ms | 33.33 ms | 33.33 ms | 1.00 |

  These are the fresh-cache runs whose `vk_pipelines.keys` are attached to the pull request (115 keys on Turnip, 130 on the Qualcomm driver), from a new save, each with two Tailed Beast Bomb Rasen Shuriken jutsus. The log shows `[vk] fps: 30.0 game frames a second, 30.0 presented`. Game speed is from the idle animation of the player character (body and orbiting Truth-Seeking Balls), whose loop took 2.655 s on both drivers (2.66 to 2.76 s in earlier runs), with no input. Practice has no round timer and refills chakra at once, so neither can be used. Earlier runs gave the same medians and p95 (Turnip 5,161 presents over 172 s, slowest 33.33 ms; Qualcomm 6,290 over 210 s, slowest 33.36 ms).
- **Memory** (`dumpsys meminfo` of the game process, during the jutsus of the fresh-cache runs): PSS 4.41 to 4.44 GB on Turnip (Graphics 1.69 GB, GL 637 MB), 4.02 to 4.03 GB on the Qualcomm driver (Graphics 1.13 GB, EGL 975 MB). Another app's game process held about 2.3 GB in the background during both runs. The heap region and `mem_used` in [game.tsv](game.tsv) are `profile_guess.py heap_signals`' answers to the phone's log (`SetHeapSize OOM: req 0xc9200000 > cap 0x8000000`, then `SetHeapSize 0xc9200000 + modules 0x5c00000 > app pool`). The 1.25 GB alias region beside it is the survey's guess, not measured.
- **Fixed on the way, each in its own pull request:**
  - The UI covering the 3D frame in black, ghost copies of earlier UI frames and a white opening movie: the UI image reached NVN unordered. Fixed by sharing the GL texture with NVN through an AHardwareBuffer with both syncs (#847).
  - `glTexImage2D` and `glTexSubImage2D` reading garbage for their 9th and 10th arguments through the big host stack (#843).
  - Sage of the Six Paths Naruto's costume flat black at the character select and invisible in fights: its diffuse is the game's one BGR5A1 (0x97) texture ([rendering.md](../../../docs/reference/rendering.md#a-materials-body-invisible-or-black-nvn_format_bgr5a1-had-no-vulkan-format), #844).
  - Qualcomm driver: jutsu and chakra effects in square cells. The effects pass's first draw writes a depth and no colour, and that driver gives such a draw wrong pixel shader inputs (#850).
  - Qualcomm driver: the characters' bodies black. On `dev` this is covered by `DM_VK_DYN_FILTER` defaulting to 0 on drivers before 512.800 and by `ds_forget` at each pass begin (#845, closed).
- **No program missing:** `DM_NVN_SHDUMP` runs on both drivers found no shader outside the map.
- **Docked and handheld:** switching mid-fight on Turnip makes the UI texture again at 1120x630 and 1600x900 and shares it each time.

## Tested on

- AYN Odin 2: Snapdragon 8 Gen 2 (Adreno 740), 16 GB, Android 13. Big cores at their maximum in every sample (`scaling_max_freq` = `cpuinfo_max_freq`), GPU at 680 MHz.
- Turnip 27.0.0-R2 (Mesa 27.0.0-devel git-2648b2059f) and the Qualcomm driver V@0676.32 (driver build 1a285a84ae).
- The test app built from this profile's tree with #843, #844, #845, #847 and #850 (log: `build: commit 12f019b8e713, shader chain cdcf140b1558`). The base and 1.70 update NSPs imported and prepared by the app on the phone (the fresh-cache runs); earlier runs used a dump folder on the SD card.
- Log driver lines: `Turnip (Mesa 27.0.0-devel (git-2648b2059f)), driver ID 18 ... custom (DM_VK_DRIVER)` and `Qualcomm Technologies Inc. Adreno Vulkan Driver (Driver Build: 1a285a84ae, ...), driver ID 8 ... the system's`.

## Builds

| Module | Build ID |
|---|---|
| `main` | `48ece454b61412b9fb46fab2be3f5ef7` |
| `subsdk0` | `c781dd63daf0ddcdb9975da50a198f93` |
| `sdk` | `2aef2cffb104ee7c9e212fd2f4c731e9` |

One build row, `v655360` (1.70; `main`'s own build path names `MasterV170`, the dump has no control data). `subsdk0` is `NintendoSDK_movie`. `subsdk1` (GLSLC) and `subsdk2` (NVIDIA's OpenGL and EGL driver) are never loaded: `main` takes only its 10 EGL imports from `subsdk2`, which the host answers, and nothing from `subsdk1`.

The US release (`0100FA10190A0000`) 1.70 has the same three build IDs, so it needs a launcher mapping by build ID, not a profile of its own.

## Graphics

- `main` imports `nvnBootstrapLoader` and 10 EGL functions (`eglGetDisplay`, `eglInitialize`, `eglBindAPI`, `eglChooseConfig`, `eglCreateContext`, `eglMakeCurrent`, `eglDestroyContext`, `eglReleaseThread`, `eglTerminate`, `eglGetProcAddress`); not `eglCreateWindowSurface`, so `profile_guess.py` calls it an NVN game. Its contexts are surfaceless, the API it binds is desktop GL (`EGL_OPENGL_API`) with ES shaders.
- `loader_census.py main`: 972 loader slots (534 NVN, 282 GL); the code outside the loaders calls 175 NVN functions, 90 GL functions (Scaleform's GL HAL in `ccScaleformPlatform_NX.cpp`) and the three interop functions. Scaleform's 1,116 GLSL sources are strings in `main` (695 GLSL ES 1.00, 421 `#version 300 es`), compiled at run time by the GL driver.
- The UI render target (`main+0x973454`): `glTexStorage2D(1, GL_RGBA8, w, h)` with the screen size the game keeps (it changes between docked and handheld), a depth renderbuffer and a framebuffer, then the NVN texture over it. Per frame (`main+0x972cc4`): `glFenceSync`, `glFlush`, `nvnSyncInitializeFromFencedGLSync`, `nvnQueueWaitSync`, the NVN draw of the UI over the 3D frame; then (`main+0x973824`) `nvnQueueFenceSync`, `nvnSyncCreateGLSync`, `glWaitSync`. Two syncs in a ring.

## Shaders

- `shader_split.py` over the 1.70 RomFS and `main`: 570 shaders (254 vertex, 291 pixel, 25 compute). `data/system/nuccMaterial_nx.nsh` (452 records) and `nuccPostEffect_nx.nsh` (136) are `NUP4` packages of plain GLSLC outputs; the 45 `data/out/*.nvn` are single ones; `main` holds 2.
- The split decodes every CRI CPK entry it can: 19,116 `xfbin` entries in the CPKs are stored in a form that is not CRILAYLA and are not scanned (`[RED] ...: not CRILAYLA; not scanned`). By their names they hold models, effects and UI, not shader code, and the `DM_NVN_SHDUMP` runs above found no program missing from the map.
- `data/scaleform_cache/GFxShaders.cache` holds no GLSLC output and `main` calls no `glProgramBinary`.

## Found on this game

- [A material's body invisible or black: NVN_FORMAT_BGR5A1 had no Vulkan format](../../../docs/reference/rendering.md#a-materials-body-invisible-or-black-nvn_format_bgr5a1-had-no-vulkan-format)
- The EGL and GL forwarding with the shared UI texture (#847), the depth-only blend on Qualcomm's driver (#850) and the two stack-passed arguments of the big host stack (#843) have their entries in those pull requests.

## Open

- Story mode, and play outside Free Battle's Practice (the 11 minutes on Turnip are Practice fights).
- The first launch of the Turnip fresh-cache run was killed by the kernel after 1.3 s: three GPU faults (`CP: AHB bus error`, `gpu fault threshold exceeded`) while the shader parts were made, then `kgsl: out of memory`. The next launch, identical, ran clean, and the Qualcomm run had no fault. Seen once in three launches; not investigated.
- A warm-cache launch with the seed built from the attached keys.
- The US release (above): the build-ID mapping is not done and the US dump was not run.
- The alias region's size; other phones and other Adreno GPUs.
- A crash in Android's own UI rendering (`libhwui.so` `ClipStack`, the game process's RenderThread) seen once at the character select on the Qualcomm driver; not seen again in the later runs. Its cause is not known.
- A 60 fps option: an FPSLocker plan held 60 at real game speed in a fight on this phone, but it is not in this profile. It comes as its own pull request.
