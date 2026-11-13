#!/usr/bin/env python3
"""Sign in to Nextendo Network on this computer and hand the account to Peanuts Native on the phone (for testing
dm/peanuts/net until the launcher has its own sign-in).

The sign-in is OAuth 2.0 with PKCE in your own browser: the password is only ever typed on nextendo.network. The
script then asks Nextendo for the 24-hour game token and writes PN_NEXTENDO_PID / PN_NEXTENDO_TOKEN (and PN_ONLINE=1)
into the app's env.txt on the phone through adb, keeping that file's other lines. The PID and the token are
credentials: they are never printed, and they reach adb on its standard input, not on a command line.

Usage: peanuts/scripts/nextendo-signin.py --client-id nxc_... --port <redirect port> [--package dev.nxnative.android.peanutsnex] [--scope "..."]
The client is your own application from nextendo.network/developers (public, PKCE), registered with the redirect http://127.0.0.1:<port>/callback
(also NEXTENDO_CLIENT_ID / NEXTENDO_REDIRECT_PORT in the environment).
Run it again when the game token runs out (24 hours).
"""
import argparse, base64, hashlib, http.server, json, os, secrets, subprocess, sys, threading, urllib.error, urllib.parse, urllib.request, webbrowser

CLIENT_ID = ""   # your application's public client ID (--client-id)
PORT = 0       # the port of its registered redirect (--port)
BASE = "https://nextendo.network"
SCOPES = "identity friends presence game.matchmaking"
KEYS = ("PN_NEXTENDO_PID", "PN_NEXTENDO_TOKEN")


def b64url(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def fail(msg):
    print("[RED] " + msg, file=sys.stderr); sys.exit(1)


def browser_sign_in(scope):
    global PORT
    """The authorization code from the browser, with the redirect URI and the PKCE verifier it was asked with."""
    verifier, state = b64url(secrets.token_bytes(32)), b64url(secrets.token_bytes(24))
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    got = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            u = urllib.parse.urlparse(self.path); q = dict(urllib.parse.parse_qsl(u.query))
            if u.path != "/callback":   # the browser also asks for /favicon.ico and the like
                self.send_response(404); self.end_headers(); return
            ok = "code" in q and q.get("state") == state
            got.update(q, ok=ok)
            page = ("<h2>Peanuts Native: signed in</h2><p>You can close this tab.</p>" if ok else "<h2>Peanuts Native: sign-in not completed</h2>")
            body = ("<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;text-align:center;padding:40px'>" + page).encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
            threading.Thread(target=self.server.shutdown, daemon=True).start()

        def log_message(self, *a): pass   # the request line carries the code

    srv = http.server.HTTPServer(("127.0.0.1", PORT), Callback)   # the port of the redirect the application was registered with
    redirect = "http://127.0.0.1:%d/callback" % srv.server_port
    url = BASE + "/api/oauth/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": CLIENT_ID, "redirect_uri": redirect, "scope": scope,
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256"})
    print("Opening the Nextendo sign-in page in your browser. If it does not open, visit:\n  " + url)
    webbrowser.open(url)
    timer = threading.Timer(300, srv.shutdown); timer.start()
    srv.serve_forever(); timer.cancel(); srv.server_close()
    if not got: fail("no answer from the browser within 5 minutes")
    if "error" in got: fail("Nextendo refused the sign-in: " + got["error"] + (" (" + got["error_description"] + ")" if "error_description" in got else ""))
    if not got.get("ok"): fail("the sign-in answer did not match this request (state)")
    return got["code"], redirect, verifier


def call(url, form=None, bearer=None):
    headers = {"X-Nextendo-Client-Id": CLIENT_ID, "Accept": "application/json", "User-Agent": "PeanutsNative-signin"}
    if bearer: headers["Authorization"] = "Bearer " + bearer
    data = urllib.parse.urlencode(form).encode() if form else None
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:200]
        fail("%s answered HTTP %d: %s" % (urllib.parse.urlparse(url).path, e.code, detail))
    except urllib.error.URLError as e:
        fail("cannot reach %s: %s" % (BASE, e.reason))


def adb(args, stdin=None):
    try:
        return subprocess.run(["adb"] + args, input=stdin, capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        fail("adb not found: install the Android platform tools and put them on PATH")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--package", default="dev.nxnative.android.peanutsnex", help="the app whose env.txt gets the account (default: the Peanuts NEX development build)")
    ap.add_argument("--scope", default=SCOPES, help="OAuth scopes to ask for (default: %(default)s)")
    ap.add_argument("--client-id", default=os.environ.get("NEXTENDO_CLIENT_ID", ""), help="your application's public client ID (nextendo.network/developers)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("NEXTENDO_REDIRECT_PORT", "0")), help="the port of its redirect http://127.0.0.1:<port>/callback")
    a = ap.parse_args()
    global CLIENT_ID, PORT
    CLIENT_ID, PORT = a.client_id, a.port
    if not CLIENT_ID or not 0 < PORT < 65536: fail("give your Nextendo application with --client-id and --port (nextendo.network/developers)")

    if "device" not in adb(["get-state"]).stdout: fail("no phone on adb: connect it with USB debugging on")
    code, redirect, verifier = browser_sign_in(a.scope)
    tokens = call(BASE + "/api/oauth/token", {"grant_type": "authorization_code", "code": code, "client_id": CLIENT_ID, "redirect_uri": redirect, "code_verifier": verifier})
    if not tokens.get("access_token"): fail("no access token in Nextendo's answer")
    game = call(BASE + "/api/nex-token", bearer=tokens["access_token"])
    pid, nex = game.get("pid"), game.get("nex_token")
    if not pid or not nex: fail("Nextendo gave no game token (its answer has: %s)" % ", ".join(sorted(game)))

    env = "/sdcard/Android/data/%s/files/env.txt" % a.package
    old = adb(["shell", "cat " + env + " 2>/dev/null"]).stdout.splitlines()
    keep = [l for l in old if l.strip() and not l.startswith(KEYS + ("PN_ONLINE=",))]
    text = "\n".join(keep + ["PN_ONLINE=1", "PN_NEXTENDO_PID=%d" % int(pid), "PN_NEXTENDO_TOKEN=" + nex]) + "\n"
    w = adb(["shell", "cat > " + env + " && chmod 666 " + env], stdin=text)
    if w.returncode: fail("could not write %s: %s" % (env, (w.stderr or w.stdout).strip()))
    hours = int(game.get("expires_in", 86400)) // 3600
    print("Signed in as %s. The account is on the phone (%s), valid for about %d hours; the app takes it at the game's next start."
          % (game.get("username") or "(no username)", a.package, hours))


if __name__ == "__main__":
    main()
