# MY HERO ONE'S JUSTICE 2

<!-- PEANUTS -->

- Title ID: `01007E700DBF6000`
- Update: `v983040`
- Engine: Unreal Engine 4
- SDK: 6.4.0
- Modules: main, subsdk0, sdk

## Android validation

- Device: POCO Pad X1
- SoC: Snapdragon 7+ Gen 3
- RAM: 8 GB
- OS: Android 16
- Driver: TurnipZ 0.3.0
- Gameplay: playable at 30 FPS with normal game speed.
- DLC: content mounts and characters appear after the ReadFile fix.

## Shaders

Prepared directly on Android using native Termux.

- Full split: 122.867 seconds.
- Targeted split: 115.540 seconds.
- Scan jobs: 1442 full, 64 targeted.
- Targeted tables matched the profile byte for byte.
- Total shaders: 9686.
- Compute: 463.
- Geometry: 17.
- Pixel: 8040.
- Vertex: 1166.
- SPIR-V validation: 17870 modules.

## Dependencies

- #1025: Unreal Engine legacy PAK v7 support.
- #1026: ReadFile overload with ReadOption.
- #1027: Build-specific offset jump table fallback.

## Known issues and limitations

- Qualcomm system Vulkan driver crashes in libllvm-qgl.so.
- Display version has not been recovered from control data.
- Sustained 60 FPS has not been validated.
- Not every DLC character has been tested individually.
- Gameplay validation used the original experimental jump-table fallback.

Pipeline keys collected with TurnipZ 0.3.0 are provided separately.
