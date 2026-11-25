# Viewfinder

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Base game (`v0`, 1.0.0), SDK 19.3.9. Sad Owl Studios' **Unity 2022.3.61f1 IL2CPP** game on **NVN**, with Bink 2 (`subsdk0`, `NintendoSDK_movie`) and FMOD. Modules `main`, `subsdk0` and `sdk`. No game code is cut and no host rule is added.

## Memory and frame rate

- **Memory:** During startup, the game reserves a 3.14 GB address region (`nn::os::AllocateAddressRegion`) alongside its 3.21 GB application pool (`0xCD500000`). The provisional 5.0 GB `mem_reserve` / 4.375 GB `mem_alias` exhausted the alias region around frame 592 when Unity's `DualThreadAllocator` (`ALLOC_DEFAULT_THREAD`) requested a new 16 MiB slab and returned null (`main+0xe86440`). Raising `mem_reserve` to `0x4b0000000` (18.75 GB) and `mem_alias` to `0x400000000` (16.0 GB) resolves the OOM crash.
- **Frame rate:** `present_interval display` in [`game.tsv`](game.tsv) presents every display tick instead of holding the game's interval 2 (30 fps). Because Viewfinder steps by Unity's frame delta time, gameplay runs at 60 fps with normal game speed.

## Android bring-up (`2026-11-23`)

Brought up on a POCO F7 (`Snapdragon 8s Gen 4`, Adreno 825, Android 17):

- **Driver:** Mesa Turnip (`Turnip Gen8 V39-patched`, `Mesa 27.0.0-devel`, `git-2648b2059f`, Vulkan 1.4.367).
- **Shaders:** 19,600 shaders extracted from the base `UnityFS` bundles and `data.unity3d` (`shader_split.py`; `0` missing in runtime logs).
- **Result:** Boots cleanly through logos and title screen into 60 fps gameplay at ~5 W board power draw with audio, controls, and normal game speed. Not tested on iOS or macOS; later updates beyond `v0` are not registered.
