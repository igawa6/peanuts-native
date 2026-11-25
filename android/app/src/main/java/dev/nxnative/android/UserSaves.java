package dev.nxnative.android;
// PEANUTS: new file. Saves for each user on a game's Saves tab (issue #422). Each user has a save of their own (Users.saveDir), but Back up saves, Restore saves,
// Import save and Delete save data worked on the first user's alone, so an import meant for a second user replaced the first user's save. With two or more
// users (or the saves of a removed user) the tab shows whose save its rows work on, a card for each, starting at the user playing now; SaveUi takes that
// user's folder (save), says whose save each dialog is about, and holds the game's own save lock around another user's backup or restore (locked), which a
// game start takes too. Copy to another user gives one user's save to another, theirs backed up first. The folder logic without Android is UserSaveDirs.
// It lives in this package because SaveUi, SaveStore, Users, Folders and Ui are package-private; the hooks are one line each (SaveUi, MainActivity.gameTab).
// Settings › Users (dev.peanuts.users.UsersPage) reaches a user's saves through the public part at the end: of, has, open.

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UserSaves {   // public: Settings › Users lists a user's saves and opens a game's Saves tab (of, has, open)
    private UserSaves() {}

    /** The Saves tab's choice by title ID: whose save its rows work on (a user's uid, or a removed user's). Kept while the app runs */
    private static final Map<String, String> picked = new HashMap<>();

    /** A user, or the save of a removed one */
    static final class Who {
        final String uid, name, said;   // name: on the card ("Anna", "Removed user"); said: within a sentence ("Anna", "a removed user")
        final boolean removed;
        final int index;   // the place in Settings › Users (a picture's background), 7 for a removed user

        Who(String uid, String name, String said, boolean removed, int index) { this.uid = uid; this.name = name; this.said = said; this.removed = removed; this.index = index; }

        /** said at the start of a sentence */
        String opening() { return said.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + said.substring(1); }
    }

    /** Today's users, then the saves of removed users this game still has. The names go to SaveStore for the backups' user line */
    static List<Who> who(Context a, Game g) {
        List<Users.User> users = Users.list(a);
        List<Who> out = new ArrayList<>();
        List<String> uids = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            Users.User u = users.get(i);
            out.add(new Who(u.uid, u.name, u.name, false, i));
            uids.add(u.uid);
            names.put(u.uid, u.name);
        }
        SaveStore.userNames(names);
        File base = g == null ? null : Folders.save(a, g);
        if (base == null) return out;
        List<String> gone = UserSaveDirs.removed(base, uids);
        for (String u : gone) {   // two or more told apart by the start of their ID
            String code = gone.size() > 1 ? u.substring(0, 4).toUpperCase(java.util.Locale.ROOT) : "";
            out.add(new Who(u, code.isEmpty() ? "Removed user" : "Removed user " + code, code.isEmpty() ? "a removed user" : "a removed user (" + code + ")", true, 7));
        }
        return out;
    }

    /** Whether the tab names users: two or more of them, or a removed user's save */
    static boolean several(Context a, Game g) { return who(a, g).size() > 1; }

    /** Whether the rows work on a removed user's save (nobody plays it: no "starts from the beginning") */
    static boolean removed(Context a, Game g) { return current(a, g).removed; }

    /** Whose save the rows work on: the one picked on the tab while it is still a user or a removed user's save, else the user playing now */
    static String uid(Context a, Game g) {
        String p = picked.get(g.titleId);
        if (p != null) for (Who w : who(a, g)) if (w.uid.equals(p)) return p;
        return Users.defaultUid(a);
    }

    private static Who current(Context a, Game g) {
        String u = uid(a, g);
        List<Who> l = who(a, g);
        for (Who w : l) if (w.uid.equals(u)) return w;
        return l.get(0);
    }

    /** The save folder the rows work on (SaveUi): the picked user's, the game's own save folder for the first user (Folders.save) */
    static File save(Context a, Game g) {
        File base = Folders.save(a, g);
        return base == null ? null : Users.saveDir(base, uid(a, g));
    }

    // ---- Words: as before with one user, naming the user with more ----

    /** "Mario Kart's save" with one user, "Anna's save of Mario Kart" with more; word is "save" or "save data" */
    static String saveOf(Context a, Game g, String word) {
        if (!several(a, g)) return g.name + "'s " + word;
        return current(a, g).said + "'s " + word + " of " + g.name;
    }

    /** "Mario Kart has no save", or "Anna has no save of Mario Kart" */
    static String noSave(Context a, Game g) { return several(a, g) ? current(a, g).opening() + " has no save of " + g.name : g.name + " has no save"; }

    /** "", or " for Anna" ("" for a removed user, who plays no more) */
    static String forWhom(Context a, Game g) {
        if (!several(a, g)) return "";
        Who w = current(a, g);
        return w.removed ? "" : " for " + w.name;
    }

    /** "Your current save", or "Anna's current save" */
    static String yours(Context a, Game g) { return several(a, g) ? current(a, g).opening() + "'s current save" : "Your current save"; }

    /** Delete save data's note on the other users' saves (empty with one user) */
    static String deleteNote(Context a, Game g) {
        if (!several(a, g)) return "";
        Who w = current(a, g);
        if (w.removed) return "\n\nThis save is " + w.said + "'s. The users' saves are in folders of their own and stay as they are.";
        return "\n\nThis is " + w.name + "'s save. The other users' saves are in folders of their own and stay as they are.";
    }

    /** A user's name for a backup or a folder of theirs: today's name, else the name the backup recorded, else "Another user" */
    static String nameOf(Context a, String uid, String recorded) {
        for (Users.User u : Users.list(a)) if (u.uid.equals(uid)) return u.name;
        return recorded != null && !recorded.isEmpty() ? recorded : "Another user";
    }

    /** Restore saves' line of a backup: whose save it is first, with two or more users or when it is not the first user's */
    static String label(Context a, Game g, SaveStore.Backup b) {
        if (!several(a, g) && Users.FIRST.equals(b.user)) return "";
        return nameOf(a, b.user, b.who) + "'s save\n";
    }

    /** Restore saves' order: the backups of the user the rows work on first, each part newest first */
    static Comparator<SaveStore.Backup> order(Context a, Game g) {
        String u = uid(a, g);
        return (x, y) -> {
            boolean mx = x.user.equals(u), my = y.user.equals(u);
            return mx != my ? (mx ? -1 : 1) : y.name.compareTo(x.name);
        };
    }

    /** A source's manifest (a backup's, or the one beside an imported backup), else null for a plain folder */
    private static SaveStore.Manifest manifestOf(File from) {
        File mf = new File(from.getAbsoluteFile().getParentFile(), from.getName() + SaveStore.MANIFEST);
        try { return mf.isFile() ? SaveStore.readManifest(mf) : null; } catch (IOException | RuntimeException e) { return null; }
    }

    /** Restore's note: whose save the source is when it is another user's, and that the device save, every user's, stays as it is when it does */
    static String restoreNote(Context a, Game g, File from) {
        File save = save(a, g);
        if (save == null) return "";
        SaveStore.Manifest m = manifestOf(from);
        StringBuilder s = new StringBuilder();
        String src = m != null ? SaveStore.userOf(m) : SaveStore.userOf(from);
        boolean known = m != null || SaveStore.accountOnly(from);   // a plain folder says nothing of its user
        if (known && several(a, g) && !src.equals(uid(a, g))) s.append("\n\nIt is ").append(nameOf(a, src, m != null ? m.who : "")).append("'s save.");
        boolean srcDevice = m != null ? m.layout >= 2 && m.entries.containsKey(SaveStore.DEVICE) : SaveStore.device(from).isDirectory();
        boolean keep = SaveStore.accountOnly(save) ? srcDevice : !srcDevice && SaveStore.hasFiles(SaveStore.device(save)) && SaveStore.fromUser(from, m != null ? m : new SaveStore.Manifest());
        if (keep) s.append("\n\nThe game's device save, which every user shares, stays as it is.");
        return s.toString();
    }

    /** Back up saves' zip name: the user's name in it with two or more users */
    static String zipName(Context a, Game g, String stamp) {
        String n = several(a, g) ? UserSaveDirs.fileName(current(a, g).name) : "";
        return SaveZip.zipName(g.titleId, n.isEmpty() ? stamp : stamp + "-" + n);
    }

    /** SaveUi.ready's part for users: a removed user's save can be backed up, copied to a user or deleted, not restored or imported into */
    static boolean ready(MainActivity a, Game g, String what) {
        if (!(what.equals("restore") || what.equals("import")) || !current(a, g).removed) return true;
        a.error("Nothing is restored or imported into a removed user's save. Copy to another user gives this save to a user.");
        return false;
    }

    /**
     * Runs work holding the game's own save lock (the first user's folder, Folders.save) when it changes another user's save (target): a game start takes that
     * lock and keeps it while the game runs, so no backup or restore of any user's save meets a running game. For the first user's save SaveStore takes it itself
     */
    static Object locked(Context a, Game g, File target, SaveUi.Work work) throws IOException {
        File base = Folders.save(a, g);
        if (base == null || SaveStore.same(base, target)) return work.run();
        try (SaveStore.Lock l = SaveStore.lock(base)) {
            return work.run();
        }
    }

    // ---- The tab: whose save, its cards, Copy to another user ----

    /** What the shown Saves tab has (one tab at a time): refreshed after each save operation (changed) and when a card is picked */
    private static final class Tab {
        final WeakReference<MainActivity> a;
        final Game g;
        final LinearLayout row;
        final View[] views;   // the section's label and the cards' row: gone once a single user is left (a removed user's save deleted)
        TextView where;
        Ui.Setting copy;
        Tab(MainActivity a, Game g, LinearLayout row, View... views) { this.a = new WeakReference<>(a); this.g = g; this.row = row; this.views = views; }
    }

    // Weak: the tab's views hold the activity (a static reference kept a destroyed launcher screen, a turn's or a fold's, until the next Saves tab). The cards'
    // click listeners hold the Tab while its views show
    private static WeakReference<Tab> tab;
    private static Tab tab() { WeakReference<Tab> w = tab; return w == null ? null : w.get(); }

    /** MainActivity.gameTab: the user cards above the Saves tab's rows, with two or more users or a removed user's save; nothing with one user */
    static void picker(MainActivity a, LinearLayout content, Game g) {
        tab = null;
        if (!several(a, g)) return;
        TextView label = Ui.add(content, Ui.section(a, "Whose save"), 16);
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        scroll.addView(row);
        Ui.add(content, scroll, 8);
        Tab t = new Tab(a, g, row, label, scroll);
        tab = new WeakReference<>(t);
        fill(t);
    }

    /** MainActivity.gameTab: the line under the rows that names the save folder, kept on the picked user's */
    static void where(TextView t, Game g) {
        Tab tb = tab();
        if (tb != null && tb.g.titleId.equals(g.titleId)) tb.where = t;
    }

    /** MainActivity.gameTab: Copy to another user, above Delete save data, with two or more users or a removed user's save */
    static void copyRow(MainActivity a, LinearLayout saves, Game g) {
        if (!several(a, g)) return;
        Ui.Setting s = new Ui.Setting(a, "", "Copy to another user", v -> copy(a, g)).chevron(a);
        dev.peanuts.users.UserFace.rowIcon(a, s.view, "user");
        saves.addView(s.view);
        Tab tb = tab();
        if (tb != null && tb.g.titleId.equals(g.titleId)) tb.copy = s;
        copyLine(a, g, s);
    }

    private static void copyLine(Context a, Game g, Ui.Setting s) {
        Who w = current(a, g);
        s.value(w.removed ? "Gives this save to a user; theirs is backed up first" : "Gives " + w.name + "'s save to another user; theirs is backed up first");
    }

    /** SaveUi.run, when a backup, restore, import, delete or copy is done: the cards and lines again */
    static void changed(MainActivity a, Game g) {
        Tab t = tab();
        if (t == null || t.a.get() != a || !t.g.titleId.equals(g.titleId) || !t.row.isAttachedToWindow()) return;
        fill(t);
    }

    private static void pick(Tab t, String uid) {
        MainActivity a = t.a.get();
        if (a == null) return;
        picked.put(t.g.titleId, uid);
        fill(t);
    }

    /** The cards (the picked one framed), the folder line and Copy's line; then each card's size, read off the main thread */
    private static void fill(Tab t) {
        MainActivity a = t.a.get();
        if (a == null) return;
        View focused = t.row.findFocus();
        String had = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        t.row.removeAllViews();
        List<Who> l = who(a, t.g);
        int shown = l.size() > 1 ? View.VISIBLE : View.GONE;
        for (View v : t.views) v.setVisibility(shown);
        if (t.copy != null) t.copy.view.setVisibility(shown);
        String on = uid(a, t.g);
        File base = Folders.save(a, t.g);
        List<TextView> lines = new ArrayList<>();
        for (Who w : l) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(a, 8);
            c.setPadding(p, p, p, Ui.dp(a, 10));
            boolean sel = w.uid.equals(on);
            c.setBackground(Ui.ripple(a, cardBg(a, sel)));
            c.setFocusable(true);
            c.setClickable(true);
            c.setTag(w.uid);
            c.setContentDescription(w.name + (sel ? ", picked" : ""));
            c.addView(dev.peanuts.users.UserFace.box(a, w.name, dev.peanuts.users.UserPictures.of(a.getFilesDir(), w.uid, w.index), 56, 14, false));
            TextView name = Ui.text(a, w.name, 14, R.color.bn_text, 600);
            name.setMaxLines(2);   // a long name, or "Removed user" and the start of its ID, on two lines
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, Ui.dp(a, 6), 0, 0);
            c.addView(name, new LinearLayout.LayoutParams(Ui.dp(a, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView line = Ui.small(a, " ");
            line.setTextSize(12);
            line.setSingleLine(true);
            line.setGravity(Gravity.CENTER);
            c.addView(line, new LinearLayout.LayoutParams(Ui.dp(a, 96), LinearLayout.LayoutParams.WRAP_CONTENT));
            lines.add(line);
            if (w.removed) c.setAlpha(sel ? 1f : 0.72f);
            c.setOnClickListener(dev.peanuts.ui.Feel.row(v -> pick(t, w.uid)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);   // every card as tall as the tallest
            lp.setMarginEnd(Ui.dp(a, 8));
            t.row.addView(c, lp);
            if (w.uid.equals(had)) c.post(c::requestFocus);   // a controller's focus stays on the card it picked
        }
        if (t.where != null) { File s = save(a, t.g); t.where.setText("The save: " + (s != null ? s.getPath() : "not set")); }
        if (t.copy != null) copyLine(a, t.g, t.copy);
        if (base == null) return;
        new Thread(() -> {
            String[] text = new String[l.size()];
            for (int i = 0; i < l.size(); i++) {
                long[] fb = {0, 0};
                count(Users.saveDir(base, l.get(i).uid), fb);
                text[i] = fb[0] == 0 ? "No save" : SaveStore.size(fb[1]);
            }
            a.runOnUiThread(() -> { for (int i = 0; i < text.length && i < lines.size(); i++) lines.get(i).setText(text[i]); });
        }, "nx-user-saves").start();
    }

    private static GradientDrawable cardBg(Context c, boolean on) {
        return Ui.round(Ui.col(c, R.color.bn_card), Ui.dp(c, 18), Ui.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), Ui.dp(c, on ? 2 : 1));
    }

    /** The files and bytes under a folder: {files, bytes} */
    private static void count(File d, long[] fb) {
        File[] l = d.listFiles();
        if (l == null) return;
        for (File f : l) {
            if (java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) count(f, fb);
            else { fb[0]++; fb[1] += f.length(); }
        }
    }

    // ---- Copy to another user ----

    /** The users to give the picked save to (not its own user), then a confirmation; the save it replaces is backed up first (SaveStore.restore) */
    static void copy(MainActivity a, Game g) {
        if (!SaveUi.ready(a, g, "copy the save")) return;
        File from = save(a, g);
        Who src = current(a, g);
        if (from == null || !SaveStore.hasFiles(from)) { a.error(noSave(a, g) + ": nothing to copy."); return; }
        File base = Folders.save(a, g);
        List<Users.User> to = new ArrayList<>();
        for (Users.User u : Users.list(a)) if (!u.uid.equals(src.uid)) to.add(u);
        if (to.isEmpty()) { a.error("There is no other user to copy to. Settings › Users adds one."); return; }
        String[] labels = new String[to.size()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = to.get(i).name + "\n" + (SaveStore.hasFiles(Users.saveDir(base, to.get(i).uid)) ? "Has a save of this game: it is backed up, then replaced" : "No save of this game yet");
        new PadFocus.Builder(a).setTitle("Copy " + saveOf(a, g, "save") + " to")
                .setItems(labels, (d, w) -> confirmCopy(a, g, src, from, to.get(w)))
                .setNegativeButton("Cancel", null).show();
    }

    private static void confirmCopy(MainActivity a, Game g, Who src, File from, Users.User dst) {
        File base = Folders.save(a, g);
        File target = Users.saveDir(base, dst.uid);
        String why = SaveStore.refuseSource(from, target);
        if (why != null) { a.error("Not copied: " + why + "."); return; }
        String st = SaveUi.stamp();
        File bk = SaveUi.target(a, g);
        boolean has = SaveStore.hasFiles(target);
        boolean dev = SaveStore.accountOnly(target) ? SaveStore.device(from).isDirectory() : SaveStore.hasFiles(SaveStore.device(target));
        String body = (has ? dst.name + "'s save of " + g.name + " is replaced with " + src.said + "'s. It is backed up first (checked), so Restore saves can bring it back. "
                : dst.name + " gets a copy of " + src.said + "'s save of " + g.name + ". ")
                + (src.removed ? "That save" : src.name + "'s save") + " stays as it is." + (dev ? "\n\nThe game's device save, which every user shares, stays as it is." : "")
                + "\n\nFrom: " + from + "\nTo: " + target + (has ? "\nBackup of it: " + new File(bk, st + SaveStore.BEFORE_RESTORE) : "");
        Ui.confirmDanger(a, "Copy to " + dst.name + "?", body, "Copy", () -> {
            if (!SaveUi.ready(a, g, "copy the save")) return;
            SaveUi.run(a, g, "Copying " + src.said + "'s save to " + dst.name + "…", () -> locked(a, g, target, () -> SaveStore.restore(from, target, bk, st)), (r, err) -> {
                if (err != null) {
                    Ui.explain(a, "Not copied", dst.name + "'s save is as it was" + (SaveStore.pending(target) ? ", but for a copy that finishes at the next backup, restore or game start" : "")
                            + ". " + (src.removed ? "The save copied" : src.name + "'s save") + " was only read.", err + "\nFrom: " + from + "\nTo: " + target).setPositiveButton("OK", null).show();
                    return;
                }
                SaveStore.Restored x = (SaveStore.Restored) r;
                Toast.makeText(a, "Copied to " + dst.name + ": " + x.now.files + " files (" + SaveStore.size(x.now.bytes) + ")"
                        + (x.before != null ? "; their old save is in Restore saves" : ""), Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---- For Settings › Users (dev.peanuts.users): a user's saves across the library ----

    /** A game of the library that a user has a save of: its title ID, its name and the user's folder of its save */
    public static final class Saved {
        public final String titleId, name;
        public final File dir;

        Saved(String titleId, String name, File dir) { this.titleId = titleId; this.name = name; this.dir = dir; }
    }

    /** The games of the library where user uid has a save (a file in their folder of the game's save, Folders.save: a picked folder as well), by name */
    public static List<Saved> of(Context c, String uid) {
        List<Saved> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Game g : Game.load(c)) {
            File base = Folders.save(c, g);
            if (base == null || !seen.add(g.titleId)) continue;
            File d = Users.saveDir(base, uid);
            if (SaveStore.hasFiles(d)) out.add(new Saved(g.titleId, g.name, d));
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** Whether user uid has a save of the game titleId: in their folder of the game's save (Folders.save), the app's own save folder for a game not in the library */
    public static boolean has(Context c, String titleId, String uid) {
        File base = null;
        for (Game g : Game.load(c)) if (g.titleId.equals(titleId)) { base = Folders.save(c, g); break; }
        if (base == null) base = AppStorage.save(c, titleId);
        return SaveStore.hasFiles(Users.saveDir(base, uid));
    }

    /** The bytes of the files in a folder (read it off the main thread) */
    public static long bytes(File d) {
        long[] fb = {0, 0};
        count(d, fb);
        return fb[1];
    }

    /** "1.2 MB" (SaveStore.size) */
    public static String size(long bytes) { return SaveStore.size(bytes); }

    /** Opens the game's page on its Saves tab with user uid picked there; false when a is not the launcher's main screen or the game is not in the library */
    public static boolean open(android.app.Activity a, String titleId, String uid) {
        if (!(a instanceof MainActivity)) return false;
        for (Game g : Game.load(a)) {
            if (!g.titleId.equals(titleId)) continue;
            picked.put(titleId, uid);
            GameSettings.openOn((MainActivity) a, g, GameSettings.SAVES);
            return true;
        }
        return false;
    }
}
