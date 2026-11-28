# Notes for agents

Peanuts Native is an overlay on [Banana Native](https://git.eden-emu.dev/moceure/BananaNative) that adds a
dual-screen companion (Eden Duo's package/runtime format) on the second display of a dual-screen Android handheld
(developed on the AYN Thor). Full narrative status and the day-by-day log: `PEANUTS.md` at the repo root. Full
contributor guide (prose): `peanuts/docs/CONTRIBUTING.md` beside this file. Current inventory of every upstream file
this project has touched, and which edits are marked / queued for a Banana Native PR / still open:
`peanuts/UPSTREAM-EDITS.md` — read it before touching any upstream file, not just before adding a new hook.

## Where code goes — no exceptions

- `android/app/src/main/java/dev/peanuts/` — launcher side (Java).
- `android/app/src/peanuts/` — the Peanuts flavor source set (icons, strings, manifest fragment); does not exist
  upstream.
- `dm/peanuts/` — host side (plain C): companion loader, guest readers, tick, input, importer, `.nup` RomFS
  provider, the companion runtime port (`dm/peanuts/rt/`).
- `peanuts/` — companion packages, docs, tests, scripts.
- Any touch to an upstream Banana Native file must carry a `PEANUTS` marker **on every hunk it introduces**, not
  just somewhere in the file. It is one of: a minimal marked hook (the common case); a block-marked
  `PEANUTS begin`/`PEANUTS end` feature that genuinely can't be a hook; a `PEANUTS(upstream-candidate)` general fix
  queued as its own PR to Banana Native; or a marked single-key game-tweak in a `game.tsv` (until the
  `peanuts.tsv` overlay file exists — see `peanuts/docs/UPSTREAM.md` §4). Check the current allowed hook list in
  `PEANUTS.md` before adding a new hook site; add the new site to that list in the same commit. Never reformat,
  restyle, or "fix" anything else in an upstream file — a general fix is its own upstream-candidate commit, not
  folded into an unrelated change. Run `peanuts/scripts/upstream-update.sh --check` to verify every hunk you just
  added is marked, before committing — it's read-only and doesn't require a rebase to run. A file that structurally
  cannot be marked (today: the two shader-digest files, `dm/gfx/tools/vk_android_build.py` and
  `dm/gfx/tools/vk_android_same.tsv`) goes on the explicit allow-list `peanuts/upstream-unmarked.txt`, with a reason
  — never silently skipped in the checker itself.
- A fix that's true for every title belongs in the core (`dm/peanuts/`, or upstream). A fix true only for one
  title's own code goes in that title's Banana Native profile (`dm/games/<title ID>/patches.py`, gated by build ID,
  **and still carries a `PEANUTS` marker** — `patches.py` is an upstream file, not a Peanuts one) — never as a
  per-title branch in shared code.
- **The shader chain is one chain.** `dm/gfx/tools/dmsh_*.py` and `dm/gfx/tools/vk_android_*.py` are not
  independent of each other (`DMSH_VK_CHAIN` covers `DMSH_CHAIN`). Never touch any file in either without the full
  regenerate-and-byte-compare procedure in `peanuts/docs/UPSTREAM.md` §3.3, and never add a `vk_android_same.tsv`
  row without an evidence string that names an actual build and an actual byte comparison — "by inspection" does
  not count as evidence for this file.

## Before touching anything

1. Read `PEANUTS.md`'s rules section and its latest status entries, and `peanuts/UPSTREAM-EDITS.md` — know what's
   already verified, what's already marked, and what's open.
2. Identify which of these you're doing, since they're different jobs:
   - Getting a title running at all under plain Banana Native → that's upstream's problem; fix it there, bring it
     back by rebase.
   - Fixing or completing a title's Banana Native **profile** (`dm/games/<id>/`: `game.tsv`, shader tables,
     `patches.py`) → edit the profile, not shared code, and mark the edit.
   - Companion work (loading, matching, native module, RomFS reads) → `dm/peanuts/` + `dev/peanuts/`.
3. Never write game files, keys, `.nup`s, packs, or anything extracted from a game into the repository, the chat, a
   commit, or an issue. Keys and game data stay on the device/build machine, with the game's own dump folder. Keep
   that dump/work folder outside the repository and git-ignored, and delete a title's folder once you're done with
   it (disk-budget practice, and it makes "did I stage game data" trivial to answer by inspection).

## Verification checklist (mandatory before marking anything done)

- [ ] **Booted on a real device**, not just "it compiles" or "the build succeeded." Name the device, the GPU driver
      (system or Turnip + version), the title, and its build ID as reported in the log.
- [ ] **Pack build vs. profile build checked**, for anything touching version handling: is the dump's build *listed*
      in `game.tsv`, and if so is it the *package* build (fast targeted shader scan) or a different listed one
      (slower full scan, not a bug)? A build not listed at all is a real gap (Dragon Quest III HD-2D Remake is the
      live example) — say which case you're in, don't conflate them.
- [ ] **Companion loads and reads correctly**, if the change touches companion code: confirm with an actual gameplay
      state (not just the standby page) when the change could affect live reads. Also confirm the companion's own
      `build_ids` actually cover the pack build you tested — a companion can support fewer builds than the pack
      profile (Mario Kart 8 Deluxe's 4.0.0-only companion against a 4.0.0/4.0.1 pack is the live example).
- [ ] **Frame rate / crash status checked**, not assumed, for anything that could regress performance or stability:
      state the number or the "no crash in N seconds" window actually observed, with what that's based on (watched
      live, or how long a log/recording covers).
- [ ] **Driver coverage stated.** If only the system driver was tried, or only Turnip, say so — don't imply both were
      checked when they weren't.
- [ ] **A native crash is symbolicated**, not guessed at: `llvm-addr2line -f -C -e <unstripped libnxhost_<id>.so>
      <pc offset>` — note it is `libnxhost_<id>.so`, not `libnxlaunch.so`, for a companion-side crash too, since
      `dm/peanuts/` and the runtime are linked into the host. Match the symbol file to the exact build that produced
      the log, before proposing a fix.

## Hard rules — never do these

- Never commit, paste into chat, or attach to an issue/PR: game ROM/RomFS/NSP/XCI data, decrypted game code or
  assets, `prod.keys` or any key value (key *names* are fine), a `.nup`, a built pack, a shader library, or a
  companion's resolved-from-game-data content.
- Never publish or distribute a shader library, pack, or `.nup` — these are derived directly from someone's own game
  files and redistributing them is redistributing that game's assets.
- Never add anything that asks for keys/games, explains how to obtain them, or links to a site that provides them.
- Never send a contribution derived from this project toward Eden's upstream (the Eden emulator) — that project bans
  AI-assisted contributions, which is exactly why Eden Duo's companion runtime (and this port of it) stays private.
  Keep that boundary; don't assume work done here is upstream-portable to Eden itself. (Banana Native's own upstream
  has no such restriction and does accept AI-assisted PRs — don't conflate the two upstreams' policies.)
- Never install, uninstall, or overwrite a build on someone else's device without saying the package name and size
  first and getting an explicit go-ahead; never touch their existing (non-test) install without being told to.
- Never carry a fixed/hardcoded address or offset across a game build without checking its build ID gate first.
- Never change a shader-chain file (`dmsh_*.py`, `vk_android_*.py`, `dmsh_abi_vk.h`) without the full
  regenerate-and-byte-compare procedure — see "Where code goes" above.
- Never touch this repository's git state beyond reading it (no commit/reset/stash/checkout/restore/rebase/
  merge/push) unless explicitly asked, and even then, never run `peanuts/scripts/upstream-update.sh`'s rebase step
  on your own initiative — that is the owner's call, run by the owner, following `peanuts/docs/UPSTREAM.md`'s
  workflow. The read-only report/diff scripts in `peanuts/scripts/upstream/` exist precisely so an agent can gather
  everything needed to brief the owner without touching history.

## Diffs and commits

- Small, single-concern, independently verifiable. A large speculative change is both hard to review and hard to
  rebase past Banana Native's next release.
- Commit message states the symptom, the cause, the fix, and what was checked on-device — cite the evidence (log
  line, crash address + symbol, frame-rate number, screenshot filename), don't just assert it works.
- Commit and push only when explicitly asked. `origin` is a local bare repo; the GitHub repository is private and
  only the owner pushes to it.
- **No AI attribution:** never add a `Co-Authored-By`, `Signed-off-by`, `Assisted-by` or "Generated with" line naming
  an AI tool or vendor, and never commit as an AI identity. The commit author is the owner's identity only.
- **No private data:** no LAN or adb addresses, home or mount paths, work-folder paths, or personal e-mails in any
  committed file or message; use placeholders (`<work dir>`, `<device ip>`).
- `sh peanuts/scripts/check-publish.sh` must print "clean" before any push.
- Run `sh peanuts/scripts/install-hooks.sh` once in every clone (worktrees share its hooks): the `commit-msg` and `pre-push` hooks then refuse an AI trailer and run `check-publish.sh` on every push, so the rules above are checked, not only read. `CLAUDE.md` at the root links to this file and `CLAUDE.md` here to the `AGENTS.md` beside it, for agents that read only that name; the repository-wide notes are in `AGENTS.md` at the root. A Windows checkout with `core.symlinks=false` gets a one-line text file holding the target path instead of the link: open that path yourself.

## Where findings go

- A fact about one title (why its profile needs a key, a measured frame rate, a known gap) → that title's own note
  (`dm/games/<id>/README.md` if Banana Native has the convention there).
- A fact about an upstream file's marking status (marked / needs a marker / queued as an upstream-candidate PR) →
  `peanuts/UPSTREAM-EDITS.md`. Don't let this drift out of sync with the actual tree — if you mark a hunk, update
  the inventory in the same commit.
- A general mechanism (true for every title) → the relevant doc in Banana Native's own `docs/reference/` if it's a
  Banana Native-level fact, `peanuts/docs/UPSTREAM.md` if it's about the sync workflow, or `PEANUTS.md`'s status log
  if it's Peanuts-specific and not yet stable enough for its own doc section.
- Don't repeat a finding in two places — link to it.
