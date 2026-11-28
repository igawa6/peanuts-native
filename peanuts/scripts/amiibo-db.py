#!/usr/bin/env python3
"""amiibo-db.py: the app's amiibo list (android/app/src/peanuts/res/raw/amiibo_db.tsv) from AmiiboAPI's database.

AmiiboAPI (https://github.com/N3evin/AmiiboAPI, MIT, Copyright (c) 2017 Nevin Vu) keeps every amiibo with its name, series and type in
database/amiibo.json, and what each one does in which game in database/games_info.json. The app ships both, offline, as one table:

  A  <ID, 16 hex digits>  <name>                       every amiibo
  S  <amiibo series, 2 hex digits>  <name>             the series an ID names (its 7th byte)
  G  <game series, 3 hex digits>  <name>               the game series (the ID's first 12 bits)
  T  <type, 2 hex digits>  <name>                      Figure, Card, Yarn, Band
  N  <title ID, 16 hex digits>  <name>               a Switch game's name as AmiiboAPI writes it
  W  <n>  <use>                                     a use's words (AmiiboAPI repeats the same ones for many amiibo)
  U  <title ID, 16 hex digits>  <use n>  <0 or 1>  <ID>,<ID>,...   what these amiibo do in a Switch game; 1: the game keeps data on them

Usage:  amiibo-db.py <amiibo.json> <games_info.json> <out.tsv> [<AmiiboAPI commit>]
        amiibo-db.py --fetch <commit> <out.tsv>       (reads both files of that commit from raw.githubusercontent.com)
Only names and uses are taken: no pictures (the app loads those on demand) and no amiibo data of any kind.
"""
import json
import sys
import urllib.request

RAW = "https://raw.githubusercontent.com/N3evin/AmiiboAPI/{commit}/database/{name}"


def clean(s):
    return " ".join(str(s).replace("\t", " ").replace("\r", " ").replace("\n", " ").split())


def table(amiibo, games, commit):
    out = [f"# The amiibo of AmiiboAPI (https://github.com/N3evin/AmiiboAPI, MIT, Copyright (c) 2017 Nevin Vu), commit {commit}",
           "# made by peanuts/scripts/amiibo-db.py; A: ID, name. S: amiibo series. G: game series. T: type. N: a game's name. W: a use. U: Switch title ID, use, keeps data, IDs"]
    for kind, key in (("S", "amiibo_series"), ("G", "game_series"), ("T", "types")):
        for code, name in sorted(amiibo[key].items()):
            out.append(f"{kind}\t{code[2:].lower()}\t{clean(name)}")
    ids = sorted(amiibo["amiibos"])
    for k in ids:
        out.append(f"A\t{k[2:].lower()}\t{clean(amiibo['amiibos'][k]['name'])}")
    rows = set()
    for k in ids:
        for g in games["amiibos"].get(k, {}).get("gamesSwitch", []):
            for tid in g.get("gameID", []):
                tid = tid.upper()
                if len(tid) != 16:
                    continue
                for u in g.get("amiiboUsage", []):
                    rows.add((tid, k[2:].lower(), 1 if u.get("write") else 0, clean(u.get("Usage", ""))))
    names = {}
    for k in ids:
        for g in games["amiibos"].get(k, {}).get("gamesSwitch", []):
            for tid in g.get("gameID", []):
                if len(tid) == 16 and g.get("gameName"):
                    names.setdefault(tid.upper(), clean(g["gameName"]))
    for tid in sorted(names):
        out.append(f"N\t{tid}\t{names[tid]}")
    uses = sorted({use for _, _, _, use in rows if use})
    for n, use in enumerate(uses):
        out.append(f"W\t{n}\t{use}")
    num = {use: n for n, use in enumerate(uses)}
    groups = {}
    for tid, k, w, use in rows:
        if use:
            groups.setdefault((tid, num[use], w), []).append(k)
    for (tid, n, w), ks in sorted(groups.items()):
        out.append(f"U\t{tid}\t{n}\t{w}\t{','.join(sorted(ks))}")
    return "\n".join(out) + "\n"


def main(argv):
    if len(argv) == 4 and argv[1] == "--fetch":
        commit, dst = argv[2], argv[3]
        read = lambda name: json.loads(urllib.request.urlopen(RAW.format(commit=commit, name=name), timeout=60).read().decode("utf-8"))
        amiibo, games = read("amiibo.json"), read("games_info.json")
    elif len(argv) in (4, 5):
        amiibo = json.loads(open(argv[1], encoding="utf-8").read())
        games = json.loads(open(argv[2], encoding="utf-8").read())
        dst, commit = argv[3], argv[4] if len(argv) == 5 else "unknown"
    else:
        sys.exit(__doc__)
    text = table(amiibo, games, commit)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    rows = [l.split("\t") for l in text.splitlines() if l.startswith("U")]
    print(f"{dst}: {sum(1 for l in text.splitlines() if l.startswith('A'))} amiibo, {sum(len(r[4].split(',')) for r in rows)} uses in "
          f"{len({r[1] for r in rows})} games")


if __name__ == "__main__":
    main(sys.argv)
