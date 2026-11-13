package dev.peanuts.online;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.Toast;

import dev.peanuts.ui.PUi;

/**
 * Settings › Online (after Settings › Library, from LibrarySettings.add): online play on Nextendo Network (dm/peanuts/net/README.md) and its account. Online play
 * on hands the game PN_ONLINE=1 and, when signed in, the account (Nextendo.envLines) from the game's next start.
 */
public final class OnlineSettings {
    private static PUi.Setting onlineRow, accountRow;

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

    private static void refresh(Activity a) {
        if (onlineRow == null || accountRow == null) return;
        onlineRow.value(Nextendo.online(a) ? "On: games go online on Nextendo Network, the community's servers" : "Off: games stay offline");
        String user = Nextendo.username(a);
        if (Nextendo.working()) accountRow.value("Waiting for Nextendo…");
        else if (user == null) accountRow.value(Nextendo.configured() ? "Not signed in: tap to sign in in the browser" : "This build has no Nextendo application (see dm/peanuts/net/README.md)");
        else {
            long left = (Nextendo.tokenExpiry(a) - System.currentTimeMillis()) / 3600_000L;
            accountRow.value("Signed in as " + (user.isEmpty() ? "(no name)" : user) + (left > 0 ? " · game token valid " + left + " h" : " · game token out of date (long press: a new one)"));
        }
    }
}
