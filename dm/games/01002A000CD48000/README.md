# Resident Evil 6 (`01002A000CD48000`)

<!-- PEANUTS: Resident Evil 6 profile note -->

## Status

Update 2.0.0 reached gameplay on both Turnip and the system Qualcomm driver. The game controls frame pacing and the profile offers no frame rate cap overrides.

### Memory layout

- **Symptom:** Startup failed after the heap allocation returned `ResultLimitReached`.
- **Cause:** The game asks for the application pool minus reported used memory, and it uses the heap pointer without checking the result. The three rewritten modules occupy 74 MiB, above the default 64 MiB allowance.
- **Fix:** Set `mem_used` to `0x5000000` (80 MiB) to account for the modules and stacks.
- **Found on:** Resident Evil 6 2.0.0 (v131072).

The remaining profile memory values are survey estimates.

## Tested on

Tests used an AYN Thor with a QCS8550 (Snapdragon 8 Gen 2), Adreno 740, 12 GB RAM and Android 13. The Peanuts test app was built from the fork with upstream 0.1.9 and this profile's rewritten code and shaders. Each session started with an empty pipeline cache.

| Driver | Logged frames | Median p50 / p95 (ms) | Slowest frame (ms) | Median game speed | Peak footprint (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Turnip Adreno Driver T30 (@Mr_Purple_666), 26.3.0-T30-1.4.359 (PurpleVK 26.3.0-devel, git-62ac221a33) | 43,500 | 16.92 / 19.94 | 127.02 | 0.989× | 5,073 |
| Qualcomm system driver (build 69e13475cb, I1df7ad3aa9), first session | 16,200 | 16.87 / 20.38 | 92.05 | 0.9865× | 2,181 |
| Same Qualcomm driver, pipeline key collection session | 12,600 | 16.87 / 19.23 | 115.24 | 0.990× | 2,196 |

Frame time percentiles are medians across 300-frame windows. On Turnip, window p50 ranged from 16.67 to 22.62 ms, median presented fps was 59.2 (lowest window 43.2), and sampled game speed ranged from 0.702× to 1.035× real time. The Turnip session ran about 12 minutes from boot through menus into gameplay and closed normally. The shorter Qualcomm sessions also reached gameplay. Pipeline keys were collected from the Turnip session and the second Qualcomm session.

## Builds

Update 2.0.0 (v131072), SDK 6.5.0, is the package build. The profile lists build IDs for `main`, `subsdk0` and `sdk`. All three phone logs report main build `061D2A06CD700785E987C42CE2D7223100000000000000000000000000000000`. Capcom's MT Framework renders through NVN.

### Found on this game

- Shader extraction requires the MT Framework ARC v7 reader that supports 0x50-byte entries (#653).
- The Android rewriter requires the scheduled offset jump-table resolver for the distant unsigned guard in `main` (#654).

## Open

- Check companion availability and behavior on this build.
- Other game builds are untested.
