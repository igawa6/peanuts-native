# PEANUTS hooks for the build pipeline, the Companion section and the Mods section

One-line additions to upstream files (PEANUTS.md rule 2).

## 1. GameSettings.java: the Companion section (one line)

File: `android/app/src/main/java/dev/nxnative/android/GameSettings.java`, in `static void build(MainActivity a, LinearLayout content, Game g, Runnable again, Runnable close)`,
after the Advanced section's Extra environment row and before the `Section[] all = ...` line. It is the last row of the Game tab's Game options card:

```java
        options.rows.addView(dev.peanuts.settings.CompanionSection.view(a, id, dev.peanuts.settings.CompanionSection.root(g.packDir)));   // PEANUTS: the Companion section (install/update/remove, second screen)
```

`CompanionSection.root(g.packDir)` computes the companion root the way `dev.peanuts.Games.Entry.companionRoot` does: `<packDir>/../peanuts`
(`<game>/<title ID>-work/peanuts`), or null without a pack (then the section says "build the game first" and Install is off).

## 1b. GameSettings.java: the Mods section (one line)

Same file, in `static void build(MainActivity a, LinearLayout content, Game g, Runnable again, Runnable close)`, directly before the Companion section's line, so
Mods shows above Companion in the Game tab:

```java
        options.rows.addView(dev.peanuts.settings.ModsSection.view(a, id, g.name, g.gameDir, g.packDir));   // PEANUTS: the Mods section, above Companion (...)
```

`ModsSection` lists the game's mods (a switch and a bin each), installs one from a folder or a zip (`dev.peanuts.mods.ModImportUi`; the pickers' results come
back through `MainActivity.onActivityResult` → `dev.peanuts.ui.LibrarySettings.result`, requests 69 and 70 → `ModsSection.pickedFolder` / `pickedZip`), and for
code mods starts the build with mods (`dev.peanuts.build.ModBuildPipeline`) and shows its state. A second hook, at the top of
`MainActivity.launch`, asks `ModsSection.packToPlay` for the pack of every start: the variant's while code mods are on and built, the normal one when no
code mod is on. While code mods are on and their build is not ready, the start does not happen: `MainActivity.modsNotReady` starts the build when it is due
(`ModsSection.due`), says how far it is (`ModsSection.waitText`) and offers Play without mods after a warning (issue #514). `FrontendLaunch.play` hands such a
game to Home's Play.
A game without a pack gets one row, "Build the game first". Both sections draw upstream's rows through `SectionUi` (reflection onto `Ui`).

## 2. AndroidManifest.xml — the build service (one element)

File: `android/app/src/ondevice/AndroidManifest.xml` (merged over main's in every APK; it already declares FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC and
POST_NOTIFICATIONS). Inside `<application>`, after the PackService element:

```xml
        <service android:name="dev.peanuts.build.BuildService" android:exported="false" android:foregroundServiceType="dataSync" />   <!-- PEANUTS: the library's build service -->
```

The service runs in the launcher's process (no `android:process`), so the game sheet's listener is called directly; the packer it starts (`PackService`) stays in `:pack`.

## 3. android/app/.gitignore — the `dev.peanuts.build` package is ignored (one line)

`android/app/.gitignore` line 2 is `build/`, which matches `src/main/java/dev/peanuts/build/` too (git check-ignore confirms), so the pipeline's sources
would never be committed. Either change that line to anchor it at the module root:

```
/build/
```

or add after it:

```
!src/main/java/dev/peanuts/build/
```

(or `git add -f` the folder once; the files then stay tracked). The session owner picks; nothing in the pipeline depends on which.

## 4. Haptics and motion inside a game's page (twelve one-line hooks, 2026-11-14)

The page a held cover opens is upstream's (`GameSettings`, `MainActivity.gameTab`, the widgets of `Ui`, the dialogs of `PadFocus.Builder`). Its feedback is
`dev.peanuts.ui.Feel`, the launcher's own vocabulary (`dev.peanuts.ui.Haptics` / `Motion`; the same two rows of Settings › Library switch it), reached from one
marked line per widget kind:

| Hook (file, place) | Call | What the user gets |
|---|---|---|
| `Ui.java`, `Ui.Setting(...)` | `view.setOnClickListener(dev.peanuts.ui.Feel.row(l))` | a row's tap or A: TICK, and the row dips (SNAP) |
| `Ui.java`, `Ui.Setting.toggle` | `dev.peanuts.ui.Feel.toggled(b, x)` first in the change listener | a switch: CLICK on, TICK off (not when code sets it) |
| `Ui.java`, `Ui.button` | `b.setOnClickListener(dev.peanuts.ui.Feel.button(l))` | a button: CLICK, and it dips |
| `Ui.java`, `Ui.Segmented` | `dev.peanuts.ui.Feel.picked(v)` before `select(i)` | Recommended / Custom: CLICK |
| `Ui.java`, `Ui.navItem` | `t.setOnClickListener(dev.peanuts.ui.Feel.nav(l))` | a rail entry (wide window): CLICK, and the section's pane enters |
| `PadFocus.java`, `Builder.create()` | `dev.peanuts.ui.Feel.dialog(d)` | a choice picked in a dialog's list: CLICK |
| `PadFocus.java`, `Builder.setPositiveButton` (override) | `dev.peanuts.ui.Feel.confirm(l)` | a confirm button with a listener (Delete, Remove, Reset): CLICK; an OK without one, and Cancel: nothing |
| `MainActivity.java`, `gameSettings()` | `dev.peanuts.ui.Feel.page(pageRoot(), 0)` after `build()` | the page enters: what shows fades in and rises 8 dp (ENTER, the first three STAGGER apart) |
| `MainActivity.java`, `gameSettingsTop()` | `dev.peanuts.ui.Feel.tab(pageRoot(), 2, this::build)` in place of `build()` | a tab's tap, L1 / R1: CLICK, and what is under the tabs enters |
| `MainActivity.java`, `gameTab()` | `dev.peanuts.ui.Feel.added(files)` after the game-files rows are added | Storage › Game files: the rows fade in when they are read |
| `GameSettings.java`, `header()` | `dev.peanuts.ui.Feel.launch(v -> a.launch(g))` | Play: HEAVY, the dip, and Home's fade over the game |
| `GameSettings.java`, `radio()` | `dev.peanuts.ui.Feel.picked(x)` first in the choice listener | the three render rows' choices (a plain `AlertDialog`): CLICK |

Rules that keep it one word per press and cheap:

- A listener that has its word already (`Feel.launch`, `Feel.heavy`, `Feel.row`: instances of `Feel`'s own listener class) is not wrapped again by `Ui.button` or
  `Ui.Setting`. That is how Play and Build with mods are HEAVY and not HEAVY and CLICK. The Peanuts sections pass plain listeners to `SectionUi` for everything
  else and get the widget's word; what the widgets do not see they call themselves: the mod's bin (`Feel.row`), their plain `AlertDialog`s (`Feel.confirm`,
  `Feel.picked`), and the ends of their jobs (`Feel.accepted`, `Feel.refused`, `Feel.done`).
- The page's entrance runs for a page opened and a tab or rail section picked, never for a page built again after a choice (`gameSettingsAgain`) and never for
  a section's own `render()` (ModsSection during an install, a delete or a build; CompanionSection during a download). The one thing that fades in later in a
  section is CompanionSection's row of buttons, once, when the catalog arrives.
- Quiet on purpose, as in the proposal: Back and B, Cancel, a control that is off, the focus moving (D-pad), and the tab that shows already.
- Only alpha, translation and scale, a layer per piece only for the entrance; a piece is never taller than the window (a taller card's rows enter instead).
- Only in the launcher's process: `Ui` is also drawn in `:readiness`, `PadFocus.Builder` also in `:game` (the in-game text input); there the hooks do nothing.
- `PUi` (the copy of `Ui` for Peanuts' own screens) does not get these lines: those screens call `Haptics` themselves, and both would play.
