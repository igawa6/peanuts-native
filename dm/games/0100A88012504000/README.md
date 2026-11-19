# Art of Rally (`0100A88012504000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update v458752 (1.1.9) over the base game, SDK 13.3.2. **Unity 2019.4.40f1** (IL2CPP) with FMOD, rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`.

**Status: provisional; boots to its menus and loads a free roam race at 60 fps on the tablet (Lenovo TB321FU, Adreno 750, Turnip Gen8 V39).** The profile is the survey's provisional one (`scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py`) with its shader tables as `shader_split.py` wrote them, corrected for memory and frame rate (below). It is not in any list of supported games.

## Builds

| Module | Build ID |
|---|---|
| `main` | `bcaa04faf88eea4a97bbf102cf4878ae` |
| `subsdk0` | `a8cb97803f7f442a3b6cce1c7cd4ee4a` |
| `sdk` | `357efc198e728cb5d55265aff304f03c` |

Only update v458752 is registered; the update's display version is not in the dump folder (no control data). The shader tables were split from the update's RomFS, which is the whole patched tree.

## Shaders

48,683 shaders: 25,413 vertex, 23,207 pixel, 62 compute and 1 geometry. 48,672 come from the player's serialized files under `Data/` and 11 are GLSLC outputs of Unity's player in `main`. The split reported no unreadable file (no `[RED]` line) and no key conflict. The tables carry 62 compute answers and 9 variation pairs; `pso_manifest.tsv` and `bf_want.tsv` are empty, and `pso_pairs.tsv` has 30,854 rows. The pack made 50,304 SPIR-V modules, every one valid for `spirv-val` (25,414 with the GL depth constant).

## Memory

| Key | Value |
|---|---|
| `sys_resource` | `0x1000000` |
| `mem_reserve` | `0x180000000` |
| `mem_alias` | `0x158000000` |
| `mem_stack` | `0x10000000` |
| `app_pool` | `0xCD500000` |

The NPDM declares 16 MB of system resource, so `IsVirtualAddressMemoryEnabled` is true: `nninitStartup` (returning to `main+0x414b60`) hands application pool − used memory to `StandardAllocator` as virtual address memory and never calls `SetHeapSize`. The pool is the Switch's hardware 3285 MiB; `StandardAllocator`'s region is 3.14 GB at alias +0.06 GB, of which 1.01 GB is mapped. The game's other address regions reach +4.51 GB of the 5.38 GB alias region, and its physical alias mappings peak at 2.00 GB of the 3.21 GB pool (the map limit stays on; `DM_REGIONS` measured both, 2026-11-19).

**The 1 GB pool copied from Ace Attorney's profile was too small and crashed the first race.** It gave `StandardAllocator` only a 0.94 GB address region; loading a race used it up, a 2 MB request in a container's growth path (`main+0x7685fc`, `bl main+0x6d15a0`) returned null, and the game wrote at null+`0xd44b0` without a null check: `CRASH sig=11 addr=0xd44b0 pc=sdk+0x5817a4 lr=main+0x7686bc`, a `memset` of 0x380f0 bytes at that address. With the hardware pool and the 6 GB block (256 MB in front + 5.375 GB alias region + 256 MB stack region + 128 MB heap region) the race loads and drives. The 5 GB block was too tight: other regions reached +4.25 GB of its 4.375 GB alias region before the block was enlarged.

## Graphics

`depth_mode zero`: the game calls `nvnDeviceSetDepthMode` with `near = 0` (log line `depth mode near=0`), as Cult of the Lamb and Castlevania do.

## Frame rate

`present_interval display`. The game sets present interval 1 → 2 (30 fps); Unity times its logic in seconds, so presenting every display tick runs it at 60 fps at the game's own speed, as for The Hong Kong Massacre (checked on the tablet, Turnip: the log's `fps` lines read 59.9–60.0 in menus and in a free roam race). The host's frame cap is not applied: `main` has neither a frame step nor an engine frame limit the host writes (`present cap 60 fps not applied` in the log).

## Open

- **Other locations and weather were not tested.** Only free roam's korkatti lakes (morning) was loaded; the address-region high-water mark may differ elsewhere.
- **The system (Qualcomm) driver was not tested.** All runs were on Turnip Gen8 V39.
- **Longer play, menus other than free roam, saves and the car/track unlocks were not checked.**
- The free roam area shown is a flat plain with a large statue and the line "hello my child…"; whether that is the game's own look or a missing scene element was not checked against the real game.

## Found on this game

- The memory fix is a profile value, not a host change; the keys and the `StandardAllocator` mechanism are in [the memory keys](../README.md#keys) and `dm/host/hle_svc.c`.
