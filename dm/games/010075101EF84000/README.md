# Stray

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Base game (`v0`, 1.0.0), SDK 17.5.6. BlueTwelve Studio's **Unreal Engine 4** game on **NVN**, with Bink 2 (`subsdk0`, `NintendoSDK_movie`). Modules `main`, `subsdk0` and `sdk`. `ue_malloc.py` and `ue_region.py` handle Unreal Engine 4's allocator and virtual address region automatically; [`patches.py`](patches.py) and `fps_target 60` in [`game.tsv`](game.tsv) unlock 60 fps.

## Frame rate

- **Current:** 60 fps (30 on the Switch). The game caps itself at 30 twice: `DefaultEngine.ini` sets `bUseFixedFrameRate=True` and `FixedFrameRate=30.0`, and `BaseSwitchDeviceProfiles.ini` sets `rhi.syncinterval=2`. [`patches.py`](patches.py) renames the `bUseFixedFrameRate` INI lookup key in `main` so `bUseFixedFrameRate` stays at its `false` default, and `fps_target 60` (`hle_fpslock.c`) writes `60` into `t.MaxFPS` and `1` into `rhi.SyncInterval`.

## Android bring-up (`2026-11-22`)

Brought up on a POCO F7 (`Snapdragon 8s Gen 4`, Adreno 825, Android 16):

- **Driver:** Mesa Turnip (`26.3.0-T30-1.4.359`, `PurpleVK 26.3.0-devel`, `git-62ac221a33`) and Qualcomm system driver (`Vulkan 1.3.295`), both reaching in-game play.
- **Shaders:** 10,748 shaders extracted from the Oodle-compressed base `.pak` (`shader_split.py` via `DM_OODLE_OOZ`: `676` compute, `3` geometry, `7,752` pixel, `2,317` vertex; `0` missing in runtime logs across 14,700+ frames).
- **Result:** Boots cleanly through logos and title screen into gameplay at 60 fps (`~2.2-2.6 GB` resident) with audio, controls, and normal game speed.
- **Open items:** In the opening thunderstorm alcove before the storm clears, dark unlit surfaces appear mostly black until illuminated by lightning flashes; full daylight gameplay immediately following renders normally. Not tested on iOS or macOS; later updates beyond `v0` are not registered.
