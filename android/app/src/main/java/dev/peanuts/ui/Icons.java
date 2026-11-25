package dev.peanuts.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The Peanuts icons (a shell with one kernel; res drawable/pi_*.xml, written by peanuts/scripts/icons.py, where a new one is drawn). Their two
 * colours are theme attributes, ?attr/pnIconInk for the shell and ?attr/pnIconKernel for the kernel, set by a style per use (res values/peanuts_icons.xml):
 * the text colour with the kernel in the accent, the quieter text colour for a sidebar entry, and the dark brown of text on banana yellow for a selected one.
 * The drawables and styles are in the Peanuts resource folder only, so they are found by name; a build without them gets null and keeps its emoji.
 */
public final class Icons {
    private Icons() {}

    /** The shell and kernel colours: NORMAL (a row's tile), QUIET (a sidebar entry), ON_BANANA (the selected one, on yellow) */
    public enum Ink {
        NORMAL("PeanutsIcon"), QUIET("PeanutsIconQuiet"), ON_BANANA("PeanutsIconOnBanana");
        final String style;
        Ink(String style) { this.style = style; }
    }

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final Map<Ink, Resources.Theme> THEMES = new HashMap<>();
    private static int night = -1;

    private static int id(Context c, String name, String type) {
        String key = type + "/" + name;
        Integer v = IDS.get(key);
        if (v == null) { v = c.getResources().getIdentifier(name, type, c.getPackageName()); IDS.put(key, v); }
        return v;
    }

    /** The icon named (library, key, …: pi_<name>) in these colours, at its own 24 dp; null when this build has none */
    public static Drawable get(Context c, String name, Ink ink) {
        if (name == null) return null;
        int d = id(c, "pi_" + name.replace('-', '_'), "drawable");
        if (d == 0) return null;
        int mode = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (mode != night) { THEMES.clear(); night = mode; }   // dark mode changed: the colours resolve again
        Resources.Theme t = THEMES.get(ink);
        if (t == null) {
            int s = id(c, ink.style, "style");
            if (s == 0) return null;
            t = c.getResources().newTheme();
            t.setTo(c.getTheme());
            t.applyStyle(s, true);
            THEMES.put(ink, t);
        }
        try { return c.getResources().getDrawable(d, t); } catch (Resources.NotFoundException e) { return null; }
    }

    /** A row's tile (Ui.Setting: an emoji on the tint, 36 dp) with the icon instead of its emoji, 22 dp in its middle; false when there is no such icon */
    public static boolean tile(TextView tile, String name) {
        Context c = tile.getContext();
        Drawable d = get(c, name, Ink.NORMAL);
        if (d == null) return false;
        int size = tile.getLayoutParams() != null && tile.getLayoutParams().width > 0 ? tile.getLayoutParams().width : PUi.dp(c, 36);
        int inset = Math.max(0, (size - PUi.dp(c, size >= PUi.dp(c, 32) ? 22 : 18)) / 2);
        tile.setText("");
        tile.setForeground(new InsetDrawable(d, inset));
        return true;
    }
}
