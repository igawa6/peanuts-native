# Contributing to Peanuts Native

Peanuts Native is an overlay on top of [Banana Native](https://git.eden-emu.dev/moceure/BananaNative), adding a
second-screen companion for dual-screen Android handhelds. Banana Native ships several releases a day, and every
rule below exists so that `peanuts/scripts/upstream-update.sh` keeps rebasing cleanly onto it. Read `PEANUTS.md` at
the repository root first — it has the exact rules, the current hook sites, and a day-by-day status log of what's
been built and verified on the Thor. Read `peanuts/UPSTREAM-EDITS.md` before touching any upstream file — it is the
current, exact inventory of every upstream file this project has touched, and which edits are marked, queued for a
Banana Native PR, or still need attention.

## Where code goes

New code goes in Peanuts-only places, which upstream never touches and which therefore never conflict on a rebase:

- `android/app/src/main/java/dev/peanuts/` — launcher side: the `Presentation` on the second display, its view, the
  JNI bridge, the library/build UI, the companion catalog.
- `android/app/src/peanuts/` — the Peanuts flavor source set (icons, strings, manifest fragment); does not exist
  upstream at all.
- `dm/peanuts/` — host side, plain C: the companion package loader, guest readers, the per-frame tick, input
  injection, the on-device NSP/XCI importer, the `.nup` RomFS provider, the companion runtime port (`dm/peanuts/rt/`).
- `peanuts/` — companion packages, docs, tests, build/dev scripts.

**A touch to an upstream file is not "always one line."** It is honest, in one of these categories, and every one of
them is marked:

- **A hook**: a small, marked call-out into Peanuts code — the common case, still kept as close to one line as the
  site allows. The current allowed hook sites are listed in `PEANUTS.md`; if your change needs a new one, add it
  there in the same commit and say why nowhere else would do.
- **A Peanuts feature that has to live inside an upstream file** (rare — e.g. logic that can't be factored out
  without upstream's own cooperation): mark the whole block, `PEANUTS begin` / `PEANUTS end`, and say in the commit
  why it isn't a hook.
- **An upstream-candidate fix**: something that would be correct for Banana Native regardless of Peanuts, found
  while working on Peanuts. Mark it `PEANUTS(upstream-candidate)`, add it to `peanuts/UPSTREAM-EDITS.md`'s queue, and
  send it to Banana Native as its own PR — it arrives back here by the next rebase either way, so don't let it carry
  as a permanent local diff.
- **A game tweak**: a single-key change to one title's `game.tsv` (`mem_map_limit`, `fps_target`, a vsync pin — the
  three that exist today). Until the `dm/games/<id>/peanuts.tsv` overlay file lands (a loader change, tracked in
  `peanuts/docs/UPSTREAM.md` §4 as not-yet-done), these are marked, single-line, in-place edits to the upstream
  `game.tsv`; once the overlay file exists, new tweaks go there instead and leave `game.tsv` untouched.

Never reformat or "fix" anything else in an upstream file while you're in there for an unrelated reason — a general
fix belongs in its own upstream-candidate commit, as above, not folded into whatever else you were doing.
`peanuts/scripts/upstream-update.sh` checks, **at the level of each individual diff hunk** (not "does this file
contain the word PEANUTS somewhere"), that every hunk it finds in a non-Peanuts-owned, non-allow-listed file still
carries a marker, or sits inside a `PEANUTS begin`/`PEANUTS end` span — a file with one marked hook and one
unrelated, unmarked hunk fails this check. Run `peanuts/scripts/upstream-update.sh --check` to run just this check,
read-only, before you rebase or commit — don't wait for a rebase to find out a hunk needs a marker. A file that
genuinely cannot be marked line-by-line without forcing an unwanted side effect (today: the two shader-digest files,
see `peanuts/docs/UPSTREAM.md` §3.3) goes on the explicit allow-list, `peanuts/upstream-unmarked.txt`, with a reason
— not silently exempted in code.

Settings go through Banana Native's own mechanisms: `env.txt` keys prefixed `PN_*`, rows in `settings.tsv`, lines
over `DM_CTL`. Don't add a new settings UI inside an upstream class.

## The shader chain is one thing, not two

`dm/gfx/tools/dmsh_*.py` (the Apple/translator chain) and `dm/gfx/tools/vk_android_*.py` (the Android/Vulkan chain)
are not independent: `DMSH_VK_CHAIN` covers `DMSH_CHAIN`, so a change to *either* moves `DMSH_VK_CHAIN` and forces
re-translation on every device. **Never change `vk_android_build.py`, `vk_android_spv.py`, `vk_android_delta.py`,
`vk_android_win.py`, `dmsh_abi_vk.h`, or any `dmsh_*` file without following the full procedure in
`peanuts/docs/UPSTREAM.md` §3.3**: regenerate the chain header with the tools' own commands (never hand-edit the
`#define`s), then a full build against a known game, byte-compared against the previous library version. A
`vk_android_same.tsv` row needs an evidence string naming the actual build and the actual byte comparison performed
— "by inspection of the one changed line" is not evidence. If you can't do the byte comparison yet (no device
available, say), leave the row out; an under-justified row is worse than a missing one, because the next person
trusts it without re-checking.

## A new-game pull request

A new-game pull request adds one game's profile and nothing else. Read NEW-GAME.md for the steps.

Before you open it
- Base it on the `dev` branch, never on `main`. Rebase on it; do not merge it in.
- One game per pull request. A host, renderer, rewriter, shader-tool or launcher change goes in its own pull request first. If your profile needs it, write "Depends on: #<n>" and keep the profile a draft until that one lands.
- Touch only `dm/games/<title ID>/` and the game's row in `dm/games/README.md`.
- Switch 1 games only. A game that needs a network sign-in to reach play cannot be supported, since Peanuts is always offline.

Never in the pull request
- Binaries of any kind, `vk_prewarm.bin` included. Attach your runs' `.keys` files instead; we build the seed.
- `peanuts/release-titles.txt`, `peanuts/games.tsv`, `nxGames`, `readiness.tsv`, the Supported games tables, `games.json` or the issue forms. We add the game after our own device runs and credit you as its contributor.
- A file over 10 MB. If your shader tables are larger, open an issue first.
- Game files, keys, or anything extracted from a game.

The profile
- `game.tsv`: `name`, a `build` row per version with its display version filled in, `package` equal to the highest build, `shader_note`, `# PEANUTS:` on the lines you add. Only keys Android reads: no `pad_layout`, no `zip_store .metallib`.
- Shader tables exactly as the current `shader_split.py` wrote them, with LF line endings.
- A `recommend` row needs a comment above it naming the note section or `docs/reference` page where the fact was measured.
- The note from the template: Status, Tested on, Builds, Open. Status says only what you ran, Open lists what you did not, and they must not disagree. Memory values you did not measure are marked as the survey's guess.

Frame rate
- Give the game the frame rates it can hold, with the method that fits its engine. The methods, cheapest first, with their profile rows and an example game each, are in `FPS-METHODS.md` (`sh peanuts/scripts/fps-survey.sh` lists every profile's rows by method). In short: test whether the game steps by elapsed time (at caps of 30 and 60, a 3-second walk lands in the same place). If it does, display pacing is enough. If movement halves at 30, it counts frames, and you need an engine limiter found by name, an FPSLocker Warehouse row for the exact build, a community timing plan, or a frame step. If nothing works safely, leave the game's own pacing (Game owned) and say why in the note.
- Measure every cap the profile offers: the median and slowest frame times, and the game speed (10 game seconds take 10 s, from an idle animation or a clock in the game). Put the numbers in the note's Status, per cap. A cap you did not measure is not offered, or is marked experimental (`fps_limit_present 1`) and named in Open.
- The default cap (`fps_limit` or `fps_target`) is one you measured.
- Do not edit `dm/tools/tests/test_android_frame_rate_games.py`: we pin the caps when the game goes on the release list.

Prewarm seed (first launch without stutter)
- The app writes the pipeline keys a run made to `/sdcard/Android/data/<app package>/files/temp/<title ID>/vk_pipelines.keys` (the test app: `dev.nxnative.android.peanutstest`), beside that driver's pipeline cache. It is one file per game, rewritten by each run, so copy it out (`adb pull`) after each driver's run and name it after the driver.
- Start from a fresh cache: delete that `temp/<title ID>` folder before the run, then play the startup, the menus and the gameplay you tested. Attach the keys files to the pull request, with the driver each came from.
- Do not commit `vk_prewarm.bin`. We build the seed from your keys when the game lands (`python3 dm/tools/vk_prewarm.py seed <title ID> <keys files>`; every file must come from the same host build), and check that a cold first launch draws right with it.

Fast Prepare (targeted shader split)
- So that a phone's Prepare reads only the game files that hold shaders, add `shader_files.tsv`: run one full split with `DM_SPLIT_FILES=dm/games/<title ID>/shader_files.tsv` (`android/SHADERS.md`). Then run a targeted split (`DM_SPLIT_TARGETED=1`) of the same build into a separate output folder, and check that its `shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv` and `pso_pairs.tsv` match the full split's. Give both times in the pull request.
- If the shader map is far larger than what play reaches (tens of MB), open an issue first. A `shader_seen.tsv` of the keys real play uses may be the answer, and we decide that together.
- A game with several builds lists the pairs a build may lack in `pso_pairs_optional.tsv`, so a targeted split of each build still matches.
- A game whose shaders are all compiled at run time has no game file to target, so it needs no `shader_files.tsv`. Say so in the note, and give the run-time shader capture's count and, if its empty save waits on a screen, the `capture_keys` row it needs.

Checks (paste the result lines)
- `python3 dm/tools/docs_patches.py`, and commit its output.
- For a profile with `shader_files.tsv`: the full and targeted split times, and that their tables match.
- `python3 -m unittest discover -s dm/tools/tests`: give the failure count at your head and at its merge-base. A pull request adds none.
- `check-profiles.sh <title ID>`, `gen_runtime_profile.py check-all`.

Device evidence
- To land as a profile: a named phone (model, chip, RAM, Android version), the driver and its exact version, a log attached (Settings > Share log), and what you played.
- To be considered for the release list: an Adreno phone with 12 GB or more, and a run on a named Turnip build (not 27.0.0-R3). At least 10 minutes of play past the first scene, with its log. The median and slowest frame times and the game speed at each cap the profile offers (10 game seconds should take 10 s). The memory peak. A run on Qualcomm's own driver as well, with its log, counts as a bonus. Runs on Mali, Xclipse or PowerVR phones, or only in menus, do not count.
- Say what you did not check.

## Adding a supported game

For a game that has no profile yet, in Peanuts or in Banana Native, [NEW-GAME.md](NEW-GAME.md) has the steps, commands and pitfalls end to end.

A "supported game" here means: this title boots and plays acceptably through Banana Native, and (usually) has a
dual-screen companion installed and loading *for the specific pack build you're running* — a companion can support
fewer builds than the pack profile does (Mario Kart 8 Deluxe's companion covering only 4.0.0 while the pack also
lists 4.0.1 is the live example). Getting a title running is two separate jobs that often happen together but don't
have to:

### 1. Get the game running through Banana Native

This is upstream's job, not Peanuts'. If the title doesn't boot, crashes, or renders wrong under plain Banana Native,
fix it there (see Banana Native's own `AGENTS.md` and `docs/agents/`) and bring the fix back by the next
`upstream-update.sh` rebase. Do not patch around a Banana Native bug inside `dm/peanuts/` — that just creates a
second place the same bug has to be fixed later.

### 2. Make the title's own profile correct

Per-game constants and shader tables live in Banana Native's own `dm/games/<title ID>/`, keyed by the base game's
title ID (`game.tsv` plus the shader tables `shader_split.py` writes). Read `dm/games/README.md` for what each file
and key means before touching one; the important `game.tsv` keys for this project's purposes:

- `name` — the game's display name.
- `build` — one row per known build: version number, display version string, and the `main=`/`subsdk0=`/`sdk=`
  build-ID hashes for that build's modules. A game's companion checks this before it loads (see below).
- `package` — which listed build the shader tables were actually built against. A dump at any *other listed* build
  still boots, but its first build on that dump runs a full shader scan instead of a fast targeted one (Super Mario
  Bros. Wonder v65536 against a v327680 package is the live example) — that's slower, not broken.
- Shader-chain keys (`shader_map.tsv`, `shader_facts.tsv`, `shader_pairs.tsv`, `pso_pairs.tsv`, and the optional
  hand-written `shader_facts_manual.tsv`) — upstream's shader translation inputs, not Peanuts', but a new title needs
  them before anything else works.

**Verifying a version**: build the title, boot it, and confirm the build ID the host reports (`module_t.build_id`,
logged on boot) matches the `build` row you're targeting. If your own dump's build isn't in `game.tsv` at all, that's
a real gap, not a bug to patch around — Dragon Quest III HD-2D Remake (dump v131072, profile only lists v262144) is
the current example of a build that should be caught before building, but isn't yet (no pre-build version refusal
exists today); flagging this gap is more useful than quietly working around it.

### 3. Match or add a companion

Companions are Eden Duo's package format: a `<TITLEID>-<Name>-<version>.dsmod.zip`, catalog entry
`dev.peanuts.CatalogActivity` installs into `<title ID>-work/peanuts/<Name>/` beside the pack. If
[eden-duo-companions](https://github.com/igawa6/eden-duo-companions) already has a companion for the title *and the
pack build you're targeting* (check its manifest's `build_ids` against the `game.tsv` build row — don't assume a
companion that exists for one build of a title covers all of them), nothing more is needed here beyond verifying it
loads and its live pages read correctly — Peanuts' native-module host (`dm/peanuts/rt/`) runs the same runtime Eden
Duo ships. If no companion exists for your build yet, that's a separate companion-authoring project against
eden-duo-companions, not something built inside this repo; see that project's own process. Whether a companion with
no native module at all (an Eden-Duo-only "no module" package) loads here has not been independently re-verified
against the current `dm/peanuts/rt/mods/mod_module.cpp` behavior — check `dm/peanuts/rt/PORT.md`'s own description
before asserting either way in a doc or a commit.

### 4. The shader map (where a title's shaders don't come from any file)

A few engines compile some shaders at boot instead of shipping them in any game file (Fire Emblem: Three Houses is
the known case so far: nine GLSLC-compiled shaders). A pack built from files alone will skip those draws — expect
black panels or missing text, not a crash. The fix is the automated run-time shader capture
(`dev.peanuts.build.RuntimeShaders`): it starts the game once after Prepare with capture enabled, lets it run long
enough to compile those shaders itself, grabs the outputs, and resumes the split/translate job with them included.
If you hit this symptom on a new title, look for it before assuming the pack is broken.

## Fixing a bug

1. **Reproduce** on the device, with the system driver and a Turnip driver both, if the symptom could be
   driver-dependent. Note the title, its build ID, the driver, and exactly what's wrong (a picture, a crash address,
   a frame rate number) — not a vague description.
2. **Log.** Pull the run's log (`/sdcard/Android/data/<app>/files/logs/`, or `adb logcat -d -b crash` for a crash).
   `[RED]` lines mark something unimplemented or wrong on purpose — read them before anything else. Banana Native's
   `scripts/log_digest.py` summarizes a long log to ~50-80 lines if you need the shape of a run fast.
3. **Symbolicate a native crash** with `llvm-addr2line` against the unstripped library that actually crashed. Note
   that Peanuts' companion runtime and `dm/peanuts/` are linked **into the host**, not into `libnxlaunch.so` alone —
   a companion-side crash, like a game/host-side one, is almost always `libnxhost_<title ID>.so`:
   ```
   llvm-addr2line -f -C -e <path to the unstripped libnxhost_<id>.so> <pc offset from the crash log>
   ```
   Use the **unstripped** build output, not what's packaged in a release APK; match debug symbols to the exact build
   that produced the crash log, not a newer rebuild.
4. **Find the cause before changing code.** As Banana Native's own `AGENTS.md` puts it: find the faulting
   instruction, the value that went wrong and who wrote it, the Horizon/NVN/Vulkan semantics being violated — then
   fix it there. A stub, a skipped call, a clamp, a retry, or a longer timeout is a workaround, not a fix, and gets
   flagged as one in the comment and the commit message if you use it anyway because the real cause is out of reach
   (the game's own code, or a platform/driver limit).
5. **Decide where the fix goes.** A fix that holds for every title belongs in the core (`dm/peanuts/`, or upstream in
   Banana Native if it's not Peanuts-specific at all). A fix that's genuinely true of only one title's own code goes
   in that title's profile (`patches.py`, gated by build ID) — `patches.py` is an upstream Banana Native file, so
   this still needs a `PEANUTS` marker on the edit, same as any other upstream touch. Never a per-title `if` branch
   inside shared code.
6. **Verify on a real device.** Not "it builds" — actually boot the title on the Thor (or another dual-screen
   handheld), confirm the companion still loads and its pages still read correctly, and say what you checked and on
   what driver.

## Testing

- **Host unit tests** live in `dm/host/tests/` (e.g. `t_nup_dump.c`, `t_android_gmem.c`, `t_android_pwlist.c`) — run
  through Banana Native's own CMake/CTest build, not duplicated here.
- **Importer tests** live in `dm/peanuts/import/tests/` (`comp_test.c` + `comp_check.py`, checking the compression
  layer against the reference LZ4). Build the importer standalone for these:
  `cmake -S dm/peanuts/import -B build && cmake --build build`, then run `./build/comp_test` and
  `python3 dm/peanuts/import/tests/comp_check.py`.
- **Importer under AddressSanitizer**: there is no built-in CMake ASan option in
  `dm/peanuts/import/CMakeLists.txt` today — add it yourself for the check
  (`cmake -S dm/peanuts/import -B build-asan -DCMAKE_C_FLAGS="-fsanitize=address -fno-omit-frame-pointer"`, then
  build and run `comp_test`/`nximport_cli` against a representative NSP/XCI). If you add a real ASan build target,
  that's a small, marked (if it touches the file at all — it's Peanuts-owned, so it doesn't need marking) addition
  worth keeping.
- **Companion runtime test**: `dm/peanuts/rt/test/rt_test.cpp` — a Linux-host test that loads a real package against
  fake guest memory and renders PNGs; build with `PEANUTS_RT_TEST` on (see `dm/peanuts/rt/CMakeLists.txt`).

## Coding conventions

- **`PEANUTS` markers on every hunk that touches an upstream file**, not just somewhere in the file — see "Where
  code goes" above for the categories and their exact marker forms. `peanuts/scripts/upstream-update.sh --check`
  checks this at hunk granularity on demand (run it before you commit); the plain form runs the same check after a
  real rebase. If a hook moved, fix the marker in place and re-run the script.
- **Keep upstream diffs minimal.** If you find yourself editing more than a line or two of an upstream file, stop and
  ask whether this is really a hook, or actually a Banana Native change that belongs in a PR to Banana Native
  instead. Never reformat or restyle a file you didn't otherwise need to touch.
- **English everywhere** — code, comments, log messages, UI text — matching Banana Native's own convention, including
  `[RED] ` as the prefix for anything unimplemented or wrong.
- **Per-game values stay in profiles**, never hardcoded with a title-ID branch in shared code.
- **Match the surrounding code's density and style** rather than introducing a new one.

## Commit and PR rules

- **Never commit game files, keys, `.nup`s, packs, shader libraries, or anything extracted from a game.** These stay
  with the game's own dump/work folder on the device or build machine, never in the repository, never attached to an
  issue or PR. Logs, screenshots, title IDs, build IDs, and offsets are fine. If you set up a pre-commit hook, have
  it refuse a commit touching a `.nup`, `.so`, `.lib`, an APK, `prod.keys`, or `title.keys` — belt-and-suspenders on
  top of the rule above, not a replacement for actually checking what you're staging.
- **Keep your game dump/work folder outside the repository and git-ignored**, and delete a title's folder once
  you're done with it rather than letting it accumulate — this is a disk-budget practice, not a licensing one, but it
  also makes "did I accidentally stage game data" much easier to answer by inspection (there's nothing there to
  stage).
- **One change per branch**, based on the stable `dev` branch and rebased on it, never merged in. `dev` follows each
  release cycle's line (today `dev-0.1.10`); `main` moves only at a release, so a pull request never targets `main`.
- **Commit messages** say what changed and why: the symptom, the cause, the fix, what was measured or checked on the
  device. No secrets, no local paths, no device serials, no personal identifiers in a committed file.
- **No binaries.** No `.so`, no APK, no GPU driver, no shader library, no prewarm seed, no pack. Everything is built
  from source.
- **Say what you did not check.** If you only tested on the system driver, or only reached the title screen, say so.

### Publishing rules (strict, checked before every push)

`peanuts/scripts/check-publish.sh` must pass before anything is pushed to GitHub or any other shared remote;
`peanuts/scripts/install-hooks.sh` installs a `commit-msg` and a `pre-push` hook that enforce it.

- **No AI attribution, ever.** The person who submits a change is its author and committer. No AI tool or vendor
  appears as an author, a committer, a `Co-Authored-By` / `Signed-off-by` / `Assisted-by` trailer, or a "Generated
  with …" line (Claude, Anthropic, Codex, OpenAI, ChatGPT, Copilot, Gemini, Cursor, Devin, …). A commit that has one
  is rewritten before it is pushed.
- **No private data in a committed file.** No LAN addresses, no adb-over-network addresses, no home or mount paths
  (your home folder, your mount points, Windows user folders), no assistant work folders, no personal e-mail addresses, no hostnames
  of your own machines. Use placeholders: `<work dir>`, `<your dump folder>`, `<device ip>`.
- **The public history starts at the import.** Banana Native's history before the fork point stays upstream; the
  public repository begins with one commit that imports Banana Native at that point (crediting it, its license and
  its authors in the message and in the README), followed by Peanuts' own commits.

### Release prewarm inputs

For a signed generic-host build, set `PN_PREWARM_SEEDS` to a directory containing
`<title ID>.seed` for each selected title listed in `peanuts/prewarm-sha256.tsv`.
The build checks these captures against the recorded SHA-256 values. To reproduce
a published version, extract its signed APK's `assets/profiles/*.seed` into that
directory with `unzip -j`. Development builds can collect their own pipelines
during play when a seed is absent.

### Checks of a release APK

Run both on the APK that is to be published, after it is built and signed:

- `ANDROID_HOME=<the Android SDK> python3 peanuts/scripts/check-min-api.py <apk>` must print "nothing newer than API 33". It lists every platform method
  and field the app's code calls that is newer than the app's minimum Android version; the compiler does not say so, and such a call closes the app on
  older phones (0.1.1 and 0.1.2 did, on Android 13). It reads the code without its version checks, so it stays a check at API 33: the calls behind
  an `SDK_INT >= 33` check would be listed at 31.
- `ANDROID_HOME=<the Android SDK> python3 dm/tools/tests/test_android_api31.py` must pass. The app installs on Android 12 (minSdk 31): every API 32+
  call needs an `if (Build.VERSION.SDK_INT >= 33)` check with the call unchanged inside it, and its lint gate requires 0 NewApi and 0 InlinedApi.
- `python3 peanuts/scripts/check-apk-holes.py <apk>` must print "no holes". Updating an APK in place can leave a replaced entry in the file as dead bytes.
  Peanuts release-type builds recreate the final archive on every invocation and reuse the build outputs that feed it.

## Using AI agents

You may use coding tools, AI ones included, to prepare a change — but you are its author, you answer for it, and
nothing in the repository or its history attributes it to a tool (see "Publishing rules" above). The checklist below
applies to every change however it was produced. See `peanuts/docs/AGENTS.md` for the compact, machine-facing version.

- **Verify on a real device before reporting something done.** A build succeeding, or code compiling, is not
  evidence a title boots, a companion loads, or a fix works. Every claim in a status note or commit message should
  trace back to something actually run on the Thor (or another dual-screen handheld) and watched.
- **No game data, keys, or anything extracted from a game in a commit** — ever, regardless of how the commit was
  produced. Print key *names*, never values; describe a crash by address and symbol, never by pasting the game's own
  decompiled code or assets into a commit, issue, or chat log.
- **No shader libraries, packs, or `.nup` files get published**, anywhere, by anyone, agent or human — they're
  derived directly from someone's own game files (see the legal note in `peanuts/docs/README.github.md`).
- **Keep diffs small and reviewable.** A large, speculative, multi-concern change is hard to verify and hard to
  rebase past the next Banana Native update. Prefer several small, verified commits to one large unverified one.
- **Cite the evidence.** A log excerpt, a crash address with its symbolication, a frame-rate measurement, a
  screenshot filename — something a reviewer (human or otherwise) can check independently, not just a claim that it
  works.
- **Respect each upstream's own policy.** The Eden Duo companion runtime is maintained separately, as its own
  project with its own contribution policy; nothing from this repository is sent to it or to Eden. Changes meant for
  Banana Native go through its own repository and rules.

## How changes get in

CI runs on every pull request (`.github/workflows/peanuts.yml`): the Peanuts tests, the catalog and issue-form checks,
the profile checks and the docs checks, reporting what your change makes fail. Still run the checks yourself and say in the PR description what ran, on what device and driver, and what was not checked.
