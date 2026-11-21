# Chained Echoes (`0100C510166F0000`)

Update v1310720 over the base game, with the Ashes of Elrant DLC (`0100C510166F1001`), SDK 14.3.0. **Unity 2020.3.36f1** (IL2CPP), rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`.

This profile was written from a survey of the game files. With it the game was packed on Linux and runs on Android (an AYN Thor, 2026-11-16): the title, a new game and the first two scenes at 60 fps, after the application pool was raised from 1 GB to 2 GB. Its other memory keys are still the rule's, and it was not booted on the Mac or the iPhone.

## What was found

- **Modules.** `main` `17b6a110…`, `subsdk0` `c781dd63…`, `sdk` `2aef2cff…`. The rewriter takes all three.
- **NPDM.** 64-bit, 16 MB of system resource, so the game has virtual address memory. `main` also imports `nn::os::SetMemoryHeapSize`.
- **Graphics.** `main` imports `nvnBootstrapLoader` and nothing of EGL, OpenGL, Vulkan or GLSLC.
- **Shaders.** 3,870 after duplicates: 1,896 vertex, 1,900 pixel, 73 compute and 1 geometry shader. They are Unity Shader objects: 2,899 keys first appear in `Data/globalgamemanagers.assets`, 546 in `Data/Resources/` (the engine's built-ins), 130 in `Data/resources.assets`, about 280 in the Addressables bundles (`Data/StreamingAssets/aa/Switch/*.bundle`), and 11 GLSLC outputs are in `main` (Unity's player's own). `shader_pairs.tsv` has 1,899 vertex / pixel pairs, `shader_facts.tsv` the 73 compute shaders' thread groups.
- **Movies.** No movie file in the RomFS. `main` takes 25 functions from `subsdk0` (`movie::Extractor`, `movie::Decoder`: Unity's video player), none of which the host supplies itself, so the module is loaded.
- **Network.** `nn::socket` (24 imports), `nn::nifm`, `nn::ssl`, `nn::friends` (67) and `nn::ec` (63, the shop) are imported, as by every Unity player; the game has no online mode.
- **Sound.** Unity's own audio: two `.resource` files of FSB5 banks (46 MB) and the music in bundles.
- **RomFS.** 661 files, 0.88 GB: 637 UnityFS bundles in `Data/StreamingAssets/aa/Switch` (0.81 GB; the largest, 105 MB, is the DLC's music), the player's files in `Data/`, `Data/Managed/Metadata/global-metadata.dat`. The DLC's own RomFS has two small files; its content is in the update.

## What is guessed

| Setting | Why it is a guess |
|---|---|
| 5 GB block: `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `sys_resource 0x1000000` | `profile_guess.py`'s rule for a game with a system resource. Not measured against another block; the game runs with it (see [Memory](#memory)) |
| No `mem_used` | the heap request was not seen |
| `shader_groups /Resources/=engine /StreamingAssets/=assets /Data/=data` | Fire Emblem Engage's groups (Unity 2020.3 with Addressables). They only name the package files |
| `pad_layout full` | a 2D role-playing game; the 3D layout has the D-pad. No file decides it |
| `romfs_marker Data/globalgamemanagers`, `zip_store` | as the other Unity profiles; the rule for the file types |
| `build` row's display version `?` | the import keeps no control data, so the version's name was not read |

`bf_want.tsv` and `pso_manifest.tsv` are empty. No `patches.py`. No `depth_mode`: the NVN depth mode the game sets was not seen (Fire Emblem Engage sets `zero`).

## Memory

- `nninitStartup` hands application pool − used memory to StandardAllocator as one address region (the log's `[mem] address region … at alias +0.06 GB, returning to main+0x322910`), and Unity reserves its own regions beside it, as in the other Unity games with virtual address memory. The region starts at +0.06 GB, so the `sdk`'s node area is cut to 62 MB by the SDK-wide function rule.
- **Resolved: a crash 4.6 s after the start, before the first picture, with a 1 GB pool** (`app_pool 0x40000000`, the first guess, from Patrick's Parabox and AI: THE SOMNIUM FILES - nirvanA Initiative). AYN Thor, two runs of two: about 80 black frames, then eight `[mem] MapPhysicalMemory +0x1000000 / +0x800000 / +0x400000 refused: 0.93 GB mapped, the application pool is full`, then `CRASH sig=11 pc=main+0x813a3c lr=main+0x813a30`, a write 0x20 bytes below the end of the 16 MB block that was refused (backtrace `main+0x813d24 main+0x81892c main+0x81b910 main+0x670d60 …`). The alias region was used up to +1.77 GB of 4.38 GB at that point: the pool ran out, not the address space.
- **Current: `app_pool 0x80000000` (2 GB)**, as Advance Wars 1+2: Re-Boot Camp. Measured on the Thor over 5.5 minutes (the title, a new game, Glenn's room, the airship's hold): StandardAllocator's region is 1.94 GB, the mapped memory rises in 0.25 GB steps to 1.51 GB at the title and 1.75 GB at the new game's first scene and stays there, the alias region is used up to +3.51 GB of 4.38 GB, and no map is refused. The process holds about 2.0 GB.
- Not measured: a battle, the world map, a long session. 0.25 GB of the pool and 0.87 GB of the alias region were left; a larger pool makes StandardAllocator's region larger and leaves Unity's own regions less of the alias region, so the two limits pull against each other.

## Testing

- 2026-11-16, AYN Thor (Android, Peanuts Native debug build of `2b5f1324` with this profile, Turnip), the pack made on Linux: first frame 4.1 s after the start, black frames (still black at 15 s), the title by 30 s (`Start`, `Load Save`, `DLC - Ashes of Elrant`, `Options`; the corner shows v1.41). `Start`: the opening dialogue, Glenn's room, the scene in the airship's hold, then walking there, with the quest box on screen. 59.6 to 59.9 fps over 13 readings (about 3 minutes) in play, frame time p99 16.7 ms. No crash; the only red lines are the 13 `gfx: mtl_* not yet on Android` ones at the start. Pixel art, portraits, dialogue boxes and text looked right in screenshots; flicker cannot be told from stills, and no Switch capture was compared.
- The pack was made with the 1 GB profile and was not made again: the host reads the pool from the app's own profile, and the pack's key covers only the profile's `build` rows.
- Not checked: sound, a battle, a save and a load, the DLC past its title screen, the options, the D-pad against the stick (`pad_layout full` is still a guess; the D-pad moved Glenn).

## Open

- Booted on Android only. The run-time keys (`render_cond`, `tex_flush_upload`, `tex_dirty_upload`, `core_preempt`, `present_interval`, …) keep their defaults; nothing in the opening asked for another.
- The memory layout past the opening: see [Memory](#memory). `main` imports `nn::os::SetMemoryHeapSize`, and no heap request was seen in the log.
- The `sdk` build has no registered cut in `dm/tools/sdk_patches.py`. The node area cut matched (StandardAllocator's region starts at +0.06 GB); whether the 2 MB alignment rule matched was not read from the rewrite.
- The DLC: the title menu lists `DLC - Ashes of Elrant`, and the entry opens the DLC's own title screen (`Start`, `Load`) on the Thor. The DLC itself was not started.
- No readiness rows (`readiness.tsv`): the game is not marked as supported on Android.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on ? through 1 `aspect_plan` row that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
