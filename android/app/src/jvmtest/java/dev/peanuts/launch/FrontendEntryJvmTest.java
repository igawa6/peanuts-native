// PEANUTS: a test of the fork's own classes (the Frontend folder's entries, GitHub issue #475); upstream has no dev.peanuts tests
package dev.peanuts.launch;

/** FrontendEntry on the JVM (no argument; prints "ok N" or throws): the file name of a game, the title ID back from a name and from a text, whose text counts. */
public final class FrontendEntryJvmTest {
    private static int checks;

    private static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want <" + want + ">, got <" + got + ">");
    }

    private static int utf8(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

    public static void main(String[] args) {
        final String za = "0100F43008C44000", app = "dev.nxnative.android.peanuts", test = "dev.nxnative.android.test.peanuts";
        // the file name: the game's name as dumps name it, then the title ID in brackets
        eq("Pokémon Legends Z-A [0100F43008C44000].nutshell", FrontendEntry.fileName("Pokémon Legends: Z-A", za), "a colon goes, and its space with it");
        eq("Super Mario Odyssey [0100000000010000].nutshell", FrontendEntry.fileName("Super Mario Odyssey", "0100000000010000"), "a plain name stays as it is");
        eq("Pokémon Legends Z-A [0100F43008C44000].nutshell", FrontendEntry.fileName("Pokémon Legends: Z-A", "0100f43008c44000"), "the title ID in upper case");
        eq("A B C D E F G H I [0100F43008C44000].nutshell", FrontendEntry.fileName("A\\B/C:D*E?F\"G<H>I|", za), "every character a FAT card or shared storage refuses");
        eq("Two lines [0100F43008C44000].nutshell", FrontendEntry.fileName("Two\nlines\t", za), "control characters are spaces");
        eq("Dots [0100F43008C44000].nutshell", FrontendEntry.fileName("Dots...", za), "no dot before the bracket");
        eq("Hidden [0100F43008C44000].nutshell", FrontendEntry.fileName("..Hidden", za), "no dot at the start: a hidden file is not listed");
        eq("0100F43008C44000 [0100F43008C44000].nutshell", FrontendEntry.fileName(":::", za), "a name with nothing left is the title ID's");
        eq("0100F43008C44000 [0100F43008C44000].nutshell", FrontendEntry.fileName(null, za), "no name at all");
        eq("Zelda™ Tears of the Kingdom [0100F2C0115B6000].nutshell", FrontendEntry.fileName("Zelda™: Tears of the Kingdom", "0100F2C0115B6000"), "a trademark sign stays");
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 120; i++) longName.append('é');   // 240 bytes of UTF-8
        String cut = FrontendEntry.fileName(longName.toString(), za);
        eq(true, utf8(cut) <= 255 - 7, "a long name fits a file name, the part file's dot and .part too: " + utf8(cut));
        eq(true, cut.startsWith("éé") && cut.contains(" [" + za + "]"), "a long name is cut, never its title ID");
        eq(FrontendEntry.NAME_BYTES, utf8(cut.substring(0, cut.indexOf(" ["))), "cut at a character's edge, at the limit");
        String emoji = FrontendEntry.fileName(new String(new char[101]).replace("\0", "🌰"), za);   // 404 bytes of chestnuts
        eq(false, emoji.contains("�") || Character.isHighSurrogate(emoji.charAt(emoji.indexOf(" [") - 1)), "never half a character");
        // the title ID back from the name
        eq(za, FrontendEntry.titleIdOfName("Pokémon Legends Z-A [0100F43008C44000].nutshell"), "the bracket's title ID");
        eq(za, FrontendEntry.titleIdOfName("Pokémon Legends Z-A [0100f43008c44000].NUTSHELL"), "any case");
        eq(za, FrontendEntry.titleIdOfName("Game [0100000000010000][0100F43008C44000].nutshell"), "the last bracket");
        eq(null, FrontendEntry.titleIdOfName("Pokémon Legends Z-A [0100F43008C44000].nup"), "another extension is not an entry");
        eq(null, FrontendEntry.titleIdOfName("Pokémon Legends Z-A.nutshell"), "a renamed entry has no bracket: its text tells");
        eq(null, FrontendEntry.titleIdOfName("Game [0100F43008C4400].nutshell"), "15 digits are no title ID");
        eq(null, FrontendEntry.titleIdOfName(null), "no name");
        eq(true, FrontendEntry.isEntry("x.Nutshell"), "the extension in any case");
        eq(false, FrontendEntry.isEntry(".x.nutshell.part"), "a part file is not an entry");
        // the text: the app's marker and the title ID
        String text = FrontendEntry.text(app, "0100f43008c44000");
        eq("Peanuts Native dev.nxnative.android.peanuts\n0100F43008C44000\n", text, "two lines");
        eq(za, FrontendEntry.titleIdOfText(text, app), "the app's own text");
        eq(null, FrontendEntry.titleIdOfText(text, test), "another Peanuts app's text is not this app's: a sync leaves it");
        eq(za, FrontendEntry.titleIdOfText(text, null), "any Peanuts app's text starts its game (the launch checks Home's list)");
        eq(za, FrontendEntry.titleIdOfText(text.replace("\n", "\r\n"), app), "Windows line ends");
        eq(za, FrontendEntry.titleIdOfText("Peanuts Native " + app + "\n" + za, app), "no last line end");
        eq(null, FrontendEntry.titleIdOfText("Peanuts Native " + app + "\n", app), "no title ID");
        eq(null, FrontendEntry.titleIdOfText("Peanuts Native " + app + "\nnot an id\n", app), "a title ID that is none");
        eq(null, FrontendEntry.titleIdOfText("Peanuts Native\n" + za + "\n", null), "the marker without an app");
        eq(null, FrontendEntry.titleIdOfText("My notes\n" + za + "\n", null), "a text of someone else's");
        eq(null, FrontendEntry.titleIdOfText("", null), "an empty file");
        eq(null, FrontendEntry.titleIdOfText(null, null), "no text");
        StringBuilder big = new StringBuilder(text);
        while (big.length() <= FrontendEntry.MAX_BYTES) big.append('x');
        eq(null, FrontendEntry.titleIdOfText(big.toString(), null), "a large file is not an entry");
        System.out.println("ok " + checks);
    }
}
