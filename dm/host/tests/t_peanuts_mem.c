// PEANUTS: the memory-pressure reader (dm/peanuts/peanuts_mem.c), run on a Linux PC: the PSI text, the meminfo text, the levels of each and of both together, files
// read from a temporary folder (present, missing, garbage) and the poll's switch.
#include "../../peanuts/peanuts_mem.c"
#include "../../peanuts/peanuts_opt.c"
#include <math.h>
#include <stdio.h>
#include <unistd.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }
static int near(float a, float b) { return fabsf(a - b) < 0.001f; }

static void put(const char *path, const char *text) { FILE *f = fopen(path, "w"); if (f) { fputs(text, f); fclose(f); } }

static const char *k_psi_calm = "some avg10=0.00 avg60=0.12 avg300=0.50 total=123456\nfull avg10=0.00 avg60=0.00 avg300=0.01 total=7890\n";
static const char *k_psi_mild = "some avg10=9.50 avg60=4.00 avg300=1.00 total=200\nfull avg10=0.50 avg60=0.10 avg300=0.00 total=10\n";
static const char *k_psi_full = "some avg10=3.00 avg60=2.00 avg300=1.00 total=200\nfull avg10=1.20 avg60=0.10 avg300=0.00 total=10\n";
static const char *k_psi_severe_some = "some avg10=25.00 avg60=10.00 avg300=1.00 total=200\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=0\n";
static const char *k_psi_severe_full = "some avg10=10.00 avg60=10.00 avg300=1.00 total=200\nfull avg10=5.00 avg60=0.00 avg300=0.00 total=0\n";
static const char *k_mi_12g = "MemTotal:       11864492 kB\nMemFree:          200000 kB\nMemAvailable:    6000000 kB\nBuffers:           10000 kB\nSwapTotal:       4194300 kB\nSwapFree:        3000000 kB\n";

int main(void) {
    pn_psi p; pn_meminfo m;

    // PSI text
    check(pn_psi_parse(k_psi_calm, &p) == 0 && p.have_some && p.have_full, "psi: both lines read");
    check(near(p.some60, 0.12f) && near(p.some300, 0.50f) && p.some_total_us == 123456ull && p.full_total_us == 7890ull, "psi: values and totals");
    check(pn_psi_parse("some avg10=1.00 avg60=2.00 avg300=3.00 total=5\n", &p) == 0 && p.have_some && !p.have_full, "psi: the full line is optional (older kernels)");
    check(pn_psi_parse("some avg10=1.00 avg60=2.00 avg300=3.00 total=5", &p) == 0 && near(p.some300, 3.0f), "psi: no trailing newline");
    check(pn_psi_parse("", &p) == -1 && !p.have_some, "psi: empty text fails");
    check(pn_psi_parse("garbage\nsome avg10=x\n", &p) == -1, "psi: malformed line fails");
    check(pn_psi_parse(NULL, &p) == -1 && pn_psi_parse("some avg10=1 avg60=1 avg300=1 total=1", NULL) == -1, "psi: NULL text or out fails");

    // meminfo text
    check(pn_meminfo_parse(k_mi_12g, &m) == 0 && m.total_kb == 11864492ull && m.avail_kb == 6000000ull, "meminfo: total and available");
    check(m.swap_total_kb == 4194300ull && m.swap_free_kb == 3000000ull, "meminfo: swap");
    check(pn_meminfo_parse("MemTotal: 100 kB\nMemAvailable: 50 kB\n", &m) == 0 && m.swap_total_kb == 0, "meminfo: swap lines are optional");
    check(pn_meminfo_parse("MemTotal: 100 kB\n", &m) == -1, "meminfo: no MemAvailable fails");
    check(pn_meminfo_parse("MemAvailable: 100 kB\n", &m) == -1, "meminfo: no MemTotal fails");
    check(pn_meminfo_parse("", &m) == -1 && pn_meminfo_parse(NULL, &m) == -1 && pn_meminfo_parse(k_mi_12g, NULL) == -1, "meminfo: empty, NULL text and NULL out fail");

    // levels of PSI
    pn_psi_parse(k_psi_calm, &p);          check(pn_mem_level(&p, NULL) == PN_MEM_OK, "level: calm psi is OK");
    pn_psi_parse(k_psi_mild, &p);          check(pn_mem_level(&p, NULL) == PN_MEM_MILD, "level: some10 9.5 is mild");
    pn_psi_parse(k_psi_full, &p);          check(pn_mem_level(&p, NULL) == PN_MEM_MILD, "level: full10 1.2 is mild");
    pn_psi_parse(k_psi_severe_some, &p);   check(pn_mem_level(&p, NULL) == PN_MEM_SEVERE, "level: some10 25 is severe");
    pn_psi_parse(k_psi_severe_full, &p);   check(pn_mem_level(&p, NULL) == PN_MEM_SEVERE, "level: full10 5 is severe");

    // levels of meminfo (the 6 GB case: 15 % is about 0.9 GB, 8 % about 0.48 GB)
    m.total_kb = 6000000; m.avail_kb = 2000000; check(pn_mem_level(NULL, &m) == PN_MEM_OK, "level: 33 % available is OK");
    m.avail_kb = 899999;  check(pn_mem_level(NULL, &m) == PN_MEM_MILD, "level: just under 15 % is mild");
    m.avail_kb = 900000;  check(pn_mem_level(NULL, &m) == PN_MEM_OK, "level: exactly 15 % is OK");
    m.avail_kb = 479999;  check(pn_mem_level(NULL, &m) == PN_MEM_SEVERE, "level: just under 8 % is severe");
    m.total_kb = 0;       check(pn_mem_level(NULL, &m) == PN_MEM_OK, "level: unknown total is OK");

    // both: the higher one
    pn_psi_parse(k_psi_calm, &p); m.total_kb = 6000000; m.avail_kb = 300000;
    check(pn_mem_level(&p, &m) == PN_MEM_SEVERE, "level: calm psi, low meminfo -> severe");
    pn_psi_parse(k_psi_severe_some, &p); m.avail_kb = 5000000;
    check(pn_mem_level(&p, &m) == PN_MEM_SEVERE, "level: severe psi, ample meminfo -> severe");
    check(pn_mem_level(NULL, NULL) == PN_MEM_OK, "level: no source is OK");

    // files
    char dir[] = "/tmp/pn_mem_XXXXXX";
    if (!mkdtemp(dir)) { printf("FAIL mkdtemp\n"); return 1; }
    char psi_path[128], mi_path[128], none_path[128];
    snprintf(psi_path, sizeof psi_path, "%s/psi", dir); snprintf(mi_path, sizeof mi_path, "%s/meminfo", dir); snprintf(none_path, sizeof none_path, "%s/none", dir);
    put(psi_path, k_psi_severe_some); put(mi_path, k_mi_12g);
    int src = 0;
    check(pn_mem_read(psi_path, mi_path, &src) == PN_MEM_SEVERE && src == 3, "read: both files, severe from the psi, sources 3");
    check(pn_mem_read(none_path, mi_path, &src) == PN_MEM_OK && src == 2, "read: psi file missing (blocked for apps): meminfo only, sources 2");
    put(psi_path, "not a psi file\n");
    check(pn_mem_read(psi_path, none_path, &src) == PN_MEM_OK && src == 0, "read: garbage and a missing file: OK, sources 0");
    put(mi_path, "MemTotal: 6000000 kB\nMemAvailable: 400000 kB\n");
    check(pn_mem_read(none_path, mi_path, &src) == PN_MEM_SEVERE && src == 2, "read: meminfo alone can say severe");

    // the poll follows the switch
    pn_opt_init("none");
    check(pn_mem_poll() == PN_MEM_OK && pn_mem_last() == PN_MEM_OK, "poll: switch off gives OK and keeps the last");
    unlink(psi_path); unlink(mi_path); rmdir(dir);

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
