#!/usr/bin/env bash
# Copy wiki/ into the Forgejo wiki repo of this project and (only with PUSH=1) push it.
# WIKI_URL: git URL of the wiki repo (<repo>.wiki.git). The wiki is generated output:
# edits made in the web UI are overwritten by the next run.
set -euo pipefail
: "${WIKI_URL:?set WIKI_URL to the git URL of the wiki repo}"
src="$(cd "$(dirname "$0")/.." && pwd)/wiki"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git clone --quiet "$WIKI_URL" "$tmp/w"
find "$tmp/w" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -R "$src"/. "$tmp/w"/
cd "$tmp/w"
git add -A
if git diff --cached --quiet; then echo "wiki: no change"; exit 0; fi
git --no-pager diff --cached --stat
if [ "${PUSH:-0}" = 1 ]; then git -c user.name=moceure -c user.email=moceure@eden-emu.dev commit -q -m "Update wiki from wiki/" && git push; else echo "dry run: set PUSH=1 to push"; fi
