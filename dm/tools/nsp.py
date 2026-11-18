#!/usr/bin/env python3
# A plaintext NSP, as NSP Box (github.com/smarkyie/NSP-Box) writes it from a game snapshot or a DLC folder: a PFS0 holding unsigned, unencrypted NCAs. Read in place,
# with no keys: an encrypted (retail) NCA, a patch (delta) and anything this reader does not know are refused with what they are.
#   PFS0     magic, count, string table size, entries (offset, size, name offset), names, data (offsets from the end of the string table)
#   NCA3     header at 0 (0xC00, plain: "NCA3" at 0x200), content type at 0x205, program ID at 0x210, four section entries at 0x240 (start/end in 512-byte sectors),
#            their hashes at 0x280 and their FS headers at 0x400 + 0x200 * i (version, fs type 0 RomFS / 1 PFS0, hash type 2 / 3, encryption 1 = none)
#   PFS0 section: hash info at +8 (master hash, block size, regions: hash table, then the PFS0's offset and size); RomFS section: IVFC levels at +0x18 + 24 * i
#            (offset, size, block size log2), the data level last
#   metadata NCA (type 1): a PFS0 with one .cnmt (dm/tools/cnmt.py's layout: the type, the version, the contents with their NCA IDs); a game's contents are a
#            Program NCA (ExeFS PFS0 section, RomFS section) and a Control NCA (RomFS: control.nacp, icon_<Language>.dat); a DLC's is one Data NCA (RomFS)
# An NCA's file name is the first 16 bytes of the SHA-256 of the whole NCA; verify=True checks it for each NCA (one read of everything) and the content
# record's hash. NSP Box's NSP is a complete snapshot of one game version (its update already laid over the base), so it stands for updates/<version>/ of a folder dump
# (base/ for version 0), a DLC for dlc/<ID>/<version>/ (scripts/nsp_extract.py writes those).
# Usage: nsp.py <file.nsp>...   (prints each package; exit 1 on a bad one)
import hashlib, os, re, struct, sys
from pathlib import Path
from typing import cast   # PEANUTS(upstream-candidate): reads borrow the open descriptor; close marks it None

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cnmt

APPLICATION, ADDON = cnmt.APPLICATION, cnmt.ADDON
NONE = 0xFFFFFFFF
CHUNK = 1 << 20


class Error(ValueError): pass


def need(ok, msg):
    if not ok: raise Error(msg)


class Package:
    """One content meta of an NSP: kind (cnmt.APPLICATION or cnmt.ADDON), id, version, and its files as {path: (offset in the NSP, size)}. Paths: exefs/..., romfs/...,
    control/... for a game, romfs/... for an add-on; cnmt is the record's bytes"""
    def __init__(self, kind, id, version, files, cnmt_name, cnmt_bytes):
        self.kind, self.id, self.version, self.files, self.cnmt_name, self.cnmt = kind, id, version, files, cnmt_name, cnmt_bytes

    def __repr__(self):
        return f'<{cnmt.TYPES.get(self.kind, hex(self.kind))} {self.id:016X} v{self.version}: {len(self.files)} files, {sum(s for _, s in self.files.values()):,} bytes>'


class Nsp:
    """A plaintext NSP read through a descriptor with pread (the descriptor stays the caller's: Nsp.close does not close it) or, given a path, through a file it opens"""
    def __init__(self, source, verify=False, size=None, name=None):
        self.own = isinstance(source, (str, bytes, os.PathLike))
        self.fd = os.open(source, os.O_RDONLY | getattr(os, 'O_BINARY', 0)) if self.own else source
        self.path = Path(os.fsdecode(source) if self.own else (name or 'the NSP'))
        try:
            self.size = size if size is not None else os.fstat(self.fd).st_size
            need(self.size >= 0x10, f'{self.path}: too short for a PFS0')
            self.entries = self._pfs(0, self.size, 'the NSP')
            self.packages = self._packages(verify)
        except BaseException:
            self.close(); raise

    def close(self):
        if self.own and self.fd is not None: os.close(self.fd)
        self.fd = None

    def __enter__(self): return self
    def __exit__(self, *a): self.close()

    def read(self, off, n):
        need(0 <= off and 0 <= n and off + n <= self.size, f'{self.path.name}: a read outside the file')
        out = []
        while n > 0:
            b = os.pread(cast(int, self.fd), min(n, 1 << 30), off) if hasattr(os, 'pread') else self._seek_read(off, min(n, 1 << 30))   # PEANUTS: open descriptor
            need(b, f'{self.path.name}: the file is shorter than its headers say')
            out.append(b); off += len(b); n -= len(b)
        return b''.join(out)

    def _seek_read(self, off, n):   # no os.pread (Windows): one reader at a time
        os.lseek(cast(int, self.fd), off, os.SEEK_SET)   # PEANUTS: open descriptor
        return os.read(cast(int, self.fd), n)   # PEANUTS: open descriptor

    def copy_to(self, off, n, out):
        for at in range(off, off + n, CHUNK): out.write(self.read(at, min(CHUNK, off + n - at)))

    def _pfs(self, base, size, what) -> dict[str, tuple[int, int]]:   # PEANUTS(upstream-candidate): offsets and sizes are pairs, including _nca calls
        """{name: (absolute offset, size)} of the PFS0 at base (size bytes)"""
        need(size >= 0x10 and self.read(base, 4) == b'PFS0', f'{self.path.name}: {what} is not a plaintext PFS0')
        count, strsize = struct.unpack_from('<II', self.read(base + 4, 8))
        need(count <= 1 << 20 and 0x10 + 0x18 * count + strsize <= size, f'{self.path.name}: {what}: the PFS0 header is cut off')
        table = self.read(base + 0x10, 0x18 * count)
        strings = self.read(base + 0x10 + 0x18 * count, strsize)
        data, out = base + 0x10 + 0x18 * count + strsize, {}
        for i in range(count):
            off, n, name_off, _ = struct.unpack_from('<QQII', table, 0x18 * i)
            need(name_off < strsize and off + n <= size - (0x10 + 0x18 * count + strsize), f'{self.path.name}: {what}: entry {i} is outside the PFS0')
            end = strings.index(b'\0', name_off) if b'\0' in strings[name_off:] else strsize
            name = strings[name_off:end].decode('utf-8', 'replace')
            need(name and '/' not in name and name not in out, f'{self.path.name}: {what}: a bad or repeated file name {name!r}')
            out[name] = (data + off, n)
        return out

    def _nca(self, off, size, name):
        """(content type, program ID, [(fs type, absolute offset of its data, size)]) of the NCA at off: the sections as the header gives them"""
        need(size >= 0xC00, f'{name}: shorter than an NCA header')
        h = self.read(off, 0xC00)
        need(h[0x200:0x204] == b'NCA3', f'{name}: no plain NCA3 header (an encrypted, retail NCA: this reader takes only plaintext NSPs, with no keys)')
        ctype, = h[0x205:0x206]
        tid, = struct.unpack_from('<Q', h, 0x210)
        secs = []
        for i in range(4):
            start, end = struct.unpack_from('<II', h, 0x240 + 16 * i)
            if not start and not end: continue
            need(6 <= start < end and end * 512 <= size, f'{name}: section {i} is outside the NCA')
            fh = h[0x400 + 0x200 * i:0x600 + 0x200 * i]
            fs_type, hash_type, enc = fh[2], fh[3], fh[4]
            need(enc == 1, f'{name}: section {i} is encrypted (type {enc}); only plaintext NCAs are read')
            sec = off + start * 512
            if hash_type == 2 and fs_type == 1:   # PFS0, hierarchical SHA-256
                doff, dsize = struct.unpack_from('<QQ', fh, 0x40)
                need(doff + dsize <= (end - start) * 512, f'{name}: section {i}: the PFS0 is outside its section')
                secs.append((1, sec + doff, dsize))
            elif hash_type == 3 and fs_type == 0:   # RomFS, IVFC
                need(fh[8:12] == b'IVFC', f'{name}: section {i}: no IVFC header')
                levels = struct.unpack_from('<I', fh, 0x14)[0] - 1
                need(2 <= levels <= 6, f'{name}: section {i}: {levels} IVFC levels')
                doff, dsize = struct.unpack_from('<QQ', fh, 0x18 + 24 * (levels - 1))
                need(doff + dsize <= (end - start) * 512, f'{name}: section {i}: the RomFS is outside its section')
                secs.append((0, sec + doff, dsize))
            else: raise Error(f'{name}: section {i}: filesystem {fs_type} with hash {hash_type} is not read')
        return ctype, tid, secs

    def _romfs(self, off, size, name) -> dict[str, tuple[int, int]]:   # PEANUTS(upstream-candidate): the same offset/size pairs as PFS files
        """{path: (absolute offset, size)} of the RomFS at off, walked with bounds and cycle checks"""
        need(size >= 0x50, f'{name}: RomFS too short')
        hdr = struct.unpack('<10Q', self.read(off, 0x50))
        need(hdr[0] == 0x50, f'{name}: bad RomFS header')
        dt, ds, ft, fs, data = hdr[3], hdr[4], hdr[7], hdr[8], hdr[9]
        need(dt + ds <= size and ft + fs <= size and data <= size, f'{name}: RomFS tables out of bounds')
        dirs, files = self.read(off + dt, ds), self.read(off + ft, fs)
        out, seen_d, seen_f = {}, set(), set()
        def visit(d, prefix, depth):
            need(depth < 256, f'{name}: RomFS nested too deep')
            while d != NONE:
                need(d + 0x18 <= ds and d not in seen_d, f'{name}: bad or cyclic RomFS directory')
                seen_d.add(d)
                _, sib, child, first, _, n = struct.unpack_from('<6I', dirs, d)
                need(n <= ds - d - 0x18, f'{name}: RomFS directory name out of bounds')
                label = dirs[d + 0x18:d + 0x18 + n].decode('utf-8')
                if d and (not label or '/' in label or label in ('.', '..')): raise Error(f'{name}: unsafe RomFS name {label!r}')
                here = f'{prefix}/{label}' if prefix and label else (prefix or label)
                f = first
                while f != NONE:
                    need(f + 0x20 <= fs and f not in seen_f, f'{name}: bad or cyclic RomFS file')
                    seen_f.add(f)
                    _, nxt, fo, fsize, _, fn = struct.unpack_from('<IIQQII', files, f)
                    need(fn <= fs - f - 0x20 and fo + fsize <= size - data, f'{name}: RomFS file out of bounds')
                    label = files[f + 0x20:f + 0x20 + fn].decode('utf-8')
                    need(label and '/' not in label and label not in ('.', '..') and '\0' not in label, f'{name}: unsafe RomFS name {label!r}')
                    path = f'{here}/{label}' if here else label
                    need(path not in out, f'{name}: {path} twice')
                    out[path] = (off + data + fo, fsize)
                    f = nxt
                if child != NONE: visit(child, here, depth + 1)
                d = sib
                if d != NONE and depth == 0: raise Error(f'{name}: the RomFS root has a sibling')
        visit(0, '', 0)
        return out

    def _sha_name(self, name, off, size):
        h = hashlib.sha256()
        for at in range(off, off + size, CHUNK): h.update(self.read(at, min(CHUNK, off + size - at)))
        return h.digest()

    def _packages(self, verify):
        metas = {n: e for n, e in self.entries.items() if n.endswith('.cnmt.nca')}
        need(metas, f'{self.path.name}: no metadata NCA (*.cnmt.nca): not an NSP Box package')
        need(all(re.fullmatch(r'[0-9a-f]{32}(\.cnmt)?\.nca', n) for n in self.entries), f'{self.path.name}: files that are not NCAs ({", ".join(sorted(self.entries))}): a retail NSP carries tickets and certificates; this reader takes only plaintext NSPs')
        digests, claimed, pkgs = {}, set(), []
        def digest(name):
            if name not in digests: digests[name] = self._sha_name(name, *self.entries[name])
            return digests[name]
        for mname in sorted(metas):
            claimed.add(mname)
            if verify: need(digest(mname)[:16].hex() == mname[:32], f'{mname}: its content does not match its name')
            ctype, tid, secs = self._nca(*self.entries[mname], mname)
            need(ctype == 1 and secs and secs[0][0] == 1, f'{mname}: not a metadata NCA')
            cn = self._pfs(secs[0][1], secs[0][2], mname)
            need(len(cn) == 1 and next(iter(cn)).endswith('.cnmt'), f'{mname}: expected one .cnmt')
            cname, (coff, csize) = next(iter(cn.items()))
            cbytes = self.read(coff, csize)
            try: rec = cnmt.parse(cbytes, cname)
            except cnmt.Error as e: raise Error(str(e)) from None
            need(rec.type in (APPLICATION, ADDON), f'{cname}: a {cnmt.TYPES.get(rec.type, hex(rec.type))} record; only a game snapshot or a DLC is read (a patch or delta needs the console\'s own formats)')
            need(hashlib.sha256(cbytes[:-32]).digest() == cbytes[-32:], f'{cname}: its digest does not match')
            files: dict[str, tuple[int, int]] = {}   # PEANUTS(upstream-candidate): the per-package table add() fills
            exs = struct.unpack_from('<H', cbytes, 0x0E)[0]
            for i in range(struct.unpack_from('<H', cbytes, 0x10)[0]):
                at = 0x20 + exs + 0x38 * i
                chash, cid = cbytes[at:at + 32], cbytes[at + 32:at + 48]
                csz, ctyp = int.from_bytes(cbytes[at + 48:at + 53], 'little'), cbytes[at + 54]
                nname = cid.hex() + '.nca'
                need(nname in self.entries and self.entries[nname][1] == csz, f'{cname}: content {nname} is missing or its size differs')
                need(nname not in claimed, f'{nname}: shared by two records')
                claimed.add(nname)
                if verify:
                    need(digest(nname) == chash and digest(nname)[:16] == cid, f'{nname}: its content does not match its name and record')
                nctype, ntid, nsecs = self._nca(*self.entries[nname], nname)
                need(ntid == tid, f'{nname}: program ID {ntid:016X}, the record\'s is {tid:016X}')
                def add(prefix: str, tree: dict[str, tuple[int, int]], out: dict[str, tuple[int, int]]) -> None:   # PEANUTS(upstream-candidate): pass this package's output table explicitly
                    for p, v in tree.items():
                        key = f'{prefix}/{p}'
                        need(key not in out, f'{key} twice')
                        out[key] = v
                if rec.type == APPLICATION and ctyp == 1:   # Program: ExeFS (PFS0) and RomFS
                    need(nctype == 0, f'{nname}: record says Program, the NCA says type {nctype}')
                    pfs = [s for s in nsecs if s[0] == 1]; rom = [s for s in nsecs if s[0] == 0]
                    need(len(pfs) == 1 and len(rom) == 1, f'{nname}: expected one ExeFS and one RomFS section')
                    add('exefs', self._pfs(pfs[0][1], pfs[0][2], nname), files); add('romfs', self._romfs(rom[0][1], rom[0][2], nname), files)   # PEANUTS: this package's table
                elif rec.type == APPLICATION and ctyp == 3:   # Control: RomFS
                    need(nctype == 2 and len(nsecs) == 1 and nsecs[0][0] == 0, f'{nname}: not a control NCA')
                    add('control', self._romfs(nsecs[0][1], nsecs[0][2], nname), files)   # PEANUTS: this package's table
                elif rec.type == ADDON and ctyp == 2:   # Data: RomFS
                    need(nctype == 4 and len(nsecs) == 1 and nsecs[0][0] == 0, f'{nname}: not a data NCA')
                    add('romfs', self._romfs(nsecs[0][1], nsecs[0][2], nname), files)   # PEANUTS: this package's table
                else: raise Error(f'{nname}: content type {cnmt.CONTENT.get(ctyp, ctyp)} of a {cnmt.TYPES[rec.type]} is not read')
            if rec.type == APPLICATION:
                need('exefs/main' in files and 'exefs/main.npdm' in files and any(p.startswith('romfs/') for p in files), f'{cname}: not a complete game snapshot (exefs/main, main.npdm and a RomFS)')
            else: need(any(p.startswith('romfs/') for p in files), f'{cname}: a DLC with no RomFS')
            pkgs.append(Package(rec.type, rec.id, rec.version, files, cname, cbytes))
        need(claimed == set(self.entries), f'{self.path.name}: NCAs that no record names ({", ".join(sorted(set(self.entries) - claimed))}); refusing to drop them')
        return pkgs


def nup_toc(packages):
    """The TOC of a NUP file (SPEC §3: nup.py parses it as any) that describes these packages with every file a raw extent at its place in the NSP, so a reader of NUP
    files reads an NSP where it is: (TOC bytes, [(kind, title ID, version, flags)]). A game of version 0 is a base package, another version a standalone update
    (selector update/<version>; the NSP is a whole snapshot of that version, so it carries the whole game, as a base-less NUP file with an update does), a DLC an add-on.
    File records have no SHA-256 (the NCA's own hash covers them: Nsp(verify=True))"""
    pool, names = bytearray(), {}
    def name(n):
        b = n.encode('utf-8')
        if b not in names: names[b] = len(pool); pool.extend(b)
        return names[b], len(b)
    dirs, files, kids, roots, recs = [], [], [], [], []
    def visit(label, node):   # node: {name: subnode dict | (offset, size)}; returns the directory's index
        d = len(dirs); dirs.append(None)
        entries = sorted(node.items(), key=lambda kv: kv[0].encode('utf-8'))
        first = len(kids); kids.extend([None] * len(entries))
        for i, (n, v) in enumerate(entries):
            if isinstance(v, dict): kids[first + i] = ('d', visit(n, v))
            else: kids[first + i] = ('f', len(files)); files.append((name(n), v))
        dirs[d] = (name(label), first, len(entries))
        return d
    for p in packages:
        tree = {}
        for path, ext in p.files.items():
            parts = path.split('/')
            b = tree
            for part in parts[:-1]: b = b.setdefault(part, {})
            b[parts[-1]] = ext
        roots.append(visit('', tree))
    index = {}   # (package, 'exefs'|'romfs') -> directory index, found through the root's children
    for i, r in enumerate(roots):
        _, first, count = dirs[r]
        for t, n in kids[first:first + count]:
            if t == 'd':
                (no, nl), *_ = dirs[n]
                for want in ('exefs', 'romfs'):
                    if bytes(pool[no:no + nl]) == want.encode(): index[(i, want)] = n
    nd, nf, np_ = len(dirs), len(files), len(packages)
    o_dir = 0x30 + 0x40 * np_; o_file = o_dir + 0x18 * nd; o_kids = o_file + 0x50 * nf; o_pool = o_kids + 4 * len(kids)
    toc = bytearray(o_pool + len(pool))
    struct.pack_into('<4sIQQQQQ', toc, 0, b'TOC\x01', np_, nd, nf, o_pool, len(pool), 0)
    for i, p in enumerate(packages):
        kind, flags = (2, 0) if p.kind == ADDON else ((0, 0) if not p.version else (1, 1))
        struct.pack_into('<QIIIIBBHIQI20s', toc, 0x30 + 0x40 * i, p.id, roots[i], index.get((i, 'exefs'), NONE), index.get((i, 'romfs'), NONE), p.version, kind, flags, 0, 0, 0, 0, bytes(20))
        recs.append((kind, p.id, p.version, flags))
    for i, (n, first, count) in enumerate(dirs): struct.pack_into('<IIIIQ', toc, o_dir + 0x18 * i, n[0], n[1], first, count, 0)
    for i, (n, (off, size)) in enumerate(files): struct.pack_into('<IIQQQ32sBBH12s', toc, o_file + 0x50 * i, n[0], n[1], size, off if size else 0, size, bytes(32), 0, 0, 0, bytes(12))
    for i, (t, n) in enumerate(kids): struct.pack_into('<I', toc, o_kids + 4 * i, n if t == 'd' else nd + n)
    toc[o_pool:] = pool
    return bytes(toc), recs


def open_nsp(path, verify=False): return Nsp(path, verify)


def open_fd(fd, size, name, verify=False): return Nsp(fd, verify, size, name)


def main():
    if len(sys.argv) < 2: sys.exit('Usage: nsp.py <file.nsp>...')
    bad = 0
    for p in sys.argv[1:]:
        try:
            with open_nsp(p, verify=True) as n:
                for pk in n.packages: print(f'{p}: {pk!r}')
        except (Error, OSError) as e:
            print(f'[RED] {p}: {e}', file=sys.stderr); bad = 1
    sys.exit(bad)


if __name__ == '__main__': main()
