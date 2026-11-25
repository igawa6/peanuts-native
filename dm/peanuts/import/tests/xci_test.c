// xci_test: a game card's XCI with the partitions a card has (GitHub issue #280). The fixture writes a card whose root HFS0 lists one partition, "secure"
// (nsp_fixture.h). A card's root also lists "update" (the system update's NCAs), "normal" and, on cards for firmware 4.0.0 and later, "logo"; normal (older
// cards) or logo (newer ones) hold a second meta NCA and the icon NCA under the secure partition's names. The importer takes the game from the secure
// partition only, so a copy outside it that does not read as an NCA (here: made-up bytes under the meta NCA's name) fails nothing, in either order of the
// partitions, in the library's probe as in the import. Without a secure partition every partition but update is read, as before. Each .nup must be the
// one the fixture's secure-only card gives, byte for byte. No game data: made-up keys and files.
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

static const uint64_t TID = 0x0100BBBB0000E000ull;
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
static uint32_t r32(const uint8_t *p) { uint32_t v; memcpy(&v, p, 4); return v; }
static uint64_t r64(const uint8_t *p) { uint64_t v; memcpy(&v, p, 8); return v; }

// ---- the fixture's card taken apart: its secure partition as it is (an HFS0 with its files), and its meta NCA's name
static FxBuf secure; static char meta[64];
static void take_apart(const char *xci) {
    size_t n; uint8_t *b = slurp(xci, &n); if (!b) abort();
    uint64_t root = r64(b + 0x130); uint32_t rn = r32(b + root + 4), rss = r32(b + root + 8);
    uint64_t data = root + 0x10 + 0x40ull * rn + rss;
    for (uint32_t i = 0; i < rn; i++) {
        const uint8_t *e = b + root + 0x10 + 0x40 * i;
        if (strcmp((const char *)b + root + 0x10 + 0x40 * rn + r32(e + 0x10), "secure")) continue;
        fx_put(&secure, b + data + r64(e), (size_t)r64(e + 8));
    }
    if (!secure.n) abort();
    uint32_t sn = r32(secure.p + 4), sss = r32(secure.p + 8);
    for (uint32_t i = 0; i < sn; i++) {
        const char *name = (const char *)secure.p + 0x10 + 0x40 * sn + r32(secure.p + 0x10 + 0x40 * i + 0x10); size_t l = strlen(name);
        if (l > 9 && !strcmp(name + l - 9, ".cnmt.nca")) snprintf(meta, sizeof meta, "%s", name);
    }
    (void)sss;
    if (!meta[0]) abort();
    free(b);
}

// ---- a partition (an HFS0) of made-up files: names, and sizes of bytes that do not read as an NCA
static void junk_partition(FxBuf *out, const char **names, int n) {
    FxEnt e[4]; uint8_t *p[4];
    for (int i = 0; i < n; i++) { snprintf(e[i].name, sizeof e[i].name, "%s", names[i]); p[i] = fx_bytes(0x4000); e[i].p = p[i]; e[i].n = 0x4000; }
    fx_fs0(e, n, 1, out);
    for (int i = 0; i < n; i++) free(p[i]);
}

// A card: the header ("HEAD" @0x100, the root HFS0's offset @0x130) and its root with these partitions, in this order
typedef struct Part { const char *name; FxBuf *body; } Part;
static void card(const char *path, const Part *parts, int n) {
    FxBuf file = {0}; FxEnt e[6]; uint8_t ch[0x400] = {0};
    memcpy(ch + 0x100, "HEAD", 4); fx_w64(ch + 0x130, 0x400); fx_put(&file, ch, sizeof ch);
    for (int i = 0; i < n; i++) { snprintf(e[i].name, sizeof e[i].name, "%s", parts[i].name); e[i].p = parts[i].body->p; e[i].n = parts[i].body->n; }
    fx_fs0(e, n, 1, &file);
    fx_write(path, file.p, file.n); free(file.p);
}

static int run(const char *out, const char **in, int n) {
    char t[20]; snprintf(t, sizeof t, "%016llx", (unsigned long long)TID);
    nximport_cancel(0); unlink(out);
    return nximport_write_nup(out, in, n, t, NULL, NULL);
}

// The probe lists the game and nothing else, and the import with the update gives the reference .nup
static void reads(const char *what, const char *xci, const char *upd, const char *ref) {
    NxImportInfo info[8]; int count = 0; char want[20]; snprintf(want, sizeof want, "%016llx", (unsigned long long)TID);
    int rc = nximport_probe(xci, info, 8, &count);
    CHECK(!rc && count == 1 && !strcmp(info[0].title_id, want) && info[0].kind == 0, "%s: the probe finds the game (%s)", what, rc ? nximport_last_error() : "one title");
    char out[700]; snprintf(out, sizeof out, "%s/case.nup", dir);
    const char *in[] = { xci, upd };
    rc = run(out, in, 2);
    CHECK(!rc && same_file(out, ref), "%s: the .nup is the secure-only card's byte for byte%s%s", what, rc ? ": " : "", rc ? nximport_last_error() : "");
}

static int rm_tree(const char *p) {   // the test's folder
    DIR *d = opendir(p); if (!d) return unlink(p);
    struct dirent *e; char q[1200];
    while ((e = readdir(d))) { if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue; snprintf(q, sizeof q, "%s/%s", p, e->d_name); rm_tree(q); }
    closedir(d); return rmdir(p);
}

int main(void) {
    const char *td = getenv("TMPDIR"); char keys[600], ref[700], path[700]; FxKeys k; FxGame g;
    snprintf(dir, sizeof dir, "%s/xci_test-XXXXXX", td && *td ? td : "/tmp");
    if (!mkdtemp(dir)) { perror(dir); return 2; }
    snprintf(keys, sizeof keys, "%s/prod.keys", dir); fx_keys_make(&k, keys);
    if (nximport_keys(keys)) { printf("the fixture's keys do not load: %s\n", nximport_last_error()); return 2; }
    fx_game(&k, dir, "card", TID, 0, 1, &g);
    snprintf(ref, sizeof ref, "%s/ref.nup", dir);
    { const char *in[] = { g.base, g.upd }; CHECK(!run(ref, in, 2), "the fixture's card (one secure partition) and its update: the reference .nup (%s)", nximport_last_error()); }
    take_apart(g.base);

    // the partitions a card has beside secure: the system update, an empty normal, a logo with the meta NCA's name over bytes that are no NCA
    FxBuf update = {0}, normal = {0}, logo = {0};
    { const char *u[] = { "00000000000000000000000000000816.cnmt.nca", "00000000000000000000000000000817.nca" }; junk_partition(&update, u, 2); }
    fx_fs0(NULL, 0, 1, &normal);
    { const char *l[] = { meta, "0000000000000000000000000000c0de.nca" }; junk_partition(&logo, l, 2); }

    printf("1 a card for firmware 4.0.0 and later: update, normal, secure, logo\n");
    { Part p[] = { { "update", &update }, { "normal", &normal }, { "secure", &secure }, { "logo", &logo } };
      snprintf(path, sizeof path, "%s/newer.xci", dir); card(path, p, 4); reads("logo after secure", path, g.upd, ref); }
    { Part p[] = { { "update", &update }, { "logo", &logo }, { "normal", &normal }, { "secure", &secure } };
      snprintf(path, sizeof path, "%s/newer-order.xci", dir); card(path, p, 4); reads("logo before secure", path, g.upd, ref); }

    printf("2 an older card: the copies in normal\n");
    { Part p[] = { { "update", &update }, { "normal", &logo }, { "secure", &secure } };
      snprintf(path, sizeof path, "%s/older.xci", dir); card(path, p, 3); reads("normal with the meta NCA's name", path, g.upd, ref); }

    printf("3 no secure partition: every partition but update, as before\n");
    { Part p[] = { { "update", &update }, { "normal", &secure } };
      snprintf(path, sizeof path, "%s/no-secure.xci", dir); card(path, p, 2); reads("the game in normal, the update partition left out", path, g.upd, ref); }

    free(update.p); free(normal.p); free(logo.p); free(secure.p);
    rm_tree(dir);
    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
