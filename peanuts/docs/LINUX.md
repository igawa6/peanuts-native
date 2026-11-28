# Peanuts Native on aarch64 Linux (AppImage)

Experimental, community / best-effort. An aarch64 Linux build for ARM handhelds that run a desktop Linux with a Vulkan driver (developed on an AYN Odin 2 Pro,
Snapdragon 8 Gen 2, running SteamOS-style Ubuntu with Mesa's Turnip). It is Banana Native's Linux host (Banana Native 0.3.43's `linux/`) with Peanuts' host
code in it, and Banana Native's AppImage launcher under Peanuts' names. One AppImage runs every Switch 1 game that has a profile in
`dm/games`, through one host (the generic host: the game's profile is loaded when it starts).

## What it does

- A game window: the games in `~/Games` (`.nsp`, `.xci`, `.nup`, also on removable media), **Prepare** (once per game: the game is imported into a `.nup`, then its
  pack is made on the device with the same packer the Android app uses), **Play**, **Options**, **Add to Steam** (for Game Mode).
- `.nsp` / `.xci` need **your own** `prod.keys`: choose the file in the window (**Choose prod.keys…**), or set `NX_KEYS`, or put it at `~/.switch/prod.keys`.
  Nothing in the AppImage or this repository searches for, downloads or ships keys; the window asks you to choose your own file.
- Its own folders, so it sits beside a Banana Native AppImage without sharing anything (the shader chains differ, so packs do not carry over):
  `~/Games/PeanutsNative/` (the `.nup` files and `packs/`), `~/.local/share/peanutsnative/` (saves, logs, the pack tools), `~/.config/peanutsnative/` (settings).
- Only Switch 1 titles are listed.

Not on Linux: the second-screen companion (one screen; the host side stays idle), shaders built while playing, code-mod builds.

## Build

On an x86_64 or arm64 Linux machine with Docker:

```sh
peanuts/linux/build-appimage.sh            # → linux/build/appimage/PeanutsNative-<version>-aarch64.AppImage
```

It cross-builds the generic host (`linux/build.sh generic`) and the importer (`dm/peanuts/import`) in the image of `linux/Dockerfile`, puts every Switch 1
profile in (`peanuts/linux/pn_appdir.sh`) and stages the launcher from `linux/appimage/` under Peanuts' names. Prepare on the device downloads the
source of the AppImage's commit from `NX_SOURCE_URL` (default: this repository on GitHub), so build AppImages you give to others from a pushed commit.
A host with one title compiled in: `peanuts/linux/build-appimage.sh <title ID> ...`.

## On the device

Needs `spirv-val` (SPIRV-Tools), `curl`, Python 3.12+ (3.14 builds the shader library without backports), and for the window PyQt6 (else zenity).
`.nsp` / `.xci` import and the keys choice are in the PyQt6 window only; the zenity picker prepares `.nup` files. A system without them: Debian/Ubuntu
`sudo apt install spirv-tools python3-pyqt6`; Fedora and Fedora-based images `sudo dnf install spirv-tools python3-pyqt6` (an image-based one, without a
package install: `python3 -m ensurepip --user && python3 -m pip install --user PyQt6`, and `spirv-val` from the `spirv-tools` package matching the system's
`spirv-tools-libs`, in `~/.local/bin`). The system's Vulkan loader is opened as `libvulkan.so.1`, so no Vulkan -dev package is needed.
Run the AppImage, choose your keys, Prepare a game (the first Prepare also downloads the pack tools, about 10 minutes; a game's shaders take 10-40 minutes),
then Play or Add to Steam. Game Mode is the tested way to play. The Odin-specific notes of Banana Native's `linux/README.md` apply (TDP, no swap, the memory guard).

## Tested

See the pull request that added this page for the exact builds, device and results.
