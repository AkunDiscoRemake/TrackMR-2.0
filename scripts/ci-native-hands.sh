#!/usr/bin/env bash
set -uo pipefail
mkdir -p .cache
abis=$(adb shell getprop ro.product.cpu.abilist)
echo "::notice title=Android test ABI::$abis; native bridge=$(adb shell getprop ro.dalvik.vm.native.bridge)"
if [[ "$abis" != *arm64-v8a* ]]; then echo '::error::Emulator has no ARM64 translation'; exit 1; fi
./gradlew --no-daemon :tracking-validation:connectedDebugAndroidTest 2>&1 | tee .cache/native-hand-test.log
result=${PIPESTATUS[0]}
adb logcat -d -v threadtime > .cache/native-hand-logcat.txt || true
if [ "$result" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
s=Path('.cache/native-hand-test.log').read_text(errors='replace')
lines=s.splitlines()
errors=[x for x in lines if x.startswith('e: ') or 'error:' in x or 'Could not' in x]
logs=Path('.cache/native-hand-logcat.txt').read_text(errors='replace').splitlines()
relevant=[x for x in logs if any(t in x for t in ('TrackMR-hands',' F ',' E AndroidRuntime','Fatal signal','crash_dump'))]
message='\n'.join(errors[-15:]+lines[-35:])+'\n'+'\n'.join(relevant)[-9500:]
print('::error title=Native hands failed::'+message[-14000:].replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
PY
else
  python3 - <<'PY'
from pathlib import Path
lines=Path('.cache/native-hand-logcat.txt').read_text(errors='replace').splitlines()
message='\n'.join(x for x in lines if 'TrackMR-hands' in x)[-8000:]
print('::notice title=Native inference passed::'+message.replace('%','%25').replace('\n','%0A'))
PY
fi
exit "$result"
