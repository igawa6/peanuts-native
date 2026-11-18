package dev.nxnative.android;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings > Advanced and a game's Advanced page: a full page (MainActivity.openAdvanced puts it in place of the page it was opened from; Back returns). Every row is the
 * same shape: its name, one line "Try this if ...", one line "Auto: ...", and a row of chips (Auto, then its values), one tap to set. A toggle, a short choice and a number with
 * presets are chips; any other number, and the developer rows' text, open a small dialog. The list shows the rows a player may set that are measured; "Experimental" adds the
 * rest, "Dev" the rows a launcher control owns and the developer rows by area. A search shows at most AdvancedTable.SEARCH_MAX rows.
 */
final class AdvancedPage {
    private AdvancedPage() {}

    private static boolean dev, experimental;   // the two switches, kept while the launcher runs
    private static final java.util.Set<String> open = new java.util.HashSet<>();   // areas the player opened (Dev), kept while the launcher runs

    /** Opens the page over the current one (titleId null: Settings' page for every game; else that game's, name for its title) */
    static void show(MainActivity a, String titleId, String name, Runnable done) { a.openAdvanced(titleId, name); }

    /** The page's content (MainActivity.build): header with Back, search, the two switches, the list */
    static void build(MainActivity a, LinearLayout content, String titleId, String name, Runnable back) {
        List<AdvancedTable.Row> rows = AdvancedSettings.table(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        Runnable[] fill = new Runnable[1];
        TextView reset = pill(a, "Reset all", v -> Ui.confirmDanger(a, "Reset all", titleId == null ? "Every row you set under Advanced goes back to Auto, for every game." : "Every row you set for " + name + " goes back to Auto.",
                "Reset all", () -> { AdvancedSettings.resetAll(a, titleId); fill[0].run(); }));
        content.addView(Ui.header(a, Ui.back(a, v -> back.run()), "Advanced", name, reset));
        Ui.add(content, Ui.small(a, titleId == null ? "Every switch starts at Auto: nothing is written and the app decides. A change applies at the next start; a line in env.txt or Extra environment wins."
                : "For " + name + " only; these win over Settings > Advanced. Auto writes nothing. A change applies at the next start."), 6);
        EditText q = new EditText(a);
        q.setHint("Search a name, a symptom or DM_...");
        q.setTextSize(15);
        q.setSingleLine(true);
        q.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.add(content, q, 8);
        LinearLayout sw = new LinearLayout(a);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        Switch exp = new Switch(a), dv = new Switch(a);
        exp.setText("Experimental  ");
        dv.setText("Developer  ");
        exp.setChecked(experimental);
        dv.setChecked(dev);
        sw.addView(exp, Ui.wrap());
        sw.addView(dv, Ui.wrap());
        Ui.add(content, sw, 4);
        Ui.add(content, list, 4);
        fill[0] = () -> {
            list.removeAllViews();
            String query = q.getText().toString().trim();
            if (!query.isEmpty()) {
                List<AdvancedTable.Row> hits = AdvancedTable.visible(rows, titleId, dev, query);
                if (hits.isEmpty()) list.addView(note(a, "Nothing matches" + (dev ? "." : ". Turn on Developer to search every switch.")));
                for (AdvancedTable.Row r : hits) list.addView(row(a, r, titleId));
            } else if (dev) areas(a, list, rows, titleId, fill[0]);
            else common(a, list, rows, titleId);
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        exp.setOnCheckedChangeListener((CompoundButton b, boolean on) -> { experimental = on; fill[0].run(); });
        dv.setOnCheckedChangeListener((CompoundButton b, boolean on) -> { dev = on; fill[0].run(); });
        fill[0].run();
    }

    private static TextView pill(Context a, String s, View.OnClickListener l) {
        TextView t = Ui.text(a, s, 14, R.color.bn_text, 700);
        t.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        t.setBackground(Ui.ripple(a, Ui.round(Ui.col(a, R.color.bn_tint), Ui.dp(a, 999), 0, 0)));
        t.setOnClickListener(l);
        t.setFocusable(true);
        return t;
    }

    private static TextView note(Context a, String s) {
        TextView t = Ui.small(a, s);
        t.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 4));
        return t;
    }

    /** The rows a player may set: friendly then advanced, the measured ones; "Experimental" adds the others; no area headers, no developer rows, no row a launcher control owns */
    private static void common(MainActivity a, LinearLayout list, List<AdvancedTable.Row> rows, String titleId) {
        int n = 0;
        for (String tier : new String[] {"friendly", "advanced"})
            for (AdvancedTable.Row r : AdvancedTable.visible(rows, titleId, false, ""))
                if (r.editable() && r.tier.equals(tier) && (experimental || !r.experimental())) { list.addView(row(a, r, titleId)); n++; }
        if (n == 0) list.addView(note(a, "Nothing to set here" + (experimental ? "." : ". Experimental shows the rest.")));
        list.addView(note(a, "Search finds more switches."));
    }

    /** Developer on: every shown row by area, an area built when opened */
    private static void areas(MainActivity a, LinearLayout list, List<AdvancedTable.Row> rows, String titleId, Runnable refresh) {
        for (String area : AdvancedTable.AREAS) {
            List<AdvancedTable.Row> mine = new ArrayList<>();
            for (AdvancedTable.Row r : AdvancedTable.visible(rows, titleId, true, "")) if (r.area.equals(area)) mine.add(r);
            if (mine.isEmpty()) continue;
            boolean isOpen = open.contains(area);
            TextView h = Ui.section(a, (isOpen ? "▾ " : "▸ ") + area + " (" + mine.size() + ")");
            h.setOnClickListener(v -> { if (!open.remove(area)) open.add(area); refresh.run(); });
            list.addView(h);
            if (!isOpen) continue;
            for (String tier : new String[] {"friendly", "advanced", "developer"})
                for (AdvancedTable.Row r : mine) if (r.tier.equals(tier)) list.addView(row(a, r, titleId));
        }
    }

    /** The chips of a row: label and value ("" for Auto), or null when the row is not a chip row */
    private static List<String[]> chipsOf(AdvancedTable.Row r) {
        if (r.owned() || r.developer()) return null;
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"Auto", ""});
        if (r.type.equals("number")) { for (String v : r.presets()) out.add(new String[] {r.unit().isEmpty() ? v : v + " " + r.unit(), v}); }
        else out.addAll(r.options());
        return out.size() > 1 && out.size() <= 6 ? out : null;
    }

    private static View row(MainActivity a, AdvancedTable.Row r, String titleId) {
        List<String[]> chips = chipsOf(r);
        if (chips == null) return tapRow(a, r, titleId);
        String glob = titleId == null ? null : AdvancedSettings.own(a, r.name, null);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 6));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.addView(Ui.text(a, r.title(), 16, R.color.bn_text, 700), Ui.wrap());
        if (r.experimental()) {
            LinearLayout.LayoutParams bp = Ui.wrap();
            bp.setMarginStart(Ui.dp(a, 8));
            t.addView(Ui.tag(a, "Experimental", true), bp);
        }
        box.addView(t, Ui.fill());
        if (!r.tryIf.isEmpty()) box.addView(Ui.small(a, "Try this if " + r.tryIf + "."), Ui.fill());
        box.addView(Ui.small(a, "Auto: " + r.deflt + (glob != null ? ". Settings: " + r.optionLabel(glob) + "." : ".")), Ui.fill());
        if (r.experimental() && !r.warn.isEmpty()) {
            TextView w = Ui.small(a, r.warn);
            w.setTextColor(Ui.col(a, R.color.bn_danger));
            box.addView(w, Ui.fill());
        }
        Flow flow = new Flow(a, Ui.dp(a, 8));
        LinearLayout.LayoutParams fp = Ui.fill();
        fp.topMargin = Ui.dp(a, 8);
        box.addView(flow, fp);
        TextView[] cells = new TextView[chips.size()];
        for (int i = 0; i < chips.size(); i++) {
            final String value = chips.get(i)[1];
            TextView c = Ui.text(a, chips.get(i)[0], 14, R.color.bn_text, 600);
            c.setGravity(android.view.Gravity.CENTER);
            c.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
            c.setFocusable(true);
            c.setOnClickListener(v -> {
                AdvancedSettings.set(a, r.name, titleId, value.isEmpty() ? null : value);
                style(a, cells, chips, AdvancedSettings.own(a, r.name, titleId));
            });
            flow.addView(c);
            cells[i] = c;
        }
        style(a, cells, chips, AdvancedSettings.own(a, r.name, titleId));
        return box;
    }

    private static void style(Context a, TextView[] cells, List<String[]> chips, String own) {
        int sel = 0;
        for (int i = 1; i < chips.size(); i++) if (chips.get(i)[1].equals(own)) sel = i;
        for (int i = 0; i < cells.length; i++) {
            boolean on = i == sel;
            cells[i].setBackground(Ui.ripple(a, Ui.round(Ui.col(a, on ? R.color.bn_banana : R.color.bn_tint), Ui.dp(a, 12), 0, 0)));
            cells[i].setTextColor(Ui.col(a, on ? R.color.bn_on_banana : R.color.bn_text));
        }
    }

    /** Chips side by side, wrapping to the next line when a chip does not fit (a word is never broken) */
    private static final class Flow extends ViewGroup {
        private final int gap;
        Flow(Context c, int gap) { super(c); this.gap = gap; }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }

    /** A row that opens a dialog (a number without presets, a developer row's text), or says where a launcher control is (an owned row; shown to developers) */
    private static View tapRow(MainActivity a, AdvancedTable.Row r, String titleId) {
        String own = AdvancedSettings.own(a, r.name, titleId), glob = titleId == null ? null : AdvancedSettings.own(a, r.name, null);
        Ui.Setting s = new Ui.Setting(a, r.developer() ? "🛠" : "⚙", r.developer() ? r.name : r.title(), null);
        String shown = own != null ? r.optionLabel(own) : glob != null ? r.optionLabel(glob) + " (from Settings)" : "Auto" + (r.deflt.isEmpty() ? "" : ": " + r.deflt);
        if (r.owned()) {
            s.value("Set in " + r.owner + ".");
            s.view.setOnClickListener(v -> new PadFocus.Builder(a).setTitle(r.title()).setMessage("This one has its own control: " + r.owner + ".").setPositiveButton("OK", null).show());
        } else {
            s.value(shown).chevron(a);
            s.view.setOnClickListener(v -> edit(a, r, titleId, s));
        }
        s.note(r.developer() ? AdvancedSettings.meaning(a, r.name) : r.tryIf.isEmpty() || r.owned() ? null : "Try this if " + r.tryIf + ".");
        s.note.setMaxLines(8);
        s.value.setMaxLines(6);
        if (r.experimental()) s.badge("Experimental");
        if (own != null) s.tag(a, "Changed", false);
        return s.view;
    }

    private static void edit(MainActivity a, AdvancedTable.Row r, String titleId, Ui.Setting s) {
        String cur = AdvancedSettings.own(a, r.name, titleId);
        EditText t = new EditText(a);
        t.setSingleLine(true);
        double[] range = r.range();
        t.setInputType(r.type.equals("number") ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (cur != null) t.setText(cur);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        String hint = (r.type.equals("number") && range != null ? "A number from " + trim(range[0]) + " to " + trim(range[1]) + (r.unit().isEmpty() ? "" : " " + r.unit()) + ". " : "The value after " + r.name + "=. ")
                + "Empty or Auto writes nothing." + (r.experimental() ? "\n" + r.warn : "");
        Ui.add(box, Ui.small(a, hint), 4);
        Ui.add(box, t, 8);
        Runnable after = () -> {
            String own = AdvancedSettings.own(a, r.name, titleId);
            s.value(own != null ? r.optionLabel(own) : "Auto" + (r.deflt.isEmpty() ? "" : ": " + r.deflt));
            s.tag(a, own != null ? "Changed" : null, false);
        };
        Ui.sheet(a, r.developer() ? r.name : r.title(), box).setPositiveButton("Save", (x, w) -> {
            String v = t.getText().toString().trim();
            if (v.isEmpty()) AdvancedSettings.set(a, r.name, titleId, null);
            else if (r.accepts(v)) AdvancedSettings.set(a, r.name, titleId, v);
            else android.widget.Toast.makeText(a, "Not a value this row takes", android.widget.Toast.LENGTH_SHORT).show();
            after.run();
        }).setNeutralButton("Auto", (x, w) -> { AdvancedSettings.set(a, r.name, titleId, null); after.run(); }).setNegativeButton("Cancel", null).show();
    }

    private static String trim(double d) { return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d); }
}
