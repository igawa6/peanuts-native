#!/bin/sh
# install-hooks.sh : installs this clone's git hooks for the publishing rules (peanuts/scripts/check-publish.sh):
#   commit-msg  refuses a message with an AI trailer (Co-Authored-By / Generated with ... Claude, Codex, Copilot, ...)
#   pre-push    runs check-publish.sh against the merge base with upstream/android (upstream/main in a contributor's clone) and refuses the push when it fails
# Run once per clone (hooks are not versioned by git). Existing hooks of the same name are kept as <hook>.local and still run first.
set -eu
top=$(git rev-parse --show-toplevel)
hooks=$(git rev-parse --git-path hooks)
mkdir -p "$hooks"
for h in commit-msg pre-push; do
    [ -f "$hooks/$h" ] && ! grep -q 'peanuts publishing rules' "$hooks/$h" && mv "$hooks/$h" "$hooks/$h.local"
done
cat > "$hooks/commit-msg" <<'EOF'
#!/bin/sh
# peanuts publishing rules: no AI attribution in commit messages (peanuts/scripts/check-publish.sh)
d=$(dirname "$0"); [ -x "$d/commit-msg.local" ] && { "$d/commit-msg.local" "$@" || exit 1; }
if grep -iqE '^ *(co-authored-by|signed-off-by|generated[- ]with|assisted[- ]by).*(claude|anthropic|codex|openai|chatgpt|gpt-[0-9]|copilot|gemini|cursor|devin|aider|windsurf)|🤖' "$1"; then
    echo "commit-msg: remove the AI trailer from the message (peanuts publishing rules)"; exit 1
fi
EOF
cat > "$hooks/pre-push" <<EOF
#!/bin/sh
# peanuts publishing rules: check-publish.sh before every push
d=\$(dirname "\$0"); [ -x "\$d/pre-push.local" ] && { "\$d/pre-push.local" "\$@" || exit 1; }
exec sh "$top/peanuts/scripts/check-publish.sh"
EOF
chmod +x "$hooks/commit-msg" "$hooks/pre-push"
echo "installed commit-msg and pre-push in $hooks"
