<!-- PEANUTS: adapted from nxnative's Brothership profile at 2f959ed. -->
# Mario & Luigi: Brothership (`01006D0017F7A000`)

## Status

The Android profile targets update 1.0.1. The shader tables and Fast Prepare file list were generated from 1.0.1. The beach renders on a clean Turnip launch with a starter seed and Background pipelines enabled. Qualcomm system-driver validation remains open. The profile leaves out the 60 and 120 fps overrides pending Android frame-time and game-speed measurements.

## Tested on

The base game ran headless on an Apple M6 Mac on 2026-11-04. The run went from an empty save through the opening movie, title, beach and first battle to the results screen, then loaded the autosave. The 30 and 60 fps runs reported no red lines. The frame-rate section records the separate Mac measurements. An earlier Android 1.0.1 run on an AYN Thor (Qualcomm QCS8550, 14.9 GiB available RAM, Android 13) with PurpleVK 26.3.0-devel (git-62ac221a33, driver version 0x6802063) and Background pipelines enabled showed a white 3D scene from a cold cache. A second launch drew the scene after loading the cache. A clean-cache validation run is still needed.

On 2026-11-28 the beach after loading a save was still white on the same Thor with the Qualcomm system driver (build `69e13475cb`, version `0x802a4035`). The app build was `00ebfe300df5`. The run read 1,606,092 bytes from its existing pipeline cache and skipped 592 draws while background pipelines compiled.

Cold-cache tests on the same Thor used the Android host at `7078567` and a compatible older shader library. With Background pipelines enabled and no seed, the beach rendered white on both drivers. A local starter seed merged from their captures supplied 257 keys. With PurpleVK the game processed all 257 before guest entry, then rendered the beach after loading the copied save. With the Qualcomm system driver, startup prewarm was explicitly enabled for the test. It processed the seed, but the run later crashed inside `vulkan.adreno.so` while loading the save. The startup prewarm experiment does not establish Qualcomm compatibility.

## Builds

| Version | Package | Main build | Evidence |
|---|---|---|---|
| 1.0.1 | `v65536` | `bf0607ac795b593a3f685934c249147c` | Full and targeted shader splits verified. Seeded cold launch reached the beach on PurpleVK. |
| 1.0.0 | `v0` | — | Historical Mac testing. |

The Android shader split used the 1.0.1 NUP on the AYN Thor, with four workers and the game's Oodle decoder. The full split took 40 s, and the targeted split took 35 s. The targeted split selected 2 of 119 RomFS files. Its `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv`, and `pso_pairs.tsv` matched the full split byte for byte.

## What it needs

No rewriter cuts of its own and no `patches.py`.

| Setting | Where | Why |
|---|---|---|
| `depth_mode zero` | [`game.tsv`](game.tsv) | the game sets near = 0 at startup (log: `depth mode near=0`); the NSP script's heap check found it |
| FMallocBinned3 constructor call cut, engine region given what the alias region has room for | the rewriter's engine-wide rules (`dm/tools/ue_malloc.py`, `dm/tools/ue_region.py`), no build ID | as the other Unreal 4.27 games (log: `62.00 GB asked, 2.81 GB of address space`) |
| Node area cut 1 GB → 62 MB, regions aligned to 2 MB | `dm/tools/sdk_patches.py`, the SDK-wide rules on `sdk` build `5bcf525f…` | the same `sdk` as OCTOPATH TRAVELER II |
| 3.75 GB guest block: `mem_reserve 0xF0000000`, `mem_alias 0xC8000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | the layout of the other Unreal 4.27 games, in place of the provisional 64 GB alias region |
| `sys_resource 0x5000000` | `game.tsv` | the NPDM declares 80 MB of system resource |
| `shader_groups GlobalShaderCache=global #Engine/=engine #Vermillion/=game` | `game.tsv` | the global shader cache, the engine's materials and the game's |

## Frame rate

The measurements below are from the Mac on 1.0.0. The game's Switch device profile inherits `rhi.syncinterval=2` (30 fps). The Mac tests used the host's Unreal frame-limit overrides.

- The same scripted route from an empty save, run side by side at 30 and at 60 fps (`run/ab.sh`), shows the same stage at the same times in all seven shots compared, from the movie's end through the title to the beach scene, at 30.0 and 59.8 fps: the game steps by the time that really passed. The first battle was played through at 60 fps (a timed stomp gave its EXCELLENT, the jump counters landed), and at 30 fps up to its stomps and enemy turns, before that run's time limit. `DM_FPS_TARGET=30` keeps the game's own 30.
- At 60 fps the windows of 300 frames stay at 58–60 fps; the dips to 49–54 are scene starts and the runs' own screenshots.
- **Historical Mac test at 120 fps**. The game also sets `t.MaxFPS` to 30 at run time, and the host's write follows the hold (`main: Unreal t.MaxFPS … 60 → 120 fps`). This holds in no particular version, since the rewriter finds Unreal's variables by name in any build; 1.0.0 is the one run, as the update in hand is damaged. The speed checks and first frame rates below ran headless on an M6 on 2026-11-05, one run at a time, from `run/save_ref`, with no red lines, and with another session's game running on the same Mac (scripts in the work folder's `run120/scripts`).
  - Speed, walking: the same timed walks (0.3 to 1.5 s, out and back), twice at each rate, covered 1,376 px of scene at 120 and 1,376 px at 60. Hold by hold the ratio is 0.95–1.04, within what repeats at one rate differ by (one hold 237 and 277 px apart at the same rate), except a 0.5 s hold that scrolled the scene about 0 px at both rates.
  - Speed, the first battle: the same walk reaches the same places and the same battle at both rates. With no press during them, the stomp (14.5–19 s, shots every 0.2 s) lines up with no shift between the rates and the enemies' turn ends at 29.4 s in all four runs. One 120 run's stomp came 2.0 s early, the spacing of the presses, so a press landed one earlier; the two runs at 120 differ by the same. This rules out double or half speed, not a difference of a few percent.
  - Frame rate: 119.4 fps in most windows of 300 frames on the beach and in the battle, and 60–72 for about 30 s of one battle (the host holds 60 when frames are late). Not measured with the Mac otherwise idle. CPU energy about 2.0–3.0 W against 1.1–1.5 W at 60.
  - In a window on a 120 Hz screen, with the Metal HUD and sound (about 3 minutes, same day, no other game running, no red lines): 119.3 fps at the end of the run, 2.16 W of CPU energy, and of 67 windows of 300 frames the slowest 1% took 8.33 ms in 36 and 16.67 ms in 23. The other 8 reached 25 to 108 ms; what was on screen then is unknown. The frame step fell to hold 2 five times and came back each time, the longest for about 560 frames, near the start.
  - Not measured at 120: timed stomps and counters landing (the presses were blind), the opening's Bink movie.

## Memory

On the Mac, on the small layout, through the first battle: 4.2 GB resident with 1.6 GB of Metal allocations at 60 fps (3.1 GB resident at 30 fps at the same stage of an earlier run), the alias region mapped up to +1.25 GB of 3.12 GB, the Unreal heap up to 1.00 GB. Not measured over a long run or on an iPhone.

## Files and shaders

- `Vermillion/Content/Paks/Vermillion-Switch.ucas` / `.utoc` (6.9 GB, TOC version 3, Oodle in 256 KB blocks, not encrypted, 56,109 chunks: 52,123 export bundles and 3,985 bulk data) hold the cooked packages. `Vermillion-Switch.pak` (version 11, 477 MB, 4,461 files, index not encrypted) holds the rest: Wwise banks and `.wem` files, fonts, localization, configs and the global shader cache. 111 `.bk2` movies lie loose in the RomFS.
- Shaders: 45,824 unique (13,581 vertex, 28,296 pixel, 3,814 compute, 133 geometry), inline in the export bundles. The split takes 39 s. A first full Mac build compiles them in about 45 minutes on an M6 (10 GB of libraries).
- The movies draw through a material, `Vermillion/Content/UI/Movie/MovieBinkMaterial.uasset`, so no shader of their own had to be found.

## Sound

Three outputs: renderer 0 (48 kHz, 32 voices, `main+0x358ca1c`), whose frames are not silent only during the movie; Wwise's own AudioOut (`main+0x365061c`, beside `AK::EventManager` in `main`); and Unreal's mixer on renderer 1 (32 kHz, 64 voices, `main+0x30a4ff0`), silent through all 108,000 frames of the movie, the field and the battle. With the renderers silent in the field, the sound there is Wwise's: no clipping, RMS about 1,000 in `DM_AUDIO_DUMP`, which interleaves all three outputs. The sound itself is still to be listened to. Those measurements predate per-renderer audio blocks: the movie's frames on renderer 0 could then be overwritten by renderer 1's silence before they played, which has not been checked on this game.

## Input

- A notice about autosave comes first on an empty save (A). The opening movie, about 2.5 minutes, took neither A nor Plus as a skip. Blind A presses at the title start a new game, and pass the beach scene.
- With a save the title goes to Load (A, A); control comes back in about 40 s from boot, with no movie.
- On the beach the crabs are scenery, and the rock wall north of the start is passed at its west end, over the round ledges. The left stick walks relative to the camera. The first battle starts on touching a sprout on the upper ledge.
- In a battle, A opens Jump, chooses the target, and lands the stomp and the jump counter when pressed in time. Tutorial pages turn and close with A.

## Mac routes

The work folder's `run/` has `host.sh` (copies a host to `build/mlhost`), `boot.sh <log> [seconds] VAR=…` (headless), `see.sh`, `shots.sh`, `mash.sh`, `sum.sh`, `stop.sh`, `to_battle.sh` (from control at the beach start to the first battle), `fight.sh` (A once a second, with shots), `ab.sh` (the frame-rate comparison) and `rec.sh` (recording the prewarm list). `run/save_ref` holds the autosave at the beach start.

## Found on this game

General change first needed here:

- **More than one audio renderer** (`hle_audren.c`): [A game with more than one renderer](../../../docs/reference/audio.md#a-game-with-more-than-one-renderer).

## Open

- **Current (2026-11-05):** the translator deletes 22 bindless accesses in 22 shaders (22 compute; imageBuffer 22), whose results read 0: `0117c7c8fbb6a5f2` `1ca3816f389a18ce` `4361dbbe8797c4da` `43bbad1ccaa23ea7` `55d9bc02a957cfc0` `58ebebab3418e50d` `632d83ffd8db2071` `6a89a43f0576e2b2` `73c08353f5d37896` `7acf11f6ad2d5919` `855ea5d0afce0e9b` `997096cef1c7b5f6` `ac980d4c676534bc` `ace307c991c69468` `b0786eaacfe8e5e5` `b407c64ae334d810` `c25e6888e5ff5949` `c4ef1679388f7ce1` `c7db95be19f06c62` `e00fb401a551b766` `e3a0dc30ae36ab4f` `f73848747856be53`. The cause is not traced, and whether a route reaches them is not known.
- Past the first battle: nothing is tested. No IPA, no iPhone run.
- The sound has not been listened to, and no route was found to make Unreal's renderer sound.
- Android 1.0.1: investigate the Qualcomm driver crash during the seeded run and measure frame times and game speed before offering profile caps.
