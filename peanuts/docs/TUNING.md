# Tuning Super Mario Odyssey on the S22: what to test

Nothing here is measured. Each row is a switch that already exists in the host (`docs/env_switches.tsv` has its full text); the point is to change one thing at a time and
compare the frame times. Put the lines in `env-0100000000010000.txt` (this game) or `env.txt` (every game) in the app's shared files folder
(`Android/data/<package>/files/`); a restart applies "restart" switches. A name that is not in the catalogue gets a `[RED]` line in the game's log.

## 1. Read the numbers first

The game's log already carries a `[vk] pace` line every 5 s (`DM_VK_PACE_LOG`, in ms; `0` off) with the GPU's busy time and span, the replay's CPU time, draws, the GPU clock
floor, the ADPF mode, the thermal status and the CPU clocks, and a `[vk] pace cost` line under it. Two minutes at the same spot, then read: a GPU busy time near the frame
period means the GPU is the limit; a replay CPU time near it means the CPU is; neither means the wait is elsewhere (pipelines compiling, the present, I/O).

## 2. If the GPU is the limit

* Handheld instead of Docked (Game settings): fewer pixels. The game note says Odyssey takes its motion moves only in docked mode.
* The other GPU driver (System or Turnip). Not compared for this game yet.
* `DM_VK_CLASSIC_RP=1` (restart): the passes as classic render passes; a speed-up on Qualcomm's driver, and a lost GPU was seen with Turnip in 1 to 2 of 5 starts on another game.
* `DM_VK_GPU_FLOOR=100` is already the default (`0` leaves the governor); `DM_VK_GPU_FLOOR_AUTO=1` follows each frame's span instead, to save power.

## 3. If the CPU is the limit

* `DM_VK_ENC=0`: the replay records without the encoder thread, to see whether that thread helps or costs.
* `DM_VK_REPLAY_NICE=0` instead of the default `-8`.
* Game settings › Use fast cores (not measured by the project either).
* The host built at -O2 or -O3 (the builds `po2` and `po3`, see IDEAS.md): install beside this one and compare.

## 4. If it stutters

* The first minutes build pipelines: play a second and a third time before judging. Odyssey ships a prewarm list (`vk_prewarm.bin`).
* `DM_VK_PSO_ASYNC=2` (restart) is what Game settings › Background pipelines sets; `DM_VK_PCACHE_SAVE_S=20` is how often the pipeline cache file is saved.
* `DM_FPS_LIMIT=30` for a steady 30 where the rate wanders; the game's logic may run at its own speed, so check that the game does not run slow.

## 5. Memory

* Game settings › RAM saver, or `DM_MEM_FILEBACK=1` with `DM_MEM_FILEBACK_KINDS=shader,texture`: pools in a file, less RAM, writes to storage.
* `DM_VK_IMG_LIMIT_MB=1024` (at least 512): a ceiling on the texture cache.
* Odyssey's profile has none of the `low_ram_*` rows, so `DM_VK_LOW_RAM` changes nothing for it; adding them needs a run that shows what the game keeps.
