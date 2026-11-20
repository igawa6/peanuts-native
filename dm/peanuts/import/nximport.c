// libnximport: the public API (nximport.h) — probe, icon, the .nup writer, the firmware fonts, .nup verify/extract.
// The .nup is written as dm/tools/nup_pack.py and the host's reader (dm/host/nup_image.h) define it: superblock 0x80, TOC (packages 0x40, dirs 0x18,
// files 0x50, children u32, string pool), blob of 16-byte-aligned raw extents (codec 0), equal files sharing one extent, then each package's meta block
// (SPEC §5: main.npdm, the control data, the icon) as the nup packer places them.
#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#include "nximport.h"
#include "nx_internal.h"
#include "nx_zip.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

int nx_jpeg_to_png(const uint8_t *jpg, size_t n, const char *out_png);   // nx_icon.c

static uint32_t u32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t u64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }
static void put16(uint8_t *p, uint16_t v) { memcpy(p, &v, 2); }
static void put32(uint8_t *p, uint32_t v) { memcpy(p, &v, 4); }
static void put64(uint8_t *p, uint64_t v) { memcpy(p, &v, 8); }

static int parse_tid(const char *s, uint64_t *tid) {
    if (!s || strlen(s) != 16) return -1; uint8_t b[8]; if (nx_hex(s, b, 8)) return -1;
    uint64_t v = 0; for (int i = 0; i < 8; i++) v = v << 8 | b[i]; *tid = v; return 0;
}

// ---------------------------------------------------------------- probe
// the body of a content NCA when it is an NCZ of the title's container, counted as solid (unpacked) or block (checked)
static void ncz_bytes(const NxTitle *t, const char *id, unsigned long long *unpack, unsigned long long *check) {
    char nm[48]; snprintf(nm, sizeof nm, "%s.nca", id);
    if (!id[0] || nx_container_find(t->c, nm)) return;
    snprintf(nm, sizeof nm, "%s.ncz", id); const NxEntry *e = nx_container_find(t->c, nm); NxNcz *z;
    if (!e || nx_ncz_open(&z, t->c, e)) return;   // a broken one: the import says why
    if (nx_ncz_solid(z)) *unpack += nx_ncz_body(z); else *check += nx_ncz_body(z);
    nx_ncz_close(z);
}

static void fill_info(NxImportInfo *o, const NxTitle *t) {
    memset(o, 0, sizeof *o);
    o->size = t->program_size + t->data_size;
    ncz_bytes(t, t->program, &o->unpack, &o->check); ncz_bytes(t, t->data, &o->unpack, &o->check);
    snprintf(o->title_id, sizeof o->title_id, "%016llx", (unsigned long long)t->title_id);
    snprintf(o->app_id, sizeof o->app_id, "%016llx", (unsigned long long)t->app_id);
    o->kind = t->kind; o->version = t->version;
    snprintf(o->name, sizeof o->name, "%s", t->name); snprintf(o->publisher, sizeof o->publisher, "%s", t->publisher);
}

int nximport_probe(const char *path, NxImportInfo *out, int max, int *count) {
    NxContainer c; NxTitle *ts = NULL; int n = 0;
    if (count) *count = 0;
    if (!nx_keys.loaded) { nx_err("keys not loaded (nximport_keys)"); return -1; }
    if (nx_container_open(&c, path)) return -1;
    if (nx_container_titles(&c, &ts, &n)) { nx_container_close(&c); return -1; }
    for (int i = 0; i < n && i < max; i++) fill_info(&out[i], &ts[i]);
    if (count) *count = n;
    free(ts); nx_container_close(&c); return 0;
}

// ---------------------------------------------------------------- icon
int nximport_icon(const char *path, const char *title_id, const char *out_png) {
    NxContainer c; NxTitle *ts = NULL; int n = 0, rc = -1; uint64_t want = 0; NxNca ctl; int have_ctl = 0; NxNode *root = NULL; uint8_t *jpg = NULL;
    if (!nx_keys.loaded) { nx_err("keys not loaded (nximport_keys)"); return -1; }
    if (title_id && *title_id && parse_tid(title_id, &want)) { nx_err("bad title id %s", title_id); return -1; }
    if (nx_container_open(&c, path)) return -1;
    if (nx_container_titles(&c, &ts, &n)) goto out;
    for (int i = 0; i < n; i++) {
        if (want && ts[i].title_id != want) continue;
        if (!nx_title_control(&ts[i], &ctl)) { have_ctl = 1; break; }
        if (want) goto out;
    }
    if (!have_ctl) NX_FAIL("%s: no control data (icon) for %s", path, title_id && *title_id ? title_id : "any title");
    { int s = nx_nca_find_section(&ctl, NX_FS_ROMFS); if (s < 0 || !(root = nx_romfs_tree(&ctl, s))) goto out; }
    NxNode *ic = nx_node_find(root, "icon_AmericanEnglish.dat");
    if (!ic) for (int i = 0; i < root->nkids; i++) if (!strncmp(root->kids[i]->name, "icon_", 5) && !root->kids[i]->is_dir) { ic = root->kids[i]; break; }
    if (!ic) NX_FAIL("%s: the control data has no icon", path);
    if (nx_node_read_whole(ic, &jpg, 4u << 20)) goto out;
    rc = nx_jpeg_to_png(jpg, (size_t)ic->size, out_png);
out:
fail:
    free(jpg); nx_node_free(root); if (have_ctl) nx_nca_close(&ctl); free(ts); nx_container_close(&c); return rc;
}

// ---------------------------------------------------------------- .nup writer
typedef struct Pkg {
    int kind; uint64_t tid; uint32_t ver; int flags; NxNode *root; char sel[40];
    const NxTitle *t;                              // the title it was made from (its control data: pkg_meta)
    uint8_t *meta; size_t meta_n, meta_cap; uint64_t meta_off;   // its meta block (pkg_meta) and where write_nup put it
} Pkg;
typedef struct Src { NxContainer c; NxTitle *ts; int n; } Src;
typedef struct Job {
    Pkg pk[64]; int npk;
    NxNca ncas[192]; int nncas;   // every NCA the trees read from (kept open: nodes point at them)
    NxNcz *ncz[192];              // ncz[i]: the NCZ ncas[i] is read from (an NSZ's or XCZ's), else NULL
    char tmp[1200];               // where solid NCZs are unpacked: a hidden folder beside the .nup
    long long prep_total, prep_done, prep_said; char prep_step[300];   // unpacking and checking the NCZs, before the trees: their own progress
    NxNode **dirs; uint32_t nd, dcap; NxNode **files; uint32_t nf, fcap; const char **step; // step[file id]
    uint8_t *pool; size_t pool_n, pool_cap; struct Intern *idx; uint32_t nidx, cidx;
    NxNode **shamap; uint32_t shacap;       // sha → written node
    NxNode **basefiles; uint32_t nbase;     // the base RomFS's files (for the BKTR shortcut)
    NxNca *base_rom; int base_rom_sec;
    long long total, done; void (*progress)(void *, const char *, long long, long long); void *user; const char *cur_step;
} Job;

_Atomic int nx_stop;
void nximport_cancel(int on) { atomic_store(&nx_stop, on ? 1 : 0); }

static const char *leaf_of(const char *p) { const char *s = strrchr(p, '/'), *b = strrchr(p, '\\'); if (b && (!s || b > s)) s = b; return s ? s + 1 : p; }

// A file of the container hashed whole (an NSZ's small plain NCAs: its meta and control) → 0 when its SHA-256 is want (and, with name, starts as its
// name says), else -1 with the reason
static int entry_sha_is(const NxContainer *c, const NxEntry *e, const uint8_t *want, int by_name) {
    uint8_t sha[32], id[16], *b = malloc(NX_CHUNK); NxSha256 h; nx_sha256_init(&h);
    if (!b) { nx_err("out of memory"); return -1; }
    for (uint64_t o = 0; o < e->size; ) { size_t k = e->size - o < NX_CHUNK ? (size_t)(e->size - o) : NX_CHUNK; if (nx_pread_all(c->fd, b, k, e->off + o)) { free(b); return -1; } nx_sha256_update(&h, b, k); o += k; }
    free(b); nx_sha256_final(&h, sha);
    if ((by_name && (nx_hex_prefix(e->name, id) || memcmp(sha, id, 16))) || (want && memcmp(sha, want, 32))) {
        nx_err("%s: %s does not match its checksum: the file is damaged or incomplete. Nothing was changed. Compress the game again from its dump, or build from the uncompressed file.", leaf_of(c->path), e->name);
        return -1;
    }
    return 0;
}

static void prep_tick(void *u, uint64_t n) {
    Job *j = u; j->prep_done += (long long)n;
    if (j->progress && (j->prep_done - j->prep_said >= (long long)NX_CHUNK || j->prep_done == j->prep_total)) { j->prep_said = j->prep_done; j->progress(j->user, j->prep_step, j->prep_done, j->prep_total); }
}

// A content NCA of title t (its id and the SHA-256 its CNMT records): <id>.nca, or <id>.ncz rebuilt from its compressed bytes and checked against both
static NxNca *job_nca(Job *j, const NxTitle *t, const char *id, const uint8_t sha[32]) {
    const NxContainer *c = t->c;
    if (j->nncas >= (int)(sizeof j->ncas / sizeof j->ncas[0])) { nx_err("too many NCAs"); return NULL; }
    char nm[48]; snprintf(nm, sizeof nm, "%s.nca", id);
    const NxEntry *e = nx_container_find(c, nm); NxNcz *z = NULL;
    if (!e) { snprintf(nm, sizeof nm, "%s.ncz", id); e = nx_container_find(c, nm); }
    if (!e) { snprintf(nm, sizeof nm, "%s.nca", id); nx_err("%s is not in %s", nm, c->path); return NULL; }
    NxNca *n = &j->ncas[j->nncas];
    if (strlen(e->name) > 4 && !strcasecmp(e->name + strlen(e->name) - 4, ".ncz")) {
        if (nx_ncz_open(&z, c, e)) return NULL;
        if (nx_nca_open_store(n, c, e, nx_ncz_store(z))) { nx_ncz_close(z); return NULL; }
    } else if (nx_nca_open(n, c, e)) return NULL;
    // PEANUTS fix (AUDIT #1): nx_nca_open can succeed with key_ok 0 (no titlekey/area key: key_why says why); it still opened the NCA's sections, so they must be closed here too
    if (!n->key_ok) { nx_err("%s in %s: %s", nm, c->path, n->key_why); nx_nca_close(n); nx_ncz_close(z); return NULL; }
    if (z) {
        uint8_t got[32];
        // its keys are the ones the ticket or prod.keys give (never a key of its own: a compressed file opens only where its uncompressed one would)
        if (!nx_ncz_keys(z, n->key_raw)) NX_FAIL("%s: the keys inside %s do not match the ones from its ticket and your keys: the file is damaged, or was compressed from another dump", leaf_of(c->path), e->name);
        { uint64_t hs; memcpy(&hs, n->hdr + 0x208, 8); if (hs != n->size) NX_FAIL("%s: %s rebuilds to %llu bytes, its header says %llu: the file is damaged", leaf_of(c->path), e->name, (unsigned long long)n->size, (unsigned long long)hs); }
        // the CNMT is the reference: its meta NCA (plain) against its name, the control NCA against its record, then this one
        if (t->meta && entry_sha_is(c, t->meta, NULL, 1)) goto fail;
        if (t->control[0]) { char cn[48]; snprintf(cn, sizeof cn, "%s.nca", t->control); const NxEntry *ce = nx_container_find(c, cn); if (ce && entry_sha_is(c, ce, t->control_sha, 1)) goto fail; }
        snprintf(j->prep_step, sizeof j->prep_step, "%s/%s", nx_ncz_solid(z) ? "unpack" : "check", leaf_of(c->path));
        if (j->progress) j->progress(j->user, j->prep_step, j->prep_done, j->prep_total);
        if (nx_ncz_prepare(z, j->tmp, got, prep_tick, j)) goto fail;
        uint8_t want[16];
        if (nx_hex_prefix(e->name, want) || memcmp(got, want, 16) || memcmp(got, sha, 32))
            NX_FAIL("%s: %s does not match its checksum: the file is damaged or incomplete. Nothing was changed. Compress the game again from its dump, or build from the uncompressed file.", leaf_of(c->path), e->name);
    }
    j->ncz[j->nncas] = z; j->nncas++; return n;
fail:
    nx_nca_close(n); nx_ncz_close(z); return NULL;
}

// What unpacking and checking the job's NCZs will take (their bodies' bytes), before any is opened for good: the titles the writer takes, as it takes them
static int job_ncz_bytes(const NxTitle *t, const char *id, uint64_t *sum) {
    char nm[48]; snprintf(nm, sizeof nm, "%s.nca", id);
    if (!id[0] || nx_container_find(t->c, nm)) return 0;
    snprintf(nm, sizeof nm, "%s.ncz", id); const NxEntry *e = nx_container_find(t->c, nm); NxNcz *z;
    if (!e) return 0;   // missing: job_nca says so
    if (nx_ncz_open(&z, t->c, e)) return -1;
    *sum += nx_ncz_body(z); nx_ncz_close(z); return 0;
}

static int add_pkg(Job *j, const NxTitle *t, int kind, uint64_t tid, uint32_t ver, NxNode *exefs, NxNode *romfs) {
    if (j->npk >= 64) { nx_err("too many packages"); return -1; }
    Pkg *p = &j->pk[j->npk]; memset(p, 0, sizeof *p); p->t = t; p->kind = kind; p->tid = tid; p->ver = ver;
    if (!(p->root = nx_node_new("", 0, 1))) { nx_err("out of memory"); return -1; }
    if (exefs) { free(exefs->name); exefs->name = strdup("exefs"); if (!nx_node_add(p->root, exefs)) return -1; }
    if (romfs) { free(romfs->name); romfs->name = strdup("romfs"); if (!nx_node_add(p->root, romfs)) return -1; }
    if (kind == 0) strcpy(p->sel, "base"); else if (kind == 1) snprintf(p->sel, sizeof p->sel, "update/%u", ver); else snprintf(p->sel, sizeof p->sel, "addon/%016llx", (unsigned long long)tid);
    j->npk++; return 0;
}

// ---- a package's meta block (SPEC §5): records of a type (u16), a zero (u16), a size (u32) and the bytes, no padding, sorted by type. The spec's own type
// numbers: the nup packer from a55uka/nup 1d904e10 on writes them one lower and so has a type 0 record, which tells the readers (Nup.Meta.shifted, android/NUP.md);
// this writer never writes a type 0. A base or update package gets its main.npdm (1, which the spec asks of every producer), its control.nacp as it is (2) and
// that NACP's display version (4); the application's records go into the base package, else the first one: the icon (3, the JPEG of icon_AmericanEnglish.dat,
// else of the first icon there, as nximport_icon takes it), the title (5) and the publisher (6), English else the first language that has them (as the probe
// reads them). An add-on has no ExeFS and no control data. Control data that is missing or cannot be read only leaves its records out: the game imports and
// plays the same, its card has no icon. Before these blocks a .nup made here had no icon of its own, so its game lost the icon once its NSP was gone
// (issue #158); dev.peanuts.ui.NupIcon reads it back.
enum { TLV_NPDM = 1, TLV_NACP = 2, TLV_ICON = 3, TLV_VERSION = 4, TLV_TITLE = 5, TLV_PUBLISHER = 6 };
#define META_NPDM_MAX (1u << 20)
#define META_ICON_MAX (4u << 20)   // the readers' bounds: Nup.META_MAX (8 MiB for a whole block), LibraryScanner's 4 MiB for an icon file

static int tlv_add(Pkg *p, uint16_t type, const void *data, size_t n) {
    if (!n) return 0;
    size_t need = p->meta_n + 8 + n;
    if (need > p->meta_cap) {
        size_t nc = p->meta_cap ? p->meta_cap : 4096; while (nc < need) nc *= 2;
        uint8_t *nb = realloc(p->meta, nc); if (!nb) { nx_err("out of memory"); return -1; }
        p->meta = nb; p->meta_cap = nc;
    }
    uint8_t *r = p->meta + p->meta_n; put16(r, type); put16(r + 2, 0); put32(r + 4, (uint32_t)n); memcpy(r + 8, data, n);
    p->meta_n = need; return 0;
}

// A title's control data: control.nacp, its display version, the icon. Nothing when the title has no control NCA or it cannot be read
typedef struct Ctl { uint8_t *nacp, *icon; size_t nacp_n, icon_n; char version[17]; } Ctl;
static void ctl_read(const NxTitle *t, Ctl *c) {
    NxNca n; NxNode *root = NULL; int s;
    memset(c, 0, sizeof *c);
    if (nx_title_control(t, &n)) return;
    if (!n.key_ok || (s = nx_nca_find_section(&n, NX_FS_ROMFS)) < 0 || !(root = nx_romfs_tree(&n, s))) { nx_nca_close(&n); return; }
    NxNode *f = nx_node_find(root, "control.nacp");
    if (f && !f->is_dir && f->size >= 0x3000 && f->size <= 0x4000 && !nx_node_read_whole(f, &c->nacp, 0x4000)) {
        c->nacp_n = (size_t)f->size;
        size_t k = 0;   // the display version: text at 0x3060, at most 16 bytes; taken only when it is printable ASCII
        while (c->nacp_n >= 0x3070 && k < 16 && c->nacp[0x3060 + k] >= 0x20 && c->nacp[0x3060 + k] < 0x7F) k++;
        if (k && (k == 16 || !c->nacp[0x3060 + k])) { memcpy(c->version, c->nacp + 0x3060, k); c->version[k] = 0; }
    }
    NxNode *ic = nx_node_find(root, "icon_AmericanEnglish.dat");
    if (!ic) for (int i = 0; i < root->nkids; i++) if (!strncmp(root->kids[i]->name, "icon_", 5) && !root->kids[i]->is_dir) { ic = root->kids[i]; break; }
    if (ic && !ic->is_dir && ic->size >= 8 && ic->size <= META_ICON_MAX && !nx_node_read_whole(ic, &c->icon, META_ICON_MAX)) {
        // an image by its first bytes (the spec asks readers to check them): a JPEG, as the console's are, or a PNG
        if ((c->icon[0] == 0xFF && c->icon[1] == 0xD8) || !memcmp(c->icon, "\x89PNG\r\n\x1a\n", 8)) c->icon_n = (size_t)ic->size;
        else { free(c->icon); c->icon = NULL; }
    }
    nx_node_free(root); nx_nca_close(&n);
}

// p's meta block (app: the package that carries the application's records). Only running out of memory fails
static int pkg_meta(Pkg *p, int app) {
    if (p->kind == 2 || !p->t) return 0;
    Ctl c; uint8_t *npdm = NULL; size_t npdm_n = 0; int rc = -1;
    ctl_read(p->t, &c);
    NxNode *np = nx_node_find(p->root, "exefs/main.npdm");
    if (np && !np->is_dir && np->size && !nx_node_read_whole(np, &npdm, META_NPDM_MAX)) npdm_n = (size_t)np->size;
    if (tlv_add(p, TLV_NPDM, npdm, npdm_n) || tlv_add(p, TLV_NACP, c.nacp, c.nacp_n) || (app && tlv_add(p, TLV_ICON, c.icon, c.icon_n)) ||
        tlv_add(p, TLV_VERSION, c.version, strlen(c.version)) || (app && tlv_add(p, TLV_TITLE, p->t->name, strlen(p->t->name))) ||
        (app && tlv_add(p, TLV_PUBLISHER, p->t->publisher, strlen(p->t->publisher)))) goto out;
    rc = 0;
out:
    free(npdm); free(c.nacp); free(c.icon); return rc;
}

static int push(NxNode ***a, uint32_t *n, uint32_t *cap, NxNode *x) {
    if (*n == *cap) { uint32_t nc = *cap ? *cap * 2 : 256; NxNode **na = realloc(*a, sizeof *na * nc); if (!na) { nx_err("out of memory"); return -1; } *a = na; *cap = nc; }
    (*a)[(*n)++] = x; return 0;
}

typedef struct Intern { uint32_t off, len, h; } Intern;
static int intern(Job *j, const char *s, uint32_t *off) {   // the string pool: one copy of each distinct name (open-addressing index)
    size_t l = strlen(s); uint32_t h = 2166136261u; for (size_t i = 0; i < l; i++) h = (h ^ (uint8_t)s[i]) * 16777619u;
    if (j->nidx * 2 >= j->cidx) {   // grow (or create) the index
        uint32_t nc = j->cidx ? j->cidx * 2 : 4096; Intern *ni = calloc(nc, sizeof *ni); if (!ni) { nx_err("out of memory"); return -1; }
        for (uint32_t i = 0; i < j->cidx; i++) if (j->idx[i].len || j->idx[i].h) { uint32_t s2 = j->idx[i].h & (nc - 1); while (ni[s2].len || ni[s2].h) s2 = (s2 + 1) & (nc - 1); ni[s2] = j->idx[i]; }
        free(j->idx); j->idx = ni; j->cidx = nc;
    }
    uint32_t slot = h & (j->cidx - 1);
    while (j->idx[slot].len || j->idx[slot].h) { Intern *x = &j->idx[slot]; if (x->h == h && x->len == l && (!l || !memcmp(j->pool + x->off, s, l))) { *off = x->off; return 0; } slot = (slot + 1) & (j->cidx - 1); }
    if (j->pool_n + l > j->pool_cap) { size_t nc = j->pool_cap ? j->pool_cap * 2 : 65536; while (nc < j->pool_n + l) nc *= 2; uint8_t *np = realloc(j->pool, nc); if (!np) { nx_err("out of memory"); return -1; } j->pool = np; j->pool_cap = nc; }
    if (l) memcpy(j->pool + j->pool_n, s, l);   // an empty name before any other: the pool is not allocated yet
    *off = (uint32_t)j->pool_n; j->pool_n += l;
    j->idx[slot].off = *off; j->idx[slot].len = (uint32_t)l; j->idx[slot].h = h ? h : 1; j->nidx++;
    return 0;
}

static uint32_t sha_slot(const Job *j, const uint8_t *sha) { uint64_t k; memcpy(&k, sha, 8); return (uint32_t)(k & (j->shacap - 1)); }
static NxNode *sha_lookup(const Job *j, const NxNode *f) {
    for (uint32_t s = sha_slot(j, f->sha);; s = (s + 1) & (j->shacap - 1)) { NxNode *x = j->shamap[s]; if (!x) return NULL; if (x->size == f->size && !memcmp(x->sha, f->sha, 32)) return x; }
}
static void sha_insert(Job *j, NxNode *f) { for (uint32_t s = sha_slot(j, f->sha);; s = (s + 1) & (j->shacap - 1)) if (!j->shamap[s]) { j->shamap[s] = f; return; } }

static int cmp_off(const void *a, const void *b) { const NxNode *x = *(NxNode *const *)a, *y = *(NxNode *const *)b; return x->off < y->off ? -1 : x->off > y->off; }
// every file under d, in no particular order (an explicit stack: a RomFS can be deep)
static int collect_files(NxNode *d, NxNode ***out, uint32_t *n, uint32_t *cap) {
    NxNode **stack = malloc(sizeof *stack * 4096); if (!stack) { nx_err("out of memory"); return -1; } int sp = 0; stack[sp++] = d;
    while (sp) { NxNode *x = stack[--sp]; for (int k = 0; k < x->nkids; k++) { if (x->kids[k]->is_dir) { if (sp < 4096) stack[sp++] = x->kids[k]; } else if (push(out, n, cap, x->kids[k])) { free(stack); return -1; } } }
    free(stack); return 0;
}

static int write_all(int fd, const void *p, size_t n, uint64_t off) {
    const uint8_t *b = p; while (n) { ssize_t w = pwrite(fd, b, n, (off_t)off); if (w < 0) { if (errno == EINTR) continue; nx_err("write failed: %s", strerror(errno)); return -1; } b += w; n -= (size_t)w; off += (uint64_t)w; } return 0;
}

static void report(Job *j, const char *step) { if (j->progress) { j->cur_step = step; j->progress(j->user, step, j->done, j->total); } }   // once per MiB
static void advance(Job *j, const char *step, uint64_t n) { while (n) { uint64_t k = n > NX_CHUNK ? NX_CHUNK : n; j->done += (long long)k; n -= k; report(j, step); } }

// ---- the blob: a pipeline. The files go in node-id order and every byte of the .nup is decided in that order (an extent's place, sharing an equal
// earlier extent, the BKTR shortcut), exactly as one thread writing file after file would; what runs in parallel is the reading (decrypt + decompress)
// of consecutive chunks, by `threads` workers each with its own decompression scratch. A chunk (one ring slot, ~PIPE_CHUNK bytes) is a run of segments:
// pieces of one big file, or many whole small files back to back (so a worker's cache still serves the LZ4 entry two neighbouring files share), cut
// where an LZ4 entry ends (nx_sec_chunk_end) so no entry is decompressed by two workers. One hasher thread does the SHA-256 in order (the hash is one
// serial chain per file), the caller's thread writes in order and keeps the bookkeeping. At most PIPE_BUDGET bytes are in flight.
#define PIPE_SLOTS 64
#define PIPE_CHUNK (4u << 20)
#define PIPE_BUDGET (256u << 20)
#define PIPE_MAX_SEGS 4096
enum { SL_FREE, SL_QUEUED, SL_READ, SL_HASHED };
typedef struct Seg { NxNode *f; uint64_t off; size_t len; NxNode *share; int first, last; } Seg;   // share: the base file to share (the BKTR shortcut); len 0 and no share: an empty file
typedef struct Slot { Seg *seg; int nseg, segcap; uint8_t *buf; size_t cap, used; int state, rc; char err[256]; } Slot;
typedef struct Pipe {
    pthread_mutex_t mu; pthread_cond_t cv_work, cv_hash, cv_main;
    Slot slot[PIPE_SLOTS];
    uint64_t q_seq, claim_seq, hash_seq;   // enqueued / claimed by a worker / hashed: the next sequence number of each stage (write_seq lives in the loop)
    uint64_t write_seq; size_t inflight; int stop, nworkers, hasher_up;
    pthread_t *th, hasher; NxSha256 h;
} Pipe;

static int nx_threads_setting;   // nximport_set_threads
void nximport_set_threads(int n) { nx_threads_setting = n; }
static int thread_count(void) {
    int n = nx_threads_setting;
    if (n <= 0) { const char *e = getenv("NXIMPORT_THREADS"); if (e && *e) n = atoi(e); }
    if (n <= 0) { int c = nx_cpu_count(); n = c > 0 ? c : 2; if (n > 6) n = 6; }
    return n > 64 ? 64 : n;
}
int nx_threads(void) { return thread_count(); }

static void *pipe_worker(void *arg) {
    Pipe *P = arg; NxCompCache cc; memset(&cc, 0, sizeof cc);
    pthread_mutex_lock(&P->mu);
    for (;;) {
        while (!P->stop && P->claim_seq == P->q_seq) pthread_cond_wait(&P->cv_work, &P->mu);
        if (P->stop) break;
        Slot *s = &P->slot[P->claim_seq++ % PIPE_SLOTS];
        pthread_mutex_unlock(&P->mu);
        s->rc = 0; uint8_t *at = s->buf;
        for (int i = 0; i < s->nseg && !s->rc; i++) {
            Seg *g = &s->seg[i]; if (!g->len || g->share) continue;
            if (nx_node_read_tc(g->f, g->off, at, g->len, &cc)) { s->rc = -1; snprintf(s->err, sizeof s->err, "%s", nximport_last_error()); }
            at += g->len;
        }
        pthread_mutex_lock(&P->mu);
        s->state = SL_READ; pthread_cond_broadcast(&P->cv_hash);
    }
    pthread_mutex_unlock(&P->mu);
    nx_comp_cache_free(&cc); return NULL;
}

static void *pipe_hasher(void *arg) {
    Pipe *P = arg;
    pthread_mutex_lock(&P->mu);
    for (;;) {
        Slot *s = &P->slot[P->hash_seq % PIPE_SLOTS];
        while (!P->stop && !(P->hash_seq < P->q_seq && s->state == SL_READ)) pthread_cond_wait(&P->cv_hash, &P->mu);
        if (P->stop) break;
        pthread_mutex_unlock(&P->mu);
        if (!s->rc) {
            const uint8_t *at = s->buf;
            for (int i = 0; i < s->nseg; i++) {
                Seg *g = &s->seg[i]; if (!g->len || g->share) continue;
                if (g->first) nx_sha256_init(&P->h);
                nx_sha256_update(&P->h, at, g->len); at += g->len;
                if (g->last) nx_sha256_final(&P->h, g->f->sha);
            }
        }
        pthread_mutex_lock(&P->mu);
        s->state = SL_HASHED; P->hash_seq++; pthread_cond_broadcast(&P->cv_main);
    }
    pthread_mutex_unlock(&P->mu); return NULL;
}

static void pipe_stop(Pipe *P) {
    pthread_mutex_lock(&P->mu); P->stop = 1; pthread_cond_broadcast(&P->cv_work); pthread_cond_broadcast(&P->cv_hash); pthread_mutex_unlock(&P->mu);
    for (int i = 0; i < P->nworkers; i++) pthread_join(P->th[i], NULL);
    if (P->hasher_up) pthread_join(P->hasher, NULL);
    for (int i = 0; i < PIPE_SLOTS; i++) { free(P->slot[i].seg); free(P->slot[i].buf); }
    free(P->th); pthread_mutex_destroy(&P->mu); pthread_cond_destroy(&P->cv_work); pthread_cond_destroy(&P->cv_hash); pthread_cond_destroy(&P->cv_main);
}

// the base file an update file f shares (the BKTR shortcut: one unbroken run of the base's bytes, where the base has a file of that very size), else NULL.
// `written` is order-based (set when a file is dispatched), so the answer is the one a serial writer would give.
static NxNode *base_share(Job *j, NxNode *f) {
    uint64_t boff; const NxSection *S = &f->nca->sec[f->sec];
    if (!(S->crypt_type == 4 && j->nbase && S->base == j->base_rom && nx_bktr_maps_to_base(f->nca, f->sec, f->off, f->size, &boff))) return NULL;
    uint32_t lo = 0, hi = j->nbase;
    while (lo < hi) { uint32_t m = (lo + hi) / 2; if (j->basefiles[m]->off < boff) lo = m + 1; else hi = m; }
    for (uint32_t k = lo; k < j->nbase && j->basefiles[k]->off == boff; k++) { NxNode *b = j->basefiles[k]; if (b->written && b->size == f->size) return b; }
    return NULL;
}

static int slot_add(Slot *s, Seg g) {
    if (s->nseg == s->segcap) { int nc = s->segcap ? s->segcap * 2 : 64; Seg *ns = realloc(s->seg, sizeof *ns * (size_t)nc); if (!ns) { nx_err("out of memory"); return -1; } s->seg = ns; s->segcap = nc; }
    s->seg[s->nseg++] = g; s->used += g.len; return 0;
}

// Writes every file's bytes into the .nup from *pos on (16-byte aligned extents), hashing; equal files share one extent. 0 ok
static int write_blob(Job *j, int fd, uint64_t *pos, const char **steps) {
    Pipe *P = calloc(1, sizeof *P); if (!P) { nx_err("out of memory"); return -1; }
    pthread_mutex_init(&P->mu, NULL); pthread_cond_init(&P->cv_work, NULL); pthread_cond_init(&P->cv_hash, NULL); pthread_cond_init(&P->cv_main, NULL);
    int rc = -1, want = thread_count(), built = 0; uint32_t fi = 0; uint64_t fo = 0, start = 0;   // fi/fo: the next file and offset to dispatch; built: the slot at q_seq is ready
    for (uint32_t i = 0; i < j->nf; i++) if (nx_sec_prepare(j->files[i]->nca, j->files[i]->sec)) goto out;   // the compression tables are loaded before the threads start
    if (!(P->th = calloc((size_t)want, sizeof *P->th))) NX_FAIL("out of memory");
    for (int i = 0; i < want; i++) { if (pthread_create(&P->th[i], NULL, pipe_worker, P)) break; P->nworkers++; }
    if (!P->nworkers) NX_FAIL("cannot create a worker thread: %s", strerror(errno));
    if (pthread_create(&P->hasher, NULL, pipe_hasher, P)) NX_FAIL("cannot create the hasher thread: %s", strerror(errno));
    P->hasher_up = 1;
    pthread_mutex_lock(&P->mu);
    while (fi < j->nf || built || P->write_seq < P->q_seq) {
        // dispatch: build the next slot (the files are walked once, in order: a file is dispatched exactly once, so `written` means "earlier in order"),
        // enqueue it when it fits the budget (always at least one slot in flight), else keep it built and wait for the head to drain
        while (fi < j->nf || built) {
            Slot *s = &P->slot[P->q_seq % PIPE_SLOTS];
            if (P->q_seq - P->write_seq == PIPE_SLOTS) break;   // the ring is full
            if (!built) {
                s->nseg = 0; s->used = 0; s->rc = 0;
                while (fi < j->nf && s->used < PIPE_CHUNK && s->nseg < PIPE_MAX_SEGS) {
                    NxNode *f = j->files[fi]; Seg g = { f, fo, 0, NULL, fo == 0, 0 };
                    if (fo == 0) {
                        f->codec = 0; f->ext_stored = f->size; f->shared = 0; f->written = 1;
                        if (f->size) g.share = base_share(j, f);
                    }
                    if (!f->size || g.share) { g.last = 1; }
                    else {
                        uint64_t end = fo + (PIPE_CHUNK - s->used); if (end > f->size) end = f->size;
                        end = nx_sec_chunk_end(f->nca, f->sec, f->off + fo, f->off + end, f->off + f->size) - f->off;
                        g.len = (size_t)(end - fo); g.last = end == f->size;
                    }
                    if (slot_add(s, g)) { pthread_mutex_unlock(&P->mu); goto out; }
                    if (g.last) { fi++; fo = 0; } else fo += g.len;
                }
                if (!s->nseg) break;
                built = 1;
            }
            if (P->inflight && P->inflight + s->used > PIPE_BUDGET) break;   // over budget: the slot stays built, enqueued after the head is written
            if (s->cap < s->used) { uint8_t *nb = realloc(s->buf, s->used); if (!nb) { pthread_mutex_unlock(&P->mu); nx_err("out of memory"); goto out; } s->buf = nb; s->cap = s->used; }
            P->inflight += s->used; s->state = SL_QUEUED; P->q_seq++; built = 0; pthread_cond_broadcast(&P->cv_work);
        }
        if (P->write_seq == P->q_seq) continue;   // (only when every file is dispatched: nothing left to wait for)
        // consume the next slot in order
        Slot *s = &P->slot[P->write_seq % PIPE_SLOTS];
        while (s->state != SL_HASHED) pthread_cond_wait(&P->cv_main, &P->mu);
        pthread_mutex_unlock(&P->mu);
        if (s->rc) { nx_err("%s", s->err); goto out; }
        if (atomic_load(&nx_stop)) { nx_err("the import was stopped"); goto out; }
        const uint8_t *at = s->buf;
        for (int i = 0; i < s->nseg; i++) {
            Seg *g = &s->seg[i]; NxNode *f = g->f; const char *step = steps[f->id];
            if (!f->size) { f->ext_off = 0; nx_sha256(NULL, 0, f->sha); continue; }
            if (g->share) { NxNode *b = g->share; f->ext_off = b->ext_off; f->ext_stored = b->ext_stored; f->codec = b->codec; memcpy(f->sha, b->sha, 32); f->shared = 1; advance(j, step, f->size); continue; }
            if (g->first) start = *pos;
            if (write_all(fd, at, g->len, start + g->off)) goto out;
            at += g->len; advance(j, step, g->len);
            if (g->last) {
                NxNode *same = sha_lookup(j, f);
                if (same) { f->ext_off = same->ext_off; f->ext_stored = same->ext_stored; f->codec = same->codec; f->shared = 1; if (ftruncate(fd, (off_t)start)) { nx_err("truncate failed: %s", strerror(errno)); goto out; } }
                else { f->ext_off = start; *pos = (start + f->size + 15) & ~15ull; sha_insert(j, f); }
            }
        }
        pthread_mutex_lock(&P->mu);
        P->inflight -= s->used; s->state = SL_FREE; P->write_seq++;
        if (s->cap > 2 * PIPE_CHUNK) { free(s->buf); s->buf = NULL; s->cap = 0; }   // a slot that grew for a huge entry gives the memory back
    }
    pthread_mutex_unlock(&P->mu);
    rc = 0;
out:
fail:
    pipe_stop(P); free(P); return rc;
}

static void sum_sizes(Job *j, NxNode *d) { for (int i = 0; i < d->nkids; i++) { if (d->kids[i]->is_dir) sum_sizes(j, d->kids[i]); else j->total += (long long)d->kids[i]->size; } }

static int write_nup(Job *j, const char *out) {
    int fd = -1, rc = -1; uint8_t *toc = NULL; const char **steps = NULL; char (*stepnames)[64] = NULL;
    // node ids: every directory first, breadth first from the package roots (a directory's children are one run of child slots), then the files in directory order
    for (int p = 0; p < j->npk; p++) if (push(&j->dirs, &j->nd, &j->dcap, j->pk[p].root)) goto out;
    for (uint32_t i = 0; i < j->nd; i++) { NxNode *d = j->dirs[i]; d->id = i; for (int k = 0; k < d->nkids; k++) if (d->kids[k]->is_dir && push(&j->dirs, &j->nd, &j->dcap, d->kids[k])) goto out; }
    for (uint32_t i = 0; i < j->nd; i++) { NxNode *d = j->dirs[i]; for (int k = 0; k < d->nkids; k++) if (!d->kids[k]->is_dir) { d->kids[k]->id = j->nf; if (push(&j->files, &j->nf, &j->fcap, d->kids[k])) goto out; } }
    // the step name of each file: "<selector>/<exefs|romfs>"
    if (!(stepnames = calloc((size_t)j->npk * 2, 64)) || !(steps = calloc(j->nf ? j->nf : 1, sizeof *steps))) NX_FAIL("out of memory");
    for (int p = 0; p < j->npk; p++) for (int t = 0; t < j->pk[p].root->nkids; t++) {
        NxNode *top = j->pk[p].root->kids[t]; snprintf(stepnames[p * 2 + t], 64, "%s/%s", j->pk[p].sel, top->name);
        NxNode **fl = NULL; uint32_t nfl = 0, cfl = 0; if (collect_files(top, &fl, &nfl, &cfl)) goto out;
        for (uint32_t i = 0; i < nfl; i++) steps[fl[i]->id] = stepnames[p * 2 + t];
        free(fl);
    }
    uint64_t nkids = 0; for (uint32_t i = 0; i < j->nd; i++) nkids += (uint64_t)j->dirs[i]->nkids;
    for (uint32_t i = 0; i < j->nd; i++) if (intern(j, j->dirs[i]->name, &j->dirs[i]->name_off)) goto out;
    for (uint32_t i = 0; i < j->nf; i++) if (intern(j, j->files[i]->name, &j->files[i]->name_off)) goto out;
    uint64_t o_pk = 0x30, o_dir = o_pk + 0x40ull * j->npk, o_file = o_dir + 0x18ull * j->nd, o_kids = o_file + 0x50ull * j->nf, o_pool = o_kids + 4 * nkids, toc_size = o_pool + j->pool_n;
    uint64_t blob = (0x80 + toc_size + 15) & ~15ull;
    if (toc_size > (1ull << 31)) NX_FAIL("TOC too large");
    // sha map and the base file index
    j->shacap = 1024; while (j->shacap < 2 * j->nf) j->shacap *= 2; if (!(j->shamap = calloc(j->shacap, sizeof *j->shamap))) NX_FAIL("out of memory");
    for (int p = 0; p < j->npk; p++) if (j->pk[p].kind == 0) {
        NxNode *r = nx_node_find(j->pk[p].root, "romfs"); uint32_t bcap = 0;
        if (r && collect_files(r, &j->basefiles, &j->nbase, &bcap)) goto out;
        if (j->nbase) qsort(j->basefiles, j->nbase, sizeof *j->basefiles, cmp_off);
    }
    // the blob
    if ((fd = open(out, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644)) < 0) NX_FAIL("cannot create %s: %s", out, strerror(errno));
    uint64_t pos = blob;
    if (write_blob(j, fd, &pos, steps)) goto out;
    if (atomic_load(&nx_stop)) NX_FAIL("the import was stopped");   // a Stop while the last files went in: the .nup is not finished
    uint64_t end = blob; for (uint32_t i = 0; i < j->nf; i++) if (j->files[i]->size && j->files[i]->ext_off + j->files[i]->ext_stored > end) end = j->files[i]->ext_off + j->files[i]->ext_stored;
    if (ftruncate(fd, (off_t)end)) NX_FAIL("truncate failed: %s", strerror(errno));
    for (int p = 0; p < j->npk; p++) {   // the meta blocks, one after the other behind the last extent (where the nup packer puts them); the file ends with them
        Pkg *k = &j->pk[p]; if (!k->meta_n) continue;
        if (write_all(fd, k->meta, k->meta_n, end)) goto out;
        k->meta_off = end; end += k->meta_n;
    }
    report(j, "toc");
    // the TOC
    if (!(toc = calloc(1, (size_t)toc_size))) NX_FAIL("out of memory");
    memcpy(toc, "TOC\x01", 4); put32(toc + 4, (uint32_t)j->npk); put64(toc + 8, j->nd); put64(toc + 0x10, j->nf); put64(toc + 0x18, o_pool); put64(toc + 0x20, j->pool_n);
    for (int p = 0; p < j->npk; p++) {
        uint8_t *r = toc + o_pk + 0x40 * p; const Pkg *k = &j->pk[p]; NxNode *ex = nx_node_find(k->root, "exefs"), *ro = nx_node_find(k->root, "romfs");
        put64(r, k->tid); put32(r + 8, k->root->id); put32(r + 0xC, ex ? ex->id : 0xFFFFFFFFu); put32(r + 0x10, ro ? ro->id : 0xFFFFFFFFu); put32(r + 0x14, k->ver); r[0x18] = (uint8_t)k->kind; r[0x19] = (uint8_t)k->flags;
        if (k->meta_n) { put64(r + 0x20, k->meta_off); put32(r + 0x28, (uint32_t)k->meta_n); }
    }
    uint64_t kid = 0;
    for (uint32_t i = 0; i < j->nd; i++) {
        NxNode *d = j->dirs[i]; uint8_t *r = toc + o_dir + 0x18 * i;
        put32(r, d->name_off); put32(r + 4, (uint32_t)strlen(d->name)); put32(r + 8, (uint32_t)kid); put32(r + 12, (uint32_t)d->nkids);
        for (int k = 0; k < d->nkids; k++, kid++) put32(toc + o_kids + 4 * kid, d->kids[k]->is_dir ? d->kids[k]->id : j->nd + d->kids[k]->id);
    }
    for (uint32_t i = 0; i < j->nf; i++) {
        NxNode *f = j->files[i]; uint8_t *r = toc + o_file + 0x50 * i;
        put32(r, f->name_off); put32(r + 4, (uint32_t)strlen(f->name)); put64(r + 8, f->size); put64(r + 0x10, f->ext_off); put64(r + 0x18, f->ext_stored); memcpy(r + 0x20, f->sha, 32); r[0x40] = (uint8_t)f->codec;
    }
    memcpy(toc + o_pool, j->pool, j->pool_n);
    // the superblock
    uint8_t sb[0x80]; memset(sb, 0, sizeof sb); memcpy(sb, "NUP\x01", 4); put16(sb + 4, 0x80); put16(sb + 6, 1); put64(sb + 8, 0x80); put64(sb + 0x10, toc_size);
    nx_sha256(toc, (size_t)toc_size, sb + 0x18); put64(sb + 0x38, blob); put64(sb + 0x40, end); put32(sb + 0x48, (uint32_t)j->npk); put32(sb + 0x4C, 0);
    { NxSha256 u; nx_sha256_init(&u); uint8_t uh[32]; for (int p = 0; p < j->npk; p++) { uint8_t rec[13]; put64(rec, j->pk[p].tid); put32(rec + 8, j->pk[p].ver); rec[12] = (uint8_t)j->pk[p].kind; nx_sha256_update(&u, rec, 13); } nx_sha256_final(&u, uh); memcpy(sb + 0x50, uh, 16); }
    if (write_all(fd, sb, 0x80, 0) || write_all(fd, toc, (size_t)toc_size, 0x80)) goto out;
    if (fsync(fd)) { nx_err("fsync failed: %s", strerror(errno)); goto out; }
    rc = 0;
out:
fail:
    if (fd >= 0) close(fd); if (rc) unlink(out);
    free(toc); free(steps); free(stepnames); return rc;
}

int nximport_write_nup(const char *out_nup, const char **inputs, int n_inputs, const char *base_title_id,
                       void (*progress)(void *user, const char *step, long long done, long long total), void *user) {
    Src *src = NULL; Job *j = NULL; int rc = -1; uint64_t base_tid;
    if (!nx_keys.loaded) { nx_err("keys not loaded (nximport_keys)"); return -1; }
    if (parse_tid(base_title_id, &base_tid)) { nx_err("bad base title id %s", base_title_id ? base_title_id : "(null)"); return -1; }
    if (n_inputs < 1 || n_inputs > 64) { nx_err("1 to 64 inputs"); return -1; }
    if (!(src = calloc((size_t)n_inputs, sizeof *src)) || !(j = calloc(1, sizeof *j))) { nx_err("out of memory"); goto out; }
    for (int i = 0; i < n_inputs; i++) src[i].c.fd = -1;
    j->progress = progress; j->user = user;
    if (progress) progress(user, "probe", 0, 0);
    for (int i = 0; i < n_inputs; i++) { if (nx_container_open(&src[i].c, inputs[i])) goto out; if (nx_container_titles(&src[i].c, &src[i].ts, &src[i].n)) goto out; }
    // the base first (its RomFS is the updates' base), then the updates by version, then the add-ons
    const NxTitle *base = NULL; NxNca *base_prog = NULL; int base_rom = -1;
    for (int i = 0; i < n_inputs && !base; i++) for (int k = 0; k < src[i].n; k++) if (src[i].ts[k].kind == 0 && src[i].ts[k].title_id == base_tid) { base = &src[i].ts[k]; break; }
    // compressed inputs: where solid NCZs are unpacked (NXIMPORT_TMPDIR, else a hidden folder beside the .nup, which is gone again at the end), and what
    // unpacking and checking them takes, for their progress. The titles as the loops below take them: the base, each update version once, each add-on once
    { const char *td = getenv("NXIMPORT_TMPDIR"), *sl = strrchr(out_nup, '/'), *bs = strrchr(out_nup, '\\'); if (bs && (!sl || bs > sl)) sl = bs;
      if (td && *td) snprintf(j->tmp, sizeof j->tmp, "%s", td);
      else snprintf(j->tmp, sizeof j->tmp, "%.*s.%s.unpack", sl ? (int)(sl - out_nup + 1) : 0, out_nup, sl ? sl + 1 : out_nup); }
    { uint64_t sum = 0; uint32_t vers[64]; uint64_t aoc[64]; int nv = 0, na = 0;
      if (base && job_ncz_bytes(base, base->program, &sum)) goto out;
      for (int pass = 0; pass < 2; pass++) for (int i = 0; i < n_inputs; i++) for (int k = 0; k < src[i].n; k++) {
          const NxTitle *t = &src[i].ts[k]; int dup = 0;
          if (t->app_id != base_tid || t->kind != (pass ? 2 : 1)) continue;
          if (t->kind == 1) { for (int q = 0; q < nv; q++) dup |= vers[q] == t->version; if (dup || nv == 64) continue; vers[nv++] = t->version; if (job_ncz_bytes(t, t->program, &sum)) goto out; }
          else { for (int q = 0; q < na; q++) dup |= aoc[q] == t->title_id; if (dup || na == 64) continue; aoc[na++] = t->title_id; if (job_ncz_bytes(t, t->data, &sum)) goto out; }
      }
      j->prep_total = (long long)sum; }
    if (base) {
        if (!base->program[0]) NX_FAIL("the base %s has no program NCA", base_title_id);
        if (!(base_prog = job_nca(j, base, base->program, base->program_sha))) goto out;
        int ex = nx_nca_find_section(base_prog, NX_FS_PFS0); base_rom = nx_nca_find_section(base_prog, NX_FS_ROMFS);
        NxNode *exefs = ex >= 0 ? nx_pfs0_tree(base_prog, ex) : NULL; if (ex >= 0 && !exefs) goto out;
        // PEANUTS: a sparse base RomFS (Castlevania: Belmont's Curse) holds only the ranges its update reads from it, its own header included only in the update: it is the
        // update's base (nx_nca_set_base below), not a tree of its own
        int rom_tree = base_rom >= 0 && !base_prog->sec[base_rom].sparse;
        NxNode *romfs = rom_tree ? nx_romfs_tree(base_prog, base_rom) : NULL; if (rom_tree && !romfs) { nx_node_free(exefs); goto out; }
        if (add_pkg(j, base, 0, base->title_id, base->version, exefs, romfs)) goto out;
        j->base_rom = base_prog; j->base_rom_sec = base_rom;
    }

    for (int pass = 0; pass < 2; pass++) for (int i = 0; i < n_inputs; i++) for (int k = 0; k < src[i].n; k++) {
        const NxTitle *t = &src[i].ts[k];
        if (t->app_id != base_tid || t->kind != (pass ? 2 : 1)) continue;
        if (t->kind == 1) {
            int dup = 0; for (int p = 0; p < j->npk; p++) if (j->pk[p].kind == 1 && j->pk[p].ver == t->version) dup = 1; if (dup) continue;
            if (!t->program[0]) NX_FAIL("update %016llx v%u has no program NCA", (unsigned long long)t->title_id, t->version);
            NxNca *pn = job_nca(j, t, t->program, t->program_sha); if (!pn) goto out;
            int ex = nx_nca_find_section(pn, NX_FS_PFS0), ro = nx_nca_find_section(pn, NX_FS_ROMFS);
            if (ro >= 0 && pn->sec[ro].crypt_type == 4) {
                if (!base_prog || base_rom < 0) NX_FAIL("the update v%u patches the base game's RomFS: the base %s must be among the inputs", t->version, base_title_id);
                if (nx_nca_set_base(pn, ro, base_prog, base_rom)) goto out;
            }
            NxNode *exefs = ex >= 0 ? nx_pfs0_tree(pn, ex) : NULL; if (ex >= 0 && !exefs) goto out;
            NxNode *romfs = ro >= 0 ? nx_romfs_tree(pn, ro) : NULL; if (ro >= 0 && !romfs) { nx_node_free(exefs); goto out; }
            if (add_pkg(j, t, 1, t->title_id, t->version, exefs, romfs)) goto out;
            j->pk[j->npk - 1].flags = base ? 0 : 1;
        } else {
            int dup = 0; for (int p = 0; p < j->npk; p++) if (j->pk[p].kind == 2 && j->pk[p].tid == t->title_id) dup = 1; if (dup) continue;
            if (!t->data[0]) NX_FAIL("add-on %016llx has no data NCA", (unsigned long long)t->title_id);
            NxNca *dn = job_nca(j, t, t->data, t->data_sha); if (!dn) goto out;
            int ro = nx_nca_find_section(dn, NX_FS_ROMFS); if (ro < 0) NX_FAIL("add-on %016llx has no RomFS", (unsigned long long)t->title_id);
            NxNode *romfs = nx_romfs_tree(dn, ro); if (!romfs) goto out;
            if (add_pkg(j, t, 2, t->title_id, t->version, NULL, romfs)) goto out;
        }
    }
    if (!j->npk) NX_FAIL("nothing for %s in the inputs (no base, update or add-on with that application id)", base_title_id);
    { int app = 0; for (int p = 0; p < j->npk; p++) if (j->pk[p].kind == 0) { app = p; break; }   // the base carries the application's records, else the first package
      for (int p = 0; p < j->npk; p++) if (pkg_meta(&j->pk[p], p == app)) goto out; }
    for (int p = 0; p < j->npk; p++) sum_sizes(j, j->pk[p].root);
    rc = write_nup(j, out_nup);
out:
fail:
    if (j) {
        for (int p = 0; p < j->npk; p++) { nx_node_free(j->pk[p].root); free(j->pk[p].meta); }   // the package's meta block (#158)
        for (int i = 0; i < j->nncas; i++) { nx_nca_close(&j->ncas[i]); nx_ncz_close(j->ncz[i]); }   // an NCZ's temporary file goes with it
        if (j->tmp[0] && !getenv("NXIMPORT_TMPDIR")) rmdir(j->tmp);   // the folder made for it, when empty (a folder of the caller's stays)
        free(j->dirs); free(j->files); free(j->pool); free(j->idx); free(j->shamap); free(j->basefiles); free(j);
    }
    if (src) { for (int i = 0; i < n_inputs; i++) { free(src[i].ts); nx_container_close(&src[i].c); } free(src); }
    nx_ncz_thread_done();
    atomic_store(&nx_stop, 0);   // a Stop ends this import only
    return rc;
}

// ---------------------------------------------------------------- firmware fonts
typedef struct FontJob {
    int got_std, got_ext; int found_ncas; int got_cjk[4];
    uint8_t *data[6]; size_t size[6];   // decode first; an invalid import must not replace any installed font
    char why[2][256];   // required font's read/decryption failure, not a missing archive (issue #22)
    int got_mii, nmii; char mii_name[16][64]; uint8_t *mii_data[16]; size_t mii_size[16];   // PEANUTS: MiiModel 0100000000000802's files (optional)
} FontJob;   // got_cjk: cjk_sc, cjk_sc_ext, cjk_tc, cjk_kr (optional)
// The firmware's Chinese and Korean shared fonts, written beside the two the app needs: a game set to one of those languages asks the console for them (types 1 to 4 of
// the shared font service) and drew most characters as "?" with the standard font (Pokemon Shield with a Chinese save). FontKorean 0100000000000812 (one file),
// FontChineseTraditional 0100000000000813 (one file), FontChineseSimple 0100000000000814 (the base file and an "ext" one). Not required: a firmware without them still gives the two
static int read_bfttf(const NxNode *f, uint8_t **data, size_t *size);
static int font_all(const FontJob *fj) { return fj->got_std && fj->got_ext && fj->got_cjk[0] && fj->got_cjk[1] && fj->got_cjk[2] && fj->got_cjk[3] && fj->got_mii; }   // nothing left to look for
static void font_try_cjk(FontJob *fj, NxNca *n) {
    int base = n->title_id == 0x0100000000000814ull ? 0 : n->title_id == 0x0100000000000813ull ? 2 : n->title_id == 0x0100000000000812ull ? 3 : -1;
    if (base < 0) return;
    int s = nx_nca_find_section(n, NX_FS_ROMFS); NxNode *root = s >= 0 ? nx_romfs_tree(n, s) : NULL;
    if (!root) return;
    for (int i = 0; i < root->nkids; i++) {
        NxNode *k = root->kids[i]; size_t l = strlen(k->name);
        if (k->is_dir || l < 6 || strcmp(k->name + l - 6, ".bfttf")) continue;
        int slot = base == 0 && strstr(k->name, "ext_") != NULL ? 1 : base;   // the simplified Chinese title holds two: ..._org_zh-cn_... and ..._ext_zh-cn_...
        if (fj->got_cjk[slot]) continue;
        if (!read_bfttf(k, &fj->data[slot + 2], &fj->size[slot + 2])) fj->got_cjk[slot] = 1;
    }
    nx_node_free(root);
}

// PEANUTS begin: the console's MiiModel system data (0100000000000802), written beside the fonts for the host's MountSystemData (hle_mii.c). A game that
// builds Mii heads from it (Mario Kart 8 Deluxe) drew none with the host's empty stand-in; the firmware the player gives the app has the real one. Optional:
// a firmware without it still gives the fonts. Its RomFS holds the files at the root (NXTexture*.dat, Shape*.dat)
static void font_try_mii(FontJob *fj, NxNca *n) {
    if (fj->got_mii || n->title_id != 0x0100000000000802ull) return;
    int s = nx_nca_find_section(n, NX_FS_ROMFS); NxNode *root = s >= 0 ? nx_romfs_tree(n, s) : NULL;
    if (!root) return;
    int ok = 1, k = 0;
    for (int i = 0; i < root->nkids && ok; i++) {
        NxNode *f = root->kids[i];
        if (f->is_dir || strlen(f->name) >= sizeof fj->mii_name[0]) continue;
        if (k == 16 || nx_node_read_whole(f, &fj->mii_data[k], 64u << 20)) { ok = 0; break; }
        snprintf(fj->mii_name[k], sizeof fj->mii_name[k], "%s", f->name); fj->mii_size[k] = (size_t)f->size; k++;
    }
    nx_node_free(root);
    if (ok && k) { fj->nmii = k; fj->got_mii = 1; return; }
    for (int i = 0; i < k; i++) { free(fj->mii_data[i]); fj->mii_data[i] = NULL; }
}
// <fonts dir>/../miimodel, through miimodel.tmp: the old folder is replaced only by a complete one
static void font_write_mii(FontJob *fj, const char *out_dir) {
    if (!fj->got_mii) return;
    char parent[1100], dir[1200], tmp[1200]; snprintf(parent, sizeof parent, "%s", out_dir);
    char *sl = strrchr(parent, '/'); if (sl) *sl = 0; else snprintf(parent, sizeof parent, ".");
    snprintf(dir, sizeof dir, "%s/miimodel", parent); snprintf(tmp, sizeof tmp, "%s/miimodel.tmp", parent);
    if (nx_mkdir(tmp, 0755) && errno != EEXIST) return;
    int ok = 1;
    for (int i = 0; i < fj->nmii && ok; i++) {
        char p[1300]; snprintf(p, sizeof p, "%s/%s", tmp, fj->mii_name[i]);
        FILE *o = fopen(p, "wb"); if (!o) { ok = 0; break; }
        ok = fwrite(fj->mii_data[i], 1, fj->mii_size[i], o) == fj->mii_size[i]; if (fclose(o)) ok = 0;
    }
    if (ok) {
        DIR *d = opendir(dir); struct dirent *e;
        if (d) { while ((e = readdir(d))) if (e->d_name[0] != '.') { char p[1300]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name); remove(p); } closedir(d); rmdir(dir); }
        if (rename(tmp, dir)) ok = 0;
    }
    if (!ok) { DIR *d = opendir(tmp); struct dirent *e;
        if (d) { while ((e = readdir(d))) if (e->d_name[0] != '.') { char p[1300]; snprintf(p, sizeof p, "%s/%s", tmp, e->d_name); remove(p); } closedir(d); rmdir(tmp); } }
}
// PEANUTS end
static int read_bfttf(const NxNode *f, uint8_t **data, size_t *size) {
    uint8_t *b = NULL; int rc = -1;
    if (f->size < 8 || nx_node_read_whole(f, &b, 64u << 20)) goto out;
    size_t n = (size_t)f->size & ~3u;
    for (size_t i = 0; i < n; i += 4) { uint32_t w = u32(b + i) ^ 0x06186249u; put32(b + i, w); }
    if (u32(b) != 0x18029a7fu) { nx_err("%s: not a bfttf (magic %08x)", f->name, u32(b)); goto out; }
    size_t len = u32(b + 4); if (len > f->size - 8) len = (size_t)f->size - 8;
    memmove(b, b + 8, len); *data = b; *size = len; b = NULL;
    rc = 0;
out:
    free(b); return rc;
}

// The common tail once an NCA's bytes are reachable through some (fd, off, size) — a plain file, or (below) a stored or inflated zip member. c is a
// caller-built NxContainer naming only fd (no path, no kind): nx_nca_open only ever reads c->fd and e->off/e->size, so this needs nothing else.
static int font_try_nca_fd(FontJob *fj, int fd, uint64_t off, uint64_t size) {
    NxContainer c; memset(&c, 0, sizeof c); c.fd = fd;
    NxEntry e; memset(&e, 0, sizeof e); snprintf(e.name, sizeof e.name, "nca"); e.off = off; e.size = size;
    NxNca n; fj->found_ncas++;
    if (nx_nca_open(&n, &c, &e)) return 0;   // header didn't decrypt to NCA3/NCA2, or too small: not a readable NCA, skip quietly
    int rc = 0;
    if (n.key_ok) font_try_cjk(fj, &n);
    if (n.key_ok) font_try_mii(fj, &n);   // PEANUTS: MiiModel
    if (n.title_id == 0x0100000000000810ull || n.title_id == 0x0100000000000811ull) {
        int std = n.title_id == 0x0100000000000811ull;
        int s = nx_nca_find_section(&n, NX_FS_ROMFS);
        // The same title also has a metadata NCA: only the archive with RomFS can hold its font.
        if (s >= 0 && !n.key_ok) snprintf(fj->why[std], sizeof fj->why[std], "%s", n.key_why);
        if (!(std ? fj->got_std : fj->got_ext)) {
            NxNode *root = s >= 0 && n.key_ok ? nx_romfs_tree(&n, s) : NULL;
            if (s >= 0 && n.key_ok && !root) snprintf(fj->why[std], sizeof fj->why[std], "%s", nximport_last_error());
            if (root) {
                NxNode *pick = NULL;
                for (int i = 0; i < root->nkids; i++) { NxNode *k = root->kids[i]; size_t l = strlen(k->name); if (k->is_dir || l < 6 || strcmp(k->name + l - 6, ".bfttf")) continue;
                    if (!pick) pick = k;
                    if (std ? strstr(k->name, "std") != NULL : (strstr(k->name, "ext_") != NULL && !strstr(k->name, "ext2"))) { pick = k; break; } }
                if (pick) { int slot = std ? 0 : 1; if (read_bfttf(pick, &fj->data[slot], &fj->size[slot])) rc = -1; else *(std ? &fj->got_std : &fj->got_ext) = 1; }
                nx_node_free(root);
            }
        }
    }
    nx_nca_close(&n); return rc;
}

static int font_try_nca(FontJob *fj, const char *path) {
    NxContainer c; if (nx_container_open(&c, path)) return 0;   // not an NCA we can read: skip
    int rc = c.kind == 2 ? font_try_nca_fd(fj, c.fd, c.e[0].off, c.e[0].size) : 0;
    nx_container_close(&c); return rc;
}

static int font_scan(FontJob *fj, const char *dir, int depth) {
    DIR *d = opendir(dir); if (!d) return 0; struct dirent *e; int rc = 0;
    while (!rc && (!font_all(fj)) && (e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char p[1200]; snprintf(p, sizeof p, "%s/%s", dir, e->d_name); struct stat st; if (stat(p, &st)) continue;
        size_t l = strlen(e->d_name); int is_nca = l > 4 && !strcasecmp(e->d_name + l - 4, ".nca");
        if (S_ISREG(st.st_mode)) { if (is_nca) rc = font_try_nca(fj, p); }
        else if (S_ISDIR(st.st_mode)) {
            if (is_nca) { char q[1300]; snprintf(q, sizeof q, "%s/00", p); if (!stat(q, &st) && S_ISREG(st.st_mode)) rc = font_try_nca(fj, q); }
            else if (depth < 3) rc = font_scan(fj, p, depth + 1);
        }
    }
    closedir(d); return rc;
}

// ---------------------------------------------------------------- firmware fonts, from a .zip (nx_zip.c)
// A stored member's bytes already sit at a fixed offset in the zip file: font_try_nca_fd reads straight from the zip's own fd, no copy, no zlib.
// A deflated member has to be decompressed before anything can look at its header (deflate is a sequential stream, not randomly seekable), so it is
// inflated to one scratch file in out_dir (which nximport_firmware_fonts has already created and is known writable), reusing font_try_nca on that
// file, then the scratch file is removed either way. This is simpler than streaming the header out of a resumable z_stream and keeping it open in
// case of a match: a firmware zip's two font archives are small, and most real firmware zips store their members uncompressed anyway (the NCAs
// inside are already AES-encrypted and partly LZ4-compressed, so a general-purpose zip tool gains little by deflating them again) — the stored path
// above is the one that actually matters for speed, and is the zero-copy one.
static int font_try_zip_deflate(FontJob *fj, int zip_fd, uint64_t data_off, uint64_t comp_size, const char *out_dir) {
    char tmp[1300]; snprintf(tmp, sizeof tmp, "%s/.fontscan.tmp", out_dir);
    FILE *o = fopen(tmp, "wb"); if (!o) { nx_err("cannot create %s: %s", tmp, strerror(errno)); return -1; }
    z_stream zs; memset(&zs, 0, sizeof zs);
    if (inflateInit2(&zs, -15) != Z_OK) { fclose(o); unlink(tmp); nx_err("zip: zlib init failed"); return -1; }   // -15: raw deflate (no zlib/gzip header), the zip member format
    uint8_t inbuf[1 << 16], outbuf[1 << 16];
    uint64_t remaining = comp_size, pos = data_off;
    int zrc = Z_OK, io_err = 0;
    for (;;) {
        if (zs.avail_in == 0) {
            if (!remaining) { zrc = Z_BUF_ERROR; break; }   // ran out of compressed input before inflate reported the stream's end: truncated/bad member
            size_t want = remaining < sizeof inbuf ? (size_t)remaining : sizeof inbuf;
            if (nx_pread_all(zip_fd, inbuf, want, pos)) { io_err = 1; break; }
            zs.next_in = inbuf; zs.avail_in = (unsigned)want; pos += want; remaining -= want;
        }
        zs.next_out = outbuf; zs.avail_out = sizeof outbuf;
        zrc = inflate(&zs, Z_NO_FLUSH);
        size_t have = sizeof outbuf - zs.avail_out;
        if (have && fwrite(outbuf, 1, have, o) != have) { io_err = 1; break; }
        if (zrc == Z_STREAM_END || zrc != Z_OK) break;
    }
    inflateEnd(&zs);
    int close_err = fclose(o);
    int rc;
    if (io_err || close_err || zrc != Z_STREAM_END) { if (!io_err) nx_err("zip: inflate failed (zlib code %d)", zrc); unlink(tmp); return -1; }
    rc = font_try_nca(fj, tmp);
    unlink(tmp);
    return rc;
}

static int font_scan_zip(FontJob *fj, const char *zip_path, const char *out_dir) {
    NxZip z; if (nx_zip_open(&z, zip_path)) return -1;
    int rc = 0;
    for (int i = 0; i < z.n && !rc && !font_all(fj); i++) {
        const NxZipEntry *e = &z.e[i];
        if (e->method == 0) rc = font_try_nca_fd(fj, z.fd, e->data_off, e->size);
        else if (e->method == 8) rc = font_try_zip_deflate(fj, z.fd, e->data_off, e->comp_size, out_dir);
        // else: an unsupported compression method (not stored, not deflate) — skip it rather than fail the whole scan over one odd member
    }
    nx_zip_close(&z);
    return rc;
}

int nximport_firmware_fonts(const char *firmware_dir, const char *out_dir) {
    if (!nx_keys.loaded) { nx_err("keys not loaded (nximport_keys)"); return -1; }
    FontJob fj = {0};
    struct stat st; if (stat(firmware_dir, &st)) { nx_err("cannot open %s: %s", firmware_dir, strerror(errno)); return -1; }
    if (!S_ISDIR(st.st_mode) && !S_ISREG(st.st_mode)) { nx_err("%s is neither a folder nor a file", firmware_dir); return -1; }
    if (nx_mkdir(out_dir, 0755) && errno != EEXIST) { nx_err("cannot create %s: %s", out_dir, strerror(errno)); return -1; }
    int rc = S_ISDIR(st.st_mode) ? font_scan(&fj, firmware_dir, 0) : font_scan_zip(&fj, firmware_dir, out_dir);
    if (rc) goto done;
    if (!fj.got_std || !fj.got_ext) {
        const char *why = !fj.got_std && fj.why[1][0] ? fj.why[1] : !fj.got_ext && fj.why[0][0] ? fj.why[0] : NULL;
        if (why) {
            nx_err("%s: cannot read %s from the firmware: %s. Check that your keys match this firmware.", firmware_dir,
                   !fj.got_std && fj.why[1][0] ? "FontStandard 0100000000000811" : "FontNintendoExtended 0100000000000810", why);
            rc = -1; goto done;
        }
        nx_err("%s: %s not found among %d NCAs (FontStandard 0100000000000811%s, FontNintendoExtended 0100000000000810%s)", firmware_dir,
               fj.found_ncas ? "the shared font archives were" : "no NCA files", fj.found_ncas, fj.got_std ? " ok" : " missing", fj.got_ext ? " ok" : " missing");
        rc = -1; goto done;
    }
    // Both required archives are readable. Only now may their output replace the installed files.
    static const char *names[6] = {"standard.ttf", "nintendo_ext.ttf", "cjk_sc.ttf", "cjk_sc_ext.ttf", "cjk_tc.ttf", "cjk_kr.ttf"};
    // Each font is written beside its name first and takes the name only once all are written: a full disk or a stop half-way leaves the installed fonts as they were
    for (int i = 0; i < 6 && !rc; i++) if (fj.data[i]) {
        char path[1208]; snprintf(path, sizeof path, "%s/%s.tmp", out_dir, names[i]);
        FILE *o = fopen(path, "wb");
        if (!o) { nx_err("cannot write %s: %s", path, strerror(errno)); rc = -1; break; }
        int written = fwrite(fj.data[i], 1, fj.size[i], o) == fj.size[i];
        int closed = fclose(o);
        if (!written || closed) { nx_err("write failed: %s", path); rc = -1; }
    }
    for (int i = 0; i < 6; i++) if (fj.data[i]) {
        char tmp[1208], path[1200]; snprintf(tmp, sizeof tmp, "%s/%s.tmp", out_dir, names[i]); snprintf(path, sizeof path, "%s/%s", out_dir, names[i]);
        if (rc) { remove(tmp); continue; }
        if (rename(tmp, path)) { nx_err("cannot replace %s: %s", path, strerror(errno)); remove(tmp); rc = -1; }
    }
    if (!rc) font_write_mii(&fj, out_dir);   // PEANUTS: MiiModel beside the fonts, when the firmware has it
done:
    for (int i = 0; i < 6; i++) free(fj.data[i]);
    for (int i = 0; i < fj.nmii; i++) free(fj.mii_data[i]);   // PEANUTS
    return rc;
}

// ---------------------------------------------------------------- .nup verify / extract
typedef struct NupView { int fd; uint8_t *toc; uint64_t toc_size, nd, nf, pool_off, pool_size, blob, fsize; uint32_t npk; uint64_t o_pk, o_dir, o_file, o_kids, nkids; } NupView;

static int nup_open(NupView *v, const char *path) {
    memset(v, 0, sizeof *v); v->fd = -1; uint8_t sb[0x80]; struct stat st;
    if ((v->fd = open(path, O_RDONLY | O_CLOEXEC)) < 0) { nx_err("cannot open %s: %s", path, strerror(errno)); return -1; }
    if (fstat(v->fd, &st) || nx_pread_all(v->fd, sb, 0x80, 0)) goto fail;
    if (memcmp(sb, "NUP\x01", 4)) NX_FAIL("%s: not a NUP file (magic)", path);
    uint64_t toc_off = u64(sb + 8); v->toc_size = u64(sb + 0x10); v->blob = u64(sb + 0x38); v->fsize = u64(sb + 0x40); v->npk = u32(sb + 0x48);
    if (toc_off != 0x80 || v->toc_size < 0x30 || v->toc_size > (1ull << 31) || v->blob % 16 || v->blob < toc_off + v->toc_size || v->fsize != (uint64_t)st.st_size) NX_FAIL("%s: superblock sizes do not match the file", path);
    if (u32(sb + 0x4C)) NX_FAIL("%s: unknown superblock flags", path);
    if (!(v->toc = malloc((size_t)v->toc_size))) NX_FAIL("out of memory");
    if (nx_pread_all(v->fd, v->toc, (size_t)v->toc_size, toc_off)) goto fail;
    { uint8_t h[32]; nx_sha256(v->toc, (size_t)v->toc_size, h); if (memcmp(h, sb + 0x18, 32)) NX_FAIL("%s: the TOC's SHA-256 does not match", path); }
    if (memcmp(v->toc, "TOC\x01", 4) || u32(v->toc + 4) != v->npk) NX_FAIL("%s: bad TOC header", path);
    v->nd = u64(v->toc + 8); v->nf = u64(v->toc + 0x10); v->pool_off = u64(v->toc + 0x18); v->pool_size = u64(v->toc + 0x20);
    v->o_pk = 0x30; v->o_dir = v->o_pk + 0x40ull * v->npk; v->o_file = v->o_dir + 0x18 * v->nd; v->o_kids = v->o_file + 0x50 * v->nf;
    if (v->nd > v->toc_size / 0x18 || v->nf > v->toc_size / 0x50 || v->o_kids > v->toc_size || v->pool_off < v->o_kids || v->pool_off > v->toc_size || v->pool_size > v->toc_size - v->pool_off) NX_FAIL("%s: TOC regions out of bounds", path);
    v->nkids = (v->pool_off - v->o_kids) / 4;
    return 0;
fail:
    if (v->fd >= 0) close(v->fd); free(v->toc); v->fd = -1; v->toc = NULL; return -1;
}
static void nup_close(NupView *v) { if (v->fd >= 0) close(v->fd); free(v->toc); }

typedef int (*EntryFn)(void *, const char *, unsigned long long, unsigned long long, int);
typedef struct Walk { NupView *v; EntryFn fn; void *user; int stopped; } Walk;

static int nup_walk(Walk *w, uint32_t dir, char *path, size_t plen, int depth) {
    NupView *v = w->v; if (depth > 64) { nx_err("directory tree too deep"); return -1; }
    const uint8_t *d = v->toc + v->o_dir + 0x18ull * dir; uint64_t first = u32(d + 8), cnt = u32(d + 12);
    if (first > v->nkids || cnt > v->nkids - first) { nx_err("a directory's children are out of range"); return -1; }
    for (uint64_t k = 0; k < cnt && !w->stopped; k++) {
        uint32_t id = u32(v->toc + v->o_kids + 4 * (first + k)); if (id >= v->nd + v->nf) { nx_err("a child id is out of range"); return -1; }
        const uint8_t *r = id < v->nd ? v->toc + v->o_dir + 0x18ull * id : v->toc + v->o_file + 0x50ull * (id - v->nd);
        uint32_t no = u32(r), nl = u32(r + 4); if ((uint64_t)no + nl > v->pool_size || nl > 0x300) { nx_err("a name is out of range"); return -1; }
        if (plen + nl + 2 > 2048) { nx_err("a path is too long"); return -1; }
        size_t np = plen; if (np) path[np++] = '/'; memcpy(path + np, v->toc + v->pool_off + no, nl); np += nl; path[np] = 0;
        if (id < v->nd) { if (nup_walk(w, id, path, np, depth + 1)) return -1; }
        else {
            uint64_t size = u64(r + 8), xo = u64(r + 0x10), xs = u64(r + 0x18); int codec = r[0x40];
            if (codec > 1) { nx_err("%s: unknown codec %d", path, codec); return -1; }
            if (size && (xo < v->blob || xo % 16 || xo > v->fsize || xs > v->fsize - xo)) { nx_err("%s: extent out of bounds", path); return -1; }
            if (!codec && xs != size) { nx_err("%s: a raw file's extent is not its size", path); return -1; }
            if (w->fn && w->fn(w->user, path, size, xo, codec)) w->stopped = 1;
        }
        path[plen] = 0;
    }
    return 0;
}

int nximport_verify_nup(const char *nup, int (*entry)(void *, const char *, unsigned long long, unsigned long long, int), void *user) {
    NupView v; if (nup_open(&v, nup)) return -1;
    Walk w = { &v, entry, user, 0 }; char path[2100]; int rc = 0;
    for (uint32_t p = 0; p < v.npk && !rc && !w.stopped; p++) {
        const uint8_t *r = v.toc + v.o_pk + 0x40ull * p; int kind = r[0x18]; uint32_t root = u32(r + 8);
        if (kind > 2) { nx_err("package %u: unknown kind %d", p, kind); rc = -1; break; }
        if (root >= v.nd) { nx_err("package %u: root out of range", p); rc = -1; break; }
        if (kind == 0) strcpy(path, "base"); else if (kind == 1) snprintf(path, sizeof path, "update/%u", u32(r + 0x14)); else snprintf(path, sizeof path, "addon/%016llx", (unsigned long long)u64(r));
        rc = nup_walk(&w, root, path, strlen(path), 0);
    }
    nup_close(&v); return rc;
}

typedef struct Ext { const char *want; const char *out; int done, rc; const char *nup; } Ext;
static int ext_cb(void *u, const char *path, unsigned long long size, unsigned long long off, int codec) {
    Ext *x = u; if (strcmp(path, x->want)) return 0; x->done = 1;
    if (codec) { nx_err("%s is compressed (codec %d): not extracted", path, codec); x->rc = -1; return 1; }
    int in = open(x->nup, O_RDONLY | O_CLOEXEC), out = open(x->out, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644); uint8_t *buf = malloc(NX_CHUNK);
    if (in < 0 || out < 0 || !buf) { nx_err("cannot open files for extraction"); x->rc = -1; }
    else { x->rc = 0; for (unsigned long long at = 0; at < size && !x->rc; ) { size_t k = size - at > NX_CHUNK ? NX_CHUNK : (size_t)(size - at); if (nx_pread_all(in, buf, k, off + at) || write_all(out, buf, k, at)) x->rc = -1; at += k; } }
    if (in >= 0) close(in); if (out >= 0) close(out); free(buf); return 1;
}
int nximport_nup_extract(const char *nup, const char *path, const char *out_file) {
    Ext x = { path, out_file, 0, -1, nup };
    if (nximport_verify_nup(nup, ext_cb, &x)) return -1;
    if (!x.done) { nx_err("%s: no entry %s", nup, path); return -1; }
    return x.rc;
}
