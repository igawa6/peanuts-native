# Pokémon Pokopia (`0400A3A01EE4C000`)

Supported. It plays at about 60 fps on both the system driver and Turnip in the opening and first areas. Two things are worth knowing before a long session: on the stock Qualcomm driver one effect can stall the game for a long time before the first Poké Ball, and in long sessions some players saw the frame rate fall over time.

<!-- TODO screenshot: Pokopia gameplay (the gallery picture shows a rendering bug) -->

## At a glance

| | |
|---|---|
| Status | Supported |
| Version | update 655360 (title screen says Ver. 2.0.1) |
| Frame rate | About 60 fps in the opening and first area (system driver and Turnip) |
| Driver | Turnip is the safer pick; see below |
| Game-specific settings | Link Play, Time of day |

## Settings that matter for this game

| Setting | What it does | When to toggle | What to expect | Default |
|---|---|---|---|---|
| Resolution (Game resolution) | The game's own dynamic resolution steps in 32x18 pixel levels from 960x540 up to at most 1920x1080 (the game's table has 34 sizes; its lists use the lower ones); Phone 720p lowers the top step to 1280x720. | If the frame rate is under target. | On a Samsung S25 Ultra on Turnip the GPU took about 36 ms a frame (23 fps) at the game's own sizes and 28 ms (31 fps) at Phone 720p. A scene with a heavy luminance pass is not helped by size. That test was before newer fixes, so your numbers may differ. | Phone 1080p |
| Time of day | Row on the Game tab: the game's day and night follow your phone's clock, set under Settings > System. | If the day and night cycle is wrong. | See [Game settings](Settings-Game). | Settings default |
| Link Play (Game settings > Game) | Experimental multiplayer: join a compatible PC host over the network. No Nintendo online services. See [Multiplayer](Multiplayer). | Only if you run a compatible PC host. | Applies at the next start. The PC host must use matching settings or it cannot be found. | Off |

<!-- TODO screenshot: Game settings page of Pokopia (Game tab, Link Play and Time of day) -->

## Drivers

- **Turnip:** gets past the Poké Ball and plays the first area. A Samsung S25 Ultra with Turnip ran about 23 fps at the game's own render size and 31 fps at Phone 720p (before the newer fixes).
- **Stock Qualcomm driver:** the earlier tests were stopped by one very large compute shader that the driver compiles for a long time right before the character comes out of a Poké Ball: one test frame took 99 seconds, then the game went on at 50-60 fps. If it seems frozen at that point, wait. This is listed as open in the notes. In a later test the user's run got past the Poké Ball.

See [GPU drivers](GPU-Drivers).

## Known issues

- **Frame rate falling over a long session** (players' logs): one log went from 59.7 fps at the start to 30 at 55 minutes. It is not heat and not memory. The cause is the number of small compute dispatches a frame growing (from about 11 to over 1,000), which come from particle effects. One host fix (fewer barriers) shipped and the notes say it was not enough; a second change (compute-to-compute barriers between the particle dispatches) is in the code, and the notes still list its phone check as pending, so whether long sessions now hold their frame rate is not measured yet. A game restart returns to the fast start. See [Getting higher FPS](Getting-Higher-FPS).
- **First visit stutter:** the first run through an area builds shaders; later visits are smoother.
- **Missing Pokémon textures:** a bug where a Pokémon had missing textures in one report was fixed in the code and not yet run on a phone, per the notes.
- **Memory:** the game keeps about 4.6 GB of its 8.9 GB heap touched in the opening on the author's 16 GB phone; the app places memory pools in parts to avoid being stopped by Android.
- **Fold screens:** an experimental option for a near-square inner screen (Fold 7: 2184x1968) widens the world camera's vertical view and was only verified on Turnip. It needs a setting in the game's Extra environment and a restart, does not correct menus or the HUD, and the stock Qualcomm driver showed incomplete terrain with it. Ask in an issue before relying on it.
