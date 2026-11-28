#!/usr/bin/env python3
"""Is a branch ready to open as a pull request on dev: the mechanical rules of peanuts/docs/CONTRIBUTING.md ("Before opening a pull request"), checked
before a reviewer has to send the PR back for them. Run it from a checkout of the branch:

    python3 peanuts/scripts/pr_ready.py

The base is the remote's dev (upstream/dev, else origin/dev, else dev); --base picks another, such as the head of the PR this one stacks on.

Must fix (exit 1):
  base        behind <base> and conflicting with it (behind without a conflict is a warning), carrying another branch's unmerged commits (a PR head),
              or merge commits
  binary      a binary file, or a .bin / .so / .apk / .dll / shader library / prewarm seed / pack added or changed; anything under dm/third_party/ but a .cs diff
  size        a file over about 10 MB or 200,000 rows that was under it at the merge-base (already over it there: a warning)
  owned       a hand edit to a generated or maintainer-owned file: the games table and the credits in README.md, peanuts/docs/README.github.md, the game
              catalog (peanuts/games.json, peanuts/games.tsv, peanuts_games.json), the release list, the version, .github/ISSUE_TEMPLATE/*, the shader chain
              lock and headers, a game added to nxGames or a profile's readiness.tsv (both put the game in the default APK)
  split       a new env switch or [auto] rule together with a game profile; an every-game change together with a game profile
  text        an em dash, en dash, middle dot or bullet added to code (in tests and data files: a warning); CRLF line endings added under dm/games/
  publish     peanuts/scripts/check-publish.sh or peanuts/scripts/upstream-update.sh --check fails (run when <head> is the checkout's HEAD)
Warnings (exit 0): every-game scope (the title and body say so), size over about 1,000 lines, the same characters in data files, C logic without a test,
a runtime change without a docs entry, a shader_split.py change beside a profile, a commit author or committer not on a users.noreply.github.com address
(GitHub's web-flow committer, GitHub <noreply@github.com>, is fine). Then a list of what no script can check (device runs, drivers, gameplay), to say in
the PR.

Usage:  pr_ready.py [--base <ref>] [<head>]
Exit 0 when nothing must be fixed, 1 when something must, 2 on a usage or git error.
"""
import argparse
import re
import shutil
import subprocess
import sys

BINARY = re.compile(r'\.(bin|so|a|apk|aab|dll|dylib|exe|lib|nup|seed|metallib|spv|zip|jar|keys|nsp|xci|nca)$', re.I)
OWNED = re.compile(r'^(peanuts/docs/README\.github\.md|peanuts/games\.(json|tsv)|android/app/src/peanuts/res/raw/peanuts_games\.json|peanuts/release-titles\.txt|'
                   r'peanuts/VERSION|\.github/ISSUE_TEMPLATE/.*|dm/gfx/tools/dmsh_chain\.lock|dm/gfx/nvn/dmsh_chain(_vk)?\.h|dm/games/[0-9A-Fa-f]{16}/readiness\.tsv)$')
# host, hle_*, vka_*, the shader chain and translator, the rewriter (in every pack's rewrite key), the user's scripts, the launcher
EVERY_GAME = re.compile(r'^(dm/(host|gfx|peanuts|third_party)/|android/host/|dm/tools/patch_nso\.py$|scripts/|android/app/.*\.(java|kt|gradle|kts)$)')
SPLIT_READER = 'dm/gfx/tools/shader_split.py'   # a new reader keyed on its own file magic lands beside its profile (#368, #580): a warning
CAP_BYTES, CAP_ROWS = 10_000_000, 200_000
WEB_FLOW = 'GitHub <noreply@github.com>'   # the committer of every commit made in GitHub's web editor
TESTS = re.compile(r'(^|/)tests?/|(^|/)test_[^/]*\.py$|Test\.(java|kt)$|_test\.(c|cc|cpp)$')
PROFILE = re.compile(r'^dm/games/([0-9A-Fa-f]{16})/')
NOTES = re.compile(r'^(docs/reference/[^/]+\.md|dm/games/[0-9A-Fa-f]{16}/README\.md)$')
RUNTIME = re.compile(EVERY_GAME.pattern + r'|^android/app/src/main/|^dm/games/[0-9A-Fa-f]{16}/')
CODE = re.compile(r'\.(c|h|cc|cpp|hpp|m|mm|java|kt|py|sh|cs|glsl|comp|js|ps1|bat|cmd|command|cmake|gradle)$|(^|/)CMakeLists\.txt$')
DOC = re.compile(r'\.(md|txt|html|rst)$|^wiki/|(^|/)LICEN[CS]E')
AUTO_RULES = 'dm/gfx/vk_android/vka_auto.h'
CHARS = {chr(0x2014): 'an em dash', chr(0x2013): 'an en dash', chr(0xB7): 'a middle dot', chr(0x2022): 'a bullet'}   # spelled out: this file stays clean
BIG = 1000
GENERATED_TABLE = re.compile(r'^dm/games/[^/]+/(?!game\.tsv$)[^/]+\.tsv$')   # shader tables the tools write: not counted in the size
REMINDERS = [
    'device run on the current release runtime with a pack prepared from the current chain: Turnip 27.0.0-R2 (never R3) and the Qualcomm system driver;'
    ' say which did not run',
    'real gameplay past the level load (a new game: player control), fps from SurfaceFlinger, memory at the heavy spot',
    'device, chip, RAM, Android version, driver versions and the build ID from the log',
    'the Python suite compared with its merge-base (no new failures), host tests for changed C (dm/host/tests), JVM tests if Java changed',
    'generated docs current: env_switches.py --check, docs_symptoms.py --check, docs_patches.py --check, check-profiles.sh',
    'a chain or translator change: the shader impact report (vk_delta.tsv entries changed, by stage) and spirv-val on every module',
    'profile tables exactly as the tools wrote them, memory rows measured',
    'the body follows the PR template, says what was not checked, and every claim matches the code',
]


def git(*a, stdin=None):
    """git's output as text, line endings kept (a CRLF line must stay visible)"""
    r = subprocess.run(['git', *a], capture_output=True, input=stdin and stdin.encode())
    if r.returncode: raise RuntimeError(r.stderr.decode('utf-8', 'replace').strip() or f'git {a[0]} failed')
    return r.stdout.decode('utf-8', 'replace')


def default_base():
    for ref in ('upstream/dev', 'origin/dev', 'dev'):
        if subprocess.run(['git', 'rev-parse', '-q', '--verify', f'{ref}^{{commit}}'], capture_output=True).returncode == 0: return ref
    raise RuntimeError('no upstream/dev, origin/dev or dev: fetch dev, or name the base with --base')


def is_dev_line(ref):
    return re.search(r'(^|/)dev(-\d[^/]*)?$', ref) is not None


def parse(diff):
    """{path: ([(new start, new count)], [(new line, added text)], [removed text])} of a -U0 diff"""
    out, cur, n = {}, None, 0
    for l in diff.split('\n'):   # not splitlines(): a CR stays on its line
        if l.startswith('diff --git '):
            m = re.match(r'diff --git a/(.*) b/(.*)$', l)
            cur = m.group(2) if m else None
            if cur: out[cur] = ([], [], [])
        elif cur is None or l.startswith(('+++ ', '--- ')): continue
        elif l.startswith('@@'):
            m = re.match(r'@@ -\S+ \+(\d+)(?:,(\d+))?', l)
            n = int(m.group(1)); out[cur][0].append((n, int(m.group(2) or 1)))
        elif l.startswith('+'): out[cur][1].append((n, l[1:])); n += 1
        elif l.startswith('-'): out[cur][2].append(l[1:])
    return out


def readme_regions(text):
    """[(first, last)] line ranges of README.md the maintainer's tools own: the games table and the credits"""
    lines, regions, b = text.splitlines(), [], None
    for i, l in enumerate(lines, 1):
        if 'TABLE_BEGIN' in l: b = i
        elif 'TABLE_END' in l and b: regions.append((b, i)); b = None
    for i, l in enumerate(lines, 1):
        if l.startswith('## ') and 'Credits' in l:
            end = next((j - 1 for j in range(i + 1, len(lines) + 1) if lines[j - 1].startswith('## ')), len(lines))
            regions.append((i, end))
    return regions


def base_findings(base, head):
    fix, warn, mb, tip = [], [], git('merge-base', base, head).strip(), git('rev-parse', base).strip()
    if mb != tip:
        behind = f'{git("rev-list", "--count", f"{head}..{base}").strip()} commits behind {base} (merge-base {mb[:8]})'
        if subprocess.run(['git', 'merge-tree', '--write-tree', '--quiet', base, head], capture_output=True).returncode:
            fix.append(('base', f'{behind} and it conflicts with {base}: rebase onto {base}'))
        else: warn.append(('base', f'{behind}, no conflict: rebase onto {base} before opening'))
    head_c = git('rev-parse', head).strip()
    stacked = {}
    for l in git('for-each-ref', '--merged', head, '--no-merged', base, '--format=%(objectname) %(refname:short)', 'refs/heads', 'refs/remotes').splitlines():
        sha, name = l.split(' ', 1)
        if sha != head_c and not is_dev_line(name): stacked.setdefault(sha, []).append(name)   # the dev line is the base, not another PR
    for sha, names in stacked.items():
        fix.append(('base', f'built on {", ".join(names)} ({sha[:8]}, not in {base}): base it on {base}, or stack it on that PR, say so, and check with --base {names[-1]}'))
    merges = git('rev-list', '--merges', f'{base}..{head}').split()
    if merges: fix.append(('base', f'{len(merges)} merge commit(s) ({", ".join(m[:8] for m in merges[:3])}): rebase, do not merge'))
    return fix, warn, mb


def diff_findings(base, head):
    fix, warn = [], []
    status = dict(reversed(l.split('\t', 1)) for l in git('diff', '--no-renames', '--name-status', f'{base}...{head}').splitlines())
    size = 0
    for l in git('diff', '--no-renames', '--numstat', f'{base}...{head}').splitlines():
        a, d, p = l.split('\t', 2)
        if status.get(p) == 'D': continue
        if a == '-' or BINARY.search(p): fix.append(('binary', f'{p}: a binary or built file; the maintainer builds every binary from source'))
        elif not GENERATED_TABLE.match(p): size += int(a) + int(d)
        if p.startswith('dm/third_party/') and (status.get(p) == 'A' or not p.endswith('.cs')):
            fix.append(('binary', f'{p}: nothing under dm/third_party/ but a marked .cs diff (vendored code changes as a version bump)'))
    files = parse(git('diff', '-U0', '--no-renames', '--no-color', '--no-ext-diff', f'{base}...{head}'))
    paths = [p for p in files if status.get(p) != 'D']

    mb = git('merge-base', base, head).strip()
    for p in paths:
        big, was = over_cap(head, p), over_cap(mb, p)
        if big: (warn if was else fix).append(('size', f'{p}: {big}' + (', as it was at the merge-base' if was else
                                                                    f': keep a file under about {CAP_BYTES // 10**6} MB and {CAP_ROWS:,} rows')))
        if p.startswith('dm/games/') and any(t.endswith('\r') for _, t in files[p][1]):
            fix.append(('text', f'{p}: CRLF line endings; the profile tools and check-profiles.sh read LF only'))

    for p in paths:
        if OWNED.match(p): fix.append(('owned', f'{p}: generated or maintainer-owned, leave it to the maintainer\'s tools'))
    if 'README.md' in paths:
        regions = readme_regions(git('show', f'{head}:README.md'))
        for s, c in files['README.md'][0]:
            if any(s <= e and s + max(c, 1) - 1 >= b for b, e in regions):
                fix.append(('owned', f'README.md:{s}: the games table and the credits are written by peanuts/scripts/games_catalog.py and the maintainer')); break
    if any(t.startswith('nxGames=') for _, t in files.get('android/app/gradle.properties', ([], [], []))[1]):
        fix.append(('owned', 'android/app/gradle.properties: nxGames changed; a game joins the default APK when the maintainer ships it'))

    every = [p for p in paths if EVERY_GAME.match(p) and not TESTS.search(p) and p != SPLIT_READER]
    profiles = sorted({PROFILE.match(p).group(1) for p in paths if PROFILE.match(p)})
    if SPLIT_READER in paths and profiles:
        warn.append(('split', f'{SPLIT_READER} with the profile of {", ".join(profiles)}: fine beside it when the new reader runs only on its own file'
                              ' magic; a change to other games\' splits is its own PR first'))
    old = {t.split('\t', 1)[0] for t in files.get('docs/env_switches.tsv', ([], [], []))[2]}
    switches = [t.split('\t', 1)[0] for _, t in files.get('docs/env_switches.tsv', ([], [], []))[1] if t.split('\t', 1)[0] not in old]
    if AUTO_RULES in paths: switches.append('[auto] rules in ' + AUTO_RULES)
    if switches and profiles:
        fix.append(('split', f'new switch {", ".join(switches)} with the profile of {", ".join(profiles)}: the switch is its own PR (off by default, its docs rows'
                             ' and tests); the profile that sets it stacks on it'))
    if every and profiles:
        fix.append(('split', f'every-game code ({", ".join(every[:3])}{", ..." if len(every) > 3 else ""}) with the profile of {", ".join(profiles)}: the every-game'
                             ' change goes in its own PR first, the profile stacks on it'))
    if every:
        warn.append(('scope', f'touches every game ({", ".join(every[:5])}{", ..." if len(every) > 5 else ""}): the title and the body say so and list the release'
                              ' games it touches, and it is its own PR'))
    if size > BIG: warn.append(('size', f'about {size} lines changed (shader tables not counted): over about {BIG}, open an issue first or split'))

    for p in paths:
        if DOC.search(p) or BINARY.search(p) or GENERATED_TABLE.match(p): continue
        kind = 'test' if TESTS.search(p) else 'code' if CODE.search(p) else 'data'   # a test may quote the character it rejects
        for n, t in files[p][1]:
            bad = [w for ch, w in CHARS.items() if ch in t]
            if bad: (fix if kind == 'code' else warn).append(('text', f'{p}:{n}: {", ".join(bad)} in {kind}: {t.strip()[:120]}'))

    logic = [p for p in paths if p.endswith(('.c', '.h')) and not TESTS.search(p)
             and any(t.strip() and not t.strip().startswith(('//', '/*', '*')) for _, t in files[p][1])]
    if logic and not any(TESTS.search(p) for p in paths):
        warn.append(('test', f'C logic changed ({", ".join(logic[:3])}) and no test touched: new logic needs a new test (dm/host/tests, dm/gfx/tests)'))
    if any(RUNTIME.match(p) for p in paths) and not any(NOTES.match(p) for p in paths):
        warn.append(('docs', 'docs entry missing: the game note (dm/games/<id>/README.md) or a docs/reference/*.md entry (Symptom, Cause, Fix, Found on),'
                             ' then dm/tools/docs_symptoms.py'))
    return fix, warn, len(paths)


def over_cap(rev, p):
    """'<n> bytes' or '<n> rows' when <rev>:<p> is over the cap, else ''"""
    info = git('cat-file', '--batch-check', stdin=f'{rev}:{p}\n').split()
    if len(info) < 3 or info[1] != 'blob': return ''   # not at <rev>
    n = int(info[2])
    if n > CAP_BYTES: return f'{n:,} bytes'
    if n > CAP_ROWS:   # 200,000 rows take at least that many bytes
        rows = subprocess.run(['git', 'cat-file', 'blob', f'{rev}:{p}'], capture_output=True).stdout.count(b'\n')
        if rows > CAP_ROWS: return f'{rows:,} rows'
    return ''


def author_findings(base, head):
    """warnings: an address that is not GitHub's noreply one puts a personal e-mail in the public history"""
    bad = {}
    for l in git('log', '--format=%h\t%an <%ae>\t%cn <%ce>', f'{base}..{head}').splitlines():
        h, a, c = l.split('\t')
        for who, v in (('author', a), ('committer', c)):
            if not v.rstrip('>').endswith('@users.noreply.github.com') and not (who == 'committer' and v == WEB_FLOW):
                bad.setdefault(f'{who} {v}', []).append(h)
    return [('author', f'{k} on {len(hs)} commit(s) ({", ".join(hs[:4])}{", ..." if len(hs) > 4 else ""}): use your GitHub noreply address'
                       ' (git config user.email, then git rebase --exec "git commit --amend --no-edit --reset-author")') for k, hs in bad.items()]


def script_findings(base, head, mb):
    """the repository's own gates, run on the checkout when <head> is its HEAD"""
    fix, warn = [], []
    top = git('rev-parse', '--show-toplevel').strip()
    runs = [('peanuts/scripts/check-publish.sh', [mb]), ('peanuts/scripts/upstream-update.sh', ['--check', base])]
    runs = [(s, a) for s, a in runs if subprocess.run(['git', 'cat-file', '-e', f'{head}:{s}'], capture_output=True).returncode == 0]
    if not runs: return fix, warn
    if git('rev-parse', head).strip() != git('rev-parse', 'HEAD').strip() or not shutil.which('sh'):
        warn.append(('publish', f'not run: {", ".join(s for s, _ in runs)} (check out {head} and run this again)')); return fix, warn
    for s, a in runs:
        r = subprocess.run(['sh', s, *a], cwd=top, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if r.returncode:
            out = [l for l in (r.stdout + r.stderr).strip().splitlines() if not l.startswith('exception (')]   # allow-listed files are fine
            fix.append(('publish', f'{s} {" ".join(a)} failed:\n' + '\n'.join('        ' + l[:200] for l in out[-12:])))
    return fix, warn


def report(base, head, mb, nfiles, fix, warn):
    lines = [f'pr_ready: {head} against {base} (merge-base {mb[:8]}, {git("rev-list", "--count", f"{base}..{head}").strip()} commits, {nfiles} files)']
    for title, fs in ((f'must fix ({len(fix)}):', fix), (f'warnings ({len(warn)}):', warn)):
        if fs: lines.append(title); lines += [f'  {k}: {t}' for k, t in fs]
    if not fix: lines.append('nothing to fix that a script can see')
    lines.append('not checked by a script (say each in the PR):')
    lines += ['  - ' + r for r in REMINDERS]
    return '\n'.join(lines)


def main(argv):
    ap = argparse.ArgumentParser(prog='pr_ready.py', description='the mechanical rules a pull request is sent back for')
    ap.add_argument('--base', help='the branch the PR targets (default: upstream/dev, else origin/dev, else dev)')
    ap.add_argument('head', nargs='?', default='HEAD')
    try: args = ap.parse_args(argv[1:])
    except SystemExit as e: return 2 if e.code else 0
    head = args.head
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')   # the findings quote the characters they flag
    try:
        base = args.base or default_base()
        fix, w1, mb = base_findings(base, head)
        f2, warn, nfiles = diff_findings(base, head)
        w3 = author_findings(base, head)
        f4, w4 = script_findings(base, head, mb)
        fix += f2 + f4; warn = w1 + warn + w3 + w4
    except RuntimeError as e:
        print(f'pr_ready: {e}', file=sys.stderr); return 2
    print(report(base, head, mb, nfiles, fix, warn))
    return 1 if fix else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
