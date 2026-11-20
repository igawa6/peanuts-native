<!-- PEANUTS: title profile, not a release compatibility claim. -->
# The Legend of Heroes: Trails beyond the Horizon

The profile covers Switch update **1.0.6 (v131072)** only. Its `main`, movie library (`subsdk0`) and `sdk` build IDs are in [game.tsv](game.tsv). The base executable is not registered.

Prepare needs the **merged base and update**: the update supplies the executables, but `asset_shader.p3a` resides in the base RomFS. The splitter reads the P3A member index and decodes the named `.nvn` entries. Of the map's 10,320 keys, 10,302 come from the archive and 18 from `main`. The vertex/pixel pairs cover translation inputs; they are not observed draw calls.

Startup reserves a `0x340800000`-byte virtual arena at `main+0xa2ec0`. This does not fit in a 2.625 GiB alias region. The profile provides a 16 GiB alias region while retaining a separate 1,920 MiB heap budget. Reserving this address space does not allocate 16 GiB of physical RAM.

**Verification boundary:** rendering, player control and save/load have not been checked on this branch. To check rendering, start an English New Game on Normal and inspect the rocket-launch scene for overlapping glyphs or a black lower image. For an input checkpoint, select Attack in the first Elder Wyvern command battle and check for damage and a party turn change. The profile is not on the release roster.

The profile can be selected for a test build with `-PnxGames=01007B102141A000`; use the [test app instructions](../../../peanuts/docs/NEW-GAME.md#6-a-test-app) rather than replacing a release installation.
