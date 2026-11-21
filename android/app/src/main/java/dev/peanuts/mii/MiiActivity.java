package dev.peanuts.mii;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import dev.peanuts.ui.PUi;

/**
 * Settings › Miis: the player's own Miis, which games list as the console's (MiiStore writes files/mii/database.bin, the host reads it as DM_MII_DB). The list page
 * adds (from one of the six default Miis, or random), edits and deletes; the edit page has a stepper for every CharInfo field, grouped as the console's editor
 * groups them. The app draws no preview: a game's Select a Mii shows the result. A game started after a change sees it.
 */
public final class MiiActivity extends Activity {
    private static final String[] COLORS = {"red", "orange", "yellow", "light green", "green", "blue", "light blue", "pink", "purple", "brown", "white", "black"};
    private List<MiiStore.Mii> miis;
    private LinearLayout content;
    private FrameLayout frame;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(PUi.col(this, dev.nxnative.android.R.color.bn_bg));
        setContentView(frame);
        miis = MiiStore.load(getFilesDir());
        showList();
    }

    private void page(String title, Runnable back, android.view.View trailing) {
        frame.removeAllViews();
        content = PUi.page(this, frame, 640);
        content.addView(PUi.header(this, PUi.back(this, v -> back.run()), title, trailing));
    }

    private void save() {
        try { MiiStore.save(getFilesDir(), miis); }
        catch (IOException e) { Toast.makeText(this, "Could not save the Miis: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    // ---- the list ----
    private void showList() {
        page("Miis", this::finish, PUi.button(this, "Add", PUi.Kind.TONAL, v -> add()));
        PUi.add(content, PUi.small(this, "Your Miis appear in games as the console's own (Mario Kart 8 Deluxe's Select a Mii, for one), beside the six default Miis. "
                + "A game started after a change sees it."), 12);
        if (miis.isEmpty()) { PUi.add(content, PUi.body(this, "No Miis yet. Add one from a default Mii or a random one."), 8); return; }
        LinearLayout card = PUi.add(content, PUi.listCard(this), 8);
        for (int i = 0; i < miis.size(); i++) {
            final int idx = i; MiiStore.Mii m = miis.get(i);
            card.addView(new PUi.Setting(this, m.get("gender") == 1 ? "👩" : "👨", m.name, v -> showEdit(idx))
                    .value(PUi.joined(m.get("gender") == 1 ? "female" : "male", "favourite colour " + COLORS[m.get("favorite_color")])).chevron(this).view);
        }
    }

    private void add() {
        if (miis.size() >= MiiStore.MAX_MIIS) { Toast.makeText(this, "At most " + MiiStore.MAX_MIIS + " Miis", Toast.LENGTH_SHORT).show(); return; }
        String[] from = {"Default Mii 1 (man)", "Default Mii 2 (man)", "Default Mii 3 (man)", "Default Mii 4 (woman)", "Default Mii 5 (woman)", "Default Mii 6 (woman)", "Random"};
        new AlertDialog.Builder(this).setTitle("Start from").setItems(from, (d, w) -> {
            MiiStore.Mii m = w < 6 ? MiiStore.fromDefault(w, "Mii " + (miis.size() + 1)) : MiiStore.random("Mii " + (miis.size() + 1), new Random());
            miis.add(m); save(); showEdit(miis.size() - 1);
        }).show();
    }

    // ---- one Mii ----
    private void showEdit(int idx) {
        MiiStore.Mii m = miis.get(idx);
        page(m.name, () -> { save(); showList(); }, PUi.button(this, "Delete", PUi.Kind.TONAL, v ->
                PUi.confirmDanger(this, "Delete " + m.name + "?", "Games no longer list this Mii. A game that saved it keeps its own copy.", "Delete", () -> { miis.remove(idx); save(); showList(); })));
        LinearLayout top = PUi.add(content, PUi.listCard(this), 8);
        top.addView(new PUi.Setting(this, "✏️", "Name", v -> rename(idx)).value(m.name).chevron(this).view);
        top.addView(new PUi.Setting(this, "🎲", "Randomize", v -> { MiiStore.Mii r = MiiStore.random(m.name, new Random()); m.v.clear(); m.v.putAll(r.v); save(); showEdit(idx); }).view);
        top.addView(new PUi.Setting(this, "📋", "Duplicate", v -> {
            if (miis.size() >= MiiStore.MAX_MIIS) { Toast.makeText(this, "At most " + MiiStore.MAX_MIIS + " Miis", Toast.LENGTH_SHORT).show(); return; }
            miis.add(m.copy(MiiStore.copyName(m.name))); save(); showList();
        }).view);
        String section = null; LinearLayout card = null;
        for (MiiStore.Field fd : MiiStore.FIELDS) {
            if (!fd.section.equals(section)) { section = fd.section; PUi.add(content, PUi.section(this, section), PUi.SECTION_GAP); card = PUi.add(content, PUi.listCard(this), 8); }
            card.addView(stepper(m, fd));
        }
    }

    private void rename(int idx) {
        MiiStore.Mii m = miis.get(idx);
        EditText e = new EditText(this); e.setText(m.name); e.setSingleLine(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MiiStore.NAME_MAX)});
        PUi.sheet(this, "Name (up to " + MiiStore.NAME_MAX + " characters)", e).setPositiveButton("OK", (d, w) -> { m.name = MiiStore.cleanName(e.getText().toString()); save(); showEdit(idx); })
                .setNegativeButton("Cancel", null).show();
    }

    /** "<label>   [−] value [+]": each tap one step, kept within the console's range and saved at once */
    private LinearLayout stepper(MiiStore.Mii m, MiiStore.Field fd) {
        LinearLayout row = PUi.row(this);
        row.setPadding(PUi.dp(this, 14), PUi.dp(this, 6), PUi.dp(this, 10), PUi.dp(this, 6));
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(PUi.body(this, fd.label));
        TextView range = PUi.small(this, fd.min + " to " + fd.max);
        col.addView(range);
        row.addView(col, PUi.weight());
        TextView value = PUi.text(this, label(fd, m.get(fd.key)), 16, dev.nxnative.android.R.color.bn_text, 600);
        value.setGravity(Gravity.CENTER); value.setMinWidth(PUi.dp(this, 64));
        Button minus = PUi.button(this, "−", PUi.Kind.TONAL, v -> { m.set(fd.key, m.get(fd.key) - 1); value.setText(label(fd, m.get(fd.key))); save(); });
        Button plus = PUi.button(this, "+", PUi.Kind.TONAL, v -> { m.set(fd.key, m.get(fd.key) + 1); value.setText(label(fd, m.get(fd.key))); save(); });
        row.addView(minus, PUi.wrap()); row.addView(value, PUi.wrap()); row.addView(plus, PUi.wrap());
        return row;
    }

    private static String label(MiiStore.Field fd, int v) {
        if (fd.key.equals("gender")) return v == 1 ? "female" : "male";
        if (fd.key.equals("favorite_color")) return COLORS[Math.max(0, Math.min(11, v))];
        if (fd.max == 1) return v == 1 ? "yes" : "no";
        return String.valueOf(v);
    }
}
