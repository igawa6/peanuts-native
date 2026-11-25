# Pokémon: Let's Go, Eevee! (`0100187003A36000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 1.0.2 (`v131072`) over the base game, SDK 5.4.151. [Pokémon: Let's Go, Pikachu!](../010003F003A34000/README.md)'s twin: the same engine, and the same `sdk`, `subsdk0` (GLSLC) and `subsdk1` (movie) builds; only `main` is a build of its own. Everything in Pikachu's note about the engine, its files, shaders, controllers and memory holds here; this note gives only what differs or was checked again. The profile is Pikachu's with Eevee's title, name, IPA name, build row and shader tables.

**Status (current): runs the new-game intro on Android with Turnip.** On an AYN Thor (Adreno 740, Android 13) with Turnip T30 (26.3.0) it plays the intro at 30 fps and draws its characters and Pokémon correctly; the log has build ID `5831EC64…`, `hid supported controller styles 0x5a` and no `[RED]` line beyond Pikachu's. Not run on Qualcomm's driver, where Pikachu draws its 3D scene black ([Pikachu, Found on this game](../010003F003A34000/README.md#found-on-this-game)); the same is expected here.

## Builds

| Module | Build ID |
|---|---|
| `main` | `5831ec64d6b696fd69e69505821b97b1` (Pikachu's: `c208db6a…`) |
| `subsdk0` (GLSLC) | `7440b3aa6a8857b516f254b19b9ff184`, as Pikachu's |
| `subsdk1` (movie) | `89d8d937ddece47a93cccf87525a059c`, as Pikachu's |
| `sdk` | `1101700d4118f1fd1458391c671f81d5`, as Pikachu's |

## What it needs

The same keys as Pikachu's profile, for the same reasons ([Pikachu, What it needs](../010003F003A34000/README.md#what-it-needs)): `op_mode handheld` (`main` sets the supported styles `0x5a` at `main+0x1400bc`, after the first setup at `main+0x38cdc`), the 4 GB layout without system resource, and `mem_used 0x4600000` for the rewritten modules.

## Files and shaders

- The RomFS lists the same files with the same sizes as Pikachu's (27,818 files, 4.12 GB with 1.0.2); the survey's census of `main` differs by 4 instructions.
- `shader_split.py` over the base game and 1.0.2 found 11,156 shaders: 39 compute, 6,409 pixel, 4,708 vertex; by source `bin` 8,353, `main` 2,770, `systemresource` 33; 4,439 variation pairs, no `[RED]` line. Against Pikachu's: Pikachu's 8 keys (4 pixel, 4 vertex) from `bin/archive/buddy_mood_pikachu.gfpak` are not here, and 4 keys of Eevee's own partner are; every other key is the same.

## Open

- Play past the intro (Oak's lab, a save) on Turnip, and a run on Qualcomm's driver.
