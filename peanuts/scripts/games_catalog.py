#!/usr/bin/env python3
"""The game catalog, peanuts/games.json, and the README's Supported games table (README.md and peanuts/docs/README.github.md): every game
this app version ships, from the sources the repository keeps:

- peanuts/release-titles.txt: which games ship (the release's title IDs);
- dm/games/<title ID>/game.tsv: the profile's name, its builds (build rows: the update's version number and display version), the builds
  Android plays (android_builds; all build rows without it) and the package's version;
- peanuts/games.tsv: the table's order, the name players know, the Game update cell where a profile has no display version ("?"), and
  whether a companion exists.

    python3 peanuts/scripts/games_catalog.py            # write peanuts/games.json and the two tables
    python3 peanuts/scripts/games_catalog.py --refresh  # first: add a row to games.tsv for a game that ships without one, and set the
                                                        # companion column from eden-duo-companions' releases (network; GITHUB_TOKEN if set)
    python3 peanuts/scripts/games_catalog.py --check    # exit 1 if a written file differs from what this script writes

The tables are written between the markers TABLE_BEGIN and TABLE_END; the rest of each README is left as it is. On main,
.github/workflows/catalog.yml runs this with --refresh (and issue_forms.py) when an input changes and once a day, and commits what changed, so
adding or removing a game needs no step by hand. Warnings (printed, never fatal): a shipped game without a profile or a games.tsv row, a
games.tsv row of a game that does not ship, companion releases that could not be read.
"""
from pathlib import Path
import json
import os
import re
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'peanuts/games.json'
GAMES = 'peanuts/games.tsv'
READMES = ('README.md', 'peanuts/docs/README.github.md')
TABLE_BEGIN = ('<!-- PEANUTS begin: the Supported games table, written by peanuts/scripts/games_catalog.py from peanuts/games.tsv, '
               'peanuts/release-titles.txt and the profiles. Edit peanuts/games.tsv, not this table -->')
TABLE_END = '<!-- PEANUTS end: the Supported games table -->'
# The app's companion catalog (android/app/src/main/java/dev/peanuts/Catalog.java): the releases and their asset names
RELEASES = 'https://api.github.com/repos/igawa6/eden-duo-companions/releases?per_page=100'
ASSET = re.compile(r'^([0-9A-Fa-f]{16})(?:-([A-Za-z0-9]+)-([0-9][0-9A-Za-z.\-]*))?\.dsmod\.zip$')


def titles(root=ROOT):
    return [t.strip() for t in (root / 'peanuts/release-titles.txt').read_text(encoding='utf-8').split() if t.strip()]


def game_rows(root=ROOT):
    """peanuts/games.tsv as {title ID: {'name', 'update', 'companion' True/False/None}}, in the file's order"""
    rows = {}
    for ln in (root / GAMES).read_text(encoding='utf-8').splitlines():
        if not ln.strip() or ln.startswith('#'):
            continue
        cells = (ln.split('\t') + ['', '', '', ''])[:5]
        comp = cells[3].strip().casefold()
        rows[cells[0].strip()] = {'name': cells[1].strip(), 'update': cells[2].strip(),
                                  'companion': True if comp == 'yes' else False if comp == 'no' else None,
                                  'since': cells[4].strip()}   # the release a game first ships in; empty: an earlier one
    return rows


def profile(root, title_id):
    path = root / 'dm/games' / title_id / 'game.tsv'
    if not path.is_file():
        return None
    rows = [ln.rstrip('\n').split('\t') for ln in path.read_text(encoding='utf-8').splitlines() if ln and not ln.startswith('#')]

    def values(key):
        return [r for r in rows if r[0] == key]
    name = next((r[1] for r in values('name') if len(r) > 1), None)
    builds = [(r[1].strip(), r[2].strip() if len(r) > 2 else '?') for r in values('build') if len(r) > 1]
    android = [v for r in values('android_builds') for c in r[1:] for v in c.split()]
    package = next((r[1] for r in values('package') if len(r) > 1), None)
    return {'name': name, 'builds': builds, 'android': android, 'package': package}


def number(version):
    m = re.search(r'\d+', version)
    return int(m.group()) if m else -1


def display(shown, cell, count):
    """The version players read: the profile's, else games.tsv's update cell when it names one version only"""
    if shown and shown != '?':
        return shown
    if count == 1 and cell and ',' not in cell:
        if cell.lower().startswith('base game'):
            return 'base game'
        return cell if not cell.isdigit() else f'v{cell}'
    return None


def build(root=ROOT, warn=print):
    shipped = titles(root)
    rows = game_rows(root)
    version = (root / 'peanuts/VERSION').read_text(encoding='utf-8').strip()
    games = []
    order = [t for t in rows if t in shipped] + [t for t in shipped if t not in rows]
    for t in order:
        p = profile(root, t)
        if p is None:
            warn(f'catalog: {t} ships (release-titles.txt) but has no profile (dm/games/{t}/game.tsv); left out')
            continue
        row = rows.get(t)
        if row is None:
            warn(f'catalog: {t} ({p["name"]}) ships but has no row in {GAMES} (--refresh adds it)')
        plays = [b for b in p['builds'] if not p['android'] or b[0] in p['android']]
        plays.sort(key=lambda b: number(b[0]), reverse=True)
        cell = row['update'] if row else ''
        note = root / 'dm/games' / t / 'README.md'
        games.append({
            'title_id': t,
            'name': row['name'] if row and row['name'] else p['name'],
            'profile_name': p['name'],
            'builds': [{'version': v, 'number': number(v), 'display': display(d, cell, len(plays))} for v, d in plays],
            'readme_update': cell or ', '.join(d if d != '?' else v for v, d in reversed(plays)),
            'package': p['package'],
            'companion': row['companion'] if row else None,
            'since': row['since'] or None if row else None,
            'profile': f'dm/games/{t}/game.tsv',
            'note': f'dm/games/{t}/README.md' if note.is_file() else None,
        })
    for t in rows:
        if t not in shipped:
            warn(f'catalog: {GAMES} lists {t} ({rows[t]["name"]}), which release-titles.txt does not ship; left out')
    table = [g['title_id'] for g in games]
    games.sort(key=lambda g: g['name'].casefold())
    return {'about': f'Written by peanuts/scripts/games_catalog.py from peanuts/release-titles.txt, dm/games/<title ID>/game.tsv and {GAMES}. '
                     'Do not edit by hand.',
            'app_version': version, 'readme_order': table, 'games': games}


def render(catalog):
    return json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'


def vkey(v):
    """A version as numbers for comparing (0.1.10 after 0.1.9)"""
    return tuple(int(x) for x in re.findall(r'\d+', v or ''))


def upcoming(g, version):
    """Whether a game first ships in a release after this app version (games.tsv's since column)"""
    return bool(g.get('since')) and vkey(g['since']) > vkey(version)


def table(catalog):
    """The text between the README's markers: the count sentence and the table, in games.tsv's order. A game whose since column names a
    later release than peanuts/VERSION is not counted in this version and is marked with the release it comes in"""
    by_id = {g['title_id']: g for g in catalog['games']}
    ver = catalog['app_version']
    later = [g for g in by_id.values() if upcoming(g, ver)]
    head = f'Peanuts Native {ver} includes {len(by_id) - len(later)} games'
    if later:
        nxt = sorted({g['since'] for g in later}, key=vkey)
        head += f', and {len(later)} more {"are" if len(later) > 1 else "is"} coming in {" and ".join(nxt)} (marked in the table)'
    lines = [head + '. The Companion column shows whether a game appears in the '
             'Eden Duo Companions catalog. Updates are shown as version numbers or numeric update IDs.', '',
             '| Game | Title ID | Game update | Companion |', '|---|---|---|---|']
    for t in catalog['readme_order']:
        g = by_id[t]
        name = f'{g["name"]} (coming in {g["since"]})' if upcoming(g, ver) else g['name']
        lines.append(f'| {name} | `{t}` | {g["readme_update"]} | {"Yes" if g["companion"] else "No"} |')
    return '\n'.join(lines)


def readme(text, catalog, name='README'):
    """text with the part between the markers written anew; None when the markers are missing"""
    if text.count(TABLE_BEGIN) != 1 or text.count(TABLE_END) != 1 or text.index(TABLE_BEGIN) > text.index(TABLE_END):
        return None
    head, rest = text.split(TABLE_BEGIN)
    _, tail = rest.split(TABLE_END)
    return f'{head}{TABLE_BEGIN}\n\n{table(catalog)}\n\n{TABLE_END}{tail}'


def companions(token=None):
    """The title IDs with a companion release (Catalog.java's: every published release's .dsmod.zip assets, all pages)"""
    found, url = set(), RELEASES
    while url:
        req = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'peanuts-games-catalog',
                                                   **({'Authorization': f'Bearer {token}'} if token else {})})
        with urllib.request.urlopen(req, timeout=30) as r:
            for rel in json.load(r):
                if rel.get('draft'):
                    continue
                for a in rel.get('assets', []):
                    m = ASSET.match(a.get('name', ''))
                    if m:
                        found.add(m[1].upper())
            nxt = re.search(r'<([^>]+)>;\s*rel="next"', r.headers.get('Link') or '')
            url = nxt[1] if nxt else None
    return found


def refresh(root=ROOT, warn=print, fetch=companions):
    """games.tsv with a row for every shipped game and the companion column from the releases; comments and order are kept"""
    path = root / GAMES
    text = path.read_text(encoding='utf-8')
    try:
        live = fetch(os.environ.get('GITHUB_TOKEN'))
    except Exception as e:   # the network or GitHub: the column stays as it is
        warn(f'catalog: companion releases not read ({e}); the companion column is left as it is')
        live = None
    out, seen = [], set()
    for ln in text.splitlines():
        if ln.strip() and not ln.startswith('#'):
            cells = (ln.split('\t') + ['', '', '', ''])[:5]
            seen.add(cells[0].strip())
            if live is not None:
                cells[3] = 'yes' if cells[0].strip() in live else 'no'
            ln = '\t'.join(cells).rstrip('\t')
        out.append(ln)
    for t in titles(root):
        p = profile(root, t)
        if t not in seen and p is not None:
            out.append('\t'.join([t, p['name'] or t, '', 'yes' if live and t in live else 'no']))
            warn(f'catalog: {GAMES}: a row for {t} ({p["name"]}) added at the end; give it the name players know if the profile\'s is not')
    path.write_text('\n'.join(out) + '\n', encoding='utf-8', newline='\n')


def outputs(root=ROOT, warn=print):
    """{path: text this script writes}; a README without its markers is left out, with a warning"""
    catalog = build(root, warn)
    want = {'peanuts/games.json': render(catalog)}
    for name in READMES:
        path = root / name
        text = readme(path.read_text(encoding='utf-8'), catalog) if path.is_file() else None
        if text is None:
            warn(f'catalog: {name} has no Supported games markers; not written')
        else:
            want[name] = text
    return want


def main(argv, root=ROOT):
    if '--refresh' in argv:
        refresh(root)
    want = outputs(root)
    stale = [n for n, t in want.items() if not (root / n).is_file() or (root / n).read_text(encoding='utf-8') != t]
    if '--check' in argv:
        for n in stale:
            print(f'{n} is out of date (run python3 peanuts/scripts/games_catalog.py)')
        if not stale:
            print('game catalog and games tables: up to date')
        return 1 if stale else 0
    for n in stale:
        (root / n).write_text(want[n], encoding='utf-8', newline='\n')
    print(f'game catalog: {len(json.loads(want["peanuts/games.json"])["games"])} games; written: {", ".join(stale) or "nothing, all up to date"}')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
