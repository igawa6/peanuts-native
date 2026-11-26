# NSP direct: a game read from its dumps

Stage 1 of 2. The host can mount a game's RomFS, and its add-on content, straight from the player's NSP or XCI
dumps while the game runs, instead of from the `.nup` the importer writes. Nothing is copied: a file's bytes are
decrypted, patched and decompressed as the game reads them. This stage is native code and PC tests only. The app
does not use it yet (stage 2), and the build still takes the game's code from the `.nup`. The only way in is a
diagnostic switch in a game's env file ([Trying it on a device](#trying-it-on-a-device)). It was run on a device
once, on 2026-11-16: Mario Kart 8 Deluxe on an AYN Thor from its base and update NSPs (7,719 entries, 60 fps, loading
as from the `.nup`); the byte-for-byte checks are on a PC ([Tests](#tests)).

Why: a `.nup` is a second copy of the game (Echoes of Wisdom: 9.6 GB beside 6.0 GB of dumps, because the import
stores it decompressed), and the import takes minutes. Read where they are, the dumps are the only copy. The
price is CPU time per read: on a PC about twice the `.nup`'s on a plain game and about three times on a
compressed one, which is fractions of a second over a thousand megabytes.

## What it is

- `dm/peanuts/peanuts_nsp.c` builds the same file index a `.nup` package gives the host (`romfs_img`, the
  entries `dm/host/hle_fs.c` looks up, lists and sizes) from the RomFS of the game's program NCA. With an update
  among the files it is the update's RomFS: its patch (BKTR) over the base's, or the update's own when it carries
  a whole one. A file's entry has the flag `IMG_ENT_NSP`; its bytes come from the importer's section reader.
- The reader is the importer's (`dm/peanuts/import`: `nx_nca.c`, `nx_crypto.c`, `nx_lz4.c`), now also built into
  the Android host library. For run time it opens a dump by descriptor as well as by path, asks for no
  sequential read-ahead, and reads a range that does not start or end on a 16-byte block in one system call.
- A compressed section (Echoes of Wisdom) keeps decoded LZ4 entries in one cache for the process:
  `NX_SHCACHE_BYTES`, 16 MiB, in 8 shards of 128 entries with a lock each. A read that covers an entry whole
  decodes straight into the game's buffer and never enters the cache. A plain AES-CTR section has no cache.
- Once an image is open its state is only read, so any number of the game's threads read at once.
- Nothing is hashed at run time. The import does hash (every block against its NCA's hash layers, `dm/peanuts/import/nx_verify.c`); a damaged dump read this way shows as wrong bytes.

## The forms

`DM_ROMFS=nsp:<file>[|<file>...]`

The launcher sets no such `DM_ROMFS` and writes no `<index>.nsp` entry yet (stage 2); these are the forms the
host understands. Each `<file>` is a path, or `fd:<n>/<name>` for a descriptor the launcher opened (a dump picked with the system
file picker; `<name>` is only for messages). Which file is the base and which the update is read from the files,
not from their order. With several updates the highest version is used. A path cannot contain `|`. At most four
files.

`DM_NX_KEYS=<prod.keys>` names the keys file. A `title.keys` beside it is read too.

Add-on content: an entry `<index>.nsp` in `DM_AOC_DIR` is the add-on's dump, a link to it, or a link whose
target is `fd:<n>/<name>`. The host reads the add-on title whose ID ends in that index (inside the host this is
the spec `nsp:<file>|aoc/<index>`). It needs `DM_NX_KEYS` too.

`DM_ROMFS_OVERLAY` works as before: a mod's folder is looked up first, whatever the image under it. Saves, and a
`DM_ROMFS` that names a `.nup`, a RomFS image or a folder, are untouched.

The keys file is read into the game's process when the first dump is opened and stays loaded for the process's
life.

## What it does not read

The mount fails, and one red line in the log says why, for:

- no `DM_NX_KEYS`, a keys file that is not there, or keys that do not open the dumps
- an NSZ or XCZ (a compressed dump)
- a personalized ticket (a title key wrapped for one console)
- a section encrypted another way than AES-CTR or a patch's AES-CTR-EX (AES-XTS, types 5 and 6)
- dumps of another title than the running game (`DM_PROGRAM_ID`, else the profile's program ID)
- dumps of another version: the build ID of the dumps' `main` must be the running main module's
- an update that patches a base which is not among the files, and a sparse base without its update
- a host built without the reader (the Mac and iOS hosts): `nsp:` is refused, not taken for a folder

The game's RomFS failing to mount stops the game, as a `.nup` that does not open does. An add-on that cannot be
read is reported as not owned and the game runs without it.

## Trying it on a device

The game must be built as usual first (from its `.nup` or a folder). The launcher sets `DM_ROMFS` itself and
does not take a `DM_ROMFS` line from an env file, so the trial uses a diagnostic switch of its own that the host
reads in its place. Put two lines in `env-<title ID>.txt` in the app's files folder
(`/sdcard/Android/data/<package>/files/`, readable by the app: `chmod 666`):

```
DM_NSP_ROMFS=<base dump>|<update dump>
DM_NX_KEYS=<prod.keys>
```

Both paths must be ones the app can open: anywhere on shared storage once the app has its all-files access, or
its own folders. The app's copy of the keys is `files/keys/prod.keys` in its internal storage. The log then has
`fs dumps nsp:...: <n> entries, read where they are (base ... + update v...)` where it had `fs NUP ...`. Remove
the lines to go back. The companion screen keeps reading the `.nup` during such a trial (it follows `DM_ROMFS`).
Add-on dumps need a link in the game's `DM_AOC_DIR`, which only the launcher writes, and it writes none for a
dump yet (stage 2).

To record which bytes a game reads, for a replay on the PC, add `DM_FSLOG=<file>`: reads from the dumps are
logged like the others.

## Tests

On a Linux PC, with no game data: `cmake -S dm/peanuts/import -B <build> && cmake --build <build> && ctest
--test-dir <build>`. `nsp_test` writes its own dumps with made-up keys (plain and compressed bases, patches,
an XCI, add-ons with tickets, and the broken ones), reads them through the importer, through `hle_fs.c` beside
the `.nup` the importer makes of them, and through the companion's provider, and checks every refusal.

With a real game: `nsp_compare`, given `NSP_CMP_KEYS`, `NSP_CMP_BASE`, `NSP_CMP_NUP` and optionally
`NSP_CMP_UPDATE`, compares the two indexes and every byte both ways, then random ranges from several threads,
and prints the two read speeds. Without the variables it is skipped.

## Stage 2 (not built yet)

None of this exists; it is what the app would need before it could offer a game read from its dumps.

- The launcher: a game "read from its dumps" in the library and on the game's sheet, `DM_ROMFS=nsp:...` (paths,
  or descriptors of picked files, kept open for the game's life), `DM_NX_KEYS`, and `<index>.nsp` links for
  add-on dumps.
- The build without a `.nup`: the importer has no ExeFS-only export yet. The build needs the game's code
  (tens of megabytes) from the dump once.
- The companion's package lookup beside the dumps, and one image shared by the host's mount and the
  companion's provider (today each opens its own index over the same files; the cache is shared).
- A recorded trace from a real game on the device (`DM_FSLOG`) to settle the cache's size.
