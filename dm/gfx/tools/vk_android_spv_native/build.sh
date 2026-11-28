#!/bin/sh
# Peanuts Native: builds libdmsh_spvn (spvn.c: vk_android_spv.py's module and post-passes in C, which vk_android_spvn.py loads through ctypes; android/SHADERS.md,
# Native post-passes). The library carries spvn.c's SHA-256 (spvn_source), which vk_android_spvn.py and build.gradle check against the tree's spvn.c.
# Usage: build.sh <output .so / .dylib> [compiler and its flags...]   (default: cc, the host's; the phone's: android/ondevice/build.sh passes
#        <NDK>/clang --target=aarch64-linux-android33). A computer needs no build: vk_android_spvn.py compiles spvn.c into its cache folder itself.
set -e
OUT=${1:?usage: build.sh <output library> [compiler...]}; shift
HERE=$(cd "$(dirname "$0")" && pwd)
[ $# -gt 0 ] || set -- cc
SHA=$(python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$HERE/spvn.c")
case "$*" in
  *android*) EXTRA="-Wl,-soname,libdmsh_spvn.so -Wl,-z,max-page-size=16384" ;;
  *) EXTRA="" ;;
esac
case "$(uname)" in Darwin) case "$*" in *android*) ;; *) EXTRA="$EXTRA -undefined dynamic_lookup" ;; esac ;; esac
"$@" -shared -fPIC -O2 -std=gnu11 -Wall -Wextra -Werror -fvisibility=hidden -ffile-prefix-map="$HERE"=. -DSPVN_SOURCE="\"$SHA\"" $EXTRA -o "$OUT" "$HERE/spvn.c"
echo "$OUT: spvn.c $SHA"
