# Splatoon Raiders (`0400380020056000`)

Splatoon Raiders starts on the phone and runs, but it is not playable yet: the frame rate is very low. It ships so that people can test it and help. If you try it, please report your phone, driver and what you saw ([Troubleshooting](Troubleshooting)).

<!-- TODO screenshot: Splatoon Raiders gameplay -->

## At a glance

| | |
|---|---|
| Version | update 65536 (over the base game) |
| Status | Boots and runs. Not playable yet (very low frame rate) |
| Frame rate | Very low. Exact figures per phone are not measured yet |
| Driver | System driver: a white scene was seen. Turnip: runs; no comparison yet ([GPU drivers](GPU-Drivers)) |
| Memory | The game maps a large memory region (8.5 GB peak on a Mac). Not measured on a phone, so 12 GB or 16 GB is the safe choice |

## What to expect

- **Slow:** the game is far below a playable frame rate. An earlier build ran its opening cutscene at about 2 fps on Turnip; a fix for the cause (an upscaler network that spilled registers on Adreno) is in, and the new figure is not measured.
- **Missing text:** the text of the Yes / No window has been seen missing. A fix is in and the phone check is still open.
- **Gyro aiming:** big movements work, steady aiming does not. Motion sensitivity and a dead zone were added, and the phone check is still open ([Settings Controls](Settings-Controls)).
- **First Prepare:** a normal long first Prepare ([First start and Prepare](First-Start-and-Prepare)).
- **On a Mac** the game plays the opening and the first level; that is the project's upstream side and says nothing about the phone.

## Settings that matter for this game

The game has no settings of its own beyond the common ones ([Settings overview](Settings-Overview)). Its resolution choices (Phone 1440p, 1080p and 720p, set in the game's resolution settings) change how large the picture is drawn: a smaller one is lighter on the GPU. Whether that gets the game to a playable rate is not measured yet.

<!-- TODO screenshot: Game settings page of Splatoon Raiders -->

## Known issues

- Very low frame rate, far from playable.
- White scene on the system driver.
- Yes / No window text missing (fix in, check open).
- Gyro aiming is rough.

More games: [Games](Games). Phones that were tested: [Tested devices](Tested-Devices).
