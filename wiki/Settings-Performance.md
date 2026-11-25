# Settings: Performance

The settings that change how fast a game runs and how sharp it looks: frame rate cap, resolution, upscaler, dynamic resolution, GPU driver, frame generation and the Advanced rows. Most are on the **Performance** tab of a game's page; the same choices exist globally under **Settings > Game defaults**. For what to try in what order, see [Getting higher FPS](Getting-Higher-FPS).

<!-- TODO screenshot: a game page, Performance tab, Picture and Speed sections -->

Not every game shows every row. A row appears only when the game's profile has the lever behind it, so the rows below say which games have them. Performance numbers are in [Getting higher FPS](Getting-Higher-FPS) and on each game's page; this page does not give any that were not measured.

## Picture

### Resolution
- **What it does:** sets the size of the picture the game draws. Which form you see depends on the game:
  - Most games: the window size, from the list **Original** (the game's own size), **Phone 1440p**, **Phone 1080p**, **Phone 720p**, **Phone 540p**, **Phone 480p**. Games that do not have the lever show no row.
  - A game with **Resolution presets** (Ocarina of Time): choices that set the window and the 3D scene together, each with a second line saying what you get ("window 1280x720 · 3D about 960x540"). Ocarina of Time has **Quality**, **Balanced** and **Performance**.
  - A game with **Docked / Handheld** rows: those two choices.
  - A game with a render configuration (Fire Emblem: Fortune's Weave): **Resolution**, **Upscaling quality** (Native, Quality, Balanced, Performance, Ultra performance) and **Graphics quality** (Quality, Performance).
- **When to toggle:** lower it when the frame rate is too low and the GPU is the limit; raise it on a QHD screen if you have GPU time to spare.
- **What to expect:** a lower size is faster and softer. In a running game, some games change the window at once and the 3D picture at the next start; the row says which. Fire Emblem: Fortune's Weave shows "This change will apply on game restart." when a pick needs a larger render-target pool than the running game loaded.
- **Default:** Phone 1080p. A game's profile can give another default when you chose none, and a game's recommendation (see [Settings overview](Settings-Overview#recommended-for-this-phone)) comes before the global default. Fire Emblem: Fortune's Weave names Phone 1080p itself.

### Upscaler
- **What it does:** chooses what scales a smaller picture up to the screen. Where a game has its own neural upscaler (Donkey Kong Bananza, `040074A01BF12000`, uses one), this replaces it, because that network is slow on phones. The choices:
  - Nearest neighbor (fastest, blocky)
  - Bilinear (fastest, softest)
  - FSR 1 (sharp, fast; the list warns it is too bright on Qualcomm's driver in some games)
  - Native (sharpest, slowest): the game's own network
  - SGSR 1 (sharp, fast, one pass)
- **When to toggle:** if the picture is too soft, try SGSR 1 or FSR 1; if the frame rate is too low, try Bilinear or Nearest neighbor.
- **What to expect:** the row appears only for a game whose network the app can replace (it is learned the first time the game runs) and not at all for a game with Resolution presets unless Tester settings are on. Changes apply from the next frame in the in-game panel.
- **Default:** SGSR 1.
- **FSR sharpness** (shown only with FSR 1): Off, Softer, Soft, Default, Max. Default: Default.
- **A game on the DLSS stand-in** (Fire Emblem: Fortune's Weave, Star Fox) has its own Upscaler list instead: **GSR 2 (smooth images)**, **FSR 1 (sharp, fast)**, **Bilinear (fastest, softest)**, **FSR 3.1 (sharp, steady)**, with **Motion clarity** and a sharpness row where the mode has them. Fire Emblem: Fortune's Weave recommends FSR 3.1 on any phone.

### CRT filter
<!-- PEANUTS: the CRT filter (CrtFilter) -->
- **What it does:** draws the picture as a CRT shows it. The choices:
  - Off
  - Scanlines
  - CRT (scanlines, mask, vignette): scanlines, an aperture-grille mask and a light vignette
  - Curved CRT (as CRT, with a curved screen)
- **When to toggle:** for older games, and for newer 2D HD games drawn in a retro style, such as the Dragon Quest HD-2D remakes.
- **What to expect:** it takes the place of the Upscaler's scaling to the screen, and costs one full-screen pass. Settings > Graphics > Picture sets it for every game, the game's page sets it for one game, and the in-game panel's Display tab changes it from the next frame and keeps it for that game.
- **Default:** Off.

### Display
- **What it does:** how the picture sits on the screen: **Fit (keep aspect)** or **Stretch to screen**. The in-game panel adds a size slider and a position; see [The in-game panel](Side-Panel-In-Game).
- **When to toggle:** Stretch if you prefer to fill the screen over the right shape.
- **Default:** Fit.

### Aspect ratio
- **What it does:** shown only for a game with an aspect mod in its profile. **16:9 (as shipped)** or another ratio, marked "native" (cameras and menus laid out for it) or "3D view" (the 3D view follows it; HUD and menus keep their 16:9 layout). **Screen shape** (Ocarina of Time) lets the picture fill the window's own shape; it is read at the start.
- **Default:** 16:9 (as shipped).

## Speed

### Frame rate cap
- **What it does:** limits the game to a frame rate.
  - Most games: **Default**, **The game's own**, **60**, **30**, **20**, **15 fps**. The app writes the cap into the game's own frame limiter, so the game runs at that rate and at the right speed. A rate the game cannot hold is greyed.
  - A game whose speed follows its frame rate (Donkey Kong Bananza, Splatoon Raiders, Ocarina of Time, Pokopia): a frame limit instead, **60 / 45 / 40 / 30 / 20 fps**.
  - A game that picks its own frame rate: the row says "This game picks its own frame rate" and has no choices (it is learned from an earlier run).
- **When to toggle:** a lower cap leaves the GPU and CPU less to do; use it when the game cannot hold its rate anyway and swings between values.
- **What to expect:** 40 and 45 are not offered for most games because a game that steps its logic by its present interval runs at the wrong speed there; a stored 40 or 45 reads as 30. For Pokopia the cap only limits how often the picture is shown, not the game itself, and the page marks it **Experimental**: expect bugs such as the game running slower or faster.
- **Default:** follows Settings, then the game's profile (for example 60 or 30).

### Hold 60 fps
- Only for a game with fixed steps per frame (Mario Kart World, `0400C3F00006E000`). **Off:** the game drops to 30 fps when it cannot keep 60, as on the console. **On:** the game always runs its 60 fps mode, so it never drops to 30; the frame rate is less even and the game runs a little slower while under 60.
- **Default:** off (a recommendation can change that).

### Full game speed
- Only for a game whose profile makes its speed follow its measured frame rate. **On:** normal speed when the frame rate drops. **Off:** the game slows with the frame rate, as on the console. Also in the in-game panel, worded "Full game speed at low frame rates".
- **Default:** on.

### Dynamic resolution
- **What it does:** lets the render size move between a lowest value and 100 % of the window. Choices: **Off**, 80 %, 70 %, 60 %, 50 %, 40 %, 30 % (the lowest size).
- **When to toggle:** on a game that drops frames in heavy scenes; off if you want a steady sharp picture.
- **What to expect:** only Donkey Kong Bananza has this lever. Hidden for a game with Resolution presets unless Tester settings are on (Ocarina of Time's **Quality** is the game's own dynamic resolution).
- **Default:** 40 % to 100 %.

### GPU driver
- **What it does:** picks the Vulkan driver for this game: **System (the phone's own driver)**, or an installed one such as Turnip. First entry is **Default** (the one from Settings) or **Recommended (…)**. Applies at the next start. See [GPU drivers](GPU-Drivers).

## Advanced (testing and troubleshooting)

Folded on a phone; tap **Show**. These are for testers; the defaults are the tested ones.

| Row | What it does | Default |
|---|---|---|
| **GPU timing** | What the game's GPU timestamps measure. **Per segment** lets a game's dynamic resolution and frame pacing see the real GPU time; **Per submission** is the older behaviour. | Game default (from the profile) |
| **Instanced draws** | Repeated meshes drawn as one. | Off. A tester lost the GPU on Adreno 830 with Turnip in 1 to 2 of 5 starts with it or Classic render passes on (open). |
| **Classic render passes** | Less CPU for each pass on Qualcomm's driver. | Off (same open report) |
| **Background pipelines** | A pipeline the cache does not have is made on background threads: "A short pop-in, not a stutter". Off: "Made when first drawn: may stutter". | On |
| **RAM saver** | Keeps texture data in a file instead of compressed RAM. Needs gigabytes of free storage and writes to storage at each area load. For phones short of RAM. | Off |
| **Use fast cores** | Keeps busy threads off the small cores. Also in **Settings**. | Off: "not measured yet", and it may work against the app's own performance hints |
| **Pause on return** | Returning from the background shows the game paused until a tap. | On |
| **Extra environment** | One `KEY=VALUE` line per setting, for testers. | None |

## Frame generation

- **What it does:** adds generated frames between the game's frames, using Lossless Scaling's frame generation (LSFG). It needs a `Lossless.dll` you already own: the app looks in `Documents/Emulation/bios`, `Switch2` and `Download` on the phone's storage and copies the first it finds, or you pick one with **Pick Lossless.dll**. Without a usable file the overlay says LSFG is unavailable.
- **Settings:** on / off; **Target frame rate** (Fixed, 60, 90, 120); **Frames per game frame** (2x, 3x, 4x, with Fixed); **Motion estimation resolution** (25, 50, 75, 100 %; lower is faster). The in-game panel applies changes from the next frame.
- **What to expect:** the game's own frame is shown last, so the picture is one game frame behind. The app also makes no more generated frames than the screen's refresh leaves room for. A game that is limited by the GPU gets no free frames from it: how much it costs on a phone is **not measured yet** in this wiki.
- **Default:** off; motion estimation 50 %.

## Overlay

- **What it does:** a text block over the game: top left the build tag, top right the frame rate and frame time, a **throttle dot** (green, yellow or red), GPU time per frame, CPU clock caps, battery temperature and level, and memory. A master switch and one switch per item (Frame rate and frame time, Throttle dot, GPU time a frame, CPU clock caps, Battery temperature and level, Build tag, Memory).
- **When to toggle:** leave it on while you tune; turn it off for recordings or if it distracts. The text is drawn by the game's own present step rather than by Android views, to keep its cost low (what it costs is not measured here).
- **Default:** on, every item on.

The dot is red when the system reports thermal status moderate or worse, or severe headroom, or the main CPU cluster is capped below 65 % of its top clock; yellow for light status or a cap below 85 %; green otherwise; grey when nothing could be read. More in [Heat and battery](Heat-and-Battery).

## Resolution and the 3D scene (profiles with presets)

Games with Resolution presets can show **Advanced (for testers)** when Tester settings are on: **Window** and **3D scene** set apart. The 3D scene overlay is part of the game's files in the pack, so a pack made before the overlay existed shows **Needs Prepare again** on the row; see [First start and Prepare](First-Start-and-Prepare).

## Sources

`GameSettings.java`, `GameUpscaler.java`, `DlssTune.java`, `Prefs.java`, `Lsfg.java`, `Overlay.java`, `ResPresets.java`, `Profile.java`, `RenderLabels.java`, `res/values/strings.xml`, `dm/games/*/game.tsv`, `android/README.md`.
