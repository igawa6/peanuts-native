// PEANUTS: the online account for Nextendo Network (PN_ONLINE=1), replacing hle_account.c's "network service account unavailable".
//
// The network service account is the Nextendo account: its ID is the Nextendo PID, and its id_token is a BAAS-shaped JWT (RS256) whose "nnex" claim carries Nextendo's
// signed 24-hour game token, which the game servers verify. The JWT's own signature is checked by no server but Splatoon 2, so a key made on the phone for this run does
// (the token layout of Nextendo's reference emulator, Ryujinx-Nextendo, as citron-nextendo and Lemon build it, GPL-3.0-or-later).
// The PID and game token come from PN_NEXTENDO_PID / PN_NEXTENDO_TOKEN for now, until the launcher's sign-in hands them over. Without them the account is 0xcafe (as in
// Lemon) and "nnex" is empty: the servers turn it away, which still shows the game got that far. Neither value is ever logged.
//
// Also the error applet, on the big host stack: upstream's hooks (hle_offline.c, hle_misc.c) print on the game thread's own stack, and Mario Kart 8 Deluxe's main thread
// reaches nn::err::ShowError(const ErrorResultVariant &) online with too little of it left: Android's vfprintf ran into the guard page (sig 11 in libc, sp just above the
// stack's end). Offline the game never got there. These versions decode the error code too.
#include "pn_net.h"
#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <mbedtls/base64.h>
#include <mbedtls/md.h>
#include <mbedtls/pk.h>
#include <mbedtls/rsa.h>

#define WHY "online (dm/peanuts/net)"
#define UP(type, name) ((type)pn_upstream(name))
static void code_of(Result r, unsigned *a, unsigned *b) { *a = 2000 + (r & 0x1ff); *b = (r >> 9) & 0x1fff; }   // a Result as the console shows it: 2<module>-<description>

// ---------- error applet ----------
PN_FORCE_BIG("_ZN2nn3err9ShowErrorENS_6ResultE", WHY ": error applet, logged on the host stack", void, pn_err_ShowRes, Result r) {
    unsigned a, b; code_of(r, &a, &b); DM_APPLET("error"); LOG("online: game asks to show error %04u-%04u (Result %#x)", a, b, r);
}
PN_FORCE_BIG("_ZN2nn3err9ShowErrorENS0_9ErrorCodeE", WHY ": error applet, logged on the host stack", void, pn_err_ShowCode, uint64_t c) {
    DM_APPLET("error"); LOG("online: game asks to show error %04u-%04u", (unsigned)(c & 0xffffffff), (unsigned)(c >> 32));
}
// ErrorResultVariant: a state byte (1: holds a Result, 2: holds an ErrorCode {u32 category, u32 number}), the value 4 bytes in (seen in Mario Kart 8 Deluxe: 02 / 0x872 / 1 for 2162-0001)
PN_FORCE_BIG("_ZN2nn3err9ShowErrorERKNS0_18ErrorResultVariantE", WHY ": error applet, logged on the host stack", void, pn_err_ShowVar, const uint8_t *v) {
    DM_APPLET("error");
    if (!v) { LOG("online: game asks to show an error (no variant)"); return; }
    uint32_t w[4]; memcpy(w, v, sizeof w);
    if (v[0] == 1) { unsigned a, b; code_of(w[1], &a, &b); LOG("online: game asks to show error %04u-%04u (Result %#x)", a, b, w[1]); }
    else if (v[0] == 2) LOG("online: game asks to show error %04u-%04u", w[1], w[2]);
    else LOG("online: game asks to show an error (variant %08x %08x %08x %08x)", w[0], w[1], w[2], w[3]);
}

// ---------- the Nextendo identity ----------
static uint64_t g_pid = 0xcafe; static char g_nex[2048];
static pthread_once_t g_id_once = PTHREAD_ONCE_INIT;
static void id_init(void) {
    const char *p = getenv("PN_NEXTENDO_PID"), *t = getenv("PN_NEXTENDO_TOKEN");
    if (p && *p) g_pid = strtoull(p, NULL, 0);
    if (t) snprintf(g_nex, sizeof g_nex, "%s", t);
    LOG("online: account: %s, %s", g_pid != 0xcafe ? "a Nextendo PID" : "no Nextendo PID (0xcafe: the servers will turn it away)", g_nex[0] ? "a game token" : "no game token");
}

static size_t b64url(const unsigned char *in, size_t n, char *out, size_t cap) {
    size_t k = 0;
    if (mbedtls_base64_encode((unsigned char *)out, cap, &k, in, n)) return 0;
    while (k && out[k - 1] == '=') k--;
    for (size_t i = 0; i < k; i++) out[i] = out[i] == '+' ? '-' : out[i] == '/' ? '_' : out[i];
    out[k] = 0; return k;
}
static void hex(char *out, size_t bytes) { unsigned char b[32]; pn_random(NULL, b, bytes); for (size_t i = 0; i < bytes; i++) sprintf(out + 2 * i, "%02x", b[i]); }

static mbedtls_pk_context g_key; static int g_key_ok;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
static void key_init(void) {   // RSA-2048, made once per run (a second or two on the phone)
    struct timespec a, b; clock_gettime(CLOCK_MONOTONIC, &a);
    mbedtls_pk_init(&g_key);
    g_key_ok = !mbedtls_pk_setup(&g_key, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA)) && !mbedtls_rsa_gen_key(mbedtls_pk_rsa(g_key), pn_random, NULL, 2048, 65537);
    clock_gettime(CLOCK_MONOTONIC, &b);
    LOG("online: account: id_token signing key %s (%lld ms)", g_key_ok ? "made" : "[RED] could not be made", (long long)((b.tv_sec - a.tv_sec) * 1000 + (b.tv_nsec - a.tv_nsec) / 1000000));
}

// The id_token: header and claims as Ryujinx-Nextendo / citron-nextendo / Lemon make them
static int make_token(char *out, size_t cap) {
    pthread_once(&g_id_once, id_init); pthread_once(&g_key_once, key_init);
    if (!g_key_ok) return -1;
    static const char *iss = "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com", *jku = "https://e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com/1.0.0/certificates";
    long long now = (long long)time(NULL);
    char sub[33], di[33], bsdid[33], jti[37], r[33];
    hex(sub, 16); hex(di, 16); hex(bsdid, 16); hex(r, 16);
    snprintf(jti, sizeof jti, "%.8s-%.4s-4%.3s-a%.3s-%.12s", r, r + 8, r + 12, r + 15, r + 18);
    char head[256], *body = malloc(4096); if (!body) return -1;
    snprintf(head, sizeof head, "{\"alg\":\"RS256\",\"kid\":\"nextendo-baas-key-1\",\"typ\":\"id_token\",\"jku\":\"%s\"}", jku);
    snprintf(body, 4096, "{\"sub\":\"%s\",\"aud\":\"ed9e2f05d286f7b8\",\"iss\":\"%s\",\"typ\":\"id_token\",\"iat\":%lld,\"exp\":%lld,\"jku\":\"%s\",\"jti\":\"%s\","
             "\"di\":\"%s\",\"sn\":\"XAW10000000000\",\"bs:did\":\"%s\",\"hm\":true,"
             "\"nintendo\":{\"dt\":\"NX Prod 1\",\"pc\":\"HAC\",\"di\":\"%s\",\"sn\":\"XAW10000000000\",\"ist\":false},\"nnex\":\"%s\"}",
             sub, iss, now, now + 3 * 60 * 60, jku, jti, di, bsdid, di, g_nex);
    size_t k = b64url((const unsigned char *)head, strlen(head), out, cap);
    if (!k || k + 2 >= cap) { free(body); return -1; }
    out[k++] = '.';
    size_t m = b64url((const unsigned char *)body, strlen(body), out + k, cap - k); free(body);
    if (!m || k + m + 2 >= cap) return -1;
    k += m;
    unsigned char hash[32], sig[512]; size_t sl = 0;
    if (mbedtls_md(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), (const unsigned char *)out, k, hash) ||
        mbedtls_pk_sign(&g_key, MBEDTLS_MD_SHA256, hash, sizeof hash, sig, sizeof sig, &sl, pn_random, NULL)) return -1;
    out[k++] = '.';
    m = b64url(sig, sl, out + k, cap - k);
    return m ? (int)(k + m) : -1;
}

// ---------- network service account ----------
PN_FORCE_BIG("_ZN2nn7account32IsNetworkServiceAccountAvailableEPbRKNS0_10UserHandleE", WHY ": the Nextendo account", Result, pn_acc_IsNsa, uint8_t *out, const void *u) {
    (void)u; if (out) *out = 1; return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7account36EnsureNetworkServiceAccountAvailableERKNS0_10UserHandleE", WHY ": the Nextendo account", Result, pn_acc_EnsureNsa, const void *u) { (void)u; return RES_OK; }
PN_FORCE_BIG("_ZN2nn7account26GetNetworkServiceAccountIdEPNS0_23NetworkServiceAccountIdERKNS0_10UserHandleE", WHY ": the Nextendo PID", Result, pn_acc_NsaId, uint64_t *id, const void *u) {
    (void)u; pthread_once(&g_id_once, id_init); if (id) *id = g_pid; return RES_OK;
}
// The token is made at the first load; the async request only has to succeed (its context completes at once, upstream's GetSystemEvent / HasDone)
PN_FORCE_BIG("_ZN2nn7account44EnsureNetworkServiceAccountIdTokenCacheAsyncEPNS0_12AsyncContextERKNS0_10UserHandleE", WHY ": succeeds", Result, pn_acc_EnsureToken, void *ctx, const void *u) {
    (void)ctx; (void)u; pthread_once(&g_key_once, key_init); return RES_OK;
}
PN_FORCE_BIG("_ZN2nn7account12AsyncContext9GetResultEv", WHY ": the account's requests succeed", Result, pn_acc_AsyncResult, void *self) { (void)self; return RES_OK; }
PN_FORCE_BIG("_ZN2nn7account37LoadNetworkServiceAccountIdTokenCacheEPmPcmRKNS0_10UserHandleE", WHY ": the Nextendo id_token", Result, pn_acc_LoadToken, uint64_t *n, char *b, uint64_t cap, const void *u) {
    (void)u;
    char *t = malloc(8192); int k = t ? make_token(t, 8192) : -1;
    if (k < 0 || !b || (uint64_t)k >= cap) { LOG("online: account: id_token %s (buffer %" PRIu64 ")", k < 0 ? "[RED] not made" : "does not fit", cap); free(t); if (n) *n = 0; return RES_FAIL; }
    memcpy(b, t, (size_t)k + 1); free(t);
    if (n) *n = (uint64_t)k;
    LOG("online: account: id_token handed out (%d bytes)", k);
    return RES_OK;
}
// Still upstream's answer (no Nintendo Account info), traced
PN_FORCE_BIG("_ZN2nn7account29LoadCachedNintendoAccountInfoEPNS0_25CachedNintendoAccountInfoERKNS0_10UserHandleEPvm", WHY ": upstream's answer, traced", Result, pn_acc_NaInfo, void *info, const void *u, void *buf, uint64_t n) {
    Result r = UP(Result (*)(void *, const void *, void *, uint64_t), "_ZN2nn7account29LoadCachedNintendoAccountInfoEPNS0_25CachedNintendoAccountInfoERKNS0_10UserHandleEPvm")(info, u, buf, n);
    unsigned a, c; code_of(r, &a, &c); LOG("online: account LoadCachedNintendoAccountInfo -> %04u-%04u (upstream's: no Nintendo Account)", a, c); return r;
}
