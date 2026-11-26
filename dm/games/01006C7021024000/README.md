# MotoGP 25

## Found on this game

On Android with the Vulkan host, the player's wearpack showed noisy pixels in customization and gameplay; after the host's block-compressed alias ordering was corrected, the same run rendered normally in customization, Grand Prix (factory team and custom rider), career, and on track.

- **Device run:** `wearpack-review-20261126-212148` — Samsung Galaxy S23 (SM-S911B), Snapdragon 8 Gen 2 / Adreno 740, 8 GB RAM, Android 16 (SDK 36).
- **Driver:** PurpleVK 26.3.0-devel (`git-62ac221a33`), Vulkan 1.4.359, custom driver through `DM_VK_DRIVER` (`Turnip_Adreno_Driver_T30_Mr_Purple_666_`).
- **App and host:** `0.1-bringup` (`b269882b41a0`), app ID `dev.nxnative.android.motogp25native`, shader chain `f36b2a1bcf19`.
- **Game:** build `v262144`, version 1.0.4, main build ID `499A85D046C70186`; SDK nnSdk 18.3.1.
- **Log:** the run log and its digest are attached to the pull request. The digest reports readiness PASS, eight frame loops reached, no unexpected `[RED]` groups (13 expected Android stubs), 6,233 frames counted at a 30 fps target, and ends with `stopped by the user`; the phone was hot five times during that run.
- **Coverage:** one device and one custom driver. The system driver, other phones, and other games on this branch were not checked.

- **Profile build:** `v262144`
- **SDK:** 18
- **Graphics:** NVN
- **Finding date:** 2026-11-26
