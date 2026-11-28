package dev.peanuts.amiibo;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * An own amiibo described for the host's stand (dm/peanuts/peanuts_amiibo.c nxhost_peanuts_amiibo_place): "key=value" lines with its ID and UID, the
 * nickname a game reads (the character's name), the owner's Mii (the player's, a CharInfo), the dates, where the host writes, the game that runs and the
 * games' data kept for it. No Android class: the JVM tests check the lines.
 */
public final class AmiiboTag {
    private AmiiboTag() {}

    public static String describe(AmiiboStore.Own o, AmiiboDb.Amiibo a, byte[] mii, String titleId, long now) {
        StringBuilder b = new StringBuilder();
        b.append("key=").append(o.key).append('\n');
        b.append("id=").append(o.id).append('\n');
        b.append("uid=").append(o.uid).append('\n');
        b.append("name=").append(fit(a.nickname(), 40)).append('\n');
        if (mii != null && mii.length == 0x58) b.append("mii=").append(AmiiboBin.hex(mii)).append('\n');
        b.append("created=").append(day(o.added > 0 ? o.added : now)).append('\n');
        String[] c = AmiiboStore.common(o);
        b.append("writes=").append(c[0]).append('\n');
        if (c[1] != null) b.append("written=").append(c[1]).append('\n');
        b.append("today=").append(day(now)).append('\n');
        b.append("dir=").append(o.dir.getAbsolutePath()).append('\n');
        if (titleId != null) b.append("title=").append(titleId.toUpperCase(Locale.ROOT)).append('\n');
        for (AmiiboStore.Area ar : AmiiboStore.areas(o)) {
            if (ar.data.length == 0 || ar.data.length > 0xd8) continue;
            b.append("area=").append(ar.access).append(':').append(AmiiboBin.hex(ar.data)).append('\n');
        }
        return b.toString();
    }

    /** The text cut to at most max bytes of UTF-8, at a character's edge */
    static String fit(String s, int max) {
        String t = s == null ? "" : s.replace('\n', ' ');
        while (t.getBytes(StandardCharsets.UTF_8).length > max) t = t.substring(0, t.offsetByCodePoints(0, t.codePointCount(0, t.length()) - 1));
        return t;
    }

    static String day(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms)); }
}
