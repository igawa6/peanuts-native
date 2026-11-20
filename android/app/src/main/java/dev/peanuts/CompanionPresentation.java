package dev.peanuts;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;

/** The companion page (CompanionPage) on the second display, owned by the game's activity */
final class CompanionPresentation extends Presentation {
    private final CompanionPage page;

    CompanionPresentation(Context outer, Display display, String gameName) {
        super(outer, display);
        page = new CompanionPage(getContext(), getWindow(), gameName);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(page.create());
    }

    @Override protected void onStart() { super.onStart(); page.start(); }
    @Override protected void onStop() { page.stop(); super.onStop(); }
}
