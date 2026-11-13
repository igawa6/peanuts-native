// libnximport: keys, containers, NCA sections (CTR and BKTR, compressed), PFS0/RomFS trees, CNMT titles (nx_internal.h).
// Formats as documented publicly (switchbrew NCA/NSP/XCI/CNMT pages); hactool (ISC, SciresM) was consulted for the CTR/BKTR counter conventions,
// Atmosphère's fssystem (as vendored by yuzu/Eden: fssystem_nca_header.h, fssystem_compressed_storage.h, fssystem_nca_file_system_driver.cpp) for the compression layer.
// Threads: once a section is set up (nx_nca_open, nx_nca_set_base, nx_sec_prepare) its reads only touch read-only state plus the caller's NxCompCache,
// so nx_sec_read_tc / nx_node_read_tc may run on several threads at once (each with its own cache); the trees and nx_sec_read (built-in cache) are single-threaded.
#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#include "nximport.h"
#include "nx_internal.h"
#include <ctype.h>
#include <errno.h>
#include <stdatomic.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static _Thread_local char nx_errbuf[512];
void nx_err(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); vsnprintf(nx_errbuf, sizeof nx_errbuf, fmt, ap); va_end(ap);
    static _Atomic int dbg = -1; if (dbg < 0) { const char *e = getenv("NX_DEBUG"); dbg = e && *e && *e != '0'; }
    if (dbg) fprintf(stderr, "nximport: %s\n", nx_errbuf);   // NX_DEBUG=1: every error as it is set, even ones a caller goes on from
}
const char *nximport_last_error(void) { return nx_errbuf; }

static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

int nx_pread_all(int fd, void *buf, size_t n, uint64_t off) {
    uint8_t *p = buf;
    while (n) {
        ssize_t r = pread(fd, p, n, (off_t)off);
        if (r < 0) { if (errno == EINTR) continue; nx_err("read failed at %llu: %s", (unsigned long long)off, strerror(errno)); return -1; }
        if (!r) { nx_err("read beyond the end of the file at %llu", (unsigned long long)off); return -1; }
        p += r; n -= (size_t)r; off += (uint64_t)r;
    }
    return 0;
}

// ---------------------------------------------------------------- keys
NxKeys nx_keys;

int nx_hex(const char *s, uint8_t *out, size_t n) {
    for (size_t i = 0; i < n; i++) {
        int v = 0;
        for (int k = 0; k < 2; k++) {
            char c = s[2 * i + k]; int d;
            if (c >= '0' && c <= '9') d = c - '0'; else if (c >= 'a' && c <= 'f') d = c - 'a' + 10; else if (c >= 'A' && c <= 'F') d = c - 'A' + 10; else return -1;
            v = v << 4 | d;
        }
        out[i] = (uint8_t)v;
    }
    return s[2 * n] == 0 ? 0 : -1;
}

static void trim(char *s) { size_t n = strlen(s); while (n && isspace((unsigned char)s[n - 1])) s[--n] = 0; while (*s && isspace((unsigned char)*s)) memmove(s, s + 1, strlen(s)); }

static int load_title_keys(const char *path) {
    FILE *f = fopen(path, "r"); if (!f) return 0;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        char *eq = strchr(line, '='); if (!eq) continue; *eq = 0; char *k = line, *v = eq + 1; trim(k); trim(v);
        NxTitleKey t; if (nx_hex(k, t.rights, 16) || nx_hex(v, t.key, 16)) continue;
        NxTitleKey *nt = realloc(nx_keys.tk, sizeof *nt * (size_t)(nx_keys.ntk + 1)); if (!nt) break; nx_keys.tk = nt; nx_keys.tk[nx_keys.ntk++] = t;
    }
    fclose(f); return 1;
}

int nximport_keys(const char *prod_keys_path) {
    FILE *f = fopen(prod_keys_path, "r");
    if (!f) { nx_err("cannot open %s: %s", prod_keys_path, strerror(errno)); return -1; }
    free(nx_keys.tk); memset(&nx_keys, 0, sizeof nx_keys);
    char line[1024];
    while (fgets(line, sizeof line, f)) {
        char *eq = strchr(line, '='); if (!eq) continue; *eq = 0; char *k = line, *v = eq + 1; trim(k); trim(v);
        if (!strcmp(k, "header_key")) { if (!nx_hex(v, nx_keys.header_key, 32)) { nx_keys.has_header_key = 1; } continue; }
        const char *pre[3] = { "key_area_key_application_", "key_area_key_ocean_", "key_area_key_system_" };
        for (int i = 0; i < 3; i++) {
            size_t pl = strlen(pre[i]);
            if (!strncmp(k, pre[i], pl) && strlen(k) == pl + 2) { int g = (int)strtol(k + pl, NULL, 16); if (g >= 0 && g < 32 && !nx_hex(v, nx_keys.kaek[i][g], 16)) { nx_keys.has_kaek[i][g] = 1; } }
        }
        if (!strncmp(k, "titlekek_", 9) && strlen(k) == 11) { int g = (int)strtol(k + 9, NULL, 16); if (g >= 0 && g < 32 && !nx_hex(v, nx_keys.titlekek[g], 16)) { nx_keys.has_titlekek[g] = 1; } }
    }
    fclose(f);
    if (!nx_keys.has_header_key) { nx_err("%s has no header_key", prod_keys_path); return -1; }
    if (!nx_keys.has_kaek[0][0]) { nx_err("%s has no key_area_key_application_00", prod_keys_path); return -1; }
    // title.keys beside it
    { char p[1200]; const char *sl = strrchr(prod_keys_path, '/'); size_t dl = sl ? (size_t)(sl - prod_keys_path + 1) : 0;
      if (dl < sizeof p - 16) { memcpy(p, prod_keys_path, dl); strcpy(p + dl, "title.keys"); load_title_keys(p); } }
    nx_keys.loaded = 1;
    return 0;
}

// ---------------------------------------------------------------- containers
static int add_entry(NxContainer *c, const char *name, size_t nlen, uint64_t off, uint64_t size) {
    NxEntry *e = realloc(c->e, sizeof *e * (size_t)(c->n + 1)); if (!e) { nx_err("out of memory"); return -1; }
    c->e = e; e = &c->e[c->n++]; memset(e, 0, sizeof *e);
    if (nlen >= sizeof e->name) nlen = sizeof e->name - 1;
    memcpy(e->name, name, nlen); e->off = off; e->size = size; return 0;
}

// PFS0 (entry 0x18) or HFS0 (entry 0x40) header at off; data begins at off + header size. 0 ok
static int read_fs0(NxContainer *c, uint64_t off, int hfs, uint64_t *data_start) {
    uint8_t h[0x10]; if (nx_pread_all(c->fd, h, 0x10, off)) return -1;
    if (memcmp(h, hfs ? "HFS0" : "PFS0", 4)) { nx_err("%s: no %s header at %llu", c->path, hfs ? "HFS0" : "PFS0", (unsigned long long)off); return -1; }
    uint32_t n = u32(h + 4), ss = u32(h + 8); size_t es = hfs ? 0x40 : 0x18;
    if (n > 100000 || ss > (64u << 20)) { nx_err("%s: bad %s header", c->path, hfs ? "HFS0" : "PFS0"); return -1; }
    uint8_t *tab = malloc(es * n + ss + 1); if (!tab) { nx_err("out of memory"); return -1; }
    if (nx_pread_all(c->fd, tab, es * n + ss, off + 0x10)) { free(tab); return -1; }
    const char *st = (const char *)tab + es * n; tab[es * n + ss] = 0;
    uint64_t data = off + 0x10 + es * n + ss; if (data_start) *data_start = data;
    for (uint32_t i = 0; i < n; i++) {
        const uint8_t *e = tab + es * i; uint32_t no = u32(e + 0x10);
        if (no >= ss) { nx_err("%s: entry name out of range", c->path); free(tab); return -1; }
        if (add_entry(c, st + no, strnlen(st + no, ss - no), data + u64(e), u64(e + 8))) { free(tab); return -1; }
    }
    free(tab); return 0;
}

static int load_tickets(NxContainer *c) {
    for (int i = 0; i < c->n; i++) {
        const NxEntry *e = &c->e[i]; size_t l = strlen(e->name);
        if (l < 4 || strcmp(e->name + l - 4, ".tik") || e->size < 0x2C0 || e->size > 0x4000) continue;
        uint8_t *t = malloc((size_t)e->size); if (!t) continue;
        if (!nx_pread_all(c->fd, t, (size_t)e->size, e->off)) {
            uint32_t sig = u32(t); size_t body = sig == 0x10000 ? 0x240 : sig == 0x10004 ? 0x140 : sig == 0x10005 ? 0x80 : sig == 0x10001 ? 0x140 : sig == 0x10002 ? 0x80 : sig == 0x10003 ? 0x60 : 0x140;
            if (body + 0x170 <= e->size && t[body + 0x141] == 0) {   // a common ticket: the title key block holds the key in the clear
                NxTitleKey tk; memcpy(tk.key, t + body + 0x40, 16); memcpy(tk.rights, t + body + 0x160, 16);
                NxTitleKey *nt = realloc(c->tik, sizeof *nt * (size_t)(c->ntik + 1)); if (nt) { c->tik = nt; c->tik[c->ntik++] = tk; }
            }
        }
        free(t);
    }
    return 0;
}

int nx_container_open(NxContainer *c, const char *path) {
    memset(c, 0, sizeof *c); c->fd = -1;
    snprintf(c->path, sizeof c->path, "%s", path);
    c->fd = open(path, O_RDONLY | O_CLOEXEC);
    if (c->fd < 0) { nx_err("cannot open %s: %s", path, strerror(errno)); return -1; }
    struct stat st; if (fstat(c->fd, &st) || !S_ISREG(st.st_mode)) { nx_err("%s is not a file", path); goto fail; }
#ifdef POSIX_FADV_SEQUENTIAL
    (void)posix_fadvise(c->fd, 0, 0, POSIX_FADV_SEQUENTIAL);   // the importer streams: a larger readahead window
#elif defined(F_RDAHEAD)
    (void)fcntl(c->fd, F_RDAHEAD, 1);   // macOS has no posix_fadvise: its readahead switch (on by default; asked for all the same)
#endif
    uint8_t h[0x400]; if ((uint64_t)st.st_size < 0x400) { nx_err("%s: too small", path); goto fail; }
    if (nx_pread_all(c->fd, h, sizeof h, 0)) goto fail;
    if (!memcmp(h, "PFS0", 4)) { c->kind = 0; if (read_fs0(c, 0, 0, NULL)) goto fail; }
    else if (!memcmp(h + 0x100, "HEAD", 4)) {   // XCI: the root HFS0 at hfs0_offset, its partitions' entries relative to the end of the root header
        c->kind = 1; uint64_t root = u64(h + 0x130), rsize = u64(h + 0x138);
        NxContainer r; memset(&r, 0, sizeof r); r.fd = c->fd; snprintf(r.path, sizeof r.path, "%s", path);
        uint64_t data; if (read_fs0(&r, root, 1, &data)) { free(r.e); goto fail; }
        (void)rsize;
        for (int i = 0; i < r.n; i++) {   // update, normal, secure, logo: the secure partition holds the game; take every partition (an NSP-like whole)
            if (!strcmp(r.e[i].name, "update")) continue;   // the system update partition is not a title of the game
            if (read_fs0(c, r.e[i].off, 1, NULL)) { free(r.e); goto fail; }
        }
        free(r.e);
    } else if (!memcmp(h + 0x200, "NCA3", 4) || !memcmp(h + 0x200, "NCA2", 4)) { c->kind = 2; if (add_entry(c, "nca", 3, 0, (uint64_t)st.st_size)) goto fail; }
    else {   // an NCA is encrypted: decrypt its header to tell
        uint8_t hdr[0x400]; NxAes a1, a2;
        if (!nx_keys.has_header_key) { nx_err("%s: not an NSP or XCI (and no header key to try an NCA)", path); goto fail; }
        if (nx_pread_all(c->fd, hdr, 0x400, 0)) goto fail;
        nx_aes_init(&a1, nx_keys.header_key); nx_aes_init(&a2, nx_keys.header_key + 16);
        nx_aes_xts_decrypt(&a1, &a2, 0, 0x200, hdr, hdr, 0x400);
        if (memcmp(hdr + 0x200, "NCA3", 4) && memcmp(hdr + 0x200, "NCA2", 4)) { nx_err("%s: not an NSP, XCI or NCA", path); goto fail; }
        c->kind = 2; if (add_entry(c, "nca", 3, 0, (uint64_t)st.st_size)) goto fail;
    }
    load_tickets(c);
    return 0;
fail:
    nx_container_close(c); return -1;
}

void nx_container_close(NxContainer *c) { if (c->fd >= 0) close(c->fd); c->fd = -1; free(c->e); c->e = NULL; c->n = 0; free(c->tik); c->tik = NULL; c->ntik = 0; }

const NxEntry *nx_container_find(const NxContainer *c, const char *name) {
    for (int i = 0; i < c->n; i++) if (!strcasecmp(c->e[i].name, name)) return &c->e[i];
    return NULL;
}

// ---------------------------------------------------------------- NCA
static void ctr_for(uint8_t ctr[16], const uint8_t tmpl[16], uint64_t abs_off) {
    memcpy(ctr, tmpl, 8); uint64_t o = abs_off >> 4;
    for (int j = 0; j < 8; j++) { ctr[15 - j] = (uint8_t)o; o >>= 8; }
}

// Plain CTR bytes of the NCA at absolute offset abs (whole file positions), counter template tmpl, into buf; ctr_read_at: the bytes at file position abs decrypted with the
// counter of position cabs (PEANUTS: a sparse section's data lies elsewhere in the body than the virtual position its counter counts; the two differ by a multiple of 16)
static int ctr_read_at(NxNca *n, const uint8_t tmpl[16], uint64_t abs, uint64_t cabs, uint8_t *buf, size_t len) {
    if ((abs ^ cabs) & 15) { nx_err("%s: data at %#llx with the counter of %#llx: not 16-byte aligned to each other", n->id, (unsigned long long)abs, (unsigned long long)cabs); return -1; }
    while (len) {
        uint64_t blk = abs & ~15ull, cblk = cabs & ~15ull; size_t in = (size_t)(abs - blk);
        if (in || len < 16) {
            uint8_t t[16], ctr[16]; if (nx_pread_all(n->fd, t, 16, n->base_off + blk)) return -1;
            ctr_for(ctr, tmpl, cblk); nx_aes_ctr(&n->key, ctr, t, t, 16);
            size_t k = 16 - in; if (k > len) k = len; memcpy(buf, t + in, k); buf += k; abs += k; cabs += k; len -= k;
        } else {
            size_t k = len & ~15ull; uint8_t ctr[16];
            if (nx_pread_all(n->fd, buf, k, n->base_off + abs)) return -1;
            ctr_for(ctr, tmpl, cabs); nx_aes_ctr(&n->key, ctr, buf, buf, k);
            buf += k; abs += k; cabs += k; len -= k;
        }
    }
    return 0;
}
static int ctr_read(NxNca *n, const uint8_t tmpl[16], uint64_t abs, uint8_t *buf, size_t len) { return ctr_read_at(n, tmpl, abs, abs, buf, len); }

static int cmp_reloc(const void *a, const void *b);
static const NxReloc *find_reloc_in(const NxReloc *r, uint32_t nr, uint64_t v) {
    uint32_t lo = 0, hi = nr;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (r[m].virt <= v) lo = m + 1; else hi = m; }
    return lo ? &r[lo - 1] : NULL;
}

// PEANUTS: a sparse section's bytes (NxSparse): each entry's range from the body (encrypted as the section, with its virtual position's counter) or zeros
static int sparse_read(NxNca *n, int s, uint64_t off, uint8_t *buf, size_t len) {
    NxSection *S = &n->sec[s]; const NxSparse *sp = S->sparse;
    while (len) {
        const NxReloc *r = sp->nr ? find_reloc_in(sp->r, sp->nr, off) : NULL;
        if (!sp->nr) { memset(buf, 0, len); return 0; }
        if (!r || off >= r->end) { nx_err("%s: offset %llu is outside section %d's sparse table", n->id, (unsigned long long)off, s); return -1; }
        size_t k = len; if (off + k > r->end) k = (size_t)(r->end - off);
        if (r->patch) memset(buf, 0, k);   // storage 1: zeros (the update supplies these bytes)
        else {
            uint64_t abs = sp->phys_base + r->phys + (off - r->virt);
            if (S->crypt_type == 1) { if (nx_pread_all(n->fd, buf, k, n->base_off + abs)) return -1; }
            else if (S->crypt_type == 3) { if (!n->key_ok) { nx_err("%s: %s", n->id, n->key_why); return -1; } if (ctr_read_at(n, S->ctr, abs, S->off + off, buf, k)) return -1; }
            else { nx_err("%s: sparse section %d uses encryption type %d (not supported)", n->id, s, S->crypt_type); return -1; }
        }
        buf += k; off += k; len -= k;
    }
    return 0;
}

// PEANUTS: the sparse table (SparseInfo @0x148: table offset (8) and size (8) from physical_offset, BucketTree header (0x10), physical_offset (8) @0x168, generation (2) @0x170).
// The table is AES-CTR with the section's counter whose generation half is the sparse generation << 16, at its own body position; its layout is the BKTR relocation
// table's (a 0x4000 header node: set count @4, end offset @8; then 0x4000 entry sets: count @4, end @8, entries @0x10 of 0x14: virtual (8), physical (8), storage index (4))
static int sparse_load(NxNca *n, int s, const uint8_t *fh) {
    NxSection *S = &n->sec[s]; uint8_t *tb = NULL; NxSparse *sp = NULL;
    uint64_t toff = u64(fh + 0x148), tsize = u64(fh + 0x150), pbase = u64(fh + 0x168); uint32_t cnt = u32(fh + 0x158 + 8); uint16_t gen = (uint16_t)(fh[0x170] | fh[0x171] << 8);
    if (memcmp(fh + 0x158, "BKTR", 4) || u32(fh + 0x15C) != 1) NX_FAIL("%s: section %d: bad sparse table header", n->id, s);
    if (!(sp = calloc(1, sizeof *sp))) NX_FAIL("out of memory");
    sp->phys_base = pbase;
    if (cnt) {
        if (!n->key_ok && S->crypt_type != 1) NX_FAIL("%s: %s", n->id, n->key_why);
        if (tsize < 0x8000 || tsize > (256u << 20) || pbase + toff + tsize > n->size) NX_FAIL("%s: section %d: sparse table out of bounds", n->id, s);
        if (!(tb = malloc((size_t)tsize))) NX_FAIL("out of memory");
        uint8_t tmpl[16]; memcpy(tmpl, S->ctr, 16); uint32_t g = (uint32_t)gen << 16; for (int j = 0; j < 4; j++) { tmpl[7 - j] = (uint8_t)g; g >>= 8; }
        if (S->crypt_type == 1 ? nx_pread_all(n->fd, tb, (size_t)tsize, n->base_off + pbase + toff) : ctr_read(n, tmpl, pbase + toff, tb, (size_t)tsize)) goto fail;
        uint32_t nb = u32(tb + 4); uint64_t total = u64(tb + 8);
        if (nb == 0 || 0x4000 * ((uint64_t)nb + 1) > tsize) NX_FAIL("%s: section %d: bad sparse table (%u entry sets; wrong key or counter?)", n->id, s, nb);
        for (uint32_t i = 0; i < nb; i++) {
            const uint8_t *bk = tb + 0x4000 * (i + 1); uint32_t ne = u32(bk + 4); uint64_t vend = u64(bk + 8);
            if (ne > 0x3FF0 / 0x14) NX_FAIL("%s: section %d: bad sparse entry set %u", n->id, s, i);
            NxReloc *nr = realloc(sp->r, sizeof *nr * (sp->nr + ne)); if (!nr) NX_FAIL("out of memory"); sp->r = nr;
            for (uint32_t j = 0; j < ne; j++) { const uint8_t *e = bk + 0x10 + 0x14 * j; NxReloc *x = &sp->r[sp->nr++]; x->virt = u64(e); x->phys = u64(e + 8); x->patch = u32(e + 16); x->end = j + 1 < ne ? u64(e + 0x14) : vend; }
        }
        if (sp->nr != cnt) NX_FAIL("%s: section %d: the sparse table has %u entries, its header %u", n->id, s, sp->nr, cnt);
        qsort(sp->r, sp->nr, sizeof *sp->r, cmp_reloc);
        for (uint32_t i = 0; i + 1 < sp->nr; i++) if (sp->r[i].end > sp->r[i + 1].virt) sp->r[i].end = sp->r[i + 1].virt;
        sp->r[sp->nr - 1].end = total;
        for (uint32_t i = 0; i < sp->nr; i++) if (sp->r[i].patch > 1) NX_FAIL("%s: section %d: sparse entry %u has storage index %u", n->id, s, i, sp->r[i].patch);
        if (getenv("NX_DEBUG")) fprintf(stderr, "nximport: %s section %d: sparse, %u entries, table @%#llx+%#llx, data from %#llx, generation %u, virtual end %#llx\n", n->id, s, sp->nr,
                                        (unsigned long long)(pbase + toff), (unsigned long long)tsize, (unsigned long long)pbase, gen, (unsigned long long)total);
        free(tb);
    }
    S->sparse = sp;
    return 0;
fail:
    free(tb); if (sp) { free(sp->r); free(sp); } return -1;
}

static int sec_read_plain(NxNca *n, int s, uint64_t off, uint8_t *buf, size_t len) {   // non-BKTR section bytes
    NxSection *S = &n->sec[s];
    if (off + len > S->size) { nx_err("%s: read past section %d (%llu + %zu > %llu)", n->id, s, (unsigned long long)off, len, (unsigned long long)S->size); return -1; }
    if (S->sparse) return sparse_read(n, s, off, buf, len);   // PEANUTS
    if (S->crypt_type == 1) return nx_pread_all(n->fd, buf, len, n->base_off + S->off + off);
    if (S->crypt_type == 3 || S->crypt_type == 4) { if (!n->key_ok) { nx_err("%s: %s", n->id, n->key_why); return -1; } return ctr_read(n, S->ctr, S->off + off, buf, len); }
    nx_err("%s: section %d uses encryption type %d (not supported)", n->id, s, S->crypt_type); return -1;
}

static int cmp_reloc(const void *a, const void *b) { const NxReloc *x = a, *y = b; return x->virt < y->virt ? -1 : x->virt > y->virt; }
static int cmp_sub(const void *a, const void *b) { const NxSubsec *x = a, *y = b; return x->off < y->off ? -1 : x->off > y->off; }

int nx_nca_set_base(NxNca *p, int s, NxNca *base, int base_sec) {
    NxSection *S = &p->sec[s]; uint8_t *rb = NULL, *sb = NULL; NxBktr *b = NULL;
    if (S->crypt_type != 4) { nx_err("%s: section %d is not a patch (BKTR) section", p->id, s); return -1; }
    const uint8_t *fh = p->hdr + 0x400 + 0x200 * s;
    uint64_t roff = u64(fh + 0x100), rsize = u64(fh + 0x108), soff = u64(fh + 0x120), ssize = u64(fh + 0x128);
    if (memcmp(fh + 0x110, "BKTR", 4) || memcmp(fh + 0x130, "BKTR", 4)) NX_FAIL("%s: section %d has no BKTR headers", p->id, s);
    if (rsize < 0x4000 || rsize > (256u << 20) || ssize < 0x4000 || ssize > (256u << 20) || roff + rsize != soff || soff + ssize > S->size) NX_FAIL("%s: BKTR tables out of bounds", p->id);
    if (!(rb = malloc((size_t)rsize)) || !(sb = malloc((size_t)ssize)) || !(b = calloc(1, sizeof *b))) NX_FAIL("out of memory");
    if (sec_read_plain(p, s, roff, rb, (size_t)rsize) || sec_read_plain(p, s, soff, sb, (size_t)ssize)) goto fail;
    if (getenv("NX_DEBUG")) { fprintf(stderr, "nximport: %s BKTR reloc @%#llx+%#llx:", p->id, (unsigned long long)roff, (unsigned long long)rsize); for (int i = 0; i < 0x20; i++) fprintf(stderr, " %02x", rb[i]); fprintf(stderr, "\n  subsec @%#llx+%#llx:", (unsigned long long)soff, (unsigned long long)ssize); for (int i = 0; i < 0x20; i++) fprintf(stderr, " %02x", sb[i]); fprintf(stderr, "\n"); }
    // relocation block: header 0x4000 (num_buckets @4, total_size @8, bucket virtual offsets @0x10), buckets 0x4000 each (num_entries @4, virtual_offset_end @8, entries @0x10, 0x14 each)
    uint32_t nb = u32(rb + 4); b->total = u64(rb + 8);
    if (nb == 0 || 0x4000 * ((uint64_t)nb + 1) > rsize) NX_FAIL("%s: bad relocation block", p->id);
    for (uint32_t i = 0; i < nb; i++) {
        const uint8_t *bk = rb + 0x4000 * (i + 1); uint32_t ne = u32(bk + 4); uint64_t vend = u64(bk + 8);
        if (ne > 0x3FF0 / 0x14) NX_FAIL("%s: bad relocation bucket", p->id);
        NxReloc *nr = realloc(b->r, sizeof *nr * (b->nr + ne)); if (!nr) NX_FAIL("out of memory"); b->r = nr;
        for (uint32_t j = 0; j < ne; j++) { const uint8_t *e = bk + 0x10 + 0x14 * j; NxReloc *x = &b->r[b->nr++]; x->virt = u64(e); x->phys = u64(e + 8); x->patch = u32(e + 16); x->end = j + 1 < ne ? u64(e + 0x14) : vend; }
    }
    qsort(b->r, b->nr, sizeof *b->r, cmp_reloc);
    for (uint32_t i = 0; i + 1 < b->nr; i++) if (b->r[i].end > b->r[i + 1].virt) b->r[i].end = b->r[i + 1].virt;
    if (b->nr) b->r[b->nr - 1].end = b->total;
    // subsection block: same shape, entries 0x10 each (offset @0, ctr_val @0xC); the region after the data (the tables) uses the section's own counter
    nb = u32(sb + 4);
    if (nb == 0 || 0x4000 * ((uint64_t)nb + 1) > ssize) NX_FAIL("%s: bad subsection block", p->id);
    uint64_t data_end = u64(sb + 8);   // the patch data's end: the relocation table's offset (newer NCAs) or the subsection's (hactool's check)
    if (data_end != roff && data_end != soff) NX_FAIL("%s: the subsection block's size does not match the tables", p->id);
    for (uint32_t i = 0; i < nb; i++) {
        const uint8_t *bk = sb + 0x4000 * (i + 1); uint32_t ne = u32(bk + 4);
        if (ne > 0x3FF) NX_FAIL("%s: bad subsection bucket", p->id);
        NxSubsec *ns = realloc(b->s, sizeof *ns * (b->ns + ne + 1)); if (!ns) NX_FAIL("out of memory"); b->s = ns;
        for (uint32_t j = 0; j < ne; j++) { const uint8_t *e = bk + 0x10 + 0x10 * j; NxSubsec *x = &b->s[b->ns++]; x->off = u64(e); x->ctr_val = u32(e + 0xC); x->end = 0; }
    }
    { NxSubsec *x = &b->s[b->ns++]; x->off = data_end; x->ctr_val = u32(fh + 0x140); x->end = S->size; }   // the tables: section_ctr_low (hactool's sentinel)
    qsort(b->s, b->ns, sizeof *b->s, cmp_sub);
    for (uint32_t i = 0; i + 1 < b->ns; i++) b->s[i].end = b->s[i + 1].off;
    b->tables_off = roff;
    free(rb); free(sb);
    free(S->bktr); S->bktr = b; S->base = base; S->base_sec = base_sec;
    return 0;
fail:
    free(rb); free(sb); if (b) { free(b->r); free(b->s); free(b); } return -1;
}

static const NxReloc *find_reloc(const NxBktr *b, uint64_t v) {
    uint32_t lo = 0, hi = b->nr;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (b->r[m].virt <= v) lo = m + 1; else hi = m; }
    return lo ? &b->r[lo - 1] : NULL;
}
static const NxSubsec *find_sub(const NxBktr *b, uint64_t p) {
    uint32_t lo = 0, hi = b->ns;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (b->s[m].off <= p) lo = m + 1; else hi = m; }
    return lo ? &b->s[lo - 1] : NULL;
}

static int bktr_read(NxNca *n, int s, uint64_t off, uint8_t *buf, size_t len) {
    NxSection *S = &n->sec[s]; const NxBktr *b = S->bktr;
    if (!b) { nx_err("%s: patch section %d has no base (the base game's NSP/XCI is needed)", n->id, s); return -1; }
    while (len) {
        const NxReloc *r = find_reloc(b, off);
        if (!r || off >= r->end) { nx_err("%s: offset %llu is outside the patch's relocation table", n->id, (unsigned long long)off); return -1; }
        size_t k = len; if (off + k > r->end) k = (size_t)(r->end - off);
        uint64_t phys = r->phys + (off - r->virt);
        if (!r->patch) { if (!S->base) { nx_err("%s: patch section %d has no base", n->id, s); return -1; } if (nx_sec_read_raw(S->base, S->base_sec, phys, buf, k)) return -1; }   // the base's raw bytes: its compression (if any) is below the patch's
        else {
            if (!n->key_ok) { nx_err("%s: %s", n->id, n->key_why); return -1; }
            size_t done = 0;
            while (done < k) {
                const NxSubsec *ss = find_sub(b, phys + done);
                if (!ss) { nx_err("%s: offset outside the subsection table", n->id); return -1; }
                size_t m = k - done; if (phys + done + m > ss->end) m = (size_t)(ss->end - (phys + done));
                uint8_t tmpl[16]; memcpy(tmpl, S->ctr, 16); uint32_t cv = ss->ctr_val; for (int j = 0; j < 4; j++) { tmpl[7 - j] = (uint8_t)cv; cv >>= 8; }
                if (ctr_read(n, tmpl, S->off + phys + done, buf + done, m)) return -1;
                done += m;
            }
        }
        buf += k; off += k; len -= k;
    }
    return 0;
}

int nx_sec_read_raw(NxNca *n, int s, uint64_t off, void *buf, size_t len) {
    if (s < 0 || s > 3 || !n->sec[s].present) { nx_err("%s: no section %d", n->id, s); return -1; }
    if (n->sec[s].crypt_type == 4) return bktr_read(n, s, off, buf, len);
    return sec_read_plain(n, s, off, buf, len);
}

// ---------------------------------------------------------------- compression
// NcaFsHeader.CompressionInfo @0x178: table offset (8), table size (8), BucketTree header (0x10: "BKTR", version 1, entry count). The layer sits above the
// hash layer (Atmosphère's CreateStorageByRawStorage: hash, then compression), so the table offset and the entries' physical offsets count from the data
// level (S->data_off: the IVFC data level or the PFS0), and the decompressed bytes are the RomFS/PFS0 itself. The table is a bucket tree of 16 KiB nodes:
// the root node (index, count = entry sets, offset = the virtual end), then the entry sets (index, count, offset = the set's virtual end, entries 0x18 each:
// virtual offset, physical offset, type (0 none, 1 zeros, 3 LZ4), physical size).
static int cmp_comp(const void *a, const void *b) { const NxCompEntry *x = a, *y = b; return x->virt < y->virt ? -1 : x->virt > y->virt; }

static int comp_load(NxNca *n, int s) {
    NxSection *S = &n->sec[s]; const uint8_t *ci = n->hdr + 0x400 + 0x200 * s + 0x178; uint8_t *tb = NULL; NxComp *c = NULL;
    uint64_t toff = u64(ci), tsize = u64(ci + 8); uint32_t cnt = u32(ci + 0x18);
    if (memcmp(ci + 0x10, "BKTR", 4) || u32(ci + 0x14) != 1) NX_FAIL("%s: section %d: bad compression table header", n->id, s);
    const uint32_t per = (0x4000 - 0x10) / 0x18, sets = cnt ? (cnt + per - 1) / per : 0;
    if (!cnt || sets > (0x4000 - 0x10) / 8) NX_FAIL("%s: section %d: %u compression entries (not supported)", n->id, s, cnt);   // more than 1.39 M entries would need L2 nodes
    uint64_t need = (uint64_t)0x4000 * (1 + sets);
    if (tsize < need || tsize > (256u << 20) || toff > UINT64_MAX - tsize) NX_FAIL("%s: section %d: compression table out of bounds", n->id, s);
    if (!(tb = malloc((size_t)need)) || !(c = calloc(1, sizeof *c))) NX_FAIL("out of memory");
    if (nx_sec_read_raw(n, s, S->data_off + toff, tb, (size_t)need)) goto fail;
    if (u32(tb + 4) != sets) NX_FAIL("%s: section %d: compression table root has %u entry sets, expected %u", n->id, s, u32(tb + 4), sets);
    c->total = u64(tb + 8); c->table_off = toff; c->cache.comp = c; c->cache.cached = UINT32_MAX;
    if (!(c->e = malloc(sizeof *c->e * cnt))) NX_FAIL("out of memory");
    for (uint32_t i = 0; i < sets; i++) {
        const uint8_t *bk = tb + 0x4000 * (i + 1); uint32_t ne = u32(bk + 4); uint64_t vend = u64(bk + 8);
        if (ne != (i + 1 < sets ? per : cnt - per * i)) NX_FAIL("%s: section %d: bad compression entry set %u", n->id, s, i);
        for (uint32_t j = 0; j < ne; j++) {
            const uint8_t *e = bk + 0x10 + 0x18 * j; NxCompEntry *x = &c->e[c->n++];
            x->virt = u64(e); x->phys = u64(e + 8); x->type = e[0x10]; x->psize = u32(e + 0x14); x->end = j + 1 < ne ? u64(e + 0x18) : vend;
        }
    }
    qsort(c->e, c->n, sizeof *c->e, cmp_comp);
    for (uint32_t i = 0; i < c->n; i++) {
        NxCompEntry *x = &c->e[i];
        x->end = i + 1 < c->n ? c->e[i + 1].virt : c->total;
        uint64_t vs = x->end - x->virt;
        // an uncompressed ("none") entry is read in place, any size, its physical size unused (Atmosphère ignores it: the Echoes of Wisdom base has one of
        // 766 MiB with physical size -1); a zeros entry has no bytes; an LZ4 block is decompressed whole, so it is bounded
        int bad = x->end <= x->virt || (i == 0 && x->virt);
        if (x->type == 0) bad |= x->phys > toff || vs > toff - x->phys;
        else if (x->type == 3) bad |= vs > (64u << 20) || x->phys > toff || x->psize > toff - x->phys;
        else if (x->type != 1) bad = 1;
        if (bad) NX_FAIL("%s: section %d: bad compression entry %u (virtual %#llx..%#llx, physical %#llx+%#x, type %d)", n->id, s, i, (unsigned long long)x->virt, (unsigned long long)x->end, (unsigned long long)x->phys, x->psize, x->type);
    }
    if (getenv("NX_DEBUG")) fprintf(stderr, "nximport: %s section %d: %u compression entries, table @%#llx+%#llx, %llu bytes decompressed\n", n->id, s, c->n, (unsigned long long)toff, (unsigned long long)tsize, (unsigned long long)c->total);
    free(tb); S->comp = c; return 0;
fail:
    free(tb); if (c) { free(c->e); free(c); } return -1;
}

void nx_comp_cache_free(NxCompCache *c) { free(c->out); free(c->in); memset(c, 0, sizeof *c); }
static void comp_free(NxComp *c) { if (!c) return; free(c->e); nx_comp_cache_free(&c->cache); free(c); }

static const NxCompEntry *find_comp(const NxComp *c, uint64_t v) {
    uint32_t lo = 0, hi = c->n;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (c->e[m].virt <= v) lo = m + 1; else hi = m; }
    return lo ? &c->e[lo - 1] : NULL;
}

static int comp_read(NxNca *n, int s, uint64_t voff, uint8_t *buf, size_t len, NxCompCache *cc) {   // voff: from the data level's start
    NxSection *S = &n->sec[s]; NxComp *c = S->comp;
    if (!cc) cc = &c->cache;
    if (cc->comp != c) { cc->comp = c; cc->cached = UINT32_MAX; }   // a thread's scratch moving to another section
    if (voff + len > c->total) { nx_err("%s: read past the decompressed section %d (%llu + %zu > %llu)", n->id, s, (unsigned long long)voff, len, (unsigned long long)c->total); return -1; }
    while (len) {
        const NxCompEntry *e = find_comp(c, voff); if (!e || voff >= e->end) { nx_err("%s: offset %llu is outside the compression table", n->id, (unsigned long long)voff); return -1; }
        size_t k = len, vs = (size_t)(e->end - e->virt); if (voff + k > e->end) k = (size_t)(e->end - voff);
        if (e->type == 1) memset(buf, 0, k);
        else if (e->type == 0) { if (nx_sec_read_raw(n, s, S->data_off + e->phys + (voff - e->virt), buf, k)) return -1; }   // stored: in place
        else {   // LZ4: the whole block; straight into buf when the read covers it all, else kept for the next read
            uint32_t idx = (uint32_t)(e - c->e); int whole = voff == e->virt && k == vs;
            if (whole || cc->cached != idx) {
                if (cc->incap < e->psize) { uint8_t *i = realloc(cc->in, e->psize); if (!i) { nx_err("out of memory"); return -1; } cc->in = i; cc->incap = e->psize; }
                if (nx_sec_read_raw(n, s, S->data_off + e->phys, cc->in, e->psize)) return -1;
                uint8_t *dst = buf;
                if (!whole) {
                    cc->cached = UINT32_MAX;
                    if (cc->outcap < vs) { uint8_t *o = realloc(cc->out, vs); if (!o) { nx_err("out of memory"); return -1; } cc->out = o; cc->outcap = vs; }
                    dst = cc->out;
                }
                long got = nx_lz4_decompress(cc->in, e->psize, dst, vs);
                if (got != (long)vs) { nx_err("%s: section %d: LZ4 block at %llu does not decompress to %zu bytes", n->id, s, (unsigned long long)e->phys, vs); return -1; }
                if (!whole) cc->cached = idx;
            }
            if (!whole) memcpy(buf, cc->out + (voff - e->virt), k);
        }
        buf += k; voff += k; len -= k;
    }
    return 0;
}

int nx_sec_prepare(NxNca *n, int s) {
    if (s < 0 || s > 3 || !n->sec[s].present) { nx_err("%s: no section %d", n->id, s); return -1; }
    NxSection *S = &n->sec[s];
    return S->compressed && !S->comp ? comp_load(n, s) : 0;
}

int nx_sec_read_tc(NxNca *n, int s, uint64_t off, void *buf, size_t len, NxCompCache *cc) {
    if (s < 0 || s > 3 || !n->sec[s].present) { nx_err("%s: no section %d", n->id, s); return -1; }
    NxSection *S = &n->sec[s];
    if (!S->compressed) return nx_sec_read_raw(n, s, off, buf, len);
    if (!S->comp && comp_load(n, s)) return -1;
    if (off < S->data_off) {   // the hash layers: raw
        size_t k = len; if (off + k > S->data_off) k = (size_t)(S->data_off - off);
        if (nx_sec_read_raw(n, s, off, buf, k)) return -1;
        buf = (uint8_t *)buf + k; off += k; len -= k;
    }
    return len ? comp_read(n, s, off - S->data_off, buf, len, cc) : 0;
}
int nx_sec_read(NxNca *n, int s, uint64_t off, void *buf, size_t len) { return nx_sec_read_tc(n, s, off, buf, len, NULL); }

uint64_t nx_sec_chunk_end(NxNca *n, int s, uint64_t off, uint64_t want_end, uint64_t limit) {
    if (s < 0 || s > 3 || !n->sec[s].present) return want_end;
    const NxSection *S = &n->sec[s]; const NxComp *c = S->comp;
    if (!S->compressed || !c || want_end >= limit || want_end <= S->data_off) return want_end;
    uint64_t v = want_end - S->data_off; if (v >= c->total) return want_end;
    const NxCompEntry *e = find_comp(c, v); if (!e || e->type != 3 || v == e->virt) return want_end;   // not inside an LZ4 entry: the boundary is free
    uint64_t end = S->data_off + e->end; (void)off;
    return end < limit ? end : limit;   // the entry ends past limit: the chunk takes the rest anyway
}
int nx_bktr_maps_to_base(const NxNca *n, int s, uint64_t off, uint64_t size, uint64_t *base_off) {
    const NxSection *S = &n->sec[s]; if (S->crypt_type != 4 || !S->bktr || !size) return 0;
    if (S->compressed || S->base->sec[S->base_sec].compressed) return 0;   // the relocations map compressed bytes, not the files' (which the reader decompresses)
    const NxReloc *r = find_reloc(S->bktr, off); if (!r || r->patch) return 0;
    uint64_t p0 = r->phys + (off - r->virt), end = off + size, cur = off;
    while (cur < end) {
        if (!r || r->patch || cur < r->virt || cur >= r->end) return 0;
        if (r->phys + (cur - r->virt) != p0 + (cur - off)) return 0;   // not contiguous in the base
        cur = r->end; if (cur < end) { r = find_reloc(S->bktr, cur); }
    }
    *base_off = p0; return 1;
}

static int find_titlekey(const NxContainer *c, const uint8_t rights[16], uint8_t key[16]) {
    for (int i = 0; i < c->ntik; i++) if (!memcmp(c->tik[i].rights, rights, 16)) { memcpy(key, c->tik[i].key, 16); return 1; }
    for (int i = 0; i < nx_keys.ntk; i++) if (!memcmp(nx_keys.tk[i].rights, rights, 16)) { memcpy(key, nx_keys.tk[i].key, 16); return 1; }
    return 0;
}

int nx_nca_open(NxNca *n, const NxContainer *c, const NxEntry *e) {
    memset(n, 0, sizeof *n); n->fd = c->fd; n->base_off = e->off; n->size = e->size;
    snprintf(n->id, sizeof n->id, "%.40s", e->name);
    if (!nx_keys.loaded) { nx_err("keys not loaded (nximport_keys)"); return -1; }
    if (e->size < 0xC00) { nx_err("%s: too small for an NCA", n->id); return -1; }
    if (nx_pread_all(n->fd, n->hdr, 0xC00, n->base_off)) return -1;
    NxAes a1, a2; nx_aes_init(&a1, nx_keys.header_key); nx_aes_init(&a2, nx_keys.header_key + 16);
    nx_aes_xts_decrypt(&a1, &a2, 0, 0x200, n->hdr, n->hdr, 0x400);
    if (!memcmp(n->hdr + 0x200, "NCA3", 4)) nx_aes_xts_decrypt(&a1, &a2, 2, 0x200, n->hdr + 0x400, n->hdr + 0x400, 0x800);
    else if (!memcmp(n->hdr + 0x200, "NCA2", 4)) { for (int i = 0; i < 4; i++) nx_aes_xts_decrypt(&a1, &a2, 0, 0x200, n->hdr + 0x400 + 0x200 * i, n->hdr + 0x400 + 0x200 * i, 0x200); }
    else { nx_err("%s: not an NCA (wrong header key?)", n->id); return -1; }
    n->content_type = n->hdr[0x205]; n->title_id = u64(n->hdr + 0x210);
    int kg = n->hdr[0x206]; if (n->hdr[0x220] > kg) kg = n->hdr[0x220]; if (kg) kg--; n->key_gen = kg;
    int kaek = n->hdr[0x207];
    static const uint8_t zero16[16];
    n->has_rights = memcmp(n->hdr + 0x230, zero16, 16) != 0;
    if (kg >= 32 || kaek > 2) { snprintf(n->key_why, sizeof n->key_why, "key generation %d / key area index %d out of range", kg, kaek); }
    else if (n->has_rights) {
        uint8_t tk[16];
        if (!find_titlekey(c, n->hdr + 0x230, tk)) snprintf(n->key_why, sizeof n->key_why, "no ticket for its rights id (not in the file, nor in title.keys)");
        else if (!nx_keys.has_titlekek[kg]) snprintf(n->key_why, sizeof n->key_why, "prod.keys has no titlekek_%02x", kg);
        else { NxAes k; nx_aes_init(&k, nx_keys.titlekek[kg]); nx_aes_decrypt_block(&k, tk, tk); nx_aes_init(&n->key, tk); n->key_ok = 1; }
    } else {
        if (!nx_keys.has_kaek[kaek][kg]) snprintf(n->key_why, sizeof n->key_why, "prod.keys has no key_area_key_%s_%02x", kaek == 0 ? "application" : kaek == 1 ? "ocean" : "system", kg);
        else { NxAes k; uint8_t area[0x40]; nx_aes_init(&k, nx_keys.kaek[kaek][kg]); nx_aes_ecb_decrypt(&k, n->hdr + 0x300, area, 0x40); nx_aes_init(&n->key, area + 0x20); n->key_ok = 1; }   // key 2: the CTR key
    }
    for (int i = 0; i < 4; i++) {
        const uint8_t *te = n->hdr + 0x240 + 0x10 * i, *fh = n->hdr + 0x400 + 0x200 * i; NxSection *S = &n->sec[i];
        uint64_t ms = u32(te), me = u32(te + 4); if (!me) continue;
        S->present = 1; S->off = ms * 0x200; S->size = (me - ms) * 0x200;
        S->fs_type = fh[2]; S->hash_type = fh[3]; S->crypt_type = fh[4];
        for (int j = 0; j < 8; j++) S->ctr[j] = fh[0x140 + 7 - j];
        static const uint8_t zero48[0x58];
        if (memcmp(fh + 0x148, zero48, 0x30) && sparse_load(n, i, fh)) { nx_nca_close(n); return -1; }   // PEANUTS: a sparse section (sparse_load; was "not supported")
        if (memcmp(fh + 0x178, zero48, 0x28)) {   // CompressionInfo: the table is read on the first read of the section (comp_load)
            if (memcmp(fh + 0x188, "BKTR", 4) || u32(fh + 0x18C) != 1) { nx_err("%s: section %d: bad compression table header", n->id, i); nx_nca_close(n); return -1; }
            S->compressed = 1;
        }
        if (S->fs_type == NX_FS_PFS0) S->data_off = u64(fh + 0x40);               // PFS0 superblock (at +8): master hash 0x20, block size, 2, hash table offset/size, pfs0_offset @+0x38
        else if (S->fs_type == NX_FS_ROMFS) S->data_off = u64(fh + 0x8 + 0x10 + 0x18 * 5);   // IVFC level 6 (the data) logical offset
    }
    return 0;
}

void nx_nca_close(NxNca *n) { for (int i = 0; i < 4; i++) { if (n->sec[i].bktr) { free(n->sec[i].bktr->r); free(n->sec[i].bktr->s); free(n->sec[i].bktr); n->sec[i].bktr = NULL; } comp_free(n->sec[i].comp); n->sec[i].comp = NULL; if (n->sec[i].sparse) { free(n->sec[i].sparse->r); free(n->sec[i].sparse); n->sec[i].sparse = NULL; } } }

// PEANUTS: a sparse section with no entries holds no data (Castlevania: Belmont's Curse's base: its ExeFS is in the update only) and counts as absent
int nx_nca_find_section(const NxNca *n, int fs_type) { for (int i = 0; i < 4; i++) if (n->sec[i].present && n->sec[i].fs_type == fs_type && !(n->sec[i].sparse && !n->sec[i].sparse->nr)) return i; return -1; }

// ---------------------------------------------------------------- trees
NxNode *nx_node_new(const char *name, size_t nlen, int is_dir) {
    NxNode *n = calloc(1, sizeof *n); if (!n) return NULL;
    n->name = malloc(nlen + 1); if (!n->name) { free(n); return NULL; }
    memcpy(n->name, name, nlen); n->name[nlen] = 0; n->is_dir = is_dir; return n;
}
void nx_node_free(NxNode *n) { if (!n) return; for (int i = 0; i < n->nkids; i++) nx_node_free(n->kids[i]); free(n->kids); free(n->name); free(n); }

static int name_cmp(const char *a, const char *b) {   // memcmp order, shorter-prefix first
    size_t la = strlen(a), lb = strlen(b); int c = memcmp(a, b, la < lb ? la : lb); return c ? c : (la < lb ? -1 : la > lb);
}
NxNode *nx_node_add(NxNode *d, NxNode *k) {
    int lo = 0, hi = d->nkids;
    while (lo < hi) { int m = (lo + hi) / 2, c = name_cmp(d->kids[m]->name, k->name); if (!c) { nx_err("repeated name %s", k->name); return NULL; } if (c < 0) lo = m + 1; else hi = m; }
    if (d->nkids == d->cap) { int nc = d->cap ? d->cap * 2 : 8; NxNode **nk = realloc(d->kids, sizeof *nk * (size_t)nc); if (!nk) { nx_err("out of memory"); return NULL; } d->kids = nk; d->cap = nc; }
    memmove(d->kids + lo + 1, d->kids + lo, sizeof *d->kids * (size_t)(d->nkids - lo)); d->kids[lo] = k; d->nkids++; return k;
}
NxNode *nx_node_find(NxNode *root, const char *path) {
    NxNode *n = root;
    while (*path && n) {
        if (*path == '/') { path++; continue; }
        const char *e = strchr(path, '/'); size_t l = e ? (size_t)(e - path) : strlen(path);
        NxNode *f = NULL;
        for (int i = 0; i < n->nkids; i++) if (strlen(n->kids[i]->name) == l && !memcmp(n->kids[i]->name, path, l)) { f = n->kids[i]; break; }
        n = f; path += l;
    }
    return n;
}
int nx_node_read_tc(const NxNode *f, uint64_t off, void *buf, size_t len, NxCompCache *cc) {
    if (f->is_dir) { nx_err("%s is a directory", f->name); return -1; }
    if (off + len > f->size) { nx_err("%s: read past the end", f->name); return -1; }
    return nx_sec_read_tc(f->nca, f->sec, f->off + off, buf, len, cc);
}
int nx_node_read(const NxNode *f, uint64_t off, void *buf, size_t len) { return nx_node_read_tc(f, off, buf, len, NULL); }
int nx_node_read_whole(const NxNode *f, uint8_t **out, size_t max) {
    if (f->size > max) { nx_err("%s is too large (%llu bytes)", f->name, (unsigned long long)f->size); return -1; }
    uint8_t *b = malloc((size_t)f->size + 1); if (!b) { nx_err("out of memory"); return -1; }
    if (f->size && nx_node_read(f, 0, b, (size_t)f->size)) { free(b); return -1; }
    b[f->size] = 0; *out = b; return 0;
}

NxNode *nx_pfs0_tree(NxNca *n, int s) {
    NxSection *S = &n->sec[s]; uint8_t h[0x10]; uint8_t *tab = NULL; NxNode *root = NULL;
    if (!S->present || S->fs_type != NX_FS_PFS0) NX_FAIL("%s: section %d is not a PFS0", n->id, s);
    if (nx_sec_read(n, s, S->data_off, h, 0x10)) goto fail;
    if (memcmp(h, "PFS0", 4)) NX_FAIL("%s: section %d has no PFS0 header (wrong key?)", n->id, s);
    uint32_t cnt = u32(h + 4), ss = u32(h + 8);
    if (cnt > 10000 || ss > (16u << 20)) NX_FAIL("%s: bad PFS0 header", n->id);
    if (!(tab = malloc(0x18 * (size_t)cnt + ss + 1))) NX_FAIL("out of memory");
    if (nx_sec_read(n, s, S->data_off + 0x10, tab, 0x18 * (size_t)cnt + ss)) goto fail;
    tab[0x18 * cnt + ss] = 0; const char *st = (const char *)tab + 0x18 * cnt; uint64_t data = S->data_off + 0x10 + 0x18 * (uint64_t)cnt + ss;
    if (!(root = nx_node_new("", 0, 1))) NX_FAIL("out of memory");
    for (uint32_t i = 0; i < cnt; i++) {
        const uint8_t *e = tab + 0x18 * i; uint32_t no = u32(e + 0x10); if (no >= ss) NX_FAIL("%s: PFS0 name out of range", n->id);
        NxNode *f = nx_node_new(st + no, strnlen(st + no, ss - no), 0); if (!f) NX_FAIL("out of memory");
        f->nca = n; f->sec = s; f->off = data + u64(e); f->size = u64(e + 8);
        if (!nx_node_add(root, f)) { nx_node_free(f); goto fail; }
    }
    free(tab); return root;
fail:
    free(tab); nx_node_free(root); return NULL;
}

typedef struct { NxNca *n; int s; uint8_t *dm, *fm; uint64_t dms, fms, data; } RomCtx;
static int romfs_walk(RomCtx *c, NxNode *dir, uint32_t d, int depth) {
    if (depth > 64) { nx_err("RomFS too deep"); return -1; }
    if ((uint64_t)d + 0x18 > c->dms) { nx_err("RomFS directory entry out of range"); return -1; }
    const uint8_t *e = c->dm + d; uint32_t child = u32(e + 8), file = u32(e + 12);
    while (file != 0xFFFFFFFFu) {
        if ((uint64_t)file + 0x20 > c->fms) { nx_err("RomFS file entry out of range"); return -1; }
        const uint8_t *f = c->fm + file; uint32_t nl = u32(f + 0x1C); if ((uint64_t)file + 0x20 + nl > c->fms) { nx_err("RomFS file name out of range"); return -1; }
        NxNode *k = nx_node_new((const char *)f + 0x20, nl, 0); if (!k) { nx_err("out of memory"); return -1; }
        k->nca = c->n; k->sec = c->s; k->off = c->data + u64(f + 8); k->size = u64(f + 16);
        if (!nx_node_add(dir, k)) { nx_node_free(k); return -1; }
        file = u32(f + 4);
    }
    while (child != 0xFFFFFFFFu) {
        if ((uint64_t)child + 0x18 > c->dms) { nx_err("RomFS directory entry out of range"); return -1; }
        const uint8_t *s = c->dm + child; uint32_t nl = u32(s + 0x14); if ((uint64_t)child + 0x18 + nl > c->dms) { nx_err("RomFS directory name out of range"); return -1; }
        NxNode *k = nx_node_new((const char *)s + 0x18, nl, 1); if (!k) { nx_err("out of memory"); return -1; }
        if (!nx_node_add(dir, k)) { nx_node_free(k); return -1; }
        if (romfs_walk(c, k, child, depth + 1)) return -1;
        child = u32(s + 4);
    }
    return 0;
}

NxNode *nx_romfs_tree(NxNca *n, int s) {
    NxSection *S = &n->sec[s]; uint8_t h[0x50]; RomCtx c = { n, s, NULL, NULL, 0, 0, 0 }; NxNode *root = NULL;
    if (!S->present || S->fs_type != NX_FS_ROMFS) NX_FAIL("%s: section %d is not a RomFS", n->id, s);
    if (nx_sec_read(n, s, S->data_off, h, 0x50)) goto fail;
    if (u64(h) != 0x50) NX_FAIL("%s: section %d has no RomFS header (wrong key or base?)", n->id, s);
    uint64_t dmo = u64(h + 0x18), fmo = u64(h + 0x38); c.dms = u64(h + 0x20); c.fms = u64(h + 0x40); c.data = S->data_off + u64(h + 0x48);
    if (c.dms > (512u << 20) || c.fms > (512u << 20)) NX_FAIL("%s: RomFS tables too large", n->id);
    if (!(c.dm = malloc((size_t)c.dms + 1)) || !(c.fm = malloc((size_t)c.fms + 1))) NX_FAIL("out of memory");
    if (nx_sec_read(n, s, S->data_off + dmo, c.dm, (size_t)c.dms) || nx_sec_read(n, s, S->data_off + fmo, c.fm, (size_t)c.fms)) goto fail;
    if (!(root = nx_node_new("", 0, 1))) NX_FAIL("out of memory");
    if (romfs_walk(&c, root, 0, 0)) goto fail;
    free(c.dm); free(c.fm); return root;
fail:
    free(c.dm); free(c.fm); nx_node_free(root); return NULL;
}

// ---------------------------------------------------------------- titles
static void hexid(char out[33], const uint8_t *id) { for (int i = 0; i < 16; i++) sprintf(out + 2 * i, "%02x", id[i]); }

static int nacp_strings(NxNca *ctl, char name[256], char publisher[256]) {
    int s = nx_nca_find_section(ctl, NX_FS_ROMFS); if (s < 0) return -1;
    NxNode *root = nx_romfs_tree(ctl, s); if (!root) return -1;
    NxNode *f = nx_node_find(root, "control.nacp"); uint8_t *b = NULL; int rc = -1;
    if (f && !f->is_dir && f->size >= 0x3000 && !nx_node_read_whole(f, &b, 0x4000)) {
        for (int i = 0; i < 16; i++) { const uint8_t *e = b + 0x300 * i; if (e[0]) { snprintf(name, 256, "%.255s", (const char *)e); snprintf(publisher, 256, "%.255s", (const char *)e + 0x200); rc = 0; break; } }   // entry 0 is AmericanEnglish
        if (rc) rc = 0;
    }
    free(b); nx_node_free(root); return rc;
}

int nx_title_control(const NxTitle *t, NxNca *ctl) {
    if (!t->control[0]) return -1;
    char nm[48]; snprintf(nm, sizeof nm, "%s.nca", t->control);
    const NxEntry *e = nx_container_find(t->c, nm); if (!e) { nx_err("control NCA %s not in %s", nm, t->c->path); return -1; }
    return nx_nca_open(ctl, t->c, e);
}

int nx_container_titles(NxContainer *c, NxTitle **out, int *count) {
    NxTitle *ts = NULL; int n = 0;
    for (int i = 0; i < c->n; i++) {
        const NxEntry *e = &c->e[i]; size_t l = strlen(e->name);
        int is_meta = (l > 9 && !strcmp(e->name + l - 9, ".cnmt.nca")) || c->kind == 2;
        if (!is_meta) continue;
        NxNca m; if (nx_nca_open(&m, c, e)) { if (c->kind == 2) continue; goto fail; }
        if (m.content_type != 1) { nx_nca_close(&m); continue; }
        int s = nx_nca_find_section(&m, NX_FS_PFS0); NxNode *root = s >= 0 ? nx_pfs0_tree(&m, s) : NULL; if (s < 0) nx_err("%s: the meta NCA %s has no PFS0 section", c->path, e->name);
        if (!root) { nx_nca_close(&m); goto fail; }
        NxNode *cf = NULL; for (int k = 0; k < root->nkids; k++) { size_t kl = strlen(root->kids[k]->name); if (kl > 5 && !strcmp(root->kids[k]->name + kl - 5, ".cnmt")) { cf = root->kids[k]; break; } }
        uint8_t *b = NULL;
        if (!cf || cf->size < 0x20 || nx_node_read_whole(cf, &b, 1u << 20)) { nx_node_free(root); nx_nca_close(&m); if (!cf) nx_err("%s: no .cnmt in %s", c->path, e->name); goto fail; }
        NxTitle t; memset(&t, 0, sizeof t); t.c = c; t.meta = e;
        t.title_id = u64(b); t.version = u32(b + 8); int type = b[0xC]; uint16_t ext = u16(b + 0xE), cc = u16(b + 0x10);
        t.kind = type == 0x80 ? 0 : type == 0x81 ? 1 : type == 0x82 ? 2 : -1;
        t.app_id = t.kind == 0 ? t.title_id : (ext >= 8 && cf->size >= 0x28 ? u64(b + 0x20) : 0);
        for (uint32_t k = 0; k < cc; k++) {
            size_t ro = 0x20 + ext + 0x38 * k; if (ro + 0x38 > cf->size) break;
            const uint8_t *r = b + ro; int ct = r[0x36];
            if (ct == 1 && !t.program[0]) hexid(t.program, r + 0x20); else if (ct == 3 && !t.control[0]) hexid(t.control, r + 0x20); else if (ct == 2 && !t.data[0]) hexid(t.data, r + 0x20);
        }
        free(b); nx_node_free(root); nx_nca_close(&m);
        if (t.kind < 0) continue;   // system/delta metas are not titles of the library
        { NxNca ctl; if (!nx_title_control(&t, &ctl)) { nacp_strings(&ctl, t.name, t.publisher); nx_nca_close(&ctl); } }
        NxTitle *nt = realloc(ts, sizeof *nt * (size_t)(n + 1)); if (!nt) { nx_err("out of memory"); goto fail; } ts = nt; ts[n++] = t;
    }
    if (!n) { nx_err("%s: no title metadata (.cnmt.nca) found", c->path); goto fail; }
    *out = ts; *count = n; return 0;
fail:
    free(ts); return -1;
}
