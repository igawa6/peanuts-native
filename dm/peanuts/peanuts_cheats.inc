// PEANUTS: code cheats on Android (Mod menu, package A): the bodies of peanuts_cheats.h, included at the end of dm/host/hle_cheats.c, whose statics they use.
//
// What a cheat file asks of main's code (a community cheat file for Pokémon Legends: Z-A: 164 of its lines are instructions written over main, "STR W8" made "STR W9" for money,
// a NOP over a branch for the donut berries) is written here the way peanuts_patches.cpp writes a package's patches: with the game's threads stopped, the page made
// writable, the words stored, the instruction cache flushed, the protection put back. On top of that:
//   - Compare first. A pass (cheats_pass, once a presented frame) queues a code word only when the cheat's value differs from what main holds there (or what an earlier
//     cheat of the same pass queued). A cheat that keeps its code in place costs a compare a pass and never a stop; a key-conditional code cheat (Z-A "Movement Speed
//     x8": 80000100, ZL held, writes one FMOV, its else the other) queues a word only on the pass its key changes.
//   - One stop a pass. Everything a pass queued is written in one stop (dm_guest_stop_all, 20 ms at most). Threads that cannot all be stopped in time (a thread inside a
//     long host call): nothing is written, the next passes try again, waiting 1, 2, 4 ... 64 passes between attempts, so a busy game is not held up every frame.
//   - Several words at once. A run of two or more neighbouring words (an 8-byte write: a code cave and its branch back) is written only when no stopped thread stands
//     inside it (dm_guest_stopped_in: its pc, registers or stack); else it waits for the next pass. A thread at a single word runs the old or the new instruction.
//   - Put back. The first write to a word keeps the word's original bytes. A cheat that stops running (switched off, refused, gone from a reloaded file) gives up its
//     bytes, and those no other running cheat writes go back to the original in the next pass's stop. Atmosphère's "Restore Code" entries are not needed for that.
//     A one-pass cheat ("set" in a meta file) that switched itself off keeps what it wrote: its bytes become the word's original.
//   - The rewriter's sites stay refused, as on the Mac: main_write_check turns a cheat off with the reason when it writes a word main.changed.tsv lists (an SVC site, a
//     cut, a moved window), before anything is queued. The other cheats run.
// Inside the stop nothing allocates, logs or takes a lock: a stopped game thread may hold the allocator's. Everything here runs under hle_cheats.c's g_lock.
// The launcher reads each cheat's state (nxhost_cheat_status, Native.cheatStatus) and reloads the file while the game runs (nxhost_cheats_reload, Native.cheatsReload).
#if PN_CODE

int dm_guest_stop_all(uint64_t timeout_ns);   // hle_thread.c (PEANUTS): every game thread stopped, G held until the resume; 0, or -1 / -2 when not in time
void dm_guest_resume_all(void);
int dm_guest_stopped_in(uint64_t lo, uint64_t hi);   // between the two: game threads that may be executing in [lo, hi) or come back into it

#define PN_STOP_NS 20000000ull   // the longest a pass waits for the game's threads to stop
#define PN_QMAX 2048             // code words written in one stop (more wait for the next pass)
#define PN_PAGES 256             // pages made writable in one stop
#define PN_REC_MAX 65536         // code words cheats may have written in one run

// A word of main's text some cheat wrote: its original bytes and which cheat writes which of its bytes (bit l of a mask = byte l of the word)
typedef struct {
    uint64_t addr;            // 4-aligned
    uint32_t orig;            // the word before the first cheat wrote it
    int qi;                   // its entry in this pass's queue, -1 none
    uint8_t uni;              // the bytes some running cheat writes (the union of own)
    uint8_t pend;             // bytes no running cheat writes any more that still have to go back to orig
    uint8_t own[CHEAT_MAX];   // per cheat (its index in g_cheat): the bytes it writes
} pn_rec;
static pn_rec *g_pn_rec; static int g_pn_nrec, g_pn_caprec;   // sorted by addr
static int g_pn_owns[CHEAT_MAX];      // per cheat: records it owns bytes of (its code is in main, or queued)
static uint8_t g_pn_ever[CHEAT_MAX];  // per cheat: it wrote code since the file was loaded
static uint8_t g_pn_wait[CHEAT_MAX];  // per cheat: a word of it waits for a later stop (1: the threads did not stop, 2: a thread stood inside)
typedef struct { uint64_t addr; uint32_t word; uint8_t skip; } pn_q;
static pn_q g_pn_q[PN_QMAX]; static int g_pn_nq;
static int g_pn_backoff, g_pn_backoff_len;   // passes left to wait before the next stop, and the current wait
static int g_pn_said_stop;                   // the "threads did not stop" line was logged for the current streak
static uint64_t g_pn_stops, g_pn_words;      // stops made and words written this run (the status line)

static void pn_load_after(void);   // (below)
static uint32_t pn_mem32(uint64_t a) { return __atomic_load_n((uint32_t *)(uintptr_t)a, __ATOMIC_RELAXED); }
static uint32_t pn_bits(uint8_t m) { uint32_t b = 0; for (int l = 0; l < 4; l++) if (m & (1u << l)) b |= 0xFFu << (8 * l); return b; }

// The record of word a, or NULL; with add, made (its original read from main) when there is none
static pn_rec *pn_rec_get(uint64_t a, int add) {
    int lo = 0, hi = g_pn_nrec;
    while (lo < hi) { int mid = (lo + hi) / 2; if (g_pn_rec[mid].addr < a) lo = mid + 1; else hi = mid; }
    if (lo < g_pn_nrec && g_pn_rec[lo].addr == a) return &g_pn_rec[lo];
    if (!add || g_pn_nrec >= PN_REC_MAX) return NULL;
    if (g_pn_nrec == g_pn_caprec) {
        int cap = g_pn_caprec ? g_pn_caprec * 2 : 64;
        pn_rec *grown = realloc(g_pn_rec, (size_t)cap * sizeof *grown);
        if (!grown) return NULL;
        g_pn_rec = grown; g_pn_caprec = cap;
    }
    memmove(&g_pn_rec[lo + 1], &g_pn_rec[lo], (size_t)(g_pn_nrec - lo) * sizeof *g_pn_rec);
    g_pn_nrec++;   // (the queue's entries name their word by address, and each record carries its own qi along: nothing else moves)
    pn_rec *r = &g_pn_rec[lo];
    memset(r, 0, sizeof *r);
    r->addr = a; r->orig = pn_mem32(a); r->qi = -1;
    return r;
}

// Word a of record r becomes word in this pass's queue (an entry it has is replaced)
static void pn_queue(pn_rec *r, uint32_t word) {
    if (r->qi >= 0) { g_pn_q[r->qi].word = word; return; }
    if (g_pn_nq >= PN_QMAX) return;   // the rest waits: the next pass compares and queues it again (a restore stays pending)
    g_pn_q[g_pn_nq] = (pn_q){r->addr, word, 0};
    r->qi = g_pn_nq++;
}

// Forget every word and the stop's state (t_cheats.c, between its cases: a file loaded by load_all alone, not by a reload, would meet records of the last file's cheats)
static void pn_forget(void) {
    g_pn_nrec = 0; g_pn_nq = 0; g_pn_backoff = g_pn_backoff_len = g_pn_said_stop = 0;
    memset(g_pn_owns, 0, sizeof g_pn_owns); memset(g_pn_ever, 0, sizeof g_pn_ever); memset(g_pn_wait, 0, sizeof g_pn_wait);
}

// Does cheat c run in a pass now (pass_runs: on, or a master outside safe mode; not broken, dead or a label)?
static int pn_runs(const cheat_t *c) { return pass_runs(c, c->master ? 0 : 1); }

// Cheat ci gives up every byte it writes. Bytes no other running cheat writes go back to the word's original at the next stop (restore), or, for a one-pass cheat
// that turned itself off, stay as they are and become the original (it set them for good)
static void pn_release(int ci, int restore) {
    int put = 0;
    for (int k = 0; k < g_pn_nrec && g_pn_owns[ci]; k++) {
        pn_rec *r = &g_pn_rec[k];
        uint8_t m = r->own[ci];
        if (!m) continue;
        r->own[ci] = 0; g_pn_owns[ci]--;
        uint8_t uni = 0; for (int j = 0; j < CHEAT_MAX; j++) uni |= r->own[j];
        r->uni = uni;
        uint8_t lost = m & ~uni;
        if (!lost) continue;
        if (restore) { r->pend |= lost; put++; }
        else { uint32_t b = pn_bits(lost); r->orig = (r->orig & ~b) | (pn_mem32(r->addr) & b); }
    }
    g_pn_owns[ci] = 0; g_pn_wait[ci] = 0;
    if (put) LOG("cheats: \"%s\" stopped running: %d code word%s go%s back to the game's own", g_cheat[ci].name, put, put == 1 ? "" : "s", put == 1 ? "es" : "");
}

static void pn_code_write(cheat_t *c, uint64_t addr, uint64_t len, uint64_t val) {
    int ci = (int)(c - g_cheat);
    if (ci < 0 || ci >= CHEAT_MAX || !len || len > 8) return;
    uint8_t b[8]; memcpy(b, &val, sizeof b);   // little-endian, as the guest and the host are
    for (uint64_t k = 0; k < len; ) {
        uint64_t w = (addr + k) & ~3ull;
        uint32_t nb = 0, bm = 0; uint8_t m = 0;
        for (; k < len && ((addr + k) & ~3ull) == w; k++) { unsigned l = (unsigned)((addr + k) & 3); m |= (uint8_t)(1u << l); nb |= (uint32_t)b[k] << (8 * l); bm |= 0xFFu << (8 * l); }
        pn_rec *r = pn_rec_get(w, 1);
        if (!r) { cheat_kill(c, "more than %d code words written by cheats", PN_REC_MAX); return; }
        if (!r->own[ci]) g_pn_owns[ci]++;
        r->own[ci] |= m; r->uni |= m; r->pend &= (uint8_t)~m; g_pn_ever[ci] = 1;
        uint32_t cur = r->qi >= 0 ? g_pn_q[r->qi].word : pn_mem32(w), want = (cur & ~bm) | nb;
        if (want != cur) pn_queue(r, want);
    }
}

static void pn_code_read(uint64_t addr, uint64_t len, uint64_t *v) {
    if (!g_pn_nq) return;
    for (uint64_t k = 0; k < len && k < 8; k++) {
        uint64_t a = addr + k;
        pn_rec *r = pn_rec_get(a & ~3ull, 0);
        if (!r || r->qi < 0) continue;
        uint64_t byte = (g_pn_q[r->qi].word >> (8 * (a & 3))) & 0xFF;
        *v = (*v & ~(0xFFull << (8 * k))) | byte << (8 * k);
    }
}

// The queue emptied: its words written, or left for the next pass to compare and queue again (a restore stays pending in its record)
static void pn_queue_clear(void) {
    for (int k = 0; k < g_pn_nq; k++) { pn_rec *r = pn_rec_get(g_pn_q[k].addr, 0); if (r) r->qi = -1; }
    g_pn_nq = 0;
}

static void pn_pass_begin(void) {
    pn_load_after();
    pn_queue_clear();
    for (int i = 0; i < g_ncheat && i < CHEAT_MAX; i++)
        if (g_pn_owns[i] && !pn_runs(&g_cheat[i])) pn_release(i, !(g_cheat[i].once && !g_cheat[i].on && !g_cheat[i].dead));
    for (int k = 0; k < g_pn_nrec; k++) {   // the bytes to put back, before this pass's cheats write theirs over them
        pn_rec *r = &g_pn_rec[k];
        r->pend &= (uint8_t)~r->uni;
        if (!r->pend) continue;
        uint32_t cur = pn_mem32(r->addr), b = pn_bits(r->pend), want = (cur & ~b) | (r->orig & b);
        if (want == cur) r->pend = 0; else pn_queue(r, want);
    }
}

static int pn_q_cmp(const void *a, const void *b) { uint64_t x = ((const pn_q *)a)->addr, y = ((const pn_q *)b)->addr; return x < y ? -1 : x > y; }

// The cheats owning bytes of word a get state why (1 / 2, g_pn_wait) for the status
static void pn_wait_mark(uint64_t a, uint8_t why) {
    pn_rec *r = pn_rec_get(a, 0);
    if (r) for (int i = 0; i < g_ncheat && i < CHEAT_MAX; i++) if (r->own[i]) g_pn_wait[i] = why;
}

static void pn_pass_end_queued(void);
static void pn_pass_end(void) {
    // A word whose last queued value is what main holds needs no write (one cheat put back what another of the same pass writes again: "Restore Code" left on
    // beside the cheat it restores): without this filter such a pair would cost a stop every pass
    int n = 0;
    for (int k = 0; k < g_pn_nq; k++) {
        pn_rec *r = pn_rec_get(g_pn_q[k].addr, 0);
        if (!r) continue;
        if (g_pn_q[k].word == pn_mem32(g_pn_q[k].addr)) { r->qi = -1; r->pend = 0; continue; }   // (pending bytes no cheat writes now hold the original then)
        g_pn_q[n] = g_pn_q[k]; r->qi = n++;
    }
    g_pn_nq = n;
    if (g_pn_nq) pn_pass_end_queued();
    pn_queue_clear();
}
static void pn_pass_end_queued(void) {
    if (g_pn_backoff > 0) { g_pn_backoff--; for (int k = 0; k < g_pn_nq; k++) pn_wait_mark(g_pn_q[k].addr, 1); return; }
    qsort(g_pn_q, (size_t)g_pn_nq, sizeof *g_pn_q, pn_q_cmp);
    for (int k = 0; k < g_pn_nq; k++) { pn_rec *r = pn_rec_get(g_pn_q[k].addr, 0); if (r) r->qi = k; }
    // The pages and their protections, read before the stop (plat_mem_region reads /proc/self/maps, which allocates); main's text is normally one mapping, read once
    static uint64_t pg_at[PN_PAGES]; static int pg_prot[PN_PAGES];
    int npg = 0, nw = g_pn_nq;
    uint64_t ps = (uint64_t)sysconf(_SC_PAGESIZE), reg_lo = 0, reg_hi = 0; int reg_prot = 0;
    for (int k = 0; k < g_pn_nq; k++) {
        uint64_t pg = g_pn_q[k].addr & ~(ps - 1);
        if (npg && pg_at[npg - 1] == pg) continue;
        if (npg == PN_PAGES) { nw = k; break; }   // the rest waits for the next pass
        if (pg < reg_lo || pg >= reg_hi) {
            uint64_t st = 0, sz = 0; int pr = 0;
            if (plat_mem_region(pg, &st, &sz, &pr) || st > pg || !(pr & PROT_READ)) { reg_lo = reg_hi = 0; pr = -1; }
            else { reg_lo = st; reg_hi = st + sz; }
            reg_prot = pr;
        }
        pg_at[npg] = pg; pg_prot[npg] = reg_prot; npg++;
    }
    for (int k = nw; k < g_pn_nq; k++) g_pn_q[k].skip = 1;
    uint64_t t0 = now_ns();
    int r = dm_guest_stop_all(PN_STOP_NS);
    if (r) {
        g_pn_backoff_len = g_pn_backoff_len ? (g_pn_backoff_len < 64 ? g_pn_backoff_len * 2 : 64) : 1;
        g_pn_backoff = g_pn_backoff_len;
        for (int k = 0; k < g_pn_nq; k++) pn_wait_mark(g_pn_q[k].addr, 1);
        if (!g_pn_said_stop++) LOG("[RED] cheats: %d code word%s not written yet: the game's threads %s within %llu ms; tried again in %d pass%s, then less often", g_pn_nq,
                                   g_pn_nq == 1 ? "" : "s", r == -2 ? "were being stopped by someone else" : "did not all stop", (unsigned long long)(PN_STOP_NS / 1000000), g_pn_backoff, g_pn_backoff == 1 ? "" : "es");
        return;
    }
    // ---- the game's threads are stopped: no allocation, no log, no lock from here to the resume ----
    int bad_errno = 0, inside = 0;
    for (int k = 0; k < nw; ) {   // runs of neighbouring words: written only while no stopped thread stands in them
        int e = k + 1;
        while (e < nw && g_pn_q[e].addr == g_pn_q[e - 1].addr + 4) e++;
        if (e - k > 1 && dm_guest_stopped_in(g_pn_q[k].addr, g_pn_q[e - 1].addr + 4) > 0) { for (int j = k; j < e; j++) g_pn_q[j].skip = 2; inside++; }
        k = e;
    }
    for (int p = 0; p < npg; p++) {
        if (pg_prot[p] < 0) { bad_errno = bad_errno ? bad_errno : EFAULT; continue; }
        int want = 0;
        for (int k = 0; k < nw && !want; k++) want = !g_pn_q[k].skip && (g_pn_q[k].addr & ~(ps - 1)) == pg_at[p];
        if (!want) { pg_prot[p] = -2; continue; }   // nothing of this page is written: its protection stays untouched
        if (pg_prot[p] & PROT_WRITE) continue;
        // Writable and still executable (what peanuts_patches.cpp does), so a thread the stop leaves running (the host's own) can still run the page; read + write
        // alone only when the system refuses both at once, the game's threads being stopped
        if (mprotect((void *)(uintptr_t)pg_at[p], ps, pg_prot[p] | PROT_WRITE) && mprotect((void *)(uintptr_t)pg_at[p], ps, PROT_READ | PROT_WRITE)) {
            bad_errno = errno ? errno : EACCES; pg_prot[p] = -3;
        }
    }
    int written = 0;
    for (int k = 0; k < nw; k++) {
        if (g_pn_q[k].skip) continue;
        uint64_t pg = g_pn_q[k].addr & ~(ps - 1); int ok = 0;
        for (int p = 0; p < npg; p++) if (pg_at[p] == pg) { ok = pg_prot[p] >= 0; break; }
        if (!ok) { g_pn_q[k].skip = 3; continue; }
        __atomic_store_n((uint32_t *)(uintptr_t)g_pn_q[k].addr, g_pn_q[k].word, __ATOMIC_RELEASE);
        written++;
    }
    for (int p = 0; p < npg; p++) if (pg_prot[p] >= 0 && !(pg_prot[p] & PROT_WRITE)) mprotect((void *)(uintptr_t)pg_at[p], ps, pg_prot[p]);   // as it was
    for (int k = 0; k < nw; ) {
        int e = k + 1;
        while (e < nw && g_pn_q[e].addr == g_pn_q[e - 1].addr + 4) e++;
        __builtin___clear_cache((char *)(uintptr_t)g_pn_q[k].addr, (char *)(uintptr_t)(g_pn_q[e - 1].addr + 4));
        k = e;
    }
    dm_guest_resume_all();
    // ---- the game runs again ----
    uint64_t us = (now_ns() - t0) / 1000;
    g_pn_backoff_len = 0; g_pn_said_stop = 0; g_pn_stops++; g_pn_words += (uint64_t)written;
    char who[200] = ""; size_t wl = 0; int nwho = 0, back = 0;   // the log line names the cheats whose words went in, and counts the words put back
    static uint8_t named[CHEAT_MAX]; memset(named, 0, sizeof named);
    for (int k = 0; k < g_pn_nq; k++) {
        pn_rec *rr = pn_rec_get(g_pn_q[k].addr, 0);
        if (!rr) continue;
        if (!g_pn_q[k].skip) {
            rr->pend = 0; int owned = 0;
            for (int i = 0; i < g_ncheat && i < CHEAT_MAX; i++) {
                if (!rr->own[i]) continue;
                g_pn_wait[i] = 0; owned = 1;
                if (named[i]++) continue;
                if (nwho++ < 3) wl += (size_t)snprintf(who + wl, wl < sizeof who ? sizeof who - wl : 0, "%s\"%s\"", nwho > 1 ? ", " : "", g_cheat[i].name);
                if (wl >= sizeof who) wl = sizeof who - 1;
            }
            back += !owned;
        }
        else if (g_pn_q[k].skip <= 2) pn_wait_mark(rr->addr, g_pn_q[k].skip == 2 ? 2 : 1);
    }
    if (written) LOG("cheats: %d code word%s written (%s%s%s%d put back) with the game's threads stopped for %llu.%03llu ms", written, written == 1 ? "" : "s",
                     who, nwho > 3 ? " and others" : "", nwho ? ", " : "", back, (unsigned long long)(us / 1000), (unsigned long long)(us % 1000));
    if (inside) LOG("cheats: %d run%s of code words left for a later pass: a game thread stands inside", inside, inside == 1 ? "" : "s");
    if (bad_errno) {   // the pages cannot be written here: the cheats that wanted them stop, with the reason
        for (int k = 0; k < g_pn_nq; k++) {
            if (g_pn_q[k].skip != 3) continue;
            pn_rec *rr = pn_rec_get(g_pn_q[k].addr, 0);
            if (!rr) continue;
            for (int i = 0; i < g_ncheat && i < CHEAT_MAX; i++)
                if (rr->own[i]) cheat_kill(&g_cheat[i], "main's code page at main+0x%llX cannot be made writable (%s)", (unsigned long long)(rr->addr - (uint64_t)(uintptr_t)g_main_mod->base), strerror(bad_errno));
            rr->pend = 0;
        }
    }
}

// A load (load_all: at start, the Mac's "Reload cheats", nxhost_cheats_reload) replaces the cheats, and with them the indexes the records name their owners by.
// pn_load_before notes each cheat's key before; pn_load_after (the next pass, status or reload, after load_all returned) gives each word's bytes to the cheat of the
// same key in the new file, and a cheat that is gone gives its bytes back (they go back at the next stop)
static char g_pn_key0[CHEAT_MAX][CHEAT_NAME_MAX + 16]; static uint8_t g_pn_on0[CHEAT_MAX], g_pn_ever0[CHEAT_MAX]; static int g_pn_n0, g_pn_remap;
static void pn_load_after(void);
static void pn_load_before(void) {
    pn_load_after();   // two loads in a row: the records go to the first one's cheats before they are noted
    g_pn_n0 = g_ncheat < CHEAT_MAX ? g_ncheat : CHEAT_MAX;
    for (int i = 0; i < g_pn_n0; i++) {
        cheat_key(&g_cheat[i], g_pn_key0[i], sizeof g_pn_key0[i]);
        g_pn_on0[i] = g_cheat[i].on && toggles(&g_cheat[i]) && !g_cheat[i].once && !g_cheat[i].restore_once;
        g_pn_ever0[i] = g_pn_ever[i];
    }
    g_pn_remap = 1;
}
static int g_pn_map[CHEAT_MAX];   // after pn_load_after: the new index of the cheat noted at i, -1 gone
static void pn_load_after(void) {
    if (!g_pn_remap) return;
    g_pn_remap = 0;
    for (int i = 0; i < g_pn_n0; i++) {
        g_pn_map[i] = -1;
        for (int j = 0; j < g_ncheat && j < CHEAT_MAX && g_pn_map[i] < 0; j++) { char k[CHEAT_NAME_MAX + 16]; cheat_key(&g_cheat[j], k, sizeof k); if (!strcmp(k, g_pn_key0[i])) g_pn_map[i] = j; }
    }
    static uint8_t own[CHEAT_MAX];
    memset(g_pn_owns, 0, sizeof g_pn_owns); memset(g_pn_ever, 0, sizeof g_pn_ever); memset(g_pn_wait, 0, sizeof g_pn_wait);
    for (int i = 0; i < g_pn_n0; i++) if (g_pn_map[i] >= 0) g_pn_ever[g_pn_map[i]] |= g_pn_ever0[i];
    for (int k = 0; k < g_pn_nrec; k++) {
        pn_rec *r = &g_pn_rec[k];
        memset(own, 0, sizeof own);
        uint8_t uni = 0;
        for (int i = 0; i < g_pn_n0; i++) if (r->own[i] && g_pn_map[i] >= 0) { own[g_pn_map[i]] |= r->own[i]; uni |= r->own[i]; }
        r->pend |= (uint8_t)(r->uni & ~uni);
        memcpy(r->own, own, sizeof own); r->uni = uni; r->qi = -1;
        for (int j = 0; j < CHEAT_MAX; j++) if (own[j]) g_pn_owns[j]++;
    }
    g_pn_nq = 0;
}

// ---- the launcher's side: the state of every cheat, and a reload that keeps the switches ----

// A field of the status text and the character after it: tabs and line breaks inside the field become spaces. o counts the whole text, out keeps what fits
static size_t pn_put(char *out, size_t cap, size_t o, const char *s, char end) {
    for (; *s; s++) { char ch = *s == '\t' || *s == '\n' || *s == '\r' ? ' ' : *s; if (o + 1 < cap) out[o] = ch; o++; }
    if (end) { if (o + 1 < cap) out[o] = end; o++; }
    return o;
}

// The reason text of a cheat without hle_cheats.c's wrapping: " (off: x)" -> "x", " (x)" -> "x"
static void pn_why(const cheat_t *c, char *out, size_t n) {
    const char *w = c->why; size_t L;
    while (*w == ' ') w++;
    if (*w == '(') w++;
    if (!strncmp(w, "off: ", 5)) w += 5;
    snprintf(out, n, "%s", w);
    L = strlen(out);
    if (L && out[L - 1] == ')') out[L - 1] = 0;
}

// Does the cheat wait on a button (opcode 8 or C4 anywhere in its program)? Decoded with a scratch cheat, so the cheat itself is never touched
static int pn_holds(const cheat_t *c) {
    static cheat_t probe;   // the decoder's scratch (it writes why / broken on a failure); callers hold g_lock
    if (c->broken || c->label) return 0;
    for (int a = 0; a < c->nins;) {
        ins_t in;
        int len = ins_dec(&probe, c->ins + a, c->nins - a, &in);
        if (len <= 0) return 0;
        if (in.op == 8 || in.op == 0xC4) return 1;
        a += len;
    }
    return 0;
}

// The reason field: "" or "<token>:<detail>". The tokens the launcher maps to a player's words: rewritten (a word the rewriter changed or moved), nolist (no
// main.changed.tsv: code writes refused), region (a write past main's image), unsupported (an opcode or form this host does not run), other; on a running cheat
// access (accesses that failed, a pointer not set yet) or wait (a code write waits for a stop of the game's threads). The detail is the host's own text
static void pn_reason(const cheat_t *c, int ci, char *out, size_t n) {
    char why[160] = "";
    out[0] = 0;
    if (c->label) return;
    if (c->broken || c->dead || c->noted) pn_why(c, why, sizeof why);
    const char *tok = NULL;
    if (c->broken) tok = !strncmp(why, "malformed", 9) || !strncmp(why, "no code words", 13) ? "other" : "unsupported";
    else if (c->dead) tok = strstr(why, "rewriter changed or moved") ? "rewritten" : strstr(why, "main.changed.tsv") ? "nolist" : strstr(why, "past the game's image") ? "region" : "other";
    else if (ci < CHEAT_MAX && g_pn_wait[ci] && pn_runs(c)) {
        snprintf(out, n, "wait:%s", g_pn_wait[ci] == 2 ? "a game thread stands inside the code words" : "the game's threads did not stop in time");
        return;
    } else if (c->noted) tok = "access";
    if (tok) snprintf(out, n, "%s:%s", tok, why);
}

// The status (Native.cheatStatus): a line per entry of the loaded file in its order, labels too: "<key>\t<state>\t<flags>\t<reason>\n". key: as <build>.enabled
// and nxhost_cheat_set have it ("Name", "Name #2"). state: on, off, master, label, refused (turned off while the game ran: a rewritten site, a write past the image,
// a runaway loop), unsupported (never runs: an opcode this host does not run, a malformed line). flags, a comma list: code (it wrote main's code this run), hold (it
// waits on a button: opcode 8 or C4), wait (a code write of it waits for a stop); a reader ignores a flag it does not know. reason: pn_reason. "" with no file
// loaded. Returns the length of the whole text; out holds as much of it as fits, NUL-terminated
__attribute__((visibility("default"))) size_t nxhost_cheat_status(char *out, size_t cap) {
    if (!out || !cap) return 0;
    pthread_mutex_lock(&g_lock);
    pn_load_after();
    size_t o = 0;
    for (int i = 0; i < g_ncheat && g_path[0]; i++) {
        const cheat_t *c = &g_cheat[i];
        int ci = i < CHEAT_MAX ? i : CHEAT_MAX - 1;
        char key[CHEAT_NAME_MAX + 16], flags[32] = "", reason[200];
        cheat_key(c, key, sizeof key);
        const char *state = c->label ? "label" : c->broken ? "unsupported" : c->dead ? "refused" : c->master ? "master" : c->on ? "on" : "off";
        int code = g_pn_ever[ci], hold = pn_holds(c), wait = g_pn_wait[ci] && pn_runs(c);
        snprintf(flags, sizeof flags, "%s%s%s%s%s", code ? "code" : "", code && hold ? "," : "", hold ? "hold" : "", (code || hold) && wait ? "," : "", wait ? "wait" : "");
        pn_reason(c, ci, reason, sizeof reason);
        o = pn_put(out, cap, o, key, '\t');
        o = pn_put(out, cap, o, state, '\t');
        o = pn_put(out, cap, o, flags, '\t');
        o = pn_put(out, cap, o, reason, '\n');
    }
    out[o < cap ? o : cap - 1] = 0;
    pthread_mutex_unlock(&g_lock);
    return o;
}

// Read the cheats folder again (the launcher rewrote the merged <build>.txt and <build>.enabled while the game runs: a cheat imported in the game). A cheat that was
// loaded keeps its switch by key, whatever <build>.enabled says; a new key takes <build>.enabled's state (off when the file does not name it). A key that is gone
// gives its code back at the next pass. A file that was loaded and cannot be read now leaves everything as it was. Returns the entries now loaded (labels too), -1
// when there is no readable file for this build (the old list stays)
static int pn_reload_locked(void) {
    if (g_path[0]) {
        FILE *f = fopen(g_path, "rb");
        if (!f) { LOG("[RED] cheats: cannot read %s again (errno %d): the cheats stay as they were", g_path, errno); return -1; }
        fclose(f);
    }
    load_all();   // (pn_load_before ran inside it)
    pn_load_after();
    for (int j = 0; j < g_ncheat; j++) {
        cheat_t *c = &g_cheat[j];
        if (!toggles(c)) continue;
        for (int i = 0; i < g_pn_n0; i++) if (g_pn_map[i] == j) { c->on = g_pn_on0[i]; c->restore_once = 0; break; }
    }
    states_save();
    if (!atomic_load(&g_active)) { pn_pass_begin(); pn_pass_end(); }   // no pass runs without a cheat: the code of the ones gone goes back now
    LOG("cheats: reloaded %s: %d entries, the switches of the cheats already loaded kept by name", g_path[0] ? g_path : "(no file for this build)", g_ncheat);
    return g_path[0] ? g_ncheat : -1;
}
__attribute__((visibility("default"))) int nxhost_cheats_reload(void) {
    pthread_mutex_lock(&g_lock);
    int r = pn_reload_locked();
    pthread_mutex_unlock(&g_lock);
    return r;
}

#else   // no PN_CODE: the hooks are never reached (their call sites test PN_CODE), these keep them compiling
static void pn_code_write(cheat_t *c, uint64_t addr, uint64_t len, uint64_t val) { (void)c; (void)addr; (void)len; (void)val; }
static void pn_code_read(uint64_t addr, uint64_t len, uint64_t *v) { (void)addr; (void)len; (void)v; }
static void pn_pass_begin(void) {}
static void pn_pass_end(void) {}
static void pn_load_before(void) {}
#endif
