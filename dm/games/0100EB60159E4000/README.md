# Aeterna Noctis (`0100EB60159E4000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 3.2.000 (v458752) over the base game, SDK 15.3.2. Unity 2022.2.5f1 IL2CPP on NVN, with FMOD. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`; the `sdk` and `subsdk0` builds are Blasphemous 2's ([its note](../010040801A4C8000/README.md)), so the SDK-wide patches that apply there apply here. The profile is the provisional one from `scripts/build_ipa_from_nsp.py`, except for its memory layout and frame rate rows. On Android (AYN Odin3 Portal, Adreno 830: Turnip, the system driver and Qualcomm 891.17) it plays at 60 fps from the title through a save's areas, a dream level and its library, at Resolution scale 0.5x to 2x, with no red line ([Android testing](#android-testing)).

## What it needs

| Setting | Where | Why |
|---|---|---|
| `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | The NPDM declares 16 MB of system resource, enabling virtual address memory |
| 16 GB alias region (`mem_alias 0x400000000`, `mem_reserve 0x428000000`) beside the Switch's 3285 MB pool | `game.tsv` | As SPY×ANYA's Android profile ([Memory](#memory)) |
| `fps_limit_present 1`, `fps_limit_max 120` | `game.tsv` | The frame limit list from 20 to 120 fps ([Frame rate](#frame-rate)) |
| `shader_groups /Data/=data` | `game.tsv` | The serialized files and bundles all live under `Data/` |

## Builds

| Build | `main` | `sdk` | `subsdk0` |
|---|---|---|---|
| v458752 (3.2.000) | `7b0a450883ba23210000000000000000` | `8618895246b420329b77746b960a66ab` | `368f01d5cb42f06cd125dc739d036719` |

- The eShop base game is a sparse NCA: it keeps only the parts of its data the update still reads, so the base version cannot be played or dumped on its own. `nximport` reads it through the update (`dm/peanuts/import/nx_nca.c`, the sparse table). The DLC (Virtuoso, `0100EB60159E5001`) holds a single empty `Dummy.txt`: a licence-only package.

## Shaders

- The merged RomFS has 250 files, 1.80 GB: 24 UnityFS bundles (1.09 GB), 209 FMOD banks and `Data/data.unity3d`.
- 10,441 shaders: 5,172 vertex, 5,176 pixel, 82 compute, 5 hull, 5 domain and one geometry. Eleven are Unity's GLSLC outputs in `main`, the others Shader objects in the serialized files and bundles; 5,164 vertex/pixel pairs. Unity 2022.2 is not among the releases `unityfs.py` lists, but the split read every bundle with no `[RED]` line (27 s on an M-series Mac). All 11,297 modules are valid for `spirv-val`; the library is 39.0 MB.
- The picture is URP 14's: the scene at 90 % (1728x972 in the docked 1920x1080; 1842x1036 in some areas), its bloom pyramid (`Hidden/Universal Render Pipeline/Bloom`, B10G11R11 levels from 864x486 down) and `UberPost`. The game's own glow and particle shaders dither (`AmplifyParticleAddDither`, `DeformShaderAddDither` and others).

## Memory

- The heap check did not run: the Mac host does not link in this tree, and Android work does not need it.
- The survey's guess (the Switch's pool in a 4.375 GB alias region) is the layout that filled on SPY×ANYA: StandardAllocator's region (pool − used) left Unity's own regions about 1.2 GB. A 1 GB pool, as the Mac profiles of Unity games on this SDK use (Blasphemous 2), risks refused maps under the pool limit on Android ([Sizing the pool and alias region for virtual address memory](../../../docs/reference/memory.md#sizing-the-pool-and-alias-region-for-virtual-address-memory)). So the profile takes SPY×ANYA's layout: the hardware pool and a 16 GB alias region, which on Android is address space only. In play the footprint stays at 1.3–2.2 GB, with up to 1.5 GB of GPU memory at 2x.

## Frame rate

- The game presents at interval 1 at 60 fps and has no engine limit the host writes. With `fps_limit_present 1` and `fps_limit_max 120` the launcher offers Game owned and display-paced caps from 20 to 120 fps (experimental), starting at 60 (the default since 0.1.8: `DM_FPS_LIMIT=60`, `DM_PRESENT_INTERVAL=display`, a 120 Hz tick; frame time p95 16.7 ms against 18.2–18.8 ms in Game owned, which paces as the first runs did, interval mode `game` on a 60 Hz tick).
- Open: the game's speed and motion at caps other than 60 (it should step by elapsed time, as Unity games do, but that was not measured).

## Android testing

- **AYN Odin3 Portal (Adreno 830), Turnip Gen8 V39, 2026-11-28.** A debug test app (`dev.nxnative.android.peanutstest`) with the pack made on a Mac from the `.nup`. Title, a save's areas, a dream level and its library, 16 runs of up to 12 minutes: 60 fps (GPU 5–7 ms a frame at 2x), no `[RED]` line beyond the expected stubs, no crash. Resolution scale 0.5x, 1x, 1.5x and 2x, Game owned and the 60 fps cap. At 2x the glow of thin lines had ripples until the resolution scale's reduction rule ([Found on this game](#found-on-this-game)); with it the glow is as at 1x.
- **AYN Odin3 Portal, Qualcomm system driver (Vulkan 1.3.284, build 3967b80d3b), 2026-11-28.** Two runs in Game owned, at 1x and at 2x: 60 fps, GPU 2.0 ms a frame at 1x and 5.5–6.9 ms at 2x, no `[RED]` line beyond the expected stubs, no crash; at 2x the resolution scale kept the same 25 shapes at 1x and filled and read two 1x copies a frame, as on Turnip.
- **AYN Odin3 Portal, Qualcomm 891.17 through adrenotools (Vulkan 1.3.351, build ab37423821), 2026-11-28.** Two runs in Game owned, at 2x and at 1x: 60 fps, GPU 4.9–6.4 ms a frame at 2x and 2.0–2.3 ms at 1x, no `[RED]` line beyond the expected stubs, no crash; at 2x the same 25 shapes at 1x and two 1x copies a frame. The app's memory footprint levels off at about 3.35 GB (the system driver: about 1.55 GB, with the same GPU memory, up to 1.4 GB at 2x): a constant addition of the driver's within these one-minute runs, not measured further.
- Not run: the DLC's content; later areas; caps other than 60.

## Open

- `pso_manifest.tsv` is empty (no recorded startup pipelines); the first minutes make pipelines (502 in a 4-minute run).
- The log digest's `game-catchup-stall` rule fires a few times a run (frames of 200 ms or more with the GPU idle) in Game owned and under the cap alike; not looked into.

## Found on this game

- [A blur chain over scaled images: ripples beside thin lines, then a flickering glow](../../../docs/reference/rendering.md#a-blur-chain-over-scaled-images-ripples-beside-thin-lines-then-a-flickering-glow-android): URP's bloom pyramid at 2x; its 921x518 layer pass, which has a depth-stencil target, keeps its scale.
