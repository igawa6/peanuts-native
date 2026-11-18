package dev.nxnative.android;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/**
 * Settings › Users: the console's users (Users) as a list; tap one to rename it, make it the user games start with, or remove it; Add user makes a new one.
 * A game that keeps a save per user (Super Mario Bros. Wonder's save files) offers them in its user selector (UserPicker), each with a save of its own.
 */
final class UsersUi {
    private UsersUi() {}

    /** The row's value: how many users and who games start with */
    static String summary(Activity a) {
        List<Users.User> l = Users.list(a); String def = Users.defaultUid(a);
        String name = l.get(0).name; for (Users.User u : l) if (u.uid.equals(def)) name = u.name;
        return l.size() == 1 ? name : l.size() + " users · games start with " + name;
    }

    static void dialog(Activity a, Runnable changed) {
        List<Users.User> l = Users.list(a); String def = Users.defaultUid(a);
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) items[i] = l.get(i).name + (l.get(i).uid.equals(def) ? "  ·  games start with this user" : "");
        PadFocus.Builder b = new PadFocus.Builder(a);
        b.setTitle("Users").setItems(items, (d, w) -> user(a, l.get(w), changed)).setNegativeButton("Close", null);
        if (l.size() < Users.MAX) b.setPositiveButton("Add user", (d, w) -> name(a, "Add user", "", n -> { Users.add(a, n); changed.run(); dialog(a, changed); }));
        b.show();
    }

    private static void user(Activity a, Users.User u, Runnable changed) {
        boolean first = u.uid.equals(Users.FIRST), isDef = u.uid.equals(Users.defaultUid(a));
        String[] items = first ? new String[] {"Rename", "Games start with this user"} : new String[] {"Rename", "Games start with this user", "Remove"};
        new PadFocus.Builder(a).setTitle(u.name).setItems(items, (d, w) -> {
            if (w == 0) name(a, "Rename", u.name, n -> { Users.rename(a, u.uid, n); changed.run(); dialog(a, changed); });
            else if (w == 1) { if (!isDef) Users.setDefault(a, u.uid); changed.run(); dialog(a, changed); }
            else Ui.confirmDanger(a, "Remove " + u.name + "?", "Games no longer offer this user. Its saves stay on the phone (each game's save folder with _user_" + u.uid.substring(0, 8)
                    + "… at the end), and come back if the user is added again with the same ID, which a new user never has.", "Remove", () -> { Users.remove(a, u.uid); changed.run(); dialog(a, changed); });
        }).setNegativeButton("Back", (d, w) -> dialog(a, changed)).show();
    }

    private static void name(Activity a, String title, String initial, java.util.function.Consumer<String> done) {
        EditText f = new EditText(a);
        f.setSingleLine(true); f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.setText(initial); f.setSelection(f.getText().length()); f.setHint("Name");
        FrameLayout box = new FrameLayout(a);
        int p = Ui.dp(a, 20); box.setPadding(p, Ui.dp(a, 8), p, 0); box.addView(f);
        new PadFocus.Builder(a).setTitle(title).setView(box).setPositiveButton("OK", (d, w) -> {
            String n = Users.clean(f.getText().toString());
            if (n.isEmpty()) { Toast.makeText(a, "A user needs a name", Toast.LENGTH_SHORT).show(); return; }
            done.accept(n);
        }).setNegativeButton("Cancel", null).show();
    }
}
