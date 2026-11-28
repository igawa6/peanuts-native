// PEANUTS: HomeViews on the JVM (peanuts/tests/test_home_views.py); prints "ok <n>" or exits 1 naming the first case that differs
package dev.peanuts.ui;

import static dev.peanuts.ui.HomeViews.CAROUSEL;
import static dev.peanuts.ui.HomeViews.COMPACT;
import static dev.peanuts.ui.HomeViews.COVERS;
import static dev.peanuts.ui.HomeViews.GRID;
import static dev.peanuts.ui.HomeViews.ROW;

import java.util.Arrays;
import java.util.List;

/** Home's views: the stored word, the View button's order, the columns, the dock, the strip's places and where a swipe lands, the words */
public final class HomeViewsJvmTest {
    static int n;

    static void check(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { System.out.println(what + ": got " + got + ", want " + want); System.exit(1); }
    }

    public static void main(String[] args) {
        // the stored word
        check("none: Grid", HomeViews.of(null), GRID);
        check("unknown (a newer version's): Grid", HomeViews.of("shelves"), GRID);
        for (int v = 0; v < HomeViews.COUNT; v++) check("round trip " + v, HomeViews.of(HomeViews.KEYS[v]), v);
        check("five names", HomeViews.NAMES.length + "," + HomeViews.ICONS.length, "5,5");

        // the View button: every view once, then Grid again
        StringBuilder order = new StringBuilder();
        int v = GRID;
        for (int i = 0; i < 6; i++) { order.append(HomeViews.NAMES[v]).append(' '); v = HomeViews.next(v); }
        check("View presses", order.toString().trim(), "Grid Row Compact Carousel Covers Grid");
        check("a broken value goes on from Grid", HomeViews.next(9), ROW);
        check("strips", HomeViews.shelf(ROW) + "," + HomeViews.shelf(CAROUSEL) + "," + HomeViews.shelf(GRID) + "," + HomeViews.shelf(COVERS), "true,true,false,false");

        // the dock: a phone held upright only
        check("phone upright", HomeViews.dock(411, 891, 411), true);
        check("phone on its side", HomeViews.dock(891, 411, 411), false);
        check("Thor", HomeViews.dock(731, 411, 411), false);
        check("tablet upright", HomeViews.dock(800, 1280, 800), false);

        // columns
        check("Grid on the Thor, a phone on its side, upright", HomeViews.columns(GRID, 731) + "," + HomeViews.columns(GRID, 600) + "," + HomeViews.columns(GRID, 411), "4,3,2");
        check("Covers", HomeViews.columns(COVERS, 731) + "," + HomeViews.columns(COVERS, 600) + "," + HomeViews.columns(COVERS, 411), "6,5,4");
        check("Compact", HomeViews.columns(COMPACT, 731) + "," + HomeViews.columns(COMPACT, 411), "2,1");
        check("strips have one", HomeViews.columns(ROW, 731) + "," + HomeViews.columns(CAROUSEL, 411), "1,1");

        // the large cover: a third of a wide screen, no taller than the screen leaves; most of an upright one
        check("Row on the Thor, with its bottom screen", HomeViews.bigCover(ROW, 691, 411, false, HomeViews.below(ROW, true)), 201);
        check("Row on the Thor alone: the facts beside it", HomeViews.bigCover(ROW, 691, 411, false, HomeViews.below(ROW, false)), 209);
        check("Carousel on the Thor alone: everything under it", HomeViews.bigCover(CAROUSEL, 691, 411, false, HomeViews.below(CAROUSEL, false)), 175);
        check("a tall wide screen: a third of the width", HomeViews.bigCover(ROW, 1200, 900, false, 66), 372);
        check("never tiny", HomeViews.bigCover(ROW, 800, 200, false, 66), 96);
        check("Carousel upright", HomeViews.bigCover(CAROUSEL, 371, 891, true, 0), 297);
        check("small beside it", HomeViews.smallCover(ROW, 214), 143);
        // once the stage is laid out: its room decides, within a share of the width and a least size
        check("the stage's room", HomeViews.stageCover(420, 560, 252), 420);
        check("a wide stage: the width's share", HomeViews.stageCover(900, 560, 252), 560);
        check("a cramped window: the least size, the page may scroll then", HomeViews.stageCover(120, 560, 252), 252);

        // the strip's places
        check("chosen: large", HomeViews.size(0, 200, 100), 200.0);
        check("half way", HomeViews.size(-0.5f, 200, 100), 150.0);
        check("far: small", HomeViews.size(3, 200, 100), 100.0);
        check("Row: chosen at the left edge", HomeViews.rowX(0, 200, 100, 10), 0.0);
        check("Row: the next after it", HomeViews.rowX(1, 200, 100, 10), 210.0);
        check("Row: the one after", HomeViews.rowX(2, 200, 100, 10), 320.0);
        check("Row: the one before, out to the left", HomeViews.rowX(-1, 200, 100, 10), -110.0);
        check("Row: moving on is continuous", HomeViews.rowX(0.999f, 200, 100, 10) > 209.7f && HomeViews.rowX(1.001f, 200, 100, 10) < 210.2f, true);
        check("Carousel: centred", HomeViews.carouselX(0, 200, 100, 10), 0.0);
        check("Carousel: neighbours", HomeViews.carouselX(1, 200, 100, 10) + "," + HomeViews.carouselX(-1, 200, 100, 10), "160.0,-160.0");
        check("Carousel: two away", HomeViews.carouselX(2, 200, 100, 10), 270.0);

        // a swipe
        check("let go near where it was", HomeViews.settle(2.2f, 0, 7), 2);
        check("let go past half", HomeViews.settle(2.6f, 0, 7), 3);
        check("a flick moves one", HomeViews.settle(2.1f, 2f, 7), 3);
        check("a flick back", HomeViews.settle(2.1f, -2f, 7), 1);
        check("a quick flick moves one", HomeViews.settle(2.2f, 4.5f, 7), 3);
        check("a hard fling moves more", HomeViews.settle(2f, 20f, 7), 4);
        check("a very hard one, to the end", HomeViews.settle(2f, 40f, 7), 6);
        check("never past the end", HomeViews.settle(6.3f, 9f, 7), 6);
        check("nor before the first", HomeViews.settle(-0.3f, -5f, 7), 0);
        check("an empty strip", HomeViews.settle(0, 3, 0), 0);
        check("a slow drag is no flick", HomeViews.settle(2.1f, 0.6f, 7), 2);

        // the chosen game kept, else the first
        List<String> ids = Arrays.asList("0100A", "0100B", "0100C");
        check("kept", HomeViews.indexOf(ids, "0100c"), 2);
        check("gone with a tab", HomeViews.indexOf(ids, "0100D"), 0);
        check("none", HomeViews.indexOf(ids, null), 0);

        // words
        long now = System.currentTimeMillis();
        check("never", HomeViews.played(0, now), "Not played");
        check("today", HomeViews.played(now - 60_000, now), "Played today");
        check("yesterday", HomeViews.played(now - 86_400_000L, now), "Played yesterday");
        check("days", HomeViews.played(now - 3 * 86_400_000L, now), "Played 3 days ago");
        check("play time", String.join("|", HomeViews.playLine(13_380_000L, 420_000L)), "3 h 43 min| played|7 min| last time");
        check("no last run", String.join("|", HomeViews.playLine(720_000L, 0)), "12 min| played||");
        check("never played", HomeViews.playShort(0) + "," + String.join("", HomeViews.playLine(0, 0)).isEmpty(), "Not played,true");
        check("tape letters grow", HomeViews.tapeText(159) + "," + HomeViews.tapeText(120) + "," + HomeViews.tapeText(82), "11.925,9.0,9.0");
        check("tape under 72 dp: peanuts only", HomeViews.tapeText(56), 0.0);
        check("tape at most 12", HomeViews.tapeText(300), 12.0);

        System.out.println("ok " + n);
    }
}
