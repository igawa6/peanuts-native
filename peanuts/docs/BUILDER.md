# Building a game for Peanuts Native in one command

`peanuts_build.py` takes a game from its files to the phone:

1. **Import**: `.nsp`, `.xci`, `.nsz` or `.xcz` files become a `.nup` (`nximport_cli`, built from `dm/peanuts/import` with cmake the first time).
2. **Pack**: `scripts/pack_android.py` makes the pack from the `.nup` (or from a dump folder, which skips step 1).
3. **Push** (`--push`): `peanuts/scripts/push-pack.py` copies the pack and the game to the phone over adb.

The importer needs your own `prod.keys`. Nothing in the repository supplies keys.

A game with no profile in `dm/games` (the builder stops with `no profile for <id>`) needs one first: [NEW-GAME.md](NEW-GAME.md) walks it from the dump to a pull request.

## Setup

| | Packer environment | Importer (for `.nsp` / `.xci`) |
|---|---|---|
| macOS | `scripts/bananative.command setup` | `brew install cmake`, Xcode command line tools |
| Linux | Python 3.14 with the packages of `android/environment.yml`: see [Linux](#linux) below | cmake, gcc |
| Windows | `scripts\bananative.bat setup` | cmake, MinGW-w64 GCC and zlib with its static library (MSYS2 UCRT64: `mingw-w64-ucrt-x86_64-gcc`, `mingw-w64-ucrt-x86_64-zlib`) on PATH |

On macOS and Linux the importer also needs zlib's headers (part of the Xcode command line tools; `zlib1g-dev` or `zlib-devel` on Linux).

`peanuts_build.py` itself uses only the standard library and Python 3.9 syntax, but it runs `scripts/pack_android.py` with the same interpreter, and the packer needs its environment (Python 3.14, `android/environment.yml`). The launchers take care of that; run the script directly only with that environment's Python.

Set `NX_KEYS` to your `prod.keys`, or pass `--keys`. The default is `~/.switch/prod.keys`.

### Desktop shader translator

Peanuts Native 0.1.5 builds its desktop shader translator from source the first time a pack needs a new shader library. Install the .NET **SDK 10.0.114** for that first build. If the packer environment contains only the runtime, set `PEANUTS_DOTNET_SDK` to the full path of the SDK’s `dotnet` executable. The launcher preserves an explicit `XLATE` selection. Later builds reuse the verified translator under `peanuts/.build/pc-translator/`. Cached shader libraries and code-only packs can be used without preparing a translator.

For an offline build set `PEANUTS_NUGET_SOURCE` to a local NuGet feed containing the packages required by `dm/third_party/ryujinx/Directory.Packages.props` and their dependencies. A populated NuGet global-packages folder works as a local feed. To prepare explicitly and print the value for `XLATE`, run:

```sh
python3 peanuts/scripts/prepare_pc_translator.py --source "$HOME/.nuget/packages"
```

The helper verifies the three portable DLL fingerprints and the translator’s embedded source digest against `dm/gfx/tools/dmsh_chain.lock` before publishing its cache directory. SDK 10.0.114 reproduced all three locked DLLs byte for byte on x86-64 Linux. Other platforms check the same fingerprints. Both the Peanuts desktop launchers and direct calls to `scripts/pack_android.py` select the result automatically. On Windows use the packer environment’s `python` and a Windows path for the feed.

### Linux

Two ways to get the packer environment. Either way the importer needs cmake, gcc and zlib's headers, and `--push` needs `adb`.

1. The distribution's own Python, when it is 3.14 (the way that was tested). Install Python 3.14, Capstone 5 and lz4 for it, SPIRV-Tools (`spirv-val` on PATH) and the .NET 10 runtime (`dotnet` on PATH) from your distribution. `zbic` is not packaged, so it goes into a venv that sees the system packages:

   ```sh
   python3.14 -m venv --system-site-packages ~/peanuts-env
   ~/peanuts-env/bin/pip install zbic==1.0.1.post1
   PEANUTS_PYTHON=~/peanuts-env/bin/python peanuts/scripts/peanuts-build.sh "Game.nsp" "Game-update.nsp" --keys ~/prod.keys --push
   ```

   If pip finds no ready-made `zbic` for your system it builds it, which needs gcc and Python's headers.

2. A micromamba environment made from the file (`micromamba create -p <folder>/env -f android/environment.yml`), with `PEANUTS_PYTHON` set to its python. This way was not run on Linux.

Without `PEANUTS_PYTHON` the launcher uses `python3.14`, or `python3` when it is 3.14 or later, from PATH.

## Examples

```sh
# macOS
peanuts/scripts/peanuts-build.command "Game folder" --keys ~/prod.keys --push
# Linux
PEANUTS_PYTHON=~/peanuts-env/bin/python peanuts/scripts/peanuts-build.sh "Game.nsp" "Game-update.nsp" "Game-dlc.nsp" --push
```

```bat
rem Windows
peanuts\scripts\peanuts-build.bat "D:\Games\Game folder" --update 131072 --push
```

Other inputs: a `.nup` (`... "Game [01006BB00C6F0000][v131072].nup" --push --dest "/sdcard/Peanut Roms/Game"`), a dump folder (`... "Dump folder" --dry-run`), and anything after `--` goes to `pack_android.py` (`... "Game.nup" -- -j 8`).

### Compressed files (.nsz, .xcz)

Files compressed with nsz go in like `.nsp` and `.xci`, mixed with them as you like (an `.nsz` base with an `.nsp` update, say). The `.nup` is the same, byte for byte, as the one from the uncompressed files.

- Every compressed NCA is rebuilt and checked against the SHA-256 the game's own metadata records before anything is written. A damaged or cut-off file stops the import with its name; nothing is changed.
- A **block** file (nsz `-B`, the `.xcz` default) is read where it is: no extra space.
- A **solid** file (the `.nsz` default) cannot be read out of order, so the importer unpacks it once into a temporary file beside the `.nup`, in the hidden folder `.<name>.nup.tmp.unpack`, and deletes it when the import ends. While it imports the folder needs about the game's size twice (the `.nup` and the unpacked copy); the builder says how much before it starts. `NXIMPORT_TMPDIR` puts the temporary file on another drive.
- A solid file whose zstd window is larger than 128 MiB is refused (nsz's levels ask for 8 MiB at the default and 128 MiB at most; another tool might ask for more): compress it again with nsz.
- When a folder holds both the `.nsp` and the `.nsz` of a game, the `.nsp` is used (nothing to unpack).
- To check a compressed file without importing it: `nximport_cli --keys prod.keys check Game.nsz` (`peanuts/.build/nximport/nximport_cli` once the builder has built it).
- Ctrl-C during the import stops it cleanly: no `.nup`, no temporary file.

## Flags

- `--keys PATH`, `--importer PATH` (or `NX_IMPORTER`), `--nup PATH`: the keys, a prebuilt importer, the `.nup` to write or reuse. If `--nup` is an existing folder, the `.nup` goes there under its default name (title, title ID, version), so a caller never has to invent the name; a file path works as before.
- `--update V`: the update to use (default the highest). For a dump it becomes `pack_android.py --version`.
- `--force-nup`: make the `.nup` again. Without it a `.nup` newer than its inputs is reused. The check is by file time only: after adding a DLC file that is older than the `.nup`, or when `--nup` names a file made with another update, pass `--force-nup`.
- A pack that stops with `the dump's shaders lack N of the M … all of them come from files the game files do not have … add-on content (DLC) that was not part of the import`: the dump is incomplete, and one possible reason is that the game's DLC `.nsp` was not on the command line. If you have DLC for the game, add it and pass `--force-nup`. This is said only when the game's shader tables are of one version, the dump is that version, it has no add-on content at all, and every missing shader's file is absent. No game is known to need it yet, and it has not been tried on a real one. The builder cannot warn before the import: the importer's `probe` lists the titles in the files given, not the add-on content a game has.
- A build that prints `shaders: N of M shaders in this app's table belong to other versions of the game (vB): not needed for vA` is fine: the profile's shader tables are the union of several game versions (`shader_versions` in its `game.tsv`), and the dump is one of them. Mario Kart 8 Deluxe 4.0.0 prints it for the 135 shaders of 4.0.1.
- A pack that stops with `the shader split read no shader` (or `has no shader in its shader_map.tsv`): the game's shaders were not found in its files, so the pack would have an empty shader library and the game could not draw. Either the dump is incomplete or the game keeps its shaders in a form the tools do not read yet (Farming Simulator: Nintendo Switch Edition's provisional profile is the known case). Older builders made the pack anyway.
- Paths may hold spaces, commas, brackets and letters outside ASCII (a game such as `Yoru, Tomosu [0100C2901153C000].nup`). A path cannot hold the ASCII unit separator (0x1F), which the shader split uses between the paths of a list.
- `--any-version`: pack an update the game's profile (`dm/games/<titleID>/game.tsv`) has no build row for. Without it that is an error that lists the supported versions.
- `--push`, `--dest DIR`, `--serial S`. The default folder on the phone is `/sdcard/Peanut Roms`, in `peanuts_build.py` and in `push-pack.py` alike: a `.nup` goes to `/sdcard/Peanut Roms/<the .nup's name without its [tags]>/`, a dump folder to `/sdcard/Peanut Roms/<its name>/`. The Peanuts library must scan that folder (Settings, Library) to show the game; pass `--dest` for another one. `--dest` must be an absolute path without `.` or `..` parts. On the phone the push replaces only the folders it copies (`<TITLE ID>-work/android-pack`, and for a dump `base/exefs`, `updates/<version>`, `dlc`), each through a `.tmp` copy.
- `--dry-run`: print the commands. Probing and the importer build still run; nothing is written.

## Games the builder refuses

A game whose profile (`dm/games/<titleID>/shader_map.tsv`) has `/glslc_capture/` rows (shaders the game compiles with GLSLC at run time, for example Fire Emblem: Three Houses) or `/ue_shaders/` rows (Unreal shader archives, for example TRIANGLE STRATEGY) needs a step that boots the game or runs the phone's decoder. The builder stops before the import with a `[RED]` message and a non-zero exit code; use Build in the Peanuts app on the device, which runs that step there. If you already have the files, set `DM_GLSLC_CAPTURE` or `DM_UE_SHADERS` to the folder that holds them and the check is skipped.

## Oodle and Unreal games a Linux PC builds itself

On Linux the builder does that step itself for the games below. Each game's shader keys were checked on a PC against its profile.

| Game | What the PC does | Checked |
|---|---|---|
| Pokémon Legends: Arceus (`01001F5010DFA000`) | decodes its Oodle Kraken archives with `unoodle` | 12,851 of 12,851 shader keys |
| Captain Tsubasa 2: World Fighters (`01009720213B0000`) | decodes its IoStore shader libraries with `unoodle` | 21,903 of 21,903 shader keys on version 1.0.6 |
| Demon Slayer -Kimetsu no Yaiba- The Hinokami Chronicles 2 (`0100AD80208A8000`) | reads inline IoStore shaders with `unoodle` | 128,449 shader keys on 1.80 |
| FINAL FANTASY RESONANCE (`0100606026F12000`) | decodes its Unreal shader libraries (Oodle Mermaid) with `unoodle` | 73,496 of 73,496 keys; the shader package is the phone's byte for byte |
| OCTOPATH TRAVELER 0 (`01005270232F2000`) | the same | its 10,596 streams and 10,324 keys; a build from its game files was not run on a PC yet |
| DRAGON QUEST III HD-2D Remake (`01003E601E324000`) | reads its Unreal shader archives (LZ4, no Oodle) | 47,298 of 47,298 keys |

- **`unoodle`** is a small worker built from the open-source ooz decoder in [`dm/third_party/ooz`](../../dm/third_party/ooz/README.md). It is GPL-3.0-or-later and a program of its own: the builder's tools start it and talk to it over a pipe (`DM_OODLE_OOZ`). It is for the PC builder only: it is not in the APK, not in the on-device build and not in any release file. Its licence as that folder's README records it: the decoder's source file carries a GPL-3.0-or-later notice, but the ooz project has no licence file, and its files without a notice that hold decoder code are left out. The builder compiles it into `peanuts/.build/ooz` the first time such a game is built. It needs x86-64, g++ or clang++, and the `patch` program.
- The builder compiles the `_nxaes` module for Unreal games so it can read encrypted pak indexes. It needs gcc or clang and Python's headers (`python3-dev` or `python3-devel`). The split decodes the archives into `<TITLE ID>-work/android-shaders/ue_shaders` and reuses them while the game's paks are the same.
- **ooz is not the game's own decoder**, and an Oodle stream has no checksum. So the pack is made only if the shader keys that come out of the split are exactly the ones in the game's profile; each key is a hash of its shader's bytes. With one key missing or one key more the build stops with a `[RED]` message that names the ooz decoder, and nothing it decoded is used. The usual cause is game files of another version than the profile's; build on the device then.
- Where the worker cannot be built (arm64, no compiler), the Unreal games are refused as above and Arceus stops before the import with the reason. On macOS and Windows nothing changes: these games are refused as before. Other games with Oodle-compressed files (for example Pokémon Legends: Z-A, Bayonetta 3) are not on the list, because their decode was not checked on a PC; they still stop at the shader split.
- `DM_OODLE_OOZ` set by you (a worker you built with `dm/third_party/ooz/build.sh`) is used for any game, with the same key check.
- Double Dragon Revive (`01006AE02236C000`): set `DM_OODLE_OOZ` to that worker to decode its IoStore libraries, or supply the decoded `DM_UE_SHADERS` folder for the version being packed. Its [two-build split](../../dm/games/01006AE02236C000/README.md#shaders) contains 18,510 keys, including 13 read directly from NROs.

## .NET

The shader translator needs .NET. The builder points the tools it starts at the Python environment's own .NET (`<env>/lib/dotnet`, on Windows `<env>/dotnet` or `<env>/Library/dotnet`) when `DOTNET_ROOT` is not set, so the environment need not be activated. A `DOTNET_ROOT` you set is kept. On Linux without such an environment the translator is started with the `dotnet` on PATH (the distribution's .NET 10 runtime).

## Where things go

- The `.nup` goes beside the first input file, named `<Name> [<TITLE ID>][v<version>].nup`. Characters Windows does not allow in the name become `-`.
- The title's icon goes beside the `.nup` as `<the .nup's name without .nup>.png` (`nximport_cli icon`, from the base file, else the update). It is written for `.nsp`, `.xci`, `.nsz` and `.xcz` inputs, also when the `.nup` is reused and only the PNG is missing (`--force-nup` writes it again). A failure is a warning, not an error. `--push` copies it beside the `.nup` on the phone, where the Peanuts library takes it as the game's icon when the game has none yet: it must be a PNG of at most 4 MiB, and once the library has an icon for a title it keeps it. Since issue #158 the `.nup` also carries the icon itself (its base package's meta block, which `nximport` writes); the library reads the PNG beside it first, then the icon inside.
- The pack goes to `<folder of the .nup>/<TITLE ID>-work/android-pack`.
- The importer is built into `peanuts/.build/nximport`, the Oodle worker into `peanuts/.build/ooz` and `_nxaes` into `peanuts/.build/pymod` (all git-ignored).

## Status

| Platform | State |
|---|---|
| macOS | Tested end to end: Link's Awakening 1.1.0 from its `.nsp` files to an AYN Thor in 4 min 17 s (import 14 s, pack 3 min 37 s, push 26 s); the pack is the phone's byte for byte |
| Linux | Tested end to end: Breath of the Wild 1.9.0 with both DLC from its `.nsp` files to an AYN Thor. Import (28.9 GiB, 87,848 files, `verify` ok) 31 min, rewrite and shader translation (19,592 shaders, all valid) and pack 45 min, push to the Thor's microSD 78 min, and the game boots from the pushed pack. Run on the distribution's Python 3.14 with a venv for `zbic`, gcc, cmake and .NET 10. The pack was not compared byte for byte with a phone-built one |
| Windows | Written, not yet tested |

A tester on Windows should run and report:

1. The importer build (`peanuts_build.py <nsp files> --dry-run`): does cmake find the compiler, and does `nximport_cli` build?
2. The probe output for the base and update files.
3. A real `.nup` run, then `nximport_cli verify` ending in `ok,`.
4. A real pack, and `sha256sum` (or `certutil -hashfile ... SHA256`) of `shaders/v-*/dmsh_vk.lib` in the pack, compared with the macOS build's hash.
5. `--push` to a phone, and whether the app starts the game from the pushed pack.
