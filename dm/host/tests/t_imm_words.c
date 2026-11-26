// PEANUTS(upstream-candidate): window_words / view_scale_words (dm/host/imm_words.h, loader.c words_apply): the words the loader writes for a window size and a
// scene percent, against the words NDK clang 28.2 assembles for the same instructions (Mario + Rabbids Sparks of Hope's sites, the golden vectors of the device probe
// strings), and the checks: the expected word of every site, the transaction (a row is written whole or not at all), the row's form, the DM_WINDOW_SIZE /
// DM_SCENE_PCT parsers, and the overlap with DM_OPTION_PATCHES.
// Usage: t_imm_words [<folder with probe_env_L1..L6.txt>]   (with the folder, the six probe env files' replacement words are compared too)
#include "../imm_words.h"
#include <stdio.h>
#include <stdlib.h>

static int g_checks;
#define CHECK(c) do { g_checks++; if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)

#define BUILD "5b76a43b231e0640521ac66203f7c178"
#define TEXT_END 0x3dcf810ull

// Mario + Rabbids Sparks of Hope (main build 5b76a43b...): the game's own words (from the dump; the values here are the instruction words)
static const imw_site_t WIN[4] = {   // handheld w/h (x21), docked w/h (x8)
    {0x28639c, 0xd280a015u, IMW_W}, {0x2863a8, 0xf2c05a15u, IMW_H}, {0x2863c8, 0xd280f008u, IMW_W}, {0x2863d4, 0xf2c08708u, IMW_H},
};
static const imw_site_t SCL[4] = {   // handheld lo/hi, docked lo/hi: movz w8,#0x5555 / movk w8,#0x3f55,lsl #16 (5/6)
    {0x3366448, 0x528aaaa8u, IMW_LO}, {0x336644c, 0x72a7eaa8u, IMW_HI}, {0x33664a0, 0x528aaaa8u, IMW_LO}, {0x33664a4, 0x72a7eaa8u, IMW_HI},
};
// NDK clang 28.2 (--target=aarch64-linux-android33) assembly, a generator script: {w, h, {handheld w, handheld h, docked w, docked h}}
static const struct { unsigned w, h; uint32_t word[4]; } WIN_GOLD[] = {
    {1280, 720, {0xd280a015u, 0xf2c05a15u, 0xd280a008u, 0xf2c05a08u}},
    {1920, 1080, {0xd280f015u, 0xf2c08715u, 0xd280f008u, 0xf2c08708u}},
    {960, 540, {0xd2807815u, 0xf2c04395u, 0xd2807808u, 0xf2c04388u}},
    {2560, 1440, {0xd2814015u, 0xf2c0b415u, 0xd2814008u, 0xf2c0b408u}},
};
// {pct, float bits, movz w8,#lo, movk w8,#hi,lsl #16}
static const struct { unsigned pct; uint32_t bits, lo, hi; } SCL_GOLD[] = {
    {40, 0x3ecccccdu, 0x529999a8u, 0x72a7d988u}, {50, 0x3f000000u, 0x52800008u, 0x72a7e008u}, {60, 0x3f19999au, 0x52933348u, 0x72a7e328u},
    {70, 0x3f333333u, 0x52866668u, 0x72a7e668u}, {80, 0x3f4ccccdu, 0x529999a8u, 0x72a7e988u}, {90, 0x3f666666u, 0x528cccc8u, 0x72a7ecc8u},
    {100, 0x3f800000u, 0x52800008u, 0x72a7f008u},
    {45, 0x3ee66666u, 0x528cccc8u, 0x72a7dcc8u}, {55, 0x3f0ccccdu, 0x529999a8u, 0x72a7e188u}, {66, 0x3f28f5c3u, 0x529eb868u, 0x72a7e508u},
    {75, 0x3f400000u, 0x52800008u, 0x72a7e808u}, {85, 0x3f59999au, 0x52933348u, 0x72a7eb28u}, {95, 0x3f733333u, 0x52866668u, 0x72a7ee68u},
    {99, 0x3f7d70a4u, 0x528e1488u, 0x72a7efa8u},
};

static uint8_t *image(void) {   // a text of main's size with the game's own words at the eight sites (calloc: the pages are only touched where used)
    uint8_t *img = calloc(1, TEXT_END);
    CHECK(img);
    for (int i = 0; i < 4; i++) { memcpy(img + WIN[i].off, &WIN[i].word, 4); memcpy(img + SCL[i].off, &SCL[i].word, 4); }
    return img;
}
static uint32_t at(const uint8_t *img, uint64_t off) { uint32_t w; memcpy(&w, img + off, 4); return w; }

static void t_float(void) {
    for (unsigned i = 0; i < sizeof SCL_GOLD / sizeof *SCL_GOLD; i++) {
        uint32_t b = imw_float_bits(SCL_GOLD[i].pct);
        CHECK(b == SCL_GOLD[i].bits);
        // the halves, spliced into the game's own words, are what the assembler gives
        uint32_t lo, hi;
        CHECK(imw_scale_word(&SCL[0], b, &lo) && imw_scale_word(&SCL[1], b, &hi));
        CHECK(lo == SCL_GOLD[i].lo && hi == SCL_GOLD[i].hi);
    }
    CHECK(imw_float_bits(100) == 0x3F800000u);   // exactly 1.0f
    CHECK(imw_float_bits(90) == 0x3F666666u && imw_float_bits(80) == 0x3F4CCCCDu && imw_float_bits(40) == 0x3ECCCCCDu);   // the three from the dump
    // the 5/6 the game builds is not one of the steps: the words 0x5555 / 0x3f55 are float(5/6)
    CHECK(((0x3f55u << 16) | 0x5555u) == 0x3F555555u);
}

static void t_window_gold(void) {
    for (unsigned g = 0; g < sizeof WIN_GOLD / sizeof *WIN_GOLD; g++) {
        uint8_t *img = image(); unsigned bad; uint32_t was;
        int n = imw_window_apply(WIN, 4, img, TEXT_END, WIN_GOLD[g].w, WIN_GOLD[g].h, &bad, &was);
        CHECK(n == 4);
        for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == WIN_GOLD[g].word[i]);
        // nothing else moved: the scale words are the game's
        for (int i = 0; i < 4; i++) CHECK(at(img, SCL[i].off) == SCL[i].word);
        CHECK(at(img, 0x28639c - 4) == 0 && at(img, 0x28639c + 4) == 0);
        // applied twice: the words are no longer the game's own, so the second write is refused and changes nothing (the loader runs once per process)
        uint32_t keep[4]; for (int i = 0; i < 4; i++) keep[i] = at(img, WIN[i].off);
        CHECK(imw_window_apply(WIN, 4, img, TEXT_END, 640, 360, &bad, &was) == IMW_E_WORD);   // (1280x720 leaves the handheld pair's words as they were, but the docked pair's changed)
        for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == keep[i]);
        free(img);
    }
}

static void t_scale_gold(void) {
    for (unsigned g = 0; g < sizeof SCL_GOLD / sizeof *SCL_GOLD; g++) {
        uint8_t *img = image(); unsigned bad; uint32_t was;
        int n = imw_scale_apply(SCL, 4, img, TEXT_END, SCL_GOLD[g].pct, &bad, &was);
        CHECK(n == 4);
        CHECK(at(img, 0x3366448) == SCL_GOLD[g].lo && at(img, 0x336644c) == SCL_GOLD[g].hi);   // handheld pair
        CHECK(at(img, 0x33664a0) == SCL_GOLD[g].lo && at(img, 0x33664a4) == SCL_GOLD[g].hi);   // docked pair
        CHECK((((SCL_GOLD[g].hi >> 5) & 0xFFFF) << 16 | ((SCL_GOLD[g].lo >> 5) & 0xFFFF)) == SCL_GOLD[g].bits);
        for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == WIN[i].word);   // the window words are untouched
        CHECK(at(img, 0x3366448 + 8) == 0);   // the neighbour (fmov s0,w8 in the game: here zero) is untouched
        free(img);
    }
    // 100 % is 0x3F800000: movz w8,#0 / movk w8,#0x3f80,lsl #16
    uint8_t *img = image(); unsigned bad; uint32_t was;
    CHECK(imw_scale_apply(SCL, 4, img, TEXT_END, 100, &bad, &was) == 4);
    CHECK(at(img, 0x3366448) == 0x52800008u && at(img, 0x336644c) == 0x72a7f008u);
    free(img);
}

static void t_transaction(void) {
    unsigned bad = 99; uint32_t was = 0;
    // a wrong expected word at the last site: nothing is written for the key (the other three words stay the game's), -2, the site and the word found reported
    for (int k = 0; k < 4; k++) {
        uint8_t *img = image();
        uint32_t other = WIN[k].word ^ (1u << 7);   // a different imm16
        memcpy(img + WIN[k].off, &other, 4);
        CHECK(imw_window_apply(WIN, 4, img, TEXT_END, 1920, 1080, &bad, &was) == IMW_E_WORD);
        CHECK(bad == (unsigned)k && was == other);
        for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == (i == k ? other : WIN[i].word));
        free(img);
        img = image();
        other = SCL[k].word ^ (1u << 9);
        memcpy(img + SCL[k].off, &other, 4);
        CHECK(imw_scale_apply(SCL, 4, img, TEXT_END, 80, &bad, &was) == IMW_E_WORD);
        CHECK(bad == (unsigned)k && was == other);
        for (int i = 0; i < 4; i++) CHECK(at(img, SCL[i].off) == (i == k ? other : SCL[i].word));
        free(img);
    }
    // a site past the text: -1, nothing written
    uint8_t *img = image();
    CHECK(imw_window_apply(WIN, 4, img, 0x2863d4, 1920, 1080, &bad, &was) == IMW_E_SITE && bad == 3);   // text ends at the h site: its word is outside
    for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == WIN[i].word);
    CHECK(imw_window_apply(WIN, 4, img, 0x2863d8, 1920, 1080, &bad, &was) == 4);   // one word more: inside
    free(img);
    // a value that does not fit an imm16, a percent outside 40..100: IMW_E_ROW and nothing written
    img = image();
    CHECK(imw_window_apply(WIN, 4, img, TEXT_END, 70000, 1080, &bad, &was) == IMW_E_ROW);
    CHECK(imw_window_apply(WIN, 4, img, TEXT_END, 0, 1080, &bad, &was) == IMW_E_ROW);
    CHECK(imw_scale_apply(SCL, 4, img, TEXT_END, 39, &bad, &was) == IMW_E_ROW);
    CHECK(imw_scale_apply(SCL, 4, img, TEXT_END, 101, &bad, &was) == IMW_E_ROW);
    for (int i = 0; i < 4; i++) CHECK(at(img, WIN[i].off) == WIN[i].word && at(img, SCL[i].off) == SCL[i].word);
    free(img);
}

static void t_row_valid(void) {
    imw_site_t s[IMW_MAX_SITES + 1];
    CHECK(imw_row_valid(IMW_KEY_WINDOW, WIN, 4) && imw_row_valid(IMW_KEY_SCALE, SCL, 4));
    CHECK(!imw_row_valid(IMW_KEY_WINDOW, WIN, 0));
    // not a movz / movk (a b, an add), an unaligned or an absurd offset, a repeated offset, a kind past 1
    memcpy(s, WIN, sizeof WIN); s[1].word = 0x14000001u; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    memcpy(s, WIN, sizeof WIN); s[1].word = 0x91000000u; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    memcpy(s, WIN, sizeof WIN); s[1].off |= 2; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    memcpy(s, WIN, sizeof WIN); s[1].off = (1ull << 40); CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    memcpy(s, WIN, sizeof WIN); s[1].off = s[0].off; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    memcpy(s, WIN, sizeof WIN); s[1].kind = 2; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    // window: needs both a w and an h site
    memcpy(s, WIN, sizeof WIN); s[1].kind = s[3].kind = IMW_W; CHECK(!imw_row_valid(IMW_KEY_WINDOW, s, 4));
    CHECK(imw_row_valid(IMW_KEY_WINDOW, WIN, 2));   // one pair is enough
    // too many sites
    for (unsigned i = 0; i < IMW_MAX_SITES + 1; i++) { s[i].off = 0x1000 + 4 * i; s[i].word = 0xd2800000u; s[i].kind = i & 1; }
    CHECK(imw_row_valid(IMW_KEY_WINDOW, s, IMW_MAX_SITES) && !imw_row_valid(IMW_KEY_WINDOW, s, IMW_MAX_SITES + 1));
    // scale: pairs of lo, hi (32-bit, the same register, lsl 0 then lsl 16)
    memcpy(s, SCL, sizeof SCL); CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 3));                            // odd count
    memcpy(s, SCL, sizeof SCL); s[0].kind = IMW_HI; s[1].kind = IMW_LO; CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 4));   // hi before lo
    memcpy(s, SCL, sizeof SCL); s[1].word = (s[1].word & ~0x1Fu) | 9; CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 4));       // another register
    memcpy(s, SCL, sizeof SCL); s[0].word |= 1u << 31; s[1].word |= 1u << 31; CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 4));   // x register
    memcpy(s, SCL, sizeof SCL); s[0].word |= 1u << 21; CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 4));    // lo at lsl 16
    memcpy(s, SCL, sizeof SCL); s[1].word &= ~(3u << 21); CHECK(!imw_row_valid(IMW_KEY_SCALE, s, 4)); // hi at lsl 0
    // movz / movk both fine, either way round (a 32-bit movz at lsl 0 then movk)
    memcpy(s, SCL, sizeof SCL); s[0].word |= 1u << 29; CHECK(imw_row_valid(IMW_KEY_SCALE, s, 4));   // opc 10 -> 11: movz -> movk
}

static void t_parse(void) {
    unsigned w = 0, h = 0, p = 0;
    CHECK(imw_parse_size(NULL, &w, &h) == 0 && imw_parse_size("", &w, &h) == 0 && imw_parse_size("original", &w, &h) == 0);
    CHECK(imw_parse_size("1280x720", &w, &h) == 1 && w == 1280 && h == 720);
    CHECK(imw_parse_size("1920x1080", &w, &h) == 1 && w == 1920 && h == 1080);
    CHECK(imw_parse_size("65535x65535", &w, &h) == 1 && w == 65535 && h == 65535);
    const char *bad_size[] = {"1280", "1280x", "x720", "1280x720x", "1280x720 ", " 1280x720", "1280X720", "0x720", "1280x0", "65536x720", "1280x65536", "-1x720", "1280x-720",
                              "123456x720", "1280x720a", "Original", "1280*720", "0x0", "1e3x720"};
    for (unsigned i = 0; i < sizeof bad_size / sizeof *bad_size; i++) CHECK(imw_parse_size(bad_size[i], &w, &h) == -1);
    CHECK(imw_parse_pct(NULL, &p) == 0 && imw_parse_pct("", &p) == 0);
    CHECK(imw_parse_pct("40", &p) == 1 && p == 40);
    CHECK(imw_parse_pct("100", &p) == 1 && p == 100);
    CHECK(imw_parse_pct("73", &p) == 1 && p == 73);
    const char *bad_pct[] = {"39", "101", "0", "5", "1000", "-50", "50%", "50.0", "0.5", " 50", "50 ", "abc", "0x50", "00000100", "+50"};
    for (unsigned i = 0; i < sizeof bad_pct / sizeof *bad_pct; i++) CHECK(imw_parse_pct(bad_pct[i], &p) == -1);

    // the documented rows, parsed
    imw_site_t s[IMW_MAX_SITES]; char b[33];
    int n = imw_parse_row(BUILD " 0x28639c:15a080d2:w 0x2863a8:155ac0f2:h 0x2863c8:08f080d2:w 0x2863d4:0887c0f2:h", IMW_KEY_WINDOW, b, s);
    CHECK(n == 4 && !strcmp(b, BUILD));
    for (int i = 0; i < 4; i++) CHECK(s[i].off == WIN[i].off && s[i].word == WIN[i].word && s[i].kind == WIN[i].kind);
    n = imw_parse_row(BUILD "\t0x3366448:a8aa8a52:lo\t0x336644c:a8eaa772:hi\t0x33664a0:a8aa8a52:lo\t0x33664a4:a8eaa772:hi", IMW_KEY_SCALE, b, s);
    CHECK(n == 4);
    for (int i = 0; i < 4; i++) CHECK(s[i].off == SCL[i].off && s[i].word == SCL[i].word && s[i].kind == SCL[i].kind);
    const char *bad_rows_w[] = {
        BUILD, BUILD " 0x28639c:15a080d2:w",                                              // no h site
        "5b76a43b231e0640521ac66203f7c17 0x28639c:15a080d2:w 0x2863a8:155ac0f2:h",         // a short build id
        "5B76A43B231E0640521AC66203F7C178 0x28639c:15a080d2:w 0x2863a8:155ac0f2:h",        // upper case
        BUILD " 28639c:15a080d2:w 0x2863a8:155ac0f2:h",                                    // no 0x
        BUILD " 0x28639c:15a080d2 0x2863a8:155ac0f2:h",                                    // no tag
        BUILD " 0x28639c:15a080d2:lo 0x2863a8:155ac0f2:h",                                 // a scale tag in a window row
        BUILD " 0x28639c:15a080:w 0x2863a8:155ac0f2:h",                                    // a short word
        BUILD " 0x28639c:15a080d2:w:x 0x2863a8:155ac0f2:h",                                // an extra field
        BUILD " 0x28639d:15a080d2:w 0x2863a8:155ac0f2:h",                                  // unaligned
        BUILD " 0x28639c:00000014:w 0x2863a8:155ac0f2:h",                                  // a b, not a movz
    };
    for (unsigned i = 0; i < sizeof bad_rows_w / sizeof *bad_rows_w; i++) CHECK(imw_parse_row(bad_rows_w[i], IMW_KEY_WINDOW, b, s) == -1);
    CHECK(imw_parse_row(BUILD " 0x3366448:a8aa8a52:lo 0x336644c:a8eaa772:w", IMW_KEY_SCALE, b, s) == -1);   // a window tag in a scale row
    CHECK(imw_parse_row(BUILD " 0x3366448:a8aa8a52:lo", IMW_KEY_SCALE, b, s) == -1);                          // a lone lo
}

static void t_option_overlap(void) {
    // the probe string (rows for the same eight words, every site an option writes) overlaps both keys: option_apply_patches would otherwise die on the rewritten words
    const char *l2 = BUILD ",m0x28639c,15a080d2,15a080d2;" BUILD ",m0x2863a8,155ac0f2,155ac0f2;" BUILD ",m0x2863c8,08f080d2,08a080d2;" BUILD ",m0x2863d4,0887c0f2,085ac0f2;"
                     BUILD ",m0x3366448,a8aa8a52,c8cc8c52;" BUILD ",m0x336644c,a8eaa772,c8eca772";
    CHECK(imw_option_overlap(l2, BUILD, WIN, 4) == 1);
    CHECK(imw_option_overlap(l2, BUILD, SCL, 4) == 1);
    // the profile's old DM_MRSOH_3D_FULL option: the handheld scale pair only; the docked pair is not in it
    const char *full = BUILD ",m0x3366448,a8aa8a52,08008052;" BUILD ",m0x336644c,a8eaa772,08f0a772";
    CHECK(imw_option_overlap(full, BUILD, SCL, 4) == 1 && imw_option_overlap(full, BUILD, WIN, 4) == 0);
    const char *dock = BUILD ",m0x33664a4,a8eaa772,08f0a772";
    CHECK(imw_option_overlap(dock, BUILD, SCL, 4) == 4);   // reports the site (1-based)
    // another build's rows, no spec, an empty spec, a row of another place: no overlap
    CHECK(imw_option_overlap("00000000000000000000000000000000,m0x3366448,a8aa8a52,08008052", BUILD, SCL, 4) == 0);
    CHECK(imw_option_overlap(NULL, BUILD, SCL, 4) == 0 && imw_option_overlap("", BUILD, SCL, 4) == 0);
    CHECK(imw_option_overlap(BUILD ",m0x1000,a8aa8a52,08008052", BUILD, SCL, 4) == 0);
    // a spec that does not parse (the '~' of the profile form): reported, so the loader writes nothing and option_apply_patches dies on it as before
    CHECK(imw_option_overlap(BUILD ",m0x3366448,a8aa8a52,08008052~" BUILD ",m0x336644c,a8eaa772,08f0a772", BUILD, SCL, 4) == IMW_E_SPEC);
}

// The six probe env files: DM_OPTION_PATCHES=build,m0x<off>,<expected>,<replacement>;... Each row's replacement is the NDK clang word the probe wrote for that launch: the loader's
// words for the same window and percent must be those words
static int read_env_rows(const char *path, uint64_t *off, uint32_t *rep, int max) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    char line[4096]; int n = 0;
    while (fgets(line, sizeof line, f)) {
        if (strncmp(line, "DM_OPTION_PATCHES=", 18)) continue;
        for (char *r = strtok(line + 18, ";\r\n"); r && n < max; r = strtok(NULL, ";\r\n")) {
            char exp[16], rp[16]; unsigned long long o;
            CHECK(sscanf(r, BUILD ",m0x%llx,%8[0-9a-f],%8[0-9a-f]", &o, exp, rp) == 3);
            uint32_t e, v; CHECK(fpw_plan_word(exp, &e) && fpw_plan_word(rp, &v));
            off[n] = o; rep[n] = v; n++;
        }
    }
    fclose(f);
    return n;
}
static void t_probe_files(const char *dir) {
    int found = 0;
    for (int L = 1; L <= 6; L++) {
        char path[1024]; snprintf(path, sizeof path, "%s/probe_env_L%d.txt", dir, L);
        uint64_t off[16]; uint32_t rep[16];
        int n = read_env_rows(path, off, rep, 16);
        if (n < 0) { fprintf(stderr, "t_imm_words: %s not found, skipped\n", path); continue; }
        found++;
        CHECK(n == 4 || n == 8);
        // the size the probe asked for, read from its handheld w / h words; the percent from the lo / hi halves when the scale rows are there
        unsigned w = imw_imm16(rep[0]), h = imw_imm16(rep[1]), pct = 0;
        CHECK(off[0] == 0x28639c && off[1] == 0x2863a8 && off[2] == 0x2863c8 && off[3] == 0x2863d4);
        if (n == 8) {
            CHECK(off[4] == 0x3366448 && off[5] == 0x336644c && off[6] == 0x33664a0 && off[7] == 0x33664a4);
            for (unsigned p = IMW_PCT_MIN; p <= IMW_PCT_MAX; p++) if (imw_imm16(rep[4]) == (imw_float_bits(p) & 0xFFFF) && imw_imm16(rep[5]) == imw_float_bits(p) >> 16) pct = p;
            CHECK(pct);
        }
        uint8_t *img = image(); unsigned bad; uint32_t was;
        CHECK(imw_window_apply(WIN, 4, img, TEXT_END, w, h, &bad, &was) == 4);
        for (int i = 0; i < 4; i++) CHECK(at(img, off[i]) == rep[i]);
        if (pct) {
            CHECK(imw_scale_apply(SCL, 4, img, TEXT_END, pct, &bad, &was) == 4);
            for (int i = 4; i < 8; i++) CHECK(at(img, off[i]) == rep[i]);
        } else {
            for (int i = 0; i < 4; i++) CHECK(at(img, SCL[i].off) == SCL[i].word);   // L1, L5, L6: the scale words stay the game's
        }
        printf("probe L%d: %ux%u%s%u: %d words equal\n", L, w, h, pct ? ", scene " : ", scene unset ", pct, n);
        free(img);
    }
    if (dir[0]) CHECK(found == 6);
}

// PEANUTS(upstream-candidate) begin: the orr-immediate window site, the rodata f32 view-scale site and view_scale_steps, with Kingdom Battle's
// verified data (Mario + Rabbids Kingdom Battle main build 3b39e0c0...). The rodata bounds below are test values around the f32 site
#define KB_BUILD "3b39e0c06b8841f1baf0ea4d54286bfe"
#define KB_RO 0x2000000ull
#define KB_DATA 0x2800000ull
static uint32_t le(const char *hex) { uint32_t w = 0; CHECK(fpw_plan_word(hex, &w)); return w; }   // "e90f1932" (bytes in file order) -> 0x32190fe9
static int le_is(const uint8_t *img, uint64_t off, const char *hex) { char b[9]; snprintf(b, sizeof b, "%02x%02x%02x%02x", img[off], img[off + 1], img[off + 2], img[off + 3]); return !strcmp(b, hex); }
static const char *const KB_STEPS_ROW = KB_BUILD " 40:cdcccc3e 50:0000003f 60:9a99193f 70:3433333f 80:cdcc4c3f 90:6766663f 100:0000803f";
static const struct { unsigned pct; const char *le; } KB_STEPS[] = {{40, "cdcccc3e"}, {50, "0000003f"}, {60, "9a99193f"}, {70, "3433333f"}, {80, "cdcc4c3f"}, {90, "6766663f"}, {100, "0000803f"}};

static void t_orr_site(void) {
    uint32_t orr = le("e90f1932");   // orr w9, wzr, #0x780 (Kingdom Battle main+0x10985ac, window W docked)
    CHECK(orr == 0x32190fe9u && imw_is_orr_wzr(orr) && !imw_is_movzk(orr) && imw_rd(orr) == 9);
    // the documented negatives for the predicate: Rn != 31, sf = 1, Rd = 31; a movz / movk is not an orr
    CHECK(!imw_is_orr_wzr((orr & ~0x3E0u) | (8u << 5)) && !imw_is_orr_wzr(orr | 0x80000000u) && !imw_is_orr_wzr(orr | 0x1Fu));
    CHECK(!imw_is_orr_wzr(0x5280a009u) && !imw_is_orr_wzr(0x72a7eaa8u) && !imw_is_orr_wzr(0xb2190fe9u) && imw_is_orr_wzr((orr & ~0x1Fu) | 30u));
    // the window word: the whole word becomes movz Wd, #v (1280 -> 09a08052, 1920 -> 09f08052)
    imw_site_t s = {0x10985ac, orr, IMW_W}; uint32_t w = 0; uint8_t b[4];
    CHECK(imw_window_word(&s, 1280, 720, &w) && w == 0x5280a009u); memcpy(b, &w, 4); CHECK(b[0] == 0x09 && b[1] == 0xa0 && b[2] == 0x80 && b[3] == 0x52);
    CHECK(imw_window_word(&s, 1920, 1080, &w) && w == 0x5280f009u); memcpy(b, &w, 4); CHECK(b[0] == 0x09 && b[1] == 0xf0 && b[2] == 0x80 && b[3] == 0x52);
    s.kind = IMW_H; CHECK(imw_window_word(&s, 1920, 1080, &w) && w == 0x52808709u);   // an h site takes the height
    s.word = orr | 0x80000000u; CHECK(!imw_window_word(&s, 1280, 720, &w));            // sf = 1: not a window word
    // Kingdom Battle's window row (two movz h, an orr w, a movz w): valid, the transaction compares the orr word itself
    const imw_site_t KB[4] = {{0x10985a0, le("08878052"), IMW_H}, {0x10985a4, le("095a8052"), IMW_H}, {0x10985ac, orr, IMW_W}, {0x10985b0, le("0aa08052"), IMW_W}};
    CHECK(imw_row_valid(IMW_KEY_WINDOW, KB, 4));
    static const struct { unsigned w, h; const char *le[4]; } KB_GOLD[] = {{1280, 720, {"085a8052", "095a8052", "09a08052", "0aa08052"}},
                                                                          {1920, 1080, {"08878052", "09878052", "09f08052", "0af08052"}}};
    for (unsigned g = 0; g < 2; g++) {
        uint8_t *img = calloc(1, 0x1100000); unsigned bad; uint32_t was; CHECK(img);
        for (int i = 0; i < 4; i++) memcpy(img + KB[i].off, &KB[i].word, 4);
        CHECK(imw_window_apply(KB, 4, img, 0x1100000, KB_GOLD[g].w, KB_GOLD[g].h, &bad, &was) == 4);
        for (int i = 0; i < 4; i++) CHECK(le_is(img, KB[i].off, KB_GOLD[g].le[i]));
        // applied again: the sites (the orr site among them: it now holds a movz, not the row's orr word) are no longer the row's: refused, nothing written
        CHECK(imw_window_apply(KB, 4, img, 0x1100000, 640, 360, &bad, &was) == IMW_E_WORD && bad == (g ? 1u : 0u));   // the first site changed (1080 stays 1080)
        for (int i = 0; i < 4; i++) CHECK(le_is(img, KB[i].off, KB_GOLD[g].le[i]));
        free(img);
    }
    // a wrong orr word at the site: IMW_E_WORD against the orr word
    { uint8_t *img = calloc(1, 0x1100000); unsigned bad; uint32_t was, other = orr ^ (1u << 10); CHECK(img);
      for (int i = 0; i < 4; i++) memcpy(img + KB[i].off, i == 2 ? &other : &KB[i].word, 4);
      CHECK(imw_window_apply(KB, 4, img, 0x1100000, 1280, 720, &bad, &was) == IMW_E_WORD && bad == 2 && was == other);
      CHECK(at(img, KB[0].off) == KB[0].word && at(img, KB[2].off) == other);
      free(img); }
    // row negatives: Rn != 31, sf = 1, Rd = 31 at the orr site
    imw_site_t r[4]; const uint32_t badorr[3] = {(orr & ~0x3E0u) | (8u << 5), orr | 0x80000000u, orr | 0x1Fu};
    for (int k = 0; k < 3; k++) { memcpy(r, KB, sizeof KB); r[2].word = badorr[k]; CHECK(!imw_row_valid(IMW_KEY_WINDOW, r, 4)); }
    // an orr word in a view_scale_words row (as lo, as hi, as both): refused, and never a scale word
    memcpy(r, SCL, sizeof SCL); r[0].word = (orr & ~0x1Fu) | 8u; CHECK(!imw_row_valid(IMW_KEY_SCALE, r, 4));
    memcpy(r, SCL, sizeof SCL); r[1].word = (orr & ~0x1Fu) | 8u; CHECK(!imw_row_valid(IMW_KEY_SCALE, r, 4));
    memcpy(r, SCL, sizeof SCL); r[0].word = r[1].word = (orr & ~0x1Fu) | 8u; CHECK(!imw_row_valid(IMW_KEY_SCALE, r, 2));
    { imw_site_t o = {0x3366448, orr, IMW_LO}; uint32_t x; CHECK(!imw_scale_word(&o, 0x3f800000u, &x)); }
    { uint8_t *img = image(); unsigned bad; uint32_t was; memcpy(r, SCL, sizeof SCL); r[0].word = (orr & ~0x1Fu) | 8u; memcpy(img + r[0].off, &r[0].word, 4);
      CHECK(imw_scale_apply(r, 4, img, TEXT_END, 90, &bad, &was) == IMW_E_ROW && at(img, r[0].off) == r[0].word && at(img, r[1].off) == SCL[1].word); free(img); }
    // the text form
    imw_site_t p[IMW_MAX_SITES]; char bld[33];
    CHECK(imw_parse_row(KB_BUILD " 0x10985a0:08878052:h 0x10985a4:095a8052:h 0x10985ac:e90f1932:w 0x10985b0:0aa08052:w", IMW_KEY_WINDOW, bld, p) == 4 && !strcmp(bld, KB_BUILD));
    for (int i = 0; i < 4; i++) CHECK(p[i].off == KB[i].off && p[i].word == KB[i].word && p[i].kind == KB[i].kind);
    CHECK(imw_parse_row(KB_BUILD " 0x10985ac:e90f1932:w 0x10985a0:e90f1932:h", IMW_KEY_WINDOW, bld, p) == 2);
    CHECK(imw_parse_row(KB_BUILD " 0x10985ac:e90f19b2:w 0x10985a0:08878052:h", IMW_KEY_WINDOW, bld, p) == -1);   // sf = 1
    CHECK(imw_parse_row(KB_BUILD " 0x10985ac:ff0f1932:w 0x10985a0:08878052:h", IMW_KEY_WINDOW, bld, p) == -1);   // Rd = 31
    CHECK(imw_parse_row(KB_BUILD " 0x10985ac:090f1932:w 0x10985a0:08878052:h", IMW_KEY_WINDOW, bld, p) == -1);   // Rn = 24
    CHECK(imw_parse_row(BUILD " 0x3366448:e80f1932:lo 0x336644c:a8eaa772:hi", IMW_KEY_SCALE, bld, p) == -1);      // an orr in a scale row
}

static uint8_t *kb_image(void) {   // Kingdom Battle's f32 at rodata 0x26e8268: 5555553f (5/6)
    uint8_t *img = calloc(1, KB_DATA); CHECK(img);
    uint32_t w = le("5555553f"); memcpy(img + 0x26e8268, &w, 4);
    return img;
}
static void t_f32_site(void) {
    const imw_site_t F = {0x26e8268, le("5555553f"), IMW_F32};
    CHECK(F.word == 0x3f555555u);
    CHECK(imw_row_valid(IMW_KEY_SCALE, &F, 1) && !imw_row_valid(IMW_KEY_WINDOW, &F, 1));   // kind 2: view_scale_words only
    imw_steps_t st; char bld[33];
    CHECK(imw_parse_steps(KB_STEPS_ROW, bld, &st) == 7 && !strcmp(bld, KB_BUILD) && imw_steps_valid(&st));
    for (unsigned i = 0; i < 7; i++) CHECK(st.pct[i] == KB_STEPS[i].pct && st.word[i] == le(KB_STEPS[i].le));
    // every step: that exact word, at the site only
    for (unsigned i = 0; i < 7; i++) {
        uint8_t *img = kb_image(); unsigned bad; uint32_t was;
        CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &st, KB_STEPS[i].pct, &bad, &was) == 1);
        CHECK(le_is(img, 0x26e8268, KB_STEPS[i].le) && at(img, 0x26e8264) == 0 && at(img, 0x26e826c) == 0);
        CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &st, KB_STEPS[i].pct, &bad, &was) == IMW_E_WORD);   // applied again: the word is no longer 5/6: refused
        free(img);
    }
    { uint8_t *img = kb_image(); unsigned bad; uint32_t was;
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &st, 100, &bad, &was) == 1 && at(img, 0x26e8268) == 0x3F800000u); free(img); }
    // P not in the table: IMW_E_STEP, nothing written
    const unsigned notin[] = {45, 55, 99, 41};
    for (unsigned i = 0; i < 4; i++) { uint8_t *img = kb_image(); unsigned bad; uint32_t was;
        CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &st, notin[i], &bad, &was) == IMW_E_STEP && at(img, 0x26e8268) == F.word); free(img); }
    // bounds: [ro_off, data_off), both known
    { uint8_t *img = kb_image(); unsigned bad = 9; uint32_t was;
      CHECK(imw_f32_apply(&F, 1, img, 0, KB_DATA, &st, 90, &bad, &was) == IMW_E_SITE && bad == 0);             // ro_off unknown
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, 0, &st, 90, &bad, &was) == IMW_E_SITE);                          // data_off unknown
      CHECK(imw_f32_apply(&F, 1, img, 0x26e826c, KB_DATA, &st, 90, &bad, &was) == IMW_E_SITE);                // the site before rodata (in the text)
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, 0x26e826b, &st, 90, &bad, &was) == IMW_E_SITE);                  // the word runs past rodata's end
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, 0x26e8268, &st, 90, &bad, &was) == IMW_E_SITE);                  // the site is data
      CHECK(at(img, 0x26e8268) == F.word);
      CHECK(imw_f32_apply(&F, 1, img, 0x26e8268, 0x26e826c, &st, 90, &bad, &was) == 1 && le_is(img, 0x26e8268, "6766663f"));   // exactly the one word: inside
      free(img); }
    { imw_site_t u = F; u.off |= 2; uint8_t *img = kb_image(); unsigned bad; uint32_t was;   // unaligned: the row is not valid
      CHECK(!imw_row_valid(IMW_KEY_SCALE, &u, 1) && imw_f32_apply(&u, 1, img, KB_RO, KB_DATA, &st, 90, &bad, &was) == IMW_E_ROW);
      CHECK(imw_f32_check(&u, 1, img, KB_RO, KB_DATA, &bad, &was) == IMW_E_SITE); free(img); }
    // expected-word mismatch: IMW_E_WORD, the word found reported
    { uint8_t *img = kb_image(); unsigned bad = 9; uint32_t was = 0, other = 0x3f400000u; memcpy(img + 0x26e8268, &other, 4);
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &st, 90, &bad, &was) == IMW_E_WORD && bad == 0 && was == other && at(img, 0x26e8268) == other); free(img); }
    // two f32 sites: all or nothing
    { imw_site_t two[2] = {F, {0x26e8270, 0x3f000000u, IMW_F32}}; uint8_t *img = kb_image(); unsigned bad; uint32_t was;
      CHECK(imw_row_valid(IMW_KEY_SCALE, two, 2));
      CHECK(imw_f32_apply(two, 2, img, KB_RO, KB_DATA, &st, 80, &bad, &was) == IMW_E_WORD && bad == 1 && at(img, 0x26e8268) == F.word);
      memcpy(img + 0x26e8270, &two[1].word, 4);
      CHECK(imw_f32_apply(two, 2, img, KB_RO, KB_DATA, &st, 80, &bad, &was) == 2 && at(img, 0x26e8268) == 0x3f4ccccdu && at(img, 0x26e8270) == 0x3f4ccccdu); free(img); }
    // mixed rows are refused (f32 with lo / hi in any order); the lo / hi writer refuses an f32 row
    imw_site_t mx[3] = {SCL[0], SCL[1], F};
    CHECK(!imw_row_valid(IMW_KEY_SCALE, mx, 3));
    mx[0] = F; mx[1] = SCL[0]; mx[2] = SCL[1]; CHECK(!imw_row_valid(IMW_KEY_SCALE, mx, 3));
    mx[0] = F; mx[1] = SCL[1]; CHECK(!imw_row_valid(IMW_KEY_SCALE, mx, 2));
    { imw_site_t p[IMW_MAX_SITES];
      CHECK(imw_parse_row(KB_BUILD " 0x26e8268:5555553f:f32", IMW_KEY_SCALE, bld, p) == 1 && p[0].off == F.off && p[0].word == F.word && p[0].kind == IMW_F32);
      CHECK(imw_parse_row(BUILD " 0x3366448:a8aa8a52:lo 0x336644c:a8eaa772:hi 0x26e8268:5555553f:f32", IMW_KEY_SCALE, bld, p) == -1);
      CHECK(imw_parse_row(KB_BUILD " 0x26e8268:5555553f:f32 0x3366448:a8aa8a52:lo", IMW_KEY_SCALE, bld, p) == -1);
      CHECK(imw_parse_row(KB_BUILD " 0x26e8268:5555553f:f32 0x26e8268:5555553f:f32", IMW_KEY_SCALE, bld, p) == -1);   // repeated offset
      CHECK(imw_parse_row(KB_BUILD " 0x26e8268:5555553f:f32 0x26e826c:5555553f:h", IMW_KEY_WINDOW, bld, p) == -1);    // f32 in a window row
      uint8_t *img = kb_image(); unsigned bad; uint32_t was;
      CHECK(imw_scale_apply(&F, 1, img, TEXT_END, 90, &bad, &was) == IMW_E_ROW && at(img, 0x26e8268) == F.word); free(img); }
    // f32 without steps (none, empty, not valid): IMW_E_ROW, nothing written
    { uint8_t *img = kb_image(); unsigned bad; uint32_t was; imw_steps_t z; memset(&z, 0, sizeof z);
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, NULL, 90, &bad, &was) == IMW_E_ROW);
      CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &z, 90, &bad, &was) == IMW_E_ROW);
      imw_steps_t b = st; b.word[6] = 0x3f800001u; CHECK(imw_f32_apply(&F, 1, img, KB_RO, KB_DATA, &b, 90, &bad, &was) == IMW_E_ROW);
      CHECK(at(img, 0x26e8268) == F.word); free(img); }
    // the steps' rules
    imw_steps_t t;
    t = st; t.n = IMW_MAX_STEPS + 1; CHECK(!imw_steps_valid(&t));                          // over the cap
    t = st; t.pct[0] = 39; t.word[0] = imw_float_bits(39); CHECK(!imw_steps_valid(&t));   // below 40
    t = st; t.n = 6; t.pct[5] = 101; t.word[5] = imw_float_bits(101); CHECK(!imw_steps_valid(&t));   // past 100
    t = st; t.pct[1] = 40; t.word[1] = t.word[0]; CHECK(!imw_steps_valid(&t));            // repeated P
    t = st; t.pct[1] = 60; t.word[1] = t.word[2]; t.pct[2] = 50; t.word[2] = le("0000003f"); CHECK(!imw_steps_valid(&t));   // not ascending
    t = st; t.word[2] = 0x3f1a0000u; CHECK(!imw_steps_valid(&t));                          // 0.6015625: more than 0.001 from 0.6
    t = st; t.word[2] = 0x7fc00000u; CHECK(!imw_steps_valid(&t));                          // NaN
    t = st; t.word[2] = 0x7f800000u; CHECK(!imw_steps_valid(&t));                          // inf
    t = st; t.word[6] = 0x3f800001u; CHECK(!imw_steps_valid(&t));                          // 100 must be exactly 1.0f
    t = st; t.word[3] = 0x3f333333u; CHECK(imw_steps_valid(&t));                           // 70: either rounding is near
    t = st; t.word[2] = 0x3f19d495u; CHECK(imw_steps_valid(&t));                           // 0.6009: within 0.001
    t = st; t.n = 6; CHECK(!imw_steps_valid(&t));                                          // no 100 entry: refused (100 is mandatory)
    CHECK(imw_parse_steps(KB_BUILD " 40:cdcccc3e 50:0000003f", bld, &t) == -1);              // the text form without 100: refused
    memset(&t, 0, sizeof t); t.n = 1; t.pct[0] = 100; t.word[0] = 0x3F800000u; CHECK(imw_steps_valid(&t));   // 100 alone: a table
    memset(&t, 0, sizeof t); for (unsigned p = 40; p <= 100; p += 5) { t.pct[t.n] = p; t.word[t.n++] = imw_float_bits(p); }
    CHECK(t.n == IMW_MAX_STEPS && imw_steps_valid(&t));                                     // 13 entries: the cap
    uint32_t w;
    CHECK(imw_steps_word(&st, 70, &w) && w == 0x3f333334u && !imw_steps_word(&st, 75, &w));
    // the text form's negatives
    const char *bad_steps[] = {
        KB_BUILD, KB_BUILD " 40:cdcccc3e 40:cdcccc3e", KB_BUILD " 50:0000003f 40:cdcccc3e", KB_BUILD " 40cdcccc3e", KB_BUILD " :cdcccc3e", KB_BUILD " 1000:0000803f",
        KB_BUILD " 40:cdcccc", KB_BUILD " 4a:cdcccc3e", KB_BUILD " 100:0100803f", KB_BUILD " 39:14aec73e", KB_BUILD " 60:00001a3f",
        KB_BUILD " 40:cdcccc3e 45:6666e63e 50:0000003f 55:cdcc0c3f 60:9a99193f 65:6666263f 70:3433333f 75:0000403f 80:cdcc4c3f 85:9a99593f 90:6766663f 95:3333733f 100:0000803f 99:a4707d3f",
        "3B39E0C06B8841F1BAF0EA4D54286BFE 100:0000803f",
    };
    for (unsigned i = 0; i < sizeof bad_steps / sizeof *bad_steps; i++) CHECK(imw_parse_steps(bad_steps[i], bld, &t) == -1);
    CHECK(imw_parse_steps(KB_BUILD " 40:cdcccc3e 45:6666e63e 50:0000003f 55:cdcc0c3f 60:9a99193f 65:6666263f 70:3433333f 75:0000403f 80:cdcc4c3f 85:9a99593f 90:6766663f 95:3333733f 100:0000803f", bld, &t) == 13);
    // option overlap: an f32 site is also written by FPSLocker r / o rows (absolute image offsets); a lo / hi or window site only by m rows, as before
    CHECK(imw_option_overlap(KB_BUILD ",r0x26e8268,5555553f,0000803f", KB_BUILD, &F, 1) == 1);
    CHECK(imw_option_overlap(KB_BUILD ",o0x26e8268,5555553f,0000803f", KB_BUILD, &F, 1) == 1);
    CHECK(imw_option_overlap(KB_BUILD ",m0x26e8268,5555553f,0000803f", KB_BUILD, &F, 1) == 1);
    CHECK(imw_option_overlap(KB_BUILD ",r0x26e826c,5555553f,0000803f;" KB_BUILD ",o0x26e8264,5555553f,0000803f", KB_BUILD, &F, 1) == 0);
    CHECK(imw_option_overlap(BUILD ",r0x26e8268,5555553f,0000803f", KB_BUILD, &F, 1) == 0);   // another build's row
    CHECK(imw_option_overlap(BUILD ",r0x3366448,a8aa8a52,08008052", BUILD, SCL, 4) == 0 && imw_option_overlap(BUILD ",o0x28639c,15a080d2,15a080d2", BUILD, WIN, 4) == 0);
}
// PEANUTS end

// PEANUTS(upstream-candidate) begin: a fixed-window build (view_scale_fixed): the ratio for DM_OUT_SIZE / DM_SCENE_PCT against Three Hopes' golden table, Game default,
// and the refusals (nothing written)
#define FX_BUILD "f62a7d41caac7d6d22d5e1384cee3720"
#define FX_RO 0x1000000ull
#define FX_DATA 0x2000000ull
// 33 rows of the golden ratio table of Fire Emblem Warriors: Three Hopes (window 1920x1080): {T_w, T_h, P, float32 bits}; a separate script checked every row against the
// arithmetic (double, min 1.0, one cast to float32): 0 mismatches
static const struct { unsigned tw, th, pct; uint32_t bits; } FX_GOLD[33] = {
    {1280, 720, 50, 0x3eaaaaabu}, {1280, 720, 55, 0x3ebbbbbcu}, {1280, 720, 60, 0x3ecccccdu}, {1280, 720, 65, 0x3edddddeu},
    {1280, 720, 70, 0x3eeeeeefu}, {1280, 720, 75, 0x3f000000u}, {1280, 720, 80, 0x3f088889u}, {1280, 720, 85, 0x3f111111u},
    {1280, 720, 90, 0x3f19999au}, {1280, 720, 95, 0x3f222222u}, {1280, 720, 100, 0x3f2aaaabu}, {1920, 1080, 50, 0x3f000000u},
    {1920, 1080, 55, 0x3f0ccccdu}, {1920, 1080, 60, 0x3f19999au}, {1920, 1080, 65, 0x3f266666u}, {1920, 1080, 70, 0x3f333333u},
    {1920, 1080, 75, 0x3f400000u}, {1920, 1080, 80, 0x3f4ccccdu}, {1920, 1080, 85, 0x3f59999au}, {1920, 1080, 90, 0x3f666666u},
    {1920, 1080, 95, 0x3f733333u}, {1920, 1080, 100, 0x3f800000u}, {2560, 1440, 50, 0x3f2aaaabu}, {2560, 1440, 55, 0x3f3bbbbcu},
    {2560, 1440, 60, 0x3f4ccccdu}, {2560, 1440, 65, 0x3f5ddddeu}, {2560, 1440, 70, 0x3f6eeeefu}, {2560, 1440, 75, 0x3f800000u},
    {2560, 1440, 80, 0x3f800000u}, {2560, 1440, 85, 0x3f800000u}, {2560, 1440, 90, 0x3f800000u}, {2560, 1440, 95, 0x3f800000u},
    {2560, 1440, 100, 0x3f800000u},
};
static uint32_t fx_at(const uint8_t *img, uint64_t off) { uint32_t w; memcpy(&w, img + off, 4); return w; }
static void fx_put(uint8_t *img, uint64_t off, uint32_t w) { memcpy(img + off, &w, 4); }
static void t_fixed_window(void) {
    uint8_t *img = calloc(1, FX_DATA); CHECK(img);
    // Three Hopes' gameplay site: the table's index 2 at main+0x1d9f618, 0.75 (LE 0000403f)
    const imw_site_t F = {0x1d9f618, 0x3f400000u, IMW_F32};
    unsigned bad = 9, tw = 0, th = 0; uint32_t was = 0, first = 0, b = 0;
    // the 33 golden rows: imw_fixed_ratio, then the write (all with DM_SCENE_PCT set)
    for (unsigned i = 0; i < 33; i++) {
        CHECK(imw_fixed_ratio(FX_GOLD[i].th, 1080, FX_GOLD[i].pct, F.word, &b) && b == FX_GOLD[i].bits);
        fx_put(img, F.off, F.word);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, FX_GOLD[i].tw, FX_GOLD[i].th, FX_GOLD[i].pct, &bad, &was, &first) == 1);
        CHECK(fx_at(img, F.off) == FX_GOLD[i].bits && first == FX_GOLD[i].bits);
    }
    // 720p + Game default on the 0.75 site: 0.667 x 0.75 = 0.5, the bytes 00 00 00 3f
    fx_put(img, F.off, F.word);
    CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 0, &bad, &was, &first) == 1);
    { static const uint8_t want[4] = {0x00, 0x00, 0x00, 0x3f}; CHECK(!memcmp(img + F.off, want, 4) && first == 0x3f000000u); }
    // 1080p + 70: float32(0.7) = 0x3f333333, the bytes 33 33 33 3f (not Kingdom Battle's steps word 0x3f333334: a fixed window computes the ratio)
    fx_put(img, F.off, F.word);
    CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1920, 1080, 70, &bad, &was, &first) == 1);
    { static const uint8_t want[4] = {0x33, 0x33, 0x33, 0x3f}; CHECK(!memcmp(img + F.off, want, 4) && first == 0x3f333333u); }
    // Game default with T_h >= H: nothing written (the game's own values)
    fx_put(img, F.off, F.word);
    CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1920, 1080, 0, &bad, &was, &first) == 0 && fx_at(img, F.off) == F.word);
    CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 2560, 1440, 0, &bad, &was, &first) == 0 && fx_at(img, F.off) == F.word);
    // Game default, several sites: each site's ratio from its own float (1.0, 0.5, 2.0 capped at 1.0); base_h from the row's H, never 1080
    {
        const imw_site_t G[3] = {{0x1d9f610, 0x3f800000u, IMW_F32}, {0x1d9f624, 0x3f000000u, IMW_F32}, {0x1d9f628, 0x40000000u, IMW_F32}};
        for (int k = 0; k < 3; k++) fx_put(img, G[k].off, G[k].word);
        CHECK(imw_fixed_apply(G, 3, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 0, &bad, &was, &first) == 3);
        CHECK(fx_at(img, G[0].off) == 0x3f2aaaabu && fx_at(img, G[1].off) == 0x3eaaaaabu && fx_at(img, G[2].off) == 0x3f800000u && first == 0x3f2aaaabu);
        CHECK(imw_fixed_ratio(720, 1440, 0, 0x3f800000u, &b) && b == 0x3f000000u);   // a 2560x1440 window: 720 / 1440
        CHECK(imw_fixed_ratio(720, 1440, 100, 0, &b) && b == 0x3f000000u);
    }
    // the parsers: DM_OUT_SIZE (unset, empty, original: T = the fixed size) and the row text
    CHECK(imw_fixed_out(NULL, 1920, 1080, &tw, &th) == 0 && tw == 1920 && th == 1080);
    CHECK(imw_fixed_out("", 1920, 1080, &tw, &th) == 0 && imw_fixed_out("original", 1920, 1080, &tw, &th) == 0 && tw == 1920 && th == 1080);
    CHECK(imw_fixed_out("1280x720", 1920, 1080, &tw, &th) == 1 && tw == 1280 && th == 720);
    CHECK(imw_fixed_out("7280x4095", 1920, 1080, &tw, &th) == 1 && th == 4095);
    CHECK(imw_fixed_out("1280x721", 1920, 1080, &tw, &th) == -2 && tw == 1920 && th == 1080);   // not 16:9: T stays the fixed size
    CHECK(imw_fixed_out("7296x4104", 1920, 1080, &tw, &th) == -2);   // 16:9, taller than 4096
    CHECK(imw_fixed_out("1280", 1920, 1080, &tw, &th) == -1 && imw_fixed_out("1280x", 1920, 1080, &tw, &th) == -1 && imw_fixed_out("x720", 1920, 1080, &tw, &th) == -1);
    CHECK(imw_fixed_out("1280x720 ", 1920, 1080, &tw, &th) == -1 && imw_fixed_out("0x0", 1920, 1080, &tw, &th) == -1 && imw_fixed_out("-1280x720", 1920, 1080, &tw, &th) == -1);
    CHECK(imw_fixed_valid(1920, 1080) && imw_fixed_valid(16 * 2, 9 * 2) && imw_fixed_valid(4096, 2304) && imw_fixed_valid(1280, 720));
    CHECK(!imw_fixed_valid(1920, 1081) && !imw_fixed_valid(16, 9) && !imw_fixed_valid(4112, 2313) && !imw_fixed_valid(0, 0) && !imw_fixed_valid(1440, 1080));
    { char bld[33]; unsigned w = 0, h = 0;
      CHECK(imw_parse_fixed(FX_BUILD " 1920x1080", bld, &w, &h) == 1 && !strcmp(bld, FX_BUILD) && w == 1920 && h == 1080);
      CHECK(imw_parse_fixed(FX_BUILD "\t1280x720", bld, &w, &h) == 1 && w == 1280 && h == 720);
      CHECK(imw_parse_fixed(FX_BUILD " 1920x1081", bld, &w, &h) == -1 && imw_parse_fixed(FX_BUILD " 8192x4608", bld, &w, &h) == -1);
      CHECK(imw_parse_fixed(FX_BUILD, bld, &w, &h) == -1 && imw_parse_fixed(FX_BUILD " 1920x1080 1280x720", bld, &w, &h) == -1 && imw_parse_fixed(FX_BUILD " original", bld, &w, &h) == -1);
      CHECK(imw_parse_fixed("F62A7D41CAAC7D6D22D5E1384CEE3720 1920x1080", bld, &w, &h) == -1 && imw_parse_fixed("f62a7d41 1920x1080", bld, &w, &h) == -1); }

    // refusals, each with nothing written
    fx_put(img, F.off, F.word);
    {   // a misaligned site (the row is not valid)
        imw_site_t M = F; M.off += 2;
        CHECK(imw_fixed_apply(&M, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW && fx_at(img, F.off) == F.word);
    }
    {   // a site outside rodata: before ro_off, past data_off, ro_off / data_off unknown
        CHECK(imw_fixed_apply(&F, 1, img, F.off + 4, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_SITE && bad == 0 && fx_at(img, F.off) == F.word);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, F.off + 2, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_SITE && fx_at(img, F.off) == F.word);
        CHECK(imw_fixed_apply(&F, 1, img, 0, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_SITE && fx_at(img, F.off) == F.word);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, 0, 1920, 1080, 1280, 720, 0, &bad, &was, &first) == IMW_E_SITE && fx_at(img, F.off) == F.word);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, F.off + 4, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == 1 && fx_at(img, F.off) == 0x3eeeeeefu);   // exactly [off, off + 4)
        fx_put(img, F.off, F.word);
    }
    {   // a non-finite ratio, or one of 0 or below (Game default takes the site's float): NaN, inf, -inf, -0.75, 0, -0
        static const uint32_t W[6] = {0x7fc00000u, 0x7f800000u, 0xff800000u, 0xbf400000u, 0x00000000u, 0x80000000u};
        for (int k = 0; k < 6; k++) {
            imw_site_t N = F; N.word = W[k]; fx_put(img, N.off, N.word);
            CHECK(!imw_fixed_ratio(720, 1080, 0, W[k], &b));
            CHECK(imw_fixed_apply(&N, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 0, &bad, &was, &first) == IMW_E_RATIO && bad == 0 && fx_at(img, N.off) == W[k]);
        }
        // the smallest denormal: a tiny ratio above 0 at 720p is written (finite, above 0); at 32x18 it rounds to 0 in float32 and is refused
        { imw_site_t N = F; N.word = 0x00000001u; fx_put(img, N.off, N.word);
          CHECK(imw_fixed_apply(&N, 1, img, FX_RO, FX_DATA, 1920, 1080, 32, 18, 0, &bad, &was, &first) == IMW_E_RATIO && fx_at(img, N.off) == N.word);
          CHECK(imw_fixed_apply(&N, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 0, &bad, &was, &first) == 1 && fx_at(img, N.off) == 0x00000001u && first == 0x00000001u); }
        fx_put(img, F.off, F.word);
        CHECK(!imw_fixed_ratio(0, 1080, 70, F.word, &b) && !imw_fixed_ratio(720, 0, 70, F.word, &b) && !imw_fixed_ratio(720, 1080, 39, F.word, &b) && !imw_fixed_ratio(720, 1080, 101, F.word, &b));
    }
    {   // the expected word: a mismatch at the second site refuses the whole row
        const imw_site_t G[2] = {{0x1d9f610, 0x3f800000u, IMW_F32}, {0x1d9f618, 0x3f400000u, IMW_F32}};
        fx_put(img, G[0].off, G[0].word); fx_put(img, G[1].off, 0x3f000000u);
        CHECK(imw_fixed_apply(G, 2, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_WORD && bad == 1 && was == 0x3f000000u);
        CHECK(fx_at(img, G[0].off) == G[0].word && fx_at(img, G[1].off) == 0x3f000000u && first == 0);
        fx_put(img, G[1].off, G[1].word);
    }
    {   // the row, the fixed size, T and P: not valid -> IMW_E_ROW
        CHECK(imw_fixed_apply(SCL, 2, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW);   // a lo / hi row
        const imw_site_t X[2] = {{0x1d9f610, 0x3f800000u, IMW_F32}, {0x3366448, 0x528aaaa8u, IMW_LO}};
        CHECK(imw_fixed_apply(X, 2, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW);    // mixed
        CHECK(imw_fixed_apply(&F, 0, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW);   // no site
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1081, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW);   // the fixed size not 16:9
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 8192, 4608, 1280, 720, 70, &bad, &was, &first) == IMW_E_ROW);   // past 4096
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 721, 70, &bad, &was, &first) == IMW_E_ROW);   // T not 16:9
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 7296, 4104, 70, &bad, &was, &first) == IMW_E_ROW);  // T taller than 4096
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 0, 0, 0, &bad, &was, &first) == IMW_E_ROW);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 39, &bad, &was, &first) == IMW_E_ROW);
        CHECK(imw_fixed_apply(&F, 1, img, FX_RO, FX_DATA, 1920, 1080, 1280, 720, 101, &bad, &was, &first) == IMW_E_ROW);
        CHECK(fx_at(img, F.off) == F.word);
    }
    // FPSLocker r / o rows on the fixed site overlap (the loader leaves the site to them, one [RED] line); a neighbouring offset does not
    CHECK(imw_option_overlap(FX_BUILD ",r0x1d9f618,0000403f,0000003f", FX_BUILD, &F, 1) == 1 && imw_option_overlap(FX_BUILD ",o0x1d9f618,0000403f,0000003f", FX_BUILD, &F, 1) == 1);
    CHECK(imw_option_overlap(FX_BUILD ",r0x1d9f61c,0000403f,0000003f", FX_BUILD, &F, 1) == 0);
    free(img);
}
// PEANUTS end

int main(int argc, char **argv) {
    t_float();
    t_window_gold();
    t_scale_gold();
    t_transaction();
    t_row_valid();
    t_parse();
    t_option_overlap();
    t_orr_site();   // PEANUTS(upstream-candidate)
    t_f32_site();   // PEANUTS(upstream-candidate)
    t_fixed_window();   // PEANUTS(upstream-candidate)
    if (argc > 1) t_probe_files(argv[1]);
    printf("t_imm_words: %d checks OK\n", g_checks);
    return 0;
}
