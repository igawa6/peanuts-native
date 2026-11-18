#!/usr/bin/env python3
"""Write the games table of wiki/Games.md from the profiles.

Reads the Android games (profiles with a `status` row in readiness.tsv), their
name and settings keys (game.tsv) and the player status table of
.github/README.md. Deterministic, no network.

    python3 scripts/wiki_games_table.py            print the table
    python3 scripts/wiki_games_table.py --write    replace the block between the
                                                   markers in wiki/Games.md
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GAMES = ROOT / "dm" / "games"
BEGIN = "<!-- games-table:begin (scripts/wiki_games_table.py) -->"
END = "<!-- games-table:end -->"

# Wiki page of each game that has one (title ID -> page name).
PAGES = {
    "040074A01BF12000": "Game-Donkey-Kong-Bananza",
    "04007EF01F8AE000": "Game-Fire-Emblem-Fortunes-Weave",
    "0400C3F00006E000": "Game-Mario-Kart-World",
    "0400A3A01EE4C000": "Game-Pokemon-Pokopia",
    "0400486024DEC000": "Game-Zelda-Ocarina-of-Time",
    "0400D4201C6EA000": "Game-Mario-Tennis-Fever",
    "040015100B514001": "Game-Super-Mario-Bros-Wonder-Switch-2-Edition",
    "0400E88025B1A000": "Game-Star-Fox",
    "0400380020056000": "Game-Splatoon-Raiders",
}


def tsv_rows(path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            rows.append(line.split("\t"))
    return rows


def readme_status():
    """Title ID -> (status, note) from the player table in .github/README.md."""
    out = {}
    for line in (ROOT / ".github" / "README.md").read_text(encoding="utf-8").splitlines():
        m = re.match(r"\| (.+?) \(`([0-9A-F]{16})`\) \| (.+?) \| (Supported|Experimental) \| (.*) \|$", line)
        if m:
            out[m.group(2)] = (m.group(4), m.group(5), m.group(3))
    return out


def settings(keys):
    s = []
    if "res_preset" in keys:
        s.append("Resolution presets")
    if "aspect" in keys:
        s.append("Aspect ratio")
    if "variant" in keys:
        s.append("Native 4:3")
    if "dlss_standin" in keys:
        s.append("Upscaler")
    if "scene_arg" in keys:
        s.append("3D scene")
    if "cheats" in keys:
        s.append("Cheats")
    if "link_play" in keys:
        s.append("Link Play")
    if "time_of_day" in keys:
        s.append("Time of day")
    if "option" in keys:
        s.append("Game options")
    return ", ".join(s) or "common settings only"


def fps(keys, vals):
    for k in ("fps_target", "fps_limit"):
        if k in vals:
            return vals[k]
    return ""


def main():
    status = readme_status()
    lines = ["| Game | Title ID | Status | Update | Frame rate and notes | Game-specific settings |",
             "|---|---|---|---|---|---|"]
    entries = []
    for d in sorted(GAMES.iterdir()):
        rd = d / "readiness.tsv"
        if not rd.is_file() or not any(r[0] == "status" for r in tsv_rows(rd)):
            continue
        gt = tsv_rows(d / "game.tsv")
        keys = {r[0] for r in gt}
        vals = {r[0]: r[1] for r in gt if len(r) > 1}
        tid = d.name
        st, note, upd = status.get(tid, ("", "", vals.get("package", "")))
        name = vals.get("name", tid)
        name = {"Pokemon Pokopia": "Pokémon Pokopia"}.get(name, name)
        entries.append((0 if st == "Supported" else 1, name, tid, st or "not in the player table", upd, note, settings(keys)))
    for _, name, tid, st, upd, note, sett in sorted(entries):
        page = PAGES.get(tid)
        link = f"[{name}]({page})" if page else name
        lines.append(f"| {link} | `{tid}` | {st} | {upd} | {note} | {sett} |")
    table = "\n".join(lines)
    if "--write" not in sys.argv:
        print(table)
        return
    path = ROOT / "wiki" / "Games.md"
    text = path.read_text(encoding="utf-8")
    pat = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.S)
    if not pat.search(text):
        sys.exit("markers not found in wiki/Games.md")
    path.write_text(pat.sub(lambda m: BEGIN + "\n" + table + "\n" + END, text), encoding="utf-8")


main()
