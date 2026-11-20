# Adding a game that has no profile yet

[BUILDER.md](BUILDER.md) builds a game whose profile is already in `dm/games/<title ID>/`, and stops with `no profile for <id> in dm/games` otherwise. This page covers that second case. It takes a Switch 1 game with no profile, in Peanuts or in Banana Native, to a pack that plays on the device and a pull request. It was first walked end to end with Kirby's Return to Dream Land Deluxe (`01006B601380E000`, base game only) on macOS and an AYN Thor; see [its note](../../dm/games/01006B601380E000/README.md).

Most of the work is automatic. Two things decide how long it takes:

- **Does Banana Native already have a profile?** `ls dm/games/<title ID>`. If it does, skip to [Pack](#5-pack) and [Test](#7-test-on-the-device): the profile is tested upstream and only Peanuts' list is missing it.
- **Does the game boot?** A provisional profile is a guess. A game that crashes or draws wrong needs a bring-up ([docs/agents](../../docs/agents/README.md), the [symptom index](../../docs/agents/symptoms.md)), which can take days.

Keep every game file and everything made from it outside the repository, beside the game (`<work dir>` below). Never commit or share them ([CONTRIBUTING.md](CONTRIBUTING.md#commit-and-pr-rules)).

## Crucial steps (the ones that cost hours when missed)

1. **Put the packer's Python first on `PATH`**, also for Gradle: macOS's own `python3` (3.9) cannot import `dm/games/game.py`, and Gradle's `:nxGenericProfiles` fails with `SyntaxError: f-string expression part cannot include a backslash`.
2. **Run the shader split by hand.** On a Mac without the Metal toolchain, `build_ipa_from_nsp.py` writes the provisional profile and then stops at the Metal check, before the split. Its `shader_map.tsv` is then a header only, and the pack has `0 of 0 MB` of shaders.
3. **Make the pack from the `.nup`**, the file the device plays, not from the extracted dump folder.
4. **Give the test app its own Keys and Firmware.** A test app is a separate app with its own data, and the release app's settings do not carry over. Without the firmware's shared fonts, some games crash at startup on a missing glyph (`CRASH sig=11 … x1=0x25a1`: U+25A1). Such a game goes into `FontGate.NEED_FONTS`.
5. **Start the game from the Peanuts library**, not with an `am start` intent. The library's Play writes the game's env lines: `DM_SHARED_FONT` (the firmware fonts) and `PN_BUILD_ID` (the whole build ID that a companion's native module checks). Without them you get the fallback font, and the companion log says `native module rejected this game build` with a black second screen.
6. **Never install a local build over the release app.** It is signed with another key, so Android refuses the update, and uninstalling the release deletes its saves. Build the test app under another id ([step 6](#6-a-test-app)).
7. **Test on the system driver and on Turnip**, and say in the pull request what you did not test.

## 0. Setup (once)

- The packer's environment (`android/environment.yml`: Python 3.14 with `lz4`, `capstone`, `fonttools`; .NET 10; `spirv-val`). `fonttools` is also needed by `build_ipa_from_nsp.py`.
  ```sh
  export PATH=<env>/bin:$PATH DOTNET_ROOT=<env>/lib/dotnet
  python -m pip install fonttools      # if build_ipa_from_nsp.py says it is missing
  ```
- For the test APK ([step 6](#6-a-test-app)): JDK 17 or 21 (the Gradle wrapper, 8.14, does not run on JDK 27), the Android SDK with `platforms;android-36` and build-tools, and NDK 28.2.13676358. A local `android/app/local.properties` (git-ignored) holds `sdk.dir=<Android SDK>`. On macOS:
  ```sh
  brew install openjdk@21
  brew install --cask android-commandlinetools
  sdkmanager --sdk_root=<Android SDK> --licenses
  sdkmanager --sdk_root=<Android SDK> "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358"
  ```
- Your own `prod.keys` and firmware, and `adb` to the device.

## 1. The `.nup`

```sh
peanuts/.build/nximport/nximport_cli --keys <prod.keys> nup "<work dir>/<Name> [<TITLE ID>][v<version>].nup" <title id in lower case> <base .nsp> [<update .nsp> ...]
peanuts/.build/nximport/nximport_cli --keys <prod.keys> icon <base .nsp> <title id in lower case> "<work dir>/<Name> [<TITLE ID>][v<version>].png"
```

The second line writes the title's icon beside the `.nup`, under the same name with `.png`. A `.nup` holds no icon: the library shows the PNG beside it, and [step 7](#7-test-on-the-device)'s `push-pack.py` copies it to the device with the `.nup`. Without it the game's cover is its first letter. `peanuts_build.py` writes this PNG itself.

`peanuts_build.py <files> --dry-run` builds `nximport_cli` first if it is missing, then stops at the profile check.

## 2. A dump folder and the survey

The survey and the shader split read a dump folder, not a `.nup`:

```sh
python peanuts/scripts/nup-extract.py "<work dir>/<Name>.nup" "<work dir>/dump"
python scripts/build_ipa_from_nsp.py "<work dir>/dump" --survey-only
```

Read `<work dir>/dump/<TITLE ID>_survey/survey.md`: modules and build IDs, SDK, engine, shader containers, `[RED]` lines. A 32-bit game stops here and cannot run. Then compare it with a profile of the same engine or SDK: `grep` the notes in `dm/games/*/README.md` for the engine name and the SDK.

**When the dump does not fit on the disk.** A dump is as large as the `.nup`, on top of it, and a `.nup` with an update can be much larger than the game's files (Diablo III: 17.9 GB of `.nsp` files, a 29.9 GiB `.nup`; the importer prints the total in its first progress lines, so stop it there if it will not fit). The survey reads little of the RomFS: each file's path, its size and its first 32 bytes, and the NRO files whole. `--heads` makes a folder with exactly that, a few MB plus the code modules:

```sh
python peanuts/scripts/nup-extract.py "<work dir>/<Name>.nup" "<work dir>/heads" --heads
python scripts/build_ipa_from_nsp.py "<work dir>/heads" --survey-only
```

It is not a dump. The survey and the provisional profile ([step 3](#3-the-provisional-profile-and-the-shader-split)) may read it; the shader split must read the game itself, and it reads a `.nup` in place: give it `"<Name>.nup:update/<version>/romfs"` and `"<Name>.nup:update/<version>/exefs/main"` (`:base/…` for a game without an update) where the commands below have the dump's folders. The pack step reads the `.nup` as well, so such a game needs no dump at all. In zsh, write `"${N}:update/…"` when the path is in a variable: `"$N:update"` is zsh's upper-case modifier and gives a path that does not exist.

## 3. The provisional profile and the shader split

```sh
python scripts/build_ipa_from_nsp.py "<work dir>/dump" --platform mac
```

This writes `<work dir>/dump/<TITLE ID>_build/profile/<TITLE ID>/game.tsv`. Without Xcode's Metal toolchain it then stops with `the Metal compiler does not run`, which is fine here.

**On Linux, leave `--platform mac` out.** With it the script stops before the survey: `--platform mac runs the game in the Mac host … it needs a Mac with Apple silicon`. Without it the script surveys, writes the same provisional profile, and then stops at the first thing an iPhone build needs and the PC lacks, which is fine too. On the PC this was written on that was `the current Python … is missing modules: fontTools`; with `fonttools` installed the next stop is the check for Xcode's tools (not run here). Either way the profile is there before the stop, with header-only shader tables, and the heap check that would correct its memory keys does not run: on Linux the memory keys stay the survey's guess, so take them from the closest profile of the same engine when its note says the guess failed there.

Then split the shaders into that profile. This is the command `build_ipa_from_nsp.py` runs (`split_shaders`); for a game with updates, `<romfs>` and `<main>` are comma-separated lists, one per version:

```sh
P="<work dir>/dump/<TITLE ID>_build/profile/<TITLE ID>"
DM_GAMES_DIR="<work dir>/dump/<TITLE ID>_build/profile" DM_GAME=<TITLE ID> \
python dm/gfx/tools/shader_split.py "<work dir>/dump/base/romfs" "<work dir>/dump/base/exefs/main" "<work dir>/split/pkg" \
    "$P/shader_map.tsv" "$P/shader_facts.tsv" "$P/shader_pairs.tsv" "$P/pso_pairs.tsv"
```

Its last line counts the shaders (`shaders: 47099 (compute 62, pixel 32861, vertex 14176); …`). Zero means the game keeps its shaders in a form `shader_split.py` does not read yet ([04-graphics.md](../../docs/agents/04-graphics.md)).

### Games the PC split cannot read

Three kinds were met, and each shows within minutes, before any long step. Such a game is not readable by the PC split: it needs a provisional pack that boots, plus a run on the device that saves what the files do not give (`DM_NVN_SHDUMP=<folder>` or `DM_GLSLC_CAPTURE=<folder>`), before its shader tables can be written. Until then a profile for it does not go into the repository (Farming Simulator's draft profile was taken out again for that reason); write down what you found instead.

| Kind | How it shows | Seen on |
|---|---|---|
| The shaders are in an archive format that has no reader | The split ends with `shaders: 0`, or with only the handful in `main` (`sources main 12`), and the survey's RomFS table is a few very large files of one extension with no known header magic. Look at the first bytes of the largest one: `head -c 16 <file> \| xxd` on the `--heads` folder is enough | Diablo III: 23 `.cpk` files of Blizzard's own format (magic `d4 c3 b2 a1`, not CRI's `CPK `). Crash Team Racing Nitro-Fueled was one: 401 `.pak` files of the Alchemy engine (magic `IGA\x1a`), until `shader_split.py` got a reader for them (`iga_shaders`: 1,933 shaders in `shaders_nx.pak`). Farming Simulator before them |
| The game compiles its shaders while it runs | The survey lists a `GLSLC` module (`subsdk0`), `main` imports `glslcCompile`, and the split prints `[RED] main holds GLSL sources and imports glslcCompile, but there are no captured GLSLC outputs in …/glslc_capture` | Crash Team Racing Nitro-Fueled (5 GLSL sources in `main`). The Binding of Isaac: Afterbirth+ is the same with no warning line: its GLSL is in two NRO files and in `.vs` / `.fs` RomFS files, which the survey's rule does not count, so only the zero says so |
| No shader anywhere in the files | `shaders: 0 ()`, no `[RED]` line, and the pack step refuses: `[RED] the profile … has no shader in its shader_map.tsv, and does not say that the game has none` | The Binding of Isaac: Afterbirth+. Before taking a zero for this kind, rule out the two above |

What such a game needs, none of which was done for these three:

1. A provisional pack of the code alone (`pack_android.py … --no-shaders`), and an app built with the provisional profile. The game has to boot far enough with no shader library to hand its shaders to NVN.
2. A run on the device with the capture switch in the game's environment. `DM_NVN_SHDUMP=<folder>`: the host saves the control section and the code of every shader the game asks for that the library does not have (`dm/gfx/vk_android/vka_shader.c`, `<key>.ctl` and `<key>.code`), which is every shader, wherever the game keeps it. `DM_GLSLC_CAPTURE=<folder>`: the host saves each output of the game's own GLSLC (`dm/host/hle_glslc.c`), for a game that compiles at run time.
3. The split again with those folders: it reads `nvn_shdump/` and `glslc_capture/` beside the RomFS (or `DM_NVN_SHDUMP_DIR` / `DM_GLSLC_CAPTURE`; `shader_split.py` `nvn_dumps`), and the map gets rows for them. A profile with `/glslc_capture/` rows is Fire Emblem: Three Houses' (`shader_groups … /glslc_capture/=runtime`): the PC builder refuses such a game unless the folder is given, and Build in the app runs the capture itself ([BUILDER.md](BUILDER.md#games-the-builder-refuses)). A dump holds only the shaders of the scenes that were played, so it is a start, not a full table; [04-graphics.md](../../docs/agents/04-graphics.md) uses it to find the container that was missed.

A stop inside a reader is a fourth case, and the usual one for an engine the tools do read: the message names the file and the reason. Two were met on My Time at Sandrock (Unity 2021.3.26f1) and are fixed. `no bundle has a Shader type tree of the same hash to borrow`: a Unity release whose stripped files need a layout in `dm/gfx/tools/unityfs.py` `KNOWN_TREES`. Write it from that release's type tree (AssetRipper's TypeTreeDumps, `StructsDump/release/<version>.dump`), check it node for node, and check on the game that every Shader object reads to its exact end and that each blob's entries are all code or parameter entries. And a `[RED] <file>: error: … (its shaders are left out)` line: one object the reader could not take costs the whole file, so the count is too low rather than zero. Compare the count with what the engine's other games have.

## 4. The profile in the repository

```sh
mkdir dm/games/<TITLE ID> && cp "$P"/*.tsv dm/games/<TITLE ID>/
```

Then:

- In `game.tsv`, set `name` (the game's name), `ipa`, the `build` row's display version (the `?` after `v<version>`) and `shader_note`. Replace the header comments with what the profile is, and add `# PEANUTS: …` (`dm/games` is Banana Native's folder: [CONTRIBUTING.md](CONTRIBUTING.md#where-code-goes)). Leave the shader keys and the tables as the tools wrote them: their bytes are in the shader library's stamp.
- Write the note, `dm/games/<TITLE ID>/README.md`: status, builds, shaders, memory, found on this game, open. Start it with `<!-- PEANUTS: … -->`. Add its row to the games table in `dm/games/README.md`, with `<!-- PEANUTS … -->` in its last cell.
- List the generated tables (`shader_map.tsv`, `shader_map.tsv.conflicts.tsv`, `shader_facts.tsv`, `shader_pairs.tsv`, `pso_pairs.tsv`, `pso_manifest.tsv`, `bf_want.tsv`) in `peanuts/upstream-unmarked.txt` with a reason, and add a row to `peanuts/UPSTREAM-EDITS.md`.
- Check: `python dm/tools/tests/test_docs.py` (every profile has a note and a row) and `bash peanuts/scripts/check-profiles.sh`.

## 5. Pack

From the `.nup`, reusing the split's package:

```sh
python scripts/pack_android.py "<work dir>/<Name>.nup" --shader-pkg "<work dir>/split/pkg"
```

The first run translates every shader (Kirby: 47,099 shaders in 12.5 minutes on an M-series Mac). A later run with the same inputs reuses the library and takes seconds. Check the end of the log: `every one valid for spirv-val`, no `[RED]` line, and `copying the shader library into the pack: <n> of <n> MB` with `n` above 0.

## 6. A test app

The host reads each game's profile from the APK (`assets/profiles/<TITLE ID>.profile.bin`), so a new game needs an APK built with it. Build the Peanuts app under another id, so that it installs beside the release and leaves its saves alone: `-PnxAppId=<suffix>` makes it `dev.nxnative.android.<suffix>` (lower-case letters and digits), an app of its own with its own data.

```sh
cd android/app
export JAVA_HOME=<JDK 21> PATH=<env>/bin:$JAVA_HOME/bin:$PATH ANDROID_HOME=<Android SDK>
./gradlew apk -PnxApp=peanuts -PnxAppId=peanutstest -PnxLabel="Peanuts test" -PnxGames=<TITLE ID> -PnxPackEngine=fake
adb install -r out/nxnative-android-peanuts.apk          # dev.nxnative.android.peanutstest
```

`-PnxPackEngine=fake` leaves out the on-device packer, which needs its own stage (`peanuts/scripts/ondevice-stage.sh`). The pack comes from step 5 instead. Add the other games you want in the same app to `-PnxGames` (comma-separated).

## 7. Test on the device

```sh
python peanuts/scripts/push-pack.py "<work dir>/<TITLE ID>-work/android-pack" --game "<work dir>/<Name>.nup" --dest "/sdcard/Peanut Roms/<Name>"
```

In the test app:

1. Settings › Library: **Keys** (your `prod.keys`), **Firmware** (your firmware folder or `.zip`), and the **game folder** (`/sdcard/Peanut Roms`).
2. Install the companion, if there is one, from the catalog (eden-duo-companions), and check that its manifest's build IDs cover your build.
3. Start the game from the library. Play about 20 seconds past the title, on the system driver, then on Turnip (Settings › GPU driver).
4. Read the log: `/sdcard/Android/data/dev.nxnative.android.peanutstest/files/logs/<TITLE ID>-<time>.log` and its `.digest.txt` (or `python scripts/log_digest.py <log>`). Check the `[vk] driver:` line (Turnip is driver ID 18), `PN_BUILD_ID`, `shared font … firmware/fonts`, the `[nvn] frame` lines, `CRASH` and `[RED]`. The companion logs to logcat: `adb logcat -d | grep -E "mod_module|module_ready"` (want `module_ready=1 build_match=1`).
5. Screenshots: `adb exec-out screencap -p > top.png`, and for the second screen `adb exec-out screencap -d <physical display id> -p > bottom.png` (the ids are in `adb shell dumpsys display | grep uniqueId`, as `local:<id>`).
6. Force-stop the test app afterwards (`adb shell am force-stop dev.nxnative.android.peanutstest`). Never delete or overwrite a save folder.

## 8. The pull request

- Revert the local `build.gradle` line. A pull request brings source only: no pack, APK, `.nup`, log with game data, or key.
- Run the checks ([CONTRIBUTING.md](CONTRIBUTING.md#testing)): `dm/tools/tests/test_docs.py`, `test_android_games.py`, `test_android_gamefonts.py`, `test_android_gamelist.py`, `peanuts/tests/*.py`, `dm/peanuts/profile/gen_runtime_profile.py check-all`, `peanuts/scripts/check-profiles.sh`, then `peanuts/scripts/check-publish.sh origin/main`.
- The Supported games tables of `README.md` and `peanuts/docs/README.github.md` list the games of `peanuts/release-titles.txt`, the release's games, which a game joins once it plays on a device. They and the issue forms follow it on main by themselves (`.github/workflows/catalog.yml`), so never edit the tables by hand. If the profile's name is in capitals, give the game's row in `peanuts/games.tsv` the name players know.
- The description says what ran on which device and driver, the build ID from the log, the companion's state, and what was not tested (known issues).
