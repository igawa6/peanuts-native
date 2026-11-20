// Synthetic dumps for nsp_test: NSP and XCI containers written here from the public formats, with keys made up for the test (nothing of a real console or
// game). A base game with a plain or compressed RomFS, an update that patches it (BKTR relocation and subsection tables, a compression table over the patched
// bytes), an add-on with a title key and its ticket (common or personalized), and the broken ones the reader must refuse. Every byte of every file is kept in
// memory beside the dumps, so the test compares what it reads with what was written, not with another reader.
// Written from the importer's own notes on the formats (nx_nca.c) and switchbrew's NCA / PFS0 / HFS0 / RomFS / CNMT / ticket pages; the cipher is nx_crypto.c's.
#ifndef NSP_FIXTURE_H
#define NSP_FIXTURE_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nx_internal.h"

typedef struct FxBuf { uint8_t *p; size_t n, cap; } FxBuf;
static size_t fx_put(FxBuf *b, const void *p, size_t n) {   // p NULL: zeros. Returns where the bytes went
    size_t at = b->n;
    if (b->n + n > b->cap) { b->cap = (b->n + n) * 2 + 4096; if (!(b->p = realloc(b->p, b->cap))) abort(); }
    if (p) memcpy(b->p + at, p, n); else memset(b->p + at, 0, n);
    b->n += n; return at;
}
static void fx_align(FxBuf *b, size_t a) { fx_put(b, NULL, (a - b->n % a) % a); }
static uint64_t fx_state = 0x9E3779B97F4A7C15ull;
static uint64_t fx_rand(void) { fx_state ^= fx_state << 13; fx_state ^= fx_state >> 7; fx_state ^= fx_state << 17; return fx_state * 0x2545F4914F6CDD1Dull; }
static void fx_fill(uint8_t *p, size_t n) { for (size_t i = 0; i < n; i++) { if (!(i & 7)) fx_rand(); p[i] = (uint8_t)(fx_state >> (8 * (i & 7))); } }
static uint8_t *fx_bytes(size_t n) { uint8_t *p = malloc(n ? n : 1); if (!p) abort(); fx_fill(p, n); return p; }
static void fx_w16(uint8_t *p, uint16_t v) { memcpy(p, &v, 2); }
static void fx_w32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void fx_w64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }
static void fx_hex(char *out, const uint8_t *p, size_t n) { for (size_t i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", p[i]); }
static void fx_write(const char *path, const void *p, size_t n) { FILE *f = fopen(path, "wb"); if (!f || fwrite(p, 1, n, f) != n || fclose(f)) { perror(path); abort(); } }

// ---- keys: a prod.keys with the three the fixture's NCAs use (generation 0)
typedef struct FxKeys { uint8_t header[32], kaek[16], titlekek[16]; } FxKeys;
static void fx_keys_make(FxKeys *k, const char *path) {
    fx_fill(k->header, 32); fx_fill(k->kaek, 16); fx_fill(k->titlekek, 16);
    char a[65], b[33], c[33]; fx_hex(a, k->header, 32); fx_hex(b, k->kaek, 16); fx_hex(c, k->titlekek, 16);
    FILE *f = fopen(path, "w"); if (!f) { perror(path); abort(); }
    fprintf(f, "header_key = %s\nkey_area_key_application_00 = %s\ntitlekek_00 = %s\n", a, b, c); fclose(f);
}

// ---- ciphers (the importer has the decrypting halves)
static void fx_xts_encrypt(const uint8_t key[32], uint64_t sector, uint8_t *p, size_t n) {   // 0x200-byte sectors, the sector number big-endian as the tweak
    NxAes a1, a2; nx_aes_init(&a1, key); nx_aes_init(&a2, key + 16);
    for (size_t off = 0; off + 0x200 <= n; off += 0x200, sector++) {
        uint8_t t[16], tw[16]; uint64_t s = sector;
        for (int i = 15; i >= 0; i--) { tw[i] = (uint8_t)s; s >>= 8; }
        nx_aes_encrypt_block(&a2, tw, t);
        for (size_t b = 0; b < 0x200; b += 16) {
            uint8_t x[16]; for (int i = 0; i < 16; i++) x[i] = (uint8_t)(p[off + b + i] ^ t[i]);
            nx_aes_encrypt_block(&a1, x, x);
            for (int i = 0; i < 16; i++) p[off + b + i] = (uint8_t)(x[i] ^ t[i]);
            uint8_t carry = (uint8_t)(t[15] >> 7);
            for (int i = 15; i > 0; i--) t[i] = (uint8_t)((t[i] << 1) | (t[i - 1] >> 7));
            t[0] = (uint8_t)((t[0] << 1) ^ (carry ? 0x87 : 0));
        }
    }
}
// AES-CTR over n bytes that lie at abs in the NCA (abs and n multiples of 16): counter = hi (4, big-endian), cv (4), the block number (8)
static void fx_ctr(const uint8_t key[16], uint32_t hi, uint32_t cv, uint64_t abs, uint8_t *p, size_t n) {
    NxAes a; uint8_t c[16]; uint64_t blk = abs >> 4; nx_aes_init(&a, key);
    for (int j = 0; j < 4; j++) { c[3 - j] = (uint8_t)(hi >> (8 * j)); c[7 - j] = (uint8_t)(cv >> (8 * j)); }
    for (int j = 0; j < 8; j++) { c[15 - j] = (uint8_t)blk; blk >>= 8; }
    nx_aes_ctr(&a, c, p, p, n);
}

// ---- PFS0 (hfs = 0) and HFS0 (hfs = 1): header, entries, names, the files one after the other
typedef struct FxEnt { char name[80]; const uint8_t *p; size_t n; } FxEnt;
static void fx_fs0(const FxEnt *e, int n, int hfs, FxBuf *out) {
    size_t es = hfs ? 0x40 : 0x18, ss = 0, at = 0, no = 0; uint8_t h[0x10] = {0};
    for (int i = 0; i < n; i++) ss += strlen(e[i].name) + 1;
    ss = (ss + 0x1F) & ~(size_t)0x1F;
    memcpy(h, hfs ? "HFS0" : "PFS0", 4); fx_w32(h + 4, (uint32_t)n); fx_w32(h + 8, (uint32_t)ss); fx_put(out, h, 0x10);
    for (int i = 0; i < n; i++) { uint8_t r[0x40] = {0}; fx_w64(r, at); fx_w64(r + 8, e[i].n); fx_w32(r + 0x10, (uint32_t)no); fx_put(out, r, es); at += e[i].n; no += strlen(e[i].name) + 1; }
    size_t st = fx_put(out, NULL, ss); no = 0;
    for (int i = 0; i < n; i++) { strcpy((char *)out->p + st + no, e[i].name); no += strlen(e[i].name) + 1; }
    for (int i = 0; i < n; i++) fx_put(out, e[i].p, e[i].n);
}

// ---- RomFS: header 0x50, the files' bytes (each at a multiple of 16), the directory table, the file table
typedef struct FxFile {
    char path[160]; uint64_t size; const uint8_t *data;
    int64_t base;      // >= 0: the bytes are the base game's section bytes (as stored there) from this offset, so an update can map them instead of carrying them
    uint64_t at;       // out: where the bytes lie in the RomFS image
} FxFile;
static void fx_romfs(FxFile *f, int nf, FxBuf *out) {
    enum { MAXD = 256 };
    static struct { char path[160]; int parent; uint32_t off; } d[MAXD];
    int nd = 1, *pd = malloc(sizeof *pd * (size_t)(nf + 1)); uint32_t *fo = malloc(sizeof *fo * (size_t)(nf + 1)); if (!pd || !fo) abort();
    d[0].path[0] = 0; d[0].parent = 0;
    for (int i = 0; i < nf; i++) {   // the directories each path goes through
        int cur = 0; const char *p = f[i].path, *s;
        while ((s = strchr(p, '/'))) {
            char full[160]; snprintf(full, sizeof full, "%.*s", (int)(s - f[i].path), f[i].path);
            int k = 1; while (k < nd && strcmp(d[k].path, full)) k++;
            if (k == nd) { if (nd == MAXD) abort(); strcpy(d[nd].path, full); d[nd].parent = cur; nd++; }
            cur = k; p = s + 1;
        }
        pd[i] = cur;
    }
#define FX_LEAF(s) (strrchr((s), '/') ? strrchr((s), '/') + 1 : (s))
    uint32_t dsz = 0, fsz = 0;
    for (int i = 0; i < nd; i++) { d[i].off = dsz; dsz += 0x18 + (((uint32_t)strlen(FX_LEAF(d[i].path)) + 3) & ~3u); }
    for (int i = 0; i < nf; i++) { fo[i] = fsz; fsz += 0x20 + (((uint32_t)strlen(FX_LEAF(f[i].path)) + 3) & ~3u); }
    size_t start = out->n; fx_put(out, NULL, 0x50);
    for (int i = 0; i < nf; i++) { fx_align(out, 16); f[i].at = out->n - start; fx_put(out, f[i].data, (size_t)f[i].size); }
    fx_align(out, 16);
    size_t dm = fx_put(out, NULL, dsz), fm = fx_put(out, NULL, fsz);
    for (int i = 0; i < nd; i++) {
        uint8_t *e = out->p + dm + d[i].off; const char *nm = FX_LEAF(d[i].path); uint32_t sib = 0xFFFFFFFFu, kid = 0xFFFFFFFFu, file = 0xFFFFFFFFu;
        for (int j = i + 1; j < nd && i; j++) if (d[j].parent == d[i].parent) { sib = d[j].off; break; }
        for (int j = 1; j < nd; j++) if (d[j].parent == i) { kid = d[j].off; break; }
        for (int j = 0; j < nf; j++) if (pd[j] == i) { file = fo[j]; break; }
        fx_w32(e, d[d[i].parent].off); fx_w32(e + 4, sib); fx_w32(e + 8, kid); fx_w32(e + 12, file); fx_w32(e + 16, 0xFFFFFFFFu);
        fx_w32(e + 0x14, (uint32_t)strlen(nm)); memcpy(e + 0x18, nm, strlen(nm));
    }
    for (int i = 0; i < nf; i++) {
        uint8_t *e = out->p + fm + fo[i]; const char *nm = FX_LEAF(f[i].path); uint32_t sib = 0xFFFFFFFFu;
        for (int j = i + 1; j < nf; j++) if (pd[j] == pd[i]) { sib = fo[j]; break; }
        fx_w32(e, d[pd[i]].off); fx_w32(e + 4, sib); fx_w64(e + 8, f[i].at - 0x50); fx_w64(e + 16, f[i].size); fx_w32(e + 0x18, 0xFFFFFFFFu);
        fx_w32(e + 0x1C, (uint32_t)strlen(nm)); memcpy(e + 0x20, nm, strlen(nm));
    }
    uint8_t *h = out->p + start;
    fx_w64(h, 0x50); fx_w64(h + 0x18, dm - start); fx_w64(h + 0x20, dsz); fx_w64(h + 0x38, fm - start); fx_w64(h + 0x40, fsz); fx_w64(h + 0x48, 0x50);
    free(pd); free(fo);
}

// A run of bytes [v, v + len) that equals the base game's section bytes from b
typedef struct FxSeg { uint64_t v, len, b; } FxSeg;

// ---- a compressed data level: img cut into entries (LZ4 blocks of literals, stored, zeros), then the bucket-tree table (16 KiB nodes: the root, then sets of
// 682 entries of 0x18: virtual offset, physical offset, type, physical size). A base run is one stored entry, so the patch can still map its bytes.
static int fx_bad_psize;   // set: the first LZ4 entry's table row claims one byte more than LZ4 can make of the entry (a damaged table)
static void fx_compress(const FxBuf *img, const FxSeg *seg, int nseg, FxBuf *phys, FxSeg *pseg, int *npseg, uint64_t *toff, uint64_t *tsize, uint32_t *cnt) {
    typedef struct { uint64_t virt, phys; uint8_t type; uint32_t psize; } E;
    E *e = NULL; uint32_t n = 0, cap = 0; uint64_t v = 0; int si = 0; *npseg = 0;
    const size_t org = phys->n;   // the data level's start in phys: the table's offsets count from it, the runs' (pseg) from the start of phys
    while (v < img->n) {
        if (n == cap) { cap = cap ? cap * 2 : 1024; if (!(e = realloc(e, sizeof *e * cap))) abort(); }
        E *x = &e[n++]; x->virt = v; x->phys = phys->n - org; x->psize = 0;
        while (si < nseg && seg[si].v < v) si++;
        if (si < nseg && seg[si].v == v) {
            x->type = 0; pseg[*npseg].v = phys->n; pseg[*npseg].len = seg[si].len; pseg[(*npseg)++].b = seg[si].b;
            fx_put(phys, img->p + v, (size_t)seg[si].len); v += seg[si].len; continue;
        }
        uint64_t len = (fx_rand() % 64 ? 256 + fx_rand() % 1500 : 20000 + fx_rand() % 45000), end = si < nseg ? seg[si].v : img->n;
        if (end > img->n) end = img->n;
        if (len > end - v) len = end - v;
        int zeros = 1; for (uint64_t i = 0; i < len && zeros; i++) zeros = !img->p[v + i];
        if (zeros) x->type = 1;
        else if (fx_rand() % 5 == 0) { x->type = 0; fx_put(phys, img->p + v, (size_t)len); }
        else {   // one LZ4 sequence: a token with the literal count (15 and count bytes of 255 when it is 15 or more), the literals, no match
            x->type = 3; size_t s0 = phys->n; uint8_t t = (uint8_t)((len < 15 ? len : 15) << 4); fx_put(phys, &t, 1);
            if (len >= 15) { uint64_t r = len - 15; uint8_t ff = 255, b; while (r >= 255) { fx_put(phys, &ff, 1); r -= 255; } b = (uint8_t)r; fx_put(phys, &b, 1); }
            fx_put(phys, img->p + v, (size_t)len); x->psize = (uint32_t)(phys->n - s0);
        }
        v += len;
    }
    fx_align(phys, 16);
    const uint32_t per = (0x4000 - 0x10) / 0x18, sets = (n + per - 1) / per;
    *toff = phys->n - org; *tsize = (uint64_t)0x4000 * (1 + sets); *cnt = n;
    size_t t0 = fx_put(phys, NULL, (size_t)*tsize);
    fx_w32(phys->p + t0 + 4, sets); fx_w64(phys->p + t0 + 8, img->n);
    for (uint32_t i = 0; fx_bad_psize && i < n; i++) if (e[i].type == 3) { uint64_t vs = (i + 1 < n ? e[i + 1].virt : img->n) - e[i].virt; e[i].psize = (uint32_t)(vs + vs / 255 + 17); break; }
    for (uint32_t s = 0; s < sets; s++) {
        uint8_t *bk = phys->p + t0 + 0x4000 * (size_t)(s + 1); uint32_t ne = s + 1 < sets ? per : n - per * s;
        fx_w32(bk, s); fx_w32(bk + 4, ne); fx_w64(bk + 8, s + 1 < sets ? e[per * (s + 1)].virt : img->n);
        for (uint32_t j = 0; j < ne; j++) { const E *x = &e[per * s + j]; uint8_t *r = bk + 0x10 + 0x18 * j; fx_w64(r, x->virt); fx_w64(r + 8, x->phys); r[0x10] = x->type; fx_w32(r + 0x14, x->psize); }
    }
    free(e);
}

// ---- an NCA
typedef struct FxSec {
    int present, fs_type, crypt;       // crypt: 1 none, 2 AES-XTS (the bytes are left as they are: nothing reads it), 3 AES-CTR, 4 a patch (AES-CTR-EX)
    FxBuf body;                        // plain bytes: the layers before data_off, then the PFS0 or the data level
    uint64_t data_off;
    uint64_t toff, tsize; uint32_t tcnt;                      // the compression table, from data_off (tcnt 0: not compressed)
    uint64_t roff, rsize, soff, ssize;                        // a patch's relocation and subsection tables, in the section
    struct { uint64_t off; uint32_t cv; } sub[4]; int nsub;   // a patch's subsections: the bytes from off to the next one's are encrypted with counter value cv
} FxSec;
typedef struct FxNca { int content_type; uint64_t title_id; int rights; uint8_t rid[16], key[16], id[16]; FxSec sec[4]; } FxNca;

static void fx_nca(const FxKeys *k, FxNca *n, FxBuf *out) {
    uint8_t hdr[0xC00] = {0}; uint64_t off = 0xC00; size_t start = out->n;
    memcpy(hdr + 0x200, "NCA3", 4); hdr[0x205] = (uint8_t)n->content_type; fx_w64(hdr + 0x210, n->title_id);
    if (n->rights) memcpy(hdr + 0x230, n->rid, 16);
    else { NxAes a; uint8_t area[0x40]; fx_fill(area, sizeof area); memcpy(area + 0x20, n->key, 16); nx_aes_init(&a, k->kaek);
           for (int i = 0; i < 4; i++) nx_aes_encrypt_block(&a, area + 16 * i, hdr + 0x300 + 16 * i); }
    fx_put(out, NULL, 0xC00);
    for (int i = 0; i < 4; i++) {
        FxSec *S = &n->sec[i]; uint8_t *fh = hdr + 0x400 + 0x200 * i; uint32_t lo = 0x2200u + (uint32_t)i, hi = 0x11110000u + (uint32_t)i;
        if (!S->present) continue;
        fx_align(&S->body, 0x200);
        fx_w32(hdr + 0x240 + 0x10 * i, (uint32_t)(off / 0x200)); fx_w32(hdr + 0x244 + 0x10 * i, (uint32_t)((off + S->body.n) / 0x200));
        fx_w16(fh, 2); fh[2] = (uint8_t)S->fs_type; fh[3] = S->fs_type == NX_FS_PFS0 ? 2 : 3; fh[4] = (uint8_t)S->crypt;
        fx_w64(fh + (S->fs_type == NX_FS_PFS0 ? 0x40 : 0x90), S->data_off);
        fx_w32(fh + 0x140, lo); fx_w32(fh + 0x144, hi);
        if (S->crypt == 4) { fx_w64(fh + 0x100, S->roff); fx_w64(fh + 0x108, S->rsize); memcpy(fh + 0x110, "BKTR", 4); fx_w32(fh + 0x114, 1);
                             fx_w64(fh + 0x120, S->soff); fx_w64(fh + 0x128, S->ssize); memcpy(fh + 0x130, "BKTR", 4); fx_w32(fh + 0x134, 1); }
        if (S->tcnt) { fx_w64(fh + 0x178, S->toff); fx_w64(fh + 0x180, S->tsize); memcpy(fh + 0x188, "BKTR", 4); fx_w32(fh + 0x18C, 1); fx_w32(fh + 0x190, S->tcnt); }
        size_t at = fx_put(out, S->body.p, S->body.n); uint8_t *b = out->p + at;
        if (S->crypt == 3) fx_ctr(n->key, hi, lo, off, b, S->body.n);
        if (S->crypt == 4) {
            for (int j = 0; j < S->nsub; j++) { uint64_t a = S->sub[j].off, z = j + 1 < S->nsub ? S->sub[j + 1].off : S->roff; fx_ctr(n->key, hi, S->sub[j].cv, off + a, b + a, (size_t)(z - a)); }
            fx_ctr(n->key, hi, lo, off + S->roff, b + S->roff, (size_t)(S->body.n - S->roff));   // the tables: the section's own counter
        }
        off += S->body.n;
    }
    fx_w64(hdr + 0x208, off);
    fx_xts_encrypt(k->header, 0, hdr, sizeof hdr);
    memcpy(out->p + start, hdr, sizeof hdr);
}

// ---- a patch section: virt is the section as the game sees it; the runs of seg come from the base, the rest is carried in the patch. The section is the
// carried bytes, the relocation table (0x4000 header: bucket count @4, virtual size @8; buckets: count @4, virtual end @8, entries of 0x14 @0x10: virtual,
// physical, 1 = from the patch) and the subsection table (the same shape, entries of 0x10: offset, the counter value @0xC)
static void fx_patch(const FxBuf *virt, const FxSeg *seg, int nseg, FxSec *S) {
    typedef struct { uint64_t v, p; uint32_t patch; } R;
    R *r = malloc(sizeof *r * (size_t)(2 * nseg + 2)); uint32_t nr = 0; uint64_t v = 0; if (!r) abort();
    for (int i = 0; i <= nseg; i++) {
        uint64_t to = i < nseg ? seg[i].v : virt->n;
        if (to > v) { r[nr].v = v; r[nr].p = S->body.n; r[nr++].patch = 1; fx_put(&S->body, virt->p + v, (size_t)(to - v)); v = to; }
        if (i < nseg && seg[i].len) { r[nr].v = v; r[nr].p = seg[i].b; r[nr++].patch = 0; v += seg[i].len; }
    }
    fx_align(&S->body, 16);
    uint64_t data = S->body.n; const uint32_t per = 0x3FF0 / 0x14, nb = (nr + per - 1) / per;
    S->nsub = 3; S->sub[0].off = 0; S->sub[0].cv = 0x71; S->sub[1].off = (data / 3) & ~15ull; S->sub[1].cv = 0x72; S->sub[2].off = (2 * data / 3) & ~15ull; S->sub[2].cv = 0x75;
    if (S->sub[1].off == 0 || S->sub[2].off == S->sub[1].off) S->nsub = 1;
    S->roff = data; S->rsize = (uint64_t)0x4000 * (1 + nb); S->soff = S->roff + S->rsize; S->ssize = 0x8000;
    size_t t = fx_put(&S->body, NULL, (size_t)S->rsize);
    fx_w32(S->body.p + t + 4, nb); fx_w64(S->body.p + t + 8, virt->n);
    for (uint32_t b = 0; b < nb; b++) {
        uint8_t *bk = S->body.p + t + 0x4000 * (size_t)(b + 1); uint32_t ne = b + 1 < nb ? per : nr - per * b;
        fx_w32(bk + 4, ne); fx_w64(bk + 8, b + 1 < nb ? r[per * (b + 1)].v : virt->n);
        for (uint32_t j = 0; j < ne; j++) { const R *x = &r[per * b + j]; uint8_t *e = bk + 0x10 + 0x14 * j; fx_w64(e, x->v); fx_w64(e + 8, x->p); fx_w32(e + 16, x->patch); }
    }
    t = fx_put(&S->body, NULL, (size_t)S->ssize);
    fx_w32(S->body.p + t + 4, 1); fx_w64(S->body.p + t + 8, data);
    fx_w32(S->body.p + t + 0x4000 + 4, (uint32_t)S->nsub);
    for (int j = 0; j < S->nsub; j++) { uint8_t *e = S->body.p + t + 0x4000 + 0x10 + 0x10 * j; fx_w64(e, S->sub[j].off); fx_w32(e + 0xC, S->sub[j].cv); }
    free(r);
}

// ---- a title in a container: its content NCA (program or data), the meta NCA with its CNMT, a ticket when the content has a title key
enum { FX_TIK_COMMON = 1, FX_TIK_PERSONAL = 2 };
// kind: 0 base, 1 update, 2 add-on. ticket: 0, or FX_TIK_*: the content NCA is then encrypted with a title key. ncz: an entry named *.ncz is added (an NSZ).
// xci: the container is an XCI (a root HFS0 whose "secure" partition holds the files). ctl (fx_title_ctl): the title's control data, NULL for none.
// A title's control data: the files of its control NCA's RomFS (content type 2; type 3 in the CNMT), control.nacp and the icons. A NULL data leaves the file out
typedef struct FxCtl { const uint8_t *nacp; size_t nacp_n; struct { const char *name; const uint8_t *data; size_t n; } icon[2]; } FxCtl;
static void fx_title_ctl(const FxKeys *k, const char *path, uint64_t tid, uint64_t app, int kind, uint32_t ver, FxNca *content, int ticket, int ncz, int xci, const FxCtl *ctl) {
    FxBuf cb = {0}, mb = {0}, cnmt = {0}, pfs = {0}, file = {0}, ctlb = {0}; FxNca meta, cn; FxEnt e[5]; int ne = 0;
    uint8_t tik[0x2C0] = {0}, h[0x20] = {0}, ext[0x10] = {0}, rec[0x38] = {0}, crec[0x38] = {0};
    fx_fill(content->id, 16); content->title_id = tid;
    if (ticket) { content->rights = 1; for (int j = 0; j < 8; j++) content->rid[7 - j] = (uint8_t)(tid >> (8 * j)); memset(content->rid + 8, 0, 8); }
    fx_fill(content->key, 16);
    fx_nca(k, content, &cb);
    { uint8_t sha[32]; nx_sha256(cb.p, cb.n, sha); memcpy(content->id, sha, 16); memcpy(rec, sha, 32); }   // as a console's: the NCA is named by its SHA-256, which the CNMT records
    if (ctl) {   // the control NCA: one RomFS section (a hash level of 0x200 random bytes, then the image), encrypted with its key area
        FxFile cf[3]; int ncf = 0; FxBuf img = {0}; memset(cf, 0, sizeof cf); memset(&cn, 0, sizeof cn);
        if (ctl->nacp) { strcpy(cf[ncf].path, "control.nacp"); cf[ncf].size = ctl->nacp_n; cf[ncf].data = ctl->nacp; cf[ncf++].base = -1; }
        for (int i = 0; i < 2; i++) if (ctl->icon[i].data) { snprintf(cf[ncf].path, sizeof cf[ncf].path, "%s", ctl->icon[i].name); cf[ncf].size = ctl->icon[i].n; cf[ncf].data = ctl->icon[i].data; cf[ncf++].base = -1; }
        fx_romfs(cf, ncf, &img);
        cn.content_type = 2; cn.title_id = tid; fx_fill(cn.id, 16); fx_fill(cn.key, 16);
        cn.sec[0].present = 1; cn.sec[0].fs_type = NX_FS_ROMFS; cn.sec[0].crypt = 3; cn.sec[0].data_off = 0x200;
        { uint8_t *hash = fx_bytes(0x200); fx_put(&cn.sec[0].body, hash, 0x200); free(hash); }
        fx_put(&cn.sec[0].body, img.p, img.n); free(img.p);
        fx_nca(k, &cn, &ctlb); free(cn.sec[0].body.p);
        { uint8_t sha[32]; nx_sha256(ctlb.p, ctlb.n, sha); memcpy(cn.id, sha, 16); memcpy(crec, sha, 32); }   // named by its SHA-256 too, which the CNMT records
        memcpy(crec + 0x20, cn.id, 16); crec[0x36] = 3;
    }
    fx_w64(h, tid); fx_w32(h + 8, ver); h[0xC] = (uint8_t)(0x80 + kind); fx_w16(h + 0xE, sizeof ext); fx_w16(h + 0x10, ctl ? 2 : 1);
    fx_w64(ext, kind ? app : 0);
    memcpy(rec + 0x20, content->id, 16); rec[0x36] = kind == 2 ? 2 : 1;   // the content's type in the title: program, or data
    for (int q = 0; q < 6; q++) rec[0x30 + q] = (uint8_t)((uint64_t)cb.n >> (8 * q));   // its size (6 bytes)
    fx_put(&cnmt, h, sizeof h); fx_put(&cnmt, ext, sizeof ext); fx_put(&cnmt, rec, sizeof rec);
    if (ctl) fx_put(&cnmt, crec, sizeof crec);
    memset(&meta, 0, sizeof meta); meta.content_type = 1; meta.title_id = tid; fx_fill(meta.id, 16); fx_fill(meta.key, 16);
    meta.sec[0].present = 1; meta.sec[0].fs_type = NX_FS_PFS0; meta.sec[0].crypt = 3; meta.sec[0].data_off = 0x200;
    { uint8_t *hash = fx_bytes(0x200); fx_put(&meta.sec[0].body, hash, 0x200); free(hash);
      FxEnt ce; snprintf(ce.name, sizeof ce.name, "%s_%016llx.cnmt", kind == 0 ? "Application" : kind == 1 ? "Patch" : "AddOnContent", (unsigned long long)tid); ce.p = cnmt.p; ce.n = cnmt.n;
      fx_fs0(&ce, 1, 0, &meta.sec[0].body); }
    fx_nca(k, &meta, &mb);
    { uint8_t sha[32]; nx_sha256(mb.p, mb.n, sha); memcpy(meta.id, sha, 16); }
    char hex[33]; fx_hex(hex, content->id, 16); snprintf(e[ne].name, sizeof e[ne].name, "%s.nca", hex); e[ne].p = cb.p; e[ne++].n = cb.n;
    fx_hex(hex, meta.id, 16); snprintf(e[ne].name, sizeof e[ne].name, "%s.cnmt.nca", hex); e[ne].p = mb.p; e[ne++].n = mb.n;
    if (ctl) { fx_hex(hex, cn.id, 16); snprintf(e[ne].name, sizeof e[ne].name, "%s.nca", hex); e[ne].p = ctlb.p; e[ne++].n = ctlb.n; }
    if (ticket) {   // a ticket with an RSA-2048 signature block (0x140): the title key block @+0x40 (the key wrapped with titlekek), the key type @+0x141, the rights ID @+0x160
        NxAes a; nx_aes_init(&a, k->titlekek);
        fx_w32(tik, 0x10004); nx_aes_encrypt_block(&a, content->key, tik + 0x140 + 0x40); tik[0x140 + 0x141] = ticket == FX_TIK_PERSONAL; memcpy(tik + 0x140 + 0x160, content->rid, 16);
        fx_hex(hex, content->rid, 16); snprintf(e[ne].name, sizeof e[ne].name, "%s.tik", hex); e[ne].p = tik; e[ne++].n = sizeof tik;
    }
    if (ncz) { snprintf(e[ne].name, sizeof e[ne].name, "00000000000000000000000000000000.ncz"); e[ne].p = tik; e[ne++].n = 0x100; }
    fx_fs0(e, ne, xci, &pfs);
    if (xci) {   // the card header ("HEAD" @0x100, the root HFS0's offset @0x130), the root with one partition
        uint8_t ch[0x400] = {0}; memcpy(ch + 0x100, "HEAD", 4); fx_w64(ch + 0x130, 0x400); fx_put(&file, ch, sizeof ch);
        FxEnt se; strcpy(se.name, "secure"); se.p = pfs.p; se.n = pfs.n; fx_fs0(&se, 1, 1, &file);
    } else fx_put(&file, pfs.p, pfs.n);
    fx_write(path, file.p, file.n);
    free(cb.p); free(mb.p); free(cnmt.p); free(pfs.p); free(file.p); free(meta.sec[0].body.p); free(ctlb.p);
}
static void fx_title(const FxKeys *k, const char *path, uint64_t tid, uint64_t app, int kind, uint32_t ver, FxNca *content, int ticket, int ncz, int xci) {
    fx_title_ctl(k, path, tid, app, kind, ver, content, ticket, ncz, xci, NULL);
}

// A program NCA's ExeFS: main (an NSO header with the build ID @0x40) and main.npdm
static void fx_exefs(FxSec *S, uint8_t build[32]) {
    uint8_t *hash = fx_bytes(0x200), nso[0x100] = {0}, npdm[0x80]; FxEnt e[2];
    fx_fill(build, 32); memcpy(nso, "NSO0", 4); memcpy(nso + 0x40, build, 32); fx_fill(npdm, sizeof npdm);
    strcpy(e[0].name, "main"); e[0].p = nso; e[0].n = sizeof nso; strcpy(e[1].name, "main.npdm"); e[1].p = npdm; e[1].n = sizeof npdm;
    memset(S, 0, sizeof *S); S->present = 1; S->fs_type = NX_FS_PFS0; S->crypt = 3; S->data_off = 0x200;
    fx_put(&S->body, hash, 0x200); free(hash); fx_fs0(e, 2, 0, &S->body);
}

// ---- a game: base and update dumps, and what they hold
enum { FX_HASH = 0x1000 };   // the bytes before the data level (the hash layers: never read by the reader, random here)
typedef struct FxGame {
    char base[600], upd[600];            // the dumps
    FxFile *bf, *uf; int nbf, nuf;       // the base's files, and the game's with the update
    FxBuf bimg, uimg;                    // the RomFS images (the data level as the game sees it)
    char bbuild[33], ubuild[33];         // main's build ID (first 16 bytes, hex) in each
    uint64_t tid;
} FxGame;
static const uint64_t fx_sizes[] = { 0, 1, 15, 16, 17, 31, 33, 255, 4095, 4096, 4097, 20000, 65535, 65536, 70001, 150000, 3, 1000, 12345, 300000 };
static void fx_name(char *out, size_t n, int i, const char *tag) {
    static const char *dirs[] = { "", "data/", "data/stage/", "data/stage/world-1/", "sound/", "a/", "a/b/c/d/e/", "Message/", "data/ui/" };
    static const char *odd[] = { "c", "c.b", "cb", "A", "b", "data.arc", "data", "Z", "_", "0" };   // names that are prefixes of each other and sort by bytes
    if (i < 10) snprintf(out, n, "%s%s", i < 5 ? "" : "data/", odd[i]);
    else if (i == 10) snprintf(out, n, "sound/\xe9\x9f\xb3\xe6\xa5\xbd.bin");
    else snprintf(out, n, "%s%s%03d.bin", dirs[i % 9], tag, i);
}
static void fx_game(const FxKeys *k, const char *dir, const char *tag, uint64_t tid, int compressed, int xci, FxGame *g) {
    enum { NB = 64 };
    memset(g, 0, sizeof *g); g->tid = tid;
    snprintf(g->base, sizeof g->base, "%s/%s-base.%s", dir, tag, xci ? "xci" : "nsp"); snprintf(g->upd, sizeof g->upd, "%s/%s-update.nsp", dir, tag);
    // the base
    g->bf = calloc(NB, sizeof *g->bf); g->nbf = NB;
    for (int i = 0; i < NB; i++) {
        FxFile *f = &g->bf[i]; fx_name(f->path, sizeof f->path, i, "b"); f->size = fx_sizes[i % 20]; f->base = -1;
        uint8_t *p = fx_bytes((size_t)f->size); if (i % 13 == 5) memset(p, 0, (size_t)f->size); if (i % 13 == 6 && f->size > 600) memset(p + 100, 0, (size_t)f->size - 300);
        f->data = p;
    }
    fx_romfs(g->bf, NB, &g->bimg);
    FxNca bn; memset(&bn, 0, sizeof bn); uint8_t build[32]; FxSec *S = &bn.sec[1];
    fx_exefs(&bn.sec[0], build); fx_hex(g->bbuild, build, 16);
    S->present = 1; S->fs_type = NX_FS_ROMFS; S->crypt = 3; S->data_off = FX_HASH;
    { uint8_t *hash = fx_bytes(FX_HASH); fx_put(&S->body, hash, FX_HASH); free(hash); }
    if (compressed) { FxSeg none; int nn; fx_compress(&g->bimg, &none, 0, &S->body, &none, &nn, &S->toff, &S->tsize, &S->tcnt); }
    else fx_put(&S->body, g->bimg.p, g->bimg.n);
    FxBuf bbody = S->body;   // the base section as stored (kept: the update's shared files are runs of it)
    { uint8_t *keep = malloc(bbody.n); memcpy(keep, bbody.p, bbody.n); bbody.p = keep; }
    fx_title(k, g->base, tid, tid, 0, 0, &bn, 0, 0, xci);
    // the update: a quarter of the base's files gone, a quarter changed, the others as they were, and new ones. A file as it was is a run of the base's
    // section when that is plain; over a compressed base the shared runs are files made of the base's stored bytes
    g->uf = calloc(NB + 24, sizeof *g->uf); FxSeg *seg = calloc(NB + 24, sizeof *seg), *pseg = calloc(NB + 24, sizeof *pseg); int ns = 0;
    for (int i = 0; i < NB; i++) {
        if (i % 4 == 0 && i > 10) continue;
        FxFile *f = &g->uf[g->nuf++]; *f = g->bf[i];
        if (i % 4 == 1) { f->size = fx_sizes[(i + 7) % 20]; f->data = fx_bytes((size_t)f->size); }
        else if (!compressed) f->base = (int64_t)(FX_HASH + g->bf[i].at);
    }
    for (int i = 0; i < 20; i++) {
        FxFile *f = &g->uf[g->nuf++]; fx_name(f->path, sizeof f->path, 100 + i, "u"); f->base = -1;
        if (compressed && i % 2) { f->size = 500 + fx_rand() % 90000; f->base = (int64_t)(fx_rand() % (bbody.n - f->size)); f->data = bbody.p + f->base; }
        else { f->size = fx_sizes[(i * 3 + 1) % 20]; f->data = fx_bytes((size_t)f->size); }
    }
    fx_romfs(g->uf, g->nuf, &g->uimg);
    for (int i = 0; i < g->nuf; i++) if (g->uf[i].base >= 0 && g->uf[i].size) { seg[ns].v = g->uf[i].at; seg[ns].len = g->uf[i].size; seg[ns++].b = (uint64_t)g->uf[i].base; }
    FxNca un; memset(&un, 0, sizeof un); FxBuf virt = {0}; S = &un.sec[1];
    fx_exefs(&un.sec[0], build); fx_hex(g->ubuild, build, 16);
    S->present = 1; S->fs_type = NX_FS_ROMFS; S->crypt = 4; S->data_off = FX_HASH;
    { uint8_t *hash = fx_bytes(FX_HASH); fx_put(&virt, hash, FX_HASH); free(hash); }
    if (compressed) { int np; fx_compress(&g->uimg, seg, ns, &virt, pseg, &np, &S->toff, &S->tsize, &S->tcnt); fx_patch(&virt, pseg, np, S); }
    else { fx_put(&virt, g->uimg.p, g->uimg.n); for (int i = 0; i < ns; i++) seg[i].v += FX_HASH; fx_patch(&virt, seg, ns, S); }
    fx_title(k, g->upd, tid + 0x800, tid, 1, 0x20000, &un, 0, 0, 0);
    free(virt.p); free(seg); free(pseg); free(bn.sec[0].body.p); free(bn.sec[1].body.p); free(un.sec[0].body.p); free(un.sec[1].body.p);
}

// ---- an add-on: one data NCA with a RomFS, encrypted with a title key
typedef struct FxAddon { char path[600]; FxFile *f; int nf; } FxAddon;
static void fx_addon(const FxKeys *k, const char *dir, const char *tag, uint64_t app, int index, int ticket, int crypt, FxAddon *a) {
    enum { N = 9 }; FxNca n; FxBuf img = {0}; memset(&n, 0, sizeof n); memset(a, 0, sizeof *a);
    snprintf(a->path, sizeof a->path, "%s/%s.nsp", dir, tag);
    a->f = calloc(N, sizeof *a->f); a->nf = N;
    for (int i = 0; i < N; i++) { FxFile *f = &a->f[i]; fx_name(f->path, sizeof f->path, 20 + i, "dlc"); f->size = fx_sizes[(i * 5 + 2) % 20]; f->data = fx_bytes((size_t)f->size); f->base = -1; }
    fx_romfs(a->f, N, &img);
    n.content_type = 5; n.sec[0].present = 1; n.sec[0].fs_type = NX_FS_ROMFS; n.sec[0].crypt = crypt; n.sec[0].data_off = FX_HASH;
    { uint8_t *hash = fx_bytes(FX_HASH); fx_put(&n.sec[0].body, hash, FX_HASH); free(hash); }
    fx_put(&n.sec[0].body, img.p, img.n);
    fx_title(k, a->path, app + 0x1000 + (uint64_t)index, app, 2, 0x10000, &n, ticket, 0, 0);
    free(img.p); free(n.sec[0].body.p);
}
#endif
