# The in-game panel

The in-game panel slides in from the right over a running game, with the game going on behind it. It holds the settings that can change while you play, plus Pause and Stop. Almost every change applies at once and is kept for the next start. The only exceptions are marked "next start".

<!-- TODO screenshot: the in-game panel open over a game, Performance tab -->

## Open and close it

| Action | Opens | Closes |
|---|---|---|
| Swipe in from the right edge | yes | |
| The phone's Back (button or edge swipe) | yes | yes |
| A long press on the on-screen controls' show / hide button (top centre) | yes | |
| A controller: Home, or hold - and + for half a second (Settings > Controller: in-game panel changes this) | yes | the controller's B |
| A tap beside the panel, or its X | | yes |

A hint at the right edge shows how, once after install. With a controller: D-pad or left stick moves the focus, left and right step a slider or radio group, A presses, B closes, L and R switch tabs; a yellow frame marks the focused row.

## The four tabs

### Performance
- **Frame rate cap**: the same choices as on the game page ([Settings: Performance](Settings-Performance#frame-rate-cap)), applied now. A game's frame limit list is 60, 45, 40, 30, 20 fps; other games: The game's own, 60, 30, 20, 15 fps.
- **Full game speed at low frame rates** (games whose speed follows their frame rate) and **Hold 60 fps: never drop to 30, no frame holds** (Mario Kart World).
- **Upscaler** (and **FSR sharpness**): for a game whose upscaler the app replaces; applies from the next frame. Hidden for a game with Resolution presets unless Settings > Help > Tester settings is on.
- **Dynamic resolution**: a slider from Off to 30 % (Donkey Kong Bananza).
- **Frame generation**: the on switch, target, multiplier and motion estimation resolution ([Settings: Performance](Settings-Performance#frame-generation)).
- The game's own **live** switches, if its profile has any.

The live lines on this tab (the frame rate now) refresh twice a second while it shows.

### Display
- **Resolution**: the same choice as on the game page. For some games the window changes at once and the 3D picture at the next start; a line says which. Fire Emblem: Fortune's Weave shows three groups: Resolution, Upscaling quality, Graphics quality.
- **Picture**: **Fit** or **Stretch**, **Size** 50 to 100 % in 5 % steps (the picture shrinks with its shape kept and black around it), and **Position (this screen)**: Top, Centre, Bottom.
- **Overlay**: the master switch and one switch per item.

### Controls
- **Controllers > Player 1** (while a gamepad is connected).
- **On-screen controls**: Auto / On / Off, opacity, size, vibration on press, the log, the show / hide button, **Reset the controls**, **Edit the controls layout…**.
- **Rumble (the game's vibration)**.
- **Motion sensitivity**: a slider, Slower to Faster.
- **Next start**: the game's own switches that need a restart, and **Button mapping** (a shortcut to the game's mapping; it applies at the next start).

See [Settings: Controls](Settings-Controls).

### General
- **Panel**: **Size** and **Text size**, each Small / Normal / Large, for every game, applied at once.
- **Cheats** (only for a game with cheats and with Game settings > Cheats on): one switch per cheat, grouped by section, live. See [Cheats](Cheats).
- **Pause the game**: freezes the game until you tap the screen (or press A or Start on a gamepad). While paused, the game's clock, graphics, threads and audio are suspended.
- **Pause when the game comes back from the background** (on by default): coming back from another app shows the game paused until a tap.
- **Stop the game**: asks "Stop the game?"; unsaved progress is lost, and the log stays for **Share log**. A game that has not saved keeps its progress only in memory, also while paused or in the background, so stopping it then loses it.
- **Back to the game**.

## Notes

- The panel's changes are the same settings as the game page's: a change in one shows in the other.
- If a game was started without live control (a test setting), the rows say the change applies at the next start. A normal start has it.

## Sources

`SidePanel.java`, `PadPanel.java`, `TouchControls.java`, `PanelLook.java`, `Overlay.java`, `Lsfg.java`, `GameUpscaler.java`, `android/README.md` (In-game panel, Pause).
