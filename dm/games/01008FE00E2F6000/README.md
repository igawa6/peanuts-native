# One Piece Pirate Warriors 4 (`01008FE00E2F6000`)

<!-- PEANUTS: a new Peanuts Native game profile and device-tested note. -->

**Status (current): plays on Android, with sound.**

Base v0 and update 1.8.7 (v1179648) tested on a
POCO Pad X1 (Snapdragon 7+ Gen 3, Adreno 735,
Android 16, Turnip Gen8 V39).

Gameplay, save/load and save import work.
The update pack registers 23 DLC entries.
Luffy Gear 5 was tested successfully in combat.

## Builds

| Module | Version | Build ID |
|---|---|---|
| main | v0 | 89cdfa58be2a5449836991ef1ac87009 |
| main | v1179648 | 033ac7a47cbe4097a9f7d5af316aa585 |
| subsdk0 | Both | 721b196b95fde7248f270bdbcad7ac53 |
| sdk | Both | 0444aea83c4abc8f12a7ac7ba5c0dd3a |

Nintendo SDK 8.3.2, NVN graphics.
Modules: main, subsdk0 and sdk.

## Shaders

The base and update splits produce identical shader
packages and shader keys.

- Compute: 196
- Geometry: 2
- Pixel: 20,336
- Vertex: 2,716
- Total: 23,250 shaders
- SPIR-V modules: 33,699
- All modules validated for Vulkan 1.3.
- Vulkan library: 657.4 MB.
- Profile inputs: d001b0f2e48b.

## Memory

The survey-generated memory layout works on the
tested device.

| Setting | Value |
|---|---|
| mem_reserve | 0x100000000 |
| mem_alias | 0x10000000 |
| mem_stack | 0x10000000 |
| app_pool | 0xCD500000 |
| sys_resource | 0x0 |

The memory layout has not been independently measured.

## Found on this game

The Android presentation limiter offers 30 and 60 fps
through the in-game panel. Both settings were tested
with normal gameplay speed.

Menu audio crackling was resolved in testing with
the following extra environment settings:

    PN_AUDIO_FOLLOW=0
    DM_AUDIO_FEED=0
    DM_DBG_AU_FRAMES=768
    DM_AUDREN_WAIT=1

Disabling audio follow was the final change that
resolved the observed crackling.

## Open

- The system GPU driver has not been tested.
- Not every DLC or chapter has been tested.
- The audio workaround has not been isolated further.
- The memory layout remains the survey's estimate.
- Independent on-device shader rebuilding is untested.
- macOS and iOS have not been tested.
