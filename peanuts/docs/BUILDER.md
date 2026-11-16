# Building a game for Peanuts Native in one command

`peanuts_build.py` takes a game from its files to the phone:

1. **Import**: `.nsp` / `.xci` files become a `.nup` (`nximport_cli`, built from `dm/peanuts/import` with cmake the first time).
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

## Flags

- `--keys PATH`, `--importer PATH` (or `NX_IMPORTER`), `--nup PATH`: the keys, a prebuilt importer, the `.nup` to write or reuse. If `--nup` is an existing folder, the `.nup` goes there under its default name (title, title ID, version), so a caller never has to invent the name; a file path works as before.
- `--update V`: the update to use (default the highest). For a dump it becomes `pack_android.py --version`.
- `--force-nup`: make the `.nup` again. Without it a `.nup` newer than its inputs is reused. The check is by file time only: after adding a DLC file that is older than the `.nup`, or when `--nup` names a file made with another update, pass `--force-nup`.
- `--any-version`: pack an update the game's profile (`dm/games/<titleID>/game.tsv`) has no build row for. Without it that is an error that lists the supported versions.
- `--push`, `--dest DIR`, `--serial S`. The default folder on the phone is `/sdcard/Peanut Roms`, in `peanuts_build.py` and in `push-pack.py` alike: a `.nup` goes to `/sdcard/Peanut Roms/<the .nup's name without its [tags]>/`, a dump folder to `/sdcard/Peanut Roms/<its name>/`. The Peanuts library must scan that folder (Settings, Library) to show the game; pass `--dest` for another one. `--dest` must be an absolute path without `.` or `..` parts. On the phone the push replaces only the folders it copies (`<TITLE ID>-work/android-pack`, and for a dump `base/exefs`, `updates/<version>`, `dlc`), each through a `.tmp` copy.
- `--dry-run`: print the commands. Probing and the importer build still run; nothing is written.

## Games the builder refuses

A game whose profile (`dm/games/<titleID>/shader_map.tsv`) has `/glslc_capture/` rows (shaders the game compiles with GLSLC at run time, for example Fire Emblem: Three Houses) or `/ue_shaders/` rows (Unreal shader archives, for example Octopath Traveler 0) needs a step that boots the game or runs the phone's decoder. The builder stops before the import with a `[RED]` message and a non-zero exit code; use Build in the Peanuts app on the device, which runs that step there. If you already have the files, set `DM_GLSLC_CAPTURE` or `DM_UE_SHADERS` to the folder that holds them and the check is skipped.

## .NET

The shader translator needs .NET. The builder points the tools it starts at the Python environment's own .NET (`<env>/lib/dotnet`, on Windows `<env>/dotnet` or `<env>/Library/dotnet`) when `DOTNET_ROOT` is not set, so the environment need not be activated. A `DOTNET_ROOT` you set is kept. On Linux without such an environment the translator is started with the `dotnet` on PATH (the distribution's .NET 10 runtime).

## Where things go

- The `.nup` goes beside the first input file, named `<Name> [<TITLE ID>][v<version>].nup`. Characters Windows does not allow in the name become `-`.
- The title's icon goes beside the `.nup` as `<the .nup's name without .nup>.png` (`nximport_cli icon`, from the base file, else the update). It is written for `.nsp` / `.xci` inputs, also when the `.nup` is reused and only the PNG is missing (`--force-nup` writes it again). A failure is a warning, not an error. `--push` copies it beside the `.nup` on the phone, where the Peanuts library takes it as the game's icon (a `.nup` holds none) when the game has none yet: it must be a PNG of at most 4 MiB, and once the library has an icon for a title it keeps it.
- The pack goes to `<folder of the .nup>/<TITLE ID>-work/android-pack`.
- The importer is built into `peanuts/.build/nximport` (git-ignored).

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
