# Control over adb

A way to drive the app from a computer, for tests and scripts: one command per `am start`, and one file that
says what the app is doing: reading the state, moving between pages, building, preparing, playing, a few
safe settings and a game's companion. Two commands delete or replace something, and only with
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
| | `-e cmd nav -e to settings -e section System` | Settings, scrolled to a heading (`Library`, `Updates`, `Display`, `Controls`, `Performance and picture`, `System`, `Help`, `About`; before the update to Banana Native v0.3.26 the three in the middle were one, `Game defaults`) |
| | `-e cmd nav -e to game -e title_id 0100F2C0115B6000 -e tab storage` | A listed game's page and tab (`performance`, `game`, `saves`, `storage`) |
| `build` | `-e cmd build -e title_id 0100F2C0115B6000` | The game sheet's Build with the files the sheet would choose: the base, the update chosen earlier (else the newest found), the DLC found, the companion as its switch stands. First build only. Refused with `wrong_version` when the sheet would ask "Build anyway?" |
| `reimport` | `-e cmd reimport -e title_id 0100F2C0115B6000 -e confirm true` | The game sheet's Import again: the game's `.nup` is made again from its dumps (the base, the update chosen earlier or else the newest found, the DLC found now), then the game is built. The new file is written beside the old one and replaces it only when the import is complete. See below |
| `prepare` | `-e cmd prepare -e title_id 0100F2C0115B6000` | Storage › Prepare again for a listed game, without its question. A dialog the app answers with is the refusal (`refused`, its text in `message`) |
| `job` | `-e cmd job -e do pause` (or `stop`) | The button of the card that shows: Stop on the build card, Pause on the Prepare card |
| | `-e cmd job -e do resume` (or `retry`) | Resume or Retry: a stopped build builds on; a Prepare job's own button is pressed |
| | `-e cmd job -e do try_anyway -e title_id 0100F2C0115B6000` | The answer "yes" to "Build anyway?" or to the Prepare card's "Try anyway": the game may not start or may draw wrongly |
| `play` | `-e cmd play -e title_id 0100F2C0115B6000` | Home's Play: the same checks, the build with mods when one is ready. A dialog Play would show is the refusal: its text comes back and the dialog is closed |
| `game` | `-e cmd game -e do stop -e confirm true` | Ends the running game as its panel's Stop does. Unsaved progress is lost |
| | `-e cmd game -e do ctl -e line "A~0.5"` | One line into the running game's live control file (the lines of `android/TESTING.md`, "Live control"). Its answer is in the run's log |
| `screens_off` | `-e cmd screens_off -e on true` | The black cover while a build runs; `-e on false` lifts it |
| `set` | `-e cmd set -e key fps_cap -e value 30 -e title_id 0100F2C0115B6000` | One of the settings below, for every game or, with `title_id`, for one |
| `companion` | `-e cmd companion -e do list` | The catalog against the library: one row a game in `data.companions` (below). With `title_id`, that game alone |
| | `-e cmd companion -e do install -e title_id 0100F2C0115B6000` | The catalog's newest companion for the game, downloaded and checked as the catalog screen does. One that is installed and older is replaced |
| | `-e cmd companion -e do update -e title_id 0100F2C0115B6000` | The same, refused (`not_installed`) when the game has none |
| | `-e cmd companion -e do remove -e title_id 0100F2C0115B6000 -e confirm true` | Deletes the game's companion package. The game and its saves stay |

`job` takes an optional `title_id`: the command is refused (`no_job`) when the job is another game's.

### `companion`

It reads the catalog from the network and downloads a package, so its answer comes later: `last_result` is
`pending` until the work has ended (a list takes a second or two, an install as long as the download).
Nothing is installed that the catalog screen would not install: the package's digest must match the
catalog's, and its runtime must not be newer than the app's.

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

### Settings `set` takes

| Key | Values | For one game |
|---|---|---|
| `console_mode` | `docked`, `handheld`, `default` | yes; not for a game whose mode is fixed |
| `res_scale` | `0.5`, `0.75`, `1`, `1.25`, `1.5`, `2`, `default` | yes; not for a game with its own Resolution rows |
| `upscaler` | `nearest`, `bilinear`, `fsr1`, `native`, `sgsr1` (one game: also `default`) | yes; not for a game on the DLSS stand-in or with Resolution presets |
| `fps_cap` | `default`, `own`, supported numeric target. Limited profiles offer supported targets from 20/30/40/45/60/90/120. A profile with fps_limit_min 30 rejects 20; unsupported rates are rejected. Game owned uses the game's own pacing and requires restart when entering/leaving it. `default` uses 60 for adjustable profiles. | yes; native-only games accept `own`/`default` |
| `overlay` | `on`, `off` | no |
| `keep_screen_on` | `on`, `off` (Settings › System › Keep the screen on while preparing) | no |

A value outside the list is `bad_value`, another key `unknown_key`, a row the game does not have
`not_for_this_game`. `data.effective` says `now` or `next_start`.

## status.json

Written whole each time (a temporary file renamed over the old one). It is written after the first command
of an app process, then at every command, at each change of the screen, at build progress, and every 2 s
while a build, a Prepare job or a game runs. Send `status` first: a file left by an earlier run of the app
is old (`written` says when). What the app cannot tell for sure is null or left out.

| Part | Fields |
|---|---|
| top | `schema` (1), `written` (ms), `seq` |
| `app` | `package`, `version`, `build_id` (as Home's header shows it), `commit`, `keys`, `firmware`, `folders` (`game`, `update`, `dlc`, `build`, `app`: each `path` and `free` bytes, null when not set), `gpu_driver` (the default's label, or `system`), `gate` |
| `screen` | `in_front`, `activity`, `page` (`home`, `settings`, `game`, `advanced`: Settings › Advanced or a game's Advanced page, over either; `to=home` and `to=settings` close it), `home` (`filter`, `find`, `show_hidden`, `scanning`), `game_page` (`title_id`, `tab`), `sheet` (`title_id` of the open game sheet), `screens_off` |
| `library` | one row a game: `title_id`, `name`, `state` (`ready`, `build`, `building`, `not_in_app`), `listed` (it is in Home's list), `game` (the .nup, dump or file), `pack`, `has_companion`, `companion` (`package`, `version`). `library_read` says when the rows were read (at most 30 s old, read anew by `status`, a scan's end and a build's end) |
| `job` | the Prepare job the launcher shows, from its `job.json`: `title_id`, `name`, `state` (`pending`, `running`, `paused`, `failed`, `done`), `process_alive`, `interrupted`, `paused_reason`, `step`, `counts` (per step: `state`, `done`, `total`), `percent`, `eta_s`, `eta`, `text` (the card's line), `error`, `error_detail`, `fix`, `cause` (`addon_not_imported` or null), `try_anyway`, `updated`, `job_file`, `log` |
| `build` | the library's build of this app process: `running`, `title_id`, `name`, `step` (`import`, `prepare`, `companion`, `done`), `percent`, `detail`, `started`, and after its end `ended`, `failed`, `error`, `code` |
| `game` | from the game's own run record: `running`, `title_id`, `name`, `pid`, `since`, `log` (a file in `files/logs`), `ended_as` (the last state the game wrote, when it no longer runs); `pack` and `mods_pack` for a game Home's Play started |
| `last_result`, `results` | see Answers |

`state` is `ready` only when the game's pack really starts it (the check Play makes), so a cover that says
Ready can be `build` here.

## Not offered, and why

- Deleting or replacing anything else: removing a game, a job's files or a pack, storage cleanup, saves
  (backup, restore, import, delete), importing a pack. A typo must not cost a save or an hour's build.
  (`reimport` replaces one `.nup` by one made from the same dumps, and asks first.)
- Anything that puts code or host switches into the game from a file: GPU driver installs, mods, the extra
  environment, env files. (`game ctl` reaches only a game that already runs.)
- Rebuild of a game that is built (other than by `reimport`), keys, firmware and folder setup, a game's second-screen
  choice, the in-game panel, pausing a game, answering a dialog. These come later.
- Installing an app update and pinning a shortcut: Android asks the user itself.

Upstream's own adb extras on the launcher (`android/TESTING.md`) are a separate, older door with its own
switch; this interface does not use them.
