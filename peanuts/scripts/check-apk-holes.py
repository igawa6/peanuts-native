#!/usr/bin/env python3
"""Every byte of an APK (any zip) accounted for: prints the bytes that belong to no entry of its central directory, and exits 1 when there are any beyond
what alignment leaves. A release check: an APK packaged over an earlier output file kept that file's old entries as dead bytes between the live ones
(a 45 MB app came out as 59 MB; nothing reads those bytes, every phone downloads and stores them).
    check-apk-holes.py <apk>
Counted for: each entry's local header, name, extra field (where zipalign puts its padding), data and data descriptor; the APK Signing Block before the
central directory; the central directory and the end records. A gap of fewer than 16 KiB that holds no local header is taken for padding and only
reported; a larger one, one that holds a local file header (an entry the directory no longer names), or more than 64 KiB of gaps together fails."""
import struct, sys

PAD_MAX, TOTAL_MAX = 16384, 65536
SIG_MAGIC = b'APK Sig Block 42'


class Bad(Exception): pass


def holes(data):
    """-> (gaps [(offset, length, holds a local header)], signing block bytes, entries) of a zip's bytes"""
    e = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    if e < 0: raise Bad('no end of central directory record: not a zip')
    n, cd_size, cd_off = struct.unpack_from('<HII', data, e + 10)
    if cd_off == 0xFFFFFFFF or n == 0xFFFF:   # zip64: the locator before the end record names the zip64 end record
        if data[e - 20:e - 16] != b'PK\x06\x07': raise Bad('zip64 sizes without a zip64 locator')
        z = struct.unpack_from('<Q', data, e - 12)[0]
        if data[z:z + 4] != b'PK\x06\x06': raise Bad('no zip64 end of central directory record')
        n, cd_size, cd_off = struct.unpack_from('<QQQ', data, z + 32)
    spans, p = [], cd_off
    for _ in range(n):
        if data[p:p + 4] != b'PK\x01\x02': raise Bad(f'central directory entry at {p}: bad signature')
        flags, = struct.unpack_from('<H', data, p + 8)
        csize, usize, nl, xl, cl = struct.unpack_from('<IIHHH', data, p + 20)
        off, = struct.unpack_from('<I', data, p + 42)
        name = data[p + 46:p + 46 + nl].decode('utf-8', 'replace')
        x = data[p + 46 + nl:p + 46 + nl + xl]; q = 0
        while q + 4 <= len(x):   # zip64 extra: the sizes and the offset that did not fit
            tag, ln = struct.unpack_from('<HH', x, q)
            if tag == 1:
                v = list(struct.unpack_from('<' + 'Q' * (ln // 8), x, q + 4))
                if usize == 0xFFFFFFFF and v: usize = v.pop(0)
                if csize == 0xFFFFFFFF and v: csize = v.pop(0)
                if off == 0xFFFFFFFF and v: off = v.pop(0)
            q += 4 + ln
        if data[off:off + 4] != b'PK\x03\x04': raise Bad(f'{name}: no local header at {off}')
        lnl, lxl = struct.unpack_from('<HH', data, off + 26)
        end = off + 30 + lnl + lxl + csize
        if flags & 8:   # a data descriptor follows: with or without its signature, 32-bit sizes (64-bit in a zip64 entry are not written by APK tools)
            end += 16 if data[end:end + 4] == b'PK\x07\x08' else 12
        spans.append((off, end, name))
        p += 46 + nl + xl + cl
    spans.sort()
    sig = 0
    if cd_off >= 32 and data[cd_off - 16:cd_off] == SIG_MAGIC:
        size, = struct.unpack_from('<Q', data, cd_off - 24)
        if size + 8 <= cd_off and struct.unpack_from('<Q', data, cd_off - size - 8)[0] == size: sig = size + 8
    gaps, at = [], 0
    for off, end, name in spans + [(cd_off - sig, cd_off, '')]:
        if off > at: gaps.append((at, off - at, b'PK\x03\x04' in data[at:off]))
        elif off < at and name: raise Bad(f'{name}: overlaps the entry before it')
        at = max(at, end)
    return gaps, sig, len(spans)


def main(argv):
    if len(argv) != 2: sys.exit(__doc__)
    data = open(argv[1], 'rb').read()
    try: gaps, sig, n = holes(data)
    except (Bad, struct.error, IndexError) as e: print(f'[RED] {argv[1]}: {e}'); return 1
    total = sum(g[1] for g in gaps)
    bad = [g for g in gaps if g[1] >= PAD_MAX or g[2]]
    for off, ln, dead in sorted(gaps, key=lambda g: -g[1])[:12]:
        print(f'   {ln:>10} bytes at {off}' + (': holds a local file header (an entry the central directory does not name)' if dead else ''))
    if bad or total > TOTAL_MAX:
        print(f'[RED] {argv[1]}: {total:,} of {len(data):,} bytes belong to no entry ({len(gaps)} gaps, {len(bad)} of them not padding): '
              'package it again from a clean step (remove the old output APK first)')
        return 1
    print(f'no holes: {n} entries, {len(data):,} bytes' + (f', signing block {sig:,}' if sig else '') + (f', {total:,} bytes of padding in {len(gaps)} gaps' if gaps else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
