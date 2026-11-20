// nup_meta_test: the meta blocks the importer's .nup writer puts in (SPEC §5; nximport.c pkg_meta, issue #158), on any PC with a C compiler and no game data:
// the dumps are written here (nsp_fixture.h) with keys made up for the test, and every record is compared with the bytes the fixture put into the dumps.
//   nup_meta_test         the files go to a scratch folder under $TMPDIR (else /tmp), removed at the end
//   nup_meta_test <dir>   the dumps and the .nups stay in dir (peanuts/tests/test_nup_icon.py reads them with the other readers: dm/tools/nup.py, Nup.java)
// What is checked:
//   a base with its control data, its update with its own, an add-on: the base's block has main.npdm, the NACP, the English icon, the display version, the title
//     and the publisher; the update's main.npdm, its NACP and version; the add-on none; the blocks lie behind the last extent and the file ends with them
//   a base without control data: main.npdm only, and the .nup is made as before
//   a NACP whose only title is French, without a display version, beside an "icon" that is no image: the French title, no icon, no version
//   an update without its base: the first package carries the application's records
#define _GNU_SOURCE
#include <ftw.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "nximport.h"
#include "nx_internal.h"
#include "nsp_fixture.h"

static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  FAIL "); } else printf("  ok   "); printf(__VA_ARGS__); printf("\n"); } while (0)

enum { FX_HASH_LEN = 0x1000 };
static const uint64_t TID = 0x0100BBBB0000C000ull;

// ---- the dumps
static uint8_t *nacp(int lang, const char *title, const char *publisher, const char *version) {   // a control.nacp: one language's title and publisher, the display version
    uint8_t *b = calloc(1, 0x4000); if (!b) abort();
    if (title) { strcpy((char *)b + 0x300 * lang, title); strcpy((char *)b + 0x300 * lang + 0x200, publisher); }
    if (version) strcpy((char *)b + 0x3060, version);
    return b;
}
static uint8_t *jpeg(size_t n) { uint8_t *b = fx_bytes(n); b[0] = 0xFF; b[1] = 0xD8; b[2] = 0xFF; b[3] = 0xE0; b[n - 2] = 0xFF; b[n - 1] = 0xD9; return b; }   // a JPEG by its first bytes

// A title's dump: a program NCA with an ExeFS (main, main.npdm) and a small plain RomFS (an update here carries a whole RomFS of its own, no patch), and its
// control NCA when ctl is not NULL
static void dump(const FxKeys *k, const char *path, uint64_t tid, uint64_t app, int kind, uint32_t ver, const FxCtl *ctl) {
    FxNca n; FxFile f[2]; FxBuf img = {0}; uint8_t build[32];
    memset(&n, 0, sizeof n); memset(f, 0, sizeof f);
    strcpy(f[0].path, "data/a.bin"); f[0].size = 3000; f[0].data = fx_bytes(3000); f[0].base = -1;
    strcpy(f[1].path, "b.txt"); f[1].size = 5; f[1].data = (const uint8_t *)"hello"; f[1].base = -1;
    fx_romfs(f, 2, &img);
    fx_exefs(&n.sec[0], build);
    FxSec *S = &n.sec[1]; S->present = 1; S->fs_type = NX_FS_ROMFS; S->crypt = 3; S->data_off = FX_HASH_LEN;
    { uint8_t *hash = fx_bytes(FX_HASH_LEN); fx_put(&S->body, hash, FX_HASH_LEN); free(hash); }
    fx_put(&S->body, img.p, img.n);
    fx_title_ctl(k, path, tid, app, kind, ver, &n, 0, 0, 0, ctl);
    free(img.p); free((void *)f[0].data); free(n.sec[0].body.p); free(n.sec[1].body.p);
}

// ---- the .nup as written
typedef struct View { uint8_t *b; size_t n; uint64_t toc_size, fsize, ext_end; uint32_t npk; } View;
static int view(const char *path, View *v) {
    memset(v, 0, sizeof *v); FILE *f = fopen(path, "rb"); if (!f) return -1;
    fseek(f, 0, SEEK_END); v->n = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    v->b = malloc(v->n); if (!v->b || fread(v->b, 1, v->n, f) != v->n) { fclose(f); return -1; } fclose(f);
    memcpy(&v->toc_size, v->b + 0x10, 8); memcpy(&v->fsize, v->b + 0x40, 8); memcpy(&v->npk, v->b + 0x48, 4);
    const uint8_t *toc = v->b + 0x80; uint64_t nd, nf; memcpy(&nd, toc + 8, 8); memcpy(&nf, toc + 0x10, 8);
    for (uint64_t i = 0; i < nf; i++) {   // where the last extent ends
        const uint8_t *r = toc + 0x30 + 0x40ull * v->npk + 0x18 * nd + 0x50 * i; uint64_t off, stored; memcpy(&off, r + 0x10, 8); memcpy(&stored, r + 0x18, 8);
        if (stored && off + stored > v->ext_end) v->ext_end = off + stored;
    }
    return 0;
}
typedef struct Pkg { int kind; uint32_t ver; uint64_t moff; uint32_t msize; } Pkg;
static Pkg pkg(const View *v, uint32_t i) {
    const uint8_t *r = v->b + 0x80 + 0x30 + 0x40 * i; Pkg p; p.kind = r[0x18]; memcpy(&p.ver, r + 0x14, 4); memcpy(&p.moff, r + 0x20, 8); memcpy(&p.msize, r + 0x28, 4); return p;
}
static int pkg_of(const View *v, int kind) { for (uint32_t i = 0; i < v->npk; i++) if (pkg(v, i).kind == kind) return (int)i; return -1; }
typedef struct Rec { unsigned type; const uint8_t *p; uint32_t n; } Rec;
// The block's records in order: their count, -1 when one is malformed (a reserved field not zero, or a record past the block's end)
static int recs(const View *v, const Pkg *p, Rec *out, int max) {
    if (!p->msize || p->moff + p->msize > v->n) return p->msize ? -1 : 0;
    const uint8_t *b = v->b + p->moff; int n = 0;
    for (uint32_t o = 0; o < p->msize; ) {
        if (p->msize - o < 8 || n == max) return -1;
        uint16_t t, z; uint32_t len; memcpy(&t, b + o, 2); memcpy(&z, b + o + 2, 2); memcpy(&len, b + o + 4, 4);
        if (z || len > p->msize - o - 8) return -1;
        out[n].type = t; out[n].p = b + o + 8; out[n++].n = len; o += 8 + len;
    }
    return n;
}
static const Rec *rec(const Rec *r, int n, unsigned type) { for (int i = 0; i < n; i++) if (r[i].type == type) return &r[i]; return NULL; }
static int types_are(const Rec *r, int n, const char *want) {   // the types in order, written as digits ("1246")
    if ((size_t)n != strlen(want)) return 0;
    for (int i = 0; i < n; i++) if (r[i].type != (unsigned)(want[i] - '0')) return 0;
    return 1;
}
static int text_is(const Rec *r, const char *s) { return r && r->n == strlen(s) && !memcmp(r->p, s, r->n); }
static int bytes_are(const Rec *r, const uint8_t *p, size_t n) { return r && r->n == n && !memcmp(r->p, p, n); }
// a file of the .nup by its path (base/exefs/main.npdm, update/<v>/exefs/main.npdm), extracted by the importer itself
static uint8_t *extract(const char *nup, const char *path, const char *dir, size_t *n) {
    char out[700]; snprintf(out, sizeof out, "%s/extracted.bin", dir); *n = 0;
    if (nximport_nup_extract(nup, path, out)) return NULL;
    FILE *f = fopen(out, "rb"); if (!f) return NULL; fseek(f, 0, SEEK_END); long l = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc((size_t)l + 1); if (!b || fread(b, 1, (size_t)l, f) != (size_t)l) { fclose(f); free(b); return NULL; } fclose(f); remove(out); *n = (size_t)l; return b;
}
static int npdm_matches(const char *nup, const char *path, const char *dir, const Rec *r) {
    size_t n; uint8_t *b = extract(nup, path, dir, &n); int ok = b && n && bytes_are(r, b, n); free(b); return ok;
}
static void write_file(const char *dir, const char *name, const uint8_t *p, size_t n) { char path[700]; snprintf(path, sizeof path, "%s/%s", dir, name); fx_write(path, p, n); }

static int rm_cb(const char *p, const struct stat *st, int flag, struct FTW *f) { (void)st; (void)flag; (void)f; return remove(p); }
int main(int argc, char **argv) {
    char dir[500], keys[600], base[600], upd[600], nup[700]; const char *td = getenv("TMPDIR"); FxKeys k; FxAddon dlc; View v; Rec r[16]; int n;
    int keep = argc > 1;
    if (keep) snprintf(dir, sizeof dir, "%s", argv[1]);
    else { snprintf(dir, sizeof dir, "%s/nup_meta_test-XXXXXX", td && *td ? td : "/tmp"); if (!mkdtemp(dir)) { perror(dir); return 2; } }
    snprintf(keys, sizeof keys, "%s/prod.keys", dir);
    fx_keys_make(&k, keys);
    if (nximport_keys(keys)) { printf("the fixture's keys do not load: %s\n", nximport_last_error()); return 2; }
    char tid[17]; snprintf(tid, sizeof tid, "%016llx", (unsigned long long)TID);

    // ---- a base with its control data, its update with its own, an add-on
    printf("a base, its update and an add-on, with control data\n");
    uint8_t *na = nacp(0, "Fixture Quest", "Fixture Works", "1.0.0"), *nb = nacp(0, "Fixture Quest", "Fixture Works", "1.2.0");
    uint8_t *icon_en = jpeg(3000), *icon_ja = jpeg(2500), *icon_up = jpeg(2800);
    FxCtl ca = { na, 0x4000, { { "icon_AmericanEnglish.dat", icon_en, 3000 }, { "icon_Japanese.dat", icon_ja, 2500 } } };
    FxCtl cu = { nb, 0x4000, { { "icon_AmericanEnglish.dat", icon_up, 2800 }, { NULL, NULL, 0 } } };
    snprintf(base, sizeof base, "%s/quest-base.nsp", dir); snprintf(upd, sizeof upd, "%s/quest-update.nsp", dir);
    dump(&k, base, TID, TID, 0, 0, &ca);
    dump(&k, upd, TID + 0x800, TID, 1, 0x20000, &cu);
    fx_addon(&k, dir, "quest-dlc", TID, 1, 0, 3, &dlc);
    write_file(dir, "quest-icon.jpg", icon_en, 3000);
    { const char *in[3] = { base, upd, dlc.path }; snprintf(nup, sizeof nup, "%s/quest.nup", dir);
      CHECK(!nximport_write_nup(nup, in, 3, tid, NULL, NULL), "the importer makes the .nup (%s)", nximport_last_error()); }
    CHECK(!nximport_verify_nup(nup, NULL, NULL), "and it verifies (%s)", nximport_last_error());
    CHECK(!view(nup, &v) && v.fsize == v.n && v.npk == 3, "the superblock's file size is the file's (%llu), three packages", (unsigned long long)v.fsize);
    { int b = pkg_of(&v, 0), u = pkg_of(&v, 1), a = pkg_of(&v, 2); Pkg pb = pkg(&v, (uint32_t)b), pu = pkg(&v, (uint32_t)u), pa = pkg(&v, (uint32_t)a);
      n = recs(&v, &pb, r, 16);
      CHECK(types_are(r, n, "123456"), "the base's block: main.npdm, the NACP, the icon, the version, the title and the publisher, in that order (%d records)", n);
      CHECK(npdm_matches(nup, "base/exefs/main.npdm", dir, rec(r, n, 1)), "its main.npdm is the ExeFS's");
      CHECK(bytes_are(rec(r, n, 2), na, 0x4000), "its NACP is the control.nacp as it is");
      CHECK(bytes_are(rec(r, n, 3), icon_en, 3000), "its icon is icon_AmericanEnglish.dat's JPEG, not the Japanese one");
      CHECK(text_is(rec(r, n, 4), "1.0.0") && text_is(rec(r, n, 5), "Fixture Quest") && text_is(rec(r, n, 6), "Fixture Works"), "its version, title and publisher are the NACP's");
      n = recs(&v, &pu, r, 16);
      CHECK(types_are(r, n, "124"), "the update's block: main.npdm, its NACP and its version, no icon, title or publisher (%d records)", n);
      CHECK(npdm_matches(nup, "update/131072/exefs/main.npdm", dir, rec(r, n, 1)) && bytes_are(rec(r, n, 2), nb, 0x4000) && text_is(rec(r, n, 4), "1.2.0"),
            "they are the update's own: its main.npdm, its NACP, version 1.2.0");
      CHECK(!pa.moff && !pa.msize, "the add-on has no meta block");
      uint64_t lo = UINT64_MAX, hi = 0, sum = pb.msize + pu.msize;
      for (int i = 0; i < 2; i++) { const Pkg *p = i ? &pu : &pb; if (p->moff < lo) lo = p->moff; if (p->moff + p->msize > hi) hi = p->moff + p->msize; }
      CHECK(lo >= v.ext_end && lo >= 0x80 + v.toc_size && hi == v.fsize && hi - lo == sum, "the blocks lie one after the other behind the last extent, and the file ends with them"); }
    { size_t len; uint8_t *b = extract(nup, "update/131072/romfs/b.txt", dir, &len);
      CHECK(b && len == 5 && !memcmp(b, "hello", 5), "a file of the RomFS reads as before"); free(b); }
    free(v.b);

    // ---- a base without control data
    printf("a base without control data\n");
    snprintf(base, sizeof base, "%s/bare-base.nsp", dir); dump(&k, base, TID + 0x10000, TID + 0x10000, 0, 0, NULL);
    { char t2[17]; const char *in[1] = { base }; snprintf(t2, sizeof t2, "%016llx", (unsigned long long)(TID + 0x10000)); snprintf(nup, sizeof nup, "%s/bare.nup", dir);
      CHECK(!nximport_write_nup(nup, in, 1, t2, NULL, NULL), "the .nup is made all the same (%s)", nximport_last_error()); }
    CHECK(!view(nup, &v) && v.fsize == v.n && !nximport_verify_nup(nup, NULL, NULL), "and verifies");
    { Pkg p = pkg(&v, 0); n = recs(&v, &p, r, 16); CHECK(types_are(r, n, "1") && npdm_matches(nup, "base/exefs/main.npdm", dir, rec(r, n, 1)), "its block holds main.npdm alone"); }
    free(v.b);

    // ---- a title only in French, no display version, an "icon" that is no image
    printf("a NACP with a French title only, no version, an icon that is no image\n");
    uint8_t *nf = nacp(3, "Jeu d'essai", "Studio d'essai", NULL);
    FxCtl cf = { nf, 0x4000, { { "icon_French.dat", (const uint8_t *)"not an image, only text", 23 }, { NULL, NULL, 0 } } };
    snprintf(base, sizeof base, "%s/french-base.nsp", dir); dump(&k, base, TID + 0x20000, TID + 0x20000, 0, 0, &cf);
    { char t2[17]; const char *in[1] = { base }; snprintf(t2, sizeof t2, "%016llx", (unsigned long long)(TID + 0x20000)); snprintf(nup, sizeof nup, "%s/french.nup", dir);
      CHECK(!nximport_write_nup(nup, in, 1, t2, NULL, NULL), "the .nup is made (%s)", nximport_last_error()); }
    CHECK(!view(nup, &v), "it reads");
    { Pkg p = pkg(&v, 0); n = recs(&v, &p, r, 16);
      CHECK(types_are(r, n, "1256") && text_is(rec(r, n, 5), "Jeu d'essai") && text_is(rec(r, n, 6), "Studio d'essai"), "main.npdm, the NACP, and the French title and publisher; no icon, no version"); }
    free(v.b);

    // ---- an update without its base
    printf("an update without its base\n");
    { const char *in[1] = { upd }; snprintf(nup, sizeof nup, "%s/update-only.nup", dir);
      CHECK(!nximport_write_nup(nup, in, 1, tid, NULL, NULL), "the .nup of the update alone is made (%s)", nximport_last_error()); }
    CHECK(!view(nup, &v) && v.npk == 1 && pkg(&v, 0).kind == 1, "one package, the update");
    { Pkg p = pkg(&v, 0); n = recs(&v, &p, r, 16);
      CHECK(types_are(r, n, "123456") && bytes_are(rec(r, n, 3), icon_up, 2800) && text_is(rec(r, n, 4), "1.2.0") && text_is(rec(r, n, 5), "Fixture Quest"),
            "it carries the application's records: the update's icon, version and title"); }
    free(v.b);

    free(na); free(nb); free(nf); free(icon_en); free(icon_ja); free(icon_up);
    for (int i = 0; i < dlc.nf; i++) free((void *)dlc.f[i].data); free(dlc.f);
    if (!keep) nftw(dir, rm_cb, 16, FTW_DEPTH | FTW_PHYS);
    printf("%s: %d checks, %d failed\n", fails ? "FAIL" : "PASS", checks, fails);
    return fails != 0;
}
