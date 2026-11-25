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

    /** The preset the row names before the player picked one: every item is on until then (Prefs.overlayItem and Hud.item default to true), which is Full */
    static final String DEFAULT_PRESET = "full";
    static final String[] MINIMAL = {"fps", "graph"};
    static final String[] NORMAL = {"fps", "graph", "throttle", "soc", "battery", "driver", "tag"};

    /**
     * The pill's items for each preset: one line, so GPU, CPU, the driver and the game's memory stay in the table (Hud.pill). Normal is what players ask about
     * (the frame rate and the temperature, with the graph); the throttle dot and "low" (1%L, no switch of its own) are Full's, words and marks few players read
     */
    static final String[] PILL_MINIMAL = {"fps"};
    static final String[] PILL_NORMAL = {"fps", "graph", "soc"};
    static final String[] PILL_FULL = {"fps", "throttle", "graph", "low", "soc", "battery", "memory"};

    /** The pill's items for a preset (null: "custom", the item switches) */
    static Set<String> pillItems(String preset) {
        switch (preset) {
            case "minimal": return new HashSet<>(Arrays.asList(PILL_MINIMAL));
            case "normal": return new HashSet<>(Arrays.asList(PILL_NORMAL));
            case "full": return new HashSet<>(Arrays.asList(PILL_FULL));
            default: return null;
        }
    }

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
        choice(c, box, "Preset", new String[] {"Minimal", "Normal", "Full", "Custom"}, new String[] {"minimal", "normal", "full", "custom"}, () -> Hud.preset(p),
                v -> { p.edit().putString(Hud.PRESET, v).apply(); apply(p, box, v, "pill".equals(Hud.style(p))); sync[0].run(); });
        choice(c, box, "Style", new String[] {"Pill", "Table", "Classic"}, new String[] {"pill", "table", "classic"}, () -> Hud.style(p),
                v -> { p.edit().putString(Hud.STYLE, v).apply(); syncTag(c, p, box); });
        choice(c, box, "Position", new String[] {"Top center", "Top right", "Top left", "Bottom center", "Bottom right", "Bottom left"},
                new String[] {"tc", "tr", "tl", "bc", "br", "bl"}, () -> Hud.corner(p),
                v -> p.edit().putString(Hud.CORNER, v).apply());
        choice(c, box, "Size", new String[] {"75%", "100%", "125%", "150%", "200%"}, new String[] {"75", "100", "125", "150", "200"}, () -> String.valueOf(p.getInt(Hud.SCALE, 100)),
                v -> p.edit().putInt(Hud.SCALE, Integer.parseInt(v)).apply());
        choice(c, box, "With bottom stats", new String[] {"Hide", "Show"}, new String[] {"hide", "show"}, () -> p.getBoolean(Hud.HIDE_FOR_STATS, true) ? "hide" : "show",
                v -> p.edit().putBoolean(Hud.HIDE_FOR_STATS, v.equals("hide")).apply());
        // the item switches stay in the box (their listeners write the preferences) but are shown only for Custom
        sync[0] = () -> {
            boolean custom = "custom".equals(Hud.preset(p));
            for (int i = 0; i < box.getChildCount(); i++) {
                View v = box.getChildAt(i);
                if (v instanceof CheckBox && !String.valueOf(((CheckBox) v).getText()).startsWith(MASTER)) v.setVisibility(custom ? View.VISIBLE : View.GONE);
            }
        };
        sync[0].run();
        syncTag(c, p, box);
    }

    /** The Build tag box shows what the game does (Hud.tagOn): unset, it is off under the pill though Overlay.rows ticked it from Prefs.overlayItem's default */
    private static void syncTag(Context c, SharedPreferences p, LinearLayout box) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (v instanceof CheckBox && "Build tag".equals(String.valueOf(((CheckBox) v).getText()))) tagBox(p, (CheckBox) v, Hud.tagOn(c), "pill".equals(Hud.style(p)));
        }
    }

    /** PEANUTS (#538 landing): sets the Build tag box without making the pill's hidden tag the table's too. The box's listener writes overlay_tag; a false written
     *  only because the pill hides the tag is taken back (unset reads off under the pill and on under the table and classic), so a player who tries the pill and
     *  goes back keeps the tag. A player's own tap still writes what they chose. */
    private static void tagBox(SharedPreferences p, CheckBox b, boolean on, boolean pill) {
        if (b.isChecked() != on) b.setChecked(on);
        if (pill && !on) p.edit().remove("overlay_tag").commit();
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
    private static void apply(SharedPreferences p, LinearLayout box, String preset, boolean pill) {
        Set<String> on = items(preset);
        if (on == null) return;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (!(v instanceof CheckBox)) continue;
            CheckBox b = (CheckBox) v;
            String text = String.valueOf(b.getText());
            if (text.startsWith(MASTER)) { b.setChecked(true); continue; }
            // under the pill, only Full turns the build tag on (Hud.tagOn: off by default there)
            for (String[] l : LABELS) if (l[1].equals(text)) {
                boolean want = on.contains(l[0]) && !(pill && "tag".equals(l[0]) && !"full".equals(preset));
                if ("tag".equals(l[0])) tagBox(p, b, want, pill); else b.setChecked(want);
            }
        }
    }
}
