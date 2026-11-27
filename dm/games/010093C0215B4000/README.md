# The Hundred Line -Last Defense Academy- (`010093C0215B4000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.2.1 (v589824) over the base game; no add-on content. SDK 18.3.2. Media.Vision's own engine (MVGL, Lua scripts) on NVN, with CRIWARE, as [Digimon Story Time Stranger](../010062E01FE0C000/README.md). Modules: `main`, `subsdk0` (NintendoSDK_movie) and `sdk`.

**Status (current): plays on Android.** On a Retroid Pocket Nova (Android 13, Adreno 740) with Turnip T30, 2026-11-25 and 2026-11-26: the title, a new game's opening movies, the first free-roam scenes, the visual-novel dialogue, the tutorial battle (won, with a special attack) and the classroom of Day 1, with the right picture, at 30 fps (the game's own rate). 60 fps is a launcher choice (below). On Qualcomm's system driver the 3D world is not drawn (open, below); use Turnip. The profile started as a provisional one from `dm/games/profile_guess.py`; its memory keys are the survey's guess, measured on the device up to Day 1 (below). The game was packed on a Windows PC (`pack_android.py`). One device only.

## Builds

| Module | Build ID |
|---|---|
| `main` | `d4a7205845845bca707f9b2f60a7096b` |
| `subsdk0` | `14765b1d77b12d8654ea87a61c65e2b1` |
| `sdk` | `f118ae1c2e7728471aaf1a0d58484968` (the same build as [Absolum](../0100A83022A22000/README.md)'s; `sdk_patches.py` applies its five address-region cuts by function match) |

## What it needs

| Setting | Where | Why |
|---|---|---|
| `modules main subsdk0 sdk` | [`game.tsv`](game.tsv) | `subsdk0` is the movie module |
| `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x8000000` | `game.tsv` | the survey's guess: the NPDM declares 128 MB of system resource, and `main` never sets a heap (it does not import `SetMemoryHeapSize`). Measured up to Day 1: the guest used about 58 % of `app_pool` ([Android](#android)) |
| no `mem_used` | `game.tsv` | the rewritten modules (`main` 36.2 MB, `sdk` 13.4 MB, `subsdk0` 7.3 MB) fit in the default 64 MB (`profile_guess.mem_used` on the pack gives none) |
| `romfs_marker app_0.nx64.mvgl` | `game.tsv` | the largest MVGL archive (2.93 GB), as Digimon's profile |
| `shader_groups /=romfs` | `game.tsv` | every RomFS file is at the root |
| `fps_limit 30`, `fps_limit_min 30`, `fps_modes 30,60`, two `fpslocker` rows | `game.tsv` | the game's own 30 fps limiter, raised to 60 by the launcher's choice ([Frame rate](#frame-rate)) |

## Files and shaders

- RomFS of 1.2.1: 26 files, 13.62 GB, all at the root: 17 MVGL archives (`*.nx64.mvgl`; `app_0` 2.93 GB, 64,274 entries), 7 CRI CPK archives (movies and sound), `boot.json` and `disp_revision`.
- **MVGL, 64-bit layout with 24-byte data entries.** The header, the 16-byte tree entries and the 0x80-byte name entries are Digimon's, but each data entry is u64 offset, u64 length, u64 stored length. The tables end exactly at the data start with 24-byte entries (app_1: 0x20 + 441 × 16 + 441 × 0x80 + 440 × 24 = 0x12170). [`mvgl.py`](../../gfx/tools/mvgl.py) `data_entry` tells the two sizes apart (16 first). Compressed entries are LZ4 blocks.
- **Shaders:** `shad` entries are NXVS / NXFS containers, as in Digimon (16,810 in `app_0`, 618 in `patch_0`, 120 in `app_1`, 18 in `app_switch_0`). `main` holds 179 more NX?S containers. `shader_split.py` finds **7,204 shaders** (4,779 pixel, 2,425 vertex): 7,026 from the archives and 178 from `main`. It reports no `[RED]` line, no variation pair and an empty conflict table.
- The archives also hold 222 `.glsl` entries (GLSL 4.40 source). `main` does not import GLSLC, so they are taken to be leftovers, as in Digimon.

<!-- PEANUTS begin: Android -->
## Frame rate

The game holds itself to 30 fps. It asks for present interval 1, and its main loop sleeps out the rest of each frame. There is no FPSLocker Warehouse config for this build, so the profile has two reviewed rows of its own (main 1.2.1 only):

| Row | Address | What it does |
|---|---|---|
| `target_i32` | `main+0xc97ce4` | the frame rate. It is 60 in the image. `main+0x6a8310` sets it; at start `main+0x4533ec` sets 30 |
| `step_ticks_u32 1000000:1` | `main+0xc97d18` | the frame time in µs, round(1,000,000 / fps), written by `main+0x6c7ef0`. The main loop (`main+0x6c7c50`) sleeps this time less the frame's elapsed time |

- The host's frame limit picks the rate: 30 by default (the game's own value), 60 when the launcher's choice is 60. Game owned writes nothing.
- 60 is a mode the game knows. Its debug menu has an `FPS` item (30 / 60, `main+0x3e0c74`). Some per-frame code acts every second frame when the rate is 60 (`main+0x41549c`, `main+0x487354`).
- The game clock steps by the measured frame time, capped at 1/30 s (`main+0x506e7c`).
- 45, 40 and 20 are not offered. The per-frame code checks for exactly 60, and the clock's 1/30 s cap would slow the game below 30.
- At 60, the dynamic resolution (`main+0x4e0e84`) compares the measured rate with the frame rate. It may lower the resolution where 60 does not hold.
- **Checked on the device (current).** Retroid Pocket Nova, Turnip T30, 2026-11-26:
  - At the default (30), the log says `frame limit 30 fps (game profile fps_limit)`. The rows write nothing, because the game has set 30 itself.
  - At 60 (the launcher's choice), the log says `reviewed FPS memory writes applied at 60 fps`. 245 five-second windows: mean 59.85 fps, 5th percentile 59.7, lowest 57.7. The overlay's 1 % low is 42 to 53.
  - The game speed is the same at 30 and 60 for: walking (the same distance in 2 s), the dialogue text reveal, AUTO advance, the backlog highlight pulse, the movies (29.8 to 30.3 frames a second at both), and the idle battle field and battle cursor. Nothing runs twice as fast.
  - No resolution drop was seen at 60 (the same crops at 30 and 60).
  - At 60 the CPU and GPU run about 8 °C hotter on the same battle screen (56.4 to 59.2 °C against 48.5 to 51.1 °C).
  - Game owned writes nothing and runs at 30.
  - Not measured at 60: battle attacks, the enemy turn and the special attack, later chapters, and play longer than 8 minutes. Frame-counted code there is still possible. For this reason 60 is not the default.

## Android

- **Pack** (2026-11-25, Windows, `pack_android.py`): the modules are rewritten with no `[RED]` line. `main` and `subsdk0` have no cut; `sdk` has its 5 function-matched cuts. The shader library `v-327c6383b614` holds 9,060 modules, every one valid for `spirv-val --target-env vulkan1.3`. It is 88.5 MB.
- **Device.** Retroid Pocket Nova (Android 13, Adreno 740), Turnip T30. 2026-11-25 and 2026-11-26, 9 runs.
- **Start and play (current).** The title comes in 48 s or less. New Game plays about 10 minutes of opening movies with the right picture, then the first free-roam scene ("Abandoned School 1F Hallway"). Continue loads the autosave. Walking, the camera, the dialogue with portraits, the tutorial battle (2 waves, a special attack, the victory movie) and the classroom of Day 1 with 10 characters draw correctly. The sound plays.
- **Frame rate.** 30 fps, steady: 151 of 155 one-second samples in a run with the battle are 29.8 to 30.1; `dropped 0`. 60 fps: see [Frame rate](#frame-rate).
- **Errors.** No crash, no `FATAL`, no device lost, no shader missing from the library, no refused address region. The only `[RED]` lines are 13 `mtl_*` stubs, once each.
- **Memory (Day 1).** The guest mapped at most 1.85 GB of the 3.21 GB `app_pool` (about 58 %). The address region (4.06 GB) used about 46 % of the alias region, with no refusal. The process's PSS reached 2.96 GB at most; the lowest free memory (MemAvailable) was 2.47 GB; no low-memory kill.
- **Soak.** 15 minutes in the classroom: 30 fps, 47 to 51 °C. PSS grew by 2 to 3 MB a minute while idle (open, below).
- **First battle: a pipeline hitch.** At the first battle entry the overlay shows "Preparing graphics: N pipelines left", and the longest frames are 190.7 ms and 152.2 ms (`vk-psoasync` busy for 180 ms). 129 pipelines were built in play on that run, all new to the cache. A prewarm seed would build them before the first frame. The profile has no `vk_prewarm.bin`: the maintainer builds the seed, and the key list of this fork's runs (Turnip T30) is available on request.
- **Movies.** The game skips a movie clip only while Plus is **held for about 1 s**: holds of 1.0 s or more stopped the clip (3 of 3); taps of 0.5 s or less did nothing (5 of 5). Each clip needs its own hold. This is the game's own behaviour, not lost input: a Plus tap opens the battle menu. A hold with a physical button is not confirmed yet.
- **System driver (Qualcomm).** The game starts, and the title, the menus and the HUD draw, but the 3D world is not drawn (fog only). Not investigated; use Turnip.
- **Log size.** During the movies the game calls `oe::SetPerformanceConfiguration` every frame, and the host logs each call (`(ignored)`): 18,837 of 22,779 lines in the run with the opening movies. Harmless.

### Open

- **Digimon's job-pool cut.** `main` has the `PoseWorker` pool. Its threads are created at `main+0x6b7218`, but the pool object's layout differs from Digimon's. The tidy call pattern Digimon's cut skips (a function called on pool+0x80 and on pool+0xa0) is not found, so no cut is ported. Watch for the same symptoms: the picture stops while sound goes on, or a crash in the pool workers. Not seen in 39 minutes of play with a battle, 6 movie clips and the soak.
- **Memory after Day 1.** The memory keys are measured only up to the classroom of Day 1. Later chapters may map more. The heap check was skipped (no Mac host).
- **Idle PSS growth** of 2 to 3 MB a minute in the classroom. A longer soak is needed.
- **The first-battle hitch** (above) until a prewarm seed is built.
- **60 fps:** battle actions, later chapters and long play (above).
- **System driver:** the 3D world is not drawn (above).
- The NVN entry points and the SDK 18 NVN object sizes have not been checked against `docs/reference/nvn-objects.md`.
- The 222 `.glsl` entries are not checked.
- One of the 179 NX?S containers in `main` is not among the split's 178 `main` shaders. It may be a duplicate key; this is not checked.
- One device only.
<!-- PEANUTS end -->
