# My Time at Sandrock (`0100B63016916000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.4.2.0 (v851968) over the base game, with 27 small DLC packages, SDK 16.2.0. **Unity 2021.3.26f1** (IL2CPP) with Wwise, rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The closest profile is [Sea of Stars](../01008C0016544000/README.md) (Unity 2021.3.25f1): it has the very same `sdk` and `subsdk0` builds.

**Status: provisional, bounded Android/Turnip play tested.** The corrected memory layout completed a 15-minute session through the opening, outdoor movement and menus (median 29.9 fps, peak logged footprint about 2.4 GiB), without a recorded crash or pool refusal. A manual save loaded after an app restart. Later quests and longer sessions remain unverified; the profile is not in any list of supported games.

The companion of eden-duo-companions (package `MyTimeAtSandrock`, the one companion that uses texture readback) is made for this build: its load plan is under `guest/B74DFD27FC9DA1B4E19D06E3D40DD370…`, the `main` of v851968. The catalog 1.0.0 companion loaded on Android: Today, Inventory item details and the outdoor Map responded, and the host logged a successful 256×256 GPU texture copy for it. This is bounded readback coverage, not validation of every page or fog edge case.

## Builds

| Module | Build ID |
|---|---|
| `main` | `b74dfd27fc9da1b4e19d06e3d40dd370` |
| `subsdk0` | `ff0149d0b5e882b87c2f06a981302b13` |
| `sdk` | `3053b22935e32ddf15ef81997603e5c2` |

The display version is the update's control data (`control.nacp` +0x3060): 1.4.2.0. The base package says 1.0.2, its own version field is 131072 though its file is named v0, and its program has no `main` of its own (it holds 84 KB of RomFS): the update carries the game.

## Shaders

Every serialized file of the game comes without type trees, the player's files and the asset bundles alike (3,000 of the 34,325 bundles were looked at: none carries a tree), so the split has no tree to borrow and reads the Shader objects with the layout `unityfs.py` has written out for their type hash (`KNOWN_TREES`, `SHADER_2021_3`, hash `f0c184272a05e54447e36366ec4876a6`). Two things in this game stopped the split before, and both are in the tools now:

1. **The Shader layout of Unity 2021.3.26f1.** Without it: `[RED] Data/Resources/unity default resources: Unity serialized file without type trees, and no bundle has a Shader type tree of the same hash to borrow`. The layout is the 2021.2 one with a program's `m_PlayerSubPrograms` and `m_ParameterBlobIndices` and the Shader's `stageCounts`. It is node for node (2,304 nodes, with the alignment flags) the Shader type tree of AssetRipper's TypeTreeDumps `StructsDump/release/2021.3.26f1.dump`, and that tree's type hash, worked out from the dump, is the game's. On the game: all 287 Shader objects of `Data/` and of the `shader` bundle read to their exact ends, and per object the blob's entries are exactly the code entries the split takes (the player subprograms, 6,145) and the parameter entries (1,930): 8,075, none in both, none in neither, none empty. Other Unity releases are read by this layout only if their Shader has the same hash, which was looked up for 2021.3.26f1 alone.
2. **A compute kernel with no code.** `SkyboxCompute`'s kernel `CubemapFilteringSecondPassCS` in the `shader` bundle has 0 bytes of code. The reader raised on it and the whole bundle, with its 152 Shader objects, was left out (5,193 shaders read). The split now leaves such a kernel out and says so: `unity compute: 1 kernel variant(s) without code left out (first: …#SkyboxCompute#CubemapFilteringSecondPassCS.0)`.

`shader_split.py` finds 11,650 shaders (71 compute, 1 domain, 1 geometry, 1 hull, 5,790 pixel, 5,786 vertex), 5,789 variation pairs, no `[RED]` line. By first appearance: 7,154 in the `shader` bundle, 3,274 in `Data/globalgamemanagers.assets`, 829 in `Data/Resources/unity_builtin_extra`, 186 in `Data/resources.assets`, 157 in `Data/Resources/unity default resources`, 38 in five other bundles, 1 in `Data/sharedassets0.assets`, and 11 GLSLC outputs in `main` (the player's own).

Packed on Linux (`scripts/pack_android.py`), the `.nup`, the split and the pack in one run, the split and the pack reading the `.nup` in place: 11,650 of 11,650 translated, 15,324 SPIR-V modules, every one valid for `spirv-val --target-env vulkan1.3`, library 116.0 MB, pack 267.5 MiB, no `[RED]` line. The shaders use tessellation (2 modules), one geometry shader, subgroup operations (36) and `ShaderViewportIndexLayerEXT` (13).

## Memory and remaining assumptions

| Setting | Where | Why it is a guess |
|---|---|---|
| `mem_reserve 0x200000000`, `mem_alias 0x1d8000000`, `mem_stack 0x10000000` | [`game.tsv`](game.tsv) | 8 GiB reserved address space, 7.375 GiB alias, preserving the original 256 MiB stack and 384 MiB heap. The larger alias leaves room for both the SDK's pool-sized reservation and Unity's regions; this is virtual address space, not an 8 GiB physical allocation |
| `app_pool 0xcd500000` (about 3.21 GiB) | `game.tsv` | The standard application pool replaces the 1.5 GiB borrowed from Sea of Stars. The old pool refused a map at 1.43 GiB committed, then the game wrote an allocator header into inaccessible memory at `main+0x52b780`. A comparison on the same APK and repaired pack reproduced that fault with the old profile; the corrected profile reaches character creation. The pool limit stays enabled. The alias region grows with it, as [Sea of Stars](../01008C0016544000/README.md#memory) requires for this SDK |
| No `mem_used` | | the NSP script sets it after the rewrite only for a game without a system resource (`refine_after_rewrite`); for this one it leaves it to the heap check. Run by hand on the pack, that step writes nothing. The rewritten modules are 155 MB, more than the 64 MB the host reports as used by default, and `main` imports `nn::os::SetMemoryHeapSize`: if the game sizes its heap as pool − used memory, the request fails (`hle_svc.c`: `[mem] SetHeapSize … + modules … > app pool`), and the value is then `mem_used 0x9e98000` (`profile_guess.mem_used` on the pack). Sea of Stars runs without the key |
| `sys_resource 0x600000` | `game.tsv` | read from the NPDM (6 MB), not a guess |
| `pad_layout full` | `game.tsv` | a 3D game with a free camera. No file decides it |
| `romfs_marker Data/globalgamemanagers` | `game.tsv` | as the other Unity profiles; the rule would name the largest file, an asset bundle |
| No `depth_mode` | | depth correctness beyond the tested scenes is unverified; frame-rate choices are described below |

## Frame rate

[FPSLocker Warehouse](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/README.md) lists the exact `B74DFD27FC9DA1B4` build (1.4.2.0) as needing no game-code patch above 30 fps. `present_interval display` lets the host's limiter select the rate instead of following the game's two-vsync request. `fps_limit 60` makes 60 fps the Android default at the owner's request; lower caps remain selectable. The launcher-only `fps_limit_max 120` opt-in also exposes experimental 90 and 120 targets in Game settings and the in-game panel. These require the device's high-refresh mode; 0 retains its existing saved meaning of 60 fps. No engine-memory or executable patch is required.

A bounded Turnip test of the 60 target reached roughly 59–60 fps after loading/warm-up in the tested outdoor view, with lower samples during loading and warm-up. This does not establish a locked 60 fps throughout town or later gameplay. Matched timed movement/camera inputs at 30 and 60 ended at visually similar positions, with no obvious double-speed movement; this is not a full physics test. The initial 90/120 targets changed presentation alone and stayed near 60 even in inventory. A matched Turnip comparison on a confirmed 120 Hz panel changed only `DM_VSYNC_HZ` from 60 to 120: inventory rose from 60 to about 119 fps while the outdoor view stayed around 57 fps. The guest vi event was a software ceiling in the light scene; the outdoor view also exceeds a 120-fps frame budget. High-cap profiles now request the same rate for presentation and the vi event. The choices remain experimental; sustained 90/120 outdoor gameplay and full physics correctness are unverified. The launcher ignores stale own-rate observations for profiles with an explicit presentation limiter.

## Found on this game

- [Android optional high frame limits](../../../docs/reference/frame-rate.md#android-optional-high-frame-limits): profile opt-in and precedence over remembered own-rate detection.

## Files

- RomFS: 103,413 files, 5.59 GB, nearly all in `Data/StreamingAssets`: 34,325 UnityFS files (2.52 GB; 174 names under `AssetBundles/`), 66,259 Wwise streams (`.wem`, 2.72 GB) and 1,365 sound banks.
- The 27 DLC packages are about 100 KB each (unlock tokens); they are in the `.nup`, and the pack lists them.
- `main` imports `nn::ro` (it can load NRO modules), and the RomFS has no `.nro` file.

## Rewrite census

From the survey, before any rewrite:

- `main`: 22,248,515 instructions, 246 undecodable words, 25,557 `x18` uses, 2 `mrs tpidr_el0`.
- `sdk`: 1,547,352 instructions, 280 undecodable words, 13,060 `x18` uses, 121 `mrs tpidrro_el0`, 60 `svc`, 40 `mrs cntpct_el0`.
- `subsdk0`: 857,478 instructions, 314 undecodable words, 9,350 `x18` uses.

The rewriter took all three modules without an error.

## Open

- Later quests and longer memory behavior, depth correctness, sound quality (Wwise), DLC completeness, and companion pages beyond Today, Inventory and Map. Opening-town movement, basic menus, manual save/reload and one successful live-texture readback have bounded coverage.
- Minimum memory sizes have not been established. The tested layout fixes the observed opening pool exhaustion; no `mem_used` adjustment or allocator patch was needed for that scene.
- `bf_want.tsv`, `pso_pairs.tsv` and `pso_manifest.tsv` are empty. No `patches.py`.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on 1.4.2.0 through 1 `aspect_plan` row that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
