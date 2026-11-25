# Advance Wars 1+2: Re-Boot Camp (`0100300012F2A000`)

Base game 1.0.0 (v0), the only version, SDK 10.4.1 (The Great Ace Attorney Chronicles' `sdk` and movie module builds, [010036E00FB20000](../010036E00FB20000/README.md)). Unity 2020.2.1f3 IL2CPP on NVN; FMOD sound through `AudioOut`; Bink 2 movies (`.bk2`) decoded by Bink's Unity plugin in `main`; Pia / NEX for online play. Modules `main`, `subsdk0` (`NintendoSDK_movie`, which `main` imports from; the movies seen so far, the opening among them, are Bink's) and `sdk`. Plays on the iPhone (IPA 7.1 GB).

On the Mac: the boot, the Bink opening movie, the title, Nell's introduction, a new casual campaign and the first training battle (Field Training, Troop Orders); sound, about 2.5 GB resident and 1.6–1.9 GB of Metal allocations, level over 15 minutes with no red line.

## What it needs

| Setting | Where | Why |
|---|---|---|
| Node area cut 1 GB → 62 MB, and 2 MB alignment for regions of 4 MB and more (SDK 20's) | `dm/tools/sdk_patches.py` SDK-wide rules, `sdk` build `d9add76f…` | StandardAllocator's region (more than 1 GB) aborted in `nninitStartup`; see [Memory](#memory) |
| `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | the NPDM declares 16 MB of system resource (virtual address memory) |
| 5 GB block with a 2.5 GB pool: `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `app_pool 0xA0000000` | `game.tsv` | Engage's 5 GB block (4.375 GB alias region) and pool. The heap check's 1 GB pool ran the game's malloc dry at the first loading screen; 2 GB ran out on Android once maps past the pool were refused ([Memory](#memory)) <!-- PEANUTS --> |
| `fpslocker … vsync_u32 0x481b2f8/0xb8/0x0`, `present_interval display`, `fps_limit 60` | `game.tsv` | Android: Unity renders every frame in battle, 60 fps where the Switch has 30 ([Frame rate](#frame-rate)) <!-- PEANUTS --> |
| `pad_layout full` | `game.tsv` | the 3D gamepad layout: the map cursor moves with the D-pad |

<!-- PEANUTS begin: Android -->
## Android

**Current: plays on Android.** AYN Thor (Adreno 740), Turnip 27.0.0-R2 through `DM_VK_DRIVER`, 2026-11-22, base game 1.0.0 (main build `320A17744AEFD67F`): the boot, the Bink opening movie, the title, a new campaign and the first training battle, about 2.5 minutes, two save commits. 59.7 fps in the menus and in battle (44 of 8,386 frames dropped, the loading screens), no red line besides the Metal statistics stubs; a few audio underruns at startup. All 20,424 shaders translate for Vulkan, Bink's included. It needed the pool of [Memory](#memory) and the frame-rate row of [Frame rate](#frame-rate).

- Not tested: Qualcomm's system driver, a whole mission, the other movies, a long session (the address space's room past 2.5 minutes).
<!-- PEANUTS end -->

## Frame rate

- 60 fps in the menus, 30 fps in battle by the game's own limit (present interval 1, the frame's work 33 ms with the CPU and GPU mostly idle).
- PEANUTS, Android: **current: 60 fps in battle too.** The game sets Unity's `OnDemandRendering.renderFrameInterval` to 1 in the menus and 2 in battle; `Update` still runs every frame and Unity renders every second one, so rendering every frame does not change the game's speed. An `fpslocker` `vsync_u32` row writes 60 / the cap there (1 at 60): the field is an IL2CPP static int, `main+0x481b2f8` holds the class, its `+0xb8` the static fields, the interval first (`0x481b2f8/0xb8/0x0`, main build `320a1774…` only). From [masagrator's FPSLocker Warehouse config](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/SaltySD/plugins/FPSLocker/patches/0100300012F2A000/320A17744AEFD67F.yaml) for this build, which writes −1; wired as Fire Emblem Engage's (`present_interval display`, `fps_limit 60`). AYN Thor, Turnip: 59.7 fps in the first training battle, GPU busy p50 7.1 ms.

## Memory

- The NPDM declares virtual address memory: `nninitStartup` (`main+0x365310`) hands pool − used to StandardAllocator as one address region and `main` sets no heap; Unity reserves its own regions beside it.
- **Resolved: `StandardAllocator::Initialize` aborted in `nninitStartup`** (`sdk+0x2edf90`, its `CentralHeap::Init` at `sdk+0x2ee060`; the heap check reported "the host stopped before nnMain"). The `sdk` build is The Great Ace Attorney Chronicles', which has no virtual address memory. Its AddressRegionManager constructor (`sdk+0x340e90`) loads the node area with `orr w8 / w9, wzr, #0x3fe00000` (`e8230b32` at `+0x340ea0`, `e9230b32` at `+0x340f3c`), the SDK 7 / 8 form, so the search for the `mov` form finds nothing there. `AllocateAddressRegion` (`sdk+0x341030`) aligns a region by its size (64 KB under 2 MB, 2 MB under 4 MB, 4 MB under 32 MB, 32 MB under 1 GB, else 1 GB, at `+0x34109c`), as DRAGON QUEST XI S's SDK 7.3.2 build, and the region is more than 1 GB. The cuts: both `orr` → `mov w8 / w9,#0x3e00000` (62 MB) and `orr w10,wzr,#0x40000000` → `mov w10,#0x2000000` at `+0x34109c` (32 MB, as from 32 MB up). Pikmin 4's SDK 10.4.8 build ([0100B7C00933A000](../0100B7C00933A000/README.md)) has the same cuts at the same offsets.
- **Resolved: then the frames stopped at the first loading screen, a graphics worker at 100% CPU** (17 s in, `UnityGfxDeviceWorker` running, the main thread waiting on a condition variable, no red line). The heap check had set a 1 GB pool (the hardware pool had used the alias region up to +4.25 GB within seconds), as for the other Unity games with virtual address memory, and that left the game's malloc too little: Unity's GPU memory pool block allocation (`main+0x96f430`) calls `aligned_alloc` through `main+0x866810`, halves the request after each failure, and once the request rounds to 0 calls on for ever with a size of 0. `sample <pid>` on the Mac host showed the spin (the guest frames are `???` in the host's image list; subtract the module base from the log's `base` lines); it worked here where lldb does not.
- **Current (PEANUTS): a 2.5 GB pool**, as Fire Emblem Engage's. **Resolved: on Android the game crashed 17 s into a boot** (SIGSEGV at `main+0x861fd8`). The 2 GB pool below was measured before the host refused maps past the pool ([Maps past the application pool fail as on Horizon](../../../docs/reference/memory.md#maps-past-the-application-pool-fail-as-on-horizon)), and the Mac's 15 minutes had already mapped 2.26 GB. With the limit, `MapPhysicalMemory` was refused at 1.94 GB mapped, and Unity's block allocator (`main+0x861f40`) took a block from its allocator's vtable and wrote the block's header at its end (`str q0, [x8, #-0x20]!`) without checking that the block had memory behind it. The Switch's own 3285 MB pool ran into a battle with 2.26 GB mapped, but StandardAllocator's reservation of nearly all of it took the alias region's high-water mark to +4.26 of its 4.38 GB in 100 s. With 2.5 GB, AYN Thor (Turnip), boot to a battle in 2.5 minutes: 2.26 GB mapped, no refused map, high-water mark +3.76 GB ([Sizing the pool and alias region](../../../docs/reference/memory.md#sizing-the-pool-and-alias-region-for-virtual-address-memory)).
- Before (2 GB pool, Mac): through 15 minutes of the title, the opening movie, the menus and the first training battle: 2.26 GB of the alias region mapped, its high-water mark +3.27 GB; the process holds about 2.5 GB on the Mac, 1.6–1.9 GB of it Metal allocations. The 1 GB of the heap check is a starting point, not a measurement ([dm/games/README.md, Adding a game](../README.md#adding-a-game)).
- **Android (#231, 2 GiB pool):** at the 2 GiB limit, 16–16.25 MiB physical mappings failed in the opening movie with 1.92–1.93 GiB mapped. The alias reservation still had room. The retail 3,285 MiB budget leaves the reservation and map limit unchanged.

## Movies

- The opening and the other movies seen so far are Bink 2 (`.bk2`), decoded by Bink's Unity plugin in `main`. Their 32 shaders are loose in `main` ([Files and shaders](#files-and-shaders)); without them every movie drew white.

## Files and shaders

- Everything is in Addressables bundles under `Data/StreamingAssets/aa/Switch`, read as Engage's (`unityfs.py`). `romfs_marker` is `Data/StreamingAssets/music.bank`.
- 20,424 shaders, all of which build: 20,392 from the bundles and Unity's 11 GLSLC outputs in `main`, and Bink's 32 (2 vertex, 22 pixel, 8 compute).
- **Resolved: every movie drew white.** Bink's movie player builds its programs from 32 control sections and code areas in `main`'s read-only segment that are in no GLSLC output and no BNSH file. 26 are 0x20-byte records in two tables in `.data` (`{code area pointer, control section pointer, u32 code length, u32 control length}`, both pointers relative relocations). The code takes the other 6 by address (`adrp` + `add` to the control section and, a few instructions away, to the code area it copies with `memcpy`); the code area lies right after the control section's own length (+0x20, 16-byte aligned) or right before it, and two control sections share one code area. Both neighbours can pass the header and length checks, so the reader (`shader_split.py` `raw_shaders`) takes only a code area the code builds the address of, the nearest one to the control section's. The 8 compute shaders' code areas have no header (SDK 10). Without them the shaders were not in the map (two `[RED] shader … is not in the shader map` lines and `program has no vertex shader` as the opening movie starts). Of 25 `main`s on hand no other has such control sections.

## Input

- The 3D pad layout: the map cursor moves with the D-pad (the tutorial battle was played with it on the Mac).

## Packing

- A regional release of the same build (`0100300012FFF000`, issue #114) packs from a profile derived from this one, with the folder's title ID and `main.npdm`'s program ID (`same_build`, `game.py` `by_build`; [development.md, The packing script in detail](../../../docs/development.md#the-packing-script-in-detail)).

## Mac routes

- The work folder's `run/step.sh <shot> <wait> <presses…>` drives a run started with `DM_CTL`. About 22 s into a boot the opening movie plays: A skips it, `L+R~0.5` opens the main menu (the first time Nell's introduction, A about 25 times, then the file select); A (Campaign), A, A (Advance Wars, the file), A, A, A, A (the mission, Field Training, Troop Orders) start the first battle.

## Found on this game

- **Shader control sections loose in `main`** (`shader_split.py` `raw_shaders`): [Loose control sections and code areas in `main` (Bink 2)](../../../docs/reference/shaders.md#loose-control-sections-and-code-areas-in-main-bink-2).
- **`sdk` node-area and 1 GB alignment cuts in the `orr` form** of the SDK 7 / 8 constructor: [The SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area), [1 GB alignment of regions of 1 GB and more](../../../docs/reference/memory.md#1-gb-alignment-of-regions-of-1-gb-and-more).
- **The heap check's 1 GB pool is too small here**: [dm/games/README.md, Adding a game](../README.md#adding-a-game).
- The spin-wait search's store rule takes one wider loop in its `main`: [development.md, Power](../../../docs/development.md#power).
- PEANUTS: **a pool measured before maps past it were refused is too small under the limit**: [Sizing the pool and alias region for virtual address memory](../../../docs/reference/memory.md#sizing-the-pool-and-alias-region-for-virtual-address-memory).
