package dev.peanuts.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Upstream's Advanced page for Settings' search (SettingsSearch, SettingsPage): its rows that match a query, by its own search
 * (AdvancedTable.visible: every word in a row's name, label, "try this if" line or fix words; the player's rows, no developer ones), and the page opened
 * with that query typed into its search field (MainActivity.openAdvanced). By reflection: these are package-private in dev.nxnative.android. Nothing is
 * found when they change; peanuts/tests/test_settings_search.py checks the names against upstream's source.
 */
final class AdvancedPort {
    private AdvancedPort() {}

    private static final String P = "dev.nxnative.android.";

    /** One of Advanced's rows: its title and its "try this if" line */
    static final class Hit {
        final String title, tryIf;
        Hit(String title, String tryIf) { this.title = title; this.tryIf = tryIf; }
    }

    /** Advanced's rows for every game that match the query, as its own search finds them (at most its SEARCH_MAX); none when the query is blank */
    static List<Hit> find(Context c, String query) {
        if (SettingsSearch.words(query).length == 0) return Collections.emptyList();
        try {
            Method table = Class.forName(P + "AdvancedSettings").getDeclaredMethod("table", Context.class);
            table.setAccessible(true);
            Method visible = Class.forName(P + "AdvancedTable").getDeclaredMethod("visible", List.class, String.class, boolean.class, String.class);
            visible.setAccessible(true);
            Class<?> row = Class.forName(P + "AdvancedTable$Row");
            Method title = row.getDeclaredMethod("title");
            title.setAccessible(true);
            Field tryIf = row.getDeclaredField("tryIf");
            tryIf.setAccessible(true);
            List<?> hits = (List<?>) visible.invoke(null, table.invoke(null, c), null, false, query);
            List<Hit> out = new ArrayList<>();
            for (Object r : hits) out.add(new Hit(String.valueOf(title.invoke(r)), String.valueOf(tryIf.get(r))));
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            android.util.Log.w("peanuts", "settings search: Advanced's rows are not reachable: " + e);
            return Collections.emptyList();
        }
    }

    /** Settings' Advanced page, with the query in its search field; false when it could not be opened */
    static boolean open(Activity a, String query) {
        try {
            Method m = a.getClass().getDeclaredMethod("openAdvanced", String.class, String.class);
            m.setAccessible(true);
            m.invoke(a, null, null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            android.util.Log.w("peanuts", "settings search: Advanced did not open: " + e);
            return false;
        }
        EditText q = firstEdit(a.findViewById(android.R.id.content));
        if (q != null && query != null) { q.setText(query.trim()); q.setSelection(q.getText().length()); }
        return true;
    }

    private static EditText firstEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            EditText e = firstEdit(((ViewGroup) v).getChildAt(i));
            if (e != null) return e;
        }
        return null;
    }
}
