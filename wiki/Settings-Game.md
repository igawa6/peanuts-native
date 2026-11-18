# Settings: Game

The **Game** tab of a game's page holds the options that belong to that game: its own switches, game language, cheats, and for some games time of day or a choice of edition. The speed and picture settings are on the Performance tab ([Settings: Performance](Settings-Performance)); controller settings are in [Settings: Controls](Settings-Controls). These rows apply at the next start unless a row says otherwise.

<!-- TODO screenshot: a game page, Game tab, Game options and Controls sections -->

Rows appear only when the game's profile has them. If this app has no profile for a game at all, its page shows only **Saves** and **Storage**, with a line saying the game is not in this build (Settings > About lists the games this build includes).

## Switch 2 Edition

- **What it does:** shown for a game that has a Switch 2 Edition in the dump (Super Mario Bros. Wonder, `040015100B514001`). **Off:** Play starts the Switch game. **On:** Play starts the Switch 2 Edition, which has its own pack.
- **When to toggle:** when you want the edition. The save is shared between the two.
- **What to expect:** the edition needs a dump folder that holds it; a `.nup` has only the original program (see [First start and Prepare](First-Start-and-Prepare)).
- **Default:** off.

## Game options

### Full vegetation
- **What it does:** Ocarina of Time (`0400486024DEC000`) only. **On:** all grass and foliage. **Off:** less grass and foliage, less GPU work.
- **When to toggle:** turn it off if the game is too slow. The game's page recommends it off on a phone with Qualcomm's driver (see the Recommended card).
- **Default:** on, unless the recommendation says otherwise.

### Hidden layers off (Experimental)
- **What it does:** skips drawing layers that the picture covers anyway ("Skips layers drawn over later"). Off: "Draws every layer".
- **What to expect:** the layers were judged hidden by eye in a few places only; expect mistakes. Shown for Ocarina of Time.
- **Default:** off.

### The game's own switches
Ocarina of Time has these, each with a line saying what it does. They apply at the next start. A row that needs the game's code patched shows "Needs Prepare again: patches the game's code (Storage > Prepare again)" until you run Prepare again.

| Row | What the app says | Notes |
|---|---|---|
| **Max resolution** | Keeps the 3D scene at full size: sharper, more GPU work. Best with Resolution > Quality | turns on Texture pool ×2 with it |
| **Texture pool ×2** | Twice the game's starting graphics memory. Max resolution needs it | |
| **Unlocked frame rate** | Game logic follows real time, so speed holds when fps changes. Does not raise fps | |
| **Keep cave walls (Qualcomm)** | Fixes walls and rocks that vanish on Death Mountain Trail with Qualcomm's driver. Costs GPU time on some phones, try it if you see holes. No effect on Turnip | |

Default for all: off. See the game's page in [Games](Games) for the current state.

### Cheats
Ocarina of Time and Fire Emblem: Fortune's Weave have a **Cheats** switch (Experimental, off by default) and a **Cheat files** sheet. See [Cheats](Cheats).

### Link Play
- Pokémon Pokopia (`0400A3A01EE4C000`) has a **Link Play** row: an opt-in way for the phone to join a world hosted on a PC (in Recompia). You enter the host's IPv4 address, and for Internet / Tailscale its room code. It applies at the next start; the choices are **Off** and **Join PC host** (shown as **Join PC host - same Wi-Fi** or **Join PC host - Internet / Tailscale**). In the game you then use PC > Link Play > Local connection > Visit.

### Game language
- **What it does:** the language the game starts in. **Phone's language · …** (the default) follows the phone when the game offers that language, else English (US); or pick one of the game's own languages. The list is the game's own.
- **When to toggle:** if the game shows the wrong language, or you want it in another one.
- **Default:** the phone's language.

### Time of day
- **What it does:** for a game whose day and night cycle follows its clock (Pokopia), **Pick a date and time…** gives the game that time at its next start, or **The phone's clock** (default). The phone's own clock is never changed. It is one setting for the app, in Settings > System as well as on the game's page.

## Controls on this tab

Rumble, Button mapping, Motion sensitivity and Xbox button labels are described in [Settings: Controls](Settings-Controls).

## Sources

`GameSettings.java`, `CheatsUi.java`, `LinkPlay.java`, `GameLanguage.java`, `TimeOfDay.java`, `Edition.java`, `dm/games/0400486024DEC000/game.tsv` (`option` rows), `Profile.java`.
