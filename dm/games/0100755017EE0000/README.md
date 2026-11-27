<!-- PEANUTS: offline Android profile for updates 1.5.0 and 2.2.0. -->
# FANTASY LIFE i: The Girl Who Steals Time (`0100755017EE0000`)

Updates 1.5.0 (v524288) and 2.2.0 (v917504); Unreal Engine 4, NVN, SDK 18.3.7. The shader map is the union of both versions: 23,372 keys, including 2,687 vertex shaders ([Shaders](#shaders)). The opening uses dynamic texture slots. <!-- PEANUTS: #800 -->

## Builds

| Version | `main` | `subsdk0` | `sdk` |
|---|---|---|---|
| 1.5.0 (v524288) | `74bcac96835db9aeeb29bce78be410ea` | `8dceb03f9f04aac7a5d35a4ad83d4601` | `f9ed9d26b4ad414362affee1658db28a` |
| 2.2.0 (v917504) | `6e4502b822cb120486285ce14f16e490` | the same | the same |

2.2.0's display version is from the update's NACP. Its `subsdk0` and `sdk` are byte-identical to 1.5.0's; only `main` changed, so only the `main` cuts needed new addresses.

## Offline New Game

The offline host does not provide Epic Online Services. On startup the game subscribes to notifications through an absent interface; during New Game it asks for the platform handle and local Epic account ID. The [build-specific cuts](patches.py) skip the two subscriptions and make both getters return null. They do not create an account or enable network play.

When the host reports dictionary version zero, the game's avatar form rejects ordinary names before running its two word checks. The final cut leaves both checks reachable when there is no dictionary version. On an Odin 2 with Turnip v27.0.0-R1, the 1.5.0 build accepted a name, started a new game, and allowed movement and conversations on the opening ship. Cold-loading the resulting save also reached the ship. Later quests, multiplayer, and the system GPU driver were not tested.

<!-- PEANUTS: #800 -->
### 2.2.0

`nso_fnmap.py` cannot carry these addresses: both `main` builds have unwind entries for only 18,414 functions, and none of the five cut functions is among them. Each 1.5.0 site was located in 2.2.0 instead by its ±0x40-byte instruction window, with the address bits masked as `nso_fnmap.py` masks them; each window occurs once in each build. Each site was then compared by disassembly:

| Cut | 1.5.0 | 2.2.0 | Check |
|---|---|---|---|
| skip `EOS_UI_AddNotifyDisplaySettingsUpdated` | `+0x5a9f064` | `+0x5e1c984` | the same `cbz x8` before the platform getter and `EOS_Platform_GetUIInterface`; the 508-byte function is identical after masking |
| no identity-interface subscription | `+0x5ad9288` | `+0x5e5bb50` | the same call in the same startup sequence; its 1,756-byte callee is identical after masking |
| no EOS platform handle | `+0x5a9f190` | `+0x5e1cab0` | the getter's `sub sp, sp, #0x40` prologue; the 308-byte function is identical |
| no Epic account ID | `+0x5a9f680` | `+0x5e1cfa0` | the getter's `sub sp, sp, #0x50` prologue; the 356-byte function is identical |
| avatar name dictionary version | `+0x5b3bca0` | `+0x5ec5710` | the `cbz w0` after `nn::ngc::ProfanityFilter::GetContentVersion`, before `MaskProfanityWordsInText` |

1.5.0's getters branch to the `mov x0, xzr; ret` at `+0x5a902a8`. That function changed in 2.2.0, so its tail no longer matches. 2.2.0's getters branch to the same two instructions at `+0x5e0cba0`, the tail of a function of the same shape (`tbnz w8, #0x1e` to them, else a tail call). The Android rewriter applied all five 2.2.0 cuts (`main.cuts.tsv`).

## Shaders

The tables are the union of 1.5.0 and 2.2.0 (`shader_versions v524288 v917504`, `package v917504`), written by `shader_split.py` from both versions' RomFS and `main` together. Compared with 1.5.0's RomFS (by the SHA-256 of each file), 2.2.0 changes `pakchunk0-Switch.pak` and 46 IoStore files. Those include all 18 containers the map names; only the two NROs are unchanged.

| Split | Keys | Compute | Geometry | Pixel | Vertex |
|---|---|---|---|---|---|
| 1.5.0 | 14,962 | 4,391 | 4 | 8,352 | 2,215 |
| 2.2.0 | 17,440 | 4,990 | 4 | 9,975 | 2,471 |
| union | 23,372 | 5,382 | 4 | 15,299 | 2,687 |

The versions share 9,030 keys. 1.5.0's rows are unchanged and untagged. The 8,410 keys only 2.2.0 has follow them, tagged `/v917504/`. The 5,932 keys only 1.5.0 has are listed in `shader_optional.tsv`, so a dump of either version may lack the other's shaders and nothing else. `shader_facts.tsv` (5,382 rows) and `shader_pairs.tsv` (2,687) are the unions of both splits. `shader_files.tsv` is unchanged: 2.2.0's files are 1.5.0's less `pakchunk20-Switch.utoc`, where 2.2.0 has no shader.

The containers are Oodle-compressed. A 2.2.0 split on an AYN Thor, with the game's own decoder, produced the same four tables byte for byte as the split on a computer with the ooz worker (`DM_OODLE_OOZ`). That computer split of 1.5.0 reproduced the previous 1.5.0 tables row for row: only the map's first comment line differs. The union was made with the same worker. With `DM_SPLIT_TARGETED=1`, each version's targeted split matches the union profile (`its tables match the profile for v524288` / `v917504`). Without these tables, a 2.2.0 Prepare stopped with 5,932 of 14,962 shaders missing, and Try anyway would have built only the 9,030 shared keys.

## 2.2.0 on Android

On an AYN Thor (Adreno 740) with Turnip v27.0.0-R2 (`DM_VK_DRIVER`), Prepare of a 2.2.0 dump passed without Try anyway. Its targeted split matched the profile for v917504, and the library holds 17,440 of 17,440 shaders. The rewrite log shows all five cuts. In a separate save, the run went from the language and brightness screens to the title (Ver.2.2.0) and New Game. The avatar form accepted the name Rowan, and the opening cutscene played. Rowan then walked across the opening ship at about 59 fps, with the "Talk to everyone" objective showing. The thin green line across the deck is the beam from the fossil's crystal that the opening cutscene shows, not a rendering fault. Later quests, multiplayer, a cold load of the 2.2.0 save and the system GPU driver were not tested.
