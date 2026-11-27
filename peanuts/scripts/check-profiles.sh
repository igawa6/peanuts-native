#!/bin/sh
# Build-time check for the assumption dev.peanuts.ui.LibraryScanner.versionInfo() makes at runtime: that a profile's "recommended" build (shown on the
# Version row and used to relabel Build -> "Build anyway") is the highest version in its game.tsv `build` rows. game.tsv's own `package` line is the real
# answer (the version the shipped shader tables were built for) but is not exposed through BuildConfig.NX_PROFILES, so LibraryScanner approximates it with
# max(build) instead -- see LibraryScanner.java's class doc and GameSheet.java's "Known gaps" note.
#
# Found in review: the round-2 runtime guard for this (LibraryScanner.checkRecommendedGuard) never actually ran (a stray `break` made it check only
# game.tsv's first line, never the `package` row) and, even fixed, would decompress assets/pack/tools.zip once per title during the first scan on the
# phone for a check that only ever needs to run once, at build time. This script replaces it: no runtime cost, and it catches a mismatch before an APK
# ships instead of after.
#
# For each title given on the command line, or (with none) every profile under dm/games/, compares dm/games/<id>/game.tsv's `package` line against the
# highest version among its `build` rows. Prints one OK/MISMATCH line per title; exits non-zero if any title mismatches, has no `package` line, or has
# no `build` rows.
#
# Found in review (round 4): the default used to be android/app/gradle.properties' `nxGames`, which then held Banana Native's own Switch 2
# defaults, not the titles the Peanuts APK is actually built with (those come from -PnxGames=... at build time, which this script has no way to read
# without invoking Gradle; since 2026-11-26 `nxGames` is peanuts/release-titles.txt, Switch 1 only). Checking every dm/games/<id> profile is both simpler and strictly more thorough -- it is what the round-3 run already did
# by hand (131 OK) -- and never misses a title just because it is not (yet, or ever) one of the ones a particular APK build happens to include.
#
# usage: check-profiles.sh [title ID ...]   -- with no arguments, checks every dm/games/<16 hex digit> directory

set -eu
cd "$(git rev-parse --show-toplevel)"

status=0
checked=0

titles="$*"
if [ -z "$titles" ]; then
    titles="$(ls dm/games | grep -E '^[0-9A-Fa-f]{16}$')"
    if [ -z "$titles" ]; then
        echo "check-profiles.sh: no dm/games/<16 hex digit> directories found, and no title IDs given on the command line" >&2
        exit 1
    fi
fi

for id in $titles; do
    tsv="dm/games/$id/game.tsv"
    if [ ! -f "$tsv" ]; then
        echo "MISMATCH $id: no $tsv"
        status=1
        continue
    fi
    # game.tsv is tab-separated: "package\tv<N>" (one line) and one or more "build\tv<N>\t<display>\t<module>=<id>..." lines. awk -F'\t' avoids any need
    # for a tab literal in a shell pattern (grep -P isn't portable, and [[:space:]] in -E would also match a game name containing a space on the wrong column)
    pkg_ver="$(awk -F'\t' '$1 == "package" { v = $2; sub(/^[vV]/, "", v); print v; exit }' "$tsv")"
    if [ -z "$pkg_ver" ]; then
        echo "MISMATCH $id: no package line in $tsv"
        status=1
        continue
    fi

    build_vers="$(awk -F'\t' '$1 == "build" { v = $2; sub(/^[vV]/, "", v); print v }' "$tsv")"
    if [ -z "$build_vers" ]; then
        echo "MISMATCH $id: no build rows in $tsv"
        status=1
        continue
    fi
    highest="$(printf '%s\n' "$build_vers" | sort -n | tail -1)"

    checked=$((checked + 1))
    if [ "$pkg_ver" = "$highest" ]; then
        echo "OK $id: package v$pkg_ver == highest build v$highest"
    else
        echo "MISMATCH $id: package v$pkg_ver != highest build v$highest"
        status=1
    fi
done

echo "check-profiles.sh: $checked title(s) checked, exit $status"
exit $status
