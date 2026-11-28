#!/bin/sh
# PEANUTS: builds unoodle, the Oodle decoding worker (README.md): build.sh <output program> [compiler and its flags...]
#   (default compiler: $CXX, else c++). peanuts/scripts/peanuts_build.py builds peanuts/.build/ooz/unoodle through it, peanuts/tests/test_unoodle.py a copy
#   for its tests. Needs a C++ compiler for x86-64 or arm64 (gcc or clang), patch, and sha256sum or shasum.
# An output ending in .so is the phone's form, libunoodle.so: the same worker as a shared library whose unoodle_main() serves the pipe protocol on stdin and
#   stdout, run by a worker process that loads it (dm/gfx/tools/ooz_split.py; android/ondevice/build.sh passes <NDK>/clang++ --target=aarch64-linux-android33).
#   The target is arm64 when the compiler's flags name aarch64 or arm64, else this machine's. Both forms carry UNOODLE_SOURCE, the SHA-256 of this folder's
#   sources in a fixed order (SOURCES below), which build.gradle checks the phone's library against.
# kraken.cpp and stdafx.h are checked against the hashes of the pinned upstream commit, copied into <output program>.src, patched there (ooz-port.patch,
# then ooz-guards.patch; on arm64 also ooz-arm64.patch, with ooz_neon.h: the decoder's SSE2 intrinsics in NEON) and compiled with unoodle.cpp. Nothing in
# this folder is changed or run.
# Exit code 3 = not built because this machine cannot (neither x86-64 nor arm64), with a message; any other failure is 1.
set -eu
OUT=${1:?usage: build.sh <output program> [compiler and flags...]}; shift
[ $# -gt 0 ] || set -- "${CXX:-c++}"
Z=$(cd "$(dirname "$0")" && pwd)
SOURCES="kraken.cpp stdafx.h unoodle.cpp ooz-port.patch ooz-guards.patch ooz-arm64.patch ooz_neon.h build.sh"   # PEANUTS: UNOODLE_SOURCE (#120)
MACHINE=$(uname -m)
case "$*" in *aarch64*|*arm64*) MACHINE=arm64;; esac   # a cross build for the phone
case $MACHINE in
	x86_64|amd64) ARCH=x86; ARCH_FLAGS=-msse4.1;;
	arm64|aarch64) ARCH=arm64; ARCH_FLAGS=;;   # ooz_neon.h: the SSE2 intrinsics the decoder uses, in NEON (ooz-arm64.patch)
	*) echo "unoodle: not built on $(uname -m): ooz's decoder uses x86 SSE2 intrinsics, which this folder has for x86-64 and arm64 (ooz_neon.h) only"; exit 3;;
esac
command -v patch >/dev/null 2>&1 || { echo "unoodle: the build needs the patch program on PATH"; exit 1; }
sum() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
check() { [ "$(sum "$Z/$1")" = "$2" ] || { echo "unoodle: $Z/$1 is not the pinned upstream file (sha256 $2)"; exit 1; }; }
check kraken.cpp 20c48a30db54efa7681f8c9c674f2a16f081373872d033885a6fdf244b9cff01
check stdafx.h 92b798615ee1ac90ed53a2685fc62dfbe7133d5ba69e567733d8081d420cf701
# PEANUTS: the source SHA-256 and the phone's library form (#120)
SRC_SHA=$(cd "$Z" && cat $SOURCES | if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | cut -d' ' -f1)
case "$OUT" in
	*.so) LIB_FLAGS="-shared -fPIC -fvisibility=hidden -DUNOODLE_LIBRARY"
		case "$*" in *android*) LIB_FLAGS="$LIB_FLAGS -static-libstdc++ -Wl,-soname,$(basename "$OUT") -Wl,-z,max-page-size=16384";; esac;;
	*) LIB_FLAGS=;;
esac
mkdir -p "$(dirname "$OUT")"
OUT=$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")
S=$OUT.src
rm -rf "$S"; mkdir -p "$S"
cp "$Z/kraken.cpp" "$Z/stdafx.h" "$Z/unoodle.cpp" "$S/"
patch -s -d "$S" -p1 --no-backup-if-mismatch < "$Z/ooz-port.patch"
patch -s -d "$S" -p1 --no-backup-if-mismatch < "$Z/ooz-guards.patch"
if [ "$ARCH" = arm64 ]; then
	cp "$Z/ooz_neon.h" "$S/"
	patch -s -d "$S" -p1 --no-backup-if-mismatch < "$Z/ooz-arm64.patch"
fi
# -fno-strict-aliasing: the decoder reads and writes through casted pointers throughout; -fwrapv: it was written for MSVC, which wraps signed overflow;
# PEANUTS: -fno-strict-overflow: pointer arithmetic wraps too. Leviathan adds negative offsets held in size_t to dst (&dst[last_offset], dst + offset); clang at -O1
# and above treats that pointer overflow as impossible and miscopies the matches (Minecraft Dungeons II on arm64 macOS: 2,110 of 2,126 streams wrong; the
# same flag as PR #889). These are the flags of the validated build (README.md), -msse4.1 on x86-64 only. Compiled from inside the copy with relative names,
# so no path of this machine is in the program.
( cd "$S" && "$@" -O2 -g0 -fno-strict-aliasing -fwrapv -fno-strict-overflow $ARCH_FLAGS $LIB_FLAGS -DUNOODLE_SOURCE="\"$SRC_SHA\"" -w -I . -o "$OUT.tmp" unoodle.cpp kraken.cpp )
mv "$OUT.tmp" "$OUT"
rm -rf "$S"
