# Trails in the Sky 1st Chapter (`01002C9022770000`)

<!-- PEANUTS -->
The profile targets Switch update **1.0.7 (`v393216`)** only: SDK 21.4.4, NVN, and the three build IDs in [game.tsv](game.tsv). The lookup tables identify 6,602 shader keys; they contain no shader programs. Prepare uses the player's own dump. Compared with 1.0.4, only `chr_cloth` shaders changed (six keys replaced by eight).

Startup requests the application pool rounded down to 2 MiB, less `0x48200000`, for the heap. The `0xCD500000` pool bounds that request at `0x85200000`. Independently, the allocator requests `10 × 0x48200000` bytes of virtual address space; 1.0.7 sets that `0x48200000` block size at `main+0x56c0d4`, and its NPDM matches the base game's. The profile leaves 16 GiB for alias mappings. These are reservation sizes, not physical RAM measurements.

The first chapter's pixel key `e356305a9fe7e4de` identifies the same shadow-depth stitch as the second chapter's `eb824d256484ca7f`. On Turnip, the existing D16 depth-only pass split now recognizes either key; it does not change other drivers or apply the second chapter's frame pacing settings. Check for single-frame darkening of the road on Elize Highway after New Game. Gameplay, the original camera view and rendering on the system driver have not been verified on this branch.

## Android on the AYN Thor

Tested with update 1.0.7 and its 29 add-ons on an AYN Thor (Adreno 740) with Turnip v26.2.0-R5. New Game on Normal reached player control in the Bright Family House, the move outdoors and Elize Highway; a manual save was written and loaded again after a restart. The game held 60 FPS in the field at 8 to 11 ms of GPU time. In 1,316 recorded frames on Elize Highway no single frame darkened the road.

Frame rate: the game steps by elapsed time. A two-second walk ends at the same place at the game's own 60 FPS and at a cap of 30 (held exactly; a cap of 20 also held exactly, its walk was not measured), so Game settings offers caps up to 60 as experimental display pacing. The game limits itself to 60; the FPSLocker Warehouse patch for this build (`8963A531FA7F27A5`) changes a minimum frame delta and a resolution scale that the host rows cannot yet write, so 90 and 120 are not offered.

First launch: `vk_prewarm.bin` holds the pipeline keys of a Turnip run. With it a cold start prepares the shader parts of 1,271 programs in about 8 seconds, and the first scenes draw without pipelines made late; the next start takes about 1 second.

System driver: the stock Qualcomm driver starts the game and draws the title screen, menus and HUD, but the 3D field stays black, also on a second start. Use Turnip.

## Found on this game

- [GPU timestamps per submission segment](../../../docs/reference/frame-rate.md#gpu-timestamps-per-submission-segment): the dynamic resolution reads the GPU time between the frame's timestamp reports, which span submissions; with one time per submission it held the scene at 800x450 of the 1600x900 targets, which the game upscaled into a blurred picture with dark smears around foliage and painted-looking rock faces (Eden on the same Thor drew them sharp). `gpu_timestamps segment` in the profile: 1600x900, GPU p50 8.3 ms / p95 17.9 ms, frame time p95 17.7 ms, 60 fps held in the field on the Thor with Turnip T30 (resolved, 2026-11-24). Found by dumping the scene's targets (the scene filled their top-left quarter), after the textures' mip levels and samplers were ruled out.
