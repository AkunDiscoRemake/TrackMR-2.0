"""Reject truncated/non-task downloads; records hash for CI provenance (not a signature)."""
import hashlib, pathlib, sys, zipfile
p = pathlib.Path(sys.argv[1])
assert 1_000_000 < p.stat().st_size < 20_000_000, 'Unexpected model size'
with zipfile.ZipFile(p) as z:
    assert {'hand_detector.tflite', 'hand_landmarks_detector.tflite'} <= set(z.namelist())
    assert z.testzip() is None
print('Hand model SHA-256:', hashlib.sha256(p.read_bytes()).hexdigest())
