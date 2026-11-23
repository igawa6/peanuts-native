# Module 1 — Architecture, structure and compatibility

## Scope
This module establishes the platform boundary and project architecture used by
the remaining BUILD MASTER modules. It does not claim that every Switch title
is playable, or that a successful APK build proves device-level compatibility.

## Architecture decisions already implemented
- Android native host target: `arm64-v8a`, Android API 33+.
- Two host layouts remain supported:
  - Generic host: one native host/JNI pair, with one runtime profile per title.
  - Per-title host: a host/JNI pair compiled for each selected title.
- Peanuts builds default to the generic host; `-PnxGenericHost=false` keeps the
  per-title fallback available.
- Runtime profiles are generated from the upstream game's public profile
  header contract, not by editing upstream game metadata.
- Runtime profile loading checks magic, schema, exact layout size, string
  termination, fixed-array capacities and bounded row values before the host
  uses the data.
- Per-title readiness metadata describes required/degraded device capabilities.
  The launcher and Vulkan host report facts consumed by the readiness check.
- New Peanuts implementation belongs in Peanuts-owned directories; upstream
  integration remains additive and marked, preserving the thin-overlay/rebase
  contract in `PEANUTS.md`.

## Automated acceptance gates
Run from the repository root:

```sh
python3 peanuts/scripts/check-module-01.py
sh peanuts/scripts/check-profiles.sh
sh peanuts/scripts/check-runtime-profile.sh
python3 -m unittest -v dm/tools/tests/test_android_games.py
python3 -m unittest -v dm/tools/tests/test_android_readiness.py
```

The module-1 gate checks architecture invariants. The profile checks validate
profile metadata, and the runtime-profile equivalence test compares the generic
runtime representation against the existing per-title compile-time profile for
every title. These are complementary checks, not substitutes for one another.

## Device compatibility boundary
The Samsung Galaxy S22 Snapdragon 8 Gen 1 / Adreno 730 is a target device, not a
certified compatibility result. Device readiness requires a real Vulkan probe
and game execution on that hardware. RAM, driver extensions, memory limits,
thermal behavior and per-title requirements must be measured; no universal
"playable" claim is inferred from the device model or from compilation.

## Remaining validation outside this module
- Physical-device Vulkan/readiness probe on the S22.
- Launch and gameplay validation per title and game version.
- Performance measurements and thermal-duration tests (covered by later
  performance/graphics modules).
- The original 80-topic source text must be checked against this module's
  scope before declaring the corresponding original topics complete.


## Component map and ownership

| Layer | Repository location | Responsibility |
|---|---|---|
| Android app and packaging | `android/app` | APK variants, ABI packaging, game inventory, profile assets, JNI launcher and readiness UI |
| Android native host build | `android/host` | NDK/CMake target boundary, host source selection, generic/per-title native libraries |
| Launcher JNI | `android/app/src/main/cpp` | Starts the matching native host; generic mode links `nxhost`, per-title mode links `nxhost_<title>` |
| Upstream host/runtime | `dm/host`, `dm/gfx` | Guest process, OS/HLE services, graphics replay and platform integration |
| Runtime profile ABI | `dm/peanuts/profile` | Versioned profile representation, validation and generic-host accessors |
| Per-game source metadata | `dm/games/<title-id>` | Build IDs, profile metadata, shader map and Android readiness requirements |
| Peanuts companion layer | `dm/peanuts`, `peanuts/` | Additive companion/runtime features and project-specific build, validation and documentation |

## Build and runtime modes

### Per-title mode
- Gradle selects title IDs using `nxGames`.
- Each ID must be exactly 16 uppercase hexadecimal characters and have a source profile.
- CMake builds a matching `nxhost_<id>` and launcher JNI library.
- The selected title profile is compiled into that host. This is the fallback and comparison path for the generic implementation.

### Generic-host mode
- `-PnxGenericHost=true` builds one `libnxhost.so` and one generic launcher JNI library.
- Gradle generates a `profile.bin` for every packaged title and places the files in app assets.
- The selected title's profile is loaded before title-dependent host code uses game-specific values.
- The generic header maps scalar/presence macros to the loaded profile. Array initializer call sites that cannot be runtime macros are explicitly routed to bounded runtime arrays under `DM_RUNTIME_PROFILE`.
- `-PnxGenericHost=false` preserves the per-title path for regression comparison and diagnosis.

## Runtime profile contract

The profile is untrusted, versioned input. The loader must reject it before use if any of these checks fail:
1. File/header magic does not match.
2. Schema version does not match the host.
3. Binary layout size differs from the compiled structure.
4. Required strings are unterminated or invalid.
5. A fixed-capacity table count exceeds its capacity.
6. Row-specific list lengths, dereference counts, slots or offsets exceed documented bounds.

The generator also checks all title headers against its known scalar, presence and array macro inventories. A new upstream profile macro is therefore a deliberate compatibility change: update the generator/schema and generic mapping or array call sites, then regenerate and test every profile. A same-size field reorder/type-width change must still be caught by the schema/layout stamp, not assumed safe.

## Readiness and game inventory

- The default Android game list is the set of title profiles carrying an explicit `status` row of `playable` or `bring-up`.
- Each listed title must include the metadata consumed by the Android build, including build rows and shader map.
- Readiness rows use `required` or `degraded`, a fact key, a minimum value/version and a human-readable reason.
- Fact keys must correspond to values actually reported by the Android launcher or Vulkan host probe. The regression test cross-checks those keys to prevent misspellings and permanent false failures.
- A `bring-up` status means integration work is available for testing; it is not a promise that the title boots or is playable.

## Compatibility and failure policy

- Unsupported ABI or an invalid Android platform configuration is a configure-time failure, not a silent fallback to another ABI.
- Missing title metadata or malformed title IDs are build-time failures.
- Missing/corrupt/incompatible runtime profile data is rejected before it can steer host offsets, loader patches or fixed arrays.
- Missing optional Vulkan prewarm data is non-fatal and must be diagnosable; it may cause first-run shader/pipeline work rather than being treated as a device certification failure.
- Readiness facts distinguish hard requirements from degraded capabilities; the app must not label a title playable merely because an APK installed.
- The generic host is not assumed equivalent by declaration: `check-runtime-profile.sh` compares its runtime representation against the per-title compile-time profile across the supported inventory.

## Reproducible validation sequence

From repository root, in this order:

```sh
python3 peanuts/scripts/check-module-01.py
python3 -m unittest -v dm/tools/tests/test_android_games.py
python3 -m unittest -v dm/tools/tests/test_android_readiness.py
sh peanuts/scripts/check-profiles.sh
sh peanuts/scripts/check-runtime-profile.sh
```

The GitHub Actions `peanuts` workflow runs the architecture gate and the existing profile/runtime checks. A passing workflow is source/build validation only. It cannot measure the S22's actual Vulkan driver, available memory, sustained clocks, thermal throttling, controller latency or title-specific runtime stability.

## Explicit non-goals of Module 1

This module does not implement a new Switch CPU/GPU emulator, promise JIT/AOT recompilation, add game-specific hacks, claim universal title support, certify Turnip/Adreno driver quality, or claim FPS gains. Those belong to later implementation/optimization modules and must be supported by code and repeatable measurements rather than architecture prose.
