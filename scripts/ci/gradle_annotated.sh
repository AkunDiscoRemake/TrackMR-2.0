#!/usr/bin/env bash
# Runs Gradle and turns compiler / build errors into GitHub annotations so failures are
# readable from the Checks UI and API without downloading the full log.
set -o pipefail
LOG="$(mktemp)"
./gradlew --stacktrace --console=plain "$@" 2>&1 | tee "$LOG"
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  n=0
  grep -E '^e: |error:|FAILED|What went wrong|Could not |CMake Error|undefined reference|Execution failed' "$LOG" | sort -u | head -45 | while IFS= read -r line; do
    msg="${line//$'%'/%25}"
    echo "::error title=build::${msg:0:900}"
  done
  # Context after "What went wrong".
  grep -A6 'What went wrong' "$LOG" | head -30 | tr '\n' ' ' | sed 's/^/::error title=gradle::/' | cut -c1-2000; echo
fi
exit "$code"
