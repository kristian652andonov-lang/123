#!/usr/bin/env bash
# Parses Java files without Minecraft on the classpath and reports only real syntax errors.
# (Everything that needs Minecraft/NeoForge types fails with "cannot find symbol" or
# "package ... does not exist"; those lines are filtered out because CI checks them.)
# Usage: tools/syntax_check.sh [files or directories...]   (default: src/main/java)
set -uo pipefail
cd "$(dirname "$0")/.."
targets=("$@")
[ ${#targets[@]} -eq 0 ] && targets=(src/main/java)
mapfile -t files < <(find "${targets[@]}" -name '*.java' 2>/dev/null)
[ ${#files[@]} -eq 0 ] && { echo "no java files"; exit 0; }
out=$(mktemp -d)
javac -proc:none -Xmaxerrs 100000 -d "$out" "${files[@]}" 2>&1 \
  | grep -E '^[^ ].*\.java:[0-9]+: error:' \
  | grep -vE 'error: (cannot find symbol|package .* does not exist|cannot access|static import only from classes and interfaces)' \
  | grep -vE 'error: (method does not override or implement a method from a supertype|incompatible types|cannot infer type|no suitable|is not abstract and does not override|non-static|cannot be applied|could not be|not a functional interface|invalid method reference|bad operand|reference to .* is ambiguous|unreported exception|cannot inherit|does not take parameters|is not applicable|cannot select|unexpected type|cannot return|missing return)' \
  | sort -u
status=${PIPESTATUS[0]}
rm -rf "$out"
echo "syntax check done (${#files[@]} files)"
