#!/usr/bin/env bash
# Answers the lookups listed in ci/api-queries.txt from build/api-flat.txt.
#   class <fully.qualified.Name>   every public/protected member of that class
#   grep <extended regex>          matching "Class :: member" lines
# Output is capped at MAX lines (default 1500).
set -euo pipefail
cd "$(dirname "$0")/.."
F=build/api-flat.txt
MAX=${MAX:-1500}
OUT=$(mktemp)
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) continue ;;
    class\ *)
      c="${line#class }"
      echo "### class $c" >> "$OUT"
      awk -v c="$c" 'index($0, c " :: ") == 1 { print; found = 1 } END { exit found ? 0 : 1 }' "$F" >> "$OUT" \
        || echo "!! no such class: $c" >> "$OUT"
      ;;
    grep\ *)
      re="${line#grep }"
      echo "### grep $re" >> "$OUT"
      grep -E -- "$re" "$F" | head -n 200 >> "$OUT" || echo "!! no match" >> "$OUT"
      ;;
    *) echo "!! unknown query: $line" >> "$OUT" ;;
  esac
done < ci/api-queries.txt
TOTAL=$(wc -l < "$OUT")
echo "=====API-QUERY-BEGIN===== ($TOTAL lines, showing up to $MAX)"
head -n "$MAX" "$OUT"
echo "=====API-QUERY-END====="
