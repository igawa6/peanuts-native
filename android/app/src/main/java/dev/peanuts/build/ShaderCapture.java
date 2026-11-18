package dev.peanuts.build;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Internal preparation mode of the non-exported GameActivity; never a normal play session. */
public final class ShaderCapture {
    private static final String DIR = "pn_capture_dir", SAVE = "pn_capture_save";
    private ShaderCapture() {}

    static Intent intent(android.content.Context c, String title, File game, File pack, File dir, File save) {
        return new Intent().setClassName(c.getPackageName(), "dev.nxnative.android.GameActivity")
                .putExtra("title_id", title).putExtra("name", game.getName()).putExtra("game", game.getPath())
                .putExtra("pack", pack.getPath()).putExtra(DIR, dir.getPath()).putExtra(SAVE, save.getPath())
                .putExtra("no_pause", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    public static boolean active(Activity a) { return a.getIntent().hasExtra(DIR); }

    public static File save(Activity a) throws IOException {
        if (!active(a)) return null;
        File save = new File(a.getIntent().getStringExtra(SAVE));
        if (!save.isDirectory() && !save.mkdirs()) throw new IOException("Cannot make the graphics preparation folder.");
        return save;
    }

    public static void environment(Activity a, List<String> env) {
        if (!active(a)) return;
        // A preparation boot gets no player's save, scripted input, companion or sound.
        // PEANUTS: use the host's single default account; a multi-user selector would wait for input during Build.
        env.removeIf(s -> s.startsWith("DM_KEYS=") || s.startsWith("DM_SAVE=") || s.startsWith("DM_GLSLC_CAPTURE=")
                || s.startsWith("DM_AUDIO=") || s.startsWith("PN_COMPANION=")
                || s.startsWith("DM_USERS=") || s.startsWith("DM_USER="));
        env.add("DM_SAVE=" + a.getIntent().getStringExtra(SAVE));
        env.add("DM_GLSLC_CAPTURE=" + a.getIntent().getStringExtra(DIR));
        env.add("DM_AUDIO=0");
        env.add("PN_COMPANION=off");
    }

    /** Keep the rendering surface alive underneath an opaque preparation screen. */
    public static void cover(Activity a, FrameLayout root) {
        if (!active(a)) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xff101714);
        box.setClickable(true);
        box.addView(new ProgressBar(a));
        TextView text = new TextView(a);
        text.setText("Finishing graphics setup\n\nThis step finishes automatically.");
        text.setGravity(Gravity.CENTER);
        text.setTextColor(Color.WHITE);
        text.setTextSize(20);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        box.addView(text);
        Button cancel = new Button(a);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> cancel(a));
        box.addView(cancel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
    }

    public static void cancel(Activity a) {
        a.startService(new Intent(a, BuildService.class).setAction(BuildService.ACTION_CANCEL));
    }

    public static void failure(Activity a, String why) {
        try { Files.write(error(new File(a.getIntent().getStringExtra(DIR))).toPath(), why.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { android.util.Log.w("peanuts.build", "capture error: " + e); }
    }

    static File error(File dir) { return new File(dir, "capture-error.txt"); }
}
