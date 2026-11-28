<!-- PEANUTS: Peanuts-only profile (Banana Native has none for this title) -->
# Untitled Goose Game (`010082400BCC6000`)

Update `v458752`, SDK 8.2.0. Unity 2018.4.1f1 IL2CPP with FMOD audio and NVN graphics. Modules `main` (`dbc3424ab22de644a987386dc692d327`), `subsdk0` (`8df876f80ce4ceee62360b31405c452f`, `NintendoSDK_movie`), and `sdk` (`3ae34e689d7a35f13cbf42bfd672af8b`).

## Shaders

4,511 shaders (`50` compute, `2,162` pixel, `2,299` vertex; `4,507` from `Data/`, `4` from `main`; 5,380 SPIR-V modules in the 16.9 MB Android library), split directly from the `v458752` `.nup`.

## Android

Tested on OnePlus 13R (`CPH2691`, Snapdragon 8 Gen 3, Adreno 750, 12 GB / 11.0 GiB usable RAM, Android 16) with zero Extra environment overrides:

- **In-app build:** builds cleanly directly inside the Android test APK on-device.
- **Qualcomm system driver (`512.762.41`):** playable with draw and compute layouts off push descriptors, which the profile asks for on Qualcomm `< 512.800` (`push_desc_qcom_old off`, see below) (~2.8 min / 9,900 frames, 58.1-60.2 fps; 60.0-60.1 fps in most windows, ~58-59 fps under 1.4 GHz thermal cap at 850-1,065 draws; 20-32 render passes/frame, recording 3.4-6.5 ms/frame, GPU p50 4.7-6.8 ms, 107 graphics / 0 compute pipelines, 0 failed, 0 `[VRED]`, 0 audio underruns across 44k callbacks).
- **Turnip Mr. Purple T30 (`26.3.0-T30-1.4.359`):** playable with push descriptors active (177-341/frame; ~2.1 min / 7,500 frames, 59.7-60.1 fps, 20-32 render passes/frame, recording 1.5-2.4 ms/frame, GPU p50 2.6-3.4 ms, 114 graphics / 50 compute pipelines, 0 failed, GPL active, 0 `[VRED]`, 0 audio underruns across 34k callbacks).
- **Qualcomm `< 512.800` rendering note:** with push descriptors on, terrain and hill meshes flickered and disappeared when push descriptors and the deferred uniform updates (`DM_VK_UB_DEFER=1`) were both active (#888). The profile's `push_desc_qcom_old off` turns push descriptors off for draw and compute layouts there only (`DM_VK_PUSH_DESC=1` turns them back on); Turnip and Qualcomm `512.800` or later keep them. It is a per-game key, not a host rule for every game: turning push descriptors off cost Breath of the Wild about 45 % more replay CPU on the stock 512.676 driver ([rendering.md](../../../docs/reference/rendering.md#push-descriptors-corrupt-deferred-ubo-updates-inside-a-render-pass-on-qualcomms-driver-android)). <!-- PEANUTS: #907 as a profile key -->
- **Not tested:** 2-player mode (no physical controllers available).

## Frame rate caps

Supports 30, 40, 45, 60, 90, and 120 fps (`present_interval display`, `fps_limit 60`, `fps_limit_min 30`, `fps_limit_max 120`) with no binary patch or `DM_FPSLOCKER` entry required; Unity scales cleanly at 1x gameplay speed across all caps.

Tested on OnePlus 13R (`CPH2691`, Adreno 750, Android 16, 120.12-120.16 Hz panel, 0 `[VRED]`, 0 failed pipelines, 0 audio underruns):

- **Qualcomm 512.762.41 (`0x802fa029`)**:
  - **30 fps**: locked 30.0 fps (p50 33.29 / p95 33.45 ms, 0 dropped/300, GPU p50 5.67 ms).
  - **40 fps**: locked 40.0 fps (p50 24.97 / p95 25.04 ms, 0 dropped/300, GPU p50 5.57 ms).
  - **45 fps**: locked 44.5-45.0 fps (p50 22.22 / p95 22.30-22.41 ms, 0 dropped/300, GPU p50 5.60-5.97 ms).
  - **90 fps**: 86.1-90.0 fps in-game (p50 11.11-11.57 / p95 11.17-14.42 ms, 0-1 dropped/300, GPU p50 5.56-6.17 ms).
  - **120 fps**: 119.6 fps menu, 79.6-94.2 fps in-game under thermal status 1 (GPU p50 5.64-6.24 ms).
- **Turnip Mr. Purple T30**:
  - **30 fps**: locked 30.0 fps (p50 33.30 / p95 33.42-33.48 ms, 0 dropped/300, GPU p50 2.55-3.31 ms).
  - **40 fps**: locked 40.0 fps (p50 24.98 / p95 25.07-25.08 ms, 0 dropped/300, GPU p50 2.97-3.41 ms).
  - **45 fps**: locked 45.0 fps (p50 22.22 / p95 22.30 ms, 0 dropped/300, GPU p50 2.97-3.42 ms).
  - **90 fps**: 89.7-91.0 fps menu, 81.9-86.3 fps in-game under 1401 MHz thermal cap (p50 11.11-11.21 ms, 1-7 dropped/300, GPU p50 2.99-3.29 ms).
  - **120 fps**: 119.8-121.5 fps menu, 82-115 fps in-game (thermal dependent, GPU p50 2.6-3.5 ms).
