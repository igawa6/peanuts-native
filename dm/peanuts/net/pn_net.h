// PEANUTS: online play on Nextendo Network (README.md in this folder). Off unless PN_ONLINE=1 is in the host's environment (env.txt), so a normal run keeps upstream's
// offline answers untouched.
//
// The online hooks live in their own table (the pnforce section, PN_FORCE below), not in upstream's swforce: the loader refuses a name twice in one table, and upstream's
// offline hooks must stay as they are for every run with Online off. loader.c's force_lookup asks pn_force_lookup first (one PEANUTS line); a hit there wins.
#pragma once
#include "../../host/hle.h"
#include <stddef.h>
#include <stdint.h>

// Same shape as HLE_FORCE (hle.h), in the pnforce table
#define PN_FORCE(mangled, why, ret, cname, ...)                                                    \
    static ret cname(__VA_ARGS__);                                                                \
    PLAT_TABLE(pnforce) static const hle_force_ent pn_force_##cname = {mangled, (void *)cname, why}; \
    static ret cname(__VA_ARGS__)
// Same as HLE_FORCE_BIG (hle.h): the implementation runs on the thread's big host stack. For calls into Android's resolver and interface list, which use several KB of stack,
// more than a game thread's own (it can be 8 KB). Register arguments only
#define PN_FORCE_BIG(mangled, why, ret, cname, ...)                                                \
    static ret cname(__VA_ARGS__) __attribute__((used));                                          \
    extern const char cname##_bs[];                                                               \
    __asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(cname##_bs) "\n" PLAT_ASM_HIDDEN " " PLAT_ASM_SYM(cname##_bs) "\n" PLAT_ASM_SYM(cname##_bs) ":\n" \
            "    adrp x16, " PLAT_ASM_PAGE(cname) "\n    add x16, x16, " PLAT_ASM_PAGEOFF(cname) "\n    b " PLAT_ASM_SYM(dm_bigstack_enter) "\n"); \
    PLAT_TABLE(pnforce) static const hle_force_ent pn_force_##cname = {mangled, (void *)(uintptr_t)cname##_bs, why}; \
    static ret cname(__VA_ARGS__)

int pn_online(void);                          // PN_ONLINE=1 (read once)
void *pn_force_lookup(const char *name);      // the online implementation of name, or NULL (Online off, or not one of ours)
void *pn_upstream(const char *name);          // upstream's forced implementation of name (swforce), for the cases ours hands back (eventfds)

// The host name a redirected address stands in for (DNS redirect in pn_net.c), for SSL's server name; NULL when the address was never redirected. Network byte order
const char *pn_redirected_host(uint32_t addr);
int pn_sock_host(int g);     // Android's descriptor behind the game's socket g, or -1 (pn_net.c)
int pn_sock_close(int g);    // closes the game's socket g as its Close would (pn_net.c)

// pn_ssl.c
int pn_ssl_pending(int g);                                   // decrypted bytes waiting in the ssl connections over the game's socket g (socket Poll counts them as readable)
int pn_random(void *unused, unsigned char *out, size_t n);   // Mbed TLS's f_rng shape: the shared DRBG
