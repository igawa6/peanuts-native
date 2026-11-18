# Crash Team Racing Nitro-Fueled (`0100F9F00C696000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.15 (v983040) on the base game. SDK 8.2.0. Vicarious Visions' Alchemy engine (build name Octane) with NVN graphics, Havok, FMOD and Bink 2. Modules `main`, `subsdk0` (the game's own GLSLC) and `sdk`.

**Status (current): plays on Android.** On an AYN Thor (Adreno 740, Turnip 27.0.0 R2) it boots, passes the licence screens, the title and the menus, and runs single races (Crash Cove, N. Gin Labs) at the game's own 30 fps with 5 to 11 ms of GPU time and 1.9 GiB of app memory. Not run on the system driver or on a Mac. The memory layout is still the survey's guess; it held for these runs.

## Builds

| Module | Build ID |
|---|---|
| `main` | `1c689518406930512c13ddf4217e7676` |
| `subsdk0` | `c8a3cc0b1d1858330a881c0c2fc87940` |
| `sdk` | `3ae34e689d7a35f13cbf42bfd672af8b` |

The companion of eden-duo-companions (package `CrashTeamRacingNitroFueled`) is for this build (`1C68951840693051`).

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x0` | `game.tsv` | the survey's guess (`dm/games/profile_guess.py`): `main` sets a heap of at most the pool − 64 MB, and the NPDM declares no system resource. Not measured |
| `mem_used 0x7000000` | `game.tsv` | the modules take 107 MB (`main` 84.4, `sdk` 13.0, `subsdk0` 9.2), more than the default 64 MB the heap request is checked against |
| `modules main subsdk0 sdk` | `game.tsv` | `subsdk0` is GLSLC, which `main` calls at run time |

No rewriter cut and no host rule of its own.

## Files and shaders

- RomFS: 608 files, 9.66 GB: 401 `.pak` archives under `archives/` (igArchive version 13, 74,311 files in all), 163 ghost files, 17 Bink 2 movies.
- `shader_split.py` found 1,947 shaders: 1,604 pixel, 341 vertex, 2 geometry. 1,933 are whole GLSLC outputs in the 86 shader `.igz` files of `archives/shaders_nx.pak` (2,582 outputs, one stage each; the other 400 archives hold none), read by the igArchive reader ([shaders.md](../../../docs/reference/shaders.md#glslc-outputs-in-alchemys-igarchive-files)); 12 are in `main` and 2 are the run-time captures below. Each output is one stage, so the 341 vertex shaders are paired with the pixel input cover.
- The Vulkan library: 1,947 of 1,947 translate, 2,063 modules, every one valid for `spirv-val`; 7.8 MB.

## Shaders compiled at run time

`main` holds 5 GLSL sources and imports `glslcCompile`. Two of them are compiled, a vertex and a pixel shader (keys `4e4208ca043b8c27` and `a48c0d8a47c19850`), within the first seconds of a boot on the Thor; without them the screen stays black through the first screens (`[RED] vk: shader … is not in the shader library`). They were captured on the Thor with `DM_GLSLC_CAPTURE` (`05716fdaa771c310.glslc`, `df823c2fe0d0e4f1.glslc`, the same names a Mac capture of this game in a fork of nxnative has) and are the `/glslc_capture/=runtime` rows of the map. A build needs that capture folder beside the RomFS, as for Fire Emblem: Three Houses; Build in the app runs the capture itself.

## Audio

The game opens two audio renderers, one at 32 kHz at boot and one at 48 kHz as its intro starts. A host that drives one stops presenting a few seconds later (`[RED] audren: a second renderer`). The host drives up to 4 ([audio.md](../../../docs/reference/audio.md#a-game-with-more-than-one-renderer)): on the Thor both render (`renderer 1 rendered 200 frames, 167 of them not silent`) and the game runs on. Judged by the log, not by ear.

## Frame rate

The game asks for present interval 2 (30 fps), as on the Switch, and steps its races by the time that passed. So the profile follows the display ticks (`present_interval display`) and leaves the rate to the host's frame limit: 60 by default (`fps_limit 60`), the launcher's choices from 20 to 120 (`fps_limit_max 120`). No code patch.

Measured on the Thor in single races, the race clock read off the companion's page against the wall clock:

| Limit | Frames a second | Race clock |
|---|---|---|
| 60 | 59 to 60 | 20.43 s in 20.43 s |
| 120 | 118 to 120 in the menus, 81 to 97 in a race | 15.34 s in 15.36 s |
| 20 | 19.8 to 19.9 | not read |

At 120 the display runs its 120 Hz mode; the race does not hold 120 on this device. 90, 45, 40 and 30 were not run.

## Rewrite census

From the survey, before any rewrite:

- `main`: 8,620,835 instructions, 386 undecodable, 10,268 `x18` uses, 185 `svc` (all `#0x26`).
- `sdk`: 1,376,579 instructions, 273 undecodable, 11,228 `x18` uses, 61 `svc`, 56 `mrs tpidrro_el0`, 32 `mrs cntpct_el0`.
- `subsdk0`: 1,427,545 instructions, 7 undecodable, 2,882 `x18` uses.

## Companion

The companion of eden-duo-companions 1.0.0 loads its native module for this build and shows its race page on the Thor's second screen: the ranked racers, the minimap with their icons, the timer, the lap and the position.

## Open

- The system driver, and more than two tracks.
- One screenshot of a race's opening flyby showed blocks of another camera's picture; not seen in the races. Not looked into.
- The memory keys are not measured.
- The frame limits not run (above), and physics or timers that may count frames at rates other than 30: none seen in two races.
