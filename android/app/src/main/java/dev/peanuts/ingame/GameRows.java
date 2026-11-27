package dev.peanuts.ingame;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The one menu's Game: the game menu's rows inside the one menu (at the right or on the bottom screen), under its
 * header (cover, name, version, this session's time), Stop set apart and asked once inline. Unlike GameMenu's own selection these rows take Android's focus,
 * so the quick menu's panel (upstream's SidePanel, which the bottom screen's menu is) moves over them as over its other rows, and a touch presses them.
 */
public final class GameRows extends LinearLayout {
    private final ImageView cover;
    private final TextView name, version, time;
    private final FrameLayout bottom;
    private final LinearLayout footer;   // the hairline and Stop, kept at the sheet's bottom under the rows (Rail), as the game menu's
    private final int rowPx;
    private GameMenu.Item stop;
    private View stopRow;
    private long startedAt;

    public GameRows(Context c, int rowPx) {
        super(c);
        this.rowPx = Math.max(rowPx, Look.dp(c, 44));
        setOrientation(VERTICAL);
        setPadding(Look.dp(c, 4), Look.dp(c, 2), Look.dp(c, 4), Look.dp(c, 4));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Look.dp(c, 4), 0, Look.dp(c, 4), Look.dp(c, 8));
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(Look.round(Look.CHIP, Look.dp(c, 11)));
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Look.dp(c, 11)); }
        });
        cover.setClipToOutline(true);
        int cs = Look.dp(c, 48);
        head.addView(cover, new LayoutParams(cs, cs));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(Look.dp(c, 12), 0, 0, 0);
        name = text(c, 15, Look.TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Look.dp(c, 4), 0, 0);
        version = text(c, 12, Look.TEXT, false);
        version.setBackground(Look.round(Look.CHIP, Look.dp(c, 999)));
        version.setPadding(Look.dp(c, 9), Look.dp(c, 3), Look.dp(c, 9), Look.dp(c, 3));
        version.setVisibility(GONE);
        line.addView(version);
        time = text(c, 13, Look.QUIET, false);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = Look.dp(c, 8);
        line.addView(time, tl);
        col.addView(line);
        head.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addView(head);
        bottom = new FrameLayout(c);
        footer = new LinearLayout(c);
        footer.setOrientation(VERTICAL);
        footer.setPadding(Look.dp(c, 14), 0, Look.dp(c, 14), Look.dp(c, 8));   // under the rows' own sides (their scroll's 10 dp and these rows' 4 dp)
    }

    /** The hairline and Stop: the rail puts them under the rows, so Stop stays in sight on a short screen while the rows scroll */
    public View footer() { return footer; }

    private static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    public void game(String gameName, String ver, long started) { name.setText(gameName == null ? "" : gameName); version(ver); startedAt = started; time.setText(played()); }
    public void version(String ver) { version.setText(ver == null ? "" : ver); version.setVisibility(ver == null || ver.isEmpty() ? GONE : VISIBLE); }
    public void cover(Bitmap b) { if (b != null) cover.setImageBitmap(b); }
    public void refresh() { time.setText(played()); if (stopRow != null && stopRow.getParent() == null) { bottom.removeAllViews(); bottom.addView(stopRow); } }

    /** The rows, top to bottom, and Stop */
    public void items(List<GameMenu.Item> rows, GameMenu.Item stopItem) {
        for (GameMenu.Item it : rows) addView(row(it, () -> it.run()));
        View hair = new View(getContext());
        hair.setBackgroundColor(Look.HAIRLINE);
        LayoutParams hl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Look.dp(getContext(), 1)));
        hl.topMargin = hl.bottomMargin = Look.dp(getContext(), 6);
        footer.addView(hair, hl);
        stop = stopItem;
        stopRow = row(stopItem, this::askStop);
        bottom.addView(stopRow);
        footer.addView(bottom);
    }

    private View row(GameMenu.Item it, Runnable press) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(rowPx);
        r.setPadding(Look.dp(c, 8), 0, Look.dp(c, 8), 0);
        r.setBackground(Look.round(0, Look.dp(c, 12)));
        ImageView ic = new ImageView(c);
        Drawable d = Look.icon(c, it.icon(), Look.Ink.MENU);
        if (d != null) ic.setImageDrawable(d);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        int is = Look.dp(c, 30);
        r.addView(ic, new LayoutParams(is, is));
        TextView t = text(c, 15, Look.TEXT, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(it.title());
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Look.dp(c, 10), 0, Look.dp(c, 8), 0);
        r.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(c, 13, Look.QUIET, false);
        value.setSingleLine(true);
        r.addView(value);
        it.bind(value);
        if (it.chevron()) {
            ImageView ch = new ImageView(c);
            Drawable cd = c.getDrawable(dev.nxnative.android.R.drawable.ic_chevron);   // the launcher's own (PUi.Setting.chevron)
            if (cd != null) { cd = cd.mutate(); cd.setTint(Look.QUIET); ch.setImageDrawable(cd); }
            int chs = Look.dp(c, 18);
            LayoutParams cl = new LayoutParams(chs, chs);
            cl.leftMargin = Look.dp(c, 4);
            r.addView(ch, cl);
        }
        r.setFocusable(true);
        r.setClickable(true);
        r.setContentDescription(it.title());
        r.setOnClickListener(v -> press.run());
        return r;
    }

    /** Stop asks once, inline: Keep playing (the focus) or Stop */
    private void askStop() {
        Context c = getContext();
        LinearLayout ask = new LinearLayout(c);
        ask.setOrientation(VERTICAL);
        ask.setPadding(Look.dp(c, 8), Look.dp(c, 2), Look.dp(c, 8), 0);
        TextView q = text(c, 15, Look.TEXT, true);
        q.setText("Stop the game?");
        ask.addView(q);
        TextView w = text(c, 13, Look.QUIET, false);
        w.setText("Unsaved progress is lost. The log stays for Share log.");
        w.setPadding(0, Look.dp(c, 4), 0, Look.dp(c, 8));
        ask.addView(w);
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(HORIZONTAL);
        TextView keep = button(c, "Keep playing", v -> { bottom.removeAllViews(); bottom.addView(stopRow); stopRow.requestFocus(); });
        TextView now = button(c, "Stop", v -> stop.run());
        now.setTextColor(Look.BAD);
        LayoutParams sl = new LayoutParams(0, rowPx, 1);
        sl.leftMargin = Look.dp(c, 8);
        b.addView(keep, new LayoutParams(0, rowPx, 1));
        b.addView(now, sl);
        ask.addView(b);
        bottom.removeAllViews();
        bottom.addView(ask);
        if (!isInTouchMode()) keep.requestFocus();
    }

    private TextView button(Context c, String s, View.OnClickListener l) {
        TextView t = text(c, 14, Look.TEXT, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Look.round(Look.ROW, Look.dp(c, 10)));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private String played() {
        if (startedAt <= 0) return "";
        long min = (android.os.SystemClock.elapsedRealtime() - startedAt) / 60000;
        if (min < 1) return "Just started";
        if (min < 60) return "Playing for " + min + " min";
        return "Playing for " + (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
    }
}
