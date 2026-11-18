#!/usr/bin/env python3
"""Checks a run's log for a game's cheats: did the engine load the profile's cheat file, did every cheat write memory, and was there no [RED] line about cheats.

    python3 scripts/cheat_log_check.py <title ID> <log file | ->  [--cheats "Name one,Name two"] [--min-addrs N]

A run for it (test app only; use a copy of a save in a folder of its own, never a real save folder): turn the game's cheats on (Game settings, or
`DM_CHEATS_ON="<names>"`), set `DM_CHEATS_DEBUG=1` so that every write is logged, play a few seconds in the game (a cheat whose pointer chain does not resolve yet,
for example before a save is loaded, writes nothing), then pass the log (`adb logcat -d`, a bug report's log, or the launcher's log file).
What it proves: the file loaded for this build, every named cheat reached guest memory (so its pointer chain resolved), nothing was refused.
What it does not: that the game shows the value (health in a fight, a level after the next level-up): look at the screen for those.
Exit code: 0 all fine, 1 a check failed, 2 unusable input."""
import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

LOADED = re.compile(r'cheats: (?P<path>\S+): build (?P<build>[0-9a-fA-F]+): (?P<n>\d+) cheats \((?P<masters>\d+) master, (?P<on>\d+) on, (?P<broken>\d+) broken, (?P<runnable>\d+) runnable\)')
WRITE = re.compile(r'cheat "(?P<name>.*)": write (?P<len>\d+) bytes of 0x(?P<val>[0-9a-fA-F]+) at guest address 0x(?P<addr>[0-9a-fA-F]+)')
RED = re.compile(r'\[RED\].*cheat', re.I)
WORD = re.compile(r'^[0-9A-Fa-f]{8}( [0-9A-Fa-f]{8})*$')


def cheat_names(text):
    """The names of the cheats in an Atmosphere file: a [Name] header with at least one line of code words after it ({Name} masters and labels with no code are skipped)."""
    names, cur, has = [], None, False
    for line in text.splitlines():
        line = line.strip()
        m = re.fullmatch(r'\[(.*)\]', line)
        if m:
            if cur and has: names.append(cur)
            cur, has = (None if m.group(1).startswith('--') else m.group(1)), False
        elif cur and WORD.match(line): has = True
    if cur and has: names.append(cur)
    return names


def profile_cheat_file(title_id):
    folder = REPO / 'dm/games' / title_id / 'cheats'
    files = sorted(folder.glob('*.txt')) if folder.is_dir() else []
    return files[0] if files else None


def check(title_id, log, only=None, min_addrs=1):
    """(lines of the report, ok). `log` is the log's text."""
    out, ok = [], True
    f = profile_cheat_file(title_id)
    if f is None: return [f'no cheat file in dm/games/{title_id}/cheats/'], False
    want = [n for n in cheat_names(f.read_text(encoding='utf-8')) if only is None or n in only]
    if only:
        for n in only:
            if n not in want: out.append(f'FAIL  "{n}" is not a cheat of {f.name}'); ok = False
    loaded = [m for m in map(LOADED.search, log.splitlines()) if m]
    if not loaded: out.append('FAIL  the engine logged no "cheats: <file>: build <id>: N cheats" line (cheats off, no file for this build, or the log is from before it started)'); ok = False
    else:
        m = loaded[-1]
        out.append(f'ok    loaded {m["path"]}: build {m["build"]}, {m["n"]} cheats, {m["on"]} on, {m["broken"]} broken, {m["runnable"]} runnable')
        if f.stem.lower() != m['build'].lower()[:len(f.stem)]: out.append(f'FAIL  the loaded build {m["build"]} is not the profile file {f.name}'); ok = False
        if int(m['broken']): out.append(f'FAIL  {m["broken"]} cheats broken'); ok = False
    addrs = {}
    for m in map(WRITE.search, log.splitlines()):
        if m: addrs.setdefault(m['name'], set()).add(int(m['addr'], 16))
    if not addrs and loaded: out.append('note  no write was logged: run with DM_CHEATS_DEBUG=1, and play until the game has loaded a save')
    for n in want:
        k = len(addrs.get(n, ()))
        if k >= min_addrs: out.append(f'ok    {n}: {k} guest address{"es" if k != 1 else ""} written')
        else: out.append(f'FAIL  {n}: {k} addresses written (needs {min_addrs}): switched off, or its pointer chain did not resolve'); ok = False
    for line in log.splitlines():
        if RED.search(line): out.append('FAIL  ' + line.strip()[:200]); ok = False
    return out, ok


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or '').split('\n')[0])   # PEANUTS(upstream-candidate): module docs can be stripped
    ap.add_argument('title_id')
    ap.add_argument('log', help='log file, or - for stdin')
    ap.add_argument('--cheats', help='comma-separated cheat names to require (default: every cheat of the profile file)')
    ap.add_argument('--min-addrs', type=int, default=1, help='distinct guest addresses each cheat must have written (default 1; a party cheat on a 4-unit party: 4)')
    a = ap.parse_args()
    try: log = sys.stdin.read() if a.log == '-' else Path(a.log).read_text(encoding='utf-8', errors='replace')
    except OSError as e: print(f'cannot read the log: {e}', file=sys.stderr); return 2
    only = [n.strip() for n in a.cheats.split(',')] if a.cheats else None
    out, ok = check(a.title_id, log, only, a.min_addrs)
    print('\n'.join(out)); print('RESULT ' + ('ok' if ok else 'FAILED'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
