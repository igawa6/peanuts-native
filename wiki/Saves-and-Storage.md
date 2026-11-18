# Saves and storage

Where the app keeps your game, saves and packs, how to back saves up and move them, and what uninstalling deletes. None of the save features need All files access (only a save folder you picked yourself on the phone's storage does).

## Storage access

The app reads your game in one of three ways.

| | All files access | A .nup used where it is | A dump folder copied into the app |
|---|---|---|---|
| Needs All files access | yes | no | no |
| Read from | where it is, e.g. `Switch2/DonkeyKongBananza` | where it is, through the system picker's permission | the app's own storage (`Android/data/<app>/files/games/`) |
| Extra space | none | none | the dump's size again (the original can be deleted afterwards) |
| On uninstall | your dump stays | your `.nup` stays | the copy is deleted with the app's data, unless you keep the data |

- Turn on **All files access** with the **Allow** button on Home, or in Android's settings. Settings > **Readiness check** shows whether it is on. A dump folder read where it is needs it, because the game opens thousands of files by path.
- A `.nup` in a place that only streams files (a cloud folder) cannot be used where it is; the app offers **Copy into the app**.
- **Export pack...** and **Import pack...** still need All files access.
- **A game file that is gone** (deleted, moved, renamed): the card says **Game file gone** with **Pick it again**. Picking the same game keeps its pack, settings and saves; another game is refused.
- **Remove from list** on a copied game asks **Remove only** or **Delete the copy** (cannot be undone). Your original dump, the pack and your saves stay either way.

## Where saves are

A game's save is in `Android/data/<app>/files/saves/<title ID>` (and its device save, `saves/<title ID>_device`), unless you picked another save folder in the game's ... button > **Folders**. Backups go to `save-backups/<title ID>/` next to the game folder, for example `Switch2/save-backups/040074A01BF12000/`; the app never deletes that folder.

**Stop the game first** (in-game panel > **Stop the game**): the app refuses to back up, restore or import while it runs.

## Back up, restore, import

- **Back up saves** (game's ... button): copies the save into a new folder named by date and time and checks every file. Without All files access, or for a copied game or a `.nup`, it exports a `.zip` to a place you pick and checks it; a copy also stays in the app's storage (deleted with the app's data).
- **Restore saves**: lists the backups the app can see, newest first. Before replacing anything the app backs up the current save (a backup ending in `-before-restore`), so a restore can be undone.
- **Import save**: takes **A .zip (from Back up saves, or a zipped save folder)** or **A save folder**. The current save is backed up first; a file that cannot be read as a save changes nothing.
- **Save folder gone:** if a folder you picked was deleted or moved, the card says **Save folder gone**; choose **Use the app's save** or **Pick the folder** again.

## Moving saves

- **To another phone:** Back up saves, copy the backup folder or `.zip` over, and use **Import save** there.
- **To a new app version:** an update keeps the app's storage, so the save stays. Only a save from version 0.1.0 or older, in `Switch2/saves/<title ID>/`, is copied in once at the game's next start.
- **Between two apps on one phone** (each has its own storage): Back up in one, Import in the other.

## What uninstalling deletes

The app's data, `Android/data/<app>/files`, holds `saves/`, `packs/` and `games/` (copied games). On uninstall Android asks whether to keep it; if you don't, all of it is deleted. Kept either way: your dumps and `.nup` files, and save backups made with All files access in `save-backups/<title ID>/`. **Back up your saves before uninstalling.**

## Packs

A pack is in `Android/data/<app>/files/packs/<title ID>`. Export and import: [First start and Prepare](First-Start-and-Prepare#moving-a-pack-between-phones).
