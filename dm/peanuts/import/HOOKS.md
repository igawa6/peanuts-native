# libnximport — upstream hooks (one line each; the importer adds none of them itself)

## android/app/src/main/cpp/CMakeLists.txt

After the `add_subdirectory(... host ...)` line (outside the `foreach(g ${games})` loop: the importer is one library, not per game):

```cmake
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dm/peanuts/import ${CMAKE_CURRENT_BINARY_DIR}/nximport)   # PEANUTS: the importer (libnximport.so, dev.peanuts.importer.Native)
```

That is all the native side needs: the target is `nximport` → `libnximport.so` (arm64, 16 KB page aligned, links only `log`), picked up by the
APK like the other shared libraries in the module. The source list lives in this folder's CMakeLists.txt (`NXIMPORT_SOURCES`): a new file there
(nx_lz4.c, the compressed sections' LZ4 block decoder, 2026-11-12) needs no change upstream.

## android/host/CMakeLists.txt (the reader inside the host library, 2026-11-15)

The host reads a game's RomFS straight from its dumps at run time (`DM_ROMFS=nsp:...`, `dm/peanuts/peanuts_nsp.c`, `peanuts/docs/NSP-DIRECT.md`). For that
`nx_nca.c`, `nx_crypto.c` and `nx_lz4.c` are also compiled into `libnxhost.so` (the `NSP_C` list between `# PEANUTS begin`/`end`, with `DM_NSP_DIRECT` in the
host's definitions). Nothing of `nximport.c` (the `.nup` writer), `nx_zip.c` or the JNI goes there, and `libnximport.so` is unchanged. The host's copy is hidden
by its version script, so the two libraries do not meet. What the run-time reader uses of the internals: `nximport_keys`, `nx_container_open_rt` (by descriptor,
no read-ahead hint), `nx_container_titles`, `nx_nca_open`, `nx_nca_set_base`, `nx_sec_share` (the shared, locked cache of decoded LZ4 entries),
`nx_romfs_tree`, `nx_pfs0_tree`, `nx_sec_read_tc`. A new source file the reader needs must be added to `NSP_C` as well as to `NXIMPORT_SOURCES`.

## Compressed NCA sections (nx_nca.c, nx_lz4.c)

Newer titles (Echoes of Wisdom) compress their RomFS: the fs header's CompressionInfo @0x178 (table offset, size, a BucketTree header) names a table
of entries {virtual offset, physical offset, type 0 none / 1 zeros / 3 LZ4, physical size} over the IVFC data level, the layout of Atmosphère's
CompressedStorage as vendored by Eden (`src/core/file_sys/fssystem/fssystem_compressed_storage.h`, `fssystem_nca_file_system_driver.cpp`:
the compression layer sits above the hash layer; a patch's base is read raw, below the patch's own table). `nx_sec_read` decompresses
transparently (the hash layers before `data_off` stay raw, `nx_sec_read_raw` reads everything raw); the trees and the .nup writer need nothing.
Tests: `comp_test` (host build) dumps LZ4 blocks + the importer's bytes, `tests/comp_check.py` compares them with python's `lz4.block`;
`nximport_cli ivfc` checks a data level against its IVFC hashes (raw, so it holds for compressed sections too).

## Java side (this is the class the JNI binds to)

```java
package dev.peanuts.importer;
public final class Native {
    static { System.loadLibrary("nximport"); }
    public interface Progress { void on(String step, long done, long total); }
    public static native int    keys(String prodKeysPath);
    public static native String probe(String file);                                  // JSON array: [{"title_id","app_id","kind","version","name","publisher"}], null on failure
    public static native int    icon(String file, String titleId, String outPng);     // titleId null = the first title with an icon
    public static native int    writeNup(String out, String[] inputs, String baseTitleId, Progress cb);
    public static native int    firmwareFonts(String firmwareDir, String outDir);     // writes <outDir>/standard.ttf and nintendo_ext.ttf
    public static native String lastError();
    public static native void   setThreads(int n);                                    // optional (2026-11-12): writeNup's reader threads, 0 = automatic; see "Speed"
}
```

`writeNup` blocks for the whole write (minutes for a 6 GB game): call it from a worker thread (the foreground service of the build pipeline);
`Progress.on` is called on that same thread once per MiB with `step` one of `probe`, `base/exefs`, `base/romfs`, `update/<version>/exefs|romfs`,
`addon/<title id>/romfs`, `toc`, and `done`/`total` in bytes of the whole job (`total` is 0 during `probe`). A failed write removes the partial file.

## Speed (2026-11-12)

`writeNup` is a pipeline: `n` reader threads decrypt + decompress consecutive chunks (4 MiB, cut at LZ4 entry ends) in parallel, one thread hashes in
order, the calling thread writes in order and keeps every decision (extent places, equal-file sharing, the BKTR shortcut) in file order — the .nup is
byte-identical whatever `n`, and at most ~256 MiB of chunks is in flight. SHA-256 uses the CPU's SHA instructions (ARMv8 SHA2 / x86 SHA-NI; the hash
self-tests against FIPS vectors at start and falls back to the portable one). `n` defaults to the online CPUs capped at 6, or `NXIMPORT_THREADS` from
the environment; `Native.setThreads(int)` (JNI `Java_dev_peanuts_importer_Native_setThreads`, declare it in the Java class to use it — writeNup's
signature is unchanged) overrides both; `n <= 0` = automatic. `Progress.on` is still called on the calling thread, once per MiB.

## Keys

`keys()` reads the user's `prod.keys` (header_key, key_area_key_application/ocean/system_NN, titlekek_NN) and, when a `title.keys` sits beside
it, its title keys; tickets inside an NSP are used first. Nothing is shipped in the APK.

## Firmware font diagnostics (issue #22)

The font scan distinguishes an absent archive from a matching archive that it cannot decrypt. A font title also has a metadata NCA, so only
its RomFS-bearing NCA can establish the font's decryption failure. The scan preserves `key_why` for either required font and reports the missing
key name instead of discarding it behind "the shared font archives were not found". It never substitutes a different generation's key.

Checked with the same local keys: firmware 19.0.1, 20.5.0, 21.2.0 and 22.5.0 import all six fonts. Firmware 23.5.0's font data NCAs require
`key_area_key_application_16`; keys ending at generation `15` reproduce the original 238-NCA failure, now reported with that specific reason.
Retesting with matching generation `16` keys imports all six fonts from 23.5.0 on Linux and Android arm64. The same key set also imports
19.0.1, 20.5.0, 21.2.0 and 22.5.0; the extracted files match the earlier successful imports byte for byte. This does not establish that every
newer-firmware report has the same cause.

The scan now decodes the fonts before writing any installed file. Previously, a readable font could overwrite its installed version before
the other required archive failed to decrypt, leaving a mixture after a rejected import. A required archive that cannot be decoded now leaves
all installed font files untouched. The buffers are freed on both success and failure.

`font_test` uses invented fonts, NCAs and keys: successful extraction, either required font needing an unavailable generation, success after
adding that made-up generation, keeping both installed fonts unchanged after either missing-generation failure, and truly absent archives. It passes on Linux and Android arm64 (AYN Thor); no real keys or firmware are fixtures.
