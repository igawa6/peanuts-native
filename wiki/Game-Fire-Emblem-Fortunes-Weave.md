# Fire Emblem: Fortune's Weave (`04007EF01F8AE000`)

Supported. It was played from the intro through many battles to the hub area Dagsion on a OnePlus Pad 3 with no significant issue, on both Turnip and the stock Qualcomm driver. It targets 30 fps. On Adreno 740 phones (for example the AYN Thor) pick a Turnip driver.

## At a glance

| | |
|---|---|
| Status | Supported |
| Version | update 131072 |
| Frame rate | 30 fps. A 60 fps cap exists in the in-game panel, but 30 is the profile's rate (see below) |
| Upscaler | GSR 2, FSR 1, Bilinear or FSR 3.1 (the game's profile recommends FSR 3.1 on every phone; the stand-in's own default is GSR 2, so FSR 3.1 applies until you pick another) |
| Driver | Turnip recommended on Adreno 740; Adreno 830 works on both |

## Tested devices

| Device | GPU | Driver | Result |
|---|---|---|---|
| OnePlus Pad 3 | Adreno 830 | Qualcomm 512.800.77 (stock) | Plays. Dungeon at 30 fps, characters correct |
| OnePlus Pad 3 | Adreno 830 | Turnip Gen8 V39 | Plays. Battle map at 30 fps |
| AYN Thor | Adreno 740 | Turnip "T30" (purple-turnip, Mesa 26.3), recommended | Plays. Dungeon at 29-30 fps |
| AYN Thor | Adreno 740 | Turnip Gen8 V39 | Plays. Dungeon at 30 fps |
| AYN Thor | Adreno 740 | Qualcomm 512.676 (stock) | Runs with faults: characters and some foliage dark, dungeon about 25 fps |
| AYN Thor | Adreno 740 | Qualcomm v863.1 | Not recommended: full-screen tile corruption now and then and memory growth |

See [GPU drivers](GPU-Drivers) for how to pick a driver. If your phone uses a Qualcomm driver older than 512.800 and you have installed a Turnip, the app recommends the Turnip.

## Settings that matter for this game

| Setting | What it does | When to toggle | What to expect | Default |
|---|---|---|---|---|
| Resolution (Game settings > Picture) | The size the game renders at: 1440p (heavy), 1152p, 1080p or 720p. | Lower it for speed. | Changes while the game runs. A note says "This change will apply on game restart." when the pick needs another render target pool than the game loaded. | 1080p |
| Upscaling quality | Native (heavy) 100 %, Quality 67 %, Balanced 59 %, Performance 50 %, Ultra performance 33 %. | Lower it for speed, raise it for a sharper picture. | Changes while the game runs. | Performance |
| Graphics quality | Quality or Performance. | Quality if the phone has headroom. | Changes shadow and detail level. | Performance |
| Upscaler | The stand-in for the console's DLSS: GSR 2 (smooth images), FSR 1 (sharp, fast), Bilinear (fastest, softest), FSR 3.1 (sharp, steady). | If you see ghosting or flicker. | FSR 3.1 keeps finer detail and flickers less in camera pans than GSR 2, but costs more GPU time: about 2 ms on Turnip and about 3 ms on a Pad 3's stock driver. GSR 2 additionally offers a Motion clarity slider. | Recommended: FSR 3.1 on every phone (applies until you choose; without a recommendation the stand-in starts on GSR 2) |
| Aspect ratio: 4:3 | A native 4:3 version of the game (cameras and menus laid out for 4:3). Needs Prepare again once. | On a squarish screen. | Fills a 4:3 screen, side bars on 16:9. Checked on the map, menus, combat, shops and dialogue; dungeon battles and the end-of-chapter report not yet checked. | 16:9 |
| Cheats (Game settings > Game options) | Gold, party stats, level and class EXP, switched on and off in the in-game panel. See [Cheats](Cheats). | If you want them. | Experimental; the profile says these were not yet phone-checked. | Off |

<!-- TODO screenshot: Game settings page of Fire Emblem (Resolution, Upscaling quality, Graphics quality, Upscaler) -->

## Frame rate and heat

The Pad 3 can run the game at 60 fps, but its GPU reached 93-96 degrees C within three minutes in the author's test, and a 50 Hz panel shows only about 40 frames a second, so the profile keeps 30. Heat in general: [Heat and battery](Heat-and-Battery).

## Memory

On the Pad 3 (16 GB) the dungeon takes about 3.7 GB. On phones with 12 GB or less the game uses a low-memory mode (smaller caches and a smaller reported memory total). On the AYN Thor (11 GB) it ran 0 of 4 long sessions into the low-memory killer with it, against 3 of 3 without. Put `DM_VK_LOW_RAM=0` or `=1` in a game's Extra environment to turn it off or on.

## Known issues

- On the stock Qualcomm 512.676 driver the dark characters are an open problem; use Turnip there.
- Movies are AV1 and are decoded in software. They ran at 30 fps on the tested phones (0 or 1 dropped frames in thousands).
- Cave torches miss some of their light at a distance in both aspects (likely the game's own light culling).
