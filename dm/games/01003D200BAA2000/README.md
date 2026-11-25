<!-- PEANUTS: new profile, provisional -->
# Pokémon Mystery Dungeon: Rescue Team DX (`01003D200BAA2000`)

Base game 1.0.0 (`v0`), SDK 7.4.0. **Unity 2018.3.12f1** (IL2CPP) with FMOD, rendering with **NVN**, 64-bit (the NPDM's byte 0xC is 0x17). Modules `main`, `subsdk0` and `sdk`. The closest profiles are the other Unity 2018 ones: [Raging Loop](../010020400E1C2000/README.md) (Unity 2018.3.10f1), [FINAL FANTASY CRYSTAL CHRONICLES Remastered Edition](../0100CE4010AAC000/README.md) (Unity 2018.4.2f1) and [Monster Sanctuary](../0100A0F00DA68000/README.md) (Unity 2018.4.23f1).

## Status

**Provisional.** The profile is the survey's: `scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py` wrote `game.tsv` and `shader_split.py` wrote the shader tables beside it. Nothing in it was checked by hand. It runs on an AYN Thor (Snapdragon 8 Gen 2, Turnip 27.0.0-R2) at 30 FPS, through the start hang that patches.py addresses (see [Found on this game](#found-on-this-game)) into play. It is on the 0.1.8 list of supported games.

**Build it from the base game 1.0.0.** The hang fix is for 1.0.0's code only: update 1.0.2 (`v131072`) has no build row and no fix, and its starts can hang. Leave the update out of the build until a 1.0.2 row is added.

## Builds

| Module | Build ID |
|---|---|
| `main` | `ca19d0695861144b8a0fd0c438264a76` |
| `subsdk0` | `baee264d3658fc6b6b581e5cbb662e3a` |
| `sdk` | `e30d2170165e907aba457c79fd83383d` |

The display version is the base game's: 1.0.0, `v0`. Only the base game is packed: the profile's `build`, `package` and `shader_versions` rows all say `v0`. There is no update row and no DLC imported (see [Open](#open)).

## Shaders

- `shader_split.py` found 9,817 programs: 16 compute, 5 geometry, 4,681 pixel, 5,115 vertex, and printed no `[RED]` line.
- By source: 9,637 in `data`, 2,671 UnityFS bundles, 1.92 GB, under `Data/StreamingAssets/ab` (`shader_groups /Data/=data`), and 180 in `main`, taken from 584 GLSLC output magics, of which 180 read whole.
- `shader_pairs.tsv` has 44 variation pairs.
- On Turnip a cold start reads the profile's tables and prepares 5,141 programs before the game starts
  (`shader parts: 5141 programs of the profile (5141 prepared)`); warm runs serve them from the driver's parts
  cache (`vk_pipelines-Turnip_27.0.0-R2-fp0.bin.parts`, 17.8 MB, the same 5,141 programs) in 0 ms.
- The system driver (Qualcomm Adreno, driver ID 8) never reads the profile's tables: `pcl_open()` returns before
  that when the driver provides no graphics pipeline library, so its logs print
  `shader parts: 0 programs of the profile (0 prepared)`. The game starts and plays on that path anyway, four
  starts, 900–7,800 frames, 2.4–2.6 fps in the first window and 29.9–30.0 after. On this driver the profile's
  tables buy a fast first scene, not a working start.
- `DM_VK_PSO_PREP=0` in `env.txt` skips the `preparing shaders:` step but not the table read: `prep_full()`
  (`dm/gfx/vk_android/vka_pso.c`) gates the read on `MemTotal` ≥ `DM_VK_PSO_PREP_MEM_MB` (default 10000 MiB),
  which a 14.9 GiB phone always passes. Measured 2026-11-23 on Turnip (logs `-2026-11-23-161734`, `-161915`:
  no `preparing shaders:` line, `5141 programs of the profile (5141 prepared)`, 29.9 fps). A Turnip start with
  the tables fully absent needs `DM_VK_PSO_PREP_MEM_MB` raised above `MemTotal` too; not run.

## Memory

The keys are the survey's, as generated; the heap check that would correct them runs on a Mac and did not run here (this profile was made on Linux). They are the values the other Unity 2018 profiles carry, [Raging Loop](../010020400E1C2000/README.md), [FINAL FANTASY CRYSTAL CHRONICLES Remastered Edition](../0100CE4010AAC000/README.md) and [Monster Sanctuary](../0100A0F00DA68000/README.md):

| Setting | Value |
|---|---|
| `mem_reserve` | `0x140000000` |
| `mem_alias` | `0x118000000` |
| `mem_stack` | `0x10000000` |
| `app_pool` | `0xCD500000` |
| `sys_resource` | `0x1000000` (read from the NPDM: 16 MB of system resource) |

## Found on this game

- Another project's closed, unmerged pull request (`seedds/nxnative` #1868) made a provisional profile of this title for update 1.0.2: `v131072`, `main` build ID `3ab632dee82d59448599b2291f30994a`, with the same `subsdk0` and `sdk` build IDs and the same 9,817 shaders. It reports the game playing on an iPad Pro at 30 fps. That profile sets `depth_mode zero`, which only the offline Metal pipeline bases read, so it is not set here.
- The importer refuses the `.nsz` of this dump with `does not match its checksum`, although `nsz -V` passes the same file: the dump's NCAs do not hash to their names, the game was altered before it was compressed. Build from the `.nsp`.
- About 4 of 6 starts (3 of 3 with `DM_WATCH=30` in `env.txt`) hung without a crash: black screen, no FPS, no sound, at random points from just after `audio #0 Start` to after the first frame. Four thread dumps are alike: the main thread (priority 29, core 0) waits on a per-job condition variable after posting a command to `UnityGfxDeviceWorker` (priority 44, core 2), which sleeps on its own condition variable one wait later. It is Unity 2018's ThreadedStreamBuffer handshake losing a wake-up ([threads.md](../../../docs/reference/threads.md#unitys-stream-buffer-handshake-loses-a-wake-up-on-apple-cores)); [patches.py](patches.py) turns its 131 plain loads into load-acquires. `DM_HITCH_DUMP` never fires before a first present; `DM_WATCH=30` does.

## Open

- The game has been played into its story on Turnip: title screen, Cubone select, the Eevee story scene, Tiny
  Woods B1F combat, and a saved game at the base's house (`fs mount PEGASUS` with the transactional save,
  log `01003D200BAA2000-20261123-161915.log`, ~5 minutes to frame 8,466, 29.9–30.0 fps throughout), the
  deepest progress tested so far. Twenty starts
  on the patched pack (2026-11-23), including two play sessions, four starts with the profile's shader tables
  unread, and eight back-to-back launches (logs `-2026-11-23-163059` through `-163240`, each reaching frame
  1,070+): **zero** `watchdog: no progress` lines in all twenty; pre-patch the same build hung 4 of 6 starts.
  Dungeons past Tiny Woods, movies, docked mode and the system driver's texture content (its own issue)
  remain open.
- On the launcher's system driver the game renders with missing texture content: the intro's Pelippers missing
  most of its textures, in-game foliage drawing solid green. The logs are silent (no `[RED]` lines, no skipped
  draws) and Turnip renders both correctly, a host Adreno-740 path issue, filed separately.
- No build row for the update 1.0.2 (`v131072`, `main` build ID `3ab632dee82d59448599b2291f30994a`).
- The DLC (`01003D200BAA2800`) is not imported.
