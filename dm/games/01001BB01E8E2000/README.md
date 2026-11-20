<!-- PEANUTS: Android bring-up of the Switch title. -->
# FANTASIAN Neo Dimension

The base game (1.0.0) and update v131072 (1.0.2) have different AArch64 `main` modules and identical `subsdk0` (NintendoSDK_movie) and `sdk` modules. Both use Unity 2022.3.30f1 IL2CPP and NVN on SDK 17.5.8. The build IDs are in `game.tsv`. This is an experimental profile, not a release-list entry.

## Memory

Both NPDMs declare 16 MiB of system resource. `nninitStartup` computes total minus used memory, rounded down to 2 MiB, then branches on `IsVirtualAddressMemoryEnabled`. On the enabled path it calls `StandardAllocator::Initialize(nullptr, size)` at `main+0x80692c` (base) or `main+0x806a2c` (update). The imported `SetMemoryHeapSize` is on the other branch. The initial partition follows [Super Monkey Ball Banana Rumble](../010031F019294000/README.md#memory), which ships the same SDK and uses the same allocation path. No game-code cuts are required by this profile.

## Shaders

The base has 35,664 UnityFS bundles and the update has 35,667. The two snapshots differ at 125 RomFS paths. Comparing serialized Shader and ComputeShader objects in those paths found one added object in the update; the update is the shader-table source. A base executable match alone does not establish rendering or gameplay on that version.

The full 1.0.2 scan found 9,918 shader keys: 4,932 vertex, 4,936 pixel, 37 compute, six hull, six domain and one geometry. Eleven are in `main`. The split produced 4,935 vertex/pixel pairs and found shaders in 3,237 RomFS files. `shader_files.tsv` retains those paths for the targeted split, including bundles that duplicate a shader whose first source is elsewhere.
