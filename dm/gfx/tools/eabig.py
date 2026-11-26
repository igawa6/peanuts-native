# PEANUTS: EA's BIG4 archives, EASF secure files and chunk-compressed files, for shader_split.py (FIFA 18's shaders; docs/reference/shaders.md "NXSHADER effects in EA
# BIG4 archives behind EASF and chunkzip"). Whole file Peanuts' own.
#
# BIG4 (big-endian): 'BIG4', u32 archive size (little-endian, not checked), u32 entry count, u32 header length; then per entry u32 offset, u32 size and a NUL-terminated name.
# Its members are often chunk-compressed: an 8-byte magic ('chunkzip' raw deflate, 'chunzstd' zstd; EA's other codecs, chunlzma and chunkref, are named but not read),
# u32 version 2, u32 decoded size, u32 block size, u32 block count, u32 alignment, 12 bytes of zeros, then each block as u32 stored size, u32 kind (0 stored, else compressed)
# and its bytes, the next block's header placed so that its bytes start on the alignment.
# EASF ("EA secure file", AssetStream's loader, assetstream.cpp): 'EASF', u32 plaintext size, an 8-byte key name ("datax   "), the SHA-256 of the plaintext's first
# min(size, 32) bytes, and at +0x30 the plaintext AES-128-CBC encrypted with the key as its IV. The game's AssetStream::InitializeParameters.getCryptoKey callback gives the
# key for a name; FIFA 18 (Fifa::AssetStreamCryptoKeyProvider::GetKey) compares the 8-byte name with a 64-bit literal and copies the key from main's read-only data:
#     mov xT, #<name, top 16 bits>, lsl #48; movk ... (the name built in a register) ... cmp xN, xT; b.eq L
#  L: adrp x8, <page>; add x8, x8, #<offset>; ldp x9, x8, [x8, #<8n>]      the 16 key bytes at page + offset + 8n
# find_key follows that pattern for the name in the EASF header and keeps the candidate whose decryption matches the header's hash; nothing about the key is stored here.
import hashlib, re, struct, zlib

EASF = b'EASF'
CHUNK = (b'chunkzip', b'chunzstd', b'chunlzma', b'chunkref')
BIG4 = b'BIG4'


class Error(ValueError): pass


def big4_entries(head, size=None):
    """[(name, offset, size)] of a BIG4 archive whose header (at least its header length) is head; size = the archive's length, to check the entries against"""
    if head[:4] != BIG4 or len(head) < 16: raise Error('not a BIG4 archive')
    n, hlen = struct.unpack_from('>II', head, 8)
    if hlen > len(head) or n > (hlen - 16) // 9 + 1: raise Error(f'BIG4 header of {hlen} bytes with {n} entries does not fit')
    out, pos = [], 16
    for _ in range(n):
        if pos + 9 > hlen: raise Error('BIG4 entry table runs past the header')
        off, sz = struct.unpack_from('>II', head, pos); pos += 8
        e = head.find(b'\0', pos, hlen)
        if e < 0: raise Error('BIG4 entry name runs past the header')
        name = head[pos:e].decode('latin-1'); pos = e + 1
        if size is not None and off + sz > size: raise Error(f'BIG4 entry {name} ({off:#x}+{sz:#x}) runs past the archive ({size:#x})')
        out.append((name, off, sz))
    return out


def big4_header_len(head16):
    return struct.unpack_from('>I', head16, 12)[0]


def unchunk(d, label='chunk'):
    """The decoded bytes of a chunk-compressed file"""
    magic = d[:8]
    if magic not in CHUNK: raise Error(f'{label}: not a chunk-compressed file')
    if magic not in (b'chunkzip', b'chunzstd'): raise Error(f'{label}: {magic.decode()} is not read')
    ver, size, bs, nb, al = struct.unpack_from('>IIIII', d, 8)
    if ver != 2 or not al or al & (al - 1) or not bs or nb != (size + bs - 1) // bs: raise Error(f'{label}: chunk header (version {ver}, {size} bytes, block {bs}, {nb} blocks, align {al}) not read')
    if magic == b'chunzstd':
        try: from compression import zstd
        except ImportError: import backports.zstd as zstd
    out, pos = bytearray(), 0x28
    for i in range(nb):
        if pos + 8 > len(d): raise Error(f'{label}: block {i} of {nb} runs past the file')
        n, kind = struct.unpack_from('>II', d, pos); start = pos + 8
        blk = d[start:start + n]
        if len(blk) != n: raise Error(f'{label}: block {i} of {nb} runs past the file')
        want = min(bs, size - len(out))
        if not kind: got = blk
        elif magic == b'chunkzip':
            z = zlib.decompressobj(-15); got = z.decompress(blk, want + 1)
        else: got = zstd.decompress(blk)
        if len(got) != want: raise Error(f'{label}: block {i} decodes to {len(got)} bytes, not {want}')
        out += got
        pos = (start + n + 8 + al - 1) // al * al - 8
    return bytes(out)


# ---- the key: a 64-bit literal compare on the key name, then adrp / add / ldp of the key ----
def _sx(v, bits): return v - (1 << bits) if v >> (bits - 1) & 1 else v


def _logic_imm(n, immr, imms):   # DecodeBitMasks for a 64-bit ORR (immediate); None when reserved
    l = (n << 6 | (~imms & 0x3f)).bit_length() - 1
    if l < 1: return None
    size = 1 << l; s, r = imms & (size - 1), immr & (size - 1)
    if s == size - 1: return None
    e = (1 << (s + 1)) - 1
    e = ((e >> r) | (e << (size - r))) & ((1 << size) - 1)
    v = 0
    for i in range(64 // size): v |= e << (i * size)
    return v


def _mov(w, regs):   # MOVZ / MOVN / MOVK / ORR-immediate from xzr into a 64-bit register; True when w was one
    rd = w & 31
    if w & 0xFF800000 in (0xD2800000, 0x92800000, 0xF2800000):
        hw, imm = (w >> 21) & 3, (w >> 5) & 0xFFFF
        if w & 0xFF800000 == 0xD2800000: regs[rd] = imm << (16 * hw)
        elif w & 0xFF800000 == 0x92800000: regs[rd] = ~(imm << (16 * hw)) & (2**64 - 1)
        elif rd in regs: regs[rd] = regs[rd] & ~(0xFFFF << (16 * hw)) | imm << (16 * hw)
        return True
    if w & 0xFF8003E0 == 0xB20003E0:   # orr xd, xzr, #imm
        v = _logic_imm((w >> 22) & 1, (w >> 16) & 0x3F, (w >> 10) & 0x3F)
        if v is not None: regs[rd] = v; return True
    return False


def _key_addr(img, at):
    """The address the ldp / ldr q at or after at (within 6 instructions) reads the key from, through adrp and add; None when the code there is not that"""
    regs = {}
    for pc in range(at, min(at + 24, len(img) - 3), 4):
        w = struct.unpack_from('<I', img, pc)[0]
        if w & 0x9F000000 == 0x90000000:   # adrp
            imm = _sx(((w >> 5) & 0x7FFFF) << 2 | (w >> 29) & 3, 21) << 12
            regs[w & 31] = (pc & ~0xFFF) + imm
        elif w & 0xFF800000 in (0x91000000, 0x91400000):   # add xd, xn, #imm{, lsl 12}
            rn = (w >> 5) & 31
            if rn not in regs: return None
            regs[w & 31] = regs[rn] + (((w >> 10) & 0xFFF) << (12 if w & 0x00400000 else 0))
        elif w & 0xFFC00000 == 0xA9400000:   # ldp xt1, xt2, [xn, #imm]
            rn = (w >> 5) & 31
            return regs[rn] + _sx((w >> 15) & 0x7F, 7) * 8 if rn in regs else None
        elif w & 0xFFC00000 == 0x3DC00000:   # ldr qt, [xn, #imm]
            rn = (w >> 5) & 31
            return regs[rn] + ((w >> 10) & 0xFFF) * 16 if rn in regs else None
        else: return None
    return None


def key_candidates(img, name):
    """16-byte keys main's code copies when an 8-byte key name equals name (the getCryptoKey pattern above), in code order, without repeats"""
    if len(name) != 8: return []
    want = int.from_bytes(name, 'little'); lo = want & 0xFFFF
    seen, out = set(), []
    for m in _movs_of(img, lo):   # each instruction that writes the name's low 16 bits; the 8 before it may write the rest
        regs = {}
        for pc in range(max(0, m - 32), m + 4, 4): _mov(struct.unpack_from('<I', img, pc)[0], regs)
        if want not in regs.values(): continue
        for pc in range(m + 4, min(m + 36, len(img) - 3), 4):   # the b.eq after the compare
            w = struct.unpack_from('<I', img, pc)[0]
            if w & 0xFF00001F == 0x54000000:
                addr = _key_addr(img, pc + _sx((w >> 5) & 0x7FFFF, 19) * 4)
                if addr is not None and 0 <= addr <= len(img) - 16 and (k := bytes(img[addr:addr + 16])) not in seen: seen.add(k); out.append(k)
                break
    return out   # candidates only: key_for keeps the one whose decryption matches the file's hash


def _movs_of(text, lo):
    """Offsets of the movz / movk (64-bit, hw 0) instructions with immediate lo, any register"""
    w0 = 0xD2800000 | lo << 5; w1 = 0xF2800000 | lo << 5
    pats = [re.escape(struct.pack('<I', w)[1:]) for w in (w0, w1)]
    lowbase = (lo << 5) & 0xE0
    rx = re.compile(b'[' + b''.join(re.escape(bytes([lowbase + r])) for r in range(32)) + b'](?:' + b'|'.join(pats) + b')', re.S)
    for m in rx.finditer(text):
        if m.start() % 4 == 0: yield m.start()


def _cbc_decrypt(key, data):
    if len(data) % 16: raise Error(f'EASF body of {len(data)} bytes is not whole blocks')
    import uepak   # AES ECB: CommonCrypto on the Mac, _nxaes on the phone (uepak.aes_decrypt); shader_split.py puts this folder on the path
    ecb = uepak.aes_decrypt(key, data)
    prev = key + data[:-16]
    return (int.from_bytes(ecb, 'big') ^ int.from_bytes(prev, 'big')).to_bytes(len(data), 'big')


def easf_parts(d):
    """(plaintext size, key name, hash of the first min(size, 32) plaintext bytes, encrypted body) of an EASF file"""
    if d[:4] != EASF or len(d) < 0x30: raise Error('not an EASF file')
    size = struct.unpack_from('>I', d, 4)[0]
    body = d[0x30:0x30 + (size + 15) // 16 * 16]
    if len(body) < size: raise Error(f'EASF body of {len(body)} bytes is shorter than its {size}-byte plaintext')
    return size, bytes(d[8:16]), bytes(d[16:0x30]), body


_KEYS = {}   # key name → key (or None: no key in main for it), per process


def key_for(img_loader, d):
    """The key of EASF file d, from main's code (img_loader() gives main's image, read once); None when main has no key for its name that matches its hash"""
    size, name, digest, body = easf_parts(d)
    if name not in _KEYS:
        _KEYS[name] = None
        n = min(size, 32); first = body[:(n + 15) // 16 * 16]
        for k in key_candidates(img_loader(), name):
            if hashlib.sha256(_cbc_decrypt(k, first)[:n]).digest() == digest: _KEYS[name] = k; break
    return _KEYS[name]


def easf_decrypt(key, d):
    size, name, digest, body = easf_parts(d)
    p = _cbc_decrypt(key, body)[:size]
    if hashlib.sha256(p[:32]).digest() != digest: raise Error(f'EASF ({name.decode("latin-1").rstrip()}): the key does not match the file')
    return p
