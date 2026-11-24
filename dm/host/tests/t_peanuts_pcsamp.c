// PEANUTS: the pure parts of the program-counter sampler (dm/peanuts/peanuts_pcsamp.c), run on a Linux PC: the top blocks (counts, order, ties, limits, bad input) and
// the /proc/self/maps lookup (anonymous maps, named maps, the edges of a range, a miss, garbage), plus a live run of the sampler on this process.
#include "../../peanuts/peanuts_pcsamp.c"
#include <math.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

static const char *k_maps =
    "7f0000000000-7f0000100000 r-xp 00000000 00:00 0                          [anon:nxnative guest]\n"
    "7f1000000000-7f1000010000 r--p 00001000 fd:01 1234                        /data/app/lib/arm64/libnxhost.so\n"
    "7f2000000000-7f2000001000 rw-p 00000000 00:00 0 \n"
    "7f3000000000-7f3000002000 rw-p 00000000 00:00 0                          [stack]";

int main(void) {
    pn_pc_bin t[8];
    uint64_t a[] = { 0x1000, 0x1010, 0x1040, 0x1044, 0x1048, 0x2000, 0x2001, 0x3000 };
    // blocks of 64 bytes: 0x40 (0x1000, 0x1010) 2; 0x41 (0x1040, 0x1044, 0x1048) 3; 0x80 (0x2000, 0x2001) 2; 0xc0 1
    int k = pn_pc_top(a, 8, 6, t, 8);
    check(k == 4 && t[0].block == 0x41 && t[0].count == 3, "top: the busiest block first");
    check(t[1].block == 0x40 && t[1].count == 2 && t[2].block == 0x80 && t[2].count == 2, "top: a tie keeps the lower block first");
    check(t[3].block == 0xc0 && t[3].count == 1, "top: the single sample last");
    k = pn_pc_top(a, 8, 6, t, 2);
    check(k == 2 && t[0].block == 0x41 && t[1].block == 0x40, "top: limited to max_out, the best of them");
    k = pn_pc_top(a, 8, 0, t, 8);
    check(k == 8 && t[0].count == 1, "top: shift 0, all addresses apart");
    k = pn_pc_top(a, 8, 12, t, 8);
    check(k == 3, "top: a 4 KiB shift merges the blocks (pages 1, 2 and 3)");
    check(pn_pc_top(NULL, 3, 6, t, 8) == 0 && pn_pc_top(a, 0, 6, t, 8) == 0 && pn_pc_top(a, 8, 6, t, 0) == 0 && pn_pc_top(a, 8, 64, t, 8) == 0 && pn_pc_top(a, 8, 6, NULL, 8) == 0, "top: bad input gives 0");
    uint64_t same[1000]; for (int i = 0; i < 1000; i++) same[i] = 0xABC000 + (i % 3);
    k = pn_pc_top(same, 1000, 6, t, 8);
    check(k == 1 && t[0].count == 1000, "top: one block holding every sample");
    uint64_t many[500]; for (int i = 0; i < 500; i++) many[i] = (uint64_t)i << 6;
    k = pn_pc_top(many, 500, 6, t, 8);
    check(k == 8 && t[0].count == 1 && t[0].block == 0 && t[7].block == 7, "top: 500 blocks of one sample, the first 8 by block order");

    uint64_t s, e; char nm[64];
    check(pn_maps_find(k_maps, 0x7f0000000040ull, &s, &e, nm, sizeof nm) && s == 0x7f0000000000ull && e == 0x7f0000100000ull && !strcmp(nm, "[anon:nxnative guest]"), "maps: a named anonymous map");
    check(pn_maps_find(k_maps, 0x7f1000000010ull, &s, &e, nm, sizeof nm) && !strcmp(nm, "/data/app/lib/arm64/libnxhost.so"), "maps: a library path");
    check(pn_maps_find(k_maps, 0x7f2000000800ull, &s, &e, nm, sizeof nm) && nm[0] == 0, "maps: an anonymous map with no name has an empty name");
    check(pn_maps_find(k_maps, 0x7f3000001fffull, &s, &e, nm, sizeof nm) && !strcmp(nm, "[stack]"), "maps: the last line without a newline, the last byte of its range");
    check(!pn_maps_find(k_maps, 0x7f3000002000ull, &s, &e, nm, sizeof nm), "maps: the end of a range is outside it");
    check(pn_maps_find(k_maps, 0x7f0000000000ull, &s, &e, nm, sizeof nm), "maps: the first byte of a range is inside it");
    check(!pn_maps_find(k_maps, 0x1234, &s, &e, nm, sizeof nm) && !pn_maps_find(NULL, 0x1234, &s, &e, nm, sizeof nm) && !pn_maps_find("garbage\n\nmore", 5, &s, &e, nm, sizeof nm), "maps: a miss, NULL and garbage give 0");
    char tiny[6]; check(pn_maps_find(k_maps, 0x7f1000000010ull, NULL, NULL, tiny, sizeof tiny) && strlen(tiny) == 5, "maps: the name is cut to the buffer, NULL start and end are fine");

    // a live run: the sampler on this process for about a second, with the report every 2 s
    setenv("DM_PC_SAMPLE", "500", 1); setenv("DM_PC_SAMPLE_DUMP_S", "2", 1);
    pn_pcsamp_start_once(); pn_pcsamp_start_once();
    volatile double x = 0; struct timeval t0, t1; gettimeofday(&t0, NULL);
    do { for (int i = 0; i < 2000000; i++) x += sqrt((double)i); gettimeofday(&t1, NULL); } while ((t1.tv_sec - t0.tv_sec) * 1000000L + (t1.tv_usec - t0.tv_usec) < 1200000L);
    uint32_t h = atomic_load(&g_head);
    check(h > 100 && h < 1500, "live: about 600 samples in 1.2 s of CPU at 500 Hz");
    int nonzero = 0; for (uint32_t i = 0; i < h && i < RING; i++) nonzero += g_ring[i] != 0;
    check(nonzero > 90 * (int)h / 100, "live: the samples carry a program counter");
    check(g_hz == 500 && g_dump_s == 2, "live: the environment was read once (500 Hz, report every 2 s)");

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
