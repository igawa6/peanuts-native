#!/bin/sh
# mncf-vendor.sh <mistel checkout> [<commit>] : copies Mistel's MIT Java library for .mncf cheat files (format/lib/java, package io.github.davdxpx.mistel.mncf)
# into dm/third_party/mistel/, word for word, with the MIT notice of Mistel's format/ folder: only the files Peanuts needs. Those are the files
# dm/third_party/mistel/MISTEL.sha256 already lists, the ones Peanuts' own Java names (android/app/src/*/java/dev/peanuts), and every file of the library
# a kept file names in its code, again until nothing new comes (comments and strings do not count). A file nobody needs any more goes when its line is
# taken out of MISTEL.sha256 before the run. Writes MISTEL.sha256 (the commit, then a SHA-256 and a path a line), README.md (the commit, the files kept
# and left out) and the commit in dm/third_party/README.md's row. peanuts/tests/test_mncf_vendor.py checks the copy against that list, so a change to the
# copy itself fails the tests: fixes go to Mistel first (https://github.com/davdxpx/mistel), then come here with this script. <commit> defaults to
# origin/main of the checkout. Only format/ is MIT; nothing else of Mistel (GPL-3.0) may be copied into this repository.
set -eu
src="${1:?usage: mncf-vendor.sh <mistel checkout> [<commit>]}"
rev="${2:-origin/main}"
cd "$(git rev-parse --show-toplevel)"
dst="dm/third_party/mistel"
pkg="java/io/github/davdxpx/mistel/mncf"
lib="format/lib/java/src/main/java/io/github/davdxpx/mistel/mncf"
commit=$(git -C "$src" rev-parse --verify "$rev^{commit}")
short=$(git -C "$src" rev-parse --short=7 "$commit")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$src" archive "$commit" format/LICENSE "$lib" | tar -x -C "$tmp"
[ -f "$tmp/format/LICENSE" ] && grep -q '^MIT License' "$tmp/format/LICENSE" || { echo "mncf-vendor: $commit has no MIT format/LICENSE"; exit 1; }

# The files to keep, as paths under the library's package folder (Foo.java, toml/Toml.java); then the ones left out
python3 - "$tmp/$lib" "$dst/MISTEL.sha256" android/app/src > "$tmp/keep" <<'EOF'
import os, re, sys
lib, listed, users = sys.argv[1], sys.argv[2], sys.argv[3]

def code(path):
    """the file's text without comments, strings and char literals"""
    s, out, i = open(path, encoding='utf-8').read(), [], 0
    while i < len(s):
        if s.startswith('//', i):
            j = s.find('\n', i); i = len(s) if j < 0 else j
        elif s.startswith('/*', i):
            j = s.find('*/', i + 2); i = len(s) if j < 0 else j + 2
        elif s.startswith('"""', i):
            j = s.find('"""', i + 3); i = len(s) if j < 0 else j + 3
        elif s[i] in '"\'':
            j = i + 1
            while j < len(s) and s[j] != s[i]:
                j += 2 if s[j] == '\\' else 1
            i = j + 1
        else:
            out.append(s[i]); i += 1
    return ''.join(out)

files = {}
for root, _, fs in os.walk(lib):
    for f in fs:
        if f.endswith('.java'):
            files[os.path.relpath(os.path.join(root, f), lib).replace(os.sep, '/')] = os.path.join(root, f)
byname = {}
for k in files:
    byname.setdefault(k.rsplit('/', 1)[-1][:-5], []).append(k)
def names(p):
    """the library's files a file names in its code; a class it declares itself (a nested one of the same name) is its own"""
    c = code(p)
    own = set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)', c))
    return {k for t in set(re.findall(r'\b[A-Z]\w*\b', c)) - own for k in byname.get(t, [])}

want = set()
if os.path.exists(listed):
    for line in open(listed, encoding='utf-8'):
        p = line.split('  ', 1)[1].strip() if '  ' in line and not line.startswith('#') else ''
        if p.startswith('java/io/github/davdxpx/mistel/mncf/'):
            k = p[len('java/io/github/davdxpx/mistel/mncf/'):]
            if k in files:
                want.add(k)
            else:
                print(f'mncf-vendor: {k} is gone from Mistel', file=sys.stderr)
for root, _, fs in os.walk(users):
    if '/dev/peanuts' in root.replace(os.sep, '/'):
        for f in fs:
            if f.endswith('.java'):
                p = os.path.join(root, f)
                if 'io.github.davdxpx.mistel' in open(p, encoding='utf-8').read():
                    want |= names(p)
todo = list(want)
while todo:
    for k in names(files[todo.pop()]):
        if k not in want:
            want.add(k); todo.append(k)
for k in sorted(want):
    print('keep', k)
for k in sorted(set(files) - want):
    print('drop', k)
EOF
[ -n "$(grep '^keep ' "$tmp/keep")" ] || { echo "mncf-vendor: no file of the library is needed"; exit 1; }

rm -rf "$dst/java"
mkdir -p "$dst/$pkg"
grep '^keep ' "$tmp/keep" | cut -d' ' -f2 | while read -r f; do
    mkdir -p "$(dirname "$dst/$pkg/$f")"
    cp "$tmp/$lib/$f" "$dst/$pkg/$f"
done
cp "$tmp/format/LICENSE" "$dst/LICENSE"
{
    echo "# mistel $commit $lib (only the files Peanuts needs) and format/LICENSE, unchanged (peanuts/scripts/mncf-vendor.sh)"
    (cd "$dst" && find LICENSE java -type f | LC_ALL=C sort | while read -r f; do printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"; done)
} > "$dst/MISTEL.sha256"

kept=$(grep -c '^keep ' "$tmp/keep")
lines=$(cd "$dst" && find java -name '*.java' -exec cat {} + | wc -l | tr -d ' ')
keepnames=$(grep '^keep ' "$tmp/keep" | cut -d' ' -f2 | sed 's/\.java$//' | sed 's/.*/`&`/' | paste -sd, - | sed 's/,/, /g' | fold -s -w 150 | sed 's/ $//')
dropnames=$(grep '^drop ' "$tmp/keep" | cut -d' ' -f2 | sed 's/\.java$//' | sed 's/.*/`&`/' | paste -sd, - | sed 's/,/, /g' | fold -s -w 150 | sed 's/ $//')
cat > "$dst/README.md" <<EOF
# Mistel: the Java library for .mncf cheat files

<!-- PEANUTS: this folder is Peanuts Native's; upstream Banana Native has none of it. Written by peanuts/scripts/mncf-vendor.sh: do not edit by hand. -->

The launcher reads, checks, converts and writes cheat files in the Mistel Native Cheat Format (\`.mncf\`, packs \`.mncp\`) with the Java library of
[Mistel](https://github.com/davdxpx/mistel)'s \`format/\` folder, package \`io.github.davdxpx.mistel.mncf\`. Upstream: \`format/lib/java/src/main/java/\`
at commit \`$commit\` (\`$short\`), **unmodified**. License: MIT, see [LICENSE](LICENSE) (Mistel's \`format/LICENSE\`). Only \`format/\` of Mistel is MIT;
nothing of its GPL-3.0 parts is in this repository.

\`android/app/build.gradle\` adds \`java/\` to the app's Java sources (\`java.srcDirs\`), so the library is compiled with the launcher, which uses it in
\`android/app/src/main/java/dev/peanuts/mods/\`. Nothing is downloaded at build time.

Only the files the launcher needs are kept: those Peanuts' own code names, and every file of the library they name in turn. $kept files, $lines lines:
$keepnames.

Left out, as the launcher needs none of them: $dropnames.

[MISTEL.sha256](MISTEL.sha256) lists every file with its SHA-256; \`peanuts/tests/test_mncf_vendor.py\` fails when a file here is changed, added or taken
away. A fix goes to Mistel first; then \`sh peanuts/scripts/mncf-vendor.sh <mistel checkout> [<commit>]\` copies the new commit's files and writes this
README, the list and the commit in [../README.md](../README.md) again. It keeps what the list has, adds what the code now names, and drops a file only
when its line is taken out of the list before the run.
EOF

# The commit in dm/third_party/README.md's row
python3 - dm/third_party/README.md "$short" <<'EOF'
import re, sys
p, short = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8', newline='').read()
t = re.sub(r'(\n\| `mistel/` \|[^\n]*?Mistel commit `)[0-9a-f]{7,40}(`)', lambda m: m.group(1) + short + m.group(2), s)
if t == s and f'Mistel commit `{short}`' not in s:
    sys.exit('mncf-vendor: dm/third_party/README.md has no `mistel/` row with "Mistel commit `...`"')
open(p, 'w', encoding='utf-8', newline='').write(t)
EOF
echo "mncf-vendor: $kept files ($lines lines) of mistel $short in $dst; left out: $(grep -c '^drop ' "$tmp/keep")"
