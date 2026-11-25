package dev.peanuts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The companion catalog: the Eden Duo companions' GitHub releases, whose assets are `<TITLEID>-<Name>-<version>.dsmod.zip` (older ones `<TITLEID>.dsmod.zip`); every
 * release is kept (found in review: same GitHub Releases API as today, just stop discarding older releases), newest version first per title, so a game's companion
 * can be installed, switched between versions or rolled back (dev.peanuts.ui.CompanionVersions). Names come from the repository's catalog.json (title id → game name).
 * Same packages, same format as Eden Duo (PACKAGE_FORMAT.md).
 */
public final class Catalog {
    public static final String RELEASES = "https://api.github.com/repos/igawa6/eden-duo-companions/releases?per_page=100";
    public static final String NAMES = "https://raw.githubusercontent.com/igawa6/eden-duo-companions/main/catalog.json";
    private static final Pattern ASSET = CompanionFile.ASSET;   // one rule for the catalog's asset names and a picked file's name
    private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:([0-9A-Fa-f]{64})");

    /** One release of one title's companion. sha256/sha256Url: see Installer.verify - at most one of them is set, both null means no digest was published anywhere */
    public static final class Item {
        public final String titleId, packageName, version, url, tag, gameName, date, notes, sha256, sha256Url;
        public final long size;
        Item(String titleId, String packageName, String version, String url, String tag, long size, String gameName, String date, String notes, String sha256, String sha256Url) {
            this.titleId = titleId; this.packageName = packageName; this.version = version; this.url = url; this.tag = tag; this.size = size; this.gameName = gameName;
            this.date = date == null ? "" : date; this.notes = notes == null ? "" : notes; this.sha256 = sha256; this.sha256Url = sha256Url;
        }
    }

    private Catalog() {}

    /** Every title with a companion, newest release only, newest first; network, call off the main thread. Kept for existing callers; see fetchVersions() for the history */
    public static List<Item> fetch() throws IOException {
        List<Item> out = new ArrayList<>();
        for (List<Item> versions : fetchVersions().values()) if (!versions.isEmpty()) out.add(versions.get(0));
        return out;
    }

    /** Every release of every title with a companion, newest first per title (dev.peanuts.ui.CompanionVersions' version history); network, call off the main thread */
    public static Map<String, List<Item>> fetchVersions() throws IOException {
        Map<String, String> names = new HashMap<>();
        try {
            JSONArray a = new JSONArray(get(NAMES));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); names.put(o.optString("titleId").toUpperCase(Locale.ROOT), o.optString("name")); }
        } catch (Exception e) { android.util.Log.w(Companion.TAG, "catalog names: " + e); }
        Map<String, List<Item>> out = new LinkedHashMap<>();
        try {
            for (JSONObject r : allReleases()) {   // every page, newest first
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                JSONArray assets = r.optJSONArray("assets");
                if (assets == null) continue;
                Map<String, JSONObject> byName = new HashMap<>();
                for (int j = 0; j < assets.length(); j++) { JSONObject as = assets.getJSONObject(j); byName.put(as.optString("name"), as); }
                String date = r.optString("published_at", r.optString("created_at", ""));
                String notes = r.optString("body", "");
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject as = assets.getJSONObject(j);
                    Matcher m = ASSET.matcher(as.optString("name"));
                    if (!m.matches()) continue;
                    String title = m.group(1).toUpperCase(Locale.ROOT);
                    String pkg = m.group(2) != null ? m.group(2) : "DualScreen-" + title;
                    String ver = m.group(3) != null ? m.group(3) : r.optString("tag_name").replaceAll(".*-(\\d[\\d.]*)$", "$1");
                    if (!ver.matches("[0-9A-Za-z.\\-]{1,32}") || ver.contains("..")) continue;   // the version names a folder (Installer.versionDir): nothing but a plain version string
                    String sha256 = digestOf(as);
                    String sha256Url = null;
                    if (sha256 == null) {
                        JSONObject sib = byName.get(as.optString("name") + ".sha256");
                        if (sib != null) sha256Url = sib.optString("browser_download_url");
                    }
                    Item it = new Item(title, pkg, ver, as.optString("browser_download_url"), r.optString("tag_name"), as.optLong("size"), names.getOrDefault(title, title), date, notes, sha256, sha256Url);
                    out.computeIfAbsent(title, k -> new ArrayList<>()).add(it);
                }
            }
        } catch (org.json.JSONException e) { throw new IOException("releases: " + e.getMessage()); }
        for (List<Item> versions : out.values()) {
            versions.sort((a, b) -> compareVersions(b.version, a.version));   // newest first, independent of release listing order
        }
        return out;
    }

    /** Every release across every page of the Releases API, following the "Link: ...; rel=\"next\"" header rather than relying on per_page=100 holding everything
     * forever (today's 46 releases fit in one page; this stops silently dropping the oldest ones once that is no longer true). Capped at 20 pages (2000
     * releases) against a malformed/looping Link header. */
    private static List<JSONObject> allReleases() throws IOException {
        List<JSONObject> out = new ArrayList<>();
        try {
            String url = RELEASES;
            for (int page = 0; url != null && page < 20; page++) {
                String[] next = new String[1];
                JSONArray a = new JSONArray(getPage(url, next));
                for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
                url = next[0];
            }
        } catch (org.json.JSONException e) { throw new IOException("releases: " + e.getMessage()); }
        return out;
    }

    private static String getPage(String url, String[] nextUrlOut) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            nextUrlOut[0] = nextLink(c.getHeaderField("Link"));
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** The "rel=\"next\"" URL out of a GitHub "Link" response header (RFC 8288), or null without one (the last/only page) */
    private static String nextLink(String linkHeader) {
        if (linkHeader == null) return null;
        for (String part : linkHeader.split(",")) {
            String[] bits = part.split(";");
            if (bits.length < 2) continue;
            String u = bits[0].trim();
            if (!u.startsWith("<") || !u.endsWith(">")) continue;
            for (int i = 1; i < bits.length; i++) if (bits[i].trim().replace(" ", "").equals("rel=\"next\"")) return u.substring(1, u.length() - 1);
        }
        return null;
    }

    /** GitHub's optional asset "digest" field, "sha256:<hex>", when present (no extra network call) */
    private static String digestOf(JSONObject asset) {
        String d = asset.optString("digest", null);
        if (d == null) return null;
        Matcher m = SHA256_DIGEST.matcher(d);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** CompanionFile.compareVersions: one rule for the catalog and a companion file */
    public static int compareVersions(String a, String b) { return CompanionFile.compareVersions(a, b); }

    public static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
