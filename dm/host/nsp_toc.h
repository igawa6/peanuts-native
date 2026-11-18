// A plaintext NSP (NSP Box: a PFS0 of unsigned NCAs, dm/tools/nsp.py) read where it is: nsp_toc_build makes the TOC of a NUP file (SPEC §3) that describes it, every
// RomFS file a raw extent of the NSP, and nup_image.h goes on with it as it does with the TOC of a .nup (a package: update/<version> for a game of that version, base for
// version 0, addon/<title ID> for a DLC; each with only its RomFS: the host reads no ExeFS or control data from a container). No keys: an NCA whose header is not
// plain "NCA3", a section that is encrypted, and a content record of a patch are refused. Layout and checks follow dm/tools/nsp.py:
//   PFS0 (magic, count, string table size; entries: offset, size, name offset), NCA3 header 0xC00 (content type 0x205, program ID 0x210, section table 0x240,
//   FS headers 0x400 + 0x200 * i: fs type, hash type, encryption), a RomFS section's data level at the last IVFC level, the CNMT (id, version, type, contents).
#pragma once
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "romfs_image.h"

typedef struct { char name[72]; uint64_t off, size; } nsp_ent;
typedef struct { uint64_t off, size; int romfs; } nsp_sec;   // a section's data (PFS0 or RomFS) at an absolute offset of the NSP

static int nsp_rd(int fd, uint64_t fsize, void *b, uint64_t n, uint64_t off) { return off <= fsize && n <= fsize - off && romfs_image_read(fd, b, (size_t)n, (off_t)off); }
static uint32_t nsp_u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t nsp_u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

// The entries of the PFS0 at base (size bytes); *out is malloc'd. 0 with why set on a failure
static int nsp_pfs(int fd, uint64_t fsize, uint64_t base, uint64_t size, nsp_ent **out, uint32_t *cnt, char *why, size_t wn) {
    uint8_t h[0x10]; *out = NULL; *cnt = 0;
    if (size < 0x10 || !nsp_rd(fd, fsize, h, 0x10, base) || memcmp(h, "PFS0", 4)) { snprintf(why, wn, "not a plaintext PFS0"); return 0; }
    uint32_t n = nsp_u32(h + 4), ss = nsp_u32(h + 8);
    if (n > (1u << 20) || 0x10 + 0x18ull * n + ss > size) { snprintf(why, wn, "the PFS0 header is cut off"); return 0; }
    uint8_t *tab = malloc(0x18ull * n + ss + 1); nsp_ent *e = calloc(n ? n : 1, sizeof *e);
    if (!tab || !e) { free(tab); free(e); snprintf(why, wn, "out of memory"); return 0; }
    if (!nsp_rd(fd, fsize, tab, 0x18ull * n + ss, base + 0x10)) { free(tab); free(e); snprintf(why, wn, "cannot read the PFS0 table"); return 0; }
    const uint8_t *str = tab + 0x18ull * n; tab[0x18ull * n + ss] = 0;
    uint64_t data = base + 0x10 + 0x18ull * n + ss;
    for (uint32_t i = 0; i < n; i++) {
        uint64_t off = nsp_u64(tab + 0x18ull * i), sz = nsp_u64(tab + 0x18ull * i + 8); uint32_t no = nsp_u32(tab + 0x18ull * i + 16);
        if (no >= ss || off > size - (data - base) || sz > size - (data - base) - off) { free(tab); free(e); snprintf(why, wn, "PFS0 entry %u is outside it", i); return 0; }
        size_t L = strnlen((const char *)str + no, ss - no);
        if (!L || L >= sizeof e[i].name || memchr(str + no, '/', L)) { free(tab); free(e); snprintf(why, wn, "PFS0 entry %u has a bad name", i); return 0; }
        memcpy(e[i].name, str + no, L); e[i].off = data + off; e[i].size = sz;
    }
    free(tab); *out = e; *cnt = n; return 1;
}

// The sections of the NCA at off: *ctype, *tid and up to 4 sections; 0 with why on a failure (an encrypted NCA, an unknown filesystem)
static int nsp_nca(int fd, uint64_t fsize, const nsp_ent *n, uint8_t *ctype, uint64_t *tid, nsp_sec sec[4], int *ns, char *why, size_t wn) {
    uint8_t h[0xC00]; *ns = 0;
    if (n->size < 0xC00 || !nsp_rd(fd, fsize, h, 0xC00, n->off)) { snprintf(why, wn, "%s: shorter than an NCA header", n->name); return 0; }
    if (memcmp(h + 0x200, "NCA3", 4)) { snprintf(why, wn, "%s: no plain NCA3 header (an encrypted, retail NCA: only plaintext NSPs are read, with no keys)", n->name); return 0; }
    *ctype = h[0x205]; *tid = nsp_u64(h + 0x210);
    for (int i = 0; i < 4; i++) {
        uint32_t st = nsp_u32(h + 0x240 + 16 * i), en = nsp_u32(h + 0x244 + 16 * i);
        if (!st && !en) continue;
        if (st < 6 || en <= st || (uint64_t)en * 512 > n->size) { snprintf(why, wn, "%s: section %d is outside the NCA", n->name, i); return 0; }
        const uint8_t *fh = h + 0x400 + 0x200 * i; uint8_t fs = fh[2], ht = fh[3], enc = fh[4]; uint64_t span = (uint64_t)(en - st) * 512, base = n->off + (uint64_t)st * 512;
        if (enc != 1) { snprintf(why, wn, "%s: section %d is encrypted (type %u)", n->name, i, enc); return 0; }
        if (ht == 2 && fs == 1) {   // PFS0, hierarchical SHA-256
            uint64_t o = nsp_u64(fh + 0x40), z = nsp_u64(fh + 0x48);
            if (o > span || z > span - o) { snprintf(why, wn, "%s: section %d: the PFS0 is outside it", n->name, i); return 0; }
            sec[*ns].off = base + o; sec[*ns].size = z; sec[*ns].romfs = 0; (*ns)++;
        } else if (ht == 3 && fs == 0) {   // RomFS, IVFC: the data level is the last
            if (memcmp(fh + 8, "IVFC", 4)) { snprintf(why, wn, "%s: section %d: no IVFC header", n->name, i); return 0; }
            uint32_t lv = nsp_u32(fh + 0x14);
            if (lv < 3 || lv > 7) { snprintf(why, wn, "%s: section %d: %u IVFC levels", n->name, i, lv); return 0; }
            uint64_t o = nsp_u64(fh + 0x18 + 24 * (lv - 2)), z = nsp_u64(fh + 0x20 + 24 * (lv - 2));
            if (o > span || z > span - o) { snprintf(why, wn, "%s: section %d: the RomFS is outside it", n->name, i); return 0; }
            sec[*ns].off = base + o; sec[*ns].size = z; sec[*ns].romfs = 1; (*ns)++;
        } else { snprintf(why, wn, "%s: section %d: filesystem %u with hash %u is not read", n->name, i, fs, ht); return 0; }
    }
    return 1;
}

// The TOC being built: directory records, file records, child slots (node ids: 0x80000000 | file index for a file, fixed when the directory count is known), the pool
typedef struct { uint32_t no, nl, first, cnt; } nsp_bdir;
typedef struct { uint32_t no, nl; uint64_t size, off; } nsp_bfile;
typedef struct { nsp_bdir *d; uint64_t nd, cd; nsp_bfile *f; uint64_t nf, cf; uint32_t *k; uint64_t nk, ck; uint8_t *pool; uint64_t np, cp; } nsp_b;

static int nsp_grow(void **p, uint64_t *cap, uint64_t need, size_t esz) {
    if (need <= *cap) return 1;
    uint64_t c = *cap ? *cap : 64; while (c < need) c *= 2;
    void *q = realloc(*p, (size_t)(c * esz)); if (!q) return 0;
    *p = q; *cap = c; return 1;
}
static int nsp_name(nsp_b *b, const uint8_t *s, uint32_t n, uint32_t *no) {
    if (!nsp_grow((void **)&b->pool, &b->cp, b->np + n + 1, 1)) return 0;
    memcpy(b->pool + b->np, s, n); *no = (uint32_t)b->np; b->np += n; return 1;
}
typedef struct { uint32_t id, no, nl; int file; const uint8_t *nm; } nsp_kid;
static int nsp_kid_cmp(const void *a, const void *b) {
    const nsp_kid *x = a, *y = b; uint32_t m = x->nl < y->nl ? x->nl : y->nl; int c = memcmp(x->nm, y->nm, m);
    return c ? c : (x->nl > y->nl) - (x->nl < y->nl);
}

typedef struct { int fd; uint64_t fsize, base; const uint8_t *dt, *ft; uint64_t ds, fs, data, size, dleft, fleft; nsp_b *b; char *why; size_t wn; } nsp_rfs;

// The RomFS directory at table offset d as a TOC directory: its id in *id. Children are sorted by name bytes (the tables' order is not: the format does not say)
static int nsp_walk(nsp_rfs *r, uint32_t d, uint32_t nlen, const uint8_t *nm, int depth, uint32_t *id) {
    nsp_b *b = r->b;
    if (depth > 255) { snprintf(r->why, r->wn, "RomFS nested too deep"); return 0; }
    uint32_t me = (uint32_t)b->nd, no; if (!nsp_grow((void **)&b->d, &b->cd, b->nd + 1, sizeof *b->d) || !nsp_name(b, nm, nlen, &no)) goto oom;
    b->d[b->nd++] = (nsp_bdir){no, nlen, 0, 0};
    uint32_t first_d = nsp_u32(r->dt + d + 8), first_f = nsp_u32(r->dt + d + 12);
    nsp_kid *kids = NULL; uint64_t nk = 0, ck = 0;
    for (uint32_t c = first_d; c != 0xFFFFFFFFu;) {
        if (r->dleft-- == 0 || c + 0x18ull > r->ds) { snprintf(r->why, r->wn, "bad or cyclic RomFS directory"); free(kids); return 0; }
        uint32_t nl = nsp_u32(r->dt + c + 20), sib = nsp_u32(r->dt + c + 4), ch = 0;
        if (nl > r->ds - c - 0x18) { snprintf(r->why, r->wn, "RomFS directory name out of bounds"); free(kids); return 0; }
        if (!nsp_grow((void **)&kids, &ck, nk + 1, sizeof *kids)) { free(kids); goto oom; }
        if (!nsp_walk(r, c, nl, r->dt + c + 0x18, depth + 1, &ch)) { free(kids); return 0; }
        kids[nk++] = (nsp_kid){ch, b->d[ch].no, nl, 0, b->pool + b->d[ch].no};
        c = sib;
    }
    for (uint32_t c = first_f; c != 0xFFFFFFFFu;) {
        if (r->fleft-- == 0 || c + 0x20ull > r->fs) { snprintf(r->why, r->wn, "bad or cyclic RomFS file"); free(kids); return 0; }
        uint64_t fo = nsp_u64(r->ft + c + 8), sz = nsp_u64(r->ft + c + 16); uint32_t sib = nsp_u32(r->ft + c + 4), nl = nsp_u32(r->ft + c + 28);
        if (nl > r->fs - c - 0x20 || fo > r->size - r->data || sz > r->size - r->data - fo) { snprintf(r->why, r->wn, "RomFS file out of bounds"); free(kids); return 0; }
        uint32_t fno;
        if (!nsp_grow((void **)&kids, &ck, nk + 1, sizeof *kids) || !nsp_grow((void **)&b->f, &b->cf, b->nf + 1, sizeof *b->f) || !nsp_name(b, r->ft + c + 0x20, nl, &fno)) { free(kids); goto oom; }
        b->f[b->nf] = (nsp_bfile){fno, nl, sz, sz ? r->base + r->data + fo : 0};
        kids[nk++] = (nsp_kid){(uint32_t)b->nf, fno, nl, 1, NULL}; b->nf++;
        c = sib;
    }
    for (uint64_t i = 0; i < nk; i++) kids[i].nm = b->pool + kids[i].no;   // the pool may have moved while it grew
    if (nk > 1) qsort(kids, (size_t)nk, sizeof *kids, nsp_kid_cmp);
    if (!nsp_grow((void **)&b->k, &b->ck, b->nk + nk, sizeof *b->k)) { free(kids); goto oom; }
    b->d[me].first = (uint32_t)b->nk; b->d[me].cnt = (uint32_t)nk;
    for (uint64_t i = 0; i < nk; i++) b->k[b->nk++] = kids[i].file ? (0x80000000u | kids[i].id) : kids[i].id;
    free(kids); *id = me; return 1;
oom:
    snprintf(r->why, r->wn, "out of memory"); return 0;
}

// One package's RomFS (the section's data at off, size bytes) as TOC directories; *romfs is its root's id
static int nsp_romfs(int fd, uint64_t fsize, nsp_b *b, uint64_t off, uint64_t size, uint32_t *romfs, char *why, size_t wn) {
    uint8_t h[0x50];
    if (size < 0x50 || !nsp_rd(fd, fsize, h, 0x50, off) || nsp_u64(h) != 0x50) { snprintf(why, wn, "bad RomFS header"); return 0; }
    uint64_t dt = nsp_u64(h + 0x18), ds = nsp_u64(h + 0x20), ft = nsp_u64(h + 0x38), fs = nsp_u64(h + 0x40), data = nsp_u64(h + 0x48);
    if (dt > size || ds > size - dt || ft > size || fs > size - ft || data > size || ds < 0x18) { snprintf(why, wn, "RomFS tables out of bounds"); return 0; }
    uint8_t *tab = malloc((size_t)(ds + fs + 1));
    if (!tab) { snprintf(why, wn, "out of memory"); return 0; }
    if (!nsp_rd(fd, fsize, tab, ds, off + dt) || !nsp_rd(fd, fsize, tab + ds, fs, off + ft)) { free(tab); snprintf(why, wn, "cannot read the RomFS tables"); return 0; }
    nsp_rfs r = {fd, fsize, off, tab, tab + ds, ds, fs, data, size, ds / 0x18 + 1, fs / 0x20 + 1, b, why, wn};
    int ok = nsp_walk(&r, 0, 0, tab, 0, romfs);
    free(tab); return ok;
}

// Builds the TOC (malloc'd, *toc_size bytes) and the package count. 0 with why on a failure
static int nsp_toc_build(int fd, uint64_t fsize, uint8_t **toc, uint64_t *toc_size, uint32_t *npk, char *why, size_t wn) {
    nsp_ent *ents = NULL; uint32_t ne = 0; nsp_b b; memset(&b, 0, sizeof b);
    struct { uint64_t tid; uint32_t ver, root, romfs; uint8_t kind, flags; } pk[64]; uint32_t np = 0;
    int ok = 0;
    if (!nsp_pfs(fd, fsize, 0, fsize, &ents, &ne, why, wn)) goto done;
    for (uint32_t i = 0; i < ne; i++) {
        size_t L = strlen(ents[i].name);
        if (L < 9 || strcmp(ents[i].name + L - 9, ".cnmt.nca")) continue;
        uint8_t ct, ctype; uint64_t tid; nsp_sec sec[4]; int ns;
        if (!nsp_nca(fd, fsize, &ents[i], &ct, &tid, sec, &ns, why, wn)) goto done;
        nsp_ent *cn; uint32_t cc;
        if (ct != 1 || ns < 1 || sec[0].romfs || !nsp_pfs(fd, fsize, sec[0].off, sec[0].size, &cn, &cc, why, wn)) { if (!why[0]) snprintf(why, wn, "%s: not a metadata NCA", ents[i].name); goto done; }
        if (cc != 1 || cn[0].size < 0x30 || cn[0].size > (1u << 20)) { free(cn); snprintf(why, wn, "%s: expected one .cnmt", ents[i].name); goto done; }
        uint8_t *c = malloc((size_t)cn[0].size); int rd = c && nsp_rd(fd, fsize, c, cn[0].size, cn[0].off); uint64_t csz = cn[0].size; free(cn);
        if (!rd) { free(c); snprintf(why, wn, "%s: cannot read the .cnmt", ents[i].name); goto done; }
        uint64_t id = nsp_u64(c); uint32_t ver = nsp_u32(c + 8); uint8_t typ = c[12]; uint16_t exs, cnt; memcpy(&exs, c + 14, 2); memcpy(&cnt, c + 16, 2);
        if (typ != 0x80 && typ != 0x82) { free(c); snprintf(why, wn, "%s: a content meta of type %#x; only a game snapshot or a DLC is read", ents[i].name, typ); goto done; }
        if (0x20 + (uint64_t)exs + 0x38ull * cnt + 0x20 > csz) { free(c); snprintf(why, wn, "%s: the .cnmt is cut off", ents[i].name); goto done; }
        uint32_t romfs = 0xFFFFFFFFu, rno;
        for (uint32_t j = 0; j < cnt; j++) {
            const uint8_t *r = c + 0x20 + exs + 0x38ull * j; uint8_t want = typ == 0x80 ? 1 : 2; char nm[40];
            if (r[0x36] != want) continue;   // a game's Program NCA, a DLC's Data NCA; the control NCA and the rest hold nothing the host reads
            for (int q = 0; q < 16; q++) snprintf(nm + 2 * q, 3, "%02x", r[0x20 + q]);
            strcat(nm, ".nca");
            nsp_ent *n = NULL; for (uint32_t q = 0; q < ne; q++) if (!strcmp(ents[q].name, nm)) n = &ents[q];
            if (!n) { free(c); snprintf(why, wn, "content %s is not in the NSP", nm); goto done; }
            nsp_sec s2[4]; int n2; uint8_t t2; uint64_t tid2;
            if (!nsp_nca(fd, fsize, n, &t2, &tid2, s2, &n2, why, wn)) { free(c); goto done; }
            if (tid2 != id) { free(c); snprintf(why, wn, "%s: program ID %016llX, the record's is %016llX", nm, (unsigned long long)tid2, (unsigned long long)id); goto done; }
            for (int q = 0; q < n2; q++) if (s2[q].romfs && romfs == 0xFFFFFFFFu) {
                uint32_t rid;
                if (!nsp_romfs(fd, fsize, &b, s2[q].off, s2[q].size, &rid, why, wn)) { free(c); goto done; }
                romfs = rid;
            }
        }
        free(c);
        if (np >= 64) { snprintf(why, wn, "too many content metas"); goto done; }
        // the package's root directory: its only child is the RomFS folder, named "romfs"
        if (!nsp_grow((void **)&b.d, &b.cd, b.nd + 1, sizeof *b.d) || !nsp_name(&b, (const uint8_t *)"", 0, &rno)) { snprintf(why, wn, "out of memory"); goto done; }
        uint32_t me = (uint32_t)b.nd; b.d[b.nd++] = (nsp_bdir){rno, 0, 0, 0};
        if (romfs != 0xFFFFFFFFu) {
            uint32_t no2; if (!nsp_grow((void **)&b.k, &b.ck, b.nk + 1, sizeof *b.k) || !nsp_name(&b, (const uint8_t *)"romfs", 5, &no2)) { snprintf(why, wn, "out of memory"); goto done; }
            b.d[romfs].no = no2; b.d[romfs].nl = 5;   // the RomFS root, whose own name is empty, is the package's "romfs" folder
            b.d[me].first = (uint32_t)b.nk; b.d[me].cnt = 1; b.k[b.nk++] = romfs;
        }
        (void)ctype;
        pk[np].tid = id; pk[np].root = me; pk[np].romfs = romfs; pk[np].ver = ver;
        pk[np].kind = typ == 0x82 ? 2 : (ver ? 1 : 0); pk[np].flags = (typ == 0x82 || !ver) ? 0 : 1;   // a snapshot of a version is a base-less update (flag 1)
        np++;
    }
    if (!np) { snprintf(why, wn, "no metadata NCA (*.cnmt.nca): not an NSP Box package"); goto done; }
    {   // emit
        uint64_t nd = b.nd, nf = b.nf, o_dir = 0x30 + 0x40ull * np, o_file = o_dir + 0x18 * nd, o_kids = o_file + 0x50 * nf, o_pool = o_kids + 4 * b.nk, total = o_pool + b.np;
        uint8_t *t = calloc(1, (size_t)total); if (!t) { snprintf(why, wn, "out of memory"); goto done; }
        memcpy(t, "TOC\x01", 4); memcpy(t + 4, &np, 4); memcpy(t + 8, &nd, 8); memcpy(t + 0x10, &nf, 8); memcpy(t + 0x18, &o_pool, 8); memcpy(t + 0x20, &b.np, 8);
        for (uint32_t i = 0; i < np; i++) {
            uint8_t *p = t + 0x30 + 0x40ull * i; uint32_t none = 0xFFFFFFFFu;
            memcpy(p, &pk[i].tid, 8); memcpy(p + 8, &pk[i].root, 4); memcpy(p + 12, &none, 4); memcpy(p + 16, &pk[i].romfs, 4); memcpy(p + 20, &pk[i].ver, 4); p[0x18] = pk[i].kind; p[0x19] = pk[i].flags;
        }
        for (uint64_t i = 0; i < nd; i++) { uint8_t *p = t + o_dir + 0x18 * i; memcpy(p, &b.d[i].no, 4); memcpy(p + 4, &b.d[i].nl, 4); memcpy(p + 8, &b.d[i].first, 4); memcpy(p + 12, &b.d[i].cnt, 4); }
        for (uint64_t i = 0; i < nf; i++) {
            uint8_t *p = t + o_file + 0x50 * i; memcpy(p, &b.f[i].no, 4); memcpy(p + 4, &b.f[i].nl, 4); memcpy(p + 8, &b.f[i].size, 8); memcpy(p + 0x10, &b.f[i].off, 8); memcpy(p + 0x18, &b.f[i].size, 8);
        }
        for (uint64_t i = 0; i < b.nk; i++) { uint32_t v = (b.k[i] & 0x80000000u) ? (uint32_t)nd + (b.k[i] & 0x7FFFFFFFu) : b.k[i]; memcpy(t + o_kids + 4 * i, &v, 4); }
        memcpy(t + o_pool, b.pool, (size_t)b.np);
        *toc = t; *toc_size = total; *npk = np; ok = 1;
    }
done:
    free(ents); free(b.d); free(b.f); free(b.k); free(b.pool);
    return ok;
}
