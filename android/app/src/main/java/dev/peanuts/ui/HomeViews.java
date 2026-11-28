package dev.peanuts.ui;

import dev.peanuts.launch.PlayLedger;

import java.util.Calendar;
import java.util.List;

/**
 * Home's five views (the View button, Settings › App › Look › Home view) and the arithmetic they share, with no Android class (HomeViewsJvmTest):
 * Grid (the covers with their names, as before), Row (one row of covers, the chosen one large at the left), Compact (a list: a small cover, the name, the
 * state in words, the play time), Carousel (the chosen cover large in the middle, its neighbours beside it) and Covers (many small covers without names).
 * Library draws them; HomeShelf is Row's and Carousel's strip.
 */
final class HomeViews {
    private HomeViews() {}

    static final int GRID = 0, ROW = 1, COMPACT = 2, CAROUSEL = 3, COVERS = 4, COUNT = 5;
    /** The stored word of each view (PeanutsPrefs.homeView, the control interface's nav view=) */
    static final String[] KEYS = {"grid", "row", "compact", "carousel", "covers"};
    static final String[] NAMES = {"Grid", "Row", "Compact", "Carousel", "Covers"};
    /** Each view's Peanuts icon (Icons.get): Grid is the library's four squares, Compact the list */
    static final String[] ICONS = {"library", "view_row", "list", "view_carousel", "view_covers"};

    /** The view a stored word names; Grid for none or one this version does not know */
    static int of(String key) {
        if (key != null) for (int i = 0; i < COUNT; i++) if (KEYS[i].equals(key)) return i;
        return GRID;
    }

    static int clamp(int view) { return view < 0 || view >= COUNT ? GRID : view; }

    /** The View button's press: the next view, Covers back to Grid */
    static int next(int view) { return (clamp(view) + 1) % COUNT; }

    /** Row and Carousel show one strip of covers (HomeShelf) */
    static boolean shelf(int view) { return view == ROW || view == CAROUSEL; }

    /** A phone held upright: Find, Sort, View and Build all sit in the dock at the bottom. A tablet, or a phone on its side, keeps them in the header */
    static boolean dock(int widthDp, int heightDp, int smallestWidthDp) { return smallestWidthDp < 600 && heightDp > widthDp; }

    /** Covers a row: Grid four on a handheld or a tablet, three on a phone on its side, two upright; Covers six, five, four; Compact two, or one upright */
    static int columns(int view, int widthDp) {
        switch (clamp(view)) {
            case COVERS: return widthDp >= 700 ? 6 : widthDp >= 540 ? 5 : 4;
            case COMPACT: return widthDp >= 540 ? 2 : 1;
            case ROW: case CAROUSEL: return 1;
            default: return widthDp >= 700 ? 4 : widthDp >= 540 ? 3 : 2;
        }
    }

    /** What a wide screen keeps above the strip, in dp (Configuration.screenHeightDp has no status bar): the header with its buttons' words, the tabs.
     *  A first guess: Library fits the cover to the room the page really leaves once it is laid out */
    static final int ABOVE = 148;

    /**
     * The large cover of Row and Carousel, in dp: on a screen wider than high about a third of the width, no more than the screen leaves under the header and
     * the tabs (ABOVE) and over what stands under the cover (belowDp: its name and state with a bottom screen; on one screen also the play time and the
     * buttons, or only the buttons when the facts stand beside it); upright most of the width (the next cover looks in at the edge, the page scrolls).
     * contentDp: the page's width less its gutters
     */
    static int bigCover(int view, int contentDp, int screenHeightDp, boolean upright, int belowDp) {
        if (upright) return Math.round(contentDp * (view == CAROUSEL ? 0.80f : 0.77f));
        int byWidth = Math.round(contentDp * (view == CAROUSEL ? 0.30f : 0.31f));
        return Math.max(96, Math.min(byWidth, screenHeightDp - ABOVE - belowDp));
    }

    /** What stands under the large cover on a wide screen, in dp: name and state (a bottom screen has the rest); on one screen Row has its facts beside the
     *  cover and only the buttons under it, Carousel all of it under it */
    static int below(int view, boolean twoScreens) {
        if (twoScreens) return 62;
        return view == CAROUSEL ? 88 : 54;
    }

    /**
     * The large cover once the stage is laid out (Library.fit), in px: the stage's room less what stands under the cover, no larger than widest (a share of
     * the width) and no smaller than least; so the strip, its facts and the key bar always fit the window and the page never scrolls
     */
    static int stageCover(int roomPx, int widestPx, int leastPx) { return Math.max(leastPx, Math.min(widestPx, roomPx)); }

    /** The smaller covers beside it */
    static int smallCover(int view, int big) { return Math.round(big * (view == CAROUSEL ? 0.69f : 0.67f)); }

    // ---- The strip's places (HomeShelf), d: how many games after the chosen one a cover is, fractional while it moves ----

    /** A cover's size: the chosen one large, the others small, in between while it moves */
    static float size(float d, float big, float small) {
        float t = Math.min(1f, Math.abs(d));
        return big + (small - big) * t;
    }

    /** Row: a cover's left edge from the strip's left edge. The chosen one at 0, the next ones after it, the ones before it out to the left */
    static float rowX(float d, float big, float small, float gap) {
        if (d >= 1) return big + gap + (d - 1) * (small + gap);
        if (d >= 0) return d * (big + gap);
        return d * (small + gap);
    }

    /** Carousel: a cover's centre from the strip's centre */
    static float carouselX(float d, float big, float small, float gap) {
        float a = Math.abs(d), near = (big + small) / 2 + gap;
        return a <= 1 ? d * near : Math.signum(d) * (near + (a - 1) * (small + gap));
    }

    /**
     * Where a swipe lands: the place it was let go at, carried on by its speed (games a second, positive towards the later ones) for 120 ms, rounded; a flick
     * moves one game at least. Never past the first or the last: the strip has ends (no circle)
     */
    static int settle(float pos, float velocity, int count) {
        if (count <= 0) return 0;
        int at = Math.round(pos), i = Math.round(pos + velocity * 0.12f);
        if (i == at && Math.abs(velocity) > 1.2f) i += velocity > 0 ? 1 : -1;
        return Math.max(0, Math.min(count - 1, i));
    }

    /** The place of a game in the list shown, 0 when it is not there (it left with a tab or Find: the first game is chosen) */
    static int indexOf(List<String> ids, String id) {
        if (id == null) return 0;
        for (int i = 0; i < ids.size(); i++) if (ids.get(i).equalsIgnoreCase(id)) return i;
        return 0;
    }

    // ---- Words ----

    /** "Played today", "Played yesterday", "Played 3 days ago", "Played on <date>"; "Not played" when never */
    static String played(long at, long now) {
        if (at <= 0) return "Not played";
        Calendar then = Calendar.getInstance(), today = Calendar.getInstance();
        then.setTimeInMillis(at);
        today.setTimeInMillis(now);
        int days = days(then, today);
        if (days <= 0) return "Played today";
        if (days == 1) return "Played yesterday";
        if (days < 7) return "Played " + days + " days ago";
        return "Played on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(at));
    }

    private static int days(Calendar a, Calendar b) {
        Calendar x = (Calendar) a.clone(), y = (Calendar) b.clone();
        for (Calendar k : new Calendar[] {x, y}) { k.set(Calendar.HOUR_OF_DAY, 12); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0); }
        return (int) Math.round((y.getTimeInMillis() - x.getTimeInMillis()) / 86_400_000d);
    }

    /** Compact's right column: "3 h 43 min", "Not played" */
    static String playShort(long ms) { return ms <= 0 ? "Not played" : PlayLedger.text(ms); }

    /** The play time as one quiet line: {"3 h 43 min", " played", "7 min", " last time"}, the numbers bold; the last pair empty when the last run is not known */
    static String[] playLine(long ms, long lastRun) {
        if (ms <= 0) return new String[] {"", "", "", ""};
        return new String[] {PlayLedger.text(ms), " played", lastRun > 0 ? PlayLedger.text(lastRun) : "", lastRun > 0 ? " last time" : ""};
    }

    /** The NO SUPPORT tape's letters in dp: they grow with the cover, 9 to 12; 0 under 72 dp, where the tape has only peanuts and the row says it in words */
    static float tapeText(float coverDp) {
        if (coverDp < 72) return 0;
        return Math.max(9f, Math.min(12f, coverDp * 0.075f));
    }
}
