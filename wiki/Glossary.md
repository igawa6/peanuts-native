# Glossary

Words the app and this wiki use, in plain terms.

| Word | Meaning |
|---|---|
| **Dump** | A copy of a game's files that you made from your own console. The app reads a dump folder (with `base/`, and `updates/` and `dlc/` if you have them) or a `.nup` file of one. NSP and XCI files do not work. |
| **`.nup` file** | One file that packs a dump. The app can use it as it is. |
| **Title ID** | The game's 16-character code, like `040074A01BF12000`. The wiki writes it after the game's name so you can match it to your dump. |
| **Update / version** | Each game profile is made for one update of the game (the "Update" column in [Games](Games)). Another version may still run, but nothing is promised. |
| **Prepare** | The one-time setup of a game on your phone or on a computer: it rewrites the game's code for Android and translates its graphics shaders. The result is called a pack. |
| **Pack** | What Prepare produces: about 250 to 400 MB per game. You can export it and import it on another phone. |
| **Shader** | A small program the GPU runs to draw things. Games have thousands; Prepare translates them for your phone. |
| **GPU driver** | The software that lets the app talk to the phone's graphics chip. Your phone's own is the **system driver** (Qualcomm). **Turnip** is a free alternative that can be faster or steadier in some games. See [GPU drivers](GPU-Drivers). |
| **Adreno** | The graphics chip family in Snapdragon phones. |
| **Frame rate / fps** | How many pictures per second the game draws. 30 is the Switch's usual speed; 60 is smoother and uses more power. |
| **Resolution / 3D scene** | How large the game draws its picture before the app scales it to your screen. Smaller is lighter on the GPU but softer. |
| **Upscaler** | The method that enlarges the picture to your screen (for example FSR or GSR 2). See [Settings Performance](Settings-Performance). |
| **Frame generation** | Makes extra pictures between real ones, for smoother motion. Needs your own `Lossless.dll` ([Settings Performance](Settings-Performance)). It adds delay. |
| **Cheat file** | A list of game tweaks in a text file (gold, stats). Only some games have one ([Cheats](Cheats)). |
| **Side panel** | The panel you open while playing to change settings without leaving the game ([Side panel in game](Side-Panel-In-Game)). |
| **Readiness check** | **Settings** > **Readiness check**: the app tests whether your phone can run your games. |
| **Supported / Experimental** | How well a game runs. Supported: played through real sessions on the author's phones. Experimental: it starts but is little tested. See [Games](Games). |
| **Issue** | A report on the project's [issue tracker](https://git.eden-emu.dev/moceure/BananaNative/issues). Reports with a log are the most useful. |
