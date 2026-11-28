#!/bin/sh
# PEANUTS: lists every profile's FPS rows, grouped by method, from a Peanuts checkout.
# Usage: sh peanuts/scripts/fps-survey.sh [checkout]   (default: the checkout this script is in). The methods: peanuts/docs/FPS-METHODS.md
src=${1:-$(cd "$(dirname "$0")/../.." && pwd)}
cd "$src/dm/games" || exit 1
for f in */game.tsv; do
    t=${f%%/*}
    rows=$(grep -oE '^(fps_[a-z_0-9]+|present_interval[[:space:]]+[a-z]+|fpslocker(_plan)?|capture_keys)' "$f" \
        | sed 's/[[:space:]][[:space:]]*/ /g' | sort | uniq -c | awk '{c=$1; $1=""; sub(/^ /,""); printf "%s%s ", $0, (c>1?"×"c:"")}')
    [ -n "$rows" ] || continue
    name=$(awk -F'\t' '$1=="name"{print $2; exit}' "$f")
    m=""
    case "$rows" in *fps_steps*) m="$m H";; esac
    case "$rows" in *fps_decouple*) m="$m G";; esac
    case "$rows" in *fps_step_elapsed*|*fps_hold*) m="$m F";; esac
    [ -f "$t/patches.py" ] && grep -qE '^FRAME_STEP[[:space:]]*=' "$t/patches.py" && case "$m" in *F*) ;; *) m="$m F";; esac
    case "$rows" in *fps_plan*|*fps_memory*) m="$m E";; esac
    case "$rows" in *fpslocker*) m="$m D";; esac
    case "$rows" in *fps_limit_present*) m="$m B";; esac
    case "$rows" in *"present_interval display"*) case "$m" in "") m=" A";; esac;; esac
    case "$rows" in *fps_target*) case "$m" in *F*) ;; *) m="$m C";; esac;; esac
    printf '%-4s %s  %s :: %s\n' "${m# }" "$t" "$name" "$rows"
done | sort
