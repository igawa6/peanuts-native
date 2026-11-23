# Optimization ideas: the tracker

One rule for all of them: an idea that changes behaviour lands behind a switch and is off unless a measurement says otherwise. An idea is "measured" only when a capture on a
real phone shows it helped; code that compiles and passes its unit tests is "landed", nothing more.

## Read first: much of the first list already exists

Reading the host after the first batch showed that most of the GPU, memory and pacing ideas were already built. The project's own switches are catalogued in
`docs/env_switches.tsv` (about 790 rows) and set from `env.txt` (`DM_*=value`, one per line; `env-<title ID>.txt` per game).

| Idea | Where it already is |
|------|--------------------|
| Memory pressure for caches | the texture cache watches `/proc/pressure/memory` (`DM_VK_IMG_PSI`), limits (`DM_VK_IMG_LIMIT_MB`, `DM_VK_IMG_RESERVE_MB`), low-RAM profile keys (`low_ram_*`, `vka_lowram.h`), file-backed pools (`DM_MEM_FILEBACK`, the RAM saver) |
| What limits the frame rate | the `[vk] pace` and `[vk] pace cost` log lines (`DM_VK_PACE_LOG`, on, every 5 s): GPU busy and span p50/p90, the replay's recording and CPU time, draws, GPU clock floor, ADPF mode, thermal status and headroom, CPU clocks |
| ADPF hint session | `DM_VK_ADPF`, `DM_VK_ADPF_GPU`, `DM_VK_ADPF_PIPELINE` (`vka_perf.c`) |
| GPU clock floor | `DM_VK_GPU_FLOOR` (QCOM queue perf hint, KGSL or ADPF), `DM_VK_GPU_FLOOR_AUTO` |
| UBWC-friendly image creation | `DM_VK_UBWC` (`vka_tex.c`) |
| Graphics pipeline libraries, vertex input dynamic state, push descriptors | `DM_VK_GPL`, `VK_EXT_vertex_input_dynamic_state`, `VK_KHR_push_descriptor` |
| Background pipeline compiles, pipeline cache file | `DM_VK_PSO_ASYNC`, `DM_VK_CS_ASYNC`, `DM_VK_PCACHE*` |
| Command encoder thread, replay priority | `DM_VK_ENC*`, `DM_VK_REPLAY_NICE` |
| Display rate hint, frame limit, frame generation, upscalers | `DM_VK_FRAME_RATE`, `DM_FPS_LIMIT`, `DM_LSFG*`, `DM_VK_FSR`, `DM_VK_NDSR` |
| Fast cores and affinity, thread priorities | `CpuCores`, `sched_setaffinity` in `vka_worker.c` and `nxlaunch.c`, `setpriority` |
| Unbuffered joystick input, game category, thermal headroom in telemetry | `GameActivity.java`, the manifest's `appCategory`, `MasterTelemetry.java` |

So the code of batch 1 mostly duplicates them: `peanuts_mem.c` (the reader of the memory level) and `peanuts_bottleneck.c` (the classifier) are tested and compile into the
APK but nothing calls them, and `PN_OPTS` is a second switch mechanism beside the `DM_*` one. They stay for Peanuts-only code that has no `DM_*` home; do not build on them
before checking `env_switches.tsv`.

## Landed

| Idea | Switch | Where | Status |
|------|--------|-------|--------|
| The host's own C at -O2 or -O3 (it is -O1, as the Mac host; only the Vulkan replay is -O2) | Gradle `-PnxHostOpt=2\|3`, CMake `-DNX_HOST_OPT`, workflow input `host_opt` | `android/host/CMakeLists.txt` | built; **not measured**. A build-time A/B: install the -O1, -O2 and -O3 builds side by side (different `app_id`) and compare the frame times and the audio |
| Memory level reader, bottleneck classifier, `PN_OPTS` | `PN_OPTS` | `dm/peanuts/peanuts_*.c` | unit tested on a PC, not wired (see above) |

## Real gaps found (not in the host)

Perfetto/ATrace sections around record, submit and present; `madvise` cold or pageout of unused pools; transparent huge pages (most Android kernels build without them);
I/O priority of the asset readers; futex priority inheritance; `setSustainedPerformanceMode` (whether Samsung's devices honour it is unchecked); ThinLTO and PGO of the host
(the linker map and the assembly may object); a pack read order that follows the first run's reads. Each needs a look at the code around it and a capture before it is worth
the risk.

## Everything else from the lists

The remaining ideas (per-pass GPU timestamps, command buffer reuse, shadow map reuse, instancing of similar draws, per-pass resolution, variable rate shading, texture
recompression, auto-tuner, crash auto-bisect, soak test, image regression, one-tap report, and so on) are **planned**. Before any of them is started, search
`docs/env_switches.tsv`, `docs/reference/rendering.md` and the `vka_*` files: several will turn out to exist.

## Blocked or deferred

* **Merging every Switch emulator line by line.** Ryujinx is C#, the Yuzu family is C++ forks of one tree: the useful path is the best commits of the Yuzu family on top of one
  base, and ideas (not code) from Ryujinx. A separate backend, started as an intent to an installed emulator.
* **JIT, block linking, fastmem, superinstructions, translator PGO.** The host runs the guest's ARM64 code natively; these have nothing to attach to until an emulation path exists.
* **Keys and firmware handling.** `AGENTS.md` forbids adding anything that asks for keys; the importer reads dump folders. A change of that rule is the owner's decision.
* **Licence.** `LICENSE` says MIT and the README says GPL-3.0-or-later. Settle it before any GPL code from another emulator comes in.
* **Closed apps (GameHub and the like).** Public ideas only; no decompiled code.
