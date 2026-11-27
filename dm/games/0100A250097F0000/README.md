<!-- PEANUTS begin: Android bring-up profile. -->
# DRAGON BALL FIGHTERZ

## Status

Playable offline on Android via the title screen's **Play offline** option. Title, opening movie, Offline Lobby, Training, and 1v1 Local Battle run at 60 FPS with no missing shaders or failed pipelines on Xiaomi POCO F7 (Snapdragon 8s Gen 4 / Adreno 825) with Mainline Turnip v32. Story Mode currently stalls on its "Now Loading" screen after the initial loading transition.

## Build and content

- Title `0100A250097F0000`, base version `1.0.0` (`v0`) only; later title updates are not yet profiled.
- Main `af996c8a822d1e8b902d91ed4019852d`, `subsdk0` (`NintendoSDK_movie`) `303578054faefc2198114b62e408c498`, `sdk` `e01ad5381c948c4ec3b0bb210b08d5c8`, Nintendo SDK 4, Unreal Engine 4.18 (Arc System Works RED) on NVN.
- `sys_resource = 0x0` and `mem_used = 0x8000000` accommodate the ~78 MB rewritten module and `.bss` footprint on a `0xCD500000` app pool. `tex_flush_upload` and `tex_dirty_upload` are enabled for UE4 CPU-written textures.
- `shader_split.py` extracts 10,679 shaders (7,858 pixel, 2,546 vertex, 272 compute, 3 geometry) and 2,546 vertex/pixel variation pairs from `RED/Content/Paks/pakchunk0-Switch.pak`.

## Device and drivers tested

- **Xiaomi POCO F7 (Snapdragon 8s Gen 4 / Adreno 825, 12 GB RAM) — Mainline Turnip v32 (`Mesa 26.3.0-devel`, `git-9c7e022677`):** Boots through the opening movie (`2,154` video frames decoded), Offline Lobby, and full 1v1 Local Battle / Training matches through KO and the victory results screen (`172` graphics + `6` compute pipelines built, `0` failed, `2,546` profile variation pairs prepared).
- **System driver:** Not tested.

## Network and offline behaviour

- **Offline play works without network connectivity:** The title menu offers a dedicated **Play offline** choice alongside **Play online** (an earlier draft noted offline mode as broken by mistake). Selecting **Play offline** enters the Offline Lobby directly without requiring Wi-Fi or online sign-in, giving access to Local Battle and Training.
- **Play online** requires Nintendo network services and is unsupported.

## Frame rate behaviour

- Runs at **59.9–60.1 FPS** (`16.6–16.7 ms` frame time, `6.1–7.3 ms` GPU busy) in menus, Offline Lobby, and 1v1 matches at the 60 FPS cap on POCO F7 with Mainline Turnip v32.

<!-- PEANUTS end -->
