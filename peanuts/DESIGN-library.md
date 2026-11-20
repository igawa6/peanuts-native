# Peanuts Native — library flow (design contract, 2026-11-12)

Owner's direction: everything on the handheld. First run sets up keys, firmware and folders (as Eden does); the home screen is a library of every game found in the game folder (NSP, XCI, .nup or dump folder); a game that is not built yet opens a sheet showing its base, update and DLC (matched by title id from the folders, or added by hand) and its companion (when the catalog has one) with one **Build** button; companion install/update/remove lives inside the game's settings, not in a separate screen.

Overlay rules still apply (PEANUTS.md): new code only under `dev/peanuts/…` and `dm/peanuts/…`; upstream files get one-line `PEANUTS` hooks at listed sites. Upstream's own screens (MainActivity Home, Add game, Ondevice panel, GameSettings) stay intact; the Peanuts launcher entry becomes the library.

## Folders and files (on the device)

```
<app files>/keys/prod.keys                      the user's keys (copied in at setup; never shipped)
<app files>/firmware/…                          the user's firmware folder or its extracted shared fonts (fonts/standard.ttf, fonts/nintendo_ext.ttf)
<game folder>/                                  the library root (setup); scanned recursively, depth 2
<game folder>/<Game>/<Game>.nup                 what the importer writes for an NSP/XCI (one file; base + update + addon inside)
<game folder>/<Game>/<title ID>-work/android-pack      the pack (Prepare)
<game folder>/<Game>/<title ID>-work/peanuts/<Name>/   the companion (catalog)
<update folder>/, <dlc folder>/                 optional extra roots scanned for update/DLC NSPs (setup)
```

Matching: an NSP/XCI's title id and type come from its content metadata (CNMT: base, patch = update, add-on = DLC); an update's base is title id with the low 11 bits = 0x800 → base; a DLC's base = (title id >> 12 << 12) | 0 … use the CNMT "application id" field. Updates/DLC are listed under the base game they belong to.

## Importer C API — `dm/peanuts/import/nximport.h`, JNI in `dm/peanuts/import/nximport_jni.c` → `dev.peanuts.importer.Native`

```c
typedef struct NxImportInfo { char title_id[17]; char app_id[17]; int kind; /* 0 base 1 update 2 dlc */ unsigned version; char name[256]; char publisher[256]; } NxImportInfo;
int  nximport_keys(const char *prod_keys_path);                 // 0 ok; loads header/key-area/titlekek keys
int  nximport_probe(const char *nsp_or_xci, NxImportInfo *out, int max, int *count);  // every title in the file (an XCI/NSP can hold base+update+dlc)
int  nximport_icon(const char *file, const char *title_id, const char *out_png);     // the control NACP icon (English or first) → PNG file
int  nximport_write_nup(const char *out_nup, const char **inputs, int n_inputs, const char *base_title_id,
                        void (*progress)(void *user, const char *step, long long done, long long total), void *user);  // base + its update + dlcs from the given NSP/XCI files → one .nup,
                        // each base and update package with its meta block (SPEC §5: main.npdm, NACP, display version; the base's icon, title and publisher; issue #158)
int  nximport_firmware_fonts(const char *firmware_dir, const char *out_dir);   // shared fonts (FontStandard / FontNintendoExtended NCAs, bfttf XOR 0x06186249) → ttf files
const char *nximport_last_error(void);
```
The `.nup` layout and compression are upstream's (`dm/host/nup_image.h`, `scripts/pack_dump.py` for the folder layout: `base/exefs`, `base/romfs`, `update/<version>/exefs|romfs`, `addon/<title id>/romfs`); write the format the host reads (uncompressed entries are fine; `DM_NUP_ZSTD` entries optional).

## Library UI — `dev.peanuts.ui`

- `SetupActivity`: first run (or Settings › Setup): pick `prod.keys` (SAF, copied to `files/keys/`), pick firmware folder (optional; extracts fonts via `Native.firmwareFonts`), pick game folder (required; All files access flow as upstream's `Folders`), optional update folder and DLC folder. Stored in `dev.peanuts` prefs (`PeanutsPrefs`).
- `LibraryActivity` (the LAUNCHER entry, replacing `CatalogActivity`'s): scans the roots, groups by base title id, shows cards: art (`LibraryScanner.icon`, kept in `files/icons/<title ID>.png`: the PNG beside a `.nup`, the icon inside a `.nup`, else `Native.icon` from an NSP/XCI; a `.nup` that carries none gets the icon beside it, `NupIcon.keep`), name, status chip: **Ready** (pack present and fits, per upstream `PackFind`/`Game`), **Build** (needs import and/or Prepare), **No profile** (title not in the APK's `BuildConfig.NX_PROFILES`). Tap: Ready → Play (start `GameActivity` as upstream's `Game`/`FrontendLaunch` does, by title id); else → `GameSheet`.
- `GameSheet` (bottom sheet / dialog): rows Base (file, version), Update (newest found; "add…" to pick another), DLC (list; add…), Companion (catalog state: not available / available x.y / installed x.y), and **Build**. Build hands off to `BuildPipeline`.
- Reuse upstream classes read-only through `dev.peanuts.Games` (reflection) where needed; never edit upstream UI.

## Build pipeline + settings integration — `dev.peanuts.build` and `dev.peanuts.settings`

- `BuildPipeline.start(ctx, sheetState)`: (1) if base is NSP/XCI → `Native.writeNup` into `<game folder>/<Name>/<Name>.nup` with progress (foreground service with notification, resumable per step); (2) add the `.nup` to upstream's game list the way `--es add_game` does (call `AdbControl`/`Ondevice` paths via intents to `MainActivity`, exactly as adb does: `--es ondevice_dump <nup> --ez ondevice_start true`, then the panel's Start — find and call the service directly if an intent cannot start it); (3) install the chosen companion (reuse `dev.peanuts.Installer`/`Catalog`); (4) report progress back to the sheet (LiveData/callback) and finish with Ready.
- Companion in game settings: one `PEANUTS` hook in upstream's game settings screen (`GameSettings.java` — find the right place, e.g. after the GPU driver row) adds a **Companion** section built by `dev.peanuts.settings.CompanionSection.view(ctx, titleId)`: current version, Install/Update/Remove, "Open on second screen: Physical/Off" (stored in prefs for later use). The old `CatalogActivity` stays as a fallback but loses its launcher entry.

## Integration (session owner)

Manifest: `LibraryActivity` gets the LAUNCHER filter (the `CatalogActivity` line changes to it); `SetupActivity`, the build service. build.gradle: nothing new. Hooks: GameSettings (1 line). Build, Thor test with Pokopia (.nup already) and with an NSP (owner provides one in the game folder).
