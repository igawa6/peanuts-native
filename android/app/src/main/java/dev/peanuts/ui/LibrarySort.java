package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Home's Sort (Library): the orders the player can pick, what each sorts by, and how All puts the filters' lists together. Plain Java, no Android, so
 * android/app/src/jvmtest's LibrarySortJvmTest checks it (dm/tools/tests/test_android_librarysort.py). The choice is kept in PeanutsPrefs.librarySort.
 */
final class LibrarySort {
    private LibrarySort() {}

    static final String LAST = "last", AZ = "az", ADDED = "added", TIME = "time";
    static final String[] MODES = {LAST, AZ, ADDED, TIME};
    static final String[] LABELS = {"Last played", "Name A–Z", "Recently added", "Play time"};
    static final String[] HINTS = {"Most recent first", null, "Newest in the library first", "Most hours first"};

    /** The mode's place in MODES; an unknown or missing one is Last played (the default) */
    static int index(String mode) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    static String label(String mode) { return LABELS[index(mode)]; }

    /**
     * The order for a mode: Last played, Recently added and Play time put the largest value first; equal values (a game never played, two at once) go A–Z by
     * name, ignoring case. Name A–Z is that alone.
     */
    static <T> Comparator<T> order(String mode, Function<T, String> name, ToLongFunction<T> lastPlayed, ToLongFunction<T> added, ToLongFunction<T> playMs) {
        Comparator<T> az = (x, y) -> name.apply(x).compareToIgnoreCase(name.apply(y));
        switch (MODES[index(mode)]) {
            case AZ: return az;
            case ADDED: return Comparator.<T>comparingLong(added).reversed().thenComparing(az);
            case TIME: return Comparator.<T>comparingLong(playMs).reversed().thenComparing(az);
            default: return Comparator.<T>comparingLong(lastPlayed).reversed().thenComparing(az);
        }
    }

    /**
     * What All shows: the building games first, as they come, then the rest in the order. Grouped (Keep … apart, the default): Ready, then Build needed,
     * then Not Supported Yet, each sorted; otherwise one list. Sorts ready, build and notSupported in place, as the filters show them too.
     */
    static <T> List<T> all(List<T> building, List<T> ready, List<T> build, List<T> notSupported, Comparator<T> order, boolean grouped) {
        ready.sort(order);
        build.sort(order);
        notSupported.sort(order);
        List<T> rest = new ArrayList<>(ready.size() + build.size() + notSupported.size());
        rest.addAll(ready);
        rest.addAll(build);
        rest.addAll(notSupported);
        if (!grouped) rest.sort(order);
        List<T> out = new ArrayList<>(building.size() + rest.size());
        out.addAll(building);
        out.addAll(rest);
        return out;
    }

    /**
     * When a game counts as added, the first time the library sees it: the time of its file (the .nup, dump folder or base file) when that is known and not
     * in the future, so a library from before Sort gets an order that means something; else now. Kept after that, so importing a game again does not move it.
     */
    static long firstSeen(long fileTime, long now) { return fileTime > 0 && fileTime <= now ? fileTime : now; }
}
