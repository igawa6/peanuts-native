package dev.peanuts.mii;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;
import dev.peanuts.users.Bottom;
import dev.peanuts.users.UserPictures;

/**
 * Settings › Users › Miis: the player's own Miis, which games list as the console's (MiiStore writes files/mii/database.bin, the host reads it as DM_MII_DB), shared
 * by every user. The top screen shows them as cards with their faces, drawn from the firmware's parts (New Mii and Import Miis first); the bottom screen acts on
 * the focused one: Edit (the Mii editor, MiiEditor), Duplicate and Delete. Import Miis takes the Miis of another Switch emulator (Eden, yuzu, Ryujinx: their
 * MiiDatabase.dat, a zip of their exported user data, or a .charinfo file; MiiImport, GitHub issue #320). A game started after a change sees it.
 */
public final class MiiActivity extends Activity {
    static final int EDIT = 1, IMPORT = 2;
    private static final String[] COLORS = {"Red", "Orange", "Yellow", "Light green", "Green", "Blue", "Light blue", "Pink", "Purple", "Brown", "White", "Black"};

    private List<MiiStore.Mii> miis;
    private FrameLayout frame;
    private Bottom bottom;
    private String focused;
    private boolean wide;
    private final Map<String, View> cards = new HashMap<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(PUi.col(this, R.color.bn_bg));
        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1));
        FrameLayout inline = new FrameLayout(this);
        root.addView(inline, new LinearLayout.LayoutParams(wide ? PUi.dp(this, 380) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        bottom = new Bottom(this, inline);
        if (saved != null) focused = saved.getString("focused");
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("focused", focused); }
    @Override protected void onStart() { super.onStart(); bottom.start(); build(); }
    @Override protected void onStop() { bottom.stop(); super.onStop(); }

    private MiiStore.Mii find(String id) { for (MiiStore.Mii m : miis) if (MiiStore.hex(m.createId).equals(id)) return m; return null; }

    // ---- The top screen ----

    private void build() {
        miis = MiiStore.load(getFilesDir());
        if (focused == null || find(focused) == null) focused = miis.isEmpty() ? null : MiiStore.hex(miis.get(0).createId);
        frame.removeAllViews();
        cards.clear();
        LinearLayout content = PUi.page(this, frame, 760);
        content.addView(PUi.header(this, PUi.back(this, v -> finish()), "Miis", PUi.chip(this, miis.size() + " of " + MiiStore.MAX_MIIS)));
        PUi.add(content, PUi.small(this, "Games that ask for a Mii show yours first, then the six default Miis. A game started after a change sees it."), 0);
        if (!MiiGl.available(this))
            PUi.add(content, PUi.card(this), 12).addView(PUi.body(this, "Your firmware's Mii parts are missing, so the Miis show as letters. Add your firmware under Console in Settings to see their faces."));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(Bottom.columns(this, wide ? 136 : 100, 5, 380, 5));   // as many as fit beside the bottom half, when it is on this screen
        if (miis.size() < MiiStore.MAX_MIIS) { grid.addView(newMii(), cell()); grid.addView(importCard(), cell()); }
        View focus = null;
        for (MiiStore.Mii m : miis) {
            View c = card(m);
            grid.addView(c, cell());
            if (MiiStore.hex(m.createId).equals(focused)) focus = c;
        }
        PUi.add(content, grid, 12);
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
        bottom.set(this::actions);
        Feel.page(content, 1);
    }

    private GridLayout.LayoutParams cell() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED));
        lp.width = PUi.dp(this, wide ? 136 : 100);
        int h = PUi.dp(this, 5);
        lp.setMargins(h, h, h, h);
        return lp;
    }

    private static GradientDrawable cardBg(Context c, boolean on) {
        return PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 18), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 2 : 1));
    }

    /** A Mii's card: its face on the band of its favourite colour's dark tone, its name */
    private View card(MiiStore.Mii m) {
        String id = MiiStore.hex(m.createId);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(this, 6);
        c.setPadding(p, p, p, PUi.dp(this, 8));
        c.setBackground(cardBg(this, id.equals(focused)));
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(m.name);
        c.addView(face(this, m, 118, 14, true));
        TextView t = PUi.text(this, m.name, 14, R.color.bn_text, 600);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, PUi.dp(this, 6), 0, 0);
        c.addView(t);
        cards.put(id, c);
        c.setOnFocusChangeListener((v, has) -> { if (has) pick(id); });
        c.setOnClickListener(Feel.row(v -> { pick(id); v.requestFocus(); }));
        return c;
    }

    /** A Mii's face in a rounded box of the dark tone that matches its favourite colour (the profile pictures' backgrounds) */
    static View face(Context c, MiiStore.Mii m, int heightDp, int radiusDp, boolean wide) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(PUi.round(0xff000000 | UserPictures.BACKGROUNDS[tone(m.get("favorite_color"))], PUi.dp(c, radiusDp), 0, 0));
        f.setClipToOutline(true);
        f.setLayoutParams(new ViewGroup.LayoutParams(wide ? ViewGroup.LayoutParams.MATCH_PARENT : PUi.dp(c, heightDp), PUi.dp(c, heightDp)));
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (MiiGl.available(c)) MiiGl.get(c).picture(m, PUi.dp(c, heightDp), 0, b -> { if (b != null) { img.setImageBitmap(b); Feel.appeared(img); } });
        else {
            TextView l = PUi.text(c, m.name.isEmpty() ? "?" : m.name.substring(0, m.name.offsetByCodePoints(0, 1)), heightDp * 0.4f, R.color.bn_text, 800);
            l.setTextColor(0xE6FFF1D2);
            l.setGravity(Gravity.CENTER);
            f.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return f;
    }

    /** The background tone for a favourite colour: red, orange, yellow, light green, green, blue, light blue, pink, purple, brown, white, black */
    static int tone(int favorite) {
        int[] t = {2, 3, 3, 1, 1, 0, 6, 2, 4, 7, 5, 5};
        return t[Math.max(0, Math.min(11, favorite))];
    }

    private View newMii() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(PUi.dp(this, 158));
        GradientDrawable d = PUi.round(0, PUi.dp(this, 18), 0, 0);
        d.setStroke(PUi.dp(this, 1.5f), PUi.col(this, R.color.bn_text_2), PUi.dp(this, 6), PUi.dp(this, 4));
        c.setBackground(PUi.ripple(this, d));
        c.setFocusable(true);
        c.setClickable(true);
        ImageView plus = new ImageView(this);
        plus.setImageDrawable(PUi.icon(this, R.drawable.ic_add, R.color.bn_text_2));
        c.addView(plus, new LinearLayout.LayoutParams(PUi.dp(this, 26), PUi.dp(this, 26)));
        TextView t = PUi.text(this, "New Mii", 13, R.color.bn_text_2, 600);
        t.setPadding(0, PUi.dp(this, 6), 0, 0);
        c.addView(t, PUi.wrap());
        c.setOnClickListener(Feel.button(v -> MiiEditor.edit(this, EDIT, null)));
        return c;
    }

    /** Import Miis: a dashed card as New Mii's, the download icon (Peanuts icons) */
    private View importCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(PUi.dp(this, 158));
        GradientDrawable d = PUi.round(0, PUi.dp(this, 18), 0, 0);
        d.setStroke(PUi.dp(this, 1.5f), PUi.col(this, R.color.bn_text_2), PUi.dp(this, 6), PUi.dp(this, 4));
        c.setBackground(PUi.ripple(this, d));
        c.setFocusable(true);
        c.setClickable(true);
        ImageView icon = new ImageView(this);
        android.graphics.drawable.Drawable dl = dev.peanuts.ui.Icons.get(this, "download", dev.peanuts.ui.Icons.Ink.QUIET);
        icon.setImageDrawable(dl != null ? dl : PUi.icon(this, R.drawable.ic_add, R.color.bn_text_2));
        c.addView(icon, new LinearLayout.LayoutParams(PUi.dp(this, 26), PUi.dp(this, 26)));
        TextView t = PUi.text(this, "Import Miis", 13, R.color.bn_text_2, 600);
        t.setPadding(0, PUi.dp(this, 6), 0, 0);
        c.addView(t, PUi.wrap());
        TextView from = PUi.text(this, "From Eden, yuzu or Ryujinx", 11, R.color.bn_text_2, 400);
        from.setGravity(Gravity.CENTER);
        from.setPadding(PUi.dp(this, 6), PUi.dp(this, 2), PUi.dp(this, 6), 0);
        c.addView(from, PUi.wrap());
        c.setOnClickListener(Feel.button(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(pick, IMPORT); }
            catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, "No file picker on this device.", Toast.LENGTH_LONG).show(); }
        }));
        return c;
    }

    /** The picked file, read off the main thread (a zip of user data can be large: it is streamed, only the Mii database is kept) */
    private void importFrom(android.net.Uri u) {
        String file = displayName(u);
        new Thread(() -> {
            MiiImport.Found f;
            try (java.io.InputStream in = getContentResolver().openInputStream(u)) { f = in == null ? null : MiiImport.read(in); }
            catch (IOException | RuntimeException e) { f = null; }
            MiiImport.Found found = f;
            runOnUiThread(() -> { if (!isFinishing()) offer(found, file); });
        }, "mii-import").start();
    }

    private String displayName(android.net.Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return "the file";
    }

    /** What the file holds, and Import for the new ones (create IDs not among the Miis yet, as many as fit under 100) */
    private void offer(MiiImport.Found f, String file) {
        miis = MiiStore.load(getFilesDir());
        int found = f == null ? 0 : f.miis.size();
        List<MiiStore.Mii> add = found == 0 ? new java.util.ArrayList<>() : MiiImport.newOnes(miis, f.miis);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        if (found == 0) {
            b.setTitle("No Miis found").setMessage(file + " holds no Miis. Pick the Mii database of Eden, yuzu or Ryujinx: MiiDatabase.dat, in "
                    + "nand/system/save/8000000000000030 (Eden shows its files in the picker under its own name). A zip of the emulator's exported user data "
                    + "or a .charinfo file works too.").setPositiveButton("OK", null);
        } else if (add.isEmpty()) {
            b.setTitle("Nothing new").setMessage(miis.size() >= MiiStore.MAX_MIIS ? "There are already " + MiiStore.MAX_MIIS + " Miis. Delete some first."
                    : (found == 1 ? "This Mii is" : "All " + found + " Miis in " + file + " are") + " among your Miis already.").setPositiveButton("OK", null);
        } else {
            int already = 0;
            java.util.Set<String> have = new java.util.HashSet<>();
            for (MiiStore.Mii m : miis) have.add(MiiStore.hex(m.createId));
            for (MiiStore.Mii m : f.miis) if (have.contains(MiiStore.hex(m.createId))) already++;
            StringBuilder msg = new StringBuilder(file + " holds " + (found == 1 ? "1 Mii" : found + " Miis") + ".");
            if (already > 0) msg.append(" ").append(already == 1 ? "1 is" : already + " are").append(" among your Miis already.");
            int over = found - already - add.size();
            if (over > 0) msg.append(" ").append(over).append(" more do not fit: a console keeps ").append(MiiStore.MAX_MIIS).append(".");
            b.setTitle(add.size() == 1 ? "Import 1 Mii?" : "Import " + add.size() + " Miis?").setMessage(msg)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Import", (x, w) -> {
                        miis.addAll(add);
                        if (!save()) return;
                        dev.peanuts.ui.Cracked.mii(this);   // Peanuts cracked: the first Mii of the player's own
                        focused = MiiStore.hex(add.get(0).createId);
                        build();
                    });
        }
        android.app.AlertDialog d = b.create();
        Feel.dialog(d);
        d.show();
    }

    private void pick(String id) {
        if (id.equals(focused)) return;
        focused = id;
        for (Map.Entry<String, View> e : cards.entrySet()) e.getValue().setBackground(cardBg(this, e.getKey().equals(id)));
        bottom.refresh();
    }

    // ---- The bottom screen: the focused Mii ----

    private View actions(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        col.setPadding(p, p, p, p);
        MiiStore.Mii m = focused != null ? find(focused) : null;
        if (m == null) {
            col.addView(PUi.body(c, "No Miis yet. Make one with New Mii, or bring yours from another emulator with Import Miis; games that ask for a Mii show them first."));
            PUi.add(col, PUi.button(c, "New Mii", PUi.Kind.PRIMARY, Feel.button(v -> MiiEditor.edit(this, EDIT, null))), 12);
            return col;
        }
        LinearLayout card = PUi.card(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14));
        card.addView(face(c, m, 132, 16, false));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(PUi.dp(c, 14), 0, 0, 0);
        TextView n = PUi.text(c, m.name, 26, R.color.bn_text, 800);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(n);
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(0, PUi.dp(c, 8), 0, 0);
        chips.addView(PUi.chip(c, COLORS[Math.max(0, Math.min(11, m.get("favorite_color")))]));
        text.addView(chips);
        card.addView(text, PUi.weight());
        col.addView(card, PUi.fill());
        PUi.add(col, PUi.button(c, "Edit " + m.name, PUi.Kind.PRIMARY, Feel.button(v -> MiiEditor.edit(this, EDIT, focused))), 12);
        View dup = PUi.button(c, "Duplicate", PUi.Kind.TONAL, Feel.button(v -> duplicate(m)));
        View del = PUi.button(c, "Delete", PUi.Kind.DANGER, Feel.button(v -> delete(m)));
        PUi.add(col, PUi.pair(c, dup, del), 8);
        return col;
    }

    private void duplicate(MiiStore.Mii m) {
        if (miis.size() >= MiiStore.MAX_MIIS) { Toast.makeText(this, "There are already " + MiiStore.MAX_MIIS + " Miis. Delete one first.", Toast.LENGTH_LONG).show(); return; }
        MiiStore.Mii copy = m.copy(MiiStore.copyName(m.name));
        miis.add(copy);
        if (!save()) return;
        focused = MiiStore.hex(copy.createId);
        build();
    }

    private void delete(MiiStore.Mii m) {
        PUi.confirmDanger(this, "Delete " + m.name + "?", "Games no longer list this Mii. A game that saved it keeps its own copy. A user whose picture it was keeps their background.", "Delete", () -> {
            miis.remove(m);
            if (!save()) return;
            dev.peanuts.users.UserImages.miiGone(this, MiiStore.hex(m.createId));   // games no longer get it as a user's picture
            try { UserPictures.forgetMii(getFilesDir(), MiiStore.hex(m.createId)); } catch (IOException ignored) { }
            focused = null;
            build();
        });
    }

    private boolean save() {
        try { MiiStore.save(getFilesDir(), miis); return true; }
        catch (IOException e) { Toast.makeText(this, "The Miis were not saved: the app could not write their files. Try again.", Toast.LENGTH_LONG).show(); return false; }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == IMPORT && result == RESULT_OK && data != null && data.getData() != null) { importFrom(data.getData()); return; }
        String id = MiiEditor.result(result, data);
        if (request == EDIT && id != null) focused = id;   // the edited or new Mii is the focused one (build() runs in onStart)
    }
}
