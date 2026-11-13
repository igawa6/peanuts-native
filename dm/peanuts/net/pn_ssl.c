// PEANUTS: nn::ssl for Nextendo Network (PN_ONLINE=1), on Mbed TLS (dm/peanuts/third_party/mbedtls). Replaces hle_net.c's offline answers.
//
// The game's Context and Connection objects are only keys here: each is found by its address, and nothing of the SDK's own layout is read or written. That also makes
// the C interface the same code: the SDK's nnssl* functions are wrappers that pass their arguments unchanged to the C++ methods (nnsslConnectionRead ->
// Connection::Read(char *, int *, unsigned), nnsslConnectionDoHandshakeWithCertBuffer -> Connection::DoHandshake(unsigned *, unsigned *), and so on: read from Mario Kart 8
// Deluxe's SDK, its wrappers branch through the PLT with x0 = the object).
//
// SetSocketDescriptor duplicates the game's socket; the connection works on the duplicate. As on Horizon, the game's own descriptor is closed then unless DoNotCloseSocket
// is set, so a later close of it cannot hit another socket that took its number. Result codes are the ssl service's (module 123: no socket 103, invalid socket 106, would
// block 204, timeout 205; as in yuzu / Eden / Lemon). The server's certificate is not verified: Nextendo Network's servers do not present Nintendo's chain (Lemon and
// Ryujinx-Nextendo do the same). Each handshake logs the certificate's subject. Without a host name, or with an address for one, the server name sent is the host the
// DNS redirect stood in for (pn_redirected_host). Decrypted data waiting in a connection counts as readable in socket Poll (pn_ssl_pending; Lemon's fix for Animal
// Crossing's hosting, error 2219-1028).
#include "pn_net.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/error.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/ssl.h>
#include <mbedtls/x509_crt.h>
#include <psa/crypto.h>

#define SSL_RES(d) ((Result)(123u | ((d) << 9)))
#define R_NO_SOCKET SSL_RES(103)
#define R_INVALID_SOCKET SSL_RES(106)
#define R_WOULD_BLOCK SSL_RES(204)
#define R_INTERNAL SSL_RES(999)
enum { IO_BLOCKING = 1, IO_NONBLOCKING = 2 };
enum { OPT_DO_NOT_CLOSE_SOCKET = 0, OPT_GET_SERVER_CERT_CHAIN = 1, OPT_SKIP_DEFAULT_VERIFY = 2, OPT_ENABLE_ALPN = 3 };
enum { EV_READ = 1, EV_WRITE = 2, EV_EXCEPT = 4 };
static int trace_on(void) { static _Atomic int v = -1; int x = atomic_load(&v); if (x < 0) { const char *e = getenv("PN_NET_TRACE"); x = e && e[0] == '1'; atomic_store(&v, x); } return x; }
static __thread void *t_ra;   // the game's call site (set by every entry below)
static const char *where(void) {
    static __thread char b[48]; module_t *m = t_ra ? module_of(t_ra) : NULL;
    if (m) snprintf(b, sizeof b, "%s+0x%llx", m->name, (unsigned long long)((uint8_t *)t_ra - m->base)); else snprintf(b, sizeof b, "%p", t_ra);
    return b;
}
#define TRACE(fmt, ...) do { if (trace_on()) LOG("online: trace [%d] ssl " fmt ", from %s", gettid(), __VA_ARGS__, where()); } while (0)

// ---------- random numbers (also the id_token's key, pn_account.c) ----------
static mbedtls_entropy_context g_ent; static mbedtls_ctr_drbg_context g_drbg; static int g_rng_ok;
static pthread_once_t g_rng_once = PTHREAD_ONCE_INIT;
static void rng_init(void) {
    mbedtls_entropy_init(&g_ent); mbedtls_ctr_drbg_init(&g_drbg);
    g_rng_ok = !mbedtls_ctr_drbg_seed(&g_drbg, mbedtls_entropy_func, &g_ent, (const unsigned char *)"peanuts-nextendo", 16);
    psa_status_t ps = psa_crypto_init();
    if (!g_rng_ok || ps != PSA_SUCCESS) LOG("[RED] online: ssl: random number generator %s, PSA %d", g_rng_ok ? "ok" : "failed", (int)ps);
}
int pn_random(void *unused, unsigned char *out, size_t n) {
    (void)unused; pthread_once(&g_rng_once, rng_init);
    return g_rng_ok ? mbedtls_ctr_drbg_random(&g_drbg, out, n) : MBEDTLS_ERR_CTR_DRBG_ENTROPY_SOURCE_FAILED;
}

// ---------- the objects ----------
typedef struct { const void *key; uint64_t id; } ctx_t;
typedef struct {
    const void *key; uint64_t id, ctx_id;
    int gfd, hfd;                                    // the game's socket, and the duplicate the connection works on
    char host[256]; int do_not_close, handshaken, setup;
    Result last;
    mbedtls_ssl_context ssl; mbedtls_ssl_config conf;
    unsigned char peek[16384]; size_t npeek, opeek;  // Peek's data, handed out by the next Read
    uint64_t rx, tx;
} conn_t;
enum { MAXOBJ = 64 };
static ctx_t *g_ctx[MAXOBJ]; static conn_t *g_conn[MAXOBJ];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static _Atomic uint64_t g_next_id = 1;
static ctx_t *ctx_of(const void *self) {
    pthread_mutex_lock(&g_mu); ctx_t *r = NULL;
    for (int i = 0; i < MAXOBJ && !r; i++) if (g_ctx[i] && g_ctx[i]->key == self) r = g_ctx[i];
    pthread_mutex_unlock(&g_mu); return r;
}
static conn_t *conn_of(const void *self) {
    pthread_mutex_lock(&g_mu); conn_t *r = NULL;
    for (int i = 0; i < MAXOBJ && !r; i++) if (g_conn[i] && g_conn[i]->key == self) r = g_conn[i];
    pthread_mutex_unlock(&g_mu); return r;
}
int pn_ssl_pending(int gfd) {
    int n = 0;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAXOBJ; i++) { conn_t *c = g_conn[i]; if (c && c->gfd == gfd && c->handshaken) n += (int)(c->npeek - c->opeek) + (int)mbedtls_ssl_get_bytes_avail(&c->ssl); }
    pthread_mutex_unlock(&g_mu); return n;
}

// ---------- the TLS session ----------
static int nonblocking(const conn_t *c) { int f = fcntl(c->hfd, F_GETFL); return f >= 0 && (f & O_NONBLOCK); }
static int bio_send(void *p, const unsigned char *b, size_t n) {
    conn_t *c = p; long r = send(c->hfd, b, n, MSG_NOSIGNAL);
    if (r >= 0) return (int)r;
    return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR ? MBEDTLS_ERR_SSL_WANT_WRITE : MBEDTLS_ERR_NET_SEND_FAILED;
}
static int bio_recv(void *p, unsigned char *b, size_t n) {
    conn_t *c = p; long r = recv(c->hfd, b, n, 0);
    if (r >= 0) return (int)r;   // 0: the peer closed
    return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR ? MBEDTLS_ERR_SSL_WANT_READ : MBEDTLS_ERR_NET_RECV_FAILED;
}
static void tls_err(const conn_t *c, const char *what, int r) { char b[160]; mbedtls_strerror(r, b, sizeof b); LOG("online: ssl %llu (%s): %s: %s (-%#x)", (unsigned long long)c->id, c->host, what, b, (unsigned)-r); }
static const char *server_name(conn_t *c, char *buf, size_t n) {
    struct in_addr a;
    if (c->host[0] && !inet_pton(AF_INET, c->host, &a)) return c->host;
    struct sockaddr_in s; socklen_t l = sizeof s;
    if (!getpeername(c->hfd, (struct sockaddr *)&s, &l)) { const char *r = pn_redirected_host(s.sin_addr.s_addr); if (r) { snprintf(buf, n, "%s", r); return buf; } }
    return c->host[0] ? c->host : NULL;
}
static Result tls_setup(conn_t *c) {
    pthread_once(&g_rng_once, rng_init);
    mbedtls_ssl_config_init(&c->conf); mbedtls_ssl_init(&c->ssl); c->setup = 1;
    int r = mbedtls_ssl_config_defaults(&c->conf, MBEDTLS_SSL_IS_CLIENT, MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
    if (r) { tls_err(c, "config", r); return R_INTERNAL; }
    mbedtls_ssl_conf_authmode(&c->conf, MBEDTLS_SSL_VERIFY_NONE);
    mbedtls_ssl_conf_rng(&c->conf, pn_random, NULL);
    if ((r = mbedtls_ssl_setup(&c->ssl, &c->conf))) { tls_err(c, "setup", r); return R_INTERNAL; }
    char b[256]; const char *sni = server_name(c, b, sizeof b);
    if (sni && (r = mbedtls_ssl_set_hostname(&c->ssl, sni))) { tls_err(c, "server name", r); return R_INTERNAL; }
    if (sni && sni != c->host) snprintf(c->host, sizeof c->host, "%s", sni);
    mbedtls_ssl_set_bio(&c->ssl, c, bio_send, bio_recv, NULL);
    return RES_OK;
}
static Result handshake(conn_t *c) {
    if (c->hfd < 0) return c->last = R_NO_SOCKET;
    if (c->handshaken) return RES_OK;
    if (!c->setup && tls_setup(c)) return c->last = R_INTERNAL;
    for (;;) {
        int r = mbedtls_ssl_handshake(&c->ssl);
        if (!r) break;
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) { if (nonblocking(c)) return c->last = R_WOULD_BLOCK; continue; }
        tls_err(c, "handshake", r); return c->last = R_INTERNAL;
    }
    c->handshaken = 1;
    char subj[256] = "(none)"; const mbedtls_x509_crt *crt = mbedtls_ssl_get_peer_cert(&c->ssl);
    if (crt) mbedtls_x509_dn_gets(subj, sizeof subj, &crt->subject);
    LOG("online: ssl %llu to %s: %s, %s; server certificate %s (not verified)", (unsigned long long)c->id, c->host[0] ? c->host : "(no name)",
        mbedtls_ssl_get_version(&c->ssl), mbedtls_ssl_get_ciphersuite(&c->ssl), subj);
    return c->last = RES_OK;
}
static void first_line(const char *what, conn_t *c, const char *b, size_t n, int request) {   // an HTTP request's method and path (no query: it can carry tokens), or the answer's status line
    if (n < 5) return;
    size_t k = 0; while (k < n && k < 120 && b[k] != '\r' && b[k] != '\n' && !(request && b[k] == '?')) k++;
    if (request ? (!memcmp(b, "GET ", 4) || !memcmp(b, "POST ", 5) || !memcmp(b, "PUT ", 4) || !memcmp(b, "DELETE ", 7)) : !memcmp(b, "HTTP/", 5))
        LOG("online: ssl %llu %s %s: %.*s", (unsigned long long)c->id, what, c->host, (int)k, b);
}
static Result tls_read(conn_t *c, char *b, int *out, unsigned n, int peek) {
    if (out) *out = 0;
    if (!c->handshaken) { Result r = handshake(c); if (r) return r; }
    if (c->npeek > c->opeek) {
        size_t k = c->npeek - c->opeek; if (k > n) k = n;
        memcpy(b, c->peek + c->opeek, k); if (!peek) c->opeek += k;
        if (c->opeek == c->npeek) c->npeek = c->opeek = 0;
        if (out) *out = (int)k; return c->last = RES_OK;
    }
    for (;;) {
        int r = mbedtls_ssl_read(&c->ssl, peek ? c->peek : (unsigned char *)b, peek ? sizeof c->peek : n);
        if (r > 0) {
            if (!c->rx) first_line("answer from", c, peek ? (char *)c->peek : b, (size_t)r, 0);
            c->rx += (uint64_t)r;
            if (peek) { c->npeek = (size_t)r; c->opeek = 0; if ((unsigned)r > n) r = (int)n; memcpy(b, c->peek, (size_t)r); }
            if (out) *out = r; return c->last = RES_OK;
        }
        if (r == 0 || r == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || r == MBEDTLS_ERR_SSL_CONN_EOF) return c->last = RES_OK;   // closed: 0 bytes
        if (r == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) continue;
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) { if (nonblocking(c)) return c->last = R_WOULD_BLOCK; continue; }
        tls_err(c, "read", r); return c->last = R_INTERNAL;
    }
}
static Result tls_write(conn_t *c, const char *b, int *out, unsigned n) {
    if (out) *out = 0;
    if (!c->handshaken) { Result r = handshake(c); if (r) return r; }
    for (;;) {
        int r = mbedtls_ssl_write(&c->ssl, (const unsigned char *)b, n);
        if (r >= 0) { if (!c->tx) first_line("request to", c, b, (size_t)r, 1); c->tx += (uint64_t)r; if (out) *out = r; return c->last = RES_OK; }
        if (r == MBEDTLS_ERR_SSL_WANT_READ || r == MBEDTLS_ERR_SSL_WANT_WRITE) { if (nonblocking(c)) return c->last = R_WOULD_BLOCK; continue; }
        tls_err(c, "write", r); return c->last = R_INTERNAL;
    }
}
static int pending(conn_t *c) { return c->handshaken ? (int)(c->npeek - c->opeek) + (int)mbedtls_ssl_get_bytes_avail(&c->ssl) : 0; }

// ---------- Context ----------
static Result ctx_create(const void *self, int version) {
    if (ctx_of(self)) return RES_OK;
    ctx_t *x = calloc(1, sizeof *x); if (!x) return R_INTERNAL;
    x->key = self; x->id = atomic_fetch_add(&g_next_id, 1);
    pthread_mutex_lock(&g_mu); int i = 0; while (i < MAXOBJ && g_ctx[i]) i++; if (i < MAXOBJ) g_ctx[i] = x; pthread_mutex_unlock(&g_mu);
    if (i == MAXOBJ) { free(x); LOG("[RED] online: ssl: more than %d contexts", MAXOBJ); return R_INTERNAL; }
    LOG("online: ssl context %llu (version %#x)", (unsigned long long)x->id, version);
    return RES_OK;
}
static Result ctx_destroy(const void *self) {
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAXOBJ; i++) if (g_ctx[i] && g_ctx[i]->key == self) { free(g_ctx[i]); g_ctx[i] = NULL; }
    pthread_mutex_unlock(&g_mu); return RES_OK;
}
static Result ctx_create(const void *self, int version);
static ctx_t *ctx_adopt(const void *self) {   // a context the game uses without a Create seen here: taken as one, as the service would have it
    ctx_t *x = ctx_of(self);
    if (!x && self) { LOG("online: ssl: a context never created here is in use: adopted"); ctx_create(self, -1); x = ctx_of(self); }
    return x;
}
static Result ctx_id(const void *self, uint64_t *out) { ctx_t *x = ctx_adopt(self); if (out) *out = x ? x->id : 0; TRACE("Context::GetContextId(%p) = %llu", self, (unsigned long long)(x ? x->id : 0)); return x ? RES_OK : R_INTERNAL; }
static Result ctx_pki(const void *self, uint64_t *id, const char *what, unsigned n) {   // certificates the game brings: kept by nobody, as nothing is verified
    if (id) *id = atomic_fetch_add(&g_next_id, 1);
    LOG("online: ssl context %llu: %s (%u bytes) taken, not used", (unsigned long long)(ctx_of(self) ? ctx_of(self)->id : 0), what, n);
    return RES_OK;
}

// ---------- Connection ----------
static Result cn_create(const void *self, const void *ctx) {
    conn_t *c = calloc(1, sizeof *c); if (!c) return R_INTERNAL;
    c->key = self; c->id = atomic_fetch_add(&g_next_id, 1); c->gfd = c->hfd = -1;
    ctx_t *x = ctx_adopt(ctx); c->ctx_id = x ? x->id : 0;
    TRACE("Connection::Create(%p, context %p = %llu)", self, ctx, (unsigned long long)c->ctx_id);
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAXOBJ; i++) if (g_conn[i] && g_conn[i]->key == self) { LOG("[RED] online: ssl connection object created twice without Destroy"); }
    int i = 0; while (i < MAXOBJ && g_conn[i]) i++; if (i < MAXOBJ) g_conn[i] = c;
    pthread_mutex_unlock(&g_mu);
    if (i == MAXOBJ) { free(c); LOG("[RED] online: ssl: more than %d connections", MAXOBJ); return R_INTERNAL; }
    return RES_OK;
}
static Result cn_destroy(const void *self) {
    conn_t *c = NULL;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < MAXOBJ; i++) if (g_conn[i] && g_conn[i]->key == self) { c = g_conn[i]; g_conn[i] = NULL; break; }
    pthread_mutex_unlock(&g_mu);
    if (!c) return R_INTERNAL;
    if (c->handshaken) mbedtls_ssl_close_notify(&c->ssl);
    if (c->setup) { mbedtls_ssl_free(&c->ssl); mbedtls_ssl_config_free(&c->conf); }
    if (c->hfd >= 0) close(c->hfd);
    LOG("online: ssl %llu (%s) closed: sent %llu, received %llu bytes", (unsigned long long)c->id, c->host, (unsigned long long)c->tx, (unsigned long long)c->rx);
    free(c);
    return RES_OK;
}
#define CONN(self, c) conn_t *c = conn_of(self); if (!c) { TRACE("%s on a connection never created here (%p)", __func__, self); return R_NO_SOCKET; }
static Result cn_socket(const void *self, int gfd) {
    CONN(self, c);
    int h = pn_sock_host(gfd);
    if (h < 0 || (h = fcntl(h, F_DUPFD_CLOEXEC, 0)) < 0) { LOG("online: ssl %llu: socket %d is not one of ours", (unsigned long long)c->id, gfd); return c->last = R_INVALID_SOCKET; }
    if (c->hfd >= 0) close(c->hfd);
    c->hfd = h; c->gfd = gfd;
    if (!c->do_not_close) { pn_sock_close(gfd); c->gfd = -1; }   // Horizon's ssl owns the socket now
    TRACE("SetSocketDescriptor(%llu, %d)", (unsigned long long)c->id, gfd);
    return c->last = RES_OK;
}
static Result cn_host(const void *self, const char *h, unsigned n) {
    CONN(self, c); size_t k = 0;
    while (h && k < n && k < sizeof c->host - 1 && h[k]) { c->host[k] = h[k]; k++; }
    c->host[k] = 0; TRACE("SetHostName(%llu, \"%s\")", (unsigned long long)c->id, c->host); return RES_OK;
}
static Result cn_iomode(const void *self, int mode) {
    CONN(self, c);
    if (c->hfd < 0) return c->last = R_NO_SOCKET;
    int f = fcntl(c->hfd, F_GETFL);
    if (f >= 0) fcntl(c->hfd, F_SETFL, mode == IO_NONBLOCKING ? f | O_NONBLOCK : f & ~O_NONBLOCK);
    TRACE("SetIoMode(%llu, %d)", (unsigned long long)c->id, mode);
    return RES_OK;
}
static Result cn_option(const void *self, int opt, int on) {
    CONN(self, c);
    TRACE("SetOption(%llu, %d, %d)", (unsigned long long)c->id, opt, on);
    if (opt == OPT_DO_NOT_CLOSE_SOCKET) c->do_not_close = on;
    else LOG("online: ssl %llu: option %d %s (taken, not used)", (unsigned long long)c->id, opt, on ? "on" : "off");
    return RES_OK;
}
static Result cn_handshake(const void *self, uint32_t *size, uint32_t *count) {
    CONN(self, c);
    if (size) *size = 0;   // no server certificate is handed out (the game set no buffer for it here)
    if (count) *count = 0;
    Result r = handshake(c);
    TRACE("DoHandshake(%llu) = %#x", (unsigned long long)c->id, r);
    if (!r && count && mbedtls_ssl_get_peer_cert(&c->ssl)) *count = 1;
    return r;
}
static Result cn_poll(const void *self, uint32_t *out, const uint32_t *in, uint32_t ms) {
    CONN(self, c);
    uint32_t want = in ? *in : EV_READ, got = 0;
    if (out) *out = 0;
    if (c->hfd < 0) return c->last = R_NO_SOCKET;
    if (want & EV_READ && pending(c)) got |= EV_READ;
    struct pollfd p = {c->hfd, (short)((want & EV_READ ? POLLIN : 0) | (want & EV_WRITE ? POLLOUT : 0)), 0};
    if (poll(&p, 1, got ? 0 : (int)ms) > 0) {
        if (want & EV_READ && p.revents & (POLLIN | POLLHUP)) got |= EV_READ;
        if (want & EV_WRITE && p.revents & POLLOUT) got |= EV_WRITE;
        if (p.revents & (POLLERR | POLLNVAL)) got |= EV_EXCEPT;
    }
    if (out) *out = got;
    return RES_OK;
}
static Result cn_id(const void *self, uint64_t *out, int which) { CONN(self, c); if (out) *out = which ? c->ctx_id : c->id; return RES_OK; }
static Result cn_pending(const void *self, int *out) { CONN(self, c); if (out) *out = pending(c); return RES_OK; }
static Result cn_last(const void *self, Result *out) { CONN(self, c); if (out) *out = c->last; return RES_OK; }
static Result cn_verify_err(const void *self, Result *out) { CONN(self, c); if (out) *out = RES_OK; return RES_OK; }   // nothing is verified, so nothing failed
static Result cn_read(const void *self, char *b, int *out, unsigned n) { CONN(self, c); Result r = tls_read(c, b, out, n, 0); TRACE("Read(%llu, %u) = %#x, %d bytes", (unsigned long long)c->id, n, r, out ? *out : -1); return r; }
static Result cn_peek(const void *self, char *b, int *out, unsigned n) { CONN(self, c); return tls_read(c, b, out, n, 1); }
static Result cn_write(const void *self, const char *b, int *out, unsigned n) { CONN(self, c); Result r = tls_write(c, b, out, n); TRACE("Write(%llu, %u) = %#x, %d bytes", (unsigned long long)c->id, n, r, out ? *out : -1); return r; }

// ================= the table: C++ methods, and the C interface with the same arguments =================
#define WHY "online (dm/peanuts/net): nn::ssl on Mbed TLS"
#define BOTH(cpp, c, ret, cname, ...)                                                                \
    extern const char cname##_bs[];                                                                 \
    PLAT_TABLE(pnforce) static const hle_force_ent pn_force_##cname##_c = {c, (void *)(uintptr_t)cname##_bs, WHY}; \
    PN_FORCE_BIG(cpp, WHY, ret, cname, __VA_ARGS__)
// Context
PN_FORCE_BIG("_ZN2nn3ssl7Context6CreateENS1_10SslVersionE", WHY, Result, pn_ssl_CtxCreate, const void *s, int v) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_create(s, v); }
PN_FORCE_BIG("nnsslContextCreate", WHY, Result, pn_ssl_CtxCreate_c, const void *s, int v) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_create(s, v); }
PN_FORCE_BIG("_ZN2nn3ssl7Context7DestroyEv", WHY, Result, pn_ssl_CtxDestroy, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_destroy(s); }
PN_FORCE_BIG("nnsslContextDestroy", WHY, Result, pn_ssl_CtxDestroy_c, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_destroy(s); }
PN_FORCE_BIG("_ZN2nn3ssl7Context12GetContextIdEPm", WHY, Result, pn_ssl_CtxId, const void *s, uint64_t *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_id(s, o); }
PN_FORCE_BIG("nnsslContextGetContextId", WHY, Result, pn_ssl_CtxId_c, const void *s, uint64_t *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return ctx_id(s, o); }
PN_FORCE_BIG("_ZN2nn3ssl7Context15ImportServerPkiEPmPKcjNS0_17CertificateFormatE", WHY, Result, pn_ssl_CtxServerPki, const void *s, uint64_t *id, const char *b, unsigned n, int f) { (void)b; (void)f; return ctx_pki(s, id, "server certificate", n); }
PN_FORCE_BIG("_ZN2nn3ssl7Context15ImportClientPkiEPmPKcS4_jj", WHY, Result, pn_ssl_CtxClientPki, const void *s, uint64_t *id, const char *c, const char *k, unsigned n, unsigned kn) { (void)c; (void)k; (void)kn; return ctx_pki(s, id, "client certificate", n); }
PN_FORCE_BIG("_ZN2nn3ssl7Context19RegisterInternalPkiEPmNS1_11InternalPkiE", WHY, Result, pn_ssl_CtxInternalPki, const void *s, uint64_t *id, int p) { (void)p; return ctx_pki(s, id, "the system's client certificate", 0); }
PN_FORCE_BIG("_ZN2nn3ssl7Context9RemovePkiEm", WHY, Result, pn_ssl_CtxRemovePki, const void *s, uint64_t id) { (void)s; (void)id; return RES_OK; }
// Connection
PN_FORCE_BIG("_ZN2nn3ssl10Connection6CreateEPNS0_7ContextE", WHY, Result, pn_ssl_Create, const void *s, const void *x) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_create(s, x); }
PN_FORCE_BIG("nnsslConnectionCreate", WHY, Result, pn_ssl_Create_c, const void *s, const void *x) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_create(s, x); }
PN_FORCE_BIG("_ZN2nn3ssl10Connection7DestroyEv", WHY, Result, pn_ssl_Destroy, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_destroy(s); }
PN_FORCE_BIG("nnsslConnectionDestroy", WHY, Result, pn_ssl_Destroy_c, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_destroy(s); }
BOTH("_ZN2nn3ssl10Connection19SetSocketDescriptorEi", "nnsslConnectionSetSocketDescriptor", Result, pn_ssl_Socket, const void *s, int fd) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_socket(s, fd); }
BOTH("_ZN2nn3ssl10Connection11SetHostNameEPKcj", "nnsslConnectionSetHostName", Result, pn_ssl_Host, const void *s, const char *h, unsigned n) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_host(s, h, n); }
BOTH("_ZN2nn3ssl10Connection15SetVerifyOptionENS1_12VerifyOptionE", "nnsslConnectionSetVerifyOption", Result, pn_ssl_Verify, const void *s, int v) { CONN(s, c); (void)c; (void)v; return RES_OK; }
BOTH("_ZN2nn3ssl10Connection9SetIoModeENS1_6IoModeE", "nnsslConnectionSetIoMode", Result, pn_ssl_IoMode, const void *s, int m) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_iomode(s, m); }
BOTH("_ZN2nn3ssl10Connection19SetSessionCacheModeENS1_16SessionCacheModeE", "nnsslConnectionSetSessionCacheMode", Result, pn_ssl_Cache, const void *s, int m) { CONN(s, c); (void)c; (void)m; return RES_OK; }
BOTH("_ZN2nn3ssl10Connection9SetOptionENS1_10OptionTypeEb", "nnsslConnectionSetOption", Result, pn_ssl_Option, const void *s, int o, unsigned on) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_option(s, o, on & 1); }
PN_FORCE_BIG("_ZN2nn3ssl10Connection11DoHandshakeEv", WHY, Result, pn_ssl_Handshake0, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_handshake(s, NULL, NULL); }
PN_FORCE_BIG("nnsslConnectionDoHandshake", WHY, Result, pn_ssl_Handshake0_c, const void *s) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_handshake(s, NULL, NULL); }
BOTH("_ZN2nn3ssl10Connection11DoHandshakeEPjS2_", "nnsslConnectionDoHandshakeWithCertBuffer", Result, pn_ssl_Handshake2, const void *s, uint32_t *sz, uint32_t *n) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_handshake(s, sz, n); }
PN_FORCE_BIG("_ZN2nn3ssl10Connection11DoHandshakeEPjS2_Pcj", WHY, Result, pn_ssl_Handshake4, const void *s, uint32_t *sz, uint32_t *n, char *b, unsigned bn) { (void)b; (void)bn; return cn_handshake(s, sz, n); }
BOTH("_ZN2nn3ssl10Connection4ReadEPcPij", "nnsslConnectionRead", Result, pn_ssl_Read, const void *s, char *b, int *o, unsigned n) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_read(s, b, o, n); }
BOTH("_ZN2nn3ssl10Connection5WriteEPKcPij", "nnsslConnectionWrite", Result, pn_ssl_Write, const void *s, const char *b, int *o, unsigned n) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_write(s, b, o, n); }
BOTH("_ZN2nn3ssl10Connection4PeekEPcPij", "nnsslConnectionPeek", Result, pn_ssl_Peek, const void *s, char *b, int *o, unsigned n) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_peek(s, b, o, n); }
BOTH("_ZN2nn3ssl10Connection7PendingEPi", "nnsslConnectionPending", Result, pn_ssl_Pending, const void *s, int *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_pending(s, o); }
BOTH("_ZN2nn3ssl10Connection4PollEPNS1_9PollEventES3_j", "nnsslConnectionPoll", Result, pn_ssl_Poll, const void *s, uint32_t *o, const uint32_t *i, unsigned ms) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_poll(s, o, i, ms); }
BOTH("_ZN2nn3ssl10Connection18GetVerifyCertErrorEPNS_6ResultE", "nnsslConnectionGetVerifyCertError", Result, pn_ssl_VerifyErr, const void *s, Result *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_verify_err(s, o); }
BOTH("_ZN2nn3ssl10Connection15GetConnectionIdEPm", "nnsslConnectionGetConnectionId", Result, pn_ssl_ConnId, const void *s, uint64_t *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_id(s, o, 0); }
BOTH("_ZN2nn3ssl10Connection12GetContextIdEPm", "nnsslConnectionGetContextId", Result, pn_ssl_ConnCtxId, const void *s, uint64_t *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_id(s, o, 1); }
PN_FORCE_BIG("_ZN2nn3ssl10Connection12GetLastErrorEPNS_6ResultE", WHY, Result, pn_ssl_LastErr, const void *s, Result *o) { t_ra = dm_bigstack_caller(__builtin_return_address(0)); return cn_last(s, o); }
// The older SDK's forms: the count (or -1) is the return value
PN_FORCE_BIG("_ZN2nn3ssl10Connection4ReadEPcj", WHY, int, pn_ssl_Read1, const void *s, char *b, unsigned n) { int o = 0; return cn_read(s, b, &o, n) ? -1 : o; }
PN_FORCE_BIG("_ZN2nn3ssl10Connection5WriteEPKcj", WHY, int, pn_ssl_Write1, const void *s, const char *b, unsigned n) { int o = 0; return cn_write(s, b, &o, n) ? -1 : o; }
PN_FORCE_BIG("_ZN2nn3ssl10Connection7PendingEv", WHY, int, pn_ssl_Pending1, const void *s) { int o = 0; return cn_pending(s, &o) ? -1 : o; }
