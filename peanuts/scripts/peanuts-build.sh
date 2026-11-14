#!/bin/sh
# Peanuts Native's builder on Linux: .nsp / .xci (or a .nup, or a dump folder) -> .nup -> pack -> (with --push) the phone.
# Needs Python 3.14 with the packages of android/environment.yml (zstd, Capstone 5, lz4, zbic, spirv-val, the .NET 10 runtime), e.g. a micromamba
# environment made from that file. PEANUTS_PYTHON names its python; else python3.14 or python3 (3.14 or later) on PATH is used.
# Usage: peanuts/scripts/peanuts-build.sh <input>... [options] [-- pack_android.py options]   (see peanuts/docs/BUILDER.md)
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
die() { printf '%s\n' "[RED] $*" >&2; exit 1; }
py=
if [ -n "${PEANUTS_PYTHON:-}" ]; then
	py=$PEANUTS_PYTHON
else
	for c in python3.14 python3; do
		if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(sys.version_info < (3, 14))' 2>/dev/null; then py=$c; break; fi
	done
fi
[ -n "$py" ] || die "the Linux builder needs Python 3.14 with the packages of android/environment.yml (zstd, Capstone 5, lz4, zbic, spirv-val, the .NET 10 runtime): make an environment from that file (micromamba create -p <folder>/env -f android/environment.yml) and set PEANUTS_PYTHON to its python, or put python3.14 on PATH"
exec "$py" "$REPO/peanuts/scripts/peanuts_build.py" "$@"
