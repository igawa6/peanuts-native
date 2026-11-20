<!-- PEANUTS: Android profile and validation for DAVE THE DIVER. -->
# DAVE THE DIVER (`010097F018538000`)

The Peanuts Android profile targets update 1.0.6.1267 (`v3080192`). Its main build ID is `f74b633e480e4e161f80a7c16a467f2a`. The update uses Unity 6000.0.52f1 IL2CPP, FMOD and Nintendo SDK 20.5.29 on NVN. The Android pack loads `main`, the Nintendo SDK movie module (`subsdk0`) and `sdk`.

## PC build

- The importer verified the base, update and six DLC in a single `.nup` containing 5,421 files.
- The updated RomFS yielded 22,061 shader keys from Unity assets. The executable supplied 11 GLSLC shaders, for 22,072 total.
- The Linux build produced a 353 MiB Android pack. All 27,803 SPIR-V modules passed `spirv-val --target-env vulkan1.3`.

## Frame rate and shader startup

- **Game owned** defaults to the original 30 fps. Game settings also offer 20, 30, 40, 45, 60, 90 and 120 fps caps. For the supported executable, `step_ticks_u32` sets Unity's fixed physics timestep to `141120000 / cap` ticks at `[main + 0xa5477e8] + 0x50`. [FPSLocker Warehouse timing patch](https://github.com/masagrator/FPSLocker-Warehouse/blob/master/SaltySD/plugins/FPSLocker/patches/010097F018538000/F74B633E480E4E16.yaml)
- `vk_prewarm.bin` supplies 160 Vulkan pipeline keys recorded through the first dive on Thor. In the empty-cache Turnip test, Peanuts built 75 applicable pipelines in 0.8 seconds before guest entry. Startup prewarm defaults to off on Thor's Qualcomm 676.53 driver.

## Android testing

- **AYN Thor, Turnip v26.2.0 R5.** Started a new main-story save with all six add-ons enabled, reached the first dive and swam for 23 seconds without a crash. The underwater samples ran at about 30 fps.
- **AYN Thor, Turnip v26.2.0 R5, 120 fps cap.** A copy of the boat checkpoint reached the first dive and responded to swim controls. Frame samples stabilized at about 118 fps underwater and about 89 fps on the boat.
- **AYN Thor, Turnip v26.2.0 R5, 20 fps cap.** A warm restart loaded the saved Vulkan cache and reached the first dive from a separate checkpoint copy. The dive ran at about 20 fps and the in-game Performance panel could switch it live to the 120 fps cap.
- **AYN Thor, Qualcomm system driver 676.53.** Loaded a copy of the boat checkpoint with all six add-ons enabled, entered the first dive and swam for 24 seconds without a crash. The underwater samples ran at about 30 fps.
- **AYN Thor, Qualcomm system driver 676.53, 60 fps cap.** The first dive ran at about 60 fps after loading. The boat ran at about 25 fps with this driver.
- Hold A at **Normal Start** and hold X at **Dive**. Short taps leave the prompts open.
- Later chapters and the individual DLC routes remain untested.

## Memory

- The NPDM declares 16 MiB of system resource. The profile uses a 5 GiB guest reservation with 4.375 GiB for virtual address regions and the standard Switch application pool.
- The shared SDK rewriter matches this build's `AddressRegionManager` constructor. The two edits at `sdk+0x145d0c` and `sdk+0x145d78` reduce the reserved node area from 1,022 MiB to 62 MiB.
