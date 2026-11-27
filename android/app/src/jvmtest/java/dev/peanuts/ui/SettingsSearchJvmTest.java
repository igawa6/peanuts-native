// PEANUTS: SettingsSearch on the JVM (peanuts/tests/test_settings_search.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SettingsSearchJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static final List<String> TITLES = new ArrayList<>(), TEXTS = new ArrayList<>();

    /** Every row of the sections in their order, with the value a fresh install shows */
    private static void table() {
        for (SettingsSections.Section s : SettingsSections.ALL)
            for (SettingsSections.Group g : s.groups)
                for (SettingsSections.Row r : g.rows) {
                    SettingsHelp.Help h = SettingsHelp.of(r.title);
                    TITLES.add(r.title);
                    TEXTS.add(SettingsSearch.text(r.title, s.label, g.label, h == null ? null : h.deflt));
                }
    }

    private static List<String> find(String q) {
        List<String> out = new ArrayList<>();
        for (int i : SettingsSearch.find(TITLES, TEXTS, q)) out.add(TITLES.get(i));
        return out;
    }

    public static void main(String[] a) {
        // Advanced's rule: every word, anywhere in the text, case and spaces do not matter
        eq("[]", Arrays.toString(SettingsSearch.words("   ")));
        eq("[fsr, sharp]", Arrays.toString(SettingsSearch.words("  FSR   Sharp ")));
        eq(true, SettingsSearch.matches("FSR sharpness, how strongly", SettingsSearch.words("sharp fsr")));
        eq(false, SettingsSearch.matches("FSR sharpness", SettingsSearch.words("fsr blur")));
        eq(true, SettingsSearch.matches(null, new String[0]));

        table();
        eq("[]", find(""));
        eq("[]", find("  "));
        // the title first: a title that starts with the words, then one that has them, then a row whose text has them
        eq("[Upscaler, Tester settings]", find("upscaler"));
        eq("[Frame rate cap, Dynamic resolution, Overlay]", find("frame rate"));
        eq("[Keys]", find("prod.keys"));   // what it does
        eq("[Console mode, Keys, Firmware, Time of day, Users, Miis]", find("console"));   // a title first, then the section Console, then what the rows do
        eq("[GPU driver, Graphics driver notice, Readiness check, Graphics self-test, ADB control (debug)]", find("driver"));   // PEANUTS: Smooth Experience's part (a title)
        eq("[Free up space, Storage]", find("space"));
        eq("[Miis, Firmware]", find("mii"));   // the firmware brings the Mii parts
        eq("[Upscaler]", find("sgsr"));   // its default
        eq("[]", find("nothing like this"));
        eq("[Rumble]", find("RUMBLE controller"));

        System.out.println("ok " + n);
    }
}
