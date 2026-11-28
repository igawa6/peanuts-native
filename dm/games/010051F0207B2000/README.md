# Tomodachi Life: Living the Dream (`010051F0207B2000`)
<!-- PEANUTS: Android game profile and validation notes. -->

Update 1.0.4 (v262144), SDK 20, NVN. `main` and `sdk` are the only modules. The PC shader build produced 14,469 Vulkan shaders.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_used 0x6200000` (98 MiB) | [`game.tsv`](game.tsv) | The rewritten modules occupy 98 MiB. At the default reported usage of 64 MiB, the SDK requests a `0xc9400000` heap that the remaining pool cannot fit. Boot fails after the rejected `SetHeapSize`. |

## Android status

The AYN Thor with Turnip Gen8 V39 reached first-run setup at 30 fps without a heap override. Display-paced caps were offered as an experiment (20 to 120 fps, Game owned by default) until GitHub issue #182: the game steps its logic once a frame, so at 60 it ran at double speed (an Odin 3 owner). The profile now keeps the game's own 30 fps timing only; a higher cap needs a reviewed frame-step plan. <!-- PEANUTS -->

Android bundles 361 Vulkan pipeline keys recorded on Thor. With Turnip Gen8 V39 and an empty cache, “Preparing shaders” built 289 pipelines in 4.9 seconds. The next launch reused the cache and skipped the progress screen. [Cold startup](../../../docs/reference/rendering.md#a-draw-skipped-for-its-pipeline-left-a-result-the-game-kept-android)

## Contributor findings

In [PR #86](https://github.com/igawa6/peanuts-native/pull/86), intermission88 tested the island at 30 fps on an AYN Thor with Turnip 27.0.0 R2. One of three boots crashed when the SDK read from address `0x24` at `sdk+0x37cdfc`. Resolved: [The boot crash in the font engine](#the-boot-crash-in-the-font-engine). <!-- PEANUTS -->

[nx2android](https://github.com/shirai91/nx2android/commit/7c9422aeba615c7a849dc314b387b14d708995d9) traced missing Mii heads and faces to a conditional depth clear in the Mii renderer.

On the AYN Thor with Turnip Gen8 V39, both settings reached the Mii creator's face-balance screen at 30 fps. Enabling `render_cond` removed the host's ignored-condition warning, with the same visible face previews. The profile leaves the setting off pending a comparison of the in-game Mii models.

**`render_cond on` since GitHub issues #196 and #182** <!-- PEANUTS -->: with the conditions ignored, the host ran every conditional clear and draw. In the Mii creator the Mii model stayed empty (an AYN Thor Max with Turnip T30 and Kimchi R8), and in a town save the ground and much of the scene came out black, the Miis' bodies white and the lamp posts dark. With `render_cond on`, on the AYN Thor with Turnip v26.2.0 R5, the creator draws the Mii's head and every face in its grid, and the town from the issue #182 save draws its ground, lit buildings and lamp lights.

<!-- PEANUTS begin: time (GitHub issue #329) -->
## Time and the clock check

**Resolved (GitHub issue #329):** at most starts the game said "Your system time has changed since the last time you played" and "If you start the game as is, the in-game time may change, or the items offered in shops may not change for a little while"; then the fountain gave nothing, the market stayed "Still setting up..." in the afternoon and at night, and no new foods came, for days. The save was started on Eden and played on a phone.

The game keeps two adjustable `ClockSnapshot`s in `Player.sav` and compares the saved one with a new one at start (`main+0x8713a8` on 1.0.4). When it cannot compute the span (status 2), or the user clock moved by any amount (status 3), it shows both messages and shifts every timestamp it stores by that change, so the time does not count. The host's steady clock stopped while the phone slept, so every sleep came out as the user setting the clock forward, and the game threw that time away; the context could also come out a second off between two starts: [The steady clock runs through sleep and restarts](../../../docs/reference/services.md#the-steady-clock-runs-through-sleep-and-restarts). A save from before the fix, or from another emulator, warns once at its first start: its snapshots are of another clock.

Measured on the AYN Thor (Turnip T30) with a copy of the issue's save, all 269 timestamps of `Player.sav` compared before and after a start and a Save:

| Build | Start | Log | Messages | Timestamps |
|---|---|---|---|---|
| old clock | first start on the Thor | `the user clock was moved -30494 s` | both | −30,494 s |
| old clock | 38 minutes after its save, no deep sleep | `the user clock was moved -1 s` | both | −1 s |
| fix | first start (the save's snapshots of the old clock) | `two steady clocks and no network time, the span is unknown` | both, once | +11,051 s (the time since the save) |
| fix | the next start, right after | none | none | (not saved) |
| fix | 67 minutes after its save | none | none | unchanged |
| fix, Qualcomm's driver | 7 minutes after that save | none | none | (not saved) |

The Thor did not deep-sleep while plugged into the PC, so a start after a real sleep was not measured on it; `t_peanuts_time` runs that case.

Time of day (Settings › System, `DM_TIME`) sets the console's clock, which this game treats as the user changing it, as on a Switch: it warns and shifts its times by the change. Leave it on the phone's clock for this game.

On the Thor with the issue's save, the boot crashed in eight of fifteen starts, with both clocks and also with no message at start, in `nn::fontll::ScalableFontEngine` (`FSS_get_advance` and `map_char`; reads at 0 to 0x28; called from `main+0x105a1e8` ← `main+0x273218`, frame 11 or 12): the crash from PR #86 above, resolved in [The boot crash in the font engine](#the-boot-crash-in-the-font-engine).

## Found on this game

General host changes first needed here; each applies to every game:

- **Pipelines made again while playing at every start** on Qualcomm's driver before 512.800 (Smoother next start, `vka_pso.c` `sw_*`): [Pipelines made again while playing at every start](../../../docs/reference/rendering.md#pipelines-made-again-while-playing-at-every-start-android).
- **The steady clock runs through sleep and restarts:** [The steady clock runs through sleep and restarts](../../../docs/reference/services.md#the-steady-clock-runs-through-sleep-and-restarts).
- **Two threads of one core in a font engine:** [Two threads of one core in a font engine](../../../docs/reference/threads.md#two-threads-of-one-core-in-a-font-engine): the boot crash below.
<!-- PEANUTS end -->

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on 1.0.4 through 2 `aspect_plan` rows that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->

<!-- PEANUTS begin: the boot crash in the font engine -->
## The boot crash in the font engine

**Resolved:** about 3 s into a boot (frame 11 or 12), more often while a text window showed at the start, the game crashed in the SDK's font engine: `CRASH sig=11` reading address `0` to `0x28` at `sdk+0x37cdfc`, `sdk+0x37cd34`, `sdk+0x37cd68` or `sdk+0x37cff4` (`FSS_get_advance`), `sdk+0x384220` (`map_char`, under `FSS_map_char` ← `ScalableFontEngine::CheckGlyphExist` ← `main+0x105e7a0`), `sdk+0x383f14` (`map_font`) or `sdk+0x377ff4` (`FSS_set_scale`, under `SetScale` ← `main+0x105edc8`). The callers are the game's text layout, `main+0x105edf8` / `main+0x105e7a0` ← `main+0x105a1e8` ← `main+0x273218` ← `main+0x2730dc`. On the AYN Thor with one save (the issue #329 reporter's) it came in 3 of 8 boots on Turnip and 8 of 15 in an earlier series, and in 1 of 3 in PR #86. With the fix: none in 24 boots of about 45 s (16 on PurpleVK 26.3.0, a Turnip build, 8 on Qualcomm's driver), at the same 30 fps; in 6 of them the log shows `AppJobThreadPool0` taking engine 0 between two calls of the `Scene Prepare Thread`, and in one the `Scene Prepare Thread` waiting for it more than 1,000 times. The start's dialogs draw their text as before.

The game's font code is NintendoWare Font 20.5.3, linked into `main`. Its texture cache (`main+0x693930`, set up by `main+0x667b2c`) keeps one `ScalableFontEngine` per core: three, sead's core count set at `main+0x88dbb4`. It picks one with the game's own callback at `main+0x1d0d470`: sead's core number in a TLS slot, which a sead thread pinned to one core stores at its start (`main+0x2173274`), else `nn::os::GetCurrentCoreNumber`. Its per-engine font cache (`main+0x105ee64`) calls `SetFont` only when the engine's font is another. The job worker `AppJobThreadPool0` (priority 44, pinned to core 0) and the `Scene Prepare Thread` (made by `main+0x8a2f00` at priority 22, running at 50; created with the default core, so pinned to core 0) both lay out text on engine 0. On a Switch they never run at once: the job worker preempts the other one. On the host they did, and one thread's `SetFont` cleared the pointers the other was reading: [Two threads of one core in a font engine](../../../docs/reference/threads.md#two-threads-of-one-core-in-a-font-engine).

<!-- PEANUTS end -->
