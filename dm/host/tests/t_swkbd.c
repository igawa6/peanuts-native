// swkbd unit test (takes seconds): lay out a ShowKeyboardArg by hand following the SDK layout, have swkbd_show write back the String, and all expected bytes are computed by hand.
// Build and run: cd dm/host/tests && clang -O1 -I.. -o /tmp/t_swkbd t_swkbd.c ../hle_swkbd.c && /tmp/t_swkbd
#include "../hle.h"
#include <stdio.h>

Result swkbd_show(void *out, const void *arg, int (*input)(const char *, const char *, const char *, const char *, int, char *, int));
typedef struct { int work, tc_flag, tc_cb, tc_cb_ud, tc_ud; } swkbd_layout_t;   // as hle_swkbd.c
extern swkbd_layout_t g_swkbd_layout;
void swkbd_layout_read(const uint32_t *init_utf8, const uint32_t *tc_cb, const uint32_t *tc_cb_ud);
void *guest_sym(const char *name) { (void)name; return NULL; }   // loader.c; the HLE entry asks it for the SDK's setters, which the cases below hand to swkbd_layout_read directly
int front_text_input(const char *t, const char *s, const char *g, const char *i, int m, char *o, int c) { (void)t; (void)s; (void)g; (void)i; (void)m; (void)o; (void)c; return -1; }

static int fails, checks;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("  ✗ "); printf(__VA_ARGS__); printf("\n"); } } while (0)

// Input stub: record the arguments received and return as configured
static const char *g_reply, *g_reply2; static int g_ret, g_called; static char g_title[256], g_init[256], g_sub[256]; static int g_max;
static int stub(const char *t, const char *s, const char *g, const char *i, int m, char *o, int c) {
    (void)g; g_called++; snprintf(g_title, sizeof g_title, "%s", t); snprintf(g_init, sizeof g_init, "%s", i); snprintf(g_sub, sizeof g_sub, "%s", s); g_max = m;
    if (g_ret > 0) snprintf(o, c, "%s", g_called > 1 && g_reply2 ? g_reply2 : g_reply);
    return g_ret;
}

// Text check callbacks (the game's, which swkbd_show calls): refuse "Bad" with a UTF-16 message, accept anything else; record what they saw
static int g_tc_calls; static uint64_t g_tc_size; static char g_tc_text[64]; static void *g_tc_user;
static uint32_t tc_check(void *dialog, uint64_t *len, void *text) {
    struct { char *p; uint64_t n; } *s = text; g_tc_calls++; g_tc_size = s->n; snprintf(g_tc_text, sizeof g_tc_text, "%s", s->p);
    if (strcmp(s->p, "Bad")) return 0;
    memcpy(dialog, "No", 3); *len = 2;   // the dialog text in the argument's encoding: UTF-8 here (isUseUtf8 = 1, as these cases set it)
    return 1;
}
static int cancel_second(const char *t, const char *s, const char *g, const char *i, int m, char *o, int c) {   // types the reply, then cancels the box that comes back
    int r = stub(t, s, g, i, m, o, c); return g_called > 1 ? 0 : r;
}
static uint32_t tc_check_ud(void *dialog, uint64_t *len, void *text, void *user) { g_tc_user = user; return tc_check(dialog, len, text); }
// AArch64 encodings for the setters' shapes (hand-built: str / strb / ldr with an unsigned offset, mov x<d>, x0, ret)
static uint32_t a_str(int t, int n, int off) { return 0xF9000000u | (uint32_t)(off / 8) << 10 | (uint32_t)n << 5 | (uint32_t)t; }
static uint32_t a_ldr(int t, int n, int off) { return 0xF9400000u | (uint32_t)(off / 8) << 10 | (uint32_t)n << 5 | (uint32_t)t; }
static uint32_t a_strb(int t, int n, int off) { return 0x39000000u | (uint32_t)off << 10 | (uint32_t)n << 5 | (uint32_t)t; }
static uint32_t a_mov0(int d) { return 0xAA0003E0u | (uint32_t)d; }
#define A_RET 0xD65F03C0u
#define A_NOP 0xD503201Fu

static uint8_t arg[0x600], work[0x1000], sbuf[0x7d4];
static struct { uint8_t *p; uint64_t n; } str = {sbuf, sizeof sbuf};
static void mkarg(int utf8, int maxlen, const uint16_t *init, int ilen) {
    memset(arg, 0, sizeof arg); memset(work, 0, sizeof work); memset(sbuf, 0xAA, sizeof sbuf);
    str.n = sizeof sbuf;   // a fresh String per call, its capacity at +8 (the SDK only reads it)
    static const uint16_t title[] = {0x8D77, 0x540D, 0};                 // U+8D77 U+540D, the game's "Name your character" title
    memcpy(arg + 0x24, title, sizeof title);
    memcpy(arg + 0x3ac, &maxlen, 4); arg[0x3bd] = (uint8_t)utf8;
    int off = 0x14; memcpy(arg + 0x3c0, &off, 4); memcpy(arg + 0x3c4, &ilen, 4);   // the way SetInitialTextUtf8 writes it
    memcpy(work + off, init, (size_t)ilen * 2);
    uint8_t *w = work; memcpy(arg + g_swkbd_layout.work, &w, 8);
    g_called = 0; g_reply2 = NULL; g_tc_calls = 0; g_tc_user = NULL;
}
static int bytes_eq(const uint8_t *want, size_t n) {   // the first n bytes equal the expectation, and everything after to the end of the buffer is 0
    if (memcmp(sbuf, want, n)) return 0;
    for (size_t i = n; i < sizeof sbuf; i++) if (sbuf[i]) return 0;
    return 1;
}

void dm_applet_enter(const char *name) { (void)name; }   // hle_oe.c: ShowKeyboard holds the game out of focus; swkbd_show, tested here, does not
void dm_applet_leave(void) {}
void guest_clock_freeze_for(int reason, int on) { (void)reason; (void)on; }   // the real implementation is in hle_os.c; the unit test does not care about the clock
int main(void) {
    unsetenv("DM_KBD");
    static const uint16_t hero[] = {'H', 'e', 'r', 'o'};

    printf("1 OK, UTF-8 output (what the game actually uses)\n");
    mkarg(1, 16, hero, 4); g_ret = 1; g_reply = "Ünï\xE5\x90\x8D";   // reply with a CJK character (U+540D, UTF-8 escaped)
    Result r = swkbd_show(&str, arg, stub);
    static const uint8_t w1[] = {0xC3, 0x9C, 0x6E, 0xC3, 0xAF, 0xE5, 0x90, 0x8D, 0x00};   // U+00DC n U+00EF U+540D + terminating 0
    CHECK(r == 0, "Result %#x expected 0", r);
    CHECK(bytes_eq(w1, sizeof w1), "UTF-8 write-back bytes differ");
    CHECK(str.n == sizeof sbuf, "String byte count %llu changed, the SDK leaves the capacity", (unsigned long long)str.n);
    CHECK(!strcmp(g_title, "\xE8\xB5\xB7\xE5\x90\x8D"), "title decoded as '%s', expected U+8D77 U+540D", g_title);
    CHECK(!strcmp(g_init, "Hero"), "initial text decoded as '%s', expected Hero", g_init);
    CHECK(g_max == 16, "limit %d expected 16", g_max);

    printf("2 cancel → 0x29F, buffer untouched\n");
    mkarg(1, 16, hero, 4); g_ret = 0;
    r = swkbd_show(&str, arg, stub);
    CHECK(r == 0x29F, "Result %#x expected 0x29F", r);
    CHECK(sbuf[0] == 0xAA && sbuf[sizeof sbuf - 1] == 0xAA, "cancel changed the buffer");
    CHECK(str.n == sizeof sbuf, "cancel changed the String byte count");

    printf("3 no window (input returns -1) → treated as cancel 0x29F\n");
    mkarg(1, 16, hero, 4); g_ret = -1;
    CHECK(swkbd_show(&str, arg, stub) == 0x29F, "no window did not return cancel");

    printf("4 UTF-16 output (isUseUtf8 = 0)\n");
    mkarg(0, 16, hero, 0); g_ret = 1; g_reply = "A\xE5\x90\x8D";   // 'A' + U+540D (UTF-8 escaped)
    r = swkbd_show(&str, arg, stub);
    static const uint8_t w4[] = {0x41, 0x00, 0x0D, 0x54, 0x00, 0x00};   // 'A' U+540D terminating 0 (little endian)
    CHECK(r == 0 && bytes_eq(w4, sizeof w4), "UTF-16 write-back bytes differ");
    CHECK(str.n == sizeof sbuf, "String byte count %llu changed, the SDK leaves the capacity", (unsigned long long)str.n);
    CHECK(g_init[0] == 0, "initial length 0 should give no initial text");

    printf("5 maximum length truncation (3 characters)\n");
    mkarg(1, 3, hero, 4); g_ret = 1; g_reply = "ABCDE";
    swkbd_show(&str, arg, stub);
    static const uint8_t w5[] = {'A', 'B', 'C', 0};
    CHECK(bytes_eq(w5, sizeof w5), "truncation result differs");
    CHECK(str.n == sizeof sbuf, "String byte count %llu changed, the SDK leaves the capacity", (unsigned long long)str.n);

    printf("6 truncation does not split a surrogate pair (limit 2 units, a + U+1F600 needs 3 units)\n");
    mkarg(1, 2, hero, 4); g_ret = 1; g_reply = "a\xF0\x9F\x98\x80";
    swkbd_show(&str, arg, stub);
    static const uint8_t w6[] = {'a', 0};
    CHECK(bytes_eq(w6, sizeof w6), "surrogate pair split or not truncated");

    printf("7 DM_KBD gives the text directly: input box not called\n");
    setenv("DM_KBD", "Kbd", 1);
    mkarg(1, 16, hero, 4); g_ret = 0;
    r = swkbd_show(&str, arg, stub);
    static const uint8_t w7[] = {'K', 'b', 'd', 0};
    CHECK(r == 0 && bytes_eq(w7, sizeof w7) && g_called == 0, "DM_KBD path differs (input box called %d times)", g_called);
    unsetenv("DM_KBD");

    printf("8 layout read off the setters' shapes (SDK 18.3.5: work buffer +0x518, flag +0x3d0, callbacks +0x3d8 / +0x4c8, user data +0x4d0)\n");
    const uint32_t init_set[] = {A_NOP, a_mov0(20), A_NOP, 0xB983C288u /* ldrsw x8, [x20, #0x3c0]: not a 64-bit ldr */, a_ldr(9, 20, 0x518), A_RET};
    const uint32_t cb_set[] = {A_NOP, 0x52800028u /* mov w8, #1 */, a_str(1, 0, 0x3d8), a_strb(8, 0, 0x3d0), A_RET};
    const uint32_t cbud_set[] = {A_NOP, 0x52800028u, a_str(1, 0, 0x4c8), a_str(2, 0, 0x4d0), a_strb(8, 0, 0x3d0), A_RET};
    swkbd_layout_read(init_set, cb_set, cbud_set);
    CHECK(g_swkbd_layout.work == 0x518 && g_swkbd_layout.tc_flag == 0x3d0 && g_swkbd_layout.tc_cb == 0x3d8 && g_swkbd_layout.tc_cb_ud == 0x4c8 && g_swkbd_layout.tc_ud == 0x4d0,
          "layout work %#x flag %#x cb %#x cb_ud %#x ud %#x", g_swkbd_layout.work, g_swkbd_layout.tc_flag, g_swkbd_layout.tc_cb, g_swkbd_layout.tc_cb_ud, g_swkbd_layout.tc_ud);

    printf("9 the initial text comes from the work buffer at the SDK's offset (+0x518, +0x4d0 being the user data)\n");
    mkarg(1, 16, hero, 4); g_ret = 1; g_reply = "Ok";
    { void *junk = (void *)0x10; memcpy(arg + 0x4d0, &junk, 8); }   // user data there: the old fixed offset would read it as the work buffer
    r = swkbd_show(&str, arg, stub);
    CHECK(r == 0 && !strcmp(g_init, "Hero"), "initial text '%s' (Result %#x)", g_init, r);

    printf("10 text check off (flag 0): the callback is not called\n");
    mkarg(1, 16, hero, 0); g_ret = 1; g_reply = "Bad";
    { void *f = (void *)tc_check; memcpy(arg + 0x3d8, &f, 8); }
    r = swkbd_show(&str, arg, stub);
    CHECK(r == 0 && g_tc_calls == 0 && g_called == 1, "flag off: %d callback calls, %d input boxes", g_tc_calls, g_called);

    printf("11 text check refuses, the box comes back with the game's message and the text; the next text is accepted\n");
    mkarg(1, 16, hero, 0); g_ret = 1; g_reply = "Bad"; g_reply2 = "Good";
    { void *f = (void *)tc_check; memcpy(arg + 0x3d8, &f, 8); arg[0x3d0] = 1; }
    r = swkbd_show(&str, arg, stub);
    static const uint8_t w11[] = {'G', 'o', 'o', 'd', 0};
    CHECK(r == 0 && bytes_eq(w11, sizeof w11), "Result %#x or bytes differ", r);
    CHECK(g_called == 2 && g_tc_calls == 2, "%d input boxes, %d callback calls (expected 2 and 2)", g_called, g_tc_calls);
    CHECK(!strcmp(g_sub, "No") && !strcmp(g_init, "Bad"), "second box: sub '%s' initial '%s'", g_sub, g_init);
    CHECK(!strcmp(g_tc_text, "Good") && g_tc_size == 0x7d4, "callback saw '%s' with size %#llx", g_tc_text, (unsigned long long)g_tc_size);
    CHECK(str.n == sizeof sbuf, "String +8 not restored after the callback: %llu", (unsigned long long)str.n);

    printf("12 the overload with user data is called with it, before the plain one\n");
    mkarg(1, 16, hero, 0); g_ret = 1; g_reply = "Fine";
    { void *f = (void *)tc_check_ud, *u = (void *)0x1234, *f1 = (void *)tc_check; memcpy(arg + 0x4c8, &f, 8); memcpy(arg + 0x4d0, &u, 8); memcpy(arg + 0x3d8, &f1, 8); arg[0x3d0] = 1; }
    r = swkbd_show(&str, arg, stub);
    CHECK(r == 0 && g_tc_calls == 1 && g_tc_user == (void *)0x1234, "Result %#x, %d calls, user %p", r, g_tc_calls, g_tc_user);

    printf("13 refused by the text check with DM_KBD (cannot type another) → cancel; the player cancelling the second box → cancel\n");
    setenv("DM_KBD", "Bad", 1);
    mkarg(1, 16, hero, 0); { void *f = (void *)tc_check; memcpy(arg + 0x3d8, &f, 8); arg[0x3d0] = 1; }
    CHECK(swkbd_show(&str, arg, stub) == 0x29F && g_called == 0, "DM_KBD refused text did not cancel");
    unsetenv("DM_KBD");
    mkarg(1, 16, hero, 0); g_ret = 1; g_reply = "Bad"; { void *f = (void *)tc_check; memcpy(arg + 0x3d8, &f, 8); arg[0x3d0] = 1; }
    r = swkbd_show(&str, arg, cancel_second);
    CHECK(r == 0x29F && g_called == 2, "cancel on the second box: Result %#x, %d boxes", r, g_called);

    printf("14 an SDK without the setters keeps the first layout and no text check\n");
    swkbd_layout_read(NULL, NULL, NULL);
    CHECK(g_swkbd_layout.work == 0x4d0 && g_swkbd_layout.tc_flag == -1, "defaults: work %#x flag %d", g_swkbd_layout.work, g_swkbd_layout.tc_flag);

    printf("%s: %d/%d\n", fails ? "FAILED" : "all passed", checks - fails, checks);
    return fails != 0;
}
