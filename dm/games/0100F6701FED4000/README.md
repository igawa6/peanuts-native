# The Legend of Heroes: Trails through Daybreak II (`0100F6701FED4000`)

<!-- PEANUTS: Android bring-up -->
**Bring-up, base 1.0.0 only.** For a single-title Android test build, use `-PnxGames=0100F6701FED4000`. The profile is not a release compatibility claim.

The executable imports the movie module `subsdk0`; the host supplies `rtld`. The allocator reserves ten `0x4be00000`-byte virtual ranges, separately from its heap. The profile leaves 16 GiB for those ranges; this is address space, not physical memory use.

The shader map has 4,471 distinct keys: 885 vertex, 3,539 pixel, 37 compute, five hull and five domain. Eighteen originate in `main`; the rest come from RomFS. The 1,750 vertex/pixel pairs cover shader inputs, not observed draws. The water materials use tessellation; this change does not implement patch draws or establish that water renders correctly.

## Checking the descriptor pools

Start a new game on Normal, decline the optional import, and inspect the stationary recap dialog for flashing rectangles. The game rotates three full-size texture pools: texture 386 in the final composite should resolve to the 1920×1080 target, not a 640×360 target, and texture 393 in the dialog should resolve to the 4096×4096 font atlas, not a 64×64 image. See [the Vulkan descriptor-pool fix](../../../docs/reference/rendering.md#rotating-texture-pools-select-the-wrong-image).

Skip the recap and office event, dismiss the field and save tutorials, and move from Montmart Bistro through the door to the street. The expected HUD objective is “Gather info in Riverside”. This revision has not been run through this route; combat, save/load, water and extended play are unverified.
