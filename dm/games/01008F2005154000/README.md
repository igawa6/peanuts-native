# South Park: The Fractured But Whole (`01008F2005154000`)

<!-- PEANUTS: profile imported from nxnative upstream (seedds/nxnative); tested on Android (POCO X7 Pro, Mali-G720, PanVK). -->

Update 1.05 (v327680) over the base game, SDK 3.5.1, Ubisoft's Snowdrop engine on NVN, with Havok and Wwise. The ExeFS loads `main`, `subsdk0` (GLSLC) and `sdk`. The six supplied DLC NSPs are licence packages at indices 1–6. The current Mac build reaches character creation with the preview rendered. The current iOS build needs a device retest.

## Status

**Plays on Android (POCO X7 Pro, Mali-G720, PanVK).** Opens and plays from the 1.05 update pack; about 29–31 fps real in normal scenes, about 50 fps presented with frame generation (x2); no crash in a 17-minute run. One dense area runs at about 5–15 fps (about 2,500 draws/frame vs 38 normally); the fps recovers on leaving the area. Not a host bug: the draw count rises and falls with the location.

On the system driver the game stays black: the readiness check fails (missing Placed memory maps), the guest memory alias export is unavailable, and the game runs at about 35–48 fps but draws only 2–5 items/frame (38 on PanVK), so the scene never renders; no crash. PanVK is required on this GPU.

Frame generation: transient title-screen corruption (green/red/yellow) on first open with framegen on; gone on re-entry and never returned in gameplay.

**Open on Android (PanVK): light sources render as a large flickering blue glow.** Most light sources — the basement flashlight, fire — render as a large flickering blue glow instead of their correct color and beam. On Switch and PC the same lights are steady and correctly colored, so this is a PanVK rendering issue, not the game's look. The v1.1.8 driver already fixed one lighting issue (integer texel buffers read as float now return the stored bits), but this bug persists, so it is a different path. A newer PanVK line (panvk-kbase v26.3.1, Mesa 26.3.0-devel) was tested and rejected: it cannot present on this phone (vkCreateSwapchainKHR fails with -1000072003), because it lacks the faux123 MediaTek display fix.

**On the Mac: reaches character creation with the preview rendered**, about 60 fps through most of the opening; the earlier Mac run also reached the first interactive scene. See [Mac testing](#mac-testing).

## Tested on

- Android: POCO X7 Pro (Dimensity 8400 Ultra, Mali-G720 MC7, 12 GB RAM), Android 15, Peanuts Native 0.1.8-1, PanVK faux123 v1.1.8 (Mesa 26.3.0-devel); also run on the system driver (black screen, above). Played from the title through the opening and Cartman's basement mission with Butters; about 17 minutes of play, Xbox controller.
- Mac: Apple M6 Mac mini, macOS 27.2.1 (see [Mac testing](#mac-testing)).
- Not tested: no Adreno phone (needed to land — see [Open](#open)); no iOS retest of the current IPA.

## Builds

- 1.05 (v327680): `main=df15edaaf603e00cd7d8a01bd05a15c2`, `subsdk0=6c8a6b30bd5e6ca1206e8b1cdcc93bf7`, `sdk=8bb65519bc6399ab432e0ba7cdb16e2b`. `package` is v327680.
- The six supplied DLC NSPs are licence packages at indices 1–6 (Towelie, Bring the Crunch, From Dusk Till Casa Bonita, Super Streamer Starter Kit, Danger Deck, Relics of Zaron).
- SDK 3.5.1; Snowdrop engine on NVN, with Havok and Wwise.

## Memory

- The NPDM declares 16 MB of system resource and `main` sets no heap: the engine takes its memory through address regions in the 8.375 GB alias region.
- **Resolved: 12 GB reservation.** The unmodified game reached alias +13.69 GB in the NSP script's first 15 s in `nnMain`. Its allocator initializer reserves three adjacent 4 GB ranges and sets the large allocator to 65,536 pages. `patches.py` matches the initializer and its callees in 1.05. It moves the bounds and caps the large allocator together to fit inside 4 GB: 1.5 GB large (24,576 pages), 512 MB for 512 KB pages and 2 GB for 64 KB pages. The free and slab paths read the stored range starts. The older SDK VammManager matches the shared rule for a 64 MB node area and 32 MB alignment for regions of 1 GB or more. [Separate virtual ranges in Snowdrop's page allocator](../../../docs/reference/memory.md#separate-virtual-ranges-in-snowdrops-page-allocator).
- **Resolved: 64 KB pool exhausted.** A first 3 GB cut with 512 MB of 64 KB pages aborted 2 s into nnMain: when that pool's bump pointer reaches its end (`main+0x17fb600`), it asks for another 4 GB at the range's end (`+0x17fb640`), a fixed-address reservation the SDK aborts on (Break at `sdk+0xa9dec`). The pool sizes come from a run of the original layout with a 64 GB alias region (`DM_ALIAS_SIZE`), with the pools' fields read by `DM_CTL` memdump at `main+0x4968900 + 0xdea80`: after 90 s (title, logos), 64 KB pages 848 MB (735 MB at 30 s), 512 KB pages 236 MB, large allocations 16,384 pages.
- **Resolved on Android: 4 GB reservation does not fit in a 4.375 GB alias region.** On Android (POCO X7 Pro, Mali-G720, PanVK), ~1 GB of the alias region is already used before the game's allocator runs (`alias region mapped up to +1.00 GB` in `nninitStartup`), so the 4 GB contiguous reservation fails, `AllocateAddressRegion` returns base 0 (unchecked), and the first use writes to `0x80000000 + 0xffff` (`0x8000ffff`), crashing with SIGSEGV in `DT_INIT` (`main+0x17fb4b0`). Confirmed by `DM_ALIAS_SIZE=0x200000000` (8 GB), which let the game open on the phone. The profile now sets `mem_alias` 8.375 GB and `mem_reserve` 9 GB, keeping the 128 MB heap cap. [Separate virtual ranges in Snowdrop's page allocator](../../../docs/reference/memory.md#separate-virtual-ranges-in-snowdrops-page-allocator).
- **Current measurements.** The compact Mac run reached alias +4.23 GB of 4.375 GB. PR #843's earlier prologue run reached the first interactive scene with this layout. A full pool can still hit the original 4 GB growth request. Peak use during later areas and battles remains open.

## Files and shaders

- The merged RomFS holds 3,010 files under `camel/sdf/nx/data`: 172 `BERG` data slices (`.sdfdata`) and 2,828 five-byte placeholders, a `.sdftoc` (SDF version 22), a `.sdfver`, two DDS boot images, and the build's own `_FileList.txt` and `_DataPartList.txt`, which list every packed file with its slice, offset and sizes (the offsets are the base game's: the update's slices are shifted).
- **Resolved: no shaders found by the split.** The shaders are GLSLC outputs in `camel/shadercache/nx/*.mcache`, stored as bare zlib streams (a 1 KB window, header `28 15`) without the 20-byte record header of the Mario + Rabbids games: [Shader records in Snowdrop SDF data slices](../../../docs/reference/shaders.md#shader-records-in-snowdrop-sdf-data-slices). The split finds 4,614 stages: 4,072 vertex, 467 fragment and 75 compute. All compile for the Mac.
- Fast Prepare: `shader_files.tsv` lists the 5 RomFS files a full split found shaders in (`sdf-A-0000`, `sdf-A-0002`, `sdf-A-0200`, `sdf-A-0201`, `sdf-A-0204`); the targeted split reads only those 5 of the 3,010 RomFS files. Full split 5m11.6s, targeted split 10.7s (Linux VM, Python 3.14, J=2); both reproduce the profile's tables byte for byte.

## Rendering

- The game sets NVN near depth to zero.
- **Resolved: missing character preview.** The preview's vertex streams declared a range beyond their pool, and the host skipped the draws even though their fetches fit. The rebuilt renderer drew the character in the same menu. [A vertex stream bound past its memory pool](../../../docs/reference/rendering.md#a-vertex-stream-bound-past-its-memory-pool).
- **Resolved on Mac: black loading screen and flickering title.** The same oversized binding affected the indexed UI draws and caused the startup draw warning. Captures show the game submitting the UI draws even on black frames. The renderer now checks the mapped vertex range using the CPU-readable indices. The loading indicator and title render again. [A vertex stream bound past its memory pool](../../../docs/reference/rendering.md#a-vertex-stream-bound-past-its-memory-pool).
- **Open on Android (PanVK): light sources render as a large flickering blue glow.** See [Status](#status).

## Mac testing

On 2026-11-21 the Apple M6 Mac mini (macOS 27.2.1) passed the title and opening cutscenes in a window with Metal HUD, audio output and the iPhone-sized alias region. The title held about 30 fps, all six DLC licences mounted and the game wrote `profile.sys` to the test save folder. The rebuilt renderer reached character creation and showed the preview. The earlier Mac run also reached the first interactive scene. [PR #843](https://github.com/seedds/nxnative/pull/843).

Press A on the title to reach Play and New Game. Hold B to skip the opening cinematic. In the character creator, choose Next, a difficulty and Create.

## Frame rate

Snowdrop requests NVN present interval 2 (30 fps at 60 Hz). The profile uses the host's existing display pacing to default to 60 fps. The launcher's **Frame rate** setting can restore 30 fps. [Present interval in display mode](../../../docs/reference/frame-rate.md#a-present-interval-that-is-the-games-only-limit-in-display-mode).

On the Apple M6 Mac mini, 1.05 reached about 60 fps through most of the opening, with a brief dip before returning to 60 fps. The original timing held about 30 fps. The visible Mac playtest reported no timing problems. The updated Mac bundle was checked at startup with both frame-rate settings. iOS has not been tested at 60 fps.

## iOS build

The unsigned separate-RomFS IPA builds all 4,614 shaders and includes the six DLC licence images. The IPA's RomFS identity matches the image from the 1.05 build. An earlier port ran on an iPhone 15 Pro Max. [PR #662](https://github.com/seedds/nxnative/pull/662).

## Found on this game

- [Shader records in Snowdrop SDF data slices](../../../docs/reference/shaders.md#shader-records-in-snowdrop-sdf-data-slices): bare zlib cache files.
- [Separate virtual ranges in Snowdrop's page allocator](../../../docs/reference/memory.md#separate-virtual-ranges-in-snowdrops-page-allocator).
- [NVN command buffer control memory](../../../docs/reference/nvn-objects.md#control-memory-capacity-queries).
- [A vertex stream bound past its memory pool](../../../docs/reference/rendering.md#a-vertex-stream-bound-past-its-memory-pool).
- [A save folder that cannot clone directories](../../../docs/reference/services.md#a-save-folder-that-cannot-clone-directories).
- [A hand-copied RomFS.img is not found on iOS](../../../docs/reference/packing.md#a-hand-copied-romfsimg-is-not-found-on-ios).

## Open

- **Adreno run needed to land.** The Mali run above counts for neither tier; the profile needs a run on an Adreno phone (12 GB or more, a named Turnip build), with its log and keys files. The PR stays open for any tester with one.
- Play into the first battle and beyond; measure the pools over sustained play, and the memory peak in later areas and battles.
- No prewarm keys attached yet: none were collected from the Mali runs; the Adreno run will provide its own.
- Find the root cause of the PanVK light glow (see [Status](#status)).
- Retest the current IPA on an iPhone.
