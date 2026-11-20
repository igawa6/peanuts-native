// libnximport — Peanuts Native's importer (peanuts/DESIGN-library.md "Importer C API"). Plain C11, no C++, no OpenSSL.
// Reads the public Switch formats (PFS0/NSP, XCI/HFS0, NCA with the header key, key areas and title keys, PFS0/RomFS sections,
// BKTR patch RomFS, CNMT, NACP) and writes a .nup container the host reads (dm/host/nup_image.h; SPEC 1.0-draft).
// Every function returns 0 on success and -1 on failure with a reason in nximport_last_error() (thread-local).
#ifndef NXIMPORT_H
#define NXIMPORT_H

#ifdef __cplusplus
extern "C" {
#endif
#define NXIMPORT_API __attribute__((visibility("default")))   // the library is built with -fvisibility=hidden: only these and the JNI are exported

typedef struct NxImportInfo {
    char title_id[17];      // 16 lower-case hex digits
    char app_id[17];        // the base application's title id (= title_id for a base)
    int kind;               // 0 base, 1 update, 2 dlc
    unsigned version;       // the CNMT version (an update's NCM version, e.g. 131072)
    char name[256];         // from the control NACP (English, else the first language); "" when there is no control
    char publisher[256];
    unsigned long long size;     // the title's content as the .nup will hold it, about: its program and data NCAs' sizes (from the CNMT: the uncompressed size)
    unsigned long long unpack;   // of that, the bytes of solid NCZs (an .nsz's): unpacked into a temporary file of this size while it imports
    unsigned long long check;    // and the bytes of block NCZs (an .xcz's mostly): read once more to check them, nothing written
} NxImportInfo;

// Loads header_key, key_area_key_{application,ocean,system}_NN and titlekek_NN from a prod.keys; a title.keys beside it (rights id = key) is read too when present.
NXIMPORT_API int nximport_keys(const char *prod_keys_path);
// Every title (CNMT) in an NSP or XCI: an XCI/NSP can hold base + update + dlc. *count = titles found (at most max written).
NXIMPORT_API int nximport_probe(const char *nsp_or_xci, NxImportInfo *out, int max, int *count);
// The control NACP's icon (English, else the first) of that title → a PNG file. title_id may be NULL for the first title that has one.
NXIMPORT_API int nximport_icon(const char *file, const char *title_id, const char *out_png);
// The base of base_title_id and its updates and add-ons found in the inputs → one .nup (packages base, update/<version>, addon/<title id>).
// A base or update package gets a meta block (SPEC §5): its main.npdm, its control.nacp and display version, and in the base (else the first package) the
// icon, title and publisher, so the .nup shows its icon without the dumps it was made from (issue #158). Missing control data only leaves those records out.
// progress(step, done, total): step is "probe", "base/exefs", "base/romfs", "update/<v>/exefs", "update/<v>/romfs", "addon/<tid>/romfs", "toc"; done/total are bytes of the
// whole job (files are written in node-id order, so the steps of two packages interleave). A failed write removes the partial file.
// Compressed inputs (.nsz, .xcz: their .ncz entries) come first: steps "unpack/<file name>" (a solid NCZ, decompressed into a temporary file) and
// "check/<file name>" (a block NCZ, read once), done/total counting only those bytes; each rebuilt NCA's SHA-256 must match its name and its CNMT record.
// The temporary files lie in NXIMPORT_TMPDIR when set, else in the hidden folder ".<out file name>.unpack" beside out_nup, and are gone when this returns.
NXIMPORT_API int nximport_write_nup(const char *out_nup, const char **inputs, int n_inputs, const char *base_title_id,
                        void (*progress)(void *user, const char *step, long long done, long long total), void *user);
// The writer's reader threads (decrypt + decompress run in parallel; the .nup is the same whatever the count): n <= 0 = automatic (the NXIMPORT_THREADS
// environment variable if set, else the online CPUs capped at 6). Set before nximport_write_nup; about 256 MiB of buffers are in flight whatever n.
NXIMPORT_API void nximport_set_threads(int n);
// Stop: on != 0 makes a running nximport_write_nup end within about a second with -1 ("the import was stopped"), its partial output and temporary files
// removed; any thread may call it. The flag is cleared when that import returns, and by on = 0 (a caller sets that before an import starts, so a Stop
// meant for an earlier one does not stop it).
NXIMPORT_API void nximport_cancel(int on);
// The shared system fonts out of firmware (FontStandard 0100000000000811 → standard.ttf, FontNintendoExtended 0100000000000810 → nintendo_ext.ttf).
// firmware_dir is either a folder (*.nca files in it and up to two levels below — Eden's nand/system/Contents/registered layout, or a flat folder;
// a <id>.nca/00 folder too) or a .zip of the same NCAs flat inside it (nx_zip.c; stored or deflated members, ZIP64 sizes if the zip tool wrote them).
NXIMPORT_API int nximport_firmware_fonts(const char *firmware_dir, const char *out_dir);
// Checks a .nup (superblock, TOC SHA-256, bounds) and lists every file: path is "<package>/<exefs|romfs>/<path>" with the package named as a selector
// (base, update/<version>, addon/<title id>). entry may be NULL (check only); returning non-zero from it stops the listing (not an error).
NXIMPORT_API int nximport_verify_nup(const char *nup, int (*entry)(void *user, const char *path, unsigned long long size, unsigned long long offset, int codec), void *user);
// Reads a raw (codec 0) file of a .nup by its path as nximport_verify_nup lists it → out_file.
NXIMPORT_API int nximport_nup_extract(const char *nup, const char *path, const char *out_file);
NXIMPORT_API const char *nximport_last_error(void);

#ifdef __cplusplus
}
#endif
#endif
