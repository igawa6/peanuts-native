package dev.peanuts.online;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The Nextendo Network account (dm/peanuts/net/README.md): the sign-in in the browser (OAuth 2.0 with PKCE; the password is only ever typed on nextendo.network),
 * and the 24-hour game token the game's online account presents (PN_NEXTENDO_PID / PN_NEXTENDO_TOKEN, handed to the host for one run by {@link #envLines}).
 * The flow is Lemon's (GPL-3.0-or-later): authorize with a loopback redirect, /api/oauth/token, /api/nex-token. The client is the builder's own application from
 * nextendo.network/developers (a public PKCE client): -PnxNextendoClientId and -PnxNextendoRedirectPort, the port of the redirect it was registered with
 * (http://127.0.0.1:<port>/callback; a registered client's redirect must match exactly). A build without them says so instead of signing in.
 *
 * The refresh token and the game token are kept in this app's own preferences, encrypted with a key in the Android Keystore. They and the PID are credentials:
 * never logged, never shown.
 */
public final class Nextendo {
    static final String TAG = "peanuts-online";
    private static final String CLIENT_ID = dev.nxnative.android.BuildConfig.NEXTENDO_CLIENT_ID;   // public (PKCE, no secret); empty: no application in this build
    private static final String BASE = "https://nextendo.network";
    private static final String SCOPES = "identity friends presence game.matchmaking";
    private static final int PORT = dev.nxnative.android.BuildConfig.NEXTENDO_REDIRECT_PORT;
    private static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
    private static final int SIGN_IN_TIMEOUT_MS = 5 * 60 * 1000, HTTP_TIMEOUT_MS = 20_000;
    private static final long REFRESH_BELOW_MS = 12 * 3600_000L, USABLE_ABOVE_MS = 10 * 60_000L;
    private static final String PREFS = "peanuts_nextendo", K_ONLINE = "online", K_USER = "username", K_REFRESH = "refresh", K_GAME = "game", KEY_ALIAS = "peanuts_nextendo";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final List<Runnable> watchers = new ArrayList<>();

    private Nextendo() {}

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- what the settings show ----------
    public static boolean online(Context c) { return prefs(c).getBoolean(K_ONLINE, false); }
    public static void online(Context c, boolean on) { prefs(c).edit().putBoolean(K_ONLINE, on).apply(); changed(); }
    /** The signed-in account's username, or null when signed out */
    public static String username(Context c) { return prefs(c).contains(K_REFRESH) ? prefs(c).getString(K_USER, "") : null; }
    /** When the game token runs out (ms since the epoch), 0 when there is none */
    public static long tokenExpiry(Context c) { JSONObject g = gameToken(c); return g == null ? 0 : g.optLong("exp"); }
    public static boolean working() { return busy.get(); }
    /** Whether this build has a Nextendo application to sign in with (-PnxNextendoClientId, -PnxNextendoRedirectPort) */
    public static boolean configured() { return !CLIENT_ID.isEmpty() && PORT > 0 && PORT < 65536; }
    /** Called on the UI thread whenever the account changes (the settings rows) */
    public static void watch(Runnable r) { synchronized (watchers) { watchers.clear(); watchers.add(r); } }
    private static void changed() { UI.post(() -> { synchronized (watchers) { for (Runnable r : watchers) r.run(); } }); }

    // ---------- the game's environment for one run (GameActivity, on the UI thread: never the network here) ----------
    public static List<String> envLines(Context c) {
        List<String> out = new ArrayList<>();
        if (!online(c)) return out;
        out.add("PN_ONLINE=1");
        JSONObject g = gameToken(c);
        if (g != null && g.optLong("exp") - System.currentTimeMillis() > USABLE_ABOVE_MS) {
            out.add("PN_NEXTENDO_PID=" + g.optLong("pid"));
            out.add("PN_NEXTENDO_TOKEN=" + g.optString("token"));
        } else if (username(c) != null) {
            Log.w(TAG, "the game token has run out: this run goes online without the account; a new one is on its way");
            refreshIfDue(c);
        }
        return out;
    }

    // ---------- sign in / out ----------
    /** Opens the sign-in page in the browser and waits for its answer on the loopback port; done gets null or why not, on the UI thread */
    public static void signIn(Activity a, Consumer<String> done) {
        if (!configured()) { done.accept("this build has no Nextendo application (build it with -PnxNextendoClientId and -PnxNextendoRedirectPort)"); return; }
        if (!busy.compareAndSet(false, true)) { done.accept("a sign-in is already running"); return; }
        changed();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            String r;
            try { r = runSignIn(a, app); } catch (Exception e) { r = e.getClass().getSimpleName(); Log.w(TAG, "sign-in: " + e); }
            busy.set(false); changed();
            String why = r;
            UI.post(() -> done.accept(why));
        }, "nextendo-signin").start();
    }

    public static void signOut(Context c) {
        prefs(c).edit().remove(K_REFRESH).remove(K_GAME).remove(K_USER).apply();
        changed();
    }

    private static String runSignIn(Activity a, Context app) throws Exception {
        String verifier = random(32), state = random(24);
        String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(SIGN_IN_TIMEOUT_MS);
            Uri url = Uri.parse(BASE + "/api/oauth/authorize").buildUpon()
                    .appendQueryParameter("response_type", "code").appendQueryParameter("client_id", CLIENT_ID)
                    .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("scope", SCOPES)
                    .appendQueryParameter("state", state).appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256").build();
            UI.post(() -> a.startActivity(new Intent(Intent.ACTION_VIEW, url)));
            while (true) {
                Socket s;
                try { s = server.accept(); } catch (SocketTimeoutException e) { return "no answer from the browser within 5 minutes"; }
                Uri cb;
                try { cb = readCallback(s, state); } finally { s.close(); }
                if (cb == null) continue;   // /favicon.ico and the like
                if (cb.getQueryParameter("error") != null) return "Nextendo refused: " + cb.getQueryParameter("error");
                if (!state.equals(cb.getQueryParameter("state"))) return "the answer did not match this sign-in";
                String code = cb.getQueryParameter("code");
                if (code == null) return "no code in Nextendo's answer";
                JSONObject t = post("grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(CLIENT_ID)
                        + "&redirect_uri=" + enc(REDIRECT) + "&code_verifier=" + enc(verifier));
                if (t == null || t.optString("refresh_token").isEmpty() || t.optString("access_token").isEmpty()) return "Nextendo gave no tokens";
                store(app, K_REFRESH, t.getString("refresh_token"));
                return fetchGameToken(app, t.getString("access_token")) ? null : "Nextendo gave no game token";
            }
        } catch (java.net.BindException e) {
            return "port " + PORT + " is in use (another sign-in?)";
        }
    }

    /** The callback's URI (null for any other request), after answering the browser with a short page */
    private static Uri readCallback(Socket s, String state) throws Exception {
        s.setSoTimeout(10_000);
        String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        String target = line == null ? null : line.split(" ").length > 1 ? line.split(" ")[1] : null;
        Uri u = target == null ? null : Uri.parse("http://127.0.0.1" + target);
        boolean cb = u != null && "/callback".equals(u.getPath()), ok = cb && u.getQueryParameter("code") != null && state.equals(u.getQueryParameter("state"));
        String page = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding:40px\">"
                + (ok ? "<h2>Peanuts Native: signed in</h2><p>You can go back to Peanuts Native now.</p>" : cb ? "<h2>Peanuts Native: sign-in not completed</h2>" : "")
                + "</body></html>";
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(((cb ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        o.write(body); o.flush();
        return cb ? u : null;
    }

    // ---------- the game token ----------
    /** A new game token in the background when the one kept runs out within 12 hours (Home, Settings, a run that found it out of date) */
    public static void refreshIfDue(Context c) {
        Context app = c.getApplicationContext();
        if (username(app) == null || tokenExpiry(app) - System.currentTimeMillis() > REFRESH_BELOW_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        changed();
        new Thread(() -> {
            try {
                String rt = load(app, K_REFRESH);
                JSONObject t = rt == null ? null : post("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(CLIENT_ID));
                if (t != null) {
                    if (!t.optString("refresh_token").isEmpty()) store(app, K_REFRESH, t.getString("refresh_token"));
                    if (!t.optString("access_token").isEmpty()) fetchGameToken(app, t.getString("access_token"));
                }
            } catch (Exception e) { Log.w(TAG, "refresh: " + e.getClass().getSimpleName()); }
            busy.set(false); changed();
        }, "nextendo-refresh").start();
    }

    private static boolean fetchGameToken(Context app, String access) throws Exception {
        HttpURLConnection h = open("/api/nex-token");
        h.setRequestProperty("Authorization", "Bearer " + access);
        JSONObject j = answer(h, "game token");
        if (j == null || j.optString("nex_token").isEmpty() || j.optLong("pid") == 0) return false;
        JSONObject g = new JSONObject().put("pid", j.getLong("pid")).put("token", j.getString("nex_token"))
                .put("exp", System.currentTimeMillis() + j.optLong("expires_in", 24 * 3600) * 1000);
        store(app, K_GAME, g.toString());
        prefs(app).edit().putString(K_USER, j.optString("username")).apply();
        Log.i(TAG, "game token received, valid " + j.optLong("expires_in", 24 * 3600) / 3600 + " h");
        changed();
        return true;
    }

    private static JSONObject gameToken(Context c) {
        try { String s = load(c, K_GAME); return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    // ---------- HTTP ----------
    private static HttpURLConnection open(String path) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(BASE + path).openConnection();
        h.setConnectTimeout(HTTP_TIMEOUT_MS); h.setReadTimeout(HTTP_TIMEOUT_MS);
        h.setRequestProperty("X-Nextendo-Client-Id", CLIENT_ID);
        h.setRequestProperty("Accept", "application/json");
        return h;
    }
    private static JSONObject post(String form) throws Exception {
        HttpURLConnection h = open("/api/oauth/token");
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = h.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
        return answer(h, "token");
    }
    private static JSONObject answer(HttpURLConnection h, String what) throws Exception {
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        h.disconnect();
        if (code >= 400) {
            Log.w(TAG, what + " request refused (HTTP " + code + ")");   // the body is not logged: an error can echo what was sent
            return null;
        }
        return new JSONObject(b.toString("UTF-8"));
    }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    // ---------- storage: AES-GCM with a key in the Android Keystore ----------
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    private static void store(Context c, String k, String v) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = ci.getIV(), ct = ci.doFinal(v.getBytes(StandardCharsets.UTF_8));
        prefs(c).edit().putString(k, Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply();
    }
    private static String load(Context c, String k) {
        String s = prefs(c).getString(k, null);
        if (s == null || s.indexOf(':') < 0) return null;
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(s.substring(0, s.indexOf(':')), Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(s.substring(s.indexOf(':') + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {   // the key is gone (app data restored on another device): signed out
            Log.w(TAG, "stored account unreadable: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String random(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return b64url(b); }
    private static String b64url(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
}
