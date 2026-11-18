#!/usr/bin/env python3
"""The player-facing side of every switch: docs/advanced_settings.tsv, one row for each row of docs/env_switches.tsv (the launcher's Settings > Advanced page and each game's
Advanced section read it from the APK's assets; build.gradle copies it).
  advanced_switches.py           write the table: a new switch gets a row with tier `developer` (`internal` for a test or numbered family) that whoever adds the switch corrects; a
                                 switch that env_switches.tsv no longer lists loses its row
  advanced_switches.py --check   exit 1 when the table does not list exactly env_switches.tsv's switches, or a row breaks the rules below

Columns (tab separated, '#' lines and empty lines skipped, the first other line is the header):
  name       the switch, as in env_switches.tsv
  tier       friendly (main groups, named in wiki/Settings-Advanced.md) | advanced (shown, collapsed) | developer (raw name and env_switches.tsv's meaning, behind "Show developer
             options") | internal (never shown; `reason` says why)
  area       Graphics | Performance | Memory | Input | Audio | Packing | Game | Diagnostics
  label      the plain name (<= 32 chars, ASCII); required for friendly and advanced
  try_if     "Try this if ..." one line (<= 110 chars, ASCII, starts with the symptom); required for friendly and advanced unless `owner` is set
  type       toggle | choice | number | text (text: any KEY=value, developer rows)
  choices    toggle and choice: `label=value;label=value`; number: `min..max` (a unit may follow after a space), then optionally `|v,v,v` the preset values shown as chips (each inside the range); empty for text. Auto (write nothing) is always the first choice
  default    what Auto does, in words (friendly and advanced)
  scope      global | game | both
  warn       a warning shown with the row (an unmeasured or risky setting); marks it Experimental
  owner      `Page > Label` of the launcher control that already sets this switch: the row is a link to it, never a second editor. Label must be a string in the launcher's sources
  profile_key  the game.tsv key the switch overrides (must exist in some dm/games/*/game.tsv)
  games      title IDs the row is shown for (empty: every game); each must be a dm/games/ folder
  fixes      log_rules.tsv ids and symptom words (`;` separated) the fix finder matches
  reason     internal rows: why it is not shown (<= 60 chars)
"""
from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[2]
ENV = REPO / 'docs/env_switches.tsv'
TABLE = REPO / 'docs/advanced_settings.tsv'
COLS = ['name', 'tier', 'area', 'label', 'try_if', 'type', 'choices', 'default', 'scope', 'warn', 'owner', 'profile_key', 'games', 'fixes', 'reason']
TIERS = ('friendly', 'advanced', 'developer', 'internal')
AREAS = ('Graphics', 'Performance', 'Memory', 'Input', 'Audio', 'Packing', 'Game', 'Diagnostics')
TYPES = ('toggle', 'choice', 'number', 'text')
SCOPES = ('global', 'game', 'both')
HEAD = '\t'.join(COLS) + '\n'
HEADER_NOTE = ('# One row for each row of docs/env_switches.tsv (dm/tools/advanced_switches.py writes and checks it; the columns are described there). The launcher shows the\n'
               '# friendly and advanced rows in Settings > Advanced and on each game\'s page; developer rows are behind "Show developer options"; internal rows are never shown.\n')

AREA_RULES = (   # first match wins, for a seeded row of class switch or player
    ('Memory', r'MEM|CACHE|IMG_|HEAP|RAM|POOL'),
    ('Input', r'PAD|MOTION|RUMBLE|TOUCH|HID|KEY|GYRO|XBOX'),
    ('Audio', r'AUDIO|SND|SOUND|OPUS|AUD_'),
    ('Packing', r'PACK|SPLIT|DMSH|PREPARE|ROMFS|NSO|CHEATS'),
    ('Performance', r'FPS|LSFG|PRESENT|PACE|THREAD|PIN|PSO|WATCH|STALL'),
    ('Graphics', r'VK|NVN|GFX|RENDER|FSR|NDSR|DLSS|GSR|DRS|WINDOW|PICTURE|SHADER|TEX'),
)


def read_env():
    """{name: (class, meaning)} from docs/env_switches.tsv"""
    out = {}
    for l in ENV.read_text().split('\n')[1:]:
        f = l.split('\t')
        if len(f) == 3 and f[0]: out[f[0]] = (f[1], f[2])
    return out


def seed(name, cls, meaning):
    """a new switch's row: nothing is shown to players until someone gives it a tier of friendly or advanced"""
    tier, reason = 'developer', ''
    if cls == 'test' or '<' in name: tier, reason = 'internal', 'test harness' if cls == 'test' else 'numbered family'
    elif 'Android' not in meaning and re.search(r'\bMac\b|iPhone|\biOS\b|Metal|MetalFX', meaning): tier, reason = 'internal', 'Mac or iOS only'
    area = 'Diagnostics'
    if cls in ('switch', 'player'):
        area = 'Game'
        for a, rx in AREA_RULES:
            if re.search(rx, name): area = a; break
    return {'name': name, 'tier': tier, 'area': area, 'type': 'text', 'scope': 'both', 'reason': reason}


def load():
    """[row dict] in file order; a short line is padded"""
    rows = []
    if TABLE.exists():
        header = False
        for l in TABLE.read_text().split('\n'):
            if not l or l.startswith('#'): continue
            f = l.split('\t')
            if not header: header = True; continue
            f += [''] * (len(COLS) - len(f))
            rows.append(dict(zip(COLS, f)))
    return rows


def text(rows):
    return HEADER_NOTE + HEAD + ''.join('\t'.join(r.get(c, '') for c in COLS) + '\n' for r in sorted(rows, key=lambda r: r['name']))


def merged():
    env = read_env()
    kept = {r['name']: r for r in load()}
    return [kept.get(n) or seed(n, c, m) for n, (c, m) in sorted(env.items())]


def profile_keys():
    keys, ids = set(), set()
    for p in (REPO / 'dm/games').glob('*/game.tsv'):
        ids.add(p.parent.name)
        for l in p.read_text().split('\n'):
            if l and not l.startswith('#'): keys.add(l.split('\t')[0])
    return keys, ids


def java_strings():
    out = []
    for p in (REPO / 'android/app/src/main/java').rglob('*.java'): out.append(p.read_text(errors='replace'))
    return '\n'.join(out)


def accepts(r, v):
    """whether a row takes this value, as the launcher's AdvancedTable.Row.accepts decides: a choice's own values, a number in range, any one-line text for a developer row"""
    if not v or '\n' in v or ('=' in v and r['type'] != 'text'): return False
    if r['type'] in ('toggle', 'choice'):
        return any(c.split('=', 1)[1].strip() == v for c in r['choices'].split(';') if '=' in c and c.split('=', 1)[0].strip())
    if r['type'] == 'number':
        m = re.fullmatch(r'(-?[0-9.]+)\.\.(-?[0-9.]+)( \S+)?', r['choices'])
        try: return bool(m) and float(m.group(1)) <= float(v) <= float(m.group(2))
        except ValueError: return False
    return r['tier'] == 'developer'


def problems(rows, env, keys=None, ids=None, java=None, wiki=None):
    """the rules of this table, as a list of messages; keys, ids, java and wiki (the profile keys, the game folders, the launcher's source text, the Advanced wiki page) are
    checked only when given"""
    bad = []
    names = [r['name'] for r in rows]
    for n in sorted(set(env) - set(names)): bad.append(f'{n}: no row in docs/advanced_settings.tsv (run dm/tools/advanced_switches.py, then give it a tier)')
    for n in sorted(set(names) - set(env)): bad.append(f'{n}: row for a switch docs/env_switches.tsv does not list')
    if len(names) != len(set(names)): bad.append('a switch has two rows')
    for r in rows:
        n, t = r['name'], r.get('tier', '')
        def no(msg, n=n): bad.append(f'{n}: {msg}')   # PEANUTS(upstream-candidate): bind this row's name
        if t not in TIERS: no(f'tier {t!r} is not one of {TIERS}'); continue
        if r['area'] not in AREAS: no(f'area {r["area"]!r} is not one of {AREAS}')
        if r['type'] not in TYPES: no(f'type {r["type"]!r} is not one of {TYPES}')
        if r['scope'] not in SCOPES: no(f'scope {r["scope"]!r} is not one of {SCOPES}')
        for c in COLS:
            if not r.get(c, '').isascii(): no(f'{c} is not ASCII')
        cls = env.get(n, ('', ''))[0]
        if t == 'internal' and not r['reason']: no('internal needs a reason')
        if len(r['reason']) > 60: no('reason is longer than 60 characters')
        if t in ('friendly', 'advanced'):
            if cls in ('test', 'path') and not r['owner']: no(f'a {cls} switch cannot be {t}')
            if not r['label']: no(f'{t} needs a label')
            if len(r['label']) > 32: no('label is longer than 32 characters')
            if not r['try_if'] and not r['owner']: no(f'{t} needs a try_if line (or an owner)')
            if len(r['try_if']) > 110: no('try_if is longer than 110 characters')
            if r['try_if'] and r['try_if'].lower().startswith('try'): no('try_if starts with the symptom, not with "Try"')
            if not r['owner']:
                if not r['default']: no('no default text (what Auto does)')
                if r['type'] == 'text': no('only developer rows can be free text')
                if r['type'] in ('toggle', 'choice'):
                    for c in r['choices'].split(';'):
                        if '=' not in c or not c.split('=')[0].strip(): no(f'choices has a bad item {c!r}')
                if r['type'] == 'number':
                    m = re.fullmatch(r'(-?[0-9.]+)\.\.(-?[0-9.]+)( [^|]+)?(?:\|([0-9.,-]+))?', r['choices'])
                    if not m: no('number choices must be min..max [unit][|v,v,v]')
                    elif m.group(4):
                        for v in m.group(4).split(','):
                            try: ok = float(m.group(1)) <= float(v) <= float(m.group(2))
                            except ValueError: ok = False
                            if not ok: no(f'preset {v!r} is outside {m.group(1)}..{m.group(2)}')
                        if len(m.group(4).split(',')) > 4: no('at most 4 presets')
        elif r['label'] or r['try_if']:
            pass   # a developer row may carry a label; nothing is required
        if r['warn'] and t not in ('friendly', 'advanced'): no('warn only for friendly and advanced rows')
        if r['owner'] and ' > ' not in r['owner']: no('owner is "Page > Label"')
        if r['owner'] and java is not None and f'"{r["owner"].split(" > ")[-1]}"' not in java: no(f'owner label {r["owner"].split(" > ")[-1]!r} is not a string in the launcher')
        if r['profile_key'] and keys is not None and r['profile_key'] not in keys: no(f'profile_key {r["profile_key"]} is in no game.tsv')
        if r['games'] and ids is not None:
            for g in r['games'].split(','):
                if g not in ids: no(f'games names {g}, which is no dm/games folder')
        if t == 'friendly' and wiki is not None and r['label'].lower() not in wiki.lower(): no(f'friendly label {r["label"]!r} is not named in wiki/Settings-Advanced.md')
    return bad


if __name__ == '__main__':
    env = read_env()
    rows = merged()
    if sys.argv[1:] == ['--check']:
        keys, ids = profile_keys()
        wp = REPO / 'wiki/Settings-Advanced.md'
        bad = problems(load(), env, keys, ids, java_strings(), wp.read_text() if wp.exists() else '')
        if not TABLE.exists() or TABLE.read_text() != text(load()): bad.append('docs/advanced_settings.tsv is not in the canonical form (run dm/tools/advanced_switches.py)')
        print('\n'.join(bad))
        sys.exit(1 if bad else 0)
    TABLE.write_text(text(rows))
    print(f'{TABLE.relative_to(REPO)}: {len(rows)} rows, ' + ', '.join(f'{t} {sum(1 for r in rows if r["tier"] == t)}' for t in TIERS))
