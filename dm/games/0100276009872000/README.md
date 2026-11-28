<!-- PEANUTS: nxnative-derived Android profile; tested on Turnip, stock driver issue open. -->
# Ōkami HD (`0100276009872000`)

Base game 1.0.0 (v0), SDK 4.5.0, NVN, CRIWARE audio and the SDK movie module. The [nxnative profile](https://github.com/seedds/nxnative/tree/main/dm/games/0100276009872000) reached the first playable scene on a Mac at the game's original 30 FPS. This profile preserves the upstream game constants; all three registered module build IDs matched the surveyed base game.

## Status

**Playable on Turnip T30 on the current `dev` host.** The initial device run progressed through the introduction, met Yomigami, used the Celestial Brush to restore the bridge at the River of the Heavens, and tackled the chest on the far side to obtain the Astral Pouch. Save and reload were verified. A subsequent cold-cache run on `dev` reached Kamiki Village with its residents petrified, with more than 18 minutes of play reported. The log records 45,000 frames and 268 five-second active windows (22 min 20 s); 218 windows report exactly 30 FPS, the minimum is 29.8 FPS, and some windows reach 60.1 FPS under the game's own pacing. All 126 shaders (129 modules) loaded; by frame 45,000, 140 graphics and 8 compute pipelines had been created with zero failures. The audio callback counters recorded no underruns. No crash occurred and the session was stopped by the user. The 13 Metal-only diagnostic stubs did not prevent play. Frame generation was off.

**Game owned pacing.** The launcher cap of 60 FPS was not applied in the initial run: the host log says that this build has neither a frame step nor an engine frame limit that the host writes. The later run used Game owned. The stats screenshot at 20:57 reports 30 FPS, 33.3 ms, 30 FPS 1% low and 100% on pace. The slowest logged whole-frame sample in the latest run was 66.8 ms; a continuous per-frame median/maximum and a timed game-speed check are not yet measured. No custom native cap is offered by this profile.

## Tested on

- AYN Thor Pro, Snapdragon 8 Gen 2, Adreno 740, Android 13, 11.0 GiB RAM reported. Tested 2026-11-28 on local test apps built from `dbbdb852e5fe` and `1ad7039e7974`, with Turnip Adreno Driver T30 (Mr Purple; PurpleVK 26.3.0, driver ID 18, Vulkan 1.4.359). The latter run began with a zero-byte pipeline cache and no prewarm keys; 99 recorded pipeline keys were saved by the last logged cache write.

**System driver: failed before gameplay** on the same device (Qualcomm driver ID 8, version `0x802a4035`, Vulkan 1.3.128). The initial run had a pipeline failure at about 42 seconds using vertex `2dfb6ded16f75047`, geometry `f0aaea64df272914` and pixel `4d4cf5d56a079e24` (result -13), followed by 7% skipped draws and SIGABRT after frame 1,812. The cold-cache retest on `dev` build `1ad7039e7974` (2026-11-29) reproduced the same pipeline failure and SIGABRT after frame 1,573, about 53 seconds into the run; one pipeline failed and 24 keys were reflected in the last logged cache write. The precise cause of the abort is not established. Use Turnip T30 for this device.

## Builds

- `v0` (1.0.0): `main=a4fddc7fd35b30cb763bf70c3213dcb7`, `subsdk0=303578054faefc2198114b62e408c498`, `sdk=e01ad5381c948c4ec3b0bb210b08d5c8`. The main build ID was verified in the Android log. No other version is supported by this profile.

## Files and shaders

- The survey found 5,744 RomFS files and the shader split found 126 shaders (8 compute, 2 geometry, 88 pixel, 28 vertex), including 91 in `main` and 35 in RomFS.
- Fast Prepare: the current `dev` splitter generated `shader_files.tsv`. The full split took 10.20 s; the targeted split took 0.33 s and scanned 126 of 5,744 RomFS files. `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv` and `pso_pairs.tsv` match byte for byte. Both runs produced the same 126 shader keys.
- The Android pack was built locally with all 126 shaders translated. All 129 resulting SPIR-V modules passed `spirv-val` for Vulkan 1.3. The Android rewriter completed for `main`, `subsdk0`, and `sdk`.
- The upstream `pso_manifest.tsv` is intentionally empty.

## Memory

- The nxnative profile's survey-derived guess uses a 4 GiB address-space reserve and a `0xCD500000` application pool. The stats screenshot at 20:57 shows 1.4 GB game RAM, 0.7 GB VRAM and 4.4/11 GB total device RAM in use; these are instantaneous values, not a measured memory peak.

## Found on this game

- The system-driver geometry pipeline failure remains open; no shared host or shader-chain code was changed.

## Open

- Qualcomm system-driver rendering and crash, later-game areas, combat encounters, and additional Celestial Brush techniques remain untested or unresolved. No companion was tested.
- Cold-cache system-driver retest reproduced the failure. The Turnip and system-driver pipeline-key captures were collected separately on the same `dev` host; no seed binary is included in the profile. Precise per-frame timing, timed game-speed check and memory peak are not yet recorded.
- The upstream Mac note reports that the prologue skip prompt does not skip; the original 30 FPS target is retained.
