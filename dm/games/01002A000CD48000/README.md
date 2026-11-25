# Resident Evil 6 (`01002A000CD48000`)

<!-- PEANUTS(upstream-candidate): Resident Evil 6 profile note -->

Update 2.0.0 (v131072), SDK 6.5.0. Capcom's MT Framework renders through NVN. The profile covers `main`, `subsdk0` and `sdk` for this build.

## Android status

The original bring-up played at about 58 fps on an AYN Thor (Adreno 740, Turnip) with the profile and both tool fixes below.

## Memory layout

- **Symptom:** Startup failed after the heap allocation returned `ResultLimitReached`.
- **Cause:** The game asks for the application pool minus reported used memory, and it uses the heap pointer without checking the result. The three rewritten modules occupy 74 MiB, above the default 64 MiB allowance.
- **Fix:** Set `mem_used` to `0x5000000` (80 MiB) to account for the modules and stacks.
- **Found on:** Resident Evil 6 2.0.0 (v131072).

## Found on this game

- Shader extraction requires the MT Framework ARC v7 reader that supports 0x50-byte entries.
- The Android rewriter requires the scheduled offset jump-table resolver for the distant unsigned guard in `main`.
