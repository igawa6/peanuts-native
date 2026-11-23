# Peanuts Native — 10 additional optimization tracks (research, 2026-11-20)

This document extends the existing ten user-defined workstreams. These are engineering candidates, not claims that the feature is already implemented or that a speedup has been measured. Each item must be checked against the current host/Vulkan replay architecture, the exact Android driver, correctness, licensing, and repeatable benchmarks before being enabled by default.

## 1. Persistent Vulkan pipeline cache with strict device identity
Persist `VkPipelineCache` data across launches, keyed by physical-device vendor/device IDs, driver version, pipeline-cache UUID, application build/schema version, and relevant compiler options. Reject stale or mismatched cache blobs and retain a safe cold-start path. Measure pipeline creation count, cache hit rate, startup time, and shader-related hitch frequency. Never share opaque cache blobs across incompatible drivers.
Research: [Vulkan pipeline cache guide](https://docs.vulkan.org/guide/latest/pipeline_cache.html).

## 2. Asynchronous shader compilation and pipeline warm-up
Move expensive translation/compilation away from latency-sensitive render submission where the architecture permits it. Track compile jobs, deduplicate identical shader keys, bound worker count and memory, prioritize shaders observed in the current scene, and optionally warm likely pipelines during loading screens. Preserve ordering and fallback behavior; never stall the render thread waiting on speculative work unless correctness requires it.
Research: [Vulkan pipeline cache guide](https://docs.vulkan.org/guide/latest/pipeline_cache.html).

## 3. Vulkan memory-budget awareness and allocator telemetry
Query supported memory-budget/heap information where available, track allocation size and lifetime by resource class, and expose budget pressure, high-water marks, failed allocations, and fragmentation indicators. Reuse pools and suballocate only where alignment, lifetime, and synchronization rules are satisfied. Use conservative fallbacks when the driver does not expose budget extensions.
Research: [Vulkan memory allocation guide](https://docs.vulkan.org/guide/latest/memory_allocation.html).

## 4. Tile-based GPU render-pass and attachment lifetime optimization
The Adreno is a mobile GPU; test whether render-pass structure, attachment load/store operations, transient attachments, and depth/stencil lifetime allow data to remain on-chip and reduce external-memory bandwidth. Do not discard contents that later passes need. Compare GPU time, bandwidth/power counters where available, and image correctness.
Research: [Vulkan tile-based rendering best practices](https://docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html).

## 5. Synchronization and barrier minimization
Audit every pipeline barrier and queue wait for the narrowest correct source/destination stages and access masks. Batch submissions where this reduces overhead, avoid unnecessary global waits, and evaluate timeline semaphores only where supported and architecturally useful. Never remove a dependency simply to improve a benchmark: missing synchronization creates nondeterministic corruption.
Research: [Khronos efficient pipeline barriers sample](https://docs.vulkan.org/samples/latest/samples/performance/pipeline_barriers/README.html).

## 6. Descriptor/resource binding and draw-call batching
Measure descriptor updates, descriptor-set churn, bindless/resource-table usage, and redundant state changes. Where supported, evaluate descriptor indexing or existing bindless paths; otherwise use compatible descriptor pools and batching. Keep per-device feature checks and a fallback path. The goal is less CPU submission overhead, not blindly enabling every extension.
Research: [Vulkan descriptor indexing guide](https://docs.vulkan.org/guide/latest/descriptor_indexing.html).

## 7. Texture streaming, format selection, and upload scheduling
Profile texture decode/transcode cost, upload bandwidth, staging-buffer reuse, mip availability, and peak residency. Prioritize visible/high-mip data, upload incrementally, and evaluate compressed GPU formats only when the source data and device support a correct conversion. Preserve exact guest format semantics; do not silently lower texture quality or substitute formats that change rendering.
Research: [Android GPU inspector overview](https://developer.android.com/agi).

## 8. Guest-code translation cache and hot-block specialization
Instrument translated guest blocks: compile time, execution count, invalidations, cache hit rate, and code-cache memory. Reuse translations only when all guest code/version and CPU-feature assumptions match; invalidate on guest writes or mapping changes. Specialize only measured hot paths and retain a generic correctness path. This complements, rather than replaces, the CPU/ARM64 workstream.
Research: [LLVM ORC JIT documentation](https://llvm.org/docs/ORCv2.html).

## 9. Asynchronous game-data I/O, decompression, and prefetch
Trace file reads, archive lookup, decompression, and page faults during load and gameplay. Evaluate bounded background reads, read-ahead for sequential assets, decompression worker pools, and cache eviction based on measured reuse. Avoid speculative prefetch that competes with emulation threads or causes memory pressure on an 8 GB phone.
Research: [Android performance and tracing documentation](https://perfetto.dev/docs/).

## 10. Crash, hang, and regression diagnostics
Add low-overhead breadcrumbs around game startup, shader compilation, Vulkan submission/presentation, memory allocation, and save operations. Capture structured failure categories, last known game/profile/driver identifiers, and bounded diagnostic logs. Add repeatable smoke/regression tests and frame-time baselines; avoid collecting ROM contents, personal files, or unnecessary device identifiers.
Research: [Perfetto memory profiling](https://perfetto.dev/docs/getting-started/memory-profiling).

---

## Cross-cutting implementation rules

- Benchmark with fixed, repeatable scenes and separate cold-cache from warm-cache runs.
- Report median, P95, P99/1% low, frame-time variance, peak RSS, allocation failures, and thermal state; do not equate interpolated frames with native rendered FPS.
- Compare at least one known-good fallback configuration.
- Feature-detect Vulkan extensions and Android APIs at runtime; no extension is assumed merely because the S22 GPU may support it.
- Keep all experimental switches reversible until correctness and stability are demonstrated.
- Preserve upstream notices and component licenses.

## First concrete implementation in this follow-up

The HUD's frame-time graph now has a P95 metric planned/added alongside 1% low. The percentile helper ignores invalid/non-positive samples and requires at least ten valid samples. JVM tests cover isolated hitches, recurring slow frames, short windows, and invalid samples. This improves diagnosis; it does not itself increase game FPS. The next build must run these tests and the Android compile before this change is considered validated.
