#!/usr/bin/env python3
# Unpacks a .nup into a dump folder in the layout scripts/pack_dump.py reads, for the tools that take only a dump folder (scripts/build_ipa_from_nsp.py's
# survey and provisional profile, dm/gfx/tools/shader_split.py): a new game's first steps (peanuts/docs/NEW-GAME.md). The .nup is only read.
#   base package       <out>/base/exefs, <out>/base/romfs
#   update packages    <out>/updates/<version>/exefs, romfs
#   add-on packages    <out>/dlc/<add-on title ID>/<version>/romfs
#   nup-extract.py "Game [01006B601380E000][v0].nup" "<work dir>/dump"
#   nup-extract.py "Game.nup" "<work dir>/dump" --exefs-only      the code modules alone (MBs, not GBs): enough to read build IDs, not for the survey
# <out> must be empty or missing; the files go to <out>.tmp first, which is renamed to <out> at the end. Keep <out> outside the repository, beside the game.
# Needs the packer's Python (dm/tools/nup.py; zstd for compressed files). Errors print [RED] and exit 1.
import argparse, os, shutil, sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm' / 'tools'))
import nup   # noqa: E402  (dm/tools/nup.py: upstream's reader of the format)


def die(msg): sys.exit(f'[RED] {msg}')


def target(p):
    """The folder of package p in the dump layout"""
    kind = nup.KINDS.get(p.kind)
    if kind == 'base': return Path('base')
    if kind == 'update': return Path('updates') / str(p.version)
    if kind == 'addon': return Path('dlc') / f'{p.title_id:016X}' / str(p.version)
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description='Unpack a .nup into a dump folder (base/, updates/<version>/, dlc/)')
    ap.add_argument('nup', help='the .nup file')
    ap.add_argument('out', help='the dump folder to make (empty or missing)')
    ap.add_argument('--exefs-only', action='store_true', help='only the code modules (exefs), no RomFS')
    a = ap.parse_args(argv)
    src, out = Path(a.nup), Path(a.out)
    if not src.is_file(): die(f'not a file: {src}')
    if out.exists() and any(out.iterdir()): die(f'{out} is not empty: give an empty or new folder')
    try: n = nup.Nup(str(src))
    except (OSError, nup.Error) as e: die(f'{src}: {e}')
    tmp = out.with_name(out.name + '.tmp')
    if tmp.exists(): shutil.rmtree(tmp)
    count = size = 0
    for p in n.packages:
        rel = target(p)
        if rel is None:
            print(f'skipped {p!r}: not a base, update or add-on package')
            continue
        root = ('pkg', p.index)
        for section in ('exefs',) if a.exefs_only else ('exefs', 'romfs'):
            try: k = n.find(section, root)
            except FileNotFoundError: continue   # an add-on has no exefs
            for d, _, files in n.walk(k):
                dest = tmp / rel / section / d
                dest.mkdir(parents=True, exist_ok=True)
                for f in files:
                    node = n.find(f'{d}/{f}' if d else f, k)
                    with n.open(node) as r, open(dest / f, 'wb') as w: shutil.copyfileobj(r, w, 1 << 22)
                    count += 1; size += n.file_size(node)
        print(f'{p!r} -> {rel}')
    if not count: die(f'{src}: nothing to unpack')
    if out.exists(): out.rmdir()
    os.replace(tmp, out)
    print(f'{count} files, {size / (1 << 30):.2f} GiB -> {out}')


if __name__ == '__main__':
    try: main()
    except KeyboardInterrupt: sys.exit('[RED] stopped')
