#!/bin/sh
# Rebase the `peanuts` overlay onto a Banana Native release (default: latest upstream/android),
# then verify the hooks. This script performs the rebase itself -- it is the one write-ish step
# in the upstream-sync workflow (see peanuts/docs/UPSTREAM.md); the read-only report/diff helpers
# in peanuts/scripts/upstream/ exist so this step has no surprises left in it.
#
# `--check`: run only the hunk-marker check, against the CURRENT branch (no fetch-and-rebase, no
# working-tree-clean requirement), and exit non-zero on failure. This lets the check run now,
# mid-development, or from a pre-commit hook -- found in review: previously it only ran
# after a rebase had already rewritten history, which is too late to catch anything before it's
# baked into the overlay branch.
#
# usage: upstream-update.sh [--check] [target]
set -eu
cd "$(git rev-parse --show-toplevel)"

mode="rebase"
if [ "${1:-}" = "--check" ]; then
    mode="check"
    shift
fi
target="${1:-upstream/android}"
branch="$(git branch --show-current)"
if [ -z "$branch" ]; then   # a detached HEAD: --check reads HEAD itself; a rebase needs a branch to move
    [ "$mode" = "check" ] || { echo "detached HEAD: check out the branch to rebase first"; exit 1; }
    branch="HEAD"
fi
allowlist="peanuts/upstream-unmarked.txt"
dropped="peanuts/upstream-dropped.txt"

# peanuts/upstream-dropped.txt: upstream paths Peanuts Native does not carry (today: every Switch 2 profile, dm/games/04*, and the
# Switch 2 games' host gates and their tests; Peanuts Native supports Switch 1 games only, owner 2026-11-26). One "pattern<TAB>reason"
# row each, the pattern a shell `case` pattern on the repo-relative path. The rebase keeps our deletion commit; when upstream edits a
# dropped file, the stop that causes (modify/delete) is resolved as a deletion; when upstream adds one (a new Switch 2 profile), it is
# removed in a commit of its own after the rebase. --check fails while any tracked path matches.
is_dropped() {
    [ -f "$dropped" ] || return 1
    while IFS="$(printf '\t')" read -r pat _reason; do
        case "$pat" in ''|'#'*) continue ;; esac
        # shellcheck disable=SC2254   # the pattern is meant to glob
        case "$1" in $pat) return 0 ;; esac
    done < "$dropped"
    return 1
}
tracked_dropped() {   # the tracked paths that match a dropped pattern, one per line (a git pathspec's `*` also matches `/`, as `case` does)
    [ -f "$dropped" ] || return 0
    pats=$(awk -F'\t' '$1 != "" && $1 !~ /^#/ { print $1 }' "$dropped")
    [ -n "$pats" ] || return 0
    # shellcheck disable=SC2086   # one pathspec per pattern
    (set -f; git ls-files -- $pats)
}
# A rebase stop whose every unmerged path is a dropped one: delete them and let the rebase go on. Any other stop is left to a human.
resolve_dropped_stop() {
    unmerged=$(git diff --name-only --diff-filter=U)
    [ -n "$unmerged" ] || return 1
    for f in $unmerged; do is_dropped "$f" || return 1; done
    for f in $unmerged; do git rm -q --ignore-unmatch -- "$f" >/dev/null 2>&1 || git rm -q --cached --ignore-unmatch -- "$f"; done
    echo "rebase: resolved as deletions (dropped upstream content, $dropped): $(echo "$unmerged" | tr '\n' ' ')"
    return 0
}

if [ "$mode" = "rebase" ]; then git fetch -q upstream --tags; fi
git rev-parse -q --verify "$target^{commit}" >/dev/null || { echo "no such ref: $target"; exit 1; }

# The hunk check must compare against the MERGE-BASE, not $target itself, whenever the branch
# hasn't been rebased onto $target yet -- otherwise it treats every upstream commit we haven't
# rebased past yet as "a hunk we introduced" and fails on the whole unrelated divergence, not our
# actual edits. That is exactly --check's situation (no rebase happens), so --check always uses
# the pre-rebase merge-base.
#
# In rebase mode, the correct "base" is different: AFTER a successful rebase, our branch's own
# history contains $target directly as an ancestor, so the right comparison is diff($target,
# branch) -- not diff(the OLD, pre-rebase merge-base, branch). Using the stale pre-rebase
# merge-base here would make the post-rebase check flag every upstream commit between that old
# merge-base and $target as "a hunk we introduced" too, the identical bug class --check was fixed
# for, just reintroduced on the other side of the rebase. So: compute the pre-rebase merge-base
# only for the check-mode message/comparison; in rebase mode, reset `base="$target"` once the
# rebase has actually succeeded.
if [ "$mode" = "check" ]; then
    base=$(git merge-base HEAD "$target") || {
        echo "no common ancestor between HEAD and $target; pass --check <base-ref> from this branch's history" >&2
        exit 1
    }
    echo "--check: hunk-marker check only (no rebase) -- $branch vs $target, since merge-base $(git rev-parse --short "$base")"
else
    [ -z "$(git status --porcelain)" ] || { echo "working tree not clean; commit first"; exit 1; }
    echo "rebasing $branch onto $target ($(git rev-parse --short "$target"))"
    if ! git rebase -q --rebase-merges "$target"; then   # keeps merge commits: other branches base on them
        while resolve_dropped_stop; do
            if GIT_EDITOR=true git rebase --continue; then break; fi
        done
        if [ -d "$(git rev-parse --git-path rebase-merge)" ] || [ -d "$(git rev-parse --git-path rebase-apply)" ]; then
            echo "rebase stopped: resolve, 'git rebase --continue', then re-run"; exit 1
        fi
    fi
    # Upstream content Peanuts does not carry that the rebase brought in (a new Switch 2 profile): removed in a commit of its own
    gone=$(tracked_dropped)
    if [ -n "$gone" ]; then
        echo "$gone" | while IFS= read -r f; do git rm -q -- "$f"; done
        git commit -q -m "Upstream sync: drop content Peanuts Native does not carry ($dropped)"
        echo "dropped after the rebase ($dropped): $(echo "$gone" | wc -l) file(s)"
    fi
    base="$target"
fi

# Files that are entirely Peanuts-owned: upstream never has them (or, for split_pool.py and
# ue_ondevice.py, upstream has the *directory* but not these specific files -- confirmed with
# `git log --diff-filter=A` + `git cat-file -e upstream/android:<path>` when they were added), so
# a whole-file diff against upstream is meaningless for them and they are exempt from the
# hook-marker check below. android/app/src/peanuts/ is the flavor source set (PNG/XML assets) --
# it does not exist upstream at all.
is_peanuts_owned() {
    case "$1" in
        peanuts/*|dm/peanuts/*|android/app/src/main/java/dev/peanuts/*|android/app/src/peanuts/*|PEANUTS.md|\
dm/gfx/tools/split_pool.py|dm/gfx/tools/ue_ondevice.py)
            return 0 ;;
        *)
            return 1 ;;
    esac
}

# peanuts/upstream-unmarked.txt: an explicit allow-list, one "path<TAB>reason" row per file that
# is deliberately left UNMARKED (today: the two shader-digest files whose bytes feed
# DMSH_VK_CHAIN, where even a comment forces re-translating every installed library -- see
# peanuts/UPSTREAM-EDITS.md's "Shader-chain exceptions" table, which this allow-list mirrors).
# Found in review: the old script had these two files hardcoded as if `is_peanuts_owned`
# applied to them, with no actual exception mechanism, so the hunk check failed on every run.
is_allowlisted() {
    f="$1"
    [ -f "$allowlist" ] || return 1
    awk -F'\t' -v f="$f" '$1 == f { found = 1 } END { exit !found }' "$allowlist"
}
allowlist_reason() {
    f="$1"
    awk -F'\t' -v f="$f" '$1 == f { print $2; exit }' "$allowlist"
}

# Hunk-level marker check: every changed *hunk* in a touched upstream file must itself carry a
# PEANUTS marker, OR fall inside a `PEANUTS begin` / `PEANUTS end` span in the file as it stands
# at $branch. The documented PEANUTS(upstream-candidate) form denotes the same kind of span.
# A file-level "grep PEANUTS anywhere in the file" check (the old behaviour) passes a
# file that has one marked hook plus a second, unmarked hunk dropped in by mistake -- exactly the
# gap a review found. The span support (found in review) covers the case
# a single trailing "see above" comment doesn't sit inside every hunk's own line window when
# several hunks share one explanation.
check_hunks_marked() {
    f="$1"
    spans=$(git show "$branch:$f" 2>/dev/null | awk '
        /PEANUTS(\(upstream-candidate\))? begin/ { b = NR }
        /PEANUTS(\(upstream-candidate\))? end/   { if (b) { print b":"NR; b = 0 } }
    ')
    # The spans go through the environment, not `awk -v`: with two or more spans the value holds
    # newlines, which macOS's awk (the one-true-awk) rejects ("newline in string"), exiting
    # non-zero, so a fully marked file was reported as unmarked. ENVIRON is POSIX and reads the
    # same in gawk and mawk.
    git diff -U1 "$base" "$branch" -- "$f" | PN_SPANS="$spans" awk '
        BEGIN {
            in_hunk = 0; has_marker = 1; bad = 0
            spans = ENVIRON["PN_SPANS"]
            nspans = split(spans, lines, "\n")
            for (i = 1; i <= nspans; i++) { split(lines[i], se, ":"); sb[i] = se[1]; sendv[i] = se[2] }
        }
        /^@@/ {
            if (in_hunk && !has_marker) bad = 1
            match($0, /\+[0-9]+(,[0-9]+)?/)
            seg = substr($0, RSTART + 1, RLENGTH - 1)
            split(seg, parts, ",")
            newstart = parts[1] + 0
            newcount = (parts[2] == "" ? 1 : parts[2] + 0)
            newend = newstart + newcount - 1
            has_marker = 0
            for (i = 1; i <= nspans; i++) {
                if (!(newend < sb[i] || newstart > sendv[i])) has_marker = 1
            }
            in_hunk = 1
            next
        }
        in_hunk { if ($0 ~ /PEANUTS/) has_marker = 1 }
        END { if (in_hunk && !has_marker) bad = 1; exit bad }
    '
}

fail=0
touched=$(git diff --name-only "$base" "$branch")
for f in $touched; do
    is_peanuts_owned "$f" && continue
    if is_allowlisted "$f"; then
        echo "exception ($allowlist): $f -- $(allowlist_reason "$f")"
        continue
    fi
    [ -f "$f" ] || continue   # deleted files have no hunks to check (a deleted upstream file is accepted as it is; the dropped ones are listed in $dropped)
    if git diff --no-color "$base" "$branch" -- "$f" | grep -q '^Binary files'; then
        echo "binary diff in upstream file $f -- cannot hunk-check; review by hand"
        fail=1
        continue
    fi
    if ! check_hunks_marked "$f"; then
        echo "hunk(s) without a PEANUTS marker in $f"
        fail=1
    fi
done

left=$(tracked_dropped)
if [ -n "$left" ]; then
    echo "$(echo "$left" | wc -l) tracked file(s) match $dropped (Peanuts Native does not carry them), e.g. $(echo "$left" | head -n 1): git rm them"
    fail=1
fi

if [ "$fail" != 0 ]; then
    echo "fix the hunks above (mark with PEANUTS, wrap in PEANUTS begin/end, or add to $allowlist with a reason), then re-run"
    exit 1
fi

if [ "$mode" = "check" ]; then
    echo "check passed: every touched-upstream-file hunk is marked, spanned, or allow-listed; no dropped upstream path ($dropped) is tracked."
    exit 0
fi

echo "overlay commits on top of $target:"
git log --oneline "$target".."$branch"
