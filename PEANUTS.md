# Peanuts Native

A second-screen companion layer for Banana Native, the sibling of Eden Duo, for Switch 1 games only: Banana Native's Switch 2
profiles and their host gates are not carried (`peanuts/upstream-dropped.txt`, kept out on every sync). Upstream
Banana Native ships several releases a day, so this fork is kept as a **thin overlay** that rebases cleanly.

## Rules that keep upstream updates cheap

1. **All new code lives in Peanuts-only places.** Upstream never touches them, so they never conflict:
   - `android/app/src/main/java/dev/peanuts/` — launcher side: the `Presentation` on the second display, its view, the JNI bridge.
   - `dm/peanuts/` — host side: companion package loader, guest readers, tick, input injection (plain C).
   - `peanuts/` — companion packages, docs, tests, `scripts/`.
2. **Hooks into upstream files are one-liners, each tagged `PEANUTS`.** A hook is a call-out to Peanuts code, never logic.
   Allowed hook sites (keep this list current):
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — create/destroy the companion `Presentation`; (2026-11-13) one line in
     `onCreate` that touches the last-3-played list for dynamic shortcuts (`dev.peanuts.launch.RecentGames`, `peanuts/docs/LAUNCHERS.md`); (2026-11-21) one line at the top of
     `fpsTick` and one in `onDestroy` for the total play time (`dev.peanuts.launch.PlayTime`; the game page's Played row is one line in `MainActivity`'s More card); and
     (2026-11-13) in `start()`'s env assembly, two lines adding `DM_ROMFS_OVERLAY`/`DM_CHEATS` for the enabled Mods (`dev.peanuts.mods.ModRuntime`,
     `design/platform/DESIGN-MODS.md`); (2026-11-18) one line in `settingsEnv` after `ResScale.env`: `dev.peanuts.Companion.env(this, id, env)` adds `PN_COMPANION=off` when the title's Second screen is Off, which is the default on a device without a second display (`Companion.hasSecondDisplay`); internal shader-preparation mode (`dev.peanuts.build.ShaderCapture`) covers the game surface, isolates its save/environment, suppresses play-session hooks and input, and reports errors to Build.
     (2026-11-17) `hostSetup` and the active existing `fpsTick` forward the current display's presentation deadline through a marked helper. The same tick applies `dev.peanuts.display.RunningDisplayMode` before reading the deadline; pause and destruction release its window-local request. Only the running effective 30 fps cap with requested FG off selects a same-resolution 60 Hz mode; pending restart preferences are ignored.
   - `android/app/src/main/cpp/nxlaunch.c` — JNI entry points forwarded to `dm/peanuts`; (2026-11-17) `Native.java`/`nxlaunch.c` weakly forward the active display's queue lead to `nxhost_presentation_deadline_set`, exported in `vk_android.h`. Nonpositive or unavailable deadlines become zero; older hosts ignore the hint.
   - `android/app/src/main/java/dev/nxnative/android/Lsfg.java` — (2026-11-17) `push` releases the Activity's display request before its native FG enable call; actual display switching is asynchronous.
   - `dm/gfx/vk_android/vk_android.h`, `vka_framegen.c`, `Native.java`, `nxlaunch.c` — (2026-11-17) expose initialized requested FG state independently of rendering warm-up. An unavailable interface leaves display selection unrestricted; external native changes use the existing Activity tick.
   - `android/host/CMakeLists.txt`, `android/app/src/main/cpp/CMakeLists.txt` — add the `dm/peanuts` sources.
   - `dm/gfx/vk_android/vka_present.c` — one call per presented frame (`peanuts_tick()`), beside `ovl_draw`; (2026-11-17) one weak declaration and one argument in `ovl_draw`'s `vko_place(...)`: `peanuts_ovl_corner(s, g_ocorner[s])` (the stats block's corner chosen in the launcher; the build tag steps aside from a top-left block); (2026-11-27) direct 4-RTT dual-screen hooks (`pn_dual_slot_drop_all`, `pn_dual_slot_done`, `pn_dual_take`/`pn_dual_draw_top`, `pn_dual_present_aux`, `pn_dual_frame_fill`; `dm/peanuts/peanuts_dual.c`, `PN_DUAL_RTT=1`).
   - `dm/gfx/vk_android/vka_draw.c` — (2026-11-27) `pn_dual_observe_draw` call in `draw_body` (guarded by `pn_dual_wanted()`, `dm/peanuts/peanuts_dual.c`) to latch pre-composite DS render targets (`PN_DUAL_RTT=1`).
   - `dm/host/hle_hid.c` — (2026-11-27) weak `pn_dual_pad_note` / `pn_dual_pad_mask` hooks in `shake_note` and `fill` for the `PN_DUAL_SWAP_BTN` (default ZL/L2) dual-screen swap toggle (`dm/peanuts/peanuts_dual.c`).
   - `android/app/src/main/java/dev/nxnative/android/TextInput.java` — (2026-11-27) two lines (`dev.peanuts.Companion.keyboardOpen(true/false)`) around the text-entry dialog so the Android soft keyboard can appear on the bottom display.
   - `dm/host/hle_audren.c` — (2026-11-20, audio follows the game's pace) in `render_main`, a weak declaration of `pn_audio_follow` and two marked lines: the rendered frame goes through `pn_audio_follow` (`dm/peanuts/peanuts_audiosync.c`; the clock's next render moves by the frames it gave out), and a call that gives nothing out yet (the stretch filling its look-ahead) appends no block. `dm/peanuts/peanuts_audiovoice.c` takes over `nn::audio::SetVoicePlayState` with its own `HLE_FORCE` (calls the SDK's own first): if upstream ever takes that symbol over too, merge the two. `peanuts_tick` feeds the game's frames (`pn_as_frame`, `nvn_game_fps` weak). `PN_AUDIO_FOLLOW` 0 / 1 (automatic, default) / 2.
   - `dm/host/hle_net.c` — (2026-11-24, issue #610) the socket Poll / Select waits (`efd_wait`, `sk_idle`, the co-op mixed poll's sleep) are native waits (`sk_sleep_ns`, `dm_thread_native_wait`), so a stop of all threads is not held up by a game thread that polls in a loop (FINAL FANTASY RESONANCE's HttpManagerThread kept its companion's load plan out). Tests: `t_net_eventfd.c` (two checks), `t_net_coop.c` (the stub). Upstream candidate. `peanuts/UPSTREAM-EDITS.md` "Socket waits are native waits".
   - `dm/gfx/vk_android/vka_dev.c` `vka_gpu_floor` — (2026-11-23, #113/#257) one weak call into `dm/peanuts/peanuts_gpufloor.c`: the KGSL clock floor's beat on kernels that apply the constraint only to flagged commands (`PN_GPU_FLOOR_BEAT`). `peanuts/UPSTREAM-EDITS.md` Round 62.
   - `dm/gfx/vk_android/vka_dev.c` `vka_cap_period_clamp`, `vka_perf.c` — (2026-11-24, 0.1.8 hardening) the active frame cap's period as the shortest, one function for the GPU floor's period and the ADPF target (`dm_fps_cap_get` / `nxhost_fps_limit_get`, weak). `peanuts/UPSTREAM-EDITS.md` "0.1.8 hardening".
   - `dm/gfx/vk_android/vka_tex.c` `vka_tex_b2t_mirror`, `vka_dlss.c` `dpass`, `vka_fsr3.c` `pass` — (2026-11-24, 0.1.8 hardening) one `vka_dyn_invalidate()` each after a compute pass recorded between the game's dispatches, so the next dispatch pushes its descriptors again (`vka_draw.c` `push_same`). `peanuts/UPSTREAM-EDITS.md` "0.1.8 hardening".
   - `dm/gfx/vk_android/vka_ovl.h` — (2026-11-24) two lines: the places `VKO_TOP_CENTER` (4) and `VKO_BOTTOM_CENTER` (6) and, in `vko_place`, `if (corner & VKO_CENTER) dx = (ew - bw) / 2;` (Overlay › Position's Top / Bottom center). `dm/host/tests/t_android_ovl.c` checks them in one `PEANUTS begin` / `end` block, through every turn.
   - `dm/gfx/vk_android/vka_dev.c`, `vka_readiness.c` — (2026-11-17) call the Peanuts guest-buffer remap probe before trusting advertised placed-memory support; use the existing tested exported-alias path on failure. `peanuts/UPSTREAM-EDITS.md`, issue #47 follow-up.
   - `android/app/build.gradle` — applicationId / label (`-PnxAppId=<suffix>`: a development build beside the app, with its own data); (2026-11-18) `androidBuildsOf` and its use in `buildsOf`: a profile's `android_builds <version> ...` row names the builds this app builds and plays when not all of the profile's (the launcher reads the others as a version it does not have; the host and the packer still know every build row).
   - `android/app/src/main/AndroidManifest.xml` — new Peanuts-owned activities/services/aliases, each its own marked block (already used for
     `CatalogActivity` and `GameHold`; 2026-11-13 added the `dev.peanuts.launch.LaunchActivity` alias, `peanuts/docs/LAUNCHERS.md`; 2026-11-18 the
     `dev.peanuts.build.BuildLogProvider` provider, `${applicationId}.buildlog`, read-only: Share build log's bundles; 2026-11-21 the Users and Miis
     activities `dev.peanuts.mii.MiiEditor`, `dev.peanuts.users.PictureActivity` and `dev.peanuts.users.WhoPlaysActivity`, one line each, Rounds 46-48).
   - `android/app/src/main/java/dev/nxnative/android/AppStorage.java` — (2026-11-13) `pack()` prefers an existing pack already in the app's own storage,
     else a build folder's `<title ID>/pack` when the user set one (`PeanutsPrefs.buildPackDir`; Settings › Library › Build folder); (2026-11-13,
     code mods) the class and `pack()` made `public` so `dev.peanuts.build.ModdedBuild`/`Prepare` can ask for a modded variant's own pack folder by
     its variant id, same call as every other pack lookup. `Folders.find()` also considers existing packs in every configured Build folder.
   - `android/app/src/ondevice/java/dev/nxnative/android/Job.java` — (2026-11-13, code mods) `modArgs()`/`modArgs(String)` (a modded variant job's
     extra rewrite-step argv) and `reuseShaders(File)` (skip split/shaders, point the shader cache at the normal pack's own library) — block-marked,
     additive only; a normal job never sets either. `design/platform/DESIGN-MODS.md`.
   - `android/app/src/ondevice/java/dev/nxnative/android/PackService.java` — (2026-11-13, code mods) the "rewrite" step appends `Job.modArgs()`
     (split on `"\n"`) to `pack_android.py`'s argv when it is not empty — block-marked, a no-op for a normal job.
     (2026-11-18, issue #120) the shaders step runs `dm/peanuts/reuse/shader_reuse.py` in place of `vk_android_build.py` (same argv; not for the simulated engine) and sets `PN_REUSE_DIR`; `android/app/build.gradle` puts `dm/peanuts/reuse/*.py` into `tools.zip`; `scripts/pack_android.py` `rewrite` keeps a module whose key moved with other builds' rows (`dm/peanuts/reuse/rewrite_reuse.py`) and records each module after the rewrite (`peanuts/docs/INCREMENTAL-BUILD.md`). (2026-11-19) `dm/gfx/tools/shader_split.py` `pooled` / `split_tables`: the scan results of unchanged plain files kept across builds (`dm/peanuts/reuse/split_reuse.py`; `peanuts/docs/INCREMENTAL-BUILD.md`, "The split"). `dm/tools/romfs.py` `walk` by the image's nodes (upstream candidate). `android/app/src/main/java/dev/nxnative/android/StorageScan.java`: Settings › Storage lists each game's build cache (`no_backup/reuse/<title ID>`, with the kept copy of its last shader library). `dm/tools/patch_nso.py` `in_forks` (upstream candidate) and `scripts/pack_android.py` `pn_rewrite_worker`, `PackService` `PN_REWRITE_J`: the phone's rewrite on its fast cores. (0.1.8) `rewrite_variants` runs a variant's rewrite through the same worker (a `# PEANUTS` line); a worker the system killed fails the build, as any other exit code. `PackService`: the full check of a build's kept shaders (`onStartCommand` with `ReuseCheck.EXTRA_CHECK`, `checkKept`, `step()` with `checking`, a job's start during it goes first). (2026-11-20, a review of #150) `PackService`: the check's stop sent again every 3 s, a check that cannot be made dropped and one that fails tried three times (`dev.peanuts.build.CheckDue`), one after an app update dropped, `full_check`'s exit code 3 its end, no check notification left by `onTimeout`, `capped` takes `PN_REWRITE_J`; `shader_split.py` `unsized` lets a `MemoryError` or an `ImportError` through, so the split never keeps a result its decoder lost. `android/app/src/jvmtest/java/dev/nxnative/android/StorageScanJvmTest.java`: the build caches row. `dm/tools/tests/test_split_versions.py` (new): upstream's targeted split of one version of a multi-version profile. `README.md`: the contributors' paragraph. `peanuts/UPSTREAM-EDITS.md` Round 26.
     (2026-11-17) shader-map validation and selection call `RuntimeShaderMap.missingRuntime`: a deficit containing only boot-generated shaders is prepared for Build’s internal capture, while missing file-based keys still stop.
   - `android/app/src/main/java/dev/nxnative/android/ExitWatch.java` — game exit notices and on-device pack retries use the same Android exit lookup by process name, PID and timestamp. `Job` saves the pack PID when its service marks a job running.
   - `android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java` — (2026-11-18) the Prepare card's progress bar is `dev.peanuts.ui.Strip.card` (the app's one bar: 6 dp, rounded, as the building cover's strip), one line.
     (2026-11-22, the build queue, #348) `prepare()` first asks `dev.peanuts.ui.Library.queuePrepare`: while the library builds or its queue waits, the
     Prepare goes into the build queue as a build of that `.nup` or dump (the packer runs one job), one line.
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
   - `android/app/src/main/java/dev/nxnative/android/Overlay.java` — (2026-11-24, the pill) one `PEANUTS begin` / `end` block in `Block.draw`: for slot 1, `dev.peanuts.overlay.Hud.margins(...)` gives the margins as locals over the inset fields (the pill floats 6 dp off the edges with a round background, a centred block is centred, a left one has its side margin on the left).
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` (`overlayItems()`), `SidePanel.java` (the Overlay section) — (2026-11-17) one line each right after `Overlay.rows(...)`: `dev.peanuts.overlay.HudSettings.rows(<ctx>, box);` (the Style and Preset choices).
   - Share build log (2026-11-18, `dev.peanuts.ui.BuildLogUi`, `peanuts/UPSTREAM-EDITS.md` Round 24): `MainActivity.java` Settings › Share log's listener is
     `dev.peanuts.ui.BuildLogUi.shareLog(this, this::shareLog)` (the newest build instead, when a build log changed after the newest run), and one row after the
     game page's Share log; `ondevice/.../Ondevice.java` one line in the Panel's constructor after the Check for updates button (`BuildLogUi.card`: the
     button shows while the job has failed or is paused) and one button line in `manage()`.
   - The Share log chooser everywhere (2026-11-21, `dev.peanuts.ui.BuildLogUi.shareLog`, words in `dev.peanuts.ui.LogChoice`, `peanuts/UPSTREAM-EDITS.md`
     Round 42): every place that shares a log asks between the game's newest run and its build when it has both, and shares the one there is otherwise. Upstream
     lines: `MainActivity.java` the game page's two rows Share log and Share build log become one **Share log…** row (`BuildLogUi.shareLog(this, g.titleId,
     g.name, null, () -> shareLog(g.titleId))`; Settings › Share log keeps its line); `ExitWatch.java` the "Game stopped" notice's Share log
     (`BuildLogUi.shareLog(a, title, name, log, () -> a.shareLog(log, digest))`); `ondevice/.../Ondevice.java` the Manage sheet's button (Share log,
     `BuildLogUi.shareLog(a, j.titleId(), j.name(), null, null)`) and the Prepare card's comment. A run picked where the caller gives no way to share it goes
     through `MainActivity.shareLog(File, File)` by reflection.
   - `SidePanel.java` `look()` — (2026-11-17) one line after the `look(box, ...)` call: `dev.peanuts.ui.PanelPolish.apply(box);   // PEANUTS: ...` (segmented short radio groups, one look for headings, notes and buttons; styling only).
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-17) one line after `GpuDriver.env(...)`: `dev.peanuts.overlay.Hud.driver(...)` with the selected driver's label, else `Readiness.driverWord` of the system facts (null reads "System"), for the table's DRV row.
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-24, the pill) the build tag's two `Prefs.overlayItem(this, "tag")` reads (`onCreate`, `fpsTick`) are `dev.peanuts.overlay.Hud.tagOn(this)`, each marked.
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-24) two lines in `fpsTick()`: `&& !dev.peanuts.ui.GameScreen.hidesOverlay(this)` on the overlay's `on` (the overlay over the game hides while the second screen shows the stats page, Overlay › With bottom stats, on by default) and `dev.peanuts.ui.GameScreen.buildTag(tag.getText())` (the stats page names the build at its bottom right).
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-24) one line in `onCreate` and one in `onDestroy` hand `dev.peanuts.ui.GameControls` its host (`peanutsControls()`, one `PEANUTS begin`/`end` block before `titleId()`: the same console mode and frame rate cap calls as `SidePanel`'s rows) for the stats page's pop-ups.
   - `android/app/src/main/cpp/nxlaunch.c` — (2026-11-17, overlay graph in realtime) in `Java_dev_nxnative_android_Native_overlaySet`, one line before `nxhost_overlay_set`: `if (peanuts_ovl_block) peanuts_ovl_block(...)` (JNI entry points forwarded to `dm/peanuts`), with `#pragma weak peanuts_ovl_block` and its prototype beside the other weak host symbols. `android/host/CMakeLists.txt`: one line compiling `dm/peanuts/peanuts_graph.c`; `android/host/libnxhost.map`: one line exporting `peanuts_ovl_block` (the host exports only `nxhost_*` and `Java_*`). `dm/peanuts/peanuts.c`: `pn_graph_frame();` in `peanuts_tick`.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-17, the GPU driver page) Settings' GPU driver row and the
     Readiness check's "Pick a GPU driver" open `dev.peanuts.ui.DriverActivity` instead of `GpuDriver.dialog`, two lines; the page itself and its
     manifest entry are Peanuts-owned. The Game settings row and the system-driver launch warning also open the page with a title ID.
     The activity result refreshes the game settings. Installs still go through `GpuDriver.install`. `peanuts/UPSTREAM-EDITS.md` Round 22.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-21, Back keeps your place) one line each in `onCreate` (after the
     first `build()`), `onSaveInstanceState`, `openAdvanced` (before `build()`) and `closeAdvanced` (after it), and two in `onConfigurationChanged`
     around its `build()`: `dev.peanuts.ui.PagePlace` takes the page's scroll position and focused row and puts them back once the page is built
     again. Home keeps its own (`dev.peanuts.ui.Library`). `peanuts/UPSTREAM-EDITS.md` Round 41.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-21, Settings in ten sections) one line at the end of `buildSettings()`:
     `dev.peanuts.ui.SettingsPage.lay(this, content)` moves the rows upstream built, by their titles, into the sections of `dev.peanuts.ui.SettingsSections`
     and shows one, with a sidebar of them on a window 600 dp wide or more, tiles of them on a tablet and a drawer on a phone. The rows, their listeners and
     `refreshSettings()` stay upstream's. And one line in `onBackPressed()`: `SettingsPage.onBack(this)` takes a tablet's Back from a section's page to
     the tiles. `peanuts/UPSTREAM-EDITS.md` Round 44.
   - `android/app/src/main/java/dev/nxnative/android/PadFocus.java` — (2026-11-21, the controller's focus on buttons and tabs) `watch()` puts the focus
     frame on through `dev.peanuts.ui.FocusFrame.at`, which draws it where the view shows: a one-line, centred text (every button, Segmented tab and chip)
     scrolls far inside itself, and a view draws its foreground in those scrolled coordinates, so the frame was off screen there and About's Check for
     updates showed no focus at all. The frame also counts as the view's focus look, so Android adds no grey highlight. `peanuts/UPSTREAM-EDITS.md` Round 44.
   - `android/app/src/main/java/dev/nxnative/android/BananaBackgroundView.java` — (2026-11-21, Peanuts cracked) one line at the start of `drawBanana`:
     `dev.peanuts.ui.Crack.draw(...)` draws the first 900 ms of a pop as the app's peanut cracking open (PeanutArt) in a Peanuts build while the system's
     animations are on; the burst stays upstream's otherwise, and so do the gone and grow-back phases. `peanuts/UPSTREAM-EDITS.md` Round 45.
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` — (2026-11-21, Who's playing?) the users line in `start()`'s env: the preselected
     user (`DM_USER`) is `dev.peanuts.users.WhoPlaysActivity.startUser(...)`, the user picked before the game started, else upstream's default.
     `peanuts/UPSTREAM-EDITS.md` Round 48.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-21, Who's playing?) `onCreate`'s `GameReturn.back` line: when no game
     comes back, `dev.peanuts.users.WhoPlaysActivity.appOpened(...)` asks who plays first (the app's icon, two users or more); and in the Peanuts block
     `headerButtons`, `dev.peanuts.users.ProfileButton` after Settings: the user playing now, with Profile and switching. Round 48.
   - `dm/host/hle_account.c` — (2026-11-21, the users' pictures) a marked block before `LoadProfileImage` (`user_image`: `DM_USER_IMAGES/<uid>.jpg`) and one
     line in it; too much for a one-line hook, as `pack_android.py`'s, so block-marked. `peanuts/UPSTREAM-EDITS.md` Round 49.
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-21, Home's first focus) `buildHome`'s header line (the rebrand's):
     `dev.peanuts.ui.Library.homeHeader` makes the build line plain text, so a controller's first focus is the first cover. Round 53.
   - `android/app/src/main/java/dev/nxnative/android/SaveUi.java`, `SaveStore.java`, `MainActivity.java` (`gameTab`), `GameActivity.java` (`saveDir`) — (2026-11-23,
     saves for each user, #422) the Saves tab works on the save of the user picked on it: `UserSaves` (a Peanuts file in this package, as `ResScale`) draws
     the user cards above the rows and Copy to another user; SaveUi takes `UserSaves.save` for `Folders.save`, names the user in its dialogs and runs another
     user's backup or restore under the game's lock (`UserSaves.locked`); SaveStore records a backup's user in its manifest and keeps the device save, every
     user's, out of another user's save (its helpers in one marked block, so the class needs no other file); GameActivity finishes a stopped restore into any
     user's save at the start (`UserSaveDirs.recoverUsers`; one it cannot finish is left and noted, it stops no start). `peanuts/UPSTREAM-EDITS.md` Round 60.
   - `android/app/src/main/java/dev/nxnative/android/GameSettings.java` — (2026-11-23, a user's saves in Settings › Users) one line after `open`: `openOn(a, g, tab)`
     opens a game's page at a tab, so Show saves on a user's card opens the game's Saves tab with that user picked (`UserSaves.open`). Round 61.
   - `android/app/src/main/java/dev/nxnative/android/GameSettings.java` — (2026-11-23, Game page Part A) the page in areas (`dev.peanuts.ui.GamePage`):
     `build`'s first line hands the page to `GamePagePort.build` on a window 600 dp wide or more; its `wide` and sections lines build the narrow column with every section while the port
     takes upstream's rows off its tabs (`GamePagePort.harvesting`); `stepTab` asks `GamePagePort.step` after Mods & Cheats' own line; `openOn` (Round 61's
     line) tells the port the tab, so Settings › Users opens the area Saves. `GamePagePort.java` is Peanuts' own file in upstream's package.
   - `android/app/src/main/java/dev/nxnative/android/SidePanel.java`, `GameActivity.java`, `SaveStore.java` — (2026-11-24, the in-game menus, `InGamePanel`,
     `dev.peanuts.ingame`) the one side panel becomes two menus. SidePanel is the quick menu: `TAB_CONTROLS` not final (Cheats and Mods come before it);
     its constructor hands its sheet to `InGamePanel.frame` (a rail of the tabs at the left, a header with Back and the tab's name); `tabBar`'s first line
     `InGamePanel.rail`; `showTab` keeps the focus on the rail (`InGamePanel.railFocused`); `open` and `close` slide through `InGamePanel.slideIn`/`slideOut`;
     `padKey`'s back key is `InGamePanel.panelBack`; `look` ends with `InGamePanel.laid` (what moved to the game menu). GameActivity: `attach` in `onCreate`
     (the game menu, a swipe in from the left edge), the combination through `InGamePanel.key` (Home held: the quick menu) and `InGamePanel.toggle` (Home
     pressed, − and + held: the game menu), the controller's own Back through `InGamePanel.backKey` (pressed the game menu, held the quick menu: the
     Thor's Home is Android's), `menuKey` and `menuMotion` (the game menu has the controller), Back through `InGamePanel.back`, and `runSave()`
     (the save the game runs on). SaveStore: `backupRunning` (the game menu's Back up the save, under the running game's own lock: the copy without the
     recovery, refused while the host's swap at a Commit has its marker beside the save).
     (2026-11-24, one menu: Layout) SidePanel: `rows()` gives the panel's rows to a second panel, the one menu at the right or on the bottom screen
     (`InGamePanel.openRight`, `openBottom`); `open()` starts with `InGamePanel.instead` (one menu at the right: the swipe and the controls' button open it).
     (2026-11-24, the quick menu button) GameActivity: `dispatchKeyEvent` hands the player's own button to `InGamePanel.quickKey` (dev.peanuts.ui.QuickButton).
     `peanuts/UPSTREAM-EDITS.md` "In-game menus".
   - `GameActivity.java`, `MainActivity.java`, `SidePanel.java`, `Lsfg.java` — (2026-11-26, Smooth Experience
     and Developer view, `dev.peanuts.smooth`; `peanuts/tests/test_smooth_experience.py` lists every line) Smooth Experience (Settings › App › Extras, off by
     default, its parts right under it while it is on): `start()`'s first line puts up `dev.peanuts.ui.StartCover` (the game's cover and a breathing peanut in
     place of the log's lines), `tail()` hands it what the log says (one line after the log view's visibility; its next look in 250 ms while the cover waits for
     the first frame) and `dispatchKeyEvent` gives the pad to its failure card; the "Preparing graphics" block (#322) and `skipShow` write their log line
     instead of the notice. The overlay keeps its own switches. Developer view (Settings › Developer, on for an app installed before, off for a new install):
     the start's tester toasts (a switch nothing reads, `DevView.startToast`; environment lines not taken), the ignored adb command's toast, and the
     in-game menu's tool words (`DevView.panel`, `DevView.frameGen`: SidePanel's two "DM_CTL is off" notes, the cap line, the frame generation state, Lsfg's
     two lines). Every toast a player needs shows either way.
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
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` (`onTopResumedActivityChanged`) — (2026-11-20, GitHub issue #183)
     `dev.peanuts.Companion.gameOnTop(this)` each time the game is the top activity again: a game that Recents moved to another display goes back to its
     own, and the companion opens again where it belongs (`ScreenPlace`). `peanuts/UPSTREAM-EDITS.md` Round 31.
   - `android/app/src/main/java/dev/nxnative/android/GameActivity.java` (`onCreate`, `onConfigurationChanged`) — (2026-11-22, TV and monitors, GitHub issue #358)
     `dev.peanuts.TvPicture.fit(this)`: on a TV or monitor the game's surface is at most 1080 lines high and the window asks for the TV's game mode. Which display
     is which: `dev.peanuts.Screens` and `ScreenRole` (the bottom screen, the companion and Screens off use only the device's own screens). By default the
     system decides what a TV shows; Play on the TV (Settings › TV and monitors) starts a game there, and the screen it left is black unless the system turned
     it off. `peanuts/UPSTREAM-EDITS.md` Round 59.
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
  eden-duo-companions' releases. Six issue forms, written by `peanuts/scripts/issue_forms.py` from one source: **Game problem** (the log
  of the run required), **Build problem** (the Share build log `.zip` required), **Setup or app problem** (keys, firmware, the library,
  storage, drivers, companions, mods, saves, the second screen, the app), **Desktop builder problem**, **Feature
  request**, and **Question, device report or development topic**; `config.yml` links companion content to eden-duo-companions and says first that game requests are not accepted (every form says so too; the Game request form was removed on 2026-11-26). They ask
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
   - `android/app/src/main/java/dev/nxnative/android/MainActivity.java` — (2026-11-21, the bottom screen's peanuts pop) one line in `onCreate` after
     `bananas.setOnPop(...)`: `dev.peanuts.ui.BottomScreen.onPop(this, ...)` counts a peanut popped on the second display as the top screen's
     (`Prefs.addBananaPopped`, Settings' line while it shows). `peanuts/UPSTREAM-EDITS.md` Round 43.
   - `android/app/src/main/java/dev/nxnative/android/NxApp.java` — (2026-11-17, the same) one line in `onCreate`: `dev.peanuts.ui.AppScreens.install(this)`
     lets the app's pages in other processes (Readiness check, Graphics self-test) tell the launcher they are in front, so the second display keeps
     its page. `peanuts/UPSTREAM-EDITS.md` Round 20.
- 2026-11-19 (Yaz0 in C, issue #120): `_nxyaz0` (`dm/peanuts/pack_worker/nxyaz0.c`, a module of the phone's `lib-dynload` like `_nxaes`, and of the Linux builder's `peanuts/.build/pymod`) decodes `shader_split.py`'s Yaz0 with the Python loop's bytes; where the loop stops on a damaged stream it hands that stream back, so the error reads as before. Mario Kart 8 Deluxe 4.0.1's direct split on a PC: 374 s → 30 s, the full scan 544 s → 148 s; on the AYN Thor 8:09 → 16 s; outputs byte for byte the same. `build.gradle` refuses a stage without it. `dm/games/0100152000022000/README.md`: the note's line on it. `peanuts/UPSTREAM-EDITS.md` Round 31; test `test_split_yaz0.py`.
- 2026-11-22 (issue #120, the review round before dev 0.1.7): rebased onto dev 0.1.7. The phone's rewrite in the worker keeps #177's second pass (`pn_rewrite_worker`: `DM_IN_PROCESS=1`, a `rerun_env` run again in a new worker); the rewriter's equivalent is 0.1.7's file (`patch_nso.py.54b9ce16465e`); the kept copies go under the 4 GB cap when they are made, the split's results first to go (`shader_reuse.py room`); `PN_REUSE=0` writes nothing; the records hash their own rules' source; Settings › Storage deletes a game's build caches only while no build or full check holds their lock (`StorageScan.reuseLock`). `peanuts/UPSTREAM-EDITS.md` Round 33.
- 2026-11-23 (issue #120, igawa6's review of #150): a dropped full check takes the records of the kept shaders and the kept copy of the library with it (`CheckDue.drop`, `shader_reuse.py drop_check`), so the next build of the game translates every shader; a sample shader the new library lacks or cannot read fails the check; the check's own folder counts under the 4 GB cap, the check asks `room()` before it writes and is dropped without room, and a build keeps no shader whose check could not fit beside the game's copies; a Next Level Games archive's kept split result covers every file its reader opens; a kept or restored module's pack gets the `build_src.sha256` a rewrite writes now (`patch_nso.py`'s own lines, `rewrite_reuse.py write_build_src`); the shader record (format 3) names the translation options (`XLATE_DEBUG` / `XLATE_REDUCED`). No upstream file touched.
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
  up, also from a shortcut or a frontend; without one the second display is Android's, or the game's live stats (`dev.peanuts.ui.StatsView`) or cover with Settings › Library ›
  **Games without a companion** (`PeanutsPrefs.bottomScreenInGames`, `bottomScreenGamesPage`). Seen on the emulator (Android 13, a 1240x1080 and a 1920x1080 second display, hot-plugged both ways):
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
- 2026-11-21 (Tomodachi Life: Living the Dream's boot crash in the font engine, the open crash of PR #86): a `nn::fontll::ScalableFontEngine` is used by one
  thread at a time, as a Horizon core keeps it. NintendoWare Font keeps one engine per core and takes no lock; the job worker `AppJobThreadPool0` and the `Scene Prepare
  Thread`, both pinned to core 0, ran in engine 0 at once on the host, and one's `SetFont` cleared the font pointers the other was reading. A call never
  runs beside another now, and between two threads of one core the engine stays with its last user until that one blocks, unless the other has the higher
  priority. `dm/host/hle_fontll.c` (new), `hle_thread.c` (`dm_core_excl_release` at SVCs and native waits), `hle_sdk16.c` (Initialize, LoadFont);
  `t_peanuts_fontll` (`make GAME=<id> peanuts-fontll`). UPSTREAM-EDITS Round 52; `docs/reference/threads.md` "Two threads of one core in a font engine".
- 2026-11-21 (GitHub issue #329, Tomodachi Life: Living the Dream's time stood still): the time service's steady clock is the host's real-time clock, not the
  monotonic one, which on Android stops in deep sleep and starts again at 0 at every boot under the same source ID. Sleep came out as the user setting the
  clock forward, and the game shifted every timestamp in its save by it, so no time passed while the phone slept. One reading gives both clocks now; the
  user clock is that reading plus the custom time's offset, so a custom time is the only user change. New source ID `dmhost-steady-02`: a save from before
  warns once. `hle_time.c`; `t_peanuts_time` (`make GAME=<id> peanuts-time`). UPSTREAM-EDITS Round 51; `docs/reference/services.md` "The steady clock runs
  through sleep and restarts".
- 2026-11-21 (GitHub issues #262 and #20, the Mali black screen): on an Arm GPU the start-up notice for a missing placed memory map no longer says
  "Pick System": Arm's own driver has no placed maps, so it asks for a PanVK build that has them, and says when the picked driver did not load (Android's
  driver ran under its name). `dev.peanuts.ui.MaliDriver`; one hunk in `GameActivity.readinessDecide`. Settings › GPU driver lists faux123's PanVK
  (placed maps from v1.1.5 on G57 and G77, by its notes), and every PanVK file counts as a Mali build. FAQ "Can a Mali or other non-Adreno device use a
  custom driver?". Seen on the emulator (the driver page and faux123's package import); the Mali notice itself only in the JVM test, no Mali device here.
  UPSTREAM-EDITS Round 40.
- 2026-11-20 (GitHub issue #44, Import pack… before the first build): a game's sheet has **Import pack…** under Build while the game is not built
  (upstream's Import pack… is on a built game's Storage tab, so a fresh install had nowhere to import a pack made on a PC or exported from another
  phone). `dev.peanuts.pack.PackImport` runs upstream's own steps (`PackZip.titleOf`, `stage`, `swapIn`, `BuildCheck`) through reflection
  (`dev.peanuts.pack.Upstream`, no upstream file changed): the pack goes where the game's next Build puts its own (its Build destination, else
  `AppStorage.pack`), staged beside it. It must be the sheet's game; against a dump folder or `.nup` its build must be that file's, and the game then
  gets its list entry (ready to play); for an NSP or XCI not imported yet its version must be the one Build uses, and Build takes it
  (`dev.peanuts.build.Prepare` step 3). Refused with nothing written: another game's pack, another version or build, a running game of the title, an
  export or import on the Storage tab, another list entry on the same pack folder, an unfinished Prepare job of the game. The words are
  `dev.peanuts.pack.FirstImport`'s, the sheet's button and dialogs `dev.peanuts.ui.PackImportUi`; the sheet's Build waits for an import. The control
  interface's `import_pack` does the same with a path and never replaces a pack (`peanuts/docs/CONTROL.md`). Tests:
  `peanuts/tests/test_first_import.py` (the whole import on the JVM with upstream's PackZip, Pack and BuildCheck; the reflected names against
  upstream's sources; the hand-over: a `.nup` of the pack's build where Build writes it gets the imported pack from upstream's PackFind and
  BuildCheck, one of another build none). Emulator, fake packs only: a pack folder and a `.zip` into the sheet's NSP game and dump-folder game, the
  refusals for another version and another game, Export pack…, the app's data cleared, the `.zip` imported again before the first build: ready to
  play. AYN Thor (test app, a fake pack): the sheet's Import pack… through the system picker into the build folder, ready to play. AYN Thor
  (2026-11-21, test app with the real packer, a game built once, its pack exported and the build deleted): the `.zip` imported on the sheet of the
  NSP before the first build, the question before replacing it and the replacement, then Build: the `.nup` made and the pack taken as it was ("The
  pack beside the game fits: nothing to prepare", 13 s instead of 5.5 min, no shader translated again), and the game started with it.
- 2026-11-21 (Back keeps your place): Settings, Advanced and a game's page come back where they were. MainActivity builds a page anew for every
  change, in a new ScrollView without a view id, and saved only which page was open, so the page came back at its top with a controller's focus on
  its first row: Back from Advanced (over Settings or a game's page), a size change, and the activity made again. The last one is what Back from a
  sub-page in front of Settings (GPU driver, Readiness check, Graphics self-test, Companions, Miis) runs into when Android ends the launcher's process
  meanwhile; while the process lives, those pages keep the place (seen on the Thor with the controller and on the emulator). `dev.peanuts.ui.PagePlace`
  takes the scroll position and the focused row before such a rebuild (also into the saved state) and puts them back in the new page's first layout,
  before the page's own first-row focus. The row is found again by its words and which of the rows with those words it was, else by its place
  (`PlacePick`). A window made again starts in touch mode, showing no focus: the row is then the page's default focus, so the controller's first
  press lands on it, unless the page was scrolled since. Home keeps its own place (`Library`). Thor: Back from Advanced, and from the Readiness check
  with the launcher's process ended behind it, land on the same row at the same position. UPSTREAM-EDITS Round 41; test
  `peanuts/tests/test_page_place.py`.
- 2026-11-21 (the Share log chooser everywhere, UPSTREAM-EDITS Round 42): every place that shares a log asks between the game's newest run and its
  build when it has both, each with when it happened and how it ended (`dev.peanuts.ui.LogChoice`); with one log it is shared at once. The game page's
  two rows are one **Share log…**. When both logs in Settings › Share log are of one game, its name is the sheet's title and the lines carry only when
  and how. Emulator (test app, a run log, a failed build log and an exit record of Link's Awakening): Settings › Share log, the game page's Share log…
  and the "Game stopped" notice ask and share; the build card, the Prepare card and its menu are covered by `peanuts/tests/test_share_log_choice.py`.
- 2026-11-21 (the bottom screen's peanuts): a tap on the drifting peanuts of the launcher's page on the second display pops the one under it and counts it,
  as on the top screen. The page drew upstream's `BananaBackgroundView` under its pages, but no touch ever reached its `popAt`: the pages' scroll views
  took every touch and nothing looked for a tap on the background. `BottomPanel.dispatchTouchEvent` now looks at each touch without taking it; a press
  where no card, button, bar or dimming scrim is, that moves no more than the touch slop and lifts before a long press (`BackgroundTap`, the same rule as
  `MainActivity.bananaTaps`), pops the peanut there, with a light tick, and `BottomScreen.popped` counts it through MainActivity's own counter. The start
  page is not a background. The pop's look and the counter are upstream's, unchanged. UPSTREAM-EDITS Round 43; test
  `peanuts/tests/test_bottom_screen_peanuts.py`.
- 2026-11-21 (Settings in ten sections): Settings shows one of ten sections at a time, Library,
  Console, Graphics, Controls, Screens, Users, App, Help, Developer and About, instead of one long page whose Library card mixed folders, keys, haptics,
  both screens and Miis. A window 600 dp wide or more (the Thor's top screen, a phone held sideways) has the sections in a sidebar at the left, the
  selected one on banana yellow, and the rows at the right; a tablet (its smaller side 600 dp or more) opens Settings on the ten sections as tiles across
  the screen (an overview of the sections on a page of its own), a tile opens its section's page and Back goes to the tiles again; a narrow phone has the
  section's page with a button of three lines that opens a drawer of the sections. Every row keeps upstream's look and listener; its tile and each section
  show the Peanuts icons (`android/app/src/peanuts/res/drawable/pi_*.xml`, the kernel in the accent). Console's icon is a console with its two
  controllers; Screens' is two screens on a device with a second screen, one screen on any other. The rows of Screens and Console mode show what they
  are set to (`SettingsSections.BY_VALUE`): the second screen on, left to Android or showing the cover, where the game plays after a swap, a turning or a
  sideways phone, a sun or a moon, a TV or the console; the icon changes with the value. The section that shows is kept while the app runs. `nav to=settings section=` of the control interface picks a section. UPSTREAM-EDITS
  Round 44; test `peanuts/tests/test_settings_sections.py`.
- 2026-11-21 (Settings on the second display): while Settings shows, the launcher's page on the second display explains what the focus is on. A
  section's entry: the section, its line and its rows with their values now. A row: its icon, what it does (`dev.peanuts.ui.SettingsHelp`), its value now
  and its app default; a switch row shows Off | On there, and a tap flips the switch on the top screen. A row tapped on the top screen is explained too, and
  a value that changes updates the page in place. Leaving Settings gives the display back to its logo. No upstream hook: `SettingsPage` watches the focus
  of the launcher's window, `BottomScreen.explain` and `BottomPanel`'s explanation page draw it; a page may hand a view of its own instead
  (`BottomScreen.Explain.view`, for the Users section of package 3). `PUi.Segmented` is upstream's `Ui.Segmented`, copied.
  Test `peanuts/tests/test_settings_explain.py`.
- 2026-11-21 (one search over every setting): each section's page has a search button. Its sheet finds the rows of all ten sections by Advanced's
  rule (every word in the row's title, section, group, value or what it does; `dev.peanuts.ui.SettingsSearch`), the titles first, and below them
  Advanced's own rows that match (upstream's `AdvancedTable.visible`, reached by reflection in `AdvancedPort`). A row of Settings jumps to its section
  and place, with the focus on it or a short highlight; one of Advanced's opens Advanced with the words in its search field. No upstream hook. Test
  `peanuts/tests/test_settings_search.py`.
- 2026-11-21 (About and Peanuts cracked): Settings › About has a Segmented of Overview (the badge, name, version and build tag, what the app is, Check for
  updates, GitHub, Questions and answers, and Peanuts cracked: the app's own peanut cracked open, its halves opening and closing slowly, over the count),
  Games (the game catalog, `peanuts/games.json`, which `games_catalog.py` also writes as the raw resource `peanuts_games.json`: All, New in the version,
  With a companion), Credits (the makers, the contributors with davdxpx first) and Licenses (Built on, In this app, each opening its page). On the second
  display About shows the big peanut, the count and the next milestone. Peanuts cracked replaces "Bananas Popped": upstream's tap counter, all time, plus
  25 for a build of a game version not built before and 1 for every 1,000 shaders it prepared (Import again of that version adds nothing), 5 for every hour
  played (a run's time from its start to its process's end, at most 6 h), 10 for a game's first start and 5 for the first own Mii (`dev.peanuts.ui.Cracked`,
  `CrackedCount`). A tapped background peanut cracks open (`Crack`) instead of bursting. `PrepareCover`'s peanut is `PeanutArt` now, shared by all three.
  UPSTREAM-EDITS Round 45; test `peanuts/tests/test_peanuts_cracked.py`.
- 2026-11-22 (the review of PR #336): the steady and the user clock read the real-time clock once a run and advance it with `CLOCK_BOOTTIME`, so a correction of
  the phone's clock (network or carrier time) can no longer make a span within a run negative; it reaches the game at its next start. `hle_time.c` `host_rt_ns`;
  `t_peanuts_time` case 8. UPSTREAM-EDITS Round 54.
- 2026-11-22 (igawa6's notes on the Settings pull requests #312 and #315, for after 0.1.7): Share log reads what it asks about off the main
  thread. A tap read the launcher's list, the game's work folders, `job.json` (up to 1 MB), a log's tail and `last-exit.txt`, and asked Android for the
  running processes, all before the sheet showed, which on an SD card or a USB drive can stall the screen. `BuildLogUi` now reads all of that on a worker
  (`dev.peanuts.ui.OffMain`) and opens the sheet with what it found; a second tap meanwhile starts nothing, and a screen that went meanwhile gets nothing.
  Tests `peanuts/tests/test_share_log_choice.py` (OffMainJvmTest, and no read left in the tap's own code).
  Peanuts cracked's play time: at every Play, `GameReturn` → `Cracked.starting` read `last-exit.txt` and asked Android for the running processes and its
  exit records on the main thread. That now runs on Cracked's own worker, in order with the settles after it; the Play's time is still taken at the tap.
  Which run counts is `CrackedCount.toCount` now (plain Java), so a record the next game's screen wrote before the worker read it counts nothing until its
  own start is noted. Tests `peanuts/tests/test_peanuts_cracked.py` (the rule on the JVM, nothing read at the Play's tap).
  `SettingsPage` kept the activity, its rows, the section pages, the drawer and the search sheet in static fields after the activity was destroyed, until
  the next `lay()`: the activity's own lifecycle callback (`Gone`, registered once per activity) now lets go of all of them; the section that shows is
  kept for the activity made again. Test `peanuts/tests/test_settings_sections.py` (every such field is let go).
  On a tablet, Back from Advanced landed on the tiles of the sections instead of the section it was opened from: Advanced replaces Settings' column, and
  its detaching was taken for Settings closing. Now only Back from a section's page and Home's build (`SettingsPage.homeBuilt`, from
  `BottomScreen.home`) put a tablet's Settings on its tiles. Test `peanuts/tests/test_settings_sections.py`.
  The icons' generator is in the repository: the `pi_*.xml` files said they came from an `icons.py` that was not there. `peanuts/scripts/icons.py` holds
  all 41 icons (shell, kernel and ink dots, the same paths byte for byte) and writes them; `--check` says which file differs. Test `peanuts/tests/test_icons.py`.
  Comments and notes that pointed at a board or page of the owner's design canvas say what they mean in words; readers of the repository cannot see
  the canvas. Test `peanuts/tests/test_no_canvas_pointers.py` keeps such pointers out of Peanuts' code and notes.
  About › Licenses names Banana Native and Frame generation GPL-3.0-or-later, as the README, `android/NOTICE.md` and the LSFG folder's LICENSE.md do
  (it said GPL-3.0). Test `peanuts/tests/test_peanuts_cracked.py`.
  Peanuts cracked takes its hours from the play-time ledger now (`dev.peanuts.launch.PlayTime`, `files/peanuts-playtime.tsv`, which came in 0.1.7):
  what the ledger's total gained since the last look counts (`CrackedCount.fromLedger`), so a game's time counts only while it runs, and never while Build
  prepares its shaders. 0.1.7 measured each run itself, from `last-exit.txt` to the process's end in Android's exit records; that is gone, and with it
  the file read and the binder calls. On the first look after an update from 0.1.7 the ledger so far counts as counted (`seenAtFirstLook`), so no hour
  counts twice. Tests `peanuts/tests/test_peanuts_cracked.py` (the rule on the JVM, the wiring).
- 2026-11-24 (the overlay's pill): Overlay › Style is Table (the default), Pill or Classic. The pill (`Hud.pill`) is one line, plain: white values, grey units,
  warm light icons (`IconSpan`, drawn in code) on its own background (`PillBg`: a dark peanut brown with a top sheen, a thin
  rim in the shell's #D9A066 and a soft inner glow); yellow and red only mark a problem (the fps number in the frame time's colour, TEMP, BAT and RAM
  by their levels), and the pill's bars on pace are light grey (`HistogramSpan(chars, true)`, the host's `pn_graph_plain` through `Bridge.graphPlain`; the table keeps green, igawa6 landing #538). Its histogram is 6 chars of the newest 20 frames (the
  host splits the box into as many columns, `Bridge.graphBars` / `pn_graph_bars`; the table keeps 48). Its 100 % is 1.5 times the overlay's 8 sp (`PILL_BASE`; Table and Classic keep 8 sp). A pill wider than 75 % of the game's window is made
  smaller from the next tick (`Hud.fitTo`, from the width the view laid out against the GameActivity window's; a `[overlay] pill fit` log line when it changes):
  no cut at the edge, room for the build tag. Everything is one size; the fps number is bold, and a thin line (`IconSpan.SEP`)
  splits the frames' group (graph, 1%L) from the phone's (temperature, battery, RAM), whose values are a dimmer white. Presets (`HudSettings.pillItems`): Minimal the fps; Normal the
  fps, the graph and the temperature; Full adds the throttle dot, 1%L, the battery and RAM; Custom the item switches (1%L with the graph). TEMP, BAT and RAM also show
  when they turn yellow or red (BAT and RAM only while their switch is on: `Overlay.tick` reads nothing else). Before the player picks one, the pill uses Normal and
  sits at the top centre; Table and Classic keep Full and the top right. Position has six places (`Hud.CORNERS` / `CORNER_IDS`, the host's `VKO_TOP_CENTER` and
  `VKO_BOTTOM_CENTER`; `pn_graph_corner` accepts 4 and 6, a bottom place is bit 2). The pill floats 6 dp off the edges with round ends, and a centred or left block
  takes the corner's side margin on the right side (`Hud.margins`, one hook in `Overlay.Block.draw`). Players who never picked a style keep the table (igawa6, landing #538: the pill is a choice, not the default). The build tag is off by default under the pill (`Hud.tagOn`,
  used by `GameActivity` in two marked lines; a player's own switch wins, and only the Full preset turns it on there).
  Tests: `t_android_ovl`, `t_peanuts_graph`, `dm/tools/tests/test_android_hud.py` (`test_pill`).
- 2026-11-26 (Switch 1 only, owner): the 19 Switch 2 profiles (`dm/games/04*`) and the host gates only they used (`dkb_wide.c`, `mkw_wide.c`,
  `oot_wide.c`, `mtf_size.c`, `pkp_aspect.c`, their tests and `OBS_*` rows) are removed; `nxGames` (the default APK's games) is
  `peanuts/release-titles.txt` with Breath of the Wild first. `upstream-update.sh` keeps them out on every sync (`peanuts/upstream-dropped.txt`,
  [UPSTREAM.md](peanuts/docs/UPSTREAM.md#38-dropped-upstream-content-switch-2)). Tests that read those profiles use Switch 1 profiles or
  `peanuts/tests/fixtures/profiles/`. The shared Switch 2 support in the host and the app (NVN2, Program-n, `Edition.java`) stays.
- 2026-11-24 (GitHub issue #561, a damaged game file at the import): the importer checks every plain NCA against its own hash layers as it writes the `.nup`
  (`dm/peanuts/import/nx_verify.c`: the fs header's SHA-256, a RomFS's IVFC levels, an ExeFS's hash table, each data block as it is read), its size against
  the CNMT and its header, and a container that ends early. A damaged or cut-off file stops the import with its name and role ("the update v393216"), and the
  card says so without Retry (`dev.peanuts.build.Compressed.failure`, beside the cut-off file's words of #697, `FILE_DAMAGED`). A `.nup` imported before this check: the shader split's FlatBuffers readers
  check their offsets, and an index outside its file ends the split with "the game file is damaged" (`PrepareText` `CAUSE_DAMAGED`, no Retry). Tests:
  `dm/peanuts/import/tests/verify_test.c`, `peanuts/tests/test_damaged_game_files.py`, `PrepareTextJvmTest`. `peanuts/UPSTREAM-EDITS.md` Round 64.
  On an AYN Thor (2026-11-25): Z-A base + 2.0.2 + DLC imports in about a second more (11.6/12.7 s against 10.9/11.7 s, the same `.nup`), and a copy of
  2.0.2 with one byte changed stops with its name, where upstream main wrote a `.nup` from it. Numbers: `dm/peanuts/import/HOOKS.md`.
  2026-11-28: a compressed RomFS's hash levels are sized for its decompressed data, the hashes past its stored data zeros; only a level's blocks that hold
  hashes in use are checked now (a good Echoes of Wisdom base was refused as damaged at level 5). Round 64's follow-up.
