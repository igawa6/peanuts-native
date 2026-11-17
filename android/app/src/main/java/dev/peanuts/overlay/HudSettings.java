package dev.peanuts.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * PEANUTS: the overlay sheets' two extra choices, the table's style, position, size and the preset, under Overlay.rows. A preset ticks the item switches that Overlay.rows already put
 * in the same box (found by their text), so upstream's own listeners write the preferences and the sheet shows it.
 */
public final class HudSettings {
    private HudSettings() {}

    /** Mirrors Overlay.ITEMS (key, label); a test compares them */
    static final String[][] LABELS = {
            {"fps", "Frame rate and frame time"},
            {"throttle", "Throttle dot (green / yellow / red)"},
            {"gpu", "GPU time a frame"},
            {"cpu", "CPU clock caps"},
            {"battery", "Battery temperature and level"},
            {"soc", "SoC temperature (hottest CPU / GPU zone)"},
            {"graph", "Frame time graph and 1% low"},
            {"driver", "GPU driver"},
            {"tag", "Build tag"},
            {"memory", "Memory: the game's and the phone's"},
    };
    private static final String MASTER = "Show the overlay over the game";

    static final String[] MINIMAL = {"fps", "graph"};
    static final String[] NORMAL = {"fps", "graph", "throttle", "soc", "battery", "driver", "tag"};

    /** The items a preset turns on (null: "custom", change nothing; every item for "full") */
    static Set<String> items(String preset) {
        switch (preset) {
            case "minimal": return new HashSet<>(Arrays.asList(MINIMAL));
            case "normal": return new HashSet<>(Arrays.asList(NORMAL));
            case "full": { Set<String> all = new HashSet<>(); for (String[] l : LABELS) all.add(l[0]); return all; }
            default: return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(Hud.PREFS, Context.MODE_MULTI_PROCESS); }

    public static void rows(Context c, LinearLayout box) {
        SharedPreferences p = prefs(c);
        final Runnable[] sync = new Runnable[1];
        choice(c, box, "Preset", new String[] {"Minimal", "Normal", "Full", "Custom"}, new String[] {"minimal", "normal", "full", "custom"}, () -> p.getString(Hud.PRESET, "normal"),
                v -> { p.edit().putString(Hud.PRESET, v).apply(); apply(box, v); sync[0].run(); });
        choice(c, box, "Style", new String[] {"Table", "Classic"}, new String[] {"table", "classic"}, () -> p.getString(Hud.STYLE, "table"),
                v -> p.edit().putString(Hud.STYLE, v).apply());
        choice(c, box, "Position", new String[] {"Top right", "Top left", "Bottom right", "Bottom left"}, new String[] {"tr", "tl", "br", "bl"}, () -> p.getString(Hud.CORNER, "tr"),
                v -> p.edit().putString(Hud.CORNER, v).apply());
        choice(c, box, "Size", new String[] {"75%", "100%", "125%", "150%", "200%"}, new String[] {"75", "100", "125", "150", "200"}, () -> String.valueOf(p.getInt(Hud.SCALE, 100)),
                v -> p.edit().putInt(Hud.SCALE, Integer.parseInt(v)).apply());
        // the item switches stay in the box (their listeners write the preferences) but are shown only for Custom
        sync[0] = () -> {
            boolean custom = "custom".equals(p.getString(Hud.PRESET, "normal"));
            for (int i = 0; i < box.getChildCount(); i++) {
                View v = box.getChildAt(i);
                if (v instanceof CheckBox && !String.valueOf(((CheckBox) v).getText()).startsWith(MASTER)) v.setVisibility(custom ? View.VISIBLE : View.GONE);
            }
        };
        sync[0].run();
    }

    private interface Pick { void on(String value); }
    private interface Cur { String get(); }

    /**
     * One compact row, like a settings list: the title on the left, the current value in the accent colour with a chevron on the right edge. A tap opens a
     * single-choice dialog. Horizontal padding is none: the box already insets its rows by 16 dp, the same edge as the overlay's switches.
     */
    private static void choice(Context c, LinearLayout box, String title, String[] names, String[] values, Cur cur, Pick pick) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TypedValue bg = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, bg, true);
        row.setBackgroundResource(bg.resourceId);
        row.setClickable(true);
        row.setFocusable(true);
        int pv = Math.round(10 * dp);
        row.setPadding(0, pv, 0, pv);
        // the title's colour and size are the master switch's, found at runtime, so the rows read as active like the switches above them
        int textColor = 0xFFFFFFFF;
        float textPx = 14 * dp;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof CheckBox && String.valueOf(((CheckBox) v).getText()).startsWith(MASTER)) {
                textColor = ((CheckBox) v).getCurrentTextColor();
                textPx = ((CheckBox) v).getTextSize();
                break;
            }
        }
        row.setEnabled(true);
        TextView name = new TextView(c);
        name.setTextColor(textColor);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        name.setSingleLine(true);
        name.setText(title);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(c);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        val.setSingleLine(true);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable show = () -> {
            int i = Math.max(0, Arrays.asList(values).indexOf(cur.get()));
            val.setTextColor(0xFFFFD54F);   // the panel's accent (SidePanel.FOCUS), full opacity
            val.setText(names[i] + " \u203A");
        };
        show.run();
        row.setOnClickListener(v -> new AlertDialog.Builder(c).setTitle(title)
                .setSingleChoiceItems(names, Math.max(0, Arrays.asList(values).indexOf(cur.get())), (d, w) -> { pick.on(values[w]); show.run(); d.dismiss(); })
                .setNegativeButton("Cancel", null).show());
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Sets the master switch on and each item's switch in the box to the preset (the boxes' own listeners write the preferences) */
    private static void apply(LinearLayout box, String preset) {
        Set<String> on = items(preset);
        if (on == null) return;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (!(v instanceof CheckBox)) continue;
            CheckBox b = (CheckBox) v;
            String text = String.valueOf(b.getText());
            if (text.startsWith(MASTER)) { b.setChecked(true); continue; }
            for (String[] l : LABELS) if (l[1].equals(text)) b.setChecked(on.contains(l[0]));
        }
    }
}
