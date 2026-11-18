# Games

Banana Native runs Nintendo Switch 2 games from a dump folder you made from your own console, or from a `.nup` package of one. This page lists every game the app has a profile for, how well each one runs, and which settings are specific to it. The table below is generated from the game profiles in the source, so it follows the app version.

## What the status means

| Status | Meaning |
|---|---|
| **Supported** | Played through real sessions on the author's test phones. Known problems are listed on its page. |
| **Experimental** | Starts, but it is little tested and has known problems; some of these are not playable yet. See [Troubleshooting](Troubleshooting) and open an issue with the log if something goes wrong. |

A game is Switch 2 only here. Other titles in the project's source belong to the Mac and iOS side and are not offered by the Android app.

## The games

<!-- games-table:begin (scripts/wiki_games_table.py) -->
| Game | Title ID | Status | Update | Frame rate and notes | Game-specific settings |
|---|---|---|---|---|---|
| [Donkey Kong Bananza](Game-Donkey-Kong-Bananza) | `040074A01BF12000` | Supported | update 458752 | 30 fps | Aspect ratio |
| [Fire Emblem Fortune's Weave](Game-Fire-Emblem-Fortunes-Weave) | `04007EF01F8AE000` | Supported | update 131072 | 30 fps; native 4:3 option; on Adreno 740 pick Turnip | Native 4:3, Upscaler, Cheats |
| [Mario Kart World](Game-Mario-Kart-World) | `0400C3F00006E000` | Supported | 1.8.0 | a phone cooler is recommended | Aspect ratio |
| [Pokémon Pokopia](Game-Pokemon-Pokopia) | `0400A3A01EE4C000` | Supported | update 655360 (Ver. 2.0.1) | about 60 fps | Link Play, Time of day |
| [Star Fox](Game-Star-Fox) | `0400E88025B1A000` | Supported | update 131072 | played from start to finish by a player, who reported no problems; frame rate and memory not measured | Upscaler, 3D scene |
| [Mario Tennis Fever](Game-Mario-Tennis-Fever) | `0400D4201C6EA000` | Experimental | update 327680 (1.1.1) | plays at 60 fps on Turnip on the tested phone; Qualcomm's own driver stalls and shows dark patches on the player intros; a long first Prepare (35,045 shaders) | common settings only |
| [Splatoon Raiders](Game-Splatoon-Raiders) | `0400380020056000` | Experimental | update 65536 | boots and runs, but not playable yet (very low frame rate) | common settings only |
| [Super Mario Bros. Wonder - Nintendo Switch 2 Edition](Game-Super-Mario-Bros-Wonder-Switch-2-Edition) | `040015100B514001` | Experimental | update 327680 (1.2.1), program 1 | reached a first course at 30 fps on Turnip on one phone; the system driver is not tested; needs a dump with the edition's own program | common settings only |
| [The Legend of Zelda: Ocarina of Time](Game-Zelda-Ocarina-of-Time) | `0400486024DEC000` | Experimental | 2.0 | plays on the system driver and Turnip, full foliage; about 30 fps in Kokiri Forest, 50 indoors | Resolution presets, Aspect ratio, Cheats, Game options |
<!-- games-table:end -->

Frame rates in the table are what the project's notes say for the tested phones. Your numbers depend on the phone, its temperature, power mode and [GPU driver](GPU-Drivers). Where nothing is stated the number is "not measured yet".

## Pages for each game

- [Donkey Kong Bananza](Game-Donkey-Kong-Bananza)
- [Fire Emblem: Fortune's Weave](Game-Fire-Emblem-Fortunes-Weave)
- [Mario Kart World](Game-Mario-Kart-World)
- [Pokémon Pokopia](Game-Pokemon-Pokopia)
- [The Legend of Zelda: Ocarina of Time](Game-Zelda-Ocarina-of-Time)
- [Mario Tennis Fever](Game-Mario-Tennis-Fever)
- [Star Fox](Game-Star-Fox)
- [Splatoon Raiders](Game-Splatoon-Raiders)
- [Super Mario Bros. Wonder - Nintendo Switch 2 Edition](Game-Super-Mario-Bros-Wonder-Switch-2-Edition)

## Update versions

Each profile is made for one update version of the game (the "Update" column). If your dump holds another version, the app warns you; you may still try it, but nothing is promised. See [First start and Prepare](First-Start-and-Prepare).

## Reports

Phone-by-phone reports are collected in [Device reports](Device-Reports) and [Tested devices](Tested-Devices).
