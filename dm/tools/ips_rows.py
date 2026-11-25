#!/usr/bin/env python3
# PEANUTS: new file (a third-party IPS code patch turned into a variant's cut rows: Animal Crossing: New Horizons' 60 fps speed fix, dm/games/01006F8002326000/speedfix60)
"""Turns an Atmosphère exefs patch (IPS or IPS32 named by the module's build ID, offsets counting the NSO's 0x100-byte header: ips.py) into rows of the
rewriter's cut form, with each record's original bytes read from the module it was made for: <image offset> <original bytes> <new bytes>, hex, one row per
record, in the patch's order. A profile's patches.py reads them into VARIANT_CUTS (dm/tools/patch_nso.py), which asserts every original before it writes the
new bytes, so the patch applies to that one build of the module and to nothing else. An emulator .pchtxt of the same patch may be given for its comments:
each row group then carries the comment above it (the rows must be the same bytes as the IPS: checked).

Usage: ips_rows.py <patch.ips> <module NSO> <out rows> [--pchtxt <file>]   (exit 1 on error)
A record that lies in the NSO header, outside the module's image or over another record is refused."""
import os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ips   # noqa: E402
import nso   # noqa: E402


class Error(Exception): pass


def pchtxt_comments(text):
    """{patch offset (as in the IPS: header counted): the comment line above its group} of a .pchtxt's enabled rows, and {offset: new bytes} of them"""
    shift, comment, on, notes, data = 0, '', False, {}, {}
    for line in text.splitlines():
        m = re.match(r'@flag offset_shift (0x[0-9a-fA-F]+)', line)
        if m: shift = int(m.group(1), 16); continue
        if line.startswith('//'): comment = line[2:].strip(); continue
        if line.startswith('@enabled'): on = True; continue
        if line.startswith('@disabled'): on = False; continue
        if line.startswith('@stop'): break
        m = re.match(r'^([0-9A-Fa-f]{8}) ([0-9A-Fa-f]+)\s*$', line)
        if m and on:
            o = int(m.group(1), 16) + shift
            notes[o], data[o] = comment, bytes.fromhex(m.group(2))
    return notes, data


def rows(patch, image, notes=None):
    """[(image offset, original bytes, new bytes, comment)] of the patch's records against the module image (nso.py Module.image)"""
    out, seen = [], []
    for r in ips.parse(patch):
        at = r.offset - ips.NSO_HEADER
        if at < 0: raise Error(f'record at {r.offset:#x} lies in the NSO header')
        if at + len(r.data) > len(image): raise Error(f'record at {r.offset:#x} reaches past the module image ({len(image):#x} bytes)')
        if any(at < e and s < at + len(r.data) for s, e in seen): raise Error(f'record at {r.offset:#x} overlaps another')
        seen.append((at, at + len(r.data)))
        out.append((at, bytes(image[at:at + len(r.data)]), r.data, (notes or {}).get(r.offset, '')))
    return out


def format_rows(rs, header=''):
    out, last = [header.rstrip('\n')] if header else [], None
    for at, ob, nb, note in rs:
        if note and note != last: out.append(f'# {note}'); last = note
        out.append(f'{at:#x}\t{ob.hex()}\t{nb.hex()}')
    return '\n'.join(out) + '\n'


def main(argv):
    a = list(argv[1:])
    pch = None
    if '--pchtxt' in a:
        i = a.index('--pchtxt')
        if i + 1 >= len(a): sys.exit(__doc__)   # --pchtxt without its file
        pch = a[i + 1]; del a[i:i + 2]
    if len(a) != 3: sys.exit(__doc__)
    src, mod_path, out = a
    with open(src, 'rb') as f: patch = f.read()
    mod = nso.cli_read(mod_path)
    notes = None
    try:
        if pch:
            with open(pch, encoding='utf-8') as f: notes, data = pchtxt_comments(f.read())
            if {r.offset: r.data for r in ips.parse(patch)} != data: raise Error(f'{pch}: its rows are not the bytes of {src}')
        rs = rows(patch, mod.image, notes)
    except (Error, ips.Error) as e: sys.exit(f'[RED] {e}')
    with open(out, 'w', encoding='utf-8', newline='\n') as f: f.write(format_rows(rs))
    print(f'{len(rs)} rows, {sum(len(r[2]) for r in rs)} bytes, build {mod.build_id[:16].hex()}: {out}')


if __name__ == '__main__':
    main(sys.argv)
