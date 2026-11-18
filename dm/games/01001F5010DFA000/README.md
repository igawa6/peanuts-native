# Pokémon Legends: Arceus (`01001F5010DFA000`)

Update 1.1.1 (`v262144`) over the base game, SDK 11.4.0. Game Freak's engine between Pokémon Sword's ([0100ABF008968000](../0100ABF008968000/README.md)) and the Trinity engine of Pokémon Scarlet and Legends: Z-A, on NVN, with Havok physics, Wwise sound and Pia / NPLN networking. Modules `main`, `subsdk0` (the movie module, which the profile does not load: `main` takes nothing from it the host does not supply), `subsdk1` (GLSLC) and `sdk`; the `sdk` build (`b39add63…`) is Kirby and the Forgotten Land's ([01004D300C5AE000](../01004D300C5AE000/README.md)). Started from the provisional profile the NSP script wrote from its survey (`dm/games/profile_guess.py`). Plays on the Mac at 30 fps, as on the Switch, from the first boot to free movement on the Obsidian Fieldlands beach (a short check, below). An unsigned IPA is built (4.2 GB); iOS is untested.

## What it needs

| Setting | Where | Why |
|---|---|---|
| 5 GB block: `mem_reserve 0x140000000`, `mem_alias 0x118000000` (4.38 GB), `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | the NPDM declares 16 MB of system resource (virtual address memory); `nninitStartup` sets a 0.02 GB heap and initializes malloc's StandardAllocator over 32 GB of address space (`main+0x2779d8c`), which the host gives 4.06 GB ([StandardAllocators over 12 to 62 GB of address space](../../../docs/reference/memory.md#standardallocators-over-12-to-62-gb-of-address-space)) |
| Node area cut and 2 MB alignment of large regions (SDK 20's) | `dm/tools/sdk_patches.py` SDK-wide rules, `sdk` build `b39add63…` (Kirby's) | without the alignment the 4.06 GB region goes to the next 1 GB boundary past the node area and does not fit ([1 GB alignment of regions of 1 GB and more](../../../docs/reference/memory.md#1-gb-alignment-of-regions-of-1-gb-and-more)) |
| `dual_want.tsv` | profile | two post-effect pixel shaders are blended with their output 1 as the second source ([A written secondary blend source](../../../docs/reference/rendering.md#a-written-secondary-blend-source-nvn-dual-source-blending)) |
| `depth_mode zero` | `game.tsv` | the game sets the near=0 depth mode, so the pipeline bases made before it runs take the `main0_z` vertex entries |
| `pad_layout full` | `game.tsv` | the 3D gamepad layout: the camera is on the right stick |

No `patches.py`, and no host code keyed to this game.

## Bring-up

- **Resolved: a `Break` in `nninitStartup`** (`Break#1 reason=0` at `sdk+0x39fb54`, from `StandardAllocator::Initialize` at `sdk+0x2f98e4` ← `main+0x2779d8c`). The host's cap lowered the allocator's 32 GB to 4.06 GB, but this `sdk` build aligns a region of 1 GB or more to 1 GB, so the region would have started at alias +1 GB and ended past the 4.38 GB alias region. The SDK-wide node-area patch matched this build (implementation 3), the alignment patch had no implementation for SDK 11.4: its placement helper (`sdk+0x34e1d8`) is the SDK 12.3.9 one with 8 more bytes. It is now the alignment patch's second implementation, and both cuts are registered for this build: a build a profile was brought up on takes only registered cuts, so with this profile in `dm/games` the SDK-wide rules no longer reached it (the `Break` came back once). The region lands at alias +0.06 GB.
- **Resolved: an abort on Metal's compiler queue** a few seconds into the language screen (`[AGX] Failed assertion "error == MTLCompilerErrorNoError"` in `findOrCreateComputeFastClearPipeline`, `MTLCompilerService` crash reports in `AGCLLVMStatelessFragmentObject::buildBlendFactor`), with or without the pipeline prebuild. The pipeline was `ha_post_effects.bnsh` variation 4 (`f4f36a03b0c6691d`): an RGBA16Float target blended with destination factor `SRC1_ALPHA`, the shader writing outputs 0 and 1. A temporary log of each blended attachment in `pso_desc` named it as the last pipeline before the abort. `DM_NVN_DUAL_WANT` then listed a second one, `ff45da02e225f961`, in the first minute.
- `[RED] no render target could get an MTLTexture` once at boot ([Passes with no render target](../../../docs/reference/rendering.md#passes-with-no-render-target)); no other red line on the route below.
- The NSP script's rewrite step was reused after the alignment rule was added: it keyed only the patch files that name one of the modules' builds ([The packing script in detail](../../../docs/development.md#the-packing-script-in-detail)).

## Performance and memory

- On the Mac (M6): 30 fps as on the Switch (`power` lines: 30.0 fps, CPU 0.64 W, about 20% of a core), with long frames of 100–260 ms where new pipelines are first built (that run's archive was made for the near=−w depth mode, before `depth_mode zero`; the Mac build from this profile makes the near=0 bases, not measured on the route since). Resident memory 2.7 GB and 1.46 GB of Metal allocations on the beach after 7.5 minutes.

## Files and shaders

- RomFS 6.0 GB, 19,101 files: effects (`bin/effect`, 2.5 GB), 678 GFPAK archives under `bin/archive` (1.6 GB, every entry Oodle Kraken: [Oodle entries of GFPAK archives](../../../docs/reference/shaders.md#oodle-entries-of-gfpak-archives)), Wwise banks (`bin/sound/NX64/wwise/bank/Default.pck` is the `romfs_marker`), message tables, compiled scripts (`bin/haxe`, `.blua`), and Trinity-style model files (`.trmdl`, `.trmsh`, `.trmtr`). The game probes `arc/data.trpfd` and `arc/data.trfmd` (Trinity's pack index), which it does not ship.
- Shaders: 12,851, by where each first appears: 11,352 in the effects' particle files (`.ptcl`), 1,386 in the loose BNSH files of `system_resource/gfx2/shader` (materials, terrain, water, grass, lighting, shadows, post effects), 73 in the GFPAKs, 40 in `main`. 6,156 pixel shaders, 5,474 of them write exactly colours 0 and 1.

## Mac routes

- The work folder's `run/boot.sh <log> [secs] VAR=value…` runs `build/plahost` (a renamed copy of the host) on the NSP script's build, RomFS image and shader libraries; `run/ctl.sh` and `run/step.sh <shot> [wait] <presses…>` are Sword's (presses through `DM_CTL`, screenshots of the next frame). Run headless.
- From a fresh save (`DM_KBD=<name>` answers the name): A at the language screen (about 70 s after start with the archive opening and the first shaders), A presses through Arceus's questions and the name, the fall through the rift, waking on the beach, Professor Laventon and the three Pokémon, then the player walks (`LUp`, `LRight`).
- The route checked on the Mac: language, name, the opening, the beach with Laventon, Cyndaquil, Rowlet and Oshawott, and a walk through the grass toward the first marker. The game had not saved yet, so there is no reference save.

## Found on this game

General changes first needed here; each applies to every game:

- **The node area and 32 MB region alignment cuts of `sdk` `b39add63…`, and the SDK 11.4.0 implementation of the SDK-wide alignment patch** (`sdk_patches.py` `MATCH_CUTS`; the build's row was removed on 2026-10-29, as the rules give the same cuts): [1 GB alignment of regions of 1 GB and more](../../../docs/reference/memory.md#1-gb-alignment-of-regions-of-1-gb-and-more).
- **Dual-source blending for NVN shaders** (`dmsh_build.py` `fs_dual`, `main0_d`, the profile's `dual_want.tsv`, `DM_NVN_DUAL_WANT`; `pso_desc` `pso_reads_src1`): [A written secondary blend source (NVN dual-source blending)](../../../docs/reference/rendering.md#a-written-secondary-blend-source-nvn-dual-source-blending).
- **The NSP script's rewrite key always covers `sdk_patches.py`** (`patch_files`): [The packing script in detail](../../../docs/development.md#the-packing-script-in-detail).

- [Reviewed community FPS timing plans on Android](../../../docs/reference/frame-rate.md#reviewed-community-fps-timing-plans-on-android): exact-build clock and activity corrections, including Game owned and supported target bounds.

## Android (Peanuts Native)

PEANUTS: built on the device and played on an AYN Thor (Adreno 740, Turnip) through the opening to the first field, initially at the original 30 fps. The current optional Android timing correction is described below. Two things were missing during bring-up:

- The device's shader split could not decode the 678 GFPAK archives (Oodle Kraken): it now runs the game's own decoder in the app's host library, as it does for an Unreal game's shader archives (`shader_split.py` `oodle`, `ue_ondevice.py`).
- People and Pokémon were not drawn: the game skins them with transform feedback (vertex shader `3374449f919e50db` captures three outputs into buffers of stride 12 / 4 / 4), which the Vulkan replay skipped. It captures now (`dm/gfx/vk_android/vka_tf.c`, `VK_EXT_transform_feedback`; `DM_VK_XFB=0` turns it off).

## Android community FPS timing corrections

Android defaults to 60 with a reviewed port of Fl4sh9174's 1.1.1 Dynamic FPS clock. It measures elapsed time through the game's system-tick import, bounds a simulation step to 1/15 second, and replaces two former 1/30-second constants. The game's nanosecond frame period follows the selected target. The source mod's fixed 60 FPS minimum period is replaced by the live cap; 90/120 remain experimental.

The helper uses separate RX code and RW data pages. The original function used as the source mod's code cave stays intact. The inserted call preserves caller registers, full SIMD registers, NZCV and FPSR, including X8 which is live at the hook. Game owned omits the code/memory writes and retains the original 30 FPS path. Choices are 20/30/40/45/60/90/120 plus Game owned.

The actual ARM64 clock fixture passed 1,088 checks: equal elapsed time and nanosecond periods at every requested target, first-frame initialization, live X8 preservation and long-pause bounds. A bounded Turnip run reached the playable beach at about 59–60 FPS; live memory reads showed roughly 16.9 ms simulation steps. An earlier run stopped at a fresh-save naming prompt because the harness did not supply a name. Broad gameplay, later areas and high-rate physics remain unvalidated.

Source: [Fl4sh9174 Switch emulator mods](https://github.com/Fl4sh9174/Switch-Emulator-Ultrawide-FPS-Mods), reviewed commit `5eed21701e8091389e2e746f073f694ea2ad4ec6`. These community timing patches are separate from FPSLocker Warehouse configs.
