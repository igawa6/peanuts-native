// libnximport: a section's bytes checked against the hash layers of its NCA while the importer reads them (nx_sec_verify, nx_internal.h). The importer
// only: the run-time reader (the host's copy of nx_nca.c) never sets a check, and this file is not among its sources (android/host/CMakeLists.txt NSP_C).
// Before this check a plain NCA's bytes were never compared with anything (an NCZ's are: nx_ncz.c rebuilds and hashes it whole), so a damaged or cut dump
// became a .nup with garbled files, and the build stopped later with a raw error (GitHub issue #561: Pokémon Legends: Z-A's data.trpfs, its index garbled).
// The formats, from switchbrew's NCA page, with LibHac's IntegrityVerificationStorage and hactool's nca_section_check_hash_table for the padding rules:
// - The NCA header holds the SHA-256 of each fs header (0x280 + 0x20 * section), over its decrypted 0x200 bytes.
// - A RomFS section (hash type 3, HierarchicalIntegrity) has the IVFC header at fs header + 8: "IVFC", its version, the master hash's size (0x20), the level
//   count (7), six level records of 0x18 from + 0x10 (logical offset, size, log2 of the block size), the master hash at + 0xC0. Each level is cut into blocks
//   of its own block size, and each block's SHA-256 is in the level before it (the first level's in the master hash); a short last block is hashed
//   zero-padded to the block size. The sixth level is the data.
// - A PFS0 section (hash type 2, HierarchicalSha256) has at fs header + 8 the master hash (the SHA-256 of the hash table), the block size (u32 @ + 0x20), the
//   layer count (2, @ + 0x24), the hash table's offset and size (@ + 0x28) and the PFS0's (@ + 0x38). Each PFS0 block's SHA-256 is in the hash table; a short
//   last block is hashed as it is.
// The hashes cover the section's raw bytes: decrypted, a BKTR section's patched image, a compressed data level as it is stored. So the check sits below the
// decompression, in nx_sec_read_raw: the hash levels are read and checked here once, and the data level as the writer reads it, every block once.
// The cost on a phone is CPU only: the writer reads every byte anyway, and a read that covers a block only in part reads the rest of that block (from the
// page cache, as a rule: its neighbour was just read).
#define _FILE_OFFSET_BITS 64
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "nximport.h"
#include "nx_internal.h"
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static uint32_t u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

typedef struct Verify {
    NxCheck k;
    uint64_t doff, dsize;    // the data level in the section
    uint32_t bs; int pad;    // its block size; pad: a short last block is hashed zero-padded to bs (IVFC), else as it is (HierarchicalSha256)
    uint8_t *hash;           // one SHA-256 per block of the data level
    uint64_t nblocks;
    _Atomic uint64_t *done;  // a bit per block: checked
    const char *part;        // "RomFS" or "ExeFS", for the messages
    char what[320];          // "<file>: the update v131072"
} Verify;

// "<what> is damaged: <why>" as the error (the app's card names the file from it: dev.peanuts.build.Compressed.failure). Always -1
static int damaged(const char *what, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static int damaged(const char *what, const char *fmt, ...) {
    char why[384]; va_list ap; va_start(ap, fmt); vsnprintf(why, sizeof why, fmt, ap); va_end(ap);
    nx_err("%s is damaged: %s", what, why);
    return -1;
}

static const uint8_t zeros[4096];
// SHA-256 of the n bytes at p followed by len - n zeros
static void block_hash(const uint8_t *p, size_t n, size_t len, uint8_t out[32]) {
    NxSha256 h; nx_sha256_init(&h); nx_sha256_update(&h, p, n);
    for (size_t z = len - n; z; ) { size_t k = z < sizeof zeros ? z : sizeof zeros; nx_sha256_update(&h, zeros, k); z -= k; }
    nx_sha256_final(&h, out);
}

// The blocks of the data level that [off, off + len) touches, each checked once: from buf when the read holds the whole block, else read whole first
static int v_read(NxCheck *k, NxNca *n, int s, uint64_t off, const uint8_t *buf, size_t len) {
    Verify *v = (Verify *)k; uint64_t end = off + len, dend = v->doff + v->dsize;
    if (!len || end <= v->doff || off >= dend) return 0;
    uint64_t lo = off > v->doff ? off : v->doff, hi = end < dend ? end : dend;
    uint8_t *tmp = NULL; int rc = 0;
    for (uint64_t b = (lo - v->doff) / v->bs, last = (hi - 1 - v->doff) / v->bs; b <= last; b++) {
        uint64_t bit = 1ull << (b & 63);
        if (atomic_load_explicit(&v->done[b >> 6], memory_order_relaxed) & bit) continue;
        uint64_t bo = v->doff + b * v->bs; size_t bl = (size_t)(dend - bo < v->bs ? dend - bo : v->bs);
        const uint8_t *p = buf + (bo - off);
        if (bo < off || bo + bl > end) {
            if (!tmp && !(tmp = malloc(v->bs))) { nx_err("out of memory"); rc = -1; break; }
            if (nx_sec_read_unchecked(n, s, bo, tmp, bl)) { char why[256]; snprintf(why, sizeof why, "%s", nximport_last_error()); rc = damaged(v->what, "its %s cannot be read at %#llx (%s)", v->part, (unsigned long long)bo, why); break; }
            p = tmp;
        }
        uint8_t h[32]; block_hash(p, bl, v->pad ? v->bs : bl, h);
        if (memcmp(h, v->hash + 32 * b, 32)) { rc = damaged(v->what, "the bytes at %#llx of its %s do not match their checksum", (unsigned long long)bo, v->part); break; }
        atomic_fetch_or_explicit(&v->done[b >> 6], bit, memory_order_relaxed);
    }
    free(tmp); return rc;
}

static void v_free(NxCheck *k) { Verify *v = (Verify *)k; if (!v) return; free(v->hash); free((void *)v->done); free(v); }

// a level's bytes (the check's own read, not checked again) into a new buffer, or -1 naming the file
static uint8_t *level_read(NxNca *n, int s, const Verify *v, uint64_t off, uint64_t size, int level) {
    uint8_t *b = malloc(size ? (size_t)size : 1);
    if (!b) { nx_err("out of memory"); return NULL; }
    if (nx_sec_read_unchecked(n, s, off, b, (size_t)size)) {
        char why[256]; snprintf(why, sizeof why, "%s", nximport_last_error());
        damaged(v->what, "hash level %d of its %s cannot be read (%s)", level, v->part, why); free(b); return NULL;
    }
    return b;
}

// the blocks of data (size bytes, blocks of bs) against nh hashes; -1 naming the level when one does not match
static int level_check(const Verify *v, const uint8_t *data, uint64_t size, uint32_t bs, int pad, const uint8_t *hash, uint64_t nh, int level) {
    uint64_t nb = (size + bs - 1) / bs;
    if (nb > nh) return damaged(v->what, "level %d of its %s has %llu blocks and only %llu checksums", level, v->part, (unsigned long long)nb, (unsigned long long)nh);
    for (uint64_t b = 0; b < nb; b++) {
        size_t bl = (size_t)(size - b * bs < bs ? size - b * bs : bs); uint8_t h[32];
        block_hash(data + b * bs, bl, pad ? bs : bl, h);
        if (memcmp(h, hash + 32 * b, 32)) return damaged(v->what, "hash level %d of its %s does not match its checksum", level, v->part);
    }
    return 0;
}

int nx_sec_verify(NxNca *n, int s, const char *what) {
    if (s < 0 || s > 3 || !n->sec[s].present) { nx_err("%s: no section %d", n->id, s); return -1; }
    NxSection *S = &n->sec[s];
    if (S->check) return 0;
    const uint8_t *fh = n->hdr + 0x400 + 0x200 * s; uint8_t h[32];
    Verify *v = calloc(1, sizeof *v); if (!v) { nx_err("out of memory"); return -1; }
    snprintf(v->what, sizeof v->what, "%s", what); v->part = S->fs_type == NX_FS_ROMFS ? "RomFS" : "ExeFS";
    // the fs header is the anchor of everything below it: its master hash, offsets and sizes
    nx_sha256(fh, 0x200, h);
    if (memcmp(h, n->hdr + 0x280 + 0x20 * s, 32)) { damaged(what, "the header of its %s does not match its checksum", v->part); v_free(&v->k); return -1; }
    // a sparse base holds only the ranges its update reads (its update's check covers them); a hash type this reader does not know: not checked
    if (S->sparse || (S->hash_type != 2 && S->hash_type != 3)) { v_free(&v->k); return 0; }
    uint64_t size = S->crypt_type == 4 ? (S->bktr ? S->bktr->total : 0) : S->size;   // the section as the hashes see it: a patch's image
    if (S->crypt_type == 4 && !S->bktr) { nx_err("%s: patch section %d has no base yet (nx_nca_set_base first)", n->id, s); v_free(&v->k); return -1; }
    const uint8_t *hi = fh + 8; uint8_t *prev = NULL;
    if (S->hash_type == 3) {
        if (memcmp(hi, "IVFC", 4) || u32(hi + 8) != 0x20 || u32(hi + 0xC) != 7) { v_free(&v->k); return 0; }   // not the IVFC this reader knows: not checked
        uint64_t off[6], sz[6]; uint32_t bs[6];
        for (int i = 0; i < 6; i++) {
            const uint8_t *r = hi + 0x10 + 0x18 * i; uint32_t order = u32(r + 16);
            off[i] = u64(r); sz[i] = u64(r + 8);
            if (order < 4 || order > 24) { v_free(&v->k); return 0; }   // a block size no NCA has: a layout this reader does not know
            bs[i] = 1u << order;
            if (off[i] > size || sz[i] > size - off[i]) { damaged(what, "level %d of its %s lies outside it", i + 1, v->part); v_free(&v->k); return -1; }
            if (i < 5 && sz[i] > (256ull << 20)) { damaged(what, "hash level %d of its %s is %llu bytes", i + 1, v->part, (unsigned long long)sz[i]); v_free(&v->k); return -1; }
        }
        const uint8_t *hash = hi + 0xC0; uint64_t nh = 1;   // the master hash: level 1's one block
        for (int i = 0; i < 5; i++) {
            uint8_t *lv = level_read(n, s, v, off[i], sz[i], i + 1);
            if (!lv || level_check(v, lv, sz[i], bs[i], 1, hash, nh, i + 1)) { free(lv); free(prev); v_free(&v->k); return -1; }
            free(prev); prev = lv; hash = lv; nh = sz[i] / 32;
        }
        v->doff = off[5]; v->dsize = sz[5]; v->bs = bs[5]; v->pad = 1; v->nblocks = (sz[5] + bs[5] - 1) / bs[5];
        if (v->nblocks > nh) { damaged(what, "the data of its %s has %llu blocks and only %llu checksums", v->part, (unsigned long long)v->nblocks, (unsigned long long)nh); free(prev); v_free(&v->k); return -1; }
    } else {
        uint32_t bs = u32(hi + 0x20); uint64_t toff = u64(hi + 0x28), tsz = u64(hi + 0x30), poff = u64(hi + 0x38), psz = u64(hi + 0x40);
        if (u32(hi + 0x24) != 2 || !bs || bs > (16u << 20)) { v_free(&v->k); return 0; }   // a layout this reader does not know: not checked
        if (toff > size || tsz > size - toff || poff > size || psz > size - poff || tsz > (64ull << 20)) { damaged(what, "the hash table of its %s lies outside it", v->part); v_free(&v->k); return -1; }
        if (!(prev = level_read(n, s, v, toff, tsz, 1))) { v_free(&v->k); return -1; }
        nx_sha256(prev, (size_t)tsz, h);
        if (memcmp(h, hi, 32)) { damaged(what, "the hash table of its %s does not match its checksum", v->part); free(prev); v_free(&v->k); return -1; }
        v->doff = poff; v->dsize = psz; v->bs = bs; v->pad = 0; v->nblocks = (psz + bs - 1) / bs;
        if (v->nblocks > tsz / 32) { damaged(what, "the data of its %s has %llu blocks and only %llu checksums", v->part, (unsigned long long)v->nblocks, (unsigned long long)(tsz / 32)); free(prev); v_free(&v->k); return -1; }
    }
    v->hash = prev;
    if (!(v->done = calloc((size_t)((v->nblocks + 63) / 64) + 1, sizeof *v->done))) { nx_err("out of memory"); v_free(&v->k); return -1; }
    v->k.read = v_read; v->k.free = v_free;
    S->check = &v->k;
    if (getenv("NX_DEBUG")) fprintf(stderr, "nximport: %s: section %d (%s) is checked as it is read: %llu blocks of %u bytes from %#llx\n", what, s, v->part,
                                    (unsigned long long)v->nblocks, v->bs, (unsigned long long)v->doff);
    return 0;
}

// How many of a checked section's data blocks were checked so far, of how many (both 0 when it has no check): for the tests and NX_DEBUG
void nx_sec_check_count(const NxNca *n, int s, uint64_t *checked, uint64_t *blocks) {
    *checked = *blocks = 0;
    if (s < 0 || s > 3 || !n->sec[s].check || n->sec[s].check->read != v_read) return;
    const Verify *v = (const Verify *)n->sec[s].check;
    *blocks = v->nblocks;
    for (uint64_t w = 0; w < (v->nblocks + 63) / 64; w++) *checked += (uint64_t)__builtin_popcountll(atomic_load_explicit(&v->done[w], memory_order_relaxed));
}
