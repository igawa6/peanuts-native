# Control over adb

A way to drive the app from a computer, for tests and scripts: one command per `am start`, and one file that
says what the app is doing: reading the state, moving between pages, building, preparing, playing, a few
safe settings, the running game's frame rate cap, console mode and second screen, and a game's companion. Two commands delete or replace something, and only with
`-e confirm true`: `reimport` makes a game's `.nup` again, and `companion` with `do=remove` deletes a
companion package. Nothing else here deletes or replaces anything.

What was checked on an AYN Thor (2026-11-16): `status`, `list`, `scan`, `nav` (home with a filter and Find,
settings with a section, a game's page with a tab), every key of `set` with a value and back (for every
game and for one), `companion` (list, install, install and update of one that is the newest, remove, and
its refusals), `play`, `game` (`ctl`, `stop`), and the refusals of `build` (a game that is built),
`reimport` (dumps not found), `screens_off` and `job` (nothing builds), a command that needs the screen
while a game runs, and an unknown command. `build` and `job` pause and resume were run on an earlier
version. Not run on a device: `prepare`, a `reimport` that goes through, `screens_off` during a build,
`job` `retry` and `try_anyway`. Which commands and arguments are taken or refused, and the JSON that is
written, are covered by PC tests (`peanuts/tests/test_ctl.py`).

```
adb shell am start -n <package>/dev.peanuts.ctl.Command -e cmd <name> [-e <argument> <value> ...] [-e id <token>]
adb shell cat /sdcard/Android/data/<package>/files/ctl/status.json
```

`<package>` is `dev.nxnative.android.peanuts` for Peanuts Native. Arguments are plain `-e` strings; `--es`,
`--ei` and `--ez` work too (every value is read as its text). `on`/`off` arguments take `true`, `1`, `on`,
`yes` or `false`, `0`, `off`, `no`.

## The gate

`dev.peanuts.ctl.Command` is an activity that draws nothing. The manifest guards it with
`android.permission.DUMP`. Android checks that permission against whoever starts the activity: adb's shell
holds it, an ordinary app cannot get it. So `am start` from adb works, on the release build too, with no
switch in the app, and another app's attempt ends in a `SecurityException`. Whoever has adb's powers on the
device (root, a shell helper the user set up, an app the user granted `DUMP` over adb) gets in as adb does.

The launcher's own screen is exported, as every launcher is. It takes no command from an intent: a command
that needs the screen waits inside the app while the launcher is brought to the front with a plain intent,
and the launcher picks it up from there.

## Answers

Every command writes its outcome into `status.json` as `last_result` and logs one line (`adb logcat -s
peanuts-ctl`). Pass `-e id <token>` and wait for `last_result.id` to be your token with `pending` false:

| Field | Meaning |
|---|---|
| `id`, `cmd`, `time` | your token, the command, when it came in (ms) |
| `ok` | true or false; null while `pending` is true (the launcher is coming to the front, or Prepare is checking the dump) |
| `error`, `message` | a short code and a sentence when `ok` is false; `message` alone can also say more about a success |
| `data` | what the command has to tell (the pack a game started with, the value stored) |

`results` holds the last eight, the newest first. Codes every command can give: `bad_args`,
`unknown_command`, `needs_ui` (the launcher did not come to the front), `game_running` (a game runs and the
command needs the launcher's screen: stop the game, or add `-e force true`, which may close the game's
screen), `internal`.

`ok` means the app took the command, as a tap would be taken. How a build or a game then goes is in the
rest of the file.

## Commands

| Command | Example | Does |
|---|---|---|
| `status` | `-e cmd status` | Writes the file again, the library rows read anew |
| `list` | `-e cmd list` | The same, and the library rows alone into `ctl/library.json` |
| `scan` | `-e cmd scan` | Settings › Library › Rescan. `screen.home.scanning` goes false when it has ended |
| `nav` | `-e cmd nav -e to home -e filter ready -e find zelda` | Home, with the filter (`all`, `building`, `ready`, `build`, `not_in_app`) and the Find text (`-e find_clear true` empties it) |
| | `-e cmd nav -e to settings -e section Graphics` | Settings with that section showing (`Library`, `Console`, `Graphics`, `Controls`, `Screens`, `Users`, `App`, `Help`, `Developer`, `About`), or scrolled to one of its groups (`-e section Picture`). The old long page's headings still lead to the section that has their rows now (`System` to `Console`, `Performance and picture` and `Game defaults` to `Graphics`, `Display` to the group of Screens). `data.section` says which shows |
| | `-e cmd nav -e to game -e title_id 0100F2C0115B6000 -e tab storage` | A listed game's page and tab (`performance`, `game`, `saves`, `storage`) |
| `build` | `-e cmd build -e title_id 0100F2C0115B6000` | The game sheet's Build with the files the sheet would choose: the base, the update chosen earlier (else the newest found), the DLC found, the companion as its switch stands. First build only. Refused with `wrong_version` when the sheet would ask "Build anyway?". While another game builds or the build queue waits, the game goes into the queue: `ok`, with `data.queue_place` and `data.queue_total` (its place in the batch, "2 of 5"); `busy` only for the game that builds now |
| `reimport` | `-e cmd reimport -e title_id 0100F2C0115B6000 -e confirm true` | The game sheet's Import again: the game's `.nup` is made again from its dumps (the base, the update chosen earlier or else the newest found, the DLC found now), then the game is built. The new file is written beside the old one and replaces it only when the import is complete. See below |
| `import_pack` | `-e cmd import_pack -e title_id 0100F2C0115B6000 -e path /sdcard/Download/banana-pack-0100F2C0115B6000-0.zip` | The game sheet's Import pack… for a game that is not built yet, with a path instead of the picker: a `.zip` made by Export pack…, or a pack folder. It never replaces a pack. See below |
| `prepare` | `-e cmd prepare -e title_id 0100F2C0115B6000` | Storage › Prepare again for a listed game, without its question. A dialog the app answers with is the refusal (`refused`, its text in `message`). While a build runs or the build queue waits, it goes into the queue as `build` does |
| `job` | `-e cmd job -e do pause` (or `stop`) | The button of the card that shows: Stop on the build card, Pause on the Prepare card. For the build queue `stop` stops the game that builds and the queue goes on with the next; `pause` pauses the queue (the game goes on first at `resume`) |
| | `-e cmd job -e do resume` (or `retry`) | Resume or Retry: a paused build queue goes on, a stopped build builds on; a Prepare job's own button is pressed |
| | `-e cmd job -e do try_anyway -e title_id 0100F2C0115B6000` | The answer "yes" to "Build anyway?" or to the Prepare card's "Try anyway": the game may not start or may draw wrongly |
| `play` | `-e cmd play -e title_id 0100F2C0115B6000` | Home's Play: the same checks, the build with mods when one is ready. A dialog Play would show is the refusal: its text comes back and the dialog is closed |
| `game` | `-e cmd game -e do stop -e confirm true` | Ends the running game as its panel's Stop does. Unsaved progress is lost |
| | `-e cmd game -e do ctl -e line "A~0.5"` | One line into the running game's live control file (the lines of `android/TESTING.md`, "Live control"). Its answer is in the run's log. `-e line` without `do` is the same |
| | `-e cmd game -e do fps_cap -e value 30` | The running game's frame rate cap, as the stats page on the second screen sets it: a rate (`30`), `own` (Game owned) or `default`. See below |
| | `-e cmd game -e do console_mode -e value handheld` | The running game's console mode, `docked` or `handheld`, as the stats page sets it |
| | `-e cmd game -e do second_screen -e value off` | Hides or shows the second screen's companion or game page, as a four-finger tap on the game does: `toggle`, `on` or `off` |
| | `-e cmd game -e do controls` | The running game's console mode, frame rate cap, the caps it offers and whether the second screen shows |
| `screens_off` | `-e cmd screens_off -e on true` | The black cover while a build runs; `-e on false` lifts it |
| `set` | `-e cmd set -e key fps_cap -e value 30 -e title_id 0100F2C0115B6000` | One of the settings below, for every game or, with `title_id`, for one |
| `share_build_log` | `-e cmd share_build_log -e title_id 0100F2C0115B6000` | Share build log without the sheet: the game's `.zip` into `ctl/build-logs/` (`adb pull` it). `data`: `file` (relative to `ctl/`), `path`, `bytes`, `files`, `jobs`, `mod_jobs`, `cut`. Pending while it is written; `no_build_log` when the game has no build log and no job folder. Bundles older than a day are deleted when the next one is made |
| `companion` | `-e cmd companion -e do list` | The catalog against the library: one row a game in `data.companions` (below). With `title_id`, that game alone |
| | `-e cmd companion -e do install -e title_id 0100F2C0115B6000` | The catalog's newest companion for the game, downloaded and checked as the catalog screen does. One that is installed and older is replaced |
| | `-e cmd companion -e do update -e title_id 0100F2C0115B6000` | The same, refused (`not_installed`) when the game has none |
| | `-e cmd companion -e do install -e path /sdcard/Download/010015100B514000-SuperMarioWonderDS-1.0.1.dsmod.zip` | Settings › Dual-screen companions › Install from file with a path instead of the picker: a `.dsmod.zip` on the device, no network. The file names its game; `title_id` with it refuses another game's file (`wrong_title`). An older version than the installed one wants `-e confirm true`. See below |
| | `-e cmd companion -e do remove -e title_id 0100F2C0115B6000 -e confirm true` | Deletes the game's companion package. The game and its saves stay |

`job` takes an optional `title_id`: the command is refused (`no_job`) when the job is another game's.

### `game`: the running game's controls

`fps_cap`, `console_mode`, `second_screen` and `controls` work on the game that runs now, through the same
calls as the stats page on the second screen and the in-game panel. The game runs in a process of its own,
so the app asks it and waits for its answer: `last_result` is `pending` for a moment, then the answer. A
`title_id` is optional; when it is given and another game runs, the command is refused with `other_game`.
Full lines, with the dev build's package as an example:

```
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do fps_cap -e value 30 -e id x1
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do fps_cap -e value own -e id x2
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do fps_cap -e value default -e id x3
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do console_mode -e value docked -e id x4
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do second_screen -e value toggle -e id x5
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd game -e do controls -e id x6
```

`fps_cap` takes only a cap the game offers. A cap the stats page shows greyed out, or one it does not show,
is refused with `not_offered`; `message` and `data.offered` (each `value` and `label`) list the ones it
offers. A rate above 60 that the game has but the display cannot show (its highest refresh rate is lower) is
refused too, and `message` says so ("This screen shows up to 60 fps."); a saved one stays saved and runs at the
display's rate. For a game with the frame limit list some picks apply only at the next start (Game owned, or
leaving it, and the experimental rates above 60), as the stats page and the in-game panel say:
`data.effective` is then `next_start` and `data.running` is the cap the game runs at until then. `default`
removes the game's own choice: the default cap is applied now when it is one of the menu's, and the game
keeps no choice of its own. For a game that started Game owned before 0.1.8 (opt-in display pacing,
`fps_owned_default`) the default is 60 fps, or 30 where the game has no 60. `data` of `fps_cap`: `cap`, `cap_label`, `running`, `running_label`,
`effective`, and `stored` (`default`) after `default`. For example:

```
"ok": true, "message": "The frame rate cap is 30 fps now.",
"data": {"title_id": "0100F2C0115B6000", "cap": "30", "cap_label": "30 fps", "running": "30", "running_label": "30 fps", "effective": "now"}
```

`console_mode` applies now when the game can change it while it runs, else at its next start
(`data.effective`). `second_screen` answers the state after it in `data.second_screen` (`on` or `off`) and
`data.changed` (false when `on` or `off` asked for what was there already). `controls` answers
`console_mode` (`docked`, `handheld`, or `fixed` when the game's Resolution sets it), `cap`, `cap_label`,
`running`, `running_label`, `caps` (every cap of the menu: `value`, `label`, `offered`) and
`second_screen` (`on`, `off`, or null when there is no second display or the game is not in front).

| Code | Meaning |
|---|---|
| `not_running` | No game runs |
| `other_game` | `title_id` names another game than the one that runs |
| `not_offered` | `fps_cap`: the game does not offer that cap. `data.offered` lists the ones it does |
| `not_for_this_game` | `console_mode`: the game's Resolution sets the console mode |
| `no_second_screen` | `second_screen`: there is no second display, or the game is not in front |
| `not_ready` | The game's screen is still starting and has no controls yet |
| `no_answer` | The game did not answer within 4 s (its screen is starting or stopping) |

### `companion`

It reads the catalog from the network and downloads a package, so its answer comes later: `last_result` is
`pending` until the work has ended (a list takes a second or two, an install as long as the download).
Nothing is installed that the catalog screen would not install: the package's digest must match the
catalog's, and its runtime must not be newer than the app's. `install` with `path` takes a file instead (below).

`data.companions` of a list: `title_id`, `name`, `package`, `installed` (a version, `?` when the package
does not record one, null), `catalog` (the newest version, null when the catalog has none for the game),
`state` (`installed`, `update`, `not_installed`, `none`) and `built` (a companion goes beside the game's
pack: without a pack nothing can be installed). `data` of an install or update: `package`, `version`,
`was` (the version before, null for a first install), `changed` (false when it was the newest already).
After a change the library rows in `status.json` are read anew in the same write as the answer.

| Code | Meaning |
|---|---|
| `needs_confirm` | `remove` without `-e confirm true` |
| `unknown_title` | The game is not in the library |
| `not_built` | The game has no pack yet |
| `no_companion` | The catalog has no companion for the game |
| `not_installed` | `remove` or `update` of a game that has none |
| `failed` | The app's installer refused or could not finish: `message` has its reason (a game runs, no network, the download does not match the catalog, the package needs a newer app) |

`install` with `path` reads the file on the device (the app's All files access) and installs it with the same
installer as the catalog's: the same checks of the package, the runtime and a running game, and the swap that
keeps the version it replaces under Versions. A file has no published checksum, so instead every entry of the
zip and the module library's checksum in its manifest are checked, and the companion shows as unverified. It
goes beside the game's pack; for a game that is not in the library yet, where its build will look (the
app's `packs/peanuts`), and `message` says so. `data`: `title_id`, `name` (the companion's), `package`,
`version`, `was`, `changed` (false when that version is installed already), `in_library`, `built`.

| Code | Meaning |
|---|---|
| `no_file` | `path` is not a file the app can read |
| `not_companion` | The file is not a dual-screen companion (not a zip, or no package.json and dualscreen/manifest.json, or it names no game) |
| `damaged` | The zip is damaged (a bad entry, cut off, a module library that does not match the manifest's checksum, no version) |
| `wrong_title` | The file is another game's companion than `title_id` |
| `needs_newer_app` | The companion needs a newer companion runtime than this app has |
| `needs_confirm` | The file is an older version than the installed one: add `-e confirm true` |
| `failed` | The installer could not finish: `message` has its reason (a game runs, no room) |

### `reimport`

It replaces a file, so it wants `-e confirm true`, as `game` with `do=stop` does. Without it the command
is refused with `needs_confirm`; `message` is then the question the sheet asks (what is deleted, what is
kept, what was found) and `data` says what would be used, so a script can look before it agrees.

`data`, whatever the answer: `nup` (the file to replace), `old_bytes`, `base`, `update`, `update_version`,
`dlc` (a list of files), `need_bytes` (the dumps' sizes together: what the new file may need), `free_bytes`
(free where the `.nup` is, null when not known), `delete_first`.

| Code | Meaning |
|---|---|
| `not_a_nup` | The game's base is not a `.nup` (a dump folder, or a game that is not imported yet: `build` imports it) |
| `dumps_missing` | A dump the old `.nup` was made from was not found: its base, an update at least as new as the one inside it, or one of its DLC. `message` names what is missing and the folders that were read. Nothing is changed |
| `low_space` | The new file does not fit beside the old one. `message` says how much is needed. When deleting the old file first would make room, `-e delete_first true` is that second choice: if the import then fails or is stopped, the game has no `.nup` and cannot be played until a build has made one |
| `needs_confirm` | See above |
| `busy`, `unknown_title`, `not_in_app`, `no_keys`, `no_firmware`, `refused` | As `build` |

`delete_first` is used only when it is needed: with room for both files the old one is kept until the new
one is complete, whatever the argument says (`data.delete_first` tells which it was). `ok` means the import
started; `build` shows its progress, and a pack made from the old file is built again (its shaders are
found anew), never answered with "Already prepared".

A build that stops because shaders come with DLC that was not imported ends with `build.code`
`addon_missing` (and `job.cause` `addon_not_imported`): the app no longer goes on by itself there. Answer
with `reimport`, or with `job` `do=try_anyway` to build without those shaders.

### `import_pack`

The pack goes where the game's next Build puts its own (its Build destination, else the app's pack folder for
the game), with the checks and dialogs of the sheet: its pack.tsv must name the game; against a dump folder or
`.nup` its code must be of that file's build, and the game is then ready to play; for a game Build still
imports from an NSP or XCI its version must be the one Build uses (the update chosen earlier, else the newest
found, else the base), and Build then makes the `.nup` and takes the pack. `last_result` is `pending` while the
pack is copied and checked; then `ok` with what the "Pack imported" dialog says, or `refused` with the
sentence of "Pack not imported". Both dialogs stay on the screen, as after the sheet's button.

| Code | Meaning |
|---|---|
| `pack_there` | The game's pack folder holds a pack already. This command replaces none: the sheet's Import pack… does, after the user picked the file |
| `built` | The game is built: its page's Storage tab has Import pack… |
| `no_file` | `path` is neither a file nor a folder |
| `no_base`, `file_gone` | No base game was found, or its file is gone (scan first) |
| `no_access` | The app has no All files access |
| `refused` | The pack was not imported: `message` is why (another game's pack, another version or build, a build that stopped part way, no room). Nothing was changed |
| `busy`, `unknown_title`, `not_in_app` | As `build` (`busy` also while the game's pack is being imported or exported) |

### Settings `set` takes

| Key | Values | For one game |
|---|---|---|
| `console_mode` | `docked`, `handheld`, `default` | yes; not for a game whose mode is fixed |
| `res_scale` | `0.5`, `0.75`, `1`, `1.25`, `1.5`, `2`, `default` | yes; not for a game with its own Resolution rows |
| `upscaler` | `nearest`, `bilinear`, `fsr1`, `native`, `sgsr1` (one game: also `default`) | yes; not for a game on the DLSS stand-in or with Resolution presets |
| `fps_cap` | `default`, `own`, supported numeric target. Limited profiles offer supported targets from 20/30/40/45/60/90/120, above 60 only up to the display's highest refresh rate (`message` says why). A profile with fps_limit_min 30 rejects 20; unsupported rates are rejected. The others offer 60/30/20/15, and 40/45 where the game's engine rows hold them (the same caps Game settings enables). Game owned uses the game's own pacing and requires restart when entering/leaving it. `default` uses 60 for adjustable profiles. | yes; native-only games accept `own`/`default` |
| `overlay` | `on`, `off` | no |
| `keep_screen_on` | `on`, `off` (Settings › Screens › Keep the screen on while preparing) | no |
| `gpu_driver` | `system`, the folder name of an installed driver (as `Turnip_27.0.0-R2`), `default` | yes. Every game: the main screen's GPU driver (`default` is `system`). One game: its Game settings' GPU driver (`default` follows this phone's recommended driver, else every game's). A name that is not installed is `bad_value`, and `message` lists the installed ones. Installing a driver is not offered |
| `fast_cores` | `on`, `off`, `default` | yes. Every game: Use fast cores on the main screen (`default` is `off`). One game: its own choice (`default` follows every game's) |
| `hold60` | `on`, `off`, `default` | only for one game, and only for a game whose settings show Hold 60 fps |
| `bottom_screen_games` | `android`, `stats`, `cover` | no. Settings › Library › Games without a companion: Android's own screen, the stats page or the game's cover |
| `display` | `fit`, `stretch`, `fill`, `default` (Fit) | only for one game. `fill` only for a game whose Display offers Fill. When Fill is untested on the game, `message` says so |

A value outside the list is `bad_value`, another key `unknown_key`, a row the game does not have
`not_for_this_game`. `data.effective` says `now` or `next_start`. For `gpu_driver`, `fast_cores`, `hold60`,
`bottom_screen_games` and `display`, `data.resolved` says what the game (or every game, without `title_id`)
starts with after the change, the default worked out as the app does: a driver's folder name or `system`,
`on` or `off`, `android`, `stats` or `cover`, `fit`, `stretch` or `fill`. `display` also gives
`data.label`, the row's text in Game settings. Full lines:

```
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key gpu_driver -e value Turnip_27.0.0-R2 -e title_id 0100F2C0115B6000 -e id s1
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key gpu_driver -e value system -e id s2
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key fast_cores -e value on -e title_id 0100F2C0115B6000 -e id s3
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key hold60 -e value on -e title_id 0100F2C0115B6000 -e id s4
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key bottom_screen_games -e value stats -e id s5
adb shell am start -n dev.nxnative.android.peanutsdev/dev.peanuts.ctl.Command -e cmd set -e key display -e value fill -e title_id 0100F2C0115B6000 -e id s6
```

For example, after the first line: `"data": {"key": "gpu_driver", "stored": "Turnip_27.0.0-R2",
"effective": "next_start", "title_id": "0100F2C0115B6000", "resolved": "Turnip_27.0.0-R2"}`.

## status.json

Written whole each time (a temporary file renamed over the old one). It is written after the first command
of an app process, then at every command, at each change of the screen, at build progress, and every 2 s
while a build, a Prepare job or a game runs. Send `status` first: a file left by an earlier run of the app
is old (`written` says when). What the app cannot tell for sure is null or left out.

| Part | Fields |
|---|---|
| top | `schema` (1), `written` (ms), `seq` |
| `app` | `package`, `version`, `build_id` (as Home's header shows it), `commit`, `keys`, `firmware`, `folders` (`game`, `update`, `dlc`, `build`, `app`: each `path` and `free` bytes, null when not set; `build_folders`: every Build destination the app knows, each `path` and `free`), `gpu_driver` (the default's label, or `system`), `gate` |
| `screen` | `in_front`, `activity`, `page` (`home`, `settings`, `game`, `advanced`: Settings › Advanced or a game's Advanced page, over either; `to=home` and `to=settings` close it), `home` (`filter`, `find`, `show_hidden`, `scanning`), `game_page` (`title_id`, `tab`), `settings_section` (the section of Settings that shows: `library`, `console`, `graphics`, `controls`, `screens`, `users`, `app`, `help`, `developer`, `about`; `hub` while a tablet shows the tiles of the sections), `sheet` (`title_id` of the open game sheet), `screens_off` |
| `library` | one row a game: `title_id`, `name`, `state` (`ready`, `build`, `building`, `queued`, `not_in_app`), `listed` (it is in Home's list), `game` (the .nup, dump or file), `pack`, `has_companion`, `companion` (`package`, `version`). `library_read` says when the rows were read (at most 30 s old, read anew by `status`, a scan's end and a build's end) |
| `job` | the Prepare job the launcher shows, from its `job.json`: `title_id`, `name`, `state` (`pending`, `running`, `paused`, `failed`, `done`), `process_alive`, `interrupted`, `paused_reason`, `step`, `counts` (per step: `state`, `done`, `total`), `percent`, `eta_s`, `eta`, `text` (the card's line), `error`, `error_detail`, `fix`, `cause` (`addon_not_imported` or null), `try_anyway`, `updated`, `job_file`, `log`; `unreadable` (the reason) when its `job.json` could not be read |
| `build` | the library's build of this app process: `running`, `title_id`, `name`, `step` (`import`, `prepare`, `companion`, `done`), `percent`, `detail`, `started`, and after its end `ended`, `failed`, `error`, `code` |
| `queue` | the build queue, kept in the app's own storage: `items` (each `id`, `title_id`, `name`, `state` (`waiting`, `running`, `ready`, `stopped`), `place` in the batch (0 for a stopped game of an earlier batch), `tries`, and for a stopped one `code`, `error`, `step`), `total` (the batch: the games since the queue last ran empty), `waiting_for` (`heat`, `battery`, `game`, `mods`, `pack`: a pack of the next game is being imported, or null). Null until the launcher has read the queue |
| `game` | from the game's own run record: `running`, `title_id`, `name`, `pid`, `since`, `log` (a file in `files/logs`), `ended_as` (the last state the game wrote, when it no longer runs); `pack` and `mods_pack` for a game Home's Play started |
| `last_result`, `results` | see Answers |

`state` is `ready` only when the game's pack really starts it (the check Play makes), so a cover that says
Ready can be `build` here.

## Not offered, and why

- Deleting or replacing anything else: removing a game, a job's files or a pack, storage cleanup, saves
  (backup, restore, import, delete), importing a pack over one that is there (`import_pack` puts one only
  where there is none). A typo must not cost a save or an hour's build.
  (`reimport` replaces one `.nup` by one made from the same dumps, and asks first.)
- Anything that puts code or host switches into the game from a file: GPU driver installs, mods, the extra
  environment, env files. (`game ctl` reaches only a game that already runs. `set gpu_driver` only picks a
  driver that is installed already. `companion` with `path` installs a companion file with the checks of
  Install from file, the screen's own button.)
- Rebuild of a game that is built (other than by `reimport`), keys, firmware and folder setup, a game's second-screen
  choice, the rest of the in-game panel, pausing a game, answering a dialog. These come later.
- Installing an app update and pinning a shortcut: Android asks the user itself.

Upstream's own adb extras on the launcher (`android/TESTING.md`) are a separate, older door with its own
switch; this interface does not use them.
