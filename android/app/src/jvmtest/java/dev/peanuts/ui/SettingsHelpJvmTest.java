// PEANUTS: SettingsHelp on the JVM (peanuts/tests/test_settings_explain.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.Set;
import java.util.TreeSet;

public final class SettingsHelpJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        // every row of the sections has its explanation, and no explanation is for a row the sections do not have
        Set<String> rows = new TreeSet<>();
        for (SettingsSections.Section s : SettingsSections.ALL)
            for (SettingsSections.Group g : s.groups)
                for (SettingsSections.Row r : g.rows) rows.add(r.title);
        eq(rows, new TreeSet<>(SettingsHelp.titles()));

        for (String t : rows) {
            SettingsHelp.Help h = SettingsHelp.of(t);
            yes(h != null && h.text.length() > 20, t + " says what it does");
            yes(h.text.endsWith("."), t + ": a whole sentence");
            for (String bad : new String[] {"·", "•", " - ", "–", "—", "!", "DM_", "env"})
                yes(!h.text.contains(bad) && (h.deflt == null || !h.deflt.contains(bad)), t + ": no " + bad);
            yes(h.deflt == null || !h.deflt.isEmpty(), t + ": a default has words");
        }

        // defaults as a fresh install shows them; none for a row that only opens or does something
        eq("SGSR 1", SettingsHelp.of("Upscaler").deflt);
        eq("60 fps", SettingsHelp.of("Frame rate cap").deflt);
        eq("System", SettingsHelp.of("GPU driver").deflt);
        eq("On", SettingsHelp.of("Bottom screen").deflt);
        eq("Off", SettingsHelp.of("Swap screens").deflt);
        eq("Stats", SettingsHelp.of("Games without a companion").deflt);   // PeanutsPrefs.bottomScreenInGames: on, with the stats page, by default
        eq("null", SettingsHelp.of("Rescan").deflt);
        eq("null", SettingsHelp.of("Share log").deflt);
        eq("null", SettingsHelp.of("A row nobody wrote"));
        eq("null", SettingsHelp.of(null));

        // the line under the title: the game defaults say they are for every game
        eq("Graphics, for every game", SettingsHelp.place(SettingsSections.section("graphics")));
        eq("Controls, for every game", SettingsHelp.place(SettingsSections.section("controls")));
        eq("Screens", SettingsHelp.place(SettingsSections.section("screens")));
        eq("", SettingsHelp.place(null));

        System.out.println("ok " + n);
    }
}
