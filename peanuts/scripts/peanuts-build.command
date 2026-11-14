#!/bin/sh
# Peanuts Native's builder on macOS: .nsp / .xci (or a .nup, or a dump folder) -> .nup -> pack -> (with --push) the phone.
# Runs peanuts_build.py with the packer's Python (scripts/bananative.command python: set up on first use, see that file).
# Usage: peanuts/scripts/peanuts-build.command <input>... [options] [-- pack_android.py options]   (see peanuts/docs/BUILDER.md)
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
exec "$REPO/scripts/bananative.command" python "$REPO/peanuts/scripts/peanuts_build.py" "$@"
