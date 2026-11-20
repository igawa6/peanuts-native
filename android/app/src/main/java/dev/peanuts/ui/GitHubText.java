package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text from GitHub (a release's notes, a short README part): Markdown with some HTML in it, as blocks of plain text with style ranges, which
 * GitHubTextStyle draws. It reads the way GitHub shows a release: a line break stays a line break, a bare https address is a link. Headings, lists
 * (-, *, + and the • some authors type), numbered lists, quotes, GitHub's notes and warnings (> [!WARNING]), code, tables, bold, italic, struck text
 * and links are kept. Pictures and badges are left out (a badge repeats the name the text says), so are rules and decorative lines (---, ━━ ×✕× ━━).
 * No Android classes, so it runs on the JVM.
 */
public final class GitHubText {
    private GitHubText() {}

    public enum Kind { HEADING, PARAGRAPH, BULLET, NUMBER, QUOTE, ALERT, CODE, ROW }
    public enum Style { BOLD, ITALIC, CODE, STRIKE, LINK }

    /** A style over text[start, end); url for a LINK (https only) */
    public static final class Span {
        public final Style style; public final int start, end; public final String url;
        Span(Style style, int start, int end, String url) { this.style = style; this.start = start; this.end = end; this.url = url; }
    }

    /**
     * One block. level: a heading's 1 to 6, a list item's depth (0 at the top). label: a numbered item's "1.", an alert's "Warning", "head" on a
     * table's first row. A ROW's cells are split by tabs, a CODE block keeps its lines as written
     */
    public static final class Block {
        public final Kind kind; public final int level; public final String label, text; public final List<Span> spans;
        Block(Kind kind, int level, String label, String text, List<Span> spans) { this.kind = kind; this.level = level; this.label = label; this.text = text; this.spans = spans; }
    }

    private static final int MAX = 20000;   // longer notes are cut (GitHub allows 125,000 characters)
    private static final Pattern FENCE = Pattern.compile(" {0,3}(```|~~~).*");
    private static final Pattern HEADING = Pattern.compile(" {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*");
    private static final Pattern BULLET = Pattern.compile("(\\s*)(?:[-*+]\\s+|[•◦▪‣●]\\s*)(.*)");
    private static final Pattern NUMBER = Pattern.compile("(\\s*)(\\d{1,9})([.)])\\s+(.*)");
    private static final Pattern QUOTE = Pattern.compile(" {0,3}>\\s?(.*)");
    private static final Pattern ALERT = Pattern.compile("\\s*\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_LINE = Pattern.compile("\\s*:?-{2,}:?\\s*");
    private static final Pattern RULE = Pattern.compile("([-*_])(\\s*\\1){2,}");
    private static final Pattern IMAGE_LINE = Pattern.compile("(?m)^[ \\t]*(!\\[[^\\]\\n]*]\\([^)\\n]*\\)[ \\t]*)+$");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?(-->|$)");
    private static final Pattern LINK_TAG = Pattern.compile("(?is)<a\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a\\s*>");
    private static final Pattern AUTOLINK = Pattern.compile("<(https://[^>\\s]+)>");
    private static final Pattern TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    private static final String ESCAPABLE = "\\`*_[]()#+-.!<>|~{}\"'";

    /** The blocks of a text, in order; none for null or empty text */
    public static List<Block> parse(String src) {
        List<Block> out = new ArrayList<>();
        if (src == null) return out;
        String s = src.length() > MAX ? src.substring(0, MAX) : src;
        String[] lines = html(s.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1);
        Open o = new Open(out);
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k], t = line.trim();
            Matcher m;
            if ((m = FENCE.matcher(line)).matches()) {   // code, as written, up to its closing fence
                o.flush();
                List<String> code = new ArrayList<>();
                while (++k < lines.length && !lines[k].trim().startsWith(m.group(1))) code.add(lines[k]);
                String c = String.join("\n", code).replaceAll("\\s+$", "");
                if (!c.trim().isEmpty()) out.add(new Block(Kind.CODE, 0, null, c, new ArrayList<>()));
                continue;
            }
            if (t.isEmpty() || rule(t)) { o.flush(); continue; }
            if (t.startsWith("|") && t.indexOf('|', 1) > 0) {   // a table: one ROW a line, the separator line under the head left out
                o.flush();
                boolean head = k + 1 < lines.length && separator(lines[k + 1]);
                for (; k < lines.length && lines[k].trim().startsWith("|"); k++) {
                    if (separator(lines[k])) continue;
                    out.add(row(cells(lines[k].trim()), head));
                    head = false;
                }
                k--;
                continue;
            }
            if ((m = QUOTE.matcher(line)).matches()) {
                String q = m.group(1).trim();
                Matcher li = BULLET.matcher(q);
                if (li.matches()) q = "• " + li.group(2).trim();   // a list in a quote: its items as lines with a dot
                if (o.kind == Kind.QUOTE || o.kind == Kind.ALERT) { o.line(q); continue; }
                o.flush();
                Matcher a = ALERT.matcher(q);
                if (a.matches()) o.start(Kind.ALERT, 0, a.group(1).substring(0, 1).toUpperCase(Locale.ROOT) + a.group(1).substring(1).toLowerCase(Locale.ROOT), a.group(2).trim());
                else o.start(Kind.QUOTE, 0, null, q);
                continue;
            }
            if ((m = HEADING.matcher(line)).matches()) { o.flush(); o.start(Kind.HEADING, m.group(1).length(), null, m.group(2)); o.flush(); continue; }
            if ((m = BULLET.matcher(line)).matches()) { o.flush(); o.start(Kind.BULLET, depth(m.group(1)), null, m.group(2).trim()); continue; }
            if ((m = NUMBER.matcher(line)).matches()) { o.flush(); o.start(Kind.NUMBER, depth(m.group(1)), m.group(2) + m.group(3), m.group(4).trim()); continue; }
            if ((o.kind == Kind.BULLET || o.kind == Kind.NUMBER) && indent(line) >= 2) { o.line(t); continue; }   // the item's next line
            if (o.kind != Kind.PARAGRAPH) { o.flush(); o.start(Kind.PARAGRAPH, 0, null, t); continue; }
            o.line(t);
        }
        o.flush();
        return out;
    }

    /** The first thing the text says, on one line of at most 140 characters (a list row's preview); "" when it says nothing */
    public static String plain(String src) { return plain(parse(src)); }

    public static String plain(List<Block> blocks) {
        for (Block b : blocks) {
            if (b.kind == Kind.CODE || b.kind == Kind.ROW) continue;
            String t = b.text.replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            return t.length() > 140 ? t.substring(0, 139).trim() + "…" : t;
        }
        return "";
    }

    /** Every block as text (tests, logs): "# " before a heading, "  " a level and "• " or "1. " before an item, "> " or "[Warning] " before a quote */
    public static String outline(List<Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Block x : blocks) {
            if (b.length() > 0) b.append('\n');
            switch (x.kind) {
                case HEADING: b.append("#".repeat(x.level)).append(' '); break;
                case BULLET: b.append("  ".repeat(x.level)).append("• "); break;
                case NUMBER: b.append("  ".repeat(x.level)).append(x.label).append(' '); break;
                case QUOTE: b.append("> "); break;
                case ALERT: b.append('[').append(x.label).append("] "); break;
                case ROW: b.append("head".equals(x.label) ? "|! " : "| "); break;
                default: break;
            }
            b.append(x.kind == Kind.ROW ? x.text.replace("\t", " | ") : x.text);
        }
        return b.toString();
    }

    // ---- Blocks ----

    /** The block being read: its lines go into text until flush() makes a Block of it */
    private static final class Open {
        final List<Block> out;
        Kind kind; int level; String label; StringBuilder text;
        Open(List<Block> out) { this.out = out; }

        void start(Kind k, int lv, String lb, String first) { kind = k; level = lv; label = lb; text = new StringBuilder(first); }

        /** A line break, as GitHub keeps it */
        void line(String t) { if (text.length() > 0) text.append('\n'); text.append(t); }

        void flush() {
            if (kind == null) return;
            Block b = block(kind, level, label, text.toString());
            if (b != null) out.add(b);
            kind = null; text = null;
        }
    }

    /** A block of Markdown text, its inline styles read; null when nothing is left to show (a paragraph of pictures). An alert keeps its label alone */
    private static Block block(Kind kind, int level, String label, String md) {
        StringBuilder t = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        inline(md, t, spans);
        int from = 0, to = t.length();
        while (from < to && Character.isWhitespace(t.charAt(from))) from++;
        while (to > from && Character.isWhitespace(t.charAt(to - 1))) to--;
        if (from == to && kind != Kind.ALERT) return null;
        List<Span> kept = new ArrayList<>();
        for (Span s : spans) {
            int a = Math.max(s.start, from) - from, e = Math.min(s.end, to) - from;
            if (e > a) kept.add(new Span(s.style, a, e, s.url));
        }
        return new Block(kind, level, label, t.substring(from, to), kept);
    }

    private static Block row(List<String> cells, boolean head) {
        StringBuilder t = new StringBuilder();
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) t.append('\t');
            inline(cells.get(i), t, spans);
        }
        return new Block(Kind.ROW, 0, head ? "head" : null, t.toString(), spans);
    }

    /** "| a | b |" → [a, b]; an escaped \| stays in its cell */
    private static List<String> cells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char x = line.charAt(i);
            if (x == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') { c.append('|'); i++; continue; }
            if (x == '|') { out.add(c.toString().trim()); c.setLength(0); continue; }
            c.append(x);
        }
        out.add(c.toString().trim());
        if (!out.isEmpty() && out.get(0).isEmpty()) out.remove(0);
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    private static boolean separator(String line) {
        String t = line.trim();
        if (!t.contains("-")) return false;
        for (String c : cells(t)) if (!TABLE_LINE.matcher(c).matches()) return false;
        return true;
    }

    /** A rule (---, ***) or a line drawn of symbols only (━━━━ ×✕× ━━━━): nothing to read */
    private static boolean rule(String t) {
        if (RULE.matcher(t).matches()) return true;
        if (t.length() < 3) return false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_SYMBOL && cp > 0x2BFF) return false;   // words, or an emoji
            i += Character.charCount(cp);
        }
        return true;
    }

    private static int indent(String line) { int k = 0; while (k < line.length() && line.charAt(k) == ' ') k++; return k; }

    /** Two spaces (or a tab) a level, as GitHub nests lists */
    private static int depth(String ws) { return Math.min(4, ws.replace("\t", "    ").length() / 2); }

    // ---- HTML ----

    /** The HTML GitHub allows in notes, as the Markdown it stands for: links, bold, code, headings, lists, line breaks. Pictures and badges go, any
     *  other tag goes and keeps its text */
    private static String html(String s) {
        s = COMMENT.matcher(s).replaceAll("");
        s = links(s);
        s = AUTOLINK.matcher(s).replaceAll("[$1]($1)");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?is)<img\\b[^>]*>", "");
        for (int n = 1; n <= 6; n++) s = s.replaceAll("(?i)<h" + n + "\\b[^>]*>", "\n\n" + "#".repeat(n) + " ");
        s = s.replaceAll("(?i)</h[1-6]\\s*>", "\n\n")
                .replaceAll("(?i)</?(?:b|strong)(?:\\s[^>]*)?>", "**")
                .replaceAll("(?i)</?(?:i|em)(?:\\s[^>]*)?>", "*")
                .replaceAll("(?i)</?(?:code|kbd|tt)(?:\\s[^>]*)?>", "`")
                .replaceAll("(?i)</?(?:del|s|strike)(?:\\s[^>]*)?>", "~~")
                .replaceAll("(?i)<summary\\b[^>]*>", "\n\n**").replaceAll("(?i)</summary\\s*>", "**\n\n")
                .replaceAll("(?i)<li\\b[^>]*>", "\n- ")
                .replaceAll("(?i)<hr\\b[^>]*>", "\n\n---\n\n")
                .replaceAll("(?i)</?(?:p|div|details|ul|ol|li|table|thead|tbody|tr|blockquote|center|section)\\b[^>]*>", "\n\n");
        s = TAG.matcher(s).replaceAll("");
        s = IMAGE_LINE.matcher(entities(s)).replaceAll("");   // a line of pictures only: a paragraph break
        return s.replaceAll("(?<=\\S)[ \\t]{2,}(?=\\S)", " ");   // the gap a dropped badge leaves
    }

    /** &lt;a href="u"&gt;words&lt;/a&gt; → [words](u); a link around a picture only (a badge) goes */
    private static String links(String s) {
        Matcher m = LINK_TAG.matcher(s);
        StringBuffer b = new StringBuffer();   // appendReplacement's StringBuilder overload is API 34; minSdk is 33
        while (m.find()) {
            String url = m.group(1) != null ? m.group(1) : m.group(2);
            String words = TAG.matcher(m.group(3).replaceAll("(?is)<img\\b[^>]*>", "")).replaceAll("").trim();
            m.appendReplacement(b, Matcher.quoteReplacement(words.isEmpty() ? "" : "[" + words.replace("]", "\\]") + "](" + url.trim().replace(" ", "%20") + ")"));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String entities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r = null;
            if (e.charAt(0) == '#') {
                int cp = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X') ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                if (Character.isValidCodePoint(cp) && cp != 0) r = new String(Character.toChars(cp));
            } else {
                switch (e.toLowerCase(Locale.ROOT)) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "nbsp": r = " "; break;
                    case "ndash": r = "–"; break;
                    case "mdash": r = "—"; break;
                    case "hellip": r = "…"; break;
                    case "copy": r = "©"; break;
                    case "reg": r = "®"; break;
                    case "trade": r = "™"; break;
                    default: break;
                }
            }
            m.appendReplacement(b, Matcher.quoteReplacement(r != null ? r : m.group()));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- Inline ----

    /** Inline Markdown of s appended to t as text, its styles into spans (positions in t) */
    static void inline(String s, StringBuilder t, List<Span> spans) {
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) { t.append(s.charAt(i + 1)); i += 2; continue; }
            if (c == '`') {
                int run = run(s, i, '`'), close = s.indexOf(s.substring(i, i + run), i + run);
                if (close > 0) {
                    int st = t.length();
                    t.append(s.substring(i + run, close).trim());
                    if (t.length() > st) spans.add(new Span(Style.CODE, st, t.length(), null));
                    i = close + run;
                    continue;
                }
                t.append(s, i, i + run);
                i += run;
                continue;
            }
            if (c == '!' && i + 1 < n && s.charAt(i + 1) == '[') {   // a picture: left out
                int[] l = link(s, i + 1);
                if (l != null) { i = l[3]; continue; }
            }
            if (c == '[') {
                int[] l = link(s, i);
                if (l != null) {
                    int st = t.length(), had = spans.size();
                    inline(s.substring(i + 1, l[0]), t, spans);
                    for (int k = spans.size() - 1; k >= had; k--) if (spans.get(k).style == Style.LINK) spans.remove(k);   // the link's own address in its words: one link
                    String url = s.substring(l[1], l[2]).trim();
                    if (url.startsWith("<") && url.endsWith(">")) url = url.substring(1, url.length() - 1);
                    if (url.startsWith("https://") && url.length() > 8 && t.length() > st) spans.add(new Span(Style.LINK, st, t.length(), url));
                    i = l[3];
                    continue;
                }
            }
            if (c == 'h' && s.startsWith("https://", i) && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {   // a bare address: a link, as GitHub makes it
                int e = address(s, i);
                if (e > i + 8) {
                    int st = t.length();
                    t.append(s, i, e);
                    spans.add(new Span(Style.LINK, st, t.length(), s.substring(i, e)));
                    i = e;
                    continue;
                }
            }
            if ((c == '*' || c == '_' || c == '~') && i + 1 < n && s.charAt(i + 1) == c && opens(s, i, 2)) {
                int close = closing(s, i + 2, String.valueOf(c) + c);
                if (close > i + 2) {
                    int st = t.length();
                    inline(s.substring(i + 2, close), t, spans);
                    spans.add(new Span(c == '~' ? Style.STRIKE : Style.BOLD, st, t.length(), null));
                    i = close + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && opens(s, i, 1)) {
                int close = closing(s, i + 1, String.valueOf(c));
                if (close > i + 1) {
                    int st = t.length();
                    inline(s.substring(i + 1, close), t, spans);
                    spans.add(new Span(Style.ITALIC, st, t.length(), null));
                    i = close + 1;
                    continue;
                }
            }
            t.append(c);
            i++;
        }
    }

    /** The end of a bare address at i: up to a space, without the punctuation that closes a sentence or a bracket around it */
    private static int address(String s, int i) {
        int e = i;
        while (e < s.length() && !Character.isWhitespace(s.charAt(e)) && s.charAt(e) != '<') e++;
        while (e > i) {
            char x = s.charAt(e - 1);
            if (".,;:!?'\"*_".indexOf(x) >= 0) { e--; continue; }
            if (x == ')' && count(s, i, e, '(') < count(s, i, e, ')')) { e--; continue; }
            break;
        }
        return e;
    }

    private static int count(String s, int from, int to, char c) { int k = 0; for (int i = from; i < to; i++) if (s.charAt(i) == c) k++; return k; }

    private static int run(String s, int i, char c) { int k = i; while (k < s.length() && s.charAt(k) == c) k++; return k - i; }

    /** A marker of width w at i opens: text follows it (not a space, not the same marker again), and an underscore is not inside a word (snake_case) */
    private static boolean opens(String s, int i, int w) {
        if (i + w >= s.length() || Character.isWhitespace(s.charAt(i + w))) return false;
        if (w == 1 && s.charAt(i + 1) == s.charAt(i)) return false;
        return s.charAt(i) != '_' || i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
    }

    /** The closing marker after from (not inside `code`, not after a space, an underscore not inside a word), or -1 */
    private static int closing(String s, int from, String marker) {
        char c = marker.charAt(0);
        for (int k = from; k < s.length(); k++) {
            char x = s.charAt(k);
            if (x == '\\') { k++; continue; }
            if (x == '`') { int r = run(s, k, '`'), close = s.indexOf(s.substring(k, k + r), k + r); if (close > 0) { k = close + r - 1; continue; } }
            if (!s.startsWith(marker, k) || Character.isWhitespace(s.charAt(k - 1))) continue;
            int after = k + marker.length();
            if (marker.length() == 1 && after < s.length() && s.charAt(after) == c) { k = after; continue; }   // part of a ** run, not a single closing *
            if (c == '_' && after < s.length() && Character.isLetterOrDigit(s.charAt(after))) continue;
            return k;
        }
        return -1;
    }

    /** [text](url) at i: { the ']' index, url start, url end, the index after ')' }, or null */
    private static int[] link(String s, int i) {
        int depth = 0;
        for (int k = i; k < s.length(); k++) {
            char x = s.charAt(k);
            if (x == '\\') { k++; continue; }
            if (x == '[') depth++;
            else if (x == ']' && --depth == 0) {
                if (k + 1 >= s.length() || s.charAt(k + 1) != '(') return null;
                int close = s.indexOf(')', k + 2);
                if (close < 0) return null;
                int urlEnd = close, q = s.indexOf(" \"", k + 2);   // [text](url "title")
                if (q > 0 && q < close) urlEnd = q;
                return new int[] {k, k + 2, urlEnd, close + 1};
            }
        }
        return null;
    }
}
