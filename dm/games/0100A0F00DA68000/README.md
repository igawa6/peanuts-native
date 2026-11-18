# Monster Sanctuary (`0100A0F00DA68000`)

<!-- PEANUTS: a Peanuts Native profile and note (Banana Native has none for this title). -->

Update 2.1.0 (v524288) over the base game (1.0.0, v0), SDK 9.4.1. **Unity 2018.4.23f1** (IL2CPP) with FMOD, rendering with **NVN**. Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. The closest profiles are [FINAL FANTASY CRYSTAL CHRONICLES Remastered Edition](../0100CE4010AAC000/README.md) (Unity 2018.4.2f1, SDK 8.2.1) and [Raging Loop](../010020400E1C2000/README.md) (Unity 2018.3.10f1).

**Status: provisional. Packed on a PC; it runs on an AYN Thor (the owner's play, 2026-11-18, with no measurements kept).** The profile is the survey's (`scripts/build_ipa_from_nsp.py` with `dm/games/profile_guess.py`); nothing in it is measured, and the game has not been booted anywhere, on a phone or on the Mac. It is not in any list of supported games.

The companion of eden-duo-companions (package `MonsterSanctuary`) is made for this build: its load plan is under `guest/8EA928B6AA10C84F9E69129C45BCAAAF…`, the `main` of v524288. Whether it loads was not checked.

## Builds

| Module | Build ID |
|---|---|
| `main` | `8ea928b6aa10c84f9e69129c45bcaaaf` |
| `subsdk0` | `fbb0b46fb4fdbf4be47b76f2285f53ed` |
| `sdk` | `290c9a04234847a176aa1c375c16af99` |

The display version is the update's control data (`control.nacp` +0x3060): 2.1.0. The base game's own `main` is `fbf5f63998f57b49…` (1.0.0); only the update was packed.

## What is guessed

| Setting | Where | Why it is a guess |
|---|---|---|
| `mem_reserve 0x140000000`, `mem_alias 0x118000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000` | [`game.tsv`](game.tsv) | `profile_guess.py`'s rule for a game whose NPDM declares a system resource and whose `main` sets a heap: the 5 GB block with the hardware pool. The heap check that would correct it runs on a Mac and was not run. The two Unity 2018 profiles above boot and play with the same values |
| `sys_resource 0x1000000` | `game.tsv` | read from the NPDM (16 MB), not a guess |
| `modules main subsdk0 sdk` | `game.tsv` | every module `main` imports from; `subsdk0` is the movie module |
| `romfs_marker Data/data.unity3d`, `zip_store` | `game.tsv` | the rule's: the largest RomFS file, the big file types |
| No `mem_used` | | the NSP script sets it after the rewrite only for a game without a system resource (`refine_after_rewrite`); for this one it leaves it to the heap check, which boots the game in the Mac host and was not run. The rewritten modules are 106 MB, more than the 64 MB the host reports as used by default, and `main` imports `nn::os::SetMemoryHeapSize`: if the game sizes its heap as pool − used memory, the request fails (`hle_svc.c`: `[mem] SetHeapSize … + modules … > app pool`). The value is then `mem_used 0x6e00000` (`profile_guess.mem_used` on the pack). The two Unity 2018 profiles above run without the key |
| No `depth_mode`, no `present_interval` | | the depth mode the game sets and its frame pacing were not seen. Raging Loop sets `depth_mode zero`; FINAL FANTASY CRYSTAL CHRONICLES Remastered Edition counts frames and keeps the default present interval |

## Files and shaders

- RomFS: 44 files, 0.27 GB: `Data/data.unity3d` (155 MB, the one UnityFS bundle, which holds the player's serialized files), 36 FMOD `.resource` banks (0.11 GB), `Data/Resources/unity default resources`, `Data/Managed`.
- `shader_split.py` found 500 shaders: 15 compute, 242 pixel, 243 vertex, with no `[RED]` line. By first appearance: 330 in `Data/data.unity3d:Resources/unity_builtin_extra`, 109 in `Data/Resources/unity default resources`, 50 in `data.unity3d:sharedassets0.assets`, 4 in `data.unity3d:globalgamemanagers.assets`, 2 in `data.unity3d:resources.assets`, and 5 GLSLC outputs in `main` (the player's own). One variation pair; `pso_pairs.tsv` has 234 legacy pass-input cover pairs.
- Packed on Linux from the `.nup` (`scripts/pack_android.py`, 53 s): 500 of 500 shaders translated, 523 SPIR-V modules, every one valid for `spirv-val --target-env vulkan1.3`, library 1.1 MB, pack 107.7 MiB, no `[RED]` line.

## Rewrite census

From the survey, before any rewrite:

- `main`: 12,414,277 instructions, 963 undecodable words, 18,671 `x18` uses.
- `sdk`: 1,378,343 instructions, 273 undecodable words, 11,300 `x18` uses, 60 `mrs tpidrro_el0`, 32 `mrs cntpct_el0`.
- `subsdk0`: 895,350 instructions, 314 undecodable words, 10,201 `x18` uses.

The rewriter took all three modules without an error.

## Open

- Everything a run would show: whether it boots, the memory layout, the depth mode, frame pacing, input, sound, saves, the companion.
- In the first run's log, two lines decide the memory keys: `SetHeapSize OOM: req … > cap …` (the heap region of 128 MB is too small: the heap check would move `mem_alias`) and `[mem] SetHeapSize … + modules … > app pool` (`mem_used`, above).
- SDK 9.4.1 is older than most profiles' (the survey's note: NVN object sizes, API version and service layouts differ by SDK). The host decides the shared font form and the NVN API version from the SDK version; no profile key was set for them here.
- `main` carries Nintendo's NEX and Pia network libraries (online play). Nothing of them was looked at.
- `bf_want.tsv` and `pso_manifest.tsv` are empty. No `patches.py`.
