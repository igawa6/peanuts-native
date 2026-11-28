# Launcher support

How a Peanuts-built game starts from outside the app: a home screen shortcut, or an emulator frontend
(ES-DE, Daijisho, Beacon, Pegasus, or anything that runs `am start`). Both paths reuse upstream's own
emulator-frontend entry point (`android/FRONTENDS.md`, `dev.nxnative.android.FrontendLaunch`), so there is
one launch contract, not two: a title ID or a game file, resolved against the library, started if it is
ready, or handed to Home if it is not.

## Home screen shortcuts

Open a game's sheet (its card, then its Build/Rebuild row) and tap **Add to home screen**
(`dev.peanuts.ui.GameSheet`). The launcher pins a shortcut named after the game, with the library's own
cover art as its icon (the same PNG Home shows on the card, `LibraryScanner.icon`); a game with no cover
yet gets a plain letter tile instead, replaced the next time the shortcut is pinned or updated. Tapping the
shortcut later starts the game directly if it is ready, or opens Home with that game if it still needs a
build, keys, or firmware fonts.

The shortcut's intent carries only the game's title ID, never a file path, and it is always explicit (it
names `dev.nxnative.android.FrontendLaunch` directly, never a broad action another app could also answer).
`FrontendLaunch` looks that title ID up again in the library's own list before doing anything, so a
shortcut (or anything else that starts this exact component with an unknown title ID) can never reach a
file outside the library. If the game or its pack is later moved, the shortcut keeps working: it follows
the title ID's card, not a fixed path.

Code: `android/app/src/main/java/dev/peanuts/launch/GameShortcuts.java`.

## Dynamic shortcuts (last 3 played)

The launcher's own "last played" shortcuts (long-press the app icon) show up to 3 games, the most
recently started first, each with its own cover art. These are kept automatically: every time a game
reaches its own host (`GameActivity.onCreate`, one marked line), it moves to the front of a small list
(`files/peanuts-recent.json`) and the launcher's dynamic shortcut set is republished. Nothing to turn on;
nothing to tap. The same title-ID-only, explicit-intent contract as a pinned shortcut applies: a dynamic
shortcut can only ever point at a title this device actually started.

Code: `android/app/src/main/java/dev/peanuts/launch/RecentGames.java`.

## Frontend folder

A frontend lists files, and a game Peanuts built may have no file of its own to list (a dump folder, a `.nup` in the app's
storage), so one pinned shortcut per game used to be the only way into a frontend's list. **Settings › Library › Add folder
› Frontend** fixes that: pick a folder, and the app keeps one small entry file there per game that is ready to play. Point the
frontend's Switch platform at the folder too, and every game you build shows up on its next rescan. The folder is one kind of
Library's folder list (`FolderKinds.FRONTEND`), with one folder at most: its row shows the folder and how many games it holds.

- **The name:** `<game name> [<title ID>].nutshell`, for example `Pokémon Legends Z-A [0100F43008C44000].nutshell`. The
  frontend's scraper finds the cover and the game's details by the name, as for a dump named the common way (the
  characters a file name cannot hold are left out). The bracket carries the title ID.
- **The text:** two lines, `Peanuts Native <application ID>` and the title ID. The first line is how the app knows the file
  is its own; the second starts the game even when someone renamed the file.
- **When it changes:** a game gets its entry once its build is finished and it is ready to play. The entry goes when the game
  leaves Home, and is renamed when the game's name changes. Picking the folder adds every game at once. The entry stays while
  the game is on Home, even when the game needs something again (shaders after an app update), so the frontend keeps its
  entry and its play time; starting it then opens Home with the game.
- **What it never touches:** any file that is not one of this app's entries (another app's, another Peanuts app's, a file
  of the same name), and anything in a subfolder. **Turn off** in the Frontend folder row's sheet takes the app's entries out
  of the folder; **Pick another** moves them.

Starting an entry is a title-ID launch: `FrontendLaunch` reads the title ID from the file's name (else from its text) and then
does exactly what a home screen shortcut does, looked up again against Home's list. The frontend sends the entry like any game
file, by the extra `game` with its path or as `ACTION_VIEW` with its URI:

```bash
adb shell am start -n dev.nxnative.android.peanuts/dev.nxnative.android.peanuts.Launch -e game "/storage/emulated/0/ROMs/switch/Pokémon Legends Z-A [0100F43008C44000].nutshell"
```

The app writes the entries by their path, so it needs All files access (as it does for the games); the row says when it is
waiting for it. A frontend's player for Peanuts Native lists `nutshell` beside `nup` among its file extensions.

Code: `android/app/src/main/java/dev/peanuts/launch/FrontendFolder.java` (the folder, the launch's title ID) and
`FrontendEntry.java` (names and texts, checked on the JVM by `peanuts/tests/test_frontend_folder.py`).

## The launch contract, for emulator frontends

The app exposes two exported components that both run the same code (`FrontendLaunch`), so a frontend can
use either:

| Component | Name | Notes |
|---|---|---|
| Upstream's alias | `<applicationId>/<applicationId>.Launch` | documented in `android/FRONTENDS.md`; the alias name itself changes with the build (`-PnxApp=peanuts` gives `dev.nxnative.android.peanuts`, the release app gives `dev.bananative.android`) |
| Peanuts' alias | `<applicationId>/dev.peanuts.launch.LaunchActivity` | the alias name is fixed; only the package before the `/` changes between builds, so a frontend's player template needs no per-build text substitution beyond that package name |

Both accept the same intent forms (`android/FRONTENDS.md` "What the app accepts" has the full list):

- `ACTION_VIEW` with a `file://`, SAF, or FileProvider URI of the game's `.nup`
- the extra `game`, the file's path
- the extra `title_id`, a game already in the library

Neither alias declares intent filters, by design: on Android 15 an explicit intent only has to match a
filter when the target declares one, and some frontends (Daijisho's `-e game {file.path}` player, for
one) send intents with no action at all. An alias with no filters at all takes every form a frontend
sends it, explicit-intent only, so nothing outside this app can reach it by an implicit `ACTION_VIEW`
either.

What happens next is exactly `FrontendLaunch`'s own contract (`android/FRONTENDS.md` "What happens"): a
game whose card is ready to play starts at once and nothing of the launcher shows; a game that needs the
user (no pack yet, Prepare, shaders to build, a missing key or firmware font, All files access) opens
Home with that game instead of failing silently. A frontend never gets to choose the pack or save folder;
only `adb` can (`android/TESTING.md`).

For the test app, use `dev.nxnative.android` as the package before the `/`. For a `-PnxApp=peanuts` build,
use `dev.nxnative.android.peanuts`.

## ES-DE

`ES-DE/custom_systems/es_find_rules.xml`:

```xml
<?xml version="1.0"?>
<ruleList>
    <emulator name="PEANUTS-NATIVE">
        <rule type="androidpackage">
            <entry>dev.nxnative.android.peanuts/dev.peanuts.launch.LaunchActivity</entry>
        </rule>
    </emulator>
</ruleList>
```

`ES-DE/custom_systems/es_systems.xml`, pointed at your `.nup` folder:

```xml
<?xml version="1.0"?>
<systemList>
    <system>
        <name>peanutsnative</name>
        <fullname>Peanuts Native</fullname>
        <path>%ROMPATH%/peanutsnative</path>
        <extension>.nup .NUP .nutshell .NUTSHELL</extension>
        <command label="Peanuts Native">%EMULATOR_PEANUTS-NATIVE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMSAF%</command>
        <command label="Peanuts Native (path)">%EMULATOR_PEANUTS-NATIVE% %EXTRA_game%=%ROM%</command>
        <platform>switch</platform>
        <theme>switch</theme>
    </system>
</systemList>
```

`%ROMSAF%` needs ES-DE's ROM folder picked through its own storage access; without that, use the "(path)"
command.

## Daijisho

Daijisho imports a platform file; this adds Peanuts Native to the Switch platform's player list (raise
`revisionNumber` by one when you add this to an existing file):

```json
{
  "name": "switch - Peanuts Native",
  "uniqueId": "switch.dev.peanuts.native",
  "description": "Supported extensions: nup, nutshell.",
  "acceptedFilenameRegex": "^(.*)\\.(?:nup|nutshell)$",
  "amStartArguments": "-n dev.nxnative.android.peanuts/dev.peanuts.launch.LaunchActivity\n -e game {file.path}",
  "killPackageProcesses": false,
  "killPackageProcessesWarning": false,
  "extra": ""
}
```

A second entry with `-a android.intent.action.VIEW\n -d {file.uri}` instead of `-e game {file.path}` is
the URI form. Import the file (Settings › Library › import a platform), then pick the player on the
Switch platform.

## Beacon, Pegasus, and anything else that runs `am start`

Pegasus's `metadata.pegasus.txt`:

```
launch: am start -n dev.nxnative.android.peanuts/dev.peanuts.launch.LaunchActivity -e game "{file.path}"
```

From a computer, with the phone on `adb`:

```bash
adb shell am start -n dev.nxnative.android.peanuts/dev.peanuts.launch.LaunchActivity -e game /storage/emulated/0/ROMs/game.nup
```

Or, for a game already in the library, by its title ID instead of its file:

```bash
adb shell am start -n dev.nxnative.android.peanuts/dev.peanuts.launch.LaunchActivity -e title_id 01007EF00011E000 -e run true
```

(`--ez run true` only tells `am` the extra's type is boolean; `FrontendLaunch` itself does not read a
`run` extra, since a known, ready title always starts at once by itself. It is harmless either way.)

## What is Peanuts-specific here, and what is upstream's

- `dev.peanuts.launch.GameShortcuts`, `dev.peanuts.launch.RecentGames`, the **Add to home screen** button
  in `GameSheet`, the `dev.peanuts.launch.LaunchActivity` alias and the Frontend folder (`FrontendFolder`,
  `FrontendEntry`, one marked line in `FrontendLaunch.onCreate`) are Peanuts additions.
- `FrontendLaunch`, the resolution logic (`ready`, `byTitleId`, `pickedGame`, the URI/path handling), the
  `<applicationId>.Launch` and `<applicationId>.Open` aliases, and the whole "what happens" behaviour are
  upstream's (`android/FRONTENDS.md`, `android/app/src/main/java/dev/nxnative/android/FrontendLaunch.java`,
  `Shortcuts.java`). Peanuts' alias only gives frontends a second, build-stable name for the same code; it
  adds no new resolution logic and no new file-access path.
- The library already registers every Peanuts-built game into upstream's own game list
  (`Upstream.addToList`, in `dev.peanuts.ui.Library`'s scan), so `FrontendLaunch`'s title-ID and ready-card
  lookups already see Peanuts' games with no change on that side.

## Checking on the device

Not yet run on a real phone or a real frontend; verify before relying on this:

- [ ] A pinned shortcut (GameSheet › Add to home screen) shows the game's cover, and tapping it starts the
      game directly when it is already built.
- [ ] The same shortcut, for a game that is not built yet, opens Home with that game instead of failing.
- [ ] After playing 2-3 different games, the launcher's own "last played" shortcuts (long-press the app
      icon) show them, newest first, each with its own cover.
- [ ] `adb shell am start -n <applicationId>/dev.peanuts.launch.LaunchActivity -e title_id <ID>` starts a
      known, ready game the same way the existing `-n <applicationId>/<applicationId>.Launch` form does.
- [ ] A frontend's import of the ES-DE or Daijisho snippet above actually resolves the component (watch
      for "Unable to find explicit activity class", `android/FRONTENDS.md`'s note on Android 15's
      explicit-intent filter matching) and starts a game.
- [ ] The Thor's own launcher (`rip.moth.cocoonshell`) is a normal Android launcher: it should accept a
      pinned shortcut's `requestPinShortcut` call and show dynamic ("last played") shortcuts the same way
      stock Android launchers do. Not checked on the device; nothing in this change is Thor-specific or
      dual-screen-specific (shortcuts and frontend launches both start on the main display, same as any
      other app's shortcut).
