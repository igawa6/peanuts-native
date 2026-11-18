# Device reports

Players have sent logs and reports for Banana Native from many phones and handhelds. This page lists them by GPU, with what each one reached, so you can see how a phone like yours did. It is a record of what was reported, not a promise: the same phone can behave differently on another app version, driver or game save.

For the short list of devices, see [Tested devices](Tested-Devices). For what to do when something fails, see [Troubleshooting](Troubleshooting) and [GPU drivers](GPU-Drivers).

## How to read the tables

- **Result.** *Runs* means the log reached the game's frame loop and ends with no error line (the player stopped the game, or the log was cut). *Runs, slow* is the same with a log median under about 20 fps. *Fails* names what the app's log digest said: the GPU driver stopped responding (**driver lost**), a **crash** (a SIGSEGV or abort), **out of memory** (Android closed the game), or a **game abort** (the game stopped itself). *Unclear* means the log is too short or stops before the game starts drawing.
- **fps (log median).** The median of the frame rates in the log, one reading every five seconds, from the log digest. It includes loading and stalls, so it is not the frame rate of steady play. Where the reporter wrote a number in the issue, the notes say so. Anything not in a log or an issue is "not measured yet".
- **Driver.** *system* is the phone's own Qualcomm driver. Other names are the Turnip (or other) driver file the player picked, as written in the log. See [GPU drivers](GPU-Drivers).
- **App version.** The Banana Native release in the log. Older versions had bugs fixed since; a failure on 0.2.x may not happen on the current release.
- **Issue.** Opens the report on the project's issue tracker. Rows with several issue numbers were seen in more than one report.
- Issues without a log digest (13 of the 63 issues this page was built from) are not listed.

Games: Ocarina of Time (`0400486024DEC000`), Donkey Kong Bananza (`040074A01BF12000`), Pokémon Pokopia (`0400A3A01EE4C000`), Mario Kart World (`0400C3F00006E000`), Splatoon Raiders (`0400380020056000`). See [Games](Games).

## Adreno 825 / 830 / 840 / 850 (newest Snapdragon generation)

| Device | Android | GPU (Adreno unless noted) | GPU driver | Game | App version | Result | fps (log median) | Issue | Notes |
|---|---|---|---|---|---|---|---|---|---|
| nubia NX809J | 16 | 840 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.0 | Runs | 30.9 | [#126](https://git.eden-emu.dev/moceure/BananaNative/issues/126) |  |
| nubia NP06J | 16 | 840 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.22 | Runs | 30 | [#265](https://git.eden-emu.dev/moceure/BananaNative/issues/265) | The issue itself reports crashes on area changes (four logs); the digest of this log shows no error line, so "Runs" here is only that log. |
| LENOVO TB323FU | 16 | 840 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 40 | [#183](https://git.eden-emu.dev/moceure/BananaNative/issues/183) |  |
| samsung SM-F971B | 17 | 840 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.2.5 | Runs | 28.7 | [#58](https://git.eden-emu.dev/moceure/BananaNative/issues/58) | Kokiri Forest range matches the project notes. |
| realme RMX5200 | 16 | 840 | system | [Mario Kart World](Game-Mario-Kart-World) | 0.3.11 | Runs | 59.1 | [#159](https://git.eden-emu.dev/moceure/BananaNative/issues/159) |  |
| vivo I2501 | 16 | 840 | system | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.3.13 | Runs | 21.6 | [#193](https://git.eden-emu.dev/moceure/BananaNative/issues/193) | The log shows update 0 (base game only). The issue text says Android closed the game for memory about 10 seconds after launch, which this log's digest did not show. |
| samsung SM-F971B | 17 | 840 | system | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.3.1 | Fails: GPU driver lost | 51.5 and 56.6 before the loss | [#93](https://git.eden-emu.dev/moceure/BananaNative/issues/93) | Two logs. |
| samsung SM-F971B | 17 | 840 | Turnip Gen8 V39 (patched) | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.3.13 | Fails: GPU driver lost | 35.9 before the loss | [#93](https://git.eden-emu.dev/moceure/BananaNative/issues/93) |  |
| Xiaomi 2509FPN0BC | 16 | 840 | system | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.3.1 | Fails: GPU driver lost (7 of 9 logs) | 21 to 25 before the loss | [#93](https://git.eden-emu.dev/moceure/BananaNative/issues/93) | The other 2 logs end without an error (log median 60 and 51.7). |
| nubia NX809J | 16 | 840 | Turnip Gen8 V39 (patched) | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.2.7 | Fails: GPU driver lost | 55.6 before the loss | [#91](https://git.eden-emu.dev/moceure/BananaNative/issues/91) |  |
| vivo I2405 | 16 | 825 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.23 | Fails: crash (SIGSEGV at the first graphics call) | none | [#290](https://git.eden-emu.dev/moceure/BananaNative/issues/290) | Same crash with Turnip Gen8 V39 (patched). |
| vivo I2405 | 16 | 825 | Turnip Gen8 V39 (patched) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.23 | Fails: crash (SIGSEGV at the first graphics call) | none | [#290](https://git.eden-emu.dev/moceure/BananaNative/issues/290) |  |
| Xiaomi M154FF | 17 | 850 (as the reporter wrote it) | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.2.8 | Runs, slow after a setting change; killed by Android with default switches | 21 to 26 with eager textures off and frame generation off; 14.7 with frame generation on | [#97](https://git.eden-emu.dev/moceure/BananaNative/issues/97), [#98](https://git.eden-emu.dev/moceure/BananaNative/issues/98) | Reporter guohaomin1995-dev. Also a picture flicker, see issue 98. |
| samsung SM-S931B | 16 | 830 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.20 / 0.3.22 | Runs | 30 | [#224](https://git.eden-emu.dev/moceure/BananaNative/issues/224), [#248](https://git.eden-emu.dev/moceure/BananaNative/issues/248), [#264](https://git.eden-emu.dev/moceure/BananaNative/issues/264) | Three logs. |
| samsung SM-S931B | 17 | 830 | Turnip Gen8 V39 (patched) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.1 | Runs | 17.7 and 32 | [#132](https://git.eden-emu.dev/moceure/BananaNative/issues/132) | Two logs. |
| samsung SM-S931W | 17 | 830 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 47.9 | [#183](https://git.eden-emu.dev/moceure/BananaNative/issues/183) |  |
| samsung SM-S931W | 17 | 830 | Qualcomm Adreno 842.8 (custom) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 33.3 | [#183](https://git.eden-emu.dev/moceure/BananaNative/issues/183) |  |
| samsung SM-S931W | 17 | 830 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.20 | Fails: crash (2 logs) | 60 and 26.6 before the crash | [#236](https://git.eden-emu.dev/moceure/BananaNative/issues/236) |  |
| samsung SM-S931W | 17 | 830 | Qualcomm Adreno 842.8 (custom) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.20 | Fails: out of memory | 60 before the stop | [#236](https://git.eden-emu.dev/moceure/BananaNative/issues/236) |  |
| samsung SM-S936U | 17 | 830 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.0 | Fails: game abort | 29.2 before the stop | [#145](https://git.eden-emu.dev/moceure/BananaNative/issues/145) |  |
| ayn Odin3 | 15 | 830 | Turnip (Mesa 27.0.0 r4 build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.1 | Runs | 30 | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131) |  |
| ayn Odin3 | 15 | 830 | Turnip (Mesa 26.3.0 r7 build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.1 | Runs | 19.9 | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131) |  |
| ayn Odin3 | 15 | 830 | Turnip A8XX MR v25.2 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.11 | Fails: game abort | 43.7 before the stop | [#160](https://git.eden-emu.dev/moceure/BananaNative/issues/160) |  |
| ayn Odin3 | 15 | 830 | Turnip Gen8 V39 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.11 | Fails: game abort | 60 before the stop | [#162](https://git.eden-emu.dev/moceure/BananaNative/issues/162) |  |
| ayn Odin3 | 15 | 830 | Turnip T30 | Splatoon Raiders | 0.3.0 | Unclear (runs; the game note says very low frame rate) | 50.8 | [#129](https://git.eden-emu.dev/moceure/BananaNative/issues/129) | Log median is much higher than the game note's description; not checked further. |
| nubia NX733J | 16 | 830 | Turnip Gen8 V38 | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.3.13 | Runs | 58 | [#207](https://git.eden-emu.dev/moceure/BananaNative/issues/207) |  |
| samsung SM-S938B | 16 | 830 | Turnip T30 | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.2.4 | Unclear | 0.9 (one reading) | [#44](https://git.eden-emu.dev/moceure/BananaNative/issues/44) | The log is short; it reached the frame loop but shows only one reading. |
| motorola razr 60 ultra | 16 | 830 | Rutabaga 26.10.30 (custom) | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.2.7 | Unclear | none | [#86](https://git.eden-emu.dev/moceure/BananaNative/issues/86) | The log stops during SDK start-up, before the game's frame loop; no error line. |

## Adreno 740 / 750 (previous generations)

| Device | Android | GPU (Adreno unless noted) | GPU driver | Game | App version | Result | fps (log median) | Issue | Notes |
|---|---|---|---|---|---|---|---|---|---|
| AYN Thor | 13 | 740 | Turnip T30 | [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | 0.2.6 | Runs, slow | 11.1 | [#81](https://git.eden-emu.dev/moceure/BananaNative/issues/81) |  |
| AYN Thor | 13 | 740 | Turnip v26.3.0 R3 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.0 | Runs | 30.1 | [#117](https://git.eden-emu.dev/moceure/BananaNative/issues/117) |  |
| AYN Thor | 13 | 740 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.2.8 | Fails: out of memory (Android closed it) | none | [#97](https://git.eden-emu.dev/moceure/BananaNative/issues/97) | Reporter HollowDem0n: Snapdragon 8 Gen 2, 16 GB, readiness check Pass. Version 0.2.8. |
| AYN Thor | 13 | 740 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.12 / 0.3.13 | Runs | 50 and 50; 60 | [#182](https://git.eden-emu.dev/moceure/BananaNative/issues/182), [#185](https://git.eden-emu.dev/moceure/BananaNative/issues/185) |  |
| AYN Thor | 13 | 740 | Turnip Gen8 V39 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 31 and 27.7 | [#182](https://git.eden-emu.dev/moceure/BananaNative/issues/182) |  |
| AYN Thor | 13 | 740 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs (one log); fails: crash (another) | 61; 35.1 before the crash; 25 | [#182](https://git.eden-emu.dev/moceure/BananaNative/issues/182), [#198](https://git.eden-emu.dev/moceure/BananaNative/issues/198) | The crash is a SIGSEGV. |
| AYN Thor | 13 | 740 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | test build | Fails: crash (2 logs) and runs (2 logs) | 33.9 before the crash; 13.3 | [#249](https://git.eden-emu.dev/moceure/BananaNative/issues/249) | A test build, not a release. |
| AYN Thor | 13 | 740 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | test build | Runs, slow | 7.5 | [#249](https://git.eden-emu.dev/moceure/BananaNative/issues/249) | A test build, not a release. |
| AYN Thor | 13 | 740 | Turnip T30 | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.3.11 | Runs | 59.6 | [#203](https://git.eden-emu.dev/moceure/BananaNative/issues/203) |  |
| AYN Odin2 | 13 | 740 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.22 | Runs, slow (3 logs) | 13.7, 60, 13.1 | [#241](https://git.eden-emu.dev/moceure/BananaNative/issues/241) |  |
| Moorechip Retroid Pocket 6 | 13 | 740 | Turnip T30 (and T23, T27) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.2.6 to 0.3.11 | Runs, slow (several logs; two ended in out-of-memory kills) | 3 to 12 | [#76](https://git.eden-emu.dev/moceure/BananaNative/issues/76) | Low numbers across all driver versions; the logs also show Android closing the game for memory. |
| Moorechip Retroid Pocket Nova | 13 | 740 | Turnip (Mesa 26.3.0 r7 build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.1 | Runs, slow | 14.9 | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131), [#180](https://git.eden-emu.dev/moceure/BananaNative/issues/180) |  |
| Moorechip Retroid Pocket Nova | 13 | 740 | Turnip (Mesa 27.0.0 r4 build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs, slow | 18.2 | [#180](https://git.eden-emu.dev/moceure/BananaNative/issues/180) |  |
| Moorechip Retroid Pocket Nova | 13 | 740 | Turnip (Mesa 26.3.0 r7 build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.12 | Runs | 29.2 | [#180](https://git.eden-emu.dev/moceure/BananaNative/issues/180) |  |
| Moorechip Retroid Pocket Nova | 13 | 740 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.12 | Runs, slow | 15 | [#180](https://git.eden-emu.dev/moceure/BananaNative/issues/180) |  |
| samsung SM-S918B | 14 | 740 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 30 | [#132](https://git.eden-emu.dev/moceure/BananaNative/issues/132), [#185](https://git.eden-emu.dev/moceure/BananaNative/issues/185) | Two issues. |
| samsung SM-S918B | 17 | 740 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.14 | Fails: GPU driver lost | 31.8 before the loss | [#211](https://git.eden-emu.dev/moceure/BananaNative/issues/211) |  |
| samsung SM-S918B | 17 | 740 | Turnip T30 (no-UBWC build) | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.14 | Runs, slow | 12.2 | [#211](https://git.eden-emu.dev/moceure/BananaNative/issues/211) |  |
| samsung SM-X710 | 16 | 740 | Turnip Gen8 V39 (patched) | [Mario Kart World](Game-Mario-Kart-World) | 0.3.14 | Runs | 29.9 | [#215](https://git.eden-emu.dev/moceure/BananaNative/issues/215) |  |
| samsung SM-X710 | 16 | 740 | Turnip T30 | [Mario Kart World](Game-Mario-Kart-World) | 0.3.14 | Runs (7 logs) | 23.6 to 30 | [#215](https://git.eden-emu.dev/moceure/BananaNative/issues/215) |  |
| samsung SM-S928B | 17 | 750 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.14 | Runs, slow | 6.7 | [#217](https://git.eden-emu.dev/moceure/BananaNative/issues/217) | The log has many [RED] lines. |
| samsung SM-S928U | 17 | 750 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.11 | Fails: GPU driver lost | 60.5 before the loss | [#154](https://git.eden-emu.dev/moceure/BananaNative/issues/154) |  |
| LENOVO TB520FU | 17 | 750 | Turnip T30 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.20 | Runs | 27.7 | [#213](https://git.eden-emu.dev/moceure/BananaNative/issues/213) |  |
| LENOVO TB520FU | 17 | 750 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Fails: GPU driver lost | 59.5 before the loss | [#213](https://git.eden-emu.dev/moceure/BananaNative/issues/213) |  |

## Adreno 720 / 725 (mid-range)

| Device | Android | GPU (Adreno unless noted) | GPU driver | Game | App version | Result | fps (log median) | Issue | Notes |
|---|---|---|---|---|---|---|---|---|---|
| motorola edge 50 pro | 16 | 720 | Turnip T30 | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.2.4 | Fails: GPU driver lost | 34 before the loss | [#69](https://git.eden-emu.dev/moceure/BananaNative/issues/69) |  |
| Xiaomi 23049PCD8G | 17 | 725 | Turnip v863.1 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.0 / 0.3.13 / 0.3.14 | Fails: GPU driver lost (3 logs) | 30 | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131), [#188](https://git.eden-emu.dev/moceure/BananaNative/issues/188), [#191](https://git.eden-emu.dev/moceure/BananaNative/issues/191) |  |
| Xiaomi 23049PCD8G | 17 | 725 | Turnip v863.1 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.11 | Runs, slow | 20.1 | [#175](https://git.eden-emu.dev/moceure/BananaNative/issues/175) |  |
| Xiaomi 23049PCD8G | 17 | 725 | system | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Fails: GPU driver lost | 30.1 before the loss | [#191](https://git.eden-emu.dev/moceure/BananaNative/issues/191) |  |
| Xiaomi 23049PCD8G | 17 | 725 | Anka Turnip 1.0.0 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs, slow | 16.7 | [#196](https://git.eden-emu.dev/moceure/BananaNative/issues/196) |  |

## Mali and Xclipse (not Adreno)

| Device | Android | GPU (Adreno unless noted) | GPU driver | Game | App version | Result | fps (log median) | Issue | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Xiaomi 2412DPC0AG | 16 | Mali-G720 MC7 | PanVK faux123 v1.1.6 | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.3.13 | Runs | 49.6 and 59.6 | [#154](https://git.eden-emu.dev/moceure/BananaNative/issues/154), [#184](https://git.eden-emu.dev/moceure/BananaNative/issues/184) | Two logs; the project's guide asks for an Adreno GPU. |
| samsung SM-S942B | 17 | AMD Radeon (Xclipse), RADV | RADV Xclipse Mesa 27.0.0-devel | [Ocarina of Time](Game-Zelda-Ocarina-of-Time) | 0.2.6 | Fails: GPU driver lost | 59.8 before the loss | [#82](https://git.eden-emu.dev/moceure/BananaNative/issues/82) |  |
| samsung SM-A566B | 17 | AMD Radeon (Xclipse), RADV | RADV Xclipse Mesa 27.0.0-devel | [Pokémon Pokopia](Game-Pokemon-Pokopia) | 0.3.1 | Unclear | 0.4 | [#251](https://git.eden-emu.dev/moceure/BananaNative/issues/251) | The log has a timeline probe warning at the start and no error line. |

## Patterns across the reports

- **Ocarina of Time on the system driver** shows 25 to 40 fps in the logs on Adreno 830 and 840 phones, in line with the game's page. A phone with an Adreno 825 (#290) crashed with a SIGSEGV at the first graphics call on both the system driver and Turnip, and two logs from an AYN Thor on the system driver ended in a SIGSEGV too (#182, #249). Whether these are fixed on the current release is not known from the logs.
- **Donkey Kong Bananza** reports on Adreno 840 phones (issues #91, #93) mostly end with the GPU driver lost after a stretch at 20 to 55 fps, on the system driver and on Turnip. A Nubia phone on Turnip V38 (#207) ran at a log median of 58.
- **Out of memory.** Android closed the game in several OoT reports (#76, #97, #236). A report from a 16 GB phone (#97) measured that version 0.2.8's default of making an image for every texture-pool slot grew the graphics memory; turning that off (`DM_VK_EAGER_TEX=0`) ran stable, at the cost of a darker house interior in that version. Check the current release notes before changing it.
- **Retroid Pocket 6** (#76) ran OoT at 3 to 12 fps in the log medians on several Turnip versions; two logs end with Android closing the game for memory. Other Adreno 740 handhelds in the same reports reached 30 to 60 fps with a Turnip T30 on later versions, so the result depends on version and settings too.
- **Mali and Xclipse** phones reached the frame loop with custom drivers in some logs, but the project's guide asks for a Snapdragon (Adreno) GPU, so treat these as unsupported experiments ([Installation](Installation)).

## How to add your phone

Open an issue with the form, fill in the phone, chip, Android version, GPU driver and release, and attach the log with **Share log**. The maintainers run a log digest on it, which is where the rows above come from. See [Troubleshooting](Troubleshooting).
