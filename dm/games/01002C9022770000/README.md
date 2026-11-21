# Trails in the Sky 1st Chapter (`01002C9022770000`)

<!-- PEANUTS -->
The profile targets Switch update **1.0.7 (`v393216`)** only: SDK 21.4.4, NVN, and the three build IDs in [game.tsv](game.tsv). The lookup tables identify 6,602 shader keys; they contain no shader programs. Prepare uses the player's own dump. Compared with 1.0.4, only `chr_cloth` shaders changed (six keys replaced by eight).

Startup requests the application pool rounded down to 2 MiB, less `0x48200000`, for the heap. The `0xCD500000` pool bounds that request at `0x85200000`. Independently, the allocator requests `10 × 0x48200000` bytes of virtual address space; 1.0.7 sets that `0x48200000` block size at `main+0x56c0d4`, and its NPDM matches the base game's. The profile leaves 16 GiB for alias mappings. These are reservation sizes, not physical RAM measurements.

The first chapter's pixel key `e356305a9fe7e4de` identifies the same shadow-depth stitch as the second chapter's `eb824d256484ca7f`. On Turnip, the existing D16 depth-only pass split now recognizes either key; it does not change other drivers or apply the second chapter's frame pacing settings. Check for single-frame darkening of the road on Elize Highway after New Game. Gameplay, the original camera view and rendering on the system driver have not been verified on this branch.
