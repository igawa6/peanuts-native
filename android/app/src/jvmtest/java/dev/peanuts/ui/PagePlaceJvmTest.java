// PEANUTS: PlacePick on the JVM (peanuts/tests/test_page_place.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.Arrays;
import java.util.List;

public final class PagePlaceJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    /** A rebuilt page's rows: the row a controller was on is found again */
    private static int again(List<String> before, int focused, List<String> after) {
        return PlacePick.pick(after, before.get(focused), PlacePick.nth(before, focused), focused);
    }

    public static void main(String[] a) {
        List<String> settings = Arrays.asList("Game folder", "Build folders", "Keys", "Check for updates", "Overlay", "Screen rotation", "Upscaler",
                "FSR sharpness", "GPU driver", "Advanced", "Share log", "Back up saves");

        // the same page built again: the same row
        for (int i = 0; i < settings.size(); i++) eq(i, again(settings, i, settings));

        // a row above it hidden in the new build (Upscaler set to something else hides FSR sharpness): still the same row, by its words
        List<String> fewer = Arrays.asList("Game folder", "Build folders", "Keys", "Check for updates", "Overlay", "Screen rotation", "Upscaler",
                "GPU driver", "Advanced", "Share log", "Back up saves");
        eq(fewer.indexOf("GPU driver"), again(settings, settings.indexOf("GPU driver"), fewer));
        eq(fewer.indexOf("Advanced"), again(settings, settings.indexOf("Advanced"), fewer));

        // a row shown above it in the new build
        List<String> more = Arrays.asList("Allow", "Game folder", "Build folders", "Keys", "Check for updates", "Overlay", "Screen rotation", "Upscaler",
                "FSR sharpness", "GPU driver", "Advanced", "Share log", "Back up saves");
        eq(more.indexOf("Share log"), again(settings, settings.indexOf("Share log"), more));

        // rows with the same words (chips "Auto" on the Advanced page): which of them it was
        List<String> chips = Arrays.asList("Reset all", "Auto", "On", "Off", "Auto", "On", "Off", "Auto", "30", "60");
        eq(4, again(chips, 4, chips));
        eq(7, again(chips, 7, chips));
        eq(2, PlacePick.nth(chips, 7));
        eq(0, PlacePick.nth(chips, 1));
        // fewer of them now: the last one
        eq(4, PlacePick.pick(Arrays.asList("Reset all", "Auto", "On", "Off", "Auto", "On"), "Auto", 2, 7));

        // a row whose words changed (a button's label): its place in the order, clamped to the last row
        eq(3, PlacePick.pick(settings, "Stop build", 0, 3));
        eq(settings.size() - 1, PlacePick.pick(settings, "Stop build", 0, 40));
        // a row with no words: its place
        eq(5, PlacePick.pick(settings, "", -1, 5));
        eq(5, PlacePick.pick(settings, null, -1, 5));

        // no row was focused (touch), or the page has none
        eq(-1, PlacePick.pick(settings, null, -1, -1));
        eq(-1, PlacePick.pick(Arrays.<String>asList(), "Keys", 0, 2));
        eq(-1, PlacePick.nth(settings, -1));
        eq(-1, PlacePick.nth(settings, 99));

        System.out.println("ok " + n);
    }
}
