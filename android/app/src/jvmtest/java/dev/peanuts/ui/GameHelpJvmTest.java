// PEANUTS: GameHelp on the JVM (peanuts/tests/test_game_page.py); prints "ok <n>" or throws
package dev.peanuts.ui;

public final class GameHelpJvmTest {
    private static int n;

    private static void yes(boolean b, String what) { n++; if (!b) throw new AssertionError(what); }

    public static void main(String[] a) {
        // every row the areas know is explained, in calm words: sentences, no separator glyph, no dash, no exclamation
        for (String t : GameAreas.titles()) {
            String h = GameHelp.of(t);
            if (t.equals("Cheats") || t.equals("Cheat files")) continue;   // Mods & Cheats' rows explain themselves (dev.peanuts.mods.CheatsPage)
            yes(h != null, t + " is explained");
            yes(h.endsWith("."), t + ": a sentence");
            yes(!h.contains("·") && !h.contains("—") && !h.contains("–") && !h.contains("!"), t + ": no separator glyph, dash or exclamation");
            yes(h.length() <= 170, t + ": short enough for the second screen");
        }
        // and only those: a help line for a title the page does not have would never show
        for (String t : GameHelp.titles()) yes(GameAreas.titles().contains(t), t + " is a row the areas know");
        yes(GameHelp.of("nope") == null, "an unknown row has no help");
        yes(GameHelp.of(null) == null, "null has no help");
        yes(GameHelp.of(" Upscaler ") != null, "the title is trimmed");
        // actions: their line says what they do, not how they are set
        yes(GameHelp.action("Back up saves") && GameHelp.action("Build again") && GameHelp.action("Share log…"), "actions");
        yes(!GameHelp.action("Upscaler") && !GameHelp.action("Played") && !GameHelp.action(null), "settings and facts are not actions");
        for (String t : new String[] {"Back up saves", "Restore saves", "Import save", "Copy to another user", "Delete save data", "Build again", "Prepare again",
                "Preparing", "Export pack…", "Import pack…", "Folders", "Add to home screen", "Share log…", "Share log", "Share build log"})
            yes(GameAreas.titles().contains(t) && GameHelp.action(t), t + " is an action the areas know");
        System.out.println("ok " + n);
    }
}
