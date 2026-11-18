#!/usr/bin/env python3
"""Show or set a package's meta block (icon, title, version, publisher: SPEC §5) in a .nup, in place and without unpacking it.

The new block is appended to the end of the file, so no blob moves: only the package record (meta offset and size), the TOC's SHA-256 and the superblock's
file size are rewritten, a few KiB. The old block stays as dead bytes. Records of other types (the console's own type 1, the title ID, the name...) are kept.
A failure part way truncates the file back to its old size and puts the old TOC and superblock back; a run killed part way leaves bytes past the size the superblock
states (the superblock is written last), which the next run cuts off before it starts. Setting what the block already holds changes nothing.

Usage: scripts/nup_meta.py <file.nup> [--package base|update/<version>|addon/<title ID>] [--icon image.jpg|png] [--title T] [--version V] [--publisher P]
       nup_meta.py <file.nup> --show
The package defaults to the base (else the first). The cards read the icon, title and publisher from the base, the version from the newest update.
The icon is the image file as it is: a JPEG or PNG, 256x256 like on the console."""
import argparse, hashlib, os, struct, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
import nup

REC = struct.Struct('<HHI')   # type, 0, size
ICON, VERSION, TITLE, PUBLISHER = 3, 4, 5, 6   # SPEC §5; a block that has a type 0 record uses numbers one lower (nup packer 1d904e10 on), android/NUP.md
NAMES = {1: 'console meta', 3: 'icon', 4: 'version', 5: 'title', 6: 'publisher', 7: 'id', 8: 'name'}


def records(b):
    out, o = [], 0
    while o + REC.size <= len(b):
        t, _, n = REC.unpack_from(b, o)
        if n > len(b) - o - REC.size: break
        out.append((t, b[o + REC.size:o + REC.size + n])); o += REC.size + n
    return out


def pick(n, sel):
    if sel is None:
        for p in n.packages:
            if p.kind == nup.BASE: return p
        return n.packages[0]
    for p in n.packages:
        if p.selector == sel: return p
    raise nup.Error(f'{n.path}: no package {sel} (has {", ".join(p.selector for p in n.packages)})')


def show(path):
    n = nup.open_nup(path)
    with open(path, 'rb') as f:
        for i, p in enumerate(n.packages):
            moff, msize = nup.PKG.unpack_from(n.toc, n.o_pkg + nup.PKG.size * i)[9:11]
            print(f'{p.selector}: ' + ('no meta block' if not msize else f'meta block {msize} bytes'))
            if not msize: continue
            f.seek(moff)
            for t, d in records(f.read(msize)):
                what = NAMES.get(t, f'type {t}')
                print(f'  {what}: ' + (d.decode('utf-8', 'replace') if t in (4, 5, 6, 8) else f'{len(d)} bytes' + (' (' + ('JPEG' if d[:2] == b"\xff\xd8" else 'PNG' if d[:2] == b"\x89P" else 'unknown image') + ')' if t == ICON else '')))


def cut_unfinished(path):
    """Bytes past the superblock's file size are a killed run's appended block: nothing refers to them yet, so cut them off"""
    with open(path, 'r+b') as fh:
        head = fh.read(nup.SB.size)
        if len(head) == nup.SB.size and head[:3] == nup.MAGIC[:3]:
            fsize = nup.SB.unpack(head)[7]
            if os.fstat(fh.fileno()).st_size > fsize: fh.truncate(fsize); print(f'{path}: cut {fsize}.. off, an unfinished earlier run')


def set_meta(path, sel, icon, title, version, publisher):
    cut_unfinished(path)
    n = nup.open_nup(path)
    pkg = pick(n, sel)
    idx = pkg.index
    off = n.o_pkg + nup.PKG.size * idx
    f = list(nup.PKG.unpack_from(n.toc, off))
    moff, msize = f[9], f[10]
    with open(path, 'rb') as fh:
        old = b''
        if msize: fh.seek(moff); old = fh.read(msize)
    recs = records(old)
    shifted = any(t == 0 for t, _ in recs)
    new = {}
    if icon is not None:
        if not (icon[:2] == b'\xff\xd8' or icon[:4] == b'\x89PNG'): raise nup.Error('the icon is not a JPEG or a PNG file')
        new[ICON] = icon
    for t, v in ((TITLE, title), (VERSION, version), (PUBLISHER, publisher)):
        if v is not None: new[t] = v.encode('utf-8')
    if not new: raise nup.Error('nothing to set: give --icon, --title, --version or --publisher')
    sh = 1 if shifted else 0
    keep = [(t, d) for t, d in recs if t + sh not in new]
    block = b''.join(REC.pack(t, 0, len(d)) + d for t, d in keep + [(t - sh, d) for t, d in new.items()])
    if sorted(keep + [(t - sh, d) for t, d in new.items()]) == sorted(recs):
        print(f'{path}: {pkg.selector} already holds this, nothing changed'); return False
    if len(block) > 8 << 20: raise nup.Error('the meta block would be over 8 MiB')

    with open(path, 'r+b') as fh:
        sb_old = fh.read(nup.SB.size)
        toc_old = n.toc
        end = n.size
        try:
            pad = -end % 16
            fh.seek(end); fh.write(b'\0' * pad + block); fh.flush()
            f[9], f[10] = end + pad, len(block)
            toc = bytearray(toc_old); nup.PKG.pack_into(toc, off, *f)
            sb = list(nup.SB.unpack(sb_old))
            sb[5] = hashlib.sha256(bytes(toc)).digest()   # toc_sha
            sb[7] = end + pad + len(block)                # fsize
            fh.seek(sb[3]); fh.write(bytes(toc))
            fh.seek(0); fh.write(nup.SB.pack(*sb)); fh.flush(); os.fsync(fh.fileno())
        except BaseException:
            fh.seek(nup.SB.unpack(sb_old)[3]); fh.write(toc_old)
            fh.seek(0); fh.write(sb_old); fh.truncate(end); fh.flush()
            raise
    nup.open_nup(path)   # the result must read back as a valid file
    print(f'{path}: {pkg.selector} meta block {msize} -> {len(block)} bytes ({", ".join(NAMES[t] for t in sorted(new))} set)')
    return True


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or '').split('\n')[0])   # PEANUTS(upstream-candidate): module docs can be stripped
    ap.add_argument('nup'); ap.add_argument('--package'); ap.add_argument('--icon', type=Path)
    ap.add_argument('--title'); ap.add_argument('--version'); ap.add_argument('--publisher'); ap.add_argument('--show', action='store_true')
    a = ap.parse_args()
    try:
        if a.show: show(a.nup)
        else: set_meta(a.nup, a.package, a.icon.read_bytes() if a.icon else None, a.title, a.version, a.publisher)
    except (nup.Error, OSError) as e:
        print(f'nup_meta: {e}', file=sys.stderr); sys.exit(1)


if __name__ == '__main__':
    main()
