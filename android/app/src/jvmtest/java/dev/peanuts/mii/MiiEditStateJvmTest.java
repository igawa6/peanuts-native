// PEANUTS: the Mii editor's edits when Android builds it again (Settings › Users, Miis)
package dev.peanuts.mii;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** MiiEditState on the JVM (peanuts/tests/test_mii_editor.py): the edited Mii and the Undo steps survive the editor's saved state */
public final class MiiEditStateJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) {
        Random r = new Random(7);
        MiiStore.Mii m = MiiStore.random("Zoë", r);
        m.set("hair_type", 131); m.set("eyebrow_y", 3); m.set("mole_y", 30);
        MiiStore.Mii back = MiiEditState.mii(MiiEditState.mii(m));
        check(back != null && back.name.equals("Zoë") && Arrays.equals(back.createId, m.createId), "the Mii keeps its name and create ID");
        check(back != null && back.v.equals(m.v), "every field comes back, the ends of the ranges too");
        check(MiiEditState.mii((byte[]) null) == null && MiiEditState.mii(new byte[12]) == null, "no Mii from missing or short bytes");
        check(MiiEditState.mii(new byte[MiiStore.CHARINFO]) == null, "no Mii from an empty CharInfo");

        ArrayDeque<Map<String, Integer>> undo = new ArrayDeque<>();   // the editor's: push puts the newest first
        for (int i = 0; i < 5; i++) { undo.push(new LinkedHashMap<>(m.v)); m.set("hair_type", i); }
        ArrayDeque<Map<String, Integer>> again = new ArrayDeque<>(MiiEditState.undo(MiiEditState.undo(undo)));
        check(again.size() == 5, "five steps come back");
        check(again.pop().get("hair_type") == 3 && again.pop().get("hair_type") == 2, "the newest step is undone first, as before");
        check(again.peekLast().get("hair_type") == 131, "the oldest step is the Mii as it was first");
        Map<String, Integer> first = MiiEditState.undo(MiiEditState.undo(undo)).get(0);
        check(first.keySet().equals(m.v.keySet()), "a step has every field");

        ArrayDeque<Map<String, Integer>> many = new ArrayDeque<>();
        for (int i = 0; i < 250; i++) { Map<String, Integer> s = new LinkedHashMap<>(m.v); s.put("height", i % 128); many.push(s); }
        byte[] b = MiiEditState.undo(many);
        List<Map<String, Integer>> kept = MiiEditState.undo(b);
        check(kept.size() == MiiEditState.UNDO_KEPT && b.length == MiiEditState.UNDO_KEPT * MiiStore.FIELDS.size(), "at most " + MiiEditState.UNDO_KEPT + " steps, a byte a field");
        check(kept.get(0).get("height") == 249 % 128, "the newest steps are the ones kept");

        check(MiiEditState.undo((byte[]) null).isEmpty() && MiiEditState.undo(new byte[MiiStore.FIELDS.size() + 1]).isEmpty(), "no steps from missing or broken bytes");
        byte[] wild = new byte[MiiStore.FIELDS.size()];
        Arrays.fill(wild, (byte) 0xff);
        Map<String, Integer> clamped = MiiEditState.undo(wild).get(0);
        boolean inRange = true;
        for (MiiStore.Field fd : MiiStore.FIELDS) { int v = clamped.get(fd.key); if (v < fd.min || v > fd.max) inRange = false; }
        check(inRange, "a step's values stay in their fields' ranges");

        System.out.println(fails == 0 ? "PASS" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
