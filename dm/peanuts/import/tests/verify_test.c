// verify_test: the importer checks a plain NCA against its own hash layers (nx_verify.c; GitHub issue #561) with no game data. The fixture's dumps
// (nsp_fixture.h: made-up keys, a base with a plain or an LZ4-compressed RomFS and the IVFC and PFS0 hash layers a console's NCAs have, its BKTR update,
// an add-on) import as before, and every block of the update's patched RomFS is checked on the way. Then damaged copies, each refused with the file and its
// role named, no .nup written: a file cut short, a container entry shorter than its NCA, a byte flipped in the base's RomFS (read through the update, and
// read for the base's own package), in the update's RomFS (its header and a file it carries), in its ExeFS, in a hash level, in an fs header, and in an LZ4
// section's stored bytes. A byte the update no longer reads from the base does not stop the import. An LZ4 base whose hash levels are sized for more than
// its stored data (the unused hashes zeros, as Echoes of Wisdom's) imports, and a byte of its data or its used hashes is still refused.
#define _GNU_SOURCE
#include "nximport.h"
#include "nsp_fixture.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  FAIL "); } else printf("  ok   "); printf(__VA_ARGS__); printf("\n"); fflush(stdout); } while (0)

static const uint64_t TID = 0x0100CCCC0000C000ull, TID_LZ4 = 0x0100CCCC0000D000ull, TID_SLACK = 0x0100CCCC0000E000ull, TID_STUB = 0x0100CCCC0000F000ull;   // PEANUTS: TID_STUB
static char dir[500];

static uint8_t *slurp(const char *p, size_t *n) {
    FILE *f = fopen(p, "rb"); if (!f) return NULL;
    fseek(f, 0, SEEK_END); long l = ftell(f); fseek(f, 0, SEEK_SET);
    uint8_t *b = malloc((size_t)l + 1); if (!b || fread(b, 1, (size_t)l, f) != (size_t)l) { free(b); fclose(f); return NULL; }
    fclose(f); *n = (size_t)l; return b;
}
static int exists(const char *p) { struct stat st; return !stat(p, &st); }
static void copy_file(const char *from, const char *to) { size_t n; uint8_t *b = slurp(from, &n); if (!b) abort(); fx_write(to, b, n); free(b); }
static void flip(const char *p, uint64_t at) {   // one byte of a file inverted (under AES-CTR the same byte of the plain text)
    size_t n; uint8_t *b = slurp(p, &n); if (!b || at >= n) abort(); b[at] ^= 0xFF; fx_write(p, b, n); free(b);
}
static void cut(const char *p, size_t drop) { size_t n; uint8_t *b = slurp(p, &n); if (!b || drop >= n) abort(); fx_write(p, b, n - drop); free(b); }
static int rm_tree(const char *p) {
    DIR *d = opendir(p); if (!d) return unlink(p);
    struct dirent *e; char q[1200];
    while ((e = readdir(d))) { if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue; snprintf(q, sizeof q, "%s/%s", p, e->d_name); rm_tree(q); }
    closedir(d); return rmdir(p);
}

static int run(const char *out, const char **in, int n, uint64_t tid) {
    char t[20]; snprintf(t, sizeof t, "%016llx", (unsigned long long)tid);
    nximport_cancel(0); unlink(out);
    return nximport_write_nup(out, in, n, t, NULL, NULL);
}
static void good(const char *what, const char **in, int n, uint64_t tid) {
    char out[700]; snprintf(out, sizeof out, "%s/good.nup", dir);
    int rc = run(out, in, n, tid);
    CHECK(!rc && exists(out), "%s imports%s%s", what, rc ? ": " : "", rc ? nximport_last_error() : "");
}
// refused with every one of the texts in expect (NULL-terminated) in the message, and no .nup left
static void refused(const char *what, const char **in, int n, uint64_t tid, const char *const *expect) {
    char out[700]; snprintf(out, sizeof out, "%s/bad.nup", dir);
    int rc = run(out, in, n, tid), all = rc != 0;
    for (const char *const *e = expect; all && *e; e++) all = strstr(nximport_last_error(), *e) != NULL;
    CHECK(all && !exists(out), "%s: refused, no .nup (%s)", what, rc ? nximport_last_error() : "it imported");
}

// ---- where a title's content NCA lies in its dump
typedef struct Loc { NxContainer c; NxNca n; const NxEntry *e; } Loc;
static void locate(const char *path, Loc *l) {
    NxTitle *ts = NULL; int nt = 0; char nm[48];
    memset(l, 0, sizeof *l);
    if (nx_container_open(&l->c, path) || nx_container_titles(&l->c, &ts, &nt) || !nt) { printf("cannot open %s: %s\n", path, nximport_last_error()); abort(); }
    snprintf(nm, sizeof nm, "%s.nca", ts[0].program[0] ? ts[0].program : ts[0].data);
    if (!(l->e = nx_container_find(&l->c, nm)) || nx_nca_open(&l->n, &l->c, l->e)) abort();
    free(ts);
}
static void unlocate(Loc *l) { nx_nca_close(&l->n); nx_container_close(&l->c); }
static uint64_t at_sec(const Loc *l, int s, uint64_t off) { return l->e->off + l->n.sec[s].off + off; }   // a section's byte in the dump file
// a byte of the update's patched RomFS that the patch itself carries: its place in the patch section (the relocation table, after nx_nca_set_base)
static uint64_t carried(const Loc *u, uint64_t virt) {
    const NxBktr *b = u->n.sec[1].bktr;
    for (uint32_t i = 0; i < b->nr; i++) if (b->r[i].patch && virt >= b->r[i].virt && virt < b->r[i].end) return b->r[i].phys + (virt - b->r[i].virt);
    abort();
}

// every file of a tree read (as the writer reads them, one call each)
static int read_all(NxNode *d) {
    for (int i = 0; i < d->nkids; i++) {
        NxNode *k = d->kids[i];
        if (k->is_dir) { if (read_all(k)) return -1; continue; }
        if (!k->size) continue;
        uint8_t *b = malloc((size_t)k->size); if (!b) abort();
        int rc = nx_node_read(k, 0, b, (size_t)k->size); free(b);
        if (rc) return -1;
    }
    return 0;
}

int main(void) {
    const char *td = getenv("TMPDIR"); char keys[600], p[700]; FxKeys k; FxGame g, gl, gs; FxAddon a;
    snprintf(dir, sizeof dir, "%s/verify_test-XXXXXX", td && *td ? td : "/tmp");
    if (!mkdtemp(dir)) { perror(dir); return 2; }
    snprintf(keys, sizeof keys, "%s/prod.keys", dir); fx_keys_make(&k, keys);
    if (nximport_keys(keys)) { printf("the fixture's keys do not load: %s\n", nximport_last_error()); return 2; }
    fx_game(&k, dir, "plain", TID, 0, 0, &g); fx_game(&k, dir, "lz4", TID_LZ4, 1, 0, &gl); fx_addon(&k, dir, "dlc", TID, 1, 0, 3, &a);
    fx_ivfc_slack = 3; fx_game(&k, dir, "slack", TID_SLACK, 1, 0, &gs); fx_ivfc_slack = 0;   // hash levels sized for the decompressed data (Echoes of Wisdom)

    // ---- good dumps
    { const char *in[] = { g.base, g.upd, a.path }; good("a plain base, its update and an add-on", in, 3, TID); }
    { const char *in[] = { gl.base, gl.upd }; good("an LZ4-compressed base and its update", in, 2, TID_LZ4); }
    // an LZ4 base whose last hash level has whole blocks past the hashes its stored data uses (zeros, with zeros for their hashes one level up): those
    // blocks are never read, so they are not checked (the console does not either). Before this the import called a good Echoes of Wisdom damaged
    { Loc l; locate(gs.base, &l); const uint8_t *r = l.n.hdr + 0x400 + 0x200 * 1 + 8 + 0x10; uint64_t s4, s5; uint32_t o5;
      memcpy(&s4, r + 0x18 * 4 + 8, 8); memcpy(&s5, r + 0x18 * 5 + 8, 8); memcpy(&o5, r + 0x18 * 5 + 16, 4);
      uint64_t used = ((s5 + (1ull << o5) - 1) >> o5) * 32; unlocate(&l);
      CHECK(s4 >= used + 2 * 512, "the slack fixture's level 5 has whole unused blocks (%llu bytes, %llu used)", (unsigned long long)s4, (unsigned long long)used); }
    { const char *in[] = { gs.base, gs.upd }; good("an LZ4 base and update with hash levels longer than their data needs", in, 2, TID_SLACK); }
    nximport_set_keep_base(1);
    { const char *in[] = { g.base, g.upd }; good("the same with the base's own package kept", in, 2, TID); }
    { const char *in[] = { gl.base, gl.upd }; good("the LZ4 one with the base kept", in, 2, TID_LZ4); }
    { const char *in[] = { gs.base, gs.upd }; good("the long-levels one with the base kept", in, 2, TID_SLACK); }
    nximport_set_keep_base(0);
    // every block of the update's patched RomFS and ExeFS is checked as its files are read
    { Loc b, u; locate(g.base, &b); locate(g.upd, &u);
      int sb = nx_nca_find_section(&b.n, NX_FS_ROMFS), su = nx_nca_find_section(&u.n, NX_FS_ROMFS), xu = nx_nca_find_section(&u.n, NX_FS_PFS0);
      int ok = !nx_sec_verify(&b.n, sb, "base") && !nx_nca_set_base(&u.n, su, &b.n, sb) && !nx_sec_verify(&u.n, su, "update") && !nx_sec_verify(&u.n, xu, "update");
      NxNode *r = ok ? nx_romfs_tree(&u.n, su) : NULL, *x = ok ? nx_pfs0_tree(&u.n, xu) : NULL;
      ok = ok && r && x && !read_all(r) && !read_all(x);
      uint64_t done = 0, all = 0, xd = 0, xa = 0; nx_sec_check_count(&u.n, su, &done, &all); nx_sec_check_count(&u.n, xu, &xd, &xa);
      CHECK(ok && all > 8 && done == all && xa > 1 && xd == xa, "reading every file checks every block: RomFS %llu of %llu, ExeFS %llu of %llu%s%s",
            (unsigned long long)done, (unsigned long long)all, (unsigned long long)xd, (unsigned long long)xa, ok ? "" : ": ", ok ? "" : nximport_last_error());
      uint64_t bd = 0, ba = 0; nx_sec_check_count(&b.n, sb, &bd, &ba);
      CHECK(ba > 0 && bd > 0 && bd < ba, "  the base's blocks the update maps are checked against the base's own levels (%llu of %llu)", (unsigned long long)bd, (unsigned long long)ba);
      nx_node_free(r); nx_node_free(x); unlocate(&u); unlocate(&b); }

    // ---- damaged copies
    char what_base[300], what_upd[300];
    snprintf(what_base, sizeof what_base, "%s: the base game is damaged: ", "base.nsp");
    snprintf(what_upd, sizeof what_upd, "%s: the update v131072 is damaged: ", "update.nsp");
    char bp[700], up[700]; snprintf(bp, sizeof bp, "%s/base.nsp", dir); snprintf(up, sizeof up, "%s/update.nsp", dir);
    Loc b, u; locate(g.base, &b); locate(g.upd, &u);
    if (nx_nca_set_base(&u.n, 1, &b.n, 1)) abort();
    const char *in[] = { bp, up };
    // a copy that stopped early
    copy_file(g.base, bp); copy_file(g.upd, up); cut(up, 0x3000);
    { const char *e[] = { "update.nsp: ", " lies outside its file (", "the file is cut off", NULL }; refused("an update cut short", in, 2, TID, e); }   // nx_nca.c's words (#697)
    // the container's entry shorter than the NCA its CNMT records (entry 0, the program NCA: its size @0x18 of the PFS0)
    copy_file(g.upd, up);
    { size_t n; uint8_t *f = slurp(up, &n); uint64_t s; memcpy(&s, f + 0x18, 8); s -= 0x200; memcpy(f + 0x18, &s, 8); fx_write(up, f, n); free(f); }
    { const char *e[] = { what_upd, "its title's metadata says", NULL }; refused("an update whose container entry is shorter than its NCA", in, 2, TID, e); }
    // a byte of a base file the update keeps as it was: read through the update's patch, and named as the base's
    int kept = -1; for (int i = 11; i < g.nbf; i++) if (i % 4 >= 2 && g.bf[i].size > 4096) { kept = i; break; }
    if (kept < 0) abort();
    copy_file(g.base, bp); copy_file(g.upd, up); flip(bp, at_sec(&b, 1, FX_HASH + g.bf[kept].at + g.bf[kept].size / 2));
    { const char *e[] = { what_base, "of its RomFS do not match their checksum", NULL }; refused("a base byte the update reads from the base", in, 2, TID, e);
      nximport_set_keep_base(1); refused("  the same with the base's own package kept", in, 2, TID, e); nximport_set_keep_base(0); }
    // a byte of a base file the update replaced: the import never reads it, and goes through
    int gone = -1; for (int i = 11; i < g.nbf; i++) if (i % 4 == 1 && g.bf[i].size > 4096) { gone = i; break; }
    if (gone < 0) abort();
    copy_file(g.base, bp); flip(bp, at_sec(&b, 1, FX_HASH + g.bf[gone].at + g.bf[gone].size / 2));
    good("a damaged byte in a base file the update replaced (never read)", in, 2, TID);
    // the update's own bytes: its RomFS header, a file it carries, its ExeFS
    copy_file(g.base, bp); copy_file(g.upd, up); flip(up, at_sec(&u, 1, carried(&u, FX_HASH + 0x20)));
    { const char *e[] = { what_upd, "of its RomFS do not match their checksum", NULL }; refused("a byte of the update's RomFS header", in, 2, TID, e); }
    int own = -1; for (int i = 0; i < g.nuf; i++) if (g.uf[i].base < 0 && g.uf[i].size > 4096) { own = i; break; }
    if (own < 0) abort();
    copy_file(g.upd, up); flip(up, at_sec(&u, 1, carried(&u, FX_HASH + g.uf[own].at + g.uf[own].size - 1)));
    { const char *e[] = { what_upd, "of its RomFS do not match their checksum", NULL }; refused("the last byte of a file the update carries", in, 2, TID, e); }
    copy_file(g.upd, up); flip(up, at_sec(&u, 0, u.n.sec[0].data_off + 0x60 + 0x50));
    { const char *e[] = { what_upd, "of its ExeFS do not match their checksum", NULL }; refused("a byte of the update's main", in, 2, TID, e); }
    // the hash layers themselves: the first IVFC level of the base, the base's RomFS fs header (in the NCA header, AES-XTS: a block of it garbled)
    copy_file(g.upd, up); copy_file(g.base, bp); flip(bp, at_sec(&b, 1, 0));
    { const char *e[] = { what_base, "hash level 1 of its RomFS does not match its checksum", NULL }; refused("a byte of the base's first hash level", in, 2, TID, e); }
    copy_file(g.base, bp); flip(bp, b.e->off + 0x400 + 0x200 + 0x18);
    { const char *e[] = { what_base, "the header of its RomFS does not match its checksum", NULL }; refused("a byte of the base's RomFS fs header", in, 2, TID, e); }
    unlocate(&u); unlocate(&b);
    // an LZ4-compressed base: a byte of its stored (compressed) data, with the base's package kept so that all of it is read
    { Loc l; locate(gl.base, &l); uint64_t mid = FX_HASH + (l.n.sec[1].size - FX_HASH) / 3;
      copy_file(gl.base, bp); copy_file(gl.upd, up); flip(bp, at_sec(&l, 1, mid)); unlocate(&l);
      const char *e[] = { what_base, "of its RomFS do not match their checksum", NULL };
      nximport_set_keep_base(1); refused("a byte of an LZ4 base's stored data", in, 2, TID_LZ4, e); nximport_set_keep_base(0); }
    // the long-levels base: a byte of its stored data, and a byte of a level 5 block that holds hashes, are still refused
    { Loc l; locate(gs.base, &l); uint64_t mid = FX_HASH + (l.n.sec[1].size - FX_HASH) / 3, l5 = 0; memcpy(&l5, l.n.hdr + 0x400 + 0x200 + 8 + 0x10 + 0x18 * 4, 8);
      const char *sin[] = { bp, up }; copy_file(gs.base, bp); copy_file(gs.upd, up); flip(bp, at_sec(&l, 1, mid));
      const char *e[] = { what_base, "of its RomFS do not match their checksum", NULL };
      nximport_set_keep_base(1); refused("a byte of the long-levels base's stored data", sin, 2, TID_SLACK, e);
      copy_file(gs.base, bp); flip(bp, at_sec(&l, 1, l5 + 7)); unlocate(&l);
      const char *e5[] = { what_base, "hash level 5 of its RomFS does not match its checksum", NULL };
      refused("a byte of the long-levels base's level 5", sin, 2, TID_SLACK, e5); nximport_set_keep_base(0); }
    // PEANUTS: a base with no RomFS section (a 7 MB stub base: ExeFS only) and an update whose patch carries the whole RomFS: the patch gets no base, every
    // block of its RomFS is checked against the update's own levels, and a byte of a file it carries is named as the update's
    { FxGame gn; fx_base_no_romfs = 1; fx_game(&k, dir, "stub", TID_STUB, 0, 0, &gn); fx_base_no_romfs = 0;
      { const char *sin[] = { gn.base, gn.upd }; good("a base with no RomFS and an update that carries the whole RomFS", sin, 2, TID_STUB); }
      Loc l; locate(gn.upd, &l); int su = nx_nca_find_section(&l.n, NX_FS_ROMFS);
      int ok = su >= 0 && !nx_nca_set_base(&l.n, su, NULL, -1) && !nx_sec_verify(&l.n, su, "update");
      NxNode *r = ok ? nx_romfs_tree(&l.n, su) : NULL; ok = ok && r && !read_all(r);
      uint64_t done = 0, all = 0; nx_sec_check_count(&l.n, su, &done, &all);
      CHECK(ok && all > 8 && done == all, "  with no base, reading every file checks every block of its RomFS: %llu of %llu%s%s", (unsigned long long)done,
            (unsigned long long)all, ok ? "" : ": ", ok ? "" : nximport_last_error());
      nx_node_free(r);
      int own = -1; for (int i = 0; i < gn.nuf; i++) if (gn.uf[i].size > 4096) { own = i; break; }
      if (own < 0) abort();
      copy_file(gn.base, bp); copy_file(gn.upd, up); flip(up, at_sec(&l, 1, carried(&l, FX_HASH + gn.uf[own].at + gn.uf[own].size / 2))); unlocate(&l);
      const char *sin[] = { bp, up }; const char *e[] = { what_upd, "of its RomFS do not match their checksum", NULL };
      refused("  a byte of a file it carries, over the base with no RomFS", sin, 2, TID_STUB, e); }
    // the add-on's own data
    { Loc l; locate(a.path, &l); snprintf(p, sizeof p, "%s/dlcbad.nsp", dir); copy_file(a.path, p);
      flip(p, at_sec(&l, 0, FX_HASH + 0x60)); unlocate(&l);
      char want[300]; snprintf(want, sizeof want, "dlcbad.nsp: the DLC %016llx is damaged: ", (unsigned long long)(TID + 0x1000 + 1));
      const char *din[] = { g.base, g.upd, p }; const char *e[] = { want, NULL }; refused("a byte of the add-on's RomFS", din, 3, TID, e); }

    if (getenv("VERIFY_TEST_KEEP")) printf("kept: %s\n", dir); else rm_tree(dir);
    printf("%s: %d checks, %d failed\n", fails ? "FAIL" : "PASS", checks, fails);
    return fails != 0;
}
