# Sea of Stars (`01008C0016544000`)

Update 2.0.59001 (v786432) over the base game, SDK 16.2.0. Unity 2021.3.25f1 IL2CPP with Wwise on NVN. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`; the `sdk` is the build of Super Mario Bros. Wonder 1.0.0 / 1.0.1 ([010015100B514000](../010015100B514000/README.md)). The DLC NSP is a licence only. Plays on the Mac at 60 fps, as on the Switch: the logos, the title, a new game (leader and difficulty choice), the Great Archives framing, the Mooncradle prologue's dialogue, walking, climbing and its scene changes, saving and continuing. An unsigned IPA is built; iOS is untested.

## What it needs

| Setting | Where | Why |
|---|---|---|
| Node area 1 GB → 62 MB | `dm/tools/sdk_patches.py` SDK-wide rules, `sdk` build `3053b229…`, `sdk+0x134728` / `+0x1347bc` | `StandardAllocator::Initialize` aborted before `nnMain`; see [Memory](#memory) |
| Region alignment 4 MB / 32 MB / 1 GB → 2 MB (SDK 20's) | `sdk_patches.py` SDK-wide rules (measured here; a row on this build until 2026-10-30), the same build, `sdk+0x134914` / `+0x13491c` / `+0x134920` | Unity's regions took twice their size in address space; see [Memory](#memory) |
| `sys_resource 0x1000000` | [`game.tsv`](game.tsv) | the NPDM's 16 MB of system resource: virtual address memory |
| <!-- PEANUTS: raised to 16 GB alias region, 2 GB stack region, and hardware pool --> `mem_reserve 0x498000000`, `mem_alias 0x400000000`, `mem_stack 0x80000000` (16 GB alias + 2 GB Horizon stack region; Mac/iOS upstream uses 5 GB / 4.375 GB / 256 MB) | `game.tsv` | with `app_pool 0xCD500000`, `StandardAllocator` reserves 3.14 GB and fills the 4.375 GB alias region; ten simultaneous 8 MB worker stacks fragment-exhaust the 256 MB stack region when exiting town; see [Memory](#memory) |
| <!-- PEANUTS: raised from 0x60000000 to 0xCD500000 --> `app_pool 0xCD500000` (3.285 GB) | `game.tsv` | hardware pool; 1.5 GB caused a crash on Android at the cutscene-to-gameplay transition; see [Memory](#memory) |
| `pad_layout full` | `game.tsv` | the 3D layout (D-pad, right stick) |
| <!-- PEANUTS: prefer Turnip on Qualcomm < 512.800 --> `recommend … driver=18` | `game.tsv` | Qualcomm `512.762.41` draws broken graphics from boot and hangs (`VK_ERROR_DEVICE_LOST` at submission `1039`/`1076`); Turnip plays 20+ min at 60 fps |

No code of the game is cut. The game asks for present interval 1, so it needs no frame-rate key.

## Memory

- **Resolved: `nn::diag` abort before `nnMain`.** `nninitStartup` (`main+0x8b66a0`) gives application pool − used memory to `StandardAllocator::Initialize(nullptr, size)` (`sdk+0x126380`, abort at `+0x126440`), and its 1 GB-aligned region did not fit after the SDK's 1 GB node area. The `AddressRegionManager` constructor (`sdk+0x134718`) is Brain Memory Training Academy's; its two `mov w8/w9, #0x3fe00000` take the 62 MB cut ([The SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area)). Mario Wonder 1.0.0 / 1.0.1 ship this `sdk` too, and Mario allocates address regions (361 in its first 100 s). With both cuts, Mario 1.0.0 boots to its title on the Mac and runs five minutes; its only red lines were graphics ones (four pipeline-prebuild link failures and `SetRenderEnableConditional`).
- **Resolved: a write at NULL + 0x3fffe0 about six minutes in, with the hardware pool** (`main+0x48b770`, Unity's `ALLOC_CACHEOBJECTS_THREAD` allocator in x19). Unity's virtual allocator got no address region: the alias region's high-water mark was +4.26 GB of 4.375 GB with 1.26 GB mapped. With a 64 GB alias region the same route reaches +6.0 GB with 1.77 GB mapped: StandardAllocator's 3.06 GB region from +1 GB commits about 0.7 GB, and Unity's own regions follow it.
- A 1.5 GB pool (StandardAllocator reserves 1.44 GB, about twice what it commits) ran 25 minutes without a crash, but still reached +4.26 GB. Region probes found the cause in the SDK's size-class alignment: Unity's N MB + 252 KB requests each take the next size class's slot, 0.86 GB of gaps among 2.59 GB of live regions ([Size-class alignment doubles Unity's regions](../../../docs/reference/memory.md#size-class-alignment-doubles-unitys-regions)). With SDK 20's 2 MB alignment cut into this build, the same live regions end at +3.24 GB, and a 20-minute session keeps the high-water mark at +3.0 GB.
- **Current:** about 2.5 GB resident and 1.13 GB of Metal allocations on the Mac in the Mooncradle prologue (2,449 → 2,466 MB resident from 3 to 13 minutes).
- <!-- PEANUTS: --> **Android (OnePlus 13R, Snapdragon 8 Gen 3, Turnip T30, v983040):**
  - With `app_pool 0x60000000` (1.5 GB), `MapPhysicalMemory` was refused (`ResultLimitReached`) at 1.43 GB mapped against the ~1.44 GB limit (`app_pool 0x60000000 − mem_used 0x4000000`) at the cutscene-to-gameplay transition, and `ALLOC_DEFAULT_THREAD` (`main+0x48d058`) wrote to the unbacked page (`CRASH sig=11 SEGV_ACCERR addr=0x40d35fffe0 pc=main+0x48d0a0`).
  - Raising `app_pool` to `0xCD500000` (3.285 GB) while keeping the 4.375 GB alias region caused `nninitStartup` (`main+0x8b8040`) to reserve 3.14 GB for `StandardAllocator` at alias `+0.06 GB`, leaving Unity only ~1.18 GB of alias address space. After the cutscene (~160 s, 1.50 GB mapped, alias high-water mark `+4.25 GB` of `4.38 GB`), Unity ran out of alias address space (`NULL` returned for a 4 MB block) and `ALLOC_CHECKOBJECTS_THREAD` wrote `stp q0, q0, [x8, #-0x20]!` at `NULL + 0x400000 − 0x20` (`CRASH sig=11 SEGV_MAPERR addr=0x3fffe0 pc=main+0x48d0a0`), the same failure seen on [SPY×ANYA: Operation Memories](../010089B01AB44000/README.md#memory).
  - **Fix (verified past cutscenes/battle/town):** `mem_alias 0x400000000` (16 GB alias region) cleared the cutscene transition, played the MP4 intro, cutscenes, scripted battle, and town at 60 fps (alias high-water mark `+5.26 GB`, `1.76 GB` mapped, `6,053` memory maps of `65,530`, 0 failed pipelines, 0 audio underruns over 450 s / 12,331 frames).
  - With `mem_alias 0x400000000`, exiting town onto the loading screen (~450 s) hit `game called nn::diag abort: Result 0, call site sdk+0x136578` (`nn::os::CreateThread` at `sdk+0x130908` → `MapAliasStack` → `StackGuardManager::MapAtRandomAddress`): 47 live stacks (113.0 MiB, 10 of them 8 MB = `0x800000`) left no contiguous 8 MB+guard slot in the 256 MB stack region (`mem_stack 0x10000000`), the same mechanism documented in [Thread stacks placed at random in the stack region](../../../docs/reference/memory.md#thread-stacks-placed-at-random-in-the-stack-region).
  - **Resolved (Turnip T30, 20+ minutes / `88,113` frames):** `mem_stack 0x80000000` (2 GB, Horizon's 39-bit stack region) and `mem_reserve 0x498000000` (`256 MB` front + `16 GB` alias + `2 GB` stack + `128 MB` heap; address space only via `MAP_NORESERVE`). In gameplay past town exit onto the world map (`88,113` frames, stopped by user), stack `UnmapMemory` reached `0x4441408000` (`~0.77 GB` into the stack region), alias high-water reached `+7.00 GB` of `16.00 GB`, physical mapped peaked at `2.03 GB` of `3.21 GB`, and kernel VMAs stayed at `7,096` of `65,530` (`10%`).

## Files and shaders

- RomFS: 2,243 files, 4.93 GB; 1,126 UnityFS bundles (1.64 GB), Wwise banks and 688 streams, 17 MP4 cinematics, all under `Data/StreamingAssets`.
- 3,898 shaders: 1,901 vertex, 1,905 pixel, 91 compute and one geometry shader. 3,887 come from `Data/`, 11 are the player's GLSLC outputs in `main`. All built for the Mac with no failure, and no run met a shader missing from the map.
- <!-- PEANUTS: --> Base `1.0.45861` (`v0`, `main=092cd60ef7c038ef934191dbb6011809`) and update `3.0.60151` (`v983040`, `main=a98aed47b317106cce87c4a682916216`, `subsdk0`/`sdk` identical to `v786432`): `v983040` holds 2,257 RomFS files (1,138 UnityFS bundles) and the exact same 3,898 shader keys and 1,904 pairs as `v786432` (only the 11 `main#GLSLC` offsets shift); `v0` holds 1,826 RomFS files (882 UnityFS bundles) and 3,406 shaders (1,658 pairs). The profile's tables hold the `v0` + `v983040` union (5,441 shaders: 2,620 vertex, 2,729 pixel, 91 compute, 1 geometry; 2,728 variation pairs), with [`shader_optional.tsv`](shader_optional.tsv) listing the 1,543 `v0`-only keys absent from `v786432` and `v983040`. All `v0` and `v983040` modules rewrite cleanly for Android (`patch_nso.py --target android`), including all five `sdk` `MATCH_CUTS`.

## Frame rate

- **Current:** 60 fps on the Mac (frame time p50 16.6 ms; p95 about 20 ms as the median of the 300-frame windows of a 40-minute session, with other jobs on the Mac; CPU about 0.9 W on an M4 Pro). The game asks for present interval 1; no key is needed.
- With other jobs' windows over the game window, macOS stops the display link and nearly every frame is discarded (`on-screen … dropped 281`), so screenshots never come; `DM_PRESENT_LINK=0` presents without it for scripted runs.

## Audio

- Wwise through two `AudioOut`s (one opened at boot from `main+0x8b63f0`, one by the sound engine from `main+0x1850070`, which polls instead of waiting on a release event). No underrun in 20 minutes; `DM_AUDIO_DUMP` holds the title music from about 30 s.
- In the 40-minute session, the host's audio watchdog logged six `[RED] audio #…: the device has not called back for 209–223 ms`, all 12 to 17.5 minutes in, while the IPA build compiled iOS shaders and packed on the same Mac. It restarted the AudioUnit each time, and the game kept presenting frames. The same Core Audio callback stalls are open on [Pokémon Brilliant Diamond](../0100000011D90000/README.md#testing).

## Mac routes

- Build with `scripts/build_ipa_from_nsp.py <dump folder> --platform mac`; its last line runs the game.
- From a fresh save, through `DM_CTL`: `Plus` at the title (about 45 s after boot), `A` (New Game), `A`, `A`, `Left`, `A` (Zale leads), `A` (Adventure), then `A` every 1.5 s through the Great Archives and the Mooncradle dialogue (about 3 minutes) to the camp, where the player has control.
- The camp: the save pedestal is at its west end, the path south leads to the Mooncradle cliff, whose climbing holds lead back to the pedestal.

## Testing

- 2026-10-26, Mac: boot, logos, title, a new game, the leader and difficulty choices, the Great Archives and Mooncradle prologue dialogue, walking, climbing, three scene changes, saving to slot 1 and continuing from the title, over a 40-minute session and four shorter ones. The only red lines were the audio watchdog's under the IPA build's load ([Audio](#audio)).
- No battle was reached (the prologue's camp has no enemy), and no cinematic MP4 has played yet.
- <!-- PEANUTS: --> 2026-11-25, Android (OnePlus 13R, Snapdragon 8 Gen 3, 11.0 GiB, v983040, `dev.nxnative.android.peanutstest`):
  - **Turnip (`Turnip_Adreno_Driver_T30_Mr_Purple_666_`):**
    - Earlier runs with smaller bounds hit pool refusal (`app_pool 0x60000000` at 1.43 GB mapped), alias VA exhaustion (`mem_alias 0x118000000` at `+4.25 GB` of `4.38 GB`), and 256 MB stack fragmentation on town exit (`sdk+0x136578` at 47 live stacks / `10 × 8 MB`).
    - With `app_pool 0xCD500000`, `mem_alias 0x400000000`, `mem_stack 0x80000000`, `mem_reserve 0x498000000`: played for 20+ minutes (`88,113` frames, clean user exit) through boot, MP4 intro, cutscenes, scripted battle, town, town exit, and further gameplay/battles at **60.0 fps median** (`p10 = 59.5 fps`, `p5 = 57.0 fps`, `max = 60.2 fps` across 298 five-second windows; brief loading-screen dips to `24.4–53.5 fps` with GPU busy `0.2–1.9 ms`). Zero failed pipelines (`234` graphics + `91` compute, `0` draws skipped waiting), zero audio underruns across `394,000+` device callbacks, `0` non-standard `[RED]` lines.
  - **System driver (Qualcomm `512.762.41` / `0x802fa029`):** broken graphics from boot and `VK_ERROR_DEVICE_LOST` (`-4`) GPU hang as pipelines start (submission `1039` / `1076`, including with `DM_VK_ZSHARE=0`). With `DM_VK_FD_ALIAS=0`, the readiness check marks it incompatible (`placed maps` is unsupported). Profile `recommend` row prefers Turnip (`driver=18`) on Qualcomm `< 512.800`.

## Open

- <!-- PEANUTS: --> Android: Qualcomm system driver (`512.762.41`) shows broken graphics from boot and hangs (`-4`) once pipelines start; newer Qualcomm drivers (`>= 512.800` with placed maps) and `v0` / `v786432` builds on Android are untested.
- iOS: the unsigned IPA (`SeaOfStars.ipa`, 5.1 GB, 5,454,467,672 bytes, with the fps counter; 3,898 iOS shader libraries) is untested on a device.

## Found on this game

- The SDK 16.2.0 instance of [the SDK's address-region node area](../../../docs/reference/memory.md#the-sdks-address-region-node-area).
- [Size-class alignment doubles Unity's regions](../../../docs/reference/memory.md#size-class-alignment-doubles-unitys-regions).
- <!-- PEANUTS: --> Android: `app_pool 0x60000000` refused `MapPhysicalMemory` at 1.43 GB mapped (`0xCD500000` reached `2.03 GB` peak); `mem_alias 0x118000000` exhausted alias VA (`SEGV_MAPERR` at `addr=0x3fffe0`, resolved by `mem_alias 0x400000000`, peaked at `+7.00 GB`); `mem_stack 0x10000000` fragmented across `47` live stacks (`10 × 8 MB`) at `sdk+0x136578` (resolved by `mem_stack 0x80000000`, reached `~0.77 GB` in stack).
