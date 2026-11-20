// libnximport: NCZ, an NCA compressed with zstd, the content of an NSZ (a PFS0) or an XCZ (an XCI); nx_internal.h "NCZ". The importer only: the
// run-time reader (dm/peanuts/peanuts_nsp.c) still refuses compressed dumps.
// The layout, as nsz writes it (github.com/nicoboss/nsz, docs/formats.md and nsz/Decompressor.py; MIT), written here from that description:
//   0x0000  the NCA's first 0x4000 bytes as they are (its header, encrypted)
//   0x4000  "NCZSECTN", u64 count, then count entries of 0x40: u64 offset, u64 size (a range of the NCA), u64 crypto type, u64 0, the key (16), the
//           counter (16, its first 8 bytes used). Types 3 (AES-CTR) and 4 (AES-CTR-EX) were decrypted and are encrypted again on the way out with that
//           key and counter (the low 8 bytes are the position >> 4, big-endian); any other type is stored as it was.
//   then    either "NCZBLOCK" (u8 version 2, u8 type 1, u8 0, u8 block size exponent, u32 block count, u64 body size) and a u32 stored size per block,
//           then the blocks (one stored at its full size is raw, a smaller one is a zstd frame); or one zstd stream to the end of the file (solid).
// The body is the NCA from 0x4000 on, decrypted: NCA byte x is body byte x - 0x4000. The ranges follow each other without a gap to the NCA's end; one that
// starts below 0x4000 has its first bytes in the header copy only, and the bytes from 0x4000 to a first range that starts later are plain.
#define _FILE_OFFSET_BITS 64
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "nximport.h"
#include "nx_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// zstd's decompressor (dm/third_party/zstd/zstddeclib.c, compiled into the importer): the calls used
typedef struct ZSTD_DCtx_s ZSTD_DCtx;
typedef struct ZSTD_inBuffer_s { const void *src; size_t size; size_t pos; } ZSTD_inBuffer;
typedef struct ZSTD_outBuffer_s { void *dst; size_t size; size_t pos; } ZSTD_outBuffer;
ZSTD_DCtx *ZSTD_createDCtx(void);
size_t ZSTD_freeDCtx(ZSTD_DCtx *d);
size_t ZSTD_DCtx_setParameter(ZSTD_DCtx *d, int param, int value);
size_t ZSTD_decompressDCtx(ZSTD_DCtx *d, void *dst, size_t cap, const void *src, size_t n);
size_t ZSTD_decompressStream(ZSTD_DCtx *d, ZSTD_outBuffer *out, ZSTD_inBuffer *in);
unsigned ZSTD_isError(size_t code);
const char *ZSTD_getErrorName(size_t code);
int ZSTD_getErrorCode(size_t code);
enum { ZSTD_d_windowLogMax = 100, ZSTD_error_frameParameter_windowTooLarge = 16 };

enum {
    NCZ_HEAD = 0x4000,
    NCZ_MAXSEC = 0x10000,      // ranges: one per BKTR subsection makes thousands in an update; more is no NCZ of a game
    NCZ_WINLOG = 27,           // a solid stream's zstd window: 128 MiB at most (nsz's levels make 8 MiB to 128 MiB; more would not fit an 8 GB phone's import)
    NCZ_BEXP_MIN = 9, NCZ_BEXP_MAX = 24,   // block sizes 512 B to 16 MiB (nsz writes 16 KiB to 4 GiB, 1 MiB by default; two blocks per reader thread are held)
    NCZ_SLOT = 4 << 20,        // the unpack's and the check's unit
    NCZ_RING_BYTES = 64 << 20  // the check's blocks in flight at most
};

typedef struct Rng { uint64_t off, end; uint32_t crypt; int key; uint8_t ctr[8]; } Rng;   // a range of the NCA; key: its index in keys

struct NxNcz {
    NxStore st;                        // first: the store's callbacks get the NCZ back from it
    int fd; uint64_t at, size;         // the NCZ in its container: the file, where it begins, its size
    char name[0x100], src[1024];       // its entry's name and its container's path (messages)
    uint64_t nca;                      // the rebuilt NCA's size
    Rng *r; uint32_t nr;
    uint8_t (*keys)[16]; NxAes *aes; int nkeys;
    int solid; uint64_t data;          // where the stream (solid) or the first block (block) begins, from the NCZ's start
    uint32_t bexp, nblk; uint64_t bsize; uint64_t *boff; uint32_t *bst;   // block: each block's offset (from the NCZ's start) and stored size
    int tmp; char tmp_path[1200];      // solid, once unpacked: the body in a temporary file (tmp_path: still to delete, where a file cannot go while open)
    uint64_t uid;                      // the block caches' key: a closed NCZ's blocks are never taken for another's
};

static uint64_t u64le(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static uint32_t u32le(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static const char *leaf(const char *p) { const char *s = strrchr(p, '/'), *b = strrchr(p, '\\'); if (b && (!s || b > s)) s = b; return s ? s + 1 : p; }
static _Atomic uint64_t next_uid = 1;

// ---------------------------------------------------------------- the tables
static const Rng *find_rng(const NxNcz *z, uint64_t off) {   // the range holding NCA offset off, NULL outside every range
    uint32_t lo = 0, hi = z->nr;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (z->r[m].end <= off) lo = m + 1; else hi = m; }
    return lo < z->nr && z->r[lo].off <= off ? &z->r[lo] : NULL;
}

static uint64_t blk_len(const NxNcz *z, uint32_t i) { return i + 1 < z->nblk ? z->bsize : (z->nca - NCZ_HEAD) - (uint64_t)(z->nblk - 1) * z->bsize; }

static int st_raw(NxStore *s, uint64_t off, void *buf, size_t len);
static int st_plain(NxStore *s, const uint8_t key[16], const uint8_t tmpl[16], uint64_t off, void *buf, size_t len);
static uint64_t st_unit_end(NxStore *s, uint64_t off);

#define ZFAIL(...) do { nx_err(__VA_ARGS__); goto fail; } while (0)

int nx_ncz_open(NxNcz **out, const NxContainer *c, const NxEntry *e) {
    *out = NULL;
    NxNcz *z = calloc(1, sizeof *z); uint8_t *tab = NULL;
    if (!z) { nx_err("out of memory"); return -1; }
    z->fd = c->fd; z->at = e->off; z->size = e->size; z->tmp = -1; z->uid = atomic_fetch_add(&next_uid, 1);
    snprintf(z->name, sizeof z->name, "%s", e->name); snprintf(z->src, sizeof z->src, "%s", leaf(c->path));
    { struct stat st; if (fstat(z->fd, &st) || (uint64_t)st.st_size < e->off || e->size > (uint64_t)st.st_size - e->off)
        ZFAIL("%s: %s lies outside the file: the file is cut off", z->src, z->name); }
    if (e->size < NCZ_HEAD + 0x10) ZFAIL("%s: %s is too small for a compressed NCA: the file is cut off or damaged", z->src, z->name);
    uint8_t h[0x10]; if (nx_pread_all(z->fd, h, sizeof h, z->at + NCZ_HEAD)) goto fail;
    if (!memcmp(h, "NCZSECT2", 8)) ZFAIL("%s: %s uses a newer NCZ layout (NCZSECT2) that cannot be read here: compress the game again with nsz", z->src, z->name);
    if (memcmp(h, "NCZSECTN", 8)) ZFAIL("%s: %s is not a compressed NCA (no NCZSECTN at 0x4000): the file is damaged", z->src, z->name);
    uint64_t n = u64le(h + 8);
    if (!n || n > NCZ_MAXSEC || NCZ_HEAD + 0x10 + 0x40 * n > e->size) ZFAIL("%s: %s has a bad range table (%llu ranges): the file is damaged", z->src, z->name, (unsigned long long)n);
    if (!(tab = malloc((size_t)(0x40 * n))) || !(z->r = calloc((size_t)n + 1, sizeof *z->r)) || !(z->keys = calloc((size_t)n, 16))) ZFAIL("out of memory");
    if (nx_pread_all(z->fd, tab, (size_t)(0x40 * n), z->at + NCZ_HEAD + 0x10)) goto fail;
    for (uint64_t i = 0; i < n; i++) {
        const uint8_t *t = tab + 0x40 * i; uint64_t off = u64le(t), size = u64le(t + 8), type = u64le(t + 0x10);
        if (type == 2) ZFAIL("%s: %s has an AES-XTS range, which no game's content uses: the file is damaged", z->src, z->name);
        if (type == 0 || type > 6) ZFAIL("%s: %s has a range of unknown encryption type %llu: the file is damaged", z->src, z->name, (unsigned long long)type);
        if (!size) continue;
        if (off > UINT64_MAX - size) ZFAIL("%s: %s has a bad range table: the file is damaged", z->src, z->name);
        if (!z->nr && off > NCZ_HEAD) { Rng *g = &z->r[z->nr++]; g->off = NCZ_HEAD; g->end = off; g->crypt = 1; }   // the plain bytes before the first range
        if (z->nr && z->r[z->nr - 1].end != off) ZFAIL("%s: %s has a gap or an overlap in its range table at %#llx: the file is damaged", z->src, z->name, (unsigned long long)off);
        if (z->nr == n + 1) ZFAIL("%s: %s has a bad range table: the file is damaged", z->src, z->name);
        Rng *g = &z->r[z->nr++]; g->off = off; g->end = off + size; g->crypt = (uint32_t)type; memcpy(g->ctr, t + 0x30, 8); g->key = -1;
        if (type == 3 || type == 4) {
            int k = 0; while (k < z->nkeys && memcmp(z->keys[k], t + 0x20, 16)) k++;
            if (k == z->nkeys) memcpy(z->keys[z->nkeys++], t + 0x20, 16);
            g->key = k;
        }
    }
    if (!z->nr || z->r[z->nr - 1].end <= NCZ_HEAD) ZFAIL("%s: %s has no compressed bytes: the file is damaged", z->src, z->name);
    z->nca = z->r[z->nr - 1].end;
    if (z->nkeys && !(z->aes = calloc((size_t)z->nkeys, sizeof *z->aes))) ZFAIL("out of memory");
    for (int k = 0; k < z->nkeys; k++) nx_aes_init(&z->aes[k], z->keys[k]);
    uint64_t p = NCZ_HEAD + 0x10 + 0x40 * n; uint8_t b[0x18] = {0};
    if (p + 8 <= e->size && nx_pread_all(z->fd, b, 8, z->at + p)) goto fail;
    if (p + 8 <= e->size && !memcmp(b, "NCZBLOCK", 8)) {
        if (p + 0x18 > e->size || nx_pread_all(z->fd, b, 0x18, z->at + p)) ZFAIL("%s: %s: its block table is cut off", z->src, z->name);
        uint32_t ver = b[8], type = b[9], bexp = b[11], nblk = u32le(b + 12); uint64_t body = u64le(b + 16);
        if (ver != 2 || type != 1) ZFAIL("%s: %s uses block layout %u.%u, which cannot be read here: compress the game again with nsz", z->src, z->name, ver, type);
        if (bexp < NCZ_BEXP_MIN || bexp > NCZ_BEXP_MAX) ZFAIL("%s: %s uses %s blocks, more than this importer reads (16 MiB): compress the game again with a smaller block size", z->src, z->name, bexp > 30 ? "very large" : bexp > 20 ? "large" : "small");
        z->bexp = bexp; z->bsize = 1ull << bexp; z->nblk = nblk;
        if (body != z->nca - NCZ_HEAD || nblk != (body + z->bsize - 1) / z->bsize) ZFAIL("%s: %s: its block table does not match its range table: the file is damaged", z->src, z->name);
        uint64_t t0 = p + 0x18;
        if (t0 + 4ull * nblk > e->size) ZFAIL("%s: %s: its block table is cut off", z->src, z->name);
        if (!(z->bst = malloc(4ull * nblk + 4)) || !(z->boff = malloc(8ull * nblk + 8))) ZFAIL("out of memory");
        if (nx_pread_all(z->fd, z->bst, 4ull * nblk, z->at + t0)) goto fail;
        uint64_t at = t0 + 4ull * nblk;
        for (uint32_t i = 0; i < nblk; i++) {
            uint32_t st = u32le((const uint8_t *)&z->bst[i]); uint64_t want = blk_len(z, i);
            if (!st || st > want) ZFAIL("%s: %s: block %u claims %u bytes for %llu: the file is damaged", z->src, z->name, i, st, (unsigned long long)want);
            z->bst[i] = st; z->boff[i] = at; at += st;
        }
        if (at > e->size) ZFAIL("%s: %s is cut off: its blocks need %llu bytes, %llu are there", z->src, z->name, (unsigned long long)at, (unsigned long long)e->size);
        z->data = t0 + 4ull * nblk;
    } else {
        if (p >= e->size) ZFAIL("%s: %s is cut off: no compressed data after its tables", z->src, z->name);
        z->solid = 1; z->data = p;
    }
    z->st.size = z->nca; z->st.raw = st_raw; z->st.plain = st_plain; z->st.unit_end = st_unit_end;
    free(tab); *out = z; return 0;
fail:
    free(tab); nx_ncz_close(z); return -1;
}

void nx_ncz_close(NxNcz *z) {
    if (!z) return;
    if (z->tmp >= 0) close(z->tmp);
    if (z->tmp_path[0]) unlink(z->tmp_path);
    free(z->r); free(z->keys); free(z->aes); free(z->boff); free(z->bst); free(z);
}
NxStore *nx_ncz_store(NxNcz *z) { return &z->st; }
int nx_ncz_solid(const NxNcz *z) { return z->solid; }
uint64_t nx_ncz_body(const NxNcz *z) { return z->nca - NCZ_HEAD; }
int nx_ncz_keys(const NxNcz *z, const uint8_t key[16]) {
    for (int k = 0; k < z->nkeys; k++) if (memcmp(z->keys[k], key, 16)) return 0;
    return 1;
}

// ---------------------------------------------------------------- encrypting again
// p holds NCA bytes [off, off + n) of range r's key and counter: XOR with its keystream (CTR: encrypting and decrypting are the same)
static void ctr_xor(const NxAes *a, const uint8_t c8[8], uint64_t off, uint8_t *p, size_t n) {
    uint8_t ctr[16]; size_t in = (size_t)(off & 15);
    memcpy(ctr, c8, 8); uint64_t b = off >> 4; for (int j = 0; j < 8; j++) { ctr[15 - j] = (uint8_t)b; b >>= 8; }
    if (in) {   // the range of this read begins inside a 16-byte block: that block's keystream from its middle
        uint8_t ks[16] = {0}; nx_aes_ctr(a, ctr, ks, ks, 16);
        size_t h = 16 - in < n ? 16 - in : n;
        for (size_t i = 0; i < h; i++) p[i] ^= ks[in + i];
        p += h; n -= h;
    }
    if (n) nx_aes_ctr(a, ctr, p, p, n);
}
// buf holds the body's bytes of NCA [off, off + len) (off >= 0x4000): encrypted as the NCA has them
static void encrypt_again(const NxNcz *z, uint64_t off, uint8_t *buf, size_t len) {
    const Rng *r = find_rng(z, off);
    while (len && r && r < z->r + z->nr) {
        size_t k = len; if (off + k > r->end) k = (size_t)(r->end - off);
        if (r->crypt == 3 || r->crypt == 4) ctr_xor(&z->aes[r->key], r->ctr, off, buf, k);
        buf += k; off += k; len -= k; r++;
    }
}

// ---------------------------------------------------------------- blocks
// A thread's last decompressed block (by NCZ uid and block index) and its scratch; freed when the thread ends (pthread key) or by nx_ncz_thread_done
typedef struct Tc { uint64_t uid; uint32_t blk; uint8_t *out, *in; size_t outcap, incap; ZSTD_DCtx *d; } Tc;
static pthread_key_t tc_key; static pthread_once_t tc_once = PTHREAD_ONCE_INIT;
static void tc_free(void *p) { Tc *t = p; if (!t) return; free(t->out); free(t->in); if (t->d) ZSTD_freeDCtx(t->d); free(t); }
static void tc_init(void) { (void)pthread_key_create(&tc_key, tc_free); }
static Tc *tc_get(void) {
    pthread_once(&tc_once, tc_init);
    Tc *t = pthread_getspecific(tc_key);
    if (!t && (t = calloc(1, sizeof *t))) { t->blk = UINT32_MAX; if (pthread_setspecific(tc_key, t)) { free(t); t = NULL; } }
    if (!t) nx_err("out of memory");
    return t;
}
void nx_ncz_thread_done(void) {
    pthread_once(&tc_once, tc_init);
    Tc *t = pthread_getspecific(tc_key);
    if (t) { pthread_setspecific(tc_key, NULL); tc_free(t); }
}

// block i decompressed into dst (blk_len bytes), with the caller's scratch for its stored bytes and its context
static int blk_decode(const NxNcz *z, uint32_t i, uint8_t *dst, uint8_t **in, size_t *incap, ZSTD_DCtx **d) {
    uint64_t want = blk_len(z, i); uint32_t st = z->bst[i];
    if (st == want) return nx_pread_all(z->fd, dst, (size_t)want, z->at + z->boff[i]);   // stored as it was: compressing it saved nothing
    if (*incap < st) { uint8_t *ni = realloc(*in, st); if (!ni) { nx_err("out of memory"); return -1; } *in = ni; *incap = st; }
    if (nx_pread_all(z->fd, *in, st, z->at + z->boff[i])) return -1;
    if (!*d && !(*d = ZSTD_createDCtx())) { nx_err("out of memory"); return -1; }
    size_t r = ZSTD_decompressDCtx(*d, dst, (size_t)want, *in, st);
    if (ZSTD_isError(r) || r != want) {
        nx_err("%s: %s: block %u does not decompress (%s): the file is damaged", z->src, z->name, i, ZSTD_isError(r) ? ZSTD_getErrorName(r) : "too short");
        return -1;
    }
    return 0;
}

// the body's bytes [p, p + len)
static int body_read(NxNcz *z, uint64_t p, uint8_t *buf, size_t len) {
    if (z->tmp >= 0) return nx_pread_all(z->tmp, buf, len, p);   // solid, unpacked
    if (z->solid) { nx_err("%s: %s is read before it was unpacked", z->src, z->name); return -1; }
    Tc *t = tc_get(); if (!t) return -1;
    while (len) {
        uint32_t i = (uint32_t)(p >> z->bexp); uint64_t in = p & (z->bsize - 1), bl = blk_len(z, i);
        size_t k = len; if (k > bl - in) k = (size_t)(bl - in);
        if (!in && k == bl) { if (blk_decode(z, i, buf, &t->in, &t->incap, &t->d)) return -1; }   // a whole block: straight into the caller's buffer
        else {
            if (t->uid != z->uid || t->blk != i) {
                if (t->outcap < bl) { uint8_t *no = realloc(t->out, (size_t)bl); if (!no) { nx_err("out of memory"); return -1; } t->out = no; t->outcap = (size_t)bl; }
                t->blk = UINT32_MAX;
                if (blk_decode(z, i, t->out, &t->in, &t->incap, &t->d)) return -1;
                t->uid = z->uid; t->blk = i;
            }
            memcpy(buf, t->out + in, k);
        }
        buf += k; p += k; len -= k;
    }
    return 0;
}

// ---------------------------------------------------------------- the store
static int st_raw(NxStore *s, uint64_t off, void *vbuf, size_t len) {
    NxNcz *z = (NxNcz *)s; uint8_t *buf = vbuf;
    if (off > z->nca || len > z->nca - off) { nx_err("%s: read past the end of %s (%#llx + %zu)", z->src, z->name, (unsigned long long)off, len); return -1; }
    if (off < NCZ_HEAD) {   // the header copy
        size_t k = len; if (k > NCZ_HEAD - off) k = (size_t)(NCZ_HEAD - off);
        if (nx_pread_all(z->fd, buf, k, z->at + off)) return -1;
        buf += k; off += k; len -= k;
    }
    if (!len) return 0;
    if (body_read(z, off - NCZ_HEAD, buf, len)) return -1;
    encrypt_again(z, off, buf, len);
    return 0;
}
// the decrypted bytes the reader wants, when the range lies in one of the NCZ's ranges with this key and counter: its body bytes are those already
static int st_plain(NxStore *s, const uint8_t key[16], const uint8_t tmpl[16], uint64_t off, void *buf, size_t len) {
    NxNcz *z = (NxNcz *)s;
    if (off < NCZ_HEAD || off > z->nca || len > z->nca - off) return 0;
    const Rng *r = find_rng(z, off);
    if (!r || (r->crypt != 3 && r->crypt != 4) || len > r->end - off || memcmp(r->ctr, tmpl, 8) || memcmp(z->keys[r->key], key, 16)) return 0;
    return body_read(z, off - NCZ_HEAD, buf, len) ? -1 : 1;
}
static uint64_t st_unit_end(NxStore *s, uint64_t off) {
    NxNcz *z = (NxNcz *)s;
    if (z->solid || off <= NCZ_HEAD || off >= z->nca) return off;
    uint64_t p = off - NCZ_HEAD, e = ((p + z->bsize - 1) >> z->bexp) << z->bexp;
    return e >= z->nca - NCZ_HEAD ? z->nca : NCZ_HEAD + e;
}

// ---------------------------------------------------------------- unpacking (solid) and checking (block)
static int stopped(void) { if (atomic_load(&nx_stop)) { nx_err("the import was stopped"); return 1; } return 0; }

// Solid: the calling thread decompresses each slot of the body and writes it to the temporary file; a second thread encrypts it again and hashes it, in order
typedef struct UnSlot { uint8_t *buf; size_t len; uint64_t p; int full; } UnSlot;
enum { UN_SLOTS = 4 };
typedef struct Un {
    NxNcz *z; NxSha256 h; pthread_mutex_t mu; pthread_cond_t cv; UnSlot slot[UN_SLOTS];
    uint64_t put, got; int end;   // slots filled by the decompressor / hashed; end: no more slots
} Un;
static void *un_hasher(void *arg) {
    Un *u = arg;
    pthread_mutex_lock(&u->mu);
    for (;;) {
        while (u->got == u->put && !u->end) pthread_cond_wait(&u->cv, &u->mu);
        if (u->got == u->put) break;
        UnSlot *s = &u->slot[u->got % UN_SLOTS];
        pthread_mutex_unlock(&u->mu);
        encrypt_again(u->z, NCZ_HEAD + s->p, s->buf, s->len);
        nx_sha256_update(&u->h, s->buf, s->len);
        pthread_mutex_lock(&u->mu);
        u->got++; pthread_cond_broadcast(&u->cv);
    }
    pthread_mutex_unlock(&u->mu);
    return NULL;
}

static int unpack(NxNcz *z, const char *tmp_dir, uint8_t sha[32], void (*tick)(void *, uint64_t), void *user) {
    Un *u = calloc(1, sizeof *u); uint8_t *in = NULL; ZSTD_DCtx *d = NULL; int rc = -1, up = 0, fd = -1; pthread_t th;
    if (!u) { nx_err("out of memory"); return -1; }
    pthread_mutex_init(&u->mu, NULL); pthread_cond_init(&u->cv, NULL); u->z = z;
    for (int i = 0; i < UN_SLOTS; i++) if (!(u->slot[i].buf = malloc(NCZ_SLOT))) ZFAIL("out of memory");
    if (!(in = malloc(1 << 20)) || !(d = ZSTD_createDCtx())) ZFAIL("out of memory");
    if (ZSTD_isError(ZSTD_DCtx_setParameter(d, ZSTD_d_windowLogMax, NCZ_WINLOG))) ZFAIL("zstd: cannot set the window limit");
    if (tmp_dir) {   // (none: only hashed, nximport_cli check)
        if (nx_mkdir(tmp_dir, 0700) && errno != EEXIST) ZFAIL("cannot make the folder for unpacking %s: %s: %s", z->src, tmp_dir, strerror(errno));
        snprintf(z->tmp_path, sizeof z->tmp_path, "%s/%.40s.body", tmp_dir, z->name);
        if ((fd = open(z->tmp_path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600)) < 0) { z->tmp_path[0] = 0; ZFAIL("cannot unpack %s into %s: %s", z->src, tmp_dir, strerror(errno)); }
#ifndef _WIN32
        if (!unlink(z->tmp_path)) z->tmp_path[0] = 0;   // gone from the folder already: its space comes back when it is closed, even if the app is killed
#endif
    }
    // the NCA's first bytes, then the body
    { uint8_t *hb = u->slot[0].buf; if (nx_pread_all(z->fd, hb, NCZ_HEAD, z->at)) goto fail; nx_sha256_init(&u->h); nx_sha256_update(&u->h, hb, NCZ_HEAD); }
    if (pthread_create(&th, NULL, un_hasher, u)) ZFAIL("cannot create a thread: %s", strerror(errno));
    up = 1;
    uint64_t src = z->at + z->data, src_end = z->at + z->size, body = z->nca - NCZ_HEAD, done = 0; size_t last = 1;
    ZSTD_inBuffer ib = { in, 0, 0 };
    while (done < body) {
        if (stopped()) goto fail;
        pthread_mutex_lock(&u->mu);
        while (u->put - u->got == UN_SLOTS) pthread_cond_wait(&u->cv, &u->mu);
        UnSlot *s = &u->slot[u->put % UN_SLOTS];
        pthread_mutex_unlock(&u->mu);
        ZSTD_outBuffer ob = { s->buf, body - done < NCZ_SLOT ? (size_t)(body - done) : NCZ_SLOT, 0 };
        while (ob.pos < ob.size) {
            if (ib.pos == ib.size) {
                if (src == src_end) break;
                size_t k = src_end - src < (1u << 20) ? (size_t)(src_end - src) : (1u << 20);
                if (nx_pread_all(z->fd, in, k, src)) goto fail;
                src += k; ib.size = k; ib.pos = 0;
            }
            size_t r = ZSTD_decompressStream(d, &ob, &ib);
            if (ZSTD_isError(r)) {
                if (ZSTD_getErrorCode(r) == ZSTD_error_frameParameter_windowTooLarge)
                    ZFAIL("%s: %s was compressed with a zstd window larger than 128 MiB, more than an import can hold: compress the game again with nsz's default level", z->src, z->name);
                ZFAIL("%s: %s does not decompress (%s at %llu of %llu bytes): the file is damaged", z->src, z->name, ZSTD_getErrorName(r), (unsigned long long)(done + ob.pos), (unsigned long long)body);
            }
            last = r;
        }
        if (ob.pos < ob.size) ZFAIL("%s is cut off: %s ends after %llu of its %llu bytes", z->src, z->name, (unsigned long long)(done + ob.pos), (unsigned long long)body);
        s->p = done; s->len = ob.pos;
        for (size_t w = 0; fd >= 0 && w < ob.pos; ) {
            ssize_t k = pwrite(fd, s->buf + w, ob.pos - w, (off_t)(done + w));
            if (k < 0) { if (errno == EINTR) continue; ZFAIL("unpacking %s: cannot write into %s: %s", z->src, tmp_dir, strerror(errno)); }
            w += (size_t)k;
        }
        done += ob.pos;
        pthread_mutex_lock(&u->mu); u->put++; pthread_cond_broadcast(&u->cv); pthread_mutex_unlock(&u->mu);
        if (tick) tick(user, ob.pos);
    }
    // the stream must end here: a frame not finished is a cut-off file, more bytes out of it a damaged one
    if (last) {
        uint8_t one; ZSTD_outBuffer ob = { &one, 1, 0 };
        for (;;) {
            if (ib.pos == ib.size) {
                if (src == src_end) ZFAIL("%s is cut off: %s's compressed data ends early", z->src, z->name);
                size_t k = src_end - src < (1u << 20) ? (size_t)(src_end - src) : (1u << 20);
                if (nx_pread_all(z->fd, in, k, src)) goto fail;
                src += k; ib.size = k; ib.pos = 0;
            }
            size_t r = ZSTD_decompressStream(d, &ob, &ib);
            if (ZSTD_isError(r)) ZFAIL("%s: %s does not decompress at its end (%s): the file is damaged", z->src, z->name, ZSTD_getErrorName(r));
            if (ob.pos) ZFAIL("%s: %s holds more data than its range table says: the file is damaged", z->src, z->name);
            if (!r) break;
        }
    }
    pthread_mutex_lock(&u->mu); u->end = 1; pthread_cond_broadcast(&u->cv); pthread_mutex_unlock(&u->mu);
    pthread_join(th, NULL); up = 0;
    nx_sha256_final(&u->h, sha);
    z->tmp = fd; fd = -1; rc = 0;
fail:
    if (up) { pthread_mutex_lock(&u->mu); u->end = 1; pthread_cond_broadcast(&u->cv); pthread_mutex_unlock(&u->mu); pthread_join(th, NULL); }   // it hashes what was put, then ends
    if (fd >= 0) close(fd);
    if (rc && z->tmp_path[0]) { unlink(z->tmp_path); z->tmp_path[0] = 0; }
    for (int i = 0; i < UN_SLOTS; i++) free(u->slot[i].buf);
    pthread_mutex_destroy(&u->mu); pthread_cond_destroy(&u->cv); free(u); free(in); if (d) ZSTD_freeDCtx(d);
    return rc;
}

// Block: worker threads decompress groups of consecutive blocks into a ring; the calling thread encrypts each group again and hashes it, in order
typedef struct BkSlot { uint8_t *buf; size_t len; int state, rc; char err[512]; } BkSlot;   // state 0 free, 1 being read, 2 read
typedef struct Bk {
    NxNcz *z; pthread_mutex_t mu; pthread_cond_t cv_w, cv_m;
    BkSlot *slot; uint32_t nslot, per, ngrp, claim, done; int stop;
} Bk;
static void *bk_worker(void *arg) {
    Bk *b = arg; uint8_t *in = NULL; size_t incap = 0; ZSTD_DCtx *d = NULL;
    pthread_mutex_lock(&b->mu);
    for (;;) {
        while (!b->stop && (b->claim == b->ngrp || b->claim - b->done == b->nslot)) pthread_cond_wait(&b->cv_w, &b->mu);
        if (b->stop || b->claim == b->ngrp) break;
        uint32_t g = b->claim++; BkSlot *s = &b->slot[g % b->nslot]; s->state = 1;
        pthread_mutex_unlock(&b->mu);
        uint32_t b0 = g * b->per, b1 = b0 + b->per < b->z->nblk ? b0 + b->per : b->z->nblk; size_t at = 0; s->rc = 0;
        for (uint32_t i = b0; i < b1 && !s->rc; i++) {
            if (blk_decode(b->z, i, s->buf + at, &in, &incap, &d)) { s->rc = -1; snprintf(s->err, sizeof s->err, "%s", nximport_last_error()); }
            at += (size_t)blk_len(b->z, i);
        }
        s->len = at;
        pthread_mutex_lock(&b->mu);
        s->state = 2; pthread_cond_broadcast(&b->cv_m);
    }
    pthread_mutex_unlock(&b->mu);
    free(in); if (d) ZSTD_freeDCtx(d);
    return NULL;
}

int nx_threads(void);   // nximport.c: the writer's reader threads
static int check_blocks(NxNcz *z, uint8_t sha[32], void (*tick)(void *, uint64_t), void *user) {
    Bk b; memset(&b, 0, sizeof b); b.z = z; int rc = -1, nth = 0, want = nx_threads(); pthread_t *th = NULL; NxSha256 h;
    pthread_mutex_init(&b.mu, NULL); pthread_cond_init(&b.cv_w, NULL); pthread_cond_init(&b.cv_m, NULL);
    b.per = z->bsize >= NCZ_SLOT ? 1 : (uint32_t)(NCZ_SLOT / z->bsize);
    b.ngrp = (z->nblk + b.per - 1) / b.per;
    uint64_t gbytes = (uint64_t)b.per * z->bsize;
    b.nslot = (uint32_t)(2 * want); if (b.nslot * gbytes > NCZ_RING_BYTES) b.nslot = (uint32_t)(NCZ_RING_BYTES / gbytes); if (b.nslot < 2) b.nslot = 2;
    if (want > (int)b.nslot) want = (int)b.nslot;
    if (!(b.slot = calloc(b.nslot, sizeof *b.slot)) || !(th = calloc((size_t)want, sizeof *th))) ZFAIL("out of memory");
    for (uint32_t i = 0; i < b.nslot; i++) if (!(b.slot[i].buf = malloc((size_t)gbytes))) ZFAIL("out of memory");
    { uint8_t *hb = b.slot[0].buf; if (nx_pread_all(z->fd, hb, NCZ_HEAD, z->at)) goto fail; nx_sha256_init(&h); nx_sha256_update(&h, hb, NCZ_HEAD); }
    for (int i = 0; i < want; i++) { if (pthread_create(&th[i], NULL, bk_worker, &b)) break; nth++; }
    if (!nth) ZFAIL("cannot create a thread: %s", strerror(errno));
    pthread_mutex_lock(&b.mu);
    for (uint32_t g = 0; g < b.ngrp; g++) {
        BkSlot *s = &b.slot[g % b.nslot];
        while (!(g < b.claim && s->state == 2)) pthread_cond_wait(&b.cv_m, &b.mu);
        pthread_mutex_unlock(&b.mu);
        if (s->rc) { nx_err("%s", s->err); pthread_mutex_lock(&b.mu); break; }
        if (stopped()) { pthread_mutex_lock(&b.mu); break; }
        encrypt_again(z, NCZ_HEAD + (uint64_t)g * gbytes, s->buf, s->len);
        nx_sha256_update(&h, s->buf, s->len);
        if (tick) tick(user, s->len);
        pthread_mutex_lock(&b.mu);
        s->state = 0; b.done++; pthread_cond_broadcast(&b.cv_w);
    }
    if (b.done == b.ngrp) rc = 0;
    b.stop = 1; pthread_cond_broadcast(&b.cv_w);
    pthread_mutex_unlock(&b.mu);
    for (int i = 0; i < nth; i++) pthread_join(th[i], NULL);
    if (!rc) nx_sha256_final(&h, sha);
fail:
    if (b.slot) for (uint32_t i = 0; i < b.nslot; i++) free(b.slot[i].buf);
    free(b.slot); free(th);
    pthread_mutex_destroy(&b.mu); pthread_cond_destroy(&b.cv_w); pthread_cond_destroy(&b.cv_m);
    return rc;
}

int nx_ncz_prepare(NxNcz *z, const char *tmp_dir, uint8_t sha[32], void (*tick)(void *, uint64_t), void *user) {
    if (z->solid) {
        if (z->tmp >= 0) { nx_err("%s: %s is unpacked already", z->src, z->name); return -1; }
        return unpack(z, tmp_dir, sha, tick, user);
    }
    return check_blocks(z, sha, tick, user);
}
