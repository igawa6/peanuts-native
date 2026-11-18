# Yoshi's Crafted World (`01006000040C2000`)

Update 1.0.1 (v65536) over the base game, SDK 4.4.1. Good-Feel's game on Unreal Engine 4.18 era (project `PJ033`, one `PJ033-Switch.pak`) with Nintendo's NVN RHI. Modules `main` and `sdk`. GMalloc is a FMallocBinned on the process heap (at `0x70f5004000`; the libc StandardAllocator is at `0x70f5000000`).

Status: brought up on the Mac. It boots, passes the start screen, the play-style menu and the opening, and shows the "How many Yoshis should go?" menu fully textured, with no red line; 10 of 10 muted boots ran clean for 40 s. The Yoshis' felt renders right through the opening since the streamed levels are kept (see [Rendering](#rendering)). Plays into the first levels on the Mac (user); from boot with that save, the [Mac route](#mac-routes) reaches the first level's gameplay at about 120 s, with no red line over 250 s. Retested on chain `86c8282f6fb4` (upstream `ce9b30b`): all 23,497 shaders translate, and over the 150 s route the opening plays at 30 fps with no red line. Retested on chain `7fbca6dd477d` (upstream `40e6bae`): all 23,497 shaders translate; from a fresh save the [step route](#mac-routes) reaches Rail-Yard Run's gameplay, with no red line over 12 minutes, the menus and opening at 30 fps and the level at 60; on chain `d374e783beec` (upstream `8334fdf`) all 23,497 again, and with that save the timed route reaches the level with no red line over 120 s. Not tested on the iPhone yet (IPA 6.1 GB).

## What it needs

| Setting | Where | Why |
|---|---|---|
| The heap memory layout (`mem_reserve 0x100000000`, `mem_alias 0x10000000`, no system resource) | [`game.tsv`](game.tsv) | the NPDM declares no system resource, so `nninitStartup` sets a 3.15 GB heap; the guesser's Unreal rule gave no heap and the app quit at launch; see [Memory](#memory) |
| `tex_flush_upload on`, `tex_dirty_upload on` | [`game.tsv`](game.tsv) | the texture streamer writes mip data into CPU-uncached pools with no flush; without both keys the 3D scene was black; see [Rendering](#rendering) |

## Memory

`nninitStartup` asks for a heap of the pool less used memory, 0xc5200000 bytes. The provisional profile first had the Unreal 64 GB alias region and no heap (`SetHeapSize OOM: req 0xc5200000 > cap 0`); [Unreal games without virtual address memory take a heap](../../../docs/reference/memory.md#unreal-games-without-virtual-address-memory-take-a-heap).

## Rendering

The deferred G-buffer pass writes six targets. Without the texture keys only the normals (smooth, from the vertices) and a mask came out; base colour and the lit scene stayed 0, while the UI drew: `packaged textures: first uploads 0`. The texture streamer (`main+0x2485ea0`) copies each cooked mip level into the buffer `RHILockTexture2D` (`main+0x27f5990`) returns, and the unlock writes it into the texture's storage, in `CPU_UNCACHED | GPU_CACHED` pools (flags 0xe2) that take no flush. With the keys: 977 first uploads by that menu.

The streamed textures are sparse (flag 0x20, no packaged layout), DXT1 / DXT5 (0x45, 0x48) and BGRA8 (0x9c), one level each, pre-swizzled in the game's files. `RHILockTexture2D` sizes its buffer from a per-level table at texture +0x120 that follows the host's level layout (the unpadded level size: 0x78000 for a 960×384 DXT5 level, which the host stores in 0x80000), and the copy takes the cooked size; see [Found on this game](#found-on-this-game).

The Yoshis are plain lightmapped static-mesh materials (fragment key `a7c457a031dcb113`: 1024² DXT1 base colour and BC5 normal, both sampled with a mip bias, an HQ lightmap and a static shadow mask; no shells, alpha test or alpha to coverage), so their felt is in the textures. From the moment the opening went dark they sampled bright speckles and dark blotches, with sharp silhouettes: `texraw <serial>.<level>` showed levels 0–3 of the base colour intact and level 4 holding another texture (level 4 of the normal all zero). The streamer re-creates a texture with more levels when finer ones stream in, copies the resident levels from the old texture on the GPU (`nvnCommandBufferCopyTextureToTexture` from `main+0x27f5044`, Unreal's `RHIAsyncReallocateTexture2D`) and writes the new levels from the CPU; the host then uploaded the whole texture from its storage, where the copied levels had never been written. Fixed for every game: [GPU copies into textures uploaded from their storage](../../../docs/reference/nvn-objects.md#gpu-copies-into-textures-uploaded-from-their-storage).

On the OnePlus Pad 2 (Adreno 750, Android 16, game 1.0.1), the broken title frame had 53 block-compressed Vulkan images whose finest mip was zero despite nonzero guest storage. Mirroring eligible whole-mip GPU copies into guest storage keeps later CPU uploads visible. With Turnip v32 the title island and felt in the opening rendered correctly; the Vulkan self-test also read back the CPU-filled and GPU-copied levels intact.

On Turnip v32, Rail-Yard Run was reached and Yoshi moved, jumped and collected coins, but the driver still hung in the course. Turning off LRZ, UBWC, the upscaler, threaded command encoding or the replay worker did not establish a workaround. On Qualcomm's system driver, validation caught linear filtering of an integer image and an oversized unused clip/cull interface. After both fixes those validation errors disappeared on the same route, but the driver still reported invalid GPU reads. One address-traced run faulted outside every address range the driver had reported, not in a known retired image. None of the 23,497 pack shaders uses the raw global-memory address table. Copying CPU-known uniform data at recording time did not prevent the stock fault either.

Turnip 27.0.0-R2 (Mesa `2648b2059f`) is not a reliable workaround on the Pad 2. One run passed the pipe and ramp and was stopped manually at frame 13,699 (247 s). A fresh launch using the same driver in the game's settings stalled at frame 1,578: fence 3,196 never completed, with the timeline at 3,195. A single playable run is not sufficient to certify this course.

On the Pad 2 with Turnip T30, after preparing integer buffer-texture rows in the same class used to bind them, Rail-Yard Run reached its results screen (69 coins, 3 red coins). Earlier direct-descriptor and bounded-index changes alone still hung on T30. This verifies one complete first course on game 1.0.1, not later levels or other drivers.

The world map on that run has broad colour bands in the sky and water, visible in the 1920×1080 window texture before display scaling. In a later 2× capture (3840×2160) Yoshi's felt still broke into coarse speckles. Both runs logged `more than 0 distinct samplers`: skipping the unused bindless table also prevented the creation of **direct** samplers. The game requested linear filtering, including for the 32³ RGB10A2 grading table at binding 260 of `3369c39f41b0ff42`, but got the default nearest sampler. Direct sampler creation now uses the device's sampler-object limit rather than the bindless table size. On the same T30 driver the corrected GPU self-test read 173 at a checker edge (linear: 173, nearest: 255).

Before/after gameplay captures in the same blue-background course, both on T30 at 2× (3840×2160), show the difference: the coarse speckles on Yoshi's nose, belly and wings are gone with the corrected sampler, the finer felt texture remains, and the blue background no longer has the broad colour steps visible in the earlier capture.

Internal resolution is separate from filtering. At 1×, the grading pass used a 1584×892 scene image on the map. In a course frame, the active area of a scene image that size was 1268×716; the remaining rows and columns were unused.

## Mac routes

From boot, `A` at 22, 25, 28, 31, 34 and 55 s passes the start screen and picks Mellow Mode; `Plus` then `A` at 62–70 s skips the opening; `A` every 10 s from 83 s reaches the "How many Yoshis should go?" menu by about 120 s (`DM_CTL`, [05-debugging](../../../docs/agents/05-debugging.md)). With a save that has a level unlocked, the same presses continue from the menu into it.

From a fresh save, step by step (`DM_CTL`, a shot after each step): the play-style menu is up by 20 s, `A` picks Mellow Mode and a second `A` leaves its notice; `A` on the title starts the opening; `Plus` once the "Skip" prompt shows (from about 40 s into the opening) skips to "How many Yoshis should go?"; `A` (One), `A` `A` (the green Yoshi), `A` four times through the dialogue reach Sunshine Station's map; `A` `A` enters Rail-Yard Run.

The probes that compare the lock size with the copy size: `DM_PROBE_ALL=1 DM_PROBE='main:27f5a54@x21+80,main:27f5a58@*x21+68+30,main:27f5a7c,main:2486490'` (x9 at `27f5a54` is the lock size, x0 at `27f5a7c` the buffer, x19 / x2 at `2486490` the copy's destination and size).

Unreal's purgatory proxy: a romfs overlay (`DM_ROMFS_OVERLAY`) with `UE4CommandLine.txt` = `../../../PJ033/PJ033.uproject -purgatorymallocproxy `; the check that reports a modified freed block branches at `main+0xae1a4c`, parks a block at `main+0xae177c` (x20, size at [sp+8]) and releases it at `main+0xae1a0c` (x26).

## Found on this game

- [Sparse textures of 8- and 16-byte blocks: blocks at most 8 GOBs high](../../../docs/reference/rendering.md#sparse-textures-of-8--and-16-byte-blocks-blocks-at-most-8-gobs-high): the crashes that moved between runs in 30–70% of boots (allocator asserts, bogus pointers) were 9 streamed DXT5 textures (384×384 to 1920×912) whose level data overran its upload buffer by 0x8000–0x20000 bytes. Found with Unreal's purgatory proxy and read-only parked blocks ([05-debugging](../../../docs/agents/05-debugging.md)).
- [Unreal games without virtual address memory take a heap](../../../docs/reference/memory.md#unreal-games-without-virtual-address-memory-take-a-heap): the launch crash of the provisional profile.
- [GPU copies into textures uploaded from their storage](../../../docs/reference/nvn-objects.md#gpu-copies-into-textures-uploaded-from-their-storage): the speckled felt once the opening went dark; the small levels of the streamed textures were stale or zero in the host's textures ([Rendering](#rendering)).
- [Sparse textures smaller than a tile keep the automatic block height](../../../docs/reference/rendering.md#sparse-textures-smaller-than-a-tile-keep-the-automatic-block-height): the boot loading icon (five 192×160 DXT5 frames) showed the left half of its chalk drawing and two stray strokes. Resolved: on chain `7fbca6dd477d` (upstream `40e6bae`) the icon at frame 60 shows the whole drawing; a host without the fix shows the broken one at the same frame; whole again on chain `d374e783beec` (upstream `8334fdf`).
- [Image atomics](../../../docs/reference/shaders.md#image-atomics): after `c3a0880`, the compute shaders `572c1b843ca32afb`, `8dbd4ee9b3da30d4` and `f0b33a6b4f3cc64f`, whose image atomics return a value (an add that hands out slots in r32ui buffer images, an exchange that swaps list heads), failed translation, and a `texture_buffer` image read as 2D stopped the host at frame 84 ("image atomics: unsupported or overflowing IMG extent"; [Buffer image reflection keeps its texture type](../../../docs/reference/shaders.md#buffer-image-reflection-keeps-its-texture-type)). Resolved upstream by `e72991d` (the host) and `2e655b7` (the translator); since `f8642c4` the three run as Metal's native texture atomics on their buffer images (retested on chain `a4e2088c2e69`).

## Open

- The iPhone (IPA 6.1 GB; 3.15 GB heap: check the memory it needs there).
