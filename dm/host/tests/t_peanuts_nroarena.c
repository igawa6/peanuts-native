// PEANUTS: the loader as the Android host runs it (the module arena, loader.c arena_reserve) with a pack that has NROs (nro.tsv, as the packer writes one since
// Banana Native v0.3.22): load_modules itself, over a pack of made-up modules written to a folder. t_nro.c is the Mac's and never reserves the arena.
//   1. the game's own modules stand back to back in the arena, in load order (what the arena is for);
//   2. each NRO is mapped, outside the arena (it was mapped into it and found no room: "do not fit in the module arena (0 left)", and the host stopped);
//   3. an address in an NRO is a module's address (module_of: what the thread pause and the crash report ask);
//   4. the pages after the last module are still handed out (dm_module_arena_tail: a companion's helper);
//   5. DM_MODULE_ARENA=0: the same pack loads with every module mapped apart.
// make GAME=<id> peanuts-nro (part of peanuts-host). No game files.
#include "../loader.c"
#include <assert.h>
#include <sys/wait.h>

struct dm_flags g_flag;
uint64_t now_ns(void) { return 0; }
void gate_register(uint64_t a, const char *k, int n, uint64_t o) { (void)a; (void)k; (void)n; (void)o; }
void hlecount_seal(void) {}
void *hlecount_wrap(const char *kind, const char *name, void *fn) { (void)kind; (void)name; return fn; }
uint64_t resolve_import(module_t *m, const char *name) { (void)m; (void)name; return 0; }
int hle_implements(const char *name) { (void)name; return 0; }
int svc_nro_deactivate(module_t *m) { m->loaded = 0; return 1; }
int dm_host_has_pac(void) { return 1; }   // (no instruction of the made-up modules is looked at)
// The platform layer's part (plat_android.c): no forced takeover table, four slots at distinct entries, K18's at 0 as on Android
const void *plat_table(const char *name, unsigned long *size) { (void)name; *size = 0; return NULL; }
void plat_slots_init(void) {}
uint64_t plat_slot_key_entry(unsigned slot) { return 8ull * slot; }
void plat_slot_check(uint64_t k18_entry) { assert(!k18_entry); }
void plat_icache_invalidate(void *p, size_t len) { (void)p; (void)len; }
void plat_sha256_init(plat_sha256_t *c) { (void)c; }
void plat_sha256_update(plat_sha256_t *c, const void *p, size_t n) { (void)c; (void)p; (void)n; }
void plat_sha256_final(uint8_t out[32], plat_sha256_t *c) { (void)c; memset(out, 0, 32); }

static int checks, failed;
#define CHECK(c, ...) do { checks++; if (!(c)) { failed++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } else { printf("ok   "); printf(__VA_ARGS__); printf("\n"); } } while (0)

// A module the loader takes: MOD0 with an empty dynamic section and a symbol table of the null symbol, a key page, a patch area after the image (three 16 KiB pages).
// An NRO's header words at 0x30 / 0x34 are its data offset and the length of its initialized data (preload_nros checks them against the layout)
enum { PAGE = 0x4000, IMG = 3 * PAGE };
static void write_module(const char *dir, const char *name) {
    static uint8_t b[IMG]; memset(b, 0, sizeof b);
    *(uint32_t *)(b + 4) = 0x100; memcpy(b + 0x100, "MOD0", 4); *(int32_t *)(b + 0x104) = 0x100;
    Elf64_Dyn *d = (Elf64_Dyn *)(b + 0x200);
    d[0] = (Elf64_Dyn){.d_tag = DT_SYMTAB, .d_un.d_ptr = 0x300}; d[1] = (Elf64_Dyn){.d_tag = DT_STRTAB, .d_un.d_ptr = 0x300 + sizeof(Elf64_Sym)};
    *(uint32_t *)(b + 0x30) = PAGE; *(uint32_t *)(b + 0x34) = 0;
    char p[1024]; snprintf(p, sizeof p, "%s/%s.patched.bin", dir, name);
    FILE *f = fopen(p, "wb"); assert(f && fwrite(b, 1, sizeof b, f) == sizeof b); fclose(f);
    snprintf(p, sizeof p, "%s/%s.fixups.tsv", dir, name);
    f = fopen(p, "w"); assert(f);
    fprintf(f, "# PATCH_OFF %x\n# KEY_PAGE_OFF %x\n# KEY_PAGE_SIZE %x\n# TEXT_END %x\n# RO_OFF %x\n# DATA_OFF %x\n# BUILD_ID 00000000000000000000000000000000\n# TARGET_ANDROID 1\n",
            2 * PAGE, PAGE, PAGE, PAGE, PAGE, PAGE);
    static const char *kn[] = {"K18", "KTLS", "KTLR", "KGATE"};
    for (int k = 0; k < 4; k++) fprintf(f, "# KEY_%s_IDX 0\t%d\n", kn[k], k);
    fclose(f);
}

static int in_arena(const module_t *m) { return g_arena && m->base >= g_arena && m->base + m->size <= g_arena_end; }

static int load(const char *dir) {
    static const char *names[] = {"main", "sdk"};
    load_modules(dir, names, 2);
    CHECK(g_nmod == 5, "two modules and three NROs are in the module list (%d)", g_nmod);
    if (g_nmod != 5) return 1;
    for (int i = 2; i < 5; i++) {
        module_t *m = &g_mod[i];
        CHECK(m->nro && m->base && m->size == IMG && !m->loaded, "%s is mapped and waits for the game's LoadModule", m->name);
        CHECK(!in_arena(m), "%s is outside the arena", m->name);
        CHECK(module_of(m->base + 0x10) == m && module_of(m->base + m->size - 1) == m, "an address in %s is that module's", m->name);
        CHECK(*(uint32_t *)(m->base + 4) == 0x100 && !memcmp(m->base + 0x100, "MOD0", 4), "%s holds its file's bytes", m->name);
    }
    return 0;
}

int main(int argc, char **argv) {
    char dir[900]; snprintf(dir, sizeof dir, "%s/t_peanuts_nroarena.XXXXXX", argc > 1 ? argv[1] : "/tmp"); assert(mkdtemp(dir));
    write_module(dir, "main"); write_module(dir, "sdk");
    char p[1024]; snprintf(p, sizeof p, "%s/nro.tsv", dir); FILE *f = fopen(p, "w"); assert(f);
    fputs("# name, SHA-256 of the NRO, its size\n", f);
    for (int i = 0; i < 3; i++) { char n[16]; snprintf(n, sizeof n, "nro%d", i); write_module(dir, n); fprintf(f, "%s\t%064x\t%u\n", n, i + 1, 128u); }
    fprintf(f, "refused\t%064x\t%u\n", 9, 128u);
    fclose(f);

    if (argc > 2) {   // the child: the same pack without the arena
        setenv("DM_MODULE_ARENA", "0", 1);
        if (load(dir)) return 1;
        CHECK(!g_arena, "DM_MODULE_ARENA=0: no arena, and the pack loads");
        return failed != 0;
    }
    if (load(dir)) return 1;
    CHECK(g_arena && in_arena(&g_mod[0]) && in_arena(&g_mod[1]), "main and sdk are in the arena");
    CHECK(g_mod[1].base == g_mod[0].base + g_mod[0].size, "sdk follows main with nothing between (%p, %p + %#zx)", (void *)g_mod[1].base, (void *)g_mod[0].base, g_mod[0].size);
    CHECK(g_arena_cur == g_arena_end, "the arena holds the two modules and nothing else");
    CHECK(nro_refused((const uint8_t[32]){[31] = 9}), "the refused row is kept");
    uint8_t *t = dm_module_arena_tail(PAGE);
    CHECK(t == g_arena_end && !t[0] && (t[0] = 1), "the pages after the last module are handed out and writable");
    pid_t c = fork();
    if (!c) { execl(argv[0], argv[0], argc > 1 ? argv[1] : "/tmp", "no-arena", (char *)NULL); _exit(127); }
    int st = 0; waitpid(c, &st, 0);
    CHECK(WIFEXITED(st) && !WEXITSTATUS(st), "the run without the arena passed (status %#x)", st);
    snprintf(p, sizeof p, "rm -rf '%s' '%s'/../t_peanuts_nroarena.??????", dir, dir); if (system(p)) {}
    printf(failed ? "%d of %d checks FAILED\n" : "all %2$d checks passed\n", failed, checks);
    return failed != 0;
}
