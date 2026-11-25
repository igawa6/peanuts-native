// PEANUTS: the control interface's companion command (peanuts/docs/CONTROL.md): what the catalog screen does for one game, without the screen
package dev.peanuts.ctl;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.peanuts.Catalog;
import dev.peanuts.CompanionFile;
import dev.peanuts.CompanionImport;
import dev.peanuts.Installer;
import dev.peanuts.settings.CompanionSection;
import dev.peanuts.ui.CtlPort;

/**
 * companion, do=list | install | update | remove. The catalog is read from the network and a package is downloaded, checked and put in place by the
 * app's own installer (dev.peanuts.Installer: the catalog's digest, the runtime version, not while a game runs), so the command answers later: its result
 * is pending until the work has ended. remove deletes a package and asks first (Request.check). install with path puts in a .dsmod.zip on the device
 * instead of the catalog's (Install from file).
 */
final class CompanionCmd {
    private CompanionCmd() {}

    /** Works on its own thread and calls finished when res has its answer; what can be refused at once was refused by Request.check */
    static void start(Context c, Request r, Result res, Runnable finished) throws Request.Bad {
        String act = r.action();
        String path = r.str("path");   // install from a file on the device (Request.check: do=install only)
        String id = r.titleId(!act.equals("list") && path == null);
        boolean confirm = r.on("confirm");
        new Thread(() -> {
            try {
                if (path != null) { file(c, path, id, confirm, res); finished.run(); return; }
                List<CtlPort.Row> rows = CtlPort.rows(c);   // reads packs: off the main thread
                if (act.equals("list")) list(rows, id, res);
                else one(c, act, row(rows, id), res);
            } catch (Request.Bad e) {
                res.fail(e.code, e.getMessage());
            } catch (IOException | RuntimeException e) {
                res.fail("failed", e.getMessage() == null ? e.toString() : e.getMessage());
            }
            finished.run();
        }, "peanuts-ctl-companion").start();
    }

    /** do=install with path: Settings' Install from file without the picker (dev.peanuts.CompanionImport, the same installer). id (null: any game) must be
     *  the file's game; an older version than the installed one wants confirm. CompanionFile's refusals keep their codes; older is needs_confirm */
    private static void file(Context c, String path, String id, boolean confirm, Result res) throws IOException, Request.Bad {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) throw new Request.Bad("no_file", path + " is not a file the app can read.");
        try {
            CompanionImport.Done d = CompanionImport.install(c, f, f.getName(), id, confirm, null);
            res.ok(d.message()).put("title_id", d.info.titleId).put("name", d.info.name).put("package", d.result.packageName).put("version", d.result.version)
                    .put("was", d.result.was).put("changed", d.result.changed()).put("in_library", d.target.inLibrary).put("built", d.target.built);
        } catch (CompanionFile.Refused e) {
            if (CompanionFile.Refused.OLDER.equals(e.code)) throw new Request.Bad("needs_confirm", e.getMessage() + " Add -e confirm true to install it anyway.");
            throw new Request.Bad(e.code, e.getMessage());
        }
    }

    private static CtlPort.Row row(List<CtlPort.Row> rows, String id) throws Request.Bad {
        for (CtlPort.Row x : rows) if (x.titleId != null && x.titleId.equalsIgnoreCase(id)) return x;
        throw new Request.Bad("unknown_title", id + " is not in the library.");
    }

    private static Catalog.Item find(List<Catalog.Item> items, String id) {
        for (Catalog.Item i : items) if (i.titleId != null && i.titleId.equalsIgnoreCase(id)) return i;
        return null;
    }

    private static void list(List<CtlPort.Row> rows, String only, Result res) throws IOException, Request.Bad {
        if (only != null) row(rows, only);
        List<Catalog.Item> items = Catalog.fetch();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CtlPort.Row x : rows) {
            if (x.titleId == null || only != null && !x.titleId.equalsIgnoreCase(only)) continue;
            Catalog.Item it = find(items, x.titleId);
            String pkg = CompanionSection.packageFor(x.companionRoot, x.titleId);
            String have = pkg == null ? null : Installer.installedVersion(x.companionRoot, pkg);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title_id", x.titleId);
            m.put("name", x.name);
            m.put("package", pkg != null ? pkg : it == null ? null : it.packageName);
            m.put("installed", have);
            m.put("catalog", it == null ? null : it.version);
            m.put("state", Request.companionState(it == null ? null : it.version, have));
            m.put("built", x.companionRoot != null);   // a companion goes beside the game's pack: build the game first
            out.add(m);
        }
        res.ok(items.size() + " companions in the catalog, " + out.size() + " games listed.").put("companions", out);
    }

    private static void one(Context c, String act, CtlPort.Row x, Result res) throws IOException, Request.Bad {
        File root = x.companionRoot;
        if (root == null) throw new Request.Bad("not_built", x.name + " has no pack yet: a companion goes beside it. Build the game first.");
        String pkg = CompanionSection.packageFor(root, x.titleId);
        String have = pkg == null ? null : Installer.installedVersion(root, pkg);
        if (act.equals("remove")) {
            if (pkg == null) throw new Request.Bad("not_installed", x.name + " has no companion installed.");
            Installer.remove(root, pkg, c);   // refuses while a game runs
            res.ok("Removed " + pkg + (have == null ? "" : " " + have) + ". The game and its saves stay.").put("package", pkg).put("removed", have);
            return;
        }
        Catalog.Item it = find(Catalog.fetch(), x.titleId);
        if (it == null) throw new Request.Bad("no_companion", "The catalog has no companion for " + x.name + ".");
        if (act.equals("update") && pkg == null) throw new Request.Bad("not_installed", x.name + " has no companion installed: use do=install.");
        if (it.version.equals(have)) {
            res.ok(it.packageName + " " + have + " is installed and is the catalog's newest.").put("package", it.packageName).put("version", have).put("changed", false);
            return;
        }
        Installer.install(it, root, c.getCacheDir(), (what, done, total) -> { }, null, c);   // downloads, checks the digest and the runtime version; refuses while a game runs
        res.ok((have == null ? "Installed " : "Updated ") + it.packageName + (have == null ? " " : " " + have + " to ") + it.version + ".")
                .put("package", it.packageName).put("version", it.version).put("was", have).put("changed", true);
    }
}
