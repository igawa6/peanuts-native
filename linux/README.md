# Banana Native on aarch64 Linux (experimental, community / best effort)

The Android host (`dm/host`, `dm/gfx/vk_android`) built as a native aarch64 glibc executable, for Snapdragon handhelds
running a desktop Linux (SteamOS-like images). It plays the same packs as Android (`target android`): a pack made by the
Android app, or by `scripts/pack_android.py`, runs unchanged. Linux has no maintainer of its own: Linux-only problems may
stay open for a while.

## Tested

| | |
|---|---|
| Version | 0.3.40 (Mario Kart World race, Mario Tennis Fever, every game's selftest); the other results below are from 0.3.26 |
| Device | AYN Odin 2 Pro (SM8550, Adreno 740, 12 GB RAM, no swap) |
| System | SteamOS-like Ubuntu 26.04 image, kernel 7.2.10, glibc 2.43, system SDL3, Plasma (Wayland) and Steam Game Mode (Gamescope, X11) |
| Driver | Mesa 26.2.7 Turnip (`VK_EXT_map_memory_placed` yes; no mesh shaders, no sparse residency) |
| Games | Mario Kart World 1.8.0 (picture, sound, controls, saves, full races; 30-35 fps average at 720p handheld, start grid 24-29, mid-race 38-53; on 0.3.40 a two-lap race had a median of 35.7 fps); Donkey Kong Bananza, update 458752, with its add-on (boots, intro and menus); Mario Tennis Fever (menus and intro at 60 fps, also on 0.3.40); Star Fox (a few minutes of play, median 58 fps at the game's own 3D resolution) |
| Does not fit yet | The Legend of Zelda: Ocarina of Time 2.0: menus and intros run, but its 3D world needs more memory than the Odin 2 has (no swap): GPU memory climbs past 3-4 GB and the kernel ends the game within minutes, in Game Mode and in Desktop Mode |

## What works and what is missing

Works: the Vulkan replay on Turnip, sound (SDL3 audio, PipeWire), gamepads, touch and rumble (SDL3), saves, `.nup` game
files and their add-ons, the replay's selftest (`--vk-selftest`).

Missing: H.264 / AAC (no AMediaCodec: `linux/shim/shim_media.c` is a stub; Mario Kart World's intros and cutscenes play
without it), motion sensors (SDL3 can read a pad's own gyro, but the Odin 2 Pro's built-in pad exposes none on this image), host
unit tests run on Linux, and the run-time shader layer's builds ("shaders are built while you play", `android/SHADERS.md`): the host
writes the layer's requests (`DM_VK_LAYER`), but on Android the app runs `dm/gfx/tools/shader_layer.py` for them while the game
plays, and nothing on Linux does that yet. So a game prepared on Linux gets the whole shader set, as before 0.3.40 (a pack made
without shaders draws nothing for the shaders it lacks).

## How it works

- The host's sources are compiled as they are, with `__ANDROID__` and `NX_LINUX=1` defined (`linux/host/CMakeLists.txt`),
  against stand-ins for the NDK headers in `linux/shim/include`. Their implementations are in `linux/shim/*.c`:
  - `shim_aaudio.c`: AAudio on SDL3 audio.
  - `shim_window.c`: `ANativeWindow` as a small record of the SDL window.
  - `shim_sys.c`: `__system_property_get`, `pthread_gettid_np`.
  - `shim_media.c`, `shim_sensor.c`: stubs.
- `linux/host/front_sdl.c` stands in for the Android launcher: SDL3 window, gamepads, touch, rumble, forwarded to the
  functions of `dm/host/android/input_android.h`. On X11 the Vulkan surface is an xcb one on its own connection (sharing
  SDL's Xlib connection with the presenting thread froze or crashed under Gamescope).
- `linux/host/tls_slot.c`: rewritten game code reads its thread slots through `[tpidr_el0 + 0x10]` (Bionic's
  `TLS_SLOT_APP`). With glibc that word is the first thread-local variable of the executable, so the host is an
  executable (not a shared library), this file is linked first, and `main_linux.c` checks the address at start. That is
  why Android packs run unchanged.
- `-Dmain=nx_host_main`: `dm/host/main.c`'s `main` is called by `nxhost_main`; the real `main` is in `main_linux.c`.
- Left out: `dm/host/android/jni_android.c`, libadrenotools (custom GPU drivers).
- Changes to shared files, all inside `#ifdef NX_LINUX` (an Android build compiles them out):
  `dm/gfx/vk_android/vka_dev.c` (instance extensions from the front end; display timing off unless asked, below) and
  `dm/gfx/vk_android/vka_present.c` (the surface from the front end).

## Build

    linux/build.sh <base game title ID>        # e.g. 0400C3F00006E000 → linux/build/<id>/nxhost_<id>

Cross build with Docker on an x86_64 or arm64 machine. The image (`linux/Dockerfile`: Ubuntu 24.04, clang, lld, the arm64
libraries) is built on first use. SDL3: `linux/sysroot/{include/SDL3,lib/libSDL3.so}` when there is one (copied from the
target system), else SDL3 built from source by `linux/build-sdl3.sh` (git tag `release-3.4.2`). The first build takes
some minutes. One binary per game: the game profile is compiled in, as on Android. Run time needs glibc ≥ 2.39, libSDL3,
libxcb, libz, and a Vulkan driver with `VK_EXT_map_memory_placed` (Turnip).

## Run

    nxhost_<id> --vk-selftest [frames]         # the replay's own selftest; NX_HEADLESS=1 for no window
    cd <pack folder> && DM_ROMFS=<game.nup or RomFS folder> DM_SAVE=… DM_TEMP=… DM_ROOT=… DM_AOC_DIR=… \
        HOME=… TMPDIR=… nxhost_<id> <pack folder>

Game settings are the host's `DM_*` variables (`docs/env_switches.tsv`); what the Android launcher sets is in
`GameActivity.java` (720p: `DM_GRAPHICS_SCALE=2 DM_OPMODE=handheld`; RAM saver:
`DM_MEM_FILEBACK=1 DM_MEM_FILEBACK_KINDS=shader,texture`). Linux-only switches: `NX_WINDOW=<w>x<h>` (a window instead of
full screen), `NX_HEADLESS=1` (no window), `NX_NO_WINDOW_OK=1` (go on when no window can be made; by default the host
ends with a `[RED]` line naming the display variables), `NX_VK_DISPLAY_TIMING=1` (below). Quit: close the window.

## Device notes (docs/reference)

- Gamescope's Vulkan layer and `VK_GOOGLE_display_timing`: [rendering.md](../docs/reference/rendering.md), "Present times
  through Gamescope's layer fault the GPU (Linux)". The host gives no present times unless `NX_VK_DISPLAY_TIMING=1`.
- No swap: [memory.md](../docs/reference/memory.md), "The measured image cache limit with no swap (Linux)". Run with
  `DM_VK_IMG_LIMIT_MB=2300` on a 12 GB device with no swap. A swap file is not a way out on Turnip: it corrupted the
  picture.
- With `DM_VK_PRUNE_MS=3` a texture cache prune does not hold one frame for a second
  ([memory.md](../docs/reference/memory.md), "A texture cache prune that holds one frame for a second").

## Preparing a pack on Linux

`linux/tools/linux-pack.sh <game.nup or dump folder>` runs the same packer as the Android app and the Mac
(`scripts/pack_android.py`): the code rewrite and the Vulkan shader library. `linux/tools/linux-pack.sh setup` (also run
by the first pack) puts what it needs in `~/.local/share/banananative/packer` with no root: a Python environment with
Capstone 5, lz4 and zbic (pip from its bootstrap script where the system Python has no `ensurepip`), the .NET 10 runtime
(Microsoft's `dotnet-install.sh`) for the translator's portable dlls (`dm/gfx/tools/dmsh_xlate_bin`; the apphost there
is the Mac's, so a wrapper beside the dlls, `dmsh_xlate-linux`, is what `XLATE` names), and it needs `spirv-val` on
PATH (SPIRV-Tools). The pack goes to `<title ID>-work/android-pack` beside the game file, where the launcher's picker
finds it. Checked on the Odin 2 with Mario Kart World 1.8.0 (`.nup`): the code modules equal the phone's pack byte
for byte except `sdk`, which carries the two `AddressRegionManager` cuts the 0.3.24 rewriter adds; the pack boots.

## AppImage

The releases carry no Linux build: build the AppImage yourself, as below. An AppImage published by anyone else is a
community build, not an official one.

### Building it, step by step

You need an **x86_64 PC running Linux** (the AppImage tool the script downloads is an x86_64 program; the result runs
on the aarch64 handheld) with several GB free and an internet connection.

1. Install the tools. Debian / Ubuntu:

       sudo apt install git curl docker.io librsvg2-bin python3 python3-venv

   Arch / CachyOS: `sudo pacman -S git curl docker librsvg python`. Then let your user run Docker without `sudo`
   and start it: `sudo usermod -aG docker $USER`, `sudo systemctl enable --now docker`, log out and back in.
   `docker run --rm hello-world` must work before you go on.

2. Get the source, on the branch `android` (the one the releases come from):

       git clone -b android https://git.eden-emu.dev/moceure/BananaNative.git
       cd BananaNative

   To build a release, check out its tag (`git checkout v0.3.26`).

3. Find the title ID of each game you want: the folder names under `dm/games/` are the game profiles (the first
   comment line of each `game.tsv` names the game). Tested on Linux:

   | Game | Title ID |
   |---|---|
   | Mario Kart World | `0400C3F00006E000` |
   | Donkey Kong Bananza | `040074A01BF12000` |
   | Mario Tennis Fever | `0400D4201C6EA000` |
   | Star Fox | `0400E88025B1A000` |

4. Build, with the title IDs of the games you want in it (one host program per game):

       linux/appimage/build-appimage.sh 0400C3F00006E000 040074A01BF12000

   The first run also builds the Docker image and SDL3, so it takes longer; later runs only rebuild what changed. With no title ID it builds the games already built in `linux/build/`, else Mario Kart World. The result is
   `linux/build/appimage/BananaNative-<version>-aarch64.AppImage`.

5. Copy it to the handheld (a USB stick, `scp`, ...), for example to `~/Games/BananaNative-aarch64.AppImage`, and make
   it executable: `chmod +x ~/Games/BananaNative-aarch64.AppImage`. Open it with a double-click: the game window shows
   each game whose `.nup` it finds in `~/Games` (its name must contain `[<title ID>]`) and offers
   **Prepare** to make its pack there (below). When it is ready, **Add to Steam** puts it in Steam's library for Game Mode
   (see "For Steam (Game Mode)" below).

What the handheld needs: glibc 2.39 or newer, a Vulkan driver with `VK_EXT_map_memory_placed` (Mesa Turnip), FUSE
(`fusermount`, to open any AppImage), and for the game window Python 3 with PyQt6 (else `zenity` for a simpler picker).
**Prepare** also needs `curl` and `spirv-val` (Ubuntu / Debian: `spirv-tools`) and downloads, once, about 130 MB of
source plus the .NET 10 runtime into `~/.local/share/banananative/packer`.

**Prepare** downloads the exact source the AppImage was built from (`<NX_SOURCE_URL>/archive/<commit>.tar.gz`), so the
commit you built must be public there. The default is this repository, which is right for a clone of `android` or a
release tag. If you build from your own commits, push them to your own fork on git.eden-emu.dev and build with
`NX_SOURCE_URL=https://git.eden-emu.dev/<you>/BananaNative linux/appimage/build-appimage.sh ...`; else Prepare stops
with a download error (an existing pack still plays).

A new release can change the shader chain (`DMSH_VK_CHAIN`): a pack made with an older version then shows a white
screen with the new AppImage. Press **Prepare** again for each game after updating.

### What the script does

`linux/appimage/build-appimage.sh [title ID ...]` builds `linux/build/appimage/BananaNative-<version>-aarch64.AppImage`
on an x86_64 PC: one host per title ID in `usr/bin`, a fallback SDL3 (`build-sdl3.sh`: X11 only, used where the system
has no `libSDL3.so.0`), and the launcher `AppRun`. It downloads `appimagetool` and the aarch64 runtime from the AppImage
project's GitHub releases on first use and needs `rsvg-convert`.

    BananaNative-aarch64.AppImage <pack folder> [game file (.nup)]
    BananaNative-aarch64.AppImage                    (no argument, from a desktop) a game picker
    BananaNative-aarch64.AppImage --games | --install | --list | --selftest [title ID]

Opened with no argument from a desktop (a double-click, no terminal), it shows the game window (`launcher.py`, PyQt6 from
the system): one card per game found (a pack, a `.nup` named `[<title ID>]`, or both) with what it needs next and the buttons
that apply: **Prepare** (makes the pack from the `.nup`, see below), **Play**, and **Options**, the options of that one game
(`<title ID>.env`). **Settings** holds the options of every game (`settings.env`). Both are lists of choices over the same
keys as `settings.env` (picture, frame rate cap, dynamic resolution and 3D resolution where the game's profile has them
(`drs_ctl`, `scene_arg`: Star Fox's arguments go to the host as `DM_GAME_ARGS_ADD`), frame
generation, overlay, window, RAM saver, CPU policy, Gamescope's layer); saving edits the file in place and keeps its other
lines; **Edit the file...** opens it. Without PyQt6, or with `NX_ZENITY=1`, it shows a picker (zenity) instead: the prepared packs
found under `~/Games` and removable media (`pack.tsv`, 5 levels down) and those added with **Add a pack folder...**
(`~/.config/banananative/games.list`), and **Settings**, which opens `settings.env`. A game whose `.nup` is not found
asks for it once (`<title ID>.nup` remembers it). Errors show as a dialog there. `--games` prints what the picker
lists; `--install` writes menu entries to `~/.local/share/applications`: `BananaNative` (the picker) and one per game
with a host here, which Steam's **Add a Non-Steam Game** also lists (they carry the `PATH` below already).

The launcher reads the title ID from the pack's `pack.tsv`, looks for the `.nup` beside the pack or in `~/Games` when
none is given, keeps saves, caches and logs in `~/.local/share/banananative/<title ID>/` and reads settings from
`~/.config/banananative/settings.env` and `<title ID>.env` (`KEY=VALUE`; a variable already in the environment wins).
Its defaults: handheld mode at graphics scale 2, the RAM saver, a fixed texture cache limit of 2300 MiB on a system
with no swap and 12 GB or less, texture cache evictions on a 3 ms budget a frame, and Gamescope's Vulkan layer off
(`NX_GAMESCOPE_WSI=1` keeps it).

Memory: every game run writes a memory trace beside its log (`<log>.mem`, one line a second: free memory, the game's
memory and GPU memory, reads back from storage). A memory guard stops the game, and says why, when free memory (MemAvailable)
is below `NX_MEM_GUARD_MB` (default 300) **and** the game has drawn no frame for `NX_MEM_GUARD_STALL` seconds (default 20);
`NX_MEM_GUARD_MB=0` turns it off. Both are needed: Mario Kart World races normally with about 100 MiB free (its RAM saver
pages textures through storage) but keeps drawing, while Ocarina of Time's 3D world needed more memory than the Odin 2 has
and the device thrashed with no frame for minutes (once frozen until switched off, once ended by the kernel's OOM killer).

Gamescope's layer and display timing: the layer offers `VK_GOOGLE_display_timing`, and the host gives present times
through it wherever it is offered. On Turnip (Adreno 740 at its full 680 MHz clock; at a 475 MHz cap it worked) that
ended in a GPU fault at the 15th present (kernel: `a6xx_irq gpu fault`, `hangcheck recover`, task `vk-present`, once
a reboot of the device) or in the encoder thread hung inside the driver within seconds. With the same layer and no
present times: four clean runs, one a full race. So the Linux host does not use display timing unless
`NX_VK_DISPLAY_TIMING=1` (`vka_dev.c`), and with that the layer is safe to leave on; the launcher still turns it off by
default because that path has had far more hours and the layer gave no measurable frame rate here.
For Steam (Game Mode): press **Add to Steam** on a game's card in the game window (it is also offered when **Prepare**
ends), or run `BananaNative-aarch64.AppImage --steam [--close-steam] [title ID ...]` (every prepared game when no title ID
is given). It adds a non-Steam game named `<game> (BananaNative)` to Steam's `shortcuts.vdf` for every Steam user here,
with the launch options below already filled in; pressed again it updates that shortcut (after moving the AppImage or the
pack) and keeps its artwork. Steam must be closed while the file is written: the window offers to close it and starts it
again, and in Game Mode it asks you to switch to Desktop Mode. The file as it was is kept as
`shortcuts.vdf.bak-banananative`. By hand: add the AppImage as a non-Steam game with the launch options
`PATH=/usr/local/bin:/usr/bin:/bin %command% "<pack folder>"`. The `PATH` part matters where Steam starts non-Steam
games without one (seen on a SteamOS-like Ubuntu image): the AppImage runtime then finds no `fusermount` and ends
before the launcher runs, with nothing on screen. The launcher writes each start and the reason it stopped to
`~/.local/share/banananative/launcher.log`; a host that cannot open a window logs the display environment and ends
(`NX_NO_WINDOW_OK=1` runs on without a picture).

## CPU governor helper

With the `ondemand` governor a core's clock rises only past 95 % load. The host's replay thread waits for the rest of
the frame every frame, shows about 70 % load, and the clock settles near 70 % of its maximum.
`sudo sh linux/tools/install-governor-helper.sh` (also in the AppImage under `usr/share/banananative/`) installs
`/usr/local/sbin/banananative-governor` and a sudoers rule for its `on [schedutil|performance]` and `off` only. The
launcher then switches the CPU clusters while a game runs (`NX_GOVERNOR=schedutil`, the default, `performance` or `0`),
and a root watcher the helper leaves behind puts back what they had once no host runs (Steam's "Exit game" kills the
launcher with the game, so the launcher cannot).

Mario Kart World on an Odin 2, one scripted race each, calm scene / start grid with 24 karts: `ondemand` 37 / 24 fps,
`schedutil` 36 / 29, `performance` 45 / 30 from a cool device. `performance` wins for a few minutes; in real play with
the charger in, the hottest CPU sensor reached 95 °C and the frame rate was back near `ondemand`'s, so `schedutil` is
the default. These runs differ in device temperature by more than the governors differ, so treat the ranking as
provisional. `ondemand` with `up_threshold` 60 was no better (33 / 26). The GPU's governor is left alone
(`performance` there: no frame time, only heat). Pinning the replay thread to the fastest core: 2-4 % with `ondemand`,
nothing with `performance`. The kernel here has no `CONFIG_UCLAMP_TASK`, so a per-thread clock floor is not possible.
