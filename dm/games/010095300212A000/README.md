# Resident Evil Revelations 2 (`010095300212A000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.2 (v131072, January 2022), the last update. SDK 1.6.0. Capcom's MT Framework with NVN graphics. Modules `main`, `subsdk0` to `subsdk4` and `sdk`. The Switch release holds every episode and extra in the base game: there is no add-on content to load.

**Status (current): plays on Android.** On an AYN Thor (Adreno 740) it boots, passes the title, the save confirmation, the language screen and the main menu, plays the campaign and enters Raid mode, on Turnip (Balemuni Apex v2, PurpleVK T30). Not checked on the system driver yet. Not run on the Mac.

## Builds

| Module | Build ID |
|---|---|
| `main` | `d69bca484713baa11285cf91df0c0368` |
| `subsdk0` | `51b3e7996b61c8c5b3a253e89c5ba195` |
| `subsdk1` | `17a56a3f438a0053cdb360b37a77c603` |
| `subsdk2` | `d4da6f7b01db9fdb76786e46b24c797d` |
| `subsdk3` | `1686ae4b62a9c6a3b6ee7a9187ee4932` |
| `subsdk4` | `b43e2fdcf4b3fad2eb10e3680981223f` |
| `sdk` | `972cc2555ec0d6a03473f8c0b03e2413` |

## What it needs

| Setting | Where | Why |
|---|---|---|
| `mem_reserve`, `mem_alias`, `mem_stack`, `app_pool`, `sys_resource` | `game.tsv` | `dm/games/profile_guess.py`'s values from the survey; not measured |
| `shader_groups /nativeNXx64/=nativenxx64` | `game.tsv` | every shader package is in an `.arc` archive under `nativeNXx64` |
| the IR camera cut (`main+0xe34a4c`) | `patches.py` | SDK 1.6.0 links the IR sensor library into `main`, which connects to the `irs` service itself and crashes without it |
| the title step cut (`main+0x820adc`..`0x820ae8`) | `patches.py` | the title GUI never stops playing on the host: the screen stayed black after the press-any-button menu |
| the type-9 dialog cut (`main+0x614da4`..`0x614dbc`) | `patches.py` | the message after the language screen waits for a ready counter nothing sets on the host |

## Files and shaders

- The RomFS keeps its data in MT Framework `.arc` archives (version 7 with 0x50-byte entries, not The Great Ace Attorney Chronicles' 0x90) under `nativeNXx64`. Shader packages (`SPK`) are in 95 of them, each control section before its code area (`shader_split.py` `mtarc_members`, `mtspk_shaders`).
- `patch_nso.py` needed two more jump-table forms in `main` (`jump_masked_tbnz` at `main+0xc44180`, `jump_offsets_wide` at `main+0xd19b08`).
- `shader_map.tsv` is 84 MB, more than three times any other profile's: the shader library has 750,531 shaders. Worth a word with the maintainer before it is merged.

## Found on this game

- **The title and the dialogs wait for GUIs that never stop playing.** The title state machine (`main+0x820a78`) waits for the title GUI to stop (`main+0x6d93d0`); its timeline keeps waiting on a variable (`main+0xa4ec90`, type 3) and never ends on the host. The cut waits for the title GUI's own step instead (6 and on: the menu was confirmed). A host fix would cover the other GUIs.
- **Type-9 dialogs** close when a global counter (`+0x1bb0`, set by `main+0x6c5c60`) reaches their ticket. Nothing calls the setter on the host; the cut closes type-9 dialogs as they show and leaves the others (the Raid mode yes/no) alone.

## Open

- Skipping a cutscene: the pause menu opens, but its skip entry does nothing.
- A hair or a texture vanishes for a frame the first time a draw with a state the prepared parts lack appears (asynchronous pipelines); learned and prepared at the next start.
- Not checked on the system driver.
