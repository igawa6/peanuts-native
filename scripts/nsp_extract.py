#!/usr/bin/env python3
# Unpacks plaintext NSPs, as NSP Box (github.com/smarkyie/NSP-Box) writes them, into a dump folder in the flattened layout scripts/pack_dump.py reads. No keys: an
# encrypted NCA, a patch and anything else this does not know is refused (dm/tools/nsp.py). An NSP Box game NSP is a complete snapshot of one version (the update is
# already laid over the base), so it becomes updates/<version>/ (base/ for version 0): exefs/, romfs/, control/ (control.nacp, icon_<Language>.dat) and meta/<record>.cnmt;
# a DLC NSP becomes dlc/<DLC title ID>/<version>/romfs/ with its record in meta/. An existing folder is never overwritten (that package is refused, nothing is written for it).
# Each NCA's SHA-256 is checked against its name and the metadata record unless --no-verify.
# Usage: nsp_extract.py <file.nsp>... --out <dump folder> [--no-verify]   (exit 1 on error)
import argparse, os, shutil, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'dm/tools'))
import cnmt, nsp


class Error(Exception): pass


def target(out, pkg):
    """The folder of a package in the dump folder: base/ or updates/<version>/ of a game, dlc/<ID>/<version>/ of an add-on"""
    if pkg.kind == nsp.ADDON: return out / 'dlc' / f'{pkg.id:016X}' / str(pkg.version)
    return out / 'updates' / str(pkg.version) if pkg.version else out / 'base'


def extract(path, out, verify=True, say=print):
    """Writes each package of the NSP under out; returns the folders made"""
    made = []
    with nsp.open_nsp(path, verify=verify) as n:
        for pkg in n.packages:
            dest = target(out, pkg)
            if dest.exists(): raise Error(f'{dest}: exists already; nothing is overwritten (remove it or choose another --out)')
            need = sum(s for _, s in pkg.files.values())
            free = shutil.disk_usage(out if out.exists() else out.parent).free
            if free < need + (64 << 20): raise Error(f'{out}: {need / 2**30:.2f} GiB to write, {free / 2**30:.2f} GiB free')
            tmp = dest.with_name(dest.name + '.partial')
            shutil.rmtree(tmp, ignore_errors=True)
            try:
                for rel, (off, size) in pkg.files.items():
                    f = tmp / rel
                    f.parent.mkdir(parents=True, exist_ok=True)
                    with open(f, 'wb') as o: n.copy_to(off, size, o)
                (tmp / 'meta').mkdir(parents=True, exist_ok=True)
                (tmp / 'meta' / pkg.cnmt_name).write_bytes(pkg.cnmt)
                dest.parent.mkdir(parents=True, exist_ok=True)
                os.replace(tmp, dest)
            except BaseException:
                shutil.rmtree(tmp, ignore_errors=True); raise
            say(f'{path.name}: {cnmt.TYPES[pkg.kind]} {pkg.id:016X} v{pkg.version} → {dest} ({len(pkg.files):,} files, {need / 2**20:.1f} MiB)')
            made.append(dest)
    return made


def main(argv=None):
    ap = argparse.ArgumentParser(description='Unpack plaintext NSPs (NSP Box) into a dump folder')
    ap.add_argument('nsp', nargs='+', type=Path)
    ap.add_argument('--out', required=True, type=Path, help='the dump folder (made if missing)')
    ap.add_argument('--no-verify', action='store_true', help='skip the SHA-256 check of each NCA (one less read of the NSP)')
    a = ap.parse_args(argv)
    try:
        a.out.mkdir(parents=True, exist_ok=True)
        for p in a.nsp: extract(p, a.out.resolve(), verify=not a.no_verify)
        return 0
    except (Error, nsp.Error, OSError) as e:
        print(f'[RED] {e}', file=sys.stderr); return 1


if __name__ == '__main__':
    sys.exit(main())
