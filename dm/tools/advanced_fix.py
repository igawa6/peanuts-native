#!/usr/bin/env python3
"""The fix finder's table (dm/tools/advanced_fix.tsv, its columns described there) and the table of wiki/Troubleshooting.md written from it, so the wiki and the app say the same.
  advanced_fix.py           print the wiki table
  advanced_fix.py --write   replace the block between the markers in wiki/Troubleshooting.md
  advanced_fix.py --check   exit 1 when the block is not what --write would write, or the table breaks a rule (problems())
"""
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import advanced_switches   # noqa: E402

REPO = Path(__file__).resolve().parents[2]
TABLE = REPO / 'dm/tools/advanced_fix.tsv'
RULES = REPO / 'dm/tools/log_rules.tsv'
LOGDIGEST = REPO / 'android/app/src/main/java/dev/nxnative/android/LogDigest.java'
WIKI = REPO / 'wiki/Troubleshooting.md'
COLS = ['rule_id', 'advanced_id', 'value', 'scope', 'sentence']
BEGIN = '<!-- fix-table:begin (dm/tools/advanced_fix.py) -->'
END = '<!-- fix-table:end -->'
EXIT = 'exit:'
# What the player sees for an exit: case (the exit notice's own sentence for that kind, ExitNotice.java)
EXIT_SEEN = {
    'out_of_memory': '"The game stopped unexpectedly": Android closed it, the phone ran out of memory',
}


def tsv(path):
    head, rows = None, []
    for l in path.read_text(encoding='utf-8').split('\n'):
        if not l or l.startswith('#'): continue
        f = l.split('\t')
        if head is None: head = f; continue
        rows.append(dict(zip(head, f + [''] * (len(head) - len(f)))))
    return head, rows


def load():
    return tsv(TABLE)


def rules():
    """{id: row} of dm/tools/log_rules.tsv"""
    return {r['id']: r for r in tsv(RULES)[1]}


def exit_kinds():
    """the verdict kinds LogDigest.java can give (set(v, "<kind>", ...))"""
    return set(re.findall(r'set\(v, "([a-z_]+)"', LOGDIGEST.read_text(encoding='utf-8')))


def problems(head, rows, adv, log_rules, kinds):
    bad = []
    if head != COLS: bad.append(f'header is {head}, not {COLS}')
    seen = set()
    for r in rows:
        k, n = r['rule_id'], r['advanced_id']
        def no(msg, k=k, n=n): bad.append(f'{k} -> {n}: {msg}')   # PEANUTS(upstream-candidate): bind this row's labels
        if (k, n) in seen: no('listed twice')
        seen.add((k, n))
        if k.startswith(EXIT):
            if k[len(EXIT):] not in kinds: no(f'{k[len(EXIT):]!r} is no verdict kind of LogDigest.java')
            if k[len(EXIT):] not in EXIT_SEEN: no('no EXIT_SEEN text in dm/tools/advanced_fix.py')
        elif k not in log_rules: no('rule_id is no id of dm/tools/log_rules.tsv')
        a = adv.get(n)
        if a is None: no('advanced_id is no row of docs/advanced_settings.tsv'); continue
        if a['tier'] not in ('friendly', 'advanced'): no(f'the row is {a["tier"]}, not friendly or advanced')
        if a['owner']: no(f'the row is owned by {a["owner"]}: the fix finder sets only rows the Advanced page edits')
        if not advanced_switches.accepts(a, r['value']): no(f'the row does not accept {r["value"]!r}')
        if r['scope'] not in ('game', 'global'): no(f'scope {r["scope"]!r} is not game or global')
        elif r['scope'] == 'game' and a['scope'] == 'global' or r['scope'] == 'global' and a['scope'] == 'game': no(f'scope {r["scope"]} but the row is {a["scope"]}')
        if not k.startswith(EXIT) and k not in a['fixes'].split(';'): no('the row\'s fixes column does not name the rule')
        s = r['sentence']
        if not s.isascii() or len(s) > 100 or not s[:1].isupper() or not s.endswith('.') or s.lower().startswith('try'): no('sentence: ASCII, at most 100 characters, a capital first, a full stop last, not "Try ..."')
    return bad


def seen_text(k, log_rules):
    if k.startswith(EXIT): return EXIT_SEEN.get(k[len(EXIT):], k)
    return '"The game stopped unexpectedly", likely cause: ' + log_rules[k]['cause'].rstrip('.')


def where(a, scope):
    path = "the game's ... button > Game settings > Advanced switches" if scope == 'game' else 'Settings > Advanced'
    return f'{path} > {a["area"]} > **{a["label"]}**'


def table(rows, adv, log_rules):
    out = ['| What you see | Settings > Advanced > row | What it does |', '|---|---|---|']
    for r in rows:
        a = adv[r['advanced_id']]
        val = next((c.split('=', 1)[0].strip() for c in a['choices'].split(';') if '=' in c and c.split('=', 1)[1].strip() == r['value']), r['value'])
        out.append(f'| {seen_text(r["rule_id"], log_rules)} | {where(a, r["scope"])}: {val} | {r["sentence"]} |')
    return '\n'.join(out) + '\n'


def wiki_text(block):
    t = WIKI.read_text(encoding='utf-8')
    i, j = t.index(BEGIN), t.index(END)
    return t[:i + len(BEGIN)] + '\n' + block + t[j:]


if __name__ == '__main__':
    head, rows = load()
    adv = {r['name']: r for r in advanced_switches.load()}
    lr = rules()
    if sys.argv[1:] == ['--check']:
        bad = problems(head, rows, adv, lr, exit_kinds())
        if not bad and WIKI.read_text(encoding='utf-8') != wiki_text(table(rows, adv, lr)): bad.append('wiki/Troubleshooting.md: the fix table is not current (run dm/tools/advanced_fix.py --write)')
        print('\n'.join(bad))
        sys.exit(1 if bad else 0)
    block = table(rows, adv, lr)
    if sys.argv[1:] == ['--write']: WIKI.write_text(wiki_text(block), encoding='utf-8')
    else: print(block, end='')
