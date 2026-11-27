# The in-game menus
<!-- PEANUTS begin: the in-game menus (#678-#680), in place of upstream's one in-game panel -->

Two menus open over a running game, as on a console, and the game goes on behind both:

- **The game menu**, at the left: this session. The game's cover, name, version and how long you have played, then Back to the game, Pause the game, Quick menu, Back up the save, Button mapping, Share log, and Stop the game at the bottom.
- **The quick menu**, at the right: the settings that can change while you play, on tabs down a rail at its left. Almost every change applies at once and is kept for the next start. The only exceptions are marked "next start".

You can have one menu instead (see [Layout](#layout)).

<!-- TODO screenshot: the game menu open over a game; the quick menu, Performance tab -->

## Open and close them

| Action | Opens | Closes |
|---|---|---|
| A controller's Home pressed, or - and + held for half a second | the game menu | either menu |
| A controller's Home held (half a second) | the quick menu | |
| On the AYN Thor, the controller's Back (its Home goes to Android): pressed, held | the game menu, the quick menu | either menu |
| The phone's Back (button or edge swipe) | the game menu | one step back |
| Swipe in from the left edge | the game menu | |
| Swipe in from the right edge | the quick menu | |
| A long press on the on-screen controls' show / hide button (top centre) | the quick menu | |
| The game menu's **Quick menu** row | the quick menu | |
| Settings > Controls > **Quick menu button**: a button of your own | the quick menu | either menu |
| A tap beside a menu | | yes |

Settings > Controls > **Controller: in-game panel** decides whether Home and - + open anything. With a controller: the D-pad or left stick moves the focus, A presses, B goes back (from the quick menu to the game menu when that opened it, else to the game). In the quick menu, up and down on the rail switch the tab, right goes into the tab's rows and left comes back to the rail. A yellow frame marks the focused row.

While Preparing shaders shows, during a shader preparation and while the game's controller screen asks for controllers, Back does what it did before: the controller screen and a preparation are cancelled, and Preparing shaders keeps its own Close.

## The game menu

- **Back to the game** closes the menu.
- **Pause the game**: freezes the game until you tap the screen (or press A or Start on a gamepad). While paused, the game's clock, graphics, threads and audio are suspended.
- **Quick menu** opens the quick menu; B there comes back to this row.
- **Back up the save**: copies the save the game runs on while it runs, to where **Back up saves** puts its copies ([Saves and storage](Saves-and-Storage)). The row shows when the newest copy was made. If the game saves during the copy, the copy is tried again; the save itself is only read.
- **Button mapping**: the game's mapping; it applies at the next start.
- **Share log**: this run's log so far.
- **Stop the game**: asks once in the menu, **Keep playing** first. Unsaved progress is lost, and the log stays for **Share log**. A game that has not saved keeps its progress only in memory, also while paused or in the background, so stopping it then loses it.

## The quick menu's tabs

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

### Cheats
Only for a game with cheats and with Game settings > Cheats on: one switch per cheat, grouped by section, live. **X All off** in the tab's header turns them all off. See [Cheats](Cheats).

### Mods
Only for a game with mods: the game's mods, as on its Mods & Cheats page.

Settings > App > **Mods tab** (and a game's own Mods & Cheats page) can hide the Cheats and Mods tabs.

### Controls
- **Controllers > Player 1** (while a gamepad is connected).
- **On-screen controls**: Auto / On / Off, opacity, size, vibration on press, the log, the show / hide button, **Reset the controls**, **Edit the controls layout…**.
- **Rumble (the game's vibration)**.
- **Motion sensitivity**: a slider, Slower to Faster.
- **Next start**: the game's own switches that need a restart. Button mapping is in the game menu.

See [Settings: Controls](Settings-Controls).

### Menu settings
The gear at the bottom of the rail.
- **Menu**: **Size** and **Text size**, each Small / Normal / Large, for every game, applied at once; **Vibrate on each step** (on) and **Menu sounds** (off).
- **This game**: **Pause when the game comes back from the background** (on by default): coming back from another app shows the game paused until a tap.
- **Layout**: see below.

## Layout

The app asks once which layout you want (twice if you pick **Later** the first time); **Menu settings > Layout** changes it later.

- **Two menus** (the default): the game menu at the left and the quick menu at the right, as above.
- **One menu at the right**: one menu over the game whose rail starts with **Game** (the game menu's rows) above the quick menu's tabs. Home, Back and - + open it on Game, held on the tab shown last; L and R step over Game and the tabs; B closes it. The left edge is left to the game.
- **Bottom screen** (a device with a second screen, such as the AYN Thor): the same one menu on the bottom screen, sliding up over what that screen shows, while the game stays in full view on top, dimmed a little. A swipe up from the bottom screen's bottom edge also opens it on Game. Touching the top screen closes it. Without a second screen at the time, the menu opens at the right instead.

## Notes

- The quick menu's changes are the same settings as the game page's: a change in one shows in the other.
- If a game was started without live control (a test setting), the rows say the change applies at the next start. A normal start has it.

## Sources

`InGamePanel.java`, `dev/peanuts/ingame/` (`GameMenu`, `Rail`, `GameRows`, `BottomScreen`, `BottomMenu`), `SidePanel.java`, `PadPanel.java`, `TouchControls.java`, `PanelLook.java`, `Overlay.java`, `Lsfg.java`, `GameUpscaler.java`, `peanuts/UPSTREAM-EDITS.md` (In-game menus).
<!-- PEANUTS end -->
