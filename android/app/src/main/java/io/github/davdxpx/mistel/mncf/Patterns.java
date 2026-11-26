package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shortest byte pattern that finds one place in a module exactly once (section 6.3).
 *
 * <p>The bytes that change when the code moves in another build (branch and page offsets) become
 * ??, so that a pattern keeps finding its place after an update as long as the code around it
 * stays the same.
 */
final class Patterns {

    private static final int STEP = 4;
    /** Candidates left after which a search looks at its last window first. */
    private static final int CROWD = 64;

    private Patterns() {
    }

    /** A pattern: its items (-1 for ??), where it matches, and the place's offset from the match. */
    static final class Found {
        final int[] items;
        final long start;
        final long place;

        Found(int[] items, long start, long place) {
            this.items = items;
            this.start = start;
            this.place = place;
        }

        String text() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    b.append(' ');
                }
                b.append(items[i] < 0 ? "??" : String.format("%02X", items[i]));
            }
            return b.toString();
        }

        /** The location of the place: the module, the pattern, and the offset unless it is 0. */
        Map<String, Object> location(String module) {
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("module", module);
            loc.put("find", text());
            if (place != 0) {
                loc.put("offset", place);
            }
            return loc;
        }
    }

    /** The default search: code and read-only data, cut where the image ends. */
    private static List<long[]> searchRanges(CodeModule m) {
        long size = m.image().length;
        List<long[]> ranges = new ArrayList<>();
        for (String n : new String[] {"code", "rodata"}) {
            if (m.hasSection(n)) {
                ranges.add(new long[] {Math.min(m.sectionStart(n), size), Math.min(m.sectionEnd(n), size)});
            }
        }
        return ranges;
    }

    /** The window's bytes, -1 where they move between builds or are not the module's own. */
    private static int[] items(CodeModule m, int start, int end, Set<Integer> moving) {
        int[] out = new int[end - start];
        byte[] image = m.image();
        for (int i = start; i < end; i++) {
            out[i - start] = moving.contains(i) || m.excluded(i, 1) ? -1 : image[i] & 0xFF;
        }
        return out;
    }

    /** A word of fixed bytes of the window: its distance from the place, its offset and its bytes. */
    private static final class Anchor {
        final long distance;
        final long start;
        final byte[] piece;

        Anchor(long distance, long start, byte[] piece) {
            this.distance = distance;
            this.start = start;
            this.piece = piece;
        }
    }

    /** The words of fixed bytes in the window, aligned as the place is, nearest the place first. */
    private static List<Anchor> anchors(int[] items, long base, long site) {
        int first = (int) Math.floorMod(site - base, (long) STEP);
        List<Anchor> words = new ArrayList<>();
        for (int i = first; i < items.length - STEP + 1; i += STEP) {
            byte[] piece = new byte[STEP];
            boolean fixed = true;
            for (int k = 0; k < STEP; k++) {
                fixed &= items[i + k] >= 0;
                piece[k] = (byte) items[i + k];
            }
            if (fixed) {
                words.add(new Anchor(Math.abs(base + i - site), base + i, piece));
            }
        }
        Collections.sort(words, new Comparator<Anchor>() {
            @Override
            public int compare(Anchor a, Anchor b) {
                int c = Long.compare(a.distance, b.distance);
                return c != 0 ? c : Long.compare(a.start, b.start);
            }
        });
        return words;
    }

    /** Where the needle lies in the ranges, overlapping, or null when it lies there more than limit times. */
    private static List<Long> occurrences(byte[] image, byte[] needle, List<long[]> ranges, int limit) {
        int k = needle.length;
        int[] shift = new int[256];
        Arrays.fill(shift, k);
        for (int i = 0; i < k - 1; i++) {
            shift[needle[i] & 0xFF] = k - 1 - i;
        }
        List<Long> found = new ArrayList<>();
        for (long[] r : ranges) {
            int start = (int) Math.max(0, Math.min(r[0], image.length));
            int end = (int) Math.max(0, Math.min(r[1], image.length));
            // A Horspool scan: the shift after any window, a match too, never skips a place.
            for (int at = start; at + k <= end; at += shift[image[at + k - 1] & 0xFF]) {
                boolean same = true;
                for (int i = k - 1; i >= 0 && same; i--) {
                    same = image[at + i] == needle[i];
                }
                if (same) {
                    found.add((long) at);
                    if (found.size() > limit) {
                        return null;
                    }
                }
            }
        }
        return found;
    }

    /**
     * The shortest pattern that finds an offset exactly once and covers the first cover bytes of
     * the place, or null when the place is not in code or read-only data, or no pattern up to 64
     * bytes is unique.
     */
    static Found make(CodeModule m, long offset, int cover) {
        int longest = 64;
        int limit = 200000;
        String section = m.sectionOf(offset);
        if (!"code".equals(section) && !"rodata".equals(section)) {
            return null;
        }
        long secStart = m.sectionStart(section);
        long secEnd = Math.min(m.sectionEnd(section), m.image().length);
        if (section.equals("code") && m.textSize() != null) {
            if (offset >= m.textSize()) {
                return null;                      // the zero bytes after the code: nothing there to find
            }
            secEnd = Math.min(secEnd, m.textSize());
        }
        byte[] image = m.image();
        int lo = (int) Math.max(secStart, offset - longest);
        int hi = (int) Math.min(secEnd, offset + longest);
        Set<Integer> moving = Arm64.movingBytes(image, lo, hi);
        int[] window = items(m, lo, hi, moving);
        cover = (int) Math.max(1, Math.min(cover, hi - offset));
        List<long[]> ranges = searchRanges(m);
        Found best = null;
        List<Anchor> all = anchors(window, lo, offset);
        for (Anchor a : all.subList(0, Math.min(3, all.size()))) {
            List<Long> hits = occurrences(image, a.piece, ranges, limit);
            if (hits == null) {
                continue;
            }
            for (String order : new String[] {"forward", "backward", "both"}) {
                Found found = grow(image, offset, cover, lo, hi, window, a.start, hits, ranges, order, longest);
                if (found != null && (best == null || found.items.length < best.items.length
                        || (found.items.length == best.items.length && Math.abs(found.place) < Math.abs(best.place)))) {
                    best = found;
                }
            }
        }
        if (best == null) {
            return null;
        }
        List<Long> check = Evaluator.findAll(m.toModuleWithoutId(), best.items, null, 2);
        if (!check.equals(Collections.singletonList(best.start))) {
            return null;
        }
        return best;
    }

    private static Found grow(byte[] image, long offset, int cover, int lo, int hi, int[] window, long a0,
            List<Long> hits, List<long[]> ranges, String order, int longest) {
        long start = Math.min(offset, a0);
        if (offset % STEP == 0) {
            start -= Math.floorMod(start, (long) STEP);
        }
        long end = Math.max(offset + cover, a0 + 1);
        end = Math.min(hi, start + ((end - start + STEP - 1) / STEP) * STEP);
        List<Long> alive = null;
        long[] verified = null;     // the module offsets of the pattern whose bytes every candidate was checked for
        boolean lookedAhead = false;
        int toggle = 0;
        while (true) {
            int[] items = slice(window, start - lo, end - lo);
            if (items.length > 0 && items[0] >= 0 && items[items.length - 1] >= 0
                    && start <= a0 && a0 < end && end - start >= 4) {
                long shift = a0 - start;
                if (alive == null) {
                    alive = new ArrayList<>();
                    for (long h : hits) {
                        alive.add(h - shift);
                    }
                }
                boolean whole = items.length == end - start;
                // The pattern only grows: a candidate needs checking only for the bytes added since it was checked.
                int doneFrom = 0;
                int doneTo = 0;
                if (verified != null && whole) {
                    doneFrom = (int) (verified[0] - start);
                    doneTo = (int) (verified[1] - start);
                }
                List<Long> kept = new ArrayList<>();
                for (long q : alive) {
                    if (matches(image, q, items, ranges, doneFrom, doneTo)) {
                        kept.add(q);
                    }
                }
                alive = kept;
                verified = whole ? new long[] {start, end} : null;
                if (alive.size() == 1 && alive.get(0) == start) {
                    return new Found(items, start, offset - start);
                }
                if (alive.size() > CROWD && !lookedAhead) {
                    // Many candidates are left. Every later window lies inside the last one this order grows to,
                    // so where two candidates match that one, none of the windows before it can be unique.
                    lookedAhead = true;
                    long[] last = lastWindow(start, end, lo, hi, order, longest, toggle);
                    int[] lastItems = slice(window, last[0] - lo, last[1] - lo);
                    if (lastItems.length == last[1] - last[0]) {
                        int matching = 0;
                        for (long q : alive) {
                            if (matches(image, q - (start - last[0]), lastItems, ranges, 0, 0) && ++matching == 2) {
                                return null;
                            }
                        }
                    }
                }
            }
            boolean growBack = start - STEP >= lo;
            boolean growAhead = end + STEP <= hi;
            if (end - start >= longest || !(growBack || growAhead)) {
                return null;
            }
            boolean ahead;
            if (order.equals("forward")) {
                ahead = growAhead;
            } else if (order.equals("backward")) {
                ahead = !growBack;
            } else {
                ahead = growAhead && (toggle % 2 == 0 || !growBack);
                toggle++;
            }
            if (ahead) {
                end += STEP;
            } else {
                start -= STEP;
                if (alive != null) {
                    List<Long> moved = new ArrayList<>();
                    for (long q : alive) {
                        moved.add(q - STEP);
                    }
                    alive = moved;
                }
            }
        }
    }

    /** Python's list slice: indices below 0 count from the end, and both ends stay inside the list. */
    private static int[] slice(int[] list, long from, long to) {
        int n = list.length;
        long a = from < 0 ? Math.max(0, from + n) : Math.min(from, n);
        long b = to < 0 ? Math.max(0, to + n) : Math.min(to, n);
        return b <= a ? new int[0] : Arrays.copyOfRange(list, (int) a, (int) b);
    }

    /** The window that growing from (start, end) in this order ends with. */
    private static long[] lastWindow(long start, long end, int lo, int hi, String order, int longest, int toggle) {
        while (true) {
            boolean growBack = start - STEP >= lo;
            boolean growAhead = end + STEP <= hi;
            if (end - start >= longest || !(growBack || growAhead)) {
                return new long[] {start, end};
            }
            boolean ahead;
            if (order.equals("forward")) {
                ahead = growAhead;
            } else if (order.equals("backward")) {
                ahead = !growBack;
            } else {
                ahead = growAhead && (toggle % 2 == 0 || !growBack);
                toggle++;
            }
            if (ahead) {
                end += STEP;
            } else {
                start -= STEP;
            }
        }
    }

    /** Whether the pattern matches at q, inside the search ranges; the items from doneFrom to doneTo are known to. */
    private static boolean matches(byte[] image, long q, int[] items, List<long[]> ranges, int doneFrom, int doneTo) {
        int n = items.length;
        boolean inside = false;
        for (long[] r : ranges) {
            inside |= r[0] <= q && q + n <= r[1];
        }
        if (!inside || q < 0 || q + n > image.length) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (i == doneFrom && doneTo > doneFrom) {
                i = doneTo - 1;
                continue;
            }
            if (items[i] >= 0 && (image[(int) q + i] & 0xFF) != items[i]) {
                return false;
            }
        }
        return true;
    }
}
