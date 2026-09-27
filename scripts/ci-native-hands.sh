#!/usr/bin/env bash
set -uo pipefail
mkdir -p .cache
abis=$(adb shell getprop ro.product.cpu.abilist)
echo "::notice title=Android test ABI::$abis; native bridge=$(adb shell getprop ro.dalvik.vm.native.bridge)"
if [[ "$abis" != *arm64-v8a* ]]; then echo '::error::Emulator has no ARM64 translation'; exit 1; fi
./gradlew --no-daemon :tracking-validation:connectedDebugAndroidTest 2>&1 | tee .cache/native-hand-test.log
result=${PIPESTATUS[0]}
adb logcat -d 'TrackMR-hands:V' 'AndroidRuntime:E' '*:S' > .cache/native-hand-logcat.txt || true
if [ "$result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
s=Path('.cache/native-hand-test.log').read_text(errors='replace')
lines=s.splitlines()
errors=[x for x in lines if x.startswith('e: ') or 'error:' in x or 'Could not' in x]
message='\n'.join(errors[-15:]+lines[-35:])+'\n'+Path('.cache/native-hand-logcat.txt').read_text(errors='replace')[-4500:]
print('::error title=Native hands failed::'+message[-14000:].replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PY
fi
exit "$result"
