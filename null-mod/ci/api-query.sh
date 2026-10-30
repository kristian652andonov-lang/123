#!/usr/bin/env bash
# Answers the lookups listed in ci/api-queries.txt from build/api-flat.txt.
#   class <fully.qualified.Name>   every public/protected member of that class
#   grep <extended regex>          matching "Class :: member" lines (max 300 per query)
# Lookups are spread over SHARDS parallel jobs; this job answers line numbers where
# (line % SHARDS) == SHARD. Output is capped at MAX lines.
set -euo pipefail
cd "$(dirname "$0")/.."
F=build/api-flat.txt
MAX=${MAX:-6000}
SHARD=${SHARD:-0}
SHARDS=${SHARDS:-1}
OUT=$(mktemp)
n=-1
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) continue ;;
  esac
  n=$((n + 1))
  [ $((n % SHARDS)) -eq "$SHARD" ] || continue
  case "$line" in
    class\ *)
      c="${line#class }"
      echo "### class $c" >> "$OUT"
      awk -v c="$c" 'index($0, c " :: ") == 1 { print "  " substr($0, length(c) + 5); found = 1 } END { exit found ? 0 : 1 }' "$F" >> "$OUT" \
        || echo "!! no such class: $c" >> "$OUT"
      ;;
    grep\ *)
      re="${line#grep }"
      echo "### grep $re" >> "$OUT"
      grep -E -- "$re" "$F" | head -n 300 >> "$OUT" || echo "!! no match" >> "$OUT"
      ;;
    *) echo "!! unknown query: $line" >> "$OUT" ;;
  esac
done < ci/api-queries.txt
TOTAL=$(wc -l < "$OUT")
echo "=====API-QUERY-BEGIN===== shard $SHARD/$SHARDS ($TOTAL lines, showing up to $MAX)"
head -n "$MAX" "$OUT"
echo "=====API-QUERY-END====="
