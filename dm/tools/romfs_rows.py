#!/usr/bin/env python3
# PEANUTS: new file (a game variant's RomFS data as rows of changed bytes: Animal Crossing: New Horizons' 60 fps speed fix, dm/games/01006F8002326000/speedfix60)
"""A variant's changed game files, kept as rows of changed bytes so that no game file is shipped: the pack step applies them to the player's own RomFS files
and writes the results into the pack's overlay folder for that variant (scripts/pack_android.py variant_overlays; the launcher passes the folder as
DM_ROMFS_OVERLAY when it starts the variant). Every original byte a row replaces is checked, and so are the original file's size and SHA-256 before and the
result's after: a file of another game version is refused whole, never patched into something in between.

The rows file (UTF-8 text, LF):
  # a comment (anywhere)
  file	<RomFS path>	<original size>	<original SHA-256>	<new size>	<new SHA-256>
  <offset>	<original bytes>	<new bytes>        (rows of the file above, ascending, not overlapping)
An offset is in the original file (hex, 0x...); the bytes are hex, '-' for none: a row with no original bytes inserts, one with no new bytes deletes, so a
file may change its size. The rows of a file start at its 'file' line and end at the next one.

Usage:
  romfs_rows.py diff <out rows> <original RomFS> <modded RomFS folder> <RomFS path>...   rows that turn each original file into the modded one (checked by applying them)
  romfs_rows.py apply <rows> <original RomFS> <out folder>                              the modded files from the original ones, into <out folder>/<RomFS path>
  romfs_rows.py check <rows> <original RomFS>                                           whether every file's rows apply (exit 1 when one does not)
<original RomFS> is a RomFS folder, a RomFS image or a .nup's <file>.nup:update/<version>/romfs (dm/tools/romfs.py). Exit 1 on error."""
import hashlib, os, sys
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import romfs   # noqa: E402

GAP = 16   # differing runs this close are one row (fewer rows; the equal bytes between them are checked as original bytes too)


class Error(Exception): pass


@dataclass
class FileRows:
    path: str
    orig_size: int
    orig_sha: str
    new_size: int
    new_sha: str
    rows: list = field(default_factory=list)   # [(offset, original bytes, new bytes)]


def _hex(b): return b.hex() if b else '-'
def _unhex(s, where):
    if s == '-': return b''
    try: return bytes.fromhex(s)
    except ValueError: raise Error(f'{where}: not hex: {s[:40]}') from None


def parse(text, name='rows'):
    """[FileRows] of a rows file's text. Error on anything malformed"""
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        where = f'{name}:{n}'
        if not line.strip() or line.startswith('#'): continue
        c = line.split('\t')
        if c[0] == 'file':
            if len(c) != 6 or not c[1] or c[1].startswith('/') or '..' in c[1].split('/') or not (c[2].isascii() and c[2].isdigit()) or not (c[4].isascii() and c[4].isdigit()) \
                    or len(c[3]) != 64 or len(c[5]) != 64:   # (isascii: '²' is a digit to isdigit, not to int)
                raise Error(f'{where}: a file line takes <RomFS path> <original size> <original SHA-256> <new size> <new SHA-256>')
            out.append(FileRows(c[1], int(c[2]), c[3].lower(), int(c[4]), c[5].lower()))
            continue
        if not out: raise Error(f'{where}: a row before the first file line')
        if len(c) != 3: raise Error(f'{where}: a row takes <offset> <original bytes> <new bytes>')
        try: off = int(c[0], 16)
        except ValueError: raise Error(f'{where}: not an offset: {c[0]}') from None
        if off < 0: raise Error(f'{where}: not an offset: {c[0]}')   # int() takes '-0x10': a slice from the file's end
        ob, nb = _unhex(c[1], where), _unhex(c[2], where)
        f = out[-1]
        if not ob and not nb: raise Error(f'{where}: a row that changes nothing')
        if f.rows and (off < f.rows[-1][0] + len(f.rows[-1][1]) or off == f.rows[-1][0]): raise Error(f'{where}: rows must be ascending and not overlap')
        if off + len(ob) > f.orig_size: raise Error(f'{where}: the row reaches past the original file ({f.orig_size} bytes)')
        f.rows.append((off, ob, nb))
    return out


def load(path):
    try:
        with open(path, encoding='utf-8') as f: text = f.read()
    except UnicodeDecodeError as e: raise Error(f'{os.path.basename(path)}: not UTF-8 text ({e.reason} at byte {e.start})') from None   # an Error: the pack step then lists no variant (pack_android.py variant_overlays)
    return parse(text, os.path.basename(path))


def apply(fr, data):
    """The new file from the original's bytes, or Error when they are not the original the rows were made from (another game version)"""
    if len(data) != fr.orig_size or hashlib.sha256(data).hexdigest() != fr.orig_sha:
        raise Error(f'{fr.path}: not the file the rows were made from ({len(data)} bytes, SHA-256 {hashlib.sha256(data).hexdigest()[:16]}...; '
                    f'the rows want {fr.orig_size} bytes, {fr.orig_sha[:16]}...): another game version')
    parts, at = [], 0
    for off, ob, nb in fr.rows:
        if data[off:off + len(ob)] != ob: raise Error(f'{fr.path}: original bytes differ at {off:#x}')
        parts.append(data[at:off]); parts.append(nb); at = off + len(ob)
    parts.append(data[at:])
    out = b''.join(parts)
    if len(out) != fr.new_size or hashlib.sha256(out).hexdigest() != fr.new_sha: raise Error(f'{fr.path}: the rows did not give the file they were made for')
    return out


def diff_bytes(a, b):
    """Rows (offset, original, new) that turn a into b: changed runs of equal-size files, GAP bytes apart at least; files of different sizes keep their common
    start and end and replace what lies between (one row)"""
    rows = []
    if len(a) == len(b):
        i, n = 0, len(a)
        while i < n:
            if a[i] == b[i]: i += 1; continue
            j = i
            while True:
                while j < n and a[j] != b[j]: j += 1
                k = j
                while k < n and k - j < GAP and a[k] == b[k]: k += 1
                if k < n and k - j < GAP: j = k; continue
                break
            rows.append((i, a[i:j], b[i:j])); i = j
        return rows
    p = 0
    while p < min(len(a), len(b)) and a[p] == b[p]: p += 1
    s = 0
    while s < min(len(a), len(b)) - p and a[len(a) - 1 - s] == b[len(b) - 1 - s]: s += 1
    return [(p, a[p:len(a) - s], b[p:len(b) - s])]


def file_rows(path, a, b):
    fr = FileRows(path, len(a), hashlib.sha256(a).hexdigest(), len(b), hashlib.sha256(b).hexdigest(), diff_bytes(a, b))
    if apply(fr, a) != b: raise Error(f'{path}: the rows do not give the modded file')   # cannot happen; checked all the same
    return fr


def format_rows(frs, header=''):
    out = [header.rstrip('\n')] if header else []
    for fr in frs:
        out.append(f'file\t{fr.path}\t{fr.orig_size}\t{fr.orig_sha}\t{fr.new_size}\t{fr.new_sha}')
        out += [f'{off:#x}\t{_hex(ob)}\t{_hex(nb)}' for off, ob, nb in fr.rows]
    return '\n'.join(out) + '\n'


def read_original(root, path):
    p = str(root).rstrip('/') + '/' + path
    try:
        with romfs.open(p, 'rb') as f: return f.read()
    except OSError as e: raise Error(f'{path}: cannot be read from the game files ({e.strerror or e})') from None


def make(rows_path, root, out):
    """Every file of the rows applied to its original in root, written to out/<RomFS path>: [RomFS path]. All or nothing: a file that does not apply stops
    it before anything is written (Error), and out is emptied first so that a file of an earlier run cannot stay beside new ones"""
    frs = load(rows_path)
    made = [(fr.path, apply(fr, read_original(root, fr.path))) for fr in frs]
    import shutil
    shutil.rmtree(out, ignore_errors=True)
    for path, data in made:
        dst = os.path.join(out, *path.split('/'))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        tmp = dst + '.partial'
        with open(tmp, 'wb') as f: f.write(data)
        os.replace(tmp, dst)
    return [p for p, _ in made]


def main(argv):
    try:
        if len(argv) >= 6 and argv[1] == 'diff':
            out, root, mod, paths = argv[2], argv[3], argv[4], argv[5:]
            frs = []
            for p in paths:
                with open(os.path.join(mod, *p.split('/')), 'rb') as f: b = f.read()
                frs.append(file_rows(p, read_original(root, p), b))
                print(f'{p}: {len(frs[-1].rows)} rows, {sum(len(nb) for _, _, nb in frs[-1].rows)} new bytes')
            with open(out, 'w', encoding='utf-8', newline='\n') as f: f.write(format_rows(frs))
        elif len(argv) == 5 and argv[1] == 'apply':
            for p in make(argv[2], argv[3], argv[4]): print(f'{p}: made')
        elif len(argv) == 4 and argv[1] == 'check':
            for fr in load(argv[2]): apply(fr, read_original(argv[3], fr.path)); print(f'{fr.path}: applies')
        else:
            sys.exit(__doc__)
    except Error as e:
        sys.exit(f'[RED] {e}')


if __name__ == '__main__':
    main(sys.argv)
