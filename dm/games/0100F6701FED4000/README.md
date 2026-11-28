# The Legend of Heroes: Trails through Daybreak II (`0100F6701FED4000`)

<!-- PEANUTS: Android bring-up -->
On the release list from 0.1.10 (`peanuts/release-titles.txt`, so in the default APK). Its `readiness.tsv` stays: `status bring-up` and the feature rows tell a player what the phone lacks. <!-- PEANUTS: on the 0.1.10 release list -->

**Bring-up, base 1.0.0 only.** The base NACP reports 1.0.0 (`v0`); no updates are registered. The 64-bit executable uses NVN and Nintendo SDK 17.5.6. For a single-title Android test build, use `-PnxGames=0100F6701FED4000`. The profile is not a release compatibility claim.

The executable imports the movie module `subsdk0`; the host supplies `rtld`. The allocator reserves ten `0x4be00000`-byte virtual ranges, separately from its heap. The profile leaves 16 GiB for those ranges; this is address space, not physical memory use.

The shader map has 4,471 distinct keys: 885 vertex, 3,539 pixel, 37 compute, five hull and five domain. Eighteen originate in `main`; the rest come from RomFS. The 1,750 vertex/pixel pairs cover shader inputs, not observed draws. The water materials use tessellation; this profile does not implement patch draws or establish that water renders correctly.

## The descriptor pools

The game uses three 1,048,576-entry texture pools. The Vulkan backend used to discard registrations outside the first range and let texture-base bits enter the sampler field. In a trace before the fix, the final composite's texture 386 changed from a 1920×1080 to a 640×360 source between frames 6442 and 6446, and the dialog draw's texture 393 from the 4096×4096 font atlas to a 64×64 image. See [Rotating texture pools select the wrong image](../../../docs/reference/rendering.md#rotating-texture-pools-select-the-wrong-image) for the shared Vulkan fix.

To check it: start a New Game on Normal, decline the optional import, and inspect the stationary recap dialog for flashing rectangles. Texture 386 in the final composite should resolve to the 1920×1080 target and texture 393 in the dialog to the font atlas.

## Hardware checkpoints

The first two runs were made on an earlier tree than the current `main`, with the descriptor-pool fix. The third is on the 0.1.8 runtime with this branch.

- **AYN Odin 2, Adreno 740, 0.1.8 runtime (2026-11-26):** main 0.1.8 with the descriptor-pool fix and this profile, pack prepared on the phone (4,471 of 4,471 shaders, 11,355 modules valid). On Turnip 27.0.0-R2, New Game → Normal → decline import → recap → opening → office → field and save tutorials → Montmart Bistro → out of its door onto the street. In a 22-second stationary recap recording (663 frames), 0 frames had colored rectangles, the red corner or a dialog dropout. 30 fps steady in the Bistro (median 33.3 ms, max 33.3 ms, SurfaceFlinger). No crash or device loss on Turnip. On the Qualcomm system driver the device was lost about 14 s after start, before the title screen (see Known limits).

- **AYN Thor, Adreno 740, Turnip v27.0.0-R1:** before the fix, five of eight before-present readbacks from the recap contained colored rectangles, and the red corner appeared in 72 of 452 frames of a stationary recap recording; with the fix it appeared in 0 of 602, and materially mismatching dialog frames fell from 148 to 0. The composite and font sources had the expected dimensions in 8 of 8 traced frames each. New Game → Normal → decline import → skip the recap → skip the office event reached the field and save tutorials; after both were dismissed, forward stick input moved Van from Montmart Bistro through its door onto the cobbled street, with building surfaces, shadows and the "Gather info in Riverside" HUD visible.
- **AYN Odin 2, Adreno 740, Android 13:** on both the Qualcomm system driver (Vulkan 1.3.128) and Turnip v27.0.0-R1 (Vulkan 1.4.363), New Game → Normal → decline import reached the recap; 22-second stationary recordings (661 frames each) showed neither the red corner nor a one-frame dialog dropout. On the system driver, after Skip, the opening cinematic played, then the host reported a GPU hang (`VK_ERROR_DEVICE_LOST`, submission 13559 at frame 6779) and exited: field gameplay was not reached on that driver. On Turnip the same Bistro route reached the street labelled **Edith – Old Town** with the "Gather info in Riverside" HUD.

This is early field control, not a compatibility claim: manual save/load, combat, later areas, water and extended play are unverified.

## Known limits

- **Qualcomm system driver (Adreno 740):** on the 0.1.8 runtime the device is lost (a submission returns -4) about 14 s after start, at frame 421 to 427, before the title screen, in 2 of 2 runs (cold and warm pipeline cache). Both runs log `compute pipeline a8c332caff006556: -13; its dispatches are skipped` first. On an earlier tree it was lost after the opening cinematic. Field gameplay is not reached on that driver. Use Turnip.
- Water patch draws (tessellation) are not implemented; see `readiness.tsv`.
