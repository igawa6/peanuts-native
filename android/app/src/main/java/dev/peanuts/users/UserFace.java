package dev.peanuts.users;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

import dev.nxnative.android.R;
import dev.peanuts.mii.MiiGl;
import dev.peanuts.mii.MiiStore;
import dev.peanuts.ui.PUi;

/**
 * A user's picture in a rounded box of its background colour: the Mii it shows (drawn from the firmware's parts), a firmware picture, or the name's first letter
 * while there is none, as a game's tile shows its first letter. Pictures are drawn off the main thread and fade in.
 */
public final class UserFace {
    private UserFace() {}

    /** A box of the picture, sizeDp tall and wide (or as wide as its parent gives it when wide is true), rounded by radiusDp */
    public static FrameLayout box(Context c, String name, UserPictures.Picture p, int sizeDp, int radiusDp, boolean wide) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(PUi.round(0xff000000 | p.color(), PUi.dp(c, radiusDp), 0, 0));
        int r = PUi.dp(c, radiusDp);
        f.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } });
        f.setClipToOutline(true);
        f.setLayoutParams(new ViewGroup.LayoutParams(wide ? ViewGroup.LayoutParams.MATCH_PARENT : PUi.dp(c, sizeDp), PUi.dp(c, sizeDp)));
        String n = name == null ? "" : name.trim();
        TextView letter = PUi.text(c, n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT), sizeDp * 0.42f, R.color.bn_text, 800);
        letter.setTextColor(0xE6FFF1D2);   // the backgrounds are dark in both themes: a light letter on them
        letter.setGravity(Gravity.CENTER);
        letter.setIncludeFontPadding(false);
        letter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(letter, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        picture(c, p, PUi.dp(c, sizeDp), b -> {
            if (b == null) return;
            letter.setVisibility(View.GONE);
            img.setAlpha(0f);
            img.setImageBitmap(b);
            img.animate().alpha(1f).setDuration(180).start();
        });
        return f;
    }

    /** The picture's bitmap of about px pixels, or null when it has none or it cannot be drawn (on the main thread, later) */
    public static void picture(Context c, UserPictures.Picture p, int px, Consumer<Bitmap> done) {
        String mii = p.mii();
        if (mii != null && MiiGl.available(c)) {
            MiiStore.Mii m = find(MiiStore.load(c.getFilesDir()), mii);
            if (m != null) { MiiGl.get(c).picture(m, px, 0, done); return; }
        }
        String fw = p.firmware();
        if (fw != null) { FirmwarePictures.bitmap(c, fw, done); return; }
        done.accept(null);
    }

    /** A settings row's tile (PUi.Setting) with one of the Peanuts icons (dev.peanuts.ui.Icons, pi_<name>) in place of its text; the text stays in a build without it */
    public static void rowIcon(Context c, View settingRow, String name) {
        View tile = ((ViewGroup) settingRow).getChildAt(0);
        if (tile instanceof TextView) dev.peanuts.ui.Icons.tile((TextView) tile, name);
    }

    static MiiStore.Mii find(List<MiiStore.Mii> all, String createId) {
        for (MiiStore.Mii m : all) if (createId.equals(MiiStore.hex(m.createId))) return m;
        return null;
    }
}
