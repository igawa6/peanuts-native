# dev.peanuts.ui — the library inside Banana's Home

Banana's Home (`dev.nxnative.android.MainActivity`) is the one home: its game box is filled by `Library.fill`, its Settings page starts with `LibrarySettings.add`,
its pickers' results pass through `LibrarySettings.result`, its Manage sheet has one Peanuts row (`GameSheet.open`). The look comes from `PUi`, a verbatim copy of
upstream's package-private `Ui` on the same `R.color`/`R.drawable` resources (`diff Ui.java PUi.java | grep -v '^[<>] *\(package\|import dev\|public\)'` is the
drift check; re-copy when upstream changes `Ui.java`). The five `dev.peanuts.ui.Feel` lines of `Ui.java` (marked `PEANUTS`) are not copied: Peanuts' screens
call `Haptics` themselves, and both would play.

## Upstream hooks (each one line, tagged `PEANUTS`)

| File | Hook |
|---|---|
| `MainActivity.build()` | `Ui.page(this, frame, 900)` — 640 → 900 dp: four covers per row on a handheld |
| `MainActivity.buildSettings()` | `dev.peanuts.ui.LibrarySettings.add(this, content);` before the Updates section |
| `MainActivity.refresh()` | `dev.peanuts.ui.Library.fill(this, gameBox, games, Ondevice.cardView(), Ondevice::shows, this::gameCard, this::manage, this::refresh);` in place of the "Your games" block |
| `MainActivity.manage()` | the `🔨 Build again` row → `dev.peanuts.ui.GameSheet.open(this, g.titleId)` |
| `MainActivity.onActivityResult()` | `if (dev.peanuts.ui.LibrarySettings.result(this, request, result, data)) return;` first (requests 60–66) |
| `AndroidManifest.xml` | `CatalogActivity` under `@style/Theme.Banana`; no Peanuts launcher entry (MainActivity keeps LAUNCHER) |
| `src/ondevice/AndroidManifest.xml` | the `BuildService` line (import → Prepare → companion) |

`gameCard`, `manage`, `refresh` stay private in MainActivity: the hook passes them as method references (`addGame` stays in MainActivity, unused by Home since SPEC3).

## Pieces

- `Library` — Home's game box (SPEC3): the filter row (All · Building · Ready · Build needed · Not Supported Yet, Find; Building only while a build or
  upstream's Prepare runs, its count in banana), then one cover grid (PUi.tile at cell size with the NACP icon, four columns on a handheld, the state on the cover,
  the name under it; Building → Ready by last played → Build needed A–Z → Not Supported Yet A–Z, no sections). Tap = Play (`Upstream.launch`, Banana's
  `MainActivity.launch` by reflection) for a ready listed game, the sheet otherwise; long press / Y = Banana's Manage (listed) or the sheet (scanned); L/R shoulders
  = previous/next filter. The Building filter holds the full cards (the build card, upstream's Ondevice card) two per row; in All the building game is a cover with
  a progress strip and a `Building · N %` chip that opens that filter. The first-run card when no game folder is set; the scan (`LibraryScanner`) and the catalog
  (`CompanionPort`) off the main thread; a scanned game that is ready goes into upstream's list (`Upstream.addToList`) so Play and Manage are Banana's own.
- `GameSheet` — the game's dialog: Base / Update / DLC / Companion rows, Build or Rebuild, Import again… for a game whose base is a .nup (the .nup made anew from the dumps, `dev.peanuts.build.Reimport`), Add keys… when prod.keys are missing. Its Choose/Add pickers come back
  through `LibrarySettings.result` (requests 65, 66).
- `LibrarySettings` — Settings › Library: folders, keys, firmware, Rescan, Haptics (System / Off), Motion (System / Reduced), Companions; the pickers
  (also the first-run card's and the sheet's).
- `Haptics`, `Motion`: the launcher's feedback, six haptic words (TICK, CLICK, HEAVY, EDGE, DONE, REJECT) and six motion tokens (SNAP, FOCUS, ENTER, EXIT,
  EMPHASIS, PROGRESS), called from `Library`, `GameSheet`, `LibrarySettings` and `CompanionVersions`, never from inside `PUi` (it stays a verbatim copy). No
  upstream hook: the D-pad's focus haptic rides on the key listeners the library's own views have, the app-in-front flag and the focus after a game on
  activity lifecycle callbacks `Library.fill` registers, and the sheet and launch animations are resources in `src/peanuts/res` found by name. Animations
  write alpha, translation and scale only, and none runs while a direction is held. `Library.render` skips a rebuild that would show the same thing, and a
  rebuild keeps the focus (by title ID) and the scroll position.
- `Feel`: the same words and tokens for the pages upstream draws (a game's page, held cover; Settings and Home's cards through the same widgets), called from
  marked one-line hooks in upstream's `Ui`, `PadFocus`, `MainActivity` and `GameSettings` and from `dev.peanuts.settings`. The table: `dev/peanuts/settings/HOOKS.md`,
  section 4. `Haptics` and `Motion` themselves still take no upstream hook.
- `LibraryScanner`, `Upstream`, `CompanionPort`, `ImporterPort`, `BuildPort` — as before (reflection into upstream and the sibling Peanuts packages).

## Launching a Ready game

Upstream's own card (Play → `MainActivity.launch`). `GameEnv.ensureFonts`/`ensureBuildId` run for every ready game after each scan, so the env is set before Play.
