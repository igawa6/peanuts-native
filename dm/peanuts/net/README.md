# dm/peanuts/net: online play on Nextendo Network

Games go online on [Nextendo Network](https://nextendo.network), the community replacement for Nintendo's online
services. Off unless `PN_ONLINE=1` is in the host's environment (`env.txt`): with it off, every network call keeps
upstream's offline answers (`dm/host/hle_net.c`, `hle_offline.c`, `hle_account.c`).

## How

The online hooks are their own table (the `pnforce` section, `PN_FORCE` / `PN_FORCE_BIG` in `pn_net.h`), which
`dm/host/loader.c`'s `force_lookup` asks before upstream's `swforce` (one `PEANUTS` hook). The game's own network
code is left as the rewriter leaves it; what changes is the system it calls.

| File | What it serves |
|---|---|
| `pn_net.c` | nifm (the network is up, the phone's address), the SDK's socket calls on Android's sockets, the resolver with Nextendo's DNS redirect, nsd's server names |
| `pn_ssl.c` | `nn::ssl` and the `nnssl*` C interface on Mbed TLS (`dm/peanuts/third_party/mbedtls`) |
| `pn_account.c` | the network service account as the Nextendo account (PID, id_token); the error applet on the host stack |
| `pn_friends.c` | the friends calls a game makes around online play: they succeed and change nothing |
| `pn_mbedtls_config.h` | Mbed TLS's threading, for the library and this folder alike |

The SDK's socket API (Mario Kart 8 Deluxe's SDK 4.4, on musl) mixes numberings: socket option levels and names and
`sockaddr` are FreeBSD's, but error numbers, `fcntl`'s `O_NONBLOCK`, poll events and `MSG_*` flags are Linux's. The
host replaces the SDK's client functions, so it speaks the game's side of that API, not the bsd service's behind it
(which yuzu / Eden / Lemon emulate). See the comment at the top of `pn_net.c`.

The DNS redirect, the token layout and the ssl fixes follow citron-nextendo and
[Lemon](https://github.com/Ghael-V/Lemon-Project) (GPL-3.0-or-later). Ryujinx-Nextendo (PolyForm Shield) is a
reference only; none of its code is here.

## Your own Nextendo application (sign-in)

Settings › Online signs in with OAuth 2.0 and PKCE (`dev.peanuts.online.Nextendo`): the password is only ever typed on
nextendo.network, in the browser. The build carries no Nextendo application of its own; each builder registers one:

1. Sign in on [nextendo.network](https://nextendo.network), open [Developers](https://nextendo.network/developers) and
   create an application: a **public application (desktop / mobile, PKCE)**, which has no secret.
2. Give it a loopback redirect, `http://127.0.0.1:<port>/callback` with a fixed port of your choice (a registered
   application's redirect must match exactly), and the scopes `identity friends presence game.matchmaking`.
3. Build with its public client ID and that port:
   `./gradlew apk -PnxApp=peanuts -PnxNextendoClientId=nxc_... -PnxNextendoRedirectPort=<port> ...`

A build without them shows "This build has no Nextendo application" in Settings › Online instead of signing in.
`peanuts/scripts/nextendo-signin.py --client-id nxc_... --port <port>` does the same sign-in from a computer and
writes the account into the app's `env.txt` over adb (for testing the host side alone).

## Settings (`env.txt`)

| Key | Meaning |
|---|---|
| `PN_ONLINE=1` | online on |
| `PN_NET_TRACE=1` | every socket and ssl call on the game's connections, with its result and the game's call site |
| `PN_NEXTENDO_SERVER`, `PN_NEXTENDO_NAT` | the redirect's addresses (default: Nextendo Network's) |
| `PN_NEXTENDO_PID`, `PN_NEXTENDO_TOKEN` | the signed-in account, for testing until the launcher's sign-in hands them over; never logged |

## Status

- 2026-11-13: Mario Kart 8 Deluxe 4.0.1 on the AYN Thor reaches Nextendo's login server: DNS redirect, TCP 443,
  TLS 1.3 (certificate `CN=nintendo-private-server`, not verified), the WebSocket upgrade (`101 Switching
  Protocols`) and the login exchange. Without a signed-in account the server turns the login away (error
  2306-0805). Next: sign-in (a Nextendo client ID for Peanuts), then matchmaking and Pia's peer-to-peer.
- 2026-11-13: signed in from Settings › Online (`dev.peanuts.online.Nextendo`), Mario Kart 8 Deluxe logs in and reaches its
  online menu: the game-server session stays up, and Pia's NAT check gets its answers from both of Nextendo's
  responders (UDP, `MSG_DONTWAIT` as FreeBSD's 0x80).
- 2026-11-13: with the friends calls answered (`pn_friends.c`: `DeclareOpenOnlinePlaySession`, `AddPlayHistory`, ...;
  upstream's failures showed "An error has occurred" at character select), Mario Kart 8 Deluxe joins a Worldwide room and
  finishes a race against other players over Pia's peer-to-peer (about 750 KB each way). Mii heads draw nothing (the
  host's MiiModel stand-in, as offline).
- Open: a Poll that mixes the game's sockets with upstream's eventfds reports nothing for the eventfds; ssl calls on
  a connection after its Destroy fail (`cn_id`), which Mario Kart only does while tearing down.
