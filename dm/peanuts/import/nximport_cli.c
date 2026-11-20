// nximport_cli — a test driver for libnximport (not shipped in the APK).
//   nximport_cli [--keys prod.keys] probe <nsp|xci|nsz|xcz>...
//   nximport_cli [--keys prod.keys] icon <nsp|xci|nsz|xcz> <title id|-> <out.png>
//   nximport_cli [--keys prod.keys] nup <out.nup> <base title id> <nsp|xci|nsz|xcz>...   (Ctrl-C stops it: no .nup, nothing left behind)
//   nximport_cli [--keys prod.keys] check <nsz|xcz>...  (every NCZ rebuilt and checked against its name and its CNMT; nothing is written)
//   nximport_cli [--keys prod.keys] fonts <firmware dir> <out dir>
//   nximport_cli verify <file.nup>                 (lists every entry: size, offset, codec)
//   nximport_cli cat <file.nup> <entry path> <out file>
// --keys defaults to $NX_KEYS, else ~/.switch/prod.keys.
#define _POSIX_C_SOURCE 200809L
#include "nximport.h"
#include "nx_internal.h"
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#include <pthread_time.h>   // winpthreads' clock_gettime
#else
#include <sys/resource.h>   // getrusage: the peak memory an import took
#endif

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static int die(const char *what) { fprintf(stderr, "%s: %s\n", what, nximport_last_error()); return 1; }

static double t0; static const char *last_step; static char last_name[300]; static long long last_mb = -1;
static void progress(void *u, const char *step, long long done, long long total) {
    (void)u; long long mb = done >> 20;
    int other = step != last_step || strcmp(step, last_name);   // a new step (the importer reuses one buffer for the names of the files it unpacks or checks)
    if (other || mb / 64 != last_mb / 64 || done == total) {
        if (other && last_step && (!strncmp(last_name, "unpack/", 7) || !strncmp(last_name, "check/", 6))) fprintf(stderr, "\n");   // keep each file's last line
        fprintf(stderr, "\r  [%6.1fs] %-28s %7lld / %lld MiB", now() - t0, step, mb, total >> 20); fflush(stderr);
        last_step = step; snprintf(last_name, sizeof last_name, "%s", step); last_mb = mb;
    }
}
static void peak(void) {   // the import's peak memory (resident), for measurements
#ifndef _WIN32
    struct rusage ru; if (!getrusage(RUSAGE_SELF, &ru)) printf("peak memory %.0f MiB\n", ru.ru_maxrss / 1024.0);
#endif
}

static void on_int(int sig) { (void)sig; nximport_cancel(1); }   // Ctrl-C: the import ends at its next step and cleans up

static void tick(void *u, uint64_t n) { long long *d = u; d[0] += (long long)n; progress(NULL, "check", d[0], d[1]); }
// every .ncz of a compressed dump rebuilt (a solid one only hashed: no temporary file) and checked as the import checks it → 0 all good
static int check(const char *path) {
    NxContainer c; NxTitle *ts = NULL; int nt = 0, bad = 0, seen = 0;
    if (nx_container_open(&c, path) || nx_container_titles(&c, &ts, &nt)) { fprintf(stderr, "%s: %s\n", path, nximport_last_error()); nx_container_close(&c); return 1; }
    for (int i = 0; i < c.n; i++) {
        const NxEntry *e = &c.e[i]; size_t l = strlen(e->name); uint8_t sha[32], id[16]; const uint8_t *want = NULL; NxNcz *z;
        if (l < 4 || strcmp(e->name + l - 4, ".ncz")) continue;
        seen++;
        for (int k = 0; k < nt && !want; k++) {
            if (!strncmp(ts[k].program, e->name, 32)) want = ts[k].program_sha; else if (!strncmp(ts[k].data, e->name, 32)) want = ts[k].data_sha; else if (!strncmp(ts[k].control, e->name, 32)) want = ts[k].control_sha;
        }
        if (nx_ncz_open(&z, &c, e)) { printf("  %s  %s\n", e->name, nximport_last_error()); bad++; continue; }
        long long d[2] = { 0, (long long)nx_ncz_body(z) }; t0 = now(); last_step = NULL;
        int rc = nx_ncz_prepare(z, NULL, sha, tick, d), named = !rc && !nx_hex_prefix(e->name, id) && !memcmp(sha, id, 16);
        fprintf(stderr, "\n");
        if (rc) printf("  %s  %s\n", e->name, nximport_last_error());
        else if (!named) printf("  %s  does not match its name: damaged\n", e->name);
        else if (!want) printf("  %s  ok by its name (no CNMT in the file lists it)\n", e->name);
        else if (memcmp(sha, want, 32)) printf("  %s  matches its name but not its CNMT record: damaged\n", e->name);
        else printf("  %s  ok (%s, %.1f GiB, %.1fs)\n", e->name, nx_ncz_solid(z) ? "solid" : "blocks", nx_ncz_body(z) / 1073741824.0, now() - t0);
        bad += rc || !named || (want && memcmp(sha, want, 32));
        nx_ncz_close(z);
    }
    nx_ncz_thread_done();
    printf("%s: %d compressed NCA%s, %s\n", path, seen, seen == 1 ? "" : "s", !seen ? "nothing to check (not an NSZ or XCZ)" : bad ? "DAMAGED" : "all good");
    free(ts); nx_container_close(&c); return bad || !seen;
}

static int list_cb(void *u, const char *path, unsigned long long size, unsigned long long off, int codec) {
    long long *n = u; n[0]++; n[1] += (long long)size; printf("%12llu  %#14llx  %d  %s\n", size, off, codec, path); return 0;
}

int main(int argc, char **argv) {
    const char *keys = getenv("NX_KEYS"); char defkeys[1024];
    if (!keys) { const char *h = getenv("HOME");
#ifdef _WIN32
        if (!h || !*h) h = getenv("USERPROFILE");   // Windows sets no HOME by default
#endif
        snprintf(defkeys, sizeof defkeys, "%s/.switch/prod.keys", h ? h : ""); keys = defkeys; }
    int a = 1;
    if (a + 1 < argc && !strcmp(argv[a], "--keys")) { keys = argv[a + 1]; a += 2; }
    if (a >= argc) { fprintf(stderr, "usage: nximport_cli [--keys prod.keys] probe|icon|nup|check|fonts|verify|cat ...\n"); return 2; }
    const char *cmd = argv[a++];
    if (strcmp(cmd, "verify") && strcmp(cmd, "cat") && nximport_keys(keys)) return die("keys");
    if (!strcmp(cmd, "probe")) {
        for (; a < argc; a++) {
            NxImportInfo info[32]; int n = 0; double t = now();
            if (nximport_probe(argv[a], info, 32, &n)) { fprintf(stderr, "%s: %s\n", argv[a], nximport_last_error()); continue; }
            printf("%s  (%d title%s, %.2fs)\n", argv[a], n, n == 1 ? "" : "s", now() - t);
            for (int i = 0; i < n && i < 32; i++) printf("  title %s  app %s  kind %s  version %u  name \"%s\"  publisher \"%s\"\n", info[i].title_id, info[i].app_id, info[i].kind == 0 ? "base" : info[i].kind == 1 ? "update" : "dlc", info[i].version, info[i].name, info[i].publisher);
            for (int i = 0; i < n && i < 32; i++) printf("  size %s  %llu  unpack %llu  check %llu\n", info[i].title_id, info[i].size, info[i].unpack, info[i].check);
        }
        return 0;
    }
    if (!strcmp(cmd, "icon")) {
        if (a + 3 > argc) return 2;
        if (nximport_icon(argv[a], strcmp(argv[a + 1], "-") ? argv[a + 1] : NULL, argv[a + 2])) return die("icon");
        printf("wrote %s\n", argv[a + 2]); return 0;
    }
    if (!strcmp(cmd, "nup")) {
        if (a + 3 > argc) return 2;
        const char *out = argv[a], *tid = argv[a + 1]; const char **in = (const char **)argv + a + 2; int n = argc - a - 2;
        t0 = now(); signal(SIGINT, on_int);
        if (nximport_write_nup(out, in, n, tid, progress, NULL)) { fprintf(stderr, "\n"); return die("nup"); }
        fprintf(stderr, "\n"); printf("wrote %s in %.1fs\n", out, now() - t0); peak(); return 0;
    }
    if (!strcmp(cmd, "check")) {
        int bad = 0; signal(SIGINT, on_int);
        for (; a < argc; a++) bad |= check(argv[a]);
        return bad;
    }
    if (!strcmp(cmd, "fonts")) {
        if (a + 2 > argc) return 2;
        if (nximport_firmware_fonts(argv[a], argv[a + 1])) return die("fonts");
        printf("wrote %s/standard.ttf and %s/nintendo_ext.ttf\n", argv[a + 1], argv[a + 1]); return 0;
    }
    if (!strcmp(cmd, "verify")) {
        if (a + 1 > argc) return 2;
        long long n[2] = { 0, 0 };
        if (nximport_verify_nup(argv[a], list_cb, n)) return die("verify");
        printf("%s: ok, %lld files, %.2f GiB\n", argv[a], n[0], n[1] / 1073741824.0); return 0;
    }
    if (!strcmp(cmd, "cat")) {
        if (a + 3 > argc) return 2;
        if (nximport_nup_extract(argv[a], argv[a + 1], argv[a + 2])) return die("cat");
        return 0;
    }
    if (!strcmp(cmd, "ncainfo")) {   // debugging: nximport_cli ncainfo <container> [<entry name>]
        if (a + 1 > argc) return 2;
        NxContainer c; if (nx_container_open(&c, argv[a])) return die("open");
        for (int i = 0; i < c.n; i++) {
            const NxEntry *e = &c.e[i]; size_t l = strlen(e->name);
            if (a + 1 < argc ? strcmp(e->name, argv[a + 1]) != 0 : (l < 4 || strcmp(e->name + l - 4, ".nca"))) continue;
            NxNca n; printf("%s  off %#llx size %llu\n", e->name, (unsigned long long)e->off, (unsigned long long)e->size);
            if (nx_nca_open(&n, &c, e)) { printf("  %s\n", nximport_last_error()); continue; }
            printf("  title %016llx type %d keygen %d rights %d key %s\n", (unsigned long long)n.title_id, n.content_type, n.key_gen, n.has_rights, n.key_ok ? "ok" : n.key_why);
            for (int s = 0; s < 4; s++) { const NxSection *S = &n.sec[s]; if (!S->present) continue;
                printf("  sec %d: off %#llx size %#llx fs %d hash %d crypt %d data_off %#llx ctr ", s, (unsigned long long)S->off, (unsigned long long)S->size, S->fs_type, S->hash_type, S->crypt_type, (unsigned long long)S->data_off);
                for (int k = 0; k < 8; k++) printf("%02x", S->ctr[k]); printf("\n");
                const uint8_t *fh = n.hdr + 0x400 + 0x200 * s;
                if (S->compressed) { uint64_t to, ts; uint32_t cnt; memcpy(&to, fh + 0x178, 8); memcpy(&ts, fh + 0x180, 8); memcpy(&cnt, fh + 0x190, 4); printf("    compressed: table @%#llx+%#llx (from data_off), %u entries\n", (unsigned long long)to, (unsigned long long)ts, cnt); }
                for (int row = 0; row < 0x200; row += 0x20) { printf("    %03x:", row); for (int k = 0; k < 0x20; k++) printf("%s%02x", k % 8 ? "" : " ", fh[row + k]); printf("\n"); }
            }
            nx_nca_close(&n);
        }
        nx_container_close(&c); return 0;
    }
    if (!strcmp(cmd, "ivfc")) {   // nximport_cli ivfc <container> <program nca> [<base container> <base program nca>]: checks a RomFS's data against its IVFC hash level
        if (a + 2 > argc) return 2;
        NxContainer c, bc; NxNca n, bn; int have_base = a + 4 <= argc;
        if (nx_container_open(&c, argv[a])) return die("open");
        const NxEntry *e = nx_container_find(&c, argv[a + 1]); if (!e) { fprintf(stderr, "no entry %s\n", argv[a + 1]); return 1; }
        if (nx_nca_open(&n, &c, e)) return die("nca");
        int s = nx_nca_find_section(&n, NX_FS_ROMFS); if (s < 0) { fprintf(stderr, "no RomFS section\n"); return 1; }
        if (have_base) {
            if (nx_container_open(&bc, argv[a + 2])) return die("open base");
            const NxEntry *be = nx_container_find(&bc, argv[a + 3]); if (!be || nx_nca_open(&bn, &bc, be)) return die("base nca");
            if (nx_nca_set_base(&n, s, &bn, nx_nca_find_section(&bn, NX_FS_ROMFS))) return die("set base");
        }
        const uint8_t *fh = n.hdr + 0x400 + 0x200 * s, *lv = fh + 0x18;   // ivfc level headers (6 x 0x18): logical offset, hash data size, block size (log2)
        uint64_t hoff, hsize, doff, dsize; uint32_t lg; memcpy(&hoff, lv + 0x18 * 4, 8); memcpy(&hsize, lv + 0x18 * 4 + 8, 8); memcpy(&doff, lv + 0x18 * 5, 8); memcpy(&dsize, lv + 0x18 * 5 + 8, 8); memcpy(&lg, lv + 0x18 * 5 + 16, 4);
        uint32_t bs = 1u << lg;
        printf("level 5 (hashes) @%#llx+%#llx, level 6 (data) @%#llx+%#llx, block %u\n", (unsigned long long)hoff, (unsigned long long)hsize, (unsigned long long)doff, (unsigned long long)dsize, bs);
        uint8_t *hashes = malloc((size_t)hsize), *buf = malloc(bs); if (!hashes || !buf || nx_sec_read_raw(&n, s, hoff, hashes, (size_t)hsize)) return die("hashes");   // raw: the hashes cover the (compressed) data level
        uint64_t blocks = (dsize + bs - 1) / bs, bad = 0; t0 = now();
        for (uint64_t i = 0; i < blocks; i++) {
            size_t k = (size_t)(dsize - i * bs < bs ? dsize - i * bs : bs); memset(buf, 0, bs);
            if (nx_sec_read_raw(&n, s, doff + i * bs, buf, k)) return die("read");
            uint8_t h[32]; nx_sha256(buf, bs, h);   // a short last block is hashed zero-padded to the block size
            if (memcmp(h, hashes + 32 * i, 32)) { if (bad < 5) printf("  block %llu (@%#llx) does not match\n", (unsigned long long)i, (unsigned long long)(doff + i * bs)); bad++; }
            if (!(i & 4095)) { fprintf(stderr, "\r  [%6.1fs] %llu / %llu blocks", now() - t0, (unsigned long long)i, (unsigned long long)blocks); fflush(stderr); }
        }
        fprintf(stderr, "\n"); printf("%llu blocks, %llu bad (%.1fs)\n", (unsigned long long)blocks, (unsigned long long)bad, now() - t0);
        return bad ? 1 : 0;
    }
    fprintf(stderr, "unknown command %s\n", cmd); return 2;
}
