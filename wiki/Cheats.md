# Cheats

Banana Native can apply cheat files in the Atmosphère format to a running game. Cheats are **experimental** and **off by default**. Two games ship with a cheat file of their own: Ocarina of Time (`0400486024DEC000`) and Fire Emblem: Fortune's Weave (`04007EF01F8AE000`). Both files were written from reading the game's code and had **not been tried in play** at the time of writing; each cheat lists what is known.

<!-- TODO screenshot: game page, Game tab, Cheats switch and Cheat files row -->

## Turn cheats on

1. Open the game's page (the **...** on its card), tab **Game**, section **Game options**.
2. Turn on **Cheats**. The row says "Cheat list in the in-game panel (General). Cheated values are stored when the game saves."
3. Start the game. Open the in-game panel (see [The in-game panel](Side-Panel-In-Game)), tab **General**: the cheats are listed under a **Cheats** heading, grouped by section, one switch each. Each switch is live.

With **Cheats** off, nothing is passed to the game and the panel shows no cheat list. A game without a cheat file has no Cheats row.

**Warning:** a cheated value is written into the game's memory, and the game stores it when it saves. A cheat turned off leaves the last value in place, and the game's next save keeps it. If you care about a save, keep a copy ([Saves and storage](Saves-and-Storage)).

## The files

- **The game's own file** comes with the app and is copied into the game's pack by Prepare. If the row says "Prepare again to add it to this game's pack", run **Storage > Prepare again** ([First start and Prepare](First-Start-and-Prepare)).
- **Imported files**: Atmosphère-format `.txt` cheat files you add yourself for this game's build. Under **Cheat files**:
  - **Import cheat file…** takes one file.
  - **Remove** (a tap on an imported file) takes it out.
  - **Export cheats…** writes the imported files and your switch settings as one `.zip`; **Import cheats…** reads such a zip back. Neither touches a save.
- **The build must match.** A file is taken only if its name is `<build ID>.txt` (the first 16 hex digits of the game's main build ID, as Atmosphère names them), or if its first line says `build <build ID>`. A file for another build is refused, with the reason on screen, because it would write to the wrong places. The game must be prepared first so its build ID is known.
- A file larger than 256 KB, a file that is not text, or a file with no `[Name]` entry followed by code lines is refused.
- Each start merges the game's own file and your imported files into one list; an imported entry that has the same name as a built-in one gets " (imported)" added.

## Which cheats come with the games

| Game | Cheats in its file | State |
|---|---|---|
| Ocarina of Time (`0400486024DEC000`), game version 2.0 | **Player:** Infinite Rupees (999), Infinite Health (experimental), Infinite Magic (experimental). **Ammo:** Arrows, Bombs, Bombchus, Deku Nuts, Deku Seeds, Deku Sticks (99 each). **Items:** Magic Beans (99), Business Scrub Seeds (99), Gold Skulltula Tokens (100). | Written from the game's code; not checked on a phone yet. Health and Magic may still not work if the game keeps its own copy. |
| Fire Emblem: Fortune's Weave (`04007EF01F8AE000`) | **Army:** Gold 9,999,999. **Party:** all stats at cap; max level and EXP (experimental); max weapon and class EXP (experimental). | Written from the game's code; not checked on a phone yet. It writes only to player units. |

Values are written again on every pass while a cheat is on, and they ignore the game's own limits (for Ocarina of Time the wallet and bag size): the game may clamp a value the next time it changes it.

## If a cheat does nothing

- Check that **Cheats** is on in the game's page and that the cheat's switch is on in the panel.
- A cheat may write nothing for a short while at the start, until the game has created the data it needs. It stays on and writes once the data exists.
- Open an issue ([Troubleshooting](Troubleshooting)) with the log from **Share log**, the game's version and the cheat's name.

## How it works (short)

The app runs the same kind of cheat program a Switch with Atmosphère runs: a short program of memory reads and writes, run over and over while the game plays. Differences from a console: there is no button combination to toggle cheats; toggling happens in the panel. For the details see the repository's cheats reference.

## Sources

`CheatsUi.java`, `Cheats.java`, `SidePanel.java` (`cheatRows`), `res/values/strings.xml`, `dm/games/0400486024DEC000/README.md` and `dm/games/04007EF01F8AE000/README.md` (Cheats sections), `dm/games/*/cheats/*.txt`, `docs/reference/cheats.md`.
