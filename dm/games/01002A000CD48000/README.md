# Resident Evil 6 (`01002A000CD48000`)

<!-- PEANUTS: Resident Evil 6 profile note -->

## Status

The original bring-up reached gameplay at about 58 fps on Turnip with both tool fixes below. The profile uses the game's own pacing. Per-cap frame times and game speed are pending.

### Memory layout

- **Symptom:** Startup failed after the heap allocation returned `ResultLimitReached`.
- **Cause:** The game asks for the application pool minus reported used memory, and it uses the heap pointer without checking the result. The three rewritten modules occupy 74 MiB, above the default 64 MiB allowance.
- **Fix:** Set `mem_used` to `0x5000000` (80 MiB) to account for the modules and stacks.
- **Found on:** Resident Evil 6 2.0.0 (v131072).

The remaining profile memory values are survey estimates.

## Tested on

| Device | Chip / GPU | RAM | Android | Driver | Result |
| --- | --- | --- | --- | --- | --- |
| AYN Thor | Snapdragon 8 Gen 2 / Adreno 740 | 12 GB | 13 | Turnip 26.3.0-T30-1.4.359 (PurpleVK git-62ac221a33) | Earlier bring-up, gameplay at about 58 fps |
| AYN Thor | Snapdragon 8 Gen 2 / Adreno 740 | 12 GB | 13 | System and Turnip versions TBD | PR verification pending |

## Builds

Update 2.0.0 (v131072), SDK 6.5.0, is the package build. The profile lists build IDs for `main`, `subsdk0` and `sdk`. Capcom's MT Framework renders through NVN.

### Found on this game

- Shader extraction requires the MT Framework ARC v7 reader that supports 0x50-byte entries (#653).
- The Android rewriter requires the scheduled offset jump-table resolver for the distant unsigned guard in `main` (#654).

## Open

- Repeat the device run with this profile on the system and Turnip drivers. Record the exact versions, build ID, gameplay, frame times, game speed and peak memory. Collect the per-driver pipeline keys and logs.
- Check companion availability and behavior on this build.
- Other game builds are untested.
