// Compressed dumps for ncz_test: the fixture's NSP and XCI files (nsp_fixture.h) written again the way nsz writes them, an NSZ or an XCZ: every program
// and public-data NCA over 0x4000 bytes as an NCZ, solid or in blocks (the layout in nx_ncz.c's header). Written from that description, not from nsz.
// The zstd frames are made here of raw and RLE blocks (RFC 8878's uncompressed kinds: every path of the reader, no compressor needed), or, where the
// system has libzstd (Linux: dlopen of libzstd.so.1), really compressed.
#ifndef NCZ_FIXTURE_H
#define NCZ_FIXTURE_H
#include "nsp_fixture.h"
#if defined(__linux__) && !defined(__ANDROID__)
#include <dlfcn.h>
#endif
#define NX_NCZ_HEAD 0x4000   // an NCZ's copy of the NCA's first bytes

// ---- a zstd frame of raw and RLE blocks: with its content size (single segment, as nsz's blocks are) or without it and a window of 2^wlog (a solid stream)
static void zf_frame(FxBuf *o, const uint8_t *p, size_t n, int with_size, int wlog) {
    uint8_t h[16]; size_t hn = 4, maxb = 128 << 10; fx_w32(h, 0xFD2FB528u);
    if (with_size) {
        int fcs = n < 256 ? 0 : n < 65536 + 256 ? 1 : 2;
        h[hn++] = (uint8_t)(fcs << 6 | 0x20);
        if (fcs == 0) h[hn++] = (uint8_t)n; else if (fcs == 1) { fx_w16(h + hn, (uint16_t)(n - 256)); hn += 2; } else { fx_w32(h + hn, (uint32_t)n); hn += 4; }
        if (n && n < maxb) maxb = n;   // the window is the content
    } else {
        h[hn++] = 0; h[hn++] = (uint8_t)((wlog - 10) << 3);
        if (((size_t)1 << wlog) < maxb) maxb = (size_t)1 << wlog;
    }
    fx_put(o, h, hn);
    size_t at = 0;
    do {
        size_t k = n - at < maxb ? n - at : maxb; int last = at + k == n, rle = k > 1;
        for (size_t i = 1; rle && i < k; i++) rle = p[at + i] == p[at];
        uint32_t bh = (uint32_t)last | (uint32_t)rle << 1 | (uint32_t)k << 3; uint8_t b3[3] = { (uint8_t)bh, (uint8_t)(bh >> 8), (uint8_t)(bh >> 16) };
        fx_put(o, b3, 3); fx_put(o, p + at, rle ? 1 : k); at += k;
    } while (at < n);
}

// ---- libzstd's compressor, when the system has it
typedef struct Zl { void *h; void *(*cctx)(void); size_t (*freec)(void *); size_t (*set)(void *, int, int); size_t (*c2)(void *, void *, size_t, const void *, size_t);
                    size_t (*bound)(size_t); unsigned (*iserr)(size_t); } Zl;
static Zl zl; static int zl_tried;
static int zl_ok(void) {
#if defined(__linux__) && !defined(__ANDROID__)
    if (!zl_tried) {
        zl_tried = 1; void *h = dlopen("libzstd.so.1", RTLD_NOW | RTLD_LOCAL);
        if (h) {
            *(void **)&zl.cctx = dlsym(h, "ZSTD_createCCtx"); *(void **)&zl.freec = dlsym(h, "ZSTD_freeCCtx"); *(void **)&zl.set = dlsym(h, "ZSTD_CCtx_setParameter");
            *(void **)&zl.c2 = dlsym(h, "ZSTD_compress2"); *(void **)&zl.bound = dlsym(h, "ZSTD_compressBound"); *(void **)&zl.iserr = dlsym(h, "ZSTD_isError");
            if (zl.cctx && zl.freec && zl.set && zl.c2 && zl.bound && zl.iserr) zl.h = h;
        }
    }
#endif
    (void)zl_tried; return zl.h != NULL;
}
static void zl_frame(FxBuf *o, const uint8_t *p, size_t n, int with_size, int level, int workers) {   // ZSTD_c_compressionLevel 100, contentSizeFlag 200, nbWorkers 400
    void *c = zl.cctx(); zl.set(c, 100, level); zl.set(c, 200, with_size); if (workers) zl.set(c, 400, workers);
    size_t cap = zl.bound(n), at = fx_put(o, NULL, cap), r = zl.c2(c, o->p + at, cap, p, n);
    if (zl.iserr(r)) abort();
    o->n = at + r; zl.freec(c);
}

// ---- an NCA as an NCZ
typedef struct NzOpt {
    int bexp;      // 0: solid; else blocks of 2^bexp bytes
    int real;      // really compressed when libzstd is there (else, and by default, raw and RLE blocks)
    int wlog;      // solid, raw and RLE: the window the frame asks for (0: 23, nsz's at its default level)
    int gap;       // the first range starts this many bytes after 0x4000; the bytes before it are stored as they are (the plain range a reader adds)
    int workers;   // real solid: zstd's threads
} NzOpt;
typedef struct NzRng { uint64_t off, end; uint32_t crypt; uint8_t ctr[8]; } NzRng;
static void nz_xor(const uint8_t key[16], const uint8_t c8[8], uint64_t off, uint8_t *p, size_t n) {   // off and n multiples of 16 here
    NxAes a; uint8_t c[16]; uint64_t b = off >> 4; nx_aes_init(&a, key); memcpy(c, c8, 8);
    for (int j = 0; j < 8; j++) { c[15 - j] = (uint8_t)b; b >>= 8; }
    nx_aes_ctr(&a, c, p, p, n);
}
static void nz_ncz(NxNca *n, const uint8_t *nca, size_t size, const NzOpt *o, FxBuf *out) {
    NzRng r[2048]; int nr = 0, order[4], ns = 0;
    for (int i = 0; i < 4; i++) if (n->sec[i].present) order[ns++] = i;
    for (int a = 0; a < ns; a++) for (int b = a + 1; b < ns; b++) if (n->sec[order[b]].off < n->sec[order[a]].off) { int t = order[a]; order[a] = order[b]; order[b] = t; }
    for (int q = 0; q < ns; q++) {
        int i = order[q]; NxSection *S = &n->sec[i];
        if (nr && r[nr - 1].end < S->off) { r[nr].off = r[nr - 1].end; r[nr].end = S->off; r[nr++].crypt = 1; }
        if (S->crypt_type == 4) {   // a patch: a range per subsection, with its counter value in counter bytes 4 to 7 (the last one covers the tables)
            if (nx_nca_set_base(n, i, NULL, 0)) abort();
            const NxBktr *b = S->bktr;
            for (uint32_t k = 0; k < b->ns; k++) {
                NzRng *x = &r[nr++]; x->off = S->off + b->s[k].off; x->end = S->off + b->s[k].end; x->crypt = 4; memcpy(x->ctr, S->ctr, 8);
                for (int j = 0; j < 4; j++) x->ctr[7 - j] = (uint8_t)(b->s[k].ctr_val >> (8 * j));
            }
        } else { NzRng *x = &r[nr++]; x->off = S->off; x->end = S->off + S->size; x->crypt = (uint32_t)S->crypt_type; memcpy(x->ctr, S->ctr, 8); }
    }
    uint8_t *pl = malloc(size); if (!pl) abort();
    memcpy(pl, nca, size);
    for (int i = 0; i < nr; i++) if (r[i].crypt == 3 || r[i].crypt == 4) nz_xor(n->key_raw, r[i].ctr, r[i].off, pl + r[i].off, (size_t)(r[i].end - r[i].off));
    if (o->gap) {   // the ranges from 0x4000 + gap on; before it, the NCA's bytes as they are
        uint64_t g0 = NX_NCZ_HEAD + (uint64_t)o->gap; int k = 0;
        for (int i = 0; i < nr; i++) { if (r[i].end <= g0) continue; r[k] = r[i]; if (r[k].off < g0) r[k].off = g0; k++; }
        nr = k; memcpy(pl + NX_NCZ_HEAD, nca + NX_NCZ_HEAD, (size_t)(g0 - NX_NCZ_HEAD));
    }
    const uint8_t *body = pl + NX_NCZ_HEAD; size_t blen = size - NX_NCZ_HEAD;
    fx_put(out, nca, NX_NCZ_HEAD);
    { uint8_t h[0x10]; memcpy(h, "NCZSECTN", 8); fx_w64(h + 8, (uint64_t)nr); fx_put(out, h, 0x10); }
    for (int i = 0; i < nr; i++) {
        uint8_t e[0x40] = {0}; fx_w64(e, r[i].off); fx_w64(e + 8, r[i].end - r[i].off); fx_w64(e + 0x10, r[i].crypt);
        if (r[i].crypt == 3 || r[i].crypt == 4) memcpy(e + 0x20, n->key_raw, 16);
        memcpy(e + 0x30, r[i].ctr, 8); fx_put(out, e, 0x40);
    }
    int real = o->real && zl_ok();
    if (o->bexp) {
        uint64_t bs = 1ull << o->bexp; uint32_t nb = (uint32_t)((blen + bs - 1) / bs);
        uint8_t h[0x18] = {0}; memcpy(h, "NCZBLOCK", 8); h[8] = 2; h[9] = 1; h[11] = (uint8_t)o->bexp; fx_w32(h + 12, nb); fx_w64(h + 16, blen); fx_put(out, h, 0x18);
        size_t tab = fx_put(out, NULL, 4ull * nb);
        for (uint32_t i = 0; i < nb; i++) {
            size_t k = (size_t)(i + 1 < nb ? bs : blen - (uint64_t)i * bs); FxBuf f = {0};
            if (real) zl_frame(&f, body + (uint64_t)i * bs, k, 1, 3, 0); else zf_frame(&f, body + (uint64_t)i * bs, k, 1, 0);
            if (f.n < k) { fx_put(out, f.p, f.n); fx_w32(out->p + tab + 4ull * i, (uint32_t)f.n); }
            else { fx_put(out, body + (uint64_t)i * bs, k); fx_w32(out->p + tab + 4ull * i, (uint32_t)k); }   // compressing saved nothing: stored as it is
            free(f.p);
        }
    } else if (real) zl_frame(out, body, blen, 0, 3, o->workers);
    else zf_frame(out, body, blen, 0, o->wlog ? o->wlog : 23);
    free(pl);
}

// ---- a dump written again with NCZs: an NSP as a PFS0, an XCI as the fixture writes one (a card header, a root HFS0 with a "secure" partition)
static int nz_is(const char *name, const char *suffix) { size_t a = strlen(name), b = strlen(suffix); return a >= b && !strcmp(name + a - b, suffix); }
static int nz_convert(const char *in, const char *out, const NzOpt *o) {
    NxContainer c; FxEnt *e; FxBuf *bodies; FxBuf pfs = {0}, file = {0}; int nz = 0;
    if (nx_container_open(&c, in)) return -1;
    e = calloc((size_t)c.n, sizeof *e); bodies = calloc((size_t)c.n, sizeof *bodies); if (!e || !bodies) abort();
    for (int i = 0; i < c.n; i++) {
        const NxEntry *x = &c.e[i]; uint8_t *b = malloc((size_t)x->size + 1); NxNca n;
        if (!b || nx_pread_all(c.fd, b, (size_t)x->size, x->off)) abort();
        snprintf(e[i].name, sizeof e[i].name, "%.79s", x->name);
        if (nz_is(x->name, ".nca") && !nz_is(x->name, ".cnmt.nca") && x->size > NX_NCZ_HEAD && !nx_nca_open(&n, &c, x)) {
            if (n.key_ok && (n.content_type == 0 || n.content_type == 5)) {
                nz_ncz(&n, b, (size_t)x->size, o, &bodies[i]);
                e[i].name[strlen(e[i].name) - 1] = 'z'; e[i].p = bodies[i].p; e[i].n = bodies[i].n; nz++;
            }
            nx_nca_close(&n);
        }
        if (!e[i].p) { fx_put(&bodies[i], b, (size_t)x->size); e[i].p = bodies[i].p; e[i].n = bodies[i].n; }
        free(b);
    }
    fx_fs0(e, c.n, c.kind == 1, &pfs);
    if (c.kind == 1) {
        uint8_t ch[0x400] = {0}; memcpy(ch + 0x100, "HEAD", 4); fx_w64(ch + 0x130, 0x400); fx_put(&file, ch, sizeof ch);
        FxEnt se; strcpy(se.name, "secure"); se.p = pfs.p; se.n = pfs.n; fx_fs0(&se, 1, 1, &file);
    } else fx_put(&file, pfs.p, pfs.n);
    fx_write(out, file.p, file.n);
    for (int i = 0; i < c.n; i++) free(bodies[i].p);
    free(bodies); free(e); free(pfs.p); free(file.p); nx_container_close(&c);
    return nz ? 0 : -1;
}

// where an entry of a written dump lies (the NCZ a test damages), by its name's ending
static int nz_find(const char *path, const char *suffix, uint64_t *off, uint64_t *size) {
    NxContainer c; int found = 0;
    if (nx_container_open(&c, path)) return 0;
    for (int i = 0; i < c.n && !found; i++) if (nz_is(c.e[i].name, suffix)) { *off = c.e[i].off; *size = c.e[i].size; found = 1; }
    nx_container_close(&c); return found;
}
#endif
