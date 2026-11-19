// PEANUTS: which server certificates nn::ssl's connections trust (pn_ssl.c). No host code here, so the PC can test it against the live servers.
#ifndef PN_TRUST_H
#define PN_TRUST_H
#include <mbedtls/ssl.h>

typedef struct {
    int redirected;   // in: the connection goes to an address of the DNS redirect (Nextendo Network)
    int pinned;       // out: the handshake trusted the server by its pinned key
} pn_trust_t;

// Sets conf's verification: always required, with the host name of mbedtls_ssl_set_hostname. A redirected connection trusts Nextendo Network's CA
// (pn_nextendo_ca.h) and, for Nextendo's self-signed servers only, their pinned keys; any other trusts the phone's system CAs. t must outlive the handshake.
void pn_trust_conf(mbedtls_ssl_config *conf, pn_trust_t *t);
const char *pn_trust_how(const pn_trust_t *t);       // what the server was verified against, for the log
int pn_trust_system_count(void);                     // certificates read from the system's store
void pn_trust_set_system_dirs(const char *const *dirs);   // tests only, before the first pn_trust_conf: where the system CAs are
#endif
