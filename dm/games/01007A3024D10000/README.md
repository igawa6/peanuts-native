<!-- PEANUTS: a Peanuts Native profile (Banana Native has none for this title) -->
# THE HOUSE OF THE DEAD 2: Remake (`01007A3024D10000`)

Update 1.0.2 (`v131072`) over the base game (`v0`, 1.0.0). SDK 19.3.5, Unity 2022.3.55f1 IL2CPP with FMOD and Addressables on NVN. Modules `main`, `subsdk0` (`NintendoSDK_movie`) and `sdk`.

## Status

On the release list from 0.1.10 (`peanuts/release-titles.txt`, so in the default APK), with no readiness row. <!-- PEANUTS: on the 0.1.10 release list -->

Plays on Samsung Galaxy Tab S8+ (`SM-X800`, Snapdragon 8 Gen 1 / Adreno 730) with Mesa Turnip T30 (`driver=18`) at 55.4 FPS median (59.7 FPS median with low-end graphics patches; 1080p, FMOD audio, 0 missing shaders, 0 crashes).

## Prewarm

- No `vk_prewarm.bin` committed in this profile yet (awaits maintainer build; a local SM-X800 Turnip T30 run collected 904 pipeline keys).

## Builds

- `v0` (1.0.0): `main=16ae183b4953cc0616e227ff2eaa7be1`, `subsdk0=e0ee365be8fb7ba6d2071082b5417b50`, `sdk=191086db0c8bb8477a320ccd7942a254`
- `v131072` (1.0.2): `main=a6f2e97d9d49d82c40fac30e3d2497d8`, `subsdk0=e0ee365be8fb7ba6d2071082b5417b50`, `sdk=191086db0c8bb8477a320ccd7942a254`

## Shaders

- `shader_split.py` over Update 1.0.2 (`v131072`): 28,134 shaders (37 compute, 1 geometry, 14,050 pixel, 14,046 vertex; 28,123 from `Data/`, 11 from `main`), 14,049 variation pairs.

## Memory

- The NPDM declares 16 MB of system resource (`sys_resource 0x1000000`).
- Uses a 16 GB virtual alias region (`mem_reserve 0x428000000`, `mem_alias 0x400000000`) so `StandardAllocator` does not crowd Unity 2022.3's own virtual address regions.
- `mem_used 0xC000000` (192 MB) to cover the ~156 MB of code modules (`main` 137.4 MB, `sdk` 12.3 MB, `subsdk0` 6.5 MB).

## Open

- Qualcomm system driver not tested.
