# Optimization ideas: the tracker

Every idea for speed, memory and stability that has come up for Peanuts Native, with where it stands. **One rule for all of them:** an idea lands behind a switch in
`dm/peanuts/peanuts_opt.h` (read from the `PN_OPTS` environment variable) and is off by default, so a build that carries many ideas behaves like the build before
them until one is switched on. An idea is "measured" only when a capture with the telemetry shows it helped on a real phone; code that compiles and passes its unit
tests is "landed", nothing more.

Status words: **landed** (code in the tree, unit tested on a PC, off by default), **planned** (not started), **needs a phone** (cannot be judged without a capture),
**exists** (the host already does it), **blocked** (a rule or a licence in the way).

## Landed

| # | Idea | Switch | Where | Wiring still to do |
|---|------|--------|-------|--------------------|
| 11, 60 | Memory-pressure level for caches (PSI, else MemAvailable) | `mem_watch` | `peanuts_mem.c` | a low-rate tick calling `pn_mem_poll`, and the caches that read `pn_mem_last` (texture, shader, pools) |
| 72, 73 | Classifier of what limits the frame rate (CPU, GPU, present, other stall) | `bottleneck` | `peanuts_bottleneck.c` | CPU submit time, GPU timestamp queries and present wait fed per frame from the Vulkan replay, a line in the HUD and the telemetry log |

## Already in the host (found by the audit)

Persistent pipeline cache and prewarm, frame cap and frame lock, frame-time HUD and JSONL log, resolution scale, SGSR and FSR upscalers, frame generation,
file-backed memory pools, uncompressed pack members, ahead-of-time shader libraries, spin-wait rewrite, driver choice.

## Planned, by area

Numbers are the ones used in the conversation. Items marked with a star need a phone to judge.

**Measurement.** Per-pass GPU timestamps (73), frame timeline coloured by cause (72), light frame capture for reports (74), one-tap report as a GitHub issue (35),
frame replay and session record (34), image regression with SSIM (14), overnight soak test (15), black box ring of the last syscalls and submits (16), energy per
profile in mAh per hour (77), driver quirks table in JSON (76), driver matrix per game (36).

**GPU.** Pipeline library and dynamic state (graphics pipeline library, extended dynamic state 1 to 3), dynamic rendering with local read and transient
attachments, avoid what turns off Adreno's UBWC, minimal barriers and timeline semaphores, secondary command buffers recorded on several threads, push descriptors or
descriptor buffers (50), relaxed precision where the game allows (29), BCn decoded in compute and asynchronous uploads (62), sampler cache (45), image and buffer pools
(41), skip empty draws (42), skip unused resolves (43), copy elimination (40), layout tracking (49), pre-rotation (46), overlay present path (47), upscaler in compute
(38), smaller intermediate formats (39), shadow map reuse (37), instancing of similar draws (26), skip unchanged uniform uploads (27), per-pass resolution (5),
variable rate shading (6), command buffer reuse by content hash (4, risky), depth pre-pass test (28, may hurt).

**Shaders and loading.** Compile during the loads, from the recorded first seconds after each load (2), compile on the small cores (3), crowd-sourced prewarm lists
as hashes only (1), pack reordered by the real read order (18), symbol ordering of the host library from the start-up profile (19), nightly cache builder on the
charger (20), mmap read-ahead and a decompression pool (I/O), pipeline cache with a checksum and automatic discard (69).

**CPU and system.** Affinity by measurement (7), ADPF hint sessions, thermal headroom ahead of the throttle (30), game category for Samsung's game mode, Horizon cores
mapped to Android cores (21), futex mutexes with priority inheritance (22), transparent huge pages (23, often disabled by kernels), own allocator in the host (24),
PC sampling of the game's threads (8), NEON for hot SDK functions, userfaultfd write protection instead of mprotect (52), clock read straight from the counter (53),
I/O priority of the asset readers (54), quieter UI and telemetry while playing (55), a note about background apps (56), dedicated core for the present thread (57),
sensor polling at the game's rate (58), cpufreq limit markers on frames (59), profiles per power source (31), external display profile (80).

**Memory (goal: a 6 GB phone).** Drop the top mip under pressure (9), recompress BCn to ASTC while preparing (10), caches that follow the pressure level (11),
compressed cold pages (12, only for cold pools), mmap the pack read-only so pages are file-backed (61, probably the largest saving), texture deduplication by hash
(62), on-demand audio decode (63), per-game ceilings for heaps (64), a "6 GB" bundle of limits plus a measured list of games that fit (65), give memory back with
madvise and react to onTrimMemory, foreground service so the app is not killed, leak detector over long sessions.

**Stability.** Auto-bisect of a crash with safer settings each retry (13), save backup before experimental sessions (17), hang watchdog with backtraces (66), HWASan
and ASan development builds (67), Vulkan validation build (68), safe-start gesture (70), Android 13 to 16 test matrix (71), fuzzing of the IPC services (33), nightly
upstream rebase bot (32).

**Input and comfort.** Late latching of input (78), rumble through VibrationEffect.Composition (79), unbuffered input dispatch, game-rate sensor polling.

**Build.** O3, LTO and PGO of the host libraries with a profile from a real phone, conservative CPU flags with run-time dispatch, compiler and Gradle caches in the
workflows, a stable signing key.

## Blocked or deferred

* **Merging every Switch emulator line by line.** Ryujinx is C#, the Yuzu family is C++ forks of one tree: the useful path is the best commits of the Yuzu family on top of
  one base, and ideas (not code) from Ryujinx. A separate backend, started as an intent to an installed emulator; not before the host's own numbers exist.
* **JIT, block linking, fastmem, superinstructions, translator PGO.** The host runs the guest's ARM64 code natively; these have nothing to attach to until an emulation
  path exists.
* **Keys and firmware handling.** `AGENTS.md` forbids adding anything that asks for keys; the importer reads dump folders. A change of that rule is the owner's decision.
* **Licence.** `LICENSE` says MIT and the README says GPL-3.0-or-later. Settle it before any GPL code from another emulator comes in.
* **Closed apps (GameHub and the like).** Public ideas only; no decompiled code.
