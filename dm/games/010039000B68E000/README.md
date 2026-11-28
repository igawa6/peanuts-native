<!-- PEANUTS: Android game support. -->
# MY HERO ONE'S JUSTICE

- Title ID: `010039000B68E000`
- Tested version: 1.0.3 (v196608).
- Engine: Unreal Engine 4.
- Main build ID: `e6c20e29986a8b4d0c60b6c711986652`.

## Android validation

Tested on POCO Pad X1, Snapdragon 7+ Gen 3,
8 GB RAM, Android 16.

- Qualcomm system driver: previously playable, but crashes in the latest test.
- Turnip: playable, with character rendering and battle effects working.
- Native 60 FPS and experimental engine-controlled 30 FPS tested.
- 7,846 shaders translated successfully.
- 14,814 SPIR-V modules validated.

The experimental 30 FPS mode requires shared-base Unreal
console variable detection for t.MaxFPS.

## Limitations

Occasional frame-rate drops remain.
Full story completion, DLC, iOS and dual-screen companions
have not been tested.

## Targeted shader split

Tested on POCO Pad X1 using native Termux, with two workers.

| Split | Time |
| --- | ---: |
| Full | 44.313 s |
| Targeted | 46.774 s |

Only 2 of 23 RomFS files contain the profile's shaders.
All four shader tables match byte for byte.
The targeted split completed without fallback.
No speed improvement was observed in this measurement.
