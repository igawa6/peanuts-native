// PEANUTS: the optimization switches (dm/peanuts/peanuts_opt.c), run on a Linux PC: the defaults, names, "all", "none", "default", "-name" and "!name", the order of
// the tokens, separators, unknown names counted, pn_opt_set, and the environment read of the first call.
#include "../../peanuts/peanuts_opt.c"
#include <stdio.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

int main(void) {
    check(PN_OPT_COUNT >= 2, "the table has its options");
    check(pn_opt_name(0) != NULL && pn_opt_doc(0) != NULL, "name and doc of option 0");
    check(pn_opt_name(-1) == NULL && pn_opt_name(PN_OPT_COUNT) == NULL, "out-of-range index has no name");
    check(!pn_opt_enabled((pn_opt_id)-1) && !pn_opt_enabled(PN_OPT_COUNT), "out-of-range id is off");

    // the environment is read on the first call
    setenv("PN_OPTS", "mem_watch", 1);
    check(pn_opt_enabled(PN_OPT_MEM_WATCH) == 1, "first call reads PN_OPTS: mem_watch on");
    check(pn_opt_enabled(PN_OPT_BOTTLENECK) == 0, "an option not named stays at its default (off)");

    check(pn_opt_init("") == 0 && !pn_opt_enabled(PN_OPT_MEM_WATCH) && !pn_opt_enabled(PN_OPT_BOTTLENECK), "empty spec: defaults");
    check(pn_opt_init("all") == 0 && pn_opt_enabled(PN_OPT_MEM_WATCH) && pn_opt_enabled(PN_OPT_BOTTLENECK), "all: every option on");
    check(pn_opt_init("all,-bottleneck") == 0 && pn_opt_enabled(PN_OPT_MEM_WATCH) && !pn_opt_enabled(PN_OPT_BOTTLENECK), "all then -name: that one off");
    check(pn_opt_init("all !mem_watch") == 0 && !pn_opt_enabled(PN_OPT_MEM_WATCH) && pn_opt_enabled(PN_OPT_BOTTLENECK), "!name with a space separator");
    check(pn_opt_init("-bottleneck;bottleneck") == 0 && pn_opt_enabled(PN_OPT_BOTTLENECK), "later token wins; semicolon separator");
    check(pn_opt_init("all,none") == 0 && !pn_opt_enabled(PN_OPT_MEM_WATCH) && !pn_opt_enabled(PN_OPT_BOTTLENECK), "none switches everything off");
    check(pn_opt_init("all,default") == 0 && !pn_opt_enabled(PN_OPT_MEM_WATCH), "default returns to the table's defaults");
    check(pn_opt_init("  ,, mem_watch ,, ") == 0 && pn_opt_enabled(PN_OPT_MEM_WATCH), "extra separators and spaces");
    check(pn_opt_init("nosuch,mem_watch,-alsono,-") == 3 && pn_opt_enabled(PN_OPT_MEM_WATCH), "unknown names and a lone '-' are counted (3), the rest applies");
    check(pn_opt_init("mem_watc") == 1 && !pn_opt_enabled(PN_OPT_MEM_WATCH), "a prefix of a name is not the name");
    check(pn_opt_init("mem_watchx") == 1 && !pn_opt_enabled(PN_OPT_MEM_WATCH), "a name with extra text is not the name");

    // init(NULL) goes back to the environment
    setenv("PN_OPTS", "bottleneck", 1);
    check(pn_opt_init(NULL) == 0 && pn_opt_enabled(PN_OPT_BOTTLENECK) && !pn_opt_enabled(PN_OPT_MEM_WATCH), "init(NULL) reads PN_OPTS again");
    unsetenv("PN_OPTS");
    check(pn_opt_init(NULL) == 0 && !pn_opt_enabled(PN_OPT_BOTTLENECK), "no PN_OPTS: defaults");

    check(pn_opt_set("mem_watch", 1) == 0 && pn_opt_enabled(PN_OPT_MEM_WATCH), "pn_opt_set on");
    check(pn_opt_set("mem_watch", 0) == 0 && !pn_opt_enabled(PN_OPT_MEM_WATCH), "pn_opt_set off");
    check(pn_opt_set("nosuch", 1) == -1 && pn_opt_set(NULL, 1) == -1, "pn_opt_set on an unknown name or NULL: -1");

    for (int i = 0; i < PN_OPT_COUNT; i++) check(pn_opt_default(i) == 0, "every experimental option is off by default");

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
