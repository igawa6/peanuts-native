package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** LibrarySort (Home's Sort) without Android: prints "ok", or exits 1 naming the first case that differs. Run by dm/tools/tests/test_android_librarysort.py */
public final class LibrarySortJvmTest {
    /** A game as Home sorts it: name, last played, first seen, play time */
    static final class G {
        final String name; final long used, added, ms;
        G(String name, long used, long added, long ms) { this.name = name; this.used = used; this.added = added; this.ms = ms; }
        @Override public String toString() { return name; }
    }

    static Comparator<G> order(String mode) { return LibrarySort.order(mode, g -> g.name, g -> g.used, g -> g.added, g -> g.ms); }

    static String names(List<G> l) { return l.stream().map(g -> g.name).collect(Collectors.joining(",")); }

    static List<G> sorted(String mode, G... gs) { List<G> l = new ArrayList<>(Arrays.asList(gs)); l.sort(order(mode)); return l; }

    static void check(String what, String got, String want) {
        if (!got.equals(want)) { System.out.println(what + ": got " + got + ", want " + want); System.exit(1); }
    }

    public static void main(String[] args) {
        G kirby = new G("Kirby", 300, 10, 0), bananza = new G("bananza", 0, 30, 5000), cart = new G("Cart", 0, 20, 5000), toad = new G("Toad", 100, 20, 9000);

        // the modes and their defaults
        check("index of an unknown mode", String.valueOf(LibrarySort.index("nope")), "0");
        check("index of null", String.valueOf(LibrarySort.index(null)), "0");
        check("index of time", String.valueOf(LibrarySort.index(LibrarySort.TIME)), "3");
        check("label of az", LibrarySort.label("az"), "Name A–Z");
        check("one label and hint slot per mode", LibrarySort.LABELS.length + "," + LibrarySort.HINTS.length, LibrarySort.MODES.length + "," + LibrarySort.MODES.length);

        // each order; ties and games without a value go A–Z, ignoring case
        check("last played", names(sorted("last", bananza, cart, kirby, toad)), "Kirby,Toad,bananza,Cart");
        check("unknown mode is last played", names(sorted("nope", bananza, cart, kirby, toad)), "Kirby,Toad,bananza,Cart");
        check("name A-Z ignores case", names(sorted("az", toad, kirby, cart, bananza)), "bananza,Cart,Kirby,Toad");
        check("recently added, tie A-Z", names(sorted("added", kirby, toad, cart, bananza)), "bananza,Cart,Toad,Kirby");
        check("play time, tie A-Z, none last", names(sorted("time", kirby, cart, bananza, toad)), "Toad,bananza,Cart,Kirby");

        // All: building first as given; grouped keeps Ready, Build needed, Not Supported Yet apart; otherwise one list
        G building2 = new G("Zelda", 0, 0, 0), building1 = new G("Arms", 0, 0, 0);
        G buildNeeded = new G("Astro", 0, 99, 0), notSupported = new G("Alpha", 0, 98, 0);
        List<G> bl = new ArrayList<>(Arrays.asList(building2, building1));
        List<G> rd = new ArrayList<>(Arrays.asList(toad, kirby)), bd = new ArrayList<>(Arrays.asList(buildNeeded)), np = new ArrayList<>(Arrays.asList(notSupported));
        check("all, grouped", names(LibrarySort.all(bl, rd, bd, np, order("az"), true)), "Zelda,Arms,Kirby,Toad,Astro,Alpha");
        check("the filters' lists are sorted too", names(rd), "Kirby,Toad");
        rd = new ArrayList<>(Arrays.asList(toad, kirby)); bd = new ArrayList<>(Arrays.asList(buildNeeded)); np = new ArrayList<>(Arrays.asList(notSupported));
        check("all, one list", names(LibrarySort.all(bl, rd, bd, np, order("az"), false)), "Zelda,Arms,Alpha,Astro,Kirby,Toad");
        rd = new ArrayList<>(Arrays.asList(toad, kirby)); bd = new ArrayList<>(Arrays.asList(buildNeeded)); np = new ArrayList<>(Arrays.asList(notSupported));
        check("all, one list by recently added", names(LibrarySort.all(bl, rd, bd, np, order("added"), false)), "Zelda,Arms,Astro,Alpha,Toad,Kirby");
        check("all, empty", names(LibrarySort.all(new ArrayList<G>(), new ArrayList<G>(), new ArrayList<G>(), new ArrayList<G>(), order("last"), true)), "");

        // first seen: the file's time when known and not in the future, else now
        check("first seen from the file", String.valueOf(LibrarySort.firstSeen(500, 1000)), "500");
        check("first seen, no file time", String.valueOf(LibrarySort.firstSeen(0, 1000)), "1000");
        check("first seen, file time in the future", String.valueOf(LibrarySort.firstSeen(2000, 1000)), "1000");

        System.out.println("ok");
    }
}
