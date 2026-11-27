// PEANUTS: FolderList on the JVM (peanuts/tests/test_folder_list.py); prints "ok <n>" or throws
package dev.peanuts.folders;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class FolderListJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    public static void main(String[] a) {
        // what an older version kept: one game folder, an update folder and a DLC folder, each a key of its own; nothing added since
        eq("[/storage/emulated/0/Games]", FolderList.read(Collections.singletonList("/storage/emulated/0/Games"), Collections.emptyList()));
        eq("[/storage/1A2B-3C4D/Updates, /storage/1A2B-3C4D/DLC]", FolderList.read(Arrays.asList("/storage/1A2B-3C4D/Updates", "/storage/1A2B-3C4D/DLC"), null));
        // an update and a DLC folder that were one folder: one entry; nothing set: an empty list; a trailing slash and blanks make no second entry
        eq("[/storage/1A2B-3C4D/Switch]", FolderList.read(Arrays.asList("/storage/1A2B-3C4D/Switch", "/storage/1A2B-3C4D/Switch/"), null));
        eq("[]", FolderList.read(Arrays.asList(null, null), Collections.emptyList()));
        eq("[/a, /b]", FolderList.read(Arrays.asList("/a", ""), Arrays.asList(" /b ", "/a/", "")));
        // the first stays first, the added ones follow in their order
        eq("[/a, /c, /b]", FolderList.read(Collections.singletonList("/a"), Arrays.asList("/c", "/b")));

        // add: at the end; twice is said, not kept twice; a kind of one folder is full; a picker answer that is no path is not added
        List<String> out = new ArrayList<>();
        eq(FolderList.Added.ADDED, FolderList.add(Arrays.asList("/a"), "/b/", 0, out));
        eq("[/a, /b]", out);
        eq(FolderList.Added.ALREADY, FolderList.add(Arrays.asList("/a", "/b"), "/B", 0, out));
        eq("[/a, /b]", out);
        eq(FolderList.Added.FULL, FolderList.add(Arrays.asList("/a"), "/b", 1, out));
        eq("[/a]", out);
        eq(FolderList.Added.ADDED, FolderList.add(Collections.emptyList(), "/b", 1, out));
        eq(FolderList.Added.NOT_A_PATH, FolderList.add(Collections.emptyList(), "content://x", 0, out));
        eq(FolderList.Added.NOT_A_PATH, FolderList.add(Collections.emptyList(), null, 0, out));

        // remove: only that folder, the others in their order; first: a build folder made the default
        eq("[/a, /c]", FolderList.remove(Arrays.asList("/a", "/b", "/c"), "/b/"));
        eq("[/a, /b, /c]", FolderList.remove(Arrays.asList("/a", "/b", "/c"), "/d"));
        eq("[/c, /a, /b]", FolderList.first(Arrays.asList("/a", "/b", "/c"), "/c"));

        // which game folder holds a game file: the deepest that does; a folder whose name only starts the same does not
        List<String> games = Arrays.asList("/storage/emulated/0/Games", "/storage/1A2B-3C4D/Switch", "/storage/1A2B-3C4D/Switch/Big");
        eq("/storage/1A2B-3C4D/Switch", FolderList.holding(games, "/storage/1A2B-3C4D/Switch/Zelda/zelda.nsp"));
        eq("/storage/1A2B-3C4D/Switch/Big", FolderList.holding(games, "/storage/1A2B-3C4D/Switch/Big/x.xci"));
        eq("null", FolderList.holding(games, "/storage/1A2B-3C4D/Switch2/x.nsp"));
        eq("/storage/emulated/0/Games", FolderList.holding(games, "/storage/emulated/0/Games"));
        // where an import writes: that game folder, else the first one, else nowhere
        eq(new File("/storage/1A2B-3C4D/Switch"), FolderList.home(games, "/storage/1A2B-3C4D/Switch/zelda.nsp"));
        eq(new File("/storage/emulated/0/Games"), FolderList.home(games, "/storage/emulated/0/Download/zelda.nsp"));
        eq("null", FolderList.home(Collections.emptyList(), "/storage/emulated/0/Download/zelda.nsp"));

        eq(true, FolderList.same("/storage/1a2b-3c4d/Switch/", "/storage/1A2B-3C4D/Switch"));
        eq(false, FolderList.same("/a", null));
        eq("/", FolderList.clean("/"));
        System.out.println("ok " + n);
    }
}
