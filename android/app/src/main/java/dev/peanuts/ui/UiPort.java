package dev.peanuts.ui;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;

/**
 * Upstream's choice controls for Peanuts' own pages, through reflection as dev.peanuts.settings.SectionUi does (Ui is package-private): Ui.Segmented (a few
 * choices side by side, the picked one on banana yellow: the profile picture's Miis or firmware pictures) and Ui.tabs (a page's tabs, the current one in the
 * accent over a bar: the Mii editor's parts). If upstream changes them, plain rows of buttons stand in.
 */
public final class UiPort {
    private UiPort() {}

    /** A Segmented of labels with sel picked; pick gets the index the player picks */
    public static View segmented(Context c, String[] labels, int sel, IntConsumer pick) {
        try {
            Class<?> k = Class.forName("dev.nxnative.android.Ui$Segmented");
            Constructor<?> m = k.getDeclaredConstructor(Context.class, String[].class, int.class, IntConsumer.class);
            m.setAccessible(true);
            return (View) m.newInstance(c, labels, sel, pick);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("peanuts", "Ui.Segmented", e);
            return fallback(c, labels, pick);
        }
    }

    /** Ui.tabs of labels with sel current; pick gets the index of another */
    public static View tabs(Context c, String[] labels, int sel, IntConsumer pick) {
        try {
            Method m = Class.forName("dev.nxnative.android.Ui").getDeclaredMethod("tabs", Context.class, String[].class, int.class, IntConsumer.class);
            m.setAccessible(true);
            return (View) m.invoke(null, c, labels, sel, pick);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("peanuts", "Ui.tabs", e);
            return fallback(c, labels, pick);
        }
    }

    private static View fallback(Context c, String[] labels, IntConsumer pick) {
        LinearLayout row = PUi.row(c);
        for (int i = 0; i < labels.length; i++) {
            int k = i;
            row.addView(PUi.button(c, labels[i], PUi.Kind.TONAL, v -> pick.accept(k)), PUi.weight());
        }
        return row;
    }
}
