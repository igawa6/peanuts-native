# Moving Out 2 (`010065D018172000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update v327680 (1.3.315, the update's NSP name; the dump folder has no control data) over the base game, SDK 14.3.1. **Unity 2021.3.16f1** (IL2CPP) with FMOD, rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The closest profiles are [Blasphemous 2](../010040801A4C8000/README.md) (Unity 2021.3.20f1, SDK 15.3.2) and [Chained Echoes](../0100C510166F0000/README.md) (SDK 14.3.0).

**Status: provisional; boots to its title and menus and plays the tutorial at 60 fps on the tablet (Lenovo TB321FU, Adreno 750, Turnip Gen8 V39).** The profile is the survey's (`scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py`) with its shader tables as `shader_split.py` wrote them, and a 6 GB guest block (below). It is not in any list of supported games.

## Builds

| Module | Build ID |
|---|---|
| `main` | `c552b6464e7348a7bb261a708b353137` |
| `subsdk0` | `75c83c09e25f9a4e60bd960815722e86` |
| `sdk` | `2587a39ac2547a780ec0e4b77e22afab` |

Only update v327680 is registered. The game's own menu shows `v1.3.4833.60`; the update's NSP name says 1.3.315, and the dump folder has no control data, so the profile's display version is the NSP name.

## Shaders

6,986 shaders: 3,436 vertex, 3,440 pixel, 109 compute and 1 geometry. 6,975 come from the player's serialized files under `Data/StreamingAssets` and 11 are GLSLC outputs of Unity's player in `main`. The split reported no unreadable file (no `[RED]` line) and no key conflict. The tables carry 109 compute answers and 3,439 variation pairs; `pso_manifest.tsv` and `bf_want.tsv` are empty, and `pso_pairs.tsv` has only its header. The pack made 8,149 SPIR-V modules, every one valid for `spirv-val` (3,437 with the GL depth constant).

## Memory

| Key | Value |
|---|---|
| `sys_resource` | `0x1000000` |
| `mem_reserve` | `0x180000000` |
| `mem_alias` | `0x158000000` |
| `mem_stack` | `0x10000000` |
| `app_pool` | `0xCD500000` |

The NPDM declares 16 MB of system resource, so `IsVirtualAddressMemoryEnabled` is true: `nninitStartup` hands application pool − used memory to `StandardAllocator` as virtual address memory and never calls `SetHeapSize`. `StandardAllocator`'s region is 3.14 GB. The game's other address regions reach +5.00 GB of the 5.38 GB alias region in the tutorial, so longer play may need a bigger block.

**The full-screen flicker was guest address-space exhaustion, not a texture bug.** With the survey's 4.375 GB alias region (`mem_alias 0x118000000`), Unity's render targets each take a 16.25 MB (sometimes 8.25 MB) address region through `VammManager::AllocateAddressRegion`; next to StandardAllocator they filled the region to about 4.1 GB, the largest hole was 7.8 MB, and the SDK refused the requests without crashing. Unity went on with those render targets' NVN textures never initialized: every other frame was drawn with no colour target and the final blit sampled a stale texture (the UI atlas, white or black) — the menu was stable, the tutorial flickered. The 6 GB block (`mem_reserve 0x180000000`, `mem_alias 0x158000000`, as Art of Rally's) fixed it; `hle_svc.c` `region_ask` now logs `[RED] [mem] address region of <n> MB asked … no gap that large` when a request has no hole. Verified on Turnip: 2,397 tutorial frames with brightness 142.9–145.7 and an average frame-to-frame change of 0.044.

## Frame rate

`present_interval display`. The game sets present interval 2 (30 fps) in play; Unity times its logic in seconds, so presenting every display tick runs it at 60 fps at the game's own speed, as The Hong Kong Massacre's profile does.

## Open

- **The address space is still close.** The tutorial reaches +5.00 GB of the 5.38 GB alias region; longer play may need a larger `mem_reserve`/`mem_alias` (the `[RED]` line will show when a request no longer fits).
- **The system (Qualcomm) driver was not tested.** All runs were on Turnip Gen8 V39.
- **Longer play, later levels and saves were not checked.** The title, menus and the tutorial were played.
- **Sound and the touch controls were not checked.**

## Found on this game

- [Address regions that no longer fit](../../../docs/reference/memory.md#address-regions-that-no-longer-fit): the render-target address-region exhaustion and the `region_ask` diagnostic.
