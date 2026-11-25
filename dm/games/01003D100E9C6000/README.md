<!-- PEANUTS: Peanuts' profile of the EU edition, made from nxnative's profile of the RU edition (0100A0800E9C4000, commit a4c82979) -->
# The Witcher 3: Wild Hunt — Complete Edition (`01003D100E9C6000`)

Update 4.04b (v458752) over the base game, SDK 15.3.0, on NVN. CD PROJEKT RED's REDengine 3 in Saber Interactive's Switch port; Havok, Wwise. Modules `main` and `sdk`. Hearts of Stone and Blood and Wine are in the base game's RomFS (`dlc/`): the main menu lists both as installed, with no DLC to add.

## Status

Plays on an AYN Thor (Snapdragon 8 Gen 2, Android 13) on Turnip 27.0.0-R2 (driver ID 18), 2026-11-24: the opening logos and movies, the title, the main menu, a new game into Kaer Morhen (the bedroom, the balcony and the valley below), loading a save, audio, a relaunch, at 60 fps (GPU p50 about 14.5 ms). On the system driver (Qualcomm 676.53, driver ID 8) it boots and plays at about 38 fps (GPU 26 ms) with wrong shading (Geralt's hair flat white, his body nearly black): use Turnip.

- **Open:** a crash once in four save loads, a null read in the game's own code (`main+0x1d5224`, `[x0+0x1c]` with x0 = 0, after an FNV-1a name hash); not seen again in the later runs.
- **Open:** the tower and the valley read dark while the eye adaptation settles after a load; they brighten within seconds, and forcing a barrier after every dispatch (`DM_VK_DISPATCH_ORDER=1`, the race nxnative found in the exposure histogram, [#3048](https://github.com/seedds/nxnative/issues/3048)) left the picture's brightness the same. Not compared with a console capture.

## Builds

| Version | main | sdk |
|---|---|---|
| 4.04b (v458752) | `4bc4a8a814fd46a4831b3aa8e689886b` | `d5a1029a57d8cd4fdf4eacad87b0cfd0` |

The `sdk` module is the RU edition's (nxnative's profile); `main` is built per edition. The profile has no `patches.py`.

## What it needs

| Setting | Why |
|---|---|
| REDengine 3 shader cache readers (`shader_split.py` `rdhs_shaders`, `fzlc_shaders`, from nxnative a4c82979) | the material caches (`shadernx.cache`, 'RDHS' footer) and the static cache (`staticshadernx.cache`, 'FZLC') hold all but the 563 loose shaders: 29,673 shaders (compute 46, domain 13, geometry 22, hull 7, pixel 4,964, vertex 24,621) |
| Tessellated draws on Vulkan (`OP_PATCH_SIZE`, `vka_draw.c`, `vka_pso.c`) | the terrain and the water are patches of one control point (7 hull, 13 domain shaders); Vulkan skipped them ("tessellation patches are not on Vulkan yet") |
| The domain shaders' own tessellation mode ([A domain shader built in the translator's default tessellation mode](../../../docs/reference/shaders.md#a-domain-shader-built-in-the-translators-default-tessellation-mode-vulkan)) | quads, fractional_even: built as triangles, the valley was fans of wedges |
| Type-2 samplers ([A separate sampler ID in a uniform buffer on Vulkan](../../../docs/reference/shaders.md#a-separate-sampler-id-in-a-uniform-buffer-on-vulkan-handle-type-2)) | 20,212 bindings in 6,323 shaders took no sampler of the game's: the shadows were a grid of black and yellow squares, the light hard-edged blocks |
| The hardware Opus decoder's hooks on the big host stack (`hle_codec.c`, `HLE_FORCE_BIG`) | the title screen sets up its decoders from a thread with a 4 KB stack; the hook's logging overflowed it ([Host calls on small guest stacks](../../../docs/reference/memory.md#host-calls-on-small-guest-stacks)) |
| A 50 GiB alias region (`mem_alias 0xC80000000`) | a constructor reserves 48 GiB and later allocations reach alias +49.00 GB (Thor log); 3.0–3.3 GB is touched |
| `present_interval display`, `fps_limit 60`, `fps_limit_max 120` | time-based logic (below) |
| `depth_mode zero` | the game sets near-zero depth (the seed's shader parts are prepared for it) |

## Frame rate

Display pacing (frame-rate catalogue method A). The game's logic runs on real time: nxnative measured 60.0 fps at the same game-clock speed as 30, and FPSLocker's Warehouse has this exact build ([`4BC4A8A814FD46A4.yaml`](https://github.com/masagrator/FPSLocker-Warehouse/blob/main/SaltySD/plugins/FPSLocker/patches/01003D100E9C6000/4BC4A8A814FD46A4.yaml): `LimitFPS` and the dynamic resolution frame-time targets, no frame step). nxnative's `fps_target 60` is not applied by this host (`present cap 60 fps not applied: this main has neither a frame step nor an engine frame limit`).

On the Thor (Turnip, `DM_FPS_LIMIT=<n>`, the balcony save): 20 held 19.9 (measured in the intro movie), 30 held 29.7–30.1, 40 held 40.0, 45 held 45.0–45.1, 60 held 59.6–60.0, Game owned ran 59.8. The panel stays at 60 Hz, so 90 and 120 are not shown. A walk test at 30 and 60 was inconclusive (the camera turned differently).

## First launch

`vk_prewarm.bin`: 292 pipeline keys (1 compute, 291 vertex) collected on Turnip from the start, the menus, the intro, the bedroom and the balcony. Cold launch (the game's cache set aside): the shader parts of 24,621 programs in 206.5 s and the 292 pipelines made before the game starts, first frame about 220 s, no pipeline missing from the list at the menus, the load and the balcony. Warm launch: the parts cache read, first frame after 4 s.

## Fast build

Targeted split against the full one, same inputs (PC, Linux): full 120 s over 977 RomFS files, targeted 7 s over the 159 files that hold shaders; shader map, facts, shader pairs, PSO pairs and the 14 package files identical. Pack 308 MiB (code and the 230.5 MB shader library); the RomFS stays in the 37.2 GB `.nup`.

## Found on this game

- Resolved: shadows and light drawn as grids and blocks on Vulkan → [A separate sampler ID in a uniform buffer on Vulkan (handle type 2)](../../../docs/reference/shaders.md#a-separate-sampler-id-in-a-uniform-buffer-on-vulkan-handle-type-2).
- Resolved: the valley's terrain as fans of wedges → [A domain shader built in the translator's default tessellation mode (Vulkan)](../../../docs/reference/shaders.md#a-domain-shader-built-in-the-translators-default-tessellation-mode-vulkan).
- Resolved: a crash at the title screen in `strncpy` on a 4 KB guest stack under `h_codec_HwOpusInit` → [Host calls on small guest stacks](../../../docs/reference/memory.md#host-calls-on-small-guest-stacks).
