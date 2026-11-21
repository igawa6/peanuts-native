#!/usr/bin/env python3
"""dm/gfx/tools/drp.py, the DRPF (.drp) archive reader: a small archive written here in both of the format's forms (clear and encrypted) round-trips through entries(), a payload tagged 'Oodl'
decodes through the Oodle decoder the caller passes, and a damaged archive is refused. No game data: the archives are built from the format's own rules (see drp.py's header), the Oodle
stream is a block stored whole (0xCC, the decoder type, the bytes), which unoodle's tests also write by hand, and the XorShift seed roll is checked against a fixed vector so a silent
change of the crypto cannot pass."""
import struct, sys, unittest, zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'dm' / 'gfx' / 'tools'))
import drp

M32 = 0xFFFFFFFF
TABLE = bytes((i * 37 + 11) & 0x7F for i in range(0x80))   # a seed table of the test's own (the game's are whatever the archive carries)


def sar(v, n):   # the arithmetic shift of a 32-bit word, the sign filling, written out the long way
    return ((v - (1 << 32) if v & 0x80000000 else v) >> n) & M32


def word(st):    # one 32-bit word of output: a draw's 31 bits and the next draw's top two bits (XorShift.cs's GetNext(0xFFFFFFFF))
    def nxt():
        t = (st[0] ^ (st[0] << 11)) & M32
        st[0], st[1], st[2] = st[1], st[2], st[3]
        st[3] = (st[3] ^ sar(st[3], 19) ^ (t ^ sar(t, 8))) & M32
        return st[3] & 0x7FFFFFFF
    return nxt() | (nxt() >> 30) << 31


def rng(seed):   # the seed's four bytes are indexes into the archive's seed table, and the value they spell is rolled in from _w backwards (XorShift.cs's constructor)
    s = 0
    for i in range(4): s |= TABLE[(seed >> (i * 8)) & 0x7F] << (i * 8)
    w = (0x41C64E6D * s + 12345) & M32
    z = (0x41C64E6D * w + 12345) & M32
    y = (0x41C64E6D * z + 12345) & M32
    x = (0x41C64E6D * y + 12345) & M32
    return [x, y, z, w]


def oodl(body):  # an Oodle stream of one block stored whole (decoder type 6 = Kraken): what a reader's decoder passes through
    return bytes([0xCC, 6]) + body


def chunk(name, data, enc, ext=zlib.crc32(b'nushdb'), compress=True):
    """One file chunk: the seed (the Crc32 of the data), then 'resd', the whole size, the extension, the stored and decoded sizes, the name, the payload ('Oodl' and the stream, or the
    bytes), padded to a word; encrypted, the words past the seed go out under the chunk's own stream"""
    payload = struct.pack('<I', drp.OODL_MAGIC) + oodl(data) if compress else data   # the tag and the stream are the stored region the header's sizes count
    head = struct.pack('<6I', 0, drp.CHUNK_MAGIC, 0, ext, len(payload), len(data)) + name.encode('ascii') + b'\0'   # a seed placeholder first: word 0 is the Crc32 of the data
    doff = (0x18 + len(name) + 1 + 3) & ~3
    c = bytearray(head + b'\0' * (doff - len(head)) + payload)
    if len(c) & 3: c += b'\0' * (4 - (len(c) & 3))   # the tail the game's own compressor leaves (aligned, past the payload)
    c[8:12] = struct.pack('<I', len(c))
    c[0:4] = struct.pack('<I', zlib.crc32(data) & M32)
    if enc:
        st = rng(zlib.crc32(data) & M32)
        v = memoryview(c).cast('I')
        for i in range(1, len(c) >> 2): v[i] ^= word(st)
    return bytes(c)


def archive(entries, enc):
    """A DRPF archive of whole chunks: the magic, the checksum over the entry count and the seed table (the header's crypto seed), the header words (clear or encrypted), the chunks, and
    an encrypted footer (the reader needs none of it, but it is there)"""
    body = b''.join(entries)
    count = len(entries)
    head = bytearray(struct.pack('<III', drp.CHECK_MAGIC, drp.CRYPTO_BLOCK, count) + TABLE)
    seed = zlib.crc32(bytes(head[8:])) & M32   # the checksum covers the entry count and the seed table, the reader's data[0x10..0x94]
    if enc:
        st = rng(seed)
        v = memoryview(head).cast('I')
        for i in range(3): v[i] ^= word(st)
    foot = bytearray(b'DRPF footer bytes, an even length....\0\0\0\0\0'[:40])
    if enc:
        st = rng(seed)
        v = memoryview(foot).cast('I')
        for i in range(len(foot) >> 2): v[i] ^= word(st)
    return struct.pack('<II', drp.MAGIC, seed) + bytes(head) + body + bytes(foot)


def decode(comp, n):   # the test's Oodle decoder: a stored block passes its bytes through, and refuses everything else
    return bytes(comp[2:]) if comp[:2] == oodl(b'')[:2] and len(comp) - 2 == n else None


class Drp(unittest.TestCase):
    def test_rng_vector(self):
        """The RNG's first words for seed 0x12345678 over the test's seed table: the ones the field-checked reader produced, which the test's own writing of XorShift.cs's terms
        (sar and word above) reproduces, so a silent change of either side cannot pass"""
        st = rng(0x12345678)
        self.assertEqual([word(st) for _ in range(4)], [0x9200faeb, 0x45c19bdb, 0x83c18383, 0x2a7754d4])
        self.assertEqual(drp.EXTENSIONS[zlib.crc32(b'nushdb')], 'nushdb')   # the extension IDs are Crc32s of the extension names

    def test_roundtrip(self):
        """A clear archive and an encrypted one both give their entries back, Oodle payloads decoded and stored ones as they are, in order"""
        a = ('CharactersMaterials_CharacterShader_200247744.nushdb', bytes(range(256)) * 40)
        b = ('effects', bytes((i * 13) & 0xFF for i in range(1000)))   # a name without its extension: the label takes the extension from the entry's ID
        for enc in (False, True):
            with self.subTest(enc=enc):
                d = archive([chunk(a[0], a[1], enc), chunk(b[0], b[1], enc, ext=zlib.crc32(b'allb'), compress=False)], enc)
                self.assertEqual([(n, bytes(m)) for n, m in drp.entries(d, 'test.drp', decode)], [a, (b[0] + '.allb', b[1])])

    def test_refuses(self):
        """A bad magic, a broken seed table, a chunk that is not 'resd', a payload that does not decode to its length, a decoder that refuses the stream, a crypto block size other
        than 0x90, and an archive cut inside its last chunk are all red (SystemExit)"""
        good = chunk('a.nushdb', b'0123456789' * 10, True)
        whole = archive([good], True)
        cases = [
            b'\0\0\0\0' + whole[4:],                                    # the magic
            bytes([whole[0x14] ^ 0xFF]) + whole[0x15:],                 # the seed table: the header decrypts to something that is not fhdr
            archive([good[:4] + b'\0\0\0\0' + good[8:]], True),         # the chunk is not 'resd'
            archive([good[:-12] + bytes([good[-12] ^ 0xFF]) + good[-11:]], True),   # the payload's ciphertext: what decodes does not hash to the seed
        ]
        for k, d in enumerate(cases):
            with self.subTest(case=k): self.assertRaises(SystemExit, list, drp.entries(d, 'test.drp', decode))
        self.assertRaises(SystemExit, list, drp.entries(whole[:len(whole) - 44], 'test.drp', decode))   # cut inside the last chunk (the footer here is 40 bytes)
        bad_block = bytearray(whole); bad_block[0xC:0x10] = struct.pack('<I', 0x88)
        self.assertRaises(SystemExit, list, drp.entries(bytes(bad_block), 'test.drp', decode))
        self.assertRaises(SystemExit, list, drp.entries(whole, 'test.drp', lambda c, n: None))   # the decoder refused the stream


if __name__ == '__main__':
    unittest.main()
