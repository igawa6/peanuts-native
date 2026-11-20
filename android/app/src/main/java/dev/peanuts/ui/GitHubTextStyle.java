package dev.peanuts.ui;

import dev.nxnative.android.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TabStopSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.widget.TextView;

import java.util.List;

/**
 * GitHubText's blocks drawn in the app's look: headings, lists with their bullets and numbers in the accent, quotes with a bar, GitHub's notes and
 * warnings with a bar and their word in its colour, code in a fixed-width face, a table's columns lined up, links in the accent. A link opens on a
 * tap; the D-pad does not stop on links, it scrolls the text (view()).
 */
final class GitHubTextStyle {
    private GitHubTextStyle() {}

    /** The text in a view for a sheet or a page: body size, a link opens on a tap; it takes the D-pad's focus (with the covers' ring) so a long text scrolls */
    static TextView view(Context c, String markdown) {
        TextView t = PUi.text(c, "", 15, R.color.bn_text, 400);
        t.setLineSpacing(PUi.dp(c, 3), 1f);
        t.setText(styled(c, GitHubText.parse(markdown), t.getPaint()));
        t.setOnTouchListener((v, e) -> tapLink((TextView) v, e));
        t.setFocusable(true);
        int r = PUi.dp(c, 14), w = PUi.dp(c, 3), p = PUi.dp(c, 10);
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[] {android.R.attr.state_focused}, PUi.round(Color.TRANSPARENT, r, PUi.col(c, R.color.bn_banana), w));
        t.setBackground(ring);
        t.setPadding(p, p, p, p);
        return t;
    }

    /** The blocks as styled text; paint measures a table's columns (the view's own paint) */
    static CharSequence styled(Context c, List<GitHubText.Block> blocks, TextPaint paint) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        int accent = PUi.col(c, R.color.bn_accent), second = PUi.col(c, R.color.bn_text_2);
        int[] from = new int[blocks.size()], to = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {   // the text first: a paragraph style set while text is still added would grow over it
            GitHubText.Block b = blocks.get(i);
            if (i > 0) s.append('\n');
            from[i] = s.length();
            if (b.kind == GitHubText.Kind.ALERT) {   // the alert's word on its own line, in its colour
                s.append(b.label);
                s.setSpan(new StyleSpan(Typeface.BOLD), from[i], s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new ForegroundColorSpan(alertColor(c, b.label)), from[i], s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!b.text.isEmpty()) s.append('\n');
            }
            int at = s.length();
            s.append(b.text);
            inline(c, s, at, b.spans, accent);
            to[i] = s.length();
        }
        int[] tabs = null;
        for (int i = 0; i < blocks.size(); i++) {
            GitHubText.Block b = blocks.get(i);
            int start = from[i], end = to[i] < s.length() ? to[i] + 1 : to[i];   // a paragraph style ends after its line break
            if (i > 0) para(s, new Gap(gap(c, blocks.get(i - 1).kind, b)), start, end);
            switch (b.kind) {
                case HEADING:
                    s.setSpan(new StyleSpan(Typeface.BOLD), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (b.level <= 3) s.setSpan(new RelativeSizeSpan(b.level <= 2 ? 1.2f : 1.08f), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case BULLET:
                    if (b.level > 0) para(s, new LeadingMarginSpan.Standard(b.level * PUi.dp(c, 18)), start, end);
                    para(s, new Bullet(PUi.dp(c, 2.5f), PUi.dp(c, 10), accent), start, end);
                    break;
                case NUMBER:
                    if (b.level > 0) para(s, new LeadingMarginSpan.Standard(b.level * PUi.dp(c, 18)), start, end);
                    para(s, new Number(b.label, Math.round(paint.measureText("00.")) + PUi.dp(c, 8), accent), start, end);
                    break;
                case QUOTE:
                    para(s, new QuoteSpan(PUi.col(c, R.color.bn_card_stroke), PUi.dp(c, 3), PUi.dp(c, 12)), start, end);
                    s.setSpan(new ForegroundColorSpan(second), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case ALERT:
                    para(s, new QuoteSpan(alertColor(c, b.label), PUi.dp(c, 3), PUi.dp(c, 12)), start, end);
                    break;
                case CODE:
                    s.setSpan(new TypefaceSpan("monospace"), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new RelativeSizeSpan(0.9f), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(second), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    para(s, new LeadingMarginSpan.Standard(PUi.dp(c, 12)), start, end);
                    break;
                case ROW:
                    if (i == 0 || blocks.get(i - 1).kind != GitHubText.Kind.ROW) tabs = columns(c, blocks, i, paint);
                    for (int x : tabs) para(s, new TabStopSpan.Standard(x), start, end);
                    if ("head".equals(b.label)) s.setSpan(new StyleSpan(Typeface.BOLD), start, to[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    break;
            }
        }
        return s;
    }

    private static void para(SpannableStringBuilder s, Object span, int start, int end) { s.setSpan(span, start, end, Spanned.SPAN_PARAGRAPH); }

    private static void inline(Context c, SpannableStringBuilder s, int at, List<GitHubText.Span> spans, int accent) {
        for (GitHubText.Span x : spans) {
            int a = at + x.start, e = at + x.end;
            switch (x.style) {
                case BOLD: s.setSpan(new StyleSpan(Typeface.BOLD), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case ITALIC: s.setSpan(new StyleSpan(Typeface.ITALIC), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case STRIKE: s.setSpan(new StrikethroughSpan(), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
                case CODE:
                    s.setSpan(new TypefaceSpan("monospace"), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new BackgroundColorSpan(PUi.col(c, R.color.bn_tint)), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LINK:
                    s.setSpan(new URLSpan(x.url), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(accent), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default: break;
            }
        }
    }

    /** Space above a block: more before a heading, little between the items of one list */
    private static int gap(Context c, GitHubText.Kind before, GitHubText.Block b) {
        if (b.kind == GitHubText.Kind.HEADING) return PUi.dp(c, 14);
        boolean item = b.kind == GitHubText.Kind.BULLET || b.kind == GitHubText.Kind.NUMBER;
        if (item && (before == GitHubText.Kind.BULLET || before == GitHubText.Kind.NUMBER)) return PUi.dp(c, 4);
        if (b.kind == GitHubText.Kind.ROW && before == GitHubText.Kind.ROW) return PUi.dp(c, 2);
        if (before == GitHubText.Kind.HEADING) return PUi.dp(c, 4);
        return PUi.dp(c, 10);
    }

    private static int alertColor(Context c, String label) {
        return "Warning".equals(label) || "Caution".equals(label) ? PUi.col(c, R.color.bn_danger) : PUi.col(c, R.color.bn_accent);
    }

    /** The tab stops of the table that starts at blocks[from]: each column as wide as its widest cell, and a gap */
    private static int[] columns(Context c, List<GitHubText.Block> blocks, int from, TextPaint paint) {
        float[] w = new float[0];
        for (int i = from; i < blocks.size() && blocks.get(i).kind == GitHubText.Kind.ROW; i++) {
            String[] cells = blocks.get(i).text.split("\t", -1);
            if (cells.length > w.length) w = java.util.Arrays.copyOf(w, cells.length);
            for (int k = 0; k < cells.length; k++) w[k] = Math.max(w[k], paint.measureText(cells[k]) * 1.08f);   // bold heads are wider
        }
        int[] tabs = new int[Math.max(0, w.length - 1)];
        float x = 0;
        for (int k = 0; k < tabs.length; k++) { x += w[k] + PUi.dp(c, 20); tabs[k] = Math.round(x); }
        return tabs;
    }

    /** A link opens on a tap; anywhere else the touch is the view's */
    private static boolean tapLink(TextView v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false;
        Layout l = v.getLayout();
        if (l == null || !(v.getText() instanceof Spanned)) return false;
        int x = Math.round(e.getX()) - v.getTotalPaddingLeft() + v.getScrollX(), y = Math.round(e.getY()) - v.getTotalPaddingTop() + v.getScrollY();
        int line = l.getLineForVertical(y);
        if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return false;
        int off = l.getOffsetForHorizontal(line, x);
        ClickableSpan[] links = ((Spanned) v.getText()).getSpans(off, off, ClickableSpan.class);
        if (links.length == 0) return false;
        if (action == MotionEvent.ACTION_UP) links[0].onClick(v);
        return true;
    }

    /** Space above the first line of the block it covers */
    private static final class Gap implements LineHeightSpan {
        private final int px;
        Gap(int px) { this.px = px; }

        @Override public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, Paint.FontMetricsInt fm) {
            if (start != ((Spanned) text).getSpanStart(this)) return;
            fm.ascent -= px;
            fm.top -= px;
        }
    }

    /** A list item's dot in the margin, in the accent, on its first line only (an item's line breaks start no new dot, as BulletSpan's would) */
    private static final class Bullet implements LeadingMarginSpan {
        private final int radius, gap, color;
        Bullet(int radius, int gap, int color) { this.radius = radius; this.gap = gap; this.color = color; }

        @Override public int getLeadingMargin(boolean first) { return 2 * radius + gap; }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || ((Spanned) text).getSpanStart(this) != start) return;
            int was = p.getColor();
            Paint.Style style = p.getStyle();
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            Paint.FontMetrics m = p.getFontMetrics();
            c.drawCircle(x + dir * radius, baseline + (m.ascent + m.descent) / 2f, radius, p);
            p.setColor(was);
            p.setStyle(style);
        }
    }

    /** A numbered item's "1." in the margin, in the accent; the item's lines line up after it */
    private static final class Number implements LeadingMarginSpan {
        private final String label; private final int width, color;
        Number(String label, int width, int color) { this.label = label; this.width = width; this.color = color; }

        @Override public int getLeadingMargin(boolean first) { return width; }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first || ((Spanned) text).getSpanStart(this) != start) return;
            int was = p.getColor();
            p.setColor(color);
            c.drawText(label, dir > 0 ? x : x - p.measureText(label), baseline, p);
            p.setColor(was);
        }
    }
}
