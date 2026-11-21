#!/usr/bin/env python3
"""New Pokémon Snap's DRPF archives (.drp, 1,034 of its RomFS files): every resource of the game that is not a plain file sits in one, an archive of name- and extension-tagged entries,
most of them Oodle-compressed. The shaders are in the SSBH shader databases (.nushdb, SHDR v1.2 containers like Smash Ultimate's, whose GLSLC outputs shader_split.py's scan then reads).
Format and crypto ported from kwsch/NewSnap (ISC licence, credited): NewSnap.Lib/DRPF/{DrpArchive,DrpFileHeader,DrpFileEntry}.cs and NewSnap.Lib/Encryption/XorShift.cs; the same layout is
described by the finalpantasy/new-pokemon-snap-recomp census, which decrypts headers only (GPL-3.0, read for understanding, not copied). Ported, not linked, so the MIT split stays clean.

  · 0x00 u32 magic 0x7F0E5359 = Crc32("DRPF") (the bytes on disk are 'YS\\x0e\\x7f'), 0x04 u32 checksum = Crc32 over 0x10..0x94, which doubles as the header's and the footer's crypto seed,
    0x08 u32 content-handling word (Crc32("fhdr") = 0xC65753E8 while clear, anything else encrypted), 0x0C u32 crypto block size (0x90), 0x10 u32 entry count, 0x14 byte[0x80] seed table (plaintext).
  · from 0x94, one chunk per entry: the first u32 is that entry's seed = Crc32 of its DECODED data (so it is also the read's integrity check); the rest is encrypted, a 128-bit XorShift stream
    XORed word by word (u32 little endian), the RNG seeded with the four bytes of the chunk's seed as indexes (& 0x7F) into the archive's seed table. Decrypted, a chunk is u32 'resd'
    (Crc32("resd") = 0xE0A331B4), u32 whole-chunk size, u32 extension (Crc32 of the extension's name), u32 compressed size, u32 decoded size, then the ASCII name up to its zero and, from the
    next word boundary, the payload: a u32 'Oodl' (Crc32("Oodl") = 0xE42D98BA) then the Oodle stream, or the stored bytes when the tag is absent. Chunks are padded to a word; the payload may
    end before the chunk does (New Snap's own compressor leaves the aligned tail). An encrypted footer ends the file, and nothing here needs it.
  · the header's words 0x08..0x14 are encrypted with the archive's checksum as the seed (the magic, checksum and seed table are not), each chunk with its own seed, the footer with the
    header's again. A clear archive (content-handling word Crc32("fhdr")) carries everything in the clear, chunks included, and only its Oodle streams still need the decoder (the UI
    scenes' .drp are clear; the rest of the dump is encrypted).

The Oodle streams are decoded by the decoder the caller passes (oodle(compressed, raw length) → the bytes): the split hands over its own, the game's decoder in the Mac host or the ooz
worker (dm/third_party/ooz's unoodle, DM_OODLE_OOZ), so this file carries no Oodle code of its own. New Pokémon Snap's streams are Kraken and Mermaid, both of which ooz reads.
Usage: the shader split takes entries() (its shaders); no other tool reads the format yet.
PEANUTS(upstream-candidate): a Peanuts Native reader (Banana Native has none for this title)."""
import struct, sys, zlib

MAGIC = 0x7F0E5359           # Crc32("DRPF")
CHECK_MAGIC = 0xC65753E8     # Crc32("fhdr"): a header whose content-handling word is this is not encrypted
CHUNK_MAGIC = 0xE0A331B4     # Crc32("resd"): a file chunk's tag
OODL_MAGIC = 0xE42D98BA      # Crc32("Oodl"): the payload is an Oodle stream
CRYPTO_BLOCK = 0x90          # the header's crypto block size, as every archive seen writes it

# The extension IDs are CRC32s of the extension's name (NewSnap.Lib/DRPF/DrpFileEntry.cs, ISC): an entry whose name carries no '.' takes its extension from here, so its label names the kind
EXTENSIONS = {
    0x1C375F45: 'txt', 0xBE1C9ACB: 'msbt', 0xB9715ED2: 'msbp', 0xBB922BB1: 'lm', 0x5C156DBC: 'nutexb', 0x950C38A5: 'bnk',
    0xCD63FEC8: 'lmb', 0x53076B6B: 'lme', 0x31C91A2C: 'luo', 0xA9B87C9F: 'rtd', 0xA76EEEC0: 'shb', 0x8C43BD03: 'skb',
    0x0FB47EED: 'achd', 0x89E759E1: 'allb', 0xE42AB2FE: 'cbsb', 0xE165A47B: 'cesb', 0xB20291CC: 'cutb', 0x7A390130: 'ecbd',
    0x9B70EEC4: 'facb', 0xF78548DD: 'ldtb', 0xBAF0E4F7: 'lprb', 0xA3D90E1B: 'mdcd', 0x37CD5F6B: 'mdfb', 0xEADAF976: 'mdrp',
    0xE1C38F19: 'misd', 0x6971203F: 'navb', 0xA459AA15: 'nvhb', 0x0B548B0F: 'path', 0x58284CA4: 'pcnb', 0x01AA8159: 'pdcd',
    0x6CD5ECCD: 'pflb', 0xB894D312: 'pfrb', 0x0972120A: 'picd', 0xF423150F: 'pstb', 0xBE2D954D: 'ptsb', 0x753C041E: 'silb',
    0x97948F6D: 'bfotf', 0x87E7C3FC: 'bfttf', 0x2E9EDB6D: 'efxbn', 0x509961FC: 'characterb', 0x623D9D06: 'matinstb',
    0xDAB89279: 'numatb', 0x8E6610FB: 'modelb', 0x0032C3E4: 'nuanmb', 0x81D2341C: 'nuhlpb', 0x236DB83A: 'numshb',
    0x67E93703: 'nusktb', 0x3F86D270: 'nusrcmdlb', 0xC3157ABE: 'courseb', 0x9C5515DE: 'nufxlb', 0x2F6D9B0B: 'nushdb',
    0x7E309203: 'reflectb', 0xE57E2E01: 'paramb', 0x2E641827: 'stfrolb', 0xE9AB884F: 'genderb', 0xF59A44EB: 'levelb',
    0x62BC395A: 'navmshb',
}

M32 = 0xFFFFFFFF

def rng_state(seed, table):
    """The four state words of the 128-bit XorShift RNG seeded for a chunk: the seed's four bytes are indexes (& 0x7F) into the archive's 0x80-byte seed table, and the 32-bit value they
    spell is rolled in one multiplication at a time from _w backwards (XorShift.cs's constructor)"""
    s = 0
    for i in range(4): s |= table[(seed >> (i * 8)) & 0x7F] << (i * 8)
    M = M32; f = 0x41C64E6D
    w0 = (f * s + 12345) & M; w1 = (f * w0 + 12345) & M; w2 = (f * w1 + 12345) & M; w3 = (f * w2 + 12345) & M
    return [w3, w2, w1, w0]   # x, y, z, w

def crypt(w, a, b, st):
    """XOR-decrypts the words a..b of a u32 view in place and leaves the RNG's state in st to go on with (a chunk is decrypted in one stream: header first, then body once the header
    says where the body ends). One word of output is two draws: the first draw's 31 bits and the next draw's top two bits as the sign (GetNext(0xFFFFFFFF) = GetNext() +
    GetNext(1) << 31, the division sampler's draws and outputs written out: a GetNext(1) is one draw, and a 31-bit value / 0x40000000 is its top two bits). The shift is arithmetic on
    32-bit words (the sign fills), subtracting the fill from the logical shift. Inlined into the loop (the state in locals) because a word each is what a whole archive costs"""
    M = M32
    x, y, z, w3 = st
    for i in range(a, b):
        t = (x ^ (x << 11)) & M
        x, y, z = y, z, w3
        w3 = (w3 ^ (w3 >> 19) - (0x2000 if w3 & 0x80000000 else 0) ^ t ^ (t >> 8) - (0x1000000 if t & 0x80000000 else 0)) & M
        r = w3 & 0x7FFFFFFF
        t = (x ^ (x << 11)) & M
        x, y, z = y, z, w3
        w3 = (w3 ^ (w3 >> 19) - (0x2000 if w3 & 0x80000000 else 0) ^ t ^ (t >> 8) - (0x1000000 if t & 0x80000000 else 0)) & M
        w[i] ^= r | ((w3 & 0x7FFFFFFF) >> 30) << 31
    st[:] = [x, y, z, w3]

def entries(d, label, oodle):
    """(name, data) of each entry of a DRPF archive (d, the file read or mapped whole, its size a multiple of 4), in order, one at a time: the header is checked, then each chunk is
    decrypted whole and its payload decoded ('Oodl' by the oodle decoder passed in, stored otherwise) and checked against its own seed (Crc32 of the decoded data). A check that fails
    is red (SystemExit), which the split takes as the archive being unreadable and leaves its shaders out. Each chunk is decrypted in a copy of its own (the archive's bytes are left
    as they are), a few hundred kilobytes at a time however big the file is"""
    def bad(why): sys.exit(f'[RED] {label}: {why}')
    if len(d) & 3 or len(d) < 0x94: bad(f'{len(d):#x} bytes: not whole words, or short of the {0x94:#x} bytes a DRPF header takes')
    if (magic := struct.unpack_from('<I', d, 0)[0]) != MAGIC: bad(f'magic {magic:08x}, not DRPF ({MAGIC:08x})')
    seed = struct.unpack_from('<I', d, 4)[0]
    table = bytes(d[0x14:0x94])
    head = bytearray(d[:0x94])
    enc = struct.unpack_from('<I', head, 8)[0] != CHECK_MAGIC
    if enc:   # encrypted: the content-handling word, the crypto block size and the entry count come clear under the checksum's seed
        crypt(memoryview(head).cast('I'), 2, 5, rng_state(seed, table))
        if struct.unpack_from('<I', head, 8)[0] != CHECK_MAGIC: bad(f'content-handling word {struct.unpack_from("<I", head, 8)[0]:08x} under the checksum\'s seed is not fhdr, and it is not fhdr clear either')
    if struct.unpack_from('<I', head, 0xC)[0] != CRYPTO_BLOCK: bad(f'crypto block size {struct.unpack_from("<I", head, 0xC)[0]:#x}, not {CRYPTO_BLOCK:#x}')
    if zlib.crc32(head[0x10:0x94]) & M32 != seed: bad('the header\'s checksum does not cover its entry count and seed table')
    count = struct.unpack_from('<I', head, 0x10)[0]
    at = 0x94
    for i in range(count):
        if at + 0x20 > len(d): bad(f'entry {i} at {at:#x} has no room for its header (the archive is {len(d):#x} bytes)')
        cseed = struct.unpack_from('<I', d, at)[0]
        c = bytearray(d[at:at + 0x400])   # the chunk header and the name it carries (the body's length is inside the encryption); the body is appended once the header says where it ends
        st = rng_state(cseed, table)
        cv = memoryview(c).cast('I')
        if enc: crypt(cv, 1, 3, st)   # the chunk header's words under the chunk's own seed
        total = struct.unpack_from('<I', c, 8)[0]
        if total & 3 or total < 0x20 or at + total > len(d): bad(f'entry {i} at {at:#x} is {total:#x} bytes: not whole words, or past the archive')
        cv.release()   # the view holds an export, and the chunk grows to its whole length before the body is decrypted
        if total > len(c): c += d[at + len(c):at + total]
        cv = memoryview(c).cast('I')
        if enc: crypt(cv, 3, total >> 2, st)   # the chunk body (the payload is encrypted too), the same stream going on
        if struct.unpack_from('<I', c, 4)[0] != CHUNK_MAGIC: bad(f'entry {i} at {at:#x} is not a resd chunk ({struct.unpack_from("<I", c, 4)[0]:08x})')
        ext, csz, dsz = struct.unpack_from('<3I', c, 0xC)
        nul = c.find(b'\0', 0x18)
        if nul < 0: bad(f'entry {i} at {at:#x}: its name has no end within its first {len(c):#x} bytes')
        name = bytes(c[0x18:nul]).decode('ascii', 'replace')
        if '.' not in name: name += '.' + EXTENSIONS.get(ext, f'{ext:08x}')   # a name without its extension: the kind is the entry's extension ID
        doff = (nul + 4) & ~3
        if ((doff + csz + 3) & ~3) > total: bad(f'{name}: its {csz:#x} bytes at {doff:#x} do not fit its {total:#x}-byte chunk')
        if struct.unpack_from('<I', c, doff)[0] == OODL_MAGIC:
            raw = oodle(bytes(c[doff + 4:doff + csz]), dsz)
            if raw is None or len(raw) != dsz: bad(f'{name}: its Oodle stream does not decode to the {dsz:#x} bytes its header says')
        else:
            if csz != dsz: bad(f'{name}: {csz:#x} bytes stored for {dsz:#x} decoded, and no Oodl tag')
            raw = bytes(c[doff:doff + csz])
        if zlib.crc32(raw) & M32 != cseed: bad(f'{name}: its decoded data hashes {zlib.crc32(raw) & M32:08x}, its chunk seed is {cseed:08x}')
        yield name, raw
        at += total
