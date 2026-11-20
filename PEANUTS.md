# Peanuts Native

A second-screen companion layer for Banana Native, the sibling of Eden Duo for Switch 2 titles. Upstream
Banana Native ships several releases a day, so this fork is kept as a **thin overlay** that rebases cleanly.

## Rules that keep upstream updates cheap

1. **All new code lives in Peanuts-only places.** Upstream never touches them, so they never conflict:
   - `android/app/src/main/java/dev/peanuts/` — launcher side: the `Presentation` on the second display, its view, the JNI bridge.
   - `dm/peanuts/` — host side: companion package loader, guest readers, tick, input injection (plain C).
   - `peanuts/` — companion packages, docs, tests, `scripts/`.
2. **Hooks into upstream files are one-liners, each tagged `PEANUTS`.** A hook is a call-out to Peanuts code, never logic.
   Allowed hook sites (keep this list current):
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — create/destroy the companion `Presentation`; (2026-11-13) one line in
     `onCreate` that touches the last-3-played list for dynamic shortcuts (`dev.peanuts.launch.RecentGames`, `peanuts/docs/LAUNCHERS.md`); and
     (2026-11-13) in `start()`'s env assembly, two lines adding `DM_ROMFS_OVERLAY`/`DM_CHEATS` for the enabled Mods (`dev.peanuts.mods.ModRuntime`,
     `design/platform/DESIGN-MODS.md`); (2026-11-18) one line in `settingsEnv` after `ResScale.env`: `dev.peanuts.Companion.env(this, id, env)` adds `PN_COMPANION=off` when the title's Second screen is Off, which is the default on a device without a second display (`Companion.hasSecondDisplay`); internal shader-preparation mode (`dev.peanuts.build.ShaderCapture`) covers the game surface, isolates its save/environment, suppresses play-session hooks and input, and reports errors to Build.
     (2026-11-17) `hostSetup` and the active existing `fpsTick` forward the current display's presentation deadline through a marked helper. The same tick applies `dev.peanuts.display.RunningDisplayMode` before reading the deadline; pause and destruction release its window-local request. Only the running effective 30 fps cap with requested FG off selects a same-resolution 60 Hz mode; pending restart preferences are ignored.
   - `android/app/src/main/cpp/nxlaunch.c` — JNI entry points forwarded to `dm/peanuts`; (2026-11-17) `Native.java`/`nxlaunch.c` weakly forward the active display's queue lead to `nxhost_presentation_deadline_set`, exported in `vk_android.h`. Nonpositive or unavailable deadlines become zero; older hosts ignore the hint.
   - `android/app/src/main/java/dev/nxnative/android/Lsfg.java` — (2026-11-17) `push` releases the Activity's display request before its native FG enable call; actual display switching is asynchronous.
   - `dm/gfx/vk_android/vk_android.h`, `vka_framegen.c`, `Native.java`, `nxlaunch.c` — (2026-11-17) expose initialized requested FG state independently of rendering warm-up. An unavailable interface leaves display selection unrestricted; external native changes use the existing Activity tick.
   - `android/host/CMakeLists.txt`, `android/app/src/main/cpp/CMakeLists.txt` — add the `dm/peanuts` sources.
   - `dm/gfx/vk_android/vka_present.c` — one call per presented frame (`peanuts_tick()`), beside `ovl_draw`; (2026-11-17) one weak declaration and one argument in `ovl_draw`'s `vko_place(...)`: `peanuts_ovl_corner(s, g_ocorner[s])` (the stats block's corner chosen in the launcher; the build tag steps aside from a top-left block).
   - `dm/gfx/vk_android/vka_dev.c`, `vka_readiness.c` — (2026-11-17) call the Peanuts guest-buffer remap probe before trusting advertised placed-memory support; use the existing tested exported-alias path on failure. `peanuts/UPSTREAM-EDITS.md`, issue #47 follow-up.
   - `android/app/build.gradle` — applicationId / label (`-PnxAppId=<suffix>`: a development build beside the app, with its own data); (2026-11-18) `androidBuildsOf` and its use in `buildsOf`: a profile's `android_builds <version> ...` row names the builds this app builds and plays when not all of the profile's (the launcher reads the others as a version it does not have; the host and the packer still know every build row).
   - `android/app/src/main/AndroidManifest.xml` — new Peanuts-owned activities/services/aliases, each its own marked block (already used for
     `CatalogActivity` and `GameHold`; 2026-11-13 added the `dev.peanuts.launch.LaunchActivity` alias, `peanuts/docs/LAUNCHERS.md`; 2026-11-18 the
     `dev.peanuts.build.BuildLogProvider` provider, `${applicationId}.buildlog`, read-only: Share build log's bundles).
   - `android/app/src/main/java/dev/nxnative/android/AppStorage.java` — (2026-11-13) `pack()` prefers an existing pack already in the app's own storage,
     else a build folder's `<title ID>/pack` when the user set one (`PeanutsPrefs.buildPackDir`; Settings › Library › Build folder); (2026-11-13,
     code mods) the class and `pack()` made `public` so `dev.peanuts.build.ModdedBuild`/`Prepare` can ask for a modded variant's own pack folder by
     its variant id, same call as every other pack lookup. `Folders.find()` also considers existing packs in every configured Build folder.
   - `android/app/src/ondevice/java/dev/nxnative/android/Job.java` — (2026-11-13, code mods) `modArgs()`/`modArgs(String)` (a modded variant job's
     extra rewrite-step argv) and `reuseShaders(File)` (skip split/shaders, point the shader cache at the normal pack's own library) — block-marked,
     additive only; a normal job never sets either. `design/platform/DESIGN-MODS.md`.
   - `android/app/src/ondevice/java/dev/nxnative/android/PackService.java` — (2026-11-13, code mods) the "rewrite" step appends `Job.modArgs()`
     (split on `"\n"`) to `pack_android.py`'s argv when it is not empty — block-marked, a no-op for a normal job.
     (2026-11-17) shader-map validation and selection call `RuntimeShaderMap.missingRuntime`: a deficit containing only boot-generated shaders is prepared for Build’s internal capture, while missing file-based keys still stop.
   - `android/app/src/main/java/dev/nxnative/android/ExitWatch.java` — game exit notices and on-device pack retries use the same Android exit lookup by process name, PID and timestamp. `Job` saves the pack PID when its service marks a job running.
   - `android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java` — (2026-11-18) the Prepare card's progress bar is `dev.peanuts.ui.Strip.card` (the app's one bar: 6 dp, rounded, as the building cover's strip), one line.
   - `android/app/src/ondevice/AndroidManifest.xml` — (2026-11-13, code mods) one new `<service>` line, `dev.peanuts.build.ModBuildService`
     (the modded-variant build, the Mods section's "Build with mods"), beside the existing `BuildService` line.
   - `scripts/pack_android.py` — (2026-11-13, code mods) `--modules`/`--mod-exefs`/`--mod-patches`, block-marked (too much logic for a one-line
     hook per rule 2's own exception): a modded variant build's module-list override, per-module source override and exefs_patches application
     before the rewrite, plus writing the variant's own `profile.bin` when its module list adds one past the shipped profile. None given (the
     normal pack build): behaviour is byte-for-byte unchanged. `design/platform/DESIGN-MODS.md`, `peanuts/UPSTREAM-EDITS.md` Round 5.
   - `android/app/src/main/java/dev/nxnative/android/Ui.java`, `PadFocus.java`, `MainActivity.java`, `GameSettings.java` — (2026-11-14, haptics
     and motion inside a game's page) one call into `dev.peanuts.ui.Feel` per widget kind: a row's tap, a switch, a button, a segmented choice, a
     rail entry, a choice dialog's list and confirm button, the page's and a tab's entrance, Play. Additive only; `peanuts/UPSTREAM-EDITS.md` Round 7.
   - `android/app/src/main/java/dev/nxnative/android/Overlay.java` — (2026-11-17, SoC temperature) one `ITEMS` row (`soc`) and three lines in `tick()`: the flag, the `&& !soc` in the early return, and one call into `dev.peanuts.overlay.SocTemp.append(s)` (the line; `SocZones` reads `/sys/class/thermal`). Additive only. (2026-11-17, label / value table) two more `ITEMS` rows (`graph`, `driver`), `&& !dev.peanuts.overlay.Hud.extra(ctx)` in the early return, and `return dev.peanuts.overlay.Hud.table(...)` in place of `return s;` (Hud hands `s` back when the style is "classic").
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` (`overlayItems()`), `SidePanel.java` (the Overlay section) — (2026-11-17) one line each right after `Overlay.rows(...)`: `dev.peanuts.overlay.HudSettings.rows(<ctx>, box);` (the Style and Preset choices).
   - Share build log (2026-11-18, `dev.peanuts.ui.BuildLogUi`, `peanuts/UPSTREAM-EDITS.md` Round 24): `MainActivity.java` Settings › Share log's listener is
     `dev.peanuts.ui.BuildLogUi.shareLog(this, this::shareLog)` (the newest build instead, when a build log changed after the newest run), and one row after the
     game page's Share log; `ondevice/.../Ondevice.java` one line in the Panel's constructor after the Check for updates button (`BuildLogUi.card`: the
     button shows while the job has failed or is paused) and one button line in `manage()`.
   - `SidePanel.java` `look()` — (2026-11-17) one line after the `look(box, ...)` call: `dev.peanuts.ui.PanelPolish.apply(box);   // PEANUTS: ...` (segmented short radio groups, one look for headings, notes and buttons; styling only).
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-17) one line after `GpuDriver.env(...)`: `dev.peanuts.overlay.Hud.driver(...)` with the selected driver's label, else `Readiness.driverWord` of the system facts (null reads "System"), for the table's DRV row.
   - `android/app/src/main/cpp/nxlaunch.c` — (2026-11-17, overlay graph in realtime) in `Java_dev_nxnative_android_Native_overlaySet`, one line before `nxhost_overlay_set`: `if (peanuts_ovl_block) peanuts_ovl_block(...)` (JNI entry points forwarded to `dm/peanuts`), with `#pragma weak peanuts_ovl_block` and its prototype beside the other weak host symbols. `android/host/CMakeLists.txt`: one line compiling `dm/peanuts/peanuts_graph.c`; `android/host/libnxhost.map`: one line exporting `peanuts_ovl_block` (the host exports only `nxhost_*` and `Java_*`). `dm/peanuts/peanuts.c`: `pn_graph_frame();` in `peanuts_tick`.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-17, the GPU driver page) Settings' GPU driver row and the
     Readiness check's "Pick a GPU driver" open `dev.peanuts.ui.DriverActivity` instead of `GpuDriver.dialog`, two lines; the page itself and its
     manifest entry are Peanuts-owned. The Game settings row and the system-driver launch warning also open the page with a title ID.
     The activity result refreshes the game settings. Installs still go through `GpuDriver.install`. `peanuts/UPSTREAM-EDITS.md` Round 22.
3. **Settings go through upstream's own mechanisms**: `env.txt` keys named `PN_*`, `settings.tsv` rows, `DM_CTL` lines. No new settings UI inside upstream classes.
4. **Never reformat or "fix" upstream files.** A general fix belongs upstream as a PR to Banana Native (its AGENTS.md prefers general changes), then it arrives by rebase.
5. **Branches:** `upstream/android` is Banana Native; `peanuts` is this overlay, always a linear set of commits on top of a Banana Native tag. History is rewritten on every update (rebase, never merge).

## Updating to a new Banana Native release

```sh
peanuts/scripts/upstream-update.sh            # latest upstream/android
peanuts/scripts/upstream-update.sh v0.3.1     # a tag
```

The script fetches, rebases `peanuts` onto the target, then checks that every `PEANUTS` hook marker is still present. If a hook moved, fix the one-liner and re-run; nothing else should conflict.

## Status

- 2026-11-11: fork created on v0.3.0 (65745297). Hardware gate passed on the AYN Thor (Adreno 740, system driver): Readiness "Ready, with limits", Pokémon Pokopia boots to its first interactive screen at 10 fps / 1080p.
- 2026-11-11: P1 — the companion `Presentation` on the second display (`dev.peanuts.Companion`, two hooks in `GameActivity`) verified on the Thor: placeholder page on Screen-2 (display 4, 1080x1240) while the game runs on the main screen at an unchanged frame rate. Test app: `./gradlew apk -PnxApp=peanuts -PnxLabel="Peanuts Native" -PnxGames=0400A3A01EE4C000 -PnxPackEngine=fake` → `out/nxnative-android-peanuts.apk` (packs are made on the PC).
- 2026-11-11: P2 — the data path verified on the Thor with Pokopia: `dm/peanuts/peanuts.c` in libnxhost reads pointer chains from `$HOME/peanuts/chains.tsv` (re-read when it changes), refreshed by the `peanuts_tick()` hook in `vka_present.c` every 2 presented frames; `dm/peanuts/peanuts_jni.c` in libnxlaunch hands the snapshot to `dev.peanuts.Bridge`; the page polls it 4×/s. Pokopia `main` build `76a489f3bd3642fa5e18bd83d32c98ba`, base `0x7405d17000` on that run. Exported host symbols need `__attribute__((visibility("default")))` (the host builds hidden; the map alone does not export).
- 2026-11-11: with the Turnip driver (v26.2.0 R5, Settings › GPU driver) Pokopia runs at 60 fps on the Thor (GPU 5 ms) and renders correctly; the system driver gave 10 fps. Module bases change per run (ASLR), chains are module-relative.
- 2026-11-12 (R1): the Eden Duo DSMod runtime runs inside libnxhost (`dm/peanuts/rt/`, see its PORT.md): the unmodified Link's Awakening companion package (`<title ID>-work/peanuts/LinksAwakening/`) loads for the running build, decodes the game's own fonts, textures and text from its RomFS, and shows its standby page on the Thor's Screen-2 while the game runs at 60 fps. Runtime tick: 2.5 ms CPU per frame on the present thread (to move off it). Not yet: touch/gestures/actions (R2), guest calls, title `.so` modules, `.nup`-internal RomFS reads.
- 2026-11-12 (R2): touch on Screen-2 → the runtime's gestures and actions (mod_input, mod_actions lifted as-is); the virtual pad is the host's keyboard/touch input source (`dm_input_set`, ORed with the real controller); the runtime ticks on its own thread. Verified on the Thor in Link's Awakening: the map page with Link's live marker, the gear page with live counts and equips, taps (Close, pins) firing actions with haptic clicks, page binds following the game's menu state. Haptics are not yet routed to the device's vibrator.
- 2026-11-12 (R3): **Peanuts Companions** (its own launcher entry, `dev.peanuts.CatalogActivity`): the Eden Duo companions' GitHub releases as the catalog (newest `<TITLEID>-<Name>-<ver>.dsmod.zip` per title, names from the repo's catalog.json), Install / Update / Remove into `<title ID>-work/peanuts/<Name>/` beside the pack (`installed.json` keeps the version). Verified on the Thor: Link's Awakening installed from the catalog and loaded by the game at its next start.
- 2026-11-12 (branding): "Peanuts Native" text (6 string hooks), peanut icon and background via the `src/peanuts/res` overlay (release/debug source sets; same drawable names as upstream's banana art).
- 2026-11-12 (performance): the companion costs about half of what it did (direct guest reads against cached readable ranges, a tick every second presented frame, the runtime's dirty-rect publishes swizzled in place). Profiling scripts: see the owner's notes; LA's 30/60 fps flips come from the game's own frame governor reacting to the host's NVN→Vulkan pipeline latency, not from Peanuts.
- 2026-11-12 (pack engine): the APK carries the real on-device packer (`peanuts/scripts/ondevice-stage.sh` builds the stage on Linux; `-PnxPackStage=`). Pokopia prepared on the Thor in ~25 min and ran at 60 fps on that pack.
- 2026-11-12 (library flow, `peanuts/DESIGN-library.md`): first-run setup (keys, firmware fonts, game/update/DLC folders), a library of every NSP/XCI/`.nup`/dump in the game folder with the games' own names and icons and a status chip, a game sheet (base, update and DLC matched by title id or chosen, companion, **Build** = import → Prepare → companion, as a foreground service), the Companion section inside the game's settings (one `GameSettings` hook). The importer (`dm/peanuts/import`, `libnximport`) decrypts NSP/XCI with the user's own `prod.keys` on the device and writes a `.nup` (verified byte-exact against an extracted dump on the PC). Hook sites added: `android/app/build.gradle` (`targets` + the res overlay), `android/app/src/main/cpp/CMakeLists.txt` (importer), both manifests (activities, build service), `GameSettings.java`.
- 2026-11-12 (R5): the runtime is Eden Duo v1.3.5.1's (runtime 24, 86 files lifted again, four `PNRT` edits) and loads a package's **native module** (`dualscreen/modules/android-arm64-v8a/<title>.so`, hashed, staged in the app's private files, dlopen'd through the dsmod module ABI; its memory calls go to the host's guest reads). Verified on the Thor: Fire Emblem: Three Houses' companion (runtime 18 + module) loads and shows its standby page on Screen-2 while the game runs at 60 fps; Link's Awakening unchanged.
- 2026-11-12 (Fire Emblem: Three Houses on the Thor, imported from its NSP as a `.nup`): two host fixes. The sampler descriptor table holds every pool's span (`vka_tex.c`, 16384: the game's second pool bound ID 4352 past the old 4096 cap; a general change, to go upstream). The nine shaders the game compiles at boot with its own GLSLC are in no game file, so a pack from the files alone skips their draws (black panels, no text): Build now starts the game once with `DM_GLSLC_CAPTURE` after Prepare, then resumes the job from its split with the captured outputs (`dev.peanuts.build.RuntimeShaders`; verified by hand: 1,372 of 1,372, the title menu draws). Build also tries an untested game version anyway by itself and installs the companion even when Prepare failed.
- 2026-11-12 (R6): the runtime reads the game's RomFS inside a `.nup` (a provider over `dm/host/nup_image.h`, the update's package over the base's; `dm/peanuts/peanuts_nup.c`, one host CMake hook), so companions of imported games get the game's own fonts and art: Three Houses' standby page on the Thor shows its logo, font and house emblems from the container. Buffer textures are 2D row-wrapped images in the Vulkan backend (`vka_tex.c`, as the translated shaders read them; Three Houses' decal and light lists, 16384×65): no "has no view" lines left. The run-time shader capture verified end to end from the library's Rebuild (a long press on a Ready game's card opens its sheet): the game starts, nine outputs land in ~20 s, the job re-runs, 1,372 of 1,372.
- 2026-11-12 (play session, Three Houses): the companion's live pages verified in a battle (chapter, turn and phase, the map with every unit, the selected unit's card). **The Thor's lid is its sleep button**: with it closed Android sleeps while the Thor's shell keeps both panels on, the top activity becomes "top-sleeping" and its process moves to the background cpuset (cores 0-2, 2.0 GHz): the battle map fell to 50 fps with the replay and encoder threads waiting for a core. `dev.peanuts.GameHold`, a foreground service in `:game` for the game's run, keeps the process in the foreground group asleep or not. Audio "dragging": the AAudio stream releases 20 ms bursts, so the audio renderer rendered four frames at once and the game's updates fell between bursts (catch-up renders); the renderer now paces by the clock with eight blocks in flight (`hle_audren.c`; the catch-up count still rises, to be looked at). Indirect draws of quads (the KTGL 2D path) were skipped; their record is now read on the CPU (`vka_draw.c`). Open: the dialogue choice box's selection glow is tiled across the row (the frame's end cap repeated; not the sampler table, not the buffer textures: the draw's state, to be read from a frame capture). Profile of a battle: host recording 12 ms a frame for 680 draws (descriptor writes), encoder 17 %, companion redraw 11 % + tick 4 % of the process.
- 2026-11-12 (Echoes of Wisdom end to end on the Thor, from its NSP): the importer reads NCA compressed sections (LZ4 bucket tree; `dm/peanuts/import`), the Build imported 9.6 GB and translated 9,858 shaders in ~50 min, the game runs (title and play at 60 fps, the dungeon's governor flips to 30 as Link's Awakening's did) with its companion's native module live on Screen-2 (the prologue page: location, equipped items). Fixed on the way: the shared fonts go into every game's env (`GameEnv.ensureFonts`; the game crashed decoding the font without them), the companion package is matched by title and looked for beside the pack and the dump, the build installs it beside the pack, and the launcher hands the runtime main's whole NSO build id (`PN_BUILD_ID`) since the module lists whole ids.
- 2026-11-12: Echoes of Wisdom's frame governor (rewritten since Link's Awakening: one frame at or over 1/60 s holds present interval 2 for 12 frames, which renews forever at interval 2) is held off through its enable byte each present (`hle_fpslock.c`, `fps_target 60` in the profile): the dungeon runs at 60 fps on the Thor where it was stuck at 30.
- 2026-11-12 (one UI): the library lives in Banana Native's Home (`dev.peanuts.ui.Library`, five one-line hooks in `MainActivity` and two manifest lines; `PUi` is a copy of the toolkit on the same resources): filter row, sections, Banana's cards with the games' icons, the game sheet and Settings › Library as Banana dialogs and rows, first-run setup as one card. The separate library and setup activities are gone. Design records: the owner's NVMe `peanuts-native/design/`.
- 2026-11-12 (Home, revision 3): a cover grid (four per row, the state on the cover, the companion badge), a Building filter while something runs, Add game gone, the two-kernel peanut icon. Link's Awakening plays in handheld mode by a Peanuts per-title default (`GameEnv`): its 1080p frame was GPU-bound at 50 fps on the Thor; 720p holds 60.
- 2026-11-13 (launcher support, `peanuts/docs/LAUNCHERS.md`): home screen shortcuts (GameSheet's new "Add to home screen", the game's own cover art as
  the icon, `dev.peanuts.launch.GameShortcuts`), dynamic "last 3 played" shortcuts (`dev.peanuts.launch.RecentGames`, one marked line in
  `GameActivity.onCreate`), and a second, build-stable alias for emulator frontends (`dev.peanuts.launch.LaunchActivity`, one marked manifest block)
  alongside upstream's own `<applicationId>.Launch` (`android/FRONTENDS.md`, `FrontendLaunch`). All three only ever carry a title ID through an
  explicit intent at `FrontendLaunch`, never a path; `FrontendLaunch` itself re-validates the title ID against the library. Not yet run on a real
  device or a real frontend (checklist in `peanuts/docs/LAUNCHERS.md`).
- 2026-11-14 (PC/Mac builder, PR #13 by intermission88, `peanuts/docs/BUILDER.md`): `peanuts/scripts/peanuts_build.py` goes from NSP/XCI (or a `.nup`, or a dump folder) to a `.nup`, the pack and, with `--push`, the phone (`peanuts/scripts/push-pack.py`, default folder `/sdcard/Peanut Roms` in both); `scripts/pack_android.py` takes the phone packer's `DM_SPLIT_DIRECT=1` and `DMSH_VK_ZSTD_LEVEL=6` (the two `PackService` sets for every job); the importer builds on macOS and, written but not yet run, with MinGW-w64 on Windows (`nx_compat.h`). Icons of .nup games: a `.nup` holds no control data, so its library card showed a letter; the builder writes the icon beside the `.nup` as `<name>.png` (`nximport_cli icon`), `push-pack.py` pushes it, and `LibraryScanner.icon()` copies it into `files/icons/<ID>.png` when it is a PNG of at most 4 MiB. Tests: `peanuts/tests/test_peanuts_build.py`. Not yet run: the app build with the `LibraryScanner` change, Linux and Windows end to end.
- 2026-11-14 (0.1.1, the state for the release; the user-facing words are in `peanuts/docs/FAQ.md`): Mods live in the Game tab of the page a held cover
  opens (install from a folder or zip, a switch per mod, delete with its own progress row, Build with mods / Rebuild with mods); the build sheet has no
  Mods row and there is no Play with mods button: every start of a game uses the mods that are on (`ModsSection.packToPlay`). A code mod that hooks the
  game through an `.ips` and a module of its own builds and loads (`dm/tools/xmod.py`, Luminescent Platinum on Pokémon Brilliant Diamond; its own memory
  numbers in `dm/peanuts/profile/mod_memory.tsv`). Delete save data on the Saves tab (backed up first). Console mode (`ScreenMode`, `DM_OPMODE`) and
  Resolution scale (`ResScale`, `DM_RES_SCALE`, a first version) in Settings › Performance and picture (Game defaults before the update to Banana Native v0.3.26) and each game's Picture settings; Console mode also live in
  the in-game panel. The app says Firmware, not Firmware fonts. Pokémon Sword and Shield at 60 fps by their profiles; no controller style before the game
  sets its supported styles (`DM_HID_STYLE_EARLY=1` is the behaviour before). The PC builder runs end to end on Linux (`peanuts/docs/BUILDER.md`).
- Next: haptics → vibrator; guest calls; a play app id of its own; upstream the `vka_tex.c`, `vka_draw.c` and `hle_audren.c` changes; verify launcher
  support on the Thor and in an actual ES-DE/Daijisho install.
- Parked: P3 — Pokopia's first real values (memory-diff hunt on the Thor), then the page as DSMod-style widgets; P4 — taps → `nxhost_input_*`.
- 2026-11-13 (build folder): Settings › Library › Build folder (`PeanutsPrefs.buildDir`), a folder the owner picks (phone or microSD) to keep every
  build organized by title ID instead of beside the game and in the app's own storage. A NEW build of title T writes its `.nup` and its pack to
  `<build folder>/<T>/` (`BuildService.stepImport`; `AppStorage.pack` prefers an existing pack already in app storage first, else routes a new one into
  the build folder — one hook, so every place that already reads `AppStorage.pack`/`Folders.packFor` picks it up: `Prepare`'s job creation, the Manage
  sheet, the companion root beside the pack). The library scans the build folder too (`PeanutsPrefs.roots`, same two-level walk). An unmounted build
  volume hides its games without removing them: `Library.staleGames`'s path-based `volumeMounted` check already covers any `gameDir`, build folder or
  not — verified by reading, not changed. Not yet run on a real device (checklist: a fresh build into a microSD build folder, an existing internal
  build still plays unmoved, companion install root under the build folder).
- 2026-11-13 (Mods, play-time half, `design/platform/DESIGN-MODS.md`): a per-game Mods section (`dev.peanuts.mods`, new package) — local-import only
  (a picked folder or zip, never a catalog), normalizing Atmosphère (`atmosphere/contents/<tid>/{romfs,exefs}` + `exefs_patches`), Yuzu/Eden "load"
  (`<mod>/romfs`, `<mod>/exefs`) and bare (`romfs`/`exefs` at the root) layouts into `<app files>/mods/<TITLEID>/<modName>/` with a `mod.json`
  (`ModStore`, the shared contract with the build-time code-mod pipeline). The Mods section of a game's settings (`dev.peanuts.settings.ModsSection`, the Game tab of the page a held cover opens; first a `ModsSheet` opened from `GameSheet`) shows them (list, on / off
  = priority, remove). At launch, `GameActivity.start()` adds `DM_ROMFS_OVERLAY` (enabled mods' romfs dirs, highest priority first, the scene
  overlay falling back last — hle_fs.c:222-223/375-388/1289-1326, first root to have the file wins, directories union) and `DM_CHEATS` (enabled
  mods' cheat files staged into one folder — hle_cheats.c's flat-folder scan, hle_cheats.c:1316-1322). A "code" mod's romfs/cheats apply the same
  way; its exefs/exefs_patches never reach the normal build (`ModStore.needsBuild`, shown as "Needs a build with mods"). Layout normalizer and
  `ModStore` order/enable/remove covered by `peanuts/tests/test_mod_importer.py` and `test_mod_store.py` (javac/java, 18 cases, all passing). Not
  yet run on a real device (checklist: import a tiny romfs mod replacing one text/texture file, enable it, confirm the file changes in-game; a
  second mod with a conflicting file, confirming order wins; a cheat file applying from the staged folder).
- 2026-11-13 (Mods, build-time half: wiring into the real on-device build): `scripts/pack_android.py`'s "rewrite" step now takes `--modules`
  (a variant's full module list), `--mod-exefs NAME=PATH` (a mod's module as a replacement/added source) and `--mod-patches NAME=DIR` (IPS/IPS32
  applied via `dm/tools/patch_ips.py` before the rewrite reads it), and writes a variant `profile.bin` (`dm/peanuts/profile/gen_runtime_profile.py
  profile-modules`) when a mod adds a module past the shipped profile's own list. On the Java side: `dev.peanuts.build.ModdedBuild` turns a
  `ModBuild.Plan` into that argv and the exlaunch-hook warning (point 4 below); `Job.modArgs()`/`reuseShaders()` (new, block-marked, `Job.java`)
  carry it on a *separate* job keyed at `<game home>/<variantId>-work` and a *separate* pack (`AppStorage.pack(c, variantId)`, made `public` for
  this) — `Prepare.runModded` (new method) never touches the normal job/pack, and reuses the normal pack's own shader library (code mods never
  change shaders) instead of re-translating. `dev.peanuts.build.ModBuildService`/`ModBuildPipeline`/`ModBuildRequest` (new, own foreground service
  and id space, `BuildService`/`BuildPipeline` untouched) run it from the Mods section's "Build with mods"/"Rebuild with mods" button, which replaces
  the old static "not available from this sheet yet" chip; `GameSheet` offers "Play with mods" once a variant pack exists (`dev.peanuts.ui.Upstream
  .game()`, a throwaway `Game` pointed at the variant pack, never added to the library list). An added `subsdk9`-named module (or one whose bytes
  contain an "exlaunch" marker) gets a plain-worded, non-blocking warning (`ModdedBuild.exlaunchWarning`) that its runtime hooks will not take
  effect — the host's main is read-only once rewritten and `svcSetProcessMemoryPermission` is unimplemented; no static hook extraction attempted.
  Tests: `peanuts/tests/test_modded_build.py` (javac/java, 10 cases), `peanuts/tests/test_gen_runtime_profile_mods.py` (5 cases, against the real
  Pokémon Brilliant Diamond profile, 0100000011D90000), `dm/tools/tests/test_pack_android_mods.py` (7 cases) — all passing alongside the existing
  mods/IPS suites (87 tests total). The whole `android/app/src/main` + `src/ondevice` + `src/peanuts` source set javac-compiles clean against
  `android.jar` + `org.json` with stub `BuildConfig`/`R` classes (not a gradle build: no resource linking or manifest merge checked that way).
  **Not yet run on a real device** — see the mods build report for the exact synthetic-mod recipe to test the wiring without Lumi's own files, and
  what is still open for real Lumi (its exlaunch subsdk9 specifically).
- 2026-11-17 (SoC temperature, the overlay's "soc" item): `dev.peanuts.overlay.SocZones` (pure Java, the hottest `cpu*`/`gpu*` thermal zone under `/sys/class/thermal`, millidegrees converted, trip points and disabled zones skipped) and `SocTemp` (read every 2 s on its own thread; "SoC 52°C", red from 80 °C, "SoC ?" when no zone reads). Hook: `Overlay.java` (see the hook list above). JVM test: `SocZonesJvmTest` (`dm/tools/tests/test_android_soc.py`).
- 2026-11-17 (overlay label / value table): `dev.peanuts.overlay.Hud` builds the top-right block from spans only (monospace, grey labels, right-aligned values, a larger fps number, `HistogramSpan` over a placeholder whose chars encode the bars so `Overlay.key` sees new frames), `FrameStats` (median, 1% low, levels, placeholder), `HudSettings` (Style Table / Classic and Preset Minimal / Normal / Full / Custom under `Overlay.rows`; a preset sets the sheet's own checkboxes). New items `graph` and `driver`. Frame times come from the host through `Bridge.frameTimes(float[])` (native, written on the C side; without it the graph row is hidden). The graph is realtime: the launcher still redraws the block twice a second, but `HistogramSpan` reports the bars' box in the bitmap (`Bridge.graphRect`) and `dm/peanuts/peanuts_graph.c` keeps a copy of the block (`peanuts_ovl_block`) and paints a new bar into it on every game frame (`pn_graph_frame`, then `nxhost_overlay_set`); test `dm/host/tests/t_peanuts_graph.c`. The block can sit in any corner (Position, `pn_overlay_corner`, sent with `Bridge.overlayCorner`; the host's `peanuts_ovl_corner`; in a bottom corner the bitmap's transparent top margin rows are rotated to the bottom) and at 75 to 200 % (Size, `pn_overlay_scale`, one `RelativeSizeSpan` over the whole text). Known limit: the on-screen fallback TextViews (used only when the host cannot draw the overlay) stay top right. `FrameStats.shortDriver` gives a compact driver name (parenthesised groups, the words Adreno / Driver / Vulkan and `@` words dropped). Preferences `pn_overlay_style`, `pn_overlay_preset` in the launcher file. Tests: `FrameStatsJvmTest`, `dm/tools/tests/test_android_hud.py`.
- 2026-11-17 (side panel polish): overlay Preset / Style / Position / Size are four compact rows (value in the accent colour, a tap opens a single-choice dialog; the item switches show only for Custom) in `HudSettings`; `dev.peanuts.ui.PanelPolish` restyles a tab after `SidePanel.look()`: radio groups of up to 4 options with labels up to 12 chars become segmented controls, `head()` headings (bold, no side padding, top gap), `note()` texts (light grey 12 sp) and all-caps Buttons get one look each; views are tagged, so it is idempotent.

   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` (`startWithDriver`), `FrontendLaunch.java` — (2026-11-18, Swap screens, GitHub issue #131)
     `dev.peanuts.Screens.startGame(...)` starts the game on the external display when Swap screens is on. It falls back to the current display if the external one is unavailable. A game on another display (so also the app started there) leaves the built-in display to its
     companion (`CompanionScreen`, an activity: Android takes no Presentation on the default display). `AndroidManifest.xml`: the `CompanionScreen` activity (process `:game`). `peanuts/UPSTREAM-EDITS.md` Round 23.
- 2026-11-18 (Share build log, issue #124's "no log attached"): one `.zip` per game with everything its build left. `dev.peanuts.build.BuildLog` is the
  game's own build log, `<app files>/build-logs/<title ID>.log` (to `.1.log` at 2 MB), which `BuildService`, `ModBuildService` and `Prepare` write as
  they go: the inputs with sizes, the folders and free space, the memory, the import's milestones, Prepare's choice of job, the progress every 5 % and
  each change of step, and an `[end]` line; a run without one ended with the app's process. `BuildLogShare.make` finds the game's jobs as Prepare does
  (`DumpCheck.workOf` of the listed game, the remembered job, the work folders each build noted, the build folder, one level of the game folder, and every
  `<title ID>…-work` beside them, so builds with mods go to `mods/`). It zips the build log, each job's `job.json`, `env.txt` (secrets' values hidden)
  and `logs/*.log` (each file as long as it was when opened, cut to its first 256 KB and last 768 KB past 1 MB, 24 MB in all), the app's own logcat lines
  of the build's tags and of crashes, the newest ANR trace of each process, and `summary.txt` (app, device, memory, the build's and the jobs' states,
  the worker plan, free space, the last `ApplicationExitInfo` of each process). Only `.log`, `.txt` and `.json` files go in (`BuildLogBundle.allowed`),
  never keys, game files, saves or packs. `BuildLogProvider` serves it read-only; the sheet offers Share (the chooser on the app's display) and Save to
  a folder (request 71 through `LibrarySettings.result`). Entry points: Home's stopped build card, the Prepare card and its Manage sheet, the game's
  Storage tab, Settings › Share log, and ctl `share_build_log`. Tests: `dm/tools/tests/test_android_build_log.py` (`BuildLogJvmTest`).
- 2026-11-19 (game catalog and issue forms): `peanuts/games.json` is the machine-readable list of the games this version ships, written by
  `peanuts/scripts/games_catalog.py` from `peanuts/release-titles.txt` (which games), each `dm/games/<title ID>/game.tsv` (the builds Android
  plays, `android_builds` respected, and the package) and `peanuts/games.tsv` (the table's order, the name players know, a display
  version a profile lacks, the companion). The same script writes the Supported games table of `README.md` and `peanuts/docs/README.github.md`
  between its `PEANUTS begin` / `end` markers; `--refresh` adds a row for a game that ships without one and sets the companion column from
  eden-duo-companions' releases. Seven issue forms, written by `peanuts/scripts/issue_forms.py` from one source: **Game problem** (the log
  of the run required), **Build problem** (the Share build log `.zip` required), **Setup or app problem** (keys, firmware, the library,
  storage, drivers, companions, mods, saves, the second screen, the app), **Desktop builder problem**, **Game request**, **Feature
  request**, and **Question, device report or development topic**; `config.yml` links companion content to eden-duo-companions. They ask
  mostly by picks: the game with its version (one option per catalog build, then another version or another game with a line to write it),
  the app version (peanuts/VERSION first), device, chip with its GPU, RAM, Android, the driver family, the settings changed, whether it
  worked before, and a required check that the log is this game's; every form keeps optional text boxes, and Game problem and Setup or app problem take
  an optional save zip (Back up saves, or the backup folder with its manifest). The fields come from what issues
  #1-#170 had to ask for again and again. `.github/workflows/catalog.yml` (PEANUTS, own repository only) runs both scripts (the catalog with
  `--refresh`) on a push to main that touches their inputs and once a day, and commits what changed. `peanuts/tests/test_issue_forms.py` checks the scripts and the forms' structure and
  only skips, never fails, while the written files are out of date.

   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-17, the launcher on a second display) one line in `buildHome`
     after the header: `dev.peanuts.ui.BottomScreen.home(...)` gets Home's header (it steps aside while the second display shows the launcher's
     page) and how Settings opens (X). `peanuts/UPSTREAM-EDITS.md` Round 20.
   - `android/app/src/main/java/dev/nxnative/android/BananaBackgroundView.java` — (2026-11-17, the same) one line each in `onDraw` and `popAt`
     after the drift `wx, wy`: `dev.peanuts.ui.BackgroundFlow.drift(...)` replaces it while the second display shows the launcher, so both screens
     draw one field and a tap pops the banana where it is drawn. The drawing stays upstream's. `peanuts/UPSTREAM-EDITS.md` Round 20.
   - `android/app/src/main/java/dev/nxnative/android/NxApp.java` — (2026-11-17, the same) one line in `onCreate`: `dev.peanuts.ui.AppScreens.install(this)`
     lets the app's pages in other processes (Readiness check, Graphics self-test) tell the launcher they are in front, so the second display keeps
     its page. `peanuts/UPSTREAM-EDITS.md` Round 20.
- 2026-11-17 (the launcher on the second display, `dev.peanuts.ui.BottomScreen` / `BottomPanel`): while Home, Settings or a game's page is in front, the
  second display (the Thor's Screen-2, or any presentation display) shows a page of the launcher instead of Android's own: the cover the D-pad is on
  (Ready: Play and Game page; to build: what the scan found and Build…; building: the step, the bar, Stop build and Screens off), else the logo.
  A `Presentation` with `Theme.Banana`, not focusable (the controller stays on Home), shown while MainActivity is started and hidden (not
  dismissed) when it stops, so a game's companion takes the display as before; a `DisplayListener` follows the display coming and going. Built
  from the launcher's own pieces (`PUi`, `BananaBackgroundView`, the covers' marks); a column on a small or squarish panel, the cover beside the
  words on a landscape screen 600 dp or wider. Home keeps its Settings gear visible and labels its X shortcut. Y on a running build’s cover asks to stop it.
  Settings › Library › **Bottom screen** turns the panel off (`PeanutsPrefs.bottomScreen`). Its buttons show the face buttons while a controller is connected (`Pads`, the
  game's own rule), the app's icons without one. The cover mark
  "Building · N %" and the build card's step words show a thin, faint bar between the parts instead of the dot (`PUi.joined`). Home's drifting peanuts and the second screen's are one field while it shows (`BackgroundFlow`): the
  displays sit in it as on the device, the second centred right below the first, one phase for both; the drift is upstream's. Play
  of a game with a companion turns the card into a start page, and the game's process shows the same page (`GameScreen`) until the companion is
  up, also from a shortcut or a frontend; without one the second display is Android's, or the game's cover with Settings › Library ›
  **Games without a companion** (`PeanutsPrefs.bottomScreenInGames`). Seen on the emulator (Android 13, a 1240x1080 and a 1920x1080 second display, hot-plugged both ways):
  the logo page, Settings by X and back, the cards of a game to build and of one marked Not Supported Yet, the setting off. Not yet run on the Thor.
- 2026-11-19 (GitHub issue #170, Breath of the Wild's blocky Sheikah glow): the Android replay's view of a pass's own target follows the draw's sampler. A sampler that pins one level (mip filter, minimum and maximum LOD the same whole number) samples that level alone; any other keeps the levels below the attachment, as before. The glow chain's upsample reads level n + 1 while rendering level n, and the old cut clamped that read to level n - 1. nxnative's `fb_pinned_level` rule (its PR 1073) in `vka_tex.c`, an upstream candidate; `DM_VK_FB_PIN=0` turns it off, live. Test: `dm/tools/tests/test_vka_fb_pinned_level.py`. `peanuts/UPSTREAM-EDITS.md` Round 30.
- 2026-11-19 (issue #149, compressed game files): `.nsz` and `.xcz` build like `.nsp` and `.xci`. The importer rebuilds each NCZ to the original NCA
  below its NCA reader (`dm/peanuts/import/nx_ncz.c`, a store under `nx_nca.c`): a block NCZ is read in place, a solid one unpacked once into a temporary
  file beside the `.nup` (unlinked as soon as it is open); each is checked against its name and its CNMT record before the writer starts, and the
  `.nup` is the uncompressed files' byte for byte. Stop now ends any import within a second (`nximport_cancel`). The library lists the files, the sheet
  counts the real size and the temporary space, the build card says Unpacking or Checking, a damaged file gets its own message
  (`dev.peanuts.build.Compressed`). The desktop builder takes them too (`peanuts_build.py`, `nximport_cli check`). Thor, ACNH 3.0.3 with Happy Home
  Paradise compressed with nsz 5.0.0: the `.nup` of the NSPs, of a solid set, of a block set and of a mixed set are one file (SHA-256 the same, 11.05
  GiB); import 30.8 s from the NSPs, 41.7 s from blocks, 64.3 s from solid; peak memory 321 to 403 MiB. A Build in the test app from the solid set ran
  to Ready in 8 min 4 s; Stop while unpacking left nothing behind. UPSTREAM-EDITS Round 29; HOOKS.md "Compressed dumps".
