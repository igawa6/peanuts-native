# Mario Tennis Fever (`0400D4201C6EA000`)

A Switch 2 game: update v327680 (1.1.1) over the base game, SDK 20.5.17 built for the Switch 2 (`Ounce`), NVN2. Camelot's engine (`nxe`) on NintendoWare (G3d, Ui2d, Vfx, Atk), with Enlighten, Pia and NPLN. Modules `main` and `sdk`, plus one NRO that `main` decodes at run time. **Plays on the Mac** (M4 Pro): the title movie, the practice, the main menu, Adventure's opening story, and Free Play singles on Stadium Court (Grass) with real rallies, at 60 fps. No IPA has been built. **Status on Android: playable, in bring-up (2026-11-12).** On the Odin 3 (Adreno 830) a whole tournament ran at about 60 fps on Mainline Turnip v32; Qualcomm's driver hangs (see Open). Packs come from the desktop packer only. The profile and its shader tables come from nxnative (`b6dbb851`), where the game plays on the Mac.

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_alias 0x500000000`, `mem_reserve 0x650000000` | [`game.tsv`](game.tsv) | `nninitStartup` asks `StandardAllocator` for 16 GB of address space, and this SDK keeps its 1 GB node area (no node-area cut matches it): with a 16 GB alias region `CentralHeap::Init` aborted (`sdk+0x13f698`) |
| `mem_stack 0x10000000`, `app_pool 0x240000000` | `game.tsv` | Splatoon Raiders' values, the Switch 2's 9 GB, not measured here |
| `sys_resource 0x2000000` | `game.tsv` | the NPDM declares 32 MB, so virtual address memory is on |
| `pad_layout full` | `game.tsv` | the game asks for most of the controller |

Render conditions, which the host evaluates for every game ([Conditional rendering evaluated at replay](../../../docs/reference/rendering.md#conditional-rendering-evaluated-at-replay); until 2026-11-12 the profile key `render_cond`): as the console does. Off, a rally looks the same and takes about 0.7 of 9.5 ms more GPU (one run each, with another job on the Mac) and logs a red line.

The NRO and the archive need no key: the NSP script captures one and `shader_split.py` reads the other (below).

## Files

- A dump folder (`base/`, `updates/327680/`): `build_ipa_from_nsp.py <folder> --platform mac` packs it with no keys. The display version `v327680` is a placeholder, as a dump has no control data.
- `main` has about 600 KB of code. It is a boot stub (`nxboot`): six threads unscramble and unpack the game's code, a 24.7 MB NRO, out of its data segment, and it loads that with `nn::ro` (A game that decodes its code at run time). The NSP script captures it from a boot in about 2 s. Its SHA-256 is the one the RomFS's NRR registers (NRR version 1). The stub obfuscates its strings and streams, but runs as it is: no game code is changed for this.
- The RomFS is `42` (7.6 GB, magic `CRA.`) and the NRR. `42` holds 8,671 files, listed only in the NRO: 5,474 sound, 164 WebM movies, the rest NARC containers of FRES, FSHA, BNTX, VFXB, SARC (One scrambled archive indexed by the NRO). `dm/gfx/tools/cra.py <42> <NRO> <folder>` writes them out.
- Movies: VP9 WebM with Vorbis, up to 2560x1440 at 60 fps; the title movie is 368 MB. The game reads them itself from the archive and feeds the SDK's extractor (`AppendMediaSourceData`).

## Shaders

35,045 in [`shader_map.tsv`](shader_map.tsv): 26,096 from models (one FSHA each), 5,331 from `ssg_system`, 3,316 from effects, 138 from `shader/`, 124 from layouts, 40 in the NRO's data. All translate. A full build takes about an hour on an M4 Pro with another job running (not timed from an empty cache in one go). [`pso_manifest.tsv`](pso_manifest.tsv) (1,351 lines) is one Mac session: boot, the movie, the menus, a match.

One does not load (current): `3032440e5027bbe5`, `shader/audience/audience_compute`, reads `array<texture2d<float>, 32> cp_t2d_cb0_0`, the compute texture handles of NVN2's per-shader `c[0]` indexed by its workgroup ID. `dmsh_reflect.py` `c2_array` only knows c2; fixing it is a chain change. The dispatch is skipped, a red line says so, and the crowd still draws; what the shader adds has not been seen.

## Rendering

- The scene renders at 2048x1152 into a 2560x1440 window texture, and the game crops the window to that (`nvnWindowSetCrop`). `DM_NVN_SHOT` takes the whole texture, so shots have a black band on the right and bottom; the window does not.
- Deferred: albedo and normals, then lighting into R11G11B10F, the atmosphere over it, bloom, an ACES tonemap. The atmosphere's table is a 3D texture of 8x8x64, filled once per stage with one draw per plane through a geometry shader that picks the layer (A passthrough geometry shader that writes the layer: the blue wash).
- Frame time in a rally, in a window of 300 frames, with another session's regression on the Mac: p50 16.6–17.2 ms, p95 about 18 ms, GPU p50 about 9 ms, p95 about 11 ms. A few single frames take 90–140 ms, not traced (open); a run after the prewarm list has not been compared.

### Resolution

- **The scenes' size (current, 2026-11-14).** One function of `nro0` (1.1.1: `nro0+0x125e980`) takes a base by the operation mode, console 2560x1440 and handheld 1920x1080, caps it by `vi::QueryDisplayBufferMaxResolution` in one performance mode, then applies the Switch 2 graphics scale (`oe::GetCurrentGraphicsScale`, stored at `nro0+0x3ecb31c` and read only there): scale 1 takes 4/5 of the base, scale 2 2/3, any other value keeps it. So the game draws console 2048x1152 at its start (scale 1), handheld 1920x1080 at scale 0 and 1280x720 at scale 2, and nothing smaller.
- **Game settings › Resolution** ([`game.tsv`](game.tsv) `graphics_scale` rows; `android_window original` keeps the game's own start as the default): Original and Phone 1440p are console scale 1 (2048x1152), Phone 1080p is handheld scale 0, Phone 720p is handheld scale 2. **Phone 540p** is handheld scale 2 plus `DM_GRAPHICS_SIZE=960x540` (the launcher passes it for a row whose scale and mode a larger row has, `Profile.gateSize`): an observation gate where the function's paths join (`nro0+0x125eb98`, [`patches.py`](patches.py), `dm/host/android/mtf_size.c`) writes that size into the scene's size record and w9, never above the game's own pick. Phone 480p takes 540p. The in-game panel changes it live: the scale, mode and size in one call, one message 300 (`nxhost_graphics_set`); on the Odin 3 (system driver, 2026-11-14) 540p → 720p → 1080p → 540p → 1080p each resized the scenes (`WindowCrop` 960x540, 1280x720, 1920x1080, ...) with no new `[RED]` line, before that change 540p → 1080p passed through 720p. This is a cut of the game's own code at a fixed address, keyed by `nro0`'s build ID; another build draws its own sizes ([Open](#open)).
- **Why it matters: the game counts frames.** It steps once a presented frame at interval 1, with no fixed steps (`fps_steps`) or sead rate (`fps_decouple`), so Game settings shows no Frame rate cap or Hold 60 fps. A frame over 16.7 ms is shown at the next vsync: 30 fps and half speed, as a console would slow (Forgejo #302, #307). A smaller scene is the lever until the engine's frame loop is understood (open).
- Measured on the Odin 3 (Adreno 830, the Android test app, Mainline Turnip v32, the tutorial court, 2026-11-14): the GPU clock the governor held for 60 fps was 660–734 MHz at 2048x1152, 607–660 at 1920x1080 and 525 at 1280x720 (of 1,100); GPU time per frame stayed about 12.7 ms because the clock follows the work. The largest step is 24 full-scene quads (3.5 ms at 2048x1152, 1.9 ms at 1280x720); the 1024x1024 shadow pass (1.5–2.1 ms) does not scale.

## Driving it on the Mac

The work folder `0400D4201C6EA000-work` beside the dump has `run/boot.sh <log> <secs> [VAR=value…]`, `run/drive.py` (control lines, `wait S`, `snap NAME`), `run/tomatch.py <log>` (from a save, to the first serve of a Free Play match in about 100 s) and `run/rally.py`. With no save, A from 25 s goes through the movie into the practice, and Plus twice leaves it for the main menu. The stick moves the menus (`LDown`). A tosses and hits. The opponent and court are the menu's defaults or random.

## Open

- **Tessellated grass is not drawn.** `shader/grass` and `shader/sea_of_clouds` use hull and domain shaders (two each), which the host has no path for: `primitive type 14 not implemented` and `geometry draw cannot capture this pipeline` (tessellation with a geometry shader), and those draws are skipped. The court's own texture and stripes draw, so grass courts look flat; compared with nothing yet.
- `3032440e5027bbe5` above.
- Nothing past the first Adventure scenes and one Free Play match has been played: no other court, doubles, the online modes (no network on the host).
- Not checked: game speed against the console; the host's `IsRunningOnOunce` answers false.
- **Full speed below 60 fps:** the engine (`nxe`) steps once a frame; a step count or frame-time value the host could drive is not found yet ([Resolution](#resolution)).
- Phone 540p's gate is for `nro0` 1.1.1 only; it could be found by function fingerprint (the 4/5 and 2/3 multiplies after `GetCurrentGraphicsScale`) for later builds.
- `linear texture storage is not in a memory pool (1024x1024 format 0x26)`, once in some runs, no effect seen.
- An IPA: the 20 GB alias region is over iOS's limit until this SDK gets a node-area cut ([An SDK 21 Switch 2 game with a 4.5 GB alias region](../../../docs/reference/memory.md#an-sdk-21-switch-2-game-with-a-45-gb-alias-region)).
- Stack slots reused after `ro::Initialize` are held off by a modelled time, not an order.

## Found on this game

- A game that decodes its code at run time and NRR version 1.
- One scrambled archive indexed by the NRO.
- A shared font larger than the game's font engine memory: a null pointer in `nn::font` at boot.
- A thread's arguments in slots its starter reuses after connecting to a service: a crash at boot about once in a dozen runs.
- The submission-ready event counted as packets: black movies, then corrupt frames.
- A passthrough geometry shader that writes the layer: a blue wash over every 3D scene with atmosphere.
- Null vertex buffers bound past stream 15.
- Prepare on a phone refused the rename of the NRO collection's folder with EPERM (issue #296): [Prepare stops at "Rewriting the game's code" with EPERM …](../../../docs/reference/packing.md#prepare-stops-at-rewriting-the-games-code-with-eperm-on-android-nropartial-android).
- Android emulator (test APK at `8be9db6b`, SwiftShader, desktop pack of 1.1.1, 2026-11-12): all eight stages, then `ro::RegisterModuleInfo` with the NRR's one hash and `NRO nro0 loaded` from the pack's `nro.tsv` (77,303 relative relocations; the NVN shader compiler's weak `glslc*` imports bound to 0), and the frame loop at 35 draws a frame, 51 fps median, still running at 96 s. Draws are off there (no placed memory), so nothing is drawn; the `[RED]` lines are the emulator's placed-memory ones, plus `sampler ID 4352 beyond the table (4096)` and one linear texture that cannot be copied back (open, unchecked on a phone).
- The Android host: the host changes of nxnative `b6dbb851` are ported in pull request #275, which the game needs to boot (NRR version 1, the NROs' imports in `dm_game_imports`, the service-connection time for started threads, the font engine's memory, null vertex buffer binds past 16 streams); the video decoder's ready event per packet was already here. Run on the Odin 3 (below). nxnative's run-time capture of the NRO is not ported (the packer decodes it instead).
- The Android pack: `pack_android.py` collects the NRO (`nro_collect.py`), rewrites it with the modules (`nro0`: 12,842 plain sites, no cuts) and lists it in the pack's `nro.tsv`; its shader split gets it as `DM_NRO_DIR`, and the targeted split of 1.1.1 makes the profile's tables byte for byte (current, 2026-11-12, macOS). The phone's Prepare reads it from `main` itself (resolved, 2026-11-12: [Prepare stops at the split …](../../../docs/reference/packing.md#prepare-stops-at-the-split-of-a-game-whose-code-a-boot-stub-packs-into-main)), and its split unscrambles in C ([about an hour on one core](../../../docs/reference/packing.md#the-phones-split-of-camelots-archive-takes-about-an-hour-on-one-core): 6 min 23 s on an Odin 3). Its shaders step took 2 h 13 min or more on an Odin 3 until its results moved to the app's own storage (pull request #280; about 14 minutes on a Galaxy Z Fold 7).
- Odin 3 (Adreno 830, Android app at `ac9249e8`, desktop pack of 1.1.1, 2026-11-12):
  - Mainline Turnip v32: a whole tournament, about 16.5 minutes (60,000 frames), at 60 fps median and 56.6 at the lowest. 4 frames of 250-420 ms, all on the game's main thread with the GPU idle (loads). No GPU fault in the kernel log.
  - Qualcomm's driver V@0800.72 (Vulkan `0x80320048`, dated 2026-02-12): kernel GPU hangs (`Fault id:2`, no page fault), each a stall of about 250 ms, the first at the first match's intro. Dark square patches on the player intros at match start. One run lost the device after 4 minutes (3 hangs in 2 s). Neither showed on Turnip; the cause is open (no GPU snapshot yet).
  - Text drew blank until a GPU-written linear texture followed the CPU's writes (pull request #276). In-match banners then lost letters ("GAME POINT" drawn as "G M PO"): the text compute only reads the glyph cache, yet its binding marked it written, so it was copied back over new glyphs (pull request #276).
  - Menu text went missing after about 15 sub-menus: each sub-menu makes and finalizes a texture and a sampler pool, and the 17th pool shared the first one's IDs (pull request #277).
  - The shadow map: a 3584x3584 depth texture over level 1 of the 7168x7168 shadow map is now that level (pull request #278); on Qualcomm's driver its scaled copy from level 0 is drawn, as D32 has no `BLIT_DST` there (pull request #279). Open: the shadow of the court's umbrella is still cut on both drivers (the console draws it whole): the shadow casters reach only the first cascade, because the passthrough geometry shader's viewport-mask broadcast is not translated (cosmetic, deferred).
  - Button icons drew as "?" (`[YELLOW] Nintendo extended shared font missing`). An icon font made by `dm/tools/make_ext_font.py`, given as `DM_SHARED_FONT_EXT`, draws them. The app now ships that font (made at the APK build, which needs fontTools), and it draws them with no switch set (resolved, 2026-11-14, Turnip: [Button icons drawn as "?" on Android](../../../docs/reference/services.md#button-icons-drawn-as--on-android)).
  - The match start: one frame of 240-275 ms as a match loads, on both drivers (2026-11-13): the replay makes about 550 images (about 450 MB) there, and making the images and their memory takes 117-177 ms of it, the uploads about 70 ms (open).
  - Audio: 0 underruns on both drivers, but about one `audren` catch-up a minute, heard as short skips (open).
  - `[RED]` lines: `tessellation patches are not on Vulkan yet` (4 draws skipped at the match intro); `linear texture serial … format 0x26 … cannot be copied back`; `virtual pool … aliasing physical pool pages is not yet on Android`; `sampler ID 4352 beyond the table (4096)` (no shader of this game reads the pools).
