#!/bin/sh
# PEANUTS: the checks a pull request is held to, reporting only what it makes fail (CI: .github/workflows/peanuts.yml, job pr-checks; also by hand).
# Each check runs at HEAD and at the merge-base of HEAD and <base ref>: a check that already fails at the merge-base is reported, not held against
# the pull request (the base fails some tool tests for reasons of its own). Exit 1 when HEAD adds a failure.
#   usage: sh peanuts/scripts/pr_checks.sh <base ref>        e.g. origin/dev
set -u
cd "$(git rev-parse --show-toplevel)"
base=$(git merge-base HEAD "${1:?usage: pr_checks.sh <base ref>}") || { echo "no merge-base with $1"; exit 2; }
out=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pr-checks.$$
mkdir -p "$out"
git worktree add -q --detach "$out/base" "$base" || exit 2
trap 'git worktree remove --force "$out/base" >/dev/null 2>&1; rm -rf "$out"' EXIT INT TERM
head=$(pwd)
new=0
echo "pull request checks: HEAD $(git rev-parse --short HEAD) against the merge-base $(git rev-parse --short "$base")"

# 1. The tool tests (dm/tools/tests): the failing tests by name, HEAD's new ones held against it
tests() { (cd "$1" && python3 -m unittest discover -s dm/tools/tests -p 'test_*.py' 2>&1) | grep -E '^(FAIL|ERROR):' | sort -u; }
tests "$out/base" > "$out/tests.base"
tests "$head" > "$out/tests.head"
added=$(comm -13 "$out/tests.base" "$out/tests.head")
echo "tool tests: $(wc -l < "$out/tests.head" | tr -d ' ') failing at HEAD, $(wc -l < "$out/tests.base" | tr -d ' ') at the merge-base"
if [ -n "$added" ]; then new=1; echo "NEW tool test failures:"; echo "$added" | sed 's/^/  /'; fi

# 2. The checks that pass or fail as a whole
check() {   # check <name> <command...>: run at both; a failure only at HEAD is new
    name=$1; shift
    (cd "$out/base" && "$@" > "$out/c.base" 2>&1); b=$?
    (cd "$head" && "$@" > "$out/c.head" 2>&1); h=$?
    if [ $h -eq 0 ]; then echo "ok    $name"
    elif [ $b -ne 0 ]; then echo "base  $name (fails at the merge-base too, not held against this pull request)"
    else new=1; echo "NEW   $name:"; tail -n 15 "$out/c.head" | sed 's/^/  /'
    fi
}
check "game catalog and Supported games tables (games_catalog.py --check)" python3 peanuts/scripts/games_catalog.py --check
check "issue forms (issue_forms.py --check)" python3 peanuts/scripts/issue_forms.py --check
check "game patches index (docs_patches.py --check)" python3 dm/tools/docs_patches.py --check
check "symptom index (docs_symptoms.py --check)" python3 dm/tools/docs_symptoms.py --check
check "environment switches (env_switches.py --check)" python3 dm/tools/env_switches.py --check
check "advanced settings (advanced_switches.py --check)" python3 dm/tools/advanced_switches.py --check
check "runtime profiles (gen_runtime_profile.py check-all)" python3 dm/peanuts/profile/gen_runtime_profile.py check-all
check "profiles (check-profiles.sh)" sh peanuts/scripts/check-profiles.sh

# 3. Hunk markers in upstream files (upstream-update.sh --check <merge-base>): the hunks this pull request adds to upstream files without a
#    PEANUTS marker (the check reads only base..HEAD, so what the base already carries is not counted)
if (cd "$head" && sh peanuts/scripts/upstream-update.sh --check "$base" > "$out/m.head" 2>&1); then
    echo "ok    hunk markers (upstream-update.sh --check): every upstream hunk this pull request adds is marked"
else
    new=1; echo "NEW   hunk markers (upstream-update.sh --check):"; tail -n 20 "$out/m.head" | sed 's/^/  /'
fi

# 4. New-game rules (peanuts/docs/CONTRIBUTING.md, "A new-game pull request"): a vk_prewarm.bin the pull request adds fails it (we build seeds
#    from the attached keys); a new profile without shader_files.tsv, or with frame-rate rows and no frame times in its note, is a warning
for f in $(git diff --name-only --diff-filter=AM "$base" HEAD -- 'dm/games/*/vk_prewarm.bin'); do new=1; echo "NEW   $f: a prewarm seed in the pull request (attach the runs' .keys files instead)"; done
for d in $(git diff --name-only --diff-filter=A "$base" HEAD -- 'dm/games/*/game.tsv' | sed 's,/game.tsv$,,'); do
    # a map whose every source is a run-time capture (/glslc_capture/...) has no game file to target, so no shader_files.tsv is asked for
    runtime_only=$(awk -F'\t' '!/^#/ && $1 != "package" { n++; if ($5 !~ /^\/glslc_capture\//) other++ } END { print (n > 0 && other == 0) ? 1 : 0 }' "$d/shader_map.tsv" 2>/dev/null)
    [ -f "$d/shader_map.tsv" ] && [ ! -f "$d/shader_files.tsv" ] && [ "$runtime_only" != 1 ] && echo "warn  $d: no shader_files.tsv, so the phone's Prepare scans every game file (CONTRIBUTING, Fast Prepare)"
    if grep -qE '^(fps_limit|fps_target|fps_modes|fps_limit_present|fps_steps|fpslocker)' "$d/game.tsv" 2>/dev/null && ! grep -qE '[0-9] ?(ms|fps)' "$d/README.md" 2>/dev/null; then
        echo "warn  $d: frame-rate rows in game.tsv but no frame times (ms) or rates (fps) in its note (CONTRIBUTING, Frame rate)"
    fi
done

[ $new -eq 0 ] && echo "No new failures." || echo "This pull request adds the failures marked NEW above."
exit $new
