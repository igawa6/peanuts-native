package dev.peanuts.folders;

import android.app.Activity;
import android.content.Context;

import java.util.List;

/**
 * One kind of folder the app uses (FolderKinds.ALL: games, updates and DLC, builds): what Settings › Library and the first-run setup call it, its icon, the
 * picker's request code, how many it takes, and where its list is kept. A later kind (the frontend folder of issue #475, an amiibo folder) is one more entry
 * in FolderKinds with a store of its own and, where it acts on a change, its {@link Hooks}.
 */
public final class FolderKind {
    /** Where a kind's list is kept (PeanutsPrefs) */
    public interface Store {
        List<String> read(Context c);
        void write(Context c, List<String> dirs);
    }

    /** What a kind does beyond keeping its list; each step has a default that only keeps the list */
    public interface Hooks {
        /** The folder was added to the list (a kind that fills it, or needs All files access for it, says so here); done: the sheet goes on */
        default void added(Activity a, String path, Runnable done) { done.run(); }
        /** Remove pressed for the folder: remove runs what takes it off the list (a kind that asks first, or empties what it wrote, runs it after that) */
        default void removing(Activity a, String path, Runnable remove) { remove.run(); }
        /** The folder's line under its path in Settings, or null for the path alone */
        default CharSequence state(Context c, String path) { return null; }
        /** The word on the folder sheet's remove button */
        default String removeLabel() { return "Remove"; }
        /** What the folder sheet says of its remove button, under the folder */
        default CharSequence removeNote() { return "Remove takes the folder off the list. The files in it stay where they are."; }
        /** What the folder sheet says about the folder, under its path; null for the kind's own line */
        default CharSequence sheetLine(Context c, String path) { return null; }
        /** Settings › Library built its rows: refresh redraws them, for a kind whose state line changes by itself (a folder it fills in the background) */
        default void rows(Activity a, Runnable refresh) {}
        /** The launcher is in front again (LibrarySettings.resume: back from Android's All files access page, say) */
        default void resumed(Activity a) {}
    }

    /** No hooks: the list is all there is */
    public static final Hooks PLAIN = new Hooks() {};

    /** key: for the control interface and tests; title: a folder's row ("Game folder"); label: the Add sheet's choice ("Games"); icon: a pi_* drawable's name;
     *  line: the Add sheet's line for the kind; about: what the folder sheet says of a folder of this kind; pick: the picker's toast */
    public final String key, title, label, icon, line, about, pick;
    /** The folder picker's request code (MainActivity.onActivityResult to LibrarySettings.result) */
    public final int request;
    /** How many folders the kind takes: 0 any number */
    public final int max;
    /** The first folder is the default (builds: where a new build goes unless a game's sheet picks another) */
    public final boolean firstIsDefault;
    final Store store;
    public final Hooks hooks;

    public FolderKind(String key, String title, String label, String icon, String line, String about, String pick, int request, int max, boolean firstIsDefault,
                      Store store, Hooks hooks) {
        this.key = key; this.title = title; this.label = label; this.icon = icon; this.line = line; this.about = about; this.pick = pick;
        this.request = request; this.max = max; this.firstIsDefault = firstIsDefault; this.store = store; this.hooks = hooks != null ? hooks : PLAIN;
    }

    /** The kind's folders, in their order */
    public List<String> list(Context c) { return store.read(c); }

    /** The kind's folders from now on */
    public void save(Context c, List<String> dirs) { store.write(c, dirs); }

    /** Whether the kind takes no more folders */
    public boolean full(Context c) { return max > 0 && list(c).size() >= max; }
}
