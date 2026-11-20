<!-- PEANUTS begin: Android bring-up profile. -->
# DRAGON BALL Z: KAKAROT + A NEW POWER AWAKENS SET

## Build and content

- Title `010051C0134F8000`, update 1.70 (`v1048576`), main
  `e99dffa39354b6068f8e7c984c77bc84`, SDK 9.3.1, Unreal Engine 4 on NVN.
- The verified input list includes add-ons `010051C0134F9001` through
  `010051C0134F9014`. Thor reported all 20, and the title confirmed the Trunks
  DLC entitlement.
- The full PC scan with the Oodle worker found 75,430 shaders and 9,650
  vertex/pixel variation pairs. Two unencrypted version 8 Unreal paks
  contain the cooked packages that hold the programs. The reader handles
  Zlib and the Oodle-compressed streams it encounters inside those packages.
  Scanning just those two paks produced the same shader tables and library
  as the full scan.

## Timing

The exact-build FPSLocker rows set `t.MaxFPS`, clear `r.VSync`, and
adjust the dynamic-resolution frame budget. The cutscene setting clears
Unreal's fixed-rate bit while retaining its other flags. Game owned skips
these writes. [FPSLocker Warehouse 1.70 configuration](https://github.com/masagrator/FPSLocker-Warehouse/blob/v4/SaltySD/plugins/FPSLocker/patches/010051C0134F8000/E99DFFA39354B606.yaml)

## Thor

The opening fight and forest dialogue ran on Turnip v26.2.0 R5. The 60 FPS
cap produced about 58–60 FPS in several five-second samples. A cold run
displayed shader preparation and processed 685 pipeline keys before guest
entry in 14 seconds, with zero dropped. Warm-cache startup, the full cap
sweep, and the remaining DLC story checks are open.

## Found on this game

- The title backdrop's color cast reproduced on Turnip v26.2.0 R5 and
  v27.0.0 R2. A temporary direct HDR tone map changed the sky and outfit
  colors, while the vegetation remained unusually green.
- In the forest, Gohan and some foliage showed block corruption. The
  corruption was visible in the scene's material attachments before
  grading and persisted with full render barriers. Several 256×256 BC1
  material inputs already contained scrambled blocks when read back from
  Vulkan, including inputs sampled by fragment shaders `ea4e877840869475`
  and `7c7b1b7eacb6940f`. Both inputs use sparse NVN storage without a
  packaged block height. The guest page contents and upload layout still
  need to be compared to identify where the blocks are corrupted.

<!-- PEANUTS end -->
