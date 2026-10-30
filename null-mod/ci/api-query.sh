#!/usr/bin/env bash
# Prints members from build/api-flat.txt. CLASSES: space separated fully qualified names
# (inner classes with $). GREP: extended regex over "Class :: member" lines. MAX: line cap.
set -euo pipefail
cd "$(dirname "$0")/.."
F=build/api-flat.txt
MAX=${MAX:-600}
OUT=$(mktemp)
if [ -n "${CLASSES:-}" ]; then
  for c in $CLASSES; do
    if ! awk -v c="$c" 'index($0, c " :: ") == 1 { print; found = 1 } END { exit found ? 0 : 1 }' "$F" >> "$OUT"; then
      echo "!! no such class: $c" >> "$OUT"
    fi
  done
fi
if [ -n "${GREP:-}" ]; then
  grep -E -- "$GREP" "$F" >> "$OUT" || echo "!! no match for: $GREP" >> "$OUT"
fi
TOTAL=$(wc -l < "$OUT")
echo "=====API-QUERY-BEGIN===== ($TOTAL lines, showing up to $MAX)"
head -n "$MAX" "$OUT"
echo "=====API-QUERY-END====="
