# Kirby and the Forgotten Land (`01004D300C5AE000`)

Update 1.1.0 (v65536) over the base game, SDK 11.4.0. HAL Laboratory's own engine on NintendoWare (G3d, Font, Fxt) with `nn::atk2` sound and NEX online. Modules `main`, `subsdk0` (NintendoSDK_movie 11.4.0) and `sdk`; no other profile ships these `sdk` and `subsdk0` builds. Plays on the Mac at 30 fps, as on the Switch: the online and difficulty prompts, the 1080p opening movie, the beach and the walk through Point of Arrival with its guide movies, the reveal of the city, inhaling a Blade Knight for the Sword ability, fights with Awoofies and Bronto Burts, the title screen and continuing a file. An unsigned IPA is built; iOS is untested.

## Android first-launch shader preparation

<!-- PEANUTS begin -->
On AYN Thor with Turnip v26.2.0 R5, a first walk from the opening beach with an empty cache triggered the 7% draw warning. The renderer recorded 23,880 draws waiting for pipelines in a 300-frame window. The profile bundles 418 recorded Vulkan pipeline keys from the opening route (412 graphics and six compute), so the Android host can prepare them before gameplay. Later stages can introduce additional pipelines.

The 39-game 0.1.5 verification APK prepared all 418 keys in 10.8 seconds on Turnip R5. Two 112-frame walks from the opening save recorded zero skipped draws, out of 125,170 draws on an empty cache and 125,687 on the warm relaunch.
<!-- PEANUTS end -->

## What it needs

| Setting | Where | Why |
|---|---|---|
| 4 GB block: `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | [`game.tsv`](game.tsv) | no system resource; 256 MB in front + 256 MB alias region + 256 MB stack region + 3.25 GB heap region. See [Memory](#memory) |
| `modules main subsdk0 sdk` | `game.tsv` | `subsdk0` is the movie library: the opening and ending movies and the guide movies |
| `shader_groups /gfx/=gfx /ptcl/=ptcl /lyt/=lyt /shader/=shader` | `game.tsv` | the shaders are BNSH archives in HAL's `.cmp` containers; see [Files and shaders](#files-and-shaders) |
| Node area cut and 2 MB alignment of large regions (SDK 20's) | `dm/tools/sdk_patches.py` SDK-wide rules, `sdk` build `b39add63…` | [Pokémon Legends: Arceus](../01001F5010DFA000/README.md)'s build, cut by the SDK-wide rules; this game has no virtual address memory and never reaches them |

No code of the game is cut, and no run-time key is set. The game asks for present interval 1 and paces itself at 30 fps.

## Memory

- The NPDM declares no system resource, so there is no virtual address memory: the game sets a heap of the pool less the used memory, 3.14 GB (`[mem] heap 3.14 GB (heap region 3.25 GB)`), which the 3.25 GB heap region holds.
- **Current:** 4.1 GB resident and 2.67 GB of Metal allocations on the Mac after 20 minutes in Point of Arrival (`footprint`: 2.6 GB untagged, the guest memory the game touches; 0.7 GB graphics; 0.6 GB large host mallocs). It grew with each new area (3.2 GB at the beach, 3.97 GB at the city) and then stayed nearly flat, 4.13 → 4.17 GB with Metal allocations steady at 2.67 GB, over a 15-minute soak in one area. The highest resident figure of the games so far; how much a phone allows is open.

## Files and shaders

- RomFS: 9,386 files, 5.72 GB. 7,201 `.cmp` and 430 `.cmpbin` containers (HAL's size-prefixed zstd and LZ4 frames), 52 `.bfstm` and 104 `.opus` streams, 36 WebM movies (`mov/EventMovie`, `mov/GuideMovie`).
- 27,297 shaders in the map (18,460 pixel, 8,755 vertex, 72 compute, 10 geometry): 18,476 in `gfx/`, 8,313 in `ptcl/` (70 of them compute), 387 in `lyt/`, 67 in the loose `shader/` files and 54 in BNSH archives embedded in `main`. Before the split opened the `.cmp` containers it found 121 ([HAL's `.cmp` containers](../../../docs/reference/shaders.md#hals-cmp-containers-a-size-prefixed-frame)). All of them built for the Mac without a failure, and no run met a shader missing from the map (`shader keys not in the shader map … 0`).

## Rendering

- **Current, harmless:** `[RED] texture type mismatch: shader … declares slot 1 / 2 as Metal type 2, texture ID … is type 7 (NVN target 2, format 0x38 …); bound as is`, once per shader, for eight UI layout pixel shaders so far (`lyt/TitleMenu`, `lyt/Resident/TitleLogo`, `lyt/Cmn/HeroPanel`, `lyt/Step/ScopeCine`, `lyt/Arena/Entry`). The texture is a 16×16×16 RGBA8 sRGB 3D texture, one of the colour-grading lookup tables (`texlist 2`), left in slots the layout material does not use: the layout shader samples slots 1 and 2 only when its uniform `c3[0].y` is non-zero.

## Movies

- The SDK's demuxer (libwebm in `subsdk0`) runs natively; the host decodes VP9 with libvpx and Vorbis with stb_vorbis into the game's CPU buffers (NV12, mode 1). The opening movie is 1920×1080 VP9 with a Vorbis track (2,297 frames); the guide movies that show a move in a corner of the screen are 480×270 VP9 without sound.
- **Resolved: the game froze at the first guide movie** ("A Jump", on the first walk from the beach, every time). A job worker (priority 46) returning an output buffer signalled the movie's events while the host decoder held its decode lock; the signal handed core 0 to the movie threads (priority 44), and the input thread among them was waiting for that lock in host code, which the handoff counted as running ([A host lock held across a same-core handoff](../../../docs/reference/threads.md#a-host-lock-held-across-a-same-core-handoff)).

## Frame rate

- **Current:** 30 fps on the Mac, as on the Switch (`power` lines: 28–30 fps; frame interval p99 33.6 ms in play). The game sets present interval 1 and paces its frames itself. CPU about one core and 5–5.6 W on an Apple M6 in Point of Arrival.

<!-- PEANUTS begin: frame limits from 20 to 120 -->

### Android: frame limits from 20 to 120

Reviewed `fpslocker` rows in [`game.tsv`](game.tsv) apply the memory writes of the [FPSLocker Warehouse config for this build](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/SaltySD/plugins/FPSLocker/patches/01004D300C5AE000/A6CE40DC3AEDB1BE.yaml) (v4; its interval offsets found by Hazerou), on Android and for main `a6ce40dc…` (1.1.0) only. No code is patched and no shader is rebuilt.

| Row | Address | What it does |
|---|---|---|
| `zero_i32` | `[main+0xA76728]+0x50` | the game's frame interval, 2 as shipped; 0 takes off its own 30 fps pacing and, by the config's note, makes the game step by the time that passed |
| `inverse_f32 30:1.95`, `30:1.7` | `[[[[main+0xA767B0]+0x140]+0x1B0]+0x20]+0x34`, `+0x38` | the dynamic resolution's GPU frame-time budget, most and least: 1.95 and 1.7 at 30 fps, scaled by 30 / the limit |

The host's frame limit then picks the rate once the player chooses one: the launcher's choices from 20 to 120 (`fps_limit_max 120`, 60 for `fps_limit 60`), and Game owned for the game's own 30 fps (a restart). Game owned was the default (`fps_owned_default 1`) until 0.1.8, see below; since then the game starts at 60. The budget's chain is four pointers deep, one more than the rows took, so the row format now takes four offsets after the first pointer ([`fpslocker_rows.h`](../../host/fpslocker_rows.h), `test_fpslocker_rows.py`).

Run on an AYN Thor (Adreno 740, Turnip 27.0.0 R2, 1920x1080), 2026-11-18, a pack built on a PC, from Continue: 4 s standing, 3 s of walking forward by the wall clock, then on to the first guide movie, 120 frames captured on the way. The writes land in play (`reviewed FPS memory writes applied at 60 fps`), not at the prompts or the opening movie, which run at the panel's rate either way.

| Limit | Frames a second (120 captured frames) | Worst frame | Where Kirby stands after 3 s of walking |
|---|---|---|---|
| Game owned | 30.0 | 38.1 ms | the reference |
| 30 | 29.9 | 35.4 ms | the same spot |
| 60 | 59.8 | 20.9 ms | the same spot |
| 20 | 17.9 (median frame 50.2 ms; about one frame in nine stays a fourth refresh) | 210 ms, at the guide movie's start | the same spot |
| 120, on the panel at 60 Hz | 54 to 55 (median frame 16.7 ms) | 208 to 218 ms, at the guide movie's start | the same spot |

So the game's speed follows the wall clock at every limit, and 60 holds on the Thor in these areas (GPU 5.5 to 9.4 ms a frame). The guide movie plays at each. About a minute more of walking, jumping and floating at 60: no crash, no red line of the game's.

Open: **120 on a 120 Hz panel.** The window asked for 120 (`window frame rate 120 fps`) and the Thor's panel stayed at 60 Hz, also with Android's peak and minimum refresh rate set to 120 for the run (put back after); what holds the panel there was not found, so no frame was shown above 60. Also open: 40, 45 and 90, 20's late frames, the opening movie under a limit other than 60, other areas and a long session, and frame generation with a limit.

**Game owned by default since GitHub issue #153.** On a Snapdragon 8+ Gen 1 (Poco F5 Pro, Turnip 27.0.0 R2, 0.1.5) the overworld collapsed to 3 to 6 fps at the 30 and the 60 limit alike, and Game owned held 30 there and in the levels. In the 30-limit log the frames slowed step by step as the overworld loaded (24, 16, 11, 6, then about 4 fps) while the GPU's median frame stayed near 30 ms and the replay recorded in about 10 ms: whole frames of 240 to 380 ms went to the game's threads waiting on each other. With the interval at 0 the game steps by the time that passed, and on this phone a slow frame hands the next one more work. The launcher now starts the game Game owned until the player picks a rate; the 20 to 120 limits stay, for phones that hold them (the Thor above). Open: a bound on the elapsed step, which would let the limits hold on slower phones.

**60 fps by default since 0.1.8** (the owner's call, 2026-11-24, for every game that started Game owned): with no pick the game starts at the 60 limit, so the reviewed rows above run from the first start. On a phone like the Poco F5 Pro above, pick **Game owned** in the game's Frame rate cap; a saved pick stays.

<!-- PEANUTS end -->

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

Game settings › Display offers **Fill the screen** beside Fit and Stretch (the game draws for the screen's own shape, from the next start), with no code of the game's own: one `aspect_plan` row (`width_movz` at `main+0x747408`). The game builds its screen size there (`mov x8,#1920; movk x8,#1080,lsl #32`, read on the 1.1.0 pack's main), and the loader makes the width the one for the picture's shape before code is sealed: 2520 for 21:9, 1234 for 8:7 ([`aspect_plan.h`](../../host/aspect_plan.h), `test_aspect_plan.py`). The site is the one the community's 21:9 patch for this build changes (Fl4sh9174, Switch-Emulator-Ultrawide-FPS-Mods: 2580 there, for 3440x1440), which says the cameras and part of the menus follow it.

The two runs below used `aspect 21:9` and `aspect 8:7` rows and the Aspect ratio list, before Fill replaced them in this profile at the owner's request.

**21:9 on an AYN Thor (2026-11-21, Turnip 27.0.0-R2, main `a6ce40dc…`, the top screen, started from the library with Aspect ratio 21:9):** the log has `aspect plan: 1 words for the 2.3333 picture`, and in the first stage the picture is 1920x823 with bars above and below, Kirby and the star blocks in their own proportions, the ability panel inside the picture and the coin counter near its right edge (outside the middle 16:9, so that part of the HUD follows the shape). 30 fps Game owned with the GPU at 9.3 ms, and 59 fps at the 60 limit with the GPU at 9.5 ms; no `CRASH` line in a run of about three minutes, two and a half of them the first start's shader preparation.

**8:7 on the Thor's second screen (the same build and driver, Settings › Library › Swap screens on, Aspect ratio 8:7):** the log has `aspect plan: 1 words for the 1.1429 picture` and `swapchain 1080x1240`; the picture fills the 1240x1080 screen with no bars and the scenery and Kirby keep their proportions, 59 fps at the 60 limit with the GPU at 11.1 ms on the beach, 47 fps a minute later in the first stage with the GPU at 8.6 ms and the SoC at 90 °C (the top screen's 21:9 runs came just before it). No HUD element was on screen in the two captures, so whether the HUD stays inside the narrower picture is open.

**Fill on the Thor's second screen (dev build 73e1d54e, Display › Fill the screen, Swap screens on):** the log has `aspect ratio: screen shape, window 1240x1080 -> 31:27` and `aspect plan: 1 words for the 1.1481 picture`, and the game runs at 60 fps on the title screen. **Known limit on a screen narrower than 16:9:** the title screen's game logo is cut at the picture's left edge (the game's own frame, `ctl` screenshot of frame 3325); the Nintendo and HAL marks in the corners and the file menu follow the shape. So the 3D view fills such a screen, and a 2D element the game places for 16:9 can fall outside it. Nothing of the kind was seen at 21:9.

Not run yet: Fill on a screen wider than 16:9, the HUD at 8:7, the menus and the movies at either shape, and a 16:9 capture of the same spot to measure how much the view changes.
<!-- PEANUTS end -->

## Audio

- `nn::atk2` through an audio renderer (96 voices, 14 effects, one sink). 131,543 of the first 132,000 rendered frames were not silent.
- **Current, open: with the silent sink the game often crashes right after Continue.** On the Mac's default output (no `DM_AUDIO`, or `DM_AUDIO=0` as `regress.py` runs it), `A` on the title's Continue crashed 7 boots of 11 within 4 s, during the load: `CRASH sig=11 addr=0 pc=main+0x6da824 lr=main+0x6dabf8`, on a job worker (priority 46, backtrace `main+0x6dabf8 main+0x803cd4 main+0x6a69e4 main+0x6d9e80 main+0x78a5b8`), with Continue pressed at 20 s or 26 s, an empty or a full pipeline archive, this host or the one the game was brought up with. With `DM_AUDIO=1` (Core Audio output) 3 boots of 3 reached the beach. The cause is not known: what the null pointer is and why the silent sink's pacing exposes it (2026-10-27).

## Android

- 2026-11-13, AYN Thor (Adreno 740), Turnip (PurpleVK 26.3.0): plays at 30 fps (GPU about 10 ms a frame), App 3.0 GiB, from Continue through Point of Arrival and the supermarket door.
- **Resolved: crashes while loading an area.** The game overwrites hundreds of MiB of write-protected texture storage in its 1.4 GiB pool (`0x40377ec000+0x5aa00000`, placed in 16 MiB parts) when it loads an area; made writable page by page, that passed `vm.max_map_count` ([Write faults made writable page by page split GPU memory past vm.max_map_count](../../../docs/reference/memory.md#write-faults-made-writable-page-by-page-split-gpu-memory-past-vmmax_map_count-android)).
- **Resolved: stutters at two spots of the first walk.** [Joining contiguous placed segments holds frames at the same spots](../../../docs/reference/memory.md#joining-contiguous-placed-segments-holds-frames-at-the-same-spots-android).
- **Current, open: with frame generation on, a dark translucent vertical column, about 14-24 px wide at 1920x1080, moves across the generated frames** (every other presented frame; the game's own frames are clean). Seen with flow 50 % at 1920x1080; the cause in the chain (`dm/gfx/vk_android/lsfg`) is not known. Frame generation off has none.

## Mac routes

- Build with `scripts/build_ipa_from_nsp.py <dump folder> --platform mac`; its last line runs the game.
- A fresh save, through `DM_CTL`: the online prompt is up about 20 s after boot: `Left`, `A` (Do Not Use), `A`; the difficulty: `Right`, `A` (Spring-Breeze Mode). The game saves here, then plays the opening movie and the beach scene (about 75 s) and gives control on the beach.
- With that save, the title shows Continue about 25 s after boot; `A` puts Kirby on the beach. Forward (`LUp`) leads to the first guide movie in about 15 s; the path then turns right past a rock, crosses a ravine under a plank (float up on the right with `A`) and runs forward between trees to the city reveal, then right past the building's base to the first Blade Knight.
- A `DM_CTL` press replaces the one being held: send the next key only after the hold ends.
- `smoke.tsv` (for `scripts/regress.py`) starts from that save, kept as `saves/beach` in the NSP folder. Run it with `--env DM_AUDIO=1` until the [crash after Continue with the silent sink](#audio) is fixed.

## Testing

- 2026-10-26, Mac: boot, the online and difficulty prompts, the opening movie, the beach, Point of Arrival with three guide movies, the city reveal, inhaling a Blade Knight (Sword), fighting with the Sword, losing it to damage, the title screen and Continue, over five runs (the longest 30 minutes, with a 15-minute soak of walking, jumping, floating, inhaling and attacking, no crash). The only red lines were the layout shaders' [texture type mismatches](#rendering); a key dump of the title menu's shader (`DM_NVN_KEYDUMP=5de6e5dc52a56300`) shows `c3[0].y` = 0, so its reads of slots 1 and 2 do not run.

## Open

- The rest of the game is untested on the Mac: no stage was finished, so no Waddle Dee Town, boss or Mouthful Mode yet, and no save at a stage's end.
- iOS: the unsigned IPA (`KirbyAndTheForgottenLand.ipa`, 6.4 GB, 6,846,727,151 bytes, with the fps counter; 27,297 iOS shader libraries, no pipeline archive) is untested on a device; its memory (4.1 GB resident on the Mac) may be the first limit there, above the 3.4 GB iOS gives an app without the Increased Memory Limit capability.

## Found on this game

- [HAL's `.cmp` containers: a size-prefixed frame](../../../docs/reference/shaders.md#hals-cmp-containers-a-size-prefixed-frame).
- [A host lock held across a same-core handoff](../../../docs/reference/threads.md#a-host-lock-held-across-a-same-core-handoff).
- [Write faults made writable page by page split GPU memory past vm.max_map_count](../../../docs/reference/memory.md#write-faults-made-writable-page-by-page-split-gpu-memory-past-vmmax_map_count-android).
- [Joining contiguous placed segments holds frames at the same spots](../../../docs/reference/memory.md#joining-contiguous-placed-segments-holds-frames-at-the-same-spots-android).
- [Joins for textures grow one segment until a join is refused](../../../docs/reference/memory.md#joins-for-textures-grow-one-segment-until-a-join-is-refused-android). <!-- PEANUTS(upstream-candidate) -->
