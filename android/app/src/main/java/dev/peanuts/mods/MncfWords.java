package dev.peanuts.mods;

import java.util.Locale;

/**
 * The reasons of Mistel's spec (Appendix B, and Peanuts' own runtime: reasons) in the player's words, for the page and the in-game tab: what stops the cheat
 * here, in a sentence, without the format's terms (UI-RULES.md 5). The reason itself goes to the log. No Android classes: CheatLoaderJvmTest reads them.
 */
public final class MncfWords {
    private MncfWords() {}

    /** Why a cheat cannot run here; c gives the names of the cheats a relation names (null: their ids are not looked up) */
    public static String reason(String reason, MncfBuild.Cheat c) {
        String r = reason == null ? "" : reason, token = r.contains(":") ? r.substring(0, r.indexOf(':')) : r, detail = r.contains(":") ? r.substring(r.indexOf(':') + 1) : "";
        switch (token) {
            case "pattern-not-found": return "Its place was not found in this version of the game.";
            case "pattern-ambiguous": return "Its place was found more than once in this version of the game, so it is not clear which one it is.";
            case "expect-mismatch": return "The game's code at its place is not what the cheat was made for: it is for another version.";
            case "build-unknown": return "It was made for other versions of the game, not this one.";
            case "outside": return "Its place lies outside the game.";
            case "module-unknown": return "It changes a part of the game this version does not have.";
            case "region-unknown": return "It writes a part of the game's memory Peanuts does not know.";
            case "game-mismatch": return "It is for another game.";
            case "signature": return "Its file was changed after it was signed, so none of its cheats run.";
            case "value-out-of-range": return "Its number does not fit the place it is written to.";
            case "requires": return "It needs another cheat of the file that cannot run here" + named(detail) + ".";
            case "excludes": return "It can never be on together with a cheat it needs" + named(detail) + ".";
            case "unknown-key": return "Its file has a part Peanuts does not know in this cheat (" + detail + ").";
            case "invalid": return detail.isEmpty() ? "It does nothing: it has no change to make." : "Its file has a mistake in this cheat (" + detail + ").";
            case "extension-missing": return "It uses a newer part of the cheat format (" + detail + ") that Peanuts does not read yet.";
            case "capability-missing":
                if (detail.equals("display")) return "It shows values from the game, which Peanuts cannot do yet.";
                if (detail.equals("code-hook")) return "It changes a function in a way Peanuts cannot do yet.";
                return "It needs something Peanuts cannot do yet (" + detail + ").";
            case "runtime": return runtime(detail);
            default: return "It cannot run here (" + r + ").";
        }
    }

    private static String named(String id) { return id.isEmpty() ? "" : " (" + id + ")"; }

    /** The runtime's reasons: runtime:unsupported:&lt;part&gt; and runtime:rewritten (section 11.1, 11.3), and Peanuts' own (MncfBuild.runtimeReason) */
    static String runtime(String why) {
        String w = why.toLowerCase(Locale.ROOT);
        if (w.startsWith("unsupported:")) return unsupported(w.substring("unsupported:".length()));
        switch (w) {
            case "rewritten": return "Peanuts rewrote this place of the game's code while building it, so this cheat cannot change it.";
            case "no-main": return "Its place is found once the game is built.";
            case "module": return "It changes a part of the game other than its main code, which Peanuts cannot do yet.";
            case "region": return "It writes a part of the game's memory Peanuts cannot reach yet.";
            case "address": return "It writes a fixed address, which the Switch does not have.";
            case "too-long": return "Its code is longer than Peanuts can run yet.";
            default: return "Peanuts cannot run it yet.";
        }
    }

    /** A part of the core Peanuts' cheat engine does not have yet (Mncf.FEATURES' keys) */
    static String unsupported(String part) {
        switch (part) {
            case "run.once": return "It runs once when you turn it on, which Peanuts cannot do yet.";
            case "run.action": return "It runs each time you press its buttons, in a way Peanuts cannot do yet.";
            case "run.timed": return "It runs for a set time, which Peanuts cannot do yet.";
            case "run.monitor": return "It shows values from the game, which Peanuts cannot do yet.";
            case "hold": return "It runs while you hold its buttons, in a way Peanuts cannot do yet.";
            case "toggle": return "It is switched with a button combination, which Peanuts cannot do yet.";
            case "press": return "It runs when you press its buttons, in a way Peanuts cannot do yet.";
            case "when": return "It waits for the game to be in a certain state, in a way Peanuts cannot do yet.";
            case "when.many": case "match.any": return "It waits for several things in the game at once, which Peanuts cannot do yet.";
            case "trigger.enter": return "It runs at the moment the game gets to a certain state, which Peanuts cannot do yet.";
            case "interval": return "It checks the game only now and then, which Peanuts cannot do yet.";
            case "override": return "It changes a function in a way Peanuts cannot do yet.";
            default: return "It uses a part of the cheat format Peanuts cannot run yet.";
        }
    }
}
