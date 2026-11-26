# Mario + Rabbids Kingdom Battle (`010067300059A000`)

Update 1.9.589692 (v589824) over the supplied base game, SDK 4.4.0, Ubisoft's Snowdrop engine on NVN, with Havok and Wwise. The loaded modules are `main`, `subsdk0` (GLSLC) and `sdk`. The supplied DLC packages are indices 1–4. Brief Mac gameplay testing passed; iOS is unvalidated.

## Memory

- `nninitStartup` sets a 100 MB heap. The NPDM declares 16 MB of system resource, and Snowdrop commits its pages through virtual address memory. The profile keeps the 5 GB guest block, with a 4.375 GB alias region and 256 MB stack region.
- **Resolved: startup null write at `main+0xea25b8`.** [Separate virtual ranges in Snowdrop's page allocator](../../../docs/reference/memory.md#separate-virtual-ranges-in-snowdrops-page-allocator). The allocator constructor (`+0xea217c`) reserves three adjacent 4 GB ranges and does not check reservation failure. The profile's build-gated platform cuts reduce the combined reservation to 3 GB: 2 GB for large allocations, 512 MB for 512 KB pages and 512 MB for 64 KB pages. The constructor's size fields, both combined-size uses and the large allocator's independent maximum page count (`+0xea21f4`, 32768 pages) change together. Its fixed 1 MB large-allocation metadata reserve remains sufficient. The reserve wrapper (`+0xf59fc8`) discards incoming x1/x2, allowing the large-size field override before the call.
- **Resolved: SDK virtual-range overhead.** [The SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area) and [large-region alignment](../../../docs/reference/memory.md#1-gb-alignment-of-regions-of-1-gb-and-more). SDK build `9de92bad67c4150268943ab4843ace65` has the older VammManager constructor (`sdk+0x93dd8`): a full 1 GB node area, with its limit at `+0x93dfc` and occupied length at `+0x93e8c`. Both become 64 MB; the large-region alignment at `+0x93fe0` becomes 32 MB. These are platform-limit cuts in `sdk_patches.py`.
- **Open:** later pool capacity and the original slab-growth fallbacks need sustained gameplay validation.

## Files and shaders

- The merged RomFS contains 4,004 files, about 2.82 GB, under `moria/sdf` and `moria/sdfconfig`. Shader objects are in Snowdrop's BERG data slices.
- **Resolved: no shaders found.** [Shader records in Snowdrop SDF data slices](../../../docs/reference/shaders.md#shader-records-in-snowdrop-sdf-data-slices). This game uses the zlib record codec; 151 cache records are themselves wrapped in SDF zstd chunks. The section layout matches Sparks of Hope's zstd records. The nested records add 151 stages, including the two fragment programs first missed at the language screen. The controls are 0x838 bytes, and compute code has no code-area header.
- The supplied update and DLC union has 11,625 stages: 10,637 vertex, 734 fragment and 254 compute. Vertex programs use the single-stage cover pairing.
- **Resolved: empty runtime capture stopped packing.** [A GLSL language preamble is not a complete shader](../../../docs/reference/shaders.md#a-glsl-language-preamble-is-not-a-complete-shader). The only `#version` string is a 646-byte header with type macros and no entry point. The initial 30-second run reached its frame loop without calling GLSLC.
- **Resolved: GLSLC rewrite assertion at `subsdk0+0x7340b8`.** [Index copied before the bounds check](../../../docs/reference/rewriter.md#index-copied-before-the-bounds-check). The copy is 71 instructions before the dispatch, across two switches preserving the index and checked register.

## Mac testing

- **Resolved: first-level pipeline compiler abort.** [An unwritten secondary blend source](../../../docs/reference/rendering.md#an-unwritten-secondary-blend-source). Fragment `fd260ce50f4a5625` writes only colour 0, while the draw uses SRC_ALPHA / SRC1_ALPHA for RGB and ZERO / SRC1_ALPHA for alpha. Metal 4's blend specialization crashed in the compiler service; the MTLDevice path reported the missing secondary output. The host folds factors for an absent secondary source to its zero choice. Matching the original GPU's missing-output value remains open in the reference entry.

Game files, logs, saves and binary analysis stay beside the NSPs in `010067300059A000_build` and `010067300059A000-work`. The build script's Mac command supplies the merged RomFS image, all four DLC roots and the shader cache. Use a fresh `DM_CTL` file for each run.

Tested on 2026-10-28 with the registered update, all four DLC images and all 11,625 stamped Mac shader libraries. Language selection, title and campaign menus, File 1 creation, the opening movie, the World 1-1 introduction and pause/resume work. A one-second left-stick movement moved the party along the path and collected two coins. The final run reported no missing shaders or failed pipelines. Testing stopped after this brief exploration check, as requested; battles and sustained play remain untested. Audio was disabled in the final control check.

From a saved File 1, A passes the title, Main Story and file selection. Y skips an already-seen cutscene. Restart Chapter from the pause menu marks the chapter introduction as seen and lets Y skip its replay. Use A for the Ziggy card and Beep-0 dialogue, then the left stick to move the party. The profile's `pso_manifest.tsv` records the menus, introduction and first exploration pipelines.

## Frame rate

- The game's maximum frame rate is "no limit" (`FLT_MAX`), which its present code maps to present interval 2: a fixed 30 fps. Its own `-fpslimit N` argument (read from `GetHostArgc`/`GetHostArgv`, parsed at `main+0xe8860c`) sets N as the maximum; above 30 it presents at interval 1 and paces itself with its CPU timer. Game speed is time-based. <!-- PEANUTS: MRKB-PN FPS limit; knowledge from the old nx2android project (60 fps and 55/60/72 fps speed probes), not yet checked on this host's device -->
- The profile passes `game_args -fpslimit 60` (never `-nofpslimit`, which selects interval 0) and sets `fps_limit 30`: the host's frame limiter holds the rate (the launcher's Frame rate cap: 60 / 45 / 40 / 30 / 20, live from the in-game panel), and the cap's default is the game's own 30. 60 is the game's own timer at 60. Since the shared patch v5 (R28) the Frame rate is a slider with no "Game owned" box (a stored Game owned is dropped), its default is 30 fps and the launcher always passes `DM_FPS_LIMIT` (30 by default); the `-fpslimit 60` argument is unchanged, so the game paces itself at 60 and the host limiter holds the chosen rate (30 by default).

## Graphics settings (Android)

<!-- PEANUTS begin: MRKB-PN graphics settings (shared words path) -->
- The Android launcher's Output resolution, Internal resolution, Upscaler and Graphics quality rows work through four profile keys, not through the game's own command line. `window_words` rewrites the window size the game builds in `main` (`main+0x10985a0` / `+0x10985a4` height, `+0x10985ac` / `+0x10985b0` width; the width at `+0x10985ac` is `orr w9,wzr,#0x780`, which the loader replaces with a `movz`; the `x8` second size at `+0x10985e4` / `+0x10985e8` is left alone). `view_scale_words` writes the 3D scale, the rodata float32 5/6 at `main+0x26e8268`, from `view_scale_steps` (40 to 100 % in steps of 10, each word chosen so the size is exact 16:9 with a height divisible by 4 at 720p and 1080p). `upscale_pass` names the composite pass (vertex `4923bfcc58031364`, pixel `e4aa9dfe594abeec`, the 3D picture at binding 257) that the FSR 1 / SGSR 1 upscaler replaces.
- The Internal resolution slider is a percent of the Output size and replaces the 5/6 literal; "Game default" keeps 5/6 (1600x900 at 1080p, 1066x600 at 720p); 100 % is the Output size. The 3D size is `W = 2*floor(Ww/2*s)`, `H = 3*floor(Wh/3*s)` (device-confirmed on four points in the R6 probe).
- `output_default 1080p`: the launcher's Output resolution when the player has stored none (Native on a 720p handheld would lower the game's own 1600x900 picture); Reset to defaults returns to it. Graphics quality defaults to Docked (no `op_mode handheld` row).
- Not checked on a device: the four rows together, the f32 rodata site written in `map_image`, the `orr` replacement through the loader, the upscaler on this game's composite pass. The seven other composite-like pixel shaders of the library are not covered by `upscale_pass`.
<!-- PEANUTS end -->

<!-- PEANUTS begin: MRKB-PN depth of field switch -->
## Depth of field (Android)

- The launcher's Performance tab (Picture) and the in-game panel's Performance tab offer **Depth of field**, a plain switch (title and state only, no helper text; in the panel the note "Applies on next start"), **on by default**: on is the game unchanged, nothing is written. Off applies at the next start. It is an `option` row (`DM_KB_DOF_OFF`, `restart perf inverted`) with one `option_patch` word, `main+0xa55938` `ldrb w8,[x8]` (`08014039`) becoming `mov w8,#1` (`28008052`). `inverted`: the stored per-game option keeps its meaning (`DM_KB_DOF_OFF` stored on = the word is written, as in the first APK that had the option); the switch shows the opposite, so a stored value never changes what the game does, only how the switch reads.
- The word is the load of the game's graphics-preset flag byte `main+0x3285585` (the apply callback `main+0xd2af20` stores `dof == 0` there; the GOT slot `0x3190b20` is loaded only by that callback and by this site). The wrapper at `main+0xa55904` is the only caller of the separable-bokeh renderer (`main+0xa573b4`) and returns at `main+0xa55944` (`cbnz w8, epilogue`) when the flag is set, as the shipped preset `RKB LOW` (`dof = 0`) does. With the option on the game takes that exit every time, so no depth-of-field pass runs (cutscene camera DoF and environment DoF alike). The reflection path's call goes through the same wrapper. No shader or host change.
- Checked on the PC against the dump (main `3b39e0c0...`): the word is as stated, no branch or literal load targets it, no `main.{changed,sites,gates,cuts,fixups}.tsv` row lies within 0x40. Not checked on a device.
<!-- PEANUTS end -->

<!-- PEANUTS begin: MRKB-PN alias sync -->
## Ambient-occlusion stripes (Android)

- **`alias_sync on`** (Android, [`dm/games/README.md`](../README.md)): the ambient-occlusion chain of the lit pass packs 4 R8 texels in one RGBA8 texel (a 640x360 R8 and a 160x360 RGBA8 texture over one storage, 640-byte rows). The replay makes one image for each shape, so the blur's RGBA8 output never reached the R8 texture the lit pass reads: it sampled the raw, unblurred occlusion and cliff and brick faces showed dashes with an 8 window-pixel period, whatever the Internal resolution, upscaler or depth-of-field setting. With the copy (`vka_tex.c` `alias sync`, both directions per frame) the stripes collapse on the Retroid Pocket Nova (Turnip T30, APK L, `DM_VK_ALIAS_SYNC=1`, R16): amplitude at the cliff column 4.01 to 0.53 and at the lower cliff face 2.87 to 0.25, lighting unchanged, 60 fps, GPU busy +0.1 ms, no new artifact. The log says `alias sync: on (profile)`; `DM_VK_ALIAS_SYNC=0` is the A/B. Same engine and same chain as Sparks of Hope, which has the key too.
<!-- PEANUTS end -->

## iOS build

The NSP script produces the unsigned `MarioRabbidsKingdomBattle.ipa` (about 5 GB), with the update, all four supplied DLC images and 14,143 iOS Metal libraries (11,625 stages plus variants). It has not been installed or tested on an iOS device.

## Found on this game

- [An unwritten secondary blend source](../../../docs/reference/rendering.md#an-unwritten-secondary-blend-source).
- [Index copied before the bounds check](../../../docs/reference/rewriter.md#index-copied-before-the-bounds-check).
- [Separate virtual ranges in Snowdrop's page allocator](../../../docs/reference/memory.md#separate-virtual-ranges-in-snowdrops-page-allocator).
- [The SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area).
- [1 GB alignment of regions of 1 GB and more](../../../docs/reference/memory.md#1-gb-alignment-of-regions-of-1-gb-and-more).
- [Shader records in Snowdrop SDF data slices](../../../docs/reference/shaders.md#shader-records-in-snowdrop-sdf-data-slices).
- [A GLSL language preamble is not a complete shader](../../../docs/reference/shaders.md#a-glsl-language-preamble-is-not-a-complete-shader).
- [Storage texel buffers at an unaligned offset (Android)](../../../docs/reference/rendering.md#storage-texel-buffers-at-an-unaligned-offset-android): profile key `tbuf_store_copy` <!-- PEANUTS: KB-38, a host patch of this fork; not yet checked on a device -->.

## Open

- Check later battles, the Donkey Kong DLC and sustained memory use.
- iOS testing is performed by the user.
