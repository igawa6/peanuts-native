package dev.peanuts.users;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each user's profile picture, as a reference and never as pixels: one of the player's Miis (by its create ID, so editing the Mii changes the picture) or one of
 * the firmware's own pictures (by its file name), and a background colour. Kept in files/users/pictures.tsv, a line a user: "uid TAB source TAB background",
 * source "" (none yet), "mii:" and 32 hex digits, or "fw:" and a plain file name. The picture is drawn when it is shown. No Android classes: UsersJvmTest checks it.
 */
public final class UserPictures {
    private UserPictures() {}

    /** The backgrounds a picture can have, darker tones that sit on the app's brown */
    public static final int[] BACKGROUNDS = {0x2E4A6B, 0x2F4228, 0x5A2A37, 0x5A4016, 0x3B3550, 0x3A2D17, 0x1F4A4A, 0x4A2F20};
    public static final String[] BACKGROUND_NAMES = {"Blue", "Green", "Rose", "Amber", "Violet", "Brown", "Teal", "Rust"};

    public static final class Picture {
        public final String source; public final int background;
        public Picture(String source, int background) { this.source = clean(source); this.background = Math.floorMod(background, BACKGROUNDS.length); }
        public boolean none() { return source.isEmpty(); }
        /** The Mii's create ID (32 hex digits), or null */
        public String mii() { return source.startsWith("mii:") ? source.substring(4) : null; }
        /** The firmware picture's file name, or null */
        public String firmware() { return source.startsWith("fw:") ? source.substring(3) : null; }
        public int color() { return BACKGROUNDS[background]; }
        public Picture withBackground(int b) { return new Picture(source, b); }
    }

    public static Picture mii(String createId, int background) { return new Picture("mii:" + createId, background); }
    public static Picture firmware(String name, int background) { return new Picture("fw:" + name, background); }

    /** A source as it may be kept: "", mii: with 32 lower-case hex digits, fw: with a plain name (no separator, not . or ..); anything else is "" */
    static String clean(String s) {
        if (s == null) return "";
        if (s.startsWith("mii:") && s.substring(4).matches("[0-9a-f]{32}")) return s;
        if (s.startsWith("fw:")) {
            String n = s.substring(3);
            if (!n.isEmpty() && n.length() <= 64 && !n.equals(".") && !n.equals("..") && n.indexOf('/') < 0 && n.indexOf('\\') < 0 && n.indexOf('\t') < 0 && n.indexOf('\n') < 0) return s;
        }
        return "";
    }

    static File file(File filesDir) { return new File(new File(filesDir, "users"), "pictures.tsv"); }

    static Map<String, Picture> parse(String text) {
        Map<String, Picture> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 3 || !c[0].matches("[0-9a-f]{32}")) continue;
            int bg;
            try { bg = Integer.parseInt(c[2].trim()); } catch (NumberFormatException e) { bg = 0; }
            out.put(c[0], new Picture(c[1], bg));
        }
        return out;
    }

    static String format(Map<String, Picture> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Picture> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue().source).append('\t').append(e.getValue().background).append('\n');
        return b.toString();
    }

    static Map<String, Picture> load(File filesDir) {
        File f = file(filesDir);
        if (!f.isFile()) return new LinkedHashMap<>();
        try { return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException e) { return new LinkedHashMap<>(); }
    }

    /** User uid's picture; a user without one gets none on the background of its place in the list (position 0 to 7), so two new users differ */
    public static Picture of(File filesDir, String uid, int position) {
        Picture p = load(filesDir).get(uid);
        return p != null ? p : new Picture("", position);
    }

    public static void set(File filesDir, String uid, Picture p) throws IOException {
        Map<String, Picture> m = load(filesDir);
        m.put(uid, p);
        store(filesDir, m);
    }

    /** A removed user's picture goes with it */
    public static void remove(File filesDir, String uid) throws IOException {
        Map<String, Picture> m = load(filesDir);
        if (m.remove(uid) != null) store(filesDir, m);
    }

    /** A Mii deleted from the collection: users that showed it keep their background and show none until another picture is picked */
    public static void forgetMii(File filesDir, String createId) throws IOException {
        Map<String, Picture> m = load(filesDir);
        boolean changed = false;
        for (Map.Entry<String, Picture> e : m.entrySet())
            if (createId.equals(e.getValue().mii())) { e.setValue(new Picture("", e.getValue().background)); changed = true; }
        if (changed) store(filesDir, m);
    }

    private static void store(File filesDir, Map<String, Picture> m) throws IOException {
        File f = file(filesDir), d = f.getParentFile();
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), format(m).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
