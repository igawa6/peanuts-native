package dev.peanuts.ingame;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import java.util.function.Consumer;

/** The Peanuts in-game control that saves and applies the right-stick mode. */
public final class FloatingStickSetting {
    private static final String KEY = "float_right_stick";

    private FloatingStickSetting() {}

    public static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY, false);
    }

    public static void addRow(Context context, LinearLayout rows, SharedPreferences prefs, Consumer<Boolean> apply) {
        CheckBox row = new CheckBox(context);
        row.setText("Floating right stick");
        row.setChecked(enabled(prefs));
        row.setOnCheckedChangeListener((button, enabled) -> {
            // Commit before rebuilding the layout because the game process can stop from this panel.
            prefs.edit().putBoolean(KEY, enabled).commit();
            apply.accept(enabled);
        });
        rows.addView(row);
    }
}
