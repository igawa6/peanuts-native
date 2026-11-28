#!/bin/sh
# Peanuts Native: builds libdmsh_rwspin (rwspin.c: patch_nso.py's spin wait search in C, which patch_nso.py loads through ctypes; docs/reference/rewriter.md,
# "The spin wait search in C"). The library carries rwspin.c's SHA-256 (rws_source), which patch_nso.py (RWSPIN_SHA) and build.gradle check.
# Usage: build.sh <output .so / .dylib> [compiler and its flags...]   (default: cc, the host's; the phone's: android/ondevice/build.sh passes
#        <NDK>/clang --target=aarch64-linux-android33). A computer needs no build: patch_nso.py compiles rwspin.c into its cache folder itself.
set -e
OUT=${1:?usage: build.sh <output library> [compiler...]}; shift
HERE=$(cd "$(dirname "$0")" && pwd)
[ $# -gt 0 ] || set -- cc
SHA=$(python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$HERE/rwspin.c")
case "$*" in
  *android*) EXTRA="-Wl,-soname,libdmsh_rwspin.so -Wl,-z,max-page-size=16384" ;;
  *) EXTRA="" ;;
esac
case "$(uname)" in Darwin) case "$*" in *android*) ;; *) EXTRA="$EXTRA -undefined dynamic_lookup" ;; esac ;; esac
"$@" -shared -fPIC -O2 -std=gnu11 -Wall -Wextra -Werror -fvisibility=hidden -ffile-prefix-map="$HERE"=. -DRWS_SOURCE="\"$SHA\"" $EXTRA -o "$OUT" "$HERE/rwspin.c"
echo "$OUT: rwspin.c $SHA"
