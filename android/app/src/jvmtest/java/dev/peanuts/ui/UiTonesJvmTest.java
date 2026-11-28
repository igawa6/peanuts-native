// PEANUTS: UiTones on the JVM (peanuts/tests/test_ui_sounds.py); prints "ok <n>" or exits 1 naming the first case that differs
package dev.peanuts.ui;

public final class UiTonesJvmTest {
    private static int n;

    static void check(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { System.out.println("FAIL " + what + ": got " + got + ", want " + want); System.exit(1); }
    }

    static String notes(UiTones t) {
        StringBuilder s = new StringBuilder();
        for (float f : t.notes) s.append(s.length() == 0 ? "" : ",").append(Math.round(f));
        return s + " " + t.ms + "ms " + t.level;
    }

    public static void main(String[] args) {
        // the in-game menu's five tones, as MenuFeel made them: the sound the menus already have
        check("move", notes(UiTones.MOVE), "2093 14ms 0.1");
        check("tab", notes(UiTones.TAB), "1568 22ms 0.12");
        check("select", notes(UiTones.SELECT), "1047,1568 34ms 0.13");
        check("open", notes(UiTones.OPEN), "784,1175 40ms 0.11");
        check("back", notes(UiTones.BACK), "1175,784 34ms 0.11");
        check("nine tones", UiTones.values().length, 9);
        for (UiTones t : UiTones.values()) {
            short[] p = t.pcm();
            int per = UiTones.RATE * t.ms / 1000;
            check(t + ": its length", p.length, per * t.notes.length);
            int peak = 0;
            for (short s : p) peak = Math.max(peak, Math.abs(s));
            check(t + ": soft, never above its level", peak <= Math.round(t.level * Short.MAX_VALUE), true);
            check(t + ": heard", peak > t.level * Short.MAX_VALUE * 0.3, true);
            for (int k = 0; k < t.notes.length; k++) {   // each note rises from silence and fades out: no click at its edges
                check(t + " note " + k + ": starts at zero", p[k * per], (short) 0);
                check(t + " note " + k + ": faded at its end", Math.abs(p[k * per + per - 1]) < t.level * Short.MAX_VALUE * 0.02, true);
            }
            check(t + ": short, a console's tick not a tune", t.ms * t.notes.length <= 200, true);
        }
        check("a step is the shortest sound", UiTones.MOVE.ms * UiTones.MOVE.notes.length <= UiTones.EDGE.ms, true);
        check("light: a step and an edge", UiTones.MOVE.light() + "," + UiTones.EDGE.light() + "," + UiTones.SELECT.light() + "," + UiTones.HEAVY.light(), "true,true,false,false");
        check("Play goes up", UiTones.HEAVY.notes[0] < UiTones.HEAVY.notes[2], true);
        check("a refusal goes down", UiTones.REJECT.notes[0] > UiTones.REJECT.notes[1], true);
        System.out.println("ok " + n);
    }
}
