"""Validate the real MediaPipe task, and optionally its exact copy inside an APK."""
import hashlib
import io
import json
import pathlib
import sys
import zipfile

MODEL_URL = 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task'
# Set from the verified official download; never update automatically on hash mismatch.
PIN_FILE = pathlib.Path(__file__).with_name('hand_model.sha256')

def verify(data):
    if not 1_000_000 < len(data) < 20_000_000:
        raise ValueError('Unexpected Hand Landmarker size')
    with zipfile.ZipFile(io.BytesIO(data)) as task:
        for name in ('hand_detector.tflite', 'hand_landmarks_detector.tflite'):
            model = task.read(name)
            if model[4:8] != b'TFL3':
                raise ValueError(f'Not a TFLite flatbuffer: {name}')
        if task.testzip() is not None:
            raise ValueError('Corrupt task ZIP')
    digest = hashlib.sha256(data).hexdigest()
    if PIN_FILE.exists() and digest != PIN_FILE.read_text().strip():
        raise ValueError('Official hand model SHA-256 mismatch')
    return {'source': MODEL_URL, 'bytes': len(data), 'sha256': digest}

if __name__ == '__main__':
    data = pathlib.Path(sys.argv[1]).read_bytes()
    result = verify(data)
    if len(sys.argv) > 2:
        with zipfile.ZipFile(sys.argv[2]) as apk:
            packaged = apk.read('assets/hand_landmarker.task')
            if packaged != data:
                raise ValueError('APK hand model differs from verified source')
            result['apk_asset'] = 'assets/hand_landmarker.task'
    print(json.dumps(result, sort_keys=True))
    # API-visible evidence even if the Actions log/ZIP storage endpoint is unavailable.
    print('::notice title=Hand Landmarker verified::' + json.dumps(result, sort_keys=True))
    pathlib.Path('.cache').mkdir(exist_ok=True)
    pathlib.Path('.cache/hand-model-provenance.json').write_text(json.dumps(result, indent=2)+'\n')
