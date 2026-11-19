// PEANUTS: server certificate verification for nn::ssl (pn_ssl.c), on Mbed TLS. Verification is always required, with the host name checked:
//
// - A connection to an address of the DNS redirect (pn_net.c: Nintendo's hosts sent to Nextendo Network) trusts Nextendo Network's own CA, which Nextendo
//   publishes for its clients (pn_nextendo_ca.h): its account and NAT-check servers present certificates it issued, with their host names (checked 2026-11-16).
// - Nextendo's game servers still present a self-signed certificate with no host name (CN=nintendo-private-server; Mario Kart 8 Deluxe's
//   g2b309e01-lp1.s.n.srv.nintendo.net on both of Nextendo's addresses), which no CA can vouch for. A redirected connection alone also takes a server
//   whose public key is one of the pinned keys below (SHA-256 of its SubjectPublicKeyInfo), or of PN_NEXTENDO_PIN's (64 hex digits each, comma-separated),
//   whatever its name: the key is the identity there. Nothing else is accepted on a redirected connection.
// - Every other connection trusts the phone's system CAs (Android's conscrypt store), as an app's would. The redirect's exception never applies to it.
//
// The game's own verification settings (SetVerifyOption, SkipDefaultVerify, ImportServerPki) are not taken: the certificates a game expects are Nintendo's,
// which no server it reaches here presents.
#include "pn_trust.h"
#include "pn_nextendo_ca.h"
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>
#include <mbedtls/x509_crt.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static const char *const g_default_pins[] = {
    "770551f13152f3d55370de6c039d3859cff0d39882f27c6c9fc6cfdb327d74b8",   // CN=nintendo-private-server, RSA-2048, self-signed (2026-07-13 to 2036-07-10)
};
static const char *const g_android_dirs[] = {"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts", NULL};
static const char *const *g_dirs = g_android_dirs;

static mbedtls_x509_crt g_system, g_nextendo;
static int g_nsystem;
static uint8_t g_pins[16][32]; static unsigned g_npins;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static int hexval(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
static void add_pin(const char *s, size_t n) {
    while (n && (*s == ' ')) s++, n--;
    while (n && s[n - 1] == ' ') n--;
    if (n != 64 || g_npins >= sizeof g_pins / sizeof *g_pins) return;
    uint8_t p[32];
    for (int i = 0; i < 32; i++) { int a = hexval(s[2 * i]), b = hexval(s[2 * i + 1]); if (a < 0 || b < 0) return; p[i] = (uint8_t)(a << 4 | b); }
    memcpy(g_pins[g_npins++], p, 32);
}
static void init(void) {
    mbedtls_x509_crt_init(&g_system); mbedtls_x509_crt_init(&g_nextendo);
    for (unsigned i = 0; g_dirs[i] && !g_nsystem; i++) {   // the first store that has certificates (Android 14 and later: the conscrypt module's)
        mbedtls_x509_crt_parse_path(&g_system, g_dirs[i]);
        for (const mbedtls_x509_crt *c = &g_system; c && c->raw.len; c = c->next) g_nsystem++;
    }
    mbedtls_x509_crt_parse(&g_nextendo, (const unsigned char *)pn_nextendo_ca_pem, sizeof pn_nextendo_ca_pem);
    for (unsigned i = 0; i < sizeof g_default_pins / sizeof *g_default_pins; i++) add_pin(g_default_pins[i], strlen(g_default_pins[i]));
    const char *e = getenv("PN_NEXTENDO_PIN");
    for (const char *p = e; p && *p;) { const char *q = strchr(p, ','); size_t n = q ? (size_t)(q - p) : strlen(p); add_pin(p, n); p = q ? q + 1 : NULL; }
}

static int key_pinned(const mbedtls_x509_crt *crt) {
    unsigned char der[2048], h[32];
    int n = mbedtls_pk_write_pubkey_der((mbedtls_pk_context *)&crt->pk, der, sizeof der);   // written at the buffer's end
    if (n <= 0 || mbedtls_sha256(der + sizeof der - n, (size_t)n, h, 0)) return 0;
    for (unsigned i = 0; i < g_npins; i++) if (!memcmp(h, g_pins[i], 32)) return 1;
    return 0;
}
// Mbed TLS asks this for each certificate of the chain, the server's last (depth 0), with what its checks found. Only a lone self-signed server
// certificate whose key is pinned is let through; a chain from the CA keeps every finding (an expired or misnamed certificate still fails).
static int verify_redirected(void *p, mbedtls_x509_crt *crt, int depth, uint32_t *flags) {
    pn_trust_t *t = p;
    if (depth == 0 && *flags && crt->next == NULL && key_pinned(crt)) { *flags = 0; t->pinned = 1; }
    return 0;
}

void pn_trust_conf(mbedtls_ssl_config *conf, pn_trust_t *t) {
    pthread_once(&g_once, init);
    t->pinned = 0;
    mbedtls_ssl_conf_authmode(conf, MBEDTLS_SSL_VERIFY_REQUIRED);
    if (t->redirected) {
        mbedtls_ssl_conf_ca_chain(conf, &g_nextendo, NULL);
        mbedtls_ssl_conf_verify(conf, verify_redirected, t);
    } else mbedtls_ssl_conf_ca_chain(conf, &g_system, NULL);
}
const char *pn_trust_how(const pn_trust_t *t) { return t->pinned ? "its pinned key" : t->redirected ? "Nextendo Network's CA" : "the system's CAs"; }
int pn_trust_system_count(void) { pthread_once(&g_once, init); return g_nsystem; }
void pn_trust_set_system_dirs(const char *const *dirs) { g_dirs = dirs; }
