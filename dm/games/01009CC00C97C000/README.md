# DEAD OR ALIVE Xtreme 3 Scarlet (`01009CC00C97C000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

The free-to-play edition (基本無料版), update 1.0.6 (v393216) on the base game. SDK 6.4.2. Koei Tecmo's KTGL engine on NVN, as in [Fire Emblem: Three Houses](../010055D009F78000/README.md). Modules `main`, `subsdk0` (GLSLC, NVN's runtime shader compiler, the same build as Three Houses'), `subsdk1` (NintendoSDK_movie) and `sdk`.

**Status (current): plays on Android at 30 fps, as on the Switch.** On a Motorola signature (Adreno 829, 11.0 GiB of RAM as the launcher reports it, Android 17) it boots to the title menu and plays: every menu, character select, the islands and both shops, the minigames (volleyball, beach flags, climbing), the casino, the gravure studio and the girls' rooms, with the 55 add-ons (`01009CC00C97D001` to `01009CC00C97D037`, `01009CC00C97D1F5` to `01009CC00C97D1FF`) found by the game. Checked on Turnip Gen8 V39 (Mesa 27.0.0-devel, Vulkan 1.4.367) and on the phone's system driver (Qualcomm 512.842.43, Vulkan 1.4.295). No crash; the only `[RED]` lines are the 13 Android stubs of the Mac renderer's statistics (`gfx: mtl_… not yet on Android`). Not run on the Mac.

## Builds

| Version | Module | Build ID |
|---|---|---|
| 1.0.6 (v393216) | `main` | `71102550c57d57db950d16f3eb5af72e` |
| 1.0.6 (v393216) | `subsdk0` | `8d073b25b9abda677e6967cef9ef6070` |
| 1.0.6 (v393216) | `subsdk1` | `16577266bb9797abf6029f85025f6d35` |
| 1.0.6 (v393216) | `sdk` | `184751379007f9c50fc96ba4d26a5dca` |

The base game alone (v0) is not listed: only the update was surveyed and played.

## What it needs

The profile is the one `dm/games/profile_guess.py` writes from the survey ([`game.tsv`](game.tsv)), with the name, the header comments and one shader group added by hand:

| Setting | Where | Why |
|---|---|---|
| `mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | `game.tsv` | the survey's guess, which is Three Houses' layout |
| `sys_resource 0x0` | `game.tsv` | the NPDM declares no system resource |
| `modules main subsdk0 subsdk1 sdk` | `game.tsv` | every module `main` imports from |
| `/glslc_capture/=runtime` in `shader_groups` | `game.tsv` | `main` holds 7 GLSL sources that KTGL compiles at boot with the game's own GLSLC (text, 2D, the loading screen). Without them the menus showed empty bars and the first loading screen was black |

No rewriter patches: the game runs with the general rewrite and the SDK-wide rules.

## Files and shaders

- RomFS: 39 files, 4.64 GB. LINKDATA archives that carry their own index, `00/data_00.lnk` (4.1 GB, 7,099 entries) and `01`–`06/data_NN.lnk`, each with a `package_data.bin` beside it; MP4 movies in `00/movie`; XHS sound banks in `00/sound`. Each add-on is one more `.lnk` archive.
- A `.lnk` starts with `char[8] 'b0NN'`, `u64 count`, `u64 file size`, `u64 alignment` (0x800), then `count` 32-byte entries of Three Houses' `DATA0.bin` form `{u64 offset, u64 inflated length, u64 stored length, u64 compressed}` (`shader_split.py` `lnk_entries`). A compressed entry is a run of chunks of up to 16 KB inflated, each a `u32` and its bytes at 0x10 alignment: `0x8000 |` the length of one zlib stream, or the length of the plain bytes when the chunk does not compress (`kt_lnk`; `data_00.lnk` entry 3709 ends with a 784-byte plain chunk). `linkdata_entries` lists them, as it lists Three Houses' `DATA1.bin` through the `DATA0.bin` beside it. All 7,873 compressed entries of the update's 7 archives and the add-ons' inflate to their inflated length (5,163 and 2,710).
- `shader_split.py` found 551 shaders: 1 compute, 6 geometry, 403 pixel, 141 vertex. 274 are GLSLC outputs in `main`, 270 in 17 entries of `data_00.lnk`, and 7 are the run-time compiles, captured on the phone with `DM_GLSLC_CAPTURE` (all 7 are among Three Houses' 9). Before the `.lnk` reader it found 23, in the entries stored plain. The add-on archives hold no GLSLC output.
- The 6 geometry shaders fail the translation's `spirv-val` check (a `ViewportIndex` decoration without its capability); every module in the library validates. No draw of the tested routes asked for them.

## Rewrite census

From the survey, before any rewrite:

- `main`: 1,686,216 instructions, 56 undecodable, 3,908 `x18` uses.
- `sdk`: 1,339,052 instructions, 268 undecodable, 10,812 `x18` uses, 54 `mrs tpidrro_el0`, 60 `svc`, 32 `mrs cntpct_el0`.
- `subsdk0`: 1,259,705 instructions, 7 undecodable, 2,360 `x18` uses.
- `subsdk1`: 908,040 instructions, 316 undecodable, 10,362 `x18` uses.

## Memory

The game sets a 3.00 GB heap in the profile's 3.25 GB heap region (`[mem] heap 3.00 GB (heap region 3.25 GB)`), with no heap or address-region failure in the log.

## Frame rate

- **Current (Turnip and the system driver):** 30 fps, as on the Switch, with the GPU at 3–6 ms a frame. The game asks for present interval 2.
- With `DM_PRESENT_INTERVAL=display` (one frame a display tick) the game shows 60 fps and plays at twice the speed: its logic steps once a frame.
- KTGL keeps a ring of four frame-rate mode entries (main+0xd6dec0: the current index at +4, a pending mode at +8, each 16-byte entry's mode at +0xc; initialized to mode 1 by main+0x38f6e0, advanced each frame by main+0x38f770, read two entries back). A mode indexes main+0x7775dc, `{1,1,1}` `{2,1,2}` `{3,1,3}` (the present interval first; main+0x38f640, 34 callers), and the frame time main+0x777618, `{1/60, 1/30, 1/20}` (main+0x38f690, 12 callers). The game sets mode 1 once more at main+0x2f1694 (`bl main+0x38f750`, the setter's only call).
- **Tried, not kept:** a 60 fps variant with mode 0 everywhere (that call and the initializer's five stores; Turnip). The game then asked for present interval 1 and ran at 60 fps, still at twice the speed: the frame time the mode gives is not the step the game's own logic takes. 60 fps at the game's speed needs its per-frame speeds and counts changed, as Animal Crossing: New Horizons' speed fix does.

## Found on this game

- **Tip: pieces of a scene the first time it appears.** A character or place drawn in parts on its first appearance is a pipeline made in the background (8 of them took about 1 s on Turnip); later runs make them ahead from the device's prewarm list.

## Open

- 60 fps at the game's speed (Frame rate).
- A prewarm seed list in the profile, so that a first run has no pipelines left to make.
- The free-to-play edition imports `nn::ec` (the shop service, 32 functions); its eShop buttons were not tried.
