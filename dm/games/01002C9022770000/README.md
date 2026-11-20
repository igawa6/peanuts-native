# Trails in the Sky 1st Chapter (`01002C9022770000`)

<!-- PEANUTS -->
The profile targets Switch update **1.0.4 (`v196608`)** only: SDK 19.3.13, NVN, and the three build IDs in [game.tsv](game.tsv). The lookup tables identify 6,600 shader keys; they contain no shader programs. Prepare uses the player's own dump.

At `main+0x467710`, startup requests the application pool rounded down to 2 MiB, less `0x48200000`, for the heap. The `0xCD500000` pool bounds that request at `0x85200000`. Independently, the allocator requests `10 × 0x48200000` bytes of virtual address space at `main+0x46b5c8`; the profile leaves 16 GiB for alias mappings. These are reservation sizes, not physical RAM measurements.

The first chapter's pixel key `e356305a9fe7e4de` identifies the same shadow-depth stitch as the second chapter's `eb824d256484ca7f`. On Turnip, the existing D16 depth-only pass split now recognizes either key; it does not change other drivers or apply the second chapter's frame pacing settings. Check for single-frame darkening of the road on Elize Highway after New Game. Gameplay, the original camera view and rendering on the system driver have not been verified on this branch.
