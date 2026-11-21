<!-- PEANUTS: Android profile and validation for Captain Tsubasa 2: World Fighters. -->
# Captain Tsubasa 2: World Fighters (`01009720213B0000`)

Update 1.0.6 (`v327680`, displayed as 1.06 on the title screen), SDK 18.3.1, Unreal with Wwise on NVN. The registered main build is `63ad5f65431ae17b1bb2304fd3abc521`. It loads `main`, `subsdk0` (NintendoSDK movie) and `sdk`.

## Android bring-up

- **AYN Thor, Turnip v26.2.0 R5.** Loaded a test save, entered Offline Match and played Japan vs. Sao Paulo Youth for 24 seconds without a crash. The runtime measured 52.8–59.6 fps in five-second match samples. The final log had no skipped draws for dynamic texture handles.
- **AYN Thor, Qualcomm system driver 676.53.** Both starts stopped before the menu. The copied-save run raised SIGSEGV at `main+0x118ca8c`, dereferencing `0x110`. The fresh-save run raised SIGBUS at `main+0x118d380`, with a misaligned read at `0x100000000e`. A cold-cache recheck also raised SIGBUS at `main+0x118d380`. The underlying cause remains open.
- The launcher linked all five add-ons in the pack. The host reported add-on indices 4 through 8.

## Shaders

- With an empty Vulkan cache on Thor and Turnip v26.2.0 R5, “Preparing shaders” processed the bundled 603-key seed before the game started. It built 555 pipelines in 30.5 seconds and skipped 48 keys for the other vertex-input mode. The game reached the autosave prompt at 59.8 fps. The next launch reused the saved cache and skipped the progress screen. [Cold startup](../../../docs/reference/rendering.md#a-draw-skipped-for-its-pipeline-left-a-result-the-game-kept-android)
- The updated RomFS has two shader libraries in the version 8 IoStore container beside `CaptainTsubasa2WF-Switch.pak`. Their 22,039 decoded LSLG records give 21,903 unique shader keys (6,577 compute, 4 geometry, 13,557 pixel and 1,765 vertex).
- The PC split uses the IoStore reader and Oodle worker. The standard decoder produced the same archive bytes as direct NSP extraction, and the full RomFS scan produced the same shader map. All 38,276 SPIR-V modules, including variants, passed Vulkan 1.3 validation.
- Vertex shader `d6b9a12aff78aa90` selects its buffer texture from c2 offsets `0x178` and `0x180`, with the sampler at `0x570`. The profile enables `tex_dyn_slots` so these particle draws can use the existing runtime binding path. [Texture slots picked at run time](../../../docs/reference/shaders.md#texture-slots-picked-at-run-time)

## Memory

- The Android profile uses a 3.75 GiB guest reservation with the shared allocator rewrite. The NPDM declares 20 MiB of system resource.
- The native allocator constructor requests 38 GiB. In this build it runs at `main+0x7a360`, called by `nninitStartup` at `main+0x1f7180`. The relocated primary vtable at `main+0x7b7cf38` names FMallocBinned3 through its Itanium RTTI and has the Unreal 5.6 slot layout.

## Found on this game

- **Resolved** The rewriter recognizes the relocated allocator RTTI, and the host retains the prefix when it installs the replacement. [Unreal allocator replacement](../../../docs/reference/memory.md#unreals-fmallocbinned3-replaced-by-the-host-allocator)
- **Resolved** The SDK's retail environment query aborted at `sdk+0x3aefa8` before the title sequence. The host now returns the retail environment enum. [Offline NSD environment query](../../../docs/reference/services.md#nsd-application-server-environment-query-aborts-during-startup)

## Open

- Full matches, online modes and the individual DLC bonuses are untested.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on 1.0.6 through 1 `aspect_plan` row that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
