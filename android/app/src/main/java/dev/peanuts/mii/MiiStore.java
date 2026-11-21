package dev.peanuts.mii;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The player's own Miis (Settings › Miis): each one a name, a create ID and the fields of a console CharInfo (nn::mii, 0x58 bytes), kept in files/mii/miis.tsv. Every
 * save also writes files/mii/database.bin for the host ("PNMII1\0\0", a u32 count, the CharInfo records; dm/host/hle_mii.c reads it as DM_MII_DB), where games list
 * these Miis as the console's own. The ranges are the ones CharInfoRaw::Verify checks; a value outside them is clamped, so a game never refuses a Mii.
 * No Android classes: MiiStoreJvmTest runs it on the JVM.
 */
public final class MiiStore {
    public static final int MAX_MIIS = 100, NAME_MAX = 10, CHARINFO = 0x58;

    /** One editable field: its CharInfo offset (after the create ID and the nickname), its label, the console's range and the editor's section */
    public static final class Field {
        public final String key, label, section; public final int offset, min, max;
        Field(String key, int offset, String label, int min, int max, String section) { this.key = key; this.offset = offset; this.label = label; this.min = min; this.max = max; this.section = section; }
    }

    /** In the editor's order. Offsets are the CharInfo's (hle_mii.c mii_charinfo) */
    public static final List<Field> FIELDS = new ArrayList<>();
    private static void f(String key, int off, String label, int min, int max, String section) { FIELDS.add(new Field(key, off, label, min, max, section)); }
    static {
        f("gender", 40, "Gender", 0, 1, "Profile");
        f("favorite_color", 39, "Favourite colour", 0, 11, "Profile");
        f("height", 41, "Height", 0, 127, "Profile");
        f("build", 42, "Build", 0, 127, "Profile");
        f("faceline_type", 45, "Face shape", 0, 11, "Face");
        f("faceline_color", 46, "Skin colour", 0, 9, "Face");
        f("faceline_wrinkle", 47, "Wrinkles", 0, 11, "Face");
        f("faceline_make", 48, "Make-up", 0, 11, "Face");
        f("hair_type", 49, "Hair style", 0, 131, "Hair");
        f("hair_color", 50, "Hair colour", 0, 99, "Hair");
        f("hair_flip", 51, "Hair parting flipped", 0, 1, "Hair");
        f("eye_type", 52, "Eyes", 0, 59, "Eyes");
        f("eye_color", 53, "Eye colour", 0, 99, "Eyes");
        f("eye_scale", 54, "Eye size", 0, 7, "Eyes");
        f("eye_aspect", 55, "Eye height", 0, 6, "Eyes");
        f("eye_rotate", 56, "Eye rotation", 0, 7, "Eyes");
        f("eye_x", 57, "Eye spacing", 0, 12, "Eyes");
        f("eye_y", 58, "Eye position", 0, 18, "Eyes");
        f("eyebrow_type", 59, "Eyebrows", 0, 23, "Eyebrows");
        f("eyebrow_color", 60, "Eyebrow colour", 0, 99, "Eyebrows");
        f("eyebrow_scale", 61, "Eyebrow size", 0, 8, "Eyebrows");
        f("eyebrow_aspect", 62, "Eyebrow height", 0, 6, "Eyebrows");
        f("eyebrow_rotate", 63, "Eyebrow rotation", 0, 11, "Eyebrows");
        f("eyebrow_x", 64, "Eyebrow spacing", 0, 12, "Eyebrows");
        f("eyebrow_y", 65, "Eyebrow position", 3, 18, "Eyebrows");
        f("nose_type", 66, "Nose", 0, 17, "Nose");
        f("nose_scale", 67, "Nose size", 0, 8, "Nose");
        f("nose_y", 68, "Nose position", 0, 18, "Nose");
        f("mouth_type", 69, "Mouth", 0, 35, "Mouth");
        f("mouth_color", 70, "Mouth colour", 0, 99, "Mouth");
        f("mouth_scale", 71, "Mouth size", 0, 8, "Mouth");
        f("mouth_aspect", 72, "Mouth height", 0, 6, "Mouth");
        f("mouth_y", 73, "Mouth position", 0, 18, "Mouth");
        f("beard_type", 75, "Beard", 0, 5, "Facial hair");
        f("beard_color", 74, "Beard colour", 0, 99, "Facial hair");
        f("mustache_type", 76, "Mustache", 0, 5, "Facial hair");
        f("mustache_scale", 77, "Mustache size", 0, 8, "Facial hair");
        f("mustache_y", 78, "Mustache position", 0, 16, "Facial hair");
        f("glass_type", 79, "Glasses", 0, 19, "Glasses");
        f("glass_color", 80, "Glasses colour", 0, 99, "Glasses");
        f("glass_scale", 81, "Glasses size", 0, 7, "Glasses");
        f("glass_y", 82, "Glasses position", 0, 20, "Glasses");
        f("mole_type", 83, "Mole", 0, 1, "Mole");
        f("mole_scale", 84, "Mole size", 0, 8, "Mole");
        f("mole_x", 85, "Mole across", 0, 16, "Mole");
        f("mole_y", 86, "Mole down", 0, 30, "Mole");
    }
    public static Field field(String key) { for (Field fd : FIELDS) if (fd.key.equals(key)) return fd; return null; }

    /**
     * The console's six default Miis (three men, three women) as CharInfo values, the editor's starting points; the same table as the host's (hle_mii.c mii_defs,
     * from Ryujinx's DefaultMii, MIT), in FIELDS' keys
     */
    static final int[][] DEFAULTS = {
        //  gen fav  h   b  fT fC fW fM  hT hC hF  eT eC eS eA eR eX eY  bT bC bS bA bR bX bY  nT nS nY  mT mC mS mA mY  BT BC MT MS MY  gT gC gS gY  oT oS oX oY
        {0, 4, 64, 64, 0, 4, 0, 0, 68, 8, 0, 2, 8, 4, 3, 4, 2, 12, 6, 8, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
        {0, 5, 64, 64, 0, 0, 0, 0, 55, 6, 0, 2, 12, 4, 3, 4, 2, 12, 6, 6, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
        {0, 0, 64, 64, 0, 1, 0, 0, 33, 1, 0, 2, 8, 4, 3, 4, 2, 12, 6, 1, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
        {1, 2, 64, 64, 0, 2, 0, 0, 24, 8, 0, 4, 8, 4, 3, 3, 2, 12, 0, 8, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
        {1, 6, 64, 64, 0, 0, 0, 0, 14, 7, 0, 4, 13, 4, 3, 3, 2, 12, 0, 7, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
        {1, 7, 64, 64, 0, 0, 0, 0, 12, 1, 0, 4, 8, 4, 3, 3, 2, 12, 0, 1, 4, 3, 6, 2, 10, 1, 4, 9, 23, 19, 4, 3, 13, 0, 8, 0, 4, 10, 0, 8, 4, 10, 0, 4, 2, 20},
    };

    public static final class Mii {
        public String name;
        public final byte[] createId;
        public final Map<String, Integer> v = new LinkedHashMap<>();
        Mii(String name, byte[] createId) { this.name = name; this.createId = createId; }
        public int get(String key) { Integer i = v.get(key); return i == null ? 0 : i; }
        /** Sets a field, clamped to the console's range */
        public void set(String key, int value) { Field fd = field(key); if (fd != null) v.put(key, Math.max(fd.min, Math.min(fd.max, value))); }
        public Mii copy(String newName) { Mii m = new Mii(newName, newCreateId()); m.v.putAll(v); return m; }
    }

    /** A new Mii from default Mii <index> (0 to 5), with a fresh create ID */
    public static Mii fromDefault(int index, String name) {
        Mii m = new Mii(cleanName(name), newCreateId());
        int[] d = DEFAULTS[Math.max(0, Math.min(5, index))];
        for (int i = 0; i < FIELDS.size(); i++) m.set(FIELDS.get(i).key, d[i]);
        return m;
    }

    /** Every field random within its range, the sizes and positions near a default's so the face stays a face */
    public static Mii random(String name, Random r) {
        Mii m = fromDefault(r.nextInt(6), name);
        for (String k : new String[]{"gender", "favorite_color", "faceline_type", "faceline_color", "hair_type", "hair_flip", "eye_type", "eyebrow_type", "nose_type", "mouth_type"}) {
            Field fd = field(k); m.set(k, fd.min + r.nextInt(fd.max - fd.min + 1));
        }
        m.set("hair_color", r.nextInt(8));                                                      // the classic hair colours (common colours 0 to 7)
        m.set("eyebrow_color", m.get("hair_color"));
        m.set("eye_color", 8 + r.nextInt(6));                                                   // the classic eye colours (8 to 13)
        m.set("height", 32 + r.nextInt(64)); m.set("build", 32 + r.nextInt(64));
        return m;
    }

    /** At most 10 characters, not empty (CharInfo's nickname: UTF-16, NUL-terminated, the first character not NUL) */
    public static String cleanName(String s) {
        String t = s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) t = "Mii";
        if (t.length() > NAME_MAX) {
            t = t.substring(0, NAME_MAX);
            if (Character.isHighSurrogate(t.charAt(t.length() - 1))) t = t.substring(0, t.length() - 1);   // never half a surrogate pair
        }
        return t;
    }

    /** The name of a duplicate: the name and " 2", the name cut short so the 2 still fits and the copy reads differently */
    public static String copyName(String name) {
        String base = cleanName(name);
        if (base.length() > NAME_MAX - 2) {
            base = base.substring(0, NAME_MAX - 2);
            if (Character.isHighSurrogate(base.charAt(base.length() - 1))) base = base.substring(0, base.length() - 1);   // never half a surrogate pair
        }
        return cleanName(base + " 2");
    }

    private static final SecureRandom RNG = new SecureRandom();
    static byte[] newCreateId() {   // a UUID version 4, as the mii service makes create IDs
        byte[] b = new byte[16]; RNG.nextBytes(b);
        b[6] = (byte) ((b[6] & 0x0f) | 0x40); b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        return b;
    }

    /** The console's CharInfo for this Mii (0x58 bytes, little-endian): create ID, nickname, then the fields; font region, type and region move 0 */
    public static byte[] charInfo(Mii m) {
        ByteBuffer b = ByteBuffer.allocate(CHARINFO).order(ByteOrder.LITTLE_ENDIAN);
        b.put(m.createId);
        String n = cleanName(m.name);
        for (int i = 0; i < 11; i++) b.putShort(16 + 2 * i, i < n.length() ? (short) n.charAt(i) : 0);
        for (Field fd : FIELDS) b.put(fd.offset, (byte) Math.max(fd.min, Math.min(fd.max, m.get(fd.key))));
        return b.array();
    }

    // ---- files/mii ----
    public static File dir(File filesDir) { return new File(filesDir, "mii"); }
    public static File database(File filesDir) { return new File(dir(filesDir), "database.bin"); }

    /** miis.tsv: one Mii a line, "<create ID hex>\t<name>\t<key>=<value>,..." */
    public static List<Mii> load(File filesDir) {
        List<Mii> out = new ArrayList<>();
        File f = new File(dir(filesDir), "miis.tsv");
        if (!f.isFile()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t", -1);
                if (c.length < 3 || c[0].length() != 32) continue;
                try {
                    byte[] id = new byte[16];
                    for (int i = 0; i < 16; i++) id[i] = (byte) Integer.parseInt(c[0].substring(2 * i, 2 * i + 2), 16);
                    Mii m = new Mii(cleanName(c[1]), id);
                    int[] d = DEFAULTS[0];
                    for (int i = 0; i < FIELDS.size(); i++) m.set(FIELDS.get(i).key, d[i]);   // a field the file lacks keeps the default's value
                    for (String kv : c[2].split(",")) { int e = kv.indexOf('='); if (e > 0) try { m.set(kv.substring(0, e), Integer.parseInt(kv.substring(e + 1))); } catch (NumberFormatException ignored) { } }
                    out.add(m);
                } catch (NumberFormatException e) { continue; }   // one bad create ID skips only its line
                if (out.size() == MAX_MIIS) break;
            }
        } catch (IOException | RuntimeException e) { /* an unreadable file is an empty list */ }
        return out;
    }

    /** Writes miis.tsv and database.bin, each through a temporary file */
    public static void save(File filesDir, List<Mii> miis) throws IOException {
        File d = dir(filesDir);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        StringBuilder sb = new StringBuilder();
        ByteArrayOutputStream db = new ByteArrayOutputStream();
        db.write("PNMII1\0\0".getBytes(StandardCharsets.US_ASCII));
        int n = Math.min(miis.size(), MAX_MIIS);
        db.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array());
        for (int i = 0; i < n; i++) {
            Mii m = miis.get(i);
            StringBuilder hex = new StringBuilder();
            for (byte x : m.createId) hex.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            sb.append(hex).append('\t').append(cleanName(m.name)).append('\t');
            boolean first = true;
            for (Field fd : FIELDS) { if (!first) sb.append(','); first = false; sb.append(fd.key).append('=').append(m.get(fd.key)); }
            sb.append('\n');
            db.write(charInfo(m));
        }
        write(new File(d, "miis.tsv"), sb.toString().getBytes(StandardCharsets.UTF_8));
        write(database(filesDir), db.toByteArray());
    }

    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private MiiStore() {}
}
