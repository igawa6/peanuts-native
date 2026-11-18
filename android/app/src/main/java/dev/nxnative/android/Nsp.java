package dev.nxnative.android;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A plaintext NSP (NSP Box: a PFS0 of unsigned NCAs; dm/tools/nsp.py, dm/host/nsp_toc.h) read where it is. {@link #build} makes the TOC of a NUP file (SPEC section 3)
 * that describes it, every RomFS file a raw extent of the NSP, and Nup goes on with it as with the TOC of a .nup: a package is update/&lt;version&gt; for a game of
 * that version (a base-less update: an NSP is a whole snapshot of one version), base for version 0, addon/&lt;title ID&gt; for a DLC. Only the RomFS of a package is
 * in the tree (the ExeFS, with main and main.npdm, is too: the launcher reads those). No keys: a header that is not plain NCA3, an encrypted section and a content
 * record of a patch are refused. No Android classes: the JVM test runs it on the Mac.
 */
final class Nsp {
    private Nsp() {}

    /** What {@link #build} makes: the TOC bytes (Nup's layout) and the package records */
    static final class Built {
        byte[] toc;
        List<Nup.Pkg> packages = new ArrayList<>();
        long dirs, files, poolOff, poolSize;
    }

    private static final class Ent { String name; long off, size; }
    private static final class Sec { long off, size; boolean romfs; }

    private static final class Kid {
        final boolean file; final int id; final byte[] name;
        Kid(boolean file, int id, byte[] name) { this.file = file; this.id = id; this.name = name; }
    }

    /** The TOC under construction */
    private static final class B {
        final List<long[]> dirs = new ArrayList<>();    // name offset, name length, first child slot, child count
        final List<long[]> files = new ArrayList<>();   // name offset, name length, size, offset in the NSP
        final List<Long> kids = new ArrayList<>();      // node ids; a file's has FILE set until the directory count is known
        final java.io.ByteArrayOutputStream pool = new java.io.ByteArrayOutputStream();

        long name(byte[] n) { long at = pool.size(); pool.write(n, 0, n.length); return at; }
    }

    private static final long FILE = 1L << 40;

    private static void need(boolean ok, String what) throws IOException { if (!ok) throw new IOException(what); }

    /**
     * An NSP this app does not read, with what to tell the player: plain is a sentence that says what is wrong and what to do (shown as the dialog's text), the
     * message is the technical detail (shown under Details, and in the log)
     */
    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        final String plain;
        Refused(String plain, String detail) { super(detail); this.plain = plain; }
        Refused(String plain, String detail, Throwable cause) { super(detail, cause); this.plain = plain; }
    }

    static final String ENCRYPTED = "This NSP is encrypted. The app reads only the plain NSPs that NSP Box writes (it has no keys and needs none). "
            + "Make the NSP with NSP Box, or use a .nup or a dump folder.";
    static final String NOT_NSP = "This file is not an NSP (it has no NSP header), although its name ends in .nsp. It may be another kind of file, or a download that did not finish.";
    static final String CUT_OFF = "This NSP is incomplete or damaged (the file ends too early or its tables are wrong). Copy or download it again, and check the file's size.";
    static final String DLC_ONLY = "This NSP holds only DLC, no game. Add the game's own NSP (or .nup); the DLC is found beside it when they are in the same folder.";
    static final String NOT_PACKAGE = "This is not an NSP Box package: it has no content metadata. Only the plain NSPs that NSP Box writes are read.";
    static final String PATCH = "This NSP is an update patch, not a complete game. NSP Box's game NSPs are complete (the update is already in them): use one of those.";

    private static byte[] rd(Nup.Source r, long off, long n, String what) throws IOException {
        need(off >= 0 && n >= 0 && off <= r.size && n <= r.size - off && n < Integer.MAX_VALUE - 16, what + ": a read outside the file");
        byte[] b = new byte[(int) n];
        r.read(off, b, 0, b.length);
        return b;
    }

    private static long u32(byte[] b, int o) { return Nup.u32(b, o); }
    private static long u64(byte[] b, int o) { return Nup.u64(b, o); }

    /** The entries of the PFS0 at base (size bytes) */
    private static List<Ent> pfs(Nup.Source r, long base, long size, String what) throws IOException {
        need(size >= 0x10, what + ": not a plaintext PFS0");
        byte[] h = rd(r, base, 0x10, what);
        need(h[0] == 'P' && h[1] == 'F' && h[2] == 'S' && h[3] == '0', what + ": not a plaintext PFS0");
        long n = u32(h, 4), ss = u32(h, 8);
        need(n <= (1 << 20) && 0x10 + 0x18 * n + ss <= size, what + ": the PFS0 header is cut off");
        byte[] t = rd(r, base + 0x10, 0x18 * n + ss, what);
        long data = base + 0x10 + 0x18 * n + ss;
        List<Ent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long off = u64(t, 0x18 * i), sz = u64(t, 0x18 * i + 8), no = u32(t, 0x18 * i + 16);
            need(no < ss && off >= 0 && sz >= 0 && off <= size - (data - base) && sz <= size - (data - base) - off, what + ": entry " + i + " is outside the PFS0");
            int s = (int) (0x18 * n + no), e = s;
            while (e < t.length && t[e] != 0) e++;
            String name = new String(t, s, e - s, StandardCharsets.UTF_8);
            need(!name.isEmpty() && name.indexOf('/') < 0, what + ": entry " + i + " has a bad name");
            Ent en = new Ent();
            en.name = name; en.off = data + off; en.size = sz;
            out.add(en);
        }
        return out;
    }

    /** Content type, program ID and the PFS0 / RomFS sections of an NCA; refuses what is not plaintext */
    private static List<Sec> nca(Nup.Source r, Ent n, long[] idType) throws IOException {
        need(n.size >= 0xC00, n.name + ": shorter than an NCA header");
        byte[] h = rd(r, n.off, 0xC00, n.name);
        if (!(h[0x200] == 'N' && h[0x201] == 'C' && h[0x202] == 'A' && h[0x203] == '3'))
            throw new Refused(ENCRYPTED, n.name + ": no plain NCA3 header (an encrypted, retail NCA: only plaintext NSPs are read, with no keys)");
        idType[0] = u64(h, 0x210);
        idType[1] = h[0x205] & 0xff;
        List<Sec> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long st = u32(h, 0x240 + 16 * i), en = u32(h, 0x244 + 16 * i);
            if (st == 0 && en == 0) continue;
            need(st >= 6 && en > st && en * 512 <= n.size, n.name + ": section " + i + " is outside the NCA");
            int fh = 0x400 + 0x200 * i, fs = h[fh + 2] & 0xff, ht = h[fh + 3] & 0xff, enc = h[fh + 4] & 0xff;
            long span = (en - st) * 512, base = n.off + st * 512;
            if (enc != 1) throw new Refused(ENCRYPTED, n.name + ": section " + i + " is encrypted (type " + enc + ")");
            Sec s = new Sec();
            if (ht == 2 && fs == 1) {   // PFS0, hierarchical SHA-256
                long o = u64(h, fh + 0x40), z = u64(h, fh + 0x48);
                need(o >= 0 && z >= 0 && o <= span && z <= span - o, n.name + ": section " + i + ": the PFS0 is outside it");
                s.off = base + o; s.size = z;
            } else if (ht == 3 && fs == 0) {   // RomFS, IVFC: the data level is the last
                need(h[fh + 8] == 'I' && h[fh + 9] == 'V' && h[fh + 10] == 'F' && h[fh + 11] == 'C', n.name + ": section " + i + ": no IVFC header");
                long lv = u32(h, fh + 0x14);
                need(lv >= 3 && lv <= 7, n.name + ": section " + i + ": " + lv + " IVFC levels");
                int at = fh + 0x18 + 24 * (int) (lv - 2);
                long o = u64(h, at), z = u64(h, at + 8);
                need(o >= 0 && z >= 0 && o <= span && z <= span - o, n.name + ": section " + i + ": the RomFS is outside it");
                s.off = base + o; s.size = z; s.romfs = true;
            } else throw new IOException(n.name + ": section " + i + ": filesystem " + fs + " with hash " + ht + " is not read");
            out.add(s);
        }
        return out;
    }

    private static final Comparator<Kid> BY_NAME = (a, b) -> {
        int n = Math.min(a.name.length, b.name.length);
        for (int i = 0; i < n; i++) {
            int c = (a.name[i] & 0xff) - (b.name[i] & 0xff);
            if (c != 0) return c;
        }
        return a.name.length - b.name.length;
    };

    /** The state of one RomFS walk */
    private static final class Walk {
        byte[] dt, ft;
        long size, data, base, dleft, fleft;
        B b;
    }

    private static int walk(Walk w, int d, byte[] nm, int depth) throws IOException {
        need(depth <= 255, "RomFS nested too deep");
        B b = w.b;
        int me = b.dirs.size();
        b.dirs.add(new long[] {b.name(nm), nm.length, 0, 0});
        List<Kid> kids = new ArrayList<>();
        for (long c = u32(w.dt, d + 8); c != 0xFFFFFFFFL; ) {
            need(w.dleft-- > 0 && c + 0x18 <= w.dt.length, "bad or cyclic RomFS directory");
            long nl = u32(w.dt, (int) c + 20), sib = u32(w.dt, (int) c + 4);
            need(nl <= w.dt.length - c - 0x18, "RomFS directory name out of bounds");
            byte[] name = Arrays.copyOfRange(w.dt, (int) c + 0x18, (int) (c + 0x18 + nl));
            kids.add(new Kid(false, walk(w, (int) c, name, depth + 1), name));
            c = sib;
        }
        for (long c = u32(w.dt, d + 12); c != 0xFFFFFFFFL; ) {
            need(w.fleft-- > 0 && c + 0x20 <= w.ft.length, "bad or cyclic RomFS file");
            int ci = (int) c;
            long fo = u64(w.ft, ci + 8), sz = u64(w.ft, ci + 16), sib = u32(w.ft, ci + 4), nl = u32(w.ft, ci + 28);
            need(nl <= w.ft.length - c - 0x20 && fo >= 0 && sz >= 0 && fo <= w.size - w.data && sz <= w.size - w.data - fo, "RomFS file out of bounds");
            byte[] name = Arrays.copyOfRange(w.ft, ci + 0x20, (int) (c + 0x20 + nl));
            b.files.add(new long[] {b.name(name), nl, sz, sz == 0 ? 0 : w.base + w.data + fo});
            kids.add(new Kid(true, b.files.size() - 1, name));
            c = sib;
        }
        for (Kid k : kids) {
            String s = new String(k.name, StandardCharsets.UTF_8);
            need(k.name.length > 0 && k.name.length <= 0x300 && s.indexOf('/') < 0 && s.indexOf('\0') < 0 && !s.equals(".") && !s.equals(".."), "a RomFS name that is not a plain file name");
        }
        kids.sort(BY_NAME);
        for (int i = 1; i < kids.size(); i++) need(BY_NAME.compare(kids.get(i - 1), kids.get(i)) != 0, "a RomFS name twice in a directory");
        long[] rec = b.dirs.get(me);
        rec[2] = b.kids.size(); rec[3] = kids.size();
        for (Kid k : kids) b.kids.add(k.file ? FILE | k.id : (long) k.id);
        return me;
    }

    private static int romfs(Nup.Source r, B b, Sec s) throws IOException {
        need(s.size >= 0x50, "bad RomFS header");
        byte[] h = rd(r, s.off, 0x50, "the RomFS");
        need(u64(h, 0) == 0x50, "bad RomFS header");
        long dt = u64(h, 0x18), ds = u64(h, 0x20), ft = u64(h, 0x38), fs = u64(h, 0x40), data = u64(h, 0x48);
        need(dt >= 0 && ds >= 0x18 && dt <= s.size && ds <= s.size - dt && ft >= 0 && fs >= 0 && ft <= s.size && fs <= s.size - ft && data >= 0 && data <= s.size, "RomFS tables out of bounds");
        Walk w = new Walk();
        w.dt = rd(r, s.off + dt, ds, "the RomFS");
        w.ft = rd(r, s.off + ft, fs, "the RomFS");
        w.size = s.size; w.data = data; w.base = s.off; w.dleft = ds / 0x18 + 1; w.fleft = fs / 0x20 + 1; w.b = b;
        return walk(w, 0, new byte[0], 0);
    }

    /** The ExeFS: a PFS0 section as a directory named exefs, its files sorted by name */
    private static int exefs(Nup.Source r, B b, Sec s, String what) throws IOException {
        List<Ent> es = pfs(r, s.off, s.size, what);
        List<Kid> kids = new ArrayList<>();
        int me = b.dirs.size();
        byte[] en = "exefs".getBytes(StandardCharsets.UTF_8);
        b.dirs.add(new long[] {b.name(en), en.length, 0, 0});
        for (Ent e : es) {
            byte[] nm = e.name.getBytes(StandardCharsets.UTF_8);
            need(nm.length <= 0x300 && !e.name.equals(".") && !e.name.equals(".."), what + ": a bad ExeFS file name");
            b.files.add(new long[] {b.name(nm), nm.length, e.size, e.size == 0 ? 0 : e.off});
            kids.add(new Kid(true, b.files.size() - 1, nm));
        }
        kids.sort(BY_NAME);
        for (int i = 1; i < kids.size(); i++) need(BY_NAME.compare(kids.get(i - 1), kids.get(i)) != 0, what + ": an ExeFS file twice");
        long[] rec = b.dirs.get(me);
        rec[2] = b.kids.size(); rec[3] = kids.size();
        for (Kid k : kids) b.kids.add(FILE | k.id);
        return me;
    }

    /** The TOC for the NSP in r (name for messages); a Refused for anything it does not read, whatever way the file is wrong */
    static Built build(Nup.Source r, String name) throws IOException {
        try {
            return build0(r, name);
        } catch (Refused e) {
            throw e;
        } catch (java.io.EOFException | RuntimeException e) {
            throw new Refused(CUT_OFF, name + ": " + e, e);
        } catch (IOException e) {   // the checks above (need): the tables do not fit the file
            throw new Refused(CUT_OFF, e.getMessage(), e);
        }
    }

    private static Built build0(Nup.Source r, String name) throws IOException {
        List<Ent> ents = pfs(r, 0, r.size, name);
        B b = new B();
        Built out = new Built();
        List<long[]> rec = new ArrayList<>();   // title ID, version, root dir, romfs dir (-1), kind, flags, exefs dir (-1)
        List<Ent> metas = new ArrayList<>();
        for (Ent e : ents) if (e.name.endsWith(".cnmt.nca")) metas.add(e);
        if (metas.isEmpty()) throw new Refused(NOT_PACKAGE, name + ": no metadata NCA (*.cnmt.nca): not an NSP Box package");
        metas.sort((a, c) -> a.name.compareTo(c.name));
        for (Ent m : metas) {
            long[] it = new long[2];
            List<Sec> ms = nca(r, m, it);
            need(it[1] == 1 && !ms.isEmpty() && !ms.get(0).romfs, m.name + ": not a metadata NCA");
            List<Ent> cn = pfs(r, ms.get(0).off, ms.get(0).size, m.name);
            need(cn.size() == 1 && cn.get(0).size >= 0x30 && cn.get(0).size <= (1 << 20), m.name + ": expected one .cnmt");
            byte[] c = rd(r, cn.get(0).off, cn.get(0).size, m.name);
            long id = u64(c, 0), ver = u32(c, 8);
            int typ = c[12] & 0xff, exs = Nup.u16(c, 14), cnt = Nup.u16(c, 16);
            if (typ != 0x80 && typ != 0x82)
                throw new Refused(typ == 0x81 || typ == 0x83 ? PATCH : NOT_PACKAGE, m.name + ": a content meta of type 0x" + Integer.toHexString(typ) + "; only a game snapshot or a DLC is read");
            need(0x20L + exs + 0x38L * cnt + 0x20 <= c.length, m.name + ": the .cnmt is cut off");
            int romfs = -1, exefs = -1;
            for (int j = 0; j < cnt; j++) {
                int at = 0x20 + exs + 0x38 * j;
                if ((c[at + 0x36] & 0xff) != (typ == 0x80 ? 1 : 2)) continue;   // a game's Program NCA, a DLC's Data NCA
                StringBuilder nm = new StringBuilder();
                for (int q = 0; q < 16; q++) nm.append(String.format("%02x", c[at + 0x20 + q] & 0xff));
                nm.append(".nca");
                Ent n = null;
                for (Ent e : ents) if (e.name.equals(nm.toString())) n = e;
                need(n != null, "content " + nm + " is not in the NSP");
                long[] t2 = new long[2];
                List<Sec> ss = nca(r, n, t2);
                need(t2[0] == id, nm + ": program ID " + Long.toHexString(t2[0]) + ", the record's is " + Long.toHexString(id));
                for (Sec s : ss) {
                    if (s.romfs && romfs < 0) romfs = romfs(r, b, s);
                    else if (!s.romfs && typ == 0x80 && exefs < 0) exefs = exefs(r, b, s, nm.toString());
                }
            }
            int root = b.dirs.size();
            b.dirs.add(new long[] {b.name(new byte[0]), 0, b.kids.size(), 0});
            if (exefs >= 0) { b.dirs.get(root)[3]++; b.kids.add((long) exefs); }   // "exefs" sorts before "romfs"
            if (romfs >= 0) {
                long[] rd = b.dirs.get(romfs);
                byte[] rn = "romfs".getBytes(StandardCharsets.UTF_8);
                rd[0] = b.name(rn); rd[1] = rn.length;   // the RomFS root, whose own name is empty, is the package's "romfs" folder
                b.dirs.get(root)[3]++;
                b.kids.add((long) romfs);
            }
            int kind = typ == 0x82 ? ADDON : ver != 0 ? UPDATE : BASE, flags = kind == UPDATE ? 1 : 0;   // a snapshot of a version is a base-less update
            rec.add(new long[] {id, ver, root, romfs, kind, flags, exefs});
        }
        long nd = b.dirs.size(), nf = b.files.size(), np = rec.size();
        byte[] pool = b.pool.toByteArray();
        long oDir = 0x30 + 0x40 * np, oFile = oDir + 0x18 * nd, oKids = oFile + 0x50 * nf, oPool = oKids + 4L * b.kids.size();
        ByteBuffer t = ByteBuffer.allocate((int) (oPool + pool.length)).order(ByteOrder.LITTLE_ENDIAN);
        t.put(new byte[] {'T', 'O', 'C', 1}).putInt((int) np).putLong(nd).putLong(nf).putLong(oPool).putLong(pool.length);
        for (int i = 0; i < np; i++) {
            long[] p = rec.get(i);
            t.position(0x30 + 0x40 * i);
            t.putLong(p[0]).putInt((int) p[2]).putInt(p[6] < 0 ? (int) Nup.NONE : (int) p[6]).putInt(p[3] < 0 ? (int) Nup.NONE : (int) p[3]).putInt((int) p[1]).put((byte) p[4]).put((byte) p[5]);
            out.packages.add(new Nup.Pkg(i, p[0], p[2], p[6] < 0 ? Nup.NONE : p[6], p[3] < 0 ? Nup.NONE : p[3], p[1], (int) p[4], (int) p[5]));
        }
        for (int i = 0; i < nd; i++) {
            long[] d = b.dirs.get(i);
            t.position((int) (oDir + 0x18L * i));
            t.putInt((int) d[0]).putInt((int) d[1]).putInt((int) d[2]).putInt((int) d[3]);
        }
        for (int i = 0; i < nf; i++) {
            long[] f = b.files.get(i);
            t.position((int) (oFile + 0x50L * i));
            t.putInt((int) f[0]).putInt((int) f[1]).putLong(f[2]).putLong(f[3]).putLong(f[2]);
        }
        for (int i = 0; i < b.kids.size(); i++) {
            long k = b.kids.get(i);
            t.position((int) (oKids + 4L * i));
            t.putInt((int) ((k & FILE) != 0 ? nd + (k & ~FILE) : k));
        }
        t.position((int) oPool);
        t.put(pool);
        out.toc = t.array();
        out.dirs = nd; out.files = nf; out.poolOff = oPool; out.poolSize = pool.length;
        return out;
    }

    private static final int BASE = Nup.BASE, UPDATE = Nup.UPDATE, ADDON = Nup.ADDON;
}
