# Settings overview

Banana Native has settings in three places: the **Settings** page on the home screen, each game's own page (the **...** button on its card, or a long press), and the **in-game panel** that slides over a running game. This page says which place holds what, and which value wins when the same setting is set in more than one.

<!-- TODO screenshot: Home screen with the Settings button and a game card with its ... button -->

## The three places

| Place | How to open it | What it holds | When a change applies |
|---|---|---|---|
| **Settings** (home screen) | the settings button on Home | defaults for every game, plus app-wide items (GPU driver, folders, users, help) | at the next game start |
| **Game page** | the **...** on a game's card, or a long press on it | that one game's values. Tabs: **Performance**, **Game**, **Saves**, **Storage** | at the next game start (a few rows are marked as live) |
| **In-game panel** | swipe in from the right edge, the phone's Back, a long press on the on-screen controls' show / hide button, or a controller (Home, or hold - and +) | the settings that can change while the game runs | at once, and kept for the next start |

Details: [Settings: Performance](Settings-Performance) · [Settings: Game](Settings-Game) · [Settings: Controls](Settings-Controls) · [Settings: Advanced](Settings-Advanced) · [The in-game panel](Side-Panel-In-Game) · [Cheats](Cheats).

## Which value wins

For a setting that exists both globally and per game, the app looks in this order and takes the first value it finds:

1. The value you set for this game (game page or in-game panel).
2. The value the game's profile **recommends for your phone** (see below), when there is one.
3. The default you set in **Settings** on the home screen.
4. The built-in default.

A game page row that follows the lower levels says **Default** (or **Recommended**). A short line on the game page says "For this game only. Default follows Settings."

An `env.txt` file or a game's own environment lines (Advanced > Extra environment) are for testers and win over all of the above. A player does not need them.

## Recommended for this phone

Some games have a **Recommended for this phone** card at the top of the Performance tab. The game's profile can recommend a resolution preset, a GPU driver, an upscaler, a frame rate cap and so on, chosen by what your phone's GPU driver reports (never by a phone's name). The card has two choices:

- **Recommended**: the values the profile gives for your phone. If you changed some of them, the card says "You changed N of these", and picking **Recommended** (after a confirmation) puts those back and keeps your other settings.
- **Custom**: keeps your own values. It changes nothing by itself.

Each row that has a recommendation shows a **Recommended** tag, or **Changed** with a line "Recommended: …" under the value. A game without a recommendation has a **Reset to defaults** button in its place; it makes the game follow the global settings again (the Extra environment lines are kept).

Which games recommend what is on each game's page in [Games](Games).

## What is on the Settings page

| Section | Row | What it is |
|---|---|---|
| Updates | **Check for updates** | shows the version; see [Updates](Updates) |
| Updates | **Player wiki** | opens this wiki in the browser; the same button is in Home's header, left of Settings |
| Display | **Overlay** | the text over the game: [Settings: Performance](Settings-Performance#overlay) |
| Display | **Screen rotation** | [Settings: Controls](Settings-Controls) |
| Controls | **On-screen controls**, **Show / hide button**, **Controller: in-game panel**, **Rumble**, **Xbox button label swap**, **Button mapping** | [Settings: Controls](Settings-Controls) |
| Performance and picture | **Resolution**, **Frame rate cap**, **Upscaler**, **FSR sharpness**, **CRT filter** <!-- PEANUTS: CRT filter -->, **Dynamic resolution**, **Frame generation**, **Use fast cores** | [Settings: Performance](Settings-Performance) |
| System | **GPU driver** | [GPU drivers](GPU-Drivers) |
| | **Time of day** | [Settings: Game](Settings-Game#time-of-day) |
| | **Advanced** | every switch with a search box: [Settings: Advanced](Settings-Advanced) |
| | **Users**, **Folders**, **Storage**, **Keep the screen on while preparing** | [Saves and storage](Saves-and-Storage), [First start and Prepare](First-Start-and-Prepare) |
| Help | **Readiness check**, **Graphics self-test**, **Share log**, **Tester settings** | [Troubleshooting](Troubleshooting) |
| About | build tag, the games this build includes | the list tells you whether your game is in this app |

The text under the Performance and picture rows says: "A game's own settings (tap its tile) override these."

### Tester settings

**Settings > Help > Tester settings** is off by default. For a game that offers **Resolution** choices (such as Ocarina of Time), it shows or hides the rows made for testing: **Advanced** resolution (window and 3D scene set apart), **Upscaler** and **Dynamic resolution**. Values you set while it was on keep applying while the rows are hidden.

## Sources

`GameSettings.java`, `MainActivity.java` (`buildSettings`), `Prefs.java`, `Recommend.java`, `android/README.md` (Recommended settings).
