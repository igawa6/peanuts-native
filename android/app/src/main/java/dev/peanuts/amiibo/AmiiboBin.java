package dev.peanuts.amiibo;

import java.util.Locale;

/**
 * What an amiibo's dump (a .bin, the 540 bytes of an NTAG215 tag, or 532 or 572 with or without its last pages) tells without any keys: the tag's UID
 * (pages 0 and 1: three bytes, the check byte, four bytes) and the amiibo's ID, which the figure keeps unencrypted at 0x54. Everything else in it (the
 * owner, the nickname, a game's data) is encrypted and is not read: the player's game data lives with Peanuts' own copy of the amiibo.
 */
public final class AmiiboBin {
    private AmiiboBin() {}

    public static final int ID_AT = 0x54, MAX = 1024;

    /** The ID and UID of a dump, as hex; or null when the bytes are not an amiibo's */
    public static final class Read {
        public final String id, uid;
        Read(String id, String uid) { this.id = id; this.uid = uid; }
    }

    public static Read read(byte[] b) {
        if (b == null || (b.length != 532 && b.length != 540 && b.length != 572)) return null;
        if ((b[0] & 0xff) != 0x04) return null;                 // NXP, the maker of every amiibo's chip
        int last = b[ID_AT + 7] & 0xff;
        if (last != 0x02 && last != 0x03) return null;         // an amiibo ID ends in 02 (03 for a few newer ones)
        byte[] uid = {b[0], b[1], b[2], b[4], b[5], b[6], b[7]};
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 8; i++) id.append(String.format(Locale.ROOT, "%02x", b[ID_AT + i] & 0xff));
        return new Read(id.toString(), hex(uid));
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
}
