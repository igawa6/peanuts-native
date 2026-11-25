# ACNH 60 FPS + SpeedFix: third-party notice for this folder

The data in this folder is from **"ACNH 60 FPS + SpeedFix" v1.4.5 by tmouh** (https://github.com/tmouh/acnh-60fps), licensed under the
**Creative Commons Attribution-NonCommercial 4.0 International licence (CC BY-NC 4.0)**: https://creativecommons.org/licenses/by-nc/4.0/.
It is used here for the 60 fps choice of Animal Crossing: New Horizons 3.0.3 (Game settings, Frame rate cap), which Peanuts Native offers free of charge.
Thanks to tmouh.

What is here, and how it was changed (the mod's content is unchanged in each case; only its form is):

| File | From the mod | Change |
|---|---|---|
| `main.tsv` | the Switch build's code patch `atmosphere/exefs_patches/ACNH_60FPS_SpeedFix/FF1D1C05670DB6021C85B624A710B963.ips` (the "SpeedFix", for main build `ff1d1c05670db6021c85b624a710b963`) | **converted**: each of its 6,810 records is one row of the rewriter's cut form, `<image offset> <original bytes> <new bytes>`. The offset is the IPS offset less the NSO header's 0x100 bytes; the new bytes are the record's, unchanged; the original bytes are the game's own at that offset, added so that the rows apply to that one build and nothing else (`dm/tools/ips_rows.py`). The comment lines are the mod's own descriptions from its emulator build's `speedfix_main.pchtxt`, whose rows are the same bytes as the IPS. |
| `romfs.tsv` | the Switch build's game data `romfs/Bcsv/ItemNpcFtrActionParam.bcsv`, `romfs/Bcsv/ItemNpcWherearenFtrActionParam.bcsv`, `romfs/Bcsv/NpcInterest.bcsv` and `romfs/Pack/StaticParam.pack` | **converted**: only the bytes the mod changed, as rows of `<offset> <original bytes> <new bytes>` against the game's own 3.0.3 files, with each original file's size and SHA-256 and each modded file's (`dm/tools/romfs_rows.py`). No game file is in this folder: the app applies the rows to the player's own files when it builds the game, and refuses a file of another version. Applied to the 3.0.3 files, the rows give the mod's four files byte for byte. |

Not used: the mod's `sdk` patch (`A226048605D3597F90CB1067D141CD36647B90CD.ips`, the swap interval 2 to 1 in NVN code the app replaces; the app presents one
frame a display tick instead, `DM_PRESENT_INTERVAL=display`), and its patches for main builds of 3.0.0, 3.0.1 and 3.0.2.

The licence's NonCommercial term applies to this folder and to what is made from it: the data may not be used for commercial purposes. The rest of
Peanuts Native keeps its own licence (GPL-3.0-or-later, see the repository's README).
