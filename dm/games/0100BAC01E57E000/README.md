# Ys X: Nordics (`0100BAC01E57E000`)

<!-- PEANUTS begin: Android -->
The profile targets the US Switch update **1.0.3 (`v196608`)** only: SDK 17.5.8, NVN, and the three build IDs in [game.tsv](game.tsv). The game runs on Falcom's own engine (fdk), in a NIS America build. The shaders are GLSLC files in `asset/nx/shader` of the RomFS, plus some in `main` (3,136 after the split). The add-on content has no code and no shaders.

## Android on the Retroid Pocket Nova

Tested with update 1.0.3 on a Retroid Pocket Nova (Turnip T30, panel at 120 Hz). Tested: boot, New Game to free control on the ship, loading saves in towns (Carnac, Rusveri), a cutscene, a short battle, and camera pans at 30, 40 and 60 fps. Memory: total PSS 2.6 to 2.7 GiB.

Not tested: the system (Qualcomm) driver, other devices, long play, most combat, area changes, fields away from towns, and the voice timing of cutscenes at 60 fps.

## Memory

Start-up (`main+0x8c26f0`) sets the heap to the free memory, rounded down to 2 MiB, less `0x42800000`. The `0xCD500000` pool bounds that request at about `0x8AC00000`. Separately, the engine allocator (`main+0x8c65d8`) requests `10 × 0x42800000` = `0x299000000` bytes (10.39 GiB) of address space with `AllocateAddressRegion`; `main+0x8c5290` sets the `0x42800000` block size. The SDK also takes its node area (about 1 GiB) at the start of the alias region. The profile uses the same partition as the Trails in the Sky profiles (same engine): 16 GiB of alias and a 2.25 GiB heap region. These are reservation sizes, not physical RAM measurements.

## Texture pools (`tex_pool_range`)

The game makes three texture pools of 1,048,576 entries. It uses one pool each frame, in turn. The host's texture table holds 2^20 IDs. Without the key, pools #2 and #3 start past the end of the table: the host drops their registrations, and their bound IDs overflow into the sampler bits. Then single frames show other textures (UI atlas on black, wrong layers, noise boxes), most while the camera moves.

The highest ID that the game registers is 475. `tex_pool_range 16384` gives each pool 16,384 IDs, so all three pools fit the texture table and the 65,536-entry image table. A/B, 30-s walk on the ship at 30 fps: 65 bad frames without the key, 0 with it.

`nvnCommandBufferResolveDepthBuffer` is a no-op on the host (open: the reason is inferred, see the code comment in `hle_nvn.c`). No visible error was seen.

## Frame rate

The game holds itself to 30 fps. `fdk::Framework` (pointer at `main+0xc50788`) keeps the target rate at `+0x34` and the lowest rate at `+0x30` (both 60 in the constructor). The game writes 30 to `+0x34` and 15 to `+0x30`, and calls the render device's `SetFrameRate(30)` (`main+0x8aa314`). That function sets the present interval to 60 / rate (2). Some states use 15 instead of 30 (`main+0x229f50`, `+0x22b41c`).

Unlock (main 1.0.3 only): one reviewed `fpslocker` row writes the frame limit to `Framework+0x34` every frame (`target_i32`). The launcher then presents at interval 1, and the host's frame limiter holds the rate. The choices are 30 (the default, the game's own rate), 40, 60, and Game owned.

The 60 fps variant: the frame step (`main+0x8678b0`) is the measured time, clamped to [1 / `+0x34`, 1 / `+0x30`] seconds. At 60 the game thread often starts a step a few ms after a long one (4 to 7 ms after 30 to 35 ms). The floor counts each short step as 16.7 ms, so the game clock ran fast: 1.03 to 1.18 of real time while walking, up to 1.19 in the intro cutscene (its shots were about 8 % shorter than at 30). The 40 and 60 caps start a variant of `main` (`patches.py` `VARIANT_CUTS['60']`) without the floor: the step is the measured time, at most 1/15 s. Measured: game clock 1.0000 of real time while walking at 60 and at 40; the intro cutscene at 60 has the same shot lengths as at 30 (113.5 s against 113.3 s). 30 runs the game's own code (1.002 of real time). Open: the code has many constants of 1/30 s, and not all are reviewed.

40 fps: on a 120 Hz panel, 40 is three refreshes a frame. Measured: 40.0 fps, every frame 25.0 ms on screen. On a 60 Hz panel 40 alternates 16.7 and 33.3 ms.

Sound (`audio_follow 0`): the game clock runs on elapsed time, so the sound must stay in real time and not follow the frame pace. Before the fix, the host counted each frame as 1/60 s at the 30 and 40 caps, so it measured the game pace as 0.500 and 0.667 of real time. The automatic mode then took the game for one that keeps its sound in step with its frames, and played the sound at half speed at 30 (0.667 at 40). The host now counts frames at the frame limit, and the profile turns the follow off.

## Even frame pacing

Game options › **Even frame pacing** (`DM_VK_PACE_EVEN`, recommended on for this game). At 60 fps on a 120 Hz panel, the copy of a heavy frame can end after the compositor's latch. That frame shows a refresh late, and the next frame shows for one refresh only (8.3 ms). Drops below 60 then look like bursts of 60 and 30. The option lets the present times follow a late frame instead of catching up, and keeps the queues shallow. It works only at a limit of 40 fps or more (at 30 it only adds latency).

Measured, 30-s camera pan at 60 fps (without screen recording):

| Scene | Option | fps | 8.3 ms frames | Latency, present to display (p50) |
|---|---|---|---|---|
| Ship deck | off | 59.6 | 0.71 % | 61.7 ms |
| Ship deck | on | 59.5 to 59.8 | 0.00 to 0.06 % | 55.8 to 61.4 ms |
| Ship mast (heavier) | off | 58.3 | 0.66 % | 67.2 ms |
| Ship mast (heavier) | on | 57.9 to 58.1 | 0.06 to 0.12 % | 56.3 to 57.6 ms |

Jumps of two refreshes or more stay at 0.2 to 0.4 a second. The 1 % low (35 to 52 fps) does not change with the option. A screen recording adds 8.3 ms frames (0.4 to 1.3 %) on the compositor side.

## Draw distance

Each map's scene file has a Clip block with per-platform ratios. The Switch values (`lod_ratio_nx`, `far_clip_ratio_nx`) are lower than the PS4 ones in many towns and fields, for example 0.5 instead of 1.0 in `mp_t1200` (Carnac) and `mp_t1250`. The scene loader (`main+0x3ddb08`) stores them at scene `+0x734` / `+0x738`.

Characters (NPCs, monsters, mounts and the player) have their own draw distance. The `CBasicChr` far fade (`main+0x244630`) fades a character out beyond a fixed 24 m from the camera target, or 2000 m on the sea map.

Game options › **Draw distance** is one choice (`option_group ysx_draw`). In the in-game panel it is in the Performance tab, above frame generation. Each tier puts a floor under both Clip ratios and sets the character distance:

| Tier | Clip ratio floor | Characters | Words |
|---|---|---|---|
| Default | as shipped | 24 m | 0 |
| High | 1.0 (the PS4 level): `fmaxnm` at `+0x3de8b4` and `+0x3deedc` | 48 m | 6 |
| Very high | 1.5: also the default constants at `+0x3de87c` and `+0x3deea4`, and the path for scenes without a Clip block at `+0x3de5a4` and `+0x3de5a8` | 72 m, plus bit 17 | 14 |
| Ultra (experimental) | 2.0: the same words as Very high | 96 m, plus bit 17 | 14 |

Characters: every tier rewrites the four words `+0x244824`, `+0x24482c`, `+0x244830` and `+0x24483c`, so the radius is selected in integer registers. The sea case keeps 2000 m.

Bit 17: Very high and Ultra replace the four `tbnz w,#17` at `+0x760c`, `+0x15bad0`, `+0x1605b0` and `+0x490494` with `nop`. Models whose `LOD_ysx` flags have bit 17 (15 models, among them the Carnac tree `o_m10plt41` and the ship hulls) otherwise skip `lod_ratio`, so their LOD distance did not scale.

The tiers are restart options. The loader checks every expected word before it writes any (log: `selected option code patches: 6 words` or `14 words`). Measured in Carnac at 60 fps, street view: all tiers 59.9 fps; GPU time p50 4.40 (Default), 4.90 (High), 5.40 (Very high), 5.63 ms (Ultra); total PSS 2.60 to 2.69 GiB. High and Ultra show NPCs and stalls beyond 24 m that Default does not show. Not measured: the CPU cost of more characters, busy towns (Reda), fields with monsters.

<!-- PEANUTS end -->
