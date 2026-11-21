<!-- PEANUTS: Android profile and validation for Demon Slayer: The Hinokami Chronicles 2. -->
# Demon Slayer -Kimetsu no Yaiba- The Hinokami Chronicles 2

The base title ID is `0100AD80208A8000`. The Android profile targets update 1.80 (`v917504`), main build `dadf52c97d951fbcc95f1a7468a7fd3e`. The game uses Unreal Engine 5.3.2 with NVN and Nintendo SDK 16.2.3. It loads `main`, `subsdk0` (NintendoSDK movie), and `sdk`.

## Memory

The NPDM declares 16 MiB of system resource. The host's existing Unreal allocator rewrite recognizes this build's FMallocBinned3 constructor and its slot-22 vtable. The Android rewriter splits the patch area around `main` to reach both ends of its 128 MiB text segment.

## Shaders

The game keeps its global shaders in the Unreal pak and material shaders inline in the version 5 IoStore export bundles under `APK2/Content/Paks`. The PC split reads those bundles with the existing Oodle worker.

The full PC split found 128,449 shader keys, including 2,843 from the global shader cache. [Unreal pak classification](../../../docs/reference/shaders.md#a-movie-pak-is-mistaken-for-an-unreal-shader-archive)

The Android build bundles 708 Vulkan pipeline keys recorded on Thor. On an empty cache with Turnip R5, “Preparing shaders” built 572 pipelines in 29.4 seconds before entering the game. The other 136 keys use the static vertex-input mode and were kept for drivers that use it. The next launch reused the saved cache and skipped the progress screen. [Cold startup](../../../docs/reference/rendering.md#a-draw-skipped-for-its-pipeline-left-a-result-the-game-kept-android)

## Startup rewrite

On the first Thor run, the guest faulted on its TLS load at `main+0x23f5fb8`. The preceding `mrs x18, tpidr_el0` at `main+0x23f5ec0` left the guest's saved register stale. [TLS register rewrite](../../../docs/reference/rewriter.md#a-tls-access-uses-the-guest-x18-register)

## Frame rate caps

Game settings and the in-game Performance panel offer 20, 30, 40, 45, 60, 90 and 120 fps. The default is 30 fps. Game owned restores the game's timing after restart.

For 1.80, the exact-build plan switches Unreal to variable-rate updates, sets `t.MaxFPS`, and disables engine VSync. The gravity helper uses the current Unreal frame delta with a 30 fps baseline, and two movement calculations use the variable-step input. The loader checks all eight original instruction words before applying the plan. [FPSLocker Warehouse's 1.81 timing patch](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/SaltySD/plugins/FPSLocker/patches/0100AD80208A8000/68E3A0EA176A79F4.yaml)

On Thor with Turnip R5, the rooftop tutorial ran at about 20 and 59–60 rendered fps with the respective caps. With the 120 fps cap, sampled render rates reached about 90 fps. Thor's panel stayed at 60 Hz during this run, and 120 fps gameplay was unverified.

## Android verification

- **AYN Thor, Turnip v26.2.0 R5.** Created a fresh save, reached the 1.80 title and played the rooftop combat tutorial. Chase dash, light attack, skill and guard responded. Five-second game-frame samples during the input check were 29.6–29.9 fps.
- **AYN Thor, Qualcomm 676.53.** Reached the 1.80 title and rendered the rooftop scene. The driver then raised SIGSEGV inside `vulkan.adreno.so+0x1ed124`. The dev app uses Turnip for play.
- The launcher mounted all 12 add-ons, and the game displayed entitlement notices, including the Battle Attire pack. Later chapters, online modes and individual DLC characters were not played.
- The PC translated all 128,449 shader keys. All 178,561 Vulkan modules passed SPIR-V validation.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on 1.80 through 1 `aspect_plan` row that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
