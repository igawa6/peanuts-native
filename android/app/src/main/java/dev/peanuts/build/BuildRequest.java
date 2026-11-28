package dev.peanuts.build;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What the library's game sheet asks to build (DESIGN-library.md "Build pipeline"): the game's base (an NSP/XCI to import, or a .nup / dump folder that passes
 * through), the update and DLC files found or picked for it, the library root they live under, and the companion package to install (null: none).
 */
public final class BuildRequest {
    public String titleId, name;
    public File base, update;
    public List<File> dlcs = new ArrayList<>();
    /** The library root (the game folder from setup); an imported .nup goes to &lt;gameDir&gt;/&lt;Name&gt;/&lt;Name&gt;.nup */
    public File gameDir;
    /** The game's explicit Build destination, or null to use the existing automatic placement. */
    public String buildDestination;
    /** The catalog package to install into the game's companion root, or null */
    public String companionPackage;
    /** Import again ({@link Reimport}): the .nup to make anew from base/update/dlcs; it is replaced only when the import is complete. null: an ordinary build */
    public File replaceNup;
    /** Import again's second choice, when the new file does not fit beside the old one: the old .nup is deleted before the import (the player was told the risk) */
    public boolean deleteOldFirst;

    public BuildRequest() {}

    public BuildRequest(String titleId, String name, File base, File update, List<File> dlcs, File gameDir, String companionPackage) {
        this.titleId = titleId; this.name = name; this.base = base; this.update = update;
        if (dlcs != null) this.dlcs = new ArrayList<>(dlcs);
        this.gameDir = gameDir; this.companionPackage = companionPackage;
    }

    /** The DLC formats accepted by the game sheet. */
    public static boolean isDlcFile(File f) {
        if (f == null || !f.isFile()) return false;
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".nsp") || n.endsWith(".nsz") || n.endsWith(".xci") || n.endsWith(".xcz");
    }

    /** Reject invalid DLC inputs before starting the importer. */
    String dlcInputError() {
        for (File f : dlcs) {
            if (f != null && !isDlcFile(f))
                return "Invalid DLC file: " + f.getName()
                        + ". Remove it from the added DLC list and choose an NSP, NSZ, XCI or XCZ file.";
        }
        return null;
    }

    /** base/update/dlcs as the importer takes them, base first */
    List<File> inputs() {
        List<File> l = new ArrayList<>();
        if (base != null) l.add(base);
        if (update != null) l.add(update);
        for (File f : dlcs) if (f != null) l.add(f);
        return l;
    }

    /** The name as a file and folder name: <Name>.nup inside <Name>/ */
    String fileName() {
        String n = name == null || name.trim().isEmpty() ? titleId : name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) n = titleId;
        return n.length() > 80 ? n.substring(0, 80).trim() : n;
    }

    /** Whether base needs the importer (an NSP/XCI); a .nup or a dump folder passes through */
    boolean needsImport() {
        if (base == null || base.isDirectory()) return false;
        String l = base.getName().toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".nsp") || l.endsWith(".xci") || l.endsWith(".nsz") || l.endsWith(".xcz");
    }
}
