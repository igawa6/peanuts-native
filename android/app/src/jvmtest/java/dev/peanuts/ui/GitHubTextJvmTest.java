// PEANUTS: GitHubText and CompanionNotes on the JVM (peanuts/tests/test_github_text.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.List;

public final class GitHubTextJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static String outline(String md) { return GitHubText.outline(GitHubText.parse(md)); }

    /** A block's styles as "BOLD 0-6 LINK 8-12 https://…" */
    private static String spans(GitHubText.Block b) {
        StringBuilder s = new StringBuilder();
        for (GitHubText.Span x : b.spans) s.append(s.length() > 0 ? " " : "").append(x.style).append(' ').append(x.start).append('-').append(x.end).append(x.url != null ? " " + x.url : "");
        return s.toString();
    }

    public static void main(String[] a) {
        text();
        companion();
        System.out.println("ok " + n);
    }

    private static void text() {
        // a contributor's badge goes (the text says the name), no HTML left
        eq("Companion by AlterDigga.", outline("<a href=\"https://github.com/AlterDigga\"><img src=\"https://img.shields.io/badge/x\" style=\"vertical-align: middle;\" alt=\"AlterDigga\"></a> Companion by AlterDigga."));
        eq("🥇 Commissioned by MsMeriBerry. Thank you!",
                outline("🥇 <a href=\"https://github.com/MsMeriBerry\"><img src=\"https://img.shields.io/badge/y\" alt=\"MsMeriBerry\"></a> Commissioned by MsMeriBerry. Thank you!"));
        // a picture goes; a line of pictures only ends the paragraph
        eq(2, GitHubText.parse("Before.\n![the map](https://example.org/map.png)\nAfter.").size());
        eq("", outline("![shot](https://example.org/s.png)\n<img src=\"x\">\n\n"));

        // line breaks stay, as GitHub shows a release; an item's next line too
        eq("First line.\nSecond line.", outline("First line.\nSecond line."));
        List<GitHubText.Block> items = GitHubText.parse("- **Battle.** Live resources.\n  Auto follows native selection.\n- **Settings.** Re-show.");
        eq("• Battle. Live resources.\nAuto follows native selection.\n• Settings. Re-show.", GitHubText.outline(items));
        eq("BOLD 0-7", spans(items.get(0)));

        // a driver's notes: • items, a drawn line, labels, a bare address as a link
        List<GitHubText.Block> purple = GitHubText.parse("• Turnip Driver Unified MrPurple\n• Unified: A6XX, A7XX, A8XX\n━━━━━━ ×✕× ━━━━━━\n- Driver Version: 26.3.0-devel\n"
                + "━━━━━━ ×✕× ━━━━━━\n- Changelog:\n•Upstream to latest Mesa source\n\nNOTE:\n•Test and tell me feedback\n- https://ko-fi.com/mrpurple666\n");
        eq("• Turnip Driver Unified MrPurple\n• Unified: A6XX, A7XX, A8XX\n• Driver Version: 26.3.0-devel\n• Changelog:\n• Upstream to latest Mesa source\nNOTE:\n• Test and tell me feedback\n"
                + "• https://ko-fi.com/mrpurple666", GitHubText.outline(purple));
        eq("LINK 0-29 https://ko-fi.com/mrpurple666", spans(purple.get(purple.size() - 1)));

        // headings with bold, a rule, code with underscores, a link's words
        List<GitHubText.Block> kimchi = GitHubText.parse("### **UPDATE 2**: Added working a8xx Turnip.\n## v26.0.0 R8\nVulkan 1.4.335\nBase commit:  e9a83d530cce\n\n---\n\n"
                + "- For the ONE UI bug, use `FD_DEV_FEATURES = enable_tp_ubwc_flag_hint=1`\n- If you need the new autotuner, [use v25.3.0 here](https://github.com/K11MCH1/AdrenoToolsDrivers/releases).");
        eq("### UPDATE 2: Added working a8xx Turnip.\n## v26.0.0 R8\nVulkan 1.4.335\nBase commit: e9a83d530cce\n• For the ONE UI bug, use FD_DEV_FEATURES = enable_tp_ubwc_flag_hint=1\n"
                + "• If you need the new autotuner, use v25.3.0 here.", GitHubText.outline(kimchi));
        eq("BOLD 0-8", spans(kimchi.get(0)));
        eq("CODE 24-68", spans(kimchi.get(3)));
        eq("LINK 31-47 https://github.com/K11MCH1/AdrenoToolsDrivers/releases", spans(kimchi.get(4)));

        // GitHub's notes and warnings, a quote
        eq("[Warning] Please don't report issues to Mesa.\n[Note] Read it.", outline(">[!WARNING]\n> Please don't report issues to Mesa.\n\n> [!NOTE]  \n> Read it."));
        eq("> Quoted line one\nline two", outline("> Quoted line one\n> line two"));

        // a list in a warning, wrapped in samp (a font, not code) around code
        eq("[Note] • GMEM for a710 users.\n• AUTO for anyone else.", outline("> [!NOTE]\n> <samp> - `GMEM` for a710 users.  </samp>  \n> <samp> - `AUTO` for anyone else.</samp>"));

        // HTML: details, headings, lists, code
        List<GitHubText.Block> det = GitHubText.parse("<details><summary>Changelog</summary>\n\n- <code>tu: fix</code>\n</details>");
        eq("Changelog\n• tu: fix", GitHubText.outline(det));
        eq("BOLD 0-9", spans(det.get(0)));
        eq("CODE 0-7", spans(det.get(1)));
        eq("### New\n• One\n• Two", outline("<h3>New</h3><ul><li>One</li><li>Two</li></ul>"));
        eq("Site", outline("<a href=\"https://x.org\"><img src=\"b.png\"> Site</a>"));

        // numbered items, a table, fenced code
        eq("1. Install the APK.\n2. In Settings, choose your folder.\n|! Game | Update\n| Metroid Dread | 2.1.0",
                outline("1. Install the APK.\n2. In Settings, choose your folder.\n\n| Game | Update |\n|---|---|\n| Metroid Dread | 2.1.0 |"));
        eq("TU_DEBUG=sysmem\n  x=1", outline("```sh\nTU_DEBUG=sysmem\n  x=1\n```"));

        // inline styles, escapes, an address without https
        List<GitHubText.Block> in = GitHubText.parse("**bold** *it* _it2_ ~~gone~~ snake_case_name `a*b` \\*not\\* [x](http://insecure)");
        eq("bold it it2 gone snake_case_name a*b *not* x", GitHubText.outline(in));
        eq("BOLD 0-4 ITALIC 5-7 ITALIC 8-11 STRIKE 12-16 CODE 33-36", spans(in.get(0)));
        eq("1. not a list", outline("1\\. not a list"));

        // entities, Windows line ends, comments, addresses in brackets
        eq("a <b> & c d 🥇", outline("a &lt;b&gt; &amp; c&nbsp;d &#x1F947;\r\n<!-- hidden -->"));
        List<GitHubText.Block> addr = GitHubText.parse("By igawa6 (https://ko-fi.com/W7W1DAK7T). See <https://example.org/x>.");
        eq("By igawa6 (https://ko-fi.com/W7W1DAK7T). See https://example.org/x.", GitHubText.outline(addr));
        eq("LINK 11-38 https://ko-fi.com/W7W1DAK7T LINK 45-66 https://example.org/x", spans(addr.get(0)));

        // one line for a list row
        eq("New", GitHubText.plain("\n\n## New\nmore"));
        eq("Fixed: works", GitHubText.plain("- **Fixed:** works"));
        eq(140, GitHubText.plain("x".repeat(300)).length());
        eq("", GitHubText.plain((String) null));
    }

    private static void companion() {
        String mk = "Mario Kart 8 Deluxe (0100152000022000), game version 4.0.1, 4.0.0 or 3.0.3 (CTGP-DX v1.1.1 on 3.0.3).\n\n"
                + "- Adds game version 4.0.1. Until now a 4.0.1 install showed the wrong-version card. Thanks to AntonyKor for the port.\n"
                + "- Everything else is unchanged from 1.0.0.\n\n"
                + "Install: in Eden Duo, long-press the game, open **Add-ons**, choose **Install**, and select this file (do not rename it). Requires Eden Duo 1.0.1 or newer.\n\n"
                + "SHA-256: e98e16e2494ab5da4c081643527c98b84f857ce2c9cb8a82e91f1364d673c8a1\n";
        eq("4.0.1, 4.0.0 or 3.0.3 (CTGP-DX v1.1.1 on 3.0.3)", CompanionNotes.gameVersion(mk));
        eq("- Adds game version 4.0.1. Until now a 4.0.1 install showed the wrong-version card. Thanks to AntonyKor for the port.\n- Everything else is unchanged from 1.0.0.",
                CompanionNotes.body(mk));
        eq("Adds game version 4.0.1. Until now a 4.0.1 install showed the wrong-version card. Thanks to AntonyKor for the port.", CompanionNotes.summary(mk));

        // the game sentence followed by a credit; an install paragraph that says one more thing
        String sword = "Pokémon Sword (`0100ABF008968000`), game version 1.3.2, with Isle of Armor and Crown Tundra. By igawa6.\n\nFixes:\n\n- The battle page no longer drops back to the map.\n\n"
                + "Install over 1.0.1: in Eden Duo, long-press the game, open **Add-ons**, choose **Install**, and select this file (don't rename it). Requires Eden Duo 1.3.2.2 or newer "
                + "(runtime 22); Korean and Chinese need Eden Duo 1.3.3. This package is for Pokémon Sword only; Pokémon Shield has its own package.\n\nSHA-256: "
                + "7ebdc4be0c974788359e0fc4793dc0d1cfafe97e5529a5a0909da8be9d574f2b\n";
        eq("1.3.2, with Isle of Armor and Crown Tundra", CompanionNotes.gameVersion(sword));
        eq("By igawa6.\n\nFixes:\n\n- The battle page no longer drops back to the map.\n\nThis package is for Pokémon Sword only; Pokémon Shield has its own package.", CompanionNotes.body(sword));

        // a credit before the game sentence; steps over two lines; a checksum in code
        String octo = "By igawa6, commissioned by TwoBitSprite (https://ko-fi.com/W7W1DAK7T) — thank you! Octopath Traveler 0 (`01005270232F2000`), Switch update **1.0.5**.\n\n"
                + "First release.\n\nInstall: in Eden Duo, long-press the game, open **Add-ons**, choose **Install**, and select the\n.dsmod.zip file without renaming it. "
                + "Requires Eden Duo **1.3.2 or newer** (runtime 22).\n\nSHA-256: `8248a934d0fa32fd6161fc5cbff0de0aa7bddb637482d99dcb2d6264a809c311`";
        eq("1.0.5", CompanionNotes.gameVersion(octo));
        eq("By igawa6, commissioned by TwoBitSprite (https://ko-fi.com/W7W1DAK7T) — thank you!\n\nFirst release.", CompanionNotes.body(octo));

        // a full stop in the name, a base game, arrows in the steps, notes without a game sentence
        eq("1.2.1", CompanionNotes.gameVersion("Super Mario Bros. Wonder (010015100B514000), game version 1.2.1.\n\nText"));
        eq("Text", CompanionNotes.body("Super Mario Bros. Wonder (010015100B514000), game version 1.2.1.\n\nText"));
        eq("1.0.0", CompanionNotes.gameVersion("Persona 3 Portable (0100DCD01525A000), base game 1.0.0.\n\nThree fixes after 1.0.0."));
        eq("Brilliant Diamond has its own package.", CompanionNotes.body("Install over 1.0.1: in Eden Duo, long-press the game → **Add-ons** → **Install**, and pick this file "
                + "(don't rename it). Requires Eden Duo 1.2.1 or newer. Brilliant Diamond has its own package."));
        String changelog = "- Fixes a display regression shared with Eden Duo.\n- Improves frame pacing.";
        eq(changelog, CompanionNotes.body(changelog));
        eq("", CompanionNotes.gameVersion("Encounter tables and battle icons are now read from your game files."));
        eq("Encounter tables and battle icons are now read from your game files.", CompanionNotes.body("Encounter tables and battle icons are now read from your game files."));
        eq("", CompanionNotes.body(null));
        eq("", CompanionNotes.gameVersion(null));
    }
}
