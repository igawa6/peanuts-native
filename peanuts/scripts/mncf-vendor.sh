#!/bin/sh
# mncf-vendor.sh <mistel checkout> [<commit>] : copies Mistel's MIT library for .mncf cheat files (format/lib/java, package io.github.davdxpx.mistel.mncf)
# into the launcher, word for word, with the MIT notice of Mistel's format/ folder, and writes the list of what was copied (MISTEL.sha256: the commit,
# then a SHA-256 and a path a line). peanuts/tests/test_mncf_vendor.py checks the copy against that list, so a change to the copy itself fails the tests:
# fixes go to Mistel first (https://github.com/davdxpx/mistel), then come here with this script. <commit> defaults to origin/main of the checkout.
# Only format/ is MIT; nothing else of Mistel (GPL-3.0) may be copied into this repository.
set -eu
src="${1:?usage: mncf-vendor.sh <mistel checkout> [<commit>]}"
rev="${2:-origin/main}"
cd "$(git rev-parse --show-toplevel)"
dst="android/app/src/main/java/io/github/davdxpx/mistel"
commit=$(git -C "$src" rev-parse --verify "$rev^{commit}")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$src" archive "$commit" format/LICENSE format/lib/java/src/main/java/io/github/davdxpx/mistel/mncf | tar -x -C "$tmp"
[ -f "$tmp/format/LICENSE" ] && grep -q '^MIT License' "$tmp/format/LICENSE" || { echo "mncf-vendor: $commit has no MIT format/LICENSE"; exit 1; }
rm -rf "$dst/mncf"
mkdir -p "$dst"
cp -R "$tmp/format/lib/java/src/main/java/io/github/davdxpx/mistel/mncf" "$dst/mncf"
cp "$tmp/format/LICENSE" "$dst/LICENSE"
{
    echo "# mistel $commit format/lib/java/src/main/java/io/github/davdxpx/mistel/mncf and format/LICENSE, unchanged (peanuts/scripts/mncf-vendor.sh)"
    (cd "$dst" && find LICENSE mncf -type f | LC_ALL=C sort | while read -r f; do printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"; done)
} > "$dst/MISTEL.sha256"
echo "mncf-vendor: $(grep -c '  ' "$dst/MISTEL.sha256") files of mistel ${commit%"${commit#????????}"} in $dst"
