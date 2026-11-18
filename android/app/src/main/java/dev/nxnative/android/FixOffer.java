package dev.nxnative.android;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fix finder on the exit notice and its "What it means" page (ExitWatch): FixFinder's pick for the run's end, shown as a line and a button "Try: &lt;row&gt;". A tap sets
 * the Advanced row for the game (AdvancedSettings.set), the line says it applies at the next start and the button becomes Undo (Auto again). A fix is offered once per row
 * and game: it is remembered as offered when the notice shows it.
 */
final class FixOffer {
    private FixOffer() {}

    private static List<FixFinder.Fix> cache;

    static synchronized List<FixFinder.Fix> fixes(Context c) {
        if (cache == null) {
            try (InputStream in = c.getAssets().open(FixFinder.ASSET)) { cache = FixFinder.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (IOException e) { cache = new ArrayList<>(); }
        }
        return cache;
    }

    private static FixFinder.State state(Context c) {
        return new FixFinder.State() {
            @Override public String own(String name, String titleId) { return AdvancedSettings.own(c, name, titleId); }
            @Override public boolean offered(String name, String titleId) { return Prefs.of(c).getBoolean(FixFinder.offeredKey(name, titleId), false); }
        };
    }

    /** The fix for this end (v: the log's digest, rec: the exit record), or null; a fix found is remembered as offered for the game */
    static FixFinder.Fix find(Context c, LogDigest.Verdict v, Map<String, String> rec) {
        if (v == null) return null;
        String id = rec.get("title");
        FixFinder.Fix f = FixFinder.pick(fixes(c), AdvancedSettings.table(c), FixFinder.key(v.rule, v.kind), id, state(c));
        if (f != null) Prefs.of(c).edit().putBoolean(FixFinder.offeredKey(f.name, id), true).apply();
        return f;
    }

    /** The line and the button under a dialog's message, for a fix find returned; nothing when there is none, or the row is no longer Auto (set on the other dialog) */
    static void add(Context c, AlertDialog.Builder b, FixFinder.Fix f, Map<String, String> rec) {
        String id = rec.get("title");
        AdvancedTable.Row r = f == null ? null : FixFinder.row(AdvancedSettings.table(c), f, id);
        if (r == null || !FixFinder.auto(state(c), f.name, id)) return;
        String target = FixFinder.target(f, id);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 20);
        box.setPadding(pad, Ui.dp(c, 4), pad, 0);
        TextView line = Ui.add(box, Ui.small(c, FixFinder.offerText(r, f)), 0);
        Button[] btn = new Button[1];
        boolean[] set = new boolean[1];
        btn[0] = Ui.add(box, Ui.button(c, FixFinder.button(r), Ui.Kind.TONAL, x -> {
            set[0] = !set[0];
            AdvancedSettings.set(c, f.name, target, set[0] ? f.value : null);
            line.setText(set[0] ? FixFinder.doneText(r, f, rec.get("name")) : FixFinder.offerText(r, f));
            btn[0].setText(set[0] ? "Undo" : FixFinder.button(r));
        }), 8);
        b.setView(box);
    }
}
