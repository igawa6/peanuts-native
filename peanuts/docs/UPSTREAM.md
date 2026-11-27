# Where our stuff comes from, and how to pull updates for it

Provenance of the three upstreams this repo draws on, and the local workflow for staying in
sync with them. The read-only report/diff helpers live in `peanuts/scripts/upstream/`; the one
script that actually rebases is `peanuts/scripts/upstream-update.sh`, run by the repo owner, not
by an agent on its own.

Repo: this worktree (remote `upstream` = `https://git.eden-emu.dev/moceure/BananaNative.git`,
**branch `android`** — that is the upstream branch we track; our own local branch is named
differently and gets rewritten onto it on every sync, never merged in).

## 1. Provenance

Three different upstreams feed this repo, and they don't overlap in what they own:

| Component | Path | Origin | Evidence |
|---|---|---|---|
| Game profiles | `dm/games/<titleid>/` (`game.tsv`, `shader_map.tsv`, `shader_pairs.tsv`, `pso_manifest.tsv`, `bf_want.tsv`, `shader_facts.tsv`, `README.md`) | **nxnative** (seedds), vendored into Banana Native's `dm/` tree, carried through unmodified to Peanuts | `git log --format='%an' -- dm/games \| sort \| uniq -c` → 419 `seedds` commits vs 27 `moceure`, 3 `igawa6`. Per-game READMEs cite `seedds/nxnative` GitHub issues/PRs directly. Our 3 `igawa6` commits on `dm/games` (`fe28e752`, `8d3dfb35`, `eae84b94`) are single-key `game.tsv` tweaks (`mem_map_limit`, `fps_target`, vsync pin) on top of an unmodified upstream profile — Peanuts does not maintain its own profile format, it only patches individual keys in place (for now; see §4). |
| Shader tools (Apple/translator chain) | `dm/gfx/tools/dmsh_*.py`, the xlate sources, `dmsh_chain.lock` | **nxnative** | Same author split as above. |
| Android Vulkan shader tools | `dm/gfx/tools/vk_android_*.py`, `dm/gfx/nvn/dmsh_chain_vk.h`, `vk_android_same.tsv` | **Banana Native** (moceure), built on top of nxnative's translator, **not independent of the Apple chain** (see §2.3) | `vk_android_build.py`'s own header says it reuses nxnative's translator and job table unchanged but is "not one of DMSH_CHAIN's files" — a Banana-Native-only artifact (`DMSH_VK_CHAIN`/`DMSH_VK_ABI` in `dm/gfx/nvn/dmsh_chain_vk.h`). Our commits here (`6c395676`, `c6c020c5`) are packer/worker tuning, not translator changes. |
| GPU driver list | `android/app/src/main/java/dev/nxnative/android/DriverFetch.java` (`REPOS[]`) | **Banana Native** (moceure) — a hand-picked list of *external* GitHub Turnip/Adreno driver repos, not vendored code | No nxnative or nx2android involvement; these are third-party driver projects Banana Native links to for download, under their own licences (`android/NOTICE.md`). Treat every entry as a trust decision, not metadata (see §2.4). |
| Host (`dm/host/`) | emulation core | **nxnative**, with Banana Native's Android/Vulkan backend layered in | 255 `seedds` / 17 `moceure` / 6 `igawa6` commits on `dm/host`. |
| App (`android/`) | launcher UI, `android/app/src/main/java/dev/nxnative/android/*` | **Banana Native** is the majority author; Peanuts (`dev.peanuts.*`) is a UI overlay on top of it, hooked in with `PEANUTS` markers at call sites in `MainActivity.java`, `GameActivity.java`, etc. `android/app/src/peanuts/` (the flavor source set: icons, strings, manifest fragment) is wholly Peanuts' own and does not exist upstream at all. | All Peanuts-owned files live under `android/app/src/main/java/dev/peanuts/` and `android/app/src/peanuts/`; the Banana Native files they call into carry `PEANUTS`-marked hunks. |
| Companion runtime | `dm/peanuts/rt/` | **Eden Duo** (`~/eden/src/core/mods`), lifted wholesale, not nxnative/Banana Native at all | `dm/peanuts/rt/PORT.md`: the current lift is Eden Duo tag `v1.4.0`, `DualScreenRuntimeVersion = 25`. The lifted files keep their original names and are byte-identical to that tag's content except for a handful of `PNRT`-tagged hunks. |

### Is nx2android a different format? Can we import from it?

No — **same format, independent vendoring, not always in sync.** nx2android
(`shirai91/nx2android`, MIT, public on GitHub — this project's own `AGENTS.md`/`README.md` cite
it directly, so there is nothing non-public about nxnative's existence; what has no public URL
is nxnative's *own* bare repo, which we only have as the shared history inside this fork) vendors
nxnative's entire `dm/` tree in-repo, with the identical file set. It adds exactly one file of
its own per game, `android.tsv` (device test notes, package name, orientation, tuned flags) —
kept as a *separate* file rather than mutating the upstream one. That split is worth adopting
locally (§4) — it makes "what nxnative shipped" vs "what we changed" diffable at a glance without
touching the vendored file. Treat nx2android as a secondary cross-check (its `android.tsv`
device notes corroborate GPU/driver compatibility), never as the primary pull source — the
correct source for nxnative content is still this fork's own `upstream/android` history.

## 2. What "thin overlay" actually means here

This repo is **not** a one-line-hook overlay today. Peanuts edits to upstream files are marked
with `PEANUTS` hunks wherever the edit is a genuine call-out into Peanuts code (one line, or a
small marked block for a structural fix that has to stay local for now) — never a silent,
unmarked rewrite of upstream logic. A rolling inventory of every upstream file Peanuts has
touched, which hunks are marked and which still need a marker or a PR back to Banana Native, is
kept in **`peanuts/UPSTREAM-EDITS.md`** — read that file for the current, exact state; it is
updated as hunks get marked, so this document doesn't duplicate (and inevitably go stale
relative to) that count. The shader-digest files this inventory tracks separately rather than
marking line-by-line (`vk_android_build.py` and its siblings, where almost every line is
Peanuts' own packer/worker tuning layered onto nxnative's translator) are listed in
`peanuts/UPSTREAM-EDITS.md`'s "Shader-chain exceptions" table as whole-file entries, not
hunk-marked — marking every line of a file that is mostly ours would defeat the point of the
marker, and even a comment-only edit to one of these two files moves `DMSH_VK_CHAIN` (§3.3).

**`peanuts/upstream-unmarked.txt`** is the machine-readable twin of that table: a one-line-per-path
allow-list (`path<TAB>reason`) that `peanuts/scripts/upstream-update.sh` reads directly, so the two
shader-digest files have an actual exception mechanism instead of being silently expected to pass
a check they categorically cannot pass. Add a path here, with a reason, only for a file that is
deliberately and permanently left unmarked for a structural reason like the shader digest — not as
a shortcut around marking an ordinary hunk.

`peanuts/scripts/upstream-update.sh` enforces the marker rule at **hunk** granularity: every diff
hunk since the merge-base, in a file outside the Peanuts-owned trees (`peanuts/`, `dm/peanuts/`,
`android/app/src/main/java/dev/peanuts/`, `android/app/src/peanuts/`, `PEANUTS.md`, plus the two
Peanuts-authored-but-upstream-directory files `dm/gfx/tools/split_pool.py` and
`dm/gfx/tools/ue_ondevice.py`) and not on the `upstream-unmarked.txt` allow-list, must itself carry
a `PEANUTS` marker, or fall inside a `PEANUTS begin` / `PEANUTS end` span — not just somewhere
else in the same file. A file-level "does this file contain the word PEANUTS anywhere" check
passes a file that has one marked hook and a second, unrelated unmarked hunk dropped in beside
it; the hunk-level check does not. The span form exists because a single trailing "see above"
comment doesn't always sit inside every hunk's own line window when several hunks share one
explanation — wrap the whole explained region in `PEANUTS begin`/`PEANUTS end` instead of trying
to find a single line close enough to every hunk.

Run `peanuts/scripts/upstream-update.sh --check [target]` to run only this hunk check, against the
merge-base of HEAD and `target` (default `upstream/android`) — no fetch-driven rebase, no
working-tree-clean requirement, safe to run anytime mid-development or from a pre-commit hook, and
it exits non-zero on failure. The plain (no `--check`) form still runs the identical check after
the real rebase, as the final step of that rebase.

## 3. Local upstream-sync workflow

Design goal: **report first, rebase once, never silently revert our own work.**
`peanuts/scripts/upstream-update.sh` does the actual rebase (`git rebase --rebase-merges
upstream/android`) and the hunk-marker check above — that is the only routine update path. The
scripts in `peanuts/scripts/upstream/` are reports that help decide *when* and *with what review*
to run it; only two of them (`sync-drivers.sh` and `sync-games.sh`, each under a narrow safety
condition) ever write to the tracked tree themselves, and only under `--apply`, and only to
files we have never locally edited *and* have no uncommitted changes to (both checked before any
checkout). `sync-shader-tools.sh` is report-only — see §3.3 for why a piecemeal pull of
translator sources is unsafe even when the safety checks above would otherwise allow it.

### 3.0 Baseline: "since we last synced"

Every report below measures "since we last synced" against **the merge-base of HEAD and the
rebase target**, not a stored marker file and not the upstream tip. Defaulting to the upstream
tip makes `since..target` empty, so a report would always print "no change" — a real bug in an
earlier draft of this workflow. The merge-base needs no bookkeeping: immediately after
`upstream-update.sh`'s rebase, HEAD's merge-base with the target *is* the last-synced commit, by
construction. `peanuts/scripts/upstream/sync-state.sh` is the shared helper that computes this
(and provides an optional override file, `peanuts/.upstream-sync`, for asking about an earlier
point — never used by the `--apply` safety checks, so a stale override can't cause a revert).

### 3.1 Report command — what changed upstream, per component and per game

`peanuts/scripts/upstream/report.sh [since-ref] [target]` (default: the merge-base, against
`upstream/android`). For each component, lists upstream commits touching that path since the
marker, author, and a change count. For `dm/games/`, breaks the diff down **per titleid** and
flags which ones we have local `igawa6` commits on. Output is plain text to stdout; nothing
written. Real sample output (`report.sh`, run 2026-11-12 against this worktree, merge-base
`6574529781`):

```
== game profiles (dm/games) ==
  7 titleid dirs changed upstream since 65745297
  0400380020056000  2 files changed, 5 insertions(+), 3 deletions(-)  (aidankinzett,Aidan Kinzett,moceure)
  ...
== shader chain (dm/gfx/tools/dmsh_*.py, vk_android_*.py, dm/gfx/nvn/dmsh_chain_vk.h) ==
  DMSH_VK_CHAIN covers DMSH_CHAIN: any change below forces re-translation (see section 3.3).
  moceure 2026-11-12 Banana Native v0.3.12: mesh draws through compute, Star Fox, ...
  our tree:     DMSH_VK_CHAIN "6257cc4482ac"   DMSH_VK_ABI "228b73bd10f6"
  upstream tip: DMSH_VK_CHAIN "65883461079b"   DMSH_VK_ABI "228b73bd10f6"
  chain moved, ABI unchanged: old installed libraries keep playing while each device's delta runs.
  our vk_android_same.tsv rows for the old chain must be re-proven against the new one (byte comparison) before trusting them.
```

### 3.2 Game profiles — per-title report, and a narrowly-gated pull

`peanuts/scripts/upstream/sync-games.sh <titleid> [--apply]`: reports upstream's changes to one
title's profile vs. our local `igawa6` commits on it. **The whole-tree rebase is still the only
routine path for a game profile** — a `game.tsv` change often depends on a `game.py` or host
change landing in the *same* upstream commit, and `game.py` silently ignores unknown keys, so a
profile pulled on its own can look fine and simply not apply. `--apply` therefore only proceeds
when every upstream commit touching this titleid's profile touches **nothing else**; otherwise it
refuses and points at the full rebase. When it does proceed, it only checks out files we have
never locally edited since the merge-base — never a two-dot `diff upstream/android HEAD`, which
would also list files only *we* changed and revert them (confirmed live: `sync-games.sh
0100BA4026E94000 --apply`, Castlevania's `mem_map_limit off`, correctly reports "no upstream
change since the merge-base" and does nothing, because upstream hasn't touched that profile since
our fork point — a two-dot diff would have wrongly flagged our own local-only edit as "changed").

Adopting nx2android's split (§1) going forward: new Peanuts-only per-game tuning should land in a
sibling file `dm/games/<titleid>/peanuts.tsv` (key-value overrides applied at profile-load time)
instead of hand-editing the upstream `game.tsv` line, once the loader is taught to read it — a
host-code change, out of scope for this doc, but the sync script already treats `peanuts.tsv` as
always-ours and never overwrites it.

### 3.3 The shader chain — one chain, not two

`DMSH_VK_CHAIN` (in `dm/gfx/nvn/dmsh_chain_vk.h`) **covers** `DMSH_CHAIN`: the header's own
comment says so, and `DMSH_CHAIN` hashes the Apple-chain sources (`dmsh_build.py`, `dmsh_fetch.py`,
`dmsh_vin.py`, `dmsh_geometry.py`, `dmsh_facts.py`, the xlate sources, `dmsh_chain.lock`). So any
change to a `dmsh_*`/`vk_android_*` file, on either "side", moves `DMSH_VK_CHAIN` and forces
re-translation. An earlier draft of this document treated them as two independent chains that
could be pulled separately with `--chain apple`/`--chain vulkan`; that was wrong.

`peanuts/scripts/upstream/sync-shader-tools.sh` is **report-only** — it never checks out or
writes anything, and has no `--apply` at all. Two reasons: a hand-kept list of "the chain's
files" is easy to get wrong (an earlier version of this script was missing `dmsh_atomic.py`,
`dmsh_pull.py`/`.metal`, `dmsh_alpha.py`, `dmsh_logic.py`, `dmsh_mesh.py`, `dmsh_ampere.py`,
`nvn/dmsh_abi.h`, `dmsh_abi.py`, `ios_metal.py` and the vendored Ryujinx `.cs` sources — a
wrong or incomplete list can report "no change" when the chain actually moved); and even a
*complete* list isn't safe to pull file-by-file, because a new translator source needs a
rebuilt `libdmsh_xlate.so` stage or Gradle's `nxPackXlateCheck` fails against the mismatch. The
script now reads its file list straight from the authoritative source — `dmsh_chain.py`'s own
`FILES` and `vk_android_build.py`'s own `VK_FILES`, imported read-only (module-level assignments
only; no function that touches the filesystem is ever called) — and determines "changed or not"
by comparing `DMSH_VK_CHAIN`/`DMSH_VK_ABI` directly between our tree and upstream's, the same
decisive signal `report.sh` already uses, rather than re-deriving it from a file list at all. The
whole-tree rebase (`upstream-update.sh`) remains the only way to actually pull a shader-chain file.

Procedure once the chain has moved, in order (each step's output feeds the next):

1. `python3 dm/gfx/tools/tool_lock.py --update-lock` — regenerates `dmsh_chain.lock` if an
   Apple-chain input moved (this is the actual regeneration command; an earlier draft guessed at
   a `--header`/digest mode on the wrong tool).
2. `python3 dm/gfx/tools/dmsh_chain.py --header` — regenerates `DMSH_CHAIN` from the lock file.
3. `python3 dm/gfx/tools/vk_android_build.py --header` — regenerates `dm/gfx/nvn/dmsh_chain_vk.h`
   (`DMSH_VK_CHAIN`, `DMSH_VK_ABI`). Never hand-edit these `#define`s.
4. Check whether `DMSH_VK_ABI` also moved. `DMSH_VK_ABI` is what the delta replay reads of an
   installed library; if it's unchanged, every already-installed device's old library keeps
   playing while that device's `ShaderLibs` runs the delta translate in the background — a moved
   chain with the same ABI is a translate pass, not an outage. If `DMSH_VK_ABI` also moved, old
   libraries will not play at all and every device needs a full rebuild, not a delta.
5. Either way, build a known game with the new tools and byte-compare the resulting `dmsh_vk.lib`
   against the previous version's library for that same game.
   - **Byte-identical**: append a row to `dm/gfx/tools/vk_android_same.tsv` (tools digest →
     library version it reproduced, with an evidence string that names the actual build and
     byte-compare performed). "By inspection of the one changed line" is not sufficient evidence
     for this file — our own existing row `ca3a1c910276 → 6257cc4482ac` was added that way and
     needs to be re-proven by an actual byte comparison, not just re-asserted, on the next sync.
   - **Different**: no row; every device's library simply moves to the new version.
   - Our two existing rows for the chain being replaced (`ca3a1c910276`, `1d1b13454f7c` →
     `6257cc4482ac`) must be re-proven against the *new* chain this way before anyone relies on
     them again — a moved chain does not carry old evidence forward automatically.
6. `python3 dm/gfx/tools/vk_android_build.py --check <rebuilt dmsh_vk.lib>` as a sanity gate.

Consider sending the worker-pool and zstd-level tool changes upstream (they're general
improvements to `vk_android_build.py`'s packer, not Peanuts-specific) so this cost stops
recurring on our side alone.

### 3.4 GPU driver list

`peanuts/scripts/upstream/sync-drivers.sh [--apply]` diffs `DriverFetch.java`'s `REPOS[]` against
upstream's copy since the merge-base. This is **not** no-risk metadata: every entry is a URL the
app downloads and runs native driver code from, so the script always prints the diff and expects
a human to read each added entry before `--apply`, which itself only proceeds if we have no local
edits to the file (confirmed: 0 `igawa6` commits on it).

### 3.5 Host/app — PEANUTS-marked-hunk merge strategy

This is the highest-risk component because Peanuts code is *interleaved* with Banana Native code
in shared files (`MainActivity.java`, `GameActivity.java`, `loader.c`, …), not isolated to its own
directory. `upstream-update.sh` does the right high-level thing (a rebase, then the hunk-level
marker check from §2). What it can't give you is a *pre-rebase* look at which upstream commits are
about to land next to one of our hooks — the one case that benefits from a human reading five
lines ahead of time:

`peanuts/scripts/upstream/check-peanuts-conflicts.sh [since-ref]`: for every `PEANUTS`-marked line
in the tree (outside the Peanuts-owned trees), finds whether upstream has touched that file's
surrounding lines since the merge-base. Real sample output, 2026-11-12:

```
-- ./android/app/src/main/java/dev/nxnative/android/GameActivity.java (upstream touched this file since 65745297) --
  line 176: dev.peanuts.Companion.start(this, getIntent().getStringExtra("name"));   // PEANUTS: the companion on the second display
    nearby upstream commit since last sync: 3c85e095 -- review before rebasing
```

This only checks lines that already carry a marker — it does not find unmarked Peanuts hunks (see
§2 and `peanuts/UPSTREAM-EDITS.md` for those); that is checked at hunk granularity by
`upstream-update.sh` itself, after the rebase runs.

### 3.6 Companion runtime — from Eden Duo, not from `git fetch`

Not a `git fetch`; the source is Eden Duo's own tag history, read through a sibling working tree
(`~/eden`, shared across sessions per `feedback_shared_eden_build_sessions`). The critical
correction here: **compare against the pinned tag's content (`git -C ~/eden show
<tag>:src/core/mods/<file>`), never against `~/eden`'s current HEAD.** `~/eden` is a shared tree
that other sessions check out to whatever they're working on at any given moment, and `git
describe --tags` essentially never equals the bare tag name once you're even one commit past it
— a HEAD-based compare reports "differs" almost unconditionally, which is useless as a signal.

`peanuts/scripts/upstream/sync-rt.sh [--apply]`:

1. Confirms the pinned tag (`v1.4.0` by default, override with `EDEN_DUO_TAG`) exists in
   `~/eden` and matches `dm/peanuts/rt/PORT.md`'s recorded lift.
2. For each of the lifted files in `dm/peanuts/rt/mods/`, diffs our copy against `git -C ~/eden
   show <tag>:src/core/mods/<name>` and partitions into unchanged / safe straight copy /
   needs-PNRT-reapply, using the actual marker inventory rather than a hardcoded guess.
3. Never auto-writes `dm/peanuts/rt/mods/*`. `--apply` only stages candidate copies (sourced from
   the tag, not the working tree) into `/tmp/peanuts-rt-sync/` for review — crossing from "Banana
   Native upstream" to "a different upstream project entirely" (Eden Duo) is gated by the cycle
   steward's explicit approval, not by this script.

**PNRT file count: 6**, confirmed live with `grep -rl PNRT dm/peanuts/rt/mods` (not scoped to
just the historical "four edited files" PORT.md's R5 section describes — those four,
`mod_runtime.h`, `mod_assets.cpp`, `mod_romfs_sources.cpp`, `mod_module_services.cpp`, are files
that existed before the R5 lift and were *re-edited* with marked hunks; the other two,
`mod_module.cpp` and `pnrt_stubs.cpp`, are native-module-host files added *in* R5 that also carry
`PNRT` markers because they hold Peanuts-authored content inside an otherwise verbatim-lift
directory. Both readings are legitimately "PNRT files" — this script and this document use the
filesystem count, 6, since that's what any new sync needs to check against).

### 3.7 Putting it together

```
1. peanuts/scripts/upstream/report.sh                     # what moved, per component/game, since the merge-base
2. peanuts/scripts/upstream/check-peanuts-conflicts.sh     # which marked hooks are near upstream churn
3. For game profiles you care about:
   peanuts/scripts/upstream/sync-games.sh <titleid>        # report; --apply only if every touching commit is scoped to the title
4. If the shader chain changed:
   peanuts/scripts/upstream/sync-shader-tools.sh            # report-only, no --apply (see §3.3)
   (then the real rebase in step 7 pulls the files; follow the printed byte-compare procedure afterward, append vk_android_same.tsv by hand if it matches)
5. peanuts/scripts/upstream/sync-drivers.sh --apply         # only after reading every added repo entry
6. For the runtime (only with a cycle steward's approval for a runtime bump):
   peanuts/scripts/upstream/sync-rt.sh                       # --apply stages to /tmp only, never the worktree
7. peanuts/scripts/upstream-update.sh --check                # run the hunk-marker check now, before rebasing, and fix/allow-list anything it flags
   peanuts/scripts/upstream-update.sh                        # the real rebase; drops Switch 2 content (section 3.8); the same hunk-marker check runs again after it
8. the owner updates peanuts/.upstream-sync (optional) and reviews peanuts/UPSTREAM-EDITS.md for any new unmarked hunks the rebase introduced
```

Steps 1–6 are read-only by default; their only writes are narrowly-gated, merge-base-safe
checkouts of files Peanuts has never locally edited and has no uncommitted changes to, or (for
the runtime) a copy into `/tmp` only. Step 7's second invocation is the one that touches git
history, and the owner runs it, not an agent acting on its own; the `--check` form is read-only
and safe for anyone to run anytime.

### 3.8 Dropped upstream content (Switch 2)

Peanuts Native supports Switch 1 games only (owner, 2026-11-26), so it does not carry Banana Native's Switch 2 profiles
(`dm/games/04*`, the title IDs that start with `04`) or the host gates only those games use (`dm/host/android/dkb_wide.c`,
`mkw_wide.c`, `oot_wide.c`, `mtf_size.c`, `pkp_aspect.c` and their `dm/host/tests/t_*.c`). The list is
**`peanuts/upstream-dropped.txt`** (`pattern<TAB>reason`, the pattern a shell `case` pattern on the repo path). The shared
Switch 2 support in the host and the app (NVN2, Program-n / the Switch 2 dump layout, `Edition.java`, the `Switch2/` storage
folder) stays: it is upstream's and is not tied to a profile.

`upstream-update.sh` keeps them out on every sync:

- our deletion commit is an ordinary overlay commit, replayed by the rebase like any other;
- a rebase stop where every unmerged path is a dropped one (upstream edited a profile we deleted: modify/delete) is resolved as
  a deletion and the rebase continues; any other stop is left to a human as before;
- a dropped path the rebase brings in (a new Switch 2 profile upstream added) is removed after the rebase in a commit of its own,
  "Upstream sync: drop content Peanuts Native does not carry";
- `--check` fails while any tracked path matches the list. The hunk check accepts a deleted upstream file as it is (it has no
  hunks), so the deletions need no marker; the edits that go with them (the `OBS_*` rows in `dm/host/dm.h`, the test runner in
  `dm/host/tests/Makefile`, the tests repointed to Switch 1 profiles) are marked hunks like any other, listed in
  `peanuts/UPSTREAM-EDITS.md`.

`peanuts/tests/test_upstream_check.py` covers the three cases in a throwaway repository. Upstream's own notes (`docs/reference`,
`docs/agents`, the per-game READMEs of Switch 1 profiles, `dm/games/README.md`) may still cite the Switch 2 profiles; they are left
as they are, so a sync does not conflict on them.

## 4. Nice to have / not yet done

- Adopt the nx2android-style overlay file `dm/games/<id>/peanuts.tsv`, implemented as one marked
  hook in `game.py`'s `load()` and one in `build.gradle`'s `tsvRows`, so the three current
  `game.tsv` edits (`mem_map_limit`, `fps_target`, the LA vsync pin) can leave the upstream file
  alone entirely.
- `report.sh --since <tag>` for "what would the next Banana Native release bring", once there's a
  reason to look ahead of `upstream/android`'s current tip.
