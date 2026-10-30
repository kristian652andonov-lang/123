#!/usr/bin/env bash
# Summarises the GameTest server run and fails on any failed test.
set -uo pipefail
LOG=${1:-gametest.log}
echo "───────── GameTest results ─────────"
grep -a -E "GAME TEST|required tests|optional tests|passed|failed|::|Exception" "$LOG" | grep -a -v "DEBUG" | tail -n 120
status=0
if grep -a -E "required tests? failed|tests? failed :\(" "$LOG" > /dev/null; then echo "FAIL: GameTests failed"; status=1; fi
grep -a -q -E "All [0-9]+ required tests passed" "$LOG" || { echo "FAIL: no 'All required tests passed' line"; status=1; }
exit $status
