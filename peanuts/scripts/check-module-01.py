#!/usr/bin/env python3
"""Static architecture/compatibility gate for Peanuts Native Module 1.

This does not claim device compatibility from a successful compile. It checks
that the Android ABI boundary, generic/per-title host modes, runtime-profile
schema guards, per-title readiness metadata and thin-overlay rules remain wired.
The full profile equivalence test remains check-runtime-profile.sh.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
errors = []
checks = 0

def require(ok, message):
    global checks
    checks += 1
    if not ok:
        errors.append(message)

def read(path):
    p = ROOT / path
    require(p.is_file(), f"missing required architecture file: {path}")
    return p.read_text(encoding="utf-8") if p.is_file() else ""

host = read("android/host/CMakeLists.txt")
launcher_cmake = read("android/app/src/main/cpp/CMakeLists.txt")
gradle = read("android/app/build.gradle")
profile_h = read("dm/peanuts/profile/profile_rt.h")
profile_c = read("dm/peanuts/profile/profile_rt.c")
generic_h = read("dm/peanuts/profile/game_profile_generic.h")
generator = read("dm/peanuts/profile/gen_runtime_profile.py")
peanuts_rules = read("PEANUTS.md")
readiness_test = read("dm/tools/tests/test_android_readiness.py")
games_test = read("dm/tools/tests/test_android_games.py")

# Platform boundary: Android native host is intentionally 64-bit ARM only.
require('CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a"' in host,
        "Android host must explicitly reject ABIs other than arm64-v8a")
require("ANDROID_PLATFORM=33" in host and "API 33+" in host,
        "Android native minimum platform/API 33 contract is missing")
require("NX_GENERIC_HOST" in host and "NX_GAMES" in host and "GAME" in host,
        "CMake must retain generic-host and per-title host build modes")
require("NX_GENERIC_HOST" in launcher_cmake and "arm64-v8a" in launcher_cmake,
        "launcher CMake must link the generic/per-title host and enforce the Android ABI")

# App packaging: Peanuts defaults to a single generic host with title-specific
# runtime profiles; the opt-out remains available for per-title hosts.
require("getOrElse(providers.gradleProperty('nxApp').getOrElse('') == 'peanuts' ? 'true' : 'false')" in gradle,
        "Peanuts app must default to the generic host")
require("nxGenericProfiles" in gradle and "profile.bin" in gradle and "assets" in gradle,
        "generic APK must generate/package per-title runtime profiles")
require("-PnxGenericHost=false" in gradle,
        "per-title host fallback must remain selectable")

# Runtime profile ABI/schema safety: binary layout changes must be versioned,
# and malformed profiles must be rejected before use.
require("DM_PROFILE_MAGIC" in profile_h and "DM_PROFILE_SCHEMA_VERSION" in profile_h,
        "runtime profile must carry magic and schema version")
require("_Static_assert" in profile_h and "DM_PROFILE_LAYOUT_SIZE" in profile_c,
        "runtime profile C layout must be checked at compile time")
require("g_profile.schema != DM_PROFILE_SCHEMA_VERSION" in profile_c,
        "runtime loader must reject schema mismatches")
require("check_strings(&g_profile)" in profile_c and "table row count" in profile_c,
        "runtime loader must validate strings and fixed-capacity row counts")
require("DM_RUNTIME_PROFILE" in generic_h and "g_profile." in generic_h,
        "generic host must map game macros to the loaded runtime profile")
require("def check_all" in generator or "check-all" in generator,
        "runtime profile generator must retain its all-profile macro/schema guard")

# Per-title compatibility: readiness metadata is the source of capability
# requirements; automated tests ensure every row maps to a reported fact.
profile_dirs = sorted(p for p in (ROOT / "dm/games").iterdir()
                      if p.is_dir() and re.fullmatch(r"[0-9A-Fa-f]{16}", p.name))
require(bool(profile_dirs), "no 16-digit game profile directories found")
missing = [p.name for p in profile_dirs if not (p / "game.tsv").is_file()]
require(not missing, "profiles missing game.tsv: " + ", ".join(missing[:20]))
ready = [p for p in profile_dirs if (p / "readiness.tsv").is_file()]
require(bool(ready), "no per-title readiness.tsv metadata found")
require("probe_facts" in readiness_test and "test_rows" in readiness_test,
        "readiness regression tests must validate reported capability facts and profile rows")
require("test_every_supported_game_by_default" in games_test,
        "default game inventory regression test is missing")

# Fork architecture: preserve the thin overlay contract rather than silently
# importing broad upstream changes into the fork.
require("All new code lives in Peanuts-only places" in peanuts_rules,
        "PEANUTS overlay ownership rule is missing")
require("one-liners, each tagged" in peanuts_rules and "Never reformat or" in peanuts_rules,
        "PEANUTS upstream-hook / no-reformat boundaries are missing")

if errors:
    print(f"MODULE 1 ARCHITECTURE GATE: FAIL ({checks} checks)")
    for error in errors:
        print(" - " + error)
    sys.exit(1)
print(f"MODULE 1 ARCHITECTURE GATE: PASS ({checks} checks)")
print(f"Game profiles discovered: {len(profile_dirs)}; profiles with readiness metadata: {len(ready)}")
print("Device-level compatibility is not inferred by this static gate; run the Android Vulkan/readiness probe on each target device.")
