# Settings: Controls

How you play: the on-screen controls, a controller, rumble, button mapping and motion aim. Global defaults are on **Settings > Controls**; per-game values are on the game page's **Game** tab (Controls section) or in the in-game panel's **Controls** tab ([The in-game panel](Side-Panel-In-Game)).

<!-- TODO screenshot: Settings page, Controls card (On-screen controls, Rumble, Button mapping) -->

## On-screen controls

- **What it does:** the touch buttons over the game. **Auto** (default) draws nothing while a gamepad is connected, **On** always draws them, **Off** never. For every game.
- **When to toggle:** Off if the controls get in the way and you play with a controller.
- **Show / hide button** (a switch): the small button at the top centre that hides the on-screen controls. Default on. A long press on it opens the in-game panel.
- In the in-game panel (Controls tab): **Opacity**, **Size** (60 to 150 %), **Vibrate on press**, **Show the log over the game**, **Show / hide button (top centre)**, **Reset the controls**, **Edit the controls layout…** (drag a group to move it; a second finger resizes it, 50 to 200 %; the layout is kept per screen shape, so a foldable keeps one layout per screen) and **Position (this screen)** for the picture: Top, Centre, Bottom.

## Screen rotation
- **What it does:** **Landscape** (default) or **Automatic**, for the game screen.

## Controller: in-game panel
- **What it does:** how a controller opens the in-game panel: **Home, or hold - and + (half a second)** (default), **Home button only**, **Hold - and + only (half a second)**, or **Off**. In the panel the D-pad or left stick moves the focus, A presses, B closes, L and R switch tabs.
- **What to expect:** while the panel is open no controller input reaches the game, and every button the game holds is released when it opens.

## Players and controllers
Each connected gamepad is a player of its own. The in-game panel's **Controllers > Player 1** (shown while a gamepad is connected) picks which one is player 1, or **Automatic**; the choice is kept for every game.

## Rumble
- **What it does:** the game's vibration, on the controller or on the phone when there is no controller. Per game; Settings has the default.
- **When to toggle:** off if it is too much or drains the battery faster for you (battery effect not measured yet).
- **What to expect:** in the in-game panel it applies at once, including turning it on in a game that started with it off.
- **Default:** on.

## Button mapping
- **What it does:** one row per Switch button with the controller button that presses it. Tap a row, then press a button on the controller; it swaps with the one it replaces. **Save** stores it, **Reset to default** drops it. The on-screen controls are not changed.
- **When to toggle:** when your controller's layout differs from the game's expectation.
- **What to expect:** applies at the next start (the host reads it once). Settings has the default for every game; a game's own mapping wins.

## Xbox button label swap
- **What it does:** for Xbox-style layouts. **Off**, **Swap labels for X/Y and A/B** (the on-screen prompts are redrawn), or **Swap labels for X/Y, swap buttons A/B** (prompts for X/Y are redrawn and A and B are swapped by label).
- **Default:** Off. Per game with a **Default** choice that follows Settings.

## Motion sensitivity
- **What it does:** how far the phone's gyro turns the aim. Steps: 10, 20, 30, 40, 50, 60, 75, 100, 125, 150 %. 100 % is the real turn, as on a console. From the next start in Game settings; the in-game panel's slider applies at each step.
- **Default:** 50 %, because a held phone is heavier and less steady than a console.

## Sources

`MainActivity.java` (`buildSettings`), `GameSettings.java`, `TouchControls.java`, `PadPanel.java`, `PadMapUi.java`, `PadOrder.java`, `Prefs.java`, `android/README.md` (Controllers and players, In-game panel).
