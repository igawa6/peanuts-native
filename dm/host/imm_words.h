// PEANUTS(upstream-candidate): movz/movk immediate writers for the profile keys window_words and view_scale_words (loader.c words_apply; game.py;
// README.md). A game that builds its window size, or the float of its 3D scale, from movz / movk instructions in its text (Snowdrop: Mario + Rabbids Sparks
// of Hope) takes the setting from the profile row's sites: the loader checks the word the row expects at each site and replaces only its imm16 field, so the
// destination register, the shift and the instruction kind stay the game's. Pure functions, so that the unit test (tests/t_imm_words.c) checks the words
// against NDK clang's own assembly, and the rows' checks and the transaction without a game
//
//   window_words <main build> <off>:<LE word>:<w|h> ...    DM_WINDOW_SIZE=WxH: every w site gets the width, every h site the height
//   view_scale_words <main build> <lo off>:<LE word>:lo <hi off>:<LE word>:hi ...    DM_SCENE_PCT=P: the float32 of P / 100, split in two halves:
//                                                          each lo site (movz Wd, #imm16) gets bits 15..0, the hi site after it (movk Wd, #imm16, lsl #16)
//                                                          bits 31..16
//   a window site may also be an orr Wd, wzr, #imm (the whole word becomes movz Wd, #v), and a view_scale_words row may instead be
//   all f32 sites (<off>:<LE word>:f32, a float in main's rodata [ro_off, data_off)) that take the word of the build's view_scale_steps row for DM_SCENE_PCT:
//   view_scale_steps <main build> <P>:<LE word> ...        P 40..100 ascending, each word a float within 0.001 of P / 100, 100 = 0x3F800000, at most 13
#pragma once
#include <stdint.h>
#include <string.h>
#include "fpslocker_plan.h"   // fpw_plan_hex / fpw_plan_word / fpw_plan_parse: the option rows' own parser, for the DM_OPTION_PATCHES overlap check

#define IMW_MAX_SITES 8
enum { IMW_W = 0, IMW_H = 1 };      // window_words site kinds
enum { IMW_LO = 0, IMW_HI = 1 };    // view_scale_words site kinds
enum { IMW_KEY_WINDOW = 0, IMW_KEY_SCALE = 1 };

typedef struct { uint64_t off; uint32_t word; uint32_t kind; } imw_site_t;   // the row's site: offset in main's text, the word the game has there, IMW_W/H or IMW_LO/HI

#define IMW_PCT_MIN 40u    // DM_SCENE_PCT's range (the launcher's slider: 40 to 100)
#define IMW_PCT_MAX 100u
#define IMW_SIZE_MAX 0xFFFFu   // an imm16

// Results of imw_*_apply (a negative result writes nothing): the count of words written, or
#define IMW_E_SITE (-1)    // a site is outside the text or not 4-byte aligned
#define IMW_E_WORD (-2)    // the word at a site is not the row's (*bad = the site, *was = the word found)
#define IMW_E_ROW (-3)     // the row itself is not valid (a hostile profile.bin) or the value does not fit
#define IMW_E_SPEC (-4)    // an option spec that could not be parsed (imw_option_overlap)

// movz / movk, 32 or 64 bit: sf opc 100101 hw imm16 Rd, opc 10 (movz) or 11 (movk)
static inline int imw_is_movzk(uint32_t w) { uint32_t c = w & 0x7F800000u; return c == 0x52800000u || c == 0x72800000u; }
// PEANUTS(upstream-candidate): orr Wd, wzr, #imm (32-bit logical immediate, sf 0, Rn 31), Rd not 31. A window site only (w / h): never a movz / movk for
// imw_is_movzk, never a view_scale_words site, never in a lo / hi pair. The window write replaces the whole word with movz Wd, #v
static inline int imw_is_orr_wzr(uint32_t w) { return (w & 0xFF8003E0u) == 0x320003E0u && (w & 0x1Fu) != 31u; }
enum { IMW_F32 = 2 };              // view_scale_words site kind: a float32 in main's rodata (row tag f32)
#define IMW_MAX_STEPS 13           // view_scale_steps entries per row
#define IMW_E_STEP (-5)            // DM_SCENE_PCT is not one of the view_scale_steps row's P (nothing written)
// A view_scale_steps row: n entries, pct[i] ascending 40..100, word[i] the float32 bits written for it
typedef struct { unsigned n; uint32_t pct[IMW_MAX_STEPS]; uint32_t word[IMW_MAX_STEPS]; } imw_steps_t;
static inline uint32_t imw_imm16(uint32_t w) { return (w >> 5) & 0xFFFFu; }
static inline uint32_t imw_hw(uint32_t w) { return (w >> 21) & 3u; }
static inline uint32_t imw_rd(uint32_t w) { return w & 0x1Fu; }
// The word with its imm16 replaced: the same register, the same shift, the same instruction kind (the way rt_pool.h rtp_encode does it)
static inline uint32_t imw_with_imm16(uint32_t w, uint32_t v) { return (w & ~(0xFFFFu << 5)) | ((v & 0xFFFFu) << 5); }

// The float32 bits of pct / 100, as the compiler rounds the decimal number (0.9 = 0x3F666666, 0.8 = 0x3F4CCCCD, 0.4 = 0x3ECCCCCD, 1.0 = 0x3F800000). The game rounds
// the render size itself (round to even), so nothing is "snapped" here
static inline uint32_t imw_float_bits(unsigned pct) { float f = (float)((double)pct / 100.0); uint32_t b; memcpy(&b, &f, 4); return b; }

// DM_WINDOW_SIZE: 0 = not set, empty or "original" (write nothing), 1 = "<w>x<h>" (1..65535 each, nothing after), -1 = anything else
static inline int imw_parse_size(const char *e, unsigned *w, unsigned *h) {
    if (!e || !*e || !strcmp(e, "original")) return 0;
    unsigned long a = 0, b = 0; const char *p = e; int n;
    for (n = 0; *p >= '0' && *p <= '9' && n < 6; p++, n++) a = a * 10 + (unsigned long)(*p - '0');
    if (!n || n > 5 || *p != 'x') return -1;
    for (p++, n = 0; *p >= '0' && *p <= '9' && n < 6; p++, n++) b = b * 10 + (unsigned long)(*p - '0');
    if (!n || n > 5 || *p) return -1;
    if (!a || !b || a > IMW_SIZE_MAX || b > IMW_SIZE_MAX) return -1;
    *w = (unsigned)a; *h = (unsigned)b;
    return 1;
}
// DM_SCENE_PCT: 0 = not set or empty (write nothing), 1 = a whole number 40..100 (decimal digits only), -1 = anything else
static inline int imw_parse_pct(const char *e, unsigned *pct) {
    if (!e || !*e) return 0;
    unsigned v = 0; int n = 0;
    for (const char *p = e; *p; p++, n++) { if (*p < '0' || *p > '9' || n >= 4) return -1; v = v * 10 + (unsigned)(*p - '0'); }
    if (v < IMW_PCT_MIN || v > IMW_PCT_MAX) return -1;
    *pct = v;
    return 1;
}

// The row's form: 1..IMW_MAX_SITES sites, each 4-byte aligned (below 4 GiB, so that offset + 4 cannot wrap) and a movz / movk, no offset twice.
// window_words: at least one w and one h site. view_scale_words: lo / hi pairs in that order, a pair's two words 32-bit (sf 0) with the same destination register,
// the lo word at lsl 0 and the hi word at lsl 16. Returns 1 when valid
// a window site may be an orr Wd, wzr, #imm (imw_is_orr_wzr); a view_scale_words row may be all f32 sites instead (any word: the float the game has there;
// mixed with lo / hi refused)
static inline int imw_row_valid(int key, const imw_site_t *s, unsigned n) {
    if (!n || n > IMW_MAX_SITES) return 0;
    unsigned nw = 0, nh = 0, nf = 0;
    for (unsigned i = 0; i < n; i++) {
        if ((s[i].off & 3) || s[i].off > (1ull << 32)) return 0;
        if (key == IMW_KEY_WINDOW ? s[i].kind > 1 || !(imw_is_movzk(s[i].word) || imw_is_orr_wzr(s[i].word))
                                  : s[i].kind > IMW_F32 || (s[i].kind != IMW_F32 && !imw_is_movzk(s[i].word))) return 0;
        for (unsigned j = 0; j < i; j++) if (s[j].off == s[i].off) return 0;
        if (s[i].kind == IMW_F32) nf++; else if (s[i].kind == IMW_W) nw++; else nh++;
    }
    if (key == IMW_KEY_WINDOW) return nw && nh;
    if (nf) return nf == n;   // an f32 row: every site f32
    if (n & 1) return 0;
    for (unsigned i = 0; i < n; i += 2) {
        const imw_site_t *lo = &s[i], *hi = &s[i + 1];
        if (lo->kind != IMW_LO || hi->kind != IMW_HI) return 0;
        if ((lo->word >> 31) || (hi->word >> 31) || imw_rd(lo->word) != imw_rd(hi->word) || imw_hw(lo->word) != 0 || imw_hw(hi->word) != 1) return 0;
    }
    return 1;
}

// The word a window site gets for the size (0: the site is not a window site or the value does not fit)
static inline int imw_window_word(const imw_site_t *s, unsigned w, unsigned h, uint32_t *out) {
    unsigned v = s->kind == IMW_H ? h : w;
    if (s->kind > 1 || !v || v > IMW_SIZE_MAX) return 0;
    if (imw_is_movzk(s->word)) *out = imw_with_imm16(s->word, v);
    else if (imw_is_orr_wzr(s->word)) *out = 0x52800000u | (v << 5) | imw_rd(s->word);   // orr Wd, wzr, #imm -> movz Wd, #v (never imw_with_imm16 on an orr)
    else return 0;
    return 1;
}
// The word a view-scale site gets for the float's bits (a lo site the low half, a hi site the high half)
static inline int imw_scale_word(const imw_site_t *s, uint32_t bits, uint32_t *out) {
    if (!imw_is_movzk(s->word) || s->kind > 1) return 0;
    *out = imw_with_imm16(s->word, s->kind == IMW_HI ? bits >> 16 : bits & 0xFFFFu);
    return 1;
}

// The transaction: every site is checked (inside [0, text_end), and the word there is the row's) before any is written, so a row is written whole or not at all.
// want[i] is site i's new word. Returns the count written, or IMW_E_SITE / IMW_E_WORD (with *bad = the site's index and *was = the word found)
static inline int imw_commit(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t text_end, const uint32_t *want, unsigned *bad, uint32_t *was) {
    for (unsigned i = 0; i < n; i++) {
        if (s[i].off + 4 > text_end || (s[i].off & 3)) { *bad = i; return IMW_E_SITE; }
        uint32_t cur; memcpy(&cur, image + s[i].off, 4);
        if (cur != s[i].word) { *bad = i; *was = cur; return IMW_E_WORD; }
    }
    for (unsigned i = 0; i < n; i++) memcpy(image + s[i].off, &want[i], 4);
    return (int)n;
}
// window_words: writes w into the w sites and h into the h sites (IMW_E_ROW: a row that is not valid, or a size that does not fit an imm16)
static inline int imw_window_apply(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t text_end, unsigned w, unsigned h, unsigned *bad, uint32_t *was) {
    uint32_t want[IMW_MAX_SITES];
    *bad = 0; *was = 0;
    if (!imw_row_valid(IMW_KEY_WINDOW, s, n)) return IMW_E_ROW;
    for (unsigned i = 0; i < n; i++) if (!imw_window_word(&s[i], w, h, &want[i])) { *bad = i; return IMW_E_ROW; }
    return imw_commit(s, n, image, text_end, want, bad, was);
}
// view_scale_words: writes float(pct / 100) into every lo / hi pair
static inline int imw_scale_apply(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t text_end, unsigned pct, unsigned *bad, uint32_t *was) {
    uint32_t want[IMW_MAX_SITES], bits = imw_float_bits(pct);
    *bad = 0; *was = 0;
    if (!imw_row_valid(IMW_KEY_SCALE, s, n) || pct < IMW_PCT_MIN || pct > IMW_PCT_MAX) return IMW_E_ROW;
    for (unsigned i = 0; i < n; i++) if (!imw_scale_word(&s[i], bits, &want[i])) { *bad = i; return IMW_E_ROW; }
    return imw_commit(s, n, image, text_end, want, bad, was);
}

// PEANUTS(upstream-candidate) begin: f32 view-scale sites and their view_scale_steps row
// The float32 bits are a finite number within 0.001 of pct / 100
static inline int imw_f32_near(uint32_t bits, unsigned pct) {
    if (((bits >> 23) & 0xFFu) == 0xFFu) return 0;   // inf / NaN
    float f; memcpy(&f, &bits, 4);
    double d = (double)f - (double)pct / 100.0;
    return d <= 0.001 && d >= -0.001;
}
// A steps row: 1..IMW_MAX_STEPS entries, P whole 40..100 ascending (so unique), each word near P / 100, an entry 100 (mandatory, so the last) exactly 0x3F800000.
// 1 when valid
static inline int imw_steps_valid(const imw_steps_t *t) {
    if (!t->n || t->n > IMW_MAX_STEPS) return 0;
    for (unsigned i = 0; i < t->n; i++) {
        if (t->pct[i] < IMW_PCT_MIN || t->pct[i] > IMW_PCT_MAX || (i && t->pct[i] <= t->pct[i - 1]) || !imw_f32_near(t->word[i], t->pct[i])) return 0;
        if (t->pct[i] == 100 && t->word[i] != 0x3F800000u) return 0;
    }
    return t->pct[t->n - 1] == 100;   // the entry 100 is mandatory (the launcher's slider always offers 100 %)
}
// The step's word for pct: 1 when the row has that P
static inline int imw_steps_word(const imw_steps_t *t, unsigned pct, uint32_t *out) {
    for (unsigned i = 0; i < t->n && i < IMW_MAX_STEPS; i++) if (t->pct[i] == pct) { *out = t->word[i]; return 1; }
    return 0;
}
// The f32 check: every site inside main's rodata [ro_off, data_off) (both known, not 0), 4-byte aligned, and the word there the row's. 0, or IMW_E_SITE /
// IMW_E_WORD with *bad / *was as imw_commit
static inline int imw_f32_check(const imw_site_t *s, unsigned n, const uint8_t *image, uint64_t ro_off, uint64_t data_off, unsigned *bad, uint32_t *was) {
    for (unsigned i = 0; i < n; i++) {
        if (!ro_off || !data_off || s[i].off < ro_off || s[i].off + 4 > data_off || (s[i].off & 3)) { *bad = i; return IMW_E_SITE; }
        uint32_t cur; memcpy(&cur, image + s[i].off, 4);
        if (cur != s[i].word) { *bad = i; *was = cur; return IMW_E_WORD; }
    }
    return 0;
}
// The f32 commit: imw_f32_check, then every site gets word. The count written, or the error (nothing written)
static inline int imw_f32_commit(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t ro_off, uint64_t data_off, uint32_t word, unsigned *bad, uint32_t *was) {
    int rc = imw_f32_check(s, n, image, ro_off, data_off, bad, was);
    if (rc) return rc;
    for (unsigned i = 0; i < n; i++) memcpy(image + s[i].off, &word, 4);
    return (int)n;
}
// view_scale_words with f32 sites: the steps row's word for pct into every site (IMW_E_ROW: the row or the steps row not valid; IMW_E_STEP: pct is not a step)
static inline int imw_f32_apply(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t ro_off, uint64_t data_off, const imw_steps_t *steps, unsigned pct,
                                unsigned *bad, uint32_t *was) {
    uint32_t word;
    *bad = 0; *was = 0;
    if (!imw_row_valid(IMW_KEY_SCALE, s, n) || s[0].kind != IMW_F32 || !steps || !imw_steps_valid(steps)) return IMW_E_ROW;
    if (!imw_steps_word(steps, pct, &word)) return IMW_E_STEP;
    return imw_f32_commit(s, n, image, ro_off, data_off, word, bad, was);
}
// A steps row's text, "<main build, 32 lowercase hex> <P>:<LE word, 8 hex> ...": the count of entries (build copied to build33), or -1 when not valid
static inline int imw_parse_steps(const char *row, char build33[33], imw_steps_t *out) {
    char buf[1024];
    if (strlen(row) >= sizeof buf) return -1;
    strcpy(buf, row);
    char *save = NULL, *t = strtok_r(buf, " \t", &save);
    if (!t || strlen(t) != 32) return -1;
    for (unsigned i = 0; i < 32; i++) if (!strchr("0123456789abcdef", t[i])) return -1;
    memcpy(build33, t, 33);
    imw_steps_t r; memset(&r, 0, sizeof r);
    while ((t = strtok_r(NULL, " \t", &save))) {
        char *c = strchr(t, ':');
        if (r.n == IMW_MAX_STEPS || !c || c == t || c - t > 3) return -1;
        unsigned p = 0;
        for (char *q = t; q < c; q++) { if (*q < '0' || *q > '9') return -1; p = p * 10 + (unsigned)(*q - '0'); }
        if (!fpw_plan_word(c + 1, &r.word[r.n])) return -1;
        r.pct[r.n++] = p;
    }
    if (!imw_steps_valid(&r)) return -1;
    *out = r;
    return (int)r.n;
}
// PEANUTS end

// PEANUTS(upstream-candidate) begin: a fixed-window build (view_scale_fixed <main build> <W>x<H>). The game keeps its window at W x H; the 3D target size T comes
// from DM_OUT_SIZE=<w>x<h> (unset: T = W x H) and the f32 view_scale_words sites get a ratio, computed in double and cast once to float32:
//   DM_SCENE_PCT=P set:  min(1, T_h * P / (H * 100))
//   P unset (Game default): min(1, T_h / H * the site's own float, the row's expected word); nothing is written when T_h >= H (the game's own values)
// Such a build has no window_words and no view_scale_steps row (game.py; the loader checks again)
#define IMW_FIXED_MIN 16u     // view_scale_fixed W and H: 16..4096 each, 16:9
#define IMW_FIXED_MAX 4096u
#define IMW_E_RATIO (-6)      // a site's ratio is not a finite number above 0 (*bad = the site; nothing written)
// The fixed window's size: 16..4096 each, exactly 16:9. 1 when valid
static inline int imw_fixed_valid(unsigned w, unsigned h) {
    return w >= IMW_FIXED_MIN && w <= IMW_FIXED_MAX && h >= IMW_FIXED_MIN && h <= IMW_FIXED_MAX && (unsigned long long)w * 9u == (unsigned long long)h * 16u;
}
// The output size T: not 0, exactly 16:9, at most IMW_FIXED_MAX lines. 1 when valid
static inline int imw_fixed_out_valid(unsigned tw, unsigned th) {
    return tw && th && th <= IMW_FIXED_MAX && (unsigned long long)tw * 9u == (unsigned long long)th * 16u;
}
// DM_OUT_SIZE: 0 = not set, empty or "original" (T = the fixed size, *tw / *th = w / h), 1 = a valid T, -1 = not <w>x<h> (imw_parse_size), -2 = not 16:9 or taller than
// IMW_FIXED_MAX
static inline int imw_fixed_out(const char *e, unsigned w, unsigned h, unsigned *tw, unsigned *th) {
    unsigned a = 0, b = 0; int r = imw_parse_size(e, &a, &b);
    *tw = w; *th = h;
    if (r <= 0) return r;
    if (!imw_fixed_out_valid(a, b)) return -2;
    *tw = a; *th = b;
    return 1;
}
// One site's ratio as float32 bits: pct 40..100, or 0 for Game default (the site's expected word, a float, is the base). 0 when the ratio is not a finite number above 0
static inline int imw_fixed_ratio(unsigned th, unsigned base_h, unsigned pct, uint32_t site_word, uint32_t *bits) {
    if (!th || !base_h || (pct && (pct < IMW_PCT_MIN || pct > IMW_PCT_MAX))) return 0;
    double r;
    if (pct) r = (double)th * pct / ((double)base_h * 100.0);
    else { float sf; memcpy(&sf, &site_word, 4); r = (double)th / base_h * (double)sf; }
    if (!(r > 0.0) || r - r != 0.0) return 0;   // NaN, inf (a site's float of inf is refused, not capped), 0 or below
    if (r > 1.0) r = 1.0;
    float f = (float)r;
    if (!(f > 0.0f)) return 0;   // below the smallest float32
    memcpy(bits, &f, 4);
    return 1;
}
// The fixed-window write: every f32 site of the row gets its ratio, all or nothing (imw_f32_check: inside rodata [ro_off, data_off), aligned, the expected word).
// pct 0 = Game default. Returns the count written, 0 when nothing is to be written (Game default and th >= base_h), IMW_E_ROW (the row, the fixed size, T or pct
// not valid), IMW_E_RATIO, or imw_f32_check's error; *bad / *was as imw_commit, *first = site 0's new word
static inline int imw_fixed_apply(const imw_site_t *s, unsigned n, uint8_t *image, uint64_t ro_off, uint64_t data_off, unsigned base_w, unsigned base_h,
                                  unsigned tw, unsigned th, unsigned pct, unsigned *bad, uint32_t *was, uint32_t *first) {
    uint32_t want[IMW_MAX_SITES];
    *bad = 0; *was = 0; *first = 0;
    if (!imw_row_valid(IMW_KEY_SCALE, s, n) || s[0].kind != IMW_F32 || !imw_fixed_valid(base_w, base_h) || !imw_fixed_out_valid(tw, th) ||
        (pct && (pct < IMW_PCT_MIN || pct > IMW_PCT_MAX))) return IMW_E_ROW;
    if (!pct && th >= base_h) return 0;
    for (unsigned i = 0; i < n; i++) if (!imw_fixed_ratio(th, base_h, pct, s[i].word, &want[i])) { *bad = i; return IMW_E_RATIO; }
    int rc = imw_f32_check(s, n, image, ro_off, data_off, bad, was);
    if (rc) return rc;
    for (unsigned i = 0; i < n; i++) memcpy(image + s[i].off, &want[i], 4);
    *first = want[0];
    return (int)n;
}
// A view_scale_fixed row's text, "<main build, 32 lowercase hex> <W>x<H>": 1 (build copied to build33), or -1 when not valid
static inline int imw_parse_fixed(const char *row, char build33[33], unsigned *w, unsigned *h) {
    char buf[128];
    if (strlen(row) >= sizeof buf) return -1;
    strcpy(buf, row);
    char *save = NULL, *t = strtok_r(buf, " \t", &save), *z;
    if (!t || strlen(t) != 32) return -1;
    for (unsigned i = 0; i < 32; i++) if (!strchr("0123456789abcdef", t[i])) return -1;
    char b[33]; memcpy(b, t, 33);
    unsigned a = 0, c = 0;
    if (!(z = strtok_r(NULL, " \t", &save)) || imw_parse_size(z, &a, &c) != 1 || strtok_r(NULL, " \t", &save) || !imw_fixed_valid(a, c)) return -1;
    memcpy(build33, b, 33); *w = a; *h = c;
    return 1;
}
// PEANUTS end

// One site's text, "<0xoffset>:<LE word, 8 hex>:<tag>" (the game.tsv form; the word's bytes as they lie in the file, so 15a080d2 is 0xd280a015), tag w / h for
// window_words and lo / hi for view_scale_words. 1 on success
static inline int imw_parse_site(const char *tok, int key, imw_site_t *out) {
    char buf[64];
    if (strlen(tok) >= sizeof buf) return 0;
    strcpy(buf, tok);
    char *c1 = strchr(buf, ':'), *c2 = c1 ? strchr(c1 + 1, ':') : NULL;
    if (!c2 || strchr(c2 + 1, ':')) return 0;
    *c1 = 0; *c2 = 0;
    imw_site_t r = {0};
    if (!fpw_plan_hex(buf, &r.off) || !fpw_plan_word(c1 + 1, &r.word)) return 0;
    const char *t = c2 + 1;
    if (key == IMW_KEY_WINDOW) r.kind = !strcmp(t, "w") ? IMW_W : !strcmp(t, "h") ? IMW_H : 99;
    else r.kind = !strcmp(t, "lo") ? IMW_LO : !strcmp(t, "hi") ? IMW_HI : !strcmp(t, "f32") ? IMW_F32 : 99;   // f32
    if (r.kind == 99) return 0;
    *out = r;
    return 1;
}
// A whole row, "<main build, 32 lowercase hex> <site> <site> ...": the count of sites (build copied to build33), or -1 when it is not valid (imw_row_valid)
static inline int imw_parse_row(const char *row, int key, char build33[33], imw_site_t *out) {
    char buf[1024];
    if (strlen(row) >= sizeof buf) return -1;
    strcpy(buf, row);
    char *save = NULL, *t = strtok_r(buf, " \t", &save);
    if (!t || strlen(t) != 32) return -1;
    for (unsigned i = 0; i < 32; i++) if (!strchr("0123456789abcdef", t[i])) return -1;
    memcpy(build33, t, 33);
    unsigned n = 0;
    while ((t = strtok_r(NULL, " \t", &save))) {
        if (n == IMW_MAX_SITES || !imw_parse_site(t, key, &out[n])) return -1;
        n++;
    }
    return imw_row_valid(key, out, n) ? (int)n : -1;
}

// DM_OPTION_PATCHES rows (fpslocker_plan.h form) of this build that write one of the sites' words: the loader writes window_words / view_scale_words before
// option_apply_patches, which dies when a word is not the one its row expects, so a site an option also writes is left to the option (nothing is written for
// the key). Returns the 1-based index of the first site an option row targets, 0 when none does (or the spec has no rows for the build), IMW_E_SPEC when the spec
// does not parse (option_apply_patches dies on it by itself)
static inline int imw_option_overlap(const char *spec, const char *build, const imw_site_t *s, unsigned n) {
    if (!spec || !*spec) return 0;
    struct fpw_plan p = {0};   // as dm_option_patch (option_patch.h) holds it
    int r = fpw_plan_parse(spec, build, &p);
    if (r < 0) return IMW_E_SPEC;
    for (unsigned k = 0; k < p.n; k++) {
        char wh = p.row[k].where;
        if (wh != 'm' && wh != 'r' && wh != 'o') continue;
        // an f32 site (an image offset in rodata) is also written by FPSLocker r / o rows (absolute image offsets); the other sites by m rows only
        for (unsigned i = 0; i < n; i++) if (p.row[k].off == s[i].off && (wh == 'm' || s[i].kind == IMW_F32)) return (int)i + 1;
    }
    return 0;
}
