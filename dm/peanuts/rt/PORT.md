# peanuts_rt port notes (R1a .. R8)

The Eden Duo DSMod companion runtime (Eden Duo's `src/core/mods`) lifted into a standalone static
library. **R8 (2026-11-16): the lift is Eden Duo v1.4.0 (tag `v1.4.0`, `123d552976`,
`DualScreenRuntimeVersion = 25`).** The sections "The lift today", "Edited lifted files",
"Runtime 25 on this host" and "How to take the next Eden runtime" describe the present state.
Everything from "R5" down is the history of the earlier rounds (runtime 12, then 24), kept as
written; where a statement there no longer holds it is marked *superseded*. Layout:

```
dm/peanuts/rt/
  peanuts_rt.h / peanuts_rt.cpp   C API: pn_rt_create / destroy / tick / frame / touch / set_page / status
  CMakeLists.txt                  add_library(peanuts_rt STATIC), option PEANUTS_RT_TEST -> rt_test
  mods/                           lifted Eden files, ORIGINAL NAMES, byte-identical except where marked "PNRT"
  mods/modules/dsmod_module_sdk.h the module SDK header (as in Eden's src/core/mods/modules/)
  mods/pnrt_stubs.cpp             bodies for the members whose files are not lifted (list below)
  shim/                           stand-ins for the Eden headers the lifted files include (same include paths)
  shim/shim.cpp, shim/stb_impl.cpp, shim/shim_r5.cpp (R5: dlopen, SHA-256, data paths, fonts)
  test/rt_test.cpp                Linux host test: real package + fake guest memory -> PNGs
  test/check_pnrt_marks.py        checks "byte-identical except where marked PNRT" against an Eden Duo tree
  test/aux_sink_test.cpp          Linux host test of the UiSink hooks: a partial publish reaches the sink as its own box
dm/peanuts/third_party/
  nlohmann/json.hpp, json_fwd.hpp (v3.12.0 single header, from Eden's CPM cache)
  stb/stb_image.h, stb_image_resize.h, stb_image_write.h (Eden externals/stb; Eden uses resize v1, not resize2)
  ankerl/unordered_dense.h, stl.h  (SnapshotMap in mod_types.h)
  bc_decoder/bc_decoder.{h,cpp}    (DDS BC1/BC3/BC7, used by mod_assets.cpp DecodeDds)
  astc/video_core/textures/astc.{h,cpp}  (R1b: Eden's software ASTC decoder, byte-identical; BNTX ASTC textures)
  astc/boost/container/static_vector.hpp (R1b: minimal stand-in for the one Boost type astc.cpp uses)
```

The lifted files include each other as `core/mods/<name>`; CMake creates
`<build>/pnrt_include/core/mods -> dm/peanuts/rt/mods` (file(CREATE_LINK ... SYMBOLIC)) so no
include line changed. Upstream sync: see "How to take the next Eden runtime".

## The lift today (R8, 2026-11-16)

Counted by `test/check_pnrt_marks.py <v1.4.0 tree> --list` (97 files compared, 84 identical,
13 edited, 57 hunks, every hunk marked):

| What | Files | Identical to v1.4.0 | Edited (PNRT hunks only) |
|---|---|---|---|
| `mods/` (lifted from `src/core/mods`) | 90 | 78 | 12 |
| `shim/` and `third_party/astc` verbatim copies | 7 | 6 | 1 (`aux_routing.h`) |
| `mods/pnrt_stubs.cpp` | 1 | this port's own, no counterpart | |

The rule: **a lifted file is byte-identical to Eden Duo's file except inside hunks that carry a
comment containing `PNRT`**, and the comment says what the hunk changes and why. Nothing else may
differ: no reformatting, no renames, no comment edits.

### What is not lifted, and why

| Eden Duo file | Why it stays out |
|---|---|
| `mod_guest_bridge.cpp` | Guest calls through BRK traps. The rewritten game has no trap handling here, and Eden Duo itself turns the bridge off under NCE. Its members are stubs in `pnrt_stubs.cpp`; `ScanRange` and `ScanPattern` are copied from it (unchanged between v1.3.5.1 and v1.4.0) |
| `mod_re_tools.cpp`, `mod_console.cpp` | Developer tools, compiled out by `EDEN_DSMOD_BUILD_DEV_TOOLS=0` as in Eden Duo's release build |
| `duo_core.cmake`, `modules/README.md` | Build glue and notes of the emulator |

Outside `src/core/mods` and with no counterpart here: the emulator's second-screen modes
(one-screen "Simulated" mode with its layout engine and Vulkan companion quad, Mode "Off" and its
settings), the GPU quad compositor and the map fade shader, the `dsm:u` service, the loader's
load-plan step (replaced by `peanuts_helper.cpp`). Texture readback from the GPU's texture cache
is replaced by the replay's (`dm/gfx/vk_android/vka_readback.inc`, `shim/shim_readback.cpp`).

### What a companion loses on this host

- Call and Sequence actions, polled sequences and spies do nothing (the guest bridge).
- Manifest `patches` are applied by `peanuts_patches.cpp`, not as on the emulator at mapping: with
  the game's threads stopped, only where main holds the file's own instructions (refused over code
  the host's rewriter changed and over the package's own load-plan sites), tried again on later
  ticks while the threads cannot be stopped; an optional patch is skipped.
- Texture readback (runtime 24) is advertised and served by the Vulkan replay, with the emulator's
  formats and limits. Different: a request's address is the texture's storage address in the game's
  memory (there is no GPU address space); a copy comes back when the present returns to its frame
  slot (three presents later); colour targets are known from a companion's first request on; a
  rescaled image (`DM_RES_SCALE`) is refused; while the game does not present, requests fail after
  the timeout.
- `gpu_composite` is ignored: the CPU raster draws the page.
- `aoc:` is the lowest index of `DM_AOC_DIR` (a folder, a `.nup` or a dump; a RomFS image is not
  read, and then nothing is served). `sysfont:` serves the firmware fonts the app imported
  (`DM_SHARED_FONT`, `DM_SHARED_FONT_EXT` and the `cjk_*.ttf` beside the standard font), never the
  stand-ins of `DM_FONT_DIR`; `nintendo_ext2_003` is not there. `@device.*` is the battery the app
  pushes (`Bridge.battery`), else the kernel's power supply files, else "no battery". `@perf.fps`
  counts the replay's frames; `@perf.frametime_ms` is the GPU's busy time a frame (the emulator's is
  a frame's time without its waits) and is absent on a driver without GPU timestamps.
- Controller focus mode and `PadIsPushed` need pad state from the host; the pad gate needs the
  host to apply it.
- Runtime 25: `@screen.mode` is always "physical" and the pane size is the size the host gave at
  `pn_rt_create` (see "Runtime 25 on this host").

## Edited lifted files (R8 state; every hunk carries a `PNRT` marker)

One line per hunk. "Hunk" is a hunk of `diff -U0` against the v1.4.0 file.

| File | Hunks | What each changes and why |
|---|---|---|
| `mods/mod_assets.cpp` | 6 | (1, 2) `#if !PNRT` around `SerialVfsFile` and `OpenPrivateRomFS`: Eden Duo's loader / PatchManager / NCA chain does not exist here. (3, 4) `sysfont:` opens the folder the host hands over (`System::SysfontRoot`) instead of the system NAND archive. (5, 6) `OpenGameRomFS` takes the host's RomFS root (`System::RomfsRoot`) |
| `mods/mod_load_plan.h` | 1 | Two read accessors, `Writes()` and `GuestCodeData()`, so `peanuts_helper.cpp` can apply a plan to a running process |
| `mods/mod_manifest.cpp` | 1 | A manifest reload clears the map composite memo with the other map caches (`ReloadManifest`) |
| `mods/mod_module.cpp` | 1 | The build check accepts the host's 8-byte build id as a prefix of a manifest's full id and the reverse |
| `mods/mod_module_services.cpp` | 3 | (1, 2) `ResolveTitleSaveDirectory` returns the save folder the host hands over (`System::SaveRoot`); the save-data service path stays under `#else`. (3) installing a module's map areas clears the map composite memo with the other geometry caches |
| `mods/mod_romfs_sources.cpp` | 2 | (1, 2) `base:` is the host's RomFS without the mods' folders, `aoc:` is `PnShim::AocRoot` (the lowest index of `DM_AOC_DIR`, `shim/shim_services.cpp`); Eden Duo's bodies stay under `#else` |
| `mods/mod_module_host.cpp` | 3 | (1) five headers for the log hunks. (2) integers a module publishes whose name ends in `_failures`, `_hidden`, `_supported`, `_request` or `_version` go to the log when they change (`DM_RT_VALUES=1`: all of them): nothing else shows them on this host. (3) the same for texts ending in `.diag`. `LockMapStateUnlessRaster` is Eden Duo's again (see "The tick rate and the map lock") |
| `mods/mod_redraw.cpp` | 2 | (1, 2) a publish that `LockMapStateUnlessRaster` refused for a busy map is counted for the snapshot log line, at `PublishUi`'s two lock sites. The fade step and the publish gate are Eden Duo's |
| `mods/mod_runtime.cpp` | 4 | (1, 2, 3) the manifest's `gpu_composite` flag does not turn the composite path on (this host has no compositor; the page would show no map) and the log says which path runs. (4) the snapshot log line gains the module's gates and the map counters (`ui_frames`, `map_base_rasters`, `base_ms`, `map_composites`, `incremental`, `composite_ms`, `publish_skipped`, `fade_steps`, `pulse_memo_hits`) |
| `mods/mod_runtime.h` | 4 | (1) `friend struct PnRtAccess` for the C API. (2, 3) `MaskUnvisited` takes an optional pixel rectangle. (4) the per-area memo of the last map composite (`MapCompositeMemo`) and the map counters |
| `mods/map_overview.h` | 3 | (1) `<array>`. (2, 3) `CompositeMapOverview` takes an optional pixel rectangle and limits its pass to it |
| `mods/mod_map.cpp` | 19 | The incremental map composite of 2026-11-14 (Metroid Dread's map on the CPU path: a whole rebuild per fade step took seconds on a handheld). (1) `<chrono>`. (2, 3, 4) `MaskUnvisited` works on the given rectangle only. (5) the pulse image is taken from the area's newest composite instead of being stamped again: the memo is looked up by the area's name and its `size_key` must equal the size asked for (until R8 the lookup used the name with the size, which is never a key, so it never hit). (6, 7) the base raster is timed and counted. (8, 9) the composite is timed; the copy of the pre-fog image moves into the non-incremental branch. (10) the incremental build: a reveal-only change copies the last composite and recomputes the pixel box of the cells that changed or are still fading. (11) `MaskUnvisited` is called with that box. (12, 13) breakable tiles and (14, 15) the magnet overlay are clipped to the box. (16, 17) the pulse image's key is kept for the memo. (18) the overview composite is limited to the box. (19) the finished composite is stored in the memo, counted and timed. `MapRasterStamp`'s fade term is Eden Duo's (a step every fourth tick) |
| `shim/video_core/dsmod/aux_routing.h` | 8 | Eden Duo's header with the `UiSink` hooks: (1) head comment, (2) the `UiSink` struct, (3) `SetUiSink`, `UiSerial` and the member, (4, 5, 7, 8) a forward to the host's callbacks at the end of `PublishUi`, `PublishUiWith`, `PublishUiPartialRects` and `ClearUi`, (6) `PublishUiPartialRects` sets the accumulated dirty box aside for the length of the call, so the sink is handed the box of that publish (nothing consumes the accumulated box on this host, which made every partial publish cover the whole page) and `TakeUi` still gets the union |

The 2026-11-14 map work was entered without marks on 29 of its hunks; R8 marked them. Two comment
lines that work had reworded in `mod_map.cpp` ("FOG (cheap, per reveal/fade state) ..." and the
trailing comment of the `MaskUnvisited` call) were put back to Eden Duo's words, since the fade is
still drawn there.

## The tick rate and the map lock (R8, 2026-11-16)

The host ticks the runtime at 60 Hz (`PN_RT_TICK_HZ` in `peanuts_rt.h`), the rate the runtime
counts with. The corrections made while the host ticked at about 30 Hz are gone and the code is
Eden Duo's again: `map_style.fade_ticks` is taken as written, the page publishes on every second
tick (30 Hz), and a map's fade steps on every fourth tick (15 Hz) in `UiSignature`, the widget
hash and `MapRasterStamp`. A fade step is still an incremental composite here.

`LockMapStateUnlessRaster` is Eden Duo's too. From 2026-11-14 to R8 it took the lock for real
after 60 refusals in a row, so that a map raster could not starve the page's publish; that wait
could hold the tick thread for the length of a raster, and could deadlock when reached from a
module's own `tick` (`publish_text("__map_state")`, then `ApplyPendingMapUpdates`) while the
raster read that module's `module:` data. It is removed, not replaced, for this reason: a map
raster off the tick thread runs only inside a redraw job, and the one place that sends a job is
`PublishUi` after it got the map state. So every raster follows a publish that got through, a
refused publish is retried on the next publish tick, and the worst case is one frame per raster.
`rt_test`'s map probe shows it, also with rasters made long on purpose (`RT_TEST_RASTER_PX=8192`
on one slow core: base raster 530 ms, composites 144 ms, 109 publishes refused, every one of the
20 composites followed by a frame, no tick over 1 ms). The starvation of 2026-11-14 came with
whole-map rebuilds on every fade step and a second rebuild for the pulse image; both are gone
(the incremental composite, the pulse memo). On a device the snapshot log line tells: `ui_frames`
must keep rising while `publish_skipped` rises. Not run on a device.

The map composite memo is cleared where the other map caches are: a manifest reload and the
install of a module's map areas. A canvas switch clears no image cache in Eden Duo either; the
memo holds one composite per area and its size check leaves one of another size unused until the
next composite of that area replaces it.

## Runtime 25 on this host (R8)

What v1.4.0 added (runtime 24 to 25), and its state here. "Lifted" means the lifted files carry it
and nothing of this host is involved.

| Change | State |
|---|---|
| `@screen.pane_w`, `@screen.pane_h`, `@screen.orientation`, `@screen.mode` | Lifted. The values are `AuxRouting::width` / `height`, which `pn_rt_create` sets once from `PnRtConfig::panel_w` / `panel_h`. The host passes a fixed 1240x1080 today, so a package reads that size and "landscape" whatever the second screen is. `@screen.mode` is always "physical" (`inline_enabled` is never set): true, this host has no one-screen mode |
| `canvas_alt`, `rect_alt`, `wrap_width_alt` | Lifted and exercised on the PC (`rt_test --module ... --panel 1080x1620` draws My Time at Sandrock on its 1080x1620 canvas). On the device the alternate canvas is never chosen while the host reports 1240x1080 |
| Canvas-switch cache reset, map view refit, pan re-anchor | Lifted; they run only on a canvas switch |
| Button tokens `L3` / `R3` (`StickL` / `StickR`) | Lifted. The pad sink already carries them: the virtual button's id is the host's button bit (StickL 4, StickR 5) |
| Action `view_zoom_step` | Lifted |
| `@view_touch_x`, `@view_touch_y`, `@view_touch_down` | Lifted, fed by the touches of `pn_rt_touch` |
| Map overlays keep the last picture while a new one decodes | Lifted |
| `src_hold: false` | Lifted |
| A module's font decode try-locks the module loader | Lifted. The retry is 2 ticks later, 33 ms at the host's 60 Hz tick, as in Eden Duo: the count is not changed |
| Launch gate (`mod_launch_gate.{h,cpp}`, the emulator's Mode "Off") | Lifted because `mod_load_plan.cpp` includes it. Nothing on this host sets it, it defaults to "not disabled", nothing waits on it, and `peanuts_helper.cpp` reads plans through `ReadModLoadPlan`, which does not ask it. A package is never told about a mode. "Companion off" here is not calling `pn_rt_create` |
| Touch rect follows the one-screen mode's Fit | Lifted; `panel_is_inline` is never set, so the touch rect is computed as in runtime 24 |

Host work that would make the first two rows true on any device (none of it is in the lifted
files): give `pn_rt_create` the second screen's real size in pixels, add a call that stores a new
size in `AuxRouting::width` / `height` when the companion window changes, and send touches in the
same pixels (the app scales a touch to page pixels today, which equals panel pixels only while the
panel and the canvas are the same size).

New shim: `shim/core/frontend/framebuffer_layout.h` (`Layout::DefaultFrameLayout` returning the
whole rectangle). `aux_routing.h` includes it for `InlineGamePictureRect`, a function of the
one-screen mode that no lifted file calls.

Checked on a PC (2026-11-16, nothing run on a device):

- `rt_test <Link's Awakening package> <out>` without a RomFS: the same status lines as before the
  lift, and 6 of the 7 PNGs byte-identical to the runtime-24 build's (the seventh,
  `gear_after_close.png`, is caught in the middle of a page transition and differs from run to run
  on either build).
- The version gate (new in `rt_test`): a probe package with `min_runtime` 25 is accepted, one with
  26 gets the update-required page. The same test against the runtime-24 library fails, as it should.
- The map probe (new in `rt_test`): a generated package with one geometry map area and a marker
  bound to fake memory. While the marker walks (180 ticks) the page publishes 71 frames with 71
  composites, 69 of them incremental; the still fade that follows steps 15 times in its 55 ticks
  (every fourth tick), all incremental, and the pulse lookup is answered from the memo (86 and 46
  times). No tick takes longer than 1 ms.
- `aux_sink_test`: after a whole-page publish, three small partial publishes reach the sink as
  their own rectangles, an unchanged one is not forwarded, and `TakeUi` still returns the page.
- `rt_test --module` on Echoes of Wisdom 1.0.2 (`min_runtime` 22): module loaded, the same PNG as
  on the runtime-24 build. On Resident Evil 4 1.0.0 and a development build of My Time at Sandrock
  (both `min_runtime` 25, refused with the update page before the lift): accepted, module loaded,
  manifests parse (`src_hold`, `rect_alt`, `wrap_width_alt`, `view_zoom_step`). With zeroed game
  memory and no RomFS, Resident Evil 4 stays on its standby page and Sandrock draws its pages in the
  built-in font; Sandrock's log says texture readback is unavailable and the module keeps its
  fallback.
- All 46 C++ files of the runtime in the Android build, and `mod_launch_gate.cpp`, pass the NDK
  compiler's syntax check with the build's own flags. No APK was built.

## How to take the next Eden runtime

Three trees are needed: BASE = the Eden Duo release this lift was taken from (today `v1.4.0`),
NEW = the release to take, and this one. Use exported or checked-out trees of the tags, not a
working tree that other work moves.

1. Before anything: `python3 test/check_pnrt_marks.py <BASE tree>` must say OK, and build and run
   `rt_test` (below) to keep its PNGs and status lines for comparison.
2. For every file under `mods/` with a counterpart in BASE's `src/core/mods`, and for the verbatim
   copies listed in `check_pnrt_marks.py` (`VERBATIM`):
   - identical to BASE's file: replace it with NEW's file, byte for byte;
   - edited: three-way merge, `diff3 -m <ours> <BASE file> <NEW file>`, and write the result over
     ours. Resolve conflicts by hand. Where a merge is clean but a PNRT hunk sits within a few
     lines of an upstream change, read the merged function: `PublishUi` in `mod_redraw.cpp` is the
     usual place.
3. Files new in NEW's `src/core/mods`: lift the ones the lifted files include or call and add the
   `.cpp` files to `PNRT_LIFTED` in `CMakeLists.txt`. A file that stays out goes on `NOT_LIFTED`
   in the checker with its reason in the table above.
4. New includes of emulator headers: add a stand-in under `shim/` with the same path.
5. If `mod_guest_bridge.cpp` changed, copy `ScanRange` and `ScanPattern` into `pnrt_stubs.cpp`
   again, and give every new `ModRuntime` member that lives in an unlifted file a stub there.
6. `python3 test/check_pnrt_marks.py <NEW tree>` must say OK: every remaining difference is a
   marked hunk. `--show` prints the hunks that fail, `--list` the table of edited files. A hunk
   passes when `PNRT` is in one of its lines or in the line just above or below it. A hunk whose
   reason is unknown is marked "reason not recorded", never given an invented one.
7. The number: `DualScreenRuntimeVersion` comes with `mod_runtime.h`. Set
   `dev.peanuts.Installer.RUNTIME_VERSION` and `ExpectedRuntime` in `test/rt_test.cpp` to it;
   `peanuts/scripts/check-runtime-version.sh` compares the first two.
8. For every change of the new runtime, decide: works from the lifted files alone, needs a value
   or a call from the host, or cannot exist here. A package must not be told of a mode this host
   does not have. Write the table as in "Runtime 25 on this host".
9. Build and test: `cmake -S dm/peanuts/rt -B <build> -DPEANUTS_RT_TEST=ON`, then `aux_sink_test`,
   `rt_test <package> <out>` (compare the PNGs with step 1; a package that uses nothing new must
   draw the same; its version gate and map probe must pass), `rt_test --module <package> <out> <title> <build id> [--panel <w>x<h>] [page...]`
   on a package of the previous and of the new `min_runtime`, and the Android compile.
10. Update this file: the header, the counts, the hunk table, the per-change table.

## R5: the v1.3.5.1 lift (runtime 24) and native modules

*History. Superseded by R8 where it names the lifted release, the number of files and the edited
files: the lift is v1.4.0 now, and "exactly four edited files" was not true after 2026-11-14.*

Every file in Eden's `src/core/mods/` at v1.3.5.1 is in `mods/` **except** the ones that stay
out by design: `mod_guest_bridge.cpp` (BRK guest-call bridge), ~~`engine_il2cpp/mercury/ichigo.cpp`~~
(lifted in R7, see "Engines" below), `mod_re_tools.cpp` + `mod_console.cpp` (dev tools, `EDEN_DSMOD_BUILD_DEV_TOOLS=0`),
`duo_core.cmake`, `modules/README.md`. 86 files in `mods/` (+ `modules/dsmod_module_sdk.h`);
`cmp` against the exported v1.3.5.1 tree shows exactly four edited files, all hunks
`PNRT`-marked (table below): `mod_runtime.h`, `mod_assets.cpp`, `mod_romfs_sources.cpp`,
`mod_module_services.cpp`. Upstream sync stays: copy the Eden file over `mods/<name>`, re-apply
the PNRT hunks.

Delta against R2 (runtime 12):
- **replaced** (v1.3.5.1 versions of the 44 files already lifted; 15 of them were byte-identical
  across the two trees): dsmod_module_abi.h, dsmod_module_extensions.h, mod_actions.cpp,
  mod_assets.cpp, mod_input.cpp, mod_load_plan.{cpp,h}, mod_manifest.cpp, mod_map.cpp,
  mod_module.h, mod_nx_runtime.cpp, mod_pages.cpp, mod_redraw.cpp, mod_runtime.{cpp,h},
  mod_runtime_shared.h, mod_state.cpp, mod_types*.h, mod_ui*.{cpp,h}.
- **added** (new in runtime 13..24): mod_expr.{cpp,h}, mod_settings.{cpp,h}, mod_nav.{cpp,h},
  mod_types_nav.h, mod_host_values.{cpp,h}, mod_clock.{cpp,h}, mod_persist.{cpp,h},
  mod_sources.{cpp,h}, mod_romfs_sources.{cpp,h}, mod_user_source.{cpp,h},
  mod_sysfont_source.{cpp,h}, mod_font_pages.{cpp,h}, mod_font_epoch.h, mod_texture_readback.{cpp,h},
  mod_view_default.h, mod_input_tokens.h, mod_input_drag.h, mod_input_hold.h, mod_input_swipe.h,
  mod_map_view_rect.h, mod_bfres.h, mod_chart.h, mod_hooks.h, mod_package_io.h,
  mod_process_guard.h, mod_yaz0.h, modules/dsmod_module_sdk.h, and the **native module host**:
  mod_module.cpp, mod_module_host.cpp, mod_module_services.cpp.
- **removed**: nothing from `mods/` (the module stubs in pnrt_stubs.cpp went away, see Stubs).
- C API: unchanged signatures; `PnRtConfig` gained `data_dir`, `save_dir`, `sysfont_dir` (NULL
  defaults); `pn_rt_status` now also prints `module=<name|none|error: ...>`.

### Native modules (R5)

`InitializeGameModule()` (mod_module_host.cpp, called from `ModRuntime::Initialize`) ->
`GameModule::Load(manifest.asset_dir, title_id, running_build_id, error)` (mod_module.cpp):
reads `dualscreen/manifest.json` (`"module"`: abi 1, `build_ids`, `libraries[<platform>]`
{path, sha256}), picks the platform directory by compile target --
`Platform()` = `linux-x86_64` (`__linux__ && __x86_64__`), `android-arm64-v8a`
(`__ANDROID__ && __aarch64__`) -- requires `path == modules/<platform>/<TITLEID>.so`, checks
SHA-256 (`SHA256()` from shim/openssl/sha.h, own implementation in shim_r5.cpp), copies the bytes
to a private 0700 staging dir (`StageModule`: `<data_dir>/dualscreen-code/module-XXXXXX/`, set
by `pn_rt_create` through `SetModuleCacheDirectory`; mod_module.cpp refuses to run on Android
without one), `Common::DynamicLibrary::Open` = `dlopen(RTLD_NOW|RTLD_LOCAL)` (shim_r5.cpp),
`GetSymbol("eden_dsmod_get_module")` -> `EdenDsmodModuleApi` checked for abi version/hash/title/
`supports_build`, then the optional extension getters (`eden_dsmod_get_extensions`, `_font_`,
`_save_`, `_write_`, `_data_`, `_texture_extensions`, `NegotiateExtension`). Back in
InitializeGameModule the `EdenDsmodHostApi` table is filled: `read_memory` / `get_read_pointer` /
`write_memory` / `is_mapped` go to `OwnerMemory()` = the shim `KProcess::GetMemory()` =
`Core::Memory::Memory` over **PnRtMemory** (read/write/pointer_at); `read_romfs` resolves
through `AssetSources` (romfs: = `romfs_dir`, file:, user:, module:, sysfont:); publish_* land in
the tick snapshot; `get_i64/f64/text` answer host values, flags, views. `create(host,
manifest-json)` then `tick()` each pn_rt_tick (`RunGameModule`), `load_image` on the module
asset worker (`StartModuleAssetWorker`), `load_data` for `module:` byte sources, the write
extension's `write_batch` through `RunWithGuestStopped` (the shim just runs it), the texture
extension refused (`GPU::DSModTextureReadbackAvailable()` = false -> not advertised).
`ShutdownGameModule` joins the workers, `destroy()`s the instance, `GameModule::~GameModule`
dlcloses and deletes the staging dir.

## Lifted (compiled) files (R1a..R2 history; superseded by the R5 list above)

mod_actions.cpp (R2), mod_assets.cpp, mod_input.cpp (R2), mod_load_plan.cpp, mod_manifest.cpp, mod_map.cpp, mod_msbt.cpp, mod_pages.cpp,
mod_nx_assets.cpp, mod_nx_runtime.cpp (R1b), mod_redraw.cpp, mod_runtime.cpp, mod_state.cpp, mod_ui.cpp, mod_ui_canvas.cpp, mod_ui_expand.cpp,
mod_ui_image.cpp, mod_ui_map_widget.cpp, mod_ui_scroll.cpp, mod_ui_text.cpp,
mod_ui_transitions.cpp, mod_ui_widget_state.cpp; headers mod_types*.h, mod_ui*.h, mod_msbt.h,
map_overview.h, map_cache_identity.h, mod_runtime.h, mod_runtime_shared.h, mod_load_plan.h,
engine_formats.h, mod_module.h, dsmod_module_abi.h, dsmod_module_extensions.h, mod_nx_assets.h. Files named in the inventory that do not exist on the
dsmod branch: mod_expr.cpp, mod_settings.cpp, mod_nav.cpp, mod_host_values.{h,cpp},
mod_view_default.h, mod_input_tokens.h (their functionality lives inside the files above).

## Edited lifted files, R5 state (superseded: see "Edited lifted files (R8 state ...)" above)

*History. This table lists 5 of the 12 files that are edited today, and "all hunks carry a PNRT
marker" stopped being true on 2026-11-14 (made true again in R8).*

| file | why |
|---|---|
| mods/mod_runtime.h | `friend struct PnRtAccess;` so peanuts_rt.cpp can switch `current_page` and read `tick_count`/page id/module state for pn_rt_set_page / pn_rt_status (Tick itself is reached without a friend: the CoreTiming shim keeps the scheduled event and pn_rt_tick fires it). |
| mods/mod_assets.cpp | `#if !PNRT` around `SerialVfsFile` + `OpenPrivateRomFS` (Eden loader/PatchManager/NCA chain); `OpenGameRomFS()` body replaced by `root = system.RomfsRoot()`; the `sysfont:` source's archive opener replaced by `system.SysfontRoot()` (no system NAND). |
| mods/mod_romfs_sources.cpp (R5) | `BuildBase` = `system.RomfsBaseRoot()` ("base:" is the game's RomFS without its mods' folders; "romfs:" has them layered over it when the host names any, `PnRtConfig::romfs_overlay_dirs`), `BuildAoc` = nullptr ("aoc:" never available; the Eden bodies stay under `#else`). |
| mods/mod_module_services.cpp (R5) | `ResolveTitleSaveDirectory` = `system.SaveRoot()` (PnRtConfig::save_dir) instead of the save-data HLE + profile manager. |
| mods/mod_module.cpp | `GameModule::Load`'s build-id check accepts the host's 16-hex-digit (8-byte, zero-padded) build id as a prefix of a manifest's full 20-byte id and vice versa (one PNRT hunk at the `build_ids` any_of). |

~~Everything else in `mods/` is byte-identical to Eden v1.3.5.1.~~ (superseded)

## Shims (dm/peanuts/rt/shim)

| Eden header | shim provides |
|---|---|
| common/common_types.h | u8..u64, s8..s64, f32/f64, u128, VAddr |
| common/logging.h | LOG_* macros -> `Common::Log::FmtLogMessage` over `std::vformat`; one sink function pointer (`SetSink`, default stderr + `__android_log_print` on Android); `Level`/`Class` enums; enum formatter |
| fmt/format.h, fmt/ranges.h | `fmt::format` = `std::format` (only `fmt::format` is used: 40 sites, literal format strings) |
| common/settings.h | `Settings::values.disabled_addons` (empty map), `IsNceEnabled()` = true (so the runtime disables the BRK guest bridge exactly as under Eden NCE) |
| common/hex_util.h | HexToString / HexStringToVector |
| common/cityhash.h | `CityHash64` as FNV-1a + finaliser (content hash only; not CityHash-compatible, nothing persisted depends on it) |
| common/thread.h | SetCurrentThreadName (pthread), SetCurrentThreadPriority (nice) |
| common/dynamic_library.h | placeholder class (GameModule member) |
| common/stb.h | as Eden's; implementation unit shim/stb_impl.cpp |
| core/core.h | **`Core::System` = the PnHost facade**: owns `Memory::Memory`, `Timing::CoreTiming`, `Tegra::GPU` (AuxRouting), `InputSubsystem` (virtual pad), `FileSystemController` (`GetModificationLoadRoot` -> synthetic dir of package dirs), `AppLoader` (icon bytes), `HIDCore` (no controller), optional `KProcess`, `RomfsRoot()`, title id, `GetDualScreenGuestMailbox()` = {0,0} |
| core/memory.h | `Core::Memory::Memory`: Read8/16/32/64, Write*, ReadBlock, WriteBlock, ReadCString, GetPointerSilent, IsValidVirtualAddressRange over a `Backend` of 3 function pointers (read/write/pointer_at + user) ; YUZU_PAGEMASK |
| core/core_timing.h | `CreateEvent` keeps the callback; `ScheduleLoopingEvent` registers it; `FireAll()` = one tick |
| core/file_sys/vfs/vfs.h, vfs_types.h | `VfsFile`/`VfsDirectory` (GetName, GetSize, Read, ReadAllBytes, ReadBytes, GetFile, GetSubdirectory, GetFiles, GetSubdirectories, GetFileRelative) over std::filesystem; `OpenHostDirectory`, `OpenHostFile`, `MakeSyntheticDir` |
| core/file_sys/{content_archive,patch_manager,registered_cache,romfs}.h, core/hle/service/filesystem/*.h | empty |
| core/hle/kernel/k_process.h, svc_types.h | `KProcess`/`KPageTable`/`KThread` (one process; its page table is three atomic fields, 0 until `pn_rt_tick` has the game's heap from `PnRtMemory::heap_range`, see "The game's heap" below), `GetInteger`, `Svc::ThreadContext`. k_process.h ends with `#include "core/core.h"` because mod_state.cpp / mod_runtime.cpp reach `Core::System` through it in Eden |
| core/loader/loader.h | `AppLoader::ReadIcon` (host-supplied JPEG bytes, empty = none) |
| video_core/dsmod/aux_routing.h, video_core/gpu.h | **the UI sink**: `AuxRouting` keeps the latest frame (PublishUi / PublishUiWith / PublishUiPartial / ClearUi / TakeUi) and forwards to an optional `UiSink` of host callbacks; touch mailbox (SetTouch/GetTouch); `PublishAuxTexture`/`PublishMapFadeTextures(Tiles)`/`PublishComposite`/`ClearComposite` accepted and dropped (GPU composite path off); `TileMask`, `DiffTiles`, `UiDiffEnabled()` = false; `Quad`; haptic sink |
| input_common/drivers/virtual_gamepad.h | `VirtualGamepad` (same VirtualButton enum) forwarding to a `PadSink` of function pointers; `InputSubsystem::GetVirtualGamepad()` |
| hid_core/frontend/emulated_controller.h | `HIDCore::GetEmulatedController` -> nullptr (PadIsPushed() = false) |
| core/hle/service/ns/language.h, core/hle/service/set/settings_types.h | (R1b) `ApplicationLanguage`, `LanguageCode`, `available_language_codes`, `ConvertToApplicationLanguage` as Eden; priority lists reduced to {self, AmericanEnglish, Japanese} |
| core/file_sys/control_metadata.h, patch_manager.h | (R1b) `NACP::GetSupportedLanguages()`; `PatchManager::GetMetadataFromBaseOrUpdate` -> no metadata (0 = all languages) |
| common/settings.h (`language_index`) | (R1b) `Settings::Language` + `CanonicalizeEnum`; set from `PnRtConfig::language` (default American English) |
| common/alignment.h, video_core/textures/workers.h | (R1b) `DivideUp`; `ThreadWorker` that runs astc row jobs inline (the Nx asset worker is already a thread) |
| **R5 shims** | |
| video_core/dsmod/aux_routing.h | (R8: v1.4.0's header, same hunks) now **Eden's v1.3.5.1 header verbatim** (wheel/pinch mailboxes, CompanionRect/FitCompanion/PanelToCanvas, SecondScreenOptions, companion_context_active, the GPU composite slots) plus PNRT-marked hunks only: a `UiSink` struct + `SetUiSink`, and a forward to it at the end of `PublishUi` / `PublishUiWith` / `PublishUiPartialRects` / `ClearUi` (34 added lines, `diff` shows them) |
| video_core/dsmod/texture_readback.h, common/dsmod_dev_tools.h, common/device_power_state.h, hid_core/resources/npad/dsmod_pad_gate.h | verbatim Eden copies (pure headers) |
| video_core/surface.h | the `PixelFormat` names texture_readback.h compares |
| video_core/gpu.h | + `DSModTextureReadbackAvailable()` / `DSModRequestTextureReadback` = `PnShim::ReadbackAvailable` / `ReadbackSubmit` (`shim/shim_readback.cpp`: the Vulkan replay's readback, `dm/gfx/vk_android/vka_readback.h`) |
| core/core.h | + `Kernel()` (KernelCore), `GetPerfStats()` (zeros), `IsPoweredOn()`/`IsMulticore()` = true, `RunWithGuestThreadsSuspended(fn)` = run fn, `GetDualScreenGuestMailboxEpoch()` = 0, `SaveRoot()`, `SysfontRoot()`; **`process` is now always non-null** (a `KProcess` whose `GetMemory()` is the PnRtMemory accessor) because the runtime's every guest access goes through `OwnerMemory()` = `OwnerProcess()->GetMemory()` |
| core/hle/kernel/kernel.h, k_auto_object.h, k_thread.h, k_process.h | `KernelCore::RetainApplicationProcess(&epoch)` -> the one process (epoch 1), `IsApplicationProcess` = same pointer; `KScopedAutoObject<T>` = a plain pointer holder (GetPointerUnsafe/SetObject/IsNull); `KThread::GetContext()` non-const (RefuseChangedProcess assigns it); `KProcess::GetMemory()` |
| core/perf_stats.h | `PerfStats::GetTotals()` = `PnShim::ReadPerfTotals` (the replay's frame count and the GPU's time a frame; all zero without a host) |
| core/arm/debug.h | `InvalidateInstructionCacheRange` no-op, `ArmInterface` fwd |
| core/core_timing.h | + `GetGlobalTimeNs/Us()` = steady clock since construction (@game.seconds) |
| common/fs/path_util.h, fs_util.h | `GetEdenPath(EdenDir|CacheDir)` over one host data dir (`SetEdenDir`, from `PnRtConfig::data_dir`, default `<tmp>/peanuts_rt`); `PathToUTF8String` |
| common/dynamic_library.h | real now: Open/Close/GetSymbol over dlopen (shim_r5.cpp) |
| openssl/sha.h | `SHA256()` + `SHA256_DIGEST_LENGTH` over an own FIPS 180-4 implementation (shim_r5.cpp): mod_module.cpp includes `<openssl/sha.h>` unchanged |
| common/atomic_ops.h, swap.h, scope_exit.h, uuid.h (empty) | `AtomicCompareAndSwap` (__atomic builtins), `swap32`, `SCOPE_EXIT` |
| core/hle/service/ns/platform_service_manager.h | `SHARED_FONTS` table + `DecryptSharedFontToTTF` (reproduced: XOR key from word 0, byte-swap, drop 2 header words) |
| hid_core/hid_types.h, hid_util.h, hid_core.h, frontend/emulated_controller.h | `NpadIdType`, `NpadButton` bits (mod_nav chords), `AnalogStickState`, `NpadIdTypeToIndex`; `EmulatedController::IsConnected()` = false and `GetEmulatedController` = nullptr (controller focus mode compiles, never engages) |
| input_common/main.h | includes the virtual gamepad shim |
| core/file_sys/vfs/vfs.h | + `Resize/IsWritable/IsReadable/Write/Rename` virtuals (lifted VfsFile subclasses override them), `VfsDirectory::GetFullPath()` |
| core/file_sys/vfs/vfs_vector.h, vfs_real.h | `VectorVfsFile` (mod_nx_runtime.cpp), `RealVfsFilesystem::OpenDirectory` (the "user:" folder) |
| core/file_sys/romfs.h | `ExtractRomFS` -> nullptr (only reached from code under `#if !PNRT`) |
| core/file_sys/{common_funcs,nca_metadata,romfs_factory,fs_save_data_types}.h, core/hle/service/acc/profile_manager.h, filesystem/save_data_controller.h | empty |

## Stubs (mods/pnrt_stubs.cpp): members of files not lifted

R5: the **module stubs are gone** (mod_module*.cpp are lifted; `RunModuleAction`,
`InitializeGameModule`, `LockMapStateUnlessRaster`, ... are Eden's). What remains stubbed is
only the guest bridge (R7: the engine stubs are gone too, see "Engines"):

- **mod_guest_bridge.cpp** (also: RunAction's Call/Sequence kinds, mod_actions.cpp): `RequestCall`,
  `StartSequence` (log "unsupported on this host", no-op; RunAction still returns `Done`),
  `ArmSpies`, `DrainGuestBridgeResults`, `RunPolledSequences` (no-ops); `ApplyPatches` =
  `PeanutsPatches::Apply` (`peanuts_patches.cpp`).
  **`ScanRange` + `ScanPattern` are Eden's bodies verbatim** (lines 63-228 of v1.3.5.1's
  mod_guest_bridge.cpp, copied into pnrt_stubs.cpp): they only read guest memory
  (OwnerMemory, HeapLow/HeapHigh, InGameplay) and engine_il2cpp.cpp's `FindClassSlotByName`
  links against `ScanRange`; ResolvePoint's "pattern" points now scan for real instead of
  getting nullopt. Upstream sync: re-copy the two functions when that file changes.
- ~~engine_il2cpp/mercury/ichigo.cpp~~ lifted as of R7 (byte-identical).
- ~~mod_module*.cpp~~ lifted as of R5 (see above).
- **mod_nx_runtime.cpp / mod_nx_assets.cpp**: no stubs since R1b (both lifted as-is).
- Dev tools (`mod_re_tools.cpp`, `mod_console.cpp`): compiled out by `EDEN_DSMOD_BUILD_DEV_TOOLS=0` (the shells in mod_runtime.cpp are already empty then).

## Verified (host, 2026-11-11)

`rt_test <eden-duo-companions>/packages/LinksAwakening <out>`:
- Discover loads the real package (4 pages, 662 points, data file 909E904AF78AC1B8.json).
- `standby.png` 1240x1080: the manifest background only -- all five widgets are
  `romfs:...arc#...bntx#...` images, no romfs locally, each logged "asset ... not found".
- `gear.png` 1240x1080: "GEAR" title, panel, hint text, "CLOSE", and the live rupee value
  **123** read from the fake memory (main+0x1CC0760) in the built-in 3x5 font (the .bffnt font
  fails to load and the runtime falls back by itself).
- `probe.png` 640x360 from a generated 1-page package: "RUPEES 123", "HEALTH 48", a bar at
  48/80 -- proves sample -> point -> bind -> text/bar on this build.
- arm64 Android (NDK 28.2.13676358, android-33, `-fexperimental-library` for std::jthread as in
  Eden's own Android build): libpeanuts_rt.a builds with all pn_rt_* symbols. Not run on device.

## Verified R1b (host, 2026-11-11, romfs = <your extracted Link's Awakening RomFS>)

`rt_test <LA package> <out> <romfs>`: `standby.png` shows the ItemBG texture, the Link's
Awakening logo and the bomb/rupee/heart icons (SARC -> BNTX, ASTC/BC decoded on the Nx worker);
`gear.png` shows the jpn_main.bffnt font ("Gear", "Close", the hint line from UI.msbt), the 12
item discs and X/Y slots (composites `cell_disc`/`slot_disc`/`tab_cap`), rupee icon + live 123.
Without a romfs dir the behaviour is as in R1a (logged "not found", blank). arm64 lib rebuilt.

## Verified R2 (host, 2026-11-11): interaction

mod_input.cpp and mod_actions.cpp are now lifted byte-identical (no PNRT hunks needed -- every
symbol they call was already in mods/ or the shims). `pnrt_stubs.cpp` keeps only the guest-bridge
(mod_guest_bridge.cpp), module (mod_module*.cpp) and engine (engine_*.cpp) stubs; `RequestCall`,
`StartSequence` and `RunModuleAction` (RunAction's Call/Sequence/Module kinds) are now real stub
bodies that log "unsupported on this host" and otherwise no-op (RunAction still reports `Done`,
fire-and-forget, as Eden does for these kinds). `ParseButton` and the Page-only `RunAction` that
used to live in pnrt_stubs.cpp are gone -- mod_actions.cpp's real versions took over.

`pn_rt_touch` now tracks every live finger_id (`PnRt::live_touches`) across calls and feeds
`AuxRouting::SetTouch` the full current set each time, attribute bit0 set only on the tick a
finger first appears (matches Eden's own hosts: yuzu_cmd/emu_window/emu_window_sdl3_aux.cpp,
android/.../native_input.cpp -- neither ever sets bit1 "end"; a released finger is just left out
of the next call). `PnRtHaptics` (new in peanuts_rt.h) is wired to `AuxRouting::SetHapticSink` in
pn_rt_create: Eden's `HapticEvent{strength,kind,flags}` is turned into an amplitude/duration pair
by a small table in peanuts_rt.cpp (`kHapticAmplitudeMs`) -- this table is PNRT's own, not lifted
from Eden (Eden hands the raw triple to the Android frontend, which picks amplitude/duration in
Kotlin; no portable C++ table exists to copy).

`rt_test <LA package> <out> <romfs>` (out-romfs2/): tapping the gear page's "Close" button (rect
[30,962,1180,118], canvas pixels, `on_tap`→`page_map_from_gear`) switches `pn_rt_status`'s page
from `gear` to `map` (gear_before_close.png / gear_after_close.png) and raises one haptic pulse.
Tapping item disc `cell_8` (payload 8, select_group "gear") then the X slot `drop_X`
(drop_action `equip_x`, accept_group "gear") runs the tap-then-tap path in DrainTaps into
`RunAction`'s `ActionKind::Write` for `equip_x` (point `equip_x` = main+0x1CC0783, u8): the fake
guest memory at that address goes from the "empty" sentinel 0xFF to 0x08 through
`PnRtMemory.write`, exactly as the manifest's action describes it (gear_before_equip.png /
gear_after_equip.png). ASan build (Linux, `-fsanitize=address -fno-omit-frame-pointer`): clean run
of the whole test binary (which exercises every touch path), no reports. arm64 Android rebuilds
clean (same toolchain as R1/R1b) with all three new/changed symbols present.

Not reproduced: the pan/pinch-zoom gesture path (`UpdateGestures`'s two-finger branch) is lifted
but untested -- the LA package's map page uses `pan_zoom` on its map widget, so it should work,
but rt_test does not drive a second finger (optional per the R2 task). The haptic amplitude/
duration table is PNRT's own invention, not a lift of Eden's Android-side mapping (see above).

## Verified R5 (host, 2026-11-12; work dir <work dir>/rt5-work)

- Host build (gcc, RelWithDebInfo) clean. `rt_test <LA package> out-la <romfs>`: standby.png /
  gear.png / probe.png and the four out-romfs2/ interaction PNGs render as in R1b/R2 (same
  status lines: `page=standby tick=94 frames=2`, `page=gear tick=188 frames=4`, Close tap ->
  `page=map`, equip_x 0xFF -> 0x08, one haptic pulse); visually identical (ItemBG, logo, icons,
  bffnt font, 12 discs, rupee 123).
- `rt_test --module fe3h/pkg out-fe3h 010055D009F78000 89048449BA238C8CF565518B83BF02D3
  battle units academy` (the 1.0.2 catalog zip: manifest min_runtime 18, requires_module true,
  modules/{linux-x86_64,android-arm64-v8a}/010055D009F78000.so): the package is accepted (no
  update-required page), log `DSMod module: loaded Fire Emblem: Three Houses DSMod for title
  010055D009F78000 build 89048449...000 (linux-x86_64)`, status
  `module=Fire Emblem: Three Houses DSMod`; default.png = the module's standby page ("STUDYING THE
  TACTICS." / "WAITING FOR THE GAME" in the built-in font, since the module font
  `file:fe3h_font.txt` needs game memory), battle/units/academy.png = the tab bars (degraded:
  built-in font, all-zero guest memory).
- ASan (`-fsanitize=address -fno-omit-frame-pointer`, detect_leaks=1): both runs above clean,
  exit 0, no reports.
- arm64 (NDK 28.2.13676358, android-33): libpeanuts_rt.a builds; llvm-nm shows `pn_rt_*`,
  `Core::Mods::GameModule::Load`, `ModRuntime::InitializeGameModule`, `SetModuleCacheDirectory`,
  `Common::DynamicLibrary::Open`, `SHA256` as defined `T` symbols. Not run on device.

## R6 (2026-11-12): the game's RomFS from a .nup container (provider callbacks)

C API, additive: `PnRtRomfs {user, open, read, close, list}` and `PnRtConfig::romfs` (last field).
Used only when `romfs_dir` is NULL (or not a directory) and `open` + `read` are set; `list` is
optional (NULL = no enumeration: only known paths open, `GetSubdirectory` is nullptr). Paths are
RomFS-relative, '/'-separated, no leading slash; callbacks must be thread-safe (asset workers +
tick thread). Shim: `FileSys::OpenProviderDirectory(const PnRtRomfs&)` (vfs.h / shim.cpp):
`ProviderDir` / `ProviderFile` -- a file holds its handle for the VfsFile's life and reads ranges
through `read` (no whole-image loads); `pn_rt_create` sets `System::romfs_root` to it, so
"romfs:" and "base:" both resolve through it ("romfs:" under the mods' folders when there are any). No lifted file changed.

Host side (dm/peanuts/peanuts_nup.{h,c}, C, over dm/host/nup_image.h): `pn_nup_open(spec)` opens
the package DM_ROMFS names (`<game>.nup:update/<v>/romfs`) as layer 0 and, when the selector is
not `base` (and not empty), `<game>.nup:base` as layer 1. A lookup (`open`, `list`) tries the
update first, then the base; `list` merges the two directories, the base's children only where the
update does not name them. An update package already holds a full RomFS (android/NUP.md), so the
base is a safety net; a base without a RomFS (Pokopia) is simply absent ("+ base (none: ...)").
Raw entries: pread at the extent; zstd entries: `nup_z_get` on open, held until `close`
(`nup_z_put`), memcpy from the decompressed buffer. peanuts.c: a DM_ROMFS that `pn_nup_is_spec`
accepts goes through the provider; a folder keeps the base-folder rule as before. peanuts_nup.c
must be added to the Android host's HOST_C list (android/host/CMakeLists.txt, beside peanuts.c).

Tests (work dir <work dir>/nup-romfs-work): `rt_test <LA pkg> out
<romfs>` and `... --provider` (a POSIX open/pread/readdir provider over the same folder, rt_test.cpp
`DirProvider`) give byte-identical PNGs for all 7 images (standby, gear, probe, the 4 interaction
PNGs); the provider run reports `17 opens, 404 reads, 40 lists`. `nup_test <spec> [path]`
(test/nup_test.cpp + peanuts_nup.c + zstddeclib.c, Linux only: peanuts_nup.c is compiled with
`__ANDROID__`/`__aarch64__`/`_ASM_X86_SIGCONTEXT_H` predefined because plat.h refuses other
targets; `plat_sha256` comes from the shim's SHA256): Pokopia.nup update/655360 lists 4 root
entries and reads LD_C0_R.BIN (247,317,504 bytes, zstd, via the temp-file map) and LI_C0_R.IDX
whole; a nup_pack.py fixture with base + update/1 proves the layering (common.txt = the update's
bytes, only_base.txt and sub/a.txt from the base, zstd sub/b.txt 300,000 bytes, root listed once
per name). ASan (detect_leaks=1) clean on all three. arm64 libpeanuts_rt.a builds with
`OpenProviderDirectory` and all `pn_rt_*`; peanuts_nup.c compiles for aarch64-linux-android33
(needs plat_sha256 and the ZSTD_* symbols the host already links).

## Load plans, the game's heap, mods' folders (2026-11-14)

Lifted files touched: mod_romfs_sources.cpp (the `BuildBase` row above) and mod_module_host.cpp
(the log hunks of the last point).

- **Load plans** (peanuts_helper.cpp, `PnRtConfig::helper`): a package's mailbox, patches of main and
  guest helper are applied to the running game in `pn_rt_create`, before the runtime loads the
  package's module (`PeanutsHelper::Install`; the file's head comment has the steps and the x18
  stubs). The host gives the block (`code_block`: the unused end of main's patch area or the
  loader's 1 MiB after the last module, so a plan's code + mailbox larger than that finds no room),
  says which runs of main its rewriter changed (`code_rewritten`) and where main's code ends
  (`text_end`). One install per process. A refused plan is one log line ("the package's load plan
  is not installed ...") and the package runs without helper and mailbox.
- **The game's heap** (`PnRtMemory::heap_range`): `pn_rt_tick` writes the base and the opened
  length to the page table shim once the game has a heap, and `ModRuntime::HeapLow` / `HeapHigh`
  (lifted, unchanged) cache base and base + opened + 1 GiB at the first ask. When the game opens
  more later, `pn_rt_tick` raises the cached end (`PnRtAccess::RaiseHeapHigh`); it never moves
  back, and a closed heap keeps its last bounds (reads of unmapped pages fail in the host's
  accessor, they do not fault).
- **Mods' folders** (`PnRtConfig::romfs_overlay_dirs`, the host's DM_ROMFS_OVERLAY): "romfs:" is a
  `LayeredDir` (shim.cpp) of those folders over the game's RomFS, the first wins; a folder that is
  missing is skipped and names are matched exactly, as the game's own mount does (hle_fs.c).
  "base:" stays the game's RomFS alone.
- **Module values in the log** (mod_module_host.cpp, PEANUTS hunks): integers whose name ends in
  `_failures`, `_hidden`, `_supported`, `_request` or `_version` and texts whose name ends in `.diag`
  are logged when they change; `DM_RT_VALUES=1` logs every integer a module publishes (3000 lines
  at most).

## R7 (2026-11-12): engines -- the engine-specific decoders are lifted

Why: on the device the Dread companion logged `mod_map.cpp:1663 GetImage:: DSMod:
'romfs:/textures/gui/textures/loadingscreentileset.bctex' is a Mercury texture this build cannot
decode` (same for `.../fonts/textures/occ_atlas.bctex`), so its standby page had no loading rings
and no font atlas: `DecodeBctex` was a `return false` stub.

How Eden wires them (v1.3.5.1): `GetImage` (mod_map.cpp:1640-1670) picks the decoder by the asset's
bytes/extension -- SARC/BNTX/DDS/PNG through `DecodeBntx`/`DecodeDds`/stb (mod_assets.cpp), then
`DecodeSosXtx` (engine_ichigo.cpp) and `DecodeBctex` (engine_mercury.cpp); `LoadFont`
(mod_assets.cpp:389-410) tries the module's font extension first, then `ParseMfnt`
(engine_mercury.cpp) and `ParseSosFont` (engine_ichigo.cpp); `ReadAssetBytes` (mod_assets.cpp:171)
uses `DecodeLzss` + `ExtractArchiveMember` (engine_ichigo.cpp) for `<archive.lzs>#<member>`;
`ResolvePoint` (mod_state.cpp:255-358) uses `FindClassSlotByName` / `DetectMethodsOffset`
(engine_il2cpp.cpp) for IL2CPP points and `FindPlayerNode` (engine_mercury.cpp) for "player"
points. None of them touches the guest bridge: the texture/font/archive ones are static byte
decoders, the lookups read guest memory through `OwnerMemory()` only.

**In (byte-identical, `mods/`, listed in CMake's PNRT_LIFTED):**

| file | engine / title | what |
|---|---|---|
| engine_mercury.cpp | Mercury (Metroid Dread) | `DecodeBctex` ("MTXT" + gzip around an NVN XTX container; RGBA8/BGRA8/BC1/BC3, block-linear via `BlockLinearOffset` from engine_formats.h), `ParseMfnt` (font metrics, in-core fallback), `FindPlayerNode` (heap sweep + stick-correlation scoring) |
| engine_ichigo.cpp | Ichigo (Story of Seasons: FoMT) | `DecodeLzss`, `ExtractArchiveMember`, `DecodeSosXtx` (raw XTX BC1/BC3/BC7/RGBA8/BGRA8), `ParseSosFont` |
| engine_il2cpp.cpp | Unity IL2CPP (Hollow Knight) | `FindClassSlotByName`, `DetectMethodsOffset`, `ResolveMethodByName`, `MethodTableLooksReal` |

Dependencies: `bc_decoder.h` (already in third_party), `engine_formats.h` (already lifted),
**zlib** (`InflateGzip` in engine_mercury.cpp: inflateInit2/inflate/inflateEnd). No LZ4, no zstd
(the LZSS in engine_ichigo.cpp is hand-written). CMake: `find_package(ZLIB)` -> `ZLIB::ZLIB`, with
a plain `z` fallback; the NDK's sysroot libz is found by FindZLIB (1.3.0.1) and the Android host
already links `z` (android/host/CMakeLists.txt).

**Shim added: `shim/zlib.h`** -- `#include_next <zlib.h>` then `#undef far` (and `near`). The
NDK's zconf.h defines `far` as an empty macro (16-bit memory-model leftover, zconf.h:415) and
engine_mercury.cpp includes <zlib.h> before mod_runtime.h, whose mod_input_swipe.h has a local
named `far`: clang then fails with "expected unqualified-id". Eden never sees it (its bundled
zlib); the shim keeps the lifted file byte-identical. The host build (glibc zlib) is unaffected.

**Still stubbed and why:** only mod_guest_bridge.cpp's bridge entry points (`RequestCall`,
`StartSequence`, `ArmSpies`, `ApplyPatches`, `DrainGuestBridgeResults`, `RunPolledSequences`): they
need BRK breakpoints in the guest (`InstallBreakpoint` / `OnGuestBreakpoint`, `core/arm/debug.h`),
which this host has no equivalent for. `ScanRange`/`ScanPattern` from that file are verbatim in
pnrt_stubs.cpp (see Stubs).

**Catalog survey** (`grep` over <eden-duo-companions>/packages/*/dualscreen/manifest.json
for source schemes and engine-specific keys): every package uses only `romfs:` / `file:` /
`module:` / `base:` / `sysfont:` / `msbt:` / `widget:` / `composite:` sources. Engine decoders
needed: **MetroidDread** -> Mercury (38 `.bctex` sources, one `"player"` point = `FindPlayerNode`;
the module's font extension supplies the metrics, `ParseMfnt` is the fallback). **LinksAwakening**,
**MarioKart8Deluxe** -> SARC/BNTX/bffnt only (already in since R1b). No catalog package is a Story
of Seasons (Ichigo) or Hollow Knight (IL2CPP) companion, so those two are lifted for completeness
and have no package-level test here. All other packages are module-driven (`file:` PNGs or
module-generated images) and need no engine decoder.

**rt_test change:** module mode takes `--romfs <dir>` (anywhere after the build id; the other
arguments stay page ids), so a module package can be run against a game RomFS.

### Verified R7 (host, 2026-11-12; work dir <work dir>/rt-engines)

- Dread RomFS: no dump existed on the PC any more (the Aug-27 `<an extracted RomFS>` was gone), so
  the base NSZ (`<your dump folder>/Metroid Dread
  [010093801237C000][v0].nsz`) was decompressed with `~/nsz-venv/bin/nsz -D` and its RomFS dumped
  with `eden-cli --dump-romfs` (harness bin-store `24-75539f37ac70`) to `<work>/dread/romfs-dread`
  (4.2 GB, textures/ = 1.2 GB; the NSP was deleted afterwards). Package: the published
  `metroid-dread-1.0.0` release zip (`gh release download`, its linux-x86_64 module sha256
  `304ab82f...` matches the manifest; the catalog dir holds no modules/).
- `rt_test --module dread/pkg out-dread 010093801237C000 646761F643AFEBB3 --romfs dread/romfs-dread
  main` (ASan, detect_leaks=1): module loads, log `GetImage: DSMod: decoded
  'romfs:/textures/system/fonts/textures/occ_atlas.bctex' (2048x1024) from the game's own
  filesystem` and `... 'loadingscreentileset.bctex' (1024x256) ...`, `LoadFont: font
  'romfs:/system/fonts/occ_32.bfont' has 308 glyph(s) ... (via module)`, the module's map data
  generated from the romfs (9 areas, 823 strings). `main.png` 1240x1080 = the standby page with the
  Chozo loading ring, "Synchronizing environment..." in the game's occ font and the three glyphs,
  i.e. exactly what the device was missing. Exit 0, no ASan/LSan reports. Without `--romfs` the
  same run logs `asset '...loadingscreentileset.bctex' not found` (the pre-R7 "cannot decode"
  warning is gone because the decoder now runs; the file is simply absent) and no page frame.
- LA regression: `rt_test <LA package> out-la <romfs>` (ASan) unchanged -- same status lines as R5
  (standby tick 96 / gear tick 190 / probe, Close tap -> `page=map`, equip_x 0xFF -> 0x08, one
  haptic pulse), `rt_test: OK`, gear.png/standby.png visually as in R1b/R2.
- `cmp` against the exported v1.3.5.1 tree: engine_mercury.cpp, engine_ichigo.cpp,
  engine_il2cpp.cpp, engine_formats.h byte-identical; the edited lifted files are exactly the five
  in the table above (mod_runtime.h, mod_assets.cpp, mod_romfs_sources.cpp,
  mod_module_services.cpp, mod_module.cpp), all hunks PNRT-marked.
- arm64 (NDK 28.2.13676358, android-33): libpeanuts_rt.a builds (after the zlib.h shim); llvm-nm
  shows `DecodeBctex`, `ParseMfnt`, `FindPlayerNode`, `DecodeSosXtx`, `DecodeLzss`, `ParseSosFont`,
  `ExtractArchiveMember`, `FindClassSlotByName`, `DetectMethodsOffset`, `ScanRange`, `ScanPattern`
  as defined `T` symbols, `inflate`/`inflateInit2_`/`inflateEnd` as the only new undefined refs
  (the NDK libz). Not run on device.
- Gaps: `FindPlayerNode` and the IL2CPP lookups compile and link but are exercised only with the
  all-zero fake memory (nothing to find; `ScanPattern` logs "pattern (17 bytes) not found", which
  is the honest answer); no Ichigo (`.lzs` / SoS XTX) sample exists on this PC, so engine_ichigo.cpp
  is compile-verified only.

## Known gaps (R5)

*Still current after R8 except where "What a companion loses on this host" above says otherwise.*

- Controller focus mode (mod_nav.cpp) and `PadIsPushed` compile but never engage: the HID shim
  has no connected controller. The pad gate (`DSModPadGate`) is set/reset by the runtime but
  nothing on this host applies it to a real pad.
- (since served: `@perf.*`, `@device.*`, "aoc:", "sysfont:", texture readback, manifest patches: see "What a companion loses on this host") `@perf.*` was never published (PerfStats totals stayed 0); `@device.*` read "no battery";
  `@game.seconds` is the wall clock since pn_rt_create.
- "aoc:" source always unavailable; "base:" = the same romfs dir as "romfs:"; "sysfont:" only
  when the host passes `sysfont_dir` (a folder of the console's .bfttf files); a module's
  `read_save_file` only when `save_dir` is passed (the game's save folder, plain files).
- Texture readback (runtime 24) not advertised: modules keep their fallback.
- Guest bridge (Call/Sequence actions, spies, patches, `OnGuestBreakpoint`) still stubbed
  (NCE conditions, as before). The guest mailbox is {0,0} unless the package's load plan was
  installed (peanuts_helper.cpp, "Load plans" below).
- The rt_test module run only proves load + default/tab pages with zero memory; module pages
  that read real game state are untested here.

## Known gaps / R2c candidates (history)

- (closed) heap-region sweeps (ArrayFind/PlayerFind points) used the fixed fallback window; the
  host now hands the game's heap (`PnRtMemory::heap_range`, "The game's heap" below).
- The page is published at ~30 Hz (every other tick) from the runtime's own redraw worker
  thread; the host must keep calling pn_rt_tick for frames to appear.
- `Settings::IsNceEnabled()` = true permanently: no BRK guest bridge, by design -- Call/Sequence/
  Module actions are logged "unsupported on this host" and otherwise no-op.
- No pan/pinch-zoom or drag-and-drop coverage in rt_test (optional for R2; the code path is
  lifted and should work, matching Eden's UpdateGestures).

## Companion search for code-mod builds (issue #33)

A custom build folder stores the normal pack at `<title>/pack` and a code-mod variant at `<title>/mod-<hash>/pack`, while the installed
companion remains at `<title>/peanuts`. The host previously searched only beside the running pack and beside the dump's work folder; neither
is the installed location for that variant. The search now includes the normal pack's companion root for the recognized 12-hex-digit variant
layout. Title matching, active-before-parked-version selection and refusal of unfinished `.new` installs still apply. App-owned variant packs
already share their normal companion root. `test_companion_search.py` compiles the production search and exercises all of these layouts.
Device check (2026-11-17): AYN Thor, Brilliant Diamond / Luminescent Platinum build `94CEAE325C205C4B9D6F7235552F28FD`,
with a copied save in Twinleaf Town. The log loads the module from `mod-<hash>/pack/../../peanuts`; the second display leaves standby and
shows the Party and Map pages. System and Mesa Turnip 27.0.0-devel (V39) runs completed without a native crash; steady frame windows were
about 60 fps. The Turnip run covered 60 seconds.
