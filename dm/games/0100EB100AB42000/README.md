<!-- PEANUTS: new profile, provisional -->
# FINAL FANTASY XII THE ZODIAC AGE (`0100EB100AB42000`)

Provisional profile, generated on 2026-11-24 by `scripts/build_ipa_from_nsp.py` from the survey of the user's
`.nup` (base `0100EB100AB42000` v0 plus update `0100EB100AB42800` v65536, 265 files, 25.21 GiB). Square Enix's
port of the Virtuos engine (PhyreEngine): the shaders sit in the 10.58 GB RomFS container `FFXII_TZA.vbf` and in
`main`, not in a top-level BNSH/BAHS. SDK 6.4.0, NVN. It has run on an Android test phone since 2026-11-24 (see Status).

## Status

Provisional. Tested on the Android test app (AYN Thor, Adreno 740). On Turnip the base (v0) build with this profile plays (cutscenes, battles, save and load, roaming, fast forward), and the update (v65536) build, which this profile is keyed to, draws its title screen (a 120 s run). On the Qualcomm system driver the base build reaches its frame loop, with the texture issues known from other games. The tables come from the PC split. `dual_want.tsv` lists the 74 pixel shaders that get a dual-source entry point; without it the library has no dual-source module and the update build shows a black screen at 30 fps.

## Builds

| build | display | main | subsdk0 | sdk |
|---|---|---|---|---|
| v65536 | 1.1.0 | `c2932c4d1c84ed7def7ceb1042b78951` | `b2bfdeddd143de2d821031fe668d097b` | `e29be8ad6778527aa248310d0c71a8a5` |

The base game (v0) is in the same `.nup` and is in the dump; its build IDs are not in `game.tsv` because the survey
and the split ran on the update, which is the version this profile is keyed to (`shader_versions v65536`). The base `main`'s build id is `598723C7A3D43935`. Modules:
`main`, `subsdk0` (NintendoSDK_movie), `sdk` — the profile loads `main subsdk0 sdk`.

## Shaders

`shader_split.py` on the update's RomFS and `main` split **6,454 shaders** (84 geometry, 5,999 pixel, 371 vertex);
sources: 3,403 in `gamedata`, 3,051 in `main`. **742 variation pairs**, 0 compute shader answers. 371 vertex shaders
pair with a cover of 2 pixel shaders (`003ce6f0fbd07b59`, `0004addcf11aa82b`).

- `gamedata/nvn/` holds 114 files (1.99 GB) — 72 `.fpo` programs and the shader-branch lists
  (`shaderbranchinfo.txt`, `shaderlist.txt`) under `gamedata/nvn/branch_shader/`.
- `FFXII_TZA.vbf` at the RomFS root (10.58 GB) is the Virtuos container; the split reads its LZ4 blocks.
- `shader_note` is the provisional note from `profile_guess.py`; the split's `shader_versions v65536` is the
  version the library is built for.

- `dual_want.tsv` lists 74 pixel shaders that `dmsh_build.py` gives a dual-source entry point (`MOD_DUAL`). Without it the
  library has no dual-source module and the replay logs `[RED] vk: a SRC1 blend factor with no dual-source module`, the
  second blend source reads as zero and the screen stays black while the game runs at 30 fps. The keys come from an
  earlier library built for this title (they are the shaders that write exactly colours 0 and 1), not from the host's
  `DM_NVN_DUAL_WANT`; a shader that is never blended this way only costs library size.

## Memory

`mem_reserve 0x100000000`, `mem_alias 0x10000000`, `mem_stack 0x10000000`, `app_pool 0xCD500000`, `sys_resource 0x0`.
`main` sets a heap of at most the app pool minus 64 MB (a 3328 MB heap region); the NPDM declares 0 MB system
resource, so `IsVirtualAddressMemoryEnabled` is false, as on Horizon.

## Found on this game

- SDK **6.4.0** (`NintendoSDK_NVN`, `libz`, `movie`, `nnSdk`, all 6_4_0). The survey's caution applies to every NVN
  object size, API version and service layout: the host was converted for SDK 12 and 16 sizes, and this is a 6.4.0
  game. Check them one by one per `docs/reference/nvn-objects.md`.
- RomFS: 128 files, 12.59 GB — `gamedata/nvn` 114 files (1.99 GB), `filesizetables/nx` 12 `.fst`, and the two root
  files (10.58 GB, `FFXII_TZA.vbf` plus one more). 40 `.mkv` movies: `zip_store` keeps `.mkv`, `.vbf` and
  `.metallib` uncompressed in the IPA.
- `main` imports 405 symbols the host's reference game does not (126 libc, 113 C++, 66 `nn::os`), including the
  `nv::InitializeGraphics` entry point; `subsdk0` is the movie module.
- `main.npdm` program id `0100EB100AB42000`; 2484520 instructions decoded in `main` (360 undecodable words).

## Open

1. **Update build roaming.** The update build was run for 120 s on Turnip (title screen); the longer play-tests used the base build. The system driver's texture issues (depth rendering) are not addressed here.
2. **SDK 6.4.0 NVN layout** is the first thing to check against the host's object sizes; the survey flags it.
3. **The `.vbf` container**: the split reads it, and it plays at runtime on Turnip, but it has not been looked at beyond that.
4. **No cover icon** in the repo: the icon lives in the base `.nsp`'s control data.
5. The base game's `subsdk0` and `sdk` build IDs and any shader differences from the update are unmeasured.
6. **`dual_want.tsv`** is taken from the dual-source modules of an earlier library built for this title, not noted by the host with `DM_NVN_DUAL_WANT`; it is probably a superset.
