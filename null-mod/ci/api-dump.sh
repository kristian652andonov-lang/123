#!/usr/bin/env bash
# Prints the public signatures of the Minecraft classes listed in ci/api-classes.txt.
# The build container this mod is written in cannot download Minecraft, so CI is the
# only place the real 1.21.1 class files exist; this keeps the code honest against them.
set -euo pipefail
cd "$(dirname "$0")/.."

GAME_JAR=""
while IFS= read -r jar; do
  if unzip -l "$jar" 2>/dev/null | grep -q "net/minecraft/client/Minecraft.class"; then
    GAME_JAR="$jar"
    break
  fi
done < <(find build/moddev -name '*.jar' ! -name '*sources*' 2>/dev/null | sort)

if [ -z "$GAME_JAR" ]; then
  echo "No Minecraft jar found under build/moddev:"; find build/moddev -name '*.jar' || true
  exit 1
fi
echo "Using $GAME_JAR"

mapfile -t CLASSES < <(grep -vE '^\s*(#|$)' ci/api-classes.txt)
OUT=build/api-dump.txt
: > "$OUT"
for cls in "${CLASSES[@]}"; do
  if ! javap -public -cp "$GAME_JAR" "$cls" >> "$OUT" 2>&1; then
    echo "!! missing: $cls" >> "$OUT"
  fi
done
sed -i -e 's/java\.lang\.//g' "$OUT"
echo "Dumped ${#CLASSES[@]} classes, $(wc -l < "$OUT") lines into $OUT"
grep '^!! missing' "$OUT" || true
