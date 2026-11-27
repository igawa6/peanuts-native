package dev.peanuts.launch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import dev.nxnative.android.R;

import java.util.Collections;
import java.util.Locale;

/**
 * Home screen shortcuts for one Peanuts-built game (LAUNCHERS.md "Home screen shortcuts"). A shortcut's intent is an explicit {@code ACTION_MAIN} at
 * {@code dev.nxnative.android.FrontendLaunch} carrying only the extra {@code title_id}: the same path emulator frontends take (FRONTENDS.md). A ready
 * game starts at once; anything that needs the user (no pack, shaders to make, All files access) opens Home with that game instead. The intent is never
 * built from a path or a URI the caller supplies — only a title ID the library itself scanned — and {@code FrontendLaunch.byTitleId} looks that title ID
 * up again against the library's own list before doing anything, so a shortcut (or a dynamic shortcut, or anyone else who starts this exact component
 * with an unknown title ID) can never reach a file outside the library.
 * <p>
 * This duplicates the small intent/pin shape of upstream's own {@code dev.nxnative.android.Shortcuts}: that class is package-private, and its icon is
 * always a plain letter tile. Here the icon is the game's own cover (the library's cached {@code files/icons/<title ID>.png}, {@code LibraryScanner.icon}),
 * since Peanuts' Home already shows that art on every card; the letter tile is only the fallback for a game with no cover yet.
 */
public final class GameShortcuts {
    private GameShortcuts() { }

    static String id(String titleId) { return "game-" + titleId.toUpperCase(Locale.ROOT); }

    /** The intent a pinned or dynamic shortcut for the title runs: an explicit class name, so only this app's own activity ever answers it */
    static Intent intent(Context c, String titleId) {
        return new Intent(Intent.ACTION_MAIN).setClassName(c.getPackageName(), "dev.nxnative.android.FrontendLaunch").putExtra("title_id", titleId.toUpperCase(Locale.ROOT));
    }

    public static boolean supported(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /**
     * Asks the launcher to pin the game's shortcut (the launcher shows its own confirmation); a shortcut already pinned is updated in place. {@code cover}
     * is the library's cover bitmap when there is one (GameSheet decodes it off the UI thread before calling this), else null for the letter fallback
     */
    public static void pin(Context c, String titleId, String name, Bitmap cover) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(c, "This launcher cannot pin shortcuts.", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo info = build(c, titleId, name, cover);
        try {
            if (!sm.requestPinShortcut(info, null)) Toast.makeText(c, "The launcher did not accept the shortcut.", Toast.LENGTH_LONG).show();
            else sm.updateShortcuts(Collections.singletonList(info));   // already pinned: a renamed game's label and a newly-found cover follow
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "GameShortcuts: pin " + titleId + " failed: " + e);
            Toast.makeText(c, "Could not add the shortcut: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** The {@link ShortcutInfo} for one title, shared by a pinned shortcut ({@link #pin}) and a dynamic one ({@link RecentGames}) */
    static ShortcutInfo build(Context c, String titleId, String name, Bitmap cover) {
        String label = name == null || name.isEmpty() ? titleId : name;
        return new ShortcutInfo.Builder(c, id(titleId))
                .setShortLabel(label.length() > 25 ? label.substring(0, 24) + "…" : label)
                .setLongLabel(label)
                .setIcon(icon(c, label, cover))
                .setIntent(intent(c, titleId))
                .build();
    }

    /** The whole cover in the part of an adaptive icon a launcher shows, on its own average colour (ShortcutArt), else a letter tile on banana yellow (upstream
     *  Shortcuts.icon's look, kept the same) */
    private static Icon icon(Context c, String name, Bitmap cover) {
        return cover != null ? Icon.createWithAdaptiveBitmap(framed(cover)) : letterIcon(c, name);
    }

    // Not cropped and full-bleed any more: a launcher shows only the middle 72 of 108 dp, so a tall cover showed about half of itself, zoomed in (issue #258)
    private static Bitmap framed(Bitmap src) {
        int size = 432;   // 108 dp at 4x
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Bitmap small = Bitmap.createScaledBitmap(src, 16, 16, true);   // the background's colour from a 16 x 16 copy, not every pixel of a large cover
        int[] px = new int[16 * 16];
        small.getPixels(px, 0, 16, 0, 0, 16, 16);
        if (small != src) small.recycle();
        cv.drawColor(ShortcutArt.average(px));
        int[] r = ShortcutArt.fit(src.getWidth(), src.getHeight(), size);
        cv.drawBitmap(src, null, new Rect(r[0], r[1], r[2], r[3]), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    /** The same letter-tile look as upstream's Shortcuts.icon, for a title whose cover has not been fetched yet */
    private static Icon letterIcon(Context c, String name) {
        int size = 432;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(c.getColor(R.color.bn_banana));
        String n = name == null ? "" : name.trim();
        String letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(c.getColor(R.color.bn_on_banana));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
        p.setTextSize(size * 0.36f);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(letter, 0, letter.length(), r);
        cv.drawText(letter, size / 2f, size / 2f - r.exactCenterY(), p);
        return Icon.createWithAdaptiveBitmap(b);
    }
}
