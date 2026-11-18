# Mario Kart World (`0400C3F00006E000`)

Supported. It plays on a 16 GB phone: the author ran a full cup of four races (18 minutes) without a crash on a Turnip driver. It needs a lot of memory and gets warm, so a phone cooler is recommended. On Qualcomm's own driver it shows spreading yellow and black pixels and is not usable; use Turnip.

## At a glance

| | |
|---|---|
| Status | Supported |
| Version | 1.8.0 |
| Memory | Needs a 16 GB phone (14 GiB reported); 12 GB phones run out of memory |
| Driver | Turnip. On the system driver one run showed spreading yellow and black pixels (open in the notes) |
| Frame rate | Not measured yet as a single figure. Title scene on the tested Honor phone: about 35 fps at 1080p and 46 fps at 720p (handheld mode), 36-37 fps at Original (docked). Races: 42-45 fps in one log, with the game switching between 60 and 30 by load; 52-60 fps on Turnip V39 with the GPU waits off (now the profile's setting). Not measured yet as one figure for a whole race |

## Settings that matter for this game

| Setting | What it does | When to toggle | What to expect | Default |
|---|---|---|---|---|
| Resolution | The game's 3D size follows the console mode and graphics scale: Phone 1080p picks handheld mode (1920x1080), Phone 720p picks 1280x720, Original and 1440p pick docked mode (2048x1152). Changes while the game runs. | Lower for speed. | In one title-scene test (system driver), 720p gave about 33 % more fps and 29 % less GPU time than 1080p. Memory hardly changes. | Phone 1080p |
| Frame rate cap | The game's own, 60, 30 or 20, held at full game speed. | To lower heat or steady the pace. | 30 and 20 keep race speed. 20 runs three steps per frame, which is beyond the game's own range; not phone-checked. | Game's own |
| Aspect ratio | 21:9, 4:3, 10:9, 1:1 and Screen shape. | On a wide or square screen. | Cameras and the 2D layer follow the shape. At 10:9 the character preview model on the character select may run past the right edge. A split-screen HUD is not moved to its half. | 16:9 |

<!-- TODO screenshot: Game settings page of Mario Kart World -->

## Memory and the texture saver

The game keeps about 4 to 6 GB of images. A memory saver (soft textures) keeps big compressed textures one level smaller, about a quarter of the bytes; the game's road can show purple if a texture the game copies from is missing, which the code guards against. In the 18-minute cup, the game's memory plus swap grew from 4.7 to 6.5 GB with the system moving the rest to swap. Some menu backgrounds were black once; not reproduced.

## Known issues

- **Frame rate in races:** the game's own controller switches between 60 and 30 by load (interval 1 and 2). One report (issue 181) of 10-20 fps on a Snapdragon 8 Gen 3 with Turnip T30 led to the GPU waits being switched off in the profile (34-39 against 52-60 fps on V39 with them on). Whether a cut costs the replay is open.
- **Heat:** races ran at 22-23 ms a frame on a warm phone, with CPU clocks capped by heat in some runs. See [Heat and battery](Heat-and-Battery).
- **Texture detail:** large terrain arrays start at a coarser level on phones that lack sparse residency for some compressed formats (so the ground can be blurrier near the camera).
- **Black rectangle under karts, glare or missing menu backgrounds after a Grand Prix race** (issue 215, Turnip): an image the GPU wrote was evicted and made again empty. A fix keeps those images cached; the notes say it is not yet checked on a phone.
- **Device lost when loading into a race:** the notes say this happened only with the GPU waits honoured, which the profile now turns off; the cause is open.
