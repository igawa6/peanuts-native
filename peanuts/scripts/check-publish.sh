#!/bin/sh
# check-publish.sh [<since-ref>] : the gate before anything of Peanuts Native is pushed to a public or shared remote (GitHub). Exit 0 = clean, 1 = fix first.
#
# Rule 1, no AI attribution: no commit in <since-ref>..HEAD may have an AI author or committer, a Co-Authored-By trailer naming an AI tool or its vendor,
#   or a "Generated with" line (Claude, Anthropic, Codex, OpenAI, ChatGPT, GPT, Copilot, Gemini, Cursor, Devin, ...). The person who submits a change is its
#   author; tools are not contributors.
# Rule 2, no private data: no file Peanuts changed since <since-ref> may contain a LAN address, a local home or mount path, an assistant's work folder,
#   an adb-over-network address or a personal e-mail. Vendored third-party code (dm/peanuts/third_party) keeps its own copyright lines.
#
# <since-ref> defaults to the merge base with upstream/android (Banana Native's history before it is upstream's, published as the single import commit of the
# public history, see peanuts/docs/CONTRIBUTING.md "Publishing"). A contributor's clone, whose upstream is Peanuts Native itself (its main branch, no android),
# takes the merge base with upstream/main: their own commits. peanuts/scripts/install-hooks.sh runs this before every push.
set -u
cd "$(git rev-parse --show-toplevel)" || exit 1
since="${1:-$(git merge-base HEAD upstream/android 2>/dev/null || git merge-base HEAD upstream/main 2>/dev/null)}"
[ -n "$since" ] || { echo "check-publish: no <since-ref>, and neither upstream/android nor upstream/main to take the merge base from"; exit 1; }
fail=0
AI='claude|anthropic|codex|openai|chatgpt|gpt-[0-9]|copilot|gemini|cursor|devin|aider|windsurf|noreply@anthropic'

# Rule 1: authors, committers, trailers
bad=$(git log "$since"..HEAD --format='%h %an <%ae> | %cn <%ce>' | grep -iE "$AI")
[ -n "$bad" ] && { echo "AI author/committer:"; echo "$bad"; fail=1; }
bad=$(git log "$since"..HEAD --format='@@%h%n%B' | awk '/^@@/{h=substr($0,3); next} {print h": "$0}' | grep -iE "^[0-9a-f]+: *(co-authored-by|signed-off-by|generated[- ]with|assisted[- ]by|with[- ]the[- ]help[- ]of).*($AI)|🤖" )
[ -n "$bad" ] && { echo "AI trailer in a commit message:"; echo "$bad"; fail=1; }

# Rule 2: private data in the files Peanuts changed (their current contents)
PRIV='192\.168\.[0-9]+\.[0-9]+|(^|[^0-9.])10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+|/home/[a-z_][a-z0-9_-]*/|/Users/[A-Za-z]|[Cc]:\\Users\\|/mnt/(nvme|ironwolf|data|sd)|\.claude/(jobs|projects)|cc-socks|:5555\b|\.ts\.net|@gmail\.com|@yahoo\.|@outlook\.|@hotmail\.'
git diff --name-only --diff-filter=d "$since" HEAD -- . ':!dm/peanuts/third_party' ':!peanuts/scripts/check-publish.sh' ':!peanuts/scripts/install-hooks.sh' ':!dm/tools/tests/test_log_digest.py' > "${TMPDIR:-/tmp}/check-publish.$$"
hits=$(tr '\n' '\0' < "${TMPDIR:-/tmp}/check-publish.$$" | xargs -0 grep -nIE "$PRIV" 2>/dev/null | grep -vE '127\.0\.0\.1|0\.0\.0\.0')
rm -f "${TMPDIR:-/tmp}/check-publish.$$"
[ -n "$hits" ] && { echo "private data in changed files (replace with a placeholder such as <work dir> or <device ip>):"; echo "$hits" | cut -c1-200; fail=1; }

[ $fail = 0 ] && echo "check-publish: clean ($since..HEAD: no AI attribution, no private data in changed files)"
exit $fail
