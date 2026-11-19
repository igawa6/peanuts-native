# Moving Out (`0100C4C00E73E000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update v393216 (1.0.6, the update's NSP name; the dump folder has no control data) over the base game, SDK 9.4.1. **Unity 2018.4.21f1** (IL2CPP) with FMOD, rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The closest profile is [Monster Sanctuary](../0100A0F00DA68000/README.md): the same engine generation and SDK, and the same `sdk` (`290c9a04234847a176aa1c375c16af99`) and `subsdk0` (`fbb0b46fb4fdbf4be47b76f2285f53ed`) builds.

**Status: provisional; boots to its title screen and main menu at 60 fps, joins a coworker and plays the first tutorial level at 30 fps on the tablet (Lenovo TB321FU, Adreno 750, Turnip Gen8 V39).** The profile is the survey's (`scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py`), with its shader tables as `shader_split.py` wrote them and its memory keys taken from Monster Sanctuary. It is not in any list of supported games.

## Builds

| Module | Build ID |
|---|---|
| `main` | `cb3172ed0c3bc646bfdf0451cba8a178` |
| `subsdk0` | `fbb0b46fb4fdbf4be47b76f2285f53ed` |
| `sdk` | `290c9a04234847a176aa1c375c16af99` |

Only update v393216 is registered. The game's own menu shows `v1.3.4833.60`; the update's NSP name says 1.0.6, and the dump folder has no control data, so the profile's display version is the NSP name.

## Shaders

17,282 shaders: 9,481 vertex, 7,732 pixel, 50 compute and 19 geometry. 17,277 come from the player's serialized files under `Data/StreamingAssets` (101 UnityFS `.abundle` files) and 5 are GLSLC outputs of Unity's player in `main`. The split reported no unreadable file (no `[RED]` line) and no key conflict. The tables carry 50 compute answers and 2 variation pairs; `pso_manifest.tsv` and `bf_want.tsv` are empty, and `pso_pairs.tsv` has 10,539 rows. The pack made 18,228 SPIR-V modules, every one valid for `spirv-val` (9,500 with the GL depth constant).

## Memory

| Key | Value |
|---|---|
| `sys_resource` | `0x1000000` |
| `mem_reserve` | `0x140000000` |
| `mem_alias` | `0x118000000` |
| `mem_stack` | `0x10000000` |
| `app_pool` | `0xCD500000` |

The NPDM declares 16 MB of system resource, so `IsVirtualAddressMemoryEnabled` is true: `nninitStartup` (returning to `main+0x1890268`) hands application pool − used memory to `StandardAllocator` as virtual address memory and never calls `SetHeapSize`. The keys are Monster Sanctuary's (a guess for both games), and they worked: `StandardAllocator`'s address region is 3.14 GB at alias +0.06 GB, and the game's physical alias mappings peaked at 0.76 GB of the 3.21 GB pool. No `SetHeapSize` line appears in the log.

## Graphics

`depth_mode zero`: the game calls `nvnDeviceSetDepthMode` with `near = 0` (log line `depth mode near=0`).

## Frame rate

The game sets present interval 1 in its menus (60 fps) and 2 in the tutorial level (30 fps); the profile keeps the default `present_interval game`, as the game's own pacing. The host's frame cap is not applied: `main` has neither a frame step nor an engine frame limit the host writes (`present cap 60 fps not applied` in the log).

## Open

- **One garbage frame during the level load.** After Continue from Coworker Select, one frame showed TV-static-like noise (raw-looking colour bands and stripes), then the tutorial rendered correctly. Whether it is the game's own transition or a rendering artifact (for example a texture or pipeline built on first use) was not established; it was not seen again once the level was loaded.
- **Only the first tutorial level was played.** Later levels, the garage, multiplayer and saves were not checked.
- **The system (Qualcomm) driver was not tested.** All runs were on Turnip Gen8 V39.
- **Sound and the touch controls were not checked.**

## Found on this game

- Nothing general yet: the memory keys are Monster Sanctuary's and the frame pacing is the game's own.
