// libnximport internals: keys, containers (PFS0/NSP, HFS0/XCI, a bare NCA), NCA sections (none/CTR/BKTR, compressed), PFS0 and RomFS trees, CNMT titles.
#ifndef NX_INTERNAL_H
#define NX_INTERNAL_H
#include <stddef.h>
#include <stdint.h>
#include "nx_crypto.h"
#include "nx_compat.h"

#define NX_CHUNK (1u << 20)   // 1 MiB: the progress callback's step and the extract buffer (the writer's pipeline chunks are PIPE_CHUNK in nximport.c)
#define NX_FS_ROMFS 0         // NCA fs header FsType
#define NX_FS_PFS0 1

void nx_err(const char *fmt, ...) __attribute__((format(printf, 1, 2)));   // sets nximport_last_error()
#define NX_FAIL(...) do { nx_err(__VA_ARGS__); goto fail; } while (0)

// ---- keys (nximport_keys)
typedef struct NxTitleKey { uint8_t rights[16], key[16]; } NxTitleKey;
typedef struct NxKeys {
    int loaded;
    uint8_t header_key[32]; int has_header_key;
    uint8_t kaek[3][32][16]; uint8_t has_kaek[3][32];   // [application, ocean, system][generation]
    uint8_t titlekek[32][16]; uint8_t has_titlekek[32];
    NxTitleKey *tk; int ntk;                             // title.keys
} NxKeys;
extern NxKeys nx_keys;
int nx_hex(const char *s, uint8_t *out, size_t n);       // exactly 2n hex digits → bytes; 0 ok

// ---- containers
typedef struct NxEntry { char name[0x100]; uint64_t off, size; } NxEntry;   // off: in the file
typedef struct NxContainer { int fd; char path[1024]; int kind; /* 0 nsp 1 xci 2 bare nca */ NxEntry *e; int n; NxTitleKey *tik; int ntik; } NxContainer;
int nx_container_open(NxContainer *c, const char *path);
void nx_container_close(NxContainer *c);
const NxEntry *nx_container_find(const NxContainer *c, const char *name);
int nx_pread_all(int fd, void *buf, size_t n, uint64_t off);   // 0 ok (short read = error)

// ---- NCA
struct NxNca;
typedef struct NxReloc { uint64_t virt, phys, end; uint32_t patch; } NxReloc;
typedef struct NxSubsec { uint64_t off, end; uint32_t ctr_val; } NxSubsec;
typedef struct NxBktr { NxReloc *r; uint32_t nr; NxSubsec *s; uint32_t ns; uint64_t total; uint64_t tables_off; } NxBktr;
// PEANUTS: a sparse section (the fs header's SparseInfo @0x148, Atmosphère SparseStorage): virtual ranges of the section read from the NCA body at phys_base + phys (patch = 0)
// or as zeros (patch = 1, the storage index), each decrypted with the counter of its virtual position; no entries: the whole section is zeros
typedef struct NxSparse { NxReloc *r; uint32_t nr; uint64_t phys_base; } NxSparse;
// a compressed section (the fs header's CompressionInfo @0x178, LibHac/Atmosphère CompressedStorage): entries of the data level, sorted by virtual offset
typedef struct NxCompEntry { uint64_t virt, end, phys; uint32_t psize; uint8_t type; /* 0 none 1 zeros 3 LZ4 */ } NxCompEntry;
// a reader's scratch for compressed sections: the last decompressed LZ4 entry (cached = its index in comp->e) and the compressed bytes' buffer.
// One per thread: nx_sec_read_tc(..., cache) with a thread's own; NULL uses the section's built-in one (single-threaded callers). Free with nx_comp_cache_free.
typedef struct NxCompCache { const struct NxComp *comp; uint8_t *out, *in; size_t outcap, incap; uint32_t cached; } NxCompCache;
typedef struct NxComp { NxCompEntry *e; uint32_t n; uint64_t total, table_off; NxCompCache cache; } NxComp;
void nx_comp_cache_free(NxCompCache *c);
typedef struct NxSection {
    int present, fs_type /* NX_FS_ROMFS 0, NX_FS_PFS0 1 */, hash_type, crypt_type /* 1 none 2 xts 3 ctr 4 bktr */;
    uint64_t off, size;          // in the NCA
    uint8_t ctr[16];             // the section's counter template (bytes 0..8; 8..16 come from the offset)
    uint64_t data_off;           // the PFS0 header's or the RomFS header's offset in the section
    NxBktr *bktr; struct NxNca *base; int base_sec;
    int compressed;              // the header has a compression table (loaded on the first read: for a BKTR section it lives in the patched bytes)
    NxComp *comp;
    NxSparse *sparse;            // PEANUTS: the sparse layer's table (nx_nca_open), below everything else
} NxSection;
typedef struct NxNca {
    int fd; uint64_t base_off, size; char id[0x41];
    uint8_t hdr[0xC00];
    uint64_t title_id; int content_type /* 0 program 1 meta 2 control 3 manual 4 data 5 public data */, key_gen, has_rights;
    NxAes key; int key_ok; char key_why[160];
    NxSection sec[4];
} NxNca;
int nx_nca_open(NxNca *n, const NxContainer *c, const NxEntry *e);
void nx_nca_close(NxNca *n);
int nx_nca_find_section(const NxNca *n, int fs_type);   // the first section of that type (an ExeFS is the lowest PFS0), -1 none
int nx_nca_set_base(NxNca *patch, int sec, NxNca *base, int base_sec);   // a BKTR section's base RomFS section; loads the tables
// the section's bytes: decrypted, virtual for BKTR, and for a compressed section the data level [data_off, data_off + the decompressed size) decompressed
// (the hash layers before data_off stay raw, so the trees' data_off-relative offsets hold for every section)
int nx_sec_read(NxNca *n, int sec, uint64_t off, void *buf, size_t len);
int nx_sec_read_tc(NxNca *n, int sec, uint64_t off, void *buf, size_t len, NxCompCache *cache);   // the same with a caller's (thread's) scratch; needs nx_sec_prepare first
int nx_sec_read_raw(NxNca *n, int sec, uint64_t off, void *buf, size_t len);   // the same without the decompression (the bytes the hash layer covers)
// Loads a compressed section's table now (it is otherwise loaded on the first read, which is not thread-safe). 0 ok; a plain section: nothing to do.
int nx_sec_prepare(NxNca *n, int sec);
// Chunking a read of [off, want_end) of a section (want_end <= limit): an end at or after want_end that does not split an LZ4 entry when one can be found
// before limit (so parallel readers never decompress one entry twice), else want_end. A plain section: want_end.
uint64_t nx_sec_chunk_end(NxNca *n, int sec, uint64_t off, uint64_t want_end, uint64_t limit);
// 1 when the virtual range of a BKTR section is one unbroken run of the base section's bytes (then *base_off is its start there), else 0
int nx_bktr_maps_to_base(const NxNca *n, int sec, uint64_t off, uint64_t size, uint64_t *base_off);

// ---- trees
typedef struct NxNode {
    char *name; int is_dir; struct NxNode **kids; int nkids, cap;
    uint64_t size; NxNca *nca; int sec; uint64_t off;   // a file: its bytes are section bytes [off, off + size)
    uint32_t id, name_off; uint64_t ext_off, ext_stored; int codec; uint8_t sha[32]; int shared, written;   // the .nup writer's fields
} NxNode;
NxNode *nx_node_new(const char *name, size_t nlen, int is_dir);
NxNode *nx_node_add(NxNode *dir, NxNode *kid);   // kids kept sorted by bytes (memcmp order; a repeated name is an error → NULL)
void nx_node_free(NxNode *n);
NxNode *nx_node_find(NxNode *root, const char *path);   // "a/b/c"
int nx_node_read(const NxNode *f, uint64_t off, void *buf, size_t len);
int nx_node_read_tc(const NxNode *f, uint64_t off, void *buf, size_t len, NxCompCache *cache);   // a thread's read (see nx_sec_read_tc)
NxNode *nx_pfs0_tree(NxNca *n, int sec);   // the files of a PFS0 section as one directory
NxNode *nx_romfs_tree(NxNca *n, int sec);  // the RomFS of a section (its root directory)
int nx_node_read_whole(const NxNode *f, uint8_t **out, size_t max);   // a small file in memory (malloc), -1 if bigger than max

// ---- LZ4 (nx_lz4.c): one block (no frame) into dst; the decoded length, -1 when malformed or not fitting
long nx_lz4_decompress(const uint8_t *src, size_t slen, uint8_t *dst, size_t dlen);

// ---- titles (CNMT)
typedef struct NxTitle {
    uint64_t title_id, app_id; int kind /* 0 base 1 update 2 dlc */; uint32_t version;
    char program[33], control[33], data[33];   // NCA ids (hex, "" none)
    const NxContainer *c; const NxEntry *meta;
    char name[256], publisher[256];
} NxTitle;
int nx_container_titles(NxContainer *c, NxTitle **out, int *count);   // every CNMT in the container (malloc'd array)
int nx_title_control(const NxTitle *t, NxNca *control);               // opens the control NCA (-1 none)
#endif
