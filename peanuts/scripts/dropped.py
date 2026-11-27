#!/usr/bin/env python3
"""Upstream paths Peanuts Native does not carry (peanuts/upstream-dropped.txt: the Switch 2 profiles, dm/games/04*, and their host gates).

Upstream notes and docs may still link to them; the doc checks that would otherwise fail on such a link (dm/tools/tests/test_docs.py, dm/tools/docs_gallery.py)
excuse a target this module calls dropped. The pattern rule is upstream-update.sh's: a shell `case` pattern on the repo-relative path, where `*` also matches `/`
(fnmatchcase reads it the same way).
  dropped.py <path>...   prints each path that is dropped; exit 0 when all are, 1 otherwise
"""
from fnmatch import fnmatchcase
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
LIST = REPO / 'peanuts/upstream-dropped.txt'


def patterns():
    if not LIST.is_file(): return []
    return [l.split('\t')[0] for l in LIST.read_text(encoding='utf-8').splitlines() if l.split('\t')[0] and not l.startswith('#')]


def dropped(path):
    """whether a path (repo-relative, or absolute inside the repo) is one Peanuts Native does not carry"""
    p = Path(path)
    if p.is_absolute():
        try: p = p.resolve().relative_to(REPO)
        except ValueError: return False
    s = p.as_posix()
    return any(fnmatchcase(s, pat) for pat in patterns())


def dropped_title(tid):
    """whether a title ID's profile folder (dm/games/<tid>) is dropped"""
    return dropped(f'dm/games/{tid}/game.tsv')


if __name__ == '__main__':
    hits = [a for a in sys.argv[1:] if dropped(a)]
    print('\n'.join(hits))
    sys.exit(0 if sys.argv[1:] and len(hits) == len(sys.argv[1:]) else 1)
