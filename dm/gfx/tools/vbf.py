"""Virtuos SRYK archives (FFX/X-2 Switch): two MD5 tables, 32-byte file records, names and 64 KB zlib, LZ4 or raw blocks (PEANUTS(upstream-candidate): LZ4).
Read members through romfs.open so archives inside a RomFS image need no extraction. Missing/shared entries have offset UINT64_MAX.
"""
import struct
import zlib
from romfs import open


def entries(path):
    with open(path, 'rb') as f:
        head = f.read(16)
        if len(head) != 16 or head[:4] != b'SRYK': raise ValueError('not a VBF archive')
        hlen, count = struct.unpack_from('<IQ', head, 4)
        if not 16 + count * 64 + 4 <= hlen <= 256 << 20: raise ValueError('invalid VBF header length')
        head += f.read(hlen - 16)
    if len(head) != hlen: raise ValueError('truncated VBF header')
    strings = 16 + count * 64
    slen = struct.unpack_from('<I', head, strings)[0]
    blocks = strings + slen
    if slen < 4 or blocks > hlen: raise ValueError('invalid VBF string table')
    out = []
    for i in range(count):
        bi, _, size, offset, name = struct.unpack_from('<IIQQQ', head, 16 + count * 32 + i * 32)
        if offset == 0xffffffffffffffff: continue
        start = strings + 4 + name
        if not strings + 4 <= start < blocks: raise ValueError('invalid VBF name offset')
        end = head.find(b'\0', start, blocks)
        if end < 0: raise ValueError('unterminated VBF name')
        n = (size + 65535) // 65536
        if blocks + (bi + n) * 2 > hlen or offset < hlen: raise ValueError('invalid VBF block range')
        lengths = struct.unpack_from(f'<{n}H', head, blocks + bi * 2)
        out.append((head[start:end].decode('utf-8'), size, offset, lengths))
    return out


# PEANUTS(upstream-candidate): FFXII The Zodiac Age's compressed blocks are raw LZ4 blocks, not zlib. A block is LZ4 when it parses as one down to its last byte and yields exactly its
# uncompressed size, which a zlib stream does not in practice; this reads the format itself (sequences of a token, literals, a 2-byte offset and a match; the last sequence has no match, and
# the end rules of the LZ4 block format hold) and answers None for anything that breaks a rule, so no exception chooses the codec.
def lz4_block(src, n):
    out, i, end = bytearray(), 0, len(src)
    while True:
        if i >= end: return None   # a block ends with a sequence that has literals only
        token = src[i]; i += 1
        lit = token >> 4
        if lit == 15:
            while True:
                if i >= end: return None
                lit += src[i]; i += 1
                if src[i - 1] != 255: break
        if i + lit > end or len(out) + lit > n: return None
        out += src[i:i + lit]; i += lit
        if i == end: return bytes(out) if len(out) == n else None
        if len(out) > n - 12 or i + 2 > end: return None   # a sequence with a match starts at least 12 bytes before the end
        offset = src[i] | src[i + 1] << 8; i += 2
        if not 0 < offset <= len(out): return None
        size = token & 15
        if size == 15:
            while True:
                if i >= end: return None
                size += src[i]; i += 1
                if src[i - 1] != 255: break
        size += 4
        if len(out) + size > n - 5: return None   # the last 5 bytes are literals
        start = len(out) - offset
        out += out[start:start + size] if offset >= size else (out[start:] * (size // offset + 1))[:size]


# PEANUTS(upstream-candidate): the zlib stream header of RFC 1950, which every zlib stream starts with: CMF is deflate with a window of at most 32 KB, FLG has no preset dictionary,
# and the two bytes read as a number are a multiple of 31. A block that is neither LZ4 nor has it is not a block of either codec.
def zlib_header(d):
    return len(d) >= 2 and d[0] & 15 == 8 and d[0] >> 4 < 8 and not d[1] & 32 and (d[0] << 8 | d[1]) % 31 == 0


def read(f, entry):
    name, size, offset, lengths = entry
    f.seek(offset); chunks = []
    for length in lengths:
        n = min(65536, size); length = length or 65536
        data = f.read(length)
        if len(data) != length: raise ValueError(f'{name}: truncated VBF block')
        if length != n:
            block = lz4_block(data, n)   # PEANUTS(upstream-candidate): FFXII The Zodiac Age's blocks are LZ4
            if block is not None: data = block
            elif zlib_header(data):
                dec = zlib.decompressobj()
                data = dec.decompress(data, n + 1)
                if not dec.eof or dec.unconsumed_tail: raise ValueError(f'{name}: invalid VBF compressed block')
            else: raise ValueError(f'{name}: invalid VBF compressed block')
        if len(data) != n: raise ValueError(f'{name}: VBF block size mismatch')
        chunks.append(data); size -= n
    return b''.join(chunks)
