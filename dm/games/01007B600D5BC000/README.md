# Devil May Cry 3 Special Edition (`01007B600D5BC000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Base 1.0.0 (`v0`) and Update 1.0.1 (`v65536`), SDK `8.2.0`, NVN graphics, FMOD audio, and `NintendoSDK_movie` (`subsdk0`).

## Status

**Plays on Android (Turnip).**

- **`60 fps` cap (default):** median **`59.8 fps`** (`16.7 ms` frame time), slowest **`125.9 ms`** (cold-cache shader/scene load; `48.0 ms` median longframe), **`1.0x` game speed**, `1.6–5.7 ms` GPU busy, `~1.2 GB` game RAM (`5.3 / 7.1 GB` system).
- **`30 fps` cap (not offered):** tested at `29.9 fps` (`33.5 ms` median, `33.8 ms` slowest), which halves game speed to **`0.5x`** because the engine steps logic/physics per frame at `60 Hz`. `fps_modes` is therefore `60` only (use LSFG `2x` for `120 FPS` display output).

## Tested on

- **Samsung Galaxy Tab S8+ (`SM-X800`)**, Snapdragon 8 Gen 1 / Adreno 730, `7,467,116 kB` (`8 GB` / `7.1 GiB` usable) RAM, Android `16`:
  - **Mesa Turnip (`driverID=18`):** `Turnip Adreno Driver T30 (@Mr_Purple_666) (26.3.0-T30-1.4.359)` (`PurpleVK 26.3.0-devel (git-62ac221a33)`, Vulkan `1.4.359`). Fresh-cache run (`vk_pipelines.keys` `3,192 bytes`, `71` graphics pipelines built, `0` failed): intro `.mp4` videos (`1280×720` NV12) render with normal colors, skips cleanly to title/menus without `FlushAudioOutBuffers` crashes, and Mission 1 combat plays at `59.8–60 fps`.
  - **Qualcomm system driver:** not tested in play (only exposes Vulkan 1.1 on `SM-X800`, fails readiness).

## Builds

| Version | Display | `main` | `subsdk0` | `sdk` |
|---|---|---|---|---|
| `v0` | `1.0.0` | `010bcee657eafedfbe822470e3e02749` | `8df876f80ce4ceee62360b31405c452f` | `3ae34e689d7a35f13cbf42bfd672af8b` |
| `v65536` | `1.0.1` | `258d8ba560cfa34c3f2250230d8d8483` | `8df876f80ce4ceee62360b31405c452f` | `3ae34e689d7a35f13cbf42bfd672af8b` |

`subsdk0` and `sdk` match the builds used by *The Legend of Zelda: Link's Awakening* (`01006BB00C6F0000`) and *Return of the Obra Dinn* (`010032E00E6E2000`).

## Memory and graphics

- **Memory:** `nninitStartup` (`0x3ec740` in `v65536/main`) calls `nn::os::SetMemoryHeapSize(0xC0000000)` (3,072 MB) and `main.npdm` declares `0` MB of system resource (`sys_resource 0x0`). The survey's `4 GB` reservation (`mem_reserve 0x100000000`, `app_pool 0xCD500000`) fits both.
- **Depth mode:** `v65536/main` sets `nvnDeviceSetDepthMode(dev, 1)` (`NVN_DEPTH_MODE_NEAR_IS_ZERO`) at `0x43aee8`, so `depth_mode zero` is set.
- **Present interval:** `nvnWindowBuilderSetPresentInterval` (`0x424afc`) sets `1` (60 FPS).
- **Texture upload / conditional render:** Uses `nvnTextureWriteTexels` (`0x4442c8`, `0x444430`), with 0 callers of `nvnMemoryPoolFlushMappedRange`, `nvnTextureFlushTexels`, or `nvnCommandBufferSetRenderEnableConditional`.

## Files and shaders

- **RomFS:** 189 files, 5.03 GB (`data/dmc3/dmc3-0.nbz`, a 1.14 GB ZIP archive with 4,938 entries, plus `.mp4` videos, `.ogg` streams, FMOD `.bank` files, and `loadericon.dds`).
- **`shader_split.py`:** 74 shaders total (32 vertex, 42 pixel, 0 compute), all `BNSH` embedded in `main`, with 0 key conflicts.
- **Fast Prepare (`shader_files.tsv`):** Full split `71.70 s` vs targeted split (`DM_SPLIT_TARGETED=1`) `0.37 s` (all 74 shaders are in `main`; `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv`, and `pso_pairs.tsv` match).

## Rewrite census (`v65536`)

- `main`: 1,585,498 instructions (34 undecodable), 3,829 `x18` uses.
- `sdk`: 1,376,579 instructions (273 undecodable), 11,228 `x18` uses, 61 `svc`, 56 `mrs tpidrro_el0`, 32 `mrs cntpct_el0`, 3 `msr tpidr_el0`, 2 `mrs tpidr_el0`.
- `subsdk0`: 897,249 instructions (315 undecodable), 10,639 `x18` uses.

## Open

- Depends on separate host PRs for `dm/host/hle_audio.c` (`FlushAudioOutBuffers`) and `dm/host/hle_movie.c` (`nv12-colorspace=2` scoped to DMC3 builds).
- Qualcomm system driver not tested (requires a Vulkan 1.2+ device).
- macOS/iOS not tested.
- 10-minute run on a 12 GB+ Adreno phone with memory peak needed for release list.
