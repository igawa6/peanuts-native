# amiibo

Games that read amiibo find one on Peanuts' virtual stand: a player places one of their own amiibo, or one of the app's list, and the game reads it as
it reads a figure on a console's NFC reader. GitHub issue #582.

## On only where the player turns it on

amiibo is off by default. **Settings › Users › amiibo** turns it on for every game, and a game's own **amiibo** switch in its Game options
(shown for a game AmiiboAPI lists, or one seen opening nfp while amiibo was on for it) turns it on or off for that game alone; the game's own switch
comes first. A change counts from the game's next start (`dev.peanuts.amiibo.AmiiboSwitch`, its own prefs file `peanuts_amiibo`).

- A game started with amiibo on gets `PN_AMIIBO=1`. The host's loader then resolves the game's `nn::nfc` and `nn::nfp` imports to
  `dm/peanuts/peanuts_amiibo.c` before its own take-over table (`dm/host/loader.c` `force_lookup`, one marked hook under the compile-time `PN_AMIIBO`
  of `android/host/CMakeLists.txt`). The file's take-overs sit in a table of their own (the section `pnamiibo`), so `hle_sdk16.c`'s block stays in the
  host unchanged; the loader's own duplicate check only looks inside one table.
- Every other game gets `hle_sdk16.c`'s nfc and nfp block, exactly as without amiibo: NFC reported off by `nn::nfc`, `nn::nfp` with no tag. Peanuts'
  stand is not reached: no helper thread starts, nothing is read or written, and the launcher's amiibo answers refuse (`enabled=0` in the status).

## How a game sees it

With amiibo on, the SDK's `nn::nfp` calls are taken over by `dm/peanuts/peanuts_amiibo.c` in place of `hle_sdk16.c`'s block. The take-over sits above
the NFC service and above the figures' encryption: a game asks for plain structures and gets them from what the app hands over.

| Call | Answer |
|---|---|
| `ListDevices`, `GetNpadId` | one NFC device on the connected controller |
| `StartDetection` | the device searches (state 1); the app sees the search (`Bridge.amiiboStatus`) |
| an amiibo placed | state 2, the activate event signalled |
| `GetTagInfo` | the tag's UID (7 bytes), NFC-A, type 2 |
| `Mount` | state 4 |
| `GetModelInfo` | from the amiibo's ID: the character as the ID's bytes hold it, variant, type, model number, series |
| `GetCommonInfo` | last write, write count, area size 0xD8 |
| `GetRegisterInfo` | the player's Mii as the owner (the picture's Mii of the user the game started with), the character's name as nickname |
| `OpenApplicationArea`, `GetApplicationArea`, `SetApplicationArea`, `Flush` | this game's area, kept by Peanuts per access ID |
| `CreateApplicationArea`, `RecreateApplicationArea` | written at once |
| `Unmount` | state 2; a second later the tag leaves the stand on its own (state 3, the deactivate event) |
| `StopDetection` | state 0; a tag on the stand leaves it |
| amiibo settings applets | end at once: already registered, nothing to erase or restore |
| `nn::nfc::IsNfcEnabled` | on |

Each game gets an area of its own on an amiibo (its access ID), as Ryujinx keeps them: there is no "data of another game" step, and resetting one
game's data leaves the others'.

## What the app keeps

- `res/raw/amiibo_db.tsv` (peanuts source set): every amiibo of [AmiiboAPI](https://github.com/N3evin/AmiiboAPI) (MIT, Copyright (c) 2017 Nevin Vu)
  with its name, series and type, and what each does in which Switch game, offline. `peanuts/scripts/amiibo-db.py` makes it from a pinned commit.
- `files/amiibo/own/<ID>-<UID>/`: one of the player's amiibo. `tag.txt` (ID, UID, from a file or the list), `placed.tsv` (per game: how often, when
  last), and what the host writes: `area-<access ID>.bin` with the game's title ID beside it (`.title`) and `common.txt` (writes, last written).
- `files/amiibo/games.txt`: games seen initializing nfp while amiibo was on for them, so their page shows the amiibo area though AmiiboAPI does not
  list them.

- `cacheDir/amiibo/<ID>.png`: pictures, downloaded from AmiiboAPI's images on GitHub (raw.githubusercontent.com) only when a page shows the amiibo,
  and kept; only once the player has turned them on (Settings › Users › amiibo pictures, off by default) and while amiibo is on, in Settings or for a
  game (`AmiiboSwitch.picturesOn`). Otherwise, or offline, each amiibo shows as Peanuts' own card and nothing is downloaded.

An imported `.bin` gives its ID (plain at 0x54) and its UID (the first pages). Everything else in a dump is encrypted and is not read: the game data a
figure carries from a console stays on the figure, and Peanuts keeps its own. No keys of any kind are asked for, read or shipped.

## Control

`adb shell am start -n <package>/dev.peanuts.ctl.Command -e cmd game -e do amiibo -e value <status | off | ID | own:<ID>-<UID>>` (CONTROL.md).
