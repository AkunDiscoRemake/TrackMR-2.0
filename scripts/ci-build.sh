#!/usr/bin/env bash
set -euo pipefail
mkdir -p .cache
if ! ./gradlew --no-daemon :core:test :dev-api:jar :app:assembleDebug :runtime:assembleDebug :app:lintDebug :runtime:lintDebug 2>&1 | tee .cache/gradle-build.log; then
  # API-visible annotation even when the runner's log download endpoint is unavailable.
  python3 - <<'PY'
from pathlib import Path
lines = Path('.cache/gradle-build.log').read_text(errors='replace').splitlines()
interesting = [x for x in lines if x.startswith('e: ') or 'error:' in x or 'Could not' in x]
message = '\n'.join(interesting[-15:] + lines[-28:])[-12000:]
print('::error title=Android build failed::' + message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
PY
  exit 1
fi
