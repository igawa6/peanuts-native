package dev.peanuts.mii;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Mii editor keeps when Android builds it again (the device turned, dark mode switched, the app's process ended in the background): the Mii as it is
 * being edited, as a CharInfo (create ID, name and every field), and the Undo steps, a byte a field in MiiStore.FIELDS' order, for the activity's saved state.
 * No Android classes: MiiEditStateJvmTest checks it.
 */
final class MiiEditState {
    private MiiEditState() {}

    /** At most this many Undo steps are kept, the newest: the saved state stays small (Android refuses a large one) */
    static final int UNDO_KEPT = 100;

    static byte[] mii(MiiStore.Mii m) { return MiiStore.charInfo(m); }

    /** The Mii back from {@link #mii(MiiStore.Mii)}; null when the bytes are not a CharInfo */
    static MiiStore.Mii mii(byte[] b) { return b != null && b.length == MiiStore.CHARINFO ? MiiImport.fromCharInfo(b, 0) : null; }

    /** The Undo steps in the order given (the editor's: newest first), at most UNDO_KEPT of them */
    static byte[] undo(Iterable<Map<String, Integer>> steps) {
        int n = MiiStore.FIELDS.size(), k = 0;
        List<Map<String, Integer>> kept = new ArrayList<>();
        for (Map<String, Integer> s : steps) { if (k++ == UNDO_KEPT) break; kept.add(s); }
        byte[] out = new byte[kept.size() * n];
        for (int i = 0; i < kept.size(); i++)
            for (int f = 0; f < n; f++) {
                Integer v = kept.get(i).get(MiiStore.FIELDS.get(f).key);
                out[i * n + f] = (byte) (v == null ? MiiStore.FIELDS.get(f).min : v);
            }
        return out;
    }

    /** The steps back, in the order they were given; none when the bytes are missing or not whole steps. Each value is clamped to its field's range */
    static List<Map<String, Integer>> undo(byte[] b) {
        List<Map<String, Integer>> out = new ArrayList<>();
        int n = MiiStore.FIELDS.size();
        if (b == null || b.length % n != 0) return out;
        for (int at = 0; at < b.length; at += n) {
            Map<String, Integer> s = new LinkedHashMap<>();
            for (int f = 0; f < n; f++) {
                MiiStore.Field fd = MiiStore.FIELDS.get(f);
                s.put(fd.key, Math.max(fd.min, Math.min(fd.max, b[at + f] & 0xff)));
            }
            out.add(s);
        }
        return out;
    }
}
