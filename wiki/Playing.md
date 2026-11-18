# Playing

How to start a game, stop it, start it from a launcher or frontend, and where its settings are. The settings themselves are on their own pages ([Settings overview](Settings-Overview)).

## Start and stop

- Tap **Play** on the game's card once it says it is ready. If something is missing (pack, shaders, file access) the app says so before it starts, with the reason.
- Stop with the in-game panel > **Stop the game** ([Side panel in game](Side-Panel-In-Game)). Stop the game before you back up, restore or import a save.
- Games with a recommendation for your phone show it at the top of their ... page: one tap applies it.
- Game language follows the phone (Polish and Thai too, where the game has them) or can be set per game.

## Settings

- **Settings** (the gear at the top of Home) holds the defaults for every game: overlay, on-screen controls, resolution, frame rate cap, frame generation, button mapping and more.
- A game's own settings (its ... button: **Performance** and **Game** tabs) override them. See [Settings overview](Settings-Overview).

## Controls

On-screen controls with a layout editor, gamepad support, and phone motion are available; a text box appears for games that ask you to type. Fold-aware layouts switch live between the outer and inner screen of a foldable. Details: [Settings: controls](Settings-Controls).

## Starting from a frontend or home screen

Banana Native starts a game for emulator frontends such as ES-DE, Daijisho, Beacon and Pegasus, which list your `.nup` files and send the one you pick to the app. All files access must be on.

- A game that is ready to play starts at once, with no launcher screen. When you stop it, the frontend comes back.
- If something needs you (no pack, Prepare, shaders to update, file access), Home opens with the game; fix it, then start again from the frontend.
- A game's menu on Home has **Add to home screen**, which pins a shortcut with its name and tile.
- Frontends need no change of their own, but until one ships Banana Native in its emulator list you add it once by hand with the component `dev.bananative.android/dev.bananative.android.Launch`. Examples for ES-DE are in the project's FRONTENDS note in the repository (`android/FRONTENDS.md`).

## First starts can be slow

Some games build GPU pipelines the first time they see each shader, so the first start can sit on the loading screen and the first minutes can stutter. Ocarina of Time (`0400486024DEC000`) is the clearest case; each start is faster. See [Games](Games) and [Getting higher FPS](Getting-Higher-FPS). A hot phone makes it slower ([Heat and battery](Heat-and-Battery)).

## Frame generation

Lossless Scaling's frame generation is built in (2x, 3x, 4x, or a target of 60 / 90 / 120 fps), with some added latency. It needs your own `Lossless.dll`, put in `Download/` or picked in Settings > Frame generation. Not included and not provided by the project.

<!-- TODO screenshot: Home with a ready game card -->
