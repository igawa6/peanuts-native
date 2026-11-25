<!-- PEANUTS: profile added in Peanuts Native -->

# WarioWare: Get It Together! (`0100563010E0C000`)

Base game 1.0.0 (v0) only; no update is in the profile. SDK 10.4.1, NintendoWare Bezel Engine 1.50 on NVN, the same engine as [Big Brain Academy: Brain vs. Brain](../0100620012D6E000/README.md). Modules `main`, `subsdk0` (GLSLC), `subsdk1` (the movie module) and `sdk`.

## Status

Boots and plays on an AYN Thor (Turnip) with no crash, below its 60 fps target (see Open). The profile was written from a survey of the game files and its memory keys are the survey's guess; they held in the run.

## Builds

| Build | Version | Modules |
|---|---|---|
| base | v0 (1.0.0) | `main` `e6784f640b1cdff90c8718a11294dbf3`, `subsdk0` `ebedb2645cb8a961714ebc29e9eee0a9`, `subsdk1` `84e50460493a12f26100e5150fbff271`, `sdk` `d9add76fc264863d85fbbd1d70076c76` |

No update and no DLC are in the profile. The profile has no `patches.py`.

## Shaders

- 11,906 shaders found by `shader_split.py`: 28 compute, 14 geometry, 9,202 pixel and 2,662 vertex. By the profile's shader groups: 9,924 in `archive` (`/Archive/`), 1,930 in other files, 49 in `main` and 3 in `movie`. The scan reported no `[RED]` line.
- Most shaders are BNSH in the `.bfsha` entries of Bezel Engine archives. How they are stored and found is in [BNSH in Bezel Engine archives](../../../docs/reference/shaders.md#bnsh-in-bezel-engine-archives), and the `.bea` format is in [Big Brain Academy's note](../0100620012D6E000/README.md#files-and-shaders).
- The 14 geometry shaders belong to Bezel's line renderer (see Open).

## Memory

The keys in [`game.tsv`](game.tsv) are the survey's guess, not measured:

- `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`: the same 4 GB layout as Big Brain Academy, which sets a 3.3 GB heap region.
- `sys_resource 0x0`: the NPDM declares no system resource, so `IsVirtualAddressMemoryEnabled` is false and `nninitStartup` takes the heap path, as on Horizon. See [Virtual address memory follows the NPDM's system resource](../../../docs/reference/memory.md#virtual-address-memory-follows-the-npdms-system-resource).

## Files

RomFS has 13,815 files, 1.28 GB in all: 420 Bezel Engine archives (`.bea`), 13,380 Opus streams (`.opus`) and 14 movies (`.webm` and `.mp4`). The romfs marker is `Archive/Sound.nx.bea`.

## Found on this game

Nothing general yet: the frame-rate limit (Open) is not a reference entry until its cause is known.

## Open

- **Frame rate, current (2026-11-23, AYN Thor, Adreno 740, Turnip 27.0.0-R2):** reaches the frame loop and ran about 11,100 frames with no crash, at 32 / 41 / 62 fps (min / median / max) against the game's 60. The GPU is busy only 5-8 ms a frame; the replay worker is the limit. The game makes about 200 queue submissions a frame whatever its draw count (19 draws on the title screen, about 394 in play), nearly each with one counter report (`occlusion queries per frame … 202.4 reports`, no query begun). The host turns each `nvnQueueSubmitCommands` into one Vulkan submission (`hle_nvn.c`; `nvnQueueFlush` does nothing), and each submission waits for the GPU work of the one eight before it (`NSLOT`, `vka_exec.c`): 7-9 ms a frame waiting for a command buffer slot, 3-4.6 ms in `vkQueueSubmit`. On NVN the GPU boundary is the flush (and present, Finish, a fenced sync), not each submit, so merging the submits between those points in the Vulkan replay would be a general change, not tried yet. What the 200 submits are is not known: a phone run with `DM_NVN_CMDSTAT=1` (command counts) and `DM_VK_QS_TRACE` would show it. Only Turnip was run; Qualcomm's system driver was not tested.
- The GLSLC module (`subsdk0`) is present. On Big Brain Academy it is imported but never called ([its note](../0100620012D6E000/README.md#files-and-shaders)); whether this game calls it is unconfirmed.
- Bezel's line renderer has 14 geometry shaders, the same kind of line-strip shaders that Big Brain Academy's note records as not built on Metal, where its draws are skipped ([Rendering](../0100620012D6E000/README.md#rendering)). This game may hit the same issue. On Android, check whether the chain builds them and whether the phone's Vulkan driver exposes a geometry stage: that is not checked yet.
