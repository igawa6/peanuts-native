# Tested devices

Banana Native is developed on one phone and reported on by players with many others. This page says which phones the project itself tests on, and which devices players have reported so far. For each report in detail, see [Device reports](Device-Reports).

## What the project tests on

- The maintainers' test phone is a Snapdragon 8 Elite Gen 5 (Adreno 840) with 16 GB of RAM. The performance numbers in the guides are from it.
- It is tested with the phone's own Qualcomm (system) driver and with the latest Turnip. Other drivers may not work ([GPU drivers](GPU-Drivers)).
- The guide asks for Android 13 or newer, a 64-bit phone with a Snapdragon (Adreno) GPU, and at least 12 GB of RAM (16 GB for Mario Kart World). See [Installation](Installation).
- Frame rates for other phones are "not measured yet" unless a player reported them.

## Devices that players reported

Each row is a device seen in at least one issue log, the GPU the log names, the Android version, which games were reported, and the issue links. "Reached the frame loop" means the game started drawing in at least one log; it does not mean it was playable.

| Device (as the log names it) | GPU | Android | Games reported | Reached the frame loop | Issues |
|---|---|---|---|---|---|
| AYN Thor | Adreno 740 | 13 | Ocarina of Time, Donkey Kong Bananza, Pokopia | Yes, on system and Turnip | [#81](https://git.eden-emu.dev/moceure/BananaNative/issues/81), [#97](https://git.eden-emu.dev/moceure/BananaNative/issues/97), [#117](https://git.eden-emu.dev/moceure/BananaNative/issues/117), [#182](https://git.eden-emu.dev/moceure/BananaNative/issues/182), [#185](https://git.eden-emu.dev/moceure/BananaNative/issues/185), [#198](https://git.eden-emu.dev/moceure/BananaNative/issues/198), [#203](https://git.eden-emu.dev/moceure/BananaNative/issues/203), [#249](https://git.eden-emu.dev/moceure/BananaNative/issues/249) |
| AYN Odin2 | Adreno 740 | 13 | Ocarina of Time | Yes (Turnip T30) | [#241](https://git.eden-emu.dev/moceure/BananaNative/issues/241) |
| AYN Odin3 | Adreno 830 | 15 | Ocarina of Time, Splatoon Raiders | Yes on some Turnip builds; game aborts on two others | [#129](https://git.eden-emu.dev/moceure/BananaNative/issues/129), [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131), [#160](https://git.eden-emu.dev/moceure/BananaNative/issues/160), [#162](https://git.eden-emu.dev/moceure/BananaNative/issues/162) |
| Retroid Pocket 6 | Adreno 740 | 13 | Ocarina of Time | Yes, slowly; two logs end in Android memory kills | [#76](https://git.eden-emu.dev/moceure/BananaNative/issues/76) |
| Retroid Pocket Nova | Adreno 740 | 13 | Ocarina of Time | Yes (Turnip) | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131), [#180](https://git.eden-emu.dev/moceure/BananaNative/issues/180) |
| nubia NX809J | Adreno 840 | 16 | Ocarina of Time, Donkey Kong Bananza | Yes; Donkey Kong Bananza on Turnip V39 lost the GPU driver | [#91](https://git.eden-emu.dev/moceure/BananaNative/issues/91), [#126](https://git.eden-emu.dev/moceure/BananaNative/issues/126) |
| nubia NX733J | Adreno 830 | 16 | Donkey Kong Bananza | Yes (Turnip V38) | [#207](https://git.eden-emu.dev/moceure/BananaNative/issues/207) |
| nubia NP06J | Adreno 840 | 16 | Ocarina of Time | Yes (system) | [#265](https://git.eden-emu.dev/moceure/BananaNative/issues/265) |
| Samsung SM-F971B | Adreno 840 | 17 | Ocarina of Time, Donkey Kong Bananza | Yes; Donkey Kong Bananza lost the GPU driver | [#58](https://git.eden-emu.dev/moceure/BananaNative/issues/58), [#93](https://git.eden-emu.dev/moceure/BananaNative/issues/93) |
| Samsung SM-S931B | Adreno 830 | 16, 17 | Ocarina of Time | Yes | [#132](https://git.eden-emu.dev/moceure/BananaNative/issues/132), [#224](https://git.eden-emu.dev/moceure/BananaNative/issues/224), [#248](https://git.eden-emu.dev/moceure/BananaNative/issues/248), [#264](https://git.eden-emu.dev/moceure/BananaNative/issues/264) |
| Samsung SM-S931W | Adreno 830 | 17 | Ocarina of Time | Yes; later logs end in a crash or out of memory | [#183](https://git.eden-emu.dev/moceure/BananaNative/issues/183), [#236](https://git.eden-emu.dev/moceure/BananaNative/issues/236) |
| Samsung SM-S936U | Adreno 830 | 17 | Ocarina of Time | Game abort after reaching it | [#145](https://git.eden-emu.dev/moceure/BananaNative/issues/145) |
| Samsung SM-S938B | Adreno 830 | 16 | Pokopia | Unclear (short log) | [#44](https://git.eden-emu.dev/moceure/BananaNative/issues/44) |
| Samsung SM-S918B | Adreno 740 | 14, 17 | Ocarina of Time | Yes; one GPU driver lost | [#132](https://git.eden-emu.dev/moceure/BananaNative/issues/132), [#185](https://git.eden-emu.dev/moceure/BananaNative/issues/185), [#211](https://git.eden-emu.dev/moceure/BananaNative/issues/211) |
| Samsung SM-S928B | Adreno 750 | 17 | Ocarina of Time | Yes, slowly | [#217](https://git.eden-emu.dev/moceure/BananaNative/issues/217) |
| Samsung SM-S928U | Adreno 750 | 17 | Ocarina of Time | GPU driver lost | [#154](https://git.eden-emu.dev/moceure/BananaNative/issues/154) |
| Samsung SM-X710 | Adreno 740 | 16 | Mario Kart World | Yes | [#215](https://git.eden-emu.dev/moceure/BananaNative/issues/215) |
| Samsung SM-S942B | AMD Radeon (Xclipse) | 17 | Ocarina of Time | GPU driver lost | [#82](https://git.eden-emu.dev/moceure/BananaNative/issues/82) |
| Samsung SM-A566B | AMD Radeon (Xclipse) | 17 | Pokopia | Unclear | [#251](https://git.eden-emu.dev/moceure/BananaNative/issues/251) |
| Xiaomi M154FF | Adreno 850 (as the reporter wrote it) | 17 | Ocarina of Time | Yes after changing a setting; killed by Android with the defaults of 0.2.8 | [#97](https://git.eden-emu.dev/moceure/BananaNative/issues/97), [#98](https://git.eden-emu.dev/moceure/BananaNative/issues/98) |
| Xiaomi 2509FPN0BC | Adreno 840 | 16 | Donkey Kong Bananza | Yes; most logs end with the GPU driver lost | [#93](https://git.eden-emu.dev/moceure/BananaNative/issues/93) |
| Xiaomi 23049PCD8G | Adreno 725 | 17 | Ocarina of Time | Yes, slowly; several GPU driver lost | [#131](https://git.eden-emu.dev/moceure/BananaNative/issues/131), [#175](https://git.eden-emu.dev/moceure/BananaNative/issues/175), [#188](https://git.eden-emu.dev/moceure/BananaNative/issues/188), [#191](https://git.eden-emu.dev/moceure/BananaNative/issues/191), [#196](https://git.eden-emu.dev/moceure/BananaNative/issues/196) |
| Xiaomi 2412DPC0AG | Mali-G720 MC7 | 16 | Ocarina of Time | Yes (PanVK driver) | [#154](https://git.eden-emu.dev/moceure/BananaNative/issues/154), [#184](https://git.eden-emu.dev/moceure/BananaNative/issues/184) |
| realme RMX5200 | Adreno 840 | 16 | Mario Kart World | Yes (system) | [#159](https://git.eden-emu.dev/moceure/BananaNative/issues/159) |
| vivo I2501 | Adreno 840 | 16 | Pokopia | Yes (system) | [#193](https://git.eden-emu.dev/moceure/BananaNative/issues/193) |
| vivo I2405 | Adreno 825 | 16 | Ocarina of Time | No: crash at the first graphics call (system and Turnip) | [#290](https://git.eden-emu.dev/moceure/BananaNative/issues/290) |
| LENOVO TB323FU | Adreno 840 | 16 | Ocarina of Time | Yes (system) | [#183](https://git.eden-emu.dev/moceure/BananaNative/issues/183) |
| LENOVO TB520FU | Adreno 750 | 17 | Ocarina of Time | Yes on Turnip; GPU driver lost on system | [#213](https://git.eden-emu.dev/moceure/BananaNative/issues/213) |
| motorola edge 50 pro | Adreno 720 | 16 | Pokopia | GPU driver lost | [#69](https://git.eden-emu.dev/moceure/BananaNative/issues/69) |
| motorola razr 60 ultra | Adreno 830 | 16 | Pokopia | Unclear (log stops during start-up) | [#86](https://git.eden-emu.dev/moceure/BananaNative/issues/86) |

Pokopia is Pokémon Pokopia (`0400A3A01EE4C000`); the other games are listed on [Games](Games).

## Notes

- Model names are the strings the phone gives the app, so some are product codes. They are not matched to marketing names here.
- The Adreno number is what the log reports; it is not a promise about the chip's name or memory size.
- A result on an old app version may not hold on the current release. [Device reports](Device-Reports) gives the app version of each row.
- To add your device, open an issue with the form and attach the log from **Share log** ([Troubleshooting](Troubleshooting)).
