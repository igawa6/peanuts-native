// nsp_test: the RomFS read straight from dumps (dm/peanuts/peanuts_nsp.c, the importer's run-time additions in nx_nca.c, and the host's hooks in
// dm/host/hle_fs.c) on a Linux PC, with no game data: the dumps are written here (nsp_fixture.h) with keys made up for the test.
//   nsp_test            scratch files go to a folder of their own under $TMPDIR (else /tmp), removed at the end
// What is checked, each against the bytes the fixture wrote:
//   1 the importer alone: a container opened by path and by descriptor; reads at every alignment and across files, relocation ranges, subsections and
//     compressed entries; eight threads reading one section at once; the shared cache with almost no room; the cache empty once the sections close
//   2 the host: DM_ROMFS=nsp:... mounted beside the .nup the importer makes of the same dumps: the two indexes equal entry by entry, every file equal read
//     whole and in a few thousand ranges, from eight threads at once; a base alone; an XCI; the fd:<n>/<name> form
//   3 DM_ROMFS_OVERLAY: a replaced file and an added one come from the folder, whatever the image under it
//   4 add-on content: DM_AOC_DIR/<index>.nsp as a link to a dump and as a link to a descriptor
//   5 what is refused, and that the reason names it: no keys, wrong keys, an NSZ, a personalized ticket, AES-XTS, another title, another version, an update
//     without its base, a file that is no dump
//   6 the companion runtime's provider (peanuts_nup.c) over the same spec
// hle_fs.c is the host's file as it is: it is compiled here as if for Android arm64 (declarations only; its entry stubs, which are arm64 assembly, are left
// out), with the handful of host functions it calls stood in below.
#include "hle.h"
#undef HLE_FORCE_BIG
#define HLE_FORCE_BIG(mangled, why, ret, cname, ...) static ret cname(__VA_ARGS__) __attribute__((used)); static ret cname(__VA_ARGS__)
#include "hle_fs.c"
#include <ftw.h>
#include <sys/mman.h>
#include <time.h>
#include "nximport.h"
#include "nx_internal.h"
#include "../../peanuts_nup.h"
#include "nsp_fixture.h"

// ---- the host around hle_fs.c
struct dm_flags g_flag; uint64_t g_t0_ns; uint64_t now_ns(void) { return 0; }
static _Atomic int g_prep_calls; static int g_prep_unprotect_at;   // the test of a destination that is write-protected: made writable at that call of the stand-in (0: never)
void dm_guest_write_prepare(void *a, size_t n) { if (++g_prep_calls == g_prep_unprotect_at) mprotect(a, n, PROT_READ | PROT_WRITE); }
static module_t g_main; module_t *g_mod = &g_main; int g_nmod = 1;
int dm_main_imports(const char *prefix) { (void)prefix; return 0; }
int dm_game_imports(const char *prefix) { (void)prefix; return 0; }   // (hle_fs.c asks this one since Banana Native v0.3.22)
int dm_mii_standin(const char *dir) { (void)dir; return 0; }
void dm_thread_service_request(void) {}
uint64_t plat_clock_ns(clockid_t c) { struct timespec t; clock_gettime(c, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
int plat_mem_region(uint64_t addr, uint64_t *start, uint64_t *size, int *prot) { (void)addr; (void)start; (void)size; (void)prot; return -1; }
int plat_clone_tree(const char *from, const char *to) { (void)from; (void)to; errno = ENOTSUP; return -1; }
int plat_rename_swap(const char *a, const char *b) { (void)a; (void)b; errno = ENOTSUP; return -1; }
void plat_swap_recover(const char *b) { (void)b; }
ssize_t plat_getxattr(const char *path, const char *name, void *buf, size_t n) { (void)path; (void)name; (void)buf; (void)n; errno = ENODATA; return -1; }
int plat_setxattr(const char *path, const char *name, const void *buf, size_t n) { (void)path; (void)name; (void)buf; (void)n; errno = ENOTSUP; return -1; }
int plat_removexattr(const char *path, const char *name) { (void)path; (void)name; return 0; }
void plat_device_model(char *out, size_t n) { if (n) *out = 0; }
_Static_assert(sizeof(NxSha256) <= sizeof(plat_sha256_t), "the importer's SHA-256 state fits the host's");
void plat_sha256_init(plat_sha256_t *c) { nx_sha256_init((NxSha256 *)c); }
void plat_sha256_update(plat_sha256_t *c, const void *p, size_t n) { nx_sha256_update((NxSha256 *)c, p, n); }
void plat_sha256_final(uint8_t out[32], plat_sha256_t *c) { nx_sha256_final((NxSha256 *)c, out); }
void plat_sha256(const void *p, size_t n, uint8_t out[32]) { nx_sha256(p, n, out); }

static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  FAIL "); } else printf("  ok   "); printf(__VA_ARGS__); printf("\n"); } while (0)
static _Thread_local uint64_t t_rng = 0x1234567887654321ull;
static uint64_t rnd(void) { t_rng ^= t_rng << 13; t_rng ^= t_rng >> 7; t_rng ^= t_rng << 17; return t_rng; }
enum { TID_HI = 0x0100AAAA, THREADS = 8 };
static const uint64_t TID = ((uint64_t)TID_HI << 32) | 0x0000B000u;

static const FxFile *file_of(const FxFile *f, int n, const char *path) { for (int i = 0; i < n; i++) if (!strcmp(f[i].path, path)) return &f[i]; return NULL; }
static romfs_img *img_of(const char *name) { for (int i = 0; i < g_nmnt; i++) if (!strcmp(g_mnt[i].name, name)) return g_mnt[i].img; return NULL; }
static int mount_rom(const char *name, const char *spec, const char *build) {
    snprintf(g_main.build_id, sizeof g_main.build_id, "%s", build); setenv("DM_ROMFS", spec, 1);
    return h_fs_MountRom(name, NULL, 0) == RES_OK && img_of(name);
}
// Bytes [off, off + len) of mount:/path through the host's calls, as a game reads them; the count read, -1 when the open or the read fails
static int64_t fs_read(const char *mnt, const char *path, int64_t off, uint8_t *buf, size_t len) {
    char p[400]; uint64_t h; size_t got = 0; snprintf(p, sizeof p, "%s:/%s", mnt, path);
    if (h_fs_OpenFile(&h, p, MODE_READ) != RES_OK) return -1;
    Result r = h_fs_ReadFile(&got, h, off, buf, len); h_fs_CloseFile(h);
    return r == RES_OK ? (int64_t)got : -1;
}
// Every file of the set read whole from the mount (in pieces of an odd size) and equal to the fixture's bytes: the number that differ
static int files_differ(const char *mnt, const FxFile *f, int n) {
    int bad = 0; uint8_t *buf = malloc(400000);
    for (int i = 0; i < n; i++) {
        char p[400]; uint64_t h; int64_t sz = -1, off = 0; int ok = 1; snprintf(p, sizeof p, "%s:/%s", mnt, f[i].path);
        if (h_fs_OpenFile(&h, p, MODE_READ) != RES_OK) { bad++; continue; }
        if (h_fs_GetFileSize(&sz, h) != RES_OK || (uint64_t)sz != f[i].size) ok = 0;
        for (size_t step = 1 + (size_t)(i * 7919 % 50000); ok && (uint64_t)off < f[i].size; ) {
            size_t got = 0; if (h_fs_ReadFile(&got, h, off, buf, step) != RES_OK || !got || memcmp(buf, f[i].data + off, got)) ok = 0;
            off += (int64_t)got;
        }
        { size_t got = 9; if (h_fs_ReadFile(&got, h, (int64_t)f[i].size, buf, 16) != RES_OK || got) ok = 0; }   // at the end: nothing
        h_fs_CloseFile(h); bad += !ok;
    }
    free(buf); return bad;
}
// Random ranges of random files through the mount against the fixture; the number that differ (also run by several threads at once)
typedef struct { const char *mnt; const FxFile *f; int n, reads, bad; uint64_t seed, bytes; } RangeJob;
static void *ranges_run(void *arg) {
    RangeJob *j = arg; uint8_t *buf = malloc(140000); t_rng = j->seed;
    for (int r = 0; r < j->reads; r++) {
        const FxFile *f = &j->f[rnd() % (uint64_t)j->n]; if (!f->size) continue;
        uint64_t off = rnd() % f->size; size_t len = 1 + (size_t)(rnd() % (rnd() % 4 ? 5000 : 130000));
        size_t want = f->size - off < len ? (size_t)(f->size - off) : len;
        int64_t got = fs_read(j->mnt, f->path, (int64_t)off, buf, len);
        if (got != (int64_t)want || memcmp(buf, f->data + off, want)) j->bad++;
        j->bytes += want;
    }
    free(buf); return NULL;
}
static int ranges_differ(const char *mnt, const FxFile *f, int n, int threads, int reads) {
    pthread_t th[THREADS]; RangeJob j[THREADS]; int bad = 0;
    for (int i = 0; i < threads; i++) { j[i] = (RangeJob){ mnt, f, n, reads, 0, 0x9E3779B97F4A7C15ull * (uint64_t)(i + 1), 0 }; pthread_create(&th[i], NULL, ranges_run, &j[i]); }
    for (int i = 0; i < threads; i++) { pthread_join(th[i], NULL); bad += j[i].bad; }
    return bad;
}
// Two images entry by entry: the same tree, names and sizes (what the host's lookups, listings and sizes read); the first entry that differs, -1 none
static int64_t index_differs(const romfs_img *a, const romfs_img *b) {
    if (a->n != b->n) return (int64_t)(a->n < b->n ? a->n : b->n);
    for (uint64_t k = 0; k < a->n; k++) {
        const img_ent *x = &a->e[k], *y = &b->e[k];
        if (x->type != y->type || x->nlen != y->nlen || memcmp(a->names + x->name, b->names + y->name, x->nlen) || x->b != y->b || (!x->type && x->a != y->a)) return (int64_t)k;
    }
    return -1;
}

// ---- 1: the importer alone. The update's RomFS section of a game, opened the way the run-time reader opens it
typedef struct { NxContainer bc, uc; NxTitle *bt, *ut; NxNca base, upd; int sec; } Sect;
static int sect_open(Sect *s, const FxGame *g, int by_fd) {
    int nb, nu, bfd = -1, ufd = -1; memset(s, 0, sizeof *s);
    if (by_fd) { bfd = open(g->base, O_RDONLY); ufd = open(g->upd, O_RDONLY); }
    if (nx_container_open_rt(&s->bc, g->base, bfd) || nx_container_open_rt(&s->uc, g->upd, ufd)) return -1;
    if (by_fd) { close(bfd); close(ufd); }   // the containers read their own duplicates
    if (nx_container_titles(&s->bc, &s->bt, &nb) || nx_container_titles(&s->uc, &s->ut, &nu)) return -1;
    char nm[48]; snprintf(nm, sizeof nm, "%s.nca", s->bt[0].program); if (nx_nca_open(&s->base, &s->bc, nx_container_find(&s->bc, nm)) || !s->base.key_ok) return -1;
    snprintf(nm, sizeof nm, "%s.nca", s->ut[0].program); if (nx_nca_open(&s->upd, &s->uc, nx_container_find(&s->uc, nm)) || !s->upd.key_ok) return -1;
    s->sec = nx_nca_find_section(&s->upd, NX_FS_ROMFS);
    if (nx_nca_set_base(&s->upd, s->sec, &s->base, nx_nca_find_section(&s->base, NX_FS_ROMFS))) return -1;
    return nx_sec_share(&s->upd, s->sec);
}
static void sect_close(Sect *s) { nx_nca_close(&s->upd); nx_nca_close(&s->base); free(s->bt); free(s->ut); nx_container_close(&s->bc); nx_container_close(&s->uc); }
typedef struct { Sect *s; const FxBuf *img; int reads, bad, hot; uint64_t seed; } SectJob;
static void *sect_run(void *arg) {   // random ranges of the data level against the image; hot: all inside one small window, so every thread wants the same entries
    SectJob *j = arg; uint8_t *buf = malloc(300000); t_rng = j->seed;
    for (int r = 0; r < j->reads; r++) {
        uint64_t span = j->hot ? 200000 : j->img->n, off = rnd() % span; size_t len = 1 + (size_t)(rnd() % (rnd() % 8 ? 3000 : 250000));
        if (off + len > j->img->n) len = (size_t)(j->img->n - off);
        if (nx_sec_read_tc(&j->s->upd, j->s->sec, FX_HASH + off, buf, len, NULL) || memcmp(buf, j->img->p + off, len)) j->bad++;
    }
    free(buf); return NULL;
}
static int sect_threads(Sect *s, const FxBuf *img, int threads, int reads, int hot) {
    pthread_t th[THREADS]; SectJob j[THREADS]; int bad = 0;
    for (int i = 0; i < threads; i++) { j[i] = (SectJob){ s, img, reads, 0, hot, 0xD1B54A32D192ED03ull * (uint64_t)(i + 1) }; pthread_create(&th[i], NULL, sect_run, &j[i]); }
    for (int i = 0; i < threads; i++) { pthread_join(th[i], NULL); bad += j[i].bad; }
    return bad;
}
static void test_importer(const FxGame *g, const char *what) {
    Sect s, sfd; uint64_t hits, misses, held, entries;
    printf("1 the importer alone, %s\n", what);
    CHECK(!sect_open(&s, g, 0), "the base and its update open for a run-time reader (%s)", nximport_last_error());
    CHECK(!sect_open(&sfd, g, 1), "and by descriptor, the descriptors closed after the open");
    CHECK(s.bc.n == sfd.bc.n && s.uc.n == sfd.uc.n && !memcmp(s.bc.e, sfd.bc.e, sizeof *s.bc.e * (size_t)s.bc.n), "the same container either way");
    { const NxBktr *b = s.upd.sec[s.sec].bktr; const NxComp *c = s.upd.sec[s.sec].comp; uint32_t from_base = 0, t[4] = { 0, 0, 0, 0 };   // the fixture is what it is meant to be
      for (uint32_t i = 0; b && i < b->nr; i++) from_base += !b->r[i].patch;
      for (uint32_t i = 0; c && i < c->n; i++) t[c->e[i].type & 3]++;
      CHECK(b && from_base > 5 && b->nr - from_base > 5 && b->ns > 2, "the patch maps %u ranges of the base and carries %u, in %u subsections", from_base, b ? b->nr - from_base : 0, b ? b->ns - 1 : 0);
      if (c) CHECK(c->n > 682 && t[3] > 100 && t[0] > 20 && t[1], "its compression table has %u entries in more than one set: %u LZ4, %u stored, %u zeros", c->n, t[3], t[0], t[1]); }
    { int bad = 0; uint8_t *a = malloc(g->uimg.n), *b = malloc(g->uimg.n);   // whole, then the same bytes through the other open
      if (nx_sec_read_tc(&s.upd, s.sec, FX_HASH, a, g->uimg.n, NULL) || memcmp(a, g->uimg.p, g->uimg.n)) bad++;
      if (nx_sec_read_tc(&sfd.upd, sfd.sec, FX_HASH, b, g->uimg.n, NULL) || memcmp(b, g->uimg.p, g->uimg.n)) bad++;
      CHECK(!bad, "the whole data level (%zu bytes) is the image the fixture wrote, by path and by descriptor", g->uimg.n); free(a); free(b); }
    { int bad = 0; uint8_t buf[200];   // every alignment of start and end, at places spread over the image (and so over files, ranges and entries)
      for (uint64_t at = 0; at + 200 < g->uimg.n; at += g->uimg.n / 97 + 1) for (int in = 0; in < 17; in++) for (int len = 1; len < 50; len += (len < 34 ? 1 : 7))
          if (nx_sec_read_tc(&s.upd, s.sec, FX_HASH + at + (uint64_t)in, buf, (size_t)len, NULL) || memcmp(buf, g->uimg.p + at + in, (size_t)len)) bad++;
      CHECK(!bad, "reads of 1 to 49 bytes at every offset inside a 16-byte block"); }
    { NxNca t; char nm[48]; snprintf(nm, sizeof nm, "%s.nca", s.bt[0].program); const NxEntry *whole = nx_container_find(&s.bc, nm); NxEntry e = *whole;   // an NCA cut short, and one past its file's end
      e.size = whole->size / 2; int cut = nx_nca_open(&t, &s.bc, &e); if (!cut) nx_nca_close(&t);
      CHECK(cut && strstr(nximport_last_error(), "lies outside the NCA"), "an NCA whose section ends past the NCA's own end does not open (%s)", nximport_last_error());
      e = *whole; e.size += 1ull << 40; int past = nx_nca_open(&t, &s.bc, &e); if (!past) nx_nca_close(&t);
      CHECK(past && strstr(nximport_last_error(), "lies outside its file"), "nor one that ends past its file's end (%s)", nximport_last_error()); }
    { uint8_t buf[64]; NxSection *S = &s.upd.sec[s.sec]; NxBktr *b = S->bktr; int bsec = nx_nca_find_section(&s.base, NX_FS_ROMFS);   // offsets no section has
      CHECK(nx_sec_read_tc(&s.upd, s.sec, UINT64_MAX - 7, buf, 16, NULL) && nx_sec_read_raw(&s.base, bsec, UINT64_MAX - 7, buf, 16) &&
            nx_sec_read_raw(&s.base, bsec, s.base.sec[bsec].size - 8, buf, 16), "a read that ends past its section, or whose end wraps around 2^64, fails");
      // a damaged relocation table: a range of the patch's own data placed at the section's end, past it, and where its end wraps (the table is put back after)
      NxReloc *r = NULL; for (uint32_t i = 0; b && i < b->nr; i++) if (b->r[i].patch) { r = &b->r[i]; break; }
      if (r) { const uint64_t phys = r->phys, at[3] = { S->size, S->size + 0x4000, UINT64_MAX - 3 }; int bad = 0;
          for (int i = 0; i < 3; i++) { r->phys = at[i]; bad += !nx_sec_read_raw(&s.upd, s.sec, r->virt, buf, 16); }
          r->phys = phys;
          CHECK(!bad && !nx_sec_read_raw(&s.upd, s.sec, r->virt, buf, 1), "a relocation that points at or past the patch section's end is a failed read, not an endless or an overlong one"); } }
    CHECK(!sect_threads(&s, &g->uimg, 1, 4000, 0), "4000 random ranges, one thread");
    CHECK(!sect_threads(&s, &g->uimg, THREADS, 3000, 0), "%d threads, 3000 random ranges each, at once", THREADS);
    nx_shcache_stats(&hits, &misses, &held, &entries);
    if (g->uimg.n && s.upd.sec[s.sec].compressed) {
        CHECK(hits && misses && held <= NX_SHCACHE_BYTES, "the shared cache served them: %llu hits, %llu misses, %llu KiB in %llu entries",
              (unsigned long long)hits, (unsigned long long)misses, (unsigned long long)(held >> 10), (unsigned long long)entries);
        nx_shcache_budget(256 << 10);   // 32 KiB a shard: nearly every read puts an entry out
        CHECK(!sect_threads(&s, &g->uimg, THREADS, 6000, 1), "%d threads on one 200 KB window with a 256 KiB cache", THREADS);
        CHECK(!sect_threads(&sfd, &g->uimg, THREADS, 2000, 0), "and on the second open of the same section (its own entries in the same cache)");
        nx_shcache_stats(NULL, NULL, &held, &entries);
        CHECK(held <= (256 << 10) && entries, "the cache stays inside its size (%llu KiB, %llu entries)", (unsigned long long)(held >> 10), (unsigned long long)entries);
        nx_shcache_budget(NX_SHCACHE_BYTES);
    } else CHECK(!hits && !misses && !entries, "a plain section never touches the cache");
    sect_close(&s); sect_close(&sfd);
    nx_shcache_stats(NULL, NULL, &held, &entries);
    CHECK(!held && !entries, "nothing of a closed section is left in the cache");
}

// ---- 2: the host, the dumps beside the .nup made of them
static void test_host(const FxGame *g, const char *tag, const char *dir) {
    char nup[700], spec[1300], nupspec[800], tid[17], dm[32], nm[32], db[32], nb[32]; const char *in[2] = { g->base, g->upd };
    printf("2 the host, %s\n", tag);
    snprintf(nup, sizeof nup, "%s/%s.nup", dir, tag); snprintf(tid, sizeof tid, "%016llx", (unsigned long long)g->tid);
    snprintf(dm, sizeof dm, "d%s", tag); snprintf(nm, sizeof nm, "n%s", tag); snprintf(db, sizeof db, "db%s", tag); snprintf(nb, sizeof nb, "nb%s", tag);
    CHECK(!nximport_write_nup(nup, in, 2, tid, NULL, NULL), "the importer makes a .nup of the two dumps (%s)", nximport_last_error());
    snprintf(spec, sizeof spec, "nsp:%s|%s", g->base, g->upd);
    CHECK(mount_rom(dm, spec, g->ubuild), "DM_ROMFS=nsp:<base>|<update> mounts");
    CHECK(mount_rom(nm, nup, g->ubuild), "DM_ROMFS=<file>.nup mounts as before (its newest update)");
    romfs_img *a = img_of(dm), *b = img_of(nm);
    if (!a || !b) { CHECK(0, "both images are there"); return; }
    int64_t k = index_differs(a, b); uint64_t nfiles = 0; for (uint64_t i = 0; i < a->n; i++) nfiles += a->e[i].type;
    CHECK(k < 0, "the two indexes are equal: %llu entries, %llu files (first difference: entry %lld)", (unsigned long long)a->n, (unsigned long long)nfiles, (long long)k);
    CHECK(nfiles == (uint64_t)g->nuf, "and hold the fixture's %d files", g->nuf);
    CHECK(!files_differ(dm, g->uf, g->nuf), "every file read whole from the dumps is the fixture's");
    CHECK(!files_differ(nm, g->uf, g->nuf), "every file read whole from the .nup is the fixture's");
    CHECK(!ranges_differ(dm, g->uf, g->nuf, 1, 4000), "4000 random ranges from the dumps");
    CHECK(!ranges_differ(nm, g->uf, g->nuf, 1, 4000), "4000 random ranges from the .nup");
    CHECK(!ranges_differ(dm, g->uf, g->nuf, THREADS, 2500), "%d threads reading the dumps at once, 2500 ranges each", THREADS);
    if (!strcmp(tag, "plain")) {   // a destination page the graphics layer protected again while the read ran: prepared and read again; one that never becomes
        // writable: a failed read. (Only a plain file is read into the destination by the system; a compressed one is copied by the process, whose write the host's
        // own fault handler serves)
        const FxFile *f = NULL; for (int i = 0; i < g->nuf; i++) if (g->uf[i].size >= 4096) { f = &g->uf[i]; break; }
        uint8_t *page = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        g_prep_calls = 0; g_prep_unprotect_at = 3; int64_t got = fs_read(dm, f->path, 0, page, 4096);
        CHECK(got == 4096 && g_prep_calls == 3 && !memcmp(page, f->data, 4096), "a write-protected destination is prepared again and the read done again (%d prepares)", g_prep_calls);
        mprotect(page, 4096, PROT_READ); g_prep_calls = 0; g_prep_unprotect_at = 0;
        CHECK(fs_read(dm, f->path, 0, page, 4096) < 0 && g_prep_calls == 9, "one that stays protected is a failed read after 8 more tries");
        munmap(page, 4096);
    }
    { int32_t t = -1; char p[64]; snprintf(p, sizeof p, "%s:/data/stage", dm); CHECK(h_fs_GetEntryType(&t, p) == RES_OK && t == ENT_DIR, "a directory of the dumps is a directory"); }
    { char p[64]; uint64_t h; snprintf(p, sizeof p, "%s:/no/such/file", dm); CHECK(h_fs_OpenFile(&h, p, MODE_READ) != RES_OK, "a path the game does not have does not open"); }
    // the base alone (the game as it was before the update), against the .nup's base package
    snprintf(spec, sizeof spec, "nsp:%s", g->base); snprintf(nupspec, sizeof nupspec, "%s:base", nup);
    CHECK(mount_rom(db, spec, g->bbuild) && mount_rom(nb, nupspec, g->bbuild), "the base alone mounts both ways");
    if (img_of(db) && img_of(nb)) {
        CHECK(index_differs(img_of(db), img_of(nb)) < 0, "the base's two indexes are equal (%llu entries)", (unsigned long long)img_of(db)->n);
        CHECK(!files_differ(db, g->bf, g->nbf) && !files_differ(nb, g->bf, g->nbf), "every file of the base is the fixture's both ways");
    }
    { char why[300] = ""; romfs_img *m; snprintf(spec, sizeof spec, "nsp:%s|%s", g->upd, g->base);   // the files' order does not matter
      CHECK((m = pn_nsp_open(spec, g->tid, g->ubuild, why, sizeof why)) && index_differs(m, a) < 0, "nsp:<update>|<base> is the same image (%s)", why); romfs_image_close(m); }
}

// ---- 3: DM_ROMFS_OVERLAY before either kind of image
static void test_overlay(const FxGame *g, const char *dir, const char *nup) {
    char ov[700], p[800], spec[1300]; uint8_t buf[64]; const FxFile *f = file_of(g->uf, g->nuf, "data/data.arc"), *other = file_of(g->uf, g->nuf, "data/Z");
    printf("3 a mod's folder before the image\n");
    snprintf(ov, sizeof ov, "%s/overlay", dir); mkdir(ov, 0755); snprintf(p, sizeof p, "%s/data", ov); mkdir(p, 0755);
    snprintf(p, sizeof p, "%s/data/data.arc", ov); fx_write(p, "replaced by a mod", 17);
    snprintf(p, sizeof p, "%s/data/added.txt", ov); fx_write(p, "added by a mod", 14);
    setenv("DM_ROMFS_OVERLAY", ov, 1); snprintf(spec, sizeof spec, "nsp:%s|%s", g->base, g->upd);
    CHECK(f && other && mount_rom("od", spec, g->ubuild) && mount_rom("on", nup, g->ubuild), "the dumps and the .nup mount with DM_ROMFS_OVERLAY set");
    unsetenv("DM_ROMFS_OVERLAY");
    const char *m[2] = { "od", "on" };
    for (int i = 0; i < 2; i++) {
        const char *w = i ? "the .nup" : "the dumps";
        CHECK(fs_read(m[i], "data/data.arc", 0, buf, sizeof buf) == 17 && !memcmp(buf, "replaced by a mod", 17), "over %s: a replaced file is the folder's", w);
        CHECK(fs_read(m[i], "data/added.txt", 0, buf, sizeof buf) == 14 && !memcmp(buf, "added by a mod", 14), "over %s: an added file is there", w);
        size_t n = other->size < sizeof buf ? (size_t)other->size : sizeof buf;
        CHECK(fs_read(m[i], "data/Z", 0, buf, sizeof buf) == (int64_t)n && !memcmp(buf, other->data, n), "over %s: a file the folder lacks is the image's", w);
    }
    CHECK(fs_read("dplain", "data/data.arc", 0, buf, 17) == (int64_t)(f->size < 17 ? f->size : 17) && (!f->size || memcmp(buf, "replaced by a mod", 17)), "without the variable the dumps' own file is read");
}

// ---- 4: add-on content
static void test_aoc(const FxKeys *k, const char *dir) {
    FxAddon a1, a2, a3, a5, a6, a7; char ad[700], p[800], t[800]; size_t sz = 0;
    printf("4 add-on content\n");
    fx_addon(k, dir, "dlc1", TID, 1, FX_TIK_COMMON, 3, &a1); fx_addon(k, dir, "dlc2", TID, 2, FX_TIK_PERSONAL, 3, &a2);
    fx_addon(k, dir, "dlc3", TID, 3, 0, 3, &a3); fx_addon(k, dir, "dlc5", TID + 0x100000, 5, FX_TIK_COMMON, 3, &a5);
    snprintf(ad, sizeof ad, "%s/aoc", dir); mkdir(ad, 0755); setenv("DM_AOC_DIR", ad, 1);
    snprintf(p, sizeof p, "%s/1.nsp", ad); CHECK(!symlink(a1.path, p), "1.nsp links to an add-on's dump");
    snprintf(p, sizeof p, "%s/2.nsp", ad); CHECK(!symlink(a2.path, p), "2.nsp links to one with a personalized ticket");
    int fd = open(a3.path, O_RDONLY); snprintf(t, sizeof t, "fd:%d/dlc3.nsp", fd); snprintf(p, sizeof p, "%s/3.nsp", ad); CHECK(fd >= 0 && !symlink(t, p), "3.nsp links to fd:<n>/<name>");
    snprintf(p, sizeof p, "%s/5.nsp", ad); CHECK(!symlink(a5.path, p), "5.nsp links to an add-on of another game");
    CHECK(dm_aoc_usable(1) && dm_aoc_usable(3), "add-ons 1 and 3 are usable");
    CHECK(!dm_aoc_usable(2) && !dm_aoc_usable(5) && !dm_aoc_usable(4), "add-ons 2 (personalized), 5 (another game) and 4 (not there) are not");
    CHECK(h_fs_QueryAocCache(&sz, 1) == RES_OK && sz != 0x1000 && sz > 100, "the cache size of add-on 1 comes from its file table (%zu bytes)", sz);
    CHECK(h_fs_MountAoc("aoc1", 1, NULL, 0) == RES_OK && h_fs_MountAoc("aoc3", 3, NULL, 0) == RES_OK, "add-ons 1 and 3 mount");
    CHECK(h_fs_MountAoc("aoc2", 2, NULL, 0) != RES_OK && !img_of("aoc2"), "add-on 2 answers \"target not found\" and nothing is mounted");
    CHECK(!files_differ("aoc1", a1.f, a1.nf) && !files_differ("aoc3", a3.f, a3.nf), "every file of both add-ons is the fixture's");
    CHECK(!ranges_differ("aoc1", a1.f, a1.nf, THREADS, 500), "%d threads reading add-on 1 at once", THREADS);
    // A descriptor's name is the picked file's own: an XCI, or an NSP named in capitals. Neither ends in ".nsp", which is what upstream's reader of plaintext
    // NSPs knows a package by (nup_image.h nup_split): the host's aoc_dir hands such a link to the dumps' reader itself
    fx_addon(k, dir, "dlc6", TID, 6, FX_TIK_COMMON, 3, &a6); fx_addon(k, dir, "dlc7", TID, 7, 0, 3, &a7);
    int f6 = open(a6.path, O_RDONLY), f7 = open(a7.path, O_RDONLY);
    snprintf(t, sizeof t, "fd:%d/dlc6.xci", f6); snprintf(p, sizeof p, "%s/6.nsp", ad); CHECK(f6 >= 0 && !symlink(t, p), "6.nsp links to fd:<n>/dlc6.xci");
    snprintf(t, sizeof t, "fd:%d/DLC7.NSP", f7); snprintf(p, sizeof p, "%s/7.nsp", ad); CHECK(f7 >= 0 && !symlink(t, p), "7.nsp links to fd:<n>/DLC7.NSP");
    CHECK(dm_aoc_usable(6) && dm_aoc_usable(7), "add-ons 6 and 7 are usable");
    CHECK(h_fs_MountAoc("aoc6", 6, NULL, 0) == RES_OK && h_fs_MountAoc("aoc7", 7, NULL, 0) == RES_OK, "add-ons 6 and 7 mount");
    CHECK(!files_differ("aoc6", a6.f, a6.nf) && !files_differ("aoc7", a7.f, a7.nf), "every file of both is the fixture's");
    snprintf(t, sizeof t, "fd:%d/gone.bin", 987); snprintf(p, sizeof p, "%s/8.nup", ad); CHECK(!symlink(t, p) && !dm_aoc_usable(8), "8.nup links to a descriptor that is not open: not usable");
}

// ---- 5: what is refused
static int refused(const char *spec, uint64_t tid, const char *build, const char *expect, char *why, size_t wn) {
    why[0] = 0; romfs_img *m = pn_nsp_open(spec, tid, build, why, wn);
    if (m) { romfs_image_close(m); return 0; }
    return strstr(why, expect) != NULL;
}
static void test_refused(const FxKeys *k, const FxGame *g, const char *dir, const char *keys, const char *wrong_keys) {
    char spec[1300], why[400], p[700]; FxAddon xts; FxGame other; FxNca n;
    printf("5 what is refused\n");
    snprintf(spec, sizeof spec, "nsp:%s|%s", g->base, g->upd);
    unsetenv("DM_NX_KEYS"); CHECK(refused(spec, TID, g->ubuild, "DM_NX_KEYS is not set", why, sizeof why), "no DM_NX_KEYS: \"%s\"", why);
    snprintf(p, sizeof p, "%s/none.keys", dir); setenv("DM_NX_KEYS", p, 1); CHECK(refused(spec, TID, g->ubuild, "cannot open", why, sizeof why), "a keys file that is not there: \"%s\"", why);
    setenv("DM_NX_KEYS", wrong_keys, 1); CHECK(refused(spec, TID, g->ubuild, "wrong header key", why, sizeof why), "another console's keys: \"%s\"", why);
    CHECK(!img_open(spec), "and the host's mount of it gives no image");
    setenv("DM_NX_KEYS", keys, 1); CHECK(!refused(spec, TID, g->ubuild, "", why, sizeof why), "the right keys again: it opens");
    CHECK(refused(spec, TID + 0x10000, g->ubuild, "the running game is 0100aaaa0001b000", why, sizeof why), "dumps of another title: \"%s\"", why);
    CHECK(refused(spec, TID, g->bbuild, "another version", why, sizeof why), "the update is not the version the game was built from: \"%s\"", why);
    snprintf(spec, sizeof spec, "nsp:%s", g->base); CHECK(refused(spec, TID, g->ubuild, "another version", why, sizeof why), "the base alone under a game built from the update: \"%s\"", why);
    snprintf(spec, sizeof spec, "nsp:%s", g->upd); CHECK(refused(spec, TID, g->ubuild, "the base dump must be among the files", why, sizeof why), "the update without its base: \"%s\"", why);
    snprintf(spec, sizeof spec, "nsp:%s/absent.nsp", dir); CHECK(refused(spec, TID, NULL, "cannot open", why, sizeof why), "a dump that is not there: \"%s\"", why);
    snprintf(p, sizeof p, "%s/garbage.nsp", dir); { uint8_t *junk = fx_bytes(0x2000); fx_write(p, junk, 0x2000); free(junk); }
    snprintf(spec, sizeof spec, "nsp:%s", p); CHECK(refused(spec, TID, NULL, "not an NSP, XCI or NCA", why, sizeof why), "a file that is no dump: \"%s\"", why);
    CHECK(refused("nsp:fd:987/x.nsp", TID, NULL, "is not open", why, sizeof why), "a descriptor that is not open: \"%s\"", why);
    // an NSZ: the container of a base with a .ncz in it
    memset(&n, 0, sizeof n); n.sec[0].present = 1; n.sec[0].fs_type = NX_FS_ROMFS; n.sec[0].crypt = 3; fx_put(&n.sec[0].body, NULL, 0x400);
    snprintf(p, sizeof p, "%s/packed.nsz", dir); fx_title(k, p, TID, TID, 0, 0, &n, 0, 1, 0);
    snprintf(spec, sizeof spec, "nsp:%s", p); CHECK(refused(spec, TID, NULL, "compressed dump (NSZ / XCZ", why, sizeof why), "an NSZ: \"%s\"", why);
    fx_addon(k, dir, "dlc7", TID, 7, FX_TIK_PERSONAL, 3, &xts);
    snprintf(spec, sizeof spec, "nsp:%s|aoc/7", xts.path); CHECK(refused(spec, TID, NULL, "personalized", why, sizeof why), "a personalized ticket: \"%s\"", why);
    fx_addon(k, dir, "dlc8", TID, 8, 0, 2, &xts);
    snprintf(spec, sizeof spec, "nsp:%s|aoc/8", xts.path); CHECK(refused(spec, TID, NULL, "encryption type 2 (AES-XTS)", why, sizeof why), "an AES-XTS section: \"%s\"", why);
    snprintf(spec, sizeof spec, "nsp:%s|aoc/9", xts.path); CHECK(refused(spec, TID, NULL, "no add-on content 9", why, sizeof why), "an add-on the dump does not hold: \"%s\"", why);
    snprintf(spec, sizeof spec, "nsp:%s|aoc/99999999999999999999", g->base);   // a number no int holds: not the game's RomFS in the add-on's place
    CHECK(refused(spec, TID, NULL, "no add-on content 99999999999999999999", why, sizeof why), "an add-on index that is no index: \"%s\"", why);
    { FxGame badz; fx_bad_psize = 1; fx_game(k, dir, "badlz4", TID, 1, 0, &badz); fx_bad_psize = 0;   // a compression table that claims more compressed bytes than LZ4 can make
      snprintf(spec, sizeof spec, "nsp:%s", badz.base); CHECK(refused(spec, TID, NULL, "bad compression entry", why, sizeof why), "an LZ4 entry larger than its worst case: \"%s\"", why); }
    { char *lng = malloc(1300); memset(lng, 'x', 1299); lng[1299] = 0; lng[0] = '/'; setenv("DM_NSP_ROMFS", lng, 1); free(lng);   // too long for a spec: refused whole, not cut
      CHECK(h_fs_MountRom("dlong", NULL, 0) != RES_OK && !img_of("dlong"), "a DM_NSP_ROMFS too long for a dump spec mounts nothing"); unsetenv("DM_NSP_ROMFS"); }
    fx_game(k, dir, "other", TID + 0x10000, 0, 0, &other);
    snprintf(spec, sizeof spec, "nsp:%s|%s", g->base, other.upd); CHECK(refused(spec, TID, NULL, "holds the update of title", why, sizeof why), "another game's update beside the base: \"%s\"", why);
}

// ---- 6: the companion runtime's provider
typedef struct { int n, dirs; } ListCount;
static void list_cb(void *u, const char *name, int is_dir) { ListCount *c = u; (void)name; c->n++; c->dirs += is_dir; }
static void test_provider(const FxGame *g) {
    char spec[1300], why[300] = ""; uint64_t size = 0; uint8_t buf[5000]; ListCount lc = { 0, 0 };
    const FxFile *f = file_of(g->uf, g->nuf, "data/stage/world-1/b030.bin"); if (!f) f = &g->uf[20];
    printf("6 the companion runtime's RomFS provider\n");
    snprintf(spec, sizeof spec, "nsp:%s|%s", g->base, g->upd);
    CHECK(pn_nup_is_spec(spec) && pn_nup_is_spec("x.nup:base") && !pn_nup_is_spec("/a/folder"), "a dump spec is one of the provider's");
    CHECK(!pn_nup_open_for(spec, g->tid + 0x10000, g->ubuild, why, sizeof why) && !pn_nup_open_for(spec, g->tid, g->bbuild, why, sizeof why), "dumps of another title, or of another version, are not the companion's either (%s)", why);
    pn_nup *n = pn_nup_open_for(spec, g->tid, g->ubuild, why, sizeof why);
    CHECK(n != NULL, "it opens the dumps of the running game (%s)", n ? pn_nup_describe(n) : why);
    if (!n) return;
    int h = pn_nup_romfs_open(n, f->path, &size); size_t want = f->size > 1000 ? 1000 : (size_t)f->size, at = f->size > 1000 ? (size_t)f->size / 3 : 0;
    CHECK(h >= 0 && size == f->size, "a file opens with its size");
    CHECK(pn_nup_romfs_read(n, h, at, buf, want) == (int64_t)want && !memcmp(buf, f->data + at, want), "and reads the fixture's bytes");
    pn_nup_romfs_close(n, h);
    CHECK(pn_nup_romfs_list(n, "data", list_cb, &lc) && lc.n > 3 && lc.dirs >= 2, "a directory lists (%d entries, %d directories)", lc.n, lc.dirs);
    pn_nup_close(n);
}

static int rm_cb(const char *p, const struct stat *st, int flag, struct FTW *f) { (void)st; (void)flag; (void)f; return remove(p); }
int main(void) {
    const char *td = getenv("TMPDIR"); char dir[500], keys[600], wrong[600], spec[1300], nup[700]; FxKeys k, kw; FxGame plain, comp, card;
    snprintf(dir, sizeof dir, "%s/nsp_test-XXXXXX", td && *td ? td : "/tmp");
    if (!mkdtemp(dir)) { perror(dir); return 2; }
    snprintf(keys, sizeof keys, "%s/prod.keys", dir); snprintf(wrong, sizeof wrong, "%s/other.keys", dir);
    fx_keys_make(&k, keys); fx_keys_make(&kw, wrong);
    setenv("DM_NX_KEYS", keys, 1);
    { char t[20]; snprintf(t, sizeof t, "%016llx", (unsigned long long)TID); setenv("DM_PROGRAM_ID", t, 1); }   // the running game: the fixture's title
    strcpy(g_main.name, "main");
    if (nximport_keys(keys)) { printf("the fixture's keys do not load: %s\n", nximport_last_error()); return 2; }
    fx_game(&k, dir, "plain", TID, 0, 0, &plain); fx_game(&k, dir, "lz4", TID, 1, 0, &comp); fx_game(&k, dir, "card", TID, 0, 1, &card);

    test_importer(&plain, "a plain base and its patch");
    test_importer(&comp, "a compressed base and its compressed patch");
    test_host(&plain, "plain", dir);
    test_host(&comp, "lz4", dir);
    printf("2 the host, a base on a card (XCI) and the fd:<n>/<name> form\n");
    snprintf(spec, sizeof spec, "nsp:%s|%s", card.base, card.upd);
    CHECK(mount_rom("dcard", spec, card.ubuild) && !files_differ("dcard", card.uf, card.nuf), "an XCI base with an NSP update: every file is the fixture's");
    { int a = open(plain.base, O_RDONLY), b = open(plain.upd, O_RDONLY); snprintf(spec, sizeof spec, "nsp:fd:%d/base.nsp|fd:%d/update.nsp", a, b);
      CHECK(a >= 0 && b >= 0 && mount_rom("dfd", spec, plain.ubuild) && !files_differ("dfd", plain.uf, plain.nuf), "DM_ROMFS=nsp:fd:<n>/<name>|fd:<n>/<name>: every file is the fixture's");
      CHECK(img_of("dfd") && img_of("dplain") && index_differs(img_of("dfd"), img_of("dplain")) < 0, "and its index is the one the paths give"); }
    snprintf(nup, sizeof nup, "%s/plain.nup", dir);
    snprintf(spec, sizeof spec, "%s|%s", plain.base, plain.upd); setenv("DM_NSP_ROMFS", spec, 1);   // the trial switch: the dumps, whatever DM_ROMFS names
    CHECK(mount_rom("dtrial", nup, plain.ubuild) && img_of("dtrial")->n > 1 && (img_of("dtrial")->e[img_of("dtrial")->n - 1].flags & IMG_ENT_NSP) && !files_differ("dtrial", plain.uf, plain.nuf),
          "DM_NSP_ROMFS=<base>|<update> mounts the dumps in place of the .nup DM_ROMFS names");
    unsetenv("DM_NSP_ROMFS");
    test_overlay(&plain, dir, nup);
    test_aoc(&k, dir);
    test_refused(&k, &plain, dir, keys, wrong);
    test_provider(&comp);

    nftw(dir, rm_cb, 16, FTW_DEPTH | FTW_PHYS);
    for (int i = 0; i < g_nmnt; i++) if (g_mnt[i].img) { romfs_image_close(g_mnt[i].img); g_mnt[i].img = NULL; }   // on a network folder a file still open stays as a hidden name
    for (int fd = 3; fd < 256; fd++) close(fd);
    nftw(dir, rm_cb, 16, FTW_DEPTH | FTW_PHYS);
    printf("%s: %d checks, %d failed\n", fails ? "FAIL" : "PASS", checks, fails);
    return fails != 0;
}
