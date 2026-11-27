# Settings: Controls

How you play: the on-screen controls, a controller, rumble, button mapping and motion aim. Global defaults are on **Settings > Controls**; per-game values are on the game page's **Game** tab (Controls section) or in the quick menu's **Controls** tab ([The in-game menus](Side-Panel-In-Game)). <!-- PEANUTS: in-game menus -->

<!-- TODO screenshot: Settings page, Controls card (On-screen controls, Rumble, Button mapping) -->

## On-screen controls

- **What it does:** the touch buttons over the game. **Auto** (default) draws nothing while a gamepad is connected, **On** always draws them, **Off** never. For every game.
- **When to toggle:** Off if the controls get in the way and you play with a controller.
- **Show / hide button** (a switch): the small button at the top centre that hides the on-screen controls. Default on. A long press on it opens the quick menu.
- In the quick menu (Controls tab): **Opacity**, **Size** (60 to 150 %), **Vibrate on press**, **Show the log over the game**, **Show / hide button (top centre)**, **Reset the controls**, **Edit the controls layout…** (drag a group to move it; a second finger resizes it, 50 to 200 %; the layout is kept per screen shape, so a foldable keeps one layout per screen) and **Position (this screen)** for the picture: Top, Centre, Bottom. <!-- PEANUTS: in-game menus -->

## Screen rotation
- **What it does:** **Landscape** (default) or **Automatic**, for the game screen.

## Controller: in-game panel
- **What it does:** how a controller opens the in-game menus: **Home, or hold - and + (half a second)** (default), **Home button only**, **Hold - and + only (half a second)**, or **Off**. Home pressed (or - and + held) opens the game menu, Home held the quick menu ([The in-game menus](Side-Panel-In-Game)). On the AYN Thor, whose Home goes to Android, the controller's Back does the same. In the menus the D-pad or left stick moves the focus, A presses, B goes back.
- **What to expect:** while a menu is open no controller input reaches the game, and every button the game holds is released when it opens. <!-- PEANUTS: in-game menus -->

## Quick menu button
- **What it does:** a button of your own that opens the quick menu at once, and closes either menu. A button games use asks first, since the game no longer gets it.
- **Default:** not set. <!-- PEANUTS: in-game menus -->

## Players and controllers
Each connected gamepad is a player of its own. The quick menu's **Controllers > Player 1** (shown while a gamepad is connected) picks which one is player 1, or **Automatic**; the choice is kept for every game. <!-- PEANUTS: in-game menus -->

## Rumble
- **What it does:** the game's vibration, on the controller or on the phone when there is no controller. Per game; Settings has the default.
- **When to toggle:** off if it is too much or drains the battery faster for you (battery effect not measured yet).
- **What to expect:** in the quick menu it applies at once, including turning it on in a game that started with it off. <!-- PEANUTS: in-game menus -->
- **Default:** on.

## Button mapping
- **What it does:** one row per Switch button with the controller button that presses it. Tap a row, then press a button on the controller; it swaps with the one it replaces. **Save** stores it, **Reset to default** drops it. The on-screen controls are not changed.
- **When to toggle:** when your controller's layout differs from the game's expectation.
- **What to expect:** applies at the next start (the host reads it once). Settings has the default for every game; a game's own mapping wins.

<!-- PEANUTS: the Xbox button label swap section is gone, the setting was removed (owner 2026-11-26) -->

## Motion sensitivity
- **What it does:** how far the phone's gyro turns the aim. Steps: 10, 20, 30, 40, 50, 60, 75, 100, 125, 150 %. 100 % is the real turn, as on a console. From the next start in Game settings; the quick menu's slider applies at each step. <!-- PEANUTS: in-game menus -->
- **Default:** 50 %, because a held phone is heavier and less steady than a console.

## Sources

`MainActivity.java` (`buildSettings`), `GameSettings.java`, `TouchControls.java`, `PadPanel.java`, `PadMapUi.java`, `PadOrder.java`, `Prefs.java`, `android/README.md` (Controllers and players, In-game panel).
