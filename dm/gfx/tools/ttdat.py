#!/usr/bin/env python3
# PEANUTS: TT Games' DAT archives (LEGO CITY UNDERCOVER on the Switch: GAME.DAT, GAME0-2.DAT and the update's PATCH.DAT, 8.5 GB). The file's first u32 (little-endian) is where the
# index is: with the top bit set, ((v ^ 0xffffffff) << 8) + 0x100, else v itself; the second u32 is the index's length. The index is big-endian: u32 length, '.CC40TAD', i32 version
# (-11 here, the only one read), u32 ?, u32 file count, u32 name count, u32 name table length, the name table (NUL-terminated strings), then one 12-byte entry per name
# {u16 last child (0 for a file), u16 previous sibling, u32 name offset, u16 parent, u16 file index}, then u32 0, i32 version again, u32 file count, and one 16-byte entry per file
# {u64 offset, u32 stored length, u32 length | 0x80000000 when compressed}. A compressed file is a run of 'RFPK' chunks {u32 stored length, u32 length (little-endian), the stored bytes},
# each stored as it is when the two lengths are equal, else a RefPack-like stream that the game's own `unref` (main+0xc77ee8 in 1.0.3, called by DecodeBuffer::Decode) decodes; unref() below is that function, op for op.
# The game's shaders are BNSH files inside its .nnx_shaders resources (a '.CC4HSER' resource header), read by shader_split.py's scan_blob (ttdat_jobs, ttdat_shaders).
# Usage: ttdat.py <file.DAT> lists the files (offset, stored length, length, compressed, path).
import struct, sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../../tools'))
from romfs import open

SIG = b'.CC40TAD'
PACKED = 0x80000000
RFPK, ZIPX = b'RFPK', b'ZIPX'


def index_at(head):
    """Where the index is and its length, from the file's first 8 bytes; None for a file too short to be an archive"""
    if len(head) < 8: return None
    off, n = struct.unpack_from('<II', head)
    if off & 0x80000000: off = ((off ^ 0xffffffff) << 8) + 0x100
    return off, n


def is_ttdat(path):
    """The file is a TT Games DAT archive: '.CC40TAD' where its first word says the index is"""
    try:
        with open(path, 'rb') as f:
            at = index_at(f.read(8))
            if not at or at[1] < 0x20: return False
            f.seek(0, 2)
            if at[0] + at[1] > f.tell(): return False
            f.seek(at[0] + 4); return f.read(8) == SIG
    except (OSError, ValueError):
        return False


def files(path):
    """The archive's files → [(path, offset, stored length, length, compressed)], in index order. ValueError for a layout this does not read"""
    with open(path, 'rb') as f:
        at = index_at(f.read(8))
        if not at: raise ValueError('too short')
        f.seek(at[0]); b = f.read(at[1] + 4)
    _, sig, ver, _, nfiles, nnames, nsize = struct.unpack_from('>I8siIIII', b)
    if sig != SIG: raise ValueError(f'no {SIG.decode()} index')
    if ver != -11: raise ValueError(f'index version {ver}: only -11 is known')
    st = b[0x20:0x20 + nsize]
    t = 0x20 + nsize
    ents = [struct.unpack_from('>HHIHH', b, t + 12 * i) for i in range(nnames)]
    t += 12 * nnames
    v, n = struct.unpack_from('>iI', b, t + 4)
    if v != ver or n != nfiles: raise ValueError(f'file table: version {v}, {n} files (the header says {nfiles})')
    t += 12
    ft = [struct.unpack_from('>QII', b, t + 16 * i) for i in range(nfiles)]

    def name(o): return st[o:st.index(b'\0', o)].decode('latin1')
    out, seen = [], set()
    for i, (last, _, no, par, fid) in enumerate(ents):
        if i == 0 or last: continue   # the root, or a folder
        parts, p = [name(no)], par
        for _ in range(len(ents)):
            if not p: break
            if p >= len(ents): raise ValueError(f'name {i}: parent {p} of {len(ents)} names')
            parts.append(name(ents[p][2])); p = ents[p][3]
        else: raise ValueError(f'name {i}: its parents loop')
        if fid >= nfiles or fid in seen: raise ValueError(f'name {i}: file index {fid}')
        seen.add(fid)
        off, stored, size = ft[fid]
        out.append(('/'.join(reversed(parts)), off, stored, size & ~PACKED, bool(size & PACKED)))
    return out


def unref(s, size):
    """One RFPK chunk's stream → its bytes. Ops, by the first byte b0: 0xxxxxxx b1 (literals (b0 >> 2) & 3, copy ((b0 >> 4) & 7) + 3 from ((b0 & 3) << 8 | b1) + 1 back);
    10xxxxxx b1 b2 (literals b1 >> 6, copy (b0 & 0x3f) + 4 from ((b1 & 0x3f) << 8 | b2) + 1); 110xxxxx b1 b2 b3 (literals (b0 >> 3) & 3, copy (((b0 >> 1) & 3) << 8 | b3) + 5 from
    ((b0 & 1) << 16 | b1 << 8 | b2) + 1); 111xxxxx (((b0 & 0x1f) << 2) + 4 literals; above 0x70, b0 & 3 literals and the end). The literals come before the copy"""
    try: return unref_(s, size)
    except IndexError: raise ValueError(f'the stream ends inside an op ({len(s)} bytes)') from None   # a truncated or damaged chunk: as any bad layout, not this reader's exception


def unref_(s, size):
    d = bytearray(); i = 0; n_s = len(s)
    while i < n_s:
        b0 = s[i]
        if b0 < 0x80:
            b1 = s[i + 1]; lit = (b0 >> 2) & 3; i += 2
            off = (((b0 & 3) << 8) | b1) + 1; n = ((b0 >> 4) & 7) + 3
        elif b0 < 0xc0:
            b1, b2 = s[i + 1], s[i + 2]; lit = b1 >> 6; i += 3
            off = (((b1 & 0x3f) << 8) | b2) + 1; n = (b0 & 0x3f) + 4
        elif b0 < 0xe0:
            b1, b2, b3 = s[i + 1], s[i + 2], s[i + 3]; lit = (b0 >> 3) & 3; i += 4
            off = (((b0 & 1) << 16) | (b1 << 8) | b2) + 1; n = ((((b0 >> 1) & 3) << 8) | b3) + 5
        else:
            lit = ((b0 & 0x1f) << 2) + 4
            if lit > 0x70:
                lit = b0 & 3; d += s[i + 1:i + 1 + lit]; break
            d += s[i + 1:i + 1 + lit]; i += 1 + lit; continue
        if lit: d += s[i:i + lit]; i += lit
        p = len(d) - off
        if p < 0: raise ValueError(f'a copy from {off} bytes back at {len(d)}')
        if off >= n: d += d[p:p + n]
        else: d += (d[p:] * (n // off + 1))[:n]   # an overlapping copy repeats the last off bytes
    if len(d) != size: raise ValueError(f'{len(d)} bytes decoded, the chunk says {size}')
    return d


def rc4(key, data):
    """RC4 (NuRC4File::RC4Init / RC4Crypt, main+0x386be0 / +0x386d98)"""
    S = list(range(256)); j = 0
    for i in range(256): j = (j + S[i] + key[i % len(key)]) & 255; S[i], S[j] = S[j], S[i]
    i = j = 0; out = bytearray(len(data))
    for x, c in enumerate(data):
        i = (i + 1) & 255; j = (j + S[i]) & 255; S[i], S[j] = S[j], S[i]; out[x] = c ^ S[(S[i] + S[j]) & 255]
    return out


def unpack(b, size):
    """A compressed file's stored bytes → its bytes: 'RFPK' chunks (unref), or 'ZIPX' chunks {u32 stored length, u32 length, the bytes RC4-encrypted with the 4 bytes of the
    length as the key} (DecodeBuffer::Decode's mode 6; the game's .txt files)"""
    out = bytearray(); i = 0
    while i < len(b):
        tag = b[i:i + 4]
        if i + 12 > len(b): raise ValueError(f'chunk at {i}: {len(b) - i} bytes, no whole header')
        z, u = struct.unpack_from('<II', b, i + 4)
        if tag == RFPK: out += b[i + 12:i + 12 + z] if z == u else unref(b[i + 12:i + 12 + z], u)   # a chunk that would not shrink is stored (Decode: memcpy)
        elif tag == ZIPX and z == u: out += rc4(struct.pack('<I', u), b[i + 12:i + 12 + z])
        else: raise ValueError(f'chunk {tag!r} at {i}: not known')
        i += 12 + z
    if len(out) != size: raise ValueError(f'{len(out)} bytes decoded, the index says {size}')
    return bytes(out)


def read(path, e, f=None):
    """One file (an entry of files()) → its bytes"""
    _, off, stored, size, packed = e
    if f is None:
        with open(path, 'rb') as g: g.seek(off); b = g.read(stored)
    else: f.seek(off); b = f.read(stored)
    if len(b) != stored: raise ValueError(f'{len(b)} of {stored} bytes')
    return unpack(b, size) if packed else b


if __name__ == '__main__':
    for e in files(sys.argv[1]): print(f'{e[1]:#012x}\t{e[2]}\t{e[3]}\t{int(e[4])}\t{e[0]}')
