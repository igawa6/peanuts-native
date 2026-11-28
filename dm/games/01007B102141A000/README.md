<!-- PEANUTS: new profile, not on the release list -->
# The Legend of Heroes: Trails beyond the Horizon (`01007B102141A000`)

Nihon Falcom's engine (the one of Trails through Daybreak II), rendering with NVN. Update 1.0.6 (v131072) over the base 1.0.0 (v0), SDK 20.5.21. Modules `main`, `subsdk0` (NintendoSDK_movie, imported by `main`) and `sdk`. The NPDM declares 16 MiB of system resource.

## Status

**Plays on a test app, on Turnip, from the opening into the first battle. Not on the release list.** The shaders are in a PH3 archive (`asset_shader.p3a`) that the split reads only with the P3A reader (Depends on: #1000); without it the split finds the 18 shaders in `main` and nothing else.

One session ran on an AYN Odin 2 on Turnip 27.0.0-R2 ([Tested on](#tested-on)). The test app imported the base and the update into one `.nup` and prepared the pack on the phone: 10,320 of 10,320 shaders translated, 27,588 modules, every one valid for spirv-val (Vulkan 1.3). A fresh English New Game on Normal played the opening with no skips (the Zeku Space Base rocket launch, Marduk, the ruins) into the Elder Wyvern Command Battle. Attack with Van hit for 545 (4,873 to 4,328), the SCLM help appeared and the next party member became active.

- No frame had a large flat colour block in the first 120 s of the opening (3,593 frames of a screen recording; the worst was 0.08 % of the frame). The rocket-launch scene had no overlapping glyphs and no black lower image, and the log had no unregistered texture IDs.
- At the battle command menu, idle, about 32 s of SurfaceFlinger: 30.0 fps, median 33.3 ms, slowest 33.3 ms, no gap over 50 ms. The host digest of the whole session (26.2 min, mostly the opening's scenes): 14,388 frames on screen, 6 dropped, the slowest 305 ms at frame 3,535 (a scene load at 121.7 s). Memory 1.4 to 2.2 GiB, GPU memory 0.5 to 1.4 GiB.
- No crash, no GPU hang. One `[RED]` line besides the usual stubs: `NVN unimplemented function called: nvnDeviceGenerateDebugDomainId`, once at start.

The profile sets no frame rate keys, so the game paces itself; it ran at 30 fps with the app's cap at 60. Game speed was not measured (see [Open](#open)).

Fast Prepare, on a PC (dev `393a90a4` with the P3A reader, the `.nup` of base 1.0.0 and update 1.0.6 read in place): the full split took 32.8 s and the targeted split (`DM_SPLIT_TARGETED=1`, a separate output folder) 13.2 s. The targeted split read 1 of the 35 RomFS files, and its `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv` and `pso_pairs.tsv` match the full split's byte for byte. The full split's tables and its `shader_files.tsv` are this profile's, byte for byte.

## Tested on

| Phone | Chip, RAM | Android | Driver | App build | Played |
|---|---|---|---|---|---|
| AYN Odin 2 | Snapdragon 8 Gen 2 (Adreno 740), 16 GB (14.9 GiB reported) | 13 (SDK 33) | Turnip 27.0.0-R2 (Mesa 27.0.0-devel git-2648b2059f, driver ID 18) | `peanuts-native-0.1.8-dev`, commit `2f4a2121948e`: `main` 0.1.8 (`0766fc9c`) with the descriptor-pool fix (#785) and this profile with the P3A reader; pack made by the app | New Game on Normal, the opening, the first Command Battle (one Attack, one party turn change). 2026-11-26 |

The log reported `PN_BUILD_ID=11D7F970861DFA66722E6E5C4725F4F7...`, the 1.0.6 `main` below.

Earlier runs, on trees before the descriptor-pool fix (kept for the symptom):

- AYN Thor, Turnip v27.0.0-R1, this profile and the P3A reader only: New Game reached the Elder Wyvern Command Battle, but readable frames alternated with large red, yellow and white rectangles. `ui_basic_p.nvn` (`b1d413be3527ef40`) read unregistered texture IDs 310 and 311 at bindings 257 and 259. The descriptor-pool fix (#785) removed them ([The descriptor pools](../0100F6701FED4000/README.md#the-descriptor-pools), the same engine).
- AYN Odin 2, Turnip v27.0.0-R1, an earlier version of this profile with a 2.625 GiB alias region: the game stopped before presenting (`0x1803` from `AllocateAddressRegion`, see [Memory](#memory)).

## Builds

| Version | Module | Build ID |
|---|---|---|
| 1.0.6 (v131072) | `main` | `11d7f970861dfa66722e6e5c4725f4f7` |
| 1.0.6 (v131072) | `subsdk0` | `a870a783a09f57256d3630b73485398e` |
| 1.0.6 (v131072) | `sdk` | `c668ae02ea221e429677aa61d3dab7c6` |

The patch CNMT `01007B102141A800` names application `01007B102141A000`; the display version comes from its `control.nacp`. The base's `main` (`b008189564487bf4e18dfa587286f6c1`) has no build row, so a build needs the update.

## Shaders

The update's patch carries the whole updated ExeFS but none of the bytes of `asset_shader.p3a`: its BKTR mapping points back to a single extent of the base's RomFS, so the split reads the merged view (base plus update). `z_patch.p3a` holds model and voice overrides, not shaders.

`asset_shader.p3a` is a 456,902,689-byte [PH3 archive](https://github.com/ph3at/p3a-format), version 1200, of 37,619 LZ4 members named `asset/nx/shader/*.nvn`. The P3A reader decodes only the `.nvn` members, and reads the member index of the game's other P3A files without inflating their models, textures or voices.

The split over the merged RomFS (35 files: 16 `.p3a`, 17 movies, 2 fonts) and the 1.0.6 `main` finds 10,320 shaders: 8,796 pixel, 1,488 vertex and 36 compute; 10,302 come from the archive and 18 from `main`. `shader_files.tsv` names only `asset_shader.p3a`.

The 1,488 rows of `shader_pairs.tsv` are the splitter's single-stage input cover, not pairs seen in play. No prewarm seed is recorded.

To repeat, from the repository root with `OUT` a work folder, `KEYS` the keys and `BASE` / `PATCH` the two NSPs:

```sh
nximport_cli --keys "$KEYS" nup "$OUT/horizon.nup" 01007b102141a000 "$BASE" "$PATCH"
export DM_GAME=01007B102141A000
root="$OUT/horizon.nup:update/131072"
DM_SPLIT_FILES="$OUT/shader_files.tsv" python dm/gfx/tools/shader_split.py \
    "$root/romfs" "$root/exefs/main" "$OUT/pkg" "$OUT/shader_map.tsv" \
    "$OUT/shader_facts.tsv" "$OUT/shader_pairs.tsv" "$OUT/pso_pairs.tsv"
DMSH_VK_REQUIRE_ALL=1 python scripts/pack_android.py "$OUT/horizon.nup" --version 131072 \
    --out "$OUT/pack" --shader-pkg "$OUT/pkg" --shader-cache "$OUT/shader-cache"
```

## Memory

`nninitStartup` (`main+0x80ec40`) subtracts used from total memory, rounds down to 2 MiB, subtracts `0x53400000`, then calls `SetMemoryHeapSize`. With the host's 3,285 MiB pool and the default 64 MiB used floor this asks for at most 1,888 MiB. The profile leaves 1,920 MiB for the heap, following the packer's 128 MiB rounding. This comes from the executable, not a measured peak.

The heap is not the game's virtual arena. `main+0xa2db8` loads a `0x53400000` budget; `main+0xa2e98` through `+0xa2ec0` multiply it by ten, round up to 2 MiB, and request `0x340800000` bytes (13.01 GiB) with `nn::os::AllocateAddressRegion`. A 2.625 GiB alias region can never fit that request: the Odin 2 log reported `0x1803` and kept the requested byte count in `x26`.

The profile gives the alias region 16 GiB without changing the heap capacity, pool, stack or system resource. Replaying the update SDK's range search with its rewritten 62 MiB node area and the Odin 2's address base placed the arena at `[0x4014000000, 0x4354800000)`, inside the alias range `[0x4010000000, 0x4410000000)` and below the stack. This is a virtual reservation; it does not commit 13 GiB of physical memory.

## Open

- The Qualcomm system driver was not run on this profile. On the same phone and runtime, Trails through Daybreak II (the same engine) lost the device on it before its title screen ([its Known limits](../0100F6701FED4000/README.md#known-limits)).
- No pipeline keys files yet: fresh-cache runs per driver, with their logs, are still to be made.
- Frame rate: game speed was not measured, and no cap was timed beyond the 30 fps at the battle menu. The profile offers no cap of its own.
- Not played: the rest of the first battle, save and load, play after the opening, and 10 minutes past the first scene. No memory peak at a heavy spot.
- One phone: no run on the AYN Thor or another device on the current runtime.
