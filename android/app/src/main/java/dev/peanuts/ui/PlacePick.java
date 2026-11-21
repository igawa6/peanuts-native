package dev.peanuts.ui;

import java.util.List;

/**
 * Which row of a rebuilt page is the one a controller was on (PagePlace): rows are new views after every build, so the row is found again by its words
 * (its first text, the title of a settings row) and which of the rows with those words it was, else by its place in the focus order. No Android types
 * (PagePlaceJvmTest).
 */
final class PlacePick {
    private PlacePick() {}

    /** Which of the rows with the same words as row {@code index} it is (0: the first) */
    static int nth(List<String> words, int index) {
        if (index < 0 || index >= words.size()) return -1;
        String w = words.get(index);
        int n = 0;
        for (int i = 0; i < index; i++) if (same(w, words.get(i))) n++;
        return n;
    }

    /**
     * The row to focus on the rebuilt page: the nth row with these words; when the words are not there (empty, or changed), the row at the same
     * place in the focus order, clamped to the last; -1 when the page has no row or none was focused
     */
    static int pick(List<String> words, String word, int nth, int index) {
        if (words.isEmpty() || index < 0) return -1;
        if (word != null && !word.isEmpty()) {
            int seen = 0, last = -1;
            for (int i = 0; i < words.size(); i++) {
                if (!same(word, words.get(i))) continue;
                if (seen++ == nth) return i;
                last = i;
            }
            if (last >= 0) return last;   // fewer rows with these words now: the last of them
        }
        return Math.min(index, words.size() - 1);
    }

    private static boolean same(String a, String b) { return a != null && !a.isEmpty() && a.equals(b); }
}
