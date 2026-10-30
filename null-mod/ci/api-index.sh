#!/usr/bin/env bash
# Builds build/api-flat.txt: every public/protected member of every Minecraft, Blaze3D and
# NeoForge class in the game jar, one per line as "fully.qualified.Class :: member".
# Used by the "NULL API query" workflow to answer signature questions without a local
# Minecraft install.
set -euo pipefail
cd "$(dirname "$0")/.."

GAME_JAR=""
while IFS= read -r jar; do
  if unzip -l "$jar" 2>/dev/null | grep -q "net/minecraft/client/Minecraft.class"; then
    GAME_JAR="$jar"; break
  fi
done < <(find build/moddev -name '*.jar' ! -name '*sources*' 2>/dev/null | sort)
[ -n "$GAME_JAR" ] || { echo "no game jar"; exit 1; }

CP="$GAME_JAR"
NEO_JAR=$(find ~/.gradle/caches -name "neoforge-*-universal.jar" 2>/dev/null | head -1 || true)
[ -n "$NEO_JAR" ] && CP="$CP:$NEO_JAR"

mkdir -p build/api-batches
rm -f build/api-batches/*
{
  unzip -Z1 "$GAME_JAR"
  [ -n "$NEO_JAR" ] && unzip -Z1 "$NEO_JAR"
} | grep -E '\.class$' \
  | grep -E '^(net/minecraft/|com/mojang/blaze3d/|com/mojang/math/|net/neoforged/)' \
  | grep -v -E 'package-info|module-info' \
  | sed -e 's/\.class$//' -e 's#/#.#g' | sort -u > build/api-all-classes.txt
split -l 300 build/api-all-classes.txt build/api-batches/b-

: > build/api-raw.txt
for f in build/api-batches/b-*; do
  # shellcheck disable=SC2046
  javap -protected -cp "$CP" $(cat "$f") >> build/api-raw.txt 2>/dev/null || true
done

awk '
  /^Compiled from/ { next }
  /^[a-z@].*(class|interface|enum|record) / && $0 !~ /^ / {
    line = $0
    if (match(line, /(class|interface|enum|record) [^ <{]+/)) {
      hdr = substr(line, RSTART, RLENGTH); split(hdr, parts, " "); cls = parts[2]
    }
    sub(/ *\{$/, "", line)
    print cls " :: " line
    next
  }
  /^  / { m = $0; sub(/^ +/, "", m); print cls " :: " m; next }
' build/api-raw.txt | sed -e 's/java\.lang\.//g' > build/api-flat.txt
echo "indexed $(wc -l < build/api-all-classes.txt) classes, $(wc -l < build/api-flat.txt) members"
