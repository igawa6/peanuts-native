# Pokémon Shining Pearl (`010018E011D92000`)

Update 1.3.0 (v393216) over the base game. Unity 2019.4.27f1 IL2CPP, SDK 11.4.3, NVN. Pokémon Brilliant Diamond's twin ([0100000011D90000](../0100000011D90000/README.md)): the same engine and the same `subsdk0` (NintendoSDK_movie) and `sdk` builds; only `main` is a build of its own (`38f59cbd…`, Brilliant Diamond's is `94ceae32…`). Everything in Brilliant Diamond's note about the engine, its memory, files and shaders holds here; this note gives only what differs or was checked again. The profile is Brilliant Diamond's with Shining Pearl's title, name, build row, marker file and shader tables. Made on Linux for Android; not built for the Mac or iOS.

## What it needs

The same keys as Brilliant Diamond's profile, for the same reasons ([Brilliant Diamond, What it needs](../0100000011D90000/README.md#what-it-needs)). The SDK node area cut comes with the `sdk` build (`2d369d99…`, `dm/tools/sdk_patches.py`).

- `romfs_marker`: the ending movies are under `Movie/pea`, not `Movie/dia`.
- `fps_target 60`: Unity's `Application.targetFrameRate` is at the same place as in Brilliant Diamond's `main` (the setter at `main+0x5fa630` stores at `main+0x4afc868`, read from this build's code), so `dm/host/hle_fpslock.c` has a row for this build with the same offset.

No `patches.py`.

## Files and shaders

- Shaders: 14,441, the same content keys as Brilliant Diamond's. The 14,430 in the RomFS are at the same places; the 11 in `main` are at other offsets, so the map is this game's own (`shader_split.py` on this game's files). The facts and pairs tables come from the same run.

## Open

- Luminescent Platinum for Shining Pearl and the other code mods have not been tried on this build: a mod's hooks are keyed to `main`'s build.

## Android community FPS timing corrections

Android uses the corresponding reviewed Fl4sh9174 timing corrections for 1.3.0, with this game's own main build and separate hook/cave addresses. The method and bounded water phase are described in [Brilliant Diamond](../0100000011D90000/README.md#android-community-fps-timing-corrections).

WaterInteraction's type 4332, 26 fields, 0x120 allocation, last instance bool at 0x118 and spare word at 0x11c were independently checked against this build's metadata and registrations. Every patch word is exact-build and expected-byte checked. Choices are 30/40/45/60/90/120 plus Game owned, default 60; 20 is unavailable because the correction cannot sustain 30 Hz NPC/water activity below 30 rendered FPS. High rates remain experimental.

Both builds passed the 2,192-check actual ARM64 CPU fixture. A bounded Turnip town route reached about 118–119 FPS at the 120 target. Broader physics, water/snow interactions and later areas remain unvalidated.

Source: [Fl4sh9174 Switch emulator mods](https://github.com/Fl4sh9174/Switch-Emulator-Ultrawide-FPS-Mods), reviewed commit `5eed21701e8091389e2e746f073f694ea2ad4ec6`. These community timing patches are separate from FPSLocker Warehouse configs.

## Found on this game

- [Reviewed community FPS timing plans on Android](../../../docs/reference/frame-rate.md#reviewed-community-fps-timing-plans-on-android): exact-build clock and activity corrections, including Game owned and supported target bounds.

<!-- PEANUTS begin: picture shapes -->
## Picture shapes (Android)

**Untested.** Game settings › Display offers **Fill the screen** on 1.3.0 through 1 `aspect_plan` row that nobody has seen on a device (`aspect_plan_untested 1`, and the Fill choice says so). They are every place this build's main has the packed 1920x1080 sizes in code and the 16/9 float words, from a scan of the code on 2026-11-21 with each expected word read on the pack; no community patch stands behind them. With Fill the 3D view may follow the screen, the HUD and menus may not, or the game may draw wrongly or not start. Fit and Stretch are unchanged. Open: every screen, at any shape.

<!-- PEANUTS end -->
