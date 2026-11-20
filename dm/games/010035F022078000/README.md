<!-- PEANUTS: DRAGON BALL: Sparking! ZERO (010035F022078000) -->
# DRAGON BALL: Sparking! ZERO (`010035F022078000`)

Base 1.0.0 (`v0`), SDK 14.3.0 (`14_3_0`), Unreal Engine 4.27 (IoStore containers beside `.pak` archives, `FMallocBinned3`), NVN, CRIWARE sound and Sofdec VP9 movies.

**Status:** boots and plays battles on Android (tested on Xiaomi `25053PC47I`, Adreno 825, Turnip `Turnip_Adreno_Driver_T30_Mr_Purple_666_` and Qualcomm system driver) at 30 fps (`bUseFixedFrameRate=True`, `FixedFrameRate=30.0` in `DefaultEngine.ini`) with sound, movies and save commits working.

## Builds

| Version | Display | `main` | `subsdk0` (`NintendoSDK_movie`) | `sdk` (`nnSdk`) |
|---|---|---|---|---|
| `v0` | `1.0.0` | `764daf48e0d3500e369c1baffa5159d6` | `c781dd63daf0ddcdb9975da50a198f93` | `2aef2cffb104ee7c9e212fd2f4c731e9` |

## Shaders

- **13,769 shaders** (`1,215` compute, `2` geometry, `9,271` pixel, `3,281` vertex) extracted from `ShaderArchive-Global-` and `ShaderArchive-` IoStore archives (`dm/gfx/tools/shader_split.py`, Oodle-compressed).
- All 13,769 shaders translate and pass `spirv-val --target-env vulkan1.3` (`21,634` modules).

## Memory

- `mem_reserve 0xF0000000`, `mem_alias 0xC8000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x1E00000` (30 MB declared in `main.npdm`).
- `nninitStartup` asks for a 62 GB engine region (`dm/tools/ue_region.py` caps it to 2.81 GB inside the alias region).
- `FMallocBinned3` constructor (`main+0x1e169cc`, vtable `0x8624128`, slot 19) is called from **two** sites (`main+0x1db9b10` and `main+0x1dba028`); both calls are cut to `nop` with `OBS_UE_MALLOC` (`dm/tools/ue_malloc.py`, `dm/tools/patch_nso.py`) so Unreal runs on the host malloc zone (`hle_uemalloc.c`).

## Found on this game

- **Multiple `FMallocBinned3` constructor call sites (`dm/tools/ue_malloc.py`, `dm/tools/patch_nso.py`):** this build calls `0x1e169cc` twice during allocator initialization (`0x1db9b10` and `0x1dba028`); cutting both call sites prevents a null-pointer dereference at `main+0x423e0d8` (`addr=0x3090`).
- **Compute texel-buffer writes & async pipelines:** setting `DM_VK_TBUF_GW=1` and `DM_VK_PSO_ASYNC=0` prevents skipped draw calls and missing cel-shaded battle slashes/auras on Android.

## Open

- **Dark landscape terrain around arenas:** `DefaultEngine.ini` enables Runtime Virtual Textures (`r.VirtualTextures=True`, `PF_DXT5` / `PF_BC5` pools); the immediate landscape ground plane renders dark/black on both Turnip and Qualcomm drivers while characters, arena geometry, sky and effects render normally.
