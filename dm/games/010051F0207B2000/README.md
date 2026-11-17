# Tomodachi Life: Living the Dream (`010051F0207B2000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.4 (v262144) over the base game, SDK 20.5.3. Nintendo's engine (sead, agl, gsys; NintendoWare G3d2, Ui2d, Vfx2, Font; Havok) with NVN graphics. Modules `main` and `sdk`.

**Status (current): plays on Android, little tested.** On an AYN Thor (Adreno 740, Turnip 27.0.0 R2) it boots and plays the island with a save brought from another emulator, at 30 fps. One boot of three crashed (below). Not run on the system driver or on the Mac.

The profile's keys come from [nx2android](https://github.com/shirai91/nx2android)'s profile of this game (`nxnative/dm/games/010051F0207B2000/game.tsv`), which records it as playable on a Snapdragon 8 Elite. Its build IDs are this dump's. The shader tables were made again here with `shader_split.py`.

## Builds

| Module | Build ID |
|---|---|
| `main` | `b39fef373fb12154385d012aab3bc99e` |
| `sdk` | `651781821e2c2e0b2bdfe905e8676f56` |

## What it needs

| Setting | Where | Why |
|---|---|---|
| 4 GB layout: `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | [`game.tsv`](game.tsv) | `main` sets a heap of at most the pool less 64 MB |
| `sys_resource 0x0` | `game.tsv` | the NPDM declares no system resource |
| `render_cond on` | `game.tsv` | the Mii renderer clears the depth of its colour pass under a render condition; run unconditionally, the clear wiped the depth pre-pass and every Mii head failed its depth test (nx2android's finding) |
| `mem_used 0x6600000` | `game.tsv` | the rewritten modules are larger than the default 64 MB |

## Files and shaders

`shader_split.py` finds 14,389 shaders (compute 193, geometry 3, pixel 11,118, vertex 3,075) in the RomFS's archives and in `main`. Four control sections in `main` (`0x278d0b8`, `0x278dc38`, `0x278eeb8`, `0x278f938`) have no code area and are left out (`[RED]` at the split). The Android library has 16,639 SPIR-V modules, every one valid for `spirv-val`.

## Frame rate

The game asks for present interval 3, then 2 (`main+0x27f5c4`): 30 fps on the Thor, frame time p50 33.5 ms. In the island scene a frame has about 2,900 draws; the GPU is busy 17 ms a frame at p50 and 34 ms at p90.

## Found on this game

Nothing general yet.

## Open

- **A crash at boot, 1 of 3 boots (2026-11-17), open.** `CRASH sig=11 addr=0x24 pc=sdk+0x37cdfc lr=sdk+0x37cdf0`, 2.9 s after start: a call in `sdk` returned null and its field at `0x24` was read. Back trace from `main+0x105edf8`. Not investigated.
- `[RED] vk: sampler reduction mode 2 not applied` (once) and `texture ID 838861 has no view of shape 0x1: it reads as unbound` (8 lines) in the island. What they draw wrong, if anything, is not checked.
- nx2android sets `DM_STEADY_REALTIME=1` for this game, so that its clock spans hold across device reboots. This host has no such switch.
- Not run: the system driver, the Mac, a new save from the start.
