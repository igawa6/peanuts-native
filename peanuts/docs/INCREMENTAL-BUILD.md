# A Build after an update: only what changed

Issue #120. A Build on the phone that comes after another one of the same game (an update of the game, an added DLC, an Import again, a Retry, an app update
whose profile gained a game version) keeps every shader of the pack's older library whose translation would come out the same, and translates only the others.
The shaders step is most of a Build's time (Animal Crossing: New Horizons on an AYN Thor, 2026-11-16: rewrite 4 min, split 51 s, shaders 2 min 40 s for 9,766
shaders, pack 8 s; a profile of 60,000 to 80,000 shaders takes 15 to 25 minutes there).

Upstream already keeps an older library's entries, but only with a manifest of the build in the game's profile (`vk_delta.tsv`, from a full Mac build:
[android/SHADERS.md, Delta updates](../../android/SHADERS.md#delta-updates)). 7 of 160 profiles have one, all of them Switch 2 titles.

On a computer (the builder, `scripts/pack_android.py` without the app) none of this keeps anything: there is no `PN_REUSE_DIR`, so every build makes
every part again as before. The builder gets two parts of this all the same, as they make every build faster with the same outputs: the walk of a `.nup` by
its nodes and, on Linux, the split's Yaz0 in C ([BUILDER.md, Yaz0](BUILDER.md#yaz0)).

## What is kept

The shaders step runs [`dm/peanuts/reuse/shader_reuse.py`](../../dm/peanuts/reuse/shader_reuse.py) with `vk_android_build.py`'s arguments. It looks at each
library in the pack's `shaders/` of this app's `DMSH_VK_CHAIN` and this game:

| The older library | Kept |
|---|---|
| built from the same profile inputs (`dmsh_chain.inputs`: the shader tables and the `nvn_*` / `shader_*` rows), the profile having its own `pso_pairs.tsv` | every shader it has: a result is a function of the tools, those inputs and the shader's key (`vk_android_build.py` keys its own result cache on exactly that) |
| built from other inputs (the app's profile gained a game version), or from the same ones by a profile without its own `pso_pairs.tsv`, with a record of each shader's part of them | each shader whose part is the same: its stage, its `shader_facts.tsv` row, its pairs (with the split's `pso_pairs.tsv` where the profile has none), its `dual_want` / `bf_want` membership, the `geo_mesh` and conflict rows that name it, the facts of the mesh programs drawn with it, the profile's `nvn_*` / `shader_*` rows; a vertex shader also its pixel shaders' parts (the parts the Mac's per-shader cache key takes, `dmsh_build.py shader_key`) |
| of another chain version, another game, with no record, or with a record of another translator or other translation options | nothing |

The record is written when a library is made: `no_backup/reuse/<title ID>/shaders/<library id>.tsv`, one digest and the entry's hash a shader, the
translator's source tree (`dm/gfx/tools/xlate_tree.py`) and the translation options (`XLATE_DEBUG` / `XLATE_REDUCED`, `dmsh_chain.py options`). Every keep
needs it, the same inputs too: the translator's sources are not in `DMSH_CHAIN` (its lock fingerprints the Mac's dlls), so an app update can bring a translator
library built from other sources with the same version, and only the record says which one made the library. The options are in `DMSH_CHAIN`'s version, by
which `vk_android_build.py` refuses options its headers were not written with; the record names them as well, so a keep does not rest on that check. A
library's entries must be the ones its record names (the library's id covers it only up to its blob). A pack made before this app has no record, so its first
build with this app keeps nothing; the next one has it.

Why the profile's own `pso_pairs.tsv`: a profile without one (most of them) pads its vertex shaders with the split's copy (`dmsh_build.py load_pairs`), which
the inputs digest does not cover; an added DLC can add pairs there while the profile stays the same. Such a profile keeps by the record, whose digests take the
pairs as `load_pairs` takes them (a review found this before it was published; `test_split_pairs_are_not_taken_for_the_same_inputs`).

Either way only a shader this build makes is kept (`vk_android_build.py`'s own selection: the profile's keys less the absences validated against the split,
`DMSH_VK_ABSENT_FILE`, and of them only those the split found: with Try anyway the tool leaves out a shader its package lacks, but never checks a kept one), and a vertex shader only when the pixel shaders paired with it that this build makes are those the older library holds. Its outputs
are padded with the inputs of the pixel shaders the build makes (`PS_INPUTS`), so an add-on installed since the last build (its pixel shaders absent then)
changes the vertex shaders it pairs with although the profile stays the same (`test_a_pixel_shader_that_comes_with_an_addon`).

How it is kept: the kept shaders and their entries' hashes go into a manifest of this build's version, game and inputs, in a copy of the profile folder
(`no_backup/reuse/<title ID>/profile/`, `DM_GAME` pointed at it), and `vk_android_build.py`'s own delta path keeps them byte for byte. No file of the Vulkan
chain changes, so the library version does not move.

Where the older library comes from: the pack's `shaders/`, and a copy of the last library this game's shaders step wrote, kept in the app's own storage
(`no_backup/reuse/<title ID>/library/v-<V>/`, `keep_library`), which `shader_reuse.py` adds to `DMSH_VK_DELTA_FROM` for itself and for `vk_android_build.py`.
So a game whose pack's library is gone keeps its shaders all the same: the build deleted (Settings › Storage, Remove game data), another Build folder, a pack
that was moved. Only this version's copy is kept, at most 4 GB for every game's copies together, the modules' and the split's kept results too, and the
full check's own folder while it is there, and none that would leave less than 2 GB free (`shader_reuse.py room`): the room under the cap comes first from this game's split results (a scan of a few seconds
makes them again), then from the games built longest ago; that is decided before any other copy goes. The pack's library and the copy count once (the same library). Settings › Storage lists each game's cache under Build caches (not ticked), and deletes one only while no build or full check of the game runs: they hold
a lock beside the folder (`no_backup/reuse/<title ID>.lock`, `StorageScan.reuseLock`), which works across the app's processes.

## The check, and what happens when it fails

A sample of the shaders that could be kept is translated anyway: one in 50, at least 16, at most 1,000, picked by a hash of the key, and at least 4 of each
class (each stage, and vertex shaders with pixel shaders that pad them apart from those without), so a rule that misses one class is checked on it too. After the library is
written, every sample entry must be in it and the older library's byte for byte. If one differs (the rule missed something for this game), or one is not in
the library or cannot be read there (its translation failed now, or the tool left it out: the sample holds only shaders this build makes and the older
library had, so this build did not make what it made before), the kept shaders are deleted and the tool runs again with the profile as it is: a full
translation, which keeps what it translated already. Before, up to half of the sample could be missing and the kept shaders still stood. The log says `[RED] reuse check: …`. The record that led to it is deleted.

Other cases that lead to a translation: an older entry that cannot be read, fewer than 32 shaders to keep, a run that stopped after keeping and before its check
(the kept results are deleted at the next start: `.pn-reuse-unchecked` in the results folder), a failed step.

What the results folder keeps across runs is checked too. `vk_android_build.py` keys a result by the tools, the profile inputs and the key alone, and the
folder is the title's (`no_backup/xlate/<title ID>`) until a job is done:

- A vertex shader translated in a run that kept its pixel shaders is padded with the kept entries' inputs, so `.pn-reuse-unchecked` lists it with the kept
  shaders: a failed check or a stop before it translates it again.
- `.pn-reuse-deps` beside the results records the run's output folder, the translator's sources, the entries it kept and what each result was made with
  (its part of the profile and, for a vertex shader, the pixel shaders the build made). Before the tool runs:
  - every result goes unless the run is a Resume of the same job (the same output folder) with the same translator: another job's translations, the sample's
    least, are not this one's to take (the check must compare a translation of now with the older library), and the translator's sources are in neither the
    result's key nor `DMSH_VK_CHAIN`;
  - a result made with another part goes (a job that did not finish, then an add-on imported);
  - an entry an earlier run kept goes unless this run keeps it with the same hash: the tool writes a kept entry as a result, which a later run would take as
    a translation, unchecked.

  A Resume of the same job keeps its translations, the sample's too. A run with `PN_REUSE=0` (or the profile's own manifest) deletes the entries an earlier
  run kept and the tags, so the next run takes none of its results by tags it did not write.

The checks that were there stay: the library's own check, the split against the profile, every module translated now through spirv-val (a kept one went
through it when it was made).

## The full check, later

The sample checks a build's kept shaders at once; the full check checks every one of them later, while the phone is plugged in. A build that kept shaders
leaves `no_backup/reuse/<title ID>/check.todo` (the library's id) and, beside it, the job's path (`check.job`, written when the job ends). The packer (`PackService`) then runs the done job's
shaders step again with `--check` first (`shader_reuse.py full_check`): every shader translated again as a build from nothing, into
`no_backup/reuse/<title ID>/check/` (results of its own: the build's results hold the kept entries), then each entry of the library compared with that
translation (`ondevice/logs/check.log`).

- When: at the end of a build (`dev.peanuts.build.BuildService`, after graphics setup and the companion), and from the launcher's 30 s timer while the app
  runs (`dev.peanuts.ui.Library`, at most every two minutes), each only while the phone is plugged in with 40 % or more and no game runs
  (`dev.peanuts.build.ReuseCheck`; plugged in, not "charging": with a charge limit or bypass charging Android says the phone is not charging). The
  notification says "Checking <game>'s shaders"; it is one of its own, so a build's notification from before (one that failed, one that is ready) stays. A
  check's start never runs a job (a job an app update left waits for the user).
- It changes nothing of the job's state: its `job.json` is neither written nor sent to the launcher (a build that binds meanwhile must not read "done" for
  its own job), its progress goes under a step of its own, and a timeout or a pause leaves it done. Its lines go to the job's logs (`check.log`, and the
  packer's own lines to `pack.log`), and the shaders step's `absent-shaders.tsv` in the job's folder is written again. The game plays as usual: the library is only read. The job's
  `split/` stays until the check is done.
- It stops when a game starts (checked every 3 s, and once more before the tool runs), when the packer's policy pauses (battery, heat; a Go on anyway
  remembered for the phone counts for the check as for a build), or when a job starts
  (the job goes first: the packer starts it once the check has stopped; a Pause while it waits pauses that job). The stop is sent again every 3 s while the tool runs (one sent before its run began
  is lost), and a game that still runs beside it 20 s later ends the packer's process. It goes on another time from the results it kept.
- A check is asked only with a done job (one not done yet starts it at its end). One that this job can never make is dropped: its split gone, a split of
  another selection, an app update since the build. One that fails (the tool ended with an error, storage full, or the packer's process ended while it ran:
  `check.running`, counted at the next start) is tried again, at most three times (`check.tries`), then dropped. Before, such a check was asked again every two minutes for good and held every other game's check. A build with code mods
  is never the check's job.
- Before it writes, the check asks for room as a kept copy does (`room`, `check_bytes`): about twice the library and 4 KiB a shader (a library of its
  own, and a result file for each shader with the same modules and a JSON head; by this count about 230 MB for ACNH 3.0.3's library of 96 MB and 580 MB
  for Mario Kart 8 Deluxe 4.0.1's of 233 MB). Its folder counts toward the cap while it is there, what a stopped check left counts toward what it needs,
  and the room comes from this game's split results first, then from the games built longest ago. Without room the check is dropped (below). A build keeps
  no shader whose check could never fit under the cap beside the game's copies that stay (the kept copy of its library and its modules':
  `PN_REUSE_KEEP_MB=0`, a library of about a third of the cap or more): the log says
  `reuse: the full check of shaders kept from … would need about N MB beside this game's copies of M MB …: none kept`. The job's `split/` is the job's, not a kept copy: it is on storage
  already when the check starts, so the free space the check asks about has it, and it goes when the check does. A full translation that differed takes
  the kept copy's place by a rename out of the check's folder, with no second copy.
- A dropped check, whatever dropped it (also an app update, no room for it, and a library to check that neither the pack nor the kept copy holds any more), takes the
  records of the game's shaders (`shaders/`) and the kept copy of its library (`library/`) with it (`CheckDue.drop`, `shader_reuse.py drop_check`): its kept
  shaders were checked by the sample alone, so the next build of the game keeps none and translates every one. Before, the records stayed and the next build
  kept the same entries again, so a wrong one the sample missed could stay in the pack for good. The records go first: a stop in between leaves the check
  due, with no record to keep by.
- All the same: `checked.tsv` says so, and the check's files and the split go. One that differs (or a shader only one of them has): the full translation is what
  the game should have. The records and the kept copy go first, then it takes the library's place in the pack (a `.partial`, flushed to storage, then a
  rename) and becomes the record and the kept copy; `check.failed` says how many differed and whether the pack's library was replaced, `check.log` has a
  `[RED] reuse full check` line, and a notification says how many shaders came out different (only when the pack's library was replaced). Storage too full for the copy leaves the pack's library and
  the check due (`[RED]` line, no notification).
- `check.todo` names what the library was made from besides its version: the profile inputs and the translator's sources. After an app update that changed
  either, a translation now is not what that build should have made, so the check is dropped (the next build of the game makes a library of this app, from
  every shader).

On the Thor (ACNH 3.0.3 with its DLC, 2026-11-19, plugged in): the check started by itself at the end of an Import again and of a build in another Build
folder, and both times found all 9,766 shaders of the library a full translation's byte for byte (9,564 of them kept from earlier builds); after the Import
again it took 2 min 45 s.

## A new update or DLC: the cover asks

A built game whose base is a `.nup`, with a newer update or add-on content found beside it that the `.nup` does not hold yet, says so on its cover in Home:
the "Update" mark beside the check, and "Update 3.0.4 and 1 add-on found" in its state (`dev.peanuts.build.UpdateFound`, from the library's last scan: the
packages inside the `.nup` against the files beside it). Only an update the game's profile knows counts, and only one newer than the `.nup`'s. A tap asks
first: **Build** takes them in with Import again (its checks for missing files and free space as on the game's page; its build keeps what did not change;
when its plan would take another update than the one named, such as a newer one the profile does not know or one picked on the game's page, Import again
asks first as it does there), **Play** plays the game as it is, **Not now** asks no more for these finds (`t.<title ID>.found_seen`; a newer find asks again,
and the mark stays as "Update later", the card's line ends in ", for later"). Nothing is built without the tap. On the second screen of a dual-screen handheld, the focused cover's card says the same
under its state, and its A button reads "Update…" (A asks first, as the tap does).

On the Thor (2026-11-19): ACNH built with its update but without its DLC, then the DLC folder set. The cover showed the "Update" mark, a tap asked "An add-on
found", and its Build made a pack byte for byte the full build's in about a minute.

## Switches and the log

- `PN_REUSE=0` in the job's `ondevice/env.txt`: off, the shaders step is `vk_android_build.py` as before, the rewrite keeps no module whose key moved, and
  nothing is written for a later build (no record, no copy of the library or of a module, no split result).
- A profile whose own manifest is of the build: upstream's delta update runs as it was.
- `PN_REUSE_KEEP_MB=<n>` in `env.txt`: the kept copies of every game together (libraries, modules, the split's results and the full check's folder), at
  most n MB (4096 by default; 0 keeps none, and then no shader either, as no full check could follow).
- `shaders.log` lines: `reuse: <library>: N shaders kept (...), M translated to check them`, then `reuse check: M of M shaders translated to check are the
  older library's byte for byte; N shaders kept`, and `reuse: this library kept for the next build of this game (S MB)` (or why not). The card shows
  "Translating M new shaders (the other N are reused)" from the tool's own count.
- The ready message (the card and the notification) says what the build took from earlier builds, from the reuse lines of the steps that ran in it
  (`dev.peanuts.build.BuildReuse`): "Animal Crossing: New Horizons is ready to play. It took 1 min 16 s: 9,564 shaders, 3 parts of the game's code and 91 game files came from
  earlier builds." A build that kept nothing, or whose check failed, says nothing more. The build log has it as `reused: …`.

## The rewrite: a module whose own part did not change

A module's key (`pack_android.py rewrite_key`) covers the profile's whole `patches.py` and every `build` / `fps_decouple` row, so an app whose profile gains a game
version rewrote `sdk` and `subsdk*` again although their build IDs had not changed. The rewriter reads of the profile only what is keyed by the module's build ID:
its build's tables in the profile's `patches.py` (`CUTS`, `OBS`, `FRAME_STEP`, `CAPABILITIES`, `VARIANT_CUTS`) and, for a build no patch file registers, the
profile's `MATCH_CUTS` of the module's name and the `build` rows' IDs of that module; for `main` also the `fps_decouple` rows.
[`dm/peanuts/reuse/rewrite_reuse.py`](../../dm/peanuts/reuse/rewrite_reuse.py) records, after each rewrite, that part (with the rewriter's files and the module's
bytes), the key and the SHA-256 of `patched.bin` (`no_backup/reuse/<title ID>/rewrite/<module>.tsv`). When the key moved later, the module is kept if its part
and `patched.bin` are the recorded ones: the current key is written (`<module>: kept, its build and its part of the profile are unchanged ...` in `rewrite.log`),
as upstream does for an equivalent rewriter (`rewriter_equiv`). The launcher's own key check then finds the current key. Whenever the profile's `patches.py` has
anything for the module's build, the whole file counts, as in the key. No record, another part, another `patched.bin`, a mod's cross-module layout: rewritten.
Tests: `peanuts/tests/test_rewrite_reuse.py`. On an AYN Thor (ACNH 3.0.3, 2026-11-19): after a full build, an app whose profile gained a
`build` row (another main, the same sdk and subsdk0) kept `subsdk0` and `sdk` (10.5 s and 20.5 s of the full build) and rewrote `main`, whose
own builds' rows changed; Prepare took 3 min 37 s. The kept `sdk.patched.bin` is byte for byte (SHA-256) the one a build from nothing makes with that
profile.

The record also keeps a copy of the module's files (`<module>.*` as the rewriter names them, each by its SHA-256:
`no_backup/reuse/<title ID>/rewrite/<module>.files/`), made again only when they changed. A pack that has no rewrite of the module (another Build folder, a build
deleted) gets the copy back when the module's part is the recorded one and every file is the recorded one: the files are copied in, then the shared
`build_src.sha256`, and the current key last (`<module>: kept from the app's copy of its last rewrite ...`). Otherwise, or with a copy
that changed, the module is rewritten; a copy cut short leaves files without a key, which the rewrite replaces. Each copy goes under the 4 GB of every game's
kept copies when it is made, as the library's (`shader_reuse.py room`), and none that would leave less than 2 GB free.

`build_src.sha256` (the SHA-256 of the rewriter's files and every patch file, the same for every module) is written, for a kept module and a restored one, as a
rewrite of any module writes it now: the lines of `patch_nso.py` that write it are taken from its source and run (`rewrite_reuse.py write_build_src`), so its
list of files has one source. Before, a kept module left the file of the rewrite it was made with, and a restore the copy's where the pack had none. The key's
list of the rewriter's files (`pack_android.py REWRITER`) is not that list: it has `nso_match.py`, which the file leaves out, and of the patch files only
`sdk_patches.py`, where the file has every profile's. When those lines cannot be found or run, the module is rewritten.

Each record covers the source of the rules that wrote it, as the split's key covers `split_reuse.py`: `shader_reuse.py`'s record and each shader's part
hash `shader_reuse.py` (`RULES`), each module's part hashes `rewrite_reuse.py`. A later app whose keep rules differ keeps nothing at its first build, instead
of trusting what the earlier rules wrote.

## The rewrite on the fast cores

A build from nothing rewrites every module; on the phone that ran on one core (ACNH 3.0.3's `main` on an AYN Thor: 153 s, of which the x18 scan 33 s and the
spin wait search 61 s). `patch_nso.py` computes those two phases in pieces with `multiprocessing`, which the phone's embedded CPython cannot start (it has no
`_multiprocessing`), and the app's own process must not fork (the JVM's threads). Now:

- `scripts/pack_android.py pn_rewrite_worker` runs each module's `patch_nso.py` in a worker process of its own: `DM_SPLIT_WORKER`, the `nxpack_worker` the
  split's workers are (a Python without the app's threads), with `DM_REWRITE_J` = `PN_REWRITE_J`, which `PackService` sets to the memory plan's workers
  (`BuildMemory`: one per fast core, fewer on a phone with less memory or after a kill). Its output goes to the module's log and its progress lines to the step's
  bar as before, the pipe read in a thread of its own; a stop ends the worker and its forks (their own process group) within half a second (it waited for the
  worker's next line before, up to half a minute in the spin phase, past the 20 s after which the packer ends its process). Without the worker command, or with one worker, the rewrite runs in the app's
  process as before. A game.tsv `variant` row's second rewrite of a module (`rewrite_variants`: Animal Crossing's 60 fps speed fix) runs in the worker the same way.
- `patch_nso.py in_parallel`, where `_multiprocessing` is missing, forks its workers itself (`in_forks`, an upstream candidate): child i computes pieces i, i + n,
  …, its results go back pickled through a pipe and are put back in order, so the output is the same; a child that fails fails the rewrite, and an error in the
  parent kills the children first. The heap is frozen while the children run (`gc.freeze`): a collection in a child would copy the pages of every object it
  inherited (a model of it: 1 GB of objects and two children came to 2.4 GB). The children follow the worker's CPUs and priority when a game starts (the
  worker's game-yield watch moves only its own threads).
- The rewriter file before this change is in `dm/tools/rewriter_equiv/` (`patch_nso.py.54b9ce16465e`, 0.1.7's since #177's second pass and #290's allocator cuts, as main has it at 54b9ce16): a pack made with
  it counts as up to date (its key is one of the current rewriter's), so the update rewrites no module. It must stay the current file without the forks
  (`test_rewrite_forks.py`): it follows every change of the rewriter on the branch this lands on, and packs made before that change are rewritten.

Measured on an AYN Thor (ACNH 3.0.3, `incremental-check.py full`, 2026-11-19): the rewrite step 134 s in place of 195 s
and 203 s (two runs with the file before), the build from nothing 5 min 34 s in place of 6 min 38 s; `main`, `subsdk0` and `sdk` byte for byte (SHA-256) the
modules of those two runs.

## The split: game files that did not change

The split scans every file of the profile's list (targeted) or of the RomFS (full) on every build; its `ScanCache` keeps results only to resume a stopped split,
keyed by the job's place and the container's time, and is deleted once the split is done. [`dm/peanuts/reuse/split_reuse.py`](../../dm/peanuts/reuse/split_reuse.py)
keeps, across builds, the result of every plain file job (`<PN_REUSE_DIR>/split/`): a file inside a `.nup`, whose table of contents has its SHA-256. A job's key:

- that SHA-256 and size;
- the names and SHA-256s of the files and folders beside it whose name starts with its stem (the name without its last extension), and for a Next Level
  Games index those of every file its reader can open (below);
- the job's direct-scan entry (its recorded containers, `DM_SPLIT_DIRECT`);
- a common part: the tools' and the profile's sources (`digest_sources`), `split_reuse.py` itself and the `_nxyaz0` module (or none), Unity's common strings
  from main, the zstd dictionaries, `DM_SPLIT_SELECTED`, the split's environment and Python's version. Another common part (other tools, another main whose
  strings differ) starts the kept results over.

The stem rule ("a file's reader opens nothing beside it but files whose name starts with its stem") was checked against every reader `shader_split.py`
runs for a plain file job, `scan_file_in` and what it calls, and `direct_file` for the direct scan:

- They read the file alone: VFS3 (`vfs3_members`), Koei Tecmo's IDRK / PDRK (`idrk_shaders`; a large container goes through a temporary file), DRPF
  (`drp.py`), Criterion's Bundle 2, Alchemy's igArchive, Heaps' PAK, Three Houses' SPKG and chunked zlib (`kt_shaders`), Unreal Engine 3, Retro Studios'
  RFRM, Snowdrop, MT Framework, the Bezel Engine, Game Freak's archives, VBF (`vbf.py`), and a file no archive reader takes (`scan_whole`, `plain_blob`:
  zstd with the RomFS's dictionaries, which are in the common part, gzip, Gust's zlib, Monolith, Phyre, LZ4 and zstd frames, Yaz0; then XNB, Unity with
  the first pass's type trees, the second pass not kept, and `scan_blob` with every container it opens: SARC, NARC, SUMO, PACX4, BAHS, NDSR, NDVE,
  BNSH, MSHD, MGFX, Needle, PlatinumGames, Viper, TFXO, SLCT).
- `scan_file_in` and `direct_file` read a `.data`, `.patch`, `.debug` or `.nxpc` file as empty when `stem + '.dict'` is there, a file or a folder:
  inside the rule.
- The Next Level Games reader (`nlg_shaders`) breaks it. It takes a file by its index's magic, not by its name, and opens the index's path less five
  characters (as if it ended in `.dict`) followed by `.patch` and each name the index lists. For `Shader/level.idx` that is `Shader/leve.data`, which does
  not start with the stem `level`, and a name with a `/` leads into a folder beside it. So for a file whose first bytes are that magic, the key covers
  the files and folders whose name starts with the shorter of the stem and the name less five characters, each folder with everything in it; a name
  shorter than five characters is not kept (that reader's paths start a folder up). Luigi's Mansion 3's archives (`<name>.dict` beside `.data`,
  `.debug`, `.nxpc`) were inside the rule already.

The archive readers (LINKDATA, `.bdt`, Unreal paks, Trinity packs and the other archive jobs) open other files, but their jobs are not kept.

A kept result is written whole or not at all (a temporary file, flushed to storage, then renamed); one that does not read back (cut short by a reboot) is
deleted and its file scanned, so it does not turn the reuse off for later splits.

Before the scan the kept results go into the `ScanCache` and each kept file is a batch of its own, so only the others are scanned, in runs as before; after it the
new results are kept. A file whose result says it could not be read is never kept, nor one whose decoder failed for something other than its bytes (no
memory, a missing module: `shader_split.py unsized` used to read such a container as the file as it is, a result that would have been kept). The check: one
kept file in 50 (at least one, once three could be kept), picked by a hash of its key and a salt drawn at each split, is scanned anyway and its result must
equal the kept one as values (sets and dicts in any order); one that differs scans every file again without the kept results and deletes them. The split has no
full check later, so the sample is another one at every split (the log names the files it scanned to check). The split's own check after it stays: its tables
against the profile's.

Archive ranges (several files, ranges planned over the whole archive), files without a hash (a plaintext NSP, a folder dump) and the Unity second pass are scanned
as before. Tests: `peanuts/tests/test_split_reuse.py` (`shader_split.py` run on a `.nup` made by `dm/tools/nup_pack.py`: a second split keeps every file but the
ones it checks and makes the same outputs byte for byte, a changed and a new file are scanned, kept results that differ are caught, `PN_REUSE=0`, the stem rule,
what the Next Level Games reader opens).

On ACNH the targeted, direct split reads only 93 files at their recorded containers: kept, they take 1 s in place of 9 s. A profile of the rest put 72 % of the
split in `romfs.walk`, which the split runs three times over the RomFS: each entry's `is_dir` was a stat of its whole path, which a `.nup` resolved from its root
(103,400 files). `dm/tools/romfs.py` `walk` now goes by the image's nodes (the same names in the same order, `topdown` and a caller's pruning as before;
`peanuts/tests/test_romfs_walk.py`; an upstream candidate). With both, the split takes 12 s from nothing in place of 56 s, and 6 s with its files kept in place
of 58 s.

## Not done yet

- The split's own work on every build (reading main, planning the jobs): a few seconds since the walk by nodes.
- Archive jobs in the split's cache: their key must cover every file they read.
- The full check while the phone is idle with the app closed: Android gives the app no start then (no JobScheduler in it); it runs from a build's end and while the app is open.
- The import: Import again writes the whole `.nup` again (on an AYN Thor 11.9 GB in about 20 s: not worth it there).
- One full check a game: `check.todo` and `check.job` are one each in `no_backup/reuse/<title ID>/`. A build of the game in another Build folder replaces
  the first folder's due check with its own (or ends it, when it kept nothing), so the first folder's library keeps what only its sample checked until a later
  build there. One check for each Build folder needs a Java side that keeps one job for each.
- The split's kept results are never pruned within one common part (the files an update replaced stay, and every split loads the list of empty results
  whole). They count in the 4 GB of the kept copies, though, and go first when a copy needs room. A game of 100,000 files keeps some tens of MB; one with
  many shader files without `DM_SPLIT_SELECTED` keeps their scan results, which can be more.

## Measured (AYN Thor, ACNH 3.0.3 with its DLC, 2026-11-18 and 19)

| Run | Rewrite step | Shaders step | Whole build |
|---|---|---|---|
| Full build | 3 min 26 s (main, subsdk0, sdk) | 2 min 34 s (9,766 translated) | 7 min 29 s |
| Import again | not run (the keys unchanged, as before) | 20 s (9,570 kept, 196 translated to check) | 2 min 15 s |
| A profile change (5 new pairs in the APK's tables) | not run (the keys unchanged) | 20 s (9,555 kept, 211 translated) | the split fell back to its full scan: 7 min 10 s |
| The same with `PN_REUSE=0` | | full | the library is the run above's byte for byte (SHA-256) |
| A profile change (a new `build` row: another main, the same sdk and subsdk0), Prepare | main rewritten (2 min 53 s), sdk and subsdk0 kept | not run (inputs unchanged) | 3 min 37 s |
| The same profile, a build from nothing | all three rewritten | | `sdk.patched.bin` is the kept one byte for byte (SHA-256) |

These ran on 0.1.4, before a review's changes (the profile's own `pso_pairs.tsv` for "same inputs", the sample by class, the paired pixel shaders a build
makes). The code as it is, on 0.1.5 (`DMSH_VK_CHAIN` `dfebef5d68a9`, the 0.1.5 release's translator), 2026-11-19:

| Run (`peanuts/scripts/incremental-check.py`) | Import | Rewrite | Split | Shaders | Pack | Whole build | Byte for byte as the full build |
|---|---|---|---|---|---|---|---|
| Full build | 25 s | 3 min 26 s | 56 s | 2 min 33 s (9,766 translated) | 6 s | 7 min 31 s | |
| Import again | 29 s | not run | 58 s | 19 s (9,564 kept, 202 translated to check them, 202 of 202 equal) | 6 s | 1 min 59 s | library and modules: yes |
| A build from the base and the update alone | 27 s | 3 min 28 s | 56 s | 2 min 31 s | 8 s | 7 min 35 s | |
| Its DLC added, Import again | 25 s | not run | 56 s | 21 s (9,564 kept, 202 checked) | 4 s | 1 min 52 s | library and modules: yes |

The split as it is now (its files kept, the walk by nodes), the same scenarios, 2026-11-19:

| Run | Import | Rewrite | Split | Shaders | Pack | Whole build | Byte for byte as the full build |
|---|---|---|---|---|---|---|---|
| Full build | 23 s | 3 min 15 s | 12 s | 2 min 32 s (9,766 translated) | 6 s | 6 min 33 s | |
| Import again | 25 s | not run | 6 s (91 of 93 files kept, 2 scanned to check, the same) | 19 s (9,564 kept, 202 of 202 checked equal) | 8 s | 1 min 2 s | library and modules: yes |
| A build from the base and the update alone | 25 s | 3 min 17 s | 15 s | 2 min 30 s | 6 s | 6 min 37 s | |
| Its DLC added, Import again | 27 s | not run | 6 s (91 kept, 2 checked) | 21 s (9,564 kept, 202 checked) | 6 s | 1 min 4 s | library and modules: yes |

ACNH's DLC brings no shaders of its own that the phone's split finds (the split reads the update's RomFS, not an add-on's), so the build without it makes the
same 9,766. An Import again went from 7 min 31 s to 1 min 2 s; what is left of it is mostly the import (the whole `.nup` written again) and the 202 shaders
translated to check the kept ones. A build from nothing is a minute faster too (the split's 56 s are 12 s); its largest part is the rewrite, `main` on one core.

With the caches apart from the pack (the last library and each module's rewrite in the app's storage), the same scenarios and `new-folder`, 2026-11-19:

| Run | Import | Rewrite | Split | Shaders | Pack | Whole build | Byte for byte as the full build |
|---|---|---|---|---|---|---|---|
| Full build | 25 s | 3 min 23 s | 13 s | 3 min 42 s (9,766 translated) | 8 s | 7 min 55 s | |
| Import again | 25 s | not run | 10 s | 19 s (9,564 kept from the app's copy, 202 of 202 checked equal) | 8 s | 1 min 7 s | library and modules: yes |
| Another Build folder, empty, the app's caches kept | 27 s | 9 s (`main`, `subsdk0`, `sdk` from the app's copies) | 11 s | 19 s (9,564 kept from the app's copy) | 6 s | **1 min 16 s** | library and modules: yes |
| The base and the update alone | 26 s | 3 min 16 s | 13 s | 2 min 32 s | 4 s | 6 min 36 s | |
| Its DLC added, Import again | 24 s | not run | 12 s | 18 s (9,564 kept, 202 checked) | 8 s | 1 min 6 s | library and modules: yes |

A Build in another, empty Build folder (or after the build was deleted) took a full build's time before, 7 to 8 minutes; with the caches it is 1 min 16 s:
the `.nup` made again from the game's files, the split's files, the shaders and the three modules from the app's storage. Its log showed each cache used. The
shader library copy is 96 MB. The full build's shaders step took 70 s longer than the other build from nothing of the same run, for the same 9,766 shaders (the
phone warm from builds just before it); the copies add no time that shows (the rewrite of the build without the DLC 3 min 16 s, its shaders
2 min 32 s, as before them).

## Checking it on a phone

`peanuts/scripts/incremental-check.py` runs the scenarios above on a phone through adb and the control interface (`peanuts/docs/CONTROL.md`) and compares the
outputs byte for byte: `full` (the reference), `again` (Import again), `new-folder` (a Build in another, empty Build folder with the app's build caches
kept: the kept library copy), `dlc-later` (copies of the base and the update in the check's folder, a build from
them, then the DLC added and Import again). It drives only a development build installed beside the player's app (`-PnxAppId`, debuggable, the real packer:
`-PnxPackStage`), builds in `Tests/<title ID>/` of the app's Build folder (four folders below what the library lists, so the player's app never shows those builds), keeps the app's settings on the computer while it runs and puts them back at the end (also after
an error), and deletes its folder unless `--keep`. It picks the phone once at the start (`--serial` when there are several). Exit status 0 when every
comparison held.

```sh
python3 peanuts/scripts/incremental-check.py --package dev.nxnative.android.<suffix> --title 01006F8002326000 --out report.json
```

## Tests

`peanuts/tests/test_shader_reuse.py` `FullCheck` (due only after a build that kept shaders, a library that is a full translation, a wrong kept shader the sample missed is found and replaced in the pack, the pack replaced since, a stopped check goes on, a library another build replaced; a check dropped for an app update, another translator, a library of another version or one gone leaves no record or kept copy, and the next build translates every shader; a stop while a check is dropped leaves it due; the check's folder counts in the cap, a check without room is dropped the same way, one makes room from the games built longest ago, what a stopped check left counts toward its room, and a build keeps no shader whose check could not follow). `CheckDueJvmTest` (through `peanuts/tests/test_reuse_check.py`): a check its job cannot make, one that failed three times and one whose process ended three times are dropped with the records and the kept copy; `test_reuse_check.py` also checks that `CheckDue` names them as `shader_reuse.py` does. `peanuts/tests/test_update_found.py` (UpdateFound on the JVM: nothing new, a newer update the profile knows, one it does not, an older one, a first update, new add-ons, both, what "Not now" remembers; the cover's wiring). `peanuts/tests/test_build_reuse.py` (the ready message: the last run of each step's log, only the steps this build ran, a failed check, the words the tools print). `peanuts/tests/test_shader_reuse.py`: a first build, a rebuild with the same inputs (only the sample translated), a profile that gains shaders, a changed fact
and a new pair (exactly those and the vertex shaders they pad are translated), pairs that change in the split's `pso_pairs.tsv` (translated, not kept by the same
inputs), an add-on's pixel shaders built after a build without them (they and the vertex shaders they pad are translated), the same inputs without a record (nothing kept unless the profile has its own `pso_pairs.tsv`), a change the rule does not see (caught by the check,
then a full translation), one sample shader the new library lacks (the same; `check` counts a missing or unreadable entry as one), no record, `PN_REUSE=0`, a stop before the check, too few to check, a library of another version, the sample's classes; the kept copy (a build keeps one and puts the environment back, a build
whose pack's library is gone keeps from it, it and the pack's count once, a damaged one is skipped, only this version's, every game's under the cap with the
oldest going first, this game's split results before them, none above the cap or when storage is low); a record other keep rules wrote keeps nothing, nor one
of other translation options; each
build's library equals a full build's. `peanuts/tests/test_rewrite_reuse.py`: a new version row keeps an unchanged `sdk`, a new main build rewrites `main`, patches for
the build (in the profile or in another profile's `patches.py`) and `MATCH_CUTS` of the module rewrite it, its bytes, its `patched.bin` and the `fps_decouple`
rows count, no record, another key, `PN_REUSE=0` (nor a record or a copy written), a copy under the cap of every game's copies, a record other keep rules
wrote; `BuildSrc`: after a keep and after a restore `build_src.sha256` is the one `patch_nso.py` writes, byte for byte (the rewriter run on a small module,
skipped without Capstone 5). `peanuts/tests/test_split_reuse.py`: past the cap this game's kept results go. `peanuts/tests/test_rewrite_forks.py` `Worker`: a tool that asks
for a rerun (#177's second pass) runs again with its variables, one that asks again for the same fails. `StorageScanJvmTest`: a game's build caches are
not deleted while a build holds their lock.
