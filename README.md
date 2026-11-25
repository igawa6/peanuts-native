<h1 align="center">Peanuts Native</h1>

<p align="center">
  Nintendo Switch games running natively on Android dual-screen handhelds, with a live companion on the second screen.
</p>

<p align="center">
  <img alt="status" src="https://img.shields.io/badge/release-0.1.8-brightgreen"> <!-- PEANUTS -->
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

<!-- PEANUTS begin: the Supported games table, written by peanuts/scripts/games_catalog.py from peanuts/games.tsv, peanuts/release-titles.txt and the profiles. Edit peanuts/games.tsv, not this table -->

Peanuts Native 0.1.8 includes 76 games. The Companion column shows whether a game appears in the Eden Duo Companions catalog, and the Contributor column the community members who brought a game to Peanuts Native. Updates are shown as version numbers or numeric update IDs.

| Game | Title ID | Game update | Companion | Contributor |
|---|---|---|---|---|
| The Legend of Zelda: Link's Awakening | `01006BB00C6F0000` | 1.1.0 | Yes |  |
| The Legend of Zelda: Echoes of Wisdom | `01008CF01BAAC000` | 1.1.0 | Yes |  |
| Fire Emblem: Three Houses | `010055D009F78000` | 1.2.0 | Yes |  |
| Fire Emblem Engage | `0100A6301214E000` | 2.0.0 | Yes |  |
| Metroid Dread | `010093801237C000` | 2.1.0 | Yes |  |
| Super Mario Odyssey | `0100000000010000` | 1.4.1 | Yes |  |
| Mario Kart 8 Deluxe | `0100152000022000` | 4.0.0, 4.0.1 | Yes | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Animal Crossing: New Horizons | `01006F8002326000` | 3.0.3 | Yes |  |
| Persona 5 Royal | `01005CA01580E000` | 1.0.2 | Yes |  |
| Castlevania: Belmont's Curse | `0100BA4026E94000` | 1.1.0 | Yes |  |
| OCTOPATH TRAVELER 0 | `01005270232F2000` | 1.0.5 | Yes |  |
| Pokémon Brilliant Diamond | `0100000011D90000` | 1.3.0 | Yes |  |
| Pokémon Shining Pearl | `010018E011D92000` | 1.3.0 | Yes |  |
| Pokémon Sword | `0100ABF008968000` | 1.3.2 | Yes |  |
| Pokémon Shield | `01008DB008C2C000` | 1.3.2 | Yes |  |
| The Legend of Zelda: Breath of the Wild | `01007EF00011E000` | 1.6.0, 1.9.0 | Yes |  |
| The Legend of Zelda: Tears of the Kingdom | `0100F2C0115B6000` | 1.4.3 | No |  |
| FINAL FANTASY RESONANCE | `0100606026F12000` | 1.0.1 | No |  |
| The Legend of Zelda: Skyward Sword HD | `01002DA013484000` | 1.0.1 | Yes |  |
| Pokémon Legends: Arceus | `01001F5010DFA000` | 1.1.1 | No |  |
| Resident Evil 4 | `010099A00BC1E000` | 131072 | Yes |  |
| Kirby and the Forgotten Land | `01004D300C5AE000` | 1.1.0 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Kirby's Return to Dream Land Deluxe | `01006B601380E000` | 1.0.0 | Yes | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Super Mario 3D World + Bowser's Fury | `010028600EBDA000` | 1.2.2 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Super Mario Bros. Wonder | `010015100B514000` | 1.2.1 | Yes |  |
| DRAGON QUEST III HD-2D Remake | `01003E601E324000` | 1.1.2 | Yes |  |
| Chained Echoes | `0100C510166F0000` | 1.41 | Yes |  |
| Yoru, Tomosu | `0100C2901153C000` | Base game (v0) | Yes |  |
| Crash Team Racing Nitro-Fueled | `0100F9F00C696000` | 1.0.15 | Yes |  |
| Captain Tsubasa 2: World Fighters | `01009720213B0000` | 1.0.6 | No |  |
| DAVE THE DIVER | `010097F018538000` | 1.0.6.1267 | Yes |  |
| Demon Slayer -Kimetsu no Yaiba- The Hinokami Chronicles 2 | `0100AD80208A8000` | 1.80 | No |  |
| Xenoblade Chronicles X: Definitive Edition | `0100453019AA8000` | 1.0.2 | No |  |
| My Time at Sandrock | `0100B63016916000` | 1.4.2.0 | Yes |  |
| Monster Sanctuary | `0100A0F00DA68000` | 2.1.0 | Yes |  |
| Astral Chain | `01007300020FA000` | 1.0.1 | No |  |
| Diablo III: Eternal Collection | `01001B300B9BE000` | 2.7.7.92380 | Yes |  |
| EA Sports FC 27 | `0100C49025D3E000` | 1.8d.6523, 1.8d.f570 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Tomodachi Life: Living the Dream | `010051F0207B2000` | 1.0.4 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a><br><a href="https://github.com/davdxpx"><img src="https://img.shields.io/badge/davdxpx-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="davdxpx"></a> |
| Unicorn Overlord | `010069401ADB8000` | 1.05 | No |  |
| Luigi's Mansion 3 | `0100DCA0064A6000` | 1.4.0 | No |  |
| Pokémon Legends: Z-A | `0100F43008C44000` | 2.0.2 | No |  |
| STORY OF SEASONS: Grand Bazaar | `0100DAE01E4C8000` | v655360 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Double Dragon Revive | `01006AE02236C000` | 1.0.0, N1.10 | No | ⭐<br><a href="https://github.com/tapionallanaire"><img src="https://img.shields.io/badge/tapionallanaire-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="tapionallanaire"></a> |
| Trails in the Sky 2nd Chapter | `01007A2027548000` | 1.0.3 | No | ⭐<br><a href="https://github.com/tapionallanaire"><img src="https://img.shields.io/badge/tapionallanaire-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="tapionallanaire"></a> |
| DRAGON BALL: Sparking! ZERO | `010035F022078000` | 1.0.0 | No | ⭐<br><a href="https://github.com/dnyan007"><img src="https://img.shields.io/badge/dnyan007-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="dnyan007"></a> |
| Pokémon Scarlet | `0100A3D008C5C000` | 4.0.0 | No |  |
| Pokémon Violet | `01008F6008C5E000` | 4.0.0 | No |  |
| DRAGON BALL Z: KAKAROT + A NEW POWER AWAKENS SET | `010051C0134F8000` | 1.70 | No |  |
| STEINS;GATE RE:BOOT | `01008BC024FDE000` | 1.0.3, 1.0.4 | No | ⭐<br><a href="https://github.com/skyhong2002"><img src="https://img.shields.io/badge/skyhong2002-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="skyhong2002"></a> |
| NieR:Automata The End of YoRHa Edition (Asian release) | `0100B8E016F76000` | 1.0.3 | No |  |
| Hyrule Warriors: Age of Calamity | `01002B00111A2000` | 1.3.0 | No |  |
| Trails in the Sky 1st Chapter | `01002C9022770000` | 1.0.7 | No | ⭐<br><a href="https://github.com/tapionallanaire"><img src="https://img.shields.io/badge/tapionallanaire-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="tapionallanaire"></a> |
| Yoshi's Crafted World | `01006000040C2000` | 1.0.1 | No | ⭐<br><a href="https://github.com/999sian"><img src="https://img.shields.io/badge/999sian-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="999sian"></a> |
| Tetris Effect: Connected | `0100DE501433A000` | 2.0.2.2 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| New Pokémon Snap | `0100F4300BF2C000` | v0 | No | ⭐<br><a href="https://github.com/ssv0225"><img src="https://img.shields.io/badge/ssv0225-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="ssv0225"></a> |
| Digimon Story Time Stranger | `010062E01FE0C000` | 1.2.2 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Hogwarts Legacy | `0100F7E00C70E000` | 1.0.5 | No | ⭐<br><a href="https://github.com/tapionallanaire"><img src="https://img.shields.io/badge/tapionallanaire-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="tapionallanaire"></a> |
| Star Ocean The Second Story R | `010065301A2E0000` | 1.1.0 | No |  |
| Stray | `010075101EF84000` | 1.0.0 | No | ⭐<br><a href="https://github.com/dnyan007"><img src="https://img.shields.io/badge/dnyan007-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="dnyan007"></a> |
| 13 Sentinels: Aegis Rim | `01003FC01670C000` | 1.10 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Captain Toad: Treasure Tracker | `01009BF0072D4000` | 1.4.2 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Advance Wars 1+2: Re-Boot Camp | `0100300012F2A000` | 1.0.0 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| FANTASY LIFE i: The Girl Who Steals Time | `0100755017EE0000` | 1.5.0 | No | ⭐<br><a href="https://github.com/tapionallanaire"><img src="https://img.shields.io/badge/tapionallanaire-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="tapionallanaire"></a> |
| Pokémon: Let's Go, Pikachu! | `010003F003A34000` | 1.0.2 | No | ⭐<br><a href="https://github.com/fireworksordie"><img src="https://img.shields.io/badge/fireworksordie-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="fireworksordie"></a> |
| Pokémon: Let's Go, Eevee! | `0100187003A36000` | 1.0.2 | No | ⭐<br><a href="https://github.com/fireworksordie"><img src="https://img.shields.io/badge/fireworksordie-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="fireworksordie"></a> |
| Viewfinder | `0100F9D023176000` | 1.0.0 | No | ⭐<br><a href="https://github.com/dnyan007"><img src="https://img.shields.io/badge/dnyan007-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="dnyan007"></a> |
| SPY×ANYA: Operation Memories | `010089B01AB44000` | 1.0.1 | No | ⭐<br><a href="https://github.com/nomnomab"><img src="https://img.shields.io/badge/nomnomab-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="nomnomab"></a> |
| Super Mario RPG | `0100BC0018138000` | 1.0.1 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| WarioWare: Get It Together! | `0100563010E0C000` | 1.0.0 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| LEGO 2K Drive | `0100739018020000` | 1.17 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| LEGO CITY UNDERCOVER | `010085500130A000` | 1.0.3 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| LEGO MARVEL Super Heroes | `01006F600FFC8000` | 1.0.1 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| Minecraft Dungeons II | `0100A7C01B792000` | 1.1.3.0 | No | ⭐<br><a href="https://github.com/intermission88"><img src="https://img.shields.io/badge/intermission88-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="intermission88"></a> |
| The Witcher 3: Wild Hunt — Complete Edition | `01003D100E9C6000` | 4.04b | No |  |
| Pokémon Mystery Dungeon: Rescue Team DX | `01003D200BAA2000` | 1.0.0 | No | ⭐<br><a href="https://github.com/shamonj03"><img src="https://img.shields.io/badge/shamonj03-181717?style=flat-square&logo=github&logoColor=white" style="vertical-align: middle;" alt="shamonj03"></a> |

<!-- PEANUTS end: the Supported games table -->

The 0.1.8 release build installed over 0.1.7 on an AYN Thor (Adreno 740, Turnip 27.0.0-R2) with its data kept. The shaders of games built with 0.1.7 were upgraded in place in seconds; games with tessellation, such as Tears of the Kingdom, prepare their shaders again. The Witcher 3, Breath of the Wild, Animal Crossing: New Horizons at 60 fps with the built-in speed fix, Fire Emblem Engage, Star Ocean The Second Story R and Hogwarts Legacy were played for this release, and the companion and the stats page drew cleanly on Turnip 27.0.0-R2. Turnip v27.0.0-R3 draws broken images, and the app now warns about it. <!-- PEANUTS: what this release was checked on -->

## What you need

- A dual-screen Android handheld. Peanuts Native is developed and tested on the AYN Thor. On a single-screen phone the
  game still runs, without the companion.
- Android 13 or later, a Snapdragon GPU with Vulkan 1.3, and 12 GB of RAM or more. A Turnip Vulkan driver is
  recommended; the app can download one.
- Free storage for the game and its build. Plan for the size of the game plus a few GB.
- Your own `prod.keys`, your own firmware dump (a folder or a `.zip`) and your own game dump (NSP, XCI, either compressed
  as NSZ or XCZ, or a dump folder), with its update. <!-- PEANUTS: .nsz and .xcz (issue #149) -->

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
- **Mods & Cheats**: hold the cover, then **Mods & Cheats**. Cheats work for every game: add a cheat file, a zip or a
  folder, and each cheat has a switch that works at once, also from the in-game panel's **Mods** tab. Install a mod
  from a folder or a zip. It is on
  from the game's next start, and each mod has a switch and a delete button. A mod that changes the game's code gets
  a build with mods, which starts by itself (it waits while another build runs). Until that build is ready, the game
  starts without that mod. After a failed build, tap **Build with mods** to try again.
- **Saves**: back up, restore, import and **Delete save data** on the game's **Saves** tab.

A game can also be built on a computer and pushed to the device: see [BUILDER.md](peanuts/docs/BUILDER.md). The builder is tested
on macOS and Linux. Windows is written but not tested yet. On Linux it also builds four games whose shaders are
Oodle-compressed or in Unreal archives; for the Oodle ones it compiles a separate open-source decoder (ooz,
GPL-3.0-or-later), which is used on the computer only and is not part of the app.

## Companions

Open Settings, then Screens, then Dual-screen companions, to see every companion and every release of it. You can install, update, remove or
roll back to an earlier release, and each download is checked against its published checksum before it is used.
Without the internet, **Install from file** there takes a companion's `.dsmod.zip` from the device, with the same checks. A
game without a companion shows its live stats on the bottom screen, or its cover, or leaves the screen to Android
(Settings, Screens, **Games without a companion**). Companion screens give haptic feedback when you
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
The Miis are drawn with the colour tables and face layout of [vee](https://github.com/j0lol/vee) (MIT) and their textures decoded with
[bcdec](https://github.com/iOrange/bcdec) (MIT). <!-- PEANUTS: the Mii drawing's sources (android/NOTICE.md) -->
Animal Crossing: New Horizons' 60 fps choice uses [ACNH 60 FPS + SpeedFix](https://github.com/tmouh/acnh-60fps) v1.4.5 by tmouh
(CC BY-NC 4.0), converted to rows of changed bytes (`dm/games/01006F8002326000/speedfix60/NOTICE.md`). <!-- PEANUTS -->


The companion runtime and package format come from [Eden Duo](https://github.com/igawa6/eden-duo).

Contributors: <!-- PEANUTS begin: the contributor list of this fork -->

- [intermission88](https://github.com/intermission88): the builder for PC, Mac and Linux (`peanuts/docs/BUILDER.md`, pull request #13), the profiles of Kirby's Return to Dream Land Deluxe, Kirby and the Forgotten Land, Super Mario 3D World + Bowser's Fury, Tetris Effect: Connected and Digimon Story Time Stranger (pull requests #34, #84, #83, #330 and #269), the guide for adding a game (`peanuts/docs/NEW-GAME.md`, pull request #34), total play time and the stats page on the bottom screen (pull requests #282 and #289), names in STORY OF SEASONS: Grand Bazaar, EA SPORTS FC 27's second controller and the NVN sampler builder's getters (pull requests #354, #284 and #268), the PC tools on Windows (pull request #328), fixes to the on-device packer, the release notes popup and the Unreal shader step (pull requests #37, #35 and #71), the profiles of 13 Sentinels: Aegis Rim, Captain Toad: Treasure Tracker, Advance Wars 1+2: Re-Boot Camp, Super Mario RPG, WarioWare: Get It Together!, LEGO 2K Drive, LEGO CITY UNDERCOVER, LEGO MARVEL Super Heroes and Minecraft Dungeons II (pull requests #384, #380, #382, #443, #430, #460, #368, #469 and #479), the overlay's pill and the stats page's console mode and frame rate cap (pull requests #538, #516 and #523), packs built on Windows on every thread (pull request #481), and Oodle and Unreal shaders in the builder on macOS (pull requests #70 and #72).
- [davdxpx](https://github.com/davdxpx): the issue and discussion forms rebuilt from the ground up, with a game catalog that also keeps the Supported games table up to date (pull request #175), building from .nsz and .xcz files, the launcher and companion on the second screen, Swap screens, the GPU driver page, Share build log, game icons that outlive their NSP, companion release notes, mod folders left out of the library and Import pack (pull requests #249 and #260), Settings in ten sections with one search (pull requests #315 and #312), users with pictures, Miis and Who's playing? (pull request #321), the Mali PanVK pointer (pull request #296), Tomodachi Life's clock and boot crash fixes (pull requests #336 and #345), the build queue (pull request #448), cheats for every game and the in-game Mods tab (pull requests #436 to #441), the game page's areas (pull requests #445 to #447), saves for each user (pull requests #432 and #442), TV and monitors (pull request #407), sound that follows the game's pace (pull request #399), the GPU clock floor on Snapdragon 8 Gen 2 and 8 Gen 3 (pull request #457), and many smaller fixes across the app.
  The largest of it is incremental builds (`peanuts/docs/INCREMENTAL-BUILD.md`): each stage of the phone's build keeps what an earlier build made, addressed by the exact inputs it came from (a shader by its part of the profile, a code module by the patches its build ID takes, a game file by its SHA-256), checks a sample of it against a fresh translation byte for byte, and makes again only what changed. An Import again of Animal Crossing: New Horizons on an AYN Thor went from 7 min 31 s to 1 min 2 s, with the same pack to the byte. <!-- PEANUTS -->
- [elliott-groves](https://github.com/elliott-groves): development builds that install beside the Peanuts Native app (pull request #60).
- [tapionallanaire](https://github.com/tapionallanaire): building the APK on Windows (pull request #54), the Hogwarts Legacy, Trails in the Sky 1st Chapter, Trails in the Sky 2nd Chapter and Double Dragon Revive profiles (pull requests #95, #221, #164 and #197), the shader readers for Level-5 archives (pull requests #230 and #243), the Unreal 4.27 allocator's page alignment (pull request #236), the Advance Wars 1+2: Re-Boot Camp memory pool and FINAL FANTASY TACTICS update 1.2.0 (pull requests #231 and #228), and the FANTASY LIFE i: The Girl Who Steals Time profile (pull request #224).
- [dnyan007](https://github.com/dnyan007): the DRAGON BALL: Sparking! ZERO profile, its allocator start-up fix and 60 fps, the sync of GPU-written terrain into block-compressed textures (pull requests #168, #167, #290 and #302), and the Stray and Viewfinder profiles at 60 fps (pull requests #379 and #426).
- [skyhong2002](https://github.com/skyhong2002): STEINS;GATE RE:BOOT 1.0.4, standalone NUP updates in the library, and Prepare surviving a missed job status read (pull requests #192, #185 and #181).
- [ssv0225](https://github.com/ssv0225): the New Pokémon Snap profile with its DRPF archive reader for the shader split and the NVN memory pool builder's getters, and the shader tools running on Linux (pull requests #307, #308, #309 and #310).
- [999sian](https://github.com/999sian): Yoshi's Crafted World on Android, with samplers chosen by the view's format, streamed mip levels, bounded draw ranges and buffer-texture classes (pull request #140).
- [ts-miller](https://github.com/ts-miller): the encoder opcodes for occlusion queries (pull request #263), the frame rate cap in the GPU clock floor (pull request #220), and textures written one texel at a time without long stalls (pull request #403).
- [shamonj03](https://github.com/shamonj03): the Pokémon Mystery Dungeon: Rescue Team DX profile with its start hang fix (pull request #502), textures the game filled before their first use (pull request #419), dual-source shader outputs (pull request #420), and two fixes to the shader split (pull requests #417 and #418).
- [fireworksordie](https://github.com/fireworksordie): the Pokémon: Let's Go, Pikachu! and Let's Go, Eevee! profiles (pull request #245).
- [nomnomab](https://github.com/nomnomab): the SPY×ANYA: Operation Memories profile (pull request #332).
- [danielcamilo1](https://github.com/danielcamilo1): the CRT filter (pull request #397).
- [g0giberry](https://github.com/g0giberry): NVN samplers that no longer flash the picture in Super Mario Odyssey (pull request #521).
- [gerdguerrero](https://github.com/gerdguerrero): Fire Emblem Engage 2.0.0's memory alignment (pull request #540).
- [ArchOwlen](https://github.com/ArchOwlen): the first issue and discussion forms (pull request #5).
<!-- PEANUTS end -->

The project as a whole is licensed GPL-3.0-or-later. Frame generation (ported from Eden and lsfg-vk) and other
components carry their own licenses; see `android/NOTICE.md` and `dm/third_party/README.md`.
