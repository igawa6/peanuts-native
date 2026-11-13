// PEANUTS: online networking for Nextendo Network, on when PN_ONLINE=1 (pn_net.h, README.md in this folder). Served here: nifm (the network is up, the phone's address),
// BSD sockets on Android's, the resolver with Nextendo Network's redirect, and nsd's server names. ssl, the online account and friends are still upstream's offline answers.
//
// The guest's side of the socket calls is the SDK's client API, which the host replaces (Mario Kart 8 Deluxe's SDK 4.4 on musl, traced with PN_NET_TRACE): its option levels
// and names are FreeBSD's (SOL_SOCKET 0xffff, SO_REUSEADDR 4, SO_NOSIGPIPE 0x800, ...) and so is sockaddr (a length byte before an 8-bit family), but its error numbers
// (EINPROGRESS 115), fcntl's O_NONBLOCK (0x800), poll events (POLLWRNORM 0x100) and MSG_* flags are Linux's, as musl numbers them. Two of those, taken as FreeBSD's
// numbers, each made Mario Kart close its connection to Nextendo Network at once (error 2306-0502): EINPROGRESS reported as 36, and a Poll for POLLOUT | POLLWRNORM
// (0x104) answered with POLLOUT alone after the connect had completed. Each failure also sets the C library's errno (the SDK's musl, __errno_location).
// (yuzu / Eden / Lemon emulate the bsd service behind that API, whose numbering the SDK translates to; they are a reference for the service, not for this side of it.)
// addrinfo and hostent have the same field order in both (Android's C library took its netdb.h from NetBSD), as do pollfd, fd_set, timeval and linger.
// Guest descriptors are this file's own small numbers (3..511) mapped to Android's: below upstream's eventfds (512..575, hle_net.c) and below select's 1024 bits.
//
// Host DNS redirect (the rules of citron-nextendo and Lemon, GPL-3.0-or-later): Nintendo's online hosts resolve to Nextendo Network's server (PN_NEXTENDO_SERVER), the NAT
// check's second host to its second responder (PN_NEXTENDO_NAT). A redirected address remembers the name it stood in for, for ssl's server name (pn_redirected_host).
#include "pn_net.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

int dm_sk_poll(void *fds, uint64_t n, int ms);                                                          // hle_net.c: eventfds, and nothing to wait on
int dm_sk_select(int nfds, const uint64_t *r, const uint64_t *w, const uint64_t *e, const int64_t *tv);

// ---------- the switch and the table ----------
int pn_online(void) {
    static _Atomic int v = -1;
    int x = atomic_load(&v);
    if (x < 0) {
        const char *e = getenv("PN_ONLINE"); x = e && e[0] == '1';
        if (!atomic_exchange(&v, x) && x) LOG("online: PN_ONLINE=1: the network hooks of dm/peanuts/net replace upstream's offline ones");
    }
    return x;
}

#if defined(__ANDROID__)
extern const hle_force_ent __start_pnforce[] __attribute__((weak, visibility("hidden"))), __stop_pnforce[] __attribute__((weak, visibility("hidden")));
static const hle_force_ent *pn_table(unsigned *n) { *n = __start_pnforce ? (unsigned)(__stop_pnforce - __start_pnforce) : 0; return __start_pnforce; }
#else
static const hle_force_ent *pn_table(unsigned *n) { *n = 0; return NULL; }
#endif
static uint32_t pn_hash(const char *s) { uint32_t h = 2166136261u; while (*s) h = (h ^ (uint8_t)*s++) * 16777619u; return h; }
static const hle_force_ent *g_tab; static unsigned g_ntab, g_mask, *g_idx; static _Atomic uint8_t *g_hit;
static pthread_once_t g_tab_once = PTHREAD_ONCE_INIT;
static void pn_table_init(void) {
    g_tab = pn_table(&g_ntab);
    unsigned cap = 16; while (cap < g_ntab * 2) cap <<= 1;
    g_idx = calloc(cap, sizeof *g_idx); g_hit = calloc(g_ntab + 1, 1); g_mask = cap - 1;
    for (unsigned i = 0; i < g_ntab; i++)
        for (unsigned j = pn_hash(g_tab[i].name) & g_mask;; j = (j + 1) & g_mask) {
            if (!g_idx[j]) { g_idx[j] = i + 1; break; }
            if (!strcmp(g_tab[g_idx[j] - 1].name, g_tab[i].name)) { LOG("[RED] online: %s is in the pnforce table twice", g_tab[i].name); abort(); }
        }
    LOG("online: %u network hooks", g_ntab);
}
void *pn_force_lookup(const char *name) {
    if (!pn_online()) return NULL;
    pthread_once(&g_tab_once, pn_table_init);
    if (!g_ntab) return NULL;
    for (unsigned j = pn_hash(name) & g_mask; g_idx[j]; j = (j + 1) & g_mask) {
        unsigned i = g_idx[j] - 1;
        if (strcmp(g_tab[i].name, name)) continue;
        if (!atomic_exchange(&g_hit[i], 1)) LOG("online: force %s  (%s)", name, g_tab[i].why);
        return g_tab[i].fn;
    }
    return NULL;
}
void *pn_upstream(const char *name) {
    unsigned long sz; const hle_force_ent *t = (const hle_force_ent *)plat_table("swforce", &sz);
    for (unsigned long i = 0; t && i < sz / sizeof *t; i++) if (!strcmp(t[i].name, name)) return t[i].fn;
    return NULL;
}

// ---------- error numbers: Android's are the guest's (Linux numbering) ----------
static __thread int t_err;
static int trace_on(void) { static _Atomic int v = -1; int x = atomic_load(&v); if (x < 0) { const char *e = getenv("PN_NET_TRACE"); x = e && e[0] == '1'; atomic_store(&v, x); } return x; }
#define TRACE(...) do { if (trace_on()) LOG("online: trace [%d] " __VA_ARGS__); } while (0)
static __thread void *t_ra;   // the game's call site of the entry point being traced (set by the nnsocket* entries below)
static const char *where(void) {
    static __thread char b[48]; module_t *m = t_ra ? module_of(t_ra) : NULL;
    if (m) snprintf(b, sizeof b, "%s+0x%llx", m->name, (unsigned long long)((uint8_t *)t_ra - m->base)); else snprintf(b, sizeof b, "%p", t_ra);
    return b;
}
static void set_errno(int e, const char *where) {
    static int *(*loc)(void); static _Atomic int logged;
    t_err = e;
    if (!loc) loc = (int *(*)(void))guest_sym("__errno_location");
    if (loc) *loc() = e;
    if (e != EAGAIN && e != EINPROGRESS && atomic_fetch_add(&logged, 1) < 64) LOG("online: %s failed: %s (%d)", where, strerror(e), e);
}
#define fail() fail_at(__func__)
#define fail_with(e) fail_with_at(e, __func__)
static int fail_at(const char *where) { set_errno(errno, where); return -1; }
static int fail_with_at(int e, const char *where) { set_errno(e, where); return -1; }

// ---------- descriptors: the guest's numbers to Android's ----------
enum { FD_FIRST = 3, FD_MAX = 512 };
static _Atomic int g_fd[FD_MAX];                     // Android's descriptor + 1; 0 = free
static _Atomic uint64_t g_tx[FD_MAX], g_rx[FD_MAX];  // bytes sent and received, for the close line
static pthread_mutex_t g_fd_mu = PTHREAD_MUTEX_INITIALIZER;
static int fd_new(int h) {
    pthread_mutex_lock(&g_fd_mu);
    for (int g = FD_FIRST; g < FD_MAX; g++)
        if (!atomic_load(&g_fd[g])) { atomic_store(&g_fd[g], h + 1); atomic_store(&g_tx[g], 0); atomic_store(&g_rx[g], 0); pthread_mutex_unlock(&g_fd_mu); return g; }
    pthread_mutex_unlock(&g_fd_mu);
    close(h); LOG("[RED] online: more than %d sockets open", FD_MAX - FD_FIRST);
    return fail_with(EMFILE);
}
static int fd_host(int g) { return g >= FD_FIRST && g < FD_MAX ? atomic_load(&g_fd[g]) - 1 : -1; }
int pn_sock_host(int g) { return fd_host(g); }

// ---------- sockaddr ----------
typedef struct { uint8_t len, family; uint16_t port; uint32_t addr; uint8_t zero[8]; } g_sockaddr_in;   // FreeBSD's sockaddr_in, 16 bytes
enum { G_AF_INET = 2 };
static int sa_in(const void *g, unsigned glen, struct sockaddr_in *h) {
    if (!g || glen < 8) return fail_with(EINVAL);
    const g_sockaddr_in *s = g;
    if (s->family != G_AF_INET) return fail_with(EAFNOSUPPORT);
    memset(h, 0, sizeof *h); h->sin_family = AF_INET; h->sin_port = s->port; h->sin_addr.s_addr = s->addr;
    return 0;
}
static void sa_out(const struct sockaddr_storage *h, void *g, unsigned *glen) {
    if (!g || !glen) return;
    g_sockaddr_in s = {.len = sizeof s};
    if (h->ss_family == AF_INET) { const struct sockaddr_in *i = (const struct sockaddr_in *)h; s.family = G_AF_INET; s.port = i->sin_port; s.addr = i->sin_addr.s_addr; }
    memcpy(g, &s, *glen < sizeof s ? *glen : sizeof s); *glen = sizeof s;
}
static const char *ip_text(uint32_t a, char *b) { inet_ntop(AF_INET, &a, b, INET_ADDRSTRLEN); return b; }

// ---------- the DNS redirect ----------
static char g_server[64] = "51.178.29.194", g_nat[64] = "164.132.111.120";   // Nextendo Network's server and its second NAT-check responder (Lemon's defaults)
static pthread_once_t g_cfg_once = PTHREAD_ONCE_INIT;
static void cfg_init(void) {
    const char *s = getenv("PN_NEXTENDO_SERVER"), *n = getenv("PN_NEXTENDO_NAT");
    if (s && *s) snprintf(g_server, sizeof g_server, "%s", s);
    if (n && *n) snprintf(g_nat, sizeof g_nat, "%s", n);
    LOG("online: Nintendo's online hosts resolve to %s, the NAT check's second host to %s", g_server, g_nat);
}
static int host_is(const char *host, const char *dom) {   // host is dom or one of its subdomains
    size_t h = strlen(host), d = strlen(dom);
    return h == d ? !strcasecmp(host, dom) : h > d && host[h - d - 1] == '.' && !strcasecmp(host + h - d, dom);
}
static const char *redirect_of(const char *host) {
    if (!host || !*host) return NULL;
    pthread_once(&g_cfg_once, cfg_init);
    size_t n = strlen(host), sfx = strlen(".n.n.srv.nintendo.net");
    if (!strncasecmp(host, "nncs2-", 6) && n > sfx && !strcasecmp(host + n - sfx, ".n.n.srv.nintendo.net")) return g_nat;
    static const char *const doms[] = {"nintendo.net", "nintendo.com", "nintendowifi.net", "nintendo.co.jp", "demonware.net"};
    for (unsigned i = 0; i < sizeof doms / sizeof *doms; i++) if (host_is(host, doms[i])) return g_server;
    return NULL;
}
static struct { uint32_t addr; char host[128]; } g_red[32]; static unsigned g_nred; static pthread_mutex_t g_red_mu = PTHREAD_MUTEX_INITIALIZER;
static void remember(uint32_t addr, const char *host) {   // every redirected host shares one address, so the last name looked up wins (as in Lemon)
    pthread_mutex_lock(&g_red_mu);
    unsigned i = 0; while (i < g_nred && g_red[i].addr != addr) i++;
    if (i == g_nred && g_nred < sizeof g_red / sizeof *g_red) g_nred++;
    if (i < g_nred) { g_red[i].addr = addr; snprintf(g_red[i].host, sizeof g_red[i].host, "%s", host); }
    pthread_mutex_unlock(&g_red_mu);
}
const char *pn_redirected_host(uint32_t addr) {
    static __thread char out[128]; const char *r = NULL;
    pthread_mutex_lock(&g_red_mu);
    for (unsigned i = 0; i < g_nred; i++) if (g_red[i].addr == addr) { snprintf(out, sizeof out, "%s", g_red[i].host); r = out; break; }
    pthread_mutex_unlock(&g_red_mu);
    return r;
}

// ---------- socket calls ----------
static int sk_socket(int dom, int type, int proto) {
    if (dom != G_AF_INET) { LOG("online: socket of family %d refused (only IPv4)", dom); return fail_with(EAFNOSUPPORT); }
    int fl = SOCK_CLOEXEC | (type & (0x20000000 | 0x800) ? SOCK_NONBLOCK : 0);   // non-blocking: FreeBSD's SOCK_NONBLOCK bit, or Linux's (0x800, as O_NONBLOCK)
    int h = socket(AF_INET, (type & 0xff) | fl, proto);
    if (h < 0) return fail();
    int g = fd_new(h);
    static _Atomic int n; if (g >= 0 && atomic_fetch_add(&n, 1) < 64) LOG("online: socket %d (%s, protocol %d)", g, (type & 0xf) == SOCK_DGRAM ? "UDP" : (type & 0xf) == SOCK_STREAM ? "TCP" : "raw", proto);
    return g;
}
static int sk_close(int g, const char *name) {
    TRACE("Close(%d) via %s, from %s", gettid(), g, name, where());
    pthread_mutex_lock(&g_fd_mu);
    int h = fd_host(g); if (h >= 0) atomic_store(&g_fd[g], 0);
    pthread_mutex_unlock(&g_fd_mu);
    if (h >= 0) {
        LOG("online: close %d (sent %llu, received %llu bytes)", g, (unsigned long long)atomic_load(&g_tx[g]), (unsigned long long)atomic_load(&g_rx[g]));
        return close(h) ? fail() : 0;
    }
    int (*up)(int) = (int (*)(int))pn_upstream(name);   // upstream's eventfds
    return up ? up(g) : fail_with(EBADF);
}
int pn_sock_close(int g) { return sk_close(g, "nnsocketClose"); }
#define HOST_FD(g, h) int h = fd_host(g); if (h < 0) return fail_with(EBADF)
static int sk_bind(int g, const void *a, unsigned n) {
    HOST_FD(g, h); struct sockaddr_in s; if (sa_in(a, n, &s)) return -1;
    char b[INET_ADDRSTRLEN]; LOG("online: bind %d to %s:%u", g, ip_text(s.sin_addr.s_addr, b), ntohs(s.sin_port));
    return bind(h, (struct sockaddr *)&s, sizeof s) ? fail() : 0;
}
static int sk_connect_(int g, const void *a, unsigned n) {
    HOST_FD(g, h); struct sockaddr_in s; if (sa_in(a, n, &s)) return -1;
    char b[INET_ADDRSTRLEN]; const char *was = pn_redirected_host(s.sin_addr.s_addr);
    LOG("online: connect %d to %s:%u%s%s", g, ip_text(s.sin_addr.s_addr, b), ntohs(s.sin_port), was ? " for " : "", was ? was : "");
    if (!connect(h, (struct sockaddr *)&s, sizeof s)) return 0;
    if (errno != EINPROGRESS) LOG("online: connect %d: %s", g, strerror(errno));
    return fail();
}
static int sk_listen(int g, int backlog) { HOST_FD(g, h); return listen(h, backlog) ? fail() : 0; }
static int sk_accept(int g, void *a, unsigned *n) {
    HOST_FD(g, h); struct sockaddr_storage s; socklen_t l = sizeof s;
    int c = accept4(h, (struct sockaddr *)&s, &l, SOCK_CLOEXEC);
    if (c < 0) return fail();
    sa_out(&s, a, n);
    return fd_new(c);
}
static int sk_shutdown_(int g, int how) { HOST_FD(g, h); return shutdown(h, how) ? fail() : 0; }
static int sk_name(int g, void *a, unsigned *n, int peer) {
    HOST_FD(g, h); struct sockaddr_storage s; socklen_t l = sizeof s;
    if (peer ? getpeername(h, (struct sockaddr *)&s, &l) : getsockname(h, (struct sockaddr *)&s, &l)) return fail();
    sa_out(&s, a, n); return 0;
}

// Socket options: FreeBSD's levels and names to Linux's. 0 when the host has no such option
static int opt_host(int lvl, int opt, int *hl, int *ho) {
    switch (lvl) {
    case 0xffff: *hl = SOL_SOCKET;
        switch (opt) {
        case 0x0004: *ho = SO_REUSEADDR; return 1; case 0x0008: *ho = SO_KEEPALIVE; return 1; case 0x0020: *ho = SO_BROADCAST; return 1;
        case 0x0080: *ho = SO_LINGER; return 1;    case 0x0100: *ho = SO_OOBINLINE; return 1; case 0x0200: *ho = SO_REUSEPORT; return 1;
        case 0x1001: *ho = SO_SNDBUF; return 1;    case 0x1002: *ho = SO_RCVBUF; return 1;    case 0x1003: *ho = SO_SNDLOWAT; return 1;
        case 0x1004: *ho = SO_RCVLOWAT; return 1;  case 0x1005: *ho = SO_SNDTIMEO; return 1;  case 0x1006: *ho = SO_RCVTIMEO; return 1;
        case 0x1007: *ho = SO_ERROR; return 1;     case 0x1008: *ho = SO_TYPE; return 1;
        }
        return 0;
    case IPPROTO_IP: *hl = IPPROTO_IP;
        switch (opt) {
        case 2: *ho = IP_HDRINCL; return 1;         case 3: *ho = IP_TOS; return 1;              case 4: *ho = IP_TTL; return 1;
        case 9: *ho = IP_MULTICAST_IF; return 1;    case 10: *ho = IP_MULTICAST_TTL; return 1;  case 11: *ho = IP_MULTICAST_LOOP; return 1;
        case 12: *ho = IP_ADD_MEMBERSHIP; return 1; case 13: *ho = IP_DROP_MEMBERSHIP; return 1;
        }
        return 0;
    case IPPROTO_TCP: *hl = IPPROTO_TCP;
        switch (opt) {
        case 1: *ho = TCP_NODELAY; return 1; case 2: *ho = TCP_MAXSEG; return 1;
        case 0x100: *ho = TCP_KEEPIDLE; return 1; case 0x200: *ho = TCP_KEEPINTVL; return 1; case 0x400: *ho = TCP_KEEPCNT; return 1;
        }
        return 0;
    }
    return 0;
}
static int sk_setsockopt_(int g, int lvl, int opt, const void *v, unsigned n) {
    HOST_FD(g, h); int hl, ho;
    if (!opt_host(lvl, opt, &hl, &ho)) {   // an option the phone has no counterpart for: taken as set, as an unknown one would hurt more than a missing tweak (logged)
        static _Atomic int k; if (atomic_fetch_add(&k, 1) < 16) LOG("online: SetSockOpt level %#x option %#x on %d: no Android counterpart, taken as set", lvl, opt, g);
        return 0;
    }
    return setsockopt(h, hl, ho, v, n) ? fail() : 0;
}
static int sk_getsockopt_(int g, int lvl, int opt, void *v, unsigned *n) {
    HOST_FD(g, h); int hl, ho;
    if (!opt_host(lvl, opt, &hl, &ho)) { LOG("online: GetSockOpt level %#x option %#x on %d: no Android counterpart", lvl, opt, g); return fail_with(ENOPROTOOPT); }
    socklen_t l = n ? *n : 0;
    if (getsockopt(h, hl, ho, v, &l)) return fail();
    if (n) *n = l;
    return 0;
}
static int sk_fcntl_(int g, int cmd, long arg, const char *name) {
    int h = fd_host(g);
    if (h < 0) { int (*up)(int, int, long) = (int (*)(int, int, long))pn_upstream(name); return up ? up(g, cmd, arg) : fail_with(EBADF); }   // upstream's eventfds
    enum { G_F_GETFD = 1, G_F_SETFD = 2, G_F_GETFL = 3, G_F_SETFL = 4, G_O_NONBLOCK = 0x800, G_O_NONBLOCK_BSD = 4 };   // 0x800 as yuzu / Lemon; FreeBSD's 4 taken too
    int f = fcntl(h, F_GETFL);
    if (f < 0) return fail();
    switch (cmd) {
    case G_F_GETFL: return (f & O_ACCMODE) | (f & O_NONBLOCK ? G_O_NONBLOCK : 0);
    case G_F_SETFL: return fcntl(h, F_SETFL, arg & (G_O_NONBLOCK | G_O_NONBLOCK_BSD) ? f | O_NONBLOCK : f & ~O_NONBLOCK) ? fail() : 0;
    case G_F_GETFD: case G_F_SETFD: return 0;   // close-on-exec: nothing is executed
    }
    return fail_with(EINVAL);
}
static int msg_host(int f) { return (f & ~0x80) | (f & 0x80 ? MSG_DONTWAIT : 0); }   // Linux's numbers (musl); FreeBSD's MSG_DONTWAIT bit (0x80) taken too
static long count(_Atomic uint64_t *c, int g, long r) { if (r > 0) atomic_fetch_add(&c[g], (uint64_t)r); return r; }
static long sk_recv_(int g, void *b, unsigned long n, int f) { HOST_FD(g, h); long r = recv(h, b, n, msg_host(f)); return r < 0 ? fail() : count(g_rx, g, r); }
static long sk_send_(int g, const void *b, unsigned long n, int f) { HOST_FD(g, h); long r = send(h, b, n, msg_host(f) | MSG_NOSIGNAL); return r < 0 ? fail() : count(g_tx, g, r); }
static long sk_recvfrom_(int g, void *b, unsigned long n, int f, void *a, unsigned *al) {
    HOST_FD(g, h); struct sockaddr_storage s = {0}; socklen_t l = sizeof s;
    long r = recvfrom(h, b, n, msg_host(f), (struct sockaddr *)&s, &l);
    if (r < 0) return fail();
    sa_out(&s, a, al); return count(g_rx, g, r);
}
static long sk_sendto_(int g, const void *b, unsigned long n, int f, const void *a, unsigned al) {
    HOST_FD(g, h); struct sockaddr_in s;
    if (!a) { long r = send(h, b, n, msg_host(f) | MSG_NOSIGNAL); return r < 0 ? fail() : count(g_tx, g, r); }
    if (sa_in(a, al, &s)) return -1;
    long r = sendto(h, b, n, msg_host(f) | MSG_NOSIGNAL, (struct sockaddr *)&s, sizeof s);
    return r < 0 ? fail() : count(g_tx, g, r);
}

// Poll and Select: descriptors of ours go to Android's poll; a call with none of ours is upstream's (eventfds, or nothing to wait on)
typedef struct { int32_t fd; int16_t events, revents; } g_pollfd;
static short ev_host(short e) { return e; }    // poll events: Linux's numbers on both sides
static short ev_guest(short e) { return e; }
static int sk_poll_(void *fds, uint64_t n, int ms) {
    g_pollfd *p = fds; uint64_t ours = 0, other = 0;
    for (uint64_t i = 0; i < n; i++) if (p[i].fd >= 0) { if (fd_host(p[i].fd) >= 0) ours++; else other++; }
    if (!ours) return dm_sk_poll(fds, n, ms);
    if (other) { static _Atomic int k; if (!atomic_exchange(&k, 1)) LOG("[RED] online: a Poll mixes sockets with descriptors that are not sockets (eventfds?): those report nothing (open)"); }
    struct pollfd st[64], *hp = n <= 64 ? st : calloc(n, sizeof *hp);
    if (!hp) return fail_with(ENOBUFS);   // ENOBUFS
    int ssl_ready = 0;   // decrypted data already waiting in an ssl connection over the socket: readable now, whatever the raw socket says
    for (uint64_t i = 0; i < n; i++) {
        hp[i].fd = p[i].fd >= 0 ? fd_host(p[i].fd) : -1; hp[i].events = ev_host(p[i].events); hp[i].revents = 0;
        if (hp[i].fd >= 0 && p[i].events & (POLLIN | POLLRDNORM) && pn_ssl_pending(p[i].fd) > 0) ssl_ready = 1;
    }
    int r = poll(hp, (nfds_t)n, ssl_ready ? 0 : ms);
    if (r >= 0) {
        r = 0;
        for (uint64_t i = 0; i < n; i++) {
            short ev = hp[i].fd >= 0 ? ev_guest(hp[i].revents) : 0;
            if (hp[i].fd >= 0 && p[i].events & (POLLIN | POLLRDNORM) && pn_ssl_pending(p[i].fd) > 0) ev |= p[i].events & (POLLIN | POLLRDNORM);
            p[i].revents = ev; r += ev != 0;
        }
    } else r = fail();
    if (hp != st) free(hp);
    return r;
}
static int sk_select_(int nfds, uint64_t *r, uint64_t *w, uint64_t *e, const int64_t *tv) {   // fd_set: 1024 bits; timeval: {i64 seconds, i64 microseconds}
    if (nfds > 1024) nfds = 1024;
    uint64_t *sets[3] = {r, w, e}; int ours = 0;
    for (int k = 0; k < 3; k++) for (int fd = 0; sets[k] && fd < nfds; fd++) if (sets[k][fd >> 6] >> (fd & 63) & 1 && fd_host(fd) >= 0) ours = 1;
    if (!ours) return dm_sk_select(nfds, r, w, e, tv);
    struct pollfd *hp = calloc(1024, sizeof *hp); int *gfd = calloc(1024, sizeof *gfd), np = 0;
    if (!hp || !gfd) { free(hp); free(gfd); return fail_with(ENOBUFS); }   // ENOBUFS
    for (int fd = 0; fd < nfds; fd++) {
        short ev = 0;
        if (r && r[fd >> 6] >> (fd & 63) & 1) ev |= POLLIN;
        if (w && w[fd >> 6] >> (fd & 63) & 1) ev |= POLLOUT;
        if (e && e[fd >> 6] >> (fd & 63) & 1) ev |= POLLPRI;
        if (ev) { hp[np] = (struct pollfd){fd_host(fd), ev, 0}; gfd[np++] = fd; }
    }
    int got = poll(hp, (nfds_t)np, tv ? (int)(tv[0] * 1000 + tv[1] / 1000) : -1);
    if (got < 0) { int r = fail(); free(hp); free(gfd); return r; }
    for (int k = 0; k < 3; k++) if (sets[k]) for (int i = 0; i < np; i++) sets[k][gfd[i] >> 6] &= ~(1ull << (gfd[i] & 63));
    int bits = 0;
    for (int i = 0; i < np; i++) {
        short v = hp[i].revents; int fd = gfd[i];
        if (r && hp[i].events & POLLIN && v & (POLLIN | POLLHUP | POLLERR)) { r[fd >> 6] |= 1ull << (fd & 63); bits++; }
        if (w && hp[i].events & POLLOUT && v & (POLLOUT | POLLERR)) { w[fd >> 6] |= 1ull << (fd & 63); bits++; }
        if (e && hp[i].events & POLLPRI && v & POLLPRI) { e[fd >> 6] |= 1ull << (fd & 63); bits++; }
    }
    free(hp); free(gfd);
    return bits;
}

// PN_NET_TRACE=1: every socket call on the game's sockets, with its result and error ([thread] first)
#define TID gettid()
static int sk_connect(int g, const void *a, unsigned n) { int r = sk_connect_(g, a, n); TRACE("Connect(%d) = %d errno %d, from %s", TID, g, r, r < 0 ? t_err : 0, where()); return r; }
static int sk_shutdown(int g, int how) { int r = sk_shutdown_(g, how); TRACE("Shutdown(%d, %d) = %d errno %d", TID, g, how, r, r < 0 ? t_err : 0); return r; }
static int sk_setsockopt(int g, int l, int o, const void *v, unsigned n) {
    int r = sk_setsockopt_(g, l, o, v, n); TRACE("SetSockOpt(%d, %#x, %#x, %d bytes: %d) = %d errno %d", TID, g, l, o, n, v && n >= 4 ? *(const int *)v : -1, r, r < 0 ? t_err : 0); return r;
}
static int sk_getsockopt(int g, int l, int o, void *v, unsigned *n) {
    int r = sk_getsockopt_(g, l, o, v, n); TRACE("GetSockOpt(%d, %#x, %#x) = %d, value %d, length %u, errno %d, from %s", TID, g, l, o, r, !r && v && n && *n >= 4 ? *(int *)v : -1, n ? *n : 0, r < 0 ? t_err : 0, where()); return r;
}
static int sk_fcntl(int g, int c, long a, const char *name) { int r = sk_fcntl_(g, c, a, name); TRACE("Fcntl(%d, cmd %d, %#lx) = %#x errno %d", TID, g, c, a, r, r < 0 ? t_err : 0); return r; }
static long sk_recv(int g, void *b, unsigned long n, int f) { long r = sk_recv_(g, b, n, f); TRACE("Recv(%d, %lu, flags %#x) = %ld errno %d", TID, g, n, f, r, r < 0 ? t_err : 0); return r; }
static long sk_send(int g, const void *b, unsigned long n, int f) { long r = sk_send_(g, b, n, f); TRACE("Send(%d, %lu, flags %#x) = %ld errno %d", TID, g, n, f, r, r < 0 ? t_err : 0); return r; }
static const char *peer_text(const void *a, char *buf) {   // a guest sockaddr as address:port, for the trace
    const g_sockaddr_in *s = a; char ip[INET_ADDRSTRLEN];
    if (!s || s->family != G_AF_INET) return "-";
    snprintf(buf, 40, "%s:%u", ip_text(s->addr, ip), ntohs(s->port)); return buf;
}
static long sk_recvfrom(int g, void *b, unsigned long n, int f, void *a, unsigned *al) {
    long r = sk_recvfrom_(g, b, n, f, a, al); char pb[40];
    if (r >= 0) TRACE("RecvFrom(%d, %lu, flags %#x) = %ld from %s", TID, g, n, f, r, peer_text(a, pb));
    else if (t_err != EAGAIN) TRACE("RecvFrom(%d, %lu, flags %#x) = %ld errno %d", TID, g, n, f, r, t_err);
    return r;
}
static long sk_sendto(int g, const void *b, unsigned long n, int f, const void *a, unsigned al) { long r = sk_sendto_(g, b, n, f, a, al); char pb[40]; TRACE("SendTo(%d, %lu, flags %#x) to %s = %ld errno %d", TID, g, n, f, peer_text(a, pb), r, r < 0 ? t_err : 0); return r; }
static int sk_poll(void *fds, uint64_t n, int ms) {
    g_pollfd *p = fds; int ours = 0;
    for (uint64_t i = 0; i < n; i++) ours |= p[i].fd >= 0 && fd_host(p[i].fd) >= 0;
    if (ours) for (uint64_t i = 0; i < n && i < 4; i++) TRACE("Poll fd %d events %#x (%llu fds, %d ms) from %s ...", TID, p[i].fd, p[i].events, (unsigned long long)n, ms, where());
    int r = sk_poll_(fds, n, ms);
    if (ours) for (uint64_t i = 0; i < n && i < 4; i++) TRACE("Poll fd %d revents %#x = %d errno %d", TID, p[i].fd, p[i].revents, r, r < 0 ? t_err : 0);
    return r;
}
static int sk_select(int nfds, uint64_t *r, uint64_t *w, uint64_t *e, const int64_t *tv) {
    uint64_t r0 = r ? r[0] : 0, w0 = w ? w[0] : 0;
    int x = sk_select_(nfds, r, w, e, tv);
    TRACE("Select(%d, read %#llx, write %#llx, %lld ms) = %d, read %#llx, write %#llx", TID, nfds, (unsigned long long)r0, (unsigned long long)w0,
          tv ? (long long)(tv[0] * 1000 + tv[1] / 1000) : -1LL, x, (unsigned long long)(r ? r[0] : 0), (unsigned long long)(w ? w[0] : 0));
    return x;
}

// ---------- the resolver ----------
// The guest's addrinfo has Android's field order (both BSD's): flags, family, socktype, protocol, addrlen, canonname, addr, next; its sockaddr is the guest's
static int sk_getaddrinfo(const char *node, const char *serv, const void *hints, void **res) {
    if (res) *res = NULL;
    struct addrinfo hh = {.ai_family = AF_INET}, *hr = NULL;
    if (hints) { const int32_t *hi = hints; hh.ai_flags = hi[0] & (AI_PASSIVE | AI_CANONNAME | AI_NUMERICHOST | AI_NUMERICSERV); hh.ai_socktype = hi[2]; hh.ai_protocol = hi[3];
                 if (hi[1] != 0 && hi[1] != G_AF_INET) return EAI_FAMILY; }
    const char *red = redirect_of(node);
    int rc = getaddrinfo(red ? red : node, serv, &hh, &hr);
    if (rc) { LOG("online: resolve %s: %s", node ? node : "(none)", gai_strerror(rc)); return rc; }   // Android's EAI_* are BSD's numbers
    struct addrinfo *head = NULL, **tail = &head; char b[INET_ADDRSTRLEN] = "?";
    for (struct addrinfo *a = hr; a; a = a->ai_next) {
        if (a->ai_family != AF_INET) continue;
        const char *canon = red ? node : a->ai_canonname; size_t cl = canon ? strlen(canon) + 1 : 0;
        struct addrinfo *o = calloc(1, sizeof *o + sizeof(g_sockaddr_in) + cl);
        if (!o) break;
        g_sockaddr_in *sa = (g_sockaddr_in *)(o + 1); const struct sockaddr_in *in = (const struct sockaddr_in *)a->ai_addr;
        *sa = (g_sockaddr_in){.len = sizeof *sa, .family = G_AF_INET, .port = in->sin_port, .addr = in->sin_addr.s_addr};
        o->ai_flags = a->ai_flags; o->ai_family = G_AF_INET; o->ai_socktype = a->ai_socktype; o->ai_protocol = a->ai_protocol;
        o->ai_addrlen = sizeof *sa; o->ai_addr = (struct sockaddr *)sa;
        if (cl) { o->ai_canonname = (char *)(sa + 1); memcpy(o->ai_canonname, canon, cl); }
        if (red) remember(in->sin_addr.s_addr, node);
        if (!head) ip_text(in->sin_addr.s_addr, b);
        *tail = o; tail = &o->ai_next;
    }
    freeaddrinfo(hr);
    if (!head) return EAI_NONAME;
    LOG("online: resolve %s -> %s%s", node ? node : "(none)", b, red ? " (Nextendo Network)" : "");
    if (res) *res = head; else for (struct addrinfo *a = head, *nx; a; a = nx) { nx = a->ai_next; free(a); }
    return 0;
}
static void sk_freeaddrinfo(void *ai) { for (struct addrinfo *a = ai, *nx; a; a = nx) { nx = a->ai_next; free(a); } }   // ours: one block per entry
static void *sk_gethostbyname(const char *name) {
    static __thread struct { struct hostent he; char name[256]; char *aliases[1]; uint32_t addr[8]; char *list[9]; } t;
    struct addrinfo *ai = NULL;
    if (sk_getaddrinfo(name, NULL, NULL, (void **)&ai)) { set_errno(ENETDOWN, "GetHostByName"); return NULL; }
    memset(&t, 0, sizeof t); snprintf(t.name, sizeof t.name, "%s", name);
    int k = 0;
    for (struct addrinfo *a = ai; a && k < 8; a = a->ai_next) {
        uint32_t ad = ((g_sockaddr_in *)a->ai_addr)->addr; int dup = 0;
        for (int j = 0; j < k; j++) dup |= t.addr[j] == ad;
        if (!dup) { t.addr[k] = ad; t.list[k] = (char *)&t.addr[k]; k++; }
    }
    sk_freeaddrinfo(ai);
    t.he.h_name = t.name; t.he.h_aliases = t.aliases; t.he.h_addrtype = AF_INET; t.he.h_length = 4; t.he.h_addr_list = t.list;
    return &t.he;
}

// ---------- nsd: server names ----------
// Fqdn is char[0x100]. Horizon puts the environment ("lp1" on retail) in for '%' and sends the account front-ends to the BAAS host behind them (citron-nextendo's mapping)
static Result nsd_resolve(char *out, const char *in) {
    if (!out || !in) return RES_FAIL;
    static const char *const map[][2] = {
        {"api.accounts.nintendo.com", "e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com"},
        {"accounts.nintendo.com", "e0d67c509fb203858ebcb2fe3f88c2aa.baas.nintendo.com"},
        {"e97b8a9d672e4ce4845ec6947cd66ef6-sb-api.accounts.nintendo.com", "e97b8a9d672e4ce4845ec6947cd66ef6-sb.baas.nintendo.com"},
        {"e97b8a9d672e4ce4845ec6947cd66ef6-sb.accounts.nintendo.com", "e97b8a9d672e4ce4845ec6947cd66ef6-sb.baas.nintendo.com"},
    };
    char b[0x100]; size_t o = 0;
    for (unsigned i = 0; i < sizeof map / sizeof *map; i++) if (!strcmp(in, map[i][0])) { snprintf(b, sizeof b, "%s", map[i][1]); goto done; }
    for (size_t i = 0; i < 0x100 && in[i] && o < sizeof b - 4; i++) { if (in[i] == '%') { memcpy(b + o, "lp1", 3); o += 3; } else b[o++] = in[i]; }
    b[o] = 0;
done:
    { static _Atomic int k; if (atomic_fetch_add(&k, 1) < 32) LOG("online: nsd %s -> %s", in, b); }
    memcpy(out, b, strlen(b) + 1);
    return RES_OK;
}

// ---------- nifm: the network is up ----------
static uint32_t local_ip_now(uint32_t *mask);
static uint32_t local_ip(uint32_t *mask) {   // cached for 5 s: games ask IsNetworkAvailable as often as every frame
    static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER; static uint32_t ip, m; static int64_t at = INT64_MIN / 2;
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); int64_t now = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    pthread_mutex_lock(&mu);
    if (now - at > 5000) { ip = local_ip_now(&m); at = now; }
    uint32_t r = ip; if (mask) *mask = m;
    pthread_mutex_unlock(&mu);
    return r;
}
static uint32_t local_ip_now(uint32_t *mask) {   // the address Android routes out of (a UDP connect sends nothing), and its interface's mask
    uint32_t ip = 0, m = htonl(0xffffff00u);
    int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s >= 0) {
        struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(53)}; a.sin_addr.s_addr = inet_addr("1.1.1.1");
        socklen_t l = sizeof a;
        if (!connect(s, (struct sockaddr *)&a, sizeof a) && !getsockname(s, (struct sockaddr *)&a, &l)) ip = a.sin_addr.s_addr;
        close(s);
    }
    struct ifaddrs *ifs = NULL;
    if (ip && !getifaddrs(&ifs)) {
        for (struct ifaddrs *i = ifs; i; i = i->ifa_next)
            if (i->ifa_addr && i->ifa_netmask && i->ifa_addr->sa_family == AF_INET && ((struct sockaddr_in *)i->ifa_addr)->sin_addr.s_addr == ip) m = ((struct sockaddr_in *)i->ifa_netmask)->sin_addr.s_addr;
        freeifaddrs(ifs);
    }
    if (mask) *mask = m;
    static _Atomic int once; if (!atomic_exchange(&once, 1)) { char b[INET_ADDRSTRLEN]; LOG("online: the phone's address %s", ip ? ip_text(ip, b) : "(none: no network?)"); }
    return ip;
}
static Result ip_config(uint32_t *ip, uint32_t *mask, uint32_t *gw, uint32_t *dns1, uint32_t *dns2) {
    uint32_t m, a = local_ip(&m);
    if (!a) return RES_FAIL;
    if (ip) *ip = a;
    if (mask) *mask = m;
    if (gw) *gw = (a & m) | htonl(1);   // Android does not tell an app its gateway; the subnet's first address, which nothing here routes by
    if (dns1) *dns1 = inet_addr("1.1.1.1");
    if (dns2) *dns2 = inet_addr("8.8.8.8");
    return RES_OK;
}

// ================= the table =================
#define WHY "online (dm/peanuts/net)"
// nifm
PN_FORCE_BIG("_ZN2nn4nifm18IsNetworkAvailableEv", WHY ": the network is available", int, pn_nifm_Available, void) { return local_ip(NULL) != 0; }
PN_FORCE("_ZN2nn4nifm27SubmitNetworkRequestAndWaitEv", WHY ": the request is accepted at once", void, pn_nifm_SubmitWait, void) {}
PN_FORCE("_ZN2nn4nifm20SubmitNetworkRequestEv", WHY ": the request is accepted at once", void, pn_nifm_Submit, void) {}
PN_FORCE("_ZN2nn4nifm22IsNetworkRequestOnHoldEv", WHY ": never on hold", int, pn_nifm_OnHold, void) { return 0; }
PN_FORCE("_ZN2nn4nifm20CancelNetworkRequestEv", WHY ": nothing to cancel", void, pn_nifm_Cancel, void) {}
PN_FORCE("_ZN2nn4nifm19SetLocalNetworkModeEb", WHY ": the local network mode switch is ignored", void, pn_nifm_LocalMode, unsigned on) { (void)on; }
PN_FORCE("_ZN2nn4nifm31HandleNetworkRequestErrorResultEv", WHY ": not resolved, no system network screen", unsigned, pn_nifm_HandleErr, void) { return 0; }
PN_FORCE_BIG("_ZN2nn4nifm26GetCurrentPrimaryIpAddressEP7in_addr", WHY ": the phone's address", Result, pn_nifm_Ip, uint32_t *a) { return ip_config(a, NULL, NULL, NULL, NULL); }
PN_FORCE_BIG("_ZN2nn4nifm26GetCurrentPrimaryIpAddressEPNS_6socket6InAddrE", WHY ": the phone's address", Result, pn_nifm_Ip2, uint32_t *a) { return ip_config(a, NULL, NULL, NULL, NULL); }
PN_FORCE_BIG("_ZN2nn4nifm22GetCurrentIpConfigInfoEP7in_addrS2_S2_S2_S2_", WHY ": the phone's address", Result, pn_nifm_IpCfg, uint32_t *a, uint32_t *m, uint32_t *g, uint32_t *d1, uint32_t *d2) { return ip_config(a, m, g, d1, d2); }
PN_FORCE_BIG("_ZN2nn4nifm22GetCurrentIpConfigInfoEPNS_6socket6InAddrES3_S3_S3_S3_", WHY ": the phone's address", Result, pn_nifm_IpCfg2, uint32_t *a, uint32_t *m, uint32_t *g, uint32_t *d1, uint32_t *d2) { return ip_config(a, m, g, d1, d2); }
// ProxySetting starts {bool enabled; u8; u16 port; ...}: not enabled. Only those 4 bytes are written, as the struct's size changed between SDKs
PN_FORCE("_ZN2nn4nifm22GetCurrentProxySettingEPNS0_12ProxySettingE", WHY ": no proxy", Result, pn_nifm_Proxy, uint8_t *p) { if (p) memset(p, 0, 4); return RES_OK; }

// nsd
PN_FORCE("_ZN2nn3nsd9ResolveExEPNS0_4FqdnERKS1_", WHY ": server name with the environment put in", Result, pn_nsd_ResolveEx, char *out, const char *in) { return nsd_resolve(out, in); }
PN_FORCE("_ZN2nn3nsd7ResolveEPNS0_4FqdnERKS1_", WHY ": server name with the environment put in", Result, pn_nsd_Resolve, char *out, const char *in) { return nsd_resolve(out, in); }

// socket, SDK 4-era overloads (plain sockaddr / pollfd / fd_set, int flags)
PN_FORCE("_ZN2nn6socket6SocketEiii", WHY, int, pn_sk_Socket, int d, int t, int p) { return sk_socket(d, t, p); }
PN_FORCE("_ZN2nn6socket5CloseEi", WHY, int, pn_sk_Close, int s) { return sk_close(s, "_ZN2nn6socket5CloseEi"); }
PN_FORCE("_ZN2nn6socket4BindEiPK8sockaddrj", WHY, int, pn_sk_Bind, int s, const void *a, unsigned n) { return sk_bind(s, a, n); }
PN_FORCE("_ZN2nn6socket7ConnectEiPK8sockaddrj", WHY, int, pn_sk_Connect, int s, const void *a, unsigned n) { return sk_connect(s, a, n); }
PN_FORCE("_ZN2nn6socket6ListenEii", WHY, int, pn_sk_Listen, int s, int b) { return sk_listen(s, b); }
PN_FORCE("_ZN2nn6socket6AcceptEiP8sockaddrPj", WHY, int, pn_sk_Accept, int s, void *a, unsigned *n) { return sk_accept(s, a, n); }
PN_FORCE("_ZN2nn6socket8ShutdownEii", WHY, int, pn_sk_Shutdown, int s, int h) { return sk_shutdown(s, h); }
PN_FORCE("_ZN2nn6socket11GetPeerNameEiP8sockaddrPj", WHY, int, pn_sk_GetPeerName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 1); }
PN_FORCE("_ZN2nn6socket11GetSockNameEiP8sockaddrPj", WHY, int, pn_sk_GetSockName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 0); }
PN_FORCE("_ZN2nn6socket10GetSockOptEiiiPvPj", WHY, int, pn_sk_GetSockOpt, int s, int l, int o, void *v, unsigned *n) { return sk_getsockopt(s, l, o, v, n); }
PN_FORCE("_ZN2nn6socket10SetSockOptEiiiPKvj", WHY, int, pn_sk_SetSockOpt, int s, int l, int o, const void *v, unsigned n) { return sk_setsockopt(s, l, o, v, n); }
PN_FORCE("_ZN2nn6socket5FcntlEiiz", WHY, int, pn_sk_Fcntl, int s, int c, long a) { return sk_fcntl(s, c, a, "_ZN2nn6socket5FcntlEiiz"); }
PN_FORCE("_ZN2nn6socket4PollEP6pollfdmi", WHY, int, pn_sk_Poll, void *f, uint64_t n, int t) { return sk_poll(f, n, t); }
PN_FORCE("_ZN2nn6socket6SelectEiP6fd_setS2_S2_P7timeval", WHY, int, pn_sk_Select, int n, uint64_t *r, uint64_t *w, uint64_t *e, const int64_t *t) { return sk_select(n, r, w, e, t); }
PN_FORCE("_ZN2nn6socket4RecvEiPvmi", WHY, long, pn_sk_Recv, int s, void *b, unsigned long n, int f) { return sk_recv(s, b, n, f); }
PN_FORCE("_ZN2nn6socket4SendEiPKvmi", WHY, long, pn_sk_Send, int s, const void *b, unsigned long n, int f) { return sk_send(s, b, n, f); }
PN_FORCE("_ZN2nn6socket8RecvFromEiPvmiP8sockaddrPj", WHY, long, pn_sk_RecvFrom, int s, void *b, unsigned long n, int f, void *a, unsigned *al) { return sk_recvfrom(s, b, n, f, a, al); }
PN_FORCE("_ZN2nn6socket6SendToEiPKvmiPK8sockaddrj", WHY, long, pn_sk_SendTo, int s, const void *b, unsigned long n, int f, const void *a, unsigned al) { return sk_sendto(s, b, n, f, a, al); }
PN_FORCE("_ZN2nn6socket4ReadEiPvm", WHY, long, pn_sk_Read, int s, void *b, unsigned long n) { return sk_recv(s, b, n, 0); }
PN_FORCE("_ZN2nn6socket5WriteEiPKvm", WHY, long, pn_sk_Write, int s, const void *b, unsigned long n) { return sk_send(s, b, n, 0); }
PN_FORCE("_ZN2nn6socket12GetLastErrnoEv", WHY ": the last socket error", int, pn_sk_Errno, void) { if (t_err != EAGAIN) TRACE("GetLastErrno = %d", TID, t_err); return t_err; }
PN_FORCE("_ZN2nn6socket12GetLastErrorEv", WHY ": the last socket error", int, pn_sk_Error, void) { return t_err; }
PN_FORCE_BIG("_ZN2nn6socket11GetAddrInfoEPKcS2_PK8addrinfoPPS3_", WHY ": with Nextendo Network's redirect", int, pn_sk_Gai, const char *h, const char *s, const void *hi, void **r) { return sk_getaddrinfo(h, s, hi, r); }
PN_FORCE("_ZN2nn6socket12FreeAddrInfoEP8addrinfo", WHY, void, pn_sk_FreeAi, void *ai) { sk_freeaddrinfo(ai); }
PN_FORCE("_ZN2nn6socket11GAIStrErrorENS0_7AiErrnoE", WHY, const char *, pn_sk_GaiStr, int e) { return gai_strerror(e); }

// socket, later SDKs' overloads (SockAddr / PollFd / FdSet / TimeVal and enum arguments: the same layouts and numbers)
PN_FORCE("_ZN2nn6socket6SocketENS0_6FamilyENS0_4TypeENS0_8ProtocolE", WHY, int, pn_sk2_Socket, int d, int t, int p) { return sk_socket(d, t, p); }
PN_FORCE("_ZN2nn6socket4BindEiPKNS0_8SockAddrEj", WHY, int, pn_sk2_Bind, int s, const void *a, unsigned n) { return sk_bind(s, a, n); }
PN_FORCE("_ZN2nn6socket7ConnectEiPKNS0_8SockAddrEj", WHY, int, pn_sk2_Connect, int s, const void *a, unsigned n) { return sk_connect(s, a, n); }
PN_FORCE("_ZN2nn6socket6AcceptEiPNS0_8SockAddrEPj", WHY, int, pn_sk2_Accept, int s, void *a, unsigned *n) { return sk_accept(s, a, n); }
PN_FORCE("_ZN2nn6socket8ShutdownEiNS0_14ShutdownMethodE", WHY, int, pn_sk2_Shutdown, int s, int h) { return sk_shutdown(s, h); }
PN_FORCE("_ZN2nn6socket11GetPeerNameEiPNS0_8SockAddrEPj", WHY, int, pn_sk2_GetPeerName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 1); }
PN_FORCE("_ZN2nn6socket11GetSockNameEiPNS0_8SockAddrEPj", WHY, int, pn_sk2_GetSockName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 0); }
PN_FORCE("_ZN2nn6socket10GetSockOptEiNS0_5LevelENS0_6OptionEPvPj", WHY, int, pn_sk2_GetSockOpt, int s, int l, int o, void *v, unsigned *n) { return sk_getsockopt(s, l, o, v, n); }
PN_FORCE("_ZN2nn6socket10SetSockOptEiNS0_5LevelENS0_6OptionEPKvj", WHY, int, pn_sk2_SetSockOpt, int s, int l, int o, const void *v, unsigned n) { return sk_setsockopt(s, l, o, v, n); }
PN_FORCE("_ZN2nn6socket5FcntlEiNS0_12FcntlCommandEz", WHY, int, pn_sk2_Fcntl, int s, int c, long a) { return sk_fcntl(s, c, a, "_ZN2nn6socket5FcntlEiNS0_12FcntlCommandEz"); }
PN_FORCE("_ZN2nn6socket4PollEPNS0_6PollFdEmi", WHY, int, pn_sk2_Poll, void *f, uint64_t n, int t) { return sk_poll(f, n, t); }
PN_FORCE("_ZN2nn6socket6SelectEiPNS0_5FdSetES2_S2_PNS0_7TimeValE", WHY, int, pn_sk2_Select, int n, uint64_t *r, uint64_t *w, uint64_t *e, const int64_t *t) { return sk_select(n, r, w, e, t); }
PN_FORCE("_ZN2nn6socket4RecvEiPvmNS0_7MsgFlagE", WHY, long, pn_sk2_Recv, int s, void *b, unsigned long n, int f) { return sk_recv(s, b, n, f); }
PN_FORCE("_ZN2nn6socket4SendEiPKvmNS0_7MsgFlagE", WHY, long, pn_sk2_Send, int s, const void *b, unsigned long n, int f) { return sk_send(s, b, n, f); }
PN_FORCE("_ZN2nn6socket8RecvFromEiPvmNS0_7MsgFlagEPNS0_8SockAddrEPj", WHY, long, pn_sk2_RecvFrom, int s, void *b, unsigned long n, int f, void *a, unsigned *al) { return sk_recvfrom(s, b, n, f, a, al); }
PN_FORCE("_ZN2nn6socket6SendToEiPKvmNS0_7MsgFlagEPKNS0_8SockAddrEj", WHY, long, pn_sk2_SendTo, int s, const void *b, unsigned long n, int f, const void *a, unsigned al) { return sk_sendto(s, b, n, f, a, al); }
PN_FORCE_BIG("_ZN2nn6socket11GetAddrInfoEPKcS2_PKNS0_8AddrInfoEPPS3_", WHY ": with Nextendo Network's redirect", int, pn_sk2_Gai, const char *h, const char *s, const void *hi, void **r) { return sk_getaddrinfo(h, s, hi, r); }
PN_FORCE("_ZN2nn6socket12FreeAddrInfoEPNS0_8AddrInfoE", WHY, void, pn_sk2_FreeAi, void *ai) { sk_freeaddrinfo(ai); }

// socket, C interface
PN_FORCE("nnsocketSocket", WHY, int, pn_csk_Socket, int d, int t, int p) { return sk_socket(d, t, p); }
PN_FORCE("nnsocketClose", WHY, int, pn_csk_Close, int s) { t_ra = __builtin_return_address(0); return sk_close(s, "nnsocketClose"); }
PN_FORCE("nnsocketBind", WHY, int, pn_csk_Bind, int s, const void *a, unsigned n) { return sk_bind(s, a, n); }
PN_FORCE("nnsocketConnect", WHY, int, pn_csk_Connect, int s, const void *a, unsigned n) { t_ra = __builtin_return_address(0); return sk_connect(s, a, n); }
PN_FORCE("nnsocketListen", WHY, int, pn_csk_Listen, int s, int b) { return sk_listen(s, b); }
PN_FORCE("nnsocketAccept", WHY, int, pn_csk_Accept, int s, void *a, unsigned *n) { return sk_accept(s, a, n); }
PN_FORCE("nnsocketShutdown", WHY, int, pn_csk_Shutdown, int s, int h) { return sk_shutdown(s, h); }
PN_FORCE("nnsocketGetPeerName", WHY, int, pn_csk_GetPeerName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 1); }
PN_FORCE("nnsocketGetSockName", WHY, int, pn_csk_GetSockName, int s, void *a, unsigned *n) { return sk_name(s, a, n, 0); }
PN_FORCE("nnsocketGetSockOpt", WHY, int, pn_csk_GetSockOpt, int s, int l, int o, void *v, unsigned *n) { t_ra = __builtin_return_address(0); return sk_getsockopt(s, l, o, v, n); }
PN_FORCE("nnsocketSetSockOpt", WHY, int, pn_csk_SetSockOpt, int s, int l, int o, const void *v, unsigned n) { return sk_setsockopt(s, l, o, v, n); }
PN_FORCE("nnsocketFcntl", WHY, int, pn_csk_Fcntl, int s, int c, long a) { return sk_fcntl(s, c, a, "nnsocketFcntl"); }
PN_FORCE("nnsocketPoll", WHY, int, pn_csk_Poll, void *f, uint64_t n, int t) { t_ra = __builtin_return_address(0); return sk_poll(f, n, t); }
PN_FORCE("nnsocketRecv", WHY, long, pn_csk_Recv, int s, void *b, unsigned long n, int f) { return sk_recv(s, b, n, f); }
PN_FORCE("nnsocketSend", WHY, long, pn_csk_Send, int s, const void *b, unsigned long n, int f) { return sk_send(s, b, n, f); }
PN_FORCE("nnsocketGetLastErrno", WHY ": the last socket error", int, pn_csk_Errno, void) { TRACE("nnsocketGetLastErrno = %d", TID, t_err); return t_err; }
PN_FORCE("nnsocketSetLastErrno", WHY, void, pn_csk_SetErrno, int e) { t_err = e; }
PN_FORCE_BIG("nnsocketGetAddrInfo", WHY ": with Nextendo Network's redirect", int, pn_csk_Gai, const char *h, const char *s, const void *hi, void **r) { return sk_getaddrinfo(h, s, hi, r); }
PN_FORCE("nnsocketFreeAddrInfo", WHY, void, pn_csk_FreeAi, void *ai) { sk_freeaddrinfo(ai); }
PN_FORCE("nnsocketGAIStrError", WHY, const char *, pn_csk_GaiStr, int e) { return gai_strerror(e); }
// The resolver options are not looked at: the redirect is the host's own
PN_FORCE_BIG("nnsocketGetHostByNameWithOptions", WHY ": with Nextendo Network's redirect", void *, pn_csk_GetHost, const char *n, const void *o, unsigned long c) { (void)o; (void)c; return sk_gethostbyname(n); }
PN_FORCE_BIG("nnsocketGetAddrInfoWithOptions", WHY ": with Nextendo Network's redirect", int, pn_csk_GaiOpt, const char *h, const char *s, const void *hi, void **r, const void *o, unsigned long c) { (void)o; (void)c; return sk_getaddrinfo(h, s, hi, r); }
