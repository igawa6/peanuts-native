#!/usr/bin/env python3
# Media.Vision's MVGL archives (Digimon Story Cyber Sleuth: Complete Edition, RomFS DSDB*.nx64.mvgl): every game file is in one of nine of them.
# Layout, little-endian, no encryption on the Switch:
#   +0x00 'MDB1', u16 tree entries, u16 name entries, u32 data entries, u32 data start, u32 archive size
#   +0x14 tree entries, 8 bytes each: u16 compare bit, u16 data index (0xffff for the root and inner nodes), u16 left, u16 right (a Patricia tree over the names)
#   then name entries, 0x40 bytes each, by tree index: char[4] extension (space-padded), char[0x3c] name ('\'-separated path without extension)
#   then data entries, 12 bytes each: u32 offset (from data start), u32 length, u32 stored length
# A file whose stored length is below its length is doboz-compressed (Attila T. Afra's doboz, version 0): one attributes byte (bits 0-2 version, 3-5 size width - 1,
# bit 7 stored), the length and the stored length in that width, then the stream: a 32-bit control word per 32 items (bit 0 first, 0 = literal byte, 1 = match),
# refilled when only its sentinel bit is left; a match is 1 to 4 bytes by its low 3 bits (offset and length - 3, see MATCH). The last 8 bytes are always literals.
# Usage: mvgl.py list <archive> [ext]   |   mvgl.py get <archive> <name>.<ext> <out file>
import os, struct, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../../tools'))
from romfs import os, open

MATCH = [(0xff, 2, 0, 0, 1), (0xffff, 2, 0, 0, 2), (0xffff, 6, 15, 2, 2), (0xffffff, 8, 31, 3, 3),
         (0xff, 2, 0, 0, 1), (0xffff, 2, 0, 0, 2), (0xffff, 6, 15, 2, 2), (0xffffffff, 11, 255, 3, 4)]   # mask, offset shift, length mask, length shift, size
TAIL = 8


def doboz(b):
    a = b[0]
    if a & 7: raise ValueError('doboz version %d' % (a & 7))
    w = ((a >> 3) & 7) + 1
    size, csize = int.from_bytes(b[1:1 + w], 'little'), int.from_bytes(b[1 + w:1 + 2 * w], 'little')
    i = 1 + 2 * w
    if a & 0x80: return bytes(b[i:i + size])
    out = bytearray(); src = b + b'\0' * 8; ctl = 1
    while len(out) < size:
        if ctl == 1: ctl = int.from_bytes(src[i:i + 4], 'little'); i += 4
        if not ctl & 1:
            if len(out) < size - TAIL: out.append(src[i]); i += 1
            else:   # the tail: every remaining byte is a literal, whatever the control bits say (doboz's decoder does the same)
                while len(out) < size:
                    if ctl == 1: ctl = int.from_bytes(src[i:i + 4], 'little'); i += 4
                    out.append(src[i]); i += 1; ctl >>= 1
                break
        else:
            word = int.from_bytes(src[i:i + 4], 'little'); m = MATCH[word & 7]
            off = (word & m[0]) >> m[1]; n = ((word >> m[3]) & m[2]) + 3; i += m[4]
            s = len(out) - off
            if off >= n: out += out[s:s + n]
            else:
                for k in range(n): out.append(out[s + k])
        ctl >>= 1
    if i - (1 + 2 * w) > csize + 4: raise ValueError('doboz stream overrun')
    return bytes(out)


# PEANUTS begin(upstream-candidate): the 64-bit layout of Digimon Story Time Stranger (010062E01FE0C000, RomFS *.nx64.mvgl; app_0.nx64.mvgl is 6.2 GB)
#   +0x00 'MDB1', u32 tree entries, u32 name entries, u32 data entries, u64 data start, u64 archive size
#   +0x20 tree entries, 16 bytes each: u32 compare bit, u32 data index (0xffffffff for the root and inner nodes), u32 left, u32 right
#   then name entries, 0x80 bytes each: char[4] extension, char[0x7c] name; then data entries, 16 bytes each: u64 offset (from data start), u32 length, u32 stored length
# A file whose stored length is below its length is one LZ4 block (not doboz). The layout is told by its tables ending exactly at the data start.
def wide(h):
    nt, nn, nd, start, size = struct.unpack_from('<IIIQQ', h, 4)
    return 0x20 + nt * 16 + nn * 0x80 + nd * 16 == start
# PEANUTS end


class Archive:
    def __init__(self, path):
        self.f = open(path, 'rb')
        # PEANUTS begin(upstream-candidate): the 64-bit layout (wide, above)
        h = self.f.read(0x20); self.f.seek(0x14); self.wide = h[:4] == b'MDB1' and wide(h)
        if self.wide:
            nt, nn, nd, self.start, self.size = struct.unpack_from('<IIIQQ', h, 4); self.f.seek(0x20)
            tree, names, data = self.f.read(nt * 16), self.f.read(nn * 0x80), self.f.read(nd * 16)
            self.files = {}
            for k in range(nt):
                idx = struct.unpack_from('<I', tree, k * 16 + 4)[0]
                if idx == 0xffffffff: continue
                ext = names[k * 0x80:k * 0x80 + 4].rstrip(b'\0 ').decode('latin1')
                name = names[k * 0x80 + 4:(k + 1) * 0x80].split(b'\0')[0].decode('latin1')
                off, n, cn = struct.unpack_from('<QII', data, idx * 16)
                self.files[name + '.' + ext] = (self.start + off, n, cn)
            return
        # PEANUTS end
        magic, nt, nn, nd, self.start, self.size = struct.unpack('<4sHHIII', h[:0x14])   # PEANUTS(upstream-candidate): the header read once, above
        if magic != b'MDB1': raise ValueError('%s: not an MVGL archive' % path)
        tree, names, data = self.f.read(nt * 8), self.f.read(nn * 0x40), self.f.read(nd * 12)
        self.files = {}   # 'name.ext' → (offset, length, stored length)
        for k in range(nt):
            idx = struct.unpack_from('<H', tree, k * 8 + 2)[0]
            if idx == 0xffff: continue
            ext = names[k * 0x40:k * 0x40 + 4].rstrip(b'\0 ').decode('latin1')
            name = names[k * 0x40 + 4:(k + 1) * 0x40].split(b'\0')[0].decode('latin1')
            off, n, cn = struct.unpack_from('<III', data, idx * 12)
            self.files[name + '.' + ext] = (self.start + off, n, cn)

    def read(self, name):
        off, n, cn = self.files[name]
        self.f.seek(off); b = self.f.read(cn)
        if self.wide and cn < n:   # PEANUTS(upstream-candidate): the 64-bit layout's entries are LZ4 blocks
            import lz4.block
            return lz4.block.decompress(b, uncompressed_size=n)
        return doboz(b) if cn < n else b


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == 'list':
        a = Archive(sys.argv[2])
        for nm, (off, n, cn) in sorted(a.files.items()):
            if len(sys.argv) < 4 or nm.endswith('.' + sys.argv[3]): print('%s\t%d\t%d' % (nm, n, cn))
    elif len(sys.argv) == 5 and sys.argv[1] == 'get':
        open(sys.argv[4], 'wb').write(Archive(sys.argv[2]).read(sys.argv[3]))
    else:
        sys.exit(__doc__ or 'usage: mvgl.py list <archive> [ext] | get <archive> <name.ext> <out>')
