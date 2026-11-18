#!/usr/bin/env python3
# Puts a game's dump folder in the console's layout, the one scripts/pack_dump.py describes: one folder for each content meta record (base/, updates/<version>/,
# dlc/<DLC title ID>/<version>/base/ and .../updates/<patch version>/), each content at its program index (program/<index>/exefs, romfs, control, meta). OunceCake
# serves a game this way; a flattened dump keeps program 0's folders in the version folder (base/exefs/), another program as program<index>/, and an add-on's
# RomFS as dlc/<DLC title ID>/<version>/romfs/; Yoink keeps a Switch 2 Edition's program 1 at switch2/ and an add-on's data patch as a folder of its own, and some
# tools name dlc/ addons/ or leave a one-version game in the dump folder itself (plan lists them all). The files are moved, not copied (the same drive: a rename each), and every move is written to
# <dump folder>/.layout-moves.tsv first, so --undo puts each one back. The run checks that pack_dump.py reads the same game before and after, and undoes its moves
# when it does not. Without --apply it only lists the moves.
# NSP-Box and nxnative read only the flattened layout: --undo gives a folder they take back.
# Usage: dump_layout.py <dump folder> [--apply | --undo]   (exit 1 on error)
import argparse, re, sys
from pathlib import Path
import pack_dump

SECTIONS = ('exefs', 'romfs', 'control', 'meta', 'logo')   # a content's folders as OunceCake names them (its program content: exefs, romfs, logo; meta; control)
LOG = '.layout-moves.tsv'


class Error(Exception): pass


def content(p): return (p / 'exefs').is_dir() or (p / 'romfs').is_dir()   # a content folder of its own (github.com/a55uka/nup tree.is_content_dir)


def other_program(p):   # program<N>/ (N >= 1, leading zeros allowed) beside a flattened version's exefs/ and romfs/: another program's content, N, else None
    m = re.fullmatch(r'program0*([1-9]\d*)', p.name)
    return int(m.group(1)) if m and p.is_dir() and content(p) else None


def plan(dump):
    """The moves that put a dump in the console's layout, (from, to) relative to the dump folder; none for a folder already in it. It takes the layouts the dump
    tools write (as github.com/a55uka/nup discover.py reads them): flattened version folders (base/exefs/, updates/<version>/romfs/, another program at
    program<N>/ beside them), a one-version folder (exefs/, romfs/ and program<N>/ in the dump folder itself: the base), Yoink's (a Switch 2 Edition's program 1
    at switch2/base/ and switch2/updates/<version>/; an add-on's data patch filed as dlc/<data patch ID>/<version>/), add-ons under dlc/ or addons/, and an
    add-on's RomFS at dlc/<DLC title ID>/<version>/romfs/ or dlc/<DLC title ID>/romfs/ (version 0)"""
    moves = []
    def version(v, to):   # a game version folder (base/ or updates/<version>/) → its folder `to`: program 0's folders, and program<N>/ folders of other programs
        moves.extend((v / s, to / 'program/0' / s) for s in SECTIONS if (v / s).is_dir())
        for p in sorted(v.iterdir()):
            if (n := other_program(p)) is not None: moves.append((p, to / 'program' / str(n)))
    if content(dump) and not (dump / 'base').exists(): version(dump, dump / 'base')   # a one-version folder: the base
    if (dump / 'base').is_dir(): version(dump / 'base', dump / 'base')
    numbered = lambda p: sorted(c for c in p.iterdir() if c.is_dir() and c.name.isdigit()) if p.is_dir() else []
    for u in numbered(dump / 'updates'): version(u, u)
    # Yoink: program 1 apart, flattened
    for src, to in [(dump / 'switch2/base', dump / 'base'), *((u, dump / 'updates' / u.name) for u in numbered(dump / 'switch2/updates'))]:
        moves.extend((src / s, to / 'program/1' / s) for s in SECTIONS if (src / s).is_dir())
    folders = [p for r in ('dlc', 'addons') if (dump / r).is_dir() for p in sorted((dump / r).iterdir()) if p.is_dir()]
    def tid(d):
        try: return int(d.name, 16)
        except ValueError: raise Error(f'{d}: an add-on folder is named by its title ID (dlc/<DLC title ID>/)') from None
    def versions(d):   # an add-on folder's flattened versions: (its version folder, the version's name); its own sections are version 0
        out = [(d, '0')] if content(d) else []
        for n in sorted(p for p in d.iterdir() if p.is_dir() and p.name not in SECTIONS):
            if not n.name.isdigit():
                if content(n): raise Error(f'{n}: an add-on version folder is named by its version number (dlc/<DLC title ID>/<version>/)')
                continue
            if content(n): out.append((n, n.name))
        return out
    for d in folders:
        t = tid(d)
        try: addon = pack_dump.data_patch_of(d, t)
        except pack_dump.Error as e: raise Error(str(e)) from None
        if addon is None:   # an add-on: each version at <version>/base/program/0/, under dlc/
            moves.extend((n / s, dump / 'dlc' / d.name / v / 'base/program/0' / s) for n, v in versions(d) for s in SECTIONS if (n / s).is_dir())
            continue
        # Yoink's data patch: under its add-on's folder (its highest version, else 0), as updates/<patch version>/program/0/
        own = next((f for f in folders if f is not d and tid(f) == addon), None)
        name = own.name if own else f'{addon:016x}'
        av = max((v for _, v in versions(own)), key=int, default='0') if own else '0'
        if own and not versions(own): av = max((p.name for p in own.iterdir() if p.is_dir() and p.name.isdigit()), key=int, default='0')   # already in the console's layout
        moves.extend((n / s, dump / 'dlc' / name / av / 'updates' / v / 'program/0' / s) for n, v in versions(d) for s in SECTIONS if (n / s).is_dir())
    rel = [(a.relative_to(dump), b.relative_to(dump)) for a, b in moves]
    clash = [b for a, b in moves if b.exists()] + [b for i, (_, b) in enumerate(rel) if b in [t for _, t in rel[:i]]]
    if clash: raise Error('these folders would be overwritten, the dump has both layouts there: sort them out by hand\n' + '\n'.join(f'  {c}' for c in clash))
    return rel


def read(dump):
    """What pack_dump.py finds in the dump, for the check: the title ID; each version's main.npdm and RomFS (what its top holds); each add-on's RomFS (what its
    top holds). The paths are left out: they are what changes"""
    app, vers, dlc = pack_dump.scan(dump)
    top = lambda d: sorted(p.name for p in d.iterdir()) if d.is_dir() else None
    def npdm(v):
        f = pack_dump.version_part(dump, vers, v, 'exefs') / 'main.npdm'
        return f.read_bytes() if f.is_file() else None
    p1 = pack_dump.program_dir(dump, 1)   # a Switch 2 Edition's program 1: its version, main.npdm and RomFS
    return app, {v: (npdm(v), top(pack_dump.version_part(dump, vers, v, 'romfs'))) for v in [0, *vers]}, {t: top(r) for t, r in dlc.items()}, \
        p1 and (p1[0], (p1[1] / 'exefs/main.npdm').read_bytes(), top(p1[1] / 'romfs'))


def tidy(dump, p):   # removes the empty folders a move left, up to the dump folder
    while p != dump and p.is_dir() and not any(p.iterdir()):
        p.rmdir(); p = p.parent


def undo(dump, say=print):
    log = dump / LOG
    if not log.is_file(): raise Error(f'{dump}: no {LOG}: nothing to undo')
    rows = [l.split('\t') for l in log.read_text(encoding='utf-8').splitlines() if l and not l.startswith('#')]
    for a, b in reversed(rows):
        src, dst = dump / a, dump / b
        if not dst.exists():
            if src.exists(): continue   # not moved (a run stopped before it)
            raise Error(f'{dst}: missing, cannot move it back to {src}')
        if src.exists(): raise Error(f'{src}: exists, cannot move {dst} back there')
        src.parent.mkdir(parents=True, exist_ok=True); dst.rename(src); tidy(dump, dst.parent)
        say(f'  {b} -> {a}')
    log.unlink()


def apply(dump, moves, say=print):
    try: before = read(dump)
    except pack_dump.Error:   # a one-version folder (exefs/ and romfs/ in the dump folder) is not read before the moves: after them it must be
        if not content(dump): raise
        before = None
    with open(dump / LOG, 'a', encoding='utf-8') as log:
        if log.tell() == 0: log.write('# moves of scripts/dump_layout.py: from <TAB> to, relative to this folder (dump_layout.py --undo puts them back)\n')
        for a, b in moves:
            log.write(f'{a.as_posix()}\t{b.as_posix()}\n'); log.flush()   # before the move: an interrupted run is undone all the same
            (dump / b).parent.mkdir(parents=True, exist_ok=True); (dump / a).rename(dump / b); tidy(dump, (dump / a).parent)
            say(f'  {a.as_posix()} -> {b.as_posix()}')
    try: after = read(dump)
    except pack_dump.Error as e: after = e
    if after != before and not (before is None and not isinstance(after, Exception)):
        say('the dump does not read the same after the moves; undoing them')
        undo(dump, say)
        raise Error(f'{dump}: read differently in the console layout ({after}); left as it was')


def main(argv=None):
    ap = argparse.ArgumentParser(description='Put a dump folder in the console\'s layout (base/program/0/..., updates/<v>/program/<i>/..., dlc/<id>/<v>/base/program/0/...)')
    ap.add_argument('dump', type=Path)
    g = ap.add_mutually_exclusive_group()
    g.add_argument('--apply', action='store_true', help='make the moves (without it they are only listed)')
    g.add_argument('--undo', action='store_true', help=f'put back every move in <dump>/{LOG}')
    a = ap.parse_args(argv)
    dump = a.dump.resolve()
    try:
        if not dump.is_dir(): raise Error(f'{dump}: not a folder (a .nup file has no folder layout)')
        if a.undo: undo(dump); print('done: moved back'); return 0
        moves = plan(dump)
        if not moves: print(f'{dump}: already in the console layout'); return 0
        print(f'{len(moves)} move(s){"" if a.apply else " (run with --apply to make them)"}:')
        if not a.apply:
            for m, n in moves: print(f'  {m.as_posix()} -> {n.as_posix()}')
            return 0
        apply(dump, moves)
        print(f'done: {dump} is in the console layout; {LOG} lists the moves (--undo puts them back)')
        return 0
    except (Error, pack_dump.Error, OSError) as e:
        print(f'error: {e}', file=sys.stderr); return 1


if __name__ == '__main__':
    sys.exit(main())
