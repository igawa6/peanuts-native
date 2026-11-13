# Building a game for Peanuts Native in one command

`peanuts_build.py` takes a game from its files to the phone:

1. **Import**: `.nsp` / `.xci` files become a `.nup` (`nximport_cli`, built from `dm/peanuts/import` with cmake the first time).
2. **Pack**: `scripts/pack_android.py` makes the pack from the `.nup` (or from a dump folder, which skips step 1).
3. **Push** (`--push`): `peanuts/scripts/push-pack.py` copies the pack and the game to the phone over adb.

The importer needs your own `prod.keys`. Nothing in the repository supplies keys.

## Setup

| | Packer environment | Importer (for `.nsp` / `.xci`) |
|---|---|---|
| macOS | `scripts/bananative.command setup` | `brew install cmake`, Xcode command line tools |
| Linux | Python 3.14 with the packages of `android/environment.yml` (e.g. a micromamba env from it); set `PEANUTS_PYTHON` to its python | cmake, gcc |
| Windows | `scripts\bananative.bat setup` | cmake, MinGW-w64 GCC (MSYS2 UCRT64 or WinLibs) on PATH |

Set `NX_KEYS` to your `prod.keys`, or pass `--keys`. The default is `~/.switch/prod.keys`.

## Examples

```sh
# macOS
peanuts/scripts/peanuts-build.command "Game folder" --keys ~/prod.keys --push
# Linux
PEANUTS_PYTHON=~/env/bin/python peanuts/scripts/peanuts-build.sh "Game.nsp" "Game-update.nsp"
```

```bat
rem Windows
peanuts\scripts\peanuts-build.bat "D:\Games\Game folder" --update 131072 --push
```

Other inputs: a `.nup` (`... "Game [01006BB00C6F0000][v131072].nup" --push --dest "/sdcard/Peanut Roms/Game"`), a dump folder (`... "Dump folder" --dry-run`), and anything after `--` goes to `pack_android.py` (`... "Game.nup" -- -j 8`).

## Flags

- `--keys PATH`, `--importer PATH` (or `NX_IMPORTER`), `--nup PATH`: the keys, a prebuilt importer, the `.nup` to write or reuse.
- `--update V`: the update to use (default the highest). For a dump it becomes `pack_android.py --version`.
- `--force-nup`: make the `.nup` again. Without it a `.nup` newer than its inputs is reused.
- `--any-version`: pack an update the game's profile (`dm/games/<titleID>/game.tsv`) has no build row for. Without it that is an error that lists the supported versions.
- `--push`, `--dest DIR` (default `/sdcard/Peanut Roms/<.nup name>`), `--serial S`.
- `--dry-run`: print the commands. Probing and the importer build still run; nothing is written.

## Where things go

- The `.nup` goes beside the first input file, named `<Name> [<TITLE ID>][v<version>].nup`. Characters Windows does not allow in the name become `-`.
- The pack goes to `<folder of the .nup>/<TITLE ID>-work/android-pack`.
- The importer is built into `peanuts/.build/nximport` (git-ignored).

## Status

| Platform | State |
|---|---|
| macOS | Tested end to end: Link's Awakening 1.1.0 from its `.nsp` files to an AYN Thor in 4 min 17 s (import 14 s, pack 3 min 37 s, push 26 s); the pack is the phone's byte for byte |
| Linux | Written, not yet tested |
| Windows | Written, not yet tested |

A tester on Linux or Windows should run and report:

1. The importer build (`peanuts_build.py <nsp files> --dry-run`): does cmake find the compiler, and does `nximport_cli` build?
2. The probe output for the base and update files.
3. A real `.nup` run, then `nximport_cli verify` ending in `ok,`.
4. A real pack, and `sha256sum` (or `certutil -hashfile ... SHA256`) of `shaders/v-*/dmsh_vk.lib` in the pack, compared with the macOS build's hash.
5. `--push` to a phone, and whether the app starts the game from the pushed pack.
