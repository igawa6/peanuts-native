# Troubleshooting

Start with the Readiness check, try the other GPU driver, then share a log. This page lists the common problems and how to report one.

## Readiness check

Settings > **Readiness check** checks Android, memory, file access, storage, the GPU and its driver, and each game's needs. An item that needs fixing has a button that opens the right place (for example **Allow file access**). **Run again** checks again; **Share** and **Copy report** give you the report for an issue.

## Share a log

Each game start writes a log.

- The game's ... button > **Storage** > **Share log**: that game's newest log.
- On the game's **Storage** tab, **Share build log** collects the preparation logs into a ZIP. Choose **Share** or **Save to a folder**.
- In Settings, **Share log** opens the newest game log. When a build log is newer, you can choose the run or the build. <!-- PEANUTS: BuildLogUi.shareLog -->

A shared log lists the settings you changed from the defaults (the Advanced switches, and any lines of your own env.txt or Extra environment), so a helper sees them without asking. Folders and secrets are left out of that list. Nothing is sent unless you share.

## Common problems

| What you see | What to try |
|---|---|
| The game is not on the list, or says "Not Supported Yet" | Your app does not include it. Settings > **About** lists the games. Update the app ([Updates](Updates)). <!-- PEANUTS --> |
| The card says the version is untested | Your dump is another version than the tested one. It may still work; the app asks before starting. |
| Preparing stopped | Read the card, tap **Retry**. Close other apps if memory ran out; free space if storage is full. |
| Prepare says "Paused: the phone is too hot" | Let the phone cool; it goes on by itself. |
| Prepare seems stuck at 1 % ("Rewriting the game code") or at "Finding the shaders" | These steps can run for minutes without a count (reported on 0.2.1 and with large `.nup` files); check it is still working before stopping it. |
| Prepare stops with a "shaders did not build" or "is cut short" message | Tap **Retry**; if it stops the same way, report it with the log. |
| **Add game** on a dump with no `updates/` does not offer Prepare | Known in some versions: only a pack made on a Mac worked; report your version and see [First start and Prepare](First-Start-and-Prepare). |
| The picture is wrong or the game crashes | Try the other GPU driver ([GPU drivers](GPU-Drivers)). |
| The phone gets hot and fps drops | Let it cool; a phone cooler helps ([Heat and battery](Heat-and-Battery)). |
| The first start is slow or black | Normal for some games while the GPU driver builds its cache ([Playing](Playing#first-starts-can-be-slow)). |
| "Game file gone" or "Save folder gone" | See [Saves and storage](Saves-and-Storage). |
| A `.nup` stops right after boot with a "cannot be used as a NUP package" error | Reported on v0.2.2 for games with DLC (add-on) mounts; update the app and report if it persists. |
| A game sees no DLC | Reported on Android with a `dlc/` folder in the dump; update the app and report with the log. |
| A frontend starts the game but Home opens instead | Something needs you (no pack, Prepare, shaders, All files access); fix it, start again ([Playing](Playing)). |

These entries come from the project's symptom index; a fix may already be in your version, so update first.

## Settings to try

When a game stops the way a row below says, the notice "The game stopped unexpectedly" (and its **What it means** page) shows a button **Try:** with the setting's name. A tap sets that row for this game only; it applies at the next start, and **Undo** puts it back to **Auto**. The button shows only while the row is still **Auto** for the game, and once per setting and game. You can set the same rows by hand ([Settings: Advanced](Settings-Advanced)).

<!-- fix-table:begin (dm/tools/advanced_fix.py) -->
| What you see | Settings > Advanced > row | What it does |
|---|---|---|
| "The game stopped unexpectedly", likely cause: Android's low-memory killer ended the game: the phone ran out of memory | the game's ... button > Game settings > Advanced switches > Memory > **Soft textures**: On | Keeps large textures without their finest level: less memory, textures look a little softer. |
| "The game stopped unexpectedly": Android closed it, the phone ran out of memory | the game's ... button > Game settings > Advanced switches > Memory > **Soft textures**: On | Keeps large textures without their finest level: less memory, textures look a little softer. |
<!-- fix-table:end -->

## Opening an issue

Open an [issue](https://git.eden-emu.dev/moceure/BananaNative/issues/new/choose) with the form. It asks for:

- what happened, and what you did just before;
- the phone, its chip and RAM, and the Android version;
- the GPU driver (Settings > **GPU driver**) and its version if it is custom;
- the game's title ID and version, and the Banana Native release;
- the Readiness check result (**Copy report**);
- the log (**Share log**).

Screenshots or a short video help for a wrong picture. Questions and "it runs on my phone" reports are welcome as issues too ([Device reports](Device-Reports)). Please don't attach, offer or link game files, keys or firmware.
