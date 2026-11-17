<h1 align="center">Peanuts Native</h1>

<p align="center">
  Nintendo Switch games running natively on Android dual-screen handhelds, with a live companion on the second screen.
</p>

<p align="center">
  <img alt="status" src="https://img.shields.io/badge/release-0.1.3-brightgreen">
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2013%2B-green">
</p>

> [!NOTE]
> Peanuts Native is in development. The current release is on the [releases page](https://github.com/igawa6/peanuts-native/releases). It is not affiliated with Nintendo.

## What it is

Peanuts Native turns a dump of a Switch 1 game you own into an Android app on your phone. It is not an emulator. The
game's own ARM64 code is rewritten to run directly on the phone's CPU, its graphics are replayed on Vulkan, and its
shaders are translated on the device. Everything happens on the phone: you pick the game file, tap Build, and play.

On a dual-screen handheld such as the AYN Thor, the game plays on the top screen and the bottom screen shows a
companion for that game: a map, your party, items or quests, read live from the running game and touchable. Companions
use the same package format as [Eden Duo](https://github.com/igawa6/eden-duo), so every Eden Duo companion works here
too.

## Supported games

These are the Switch 1 games this app version includes. The last column says what was seen on an AYN Thor: a game
marked "new" was played there for a short time only, and the last two have not been run on a device yet. Kirby's
Return to Dream Land Deluxe was run by its contributor on their own AYN Thor only. The
Companion column says whether the game has a companion that was checked with it. Use the game update listed. The app
shows which update you have and which one a game supports, and asks before building any other.

| Game | Title ID | Game update | Companion | Runs at |
|---|---|---|---|---|
| The Legend of Zelda: Link's Awakening | `01006BB00C6F0000` | 1.1.0 | Yes | 60 fps |
| The Legend of Zelda: Echoes of Wisdom | `01008CF01BAAC000` | 1.1.0 | Yes | 60 fps |
| Fire Emblem: Three Houses | `010055D009F78000` | 1.2.0 | Yes | 60 fps |
| Fire Emblem Engage | `0100A6301214E000` | 2.0.0 | Yes | 60 fps |
| Metroid Dread | `010093801237C000` | 2.1.0 | Yes | 60 fps |
| Super Mario Odyssey | `0100000000010000` | 1.4.1 | Yes | 60 fps |
| Mario Kart 8 Deluxe | `0100152000022000` | 4.0.0, 4.0.1 | Yes | 60 fps |
| Animal Crossing: New Horizons | `01006F8002326000` | 3.0.3 | Yes | 30 fps, as on Switch |
| Persona 5 Royal | `01005CA01580E000` | 1.0.2 | Yes | 60 fps |
| Castlevania: Belmont's Curse | `0100BA4026E94000` | 1.1.0 | Yes | 60 fps |
| OCTOPATH TRAVELER 0 | `01005270232F2000` | 1.0.5 | Yes | 60 fps |
| Pokémon Brilliant Diamond | `0100000011D90000` | 1.3.0 | Yes | 60 fps |
| Pokémon Shining Pearl | `010018E011D92000` | 1.3.0 | Not checked | 60 fps; new, little tested |
| Pokémon Sword | `0100ABF008968000` | 1.3.2 | Yes | 60 fps (30 on Switch) |
| Pokémon Shield | `01008DB008C2C000` | 1.3.2 | Yes | 60 fps (30 on Switch); new, little tested |
| The Legend of Zelda: Breath of the Wild | `01007EF00011E000` | 1.9.0 | Yes | 60 fps (30 on Switch); new, little tested |
| FINAL FANTASY RESONANCE | `0100606026F12000` | 1.0.1 | No | 45 to 60 fps; new, little tested |
| The Legend of Zelda: Skyward Sword HD | `01002DA013484000` | 1.0.1 | Not checked | 60 fps; new: right indoors, the fog outdoors is not checked and may draw wrong |
| Pokémon Legends: Arceus | `01001F5010DFA000` | 1.1.1 | No | 30 fps, as on Switch; new, little tested |
| Tomodachi Life: Living the Dream | `010051F0207B2000` | 1.0.4 | Not checked | 30 fps; new, little tested: the island with a save, by its contributor; 1 boot of 3 crashed |
| Resident Evil 4 | `010099A00BC1E000` | v131072 | Not checked | 60 fps; new, a first profile: the first scenes only |
| Kirby's Return to Dream Land Deluxe | `01006B601380E000` | 1.0.0 (base game) | Yes | 50 to 60 fps; new, little tested: its first stage, by its contributor |
| Super Mario Bros. Wonder | `010015100B514000` | 1.0.0, 1.0.1, 1.2.1 | Not checked | Not tested on a device yet |
| DRAGON QUEST III HD-2D Remake | `01003E601E324000` | 1.1.2 only | Not checked | Not tested on a device yet |

## What you need

- A dual-screen Android handheld. Peanuts Native is developed and tested on the AYN Thor. On a single-screen phone the
  game still runs, without the companion.
- Android 13 or later, a Snapdragon GPU with Vulkan 1.3, and 12 GB of RAM or more. A Turnip Vulkan driver is
  recommended; the app can download one.
- Free storage for the game and its build. Plan for the size of the game plus a few GB.
- Your own `prod.keys`, your own firmware dump (a folder or a `.zip`) and your own game dump (NSP, XCI or a dump
  folder), with its update.

## Your keys and games

Peanuts Native only runs games you dumped from your own console, with keys from that console. It contains no Nintendo
code, firmware or keys, never downloads any, and does not link to places that offer them. Nobody from this project
will ever ask you for money, keys or game files.

## Getting started

1. Install the APK and allow All files access when asked.
2. On the first screen, add your **Keys** (`prod.keys`), pick your **Game folder**, and add your **Firmware**. Both
   keys and firmware are needed before the first build. A build folder is optional (Settings, Library, **Build
   folder**): pick one, on the phone or the microSD card, to keep every build there, organized by title ID.
3. Open a game from the library and tap **Build**. The app imports the game, rewrites its code, translates its
   shaders and installs its companion. Most games take 5 to 40 minutes on the Thor.
4. Tap the game to play. The companion starts on the bottom screen.

Hold a game's cover to open its own page: picture and speed settings, mods, the companion, saves and storage.
After an app update, rebuilding every game is strongly recommended: hold the cover, open **Storage**, tap
**Build again**, then **Rebuild**.

## Settings worth knowing

- **Console mode**: Docked or Handheld, for all games in Settings and per game on its page. It can also be changed
  while the game runs, from the in-game panel.
- **Resolution scale** (first version): 0.5x to 2x of the size the game draws at. Not every effect follows it.
- **Mods** (first version): hold the cover, **Game** tab, **Mods**. Install a mod from a folder or a zip. It is on
  from the game's next start, and each mod has a switch and a delete button. A mod that changes the game's code gets
  a build with mods, which starts by itself (it waits while another build runs). Until that build is ready, the game
  starts without that mod. After a failed build, tap **Build with mods** to try again.
- **Saves**: back up, restore, import and **Delete save data** on the game's **Saves** tab.

A game can also be built on a computer and pushed to the device: see [BUILDER.md](BUILDER.md). The builder is tested
on macOS and Linux. Windows is written but not tested yet. On Linux it also builds four games whose shaders are
Oodle-compressed or in Unreal archives; for the Oodle ones it compiles a separate open-source decoder (ooz,
GPL-3.0-or-later), which is used on the computer only and is not part of the app.

## Companions

Open Settings, then Companions, to see every companion and every release of it. You can install, update, remove or
roll back to an earlier release, and each download is checked against its published checksum before it is used. A
game without a companion leaves the bottom screen free for Android. Companion screens give haptic feedback when you
tap them.

The companions themselves are published in
[eden-duo-companions](https://github.com/igawa6/eden-duo-companions), with screenshots and what each one shows.

## FAQ

**Is this piracy?** No. It runs only a dump of a game you own, made from your own console, and ships no game code,
firmware or keys.

**My game update is different from the table. Will it work?** The game's page tells you. Some games support more than
one update; others need the exact one listed. For an unsupported update the app asks before building, and the result
may not start.

**Can I share my built game or its shaders?** No. Both are made from your own game's files. Share nothing derived
from a game, and point other players to their own dump instead.

**Does the companion slow the game down?** Very little. The games above hold their frame rate with the companion
running.

More questions and troubleshooting: [FAQ.md](FAQ.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for adding a game, fixing a bug and testing on a device. Run
`sh peanuts/scripts/install-hooks.sh` once after cloning.

## Credits and license

Peanuts Native is a fork of [Banana Native](https://git.eden-emu.dev/moceure/BananaNative) by moceure, which does the
real work of running Switch games natively on Android and is built on [nxnative](https://github.com/seedds/nxnative)
by seedds (MIT). Some fixes are adapted from [nx2android](https://github.com/shirai91/nx2android) (MIT).

The companion runtime and package format come from [Eden Duo](https://github.com/igawa6/eden-duo).

Contributors: the builder for PC, Mac and Linux (`peanuts/docs/BUILDER.md`) is by [intermission88](https://github.com/intermission88)
(pull request #13), and so are the profile of Kirby's Return to Dream Land Deluxe and the guide for adding a game
(`peanuts/docs/NEW-GAME.md`, pull request #34). The issue and discussion forms were set right by [ArchOwlen](https://github.com/ArchOwlen) (pull request #5).

The project as a whole is licensed GPL-3.0-or-later. Frame generation (ported from Eden and lsfg-vk) and other
components carry their own licenses; see `android/NOTICE.md` and `dm/third_party/README.md`.
