#!/usr/bin/env bash
# Judges the scripted client run (ClientAutoTest): it must reach DONE with no failures and
# without any exception that passes through NULL's code.
set -uo pipefail
LOG=${1:-client.log}
echo "───────── autotest steps ─────────"
grep -a "NULL-AUTOTEST" "$LOG" | sed -E 's/^.*(NULL-AUTOTEST)/\1/' | tail -n 400
grep -a -o -E "NULL desktop interference: (available|headless|failed|unsupported os)" "$LOG" | tail -n 1 | grep . || echo "NULL desktop interference: no result in the client log"
echo "───────── errors mentioning NULL ─────────"
grep -a -n -B2 -A12 -E "Exception|Error" "$LOG" | grep -a -E "dev\.kristian\.nullmod|Exception|Error" | head -n 120 || true
status=0
grep -aq "NULL-AUTOTEST: DONE" "$LOG" || { echo "FAIL: the scripted session never finished"; status=1; }
if grep -aq "NULL-AUTOTEST-FAIL" "$LOG"; then echo "FAIL: scripted steps failed"; status=1; fi
if grep -a -E "^\s+at dev\.kristian\.nullmod" "$LOG" > /dev/null; then
  echo "FAIL: exceptions were thrown through NULL code:"
  grep -a -B30 -E "^\s+at dev\.kristian\.nullmod" "$LOG" | grep -a -E "Exception|Error|Caused by|at dev\.kristian" | head -n 80
  status=1
fi
[ $status -eq 0 ] && echo "OK: the client ran every stage and quit cleanly"
exit $status
