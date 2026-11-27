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
        <extension>.nup .NUP</extension>
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
  "description": "Supported extensions: nup.",
  "acceptedFilenameRegex": "^(.*)\\.(?:nup)$",
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
  in `GameSheet`, and the `dev.peanuts.launch.LaunchActivity` alias are Peanuts additions.
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
