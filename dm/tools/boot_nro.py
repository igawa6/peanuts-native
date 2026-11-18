#!/usr/bin/env python3
"""NROs that a boot stub packs into its main's data segment, decoded offline (no run of the game).

Mario Tennis Fever's main (0400D4201C6EA000) is a boot stub. nnMain starts six threads, and each decodes one part of the game's code, a 24.7 MB NRO, from main's
data segment into one buffer. Then the stub hands the buffer to nn::ro::LoadModule. The NRR it registers first is a RomFS file and is not packed here.

The decoding of one part (main+0xde0 and its five siblings, 1.1.1):
  - A part is a stream read backwards from its end (a pointer in a table in .data). Each byte is unscrambled as raw ^ rotr8(the raw byte read before it, 1) ^ key,
    where key starts at key0 and grows by step for every byte read (mod 256). The stream starts 8 bytes before the end. The byte at end-8 is only the first "byte
    read before"; the four bytes before it unscramble to the part's decoded size (big-endian, then XOR a constant per part).
  - The last part also XORs every byte with the low byte of an MT19937 output (seed mt_seed; the first four outputs are unused, the next four unscramble the
    size, the stream takes the rest in order).
  - The unscrambled bytes are Camelot's LZ, the same as the CRA archive's kinds 0x50 and 0x60 (dm/gfx/tools/cra.py lz_undo).
  - The parts follow each other in the output, in table order.
Checked: the six parts of 1.1.1's main make the NRO that nxnative's Mac host captured (DM_NRO_CAPTURE), byte for byte: SHA-256 1ec347bc…ce975, the hash the NRR
registers. nro_collect.py keeps a decoded NRO only when an NRR registers its SHA-256.

The constants and the table's address are per build (BUILDS, keyed by main's build ID): a build not listed is not decoded."""
import os, struct, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'gfx', 'tools'))
import cra

# main's build ID (first 16 bytes, hex) → (where, the table of the parts' stream ends in the image, [(key0, step, size XOR, MT19937 seed or None)] per part)
BUILDS = {
    '104bce6874603701935eb76cb7d8b59e': ('Mario Tennis Fever 1.1.1 (v327680)', 0xcd6e0,
                                         [(0x68, 0x0d, 0x34414e5b, None), (0x70, 0x0e, 0x38465462, None), (0x78, 0x0f, 0x3c4b5a69, None),
                                          (0x80, 0x10, 0x40506070, None), (0x88, 0x11, 0x44556677, None), (0x90, 0x12, 0x485a6c7e, 0x76)]),
}
MT_UNUSED = 4   # MT19937 outputs the last part draws before its size


class Error(Exception): pass


def mt19937(seed):
    """The 32-bit outputs of MT19937 seeded with init_genrand(seed), as the stub's own generator (main+0x1da8)"""
    m = [seed & 0xffffffff]
    for i in range(1, 624): m.append((0x6c078965 * (m[-1] ^ (m[-1] >> 30)) + i) & 0xffffffff)
    while True:
        for k in range(624):
            y = (m[k] & 0x80000000) | (m[(k + 1) % 624] & 0x7fffffff)
            m[k] = m[(k + 397) % 624] ^ (y >> 1) ^ (0x9908b0df if y & 1 else 0)
        for y in m:
            y ^= y >> 11; y ^= (y << 7) & 0x9d2c5680; y ^= (y << 15) & 0xefc60000
            yield y ^ (y >> 18)


def unscramble(img, lo, end, key0, step, mt=None):
    """The bytes of the stream that ends at img[end], in reading order (from end-9 down to lo), unscrambled. mt: the generator, already past the size's outputs"""
    if not 0 <= lo <= end - 9 or end > len(img): raise Error(f'a stream [{lo:#x}, {end:#x}) outside the image ({len(img):#x} bytes)')
    out = bytearray(end - 8 - lo); key = key0
    for i, p in enumerate(range(end - 9, lo - 1, -1)):
        r, prev = img[p], img[p + 1]
        b = r ^ ((prev >> 1 | prev << 7) & 0xff) ^ key
        if mt is not None: b ^= next(mt) & 0xff
        out[i] = b; key = (key + step) & 0xff
    return out


def part_size(img, end, xor, mt=None):
    """A part's decoded size: the four bytes before end-8, each unscrambled against the next one down (no key; the last part's against MT outputs)"""
    w = 0
    for j in range(4, 8):
        hi, lo = img[end - j], img[end - j - 1]
        b = ((hi >> 1 | hi << 7) & 0xff) ^ lo
        if mt is not None: b ^= next(mt) & 0xff
        w = w << 8 | b
    return w ^ xor


def decode(img, lo, table, parts):
    """The NRO the parts make: [(key0, step, size XOR, MT seed)] with their stream ends in img at table, table + 8, … Part 0's stream starts at lo (the data segment), each
    other part's after the previous part's end"""
    out = bytearray()
    for i, (key0, step, xor, seed) in enumerate(parts):
        end = struct.unpack_from('<Q', img, table + 8 * i)[0]
        mt = None
        if seed is not None:
            mt = mt19937(seed)
            for _ in range(MT_UNUSED): next(mt)
        size = part_size(img, end, xor, mt)
        stream = unscramble(img, lo, end, key0, step, mt); lo = end
        got = cra.lz_undo(bytes(stream), 0)   # it stops at the end code (a distance 0): any bytes after it are padding
        if len(got) != size: raise Error(f'part {i} (stream end {end:#x}): {len(got):#x} bytes decoded, its size says {size:#x}')
        out += got
    return bytes(out)


def nros(data, name='module'):
    """[(label, NRO bytes)] that a module (an NSO's bytes) packs, or [] when its build is not in BUILDS. The build ID is read from the NSO header first, so other modules
    are not decompressed"""
    if data[:4] != b'NSO0' or len(data) < 0x60: return []
    b = bytes(data[0x40:0x50]).hex()
    if b not in BUILDS: return []
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import nso
    m = nso.load(data, name)
    where, table, parts = BUILDS[b]
    return [(f'{os.path.basename(name)} boot stub ({where})', decode(m.image, m.segments[2][0], table, parts))]


if __name__ == '__main__':
    if len(sys.argv) != 3: sys.exit('Usage: boot_nro.py <ExeFS main> <out .nro>')
    with open(sys.argv[1], 'rb') as f: got = nros(f.read(), sys.argv[1])
    if not got: sys.exit(f'[RED] {sys.argv[1]}: not a boot stub this tool knows (BUILDS)')
    import hashlib
    open(sys.argv[2], 'wb').write(got[0][1])
    print(f'{got[0][0]}: {len(got[0][1])} bytes, SHA-256 {hashlib.sha256(got[0][1]).hexdigest()} → {sys.argv[2]}')
