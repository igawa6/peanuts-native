# dm/peanuts/net: online play on Nextendo Network

Games go online on [Nextendo Network](https://nextendo.network), the community replacement for Nintendo's online
services. Off unless `PN_ONLINE=1` is in the host's environment (`env.txt`), and then only for a game build it is verified
on: `g_verified` in `pn_net.c`, by main's build ID (today Mario Kart 8 Deluxe 4.0.1). Any other game stays offline with a
`[RED]` line until it is tried with `PN_ONLINE_UNVERIFIED=1` and added there. Off, every network call keeps
upstream's offline answers (`dm/host/hle_net.c`, `hle_offline.c`, `hle_account.c`). Upstream's local wireless over IP (`DM_COOP`,
`hle_coop.c`) replaces the same socket calls, so with `DM_COOP` set online play stays off for the run (a `[RED]` line says so).

## How

The online hooks are their own table (the `pnforce` section, `PN_FORCE` / `PN_FORCE_BIG` in `pn_net.h`), which
`dm/host/loader.c`'s `force_lookup` asks before upstream's `swforce` (one `PEANUTS` hook). The game's own network
code is left as the rewriter leaves it; what changes is the system it calls.

| File | What it serves |
|---|---|
| `pn_net.c` | nifm (the network is up, the phone's address), the SDK's socket calls on Android's sockets, the resolver with Nextendo's DNS redirect, nsd's server names |
| `pn_ssl.c` | `nn::ssl` and the `nnssl*` C interface on Mbed TLS (`dm/peanuts/third_party/mbedtls`) |
| `pn_trust.c`, `pn_nextendo_ca.h` | which server certificates a connection trusts (below); Nextendo Network's published root CA |
| `pn_account.c` | the network service account as the Nextendo account (PID, id_token); the error applet on the host stack |
| `pn_friends.c` | the friends calls a game makes around online play: they succeed and change nothing |
| `pn_mbedtls_config.h` | Mbed TLS's threading, for the library and this folder alike |

The SDK's socket API (Mario Kart 8 Deluxe's SDK 4.4, on musl) mixes numberings: socket option levels and names and
`sockaddr` are FreeBSD's, but error numbers, `fcntl`'s `O_NONBLOCK`, poll events and `MSG_*` flags are Linux's. The
host replaces the SDK's client functions, so it speaks the game's side of that API, not the bsd service's behind it
(which yuzu / Eden / Lemon emulate). See the comment at the top of `pn_net.c`.

## Servers and certificates

The DNS redirect sends Nintendo's online hosts to Nextendo Network's server, `51.178.29.194`, and the NAT check's second host
(`nncs2-*.n.n.srv.nintendo.net`) to its second server, `164.132.111.120`. Both are Nextendo Network's: they are the addresses its
official Switch client [Prelude-Nextendo](https://git.nextendo.network/NextendoNetwork/Prelude-Nextendo) uses
(`source/nextendo_config.h`: `NEXTENDO_SERVER_IP_DEFAULT`, and `NEXTENDO_SERVER_IP_NNCSD2`, the OVH server that runs Nextendo's
[NAT-check responder](https://git.nextendo.network/NextendoNetwork/nextendo-nncs)), and the certificates they present are
Nextendo's. `PN_NEXTENDO_SERVER` and `PN_NEXTENDO_NAT` replace them.

Every TLS connection verifies the server's certificate, with its host name (`pn_trust.c`):

- **A connection to an address of the redirect** trusts Nextendo Network's root CA (`pn_nextendo_ca.h`: `romfs/sd/rootCA.pem` of
  Prelude-Nextendo, the certificate Nextendo publishes for its clients). Its account, `znc` and NAT-check servers present
  certificates that CA issued for their names. Nextendo's game servers still present one self-signed certificate with no host
  name (`CN=nintendo-private-server`; Mario Kart 8 Deluxe's `g2b309e01-lp1.s.n.srv.nintendo.net`, on both addresses), which a
  redirected connection alone takes by its pinned public key (SHA-256 of the SubjectPublicKeyInfo, `770551f1…74b8`; more with
  `PN_NEXTENDO_PIN`). Nothing else is accepted there: another self-signed key, a wrong name or an expired certificate fails.
- **Any other connection** trusts the phone's system CAs (`/apex/com.android.conscrypt/cacerts`, else
  `/system/etc/security/cacerts`), and neither Nextendo's CA nor the pin.

A refused certificate fails the handshake, and the log says what it was checked against and why. The game's own verification
settings are not taken (the certificates it expects are Nintendo's, which none of these servers present).

## Credits

The DNS redirect, the token layout and the ssl fixes follow citron-nextendo and
[Lemon](https://github.com/Ghael-V/Lemon-Project) (GPL-3.0-or-later). Ryujinx-Nextendo (PolyForm Shield) is a
reference only; none of its code is here. Prelude-Nextendo (PolyForm Shield) gives Nextendo's root CA certificate and its
two server addresses: Nextendo's public data for its clients, not its code (none of that is here either).

## The account (sign-in)

The host takes the signed-in Nextendo account from `PN_NEXTENDO_PID` and `PN_NEXTENDO_TOKEN`. Sign in with your own Nextendo
application, which you register once:

1. Sign in on [nextendo.network](https://nextendo.network), open [Developers](https://nextendo.network/developers) and
   create an application: a **public application (desktop / mobile, PKCE)**, which has no secret.
2. Give it a loopback redirect, `http://127.0.0.1:<port>/callback` with a fixed port of your choice (a registered
   application's redirect must match exactly), and the scopes `identity friends presence game.matchmaking`.
3. `peanuts/scripts/nextendo-signin.py --client-id nxc_... --port <port>` signs in in the computer's browser and writes
   `PN_ONLINE=1` and the account into the app's `env.txt` over adb, without printing them.

## Settings (`env.txt`)

| Key | Meaning |
|---|---|
| `PN_ONLINE=1` | online on, for the verified game builds |
| `PN_ONLINE_UNVERIFIED=1` | with `PN_ONLINE=1`: online on for any game, to try one that is not verified yet |
| `PN_NET_TRACE=1` | every socket and ssl call on the game's connections, with its result and the game's call site |
| `PN_NEXTENDO_SERVER`, `PN_NEXTENDO_NAT` | the redirect's addresses (default: Nextendo Network's) |
| `PN_NEXTENDO_PIN` | more server keys a redirected connection takes (SHA-256 of the SubjectPublicKeyInfo, 64 hex digits, comma-separated), for a Nextendo server whose self-signed key changed |
| `PN_NEXTENDO_PID`, `PN_NEXTENDO_TOKEN` | the signed-in account (`peanuts/scripts/nextendo-signin.py` writes it); never logged |

## Status

- 2026-11-13: Mario Kart 8 Deluxe 4.0.1 on the AYN Thor reaches Nextendo's login server: DNS redirect, TCP 443,
  TLS 1.3 (certificate `CN=nintendo-private-server`; not verified then, see 2026-11-16), the WebSocket upgrade (`101 Switching
  Protocols`) and the login exchange. Without a signed-in account the server turns the login away (error
  2306-0805). Next: sign-in (a Nextendo client ID for Peanuts), then matchmaking and Pia's peer-to-peer.
- 2026-11-13: signed in (with the launcher's sign-in that follows this change), Mario Kart 8 Deluxe logs in and reaches its
  online menu: the game-server session stays up, and Pia's NAT check gets its answers from both of Nextendo's
  responders (UDP, `MSG_DONTWAIT` as FreeBSD's 0x80).
- 2026-11-13: with the friends calls answered (`pn_friends.c`: `DeclareOpenOnlinePlaySession`, `AddPlayHistory`, ...;
  upstream's failures showed "An error has occurred" at character select), Mario Kart 8 Deluxe joins a Worldwide room and
  finishes a race against other players over Pia's peer-to-peer (about 750 KB each way). Mii heads draw nothing (the
  host's MiiModel stand-in, as offline).
- 2026-11-16: server certificates are verified (`pn_trust.c`, above). Checked on the PC against the live servers with the same code
  and Mbed TLS build: 14 of 14 cases as expected (Nextendo's account, `znc` and NAT-check hosts by its CA, Mario Kart's game server
  by its pinned key on both addresses; a wrong name, another self-signed key, an untrusted root and an expired certificate refused;
  Nextendo's CA and the pin refused off the redirect; github.com by the system's CAs). The AYN Thor's Android 13 store
  (`/system/etc/security/cacerts`, 125 certificates) parses whole.
- 2026-11-16, on the AYN Thor with Mario Kart 8 Deluxe 4.0.1 on Peanuts Native 0.1.3: three online runs (Turnip Gen8 V39 twice,
  the system driver once) each verify Nextendo's account server (`api-lp1.znc.srv.nintendo.net`) by its CA and the game server
  (`g2b309e01-lp1.s.n.srv.nintendo.net`) by its pinned key, log in, and join Worldwide rooms with 8 to 10 other players
  (0.5 to 1.6 MB of Pia traffic in). No certificate was refused. With Online play off (Turnip, 65 s of play at 60 fps) the log
  has no line of this folder's and no `[RED]` line that a run from before it lacks.
- Open: a Poll that mixes the game's sockets with upstream's eventfds reports nothing for the eventfds; ssl calls on
  a connection after its Destroy fail (`cn_id`), which Mario Kart only does while tearing down.
