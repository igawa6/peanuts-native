// ncz_test: the importer on compressed dumps (.nsz, .xcz) with no game data. The fixture's dumps (nsp_fixture.h: made-up keys, a base with a plain or an
// LZ4-compressed RomFS, its BKTR update, an add-on with a ticket, a base on a card) are written again with NCZs (ncz_fixture.h), and every .nup made from
// them must be the one the uncompressed dumps give, byte for byte: solid and block, small and exact last blocks, a first range past 0x4000, an XCZ,
// compressed and plain files mixed, really compressed frames where libzstd is there. Then damaged and cut-off files, a stream asking for too large a
// window, keys that are not the ticket's, and Stop midway: each fails with its reason, writes no .nup and leaves no temporary file.
#define _GNU_SOURCE
#include "nximport.h"
#include "ncz_fixture.h"
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  FAIL "); } else printf("  ok   "); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

static const uint64_t TID = 0x0100BBBB0000C000ull, TID_LZ4 = 0x0100BBBB0000D000ull, TID_CARD = 0x0100BBBB0000E000ull;
static char dir[500];

static uint8_t *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long l = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc((size_t)l + 1); if (!b || fread(b, 1, (size_t)l, f) != (size_t)l) { free(b); fclose(f); return NULL; }
    fclose(f); *n = (size_t)l; return b;
}
static int same_file(const char *a, const char *b) {
    size_t na = 0, nb = 0; uint8_t *x = slurp(a, &na), *y = slurp(b, &nb); int same = x && y && na == nb && !memcmp(x, y, na);
    free(x); free(y); return same;
}
static int exists(const char *p) { struct stat st; return !stat(p, &st); }
static void copy_file(const char *from, const char *to) { size_t n; uint8_t *b = slurp(from, &n); if (!b) abort(); fx_write(to, b, n); free(b); }
static void patch(const char *p, uint64_t at, const void *bytes, size_t n) {   // bytes written over a file's
    FILE *f = fopen(p, "r+b"); if (!f || fseek(f, (long)at, SEEK_SET) || fwrite(bytes, 1, n, f) != n || fclose(f)) abort();
}
static uint8_t byte_at(const char *p, uint64_t at) { size_t n; uint8_t *b = slurp(p, &n), v; if (!b || at >= n) abort(); v = b[at]; free(b); return v; }

// ---- an import, its progress
typedef struct Prog { int calls, back, stop; char stop_on[24], first_prep[300]; long long prep_total, write_total, last_prep, last_write; } Prog;
static void prog(void *u, const char *step, long long done, long long total) {
    Prog *p = u; p->calls++;
    int prep = !strncmp(step, "unpack/", 7) || !strncmp(step, "check/", 6);
    if (prep) { if (!p->first_prep[0]) snprintf(p->first_prep, sizeof p->first_prep, "%s", step); if (done < p->last_prep) p->back++; p->last_prep = done; p->prep_total = total; }
    else if (strcmp(step, "probe")) { if (done < p->last_write) p->back++; p->last_write = done; p->write_total = total; }
    if (p->stop_on[0] && !p->stop && !strncmp(step, p->stop_on, strlen(p->stop_on)) && done > 0) { p->stop = 1; nximport_cancel(1); }
}
static int run(const char *out, const char **in, int n, uint64_t tid, Prog *p) {
    char t[20]; snprintf(t, sizeof t, "%016llx", (unsigned long long)tid);
    nximport_cancel(0); unlink(out);
    return nximport_write_nup(out, in, n, t, prog, p);
}
static int no_temp(const char *out) {   // the hidden folder beside the .nup is gone (its files were removed with it)
    char t[800]; const char *sl = strrchr(out, '/'); snprintf(t, sizeof t, "%.*s.%s.unpack", (int)(sl - out + 1), out, sl + 1);
    return !exists(t);
}
static void same(const char *what, const char *ref, const char **in, int n, uint64_t tid) {
    char out[700]; Prog p; memset(&p, 0, sizeof p); snprintf(out, sizeof out, "%s/case.nup", dir);
    int rc = run(out, in, n, tid, &p);
    CHECK(!rc && same_file(out, ref), "%s: the .nup is the uncompressed dumps' byte for byte%s%s", what, rc ? ": " : "", rc ? nximport_last_error() : "");
    CHECK(no_temp(out) && !p.back && (!p.prep_total || p.last_prep == p.prep_total) && p.last_write == p.write_total,
          "  nothing left beside it, the progress only went forward and ended at its totals (%lld unpacked or checked, %lld written)", p.prep_total, p.write_total);
}
static void refused(const char *what, const char *expect, const char **in, int n, uint64_t tid) {
    char out[700]; Prog p; memset(&p, 0, sizeof p); snprintf(out, sizeof out, "%s/bad.nup", dir);
    int rc = run(out, in, n, tid, &p);
    CHECK(rc && strstr(nximport_last_error(), expect) && !exists(out) && no_temp(out), "%s: refused (%s), no .nup and nothing left beside it", what, rc ? nximport_last_error() : "it imported");
}

// ---- a damaged copy of a compressed dump: its NCZ's entry located, then the change
typedef struct Ncz { uint64_t off, size, sec, data; uint32_t nsec; } Ncz;
static Ncz locate(const char *p) {
    Ncz z; memset(&z, 0, sizeof z);
    if (!nz_find(p, ".ncz", &z.off, &z.size)) abort();
    size_t n; uint8_t *b = slurp(p, &n); if (!b) abort();
    memcpy(&z.nsec, b + z.off + 0x4008, 4); z.sec = z.off + 0x4010; z.data = z.sec + 0x40ull * z.nsec;
    free(b); return z;
}
static void damaged(const char *from, const char *to) { copy_file(from, to); }

static int rm_tree(const char *p) {   // the test's folder
    DIR *d = opendir(p); if (!d) return unlink(p);
    struct dirent *e; char q[1200];
    while ((e = readdir(d))) { if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue; snprintf(q, sizeof q, "%s/%s", p, e->d_name); rm_tree(q); }
    closedir(d); return rmdir(p);
}

int main(void) {
    const char *td = getenv("TMPDIR"); char keys[600], ref[700], ref_lz4[700], ref_card[700], p[64][700]; FxKeys k; FxGame g, gl, gc; FxAddon a;
    snprintf(dir, sizeof dir, "%s/ncz_test-XXXXXX", td && *td ? td : "/tmp");
    if (!mkdtemp(dir)) { perror(dir); return 2; }
    snprintf(keys, sizeof keys, "%s/prod.keys", dir); fx_keys_make(&k, keys);
    if (nximport_keys(keys)) { printf("the fixture's keys do not load: %s\n", nximport_last_error()); return 2; }
    fx_game(&k, dir, "plain", TID, 0, 0, &g); fx_game(&k, dir, "lz4", TID_LZ4, 1, 0, &gl); fx_game(&k, dir, "card", TID_CARD, 0, 1, &gc);
    fx_addon(&k, dir, "dlc", TID, 1, FX_TIK_COMMON, 3, &a);
    printf("1 the uncompressed dumps: the reference .nup files\n");
    snprintf(ref, sizeof ref, "%s/ref.nup", dir); snprintf(ref_lz4, sizeof ref_lz4, "%s/ref-lz4.nup", dir); snprintf(ref_card, sizeof ref_card, "%s/ref-card.nup", dir);
    { const char *in[] = { g.base, g.upd, a.path }; Prog q; memset(&q, 0, sizeof q); CHECK(!run(ref, in, 3, TID, &q), "base, update and add-on (%s)", nximport_last_error()); }
    { const char *in[] = { gl.base, gl.upd }; Prog q; memset(&q, 0, sizeof q); CHECK(!run(ref_lz4, in, 2, TID_LZ4, &q), "a compressed base and its update (%s)", nximport_last_error()); }
    { const char *in[] = { gc.base, gc.upd }; Prog q; memset(&q, 0, sizeof q); CHECK(!run(ref_card, in, 2, TID_CARD, &q), "a base on a card and its update (%s)", nximport_last_error()); }

    printf("2 written again as NSZ and XCZ%s\n", zl_ok() ? " (and really compressed with the system's libzstd)" : " (no libzstd here: raw and RLE frames only)");
    enum { B_SOLID, B_B14, B_B9, B_B20, B_GAP, B_GAPB, U_SOLID, U_B9, U_B14, D_B16, D_SOLID, C_B20, C_SOLID, L_SOLID, L_B14, U_W28, Z_SOLID, Z_B16, Z_LB12, Z_DB12, NP };
    struct { int idx; const char *src, *name; NzOpt o; } conv[] = {
        { B_SOLID, g.base, "base-solid.nsz", { .bexp = 0 } }, { B_B14, g.base, "base-b14.nsz", { .bexp = 14 } }, { B_B9, g.base, "base-b9.nsz", { .bexp = 9 } },
        { B_B20, g.base, "base-b20.nsz", { .bexp = 20 } }, { B_GAP, g.base, "base-gap.nsz", { .gap = 0x1000 } }, { B_GAPB, g.base, "base-gap-b14.nsz", { .bexp = 14, .gap = 0x2400 } },
        { U_SOLID, g.upd, "update-solid.nsz", { .bexp = 0 } }, { U_B9, g.upd, "update-b9.nsz", { .bexp = 9 } }, { U_B14, g.upd, "update-b14.nsz", { .bexp = 14 } },
        { D_B16, a.path, "dlc-b16.nsz", { .bexp = 16 } }, { D_SOLID, a.path, "dlc-solid.nsz", { .bexp = 0 } },
        { C_B20, gc.base, "card-b20.xcz", { .bexp = 20 } }, { C_SOLID, gc.base, "card-solid.xcz", { .bexp = 0 } },
        { L_SOLID, gl.base, "lz4-solid.nsz", { .bexp = 0 } }, { L_B14, gl.upd, "lz4-update-b14.nsz", { .bexp = 14 } },
        { U_W28, g.base, "base-w28.nsz", { .wlog = 28 } },
        { Z_SOLID, g.base, "z-base-solid.nsz", { .real = 1, .workers = 2 } }, { Z_B16, g.upd, "z-update-b16.nsz", { .bexp = 16, .real = 1 } },
        { Z_LB12, gl.base, "z-lz4-b12.nsz", { .bexp = 12, .real = 1 } }, { Z_DB12, a.path, "z-dlc-b12.nsz", { .bexp = 12, .real = 1 } },
    };
    int made = 0, all = (int)(sizeof conv / sizeof conv[0]);
    for (int i = 0; i < all; i++) { snprintf(p[conv[i].idx], sizeof p[0], "%s/%s", dir, conv[i].name); made += !nz_convert(conv[i].src, p[conv[i].idx], &conv[i].o); }
    CHECK(made == all, "%d of %d dumps written with NCZs", made, all);
    { Ncz z = locate(p[B_SOLID]); uint64_t first; size_t n; uint8_t *b = slurp(p[B_SOLID], &n); memcpy(&first, b + z.sec, 8); free(b);
      CHECK(first < 0x4000, "the fixture's NCAs begin their first section below 0x4000 (%#llx): those bytes are only in the header copy", (unsigned long long)first); }

    printf("3 the probe: a title's real size, and what unpacking or checking it takes\n");
    { NxImportInfo nsp, sol, blk; int n1 = 0, n2 = 0, n3 = 0; uint64_t off, nca_size, z_off, z_size;
      int ok = !nximport_probe(g.base, &nsp, 1, &n1) && !nximport_probe(p[B_SOLID], &sol, 1, &n2) && !nximport_probe(p[C_B20], &blk, 1, &n3) && n1 == 1 && n2 == 1 && n3 == 1;
      ok = ok && nz_find(g.base, ".nca", &off, &nca_size) && nz_find(p[B_SOLID], ".ncz", &z_off, &z_size);
      CHECK(ok && nsp.size == nca_size && !nsp.unpack && !nsp.check, "an NSP: its size is its program NCA's (%llu bytes), nothing to unpack or check", ok ? nsp.size : 0);
      CHECK(ok && sol.size == nca_size && sol.unpack == nca_size - 0x4000 && !sol.check && z_size < sol.size + 0x8000,
            "a solid NSZ: the same size, its body to unpack (%llu bytes; the NCZ is %llu)", ok ? sol.unpack : 0, ok ? (unsigned long long)z_size : 0);
      CHECK(ok && blk.check > 0 && !blk.unpack, "an XCZ in blocks: %llu bytes to check, none to unpack", ok ? blk.check : 0); }

    printf("4 each the same .nup as the uncompressed dumps\n");
    { const char *in[] = { p[B_SOLID], g.upd, a.path }; same("a solid NSZ base, the update and the add-on as NSPs", ref, in, 3, TID); }
    { const char *in[] = { g.base, p[U_SOLID], p[D_B16] }; same("an NSP base, a solid NSZ update (BKTR) over it, a block NSZ add-on", ref, in, 3, TID); }
    { const char *in[] = { p[B_B14], p[U_B9], p[D_SOLID] }; same("everything compressed: 16 KiB blocks, 512-byte blocks (an exact last block), solid", ref, in, 3, TID); }
    { const char *in[] = { p[B_B20], p[U_B14], a.path }; same("1 MiB blocks (one block holds the whole body)", ref, in, 3, TID); }
    { const char *in[] = { p[B_GAP], g.upd, a.path }; same("a first range 4 KiB after 0x4000, solid (the bytes before it plain)", ref, in, 3, TID); }
    { const char *in[] = { p[B_GAPB], p[U_SOLID], a.path }; same("a first range past 0x4000 in blocks", ref, in, 3, TID); }
    { const char *in[] = { p[C_B20], gc.upd }; same("an XCZ in blocks (its secure partition's NCZ) with an NSP update", ref_card, in, 2, TID_CARD); }
    { const char *in[] = { p[C_SOLID], gc.upd }; same("a solid XCZ", ref_card, in, 2, TID_CARD); }
    { const char *in[] = { p[L_SOLID], p[L_B14] }; same("a base with an LZ4-compressed RomFS as a solid NSZ, its update in blocks", ref_lz4, in, 2, TID_LZ4); }
    setenv("NXIMPORT_THREADS", "1", 1);
    { const char *in[] = { p[B_B9], p[U_B9], p[D_B16] }; same("one reader thread", ref, in, 3, TID); }
    setenv("NXIMPORT_THREADS", "8", 1);
    { const char *in[] = { p[B_B9], p[U_B9], p[D_B16] }; same("eight reader threads", ref, in, 3, TID); }
    unsetenv("NXIMPORT_THREADS");
    if (zl_ok()) {
        { const char *in[] = { p[Z_SOLID], p[Z_B16], p[Z_DB12] }; same("really compressed: a solid stream made by two zstd threads, 64 KiB and 4 KiB blocks", ref, in, 3, TID); }
        { const char *in[] = { p[Z_LB12], gl.upd }; same("really compressed, the LZ4 base in 4 KiB blocks", ref_lz4, in, 2, TID_LZ4); }
    } else printf("  (skipped: really compressed frames, no libzstd.so.1 on this system)\n");
    { char tmpd[700]; snprintf(tmpd, sizeof tmpd, "%s/elsewhere", dir); mkdir(tmpd, 0700); setenv("NXIMPORT_TMPDIR", tmpd, 1);
      const char *in[] = { p[B_SOLID], p[U_SOLID], a.path }; same("unpacked into NXIMPORT_TMPDIR", ref, in, 3, TID);
      DIR *d = opendir(tmpd); int left = 0; struct dirent *e; while (d && (e = readdir(d))) left += e->d_name[0] != '.'; if (d) closedir(d);
      CHECK(exists(tmpd) && !left, "  that folder stays, empty"); unsetenv("NXIMPORT_TMPDIR"); }
    { char out[700]; Prog q; memset(&q, 0, sizeof q); snprintf(out, sizeof out, "%s/case.nup", dir); const char *in[] = { p[B_SOLID], p[U_B14], a.path };
      run(out, in, 3, TID, &q);
      CHECK(!strcmp(q.first_prep, "unpack/base-solid.nsz") && q.prep_total > 0, "the progress names the step and the file (\"%s\", %lld bytes before the write)", q.first_prep, q.prep_total); }

    printf("5 damaged, cut off or not readable here: refused before anything is written\n");
    char bad[700]; snprintf(bad, sizeof bad, "%s/bad.nsz", dir);
    #define IN_BAD const char *in[] = { bad, g.upd, a.path }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); uint64_t at = z.data + (z.size - (z.data - z.off)) / 2; uint8_t v = byte_at(bad, at) ^ 0x40; patch(bad, at, &v, 1);
      IN_BAD; refused("one byte changed inside a solid stream's data", "does not match its checksum", in, 3, TID); }
    { damaged(p[B_B14], bad); Ncz z = locate(bad); uint32_t nb; size_t n; uint8_t *b = slurp(bad, &n); memcpy(&nb, b + z.data + 12, 4);
      uint64_t blocks = z.data + 0x18 + 4ull * nb, at = blocks + 3 * (1ull << 14) + 100; free(b); uint8_t v = byte_at(bad, at) ^ 1; patch(bad, at, &v, 1);
      IN_BAD; refused("one byte changed in a stored block", "does not match its checksum", in, 3, TID); }
    { damaged(p[B_B14], bad); Ncz z = locate(bad); uint32_t big = (1u << 14) + 1; patch(bad, z.data + 0x18, &big, 4);
      IN_BAD; refused("a block that claims more bytes than a block holds", "claims", in, 3, TID); }
    { damaged(p[B_B14], bad); Ncz z = locate(bad); uint64_t body; size_t n; uint8_t *b = slurp(bad, &n); memcpy(&body, b + z.data + 16, 8); free(b); body += 0x200; patch(bad, z.data + 16, &body, 8);
      IN_BAD; refused("a block table whose size is not the ranges'", "does not match its range table", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); patch(bad, z.off + 0x4000, "NCZSECT2", 8);
      IN_BAD; refused("NXPress's NCZSECT2", "NCZSECT2", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); patch(bad, z.off + 0x4000, "NCZSECTX", 8);
      IN_BAD; refused("no NCZSECTN", "not a compressed NCA", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); uint64_t t = 2; patch(bad, z.sec + 0x10, &t, 8);
      IN_BAD; refused("an AES-XTS range", "AES-XTS", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); uint64_t t = 9; patch(bad, z.sec + 0x40 + 0x10, &t, 8);
      IN_BAD; refused("a range of an unknown type", "unknown encryption type", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); uint64_t o; size_t n; uint8_t *b = slurp(bad, &n); memcpy(&o, b + z.sec + 0x40, 8); free(b); o += 0x200; patch(bad, z.sec + 0x40, &o, 8);
      IN_BAD; refused("a gap between two ranges", "gap or an overlap", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); uint64_t c = 1ull << 40; patch(bad, z.off + 0x4008, &c, 8);
      IN_BAD; refused("a range count past the file", "bad range table", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); int k = -1; size_t n; uint8_t *b = slurp(bad, &n);
      for (uint32_t i = 0; i < z.nsec && k < 0; i++) { uint64_t t; memcpy(&t, b + z.sec + 0x40 * i + 0x10, 8); if (t == 3) k = (int)i; }
      free(b); uint8_t x = byte_at(bad, z.sec + 0x40 * (uint64_t)k + 0x20) ^ 0x80; patch(bad, z.sec + 0x40 * (uint64_t)k + 0x20, &x, 1);
      IN_BAD; refused("a range's key that is not the one the ticket and prod.keys give", "keys inside", in, 3, TID); }
    { const char *in[] = { p[U_W28], g.upd, a.path }; refused("a solid stream asking for a 256 MiB window", "window larger than 128 MiB", in, 3, TID); }
    { damaged(p[B_SOLID], bad); Ncz z = locate(bad); FILE *f = fopen(bad, "r+b"); if (!f || ftruncate(fileno(f), (off_t)(z.data + (z.off + z.size - z.data) / 2))) abort(); fclose(f);
      IN_BAD; refused("a solid NSZ cut off halfway", "cut", in, 3, TID); }
    { damaged(p[B_B14], bad); Ncz z = locate(bad); FILE *f = fopen(bad, "r+b"); if (!f || ftruncate(fileno(f), (off_t)(z.off + z.size - 5000))) abort(); fclose(f);
      IN_BAD; refused("a block NSZ cut off", "cut", in, 3, TID); }
    { damaged(p[B_SOLID], bad); size_t n; uint8_t *b = slurp(bad, &n); uint32_t ne, i; memcpy(&ne, b + 4, 4);   // the PFS0 says the NCZ is shorter: its stream ends early
      for (i = 0; i < ne; i++) { uint32_t no; memcpy(&no, b + 0x10 + 0x18 * i + 0x10, 4); const char *nm = (const char *)b + 0x10 + 0x18 * ne + no; if (nz_is(nm, ".ncz")) break; }
      uint64_t sz; memcpy(&sz, b + 0x10 + 0x18 * i + 8, 8); sz -= 3000; patch(bad, 0x10 + 0x18 * i + 8, &sz, 8); free(b);
      IN_BAD; refused("a solid NCZ whose stream ends before its body does", "cut off", in, 3, TID); }

    printf("6 Stop\n");
    { char out[700]; Prog q; memset(&q, 0, sizeof q); strcpy(q.stop_on, "unpack/"); snprintf(out, sizeof out, "%s/stop.nup", dir); const char *in[] = { p[B_SOLID], g.upd, a.path };
      int rc = run(out, in, 3, TID, &q);
      CHECK(rc && q.stop && strstr(nximport_last_error(), "stopped") && !exists(out) && no_temp(out), "while unpacking: stopped (%s), no .nup and nothing beside it", nximport_last_error()); }
    { char out[700]; Prog q; memset(&q, 0, sizeof q); strcpy(q.stop_on, "check/"); snprintf(out, sizeof out, "%s/stop.nup", dir); const char *in[] = { p[B_B9], g.upd, a.path };
      int rc = run(out, in, 3, TID, &q);
      CHECK(rc && q.stop && strstr(nximport_last_error(), "stopped") && !exists(out), "while checking blocks: stopped (%s)", nximport_last_error()); }
    { char out[700]; Prog q; memset(&q, 0, sizeof q); strcpy(q.stop_on, "base/romfs"); snprintf(out, sizeof out, "%s/stop.nup", dir); const char *in[] = { g.base, g.upd, a.path };
      int rc = run(out, in, 3, TID, &q);
      CHECK(rc && q.stop && strstr(nximport_last_error(), "stopped") && !exists(out), "an uncompressed import while it writes: stopped too (%s)", nximport_last_error()); }
    { char t[20]; snprintf(t, sizeof t, "%016llx", (unsigned long long)TID); char out[700]; snprintf(out, sizeof out, "%s/after.nup", dir); const char *in[] = { p[B_SOLID], g.upd, a.path };
      nximport_cancel(1); unlink(out);
      int rc = nximport_write_nup(out, in, 3, t, NULL, NULL);   // a Stop set before an import begins ends it at once: the caller clears it (nximport_cancel(0)) when a new one starts
      CHECK(rc && strstr(nximport_last_error(), "stopped"), "a Stop set before the import began stops it (%s)", nximport_last_error());
      rc = nximport_write_nup(out, in, 3, t, NULL, NULL);
      CHECK(!rc && same_file(out, ref), "the flag is gone when an import returns: the next one runs (%s)", rc ? nximport_last_error() : "the same .nup"); }

    if (getenv("NCZ_TEST_KEEP")) printf("kept: %s (the dumps, the compressed ones, prod.keys)\n", dir); else rm_tree(dir);
    printf("%s: %d checks, %d failed\n", fails ? "FAIL" : "PASS", checks, fails);
    return fails != 0;
}
