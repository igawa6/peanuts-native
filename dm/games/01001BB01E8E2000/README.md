<!-- PEANUTS: Android bring-up of the Switch title. -->
# FANTASIAN Neo Dimension

The base game (1.0.0) and update v131072 (1.0.2) have different AArch64 `main` modules and identical `subsdk0` (NintendoSDK_movie) and `sdk` modules. Both use Unity 2022.3.30f1 IL2CPP and NVN on SDK 17.5.8. The build IDs are in `game.tsv`. This is an experimental profile. <!-- PEANUTS: on the 0.1.9 release list --> It is on the release list from 0.1.9 (`peanuts/release-titles.txt`, so in the default APK), with no readiness row.

## Memory

Both NPDMs declare 16 MiB of system resource. `nninitStartup` computes total minus used memory, rounded down to 2 MiB, then branches on `IsVirtualAddressMemoryEnabled`. On the enabled path it calls `StandardAllocator::Initialize(nullptr, size)` at `main+0x80692c` (base) or `main+0x806a2c` (update). The imported `SetMemoryHeapSize` is on the other branch. The initial partition follows [Super Monkey Ball Banana Rumble](../010031F019294000/README.md#memory), which ships the same SDK and uses the same allocation path. No game-code cuts are required by this profile.

On an AYN Thor, 1.0.2 with a 1 GiB application pool initialized the 1920×1080 swapchain, audio and HID and committed its first system-settings save. `MapPhysicalMemory` then refused 16 MiB, 8 MiB and `0x578000` as the pool filled. Unity reported “Could not allocate memory: System out of memory!” and cleared a null allocation (`sdk+0x50d69c`, caller `main+0xaf7dfc`) before the title screen. The profile raises the physical pool to 2 GiB without changing the address-space partition.

## Shaders

The base has 35,664 UnityFS bundles and the update has 35,667. The two snapshots differ at 125 RomFS paths. Comparing serialized Shader and ComputeShader objects in those paths found one added object in the update; the update is the shader-table source. A base executable match alone does not establish rendering or gameplay on that version.

The full 1.0.2 scan found 9,918 shader keys: 4,932 vertex, 4,936 pixel, 37 compute, six hull, six domain and one geometry. Eleven are in `main`. The split produced 4,935 vertex/pixel pairs and found shaders in 3,237 RomFS files. `shader_files.tsv` retains those paths for the targeted split, including bundles that duplicate a shader whose first source is elsewhere.

<!-- PEANUTS begin: Android results (#579) -->
## Android

- 2026-11-25, AYN Thor (Adreno 740), Turnip 27.0.0-R2, the 0.1.8 runtime with a test APK; the base 1.0.0 with update 1.0.2, built on the phone by the app (all shaders found). The contributor's report on pull request #579.
- Route: the title (Ver. 1.0.2), New Game on Normal, the opening, the Robo Guard battle (won), the Field Movement tutorial, then Leo walking freely on the walkway. No crash; the only `[RED]` lines are the `mtl_*` statistics stubs.
- Frame pacing (SurfaceFlinger, 117.84 Hz panel): the battle 58.51 fps over 92 s, with 9 frames over 42 ms (the longest 195 ms) as it opened and made its pipelines for the first time; walking 58.96 fps over 42 s, no frame over 25.5 ms.
- No prewarm seed, so a first battle hitches while its pipelines are made.
- Not checked: Qualcomm's own driver, play past the walkway.
<!-- PEANUTS end -->
