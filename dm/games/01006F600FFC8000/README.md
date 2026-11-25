# LEGO MARVEL Super Heroes (`01006F600FFC8000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.1 (v65536) over the base game 1.0.0 (v0), SDK 9.3.1. TT Games' own engine (Nu2: DAT archives, NuUbershader), the engine of [LEGO CITY UNDERCOVER](../010085500130A000/README.md#for-the-next-tt-games-title), on NVN, with Havok. Modules `main`, `subsdk0` (GLSLC, NVN's run-time GLSL compiler), `subsdk1` (NintendoSDK_movie) and `sdk`.

**Status (current): plays on an AYN Thor (Turnip 27.0.0-R2), at 30 fps as the game asks or at 60 with the launcher's frame limit.** Checked 2026-11-23 with a pack of 1.0.1 built on a PC: boot, the menus, the opening cutscene (Iron Man and the Silver Surfer) and play in Sand Central Station. At 30: 29.9 fps, frame time p50 33.5 ms, GPU busy p50 9-16 ms (p90 up to 30 in the heaviest stretch), 7 frames dropped in 9,888. At 60 (Game settings, frame limit): 59.7 fps, frame time p50 16.7 ms, GPU busy p50 / p90 4-10 / 7-12 ms, 3 dropped in 4,790, and the game's speed looked normal in play. No crash; no `[RED]` line but the `mtl_*` statistics stubs, except one buffer → texture copy in the first run (below). The profile is the survey's guess (`dm/games/profile_guess.py`) plus the frame rate keys. Not run on the system driver. <!-- PEANUTS -->

## Builds

| Module | Build ID |
|---|---|
| `main` | `5d769abcad9f2743805a77e138efc900` |
| `subsdk0` | `0e0956c8d8d7489bf178e9efd9055a3b` |
| `subsdk1` | `5a7ada8dd7447c10aa244944f64d0aff` |
| `sdk` | `ad3fdfbd62a5f4b082d385ffe420d440` |

The app builds 1.0.1 only (`android_builds v65536`).

## Files and shaders

- RomFS: `GAME.DAT`, `GAME0.DAT`, `GAME1.DAT` (5.8 GB together) and the update's `PATCH.DAT`; no loose files.
- The shaders are single-stage BNSH files in the DAT archives' `.nnx_shaders` resources, read as for LEGO CITY UNDERCOVER ([BNSH files in TT Games' DAT archives](../../../docs/reference/shaders.md#bnsh-files-in-tt-games-dat-archives)). `shader_split.py` on the update's RomFS and `main`, `J=8`: 10,787 shaders (pixel 9,166, vertex 1,621), 10,456 from the DATs and 331 from `main`'s GLSLC outputs; no variation pairs (each BNSH holds one stage), no `[RED]` line.
- As in LEGO CITY UNDERCOVER, the engine can compile a shader its caches lack at run time through `subsdk0`'s GLSLC; such a key would be missing from the shader map.

## Frame rate <!-- PEANUTS -->

The game presents at 30 (present interval 2). Its engine steps by measured frame time, as LEGO CITY UNDERCOVER's (`GameFramework`, `FrameworkEnvironment::SetFrameTime`, `CalcFrameTimes` in `main`), so the profile offers the launcher's frame limit as experimental: `fps_limit_present 1` with `fps_limit_max 120`. The launcher offers a limit for such a game only with a maximum above 60 (`Profile.optInPresentRate`); the game starts Game owned (30) until the player picks a rate. At 60 the game's speed looked normal in play; a measured comparison with 30 was not made. The Thor's display was at 60 Hz, so 90 and 120 were not tried.

## Memory

The survey's guess: `main` sets a heap of at most the pool − 64 MB (`mem_reserve 0x100000000`, `app_pool 0xCD500000`); the NPDM declares no system resource (`sys_resource 0x0`). Not measured.

## Open

- **Open: one `[RED] vk: buffer → texture copy source at 0x40cb3fb000 (524288 bytes) is not in placed guest RAM`** in the first run, at 189 s in Sand Central Station: a 512 KiB copy into a 1024x1024 texture (format 0x42) whose storage was not in placed guest RAM either. Nothing looked wrong on screen; it did not come back in the second run. Not looked into ([A buffer ↔ texture copy names only the bytes it copies](../../../docs/reference/memory.md#a-buffer--texture-copy-names-only-the-bytes-it-copies-android) is the closest known case).
- **Open: only Turnip was run**, not the system driver.
- Longer play: other levels, the open-world Manhattan hub, two players.

## Found on this game

Nothing yet.
