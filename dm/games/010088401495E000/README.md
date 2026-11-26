<!-- PEANUTS: a Peanuts Native profile (Banana Native has none for this title) -->
# THE HOUSE OF THE DEAD: Remake (`010088401495E000`)

Update 1.1.0 (`v131072`) over the base game (`v0`, 1.0.0). SDK 13.3.2, Unity 2020.3.25f1 IL2CPP with FMOD and Addressables on NVN. Modules `main`, `subsdk0` (`NintendoSDK_movie`) and `sdk`.

## Status

Plays on Samsung Galaxy Tab S8+ (`SM-X800`, Snapdragon 8 Gen 1 / Adreno 730) with Mesa Turnip T30 (`driver=18`) at ~59.7 FPS median (`1920x1080`, 1080p H.264 movies + FMOD audio, 0 missing shaders, 0 crashes).

## Prewarm

- No `vk_prewarm.bin` committed in this profile yet (awaits maintainer build; a local SM-X800 Turnip T30 run collected 954 pipeline keys).

## Builds

- `v0` (1.0.0): `main=f42fa3eeb61779643c8c843e73bf41bc`, `subsdk0=a8cb97803f7f442a3b6cce1c7cd4ee4a`, `sdk=357efc198e728cb5d55265aff304f03c`
- `v131072` (1.1.0): `main=91a3518371696ed07cb4d2c87754ccca`, `subsdk0=a8cb97803f7f442a3b6cce1c7cd4ee4a`, `sdk=357efc198e728cb5d55265aff304f03c`

## Shaders

- `shader_split.py` over Update 1.1.0 (`v131072`): 10,761 shaders (56 compute, 1 geometry, 5,354 pixel, 5,350 vertex; 10,750 from `Data/`, 11 from `main`), 5,352 variation pairs.

## Memory

- The NPDM declares 16 MB of system resource (`sys_resource 0x1000000`).
- Uses a 16 GB virtual alias region (`mem_reserve 0x428000000`, `mem_alias 0x400000000`) so `StandardAllocator` does not crowd Unity 2020.3's own virtual address regions.
- `mem_used 0x8000000` (128 MB) to cover the ~113 MB of code modules (`main` 92.8 MB, `sdk` 13.6 MB, `subsdk0` 6.6 MB).

## Open

- Qualcomm system driver not tested.
