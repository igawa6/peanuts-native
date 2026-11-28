# MotoGP 25

<!-- PEANUTS: profile from Android device runs; the BC alias fix is in #824. -->

## Status

Played on Android with the Vulkan host; wearpack and bike numbers render normally in customization, Grand Prix (factory team and custom rider), career and on track. A 16.6-minute run reached frame 28,846 with 28,246 frames presented, no crash, and was left running in the background. Career reached Moto 2. The original wearpack noise was gone after the host fix in #824.

## Tested on

- Samsung Galaxy S23 (SM-S911B), Snapdragon 8 Gen 2 / Adreno 740, **8 GB RAM**, Android 16 (SDK 36). This is below the README's 12 GB recommendation; the runs still reached the frame loop and played normally.
- PurpleVK 26.3.0-devel (`git-62ac221a33`), Vulkan 1.4.359, custom driver selected through `DM_VK_DRIVER` (`Turnip_Adreno_Driver_T30_Mr_Purple_666_`). No run on Qualcomm's system driver was made, so whether the game needs this driver on 8 GB is open.
- App `0.1-bringup`, host commit `1cc826d` (the fix in #824), profile `01006C7021024000`, shader chain `f36b2a1bcf19`.
- Named runs: `wearpack-review-20261126-212148` (pre-review host), `motogp25-pr824-verify-20261127-005555` (reworked host), `01006C7021024000-20261127-163901` (16.6 minutes). Their logs and digests are attached to #841 and reported in #824.

## Builds

- Base game `v262144`, version 1.0.4, main build ID `499A85D046C70186`; SDK nnSdk 18.3.1.
- The profile lists only this build; no update or DLC build was tested.

## Found on this game

On Android with the Vulkan host, the player's wearpack showed noisy pixels in customization and gameplay. After the host's block-compressed alias ordering was corrected, the same modes rendered normally. The general fix and its mechanism are documented in `docs/reference/rendering.md` under "BC alias writes visible to later draws" (in #824).

## Open

- One phone and one custom driver only; the system driver, a phone with 12 GB or more, other builds and other games on this branch were not checked.
- Whether the game requires this driver on 8 GB is unknown; no system-driver run was made.
- Release-list measurements (10 minutes on a named Turnip build with median and slowest frame times, game speed at 30, and memory peak) are not complete here; the phone is below the 12 GB recommendation.
