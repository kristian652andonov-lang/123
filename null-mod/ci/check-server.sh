#!/usr/bin/env bash
# Judges the dedicated server side of the multiplayer test.
set -uo pipefail
LOG=${1:-server/logs/latest.log}
echo "───────── server ─────────"
grep -a -E "NULL-AUTOTEST|Done \(|Stopping|NULL stage|ERROR|Exception" "$LOG" | head -n 80
status=0
grep -aq "Done (" "$LOG" || { echo "FAIL: the server never finished starting"; status=1; }
if grep -aq "NULL-AUTOTEST-FAIL" "$LOG"; then echo "FAIL: a server-side check failed"; status=1; fi
grep -aq "NULL-AUTOTEST: dimension self-check passed" "$LOG" || { echo "FAIL: the NULL dimension self-check did not pass"; status=1; }
if grep -a -E "^\s+at dev\.kristian\.nullmod" "$LOG" > /dev/null; then
  echo "FAIL: the dedicated server threw through NULL code:"
  grep -a -B30 -E "^\s+at dev\.kristian\.nullmod" "$LOG" | grep -a -E "Exception|Error|Caused by|at dev\.kristian" | head -n 60
  status=1
fi
if grep -a -E "NoClassDefFoundError|ClassNotFoundException|Attempted to load class .* for invalid dist" "$LOG" | grep -a -i "net/minecraft/client\|net.minecraft.client\|dev.kristian" > /dev/null; then
  echo "FAIL: client classes were loaded on the dedicated server"
  status=1
fi
[ $status -eq 0 ] && echo "OK: the dedicated server ran NULL without errors"
exit $status
