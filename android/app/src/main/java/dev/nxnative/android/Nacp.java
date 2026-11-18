package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A game's control data in a dump folder (a version's control/ folder: control.nacp and icon_&lt;Language&gt;.dat, as OunceCake serves the Control content;
 * dm/tools/nacp.py reads the same): its title, publisher and display version, and its icon (a JPEG per language). No Android classes: NacpJvmTest runs it.
 */
final class Nacp {
    private Nacp() {}

    /** The NACP's languages in its own order (its title entries and SupportedLanguageFlag bits): the language tag and the icon file's name for each */
    static final String[][] LANGUAGES = {
        {"en-US", "AmericanEnglish"}, {"en-GB", "BritishEnglish"}, {"ja", "Japanese"}, {"fr", "French"}, {"de", "German"}, {"es-419", "LatinAmericanSpanish"},
        {"es", "Spanish"}, {"it", "Italian"}, {"nl", "Dutch"}, {"fr-CA", "CanadianFrench"}, {"pt", "Portuguese"}, {"ru", "Russian"}, {"ko", "Korean"},
        {"zh-Hant", "TraditionalChinese"}, {"zh-Hans", "SimplifiedChinese"}, {"pt-BR", "BrazilianPortuguese"},
    };

    static final class Control {
        final String title, publisher, version;   // null when the NACP has none
        final byte[] icon;                         // a JPEG, null when the folder has none
        Control(String title, String publisher, String version, byte[] icon) { this.title = title; this.publisher = publisher; this.version = version; this.icon = icon; }
    }

    /** The NACP language index for a language tag: the exact tag, else its language (fr-CA → fr, pt-PT → pt), else American English */
    static int language(String tag) {
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i][0].equalsIgnoreCase(tag)) return i;
        String lang = tag.split("-", 2)[0];
        if (lang.equals("zh")) return tag.contains("Hant") || tag.endsWith("TW") || tag.endsWith("HK") ? 13 : 14;
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i][0].equalsIgnoreCase(lang)) return i;
        return 0;
    }

    /** The title area: 0x300 bytes per language (name 0x200, publisher 0x100). Newer SDKs compress it (a u16 size, then raw deflate to 32 languages × 0x300) */
    static byte[] titles(byte[] nacp) {
        if (nacp.length >= 2) {
            int size = (nacp[0] & 0xff) | (nacp[1] & 0xff) << 8;
            if (size > 0 && 2 + size <= nacp.length) {
                Inflater inf = new Inflater(true);
                try {
                    inf.setInput(nacp, 2, size);
                    byte[] out = new byte[0x6000 + 1];   // no further than 32 languages: a crafted size could inflate much more
                    int n = 0, k;
                    while (n < out.length && (k = inf.inflate(out, n, out.length - n)) > 0) n += k;
                    if (inf.finished() && n == 0x6000) return java.util.Arrays.copyOf(out, n);
                } catch (DataFormatException e) {
                    // not compressed
                } finally { inf.end(); }
            }
        }
        return java.util.Arrays.copyOf(nacp, Math.min(nacp.length, 0x3000));
    }

    private static String text(byte[] b, int at, int max) {
        int end = at;
        while (end < at + max && end < b.length && b[end] != 0) end++;
        if (end == at) return null;
        String s = new String(b, at, end - at, StandardCharsets.UTF_8).trim();
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i)) || s.charAt(i) == '�') return null;
        return s.isEmpty() ? null : s;
    }

    /** The entry of language index lang when it has a name, else the first that has one (the console's own fallback order); -1 when none has */
    private static int entry(byte[] t, int lang) {
        if (lang * 0x300 + 0x300 <= t.length && text(t, lang * 0x300, 0x200) != null) return lang;
        for (int i = 0; i * 0x300 + 0x300 <= t.length; i++) if (text(t, i * 0x300, 0x200) != null) return i;
        return -1;
    }

    /** A control folder's details in a language (a tag: the phone's, or the game's language setting); null when it has no control.nacp or it cannot be read */
    static Control read(File control, String langTag) {
        File f = new File(control, "control.nacp");
        if (!f.isFile()) return null;
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            int lang = language(langTag);
            byte[] t = titles(b);
            int e = entry(t, lang);
            String title = e < 0 ? null : text(t, e * 0x300, 0x200), publisher = e < 0 ? null : text(t, e * 0x300 + 0x200, 0x100);
            String version = b.length >= 0x3070 ? text(b, 0x3060, 0x10) : null;
            return new Control(title, publisher, version, icon(control, lang));
        } catch (IOException e) {
            return null;
        }
    }

    /** The icon for language index lang: icon_&lt;Language&gt;.dat, else American English's, else the first icon_*.dat there */
    static byte[] icon(File control, int lang) throws IOException {
        for (String n : new String[] {LANGUAGES[lang][1], LANGUAGES[0][1]}) {
            File f = new File(control, "icon_" + n + ".dat");
            if (f.isFile()) return Files.readAllBytes(f.toPath());
        }
        File[] l = control.listFiles((d, n) -> n.startsWith("icon_") && n.endsWith(".dat"));
        if (l == null || l.length == 0) return null;
        java.util.Arrays.sort(l);
        return Files.readAllBytes(l[0].toPath());
    }

    /** The phone's language as a tag (Locale.toLanguageTag: en-US, zh-Hant-TW) */
    static String phoneLanguage() { return Locale.getDefault().toLanguageTag(); }
}
