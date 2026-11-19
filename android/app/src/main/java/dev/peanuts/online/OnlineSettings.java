package dev.peanuts.online;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import dev.peanuts.ui.PUi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Settings › Online (after Settings › Library, from LibrarySettings.add): online play on Nextendo Network (dm/peanuts/net/README.md) and its account. Online play
 * on hands the game PN_ONLINE=1 and, when signed in, the account (Nextendo.envLines) from the game's next start.
 */
public final class OnlineSettings {
    private static PUi.Setting onlineRow, accountRow, nameRow, countryRow;

    private OnlineSettings() {}

    public static void add(Activity a, LinearLayout content) {
        PUi.add(content, PUi.section(a, "Online"), 16);
        LinearLayout card = PUi.add(content, PUi.listCard(a), 8);
        onlineRow = new PUi.Setting(a, "🌐", "Online play", v -> onlineRow.toggle.toggle())
                .toggle(a, Nextendo.online(a), (b, on) -> { Nextendo.online(a, on); refresh(a); });
        card.addView(onlineRow.view);
        accountRow = new PUi.Setting(a, "👤", "Nextendo account", v -> account(a)).chevron(a);
        accountRow.view.setOnLongClickListener(v -> { Nextendo.refreshIfDue(a); Toast.makeText(a, "Asking Nextendo for a new game token", Toast.LENGTH_SHORT).show(); return true; });
        card.addView(accountRow.view);
        nameRow = new PUi.Setting(a, "🏷", "Player name", v -> nameDialog(a)).chevron(a);
        card.addView(nameRow.view);
        countryRow = new PUi.Setting(a, "🏳", "Country", v -> countryDialog(a)).chevron(a);
        card.addView(countryRow.view);
        Nextendo.watch(() -> refresh(a));
        Nextendo.refreshIfDue(a);
        refresh(a);
    }

    private static void account(Activity a) {
        if (Nextendo.working()) { Toast.makeText(a, "Waiting for Nextendo…", Toast.LENGTH_SHORT).show(); return; }
        String user = Nextendo.username(a);
        if (user != null) {
            PUi.confirmDanger(a, "Sign out of Nextendo?", "Peanuts Native forgets the account on this device. Games stay online-capable; sign in again to play online with it.",
                    "Sign out", () -> { Nextendo.signOut(a); Toast.makeText(a, "Signed out of Nextendo", Toast.LENGTH_SHORT).show(); });
            return;
        }
        Toast.makeText(a, "Sign in on the Nextendo page, then come back to Peanuts Native", Toast.LENGTH_LONG).show();
        Nextendo.signIn(a, why -> Toast.makeText(a, why == null ? "Signed in to Nextendo as " + Nextendo.username(a) : "Not signed in: " + why, Toast.LENGTH_LONG).show());
    }

    /** The name games show for the player (DM_NICK), up to 10 characters as on a console; empty: the Nextendo username */
    private static void nameDialog(Activity a) {
        EditText e = new EditText(a);
        String now = Nextendo.playerNameChosen(a) ? Nextendo.playerName(a) : "";
        String user = Nextendo.username(a);
        e.setHint(user != null && !user.isEmpty() ? Nextendo.nickname(user) + " (your Nextendo name)" : "Player");
        e.setText(now);
        e.setSingleLine(true);
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(20)});
        e.setSelection(now.length());
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(e, PUi.fill());
        wrap.addView(PUi.small(a, "Up to 10 characters, as on a console. Every game shows it from its next start; leave it empty for your Nextendo name."), PUi.fill());
        AlertDialog d = PUi.sheet(a, "Player name", wrap)
                .setPositiveButton("Save", (x, w) -> Nextendo.playerName(a, e.getText().toString()))
                .setNegativeButton("Cancel", null)
                .setNeutralButton(Nextendo.playerNameChosen(a) ? "Use Nextendo name" : null, (x, w) -> Nextendo.playerName(a, null)).create();
        d.show();
        e.requestFocus();
    }

    /** The account's country (PN_NEXTENDO_COUNTRY): Mario Kart 8 Deluxe shows it as the player's flag online */
    private static void countryDialog(Activity a) {
        List<String> codes = new ArrayList<>(Arrays.asList(Locale.getISOCountries()));
        codes.sort((x, y) -> Nextendo.countryName(x).compareToIgnoreCase(Nextendo.countryName(y)));
        String phone = Locale.getDefault().getCountry();
        String[] names = new String[codes.size() + 1];
        names[0] = "The phone's (" + (phone.matches("[A-Z]{2}") ? Nextendo.countryName(phone) : "none") + ")";
        for (int i = 0; i < codes.size(); i++) names[i + 1] = Nextendo.countryName(codes.get(i));
        int sel = Nextendo.countryChosen(a) ? codes.indexOf(Nextendo.country(a)) + 1 : 0;
        new AlertDialog.Builder(a).setTitle("Country")
                .setSingleChoiceItems(names, sel, (dlg, w) -> { Nextendo.country(a, w == 0 ? null : codes.get(w - 1)); dlg.dismiss(); })
                .setNegativeButton("Cancel", null).show();
    }

    private static void refresh(Activity a) {
        if (onlineRow == null || accountRow == null) return;
        onlineRow.value(Nextendo.online(a) ? "On: games verified for it go online on Nextendo Network (Mario Kart 8 Deluxe 4.0.1 and Animal Crossing: New Horizons 3.0.3 so far); the others stay offline" : "Off: games stay offline");
        if (nameRow != null) { String n = Nextendo.playerName(a); nameRow.value((n == null ? "Player" : n) + (Nextendo.playerNameChosen(a) ? "" : n == null ? " (the default)" : " (your Nextendo name)")); }
        if (countryRow != null) { String cc = Nextendo.country(a); countryRow.value(cc == null ? "None (no flag)" : Nextendo.countryName(cc) + (Nextendo.countryChosen(a) ? "" : " (the phone's)")); }
        String user = Nextendo.username(a);
        if (Nextendo.working()) accountRow.value("Waiting for Nextendo…");
        else if (user == null) accountRow.value(Nextendo.configured() ? "Not signed in: tap to sign in in the browser" : "This build has no Nextendo application (see dm/peanuts/net/README.md)");
        else {
            long left = (Nextendo.tokenExpiry(a) - System.currentTimeMillis()) / 3600_000L;
            accountRow.value("Signed in as " + (user.isEmpty() ? "(no name)" : user) + (left > 0 ? " · game token valid " + left + " h" : " · game token out of date (long press: a new one)"));
        }
    }
}
