package dev.peanuts.mods;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.nxnative.android.CheatsBridge;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;
import io.github.davdxpx.mistel.mncf.MncfFile;
import io.github.davdxpx.mistel.mncf.Release;

/**
 * The screens of new cheat file versions (CheatUpdates; Mistel's spec, section 17): the question once per file and host, Check for updates and Back to
 * version N on a file's sheet, the line on its card when a new version is ready or a game update stopped its cheats, and the update screen with the notes
 * since the player's version. Nothing is installed without the player's tap; Peanuts follows only the address the file's author put into it.
 */
final class CheatUpdatesUi {
    private CheatUpdatesUi() {}

    // ---- The question, once per file and host ----

    /** After files were added: for each whose file names where its new versions are, the question whether Peanuts may look there on its own; then then */
    static void ask(Activity a, List<CheatSources.Source> added, Runnable then) {
        for (CheatSources.Source s : added) {
            CheatUpdates.Held h = CheatUpdates.held(CheatUpdates.folderOf(s), s);
            if (h == null || h.host() == null || CheatUpdates.allowed(h) != null) continue;
            List<CheatSources.Source> rest = new ArrayList<>(added);
            rest.removeAll(CheatUpdates.sourcesOf(CheatUpdates.folderOf(s), h.key));
            String host = h.host();
            List<String[]> lines = new ArrayList<>();
            lines.add(new String[] {"update", "Look there on its own?", "When you open its cheats, at most once a day, and when a game update stops them. Peanuts asks "
                    + host + " for that one pack and sends nothing about you or this device."});
            lines.add(new String[] {"info", "Or only when you ask", "Without this, Peanuts looks only when you tap Check for updates on one of its files. A new version is never put in place without you."});
            CheatImportUi.dialog(a, "New versions of " + h.name(), null, "The pack " + h.name() + " says where its author puts its new versions: " + host + ".", lines,
                    "Not now", () -> { answer(h, false); ask(a, rest, then); }, "Allow", () -> { answer(h, true); ask(a, rest, then); });
            return;
        }
        for (CheatSources.Source s : added) {
            String host = CheatUpdates.host(s);
            if (host == null || CheatUpdates.allowed(s) != null) continue;
            List<CheatSources.Source> rest = new ArrayList<>(added);
            rest.remove(s);
            List<String[]> lines = new ArrayList<>();
            lines.add(new String[] {"update", "Look there on its own?", "When you open its cheats, at most once a day, and when a game update stops them. Peanuts asks "
                    + host + " for that one file and sends nothing about you or this device."});
            lines.add(new String[] {"info", "Or only when you ask", "Without this, Peanuts looks only when you tap Check for updates on the file. A new version is never put in place without you."});
            CheatImportUi.dialog(a, "New versions of " + s.name, null, s.name + " says where its author puts its new versions: " + host + ".", lines,
                    "Not now", () -> { answer(a, s, false); ask(a, rest, then); }, "Allow", () -> { answer(a, s, true); ask(a, rest, then); });
            return;
        }
        if (then != null) then.run();
    }

    private static void answer(CheatUpdates.Held h, boolean yes) {
        try { CheatUpdates.allowed(h, yes); } catch (java.io.IOException e) { android.util.Log.w("peanuts", "cheats: the update answer of " + h.name() + ": " + e); }
    }

    private static void answer(Activity a, CheatSources.Source s, boolean yes) {
        try { CheatUpdates.allowed(s, yes); } catch (java.io.IOException e) { android.util.Log.w("peanuts", "cheats: the update answer of " + s.name + ": " + e); }
    }

    // ---- The file's sheet ----

    /** The rows a file's sheet gets for its versions (when it names where they are, or has an earlier one): Check for updates, Look on its own, Back to version N */
    static void sheet(Activity a, CheatsPage.Model m, CheatSources.Source s, LinearLayout box, AlertDialog[] sheet) {
        MncfFile f = CheatUpdates.file(s), before = CheatUpdates.previous(s);
        CheatUpdates.Held h = CheatUpdates.held(m.folder, s);
        String host = CheatUpdates.host(s), packHost = h == null ? null : h.host();
        if (host == null && packHost == null && before == null) return;
        PUi.add(box, PUi.section(a, "Versions"), 16);
        LinearLayout card = PUi.add(box, PUi.listCard(a), 6);
        String v = f == null ? null : f.fileVersion();
        if (packHost != null) {
            io.github.davdxpx.mistel.mncf.Pack pk = h.pack();
            String pv = pk == null ? null : pk.fileVersion();
            PUi.Setting look = new PUi.Setting(a, "", "Check for updates", Feel.row(x -> { close(sheet); checkPack(a, m, h); }))
                    .value("It comes from the pack " + h.name() + (pv != null ? ", version " + pv : "") + ". New versions come from " + packHost + ".");
            CheatsPage.rowLook(look.view, "update");
            card.addView(look.view);
            PUi.Setting own = new PUi.Setting(a, "", "Look on its own", null).value("For the whole pack, at most once a day, and when a game update stops its cheats")
                    .toggle(a, Boolean.TRUE.equals(CheatUpdates.allowed(h)), (b, x) -> { Feel.toggled(b, x); answer(h, x); });
            CheatsPage.rowLook(own.view, "clock");
            own.view.setOnClickListener(x -> own.toggle.toggle());
            card.addView(own.view);
        }
        if (host != null) {
            PUi.Setting look = new PUi.Setting(a, "", "Check for updates", Feel.row(x -> { close(sheet); check(a, m, s); }))
                    .value((v != null ? "Version " + v + ". " : "") + "New versions come from " + host + ".");
            CheatsPage.rowLook(look.view, "update");
            card.addView(look.view);
            Boolean ok = CheatUpdates.allowed(s);
            PUi.Setting own = new PUi.Setting(a, "", "Look on its own", null).value("At most once a day, and when a game update stops its cheats")
                    .toggle(a, Boolean.TRUE.equals(ok), (b, x) -> { Feel.toggled(b, x); answer(a, s, x); });
            CheatsPage.rowLook(own.view, "clock");
            own.view.setOnClickListener(x -> own.toggle.toggle());
            card.addView(own.view);
        }
        if (before != null) {
            String bv = before.fileVersion() != null ? before.fileVersion() : "before";
            PUi.Setting back = new PUi.Setting(a, "", "Back to version " + bv, Feel.row(x -> { close(sheet); back(a, m, s, before); }))
                    .value("The version you had before" + (v != null ? " version " + v : "") + ". What you set comes with you.");
            CheatsPage.rowLook(back.view, "undo");
            card.addView(back.view);
        }
    }

    private static void close(AlertDialog[] sheet) { if (sheet[0] != null) sheet[0].dismiss(); }

    // ---- The card's line ----

    /** On a file's card: a line when a new version is ready (tap: the update screen), or when a game update stopped its cheats and Peanuts may not look on its own */
    static void cardLine(Activity a, LinearLayout card, CheatsPage.Model m, CheatSources.Block b, CheatSources.Source s) {
        CheatUpdates.Held h = CheatUpdates.held(m.folder, s);
        if (h != null) {
            List<CheatSources.Source> of = CheatUpdates.sourcesOf(m.folder, h.key);
            byte[] po = CheatUpdates.offered(h);
            if (po != null && !of.isEmpty() && of.get(0).id.equals(s.id)) {
                CheatUpdates.PackOffer o = CheatUpdates.offer(m.folder, h, po, m.merged, m.on, CheatValues.read(m.folder), m.build, m.g.titleId);
                if (o == null) return;
                TextView t = CheatsPage.cardLine(a, "Version " + o.next.fileVersion() + " of the pack " + h.name() + " is ready" + (o.knows ? ": it knows " + m.versionWords() + ". " : ". ")
                        + "Tap to see what changed.");
                link(t, () -> showPack(a, m, h, po, false));
                card.addView(t);
            } else if (po == null && h.host() != null && !Boolean.TRUE.equals(CheatUpdates.allowed(h)) && CheatUpdates.stuck(b, m.build)) {
                TextView t = CheatsPage.cardLine(a, "A newer version of the pack " + h.name() + " may know " + m.versionWords() + ". Tap to ask " + h.host() + ".");
                link(t, () -> checkPack(a, m, h));
                card.addView(t);
            }
            return;
        }
        byte[] offer = CheatUpdates.offered(s);
        if (offer != null) {
            CheatUpdates.Offer o = CheatUpdates.offer(s, offer, m.merged, m.on, CheatValues.read(m.folder), m.build);
            if (o == null) return;
            TextView t = CheatsPage.cardLine(a, "Version " + o.next.fileVersion() + " is ready" + (o.knows ? ": it knows " + m.versionWords() + ". " : ". ") + "Tap to see what changed.");
            link(t, () -> show(a, m, s, offer, false));
            card.addView(t);
            return;
        }
        if (CheatUpdates.host(s) != null && !Boolean.TRUE.equals(CheatUpdates.allowed(s)) && !CheatUpdates.wentBack(s) && CheatUpdates.stuck(b, m.build)) {
            TextView t = CheatsPage.cardLine(a, "A newer version of this file may know " + m.versionWords() + ". Tap to ask " + CheatUpdates.host(s) + ".");
            link(t, () -> check(a, m, s));
            card.addView(t);
        }
    }

    private static void link(TextView t, Runnable r) {
        t.setFocusable(true);
        t.setClickable(true);
        t.setTextColor(PUi.col(t.getContext(), dev.nxnative.android.R.color.bn_accent));
        t.setOnClickListener(Feel.row(v -> r.run()));
    }

    // ---- Looking ----

    private static final Set<String> looking = new java.util.HashSet<>();   // the titles Peanuts is looking for new versions of on its own now

    /** The looks Peanuts makes on its own when the page opens (the files whose player allowed it, once a day or when a game update stopped them): the page again after a find */
    static void auto(Activity a, CheatsPage.Model m) {
        if (m.build == null || looking.contains(m.g.titleId)) return;
        long now = System.currentTimeMillis();
        List<CheatSources.Source> due = new ArrayList<>();
        for (CheatSources.Source s : m.sources) if (CheatUpdates.due(s, now, CheatUpdates.stuck(m.block(s.id), m.build))) due.add(s);
        List<CheatUpdates.Held> packs = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (CheatSources.Source s : m.sources) {
            CheatUpdates.Held h = CheatUpdates.held(m.folder, s);
            if (h != null && seen.add(h.key) && CheatUpdates.due(h, now, CheatUpdates.stuck(m.block(s.id), m.build))) packs.add(h);
        }
        if (due.isEmpty() && packs.isEmpty()) return;
        String title = m.g.titleId;
        looking.add(title);
        new Thread(() -> {
            boolean found = false;
            try {
                for (CheatSources.Source s : due) {
                    CheatUpdates.Look l = CheatUpdates.look(s, new CheatUpdates.Https(), now);
                    if (l.found()) found = true;
                    else if (!l.reason.equals("same") && !l.reason.equals("older")) android.util.Log.i("peanuts", "cheats: no new version of " + s.name + ": " + l.reason + (l.detail != null ? " (" + l.detail + ")" : ""));
                }
                for (CheatUpdates.Held h : packs) {
                    CheatUpdates.Look l = CheatUpdates.look(h, new CheatUpdates.Https(), now);
                    if (l.found()) found = true;
                    else if (!l.reason.equals("same") && !l.reason.equals("older")) android.util.Log.i("peanuts", "cheats: no new version of the pack " + h.name() + ": " + l.reason);
                }
            } finally {
                boolean f = found;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { looking.remove(title); if (f) CheatsPage.rebuild(); });
            }
        }, "peanuts-cheat-updates").start();
    }

    /** Check for updates: looks now (off the UI thread), then the update screen, or what was found */
    static void check(Activity a, CheatsPage.Model m, CheatSources.Source s) {
        String host = CheatUpdates.host(s);
        if (host == null) return;
        CheatPick.work(a, "Check for updates", "Asking " + host + " for the newest version of " + s.name + ".", () -> CheatUpdates.look(s, new CheatUpdates.Https(), System.currentTimeMillis()), l -> {
            if (l.found()) { byte[] offer = CheatUpdates.offered(s); if (offer != null) { CheatsPage.rebuild(); show(a, m, s, offer, false); return; } }
            if (l.detail != null) android.util.Log.i("peanuts", "cheats: looking for a new version of " + s.name + ": " + l.detail);
            String title = l.found() ? "Up to date" : words(l.reason)[0], text = l.found() ? "You have the newest version." : words(l.reason)[1];
            CheatImportUi.message(a, title, String.format(Locale.ROOT, text, host, l.version == null ? "" : l.version));
            CheatsPage.rebuild();
        }, e -> {
            Feel.refused(a);
            CheatImportUi.message(a, "Could not look", host + " did not answer. Try again later.");
        });
    }

    /** What a look that found nothing to install says: {title, text with %1$s the host and %2$s the version at the address} */
    static String[] words(String reason) {
        switch (reason) {
            case "same": return new String[] {"Up to date", "You have the newest version: %2$s is the one at %1$s."};
            case "older": return new String[] {"Up to date", "The version at %1$s, %2$s, is not newer than yours, so yours stays."};
            case "left": return new String[] {"No newer version", "Version %2$s at %1$s is the one you went back from. Peanuts offers the next one."};
            case "unreachable": return new String[] {"Could not look", "%1$s did not answer. Try again later."};
            case "update:signer": return new String[] {"Not taken", "The file at %1$s is signed by someone else than the version you have, so Peanuts does not take it as its new version."};
            case "update:signature": return new String[] {"Not taken", "The file at %1$s was changed after it was signed, so Peanuts does not take it."};
            case "update:id": case "update:game": return new String[] {"Not taken", "%1$s now has another cheat file, not a new version of this one."};
            default: return new String[] {"Not taken", "The answer from %1$s is not a cheat file Peanuts can read."};
        }
    }

    /** Check for updates of a pack: as for a file */
    static void checkPack(Activity a, CheatsPage.Model m, CheatUpdates.Held h) {
        String host = h.host();
        if (host == null) return;
        CheatPick.work(a, "Check for updates", "Asking " + host + " for the newest version of the pack " + h.name() + ".", () -> CheatUpdates.look(h, new CheatUpdates.Https(), System.currentTimeMillis()), l -> {
            if (l.found()) { byte[] offer = CheatUpdates.offered(h); if (offer != null) { CheatsPage.rebuild(); showPack(a, m, h, offer, false); return; } }
            if (l.detail != null) android.util.Log.i("peanuts", "cheats: looking for a new version of the pack " + h.name() + ": " + l.detail);
            String[] w = l.found() ? new String[] {"Up to date", "You have the newest version."} : words(l.reason);
            CheatImportUi.message(a, w[0], String.format(Locale.ROOT, w[1], host, l.version == null ? "" : l.version));
            CheatsPage.rebuild();
        }, e -> {
            Feel.refused(a);
            CheatImportUi.message(a, "Could not look", host + " did not answer. Try again later.");
        });
    }

    /** A pack's update screen: its notes, what each of its files that changed brings, its new files, the ones it no longer has; one tap installs it */
    static void showPack(Activity a, CheatsPage.Model m, CheatUpdates.Held h, byte[] data, boolean byHand) {
        CheatUpdates.PackOffer o = CheatUpdates.offer(m.folder, h, data, m.merged, m.on, CheatValues.read(m.folder), m.build, m.g.titleId);
        if (o == null) { CheatImportUi.message(a, "Not a cheat pack", "Peanuts cannot read it, so the pack " + h.name() + " stays as it is."); return; }
        String v = o.next.fileVersion() != null ? o.next.fileVersion() : "without a number", have = o.now.fileVersion();
        List<String[]> lines = new ArrayList<>();
        if (byHand && !o.newer()) lines.add(new String[] {"warning", o.check.reason().equals("update:signer") ? "Signed by someone else" : have != null ? "You have version " + have : "Not a newer version",
                o.check.reason().equals("update:signer") ? "The pack you have is signed by another key. Use this one only if you trust where it came from." : "This one is not newer. Use it only if you want to go back to it."});
        int shown = 0;
        for (Release r : o.since) {
            if (shown++ == 5) break;
            String text = r.text();
            lines.add(new String[] {"history", "Version " + r.version() + (r.date() != null ? ", " + day(r.date()) : ""), text != null ? text : "No notes for this version."});
        }
        List<String> news = new ArrayList<>(), fixed = new ArrayList<>(), reset = new ArrayList<>(), gone = new ArrayList<>(o.gone);
        for (CheatUpdates.Offer f : o.files) { news.addAll(f.added); fixed.addAll(f.fixed); reset.addAll(f.reset); gone.addAll(f.gone); }
        if (!o.added.isEmpty()) lines.add(new String[] {"add", "New files: " + String.join(", ", o.added), "Added with the cheats their files turn on."});
        if (!news.isEmpty()) lines.add(new String[] {"add", "New: " + String.join(", ", news), "Marked New on the cheats page until you start the game."});
        if (!fixed.isEmpty()) lines.add(new String[] {"check", "Fixed: " + String.join(", ", fixed), "Marked Fixed until you start the game."});
        if (!reset.isEmpty()) lines.add(new String[] {"scale", "Back to the file's own number: " + String.join(", ", reset), "The number you set no longer fits this version."});
        if (!gone.isEmpty()) lines.add(new String[] {"info", "No longer in it: " + String.join(", ", gone), "What you set for these goes with them."});
        lines.add(new String[] {"controller", "Everything else stays as you set it", "The cheats you switched on and the numbers you set move to the new version."});
        lines.add(new String[] {"undo", "You can go back file by file", "Each file that changes keeps its version before. Tap it on the cheats page to go back."});
        View card = CheatImportUi.formatCard(a, "update", "Version " + v, (byHand ? "Added by you" : "From " + o.host) + ", the pack " + h.name(), null);
        CheatImportUi.dialog(a, (byHand && !o.newer() ? "Use version " : "Version ") + v + " of " + h.name(), card, null, lines, "Not now", null, byHand ? "Use this version" : "Install",
                () -> installPack(a, m, h, data, v));
    }

    static void installPack(Activity a, CheatsPage.Model m, CheatUpdates.Held h, byte[] data, String v) {
        CheatsPage.Game g = m.g;
        CheatPick.work(a, "Install version " + v, "Finding each cheat's place in your game's code.", () -> {
            Set<String> on = CheatsBridge.on(a, g.titleId);
            CheatUpdates.PackDone d = CheatUpdates.install(m.folder, h, data, m.merged, on, g.titleId, System.currentTimeMillis());
            CheatsBridge.on(a, g.titleId, on);
            if (m.build != null) {
                CheatsBridge.prepare(a, g.titleId, g.packDir, m.build);
                CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, m.build, CheatsBridge.textEnd(g.packDir), true);
            }
            return d;
        }, d -> {
            Feel.accepted(a);
            CheatsPage.rebuild();
            Toast.makeText(a, "Version " + d.version + " of the pack " + h.name() + " is installed.", Toast.LENGTH_LONG).show();
        }, e -> {
            Feel.refused(a);
            android.util.Log.w("peanuts", "cheats: installing a new version of the pack " + h.name() + ": " + e);
            CheatImportUi.message(a, "Not installed", "The pack " + h.name() + " stays as it was. Free some space and try again.");
            CheatsPage.rebuild();
        });
    }

    // ---- The update screen ----

    /**
     * The update screen: version, where it comes from, the notes of each version since the player's, newest first, the cheats it brings and fixed, the numbers
     * that go back to the file's own, the cheats that are gone, and that everything else stays. byHand: a file the player added (it may be older, or signed by
     * someone else: said, and still possible). One tap installs it
     */
    static void show(Activity a, CheatsPage.Model m, CheatSources.Source s, byte[] data, boolean byHand) {
        CheatUpdates.Offer o = CheatUpdates.offer(s, data, m.merged, m.on, CheatValues.read(m.folder), m.build);
        if (o == null) { CheatImportUi.message(a, "Not a cheat file", "Peanuts cannot read it, so " + s.name + " stays as it is."); return; }
        String v = o.next.fileVersion() != null ? o.next.fileVersion() : "without a number", have = o.now.fileVersion();
        List<String[]> lines = new ArrayList<>();
        if (byHand && !o.newer()) {
            String why = o.check.reason();
            if (why.equals("update:version")) lines.add(new String[] {"warning", have != null ? "You have version " + have : "Not a newer version",
                    "This one is not newer. Use it only if you want to go back to it."});
            else if (why.equals("update:signer")) lines.add(new String[] {"warning", "Signed by someone else", "The version you have is signed by another key. Use it only if you trust where it came from."});
        }
        int shown = 0;
        for (Release r : o.since) {
            if (shown++ == 5) { lines.add(new String[] {"history", "And " + CheatsPage.count(o.since.size() - 5, "earlier version"), "Their notes are in the file."}); break; }
            String text = CheatUpdates.text(o.next, r, Locale.getDefault());
            lines.add(new String[] {"history", "Version " + r.version() + (r.date() != null ? ", " + day(r.date()) : ""), text != null ? text : "No notes for this version."});
        }
        if (!o.added.isEmpty()) lines.add(new String[] {"add", "New: " + String.join(", ", o.added), "Marked New on the cheats page until you start the game."});
        if (!o.fixed.isEmpty()) lines.add(new String[] {"check", "Fixed: " + String.join(", ", o.fixed), "Marked Fixed until you start the game."});
        if (!o.reset.isEmpty()) lines.add(new String[] {"scale", "Back to the file's own number: " + String.join(", ", o.reset), "The number you set no longer fits this version."});
        if (!o.gone.isEmpty()) lines.add(new String[] {"info", "No longer in it: " + String.join(", ", o.gone), "What you set for these goes with them."});
        lines.add(new String[] {"controller", "Everything else stays as you set it", "The cheats you switched on and the numbers you set move to the new version."});
        if (have != null) lines.add(new String[] {"undo", "You can go back", "Peanuts keeps version " + have + ". Tap the file on the cheats page to go back to it."});
        String sub = byHand ? "Added by you, for " + s.name : "From " + o.host + (o.knows ? ", made for " + m.versionWords() + " too" : "");
        View card = CheatImportUi.formatCard(a, "update", "Version " + v, sub, o.check.file() != null && io.github.davdxpx.mistel.mncf.SignatureResult.VERIFIED.equals(o.check.file().signature().result()) ? signer(o.check.file()) : null);
        String title = (byHand && !o.newer() ? "Use version " : "Version ") + v + " of " + s.name;
        CheatImportUi.dialog(a, title, card, null, lines, "Not now", null, byHand ? "Use this version" : "Install", () -> install(a, m, s, data, v));
    }

    private static String signer(MncfFile f) {
        List<String> n = f.signature().signers();
        return n.isEmpty() ? null : n.get(0);
    }

    /** "2026-12-02" as "2 Dec 2026", in the device's language; the text as it is when it is no such day */
    static String day(String iso) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(iso);
            return d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** data installed as s's new version (off the UI thread, then made ready for the build so what the player set is in place), the page again */
    static void install(Activity a, CheatsPage.Model m, CheatSources.Source s, byte[] data, String v) {
        File folder = m.folder;
        CheatsPage.Game g = m.g;
        CheatPick.work(a, "Install version " + v, "Finding each cheat's place in your game's code.", () -> {
            Set<String> on = CheatsBridge.on(a, g.titleId);
            CheatUpdates.Done d = CheatUpdates.install(folder, s, data, m.merged, on);
            CheatsBridge.on(a, g.titleId, on);
            if (m.build != null) {
                CheatsBridge.prepare(a, g.titleId, g.packDir, m.build);
                CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, m.build, CheatsBridge.textEnd(g.packDir), true);   // puts what the player set in place
            }
            return d;
        }, d -> {
            Feel.accepted(a);
            CheatsPage.rebuild();
            Toast.makeText(a, "Version " + d.version + " of " + s.name + " is installed." + (d.reset.isEmpty() ? "" : " " + CheatsPage.count(d.reset.size(), "number") + " went back to the file's own."), Toast.LENGTH_LONG).show();
        }, e -> {
            Feel.refused(a);
            android.util.Log.w("peanuts", "cheats: installing a new version of " + s.name + ": " + e);
            CheatImportUi.message(a, "Not installed", s.name + " stays as it was. Free some space and try again.");
            CheatsPage.rebuild();
        });
    }

    /** Back to version N: asked, then the earlier file in place with what the player set, and the newer version not offered again */
    static void back(Activity a, CheatsPage.Model m, CheatSources.Source s, MncfFile before) {
        MncfFile now = CheatUpdates.file(s);
        String bv = before.fileVersion() != null ? before.fileVersion() : "before", nv = now == null || now.fileVersion() == null ? "this one" : now.fileVersion();
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"controller", "What you set comes with you", "The cheats you switched on and the numbers you set move back, where version " + bv + " has them."});
        lines.add(new String[] {"update", "Version " + nv + " is not offered again", "Peanuts offers the next version after it."});
        File folder = m.folder;
        CheatsPage.Game g = m.g;
        CheatImportUi.dialog(a, "Back to version " + bv + "?", null, s.name + " goes back to the version you had before version " + nv + ".", lines, "Cancel", null, "Go back", () ->
                CheatPick.work(a, "Back to version " + bv, null, () -> {
                    Set<String> on = CheatsBridge.on(a, g.titleId);
                    CheatUpdates.Done d = CheatUpdates.back(folder, s, m.merged, on);
                    CheatsBridge.on(a, g.titleId, on);
                    if (m.build != null) {
                        CheatsBridge.prepare(a, g.titleId, g.packDir, m.build);
                        CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, m.build, CheatsBridge.textEnd(g.packDir), true);
                    }
                    return d;
                }, d -> {
                    Feel.accepted(a);
                    CheatsPage.rebuild();
                    Toast.makeText(a, s.name + " is back at version " + bv + ".", Toast.LENGTH_LONG).show();
                }, e -> {
                    Feel.refused(a);
                    android.util.Log.w("peanuts", "cheats: back to an earlier version of " + s.name + ": " + e);
                    CheatImportUi.message(a, "Not changed", s.name + " stays at version " + nv + ".");
                    CheatsPage.rebuild();
                }));
    }

    /** The source whose file is another version of f (CheatUpdates.versionOf) */
    static CheatSources.Source versionOf(File folder, MncfFile f) { return CheatUpdates.versionOf(folder, f); }
}
