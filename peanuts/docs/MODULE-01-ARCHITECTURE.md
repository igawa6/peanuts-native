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
