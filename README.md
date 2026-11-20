<h1 align="center">Peanuts Native</h1>

<p align="center">
  Nintendo Switch games running natively on Android dual-screen handhelds, with a live companion on the second screen.
</p>

<p align="center">
  <img alt="status" src="https://img.shields.io/badge/release-0.1.5-brightgreen"> <!-- PEANUTS -->
  <img alt="license" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%2013%2B-green">
</p>

<div align="center">
  <a href="https://discord.gg/r6vBWKAqCK"><img src="https://img.shields.io/badge/Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord"></a>&nbsp;<a href="https://ko-fi.com/igawa6"><img src="https://img.shields.io/badge/Support_me_on_Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
</div>

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

<!-- PEANUTS: supported game versions and Eden Duo companion availability -->

Peanuts Native 0.1.5 includes 39 games. The Companion column shows whether a game appears in the Eden Duo Companions catalog. Updates are shown as version numbers or numeric update IDs.

| Game | Title ID | Game update | Companion |
|---|---|---|---|
| The Legend of Zelda: Link's Awakening | `01006BB00C6F0000` | 1.1.0 | Yes |
| The Legend of Zelda: Echoes of Wisdom | `01008CF01BAAC000` | 1.1.0 | Yes |
| Fire Emblem: Three Houses | `010055D009F78000` | 1.2.0 | Yes |
| Fire Emblem Engage | `0100A6301214E000` | 2.0.0 | Yes |
| Metroid Dread | `010093801237C000` | 2.1.0 | Yes |
| Super Mario Odyssey | `0100000000010000` | 1.4.1 | Yes |
| Mario Kart 8 Deluxe | `0100152000022000` | 4.0.0, 4.0.1 | Yes |
| Animal Crossing: New Horizons | `01006F8002326000` | 3.0.3 | Yes |
| Persona 5 Royal | `01005CA01580E000` | 1.0.2 | Yes |
| Castlevania: Belmont's Curse | `0100BA4026E94000` | 1.1.0 | Yes |
| OCTOPATH TRAVELER 0 | `01005270232F2000` | 1.0.5 | Yes |
| Pokémon Brilliant Diamond | `0100000011D90000` | 1.3.0 | Yes |
| Pokémon Shining Pearl | `010018E011D92000` | 1.3.0 | Yes |
| Pokémon Sword | `0100ABF008968000` | 1.3.2 | Yes |
| Pokémon Shield | `01008DB008C2C000` | 1.3.2 | Yes |
| The Legend of Zelda: Breath of the Wild | `01007EF00011E000` | 1.6.0, 1.9.0 | Yes |
| The Legend of Zelda: Tears of the Kingdom | `0100F2C0115B6000` | 1.4.3 | No <!-- PEANUTS --> |
| FINAL FANTASY RESONANCE | `0100606026F12000` | 1.0.1 | No |
| The Legend of Zelda: Skyward Sword HD | `01002DA013484000` | 1.0.1 | Yes |
| Pokémon Legends: Arceus | `01001F5010DFA000` | 1.1.1 | No |
| Resident Evil 4 | `010099A00BC1E000` | 131072 | Yes |
| Kirby and the Forgotten Land | `01004D300C5AE000` | 1.1.0 | No |
| Kirby's Return to Dream Land Deluxe | `01006B601380E000` | 1.0.0 | Yes |
| Super Mario 3D World + Bowser's Fury | `010028600EBDA000` | 1.2.2 | No |
| Super Mario Bros. Wonder | `010015100B514000` | 1.2.1 | Yes |
| DRAGON QUEST III HD-2D Remake | `01003E601E324000` | 1.1.2 | Yes |
| Chained Echoes | `0100C510166F0000` | 1.41 | Yes |
| Yoru, Tomosu | `0100C2901153C000` | Base game (v0) | Yes |
| Crash Team Racing Nitro-Fueled | `0100F9F00C696000` | 1.0.15 | Yes |
| Captain Tsubasa 2: World Fighters | `01009720213B0000` | 1.0.6 | No <!-- PEANUTS --> |
| DAVE THE DIVER | `010097F018538000` | 1.0.6.1267 | Yes <!-- PEANUTS --> |
| Demon Slayer -Kimetsu no Yaiba- The Hinokami Chronicles 2 | `0100AD80208A8000` | 1.80 | No <!-- PEANUTS --> |
| Xenoblade Chronicles X: Definitive Edition | `0100453019AA8000` | 1.0.2 | No |
| My Time at Sandrock | `0100B63016916000` | 1.4.2.0 | Yes |
| Monster Sanctuary | `0100A0F00DA68000` | 2.1.0 | Yes |
| Astral Chain | `01007300020FA000` | 1.0.1 | No <!-- PEANUTS --> |
| Diablo III: Eternal Collection | `01001B300B9BE000` | 2.7.7.92380 | Yes |
| EA Sports FC 27 | `0100C49025D3E000` | 1.8d.6523 | No |
| Tomodachi Life: Living the Dream | `010051F0207B2000` | 1.0.4 | No |

On the 0.1.5 verification build, an AYN Thor with Turnip v26.2.0 R5 reached the rooftop fight in Demon Slayer 2 and the opening cave in Tears of the Kingdom. Kirby and the Forgotten Land skipped no draws in the opening walk with either a fresh or a saved shader cache.

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

A game can also be built on a computer and pushed to the device: see [BUILDER.md](peanuts/docs/BUILDER.md). The builder is tested
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

More questions and troubleshooting: [FAQ.md](peanuts/docs/FAQ.md).

## Contributing

See [CONTRIBUTING.md](peanuts/docs/CONTRIBUTING.md) for adding a game, fixing a bug and testing on a device. Run
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
