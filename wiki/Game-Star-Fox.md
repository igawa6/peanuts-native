# Star Fox (`0400E88025B1A000`)

Star Fox runs on the phone. It boots and reaches its first scenes on both the system driver and Turnip, and one player has played it from start to finish and reported no problems. The project has no measurements for it yet (frame rate, memory, heat), so numbers on this page are "not measured yet". It needs a lot of memory: a phone with 16 GB is the safe choice.

<!-- TODO screenshot: Star Fox gameplay -->

## At a glance

| | |
|---|---|
| Version | update 131072 (the package the profile was made for) |
| Result so far | Boots and reaches its first scenes on the system driver and on Turnip. One player played it from start to finish with no complaints (their phone and driver are not recorded) |
| Frame rate | Not measured yet. One phone run showed about 19.5 to 20.5 ms of GPU time per frame at the game's default 3D size |
| Memory | About 8.5 GB in use on a Mac. Not measured on a phone yet. 12 GB or 16 GB of RAM is recommended |
| Driver | Both the system driver and Turnip start it. No comparison yet ([GPU drivers](GPU-Drivers)) |
| Package | A `.nup` of about 30 GB (base game plus update). It carries no name or icon, so the app shows the game's name from its own list and a placeholder version `v131072` |

## What to expect

- **First Prepare:** the game has about 2,900 shaders and a 14.5 GB archive. Let it finish with the phone charged ([First start and Prepare](First-Start-and-Prepare)).
- **Picture:** the game draws its 3D scene small and the app scales it up with a stand-in for the console's DLSS. At the default the 3D scene is 768x432 under load on a 1080p screen, so it looks soft. See the setting below.
- **Sound, video and camera features:** the game's movie and camera modules are supported well enough to start. Nothing about online play is tested.
- **Fonts:** menu text depends on your phone's fonts. A fix for missing text is in, and a phone check is still open.

## Settings that matter for this game

Both are in **Game settings**. Common settings are in [Settings overview](Settings-Overview).

| Setting | What it does | When to toggle | What to expect | Default |
|---|---|---|---|---|
| **Upscaler** | Picks how the small 3D picture is enlarged: the DLSS stand-in's own choices (GSR 2 and others, see [Settings Performance](Settings-Performance)) | If the picture looks too soft or too sharp | Cost and looks per choice are not measured yet | GSR 2 (smooth) |
| **3D scene** | Sets the size of the 3D picture before scaling, by passing the game its own resolution settings at the next start | If you have GPU time to spare, or the frame rate is too low | Higher choices cost frame rate: the GPU is already past its budget at the default | Game default |

3D scene sizes on a 1080p screen (from the game's own settings, not yet measured on a phone):

| Choice | 3D picture at full dynamic resolution | Under load |
|---|---|---|
| Game default | 960x540 | 768x432 (seen on the phone) |
| 60% | 1152x648 | 922x518 |
| 75% | 1440x810 | 1152x648 |
| 100% | 1920x1080 | 1536x864 |
| The game's 50%, no dynamic resolution | 960x540 | 960x540 |

The choice applies at the next start of the game. The cost of each choice on a phone is not measured yet.

<!-- TODO screenshot: Game settings page of Star Fox (Upscaler and 3D scene) -->

## Known issues

- Only one start-to-finish play is reported, without phone or driver details. Online modes are not tested.
- Memory pressure on 8 GB or 12 GB phones has not been tried.
- No Android-specific problems are listed yet. Please open an issue with the log if something goes wrong ([Troubleshooting](Troubleshooting)).

More games: [Games](Games). Phones that were tested: [Tested devices](Tested-Devices).
