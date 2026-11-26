<!-- PEANUTS: new profile, provisional -->
# FINAL FANTASY (Pixel Remaster) (`01000EA014150000`)

SQUARE ENIX CO., LTD. **Unity 2022.3.30f1**, 164 managed assemblies, rendering with **NVN**, 64-bit (`check-arch` printed `RESULT: ARCH64 programs=1`). SDK 19.2.2, build 9661, read from the NPDM. Modules `main`, `subsdk0` and `sdk`.

## Status

**Plays on the test app; the tables are still the generated ones.** `game.tsv` and the shader tables come from `scripts/build_ipa_from_nsp.py`, with `dm/games/profile_guess.py` and `shader_split.py`. Three entries in `game.tsv` were changed by hand: `romfs_marker`, the SDK version in the header comment, and the memory keys (see [Memory](#memory)). `check-profiles.sh` passes with the profile in place. One session ran on an AYN Thor (Snapdragon QCS8550 / Adreno 740, Android 13) as the test app `dev.nxnative.android.peanutstest`, on the Turnip 27.0.0-R2 driver, with the game at v262144: 63,883 frames in about 17.7 minutes, median 59.8 fps, p95 frame time 19.41 ms, 801 long frames, and no crash. Played: the opening, a town, the church, battles, the first boss, and a ship. On the system driver (Qualcomm Adreno, build 69e13475cb) four sessions of 25 seconds to 3.2 minutes ran without a crash at a median of 59.8 to 60.2 fps. The player saw graphical glitches there, as with other games on that driver; this profile does not address them, and graphics are correct on Turnip.

The packed version is 262144, from the update `FINAL FANTASY [01000EA014150800][v262144][Up v1.2.0_5].nsp`; its `.nup` verified `ok, 904 files, 0.59 GiB`. The base `.nsp` probes as `title 01000ea014150000 kind base version 0`, so the base dump is v0 and the profile carries no v0 build row (see [Open](#open)).

## Builds

| Module | Build ID |
|---|---|
| `main` | `6b0bd4f481c4114952b2702defc18bcd` |
| `subsdk0` | `c781dd63daf0ddcdb9975da50a198f93` |
| `sdk` | `2aef2cffb104ee7c9e212fd2f4c731e9` |

These are the generated survey's values, taken from the PE PDB GUID plus `dbiRVA`: the game ships no IL2CPP metadata, so the survey had no IL2CPP build id to take. The display version of `v262144` is 1.2.0 (the update is labelled `Up v1.2.0_5`).

## Shaders

- `shader_split.py` reported `split: shaders: 1655` with no `[RED]` line: 1,655 programs — 56 compute, 1 geometry, 801 pixel, 797 vertex.
- They come from 78 NVN shader containers in the RomFS totalling 478,093,834 bytes of chunked NVN packets, version 5. The RomFS holds 863 files (700 MiB); exefs is 1222 MiB.
- `shader_map.tsv` has 1,656 rows; `shader_facts.tsv` has 56 rows, the compute shaders' thread group and shared memory read from the control section. `shader_pairs.tsv` has 797 vertex/pixel pairs. `pso_pairs.tsv`, `pso_manifest.tsv` and `bf_want.tsv` are headers only, and the conflicts table is empty.
- No prewarm seed is recorded, so a first start makes its pipelines on the frame path ([Pipelines made on the frame path](../../../docs/reference/rendering.md#pipelines-made-on-the-frame-path-on-the-vulkan-replay)).

## Memory

The keys below are the generated survey's, changed by hand in `game.tsv` after the crashes recorded underneath.

| Setting | Value | Survey value |
|---|---|---|
| `mem_reserve` | `0x440000000` | `0x140000000` |
| `mem_alias` | `0x400000000` | `0x118000000` |
| `mem_stack` | `0x28000000` | `0x10000000` |
| `app_pool` | `0xCD500000` | same |
| `sys_resource` | `0x1000000` (read from the NPDM: 16 MB of system resource) | same |

Five sessions ended in a SIGSEGV within 5 minutes of play. Four faulted at `main+0x8080dc`: the game's allocator was given NULL for a 16 MiB block and wrote a block header at address `0xffffe0`, on an area load. The fifth was a null read at `main+0x910a90` after the game read `0xcdcdcdcd`, and was not analysed further. With `mem_alias` set to `0x400000000` and the stack region unchanged, one session ended at 464 s with an `nn::diag` abort in `nn::os::detail::StackGuardManager::MapAtRandomAddress`: a new 8 MB thread stack found no free place in the 256 MB stack region. The same abort and the same remedy, a larger stack region, are in [Super Monkey Ball Banana Rumble](../010031F019294000/README.md#memory). With `mem_alias` `0x400000000`, `mem_stack` `0x28000000` and `mem_reserve` `0x440000000`, one session of 63,883 frames ran with no crash. The game never calls `SetHeapSize`. Its memory comes from the alias region, which reached a high-water mark of +5.50 GB in that session, and it puts 8 MB thread stacks in the stack region. The layout rule is `mem_reserve = 0x10000000 + mem_alias + mem_stack + 0x8000000`.

## Found on this game

- An earlier provisional profile of this title was built from the **v0 base dump**: SDK 14.3.0, Unity 2019.3.15f1 / 2020.3.37f1 strings. Its split also found 1,655 programs, which is the one cross-check on this profile's shader count. It is a different game version, so its shader keys do not carry over to this profile's tables.
- The `romfs_marker` was changed from `Data/StreamingAssets/aa/Switch/ff6_battle_effect_assets_all.bundle`, a file that all six Pixel Remaster games ship, to `Data/StreamingAssets/aa/Switch/ff1_bgm_13_assets_all.bundle`, which is 2,296,018 bytes in this dump.

## Open

- One long session, on Turnip. A session longer than about 18 minutes was not run, and the crashes were timing dependent.
- The system driver shows graphical glitches. Other games show similar glitches on it, so they are outside this profile.
- The profile has one build row, `v262144`. The base `.nsp` probes as version 0, so a build packed from the base dump alone has no build row; the update must be in the build.
- The build IDs in the tables (above) are the survey's PE PDB values. The app reported game build id 6B0BD4F481C41149 for the first run, the first 8 bytes of the `main` row above.
